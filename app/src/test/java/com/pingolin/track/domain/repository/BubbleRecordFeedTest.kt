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

import com.google.gson.Gson
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import io.mockk.clearAllMocks
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every privacy row this phone stores, from any path, feeds the record of how wide a bubble each
 * circle got when: the bubble the send paths use, and the server's `approximate_intervals`,
 * decoded from the server's own JSON. What doesn't read is the widest bubble.
 */
class BubbleRecordFeedTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC).toString().replace("Z", "+00:00")

    private fun row(circle: String, mode: String, radius: Int? = null, more: String = "") = Gson().fromJson(
        """{"circle_id":"$circle","sharing_mode":"$mode","approximate_radius_miles":${radius ?: "null"},""" +
            """"share_realtime":true,"share_history":true,"share_battery":true,"paused_until":null,"primary_device_id":null$more}""",
        PrivacySettingsResponse::class.java,
    )

    @Test
    fun `a bubble is recorded from the rows stored here, as wide as the send paths make it, and its end when it stops`() = runTest {
        val h = TrustHarness()
        h.repository.persistPrivacyLocally(row("c-1", "exact"))
        h.statements.now += 600
        h.repository.persistPrivacyLocally(row("c-1", "approximate", 5))
        assertEquals(5, h.approximateIntervals.radiusAt("c-1", h.statements.now * 1000))

        h.statements.now += 3_600
        h.repository.persistPrivacyLocally(row("c-1", "exact"))
        val off = h.statements.now * 1000
        assertEquals(5, h.approximateIntervals.radiusAt("c-1", off - 60_000L))
        assertNull(h.approximateIntervals.radiusAt("c-1", off + 2 * 60_000L))

        // A stored radius as the send paths take it: unset, out of range.
        h.repository.persistPrivacyLocally(row("c-2", "approximate", null))
        assertEquals(LocationRepository.DEFAULT_BUBBLE_RADIUS_MILES, h.approximateIntervals.radiusAt("c-2", off))
        h.repository.persistPrivacyLocally(row("c-3", "approximate", 0))
        assertEquals(1, h.approximateIntervals.radiusAt("c-3", off))
        h.repository.persistPrivacyLocally(row("c-4", "approximate", 90))
        assertEquals(25, h.approximateIntervals.radiusAt("c-4", off))
        // Paused from a bubble: the bubble a send after the pause still uses (sendsBubble).
        h.repository.persistPrivacyLocally(row("c-5", "paused", 7, ""","mode_before_pause":"approximate""""))
        assertEquals(7, h.approximateIntervals.radiusAt("c-5", off))
    }

    @Test
    fun `another account's sign-in takes this account's bubbles, and an erasure all - its own sign-in keeps them`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        h.repository.persistPrivacyLocally(row("c-1", "approximate", 5))
        assertEquals(5, h.approximateIntervals.radiusAt("c-1", now))

        h.repository.keepOnlyAccount(h.self) { true }
        assertEquals("its own sign-in", 5, h.approximateIntervals.radiusAt("c-1", now))
        h.repository.keepOnlyAccount("07e40000-0000-4000-8000-0000000000e7") { true }
        assertNull("another account's sign-in", h.approximateIntervals.radiusAt("c-1", now))

        h.repository.persistPrivacyLocally(row("c-1", "approximate", 5))
        h.repository.wipeAccountEntries()
        assertNull("an erasure", h.approximateIntervals.radiusAt("c-1", now))
    }

    @Test
    fun `the server's bubbles, as its JSON writes them, are recorded - one this phone never saw`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        val start = now - 5 * 3_600_000L
        val end = now - 3 * 3_600_000L
        // In a 3-mile bubble since a minute ago (a row with no bubble would end it).
        h.repository.persistPrivacyLocally(row("c-1", "approximate", 3, more = ""","approximate_until":null,"not_sharing_intervals":[],""" +
            """"approximate_intervals":[{"start":"${iso(start)}","end":"${iso(end)}","radius_miles":10},""" +
            """{"start":"${iso(now - 60_000L)}","end":null,"radius_miles":3}]"""))

        assertEquals(10, h.approximateIntervals.radiusAt("c-1", start + 3_600_000L))
        assertNull(h.approximateIntervals.radiusAt("c-1", end + 2 * 60_000L))
        assertEquals(3, h.approximateIntervals.radiusAt("c-1", now + 3_600_000L))
    }

    @Test
    fun `what doesn't read is the widest bubble, from the beginning - never 'no bubble'`() = runTest {
        val h = TrustHarness()
        val now = h.statements.now * 1000
        val end = now - 3_600_000L

        h.repository.persistPrivacyLocally(row("c-1", "exact", more = ""","approximate_intervals":[{"start":"yesterday","end":"${iso(end)}","radius_miles":4}]"""))
        assertEquals(4, h.approximateIntervals.radiusAt("c-1", now - 5 * 24 * 3_600_000L))
        assertNull("its end still reads", h.approximateIntervals.radiusAt("c-1", end + 2 * 60_000L))

        h.repository.persistPrivacyLocally(row("c-2", "exact", more = ""","approximate_intervals":[{"start":"${iso(end)}","end":null}]"""))
        assertEquals("no width", 25, h.approximateIntervals.radiusAt("c-2", now))

        h.repository.persistPrivacyLocally(row("c-3", "exact", more = ""","approximate_intervals":[null]"""))
        assertEquals(25, h.approximateIntervals.radiusAt("c-3", now - 5 * 24 * 3_600_000L))

        for ((circle, width) in listOf("c-4" to "0", "c-5" to "-3")) {
            h.repository.persistPrivacyLocally(row(circle, "exact", more = ""","approximate_intervals":[{"start":"${iso(end)}","end":null,"radius_miles":$width}]"""))
            assertEquals("a width of $width", 25, h.approximateIntervals.radiusAt(circle, now))
        }
        h.repository.persistPrivacyLocally(row("c-6", "exact", more = ""","approximate_intervals":[{"start":"${iso(end)}","end":null,"radius_miles":40}]"""))
        assertEquals(25, h.approximateIntervals.radiusAt("c-6", now))
    }
}
