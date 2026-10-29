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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.domain.pending.QueuedForSync
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.RateLimitInterceptor
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The address check through the app's own [RateLimitInterceptor], as the API client has it
 * (AppModule): a 429 is tried again up to 2 more times, at most 20 s in all, before the lookup
 * sees anything. A 429 it gives up on reaches invite() as a 429, which is "no answer": the
 * tap is kept, and nothing is signed or sent.
 */
class LookupRateLimitTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient.Builder().addInterceptor(RateLimitInterceptor()).build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TrackApi::class.java)
    private val dao = InMemoryPendingChangeDao()
    private val h = TrustHarness(pendingChanges = pendingChangesIn(dao))

    @After
    fun tearDown() {
        server.shutdown()
        clearAllMocks()
    }

    private fun tooMany() = MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
        .setHeader("Content-Type", "application/json").setBody("""{"success":false,"error":"x","error_code":"rate_limited"}""")

    private fun lookupThroughTheInterceptor() {
        coEvery { h.tracksApi.lookupPublicKey(any()) } coAnswers { api.lookupPublicKey(firstArg()) }
    }

    @Test
    fun `a 429 the interceptor gives up on keeps the tap - three tries in all, nothing signed or sent`() = runTest {
        h.earlierSeed("me", "alice")
        repeat(3) { server.enqueue(tooMany()) }
        lookupThroughTheInterceptor()

        val tapped = h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true)

        assertTrue(tapped.exceptionOrNull() is QueuedForSync)
        assertEquals("Waiting to sync. dan@x.test will be invited as soon as Pingolin can check the address.", tapped.exceptionOrNull()?.message)
        assertEquals("the first try and the interceptor's 2", 3, server.requestCount)
        assertEquals(1, dao.changes.value.size)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())
    }

    @Test
    fun `a 429 the interceptor gets past is the answer that follows it - here a real no account`() = runTest {
        h.earlierSeed("me", "alice")
        server.enqueue(tooMany())
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("""{"success":false}"""))
        lookupThroughTheInterceptor()
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns
            Response.success(ApiResponse(true, InviteResponse(delivery = "notified"), null))

        val tapped = h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true)

        assertTrue(tapped.isSuccess)
        assertEquals(2, server.requestCount)
        assertTrue("nothing kept", dao.changes.value.isEmpty())
        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals("the address invitation, signed", MembershipStatements.Kind.INVITATION_TO_EMAIL, statement.kind)
    }
}
