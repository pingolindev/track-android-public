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
import com.pingolin.track.data.remote.dto.PendingInviteResponse
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Invitee records and the transition seed, through the real CircleRepository:
 * - The transition seed is settled against the circle's first complete member list. A pinned
 *   user it doesn't list — an invitee still pending when an earlier app pinned them, or a
 *   member long gone whose pin was never retired — gets NOTHING: no entry, no invitation
 *   record, no tombstone. The signed-invitation rules decide when they appear.
 * - An invitation record makes its invitee this user's own only when they appear with the
 *   invitation signed here; withdrawn, it goes, and the invitation confirms nobody. Checked on
 *   a phone that did NOT create the circle, for the chosen path and catch-up delivery only (the
 *   creator's rule and what a rotation does are tested elsewhere).
 * - A member who joins on an invitation from an app older than signed invitations is hidden
 *   on every device and counted in the owner/admin "kept out" line; nobody is asked.
 * - This device's own records only, never an invitation a listed member carries. Missing from
 *   the server's pending list, a record only goes (the id stays live: the two lists come from
 *   replicas that can lag each other); the id goes dead only when a COMPLETE member list shows
 *   this user as no longer an owner or admin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteeRecordAndSeedTest {

    @After
    fun tearDown() = clearAllMocks()

    /** A device of "me", the circle's owner, that has only run builds from before signed
     * invitations.
     */
    private fun TestScope.transitionDevice(heldPins: List<String>): TrustHarness =
        TrustHarness(selfRole = "owner").also { h ->
            h.repository.deliveryScope = this
            val pins = (heldPins + "me").joinToString(",") { "\"c-1:$it\": {}" }
            every { h.cryptoManager.exportMemberPins("me") } returns """{"pins": {$pins}}"""
            // What the earlier app held: the pins it made at invite time and on every view.
            for (id in heldPins) h.pins.pin(id, sharing(id), signing(id))
        }

    @Test
    fun `a pending legacy invitee held at the seed is not tombstoned, and on joining stays hidden and is never asked about`() = runTest {
        val h = transitionDevice(heldPins = listOf("alice", "dan"))
        // The first complete member list: dan (invited by the earlier app) hasn't joined.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice")))

        h.repository.refreshMembers("c-1")

        assertEquals(mapOf("alice" to Origin.PRE_ROUND2), h.approvals.origins("c-1").filterKeys { it != "me" })
        assertEquals("no tombstone for the pending invitee", emptyMap<String, Int>(), h.approvals.tombstones("c-1"))
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))

        // dan joins on the earlier app's unsigned invitation: hidden on the owner's phone too,
        // counted in the "kept out" line, never asked about, handed nothing.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice"), h.rosterMember("dan", keyVersion = null)))
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")

        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
        assertTrue(h.questions.none { it.startsWith("join") })
        assertTrue(h.storedRows.none { it.userId == "dan" })
        assertEquals(setOf("dan"), h.repository.membersHiddenIn("c-1"))
        assertEquals(1, h.repository.memberStates.value["c-1"]?.keptOut)
        assertFalse(delivered.containsKey("dan"))
        assertFalse(h.approvals.origins("c-1").containsKey("dan"))
        assertEquals(emptyMap<String, Int>(), h.approvals.tombstones("c-1"))
    }

    @Test
    fun `a removed member pinned at the seed and listed again later is neither confirmed nor delivered to`() = runTest {
        val h = transitionDevice(heldPins = listOf("alice", "mallory"))
        h.approvals.markCreatedHere("c-1")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice")))
        h.repository.refreshMembers("c-1")

        // The server lists mallory again, with no statement from anyone.
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("mallory", keyVersion = null)))
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")

        assertFalse(h.approvals.origins("c-1").containsKey("mallory"))
        assertFalse("not even by the device that created the circle", delivered.containsKey("mallory"))
        coVerify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", any(), "me", "mallory", any()) }
    }

    @Test
    fun `a withdrawn invitation's invitee listed again is not own, not confirmed, not handed the circle`() = runTest {
        // A phone of the owner that did not create the circle.
        val h = TrustHarness(selfRole = "owner").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }
        // "me" invited dan with a signed invitation, recorded as inviteToCircle records it.
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // ... and withdrew it from the pending invitations.
        coEvery { h.tracksApi.getCircleInvites("c-1") } returns Response.success(ApiResponse(success = true, data = listOf(
            PendingInviteResponse(
                id = "i-1", inviteType = "email", targetEmail = "dan@x.test", status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, statement = invite.statementHex,
                statementSignature = invite.signatureHex, targetUserId = "dan",
            ),
        ), error = null))
        coEvery { h.tracksApi.revokeInvite("c-1", "i-1") } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        h.repository.refreshPendingInvites("c-1")
        assertTrue(h.repository.revokeInvite("c-1", "i-1").isSuccess)
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))

        // The server lists dan anyway, carrying the withdrawn invitation.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("dan", invite, keyVersion = null)))
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")

        assertFalse(h.approvals.origins("c-1").containsKey("dan"))
        assertFalse(delivered.containsKey("dan"))
        coVerify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", any(), "me", "dan", any()) }
    }

    @Test
    fun `an expired invitation's invitee listed later is not own through the record, and not handed the circle`() = runTest {
        val h = TrustHarness(selfRole = "owner").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }
        // "me" invited dan; it ran out, and its five minutes, before anyone saw him listed.
        val invite = h.invitation("dan", by = "me", id = "inv-dan", expiresAt = h.statements.now - 301)
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now - 301)
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("dan", invite, keyVersion = null)))
        val delivered = h.deliveriesAccepted()

        h.repository.deliverOwedAccess("c-1")

        // Still confirmed by the invitation, but nobody's own through the record, and no
        // catch-up delivery from here.
        assertEquals(Origin.STATEMENT, h.approvals.origins("c-1")["dan"])
        assertFalse(delivered.containsKey("dan"))
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
    }

    @Test
    fun `an invitee listed with another invitation than the one signed here is not own through the record`() = runTest {
        val h = TrustHarness(selfRole = "owner").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // Listed carrying someone else's invitation of him.
        val others = h.invitation("dan", by = "owner", id = "inv-dan-2")
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("dan", others, keyVersion = null)))

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.STATEMENT, h.approvals.origins("c-1")["dan"])
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
    }

    @Test
    fun `an invitee who joins with the invitation signed here is own and handed the circle`() = runTest {
        val h = TrustHarness(selfRole = "owner").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("dan", invite, keyVersion = null)))
        val delivered = h.deliveriesAccepted()

        assertEquals(1, h.repository.deliverOwedAccess("c-1"))

        assertEquals(Origin.OWN, h.approvals.origins("c-1")["dan"])
        assertTrue(delivered.containsKey("dan"))
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
    }

    // ---- Invitations the server withdrew, or no longer lists ---------------------------

    /** "me", an owner, invited [who] from this phone: the record inviteToCircle makes. */
    private fun TestScope.inviterDevice(role: String = "owner"): TrustHarness =
        TrustHarness(selfRole = role).also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }

    private fun pendingList(vararg invites: com.pingolin.track.domain.repository.MembershipStatements.Signed) =
        Response.success(ApiResponse(success = true, data = invites.mapIndexed { i, invite ->
            PendingInviteResponse(
                id = "i-$i", inviteType = "email", targetEmail = "x$i@x.test", status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, statement = invite.statementHex,
                statementSignature = invite.signatureHex,
            )
        }, error = null))

    private fun TrustHarness.serverPending(vararg invites: com.pingolin.track.domain.repository.MembershipStatements.Signed) {
        coEvery { tracksApi.getCircleInvites("c-1") } returns pendingList(*invites)
    }

    @Test
    fun `missing from the server's pending list, a record goes but its id stays live - who just accepted is confirmed on the next list`() = runTest {
        val h = inviterDevice()
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // dan has just accepted: the pending list (one replica) no longer has the invitation,
        // and the member list (another, behind it) doesn't show him yet.
        h.serverPending()
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
        assertFalse(h.approvals.invitationDead("c-1", "inv-dan"))
        // The next list shows him with it: confirmed by the invitation (no longer this user's own
        // through the record), and handed the circle while it is live.
        h.repository.ownInvitesLooks.clear()
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("dan", invite, keyVersion = null)))
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")
        assertEquals(Origin.STATEMENT, h.approvals.origins("c-1")["dan"])
        assertTrue(delivered.containsKey("dan"))
    }

    @Test
    fun `an invitation still pending on the server keeps its record`() = runTest {
        val h = inviterDevice()
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        h.serverPending(invite)
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(mapOf("dan" to "inv-dan"), h.approvals.pendingInvitees("c-1"))
        assertFalse(h.approvals.invitationDead("c-1", "inv-dan"))
    }

    @Test
    fun `an invitation made while the pending list is on its way is not judged against it`() = runTest {
        val h = inviterDevice()
        val dans = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // erin is invited from this phone meanwhile: the list read before doesn't have it.
        coEvery { h.tracksApi.getCircleInvites("c-1") } answers {
            h.approvals.approveInvitee("c-1", "erin", "erin@x.test", "inv-erin", h.statements.now + 7 * 86_400)
            pendingList(dans)
        }
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(mapOf("dan" to "inv-dan", "erin" to "inv-erin"), h.approvals.pendingInvitees("c-1"))
        assertEquals(setOf<String>(), h.approvals.deadInvitations("c-1"))
    }

    @Test
    fun `demoted on a complete member list - every invitation this user still had pending goes dead, and its invitee listed again is refused`() = runTest {
        val h = inviterDevice(role = "admin")
        val dans = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        h.approvals.approveInvitee("c-1", null, "erin@x.test", "inv-erin", h.statements.now + 7 * 86_400)
        // No longer an admin: the server withdrew both, and this phone can't read the list.
        h.serverLists(listOf(h.rosterMember("me", role = "member"), h.rosterMember("owner", role = "owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees("c-1"))
        assertTrue(h.approvals.invitationDead("c-1", "inv-dan"))
        assertTrue(h.approvals.invitationDead("c-1", "inv-erin"))
        coVerify(exactly = 0) { h.tracksApi.getCircleInvites(any()) }
        // A server that lists dan with it anyway gets nothing here.
        h.serverLists(listOf(h.rosterMember("me", role = "member"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans, keyVersion = null)))
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")
        assertFalse(h.approvals.origins("c-1").containsKey("dan"))
        assertFalse(delivered.containsKey("dan"))
    }

    @Test
    fun `demoted - an invitation a listed member carries stays live, and they stay confirmed`() = runTest {
        val h = inviterDevice(role = "admin")
        // erin joined with this user's address invitation; her email isn't readable here yet,
        // so her record is still pending when the list shows this user demoted.
        val erins = h.invitation("erin", by = "me", kind = com.pingolin.track.domain.repository.MembershipStatements.Kind.INVITATION_TO_EMAIL, id = "inv-erin")
        h.approvals.approveInvitee("c-1", null, "erin@x.test", "inv-erin", h.statements.now + 7 * 86_400)
        h.serverLists(listOf(h.rosterMember("me", role = "member"), h.rosterMember("owner", role = "owner"), h.rosterMember("erin", erins, keyVersion = null)))

        h.repository.refreshMembers("c-1")

        assertFalse(h.approvals.invitationDead("c-1", "inv-erin"))
        assertTrue(h.approvals.origins("c-1").containsKey("erin"))
        assertTrue(h.storedRows.any { it.userId == "erin" })
    }

    @Test
    fun `a member list that isn't complete kills nothing - the next complete one settles it`() = runTest {
        val h = inviterDevice(role = "admin")
        val dans = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // Demoted, on a first page whose second one fails: nothing to judge.
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), null) } returns Response.success(ApiResponse(success = true,
            data = com.pingolin.track.data.remote.dto.PaginatedResponse(items = listOf(h.rosterMember("me", role = "member")), hasMore = true, cursor = "p2"),
            error = null))
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), "p2") } returns
            Response.error(503, "unavailable".toResponseBody("text/plain".toMediaType()))

        assertTrue(h.repository.refreshMembers("c-1").isFailure)

        assertEquals(mapOf("dan" to "inv-dan"), h.approvals.pendingInvitees("c-1"))
        assertFalse(h.approvals.invitationDead("c-1", "inv-dan"))
        // The next complete list shows dan with that invitation: this user's own choice.
        h.serverLists(listOf(h.rosterMember("me", role = "member"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans, keyVersion = null)))
        h.repository.refreshMembers("c-1")
        assertEquals(Origin.OWN, h.approvals.origins("c-1")["dan"])
        assertFalse(h.approvals.invitationDead("c-1", "inv-dan"))
    }

    @Test
    fun `the server calling a listed member's invitation withdrawn changes nothing`() = runTest {
        val h = inviterDevice()
        // "me" invited erin by email; she joined, carrying it. Her email isn't readable this
        // pass, so her record is still pending, and the server's pending list lacks it.
        val invite = h.invitation("erin", by = "me", kind = com.pingolin.track.domain.repository.MembershipStatements.Kind.INVITATION_TO_EMAIL, id = "inv-erin")
        h.approvals.approveInvitee("c-1", null, "erin@x.test", "inv-erin", h.statements.now + 7 * 86_400)
        h.serverPending()
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("erin", invite, keyVersion = null)))
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } throws java.io.IOException("offline")

        h.repository.refreshMembers("c-1")

        assertFalse(h.approvals.invitationDead("c-1", "inv-erin"))
        assertEquals(mapOf("erin@x.test" to "inv-erin"), h.approvals.pendingInvitees("c-1"))
        // Her email readable again: this user's own invitee, receive-only, and handed the circle.
        h.repository.ownInvitesLooks.clear()
        h.serverLists(
            listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("erin", invite, keyVersion = null)),
            keys = listOf(h.key("me"), h.key("owner"), h.key("erin", keyVersion = null, email = "erin@x.test")),
        )
        val delivered = h.deliveriesAccepted()
        h.repository.deliverOwedAccess("c-1")
        assertEquals(Origin.OWN_KIND2, h.approvals.origins("c-1")["erin"])
        assertTrue(delivered.containsKey("erin"))
    }

    @Test
    fun `another admin's invitations are never this device's to withdraw`() = runTest {
        val h = inviterDevice()
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        val dans = h.invitation("dan", by = "me", id = "inv-dan")
        // alice's invitation of zoe is gone from the server's list; this phone has no record of it.
        val zoes = h.invitation("zoe", by = "owner", id = "inv-zoe")
        h.serverPending(dans)
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(setOf<String>(), h.approvals.deadInvitations("c-1"))
        // zoe, listed while that invitation is live, is confirmed as any member's device would.
        h.repository.ownInvitesLooks.clear()
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("zoe", zoes, keyVersion = null)))
        h.repository.refreshMembers("c-1")
        assertEquals(Origin.STATEMENT, h.approvals.origins("c-1")["zoe"])
    }
}
