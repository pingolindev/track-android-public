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

package com.pingolin.track.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.os.BatteryManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pingolin.track.BuildConfig
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.domain.repository.BoundaryRepository
import uniffi.pingolin_mobile.BoundaryCrossingType
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationAccuracy
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.LocationUpdateHandle
import com.pingolin.track.location.LocationUpdateListener
import com.pingolin.track.location.LocationUpdateRequest
import com.pingolin.track.receiver.WatchdogAlarmReceiver
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.BoundaryMonitorCallbackImpl
import com.pingolin.track.util.MonitoringManager
import com.pingolin.track.util.MotionDetectionManager
import com.pingolin.track.util.MotionState
import com.pingolin.track.util.NotSharingNotice
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.TrackingEvent
import com.pingolin.track.util.TrackingHealth
import com.pingolin.track.util.TrackingHealthNotifier
import com.pingolin.track.util.TrackingStopReason
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.util.storeRefusal
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * What a usable fix should produce.
 *
 * A green dot is honest if the phone is alive and COULD have sent a position — not
 * only if it sent one worth keeping. Those are two different claims, and one
 * message must not make both: every online device would write a history row per
 * circle per send interval whether or not it had moved, and a phone on a desk
 * overnight is what actually fills the location store.
 *
 * So a fix is judged once: [HISTORY] when the device has genuinely moved,
 * [LIVENESS] when it has not but is due to say so, [NONE] when it has nothing to
 * add. See [LocationTrackingService.decideSend].
 */
internal enum class LocationSendDecision {
    /** Moved far enough to be worth a row in history: the normal send path. */
    HISTORY,

    /** Not moved. Say "I am here now" — latest position only, no history row. */
    LIVENESS,

    /** Neither. Say nothing. */
    NONE,
}

/**
 * What the service asks the location provider for: how often, and how hard to try.
 * See [LocationTrackingService.pollProfileFor].
 */
internal data class PollProfile(
    val intervalMs: Long,
    val accuracy: LocationAccuracy,
)

/**
 * What a fix too rough for the real-time accuracy gate still says, when it
 * arrives while the phone is STILL and polling at [LocationAccuracy.BALANCED].
 * See [LocationTrackingService.coarseStillFixVerdict].
 */
internal enum class CoarseStillVerdict {
    /** Within reach of the last precise fix: the phone has not gone anywhere. */
    CONFIRMS_PRESENCE,

    /** Too far from it, or there is no precise fix to compare with: take one. */
    SUGGESTS_DEPARTURE,

    /** Too rough to say either — a cell fix of several kilometres. Ignore it. */
    TOO_ROUGH,
}

@AndroidEntryPoint
class LocationTrackingService : Service() {


    @Inject lateinit var locationRepository: LocationRepository
    @Inject lateinit var boundaryRepository: BoundaryRepository
    @Inject lateinit var circleRepository: CircleRepository
    @Inject lateinit var circleDao: CircleDao
    @Inject lateinit var placeAlertDao: PlaceAlertDao
    @Inject lateinit var privacySettingDao: PrivacySettingDao
    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var monitoringManager: MonitoringManager
    @Inject lateinit var alertRulesReload: com.pingolin.track.domain.repository.AlertRulesReload
    @Inject lateinit var alertHandler: AlertHandler
    @Inject lateinit var motionDetectionManager: MotionDetectionManager
    @Inject lateinit var uxEventBus: com.pingolin.track.util.UxEventBus
    @Inject lateinit var destinationRepository: com.pingolin.track.domain.repository.DestinationRepository
    @Inject lateinit var destinationDao: com.pingolin.track.data.local.dao.DestinationDao
    @Inject lateinit var locationProvider: LocationProvider
    @Inject lateinit var newestOwnFix: com.pingolin.track.location.NewestOwnFix

    /** Non-null exactly while subscribed to periodic fixes; the cancel handle is
     *  also the "are we subscribed?" flag the re-subscription paths guard on. */
    private var locationUpdates: LocationUpdateHandle? = null
    private var isTracking = false
    @Volatile private var isBatterySaverMode = false
    // What the ongoing notification may say about sharing (sharingNowFor); "active" until known.
    @Volatile private var sharingNow = SharingNow.SHARING

    // Monotonic throttle for the periodic boundary-set reload; see refreshBoundaries for
    // why the reload has to keep happening after the service is already up.
    //
    // 15 minutes, not 2. The reload is a full paginated GET /places with every place
    // decrypted, a GET of place alerts and a roster fetch per alert circle, and it is
    // checked on every fix — so at 2 minutes a STILL phone (one fix per 5-minute poll)
    // would pay for it on every single fix, all night, for boundaries that had not
    // changed. A place that gains an alert elsewhere is picked up within 15 minutes
    // here, and immediately on the paths that know something changed: any ACTION_START
    // on a running service (the app coming to the foreground, a watchdog or push
    // restart) and ACTION_UPDATE_SETTINGS (circles changed) both reload straight away.
    @Volatile private var lastBoundaryReloadElapsedMs = 0L
    private val boundaryReloadIntervalMs = 15 * 60 * 1000L // 15 min

    // Dedicated handler thread for location callbacks to avoid blocking main thread
    private var locationHandlerThread: HandlerThread? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastSentLocation: android.location.Location? = null
    private var isDistanceTrackingEnabled = false
    private var distanceThresholdMeters = 100f

    // Current polling profile pushed to the LocationProvider. Decoupled from the
    // user's send cadence; recomputed whenever motion state, battery tier, the
    // power-save mode or battery saver changes. See pollProfileFor.
    @Volatile private var currentPollIntervalMs: Long = POLL_UNKNOWN_MS
    @Volatile private var currentPollAccuracy: LocationAccuracy = LocationAccuracy.HIGH

    // Motion state mirror (observed from MotionDetectionManager). Drives polling profile
    // and trip-start detection.
    @Volatile private var motionState: MotionState = MotionState.UNKNOWN

    // Anchor for the stationary inference that seeds STILL when activity
    // recognition never reports one — see shouldInferStill.
    private var stillAnchor: android.location.Location? = null
    private var stillAnchorAtMs: Long = 0L

    // The GPS-evidence way OUT of a MOVING the motion detector never ends — see
    // shouldHoldStillCadenceWhileMoving. When MOVING began, the spot the fixes have
    // been holding to since, and whether that evidence currently holds the poll at
    // the STILL cadence.
    @Volatile private var movingSinceMs: Long = 0L
    private var movingAnchor: android.location.Location? = null
    private var movingAnchorAtMs: Long = 0L
    @Volatile private var stationaryWhileMoving: Boolean = false

    // Last fix that passed the inbound guards — the payload a liveness check-in
    // re-sends — or a newer one a path outside this service took (NewestOwnFix, see
    // adoptNewerOwnFix). Distinct from lastSentLocation, which is the distance baseline.
    @Volatile private var lastKnownFix: android.location.Location? = null

    // Elapsed-realtime (nanos) of the last coarse STILL fix that confirmed the phone is
    // still within reach of [lastKnownFix]. It lets a liveness check-in keep re-sending
    // the precise position after the precise fix itself has aged — see
    // coarseStillFixVerdict and maybeSendLivenessCheckIn. 0 = none.
    @Volatile private var lastPresenceConfirmedElapsedNanos: Long = 0L

    // One precise one-shot at a time, however many coarse fixes suggest a departure,
    // and at most one per poll interval (see preciseFixRequestDue).
    @Volatile private var preciseFixInFlight: Boolean = false
    @Volatile private var lastPreciseFixRequestElapsedMs: Long = 0L

    // The health clock in memory. The persisted copy (SessionManager.recordLocationFix,
    // read by the watchdog every 20 minutes) is written at most once a minute: it is an
    // EncryptedSharedPreferences file rewrite, and a MOVING phone accepts a fix every 20
    // seconds.
    @Volatile private var lastFixAtMs: Long = 0L
    private var lastFixPersistedAtMs: Long = 0L

    // The OS power-saver ("Battery Saver" in the system settings), cached by
    // powerSaveReceiver. Treated as the lowest battery tier.
    @Volatile private var cachedPowerSaveMode: Boolean = false

    // What the ongoing notification last said, so an identical re-post is skipped.
    @Volatile private var lastPostedNotificationText: String? = null

    // Dead-man's-switch re-arm throttle for the watchdog alarm — see
    // rearmWatchdogIfDue.
    @Volatile private var lastWatchdogRearmElapsedMs: Long = 0L

    private val powerManager: PowerManager? by lazy {
        getSystemService(Context.POWER_SERVICE) as? PowerManager
    }

    // Set when motion transitions to MOVING — causes the next fresh fix to bypass the
    // distance/time filter so the trip anchor is the actual start of the trip.
    @Volatile private var forceSendNextFix: Boolean = false

    // Last observed battery tier (0 = <20%, 1 = 20-50%, 2 = >=50% or charging).
    // Used to detect tier transitions and reapply the polling profile.
    @Volatile private var lastBatteryTier: Int = BATTERY_TIER_UNINIT

    // Throttling for notification updates (avoid excessive UI updates on every location)
    private var lastNotificationUpdateTime = 0L
    private val notificationUpdateIntervalMs = 5000L // Update at most every 5 seconds

    // ---- Cached DB data to reduce query frequency ----
    @Volatile private var cachedCircles: List<CircleEntity> = emptyList()
    // Read by refreshBoundaries, to decide which circles carry alert-enabled
    // places at all. The per-fix geometry lives in PlaceAlertEvaluator.
    @Volatile private var cachedActiveAlerts: List<PlaceAlertEntity> = emptyList()
    @Volatile private var lastCacheRefreshTime = 0L
    private val cacheRefreshIntervalMs = 5 * 60 * 1000L // Refresh every 5 minutes

    /** What the ongoing notification may say about sharing; see [sharingNowFor]. */
    enum class SharingNow { SHARING, PAUSED, OFF }

