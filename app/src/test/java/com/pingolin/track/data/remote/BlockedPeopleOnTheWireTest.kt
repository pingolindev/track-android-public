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

package com.pingolin.track.data.remote

import com.google.gson.JsonParser
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.BlockPersonRequest
import com.pingolin.track.testutil.startOnIpv4Loopback
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * "Blocked people" as the server reads and writes it, through a real Retrofit [TrackApi] and
 * the app's converter, onto a MockWebServer.
 *
 * The answers below are the SERVER's JSON, written out, not built from this app's own classes:
 * Gson leaves a field whose name does not match at its default and says nothing, so a fixture
 * made from the app's DTO would pass with a wrong wire name.
 */
class BlockedPeopleOnTheWireTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TrackApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun ok(data: String = "null") =
        MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$data}""")

    @Test
    fun `the list is one GET, and each row's address is read from blocked_email`() = runTest {
        server.enqueue(
            ok(
                """[{"blocked_email":"adam@example.test","created_at":"2026-10-07T13:00:00.123456Z"},""" +
                    """{"blocked_email":"zoe@example.test","created_at":"2026-10-07T13:05:00Z"}]""",
            ),
        )

        val body = api.listBlockedPeople().body()!!

        val sent = server.takeRequest()
        assertEquals("GET", sent.method)
        assertEquals("/api/v1/invites/blocks", sent.path)
        assertTrue(body.success)
        assertEquals(listOf("adam@example.test", "zoe@example.test"), body.data!!.map { it.blockedEmail })
        assertEquals("2026-10-07T13:00:00.123456Z", body.data!![0].createdAt)
    }

    @Test
    fun `nobody blocked is an empty list, not a missing one`() = runTest {
        server.enqueue(ok("[]"))
        val body = api.listBlockedPeople().body()!!
        assertTrue(body.success)
        assertEquals(0, body.data!!.size)
    }

    @Test
    fun `a block is one POST whose body is the address under the name email`() = runTest {
        server.enqueue(ok())

        val answer = api.blockPerson(BlockPersonRequest("Sam+circles@Example.test"))

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/v1/invites/block", sent.path)
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            JsonParser.parseString("""{"email":"Sam+circles@Example.test"}"""),
            JsonParser.parseString(sent.body.readUtf8()),
        )
        assertTrue(answer.isSuccessful && answer.body()!!.success)
    }

    /**
     * The address never goes in a DELETE's path: a path is what a proxy's access log keeps, so
     * every address a person unblocked would sit there in the clear for as long as the log is
     * kept. It goes in the body, like a block's, whatever characters it holds.
     */
    @Test
    fun `an unblock is one POST whose body is the address, and whose path carries none`() = runTest {
        for (address in listOf("sam@example.test", "sam+circles@example.test", "a/b?c#d%e@example.test")) {
            server.enqueue(ok())

            val answer = api.unblockPerson(BlockPersonRequest(address))

            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals(address, "/api/v1/invites/unblock", sent.path)
            assertEquals(address, null, sent.requestUrl!!.query)
            assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
            val body = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
            assertEquals(setOf("email"), body.keySet())
            assertEquals(address, body.get("email").asString)
            assertTrue(answer.isSuccessful && answer.body()!!.success)
        }
    }

    @Test
    fun `no request of the block list carries an address anywhere but in its body`() = runTest {
        server.enqueue(ok("[]"))
        server.enqueue(ok())
        server.enqueue(ok())
        api.listBlockedPeople()
        api.blockPerson(BlockPersonRequest("sam@example.test"))
        api.unblockPerson(BlockPersonRequest("sam@example.test"))
        repeat(3) {
            val sent = server.takeRequest()
            assertTrue(sent.path!!, "@" !in sent.path!! && "%40" !in sent.path!!)
        }
    }

    @Test
    fun `a full list is a 409 that carries its code`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"success":false,"error":"Your list of blocked people is full. Unblock someone to add another.","error_code":"blocked_people_limit"}""",
            ),
        )
        val answer = api.blockPerson(BlockPersonRequest("one-too-many@example.test"))
        assertEquals(409, answer.code())
        assertEquals("blocked_people_limit", com.pingolin.track.data.remote.dto.ApiErrors.codeOf(answer))
    }
}
