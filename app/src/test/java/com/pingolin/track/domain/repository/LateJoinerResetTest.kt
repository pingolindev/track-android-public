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
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.StoreStatementRequest
import com.pingolin.track.data.remote.dto.StoreStatementResponse
import com.pingolin.track.domain.pending.PendingChangeRunner
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingExecutors
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.HeldStatement
import uniffi.pingolin_mobile.MemberEligibility
import uniffi.pingolin_mobile.PinState
import uniffi.pingolin_mobile.RecordMember
import uniffi.pingolin_mobile.RefreshRecordsReport

/**
 * After this account's own password reset (it owns the circle), a member the SDK's eligibility check
 * doesn't let in, with no flag, whose only basis here is this account's own invitation from before
 * the reset, is asked about once: "Let them in?". The yes signs an admission of their listed keys
 * with the CURRENT key, as a waiting "Let them in". And an owner's "It's them" for a member the chain
 * doesn't count lets them in the same way.
 *
 * The SDK is mocked. Its eligibility check lets Maya in only on an admission of the keys listed for
 * her, signed with this account's CURRENT key and served in her row, or, before the reset, on her
 * invitation; it counts this account's own row throughout. Ids are UUIDs: "is this me?" compares
 * canonical forms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LateJoinerResetTest {

    private companion object {
        const val ME = "aaaaaaaa-0000-4000-8000-00000000000a"
        const val MAYA = "cccccccc-0000-4000-8000-00000000000c"
        const val BEN = "dddddddd-0000-4000-8000-00000000000d"
        const val INV = "11111111-0000-4000-8000-000000000001"

        /** This account's signing key before its reset. */
        val OLD = signing("old-me")
    }

    @After
    fun tearDown() = clearAllMocks()

    private var clock = 1_800_000_000_000L
    private val dao = InMemoryPendingChangeDao()
    private val store = pendingChangesIn(dao, account = ME, now = { clock })

    /** Eligibility lets Maya in on her invitation: before the reset that broke it (this account's,
     * or hers).
     */
    private var herInvitationCounts = false
    private var removed = false
    private var forked = false

    /** The eligibility check's three flags for Maya: her invitation ran out, its signer
     * isn't a chain owner or admin, an address invitation.
     */
    private var expired = false
    private var signerNotAdmin = false
    private var addressOnly = false

    /** The eligibility check can't be read (INCLUDE). */
    private var eThrows = false

    /** The chain's role for Maya: null, it doesn't count her (she joined after its last key change). */
    private var mayaRole: CircleRole? = null

    /** Maya's invitation, as her row serves it. */
    private var mayaInvitation: MembershipStatements.Signed? = null

    /** Admissions of Maya the server stored, served in her row from then on. */
    private val served = mutableListOf<MembershipStatements.Signed>()

    /** Whose keys the server lists for Maya. */
    private var mayaKeysOf = MAYA

    private var ben = false

    /** Where this account's late-joiner answers are kept. */
    private val answersPrefs = InMemoryPrefs()

    private fun TestScope.harness(selfRole: String = "owner") = TrustHarness(self = ME, selfRole = selfRole, pendingChanges = store).also { h ->
        h.repository.deliveryScope = this
        h.recordedCircle()
        h.circle = h.circle.copy(keyRotationNeeded = false, isOwner = selfRole == "owner")
        h.repository.catchUpPausesMs = emptyList()
        h.repository.lateJoinerAnswers = SecurePrefsLateJoinerAnswers(mockk(), { ME }, { answersPrefs })
        every { h.cryptoManager.chainRole("c-1", MAYA) } answers { mayaRole }
        every { h.cryptoManager.refreshEligibility("c-1", any(), any()) } answers {
            if (eThrows) throw uniffi.pingolin_mobile.MobileException.InvalidInput("import this circle's records first")
            arg<List<RecordMember>>(1).map { m ->
                val maya = m.userId == MAYA
                val eligible = !maya || herInvitationCounts || m.statements.any { admitsListedKeys(h, it) }
                MemberEligibility(
                    m.userId, eligible, null, maya && expired, maya && signerNotAdmin, maya && removed,
                    if (maya && removed) 3 else null, maya && addressOnly,
                )
            }
        }
        every { h.cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } answers {
            RefreshRecordsReport(emptyList(), null, false, forked, emptyList(), emptyList())
        }
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(
            ApiResponse(true, RefreshRecordsResponse(listOf(CircleRefreshRecords("c-1", emptyList(), complete = true)), more = false), null)
        )
        coEvery { h.tracksApi.storeMembershipStatement("c-1", MAYA, any()) } answers {
            val request = arg<StoreStatementRequest>(2)
            served += MembershipStatements.Signed(request.statement, request.statementSignature)
            Response.success(ApiResponse(true, StoreStatementResponse(true), null))
        }
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), any()) } answers {
            Response.success(ApiResponse(true, PaginatedResponse(items = roster(h), hasMore = false), null))
        }
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } answers {
            Response.success(ApiResponse(true, roster(h).map { h.key(it.userId, keyVersion = it.keyVersion, keysOf = keysOf(it.userId)) }, null))
        }
        h.rotationAccepted()
    }

    private fun keysOf(id: String) = if (id == MAYA) mayaKeysOf else id

    private fun roster(h: TrustHarness) = listOfNotNull(
        h.rosterMember(ME),
        h.rosterMember(BEN, role = "admin").takeIf { ben },
        // Left out of the circle's last key change: she holds the version before it.
        h.rosterMember(MAYA, invitation = mayaInvitation, vouches = served.toList(), keysOf = mayaKeysOf, keyVersion = 2),
    )

    /** What lets her in: an admission of Maya's listed keys signed with this account's current key. */
    private fun admitsListedKeys(h: TrustHarness, held: HeldStatement): Boolean {
        val st = h.statements.parse(held.statementHex) ?: return false
        return st.kind == Kind.ADMISSION && st.signerUserId == ME && st.subjectUserId == MAYA &&
            st.subjectSharingKeyHex == sharing(mayaKeysOf) && st.subjectSigningKeyHex == signing(mayaKeysOf) &&
            h.statements.verify(held.statementHex, held.signatureHex, signing(ME))
    }

    /** This device before its account's reset: the old key is the one in use. */
    private fun beforeTheReset(h: TrustHarness) {
        herInvitationCounts = true
        every { h.cryptoManager.signingPublicKeyHex() } returns OLD
        h.statements.selfSigningKey = OLD
    }

    /** The reset: new keys in use, and the old one restored here ([restored], the recovery key) or not. */
    private fun theReset(h: TrustHarness, restored: Boolean) {
        herInvitationCounts = false
        every { h.cryptoManager.signingPublicKeyHex() } returns signing(ME)
        h.statements.selfSigningKey = signing(ME)
        if (restored) h.statements.previousSigningKeys += OLD
    }

    /** Maya's invitation, by this account, under [key] (its old one by default), binding [keysOf]'s keys. */
    private fun invited(h: TrustHarness, key: String = OLD, by: String = ME, keysOf: String = MAYA) {
        mayaInvitation = h.invitation(MAYA, by = by, signerKey = key, id = INV, keysOf = keysOf)
    }

    private suspend fun TestScope.pass(h: TrustHarness, records: Boolean = true) {
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        if (!records) return
        h.repository.syncRefreshRecords()
        advanceUntilIdle()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
    }

    private fun asked(h: TrustHarness) =
        h.repository.keyAccessRequests.value.filter { it.userId == MAYA && it.reason == KeyAccessRequest.Reason.LATE_JOINER }

    private fun runner(h: TrustHarness) = PendingChangeRunner(
        dao, store, h.sessionManager, h.circleDao, h.repository, mockk(relaxed = true),
        PendingExecutors(
            mockk(), mockk(), mockk(), mockk(), mockk(), { h.repository }, mockk(), mockk(), h.sessionManager,
            { mockk<AuthRepository> { every { signingKeyMissing() } returns false } },
        ),
    )

    private fun later() {
        clock += 60 * 60_000L
    }

    /** What this device signed about Maya. */
    private fun signedForMaya(h: TrustHarness) = h.statements.signed.filter { h.statements.parse(it.statementHex)?.subjectUserId == MAYA }

    /** A fresh store after a reset with the recovery key: Maya's only basis is this account's old-key invitation. */
    private suspend fun TestScope.freshStore(selfRole: String = "owner"): TrustHarness {
        val h = harness(selfRole)
        theReset(h, restored = true)
        invited(h)
        pass(h)
        return h
    }

    /** A kept store: Maya approved here on her invitation before the reset, then the reset ([restored] or not). */
    private suspend fun TestScope.keptStore(restored: Boolean = false, beforeTheSecondPass: (TrustHarness) -> Unit = {}): TrustHarness {
        val h = harness()
        beforeTheReset(h)
        invited(h)
        pass(h)
        assertEquals("approved on her invitation", Origin.STATEMENT, h.origins()[MAYA])
        theReset(h, restored)
        beforeTheSecondPass(h)
        pass(h)
        return h
    }

    // ---- asked ------------------------------------------------------------------------------------

    @Test
    fun `a fresh store - her only basis the old-key invitation a restored key verifies - asked once, her listed keys, empty-bodied`() = runTest {
        val h = freshStore()

        val q = asked(h).single()
        assertEquals(LateJoiners.Case.INVITED_BEFORE_RESET, q.lateCase)
        assertEquals(sharing(MAYA) to signing(MAYA), q.sharingKeyHex to q.signingKeyHex)
        assertEquals(INV, q.invitation?.id)
        assertNull("the inviter is this account, an owner: no inviter line", q.inviterName)
        assertEquals("taken to the person once", 1, h.questions.count { it.startsWith("join c-1 Family $MAYA") })

        pass(h)
        assertEquals("pass after pass, the same one question", 1, asked(h).size)
        assertEquals(1, h.questions.count { it.startsWith("join c-1 Family $MAYA") })
    }

    @Test
    fun `a kept store - approved here on her invitation before the reset, which no key held verifies now - asked`() = runTest {
        val h = keptStore()

        assertEquals(LateJoiners.Case.INVITED_BEFORE_RESET, asked(h).single().lateCase)
    }

    @Test
    fun `a kept store with a later restore - the invitation is pre-reset, whatever key verifies it - asked`() = runTest {
        val h = keptStore(restored = true)

        assertEquals(LateJoiners.Case.INVITED_BEFORE_RESET, asked(h).single().lateCase)
    }

    @Test
    fun `a member the chain counts only as pending is asked too`() = runTest {
        mayaRole = CircleRole.MEMBER
        val h = freshStore()

        assertEquals(LateJoiners.Case.INVITED_BEFORE_RESET, asked(h).single().lateCase)
    }

    // ---- never ---------------------------------------------------------------------------------------------------

    @Test
    fun `the unbacked pointer - approved here with no record of her invitation, which no key verifies - not asked`() = runTest {
        val h = harness()
        theReset(h, restored = false)
        invited(h)
        h.approvals.approve("c-1", listOf(MAYA), Origin.STATEMENT)
        h.pins.pin(MAYA, sharing(MAYA), signing(MAYA))
        pass(h)

        assertEquals(Origin.STATEMENT, h.origins()[MAYA])
        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `any other approval - a seed from before signed invitations, It's them with no statement, a basis naming another's - not asked`() = runTest {
        val seeded = harness()
        theReset(seeded, restored = false)
        invited(seeded)
        seeded.earlierSeed(ME, MAYA)
        seeded.approvals.recordApprovalBasis("c-1", mapOf(MAYA to listOf(INV)))
        pass(seeded)
        assertEquals(Origin.PRE_ROUND2, seeded.origins()[MAYA])
        assertTrue("a seed is no basis", asked(seeded).isEmpty())

        val itsThem = harness()
        theReset(itsThem, restored = false)
        invited(itsThem)
        itsThem.approvals.approve("c-1", listOf(MAYA), Origin.OWN)
        itsThem.pins.pin(MAYA, sharing(MAYA), signing(MAYA))
        pass(itsThem)
        assertEquals(Origin.OWN, itsThem.origins()[MAYA])
        assertTrue("It's them names no statement", asked(itsThem).isEmpty())

        ben = true
        val another = harness()
        theReset(another, restored = false)
        another.pins.pin(BEN, sharing(BEN), signing(BEN))
        invited(another, key = signing("old-ben"), by = BEN)
        another.approvals.approve("c-1", listOf(MAYA, BEN), Origin.STATEMENT)
        another.approvals.recordApprovalBasis("c-1", mapOf(MAYA to listOf(INV)))
        another.pins.pin(MAYA, sharing(MAYA), signing(MAYA))
        pass(another)
        assertTrue("another signer's", asked(another).isEmpty())
    }

    @Test
    fun `never asked on an invitation the current key verifies - not from before the reset`() = runTest {
        val h = harness()
        theReset(h, restored = true)
        invited(h, key = signing(ME))
        pass(h)

        assertEquals(Origin.STATEMENT, h.origins()[MAYA])
        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `never asked for someone an owner or admin removed`() = runTest {
        removed = true
        val h = freshStore()

        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `never asked when the invitation binds other keys than the list shows - It's them decides`() = runTest {
        val h = keptStore { mayaKeysOf = "maya-new" }

        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `never asked on a forked chain`() = runTest {
        forked = true
        val h = freshStore()

        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `never asked before this process imported the circle's records`() = runTest {
        val h = harness()
        theReset(h, restored = true)
        invited(h)
        pass(h, records = false)

        assertTrue(asked(h).isEmpty())
    }

    @Test
    fun `never asked on a plain member's device`() = runTest {
        val h = freshStore(selfRole = "member")

        assertTrue(asked(h).isEmpty())
    }

    // ---- nothing without the yes; the yes ---------------------------------------------------------

    @Test
    fun `nothing is signed, chosen or handed over for her until the yes`() = runTest {
        val h = freshStore()
        val delivered = h.deliveriesAccepted()
        repeat(3) {
            later()
            pass(h)
        }

        assertEquals(1, asked(h).size)
        assertTrue("signed nothing for her", signedForMaya(h).isEmpty())
        assertTrue("sent nothing", served.isEmpty())
        assertNull("chose nothing", h.origins()[MAYA])
        assertFalse("handed her nothing", delivered.containsKey(MAYA))
    }

    @Test
    fun `the yes - nothing signed while the key in use isn't held, then an admission of her listed keys under the current key`() = runTest {
        val h = freshStore()
        val delivered = h.deliveriesAccepted()
        val q = asked(h).single()

        h.heldKeyVersion = 2
        h.repository.letIn(q)
        runner(h).runOnce()
        advanceUntilIdle()
        assertTrue("nothing signed while behind", signedForMaya(h).isEmpty())
        assertTrue(served.isEmpty())
        assertEquals("it waits", PendingChanges.WAITING, dao.changes.value.single().state)

        h.heldKeyVersion = 3
        later()
        runner(h).runOnce()
        advanceUntilIdle()

        val admission = served.single()
        val st = h.statements.parse(admission.statementHex)!!
        assertEquals(Kind.ADMISSION, st.kind)
        assertEquals(ME to MAYA, st.signerUserId to st.subjectUserId)
        assertEquals("her listed keys", sharing(MAYA) to signing(MAYA), st.subjectSharingKeyHex to st.subjectSigningKeyHex)
        assertTrue("under the current key", h.statements.verify(admission.statementHex, admission.signatureHex, signing(ME)))
        assertFalse("never the restored one", h.statements.verify(admission.statementHex, admission.signatureHex, OLD))
        assertEquals("this person's choice", Origin.OWN, h.origins()[MAYA])
        assertTrue("done", dao.changes.value.isEmpty())
        assertTrue("handed the circle", delivered.containsKey(MAYA))
        pass(h)
        assertTrue("no question once she is in", asked(h).isEmpty())
    }

    // ---- the basis record -------------------------------------------------------------------------

    @Test
    fun `the basis - written for an approval on a statement, with its id, and on every pass for one an earlier build made`() = runTest {
        val h = harness()
        beforeTheReset(h)
        invited(h)
        pass(h)
        assertEquals(mapOf(MAYA to setOf(INV)), h.approvals.approvalBasis("c-1"))

        // An approval an earlier build made: no basis until the next pass reads her invitation under
        // the current key.
        val earlier = harness()
        beforeTheReset(earlier)
        invited(earlier)
        earlier.approvals.approve("c-1", listOf(MAYA), Origin.STATEMENT)
        earlier.pins.pin(MAYA, sharing(MAYA), signing(MAYA))
        assertTrue(earlier.approvals.approvalBasis("c-1").isEmpty())
        earlier.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertEquals(mapOf(MAYA to setOf(INV)), earlier.approvals.approvalBasis("c-1"))

        // Never for a statement only a restored key verifies: one that would rest on the old identity.
        val restored = harness()
        theReset(restored, restored = true)
        invited(restored)
        pass(restored)
        assertTrue(restored.approvals.approvalBasis("c-1").isEmpty())
    }

    // ---- "It's them" for a member the chain doesn't count -----------------------------------------

    /** Maya reset her password: her keys are new, this device holds her old ones; her invitation binds the old. */
    private suspend fun TestScope.mayaReset(selfRole: String = "owner"): TrustHarness {
        val h = harness(selfRole)
        herInvitationCounts = true
        invited(h, key = signing(ME))
        pass(h)
        assertEquals(Origin.STATEMENT, h.origins()[MAYA])
        herInvitationCounts = false
        mayaKeysOf = "maya-new"
        pass(h)
        return h
    }

    private fun itsThem(receiveOnly: Boolean = false) = MemberKeyAlert(
        circleId = "c-1", userId = MAYA, displayName = "Maya", state = PinState.CHANGED_BOTH, previousPinnedAt = 1L,
        newSharingHex = sharing("maya-new"), newSigningHex = signing("maya-new"), receiveOnly = receiveOnly,
    )

    @Test
    fun `an owner's It's them for a member the chain doesn't count - an admission of exactly the shown keys, current key`() = runTest {
        val h = mayaReset()
        assertTrue("nothing before the yes", signedForMaya(h).isEmpty())

        h.repository.approveMemberKeyChange(itsThem())
        advanceUntilIdle()
        assertEquals(
            PendingInputs.LetIn("Family", MAYA, "Maya", KeyAccessRequest.Reason.KEY_CHANGE.name, sharing("maya-new"), signing("maya-new"), tapVersion = 3),
            PendingInputs.read(dao.changes.value.single()),
        )
        runner(h).runOnce()
        advanceUntilIdle()

        val admission = served.single()
        val st = h.statements.parse(admission.statementHex)!!
        assertEquals(Kind.ADMISSION, st.kind)
        assertEquals("the shown keys", sharing("maya-new") to signing("maya-new"), st.subjectSharingKeyHex to st.subjectSigningKeyHex)
        assertTrue("under the current key", h.statements.verify(admission.statementHex, admission.signatureHex, signing(ME)))
        assertTrue("done", dao.changes.value.isEmpty())
    }

    @Test
    fun `an owner's It's them signs only the shown keys - listed keys that changed again before it ran sign nothing`() = runTest {
        val h = mayaReset()
        h.repository.approveMemberKeyChange(itsThem())
        advanceUntilIdle()
        mayaKeysOf = "maya-newer"

        runner(h).runOnce()
        advanceUntilIdle()

        assertTrue(signedForMaya(h).isEmpty())
        assertTrue(served.isEmpty())
    }

    @Test
    fun `an owner's It's them - never for a member the chain counts, from a plain member's device, or an email invitee's It's them`() = runTest {
        mayaRole = CircleRole.MEMBER
        val counted = mayaReset()
        counted.repository.approveMemberKeyChange(itsThem())
        advanceUntilIdle()
        assertTrue("counted: E takes them on the person's word", dao.changes.value.isEmpty())

        mayaRole = null
        mayaKeysOf = MAYA
        val plain = mayaReset(selfRole = "member")
        plain.repository.approveMemberKeyChange(itsThem())
        advanceUntilIdle()
        assertTrue("a plain member's It's them stays as it was", dao.changes.value.isEmpty())

        mayaKeysOf = MAYA
        val invitee = mayaReset()
        invitee.repository.approveMemberKeyChange(itsThem(receiveOnly = true))
        advanceUntilIdle()
        assertTrue("an email invitee's: their own question admits them", dao.changes.value.isEmpty())
        assertTrue(signedForMaya(invitee).isEmpty())
    }

    @Test
    fun `an owner's It's them - only the stuck state - E lets her in, raises a flag, she was removed, or E can't be read - nothing is kept`() = runTest {
        val cases = listOf<Pair<String, suspend TestScope.(TrustHarness) -> Unit>>(
            "E lets her in" to { herInvitationCounts = true },
            "her invitation ran out" to { expired = true },
            "its signer isn't a chain owner or admin" to { signerNotAdmin = true },
            "an address invitation" to { addressOnly = true },
            "an owner or admin removed her" to { removed = true },
            "the chain forked" to { h -> forked = true; pass(h) },
            "E can't be read" to { eThrows = true },
        )
        for ((why, condition) in cases) {
            herInvitationCounts = false; expired = false; signerNotAdmin = false; addressOnly = false
            removed = false; forked = false; eThrows = false; mayaKeysOf = MAYA
            val h = mayaReset()
            condition(h)

            h.repository.approveMemberKeyChange(itsThem())
            advanceUntilIdle()

            assertTrue("$why: nothing kept", dao.changes.value.isEmpty())
            assertTrue("$why: nothing signed", signedForMaya(h).isEmpty())
            assertEquals("$why: It's them itself still recorded", Origin.OWN, h.origins()[MAYA])
        }
    }

    @Test
    fun `an owner's kept It's them re-checks the stuck state with the confirmed keys - let in, counted, flagged or removed meanwhile - signs nothing`() = runTest {
        val cases = listOf<Pair<String, () -> Unit>>(
            "E lets her in meanwhile" to { herInvitationCounts = true },
            "the chain counts her now" to { mayaRole = CircleRole.MEMBER },
            "her invitation ran out meanwhile" to { expired = true },
            "an owner or admin removed her meanwhile" to { removed = true },
        )
        for ((why, meanwhile) in cases) {
            herInvitationCounts = false; expired = false; removed = false; mayaRole = null; mayaKeysOf = MAYA
            val h = mayaReset()
            h.repository.approveMemberKeyChange(itsThem())
            advanceUntilIdle()
            assertEquals("$why: kept", 1, dao.changes.value.size)

            meanwhile()
            later()
            runner(h).runOnce()
            advanceUntilIdle()

            assertTrue("$why: nothing signed", signedForMaya(h).isEmpty())
            assertTrue("$why: nothing sent", served.isEmpty())
            assertTrue("$why: the change ended", dao.changes.value.isEmpty())
        }
    }
}