    companion object {
        private const val TAG = "LocationTrackingService"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pingolin.track.START_TRACKING"
        const val ACTION_STOP = "com.pingolin.track.STOP_TRACKING"

        /** The keep-alive geofence moves along at most this often. */
        const val GEOFENCE_REARM_MIN_MS = 10L * 60L * 1000L

        /** Whether a phone [movedMeters] from the armed centre, [sinceArmedMs] after arming, re-arms it. */
        internal fun geofenceRearmDue(movedMeters: Float, sinceArmedMs: Long): Boolean =
            movedMeters >= KeepAliveGeofence.RADIUS_METERS && sinceArmedMs >= GEOFENCE_REARM_MIN_MS
        const val ACTION_UPDATE_SETTINGS = "com.pingolin.track.UPDATE_TRACKING_SETTINGS"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_BATTERY_SAVER = "battery_saver"

        private const val DEFAULT_INTERVAL_MS = 30000L
        private const val MIN_INTERVAL_MS = 10000L
        private const val MAX_INTERVAL_MS = 3600000L // 1 hour for battery saver

        // Accuracy thresholds live in LocationPolicy, alongside the permission
        // gate they have to agree with. See LocationPolicy.

        /** Trigger automatic arrival at a destination once the user is
         * within this distance (meters). 100 m is close enough to be
         * "there" but wide enough that typical GPS drift doesn't mask
         * an actual arrival. */
        private const val DESTINATION_ARRIVAL_RADIUS_METERS = 100.0f

        // ---- Polling profile: GPS sample cadence, decoupled from user send cadence ----
        // Base polling intervals (before battery scaling)
        private const val POLL_MOVING_MS = 20_000L          // 20s while moving
        private const val POLL_STILL_MS = 5 * 60_000L       // 5 min while still
        private const val POLL_UNKNOWN_MS = 30_000L         // 30s when motion unknown

        // Battery tiers (percent): >=MID → normal, [LOW,MID) → 2×, <LOW → 4×
        private const val BATTERY_MID_THRESHOLD = 50
        private const val BATTERY_LOW_THRESHOLD = 20
        private const val POLL_MULT_MID_BATTERY = 2
        private const val POLL_MULT_LOW_BATTERY = 4

        // Clamp range for computed poll interval
        private const val MIN_POLL_MS = 10_000L
        private const val MAX_POLL_MS = 30 * 60_000L

        // Reject any location callback whose fix is older than this (catches cached fixes
        // delivered on stream start, which would otherwise become a stale trip anchor)
        private const val STALE_FIX_MAX_AGE_MS = LocationPolicy.STALE_FIX_MAX_AGE_MS

        // Battery tier sentinel
        private const val BATTERY_TIER_UNINIT = -1

        // Stationary inference — the seed for MotionState.STILL when motion
        // detection cannot supply one.
        //
        // Every motion signal is inherently a TRANSITION, so a device that was
        // already still when the service registered is never told so: there is no
        // transition into a state it is already in. motionState then stays UNKNOWN
        // indefinitely and the poll sits at POLL_UNKNOWN_MS (30s) instead of
        // POLL_STILL_MS (5min). That is a design gap, not an API failure, and
        // sensor-based motion detection has the same shape: TYPE_SIGNIFICANT_MOTION
        // fires only on the way INTO motion, so a device that never moves never
        // reports anything. It also leaves the state UNKNOWN permanently on hardware
        // missing either sensor, which makes this inference the sole path to STILL
        // there.
        //
        // Inferring from the fixes we already collect is deliberately conservative:
        // a moving device CANNOT satisfy it, because its fixes leave the radius. So
        // unlike seeding STILL optimistically at startup, this can never under-poll
        // someone who is driving.
        // A liveness check-in re-sends the CACHED fix so peers' timestamp stays
        // fresh while the GPS poll is stretched. It must never present an ancient
        // position as current: if the cache is older than this, GPS has stopped
        // delivering and the honest answer is to let the timestamp go stale (the
        // stale-send warning then fires, which is the correct outcome).
        private const val STILL_REFRESH_MAX_FIX_AGE_FACTOR = 2
        private const val STILL_REFRESH_MAX_FIX_AGE_FLOOR_MS = 10 * 60_000L

        /**
         * What a liveness check-in re-sends: the newer, by elapsed realtime, of this service's own
         * last fix and the newest any path took ([com.pingolin.track.location.NewestOwnFix]), so
         * it is never an older position than one already sent. The service's own on a tie.
         */
        internal fun livenessPayload(
            own: android.location.Location?,
            held: android.location.Location?,
        ): android.location.Location? = when {
            held == null -> own
            own == null -> held
            held.elapsedRealtimeNanos > own.elapsedRealtimeNanos -> held
            else -> own
        }

        /** Whether a fix the service takes in is news: not older than the last one it took in. */
        internal fun freshFixIsNews(known: android.location.Location?, fix: android.location.Location): Boolean =
            known == null || fix.elapsedRealtimeNanos >= known.elapsedRealtimeNanos

        /** Whether a fix taken outside the service is news to it: newer than the last one it took in. */
        internal fun oneShotIsNews(known: android.location.Location?, fix: android.location.Location): Boolean =
            known == null || fix.elapsedRealtimeNanos > known.elapsedRealtimeNanos

        /**
         * Pure rule for [maybeSendLivenessCheckIn]: is the cached fix too old to
         * honestly present as current?
         */
        internal fun stillRefreshCacheTooOld(
            cacheAgeMs: Long,
            pollIntervalMs: Long,
            factor: Int = STILL_REFRESH_MAX_FIX_AGE_FACTOR,
            floorMs: Long = STILL_REFRESH_MAX_FIX_AGE_FLOOR_MS,
        ): Boolean = cacheAgeMs > (pollIntervalMs * factor).coerceAtLeast(floorMs)

        private const val STILL_INFER_RADIUS_M = 50f
        private const val STILL_INFER_MIN_SPAN_MS = 3 * 60_000L

        // ---- Liveness check-ins ----
        //
        // The FLOOR on how often a device that has NOT moved says so — not the
        // cadence itself. The cadence is [livenessIntervalMsFor], which never
        // checks in more often than the user's own send interval.
        //
        // 5 minutes, matching POLL_STILL_MS: while STILL the GPS poll is already
        // stretched to 5 minutes, so a check-in rides a fix the device was taking
        // anyway and adds no wakeup. Shortening it below the poll would mean waking
        // to re-send a cached fix.
        //
        // This is the number to tune if the balance between store size and how
        // quickly a dead phone is noticed turns out wrong. History volume for a
        // stationary fleet is unaffected by it — stationary devices write no
        // history at all — so it trades check-in traffic against the age of the
        // freshest "last seen".
        private const val LIVENESS_CHECKIN_INTERVAL_MS = 5 * 60_000L

        // Battery saver checks in more slowly, but it DOES check in. "Save battery"
        // must not read as "look dead": the whole point of the split is that a live
        // phone which is merely not moving is not inactive, and a phone conserving
        // power is the case where that distinction matters most to whoever is
        // watching. It checks in on the batch interval the user picked (the 5/15/30/60
        // minute chips); this is the default when none is passed, matching the 15-minute
        // default chip.
        private const val LIVENESS_CHECKIN_INTERVAL_BATTERY_SAVER_MS = 3 * LIVENESS_CHECKIN_INTERVAL_MS

        // ---- Battery saver ("Batch uploads" in the UI) ----
        //
        // A FLAT BALANCED (wifi/cell) poll at the batch interval the user picked, whatever
        // the motion state, the battery tier or the real-time send interval say — the same
        // shape as the iOS app's battery saver (a flat 15-minute poll at
        // kCLLocationAccuracyHundredMeters that accepts 500 m). Clamped to the chips the
        // UI offers so a stray stored value cannot poll every second or once a day.
        private const val SAVER_POLL_MIN_MS = 5 * 60_000L
        private const val SAVER_POLL_MAX_MS = 60 * 60_000L

        /** The batch interval, in minutes as SessionManager stores it, as a poll period. */
        internal fun saverIntervalMsFor(minutes: Int): Long =
            (minutes.toLong() * 60_000L).coerceIn(SAVER_POLL_MIN_MS, SAVER_POLL_MAX_MS)

        /**
         * How often a stationary device checks in: the floor above, or the user's own
         * send interval, whichever is LONGER. In battery saver: the batch interval
         * ([saverIntervalMs]), which is the poll itself — each check-in rides a fix
         * the device was taking anyway.
         *
         * Taking the max means no setting sends more often than its own interval asks
         * for. A flat floor would override a deliberate user choice in the one
         * direction that costs them something: at the 15-minute maximum interval, a
         * 5-minute floor is three times the messages they asked for, the opposite of
         * what check-ins are for.
         *
         * With the max:
         *  - the 60-second default checks in every 5 minutes — a fifth of the messages
         *    of its interval, and no history at all;
         *  - a 15-minute user checks in every 15 minutes, exactly the cadence they
         *    chose, and still writes no history while stationary.
         *
         * Nobody sends more often than their own interval. The floor still protects
         * against a nonsensical stored interval, since `maxOf` with 0 or a negative is
         * the floor.
         *
         * Only consulted when time-based sending is ON. Distance-only users are
         * filtered out before this is read — see [decideSend] — so an interval they
         * are not using cannot drive a cadence they do not want.
         */
        internal fun livenessIntervalMsFor(
            userIntervalMs: Long,
            batterySaver: Boolean,
            saverIntervalMs: Long = LIVENESS_CHECKIN_INTERVAL_BATTERY_SAVER_MS,
        ): Long =
            if (batterySaver) {
                // The real-time send interval is not in play in battery saver — the
                // batch interval is the user's cadence there, and it is also the poll.
                saverIntervalMs.coerceAtLeast(LIVENESS_CHECKIN_INTERVAL_MS)
            } else {
                maxOf(LIVENESS_CHECKIN_INTERVAL_MS, userIntervalMs)
            }

        // Slack on the "is a check-in due?" test, and it is load-bearing: the check
        // runs ON a tick of the very period it is measuring, so a bare `elapsed >=
        // interval` lets ordinary timer jitter miss by milliseconds and HALVE the
        // effective rate (re-sends 120s apart on a 60s interval).
        //
        // Scaled to the poll (half of it) because that is the spacing of the fixes
        // being judged, and capped so a 5-minute poll cannot buy itself a 2.5-minute
        // head start.
        private const val LIVENESS_DUE_MAX_SLACK_MS = 60_000L

        /**
         * Is a liveness check-in due, [msSinceLastSend] after the last send of any
         * kind? A history send refreshes what peers see just as a check-in does, so
         * it resets this clock too.
         */
        internal fun livenessDue(
            msSinceLastSend: Long,
            livenessIntervalMs: Long,
            pollIntervalMs: Long,
            maxSlackMs: Long = LIVENESS_DUE_MAX_SLACK_MS,
        ): Boolean {
            val slack = (pollIntervalMs / 2).coerceAtMost(maxSlackMs).coerceAtLeast(0L)
            return msSinceLastSend >= livenessIntervalMs - slack
        }

        /**
         * The send decision for one usable fix.
         *
         * [movedMeters] is the distance from the last position that was actually
         * committed to HISTORY — null when there is none yet (fresh service, fresh
         * install, or a user who just signed in). No baseline means the first fix
         * anchors one, so it is history.
         *
         * The distance threshold is the user's own, unscaled. Making it smaller
         * would be the obvious way to "send more often" and is wrong twice over: it
         * would add history rows while the user is genuinely moving, and it would
         * let GPS jitter on a resting phone read as movement, which is the exact
         * failure a stationary device must not have.
         *
         * The two toggles mean:
         *  - Both off: the user asked for no uploads. A check-in is an upload, so
         *    still nothing.
         *  - Distance only: "put me on the map when I move" — sends on a timer are
         *    what that toggle turns OFF, and a check-in is a timed send. So a
         *    stationary distance-only user stays silent, and
         *    staleSendThresholdMsFor returns null for them so nothing complains
         *    about it.
         *  - Time on: the default. Movement earns a history row; standing still
         *    earns a check-in on the cadence.
         */
        internal fun decideSend(
            movedMeters: Float?,
            distanceThresholdMeters: Float,
            msSinceLastSend: Long,
            timeEnabled: Boolean,
            distanceEnabled: Boolean,
            livenessIntervalMs: Long,
            pollIntervalMs: Long,
        ): LocationSendDecision {
            if (!timeEnabled && !distanceEnabled) return LocationSendDecision.NONE
            if (movedMeters == null) return LocationSendDecision.HISTORY
            if (movedMeters >= distanceThresholdMeters) return LocationSendDecision.HISTORY
            if (!timeEnabled) return LocationSendDecision.NONE
            return if (livenessDue(msSinceLastSend, livenessIntervalMs, pollIntervalMs)) {
                LocationSendDecision.LIVENESS
            } else {
                LocationSendDecision.NONE
            }
        }

        /**
         * The whole decision for one fix, including the two overrides that skip
         * [decideSend] entirely. Extracted so all three entry paths are one
         * expression that can be read — and tested — without a running service.
         *
         * [forceSend] is the trip-start / place-crossing bypass, armed by the motion
         * observer on (any → MOVING) and by checkOwnLocationAgainstBoundaries. It
         * yields HISTORY, never a check-in: a trip with no start point, or a place
         * alert whose position was never kept, is the failure each of those exists
         * to prevent.
         *
         * [isLivenessCheckIn] means maybeSendLivenessCheckIn has already established
         * the cadence has elapsed. Re-testing it here would double-count the
         * decision and land sends every 120s on a 60s interval.
         */
        internal fun decideSendForFix(
            forceSend: Boolean,
            isLivenessCheckIn: Boolean,
            movedMeters: Float?,
            distanceThresholdMeters: Float,
            msSinceLastSend: Long,
            timeEnabled: Boolean,
            distanceEnabled: Boolean,
            livenessIntervalMs: Long,
            pollIntervalMs: Long,
        ): LocationSendDecision = when {
            forceSend -> LocationSendDecision.HISTORY
            isLivenessCheckIn -> LocationSendDecision.LIVENESS
            else -> decideSend(
                movedMeters = movedMeters,
                distanceThresholdMeters = distanceThresholdMeters,
                msSinceLastSend = msSinceLastSend,
                timeEnabled = timeEnabled,
                distanceEnabled = distanceEnabled,
                livenessIntervalMs = livenessIntervalMs,
                pollIntervalMs = pollIntervalMs,
            )
        }

        /**
         * When a crossing found in [fixTimeMs] should be reported as happening.
         *
         * The boundary check runs against a Location that can be minutes stale
         * by the time the send cycle processes it. Stamping the crossing with
         * now() would report it that much LATE, which is exactly what sending a
         * crossing time exists to avoid.
         *
         * Falls back to [now] when the fix carries no usable time: Location.time
         * is 0 on some sources, and a clock skew (or a fix from a device whose
         * clock is ahead) must not produce a crossing in the FUTURE — a future
         * stamp would render as a time the user has not reached yet. A fix that
         * is merely old is kept as-is; that is the whole point.
         */
        internal fun crossingTimeFor(fixTimeMs: Long, now: Long): Long =
            if (fixTimeMs <= 0L || fixTimeMs > now) now else fixTimeMs

        /**
         * When an auto-arrival is reported as having happened — never simply the
         * moment the report is assembled, which is wrong in two ways:
         *
         *  - [remembered] is the time this arrival was FIRST detected, held
         *    across a failed report. `checkOwnLocationAgainstDestinations`
         *    un-flags a destination whose report failed so a later tick retries
         *    it, and the later tick is the moment the network came back, not the
         *    moment the user arrived. That is precisely the case a sealed
         *    occurred-at exists for, so a remembered detection always wins.
         *  - otherwise the FIX's own time, via [crossingTimeFor], for the same
         *    reason the boundary path uses it: the send cycle can process a
         *    Location minutes after it was taken.
         */
        internal fun arrivalTimeFor(remembered: Long?, fixTimeMs: Long, now: Long): Long =
            remembered ?: crossingTimeFor(fixTimeMs, now)

        /**
         * Pure decision for [maybeInferStill]: have we held one spot long enough?
         * Extracted so the rule is unit-testable without an Android Location or a
         * running service.
         */
        internal fun shouldInferStill(
            distanceFromAnchorM: Float,
            elapsedSinceAnchorMs: Long,
            radiusM: Float = STILL_INFER_RADIUS_M,
            minSpanMs: Long = STILL_INFER_MIN_SPAN_MS,
        ): Boolean = distanceFromAnchorM <= radiusM && elapsedSinceAnchorMs >= minSpanMs

        /**
         * The anchor radius to judge stillness at, widened to a fix's own accuracy
         * when that is rougher than [STILL_INFER_RADIUS_M].
         *
         * Battery-saver ACCEPTS fixes up to 500 m
         * ([LocationPolicy.MIN_ACCURACY_BALANCED]) — it does not request them; see
         * that constant. A 500 m fix can never land within 50 m of its anchor, so
         * whenever one does arrive the anchor resets on EVERY callback, STILL is
         * never inferred, applyPollingProfile never downshifts, and the mode that
         * exists to save battery polls at the UNKNOWN cadence indefinitely.
         * Silently: there is no log line for a rule that never fires.
         *
         * Widening is the safe direction HERE, unlike the place-alert gate
         * (PlaceAlertRules.canResolve, which skips instead). The cost of being
         * wrong is a slowly-moving device downshifting its poll — recoverable, and
         * bounded, because activity recognition overrides this the moment it has an
         * opinion (see [maybeInferStill]'s UNKNOWN guard). The cost of being wrong
         * about a geofence is a false notification, which is not recoverable.
         *
         * Null/absent accuracy keeps the fixed radius: unknown is not licence to
         * relax the rule.
         */
        internal fun stillInferRadiusFor(accuracyMeters: Float?): Float =
            maxOf(STILL_INFER_RADIUS_M, accuracyMeters ?: 0f)

        /**
         * Whether a fix of this accuracy can decide a
         * [DESTINATION_ARRIVAL_RADIUS_METERS] arrival test.
         *
         * Same rule and same reasoning as PlaceAlertRules.canResolve: a fix whose
         * own error exceeds the ring is consistent with being inside OR outside it,
         * so firing from it is a guess. Null accuracy means unknown, and unknown
         * evaluates — a fix with no accuracy reported must not disable arrivals.
         */
        internal fun canResolveArrivalRadius(accuracyMeters: Float?): Boolean =
            accuracyMeters == null || accuracyMeters <= DESTINATION_ARRIVAL_RADIUS_METERS

        /**
         * The battery multiplier on the poll: normal 1x, mid (20-50 %) 2x, low
         * (< 20 %) 4x. Charging forces 1x whatever the level.
         *
         * The OS power-saver counts as the LOW tier whatever the level says. The
         * user asked the whole phone to save power; a location app polling at full
         * rate through that is the one thing they would least expect to still be
         * running. Charging still wins — most phones switch power-saver off on the
         * charger anyway, and a phone on the charger has nothing to save.
         */
        internal fun batteryMultiplierFor(
            batteryLevel: Int?,
            isCharging: Boolean,
            powerSaveMode: Boolean,
        ): Int = when {
            isCharging -> 1
            powerSaveMode -> POLL_MULT_LOW_BATTERY
            batteryLevel == null -> 1
            batteryLevel < BATTERY_LOW_THRESHOLD -> POLL_MULT_LOW_BATTERY
            batteryLevel < BATTERY_MID_THRESHOLD -> POLL_MULT_MID_BATTERY
            else -> 1
        }

        /**
         * The tier [batteryMultiplierFor] is a step function of (0 = low, 1 = mid,
         * 2 = normal), so a change of level WITHIN a tier costs no re-subscription.
         */
        internal fun batteryTierFor(
            batteryLevel: Int?,
            isCharging: Boolean,
            powerSaveMode: Boolean,
        ): Int = when {
            isCharging -> 2
            powerSaveMode -> 0
            batteryLevel == null -> 2
            batteryLevel < BATTERY_LOW_THRESHOLD -> 0
            batteryLevel < BATTERY_MID_THRESHOLD -> 1
            else -> 2
        }

        /**
         * What to ask the location provider for. Pure, so every rule below is pinned
         * by LocationTrackingServiceTest against this function rather than against a
         * transcribed copy of it.
         *
         * Battery saver: a FLAT [LocationAccuracy.BALANCED] poll at the batch
         * interval, ignoring motion, battery tier and the real-time send interval —
         * never the real-time HIGH subscription at whatever cadence the service
         * happened to start with, which could poll GPS every 20 s for days.
         *
         * Otherwise:
         *   - base: MOVING 20 s, STILL 5 min, UNKNOWN 30 s. A MOVING the GPS says is
         *     stationary ([stationaryWhileMoving]) polls at the STILL cadence;
         *   - times the battery multiplier ([batteryMultiplierFor]);
         *   - capped at the user's send interval so a scheduled send has a fresh fix
         *     — except while (effectively) STILL, where a five-minute-old fix
         *     describes the same place, and except when the battery multiplier is
         *     above 1: the cap exists for sends that happen AT the user interval, and
         *     with sends driven by distance and liveness none do, so at low battery
         *     the cap would only cancel the multiplier (UNKNOWN at < 20 % held to 60
         *     s instead of 120 s);
         *   - clamped to [MIN_POLL_MS, MAX_POLL_MS].
         *
         * Accuracy is [LocationAccuracy.BALANCED] while STILL. Nothing while still
         * needs a satellite: liveness re-sends the cached precise fix, and leaving is
         * signalled by the significant-motion trigger — with a BALANCED fix that
         * lands too far away as the backstop (see coarseStillFixVerdict). The
         * GPS-evidence STILL cadence while MOVING stays HIGH: the motion detector
         * still thinks the phone is moving, and a precise fix is what ends that
         * state.
         */
        internal fun pollProfileFor(
            motion: MotionState,
            stationaryWhileMoving: Boolean,
            batterySaver: Boolean,
            saverIntervalMs: Long,
            multiplier: Int,
            timeBasedEnabled: Boolean,
            userIntervalMs: Long,
        ): PollProfile {
            if (batterySaver) {
                return PollProfile(
                    intervalMs = saverIntervalMs.coerceIn(SAVER_POLL_MIN_MS, SAVER_POLL_MAX_MS),
                    accuracy = LocationAccuracy.BALANCED,
                )
            }
            val holdsStill = motion == MotionState.STILL ||
                (motion == MotionState.MOVING && stationaryWhileMoving)
            val base = when {
                holdsStill -> POLL_STILL_MS
                motion == MotionState.MOVING -> POLL_MOVING_MS
                else -> POLL_UNKNOWN_MS
            }
            var interval = base * multiplier
            if (timeBasedEnabled && !holdsStill && multiplier == 1 && userIntervalMs in 1 until interval) {
                interval = userIntervalMs
            }
            return PollProfile(
                intervalMs = interval.coerceIn(MIN_POLL_MS, MAX_POLL_MS),
                accuracy = if (motion == MotionState.STILL) LocationAccuracy.BALANCED else LocationAccuracy.HIGH,
            )
        }

        // How long MOVING must have lasted before GPS evidence may override it.
        private const val MOVING_STATIONARY_MIN_MOVING_MS = 5 * 60_000L

        /**
         * The GPS-evidence exit from MOVING: poll at the STILL cadence once MOVING
         * has lasted [minMovingMs] AND every fix for [STILL_INFER_MIN_SPAN_MS] has
         * held to one spot — the same test [shouldInferStill] applies to UNKNOWN.
         *
         * Why it is needed: the motion detector's only way out of MOVING is an
         * accelerometer dwell, and a phone resting on a vibrating surface, or one
         * whose sensor batches are lost while it sleeps, can sit in MOVING with GPS
         * pinned at 20 s — 15x the STILL cost — for as long as that lasts. This is
         * positive evidence (fixes that stayed put), not "the trigger did not fire",
         * so it does not reintroduce the timeout MotionDetectionManager warns
         * against. It changes only the poll: the motion state is left alone, and the
         * first fix outside the radius restores 20 s.
         */
        internal fun shouldHoldStillCadenceWhileMoving(
            movingForMs: Long,
            distanceFromAnchorM: Float,
            elapsedSinceAnchorMs: Long,
            radiusM: Float,
            minMovingMs: Long = MOVING_STATIONARY_MIN_MOVING_MS,
        ): Boolean = movingForMs >= minMovingMs &&
            shouldInferStill(distanceFromAnchorM, elapsedSinceAnchorMs, radiusM)

        // A coarse STILL fix within this of the last precise fix confirms the phone
        // is where it was. Same number the iOS app uses for its keep-alive fixes.
        private const val STILL_PRESENCE_RADIUS_M = 500f

        /**
         * What a fix too rough for the real-time gate says while STILL at
         * [LocationAccuracy.BALANCED].
         *
         *  - rougher than [LocationPolicy.MIN_ACCURACY_BALANCED]: nothing — a cell
         *    fix of kilometres can neither confirm nor refute a position;
         *  - within max(500 m, its own accuracy) of the last precise fix: presence
         *    confirmed. It refreshes the liveness cache age so a check-in keeps
         *    re-sending the PRECISE position — never this rough one, and never a
         *    history row;
         *  - farther, or with no precise fix to compare against: take one precise
         *    fix, and let that decide through the normal path.
         */
        internal fun coarseStillFixVerdict(
            accuracyMeters: Float,
            distanceFromLastPreciseM: Float?,
        ): CoarseStillVerdict = when {
            accuracyMeters > LocationPolicy.MIN_ACCURACY_BALANCED -> CoarseStillVerdict.TOO_ROUGH
            distanceFromLastPreciseM == null -> CoarseStillVerdict.SUGGESTS_DEPARTURE
            distanceFromLastPreciseM <= maxOf(STILL_PRESENCE_RADIUS_M, accuracyMeters) ->
                CoarseStillVerdict.CONFIRMS_PRESENCE
            else -> CoarseStillVerdict.SUGGESTS_DEPARTURE
        }

        // A precise fix this far from the last one (and outside both fixes' error)
        // confirms the departure a coarse STILL fix suggested.
        private const val DEPARTURE_CONFIRM_MIN_M = 200f

        /**
         * Did the precise fix taken after a coarse "departure" hint confirm it? The
         * two fixes must be farther apart than [DEPARTURE_CONFIRM_MIN_M] AND than
         * their combined error, so two honest fixes of the same spot never read as
         * a trip.
         */
        internal fun preciseFixConfirmsDeparture(
            distanceM: Float,
            fixAccuracyM: Float,
            previousAccuracyM: Float,
        ): Boolean = distanceM > maxOf(DEPARTURE_CONFIRM_MIN_M, fixAccuracyM + previousAccuracyM)

        // How long to wait for that precise one-shot before giving up on it.
        private const val PRECISE_ONE_SHOT_TIMEOUT_MS = 30_000L

        /**
         * At most one precise one-shot per poll interval. Every path that asks for
         * one (a coarse fix that suggests a departure or is too rough to judge, a
         * BALANCED poll that delivers nothing, a restart with no recent fix) can
         * repeat — indoors with no fix of any kind, the tick would otherwise retry
         * a 30 s HIGH attempt every minute. One per poll is what a HIGH-accuracy
         * STILL poll would cost, so the worst case is never more than that.
         */
        internal fun preciseFixRequestDue(
            nowElapsedMs: Long,
            lastRequestElapsedMs: Long,
            pollIntervalMs: Long,
        ): Boolean = lastRequestElapsedMs == 0L || nowElapsedMs - lastRequestElapsedMs >= pollIntervalMs

        // The persisted health clock is written at most this often (see lastFixAtMs).
        private const val FIX_HEALTH_PERSIST_MIN_SPACING_MS = 60_000L

        /**
         * Whether to re-post the ongoing notification. Not while the screen is off
         * — nobody can see it, and otherwise almost all re-posts happen with the
         * screen off — and not when the text has not changed. The screen coming
         * back on forces one refresh (screenOnReceiver).
         */
        internal fun shouldPostNotificationUpdate(
            isInteractive: Boolean,
            text: String,
            lastPostedText: String?,
        ): Boolean = isInteractive && text != lastPostedText

        /**
         * What the ongoing notification may say about sharing: "active" only while some circle
         * receives this phone's location. Read from each circle's local privacy row, the one the
         * send gates read: paused ([isSharingPausedAt]) or real-time off means it receives
         * nothing. A circle without a row is being primed and isn't called off. No circle at all
         * is off: the location goes to nobody but this account's own history.
         */
        internal fun sharingNowFor(
            circleIds: Collection<String>,
            rows: Map<String, com.pingolin.track.data.local.entity.PrivacySettingEntity>,
            nowMs: Long,
        ): SharingNow {
            var anyPaused = false
            for (circleId in circleIds) {
                val row = rows[circleId] ?: return SharingNow.SHARING
                val paused = row.isSharingPausedAt(nowMs)
                if (!paused && row.shareRealtime) return SharingNow.SHARING
                if (paused) anyPaused = true
            }
            return if (anyPaused) SharingNow.PAUSED else SharingNow.OFF
        }

        // Re-arm the watchdog alarm at most this often from the send path.
        private const val WATCHDOG_REARM_MIN_SPACING_MS = 5 * 60_000L

        /**
         * Whether a successful send should push the watchdog alarm out again. The
         * alarm is a dead man's switch: every successful send re-arms it 20 minutes
         * out, so it only fires after silence. Throttled, because a MOVING phone
         * sends every 20 s and each re-arm is an AlarmManager call; at a 5-minute
         * spacing the alarm still fires 15-20 minutes after the last send.
         */
        internal fun watchdogRearmDue(
            nowElapsedMs: Long,
            lastRearmElapsedMs: Long,
            minSpacingMs: Long = WATCHDOG_REARM_MIN_SPACING_MS,
        ): Boolean = lastRearmElapsedMs == 0L || nowElapsedMs - lastRearmElapsedMs >= minSpacingMs

        // Session-success staleness: how long without any successful send before the
        // persistent notification flips and a SendingStale UX event fires. The threshold
        // must scale with the user's own cadence — staleSendThresholdMs() derives it from
        // getTrackingIntervalMs() × 2. A fixed value cannot work: at the default 15-minute
        // interval a flat 5-minute threshold fires three times before the next send is even
        // due, and in distance-only mode there is no expected cadence to compare against.
        // This floor only stops very short intervals making the warning trigger-happy.
        private const val STALE_SEND_THRESHOLD_FLOOR_MS = 5 * 60_000L

        /**
         * How long without a successful send of ANY kind before the notification
         * flips and a SendingStale event fires. Null suppresses the warning.
         *
         * Measured against check-ins too: a stationary device's sends arrive on
         * [livenessIntervalMsFor], which is the LONGER of the check-in floor and the
         * user's own interval — so for a short interval it is the floor, and for a
         * long one it is the user's interval itself. Either way it is the clock that
         * actually drives sends, and the threshold has to be measured against that
         * one. At the 60s default a threshold from the interval alone would be 5
         * minutes (its floor) against a 5-minute cadence, so a single tick of jitter
         * would cry wolf every time the user sat down.
         *
         * Two full check-in periods is the same "missed one expected send plus its
         * retry window" definition the interval term uses.
         *
         * The arithmetic, given that [livenessIntervalMsFor] takes a max, is that
         * this returns EXACTLY two check-in periods for every setting the UI offers
         * (SettingsFragment offers 10/30/60/300/600/900 seconds):
         *
         *   interval  cadence   threshold
         *   10-300s   5 min     10 min
         *   600s      10 min    20 min
         *   900s      15 min    30 min
         *
         * Battery saver checks in on its batch interval (5/15/30/60 minutes), so its
         * threshold is 10/30/60/120 minutes. LocationHeartbeatWorker widens its own
         * staleness watchdog to match (heartbeatStaleThresholdMsFor), so a healthy
         * battery-saving service is never covered by a redundant heartbeat send.
         *
         * The `intervalMs * 2` term can never be the maximum in a live call —
         * `cadence >= interval` by construction. It stays because this is a pure
         * function over two independent arguments and a caller passing an unrelated
         * pair should still get a sane answer; nothing live depends on it.
         */
        internal fun staleSendThresholdMsFor(
            timeBasedEnabled: Boolean,
            intervalMs: Long,
            livenessIntervalMs: Long,
            floorMs: Long = STALE_SEND_THRESHOLD_FLOOR_MS,
        ): Long? {
            if (!timeBasedEnabled) return null
            return maxOf(intervalMs * 2, livenessIntervalMs * 2, floorMs)
        }

        // How often we tick the foreground notification so the "Last shared Nm ago" age
        // stays honest even while nothing else is happening.
        private const val NOTIFICATION_TICK_INTERVAL_MS = 60_000L
        // Cadence for the background client-side evaluation pass (inactivity + subscriber-side
        // place alerts).
        //
        // Place alerts set the pace here: this interval IS the worst-case lag on "Sam arrived
        // at Home" once the app is backgrounded, and a quarter of an hour late is the kind of
        // alert users read as broken. 5 minutes costs one HTTP round-trip per watched circle,
        // on a foreground service already streaming this device's own location, so the
        // marginal cost is small next to what the service does anyway.
        //
        // Inactivity would be happy with something much slower — those timeouts are
        // hour-scale — but it inherits this cadence harmlessly, and sharing one pass is what
        // avoids two loops each hitting the same circles.
        private const val BACKGROUND_EVAL_INTERVAL_MS = 5 * 60_000L

        // Self-heal thresholds. After this many consecutive end-to-end send
        // failures (envelope AND per-target fallback both fail to land on
        // any target), re-import circle keys. Threshold is small because
        // the typical "real" outage either recovers fast or persists for
        // hours — three failures is a clear signal something is wrong
        // beyond a single transient.
        private const val KEY_HEAL_FAILURE_THRESHOLD = 3
        // Cooldown between heal attempts so a long network outage doesn't
        // hammer importCircleKeys repeatedly (it'd fail every time and
        // accomplish nothing).
        private const val KEY_HEAL_COOLDOWN_MS = 5L * 60_000L

        // Session key rotation interval: 5 hours 45 minutes (overlap with 6-hour key duration)
        private const val SESSION_KEY_ROTATION_INTERVAL_MS = 5 * 60 * 60 * 1000L + 45 * 60 * 1000L

        // Static flag to track service running state across app restarts
        @Volatile
        var isRunning: Boolean = false
            private set

        /**
         * The poll interval the running service is actually using, 0 when it is not
         * running. Published for WatchdogAlarmReceiver's blind check: a fix is only
         * overdue relative to the POLL, and the poll can be far longer than the
         * user's send interval (20 minutes while STILL below 20 % battery, up to an
         * hour in battery saver).
         */
        @Volatile
        var publishedPollIntervalMs: Long = 0L
            private set
    }

