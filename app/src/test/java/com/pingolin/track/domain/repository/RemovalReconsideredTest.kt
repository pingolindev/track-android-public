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
import com.pingolin.track.data.remote.dto.PendingInviteResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Re-invited before that rotation: a removal this device made without a key change, and no
 * record of its own has named yet, stands (over-removal is mended by one more invitation;
 * under-removal leaks). Only this account changing its mind clears it: a re-invite made from
 * this device, or a newer statement about the member signed by this account under its current
 * key. Another admin's statement doesn't.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovalReconsideredTest {

    /** Accounts the invite lookup names are UUIDs (AccountIds.strict). */
    private val bob = "b0b00000-0000-4000-8000-00000000000b"

    @After
    fun tearDown() = clearAllMocks()

    /** "me", the owner, removed bob without a key change 100 seconds ago. */
    private fun TestScope.harness() = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice")
        h.ownRemovals.add("c-1", bob, h.statements.now - 100)
    }

    @Test
    fun `a re-invite of their account made from this device clears the removal`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.lookupPublicKey("bob@x.test") } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse("bob@x.test", sharing(bob), bob, signing(bob)), error = null)
        )
        coEvery { h.tracksApi.inviteToCircle("c-1", any()) } returns Response.success(
            ApiResponse(true, InviteResponse(delivery = "notified"), null)
        )

        assertTrue(h.repository.inviteToCircle("c-1", "bob@x.test").isSuccess)

        assertEquals(emptySet<String>(), h.ownRemovals.of("c-1"))
    }

    @Test
    fun `a re-invite withdrawn from here brings its removal back, as it was`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.lookupPublicKey("bob@x.test") } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse("bob@x.test", sharing(bob), bob, signing(bob)), error = null)
        )
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns Response.success(
            ApiResponse(true, InviteResponse(delivery = "notified"), null)
        )
        coEvery { h.tracksApi.revokeInvite("c-1", "inv-1") } returns Response.success(ApiResponse(true, Unit, null))
        assertTrue(h.repository.inviteToCircle("c-1", "bob@x.test").isSuccess)
        assertEquals(emptySet<String>(), h.ownRemovals.of("c-1"))
        // The answer names no invitation: the owner's list does, with its statement.
        coEvery { h.tracksApi.getCircleInvites("c-1") } returns Response.success(ApiResponse(true, listOf(
            PendingInviteResponse(
                id = "inv-1", inviteType = "email", targetEmail = "bob@x.test", status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, targetUserId = bob,
                statement = sent.captured.statement, statementSignature = sent.captured.statementSignature,
            ),
        ), null))

        assertTrue(h.repository.revokeInvite("c-1", "inv-1").isSuccess)

        assertEquals(setOf(bob), h.ownRemovals.of("c-1"))
        assertEquals(h.statements.now - 100, h.ownRemovals.removedAt("c-1", bob))
    }

    @Test
    fun `a re-invite withdrawn from its pending row brings its removal back, even while the server can't be reached`() = runTest {
        val h = harness()
        // Re-invited from here before a restart: only the pending list names the invitation now.
        h.ownRemovals.clearedByInvitation("c-1", bob, "st-inv", h.statements.now + 86_400)
        assertEquals(emptySet<String>(), h.ownRemovals.of("c-1"))
        coEvery { h.tracksApi.revokeInvite("c-1", "inv-1") } throws java.io.IOException("offline")

        assertTrue(h.repository.withdrawInvitation("c-1", "inv-1", "st-inv").isFailure)

        assertEquals("the invitation is dead here: its removal stands", setOf(bob), h.ownRemovals.of("c-1"))
        assertEquals(h.statements.now - 100, h.ownRemovals.removedAt("c-1", bob))
    }

    @Test
    fun `a removal reconsidered by this account's invitation on their row is kept against it - run out with them off the list, it's back`() = runTest {
        val h = harness()
        val expires = h.statements.now + 86_400
        val invitation = h.statements.statement(
            Kind.INVITATION_TO_ACCOUNT, "c-1", "me", signing("me"), bob, sharing(bob), signing(bob),
            id = "st-back", expiresAt = expires, issuedAt = h.statements.now,
        )
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", role = "admin"), h.rosterMember(bob, vouches = listOf(invitation))))
        h.repository.refreshMembers("c-1")
        assertEquals(emptySet<String>(), h.ownRemovals.of("c-1"))

        // They left again before any plan, and the invitation ran out.
        h.ownRemovals.settleRunOut("c-1", listOf("me", "alice"), expires + 301)

        assertEquals(setOf(bob), h.ownRemovals.of("c-1"))
        assertEquals(h.statements.now - 100, h.ownRemovals.removedAt("c-1", bob))
    }

    @Test
    fun `a newer statement of this account's about them clears it - an older one, another admin's, another key's, one about someone else, an address invitation, another circle's or a withdrawn one doesn't`() = runTest {
        fun TrustHarness.about(
            by: String,
            issuedAt: Long,
            kind: Kind = Kind.ADMISSION,
            key: String = signing(by),
            subject: String = bob,
            circle: String = "c-1",
            id: String = "st-about",
        ) = if (kind == Kind.INVITATION_TO_EMAIL) {
            statements.statement(kind, circle, by, key, email = "bob@x.test", issuedAt = issuedAt, id = id)
        } else {
            statements.statement(kind, circle, by, key, subject, sharing(subject), signing(subject), issuedAt = issuedAt, id = id)
        }
        val cases = listOf<Pair<String, TrustHarness.() -> MembershipStatements.Signed>>(
            "newer, this account's" to { about("me", statements.now) },
            "newer, this account's invitation" to { about("me", statements.now, Kind.INVITATION_TO_ACCOUNT) },
            "older, this account's" to { about("me", statements.now - 200) },
            "newer, another admin's" to { about("alice", statements.now) },
            "newer, in this account's name under another key" to { about("me", statements.now, key = signing("mallory")) },
            "newer, this account's, about someone else, on their row" to { about("me", statements.now, subject = "carol") },
            "newer, this account's, to their address" to { about("me", statements.now, Kind.INVITATION_TO_EMAIL) },
            "newer, this account's, another circle's" to { about("me", statements.now, Kind.INVITATION_TO_ACCOUNT, circle = "c-2") },
            "newer, this account's invitation, withdrawn here" to {
                approvals.markInvitationDead("c-1", "INV-GONE")
                about("me", statements.now, Kind.INVITATION_TO_ACCOUNT, id = "inv-gone")
            },
        )
        for ((case, statement) in cases) {
            val h = harness()
            h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", role = "admin"), h.rosterMember(bob, vouches = listOf(h.statement()))))

            h.repository.refreshMembers("c-1")

            val expected = if (case == "newer, this account's" || case == "newer, this account's invitation") emptySet() else setOf(bob)
            assertEquals(case, expected, h.ownRemovals.of("c-1"))
        }
    }
}
