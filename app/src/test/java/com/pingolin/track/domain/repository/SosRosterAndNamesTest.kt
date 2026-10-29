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

import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * What an SOS's judge reads from the member list: who this device SHOWS in a circle, once this
 * process judged its complete list, and whether a member's name opened here or is the server's
 * plain fallback.
 */
class SosRosterAndNamesTest {

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `the roster an SOS reads - no decision before this process judged the list, then only the members shown`() = runTest {
        val h = TrustHarness(selfRole = "member")
        assertNull("no member list judged in this process", h.repository.memberShownIn("c-1", "owner"))
        h.earlierSeed("me", "owner")

        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("old")))
        h.repository.refreshMembers("c-1")

        assertEquals(true, h.repository.memberShownIn("c-1", "owner"))
        assertEquals("this account itself", true, h.repository.memberShownIn("c-1", "me"))
        assertEquals("listed and hidden here", false, h.repository.memberShownIn("c-1", "old"))
        assertEquals("not listed", false, h.repository.memberShownIn("c-1", "stranger"))
        assertNull("another circle: no decision", h.repository.memberShownIn("c-2", "owner"))
    }

    @Test
    fun `a member's name opened here is marked so, and the list's plain fallback never is`() = runTest {
        val h = TrustHarness(selfRole = "member")
        h.earlierSeed("me", "owner")
        val sealed = EncryptedPayload(nonce = "n", ciphertext = "sealed-olivia")
        val broken = EncryptedPayload(nonce = "n", ciphertext = "doesn't open")
        every { h.cryptoManager.decryptDataV2("c-1", any(), "display_name") } answers {
            if (arg<uniffi.pingolin_mobile.EncryptedData>(1).ciphertext == "sealed-olivia") "Olivia" else throw IllegalStateException("no")
        }
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner").copy(encryptedDisplayName = sealed)))
        h.repository.refreshMembers("c-1")
        var rows = h.storedRows.associateBy { it.userId }
        assertEquals("Olivia", rows.getValue("owner").displayName)
        assertTrue(rows.getValue("owner").nameOpened)
        assertEquals("Me", rows.getValue("me").displayName)
        assertFalse("a plain name is the server's", rows.getValue("me").nameOpened)

        h.serverLists(listOf(
            h.rosterMember("me"),
            h.rosterMember("owner", role = "owner").copy(displayName = "Owner (the server's)", encryptedDisplayName = broken),
        ))
        h.repository.refreshMembers("c-1")
        rows = h.storedRows.associateBy { it.userId }
        assertEquals("Owner (the server's)", rows.getValue("owner").displayName)
        assertFalse("a sealed name that doesn't open falls back, unmarked", rows.getValue("owner").nameOpened)
    }

    @Test
    fun `a key import that moves a circle's version takes this account's own open SOS there along, and one that moves none doesn't`() = runTest {
        val h = TrustHarness(selfRole = "member")
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, error = null, data = listOf(
            CircleKeyBundleResponse("c-1", EncryptedPayload("n", "c"), "e", 4, senderUserId = "owner"),
        )))
        every { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            h.heldKeyVersion = 4
            Unit
        }

        h.repository.importCircleKeys()
        // The import is the key read the re-seal needs: none again.
        coVerify(exactly = 1) { h.sosRepository.resealOwn(setOf("c-1"), keysRead = true) }

        h.repository.importCircleKeys()
        coVerify(exactly = 1) { h.sosRepository.resealOwn(any(), any()) }
    }

    @Test
    fun `a key import that moves a circle's version has an SOS waiting there judged again, and one that moves none doesn't`() = runTest {
        val h = TrustHarness(selfRole = "member")
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, error = null, data = listOf(
            CircleKeyBundleResponse("c-1", EncryptedPayload("n", "c"), "e", 4, senderUserId = "owner"),
        )))
        every { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            h.heldKeyVersion = 4
            Unit
        }

        h.repository.importCircleKeys()
        verify(exactly = 1) { h.sosRepository.keysMoved(setOf("c-1")) }

        h.repository.importCircleKeys()
        verify(exactly = 1) { h.sosRepository.keysMoved(any()) }
    }
}
