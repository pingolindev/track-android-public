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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * The phone's half of re-sealing a frozen last location: this account's frozen last location in a
 * circle where its real-time sharing is off, sealed again under the circle's new key after a rotation.
 * The point and the bytes come from this phone's own record of what it sealed and sent, never from the
 * served row.
 */
class LastKnownResealTest {

    private companion object {
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val ME = "a0a0a0a0-0000-4000-8000-0000000000a0"
        const val SOMEONE = "b0b0b0b0-0000-4000-8000-0000000000b0"
        const val NOW = 1_760_000_000_000L
        val NONCE = ByteArray(24) { (it + 1).toByte() }
        val CIPHERTEXT = ByteArray(40) { (it * 7 + 3).toByte() }
        val WRAP = ByteArray(48) { (it * 5 + 1).toByte() }
        val WRAP_NONCE = ByteArray(24) { (it * 3 + 2).toByte() }
        val NEW_SEAL = EncryptedData(nonce = "11".repeat(24), ciphertext = "22".repeat(40))
        val NEW_TIME = EncryptedData(nonce = "33".repeat(24), ciphertext = "44".repeat(24))

        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    }

    /** The store, in memory. */
    private class MemoryStore : OwnLastSentStore {
        val rows = mutableMapOf<String, OwnLastSent>()
        override fun get(circleId: String) = rows[circleId]
        override fun put(circleId: String, record: OwnLastSent) { rows[circleId] = record }
        override fun update(circleId: String, from: OwnLastSent, to: OwnLastSent): Boolean {
            if (rows[circleId] != from) return false
            rows[circleId] = to
            return true
        }
        override fun remove(circleId: String) { rows.remove(circleId) }
        override fun circles() = rows.keys.toList()
        override fun wipeAll() = rows.clear()
    }

    private val store = MemoryStore()
    private val api = mockk<TrackApi>()
    private val crypto = mockk<CryptoManager>()
    private var privacy: PrivacySettingEntity? = PrivacySettingEntity(circleId = CIRCLE, updatedAt = 0L, shareRealtime = false)
    private var access = true
    /** How coarse a point taken now goes to the circle, in metres: exact by default. */
    private var radiusNow = 0.0
    private val sealedPoints = mutableListOf<Location>()
    private val sealedTimes = mutableListOf<Long>()
    private val bodies = mutableListOf<JsonObject>()

    private val record = OwnLastSent(
        devicePseudonym = "device-ps", nonce = hex(NONCE), ciphertext = hex(CIPHERTEXT), keyId = "s:7",
        wrappedKey = null, wrappedKeyNonce = null, latitude = 37.3349, longitude = -122.009, accuracy = 12.0,
        timestampMs = NOW - 3_600_000L, keyVersion = 2, coarsenRadiusMeters = 0.0,
    )

    private fun reseal() = LastKnownReseal(
        store = store, api = api, crypto = crypto, privacyOf = { privacy }, me = { ME }, accessOpen = { access },
        coarsenRadiusNow = { radiusNow }, now = { NOW },
    )

    /** /latest as the server serves it, with [rows] as its last_known. */
    private fun served(vararg rows: JsonObject) = Response.success(JsonObject().apply {
        addProperty("success", true)
        add("data", JsonObject().apply {
            add("locations", JsonArray())
            add("last_known", JsonArray().apply { rows.forEach { add(it) } })
        })
    })

    private fun frozenRow(
        user: String = ME,
        payload: JsonObject = JsonObject().apply {
            addProperty("nonce", b64(NONCE)); addProperty("ciphertext", b64(CIPHERTEXT)); addProperty("key_id", "s:7")
        },
        time: JsonObject? = JsonObject().apply { addProperty("nonce", "55".repeat(24)); addProperty("ciphertext", "66".repeat(24)) },
    ) = JsonObject().apply {
        addProperty("user_id", user)
        add("encrypted_payload", payload)
        time?.let { add("encrypted_timestamp", it) }
        addProperty("location_sharing", "off")
    }

