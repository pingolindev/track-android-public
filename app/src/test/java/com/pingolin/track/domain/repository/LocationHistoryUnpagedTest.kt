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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEvent
import com.pingolin.track.util.UxEventBus
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager

/**
 * Location history is UNPAGED, and this is the class that holds the client to it.
 *
 * The server answers a `/locations` history request with the whole requested
 * day-bucket range, time-sliced down to a fixed point budget. It emits no
 * `next_cursor` or `has_more`, and its `limit` parameter is accepted and
 * ignored: a client-supplied row cap would truncate where the range-spanning
 * downsample does not.
 *
 * Paging fields declared WITH DEFAULTS would let Gson fill in null/false on
 * every response, so a client paging on them would page on a cursor that does
 * not exist, and a "Load more" bound to `has_more` could never appear.
 * `truncated` is the one field that DOES tell the client something was lost,
 * and it is read, so that loss is never silent.
 *
 * Assertions are on the WIRE (MockWebServer + a real Retrofit-backed [TrackApi]),
 * not on a Kotlin object: a request object can hold the right range while the
 * query string carries a default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationHistoryUnpagedTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: LocationRepository

    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val uxEventBus: UxEventBus = mockk()

    /** Every UxEvent the repository emitted, in order. */
    private val events = mutableListOf<UxEvent>()

    private val millisPerDay = 86_400_000L

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()

        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)

        every { cryptoManager.hasCircleKey(any()) } returns true
        every { sessionManager.getUserId() } returns "u-1"
        every { sessionManager.getDeviceId() } returns "d-1"
        every { uxEventBus.emit(capture(events)) } just Runs

        repository = LocationRepository(
            locationProvider = mockk<LocationProvider>(relaxed = true),
            locationDao = mockk<LocationDao>(relaxed = true),
            pendingLocationDao = mockk<PendingLocationDao>(relaxed = true),
            circleDao = mockk<CircleDao>(relaxed = true),
            privacySettingDao = mockk<PrivacySettingDao>(relaxed = true),
            tracksApi = api,
            authApi = mockk<AuthApi>(relaxed = true),
            sessionManager = sessionManager,
            // Never exercised by these tests; the stream refresh has its own suite.
            tokenRefresher = com.pingolin.track.util.TokenRefresher(sessionManager) { io.mockk.mockk(relaxed = true) },
            cryptoManager = cryptoManager,
            uxEventBus = uxEventBus,
            queueMonitor = mockk<QueueMonitor>(relaxed = true),
            circleRepository = mockk<dagger.Lazy<CircleRepository>>(relaxed = true),
            boundaryRepository = mockk<dagger.Lazy<BoundaryRepository>>(relaxed = true),
            placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true),
            currentKeyGate = CurrentKeyGate(mockk<CircleDao>(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = com.pingolin.track.util.ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    /** A history response body. `locations` empty keeps these tests about the
     *  request and the response's flags, not about decryption. */
    private fun historyBody(
        historyDaysAllowed: Int = 365,
        extra: String = "",
    ) = MockResponse().setResponseCode(200).setBody(
        "{\"success\":true,\"data\":{\"locations\":[]," +
            "\"history_days_allowed\":$historyDaysAllowed$extra},\"error\":null}"
    )

    // =========================================================================
    // One request is the whole answer
    // =========================================================================

    @Test
    fun `the history request carries no cursor and no row cap`() = runTest {
        server.enqueue(historyBody())

        repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
        )

        val url = server.takeRequest().requestUrl!!
        assertNull(
            "the server has no cursor parameter; sending one paged on a value it never returns",
            url.queryParameter("cursor")
        )
        assertNull(
            "the server documents `limit` as accepted and IGNORED — a client row cap is the " +
                "truncating design the downsample replaced",
            url.queryParameter("limit")
        )
    }

    @Test
    fun `a has_more cursor in the body never provokes a second request`() = runTest {
        // A server that claims there is more. The real one never says this,
        // and the client must not act on it if one ever did: looping on these
        // two fields would come back for page 2.
        server.enqueue(historyBody(extra = ",\"next_cursor\":\"page-2\",\"has_more\":true"))
        server.enqueue(historyBody(extra = ",\"next_cursor\":null,\"has_more\":false"))

        repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
        )

        assertEquals(
            "the response spans the whole window; there is no page 2 to fetch",
            1, server.requestCount
        )
    }

    // =========================================================================
    // truncated: the loss the server flags, which the client must surface
    // =========================================================================

    @Test
    fun `a truncated history is surfaced instead of quietly ending early`() = runTest {
        server.enqueue(historyBody(extra = ",\"truncated\":true"))

        val result = repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
        )

        assertTrue("the fetch itself succeeded", result.isSuccess)
        assertTrue(
            "the server said it dropped the oldest groups; the result has to carry that",
            result.getOrThrow().truncated
        )
        assertTrue(
            "truncation must reach the user — the field exists so the loss is never silent. " +
                "Emitted: $events",
            events.any { it is UxEvent.HistoryTruncated }
        )
    }

    @Test
    fun `an untruncated history reports no loss`() = runTest {
        server.enqueue(historyBody())

        val result = repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
        )

        assertTrue(
            "an absent `truncated` means nothing was dropped, not that something was",
            !result.getOrThrow().truncated
        )
        assertTrue(
            "no loss happened, so nothing should be announced. Emitted: $events",
            events.none { it is UxEvent.HistoryTruncated }
        )
    }

    // =========================================================================
    // The plan-clamp notice belongs to ranges the USER picked
    // =========================================================================

    private fun utcIso(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date(ms))

    @Test
    fun `a range the code chose on its own does not blame the user for the clamp`() = runTest {
        // A free account: the server clamps a 30-day request to 2 days.
        server.enqueue(historyBody(historyDaysAllowed = 2))
        val now = System.currentTimeMillis()

        repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
            startTime = utcIso(now - 30 * millisPerDay),
            endTime = utcIso(now),
            notifyRangeClamping = false,
        )

        assertTrue(
            "a background read must not pop 'your plan allows 2 days' — the user picked no " +
                "range here. Emitted: $events",
            events.none { it is UxEvent.HistoryClamped }
        )
    }

    @Test
    fun `a range the user picked still reports the plan clamp`() = runTest {
        server.enqueue(historyBody(historyDaysAllowed = 2))
        val now = System.currentTimeMillis()

        repository.fetchLocationHistory(
            circleId = LocationRepository.SELF_CIRCLE_ID,
            startTime = utcIso(now - 30 * millisPerDay),
            endTime = utcIso(now),
        )

        assertTrue(
            "suppressing the notice for background fetches must not suppress it for the " +
                "date range someone actually chose. Emitted: $events",
            events.any { it is UxEvent.HistoryClamped }
        )
    }
}
