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

package com.pingolin.track.data.remote.api

import com.pingolin.track.data.remote.dto.CreateDestinationRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.testutil.startOnIpv4Loopback
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * A destination's creator, the server's `user_id`, where it's decoded: in its one spelling, at
 * the same entry that takes its circle's. One that is no UUID refuses the destination; one the
 * server left out stays out, so the destination is nobody's here.
 */
class DestinationCreatorEntryTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val logged = mutableListOf<String>()
    private val api = CanonicalIdsTrackApi(
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java),
    ) { logged += it }

    private val ann = "a11ce000-0000-4000-8000-00000000000a"
    private val circle = "c1c1c1c1-0000-4000-8000-0000000000c1"
    private val enc = """{"nonce":"n","ciphertext":"c"}"""

    @After
    fun tearDown() = server.shutdown()

    private fun reply(data: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"data":$data,"error":null}""")

    private fun destination(id: String, creator: String?) =
        """{"id":"$id",${creator?.let { """"user_id":"$it",""" }.orEmpty()}"encrypted_user_id":$enc,"circle_id":"$circle",""" +
            """"encrypted_destination":$enc,"notify_arrival":true,"status":"active","started_day_bucket":1,"created_day_bucket":1,"updated_day_bucket":1}"""

    @Test
    fun `the list - each spelling of the creator is its one, a missing one stays missing, one that is no UUID drops its row`() = runTest {
        val spellings = listOf(ann, ann.uppercase(), ann.replace("-", ""), "{$ann}", "urn:uuid:${ann.uppercase()}")
        val rows = spellings.mapIndexed { i, s -> destination("d-$i", s) } + destination("d-none", null) + destination("d-bad", "user-ann")
        server.enqueue(reply("[${rows.joinToString(",")}]"))

        val listed = api.getDestinations().body()!!.data!!

        assertEquals(spellings.indices.map { "d-$it" } + "d-none", listed.map { it.id })
        assertEquals(List(spellings.size) { ann } + null, listed.map { it.userId })
        assertEquals(listOf("destinations: 1 account id(s) that aren't UUIDs; their entries refused"), logged)
    }

    @Test
    fun `one destination - its creator in its one spelling, or no answer when it is no UUID`() = runTest {
        server.enqueue(reply(destination("d-1", ann.uppercase())))
        assertEquals(ann, api.getDestination("d-1").body()!!.data!!.userId)

        server.enqueue(reply(destination("d-1", null)))
        assertNull("the server didn't say: nobody's", api.getDestination("d-1").body()!!.data!!.userId)

        server.enqueue(reply(destination("d-1", "user-ann")))
        val refused = api.getDestination("d-1").body()!!
        assertFalse(refused.success)
        assertNull(refused.data)
        assertEquals(listOf("destination: its account ids couldn't be read; not taken"), logged)
    }

    @Test
    fun `a row with no creator whose circle is spelled another way still reads, its circle in its one spelling`() = runTest {
        // The circle's copy() runs with the creator absent: a non-null field left null by Gson would
        // throw there and take the whole list with it.
        server.enqueue(reply("[${destination("d-1", null).replace(circle, circle.uppercase())}]"))

        val listed = api.getDestinations().body()!!.data!!

        assertEquals(listOf(circle), listed.map { it.circleId })
        assertEquals(listOf<String?>(null), listed.map { it.userId })
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `the create answer carries the creator in its one spelling`() = runTest {
        server.enqueue(reply(destination("d-1", ann.replace("-", "").uppercase())))
        val payload = EncryptedPayload("n", "c")

        val created = api.createDestination(
            CreateDestinationRequest(
                circleId = circle, encryptedDestination = payload, encryptedDestName = null, encryptedEta = null,
                etaDayBucket = null, notifyArrival = true, encryptedUserId = payload,
            )
        ).body()!!.data!!

        assertEquals(ann, created.userId)
    }
}
