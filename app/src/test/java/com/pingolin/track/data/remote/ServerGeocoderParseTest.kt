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

package com.pingolin.track.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.GeocodingResult

/**
 * Parsing of the Pelias FeatureCollection the server-side geocoder returns.
 *
 * The payloads below are trimmed copies of real geocoder responses.
 * Both venue shapes are covered on purpose: Overture data carries
 * housenumber/street columns, but ~2-4% of POIs have no address in the source
 * at all, so the street-less shape is live and needs the second reverse lookup
 * to fill the address in.
 *
 * The parser under test is private to ServerGeocoder, so this mirrors it
 * exactly; the point is to pin the field mapping (notably region_a/country_a
 * over region/country) that the UI depends on.
 */
class ServerGeocoderParseTest {

    private val gson = Gson()

    /** Mirrors ServerGeocoder's street derivation — POI rows only. */
    private fun street(str: (String) -> String?, type: String): String? =
        if (type == "poi") {
            listOfNotNull(str("housenumber"), str("street")).joinToString(" ").ifBlank { null }
        } else null

    // Mirrors ServerGeocoder.parseFeature.
    private fun parse(json: String): List<GeocodingResult> {
        val root = gson.fromJson(json, JsonObject::class.java) ?: return emptyList()
        val feats = root.getAsJsonArray("features") ?: return emptyList()
        return (0 until feats.size()).mapNotNull { i ->
            val feat = feats.get(i).asJsonObject
            val props = feat.getAsJsonObject("properties") ?: return@mapNotNull null
            val coords = feat.getAsJsonObject("geometry")?.getAsJsonArray("coordinates")
            fun str(k: String): String? =
                if (props.has(k) && !props.get(k).isJsonNull) props.get(k).asString else null
            val layer = str("layer") ?: ""
            val type = if (layer == "address" || layer == "street") "address" else "poi"
            GeocodingResult(
                text = if (type == "address") {
                    str("name")
                        ?: listOfNotNull(str("housenumber"), str("street")).joinToString(" ")
                            .ifBlank { null } ?: str("label") ?: ""
                } else str("label") ?: str("name") ?: "",
                lat = coords?.get(1)?.asDouble ?: 0.0,
                lng = coords?.get(0)?.asDouble ?: 0.0,
                resultType = type,
                confidence = 1.0,
                name = str("name"),
                city = run {
                    val locality = str("locality") ?: str("localadmin")
                    val hood = str("neighbourhood") ?: str("borough")
                    when {
                        street(::str, type) != null -> locality ?: hood
                        hood.isNullOrBlank() -> locality
                        locality.isNullOrBlank() -> hood
                        hood.equals(locality, ignoreCase = true) -> locality
                        else -> "$hood, $locality"
                    }
                },
                state = str("region_a") ?: str("region"),
                postcode = str("postalcode"),
                country = str("country_a") ?: str("country"),
                address = street(::str, type),
            )
        }
    }

