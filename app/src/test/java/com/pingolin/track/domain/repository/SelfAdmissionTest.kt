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
import com.pingolin.track.domain.repository.MembershipStatements.Kind
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
import uniffi.pingolin_mobile.EligibilityPurpose
import uniffi.pingolin_mobile.MemberEligibility
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMember
import uniffi.pingolin_mobile.RecordMemberState
import uniffi.pingolin_mobile.RefreshRecordKind
import uniffi.pingolin_mobile.RefreshRecordsReport

/**
 * After a password reset the chain still commits this account's old keys, so the SDK's
 * eligibility check doesn't count its own row: as an owner or admin it publishes its own
 * admission of its current keys, its key changes wait until the server serves that
 * admission, and receive-only lifts exactly where the check counts its own row.
 *
 * The SDK is mocked. Its eligibility check counts this account's own row only with an
 * admission of its own, by itself, served in its row (what the SDK does once the device's
 * own keys are the ones it passes); every other member as this device confirms them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelfAdmissionTest {

    private companion object {
        // Account ids are UUIDs: "is this me?" compares their canonical forms (AccountIds).
        const val ME = "aaaaaaaa-0000-4000-8000-00000000000a"
        const val ALICE = "bbbbbbbb-0000-4000-8000-00000000000b"
    }

    @After
    fun tearDown() = clearAllMocks()

    private var clock = 1_000_000L

    /** Every own admission the server was sent. */
    private val sent = mutableListOf<StoreStatementRequest>()

    /** The server's answer to the next upload; a 200 serves it in this account's row (unless [storesWithoutServing]). */
    private var reply = 200
    private var storesWithoutServing = false

    /** What the server serves in this account's own row. */
    private val served = mutableListOf<MembershipStatements.Signed>()

    private var eligibilityThrows = false
    private var forked = false

    private fun TestScope.harness(
        selfRole: String = "owner",
        /** This account was invited (by alice, the owner) with its OLD keys: receive-only by its statements after the reset. */
        invitedWithOldKeys: Boolean = false,
        /** The keys the server lists for this account (its current ones unless a test says otherwise). */
        listedAs: String = ME,
        /** The keys the server lists for alice: her new ones after her own reset. */
        aliceKeysOf: String = ALICE,
        recorded: Boolean = true,
    ) = TrustHarness(self = ME, selfRole = selfRole).also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed(ME, ALICE)
        if (recorded) h.recordedCircle()
        h.circle = h.circle.copy(keyRotationNeeded = false, isOwner = selfRole == "owner")
        h.repository.selfAdmissionClock = { clock }
        h.repository.catchUpPausesMs = emptyList()
        every { h.cryptoManager.refreshEligibility("c-1", any(), any()) } answers {
            // The own row is read for INCLUDE; a DELIVER read still answers, so a delivery shows what receive-only decided.
            if (eligibilityThrows && arg<EligibilityPurpose>(2) == EligibilityPurpose.INCLUDE) {
                throw MobileException.InvalidInput("import this circle's records first")
            }
            arg<List<RecordMember>>(1).map { m ->
                val eligible = if (m.userId == ME) m.statements.any { ownAdmission(h, it.statementHex) } else m.confirmed
                MemberEligibility(m.userId, eligible, null, false, false, false, null, false)
            }
        }
        every { h.cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } answers {
            RefreshRecordsReport(emptyList(), null, false, forked, emptyList(), emptyList())
        }
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(
            ApiResponse(true, RefreshRecordsResponse(listOf(CircleRefreshRecords("c-1", emptyList(), complete = true)), more = false), null)
        )
        coEvery { h.tracksApi.storeMembershipStatement("c-1", ME, any()) } answers {
            val request = arg<StoreStatementRequest>(2)
            sent += request
            if (reply == 200) {
                if (!storesWithoutServing) served += MembershipStatements.Signed(request.statement, request.statementSignature)
                Response.success(ApiResponse(true, StoreStatementResponse(true), null))
            } else {
                Response.error(reply, "{}".toResponseBody())
            }
        }
        val invite = if (invitedWithOldKeys) h.invitation(ME, by = ALICE, keysOf = "old-me") else null
        val aliceRole = if (selfRole == "owner") "member" else "owner"
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), any()) } answers {
            Response.success(
                ApiResponse(
                    true,
                    PaginatedResponse(
                        items = listOf(
                            h.rosterMember(ME, invitation = invite, vouches = served.toList(), keysOf = listedAs),
                            h.rosterMember(ALICE, role = aliceRole, keysOf = aliceKeysOf, keyVersion = aliceKeyVersion),
                        ),
                        hasMore = false,
                    ),
                    null,
                )
            )
        }
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } answers {
            Response.success(
                ApiResponse(
                    true,
                    listOf(h.key(ME, keysOf = listedAs), h.key(ALICE, keyVersion = aliceKeyVersion, keysOf = aliceKeysOf)),
                    null,
                )
            )
        }
        h.rotationAccepted()
    }

    private var aliceKeyVersion: Int? = 3

    /** An admission whose signer and subject are both this account. */
    private fun ownAdmission(h: TrustHarness, statementHex: String) =
        h.statements.parse(statementHex)?.let { it.kind == Kind.ADMISSION && it.signerUserId == ME && it.subjectUserId == ME } == true

    /** A records check, as every key import runs one, and whatever it sets going. */
    private suspend fun TestScope.recordsChecked(h: TrustHarness) {
        h.repository.syncRefreshRecords()
        advanceUntilIdle()
    }

    private suspend fun TestScope.listRead(h: TrustHarness) {
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
    }

    private suspend fun TestScope.rotated(h: TrustHarness) {
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
    }

    private fun minutes(n: Long) {
        clock += n * 60_000L
    }

    // ---- the emitter ------------------------------------------------------------------------------

    @Test
    fun `due - one admission of this account's current keys, signed by it, then the member list again`() = runTest {
        val h = harness()
        listRead(h)
        recordsChecked(h)

        assertEquals(1, sent.size)
        val st = h.statements.parse(sent.single().statement)!!
        assertEquals(Kind.ADMISSION, st.kind)
        assertEquals(ME, st.signerUserId)
        assertEquals(ME, st.subjectUserId)
        assertEquals(TrustHarness.sharing(ME) to TrustHarness.signing(ME), st.subjectSharingKeyHex to st.subjectSigningKeyHex)
        assertTrue("signed with the current key", h.statements.verify(sent.single().statement, sent.single().statementSignature, TrustHarness.signing(ME)))
        // Into its own row only as served: the member list read again after the 200.
        coVerify(atLeast = 2) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        assertEquals(1, served.size)

        // Served now, the eligibility check counts the own row: nothing more,
        // however much later.
        minutes(60)
        recordsChecked(h)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a 200 ends it for that key version, even before the member list serves it`() = runTest {
        val h = harness()
        storesWithoutServing = true
        listRead(h)
        recordsChecked(h)
        assertEquals(1, sent.size)

        minutes(11)
        recordsChecked(h)
        assertEquals("one per circle per key version", 1, sent.size)
    }

    @Test
    fun `not due - its own row counted, or a plain member - nothing is sent`() = runTest {
        val counted = harness()
        served += counted.statements.statement(Kind.ADMISSION, "c-1", ME, TrustHarness.signing(ME), ME, TrustHarness.sharing(ME), TrustHarness.signing(ME))
        listRead(counted)
        recordsChecked(counted)
        assertTrue(sent.isEmpty())

        served.clear()
        val plain = harness(selfRole = "member")
        listRead(plain)
        recordsChecked(plain)
        assertTrue("a plain member never sends one", sent.isEmpty())
    }

    @Test
    fun `a 400 leaves nothing in its own row and is tried again once ten minutes have passed`() = runTest {
        val h = harness()
        reply = 400
        listRead(h)
        recordsChecked(h)
        assertEquals(1, sent.size)
        assertTrue("not in its own row", served.isEmpty())

        minutes(5)
        recordsChecked(h)
        assertEquals("no attempt inside the 10 minutes", 1, sent.size)

        minutes(6)
        reply = 200
        recordsChecked(h)
        assertEquals(2, sent.size)
        assertEquals("served", 1, served.size)
        // and the check counts its own row: nothing more
        minutes(30)
        recordsChecked(h)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a 403 is not retried at that key version and listed role, but at the next version it is`() = runTest {
        val h = harness()
        reply = 403
        listRead(h)
        recordsChecked(h)
        assertEquals(1, sent.size)

        minutes(30)
        recordsChecked(h)
        assertEquals("a 403 ends it for v3 as the owner", 1, sent.size)

        // A later key change names this account again (at v4) before its keys count.
        h.circleVersion = 4
        h.heldKeyVersion = 4
        reply = 200
        recordsChecked(h)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a later naming record makes it due again at the next version, and it goes again`() = runTest {
        val h = harness()
        listRead(h)
        recordsChecked(h)
        assertEquals(1, sent.size)

        // v4 names this account with its old keys again: the admission served counts no more.
        served.clear()
        h.circleVersion = 4
        h.heldKeyVersion = 4
        minutes(11)
        listRead(h)
        recordsChecked(h)
        assertEquals(2, sent.size)
    }

    @Test
    fun `the keys it admits are this device's own, whatever the server lists for it`() = runTest {
        val h = harness(listedAs = "server-me")
        listRead(h)
        recordsChecked(h)

        val st = h.statements.parse(sent.single().statement)!!
        assertEquals(TrustHarness.sharing(ME) to TrustHarness.signing(ME), st.subjectSharingKeyHex to st.subjectSigningKeyHex)
    }

    @Test
    fun `behind on the key, it signs nothing and starts no retry clock`() = runTest {
        val h = harness()
        h.heldKeyVersion = 2
        listRead(h)
        recordsChecked(h)
        assertTrue(sent.isEmpty())
        assertTrue(h.statements.signed.isEmpty())

        // Caught up: at once, no clock to wait out.
        h.heldKeyVersion = 3
        recordsChecked(h)
        assertEquals(1, sent.size)
    }

    // ---- key changes wait --------------------------------------------------------------------------

    @Test
    fun `while due, key changes build nothing and set no back-off - then they go at once`() = runTest {
        val h = harness()
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } returns true
        reply = 500
        listRead(h)
        recordsChecked(h)
        assertTrue(served.isEmpty())

        rotated(h)
        rotated(h)
        verify(exactly = 0) { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        assertTrue(h.repository.ejectMember("c-1", ALICE).exceptionOrNull() is KeyNotCurrentException)
        verify(exactly = 0) { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) }

        // Served by another way (an earlier upload the server took late): the list says so, and the step goes now.
        served += h.statements.statement(Kind.ADMISSION, "c-1", ME, TrustHarness.signing(ME), ME, TrustHarness.sharing(ME), TrustHarness.signing(ME))
        listRead(h)
        rotated(h)
        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
    }

    @Test
    fun `a fresh store after a restore, its re-key due, signs and uploads, then the re-key builds`() = runTest {
        val h = harness()
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } returns true
        listRead(h)
        assertFalse("the re-key holds its seals back", h.repository.holdsCurrentKey("c-1"))

        recordsChecked(h)
        assertEquals("not held back by the re-key it is for", 1, sent.size)

        assertTrue(h.repository.rotationDue(h.circle))
        rotated(h)
        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
    }

    // ---- an eligibility read that fails, or a forked chain, is not known ------------------------------

    @Test
    fun `the eligibility check not readable - nothing sent, no retry clock, key changes still wait - then one emission`() = runTest {
        val h = harness()
        eligibilityThrows = true
        // The builder's own "not yet" while the records aren't imported.
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("import this circle's records first, then rotate")
        every { h.cryptoManager.activeKeyCameViaPreviousIdentity("c-1") } returns true
        listRead(h)
        recordsChecked(h)
        rotated(h)
        assertTrue(sent.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }

        // The records imported: due, and sent at once (no clock was started).
        eligibilityThrows = false
        recordsChecked(h)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a forked import sends nothing - then one emission once it isn't forked`() = runTest {
        val h = harness()
        forked = true
        listRead(h)
        recordsChecked(h)
        assertTrue(sent.isEmpty())

        forked = false
        recordsChecked(h)
        assertEquals(1, sent.size)
    }

    // ---- receive-only lifted exactly where the eligibility check counts this account's own row -------

    @Test
    fun `an admin invited with its old keys, its own admission served, rotates and hands over`() = runTest {
        aliceKeyVersion = 2 // owed the current copy
        val h = harness(selfRole = "admin", invitedWithOldKeys = true)
        h.circle = h.circle.copy(keyRotationNeeded = true)
        val delivered = h.deliveriesAccepted()
        listRead(h)
        recordsChecked(h)
        assertEquals(1, served.size)

        assertEquals(1, h.repository.deliverOwedAccess("c-1"))
        assertTrue(ALICE in delivered)
        rotated(h)
        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
    }

    @Test
    fun `receive-only stays - a plain member, an admission not stored, or E not known`() = runTest {
        aliceKeyVersion = 2
        for (case in listOf("plain", "not stored", "unknown")) {
            sent.clear(); served.clear()
            reply = if (case == "not stored") 500 else 200
            eligibilityThrows = case == "unknown"
            val h = harness(selfRole = if (case == "plain") "member" else "admin", invitedWithOldKeys = true)
            h.circle = h.circle.copy(keyRotationNeeded = true)
            val delivered = h.deliveriesAccepted()
            listRead(h)
            recordsChecked(h)

            assertEquals(case, 0, h.repository.deliverOwedAccess("c-1"))
            assertTrue(case, delivered.isEmpty())
            rotated(h)
            coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
            clearAllMocks()
        }
    }

    // ---- a flagged rotation after a member's reset -------------------------------------------------

    @Test
    fun `recorded - the reset member is neither included nor wrapped until It's them, then only to the new keys`() = runTest {
        val h = harness(aliceKeysOf = "alice2")
        // This account's own row counted by its chain here: nothing of its own is due.
        every { h.cryptoManager.refreshEligibility("c-1", any(), any()) } answers {
            arg<List<RecordMember>>(1).map { MemberEligibility(it.userId, it.confirmed, null, false, false, false, null, false) }
        }
        h.circle = h.circle.copy(keyRotationNeeded = true)
        listRead(h)

        rotated(h)
        val planned = mutableListOf<List<PlannedEntry>>()
        val rows = mutableListOf<List<RecordMember>>()
        verify { h.cryptoManager.prepareRecordedRotation("c-1", ME, any(), capture(planned), capture(rows), any(), any()) }
        assertEquals(RecordMemberState.PENDING, planned.last().single { it.userId == ALICE }.state)
        coVerify { h.tracksApi.rotateCircleKeys("c-1", match { request -> request.memberKeys.none { it.userId == ALICE } }) }

        // "It's them" for her new keys; the rotation has landed and an import judged it.
        h.repository.acceptMemberKeys("c-1", ALICE, "Alice", TrustHarness.sharing("alice2") to TrustHarness.signing("alice2"))
        h.circleVersion = 4
        h.heldKeyVersion = 4
        recordsChecked(h)
        listRead(h)
        rotated(h)

        verify(atLeast = 2) { h.cryptoManager.prepareRecordedRotation("c-1", ME, any(), capture(planned), capture(rows), any(), any()) }
        assertEquals(RecordMemberState.INCLUDED, planned.last().single { it.userId == ALICE }.state)
        val aliceRow = rows.last().single { it.userId == ALICE }
        assertEquals(TrustHarness.sharing("alice2") to TrustHarness.signing("alice2"), aliceRow.sharingPublicKeyHex to aliceRow.signingPublicKeyHex)
        coVerify { h.tracksApi.rotateCircleKeys("c-1", match { request -> request.memberKeys.any { it.userId == ALICE } }) }
    }

    @Test
    fun `record-less - the reset member is neither wrapped until It's them, then only to the new keys`() = runTest {
        val h = harness(aliceKeysOf = "alice2", recorded = false)
        // The builder refuses this circle's first record here: the rotation goes without one (legacyRotation).
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), RefreshRecordKind.TRANSITION, any(), any(), any(), any()) } throws
            MobileException.InvalidInput("refused")
        h.circle = h.circle.copy(keyRotationNeeded = true)
        listRead(h)

        rotated(h)
        val ids = mutableListOf<List<String>>()
        val keys = mutableListOf<List<String>>()
        verify { h.cryptoManager.prepareKeyRotation("c-1", ME, capture(ids), capture(keys), any(), any()) }
        assertEquals(listOf(ME), ids.last())
        assertFalse(keys.last().contains(TrustHarness.sharing(ALICE)) || keys.last().contains(TrustHarness.sharing("alice2")))

        h.repository.acceptMemberKeys("c-1", ALICE, "Alice", TrustHarness.sharing("alice2") to TrustHarness.signing("alice2"))
        listRead(h)
        rotated(h)

        verify(atLeast = 2) { h.cryptoManager.prepareKeyRotation("c-1", ME, capture(ids), capture(keys), any(), any()) }
        val wrappedTo = ids.last().zip(keys.last()).toMap()
        assertEquals(TrustHarness.sharing("alice2"), wrappedTo[ALICE])
        assertFalse("never her old key", keys.flatten().contains(TrustHarness.sharing(ALICE)))
    }
}
