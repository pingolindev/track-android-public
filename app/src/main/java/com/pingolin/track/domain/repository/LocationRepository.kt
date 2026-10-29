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

package com.pingolin.track.domain.repository

import android.os.Looper
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PendingLocationEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.local.entity.sendsBubble
import com.pingolin.track.data.local.entity.sendsBatteryLevel
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.location.LocationAccuracy
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.LocationUpdateListener
import com.pingolin.track.location.LocationUpdateRequest
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.hasKeyInUse
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedLocation
import uniffi.pingolin_mobile.Location
import uniffi.pingolin_mobile.LocationManager
import uniffi.pingolin_mobile.PrivacyConfig
import uniffi.pingolin_mobile.SdkConfig
import uniffi.pingolin_mobile.StreamErrorCallback
import uniffi.pingolin_mobile.Environment as SdkEnvironment
import android.os.Build
import android.util.Log
import com.pingolin.track.BuildConfig
import com.pingolin.track.data.remote.dto.CreateDeviceRequest
import com.pingolin.track.data.remote.dto.EncryptedBlobRequest
import com.pingolin.track.data.remote.dto.PublishSessionKeyRequest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Motion and precision fields for one outgoing position. Grouped so the
 * coarsened and exact branches cannot drift apart at the two call sites that
 * build a [Location].
 */
private data class MotionFields(
    val altitude: Double?,
    val speed: Double?,
    val heading: Double?,
    val accuracy: Double?,
)

/** See [LocationRepository.streamTokenAction]. */
internal enum class StreamTokenAction { NONE, PUSH_STORED, REFRESH }

/**
 * Result of decrypting a single location.
 */
sealed class DecryptionResult {
    data class Success(val location: LocationEntity) : DecryptionResult()
    data class KeyMissing(val circleId: String) : DecryptionResult()
    data class DecryptionFailed(val circleId: String, val reason: String) : DecryptionResult()

    /**
     * A circle's row naming THIS account: never this device's own fix, marker or alert input,
     * whoever sealed it. Dropped before its payload opens, and no failure: not counted, and not
     * received.
     */
    object NamedMe : DecryptionResult()
}

/**
 * Summary of location fetch operation with partial success support.
 * Allows UI to show how many locations were successfully decrypted.
 */
data class LocationFetchResult(
    val locations: List<LocationEntity>,
    val totalReceived: Int,
    val decryptionFailures: Int,
    val keyMissingCount: Int
) {
    val successCount: Int get() = locations.size
    val hasPartialSuccess: Boolean get() = decryptionFailures > 0 && locations.isNotEmpty()
    val allFailed: Boolean get() = locations.isEmpty() && totalReceived > 0

    /**
     * Members THIS refresh produced a location for.
     *
     * The alert evaluators need it because the local `locations` table is a
     * CACHE of last-known positions, not a snapshot of this refresh:
     * `persistAndEvaluate` only inserts, and the sole deletes are keyed on circle
     * MEMBERSHIP (`deleteForMissingMembers`) and a 90-day sweep — while an
     * inactivity timeout is measured in hours. So a member absent from this
     * response keeps a row whose timestamp goes on ageing, and reading that row
     * alone cannot tell "went quiet" from "was not in this response".
     *
     * The most reachable cause of absence is not a fault. A member who has PAUSED
     * sharing is filtered out server-side: a paused member's ciphertext is never
     * sent at all. Without this set, pausing — the control a person reaches for
     * when they do not want their whereabouts known — would cause their circle to
     * be told they might need attention.
     *
     * DERIVED FROM THE DECODE RESULT, NEVER FROM THE PRIVACY TABLE. Absence is
     * the only signal the server gives and it is sufficient; looking up who is
     * paused would re-introduce exactly the disclosure that server-side filter
     * exists to prevent, while looking like a tidier implementation.
     *
     * Built from `locations`, which is the full decrypted set BEFORE
     * `filterReplayedMemberLocations` — a replay-drop means the entry already
     * held is at least as new as what arrived, not that the member went quiet.
     * That is the same distinction the field's own construction already relies
     * on, and the iOS app populates its `refreshedUserIds` from the same side of
     * the guard.
     */
    val refreshedUserIds: Set<String> get() = locations.mapTo(mutableSetOf()) { it.userId }

    /**
     * Whether this refresh is a sound basis for judging the circle at all.
     *
     * A row that could not be opened is indistinguishable from a member who went
     * quiet, so one unopened row disqualifies the whole refresh — including for
     * members it did decrypt.
     */
    val isComplete: Boolean get() = decryptionFailures == 0 && keyMissingCount == 0
}

/**
 * Result of a location-history fetch.
 *
 * NOT a page. The server answers the whole requested day-bucket range in one
 * response (see [com.pingolin.track.data.remote.dto.LocationHistoryResponse]),
 * so there is nothing to follow and no cursor to carry.
 */
data class LocationHistoryResult(
    val locations: List<LocationEntity>,
    /** The server dropped the OLDEST groups to stay under its row cap, so the
     *  far end of the window is missing from [locations]. */
    val truncated: Boolean = false
)

