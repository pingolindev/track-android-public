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

package com.pingolin.track.location

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.AccountIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.Location

/**
 * The phone's half of the re-seal after a rotation: this account's last location in a circle where its
 * real-time sharing is off, sealed again under the circle's current key after a rotation, so its members
 * still see where it was (with real time off, the user's last location is the one shown). The apps draw
 * such a row only under the current key, so without this the marker goes at the next rotation.
 *
 * The point and the bytes come from this phone's own record of what it sealed and sent ([OwnLastSent]),
 * never from the row the server serves, which may be a removed member's forgery:
 * - the served row is only found, by its payload's bytes being the record's, field by field;
 * - the new payload seals the record's position and time, and nothing else (no battery: a frozen row is
 *   never judged);
 * - the served blobs go back only as `previous_*`, untouched, for the server's compare-and-swap.
 *
 * Due only while sharing there is off in real time, not paused (nothing is sent while paused), with the
 * account allowed to track at all ([accessOpen]: a re-seal is a send), and never finer than the circle
 * gets now ([coarsenRadiusNow]): a point sealed precisely is not sealed again for a circle that has
 * since been switched to sharing approximately, whose later members hold only the new key. Once per
 * circle and version: a newer version is tried once, whatever comes of it, and a refusal is never
 * retried.
 */
