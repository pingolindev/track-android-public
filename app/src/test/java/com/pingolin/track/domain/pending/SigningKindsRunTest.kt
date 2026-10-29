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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InviteRenewalQuestion
import com.pingolin.track.domain.repository.InviteeChangedException
import com.pingolin.track.domain.repository.MembershipStatements
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each signing kind's re-check and run: who may, from the whole member list; the server's
 * "already" codes left to the statement-id look; the person asked again once about other
 * keys, never signed for keys they didn't see; the password waited for.
 */
class SigningKindsRunTest {

    private val circles = mockk<CircleRepository>(relaxed = true)
    private val sessionManager = mockk<SessionManager> { every { getUserId() } returns "me" }
    private var keyMissing = false
    private val executors = PendingExecutors(
        mockk(), mockk(), mockk(), mockk(), mockk(), { circles }, mockk(), mockk(), sessionManager,
        { mockk<com.pingolin.track.domain.repository.AuthRepository> { every { signingKeyMissing() } answers { keyMissing } } },
    )

    private fun change(kind: PendingKind, inputs: PendingInputs) = PendingChangeEntity(
        id = "ch-1", account = "me", circleId = "c-1", kind = kind.name, inputs = PendingInputs.toJson(inputs),
        createdAt = 0, expiresAt = Long.MAX_VALUE, state = PendingChanges.RUNNING,
    )

    private suspend fun run(kind: PendingKind, inputs: PendingInputs, run: PendingRun = PendingRun("ch-1", "ch-1")) =
        executors.forKind(kind)!!.run(change(kind, inputs), inputs, run)

    private val invite = PendingInputs.Invite("Family", "dan@x.test")

    // ---- Invite someone

    @Test
    fun `an invite waits for the password, and signs nothing meanwhile`() = runTest {
        keyMissing = true
        assertEquals(PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD), run(PendingKind.INVITE, invite))
        coVerify(exactly = 0) { circles.inviteToCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `an invite by someone no longer an owner or admin is dropped with that line, from the whole member list`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "member")
        assertEquals(
            PendingOutcome.Dropped("dan@x.test's invitation wasn't done: you're no longer an owner or admin of Family."),
            run(PendingKind.INVITE, invite),
        )
        coEvery { circles.memberRolesWhole("c-1") } returns null
        assertEquals("a member list that didn't load decides nothing", PendingOutcome.Transient(maybeSent = false), run(PendingKind.INVITE, invite))
        coVerify(exactly = 0) { circles.inviteToCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `an invite's already answers go to the look, by code`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner")
        fun refused(code: String) = Result.failure<com.pingolin.track.data.remote.dto.InviteResponse>(RefusedException("x", 409, code))
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns refused("request_already_done")
        assertEquals(PendingOutcome.Already(null), run(PendingKind.INVITE, invite))
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns refused("invite_already_exists")
        assertEquals(PendingOutcome.Already("dan@x.test already has an invitation to Family."), run(PendingKind.INVITE, invite))
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns refused("circle_already_member")
        assertEquals(PendingOutcome.Already("dan@x.test is already in Family."), run(PendingKind.INVITE, invite))
    }

    @Test
    fun `an invite past the invitation e-mail cap ends with its own line, and any other 429 still waits`() = runTest {
        // The invitation e-mail cap's 429 can last most of a day: waiting on it would retry
        // about 30 times a day for a week, and then send the invitation by itself hours after
        // the person had moved on.
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner")
        fun refused(code: String?) = Result.failure<com.pingolin.track.data.remote.dto.InviteResponse>(RefusedException("x", 429, code))
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns refused("invite_limit_reached")
        assertEquals(
            PendingOutcome.Dropped("dan@x.test's invitation wasn't done: too many invitations in the last 24 hours. Try again later."),
            run(PendingKind.INVITE, invite),
        )
        for (code in listOf("rate_limit_exceeded", null)) {
            coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns refused(code)
            assertEquals("429 $code", PendingOutcome.Transient(maybeSent = false), run(PendingKind.INVITE, invite))
        }
    }