    // Cached battery state to avoid expensive registerReceiver calls on every location update
    // Updated by batteryReceiver when battery state changes (much more efficient)
    @Volatile private var cachedBatteryLevel: Int? = null
    @Volatile private var cachedIsCharging: Boolean = false

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                cachedBatteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else null

                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cachedIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                   status == BatteryManager.BATTERY_STATUS_FULL

                // Reapply the polling profile if battery tier crossed a threshold.
                // Poll interval is step-function of tier, so intra-tier changes are no-ops.
                val prev = lastBatteryTier
                val tier = computeBatteryTier()
                lastBatteryTier = tier
                if (prev != BATTERY_TIER_UNINIT && prev != tier) {
                    applyPollingProfile("battery tier $prev -> $tier (level=$cachedBatteryLevel%, charging=$cachedIsCharging)")
                }
            }
        }
    }

    // The OS power-saver toggled. It counts as the low battery tier — see
    // batteryMultiplierFor — so it re-tunes the poll exactly like a tier change.
    private val powerSaveReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            cachedPowerSaveMode = powerManager?.isPowerSaveMode == true
            val prev = lastBatteryTier
            val tier = computeBatteryTier()
            lastBatteryTier = tier
            if (prev != BATTERY_TIER_UNINIT && prev != tier) {
                applyPollingProfile("power saver ${if (cachedPowerSaveMode) "on" else "off"} (tier $prev -> $tier)")
            }
        }
    }

    // The screen came on: bring the ongoing notification up to date now, since
    // updateNotification does not re-post it while the screen is off.
    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON || !isTracking) return
            lastNotificationUpdateTime = 0L
            updateNotification(null)
        }
    }

    // Location (or one of its providers) switched off or on. Play services lets the keep-alive
    // geofence go while location is off (GEOFENCE_NOT_AVAILABLE), so this start's next fix arms it
    // again.
    private val locationModeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            geofenceCenter = null
            // And the ongoing notification says so at once, either way.
            if (!isTracking) return
            lastNotificationUpdateTime = 0L
            updateNotification(null)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Location processing runs off the main thread.
        locationHandlerThread = HandlerThread("LocationCallbackThread").apply {
            start()
        }

        // A sticky receiver keeps battery state cached; polling it with registerReceiver(null,
        // ...) on every location update is far more expensive.
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, batteryFilter)

        // All are protected system broadcasts, so registering without an export flag
        // is allowed on every API level this app supports.
        cachedPowerSaveMode = powerManager?.isPowerSaveMode == true
        try {
            registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
            registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            registerReceiver(locationModeReceiver, IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION).apply {
                addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
            })
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register power-save/screen receivers: ${e.message}")
        }

        // Seed the cache now — the receiver won't fire until the state actually changes.
        try {
            val batteryIntent = registerReceiver(null, batteryFilter)
            batteryIntent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                cachedBatteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else null

                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cachedIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                   status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize battery state", e)
        }

        // Headless cold-start key recovery. If the user reinstalled or the
        // EncryptedSharedPreferences master key rotated, the SDK's circle-key store boots empty
        // and every send fails with "Missing key" until the user happens to open the app
        // (MainViewModel.refreshCircles is the only other thing that calls importCircleKeys). The
        // self-heal in onAllSendsFailed needs 3 consecutive failures + a 5-min cooldown, which can
        // stretch to many hours when the phone is idle and location callbacks are sparse. Pulling
        // keys once at FGS startup closes that gap. Idempotent + cheap (single HTTP).
        // A fix taken and sent outside this service (send-now, the map-open refresh, a
        // check-in...; NewestOwnFix) gets the same bookkeeping and crossing check as one of its
        // own, and is not sent again. Without it a send-now arrival would go unevaluated, so
        // unreported, until this service's own poll next delivers, minutes later.
        serviceScope.launch {
            newestOwnFix.kept.collect { fix -> takeOneShotFix(fix) }
        }

        serviceScope.launch {
            try {
                val result = circleRepository.importCircleKeys()
                if (result.isSuccess) {
                    Log.i(TAG, "FGS cold-start: imported ${result.getOrNull()} circle keys")
                } else {
                    Log.w(TAG, "FGS cold-start importCircleKeys failed: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "FGS cold-start importCircleKeys threw: ${e.message}")
            }
        }
    }

    /**
     * Refresh cached circles, alerts, and places from DB.
     * Called on first location update and then every 5 minutes.
     */
    private suspend fun refreshCachedData() {
        val now = System.currentTimeMillis()
        if (now - lastCacheRefreshTime < cacheRefreshIntervalMs) return
        lastCacheRefreshTime = now

        try {
            // Pull the circle list from the SERVER first, not just the local table.
            //
            // Every other caller of refreshCircles is a UI ViewModel or Fragment, so nothing
            // refreshes `circles` while the app sits in the background. Reading only
            // circleDao.getAll() therefore lets an empty or stale local table stay that way
            // forever: `cachedCircles` stays empty and the send path falls through to "No
            // circles found - sending for self-tracking only". The device is an active member
            // server-side and publishes to nobody, silently, until the user happens to open the
            // Circles screen — the state a device lands in after clearData followed by login.
            //
            // Best-effort and already behind the 5-minute staleness gate, so this adds no new
            // cadence. refreshCircles only ever inserts (no prune), so a partial or failed
            // response can never blank the circles we already hold — on failure we simply fall
            // through to the retained local rows.
            runCatching { circleRepository.refreshCircles() }
                .onFailure { Log.w(TAG, "refreshCircles failed; using retained local circles: ${it.message}") }
            cachedCircles = circleDao.getAll()
            cachedActiveAlerts = placeAlertDao.getActive()

            Log.d(TAG, "Refreshed cached data: ${cachedCircles.size} circles, ${cachedActiveAlerts.size} alerts")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh cached data", e)
        }
    }

    /**
     * One background pass over the circles whose members we need fresh locations for.
     *
     * TWO consumers, ONE fetch. `refreshLatestLocations` writes members' latest decrypted
     * locations to Room *through* `LocationRepository.persistAndEvaluate`, which is also the
     * choke point feeding subscriber-side place-alert evaluation. So fetching a circle is by
     * itself enough to make its place alerts evaluate — there is no second call to make
     * here, and adding one would double-evaluate.
     *
     * Place-alert circles MUST stay in this fetch. Nothing else refreshes member locations
     * outside a UI ViewModel (MapViewModel / MainViewModel), so dropping them means
     * subscriber-side place alerts for OTHER members only evaluate while the app is open:
     * with no inactivity rule configured `inactivityCircleIds()` is empty, this function
     * returns immediately, and a watched member can arrive anywhere with the phone in a
     * pocket without anything ever looking. (SELF is unaffected either way — own fixes reach
     * the evaluator through `sendLocation`, which this service already drives.)
     *
     * Background paths must not depend on state that only the UI refreshes — place_alerts,
     * circle_members and circles are all such state. Worth checking before adding another.
     *
     * Fetching the UNION and evaluating each concern over its own subset keeps a circle that
     * has both an inactivity rule and a place alert to a single network round-trip.
     */
    private suspend fun runBackgroundEvaluationPass() {
        try {
            // Make sure the alert/circle cache this pass reads is current. Its only other
            // caller is the own-location update path, so on a STATIONARY device it can go
            // stale indefinitely — and a stationary device is precisely when watching someone
            // else matters, since we are sitting still while they move. Self-throttled, so
            // this is a no-op when the cache is already fresh.
            refreshCachedData()
            // ...and the low-battery and inactivity rules this pass judges, when stale
            // ([AlertRulesReload]). Every other refresh of them is a screen, an edit here
            // or a queued change: without this, after a sign-in, an install or a database
            // made again there would be nothing here to judge, and a rule made on another
            // device would never arrive. Before the circles are read from them.
            // Best-effort: a failure keeps the rules already here.
            alertRulesReload.reloadIfStale()

            val inactivityCircles = monitoringManager.inactivityCircleIds().toSet()
            // Low-battery rules are evaluated here for the same reason inactivity
            // is: the comparison needs the watched member's decrypted payload, and
            // the server cannot make it. The wake-up push (AlertsCheckWorker) is
            // the timely path; this periodic pass is what catches a drain when the
            // wake-up was dropped, deferred by Doze, or never sent because the
            // watched phone died before it could ask.
            val batteryCircles = monitoringManager.batteryAlertCircleIds().toSet()
            // A circle-mate's "watch me" request: only OUR device can raise it,
            // since theirs is the one that has gone quiet.
            //
            // Cached for 30 minutes rather than fetched on every 5-minute pass:
            // this list changes when somebody creates or deletes a request, which
            // is rare, and fetching it every pass would cost a phone sitting on a
            // desk twelve requests an hour. The alerts_check push path
            // (AlertsCheckWorker) still fetches fresh, and refreshes this cache as
            // it does.
            val selfAlerts = monitoringManager.fetchCircleSelfAlertsCached()
            val selfAlertCircles = selfAlerts.map { it.circleId }.toSet()
            // A "Just me" alert carries a null circle and needs no member fetch — its own
            // fixes already flow through sendLocation. Only alerts homed to a real circle
            // require somebody else's location to evaluate.
            val placeAlertCircles = cachedActiveAlerts
                .mapNotNull { alert -> alert.circleId?.takeIf { it.isNotBlank() } }
                .toSet()

            val circleIds = inactivityCircles + placeAlertCircles + batteryCircles + selfAlertCircles
            if (circleIds.isEmpty()) return

            // circleId -> the members THIS refresh produced a location for. A
            // healthy circle is not enough on its own: a member absent from the
            // response keeps an ageing row in the local cache, and reading that
            // row alone cannot tell "went quiet" from "was not in this response".
            // See LocationFetchResult.refreshedUserIds.
            val refreshed = mutableMapOf<String, Set<String>>()
            for (cid in circleIds) {
                val result = locationRepository.refreshLatestLocations(cid).getOrNull()
                if (result != null && result.isComplete) {
                    refreshed[cid] = result.refreshedUserIds
                }
                // Place alerts need no explicit call: the refresh above already ran them via
                // persistAndEvaluate. A failed refresh simply contributes no new locations,
                // and the watermark means the next pass picks up where this one left off.
            }
            // Judge inactivity only for circles that HAVE an inactivity rule and whose
            // refresh both reached the server and decrypted cleanly. Passing the
            // place-alert-only circles in here would let a connectivity gap read as
            // "everyone went silent".
            monitoringManager.evaluateInactivity(refreshed.filterKeys { it in inactivityCircles })
            monitoringManager.evaluateLowBattery(refreshed.filterKeys { it in batteryCircles })
            monitoringManager.evaluateCircleSelfAlerts(selfAlerts, refreshed.filterKeys { it in selfAlertCircles })
        } catch (e: Exception) {
            Log.e(TAG, "runBackgroundEvaluationPass failed", e)
        }
    }

    /**
     * Wires up boundary monitoring for place alerts. All boundary checking happens
     * client-side; the server never learns where a place is or when it was crossed.
     */
    private suspend fun initializeBoundaryMonitoring() {
        try {
            // getUserDisplayName is NOT a suspend function, so no runBlocking is needed here.
            val callback = BoundaryMonitorCallbackImpl(
                alertHandler = alertHandler,
                displayNameProvider = { userId ->
                    boundaryRepository.getUserDisplayName(userId)
                },
                crossingReportHandler = BoundaryMonitorCallbackImpl.CrossingReportHandler { placeId, userId, deviceId, crossingType, occurredAtMs ->
                    boundaryRepository.reportCrossingFromMonitor(placeId, userId, deviceId, crossingType, occurredAtMs)
                },
                coroutineScope = serviceScope
            )

            boundaryRepository.setMonitorCallback(callback)
            Log.d(TAG, "Boundary monitoring callback initialized")

            refreshBoundaries()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize boundary monitoring", e)
        }
    }

    /**
     * (Re)load the geofence boundary set from the server for every circle and
     * hand it to the native boundary checker. A place only participates in
     * crossing detection once an alert targets it (the server sets the place's
     * alerts_enabled flag then, and BoundaryRepository filters to alertsEnabled
     * places), so this MUST be able to run again after the service has started
     * — otherwise a member whose tracking service was already running when
     * another member created the alert never begins monitoring that place,
     * never detects/reports the crossing, and the alert silently never fires.
     * Called at cold start, then periodically from the location loop and on a
     * foreground ACTION_START.
     */
    private suspend fun refreshBoundaries() {
        // Advance the throttle up-front so a transient refreshPlaces failure
        // doesn't hammer the API on every location fix; we simply retry on the
        // next interval.
        lastBoundaryReloadElapsedMs = SystemClock.elapsedRealtime()
        try {
            val circles = circleDao.getAll()
            // Load EVERY circle's boundaries in ONE native commit. The native
            // BoundaryChecker replaces its whole boundary set on each load (the
            // mobile SDK's checker clears its map first), so a per-circle loop
            // here would leave only the LAST circle monitored and silently drop
            // crossing alerts on all the others. loadBoundariesForAllCircles
            // accumulates the union of every circle's alertsEnabled boundaries and
            // loads it exactly once.
            boundaryRepository.loadBoundariesForAllCircles(circles.map { it.id })
            Log.d(TAG, "Loaded boundaries for ${circles.size} circles")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh boundaries", e)
        }
    }

    /**
     * Whether this service can actually do its job. Requires FINE — see
     * [LocationPolicy.hasPreciseLocation] for why a grant the platform accepts
     * is not enough. A start that cannot hold it (boot receiver, watchdog
     * alarm, heartbeat worker, FCM wake) stops cleanly instead of pretending,
     * and MainActivity tells the user why — see
     * `MainActivity.showPreciseLocationRequiredDialog`.
     */
    private fun hasPreciseLocationPermission(): Boolean =
        LocationPolicy.hasPreciseLocation(applicationContext)

    /** True when the user granted "Approximate location" and nothing better. */
    private fun hasCoarseLocationOnly(): Boolean =
        LocationPolicy.hasCoarseLocationOnly(applicationContext)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A `location`-typed foreground service (the only type this service declares in the
        // manifest) requires the runtime location permission AND an eligible start state on
        // Android 14+. If the permission isn't held — e.g. revoked by the user, or reset by a
        // reinstall before the app re-grants it, then a queued ACTION_START (boot receiver,
        // watchdog alarm, heartbeat worker, FCM wake) fires — the platform throws
        // SecurityException from startForeground(...LOCATION) and crashes the whole process.
        // Don't attempt it: stop cleanly and let a foreground caller restart tracking once
        // permission is granted.
        //
        // stopSelf() ALONE IS NOT ENOUGH. Once a caller has used startForegroundService(), the
        // platform demands a matching startForeground() whether or not the service then stops.
        // Skipping it crashes on Samsung/API 30 with:
        //
        //   FATAL EXCEPTION: main
        //   android.app.RemoteServiceException: Context.startForegroundService()
        //   did not then call Service.startForeground(): ServiceRecord{...
        //   com.pingolin.track.dev/...service.LocationTrackingService}
        //
        // which the user sees as "Pingolin Track keeps stopping" every time the app is opened
        // without the location permission (revoked by the user, reset by a reinstall, or denied
        // at the prompt).
        //
        // So satisfy the contract FIRST, with FOREGROUND_SERVICE_TYPE_NONE — the manifest
        // declares `location`, but a start may use a subset, and NONE needs no runtime
        // permission, so it cannot throw the SecurityException that starting as `location`
        // would. Then stop.
        //
        // The same when this phone's session store can't be opened now: nothing here runs
        // without it. Its words read nothing from that store: createNotification() does, and a
        // throw there would leave the contract unmet, and the platform would kill the app. The
        // watchdog looks again soon, and starts tracking once the store opens. No journal entry:
        // the journal is in that store.
        if (sessionManager.storeRefusal() != null) {
            // As the start below: its declared type first, NONE should that be refused (Android 14
            // refuses NONE to an app targeting it).
            val notification = buildNotification(getString(R.string.cant_start_title))
            val promoted = listOf(
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE,
            ).any { type ->
                runCatching { androidx.core.app.ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
                    .onFailure { Log.w(TAG, "startForeground($type) with the session store refused failed: ${it.javaClass.simpleName}") }
                    .isSuccess
            }
            if (promoted) {
                androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            // Sharing doesn't stop silently: a plain notice stays up until it starts again, and
            // saying it arms the watchdog's soonest look.
            NotSharingNotice.show(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hasPreciseLocationPermission()) {
            if (hasCoarseLocationOnly()) {
                // The platform WOULD let this start — see
                // hasPreciseLocationPermission. Refusing is deliberate: every
                // fix would be discarded by the accuracy filter, so starting
                // only produces a notification claiming tracking the user is
                // not getting.
                Log.w(TAG, "onStartCommand: only approximate location granted — every fix would be discarded; stopping")
                journalStop(TrackingStopReason.COARSE_LOCATION_ONLY)
            } else {
                Log.w(TAG, "onStartCommand: location permission not granted — cannot start location FGS; stopping")
                journalStop(TrackingStopReason.NO_LOCATION_PERMISSION)
            }
            try {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
                androidx.core.app.ServiceCompat.stopForeground(
                    this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
                )
            } catch (e: Exception) {
                // A background start can still be refused (12+). Nothing more to
                // do — but never let this path throw out of onStartCommand.
                Log.w(TAG, "no-permission startForeground(NONE) failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // CRITICAL: Always call startForeground() immediately when service is started
        // with startForegroundService() to avoid ForegroundServiceDidNotStartInTimeException
        // This must happen within 5 seconds of startForegroundService() being called.
        // Defense-in-depth: even with the permission held, an ineligible FGS
        // state (background-start restrictions on 12+, or a brief
        // permission/eligibility race) can still throw
        // ForegroundServiceStartNotAllowedException / SecurityException. Catch
        // it and bail rather than crash; the next foreground-context start
        // (MainActivity.onResume re-issues ACTION_START) recovers.
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
            // Sharing again: the notice that it couldn't be is down.
            NotSharingNotice.clear(this)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(location) failed: ${e.javaClass.simpleName}: ${e.message}; stopping")
            journalStop(TrackingStopReason.FOREGROUND_START_REFUSED)
            // Same contract problem as the no-permission branch above: the
            // typed start threw, so nothing has satisfied startForegroundService()
            // yet and stopSelf() alone would leave the platform to kill us with
            // RemoteServiceException. Try the untyped promotion before stopping.
            try {
                androidx.core.app.ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, createNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
                )
                androidx.core.app.ServiceCompat.stopForeground(
                    this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
                )
            } catch (e2: Exception) {
                Log.w(TAG, "fallback startForeground(NONE) also failed: ${e2.javaClass.simpleName}")
            }
            stopSelf()
            return START_NOT_STICKY
        }

        // Kick an expedited flush of the pending queue on every service start.
        // Service starts happen after crashes, boot, and user re-enables — all
        // scenarios where a backlog may exist and the 15-min periodic worker
        // would otherwise be the only thing draining it.
        try {
            com.pingolin.track.worker.LocationSyncWorker.syncNow(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule startup queue flush: ${e.message}")
        }

        when (intent?.action) {
            ACTION_START -> {
                // A start for no session, or for an account an admin hasn't approved, starts
                // nothing: say, the stopped notice's "Try again" tapped after a sign-out, or
                // kept from before the account went back to waiting. It never switches tracking
                // on below for whoever signs in next.
                if (!sessionManager.isLoggedIn() || sessionManager.isRegistrationUnapproved()) {
                    Log.i(TAG, "ACTION_START: not signed in, or still under review; ignoring start")
                    journalStop(TrackingStopReason.NOT_LOGGED_IN)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (sessionManager.isTrackingPaused()) {
                    Log.i(TAG, "ACTION_START: Tracking is paused by user, ignoring start")
                    journalStop(TrackingStopReason.PAUSED)
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Sync the tracking-enabled pref with reality here, not only from the Settings
                // toggle. If the service is genuinely starting to track, the pref must say so
                // — otherwise the heartbeat worker's staleness watchdog bails at
                // `isTrackingEnabled()` while the foreground service is very much alive, and
                // an install whose toggle was never touched sits at false permanently.
                sessionManager.setTrackingEnabled(true)

                val configuredInterval = sessionManager.getTrackingIntervalMs()
                val intentInterval = intent.getLongExtra(EXTRA_INTERVAL_MS, -1L)
                val intervalMs = if (intentInterval > 0) intentInterval else configuredInterval
                val finalInterval = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                // Battery saver comes from the stored preference, ALWAYS — not
                // from the intent extra. Only MainViewModel and BootReceiver
                // ever set the extra; the heartbeat worker, the watchdog and
                // both push paths start this service without it, so reading
                // the extra would silently drop a saver user back into
                // real-time tracking every time the OS killed the service and
                // one of those brought it back. Settings writes the preference
                // before it restarts tracking, so the preference is always the
                // current answer. The extra is still accepted, and only
                // logged.
                val batterySaver = sessionManager.isBatterySaverEnabled()
                if (intent.hasExtra(EXTRA_BATTERY_SAVER) &&
                    intent.getBooleanExtra(EXTRA_BATTERY_SAVER, batterySaver) != batterySaver
                ) {
                    Log.w(TAG, "ACTION_START: battery-saver extra disagrees with the stored preference; using the preference ($batterySaver)")
                }

                Log.i(TAG, "ACTION_START: configuredInterval=${configuredInterval}ms, intentInterval=${intentInterval}ms, finalInterval=${finalInterval}ms, batterySaver=$batterySaver")
                startTracking(finalInterval, batterySaver)
            }
            ACTION_STOP -> {
                stopTracking()
            }
            ACTION_UPDATE_SETTINGS -> {
                Log.i(TAG, "ACTION_UPDATE_SETTINGS: reloading settings and invalidating cache")
                reloadDistanceSettings()
                // Force circle cache refresh and send immediate location
                lastCacheRefreshTime = 0L
                // Circles changed (CircleRepository sends this on join/leave), and a new
                // circle can bring places with alerts on them: reload the boundary set on
                // the next fix rather than waiting out the 15-minute floor.
                lastBoundaryReloadElapsedMs = 0L
                // Send location immediately so new circles get a location right away
                serviceScope.launch {
                    try {
                        val location = locationProvider.lastLocation()
                        if (location != null) {
                            handleLocationUpdate(location)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send immediate location after settings update: ${e.message}")
                    }
                }
            }
            else -> {
                // Service restarted by Android (START_STICKY) with null intent. Re-start
                // tracking with saved settings instead of stopping, when the one rule
                // every path that brings tracking back asks says so: signed in, tracking
                // on, and an account an admin has approved.
                if (sessionManager.isTrackingPaused()) {
                    Log.i(TAG, "Service restarted but tracking is paused — stopping")
                    journalStop(TrackingStopReason.PAUSED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (sessionManager.shouldBeTracking()) {
                    val intervalMs = sessionManager.getTrackingIntervalMs()
                        .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                    val batterySaver = sessionManager.isBatterySaverEnabled()
                    Log.i(TAG, "Service restarted by system — resuming tracking (interval=${intervalMs}ms, batterySaver=$batterySaver)")
                    startTracking(intervalMs, batterySaver)
                } else {
                    Log.i(TAG, "Service restarted but not signed in, or still under review — stopping")
                    journalStop(TrackingStopReason.NOT_LOGGED_IN)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun startTracking(intervalMs: Long, batterySaver: Boolean = false) {
        // What brings this service back should it die without a word ([LocationKeepAlive]): held
        // here, where only a start that passed every gate arrives, at the cadence in force. A start
        // that is refused (signed out, paused, the store) or a stop never holds it.
        LocationKeepAlive.register(this, batterySaver, sessionManager.getBatterySaverInterval())
        if (isTracking) {
            // ACTION_START arrived on an already-running service. Re-register
            // the location callback so a service that was started in a
            // restricted FGS context (e.g. PUSH_MESSAGING, where Android 14+
            // refuses to deliver locations) can pick up location access if
            // the new caller has it — typically MainActivity foregrounding.
            // Without this the FGS could be alive but blind for the rest of
            // its lifetime.
            Log.i(TAG, "Already tracking — re-attaching location updates to refresh FGS capability")
            isRunning = true
            if (batterySaver != isBatterySaverMode) {
                // Battery saver was switched while the service ran. Re-tune to the new
                // mode; the re-subscription is also the re-attach.
                isBatterySaverMode = batterySaver
                lastPostedNotificationText = null
                applyPollingProfile("battery saver ${if (batterySaver) "on" else "off"}")
            } else {
                reattachLocationUpdates()
            }
            // A restart of a running service is often the watchdog saying no fix
            // has arrived. Re-attaching a BALANCED subscription that was
            // delivering nothing would deliver nothing again, so take one precise
            // fix when the last one is more than a poll old — the fix a HIGH
            // re-attach would produce.
            val fixAge = lastFixAgeMs()
            if (currentPollAccuracy == LocationAccuracy.BALANCED &&
                (fixAge == TrackingHealth.NO_FIX || fixAge > currentPollIntervalMs)
            ) {
                requestPreciseFix("restart with no fix for ${if (fixAge < 0) "ever" else "${fixAge / 1000}s"}")
            }
            // An ACTION_START on an already-running service (foreground,
            // watchdog re-arm, push) is a good moment to re-pull the boundary
            // set so a place that gained an alert since the last reload starts
            // being monitored promptly, rather than waiting for the periodic
            // refresh in the location loop.
            serviceScope.launch { refreshBoundaries() }
            return
        }

        // Initialize monitoring manager for E2EE-compliant battery and inactivity alerts
        serviceScope.launch {
            monitoringManager.initialize()
        }

        // Initialize boundary monitoring for E2EE-compliant place alerts
        serviceScope.launch {
            initializeBoundaryMonitoring()
        }

        serviceScope.launch {
            initializeSessionKeys()
        }

        // Periodic notification refresh so "Last shared Nm ago" keeps aging even if
        // no location callbacks fire (e.g., user is stationary for a long time and
        // the distance filter suppresses sends).
        serviceScope.launch {
            // Whether any circle receives the location, before the first fix says so.
            refreshSharingNow()
            lastNotificationUpdateTime = 0L
            updateNotification(null)
            while (isActive) {
                delay(NOTIFICATION_TICK_INTERVAL_MS)
                // A pause set or ended in the app shows within a tick, not at the next fix.
                refreshSharingNow()
                // Force past the updateNotification throttle so the age actually refreshes.
                lastNotificationUpdateTime = 0L
                updateNotification(null)
                // Rides this existing tick on purpose: a dedicated timer for the
                // check-in would add wakeups and give back part of what
                // stretching the poll just saved.
                maybeSendLivenessCheckIn()
                maybeRefreshPreciseFix()
            }
        }

        // Start session key rotation timer (every 5h45m to overlap with 6h key duration)
        serviceScope.launch {
            // Check immediately on start, then periodically
            rotateSessionKeys()
            while (isActive) {
                delay(SESSION_KEY_ROTATION_INTERVAL_MS)
                rotateSessionKeys()
            }
        }

        // Periodic client-side evaluation of the alert families the E2EE-blind server cannot
        // judge: inactivity (is a watched member silent?) and subscriber-side place alerts
        // (did a watched member cross one of my places?). Both are decided here, from member
        // locations this device refreshes and decrypts itself — see
        // runBackgroundEvaluationPass.
        serviceScope.launch {
            while (isActive) {
                delay(BACKGROUND_EVAL_INTERVAL_MS)
                runBackgroundEvaluationPass()
            }
        }

        isBatterySaverMode = batterySaver

        isDistanceTrackingEnabled = sessionManager.isDistanceSendingEnabled()
        distanceThresholdMeters = sessionManager.getDistanceSendingThreshold().toFloat()
        lastSentLocation = null // Reset on start

        // Seed lastSuccessfulSendAt from persisted SessionManager so we don't claim
        // "waiting for first send" when LocationHeartbeatWorker has been uploading
        // in the background while the service was dead.
        lastSuccessfulSendAt = sessionManager.getLastSuccessfulSendAt()

        // Pre-open the upload stream in real-time mode only. Battery saver does NOT skip
        // streaming — every send goes over the same stream, opened lazily by the first
        // one (LocationRepository.sendEnvelope) — it only skips opening it up front,
        // where it would sit idle for up to a whole batch interval before anything used
        // it.
        if (!batterySaver) {
            // Registration must complete before the stream opens, or the server rejects
            // locations from an unknown device.
            serviceScope.launch {
                if (locationRepository.ensureDeviceRegistered()) {
                    val deviceId = sessionManager.getDeviceId()
                    locationRepository.startStreaming(deviceId)
                } else {
                    Log.w(TAG, "Device registration failed, streaming will start when location is sent")
                }
            }
        }

        // Initialize motion state from last persisted value so polling profile is sane
        // before Activity Recognition delivers its first transition event.
        motionState = sessionManager.getLastMotionState() ?: MotionState.UNKNOWN
        movingSinceMs = if (motionState == MotionState.MOVING) System.currentTimeMillis() else 0L
        movingAnchor = null
        stationaryWhileMoving = false
        forceSendNextFix = false
        lastBatteryTier = computeBatteryTier()

        // Start Activity Recognition. Motion transitions drive the polling profile
        // (STILL backs off, MOVING bursts) and trigger a forced trip-start send on
        // transitions into MOVING.
        try {
            motionDetectionManager.start()
        } catch (e: Exception) {
            Log.w(TAG, "Motion detection start failed: ${e.message}")
        }

        // Observe motion state. Each transition:
        //   - recomputes the polling interval for the new state
        //   - on (any → MOVING), arms forceSendNextFix so the trip is anchored at start
        serviceScope.launch {
            motionDetectionManager.motionState.collect { newState ->
                val prev = motionState
                motionState = newState
                if (prev != newState) {
                    Log.i(TAG, "Motion transition: $prev -> $newState")
                    // Any real transition ends the GPS-evidence override: the detector
                    // has spoken again, and a new MOVING starts its own clock.
                    stationaryWhileMoving = false
                    movingAnchor = null
                    movingSinceMs = if (newState == MotionState.MOVING) System.currentTimeMillis() else 0L
                    if (prev != MotionState.MOVING && newState == MotionState.MOVING) {
                        forceSendNextFix = true
                        Log.i(TAG, "Trip start — will force-send the next fresh fix")
                    }
                    applyPollingProfile("motion $prev -> $newState")
                }
            }
        }

        // Compute the initial polling profile. GPS sample cadence is decoupled from
        // the user's time-based send interval — we poll often to detect movement, and
        // the send filter in handleLocationUpdate decides what actually goes on the wire.
        val profile = computePollProfile()
        currentPollIntervalMs = profile.intervalMs
        currentPollAccuracy = profile.accuracy
        publishedPollIntervalMs = profile.intervalMs

        locationUpdates = locationProvider.requestUpdates(
            buildLocationRequest(profile),
            locationListener,
        )

        isTracking = true
        isRunning = true
        // Tracking is genuinely up: record it and clear any recovery state, so
        // the watchdog stops counting failures and the "tracking stopped"
        // notification (if one is showing) is taken down.
        journalEvent(TrackingEvent.STARTED)
        sessionManager.clearTrackingRecoveryState()
        TrackingHealthNotifier.clear(this)
        val mode = if (batterySaver) "battery saver" else "real-time"
        val timeInfo = if (sessionManager.isTimeBasedTrackingEnabled()) "time=${intervalMs}ms" else "time=off"
        val distInfo = if (isDistanceTrackingEnabled) "distance=${distanceThresholdMeters.toInt()}m" else "distance=off"
        Log.i(TAG, "Location tracking started ($mode) $timeInfo, $distInfo, poll=${currentPollIntervalMs}ms/${currentPollAccuracy}, motion=$motionState, batteryTier=$lastBatteryTier")
    }

    /**
     * The listener is created once and reused across every re-subscription, so
     * changing the polling interval is cancel + request with the SAME callback
     * object.
     */
    private val locationListener = object : LocationUpdateListener {
        override fun onLocation(location: android.location.Location) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Location callback: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}m")
            }
            handleLocationUpdate(location)
        }

        override fun onAvailabilityChanged(isAvailable: Boolean) {
            Log.d(TAG, "Location availability changed: isAvailable=$isAvailable")
        }
    }

    private fun buildLocationRequest(profile: PollProfile): LocationUpdateRequest {
        return LocationUpdateRequest(
            intervalMs = profile.intervalMs,
            // HIGH while moving or unsure; BALANCED while STILL and in battery saver.
            // See pollProfileFor.
            accuracy = profile.accuracy,
            // The dedicated HandlerThread, so fix processing — which touches Room
            // and the boundary evaluator — never runs on the main thread.
            looper = locationHandlerThread?.looper ?: Looper.getMainLooper(),
        )
    }

    /**
     * The liveness check-in that runs when no fix has arrived to carry one.
     *
     * The inbound path decides history-vs-check-in for every fix it gets
     * ([decideSend]), so on a device whose GPS is delivering, this is a no-op — the
     * due test below is already satisfied by whatever that path just sent. What it
     * covers is the gap: while STILL the poll stretches to POLL_STILL_MS and, under
     * Doze or a stuck provider, further still. Without this, a stationary phone's
     * "last seen" would age with the poll rather than with the check-in cadence, and
     * the watchers would read a live phone as gone.
     *
     * It re-sends the cached fix on the check-in cadence, not the user's send
     * interval, and what it sends is a check-in (latest position only), not a
     * history row.
     *
     * Deliberately reuses handleLocationUpdate rather than duplicating the send:
     * that path also runs the boundary and destination checks, and re-running those
     * on identical coordinates is idempotent (both are ledger/status guarded).
     * Re-implementing the send would mean re-implementing those invariants.
     *
     * Not gated on motionState == STILL. The due test is a better gate than the
     * motion state: a device that is moving has just sent something, so this returns
     * immediately, while a device whose activity recognition never reports STILL
     * would otherwise fall through this hole and go silent.
     *
     * Honesty guard: if the cached fix is older than
     * STILL_REFRESH_MAX_FIX_AGE_FACTOR x the poll interval, GPS has stopped
     * delivering and we must NOT dress an ancient position up as current. A check-in
     * asserts "I am here NOW", so this guard matters all the more: the honest
     * outcome is to let the timestamp go stale so the warning fires.
     */
    private fun maybeSendLivenessCheckIn() {
        if (!isTracking) return
        // Distance-only users asked NOT to be sent on a timer, and a check-in is a
        // timed send. See decideSend.
        if (!sessionManager.isTimeBasedTrackingEnabled()) return

        // Battery saver is NOT excluded: a phone saving power that stopped refreshing its
        // own liveness would read as dead to everyone watching it — the one outcome
        // check-ins exist to avoid. It checks in on a slower cadence instead; see
        // livenessIntervalMs.
        //
        // The newest position ANY path took, not only this service's: a send made outside it
        // (send-now, the map-open refresh, a check-in...) never reaches lastKnownFix, so
        // without this the older place would be re-sent, stamped now, and the circle would
        // see the person back there.
        adoptNewerOwnFix()
        val cached = lastKnownFix ?: return
        if (!livenessDue(
                System.currentTimeMillis() - lastSendTime,
                livenessIntervalMs(),
                currentPollIntervalMs,
            )
        ) {
            return
        }

        // Never older than a position already sent: a send-now rougher than the holder keeps (100 m,
        // often so indoors) still goes out, so re-sending this older fix stamped now would put the
        // person back where they were. Skipped: the circle keeps the newer one with its real age,
        // and the next fix this service takes in resumes the check-ins.
        if (newestOwnFix.goesBack(cached)) {
            Log.d(TAG, "Liveness check-in skipped: a newer position than the cached one was already sent")
            return
        }

        // The cache is as fresh as the newer of the precise fix itself and the last
        // coarse STILL fix that confirmed the phone is still within reach of it
        // (coarseStillFixVerdict). While STILL the poll is BALANCED, so the precise fix
        // ages while wifi/cell fixes keep proving the phone has not gone anywhere; a
        // check-in re-sends the precise position either way, never the coarse one.
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val fixAgeMs = (nowNanos - cached.elapsedRealtimeNanos) / 1_000_000L
        val presenceAgeMs = lastPresenceConfirmedElapsedNanos
            .takeIf { it > cached.elapsedRealtimeNanos }
            ?.let { (nowNanos - it) / 1_000_000L }
        val cacheAgeMs = if (presenceAgeMs != null) minOf(fixAgeMs, presenceAgeMs) else fixAgeMs
        if (stillRefreshCacheTooOld(cacheAgeMs, currentPollIntervalMs)) {
            Log.d(TAG, "Liveness check-in skipped: cached fix is ${cacheAgeMs / 1000}s old — letting the timestamp go stale rather than faking it")
            return
        }

        Log.d(TAG, "Liveness check-in: re-sending cached fix (age=${cacheAgeMs / 1000}s) with a current timestamp")
        handleLocationUpdate(cached, isLivenessCheckIn = true)
    }

    /**
     * Take a newer fix that a path outside this service took
     * ([NewestOwnFix][com.pingolin.track.location.NewestOwnFix]) as this service's last known one
     * ([livenessPayload]): a check-in then re-sends it, and its honesty guard and presence confirmation
     * measure that fix, not the older one. A presence confirmed before was about the older fix, so it goes.
     */
    private fun adoptNewerOwnFix() {
        val own = lastKnownFix
        val newest = livenessPayload(own, newestOwnFix.newest()) ?: return
        if (newest === own) return
        lastKnownFix = newest
        lastPresenceConfirmedElapsedNanos = 0L
    }

    /**
     * A fix a path outside this service took and sent
     * ([NewestOwnFix][com.pingolin.track.location.NewestOwnFix]): the same bookkeeping and place-crossing
     * check as one of its own, and no second send. The crossing report goes out as for any fix; the native
     * checker's inside/outside state is the one every fix moves, so a later fix at the same place reports
     * nothing again. Only while tracking, and only when newer than what this service already has.
     */
    private fun takeOneShotFix(fix: android.location.Location) {
        if (!isTracking || !oneShotIsNews(lastKnownFix, fix)) return
        handleLocationUpdate(fix, alreadySent = true)
    }

    /**
     * How often this device checks in when it has not moved: the floor, or the user's
     * own send interval, whichever is longer. See [livenessIntervalMsFor].
     *
     * Battery saver slows the cadence rather than stopping it — see
     * [LIVENESS_CHECKIN_INTERVAL_BATTERY_SAVER_MS].
     */
    private fun livenessIntervalMs(): Long = livenessIntervalMsFor(
        userIntervalMs = sessionManager.getTrackingIntervalMs(),
        batterySaver = isBatterySaverMode,
        saverIntervalMs = saverIntervalMsFor(sessionManager.getBatterySaverInterval()),
    )

    /**
     * Promote UNKNOWN -> STILL once the device has demonstrably held one spot.
     *
     * Only ever fires while motionState is UNKNOWN: once activity recognition has
     * an opinion (it fires on the first real transition) that opinion wins, and
     * this steps out of the way. The anchor resets the moment a fix lands outside
     * the radius, which is what makes it safe for a moving device — it simply
     * never accumulates the required span.
     */
    private fun maybeInferStill(location: android.location.Location) {
        if (motionState != MotionState.UNKNOWN) {
            // AR has spoken (or we already inferred); drop the anchor so a later
            // return to UNKNOWN starts a fresh observation rather than reusing a
            // stale one.
            stillAnchor = null
            return
        }

        val now = System.currentTimeMillis()
        val anchor = stillAnchor
        // Judge stillness at the ROUGHER of the fixed radius and this fix's own
        // accuracy — see [stillInferRadiusFor]. Holding a 500 m fix to a 50 m
        // anchor is not a strict test, it is an impossible one.
        val radiusM = stillInferRadiusFor(
            if (location.hasAccuracy()) location.accuracy else null
        )
        if (anchor == null || anchor.distanceTo(location) > radiusM) {
            stillAnchor = location
            stillAnchorAtMs = now
            return
        }

        if (!shouldInferStill(anchor.distanceTo(location), now - stillAnchorAtMs, radiusM)) return

        motionState = MotionState.STILL
        sessionManager.setLastMotionState(MotionState.STILL)
        stillAnchor = null
        Log.i(
            TAG,
            "Inferred STILL: held within ${radiusM.toInt()}m for " +
                "${(now - stillAnchorAtMs) / 1000}s with no activity-recognition transition"
        )
        applyPollingProfile("inferred STILL")
    }

    /**
     * Hold the STILL cadence while MOVING once GPS says the phone has not gone
     * anywhere — see [shouldHoldStillCadenceWhileMoving]. Fresh, accepted fixes only.
     *
     * The mirror of [maybeInferStill] for the other state the motion detector can
     * leave the service stuck in. The motion state itself is not touched: the
     * detector's accelerometer keeps listening, and its STILL ends this the normal
     * way. What changes is the poll, and the first fix outside the radius restores
     * it.
     */
    private fun maybeHoldStillCadenceWhileMoving(location: android.location.Location) {
        if (motionState != MotionState.MOVING || isBatterySaverMode) {
            movingAnchor = null
            return
        }
        val now = System.currentTimeMillis()
        val radiusM = stillInferRadiusFor(if (location.hasAccuracy()) location.accuracy else null)
        val anchor = movingAnchor
        if (anchor == null || anchor.distanceTo(location) > radiusM) {
            movingAnchor = location
            movingAnchorAtMs = now
            if (stationaryWhileMoving) {
                stationaryWhileMoving = false
                Log.i(TAG, "Left the spot while MOVING — back to the moving cadence")
                applyPollingProfile("left the spot while MOVING")
            }
            return
        }
        if (stationaryWhileMoving) return
        if (shouldHoldStillCadenceWhileMoving(
                movingForMs = now - movingSinceMs,
                distanceFromAnchorM = anchor.distanceTo(location),
                elapsedSinceAnchorMs = now - movingAnchorAtMs,
                radiusM = radiusM,
            )
        ) {
            stationaryWhileMoving = true
            Log.i(
                TAG,
                "GPS evidence: held within ${radiusM.toInt()}m for ${(now - movingAnchorAtMs) / 1000}s " +
                    "while the motion detector still says MOVING — polling at the STILL cadence"
            )
            applyPollingProfile("stationary while MOVING (GPS evidence)")
        }
    }

    /** The profile the service should be polling with right now. See [pollProfileFor]. */
    private fun computePollProfile(): PollProfile = pollProfileFor(
        motion = motionState,
        stationaryWhileMoving = stationaryWhileMoving,
        batterySaver = isBatterySaverMode,
        saverIntervalMs = saverIntervalMsFor(sessionManager.getBatterySaverInterval()),
        multiplier = batteryMultiplierFor(cachedBatteryLevel, cachedIsCharging, cachedPowerSaveMode),
        timeBasedEnabled = sessionManager.isTimeBasedTrackingEnabled(),
        userIntervalMs = sessionManager.getTrackingIntervalMs(),
    )
    // POLL_STILL_MS and LIVENESS_CHECKIN_INTERVAL_MS are both 5 minutes on purpose: while
    // still, SENDS arrive at the poll cadence (they are filtered from inbound fixes), so a
    // check-in rides a fix the device was taking anyway, and staleSendThresholdMsFor()
    // takes the check-in cadence as one of its terms so the warning is not measured
    // against a clock that stopped driving sends. Exempting STILL from the user-interval
    // cap is what makes POLL_STILL_MS reachable at all: with the cap, a still phone keeps
    // polling near the user's interval.

    private fun computeBatteryTier(): Int =
        batteryTierFor(cachedBatteryLevel, cachedIsCharging, cachedPowerSaveMode)

    /**
     * Recompute the polling profile and re-subscribe the LocationProvider if the
     * interval or the accuracy changed. No-op when not tracking or when nothing
     * changed.
     *
     * Battery saver goes through the same path and simply computes its flat
     * profile; exempting it would freeze a saver user's subscription at whatever
     * cadence the service started with (20 s if the last motion state was
     * MOVING) for the life of the service.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun applyPollingProfile(reason: String) {
        if (!isTracking) return
        val current = locationUpdates ?: return
        val profile = computePollProfile()
        if (profile.intervalMs == currentPollIntervalMs && profile.accuracy == currentPollAccuracy) return

        Log.i(
            TAG,
            "Polling profile update ($reason): ${currentPollIntervalMs}ms/$currentPollAccuracy -> " +
                "${profile.intervalMs}ms/${profile.accuracy}"
        )
        currentPollIntervalMs = profile.intervalMs
        currentPollAccuracy = profile.accuracy
        publishedPollIntervalMs = profile.intervalMs
        current.cancel()
        locationUpdates = locationProvider.requestUpdates(
            buildLocationRequest(profile), locationListener
        )
    }

    /**
     * Re-register the fused location callback without changing the polling
     * profile. Needed when ACTION_START arrives from a context that may have
     * foreground capability after the service was originally started by an FCM
     * wake (where Android 14+ blocks location/camera/microphone access on the
     * FGS). Without this, the service stays alive but blind.
     *
     * Not skipped in battery saver: a saver service started blind (from a push
     * or a boot broadcast) would have no way back to location access at all.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun reattachLocationUpdates() {
        if (!isTracking) return
        val current = locationUpdates ?: return
        current.cancel()
        locationUpdates = locationProvider.requestUpdates(
            buildLocationRequest(PollProfile(currentPollIntervalMs, currentPollAccuracy)),
            locationListener,
        )
    }

    /** Age of the last accepted fix, or [TrackingHealth.NO_FIX] if there is none. */
    private fun lastFixAgeMs(): Long {
        // The in-memory clock is ahead of the persisted one by up to a minute — see
        // stampFixHealth — so read whichever is newer.
        val at = maxOf(lastFixAtMs, sessionManager.lastLocationFixAt())
        return if (at <= 0L) TrackingHealth.NO_FIX
        else (System.currentTimeMillis() - at).coerceAtLeast(0L)
    }

    /**
     * A genuinely new fix has arrived: stamp the health clock the watchdog reads,
     * and clear any recovery state and "tracking stopped" notification — a fix is
     * proof it is working again.
     *
     * Unconditional, each would cost a write on EVERY fix — an
     * EncryptedSharedPreferences rewrite for the timestamp, another for the
     * recovery state, and two NotificationManager IPCs, 360 of each an hour while
     * moving. So the timestamp is persisted at most once a minute (the watchdog
     * reads it every 20), and the other three happen only when something is
     * actually set.
     */
    private fun stampFixHealth() {
        val now = System.currentTimeMillis()
        lastFixAtMs = now
        if (now - lastFixPersistedAtMs >= FIX_HEALTH_PERSIST_MIN_SPACING_MS) {
            lastFixPersistedAtMs = now
            sessionManager.recordLocationFix(now)
        }
        if (sessionManager.hasTrackingRecoveryState()) sessionManager.clearTrackingRecoveryState()
        TrackingHealthNotifier.clearIfShown(this)
    }

    private fun journalEvent(event: TrackingEvent, reason: TrackingStopReason = TrackingStopReason.NONE) {
        try {
            sessionManager.trackingJournal.record(event, reason, lastFixAgeMs())
        } catch (e: Exception) {
            // The journal exists to explain failures; it must never cause one.
            Log.w(TAG, "Failed to write tracking journal entry: ${e.message}")
        }
    }

    private fun journalStop(reason: TrackingStopReason) = journalEvent(TrackingEvent.STOPPED, reason)

    private fun stopTracking() {
        // Recorded here rather than at each caller: this is the single point
        // every deliberate stop passes through, and a clean stop that leaves
        // no record leaves an outage nobody can explain.
        journalStop(TrackingStopReason.USER_ACTION)
        // A deliberate stop (sign-out among them): nothing brings it back by itself.
        LocationKeepAlive.unregister(this)
        KeepAliveGeofence.disarm(this)
        geofenceCenter = null

        locationUpdates?.cancel()
        locationUpdates = null

        try {
            motionDetectionManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Motion detection stop failed: ${e.message}")
        }

        locationRepository.stopStreaming()

        isTracking = false
        isRunning = false
        publishedPollIntervalMs = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        Log.i(TAG, "Location tracking stopped")
    }

    /**
     * Called when the user changes settings while tracking is already active.
     */
    private fun reloadDistanceSettings() {
        if (!isTracking) return

        val wasEnabled = isDistanceTrackingEnabled
        isDistanceTrackingEnabled = sessionManager.isDistanceSendingEnabled()
        distanceThresholdMeters = sessionManager.getDistanceSendingThreshold().toFloat()

        Log.i(TAG, "Distance filter: was=$wasEnabled, now=$isDistanceTrackingEnabled, threshold=${distanceThresholdMeters.toInt()}m")

        // Battery saver and its batch interval are settings too. Re-read them here so a
        // change takes effect at once — the profile below re-subscribes if it moved.
        val saver = sessionManager.isBatterySaverEnabled()
        if (saver != isBatterySaverMode) {
            Log.i(TAG, "Battery saver: was=$isBatterySaverMode, now=$saver")
            isBatterySaverMode = saver
            lastPostedNotificationText = null
        }
        // The keep-alive at the cadence now in force: never more often than this service's own.
        LocationKeepAlive.register(this, saver, sessionManager.getBatterySaverInterval())

        // Send interval / time-enabled toggle can change the poll cap. Reapply profile.
        applyPollingProfile("settings reload")
    }

    private var lastSendTime = 0L

    // Wall-clock timestamp of the last SEND that actually succeeded (any target). 0 = never.
    // `lastSendTime` above is set pre-send for the time-based filter; this field is set
    // only from success branches so the notification and staleness warning tell the truth.
    // Reads via [observedLastSuccessfulSendAt] also pull in the persisted SessionManager
    // value so out-of-process success (e.g. LocationHeartbeatWorker) is reflected in
    // the notification.
    @Volatile private var lastSuccessfulSendAt: Long = 0L

    private fun observedLastSuccessfulSendAt(): Long {
        val persisted = sessionManager.getLastSuccessfulSendAt()
        val current = lastSuccessfulSendAt
        if (persisted > current) {
            lastSuccessfulSendAt = persisted
            return persisted
        }
        return current
    }

    // Latch so we emit SendingStale once per stale transition, not on every periodic tick.
    @Volatile private var staleWarningEmitted: Boolean = false

    // Self-heal counters, driving the key re-import in onAllSendsFailed. When every target
    // send in a row fails (envelope AND per-target fallback both land nowhere), local
    // circle-key state is the likeliest culprit: the typical shape is a long gap after a
    // package update, with `unwrapPlaceKey failed` logged by PlaceRepository at the same
    // time. See KEY_HEAL_FAILURE_THRESHOLD / KEY_HEAL_COOLDOWN_MS for the tuning.
    @Volatile private var consecutiveSendFailures: Int = 0
    @Volatile private var lastKeyHealAttemptAtMs: Long = 0L

    private fun onAnySendLanded() {
        consecutiveSendFailures = 0
        rearmWatchdogIfDue()
    }

    /**
     * Push the watchdog alarm 20 minutes out again: it is a dead man's switch, so a
     * service that is landing sends never hears from it, and only silence lets it
     * fire — no wakeups at all on a perfectly healthy phone. Throttled by
     * [watchdogRearmDue]; the watchdog's own tick re-arms it too, so a quiet
     * service keeps being checked every 20 minutes.
     */
    private fun rearmWatchdogIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (!watchdogRearmDue(now, lastWatchdogRearmElapsedMs)) return
        lastWatchdogRearmElapsedMs = now
        try {
            // Never resurrect the alarm for a session that has just ended: the
            // watchdog's cancel() at logout must stay cancelled.
            if (!sessionManager.isLoggedIn()) return
            WatchdogAlarmReceiver.schedule(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-arm the watchdog after a send: ${e.message}")
        }
    }

    private fun onAllSendsFailed() {
        consecutiveSendFailures += 1
        val now = System.currentTimeMillis()
        if (consecutiveSendFailures < KEY_HEAL_FAILURE_THRESHOLD) return
        if (now - lastKeyHealAttemptAtMs < KEY_HEAL_COOLDOWN_MS) return
        lastKeyHealAttemptAtMs = now
        Log.w(TAG, "Send chain failed $consecutiveSendFailures× in a row — refreshing circle keys")
        serviceScope.launch {
            try {
                val result = circleRepository.importCircleKeys()
                if (result.isSuccess) {
                    Log.i(TAG, "Self-heal: imported ${result.getOrNull()} circle keys; next send should encrypt cleanly")
                    // Don't reset consecutiveSendFailures here — let the
                    // next successful send do it via onAnySendLanded so
                    // we get an unambiguous signal that recovery worked.
                } else {
                    val e = result.exceptionOrNull()
                    Log.w(TAG, "Self-heal importCircleKeys failed: ${e?.javaClass?.simpleName}: ${e?.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Self-heal importCircleKeys threw: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * A fix too rough for the real-time gate, arriving while STILL at
     * [LocationAccuracy.BALANCED] — see [coarseStillFixVerdict].
     *
     * It is never a position: it does not move the marker, write history, anchor a
     * trip or reach the place-alert and arrival checks, all of which keep needing a
     * fix that passes the 100 m gate. What it can say is whether the phone is still
     * where the last precise fix put it. If so, the precise position stays honest to
     * re-send as a liveness check-in; if not, one precise fix is taken to find out.
     */
    private fun handleCoarseStillFix(location: android.location.Location) {
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        if (fixAgeMs > STALE_FIX_MAX_AGE_MS) {
            Log.d(TAG, "Skipping stale coarse fix (age=${fixAgeMs}ms > ${STALE_FIX_MAX_AGE_MS}ms)")
            return
        }
        // Measured from the newest position any path took, so a presence it confirms is
        // about the fix a check-in will re-send.
        adoptNewerOwnFix()
        val precise = lastKnownFix
        val distanceM = precise?.distanceTo(location)
        when (coarseStillFixVerdict(location.accuracy, distanceM)) {
            CoarseStillVerdict.CONFIRMS_PRESENCE -> {
                lastPresenceConfirmedElapsedNanos = location.elapsedRealtimeNanos
                // A fresh fix, even a rough one, is proof the location stack works.
                stampFixHealth()
                Log.d(TAG, "Coarse STILL fix (${location.accuracy.toInt()}m) confirms presence ${distanceM?.toInt()}m from the last precise fix")
                maybeSendLivenessCheckIn()
            }
            CoarseStillVerdict.SUGGESTS_DEPARTURE -> {
                stampFixHealth()
                requestPreciseFix(
                    if (distanceM == null) "no precise fix to compare a coarse STILL fix with"
                    else "coarse STILL fix ${distanceM.toInt()}m from the last precise fix"
                )
            }
            CoarseStillVerdict.TOO_ROUGH -> {
                // Too rough to judge — a cell-only area. It still proves the
                // location stack is delivering, so the watchdog must not call
                // the service blind, and once the precise fix is a whole poll
                // old it is refreshed with a HIGH one-shot: what a HIGH STILL
                // poll would cost, paid only where wifi/cell cannot do the
                // job.
                stampFixHealth()
                val preciseAgeMs = precise?.let {
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L
                }
                if (preciseAgeMs == null || preciseAgeMs >= currentPollIntervalMs) {
                    requestPreciseFix("coarse STILL fix too rough to judge (${location.accuracy.toInt()}m)")
                } else {
                    Log.d(TAG, "Coarse STILL fix too rough to judge (${location.accuracy.toInt()}m) — precise fix still recent")
                }
            }
        }
    }

    /**
     * The backstop for a BALANCED poll that delivers nothing at all (network location
     * switched off, nothing in range). With no fix of any kind for two polls, take one
     * precise fix rather than let the watchdog call the service blind and eventually
     * tell the user tracking has stopped. Runs on the 60 s notification tick.
     */
    private fun maybeRefreshPreciseFix() {
        if (!isTracking || currentPollAccuracy != LocationAccuracy.BALANCED) return
        val age = lastFixAgeMs()
        if (age == TrackingHealth.NO_FIX || age > 2 * currentPollIntervalMs) {
            requestPreciseFix("no fix of any kind at BALANCED for ${if (age < 0) "ever" else "${age / 1000}s"}")
        }
    }

    /**
     * One HIGH one-shot, when a coarse STILL fix suggests the phone has moved. The
     * fix goes through the normal path; if it confirms a departure
     * ([preciseFixConfirmsDeparture]) the service stops trusting STILL and polls as
     * UNKNOWN — the significant-motion trigger evidently did not fire — until the
     * motion detector or [maybeInferStill] has an opinion again. One in flight at a
     * time, and time-boxed so a fix that never comes also stops costing power.
     */
    @SuppressLint("MissingPermission")
    private fun requestPreciseFix(reason: String) {
        if (preciseFixInFlight) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!preciseFixRequestDue(nowElapsed, lastPreciseFixRequestElapsedMs, currentPollIntervalMs)) {
            Log.d(TAG, "Precise fix not requested ($reason): one was taken less than a poll ago")
            return
        }
        lastPreciseFixRequestElapsedMs = nowElapsed
        preciseFixInFlight = true
        Log.i(TAG, "Requesting one precise fix: $reason")
        serviceScope.launch {
            try {
                val fix = withTimeoutOrNull(PRECISE_ONE_SHOT_TIMEOUT_MS) {
                    locationProvider.currentLocation(LocationAccuracy.HIGH)
                }
                if (fix == null) {
                    Log.d(TAG, "Precise one-shot produced no fix")
                    return@launch
                }
                val previous = lastKnownFix
                if (motionState == MotionState.STILL && previous != null &&
                    preciseFixConfirmsDeparture(
                        distanceM = previous.distanceTo(fix),
                        fixAccuracyM = if (fix.hasAccuracy()) fix.accuracy else 0f,
                        previousAccuracyM = if (previous.hasAccuracy()) previous.accuracy else 0f,
                    )
                ) {
                    Log.i(TAG, "Precise fix confirms a departure while STILL (${previous.distanceTo(fix).toInt()}m) — polling as UNKNOWN")
                    motionState = MotionState.UNKNOWN
                    stillAnchor = null
                    applyPollingProfile("departure confirmed while STILL")
                }
                handleLocationUpdate(fix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Precise one-shot failed: ${e.message}")
            } finally {
                preciseFixInFlight = false
            }
        }
    }

    /** Where this start armed the keep-alive geofence, and when: this start's own, in memory. */
    @Volatile private var geofenceCenter: android.location.Location? = null
    private var geofenceArmedAtElapsed = 0L

    /**
     * Arm [KeepAliveGeofence] around [fix] when this start has none yet (or location was switched
     * off and on since: [locationModeReceiver]), or once the phone is out of the one armed and it was
     * armed at least [GEOFENCE_REARM_MIN_MS] ago: a moving phone moves it along a few times an hour
     * at most, a still one never.
     */
    private fun maybeArmGeofence(fix: android.location.Location) {
        // None without precise location allowed all the time, and none counted as armed: the first fix
        // once "Allow all the time" is back arms it (it can come back while this service runs).
        if (!LocationPolicy.hasPreciseLocation(this) || !LocationPolicy.hasBackgroundLocation(this)) {
            if (geofenceCenter != null) KeepAliveGeofence.disarm(this)
            geofenceCenter = null
            return
        }
        val center = geofenceCenter
        val now = SystemClock.elapsedRealtime()
        if (center != null && !geofenceRearmDue(center.distanceTo(fix), now - geofenceArmedAtElapsed)) return
        KeepAliveGeofence.arm(this, fix.latitude, fix.longitude)
        geofenceCenter = android.location.Location(fix)
        geofenceArmedAtElapsed = now
    }

    private fun handleLocationUpdate(
        location: android.location.Location,
        isLivenessCheckIn: Boolean = false,
        /** Taken and sent outside this service ([takeOneShotFix]): bookkeeping and checks, no send. */
        alreadySent: Boolean = false,
    ) {
        // Filter out low-accuracy locations to save bandwidth, at the threshold
        // of the mode: the real-time threshold in battery saver would discard
        // every fix between 100 m and 500 m — reporting nothing rather than
        // something approximate, which is the worse of the two for a
        // location-sharing app.
        //
        // Battery saver ASKS for rough fixes too — it polls at
        // LocationAccuracy.BALANCED (see pollProfileFor), so the relaxed
        // threshold matches the request, as it does on iOS.
        val minAccuracy = LocationPolicy.minAccuracyMeters(isBatterySaverMode)
        if (location.hasAccuracy() && location.accuracy > minAccuracy) {
            // Not a position — but while STILL at BALANCED it is still evidence of
            // where the phone is NOT. See handleCoarseStillFix.
            if (!isLivenessCheckIn && !isBatterySaverMode && currentPollAccuracy == LocationAccuracy.BALANCED) {
                handleCoarseStillFix(location)
                return
            }
            Log.d(TAG, "Skipping location with accuracy ${location.accuracy}m (threshold: ${minAccuracy}m)")
            return
        }

        // Stale-fix guard. Either LocationProvider can deliver a cached fix as the
        // first callback after subscribing — fused because setWaitForAccurateLocation
        // is false, LocationManager because it seeds a new subscription from the
        // provider's last known fix. If that cached fix is old and we let it through, it becomes the trip
        // anchor and every subsequent distance-filter is measured from the wrong point.
        // Reject anything older than STALE_FIX_MAX_AGE_MS — we'll get a fresh sample soon.
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        // The guard protects the TRIP ANCHOR from that cached
        // first callback. A liveness check-in is deliberately re-sending a known-old
        // fix, and its own age check in maybeSendLivenessCheckIn is the relevant one.
        if (!isLivenessCheckIn && fixAgeMs > STALE_FIX_MAX_AGE_MS) {
            Log.d(TAG, "Skipping stale fix (age=${fixAgeMs}ms > ${STALE_FIX_MAX_AGE_MS}ms)")
            return
        }

        // Not news: older than a fix already taken in, as one of this service's own can be once a
        // newer one taken outside it was handed over (takeOneShotFix). Checked against the places,
        // it would carry the person back out and in again: two false crossings.
        if (!isLivenessCheckIn && !freshFixIsNews(lastKnownFix, location)) {
            Log.d(TAG, "Skipping a fix older than the last one taken in")
            return
        }

        if (!isLivenessCheckIn) {
            // Seed STILL when activity recognition cannot. Fresh fixes only — a
            // re-sent cached fix is not a new observation and must not anchor it.
            maybeInferStill(location)
            // And the reverse: hold the STILL cadence when the motion detector is
            // stuck in MOVING on a phone that is not going anywhere.
            maybeHoldStillCadenceWhileMoving(location)
            lastKnownFix = location
            newestOwnFix.offer(location)
            // The keep-alive geofence: around this start's first fix, and again once the phone
            // is out of it. Never per fix.
            maybeArmGeofence(location)
        }

        // Stamp the health clock. Deliberately AFTER the accuracy and staleness
        // gates and only for a genuinely new fix: a re-sent cached fix is not
        // evidence that location is still arriving, and counting it would let a
        // blind service look healthy indefinitely. Also clears any "tracking
        // stopped" notification — a fix is proof it is working again.
        if (!isLivenessCheckIn) {
            stampFixHealth()
        }

        // PLACE BOUNDARIES ARE EVALUATED BEFORE THE SEND FILTER, NOT AFTER IT.
        //
        // Past the filter's `return`s, a fix the filter declined to SEND would never
        // even be CHECKED for a crossing: with distance-based tracking the crossing
        // would go unnoticed until the user had moved another whole threshold, and
        // with both toggles off ("Time OFF + Distance OFF → don't send")
        // own-crossings would never be detected at all. Whether a place alert fires
        // is not a question the location-upload preference should be answering.
        //
        // Checking is local: boundaryRepository.checkLocation is an in-memory native
        // call against a boundary set this device already holds, no network and no
        // decryption, so running it on every usable fix costs approximately nothing.
        //
        // Still AFTER the accuracy and stale-fix gates above, deliberately — those
        // are about whether the fix is worth believing at all, and a crossing
        // decided from a rough or cached fix is a false alert.
        serviceScope.launch {
            try {
                // Kept adjacent to the check: a place that gained an alert after this
                // service started has to enter the monitored set before the very cycle
                // that would evaluate it.
                if (SystemClock.elapsedRealtime() - lastBoundaryReloadElapsedMs > boundaryReloadIntervalMs) {
                    refreshBoundaries()
                }
                checkOwnLocationAgainstBoundaries(
                    location.latitude,
                    location.longitude,
                    location.time,
                    if (location.hasAccuracy()) location.accuracy else null,
                )

                // Destination auto-arrival is checked here too, for the same
                // reason, and the failure would be worse: a fix the send
                // filter declined would never be TESTED for arrival, so the
                // arrival itself would go unreported until some later fix
                // happened to pass the filter. Not merely a vaguer
                // notification — no notification, and a destination left
                // showing "active" while the user stood in it.
                //
                // Its own accuracy rule stays attached to it. Arrival is a
                // 100 m test while battery-saver accepts fixes up to 500 m,
                // and a fix rougher than the ring says nothing about it —
                // announcing an arrival the user has not made, to their whole
                // circle, is not recoverable, because reportArrival flips the
                // destination's status.
                if (canResolveArrivalRadius(if (location.hasAccuracy()) location.accuracy else null)) {
                    checkOwnLocationAgainstDestinations(
                        location.latitude,
                        location.longitude,
                        location.time,
                    )
                } else {
                    Log.d(TAG, "Skipping destination arrival check: fix accuracy ${location.accuracy}m " +
                        "cannot resolve a ${DESTINATION_ARRIVAL_RADIUS_METERS.toInt()}m arrival radius")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Boundary/destination pre-check failed", e)
            }
        }

        // Taken and sent outside this service: checked above like any fix, and NOT sent again.
        // Nor does it use up a pending trip start, which is for the next fix this service sends.
        if (alreadySent) return

        // Trip-start override: consume forceSendNextFix if set and bypass the send filter
        // once. Set by the motion observer on (any → MOVING) transitions, and by
        // checkOwnLocationAgainstBoundaries when it finds a place crossing.
        //
        // It forces a HISTORY send specifically. Anchoring a trip, or putting a
        // position behind a place-crossing alert, are both cases where the row has to
        // survive — a check-in would leave the trip with no start and the alert unable
        // to say where.
        val tripStartSend = forceSendNextFix
        if (tripStartSend) {
            forceSendNextFix = false
            Log.i(TAG, "Trip start — bypassing distance/time filter to anchor trip")
        }
        val decision = decideSendForFix(
            forceSend = tripStartSend,
            isLivenessCheckIn = isLivenessCheckIn,
            // Distance from the last position committed to HISTORY, not from the last
            // thing sent. A check-in must NOT advance this baseline: on a device
            // drifting slowly, re-anchoring on every check-in would let it travel an
            // unbounded distance without ever crossing the threshold, and its history
            // would be a single point.
            movedMeters = lastSentLocation?.distanceTo(location),
            distanceThresholdMeters = distanceThresholdMeters,
            msSinceLastSend = System.currentTimeMillis() - lastSendTime,
            timeEnabled = sessionManager.isTimeBasedTrackingEnabled(),
            distanceEnabled = isDistanceTrackingEnabled,
            livenessIntervalMs = livenessIntervalMs(),
            pollIntervalMs = currentPollIntervalMs,
        )
        if (decision == LocationSendDecision.NONE) return

        // Update lastSentLocation SYNCHRONOUSLY before launching coroutine
        // so the next GPS callback (which arrives on the same handler thread)
        // sees the correct reference point for distance calculations.
        //
        // HISTORY only, for the reason given above. lastSendTime advances for BOTH:
        // it is the check-in clock, and a history send refreshes what peers see just
        // as a check-in does, so it postpones the next one.
        if (decision == LocationSendDecision.HISTORY) {
            lastSentLocation = location
            rememberHistorySentPosition(location)
        }
        lastSendTime = System.currentTimeMillis()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sending $decision: ${location.latitude}, ${location.longitude} (accuracy: ${location.accuracy}m, fixAge=${fixAgeMs}ms, tripStart=$tripStartSend, motion=$motionState)")
        }
        serviceScope.launch {
            try {
                val batteryLevel = getBatteryLevel()
                val isCharging = isDeviceCharging()

                // Update battery monitor for E2EE-compliant low battery alerts
                if (batteryLevel != null) {
                    monitoringManager.updateBatteryLevel(batteryLevel, isCharging)
                }

                // The boundary reload and the place-alert check run BEFORE
                // the send filter — see the pre-check in
                // handleLocationUpdate. Repeating them here would re-enter
                // the native checker for the same fix.

                // Destination auto-arrival runs in the pre-check with the
                // boundary check, above the send filter (see
                // handleLocationUpdate). Repeating it here would re-run the
                // arrival test for the same fix.

                refreshCachedData()

                val circles = cachedCircles

                if (circles.isEmpty()) {
                    Log.d(TAG, "No circles found - sending for self-tracking only")
                    sharingNow = sharingNowFor(emptyList(), emptyMap(), System.currentTimeMillis())
                    val selfTargets = listOf(LocationRepository.SELF_CIRCLE_ID)
                    val result = if (decision == LocationSendDecision.LIVENESS) {
                        locationRepository.sendEnvelopeLivenessRefresh(selfTargets, location, batteryLevel)
                    } else {
                        locationRepository.sendEnvelopeLocation(selfTargets, location, batteryLevel)
                    }
                    if (result.isSuccess) {
                        Log.d(TAG, "Self-tracking $decision sent successfully via envelope")
                        if (decision == LocationSendDecision.HISTORY) {
                            lastSentLocation = location
                            rememberHistorySentPosition(location)
                        }
                        lastSendTime = System.currentTimeMillis()
                        lastSuccessfulSendAt = System.currentTimeMillis()
                        sessionManager.setLastSuccessfulSendAt(lastSuccessfulSendAt)
                        staleWarningEmitted = false
                        onAnySendLanded()
                    } else if (decision == LocationSendDecision.LIVENESS) {
                        // Retry NOW, once, through the check-in path — never through
                        // the history fallback below, which would write the row the
                        // filter just decided not to write. The retry is worth making:
                        // sendEnvelope re-attempts startStreaming on entry, so a
                        // check-in that failed because the stream had just dropped can
                        // land on the second call.
                        //
                        // What must NOT happen is the durable queue persisting it for
                        // a replay minutes or hours later — by then it would assert a
                        // stale position as current. See
                        // LocationRepository.sendEnvelopeLivenessRefresh, which drops
                        // rather than enqueues; that is why storeLocalLocation (which
                        // both persists AND queues) is absent from this branch.
                        val err = result.exceptionOrNull()
                        Log.w(TAG, "Self-tracking liveness check-in failed: ${err?.javaClass?.simpleName}: ${err?.message} — retrying once")
                        val retry = locationRepository.sendEnvelopeLivenessRefresh(
                            selfTargets, location, batteryLevel
                        )
                        if (retry.isSuccess) {
                            lastSendTime = System.currentTimeMillis()
                            lastSuccessfulSendAt = System.currentTimeMillis()
                            sessionManager.setLastSuccessfulSendAt(lastSuccessfulSendAt)
                            staleWarningEmitted = false
                            onAnySendLanded()
                        } else {
                            val rErr = retry.exceptionOrNull()
                            Log.w(TAG, "Self-tracking liveness retry also failed: ${rErr?.javaClass?.simpleName}: ${rErr?.message} — dropping, next check-in is minutes away")
                            onAllSendsFailed()
                        }
                    } else {
                        val envErr = result.exceptionOrNull()
                        Log.w(TAG, "Self-tracking envelope failed: ${envErr?.javaClass?.simpleName}: ${envErr?.message}, falling back")
                        val fallbackResult = locationRepository.sendSelfTrackingLocation(location, batteryLevel)
                        if (fallbackResult.isSuccess) {
                            onAnySendLanded()
                        } else {
                            val fbErr = fallbackResult.exceptionOrNull()
                            Log.w(TAG, "Self-tracking fallback also failed: ${fbErr?.javaClass?.simpleName}: ${fbErr?.message}, storing locally")
                            locationRepository.storeLocalLocation(location)
                            onAllSendsFailed()
                        }
                    }
                    updateNotification(location)
                    return@launch
                }

                Log.d(TAG, "Found ${circles.size} circles to send location to")

                // Batch fetch all privacy settings in one query (avoids N+1 problem)
                val circleIds = circles.map { it.id }
                // Close the cold-start / headless FAIL-OPEN window: a circle we hold with
                // NO local privacy row passes every gate below (approximate -> exact,
                // pause ignored, realtime-off ignored). This send path runs headless
                // (BootReceiver / FCM / UnifiedPush wake) before the fire-and-forget
                // privacy sync may have landed, so prime any missing rows from the server
                // (GET + local mirror only, never an outbound PUT). A circle whose posture
                // still can't be resolved stays unprimed and is EXCLUDED below (fail closed).
                var allPrivacySettings = privacySettingDao.getAllForCircles(circleIds)
                val missingPrivacy = circleIds - allPrivacySettings.map { it.circleId }.toSet()
                if (missingPrivacy.isNotEmpty()) {
                    Log.i(TAG, "Priming ${missingPrivacy.size} circle(s) with no local privacy row before send")
                    missingPrivacy.forEach { circleRepository.ensurePrivacyPrimed(it) }
                    allPrivacySettings = privacySettingDao.getAllForCircles(circleIds)
                }
                val privacySettingsMap = allPrivacySettings.associateBy { it.circleId }
                val now = System.currentTimeMillis()
                sharingNow = sharingNowFor(circleIds, privacySettingsMap, now)

                val eligibleCircles = circles.filter { circle ->
                    val settings = privacySettingsMap[circle.id]

                    // Posture UNKNOWN (priming couldn't resolve it) -> fail closed: never
                    // send a realtime (possibly exact) fix to a circle whose privacy we
                    // can't read. Self-tracking is added to the target set separately
                    // below, so this holds back only the unresolved circle, not the
                    // user's own history.
                    if (settings == null) {
                        Log.w(TAG, "Skipping circle ${circle.id} - privacy posture unknown (unprimed), fail closed")
                        return@filter false
                    }

                    // Mode AND deadline — see [isSharingPausedAt]. Testing
                    // the deadline alone would read an indefinite pause
                    // (paused_until NULL, which is what the server stores for
                    // "until I resume") as un-paused, and keep uploading
                    // exact fixes to a circle the user had stopped sharing
                    // with.
                    if (settings.isSharingPausedAt(now)) {
                        Log.d(TAG, "Skipping circle ${circle.id} - sharing paused")
                        return@filter false
                    }

                    if (settings.shareRealtime == false) {
                        Log.d(TAG, "Skipping circle ${circle.id} - realtime sharing disabled")
                        return@filter false
                    }

                    true
                }

                Log.d(TAG, "${eligibleCircles.size} circles eligible for location sharing")

                // Envelope encryption: encrypt the location ONCE, then wrap that key per target.
                val sendTargetIds = mutableListOf<String>()
                sendTargetIds.addAll(eligibleCircles.map { it.id })
                sendTargetIds.add(LocationRepository.SELF_CIRCLE_ID)

                if (sendTargetIds.isNotEmpty()) {
                    val result = if (decision == LocationSendDecision.LIVENESS) {
                        locationRepository.sendEnvelopeLivenessRefresh(
                            sendTargetIds, location, batteryLevel
                        )
                    } else {
                        locationRepository.sendEnvelopeLocation(
                            sendTargetIds, location, batteryLevel
                        )
                    }
                    if (result.isSuccess) {
                        Log.d(TAG, "Envelope $decision sent to ${sendTargetIds.size} targets")
                        if (decision == LocationSendDecision.HISTORY) {
                            lastSentLocation = location
                            rememberHistorySentPosition(location)
                        }
                        lastSendTime = System.currentTimeMillis()
                        lastSuccessfulSendAt = System.currentTimeMillis()
                        sessionManager.setLastSuccessfulSendAt(lastSuccessfulSendAt)
                        staleWarningEmitted = false
                        onAnySendLanded()
                    } else if (decision == LocationSendDecision.LIVENESS) {
                        // Retry NOW, once, per target — and through the CHECK-IN path,
                        // never the history fallback below, which would write the row
                        // the filter just decided not to write.
                        //
                        // Per target, for the same reason the history fallback is:
                        // sendEnvelope refuses the WHOLE batch when any one target's
                        // circle key is missing (it returns before the send loop), so
                        // one bad key would otherwise silence every circle's check-in
                        // AND self-tracking at once. Retrying each target alone lets
                        // the healthy ones through.
                        //
                        // What must NOT happen is the durable queue persisting a
                        // check-in for replay minutes or hours later — by then it
                        // asserts a stale position as current. That is why
                        // storeLocalLocation, which both persists and queues, is
                        // absent from this branch and why
                        // LocationRepository.sendEnvelopeLivenessRefresh drops instead
                        // of enqueueing. A dropped check-in costs nothing: the next
                        // one is minutes away, and until then the device is honestly
                        // "not heard from".
                        val err = result.exceptionOrNull()
                        Log.w(TAG, "Liveness check-in batch failed for ${sendTargetIds.size} targets: ${err?.javaClass?.simpleName}: ${err?.message} — retrying per target")
                        var anyCheckedIn = false
                        val checkInErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
                        coroutineScope {
                            sendTargetIds.map { targetId ->
                                async {
                                    val r = locationRepository.sendEnvelopeLivenessRefresh(
                                        listOf(targetId), location, batteryLevel
                                    )
                                    if (r.isSuccess) anyCheckedIn = true
                                    else r.exceptionOrNull()?.let {
                                        checkInErrors.add("${targetId.take(8)}=${it.javaClass.simpleName}:${it.message}")
                                    }
                                }
                            }.awaitAll()
                        }
                        if (anyCheckedIn) {
                            lastSendTime = System.currentTimeMillis()
                            lastSuccessfulSendAt = System.currentTimeMillis()
                            sessionManager.setLastSuccessfulSendAt(lastSuccessfulSendAt)
                            staleWarningEmitted = false
                            onAnySendLanded()
                        } else {
                            Log.w(TAG, "Liveness check-in reached no target on retry either — dropping. per-target errors: ${checkInErrors.joinToString("; ")}")
                            onAllSendsFailed()
                        }
                    } else {
                        val envErr = result.exceptionOrNull()
                        Log.w(TAG, "Envelope send failed: ${envErr?.javaClass?.simpleName}: ${envErr?.message}")
                        // Fallback: per-target send. Self-tracking MUST be retried here
                        // alongside eligibleCircles. Retrying only the circles means any
                        // persistent envelope failure — a flaky self-key check, the SDK
                        // refusing one target, anything that fails the whole batch — silently
                        // drops every self-tracking send while circle sends keep landing.
                        // That shows up as circle members seeing the user fresh while "Your
                        // location" on web sits hours stale: the server only learns the
                        // user's own pseudonym from a successfully ingested self row.
                        var anySent = false
                        var selfSent = false
                        // Aggregate fallback errors so the log line on
                        // "everything failed" tells us what each target
                        // actually returned, not just that it failed.
                        val perTargetErrors = java.util.concurrent.ConcurrentLinkedQueue<String>()
                        coroutineScope {
                            val selfJob = async {
                                val r = locationRepository.sendSelfTrackingLocation(location, batteryLevel)
                                if (r.isSuccess) { anySent = true; selfSent = true }
                                else r.exceptionOrNull()?.let {
                                    perTargetErrors.add("self=${it.javaClass.simpleName}:${it.message}")
                                }
                            }
                            val circleJobs = eligibleCircles.map { circle ->
                                async {
                                    // Use sendEnvelopeLocation with a single target rather than
                                    // sendLocation: the envelope format carries the wrapped_msg_key
                                    // and a shippable key_id that a receiver needs to decrypt the
                                    // session-key-sealed location. A payload without them is routed
                                    // to the receiver's direct-circle-key branch and fails to
                                    // decrypt, so no receiver can recover it. A single-target
                                    // envelope keeps the wire format consistent with the batch send.
                                    val r = locationRepository.sendEnvelopeLocation(
                                        listOf(circle.id), location, batteryLevel
                                    )
                                    if (r.isSuccess) anySent = true
                                    else r.exceptionOrNull()?.let {
                                        perTargetErrors.add("${circle.id.take(8)}=${it.javaClass.simpleName}:${it.message}")
                                    }
                                }
                            }
                            (circleJobs + selfJob).awaitAll()
                        }
                        if (!selfSent) {
                            Log.w(TAG, "Envelope fallback: self-tracking send failed")
                        }
                        if (anySent) {
                            // This fallback is reached only on the HISTORY branch — a
                            // check-in returns above rather than falling through to
                            // per-target sends — so advancing the baseline is right here.
                            lastSentLocation = location; lastSendTime = System.currentTimeMillis()
                            rememberHistorySentPosition(location)
                            lastSuccessfulSendAt = System.currentTimeMillis()
                            sessionManager.setLastSuccessfulSendAt(lastSuccessfulSendAt)
                            staleWarningEmitted = false
                            onAnySendLanded()
                        } else {
                            Log.w(TAG, "All send attempts failed, queuing location locally. per-target errors: ${perTargetErrors.joinToString("; ")}")
                            locationRepository.storeLocalLocation(location)
                            onAllSendsFailed()
                        }
                    }
                }

                // No second place-alert pass here. This device's own fixes
                // are evaluated by PlaceAlertEvaluator, reached from the
                // sends above via LocationRepository.persistAndEvaluate —
                // the documented choke point where "every location this
                // device stores" is judged exactly once. A second, cruder
                // check over the same fix (a bare `distance <= radius` with
                // no exit margin, in-memory state, and no ledger, schedule
                // or cooldown check) could raise a banner the deduped
                // engine had deliberately suppressed.

                updateNotification(location)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling location update", e)
            }
        }
    }

    /**
     * Publish the baseline the moved / not-moved decision is measured from.
     *
     * This service keeps its own in-memory [lastSentLocation] for the hot path; this
     * writes the same position where LocationHeartbeatWorker can read it. The worker
     * is constructed fresh on every tick and shares no memory with us, so without
     * this it has no baseline at all and every heartbeat writes history — which is
     * most of what a phone that has been left alone sends.
     */
    private fun rememberHistorySentPosition(location: android.location.Location) {
        try {
            sessionManager.setLastHistorySentPosition(
                location.latitude, location.longitude, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            // A baseline we failed to persist costs one extra history row on the
            // worker's next tick. It must never cost the send that is in flight.
            Log.w(TAG, "Failed to persist last history-sent position: ${e.message}")
        }
    }

    private fun getBatteryLevel(): Int? {
        return cachedBatteryLevel
    }

    private fun isDeviceCharging(): Boolean {
        return cachedIsCharging
    }

    private fun createNotification(): Notification {
        // The stored preference, not isBatterySaverMode: this runs from onStartCommand
        // before startTracking has set the mode for this start.
        val contentText = notSharingText() ?: if (sessionManager.isBatterySaverEnabled()) {
            "Battery saver • ${saverCadenceLabel()}"
        } else {
            "Location sharing is active"
        }
        return buildNotification(contentText)
    }

    /**
     * The ongoing notification, built ONE way for the first post and every update:
     * a separate copy built for updates could drop the content intent or the
     * action button, and within a minute of starting, tapping the notification
     * would do nothing.
     */
    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Manage sharing" opens the circles, where each one's pause is the stop that lasts (there
        // is no device-wide pause). A button that only stopped this service would set nothing: the
        // watchdog, the heartbeat or the next app open would start it again, and nobody in a
        // circle would be told anything.
        val sharingIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "circles")
        }
        val sharingPendingIntent = PendingIntent.getActivity(
            this, 1, sharingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TrackApplication.LOCATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Track")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_circles, getString(R.string.notification_manage_sharing), sharingPendingIntent)
            .build()
    }

    /** "every 15 min" — the batch interval, as the notification states it. */
    private fun saverCadenceLabel(): String =
        "updates every ${saverIntervalMsFor(sessionManager.getBatterySaverInterval()) / 60_000L} min"

    private fun updateNotification(location: android.location.Location?) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateTime < notificationUpdateIntervalMs) {
            return
        }
        lastNotificationUpdateTime = now

        val contentText = buildNotificationText(now)

        // Re-posted only when it would say something new AND someone can see it —
        // see shouldPostNotificationUpdate. The screen coming on forces a refresh
        // (screenOnReceiver), so the age is right whenever it is actually looked at.
        val interactive = powerManager?.isInteractive ?: true
        if (shouldPostNotificationUpdate(interactive, contentText, lastPostedNotificationText)) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
            lastPostedNotificationText = contentText
        }

        // If no successful send within the user-configured threshold,
        // surface a one-shot warning (latched via staleWarningEmitted,
        // reset by every successful send) so the user actually knows the
        // green dot is lying. Suppressed entirely in distance-only mode
        // because there's no expected cadence to be late against.
        val last = observedLastSuccessfulSendAt()
        val threshold = staleSendThresholdMs()
        if (threshold != null
            && last > 0
            && now - last >= threshold
            && !staleWarningEmitted
        ) {
            staleWarningEmitted = true
            uxEventBus.emit(com.pingolin.track.util.UxEvent.SendingStale(ageMillis = now - last))
        }
    }

    /**
     * Compute the staleness threshold from the user's tracking config.
     *
     * - Time-based ON: two full intervals, two full check-in periods, and a floor —
     *   whichever is largest. Two periods is the "missed one expected send plus its
     *   retry window" definition of actually stuck, applied to whichever clock is
     *   actually driving sends. The check-in term is the one that matters for a
     *   stationary device: its sends arrive on the check-in cadence, not on the
     *   user's interval, so a threshold derived from the interval alone would fire
     *   every time somebody sat down.
     * - Time-based OFF (distance-only): returns null so the warning is suppressed. A
     *   stationary distance-only user sends nothing — no history and no check-in,
     *   see [decideSend] — and complaining about that would be useless noise.
     *
     * When check-ins legitimately cannot run — GPS has stopped delivering and the
     * cached fix aged out — sends really do stop, and firing this warning is then
     * the correct behaviour.
     */
    private fun staleSendThresholdMs(): Long? = staleSendThresholdMsFor(
        timeBasedEnabled = sessionManager.isTimeBasedTrackingEnabled(),
        intervalMs = sessionManager.getTrackingIntervalMs(),
        livenessIntervalMs = livenessIntervalMs(),
    )

    /** [sharingNowFor] from the circles and privacy rows in Room, the ones the send gates read. */
    private suspend fun refreshSharingNow() {
        try {
            val circleIds = circleDao.getAll().map { it.id }
            val rows = privacySettingDao.getAllForCircles(circleIds).associateBy { it.circleId }
            sharingNow = sharingNowFor(circleIds, rows, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read what the notification may say about sharing: ${e.message}")
        }
    }

    /** The notification's words while no circle receives the location; null while one does. */
    private fun notSharingText(): String? = when (sharingNow) {
        SharingNow.SHARING -> null
        SharingNow.PAUSED -> getString(R.string.notification_sharing_paused)
        SharingNow.OFF -> getString(R.string.member_sharing_off)
    }

    private fun buildNotificationText(nowMs: Long): String {
        // Nobody receives the location: say so, and no "Last shared" for a send nobody got.
        notSharingText()?.let { return it }
        // Location is off on this phone: nothing can be shared, so the notification must not
        // say "Location sharing active" while the circle sees them freeze.
        if (!com.pingolin.track.util.LocationAccess.servicesOn(this)) {
            return getString(R.string.notification_location_services_off)
        }
        val prefix = if (isBatterySaverMode) "Battery saver, ${saverCadenceLabel()}" else "Location sharing active"
        val last = observedLastSuccessfulSendAt()
        val ageSuffix = when {
            last == 0L -> "waiting for first send"
            else -> {
                val ageMs = nowMs - last
                when {
                    ageMs < 60_000L -> "Last shared: just now"
                    ageMs < 3_600_000L -> "Last shared: ${ageMs / 60_000L}m ago"
                    else -> "Last shared: ${ageMs / 3_600_000L}h ago"
                }
            }
        }
        return "$prefix • $ageSuffix"
    }

    /**
     * Called at service start so session keys are ready before the first location
     * update needs one.
     */
    private suspend fun initializeSessionKeys() {
        try {
            val circles = circleDao.getAll()
            for (circle in circles) {
                try {
                    locationRepository.ensureSessionKey(circle.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize session key for circle ${circle.id}: ${e.message}")
                }
            }
            Log.d(TAG, "Session keys initialized for ${circles.size} circles")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize session keys", e)
        }
    }

    /**
     * Runs every 5h45m so each new session key overlaps the outgoing one's 6-hour
     * lifetime — without that overlap a rotation would strand in-flight payloads.
     */
    private suspend fun rotateSessionKeys() {
        try {
            val circles = circleDao.getAll()
            for (circle in circles) {
                try {
                    locationRepository.ensureSessionKey(circle.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to rotate session key for circle ${circle.id}: ${e.message}")
                }
            }
            Log.d(TAG, "Session key rotation completed for ${circles.size} circles")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate session keys", e)
        }
    }

    /**
     * Self-monitoring: detects when the current user crosses a place boundary.
     * All checking is client-side, so the server never learns the boundary or the crossing.
     */
    private suspend fun checkOwnLocationAgainstBoundaries(
        latitude: Double,
        longitude: Double,
        /** `Location.time` of the FIX this check is evaluating — see [crossingTimeFor]. */
        fixTimeMs: Long,
        /**
         * Reported accuracy of the fix, or null if it had none. Passed to the
         * native checker so it can gate each boundary on it — unlike the place
         * alerts and the destination arrival, whose rings this service knows,
         * the geofence radii live inside the checker, so only it can decide
         * which of them this fix is precise enough to judge.
         */
        accuracyMeters: Float?,
    ) {
        try {
            // Must go through the boundary CHECKER (populated by loadBoundariesForCircle),
            // not the BoundaryMonitor — the monitor is never loaded with places, so routing
            // this there means own-location crossings are silently never detected.
            val crossings = boundaryRepository.checkLocation(
                latitude, longitude, accuracyMeters?.toDouble(),
            )
            if (crossings.isEmpty()) return

            // A CROSSING FORCES THE NEXT FIX PAST THE SEND FILTER.
            //
            // The subscriber cannot render "Sam arrived at CAP" from the crossing
            // report — that report carries no location by design. It has to re-derive
            // the crossing from this device's POSITION, so the position has to be on
            // the server for the alert to say anything more than "a member arrived at
            // a place".
            //
            // Scoped exactly to places that matter: the native checker only holds
            // boundaries loaded with `.filter { it.alertsEnabled }`, so reaching here
            // already means somebody in the circle is subscribed to this crossing.
            // The arrival is therefore being announced to them regardless — the
            // position only decides whether the announcement can name where.
            //
            // Reuses the trip-start bypass rather than sending from here: the real
            // send path resolves circle targets and honours the privacy rules
            // (mayEmitPresenceTo, pauses), and a second copy of that would be a
            // privacy bug waiting to happen. While moving — which is what you are
            // doing when you cross — the next fix is POLL_MOVING_MS (20s) away.
            forceSendNextFix = true

            val userId = sessionManager.getUserId() ?: return
            val deviceId = sessionManager.getDeviceId()
            for (crossing in crossings) {
                val eventType = when (crossing.crossingType) {
                    BoundaryCrossingType.ENTER -> BoundaryRepository.EVENT_TYPE_ENTER
                    BoundaryCrossingType.EXIT -> BoundaryRepository.EVENT_TYPE_EXIT
                }
                Log.d(TAG, "Own boundary crossing: ${crossing.boundaryName} ($eventType) — reporting")
                // Report the crossing so the server can notify any circle
                // members who subscribed to this user's crossings. The
                // crossing user is us, so no local self-notification is
                // shown.
                // The Result must not be discarded: a failed crossing
                // report would be invisible — no log, no retry, and an
                // alert that never fires looks exactly like one nobody
                // subscribed to. The repository queues a total failure to
                // `pending_crossings`, so this only has to say so — but it
                // has to say so, because a queued crossing is a DELAYED
                // alert and a silent one is not.
                boundaryRepository.reportCrossingFromMonitor(
                    placeId = crossing.boundaryId,
                    userId = userId,
                    deviceId = deviceId,
                    crossingType = eventType,
                    // The FIX's time, not now() — see [crossingTimeFor].
                    occurredAtMs = crossingTimeFor(fixTimeMs, System.currentTimeMillis()),
                ).onFailure { e ->
                    Log.w(TAG, "Crossing report for place ${crossing.boundaryId} did not land; queued for retry", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking own location against boundaries", e)
        }
    }

    /** In-memory set of destination IDs we've already auto-fired arrival
     *  for this service session. Survives until the service process is
     *  restarted. Server-side status check on next run catches cases
     *  where the service did restart and we'd otherwise double-fire. */
    private val autoArrivedDestinations = mutableSetOf<String>()

    /**
     * When each pending auto-arrival was first DETECTED, keyed by destination
     * id.
     *
     * Exists because the report can fail and be retried: the failure branch
     * below un-flags the destination so a later tick tries again, and that later
     * tick would otherwise stamp the arrival with the moment the network came
     * back. That is exactly the case the sealed occurred-at is for — an arrival
     * detected offline announcing itself as if it had just happened.
     *
     * Written only AFTER the privacy gate passes, so a detection suppressed by a
     * paused circle is not remembered: reporting it later with the original time
     * would disclose presence during the window the pause existed to hide.
     * Cleared on success, and cleared when the user is no longer inside the
     * radius — coming back is a new arrival, and stamping it with a days-old
     * detection would be worse than using the reconnect time.
     */
    private val autoArrivalDetectedAtMs = mutableMapOf<String, Long>()

    /**
     * Auto-arrival: fire [DestinationRepository.reportArrival] for any of
     * the current user's own active destinations that are now within
     * [DESTINATION_ARRIVAL_RADIUS_METERS] of the live location, so the
     * user need not report that they arrived — the server-side push
     * pipeline handles notifying other circle members.
     */
    private suspend fun checkOwnLocationAgainstDestinations(
        latitude: Double,
        longitude: Double,
        /** `Location.time` of the FIX this check is evaluating — see [arrivalTimeFor]. */
        fixTimeMs: Long,
    ) {
        try {
            val userId = sessionManager.getUserId() ?: return
            // This account's own active destinations: the sealed id AND
            // the server's creator both name it. Filtered in memory
            // (typical active-destination count is ≤ handful).
            val mine = com.pingolin.track.domain.repository.DestinationRepository.ownActive(destinationDao.getByStatus("active"), userId)
            if (mine.isEmpty()) return

            val here = android.location.Location("").apply {
                this.latitude = latitude
                this.longitude = longitude
            }
            for (dest in mine) {
                if (dest.id in autoArrivedDestinations) continue
                val there = android.location.Location("").apply {
                    this.latitude = dest.destLatitude
                    this.longitude = dest.destLongitude
                }
                val meters = here.distanceTo(there)
                if (meters <= DESTINATION_ARRIVAL_RADIUS_METERS) {
                    // Privacy posture gate: the arrival EVENT itself is an ambient
                    // presence + timing signal (the server notifies the circle), so an
                    // auto-arrival must honor the same per-circle posture as a realtime
                    // fix — the coordinate omission at the reportArrival choke point does
                    // NOT hide "the user is right now at this destination". Route through
                    // the shared presence gate, matching boundary crossings and the iOS
                    // client. Skip WITHOUT marking auto-arrived so it re-fires once the
                    // circle un-pauses while the user is still at the destination. The
                    // manual "Arrived" button stays ungated as explicit user intent.
                    if (!boundaryRepository.mayEmitPresenceTo(dest.circleId)) {
                        Log.d(TAG, "Auto-arrival: circle ${dest.circleId} paused/realtime-off/unknown — suppressing arrival event for ${dest.id}")
                        continue
                    }
                    Log.i(TAG, "Auto-arrival: within ${meters.toInt()}m of destination ${dest.id}; reporting")
                    // Force the next fix past the send filter — but NOT for the reason
                    // the place-crossing one does, and the difference is worth being
                    // precise about.
                    //
                    // A destination arrival does NOT need this device's position to be
                    // named. NotificationTickleFetcher calls
                    // destinationRepository.resolveNotificationCopy and decrypts the
                    // actor and destination names out of the notification row's own
                    // metadata ciphertexts, so "Alice arrived at Home" renders from the
                    // push alone. Place alerts have no such payload — that is why they
                    // must re-derive the crossing from a position, and destinations
                    // need not.
                    //
                    // What this buys is the MAP: the circle has just been told someone
                    // arrived, and without a send their marker sits wherever the filter
                    // last allowed — possibly streets away from the destination they
                    // were just told about. The arrival is already being announced, so
                    // the position discloses nothing further.
                    //
                    // Set AFTER the presence gate above, never before. A paused or
                    // realtime-off circle suppresses the arrival event, and forcing an
                    // upload for an event we are deliberately not sending would leak
                    // exactly the presence that gate exists to withhold.
                    forceSendNextFix = true
                    // First post-gate detection wins, and survives a failed report so
                    // the retry carries the time of the arrival rather than the time
                    // of the retry.
                    val detectedAtMs = arrivalTimeFor(
                        remembered = autoArrivalDetectedAtMs[dest.id],
                        fixTimeMs = fixTimeMs,
                        now = System.currentTimeMillis(),
                    )
                    autoArrivalDetectedAtMs[dest.id] = detectedAtMs
                    autoArrivedDestinations.add(dest.id)
                    // Resolve the label BEFORE reporting: a successful
                    // report deletes the local row (arrived destinations
                    // leave the list immediately), after which the
                    // saved-place / name lookup has nothing to read.
                    val destLabel = try {
                        destinationRepository.labelForDestination(dest)
                    } catch (e: Exception) {
                        dest.destName
                    }
                    val result = destinationRepository.reportArrival(
                        destinationId = dest.id,
                        circleId = dest.circleId,
                        latitude = latitude,
                        longitude = longitude,
                        occurredAtMs = detectedAtMs,
                    )
                    if (result.isFailure) {
                        Log.w(TAG, "Auto-arrival report failed for ${dest.id}: ${result.exceptionOrNull()?.message}")
                        // Un-flag so a later tick can retry. The detection time stays
                        // in the map on purpose — it is what the retry has to send.
                        autoArrivedDestinations.remove(dest.id)
                    } else {
                        autoArrivalDetectedAtMs.remove(dest.id)
                        // Immediate local "You've arrived!" — the server
                        // deliberately sends the creator no arrival push
                        // (their in-app record is inserted server-side),
                        // so the device that detected the arrival is the
                        // one that must say so.
                        alertHandler.showSelfArrival(dest.id, destLabel)
                    }
                } else {
                    // Outside the radius. Drop any detection time being held for a
                    // retry: the user has left, so the next time they are inside it
                    // is a NEW arrival and must carry its own stamp. Without this a
                    // report that kept failing would eventually announce a fresh
                    // arrival with a days-old time — worse than the reconnect time
                    // the sealed field exists to replace.
                    autoArrivalDetectedAtMs.remove(dest.id)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking destinations for auto-arrival", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The service can be destroyed without any of the deliberate stop
        // branches running — a system reclaim, an app update, a kill. Recorded
        // separately so a disappearance is distinguishable from a decision.
        if (isTracking) journalStop(TrackingStopReason.SERVICE_DESTROYED)
        // Clear the boundary monitor callback OFF the main thread. onDestroy runs on Main,
        // and clearMonitorCallback() is a suspend fun that reaches into the native
        // BoundaryMonitor (JNI), so a runBlocking(Dispatchers.IO) here would stall Main
        // behind a native mutex and ANR. boundaryRepository is a process-lifetime
        // @Singleton that outlives this service and the callback only nulls a reference, so
        // fire it on a DETACHED scope (NOT serviceScope, which is cancelled just below): it
        // runs to completion on its own without blocking teardown.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                boundaryRepository.clearMonitorCallback()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear boundary monitor callback", e)
            }
        }
        try {
            motionDetectionManager.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Motion detection stop failed in onDestroy: ${e.message}")
        }
        serviceScope.cancel()
        locationUpdates?.cancel()
        locationUpdates = null
        locationHandlerThread?.quitSafely()
        locationHandlerThread = null
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister battery receiver", e)
        }
        try {
            unregisterReceiver(powerSaveReceiver)
            unregisterReceiver(screenOnReceiver)
            unregisterReceiver(locationModeReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister power-save/screen receivers", e)
        }
        publishedPollIntervalMs = 0L
        // A destroy that did not come through stopTracking (a system reclaim) must not
        // leave the watchdog and the heartbeat worker believing the service is alive.
        isRunning = false
        locationRepository.cleanup()
    }
}
