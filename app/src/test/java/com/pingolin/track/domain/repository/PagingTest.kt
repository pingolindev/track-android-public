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
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.testutil.startOnIpv4Loopback
import io.mockk.clearAllMocks
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The lists the server pages (its `PaginatedResponse`: `items`, `next_cursor`,
 * `has_more`): the app reads the cursor under the server's own name and sends it back, so a
 * list gets past its first page.
 */
class PagingTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val api = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TrackApi::class.java)
    private val h = TrustHarness()

    @After
    fun tearDown() {
        server.shutdown()
        clearAllMocks()
    }

    private fun page(json: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(json)

    @Test
    fun `a real two-page member list - the cursor is read from next_cursor and sent back`() = runTest {
        server.enqueue(page("""{"success":true,"data":{"items":[{"user_id":"u-1","role":"owner"},{"user_id":"u-2","role":"member"}],"next_cursor":"bWVtYmVyLTI","has_more":true},"error":null}"""))
        server.enqueue(page("""{"success":true,"data":{"items":[{"user_id":"u-3","role":"member"}],"has_more":false},"error":null}"""))

        val first = api.getCircleMembers("c-1", limit = 100).body()!!.data!!
        assertEquals("bWVtYmVyLTI", first.cursor)
        server.takeRequest()
        val second = api.getCircleMembers("c-1", limit = 100, cursor = first.cursor).body()!!.data!!

        assertEquals("/api/v1/circles/c-1/members?limit=100&cursor=bWVtYmVyLTI", server.takeRequest().path)
        assertEquals(listOf("u-3"), second.items.map { it.userId })
        assertNull("the last page has none", second.cursor)
    }

    @Test
    fun `a real circle list page - its next_cursor is read`() = runTest {
        server.enqueue(page("""{"success":true,"data":{"items":[],"next_cursor":"Y2lyY2xlLTI","has_more":true},"error":null}"""))

        assertEquals("Y2lyY2xlLTI", api.getCircles().body()!!.data!!.cursor)
    }

    @Test
    fun `a roster scan the server restarts from the first page is merged by user id`() = runTest {
        h.earlierSeed("me", "alice", "bob")
        val first = PaginatedResponse(items = listOf(h.rosterMember("me"), h.rosterMember("alice")), hasMore = true, cursor = "m-2")
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), null) } returns Response.success(ApiResponse(true, first, null))
        // An old cursor, around a deploy: the server starts over, repeating the first page.
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), "m-2") } returns Response.success(
            ApiResponse(true, PaginatedResponse(items = listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob")), hasMore = false), null)
        )
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } returns Response.success(
            ApiResponse(true, listOf("me", "alice", "bob").map { h.key(it) }, null)
        )

        h.repository.refreshMembers("c-1")

        assertEquals(listOf("alice", "bob", "me"), h.storedRows.map { it.userId }.sorted())
    }

    @Test
    fun `a roster is read to its last page by the cursor, and one that stops without a cursor is not whole`() = runTest {
        val first = PaginatedResponse(items = listOf(h.rosterMember("me"), h.rosterMember("alice")), hasMore = true, cursor = "m-2")
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), null) } returns Response.success(ApiResponse(true, first, null))
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), "m-2") } returns
            Response.success(ApiResponse(true, PaginatedResponse(items = listOf(h.rosterMember("bob")), hasMore = false), null))

        assertEquals(setOf("me", "alice", "bob"), h.repository.memberIdsWhole("c-1"))

        coEvery { h.tracksApi.getCircleMembers("c-1", any(), null) } returns
            Response.success(ApiResponse(true, first.copy(cursor = null), null))
        assertNull(h.repository.memberIdsWhole("c-1"))
    }
}
