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

import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.MobileException

/**
 * Signed invitations and delivery by any member, end to end through the real
 * CircleRepository: who is shown, who is handed the circle and by whom, what a plain
 * member's app may refresh, and what one password reset changes where.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedInvitationAccessTest {

    @After
    fun tearDown() = clearAllMocks()

    /** A device of "me" in role [role], seeded with [seed] (empty: first saw the circle now). */
    private fun TestScope.harness(role: String = "member", seed: List<String> = listOf("me", "owner")): TrustHarness =
        TrustHarness(selfRole = role).also {
            it.repository.deliveryScope = this
            if (seed.isNotEmpty()) it.earlierSeed(*seed.toTypedArray())
        }

    // ---- a newcomer invited by an owner is let in by ANY member's app ----------------

    /**
     * An invitee is not tombstoned when the circle's key changes between the invitation and
     * the next member-list pass; otherwise their invitation would be refused as "older than
     * their removal". Here only [com.pingolin.track.util.KeyAccessApprovals.retain]
     * tombstones, and a pending invitee lives only in INVITED, never in what it counts.
     */
    @Test
    fun `a key change right after an invitation leaves the invitee untouched - they join and get the current key`() = runTest {
        val h = harness(role = "owner")
        // "me" invited dan at version 3 (inviteToCircle records the invitee with that invitation).
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        h.approvals.approveInvitee("c-1", "dan", "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        // The circle's key changes before any member-list pass: version 4 here and on the server.
        h.circleVersion = 4
        h.heldKeyVersion = 4

        // A member-list pass while dan is still pending: no tombstone.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner", keyVersion = 4)))
        h.repository.refreshMembers("c-1")
        assertEquals(emptyMap<String, Int>(), h.approvals.tombstones("c-1"))

        // dan joins with the invitation made at version 3: confirmed, and handed version 4.
        h.serverLists(listOf(
            h.rosterMember("me", role = "owner"), h.rosterMember("owner", keyVersion = 4),
            h.rosterMember("dan", invite, keyVersion = null),
        ))
        val delivered = h.deliveriesAccepted()
        assertEquals(1, h.repository.deliverOwedAccess("c-1"))

        assertEquals(4, delivered.getValue("dan").keyVersion)
        assertTrue(h.storedRows.any { it.userId == "dan" })
        assertEquals(emptyMap<String, Int>(), h.approvals.tombstones("c-1"))
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `a plain member's app hands a newcomer invited by the owner the circle, with no question`() = runTest {
        val h = harness()
        val invite = h.invitation("dan", by = "owner")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", invite, keyVersion = null)))
        val delivered = h.deliveriesAccepted()

        assertEquals(1, h.repository.deliverOwedAccess("c-1"))

        assertEquals(3, delivered.getValue("dan").keyVersion)
        assertEquals("sig-dan", delivered.getValue("dan").wrapSignature)
        verify { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing("dan"), "me", "dan", signing("dan")) }
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
        // Shown, "Joining…" until their copy is current, and pinned with the keys the owner saw.
        assertTrue(h.storedRows.any { it.userId == "dan" })
        assertEquals(setOf("dan"), h.repository.memberStates.value["c-1"]?.joining)
        assertEquals(sharing("dan"), h.pins.sharing["dan"])
        assertEquals(Origin.STATEMENT, h.origins()["dan"])
    }

    @Test
    fun `after the invitation lapses only the device that created the circle hands it over`() = runTest {
        // (role the server reports, whether this device's own record says it
        // created the circle) → delivers. A role alone never widens delivery.
        for ((role, created) in listOf("member" to false, "admin" to false, "owner" to false, "owner" to true)) {
            val h = harness(role)
            if (created) h.approvals.markCreatedHere("c-1")
            val invite = h.invitation("dan", by = "owner", expiresAt = h.statements.now - 301)
            val ownerRow = h.rosterMember("owner", role = "owner")
            h.serverLists(listOf(h.rosterMember("me"), ownerRow, h.rosterMember("dan", invite, keyVersion = null)))
            val delivered = h.deliveriesAccepted()

            h.repository.deliverOwedAccess("c-1")

            assertEquals("role=$role created=$created", created, "dan" in delivered)
            // Still shown on all: expiry decides only the first
            // delivery.
            assertTrue("role=$role", h.storedRows.any { it.userId == "dan" })
        }
    }

    @Test
    fun `the phone that creates a circle records that it did`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        val start = src.indexOf("suspend fun createCircle(")
        val body = src.substring(start, src.indexOf("\n    suspend fun ", start + 1))
        // The key is made under the id the server reserved, so the circle has its real id
        // from the start; it is recorded once the server made the circle under that id.
        val checked = body.indexOf("created.id.equals(recorded.circleId, ignoreCase = true)")
        val recorded = body.indexOf("keyAccessApprovals.markCreatedHere(dto.id)")
        assertTrue("recorded once the server made the circle under the reserved id", checked in 0 until recorded)
    }

    @Test
    fun `a role the server claims for this user grants no delivery`() = runTest {
        // A plain member's phone the server now calls "admin": alice (confirmed
        // here in the seed) is owed the current copy. Nothing about her lets this
        // phone serve her, and the server's word does not either.
        val h = harness("admin", listOf("me", "owner", "alice"))
        h.serverLists(listOf(h.rosterMember("me", role = "admin"), h.rosterMember("owner", role = "owner"), h.rosterMember("alice", keyVersion = 2)))
        val delivered = h.deliveriesAccepted()

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        assertTrue(delivered.isEmpty())

        // The phone that created the circle, still listed as its owner, serves her.
        val creator = harness("owner", listOf("me", "owner", "alice"))
        creator.approvals.markCreatedHere("c-1")
        creator.serverLists(listOf(creator.rosterMember("me", role = "owner"), creator.rosterMember("alice", keyVersion = 2)))
        val served = creator.deliveriesAccepted()
        assertEquals(1, creator.repository.deliverOwedAccess("c-1"))
        assertEquals(creator.circleVersion, served.getValue("alice").keyVersion)
    }

    @Test
    fun `a member left pending by a refresh is served by owner and admin devices, or on an admission`() = runTest {
        val plain = harness("member", listOf("me", "owner", "alice"))
        plain.serverLists(listOf(plain.rosterMember("me"), plain.rosterMember("owner", role = "owner"), plain.rosterMember("alice", keyVersion = 2)))
        val none = plain.deliveriesAccepted()
        plain.repository.deliverOwedAccess("c-1")
        assertTrue(none.isEmpty())

        // An admission made at this device's version lets any member serve her.
        val admission = plain.statements.statement(Kind.ADMISSION, "c-1", "owner", signing("owner"), "alice", sharing("alice"), signing("alice"), atVersion = 3)
        plain.serverLists(listOf(plain.rosterMember("me"), plain.rosterMember("owner", role = "owner"), plain.rosterMember("alice", vouches = listOf(admission), keyVersion = 2)))
        val served = plain.deliveriesAccepted()
        plain.repository.deliverOwedAccess("c-1")
        assertEquals(3, served.getValue("alice").keyVersion)
    }

    // ---- nobody without a valid invitation is shown or given anything ----------------

    @Test
    fun `a forged invitation hides the member everywhere and gets them nothing`() = runTest {
        for (role in listOf("member", "owner")) {
            val h = harness(role)
            val forged = h.invitation("ghost", by = "owner", signerKey = signing("server"))
            h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = if (role == "owner") "admin" else "owner"), h.rosterMember("ghost", forged, keyVersion = null)))
            h.deliveriesAccepted()

            h.repository.deliverOwedAccess("c-1")

            coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }
            verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), "ghost", any(), any()) }
            assertTrue(h.storedRows.none { it.userId == "ghost" })
            assertTrue(h.repository.keyAccessRequests.value.isEmpty())
            // Owners and admins are told how many were kept
            // out.
            assertEquals("role=$role", if (role == "owner") 1 else 0, h.repository.memberStates.value["c-1"]?.keptOut)
        }
    }

    @Test
    fun `a member from before signed invitations is hidden everywhere, and never asked about`() = runTest {
        val plain = harness("member")
        plain.serverLists(listOf(plain.rosterMember("me"), plain.rosterMember("owner", role = "owner"), plain.rosterMember("old")))
        plain.repository.refreshMembers("c-1")
        assertTrue(plain.storedRows.none { it.userId == "old" })
        assertTrue(plain.repository.keyAccessRequests.value.isEmpty())
        // Their locations are left off the map too.
        assertEquals(setOf("old"), plain.repository.membersHiddenIn("c-1"))
        assertEquals(0, plain.repository.memberStates.value["c-1"]?.keptOut)

        val owner = harness("owner")
        owner.serverLists(listOf(owner.rosterMember("me", role = "owner"), owner.rosterMember("old")))
        owner.repository.refreshMembers("c-1")
        assertTrue(owner.storedRows.none { it.userId == "old" })
        assertTrue(owner.repository.keyAccessRequests.value.isEmpty())
        assertTrue(owner.questions.isEmpty())
        // Counted in the owner's "kept out" line instead.
        assertEquals(1, owner.repository.memberStates.value["c-1"]?.keptOut)
    }

    @Test
    fun `a member from before signed invitations whom this owner let in earlier stays in`() = runTest {
        val owner = harness("owner")
        // An earlier "Let them in": a permanent approval, and their keys pinned.
        owner.approvals.approve("c-1", listOf("old"), Origin.OWN)
        owner.pins.pin("old", sharing("old"), signing("old"))
        owner.serverLists(listOf(owner.rosterMember("me", role = "owner"), owner.rosterMember("old")))

        owner.repository.refreshMembers("c-1")

        assertTrue(owner.storedRows.any { it.userId == "old" })
        assertTrue(owner.repository.keyAccessRequests.value.isEmpty())
        assertEquals(0, owner.repository.memberStates.value["c-1"]?.keptOut)
    }

    @Test
    fun `an invitation from someone this device cannot confirm is asked about with their number`() = runTest {
        val h = harness("owner", listOf("me"))
        // mallory is listed, not confirmed here; her invitation of zoe cannot be checked.
        val invite = h.invitation("zoe", by = "mallory")
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("mallory"), h.rosterMember("zoe", invite)))

        h.repository.refreshMembers("c-1")

        val zoe = h.repository.keyAccessRequests.value.single { it.userId == "zoe" }
        assertEquals(KeyAccessRequest.Reason.UNCONFIRMED_INVITER, zoe.reason)
        assertEquals("number-of-zoe", zoe.number)
    }

    // ---- the security refresh: any member, when nobody is left out ------------------

    @Test
    fun `a plain member finishes a flagged refresh only when it confirms everyone`() = runTest {
        val h = harness("member", listOf("me", "owner", "alice"))
        h.repository.noteRotationCause("c-1", "member_removed")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("alice")))
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "owner", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(3, sent.captured.expectedKeyVersion)

        // Somebody this device cannot confirm: the refresh is left to an owner or admin.
        val other = harness("member", listOf("me", "owner", "alice"))
        other.repository.noteRotationCause("c-1", "member_removed")
        other.serverLists(listOf(other.rosterMember("me"), other.rosterMember("owner", role = "owner"), other.rosterMember("alice"), other.rosterMember("stranger")))
        other.rotationAccepted()
        other.repository.autoRotateIfNeeded("c-1")
        coVerify(exactly = 0) { other.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `an app whose own invitation was to its email delivers and refreshes nothing`() = runTest {
        val h = harness("member", listOf("me", "owner"))
        val mine = h.invitation("me", by = "owner", kind = Kind.INVITATION_TO_EMAIL)
        val dans = h.invitation("dan", by = "owner")
        h.repository.noteRotationCause("c-1", "member_removed")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans, keyVersion = null)))
        h.deliveriesAccepted()
        h.rotationAccepted()

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        h.repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `an app whose own invitation names the keys it had before a reset is receive-only until an admission`() = runTest {
        val h = harness("member", listOf("me", "owner"))
        val old = h.invitation("me", by = "owner", keysOf = "me-before-reset")
        val dans = h.invitation("dan", by = "owner")
        h.serverLists(listOf(h.rosterMember("me", old), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans, keyVersion = null)))
        h.deliveriesAccepted()
        assertEquals(0, h.repository.deliverOwedAccess("c-1"))

        val admitted = h.statements.statement(Kind.ADMISSION, "c-1", "owner", signing("owner"), "me", sharing("me"), signing("me"))
        h.serverLists(listOf(h.rosterMember("me", old, listOf(admitted)), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans, keyVersion = null)))
        assertEquals(1, h.repository.deliverOwedAccess("c-1"))
    }

    @Test
    fun `a delivery pass that found nothing to hand over is not repeated on every refresh`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("old", keyVersion = null)))
        h.deliveriesAccepted()

        assertEquals(0, h.repository.deliverIfDue("c-1"))
        assertEquals(0, h.repository.deliverIfDue("c-1"))

        coVerify(exactly = 1) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
    }

    // ---- what the server answers --------------------------------------------------------

    @Test
    fun `someone else delivering first is quiet, and a circle that moved on stops the pass`() = runTest {
        val h = harness()
        h.serverLists(listOf(
            h.rosterMember("me"), h.rosterMember("owner", role = "owner"),
            h.rosterMember("dan", h.invitation("dan", by = "owner"), keyVersion = null),
            h.rosterMember("eve", h.invitation("eve", by = "owner"), keyVersion = null),
        ))
        coEvery { h.tracksApi.deliverAccess("c-1", any(), any()) } returns
            Response.error(409, """{"success":false,"error_code":"key_version_mismatch"}""".toResponseBody(null))

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        coVerify(exactly = 1) { h.tracksApi.deliverAccess(any(), any(), any()) }

        val again = harness()
        again.serverLists(listOf(again.rosterMember("me"), again.rosterMember("owner", role = "owner"), again.rosterMember("dan", again.invitation("dan", by = "owner"), keyVersion = null)))
        coEvery { again.tracksApi.deliverAccess("c-1", any(), any()) } returns
            Response.error(409, """{"success":false,"error_code":"key_already_delivered"}""".toResponseBody(null))
        assertEquals(0, again.repository.deliverOwedAccess("c-1"))
    }

    @Test
    fun `a device behind on the circle hands nothing over`() = runTest {
        val h = harness()
        h.heldKeyVersion = 2
        h.serverLists(listOf(h.rosterMember("me", keyVersion = 2), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", h.invitation("dan", by = "owner"), keyVersion = null)))
        h.deliveriesAccepted()

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }
    }

    // ---- questions only a person can answer reach them -----------------------------------

    @Test
    fun `a member's reset is taken to this user as a notification, and goes once answered`() = runTest {
        val h = harness("member", listOf("me", "owner", "bob"))
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("bob", keysOf = "bob-new")))

        // Raised by a background pass as much as by the Circles screen: nobody has to be
        // looking at the Circles screen for the question to reach them.
        h.repository.refreshMembers("c-1")
        assertEquals(listOf("reset c-1 bob Bob"), h.questions)

        h.repository.deferMemberKeyChange(h.repository.memberKeyAlerts.value.single { it.userId == "bob" })
        assertEquals(listOf("reset c-1 bob Bob", "answered c-1 bob"), h.questions)
    }

    @Test
    fun `a newcomer waiting on an owner is taken to the owner as a notification, once, and goes once answered`() = runTest {
        // zoe was invited by mallory, whom this phone can't confirm. mallory
        // herself, with nothing at all, is never asked about.
        val owner = harness("owner")
        val invite = owner.invitation("zoe", by = "mallory")
        owner.serverLists(listOf(owner.rosterMember("me", role = "owner"), owner.rosterMember("mallory"), owner.rosterMember("zoe", invite)))
        owner.statementsAccepted()
        owner.deliveriesAccepted()

        owner.repository.refreshMembers("c-1")
        owner.repository.refreshMembers("c-1")
        // Once, however often the member list is judged (the notifier's own once a day aside).
        assertEquals(listOf("join c-1 Family zoe Zoe"), owner.questions)

        owner.repository.grantKeyAccess(owner.repository.keyAccessRequests.value.single(), com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()
        assertEquals("answered c-1 zoe", owner.questions.last())
        // A plain member's phone is never asked, so it is never notified.
        val plain = harness("member")
        plain.serverLists(listOf(
            plain.rosterMember("me"), plain.rosterMember("owner", role = "owner"),
            plain.rosterMember("mallory"), plain.rosterMember("zoe", plain.invitation("zoe", by = "mallory")),
        ))
        plain.repository.refreshMembers("c-1")
        assertTrue(plain.questions.isEmpty())
    }

    // ---- a password reset: each device decides for itself ----------------------

    @Test
    fun `It's them counts on this device only, and hands them the circle`() = runTest {
        val h = harness("member", listOf("me", "owner", "bob"))
        // bob reset his password: new keys, and his app asked for a fresh copy.
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("bob", keysOf = "bob-new", accessRequested = true)))
        val delivered = h.deliveriesAccepted()
        h.repository.refreshMembers("c-1")
        val alert = h.repository.memberKeyAlerts.value.single { it.userId == "bob" }
        assertEquals("number-of-bob", alert.number)

        h.repository.approveMemberKeyChange(alert)
        advanceUntilIdle()

        verify { h.cryptoManager.approveMemberKeyChange("c-1", "bob", sharing("bob-new"), signing("bob-new")) }
        assertEquals(Origin.OWN, h.origins()["bob"])
        assertEquals(3, delivered.getValue("bob").keyVersion)
        verify { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing("bob-new"), "me", "bob", signing("bob-new")) }
        // Nothing signed, nothing sent for other devices: they ask their own users.
        assertTrue(h.statements.signed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), any(), any()) }
    }

    @Test
    fun `Not now is asked again tomorrow at the earliest`() = runTest {
        val h = harness("member", listOf("me", "owner", "bob"))
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("bob", keysOf = "bob-new")))
        h.repository.refreshMembers("c-1")
        h.repository.deferMemberKeyChange(h.repository.memberKeyAlerts.value.single())

        h.repository.refreshMembers("c-1")

        assertTrue(h.repository.memberKeyAlerts.value.isEmpty())
    }

    @Test
    fun `a statement about keys other than this device's pin is the question, never a new pin`() = runTest {
        val h = harness("member", listOf("me", "owner"))
        h.pins.pin("dan", sharing("dan-old"), signing("dan-old")) // known from another circle
        val invite = h.invitation("dan", by = "owner", keysOf = "dan-new")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", invite, keysOf = "dan-new", keyVersion = null)))
        h.deliveriesAccepted()

        h.repository.deliverOwedAccess("c-1")

        assertTrue(h.storedRows.any { it.userId == "dan" })
        assertEquals(sharing("dan-new"), h.repository.memberKeyAlerts.value.single().newSharingHex)
        verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), "dan", any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }
        assertEquals(sharing("dan-old"), h.pins.sharing["dan"])
    }

    // ---- the newcomer's side ----------------------------------------------

    private fun receivedInvite(h: TrustHarness, statement: MembershipStatements.Signed?) {
        coEvery { h.tracksApi.getReceivedInvites() } returns Response.success(ApiResponse(
            success = true,
            data = listOf(ReceivedInviteResponse(
                id = "inv-1", circleId = "c-1", invitedBy = "owner", inviteType = "email",
                createdDayBucket = 0, expiresDayBucket = 7, hasEncryptedKey = false,
                inviterDisplayName = "Owner",
                statement = statement?.statementHex, statementSignature = statement?.signatureHex,
            )),
            error = null,
        ))
    }

    @Test
    fun `an invitation made out to other keys is not accepted - the circle is asked to renew it`() = runTest {
        val h = harness("member", listOf("me"))
        receivedInvite(h, h.invitation("me", by = "owner", keysOf = "someone-else"))
        coEvery { h.tracksApi.acceptInvite(any(), any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        coEvery { h.tracksApi.reportInviteMismatch("inv-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.InviteMismatchResponse(true), error = null))

        val result = h.repository.acceptInvite("inv-1", "c-1", "v2")

        // Never "ask them"; the person's yes is kept for when it is renewed.
        assertTrue(result.exceptionOrNull() is InvitationBeingRenewedException)
        coVerify(exactly = 0) { h.tracksApi.acceptInvite(any(), any()) }
        coVerify(exactly = 1) { h.tracksApi.reportInviteMismatch("inv-1") }
        assertEquals(listOf("inv-1" to "v2"), h.pendingAccepts.all().map { it.inviteId to it.noticeVersion })

        // Not renewed yet: nothing to try, and no second report for the same statement.
        assertEquals(0, h.repository.retryPendingAccepts())
        h.repository.acceptInvite("inv-1", "c-1", "v2")
        coVerify(exactly = 1) { h.tracksApi.reportInviteMismatch("inv-1") }
    }

    @Test
    fun `an invitation the server calls out of date waits for its renewal, with the agreement already given`() = runTest {
        val h = harness("member", listOf("me"))
        receivedInvite(h, h.invitation("me", by = "owner"))
        val sent = io.mockk.slot<com.pingolin.track.data.remote.dto.AcceptInviteRequest>()
        coEvery { h.tracksApi.acceptInvite(any(), capture(sent)) } returns
            Response.error(409, """{"success":false,"error_code":"invite_out_of_date"}""".toResponseBody(null))

        val result = h.repository.acceptInvite("inv-1", "c-1", "v2")

        assertTrue(result.exceptionOrNull() is InvitationBeingRenewedException)
        coVerify(exactly = 1) { h.tracksApi.acceptInvite("inv-1", any()) }
        // The server asked for the renewal itself: no report from here.
        coVerify(exactly = 0) { h.tracksApi.reportInviteMismatch(any()) }
        // The agreement to the notice that was on screen goes in the
        // accept.
        assertEquals("v2", sent.captured.locationConsent.clientNoticeVersion)
        assertEquals("android", sent.captured.locationConsent.surface)
        assertEquals(listOf("inv-1"), h.pendingAccepts.all().map { it.inviteId })
    }

    @Test
    fun `once renewed for this account the invitation is joined by itself, once`() = runTest {
        val h = harness("member", listOf("me"))
        receivedInvite(h, h.invitation("me", by = "owner", keysOf = "someone-else"))
        coEvery { h.tracksApi.reportInviteMismatch("inv-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.InviteMismatchResponse(true), error = null))
        h.repository.acceptInvite("inv-1", "c-1", "v2")

        // Renewed: the same invitation, now naming this account's keys.
        receivedInvite(h, h.invitation("me", by = "owner"))
        val sent = io.mockk.slot<com.pingolin.track.data.remote.dto.AcceptInviteRequest>()
        coEvery { h.tracksApi.acceptInvite("inv-1", capture(sent)) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))

        h.repository.retryPendingAccepts()

        coVerify(exactly = 1) { h.tracksApi.acceptInvite("inv-1", any()) }
        assertEquals("the consent they gave, sent with the accept", "v2", sent.captured.locationConsent.clientNoticeVersion)
        assertTrue(h.pendingAccepts.all().isEmpty())
        h.repository.retryPendingAccepts()
        coVerify(exactly = 1) { h.tracksApi.acceptInvite("inv-1", any()) }
    }

    /** An address invitation is never renewed, so there is nothing to report. */
    @Test
    fun `an invitation to another address says so, and asks the circle for nothing`() = runTest {
        val h = harness("member", listOf("me"))
        receivedInvite(h, h.invitation("me", by = "owner", kind = Kind.INVITATION_TO_EMAIL, email = "someone-else@x.test"))

        val result = h.repository.acceptInvite("inv-1", "c-1", "v2")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is InvitationBeingRenewedException)
        coVerify(exactly = 0) { h.tracksApi.reportInviteMismatch(any()) }
        coVerify(exactly = 0) { h.tracksApi.acceptInvite(any(), any()) }
        assertTrue("nothing to join by itself later", h.pendingAccepts.all().isEmpty())
    }

    /** Its sender may not invite people to this circle now. */
    @Test
    fun `an invitation nobody can renew says so, keeps nothing, and is never counted as joined`() = runTest {
        val notRenewable = """{"success":false,"error_code":"invite_not_renewable"}"""

        // The mismatch report is refused: nobody is asked, nothing is kept.
        val h = harness("member", listOf("me"))
        every { h.context.getString(com.pingolin.track.R.string.invite_not_renewable) } returns "can't be used any more"
        receivedInvite(h, h.invitation("me", by = "owner", keysOf = "someone-else"))
        coEvery { h.tracksApi.reportInviteMismatch("inv-1") } returns Response.error(409, notRenewable.toResponseBody(null))

        val reported = h.repository.acceptInvite("inv-1", "c-1", "v2")

        assertEquals("can't be used any more", reported.exceptionOrNull()?.message)
        assertFalse(reported.exceptionOrNull() is InvitationBeingRenewedException)
        assertTrue(h.pendingAccepts.all().isEmpty())

        // The accept itself says so (made out to old keys, by someone gone): the same.
        val h2 = harness("member", listOf("me"))
        every { h2.context.getString(com.pingolin.track.R.string.invite_not_renewable) } returns "can't be used any more"
        receivedInvite(h2, h2.invitation("me", by = "owner"))
        coEvery { h2.tracksApi.acceptInvite(any(), any()) } returns Response.error(409, notRenewable.toResponseBody(null))

        val accepted = h2.repository.acceptInvite("inv-1", "c-1", "v2")

        assertEquals("can't be used any more", accepted.exceptionOrNull()?.message)
        assertTrue(h2.pendingAccepts.all().isEmpty())

        // One that was waiting, renewed and then refused that way, is not "joined".
        val h3 = harness("member", listOf("me"))
        every { h3.context.getString(com.pingolin.track.R.string.invite_not_renewable) } returns "can't be used any more"
        receivedInvite(h3, h3.invitation("me", by = "owner", keysOf = "someone-else"))
        coEvery { h3.tracksApi.reportInviteMismatch("inv-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.InviteMismatchResponse(true), error = null))
        h3.repository.acceptInvite("inv-1", "c-1", "v2")
        receivedInvite(h3, h3.invitation("me", by = "owner"))
        coEvery { h3.tracksApi.acceptInvite(any(), any()) } returns Response.error(409, notRenewable.toResponseBody(null))

        assertEquals(0, h3.repository.retryPendingAccepts())
        assertTrue(h3.pendingAccepts.all().isEmpty())
    }

    @Test
    fun `an invitation that went away is forgotten, not accepted`() = runTest {
        val h = harness("member", listOf("me"))
        receivedInvite(h, h.invitation("me", by = "owner", keysOf = "someone-else"))
        coEvery { h.tracksApi.reportInviteMismatch("inv-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.InviteMismatchResponse(true), error = null))
        h.repository.acceptInvite("inv-1", "c-1", "v2")

        coEvery { h.tracksApi.getReceivedInvites() } returns Response.success(ApiResponse(success = true, data = emptyList(), error = null))

        assertEquals(0, h.repository.retryPendingAccepts())
        assertTrue(h.pendingAccepts.all().isEmpty())
        coVerify(exactly = 0) { h.tracksApi.acceptInvite(any(), any()) }
    }

    private fun copyFrom(sender: String, version: Int = 3) = CircleKeyBundleResponse(
        circleId = "c-1",
        encryptedCircleKey = EncryptedPayload("nn", "cc"),
        ephemeralPublicKey = "eph",
        keyVersion = version,
        senderUserId = sender,
        wrapSignature = "sig",
    )

    @Test
    fun `a copy this device refuses is followed by one request for a fresh one`() = runTest {
        val h = harness("member", listOf("me", "owner"))
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("ghost")), error = null))
        every { h.cryptoManager.storeCircleKey("c-1", any(), any(), any(), any(), any(), any(), any()) } throws MobileException.SignatureInvalid("unpinned signer")
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

        h.repository.importCircleKeys()
        h.repository.importCircleKeys()

        coVerify(exactly = 1) { h.tracksApi.requestAccess("c-1") }
    }

    @Test
    fun `with nothing else to start from, whoever handed over the first copy is where trust starts`() = runTest {
        val h = harness("member", seed = emptyList()) // no seed, no pins
        h.heldKeyVersion = 0
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))
        every { h.cryptoManager.storeCircleKey("c-1", any(), any(), any(), any(), any(), any(), any()) } answers { h.heldKeyVersion = 3 }
        val dans = h.invitation("dan", by = "owner")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans)))

        // The member list is judged before the first copy is taken (as a refresh does).
        h.repository.refreshMembers("c-1")
        h.repository.importCircleKeys()
        h.repository.refreshMembers("c-1")

        assertEquals(Origin.ANCHOR, h.origins()["owner"])
        assertEquals(setOf("me", "owner", "dan"), h.storedRows.map { it.userId }.toSet())
        assertTrue(h.firstCopies.isEmpty())
    }

    @Test
    fun `a first copy is taken from a confirmed member under their pinned key, with the name check at that version`() = runTest {
        val h = harness("member", seed = emptyList())
        h.heldKeyVersion = 0
        val mine = h.invitation("me", by = "owner")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner")))
        h.repository.refreshMembers("c-1") // the owner signed my invitation: confirmed and pinned
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))

        h.repository.importCircleKeys()

        val (sender, key, name) = h.firstCopies.single()
        assertEquals("owner", sender)
        assertEquals(signing("owner"), key)
        // No circle list seen, so no name known to check against.
        assertEquals(null, name)
        verify(exactly = 0) { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a first copy from someone this device does not confirm is refused and a fresh one asked for`() = runTest {
        val h = harness("member", seed = emptyList())
        h.heldKeyVersion = 0
        val mine = h.invitation("me", by = "owner")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner"), h.rosterMember("ghost")))
        h.repository.refreshMembers("c-1")
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("ghost")), error = null))
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

        h.repository.importCircleKeys()

        assertTrue(h.firstCopies.isEmpty())
        verify(exactly = 0) { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { h.tracksApi.requestAccess("c-1") }
    }

    @Test
    fun `a first copy that arrives before its member list is judged waits, and asks for nothing`() = runTest {
        val h = harness("member", seed = emptyList())
        h.heldKeyVersion = 0
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))
        coEvery { h.tracksApi.requestAccess(any()) } returns Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

        h.repository.importCircleKeys()

        verify(exactly = 0) { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(h.firstCopies.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.requestAccess(any()) }
    }

    // ---- the e-mail trust check: this account's invitation names an address this device doesn't hold as its own ----

    @Test
    fun `an e-mail invitee whose invitation names another address, or with none here, waits for its first copy and trusts no one`() = runTest {
        // As on iOS: the address changed elsewhere, or the server sent none at sign-in. The invitation
        // can't anchor its signer, and nothing may be taken on trust in its place.
        for ((invited, here) in listOf("old@x.test" to "me@x.test", "me@x.test" to null)) {
            val h = harness("member", seed = emptyList())
            h.heldKeyVersion = 0
            every { h.sessionManager.getEmail() } returns here
            val mine = h.invitation("me", by = "owner", kind = Kind.INVITATION_TO_EMAIL, email = invited)
            h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner")))
            coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))
            coEvery { h.tracksApi.requestAccess(any()) } returns Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))

            h.repository.refreshMembers("c-1")
            h.repository.importCircleKeys()
            h.repository.refreshMembers("c-1")

            val case = "invited $invited, here $here"
            verify(exactly = 0) { h.cryptoManager.storeCircleKey(any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(case, h.firstCopies.isEmpty())
            assertEquals(case, null, h.origins()["owner"])
            coVerify(exactly = 0) { h.tracksApi.requestAccess(any()) }
        }
    }

    @Test
    fun `an e-mail invitee whose invitation names this account's address takes its first copy from the inviter`() = runTest {
        val h = harness("member", seed = emptyList())
        h.heldKeyVersion = 0
        every { h.sessionManager.getEmail() } returns " ME@x.test "
        val mine = h.invitation("me", by = "owner", kind = Kind.INVITATION_TO_EMAIL, email = "me@x.test")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner")))
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))

        h.repository.refreshMembers("c-1")
        h.repository.importCircleKeys()

        assertEquals("owner", h.firstCopies.single().first)
        assertEquals(Origin.ANCHOR, h.origins()["owner"])
    }

    @Test
    fun `a first sender taken on trust is not made an anchor once this account's invitation names another address`() = runTest {
        val h = harness("member", seed = emptyList())
        h.heldKeyVersion = 0
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = listOf(copyFrom("owner")), error = null))
        every { h.cryptoManager.storeCircleKey("c-1", any(), any(), any(), any(), any(), any(), any()) } answers { h.heldKeyVersion = 3 }
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner")))
        h.repository.refreshMembers("c-1") // nobody confirmed, nothing to start from
        h.repository.importCircleKeys() // the first copy, on trust: its sender noted
        val mine = h.invitation("me", by = "owner", kind = Kind.INVITATION_TO_EMAIL, email = "old@x.test")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner")))

        h.repository.refreshMembers("c-1")

        assertEquals(null, h.origins()["owner"])
    }

    @Test
    fun `whoever signed this user's own invitation is trusted on a phone with nothing else`() = runTest {
        val h = harness("member", seed = emptyList()) // no seed, no pins
        val mine = h.invitation("me", by = "owner")
        val dans = h.invitation("dan", by = "owner")
        h.serverLists(listOf(h.rosterMember("me", mine), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", dans), h.rosterMember("ghost")))

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.ANCHOR, h.origins()["owner"])
        assertEquals(setOf("me", "owner", "dan"), h.storedRows.map { it.userId }.toSet())
        assertEquals(sharing("owner"), h.pins.sharing["owner"])
    }

    // ---- the transition: the one-time seed from held pins -----------------------

    /** exportMemberPins' JSON for circle pins "<circle>:<user>" (identity pins start with "*"). */
    private fun pinsExport(vararg keys: String) =
        """{"format":"pingolin.member_pins.v1","owner_user_id":"me","pins":{""" +
            keys.joinToString(",") { """"$it":{"sharing_pubkey_hex":"x","signing_pubkey_hex":"s"}""" } +
            """},"identity_pins":{}}"""

    @Test
    fun `the pins this device already held seed a circle once, and a pin made after that seed never does`() = runTest {
        val h = harness("member", seed = emptyList())
        every { h.cryptoManager.exportMemberPins("me") } returns pinsExport("c-1:alice", "c-1:bob", "*:zed")
        h.pins.pin("alice", sharing("alice"), signing("alice"))
        h.pins.pin("bob", sharing("bob"), signing("bob"))
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", role = "owner"), h.rosterMember("bob"), h.rosterMember("carol")))

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.PRE_ROUND2, h.origins()["alice"])
        assertEquals(Origin.PRE_ROUND2, h.origins()["bob"])
        assertEquals(setOf("me", "alice", "bob"), h.storedRows.map { it.userId }.toSet())
        assertTrue(h.approvals.heldPinsSeeded())
        assertFalse(h.approvals.trustedInOtherCircles("c-9").contains("zed"))

        // Pins made after the update — in this circle or another — never seed, in this
        // process or after a restart.
        every { h.cryptoManager.exportMemberPins("me") } returns pinsExport("c-1:alice", "c-1:bob", "c-1:carol", "c-2:dave")
        h.repository.refreshMembers("c-1")
        h.repository.wipeInMemory()
        h.repository.refreshMembers("c-1")

        assertTrue("carol" !in h.origins())
        assertTrue(h.approvals.origins("c-2").isEmpty())
        assertFalse(h.approvals.hasPreRound2Seed("c-2"))
    }

    @Test
    fun `a circle that already has records is not seeded from pins`() = runTest {
        val h = harness("member", listOf("me", "alice")) // seeded already
        every { h.cryptoManager.exportMemberPins("me") } returns pinsExport("c-1:alice", "c-1:ghost")
        h.pins.pin("ghost", sharing("ghost"), signing("ghost"))
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("ghost")))

        h.repository.refreshMembers("c-1")

        assertTrue("ghost" !in h.origins())
        assertTrue(h.storedRows.none { it.userId == "ghost" })
    }

    // ---- the backfill ---------------------------------------------------------

    @Test
    fun `an owner's device vouches once for a seed member nothing else vouches for`() = runTest {
        val h = harness("owner", listOf("me", "alice"))
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice")))
        val stored = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        val admission = h.statements.parse(stored.getValue("alice").statement)!!
        assertEquals(Kind.ADMISSION, admission.kind)
        assertEquals(sharing("alice"), admission.subjectSharingKeyHex)
        coVerify(exactly = 1) { h.tracksApi.storeMembershipStatement("c-1", "alice", any()) }
    }

    @Test
    fun `the backfill signs only seed members`() = runTest {
        // An owner's phone with no seed: seeded once from its pins (alice); dan
        // came in on a signed invitation; zoe has nothing and was never pinned
        // here.
        val h = harness("owner", seed = emptyList())
        every { h.cryptoManager.exportMemberPins("me") } returns pinsExport("c-1:alice")
        h.pins.pin("alice", sharing("alice"), signing("alice"))
        val dans = h.invitation("dan", by = "me", signerKey = signing("me"))
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice"), h.rosterMember("dan", dans), h.rosterMember("zoe")))
        val stored = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertEquals(setOf("alice"), stored.keys)
        // zoe (an older app's invitation) is hidden and counted instead, never asked
        // about.
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
        assertEquals(1, h.repository.memberStates.value["c-1"]?.keptOut)
    }

    @Test
    fun `a member whose invitation's signer left is vouched for only when a confirmed member handed them their copy`() = runTest {
        // An owner's phone. bob was confirmed here through carol's invitation; carol has left,
        // so nothing that other members' apps can check vouches for bob any more.
        val h = harness("owner", listOf("me", "alice"))
        h.approvals.approve("c-1", listOf("bob"), Origin.STATEMENT)
        h.pins.pin("bob", sharing("bob"), signing("bob"))
        val carols = h.invitation("bob", by = "carol")
        fun bobWithCopyFrom(signer: String) = h.rosterMember("bob", carols).copy(
            wrapSignerUserId = signer,
            wrapSignature = "sig-by-$signer",
            encryptedCircleKey = com.pingolin.track.data.remote.dto.EncryptedPayload("n-bob", "c-bob"),
            ephemeralPublicKey = "e-bob",
        )
        fun listWith(bob: com.pingolin.track.data.remote.dto.MemberResponse) =
            h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice"), bob))
        val stored = h.statementsAccepted()

        // A copy bob signed for himself proves nothing, whatever verifies.
        every { h.cryptoManager.verifyWrap(any(), any(), any()) } returns true
        listWith(bobWithCopyFrom("bob"))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertFalse("bob" in stored)

        // alice (confirmed here) is named as the one who handed it over, but it does not verify.
        every { h.cryptoManager.verifyWrap(any(), any(), any()) } returns false
        listWith(bobWithCopyFrom("alice"))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertFalse("bob" in stored)

        // It verifies under alice's key, for bob's copy of this circle: bob is vouched for.
        every {
            h.cryptoManager.verifyWrap(signing("alice"), "sig-by-alice", match {
                it.senderUserId == "alice" && it.recipientUserId == "bob" && it.circleId == "c-1" &&
                    it.wrapCiphertextHex == "c-bob" && it.keyVersion == 3u
            })
        } returns true
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        val admission = h.statements.parse(stored.getValue("bob").statement)!!
        assertEquals(Kind.ADMISSION, admission.kind)
        assertEquals(sharing("bob"), admission.subjectSharingKeyHex)
        assertEquals(signing("bob"), admission.subjectSigningKeyHex)
    }

    @Test
    fun `a device with neither a seed from before signed invitations nor held pins never backfills`() = runTest {
        val fresh = harness("owner", seed = emptyList())
        every { fresh.cryptoManager.exportMemberPins("me") } returns pinsExport()
        // It trusts alice from another circle here, which is no seed.
        fresh.approvals.approve("c-2", listOf("alice"), Origin.STATEMENT)
        fresh.pins.pin("alice", sharing("alice"), signing("alice"))
        fresh.serverLists(listOf(fresh.rosterMember("me", role = "owner"), fresh.rosterMember("alice")))
        fresh.statementsAccepted()

        fresh.repository.refreshMembers("c-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { fresh.tracksApi.storeMembershipStatement(any(), any(), any()) }
        assertFalse(fresh.approvals.hasPreRound2Seed("c-1"))
        assertEquals(Origin.ANCHOR, fresh.origins()["alice"])
    }
}
