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
 * Resume puts back the mode held before the pause. The privacy settings decoded from the
 * server's own JSON (snake_case) through the app's Retrofit and Gson, with
 * `mode_before_pause` present, null and absent; and the resume call as it goes on the wire.
 */
class PrivacyResumeWireTest {

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

    /** The settings as the server writes them; [extra] is `mode_before_pause`, or "" for a response
     * without it.
     */
    private fun settings(mode: String, radius: String, pausedUntil: String, extra: String) =
        MockResponse().setResponseCode(200).setBody(
            """{"success":true,"data":{"id":"5f0c7d1e-0000-4000-8000-000000000001",""" +
                """"circle_id":"c0000000-0000-4000-8000-00000000000c","sharing_mode":"$mode",""" +
                """"approximate_radius_miles":$radius,"approximate_until":null,"paused_until":$pausedUntil,""" +
                """"share_realtime":true,"share_history":true,"share_battery":false,"primary_device_id":null,""" +
                """"primary_device_pseudonym":null,"show_all_devices":false,"allow_alerts_on_me":true,""" +
                """"created_day_bucket":20374,"updated_day_bucket":20375$extra}}"""
        )

    @Test
    fun `while paused the settings say what Resume goes back to, and an older server says nothing`() = runTest {
        server.enqueue(settings("paused", "5", "\"2026-10-14T17:00:00+00:00\"", ",\"mode_before_pause\":\"approximate\""))
        server.enqueue(settings("exact", "null", "null", ",\"mode_before_pause\":null"))
        server.enqueue(settings("paused", "null", "null", ""))

        val paused = api.getPrivacySettings("c0000000-0000-4000-8000-00000000000c").body()!!.data!!
        assertEquals("paused", paused.sharingMode)
        assertEquals("approximate", paused.modeBeforePause)
        assertEquals("the radius is kept while paused", 5, paused.approximateRadiusMiles)

        assertNull(api.getPrivacySettings("c0000000-0000-4000-8000-00000000000c").body()!!.data!!.modeBeforePause)
        assertNull(
            "Gson leaves a missing key null, whatever the Kotlin default",
            api.getPrivacySettings("c0000000-0000-4000-8000-00000000000c").body()!!.data!!.modeBeforePause,
        )
    }

    @Test
    fun `Resume is a POST to the circle's resume route with nothing in it, answered with the settings`() = runTest {
        server.enqueue(settings("approximate", "5", "null", ",\"mode_before_pause\":null"))

        val restored = api.resumeSharing("c0000000-0000-4000-8000-00000000000c").body()!!.data!!

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/privacy/circles/c0000000-0000-4000-8000-00000000000c/resume", request.path)
        assertEquals(0L, request.bodySize)
        assertEquals("approximate", restored.sharingMode)
        assertEquals(5, restored.approximateRadiusMiles)
        assertNull(restored.pausedUntil)
        assertNull(restored.modeBeforePause)
    }
}
