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
import com.pingolin.track.data.remote.dto.HeldStatement
import com.pingolin.track.data.remote.dto.StatementsHeldResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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
import uniffi.pingolin_mobile.EligibilityPurpose
import uniffi.pingolin_mobile.MemberEligibility
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.RecordMember

/**
 * Signatures no one decides now: the admission backfill signs, on a circle with refresh
 * records, only as an owner or admin in the chain and only for a member the chain counts, each
 * id saved before its request and looked for before signing again, at most 20 per member; a
 * waiting change for someone the records removed after the person decided ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignaturesNoOneDecidesTest {

    /** Accounts the invite lookup names are UUIDs (AccountIds.strict). */
    private val dan = "da400000-0000-4000-8000-0000000000d4"

    @After
    fun tearDown() = clearAllMocks()

    /** An owner's device; alice and bob are of its seed, confirmed by nothing others can check. */
    private fun TestScope.harness() = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice", "bob")
        h.recordedCircle()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob")))
        h.deliveriesAccepted()
    }

    @Test
    fun `the backfill vouches as the chain's owner, only for a member the chain counts, each id saved first`() = runTest {
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "bob") } returns null
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertEquals(setOf("alice"), admitted.keys)
        val id = h.statements.parse(admitted.getValue("alice").statement)!!.statementId
        assertEquals("saved before its request went out", setOf(id), h.approvals.backfillIds("c-1", "alice"))
        assertEquals(setOf("alice"), h.approvals.backfilled("c-1"))
    }

    @Test
    fun `a member the chain stops counting while the pass goes on is stopped at the signature - nothing saved or sent`() = runTest {
        // Checked again at the signature: alice is counted when the pass looks, and an
        // eject of her is accepted by the time her admission is signed.
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "bob") } returns null
        every { h.cryptoManager.chainRole("c-1", "alice") } answers {
            if (h.statements.signed.any { it.statementHex.contains("|alice|") }) null else CircleRole.MEMBER
        }
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertEquals("signed once", 1, h.statements.signed.size)
        assertTrue("never sent", admitted.isEmpty())
        assertTrue("its id never saved", h.approvals.backfillIds("c-1", "alice").isEmpty())
        assertFalse("alice" in h.approvals.backfilled("c-1"))
    }

    @Test
    fun `a device that isn't an owner or admin in the chain vouches for nobody`() = runTest {
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.MEMBER
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(admitted.isEmpty())
    }

    @Test
    fun `an admission already held is found by looking, and never signed again`() = runTest {
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "bob") } returns null
        h.approvals.addBackfillId("c-1", "alice", "st-earlier")
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } returns
            Response.success(ApiResponse(true, StatementsHeldResponse(listOf(HeldStatement("stearlier", "vouch"))), null))
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(admitted.isEmpty())
        assertEquals(setOf("alice"), h.approvals.backfilled("c-1"))
    }

    @Test
    fun `no answer to the look signs nothing, and twenty unheld admissions are the last`() = runTest {
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "bob") } returns null
        h.approvals.addBackfillId("c-1", "alice", "st-earlier")
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } returns Response.error(503, okhttp3.ResponseBody.Companion.run { "{}".toResponseBody(null) })
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertTrue("couldn't look: next pass", admitted.isEmpty())

        repeat(19) { h.approvals.addBackfillId("c-1", "alice", "st-$it") }
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } returns
            Response.success(ApiResponse(true, StatementsHeldResponse(emptyList()), null))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertTrue("20 already, none held: it stops", admitted.isEmpty())
        assertFalse("alice" in h.approvals.backfilled("c-1"))
    }

    private fun TrustHarness.removedAt(version: Int?) {
        every { cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            arg<List<RecordMember>>(1).map { MemberEligibility(it.userId, false, null, false, false, version != null, version, false) }
        }
    }

    @Test
    fun `a waiting invitation or admission whose member is removed while it runs is stopped at the signature - nothing saved or sent`() = runTest {
        // The removal is asked again once the statement's version is settled, before its
        // id is saved and its request leaves.
        val h = harness()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.success(
            ApiResponse(true, com.pingolin.track.data.remote.dto.UserPublicKeyResponse("dan@x.test", TrustHarness.sharing(dan), dan, TrustHarness.signing(dan)), null)
        )
        // Not removed at the run's first look; an eject at v4 accepted by the time the statement is signed.
        var looks = 0
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            val removedAt = if (looks++ == 0) null else 4
            arg<List<RecordMember>>(1).map { MemberEligibility(it.userId, false, null, false, false, removedAt != null, removedAt, false) }
        }
        val saved = mutableListOf<com.pingolin.track.domain.pending.SignedStatement>()

        val invited = h.repository.inviteToCircle(
            "c-1", "dan@x.test",
            pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { saved += it }, tapVersion = 3),
        )

        assertTrue(invited.exceptionOrNull() is com.pingolin.track.domain.pending.RemovedSinceDecided)
        assertEquals("asked after the lookup, and again at the signature", 2, looks)
        assertTrue("its id never saved", saved.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }

        // An admission: the run's check at the signature stops it before its id is saved.
        val admitted = h.statementsAccepted()
        val promoted = h.repository.updateMemberRole(
            "c-1", "alice", "admin", decidedKeys = TrustHarness.sharing("alice") to TrustHarness.signing("alice"),
            pendingRun = com.pingolin.track.domain.pending.PendingRun(
                "ch-2", "ch-2", onSigned = { saved += it }, tapVersion = 3,
                beforeSend = { throw com.pingolin.track.domain.pending.RemovedSinceDecided() },
            ),
        )

        assertTrue(promoted.exceptionOrNull() is com.pingolin.track.domain.pending.RemovedSinceDecided)
        assertTrue(saved.isEmpty())
        assertTrue(admitted.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
    }

    @Test
    fun `removed after the person decided is a removal at a version above the one decided at`() = runTest {
        val h = harness()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        h.removedAt(4)
        assertEquals(true, h.repository.removedSinceDecided("c-1", "bob", tapVersion = 3))
        assertEquals("decided after it", false, h.repository.removedSinceDecided("c-1", "bob", tapVersion = 4))
        assertEquals("the version decided at unknown: any removal counts", true, h.repository.removedSinceDecided("c-1", "bob", tapVersion = null))
        h.removedAt(null)
        assertEquals(false, h.repository.removedSinceDecided("c-1", "bob", tapVersion = 3))

        // Someone off the list (an invitee) is asked about too, as not listed.
        h.removedAt(5)
        assertEquals(true, h.repository.removedSinceDecided("c-1", "zed", tapVersion = 3, keys = "zed-x" to "zed-s"))

        // Can't be told: null, and the change waits.
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } throws MobileException.InvalidInput("no records")
        assertNull(h.repository.removedSinceDecided("c-1", "bob", tapVersion = 3))

        // A circle without records: never removed since
        // the person decided.
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false
        assertEquals(false, h.repository.removedSinceDecided("c-1", "bob", tapVersion = 3))
    }
}