    /** Real venue response shape: name + locality, and NO street. */
    private val venueJson = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[-118.3781,34.1706]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs",
           "locality":"Los Angeles","region":"California","region_a":"CA",
           "country":"United States","country_a":"USA",
           "label":"Pink's Hot Dogs, Los Angeles, CA, USA"}},
        {"geometry":{"type":"Point","coordinates":[-118.34,33.83]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs",
           "locality":"Torrance","region":"California","region_a":"CA",
           "country":"United States","country_a":"USA",
           "label":"Pink's Hot Dogs, Torrance, CA, USA"}}
      ]}
    """.trimIndent()

    /** Real address response shape: housenumber + street present. */
    private val addressJson = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[-118.3444,34.0836]},
         "properties":{"layer":"address","housenumber":"709",
           "street":"North La Brea Avenue","locality":"Los Angeles",
           "region":"California","region_a":"CA","postalcode":"90038",
           "country":"United States","country_a":"USA",
           "label":"709 North La Brea Avenue, Los Angeles, CA, USA"}}
      ]}
    """.trimIndent()

    @Test
    fun `every feature is lifted, not just the first`() {
        val out = parse(venueJson)
        assertEquals(2, out.size)
        assertEquals("Torrance", out[1].city)
    }

    @Test
    fun `venue rows become POIs carrying the name`() {
        val row = parse(venueJson).first()
        assertEquals("poi", row.resultType)
        assertEquals("Pink's Hot Dogs", row.name)
        assertEquals(34.1706, row.lat, 1e-6)
    }

    /**
     * A venue row with no street columns must still yield a null address
     * rather than an empty string, because null is what triggers the
     * reverse-lookup fallback.
     */
    @Test
    fun `a venue with no street columns yields a null address, not blank`() {
        assertNull(parse(venueJson).first().address)
    }

    /**
     * `text` must mean "the street line" on an address row, exactly as it does
     * for a tile row. Holding the full Pelias label instead would make the
     * create-place dialog render
     * "715 North La Brea Avenue, Los Angeles, CA, USA, Los Angeles, CA, USA".
     */
    @Test
    fun `an address row's text is the street alone, not the full label`() {
        assertEquals("709 North La Brea Avenue", parse(addressJson).first().text)
    }

    @Test
    fun `a venue row's text keeps the descriptive label`() {
        assertEquals("Pink's Hot Dogs, Los Angeles, CA, USA", parse(venueJson).first().text)
    }

    @Test
    fun `address rows do carry the street`() {
        val row = parse(addressJson).first()
        assertEquals("address", row.resultType)
        assertEquals("709 North La Brea Avenue", row.text.substringBefore(","))
        assertEquals("90038", row.postcode)
    }

    /**
     * Server and tile rows land in the same list, and tiles only have the
     * short forms — "Manteca, California" next to "Manteca, CA" looks broken.
     */
    @Test
    fun `abbreviated region and country win over the long forms`() {
        val row = parse(venueJson).first()
        assertEquals("CA", row.state)
        assertEquals("USA", row.country)
    }

    /**
     * One chain's outlets: one in Camarillo and four in Los Angeles, real and
     * distinct, 1.7-23 km apart. Venue rows carry no street, so name + city
     * would be everything the user sees, and the four would look identical.
     * The gateway does know which part of the city each is in. Real
     * neighbourhoods from a live response.
     */
    private val sameCityJson = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[-118.34431,34.08391]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs","neighbourhood":"Fairfax District",
           "locality":"Los Angeles","region_a":"CA","country_a":"USA"}},
        {"geometry":{"type":"Point","coordinates":[-118.36903,34.15160]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs","neighbourhood":"Toluca Lake",
           "locality":"Los Angeles","region_a":"CA","country_a":"USA"}},
        {"geometry":{"type":"Point","coordinates":[-118.35944,34.13869]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs","neighbourhood":"Hollywood Hills",
           "locality":"Los Angeles","region_a":"CA","country_a":"USA"}},
        {"geometry":{"type":"Point","coordinates":[-118.40926,33.94347]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs","neighbourhood":"Westchester",
           "locality":"Los Angeles","region_a":"CA","country_a":"USA"}}
      ]}
    """.trimIndent()

    @Test
    fun `same-name same-city venues are told apart by neighbourhood`() {
        val cities = parse(sameCityJson).map { it.city }
        assertEquals(
            listOf(
                "Fairfax District, Los Angeles",
                "Toluca Lake, Los Angeles",
                "Hollywood Hills, Los Angeles",
                "Westchester, Los Angeles",
            ),
            cities,
        )
        assertEquals("every row must be distinguishable", 4, cities.toSet().size)
    }

    /** No neighbourhood: fall back to the plain locality, no stray comma. */
    @Test
    fun `a venue with no neighbourhood keeps the plain city`() {
        assertEquals("Los Angeles", parse(venueJson).first().city)
    }

    /**
     * When the Overture import carries street columns, venue rows arrive with a
     * housenumber and street of their own. The street is both a better
     * differentiator than the neighbourhood and mutually exclusive with it on a
     * line this narrow, so the neighbourhood has to yield.
     */
    private val enrichedVenueJson = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[-118.3444,34.0836]},
         "properties":{"layer":"venue","name":"Pink's Hot Dogs",
           "housenumber":"709","street":"N La Brea Ave",
           "neighbourhood":"Fairfax District","locality":"Los Angeles",
           "region_a":"CA","postalcode":"90038","country_a":"USA"}}
      ]}
    """.trimIndent()

    @Test
    fun `an enriched venue surfaces its street as the address`() {
        val row = parse(enrichedVenueJson).first()
        assertEquals("poi", row.resultType)
        assertEquals("709 N La Brea Ave", row.address)
    }

    @Test
    fun `a street displaces the neighbourhood, which would not fit beside it`() {
        assertEquals("Los Angeles", parse(enrichedVenueJson).first().city)
    }

    /** Losing the locality must not also lose the neighbourhood. */
    @Test
    fun `a street row with no locality still falls back to the neighbourhood`() {
        val json = """
          {"features":[{"geometry":{"type":"Point","coordinates":[-118.0,34.0]},
           "properties":{"layer":"venue","name":"X","housenumber":"1","street":"Main St",
             "neighbourhood":"Fairfax District","region_a":"CA","country_a":"USA"}}]}
        """.trimIndent()
        assertEquals("Fairfax District", parse(json).first().city)
    }

    /**
     * A venue with only a street and no number is valid — the importer sets the
     * two independently, and Overture has plenty of them ("Tom Bradley
     * International Terminal"). It must not render a leading space.
     */
    @Test
    fun `a street with no house number renders without a leading space`() {
        val json = """
          {"features":[{"geometry":{"type":"Point","coordinates":[-118.0,34.0]},
           "properties":{"layer":"venue","name":"X","street":"Dominicusstrasse 44",
             "locality":"Berlin","country_a":"DEU"}}]}
        """.trimIndent()
        assertEquals("Dominicusstrasse 44", parse(json).first().address)
    }

    /** Don't render "Los Angeles, Los Angeles" when the two agree. */
    @Test
    fun `a neighbourhood equal to the locality is not repeated`() {
        val json = """
          {"features":[{"geometry":{"type":"Point","coordinates":[-118.0,34.0]},
           "properties":{"layer":"venue","name":"X","neighbourhood":"Brea",
             "locality":"Brea","region_a":"CA","country_a":"USA"}}]}
        """.trimIndent()
        assertEquals("Brea", parse(json).first().city)
    }

    @Test
    fun `an empty collection parses to nothing rather than throwing`() {
        assertTrue(parse("""{"features":[]}""").isEmpty())
        assertTrue(parse("""{}""").isEmpty())
    }

    // ---- layer filtering (forward search keeps only concrete places) -------

    /** Mirrors ServerGeocoder.PLACE_LAYERS / isPlaceLayer. */
    private val placeLayers = setOf("venue", "address", "street")

    private fun layersOf(json: String): List<String> {
        val root = gson.fromJson(json, JsonObject::class.java)
        val feats = root.getAsJsonArray("features") ?: return emptyList()
        return (0 until feats.size()).map {
            feats.get(it).asJsonObject.getAsJsonObject("properties").get("layer").asString
        }
    }

    /**
     * A real "birmingham" response — eight localities, trimmed to three here —
     * ranked with Birmingham ENGLAND first even from a Modesto focus. These are
     * exactly the rows the on-device city index owns and prices with population,
     * so forward search must drop them rather than let an unpriced
     * "Birmingham, England" outrank a population-ranked "Birmingham, AL".
     */
    private val cityQueryJson = """
      {"features":[
        {"geometry":{"type":"Point","coordinates":[-1.8998,52.4814]},
         "properties":{"layer":"locality","name":"Birmingham","country_a":"GBR","region_a":"BIR"}},
        {"geometry":{"type":"Point","coordinates":[-86.8025,33.5207]},
         "properties":{"layer":"locality","name":"Birmingham","country_a":"USA","region_a":"AL"}},
        {"geometry":{"type":"Point","coordinates":[-84.7999,34.1651]},
         "properties":{"layer":"locality","name":"Cartersville","country_a":"USA","region_a":"GA"}}
      ]}
    """.trimIndent()

    @Test
    fun `a city query yields only administrative layers, all filtered out`() {
        val kept = layersOf(cityQueryJson).filter { it in placeLayers }
        assertTrue("no concrete places in a city response", kept.isEmpty())
    }

    /**
     * ...which also removes the junk the gateway mixes in: "Cartersville"
     * comes back for a "birmingham" query with confidence 1.00.
     */
    @Test
    fun `filtering a city response drops the non-matching junk too`() {
        val names = layersOf(cityQueryJson).indices
            .filter { layersOf(cityQueryJson)[it] in placeLayers }
        assertTrue("Cartersville must not survive", names.isEmpty())
    }

    @Test
    fun `venue and address responses survive the filter`() {
        assertTrue(layersOf(venueJson).all { it in placeLayers })
        assertTrue(layersOf(addressJson).all { it in placeLayers })
    }
}
