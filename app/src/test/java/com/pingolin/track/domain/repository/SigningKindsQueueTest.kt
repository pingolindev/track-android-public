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
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.domain.pending.QueuedForSync
import com.pingolin.track.domain.pending.SignedStatement
import com.pingolin.track.domain.pending.StatementNotSaved
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The kinds that sign, while this phone is behind: the person's answer is kept, with nothing
 * signed, pinned or approved; a waiting change's run saves each statement's id before its
 * request leaves, and sends nothing when it can't (fail closed).
 */
class SigningKindsQueueTest {

    private val dao = InMemoryPendingChangeDao()
    private val h = TrustHarness(pendingChanges = pendingChangesIn(dao))

    /** Accounts the invite lookup names are UUIDs (AccountIds.strict). */
    private val dan = "da400000-0000-4000-8000-0000000000d4"

    @After
    fun tearDown() = clearAllMocks()

    private fun behind() {
        h.circleVersion = 4
        h.repository.catchUpPausesMs = emptyList()
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(true, null, null))
    }

    private fun emailInvitationsAnswered(): io.mockk.CapturingSlot<InviteRequest> {
        // No account for the address: an invitation to the email, signed here.
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(404, """{"success":false}""".toResponseBody(null))
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns
            Response.success(ApiResponse(true, InviteResponse(delivery = "notified"), null))
        return sent
    }

    @Test
    fun `behind, an invitation the person asks for is kept, and nothing is signed or sent`() = runTest {
        h.earlierSeed("me", "alice")
        behind()

        val result = h.repository.inviteToCircle("c-1", "Dan@X.test", keepIfBehind = true)

        assertTrue(result.exceptionOrNull() is QueuedForSync)
        // With the version this phone held when the person decided.
        assertEquals(PendingInputs.Invite("Family", "dan@x.test", tapVersion = 3), PendingInputs.read(dao.changes.value.single()))
        assertTrue("nothing signed", h.statements.signed.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `a waiting invitation's run saves its statement's id before the request leaves, and sends its own id`() = runTest {
        h.earlierSeed("me", "alice")
        val sent = emailInvitationsAnswered()
        val order = mutableListOf<String>()
        val saved = mutableListOf<SignedStatement>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } answers {
            order += "request"
            Response.success(ApiResponse(true, InviteResponse(delivery = "notified"), null))
        }
        val run = PendingRun("ch-1", "ch-1", onSigned = { saved += it; order += "saved" })

        val result = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = run)

        assertTrue(result.isSuccess)
        assertEquals(listOf("saved", "request"), order)
        assertEquals(h.statements.parse(sent.captured.statement!!)!!.statementId, saved.single().id)
        assertEquals("ch-1", sent.captured.clientRequestId)
    }

    @Test
    fun `a statement id that can't be saved sends nothing (fail closed)`() = runTest {
        h.earlierSeed("me", "alice")
        emailInvitationsAnswered()
        val run = PendingRun("ch-1", "ch-1", onSigned = { throw StatementNotSaved() })

        val result = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = run)

        assertTrue(result.exceptionOrNull() is StatementNotSaved)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `a waiting invitation never goes out unsigned on a lookup that didn't answer - it waits`() = runTest {
        h.earlierSeed("me", "alice")
        val run = PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") })
        for (status in listOf(502, 408, 429)) {
            coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(status, """{"success":false}""".toResponseBody(null))

            val result = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = run)

            assertTrue("$status", result.exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
        }
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    private fun lookupUnavailable() {
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns
            Response.error(503, """{"success":false,"error":"x","error_code":"lookup_unavailable"}""".toResponseBody(null))
    }

    @Test
    fun `an address that can't be checked - the tap is kept with its waiting line, nothing signed or sent`() = runTest {
        h.earlierSeed("me", "alice")
        lookupUnavailable()

        val tapped = h.repository.inviteToCircle("c-1", "Dan@X.test", keepIfBehind = true)

        assertEquals("Waiting to sync. dan@x.test will be invited as soon as Pingolin can check the address.", tapped.exceptionOrNull()?.message)
        assertTrue(tapped.exceptionOrNull() is QueuedForSync)
        assertEquals(PendingInputs.Invite("Family", "dan@x.test", addressCheck = true, tapVersion = 3), PendingInputs.read(dao.changes.value.single()))
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())

        // No connection, a time limit or any 5xx is the same check that couldn't be done.
        coEvery { h.tracksApi.lookupPublicKey(any()) } throws java.net.SocketTimeoutException()
        assertTrue(h.repository.inviteToCircle("c-1", "eve@x.test", keepIfBehind = true).exceptionOrNull() is QueuedForSync)
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(500, """{"success":false}""".toResponseBody(null))
        assertTrue(h.repository.inviteToCircle("c-1", "fay@x.test", keepIfBehind = true).exceptionOrNull() is QueuedForSync)
        // So is a 408 (a time limit) or a 429 (try later).
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(408, """{"success":false}""".toResponseBody(null))
        assertTrue(h.repository.inviteToCircle("c-1", "gus@x.test", keepIfBehind = true).exceptionOrNull() is QueuedForSync)
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns
            Response.error(429, """{"success":false,"error_code":"rate_limited"}""".toResponseBody(null))
        assertTrue(h.repository.inviteToCircle("c-1", "hal@x.test", keepIfBehind = true).exceptionOrNull() is QueuedForSync)
        assertEquals(
            listOf("dan@x.test", "eve@x.test", "fay@x.test", "gus@x.test", "hal@x.test"),
            dao.changes.value.map { (PendingInputs.read(it) as PendingInputs.Invite).email },
        )
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue("never an address-only or unsigned invitation", h.statements.signed.isEmpty())
    }

    @Test
    fun `kept for the check, it goes out once the check answers - once, bound to the account and keys named`() = runTest {
        h.earlierSeed("me", "alice")
        lookupUnavailable()
        h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true)
        val kept = dao.changes.value.single()
        // Later the directory answers: dan's account, with both keys.
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.success(
            ApiResponse(true, com.pingolin.track.data.remote.dto.UserPublicKeyResponse("dan@x.test", TrustHarness.sharing(dan), dan, TrustHarness.signing(dan)), null)
        )
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns
            Response.success(ApiResponse(true, InviteResponse(delivery = "notified"), null))
        val saved = mutableListOf<SignedStatement>()

        val result = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = PendingRun(kept.id, kept.id, onSigned = { saved += it }))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { h.tracksApi.inviteToCircle(any(), any()) }
        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals(com.pingolin.track.domain.repository.MembershipStatements.Kind.INVITATION_TO_ACCOUNT, statement.kind)
        assertEquals(dan, statement.subjectUserId)
        assertEquals(TrustHarness.sharing(dan), statement.subjectSharingKeyHex)
        assertEquals(TrustHarness.signing(dan), statement.subjectSigningKeyHex)
        assertEquals(kept.id, sent.captured.clientRequestId)
        assertEquals(statement.statementId, saved.single().id)
    }

    @Test
    fun `It's them when the address can't be checked is kept with the answer, and a waiting run waits`() = runTest {
        h.earlierSeed("me", "alice")
        lookupUnavailable()
        val change = InviteeChangedException("c-1", "dan@x.test", "dan", TrustHarness.sharing("dan"), TrustHarness.signing("dan"), "1 2 3")

        val answered = h.repository.confirmInviteeAndInvite(change, keepIfBehind = true)

        assertEquals("Waiting to sync. dan@x.test will be invited as soon as Pingolin can check the address.", answered.exceptionOrNull()?.message)
        assertEquals(
            PendingInputs.Invite(
                "Family", "dan@x.test", confirmedUserId = "dan", confirmedSharingKey = TrustHarness.sharing("dan"),
                confirmedSigningKey = TrustHarness.signing("dan"), addressCheck = true, tapVersion = 3,
            ),
            PendingInputs.read(dao.changes.value.single()),
        )
        // A 429 is no answer too: the answer is kept again, never "couldn't check".
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(429, """{"success":false}""".toResponseBody(null))
        assertEquals(
            "Waiting to sync. dan@x.test will be invited as soon as Pingolin can check the address.",
            h.repository.confirmInviteeAndInvite(change, keepIfBehind = true).exceptionOrNull()?.message,
        )
        // The tap takes nothing yet: their details are taken only once the check
        // answers.
        io.mockk.verify(exactly = 0) { h.cryptoManager.approveMemberKeyChange(any(), any(), any(), any()) }
        assertTrue("nothing pinned", h.pins.sharing["dan"] == null)
        val waiting = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") }))
        assertTrue(waiting.exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `a refused check (a 4xx) is never kept - a run waits, It's them couldn't check, a plain tap goes unsigned`() = runTest {
        h.earlierSeed("me", "alice")
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns
            Response.success(ApiResponse(true, InviteResponse(delivery = "notified"), null))
        val change = InviteeChangedException("c-1", "dan@x.test", "dan", TrustHarness.sharing("dan"), TrustHarness.signing("dan"), "1 2 3")
        for (status in listOf(400, 401, 403)) {
            coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(status, """{"success":false}""".toResponseBody(null))

            val waiting = h.repository.inviteToCircle("c-1", "dan@x.test", pendingRun = PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") }))
            assertTrue("a run waits", waiting.exceptionOrNull() is com.pingolin.track.domain.pending.CouldNotCheck)

            val answered = h.repository.confirmInviteeAndInvite(change, keepIfBehind = true)
            assertEquals("Couldn't check dan@x.test just now.", answered.exceptionOrNull()?.message)

            // The plain tap: the unsigned live fallback.
            assertTrue(h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true).isSuccess)
            assertEquals(null, sent.captured.statement)
            assertEquals(null, sent.captured.encryptedCircleKey)
        }
        assertTrue("never kept", dao.changes.value.isEmpty())
        assertTrue("nothing signed", h.statements.signed.isEmpty())
        io.mockk.verify(exactly = 0) { h.cryptoManager.approveMemberKeyChange(any(), any(), any(), any()) }
        coVerify(exactly = 3) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `a real no account answer still sends the address invitation at once`() = runTest {
        h.earlierSeed("me", "alice")
        val sent = emailInvitationsAnswered()

        assertTrue(h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true).isSuccess)

        assertTrue("not kept", dao.changes.value.isEmpty())
        assertEquals(
            com.pingolin.track.domain.repository.MembershipStatements.Kind.INVITATION_TO_EMAIL,
            h.statements.parse(sent.captured.statement!!)!!.kind,
        )
    }

    @Test
    fun `a waiting run that finds this phone behind after all neither queues again nor signs`() = runTest {
        h.earlierSeed("me", "alice")
        behind()
        val run = PendingRun("ch-1", "ch-1", onSigned = { error("never signed while behind") })

        val result = h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true, pendingRun = run)

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        assertTrue(dao.changes.value.isEmpty())
        assertTrue(h.statements.signed.isEmpty())
    }

    @Test
    fun `behind, Let them in is still asked - and not asked again once its answer waits`() = runTest {
        h.earlierSeed("me", "alice")
        h.repository.deliveryScope = this
        h.serverLists(
            roster = listOf(
                h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("mallory"),
                h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null),
            )
        )
        behind()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = h.repository.keyAccessRequests.value.single()

        assertTrue(h.repository.letIn(request).exceptionOrNull() is QueuedForSync)
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue("not asked again while its answer waits", h.repository.keyAccessRequests.value.isEmpty())
        assertEquals(1, dao.changes.value.size)

        // "Not now" to the waiting change's own question: it ends, and the live question stays
        // away as after its own "Not now".
        h.repository.pendingQuestionAnswered("c-1", "newcomer", deferLetIn = true)
        dao.end(dao.changes.value.single().id, null)
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `behind, Let them in is kept for the keys the question showed - nothing pinned, approved or signed`() = runTest {
        h.earlierSeed("me", "alice")
        behind()
        val request = KeyAccessRequest(
            circleId = "c-1", circleName = "Family", userId = "sam", displayName = "Sam", email = null,
            reason = KeyAccessRequest.Reason.UNCONFIRMED_INVITER, number = "1 2 3",
            sharingKeyHex = TrustHarness.sharing("sam"), signingKeyHex = TrustHarness.signing("sam"),
        )

        val result = h.repository.letIn(request)

        assertTrue(result.exceptionOrNull() is QueuedForSync)
        assertEquals(
            PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", TrustHarness.sharing("sam"), TrustHarness.signing("sam"), tapVersion = 3),
            PendingInputs.read(dao.changes.value.single()),
        )
        assertTrue("not pinned", h.pins.sharing["sam"] == null)
        assertTrue("not approved", "sam" !in h.origins())
        assertTrue("nothing signed", h.statements.signed.isEmpty())
    }
}
