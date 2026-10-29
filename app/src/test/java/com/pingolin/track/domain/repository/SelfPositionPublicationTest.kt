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

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's own marker comes only from `LocationRepository.selfLocation`, and
 * that is published from the location being sent (see SelfPosition's docs).
 *
 * Every send path must publish it: if only the circle send path did, a user
 * whose uploads go down the personal path would have their marker frozen
 * wherever a circle send last left it, while the app uploads correctly the whole
 * time. These tests pin both halves: that the mapping is the same for both
 * paths, and that every send path actually calls it.
 */
class SelfPositionPublicationTest {

    private fun fix(
        lat: Double = 51.5,
        lon: Double = -0.12,
        hasAltitude: Boolean = true,
        hasSpeed: Boolean = true,
        hasBearing: Boolean = true,
        hasAccuracy: Boolean = true,
    ): Location = mockk<Location>(relaxed = true).also {
        every { it.latitude } returns lat
        every { it.longitude } returns lon
        every { it.hasAltitude() } returns hasAltitude
        every { it.altitude } returns 42.0
        every { it.hasSpeed() } returns hasSpeed
        every { it.speed } returns 3.5f
        every { it.hasBearing() } returns hasBearing
        every { it.bearing } returns 270.0f
        every { it.hasAccuracy() } returns hasAccuracy
        every { it.accuracy } returns 8.0f
    }

    @Test
    fun `both send paths map an identical fix identically`() {
        // The two call sites pass the same arguments for a live send, so the
        // published position cannot differ between the circle path and the
        // personal path.
        val location = fix()
        val fromCirclePath = LocationRepository.SelfPosition.from(location, isHistoryReplay = false, nowMs = 1_000L)
        val fromPersonalPath = LocationRepository.SelfPosition.from(location, isHistoryReplay = false, nowMs = 1_000L)
        assertEquals(fromCirclePath, fromPersonalPath)
        assertNotNull(fromCirclePath)
    }

    @Test
    fun `a live fix maps every field across`() {
        val p = LocationRepository.SelfPosition.from(fix(), isHistoryReplay = false, nowMs = 7_000L)!!
        assertEquals(51.5, p.latitude, 0.0)
        assertEquals(-0.12, p.longitude, 0.0)
        assertEquals(42.0, p.altitude!!, 0.0)
        assertEquals(3.5, p.speed!!, 0.0001)
        assertEquals(270.0, p.heading!!, 0.0001)
        assertEquals(8.0, p.accuracy!!, 0.0001)
        // Stamped with NOW, not the fix's own time: this is "where I am", and
        // the marker's freshness is what the map reasons about.
        assertEquals(7_000L, p.timestamp)
    }

    @Test
    fun `a history replay publishes nothing`() {
        // Re-encrypting past points for a newly-joined circle must not drag the
        // self marker backwards through history stamped "just now".
        assertNull(LocationRepository.SelfPosition.from(fix(), isHistoryReplay = true))
    }

    @Test
    fun `absent optional fields become null rather than fabricated zeroes`() {
        val p = LocationRepository.SelfPosition.from(
            fix(hasAltitude = false, hasSpeed = false, hasBearing = false, hasAccuracy = false),
            isHistoryReplay = false,
        )!!
        assertNull(p.altitude)
        assertNull(p.speed)
        assertNull(p.heading)
        assertNull(p.accuracy)
    }

    /**
     * A source-scan tripwire, in the style the codebase already uses
     * elsewhere.
     *
     * A send path that never publishes at all is invisible to any behavioural
     * test of the mapping. This asserts that the personal send path still
     * contains the publish call, so a future edit that drops it fails here
     * rather than on someone's map.
     */
    @Test
    fun `every send path still publishes the self position`() {
        val src = java.io.File(
            "src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt"
        ).readText()

        // The publish must go through the one helper: a call site rolling its
        // own assignment lets the two paths drift apart.
        val assignments = Regex("""_selfLocation\.value\s*=""").findAll(src).count()
        assertEquals(
            "_selfLocation should be assigned only inside publishSelfPosition and the reset; " +
                "a send path assigning it directly is how the paths drifted apart",
            2, assignments,
        )

        fun bodyOf(signature: String, chars: Int = 2500): String {
            val start = src.indexOf(signature)
            assertTrue("$signature not found — update this test with the new name", start >= 0)
            // Enough of the function to cover its opening work.
            return src.substring(start, minOf(start + chars, src.length))
        }

        // The envelope path is two public entry points — a history send
        // and a liveness check-in — over one implementation. Both must
        // reach it, and it must publish: a check-in that skipped the
        // publish would freeze the user's own marker for exactly as long
        // as they stood still.
        assertTrue(
            "sendEnvelopeLocation (circle path) must go through sendEnvelope",
            bodyOf("suspend fun sendEnvelopeLocation").contains("sendEnvelope("),
        )
        assertTrue(
            "sendEnvelopeLivenessRefresh (check-in path) must go through sendEnvelope",
            bodyOf("suspend fun sendEnvelopeLivenessRefresh").contains("sendEnvelope("),
        )
        assertTrue(
            "sendEnvelope (the shared implementation) must publish the self position",
            bodyOf("private suspend fun sendEnvelope(").contains("publishSelfPosition("),
        )
        assertTrue(
            "sendSelfTrackingLocation (personal path) must publish the self position — " +
                "it did not, and personal-only users saw a frozen marker",
            bodyOf("suspend fun sendSelfTrackingLocation").contains("publishSelfPosition("),
        )
    }
}
