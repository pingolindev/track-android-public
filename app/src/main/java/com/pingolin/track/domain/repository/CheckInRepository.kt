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

import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.local.entity.sendsBubble
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.LiveTime
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckInRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val checkInDao: CheckInDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    // Same source LocationRepository.coarsenRadiusMetersFor reads: the local privacy
    // row (privacySettingDao) primed via CircleRepository. Used so a check-in honors
    // the circle's pause / realtime-off / approximate posture before attaching a fix.
    private val privacySettingDao: PrivacySettingDao,
    private val circleRepository: CircleRepository,
    /** Where a check-in made while this phone is behind waits. */
    private val pendingChanges: PendingChanges,
    /** When this account paused each circle: a check-in made during a pause carries no position there. */
    private val pauseIntervals: com.pingolin.track.util.PauseIntervals,
    /** How wide a bubble each circle got when: a check-in's position goes no finer than the one then. */
    private val approximateIntervals: com.pingolin.track.util.ApproximateIntervals,
) {
    companion object {
        /** A check-in made longer ago than this is placed against the record of pauses. */
        private const val LATE_CHECK_IN_MS = 2 * 60_000L

        const val TYPE_SAFE_ARRIVAL = "safe_arrival"
        const val TYPE_ARRIVED = "arrived"
        const val TYPE_GENERAL = "general"
        const val TYPE_EMERGENCY_OK = "emergency_ok"

        /** Milliseconds per day for day bucket calculation */
        private const val MS_PER_DAY = 86400000L

        /** The most the server lists at once: the check-in rules' refresh reads pages of it. */
        internal const val CHECK_IN_PAGE = 100

        /** How far back the check-in rules' refresh reads: past the longest limit (24 hours), and the day bucket's edge. */
        internal const val RECENT_MS = 26L * 3_600_000L

        /** A bound on the pages one check-in rules' refresh follows. */
        internal const val MAX_RECENT_PAGES = 5

        /** A bound on the pages one read of this account's newest check-in follows (1,000 check-ins). */
        internal const val MAX_OWN_PAGES = 10
    }

    fun observeCheckInsByCircle(circleId: String): Flow<List<CheckInEntity>> =
        checkInDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /**
     * Compute day bucket from timestamp (days since Unix epoch).
     */
    private fun computeDayBucket(timestampMs: Long): Int =
        (timestampMs / MS_PER_DAY).toInt()

    /**
     * Encrypt timestamp as a Long (milliseconds since epoch).
     */
    private fun encryptTimestamp(circleId: String, timestampMs: Long): EncryptedPayload {
        val encrypted = cryptoManager.encryptDataV2(circleId, timestampMs.toString(), "timestamp")
        return EncryptedPayload(
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext
        )
    }

    /**
     * Whether a check-in made now to [circleId] could carry this phone's position at all, by
     * [positionAllowed]'s posture rules: never while sharing there is paused, real-time sharing is
     * off, the posture can't be told, or the record of pauses says now is in a pause. The map offers
     * the check-in screen, the one that sends a position, only then (never promise a position the
     * phone will withhold).
     */
    suspend fun sendsPositionNow(circleId: String): Boolean {
        if (circleId == LocationRepository.SELF_CIRCLE_ID || circleId == LocationRepository.SELF_CIRCLE_UUID) return true
        circleRepository.ensurePrivacyPrimed(circleId)
        val privacy = try { privacySettingDao.get(circleId) } catch (e: Exception) { null } ?: return false
        if (privacy.isSharingPausedAt() || privacy.shareRealtime == false) return false
        return runCatching { pauseIntervals.covers(circleId, System.currentTimeMillis()) }.getOrNull() == false
    }

    /**
     * The fix a check-in to [circleId] may carry as the circle's setting is now, or null
     * ([encryptCheckInLocation] has the rules). A check-in kept while this phone is behind
     * stores what this allows when queued, and it is applied again when sent, so the stricter
     * of the two goes out.
     */
    private suspend fun positionAllowed(circleId: String, latitude: Double, longitude: Double, capturedAtMs: Long): Pair<Double, Double>? {
        val isSelf = circleId == LocationRepository.SELF_CIRCLE_ID ||
            circleId == LocationRepository.SELF_CIRCLE_UUID
        val privacy = if (isSelf) {
            null
        } else {
            circleRepository.ensurePrivacyPrimed(circleId)
            try {
                privacySettingDao.get(circleId)
            } catch (e: Exception) {
                null
            }
        }

        // Mode AND deadline, see [isSharingPausedAt]. An indefinite pause carries a
        // NULL deadline, so a deadline-only test would read it as un-paused and
        // attach an exact fix to the check-in.
        val paused = privacy?.isSharingPausedAt() == true
        val realtimeOff = privacy?.shareRealtime == false
        // Non-self circle whose posture is paused / realtime-off / unknown (unprimed) →
        // omit the fix entirely (fail closed for the unknown case).
        if (!isSelf && (privacy == null || paused || realtimeOff)) return null
        // Made during a pause there that this phone knows of, however fresh, or while the record of
        // pauses can't be read: no position either. One made a while ago (a check-in that waited)
        // needs a privacy row read after it too; never a wait: the check-in itself still goes.
        if (!isSelf) {
            val inPause = runCatching { pauseIntervals.covers(circleId, capturedAtMs) }.getOrNull()
            val late = System.currentTimeMillis() - capturedAtMs > LATE_CHECK_IN_MS
            if (inPause != false || (late && !pauseIntervals.syncedSince(circleId, capturedAtMs))) return null
        }

        // Coarsen for an "approximate" circle (0.0 = identity for exact/self); mirrors
        // LocationRepository.coarsenRadiusMetersFor's radius math (1-25 mile clamp).
        val radiusMeters = if (isSelf || privacy == null || !privacy.sendsBubble()) {
            0.0
        } else {
            (privacy.approximateRadiusMiles ?: LocationRepository.DEFAULT_BUBBLE_RADIUS_MILES)
                .coerceIn(1, 25) * LocationRepository.METERS_PER_MILE
        }
        // And no finer than the bubble in force when the check-in was made, if it goes later; one
        // made a while ago gets the widest while the server hasn't told this phone of that time yet,
        // or the record can't be read.
        val lateCheckIn = System.currentTimeMillis() - capturedAtMs > LATE_CHECK_IN_MS
        val thenMiles = if (isSelf) null else try {
            if (lateCheckIn && !approximateIntervals.knowsSince(circleId, capturedAtMs)) LocationRepository.MAX_BUBBLE_RADIUS_MILES
            else approximateIntervals.radiusAt(circleId, capturedAtMs)
        } catch (e: com.pingolin.track.util.StoreUnreadable) {
            if (lateCheckIn) LocationRepository.MAX_BUBBLE_RADIUS_MILES else null
        }
        val thenMeters = thenMiles?.coerceIn(1, LocationRepository.MAX_BUBBLE_RADIUS_MILES)
            ?.let { it * LocationRepository.METERS_PER_MILE } ?: 0.0
        return LocationRepository.coarsenLocation(latitude, longitude, maxOf(radiusMeters, thenMeters))
    }

    /**
     * Encrypt a check-in's location fix while honoring the circle's privacy posture
     * (parity with LocationRepository's send paths and the iOS app), or return null
     * when no fix may be attached:
     *  - self circle → exact (there is no server privacy row);
     *  - PAUSED / REALTIME-OFF / unknown-posture (unprimed) circle → null: the check-in
     *    still sends, just with no coordinates;
     *  - "approximate" circle → coarsened to its bubble BEFORE encryption so an exact
     *    check-in point never reaches an approximate circle's viewers;
     *  - exact circle → sent as-is.
     *
     * Resolves the SAME local privacy row [LocationRepository.coarsenRadiusMetersFor]
     * reads, priming it first (GET + local mirror) so a missing row (fresh login /
     * headless) fails CLOSED, omit the fix, rather than leaking an exact point. The
     * posture is evaluated at SEND time, so a queued (offline) check-in that syncs later
     * honors the posture that is current then.
     */
    private suspend fun encryptCheckInLocation(
        circleId: String,
        latitude: Double,
        longitude: Double,
        timestampMs: Long
    ): EncryptedPayload? {
        val (fixLat, fixLng) = positionAllowed(circleId, latitude, longitude, timestampMs) ?: return null
        val location = uniffi.pingolin_mobile.Location(
            latitude = fixLat,
            longitude = fixLng,
            altitude = null,
            speed = null,
            heading = null,
            accuracy = null,
            timestamp = timestampMs,
            batteryLevel = null,
            isCharging = false
        )
        val encrypted = cryptoManager.encryptLocation(circleId, location)
        return EncryptedPayload(
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext
        )
    }

    /**
     * Decrypt timestamp from encrypted payload.
     */
    private fun decryptTimestamp(circleId: String, encrypted: EncryptedPayload): Long? {
        return try {
            val decrypted = cryptoManager.decryptDataV2(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                ),
                "timestamp"
            )
            decrypted.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt user ID from encrypted payload.
     */
    private fun decryptUserId(circleId: String, encrypted: EncryptedPayload): String? {
        return try {
            cryptoManager.decryptDataV2(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                ),
                "user_id"
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check in. With [keepIfBehind] (the person's tap), a check-in this phone can't send because
     * it is behind on [circleId] is kept and sent by itself once it catches up, with the time the
     * person checked in; the failure is then [com.pingolin.track.domain.pending.QueuedForSync].
     * A waiting change's run ([pendingRun]) passes that time as [checkedInAt].
     */
    suspend fun createCheckIn(
        circleId: String,
        type: String,
        message: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        placeId: String? = null,
        keepIfBehind: Boolean = false,
        checkedInAt: Long? = null,
        pendingRun: PendingRun? = null,
    ): Result<CheckInEntity> {
        return try {
            val userId = sessionManager.getUserId() ?: return Result.failure(Exception("No user"))
            val now = checkedInAt ?: System.currentTimeMillis()
            // Nothing is sealed under a key a removed member may hold.
            try {
                circleRepository.requireCurrentKey(circleId)
            } catch (e: KeyNotCurrentException) {
                if (!keepIfBehind || pendingRun != null) throw e
                return queueCheckIn(circleId, type, message, latitude, longitude, placeId, now)
            }
            val deviceId = sessionManager.getDeviceId()
            val dayBucket = computeDayBucket(now)

            // E2EE: Encrypt user ID with circle key
            val encryptedUserIdData = cryptoManager.encryptDataV2(circleId, userId, "user_id")
            val encryptedUserId = EncryptedPayload(
                nonce = encryptedUserIdData.nonce,
                ciphertext = encryptedUserIdData.ciphertext
            )

            // E2EE: Encrypt device ID with circle key
            val encryptedDeviceIdData = cryptoManager.encryptDataV2(circleId, deviceId, "device_id")
            val encryptedDeviceId = EncryptedPayload(
                nonce = encryptedDeviceIdData.nonce,
                ciphertext = encryptedDeviceIdData.ciphertext
            )

            // E2EE: Encrypt timestamp with circle key
            val encryptedTimestamp = encryptTimestamp(circleId, now)

            // Encrypt location if provided, honoring this circle's privacy posture.
            // See [encryptCheckInLocation]: self is exact; a paused / realtime-off /
            // unknown-posture circle omits the fix; an "approximate" circle is coarsened
            // to its bubble BEFORE encryption so an exact check-in point never reaches an
            // approximate circle's viewers.
            val encryptedLocation: EncryptedPayload? =
                if (latitude != null && longitude != null) {
                    encryptCheckInLocation(circleId, latitude, longitude, now)
                } else {
                    null
                }

            // SECURITY: Encrypt message with circle key
            var encryptedMessage: EncryptedPayload? = null
            if (message != null) {
                val encrypted = cryptoManager.encryptDataV2(circleId, message, "message")
                encryptedMessage = EncryptedPayload(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                )
            }

            // E2EE: Derive user pseudonym for server-side filtering
            val userPseudonym = cryptoManager.deriveUserPseudonym(circleId, userId)

            val response = tracksApi.createCheckIn(
                CreateCheckInRequest(
                    deviceId = deviceId,
                    circleId = circleId,
                    type = type,
                    encryptedMessage = encryptedMessage,
                    encryptedLocation = encryptedLocation,
                    placeId = placeId,
                    encryptedUserId = encryptedUserId,
                    encryptedDeviceId = encryptedDeviceId,
                    dayBucket = dayBucket,
                    encryptedTimestamp = encryptedTimestamp,
                    userPseudonym = userPseudonym,
                    clientRequestId = pendingRun?.requestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val dto = response.body()!!.data!!

                // Decrypt message from server response
                val decryptedMessage = dto.encryptedMessage?.let { encrypted ->
                    try {
                        cryptoManager.decryptDataV2(
                            circleId,
                            uniffi.pingolin_mobile.EncryptedData(
                                nonce = encrypted.nonce,
                                ciphertext = encrypted.ciphertext
                            ),
                            "message"
                        )
                    } catch (e: Exception) {
                        message // Fall back to original message if decryption fails
                    }
                } ?: message

                // Decrypt user ID from response
                val decryptedUserId = decryptUserId(circleId, dto.encryptedUserId) ?: userId

                // Decrypt timestamp from response
                val timestamp = decryptTimestamp(circleId, dto.encryptedTimestamp) ?: now

                val checkIn = CheckInEntity(
                    id = dto.id,
                    circleId = dto.circleId ?: circleId,
                    userId = decryptedUserId,
                    type = dto.checkInType,
                    message = decryptedMessage,
                    latitude = latitude,
                    longitude = longitude,
                    placeId = dto.placeId,
                    timestamp = timestamp,
                    isSynced = true
                )
                checkInDao.insert(checkIn)
                Result.success(checkIn)
            } else if (pendingRun != null) {
                // A waiting change retries by itself, with its own id; a copy stored for
                // the sync below would go out a second time.
                Result.failure(ApiErrors.refused(response, "Couldn't send the check-in just now."))
            } else {
                // Store locally for later sync
                val checkIn = CheckInEntity(
                    id = UUID.randomUUID().toString(),
                    circleId = circleId,
                    userId = userId,
                    type = type,
                    message = message,
                    latitude = latitude,
                    longitude = longitude,
                    placeId = placeId,
                    timestamp = now,
                    isSynced = false
                )
                checkInDao.insert(checkIn)
                Result.failure(Exception("Failed to send check-in, saved locally"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Keep a check-in until this phone catches up on [circleId]: its type, message, place and
     * time, and its position as the circle's setting allows NOW, coarsened, or none. Nothing
     * is sealed.
     */
    private suspend fun queueCheckIn(
        circleId: String,
        type: String,
        message: String?,
        latitude: Double?,
        longitude: Double?,
        placeId: String?,
        checkedInAt: Long,
    ): Result<CheckInEntity> {
        val position = if (latitude != null && longitude != null) positionAllowed(circleId, latitude, longitude, checkedInAt) else null
        val circleName = pendingChanges.circleName(circleId)
        return pendingChanges.queue(
            circleId,
            PendingInputs.CheckIn(circleName, type, message, position?.first, position?.second, placeId, checkedInAt),
        )
    }

    suspend fun createQuickCheckIn(circleId: String, type: String, keepIfBehind: Boolean = false): Result<CheckInEntity> {
        return createCheckIn(circleId, type, keepIfBehind = keepIfBehind)
    }

    suspend fun createCheckInWithLocation(
        circleId: String,
        type: String,
        latitude: Double,
        longitude: Double,
        keepIfBehind: Boolean = false,
    ): Result<CheckInEntity> {
        return createCheckIn(
            circleId = circleId,
            type = type,
            latitude = latitude,
            longitude = longitude,
            keepIfBehind = keepIfBehind,
        )
    }

    suspend fun createCheckInAtPlace(
        circleId: String,
        placeId: String,
        message: String? = null,
        keepIfBehind: Boolean = false,
    ): Result<CheckInEntity> {
        return createCheckIn(
            circleId = circleId,
            type = TYPE_SAFE_ARRIVAL,
            message = message,
            placeId = placeId,
            keepIfBehind = keepIfBehind,
        )
    }

    suspend fun refreshCheckIns(circleId: String): Result<List<CheckInEntity>> {
        return try {
            val response = tracksApi.getCircleCheckIns(circleId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                // Per-check-in decrypt is pure CPU (UniFFI); move it off the Main
                // thread so a large check-in set can't jank the caller.
                val checkIns = withContext(Dispatchers.Default) {
                    response.body()!!.data!!.mapNotNull { dto -> open(circleId, dto) }
                }
                checkIns.forEach { checkInDao.insert(it) }
                Result.success(checkIns)
            } else {
                Result.failure(Exception("Failed to fetch check-ins"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** A listed check-in as this phone reads it, or null when its sealed user id or time doesn't open here. */
    private fun open(circleId: String, dto: CheckInResponse): CheckInEntity? {
        // E2EE: Decrypt user ID
        val decryptedUserId = decryptUserId(circleId, dto.encryptedUserId) ?: return null

        // E2EE: Decrypt timestamp. The sealed time is required: one that doesn't open is
        // tampering or a key not held, never "no time", and the server's day bucket would
        // date the row at its will. Dropped, as an unopenable user id is.
        val timestamp = decryptTimestamp(circleId, dto.encryptedTimestamp) ?: return null

        // SECURITY: Decrypt message with circle key
        val decryptedMessage = dto.encryptedMessage?.let { encrypted ->
            try {
                cryptoManager.decryptDataV2(
                    circleId,
                    uniffi.pingolin_mobile.EncryptedData(
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext
                    ),
                    "message"
                )
            } catch (e: Exception) {
                null // Decryption failed, message unavailable
            }
        }

        // Decrypt location if present
        val location = dto.encryptedLocation?.let { encrypted ->
            try {
                cryptoManager.decryptLocation(
                    circleId,
                    uniffi.pingolin_mobile.EncryptedData(
                        nonce = encrypted.nonce,
                        ciphertext = encrypted.ciphertext
                    )
                )
            } catch (e: Exception) {
                null
            }
        }

        return CheckInEntity(
            id = dto.id,
            circleId = dto.circleId ?: circleId,
            userId = decryptedUserId,
            type = dto.checkInType,
            message = decryptedMessage,
            latitude = location?.latitude,
            longitude = location?.longitude,
            placeId = dto.placeId,
            timestamp = timestamp,
            isSynced = true
        )
    }

    /**
     * What [refreshRecentCheckIns] read: the check-ins that opened here, and how many within its
     * reach didn't.
     */
    data class RecentCheckIns(val read: List<CheckInEntity>, val unopened: Int) {
        /**
         * Every check-in within reach opened here. A member's silence says something only then:
         * one that didn't open (a key this phone doesn't hold yet, say) may be exactly their
         * check-in, and the location refresh holds its own alerts to the same rule (isComplete).
         */
        val whole: Boolean get() = unopened == 0
    }

    /**
     * [circleId]'s check-ins of about the last day, for the check-in rules
     * (MonitoringManager.evaluateCheckInRules), each read as [refreshCheckIns] reads it and kept
     * here. Pages of [CHECK_IN_PAGE], newest first, until a page comes back short, one reaches
     * back past the day of [nowMs] minus [windowMs] ([RECENT_MS]: longer than any rule's limit),
     * or [MAX_RECENT_PAGES]. A page that fails fails the whole read: a read cut short can be
     * missing exactly the check-in that matters, and a failed read is never "no check-in". A
     * check-in older than that reach doesn't count against [RecentCheckIns.whole]: no rule's
     * verdict turns on it. The alert history reads further back the same way (HistoryCheckIns).
     */
    suspend fun refreshRecentCheckIns(
        circleId: String,
        nowMs: Long = System.currentTimeMillis(),
        windowMs: Long = RECENT_MS,
    ): Result<RecentCheckIns> {
        return try {
            val floorDay = Math.floorDiv(nowMs - windowMs, MS_PER_DAY).toInt()
            val read = mutableListOf<CheckInEntity>()
            var unopened = 0
            var last: CheckInResponse? = null
            for (page in 1..MAX_RECENT_PAGES) {
                val response = tracksApi.getCircleCheckIns(circleId, CHECK_IN_PAGE, last?.dayBucket, last?.id)
                val rows = response.body()?.takeIf { response.isSuccessful && it.success }?.data
                    ?: return Result.failure(Exception("Failed to fetch check-ins"))
                val opened = withContext(Dispatchers.Default) { rows.map { it to open(circleId, it) } }
                for ((dto, checkIn) in opened) {
                    if (checkIn != null) read += checkIn else if (dto.dayBucket >= floorDay) unopened++
                }
                if (rows.size < CHECK_IN_PAGE || rows.minOf { it.dayBucket } < floorDay) break
                last = rows.last()
            }
            read.forEach { checkInDao.insert(it) }
            Result.success(RecentCheckIns(read, unopened))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * This account's newest check-in in [circleId] that the server has (epoch ms), for the countdown
     * on its own check-in rules (CheckInCountdown); null when it has none from [sinceMs]'s day on.
     *
     * Read from the server, newest first, so a check-in from another of this account's phones
     * counts and one still waiting on this phone doesn't: the circle's phones see only the
     * server's. Only one from a rule's start ([sinceMs]) on can move a deadline, so the read stops
     * once a page goes back past that day, or past the day of the newest found (a day's check-ins
     * come in no order of time). Each check-in read is kept here, as [refreshCheckIns] keeps them.
     * A failed page, a check-in in that range that doesn't open (it may be this account's newest),
     * or more than [MAX_OWN_PAGES] pages: a failure, so no time is shown rather than a wrong one. A
     * check-in sealed further ahead of [nowMs] than a clock may be off (LiveTime) isn't counted, as
     * the circle's phones don't count it.
     */
    suspend fun newestOwnCheckIn(circleId: String, sinceMs: Long, nowMs: Long = System.currentTimeMillis()): Result<Long?> {
        return try {
            val self = sessionManager.getUserId()?.let { AccountIds.storeKey(it) }
                ?: return Result.failure(IllegalStateException("Not signed in"))
            // Day buckets below this hold nothing that can move the deadline.
            var floorDay = Math.floorDiv(sinceMs, MS_PER_DAY).toInt()
            var newest: Long? = null
            var last: CheckInResponse? = null
            for (page in 1..MAX_OWN_PAGES) {
                val response = tracksApi.getCircleCheckIns(circleId, CHECK_IN_PAGE, last?.dayBucket, last?.id)
                val rows = response.body()?.takeIf { response.isSuccessful && it.success }?.data
                    ?: return Result.failure(Exception("Failed to fetch check-ins"))
                val opened = withContext(Dispatchers.Default) { rows.map { it to open(circleId, it) } }
                for ((dto, checkIn) in opened) {
                    if (dto.dayBucket < floorDay) continue
                    checkIn ?: return Result.failure(Exception("A check-in that may be this account's doesn't open here"))
                    checkInDao.insert(checkIn)
                    if (AccountIds.storeKey(checkIn.userId) != self || !LiveTime.plausible(checkIn.timestamp, nowMs)) continue
                    if (newest == null || checkIn.timestamp > newest) newest = checkIn.timestamp
                    // A newer one would be on this day or a later one.
                    floorDay = maxOf(floorDay, dto.dayBucket)
                }
                if (rows.size < CHECK_IN_PAGE || rows.minOf { it.dayBucket } < floorDay) return Result.success(newest)
                last = rows.last()
            }
            Result.failure(Exception("Too many check-ins to settle the newest"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Each member's newest check-in held here for [circleId] (epoch ms), any type, keyed by
     * [AccountIds.storeKey] of the id it was sealed with. Any key version that opened counts. One
     * sealed further ahead of [nowMs] than a clock may be off (LiveTime) doesn't: it would keep
     * the member's rules quiet until that date.
     */
    suspend fun lastCheckIns(circleId: String, nowMs: Long = System.currentTimeMillis()): Map<String, Long> =
        checkInDao.lastByUser(circleId, nowMs + LiveTime.MAX_FUTURE_SKEW_MS)
            .groupBy({ AccountIds.storeKey(it.userId) }, { it.lastAt })
            .mapValues { (_, times) -> times.max() }

    suspend fun syncPendingCheckIns(): Result<Int> {
        return try {
            val pending = checkInDao.getPendingSync()
            var syncedCount = 0

            for (checkIn in pending) {
                try {
                    // Kept until this device holds the circle's current key.
                    if (!circleRepository.holdsCurrentKey(checkIn.circleId)) continue
                    val now = checkIn.timestamp
                    val dayBucket = computeDayBucket(now)

                    // E2EE: Encrypt user ID with circle key
                    val encryptedUserIdData = cryptoManager.encryptDataV2(checkIn.circleId, checkIn.userId, "user_id")
                    val encryptedUserId = EncryptedPayload(
                        nonce = encryptedUserIdData.nonce,
                        ciphertext = encryptedUserIdData.ciphertext
                    )

                    // E2EE: Encrypt device ID with circle key
                    val deviceId = sessionManager.getDeviceId()
                    val encryptedDeviceIdData = cryptoManager.encryptDataV2(checkIn.circleId, deviceId, "device_id")
                    val encryptedDeviceId = EncryptedPayload(
                        nonce = encryptedDeviceIdData.nonce,
                        ciphertext = encryptedDeviceIdData.ciphertext
                    )

                    // E2EE: Encrypt timestamp with circle key
                    val encryptedTimestamp = encryptTimestamp(checkIn.circleId, now)

                    // Encrypt location if present, honoring the circle's privacy posture
                    // at SEND time (it may have changed since the check-in was queued),
                    // the SAME gate as createCheckIn, so an offline-then-synced check-in
                    // can't leak an exact fix to a paused / realtime-off / approximate
                    // circle. See [encryptCheckInLocation].
                    val encryptedLocation: EncryptedPayload? =
                        if (checkIn.latitude != null && checkIn.longitude != null) {
                            encryptCheckInLocation(
                                checkIn.circleId,
                                checkIn.latitude,
                                checkIn.longitude,
                                checkIn.timestamp
                            )
                        } else {
                            null
                        }

                    // SECURITY: Encrypt message with circle key
                    var encryptedMessage: EncryptedPayload? = null
                    if (checkIn.message != null) {
                        val encrypted = cryptoManager.encryptDataV2(checkIn.circleId, checkIn.message, "message")
                        encryptedMessage = EncryptedPayload(
                            nonce = encrypted.nonce,
                            ciphertext = encrypted.ciphertext
                        )
                    }

                    // E2EE: Derive user pseudonym for server-side filtering
                    val userPseudonym = cryptoManager.deriveUserPseudonym(checkIn.circleId, checkIn.userId)

                    val response = tracksApi.createCheckIn(
                        CreateCheckInRequest(
                            deviceId = deviceId,
                            circleId = checkIn.circleId,
                            type = checkIn.type,
                            encryptedMessage = encryptedMessage,
                            encryptedLocation = encryptedLocation,
                            placeId = checkIn.placeId,
                            encryptedUserId = encryptedUserId,
                            encryptedDeviceId = encryptedDeviceId,
                            dayBucket = dayBucket,
                            encryptedTimestamp = encryptedTimestamp,
                            userPseudonym = userPseudonym
                        )
                    )

                    if (response.isSuccessful) {
                        checkInDao.markSynced(checkIn.id)
                        syncedCount++
                    }
                } catch (e: Exception) {
                    // Continue with next check-in
                }
            }

            Result.success(syncedCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
