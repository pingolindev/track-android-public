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

import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.OpenedText

/**
 * This account's own sealed name in each circle, through the real CircleRepository: the roster-load
 * backfill, as iOS's sealOwnNameIfOwed does it. Every app names a member only by the sealed name it
 * opens itself, and a name sealed only at create and join would miss every circle whose current key
 * wasn't here then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnNameBackfillTest {

    @After
    fun tearDown() = clearAllMocks()

    // Real account ids: "is this me?" compares canonical ids, and an id that is no UUID is nobody (AccountIds).
    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val ann = "a0000000-0000-4000-8000-0000000000a1"
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"

    /** The names this account sealed and sent, as the server got them, in order. */
    private val sent = mutableListOf<Map<String, Any>>()

    /** A harness whose member list has [me] (owner) and [ann], [me] named [displayName], and the server takes a name. */
    private fun TestScope.harness(displayName: String? = "Me Myself", ownSealed: EncryptedPayload? = null): TrustHarness {
        val h = TrustHarness(self = me, selfRole = "owner", canonicalIds = true, circleId = fam)
        h.repository.deliveryScope = this
        h.repository.ownNameScope = this
        h.earlierSeed(me, ann)
        coEvery { h.tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(emptyList(), more = false), error = null))
        every { h.sessionManager.getDisplayName() } returns displayName
        every { h.cryptoManager.encryptDataV2(h.circleId, any(), "display_name") } answers {
            EncryptedData(nonce = "n:${secondArg<String>()}", ciphertext = "c:${secondArg<String>()}@v${h.heldKeyVersion}")
        }
        coEvery { h.tracksApi.setDisplayName(h.circleId, any()) } answers {
            sent += secondArg<Map<String, Any>>()
            Response.success(ApiResponse(success = true, data = Unit, error = null))
        }
        h.serverLists(listOf(h.rosterMember(me, role = "owner").copy(encryptedDisplayName = ownSealed), h.rosterMember(ann)))
        return h
    }

    private fun sealedOf(name: String, version: Int = 3) =
        mapOf("encrypted_display_name" to mapOf("nonce" to "n:$name", "ciphertext" to "c:$name@v$version"))

    /** What this account's row's sealed name opens as here, and under which key version. */
    private fun TrustHarness.opensAs(text: String, version: Int) {
        every { cryptoManager.decryptDataV2Opened(circleId, any(), "display_name") } returns OpenedText(text, version)
        every { cryptoManager.isCurrentKeyVersion(circleId, any()) } answers { secondArg<Int>() == heldKeyVersion }
    }

    @Test
    fun `a row with no sealed name gets this account's name sealed under the current key, once a process`() = runTest {
        val h = harness()

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()
        assertEquals(listOf(sealedOf("Me Myself")), sent)

        // The server didn't reflect it back: not asked again and again.
        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()
        assertEquals(1, sent.size)
    }

    @Test
    fun `a sealed name that opens here as this name under the current key sends nothing`() = runTest {
        val h = harness(ownSealed = EncryptedPayload(nonce = "n", ciphertext = "c"))
        h.opensAs("Me Myself", version = 3)

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(emptyList<Map<String, Any>>(), sent)
    }

    @Test
    fun `a name sealed under a key from before a rotation is sealed again under the current one`() = runTest {
        val h = harness(ownSealed = EncryptedPayload(nonce = "n", ciphertext = "c"))
        h.opensAs("Me Myself", version = 2)

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(listOf(sealedOf("Me Myself")), sent)
    }

    @Test
    fun `a name changed since it was sealed is sealed again as the new one`() = runTest {
        val h = harness(ownSealed = EncryptedPayload(nonce = "n", ciphertext = "c"))
        h.opensAs("Old Name", version = 3)

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(listOf(sealedOf("Me Myself")), sent)
    }

    @Test
    fun `a sealed name that doesn't open here is sealed again`() = runTest {
        val h = harness(ownSealed = EncryptedPayload(nonce = "n", ciphertext = "c"))
        every { h.cryptoManager.decryptDataV2Opened(h.circleId, any(), "display_name") } throws
            uniffi.pingolin_mobile.MobileException.DecryptionFailed("no key opens it")

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(listOf(sealedOf("Me Myself")), sent)
    }

    @Test
    fun `nothing is sealed while this device is behind in the circle`() = runTest {
        val h = harness()
        h.heldKeyVersion = 2

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(emptyList<Map<String, Any>>(), sent)
    }

    @Test
    fun `with no display name the address is the name, and with neither nothing is sealed`() = runTest {
        val h = harness(displayName = "   ")
        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()
        assertEquals(listOf(sealedOf("$me@x.test")), sent)

        sent.clear()
        val none = harness(displayName = null)
        every { none.sessionManager.getEmail() } returns null
        none.repository.refreshMembers(none.circleId)
        advanceUntilIdle()
        assertEquals(emptyList<Map<String, Any>>(), sent)
    }

    @Test
    fun `a write the server refused is tried again at the next load`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.setDisplayName(h.circleId, any()) } answers {
            sent += secondArg<Map<String, Any>>()
            Response.error(503, "".toResponseBody(null))
        }

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()
        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        assertEquals(listOf(sealedOf("Me Myself"), sealedOf("Me Myself")), sent)
    }

    @Test
    fun `create and join send it again even where this process sealed it, never for the personal key's slot`() = runTest {
        val h = harness()
        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()
        assertEquals(1, sent.size)

        // A new membership's row starts with no name. The personal key's slot, held and current as
        // it always is, is no circle (AccountIds.isPersonalSlot).
        val slot = com.pingolin.track.util.AccountIds.PERSONAL_SLOT
        every { h.cryptoManager.hasCircleKey(slot) } returns true
        every { h.cryptoManager.getActiveVersion(slot) } returns 1
        coEvery { h.tracksApi.setDisplayName(slot, any()) } answers {
            sent += mapOf("slot" to secondArg<Map<String, Any>>())
            Response.success(ApiResponse(success = true, data = Unit, error = null))
        }
        coEvery { h.circleDao.getAll() } returns listOf(h.circle, h.circle.copy(id = slot))
        h.repository.uploadEncryptedDisplayNames()

        assertEquals(listOf(sealedOf("Me Myself"), sealedOf("Me Myself")), sent)
    }

    @Test
    fun `create and join seal nothing where this device is behind`() = runTest {
        val h = harness()
        h.heldKeyVersion = 2
        coEvery { h.circleDao.getAll() } returns listOf(h.circle)

        h.repository.uploadEncryptedDisplayNames()

        assertEquals(emptyList<Map<String, Any>>(), sent)
    }

    @Test
    fun `a key that moved on here, by an import or this device's own rotation, gets the name sealed under it`() {
        // Both are the points where this account's SOS and frozen last location are re-sealed for a new key.
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        assertTrue(src.contains("                    scheduleLastKnownReseal(moved)\n                    // And its name, which a newcomer can't open under the older key.\n                    scheduleOwnNameSeal(moved)\n"))
        assertTrue(src.contains("            scheduleLastKnownReseal(listOf(circleId))\n            // And its name, which a newcomer can't open under the older key.\n            scheduleOwnNameSeal(listOf(circleId))\n"))
    }
}
