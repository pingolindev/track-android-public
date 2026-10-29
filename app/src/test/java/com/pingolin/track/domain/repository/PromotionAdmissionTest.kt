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
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Through the real CircleRepository: promoting a member to admin signs an admission for only
 * the keys this phone holds for them (an email invitee's receive-only recorded pair, else the
 * pinned one), and only while the member list shows exactly those, and, when their number was
 * shown, only the keys behind it. The admission is stored before the role changes, and the
 * whole promotion waits while this phone is behind. Anything else signs nothing and changes
 * no role.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromotionAdmissionTest {

    @After
    fun tearDown() = clearAllMocks()

    /** Changes waiting to sync on the owner's phone. */
    private val waiting = com.pingolin.track.testutil.InMemoryPendingChangeDao()

    /** The owner's phone; erin joined on an address invitation (receive-only), bob is from before. */
    private fun TestScope.ownerPhone(): TrustHarness = TrustHarness(
        selfRole = "owner", pendingChanges = com.pingolin.track.testutil.pendingChangesIn(waiting),
    ).also {
        it.repository.deliveryScope = this
        it.earlierSeed("me", "bob")
        // bob's one-time backfill admission was signed long ago: only promotions sign here.
        it.approvals.markBackfilled("c-1", listOf("bob"))
        it.repository.catchUpPausesMs = emptyList()
    }

    private val erinsInvitation = { h: TrustHarness -> h.invitation("erin", by = "me", kind = Kind.INVITATION_TO_EMAIL, id = "inv-erin") }

    private fun TrustHarness.lists(erinKeysOf: String = "erin", bobKeysOf: String = "bob") = serverLists(listOf(
        rosterMember("me", role = "owner"),
        rosterMember("bob", keysOf = bobKeysOf),
        rosterMember("erin", erinsInvitation(this), keysOf = erinKeysOf),
    ))

    private fun TrustHarness.rolesTaken() {
        coEvery { tracksApi.updateMemberRole("c-1", any(), any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
    }

    @Test
    fun `an email invitee's promotion signs the keys recorded for them, and the admission comes before the role`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        assertEquals(Origin.KIND2, h.origins()["erin"])
        val admitted = h.statementsAccepted()
        h.rolesTaken()

        val question = h.repository.promotionQuestion("c-1", "erin")!!
        assertEquals(sharing("erin") to signing("erin"), question.keys)
        assertEquals("number-of-erin", question.number)
        assertTrue(h.repository.updateMemberRole("c-1", "erin", "admin", question.keys).isSuccess)

        val admission = h.statements.parse(admitted.getValue("erin").statement)!!
        assertEquals(Kind.ADMISSION, admission.kind)
        assertEquals(sharing("erin"), admission.subjectSharingKeyHex)
        assertEquals(signing("erin"), admission.subjectSigningKeyHex)
        coVerifyOrder {
            h.tracksApi.storeMembershipStatement("c-1", "erin", any())
            h.tracksApi.updateMemberRole("c-1", "erin", any())
        }
    }

    @Test
    fun `while the recorded keys can't be read nothing is asked, signed or changed, and once they read the question comes`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        h.statementsAccepted()
        h.rolesTaken()
        h.approvalPrefs.damaged = true

        assertNull(h.repository.promotionQuestion("c-1", "erin"))
        assertEquals(
            CircleRepository.PROMOTION_NOT_DONE,
            h.repository.updateMemberRole("c-1", "erin", "admin", sharing("erin") to signing("erin")).exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), "erin", any()) }
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }

        h.approvalPrefs.damaged = false
        assertEquals(sharing("erin") to signing("erin"), h.repository.promotionQuestion("c-1", "erin")!!.keys)
    }

    @Test
    fun `swapped keys and a promotion - nothing is signed, the role stays, and the key-change question is asked`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        val question = h.repository.promotionQuestion("c-1", "erin")!!
        // The server now shows other keys for erin.
        h.lists(erinKeysOf = "erin-swapped")
        h.repository.refreshMembers("c-1")
        h.statementsAccepted()
        h.rolesTaken()

        for (decided in listOf(question.keys, null)) {
            val result = h.repository.updateMemberRole("c-1", "erin", "admin", decided)
            assertEquals("Their details changed, so they weren't made an admin.", result.exceptionOrNull()?.message)
        }

        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), "erin", any()) }
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
        assertFalse(h.statements.signed.any { h.statements.parse(it.statementHex)?.kind == Kind.ADMISSION })
        assertEquals(Origin.KIND2, h.origins()["erin"])
        val alert = h.repository.memberKeyAlerts.value.single { it.userId == "erin" }
        assertTrue(alert.receiveOnly)
        assertEquals(sharing("erin-swapped"), alert.newSharingHex)
    }

    @Test
    fun `the number shown for keys A, then the list shows B before the tap - nothing is signed for B`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        val shownForA = h.repository.promotionQuestion("c-1", "erin")!!
        h.statementsAccepted()
        h.rolesTaken()

        // The list moves to B before the tap.
        h.lists(erinKeysOf = "erin-b")
        h.repository.refreshMembers("c-1")
        assertTrue(h.repository.updateMemberRole("c-1", "erin", "admin", shownForA.keys).isFailure)

        // "It's them" for B after the number for A was shown: B is held and listed now, but it
        // isn't what the person compared.
        h.repository.approveMemberKeyChange(h.repository.memberKeyAlerts.value.single { it.userId == "erin" })
        assertEquals(sharing("erin-b") to signing("erin-b"), h.repository.promotionKeys("c-1", "erin"))
        assertTrue(h.repository.updateMemberRole("c-1", "erin", "admin", shownForA.keys).isFailure)

        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), "erin", any()) }
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
    }

    @Test
    fun `a pinned member's promotion signs the pinned keys, and a key change nobody accepted signs nothing`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        assertNull("no question for a member confirmed with keys", h.repository.promotionQuestion("c-1", "bob"))
        val admitted = h.statementsAccepted()
        h.rolesTaken()

        assertTrue(h.repository.updateMemberRole("c-1", "bob", "admin").isSuccess)
        assertEquals(sharing("bob"), h.statements.parse(admitted.getValue("bob").statement)!!.subjectSharingKeyHex)

        // bob's keys change and nobody said "It's them".
        admitted.clear()
        h.lists(bobKeysOf = "bob-new")
        h.repository.refreshMembers("c-1")
        assertTrue(h.repository.updateMemberRole("c-1", "bob", "admin").isFailure)
        assertFalse(admitted.containsKey("bob"))
        coVerify(exactly = 1) { h.tracksApi.updateMemberRole("c-1", "bob", any()) }
    }

    @Test
    fun `while behind the whole promotion waits - nothing signed, no role change, and what is true`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        h.statementsAccepted()
        h.rolesTaken()
        coEvery { h.tracksApi.requestAccess("c-1") } throws java.io.IOException("offline")
        h.heldKeyVersion = 2

        val result = h.repository.updateMemberRole("c-1", "bob", "admin")

        assertEquals(
            "Not done: this phone is still waiting to sync this circle's settings from another member.",
            result.exceptionOrNull()?.message,
        )
        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
    }

    @Test
    fun `while behind, a promotion the person asks for waits, bound to the keys behind the number shown`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        h.statementsAccepted()
        h.rolesTaken()
        val question = h.repository.promotionQuestion("c-1", "erin")!!
        coEvery { h.tracksApi.requestAccess("c-1") } throws java.io.IOException("offline")
        h.heldKeyVersion = 2

        val result = h.repository.updateMemberRole("c-1", "erin", "admin", question.keys, keepIfBehind = true)

        assertTrue(result.exceptionOrNull() is com.pingolin.track.domain.pending.QueuedForSync)
        val kept = com.pingolin.track.domain.pending.PendingInputs.read(waiting.changes.value.single())
            as com.pingolin.track.domain.pending.PendingInputs.MakeAdmin
        assertEquals(question.keys, kept.sharingKey to kept.signingKey)
        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
    }

    @Test
    fun `a waiting promotion's run saves the admission's id before it is stored, then changes the role`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        val admitted = h.statementsAccepted()
        h.rolesTaken()
        val question = h.repository.promotionQuestion("c-1", "erin")!!
        val saved = mutableListOf<String>()
        val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { saved += it.id })

        assertTrue(h.repository.updateMemberRole("c-1", "erin", "admin", question.keys, pendingRun = run).isSuccess)

        assertEquals(h.statements.parse(admitted.getValue("erin").statement)!!.statementId, saved.single())
        coVerifyOrder {
            h.tracksApi.storeMembershipStatement("c-1", "erin", any())
            h.tracksApi.updateMemberRole("c-1", "erin", any())
        }
    }

    @Test
    fun `a waiting promotion whose admission is already stored changes only the role, signing nothing`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        h.statementsAccepted()
        h.rolesTaken()
        val question = h.repository.promotionQuestion("c-1", "erin")!!
        val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") }, admissionHeld = true)

        assertTrue(h.repository.updateMemberRole("c-1", "erin", "admin", question.keys, pendingRun = run).isSuccess)

        coVerify(exactly = 0) { h.tracksApi.storeMembershipStatement(any(), any(), any()) }
        coVerify(exactly = 1) { h.tracksApi.updateMemberRole("c-1", "erin", any()) }
    }

    @Test
    fun `an admission the server doesn't store changes no role`() = runTest {
        val h = ownerPhone()
        h.lists()
        h.repository.refreshMembers("c-1")
        coEvery { h.tracksApi.storeMembershipStatement("c-1", any(), any()) } returns
            Response.error(503, "unavailable".toResponseBody("text/plain".toMediaType()))
        h.rolesTaken()

        val result = h.repository.updateMemberRole("c-1", "bob", "admin")

        assertEquals("Nothing was changed. Please try again.", result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
        // Making someone a plain member signs nothing and goes straight through.
        assertTrue(h.repository.updateMemberRole("c-1", "bob", "member").isSuccess)
        coVerify(exactly = 1) { h.tracksApi.storeMembershipStatement(any(), any(), any()) }
    }
}
