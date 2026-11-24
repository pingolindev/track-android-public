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

import com.pingolin.track.testutil.startOnIpv4Loopback
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * What goes on the wire for check-in rules, through the API as the app builds it: both lists of
 * inactivity rules ask for check-in rules too (without it the server leaves them out, for the
 * older apps that would judge them by location updates), of both kinds (without check_in_modes it
 * lists one-time rules only, for the apps that would judge a scheduled one by its hours), and the
 * circle's check-ins page by the previous page's last day bucket and id.
 */
class CheckInRuleQueryTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    private val api: TrackApi = CanonicalIdsTrackApi(
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java),
    ) {}

    private val circle = "c1c1c1c1-0000-4000-8000-0000000000c1"

    @After
    fun tearDown() = server.shutdown()

    private fun empty() = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"data":[],"error":null}""")

    /** The path and query [call] asked for. */
    private suspend fun path(call: suspend () -> Unit): String {
        server.enqueue(empty())
        call()
        return server.takeRequest().path!!
    }

    @Test
    fun `both lists of inactivity rules ask for check-in rules of both kinds, on every page`() = runTest {
        val kinds = "include_check_in=true&check_in_modes=once%2Cscheduled"
        assertEquals("/api/v1/inactivity-alerts?limit=100&offset=0&$kinds", path { api.getInactivityAlerts(100, 0) })
        assertEquals(
            "/api/v1/inactivity-alerts?limit=100&offset=0&cursor_day_bucket=20000&cursor_id=a-1&$kinds",
            path { api.getInactivityAlerts(limit = 100, cursorDayBucket = 20000, cursorId = "a-1") },
        )
        assertEquals("/api/v1/inactivity-alerts/circle-self-alerts?$kinds", path { api.getCircleSelfAlerts() })
        assertEquals("once,scheduled", com.pingolin.track.util.CheckInModes.JUDGED)
    }

    @Test
    fun `the circle's check-ins page from the previous page's last row`() = runTest {
        assertEquals(
            "/api/v1/circles/$circle/check-ins?limit=100&cursor_day_bucket=20000&cursor_id=k-1",
            path { api.getCircleCheckIns(circle, 100, 20000, "k-1") },
        )
        // The Check-ins screen's read is as it was.
        assertEquals("/api/v1/circles/$circle/check-ins?limit=50", path { api.getCircleCheckIns(circle) })
    }
}
