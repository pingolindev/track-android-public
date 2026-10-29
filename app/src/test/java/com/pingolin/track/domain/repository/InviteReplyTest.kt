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

import com.google.gson.Gson
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiErrors
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.PendingInviteResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.testutil.startOnIpv4Loopback
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The server answers an invitation with `{ invite_token, delivery }`: 201,
 * `{"success":true,"data":{...}}`, with `error` and `error_code` left out. It names neither the
 * invitation nor its circle. An app expecting an id and the circle would refuse every such answer
 * after the server had made the invitation: the inviter would be told it didn't go, and nothing an
 * invitation from here records would be recorded. Here the answer goes on the wire as the server
 * writes it, through the app's Retrofit, Gson and decoder (CanonicalIdsTrackApi), into the real
 * CircleRepository.
 *
 * With no id in the answer, a withdrawal learns its statement from the owner's list: one this run
 * hasn't read yet is read first, and a withdrawal that can't name its statement isn't made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteReplyTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val wire: TrackApi = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TrackApi::class.java)

    @After
    fun tearDown() {
        server.shutdown()
        clearAllMocks()
    }

    /** The circle, a UUID as the decoder takes it, with hex letters so its upper case differs. */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"
    private val otherCircle = "be0c1e00-0000-4000-8000-0000000000be"
    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val email = "dan@x.test"

    /** The token the server hands the inviter; a stand-in. Nothing here keeps it. */
    private val token = "invitee-token-stand-in"

    /** The answer as the server writes it; [extra] adds what a later server might send. */
    private fun answer(delivery: String, extra: String = "") = MockResponse()
        .setResponseCode(201)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"data":{"invite_token":"$token","delivery":"$delivery"$extra}}""")

    private fun TestScope.harness() = TrustHarness(self = me, selfRole = "owner", canonicalIds = true, circleId = fam).also { h ->
        h.repository.deliveryScope = this
        every { h.cryptoManager.circleHasRefreshRecords(fam) } returns false
        // Every invitation goes on the wire, then through the decoder the harness puts in front.
        coEvery { h.tracksApi.inviteToCircle(any(), any()) } coAnswers { wire.inviteToCircle(firstArg(), secondArg()) }
    }

    /** The address check names dan's account, with his keys. */
    private fun TrustHarness.directoryNamesDan() {
        coEvery { tracksApi.lookupPublicKey(email) } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse(email, sharing(dan), dan, signing(dan)), error = null)
        )
    }

    /** The invitation as it went on the wire. */
    private fun sent(): InviteRequest {
        val request = server.takeRequest()
        assertEquals("/api/v1/circles/$fam/invite/email", request.path)
        return Gson().fromJson(request.body.readUtf8(), InviteRequest::class.java)
    }

    /** The owner's list names [inviteId], with the statement [request] carried, as the server lists it. */
    private fun TrustHarness.listsInvitation(inviteId: String, request: InviteRequest) {
        coEvery { tracksApi.getCircleInvites(fam) } returns Response.success(ApiResponse(success = true, data = listOf(
            PendingInviteResponse(
                id = inviteId, inviteType = "email", targetEmail = email, status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, invitedBy = me, targetUserId = dan,
                statement = request.statement, statementSignature = request.statementSignature,
            ),
        ), error = null))
    }

    @Test
    fun `the server's own answer - 201 with its token and "notified", naming no circle - is the invitation sent, and what an invitation from here records is recorded`() = runTest {
        val h = harness()
        h.directoryNamesDan()
        // Removed from here without a key change, and no record has named it yet.
        h.ownRemovals.add(fam, dan, h.statements.now - 100)
        server.enqueue(answer("notified"))

        val result = h.repository.inviteToCircle(fam, email)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val reply = result.getOrThrow()
        assertEquals("notified", reply.delivery)
        assertNull(reply.circleId)
        assertFalse("the invitee's token is never kept", reply.toString().contains(token))
        assertEquals("nothing refused on the way", emptyList<String>(), h.refusedIds)
        val signed = h.statements.parse(sent().statement!!)!!
        assertEquals(Kind.INVITATION_TO_ACCOUNT, signed.kind)
        // This user's invitee, bound to the invitation signed here.
        assertEquals(mapOf(dan to signed.statementId), h.approvals.pendingInvitees(fam))
        // The re-invite cleared the removal, kept against this invitation.
        assertEquals(emptySet<String>(), h.ownRemovals.of(fam))
    }

    @Test
    fun `an address with no account - "emailed_no_account" - is sent, and the invitation to the address recorded`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.lookupPublicKey(email) } returns Response.error(404, "{}".toResponseBody(null))
        server.enqueue(answer("emailed_no_account"))

        val result = h.repository.inviteToCircle(fam, email)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals("emailed_no_account", result.getOrThrow().delivery)
        val signed = h.statements.parse(sent().statement!!)!!
        assertEquals(Kind.INVITATION_TO_EMAIL, signed.kind)
        assertEquals(mapOf(email to signed.statementId), h.approvals.pendingInvitees(fam))
    }

    @Test
    fun `an answer naming the circle in another spelling is that circle - one naming another circle, or no UUID, is no answer and records nothing`() = runTest {
        val same = harness()
        same.directoryNamesDan()
        server.enqueue(answer("notified", ""","circle_id":"${fam.uppercase()}""""))

        val named = same.repository.inviteToCircle(fam, email)

        assertTrue(named.exceptionOrNull()?.message, named.isSuccess)
        assertEquals(fam, named.getOrThrow().circleId)
        assertEquals(1, same.approvals.pendingInvitees(fam).size)
        sent()

        for ((circle, why) in listOf(otherCircle to "it names another circle than the one it was sent in", "circle-fam" to "its circle id isn't a UUID")) {
            val h = harness()
            h.directoryNamesDan()
            h.ownRemovals.add(fam, dan, h.statements.now - 100)
            server.enqueue(answer("notified", ""","circle_id":"$circle""""))

            val result = h.repository.inviteToCircle(fam, email)

            assertEquals(circle, CircleRepository.INVITE_NOT_SENT, result.exceptionOrNull()?.message)
            assertEquals(circle, listOf("invitation: $why; not taken"), h.refusedIds)
            assertEquals(circle, emptyMap<String, String>(), h.approvals.pendingInvitees(fam))
            assertEquals(circle, setOf(dan), h.ownRemovals.of(fam))
            sent()
        }
    }

    @Test
    fun `a withdrawal of an invitation this run has no statement for reads the owner's list first - its statement goes dead here, its record goes, its removal comes back`() = runTest {
        val h = harness()
        h.directoryNamesDan()
        h.ownRemovals.add(fam, dan, h.statements.now - 100)
        server.enqueue(answer("notified"))
        assertTrue(h.repository.inviteToCircle(fam, email).isSuccess)
        val request = sent()
        val signed = h.statements.parse(request.statement!!)!!
        h.listsInvitation("inv-1", request)
        coEvery { h.tracksApi.revokeInvite(fam, "inv-1") } returns Response.success(ApiResponse(true, Unit, null))

        assertTrue(h.repository.revokeInvite(fam, "inv-1").isSuccess)

        coVerifyOrder {
            h.tracksApi.getCircleInvites(fam)
            h.tracksApi.revokeInvite(fam, "inv-1")
        }
        assertTrue(h.approvals.invitationDead(fam, signed.statementId))
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees(fam))
        assertEquals(setOf(dan), h.ownRemovals.of(fam))
    }

    @Test
    fun `a withdrawal that can't read the owner's list withdraws nothing, and says so in this app's words`() = runTest {
        val cases = listOf(
            Response.error<ApiResponse<List<PendingInviteResponse>>>(500, "{}".toResponseBody(null)) to ApiErrors.TROUBLE,
            Response.error<ApiResponse<List<PendingInviteResponse>>>(400, "{}".toResponseBody(null)) to "Couldn't withdraw the invitation just now.",
        )
        for ((listed, line) in cases) {
            val h = harness()
            coEvery { h.tracksApi.getCircleInvites(fam) } returns listed
            coEvery { h.tracksApi.revokeInvite(fam, "inv-1") } returns Response.success(ApiResponse(true, Unit, null))

            assertEquals(line, h.repository.revokeInvite(fam, "inv-1").exceptionOrNull()?.message)

            coVerify(exactly = 0) { h.tracksApi.revokeInvite(any(), any()) }
        }
    }

    @Test
    fun `a withdrawal whose statement is known goes without reading the list - once read, and from a renewal's question - and its record goes`() = runTest {
        val h = harness()
        h.approvals.approveInvitee(fam, dan, email, "st-dan", h.statements.now + 7 * 86_400)
        coEvery { h.tracksApi.revokeInvite(fam, any()) } returns Response.success(ApiResponse(true, Unit, null))

        // A renewal's "Withdraw" names its statement itself.
        assertTrue(h.repository.withdrawInvitation(fam, "inv-1", "st-dan").isSuccess)

        coVerify(exactly = 0) { h.tracksApi.getCircleInvites(any()) }
        assertTrue(h.approvals.invitationDead(fam, "st-dan"))
        assertEquals(emptyMap<String, String>(), h.approvals.pendingInvitees(fam))

        // The list, read once, names another: its withdrawal reads nothing more.
        val another = h.invitation(dan, by = me, id = "st-dan-2")
        h.listsInvitation("inv-2", InviteRequest(email = email, statement = another.statementHex, statementSignature = another.signatureHex))
        assertTrue(h.repository.refreshPendingInvites(fam).isSuccess)
        assertTrue(h.repository.revokeInvite(fam, "inv-2").isSuccess)
        coVerify(exactly = 1) { h.tracksApi.getCircleInvites(fam) }
        assertTrue(h.approvals.invitationDead(fam, "st-dan-2"))
    }
}
