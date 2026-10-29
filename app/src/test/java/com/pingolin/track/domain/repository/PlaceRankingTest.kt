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
import uniffi.pingolin_mobile.GeocodingResult

/**
 * Ranking applied to server-side geocoder results.
 *
 * Same policy as [rankCities] minus prominence, which the server cannot
 * supply — see the note on [rankPlaces]. Coordinates below are real, and the
 * focus point throughout is Modesto, CA.
 */
class PlaceRankingTest {

    private val modestoLat = 37.6391
    private val modestoLng = -120.9969

    private fun place(
        name: String,
        lat: Double,
        lng: Double,
        country: String? = "USA",
        type: String = "poi",
        address: String? = null,
    ) = GeocodingResult(
        text = name,
        lat = lat,
        lng = lng,
        resultType = type,
        confidence = 1.0,
        name = name,
        city = null,
        state = null,
        postcode = null,
        country = country,
        address = address,
    )

    private fun rank(rows: List<GeocodingResult>, home: String? = "US") =
        rankPlaces(rows, modestoLat, modestoLng, home)

    /**
     * Why ranking can't be on distance alone, with the real rows the gateway
     * returns for "pinks hot dogs" from Modesto: the nearest hot-dog places
     * are Mark's (80 km) and Casper's (82 km), and every actual Pink's is
     * ~420 km away. Ranking on distance alone puts Mark's first.
     */
    @Test
    fun `a name that matches the query beats a nearer one that does not`() {
        val marks = place("Mark's Hot Dogs", 37.3382, -121.8863)      // San Jose, 80 km
        val caspers = place("Casper's Hot Dogs", 37.7022, -121.9358)  // Dublin, 82 km
        val pinksFar = place("Pink's Hot Dogs", 34.0928, -118.3287)   // LA, ~460 km
        val pinksNearer = place("Pink's Hot Dogs", 34.2164, -119.0376) // Camarillo, ~418 km

        val out = rankPlaces(
            listOf(marks, caspers, pinksFar, pinksNearer),
            modestoLat, modestoLng, "US", query = "pinks hot dogs",
        )
        assertEquals("the nearer Pink's leads", pinksNearer.lat, out[0].lat, 1e-9)
        assertEquals("then the other Pink's", pinksFar.lat, out[1].lat, 1e-9)
        assertTrue(
            "non-matching names must fall below every match",
            out.drop(2).all { it.name!!.contains("Mark") || it.name!!.contains("Casper") },
        )
    }

    /** Apostrophes must not split the token, or the exact hit sinks. */
    @Test
    fun `Pink's matches a query typed as pinks`() {
        val pinks = place("Pink's Hot Dogs", 34.0928, -118.3287)
        val other = place("Hot Dogs Plus", 37.65, -121.0)   // 1 km away
        val out = rankPlaces(listOf(other, pinks), modestoLat, modestoLng, "US", "pinks hot dogs")
        assertEquals("Pink's Hot Dogs", out[0].name)
    }

    /** With no query text the ranking degrades to pure geography, not chaos. */
    @Test
    fun `an empty query leaves geography in charge`() {
        val far = place("A", 34.0522, -118.2437)
        val near = place("B", 37.6688, -120.9970)
        assertEquals("B", rankPlaces(listOf(far, near), modestoLat, modestoLng, "US", "")[0].name)
    }

    /** The headline behaviour: among equal matches, the nearest branch wins. */
    @Test
    fun `nearest place ranks first`() {
        val far = place("Starbucks", 34.0522, -118.2437)   // Los Angeles, ~450 km
        val near = place("Starbucks", 37.6688, -120.9970)  // Modesto, ~3 km
        val mid = place("Starbucks", 37.9577, -121.2908)   // Stockton, ~40 km
        val out = rank(listOf(far, mid, near))
        assertEquals(near.lat, out[0].lat, 1e-9)
        assertEquals(mid.lat, out[1].lat, 1e-9)
        assertEquals(far.lat, out[2].lat, 1e-9)
    }

    /** Home country outranks a nearer foreign place across a border. */
    @Test
    fun `home country outranks a somewhat nearer foreign place`() {
        // Tijuana MX ~750 km from Modesto; Portland OR ~900 km.
        val foreignNearer = place("Pharmacy", 32.5149, -117.0382, country = "MEX")
        val homeFarther = place("Pharmacy", 45.5152, -122.6784, country = "USA")
        assertEquals("USA", rank(listOf(foreignNearer, homeFarther))[0].country)
    }

