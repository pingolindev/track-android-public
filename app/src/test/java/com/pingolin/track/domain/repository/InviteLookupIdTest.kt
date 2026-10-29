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
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.pending.CouldNotCheck
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import io.mockk.CapturingSlot
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The account the address check names for an invitee: 8-4-4-4-12 in any case, or 32 hex, is that
 * account, lower-cased and hyphenated, and only that id is pinned, wrapped to, signed and
 * recorded. Any other id is no answer: never "no account" (the address-only invitation) and never
 * a first contact; nothing is pinned, wrapped, signed or sent, and a waiting run waits. None named
 * is the address-only invitation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteLookupIdTest {

    @After
    fun tearDown() = clearAllMocks()

    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val email = "dan@x.test"

    private fun TestScope.harness() = TrustHarness(self = me, selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed(me)
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false
    }

    /** The address check names [id] for dan's address, with the keys of [keysOf]. */
    private fun TrustHarness.directoryNames(id: String?, keysOf: String = dan) {
        coEvery { tracksApi.lookupPublicKey(email) } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse(email, sharing(keysOf), id, signing(keysOf)), error = null)
        )
    }

    private fun TrustHarness.invitationsTaken(): CapturingSlot<InviteRequest> {
        val sent = slot<InviteRequest>()
        coEvery { tracksApi.inviteToCircle("c-1", capture(sent)) } returns Response.success(
            ApiResponse(true, InviteResponse(delivery = "notified"), null)
        )
        return sent
    }

    private fun TrustHarness.nothingPinnedWrappedSignedOrSent(case: String) {
        verify(exactly = 0) { cryptoManager.pinMemberKeys(any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.approveMemberKeyChange(any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.wrapCircleKeyForMemberFull(any(), any(), any(), any(), any()) }
        assertEquals(case, emptyList<MembershipStatements.Signed>(), statements.signed)
        coVerify(exactly = 0) { tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `the account named in another spelling the rule takes is pinned, wrapped to, signed and recorded under its canonical id`() = runTest {
        for (spelling in listOf(dan.uppercase(), dan.replace("-", ""), dan.replace("-", "").uppercase())) {
            val h = harness()
            h.directoryNames(spelling)
            val sent = h.invitationsTaken()

            assertTrue(spelling, h.repository.inviteToCircle("c-1", email).isSuccess)

            verify { h.cryptoManager.pinMemberKeys("c-1", dan, sharing(dan), signing(dan)) }
            verify { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing(dan), me, dan, signing(dan)) }
            val statement = h.statements.parse(sent.captured.statement!!)!!
            assertEquals(spelling, Kind.INVITATION_TO_ACCOUNT, statement.kind)
            assertEquals(spelling, dan, statement.subjectUserId)
            assertEquals(spelling, setOf(dan), h.approvals.pendingInvitees("c-1").keys)
            verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), spelling, any(), any()) }
        }
    }

    @Test
    fun `upper case or 32 hex finds the account's pin - changed keys ask "Is this really…?"`() = runTest {
        for (spelling in listOf(dan.uppercase(), dan.replace("-", ""))) {
            val h = harness()
            h.pins.pin(dan, sharing(dan), signing(dan))
            h.directoryNames(spelling, keysOf = "mallory")

            val changed = h.repository.inviteToCircle("c-1", email).exceptionOrNull() as InviteeChangedException

            assertEquals(spelling, dan, changed.userId)
            assertEquals(spelling, sharing("mallory"), changed.sharingKeyHex)
            h.nothingPinnedWrappedSignedOrSent(spelling)
        }
    }

    @Test
    fun `any other id is no answer - "Couldn't check", nothing pinned, wrapped, signed or sent`() = runTest {
        for (notId in listOf("{$dan}", "urn:uuid:$dan", " $dan", "$dan ", "user-dan", dan.replace("-", "_"), "${dan.replace("-", "")}0", "{${dan.replace("-", "")}}")) {
            val h = harness()
            h.directoryNames(notId)

            val result = h.repository.inviteToCircle("c-1", email, keepIfBehind = true)

            assertEquals(notId, "Couldn't check dan@x.test just now.", result.exceptionOrNull()?.message)
            h.nothingPinnedWrappedSignedOrSent(notId)
            assertEquals(notId, emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
        }
    }

    @Test
    fun `a waiting invitation whose check names no UUID waits, and nothing goes`() = runTest {
        val h = harness()
        h.directoryNames("{$dan}")

        val result = h.repository.inviteToCircle("c-1", email, pendingRun = PendingRun("change-1", "change-1"))

        assertTrue(result.exceptionOrNull() is CouldNotCheck)
        h.nothingPinnedWrappedSignedOrSent("waiting")
    }

    @Test
    fun `none named is the address-only invitation`() = runTest {
        for (none in listOf(null, "", "  ")) {
            val h = harness()
            h.directoryNames(none)
            val sent = h.invitationsTaken()

            assertTrue("$none", h.repository.inviteToCircle("c-1", email).isSuccess)

            assertNull("$none", sent.captured.encryptedCircleKey)
            assertEquals("$none", Kind.INVITATION_TO_EMAIL, h.statements.parse(sent.captured.statement!!)!!.kind)
            verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `"It's them" is for the account in any spelling the rule takes, and approves its details under the canonical id`() = runTest {
        val h = harness()
        h.pins.pin(dan, sharing(dan), signing(dan))
        h.directoryNames(dan.replace("-", "").uppercase(), keysOf = "mallory")
        h.invitationsTaken()
        val changed = h.repository.inviteToCircle("c-1", email).exceptionOrNull() as InviteeChangedException

        assertTrue(h.repository.confirmInviteeAndInvite(changed).isSuccess)

        verify { h.cryptoManager.approveMemberKeyChange("c-1", dan, sharing("mallory"), signing("mallory")) }
        verify { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing("mallory"), me, dan, signing("mallory")) }

        // Answered for dan: the check now naming no UUID is no answer, not "someone else".
        h.directoryNames("{$dan}", keysOf = "mallory")
        assertEquals("Couldn't check dan@x.test just now.", h.repository.confirmInviteeAndInvite(changed).exceptionOrNull()?.message)
    }
}