@Singleton
class LocationRepository @Inject constructor(
    // Fused on gms, android.location.LocationManager on libre — see LocationProvider.
    private val locationProvider: LocationProvider,
    private val locationDao: LocationDao,
    private val pendingLocationDao: PendingLocationDao,
    private val circleDao: CircleDao,
    private val privacySettingDao: PrivacySettingDao,
    private val tracksApi: TrackApi,
    private val authApi: com.pingolin.track.data.remote.api.AuthApi,
    private val sessionManager: SessionManager,
    // The ONE refresh path, shared with TokenAuthenticator. Not a second
    // implementation — see refreshTokenForStream.
    private val tokenRefresher: com.pingolin.track.util.TokenRefresher,
    private val cryptoManager: CryptoManager,
    private val uxEventBus: com.pingolin.track.util.UxEventBus,
    private val queueMonitor: QueueMonitor,
    // Lazy to break the CircleRepository <-> LocationRepository DI cycle (CircleRepository
    // already holds Lazy<LocationRepository>). Used only to prime a missing per-circle
    // privacy row before a realtime send so an unknown posture fails CLOSED, never exact.
    private val circleRepository: dagger.Lazy<CircleRepository>,
    // Lazy to break the LocationRepository -> BoundaryRepository -> PlaceRepository ->
    // LocationRepository DI cycle (PlaceRepository injects LocationRepository directly, so a
    // direct BoundaryRepository edge here would be an unbroken cycle). Used only for the
    // drain-time presence gate (mayEmitPresenceTo) in syncPendingLocations, so a circle
    // PAUSED / realtime-off AFTER a fix was queued doesn't receive the pre-pause backlog.
    private val boundaryRepository: dagger.Lazy<BoundaryRepository>,
    // Subscriber-side place-alert evaluation. Every decrypted location this device
    // persists is fed to it from persistAndEvaluate — see that function.
    private val placeAlertEvaluator: com.pingolin.track.domain.placealerts.PlaceAlertEvaluator,
    // Nothing is sealed for a circle whose key moved on without this device.
    private val currentKeyGate: CurrentKeyGate,
    // Locations kept for such a circle until it can be shared with again.
    private val queuedCircleLocationDao: com.pingolin.track.data.local.dao.QueuedCircleLocationDao,
    // Which members share only an approximate location, from the mode the server reports beside
    // their fixes: the map draws them as an area.
    private val approximateMembers: com.pingolin.track.util.ApproximateMembersStore,
    // The newest fix of this account's own position any path took to send (the stale-liveness
    // re-send included): every live send here records the one it carries, and so does a fix
    // taken here to send.
    private val newestOwnFix: com.pingolin.track.location.NewestOwnFix,
    // When this account paused each circle: a point captured inside one of a circle's pauses never goes
    // there, however late. Fed by every privacy row CircleRepository stores.
    private val pauseIntervals: com.pingolin.track.util.PauseIntervals,
    // How wide a bubble each circle got when: a point goes no finer than the one in force when it was
    // taken. Fed by every privacy row CircleRepository stores, as the pauses are.
    private val approximateIntervals: com.pingolin.track.util.ApproximateIntervals,
) {
    /**
     * The (circle, member, fix time) of each of [rows] whose fix is already held with alerts on its
     * member refused. Only rows that would say otherwise are asked about; a store that can't be read
     * leaves the write as it was.
     */
    private suspend fun heldRefusals(rows: List<LocationEntity>): Set<Triple<String, String, Long>> {
        val asked = rows.filter { it.allowAlertsOnMe != false }
        if (asked.isEmpty()) return emptySet()
        val refused = HashSet<Triple<String, String, Long>>()
        try {
            for ((circleId, inCircle) in asked.groupBy { it.circleId }) {
                // Within SQLite's bound on a query's variables.
                for (times in inCircle.map { it.timestamp }.distinct().chunked(500)) {
                    locationDao.refusedAt(circleId, times).mapTo(refused) { Triple(circleId, it.userId, it.timestamp) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Refusals held not read: ${e.javaClass.simpleName}")
        }
        return refused
    }

    /**
     * THE choke point for persisting decrypted locations.
     *
     * Every location this device stores — its own fixes, the live per-circle poll,
     * and history pages — lands here, and here alone, so subscriber-side place
     * alerts are evaluated exactly once per stored location. Do NOT call
     * locationDao.insert / insertAll directly: a location that bypasses this
     * function is a location no alert is evaluated against, which is precisely the
     * silent failure this design exists to prevent.
     *
     * insertAll carries the same OnConflictStrategy.REPLACE as the single-row insert
     * (LocationDao), so both shapes have the same write semantics — the unique
     * (circleId, userId, timestamp) index still collapses a re-inserted fix.
     *
     * Evaluation is best-effort: it is a local convenience computed on data already
     * in hand, so a failure there must never fail (or roll back) the location write.
     *
     * [forAlerts] is all of [locations] except where a caller holds rows that are
     * not news: a history page's late points (`history_only`) are stored and shown,
     * and judged by nobody.
     */
    private suspend fun persistAndEvaluate(
        locations: List<LocationEntity>,
        forAlerts: List<LocationEntity> = locations,
        /** The server's rows (a fetch), not this phone's own fix: the evaluator takes none naming this
         * account.
         */
        fromServer: Boolean,
    ) {
        if (locations.isEmpty()) return
        // A "no" held stays: REPLACE would write the served word in both directions, so the same fix
        // served again with a "yes", or no word, would raise a refusal this phone holds. After a
        // restart the replay guard is empty, and the server alone could do that by serving a quiet
        // member's last fix again. A new fix carries its own word, as served.
        val refused = heldRefusals(locations)
        fun kept(row: LocationEntity) =
            if (row.allowAlertsOnMe != false && Triple(row.circleId, row.userId, row.timestamp) in refused) {
                row.copy(allowAlertsOnMe = false)
            } else row
        locationDao.insertAll(if (refused.isEmpty()) locations else locations.map(::kept))
        if (forAlerts.isEmpty()) return
        try {
            placeAlertEvaluator.onLocationsPersisted(if (refused.isEmpty()) forAlerts else forAlerts.map(::kept), fromServer)
        } catch (e: Exception) {
            Log.w(TAG, "Local place-alert evaluation failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Lock object for synchronizing access to locationManager and isStreaming.
     * These fields are accessed from multiple threads (location callback, UI, workers)
     * and must be modified atomically to prevent race conditions.
     */
    private val streamingLock = Any()

    // Protected by streamingLock - use synchronized(streamingLock) when accessing
    private var locationManager: LocationManager? = null
    private var isStreaming = false

    /**
     * The JWT the live SDK stream presents on its RPCs: the one it was built with,
     * then whatever [pushTokenToStream] handed it. Protected by streamingLock.
     */
    private var streamToken: String? = null

    /**
     * When the current stream was started (elapsedRealtime), or 0 when none is up.
     * A stream earns a fresh reconnect backoff only by lasting — see
     * [reconnectAttemptsAfterFailure].
     */
    @Volatile private var streamStartedAtMs = 0L

    init {
        // Every refresh — the REST authenticator's or the stream's own — hands the
        // new token to the running stream, which reads it per RPC.
        tokenRefresher.addTokenListener { token -> pushTokenToStream(token) }
    }

    /** Emits the timestamp (epoch ms) each time a location is successfully sent to the server. */
    private val _locationSentEvent = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val locationSentEvent: kotlinx.coroutines.flow.SharedFlow<Long> = _locationSentEvent

    // Reconnection state for exponential backoff
    @Volatile private var reconnectAttempts = 0
    @Volatile private var lastReconnectTime = 0L
    @Volatile private var pendingReconnect = false
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val TAG = "LocationRepository"
        private const val MS_PER_DAY = 86400000L

        /**
         * A live fix older than this is placed against the record of pauses; a newer one goes by the
         * posture now. A replayed crossing is judged by the same line
         * ([BoundaryRepository.replayGate]).
         */
        internal const val LATE_AFTER_MS = 2 * 60_000L

        /** At most one privacy re-read a minute before what waited goes ([rereadPrivacyBefore]). */
        private const val PRIVACY_REREAD_EVERY_MS = 60_000L

        /** Kept locations sent per database read while a queue drains. */
        private const val QUEUE_FLUSH_BATCH = 50

        /**
         * Kept points sent in any one minute, at most. The server's ingest allows each user
         * 120 messages a minute over all of their devices, and late points count against the
         * same budget as live ones, so a backlog takes a third of it and the live points
         * keep the rest.
         */
        internal const val KEPT_POINTS_PER_MINUTE = 40

        /**
         * The timestamp a send carries: the fix's own time for a history row, the
         * current moment for a liveness check-in.
         *
         * The distinction is the whole point of a check-in and is easy to lose. A
         * check-in is usually built from a CACHED fix — the device has not moved, so
         * there may be no newer one — and it asserts "I am here now". This timestamp
         * is the one that gets encrypted, so it is what every reader sees: the map's
         * "last updated", and the inactivity alerts that decide whether a member has
         * gone quiet. Ship the cached fix's original time and a check-in tells the
         * watcher nothing they did not already know, which defeats the purpose of a
         * check-in.
         *
         * A history row is the opposite case and keeps the fix's own time: the send
         * cycle can process a Location minutes after it was taken (555 s has been
         * measured), and a row is a claim about where the device WAS.
         */
        internal fun sendTimestampFor(livenessOnly: Boolean, fixTimeMs: Long, nowMs: Long): Long =
            if (livenessOnly) nowMs else fixTimeMs

        /**
         * A "Device not found" refusal re-registers at most this often, and a registration the
         * server took without a push route is tried again no sooner (unless a new route arrives).
         */
        internal const val REJECTION_REREGISTRATION_COOLDOWN_MS = 5L * 60_000L

        /** Whether a refusal at [nowElapsedMs] may re-register, the last one at [lastElapsedMs] (null: never). */
        internal fun rejectionReregistrationDue(nowElapsedMs: Long, lastElapsedMs: Long?): Boolean =
            lastElapsedMs == null || nowElapsedMs - lastElapsedMs >= REJECTION_REREGISTRATION_COOLDOWN_MS

        const val SELF_CIRCLE_ID = "self"
        // Nil UUID used by server for personal tracking (maps to "self" locally)
        const val SELF_CIRCLE_UUID = "00000000-0000-0000-0000-000000000000"

        /** Rate limit for the automatic self-key heal. See republishSelfKeyIfHeld. */
        private const val SELF_KEY_REPUBLISH_COOLDOWN_MS = 60L * 60L * 1000L

        /** How often verifySelfKeySlots may hit the network. See its docs. */
        private const val SELF_KEY_SLOT_CHECK_INTERVAL_MS = 60L * 60L * 1000L

        // Reconnection constants
        private const val RECONNECT_BASE_DELAY_MS = 1000L   // 1 second initial delay
        private const val RECONNECT_MAX_DELAY_MS = 60000L  // 60 seconds max delay
        private const val RECONNECT_MAX_ATTEMPTS = 10       // Give up after 10 attempts

        /** How long a stream must stay up before its failure starts a fresh backoff. */
        internal const val STREAM_STABLE_AFTER_MS = 2 * 60_000L

        /**
         * The reconnect attempt count after a stream failure. A stream that lasted
         * [STREAM_STABLE_AFTER_MS] was healthy, so its failure is a fresh one and
         * backs off from the start; one that failed sooner (or never came up) keeps
         * climbing toward the cap.
         */
        internal fun reconnectAttemptsAfterFailure(attempts: Int, startedAtMs: Long, nowMs: Long): Int =
            if (startedAtMs > 0L && nowMs - startedAtMs >= STREAM_STABLE_AFTER_MS) 0 else attempts

        /** Life a token must have left for the stream's next RPC. */
        internal const val STREAM_TOKEN_MIN_REMAINING_S = 60L

        /**
         * What the send path must do so the stream's next RPC carries a live token.
         * [presented] is the running stream's token (null when none is up — the next
         * one is built from [stored]). An unreadable token is left alone: the
         * server's answer is the only authority on it.
         */
        internal fun streamTokenAction(presented: String?, stored: String?, nowMs: Long): StreamTokenAction {
            val next = presented ?: stored
            if (com.pingolin.track.util.JwtExpiry.expiryEpochSeconds(next) == null) return StreamTokenAction.NONE
            if (com.pingolin.track.util.JwtExpiry.isValidForAtLeast(next, STREAM_TOKEN_MIN_REMAINING_S, nowMs)) {
                return StreamTokenAction.NONE
            }
            if (presented != null && stored != null && stored != presented &&
                com.pingolin.track.util.JwtExpiry.isValidForAtLeast(stored, STREAM_TOKEN_MIN_REMAINING_S, nowMs)
            ) return StreamTokenAction.PUSH_STORED
            return StreamTokenAction.REFRESH
        }

        // Reusable date formatter (SimpleDateFormat is not thread-safe, so use ThreadLocal)
        private val dateFormat = ThreadLocal.withInitial {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
        }

        /** Bubble radius applied when a circle shares "approximate" but stores no explicit radius. */
        const val DEFAULT_BUBBLE_RADIUS_MILES = 1

        /** The widest bubble: what a radius that can't be read is taken as. */
        const val MAX_BUBBLE_RADIUS_MILES = 25
        // Non-private so CheckInRepository can compute the same bubble radius when it
        // coarsens a check-in fix for an "approximate" circle (see coarsenRadiusMetersFor).
        internal const val METERS_PER_MILE = 1609.344

        /**
         * Deterministic snap-to-grid coarsening for "approximate" (bubble) sharing.
         *
         * Snaps (lat,lng) to the NEAREST point of a grid whose cell size equals
         * [radiusMeters]. Because the grid is FIXED (not random per-send jitter), a
         * stationary sender always reports the SAME coarsened point, so a malicious
         * viewer/server cannot average many updates back toward the true location. The
         * reported point is within ~0.71*radius of the truth. [radiusMeters] <= 0 is the
         * identity (no coarsening).
         *
         * Pure and stateless → unit-testable without Android. Applied SENDER-SIDE, before
         * encryption, per real circle, and only when that circle shares "approximate".
         */
        fun coarsenLocation(lat: Double, lng: Double, radiusMeters: Double): Pair<Double, Double> {
            if (radiusMeters <= 0.0) return lat to lng
            val cellLatDeg = radiusMeters / 111320.0
            val snappedLat = Math.round(lat / cellLatDeg) * cellLatDeg
            val metersPerDegLng = 111320.0 * Math.cos(Math.toRadians(lat))
            val snappedLng = if (Math.abs(metersPerDegLng) < 1.0) {
                lng // near-pole guard: a degree of longitude collapses, leave it unchanged
            } else {
                val cellLngDeg = radiusMeters / metersPerDegLng
                Math.round(lng / cellLngDeg) * cellLngDeg
            }
            return snappedLat to snappedLng
        }
    }

    /**
     * Emits the freshly-decrypted locations from each successful network refresh
     * (keyed by circleId), regardless of whether the caller was the map VM or the
     * foreground service. The UI uses this as a cold-start fast-path because the
     * Room observe-Flow's first emission lags several seconds on a cold launch.
     */
    private val _freshLocations = MutableSharedFlow<Pair<String, List<LocationEntity>>>(
        replay = 1, extraBufferCapacity = 8
    )
    val freshLocations: SharedFlow<Pair<String, List<LocationEntity>>> = _freshLocations.asSharedFlow()

    // Cold-start fast-path accumulator: the latest successfully-decrypted
    // location per user, per circle. refreshLatestLocations emits the MERGED
    // values instead of just the current refresh's decrypted subset, so a
    // single refresh that only partially decrypts (a transient envelope /
    // session-key timing failure at cold start — "1/2 succeeded") doesn't
    // erase a member who decrypted on an earlier refresh. Only consulted while
    // Room's observe-Flow is still cold (memberLocations = room.ifEmpty {
    // fresh }); once Room warms up it wins, so a stale entry here can't outlive
    // the source of truth, and a fresh process starts the map empty again.
    private val freshByUser =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, LocationEntity>>()

    /**
     * By circle: where each member who turned real-time sharing off there was when they did, as
     * the latest read's `last_known` says (with real time off, the user's last location is the one
     * shown). Only a row opened under the circle's CURRENT key: after the next rotation their
     * marker goes and "Location sharing off" stays. Kept apart and in memory only: never in Room
     * `locations`, a refresh's members, the replay guard or any alert, so nothing ever judges a
     * frozen position as a fresh one. Per circle, never by member alone: someone off in one circle
     * may share live in another. Each latest read replaces its circle's.
     */
    private val _frozenLocations = MutableStateFlow<Map<String, List<LocationEntity>>>(emptyMap())
    val frozenLocations: StateFlow<Map<String, List<LocationEntity>>> = _frozenLocations.asStateFlow()

    /** A drawn frozen row kept across a rotation, for a while. In memory only. */
    private val frozenGrace = com.pingolin.track.location.FrozenGrace()

    // Replay / reorder guard. A member's location payload is E2E-encrypted and its
    // timestamp lives INSIDE the AEAD (decrypted.timestamp), so the server can't
    // forge it — but a malicious/compromised server can still REPLAY or REORDER
    // old (validly-encrypted) envelopes, which would momentarily rewind a member's
    // displayed marker to a stale position. We remember the highest AUTHENTICATED
    // timestamp we have already accepted per member and drop any decrypted location
    // whose authenticated timestamp is <= it (see filterReplayedMemberLocations).
    //
    // Keyed [circleId] -> ["userId:deviceId"] -> lastAcceptedTs:
    //  - circleId first because the SAME real-world GPS timestamp is re-encrypted
    //    independently per circle, so a cross-circle key would false-drop the very
    //    same update the second time we see it in another circle.
    //  - "userId:deviceId" (device granularity) so a member's genuinely-older
    //    update from a SECOND device is not dropped by the first device's newer one.
    // In-memory only, mirroring freshByUser's lifetime: a fresh process starts empty
    // (first-seen is accepted) and cleanup() clears it on teardown.
    private val lastAcceptedMemberTs =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()

    /**
     * This device's own most-recent position, published on every location send.
     * The map renders the self marker from THIS instead of from decrypting the
     * server's re-encrypted echo of our own location. We don't need the server
     * to tell us where we are — and sourcing it locally sidesteps the cold-start
     * race where a freshly-launched client doesn't yet hold the circle-key
     * version our last upload was wrapped under, which would briefly leave the
     * own marker undecryptable. Circle-independent: the position is the same in
     * every circle.
     */
    data class SelfPosition(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val speed: Double?,
        val heading: Double?,
        val accuracy: Double?,
        val timestamp: Long,
    ) {
        companion object {
            /**
             * Map a fix to a self position, or null when it must not be
             * published.
             *
             * Null for a history replay: those re-encrypt PAST points for a
             * newly-joined circle, and publishing one would jump the self
             * marker through history stamped "just now".
             *
             * Pure so both send paths can be shown to agree.
             */
            @JvmStatic
            fun from(
                location: android.location.Location,
                isHistoryReplay: Boolean,
                nowMs: Long = System.currentTimeMillis(),
            ): SelfPosition? {
                if (isHistoryReplay) return null
                return SelfPosition(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                    heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    timestamp = nowMs,
                )
            }
        }
    }
    private val _selfLocation = kotlinx.coroutines.flow.MutableStateFlow<SelfPosition?>(null)
    val selfLocation: kotlinx.coroutines.flow.StateFlow<SelfPosition?> = _selfLocation

    /**
     * Publish our own position for the map's self marker, from the location we
     * are SENDING (not the server's re-encrypted echo), so the own marker is
     * instant and decrypt-independent.
     *
     * EVERY send path must call this: `sendEnvelopeLocation` (the circle path)
     * and `sendSelfTrackingLocation` (the personal path) alike. A path that
     * skips it leaves the own marker frozen at whatever another send last left
     * there, so the app reports its position correctly to the server while
     * drawing the user hours in the past. One helper keeps every send path
     * publishing the same way.
     */
    private fun publishSelfPosition(location: android.location.Location, isHistoryReplay: Boolean) {
        SelfPosition.from(location, isHistoryReplay)?.let { _selfLocation.value = it }
    }

    fun observeLatestLocationsByCircle(circleId: String): Flow<List<LocationEntity>> =
        locationDao.observeLatestByCircle(circleId).orWhenUnopenable(emptyList())

    /**
     * Drop cached locations for users no longer in the circle. Keeps the
     * map from rendering deleted-member uploads as "U" (Unknown) markers
     * after the member list refresh removes them from circle_members.
     */
    suspend fun pruneLocationsForMissingMembers(circleId: String, keepUserIds: List<String>): Int {
        if (keepUserIds.isEmpty()) return 0
        // Who shares approximately there goes the same way.
        approximateMembers.retainMembers(circleId, keepUserIds)
        return locationDao.deleteForMissingMembers(circleId, keepUserIds)
    }

    /**
     * Viewers' copies: a member of [roster] who keeps their location history from [circleId]
     * (`history_sharing` "off") leaves only their newest position on this phone, the one the map
     * shows, and none while their real-time sharing is off too (then the server sends them in
     * neither array, and they're drawn nowhere). Level-triggered, on every member list read and
     * every latest or history read, so rows an older app version stored are cleared too, and each
     * position replaces theirs instead of adding a trail. A viewer's history comes only from the
     * server's history read, which leaves them out. Never this account's own rows. Returns how many
     * rows went.
     */
    suspend fun purgeHiddenHistory(circleId: String, roster: List<com.pingolin.track.data.local.entity.CircleMemberEntity>): Int {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return 0
        val me = sessionManager.getUserId()
        val hiding = roster.filter {
            com.pingolin.track.ui.circles.MemberSharing.historyOff(it) &&
                it.userId != me && !com.pingolin.track.util.AccountIds.same(it.userId, me)
        }
        if (hiding.isEmpty()) return 0
        val stored = locationDao.userIdsIn(circleId)
        var gone = 0
        for (member in hiding) {
            val bothOff = member.locationSharing == com.pingolin.track.ui.circles.MemberSharing.OFF
            for (spelling in stored.filter { com.pingolin.track.util.AccountIds.same(it, member.userId) || it == member.userId }) {
                gone += if (bothOff) locationDao.deleteMember(circleId, spelling) else locationDao.deleteAllButNewest(circleId, spelling)
            }
            // The map's first-paint copy keeps only a member's newest position; with both off, none.
            if (bothOff) freshByUser[circleId]?.keys?.removeIf { com.pingolin.track.util.AccountIds.same(it, member.userId) || it == member.userId }
        }
        if (gone > 0) Log.i(TAG, "Dropped $gone cached positions of members hiding their history in $circleId")
        return gone
    }

    /** [purgeHiddenHistory] against the member list as last stored; a failure leaves the cache as it was, for the next read. */
    private suspend fun purgeHiddenHistory(circleId: String) {
        try {
            purgeHiddenHistory(circleId, circleRepository.get().storedMembers(circleId))
        } catch (e: Exception) {
            Log.w(TAG, "Hidden history not purged in $circleId: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Forget who shares approximately in any circle but [circleIds], this account's WHOLE
     * circle list. Called by every circles refresh that read the whole list.
     */
    fun keepApproximateCircles(circleIds: Set<String>) = approximateMembers.retainCircles(circleIds)

    fun requestLocationUpdates(intervalMs: Long = 30000): Flow<android.location.Location> = callbackFlow {
        val handle = locationProvider.requestUpdates(
            LocationUpdateRequest(intervalMs, LocationAccuracy.HIGH, Looper.getMainLooper()),
            object : LocationUpdateListener {
                override fun onLocation(location: android.location.Location) {
                    trySend(location)
                }
            },
        )
        awaitClose { handle.cancel() }
    }

    suspend fun getCurrentLocation(): android.location.Location? {
        return try {
            locationProvider.currentLocation(LocationAccuracy.HIGH)
                // Taken to send (a check-in, a needs-attention alert, a destination): the newest
                // position, which a liveness check-in may re-send (NewestOwnFix).
                ?.also { newestOwnFix.offer(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun startStreaming(deviceId: String) {
        // Minimize lock scope to avoid blocking other location operations during SDK initialization
        val needsInit = synchronized(streamingLock) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "startStreaming: deviceId=$deviceId, locationManager exists=${locationManager != null}")
            }

            val sdkIsStreaming = try {
                locationManager?.isStreaming() ?: false
            } catch (e: IllegalStateException) {
                // LocationManager was destroyed (e.g., service restart) — recreate it
                Log.w(TAG, "startStreaming: LocationManager destroyed, will recreate")
                locationManager = null
                false
            }
            if (isStreaming && !sdkIsStreaming) {
                Log.w(TAG, "startStreaming: SDK stream was closed, will restart")
                isStreaming = false
            }

            locationManager == null
        }

        // Create LocationManager outside the lock (can be slow due to network)
        if (needsInit) {
            val token = sessionManager.getAccessToken()
            val userId = sessionManager.getUserId()

            if (token == null) {
                Log.e(TAG, "startStreaming: No access token available, cannot start streaming")
                return
            }
            if (userId == null) {
                Log.e(TAG, "startStreaming: No user ID available, cannot start streaming")
                return
            }

            val ingestUrl = sessionManager.getIngestUrl()
            Log.d(TAG, "startStreaming: Creating LocationManager with ingestUrl=$ingestUrl")

            val sdkConfig = SdkConfig(
                authEndpoint = sessionManager.getAuthBaseUrl(),
                ingestEndpoint = ingestUrl,
                apiEndpoint = sessionManager.getApiBaseUrl(),
                environment = SdkEnvironment.CUSTOM
            )
            val privacyConfig = PrivacyConfig(
                enableTimestampBucketing = true,
                timestampBucketSeconds = 300L, // 5 minutes
                addTimestampJitter = true,
                enableBatchUploads = false,
                batchSize = 10u,
                maxBatchDelaySeconds = 60uL,
                enableBatchPadding = false,
                paddedBatchSize = 10u
            )

            try {
                val newManager = LocationManager(sdkConfig, token, userId, cryptoManager, privacyConfig)
                newManager.setErrorCallback(LocationStreamErrorCallback())
                synchronized(streamingLock) {
                    // Double-check another thread didn't init while we were creating
                    if (locationManager == null) {
                        locationManager = newManager
                        streamToken = token
                        Log.d(TAG, "startStreaming: LocationManager created successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "startStreaming: Failed to create LocationManager", e)
                return
            }
        }

        synchronized(streamingLock) {
            if (!isStreaming) {
                try {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "startStreaming: Calling SDK startStreaming with deviceId=$deviceId")
                    }
                    locationManager?.startStreaming(deviceId)
                    isStreaming = true
                    // NOT resetReconnectState(): the SDK returns before
                    // it has even tried to connect, so resetting the
                    // attempt counter here would make every failure wait
                    // the 1 s base delay, and a stream that cannot
                    // connect would be retried about once a second,
                    // indefinitely. Only drop the queued retry; the
                    // counter resets once this stream has lasted
                    // (onStreamError).
                    cancelReconnect()
                    streamStartedAtMs = android.os.SystemClock.elapsedRealtime()
                    val sdkState = locationManager?.isStreaming() ?: false
                    Log.d(TAG, "startStreaming: Streaming started successfully, SDK isStreaming=$sdkState")
                } catch (e: Exception) {
                    Log.e(TAG, "startStreaming: Failed to start streaming: ${e.message}", e)
                    isStreaming = false
                }
            } else {
                val sdkState = locationManager?.isStreaming() ?: false
                Log.d(TAG, "startStreaming: Already streaming (local=$isStreaming, SDK=$sdkState), skipping start")
            }
        }
    }

    fun stopStreaming() {
        synchronized(streamingLock) {
            try {
                locationManager?.stopStreaming()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stopStreaming: LocationManager already destroyed")
            }
            locationManager = null
            isStreaming = false
            streamToken = null
        }
        streamStartedAtMs = 0L
    }

    fun isStreaming(): Boolean = synchronized(streamingLock) { isStreaming }

    /**
     * Hand [token] to the live stream, which reads it on its next RPC. A no-op with
     * no stream up: the next one is built from the stored token anyway.
     */
    private fun pushTokenToStream(token: String) {
        val manager = synchronized(streamingLock) {
            val m = locationManager ?: return
            if (streamToken == token) return
            streamToken = token
            m
        }
        try {
            manager.updateToken(token)
            Log.d(TAG, "Stream token updated in place")
        } catch (e: Exception) {
            // A destroyed manager: the next send rebuilds the stream from prefs.
            Log.w(TAG, "Stream token update failed: ${e.message}")
        }
    }

    /**
     * Make sure the stream's next RPC will not present an expired token.
     *
     * Without this, a send that races the REST refresh goes out with the old
     * token, the server refuses it, the SDK drops the burst with it, and the
     * stream is torn down and rebuilt — all to learn what the token's own `exp`
     * already said.
     *
     * Refreshes only while the foreground service is up. From a worker with the
     * service dead that is precisely the background rotation TokenRefresher warns
     * against (a process frozen mid-rotation keeps a consumed refresh token), so
     * there it only adopts a stored token that is already fresher.
     */
    private suspend fun ensureStreamTokenFresh() {
        try {
            val presented = synchronized(streamingLock) { if (locationManager != null) streamToken else null }
            val stored = sessionManager.getAccessToken()
            when (streamTokenAction(presented, stored, System.currentTimeMillis())) {
                StreamTokenAction.NONE -> Unit
                StreamTokenAction.PUSH_STORED -> stored?.let { pushTokenToStream(it) }
                StreamTokenAction.REFRESH -> {
                    if (!com.pingolin.track.service.LocationTrackingService.isRunning) return
                    // The listener registered in init pushes the fresh token.
                    val outcome = tokenRefresher.refresh()
                    Log.d(TAG, "Pre-send stream token refresh: $outcome")
                }
            }
        } catch (e: Exception) {
            // Best effort: the send goes ahead, and a refused token still takes the
            // onStreamError refresh path.
            Log.w(TAG, "Pre-send stream token check failed: ${e.message}")
        }
    }

    /** What a send does with one circle for a point, by when the point was taken ([pauseGate]). */
    internal enum class PauseGate { SEND, SKIP, HOLD }

    /**
     * A point taken inside a pause of [circleId] at [capturedAtMs] that this phone knows of
     * ([com.pingolin.track.util.PauseIntervals]) never goes there ([PauseGate.SKIP]), however fresh
     * or late, and whatever the circle's posture is now; the server's ingest, which sees only when it
     * arrives, can't tell (a batched fix, a held one, a re-send).
     *
     * A point that [waits] (a kept point, a queued one) goes only once the record can place it: read,
     * and synced since the point was taken, so a pause set and ended on another phone while this one
     * was offline is known first ([PauseGate.HOLD] until then; it stays where it is). A live point
     * taken within [LATE_AFTER_MS] skips only that wait: it goes by its circle's posture NOW, checked
     * beside this, and ingest drops what arrives during a pause. An older live point (a held or
     * cached fix) waits for nothing either: one the record can't place yet, or read, doesn't go there
     * now. After a resume made on this phone the first point goes at once
     * ([com.pingolin.track.util.PauseIntervals.resumedHere]); after one seen from elsewhere, those
     * taken within the margin of its end don't. Personal history is never held back.
     */
    internal fun pauseGate(circleId: String, capturedAtMs: Long, waits: Boolean): PauseGate {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return PauseGate.SEND
        val inPause = try {
            pauseIntervals.covers(circleId, capturedAtMs)
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            Log.w(TAG, "Pause record unreadable (${e.javaClass.simpleName}): a point to $circleId taken before now may wait")
            null
        }
        if (inPause == true) return PauseGate.SKIP
        // Taken just now and live: no wait for a read.
        if (!waits && !isLate(capturedAtMs)) return PauseGate.SEND
        // Waiting, or late: placed only by a privacy row read after it; unplaced or unread, it doesn't go.
        return if (inPause == null || !pauseIntervals.syncedSince(circleId, capturedAtMs)) PauseGate.HOLD else PauseGate.SEND
    }

    /** A live fix taken a while ago: one the record of pauses must be able to place. */
    private fun isLate(capturedAtMs: Long): Boolean = wallNow() - capturedAtMs > LATE_AFTER_MS

    /** The wall clock a point is judged late by ([isLate]) and a queued copy stamped with ([PendingLocationEntity.createdAt]). Tests set it. */
    internal var wallNow: () -> Long = System::currentTimeMillis

    /** When [rereadPrivacyBefore] last read the circle list again. */
    @Volatile
    private var lastPrivacyReread = 0L

    /**
     * Back from an offline stretch, before what waited goes (iOS does the same): one light privacy
     * re-read, the `privacy_check` wake's own, when some of [waiting] (circle, capture time) is a
     * point the record of pauses can't place yet. A pause set and ended elsewhere while this phone
     * was offline is known first, and the rest go at once rather than at the next 5-minute pass.
     * At most one a minute; if it fails (still offline), [pauseGate] holds what it can't place.
     */
    private suspend fun rereadPrivacyBefore(waiting: List<Pair<String, Long>>) {
        val unplaced = waiting.any { (circleId, capturedAtMs) ->
            circleId != SELF_CIRCLE_ID && circleId != SELF_CIRCLE_UUID && !pauseIntervals.syncedSince(circleId, capturedAtMs)
        }
        if (!unplaced) return
        val now = System.currentTimeMillis()
        if (now - lastPrivacyReread < PRIVACY_REREAD_EVERY_MS) return
        lastPrivacyReread = now
        try {
            circleRepository.get().refreshCircles()
                .onFailure { Log.w(TAG, "Privacy re-read before a drain failed: ${it.javaClass.simpleName}; what it can't place waits") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Privacy re-read before a drain failed: ${e.javaClass.simpleName}; what it can't place waits")
        }
    }

    /**
     * Bubble radius in METERS to coarsen an outgoing share to for [circleId], or 0.0
     * (identity → send the raw point) when the circle shares "exact", when it is a
     * SELF target, or when no local privacy setting is cached.
     *
     * Reads only the locally-cached privacy setting (Room primary-key lookup, no
     * network) so it is cheap on the hot send path. Coarsening MUST happen
     * sender-side: the server and every viewer are untrusted, so an approximate
     * circle's raw point must never leave this device. See [coarsenLocation] for the
     * algorithm.
     * A MISSING setting is the server's no-row default, exact. One that can't be READ
     * (the lookup throws) is a posture this phone doesn't know, so it gets the widest
     * bubble, as an unknown one does everywhere else: returning exact there would seal
     * the raw point whenever this read fails just after the send path's own read has
     * passed. This read stays, rather than the gate's row being passed in: the seal's
     * posture read has to fall just before its stamp, and the gate's comes earlier,
     * awaits apart.
     */
    private suspend fun coarsenRadiusMetersFor(circleId: String): Double {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return 0.0
        val setting = try {
            privacySettingDao.get(circleId)
        } catch (e: Exception) {
            Log.w(TAG, "coarsenRadiusMetersFor: privacy lookup failed for $circleId: ${e.message} — the widest bubble")
            return MAX_BUBBLE_RADIUS_MILES * METERS_PER_MILE
        }
        // Approximate, or an ended pause that had a bubble before it (see [sendsBubble]).
        if (setting == null || !setting.sendsBubble()) return 0.0
        // Guard the documented 1-25 mile range; a 0/negative stored radius would otherwise
        // disable coarsening and silently leak the exact point despite "approximate" mode.
        val miles = (setting.approximateRadiusMiles ?: DEFAULT_BUBBLE_RADIUS_MILES).coerceIn(1, 25)
        return miles * METERS_PER_MILE
    }

    /**
     * The bubble, in meters, a point taken at [capturedAtMs] goes to [circleId] in: the circle's now
     * ([coarsenRadiusMetersFor]), or the widest in force when the point was taken where that is wider
     * ([com.pingolin.track.util.ApproximateIntervals]). A point taken a while ago gets the widest
     * bubble while that record can't be read: coarsening is the safe side.
     */
    internal suspend fun coarsenRadiusMetersFor(circleId: String, capturedAtMs: Long): Double {
        val now = coarsenRadiusMetersFor(circleId)
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return now
        // Read once, so a point is never fresh to one check and late to the next: a late one the
        // server hasn't told this phone of is the widest before the record is read
        // ([bubbleMilesAt]), and a fresh one goes by the bubble now while the record can't be read.
        val late = isLate(capturedAtMs)
        val thenMiles = try {
            bubbleMilesAt(circleId, capturedAtMs, late)
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            Log.w(TAG, "Bubble record unreadable (${e.javaClass.simpleName}) for $circleId")
            if (late) MAX_BUBBLE_RADIUS_MILES else null
        }
        val then = thenMiles?.coerceIn(1, MAX_BUBBLE_RADIUS_MILES)?.let { it * METERS_PER_MILE } ?: 0.0
        return maxOf(now, then)
    }

    /**
     * The widest bubble of [circleId] in force at [capturedAtMs], in miles, null for none: the
     * widest for a late point whose history the server hasn't told this phone yet.
     * [com.pingolin.track.util.StoreUnreadable] while the record can't be read.
     */
    private fun bubbleMilesAt(circleId: String, capturedAtMs: Long, late: Boolean): Int? =
        if (late && !approximateIntervals.knowsSince(circleId, capturedAtMs)) MAX_BUBBLE_RADIUS_MILES
        else approximateIntervals.radiusAt(circleId, capturedAtMs)

    /**
     * Whether a queued row of [circleId], taken at [capturedAtMs] and SEALED at [sealedAtMs] (its
     * `createdAt`: just after the posture was read for the seal), may still go: it can't be coarsened
     * again, so it goes only if it was sealed in a bubble at least as wide as the circle's now and as
     * any in force when it was taken. What it was sealed in is at least the bubble this phone's own
     * privacy row had then ([com.pingolin.track.util.ApproximateIntervals.heldAt]; every seal takes
     * that row at the least): the least of the minute before, in which the read fell. Else it never
     * goes there ([PauseGate.SKIP]): a row sealed exact, and the circle approximate before it
     * drained, included. While the record can't be read it waits ([PauseGate.HOLD]).
     */
    internal suspend fun sealedBubbleGate(circleId: String, capturedAtMs: Long, sealedAtMs: Long): PauseGate {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return PauseGate.SEND
        val now = coarsenRadiusMetersFor(circleId)
        return try {
            val then = bubbleMilesAt(circleId, capturedAtMs, isLate(capturedAtMs))?.let { it * METERS_PER_MILE } ?: 0.0
            val sealedMiles = listOf(sealedAtMs - com.pingolin.track.util.PauseIntervals.SKEW_MS, sealedAtMs)
                .minOf { approximateIntervals.heldAt(circleId, it) ?: 0 }
            if (sealedMiles * METERS_PER_MILE >= maxOf(now, then)) PauseGate.SEND else PauseGate.SKIP
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            Log.w(TAG, "Bubble record unreadable (${e.javaClass.simpleName}): a queued point to $circleId waits")
            PauseGate.HOLD
        }
    }

    /**
     * The motion and precision fields to send alongside a position.
     *
     * On the COARSENED (bubble) branch these are stripped, and that is the whole
     * point of this function. Snapping the coordinate to a grid cell hides the
     * point, but shipping verbatim speed, heading and altitude beside it hands a
     * viewer everything needed to dead-reckon within the cell — and a true
     * accuracy of a few metres openly advertises sub-cell precision, which tells
     * the reader the snapped centre is not where you are. Together they recover
     * a finer position than the bubble is meant to reveal, so the bubble stops
     * being a bubble.
     *
     * The iOS app does the same (SDKLocationStreamer's coarsened branch nulls the
     * three fields and floors accuracy to the cell), so the same setting protects
     * users identically on either platform.
     *
     * The exact (non-bubble) branch is untouched: radius 0 returns the real
     * values, because there is nothing to protect there.
     */
    private fun motionFieldsFor(
        location: android.location.Location,
        coarsenRadiusMeters: Double,
    ): MotionFields {
        if (coarsenRadiusMeters <= 0.0) {
            return MotionFields(
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            )
        }
        // Floor accuracy to the cell so it never claims to be more precise than
        // the cell it was snapped to; an absent accuracy becomes the cell size
        // rather than null, for the same reason.
        val reportedAccuracy = if (location.hasAccuracy()) {
            maxOf(location.accuracy.toDouble(), coarsenRadiusMeters)
        } else {
            coarsenRadiusMeters
        }
        return MotionFields(
            altitude = null,
            speed = null,
            heading = null,
            accuracy = reportedAccuracy,
        )
    }

    /** Send-now's send to [circleId]: what became of it, said as it is ([SendOutcome]). */
    suspend fun sendLocation(
        circleId: String,
        location: android.location.Location,
        batteryLevel: Int?
    ): Result<SendOutcome> {
        // Send-now and the map-open refresh come this way, outside the tracking service: the
        // newest position, which its liveness check-in must not go back on (NewestOwnFix). Noted
        // as sent whatever the guards say about keeping it.
        newestOwnFix.offer(location)
        newestOwnFix.noteSent(location)
        return try {
            val userId = sessionManager.getUserId()
            if (userId == null) {
                Log.e(TAG, "sendLocation failed: No user ID")
                return Result.failure(Exception("No user"))
            }

            if (!ensureDeviceRegistered()) {
                Log.e(TAG, "sendLocation failed: Device registration failed")
                return Result.failure(Exception("Device registration failed"))
            }

            val deviceId = sessionManager.getDeviceId()

            val hasKey = cryptoManager.hasCircleKey(circleId)
            Log.d(TAG, "Circle $circleId hasKey: $hasKey")
            if (!hasKey) {
                Log.e(TAG, "sendLocation failed: Circle key not available for $circleId")
                // Kept on the phone until the circle opens here.
                queueForCircle(circleId, location, batteryLevel)
                return Result.failure(CircleKeyUnavailableException(circleId))
            }

            // Behind on this circle's key: a member removed since holds the key we
            // would seal under. Kept on the phone and sent as history once the current
            // key arrives, unless this user has an SOS active there
            // (mayStreamLocationTo).
            if (!currentKeyGate.mayStreamLocationTo(circleId)) {
                Log.d(TAG, "sendLocation: queueing for $circleId — this device does not hold its current key")
                queueForCircle(circleId, location, batteryLevel)
                return Result.success(SendOutcome.WAITING_FOR_CIRCLE)
            }

            // Kept points for this circle go out first, as history (see sendEnvelope).
            sendKeptBefore(listOf(circleId))

            // Fail-closed privacy priming (defense in depth): if this circle has no
            // local privacy row, coarsenRadiusMetersFor below fails OPEN (null -> 0.0 ->
            // exact) and the upstream pause/realtime gates would pass too. Prime it from
            // the server (GET + local mirror only, never an outbound PUT). If the posture
            // still can't be resolved (network throw), FAIL CLOSED — don't emit a realtime
            // (possibly exact) fix to a circle whose privacy is unknown.
            if (!circleRepository.get().ensurePrivacyPrimed(circleId)) {
                Log.w(TAG, "sendLocation: privacy posture unknown for $circleId — deferring realtime send (fail closed)")
                return Result.failure(Exception("Privacy posture unknown; deferring realtime send"))
            }

            // Per-circle privacy gate (parity with the automated service/worker
            // callers and the iOS app): a PAUSED or REALTIME-OFF circle must not
            // receive a realtime fix at all. Self is exempt (no server privacy row;
            // always exact). The row was just primed above, so a still-missing row
            // is an unexpected race → FAIL CLOSED and defer, consistent with the
            // priming gate.
            //
            // shareBattery is read here too and applied to the payload below. The
            // battery level rides INSIDE the encrypted location, so the server
            // cannot strip it — the only place the setting can be honoured is here,
            // before the bytes are sealed. Self keeps its battery: that is the
            // user's own history rather than something shared with a circle.
            var sharesBattery = true
            if (circleId != SELF_CIRCLE_ID && circleId != SELF_CIRCLE_UUID) {
                val privacy = try {
                    privacySettingDao.get(circleId)
                } catch (e: Exception) {
                    Log.w(TAG, "sendLocation: privacy read failed for $circleId: ${e.message} — deferring (fail closed)")
                    return Result.failure(Exception("Privacy posture unknown; deferring realtime send"))
                }
                if (privacy == null) {
                    Log.w(TAG, "sendLocation: privacy row missing for $circleId after priming — deferring (fail closed)")
                    return Result.failure(Exception("Privacy posture unknown; deferring realtime send"))
                }
                // Mode AND deadline — see [isSharingPausedAt]. A pause with no
                // deadline is the server's "until I resume"; reading only the
                // deadline would treat it as un-paused and send an exact fix
                // anyway.
                val paused = privacy.isSharingPausedAt()
                if (paused || privacy.shareRealtime == false) {
                    Log.d(TAG, "sendLocation: skipping $circleId — ${if (paused) "sharing paused" else "realtime sharing off"}")
                    // Real-time off wins, as the server reads the two.
                    return Result.success(if (privacy.shareRealtime == false) SendOutcome.REALTIME_OFF else SendOutcome.PAUSED)
                }
                // Taken during a pause there: never sent there.
                when (pauseGate(circleId, location.time, waits = false)) {
                    PauseGate.SKIP -> {
                        Log.d(TAG, "sendLocation: skipping $circleId — taken during a pause there")
                        return Result.success(SendOutcome.PAUSED)
                    }
                    PauseGate.HOLD -> return Result.failure(Exception("A late point the pause record can't place yet; not sent"))
                    PauseGate.SEND -> Unit
                }
                // The battery switch AND "Allow alerts on me".
                sharesBattery = privacy.sendsBatteryLevel()
            }

            // Sender-side coarsening: if THIS circle shares "approximate" (bubble), snap
            // our lat/lng to a deterministic grid (cell = bubble radius) BEFORE encryption
            // so the circle's viewers only ever receive the bubble, never the exact point.
            // Exact circles return radius 0 → identity (raw point). The local copy stored
            // below stays exact (our own history).
            val coarsenRadiusMeters = coarsenRadiusMetersFor(circleId, location.time)
            // The moment the posture was read for this seal, and what a queued copy is stamped
            // with: a refresh before the queue that runs past a minute, with a switch to
            // approximate seen inside it, must not make the drain read the new bubble as this
            // seal's. Taken just after the read, so the read falls in the minute
            // [sealedBubbleGate] samples.
            val sealedAt = wallNow()
            val (sendLat, sendLng) =
                coarsenLocation(location.latitude, location.longitude, coarsenRadiusMeters)

            val motion = motionFieldsFor(location, coarsenRadiusMeters)
            val sdkLocation = Location(
                latitude = sendLat,
                longitude = sendLng,
                altitude = motion.altitude,
                speed = motion.speed,
                heading = motion.heading,
                accuracy = motion.accuracy,
                timestamp = location.time,
                batteryLevel = if (sharesBattery) batteryLevel?.toUByte() else null,
                isCharging = false // Will be updated by caller when available
            )

            ensureSessionKey(circleId)

            // Seal circle-member locations under location_aad_v3(our own per-circle user
            // pseudonym) so a malicious server cannot relabel our ciphertext under another
            // member (a bound reader's tag check then fails closed). Derive from the SAME
            // userId we encrypt into encrypted_user_id below — that is exactly what a
            // reader decrypts and derives its claimed pseudonym from, so the tags match.
            // runCatching → null (derivation miss) falls back to the unbound encrypt,
            // which a v3-capable reader still reads via its v2/v1 fallback — so a hiccup
            // never blocks a send. SELF sends live in sendSelfTrackingLocation /
            // storeLocalLocation and stay unbound (a single-user self circle has no other
            // member to relabel into).
            val senderPseudonym = runCatching {
                cryptoManager.deriveUserPseudonym(circleId, userId)
            }.getOrNull()
            // The version and device pseudonym this seal goes under, read before it.
            val sealedUnder = sealedUnder(circleId, deviceId)

            var encryptedData: uniffi.pingolin_mobile.EncryptedData
            var locationKeyId: String?
            try {
                val sessionResult = if (senderPseudonym != null) {
                    cryptoManager.encryptLocationWithSessionKeyBound(circleId, sdkLocation, senderPseudonym)
                } else {
                    cryptoManager.encryptLocationWithSessionKey(circleId, sdkLocation)
                }
                encryptedData = sessionResult.encrypted
                locationKeyId = sessionResult.keyId
            } catch (e: Exception) {
                Log.w(TAG, "Session key encryption failed, falling back to circle key: ${e.message}")
                encryptedData = if (senderPseudonym != null) {
                    cryptoManager.encryptLocationBound(circleId, sdkLocation, senderPseudonym)
                } else {
                    cryptoManager.encryptLocation(circleId, sdkLocation)
                }
                locationKeyId = null
            }

            // locationKeyId is NOT embedded in the ciphertext, and the server does not
            // extract one: the id travels as its own proto field, so it must be handed to
            // sendLocation() explicitly (and persisted on any queued copy) or the session
            // key this payload was sealed under can never be selected again.
            //
            // userId/deviceId are left EMPTY on purpose: the SDK's outgoing LocationUpdate
            // proto carries only server-derived pseudonyms (user_pseudonym /
            // device_pseudonym, BLAKE3-keyed) and never reads EncryptedLocation.userId /
            // deviceId — the streaming loop builds the proto solely from
            // encrypted_location.location plus the derived pseudonyms, dropping these
            // fields. Encrypting anything into them here is dead work.
            val encryptedLocation = EncryptedLocation(
                location = encryptedData,
                timestamp = location.time,
                userId = "",
                deviceId = ""
            )

            if (locationKeyId != null) {
                Log.d(TAG, "Location encrypted with session key: $locationKeyId")
            }

            ensureStreamTokenFresh()
            val sdkIsStreaming = locationManager?.isStreaming() ?: false
            if (!sdkIsStreaming || locationManager == null) {
                Log.d(TAG, "Starting streaming for location send (SDK streaming: $sdkIsStreaming)")
                startStreaming(deviceId)
            }

            val actuallyStreaming = locationManager?.isStreaming() ?: false
            // The SDK's sendLocation / sendEnvelopeLocation just writes the
            // message into an in-process tokio mpsc channel and returns Ok
            // as long as the channel is open. If the underlying gRPC stream
            // has silently died (network blip during idle, token expired
            // during Doze, server restart), messages continue to land in
            // the channel but never reach the server — the caller thinks
            // every send succeeded while the server sees nothing for hours.
            // Guard with a post-send isStreaming() re-check: if the SDK
            // reports the stream is down after our call, the message is in
            // a zombie channel, so we queue a safety-net copy for
            // LocationSyncWorker to retry against a fresh stream. Cheap,
            // local, no extra network. At worst it produces a duplicate
            // row (the server dedups by
            // user_pseudonym/device_pseudonym/timestamp).
            var needsQueue = !actuallyStreaming || locationManager == null
            if (!needsQueue) {
                try {
                    locationManager?.sendLocation(deviceId, circleId, encryptedLocation, locationKeyId)
                    if (locationManager?.isStreaming() != true) {
                        Log.w(TAG, "Stream died around send for $circleId, queueing safety-net copy")
                        needsQueue = true
                    } else {
                        Log.d(TAG, "Location sent via gRPC stream to circle $circleId")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "sendLocation SDK call threw for $circleId: ${e.message}, queueing")
                    needsQueue = true
                }
            } else {
                Log.w(TAG, "Streaming not available, queuing location")
            }
            if (needsQueue) {
                pendingLocationDao.insert(
                    PendingLocationEntity(
                        circleId = circleId,
                        encryptedPayload = "${encryptedData.nonce}:${encryptedData.ciphertext}",
                        timestamp = location.time,
                        createdAt = sealedAt,
                        // Carry the session key id with the ciphertext it names.
                        // Queuing without it is the same data loss as sending
                        // without it, just deferred to the drain.
                        keyId = locationKeyId
                    )
                )
                try { queueMonitor.onLocationEnqueued() } catch (e: Exception) { /* non-fatal */ }
            }
            // Sent or queued, it's this circle's latest row now: what a re-seal after a
            // rotation is built from.
            noteLastSent(circleId, sealedUnder, encryptedData, locationKeyId, null, null, sdkLocation, coarsenRadiusMeters)

            // Store locally
            val locationEntity = LocationEntity(
                circleId = circleId,
                userId = userId,
                deviceId = deviceId,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                batteryLevel = batteryLevel,
                timestamp = location.time,
                isSynced = actuallyStreaming,
                dayBucket = (location.time / MS_PER_DAY).toInt()
            )
            persistAndEvaluate(listOf(locationEntity), fromServer = false)

            Log.d(TAG, "Location stored locally, streaming: $actuallyStreaming")
            // Kept for the drain, whether or not the stream took a copy: "waiting to send",
            // never "sent" for what may not have gone.
            Result.success(if (needsQueue) SendOutcome.QUEUED else SendOutcome.SENT)
        } catch (e: Exception) {
            Log.e(TAG, "sendLocation exception", e)
            Result.failure(e)
        }
    }

    /**
     * Send a location to each of [targetIds] (circle ids and/or [SELF_CIRCLE_ID]) using
     * envelope encryption: the point is sealed once under a random msg_key and the
     * msg_key is wrapped per target, rather than re-encrypting the point N times.
     *
     * This is the HISTORY send: the server keeps the row in the history table as well
     * as in `latest_locations`. Use [sendEnvelopeLivenessRefresh] for a device that has
     * not moved — see its docs for why the two are separate calls.
     */
    suspend fun sendEnvelopeLocation(
        targetIds: List<String>,
        location: android.location.Location,
        batteryLevel: Int?,
        // A send of a PAST point. It must NOT touch live-position state: publishing
        // an old point as _selfLocation would jump the map's self marker through history
        // stamped "just now", firing _locationSentEvent falsely and inserting duplicate local
        // rows.
        // When true: skip the self-marker publish, the sent-event emit, and the local
        // LocationEntity re-insert. Live callers keep the default (false).
        isHistoryReplay: Boolean = false
    ): Result<Unit> = sendEnvelope(targetIds, location, batteryLevel, isHistoryReplay, livenessOnly = false)

    /**
     * Send a LIVENESS CHECK-IN to each of [targetIds]: the same encrypted point, over
     * the same envelope path, but routed by the server to `latest_locations` ONLY —
     * no history row.
     *
     * Send this when the device took a fix at its normal cadence and found it has NOT
     * moved far enough for the position to be worth keeping. "This phone is alive and
     * could have sent a position" is exactly what the green dot and the inactivity
     * alerts mean, so the fix still travels; what it must not do is write one history
     * row per circle per minute for a phone sitting on a desk.
     *
     * Three deliberate differences from [sendEnvelopeLocation], each load-bearing:
     *
     *  - The payload carries the CURRENT time, not the cached fix's original time. A
     *    check-in asserts "I am here now"; the map and the inactivity logic both read
     *    that decrypted timestamp, so a stale one would defeat the whole point.
     *  - A failure is DROPPED, never queued. The offline queue is replayed minutes or
     *    hours later ([syncPendingLocations]), and replaying a check-in would assert
     *    a position the device has since left as its current one. A missed check-in
     *    is self-correcting: the next one is along in minutes.
     *  - The SDK call is `sendEnvelopeLivenessRefresh`, which sets `liveness_only` on
     *    the wire. A server that doesn't know the field ignores it and writes history
     *    as for any other send.
     *
     * Everything else — the privacy gates, the per-circle coarsening, the
     * sender-pseudonym binding, the local persist-and-evaluate — is the SAME code,
     * because a check-in that skipped a privacy gate would be a privacy hole that
     * only appeared when the user stood still.
     */
    suspend fun sendEnvelopeLivenessRefresh(
        targetIds: List<String>,
        location: android.location.Location,
        batteryLevel: Int?,
    ): Result<Unit> = sendEnvelope(targetIds, location, batteryLevel, isHistoryReplay = false, livenessOnly = true)

    /**
     * The one implementation behind [sendEnvelopeLocation] and
     * [sendEnvelopeLivenessRefresh].
     *
     * Deliberately ONE body rather than two: the privacy posture priming, the paused /
     * realtime-off gates, the shareBattery enforcement and the AAD-v3 sender binding
     * below are the rules that decide what leaves this device, and a copy of them that
     * drifted would fail in the one mode nobody watches. [livenessOnly] switches the
     * three things that genuinely differ; the callers above document them.
     */
    private suspend fun sendEnvelope(
        targetIds: List<String>,
        location: android.location.Location,
        batteryLevel: Int?,
        isHistoryReplay: Boolean,
        livenessOnly: Boolean,
        /**
         * A point kept while this device could not share with the circle, sent as HISTORY
         * (`history_only`): no live update, no alerts. Never queued for the live replay; a
         * point that does not go out fails the call, so the caller keeps it.
         */
        historyOnly: Boolean = false,
    ): Result<Unit> {
        // Resolved once, here, so the payload, the SDK envelope, the local row and
        // the "last shared" event can never disagree about which time this is.
        val sendTimestamp = sendTimestampFor(livenessOnly, location.time, System.currentTimeMillis())
        val logKind = if (livenessOnly) "liveness check-in" else "location"
        // A live position (not a check-in re-sending one, nor a kept or late point), from the
        // service, member_joined or the heartbeat worker: the newest a check-in may re-send.
        if (!livenessOnly && !isHistoryReplay && !historyOnly) {
            newestOwnFix.offer(location)
            newestOwnFix.noteSent(location)
        }
        return try {
            val userId = sessionManager.getUserId()
            if (userId == null) {
                Log.e(TAG, "sendEnvelopeLocation failed: No user ID")
                return Result.failure(Exception("No user"))
            }

            // Publish our own position for the map's self marker. Driven from the
            // location we're sending (not the server's re-encrypted echo), so the
            // own marker is instant and decrypt-independent. See selfLocation.
            // Skipped for history replay: an old point is not our live position.
            publishSelfPosition(location, isHistoryReplay)

            if (!ensureDeviceRegistered()) {
                Log.e(TAG, "sendEnvelopeLocation failed: Device registration failed")
                return Result.failure(Exception("Device registration failed"))
            }

            val deviceId = sessionManager.getDeviceId()

            // Circles whose key moved on without this device get nothing: a member
            // removed since holds the key we would seal under — unless this user
            // has an SOS active there (mayStreamLocationTo). The point is still
            // kept locally below, under the first target.
            val sealable = targetIds.filter { currentKeyGate.mayStreamLocationTo(it) }
            // What was kept for these circles while this device could not share with
            // them goes out first, as history: JUST BEFORE this live point, so this
            // point is the one that lands last — on a server that treats history as
            // live, whatever lands last is the member's position.
            if (!isHistoryReplay) sendKeptBefore(sealable)
            if (sealable.size < targetIds.size) {
                Log.d(TAG, "sendEnvelope: ${targetIds.size - sealable.size} circle(s) skipped — this device does not hold their current key")
                // Kept on the phone for them — not a liveness check-in, which
                // asserts "here now" and would be false later, and not a replay,
                // which is the queue.
                if (!livenessOnly && !isHistoryReplay) {
                    for (skipped in targetIds - sealable.toSet()) queueForCircle(skipped, location, batteryLevel)
                }
            }

            for (targetId in sealable) {
                if (targetId != SELF_CIRCLE_ID) {
                    try {
                        ensureSessionKey(targetId)
                    } catch (e: Exception) {
                        // WARN (not DEBUG): session-key setup failure means the send will fall
                        // back to the static circle key, which is slower to rotate and a real
                        // signal when diagnosing "my friend can't see me" reports.
                        Log.w(TAG, "Session key setup skipped for $targetId: ${e.message}")
                    }
                }
            }

            // Map "self" to nil UUID for SDK lookups and server communication
            val sdkTargetIds = sealable.map { id ->
                if (id == SELF_CIRCLE_ID) SELF_CIRCLE_UUID else id
            }

            for (targetId in sdkTargetIds) {
                // Sealed under: a key in use, the personal target's
                // included.
                if (!cryptoManager.hasKeyInUse(targetId)) {
                    Log.e(TAG, "sendEnvelopeLocation: Missing key for target $targetId")
                    return Result.failure(CircleKeyUnavailableException(targetId))
                }
            }

            // The SDK Location is built PER TARGET inside the loop below: an
            // "approximate" (bubble) circle coarsens the coordinates before encryption
            // while exact/self targets send the raw point, so one shared object can't
            // serve every target. See coarsenLocation / coarsenRadiusMetersFor.

            ensureStreamTokenFresh()
            val sdkIsStreaming = locationManager?.isStreaming() ?: false
            if (!sdkIsStreaming || locationManager == null) {
                Log.d(TAG, "Starting streaming for envelope location send")
                startStreaming(deviceId)
            }

            val actuallyStreaming = locationManager?.isStreaming() ?: false

            // Bind each MEMBER circle's row to our per-circle sender pseudonym
            // (location_aad_v3) so a malicious server cannot relabel our ciphertext under
            // another member — a bound reader's tag check then fails closed. A sender
            // pseudonym is PER-CIRCLE, so a single multi-target envelope can't be bound;
            // hence one envelope per target (each single-target → exactly one wrap).
            // Derive from the SAME userId the reader resolves for the row (from the
            // response's encrypted_user_id) and derives its claimed pseudonym from, so the
            // tags match. runCatching → null (derivation miss) falls back to the unbound
            // _v2 envelope, which a v3-capable reader still reads via its v2/v1 fallback —
            // a hiccup never blocks a send. SELF stays unbound (a single-user self circle
            // has no other member to relabel into), mirroring the sendLocation split.
            // Liveness delivery tally. A check-in is never queued, so "queued for
            // later" cannot stand in for "landed" the way it does on the history path:
            // without this the caller would advance its last-successful-send clock on a
            // send that went nowhere, and both staleness watchdogs would go blind
            // exactly when the stream is down. Attempted-but-none-delivered is a
            // failure; every target skipped by a privacy gate is not.
            var livenessAttempted = false
            var livenessDelivered = false
            // A kept history point that could not go out now stays kept.
            var historyUndelivered = false

            for (targetCircleId in sdkTargetIds) {
                val isSelfTarget =
                    targetCircleId == SELF_CIRCLE_UUID || targetCircleId == SELF_CIRCLE_ID

                // Fail-closed privacy priming (defense in depth): a real circle we hold
                // with NO local privacy row would fail OPEN in coarsenRadiusMetersFor
                // below (null -> 0.0 -> exact). Prime it from the server (GET + local
                // mirror only, never an outbound PUT). If the posture still can't be
                // resolved (network throw), SKIP this target — never emit a realtime
                // (possibly exact) fix to a circle whose privacy is unknown. Self is
                // exact by design and needs no row.
                if (!isSelfTarget && !circleRepository.get().ensurePrivacyPrimed(targetCircleId)) {
                    Log.w(TAG, "sendEnvelopeLocation: privacy posture unknown for $targetCircleId — skipping target (fail closed)")
                    continue
                }

                // Per-target privacy gate (parity with the automated callers and the
                // iOS app): skip a PAUSED or REALTIME-OFF circle so it never
                // receives a realtime fix. Self is exempt (handled above). The row
                // was just primed, so a still-missing row is an unexpected race →
                // skip (fail closed).
                //
                // shareBattery is read here and applied to the payload below. It has
                // to be enforced HERE, on the sender, because the battery level
                // rides inside the encrypted location: the server cannot strip a
                // field it cannot read, so a setting nobody applied before
                // encryption is not a setting at all. Self always carries it — that
                // is the user's own history, not a circle's.
                var targetSharesBattery = true
                if (!isSelfTarget) {
                    val privacy = try {
                        privacySettingDao.get(targetCircleId)
                    } catch (e: Exception) {
                        Log.w(TAG, "sendEnvelopeLocation: privacy read failed for $targetCircleId: ${e.message} — skipping (fail closed)")
                        continue
                    }
                    if (privacy == null) {
                        Log.w(TAG, "sendEnvelopeLocation: privacy row missing for $targetCircleId after priming — skipping (fail closed)")
                        continue
                    }
                    // Mode AND deadline — see [isSharingPausedAt]. A
                    // pause with no deadline is the server's "until I
                    // resume"; reading only the deadline would treat
                    // it as un-paused and wrap the key for the circle
                    // anyway.
                    val paused = privacy.isSharingPausedAt()
                    if (paused || privacy.shareRealtime == false) {
                        Log.d(TAG, "sendEnvelopeLocation: skipping $targetCircleId — ${if (paused) "sharing paused" else "realtime sharing off"}")
                        continue
                    }
                    // Taken during a pause there: a kept point or a re-sent fix never goes
                    // there, and a kept one is done with. A kept one the record can't place
                    // yet stays kept.
                    when (pauseGate(targetCircleId, location.time, waits = historyOnly)) {
                        PauseGate.SKIP -> {
                            Log.d(TAG, "sendEnvelopeLocation: skipping $targetCircleId — taken during a pause there")
                            continue
                        }
                        PauseGate.HOLD -> {
                            if (historyOnly) historyUndelivered = true
                            continue
                        }
                        PauseGate.SEND -> Unit
                    }
                    targetSharesBattery = privacy.sendsBatteryLevel()
                }

                val senderPseudonym = if (isSelfTarget) null else runCatching {
                    cryptoManager.deriveUserPseudonym(targetCircleId, userId)
                }.getOrNull()

                // Sender-side coarsening: if THIS target is a real circle sharing
                // "approximate" (bubble), snap our lat/lng to a deterministic grid
                // (cell = bubble radius) BEFORE encryption so the circle's viewers only
                // ever receive the bubble. Self targets (the device-local self marker)
                // and exact circles return radius 0 → identity (raw point).
                val coarsenRadiusMeters =
                    if (isSelfTarget) 0.0 else coarsenRadiusMetersFor(targetCircleId, location.time)
                // What a queued copy is stamped with, as in sendLocation.
                val sealedAt = wallNow()
                val (targetLat, targetLng) =
                    coarsenLocation(location.latitude, location.longitude, coarsenRadiusMeters)
                val targetMotion = motionFieldsFor(location, coarsenRadiusMeters)
                val targetLocation = Location(
                    latitude = targetLat,
                    longitude = targetLng,
                    altitude = targetMotion.altitude,
                    speed = targetMotion.speed,
                    heading = targetMotion.heading,
                    accuracy = targetMotion.accuracy,
                    // now() for a liveness check-in — see [sendEnvelopeLivenessRefresh].
                    // This is the timestamp that gets ENCRYPTED, so it is the one every
                    // reader (map "last updated", inactivity alerts) actually sees.
                    timestamp = sendTimestamp,
                    // Omitted outright when the circle's shareBattery is off, rather
                    // than sent and hidden by the reader: the payload is encrypted for
                    // that circle's members, so anything included in it IS shared.
                    batteryLevel = if (targetSharesBattery) batteryLevel?.toUByte() else null,
                    isCharging = false
                )

                // The version and device pseudonym this seal goes under, read before it.
                val sealedUnder = if (isSelfTarget) null else sealedUnder(targetCircleId, deviceId)

                // _v2 binds the location payload to its domain + the msg_key wrap
                // to its target (AAD v2); _Bound additionally binds the payload to
                // our sender pseudonym (AAD v3). Reads fall back to v2/v1 for
                // unbound rows.
                val envelope = if (senderPseudonym != null) {
                    cryptoManager.envelopeEncryptLocationBound(
                        targetLocation, listOf(targetCircleId), senderPseudonym
                    )
                } else {
                    cryptoManager.envelopeEncryptLocationV2(targetLocation, listOf(targetCircleId))
                }

                // Single-target envelope → exactly one key wrap for this circle.
                val keyWrap = envelope.keyWraps.firstOrNull()
                if (keyWrap == null) {
                    Log.w(TAG, "Envelope produced no key wrap for target $targetCircleId, skipping")
                    continue
                }
                val serverCircleId = keyWrap.targetId

                // userId/deviceId are left EMPTY: the SDK ingest proto carries only
                // server-derived pseudonyms and never reads these fields (see the
                // sibling sendLocation).
                val encryptedLocation = EncryptedLocation(
                    location = uniffi.pingolin_mobile.EncryptedData(
                        nonce = envelope.ciphertextNonce,
                        ciphertext = envelope.ciphertext
                    ),
                    // The SDK derives `day_bucket` from this, so a check-in has to carry
                    // now() here too or it would be filed under the day the cached fix
                    // was taken.
                    timestamp = sendTimestamp,
                    userId = "",
                    deviceId = ""
                )

                // See the comment on the sibling sendLocation path for the
                // post-send isStreaming() re-check rationale (detect a
                // stream that died under us while the message was already
                // queued into the SDK channel).
                var needsQueue = !actuallyStreaming || locationManager == null
                // Taken by a live stream: the circle's latest row is this one.
                var handedOff = false
                if (livenessOnly) livenessAttempted = true
                if (!needsQueue) {
                    try {
                        if (livenessOnly) {
                            locationManager?.sendEnvelopeLivenessRefresh(
                                deviceId,
                                serverCircleId,
                                encryptedLocation,
                                keyWrap.wrappedKey,
                                keyWrap.wrappedKeyNonce
                            )
                        } else if (historyOnly) {
                            locationManager?.sendEnvelopeHistoryLocation(
                                deviceId,
                                serverCircleId,
                                encryptedLocation,
                                keyWrap.wrappedKey,
                                keyWrap.wrappedKeyNonce
                            )
                        } else {
                            locationManager?.sendEnvelopeLocation(
                                deviceId,
                                serverCircleId,
                                encryptedLocation,
                                keyWrap.wrappedKey,
                                keyWrap.wrappedKeyNonce
                            )
                        }
                        if (locationManager?.isStreaming() != true) {
                            Log.w(TAG, "Stream died around $logKind for $serverCircleId, queueing safety-net copy")
                            needsQueue = true
                        } else {
                            if (livenessOnly) livenessDelivered = true
                            handedOff = true
                            Log.d(TAG, "Envelope $logKind sent via gRPC to target $serverCircleId")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "sendEnvelopeLocation SDK call threw for $serverCircleId: ${e.message}, queueing")
                        needsQueue = true
                    }
                } else {
                    Log.w(TAG, "Streaming not available, queuing envelope location for $serverCircleId")
                }
                // A liveness check-in is NEVER QUEUED — which is not the same as never
                // retried. An immediate retry is correct and the callers do one; what
                // is wrong is the DURABLE queue, which holds up to ~24h of fixes and
                // replays them on reconnect. A replayed check-in would assert a
                // position the device left hours ago as its CURRENT one, the exact lie
                // the fresh-timestamp rule above exists to prevent. A retry re-runs
                // this method and re-stamps the payload with now(); a replay ships the
                // bytes queued here, whenever the drain gets to them.
                //
                // Dropping is free: the next check-in is minutes away, and until then
                // the device is legitimately "not heard from", which is what inactivity
                // means. (There is no queue column to distinguish the two kinds —
                // PendingLocationEntity.wrappedKey only says envelope-vs-bare — so a
                // queued check-in could not be filtered at drain time either. Adding a
                // column would put a stale-position replay behind a schema change, and
                // an applied migration cannot be edited afterwards.)
                if (needsQueue && livenessOnly) {
                    Log.d(TAG, "Liveness check-in for $serverCircleId could not be sent — dropping (never queued)")
                    needsQueue = false
                }
                // The live replay queue would send a kept point as a live report. It stays
                // in the circle's own queue instead, for the next flush.
                if (needsQueue && historyOnly) {
                    historyUndelivered = true
                    needsQueue = false
                }
                if (needsQueue) {
                    // Persist THIS target's wrap alongside the ciphertext: the payload is
                    // sealed under a random per-call msg_key recoverable only via the
                    // wrap, so the drain must replay it through the envelope path. A
                    // queued row without its wrap is undecryptable forever.
                    pendingLocationDao.insert(
                        PendingLocationEntity(
                            circleId = if (serverCircleId == SELF_CIRCLE_UUID) SELF_CIRCLE_ID else serverCircleId,
                            encryptedPayload = "${envelope.ciphertextNonce}:${envelope.ciphertext}",
                            timestamp = location.time,
                            wrappedKey = keyWrap.wrappedKey,
                            wrappedKeyNonce = keyWrap.wrappedKeyNonce,
                            createdAt = sealedAt
                        )
                    )
                    try { queueMonitor.onLocationEnqueued() } catch (e: Exception) { /* non-fatal */ }
                }
                // Sent or queued live, a check-in included, it's this circle's latest row
                // now. A history point never is, and a check-in dropped unsent never was.
                if (!historyOnly && !isHistoryReplay && (handedOff || needsQueue)) {
                    noteLastSent(
                        serverCircleId, sealedUnder, encryptedLocation.location, null,
                        keyWrap.wrappedKey, keyWrap.wrappedKeyNonce, targetLocation, coarsenRadiusMeters,
                    )
                }
            }

            // Store locally for the first target (for local display). Skipped for history
            // replay: the point already lives in the self history, so re-inserting it under
            // the joined member circle would only duplicate rows.
            if (!isHistoryReplay) {
                val primaryCircleId = targetIds.first()
                val localCircleId = if (primaryCircleId == SELF_CIRCLE_UUID) SELF_CIRCLE_ID else primaryCircleId
                val locationEntity = LocationEntity(
                    circleId = localCircleId,
                    userId = userId,
                    deviceId = deviceId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                    heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    batteryLevel = batteryLevel,
                    timestamp = sendTimestamp,
                    isSynced = actuallyStreaming,
                    dayBucket = (sendTimestamp / MS_PER_DAY).toInt()
                )
                // A check-in persists locally too, deliberately.
                // persistAndEvaluate is the documented choke point where
                // "every location this device stores" reaches
                // PlaceAlertEvaluator, and a check-in is taken at a position
                // up to a whole distance threshold away from the last one that
                // was judged — enough to have crossed a 100 m ring. Skipping
                // it would silently delay the local arrival banner until the
                // user moved a full threshold. The row count is not a concern
                // here: this is one row per SEND CYCLE (not per circle) in a
                // local table, and it drops with the send rate anyway.
                persistAndEvaluate(listOf(locationEntity), fromServer = false)

                // Notify observers that a location was sent (freshness signal — a replayed
                // history point must not advance "last shared"). A check-in DOES advance
                // it: refreshing what peers see is the entire job of a check-in.
                if (actuallyStreaming) {
                    _locationSentEvent.tryEmit(sendTimestamp)
                }

                Log.d(TAG, "Envelope $logKind stored locally for $localCircleId")
            }
            if (historyUndelivered) return Result.failure(Exception("Kept location not sent; kept for later"))
            // Attempted every target and landed none: report it, so the caller does not
            // advance its "last successful send" clock on a check-in that went nowhere.
            if (livenessOnly && livenessAttempted && !livenessDelivered) {
                Log.w(TAG, "Liveness check-in reached no target (stream unavailable)")
                return Result.failure(Exception("Liveness check-in not delivered"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "sendEnvelopeLocation exception", e)
            Result.failure(e)
        }
    }

    /**
     * Handles SDK stream errors: server rejections of location updates, and the
     * re-registration / token-refresh recoveries they imply. Internal for its tests.
     */
    internal inner class LocationStreamErrorCallback : StreamErrorCallback {
        // The SDK can't take a throw from these: every body is caught (sdkCallback).
        override fun onLocationRejected(deviceId: String, error: String) = com.pingolin.track.util.sdkCallback(TAG, "onLocationRejected") {
            Log.w(TAG, "Location rejected for device $deviceId: $error")

            // "Device not found or not owned by user" means either the device was
            // deactivated (is_active=FALSE) after a push token was invalidated, or it
            // belongs to a different user (the account was switched).
            if (error.contains("Device not found", ignoreCase = true) ||
                error.contains("not owned by user", ignoreCase = true)) {
                // Once per cooldown, however many fixes are refused meanwhile: otherwise
                // a phone the server can't take (no push route) would re-register on
                // every refused fix, without end.
                val now = elapsedNow()
                if (!rejectionReregistrationDue(now, lastRejectionReregistrationAt)) {
                    Log.w(TAG, "Device still refused; it re-registers again after the cooldown")
                    return@sdkCallback
                }
                lastRejectionReregistrationAt = now
                Log.w(TAG, "Device registration invalid, will re-register and restart streaming")
                sessionManager.setDeviceRegistered(false)
                stopStreaming()
                pendingReregistration = true
                kotlinx.coroutines.MainScope().launch {
                    val registered = ensureDeviceRegistered()
                    if (registered) {
                        Log.i(TAG, "Device re-registered successfully, restarting streaming")
                        val newDeviceId = sessionManager.getDeviceId()
                        startStreaming(newDeviceId)
                    } else {
                        Log.e(TAG, "Device re-registration failed")
                    }
                }
            }
        }

        override fun onStreamError(error: String) = com.pingolin.track.util.sdkCallback(TAG, "onStreamError") {
            Log.e(TAG, "Stream error: $error")
            isStreaming = false
            reconnectAttempts = reconnectAttemptsAfterFailure(
                reconnectAttempts, streamStartedAtMs, android.os.SystemClock.elapsedRealtime()
            )
            streamStartedAtMs = 0L

            // The stream reads its token per RPC, and every refresh hands it the
            // new one (pushTokenToStream), so this should be rare. It is still the
            // fallback when the refresh races the send: the server's
            // Unauthenticated ends the SDK's upload task, so refresh and rebuild
            // the stream with the new token, then reconnect normally.
            //
            // Reconnecting without the refresh would be an hours-long silent
            // outage: the phone connects, sends one location, is rejected,
            // reconnects with the SAME stale token, and keeps failing until the
            // user relaunches the app or signs in again.
            if (error.contains("Unauthenticated", ignoreCase = true) ||
                error.contains("Invalid or expired token", ignoreCase = true)) {
                kotlinx.coroutines.MainScope().launch {
                    val refreshed = refreshTokenForStream()
                    if (refreshed) {
                        Log.i(TAG, "Token refreshed after Unauthenticated; tearing down SDK to pick up new token")
                        // stopStreaming() nulls out locationManager so the
                        // next startStreaming() rebuilds it from prefs
                        // (which now has the fresh token).
                        stopStreaming()
                        // Retry the operation that failed, once, rather than
                        // waiting out a backoff with a token that is now good.
                        // OkHttp gets this for free by returning a new Request;
                        // the stream has to ask. One attempt, not a loop: if the
                        // fresh token fails too, the reconnect schedule below is
                        // the right place for that, not a retry spiral.
                        // attemptReconnect() no-ops if a stream is already up,
                        // and on failure the scheduled backoff below still runs.
                        runCatching { attemptReconnect() }
                            .onFailure {
                                Log.w(TAG, "Immediate reconnect after refresh failed: ${it.message}")
                                scheduleReconnect()
                            }
                    } else {
                        Log.w(TAG, "Token refresh failed after Unauthenticated; reconnect will reuse old token (likely fail again)")
                        scheduleReconnect()
                    }
                }
            } else {
                scheduleReconnect()
            }
        }
    }

    /**
     * Exchange the current (expired) access token for a fresh one.
     * Returns true if SessionManager now holds a fresher token than before.
     *
     * Only used by onStreamError above — the rest of the app's HTTP traffic
     * goes through OkHttp's TokenAuthenticator which handles 401s on its
     * own. The gRPC SDK doesn't run through OkHttp, so it needs this hook.
     *
     * The Authorization header attached by the OkHttp interceptor carries
     * the current (possibly expired) JWT; the server validates the
     * signature and the backing session row, ignoring `exp`, and issues a
     * new token in the response body.
     */
    private suspend fun refreshTokenForStream(): Boolean {
        // Delegates to the shared TokenRefresher; nothing here persists
        // tokens. A second, independent refresh (calling authApi.refreshToken
        // directly and persisting both tokens with no lock and no
        // coordination with TokenAuthenticator) breaks sessions: the server
        // rotates the refresh token on every call and tolerates only one
        // rotation back, so two refreshes at overlapping moments leave one
        // side holding a retired token — the next refresh 401s and the client
        // signs itself out against a session the server still considers live.
        return when (val outcome = tokenRefresher.refresh()) {
            is com.pingolin.track.util.TokenRefresher.Outcome.Refreshed,
            is com.pingolin.track.util.TokenRefresher.Outcome.AlreadyFresh -> true

            is com.pingolin.track.util.TokenRefresher.Outcome.SessionRejected,
            is com.pingolin.track.util.TokenRefresher.Outcome.NoTokenAfterRefresh -> {
                // Terminal, and TokenAuthenticator's path performs the
                // teardown; the guard in triggerForceLogout makes a duplicate
                // harmless if both arrive at once.
                Log.w(TAG, "refreshTokenForStream: session rejected ($outcome)")
                false
            }

            else -> {
                Log.w(TAG, "refreshTokenForStream: not refreshed ($outcome); keeping session")
                false
            }
        }
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     * This is called when the stream encounters an error.
     */
    private fun scheduleReconnect() {
        if (pendingReconnect) {
            Log.d(TAG, "Reconnect already scheduled, skipping")
            return
        }

        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            // Don't give up permanently — reset counter and retry after a long delay.
            // The server may be restarting (rolling deployment, scaling, etc.)
            Log.w(TAG, "Max reconnect attempts ($RECONNECT_MAX_ATTEMPTS) reached, will retry in 5 minutes")
            reconnectAttempts = 0
            pendingReconnect = true
            reconnectHandler.postDelayed({
                pendingReconnect = false
                attemptReconnect()
            }, 5 * 60 * 1000L) // 5 minutes
            return
        }

        val baseDelay = RECONNECT_BASE_DELAY_MS * (1L shl minOf(reconnectAttempts, 6))
        val jitter = (Math.random() * 0.3 * baseDelay).toLong()
        val delay = minOf(baseDelay + jitter, RECONNECT_MAX_DELAY_MS)

        reconnectAttempts++
        pendingReconnect = true

        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms")

        reconnectHandler.postDelayed({
            pendingReconnect = false
            attemptReconnect()
        }, delay)
    }

    private fun attemptReconnect() {
        val deviceId = sessionManager.getDeviceId()

        synchronized(streamingLock) {
            if (isStreaming) {
                Log.d(TAG, "Already streaming, skipping reconnect")
                resetReconnectState()
                return
            }
        }

        Log.i(TAG, "Attempting to reconnect stream (attempt $reconnectAttempts)")
        startStreaming(deviceId)
    }

    private fun resetReconnectState() {
        reconnectAttempts = 0
        lastReconnectTime = 0L
        pendingReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    fun cancelReconnect() {
        pendingReconnect = false
        reconnectHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Cancelled pending reconnect")
    }

    @Volatile
    private var pendingReregistration = false

    /** When a "Device not found" refusal last started a re-registration (elapsed ms); null: never. */
    @Volatile internal var lastRejectionReregistrationAt: Long? = null

    /** The clock for that cooldown. Tests set it. */
    internal var elapsedNow: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /**
     * Last verifySelfKeySlots network check, epoch ms; 0 = never. In-memory on
     * purpose — a process restart is exactly when re-checking is cheapest and
     * most useful.
     *
     * When the self-key slots were last checked against the record; tests reset it.
     */
    @Volatile
    internal var lastSelfKeySlotCheckMs = 0L

    /**
     * Ensure the personal encryption key for self-tracking exists, syncing with the
     * server so every client shares one key.
     *
     * Never creates a local-only key: if the server sync fails this returns false and
     * self-tracking stays off, because a divergent local key silently breaks every
     * other platform's view of this user's own history.
     */
    suspend fun ensurePersonalKeyExists(): Boolean {
        // A key IN USE, not merely held: after a reset and a restore the slot can hold only
        // the old key, retired for reading old history — `hasCircleKey` counts it — and a
        // gate on "held" would never publish the new one below.
        if (cryptoManager.hasKeyInUse(SELF_CIRCLE_ID)) {
            Log.d(TAG, "Personal key already exists locally, verifying it works...")

            // Verify the existing key actually works (catches corrupted keys).
            //
            // This proves the key is WELL-FORMED and nothing more. Encrypting
            // and decrypting under the same key succeeds for ANY key, so it
            // cannot tell our key from a stranger's — which is why a device
            // running two different self keys in its two slots passes it.
            // `verifySelfKeySlots` below is the check that can fail.
            try {
                val testData = "keycheck"
                val encrypted = cryptoManager.encryptDataV2(SELF_CIRCLE_ID, testData, "key_check")
                val decrypted = cryptoManager.decryptDataV2(SELF_CIRCLE_ID, encrypted, "key_check")
                if (decrypted != testData) {
                    Log.e(TAG, "Existing personal key is corrupted - decryption failed")
                    // Can't fix without clearAllKeys(), so just fail
                    // User should logout/login or clear app data
                    return false
                }
                Log.d(TAG, "Existing personal key verified successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Existing personal key verification failed: ${e.message}")
                Log.e(TAG, "The self key exists but cannot be used. Try logging out and back in.")
                return false
            }

            // Ensure key also exists under nil UUID for SDK's sendLocation: in use
            // there, not just a retired pre-reset key in the slot.
            if (!cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID)) {
                Log.d(TAG, "Migrating self key to also be stored under nil UUID...")
                migratePersonalKeyToNilUuid()
            }
            verifySelfKeySlots()
            return true
        }

        return try {
            Log.d(TAG, "Checking server for existing self key...")
            val response = tracksApi.getSelfKey()
            val body = response.body()

            // A non-2xx is NOT "the server has no self key". Retrofit does
            // not throw on 401/500/503, so without this guard every one of
            // them would fall through to the mint below and OVERWRITE a
            // self key that is protecting live data — destroying every
            // personal location row on the account. The outer catch
            // doesn't cover it; it only sees transport failures. Other
            // clients apply the same rule.
            //
            // Only an authoritative 2xx answer may decide anything.
            // Anything else: mint nothing, report failure, try again
            // later.
            if (!response.isSuccessful || body?.success != true) {
                Log.e(
                    TAG,
                    "Self-key lookup did not answer authoritatively " +
                        "(HTTP ${response.code()}, success=${body?.success}) — " +
                        "NOT creating a key; the server may still hold one"
                )
                return false
            }

            val keyBundle = body.data
            if (keyBundle != null) {
                Log.d(TAG, "Found self key on server, importing...")
                // SECURITY: Only log key metadata in debug builds to prevent sensitive info leakage
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Ephemeral public key length: ${keyBundle.ephemeralPublicKey.length}")
                    Log.d(TAG, "Nonce length: ${keyBundle.encryptedCircleKey.nonce.length}")
                    Log.d(TAG, "Ciphertext length: ${keyBundle.encryptedCircleKey.ciphertext.length}")
                }

                // Import the key from server - SDK expects hex(nonce || ciphertext)
                val encryptedKey = "${keyBundle.encryptedCircleKey.nonce}${keyBundle.encryptedCircleKey.ciphertext}"
                Log.d(TAG, "Combined encrypted key length: ${encryptedKey.length}")

                // SECURITY: Only log keypair comparison in debug builds
                if (BuildConfig.DEBUG) {
                    // The self key was encrypted for the user's sharing public key, so a
                    // local keypair that differs from the server's makes ECDH derive the
                    // wrong shared secret and every decrypt fails.
                    try {
                        val localPubKey = cryptoManager.exportPublicKey()
                        val serverExpectedPubKey = sessionManager.getPublicKey()
                        Log.d(TAG, "==== KEY COMPARISON ====")
                        Log.d(TAG, "Local SDK public key: $localPubKey")
                        Log.d(TAG, "Server expected key:  $serverExpectedPubKey")
                        if (localPubKey == serverExpectedPubKey) {
                            Log.d(TAG, "KEY MATCH - local key matches server's expected key")
                        } else if (serverExpectedPubKey.isNullOrEmpty()) {
                            Log.w(TAG, "Server expected key is empty - cannot verify match")
                        } else {
                            Log.e(TAG, "KEY MISMATCH DETECTED!")
                            Log.e(TAG, "  The SDK has a different keypair than what the server expects.")
                            Log.e(TAG, "  This means the sharing keypair was not imported correctly during login.")
                            Log.e(TAG, "  ECDH will produce wrong shared secret -> decryption will fail.")
                        }
                        Log.d(TAG, "========================")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not verify keypair match: ${e.message}")
                    }
                }

                try {
                    // Our own wrap: sender == recipient == us, with the
                    // signature the server keeps beside it. A signed copy
                    // must verify under OUR signing key — a key the
                    // server made up and sealed to our public key cannot
                    // — and the signature binds the nil UUID, so it goes
                    // with that slot. Unsigned copies (keys minted by app
                    // versions without signing) are still accepted here,
                    // as first contact: this branch only runs with no
                    // personal key held.
                    //
                    // Always labelled ours, whatever signer the server
                    // names: the personal key is ours by definition, and
                    // a copy "signed by" somebody else must fail
                    // verification under our key rather than pass as an
                    // unpinned stranger's first-contact wrap.
                    val selfUserId = sessionManager.getUserId().orEmpty()
                    cryptoManager.storeCircleKey(
                        SELF_CIRCLE_UUID,
                        encryptedKey,
                        keyBundle.ephemeralPublicKey,
                        keyBundle.keyVersion,
                        selfUserId,
                        selfUserId,
                        keyBundle.wrapSignature,
                        null,
                    )
                    // A copy that opens only through an identity from before a
                    // password reset: the SDK keeps that old key retired, for old
                    // history only, and new personal data needs a key this device
                    // made itself. With no personal key in use here there is
                    // nothing to lose: a new one is published over the copy, and
                    // nobody is asked. The server keeps the replaced copy,
                    // retired.
                    if (runCatching { cryptoManager.personalCopySupersededByReset(encryptedKey, keyBundle.ephemeralPublicKey) }.getOrDefault(false)) {
                        Log.i(TAG, "The published personal key opens only through an identity from before a reset; publishing a new one over it")
                        return createAndUploadSelfKey(overReset = true)
                    }
                    // The legacy "self" slot holds the same key: a local copy,
                    // which follows the key's provenance (the SDK's
                    // `copyPersonalKey`).
                    cryptoManager.copyPersonalKey(SELF_CIRCLE_UUID, SELF_CIRCLE_ID)
                    Log.d(TAG, "Self key imported from server successfully (stored under both IDs)")
                    sessionManager.setUnusableSelfKeyCopy(null)

                    try {
                        val testData = "test"
                        val encrypted = cryptoManager.encryptDataV2(SELF_CIRCLE_ID, testData, "key_check")
                        val decrypted = cryptoManager.decryptDataV2(SELF_CIRCLE_ID, encrypted, "key_check")
                        if (decrypted == testData) {
                            Log.d(TAG, "Self key verification PASSED - encryption/decryption works")
                        } else {
                            Log.e(TAG, "Self key verification FAILED - decrypted data doesn't match")
                            return false
                        }
                    } catch (verifyEx: Exception) {
                        Log.e(TAG, "Self key verification FAILED: ${verifyEx.message}", verifyEx)
                        Log.e(TAG, "The self key was imported but cannot be used for encryption. " +
                                "This may indicate a keypair mismatch between web and Android. " +
                                "Try logging out and back in, or clearing app data.")
                        // The bad key stays stored; clearAllKeys() on logout cleans it up.
                        return false
                    }

                    true
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Failed to decrypt self key from server. " +
                            "This usually means the sharing keypair doesn't match between platforms. " +
                            "Error: ${e.message}", e)
                    // Do NOT create a local key - this would break
                    // cross-platform sync.
                    //
                    // But if we ALREADY HOLD one, publishing it is not creating
                    // anything: it restores the key our own personal history is
                    // encrypted under, which is the key the server should be
                    // serving. Reaching here means the stored copy belongs to
                    // some other keypair — typically because another client
                    // minted a replacement when it could not open ours. Nothing
                    // else can undo that, because after the mint the original
                    // survives only in this keystore; left to a person,
                    // personal tracking would stay broken on every other device
                    // until somebody noticed and pressed a button. Heal it here
                    // instead — but only as the holder of the RECORDED key, and
                    // only as the holder's check allows: never a republish of a
                    // key the record does not name.
                    val userId = sessionManager.getUserId().orEmpty()
                    val heldIsRecorded = cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID) &&
                        keyBundle.userPseudonym != null &&
                        runCatching { cryptoManager.deriveUserPseudonym(SELF_CIRCLE_UUID, userId) }.getOrNull() == keyBundle.userPseudonym
                    when {
                        heldIsRecorded -> checkPublishedCopy(keyBundle, userId)
                        // It does not open here: say so, once per distinct copy.
                        // The server wakes the account's other phones; the one
                        // holding the recorded key republishes it for the
                        // account's current keys, and this phone takes it on a
                        // later pass. A copy that is not this account's own
                        // (SignatureInvalid) is refused and never flagged.
                        e is uniffi.pingolin_mobile.MobileException.DecryptionFailed -> reportUnusableCopy(keyBundle, userId, "it does not open here")
                        else -> Log.w(TAG, "Server self key unusable here (${e.javaClass.simpleName}), and this device does not hold the recorded one — left alone")
                    }
                    // Usable afterwards only if this device holds a personal key of its own, in use.
                    cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID)
                }
            } else {
                // Reached ONLY on a 2xx whose envelope says success with no
                // key — the server's own statement that this account has none.
                // createAndUploadSelfKey deliberately does not send
                // replace=true (unlike republishSelfKeyIfHeld): minting must
                // never clobber, so if the server does hold a key after all,
                // the store is refused rather than silently overwriting.
                Log.d(TAG, "No self key on server, creating new one...")
                createAndUploadSelfKey()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync self key with server", e)
            false
        }
    }

    /**
     * Create a new self key and upload to server for cross-device sync. [overReset]: over a
     * published copy that opens only through an identity from before a password reset, the
     * one mint that replaces what the server holds; every other mint stays refusable.
     */
    private suspend fun createAndUploadSelfKey(overReset: Boolean = false): Boolean {
        return try {
            val creationData = cryptoManager.prepareCircleCreation()
            val selfUserId = sessionManager.getUserId().orEmpty()

            // The published copy: wrapped to this account, signed as our own for
            // the nil UUID the server keys it by, with the pseudonym under exactly
            // this key so the server records it with the bytes. The sdk makes all
            // three from the key held under the creation's temp id.
            val publishable = cryptoManager.publishablePersonalKey(creationData.tempCircleId, selfUserId)

            val request = com.pingolin.track.data.remote.dto.StoreSelfKeyRequest(
                encryptedSelfKey = com.pingolin.track.data.remote.dto.EncryptedBlobRequest(
                    nonce = publishable.encryptedKey.nonce,
                    ciphertext = publishable.encryptedKey.ciphertext
                ),
                ephemeralPublicKey = publishable.ephemeralPublicKey,
                wrapSignature = publishable.wrapSignature,
                userPseudonym = publishable.userPseudonym,
                replace = true.takeIf { overReset },
            )

            // Publish FIRST, install only once the server has it. Installing a
            // key the server refused would make the divergence permanent:
            // finalizeCircleCreation persists, every later launch stops at "key
            // already held", and personal locations would go on being sealed
            // under a key no other device could ever obtain. The iOS app
            // publishes first too. Until finalize, prepareCircleCreation's key
            // lives in memory only.
            val uploadResponse = tracksApi.storeSelfKey(request)
            if (!(uploadResponse.isSuccessful && uploadResponse.body()?.success == true)) {
                Log.w(
                    TAG,
                    "Self key not published (HTTP ${uploadResponse.code()}: ${uploadResponse.body()?.error}) " +
                        "— not installing it; the next sync tries again"
                )
                return false
            }
            Log.d(TAG, "Self key uploaded to server successfully")

            cryptoManager.finalizeCircleCreation(creationData.tempCircleId, SELF_CIRCLE_ID)

            // Also under the nil UUID, for the SDK's sendLocation, which looks up by circle id.
            cryptoManager.copyPersonalKey(SELF_CIRCLE_ID, SELF_CIRCLE_UUID)
            Log.d(TAG, "Personal key created and synced successfully (stored under both IDs)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create and upload self key", e)
            false
        }
    }

    /**
     * Confirm both self-key slots hold the key our DATA is encrypted under, and
     * heal them when they don't.
     *
     * The self key lives under `SELF_CIRCLE_ID` ("self") and
     * `SELF_CIRCLE_UUID`, and different readers use different ones —
     * PlaceRepository unwraps personal place keys via "self",
     * sendEnvelopeLocation encrypts via the nil UUID. Unless something keeps
     * them equal they can diverge, and the app would then read places with one
     * key while writing locations with the other.
     *
     * The reference is the account's recorded `user_pseudonym`, served
     * alongside the wrap. The server writes it on the first successful personal
     * row, so it names the key our history is ACTUALLY under, and the pseudonym
     * is derived from the key itself (`derive_user_pseudonym(circle_key,
     * user_id)`). Deriving it locally from each slot and comparing is therefore
     * a check that can FAIL — unlike the encrypt/decrypt round trip, which any
     * key passes.
     *
     * Both slots must agree with it. When one does and the other doesn't, the
     * agreeing key is the truth and gets copied across; when the SERVER's copy
     * is the odd one out, we re-publish ours rather than adopt theirs, because
     * adopting it orphans every personal row ever written.
     *
     * Best-effort and silent on the happy path: this runs on a hot path and a
     * server that cannot tell us the pseudonym (an older server, offline,
     * nothing ever sent) leaves everything exactly as it was.
     */
    private suspend fun verifySelfKeySlots(force: Boolean = false) {
        val userId = sessionManager.getUserId()
        if (userId.isNullOrEmpty()) return

        // ensurePersonalKeyExists sits on the SEND path — sendSelfTrackingLocation
        // calls it for every fix, roughly once a minute — so this cannot fetch
        // per call. Slot divergence is caused by a key being rewritten, not by
        // anything that happens between two location sends, so checking on the
        // first call and then hourly catches it just as fast in practice. A wake
        // that asks for the copy to be put right ([onSilentWake]) forces it.
        val now = System.currentTimeMillis()
        val since = now - lastSelfKeySlotCheckMs
        if (!force && lastSelfKeySlotCheckMs != 0L && since < SELF_KEY_SLOT_CHECK_INTERVAL_MS) return
        lastSelfKeySlotCheckMs = now

        val bundle = try {
            tracksApi.getSelfKey().body()?.data
        } catch (e: Exception) {
            Log.d(TAG, "Self-key slot check skipped: ${e.message}")
            null
        }
        if (bundle == null) return
        val recorded = bundle.userPseudonym
        // No recorded pseudonym means nothing has ever been sent under this key, so
        // there is no history to be wrong about and nothing to compare: only the
        // published copy itself is checked, as the sdk's holder check allows — and if
        // it is this device's key, the record is pointed at it (a compare-and-set).
        if (recorded.isNullOrEmpty()) {
            checkPublishedCopy(bundle, userId, recordIsOurs = false)
            return
        }

        fun pseudonymOf(slot: String): String? = runCatching {
            cryptoManager.deriveUserPseudonym(slot, userId)
        }.getOrNull()

        val selfSlot = pseudonymOf(SELF_CIRCLE_ID)
        val uuidSlot = pseudonymOf(SELF_CIRCLE_UUID)
        if (selfSlot == recorded && uuidSlot == recorded) {
            // This device holds the recorded key: check the published copy.
            checkPublishedCopy(bundle, userId)
            return
        }

        Log.w(
            TAG,
            "Self-key slots disagree with the recorded pseudonym " +
                "(self=${selfSlot == recorded}, uuid=${uuidSlot == recorded}) — healing"
        )

        // Whichever slot matches holds the real key; mirror it into the other.
        val goodSlot = when (recorded) {
            selfSlot -> SELF_CIRCLE_ID
            uuidSlot -> SELF_CIRCLE_UUID
            else -> null
        }
        if (goodSlot == null) {
            // Neither slot is the key the account's record names. The server's copy is
            // taken only through the sdk's adoption, which makes its own checks — among
            // them that the copy is signed by this account's own key — and ADDS: the
            // key held now is retired, not lost.
            adoptOrReport(bundle, userId, recorded)
            return
        }

        val badSlot = if (goodSlot == SELF_CIRCLE_ID) SELF_CIRCLE_UUID else SELF_CIRCLE_ID
        // Both slots then hold the recorded key; the published copy is checked as
        // the holder's — a copy that opens to a different key is left alone: that
        // divergence is repaired by adoption, never by publishing over it.
        if (copySelfKeySlot(from = goodSlot, to = badSlot)) checkPublishedCopy(bundle, userId)
    }

    /**
     * The holder's check: this device holds the key the account's record names, so it
     * looks at the copy the server publishes and puts it right only as the sdk says — sign
     * a copy of our key that carries no valid signature; republish ours, at most hourly,
     * only when the copy can't be opened with the account's keys at all; and leave a copy
     * of ANOTHER key alone. Never for a key taken on trust.
     *
     * When the record does NOT name our key ([recordIsOurs] false: nothing recorded, or a
     * pseudonym mismatch) and the copy opens to our key, the record is set to it by a
     * compare-and-set. [mayRepublish] is false where this device does not hold the
     * recorded key: then it never publishes over the copy.
     */
    private suspend fun checkPublishedCopy(
        bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse,
        userId: String,
        recordIsOurs: Boolean = true,
        mayRepublish: Boolean = true,
    ) {
        val encryptedKey = "${bundle.encryptedCircleKey.nonce}${bundle.encryptedCircleKey.ciphertext}"
        val status = try {
            cryptoManager.checkPublishedPersonalCopy(userId, encryptedKey, bundle.ephemeralPublicKey, bundle.keyVersion, bundle.wrapSignature)
        } catch (e: Exception) {
            Log.w(TAG, "Published personal copy not checked: ${e.message}")
            return
        }
        val takenOnTrust = runCatching { cryptoManager.personalKeyTakenOnTrust() }.getOrDefault(true)
        when (status) {
            // Validly signed by this account and opening to our key: the account's genuine key.
            uniffi.pingolin_mobile.PublishedCopyStatus.OPENS ->
                if (!recordIsOurs) recordOurPseudonym(bundle, userId)
            uniffi.pingolin_mobile.PublishedCopyStatus.UNSIGNED ->
                if (takenOnTrust) {
                    Log.i(TAG, "Published copy unsigned; this key was taken on trust here, so it is not signed from here")
                } else {
                    signServerSelfKey(bundle)
                    if (!recordIsOurs) recordOurPseudonym(bundle, userId)
                }
            uniffi.pingolin_mobile.PublishedCopyStatus.CANNOT_OPEN ->
                when {
                    !mayRepublish -> Log.i(TAG, "Published copy can't be opened here; this device doesn't hold the recorded key, so it publishes nothing")
                    takenOnTrust -> Log.i(TAG, "Published copy can't be opened; this key was taken on trust here, so it is not published from here")
                    else -> republishSelfKeyIfHeld()
                }
            uniffi.pingolin_mobile.PublishedCopyStatus.OPENS_TO_DIFFERENT_KEY ->
                Log.i(TAG, "Another genuine personal key is published; adoption repairs that, not a republish")
            else -> Log.i(TAG, "Published personal copy: $status")
        }
    }

    /**
     * A wake from the server (`republish_requested`: another phone of this account can't use
     * the published copy): run the check now rather than at the next hourly one. The
     * holder's check decides whether anything is signed or published.
     */
    suspend fun onSilentWake() {
        val bundle = runCatching { tracksApi.getSelfKey().body()?.data }.getOrNull() ?: return
        if (bundle.republishRequested) verifySelfKeySlots(force = true)
    }

    /**
     * Adopt the account's published personal copy if the sdk says it may, and otherwise act
     * on why not: CANNOT_OPEN and UNSIGNED raise "this device can't use it" once per distinct
     * copy, so the phone holding the recorded key puts it right; nothing else raises it.
     * NO_SIGNING_KEY is never a prompt from here: this runs in the background.
     */
    private suspend fun adoptOrReport(
        bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse,
        userId: String,
        recorded: String,
    ) {
        val encryptedKey = "${bundle.encryptedCircleKey.nonce}${bundle.encryptedCircleKey.ciphertext}"
        val verdict = try {
            cryptoManager.adoptPersonalKey(userId, encryptedKey, bundle.ephemeralPublicKey, bundle.keyVersion, bundle.wrapSignature, recorded)
        } catch (e: Exception) {
            Log.e(TAG, "Adopting the account's personal key failed: ${e.message}")
            return
        }
        when (verdict) {
            uniffi.pingolin_mobile.PersonalKeyVerdict.ADOPT -> {
                Log.i(TAG, "Adopted the account's published personal key; the one held before is kept, retired")
                sessionManager.setUnusableSelfKeyCopy(null)
                // The adopted key is in use under the nil UUID; "self" follows, and its old
                // key is retired too, not dropped.
                copySelfKeySlot(from = SELF_CIRCLE_UUID, to = SELF_CIRCLE_ID)
            }
            uniffi.pingolin_mobile.PersonalKeyVerdict.CANNOT_OPEN,
            uniffi.pingolin_mobile.PersonalKeyVerdict.UNSIGNED -> reportUnusableCopy(bundle, userId, verdict.name)
            uniffi.pingolin_mobile.PersonalKeyVerdict.NO_SIGNING_KEY ->
                Log.w(TAG, "Personal key not adopted: this account's signing key isn't loaded here")
            // Another device sent last, or the record is stale: never a flag. If the
            // published copy is OUR key, the record is pointed at it; nothing else.
            uniffi.pingolin_mobile.PersonalKeyVerdict.PSEUDONYM_MISMATCH ->
                checkPublishedCopy(bundle, userId, recordIsOurs = false, mayRepublish = false)
            else -> Log.i(TAG, "Personal key not adopted: $verdict")
        }
    }

    /**
     * Point the account's record at the pseudonym under the key the stored copy carries —
     * this device's key in use, which the holder's check just found the copy opens to. A
     * compare-and-set on the copy's exact bytes: a copy replaced meanwhile is left for the
     * next check.
     */
    private suspend fun recordOurPseudonym(
        bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse,
        userId: String,
    ) {
        val ours = runCatching { cryptoManager.deriveUserPseudonym(SELF_CIRCLE_UUID, userId) }.getOrNull() ?: return
        if (ours == bundle.userPseudonym) return
        try {
            val response = tracksApi.recordSelfPseudonym(
                com.pingolin.track.data.remote.dto.RecordSelfPseudonymRequest(
                    keyVersion = bundle.keyVersion,
                    encryptedCircleKey = com.pingolin.track.data.remote.dto.EncryptedBlobRequest(
                        nonce = bundle.encryptedCircleKey.nonce,
                        ciphertext = bundle.encryptedCircleKey.ciphertext,
                    ),
                    ephemeralPublicKey = bundle.ephemeralPublicKey,
                    userPseudonym = ours,
                )
            )
            when {
                response.isSuccessful && response.body()?.data?.recorded == true ->
                    Log.i(TAG, "The account's record now names the published personal key")
                response.code() == 409 -> Log.i(TAG, "The personal copy changed before the record was repaired; the next check looks again")
                else -> Log.w(TAG, "Personal record not repaired: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Personal record not repaired: ${e.message}")
        }
    }

    /**
     * "This device can't use the personal copy it was served": once per distinct copy
     * for this account, kept across restarts, and again only after a copy has opened here
     * (first contact or adoption clears it). 404 means nothing is stored to put right.
     */
    private suspend fun reportUnusableCopy(
        bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse,
        userId: String,
        why: String,
    ) {
        val digest = copyDigest(bundle)
        val copy = "${userId.lowercase()}:$digest"
        if (sessionManager.getUnusableSelfKeyCopy() == copy) return
        try {
            val response = tracksApi.reportSelfKeyUnreadable(
                com.pingolin.track.data.remote.dto.SelfKeyUnreadableRequest(copySha256 = digest)
            )
            when {
                // Not the stored copy any more: nothing was asked; the next check reads it again.
                response.isSuccessful && response.body()?.data?.copyChanged == true ->
                    Log.i(TAG, "Personal copy changed before the report; reading it again later")
                response.isSuccessful -> {
                    sessionManager.setUnusableSelfKeyCopy(copy)
                    Log.w(TAG, "Personal copy unusable here ($why); the account's other phones were asked to put it right")
                }
                response.code() == 404 -> sessionManager.setUnusableSelfKeyCopy(copy)
                else -> Log.w(TAG, "Unusable personal copy not reported: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unusable personal copy not reported: ${e.message}")
        }
    }

    /**
     * A copy's identity for the flag, as the server computes it and iOS keeps it: sha256
     * over the raw bytes ciphertext ‖ nonce ‖ ephemeral public key. A field that isn't hex
     * hashes as its own text, so a malformed copy still has a stable identity.
     */
    private fun copyDigest(bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse): String {
        fun raw(hex: String): ByteArray =
            if (hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            } else {
                hex.toByteArray()
            }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(raw(bundle.encryptedCircleKey.ciphertext))
        digest.update(raw(bundle.encryptedCircleKey.nonce))
        digest.update(raw(bundle.ephemeralPublicKey))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Sign the server's copy of our personal key when it carries no valid signature:
     * personal keys minted by app versions without signing are unsigned, and an unsigned
     * "own" wrap would let a server install a personal key of its choosing on a new
     * device. The SDK signs only a wrap that opens to the key this device already holds,
     * and never one it took on trust, so a server can never get our signature over a key
     * it chose. The signature goes to the nil circle's wrap-signature PUT, which stores it
     * only on the exact bytes we read: re-POSTing those bytes would replace a personal key
     * another device published since. Called by the holder's check ([checkPublishedCopy])
     * when the sdk says UNSIGNED.
     */
    private suspend fun signServerSelfKey(bundle: com.pingolin.track.data.remote.dto.CircleKeyBundleResponse): Boolean {
        val selfUserId = sessionManager.getUserId()?.takeIf { it.isNotEmpty() } ?: return false
        val signature = try {
            cryptoManager.signOwnCircleKeyWrap(
                SELF_CIRCLE_UUID,
                selfUserId,
                bundle.keyVersion,
                "${bundle.encryptedCircleKey.nonce}${bundle.encryptedCircleKey.ciphertext}",
                bundle.ephemeralPublicKey,
            )
        } catch (e: Exception) {
            // Typically: the server's copy is not the key we hold. Never vouch for it.
            Log.w(TAG, "Server self key left unsigned: ${e.message}")
            return false
        }
        try {
            val response = tracksApi.signOwnWrap(
                SELF_CIRCLE_UUID,
                com.pingolin.track.data.remote.dto.OwnWrapSignatureRequest(
                    keyVersion = bundle.keyVersion,
                    encryptedCircleKey = com.pingolin.track.data.remote.dto.EncryptedPayload(
                        nonce = bundle.encryptedCircleKey.nonce,
                        ciphertext = bundle.encryptedCircleKey.ciphertext,
                    ),
                    ephemeralPublicKey = bundle.ephemeralPublicKey,
                    wrapSignature = signature,
                )
            )
            return when {
                response.isSuccessful && response.body()?.data?.signed == true -> {
                    Log.i(TAG, "Signed the server's copy of our personal key")
                    true
                }
                // Replaced since we read it: the next check signs the new copy, if it
                // opens to our key.
                response.code() == 409 -> {
                    Log.i(TAG, "Personal key changed on the server before our signature landed")
                    false
                }
                else -> {
                    Log.w(TAG, "Personal key signature not stored: HTTP ${response.code()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Personal key signature not sent: ${e.message}")
            return false
        }
    }

    /**
     * Put the personal key held under [from] under [to] as well: the sdk's own slot
     * copy (`copyPersonalKey`), which hands no key material out. A wrap round-trip is
     * no substitute: it refuses a key taken on trust.
     */
    private fun copySelfKeySlot(from: String, to: String): Boolean = try {
        // A local copy between the two slots; a different key already in [to] is retired,
        // not dropped, and a key taken on trust stays marked.
        cryptoManager.copyPersonalKey(from, to)
        Log.i(TAG, "Self-key slot '$to' realigned with '$from'")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not realign self-key slot '$to' from '$from': ${e.message}", e)
        false
    }

    /**
     * Automatic half of [republishSelfKey]: heal a diverged server copy
     * without asking anyone, but only when we actually hold the key and only
     * rarely.
     *
     * The cooldown is a loop guard, not politeness. A client that mints over a
     * key it cannot open may replace ours right back, and two healers with no
     * brake would trade writes on every sync. One attempt per
     * [SELF_KEY_REPUBLISH_COOLDOWN_MS] converges quickly once the other side
     * stops and stays harmless while it doesn't.
     *
     * Returns whether the local key is usable afterwards — which it is either
     * way, since publishing never changes what this device holds.
     */
    private suspend fun republishSelfKeyIfHeld(): Boolean {
        val holdsKey = cryptoManager.hasKeyInUse(SELF_CIRCLE_ID) ||
            cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID)
        if (!holdsKey) {
            Log.w(TAG, "Server self key unreadable and this device holds none — cannot heal")
            return false
        }

        val since = System.currentTimeMillis() - sessionManager.getLastSelfKeyRepublishAt()
        if (since < SELF_KEY_REPUBLISH_COOLDOWN_MS) {
            Log.i(TAG, "Server self key unreadable; re-publish skipped (last attempt ${since / 60_000}m ago)")
            return true
        }

        Log.w(TAG, "Server self key unreadable — re-publishing the key this device holds")
        sessionManager.setLastSelfKeyRepublishAt(System.currentTimeMillis())
        republishSelfKey()
            .onSuccess { Log.i(TAG, "Self key re-published") }
            .onFailure { Log.w(TAG, "Self key re-publish failed: ${it.message}") }
        return true
    }

    /**
     * Re-publish the personal key THIS DEVICE already holds, overwriting the server's
     * copy. Recovery for a self key that has diverged across clients.
     *
     * `POST /api/v1/users/me/self-key` is an UPSERT over live E2EE key material and
     * the server validates only lengths, so any client can replace it. A client that
     * cannot open the stored wrap may MINT A FRESH RANDOM KEY over it. This device
     * never does — `syncSelfKey` logs "CRITICAL: Failed to decrypt self key from
     * server" and keeps its own, deliberately, so two platforms can't clobber each
     * other.
     *
     * The consequence is one-directional and silent: after such a mint the phone keeps
     * sealing personal locations under the ORIGINAL key while the server hands every
     * other client the new one. Locations keep flowing and decrypt fine on this
     * device; on a client whose path has no way to say "served, could not decrypt",
     * "Your Location" quietly freezes at the last fix it could open.
     * Nothing else heals it — the original key then exists only in this keystore, so
     * only this device can put it back.
     *
     * The copy comes from the sdk's `publishablePersonalKey`: the key ALREADY HELD
     * (never a new one), wrapped to this account, signed as our own over the nil UUID,
     * with the account's pseudonym under exactly that key for the server to record
     * with it. It refuses a key this device took on trust, so such a key is never
     * published, and it never produces an unsigned copy.
     *
     * Local state is untouched on every path — this only ever republishes. Called only
     * where the holder's check allows it ([checkPublishedCopy]): a copy nobody can
     * open.
     */
    suspend fun republishSelfKey(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val selfUserId = sessionManager.getUserId()
            if (selfUserId.isNullOrEmpty()) {
                return@withContext Result.failure(IllegalStateException("Not signed in"))
            }

            // The key is stored under both ids (see syncSelfKey / createAndUploadSelfKey),
            // but which one is present depends on how it got here — an install that
            // predates migratePersonalKeyToNilUuid has only "self". Ask rather than assume:
            // publishablePersonalKey throws KeyNotFound on the wrong id.
            val keyId = when {
                cryptoManager.hasKeyInUse(SELF_CIRCLE_ID) -> SELF_CIRCLE_ID
                cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID) -> SELF_CIRCLE_UUID
                else -> {
                    Log.w(TAG, "republishSelfKey: no personal key held on this device")
                    return@withContext Result.failure(
                        IllegalStateException("No personal key on this device")
                    )
                }
            }

            // Signed as our own over the nil UUID, never unsigned, with the pseudonym
            // under exactly this key. The sdk refuses a key taken on trust: one this
            // device never had from its own account is never published from here.
            val publishable = cryptoManager.publishablePersonalKey(keyId, selfUserId)

            val response = tracksApi.storeSelfKey(
                com.pingolin.track.data.remote.dto.StoreSelfKeyRequest(
                    encryptedSelfKey = com.pingolin.track.data.remote.dto.EncryptedBlobRequest(
                        nonce = publishable.encryptedKey.nonce,
                        ciphertext = publishable.encryptedKey.ciphertext
                    ),
                    ephemeralPublicKey = publishable.ephemeralPublicKey,
                    // The store is an upsert, and the server may refuse to
                    // overwrite a key its data is still encrypted under.
                    // From the payload alone this call looks exactly like
                    // the mint that guard exists to stop — same blob, same
                    // shape — so say which one it is: the key this device
                    // holds, wrapped unchanged. createAndUploadSelfKey
                    // must NOT send this; being refusable is the whole
                    // point of that path.
                    replace = true,
                    wrapSignature = publishable.wrapSignature,
                    userPseudonym = publishable.userPseudonym,
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                Log.i(TAG, "Personal key re-published to server from this device")
                Result.success(Unit)
            } else {
                Log.w(TAG, "republishSelfKey: server refused: ${response.body()?.error ?: "HTTP " + response.code()}")
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't repair your other devices just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "republishSelfKey failed", e)
            Result.failure(e)
        }
    }

    /**
     * Fill the nil-UUID slot for users who have the key stored only under "self".
     *
     * From the key this device HOLDS, never from the server's copy: storing
     * whatever the server's wrap opens to, unchecked, can leave the two slots
     * holding different keys, and the SDK refuses a server key that differs from
     * the one held in the other slot.
     */
    private fun migratePersonalKeyToNilUuid() {
        if (copySelfKeySlot(from = SELF_CIRCLE_ID, to = SELF_CIRCLE_UUID)) {
            Log.d(TAG, "Successfully migrated self key to nil UUID")
        }
    }

    /** A personal key IN USE here: not just the retired one from before a reset. */
    fun hasPersonalKey(): Boolean = cryptoManager.hasKeyInUse(SELF_CIRCLE_ID)

    /**
     * Ensure this device is registered with the backend; location streaming cannot
     * start until it is.
     *
     * A registration that fails while [pendingReregistration] is set means the device
     * ID belongs to a different user, so a new device ID is generated and retried.
     */
    suspend fun ensureDeviceRegistered(): Boolean {
        // Always ensure personal key exists (handles nil UUID migration for existing users). It
        // gates only a registration with no push route: see [registrationNeedsPersonalKey].
        val withName = ensurePersonalKeyExists()
        if (!withName && registrationNeedsPersonalKey()) {
            Log.e(TAG, "Cannot register device: personal key not available")
            return false
        }

        if (sessionManager.isDeviceRegistered()) {
            Log.d(TAG, "Device already registered")
            return true
        }

        if (registrationWaitsForRoute()) {
            Log.d(TAG, "Device not registered yet: waiting for a push route or the cooldown")
            return false
        }

        when (attemptDeviceRegistration(withName)) {
            Registration.REGISTERED -> {
                pendingReregistration = false
                return true
            }
            // Taken without a push route: nothing to do with whose the device id is.
            Registration.NO_PUSH_ROUTE -> {
                pendingReregistration = false
                return false
            }
            Registration.FAILED -> Unit
        }

        // Registration failed - if this was a re-registration attempt after rejection,
        // the device ID likely belongs to a different user. Generate a new one and retry.
        if (pendingReregistration) {
            Log.w(TAG, "Re-registration with existing device ID failed, generating new device ID")
            sessionManager.clearDeviceId()
            pendingReregistration = false

            return attemptDeviceRegistration(withName) == Registration.REGISTERED
        }

        return false
    }

    /**
     * Whether a registration has to wait for the personal key: only when it carries no push route.
     *
     * The server keeps nothing of a device but its push route (the encrypted name and type are
     * accepted and discarded, empty ones too), and refuses every location send from a device
     * without one. So a registration carrying a token never waits for the key: dropping it
     * whenever the key isn't there yet gets the first sign-in's fixes refused.
     */
    private fun registrationNeedsPersonalKey(): Boolean =
        sessionManager.getPushToken() == null && sessionManager.getPushEndpoint() == null

    /** What a registration came to. */
    private enum class Registration { REGISTERED, NO_PUSH_ROUTE, FAILED }

    /** The push route (token or endpoint, null for none) the server last took a registration without, and when. */
    @Volatile private var noRouteAttempt: Pair<String?, Long>? = null

    private fun currentPushRoute(): String? = sessionManager.getPushToken() ?: sessionManager.getPushEndpoint()

    /**
     * Whether a registration waits: the server took the last one without registering a push route
     * (push_registered false), this phone's route is the same, and the cooldown hasn't passed.
     * Every send asks [ensureDeviceRegistered]; without this a phone the server can't register yet
     * would POST again on every send. A new token or endpoint goes at once.
     */
    private fun registrationWaitsForRoute(): Boolean {
        val (route, at) = noRouteAttempt ?: return false
        return route == currentPushRoute() && !rejectionReregistrationDue(elapsedNow(), at)
    }

    /**
     * The core registration call, split out of [ensureDeviceRegistered] so it can be
     * retried against a freshly generated device ID. [withName]: the personal key is here to
     * encrypt the device's name and type; without it they go empty, which the server takes.
     */
    private suspend fun attemptDeviceRegistration(withName: Boolean): Registration {
        return try {
            val deviceId = sessionManager.getDeviceId()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val deviceType = "android"

            val (encryptedName, encryptedType) = if (withName) {
                val name = cryptoManager.encryptDataV2(SELF_CIRCLE_ID, deviceName, "device_name")
                val type = cryptoManager.encryptDataV2(SELF_CIRCLE_ID, deviceType, "device_type")
                EncryptedBlobRequest(nonce = name.nonce, ciphertext = name.ciphertext) to
                    EncryptedBlobRequest(nonce = type.nonce, ciphertext = type.ciphertext)
            } else {
                EncryptedBlobRequest(nonce = "", ciphertext = "") to EncryptedBlobRequest(nonce = "", ciphertext = "")
            }

            // Capture the push settings ONCE so the "synced" flag below reflects
            // exactly what this request carried — re-reading getPushToken() after
            // the call could observe a different value and falsely mark a
            // token-less registration as synced.
            val pushToken = sessionManager.getPushToken()
            val pushType = sessionManager.getPushType()
            val pushEndpoint = sessionManager.getPushEndpoint()

            val request = CreateDeviceRequest(
                deviceId = deviceId,
                encryptedDeviceName = encryptedName,
                encryptedDeviceType = encryptedType,
                pushToken = pushToken,
                pushType = pushType,
                pushEndpoint = pushEndpoint
            )

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Registering device $deviceId with backend...")
            }
            val response = tracksApi.createDevice(request)

            if (response.isSuccessful && response.body()?.success == true) {
                val returnedDevice = response.body()?.data
                if (returnedDevice != null) {
                    // Server should use the device_id we provided (idempotent registration)
                    if (returnedDevice.id != deviceId) {
                        if (BuildConfig.DEBUG) {
                            Log.e(TAG, "Server returned unexpected device ID: ${returnedDevice.id} (expected: $deviceId)")
                        }
                    }
                    // Registered only when the server keeps a push route for this
                    // device (push_registered): the device exists for the server
                    // only as that route, and location ingest, needs-attention
                    // and check-ins refuse it without one. A token-less POST is
                    // answered 201 and writes nothing, so counting it as
                    // registered would open a stream whose every fix is refused,
                    // and then re-POST token-less (the iOS app reads it the same
                    // way). A server too old to say: the route this request
                    // carried.
                    val registered = returnedDevice.pushRegistered
                        ?: (pushToken != null || pushEndpoint != null)
                    sessionManager.setDeviceRegistered(registered)
                    sessionManager.setPushTokenSynced(registered)
                    if (!registered) {
                        noRouteAttempt = (pushToken ?: pushEndpoint) to elapsedNow()
                        Log.w(TAG, "Device taken without a push route; it registers once one arrives")
                        return Registration.NO_PUSH_ROUTE
                    }
                    noRouteAttempt = null
                    Log.d(TAG, "Device registered successfully (pushToken=${pushToken != null}, endpoint=${pushEndpoint != null})")
                    return Registration.REGISTERED
                }
            }

            val error = response.body()?.error ?: response.errorBody()?.string() ?: "Unknown error"
            Log.e(TAG, "Device registration failed: $error (code: ${response.code()})")
            Registration.FAILED
        } catch (e: Exception) {
            Log.e(TAG, "Device registration exception", e)
            Registration.FAILED
        }
    }

    /**
     * Force device re-registration with the backend, even if the local flag says it's registered.
     *
     * This should be called:
     * - On app startup after an update (to reactivate devices that may have been deactivated
     *   due to push token invalidation while the app wasn't running)
     * - When the FCM push token changes
     *
     * The backend's upsert will set is_active = TRUE, reactivating any deactivated device.
     */
    suspend fun forceDeviceReregistration(): Boolean {
        val withName = ensurePersonalKeyExists()
        if (!withName && registrationNeedsPersonalKey()) {
            Log.e(TAG, "Cannot re-register device: personal key not available")
            return false
        }

        Log.d(TAG, "Forcing device re-registration to ensure is_active=TRUE on server")
        sessionManager.setDeviceRegistered(false)

        return attemptDeviceRegistration(withName) == Registration.REGISTERED
    }

    /**
     * Send location for self-tracking to the server, encrypted with personal key.
     * Used when the user has no circles but wants their location history stored.
     */
    suspend fun sendSelfTrackingLocation(
        location: android.location.Location,
        batteryLevel: Int?
    ): Result<SendOutcome> {
        // A live fix, by the service or the map (NewestOwnFix).
        newestOwnFix.offer(location)
        newestOwnFix.noteSent(location)
        return try {
            // The personal path publishes the self marker too: without it
            // the map draws personal-only users at a stale position
            // indefinitely while their uploads land perfectly. Never a
            // history replay: this path only ever sends a live fix.
            publishSelfPosition(location, isHistoryReplay = false)

            val userId = sessionManager.getUserId()
            if (userId == null) {
                Log.e(TAG, "sendSelfTrackingLocation failed: No user ID")
                return Result.failure(Exception("No user"))
            }

            // Ensure device is registered (also ensures personal key exists)
            if (!ensureDeviceRegistered()) {
                Log.e(TAG, "sendSelfTrackingLocation failed: Device registration failed")
                return Result.failure(Exception("Device registration failed"))
            }

            val deviceId = sessionManager.getDeviceId()

            val sdkLocation = Location(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                timestamp = location.time,
                batteryLevel = batteryLevel?.toUByte(),
                isCharging = false
            )

            val encryptedData = cryptoManager.encryptLocation(SELF_CIRCLE_ID, sdkLocation)

            // userId/deviceId stay EMPTY, as on every other send path: the SDK conveys
            // identity via the deviceId argument to sendLocation and the server derives
            // pseudonyms, so anything encrypted into these fields is discarded.
            val encryptedLocation = EncryptedLocation(
                location = encryptedData,
                timestamp = location.time,
                userId = "",
                deviceId = ""
            )

            ensureStreamTokenFresh()
            val sdkIsStreaming = locationManager?.isStreaming() ?: false
            if (!sdkIsStreaming || locationManager == null) {
                Log.d(TAG, "Starting streaming for self-tracking (SDK streaming: $sdkIsStreaming)")
                startStreaming(deviceId)
            }

            // Post-send re-check detects zombie-channel writes (see the sibling
            // sendEnvelopeLocation path for the full rationale).
            val actuallyStreaming = locationManager?.isStreaming() ?: false
            var needsQueue = !actuallyStreaming || locationManager == null
            if (!needsQueue) {
                try {
                    // Use nil UUID for server - server maps this to personal tracking.
                    // No key id: encryptLocation() above is a plain circle-key
                    // encrypt that names no key, so the reader's circle-key branch
                    // (which tries held versions) is the correct destination.
                    locationManager?.sendLocation(deviceId, SELF_CIRCLE_UUID, encryptedLocation, null)
                    if (locationManager?.isStreaming() != true) {
                        Log.w(TAG, "Stream died around self-tracking send, queueing safety-net copy")
                        needsQueue = true
                    } else {
                        Log.d(TAG, "Self-tracking location sent via gRPC stream")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Self-tracking sendLocation SDK call threw: ${e.message}, queueing")
                    needsQueue = true
                }
            } else {
                Log.w(TAG, "Streaming not available for self-tracking, queuing location")
            }
            if (needsQueue) {
                pendingLocationDao.insert(
                    PendingLocationEntity(
                        circleId = SELF_CIRCLE_ID,
                        encryptedPayload = "${encryptedData.nonce}:${encryptedData.ciphertext}",
                        timestamp = location.time,
                        createdAt = System.currentTimeMillis()
                    )
                )
                try { queueMonitor.onLocationEnqueued() } catch (e: Exception) { /* non-fatal */ }
            }

            val locationEntity = LocationEntity(
                circleId = SELF_CIRCLE_ID,
                userId = userId,
                deviceId = deviceId,
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                batteryLevel = batteryLevel,
                timestamp = location.time,
                isSynced = actuallyStreaming,
                dayBucket = (location.time / MS_PER_DAY).toInt()
            )
            persistAndEvaluate(listOf(locationEntity), fromServer = false)
            Log.d(TAG, "Self-tracking location stored locally, streaming: $actuallyStreaming")
            // Kept for the drain: "waiting to save", never "saved" for what may not have gone.
            Result.success(if (needsQueue) SendOutcome.QUEUED else SendOutcome.SENT)
        } catch (e: Exception) {
            Log.e(TAG, "sendSelfTrackingLocation exception", e)
            Result.failure(e)
        }
    }

    /**
     * Fallback used when a live send failed for any reason. Writes the location to the
     * local history cache AND, critically, enqueues an encrypted copy to the
     * pending-locations outbox so `syncPendingLocations()` will retry it.
     *
     * The outbox insert is what makes the row recoverable: nothing sweeps
     * LocationEntity rows by isSynced (`locationDao.getPendingSync()` has no caller),
     * so a location written here without an outbox copy is never sent, ever.
     */
    suspend fun storeLocalLocation(location: android.location.Location) {
        val userId = sessionManager.getUserId() ?: "self"
        val deviceId = sessionManager.getDeviceId()

        val locationEntity = LocationEntity(
            circleId = SELF_CIRCLE_ID,
            userId = userId,
            deviceId = deviceId,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
            speed = if (location.hasSpeed()) location.speed.toDouble() else null,
            heading = if (location.hasBearing()) location.bearing.toDouble() else null,
            accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            batteryLevel = null,
            timestamp = location.time,
            isSynced = false,
            dayBucket = (location.time / MS_PER_DAY).toInt()
        )
        persistAndEvaluate(listOf(locationEntity), fromServer = false)

        // Queue for retry by LocationSyncWorker. Self-circle encryption is
        // enough to guarantee at least the user's own history survives offline
        // periods; peer-visible retries aren't attempted here because we may
        // not have had the chance to derive session/circle keys for every
        // target. The periodic worker + network-callback flush path will pick
        // these up on reconnect.
        try {
            // Sealed under the personal key in use.
            if (cryptoManager.hasKeyInUse(SELF_CIRCLE_UUID)) {
                val sdkLocation = Location(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else null,
                    speed = if (location.hasSpeed()) location.speed.toDouble() else null,
                    heading = if (location.hasBearing()) location.bearing.toDouble() else null,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                    timestamp = location.time,
                    batteryLevel = null,
                    isCharging = false
                )
                val encryptedData = cryptoManager.encryptLocation(SELF_CIRCLE_UUID, sdkLocation)
                pendingLocationDao.insert(
                    PendingLocationEntity(
                        circleId = SELF_CIRCLE_ID,
                        encryptedPayload = "${encryptedData.nonce}:${encryptedData.ciphertext}",
                        timestamp = location.time,
                        createdAt = System.currentTimeMillis()
                    )
                )
                try { queueMonitor.onLocationEnqueued() } catch (e: Exception) { /* non-fatal */ }
                Log.d(TAG, "Stored local location and queued for retry")
            } else {
                Log.w(TAG, "Stored local location but SELF key missing - not queued for retry")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue local location for retry: ${e.message}")
        }
    }

    suspend fun syncPendingLocations(): Result<Int> {
        return try {
            // Login guard only: the drained EncryptedLocation carries empty
            // userId/deviceId (see below), so no id is needed here.
            //
            // isLoggedIn(), not a null-check on the user id. A forced sign-out
            // KEEPS the stored user id — that is what lets the account
            // recover on the next sign-in — so an id is present while there is
            // no session at all, and reading one would turn this guard
            // into a no-op. See SessionManager.clearCredentialsOnly.
            if (!sessionManager.isLoggedIn()) return Result.success(0)
            val deviceId = sessionManager.getDeviceId()

            val pending = pendingLocationDao.getPending(100)
            if (pending.isEmpty()) return Result.success(0)

            // The queue is capped by age (24h) + size (1000) via
            // PendingLocationDao.cleanup(), deliberately NOT by retry count: at a 15-min
            // period with exponential backoff, a retry-count cap of five is exhausted by
            // roughly an hour offline, and everything queued for the rest of a multi-day
            // outage is then discarded.

            // Make sure we're actually connected to the ingest stream. The
            // worker often runs right after a reconnect or service restart,
            // when the stream is still closed — without this, every pending
            // item would be silently skipped and the queue would never drain.
            // Catching Throwable here specifically because native LocationManager
            // init can raise UnsatisfiedLinkError / LinkageError (subclasses of
            // Error, not Exception) in degraded environments; we'd rather fall
            // back to "stream unavailable" than crash the worker.
            ensureStreamTokenFresh()
            val sdkStreaming = try { locationManager?.isStreaming() ?: false } catch (t: Throwable) { false }
            if (!sdkStreaming || locationManager == null) {
                Log.i(TAG, "syncPendingLocations: stream down, starting before flush (queued=${pending.size})")
                try {
                    startStreaming(deviceId)
                } catch (t: Throwable) {
                    Log.w(TAG, "syncPendingLocations: startStreaming failed: ${t.message}")
                }
            }
            val canSend = try {
                (locationManager?.isStreaming() ?: false) && locationManager != null
            } catch (t: Throwable) {
                false
            }
            if (!canSend) {
                Log.w(TAG, "syncPendingLocations: stream unavailable, will retry on next tick (queued=${pending.size})")
                return Result.success(0)
            }
            // Back online: what the record of pauses can't place yet is placed first.
            rereadPrivacyBefore(pending.map { it.circleId to it.timestamp })

            var syncedCount = 0
            // Drain-time privacy re-check memo. A circle PAUSED / realtime-off AFTER a fix
            // was queued must not receive its pre-pause backlog on reconnect (this queue can
            // hold up to ~24h of fixes). The payloads are already sealed, so we cannot
            // re-coarsen — we HOLD (leave queued) any row bound for a currently
            // paused / realtime-off / unknown-posture circle; it re-evaluates on the next
            // drain tick and ages out via PendingLocationDao.cleanup() if the pause persists.
            // Mirrors the CheckInRepository send-time gate (encryptCheckInLocation) and reuses
            // BoundaryRepository.mayEmitPresenceTo (self always emits; unknown fails CLOSED).
            // Memoized per circle so we prime/read a posture once per drain, not once per row.
            val presenceOkByCircle = HashMap<String, Boolean>()
            for (pendingLocation in pending) {
                try {
                    val circleId = pendingLocation.circleId
                    // Taken during a pause of its circle: it never goes there, and is
                    // dropped, as the server drops one that arrives during a pause. One the
                    // record can't place yet (unread, or no privacy re-sync since it was
                    // taken) waits for a later drain. Personal history (self) is never held
                    // back.
                    when (pauseGate(circleId, pendingLocation.timestamp, waits = true)) {
                        PauseGate.SKIP -> {
                            Log.d(TAG, "syncPendingLocations: id=${pendingLocation.id} was taken during a pause of $circleId — dropped")
                            pendingLocationDao.deleteById(pendingLocation.id)
                            continue
                        }
                        PauseGate.HOLD -> continue
                        PauseGate.SEND -> Unit
                    }
                    // Sealed when it was queued: one sealed finer than the bubble now, or
                    // than one in force when it was taken, can't be coarsened now, and is
                    // dropped.
                    when (sealedBubbleGate(circleId, pendingLocation.timestamp, pendingLocation.createdAt)) {
                        PauseGate.SKIP -> {
                            Log.d(TAG, "syncPendingLocations: id=${pendingLocation.id} was sealed finer than its bubble then — dropped")
                            pendingLocationDao.deleteById(pendingLocation.id)
                            continue
                        }
                        PauseGate.HOLD -> continue
                        PauseGate.SEND -> Unit
                    }
                    // Posture gate (see memo above): hold rows for a now-paused / realtime-off
                    // / unknown circle instead of replaying stale pre-pause fixes.
                    val mayEmit: Boolean = presenceOkByCircle[circleId] ?: run {
                        val ok = try {
                            boundaryRepository.get().mayEmitPresenceTo(circleId)
                        } catch (t: Throwable) {
                            false // unknown / unprimed posture -> fail closed, hold the row
                        }
                        presenceOkByCircle[circleId] = ok
                        ok
                    }
                    if (!mayEmit) {
                        Log.d(TAG, "syncPendingLocations: circle $circleId paused/realtime-off/unknown — holding queued id=${pendingLocation.id}")
                        continue
                    }
                    val parts = pendingLocation.encryptedPayload.split(":")
                    if (parts.size != 2) {
                        // Malformed payload — drop it rather than looping forever
                        pendingLocationDao.deleteById(pendingLocation.id)
                        continue
                    }
                    val encryptedData = uniffi.pingolin_mobile.EncryptedData(
                        nonce = parts[0],
                        ciphertext = parts[1]
                    )
                    // userId/deviceId left EMPTY to match the live send paths: the SDK
                    // builds the outgoing proto from server-derived pseudonyms plus
                    // encrypted_location.location only, and drops these fields.
                    val encryptedLocation = EncryptedLocation(
                        location = encryptedData,
                        timestamp = pendingLocation.timestamp,
                        userId = "",
                        deviceId = ""
                    )
                    val serverCircleId = if (pendingLocation.circleId == SELF_CIRCLE_ID) {
                        SELF_CIRCLE_UUID
                    } else {
                        pendingLocation.circleId
                    }
                    // Envelope rows carry a per-target wrap of the random msg_key the
                    // payload was sealed under; they MUST be replayed via the envelope
                    // path or the payload can never be unwrapped. Bare rows drain via
                    // sendLocation and must carry their `keyId` with them: a session-key
                    // row does NOT decrypt under the circle key, so replaying one without
                    // its id strands it permanently. A null keyId means the row was sealed
                    // under the circle key itself and needs none.
                    val wrappedKey = pendingLocation.wrappedKey
                    val wrappedKeyNonce = pendingLocation.wrappedKeyNonce
                    if (wrappedKey != null && wrappedKeyNonce != null) {
                        locationManager?.sendEnvelopeLocation(
                            deviceId, serverCircleId, encryptedLocation, wrappedKey, wrappedKeyNonce
                        )
                    } else {
                        locationManager?.sendLocation(
                            deviceId, serverCircleId, encryptedLocation, pendingLocation.keyId
                        )
                    }
                    // Stream-health post-check. If the stream died under us mid-flush,
                    // don't delete the pending row — let the next sync tick retry it,
                    // rather than losing it to a zombie channel that accepted the write
                    // and dropped it.
                    if (locationManager?.isStreaming() != true) {
                        Log.w(TAG, "syncPendingLocations: stream dropped around send for id=${pendingLocation.id}, retrying on next tick")
                        pendingLocationDao.incrementRetry(pendingLocation.id)
                    } else {
                        pendingLocationDao.deleteById(pendingLocation.id)
                        syncedCount++
                    }
                } catch (e: Exception) {
                    pendingLocationDao.incrementRetry(pendingLocation.id)
                    Log.w(TAG, "syncPendingLocations: send failed for id=${pendingLocation.id}, retry=${pendingLocation.retryCount + 1}: ${e.message}")
                }
            }

            if (syncedCount > 0) {
                Log.i(TAG, "syncPendingLocations: flushed $syncedCount of ${pending.size} pending locations")
            }
            Result.success(syncedCount)
        } catch (e: Exception) {
            Log.e(TAG, "syncPendingLocations failed", e)
            Result.failure(e)
        }
    }

    /**
     * Replay / reorder guard for decrypted MEMBER locations.
     *
     * Drops any location a malicious/compromised server may have REPLAYED or
     * REORDERED: one whose AUTHENTICATED (in-AEAD) timestamp is <= the newest we
     * have already accepted for the same (circle, user, device). First-seen members
     * are accepted and recorded. Surviving locations are returned in input order.
     *
     * Only the authenticated `decrypted.timestamp` (carried on LocationEntity.timestamp)
     * is ever compared — never the server-supplied DTO timestamp — so the server can't
     * lie its way past the guard. Self is device-local (see [selfLocation]) and never
     * server-decrypted, so the self circle is skipped and left untouched.
     */
    private fun filterReplayedMemberLocations(
        circleId: String,
        locations: List<LocationEntity>
    ): List<LocationEntity> {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return locations
        val perMember = lastAcceptedMemberTs.getOrPut(circleId) {
            java.util.concurrent.ConcurrentHashMap<String, Long>()
        }
        val accepted = ArrayList<LocationEntity>(locations.size)
        val now = System.currentTimeMillis()
        var beyondClock = 0
        for (loc in locations) {
            val memberKey = "${loc.userId}:${loc.deviceId}"
            val newTs = loc.timestamp
            // A time further ahead than a clock may be off is no fix of now: never taken, and
            // never the guard's, or it would drop every genuine fix after it.
            if (!com.pingolin.track.util.LiveTime.plausible(newTs, now)) {
                beyondClock++
                continue
            }
            // compute() runs the remap atomically per key, so concurrent refreshes
            // (e.g. several circles in parallel) can't race the read-decide-write.
            // `advanced` is set inside and read right after — safe because compute
            // is synchronous.
            var advanced = false
            perMember.compute(memberKey) { _, lastTs ->
                // One kept from beyond the clock reads as none.
                val last = lastTs?.takeIf { com.pingolin.track.util.LiveTime.plausible(it, now) }
                if (last == null || newTs > last) {
                    advanced = true
                    newTs
                } else {
                    lastTs // keep the newer timestamp we already accepted
                }
            }
            if (advanced) {
                accepted.add(loc)
            } else {
                Log.d(TAG, "Dropping replayed/reordered location for member $memberKey " +
                        "in circle $circleId (authenticated ts=$newTs not newer than last accepted)")
            }
        }
        // A count only: no member, no circle.
        if (beyondClock > 0) Log.w(TAG, "$beyondClock location(s) timed beyond this phone's clock dropped")
        return accepted
    }

    suspend fun refreshLatestLocations(circleId: String): Result<LocationFetchResult> {
        return try {
            // Check if we have the circle key before attempting to fetch/decrypt.
            // TYPED deliberately: this returns before any network call, and the
            // map treats every untyped failure here as a connectivity problem.
            // An untyped Exception would make a keyless circle render as
            // "Couldn't reach server" on a device with a healthy connection.
            if (!cryptoManager.hasCircleKey(circleId)) {
                return Result.failure(CircleKeyUnavailableException(circleId))
            }

            val response = tracksApi.getLatestLocations(circleId)

            // Handle 404 — CONFIRM before destroying local state.
            //
            // A 404 here is ambiguous: "you were removed from this circle" and
            // "that pod is mid-restart" look identical at this call site, and a
            // rolling deploy produces the second routinely. Deleting the circle on a
            // single 404 therefore stops the device publishing location to a circle it
            // is still in AND tells the user they were removed — neither recoverable
            // until they happen to open the Circles screen.
            //
            // GET /circles is the one endpoint that actually knows, so ask it. Only
            // a definitive "you are not in that list" deletes; an inconclusive answer
            // (network down, list fetch failed) leaves local state alone and simply
            // fails this refresh, which the next tick retries.
            if (response.code() == 404) {
                val stillAMember = try {
                    circleRepository.get().isStillAMember(circleId)
                } catch (e: Exception) {
                    null
                }
                if (stillAMember != false) {
                    Log.w(
                        TAG,
                        "Circle $circleId returned 404 but the circle list " +
                            (if (stillAMember == true) "still contains it" else "could not be checked") +
                            " — treating as transient, keeping local state"
                    )
                    return Result.failure(Exception("Locations unavailable for $circleId"))
                }
                // Announce the removal ONCE, on the transition.
                //
                // The circle ids refreshed here come from cached ALERT rows
                // (LocationTrackingService / AlertsCheckWorker map over alert.circleId),
                // not from the circle table — so an alert left pointing at a deleted circle
                // drives this branch on every pass. Deleting the circle row does not stop
                // it: the row is already gone, `getById` returns null, and announcing on
                // every pass would tell the user "Removed from Unknown circle" once every
                // alert-check cycle.
                // What was kept for it goes, whether or not the row was still here: who
                // shared approximately there, its kept points, what this phone last sent
                // there and its privacy row, as on every other way a circle is forgotten.
                forgetCircleLocations(circleId)
                val existing = try { circleDao.getById(circleId) } catch (_: Exception) { null }
                if (existing != null) {
                    Log.w(TAG, "Circle $circleId not found (404) and absent from the circle list, removing from local DB")
                    circleDao.deleteById(circleId)
                    uxEventBus.emit(
                        com.pingolin.track.util.UxEvent.CircleGone(
                            circleName = existing.name ?: "Unknown circle"
                        )
                    )
                } else {
                    Log.d(TAG, "Circle $circleId already absent locally — not re-announcing")
                }
                return Result.failure(CircleNotFoundException(circleId))
            }

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val wrapper = response.body()!!.data!!
                val dtos = wrapper.locations
                Log.d(TAG, "refreshLatestLocations: circle=$circleId, received ${dtos.size} locations from API")
                // Run the synchronous JNI decrypt + JSON parse off the main
                // thread — callers observe on viewModelScope (Main.immediate).
                val results = withContext(Dispatchers.Default) {
                    buildList {
                        for (dto in dtos) {
                            add(decryptLocationResponseWithResult(circleId, dto))
                        }
                    }
                }

                val locations = results.filterIsInstance<DecryptionResult.Success>()
                    .map { it.location }
                // The frozen rows, opened as any row is, and kept apart from [locations]:
                // see [frozenLocations].
                val frozenDtos = wrapper.lastKnown.orEmpty()
                val frozenResults = withContext(Dispatchers.Default) {
                    frozenDtos.map { decryptLocationResponseWithResult(circleId, it) }
                }
                // How each member shares here, as the server said beside their fix: an
                // approximate member is drawn as an area. From every row this device
                // opened — the mode is the server's word about the member, not about that
                // one point. This account's own rows are never among them (not received).
                // A frozen row is drawn by the same rules.
                if (circleId != SELF_CIRCLE_ID && circleId != SELF_CIRCLE_UUID) {
                    approximateMembers.record(
                        circleId,
                        (dtos.zip(results) + frozenDtos.zip(frozenResults)).mapNotNull { (dto, result) ->
                            (result as? DecryptionResult.Success)?.let {
                                com.pingolin.track.util.ApproximateMembersStore.Report(
                                    it.location.userId, dto.sharingMode, dto.approximateRadiusMiles
                                )
                            }
                        },
                    )
                }
                val keyMissing = results.filterIsInstance<DecryptionResult.KeyMissing>().size
                val failures = results.filterIsInstance<DecryptionResult.DecryptionFailed>().size
                // A key (or key version) this device lacks: ask the next
                // circles refresh to re-import keys, which it does not do on
                // every call.
                if (keyMissing > 0 || failures > 0 ||
                    frozenResults.any { it is DecryptionResult.KeyMissing || it is DecryptionResult.DecryptionFailed }
                ) {
                    runCatching { circleRepository.get().requestKeyReimport() }
                }

                // Replay/reorder guard: drop any member location the server may have
                // replayed or reordered (authenticated ts <= the newest already accepted
                // for that member) BEFORE it can reach the DB cache or the UI fast-path,
                // so a stale envelope neither rewinds a marker nor pollutes local history.
                // `locations` (the full decrypted set) still drives LocationFetchResult
                // below, so a replay-drop is never miscounted as a decryption failure.
                val hidden = runCatching { circleRepository.get().membersHiddenIn(circleId) }.getOrNull().orEmpty()
                // Only a CURRENT row is live: a row only an older key opened is stored
                // for history, and is never a marker, never alert input, and never the
                // guard's.
                val (live, history) = locations.partition { it.keyCurrent }
                val newer = filterReplayedMemberLocations(circleId, live)
                val displayLocations = newer
                    // Somebody this device does not show in the circle is not
                    // shown on the map either.
                    .filter { it.userId !in hidden }
                // A served "no" outlives the drop. The server serves a member's last fix
                // again with their "Allow alerts on me" as it is now, and turning it off
                // sends no fix, so the guard would drop the row that says so and the alert
                // passes would go on judging the quiet member on the old "yes". One way
                // only: a "no" lowers the row the circle holds; a "yes", or no word, from a
                // row the guard dropped raises nothing, as only the member's next fix can.
                for (dropped in live) {
                    if (dropped.allowAlertsOnMe != false || dropped.userId in hidden) continue
                    if (newer.any { it === dropped }) continue
                    try {
                        locationDao.lowerAlertsConsent(circleId, dropped.userId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "A served no to alerts not kept: ${e.javaClass.simpleName}")
                    }
                }
                val historyKept = history.filter { it.userId !in hidden }
                // Each read replaces the circle's frozen rows: a member's newest one, opened
                // under the CURRENT key only, and never someone this device doesn't show.
                val opened = frozenResults.filterIsInstance<DecryptionResult.Success>()
                    .map { it.location }
                    .filter { it.userId !in hidden }
                val currentFrozen = opened
                    .filter { it.keyCurrent }
                    .groupBy { it.userId }
                    .mapNotNull { (_, rows) -> rows.maxByOrNull { it.timestamp } }
                if (circleId != SELF_CIRCLE_ID && circleId != SELF_CIRCLE_UUID) {
                    // Across a rotation, one already drawn stays drawn a while, until its
                    // member's phone re-seals it under the new key (FrozenGrace).
                    val carried = frozenGrace.carried(
                        circleId,
                        active = runCatching { cryptoManager.getActiveVersion(circleId) }.getOrNull(),
                        now = System.currentTimeMillis(),
                        previous = _frozenLocations.value[circleId].orEmpty(),
                        current = currentFrozen,
                        staleServed = opened.filterNot { it.keyCurrent }.mapTo(HashSet()) { it.userId },
                    )
                    val frozen = currentFrozen + carried
                    _frozenLocations.update { if (frozen.isEmpty()) it - circleId else it + (circleId to frozen) }
                }
                // The latest endpoint never returns a late point; one that
                // came anyway would not be judged for alerts either.
                val late = dtos.zip(results).mapNotNullTo(HashSet()) { (dto, result) ->
                    (result as? DecryptionResult.Success)?.location?.takeIf { dto.historyOnly }
                }

                if (historyKept.isNotEmpty()) persistAndEvaluate(historyKept, forAlerts = emptyList(), fromServer = true)
                if (displayLocations.isNotEmpty()) {
                    persistAndEvaluate(displayLocations, displayLocations.filterNot { it in late }, fromServer = true)
                    // Cold-start fast-path: publish to the UI directly so it
                    // needn't wait on the slow-to-first-emit Room observe-Flow.
                    // Merge into the per-user accumulator (newest wins per user)
                    // and emit the union, so a later refresh that decrypts only
                    // a subset doesn't drop a member decrypted on an earlier one.
                    val perUser = freshByUser.getOrPut(circleId) {
                        java.util.concurrent.ConcurrentHashMap<String, LocationEntity>()
                    }
                    for (loc in displayLocations) {
                        val prev = perUser[loc.userId]
                        if (prev == null || loc.timestamp >= prev.timestamp) {
                            perUser[loc.userId] = loc
                        }
                    }
                    _freshLocations.tryEmit(circleId to perUser.values.toList())
                }
                // A member hiding their history keeps only this newest position here.
                purgeHiddenHistory(circleId)

                val fetchResult = LocationFetchResult(
                    locations = locations,
                    // Rows naming this account are not received, so never "all
                    // failed".
                    totalReceived = dtos.size - results.count { it == DecryptionResult.NamedMe },
                    decryptionFailures = failures,
                    keyMissingCount = keyMissing
                )

                if (fetchResult.hasPartialSuccess || fetchResult.allFailed) {
                    Log.w(TAG, "Decryption issues: ${fetchResult.successCount}/${fetchResult.totalReceived} succeeded, " +
                            "${fetchResult.keyMissingCount} key missing, ${fetchResult.decryptionFailures} failed")
                }

                Result.success(fetchResult)
            } else {
                Log.w(TAG, "refreshLatestLocations: API returned unsuccessful response for $circleId")
                Result.failure(Exception("Failed to fetch locations"))
            }
        } catch (e: Exception) {
            // The poll-failure path behind the map's "Couldn't reach server — showing
            // last known" pill (MapViewModel._lastRefreshFailedAt). The exception class
            // is the only diagnosis a transient outage leaves behind:
            //   SocketTimeoutException  -> slow/dropped path
            //   UnknownHostException    -> DNS failure (host not resolving)
            //   ConnectException        -> connection refused/unreachable
            //   SSLException            -> TLS/pinning problem
            //   HttpException (5xx)     -> server-side error
            // Drop this log and the only remaining symptom is a gap in the "received N
            // locations from API" cadence, which rotates out of logcat within minutes.
            Log.w(TAG, "refreshLatestLocations failed for $circleId: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    /** Exception thrown when a circle no longer exists on the server */
    class CircleNotFoundException(val circleId: String) : Exception("Circle not found: $circleId")

    /**
     * This device holds no key for the circle, so there is nothing to fetch or
     * decrypt. Raised BEFORE any network call.
     *
     * It exists to keep the map from blaming the network for a local key problem.
     * MapViewModel sets its network-failure flag for untyped failures out of
     * [refreshLatestLocations], so a missing key raised as a plain Exception renders
     * as "Couldn't reach server — showing last known" on a device whose connection is
     * fine: wrong cause, and wrong remedy — the user needs to refresh circles or
     * rejoin, not to go find signal.
     */
    class CircleKeyUnavailableException(val circleId: String) :
        Exception("Awaiting sync for circle settings from another member. Locations will be queued until synced.")

    // ===== Locations for a circle this device cannot share with yet =====

    private val queueScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val queueFlush = kotlinx.coroutines.sync.Mutex()

    /** Kept points sent in the last minute, for [sendKeptBefore]. Used under [queueFlush]. */
    private val keptPace = KeptPointPace(KEPT_POINTS_PER_MINUTE)

    /**
     * Keep [location] for [circleId] until this device can share with the circle again:
     * its key moved on without this device, or it cannot open the circle at all. The
     * circle's privacy decides at capture, exactly as a live send would: paused,
     * realtime-off or an unknown posture keeps nothing; "approximate" keeps the coarsened
     * point and the stripped motion fields, never the exact ones. 48 hours at most.
     */
    private suspend fun queueForCircle(circleId: String, location: android.location.Location, batteryLevel: Int?) {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return
        try {
            if (!circleRepository.get().ensurePrivacyPrimed(circleId)) return
            val privacy = privacySettingDao.get(circleId) ?: return
            if (privacy.isSharingPausedAt() || privacy.shareRealtime == false) return
            val radius = coarsenRadiusMetersFor(circleId, location.time)
            val (lat, lng) = coarsenLocation(location.latitude, location.longitude, radius)
            val motion = motionFieldsFor(location, radius)
            val now = System.currentTimeMillis()
            queuedCircleLocationDao.insert(
                com.pingolin.track.data.local.entity.QueuedCircleLocationEntity(
                    circleId = circleId,
                    latitude = lat,
                    longitude = lng,
                    altitude = motion.altitude,
                    speed = motion.speed,
                    heading = motion.heading,
                    accuracy = motion.accuracy,
                    batteryLevel = if (privacy.sendsBatteryLevel()) batteryLevel else null,
                    timestamp = location.time,
                    createdAt = now,
                )
            )
            queuedCircleLocationDao.dropOlderThan(now - com.pingolin.track.data.local.dao.QueuedCircleLocationDao.MAX_AGE_MS)
            val over = queuedCircleLocationDao.count(circleId) - com.pingolin.track.data.local.dao.QueuedCircleLocationDao.MAX_PER_CIRCLE
            if (over > 0) queuedCircleLocationDao.dropOldest(circleId, over)
        } catch (e: Exception) {
            Log.w(TAG, "queueForCircle: nothing kept for $circleId: ${e.message}")
        }
    }

    /**
     * Send what was kept for [circleIds] — those this device can share with again — as
     * HISTORY (no live update, no alerts), oldest first. Called just before
     * a live point or liveness check-in goes to them, never after one: on a server that
     * treats history as live, the point that lands last becomes the member's position.
     * Stops at the first point that does not go out and keeps the rest for next time.
     */
    private suspend fun sendKeptBefore(circleIds: Collection<String>) {
        if (circleIds.isEmpty()) return
        // Never in the way of the live point itself: a failure here only leaves them kept.
        try {
            val waiting = queuedCircleLocationDao.circleIds().toSet()
            val due = circleIds.filter { it in waiting }.toSet()
            // Paced (keptPace): a long backlog goes out a share at a time, over the next
            // live points, and never takes the whole of the server's per-user limit.
            if (due.isNotEmpty()) flushQueued(only = due, pace = keptPace) { circleId, row -> sendKept(circleId, row) }
        } catch (e: Exception) {
            Log.w(TAG, "sendKeptBefore: kept locations stay kept: ${e.message}")
        }
    }

    private suspend fun sendKept(circleId: String, row: com.pingolin.track.data.local.entity.QueuedCircleLocationEntity): Boolean {
        val location = android.location.Location("queued").apply {
            latitude = row.latitude
            longitude = row.longitude
            row.altitude?.let { altitude = it }
            row.speed?.let { speed = it.toFloat() }
            row.heading?.let { bearing = it.toFloat() }
            row.accuracy?.let { accuracy = it.toFloat() }
            time = row.timestamp
        }
        return sendEnvelope(
            listOf(circleId), location, row.batteryLevel,
            isHistoryReplay = true, livenessOnly = false, historyOnly = true,
        ).isSuccess
    }

    /**
     * The kept points of [only] (every circle when null), with the send passed in, so the
     * queue's rules are tested alone.
     */
    internal suspend fun flushQueued(
        only: Set<String>? = null,
        /** How many may go out now; the rest wait for a later flush. Unpaced when null. */
        pace: KeptPointPace? = null,
        send: suspend (String, com.pingolin.track.data.local.entity.QueuedCircleLocationEntity) -> Boolean,
    ) {
        if (!queueFlush.tryLock()) return
        try {
            queuedCircleLocationDao.dropOlderThan(
                System.currentTimeMillis() - com.pingolin.track.data.local.dao.QueuedCircleLocationDao.MAX_AGE_MS
            )
            // Read under the lock, so two live sends in a row cannot both spend one allowance.
            var left = pace?.allowance() ?: Int.MAX_VALUE
            for (circleId in queuedCircleLocationDao.circleIds()) {
                if (only != null && circleId !in only) continue
                if (!currentKeyGate.holdsCurrentKey(circleId)) continue
                while (left > 0) {
                    val batch = queuedCircleLocationDao.oldest(circleId, minOf(QUEUE_FLUSH_BATCH, left))
                    if (batch.isEmpty()) break
                    // Kept while this phone was offline too: placed first.
                    rereadPrivacyBefore(batch.map { circleId to it.timestamp })
                    for (row in batch) {
                        if (!send(circleId, row)) {
                            Log.w(TAG, "flushQueued: a kept location for $circleId did not go out — trying again later")
                            return
                        }
                        queuedCircleLocationDao.deleteById(row.id)
                        pace?.sent()
                        left--
                    }
                    Log.i(TAG, "flushQueued: sent ${batch.size} kept location(s) to $circleId as history")
                }
                if (left <= 0) return
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushQueued: ${e.message}")
        } finally {
            queueFlush.unlock()
        }
    }

    /** The key version and device pseudonym a seal for a circle goes under. */
    private class SealedUnder(val keyVersion: Int, val devicePseudonym: String)

    /**
     * [SealedUnder] for [circleId], read just BEFORE the seal: a rotation landing between seal and
     * record then names an older version than the bytes went under, and a re-seal is tried and
     * refused (harmless), never a newer one, which would skip that rotation's re-seal. Null when it
     * can't be read: then nothing is recorded.
     */
    private fun sealedUnder(circleId: String, deviceId: String): SealedUnder? = runCatching {
        SealedUnder(cryptoManager.getActiveVersion(circleId), cryptoManager.deriveDevicePseudonym(circleId, deviceId))
    }.getOrNull()

    /**
     * What this phone just sealed and handed the stream for [circleId], as it went: the record a
     * re-seal after a rotation is built from, never the row the server serves back, with how coarse
     * the position was sealed. Only a send that leaves the circle's latest row comes here. Never fails
     * the send it follows.
     */
    private fun noteLastSent(
        circleId: String,
        sealedUnder: SealedUnder?,
        sealed: uniffi.pingolin_mobile.EncryptedData,
        keyId: String?,
        wrappedKey: String?,
        wrappedKeyNonce: String?,
        point: Location,
        coarsenRadiusMeters: Double,
    ) {
        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return
        if (sealedUnder == null) return
        runCatching {
            circleRepository.get().ownLastSent.put(
                circleId,
                com.pingolin.track.location.OwnLastSent(
                    devicePseudonym = sealedUnder.devicePseudonym,
                    nonce = sealed.nonce,
                    ciphertext = sealed.ciphertext,
                    keyId = keyId,
                    wrappedKey = wrappedKey,
                    wrappedKeyNonce = wrappedKeyNonce,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    accuracy = point.accuracy,
                    timestampMs = point.timestamp,
                    keyVersion = sealedUnder.keyVersion,
                    coarsenRadiusMeters = coarsenRadiusMeters,
                ),
            )
        }.onFailure { Log.w(TAG, "the last send to $circleId isn't recorded: ${it.javaClass.simpleName}") }
    }

    /**
     * What this repository keeps for a circle goes with it when this phone forgets the
     * circle (left, deleted, or gone): its kept locations, who shared approximately there,
     * and this account's privacy row there.
     */
    suspend fun forgetCircleLocations(circleId: String) {
        runCatching { queuedCircleLocationDao.deleteCircle(circleId) }
        approximateMembers.forgetCircle(circleId)
        _frozenLocations.update { it - circleId }
        frozenGrace.forget(circleId)
        // What this phone last sent there, kept for a re-seal: it holds coordinates.
        runCatching { circleRepository.get().ownLastSent.remove(circleId) }
        // The posture every send gate reads. Kept, a rejoin sends under it until the list's posture
        // is stored again, since a row present is taken as primed; gone, the first send asks the
        // server ([CircleRepository.ensurePrivacyPrimed]) and holds back while it can't.
        runCatching { privacySettingDao.delete(circleId) }
    }

    /**
     * Fetch location history over the /locations/history endpoint, which is addressed
     * by day bucket rather than by timestamp.
     *
     * The requested range is converted to day buckets and always read from the
     * server: a viewer's history comes only from the server's history read, which
     * leaves out a member hiding theirs. What it returns is still cached (the newest
     * row per member is the map's), and the cache is never read back as history.
     *
     * ONE call is the whole answer. There is no page size and no cursor: the server
     * downsamples the requested window to a fixed point budget so that the result
     * spans the full range (see
     * [com.pingolin.track.data.remote.dto.LocationHistoryResponse]). To see more
     * detail, narrow the range — there is nothing further to fetch for a given one.
     *
     * [notifyRangeClamping] gates the user-facing "your plan capped this range"
     * notice. Leave it on for a range the user picked; turn it off for a range this
     * code chose on its own, where the notice would blame the user for a request they
     * never made.
     */
    suspend fun fetchLocationHistory(
        circleId: String,
        userId: String? = null,
        startTime: String? = null,
        endTime: String? = null,
        notifyRangeClamping: Boolean = true
    ): Result<LocationHistoryResult> {
        return try {
            if (!cryptoManager.hasCircleKey(circleId)) {
                return Result.failure(CircleKeyUnavailableException(circleId))
            }

            val startMs = startTime?.let { parseTimestamp(it) } ?: (System.currentTimeMillis() - 24 * 60 * 60 * 1000)
            val endMs = endTime?.let { parseTimestamp(it) } ?: System.currentTimeMillis()
            val startDayBucket = (startMs / MS_PER_DAY).toInt()
            // end_day is exclusive in the API, so add 1 to include the entire end day
            val endDayBucket = (endMs / MS_PER_DAY).toInt() + 1

            // Map "self" to nil UUID for API calls (server expects nil UUID for self-tracking)
            val apiCircleId = if (circleId == SELF_CIRCLE_ID) SELF_CIRCLE_UUID else circleId
            Log.d(TAG, "Fetching history for circle $circleId (API: $apiCircleId), userId=$userId, days $startDayBucket-$endDayBucket")
            val response = tracksApi.getLocationHistoryByDayBucket(
                circleId = apiCircleId,
                userId = userId,
                startDayBucket = startDayBucket,
                endDayBucket = endDayBucket
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data?.locations != null) {
                val responseData = response.body()!!.data!!
                val locationResponses = responseData.locations
                Log.d(TAG, "API returned ${locationResponses.size} location responses, truncated=${responseData.truncated}")
                // Decrypt off the main thread: one history response can be thousands
                // of points (the server's budget is 5,000), and each point is a
                // synchronous JNI XChaCha20-Poly1305 decrypt + JSON parse —
                // doing that on the Main dispatcher freezes the UI / ANRs.
                val results = withContext(Dispatchers.Default) {
                    buildList {
                        for (dto in locationResponses) {
                            add(decryptLocationResponseWithResult(circleId, dto))
                        }
                    }
                }

                val locations = results.filterIsInstance<DecryptionResult.Success>()
                    .map { it.location }
                val keyMissing = results.filterIsInstance<DecryptionResult.KeyMissing>().size
                val failures = results.filterIsInstance<DecryptionResult.DecryptionFailed>().size
                // A key (or key version) this device lacks: ask the next
                // circles refresh to re-import keys, which it does not do on
                // every call.
                if (keyMissing > 0 || failures > 0) {
                    runCatching { circleRepository.get().requestKeyReimport() }
                }

                // Late points (kept by a sender that could not share yet, sent as
                // history) are shown like any other, but are history, not news: judging one
                // would raise an alert hours after the crossing.
                val forAlerts = locationResponses.zip(results).mapNotNull { (dto, result) ->
                    // History (a row only an older key opened) is shown, and judged by
                    // nobody.
                    (result as? DecryptionResult.Success)?.location?.takeUnless { dto.historyOnly || !it.keyCurrent }
                }
                if (locations.isNotEmpty()) {
                    persistAndEvaluate(locations, forAlerts, fromServer = true)
                    Log.d(TAG, "Cached ${locations.size} decrypted locations for circle $circleId")
                } else if (locationResponses.isNotEmpty()) {
                    Log.w(TAG, "No locations could be decrypted from ${locationResponses.size} responses for circle $circleId " +
                            "(keyMissing=$keyMissing, failures=$failures)")
                }
                // A read in flight while someone hid their history leaves no trail of theirs here.
                purgeHiddenHistory(circleId)
                // Surface decryption failures to the UI, not just the log. The map screen
                // has its own DecryptionWarning for the realtime path; history emits a
                // one-shot UxEvent so the snackbar tells the user why the timeline is
                // empty or incomplete.
                if (keyMissing > 0) {
                    uxEventBus.emit(
                        com.pingolin.track.util.UxEvent.DecryptionKeyMissing(
                            circleId = circleId,
                            count = keyMissing
                        )
                    )
                }
                // Detect silent server-side range clamping (free tier →
                // 2 days). Without this, "I picked 7 days but only see
                // 2" looks like an app fault. 2 = tolerance for
                // day-bucket rounding.
                val requestedDays = endDayBucket - startDayBucket
                val allowedDays = responseData.historyDaysAllowed
                if (notifyRangeClamping && allowedDays != null && allowedDays > 0 &&
                    requestedDays - allowedDays > 2
                ) {
                    uxEventBus.emit(
                        com.pingolin.track.util.UxEvent.HistoryClamped(
                            requestedDays = requestedDays,
                            allowedDays = allowedDays
                        )
                    )
                }
                // The other way a range comes back short: the downsample
                // overflows the server's row cap and it drops the OLDEST
                // groups, so the trail stops early. The server sets this
                // flag so that the loss is never silent, and it stays silent
                // unless the client reads it. Unlike a plan clamp there is
                // no upgrade that changes it and no next page to fetch; the
                // remedy is a narrower range, which the notice says.
                if (responseData.truncated) {
                    Log.w(TAG, "History truncated by server for circle $circleId: " +
                            "oldest groups dropped from days $startDayBucket-$endDayBucket")
                    uxEventBus.emit(
                        com.pingolin.track.util.UxEvent.HistoryTruncated(circleId = circleId)
                    )
                }
                Result.success(LocationHistoryResult(
                    locations = locations,
                    truncated = responseData.truncated
                ))
            } else {
                Log.e(TAG, "Failed to fetch location history: ${response.body()?.error} (code: ${response.code()})")
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't load the history just now.")))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching location history", e)
            Result.failure(e)
        }
    }

    /**
     * Parse ISO timestamp string to milliseconds.
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            dateFormat.get()!!.parse(timestamp)?.time ?: System.currentTimeMillis()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }

    private suspend fun decryptLocationResponseWithResult(
        circleId: String,
        dto: com.pingolin.track.data.remote.dto.EncryptedLocationResponse
    ): DecryptionResult {
        val keyCircleId = if (circleId == SELF_CIRCLE_ID) SELF_CIRCLE_ID else circleId

        if (!cryptoManager.hasCircleKey(keyCircleId)) {
            Log.w(TAG, "Missing key for circle $keyCircleId, cannot decrypt location")
            return DecryptionResult.KeyMissing(keyCircleId)
        }

        return try {
            // Check for envelope encryption fields - they live inside encrypted_payload
            // (the backend nests them in the EncryptedBlobResponse), but also check
            // top-level for backward compatibility
            val wrappedKey = dto.encryptedPayload.wrappedMsgKey ?: dto.wrappedMsgKey
            val wrappedKeyNonce = dto.encryptedPayload.wrappedMsgKeyNonce ?: dto.wrappedMsgKeyNonce
            val keyId = dto.encryptedPayload.keyId ?: dto.keyId

            // SECURITY: Only log encrypted payload details in debug builds
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Decrypting location for circle $circleId: " +
                        "nonce length=${dto.encryptedPayload.nonce.length}, " +
                        "ciphertext length=${dto.encryptedPayload.ciphertext.length}, " +
                        "dayBucket=${dto.dayBucket}, " +
                        "hasWrappedKey=${wrappedKey != null}, " +
                        "keyId=$keyId")
            }

            // SECURITY: resolve the row's attributed user_id BEFORE decrypting the
            // payload — the sender-bound reads below derive the claimed pseudonym from
            // it. The sealed one, opened with the key version that opened it; the plain
            // one only where the row carries none. A sealed id that is there and doesn't
            // open drops the row: the plain id is then only the server's word, and would
            // show one member's point under another's name. This is the identity the
            // location is displayed under; for self rows it is our own id. (deviceId is
            // resolved later — the bind ignores it.)
            val sealedId = dto.encryptedUserId?.let { encrypted ->
                try {
                    cryptoManager.decryptDataV2Opened(
                        circleId,
                        uniffi.pingolin_mobile.EncryptedData(
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext
                        ),
                        "user_id"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "A location row for circle $circleId has a sealed user id that doesn't open; dropped")
                    return DecryptionResult.DecryptionFailed(circleId, "sealed user id doesn't open")
                }
            }
            val named = sealedId?.text ?: dto.userId
            // One spelling per account: the replay guard, the members this circle doesn't show and
            // the alert state all key on this id, and a second spelling of a member would be a
            // second member to each of them. A row naming no account is no member's fix: it is
            // dropped, and counted like a row that didn't open (as on iOS).
            val userId = com.pingolin.track.util.AccountIds.canonical(named) ?: run {
                Log.w(TAG, "A location row for circle $circleId names no account; dropped")
                return DecryptionResult.DecryptionFailed(circleId, "no account id")
            }
            // A circle's row naming this account, by its plain id OR its sealed one, is no fix of
            // this device's: any member can seal a row with this account's id, and this account's
            // other devices' rows with any device id. This phone's own position is the only "me".
            // Dropped before the payload opens. The personal circle's rows are this account's own
            // history.
            if (circleId != SELF_CIRCLE_ID && circleId != SELF_CIRCLE_UUID) {
                val me = sessionManager.getUserId()
                // With no account there is nothing to tell this account's rows by: none
                // is taken (as on iOS).
                if (me == null || com.pingolin.track.util.AccountIds.same(dto.userId, me) || com.pingolin.track.util.AccountIds.same(sealedId?.text, me)) {
                    return DecryptionResult.NamedMe
                }
            }

            // The *ForUser bound decrypts below take the resolved user_id and derive the
            // claimed USER pseudonym INTERNALLY, once per held circle-key version (newest
            // first). Deriving per version is what makes this cross-rotation-safe: a v3
            // row written under a now-superseded key version still verifies, whereas
            // deriving only for the active version would drop it — permanent history
            // loss. A server relabel (serving member A's ciphertext under member B) fails
            // every version's tag and, since a v3 AAD matches neither the v2 "location"
            // nor the v1 empty AAD, the v2/v1 fallback fails too: the row is dropped
            // (fail closed). A row matching no version falls back to the unbound read
            // internally (senderVerified=false).

            val (decrypted, payloadVersion) = if (keyId != null && keyId.startsWith("s:")) {
                Log.d(TAG, "Using session key decryption for circle $circleId, keyId=$keyId")

                // This circle's own key under that id: another circle's, or the personal
                // one, never opens this row.
                if (!cryptoManager.hasPeerSessionKeyFor(circleId, keyId)) {
                    try {
                        // Strip "s:" prefix — DB stores plain UUID, not the prefixed key_id
                        val apiKeyId = keyId.removePrefix("s:")
                        val sessionKeyResponse = tracksApi.getSessionKey(circleId, apiKeyId)
                        val skData = sessionKeyResponse.body()?.takeIf { sessionKeyResponse.isSuccessful && it.success }?.data
                        if (skData != null && !com.pingolin.track.util.AccountIds.same(skData.sessionKeyId, apiKeyId)) {
                            // An answer about another key than the one asked
                            // for: nothing taken.
                            Log.w(TAG, "The session key answer for circle $circleId names another key than the one asked for; not taken")
                        } else if (skData != null) {
                            val expiresAtSecs = try {
                                Instant.parse(skData.expiresAt).epochSecond
                            } catch (e: Exception) {
                                // Default to 6 hours from now if parsing fails
                                System.currentTimeMillis() / 1000 + 21600
                            }
                            // Kept under the id the row names, the
                            // one asked for.
                            cryptoManager.importPeerSessionKey(
                                circleId,
                                apiKeyId,
                                skData.encryptedSessionKey,
                                skData.keyNonce,
                                skData.keyVersion,
                                expiresAtSecs
                            )
                        } else {
                            Log.w(TAG, "Failed to fetch session key $keyId for circle $circleId")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error fetching session key $keyId: ${e.message}")
                    }
                }

                try {
                    val encData = uniffi.pingolin_mobile.EncryptedData(
                        nonce = dto.encryptedPayload.nonce,
                        ciphertext = dto.encryptedPayload.ciphertext
                    )
                    // v3 sender-bound session-key decrypt: resolves the key by keyId,
                    // verifies the user pseudonym derived per circle-key version from
                    // user_id, and falls back to the unbound read under the same key
                    // internally. No JSON fallback is needed here — session rows are
                    // always SDK-format; web {lat,lng} rows arrive on the envelope or
                    // circle-key path below.
                    cryptoManager.decryptLocationWithSessionKeyBoundForUser(circleId, encData, keyId, userId)
                        .opened(circleId)
                } catch (e: Exception) {
                    Log.w(TAG, "Session key decryption failed for $keyId, falling back to circle key: ${e.message}")
                    cryptoManager.decryptLocationBoundForUser(
                        keyCircleId,
                        uniffi.pingolin_mobile.EncryptedData(
                            nonce = dto.encryptedPayload.nonce,
                            ciphertext = dto.encryptedPayload.ciphertext
                        ),
                        userId
                    ).opened(circleId)
                }
            } else if (wrappedKey != null && wrappedKeyNonce != null) {
                // Envelope decryption: unwrap msg_key, then decrypt ciphertext
                Log.d(TAG, "Using envelope decryption for circle $circleId")
                // Use the SDK circle key lookup ID (may need nil UUID for self)
                val sdkCircleId = if (circleId == SELF_CIRCLE_ID) SELF_CIRCLE_UUID else circleId
                // v3 sender-bound envelope decrypt: targetId unwraps the msg_key from the
                // circle key, then the payload is verified against the user pseudonym
                // derived per circle-key version from user_id, falling back to the v2/v1
                // read internally (senderVerified=false). Same user_id as the other two
                // read paths.
                cryptoManager.unwrapAndDecryptLocationBoundForUser(
                    sdkCircleId,
                    wrappedKey,
                    wrappedKeyNonce,
                    dto.encryptedPayload.ciphertext,
                    dto.encryptedPayload.nonce,
                    userId
                ).opened(circleId)
            } else {
                // Direct circle-key decryption.
                try {
                    // v3 sender-bound circle-key decrypt; derives the user pseudonym per
                    // circle-key version from user_id, falling back to the unbound v2/v1
                    // read internally (senderVerified=false).
                    cryptoManager.decryptLocationBoundForUser(
                        keyCircleId,
                        uniffi.pingolin_mobile.EncryptedData(
                            nonce = dto.encryptedPayload.nonce,
                            ciphertext = dto.encryptedPayload.ciphertext
                        ),
                        userId
                    ).opened(circleId)
                } catch (e: Exception) {
                    Log.d(TAG, "SDK format failed, trying JSON format: ${e.message}")
                    val locationJson = cryptoManager.decryptDataOpened(
                        keyCircleId,
                        uniffi.pingolin_mobile.EncryptedData(
                            nonce = dto.encryptedPayload.nonce,
                            ciphertext = dto.encryptedPayload.ciphertext
                        )
                    )
                    parseLocationFromJson(locationJson.text) to locationJson.keyVersion
                }
            }

            // SECURITY: Decrypt deviceId if encrypted, else fall back to plaintext.
            // (userId is resolved earlier, before the payload decrypt, because the
            // sender-bound reads derive the claimed pseudonym from it.)
            val deviceId = dto.encryptedDeviceId?.let { encrypted ->
                try {
                    cryptoManager.decryptDataV2(
                        circleId,
                        uniffi.pingolin_mobile.EncryptedData(
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext
                        ),
                        "device_id"
                    )
                } catch (e: Exception) {
                    dto.deviceId // Fall back to plaintext
                }
            } ?: dto.deviceId ?: "unknown"

            // CURRENT only where the version that opened the payload (its time inside it) AND,
            // when the row carries one, the sealed user id is the one this device seals the
            // circle under: the SDK's one test, never a compare here. Else HISTORY: stored and
            // shown in history, never live, never alert input, never the guard's.
            val keyCurrent = cryptoManager.isCurrentKeyVersion(keyCircleId, payloadVersion) &&
                (sealedId == null || cryptoManager.isCurrentKeyVersion(keyCircleId, sealedId.keyVersion))

            DecryptionResult.Success(LocationEntity(
                circleId = circleId,
                userId = userId,
                deviceId = deviceId,
                latitude = decrypted.latitude,
                longitude = decrypted.longitude,
                altitude = decrypted.altitude,
                speed = decrypted.speed,
                heading = decrypted.heading,
                accuracy = decrypted.accuracy,
                // The level the member's phone sealed (none when they don't share their battery with
                // this circle): what a low-battery rule here is judged on. Only on a row that is
                // news: a row only an older key opened, or a late point, is history, judged by
                // nobody (MonitoringManager reads the member's newest row, and a late point can be
                // newer than their last live one).
                batteryLevel = decrypted.batteryLevel?.toInt()?.takeIf { keyCurrent && !dto.historyOnly },
                timestamp = decrypted.timestamp,
                isSynced = true,
                dayBucket = (decrypted.timestamp / MS_PER_DAY).toInt(),
                // Plaintext on the row, not inside the sealed payload: it is the
                // server's consent field. Carried onto the stored location so the
                // evaluator judges each fix against the posture that arrived WITH it.
                allowAlertsOnMe = dto.allowAlertsOnMe,
                keyCurrent = keyCurrent
            ))
        } catch (e: Exception) {
            // Each field is labelled with what it actually holds: the wrapped
            // message key as such, and the row's key id read from the nested
            // `encryptedPayload.keyId` first, as the resolution above prefers, so
            // a healthy envelope never logs as a missing session key.
            //
            // The truncated values stay behind BuildConfig.DEBUG, matching the
            // rule the debug block above states: encrypted payload details do not
            // go into a release log. Release keeps the two booleans, which are
            // what separates "no envelope on this row" from "an envelope we could
            // not open".
            val wrappedMsgKey = dto.encryptedPayload.wrappedMsgKey ?: dto.wrappedMsgKey
            val rowKeyId = dto.encryptedPayload.keyId ?: dto.keyId
            val detail = if (BuildConfig.DEBUG) {
                "wrappedMsgKey=${wrappedMsgKey?.take(8) ?: "none"}, rowKeyId=${rowKeyId ?: "none"}"
            } else {
                "hasWrappedMsgKey=${wrappedMsgKey != null}, hasRowKeyId=${rowKeyId != null}"
            }
            Log.e(TAG, "Failed to decrypt location for circle $circleId ($detail): ${e.message}")
            DecryptionResult.DecryptionFailed(circleId, e.message ?: "Unknown error")
        }
    }

    /**
     * The location this read opened, with the circle key version that opened it.
     *
     * Unwrap the [uniffi.pingolin_mobile.Location] from a sender-bound decrypt,
     * noting in debug builds when the row's claimed user pseudonym was
     * cryptographically verified (v3).
     *
     * Observability only: an unverified row is still returned, because rows
     * written without sender binding — by older app versions, or by clients that
     * fell back to the unbound encrypt — are legitimate and must keep rendering.
     * The v3 tag check itself is what fails closed on a relabel, inside the SDK.
     */
    private fun uniffi.pingolin_mobile.BoundLocation.opened(circleId: String): Pair<uniffi.pingolin_mobile.Location, Int> {
        if (BuildConfig.DEBUG && senderVerified) {
            Log.d(TAG, "v3 sender-verified location for circle $circleId")
        }
        return location to keyVersion
    }

    /**
     * Parse location from JSON format (web-created locations).
     * Supports both field naming conventions:
     * - Web format: { lat, lng }
     * - SDK format: { latitude, longitude, altitude, speed, heading, accuracy, timestamp, ... }
     */
    private fun parseLocationFromJson(json: String): uniffi.pingolin_mobile.Location {
        val jsonObj = org.json.JSONObject(json)

        val latitude = if (jsonObj.has("latitude")) {
            jsonObj.getDouble("latitude")
        } else {
            jsonObj.getDouble("lat")
        }

        val longitude = if (jsonObj.has("longitude")) {
            jsonObj.getDouble("longitude")
        } else {
            jsonObj.getDouble("lng")
        }

        return uniffi.pingolin_mobile.Location(
            latitude = latitude,
            longitude = longitude,
            altitude = jsonObj.optDouble("altitude").takeIf { !it.isNaN() },
            speed = jsonObj.optDouble("speed").takeIf { !it.isNaN() },
            heading = jsonObj.optDouble("heading").takeIf { !it.isNaN() },
            accuracy = jsonObj.optDouble("accuracy").takeIf { !it.isNaN() },
            timestamp = jsonObj.optLong("timestamp", System.currentTimeMillis()),
            batteryLevel = null,
            isCharging = false
        )
    }

    // ===== Session Keys =====

    /**
     * Ensure a valid session key exists for [circleId], creating and publishing one if
     * not. Called before encrypting locations.
     *
     * Never throws: if creation or publishing fails, the local key is invalidated and
     * location encryption silently falls back to the circle key.
     */
    suspend fun ensureSessionKey(circleId: String) {
        try {
            if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return

            if (cryptoManager.hasActiveSessionKey(circleId)) {
                return
            }

            // Create a new session key (6 hours = 21600 seconds)
            val sessionKeyData = cryptoManager.createSessionKey(circleId, 21600L)

            val deviceId = sessionManager.getDeviceId()
            val request = PublishSessionKeyRequest(
                sessionKeyId = sessionKeyData.sessionKeyId,
                deviceId = deviceId,
                encryptedSessionKey = sessionKeyData.encryptedSessionKey,
                keyNonce = sessionKeyData.keyNonce,
                keyVersion = sessionKeyData.keyVersion,
                expiresAt = sessionKeyData.expiresAt
            )

            val response = tracksApi.publishSessionKey(circleId, request)
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "Published session key ${sessionKeyData.sessionKeyId} for circle $circleId")
            } else {
                Log.w(TAG, "Failed to publish session key for circle $circleId: ${response.body()?.error}")
                // Invalidate the locally-created session key so encryption falls back to circle key
                cryptoManager.invalidateSessionKeysForCircle(circleId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure session key for circle $circleId: ${e.message}")
            // Invalidate on any failure so we don't encrypt with an unpublished key
            try {
                cryptoManager.invalidateSessionKeysForCircle(circleId)
            } catch (invalidateEx: Exception) {
                Log.w(TAG, "Failed to invalidate session key for circle $circleId: ${invalidateEx.message}")
            }
        }
    }

    fun cleanup() {
        stopStreaming()
        locationManager?.clearErrorCallback()
        locationManager?.close()
        locationManager = null
        // Drop the replay-guard state on teardown so it can't leak accepted
        // timestamps across an account switch (logout tears the tracking service
        // down → cleanup()). Keys already include the per-account circle UUID, so
        // this is defence-in-depth; the reset only re-arms first-seen acceptance,
        // matching a fresh process start.
        lastAcceptedMemberTs.clear()
    }

    /**
     * SECURITY: drop every in-memory decrypted position this @Singleton holds so
     * it can't leak across a same-process account switch. clearAllKeys() +
     * clearAllTables() on logout wipe the crypto + persisted rows, but these
     * process-lifetime fields (cold-start fast-path cache + own marker) survive
     * the singleton, so the next user could momentarily see the prior user's
     * markers. Called from AuthRepository.clearLocalData and the force-logout
     * callback. Resets to the fresh-process empty state.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // resetReplayCache()
    fun wipeInMemory() {
        freshByUser.clear()
        _frozenLocations.value = emptyMap()
        frozenGrace.clear()
        lastAcceptedMemberTs.clear()
        _selfLocation.value = null
        // Drop the replay=1 cache too: the next subscriber (a fresh account's map on a
        // same-process switch) would otherwise receive the prior user's last decrypted
        // locations as the SharedFlow's replayed emission.
        _freshLocations.resetReplayCache()
        // Who shares approximately in which circle is the signed-out account's; kept on disk
        // too, so it is dropped here with the rest.
        approximateMembers.clear()
        // The signed-out account's own newest position (it is held per account as well).
        newestOwnFix.clear()
    }
}

/**
 * How many kept points may go out now: at most [perMinute] in any minute. Not
 * thread-safe; the caller holds its flush lock.
 */
internal class KeptPointPace(private val perMinute: Int, private val now: () -> Long = System::currentTimeMillis) {
    private val sentAt = ArrayDeque<Long>()

    fun allowance(): Int {
        val minuteAgo = now() - 60_000L
        while (sentAt.isNotEmpty() && sentAt.first() <= minuteAgo) sentAt.removeFirst()
        return (perMinute - sentAt.size).coerceAtLeast(0)
    }

    fun sent() {
        sentAt.addLast(now())
    }
}
