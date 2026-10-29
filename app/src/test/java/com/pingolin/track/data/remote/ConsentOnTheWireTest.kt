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
import com.pingolin.track.data.remote.dto.AcceptInviteRequest
import com.pingolin.track.data.remote.dto.CreateCircleRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.GrantLocationConsentRequest
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
import retrofit2.http.POST

/**
 * Consent is membership, as the server reads it: the agreement travels IN the accept and the
 * creation, and only leaving ends it. Through a real Retrofit [TrackApi] and the app's
 * converter, onto a MockWebServer.
 */
class ConsentOnTheWireTest {

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
    fun `the accept carries the agreement, in the shape the server reads`() = runTest {
        server.enqueue(ok())

        api.acceptInvite("inv-1", AcceptInviteRequest(GrantLocationConsentRequest(clientNoticeVersion = "1.1")))

        val sent = server.takeRequest()
        assertEquals("POST", sent.method)
        assertEquals("/api/v1/invites/inv-1/accept", sent.path)
        assertTrue(sent.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            JsonParser.parseString("""{"location_consent":{"client_notice_version":"1.1","surface":"android"}}"""),
            JsonParser.parseString(sent.body.readUtf8()),
        )
    }

    @Test
    fun `the creation carries the creator's agreement beside the circle`() = runTest {
        server.enqueue(ok())

        api.createCircle(
            CreateCircleRequest(
                encryptedName = EncryptedPayload("n", "c"),
                encryptedCircleKey = EncryptedPayload("kn", "kc"),
                ephemeralPublicKey = "e",
                locationConsent = GrantLocationConsentRequest(clientNoticeVersion = "1.1"),
            )
        )

        val sent = server.takeRequest()
        assertEquals("/api/v1/circles", sent.path)
        val body = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
        assertEquals(
            JsonParser.parseString("""{"client_notice_version":"1.1","surface":"android"}"""),
            body.get("location_consent"),
        )
        assertEquals("e", body.get("ephemeral_public_key").asString)
    }

    /** The app never calls `POST /consent/location/circles/{id}`; the grant comes with the join. */
    @Test
    fun `nothing in the app can record an agreement apart from a join or a creation`() {
        val posts = TrackApi::class.java.methods.mapNotNull { it.getAnnotation(POST::class.java)?.value }
        assertTrue(posts.toString(), posts.none { it.startsWith("api/v1/consent/") })
    }

    /**
     * Nor stop it apart from leaving: no client call withdraws the consent. Leaving closes it
     * on the server, and the withdrawal route stays there for older versions of the app.
     */
    @Test
    fun `nothing in the app withdraws the agreement apart from leaving`() {
        val deletes = TrackApi::class.java.methods.mapNotNull { it.getAnnotation(retrofit2.http.DELETE::class.java)?.value }
        assertTrue(deletes.toString(), deletes.none { it.startsWith("api/v1/consent/") })
    }
}
