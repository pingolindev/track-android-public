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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.ui.circles.MemberSharing
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The members list's `location_sharing` / `sharing_paused_until`, decoded from the server's own
 * JSON (snake_case, as the server serialises them: the label always, the end as an RFC 3339
 * timestamp — "+00:00", fractions when there are some — or null) through the app's Retrofit and
 * Gson, not from the app's own data class. And from a response without the fields: nothing is
 * hidden.
 */
class MemberSharingWireTest {

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
    fun teardown() {
        server.shutdown()
    }

    /** One entry as the server writes it; [sharing] is the two keys, or "" for a response
     * without them.
     */
    private fun entry(id: String, sharing: String) =
        """{"user_id":"$id","display_name":null,"role":"member",""" +
            """"encrypted_circle_key":{"nonce":"bm9uY2U=","ciphertext":"Y2lwaGVy"},"ephemeral_public_key":"ZXBo",""" +
            """"can_see_history":true,"can_see_realtime":true,"status":"active","joined_day_bucket":20375,""" +
            """"can_rotate_keys":false$sharing}"""

    private fun page(vararg entries: String) = MockResponse().setResponseCode(200).setBody(
        """{"success":true,"data":{"items":[${entries.joinToString(",")}],"has_more":false}}"""
    )

    @Test
    fun `the server's four states arrive and are kept`() = runTest {
        server.enqueue(
            page(
                entry("a0000000-0000-4000-8000-00000000000a", ",\"location_sharing\":\"on\",\"sharing_paused_until\":null"),
                entry("b0000000-0000-4000-8000-00000000000b", ",\"location_sharing\":\"off\",\"sharing_paused_until\":null"),
                entry("c0000000-0000-4000-8000-00000000000c", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":\"2026-10-14T17:00:00+00:00\""),
                entry("d0000000-0000-4000-8000-00000000000d", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":null"),
            )
        )

        val items = api.getCircleMembers("c-1").body()!!.data!!.items

        assertEquals(listOf("on", "off", "paused", "paused"), items.map { it.locationSharing })
        assertEquals(listOf(null, null, "2026-10-14T17:00:00+00:00", null), items.map { it.sharingPausedUntil })
        assertEquals(
            listOf(null to null, "off" to null, "paused" to 1_791_997_200_000L, "paused" to null),
            items.map { MemberSharing.fromWire(it.locationSharing, it.sharingPausedUntil) },
        )
    }

    @Test
    fun `a pause's end in each form the server writes it, and one it can't read`() = runTest {
        server.enqueue(
            page(
                entry("a0000000-0000-4000-8000-00000000000a", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":\"2026-10-14T17:00:00+00:00\""),
                entry("b0000000-0000-4000-8000-00000000000b", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":\"2026-10-14T17:00:00.123456+00:00\""),
                entry("c0000000-0000-4000-8000-00000000000c", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":\"2026-10-14T17:00:00Z\""),
                entry("d0000000-0000-4000-8000-00000000000d", ",\"location_sharing\":\"paused\",\"sharing_paused_until\":\"soon\""),
            )
        )

        val items = api.getCircleMembers("c-1").body()!!.data!!.items

        assertEquals(
            "an unreadable end still decodes the list, as a pause with no end",
            listOf("paused" to 1_791_997_200_000L, "paused" to 1_791_997_200_123L, "paused" to 1_791_997_200_000L, "paused" to null),
            items.map { MemberSharing.fromWire(it.locationSharing, it.sharingPausedUntil) },
        )
    }

    @Test
    fun `a server from before the fields hides nobody`() = runTest {
        server.enqueue(page(entry("a0000000-0000-4000-8000-00000000000a", "")))

        val member = api.getCircleMembers("c-1").body()!!.data!!.items.single()

        assertNull("Gson leaves a missing key null, whatever the Kotlin default", member.locationSharing)
        assertNull(member.sharingPausedUntil)
        assertEquals(null to null, MemberSharing.fromWire(member.locationSharing, member.sharingPausedUntil))
    }
}
