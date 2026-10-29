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
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import io.mockk.CapturingSlot
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The circle's name in an invitation: sent only to an address the lookup ANSWERED has no
 * account, which gets it in the invitation e-mail; the server keeps it nowhere. An account
 * holder (one that never published keys included) and anyone behind a lookup that didn't
 * answer get no name in the request at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteCircleNameTest {

    @After
    fun tearDown() = clearAllMocks()

    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val email = "dan@x.test"

    private fun TestScope.harness() = TrustHarness(self = me, selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed(me)
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false
        assertEquals("the circle the harness invites to", "Family", h.circle.name)
    }

    private fun TrustHarness.lookupAnswers(userId: String?, sharingKey: String?, signingKey: String?) {
        coEvery { tracksApi.lookupPublicKey(email) } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse(email, sharingKey, userId, signingKey), error = null)
        )
    }

    private fun TrustHarness.lookupRefused(code: Int) {
        coEvery { tracksApi.lookupPublicKey(email) } returns Response.error(code, "{}".toResponseBody(null))
    }

    private fun TrustHarness.invitationsTaken(): CapturingSlot<InviteRequest> {
        val sent = slot<InviteRequest>()
        coEvery { tracksApi.inviteToCircle("c-1", capture(sent)) } returns Response.success(
            ApiResponse(true, InviteResponse(delivery = "notified"), null)
        )
        return sent
    }

    @Test
    fun `an account holder's invitation carries no circle name`() = runTest {
        val h = harness()
        h.lookupAnswers(dan, sharing(dan), signing(dan))
        val sent = h.invitationsTaken()

        assertTrue(h.repository.inviteToCircle("c-1", email).isSuccess)

        assertNull(sent.captured.circleName)
        assertTrue("still the account's invitation, key and all", sent.captured.encryptedCircleKey != null)
    }

    @Test
    fun `an account that never published keys gets no circle name either - no account is the test, not the kind`() = runTest {
        val h = harness()
        h.lookupAnswers(dan, sharingKey = null, signingKey = null)
        val sent = h.invitationsTaken()

        assertTrue(h.repository.inviteToCircle("c-1", email).isSuccess)

        assertNull(sent.captured.circleName)
    }

    @Test
    fun `an address the lookup answered has no account gets the circle's name`() = runTest {
        // The directory's "no account" both ways: a 404, and an answer that names nobody.
        val answers: List<Pair<String, TrustHarness.() -> Unit>> = listOf(
            "404" to { lookupRefused(404) },
            "no account named" to { lookupAnswers(null, null, null) },
        )
        for ((case, answer) in answers) {
            val h = harness()
            h.answer()
            val sent = h.invitationsTaken()

            assertTrue(case, h.repository.inviteToCircle("c-1", email).isSuccess)

            assertEquals(case, "Family", sent.captured.circleName)
        }
    }

    @Test
    fun `a 2xx lookup with nothing readable in it is no answer - nothing is signed or sent, and no name`() = runTest {
        val unreadable: List<Pair<String, Response<ApiResponse<UserPublicKeyResponse>>>> = listOf(
            "no data" to Response.success(ApiResponse(success = true, data = null, error = null)),
            "not a success" to Response.success(ApiResponse(success = false, data = UserPublicKeyResponse(email, null, null, null), error = null)),
            "no body" to Response.success(null),
        )
        for ((case, answer) in unreadable) {
            val h = harness()
            coEvery { h.tracksApi.lookupPublicKey(email) } returns answer
            h.invitationsTaken()

            assertEquals(case, "Couldn't check $email just now.", h.repository.inviteToCircle("c-1", email).exceptionOrNull()?.message)

            coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
            assertTrue(case, h.statements.signed.isEmpty())
        }
    }

    @Test
    fun `a lookup that didn't answer sends no circle name, though the plain tap still goes`() = runTest {
        // Another 4xx is no answer about the address: the tap's invitation still goes, nameless.
        for (code in listOf(400, 403)) {
            val h = harness()
            h.lookupRefused(code)
            val sent = h.invitationsTaken()

            assertTrue("$code", h.repository.inviteToCircle("c-1", email).isSuccess)

            assertNull("$code", sent.captured.circleName)
        }
    }
}
