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
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import java.io.File

/**
 * Who is responding to my own request, by name: GET /sos/{id} names who acknowledged
 * (`acknowledged_by`, oldest first, the raiser never in it) to the raiser alone; every other
 * member gets the count only. The answers below are the server's own replies, served through
 * MockWebServer and real Retrofit/Gson into a real [NotificationNames] over this device's
 * roster.
 */
class MyAlertRespondersTest {

    private companion object {
        const val RAISER = "89351dd8-e5af-4e40-9947-83e479cdc047"
        const val FIRST = "2e12fee7-0f26-4867-bfcc-58f0905a944e"
        const val SECOND = "ca25129b-f5b6-46e3-8e3f-c62844c490d9"
        const val CIRCLE = "82007c83-d324-4e8b-baf5-5f975ee5c61f"
        const val SOS = "2b6d26f0-90ba-42dc-8b5c-43f6e7e473bb"

        /** GET /sos/{id} as the raiser, after two acknowledgements. */
        const val AS_RAISER_AFTER_TWO = """{"success":true,"data":{"id":"2b6d26f0-90ba-42dc-8b5c-43f6e7e473bb","circle_id":"82007c83-d324-4e8b-baf5-5f975ee5c61f","encrypted_location":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_state":null,"status":"active","encrypted_user_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"day_bucket":20000,"encrypted_timestamp":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"resolved_day_bucket":null,"acknowledged_by_me":false,"dismissed_by_me":false,"dismissed_at":null,"acknowledgment_count":2,"acknowledged_by":["2e12fee7-0f26-4867-bfcc-58f0905a944e","ca25129b-f5b6-46e3-8e3f-c62844c490d9"]}}"""

        /** GET /sos/{id} as the raiser, before any. */
        const val AS_RAISER_BEFORE_ANY = """{"success":true,"data":{"id":"2b6d26f0-90ba-42dc-8b5c-43f6e7e473bb","circle_id":"82007c83-d324-4e8b-baf5-5f975ee5c61f","encrypted_location":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_state":null,"status":"active","encrypted_user_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"day_bucket":20000,"encrypted_timestamp":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"resolved_day_bucket":null,"acknowledged_by_me":false,"dismissed_by_me":false,"dismissed_at":null,"acknowledgment_count":0,"acknowledged_by":[]}}"""

        /** GET /sos/{id} as another member (the first acknowledger): no list. */
        const val AS_ANOTHER_MEMBER = """{"success":true,"data":{"id":"2b6d26f0-90ba-42dc-8b5c-43f6e7e473bb","circle_id":"82007c83-d324-4e8b-baf5-5f975ee5c61f","encrypted_location":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_state":null,"status":"active","encrypted_user_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"encrypted_device_id":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"day_bucket":20000,"encrypted_timestamp":{"nonce":"080808080808080808080808080808080808080808080808","ciphertext":"0707070707070707070707070707070707070707070707070707070707070707"},"resolved_day_bucket":null,"acknowledged_by_me":true,"dismissed_by_me":false,"dismissed_at":null,"acknowledgment_count":2}}"""
    }

    private lateinit var server: MockWebServer
    private lateinit var responders: MyAlertResponders
    private var roster = listOf(member(FIRST, "Fay"), member(SECOND, "Gus"))