    /** ...but the bonus is one decade of distance, not infinite. */
    @Test
    fun `a much nearer foreign place still wins`() {
        // 30 km foreign vs 3000 km home: two decades apart, bonus is worth one.
        val foreignVeryNear = place("Cafe", 37.7, -121.0, country = "MEX")
        val homeVeryFar = place("Cafe", 40.7128, -74.0060, country = "USA")
        assertEquals("MEX", rank(listOf(foreignVeryNear, homeVeryFar))[0].country)
    }

    /**
     * The geocoder labels rows alpha-3 ("USA") while the device region is
     * alpha-2 ("US"). Prefix matching would be wrong — "CHN".startsWith("CH")
     * is true — so this pins the proper resolution.
     *
     * Discriminating case: Tijuana MX is 674 km away, Portland OR 887 km — a
     * 1.3x gap, well inside the one decade the bonus is worth, so the bonus is
     * what decides. That only happens if "US" actually resolved to "USA";
     * without the bonus the nearer Mexican row wins, which is the control.
     */
    @Test
    fun `alpha-2 device region matches alpha-3 rows`() {
        val usa = place("Shop", 45.5152, -122.6784, country = "USA")   // Portland, 887 km
        val mex = place("Shop", 32.5149, -117.0382, country = "MEX")   // Tijuana, 674 km
        val ranked = rankPlaces(listOf(mex, usa), modestoLat, modestoLng, "US")
        val withoutHome = rankPlaces(listOf(mex, usa), modestoLat, modestoLng, null)
        assertEquals("US must resolve to USA and lift that row", "USA", ranked[0].country)
        assertEquals("control: with no region, the nearer row wins", "MEX", withoutHome[0].country)
    }

    /** A Swiss device must NOT treat Chinese rows as home. */
    @Test
    fun `CH does not match CHN`() {
        val china = place("Shop", 39.9042, 116.4074, country = "CHN")
        val swiss = place("Shop", 47.3769, 8.5417, country = "CHE")
        assertEquals("CHE", rankPlaces(listOf(china, swiss), 47.3769, 8.5417, "CH")[0].country)
    }

    @Test
    fun `no focus point leaves the country bonus doing the work`() {
        val foreign = place("Shop", 48.8566, 2.3522, country = "FRA")
        val home = place("Shop", 40.7128, -74.0060, country = "USA")
        assertEquals("USA", rankPlaces(listOf(foreign, home), null, null, "US")[0].country)
    }

    @Test
    fun `with no context the order is left alone`() {
        val a = place("A", 10.0, 10.0, country = "FRA")
        val b = place("B", 20.0, 20.0, country = "DEU")
        assertEquals(listOf("A", "B"), rankPlaces(listOf(a, b), null, null, null).map { it.name })
    }

    @Test
    fun `a single row is returned untouched`() {
        val only = listOf(place("Solo", 37.6, -121.0))
        assertTrue(rank(only) === only)
    }

    @Test
    fun `rows with no country are ranked on distance alone and not dropped`() {
        val near = place("X", 37.66, -121.0, country = null)
        val far = place("Y", 40.71, -74.0, country = null)
        val out = rank(listOf(far, near))
        assertEquals(2, out.size)
        assertEquals("X", out[0].name)
    }

    /**
     * The source carries real duplicates: "pinks hot dogs" returns two records
     * for 201 Universal City Plaza. A row showing its street drops the
     * neighbourhood, so without this they render as two identical lines.
     */
    @Test
    fun `two records at the same address collapse to one`() {
        val rows = listOf(
            place("Pink's Hot Dogs", 34.1381, -118.3534, address = "201 Universal City Plaza"),
            place("Pink's Hot Dogs", 34.1370, -118.3520, address = "201 Universal City Plaza"),
            place("Pink's Hot Dogs", 34.0836, -118.3444, address = "709 N La Brea Ave"),
        )
        val out = rank(rows)
        assertEquals(2, out.size)
        assertEquals(
            setOf("709 N La Brea Ave", "201 Universal City Plaza"),
            out.mapNotNull { it.address }.toSet(),
        )
    }

    /** The survivor must be the best-ranked one, not whichever arrived first. */
    @Test
    fun `the nearer of two same-address records is the one kept`() {
        val far = place("Pink's Hot Dogs", 34.1381, -118.3534, address = "201 Universal City Plaza")
        val near = place("Pink's Hot Dogs", 37.65, -121.00, address = "201 Universal City Plaza")
        val out = rank(listOf(far, near))
        assertEquals(1, out.size)
        assertEquals("the closer row to Modesto must survive", 37.65, out[0].lat, 1e-6)
    }