    private fun given(latest: Response<JsonObject>, answer: Int = 200, active: Int = 3) {
        store.put(CIRCLE, store.get(CIRCLE) ?: record)
        every { crypto.getActiveVersion(CIRCLE) } returns active
        every { crypto.deriveUserPseudonym(CIRCLE, ME) } returns "user-ps-current"
        val point = slot<Location>()
        every { crypto.encryptLocationBound(CIRCLE, capture(point), "user-ps-current") } answers { sealedPoints += point.captured; NEW_SEAL }
        val time = slot<Long>()
        every { crypto.encryptTimestamp(CIRCLE, capture(time)) } answers { sealedTimes += time.captured; NEW_TIME }
        coEvery { api.getLatestLocationsAsServed(CIRCLE) } returns latest
        val body = slot<JsonObject>()
        coEvery { api.resealLastKnown(CIRCLE, capture(body)) } answers {
            bodies += body.captured
            if (answer == 200) Response.success(JsonObject().apply { addProperty("success", true) })
            else Response.error(answer, """{"success":false,"error":"There is no last location to re-seal"}""".toResponseBody())
        }
    }

    @Test
    fun `this account's own frozen row, its payload the record's, is sealed again from the record under the current key`() = runTest {
        val row = frozenRow(user = ME.uppercase())
        given(served(row))

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))

        // The record's position and time, sealed as a normal send is: no battery, no
        // motion.
        val point = sealedPoints.single()
        assertEquals(record.latitude, point.latitude, 0.0)
        assertEquals(record.longitude, point.longitude, 0.0)
        assertEquals(record.accuracy, point.accuracy)
        assertEquals(record.timestampMs, point.timestamp)
        assertNull(point.batteryLevel)
        assertNull(point.altitude); assertNull(point.speed); assertNull(point.heading)
        assertEquals(listOf(record.timestampMs), sealedTimes)

        val body = bodies.single()
        assertEquals("device-ps", body.get("device_pseudonym").asString)
        // The served blobs go back untouched, for the server's compare-and-swap.
        assertEquals(row.get("encrypted_payload"), body.get("previous_encrypted_payload"))
        assertEquals(row.get("encrypted_timestamp"), body.get("previous_encrypted_timestamp"))
        val payload = body.getAsJsonObject("encrypted_payload")
        assertEquals(NEW_SEAL.nonce, payload.get("nonce").asString)
        assertEquals(NEW_SEAL.ciphertext, payload.get("ciphertext").asString)
        assertEquals("c:3", payload.get("key_id").asString)
        assertFalse(payload.has("wrapped_msg_key"))
        assertEquals(NEW_TIME.nonce, body.getAsJsonObject("encrypted_timestamp").get("nonce").asString)
        assertEquals(NEW_TIME.ciphertext, body.getAsJsonObject("encrypted_timestamp").get("ciphertext").asString)

        // The record follows the request it built, never the answer; the row keeps its device.
        val kept = store.get(CIRCLE)!!
        assertEquals(NEW_SEAL.nonce, kept.nonce)
        assertEquals(NEW_SEAL.ciphertext, kept.ciphertext)
        assertEquals("c:3", kept.keyId)
        assertEquals(3, kept.keyVersion)
        assertEquals("device-ps", kept.devicePseudonym)
        assertEquals(record.timestampMs, kept.timestampMs)
    }

    @Test
    fun `a served row that isn't the record, a forgery among them, is never touched`() = runTest {
        val forged = CIPHERTEXT.copyOf().also { it[0] = (it[0] + 1).toByte() }
        given(served(frozenRow(payload = JsonObject().apply {
            addProperty("nonce", b64(NONCE)); addProperty("ciphertext", b64(forged)); addProperty("key_id", "s:7")
        })))

        assertEquals(LastKnownReseal.Outcome.NO_MATCH, reseal().run(CIRCLE))

        coVerify(exactly = 0) { api.resealLastKnown(any(), any()) }
        assertTrue(sealedPoints.isEmpty())
        // Tried once for this version, whatever came of it.
        assertEquals(3, store.get(CIRCLE)!!.triedVersion)
    }

    @Test
    fun `my bytes under another member's id are not my row`() = runTest {
        given(served(frozenRow(user = SOMEONE)))

        assertEquals(LastKnownReseal.Outcome.NO_MATCH, reseal().run(CIRCLE))
        coVerify(exactly = 0) { api.resealLastKnown(any(), any()) }
    }

    /** The served time is never compared, and never used: the new time is the record's. */
    @Test
    fun `a matching payload with a swapped time is sealed with the record's time, the served time only passed back`() = runTest {
        val swapped = JsonObject().apply { addProperty("nonce", "77".repeat(24)); addProperty("ciphertext", "88".repeat(24)) }
        given(served(frozenRow(time = swapped)))

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))

        assertEquals(listOf(record.timestampMs), sealedTimes)
        val body = bodies.single()
        assertEquals(swapped, body.get("previous_encrypted_timestamp"))
        assertEquals(NEW_TIME.nonce, body.getAsJsonObject("encrypted_timestamp").get("nonce").asString)
        assertEquals(NEW_TIME.ciphertext, body.getAsJsonObject("encrypted_timestamp").get("ciphertext").asString)
    }

    @Test
    fun `a row with no sealed time gets none`() = runTest {
        given(served(frozenRow(time = null)))

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))

        assertTrue(sealedTimes.isEmpty())
        assertFalse(bodies.single().has("encrypted_timestamp"))
        assertFalse(bodies.single().has("previous_encrypted_timestamp"))
    }

    @Test
    fun `a refusal is final for that version, never looped`() = runTest {
        given(served(frozenRow()), answer = 404)

        assertEquals(LastKnownReseal.Outcome.REFUSED, reseal().run(CIRCLE))
        assertEquals(3, store.get(CIRCLE)!!.triedVersion)
        assertEquals(LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        coVerify(exactly = 1) { api.getLatestLocationsAsServed(CIRCLE) }
        coVerify(exactly = 1) { api.resealLastKnown(CIRCLE, any()) }
        // The record's bytes are what the server still holds: untouched.
        assertEquals(record.ciphertext, store.get(CIRCLE)!!.ciphertext)
    }

    // ===== a re-seal whose answer never came back =====

    /** The row as the server serves it once our re-seal under `c:3` landed: this phone's own new bytes. */
    private fun resealedRow(keyId: String = "c:3", wrap: Boolean = false) = frozenRow(payload = JsonObject().apply {
        addProperty("nonce", NEW_SEAL.nonce); addProperty("ciphertext", NEW_SEAL.ciphertext); addProperty("key_id", keyId)
        if (wrap) { addProperty("wrapped_msg_key", hex(WRAP)); addProperty("wrapped_msg_key_nonce", hex(WRAP_NONCE)) }
    })

    /** A re-seal sent under `c:3` whose 200 was lost on the way back. */
    private suspend fun lostAnswer() {
        given(served(frozenRow()))
        coEvery { api.resealLastKnown(CIRCLE, any()) } throws java.io.IOException("connection reset")
        assertEquals(LastKnownReseal.Outcome.FAILED, reseal().run(CIRCLE))
        val kept = store.get(CIRCLE)!!
        assertEquals(NEW_SEAL.nonce, kept.pendingNonce)
        assertEquals(NEW_SEAL.ciphertext, kept.pendingCiphertext)
        assertEquals(3, kept.pendingVersion)
        assertEquals("the record still names what the server held", record.ciphertext, kept.ciphertext)
    }

    @Test
    fun `a 200 lost on the way back is taken as landed at the next run, from this phone's own bytes`() = runTest {
        lostAnswer()
        coEvery { api.getLatestLocationsAsServed(CIRCLE) } returns served(resealedRow())

        assertEquals(LastKnownReseal.Outcome.ADOPTED, reseal().run(CIRCLE))

        val kept = store.get(CIRCLE)!!
        assertEquals(NEW_SEAL.nonce, kept.nonce)
        assertEquals(NEW_SEAL.ciphertext, kept.ciphertext)
        assertEquals("c:3", kept.keyId)
        assertEquals(3, kept.keyVersion)
        assertNull(kept.wrappedKey)
        assertNull(kept.pendingNonce)
        assertNull(kept.triedVersion)
        coVerify(exactly = 1) { api.resealLastKnown(CIRCLE, any()) }
    }

    @Test
    fun `once taken as landed, the next version is sealed again in the same run`() = runTest {
        lostAnswer()
        every { crypto.getActiveVersion(CIRCLE) } returns 4
        val row = resealedRow()
        coEvery { api.getLatestLocationsAsServed(CIRCLE) } returns served(row)
        coEvery { api.resealLastKnown(CIRCLE, any()) } answers { Response.success(JsonObject()) }

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))

        coVerify(exactly = 2) { api.resealLastKnown(CIRCLE, any()) }
        coVerify(exactly = 2) { api.getLatestLocationsAsServed(CIRCLE) }
        assertEquals(4, store.get(CIRCLE)!!.keyVersion)
        assertNull(store.get(CIRCLE)!!.pendingNonce)
    }

    /** Real-time back on mid-run: its fresh record is never written over. */
    @Test
    fun `a send that lands while a re-seal is out is never written over by its answer, nor by a refusal`() = runTest {
        val fresh = record.copy(nonce = "99".repeat(24), ciphertext = "aa".repeat(40), keyId = "s:9", keyVersion = 3, timestampMs = NOW)
        for (code in listOf(200, 404)) {
            store.rows.clear()
            given(served(frozenRow()), answer = code)
            coEvery { api.resealLastKnown(CIRCLE, any()) } answers {
                store.put(CIRCLE, fresh)
                if (code == 200) Response.success(JsonObject())
                else Response.error(404, """{"success":false}""".toResponseBody())
            }

            reseal().run(CIRCLE)

            assertEquals("answer $code", fresh, store.get(CIRCLE))
        }
    }

    @Test
    fun `a refusal leaves nothing pending`() = runTest {
        given(served(frozenRow()), answer = 404)

        assertEquals(LastKnownReseal.Outcome.REFUSED, reseal().run(CIRCLE))

        assertNull(store.get(CIRCLE)!!.pendingNonce)
        assertNull(store.get(CIRCLE)!!.pendingVersion)
    }

    @Test
    fun `a row like the pending re-seal but under another key, or with a wrap, is not taken as landed`() = runTest {
        for (unlike in listOf(resealedRow(keyId = "c:4"), resealedRow(wrap = true))) {
            store.rows.clear()
            lostAnswer()
            coEvery { api.getLatestLocationsAsServed(CIRCLE) } returns served(unlike)

            assertEquals(LastKnownReseal.Outcome.NO_MATCH, reseal().run(CIRCLE))

            val kept = store.get(CIRCLE)!!
            assertEquals("not taken: the record still names the old bytes", record.ciphertext, kept.ciphertext)
            assertNull(kept.pendingNonce)
        }
    }

    @Test
    fun `nothing while real-time sharing is on, while paused, with access closed, or before the key moves`() = runTest {
        given(served(frozenRow()))

        privacy = PrivacySettingEntity(circleId = CIRCLE, updatedAt = 0L, shareRealtime = true)
        assertEquals(LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        privacy = PrivacySettingEntity(
            circleId = CIRCLE, updatedAt = 0L, shareRealtime = false,
            sharingMode = PrivacySettingEntity.MODE_PAUSED, pausedUntil = NOW + 60_000L,
        )
        assertEquals("deferred until the pause ends", LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        privacy = PrivacySettingEntity(circleId = CIRCLE, updatedAt = 0L, shareRealtime = false)
        access = false
        assertEquals("a re-seal is a send", LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        access = true
        every { crypto.getActiveVersion(CIRCLE) } returns record.keyVersion
        assertEquals(LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        privacy = null
        every { crypto.getActiveVersion(CIRCLE) } returns 3
        assertEquals("no posture to read is no re-seal", LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))

        coVerify(exactly = 0) { api.getLatestLocationsAsServed(any()) }
        coVerify(exactly = 0) { api.resealLastKnown(any(), any()) }
    }

    @Test
    fun `a pause that has ended no longer holds it back`() = runTest {
        privacy = PrivacySettingEntity(
            circleId = CIRCLE, updatedAt = 0L, shareRealtime = false,
            sharingMode = PrivacySettingEntity.MODE_PAUSED, pausedUntil = NOW - 1L,
        )
        given(served(frozenRow()))

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))
    }

    @Test
    fun `an envelope record matches by its wrap, its bytes in either alphabet, and names no key`() = runTest {
        store.put(CIRCLE, record.copy(keyId = null, wrappedKey = hex(WRAP), wrappedKeyNonce = hex(WRAP_NONCE)))
        val envelope = JsonObject().apply {
            addProperty("nonce", hex(NONCE)); addProperty("ciphertext", b64(CIPHERTEXT)); addProperty("key_id", "")
            addProperty("wrapped_msg_key", b64(WRAP)); addProperty("wrapped_msg_key_nonce", hex(WRAP_NONCE))
        }
        given(served(frozenRow(payload = envelope)))

        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))
        assertEquals(envelope, bodies.single().get("previous_encrypted_payload"))
        // Re-sealed straight under the circle key: no wrap left on the record.
        assertNull(store.get(CIRCLE)!!.wrappedKey)
    }

    @Test
    fun `an envelope with another wrap is not the record`() = runTest {
        store.put(CIRCLE, record.copy(keyId = null, wrappedKey = hex(WRAP), wrappedKeyNonce = hex(WRAP_NONCE)))
        val other = WRAP.copyOf().also { it[5] = (it[5] + 1).toByte() }
        given(served(frozenRow(payload = JsonObject().apply {
            addProperty("nonce", hex(NONCE)); addProperty("ciphertext", hex(CIPHERTEXT)); addProperty("key_id", "")
            addProperty("wrapped_msg_key", b64(other)); addProperty("wrapped_msg_key_nonce", hex(WRAP_NONCE))
        })))

        assertEquals(LastKnownReseal.Outcome.NO_MATCH, reseal().run(CIRCLE))
        coVerify(exactly = 0) { api.resealLastKnown(any(), any()) }
    }

    @Test
    fun `a session-key record matches only its own key id`() = runTest {
        given(served(frozenRow(payload = JsonObject().apply {
            addProperty("nonce", b64(NONCE)); addProperty("ciphertext", b64(CIPHERTEXT)); addProperty("key_id", "s:8")
        })))

        assertEquals(LastKnownReseal.Outcome.NO_MATCH, reseal().run(CIRCLE))
    }

    @Test
    fun `bytes compare as bytes, whichever alphabet each side is in`() {
        assertTrue(LastKnownReseal.sameBytes(hex(CIPHERTEXT), b64(CIPHERTEXT)))
        assertTrue(LastKnownReseal.sameBytes(hex(CIPHERTEXT).uppercase(), hex(CIPHERTEXT)))
        assertTrue(LastKnownReseal.sameBytes(b64(CIPHERTEXT), b64(CIPHERTEXT)))
        assertFalse(LastKnownReseal.sameBytes(hex(CIPHERTEXT), b64(NONCE)))
        assertFalse(LastKnownReseal.sameBytes(null, hex(NONCE)))
        assertFalse(LastKnownReseal.sameBytes("", ""))
    }

    @Test
    fun `a record survives its line, an old line reads as of an unknown radius, and another format as none`() {
        val envelope = record.copy(
            keyId = null, wrappedKey = hex(WRAP), wrappedKeyNonce = hex(WRAP_NONCE), accuracy = null, triedVersion = 4,
            coarsenRadiusMeters = 8046.72,
        )
        assertEquals(record, OwnLastSent.decode(record.encode()))
        assertEquals(envelope, OwnLastSent.decode(envelope.encode()))
        val pending = record.copy(pendingNonce = NEW_SEAL.nonce, pendingCiphertext = NEW_SEAL.ciphertext, pendingVersion = 3)
        assertEquals(pending, OwnLastSent.decode(pending.encode()))
        // Format 2 kept no pending re-seal, and format 1 no radius either: unknown, never exact by mistake.
        val fields = record.encode().split('\t')
        assertEquals(record, OwnLastSent.decode((listOf("2") + fields.subList(1, 14)).joinToString("\t")))
        assertEquals(record.copy(coarsenRadiusMeters = null), OwnLastSent.decode((listOf("1") + fields.subList(1, 13)).joinToString("\t")))
        assertNull(OwnLastSent.decode((listOf("4") + fields.drop(1)).joinToString("\t")))
        assertNull(OwnLastSent.decode("garbage"))
    }

    /** Never sealed again finer than the circle gets now. */
    @Test
    fun `a precise point is not sealed again while the circle shares approximately, and is once it's exact again`() = runTest {
        given(served(frozenRow()))
        radiusNow = 8046.72 // 5 miles

        assertEquals(LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))
        coVerify(exactly = 0) { api.getLatestLocationsAsServed(any()) }
        assertNull("not tried, so a return to exact can still re-seal it", store.get(CIRCLE)!!.triedVersion)

        radiusNow = 0.0
        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))
    }

    @Test
    fun `a point sealed coarse may be sealed again however the circle shares now, one of unknown coarseness only while exact`() = runTest {
        store.put(CIRCLE, record.copy(coarsenRadiusMeters = 8046.72))
        given(served(frozenRow()))
        radiusNow = 1609.34
        assertEquals("coarser than now: never finer", LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))

        store.put(CIRCLE, record.copy(coarsenRadiusMeters = null, keyVersion = 2))
        radiusNow = 1609.34
        assertEquals(LastKnownReseal.Outcome.NOT_DUE, reseal().run(CIRCLE))
        radiusNow = 0.0
        assertEquals(LastKnownReseal.Outcome.RESEALED, reseal().run(CIRCLE))
    }

    @Test
    fun `every circle with a record is looked at, each on its own`() = runTest {
        given(served(frozenRow()))
        store.put("d2d2d2d2-0000-4000-8000-0000000000d2", record)
        every { crypto.getActiveVersion("d2d2d2d2-0000-4000-8000-0000000000d2") } returns record.keyVersion

        reseal().runAll()

        coVerify(exactly = 1) { api.resealLastKnown(CIRCLE, any()) }
        verify(exactly = 0) { crypto.encryptLocationBound("d2d2d2d2-0000-4000-8000-0000000000d2", any(), any()) }
    }
}
