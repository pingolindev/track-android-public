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

package com.pingolin.track.ui.checkin

import com.google.gson.JsonParser
import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.testutil.InMemoryPendingChangeDao
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * "Checking In" is a plain check-in: "general", as iOS sends it. It never sends emergency_ok,
 * which the whole circle reads as "…has confirmed they are OK.", an all-clear.
 */
class CheckInChoicesTest {

    @Test
    fun `each choice sends its own type, and Checking In and a message are plain check-ins`() {
        assertEquals(
            listOf("I'm Safe" to "safe_arrival", "Arrived" to "arrived", "Checking In" to "general"),
            CheckInChoices.QUICK.map { it.label to it.type },
        )
        assertEquals("general", CheckInChoices.CHECKING_IN)
        assertEquals("general", CheckInChoices.MESSAGE_TYPE)
        assertFalse("no choice sends the all-clear", CheckInChoices.QUICK.any { it.type == "emergency_ok" })
    }

    @Test
    fun `the list names each type, and an all-clear from the web or an older app as one`() {
        assertEquals("💬" to "Checking In", CheckInChoices.label("general"))
        assertEquals("✅" to "I'm Safe", CheckInChoices.label("safe_arrival"))
        assertEquals("🏠" to "Arrived", CheckInChoices.label("arrived"))
        assertEquals("✅" to "All Clear", CheckInChoices.label("emergency_ok"))
        assertEquals("💬" to "Check-In", CheckInChoices.label("something new"))
    }

    // ===== on the wire =====

