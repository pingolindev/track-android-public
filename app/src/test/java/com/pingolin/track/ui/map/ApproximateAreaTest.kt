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

package com.pingolin.track.ui.map

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A member sharing an approximate location is an area, never a pin: the rule, the
 * size and the shape the map draws.
 */
class ApproximateAreaTest {

    private val lat = 37.33490
    private val lng = -122.00900

    /** Great-circle distance in meters. */
    private fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(a))
    }

    @Test
    fun `a member the server reports as approximate is an area of 1_25 times their radius`() {
        assertEquals(MemberSpot.Approximate(1.25 * 2 * 1609.344), MemberSpot.of(isSelf = false, approximateRadiusMiles = 2))
    }

    @Test
    fun `this phone is never an area, and a member reported as anything else is a pin`() {
        assertEquals(MemberSpot.Exact, MemberSpot.of(isSelf = true, approximateRadiusMiles = 3))
        assertEquals(MemberSpot.Exact, MemberSpot.of(isSelf = false, approximateRadiusMiles = null))
    }

    @Test
    fun `nothing finer than the area is shown for an approximate member, the point for an exact one`() {
        assertFalse(MemberSpot.of(isSelf = false, approximateRadiusMiles = 1).showsPoint)
        assertTrue(MemberSpot.of(isSelf = false, approximateRadiusMiles = null).showsPoint)
        assertTrue(MemberSpot.of(isSelf = true, approximateRadiusMiles = 1).showsPoint)
    }

    @Test
    fun `the area holds the person at every radius, even when the server sends its lowest value`() {
        // The sender snaps to a grid of cell r, so the person is within r / sqrt(2) of the point.
        // The server sends round(r * (1 ± 0.2)), never below 1.
        for (r in 1..25) {
            val lowest = Math.round(r * 0.8).toInt().coerceAtLeast(1)
            val area = ApproximateArea.radiusMeters(lowest)
            val farthest = r * ApproximateArea.METERS_PER_MILE / Math.sqrt(2.0)
            assertTrue("r=$r: area $area m < $farthest m", area >= farthest)
        }
    }

    @Test
    fun `the outline is a closed ring whose every point is the area's radius from the centre`() {
        val radius = ApproximateArea.radiusMeters(1)
        val ring = ApproximateArea.ring(lat, lng, radius)

        assertEquals(ApproximateArea.RING_SEGMENTS + 1, ring.size)
        assertEquals(ring.first().toList(), ring.last().toList())
        for (p in ring) {
            assertEquals(radius, meters(lat, lng, p[1], p[0]), radius * 0.001)
        }
    }

    @Test
    fun `the box holds the whole outline, so fitting everyone fits every area`() {
        val radius = ApproximateArea.radiusMeters(5)
        val box = ApproximateArea.box(lat, lng, radius)

        for (p in ApproximateArea.ring(lat, lng, radius)) {
            assertTrue(p[1] in box.south..box.north)
            assertTrue(p[0] in box.west..box.east)
        }
        // North-south it reaches the radius each way (about 111.2 km a degree).
        assertEquals(radius, meters(lat, lng, box.north, lng), radius * 0.001)
        assertEquals(radius, meters(lat, lng, box.south, lng), radius * 0.001)
    }

    @Test
    fun `the feature is a GeoJSON polygon in plain decimals, coloured like the member`() {
        val json = ApproximateArea.featureJson("#6D4AFF", lat, lng, ApproximateArea.radiusMeters(1))

        val feature = JsonParser.parseString(json).asJsonObject
        assertEquals("Feature", feature["type"].asString)
        assertEquals("#6D4AFF", feature["properties"].asJsonObject["color"].asString)
        val geometry = feature["geometry"].asJsonObject
        assertEquals("Polygon", geometry["type"].asString)
        val ring = geometry["coordinates"].asJsonArray[0].asJsonArray
        assertEquals(ApproximateArea.RING_SEGMENTS + 1, ring.size())
        assertEquals(ring[0], ring[ring.size() - 1])
        // MapLibre's GeoJSON parser rejects scientific notation, silently.
        assertFalse(json.contains(Regex("[0-9][eE][-+]?[0-9]")))
    }
}
