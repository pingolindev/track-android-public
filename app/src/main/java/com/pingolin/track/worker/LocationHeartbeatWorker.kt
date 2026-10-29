/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Track.
 *
 * Pingolin Track is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Track is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.track.worker

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.track.BuildConfig
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationAccuracy
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.service.LocationSendDecision
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Periodic heartbeat worker that sends a location update every 15 minutes
 * when the foreground LocationTrackingService is not running.
 *
 * This ensures the server receives periodic location updates even when:
 * - The foreground service was killed by the OS
 * - The device is in Doze mode
 * - The user hasn't opened the app recently
 *
 * If the foreground service is already running, this worker skips silently.
 * If the user has paused tracking, it skips before taking any fix — see
 * [heartbeatGate].
 */
@HiltWorker
class LocationHeartbeatWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val locationRepository: LocationRepository,
    private val circleRepository: CircleRepository,
    private val sessionManager: SessionManager,
    private val locationProvider: LocationProvider,
    private val circleDao: CircleDao,
    private val privacySettingDao: PrivacySettingDao,
    private val newestOwnFix: com.pingolin.track.location.NewestOwnFix,
    /** This phone's battery level, for the sends made here. */
    private val battery: com.pingolin.track.util.BatteryLevel,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LocationHeartbeatWorker"
        private const val PERIODIC_WORK_NAME = "location_heartbeat_periodic"
        private const val LOCATION_TIMEOUT_MS = 15_000L
        /**
         * Staleness watchdog: if the foreground service is running but
         * hasn't landed a successful send in this long, override the
         * "skip because FG is alive" short-circuit and fire a heartbeat
         * send anyway. Guards against situations where the service is
         * technically alive but its adaptive polling has gone silent
         * (GPS stuck, dedup filter, Doze bucketing of the FLP callback,
         * etc.) — circle members would otherwise see an ever-growing
         * "X hours ago" until the process gets killed and restarted.
         *
         * 45 minutes, because a healthy foreground service advances the
         * shared clock on its liveness check-in cadence rather than on the
         * user's send interval. In battery saver that cadence is the batch
         * interval, up to an hour, so the threshold used there is
         * [heartbeatStaleThresholdMsFor]; this is its real-time value and
         * its floor.
         *
         * The width costs little: when this DOES fire it makes the same
         * moved / not-moved decision as the service (see doWork), so a
         * stalled stationary device is covered by a check-in rather than by
         * a history row.
         */
        private const val STALE_SEND_THRESHOLD_MS = 45L * 60L * 1000L

        /**
         * How far the device must have moved since its last HISTORY send for this
         * heartbeat to write another one. Below it, the heartbeat still checks in —
         * the device is alive and could have sent a position, which is what the
         * green dot and the inactivity alerts actually mean — but writes no history.
         *
         * Deliberately a constant here rather than the user's own distance setting.
         * The heartbeat's fix is the app's only genuinely coarse location request
         * (BALANCED, see the comment on the currentLocation call below), routinely
         * hundreds of metres on the libre flavour. Judging it against a threshold
         * the user may have set to 50 m would turn that provider's own noise into a
         * stream of history rows from a phone on a table — the exact failure this
         * threshold exists to stop. 250 m is above what a wifi/cell fix drifts by
         * while standing still, and a device that really has moved 250 m gets a
         * history row on this tick.
         */
        private const val HEARTBEAT_HISTORY_DISTANCE_METERS = 250f

        /**
         * Schedule the periodic heartbeat worker.
         * Runs every 15 minutes with a 5-minute flex window.
         */
        fun schedule(context: Context) {
            // Network is the only constraint, and that is deliberate. Do NOT add
            // setRequiresBatteryNotLow(true): once an idle, unplugged device drifts past the
            // ~15% "battery low" threshold, that constraint and Doze together defer every
            // subsequent tick — the 15-minute interval can stretch to several hours with no
            // send landing on the server. A heartbeat is one encrypted sub-1KB message, a
            // rounding error against the cost of the user silently going dark.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val heartbeatRequest = PeriodicWorkRequestBuilder<LocationHeartbeatWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )
                .build()

            // The policy must be UPDATE, and the choice among the three is load-bearing.
            // MainActivity.onCreate calls schedule() on every launch, so REPLACE would restart
            // the periodic timer from zero each time: a user who opens the app every 5-15
            // minutes pushes the 15-minute heartbeat forward on every session, it effectively
            // never fires, and their own marker goes hours stale. KEEP avoids that but
            // silently ignores constraint and interval changes shipped in an app upgrade.
            // UPDATE preserves the existing next-run clock while still applying the new request.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                heartbeatRequest
            )
            Log.d(TAG, "Heartbeat worker scheduled")
        }

        /**
         * Cancel the heartbeat worker.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }

        /**
         * The same moved / not-moved rule the foreground service applies
         * ([LocationTrackingService.decideSend]), with this worker's own threshold
         * and without the NONE case.
         *
         * There is no NONE, deliberately: this worker only runs when the foreground
         * service is dead or has gone silent, and it is then the only thing keeping
         * the device visible. Its whole reason to exist is that saying nothing is
         * the failure. It also ignores the user's time-based / distance-only
         * toggles — see the target-resolution comment in doWork for why. For a
         * distance-only user, the 15-minute heartbeat writes history only when they
         * have actually moved, which is close to what that toggle asks for.
         *
         * [movedMeters] is null when this install has no recorded history send yet.
         * That first send anchors the baseline, so it has to be a real one.
         */
        internal fun heartbeatDecision(
            movedMeters: Float?,
            thresholdMeters: Float = HEARTBEAT_HISTORY_DISTANCE_METERS,
        ): LocationSendDecision =
            if (movedMeters == null || movedMeters >= thresholdMeters) LocationSendDecision.HISTORY
            else LocationSendDecision.LIVENESS

        /**
         * Metres between the last history-sent position and [location], or null when
         * there is no baseline. Uses the platform's geodesic helper, the same one
         * `Location.distanceTo` is built on, because the baseline is stored as bare
         * coordinates rather than as a Location.
         */
        internal fun movedSinceLastHistorySend(
            last: SessionManager.SentPosition?,
            location: android.location.Location,
        ): Float? {
            if (last == null) return null
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                last.latitude, last.longitude, location.latitude, location.longitude, results
            )
            return results[0]
        }

        // Slack on top of two battery-saver batch intervals before a healthy saver
        // service's silence counts as a stall. See heartbeatStaleThresholdMsFor.
        private const val SAVER_STALE_SLACK_MS = 15L * 60L * 1000L

        /**
         * How long the foreground service may go without a successful send before
         * this worker covers for it.
         *
         * 45 minutes in real-time mode. In battery saver the service's own cadence
         * IS the batch interval (5/15/30/60 minutes), so at the 30- and 60-minute
         * chips a fixed 45 minutes would call a healthy saver service "stale"
         * between every pair of its own sends and add a redundant heartbeat fix +
         * upload on top. Two batch intervals plus [SAVER_STALE_SLACK_MS] keeps
         * "missed one send and its retry" as the definition of stuck.
         */
        internal fun heartbeatStaleThresholdMsFor(batterySaver: Boolean, saverIntervalMinutes: Int): Long =
            if (!batterySaver) STALE_SEND_THRESHOLD_MS
            else maxOf(
                STALE_SEND_THRESHOLD_MS,
                2L * saverIntervalMinutes.coerceAtLeast(1) * 60_000L + SAVER_STALE_SLACK_MS,
            )

        /**
         * Whether this tick should take a fix and send it, and if so whether it is
         * standing in for a stalled foreground service. Pure, so the order of the
         * gates is pinned by a test rather than by reading doWork.
         *
         * PAUSE IS A GATE, and it comes before anything that takes a fix: checked only
         * around restarting the foreground service, a paused user's phone would go on
         * taking a location fix and uploading it to every circle about every 15 minutes
         * — "Pause tracking" would share their position all the same.
         *
         * And it asks [wanted], the one rule every path that brings tracking back asks
         * (SessionManager.shouldBeTracking): signed in, tracking on, no device-wide
         * pause, and an account an admin has approved (an install updated from an
         * earlier version may have scheduled this tick for an applicant it couldn't
         * know about). A stalled service is no exception: the service turns tracking on
         * whenever it starts, and only a sign-out takes it off, so a running service
         * with tracking off can't be a stale preference.
         */
        internal fun heartbeatGate(
            fgsRunning: Boolean,
            lastSendAgeMs: Long,
            staleThresholdMs: Long,
            loggedIn: Boolean,
            paused: Boolean,
            wanted: Boolean,
        ): HeartbeatGate {
            val stalled = fgsRunning && lastSendAgeMs >= staleThresholdMs
            if (fgsRunning && !stalled) return HeartbeatGate.SKIP_SERVICE_HEALTHY
            if (!loggedIn) return HeartbeatGate.SKIP_NOT_LOGGED_IN
            if (paused) return HeartbeatGate.SKIP_PAUSED
            if (!wanted) return HeartbeatGate.SKIP_NOT_WANTED
            return if (stalled) HeartbeatGate.RUN_FOR_STALLED_SERVICE else HeartbeatGate.RUN
        }
    }

    /** See [heartbeatGate]. */
    internal enum class HeartbeatGate {
        SKIP_SERVICE_HEALTHY,
        SKIP_NOT_LOGGED_IN,
        SKIP_PAUSED,
        SKIP_NOT_WANTED,
        RUN,
        RUN_FOR_STALLED_SERVICE,
    }

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        // Foreground service alive? Skip — UNLESS our last successful send
        // is older than the staleness threshold, in which case the FG
        // service has silently stalled and we need to cover for it. The
        // watchdog fires at most once per worker tick (15 min) so even
        // pathological stall scenarios stay bounded. See heartbeatGate for
        // the whole order, including the pause gate.
        val lastSend = sessionManager.getLastSuccessfulSendAt()
        val sendAgeMs = if (lastSend > 0) System.currentTimeMillis() - lastSend else Long.MAX_VALUE
        val gate = heartbeatGate(
            fgsRunning = LocationTrackingService.isRunning,
            lastSendAgeMs = sendAgeMs,
            staleThresholdMs = heartbeatStaleThresholdMsFor(
                batterySaver = sessionManager.isBatterySaverEnabled(),
                saverIntervalMinutes = sessionManager.getBatterySaverInterval(),
            ),
            loggedIn = sessionManager.isLoggedIn(),
            paused = sessionManager.isTrackingPaused(),
            wanted = sessionManager.shouldBeTracking(),
        )
        when (gate) {
            HeartbeatGate.SKIP_SERVICE_HEALTHY -> {
                Log.d(TAG, "Foreground service is running (last send ${sendAgeMs / 1000}s ago), skipping heartbeat")
                return Result.success()
            }
            HeartbeatGate.SKIP_NOT_LOGGED_IN -> {
                Log.d(TAG, "Not logged in, skipping heartbeat")
                return Result.success()
            }
            HeartbeatGate.SKIP_PAUSED -> {
                // Before any fix, before the stream: a paused user shares nothing.
                Log.d(TAG, "Tracking paused by the user, skipping heartbeat")
                return Result.success()
            }
            HeartbeatGate.SKIP_NOT_WANTED -> {
                // Before any fix and any start, so the send below never goes out either.
                Log.d(TAG, "Tracking not on, or the account not approved yet, skipping heartbeat")
                return Result.success()
            }
            HeartbeatGate.RUN_FOR_STALLED_SERVICE -> {
                Log.w(TAG, "Foreground service running but last successful send was ${sendAgeMs / 1000}s ago — firing staleness-guard heartbeat")
            }
            HeartbeatGate.RUN -> Unit
        }

        // If tracking *should* be on and the foreground service is dead (OS killed
        // it, app was swiped, Doze, etc.), relaunch it so the persistent tracking
        // notification comes back and real-time streaming resumes. FGS with
        // FOREGROUND_SERVICE_TYPE_LOCATION is allowed from workers when the app
        // holds ACCESS_BACKGROUND_LOCATION. Fall through to the one-shot
        // heartbeat send anyway — the service may take a moment to initialize,
        // and this guarantees at least one fresh fix lands.
        //
        // Re-check the permission rather than assuming it: the user can revoke it at any
        // time and a reinstall resets it. Starting the FGS without it is pointless — the
        // service stops itself immediately — and can take the whole process down with
        // RemoteServiceException, once per heartbeat.
        if (hasLocationPermission()) {
            try {
                val intent = android.content.Intent(
                    applicationContext,
                    LocationTrackingService::class.java
                ).apply { action = LocationTrackingService.ACTION_START }
                androidx.core.content.ContextCompat.startForegroundService(
                    applicationContext,
                    intent
                )
                Log.i(TAG, "Restarted LocationTrackingService from heartbeat worker")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restart LocationTrackingService from worker: ${e.message}")
            }
        }

        return withTimeoutOrNull(120_000L) {
            try {
                // The timeout is what cancels the request: LocationProvider
                // ties the underlying cancellation to the coroutine, so a
                // fix that does not arrive in LOCATION_TIMEOUT_MS also stops
                // costing power.
                // BALANCED here, as in the foreground service's
                // battery-saver and STILL polls
                // (LocationTrackingService.pollProfileFor); this one runs
                // for every user on this worker's schedule. What it
                // resolves to depends on the flavour, and the difference
                // matters:
                //
                //   gms   FusedLocationProvider:  PRIORITY_BALANCED_POWER_ACCURACY
                //         — documented by Google as roughly 100 m, which is exactly
                //           where place-alert rings START (dialog_create_place_alert
                //           valueFrom="100"). Nominal is a target, not a bound.
                //   libre PlatformLocationProvider: NETWORK_PROVIDER FIRST, then GPS
                //         — wifi/cell positioning, routinely hundreds of metres.
                //
                // The fix from here reaches PlaceAlertEvaluator through
                // LocationRepository.persistAndEvaluate, so it is judged
                // against those rings. That is why PlaceAlertRules.canResolve
                // exists and why it is not theoretical: on libre this call
                // can hand a 500 m fix to a 100 m geofence. Do not
                // "simplify" the gate away.
                val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                    try {
                        locationProvider.currentLocation(LocationAccuracy.BALANCED)
                    } catch (e: Exception) {
                        Log.w(TAG, "getCurrentLocation failed, trying getLastLocation", e)
                        null
                    }
                }

                val fetched = location ?: try {
                    locationProvider.lastLocation()
                } catch (e: Exception) {
                    Log.w(TAG, "getLastLocation also failed", e)
                    null
                }

                if (fetched == null) {
                    Log.w(TAG, "No location available for heartbeat")
                    return@withTimeoutOrNull Result.success()
                }
                // Or a newer one another path of this app took (send-now, a
                // check-in...): a check-in from here must not put the person
                // back at an older place, stamped now (NewestOwnFix).
                val finalLocation = newestOwnFix.newerOf(fetched)

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Heartbeat location: ${finalLocation.latitude}, ${finalLocation.longitude}")
                }

                if (!locationRepository.ensureDeviceRegistered()) {
                    Log.w(TAG, "Device registration failed, storing locally")
                    locationRepository.storeLocalLocation(finalLocation)
                    return@withTimeoutOrNull Result.retry()
                }

                // Targets must match what LocationTrackingService sends to: the self-circle
                // plus every shared circle that isn't paused and hasn't had realtime sharing
                // switched off. Sending to SELF alone is not enough — a user in Distance-only
                // mode sitting still (no 100m crossing) keeps their own history fresh while
                // going dark on every circle member's map, green to grey after 15 minutes with
                // "last seen" growing until they move or open the app. The privacy filtering
                // below mirrors LocationTrackingService.handleLocationUpdate, so pausing
                // sharing really does pause it for heartbeats too.
                val targets = mutableListOf<String>().apply {
                    add(LocationRepository.SELF_CIRCLE_ID)
                    try {
                        val circles = circleDao.getAll()
                        if (circles.isNotEmpty()) {
                            val circleIds = circles.map { it.id }
                            // Prime circles with no local privacy row so an approximate/
                            // paused/realtime-off circle isn't fail-open'd on a headless
                            // heartbeat (fresh boot / FCM wake before any privacy sync).
                            // GET + local mirror only, never an outbound PUT.
                            var privacy = privacySettingDao.getAllForCircles(circleIds)
                                .associateBy { it.circleId }
                            val missing = circleIds - privacy.keys
                            if (missing.isNotEmpty()) {
                                missing.forEach { circleRepository.ensurePrivacyPrimed(it) }
                                privacy = privacySettingDao.getAllForCircles(circleIds)
                                    .associateBy { it.circleId }
                            }
                            val now = System.currentTimeMillis()
                            circles.forEach { circle ->
                                val s = privacy[circle.id]
                                // Unknown posture (priming failed) -> fail closed: skip
                                // this circle rather than send it a possibly-exact fix.
                                if (s == null) {
                                    Log.w(TAG, "Heartbeat skipping ${circle.id} - privacy posture unknown (unprimed), fail closed")
                                    return@forEach
                                }
                                // Mode AND deadline — see [isSharingPausedAt]. An
                                // indefinite pause carries a NULL deadline, so a
                                // deadline-only test would keep the heartbeat uploading
                                // to a circle the user had paused.
                                val paused = s.isSharingPausedAt(now)
                                val realtimeOff = s.shareRealtime == false
                                if (!paused && !realtimeOff) add(circle.id)
                            }
                        }
                    } catch (e: Exception) {
                        // Fall back to self-only if circle lookup fails —
                        // the staleness guard is still strictly better than
                        // zero sends.
                        Log.w(TAG, "Circle target resolution failed, falling back to self-only: ${e.message}")
                    }
                }
                // History only if the device actually moved since the last one; a
                // check-in otherwise. Without this the heartbeat alone would write
                // four history rows an hour, per circle, from a phone left on a
                // table — the foreground service is the bigger source but it is
                // not the only one, and a device whose service the OS killed sends
                // nothing BUT these.
                val moved = movedSinceLastHistorySend(
                    sessionManager.getLastHistorySentPosition(), finalLocation
                )
                val decision = heartbeatDecision(moved)
                // A check-in stamps its fix now: never one older than a
                // position already sent, or the person is put back where they
                // were. The circle keeps the newer one with its real age.
                if (decision == LocationSendDecision.LIVENESS && newestOwnFix.goesBack(finalLocation)) {
                    Log.i(TAG, "Heartbeat check-in skipped: a newer position was already sent")
                    return@withTimeoutOrNull Result.success()
                }
                Log.d(TAG, "Heartbeat $decision to ${targets.size} target(s): $targets (moved=${moved?.toInt()?.toString() ?: "no baseline"}m)")
                // With this phone's battery level, as the service's sends carry it:
                // while the service is dead these heartbeats are all a circle hears,
                // and without a level a low-battery alert would stay quiet exactly
                // when a dying phone's service was killed. Whether each circle gets it
                // is its own setting, applied where its copy is sealed
                // (LocationRepository.sendEnvelope: `targetSharesBattery`).
                val batteryLevel = battery.now()
                val result = if (decision == LocationSendDecision.LIVENESS) {
                    locationRepository.sendEnvelopeLivenessRefresh(targets, finalLocation, batteryLevel)
                } else {
                    locationRepository.sendEnvelopeLocation(targets, finalLocation, batteryLevel)
                }

                if (result.isSuccess) {
                    Log.i(TAG, "Heartbeat $decision sent successfully")
                    // Advance the shared "last successful send" clock so the
                    // staleness watchdog on the next tick doesn't immediately
                    // re-fire. Same timestamp LocationTrackingService writes on
                    // its success branches. A check-in advances it too: it is a
                    // successful send, and proving the device is alive is the whole
                    // thing the watchdog is watching for.
                    sessionManager.setLastSuccessfulSendAt(System.currentTimeMillis())
                    // Only a history send moves the baseline. Advancing it on a
                    // check-in would let a slowly-drifting device travel any distance
                    // without ever crossing the threshold.
                    if (decision == LocationSendDecision.HISTORY) {
                        sessionManager.setLastHistorySentPosition(
                            finalLocation.latitude, finalLocation.longitude, System.currentTimeMillis()
                        )
                    }
                    // Take the opportunity to drain any backlog while we know
                    // the stream is hot. syncPendingLocations is cheap when empty.
                    try {
                        locationRepository.syncPendingLocations()
                    } catch (e: Exception) {
                        Log.w(TAG, "Heartbeat post-send flush failed: ${e.message}")
                    }
                    Result.success()
                } else {
                    val err = result.exceptionOrNull()
                    val msg = err?.message.orEmpty()
                    Log.w(TAG, "Heartbeat $decision failed: $msg")
                    // RETRY yes, REPLAY no — and the two differ by which
                    // fix is sent.
                    //
                    // Both kinds still return Result.retry() below, and
                    // that is a genuine retry: WorkManager re-runs doWork
                    // from the top, which takes a FRESH fix and re-makes
                    // the moved / not-moved decision on it. Nothing stale
                    // is re-asserted.
                    //
                    // What differs is the durable queue. A failed HISTORY
                    // send is persisted and replayed: it is filed under the
                    // time it was taken, so it is still true whenever it
                    // lands. A failed check-in is dropped, because a
                    // check-in claims "I am here NOW" and the queue can
                    // hold it for hours. storeLocalLocation is the call
                    // that does the persisting — it inserts into
                    // pendingLocationDao as well as the local table — so
                    // it is what has to be skipped.
                    //
                    // The key-heal below still runs for both: a check-in
                    // fails on a missing circle key for exactly the same
                    // reason a real send does.
                    if (decision == LocationSendDecision.HISTORY) {
                        locationRepository.storeLocalLocation(finalLocation)
                    }
                    // A missing circle key is unambiguously fixable: pull a
                    // fresh wrap from the server and the next heartbeat will
                    // encrypt cleanly. Without this, a device whose local
                    // key store got wiped (reinstall, EncryptedSharedPreferences
                    // master-key rotation) stays silent until the user opens
                    // the app, which shows up as a gap of many hours.
                    // Don't depend on LocationTrackingService's self-heal:
                    // that path needs 3 consecutive failures and a 5-min
                    // cooldown, which the heartbeat's 15-min cadence drags
                    // out to an hour minimum even before Doze enters.
                    if (err is com.pingolin.track.domain.repository.LocationRepository.CircleKeyUnavailableException ||
                        msg.contains("Key not available", ignoreCase = true) ||
                        msg.contains("No circle key", ignoreCase = true) ||
                        msg.contains("Missing key", ignoreCase = true)) {
                        try {
                            val heal = circleRepository.importCircleKeys()
                            if (heal.isSuccess) {
                                Log.i(TAG, "Heartbeat key-heal: imported ${heal.getOrNull()} circle keys; next tick should encrypt")
                            } else {
                                Log.w(TAG, "Heartbeat key-heal failed: ${heal.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Heartbeat key-heal threw: ${e.message}")
                        }
                    }
                    Result.retry()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat worker failed", e)
                Result.retry()
            }
        } ?: run {
            Log.w(TAG, "Heartbeat worker timed out after 2 minutes, will retry")
            Result.retry()
        }
    }

    /**
     * The same rule the service it restarts gates on, by calling it rather
     * than by re-stating it: a copy that accepted a coarse-only grant, against
     * a service that refuses one, would spawn a service which stops itself
     * immediately, once per heartbeat, forever.
     */
    private fun hasLocationPermission(): Boolean =
        com.pingolin.track.service.LocationPolicy.hasPreciseLocation(applicationContext)
}