class LastKnownReseal(
    private val store: OwnLastSentStore,
    private val api: TrackApi,
    private val crypto: CryptoManager,
    private val privacyOf: suspend (circleId: String) -> PrivacySettingEntity?,
    private val me: () -> String?,
    private val accessOpen: () -> Boolean,
    /** How coarse a point taken now goes to the circle, in metres (LocationRepository.coarsenRadiusMetersFor). */
    private val coarsenRadiusNow: suspend (circleId: String) -> Double,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Outcome { NOT_DUE, NO_MATCH, RESEALED, ADOPTED, REFUSED, FAILED }

    private val mutex = Mutex()

    /** Every circle this account holds a record for: after a privacy refresh (the privacy_check wake). */
    suspend fun runAll() {
        for (circleId in store.circles()) run(circleId)
    }

    /** [circleId]'s, when one is due there. Never throws. */
    suspend fun run(circleId: String): Outcome = mutex.withLock {
        try {
            attempt(circleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "re-seal in $circleId not tried: ${e.javaClass.simpleName}")
            Outcome.FAILED
        }
    }

    private suspend fun attempt(circleId: String): Outcome {
        if (!accessOpen()) return Outcome.NOT_DUE
        var record = store.get(circleId) ?: return Outcome.NOT_DUE
        val privacy = privacyOf(circleId) ?: return Outcome.NOT_DUE
        if (privacy.shareRealtime || privacy.isSharingPausedAt(now())) return Outcome.NOT_DUE
        val user = me() ?: return Outcome.NOT_DUE
        val active = crypto.getActiveVersion(circleId)

        // A re-seal sent before whose answer never came back: landed if the server serves this phone's own
        // row as exactly that request, under its `c:{version}` and with no wrap. Otherwise it never took,
        // and the record stands as it was.
        var latest: JsonObject? = null
        var adopted = false
        if (record.pendingNonce != null) {
            val read = api.getLatestLocationsAsServed(circleId)
            if (!read.isSuccessful) return Outcome.FAILED
            latest = read.body()
            adopted = pendingServed(latest, user, record)
            val settled = if (adopted) record.pendingLanded() else record.copy(pendingNonce = null, pendingCiphertext = null, pendingVersion = null)
            // A send that landed meanwhile stands: this run then has nothing left to do.
            if (!store.update(circleId, record, settled)) return Outcome.NOT_DUE
            record = settled
        }

        if (active <= record.keyVersion || record.triedVersion == active) return if (adopted) Outcome.ADOPTED else Outcome.NOT_DUE
        // Never finer than the circle gets now: not tried, so a return to exact can still re-seal it. A
        // record that doesn't say how coarse it was sealed counts as exact.
        if ((record.coarsenRadiusMeters ?: 0.0) < coarsenRadiusNow(circleId)) return Outcome.NOT_DUE

        if (latest == null) {
            val read = api.getLatestLocationsAsServed(circleId)
            if (!read.isSuccessful) return Outcome.FAILED
            latest = read.body()
        }
        val row = ownFrozenRow(latest, user, record)
        if (row == null) {
            store.update(circleId, record, record.copy(triedVersion = active))
            return Outcome.NO_MATCH
        }

        // The record's position and time, sealed under the current key as a normal send is.
        val point = Location(
            latitude = record.latitude,
            longitude = record.longitude,
            altitude = null,
            speed = null,
            heading = null,
            accuracy = record.accuracy,
            timestamp = record.timestampMs,
            batteryLevel = null,
            isCharging = false,
        )
        val sealed = crypto.encryptLocationBound(circleId, point, crypto.deriveUserPseudonym(circleId, user))
        val sealedTime = row.timestamp?.let { crypto.encryptTimestamp(circleId, record.timestampMs) }
        val body = JsonObject().apply {
            addProperty("device_pseudonym", record.devicePseudonym)
            add("previous_encrypted_payload", row.payload)
            row.timestamp?.let { add("previous_encrypted_timestamp", it) }
            add("encrypted_payload", JsonObject().apply {
                addProperty("nonce", sealed.nonce)
                addProperty("ciphertext", sealed.ciphertext)
                addProperty("key_id", "c:$active")
            })
            sealedTime?.let {
                add("encrypted_timestamp", JsonObject().apply {
                    addProperty("nonce", it.nonce)
                    addProperty("ciphertext", it.ciphertext)
                })
            }
        }
        // Kept before the request goes: its answer may never come back.
        val sent = record.copy(pendingNonce = sealed.nonce, pendingCiphertext = sealed.ciphertext, pendingVersion = active)
        // A send that landed meanwhile stands, and leaves this row behind: nothing to re-seal.
        if (!store.update(circleId, record, sent)) return Outcome.NOT_DUE
        val answer = api.resealLastKnown(circleId, body)
        // Each write below goes only over the record this run left: never over a send that landed
        // while the request was out.
        if (answer.isSuccessful) {
            // What the server holds now is what was just sent: the record follows the request, never
            // the answer. The row keeps its device and pseudonym on the server, so the record does too.
            store.update(circleId, sent, sent.pendingLanded())
            return Outcome.RESEALED
        }
        if (answer.code() == 404) {
            // Refused: nothing changed on the server, and nothing is pending.
            store.update(circleId, sent, record.copy(triedVersion = active))
            return Outcome.REFUSED
        }
        // No answer to read: still pending, looked at again at the next run.
        return Outcome.FAILED
    }

    /**
     * Whether [latest] serves this account's own frozen row as exactly [record]'s pending re-seal: its
     * nonce and ciphertext, the `c:{version}` it named, and no wrap. Only this phone's own sealed bytes.
     */
    internal fun pendingServed(latest: JsonObject?, user: String, record: OwnLastSent): Boolean {
        val version = record.pendingVersion ?: return false
        val rows = latest?.objectAt("data")?.get("last_known")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return rows.any { element ->
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            val payload = row.objectAt("encrypted_payload") ?: return@any false
            AccountIds.same(row.stringAt("user_id"), user) &&
                sameBytes(payload.stringAt("nonce"), record.pendingNonce) &&
                sameBytes(payload.stringAt("ciphertext"), record.pendingCiphertext) &&
                payload.stringAt("key_id") == "c:$version" &&
                payload.stringAt("wrapped_msg_key") == null && payload.stringAt("wrapped_msg_key_nonce") == null
        }
    }

    /** A served `last_known` row's two blobs, untouched. */
    internal class ServedRow(val payload: JsonObject, val timestamp: JsonObject?)

    /**
     * This account's frozen row in the latest read whose payload is [record]'s, byte for byte in
     * every field it carries, or null. Ids are compared in their one form.
     */
    internal fun ownFrozenRow(latest: JsonObject?, user: String, record: OwnLastSent): ServedRow? {
        val rows = latest?.objectAt("data")?.get("last_known")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        for (element in rows) {
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (!AccountIds.same(row.stringAt("user_id"), user)) continue
            val payload = row.objectAt("encrypted_payload") ?: continue
            if (!payloadIsRecord(payload, record)) continue
            return ServedRow(payload, row.objectAt("encrypted_timestamp"))
        }
        return null
    }

    private fun payloadIsRecord(served: JsonObject, record: OwnLastSent): Boolean {
        if (!sameBytes(served.stringAt("nonce"), record.nonce)) return false
        if (!sameBytes(served.stringAt("ciphertext"), record.ciphertext)) return false
        val servedWrap = served.stringAt("wrapped_msg_key")
        val servedWrapNonce = served.stringAt("wrapped_msg_key_nonce")
        val servedKeyId = served.stringAt("key_id")
        return if (record.wrappedKey != null || record.wrappedKeyNonce != null) {
            // An envelope: its wrap for this circle, and no key named.
            sameBytes(servedWrap, record.wrappedKey) && sameBytes(servedWrapNonce, record.wrappedKeyNonce) &&
                servedKeyId.isNullOrEmpty()
        } else {
            servedWrap == null && servedWrapNonce == null && when (val keyId = record.keyId) {
                // The session key it was sealed under, by name.
                null -> servedKeyId == null || !servedKeyId.startsWith("s:")
                else -> servedKeyId == keyId
            }
        }
    }

    internal companion object {
        private const val TAG = "LastKnownReseal"

        /**
         * Whether two blob fields hold the same bytes, whichever alphabet each is in: the app holds
         * the SDK's lowercase hex, and a row may come back in hex or base64. Compared as bytes, never
         * as text, so an encoding's variants never read as different.
         */
        fun sameBytes(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            val left = decodings(a)
            return decodings(b).any { r -> left.any { it.contentEquals(r) } }
        }

        private fun decodings(s: String): List<ByteArray> = listOfNotNull(
            s.takeIf { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
                ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray(),
            runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()?.takeIf { it.isNotEmpty() },
        )

        private fun JsonObject.objectAt(name: String): JsonObject? =
            get(name)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.stringAt(name: String): String? =
            get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
    }
}