    @Test
    fun `an invite - Is this really asked once, then the details keep changing`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin")
        val changed = InviteeChangedException("c-1", "dan@x.test", "dan", "s2", "g2", "1 2 3")
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), any()) } returns Result.failure(changed)
        assertEquals(
            PendingOutcome.NeedsPerson(PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.INVITEE, "dan", "s2", "g2", "1 2 3"))),
            run(PendingKind.INVITE, invite),
        )

        val confirmed = invite.copy(confirmedUserId = "dan", confirmedSharingKey = "s2", confirmedSigningKey = "g2")
        coEvery { circles.confirmInviteeAndInvite(any(), any(), any()) } returns
            Result.failure(InviteeChangedException("c-1", "dan@x.test", "dan", "s3", "g3", null))
        assertEquals(PendingOutcome.Dropped("Their details keep changing, so dan@x.test wasn't invited."), run(PendingKind.INVITE, confirmed))

        // The address names another account by then: never invited in its place.
        coEvery { circles.confirmInviteeAndInvite(any(), any(), any()) } returns
            Result.failure(com.pingolin.track.domain.repository.InviteeNotAsConfirmedException("dan@x.test"))
        assertEquals(PendingOutcome.Dropped("Their details keep changing, so dan@x.test wasn't invited."), run(PendingKind.INVITE, confirmed))
    }

    // ---- Make admin

    private val makeAdmin = PendingInputs.MakeAdmin("Family", "sam", "Sam", "s1", "g1")

    @Test
    fun `a promotion - member left, or not the owner any more, from the whole member list`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner")
        assertEquals(PendingOutcome.Dropped("Making Sam an admin wasn't done: Sam is no longer in Family."), run(PendingKind.MAKE_ADMIN, makeAdmin))
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        assertEquals(
            PendingOutcome.Dropped("Making Sam an admin wasn't done: you're no longer an owner or admin of Family."),
            run(PendingKind.MAKE_ADMIN, makeAdmin),
        )
    }

    @Test
    fun `a promotion with other keys than the person saw - asked once, never signed for them`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.promotionKeysNow("c-1", "sam") } returns null
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s2" to "g2")
        coEvery { circles.computeSafetyNumber("sam", "s2", "g2") } returns "9 9 9"

        assertEquals(
            PendingOutcome.NeedsPerson(PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.MEMBER, "sam", "s2", "g2", "9 9 9"))),
            run(PendingKind.MAKE_ADMIN, makeAdmin),
        )
        coVerify(exactly = 0) { circles.updateMemberRole(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a promotion answered It's them - the new keys are taken through the live answer, then the promotion goes`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        val answered = makeAdmin.copy(sharingKey = "s2", signingKey = "g2", asked = true)
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s2" to "g2")
        var accepted = false
        coEvery { circles.promotionKeysNow("c-1", "sam") } answers { if (accepted) "s2" to "g2" else null }
        coEvery { circles.acceptMemberKeys("c-1", "sam", "Sam", "s2" to "g2") } answers { accepted = true }
        coEvery { circles.updateMemberRole(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val run = PendingRun("ch-1", "ch-1")

        assertEquals(PendingOutcome.Done(), run(PendingKind.MAKE_ADMIN, answered, run))
        coVerify(exactly = 1) { circles.acceptMemberKeys("c-1", "sam", "Sam", "s2" to "g2") }
        coVerify(exactly = 1) {
            circles.updateMemberRole("c-1", "sam", "admin", "s2" to "g2", false, match { it.changeId == run.changeId && it.beforeSend != null })
        }
    }

    @Test
    fun `a promotion answered, then changed again - dropped, never asked a second time`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.promotionKeysNow("c-1", "sam") } returns null
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s3" to "g3")

        assertEquals(
            PendingOutcome.Dropped("Their details keep changing, so Sam wasn't made an admin."),
            run(PendingKind.MAKE_ADMIN, makeAdmin.copy(sharingKey = "s2", signingKey = "g2", asked = true)),
        )
        coVerify(exactly = 0) { circles.acceptMemberKeys(any(), any(), any(), any()) }
    }

    @Test
    fun `a promotion whose admission the look found stored - no password needed, only the role`() = runTest {
        keyMissing = true
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.updateMemberRole(any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        val run = PendingRun("ch-1", "ch-1", admissionHeld = true)

        assertEquals(PendingOutcome.Done(), run(PendingKind.MAKE_ADMIN, makeAdmin, run))
        coVerify(exactly = 0) { circles.promotionKeysNow(any(), any()) }
    }

    // ---- Let them in

    private val letIn = PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s1", "g1")

    @Test
    fun `a let-in for someone already let in by someone else is done, and says so`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.letInNow("c-1", "sam") } returns CircleRepository.LetInNow.LET_IN

        assertEquals(PendingOutcome.Done("Sam was already let in."), run(PendingKind.LET_IN, letIn))
        coVerify(exactly = 0) { circles.grantKeyAccess(any(), any()) }
    }

    @Test
    fun `a let-in no longer asked about, and not let in either - not done, never "already let in"`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.letInNow("c-1", "sam") } returns CircleRepository.LetInNow.NOT_ASKED

        assertEquals(PendingOutcome.Dropped("Letting Sam in wasn't done."), run(PendingKind.LET_IN, letIn))
        coEvery { circles.letInNow("c-1", "sam") } returns null
        assertEquals("a roster that didn't load decides nothing", PendingOutcome.Transient(maybeSent = false), run(PendingKind.LET_IN, letIn))
        coVerify(exactly = 0) { circles.grantKeyAccess(any(), any()) }
    }

    @Test
    fun `a let-in with its admission stored records the choice only - after the member check, before who may sign`() = runTest {
        keyMissing = true
        // Not an owner or admin any more: that gates signing only, and nothing is signed here.
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "member", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.grantKeyAccess(any(), any()) } returns Result.success(Unit)
        val held = PendingRun("ch-1", "ch-1", admissionHeld = true)

        assertEquals(PendingOutcome.Done(), run(PendingKind.LET_IN, letIn, held))
        coVerify(exactly = 1) { circles.grantKeyAccess(match { it.userId == "sam" && it.sharingKeyHex == "s1" && it.signingKeyHex == "g1" }, held) }
        coVerify(exactly = 0) { circles.letInNow(any(), any()) }

        // Gone from the circle by now: nothing to record for them.
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me")
        assertEquals(PendingOutcome.Dropped("Letting Sam in wasn't done: Sam is no longer in Family."), run(PendingKind.LET_IN, letIn, held))
        coEvery { circles.memberIdsWhole("c-1") } returns null
        assertEquals(PendingOutcome.Transient(maybeSent = false), run(PendingKind.LET_IN, letIn, held))
        coVerify(exactly = 1) { circles.grantKeyAccess(any(), any()) }
    }

    @Test
    fun `a let-in's end with its admission held records the choice only while the complete roster lists them`() = runTest {
        val ended = change(PendingKind.LET_IN, letIn)
        coEvery { circles.recordLetInChoice("c-1", "sam") } returns true

        // Listed: the choice, read back.
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        assertTrue(executors.recordLetIn(ended))
        coVerify(exactly = 1) { circles.recordLetInChoice("c-1", "sam") }

        // Not listed: nobody to choose; it ends with no choice.
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me")
        assertTrue(executors.recordLetIn(ended))

        // No complete roster: it waits.
        coEvery { circles.memberIdsWhole("c-1") } returns null
        assertTrue(!executors.recordLetIn(ended))
        coVerify(exactly = 1) { circles.recordLetInChoice(any(), any()) }

        // Listed, but the choice didn't reach the disk (or failed): it waits.
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.recordLetInChoice("c-1", "sam") } returns false
        assertTrue(!executors.recordLetIn(ended))
        coEvery { circles.recordLetInChoice("c-1", "sam") } throws IllegalStateException("disk")
        assertTrue(!executors.recordLetIn(ended))

        // Never through the live answer's check against what this phone last cached.
        coVerify(exactly = 0) { circles.grantKeyAccess(any(), any()) }
        coVerify(exactly = 0) { circles.listedKeys(any(), any()) }
    }

    @Test
    fun `a let-in whose own choice is recorded but none of its admissions stored goes on and makes one`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.letInNow("c-1", "sam") } returns CircleRepository.LetInNow.CHOSEN_HERE
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s1" to "g1")
        coEvery { circles.grantKeyAccess(any(), any()) } returns Result.success(Unit)

        assertEquals("never \"already let in\" for its own choice", PendingOutcome.Done(), run(PendingKind.LET_IN, letIn))
        coVerify(exactly = 1) { circles.grantKeyAccess(any(), any()) }
    }

    @Test
    fun `a let-in with other keys than the question showed - asked again once, then dropped`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.letInNow("c-1", "sam") } returns CircleRepository.LetInNow.WAITING
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s2" to "g2")
        coEvery { circles.computeSafetyNumber("sam", "s2", "g2") } returns "4 4"

        assertEquals(
            PendingOutcome.NeedsPerson(PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.LET_IN, "sam", "s2", "g2", "4 4"))),
            run(PendingKind.LET_IN, letIn),
        )
        assertEquals(
            PendingOutcome.Dropped("Their details keep changing, so Sam wasn't let in."),
            run(PendingKind.LET_IN, letIn.copy(sharingKey = "s9", signingKey = "g9", asked = true)),
        )
        coVerify(exactly = 0) { circles.grantKeyAccess(any(), any()) }
    }

    // ---- Renew an invitation

    private val renewal = PendingInputs.RenewInvitation("Family", "inv-1", "st-1", "dan", "dan@x.test", "s1", "g1")

    @Test
    fun `a let-in about a late joiner asks E again, never whether they're confirmed`() = runTest {
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.removedSinceDecided(any(), any(), any(), any()) } returns false
        coEvery { circles.lateJoinerNow("c-1", "sam") } returns CircleRepository.LetInNow.LET_IN

        assertEquals(
            PendingOutcome.Done(PendingLines.alreadyLetIn("Sam")),
            run(PendingKind.LET_IN, letIn.copy(questionKind = com.pingolin.track.domain.repository.KeyAccessRequest.Reason.LATE_JOINER.name)),
        )
        coVerify(exactly = 0) { circles.letInNow(any(), any()) }
    }

    // ---- Removed in the circle's records after the person decided

    @Test
    fun `a change that would sign for someone the records removed after the person decided ends with "no longer in"`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.removedSinceDecided("c-1", "sam", 3, any()) } returns true
        coEvery { circles.removedSinceDecided("c-1", "dan", 3, "s1" to "g1") } returns true

        assertEquals(
            PendingOutcome.Dropped("Making Sam an admin wasn't done: Sam is no longer in Family."),
            run(PendingKind.MAKE_ADMIN, makeAdmin.copy(tapVersion = 3)),
        )
        assertEquals(
            PendingOutcome.Dropped("Letting Sam in wasn't done: Sam is no longer in Family."),
            run(PendingKind.LET_IN, letIn.copy(tapVersion = 3)),
        )
        assertTrue(run(PendingKind.RENEW_INVITATION, renewal.copy(tapVersion = 3)) is PendingOutcome.Dropped)
        coVerify(exactly = 0) { circles.updateMemberRole(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { circles.grantKeyAccess(any(), any()) }
        coVerify(exactly = 0) { circles.renewInvitation(any(), any()) }

        // Can't be told now: it waits, and signs nothing.
        coEvery { circles.removedSinceDecided("c-1", "sam", 3, any()) } returns null
        assertEquals(PendingOutcome.Transient(maybeSent = false), run(PendingKind.LET_IN, letIn.copy(tapVersion = 3)))
    }

    @Test
    fun `the removal is asked again at the signature, once the version is fixed - a promotion, a let-in and a renewal`() = runTest {
        // An eject accepted while the run went on is found only after the first look, and
        // then nothing may leave.
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner", "sam" to "member")
        coEvery { circles.memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { circles.promotionKeysNow("c-1", "sam") } returns ("s1" to "g1")
        coEvery { circles.letInNow("c-1", "sam") } returns CircleRepository.LetInNow.WAITING
        coEvery { circles.listedKeys("c-1", "sam") } returns ("s1" to "g1")
        val question = InviteRenewalQuestion("c-1", "Family", "inv-1", "st-1", "dan@x.test", "dan", "s1", "g1", null, MembershipStatements.Signed("x", "y"))
        coEvery { circles.renewalNow(any(), any(), any(), any(), any(), any(), any()) } returns CircleRepository.RenewalNow.Pending(question)
        var removed: Boolean? = false
        coEvery { circles.removedSinceDecided("c-1", any(), 3, any()) } answers { removed }
        // What the repository does with the run: signs (meanwhile the eject is accepted), then asks before sending.
        suspend fun signs(run: PendingRun): Result<Unit> {
            removed = true
            return runCatching { run.beforeSend!!.invoke() }
        }
        coEvery { circles.updateMemberRole("c-1", "sam", "admin", any(), any(), any()) } coAnswers { signs(arg(5)) }
        coEvery { circles.grantKeyAccess(any(), any()) } coAnswers { signs(arg(1)) }
        coEvery { circles.renewInvitation(question, any(), any()) } coAnswers { signs(arg(2)) }

        assertEquals(
            PendingOutcome.Dropped("Making Sam an admin wasn't done: Sam is no longer in Family."),
            run(PendingKind.MAKE_ADMIN, makeAdmin.copy(tapVersion = 3)),
        )
        removed = false
        assertEquals(
            PendingOutcome.Dropped("Letting Sam in wasn't done: Sam is no longer in Family."),
            run(PendingKind.LET_IN, letIn.copy(tapVersion = 3)),
        )
        removed = false
        assertEquals(
            PendingOutcome.Dropped(PendingLines.memberLeft(PendingInputs.subject(renewal), "dan@x.test", "Family")),
            run(PendingKind.RENEW_INVITATION, renewal.copy(tapVersion = 3)),
        )

        // No answer at the signature: it waits, and nothing left.
        coEvery { circles.grantKeyAccess(any(), any()) } coAnswers {
            removed = null
            runCatching { arg<PendingRun>(1).beforeSend!!.invoke() }
        }
        removed = false
        assertEquals(PendingOutcome.Transient(maybeSent = false), run(PendingKind.LET_IN, letIn.copy(tapVersion = 3)))
    }

    @Test
    fun `an invitation whose invitee the records removed after the person decided ends with "no longer in"`() = runTest {
        coEvery { circles.memberRolesWhole("c-1") } returns mapOf("me" to "owner")
        val run = slot<PendingRun>()
        coEvery { circles.inviteToCircle("c-1", "dan@x.test", any(), capture(run)) } returns Result.failure(RemovedSinceDecided())

        assertEquals(
            PendingOutcome.Dropped("dan@x.test's invitation wasn't done: dan@x.test is no longer in Family."),
            run(PendingKind.INVITE, invite.copy(tapVersion = 3)),
        )
        assertEquals("the version decided at travels with the run", 3, run.captured.tapVersion)
    }

    @Test
    fun `a renewal of an invitation withdrawn or run out meanwhile ends with its line, and one that can't be read waits`() = runTest {
        coEvery { circles.renewalNow("c-1", "inv-1", "st-1", "dan@x.test", "dan", "s1", "g1") } returns CircleRepository.RenewalNow.Ended
        assertEquals(PendingOutcome.Dropped("dan@x.test's invitation wasn't renewed: it was withdrawn or ran out."), run(PendingKind.RENEW_INVITATION, renewal))
        coEvery { circles.renewalNow(any(), any(), any(), any(), any(), any(), any()) } returns CircleRepository.RenewalNow.CouldNotRead
        assertEquals(PendingOutcome.Transient(maybeSent = false), run(PendingKind.RENEW_INVITATION, renewal))
    }

    @Test
    fun `a renewal - other keys in the directory asked once, another account never renewed`() = runTest {
        val question = InviteRenewalQuestion("c-1", "Family", "inv-1", "st-1", "dan@x.test", "dan", "s1", "g1", null, MembershipStatements.Signed("x", "y"))
        coEvery { circles.renewalNow(any(), any(), any(), any(), any(), any(), any()) } returns CircleRepository.RenewalNow.Pending(question)
        coEvery { circles.renewInvitation(question, any(), any()) } returns
            Result.failure(InviteeChangedException("c-1", "dan@x.test", "dan", "s2", "g2", "5"))
        assertEquals(
            PendingOutcome.NeedsPerson(PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.RENEWAL, "dan", "s2", "g2", "5"))),
            run(PendingKind.RENEW_INVITATION, renewal),
        )
        coEvery { circles.renewInvitation(question, any(), any()) } returns
            Result.failure(InviteeChangedException("c-1", "dan@x.test", "eve", "s2", "g2", "5"))
        assertEquals(PendingOutcome.Dropped(PendingLines.RENEWAL_DETAILS_KEEP_CHANGING), run(PendingKind.RENEW_INVITATION, renewal))
    }
}
