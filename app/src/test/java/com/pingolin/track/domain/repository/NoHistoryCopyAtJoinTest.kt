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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Creating or joining a circle sends a fresh position and nothing from before.
 *
 * The person's personal history is not copied into a circle they create or join,
 * as on iOS: the location notice covers sharing from the moment of joining, and a
 * burst of past points would stall live sharing and leave the oldest point as the
 * newcomer's "current" position. The kept late points (history_only, 48 h) are a
 * different path and stay.
 *
 * Source checks, like the kept-points test in [LocationRepositoryTest]: a copy
 * would run in a fire-and-forget coroutine no unit test could observe returning.
 */
class NoHistoryCopyAtJoinTest {

    private val repoDir = "src/main/java/com/pingolin/track/domain/repository"
    private val circleSrc = java.io.File("$repoDir/CircleRepository.kt").readText()
    private val locationSrc = java.io.File("$repoDir/LocationRepository.kt").readText()

    /** From [signature] to the next member function of the class. */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found — update this test with the new name", start >= 0)
        val next = Regex("\n    (?:(?:suspend|private|internal|override) )*fun ")
            .find(src, start + signature.length)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    @Test
    fun `nothing copies personal history into a circle`() {
        assertFalse(locationSrc.contains("sharePersonalHistoryWithCircle"))
        assertFalse(circleSrc.contains("sharePersonalHistoryWithCircle"))
        // A past point goes out only as a kept late point, filed as history only.
        Regex("isHistoryReplay = true").findAll(locationSrc).forEach { m ->
            val call = locationSrc.substring(m.range.first, minOf(m.range.first + 120, locationSrc.length))
            assertTrue("a past point sent as live: $call", call.contains("historyOnly = true"))
        }
    }

    @Test
    fun `create and join still send a fresh position at once`() {
        val create = functionBody(circleSrc, "suspend fun createCircle(")
        val join = functionBody(circleSrc, "suspend fun acceptInvite(")
        assertTrue(create.contains("notifyTrackingServiceCirclesChanged()"))
        assertTrue(join.contains("notifyTrackingServiceCirclesChanged()"))
        assertFalse(create.contains("fetchLocationHistory("))
        assertFalse(join.contains("fetchLocationHistory("))
    }
}