    /**
     * Rows with no street must never be collapsed: a blank address is not
     * evidence two outlets are the same place, and merging them would hide one.
     */
    @Test
    fun `same-named rows without a street are all kept`() {
        val rows = listOf(
            place("Togo's", 37.64, -121.00),
            place("Togo's", 37.70, -121.10),
            place("Togo's", 37.80, -121.20),
        )
        assertEquals(3, rank(rows).size)
    }

    /** Different addresses are different outlets, however similar the name. */
    @Test
    fun `same name at different addresses are all kept`() {
        val rows = listOf(
            place("Pink's Hot Dogs", 34.0836, -118.3444, address = "709 N La Brea Ave"),
            place("Pink's Hot Dogs", 34.2000, -119.0000, address = "630 Ventura Blvd Ste 1215"),
            place("Pink's Hot Dogs", 33.8300, -118.3400, address = "3525 W Carson St"),
        )
        assertEquals(3, rank(rows).size)
    }

    /** Address ROWS share street lines legitimately; only POIs collapse. */
    @Test
    fun `address rows are never collapsed`() {
        val rows = listOf(
            place("100 Main St", 37.64, -121.00, type = "address", address = "100 Main St"),
            place("100 Main St", 37.70, -121.10, type = "address", address = "100 Main St"),
        )
        assertEquals(2, rank(rows).size)
    }

    /**
     * With the map centred on Modesto, searching "in n out" must not list an
     * In-N-Out in Santa Ana (326 mi), one in Phoenix (584 mi) or one in Crane,
     * Missouri (1506 mi) ABOVE every In-N-Out Burger in California, the nearest
     * of which is 40 mi away.
     *
     * The shorter name equals the query exactly, but the exact tier must not
     * gate distance absolutely: enough proximity has to be able to beat it.
     */
    @Test
    fun `a nearer fuller name beats an exact name far away`() {
        val santaAna = place("In-N-Out", 33.7455, -117.8677)        // 517 km
        val crane = place("In-N-Out", 36.9045, -93.5674)            // 2419 km
        val phoenix = place("IN-N-OUT", 33.4734, -112.0447)         // 933 km
        val livermore = place("In-N-Out Burger", 37.6819, -121.7680) // 69 km
        val fresno = place("In-N-Out Burger", 36.8386, -119.7889)   // 142 km

        val out = rankPlaces(
            listOf(santaAna, crane, phoenix, livermore, fresno),
            modestoLat, modestoLng, "US", query = "in n out",
        )
        assertEquals("the nearest branch must lead", livermore.lat, out[0].lat, 1e-6)
        assertEquals("then the next nearest", fresno.lat, out[1].lat, 1e-6)
        assertTrue(
            "Missouri must not outrank California from Modesto",
            out.indexOfFirst { it.lat == crane.lat } > out.indexOfFirst { it.lat == livermore.lat },
        )
    }

    /**
     * ...but the exact name still wins when the distances are comparable: a
     * real Modesto search returns "Target" and "Ulta Beauty at Target" in the
     * same retail park.
     */
    @Test
    fun `an exact name still leads a longer one at a similar distance`() {
        val target = place("Target", 37.6700, -121.0000)
        val ulta = place("Ulta Beauty at Target", 37.6699, -121.0001)
        val out = rankPlaces(listOf(ulta, target), modestoLat, modestoLng, "US", "target")
        assertEquals("Target", out[0].name)
    }

    /** The bonus is bounded: roughly 2x further and the fuller name wins. */
    @Test
    fun `the exact-name preference is worth about a factor of two in distance`() {
        val exactFar = place("Coffee", 37.9577, -121.2908)      // ~40 km
        val fullerNear = place("Coffee House", 37.6688, -120.9970) // ~3 km
        val out = rankPlaces(listOf(exactFar, fullerNear), modestoLat, modestoLng, "US", "coffee")
        assertEquals("13x nearer must win", "Coffee House", out[0].name)
    }

    /** The Mark's-Hot-Dogs guard must survive: a partial match stays below. */
    @Test
    fun `a name missing a query word still ranks below every full match`() {
        val marks = place("Mark's Hot Dogs", 37.3382, -121.8863)   // 80 km, partial
        val pinksFar = place("Pink's Hot Dogs", 34.0928, -118.3287) // 460 km, full
        val out = rankPlaces(
            listOf(marks, pinksFar), modestoLat, modestoLng, "US", "pinks hot dogs",
        )
        assertEquals("Pink's Hot Dogs", out[0].name)
    }
}
