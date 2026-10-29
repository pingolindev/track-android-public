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
import com.pingolin.track.data.local.dao.DestinationDao
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.local.entity.sendsBubble
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DestinationRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val destinationDao: DestinationDao,
    private val circleMemberDao: com.pingolin.track.data.local.dao.CircleMemberDao,
    private val placeDao: com.pingolin.track.data.local.dao.PlaceDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    // Same source LocationRepository.coarsenRadiusMetersFor reads: the local privacy
    // row (privacySettingDao) primed via CircleRepository. Used so an arrival report
    // honors the circle's pause / realtime-off / approximate posture before attaching
    // a fix — parity with CheckInRepository.encryptCheckInLocation.
    private val privacySettingDao: com.pingolin.track.data.local.dao.PrivacySettingDao,
    private val circleRepository: CircleRepository,
    /** Where a destination or an arrival made while this phone is behind waits. */
    private val pendingChanges: PendingChanges,
) {
    companion object {
        /** GET /api/v1/destinations returns at most this many rows. */
        const val SERVER_LIST_CAP = 100
        const val STATUS_ACTIVE = "active"
        const val STATUS_ARRIVED = "arrived"
        const val STATUS_CANCELLED = "cancelled"
        const val STATUS_EXPIRED = "expired"

        private const val TAG = "DestinationRepo"

        private const val MS_PER_DAY = 86400000L

        /** A destination within this distance of a saved place is described
         *  by the place's name ("place name if possible, else address").
         *  Matches the auto-arrival radius so the two notions of "at" agree. */
        private const val PLACE_MATCH_RADIUS_METERS = 100.0f

        /**
         * Cross-platform wire encoding for the destination `"eta"` field: epoch
         * milliseconds as a plain base-10 string. This is the ONLY place the
         * precise ETA is encoded for the wire; both create/update sites below
         * encrypt exactly this under the `"eta"` AAD tag.
         *
         * iOS decrypts this field and parses it with `Int64(plaintext)`
         * (DestinationsManager.swift), so the encoding MUST be an integer string
         * it accepts. An ISO-8601 string fails that parse and the precise ETA
         * silently vanishes on iOS. Locked by DestinationRepositoryEtaContractTest.
         */
        internal fun encodeEtaFieldPlaintext(etaMs: Long): String = etaMs.toString()

        /**
         * Of [active], this account's own live destinations ([DestinationEntity.isMine]): the only
         * ones an arrival is ever reported for by itself.
         */
        fun ownActive(active: List<DestinationEntity>, me: String?): List<DestinationEntity> =
            active.filter { it.status == STATUS_ACTIVE && it.isMine(me) }
    }

    fun observeAll(): Flow<List<DestinationEntity>> = destinationDao.observeAll().orWhenUnopenable(emptyList())

    fun observeActive(): Flow<List<DestinationEntity>> = destinationDao.observeActive().orWhenUnopenable(emptyList())

    /**
     * Observe the CURRENT user's OWN destinations (all statuses). The destinations
     * list is "my destinations": only the creator can report arrival / cancel, so the
     * actionable list shows only mine. Circle members' shared destinations are surfaced
     * on the map (color-coded by creator) and via push, not in this list. Empty when no
     * user is logged in.
     */
    fun observeMine(): Flow<List<DestinationEntity>> {
        val userId = sessionManager.getUserId() ?: return flowOf(emptyList())
        return destinationDao.observeAll().map { all -> all.filter { it.isMine(userId) } }.orWhenUnopenable(emptyList())
    }

    /**
     * The current user's OWN active destinations. Destinations are freemium: free
     * gets 1 active at a time, premium is unlimited. The create flow uses this to
     * offer a graceful cancel-and-replace at the limit instead of walking a free
     * user through the whole dialog only to 402 at Share. Empty when logged out.
     */
    suspend fun activeMine(): List<DestinationEntity> {
        val userId = sessionManager.getUserId() ?: return emptyList()
        return ownActive(destinationDao.getByStatus(STATUS_ACTIVE), userId)
    }

    /**
     * Only this account's own destination is changed, arrived at or deleted from here:
     * [NotYourDestinationException] for one it knows isn't, and [CouldNotCheck] when it can't
     * tell yet (no row here, or one whose creator isn't known yet), so a change kept to sync
     * waits for a list that says. Before anything is sealed or sent.
     */
    private suspend fun requireMine(destinationId: String) {
        val me = sessionManager.getUserId()
        when (destinationDao.getById(destinationId)?.ownership(me) ?: DestinationEntity.Ownership.UNKNOWN) {
            DestinationEntity.Ownership.MINE -> Unit
            DestinationEntity.Ownership.NOT_MINE -> throw NotYourDestinationException()
            DestinationEntity.Ownership.UNKNOWN -> throw com.pingolin.track.domain.pending.CouldNotCheck()
        }
    }

    fun observeByCircle(circleId: String): Flow<List<DestinationEntity>> =
        destinationDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /** One destination by id, from the local cache. Null when this device has
     *  never synced it — callers that need it (a notification tap on a cold
     *  start) refresh once and ask again. */
    suspend fun getById(destinationId: String): DestinationEntity? =
        destinationDao.getById(destinationId)

    private fun computeDayBucket(timestampMs: Long): Int =
        (timestampMs / MS_PER_DAY).toInt()

    private fun decryptString(circleId: String, encrypted: EncryptedPayload, fieldTag: String): String? {
        return try {
            cryptoManager.decryptDataV2(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                ),
                fieldTag
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Personalised copy for a destination notification, resolved from the
     * circle-key ciphertexts the server embeds in notification metadata
     * (encrypted_dest_name / encrypted_user_id + nonces, hex). The server
     * is E2EE-blind, so "Alice shared a destination: Home" can only be
     * assembled client-side.
     */
    data class NotificationCopy(
        val actorName: String?,
        val destLabel: String?,
        /** Destination coordinates from the locally-synced row, so a notification tap can
         *  zoom the map straight to it. Null when the row isn't available locally. */
        val destLat: Double? = null,
        val destLng: Double? = null,
    )

    /**
     * Resolve actor display name + destination label from a notification's
     * metadata map. Every part is best-effort: missing keys, an unknown
     * circle key, or a departed member just degrade to nulls and callers
     * fall back to generic copy.
     *
     * The label prefers a saved place within [PLACE_MATCH_RADIUS_METERS]
     * of the destination ("place name if possible"), falling back to the
     * decrypted destination name — which the create flow seeds with the
     * tapped place / searched address.
     */
    suspend fun resolveNotificationCopy(metadata: Map<String, String>): NotificationCopy {
        val circleId = com.pingolin.track.util.circleIdOf(metadata)
            ?: return NotificationCopy(null, null)

        val decryptedName = metadata["encrypted_dest_name"]?.takeIf { it.isNotBlank() }?.let { ct ->
            metadata["dest_name_nonce"]?.takeIf { it.isNotBlank() }?.let { nonce ->
                decryptString(circleId, EncryptedPayload(nonce = nonce, ciphertext = ct), "dest_name")
            }
        }

        val actorName = metadata["encrypted_user_id"]?.takeIf { it.isNotBlank() }?.let { ct ->
            metadata["user_id_nonce"]?.takeIf { it.isNotBlank() }?.let { nonce ->
                decryptString(circleId, EncryptedPayload(nonce = nonce, ciphertext = ct), "user_id")
            }
        }?.let { actorUserId -> circleMemberDao.get(circleId, actorUserId)?.displayName }

        // Fetch the locally-synced row once: its coordinates feed both the saved-place
        // label and the "zoom to destination on tap" extras. They exist only on that
        // row — the notification metadata carries no location ciphertext — so a row
        // already pruned locally simply falls back to the decrypted name.
        val dest = metadata["destination_id"]?.let { destId -> destinationDao.getById(destId) }
        val placeName = dest?.let { nearestPlaceName(circleId, it.destLatitude, it.destLongitude) }

        return NotificationCopy(actorName, placeName ?: decryptedName, dest?.destLatitude, dest?.destLongitude)
    }

    /**
     * Label for one of the user's own destinations (self-arrival
     * notification): saved place name when the destination sits on one,
     * else the destination's own decrypted name.
     */
    suspend fun labelForDestination(dest: DestinationEntity): String? =
        nearestPlaceName(dest.circleId, dest.destLatitude, dest.destLongitude) ?: dest.destName

    /**
     * Saved-place name within [PLACE_MATCH_RADIUS_METERS] of the destination, or
     * null when it isn't sitting on a saved place. The destinations list uses this
     * as the row title ("Home") and shows [DestinationEntity.destName] — the
     * address line the create flow seeds — as the secondary line, so a destination
     * created from a place reads as "place name as well as the address".
     */
    suspend fun placeNameForDestination(dest: DestinationEntity): String? =
        nearestPlaceName(dest.circleId, dest.destLatitude, dest.destLongitude)

    private suspend fun nearestPlaceName(circleId: String, latitude: Double, longitude: Double): String? {
        return try {
            placeDao.getByCircle(circleId)
                .filter { place ->
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        latitude, longitude, place.latitude, place.longitude, results
                    )
                    results[0] <= PLACE_MATCH_RADIUS_METERS
                }
                .minByOrNull { place ->
                    val results = FloatArray(1)
                    android.location.Location.distanceBetween(
                        latitude, longitude, place.latitude, place.longitude, results
                    )
                    results[0]
                }
                ?.name
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt destination coordinates from encrypted payload.
     */
    private fun decryptDestination(circleId: String, encrypted: EncryptedPayload): Pair<Double, Double>? {
        return try {
            val location = cryptoManager.decryptLocation(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                )
            )
            Pair(location.latitude, location.longitude)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Create a new destination. With [keepIfBehind] (the person's tap), one this phone can't
     * share because it is behind on [circleId] is kept and shared by itself once it catches up:
     * the failure is then [com.pingolin.track.domain.pending.QueuedForSync].
     */
    suspend fun createDestination(
        circleId: String,
        destName: String?,
        destLatitude: Double,
        destLongitude: Double,
        estimatedArrivalAtMs: Long? = null,
        notifyArrival: Boolean = true,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<DestinationEntity> {
        return try {
            val userId = sessionManager.getUserId() ?: return Result.failure(Exception("No user"))
            // Nothing is sealed under a key a removed member may hold.
            try {
                circleRepository.requireCurrentKey(circleId)
            } catch (e: KeyNotCurrentException) {
                if (!keepIfBehind || pendingRun != null) throw e
                val circleName = pendingChanges.circleName(circleId)
                // Waits a day, or until its arrival time passes if that comes first.
                return pendingChanges.queue(
                    circleId,
                    PendingInputs.Destination(circleName, destName, destLatitude, destLongitude, estimatedArrivalAtMs, notifyArrival),
                    expiresAt = estimatedArrivalAtMs,
                )
            }

            // E2EE: Encrypt destination coordinates
            val destLocation = uniffi.pingolin_mobile.Location(
                latitude = destLatitude,
                longitude = destLongitude,
                altitude = null,
                speed = null,
                heading = null,
                accuracy = null,
                timestamp = System.currentTimeMillis(),
                batteryLevel = null,
                isCharging = false
            )
            val encryptedDestData = cryptoManager.encryptLocation(circleId, destLocation)
            val encryptedDestination = EncryptedPayload(
                nonce = encryptedDestData.nonce,
                ciphertext = encryptedDestData.ciphertext
            )

            // E2EE: Encrypt destination name if provided
            val encryptedDestName = destName?.let {
                val encrypted = cryptoManager.encryptDataV2(circleId, it, "dest_name")
                EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
            }

            // E2EE: Encrypt ETA if provided (precise timestamp encrypted with circle key).
            // Canonical cross-platform wire encoding is epoch-millis as a string under the
            // "eta" AAD tag: iOS reads this back via Int64(plaintext), so an ISO-8601 string
            // fails its parse and the ETA silently vanishes on iOS.
            val encryptedEta = estimatedArrivalAtMs?.let { ms ->
                val encrypted = cryptoManager.encryptDataV2(circleId, encodeEtaFieldPlaintext(ms), "eta")
                EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
            }
            val etaDayBucket = estimatedArrivalAtMs?.let { computeDayBucket(it) }

            // E2EE: Encrypt user ID
            val encryptedUserIdData = cryptoManager.encryptDataV2(circleId, userId, "user_id")
            val encryptedUserId = EncryptedPayload(
                nonce = encryptedUserIdData.nonce,
                ciphertext = encryptedUserIdData.ciphertext
            )

            val response = tracksApi.createDestination(
                CreateDestinationRequest(
                    circleId = circleId,
                    encryptedDestination = encryptedDestination,
                    encryptedDestName = encryptedDestName,
                    encryptedEta = encryptedEta,
                    etaDayBucket = etaDayBucket,
                    notifyArrival = notifyArrival,
                    encryptedUserId = encryptedUserId,
                    clientRequestId = pendingRun?.requestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val dto = response.body()!!.data!!
                val entity = mapDtoToEntity(circleId, dto, userId, destName, destLatitude, destLongitude)
                destinationDao.insert(entity)
                Result.success(entity)
            } else {
                // Destinations are freemium, so this endpoint really does 402, with
                // `error_code: "quota_destinations_exceeded"` and a `quota` object.
                // Discarding the body would throw all of that away and leave the UI
                // with "Failed to create destination" — a dead end for a user one tap
                // from the thing that would fix it. Same idiom as PlaceRepository.
                Result.failure(
                    ApiErrors.toException(response, "Failed to create destination")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update a destination's status.
     */
    suspend fun updateDestination(
        destinationId: String,
        status: String? = null,
        circleId: String? = null,
        estimatedArrivalAtMs: Long? = null,
        notifyArrival: Boolean? = null
    ): Result<Unit> {
        return try {
            requireMine(destinationId)
            if (estimatedArrivalAtMs != null && circleId != null) circleRepository.requireCurrentKey(circleId)
            // E2EE: Encrypt ETA if provided. Epoch-millis string under the "eta" tag —
            // canonical cross-platform encoding (see encodeEtaFieldPlaintext for why not ISO-8601).
            val encryptedEta = if (estimatedArrivalAtMs != null && circleId != null) {
                val encrypted = cryptoManager.encryptDataV2(circleId, encodeEtaFieldPlaintext(estimatedArrivalAtMs), "eta")
                EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
            } else null
            val etaDayBucket = estimatedArrivalAtMs?.let { computeDayBucket(it) }

            val response = tracksApi.updateDestination(
                destinationId,
                UpdateDestinationRequest(
                    status = status,
                    encryptedEta = encryptedEta,
                    etaDayBucket = etaDayBucket,
                    notifyArrival = notifyArrival
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                // Update local cache
                destinationDao.getById(destinationId)?.let { existing ->
                    val updated = existing.copy(
                        status = status ?: existing.status,
                        notifyArrival = notifyArrival ?: existing.notifyArrival,
                        updatedDayBucket = computeDayBucket(System.currentTimeMillis())
                    )
                    destinationDao.update(updated)
                }
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.w(TAG, "updateDestination refused: HTTP ${response.code()} ${response.body()?.error ?: errorBody}")
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response.code(), com.pingolin.track.data.remote.dto.ApiErrors.parse(errorBody)?.errorCode, "Couldn't update the destination just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Cancel a destination.
     */
    suspend fun cancelDestination(destinationId: String): Result<Unit> {
        return updateDestination(destinationId, status = STATUS_CANCELLED)
    }

    /**
     * The fix an arrival reported to [circleId] may carry as the circle's setting is now, or
     * null ([encryptArrivalLocation] has the rules). An arrival kept while this phone is behind
     * stores what this allows when queued, and it is applied again when sent, so the stricter
     * of the two goes out.
     */
    private suspend fun arrivalPositionAllowed(circleId: String, latitude: Double, longitude: Double): Pair<Double, Double>? {
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

        val now = System.currentTimeMillis()
        // Mode AND deadline — see [isSharingPausedAt]. An indefinite pause carries a
        // NULL deadline, so a deadline-only test would read it as un-paused and
        // attach an exact fix to the destination.
        val paused = privacy?.isSharingPausedAt(now) == true
        val realtimeOff = privacy?.shareRealtime == false
        // Non-self circle whose posture is paused / realtime-off / unknown (unprimed) →
        // omit the fix entirely (fail closed for the unknown case).
        if (!isSelf && (privacy == null || paused || realtimeOff)) return null

        // Coarsen for an "approximate" circle (0.0 = identity for exact/self); mirrors
        // LocationRepository.coarsenRadiusMetersFor's radius math (1-25 mile clamp).
        val radiusMeters = if (isSelf || privacy == null || !privacy.sendsBubble()) {
            0.0
        } else {
            (privacy.approximateRadiusMiles ?: LocationRepository.DEFAULT_BUBBLE_RADIUS_MILES)
                .coerceIn(1, 25) * LocationRepository.METERS_PER_MILE
        }
        return LocationRepository.coarsenLocation(latitude, longitude, radiusMeters)
    }

    /**
     * Encrypt an arrival's location fix while honoring the destination circle's privacy
     * posture (parity with [CheckInRepository.encryptCheckInLocation] and the
     * LocationRepository send paths), or return null when no fix may be attached:
     *  - self circle → exact (there is no server privacy row);
     *  - PAUSED / REALTIME-OFF / unknown-posture (unprimed) circle → null: the arrival
     *    EVENT still fires ([reportArrival] handles a null location), just with no
     *    coordinates;
     *  - "approximate" circle → coarsened to its bubble BEFORE encryption so an exact
     *    arrival point never reaches an approximate circle's viewers;
     *  - exact circle → sent as-is.
     *
     * Resolves the SAME local privacy row [LocationRepository.coarsenRadiusMetersFor]
     * reads, priming it first (GET + local mirror) so a missing row (fresh login /
     * headless) fails CLOSED — omit the fix — rather than leaking an exact point.
     */
    private suspend fun encryptArrivalLocation(
        circleId: String,
        latitude: Double,
        longitude: Double
    ): EncryptedPayload? {
        val now = System.currentTimeMillis()
        val (fixLat, fixLng) = arrivalPositionAllowed(circleId, latitude, longitude) ?: return null
        return try {
            val location = uniffi.pingolin_mobile.Location(
                latitude = fixLat,
                longitude = fixLng,
                altitude = null,
                speed = null,
                heading = null,
                accuracy = null,
                timestamp = now,
                batteryLevel = null,
                isCharging = false
            )
            val encrypted = cryptoManager.encryptLocation(circleId, location)
            EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
        } catch (e: Exception) {
            Log.w(TAG, "reportArrival: encryptLocation failed: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    /**
     * Report arrival at a destination.
     *
     * @param occurredAtMs when the arrival ACTUALLY happened. Sealed with the
     *   circle key and sent so the SUBSCRIBER can stamp their notification with
     *   it; the server never reads it and can only observe when the report
     *   arrived. Defaults to now, which is correct for the manual "I've arrived"
     *   button — the auto-arrival path passes the moment it first detected the
     *   arrival, because that path retries and the retry is not the arrival.
     */
    suspend fun reportArrival(
        destinationId: String,
        circleId: String,
        latitude: Double? = null,
        longitude: Double? = null,
        occurredAtMs: Long = System.currentTimeMillis(),
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<ReportArrivalResponse> {
        return try {
            Log.d(TAG, "reportArrival: dest=$destinationId circle=$circleId hasLoc=${latitude != null}")
            val userId = sessionManager.getUserId() ?: run {
                Log.w(TAG, "reportArrival: no userId in session")
                return Result.failure(Exception("No user"))
            }
            requireMine(destinationId)
            // Nothing is sealed under a key a removed member may hold. The person's "I've
            // arrived" ([keepIfBehind]) is kept while this phone is behind, with the time and
            // the position the circle's setting allows now; the automatic arrival retries by
            // itself and is never kept here.
            try {
                circleRepository.requireCurrentKey(circleId)
            } catch (e: KeyNotCurrentException) {
                if (!keepIfBehind || pendingRun != null) throw e
                val position = if (latitude != null && longitude != null) arrivalPositionAllowed(circleId, latitude, longitude) else null
                val circleName = pendingChanges.circleName(circleId)
                val destName = runCatching { destinationDao.getById(destinationId)?.destName }.getOrNull()
                return pendingChanges.queue(
                    circleId,
                    PendingInputs.Arrived(circleName, destinationId, destName, occurredAtMs, position?.first, position?.second),
                )
            }

            // E2EE: Encrypt user ID
            val encryptedUserIdData = try {
                cryptoManager.encryptDataV2(circleId, userId, "user_id")
            } catch (e: Exception) {
                Log.w(TAG, "reportArrival: encryptData(userId) failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            val encryptedUserId = EncryptedPayload(
                nonce = encryptedUserIdData.nonce,
                ciphertext = encryptedUserIdData.ciphertext
            )

            // E2EE: Encrypt event type
            val encryptedEventTypeData = try {
                cryptoManager.encryptDataV2(circleId, "arrival", "event_type")
            } catch (e: Exception) {
                Log.w(TAG, "reportArrival: encryptData(\"arrival\") failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            val encryptedEventType = EncryptedPayload(
                nonce = encryptedEventTypeData.nonce,
                ciphertext = encryptedEventTypeData.ciphertext
            )

            // E2EE: Compute event type hash
            val eventTypeHash = try {
                cryptoManager.computeEventTypeHash(circleId, "arrival")
            } catch (e: Exception) {
                Log.w(TAG, "reportArrival: computeEventTypeHash failed: ${e.javaClass.simpleName}: ${e.message}")
                throw e
            }
            Log.d(TAG, "reportArrival: eventTypeHash=${eventTypeHash.take(16)}… (${eventTypeHash.length} chars)")

            // E2EE: Encrypt location if provided, honoring the circle's privacy posture.
            // Gate lives here at the reportArrival choke point so BOTH auto-arrival
            // (LocationTrackingService) and manual "I've arrived" (DestinationsViewModel)
            // inherit it. See [encryptArrivalLocation]: self is exact; a paused /
            // realtime-off / unknown-posture circle omits the fix (the arrival EVENT still
            // fires, just with no coordinates); an "approximate" circle is coarsened to its
            // bubble BEFORE encryption so an exact arrival point never reaches an
            // approximate circle's viewers.
            val encryptedLocation = if (latitude != null && longitude != null) {
                encryptArrivalLocation(circleId, latitude, longitude)
            } else null

            // Seal the arrival time to the circle. Encoded as a decimal string
            // because encryptDataV2 seals strings and the recipients parse it back
            // to epoch millis; the server only ever handles the ciphertext. Same
            // shape, and the same "occurred_at" field tag, as
            // BoundaryRepository.reportCrossing and iOS DestinationsManager — the
            // tag is AAD-bound, so a mismatch fails to decrypt rather than
            // decrypting to something wrong.
            val encryptedOccurredAt = runCatching {
                val blob = cryptoManager.encryptDataV2(circleId, occurredAtMs.toString(), "occurred_at")
                EncryptedPayload(nonce = blob.nonce, ciphertext = blob.ciphertext)
            }.getOrElse {
                // Never fail the arrival over the timestamp: an arrival reported
                // without one still notifies, just with the server-side stamp instead.
                Log.w(TAG, "Failed to encrypt arrival time; reporting without it", it)
                null
            }

            Log.d(TAG, "reportArrival: sending POST /destinations/$destinationId/arrive")
            val response = tracksApi.reportDestinationArrival(
                destinationId,
                ReportArrivalRequest(
                    encryptedLocation = encryptedLocation,
                    encryptedUserId = encryptedUserId,
                    eventTypeHash = eventTypeHash,
                    encryptedEventType = encryptedEventType,
                    encryptedOccurredAt = encryptedOccurredAt
                )
            )
            Log.d(TAG, "reportArrival: HTTP ${response.code()} success=${response.body()?.success}")

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                // Arrived destinations leave the list immediately: the
                // server's list endpoint doesn't return arrived rows,
                // so deleting locally is exactly what the next refresh
                // would do anyway — this just makes it instant instead of
                // waiting for the next sync. The arrival itself lives on
                // as a notification ("You've arrived!").
                destinationDao.deleteById(destinationId)
                Result.success(response.body()!!.data!!)
            } else if (response.code() == 404) {
                // Server returned 404: the destination row no longer
                // satisfies (id, user_id, status='active'). Pull fresh
                // state from the server so the local cache reflects
                // whatever happened (cancelled, arrived, expired) — a
                // synthetic local mutation here would race the source
                // of truth.
                refreshDestinations()
                Result.failure(RefusedException("This destination is no longer active — refresh the list", 404, null))
            } else {
                val errorBody = response.errorBody()?.string()?.take(500) ?: ""
                val apiError = response.body()?.error
                val detail = apiError ?: errorBody.ifBlank { "no body" }
                Log.w(TAG, "reportArrival: HTTP ${response.code()} — $detail")
                val code = com.pingolin.track.data.remote.dto.ApiErrors.parse(errorBody)?.errorCode
                Result.failure(
                    RefusedException(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response.code(), code, "Couldn't send your arrival just now."), response.code(), code)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "reportArrival: exception ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Delete a destination.
     */
    suspend fun deleteDestination(destinationId: String): Result<Unit> {
        return try {
            requireMine(destinationId)
            val response = tracksApi.deleteDestination(destinationId)
            if (response.isSuccessful) {
                destinationDao.deleteById(destinationId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete destination"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The last destination list the server gave; see [refreshDestinationsListed]. */
    @Volatile private var lastListedDestinations: ListedDestinations? = null

    /**
     * A destination list as the server gave it: every listed destination's status by id (one
     * listed but not readable here included), and whether the list is whole. The server stops
     * at [SERVER_LIST_CAP] rows, so a full list may have left some out.
     */
    data class ListedDestinations(val statuses: Map<String, String>, val whole: Boolean)

    /**
     * [refreshDestinations], then the list it read, or null when it failed. A listed
     * destination's status holds whether the list is whole or not; only a destination's
     * ABSENCE needs a whole list to say it ended.
     */
    suspend fun refreshDestinationsListed(): ListedDestinations? {
        refreshDestinations().getOrNull() ?: return null
        return lastListedDestinations
    }

    /**
     * Refresh destinations from the server.
     */
    suspend fun refreshDestinations(): Result<List<DestinationEntity>> {
        return try {
            val response = tracksApi.getDestinations()
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                // Capture the raw SERVER ids BEFORE decrypt so the prune below keys on
                // SERVER TRUTH, never on the decrypted survivors. A destination the
                // server DID return but we couldn't decrypt THIS tick (transiently
                // missing circle key — e.g. a destination push landing mid key-load
                // after a restart) must be RETAINED, not wiped.
                val rawIds = response.body()!!.data!!.map { it.id }
                // Whole only under the server's cap (see [refreshDestinationsListed]).
                lastListedDestinations = response.body()!!.data!!.let { listed ->
                    ListedDestinations(listed.associate { it.id to it.status }, whole = listed.size < SERVER_LIST_CAP)
                }
                // Per-destination decrypt is pure CPU (UniFFI); move it off the
                // Main thread so a large destination set can't jank the caller.
                val destinations = withContext(Dispatchers.Default) {
                    response.body()!!.data!!.mapNotNull { dto ->
                    val cId = dto.circleId

                    // E2EE: Decrypt user ID
                    val decryptedUserId = decryptString(cId, dto.encryptedUserId, "user_id")
                        ?: return@mapNotNull null

                    // E2EE: Decrypt destination coordinates
                    val coords = decryptDestination(cId, dto.encryptedDestination)
                        ?: return@mapNotNull null

                    // E2EE: Decrypt destination name
                    val decryptedDestName = dto.encryptedDestName?.let { decryptString(cId, it, "dest_name") }

                    DestinationEntity(
                        id = dto.id,
                        userId = decryptedUserId,
                        circleId = cId,
                        destName = decryptedDestName,
                        destLatitude = coords.first,
                        destLongitude = coords.second,
                        etaDayBucket = dto.etaDayBucket,
                        notifyArrival = dto.notifyArrival,
                        status = dto.status,
                        startedDayBucket = dto.startedDayBucket,
                        arrivedDayBucket = dto.arrivedDayBucket,
                        createdDayBucket = dto.createdDayBucket,
                        updatedDayBucket = dto.updatedDayBucket,
                        creatorId = dto.userId,
                    )
                }
                }
                destinationDao.insertAll(destinations)
                // The list endpoint returns the authoritative active+recent set across
                // every circle, so anything we hold locally that isn't in it is stale
                // (cancelled / arrived-and-aged-out) and should be pruned. Prune against
                // SERVER TRUTH (rawIds), NOT the successfully-decrypted subset: a
                // destination the server returned but we couldn't decrypt THIS tick stays
                // in rawIds and is RETAINED, so a transient missing circle key cannot
                // wipe a circle's active destinations (which LocationTrackingService
                // auto-arrival reads from — an unrecoverable missed arrival). An empty
                // DECRYPT result must NOT clear the table. Only when the SERVER genuinely
                // returns zero rows is keepIds empty, so every local row falls into
                // toDelete and is cleared — the one case a full clear is correct. Chunk
                // the delete under SQLite's 999-variable cap. Mirrors the prune in
                // PlaceRepository.
                val keepIds = rawIds.toHashSet()
                val toDelete = destinationDao.getAllIds().filterNot { it in keepIds }
                if (toDelete.isNotEmpty()) {
                    destinationDao.deleteByIdsChunked(toDelete)
                }
                Result.success(destinations)
            } else {
                Result.failure(Exception("Failed to fetch destinations"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Map a DTO to an entity with provided decrypted values.
     */
    private fun mapDtoToEntity(
        circleId: String,
        dto: DestinationResponse,
        userId: String,
        destName: String?,
        destLatitude: Double,
        destLongitude: Double
    ): DestinationEntity {
        return DestinationEntity(
            id = dto.id,
            userId = userId,
            circleId = circleId,
            destName = destName,
            destLatitude = destLatitude,
            destLongitude = destLongitude,
            etaDayBucket = dto.etaDayBucket,
            notifyArrival = dto.notifyArrival,
            status = dto.status,
            startedDayBucket = dto.startedDayBucket,
            arrivedDayBucket = dto.arrivedDayBucket,
            createdDayBucket = dto.createdDayBucket,
            updatedDayBucket = dto.updatedDayBucket,
            creatorId = dto.userId,
        )
    }
}

/**
 * Another account's destination, or one sealed with this account's id by someone else. No words
 * of its own: each caller says in its own words that it couldn't (plainText's fallback).
 */
class NotYourDestinationException : Exception()
