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
import com.pingolin.track.data.remote.dto.RenewInvitationRequest
import com.pingolin.track.data.remote.dto.RenewInvitationResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * On the inviter's phone, nothing is renewed silently. An ACCOUNT invitation this account made,
 * while it is an owner or admin (this device created the circle, else the circle list's role),
 * whose invitee's app found it no longer names them, raises "Is this really {email}?" once —
 * only when the original holds here and the directory, read here, shows the same account with
 * changed keys. "It's them" renews it for exactly the keys shown (the original's id, times and
 * subject kept), asking again at most once if they change by the answer; "No" withdraws it for
 * good. An address invitation is never renewed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvitationRenewalTest {

    /** The invitee, and another account: the directory's ids are UUIDs (AccountIds.strict). */
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val mallory = "3a110000-0000-4000-8000-000000000011"

    @After
    fun tearDown() = clearAllMocks()

    /** Changes waiting to sync on this phone. */
    private val waiting = com.pingolin.track.testutil.InMemoryPendingChangeDao()

    private fun TestScope.harness(selfRole: String = "owner"): TrustHarness =
        // The waiting changes' clock is the statements' (FakeMembershipStatements.now, seconds).
        TrustHarness(selfRole = selfRole, pendingChanges = com.pingolin.track.testutil.pendingChangesIn(waiting, now = { 1_800_000_000_000L })).also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }

    /**
     * [by] invited dan (kind 1, dan's keys then), and dan's app reported it. Made on this phone
     * ([by] "me"), it left the invitee record inviteToCircle makes.
     */
    private fun TrustHarness.madeHereAndReported(
        kind: Kind = Kind.INVITATION_TO_ACCOUNT,
        by: String = "me",
        createdHere: Boolean = true,
        expiresAt: Long = statements.now + 7 * 86_400,
        signerKey: String = signing(by),
        /** dan's id as the invitation names him. */
        subject: String = dan,
    ): MembershipStatements.Signed {
        val original = invitation(
            subject, by = by, kind = kind, id = "inv-dan", email = "dan@x.test", keysOf = "dan-old",
            expiresAt = expiresAt, signerKey = signerKey,
        )
        if (by == "me") {
            approvals.approveInvitee("c-1", subject.takeIf { kind == Kind.INVITATION_TO_ACCOUNT }, "dan@x.test", "inv-dan", expiresAt)
        }
        // An owner by this device's own record (it created the circle).
        if (createdHere) approvals.markCreatedHere("c-1")
        coEvery { tracksApi.getCircleInvites("c-1") } returns Response.success(ApiResponse(success = true, data = listOf(
            PendingInviteResponse(
                id = "i-1", inviteType = "email", targetEmail = "dan@x.test", status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, statement = original.statementHex,
                statementSignature = original.signatureHex, targetUserId = dan, renewalRequested = true, needsRenewal = true,
            ),
        ), error = null))
        return original
    }

    private fun TrustHarness.directorySays(userId: String = dan, keysOf: String = "dan-new") {
        coEvery { tracksApi.lookupPublicKey("dan@x.test") } returns Response.success(ApiResponse(success = true, data =
            UserPublicKeyResponse("dan@x.test", sharing(keysOf), userId, signing(keysOf)), error = null))
    }

    private fun TrustHarness.renewalTaken(): io.mockk.CapturingSlot<RenewInvitationRequest> {
        val sent = slot<RenewInvitationRequest>()
        coEvery { tracksApi.renewInvitation("c-1", "i-1", capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = RenewInvitationResponse(renewed = true, keyVersion = circleVersion), error = null))
        return sent
    }

    @Test
    fun `while this device's records can't be read nothing is renewed - the question stays, and a waiting renewal can't tell yet`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        val sent = h.renewalTaken()
        suspend fun now() = h.repository.renewalNow("c-1", "i-1", "inv-dan", "dan@x.test", dan, sharing("dan-new"), signing("dan-new"))
        h.approvalPrefs.damaged = true

        assertEquals("The invitation for dan@x.test couldn't be renewed just now.", h.repository.renewInvitation(question).exceptionOrNull()?.message)
        assertEquals(CircleRepository.RenewalNow.CouldNotRead, now())
        assertFalse(sent.isCaptured)
        assertEquals("the question stays", 1, h.repository.inviteRenewalQuestions.value.size)

        h.approvalPrefs.damaged = false
        assertTrue(now() is CircleRepository.RenewalNow.Pending)
    }

    @Test
    fun `asked once about an invitation this phone made, and It's them renews it for exactly the keys shown`() = runTest {
        val h = harness()
        val original = h.madeHereAndReported()
        h.directorySays()

        h.repository.askAboutRenewals("c-1")

        val question = h.repository.inviteRenewalQuestions.value.single()
        assertEquals("dan@x.test", question.email)
        assertEquals(sharing("dan-new"), question.sharingKeyHex)
        assertEquals(signing("dan-new"), question.signingKeyHex)
        h.repository.renewalLooks.clear()
        h.repository.askAboutRenewals("c-1")
        assertEquals("not twice", 1, h.repository.inviteRenewalQuestions.value.size)

        val sent = h.renewalTaken()
        assertTrue(h.repository.renewInvitation(question).isSuccess)

        val renewed = h.statements.parse(sent.captured.statement)!!
        val was = h.statements.parse(original.statementHex)!!
        assertEquals("the original's id", "inv-dan", renewed.statementId)
        assertEquals("the original's expiry", was.expiresAt, renewed.expiresAt)
        assertEquals(dan, renewed.subjectUserId)
        assertEquals(sharing("dan-new"), renewed.subjectSharingKeyHex)
        assertEquals(signing("dan-new"), renewed.subjectSigningKeyHex)
        // The circle's copy goes to those keys only.
        assertEquals("c-${sharing("dan-new")}", sent.captured.encryptedCircleKey?.ciphertext)
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
        assertTrue(h.approvals.renewalAsked("c-1", "inv-dan"))
    }

    @Test
    fun `on a circle with records a renewal carries the key only by an owner or admin in the chain`() = runTest {
        // An invitation's key is a delivery.
        for ((role, carries) in listOf(uniffi.pingolin_mobile.CircleRole.ADMIN to true, uniffi.pingolin_mobile.CircleRole.MEMBER to false)) {
            val h = harness()
            h.madeHereAndReported()
            h.directorySays()
            io.mockk.every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns true
            io.mockk.every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 3)
            io.mockk.every { h.cryptoManager.chainRole("c-1", "me") } returns role
            h.repository.askAboutRenewals("c-1")
            val question = h.repository.inviteRenewalQuestions.value.single()
            val sent = h.renewalTaken()

            assertTrue("$role", h.repository.renewInvitation(question).isSuccess)

            assertEquals("$role", carries, sent.captured.encryptedCircleKey != null)
        }
    }

    @Test
    fun `a waiting renewal whose invitee is removed while it runs is stopped at the signature, and nothing is sent`() = runTest {
        // Removed in the circle's records while it runs: stopped at the signature.
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        h.renewalTaken()

        val result = h.repository.renewInvitation(
            question,
            pendingRun = com.pingolin.track.domain.pending.PendingRun(
                "ch-1", "ch-1", beforeSend = { throw com.pingolin.track.domain.pending.RemovedSinceDecided() },
            ),
        )

        assertTrue(result.exceptionOrNull() is com.pingolin.track.domain.pending.RemovedSinceDecided)
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
    }

    /** One made before a password reset, under a restored identity's key, is this account's too. */
    @Test
    fun `an invitation made before a reset is asked about, and renewed with the current key`() = runTest {
        val h = harness()
        h.statements.previousSigningKeys += "me-before-reset"
        h.madeHereAndReported(signerKey = "me-before-reset")
        h.directorySays()

        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        val sent = h.renewalTaken()
        assertTrue(h.repository.renewInvitation(question).isSuccess)

        assertTrue(h.statements.verify(sent.captured.statement, sent.captured.statementSignature, signing("me")))
        assertEquals("inv-dan", h.statements.parse(sent.captured.statement)!!.statementId)
    }

    @Test
    fun `a report alone asks nothing - the directory shows the keys it was made for`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays(keysOf = "dan-old")

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `the directory naming dan in upper case or 32 hex is dan - asked, and It's them renews him`() = runTest {
        for (spelling in listOf(dan.uppercase(), dan.replace("-", ""))) {
            val h = harness()
            h.madeHereAndReported()
            h.directorySays(userId = spelling)

            h.repository.askAboutRenewals("c-1")

            val question = h.repository.inviteRenewalQuestions.value.single()
            assertEquals(spelling, dan, question.userId)
            val sent = h.renewalTaken()
            assertTrue(spelling, h.repository.renewInvitation(question).isSuccess)
            assertEquals(spelling, dan, h.statements.parse(sent.captured.statement)!!.subjectUserId)
        }
    }

    @Test
    fun `an invitation naming dan in another spelling is still his - asked, re-asked on new keys, and renewed`() = runTest {
        val h = harness()
        h.madeHereAndReported(subject = dan.replace("-", ""))
        h.directorySays()

        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        // His keys changed again by the answer: the same account, so asked about the new ones.
        h.directorySays(keysOf = "dan-newer")
        assertEquals(
            "dan@x.test's details changed again, so you're asked about the new ones.",
            h.repository.renewInvitation(question).exceptionOrNull()?.message,
        )
        val again = h.repository.inviteRenewalQuestions.value.single()
        val sent = h.renewalTaken()

        assertTrue(h.repository.renewInvitation(again).isSuccess)

        assertEquals(sharing("dan-newer"), h.statements.parse(sent.captured.statement)!!.subjectSharingKeyHex)
    }

    @Test
    fun `a directory id that isn't a UUID is no answer - nothing asked, and It's them couldn't check, the question kept`() = runTest {
        for (notId in listOf("{$dan}", "urn:uuid:$dan", " $dan", "user-dan")) {
            val h = harness()
            h.madeHereAndReported()
            h.directorySays(userId = notId)

            h.repository.askAboutRenewals("c-1")
            assertTrue(notId, h.repository.inviteRenewalQuestions.value.isEmpty())

            // Asked once it names dan: the answer that named no UUID marked nothing.
            h.repository.renewalLooks.clear()
            h.directorySays()
            h.repository.askAboutRenewals("c-1")
            val question = h.repository.inviteRenewalQuestions.value.single()
            h.directorySays(userId = notId)
            h.renewalTaken()

            assertEquals(notId, "Couldn't check dan@x.test just now.", h.repository.renewInvitation(question).exceptionOrNull()?.message)
            val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1")
            assertTrue(notId, h.repository.renewInvitation(question, pendingRun = run).exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
            assertEquals("the question stays", listOf(question.statementId), h.repository.inviteRenewalQuestions.value.map { it.statementId })
            assertTrue(notId, h.statements.renewed.isEmpty())
            coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        }
    }

    @Test
    fun `another account behind the address now is never a renewal`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays(userId = mallory)

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `an invitation another member made is not asked about here`() = runTest {
        val h = harness()
        h.madeHereAndReported(by = "bob")
        h.directorySays()

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `an owner or admin by the circle list asks too - createdHere, else the list's role`() = runTest {
        // The owner, by the circle list only (another device of this account created it).
        val owner = harness()
        owner.madeHereAndReported(createdHere = false)
        owner.directorySays()
        owner.repository.askAboutRenewals("c-1")
        assertEquals(1, owner.repository.inviteRenewalQuestions.value.size)

        // An admin: the circle list says so through this account's own row.
        val admin = harness(selfRole = "member")
        admin.madeHereAndReported(createdHere = false)
        admin.directorySays()
        coEvery { admin.circleMemberDao.get("c-1", "me") } returns
            com.pingolin.track.data.local.entity.CircleMemberEntity("c-1", "me", "Me", role = "admin", joinedAt = 0)
        admin.repository.askAboutRenewals("c-1")
        assertEquals(1, admin.repository.inviteRenewalQuestions.value.size)
    }

    @Test
    fun `a plain member is never asked - not even about an invitation it once made`() = runTest {
        val h = harness(selfRole = "member")
        h.madeHereAndReported(createdHere = false)
        h.directorySays()
        coEvery { h.circleMemberDao.get("c-1", "me") } returns
            com.pingolin.track.data.local.entity.CircleMemberEntity("c-1", "me", "Me", role = "member", joinedAt = 0)

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `the original is checked first - expired, not our signature, or its subject gone is never asked about`() = runTest {
        val expired = harness()
        expired.madeHereAndReported(expiresAt = expired.statements.now - 1)
        expired.directorySays()
        expired.repository.askAboutRenewals("c-1")
        assertTrue("expired", expired.repository.inviteRenewalQuestions.value.isEmpty())

        val forged = harness()
        forged.madeHereAndReported(signerKey = signing("mallory"))
        forged.directorySays()
        forged.repository.askAboutRenewals("c-1")
        assertTrue("not signed by this account's key", forged.repository.inviteRenewalQuestions.value.isEmpty())

        // Not pending by this phone's own record: withdrawn or refused here, or never
        // recorded.
        val withdrawn = harness()
        withdrawn.madeHereAndReported()
        withdrawn.directorySays()
        withdrawn.approvals.withdrawInvitation("c-1", "inv-dan")
        withdrawn.repository.askAboutRenewals("c-1")
        assertTrue("withdrawn", withdrawn.repository.inviteRenewalQuestions.value.isEmpty())

        val dead = harness()
        dead.madeHereAndReported()
        dead.directorySays()
        dead.approvals.markInvitationDead("c-1", "inv-dan")
        dead.repository.askAboutRenewals("c-1")
        assertTrue("dead", dead.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `the record, not a tombstone, says the invitation stands - a returning member's new invitation is renewable`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        // dan was a member once, and left at version 3; this is a new invitation.
        h.approvalPrefs.edit().putStringSet("me|c-1#tomb", setOf("dan\t3")).apply()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        h.renewalTaken()

        assertTrue(h.repository.renewInvitation(question).isSuccess)
    }

    @Test
    fun `a roster pass before they join never tombstones the invitee - the question is still asked`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        // As inviteToCircle records it (the invitation signed here, and its expiry); dan is not
        // on the member list yet.
        h.approvals.approveInvitee("c-1", dan, "dan@x.test", "inv-dan", h.statements.now + 7 * 86_400)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner")))

        h.repository.refreshMembers("c-1")

        assertTrue("the roster pass ran", h.storedRows.any { it.userId == "me" })
        assertEquals("no tombstone for a pending invitee", emptyMap<String, Int>(), h.approvals.tombstones("c-1"))
        h.repository.askAboutRenewals("c-1")
        assertEquals(1, h.repository.inviteRenewalQuestions.value.size)
    }

    @Test
    fun `an address invitation is never renewed`() = runTest {
        val h = harness()
        h.madeHereAndReported(kind = Kind.INVITATION_TO_EMAIL)
        h.directorySays()

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `No withdraws the invitation for good`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        coEvery { h.tracksApi.revokeInvite("c-1", "i-1") } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.declineRenewal(h.repository.inviteRenewalQuestions.value.single()).isSuccess)

        coVerify(exactly = 1) { h.tracksApi.revokeInvite("c-1", "i-1") }
        assertTrue(h.approvals.invitationDead("c-1", "inv-dan"))
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
    }

    @Test
    fun `No brings back the removal its invitation cleared, even while the server can't be reached`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        // This user removed dan without a key change, then re-invited them: the clear is kept against the invitation.
        h.ownRemovals.add("c-1", dan, h.statements.now - 100)
        h.ownRemovals.clearedByInvitation("c-1", dan, "inv-dan", h.statements.now + 7 * 86_400)
        coEvery { h.tracksApi.revokeInvite("c-1", "i-1") } throws java.io.IOException("offline")
        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.declineRenewal(h.repository.inviteRenewalQuestions.value.single()).isFailure)

        assertEquals("the invitation is dead here: its removal stands", setOf(dan), h.ownRemovals.of("c-1"))
        assertEquals(h.statements.now - 100, h.ownRemovals.removedAt("c-1", dan))
    }

    /** A renewal made while behind would carry this phone's older version, which the server refuses. */
    @Test
    fun `while this phone is behind It's them signs nothing, says what is true, and the question waits`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        val sent = h.renewalTaken()
        // The circle moved on without this phone, and catching up doesn't bring it.
        h.repository.catchUpPausesMs = emptyList()
        coEvery { h.tracksApi.requestAccess("c-1") } throws java.io.IOException("offline")
        h.heldKeyVersion = 2

        val result = h.repository.renewInvitation(question)

        assertEquals(
            "Not done: this phone is still waiting to sync this circle's settings from another member.",
            result.exceptionOrNull()?.message,
        )
        assertTrue("nothing signed", h.statements.renewed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        assertEquals(listOf(question), h.repository.inviteRenewalQuestions.value)
        assertEquals(listOf(question.statementId), h.renewalQuestions.all().map { it.statementId })

        // Caught up: the same question renews it.
        h.heldKeyVersion = 3
        assertTrue(h.repository.renewInvitation(question).isSuccess)
        assertEquals("inv-dan", h.statements.parse(sent.captured.statement)!!.statementId)
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `while behind, It's them waits as a change bound to the keys shown, until the invitation runs out`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        h.renewalTaken()
        h.repository.catchUpPausesMs = emptyList()
        coEvery { h.tracksApi.requestAccess("c-1") } throws java.io.IOException("offline")
        h.heldKeyVersion = 2

        val result = h.repository.renewInvitation(question, keepIfBehind = true)

        assertTrue(result.exceptionOrNull() is com.pingolin.track.domain.pending.QueuedForSync)
        assertTrue("nothing signed", h.statements.renewed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        val kept = waiting.changes.value.single()
        assertEquals(
            com.pingolin.track.domain.pending.PendingInputs.RenewInvitation(
                "Family", question.inviteId, question.statementId, question.userId, question.email,
                question.sharingKeyHex, question.signingKeyHex, tapVersion = 2,
            ),
            com.pingolin.track.domain.pending.PendingInputs.read(kept),
        )
        assertEquals("its invitation's own end", h.statements.parse(question.original.statementHex)!!.expiresAt * 1000, kept.expiresAt)
        assertTrue("the question is answered", h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `It's them when the address can't be checked is kept with its waiting line, signing nothing`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        h.renewalTaken()
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns
            Response.error(503, """{"success":false,"error":"x","error_code":"lookup_unavailable"}""".toResponseBody(null))

        val result = h.repository.renewInvitation(question, keepIfBehind = true)

        assertEquals(
            "Waiting to sync. dan@x.test's invitation will be renewed as soon as Pingolin can check the address.",
            result.exceptionOrNull()?.message,
        )
        assertTrue("nothing signed", h.statements.renewed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        val change = waiting.changes.value.single()
        val kept = com.pingolin.track.domain.pending.PendingInputs.read(change)
            as com.pingolin.track.domain.pending.PendingInputs.RenewInvitation
        assertTrue(kept.addressCheck)
        assertEquals(question.sharingKeyHex, kept.sharingKey)
        assertEquals("the version held at the tap", 3, kept.tapVersion)
        assertEquals("until the invitation's own end", h.statements.parse(question.original.statementHex)!!.expiresAt * 1000, change.expiresAt)
        assertTrue("the question is answered", h.repository.inviteRenewalQuestions.value.isEmpty())

        // A waiting run meanwhile: it waits, nothing signed.
        val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1")
        assertTrue(h.repository.renewInvitation(question, pendingRun = run).exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
        assertTrue(h.statements.renewed.isEmpty())

        // A 408 (a time limit) or a 429 (try later) is no answer too.
        for (status in listOf(408, 429)) {
            coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.error(status, """{"success":false}""".toResponseBody(null))
            assertTrue("$status", h.repository.renewInvitation(question, pendingRun = run).exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
            assertEquals(
                "Waiting to sync. dan@x.test's invitation will be renewed as soon as Pingolin can check the address.",
                h.repository.renewInvitation(question, keepIfBehind = true).exceptionOrNull()?.message,
            )
        }
        assertTrue(h.statements.renewed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
    }

    @Test
    fun `the check answers but names no account any more - can't be renewed, the question goes, nothing signed`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.error(404, """{"success":false}""".toResponseBody(null))

        val result = h.repository.renewInvitation(question, keepIfBehind = true)

        assertEquals("The invitation for dan@x.test can't be renewed any more.", result.exceptionOrNull()?.message)
        assertTrue("the question goes", h.repository.inviteRenewalQuestions.value.isEmpty())
        assertTrue("never kept", waiting.changes.value.isEmpty())
        assertTrue(h.statements.renewed.isEmpty())
        // A waiting run meets the same answer: it ends, never waits on it.
        val waited = h.repository.renewInvitation(question, pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1"))
        assertEquals("The invitation for dan@x.test can't be renewed any more.", waited.exceptionOrNull()?.message)
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
    }

    @Test
    fun `a refused check (a 4xx) is never kept - It's them couldn't check, a run waits`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.error(403, """{"success":false}""".toResponseBody(null))

        val result = h.repository.renewInvitation(question, keepIfBehind = true)

        assertEquals("Couldn't check dan@x.test just now.", result.exceptionOrNull()?.message)
        val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1")
        assertTrue(h.repository.renewInvitation(question, pendingRun = run).exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
        assertTrue("never kept", waiting.changes.value.isEmpty())
        assertTrue(h.statements.renewed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
    }

    @Test
    fun `a No the server doesn't take is told in this app's words, never the server's`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        coEvery { h.tracksApi.revokeInvite("c-1", "i-1") } returns
            Response.success(ApiResponse(success = false, data = null, error = "Invite not found or expired"))

        val result = h.repository.declineRenewal(h.repository.inviteRenewalQuestions.value.single())

        assertEquals("Couldn't withdraw the invitation just now.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `asked again at most once - a second change by the answer renews nothing and asks no more`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        h.renewalTaken()

        h.directorySays(keysOf = "dan-newer")
        assertTrue(h.repository.renewInvitation(h.repository.inviteRenewalQuestions.value.single()).isFailure)
        val again = h.repository.inviteRenewalQuestions.value.single()
        assertTrue(again.reasked)

        h.directorySays(keysOf = "dan-newest")
        assertTrue(h.repository.renewInvitation(again).isFailure)

        assertTrue("no third question", h.repository.inviteRenewalQuestions.value.isEmpty())
        assertTrue("answered for good", h.approvals.renewalAsked("c-1", "inv-dan"))
        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        h.repository.renewalLooks.clear()
        h.repository.askAboutRenewals("c-1")
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }

    @Test
    fun `details that changed again by the answer are asked about again, and nothing is signed`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val question = h.repository.inviteRenewalQuestions.value.single()
        h.renewalTaken()

        h.directorySays(keysOf = "dan-newer")
        assertTrue(h.repository.renewInvitation(question).isFailure)

        coVerify(exactly = 0) { h.tracksApi.renewInvitation(any(), any(), any()) }
        assertEquals(sharing("dan-newer"), h.repository.inviteRenewalQuestions.value.single().sharingKeyHex)
        // Kept, as asked again: a restart brings back the re-asked question, not a fresh one.
        assertTrue(h.renewalQuestions.all().single().reasked)
    }

    @Test
    fun `a raised question is marked at once, comes back after a restart as it was, and is notified once`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        val raised = h.repository.inviteRenewalQuestions.value.single()
        assertTrue("marked when raised, not only when answered", h.approvals.renewalAsked("c-1", "inv-dan"))

        h.repository.wipeInMemory() // a new process: nothing in memory
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
        h.repository.restoreRenewalQuestions()
        assertEquals(raised, h.repository.inviteRenewalQuestions.value.single())

        h.repository.renewalLooks.clear()
        h.repository.askAboutRenewals("c-1")
        assertEquals(1, h.repository.inviteRenewalQuestions.value.size)
        assertEquals("notified once", 1, h.questions.count { it.startsWith("renew ") })

        h.renewalTaken()
        assertTrue(h.repository.renewInvitation(h.repository.inviteRenewalQuestions.value.single()).isSuccess)
        assertTrue("answered: gone from the store", h.renewalQuestions.all().isEmpty())
    }

    @Test
    fun `asked again once, across a restart - the next change drops it for good`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")
        h.directorySays(keysOf = "dan-newer")
        h.repository.renewInvitation(h.repository.inviteRenewalQuestions.value.single())

        h.repository.wipeInMemory()
        h.repository.restoreRenewalQuestions()
        val restored = h.repository.inviteRenewalQuestions.value.single()
        assertTrue(restored.reasked)

        h.directorySays(keysOf = "dan-newest")
        val result = h.repository.renewInvitation(restored)

        assertEquals("Their details keep changing, so the invitation wasn't renewed.", result.exceptionOrNull()?.message)
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
        assertTrue(h.renewalQuestions.all().isEmpty())
    }

    @Test
    fun `the server's refusals - keys moved again asks once more, gone for good drops it, unreachable keeps it`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.repository.askAboutRenewals("c-1")

        // Unreachable: the question stays for another answer.
        coEvery { h.tracksApi.renewInvitation("c-1", "i-1", any()) } returns
            Response.error(503, """{"success":false}""".toResponseBody(null))
        assertTrue(h.repository.renewInvitation(h.repository.inviteRenewalQuestions.value.single()).isFailure)
        assertEquals(1, h.renewalQuestions.all().size)

        // Their keys moved between the look-up and the PUT: asked again, once.
        coEvery { h.tracksApi.renewInvitation("c-1", "i-1", any()) } answers {
            h.directorySays(keysOf = "dan-newer")
            Response.error(409, """{"success":false,"error_code":"invite_lookup_changed"}""".toResponseBody(null))
        }
        assertTrue(h.repository.renewInvitation(h.repository.inviteRenewalQuestions.value.single()).isFailure)
        val again = h.repository.inviteRenewalQuestions.value.single()
        assertTrue(again.reasked)
        assertEquals(sharing("dan-newer"), again.sharingKeyHex)

        // Renewed or withdrawn meanwhile: nothing left to ask.
        coEvery { h.tracksApi.renewInvitation("c-1", "i-1", any()) } returns
            Response.error(409, """{"success":false,"error_code":"invite_changed"}""".toResponseBody(null))
        assertTrue(h.repository.renewInvitation(again).isFailure)
        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
        assertTrue(h.renewalQuestions.all().isEmpty())
    }

    @Test
    fun `a withdrawn invitation is not renewed even if the server lists it again`() = runTest {
        val h = harness()
        h.madeHereAndReported()
        h.directorySays()
        h.approvals.markInvitationDead("c-1", "inv-dan")

        h.repository.askAboutRenewals("c-1")

        assertTrue(h.repository.inviteRenewalQuestions.value.isEmpty())
    }
}
