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
import com.pingolin.track.data.remote.dto.StatementsHeldRequest
import com.pingolin.track.data.remote.dto.StatementsHeldResponse
import com.pingolin.track.domain.pending.LookAnswer
import com.pingolin.track.domain.pending.SignedStatement
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The look asks the server which of a change's statements it holds, and only its positive answer
 * counts; settling kills the rest durably, before anything ends, and binds the invitee record to
 * the invitation the server holds.
 */
class StatementSettleTest {

    private val h = TrustHarness()

    @After
    fun tearDown() = clearAllMocks()

    private val a = "0a1b2c3d-0000-4000-8000-000000000001"
    private val b = "0a1b2c3d-0000-4000-8000-000000000002"

    private fun hex(id: String) = id.replace("-", "")

    private fun lookAnswers(response: Response<ApiResponse<StatementsHeldResponse>>) {
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } returns response
    }

    @Test
    fun `the look asks with 16-byte hex ids and counts only the ids it asked about`() = runTest {
        val sent = slot<StatementsHeldRequest>()
        coEvery { h.tracksApi.statementsHeld("c-1", capture(sent)) } returns Response.success(
            ApiResponse(true, StatementsHeldResponse(listOf(HeldStatement(hex(b).uppercase(), "vouch"), HeldStatement("ff".repeat(16), "vouch"))), null)
        )

        val answer = h.repository.statementsHeld("c-1", listOf(a, b))

        assertEquals(listOf(hex(a), hex(b)), sent.captured.statementIds)
        assertEquals(LookAnswer.Held(setOf(hex(b))), answer)
    }

    @Test
    fun `a 404 is not found, and no answer, a 5xx or a refusal is couldn't look - never none held`() = runTest {
        lookAnswers(Response.error(404, """{"success":false}""".toResponseBody(null)))
        assertEquals(LookAnswer.NotFound, h.repository.statementsHeld("c-1", listOf(a)))
        lookAnswers(Response.error(503, """{"success":false}""".toResponseBody(null)))
        assertEquals(LookAnswer.CouldNotLook, h.repository.statementsHeld("c-1", listOf(a)))
        lookAnswers(Response.success(ApiResponse(false, null, null)))
        assertEquals(LookAnswer.CouldNotLook, h.repository.statementsHeld("c-1", listOf(a)))
        coEvery { h.tracksApi.statementsHeld("c-1", any()) } throws java.net.SocketTimeoutException()
        assertEquals(LookAnswer.CouldNotLook, h.repository.statementsHeld("c-1", listOf(a)))
    }

    @Test
    fun `settling kills the spares durably and binds the invitee record to the held invitation only`() {
        val held = SignedStatement(b, subject = "dan", expiresAtSecs = 1_900_000_000)

        assertTrue(h.repository.settleSignedStatements("c-1", "dan@x.test", held, listOf(a)))

        assertTrue(h.approvals.invitationDead("c-1", a))
        assertFalse(h.approvals.invitationDead("c-1", b))
        assertEquals(mapOf("dan" to b), h.approvals.pendingInvitees("c-1"))
    }

    @Test
    fun `a dead-id write that fails settles nothing, and says so`() {
        val held = SignedStatement(b, subject = "dan", expiresAtSecs = 1_900_000_000)
        h.approvalPrefs.failCommits = true

        assertFalse(h.repository.settleSignedStatements("c-1", "dan@x.test", held, listOf(a)))

        h.approvalPrefs.failCommits = false
        assertFalse(h.approvals.invitationDead("c-1", a))
        assertTrue("no invitee record before the spares are dead", h.approvals.pendingInvitees("c-1").isEmpty())
    }
}
