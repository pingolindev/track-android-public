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

import android.util.Log
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.pingolin_mobile.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for E2EE boundary monitoring.
 *
 * All boundary checking is done CLIENT-SIDE to maintain end-to-end encryption.
 * The server never sees:
 * - Plaintext coordinates (encrypted in places table)
 * - User locations (stored encrypted)
 * - Event types (encrypted, server only sees hash for routing)
 *
 * Server-side notification routing uses a hash commitment scheme:
 * - Client sends event_type_hash = BLAKE3(circle_key || event_type)
 * - Server matches against pre-computed hashes for "enter"/"exit"
 * - Server cannot determine event_type without circle key
 */
@Singleton
class BoundaryRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val placeRepository: PlaceRepository,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val boundaryChecker: BoundaryChecker,
    private val boundaryMonitor: BoundaryMonitor,
    // Local privacy row (primed via CircleRepository) so a self-crossing emit honors the
    // target circle's pause / realtime-off posture — a crossing is a presence signal and
    // must not reach a circle the user paused. Same source as the LocationRepository /
    // CheckInRepository send gates.
    private val privacySettingDao: com.pingolin.track.data.local.dao.PrivacySettingDao,
    private val circleRepository: CircleRepository,
    // Outbox for crossings whose report did not reach the server: without one, a
    // lost request would be a lost alert with nothing to show it happened.
    private val pendingCrossingDao: com.pingolin.track.data.local.dao.PendingCrossingDao,
    // The record of when sharing with each circle was paused or real-time was off, which a
    // replayed crossing is judged against at its own time ([replayGate]).
    private val pauseIntervals: com.pingolin.track.util.PauseIntervals,
) {
    companion object {
        private const val TAG = "BoundaryRepository"

        const val EVENT_TYPE_ENTER = "enter"
        const val EVENT_TYPE_EXIT = "exit"

        /** Milliseconds per day for day bucket calculation */
        private const val MS_PER_DAY = 86400000L

        /** Longest a reload goes without re-reading the alerted circles' rosters. */
        internal const val ROSTER_MAX_AGE_MS = 60 * 60_000L

        /**
         * Do the alerted circles' rosters need re-reading? Yes when the set of alert
         * circles changed since the last successful refresh (including the first
         * reload, when nothing was refreshed yet), or when that refresh is older than
         * [ROSTER_MAX_AGE_MS]. Nothing to refresh when no alert names a circle.
         */
        internal fun rosterRefreshDue(
            alertCircles: Set<String>,
            lastRefreshed: Set<String>,
            lastRefreshAtMs: Long,
            nowMs: Long,
        ): Boolean {
            if (alertCircles.isEmpty()) return false
            if (alertCircles != lastRefreshed) return true
            return lastRefreshAtMs <= 0L || nowMs - lastRefreshAtMs >= ROSTER_MAX_AGE_MS || nowMs < lastRefreshAtMs
        }
    }

    // Reference to the monitor callback for lifecycle management
    private val monitorCallback = AtomicReference<BoundaryMonitorCallback?>(null)

    // Current boundary states for monitored users
    private val _boundaryStates = MutableStateFlow<Map<String, List<BoundaryStateResponse>>>(emptyMap())
    val boundaryStates: StateFlow<Map<String, List<BoundaryStateResponse>>> = _boundaryStates.asStateFlow()

    // Recent boundary events
    private val _recentEvents = MutableStateFlow<List<DecryptedBoundaryEvent>>(emptyList())
    val recentEvents: StateFlow<List<DecryptedBoundaryEvent>> = _recentEvents.asStateFlow()

    // Local cache of user display names for friendly notification messages
    // Thread-safe: accessed from multiple threads (location callback, main thread, boundary monitor)
    private val userDisplayNames = ConcurrentHashMap<String, String>()

    /**
     * Compute day bucket from timestamp (days since Unix epoch).
     */
    private fun computeDayBucket(timestampMs: Long): Int =
        (timestampMs / MS_PER_DAY).toInt()

    /**
     * Compute event type hash for server-side notification routing.
     *
     * Uses BLAKE3(circle_key || event_type) via the native SDK.
     * This allows the server to route notifications without knowing the actual event type.
     */
    private fun computeEventTypeHash(circleId: String, eventType: String): String? {
        return try {
            cryptoManager.computeEventTypeHash(circleId, eventType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute event type hash for $eventType", e)
            null
        }
    }

    /**
     * Build the alertsEnabled boundary list for a single circle: refresh its
     * places from the API (this decrypts + stores locally), then map each place
     * an alert actually targets to a native [Boundary]. Factored out so the
     * single-circle and all-circles loaders share ONE copy of the alertsEnabled
     * filter + radius/id scheme. Returns [Result.failure] with the cause intact
     * if the place refresh fails, so [loadBoundariesForCircle] can propagate it
     * while [loadBoundariesForAllCircles] can skip just that one circle.
     */
    private suspend fun buildBoundariesForCircle(circleId: String): Result<List<Boundary>> {
        return try {
            // Refresh places from API (this decrypts and stores in local DB)
            val refreshResult = placeRepository.refreshPlaces(circleId)
            if (refreshResult.isFailure) {
                return Result.failure(refreshResult.exceptionOrNull() ?: Exception("Failed to load places"))
            }

            val places = refreshResult.getOrNull() ?: emptyList()
            Result.success(boundariesFromPlaces(places))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build boundaries for circle $circleId", e)
            Result.failure(e)
        }
    }

    /**
     * Map a decrypted place set to the native geofence boundaries the checker
     * monitors. A place participates in crossing detection iff an alert actually
     * targets it (alertsEnabled — "an alert turns monitoring on"). No fallback is
     * needed: a boundary place with no alert fired for nobody anyway, and any
     * boundary WITH an alert already has alertsEnabled set by the server, so
     * gating purely on alertsEnabled loses nothing that was actually delivering
     * crossings. The radius prefers an explicit boundaryRadius ring when present,
     * else the place radius.
     */
    private fun boundariesFromPlaces(places: List<PlaceEntity>): List<Boundary> =
        places
            .filter { it.alertsEnabled }
            .map { place ->
                Boundary(
                    id = place.id,
                    circleId = place.circleId,
                    centerLat = place.latitude,
                    centerLng = place.longitude,
                    radiusMeters = (place.boundaryRadius ?: place.radius).toUInt(),
                    name = place.name
                )
            }

    /**
     * Fetch and decrypt one circle's places, and register their geofences with the
     * native BoundaryChecker.
     *
     * NOTE: the native BoundaryChecker (from the mobile SDK) REPLACES its whole
     * boundary map on every load, so this loads ONLY [circleId]'s boundaries and
     * drops any already loaded. To monitor several circles at once use
     * [loadBoundariesForAllCircles] — calling this in a per-circle loop would
     * leave only the last circle monitored.
     */
    suspend fun loadBoundariesForCircle(circleId: String): Result<Int> {
        return try {
            val boundaries = buildBoundariesForCircle(circleId)
                .getOrElse { return Result.failure(it) }
            boundaryChecker.loadBoundaries(boundaries)
            Result.success(boundaries.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load boundaries for circle $circleId", e)
            Result.failure(e)
        }
    }

    /**
     * (Re)load the geofence boundary set for MANY circles in a SINGLE native
     * commit. The native BoundaryChecker REPLACES its whole boundary map on
     * every load_boundaries call (it clears the map, then inserts), so
     * loading one circle at a time in a loop leaves only the LAST circle's
     * boundaries active — a member's crossings on every earlier circle's places
     * then silently never fire. Accumulate every circle's alertsEnabled
     * boundaries into one union and hand the native checker that union ONCE.
     *
     * Boundary ids are place ids (server-unique; each place row carries exactly
     * one circleId — see findCircleForPlace), so the union has no id collisions
     * and needs no dedup: a place shared across circles still resolves to a
     * single local row and a single boundary.
     *
     * PERF: GET /places returns the FULL visible set (personal + circle + shared)
     * and IGNORES circle_id server-side, so this fetches + decrypts the place set
     * exactly ONCE and builds the union from that. Fanning out per circle would do
     * N identical (fetch + decrypt + REPLACE) rounds, REPLACE-rewriting every place
     * row and re-emitting every Room observer every 2 minutes. [circleIds] is only
     * used for logging — the fetch does not scope to it.
     *
     * Returns the total number of boundaries loaded across all circles.
     */
    suspend fun loadBoundariesForAllCircles(circleIds: List<String>): Result<Int> {
        return try {
            // Best-effort network refresh (null = all visible places; refreshPlaces
            // skips the DB REPLACE for unchanged rows, so a no-op reload is cheap).
            // Do NOT bail if it fails: the geofence union must still be rebuilt from
            // whatever places are already in the local DB, so a transient/persistent
            // refreshPlaces failure can't silently BLANK monitoring — otherwise place
            // alerts detect no crossings while the app keeps sharing location (unlike
            // destinations, which read the DAO directly and always work). The failure
            // is logged and simply retried on the next reload tick.
            val refreshResult = placeRepository.refreshPlaces(null)
            if (refreshResult.isFailure) {
                Log.w(TAG, "refreshPlaces failed; rebuilding boundaries from retained local rows")
            }
            // Keep the LOCAL alert set current on the same tick.
            //
            // Subscriber-side place alerts are evaluated on this device against the
            // `place_alerts` rows it holds, and this is the ONLY background refresh of
            // that table — every other write needs the user to open the Places tab or
            // create an alert. Without it, an alert created on another device (or on
            // web/iOS) never reaches this device's evaluator, and any local loss is
            // permanent until the user happens to open Places. Best-effort, exactly
            // like refreshPlaces above: a failure just retries on the next tick and
            // never blanks the local rows (refreshPlaceAlerts only ever inserts).
            try {
                placeRepository.refreshPlaceAlerts()
            } catch (e: Exception) {
                Log.w(TAG, "refreshPlaceAlerts failed; keeping retained local alerts")
            }
            // ...and the member ROSTERS those alerts are scoped by, for the same
            // reason. A circle-wide alert watches "everyone in THIS circle", which
            // PlaceAlertEvaluator resolves against local circle_members — and every
            // caller of refreshMembers is a UI ViewModel, so a device that hasn't
            // opened the right screen (a fresh install, say) holds no roster and the
            // scope cannot be evaluated. Only circles an active alert actually names
            // are fetched, so this stays proportional to the alerts in play rather
            // than fanning out over every circle every two minutes.
            //
            // Sourced via PlaceRepository rather than by injecting PlaceAlertDao here:
            // this class must stay structurally unable to consult alerts when deciding
            // where a crossing is EMITTED (see reportCrossingFromMonitor), and
            // BoundaryCrossingEmitTest asserts exactly that on the constructor.
            //
            // Not on every reload, though: rosters change rarely, and refreshing them
            // each tick for every alert circle costs battery for nothing. They are
            // refreshed when the set of alert circles changes, and otherwise at most
            // once per ROSTER_MAX_AGE_MS — so a member who joins an alerted circle is
            // in scope within the hour even with no screen opened. A failed refresh is
            // not recorded, so it is retried on the next reload.
            try {
                val alertCircles = placeRepository.activeAlertCircleIds().toSet()
                val now = android.os.SystemClock.elapsedRealtime()
                if (rosterRefreshDue(alertCircles, lastRosterCircleIds, lastRosterRefreshAtMs, now)) {
                    var allRefreshed = true
                    alertCircles.forEach { circleId ->
                        if (circleRepository.refreshMembers(circleId).isFailure) allRefreshed = false
                    }
                    if (allRefreshed) {
                        lastRosterCircleIds = alertCircles
                        lastRosterRefreshAtMs = now
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "member roster refresh failed; alert scoping falls back to unscoped")
            }
            // Build the native geofence union from the RETAINED Room rows, NOT this
            // tick's freshly-decrypted output (refreshResult.getOrNull()). refreshPlaces
            // prunes only against server truth, so a place whose circle key was
            // transiently missing THIS tick — decrypted+stored on an earlier tick — is
            // still in the DB and MUST stay monitored. Mapping the decrypted output would
            // drop that place from the union and unmonitor its geofence for ≥1 reload tick.
            // The DB stores decrypted place coords, so boundariesFromPlaces maps the
            // entities cleanly.
            val retained = placeRepository.getAllPlaces()
            val all = boundariesFromPlaces(retained)
            boundaryChecker.loadBoundaries(all)
            if (all.isEmpty()) {
                // Log.w survives release minification (unlike the Log.d below), so the
                // failure mode where place-alert crossings are never detected — an
                // empty monitored set — is visible in production logcat.
                Log.w(TAG, "boundary set EMPTY after load: ${retained.size} retained places, 0 alert-enabled — place-alert crossings will NOT be detected")
            }
            Log.d(TAG, "Loaded ${all.size} boundaries from ${retained.size} retained places (${circleIds.size} circles)")
            Result.success(all.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load boundaries for circles", e)
            Result.failure(e)
        }
    }

    /**
     * Check a location against all loaded boundaries.
     * Returns any crossings detected.
     *
     * [accuracyMeters] is the fix's reported horizontal accuracy, and MUST be
     * passed on when the caller has it. The native checker gates each boundary
     * on it: a fix rougher than a boundary's own radius decides nothing about
     * that boundary and is skipped, leaving its state untouched (see
     * `can_resolve` in the mobile SDK's boundary checker).
     *
     * Dropping it here would be silent. The binding defaults it to null —
     * "unknown", which evaluates — so a caller that forgets compiles cleanly and
     * behaves as if there were no gate. That is the whole failure mode: a
     * crossing reported off a 500 m fix is broadcast to the mover's circle.
     * BoundaryAccuracyWiringTest is what holds this shut.
     */
    suspend fun checkLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double? = null,
    ): List<BoundaryCrossing> {
        return try {
            boundaryChecker.checkLocation(latitude, longitude, accuracyMeters)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check location against boundaries", e)
            emptyList()
        }
    }

    /**
     * Report a boundary crossing to the server.
     * All sensitive data is encrypted with the circle key.
     */
    suspend fun reportCrossing(
        placeId: String,
        circleId: String,
        crossingUserId: String,
        eventType: String, // "enter" or "exit"
        latitude: Double? = null,
        longitude: Double? = null,
        /**
         * When the crossing happened. Sealed with the circle key and sent so the
         * SUBSCRIBER can stamp their notification with it; the server never reads
         * it and can only observe when the report arrived.
         */
        occurredAtMs: Long = System.currentTimeMillis()
    ): Result<ReportBoundaryCrossingResponse> {
        return try {
            circleRepository.requireCurrentKey(circleId)
            val deviceId = sessionManager.getDeviceId()

            // Encrypt event type
            val encryptedEventTypeData = cryptoManager.encryptDataV2(circleId, eventType, "event_type")
            val encryptedEventType = EncryptedPayload(
                nonce = encryptedEventTypeData.nonce,
                ciphertext = encryptedEventTypeData.ciphertext
            )

            // Encrypt device ID
            val encryptedDeviceIdData = cryptoManager.encryptDataV2(circleId, deviceId, "device_id")
            val encryptedDeviceId = EncryptedPayload(
                nonce = encryptedDeviceIdData.nonce,
                ciphertext = encryptedDeviceIdData.ciphertext
            )

            // Compute event type hash for server-side routing
            val eventTypeHash = computeEventTypeHash(circleId, eventType)
                ?: return Result.failure(Exception("Failed to compute event type hash"))

            // Optionally encrypt location
            var encryptedLocation: EncryptedPayload? = null
            if (latitude != null && longitude != null) {
                val location = Location(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = null,
                    speed = null,
                    heading = null,
                    accuracy = null,
                    timestamp = System.currentTimeMillis(),
                    batteryLevel = null,
                    isCharging = false
                )
                val encrypted = cryptoManager.encryptLocation(circleId, location)
                encryptedLocation = EncryptedPayload(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                )
            }

            // Seal the crossing time to the circle. Encoded as a decimal string
            // because encryptDataV2 seals strings and the recipients parse it back
            // to epoch millis; the server only ever handles the ciphertext.
            val encryptedOccurredAt = runCatching {
                val blob = cryptoManager.encryptDataV2(circleId, occurredAtMs.toString(), "occurred_at")
                EncryptedPayload(nonce = blob.nonce, ciphertext = blob.ciphertext)
            }.getOrElse {
                // Never fail the crossing report over the timestamp: a crossing
                // reported without one still notifies, just with the server-side
                // stamp instead.
                Log.w(TAG, "Failed to encrypt crossing time; reporting without it", it)
                null
            }

            val response = tracksApi.reportBoundaryCrossing(
                ReportBoundaryCrossingRequest(
                    placeId = placeId,
                    crossingUserId = crossingUserId,
                    encryptedEventType = encryptedEventType,
                    encryptedDeviceId = encryptedDeviceId,
                    eventTypeHash = eventTypeHash,
                    encryptedLocation = encryptedLocation,
                    encryptedOccurredAt = encryptedOccurredAt
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't tell your circle you arrived or left just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get boundary events for a circle, with decrypted data.
     */
    suspend fun getBoundaryEvents(
        circleId: String,
        placeId: String? = null,
        limit: Int = 50
    ): Result<List<DecryptedBoundaryEvent>> {
        return try {
            val response = tracksApi.getBoundaryEvents(circleId, placeId, limit)

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val events = response.body()!!.data!!.mapNotNull { dto ->
                    decryptBoundaryEvent(circleId, dto)
                }
                _recentEvents.value = events
                Result.success(events)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't load the arrivals and departures just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get current boundary states for a circle.
     */
    suspend fun getBoundaryStates(circleId: String): Result<List<BoundaryStateResponse>> {
        return try {
            val response = tracksApi.getBoundaryStates(circleId)

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val states = response.body()!!.data!!
                _boundaryStates.value = _boundaryStates.value + (circleId to states)
                Result.success(states)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't load who is where just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypt a boundary event response.
     */
    private fun decryptBoundaryEvent(circleId: String, dto: BoundaryEventResponse): DecryptedBoundaryEvent? {
        return try {
            // Decrypt user ID
            val userId = cryptoManager.decryptDataV2(
                circleId,
                EncryptedData(
                    nonce = dto.encryptedUserId.nonce,
                    ciphertext = dto.encryptedUserId.ciphertext
                ),
                "user_id"
            )

            // Decrypt device ID
            val deviceId = cryptoManager.decryptDataV2(
                circleId,
                EncryptedData(
                    nonce = dto.encryptedDeviceId.nonce,
                    ciphertext = dto.encryptedDeviceId.ciphertext
                ),
                "device_id"
            )

            // Decrypt event type
            val eventType = cryptoManager.decryptDataV2(
                circleId,
                EncryptedData(
                    nonce = dto.encryptedEventType.nonce,
                    ciphertext = dto.encryptedEventType.ciphertext
                ),
                "event_type"
            )

            // Decrypt timestamp
            val timestamp = try {
                val timestampStr = cryptoManager.decryptDataV2(
                    circleId,
                    EncryptedData(
                        nonce = dto.encryptedOccurredAt.nonce,
                        ciphertext = dto.encryptedOccurredAt.ciphertext
                    ),
                    "timestamp"
                )
                timestampStr.toLongOrNull() ?: (dto.dayBucket.toLong() * MS_PER_DAY)
            } catch (e: Exception) {
                dto.dayBucket.toLong() * MS_PER_DAY
            }

            DecryptedBoundaryEvent(
                id = dto.id,
                placeId = dto.placeId,
                circleId = dto.circleId,
                userId = userId,
                deviceId = deviceId,
                eventType = eventType,
                timestamp = timestamp
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Set a callback for boundary crossing events detected by the native checker.
     */
    suspend fun setBoundaryCallback(callback: BoundaryCallback) {
        try {
            boundaryChecker.setCallback(callback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set boundary callback", e)
        }
    }

    /**
     * Clear all loaded boundaries.
     */
    suspend fun clearBoundaries() {
        try {
            boundaryChecker.clearBoundaries()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear boundaries", e)
        }
    }

    /**
     * SECURITY: drop the decrypted in-memory boundary state this @Singleton holds so
     * it can't leak across a same-process account switch. The StateFlows cache
     * decrypted boundary states + events, the display-name map holds member names,
     * and the native BoundaryChecker holds decrypted place geofences. Called from
     * both logout paths (AuthRepository.clearLocalData + the force-logout callback),
     * mirroring the location/circle repos. Resets to the fresh-process empty state.
     */
    suspend fun wipeInMemory() {
        _boundaryStates.value = emptyMap()
        _recentEvents.value = emptyList()
        userDisplayNames.clear()
        lastRosterCircleIds = emptySet()
        lastRosterRefreshAtMs = 0L
        clearBoundaries()
    }

    /** Alert circles whose rosters the last reload refreshed, and when (elapsedRealtime). */
    @Volatile private var lastRosterCircleIds: Set<String> = emptySet()
    @Volatile private var lastRosterRefreshAtMs = 0L

    /**
     * Check if user was last seen inside a boundary.
     */
    suspend fun isInsideBoundary(boundaryId: String): Boolean {
        return try {
            boundaryChecker.isInsideBoundary(boundaryId) ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get distance to a boundary from a location.
     */
    suspend fun distanceToBoundary(boundaryId: String, latitude: Double, longitude: Double): Double? {
        return try {
            boundaryChecker.distanceToBoundary(boundaryId, latitude, longitude)
        } catch (e: Exception) {
            null
        }
    }

    // ===== BoundaryMonitor Integration (for monitoring other users) =====

    /**
     * Set the callback for BoundaryMonitor events.
     * Called when monitoring other circle members for boundary crossings.
     */
    suspend fun setMonitorCallback(callback: BoundaryMonitorCallback) {
        try {
            monitorCallback.set(callback)
            boundaryMonitor.setCallback(callback)
            Log.d(TAG, "BoundaryMonitor callback set")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set monitor callback", e)
        }
    }

    /**
     * Clear the BoundaryMonitor callback.
     */
    suspend fun clearMonitorCallback() {
        try {
            monitorCallback.set(null)
            boundaryMonitor.clearCallback()
            Log.d(TAG, "BoundaryMonitor callback cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear monitor callback", e)
        }
    }

    /**
     * Start monitoring [userId] for boundary crossings. [circleId] must be a circle
     * we share with them — that is where the key to decrypt their location comes from.
     */
    suspend fun startMonitoringUser(userId: String, circleId: String) {
        try {
            boundaryMonitor.monitorUser(userId, circleId)
            Log.d(TAG, "Started monitoring user $userId in circle $circleId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring user $userId", e)
        }
    }

    suspend fun stopMonitoringUser(userId: String) {
        try {
            boundaryMonitor.unmonitorUser(userId)
            Log.d(TAG, "Stopped monitoring user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring user $userId", e)
        }
    }

    suspend fun getMonitoredUsers(): List<String> {
        return try {
            boundaryMonitor.getMonitoredUsers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get monitored users", e)
            emptyList()
        }
    }

    /**
     * Load still-encrypted places into the BoundaryMonitor, which decrypts them
     * itself for member-location checking. Returns how many loaded.
     */
    suspend fun loadPlacesForMonitoring(places: List<EncryptedPlace>): Result<Int> {
        return try {
            val count = boundaryMonitor.loadEncryptedPlaces(places)
            Log.d(TAG, "Loaded $count places into BoundaryMonitor")
            Result.success(count.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load places for monitoring", e)
            Result.failure(e)
        }
    }

    /**
     * Decrypt a circle member's location update and check it against every loaded
     * boundary, returning the crossings it produced (possibly none).
     */
    suspend fun processMemberLocation(
        memberLocation: EncryptedMemberLocation
    ): List<BoundaryCrossing> {
        return try {
            boundaryMonitor.processMemberLocation(memberLocation)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process member location", e)
            emptyList()
        }
    }

    /**
     * Check own location against all boundaries in the BoundaryMonitor.
     * This is for checking the current user's location, not circle members.
     */
    suspend fun checkOwnLocation(latitude: Double, longitude: Double): List<BoundaryCrossing> {
        return try {
            boundaryMonitor.checkOwnLocation(latitude, longitude)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check own location", e)
            emptyList()
        }
    }

    /**
     * Set display names for monitored users.
     * Call this when loading circle members to enable friendly notification messages.
     */
    fun setUserDisplayNames(names: Map<String, String>) {
        userDisplayNames.clear()
        userDisplayNames.putAll(names)
        Log.d(TAG, "Set display names for ${names.size} users")
    }

    /**
     * Get display name for a user (for use in notifications).
     */
    fun getUserDisplayName(userId: String): String {
        return userDisplayNames[userId] ?: userId
    }

    /**
     * Get the number of places loaded in BoundaryMonitor.
     */
    suspend fun getMonitoredPlaceCount(): Int {
        return try {
            boundaryMonitor.placeCount().toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Report a crossing from BoundaryMonitor callback.
     * This wraps reportCrossing for use with the callback handler.
     */
    suspend fun reportCrossingFromMonitor(
        placeId: String,
        userId: String,
        deviceId: String,
        crossingType: String,
        /** When the crossing was detected on this device — see CrossingReportHandler. */
        occurredAtMs: Long = System.currentTimeMillis(),
        /** False only when called from [syncPendingCrossings], so a failed replay
         *  updates the row it came from instead of inserting another one. */
        enqueueOnFailure: Boolean = true
    ): Result<Unit> {
        val place = placeRepository.getPlace(placeId)
        if (place == null) {
            Log.e(TAG, "Cannot report crossing: place $placeId not found")
            return Result.failure(Exception("Place not found"))
        }

        // Resolve the circle(s) to report this crossing under.
        //
        // A CIRCLE place reports under its own circle.
        //
        // A PERSONAL place reports ONLY to circles it is actually SHARED to —
        // sharedCircles when we own it, wrapViaCircle when we received it. Those
        // circles can already see the place, so routing a crossing to them reveals
        // nothing new, and they are the only circles that can hold a remote
        // subscriber: the server gates alert creation on place access, which for a
        // personal place means the OWNER or someone the place is shared with. With
        // no shares there is provably no remote subscriber for the blind server to
        // route to.
        //
        // An ACTIVE ALERT'S OWN routing circle is deliberately NOT a source here, and
        // adding it is a privacy break, not a convenience. A personal place can
        // be homed to ANY circle the user belongs to, so routing by the alert's circle
        // would encrypt+hash "this user entered place X" under a circle that cannot
        // see the place and was never shared it — exposing a private place to both
        // the server and that circle. It is also unnecessary: the subscriber's own
        // device detects the crossing locally (PlaceAlertEvaluator), so there is
        // nothing left to route.
        val targetCircles: List<String> =
            if (place.circleId == PlaceRepository.PERSONAL_CIRCLE_ID) {
                buildList {
                    place.sharedCircles?.split(",")?.forEach { c ->
                        c.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                    place.wrapViaCircle?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }.distinct()
                    // Only circles this device is actually a member of — i.e. holds the
                    // key for. A listed circle we're not in (or lack the key for) would
                    // throw on the circle-keyed encrypt/hash, so drop it.
                    .filter { cryptoManager.hasCircleKey(it) }
            } else {
                listOf(place.circleId)
            }

        if (targetCircles.isEmpty()) {
            // An unshared personal place has no remote subscriber. Its alerts are
            // evaluated on the subscriber's own device, so nothing is emitted and
            // no boundary_event is created — the crossing never leaves the phone.
            Log.d(TAG, "reportCrossingFromMonitor: place $placeId not shared to any circle — nothing to emit")
            return Result.success(Unit)
        }

        // Report under each target circle. Each carries its own privacy-posture gate
        // (a boundary crossing is a PRESENCE signal, so a PAUSED or REALTIME-OFF circle
        // is skipped and an unknown/unprimed posture fails CLOSED) and its own
        // circle-keyed hash + encryption. Detection still ran locally regardless.
        var lastError: Throwable? = null
        var anySucceeded = false
        for (circleId in targetCircles) {
            if (!mayEmitPresenceTo(circleId)) {
                Log.d(TAG, "reportCrossingFromMonitor: circle $circleId paused/realtime-off/unknown — suppressing crossing emit")
                continue
            }
            when (replayGate(circleId, occurredAtMs)) {
                LocationRepository.PauseGate.SKIP -> {
                    Log.d(TAG, "reportCrossingFromMonitor: circle $circleId — it happened while not sharing there; never sent")
                    continue
                }
                LocationRepository.PauseGate.HOLD -> {
                    Log.d(TAG, "reportCrossingFromMonitor: circle $circleId — a late crossing the pause record can't place yet; held")
                    lastError = CrossingNotPlacedException()
                    continue
                }
                LocationRepository.PauseGate.SEND -> Unit
            }
            // Held, not dropped, while this device lacks the circle's current key:
            // what it would seal now, a member removed since could read. The report is
            // queued below and sealed when the replay finds the key has arrived.
            if (!circleRepository.holdsCurrentKey(circleId)) {
                Log.d(TAG, "reportCrossingFromMonitor: circle $circleId — waiting for its current key; holding the crossing")
                lastError = KeyNotCurrentException()
                continue
            }
            reportCrossing(
                placeId = placeId,
                circleId = circleId,
                crossingUserId = userId,
                eventType = crossingType,
                occurredAtMs = occurredAtMs
            ).onFailure { lastError = it }.onSuccess { anySucceeded = true }
        }

        // Queue for retry when the report reached NOBODY: a report dropped on
        // failure leaves an alert that never fired indistinguishable from one
        // nobody subscribed to.
        //
        // Gated on `!anySucceeded`, not on `lastError != null`, so a partial
        // failure across several circles does not replay to the circles that
        // already got it. The partial case therefore loses the failed circle's
        // report: the alternative is a per-circle queue whose two replay modes
        // are more likely to be wrong than that case is to occur. Most personal
        // places are shared to one circle or none.
        //
        // `enqueueOnFailure` is false when this IS the replay: a failed replay
        // increments the existing row's retry count rather than inserting a
        // second one, or the queue grows a row per attempt.
        if (enqueueOnFailure && lastError != null && !anySucceeded) {
            try {
                pendingCrossingDao.insert(
                    com.pingolin.track.data.local.entity.PendingCrossingEntity(
                        placeId = placeId,
                        userId = userId,
                        deviceId = deviceId,
                        crossingType = crossingType,
                        occurredAtMs = occurredAtMs,
                        createdAt = System.currentTimeMillis()
                    )
                )
                pendingCrossingDao.cleanup()
                Log.w(TAG, "Crossing report failed for every target circle — queued for retry", lastError)
            } catch (e: Exception) {
                // The queue insert failing is the one case left with nowhere to go.
                // Log at error: it is the difference between a delayed alert and a
                // lost one.
                Log.e(TAG, "Crossing report failed AND could not be queued — alert lost", e)
            }
        }

        // Nothing errored (or every circle was posture-suppressed, which is not a
        // failure — there was simply nothing to send).
        return lastError?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    /**
     * Replay queued crossing reports. Mirrors `LocationRepository.syncPendingLocations`.
     *
     * Returns the number of crossings that reached the server. Oldest first, because
     * a crossing's order is its meaning: an enter replayed after its own exit tells
     * a subscriber somebody is still somewhere they have left.
     *
     * Each row is deleted on success and on a PERMANENT refusal, and kept only for
     * failures that could plausibly succeed later. A row whose place has been
     * deleted, or that is no longer shared to any circle, can never succeed — the
     * live path returns `success(Unit)` for the no-target case, so those drain
     * cleanly rather than sitting in the queue until the age bound evicts them.
     *
     * Crossings older than the age bound are dropped by `cleanup()` rather than
     * sent: see `PendingCrossingDao.MAX_AGE_MS` for why a stale crossing is worse
     * than no crossing.
     */
    suspend fun syncPendingCrossings(): Result<Int> {
        return try {
            if (!sessionManager.isLoggedIn()) return Result.success(0)

            // Evict the stale and the overflowing BEFORE reading, so this never
            // replays something the bound has already decided is not worth sending.
            pendingCrossingDao.cleanup()

            val pending = pendingCrossingDao.getPending()
            if (pending.isEmpty()) return Result.success(0)

            var sent = 0
            for (row in pending) {
                val result = reportCrossingFromMonitor(
                    placeId = row.placeId,
                    userId = row.userId,
                    deviceId = row.deviceId,
                    crossingType = row.crossingType,
                    occurredAtMs = row.occurredAtMs,
                    enqueueOnFailure = false
                )
                if (result.isSuccess) {
                    pendingCrossingDao.deleteById(row.id)
                    sent++
                } else {
                    // Kept, and counted. The count is for diagnostics and backoff
                    // only — eviction is by age and size, for the reason spelled
                    // out in PendingLocationDao: a retry-count cap is exhausted in
                    // about an hour and then discards everything.
                    pendingCrossingDao.incrementRetry(row.id)
                }
            }
            if (sent > 0) Log.i(TAG, "Replayed $sent queued crossing report(s)")
            Result.success(sent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to drain queued crossings", e)
            Result.failure(e)
        }
    }

    /**
     * A crossing that happened while sharing with [circleId] was paused, or real-time sharing was off,
     * never goes, however late it is replayed: it is judged against the record of not-sharing spans at
     * its own time, as a late point is ([LocationRepository.pauseGate]). Crossing times are sealed, so
     * the server can judge a replay only by the posture now, and a pause set and ended on another
     * phone while this one was offline would be missed. One just detected goes by the posture now
     * ([mayEmitPresenceTo]); a late one the record can't place yet waits in the queue.
     */
    internal fun replayGate(circleId: String, occurredAtMs: Long): LocationRepository.PauseGate {
        if (circleId == LocationRepository.SELF_CIRCLE_ID || circleId == LocationRepository.SELF_CIRCLE_UUID) {
            return LocationRepository.PauseGate.SEND
        }
        val inPause = try {
            pauseIntervals.covers(circleId, occurredAtMs)
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            Log.w(TAG, "Pause record unreadable (${e.javaClass.simpleName}): a crossing to $circleId may wait")
            null
        }
        if (inPause == true) return LocationRepository.PauseGate.SKIP
        if (wallNow() - occurredAtMs <= LocationRepository.LATE_AFTER_MS) return LocationRepository.PauseGate.SEND
        return if (inPause == null || !pauseIntervals.syncedSince(circleId, occurredAtMs)) {
            LocationRepository.PauseGate.HOLD
        } else {
            LocationRepository.PauseGate.SEND
        }
    }

    /** The wall clock a crossing is judged late by ([replayGate]). Tests set it. */
    internal var wallNow: () -> Long = System::currentTimeMillis

    /**
     * Whether a presence signal (a boundary crossing OR a destination auto-arrival) may be
     * emitted to [circleId] given its privacy posture — parity with the CheckInRepository /
     * LocationRepository send gates. A presence event tells a subscriber "this user is/was
     * here right now", so a PAUSED or REALTIME-OFF circle must not receive it. Primes the
     * local privacy row first so a missing / unknown posture fails CLOSED (returns false →
     * skip). Self circles have no server privacy row and always emit.
     *
     * This gates PRESENCE, not coordinates: the caller carries no location blob (or omits
     * it), so there is nothing to coarsen — an "approximate" circle still receives the
     * event (the user opted into sharing).
     *
     * Shared helper: [reportCrossingFromMonitor] and LocationTrackingService's destination
     * auto-arrival both route through this one implementation.
     */
    suspend fun mayEmitPresenceTo(circleId: String): Boolean {
        // The legacy device-wide Pause Tracking, until PauseTrackingMigration pauses
        // each circle instead: no crossing is reported anywhere meanwhile.
        if (sessionManager.isTrackingPaused()) return false
        val isSelf = circleId == LocationRepository.SELF_CIRCLE_ID ||
            circleId == LocationRepository.SELF_CIRCLE_UUID
        if (isSelf) return true
        circleRepository.ensurePrivacyPrimed(circleId)
        val privacy = try {
            privacySettingDao.get(circleId)
        } catch (e: Exception) {
            null
        }
        // Unknown / unprimed posture → fail closed.
        if (privacy == null) return false
        // Mode AND deadline — see [isSharingPausedAt]. An indefinite pause carries a
        // NULL deadline, so a deadline-only test would let presence events keep
        // flowing to a circle the user had paused.
        val paused = privacy.isSharingPausedAt()
        val realtimeOff = privacy.shareRealtime == false
        return !(paused || realtimeOff)
    }


}

/** A late crossing the record of pauses can't place yet: kept in the queue, not sent. */
class CrossingNotPlacedException : Exception("A late crossing the pause record can't place yet; held")

/**
 * Decrypted boundary event for display.
 */
data class DecryptedBoundaryEvent(
    val id: String,
    val placeId: String,
    val circleId: String,
    val userId: String,
    val deviceId: String,
    val eventType: String, // "enter" or "exit"
    val timestamp: Long
)
