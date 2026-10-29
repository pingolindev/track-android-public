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
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The cache key decides when a repeated search is answered from memory
 * instead of costing another round trip and another anonymous credit. Getting
 * it wrong in either direction is bad: too loose and a user who pans the map
 * keeps seeing results for the city they left; too tight and backspacing one
 * character re-queries, which is the whole case the cache exists for.
 *
 * GeocodingRepository's own key builder needs a Context, so this mirrors it.
 */
class SearchCacheKeyTest {

    private fun key(
        query: String,
        limit: Int,
        focusLat: Double?,
        focusLng: Double?,
        serverOn: Boolean,
    ): String {
        fun coord(d: Double?) = d?.let { "%.3f".format(it) } ?: "-"
        return buildString {
            append(query.trim().lowercase()); append('|')
            append(limit); append('|')
            append(coord(focusLat)); append(','); append(coord(focusLng)); append('|')
            append(serverOn)
        }
    }

    private val modestoLat = 37.6391
    private val modestoLng = -120.9969

    @Test
    fun `same query and place hits the same key`() {
        assertEquals(
            key("starbucks", 24, modestoLat, modestoLng, true),
            key("starbucks", 24, modestoLat, modestoLng, true),
        )
    }

    /** Case and stray whitespace are the same question. */
    @Test
    fun `query is normalized`() {
        assertEquals(
            key("starbucks", 24, modestoLat, modestoLng, true),
            key("  StarBucks  ", 24, modestoLat, modestoLng, true),
        )
    }

    /** Camera drift of a few metres must not miss — that is the common case. */
    @Test
    fun `tiny camera jitter still hits`() {
        assertEquals(
            key("starbucks", 24, 37.63912, -120.99688, true),
            key("starbucks", 24, 37.63914, -120.99691, true),
        )
    }

    /** A real pan must miss, or the user keeps seeing results for the area
     * they left.
     */
    @Test
    fun `panning to another area misses`() {
        assertNotEquals(
            key("starbucks", 24, modestoLat, modestoLng, true),
            key("starbucks", 24, 34.0522, -118.2437, true),
        )
    }

    /**
     * Flipping the premium toggle must not serve the other tier's answer —
     * free is viewport-scoped local data, premium is a global lookup.
     */
    @Test
    fun `toggling the server geocoder misses`() {
        assertNotEquals(
            key("starbucks", 24, modestoLat, modestoLng, true),
            key("starbucks", 24, modestoLat, modestoLng, false),
        )
    }

    /** Different fetch sizes are different questions (over-fetch vs not). */
    @Test
    fun `a different limit misses`() {
        assertNotEquals(
            key("starbucks", 24, modestoLat, modestoLng, true),
            key("starbucks", 6, modestoLat, modestoLng, true),
        )
    }

    /** No focus point (create-place) must not collide with a focused search. */
    @Test
    fun `absent focus is distinct from any focus`() {
        assertNotEquals(
            key("starbucks", 6, null, null, true),
            key("starbucks", 6, modestoLat, modestoLng, true),
        )
    }
}
