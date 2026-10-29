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
 * The town's tiles are scoped by the cities index's tile_ids, which leave out a SPLIT cell (one the tile
 * build re-exported as its 32 precision-5 children): Cupertino's tile_id is 9q9hr but its tile_ids are
 * "9q95,9q9m", and 1 Infinite Loop is in 9q9hr, so a search for "1 Infinite Loop, Cupertino" must reach
 * it. And a loaded split child must be kept: a test like `geohash4(row) in cells` throws it away,
 * because a precision-5 id never equals a geohash-4. Here: the prefix test and the geohash arithmetic it
 * rests on.
 */
class SplitCellScopeTest {

    // In 9q9hr (the cell the town's own point falls in).
    private val infiniteLoop = 37.331684 to -122.030372
    private val cupertino = 37.323 to -122.0322

    @Test
    fun `a row is in a split cell's child, and in its precision-4 parent, by prefix`() {
        assertEquals("9q9hr", geohash(infiniteLoop.first, infiniteLoop.second, 5))
        assertEquals("9q9hr", geohash(cupertino.first, cupertino.second, 5))
        assertTrue(inCells(infiniteLoop.first, infiniteLoop.second, setOf("9q9hr")))
        assertTrue(inCells(infiniteLoop.first, infiniteLoop.second, setOf("9q9h")))
        // An equality test fails here: a precision-5 id never equals a geohash-4, so the
        // loaded child would be thrown away.
        assertFalse(geohash4(infiniteLoop.first, infiniteLoop.second) in setOf("9q9hr"))
    }

    @Test
    fun `a row outside every cell is out, whatever their precisions`() {
        val cells = setOf("9q95", "9q9m", "9q9hr", "9q9")
        assertTrue("a collapsed parent covers its children", inCells(37.25, -121.95, setOf("9q9")))
        // Seattle: no cell of Cupertino's at any precision.
        assertFalse(inCells(47.6062, -122.3321, cells))
        // A sibling of the loaded child, in the same split parent, is not the child.
        assertFalse(inCells(37.36, -122.07, setOf("9q9hr")))
        assertFalse("no cells, nothing in", inCells(infiniteLoop.first, infiniteLoop.second, emptySet()))
    }

    @Test
    fun `geohash matches published values at every precision, and geohash4 is its first four`() {
        assertEquals("u4pruydqqvj", geohash(57.64911, 10.40744, 11))
        assertEquals("gbsuv7ztq", geohash(48.669, -4.329, 9))
        for ((lat, lng) in listOf(57.64911 to 10.40744, 40.7484 to -73.9857, -33.8568 to 151.2153)) {
            assertEquals(geohash(lat, lng, 4), geohash4(lat, lng))
            assertTrue(geohash(lat, lng, 6).startsWith(geohash4(lat, lng)))
        }
    }

    @Test
    fun `a cell's centre is inside it`() {
        for (cell in listOf("9q9hr", "9q9h", "9q9", "u4pruydqqvj", "s0000")) {
            val (lat, lng) = geohashCentre(cell)
            assertEquals(cell, geohash(lat, lng, cell.length))
        }
    }

    @Test
    fun `the eight neighbours are at the cell's own precision, around it, across the antimeridian`() {
        // A precision-5 cell's neighbours are precision-5 cells (the expected set for 9q9hr).
        assertEquals(
            setOf("9q9hx", "9q9k8", "9q9k2", "9q9k0", "9q9hp", "9q9hn", "9q9hq", "9q9hw"),
            geohashNeighbours("9q9hr").toSet(),
        )
        assertEquals(8, geohashNeighbours("9q9h").size)
        assertTrue(geohashNeighbours("9q9h").all { it.length == 4 && it != "9q9h" })
        // At the antimeridian the cells to the east wrap round to -180.
        val east = geohash(0.0, 179.99, 4)
        assertTrue(geohashNeighbours(east).any { geohashCentre(it).second < 0 })
    }
}