    private val server = MockWebServer().apply {
        startOnIpv4Loopback()
        // The answer doesn't matter here: the request body is the whole point.
        repeat(2) { enqueue(MockResponse().setResponseCode(500).setBody("""{"success":false,"error":"x"}""")) }
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    /** Every position sealed for a check-in, in order. */
    private val sealed = mutableListOf<uniffi.pingolin_mobile.Location>()

    private fun repository(
        pauses: com.pingolin.track.util.PauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        bubbles: com.pingolin.track.util.ApproximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    ): CheckInRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)
        val crypto = mockk<CryptoManager>(relaxed = true) {
            every { encryptDataV2(any(), any(), any()) } returns EncryptedData("bm9uY2U=", "Y2lwaGVy")
            every { encryptLocation(any(), any()) } answers {
                sealed += secondArg<uniffi.pingolin_mobile.Location>()
                EncryptedData("bm9uY2U=", "Y2lwaGVy")
            }
        }
        val session = mockk<SessionManager>(relaxed = true) {
            every { getUserId() } returns "me"
            every { getDeviceId() } returns "device-1"
        }
        val privacy = mockk<PrivacySettingDao>().also { dao ->
            coEvery { dao.get(any()) } returns PrivacySettingEntity(circleId = "c-1", sharingMode = "exact", updatedAt = 0)
        }
        val circles = mockk<CircleRepository>(relaxed = true) {
            coEvery { requireCurrentKey("c-1") } returns Unit
            coEvery { ensurePrivacyPrimed(any()) } returns true
        }
        val pending = PendingChanges(
            InMemoryPendingChangeDao(), session, mockk(relaxed = true),
            mockk<CircleDao> { coEvery { getById(any()) } returns CircleEntity("c-1", "Family", null, 0, 0) },
            mockk<CircleMemberDao>(relaxed = true),
        )
        return CheckInRepository(api, mockk<CheckInDao>(relaxed = true), session, crypto, privacy, circles, pending, pauses, bubbles)
    }

    private fun sentType(): String =
        JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["check_in_type"].asString

    @Test
    fun `a check-in made during a pause of its circle goes, but without its position there, however late`() = runTest {
        val pauses = com.pingolin.track.util.PauseIntervals.inMemory()
        val now = System.currentTimeMillis()
        // Paused 10:00-12:00 by another phone, while this one was offline; the circle shares again now.
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(now - 4 * 3_600_000L, now - 2 * 3_600_000L),
        ))
        val checkIns = repository(pauses)
        fun sentLocation() = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["encrypted_location"]

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 3 * 3_600_000L)
        assertTrue("made during the pause: no position", sentLocation().let { it == null || it.isJsonNull })

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 3_600_000L)
        assertTrue("made after it: its position", sentLocation().let { it != null && !it.isJsonNull })
    }

    @Test
    fun `a check-in made just after a resume made here carries its position, and one that waited the record can't place yet goes without`() = runTest {
        val pauses = com.pingolin.track.util.PauseIntervals.inMemory()
        val now = System.currentTimeMillis()
        pauses.observe("c-1", notSharing = true, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(now - 3_600_000L, null),
        ))
        // Resumed just now, here.
        pauses.resumedHere("c-1")
        pauses.observe("c-1", notSharing = false, deadlineMs = null)
        val checkIns = repository(pauses)
        fun sentLocation() = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["encrypted_location"]

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3)
        assertTrue("made just now: its position", sentLocation().let { it != null && !it.isJsonNull })

        // One that waited, from before any privacy row was read here: no position, and no wait.
        val unread = repository(com.pingolin.track.util.PauseIntervals.inMemory())
        unread.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 10 * 60_000L)
        assertTrue(sentLocation().let { it == null || it.isJsonNull })
    }

    @Test
    fun `a check-in made in the last two minutes of a pause goes without its position after a resume seen from elsewhere`() = runTest {
        val pauses = com.pingolin.track.util.PauseIntervals.inMemory()
        val now = System.currentTimeMillis()
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(now - 3_600_000L, now),
        ))
        val checkIns = repository(pauses)

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 90_000L)
        val location = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject["encrypted_location"]
        assertTrue(location == null || location.isJsonNull)
    }

    @Test
    fun `a check-in made inside a bubble goes in it, even sent after a switch back to exact`() = runTest {
        val bubbles = com.pingolin.track.util.ApproximateIntervals.inMemory()
        val now = System.currentTimeMillis()
        // A 5-mile bubble 10:00-12:00, set on another phone; the circle is exact again now.
        bubbles.observe("c-1", radiusMiles = null, deadlineMs = null, server = listOf(
            com.pingolin.track.util.ApproximateIntervals.Span(now - 4 * 3_600_000L, now - 2 * 3_600_000L, 5),
        ))
        // Late check-ins need a privacy row read after them.
        val pauses = com.pingolin.track.util.PauseIntervals.inMemory()
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = emptyList())
        val checkIns = repository(pauses, bubbles)

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 3 * 3_600_000L)
        server.takeRequest()
        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = now - 3_600_000L)
        server.takeRequest()

        val inBubble = com.pingolin.track.domain.repository.LocationRepository.coarsenLocation(
            52.1, 4.3, 5 * com.pingolin.track.domain.repository.LocationRepository.METERS_PER_MILE,
        )
        assertEquals(2, sealed.size)
        assertEquals(inBubble.first, sealed[0].latitude, 0.0)
        assertEquals(inBubble.second, sealed[0].longitude, 0.0)
        assertEquals("made after it: exactly", 52.1, sealed[1].latitude, 0.0)
    }

    @Test
    fun `a check-in that waited, made at a time the server hasn't told this phone of, goes in the widest bubble`() = runTest {
        val pauses = com.pingolin.track.util.PauseIntervals.inMemory()
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = emptyList())
        val checkIns = repository(pauses, com.pingolin.track.util.ApproximateIntervals.inMemory())

        checkIns.createCheckIn("c-1", "general", latitude = 52.1, longitude = 4.3, checkedInAt = System.currentTimeMillis() - 10 * 60_000L)
        server.takeRequest()

        val widest = com.pingolin.track.domain.repository.LocationRepository.coarsenLocation(
            52.1, 4.3, 25 * com.pingolin.track.domain.repository.LocationRepository.METERS_PER_MILE,
        )
        assertEquals(widest.first, sealed.single().latitude, 0.0)
    }

    @Test
    fun `Checking In and a check-in in the person's words go out as general`() = runTest {
        val checkIns = repository()

        checkIns.createQuickCheckIn("c-1", CheckInChoices.CHECKING_IN)
        assertEquals("general", sentType())

        checkIns.createCheckIn("c-1", CheckInChoices.MESSAGE_TYPE, message = "On my way", latitude = null, longitude = null)
        assertEquals("general", sentType())
    }
}
