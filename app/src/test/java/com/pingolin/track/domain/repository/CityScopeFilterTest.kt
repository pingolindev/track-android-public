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

/**
 * Pins `geohash4`, the cell encoder the fallback uses to keep results inside the
 * city it says it searched.
 *
 * WHY THIS IS WORTH TESTING
 *   A wrong `geohash4` does not crash and does not error — it silently filters
 *   every hit out, so "search this city" returns nothing and reads as missing
 *   data. The opposite failure is no filter at all: a hint that mis-resolved to
 *   Hohhot would return Pink's Bakery in Seattle under an "In Hohhot, 20"
 *   header, on a device whose map was in Modesto. The header and the rows would
 *   disagree and nothing in the UI could reveal it.
 *
 *   Expected values were cross-checked two ways before being written down:
 *   against published geohashes (the u4pruydqqvj / gbsuv7ztq examples) and
 *   against an independent textbook implementation of the bit interleave. The
 *   Kotlin and Swift copies are retypes of each other, so "it matches the other
 *   platform" would not catch a shared mistake.
 */
class CityScopeFilterTest {

    @Test
    fun `geohash4 matches published values`() {
        assertEquals("u4pr", geohash4(57.64911, 10.40744))
        assertEquals("gbsu", geohash4(48.669, -4.329))
        assertEquals("s000", geohash4(0.0, 0.0))
        assertEquals("dr5r", geohash4(40.7484, -73.9857))   // Empire State Building
        assertEquals("r3gx", geohash4(-33.8568, 151.2153))  // Sydney Opera House
    }

    @Test
    fun `geohash4 is always four characters`() {
        // Precision must not vary at the poles or across the antimeridian, or the
        // `in cityTiles` test compares strings of different lengths and never hits.
        for ((lat, lng) in listOf(90.0 to 180.0, -90.0 to -180.0, 0.0 to 179.9999)) {
            assertEquals(4, geohash4(lat, lng).length)
        }
    }

    /**
     * A Seattle result must not pass a Modesto cell set.
     */
    @Test
    fun `a far away hit does not fall in the wrong citys cells`() {
        val modestoCell = geohash4(37.6391, -120.9969)
        val seattleCell = geohash4(47.6062, -122.3321)
        assertFalse(
            "Seattle and Modesto must not share a precision-4 cell",
            modestoCell == seattleCell,
        )
        val cityTiles = setOf(modestoCell)
        assertTrue(geohash4(37.6391, -120.9969) in cityTiles)
        assertFalse(geohash4(47.6062, -122.3321) in cityTiles)
    }

    /**
     * A precision-4 cell is roughly 39 x 19.5 km, so a neighbouring town can
     * legitimately share the city's cell. That is expected and is why the filter
     * is "in the city's OWN cells" rather than a radius — over-inclusion inside a
     * cell is fine, cross-region leakage is not.
     */
    @Test
    fun `a nearby town may legitimately share a cell`() {
        // Salida shares Modesto's cell; Oakdale, 12 km further out, does NOT. Both
        // values computed independently before being written down — an assumed
        // "nearby means same cell" is wrong often enough to be worth pinning.
        assertEquals(geohash4(37.6391, -120.9969), geohash4(37.7060, -121.0850))
        assertFalse(geohash4(37.6391, -120.9969) == geohash4(37.7666, -120.8471))
    }
}
