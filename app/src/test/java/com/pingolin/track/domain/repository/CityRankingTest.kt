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
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CityResult

/**
 * Ranking policy for city results: prominence, discounted by distance from
 * what the user is looking at, plus a bonus for their own country.
 *
 * Every row below carries the real population and coordinates from the live
 * cities index, so these are the actual orderings a user sees — not a
 * hand-picked arithmetic that only works on invented numbers.
 */
class CityRankingTest {

    // Akron, OH — the focus point used throughout this file.
    private val akronLat = 41.0814
    private val akronLng = -81.5190

    private fun city(
        name: String,
        cc: String,
        pop: Long,
        lat: Double,
        lng: Double,
        admin1: String = "",
    ) = CityResult(
        geonameId = name.hashCode().toLong() + cc.hashCode() + admin1.hashCode(),
        name = name,
        asciiName = name,
        countryCode = cc,
        admin1 = admin1,
        population = pop,
        lat = lat,
        lng = lng,
        tileId = "aaaa",
        tileIds = emptyList(),
        hasShard = false,
    )

    private fun rank(rows: List<CityResult>, home: String? = "US") =
        rankCities(rows, akronLat, akronLng, home)

    /** The headline ask: the nearby one wins even though it is the smallest. */
    @Test
    fun `nearest same-name city outranks bigger distant ones`() {
        val ohio = city("Springfield", "US", 58_662, 39.9242, -83.8088, "OH")
        val rows = listOf(
            city("Springfield", "US", 169_176, 37.2153, -93.2982, "MO"),
            city("Springfield", "US", 155_929, 42.1015, -72.5898, "MA"),
            city("Springfield", "US", 116_250, 39.7990, -89.6440, "IL"),
            ohio,
        )
        assertEquals("OH", rank(rows).first().admin1)
    }

    /** Home country outranks a foreign city five times its size. */
    @Test
    fun `home country beats a larger foreign homonym`() {
        val rows = listOf(
            city("Birmingham", "GB", 1_144_919, 52.4814, -1.8998, "ENG"),
            city("Birmingham", "US", 212_237, 33.5207, -86.8025, "AL"),
        )
        val out = rank(rows)
        assertEquals("US", out[0].countryCode)
        assertEquals("GB", out[1].countryCode)
    }

    /**
     * ...but prominence is not smothered: a 10.9M city on another continent
     * must still beat an 11k one at home.
     */
    @Test
    fun `a vastly larger foreign city still wins`() {
        val rows = listOf(
            city("Delhi", "US", 11_651, 37.4319, -120.7783, "CA"),
            city("Delhi", "IN", 10_927_986, 28.6667, 77.2167, "07"),
        )
        assertEquals("IN", rank(rows).first().countryCode)
    }

    /** The case the country tier exists for still holds. */
    @Test
    fun `London GB still beats London OH from Ohio`() {
        val rows = listOf(
            city("London", "US", 10_060, 39.8865, -83.4483, "OH"),
            city("London", "GB", 8_961_989, 51.5085, -0.1257, "ENG"),
        )
        assertEquals("GB", rank(rows).first().countryCode)
    }

    /** Standing in the small one flips it — proximity is what changed. */
    @Test
    fun `being in the small town promotes it over the world-famous one`() {
        val parisTx = city("Paris", "US", 24_782, 33.6609, -95.5555, "TX")
        val parisFr = city("Paris", "FR", 2_138_551, 48.8534, 2.3488, "11")

        val fromOhio = rankCities(listOf(parisTx, parisFr), akronLat, akronLng, "US")
        assertEquals("far away, France wins", "FR", fromOhio.first().countryCode)

        val fromParisTx = rankCities(listOf(parisTx, parisFr), 33.6609, -95.5555, "US")
        assertEquals("standing in it, Texas wins", "US", fromParisTx.first().countryCode)
    }

    /** No focus point (create-place dialog): country bonus alone still applies. */
    @Test
    fun `without a focus point the country bonus still orders results`() {
        val rows = listOf(
            city("Birmingham", "GB", 1_144_919, 52.4814, -1.8998, "ENG"),
            city("Birmingham", "US", 212_237, 33.5207, -86.8025, "AL"),
        )
        assertEquals("US", rankCities(rows, null, null, "US").first().countryCode)
    }

    /** No context at all degrades to the index's own population order. */
    @Test
    fun `with neither signal it is plain population order`() {
        val rows = listOf(
            city("Birmingham", "US", 212_237, 33.5207, -86.8025, "AL"),
            city("Birmingham", "GB", 1_144_919, 52.4814, -1.8998, "ENG"),
        )
        assertEquals("GB", rankCities(rows, null, null, null).first().countryCode)
    }

    /** A GB user gets the mirror-image answer — the bonus is not US-shaped. */
    @Test
    fun `the country bonus follows the user, not a hardcoded region`() {
        val rows = listOf(
            city("Birmingham", "US", 212_237, 33.5207, -86.8025, "AL"),
            city("Birmingham", "GB", 1_144_919, 52.4814, -1.8998, "ENG"),
        )
        // London-based user, GB device.
        assertEquals("GB", rankCities(rows, 51.5085, -0.1257, "GB").first().countryCode)
    }

    @Test
    fun `zero population rows do not blow up the score`() {
        val rows = listOf(
            city("Nowhere", "US", 0, 41.0, -81.5),
            city("Somewhere", "US", 5_000, 41.1, -81.6),
        )
        val out = rank(rows)
        assertEquals(2, out.size)
        assertTrue(out.all { it.population >= 0 })
    }

    @Test
    fun `a single row is returned untouched`() {
        val only = listOf(city("Akron", "US", 197_000, 41.0814, -81.5190, "OH"))
        assertEquals(only, rank(only))
    }
}
