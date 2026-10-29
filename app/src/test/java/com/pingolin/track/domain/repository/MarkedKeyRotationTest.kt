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
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The key in use for a circle came here only through an identity from before a password
 * reset, or was taken on trust at first contact. A device that may re-key the circle
 * (can_rotate_keys: an owner or admin) re-keys it, unflagged, rather than go on with it,
 * and a newcomer then gets the new key; a plain member leaves it to the next refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkedKeyRotationTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun TestScope.harness(selfRole: String = "owner") = TrustHarness(selfRole = selfRole).also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me")
        h.circle = h.circle.copy(keyRotationNeeded = false, isOwner = selfRole == "owner")
    }

    @Test
    fun `an owner-only circle whose key was taken on trust is re-keyed, and the joiner gets the new key`() = runTest {
        val h = harness()
        every { h.cryptoManager.activeKeyTakenOnTrust("c-1") } returns true
        // dan joined with this user's invitation: the server flags the circle for him.
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.repository.noteRotationCause("c-1", RotationPlanner.CAUSE_MEMBER_JOINED)
        h.serverLists(roster = listOf(h.rosterMember("me"), h.rosterMember("dan", h.invitation("dan", by = "me"), keyVersion = null)))
        h.deliveriesAccepted()
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue("re-keyed, not handed on", sent.isCaptured)
        assertEquals(setOf("me", "dan"), sent.captured.memberKeys.map { it.userId }.toSet())
    }

    @Test
    fun `an owner re-keys an unflagged circle whose key came only through a restored identity`() = runTest {
        val h = harness()
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } returns true
        h.serverLists(roster = listOf(h.rosterMember("me")))
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(sent.isCaptured)
    }

    @Test
    fun `a plain member with a marked key leaves it to the next refresh`() = runTest {
        val h = harness(selfRole = "member")
        every { h.cryptoManager.activeKeyTakenOnTrust("c-1") } returns true
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } returns true
        h.serverLists(roster = listOf(h.rosterMember("owner", role = "owner"), h.rosterMember("me")))
        h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertFalse(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `an owner's device seals nothing under a marked key until its re-key lands, and then the writes waiting go`() = runTest {
        // Seals wait for the re-key, as on iPhone.
        val h = harness()
        var marked = true
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } answers { marked }
        h.serverLists(roster = listOf(h.rosterMember("me")))
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } answers {
            marked = false // the new key is this device's own
            Response.success(ApiResponse(success = true, data = RotateKeysResponse(newKeyVersion = h.circleVersion + 1), error = null))
        }
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertFalse("seals wait", h.repository.holdsCurrentKey("c-1"))
        assertEquals("not shown as behind", emptySet<String>(), h.repository.behindCircles.value)

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
        assertTrue(h.repository.holdsCurrentKey("c-1"))
        verify { h.pendingChanges.kick() }
    }

    @Test
    fun `a plain member's device goes on sealing under a marked key`() = runTest {
        val h = harness(selfRole = "member")
        every { h.cryptoManager.activeKeyTakenOnTrust("c-1") } returns true
        h.serverLists(roster = listOf(h.rosterMember("owner", role = "owner"), h.rosterMember("me")))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(h.repository.holdsCurrentKey("c-1"))
    }

    @Test
    fun `an unmarked, unflagged circle is left alone`() = runTest {
        val h = harness()
        // With its records: a record-less one would get its first record at once.
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns true
        h.serverLists(roster = listOf(h.rosterMember("me")))
        h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertFalse(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }
}
