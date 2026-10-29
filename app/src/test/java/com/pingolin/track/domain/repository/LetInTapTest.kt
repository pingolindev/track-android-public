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
import com.pingolin.track.data.remote.dto.StatementsHeldResponse
import com.pingolin.track.domain.pending.PendingChangeRunner
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingExecutors
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingLines
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.testutil.InMemoryPendingChangeDao
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
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The person's "Let them in" tap, end to end, in the same order for the live answer as for a
 * waiting one. The tap is kept as a waiting change, as iOS keeps every tap, and the real runner,
 * executors and repository take it from there: the admission, then the choice, read back, then
 * the hand-over. A refusal or no answer leaves no choice and nothing handed over; a tap is never
 * dismissed with nothing done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LetInTapTest {

    private var clock = 1_800_000_000_000L
    private val dao = InMemoryPendingChangeDao()
    private val store = pendingChangesIn(dao, now = { clock })
    private val h = TrustHarness(pendingChanges = store)
    private val executors = PendingExecutors(
        mockk(), mockk(), mockk(), mockk(), mockk(), { h.repository }, mockk(), mockk(), h.sessionManager,
        { mockk<AuthRepository> { every { signingKeyMissing() } returns false } },
    )
    private val runner = PendingChangeRunner(dao, store, h.sessionManager, h.circleDao, h.repository, mockk(relaxed = true), executors)

    @After
    fun tearDown() = clearAllMocks()

    /** The owner is asked about a keyless newcomer invited by mallory, whom this phone can't confirm. */
    private suspend fun TestScope.newcomerAsked(): KeyAccessRequest {
        h.earlierSeed("me", "alice")
        h.repository.deliveryScope = this
        h.circle = h.circle.copy(keyRotationNeeded = false)
        serveRoster()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        return h.repository.keyAccessRequests.value.single()
    }

    private fun serveRoster() = h.serverLists(
        roster = listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("mallory"),
            h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null))
    )

    /** What the statements-held look answers: the ids of [ids]
     * the server holds.
     */
    private fun serverHolds(vararg ids: String) {
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } returns Response.success(
            ApiResponse(true, StatementsHeldResponse(ids.map { com.pingolin.track.data.remote.dto.HeldStatement(PendingChanges.hexId(it)) }), null)
        )
    }

    /** Past the change's backoff and the 2 minutes since its statement went out. */
    private fun later() {
        clock += 60 * 60_000L
    }

    @Test
    fun `a tap while this phone is current is kept as a let-in and runs now - the admission, then the choice, then the hand-over`() = runTest {
        val request = newcomerAsked()
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        val signedBefore = h.statements.signed.size

        val tapped = h.repository.letIn(request)

        assertTrue("quiet: it goes now", tapped.isSuccess)
        assertTrue("the question is answered", h.repository.keyAccessRequests.value.isEmpty())
        assertEquals(
            PendingInputs.LetIn("Family", "newcomer", "Newcomer", "UNCONFIRMED_INVITER", sharing("newcomer"), signing("newcomer"), tapVersion = 3),
            PendingInputs.read(dao.changes.value.single()),
        )
        assertEquals("the tap signs nothing", signedBefore, h.statements.signed.size)
        assertEquals("nor chooses", null, h.origins()["newcomer"])
        assertEquals("nor pins", null, h.pins.sharing["newcomer"])

        runner.runOnce()
        advanceUntilIdle()

        assertTrue("done", dao.changes.value.isEmpty())
        assertTrue(dao.notices.value.isEmpty())
        assertTrue("admitted", "newcomer" in admitted)
        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue("handed the circle", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a tap whose admission is refused (403) - no choice, no hand-over, and the person is told why`() = runTest {
        val request = newcomerAsked()
        val delivered = h.deliveriesAccepted()
        coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } returns
            Response.error(403, """{"success":false,"error_code":"insufficient_permissions"}""".toResponseBody(null))
        h.repository.letIn(request)

        runner.runOnce()
        advanceUntilIdle()

        // A 403 at send decides nothing by itself: the complete roster does, at the next run.
        assertEquals(PendingChanges.WAITING, dao.changes.value.single().state)
        assertEquals(null, h.origins()["newcomer"])
        assertFalse(delivered.containsKey("newcomer"))

        // This user is no longer an owner or admin: said so, and it ends with nothing chosen.
        h.role = "member"
        serveRoster()
        serverHolds()
        later()
        runner.runOnce()
        advanceUntilIdle()

        assertTrue(dao.changes.value.isEmpty())
        assertEquals(
            PendingLines.notOwner(PendingInputs.letInSubject("Newcomer"), "Family"),
            dao.notices.value.single().line,
        )
        assertEquals("no choice", null, h.origins()["newcomer"])
        assertFalse("nothing handed over", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a tap whose admission gets no answer - no choice, no hand-over, it waits, and once it is found held the choice follows`() = runTest {
        val request = newcomerAsked()
        val delivered = h.deliveriesAccepted()
        coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } throws java.net.SocketTimeoutException()
        h.repository.letIn(request)

        runner.runOnce()
        advanceUntilIdle()

        val waiting = dao.changes.value.single()
        assertEquals(PendingChanges.WAITING, waiting.state)
        assertEquals(PendingChanges.LOST_ANSWER, waiting.lastOutcome)
        assertEquals(null, h.origins()["newcomer"])
        assertFalse(delivered.containsKey("newcomer"))
        assertTrue(dao.notices.value.isEmpty())

        // The admission had landed: the statements-held look finds it, and only the
        // choice is left.
        serverHolds(PendingChanges.signedIds(waiting).single())
        later()
        runner.runOnce()
        advanceUntilIdle()

        assertTrue(dao.changes.value.isEmpty())
        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue(delivered.containsKey("newcomer"))
    }

    /** The tap's admission reaches the server but isn't stored: a 503, so the change waits. */
    private suspend fun TestScope.tappedAdmissionNotStored(): Pair<String, MutableMap<String, com.pingolin.track.data.remote.dto.DeliverAccessRequest>> {
        val request = newcomerAsked()
        val delivered = h.deliveriesAccepted()
        coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } returns Response.error(503, "{}".toResponseBody(null))
        h.repository.letIn(request)
        runner.runOnce()
        advanceUntilIdle()
        serverHolds()
        return dao.changes.value.single().id to delivered
    }

    @Test
    fun `a tap whose admission isn't stored, then Cancel - no choice, no hand-over, no line`() = runTest {
        val (id, delivered) = tappedAdmissionNotStored()

        assertEquals(PendingChanges.Cancelled.CANCELLING, store.cancel(id))
        later()
        runner.runOnce()
        advanceUntilIdle()

        assertTrue("cancelled", dao.changes.value.isEmpty())
        assertTrue(dao.notices.value.isEmpty())
        assertEquals("no choice left behind", null, h.origins()["newcomer"])
        assertFalse("nothing handed over", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a tap whose admission isn't stored, then too old - no choice, no hand-over, too long to catch up`() = runTest {
        val (id, delivered) = tappedAdmissionNotStored()

        clock = dao.get(id)!!.expiresAt + 1
        runner.runOnce()
        advanceUntilIdle()

        assertTrue("ended", dao.changes.value.isEmpty())
        assertEquals(PendingLines.tooOld(PendingInputs.letInSubject("Newcomer")), dao.notices.value.single().line)
        assertEquals("no choice left behind", null, h.origins()["newcomer"])
        assertFalse("nothing handed over", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a tap that can't be kept - nothing done, the question stays, and it says why`() = runTest {
        val request = newcomerAsked()
        repeat(PendingChanges.MAX_PER_CIRCLE) { n ->
            store.queue<Unit>("c-1", PendingInputs.LetIn("Family", "u$n", "U$n", "UNCONFIRMED_INVITER", "s$n", "g$n"))
        }

        val tapped = h.repository.letIn(request)

        assertEquals(PendingLines.tooMany("Family"), tapped.exceptionOrNull()?.message)
        assertEquals("the question stays", listOf(request), h.repository.keyAccessRequests.value)
        assertEquals(PendingChanges.MAX_PER_CIRCLE, dao.changes.value.size)
    }
}
