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

import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.domain.placealerts.PlaceAlertRules
import com.pingolin.track.util.ServerTime
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.hasKeyInUse
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypted place alert event for client-side use.
 * Created by decrypting PlaceAlertEventResponse with the circle key.
 */
data class DecryptedPlaceAlertEvent(
    val id: String,
    val placeAlertId: String,
    /** Decrypted target user ID */
    val targetUserId: String,
    /** Decrypted device ID */
    val deviceId: String,
    /** Decrypted event type ("arrival" or "departure") */
    val eventType: String,
    /** Day bucket when event occurred */
    val dayBucket: Int,
    /** Decrypted precise timestamp */
    val timestamp: Long
)

@Singleton
class PlaceRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val placeDao: PlaceDao,
    private val placeAlertDao: PlaceAlertDao,
    private val cryptoManager: CryptoManager,
    private val locationRepository: LocationRepository,
    private val sessionManager: SessionManager,
    // Lazy, mirroring CircleRepository's own Lazy<LocationRepository>: these two
    // sit in each other's neighbourhood and eager injection is the kind of thing
    // that only fails once the graph grows.
    private val circleRepository: dagger.Lazy<CircleRepository>,
    /** Where a place change made while this phone is behind waits. */
    private val pendingChanges: PendingChanges,
) {
    companion object {
        // Use same IDs as LocationRepository for personal data
        const val PERSONAL_CIRCLE_ID = "self"
        const val PERSONAL_CIRCLE_UUID = "00000000-0000-0000-0000-000000000000"

        /** Server's maximum page size for GET /place-alerts (it clamps to 1..100).
         *  Asking for the max keeps the common case a single complete page; a page
         *  THIS full is treated as possibly-truncated and suppresses the prune. */
        const val PLACE_ALERT_PAGE_LIMIT = 100
    }

    /** Pauses before setting up personal places again after a failure; tests shorten them. */
    internal var personalSetupPausesMs: List<Long> = listOf(2_000L, 5_000L)

    fun observePlaces(): Flow<List<PlaceEntity>> = placeDao.observeAll().orWhenUnopenable(emptyList())

    /** Observe personal places — those not tied to any circle. */
    fun observePersonalPlaces(): Flow<List<PlaceEntity>> =
        placeDao.observeByCircle(PERSONAL_CIRCLE_ID).orWhenUnopenable(emptyList())

    fun observePlacesByCircle(circleId: String): Flow<List<PlaceEntity>> =
        placeDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /** A circle's own places plus places shared into it from other circles. */
    fun observePlacesByCircleIncludingShared(circleId: String): Flow<List<PlaceEntity>> =
        placeDao.observeByCircleIncludingShared(circleId).orWhenUnopenable(emptyList())

    fun observeAlertsByPlace(placeId: String): Flow<List<PlaceAlertEntity>> =
        placeAlertDao.observeByPlace(placeId).orWhenUnopenable(emptyList())

    /** Place ids the CURRENT user has an active place alert on. Per-user (local
     *  place_alerts holds only this user's subscriptions) — lets the map ring the
     *  geofence only for places THIS user alerts on, so a share recipient doesn't see
     *  a ring drawn from the owner's alert (place alerts are per user, per place). */
    fun observeAlertedPlaceIds(): Flow<List<String>> =
        placeAlertDao.observeActiveAlertPlaceIds().orWhenUnopenable(emptyList())

    suspend fun getPlace(placeId: String): PlaceEntity? = placeDao.getById(placeId)

    /**
     * Distinct routing circles named by this user's ACTIVE place alerts.
     *
     * Lets the boundary reload keep those circles' member rosters fresh without
     * BoundaryRepository having to inject PlaceAlertDao — that class must stay
     * structurally unable to consult alerts when deciding where a crossing is
     * emitted. PlaceAlertEvaluator scopes a circle-wide alert against local
     * circle_members, and nothing else refreshes that table in the background.
     */
    suspend fun activeAlertCircleIds(): List<String> =
        placeAlertDao.getActive()
            .mapNotNull { it.circleId?.trim()?.takeIf { c -> c.isNotEmpty() } }
            .distinct()

    /** Snapshot of every locally-cached place (all circles). The boundary reload
     *  builds its geofence union from these RETAINED rows rather than a single
     *  refresh tick's decrypted output, so a place that was transiently
     *  un-decryptable this tick (missing circle key) — but is still in the DB
     *  from an earlier decrypt — stays monitored. */
    suspend fun getAllPlaces(): List<PlaceEntity> = placeDao.getAll()

    /** Every place id of the last `refreshPlaces(null)` that got every page; see [refreshPlacesWhole]. */
    @Volatile private var lastWholePlaceIds: Set<String>? = null

    /**
     * `refreshPlaces(null)`, then every place id the server listed, when every page came: the
     * only list that says a place was deleted, never the local rows or a refresh that failed.
     * A place listed but not readable here is in it. Null when the refresh failed or stopped
     * early.
     */
    suspend fun refreshPlacesWhole(): Set<String>? {
        refreshPlaces(null).getOrNull() ?: return null
        return lastWholePlaceIds
    }

    suspend fun refreshPlaces(circleId: String? = null): Result<List<PlaceEntity>> {
        return try {
            // If fetching personal places, ensure we have the personal key first
            if (circleId == null || circleId == PERSONAL_CIRCLE_UUID) {
                // A key IN USE: a slot holding only the retired pre-reset key
                // still takes the setup, which publishes the new one.
                if (!cryptoManager.hasKeyInUse(PERSONAL_CIRCLE_ID)) {
                    android.util.Log.d("PlaceRepository", "Personal key not found, syncing from server...")
                    locationRepository.ensurePersonalKeyExists()
                }
            }

            // Fetch the FULL visible place set by following the server's offset
            // pagination to exhaustion. GET /places caps a page at 50 (max 100) and
            // ignores circle_id server-side, so a single unpaginated call sees only
            // places 1-50 — and the prune below would then delete every place past
            // that locally. Page at the max size; stop on a short page. `completeFetch`
            // gates the prune so a mid-pagination failure never deletes rows that
            // merely happened to fall on a page we never got.
            val rawPlaces = mutableListOf<PlaceResponse>()
            var completeFetch = true
            run {
                val pageSize = 100
                var offset = 0
                while (true) {
                    val response = tracksApi.getPlaces(circleId, limit = pageSize, offset = offset)
                    val ok = response.isSuccessful && response.body()?.success == true && response.body()?.data != null
                    if (!ok) {
                        // A failed first page is a hard failure; a failed later page
                        // keeps the pages we have but disables the prune.
                        if (offset == 0) return Result.failure(Exception("Failed to fetch places"))
                        completeFetch = false
                        break
                    }
                    val page = response.body()!!.data!!
                    rawPlaces += page
                    if (page.size < pageSize) break
                    offset += pageSize
                    // Safety valve against a server that never returns a short page.
                    if (offset >= 10_000) { completeFetch = false; break }
                }
            }
            // Whole only when every page came (see [refreshPlacesWhole]).
            if (circleId == null) {
                lastWholePlaceIds = if (completeFetch) rawPlaces.mapTo(HashSet()) { it.id } else null
            }
            // A place id the list serves more than once, in any spelling, has EVERY row of it
            // dropped here, never the first or the last kept. The id isn't in a place's sealed
            // fields, so a second row under this account's place's id, copied from another place,
            // would steer its geometry and the alerts judged against it. It is still listed: the
            // place kept here from before stays as it was, neither updated nor pruned.
            val repeated = rawPlaces.groupingBy { com.pingolin.track.util.AccountIds.storeKey(it.id) }.eachCount()
                .filterValues { it > 1 }.keys
            if (repeated.isNotEmpty()) {
                android.util.Log.w("PlaceRepository", "${repeated.size} place id(s) the list served more than once: none of their rows taken")
            }
            val rows = rawPlaces.filterNot { com.pingolin.track.util.AccountIds.storeKey(it.id) in repeated }

                // Existing rows for EVERY id in the response — read to (a)
                // preserve the local-only sortOrder for every place regardless of
                // circle (a scoped refresh must never zero another circle's
                // ordering), and (b) skip the Room REPLACE for rows whose
                // decrypted content is unchanged, so the 2-min boundary reload
                // doesn't re-emit every places observer every cycle.
                val existingById = placeDao.getByIdsChunked(rows.map { it.id }).associateBy { it.id }
                // Decrypt loop is pure compute (per-place key unwrap + field decrypt +
                // entity construction; no Room/network inside — those run after). Move it
                // off the Main thread so a large place set can't jank the UI.
                val places = withContext(Dispatchers.Default) {
                    rows.mapNotNull { dto ->
                    try {
                        // A wrapped_key in the response means the place uses per-place
                        // envelope encryption; without one it is a legacy
                        // circle-key-direct row. wrap_via_circle says which key
                        // unwraps it: NULL = owner-wrap (use the self-key), set = the
                        // wrap came from a place_shares row visible to the requester
                        // (use that circle's key).
                        val wrappedKey = dto.wrappedKey
                        val wrappedKeyNonce = dto.wrappedKeyNonce
                        val placeKey: String? = if (wrappedKey != null && wrappedKeyNonce != null) {
                            val wrapTarget = when {
                                dto.wrapViaCircle != null -> {
                                    if (dto.wrapViaCircle == PERSONAL_CIRCLE_UUID) PERSONAL_CIRCLE_ID
                                    else dto.wrapViaCircle
                                }
                                else -> PERSONAL_CIRCLE_ID
                            }
                            if (!cryptoManager.hasCircleKey(wrapTarget)) {
                                android.util.Log.w("PlaceRepository", "Missing wrap-target key for place ${dto.id}: $wrapTarget")
                                return@mapNotNull null
                            }
                            try {
                                cryptoManager.unwrapPlaceKey(wrapTarget, wrappedKey, wrappedKeyNonce)
                            } catch (e: Exception) {
                                android.util.Log.w("PlaceRepository", "Place ${dto.id}: unwrapPlaceKey failed via $wrapTarget — trying legacy decrypt as fallback", e)
                                null
                            }
                        } else null

                        // Map null or nil UUID to personal key ID for encryption
                        // API returns null for personal places, nil UUID is also treated as personal
                        val keyId = if (dto.circleId == null || dto.circleId == PERSONAL_CIRCLE_UUID) {
                            PERSONAL_CIRCLE_ID
                        } else {
                            dto.circleId
                        }

                        // Only the legacy path needs the circle key; the envelope path
                        // already validated its wrapTarget above.
                        if (placeKey == null && !cryptoManager.hasCircleKey(keyId)) {
                            android.util.Log.w("PlaceRepository", "Missing key for circle: $keyId (original: ${dto.circleId})")
                            return@mapNotNull null
                        }

                        // Helper: decrypt one EncryptedPayload using whichever path applies.
                        fun decryptField(payload: EncryptedPayload, label: String): String? = try {
                            val ed = uniffi.pingolin_mobile.EncryptedData(
                                nonce = payload.nonce,
                                ciphertext = payload.ciphertext,
                            )
                            if (placeKey != null) cryptoManager.decryptWithPlaceKeyV2(placeKey, ed, label)
                            else cryptoManager.decryptDataV2(keyId, ed, label)
                        } catch (e: Exception) {
                            android.util.Log.w("PlaceRepository", "Failed to decrypt $label for place ${dto.id}: ${e.message}")
                            null
                        }

                        val decryptedName = decryptField(dto.encryptedName, "name")
                            ?: return@mapNotNull null

                        val decryptedAddress = dto.encryptedAddress?.let { decryptField(it, "address") }

                        val decryptedNotes = dto.encryptedNotes?.let { decryptField(it, "notes") }

                        // Decrypt location - try SDK Location format first, fall back to JSON
                        val decryptedLocation = try {
                            val ed = uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedLocation.nonce,
                                ciphertext = dto.encryptedLocation.ciphertext,
                            )
                            if (placeKey != null) {
                                val locationJson = cryptoManager.decryptWithPlaceKeyV2(placeKey, ed, "location")
                                parseLocationFromJson(locationJson)
                            } else {
                                try {
                                    cryptoManager.decryptLocation(keyId, ed)
                                } catch (_: Exception) {
                                    parseLocationFromJson(cryptoManager.decryptDataV2(keyId, ed, "location"))
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("PlaceRepository", "Failed to decrypt location for place ${dto.id}: ${e.message}")
                            return@mapNotNull null
                        }

                        // Store personal places with PERSONAL_CIRCLE_ID locally
                        // null or nil UUID both map to personal
                        val localCircleId = if (dto.circleId == null || dto.circleId == PERSONAL_CIRCLE_UUID) {
                            PERSONAL_CIRCLE_ID
                        } else {
                            dto.circleId
                        }

                        PlaceEntity(
                            id = dto.id,
                            circleId = localCircleId,
                            name = decryptedName,
                            notes = decryptedNotes,
                            address = decryptedAddress,
                            latitude = decryptedLocation.latitude,
                            longitude = decryptedLocation.longitude,
                            radius = dto.radiusMeters,
                            icon = dto.icon,
                            color = dto.color,
                            placeType = dto.placeType ?: "place",
                            boundaryRadius = dto.boundaryRadius,
                            alertsEnabled = dto.alertsEnabled,
                            // The server's places carry no created_at (only created_day_bucket),
                            // so this is the sync time; nothing orders by it.
                            createdAt = ServerTime.epochMs(dto.createdAt) ?: System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            // Preserve the local-only sortOrder for THIS place from its
                            // own cached row. The response carries every circle, so a
                            // lookup scoped to the requested circle would zero the
                            // ordering of every out-of-circle place on REPLACE.
                            sortOrder = existingById[dto.id]?.sortOrder ?: 0,
                            sharedCircles = dto.sharedCircles?.joinToString(","),
                            // Cache the unwrapped place_key for the owner AND for share
                            // recipients. A recipient already unwrapped it (above) via a
                            // circle they belong to, and already stores the place's decrypted
                            // name/location, so caching the key leaks nothing extra — it just
                            // lets them set/read per-place-key alerts on a personal place
                            // shared to them. An un-share revokes access on the next
                            // refresh (the server stops returning the wrapped_key), which
                            // drops the place (and this key) locally.
                            placeKey = placeKey,
                            // Persist the wrap-via-circle hint so the UI can
                            // tell received shares apart from owned places.
                            // null UUID maps to the personal sentinel even
                            // though that case shouldn't happen in practice
                            // (owners get null wrap_via_circle, not a
                            // self-circle reference).
                            wrapViaCircle = dto.wrapViaCircle?.takeIf {
                                it != PERSONAL_CIRCLE_UUID
                            },
                            // Server only ships user_id for share recipients,
                            // so this is null on owner reads. Resolved to
                            // a display name in the UI via the members
                            // store / circle members list.
                            ownerUserId = dto.userId,
                            // Server-authoritative ownership (gates
                            // the owner-only radius tuner). Falls
                            // back to the legacy wrapViaCircle==null
                            // proxy when an older server omits
                            // is_owner.
                            isOwner = dto.isOwner ?: (dto.wrapViaCircle == null)
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("PlaceRepository", "Failed to decrypt place ${dto.id}: ${e.message}", e)
                        null
                    }
                    }
                }
                android.util.Log.d("PlaceRepository", "Decrypted ${places.size} places")
                // Only REPLACE rows that are new or whose decrypted content actually
                // changed. updatedAt is volatile (set to now above), so normalize it to
                // the cached value before comparing — otherwise every refresh would look
                // "changed" and rewrite the whole table, and each REPLACE (DELETE+INSERT)
                // makes Room re-emit every places observer. sortOrder is already carried
                // over, so equality here reflects real content changes only. Unchanged
                // rows are skipped entirely (their stored updatedAt is left untouched).
                val toWrite = places.filter { p ->
                    val existing = existingById[p.id]
                    existing == null || existing.copy(updatedAt = p.updatedAt) != p
                }
                if (toWrite.isNotEmpty()) {
                    placeDao.upsertAll(toWrite)
                    android.util.Log.d("PlaceRepository", "Wrote ${toWrite.size} changed/new places")
                }
                // When refreshPlaces(null) returns the full visible set, drop any local
                // rows the server no longer reports — e.g. a share revoked because the
                // owner was deleted, or a place removed by another circle peer. Scoped
                // fetches (circleId != null) only see a slice, so don't prune there to
                // avoid wiping unrelated rows. `completeFetch` also guards against pruning
                // when pagination stopped early on a later-page failure — rows on the
                // unfetched pages must not be deleted.
                if (circleId == null && completeFetch) {
                    // Prune against SERVER truth (rawPlaces), NOT the successfully-decrypted
                    // subset (places). A place the server DID return but we couldn't decrypt
                    // THIS tick — e.g. a transiently missing circle key during the 2-min
                    // background boundary reload — stays in rawPlaces, so it's retained
                    // as-is instead of being deleted. Deleting it would CASCADE-delete its
                    // place_alerts (Entities.kt place_alerts FK ON DELETE CASCADE), silently
                    // wiping the user's alerts over a transient key gap. Only rows the server
                    // no longer returns AT ALL are dropped; when the server genuinely returns
                    // zero places, keepIds is empty and every local row is pruned.
                    val keepIds = rawPlaces.mapTo(HashSet()) { it.id }
                    // Compute the delete set explicitly and delete it in ≤999-variable chunks
                    // (deleteByIdsChunked) instead of a single NOT IN(:keepIds), which
                    // overflows SQLite's host-variable cap above ~999 places (API 26–30) and
                    // emits an invalid NOT IN () when keepIds is empty.
                    val toDelete = placeDao.getAllIds().filterNot { it in keepIds }
                    val pruned = if (toDelete.isEmpty()) 0 else placeDao.deleteByIdsChunked(toDelete)
                    if (pruned > 0) {
                        android.util.Log.i("PlaceRepository", "Pruned $pruned stale local places not returned by server")
                    }
                }
                Result.success(places)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPlace(
        circleId: String,
        name: String,
        notes: String? = null,
        address: String? = null,
        latitude: Double,
        longitude: Double,
        radius: Int,
        icon: String? = null,
        color: String? = null,
        placeType: String = "place",
        boundaryRadius: Int? = null,
        /** A copy made by a waiting change sends its own id. */
        clientRequestId: String? = null,
    ): Result<PlaceEntity> {
        return try {
            if (!circleRepository.get().ensureCircleKey(circleId)) {
                return Result.failure(Exception("This circle isn't open on this phone yet."))
            }
            // Nothing is sealed under a key a removed member may hold.
            circleRepository.get().requireCurrentKey(circleId)

            val encryptedName = try {
                cryptoManager.encryptDataV2(circleId, name, "name")
            } catch (e: IllegalStateException) {
                android.util.Log.e("PlaceRepository", "Encryption failed for circle place: ${e.message}")
                return Result.failure(Exception("Couldn't encrypt this place on this phone, so it wasn't saved."))
            }

            // Encrypt location as JSON for cross-platform compatibility with web
            val locationJson = org.json.JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("altitude", org.json.JSONObject.NULL)
                put("speed", org.json.JSONObject.NULL)
                put("heading", org.json.JSONObject.NULL)
                put("accuracy", org.json.JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
                put("batteryLevel", org.json.JSONObject.NULL)
                put("isCharging", false)
            }.toString()

            val encryptedLocation = try {
                cryptoManager.encryptDataV2(circleId, locationJson, "location")
            } catch (e: IllegalStateException) {
                android.util.Log.e("PlaceRepository", "Location encryption failed for circle place: ${e.message}")
                return Result.failure(Exception("Couldn't encrypt this place on this phone, so it wasn't saved."))
            }

            // Encrypt address if provided
            val encryptedAddress = address?.let {
                try {
                    val encrypted = cryptoManager.encryptDataV2(circleId, it, "address")
                    EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
                } catch (e: IllegalStateException) {
                    android.util.Log.w("PlaceRepository", "Address encryption failed: ${e.message}")
                    null
                }
            }

            // Encrypt notes if the user provided any. Treat blank as absent
            // so we don't send an empty-ciphertext row.
            val encryptedNotes = notes?.takeIf { it.isNotBlank() }?.let {
                try {
                    val encrypted = cryptoManager.encryptDataV2(circleId, it, "notes")
                    EncryptedPayload(nonce = encrypted.nonce, ciphertext = encrypted.ciphertext)
                } catch (e: IllegalStateException) {
                    android.util.Log.w("PlaceRepository", "Notes encryption failed: ${e.message}")
                    null
                }
            }

            val response = tracksApi.createPlace(
                CreatePlaceRequest(
                    circleId = circleId,
                    encryptedName = EncryptedPayload(
                        nonce = encryptedName.nonce,
                        ciphertext = encryptedName.ciphertext
                    ),
                    encryptedAddress = encryptedAddress,
                    encryptedNotes = encryptedNotes,
                    encryptedLocation = EncryptedPayload(
                        nonce = encryptedLocation.nonce,
                        ciphertext = encryptedLocation.ciphertext
                    ),
                    radiusMeters = radius,
                    icon = icon,
                    color = color,
                    placeType = placeType,
                    boundaryRadius = boundaryRadius,
                    clientRequestId = clientRequestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val placeId = response.body()!!.data!!
                val now = System.currentTimeMillis()
                val place = PlaceEntity(
                    id = placeId,
                    circleId = circleId,
                    name = name,
                    notes = notes?.takeIf { it.isNotBlank() },
                    address = address,
                    latitude = latitude,
                    longitude = longitude,
                    radius = radius,
                    icon = icon,
                    color = color,
                    placeType = placeType,
                    boundaryRadius = boundaryRadius,
                    createdAt = now,
                    updatedAt = now
                )
                placeDao.upsert(place)
                Result.success(place)
            } else {
                // Carry error_code/quota on a 402 so the UI can
                // show the premium prompt; the words are this
                // app's, never the server's.
                Result.failure(ApiErrors.toException(response, "Couldn't save the place just now."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a personal place (not tied to any circle).
     *
     * Envelope encryption: each place gets a fresh per-place key.
     * Field ciphertexts are encrypted under the place_key, and
     * the place_key itself is wrapped under the user's self-key for
     * ownership. Sharing this place into a circle later just adds an
     * additional wrap of the same place_key under the circle's key
     * (`sharePlaceWithCircle`) — the underlying ciphertexts don't
     * change, so edits propagate to all circles seeing the place.
     */
    suspend fun createPersonalPlace(
        name: String,
        notes: String? = null,
        address: String? = null,
        latitude: Double,
        longitude: Double,
        radius: Int,
        icon: String? = null,
        color: String? = null,
        placeType: String = "place",
        boundaryRadius: Int? = null,
        /** Only a restore passes this — see [CreatePlaceRequest.id]. A taken id
         *  fails with [com.pingolin.track.data.remote.dto.AlreadyExistsException],
         *  which the restore reads as "already there". */
        restoreId: String? = null
    ): Result<PlaceEntity> {
        return try {
            // Ensure personal key exists — needed both for wrapping the place_key
            // under self and as a fallback if the SDK crypto primitives need a key
            // registered for "self". In use, not merely held.
            if (!cryptoManager.hasKeyInUse(PERSONAL_CIRCLE_ID)) {
                // Set up again after a moment when it fails (no network, a
                // server blip), instead of asking the person to try again.
                var keyCreated = locationRepository.ensurePersonalKeyExists()
                for (pause in personalSetupPausesMs) {
                    if (keyCreated) break
                    kotlinx.coroutines.delay(pause)
                    keyCreated = locationRepository.ensurePersonalKeyExists()
                }
                if (!keyCreated) {
                    return Result.failure(Exception("Couldn't set up your personal places just now."))
                }
            }
            if (!cryptoManager.hasKeyInUse(PERSONAL_CIRCLE_ID)) {
                return Result.failure(Exception("Your personal places aren't ready on this phone yet."))
            }

            // Step 1: fresh per-place key.
            val placeKey = cryptoManager.generatePlaceKey()

            // Step 2: encrypt each field under the place_key. Same
            // XChaCha20-Poly1305 primitive used everywhere — each field gets
            // its own random 24-byte nonce.
            val encryptedName = try {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, name, "name")
            } catch (e: Exception) {
                android.util.Log.e("PlaceRepository", "createPersonalPlace: name encrypt failed", e)
                return Result.failure(Exception("Couldn't encrypt this place on this phone, so it wasn't saved."))
            }

            // Encrypt location as JSON for cross-platform compatibility with web
            val locationJson = org.json.JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("altitude", org.json.JSONObject.NULL)
                put("speed", org.json.JSONObject.NULL)
                put("heading", org.json.JSONObject.NULL)
                put("accuracy", org.json.JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
                put("batteryLevel", org.json.JSONObject.NULL)
                put("isCharging", false)
            }.toString()

            val encryptedLocation = try {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, locationJson, "location")
            } catch (e: Exception) {
                android.util.Log.e("PlaceRepository", "createPersonalPlace: location encrypt failed", e)
                return Result.failure(Exception("Couldn't encrypt this place on this phone, so it wasn't saved."))
            }

            val encryptedAddress = address?.let {
                try {
                    val enc = cryptoManager.encryptWithPlaceKeyV2(placeKey, it, "address")
                    EncryptedPayload(nonce = enc.nonce, ciphertext = enc.ciphertext)
                } catch (e: Exception) {
                    android.util.Log.w("PlaceRepository", "createPersonalPlace: address encrypt failed", e)
                    null
                }
            }

            val encryptedNotes = notes?.takeIf { it.isNotBlank() }?.let {
                try {
                    val enc = cryptoManager.encryptWithPlaceKeyV2(placeKey, it, "notes")
                    EncryptedPayload(nonce = enc.nonce, ciphertext = enc.ciphertext)
                } catch (e: Exception) {
                    android.util.Log.w("PlaceRepository", "createPersonalPlace: notes encrypt failed", e)
                    null
                }
            }

            // Step 3: wrap the place_key under the owner's self-key. The wrap
            // pair (wrapped_key + nonce) goes onto the places row alongside the
            // field ciphertexts. We pass our user_id as sender; the place_id is
            // unknown until the server creates the row, so it goes out empty.
            val senderUserId = sessionManager.getUserId().orEmpty()
            val ownerWrap = try {
                cryptoManager.wrapPlaceKey(PERSONAL_CIRCLE_ID, placeKey, senderUserId, "")
            } catch (e: Exception) {
                android.util.Log.e("PlaceRepository", "createPersonalPlace: owner wrap failed", e)
                return Result.failure(Exception("Couldn't encrypt this place on this phone, so it wasn't saved."))
            }

            // Use null for personal places on the server (not nil UUID)
            val response = tracksApi.createPlace(
                CreatePlaceRequest(
                    id = restoreId,
                    circleId = null,
                    encryptedName = EncryptedPayload(
                        nonce = encryptedName.nonce,
                        ciphertext = encryptedName.ciphertext
                    ),
                    encryptedAddress = encryptedAddress,
                    encryptedNotes = encryptedNotes,
                    encryptedLocation = EncryptedPayload(
                        nonce = encryptedLocation.nonce,
                        ciphertext = encryptedLocation.ciphertext
                    ),
                    radiusMeters = radius,
                    icon = icon,
                    color = color,
                    placeType = placeType,
                    boundaryRadius = boundaryRadius,
                    wrappedKey = ownerWrap.wrappedKey,
                    wrappedKeyNonce = ownerWrap.wrappedKeyNonce
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val placeId = response.body()!!.data!!
                val now = System.currentTimeMillis()
                val place = PlaceEntity(
                    id = placeId,
                    circleId = PERSONAL_CIRCLE_ID, // Store as "self" locally
                    name = name,
                    notes = notes?.takeIf { it.isNotBlank() },
                    address = address,
                    latitude = latitude,
                    longitude = longitude,
                    radius = radius,
                    icon = icon,
                    color = color,
                    placeType = placeType,
                    boundaryRadius = boundaryRadius,
                    createdAt = now,
                    updatedAt = now,
                    // Cache the place_key — sharing/unsharing later avoids
                    // an extra unwrap round-trip.
                    placeKey = placeKey
                )
                placeDao.upsert(place)
                Result.success(place)
            } else {
                // On a 402 (place count limit) the code + quota live in the
                // error body, not response.body(); map it to a QuotaException so
                // the caller can surface the premium prompt.
                Result.failure(ApiErrors.toException(response, "Couldn't save the place just now."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Edit a place. An older place owned by a circle is sealed under the circle's key: with
     * [keepIfBehind] (the person's tap), an edit this phone can't make because it is behind on that
     * circle is kept, with only the fields the person changed, and saved by itself once it catches
     * up: the failure is then [com.pingolin.track.domain.pending.QueuedForSync].
     */
    suspend fun updatePlace(
        placeId: String,
        name: String? = null,
        notes: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Int? = null,
        icon: String? = null,
        color: String? = null,
        boundaryRadius: Int? = null,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<PlaceEntity> {
        return try {
            val existingPlace = placeDao.getById(placeId)
                ?: return Result.failure(Exception("Place not found"))

            // A place with a per-place key encrypts its fields under THAT key, and an
            // edit must keep using it: re-encrypting with the circle key desyncs the
            // place from the key its alerts and shares are encrypted under, silently
            // breaking place-key-encrypted alerts on edit. Only legacy circle-keyed
            // places with no per-place key fall back to the circle key.
            val placeKey = existingPlace.placeKey
            if (placeKey == null && !circleRepository.get().ensureCircleKey(existingPlace.circleId)) {
                return Result.failure(Exception("This circle isn't open on this phone yet."))
            }
            if (placeKey == null) {
                try {
                    circleRepository.get().requireCurrentKey(existingPlace.circleId)
                } catch (e: KeyNotCurrentException) {
                    if (!keepIfBehind || pendingRun != null) throw e
                    val circleName = pendingChanges.circleName(existingPlace.circleId)
                    return pendingChanges.queue(
                        existingPlace.circleId,
                        PendingInputs.EditPlace(
                            circleName, placeId, existingPlace.name,
                            name, notes, latitude, longitude, radius, icon, color, boundaryRadius,
                        ),
                        // The place as the person saw it: changed elsewhere meanwhile, it isn't saved.
                        seen = PendingInputs.toJson(PendingInputs.EditPlace(
                            circleName, placeId, existingPlace.name,
                            existingPlace.name, existingPlace.notes, existingPlace.latitude, existingPlace.longitude,
                            existingPlace.radius, existingPlace.icon, existingPlace.color, existingPlace.boundaryRadius,
                        )),
                    )
                }
            }
            fun encryptField(plaintext: String, fieldTag: String) =
                if (placeKey != null) cryptoManager.encryptWithPlaceKeyV2(placeKey, plaintext, fieldTag)
                else cryptoManager.encryptDataV2(existingPlace.circleId, plaintext, fieldTag)

            var encryptedNotes: EncryptedPayload? = null

            // The server's update rewrites the whole row and REQUIRES
            // encrypted_name + encrypted_location + radius_meters on every call
            // (CreatePlaceRequest — all non-optional). A partial edit (e.g. the
            // radius-only "alert area" tune, which passes neither name nor
            // lat/lng) must therefore re-encrypt and re-send the UNCHANGED
            // name/location from the existing row, else the request 422s
            // ("Failed to update place"). Re-encryption goes through the same
            // place key (encryptField), keeping the row in sync with the key its
            // alerts/shares are encrypted under.
            val encryptedName = encryptField(name ?: existingPlace.name, "name").let {
                EncryptedPayload(nonce = it.nonce, ciphertext = it.ciphertext)
            }

            // Encrypt notes when the caller passes them. Blank string ⇒
            // treat as "clear notes" by still sending an encrypted empty
            // string (backend blanks on empty ciphertext is a server
            // concern; here we preserve user intent).
            if (notes != null) {
                val encrypted = encryptField(notes, "notes")
                encryptedNotes = EncryptedPayload(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                )
            }

            // Keep the current point when the caller doesn't move the place.
            // Encrypt as JSON for cross-platform compatibility with web.
            val locationJson = org.json.JSONObject().apply {
                put("latitude", latitude ?: existingPlace.latitude)
                put("longitude", longitude ?: existingPlace.longitude)
                put("altitude", org.json.JSONObject.NULL)
                put("speed", org.json.JSONObject.NULL)
                put("heading", org.json.JSONObject.NULL)
                put("accuracy", org.json.JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
                put("batteryLevel", org.json.JSONObject.NULL)
                put("isCharging", false)
            }.toString()
            val encryptedLocation = encryptField(locationJson, "location").let {
                EncryptedPayload(nonce = it.nonce, ciphertext = it.ciphertext)
            }

            val response = tracksApi.updatePlace(
                placeId,
                UpdatePlaceRequest(
                    encryptedName = encryptedName,
                    encryptedLocation = encryptedLocation,
                    encryptedNotes = encryptedNotes,
                    radiusMeters = radius ?: existingPlace.radius,
                    // The server rewrites the WHOLE row from this request, so any field
                    // sent null is cleared server-side (resets icon→default pin, color→
                    // default) and clobbers the local value on the next sync. Partial
                    // updates — e.g. the radius-only "alert area" tune from the bell —
                    // pass no icon/color, so fall back to the existing row like
                    // name/location/radius above.
                    icon = icon ?: existingPlace.icon,
                    color = color ?: existingPlace.color,
                    boundaryRadius = boundaryRadius ?: existingPlace.boundaryRadius
                )
            )

            if (response.isSuccessful) {
                val updatedPlace = existingPlace.copy(
                    name = name ?: existingPlace.name,
                    notes = notes ?: existingPlace.notes,
                    latitude = latitude ?: existingPlace.latitude,
                    longitude = longitude ?: existingPlace.longitude,
                    radius = radius ?: existingPlace.radius,
                    icon = icon ?: existingPlace.icon,
                    color = color ?: existingPlace.color,
                    boundaryRadius = boundaryRadius ?: existingPlace.boundaryRadius,
                    updatedAt = System.currentTimeMillis()
                )
                placeDao.update(updatedPlace)
                Result.success(updatedPlace)
            } else {
                Result.failure(RefusedException("Failed to update place", response.code(), ApiErrors.codeOf(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlace(placeId: String): Result<Unit> {
        return try {
            val response = tracksApi.deletePlace(placeId)
            if (response.isSuccessful) {
                placeDao.deleteById(placeId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete place"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compute day bucket (days since Unix epoch) for E2EE timestamp masking.
     */
    private fun computeDayBucket(): Int {
        return (System.currentTimeMillis() / 86400000).toInt()
    }

    /**
     * BLAKE3(circle_key, eventType) server match hash for the mover-side routing
     * path — the place-alerts worker matches a mover's crossing hash against these,
     * so an alert with no hash never fires that way. Null when the circle key isn't
     * available, and null for a circle-less alert: with no routing circle there is no
     * key to hash under and nothing for the server to match, because that alert is
     * evaluated entirely on the subscriber's own device.
     */
    private fun eventHash(circleId: String?, eventType: String): String? {
        if (circleId == null) return null
        return try { cryptoManager.computeEventTypeHash(circleId, eventType) } catch (e: Exception) { null }
    }

    /**
     * @param circleId The circle this alert routes under, or NULL for a purely
     *   LOCAL alert on the user's own personal place. Subscriber-side evaluation
     *   needs no routing circle: the subscriber's own device detects the
     *   crossing and raises the notification, so there is nothing for the blind
     *   server to route. The server accepts a null circle_id for an owner's
     *   alert on their own personal place — and not requiring one means a
     *   personal place never has to be shared to a whole circle to be alerted on.
     */
    suspend fun createPlaceAlert(
        circleId: String?,
        placeId: String,
        targetUserId: String,
        alertOnArrival: Boolean = true,
        alertOnDeparture: Boolean = false,
        activeDays: List<String>? = null,
        activeStartTime: String? = null,
        activeEndTime: String? = null,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        notifySms: Boolean = false,
        isRecurring: Boolean = false,
        expiresAt: String? = null,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<String> {
        return try {
            // E2EE: encrypt the target with the per-PLACE key when the place has one.
            // That key is wrapped to the owner and to every circle the place is
            // shared with, so the alert decrypts for all of them and round-trips
            // regardless of whether the place is personal or circle-owned.
            // Fall back to the circle key for legacy circle-keyed places with no place key.
            val placeKey = placeDao.getById(placeId)?.placeKey
            val encryptedTargetUserId = if (placeKey != null) {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, targetUserId, "target_user_id")
            } else {
                // No per-place key: fall back to the circle key, or — for a
                // circle-less local alert — the device's own personal key, which is
                // how a personal place is encrypted anyway (see refreshPlaces). This
                // keeps the target round-tripping through refreshPlaceAlerts, which
                // decrypts under place.circleId ("self") for such places.
                val keyId = circleId ?: PERSONAL_CIRCLE_ID
                if (!circleRepository.get().ensureCircleKey(keyId)) {
                    return Result.failure(Exception("This circle isn't open on this phone yet."))
                }
                try {
                    circleRepository.get().requireCurrentKey(keyId)
                } catch (e: KeyNotCurrentException) {
                    if (!keepIfBehind || pendingRun != null || circleId == null) throw e
                    return queuePlaceAlert(
                        circleId, placeId, targetUserId, alertOnArrival, alertOnDeparture, activeDays,
                        activeStartTime, activeEndTime, notifyPush, notifyEmail, notifySms, isRecurring, expiresAt,
                    )
                }
                cryptoManager.encryptDataV2(keyId, targetUserId, "target_user_id")
            }
            val dayBucket = computeDayBucket()

            val response = tracksApi.createPlaceAlert(
                CreatePlaceAlertRequest(
                    placeId = placeId,
                    circleId = circleId,
                    targetUserId = targetUserId,
                    encryptedTargetUserId = EncryptedPayload(
                        nonce = encryptedTargetUserId.nonce,
                        ciphertext = encryptedTargetUserId.ciphertext
                    ),
                    alertOnArrival = alertOnArrival,
                    alertOnDeparture = alertOnDeparture,
                    activeDays = activeDays,
                    activeStartTime = activeStartTime,
                    activeEndTime = activeEndTime,
                    // Name the zone the window above was written in. Without it the
                    // column stays 'UTC' and the server-side worker fires a
                    // "08:00-17:00" alert at 08:00 UTC while this device's own
                    // evaluator waits for 08:00 local.
                    timezone = PlaceAlertRules.deviceTimezoneId(),
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    isRecurring = isRecurring,
                    expiresAt = expiresAt,
                    dayBucket = dayBucket,
                    arrivalHash = if (alertOnArrival) eventHash(circleId, "arrival") else null,
                    departureHash = if (alertOnDeparture) eventHash(circleId, "departure") else null,
                    // The Android mover reports boundary crossings hashed over
                    // "enter"/"exit" (LocationTrackingService/BoundaryRepository), so the
                    // place-alerts worker only matches those crossings against the
                    // enter/exit hashes — arrival/departure never match an enter/exit
                    // crossing. Co-populate so newly created alerts actually fire. Same
                    // circle id + toggle as arrival/departure (do not change the keying).
                    enterTypeHash = if (alertOnArrival) eventHash(circleId, "enter") else null,
                    exitTypeHash = if (alertOnDeparture) eventHash(circleId, "exit") else null,
                    clientRequestId = pendingRun?.requestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val alertId = response.body()!!.data!!
                // Refresh alerts to get the full entity
                refreshPlaceAlerts()
                Result.success(alertId)
            } else {
                // Carry error_code/quota on a 402 (place alert limit) so the UI
                // can show the premium prompt.
                Result.failure(ApiErrors.toException(response, "Couldn't save the alert just now."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a circle-wide place alert (target_user_id = null), applying to ALL
     * current and future members of the circle. encrypted_target_user_id carries the
     * sentinel "all" under the same key [createPlaceAlert] would have used.
     *
     * [circleId] follows [createPlaceAlert]: null means a purely local alert.
     */
    suspend fun createCircleWidePlaceAlert(
        circleId: String?,
        placeId: String,
        alertOnArrival: Boolean = true,
        alertOnDeparture: Boolean = false,
        activeDays: List<String>? = null,
        activeStartTime: String? = null,
        activeEndTime: String? = null,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        notifySms: Boolean = false,
        isRecurring: Boolean = true,
        expiresAt: String? = null,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<String> {
        return try {
            // E2EE: encrypt the "all" sentinel with the per-PLACE key when available
            // (round-trips for the owner and every shared circle); fall back to the
            // circle key for legacy circle-keyed places with no place key.
            val placeKey = placeDao.getById(placeId)?.placeKey
            val encryptedTargetUserId = if (placeKey != null) {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, "all", "target_user_id")
            } else {
                // Same key fallback as createPlaceAlert.
                val keyId = circleId ?: PERSONAL_CIRCLE_ID
                if (!circleRepository.get().ensureCircleKey(keyId)) {
                    return Result.failure(Exception("This circle isn't open on this phone yet."))
                }
                try {
                    circleRepository.get().requireCurrentKey(keyId)
                } catch (e: KeyNotCurrentException) {
                    if (!keepIfBehind || pendingRun != null || circleId == null) throw e
                    return queuePlaceAlert(
                        circleId, placeId, null, alertOnArrival, alertOnDeparture, activeDays,
                        activeStartTime, activeEndTime, notifyPush, notifyEmail, notifySms, isRecurring, expiresAt,
                    )
                }
                cryptoManager.encryptDataV2(keyId, "all", "target_user_id")
            }
            val dayBucket = computeDayBucket()

            val response = tracksApi.createPlaceAlert(
                CreatePlaceAlertRequest(
                    placeId = placeId,
                    circleId = circleId,
                    targetUserId = null,
                    encryptedTargetUserId = EncryptedPayload(
                        nonce = encryptedTargetUserId.nonce,
                        ciphertext = encryptedTargetUserId.ciphertext
                    ),
                    alertOnArrival = alertOnArrival,
                    alertOnDeparture = alertOnDeparture,
                    activeDays = activeDays,
                    activeStartTime = activeStartTime,
                    activeEndTime = activeEndTime,
                    // Name the zone the window above was written in. Without it the
                    // column stays 'UTC' and the server-side worker fires a
                    // "08:00-17:00" alert at 08:00 UTC while this device's own
                    // evaluator waits for 08:00 local.
                    timezone = PlaceAlertRules.deviceTimezoneId(),
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    isRecurring = isRecurring,
                    expiresAt = expiresAt,
                    dayBucket = dayBucket,
                    arrivalHash = if (alertOnArrival) eventHash(circleId, "arrival") else null,
                    departureHash = if (alertOnDeparture) eventHash(circleId, "departure") else null,
                    // Co-populated for the same reason as in [createPlaceAlert]: the
                    // Android mover hashes crossings over "enter"/"exit", which never
                    // match an arrival/departure hash.
                    enterTypeHash = if (alertOnArrival) eventHash(circleId, "enter") else null,
                    exitTypeHash = if (alertOnDeparture) eventHash(circleId, "exit") else null,
                    clientRequestId = pendingRun?.requestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val alertId = response.body()!!.data!!
                refreshPlaceAlerts()
                Result.success(alertId)
            } else {
                // Same place-alerts route/limit as createPlaceAlert — carry
                // error_code/quota on a 402 for the premium prompt.
                Result.failure(ApiErrors.toException(response, "Couldn't save the alert just now."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Keep a place alert until this phone catches up on [circleId]: what the person chose, and
     * nothing sealed. [targetUserId] null is the whole circle.
     */
    private suspend fun queuePlaceAlert(
        circleId: String,
        placeId: String,
        targetUserId: String?,
        alertOnArrival: Boolean,
        alertOnDeparture: Boolean,
        activeDays: List<String>?,
        activeStartTime: String?,
        activeEndTime: String?,
        notifyPush: Boolean,
        notifyEmail: Boolean,
        notifySms: Boolean,
        isRecurring: Boolean,
        expiresAt: String?,
    ): Result<String> {
        val circleName = pendingChanges.circleName(circleId)
        val placeName = placeDao.getById(placeId)?.name.orEmpty()
        val memberName = targetUserId?.let { pendingChanges.memberName(circleId, it) }
        return pendingChanges.queue(
            circleId,
            PendingInputs.PlaceAlert(
                circleName, placeId, placeName, targetUserId, memberName, alertOnArrival, alertOnDeparture,
                activeDays, activeStartTime, activeEndTime, notifyPush, notifyEmail, notifySms, isRecurring, expiresAt,
            ),
        )
    }

    /**
     * Refresh place alerts from the server with E2EE decryption.
     */
    suspend fun refreshPlaceAlerts(): Result<List<PlaceAlertEntity>> {
        return try {
            val response = tracksApi.getPlaceAlerts(limit = PLACE_ALERT_PAGE_LIMIT)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val alerts = response.body()!!.data!!.mapNotNull { dto ->
                    try {
                        val place = placeDao.getById(dto.placeId) ?: return@mapNotNull null

                        // E2EE: decrypt the target with the per-PLACE key when the place
                        // has one, mirroring how it was encrypted; fall back to the
                        // circle key for legacy circle-keyed places. A mismatch (e.g.
                        // an old alert keyed to a now-irrelevant circle) throws and is
                        // caught below, dropping that stale row.
                        val placeKey = place.placeKey
                        val encryptedTarget = uniffi.pingolin_mobile.EncryptedData(
                            nonce = dto.encryptedTargetUserId.nonce,
                            ciphertext = dto.encryptedTargetUserId.ciphertext
                        )
                        val decryptedTargetUserId = if (placeKey != null) {
                            cryptoManager.decryptWithPlaceKeyV2(placeKey, encryptedTarget, "target_user_id")
                        } else {
                            if (!cryptoManager.hasCircleKey(place.circleId)) {
                                return@mapNotNull null
                            }
                            cryptoManager.decryptDataV2(place.circleId, encryptedTarget, "target_user_id")
                        }

                        PlaceAlertEntity(
                            id = dto.id,
                            placeId = dto.placeId,
                            // The alert's routing circle (server circle_id). NULL from
                            // an older backend; consumers fall back to place.circleId.
                            circleId = dto.circleId,
                            userId = dto.userId,
                            targetUserId = decryptedTargetUserId,
                            alertOnArrival = dto.alertOnArrival,
                            alertOnDeparture = dto.alertOnDeparture,
                            activeDays = dto.activeDays?.joinToString(","),
                            activeStartTime = dto.activeStartTime,
                            activeEndTime = dto.activeEndTime,
                            isOvernight = dto.isOvernight,
                            notifyPush = dto.notifyPush,
                            notifyEmail = dto.notifyEmail,
                            notifySms = dto.notifySms,
                            isRecurring = dto.isRecurring,
                            isActive = dto.isActive,
                            // Unreadable = no end date, never "now": falling back to the sync
                            // time would store every alert with an end date as already over
                            // on Android 8-13, and an edit here sends the stored value back.
                            expiresAt = dto.expiresAt?.let { raw ->
                                ServerTime.epochMs(raw).also {
                                    if (it == null) android.util.Log.w("PlaceRepository", "Place alert ${dto.id}: unreadable expires_at, kept with no end date")
                                }
                            },
                            dayBucket = dto.dayBucket,
                            suppressedByTarget = dto.suppressedByTarget
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                if (alerts.isNotEmpty()) {
                    placeAlertDao.insertAll(alerts)
                }
                // Drop local alerts the server no longer returns. Inserting
                // alone never deletes, so without this an alert deleted (or
                // replaced) server-side would live on in Room forever — a
                // phantom subscription that still drives local evaluation and,
                // worse, still contributes its routing circle to the crossing
                // emit in reportCrossingFromMonitor.
                //
                // Prune against SERVER truth (every id in the response), NOT
                // the successfully-decrypted subset: an alert the server DID
                // return but we couldn't decrypt this tick — a transiently
                // missing place/circle key — must be RETAINED, not deleted.
                // Same reasoning as the refreshPlaces prune. This whole block
                // only runs inside the success branch, so a failed fetch never
                // prunes anything.
                //
                // The list endpoint is PAGINATED (server default 50, max 100),
                // so a FULL page means there may be more alerts we haven't
                // seen — pruning then would delete every alert past the page
                // boundary. Only prune on a short page, which is the same
                // completeFetch guard refreshPlaces uses for its own prune.
                val serverIds = response.body()!!.data!!.mapTo(HashSet()) { it.id }
                val completeFetch = serverIds.size < PLACE_ALERT_PAGE_LIMIT
                val staleIds = if (completeFetch) {
                    placeAlertDao.getAllIds().filterNot { it in serverIds }
                } else {
                    emptyList()
                }
                if (staleIds.isNotEmpty()) {
                    placeAlertDao.deleteByIdsChunked(staleIds)
                    android.util.Log.i(
                        "PlaceRepository",
                        "Pruned ${staleIds.size} stale local place alerts not returned by server"
                    )
                }
                // Circle-key-rotation recovery: re-derive + refresh the match
                // hashes for any alert the server flagged stale (old-key hashes
                // won't match a mover's new-key crossing). Best-effort.
                for (dto in response.body()!!.data!!) {
                    if (dto.needsHashUpdate != true) continue
                    val place = placeDao.getById(dto.placeId) ?: continue
                    // Re-derive under the ALERT'S routing circle, NOT place.circleId. For a
                    // personal place the latter is the device "self" key, which no other
                    // member can reproduce — so a self-keyed hash never matches a crossing
                    // hashed under the real circle, and the alert silently dies after any
                    // rotation. Prefer the server circle_id; fall back to place.circleId for
                    // an older backend, and skip when that resolves to the self sentinel
                    // (nothing correct to re-key to).
                    val routingCircleId = dto.circleId ?: place.circleId
                    if (routingCircleId == PERSONAL_CIRCLE_ID || routingCircleId == PERSONAL_CIRCLE_UUID) continue
                    val arrival = if (dto.alertOnArrival) eventHash(routingCircleId, "arrival") else null
                    val departure = if (dto.alertOnDeparture) eventHash(routingCircleId, "departure") else null
                    // Re-derive enter/exit alongside arrival/departure: the mover reports
                    // crossings over "enter"/"exit", so those are the hashes the worker
                    // actually matches after a rotation. Same circle id + toggle.
                    val enter = if (dto.alertOnArrival) eventHash(routingCircleId, "enter") else null
                    val exit = if (dto.alertOnDeparture) eventHash(routingCircleId, "exit") else null
                    if (arrival == null && departure == null) continue
                    try {
                        tracksApi.refreshPlaceAlertHashes(
                            dto.id,
                            RefreshPlaceAlertHashesRequest(arrival, departure, enter, exit)
                        )
                    } catch (e: Exception) { /* retried on next refresh */ }
                }
                Result.success(alerts)
            } else {
                Result.failure(Exception("Failed to fetch alerts"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Update a place alert.
     */
    suspend fun updatePlaceAlert(
        alertId: String,
        alertOnArrival: Boolean,
        alertOnDeparture: Boolean,
        activeDays: List<String>? = null,
        activeStartTime: String? = null,
        activeEndTime: String? = null,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        notifySms: Boolean = false,
        isRecurring: Boolean = false,
        isActive: Boolean = true,
        expiresAt: String? = null
    ): Result<Unit> {
        return try {
            // Keep the match hashes in sync with the toggles. The backend COALESCEs
            // a null (keeps the old hash), so this mainly matters for turning a
            // direction ON during an edit (its hash was NULL). Derive them under
            // the ALERT'S routing circle (server circle_id), NOT place.circleId —
            // for a personal place the latter is the device "self" key, whose
            // hashes no crossing ever matches. Fall back to place.circleId for an
            // older alert with no stored circleId, and skip when that resolves to
            // the self sentinel.
            val alert = placeAlertDao.getById(alertId)
            val placeCircleId = alert?.placeId?.let { placeDao.getById(it)?.circleId }
            val routingCircleId = (alert?.circleId ?: placeCircleId)
                ?.takeUnless { it == PERSONAL_CIRCLE_ID || it == PERSONAL_CIRCLE_UUID }
            val arrivalHash = if (alertOnArrival && routingCircleId != null) eventHash(routingCircleId, "arrival") else null
            val departureHash = if (alertOnDeparture && routingCircleId != null) eventHash(routingCircleId, "departure") else null
            // Co-populate enter/exit — the mover reports crossings over "enter"/"exit",
            // so those are the hashes the worker matches. Same circle id + toggle.
            val enterTypeHash = if (alertOnArrival && routingCircleId != null) eventHash(routingCircleId, "enter") else null
            val exitTypeHash = if (alertOnDeparture && routingCircleId != null) eventHash(routingCircleId, "exit") else null
            val response = tracksApi.updatePlaceAlert(
                alertId,
                UpdatePlaceAlertRequest(
                    alertOnArrival = alertOnArrival,
                    alertOnDeparture = alertOnDeparture,
                    activeDays = activeDays,
                    activeStartTime = activeStartTime,
                    activeEndTime = activeEndTime,
                    // Name the zone the window above was written in. Without it the
                    // column stays 'UTC' and the server-side worker fires a
                    // "08:00-17:00" alert at 08:00 UTC while this device's own
                    // evaluator waits for 08:00 local.
                    timezone = PlaceAlertRules.deviceTimezoneId(),
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    isRecurring = isRecurring,
                    isActive = isActive,
                    expiresAt = expiresAt,
                    arrivalHash = arrivalHash,
                    departureHash = departureHash,
                    enterTypeHash = enterTypeHash,
                    exitTypeHash = exitTypeHash
                )
            )

            if (response.isSuccessful) {
                // Refresh alerts to get updated data
                refreshPlaceAlerts()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update alert"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePlaceAlert(alertId: String): Result<Unit> {
        return try {
            val response = tracksApi.deletePlaceAlert(alertId)
            if (response.isSuccessful) {
                placeAlertDao.deleteById(alertId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete alert"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get place alert events (arrival/departure history) for a place.
     * Returns decrypted event data.
     */
    suspend fun getPlaceAlertEvents(
        circleId: String,
        placeId: String
    ): Result<List<DecryptedPlaceAlertEvent>> {
        return try {
            // Check if we have the circle key
            if (!circleRepository.get().ensureCircleKey(circleId)) {
                return Result.failure(Exception("This circle isn't open on this phone yet."))
            }

            val response = tracksApi.getPlaceAlertEvents(placeId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val events = response.body()!!.data!!.mapNotNull { dto ->
                    try {
                        // E2EE: Decrypt event data
                        val decryptedTargetUserId = cryptoManager.decryptDataV2(
                            circleId,
                            uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedTargetUserId.nonce,
                                ciphertext = dto.encryptedTargetUserId.ciphertext
                            ),
                            "target_user_id"
                        )
                        val decryptedDeviceId = cryptoManager.decryptDataV2(
                            circleId,
                            uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedDeviceId.nonce,
                                ciphertext = dto.encryptedDeviceId.ciphertext
                            ),
                            "device_id"
                        )
                        val decryptedEventType = cryptoManager.decryptDataV2(
                            circleId,
                            uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedEventType.nonce,
                                ciphertext = dto.encryptedEventType.ciphertext
                            ),
                            "event_type"
                        )
                        val decryptedTimestamp = cryptoManager.decryptDataV2(
                            circleId,
                            uniffi.pingolin_mobile.EncryptedData(
                                nonce = dto.encryptedTimestamp.nonce,
                                ciphertext = dto.encryptedTimestamp.ciphertext
                            ),
                            "timestamp"
                        ).toLong()

                        DecryptedPlaceAlertEvent(
                            id = dto.id,
                            placeAlertId = dto.placeAlertId,
                            targetUserId = decryptedTargetUserId,
                            deviceId = decryptedDeviceId,
                            eventType = decryptedEventType, // "arrival" or "departure"
                            dayBucket = dto.dayBucket,
                            timestamp = decryptedTimestamp
                        )
                    } catch (e: Exception) {
                        null
                    }
                }
                Result.success(events)
            } else {
                Result.failure(Exception("Failed to fetch alert events"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Copy a place to another circle.
     * The place is decrypted from the source circle and re-encrypted for the target circle.
     */
    suspend fun copyPlaceToCircle(
        place: PlaceEntity,
        targetCircleId: String,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<PlaceEntity> {
        return try {
            // Check if we have both circle keys. Log on miss: a
            // silent failure here leaves no trace of why a share
            // did nothing.
            if (!circleRepository.get().ensureCircleKey(place.circleId)) {
                android.util.Log.e(
                    "PlaceRepository",
                    "copyPlaceToCircle: missing SOURCE circle key for place '${place.name}' (place.circleId=${place.circleId})",
                )
                return Result.failure(Exception("The place's circle isn't open on this phone yet."))
            }
            if (!circleRepository.get().ensureCircleKey(targetCircleId)) {
                android.util.Log.e(
                    "PlaceRepository",
                    "copyPlaceToCircle: missing TARGET circle key (targetCircleId=$targetCircleId)",
                )
                return Result.failure(Exception("That circle isn't open on this phone yet."))
            }
            try {
                circleRepository.get().requireCurrentKey(targetCircleId)
            } catch (e: KeyNotCurrentException) {
                // The person's copy is kept while this phone is behind on the target.
                if (!keepIfBehind || pendingRun != null) throw e
                val circleName = pendingChanges.circleName(targetCircleId)
                return pendingChanges.queue(targetCircleId, PendingInputs.CopyPlaces(circleName, place.id, place.name, place.circleId, 1))
            }

            android.util.Log.d(
                "PlaceRepository",
                "copyPlaceToCircle: place='${place.name}' from=${place.circleId} to=$targetCircleId",
            )

            // Create the place in the target circle (re-encrypted with target key)
            val result = createPlace(
                circleId = targetCircleId,
                name = place.name,
                latitude = place.latitude,
                longitude = place.longitude,
                radius = place.radius,
                icon = place.icon,
                color = place.color,
                clientRequestId = pendingRun?.requestId,
            )
            if (result.isFailure) {
                android.util.Log.e(
                    "PlaceRepository",
                    "copyPlaceToCircle: createPlace failed for target=$targetCircleId",
                    result.exceptionOrNull(),
                )
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("PlaceRepository", "copyPlaceToCircle: unexpected exception", e)
            Result.failure(e)
        }
    }

    /**
     * Copy all places from one circle to another.
     * Returns the number of places successfully copied.
     *
     * With [keepIfBehind] (the person's tap), a copy this phone can't make because it is behind
     * on the target is kept with the places there are now, and made by itself once it catches
     * up. A waiting change's run copies only [onlyPlaceIds] (the places there were), each with
     * its own request id made from the change's and the place's, so a second run makes no
     * second copy, and it fails on a copy that failed.
     */
    suspend fun copyAllPlacesToCircle(
        sourceCircleId: String,
        targetCircleId: String,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
        onlyPlaceIds: Set<String>? = null,
    ): Result<Int> {
        return try {
            // Check if we have both circle keys
            if (!circleRepository.get().ensureCircleKey(sourceCircleId)) {
                return Result.failure(Exception("The place's circle isn't open on this phone yet."))
            }
            if (!circleRepository.get().ensureCircleKey(targetCircleId)) {
                return Result.failure(Exception("That circle isn't open on this phone yet."))
            }
            try {
                circleRepository.get().requireCurrentKey(targetCircleId)
            } catch (e: KeyNotCurrentException) {
                if (!keepIfBehind || pendingRun != null) throw e
                val ids = placeDao.getByCircle(sourceCircleId).map { it.id }
                if (ids.isEmpty()) return Result.success(0)
                val circleName = pendingChanges.circleName(targetCircleId)
                return pendingChanges.queue(
                    targetCircleId,
                    PendingInputs.CopyPlaces(circleName, null, null, sourceCircleId, ids.size),
                    seen = com.google.gson.Gson().toJson(ids),
                )
            }

            // Get all places from the source circle
            val places = placeDao.getByCircle(sourceCircleId)
                .filter { onlyPlaceIds == null || it.id in onlyPlaceIds }
            if (places.isEmpty()) {
                return Result.success(0)
            }

            var successCount = 0
            var failed: Throwable? = null
            for (place in places) {
                val run = pendingRun?.let { it.copy(requestId = copyRequestId(it.requestId, place.id)) }
                val result = copyPlaceToCircle(place, targetCircleId, pendingRun = run)
                if (result.isSuccess) {
                    successCount++
                } else if (run != null) {
                    failed = failed ?: result.exceptionOrNull()
                }
            }

            failed?.let { return Result.failure(it) }
            Result.success(successCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One copy's request id within a waiting "copy all places": made from the change's id
     * and the place's, so a second run sends the same one and the second copy never gets
     * the first one back.
     */
    internal fun copyRequestId(changeId: String, placeId: String): String =
        java.util.UUID.nameUUIDFromBytes("$changeId:$placeId".toByteArray(Charsets.UTF_8)).toString()

    /**
     * Per-place mutex map for legacy-place envelope migration. Without it, two
     * concurrent share calls on the same legacy place both enter the migration, both
     * generate fresh place_keys, both post a different key, and the loser's local
     * PlaceEntity cache silently disagrees with the server. The mutex serializes
     * migrations per place id, so the second caller blocks until the first completes
     * and then proceeds with the now-cached placeKey.
     */
    private val migrationMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /**
     * Migrate a legacy circle-key-direct place to envelope encryption.
     *
     * Re-encrypts every encrypted_* field under a fresh per-place key, wraps the
     * place_key under the user's self-key for ownership and under the original
     * circle's key (if any) for member access, then commits the whole thing via
     * the server's atomic /migrate-to-envelope endpoint. The server applies it
     * in one transaction, and only to a place that has no wrapped key yet, so a
     * half-migrated place is unrepresentable (it all commits or none of it does)
     * and a second concurrent migration gets a 409 Conflict — the local mutex
     * below normally stops it from even trying.
     *
     * Returns the updated PlaceEntity (with placeKey populated) on success, or
     * null when the requester isn't the owner / network fails. The caller falls
     * back to the legacy copy-on-share path in those cases.
     */
    private suspend fun migrateLegacyPlaceToEnvelope(place: PlaceEntity): PlaceEntity? {
        val mutex = migrationMutexes.computeIfAbsent(place.id) { kotlinx.coroutines.sync.Mutex() }
        mutex.lock()
        return try {
            // Re-read the entity after acquiring the lock — if a sibling
            // call already migrated this place, just return the fresh
            // entity instead of re-running the work.
            val current = placeDao.getById(place.id) ?: return null
            if (current.placeKey != null) return current
            doMigrateLegacyPlaceToEnvelope(current)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun doMigrateLegacyPlaceToEnvelope(place: PlaceEntity): PlaceEntity? {
        return try {
            // The fallback decryption key — same one refreshPlaces used
            // when this place was loaded.
            val sourceKeyId = place.circleId
            if (!cryptoManager.hasCircleKey(sourceKeyId)) {
                android.util.Log.w("PlaceRepository", "migrate: source key missing for ${place.id}")
                return null
            }
            // The new place key is wrapped for the place's circle below; under an
            // older key than the circle's, a member removed since could open it. A
            // later pass migrates it, once the current key is here.
            if (sourceKeyId != PERSONAL_CIRCLE_ID && !circleRepository.get().holdsCurrentKey(sourceKeyId)) {
                android.util.Log.i("PlaceRepository", "migrate: waiting for ${place.circleId}'s current key before migrating ${place.id}")
                return null
            }
            // Self-key is the wrap target for the new owner-side wrap: one in use.
            if (!cryptoManager.hasKeyInUse(PERSONAL_CIRCLE_ID)) {
                val ok = locationRepository.ensurePersonalKeyExists()
                if (!ok || !cryptoManager.hasKeyInUse(PERSONAL_CIRCLE_ID)) {
                    android.util.Log.w("PlaceRepository", "migrate: self-key unavailable")
                    return null
                }
            }

            // Generate the new per-place key + re-encrypt every field
            // we care about (name, address, location, notes). Plain-
            // text values are already in the local PlaceEntity from
            // when refreshPlaces decrypted under the circle key.
            val placeKey = cryptoManager.generatePlaceKey()
            val encName = cryptoManager.encryptWithPlaceKeyV2(placeKey, place.name, "name")
            val locationJson = org.json.JSONObject().apply {
                put("latitude", place.latitude)
                put("longitude", place.longitude)
                put("altitude", org.json.JSONObject.NULL)
                put("speed", org.json.JSONObject.NULL)
                put("heading", org.json.JSONObject.NULL)
                put("accuracy", org.json.JSONObject.NULL)
                put("timestamp", System.currentTimeMillis())
                put("batteryLevel", org.json.JSONObject.NULL)
                put("isCharging", false)
            }.toString()
            val encLocation = cryptoManager.encryptWithPlaceKeyV2(placeKey, locationJson, "location")
            val encAddress = place.address?.let {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, it, "address")
            }
            val encNotes = place.notes?.takeIf { it.isNotBlank() }?.let {
                cryptoManager.encryptWithPlaceKeyV2(placeKey, it, "notes")
            }

            // Wrap the new place_key for the owner (self) and for the original circle
            // (if the place lives in one). Both wraps travel together to the atomic
            // migration endpoint, which commits everything or nothing in one
            // transaction, so a half-migrated place cannot exist. The sender and
            // place_id are bound into each wrap signature.
            val senderUserId = sessionManager.getUserId().orEmpty()
            val ownerWrap = cryptoManager.wrapPlaceKey(PERSONAL_CIRCLE_ID, placeKey, senderUserId, place.id)
            val circleShares = if (sourceKeyId != PERSONAL_CIRCLE_ID && cryptoManager.hasCircleKey(sourceKeyId)) {
                val originalCircleWrap = cryptoManager.wrapPlaceKey(sourceKeyId, placeKey, senderUserId, place.id)
                listOf(
                    com.pingolin.track.data.remote.dto.CircleShareWrap(
                        circleId = sourceKeyId,
                        wrappedKey = originalCircleWrap.wrappedKey,
                        wrappedKeyNonce = originalCircleWrap.wrappedKeyNonce,
                    )
                )
            } else {
                emptyList()
            }

            val response = tracksApi.migratePlaceToEnvelope(
                place.id,
                com.pingolin.track.data.remote.dto.MigratePlaceToEnvelopeRequest(
                    encryptedName = EncryptedPayload(nonce = encName.nonce, ciphertext = encName.ciphertext),
                    encryptedAddress = encAddress?.let {
                        EncryptedPayload(nonce = it.nonce, ciphertext = it.ciphertext)
                    },
                    encryptedLocation = EncryptedPayload(nonce = encLocation.nonce, ciphertext = encLocation.ciphertext),
                    encryptedNotes = encNotes?.let {
                        EncryptedPayload(nonce = it.nonce, ciphertext = it.ciphertext)
                    },
                    wrappedKey = ownerWrap.wrappedKey,
                    wrappedKeyNonce = ownerWrap.wrappedKeyNonce,
                    circleShares = circleShares,
                )
            )
            if (!response.isSuccessful || response.body()?.success != true) {
                // 409 means another caller already migrated this place.
                // Re-fetch from server so we see the freshly-set wraps
                // — the next refreshPlaces will populate placeKey for
                // us via the unwrap path.
                if (response.code() == 409) {
                    android.util.Log.i(
                        "PlaceRepository",
                        "migrate: place ${place.id} already migrated (race/idempotency); refreshing from server",
                    )
                    refreshPlaces(if (sourceKeyId == PERSONAL_CIRCLE_ID) null else sourceKeyId)
                    return placeDao.getById(place.id)
                }
                android.util.Log.w(
                    "PlaceRepository",
                    "migrate: server returned ${response.code()} for ${place.id} (likely non-owner)",
                )
                return null
            }

            val migrated = place.copy(placeKey = placeKey)
            placeDao.update(migrated)
            android.util.Log.i("PlaceRepository", "migrate: place ${place.id} converted to envelope")
            migrated
        } catch (e: Exception) {
            android.util.Log.e("PlaceRepository", "migrate: unexpected exception", e)
            null
        }
    }

    /**
     * Share a place with a circle.
     *
     * Envelope path (the place has a cached place_key): wrap the existing
     * place_key under the target circle's key and POST to
     * /api/v1/places/:id/share, and the server records the share. No duplicate
     * place is created — the same encrypted_* fields on the original place become
     * decryptable by both the owner (via the place's own wrapped_key) and circle
     * members (via the share's wrapped_key), so later edits propagate to
     * everyone.
     *
     * Legacy fallback (no cached place_key): copy the place into the target
     * circle re-encrypted under the circle key, which creates a duplicate row
     * whose edits do NOT propagate. Reachable only for a place not migrated to
     * envelope encryption.
     */
    suspend fun sharePlaceWithCircle(
        placeId: String,
        circleId: String,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<Unit> {
        return try {
            val place = placeDao.getById(placeId)
                ?: return Result.failure(Exception("Place not found"))

            val placeKey = place.placeKey
            if (placeKey != null) {
                if (!circleRepository.get().ensureCircleKey(circleId)) {
                    return Result.failure(Exception("That circle isn't open on this phone yet."))
                }
                // The place key wrapped under an older circle key opens for
                // a removed member too.
                circleRepository.get().requireCurrentKey(circleId)
                val senderUserId = sessionManager.getUserId().orEmpty()
                val wrap = try {
                    cryptoManager.wrapPlaceKey(circleId, placeKey, senderUserId, placeId)
                } catch (e: Exception) {
                    android.util.Log.e("PlaceRepository", "sharePlaceWithCircle: wrap failed for circle=$circleId", e)
                    return Result.failure(Exception("Couldn't share the place with that circle."))
                }
                val response = tracksApi.sharePlace(
                    placeId,
                    com.pingolin.track.data.remote.dto.SharePlaceRequest(
                        circleId = circleId,
                        wrappedKey = wrap.wrappedKey,
                        wrappedKeyNonce = wrap.wrappedKeyNonce,
                    )
                )
                if (!response.isSuccessful || response.body()?.success != true) {
                    android.util.Log.w("PlaceRepository", "share refused: ${response.code()} ${response.body()?.error}")
                    return Result.failure(ApiErrors.refused(response, "Couldn't share the place just now."))
                }
                // Update local entity sharedCircles tracking.
                val currentShared = place.sharedCircles
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.toMutableList()
                    ?: mutableListOf()
                if (!currentShared.contains(circleId)) currentShared.add(circleId)
                placeDao.update(place.copy(sharedCircles = currentShared.joinToString(",")))
                return Result.success(Unit)
            }

            // Legacy place hitting the share path: migrate it to envelope encryption
            // first so the result is a single canonical place row with per-recipient
            // wraps, rather than a duplicate row in the target circle.
            val migratedPlace = migrateLegacyPlaceToEnvelope(place)
            if (migratedPlace != null && migratedPlace.placeKey != null) {
                // The recursion takes the envelope branch above.
                return sharePlaceWithCircle(placeId, circleId, keepIfBehind, pendingRun)
            }

            // Migration didn't take — fall back to copying so the user-visible
            // "share" doesn't fail outright.
            android.util.Log.w("PlaceRepository", "Legacy share fallback for place $placeId — migration failed")
            val result = copyPlaceToCircle(place, circleId, pendingRun = pendingRun)
            // Behind on the circle: kept as the share the person asked for, below.
            (result.exceptionOrNull() as? KeyNotCurrentException)?.let { throw it }
            if (result.isSuccess) {
                val currentShared = place.sharedCircles?.split(",")?.filter { it.isNotBlank() }?.toMutableList() ?: mutableListOf()
                if (!currentShared.contains(circleId)) {
                    currentShared.add(circleId)
                }
                val updatedPlace = place.copy(sharedCircles = currentShared.joinToString(","))
                placeDao.update(updatedPlace)
                Result.success(Unit)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to share place"))
            }
        } catch (e: KeyNotCurrentException) {
            // The person's share is kept while this phone is behind on the circle.
            if (!keepIfBehind || pendingRun != null) return Result.failure(e)
            val place = placeDao.getById(placeId) ?: return Result.failure(e)
            val circleName = pendingChanges.circleName(circleId)
            pendingChanges.queue(circleId, PendingInputs.SharePlace(circleName, placeId, place.name))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unshare a place from a circle.
     *
     * A 404 counts as SUCCESS. The server answers 404 "Share not found" when
     * there was no share to delete, which means the end state the caller asked
     * for — this place is not shared with this circle — already holds. Treating
     * it as a failure would leave the user permanently stuck: the UI would roll
     * back to "shared", so the toggle springs back on and can never be turned
     * off, while the server has no share to delete. Local and server state
     * diverge easily here (a second device, a failed share that still updated
     * the local row), so this has to converge rather than deadlock.
     */
    suspend fun unsharePlaceFromCircle(placeId: String, circleId: String): Result<Unit> {
        return try {
            val response = tracksApi.unsharePlace(placeId, circleId)

            // 404 == already not shared; fall through to reconcile local state.
            if (response.isSuccessful || response.code() == 404) {
                // Update local entity to remove the circle from shared list
                val place = placeDao.getById(placeId)
                if (place != null) {
                    val currentShared = place.sharedCircles?.split(",")?.filter { it.isNotBlank() && it != circleId }
                    val updatedPlace = place.copy(sharedCircles = currentShared?.joinToString(",")?.ifBlank { null })
                    placeDao.update(updatedPlace)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception(ApiErrors.plainLine(response, "Couldn't stop sharing the place just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get all places for a circle (non-Flow version for batch operations).
     */
    suspend fun getPlacesByCircle(circleId: String): List<PlaceEntity> {
        return placeDao.getByCircle(circleId)
    }

    /**
     * Update the sort order of places locally.
     * This is a local-only operation - sort order is not synced to the server.
     * Uses a transaction to prevent intermediate Flow emissions.
     */
    suspend fun updatePlaceSortOrder(orderedPlaces: List<PlaceEntity>) {
        val idToSortOrder = orderedPlaces.mapIndexed { index, place ->
            place.id to index
        }.toMap()
        placeDao.updateSortOrderBatch(idToSortOrder)
    }

    fun isInsidePlace(place: PlaceEntity, latitude: Double, longitude: Double): Boolean {
        val distance = calculateDistance(
            place.latitude, place.longitude,
            latitude, longitude
        )
        return distance <= place.radius
    }

    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth radius in meters
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return r * c
    }

    /**
     * Parse location from JSON format (web-created places).
     * Supports both field naming conventions:
     * - Web format: { lat, lng }
     * - SDK format: { latitude, longitude, altitude, speed, heading, accuracy, timestamp, ... }
     */
    private fun parseLocationFromJson(json: String): uniffi.pingolin_mobile.Location {
        val jsonObj = org.json.JSONObject(json)

        // Try latitude/longitude first (SDK format), then lat/lng (web format)
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
}