    private fun member(userId: String, name: String, opened: Boolean = true) = CircleMemberEntity(
        circleId = CIRCLE, userId = userId, displayName = name, role = "member", joinedAt = 0L, nameOpened = opened,
    )

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)
        val memberDao = mockk<CircleMemberDao>()
        coEvery { memberDao.getByCircle(CIRCLE) } answers { roster }
        val session = mockk<SessionManager> { every { getUserId() } returns RAISER }
        val names = NotificationNames(
            circleMemberDao = memberDao,
            circleDao = mockk<CircleDao>(),
            placeDao = mockk<PlaceDao>(),
            tracksApi = api,
            cryptoManager = mockk(relaxed = true),
            sessionManager = session,
            circleRepository = dagger.Lazy { mockk<CircleRepository>(relaxed = true) },
            sosRepository = dagger.Lazy { mockk<SosRepository>() },
        )
        responders = MyAlertResponders(api, names, session)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun alert(raiser: String = RAISER) = SosAlertEntity(
        id = SOS, circleId = CIRCLE, userId = raiser, status = "active", latitude = 1.0, longitude = 2.0,
        acknowledgedBy = "", createdAt = 0L, resolvedAt = null, acknowledgmentCount = 2,
    )

    private fun serve(body: String) {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
    }

    @Test
    fun `my request names the oldest responder this device can name, and counts the others`() = runTest {
        serve(AS_RAISER_AFTER_TWO)
        assertEquals("Fay and 1 other are responding", responders.copyFor(alert()))
        assertEquals("/api/v1/sos/$SOS", server.takeRequest().path)

        // The oldest is someone this device can't name: the oldest it can (as iOS's live card).
        roster = listOf(member(FIRST, "Fay", opened = false), member(SECOND, "Gus"))
        serve(AS_RAISER_AFTER_TWO)
        assertEquals("Gus and 1 other are responding", responders.copyFor(alert()))

        // Nobody it can name: still how many.
        roster = emptyList()
        serve(AS_RAISER_AFTER_TWO)
        assertEquals("Someone and 1 other are responding", responders.copyFor(alert()))
    }

    @Test
    fun `no list, an empty one, or no answer keeps the count`() = runTest {
        // As another member the key is absent; before anyone answered it's [].
        serve(AS_ANOTHER_MEMBER)
        assertNull(responders.copyFor(alert()))
        serve(AS_RAISER_BEFORE_ANY)
        assertNull(responders.copyFor(alert()))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}"""))
        assertNull(responders.copyFor(alert()))
        // An answer about another alert is no answer.
        serve(AS_RAISER_AFTER_TWO.replace(SOS, "2b6d26f0-90ba-42dc-8b5c-43f6e7e473bc"))
        assertNull(responders.copyFor(alert()))
    }

    @Test
    fun `another member's alert isn't asked about at all`() = runTest {
        assertNull(responders.copyFor(alert(raiser = FIRST)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a notice's earlier responders are those ahead of it in the list`() = runTest {
        serve(AS_RAISER_AFTER_TWO)
        assertEquals(0, responders.earlierThan(SOS, FIRST))
        serve(AS_RAISER_AFTER_TWO)
        assertEquals(1, responders.earlierThan(SOS, SECOND.uppercase()))
        // Not on the list, or no list: unknown.
        serve(AS_RAISER_AFTER_TWO)
        assertNull(responders.earlierThan(SOS, "d1ed0000-0000-4000-8000-0000000000d1"))
        serve(AS_ANOTHER_MEMBER)
        assertNull(responders.earlierThan(SOS, FIRST))
        assertNull(responders.earlierThan(SOS, null))
    }

    @Test
    fun `the decoded list is the server's own field, each id in its one spelling`() = runTest {
        serve(AS_RAISER_AFTER_TWO.replace(SECOND, SECOND.uppercase()))
        assertEquals(listOf(FIRST, SECOND), responders.acknowledgers(SOS))
    }

    @Test
    fun `the two screens that showed only a count ask for the names, for my own alerts only`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val sheet = map.substring(map.indexOf("val tvAcknowledged = view.findViewById<TextView>(R.id.tvSosAcknowledged)"))
            .substringBefore("val tvAddress")
        assertTrue(sheet.contains("if (isMine) {") && sheet.contains("myAlertResponders.copyFor(alert)?.let {"))
        // "Mine" in the raiser id's one spelling, as the Safety list's (SosViewModel.isMyAlert).
        assertTrue(
            map.contains("val isMine = com.pingolin.track.util.AccountIds.same(alert.userId, sessionManager.getUserId())")
        )

        val fragment = File("src/main/java/com/pingolin/track/ui/sos/SosFragment.kt").readText()
        assertTrue(fragment.contains(".filter { viewModel.isMyAlert(it) && it.acknowledgmentCount > 0 }"))
        assertTrue(fragment.contains("myAlertResponders.copyFor(alert)"))

        val adapter = File("src/main/java/com/pingolin/track/ui/sos/SosAlertsAdapter.kt").readText()
        assertTrue(adapter.contains("val named = responders[alert.id]?.takeIf { isMine }"))
    }
}
