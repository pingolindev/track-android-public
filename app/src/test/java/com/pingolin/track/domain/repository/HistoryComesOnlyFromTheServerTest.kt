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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A viewer's history comes ONLY from the server's history read, which leaves out a member hiding
 * theirs. This phone caches what it reads, a newest row per member for the map, and never reads
 * that cache back as anyone's history: there are no cache readers by circle, by member or by time
 * range, and the history read has no cache-first branch. And every member list read purges what a
 * member hiding their history left here.
 */
class HistoryComesOnlyFromTheServerTest {

    private val main = "src/main/java/com/pingolin/track/"

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("found $signature", start >= 0)
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        error("unbalanced $signature")
    }

    @Test
    fun `the cache has no reader of past positions, and the history read never reads it`() {
        val repository = File(main + "domain/repository/LocationRepository.kt").readText()
        for (gone in listOf("fun getLocationHistory(", "fun observeLocationsByCircle(", "fun observeLocationsByUser(", "forceRefresh")) {
            assertFalse(gone, repository.contains(gone))
        }
        val read = body(repository, "suspend fun fetchLocationHistory(")
        assertEquals("the history read writes the cache and reads nothing from it", emptyList<String>(),
            Regex("""locationDao\.(\w+)\(""").findAll(read).map { it.groupValues[1] }.toList())

        // The one time-range reader left on the table is the tests'.
        val callers = File(main).walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText().contains("getByTimeRange(") }.map { it.name }.toList()
        assertEquals(listOf("Daos.kt"), callers)
    }

    @Test
    fun `the map's history is the history read`() {
        val map = File(main + "ui/map/MapViewModel.kt").readText()
        val fetch = body(map, "private fun fetchLocationHistory(mode: TimeRangeMode)")
        assertEquals(2, Regex("""locationRepository\.fetchLocationHistory\(""").findAll(fetch).count())
        assertFalse(map.contains("locationDao"))
    }

    @Test
    fun `every member list read purges, and every latest and history read too`() {
        val circles = File(main + "domain/repository/CircleRepository.kt").readText()
        val stored = circles.indexOf("circleMemberDao.replaceForCircle(circleId, shown)")
        val purge = circles.indexOf("locationRepository.get().purgeHiddenHistory(circleId, shown)")
        assertTrue("the list read purges after the list is stored", stored >= 0 && purge > stored)

        val repository = File(main + "domain/repository/LocationRepository.kt").readText()
        assertTrue(body(repository, "suspend fun refreshLatestLocations(").contains("purgeHiddenHistory(circleId)"))
        assertTrue(body(repository, "suspend fun fetchLocationHistory(").contains("purgeHiddenHistory(circleId)"))
    }
}
