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

package com.pingolin.track.ui.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import uniffi.pingolin_mobile.GeocodingResult

/**
 * The create-place dialog must not put `GeocodingResult.text` in its Address
 * field: on a POI row that field is name + locality, not a street, so picking
 * "Pink's Hot Dogs" would write "Pink's Hot Dogs, North Hollywood, CA" where
 * the street address belongs, and leave the Label empty.
 */
// Robolectric: distances take their unit words from android.icu (the phone's CLDR data).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AddressFormatTest {

    /**
     * The home-country rule reads `Locale.getDefault()`, so pin it: otherwise
     * whether "USA" is omitted depends on the machine running the suite, and
     * the test would pass here and fail on someone else's laptop.
     */
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private fun poi(
        name: String?,
        address: String?,
        city: String? = "North Hollywood",
        state: String? = "CA",
        postcode: String? = "91601",
        country: String? = "USA",
        text: String = "$name, $city, $state",
    ) = GeocodingResult(
        text = text,
        lat = 34.1706,
        lng = -118.3781,
        resultType = "poi",
        confidence = 0.9,
        name = name,
        city = city,
        state = state,
        postcode = postcode,
        country = country,
        address = address,
    )

    private fun address(text: String) = GeocodingResult(
        text = text,
        lat = 34.1706,
        lng = -118.3781,
        resultType = "address",
        confidence = 0.9,
        name = null,
        city = "North Hollywood",
        state = "CA",
        postcode = "91601",
        country = "USA",
        address = null,
    )

    @Test
    fun `POI address line uses the street, not the name and locality`() {
        val row = poi(name = "Pink's Hot Dogs", address = "709 N La Brea Ave")
        assertEquals(
            "709 N La Brea Ave, North Hollywood, CA 91601",
            row.fullAddress(),
        )
    }

    @Test
    fun `POI label is the bare name`() {
        assertEquals(
            "Pink's Hot Dogs",
            poi(name = "Pink's Hot Dogs", address = "709 N La Brea Ave").suggestedLabel(),
        )
    }

    /** A POI with no derived street: the full address is its locality, and the
     * name is still offered as the label.
     */
    @Test
    fun `a POI with no derived street still gets its locality, and the name is still offered`() {
        val row = poi(name = "Pink's Hot Dogs", address = null)
        assertEquals("North Hollywood, CA 91601", row.fullAddress())
        assertEquals("Pink's Hot Dogs", row.suggestedLabel())
    }

    @Test
    fun `address rows keep their street line and offer no label`() {
        val row = address("709 N La Brea Ave")
        assertEquals("709 N La Brea Ave, North Hollywood, CA 91601", row.fullAddress())
        assertNull("an address row has no name worth suggesting", row.suggestedLabel())
    }

    /** Older tiles carry no structured columns; the plain text is used as is. */
    @Test
    fun `falls back to text when the tile has no structured fields`() {
        val bare = GeocodingResult(
            text = "Somewhere, Someplace",
            lat = 0.0, lng = 0.0, resultType = "poi", confidence = 0.5,
            name = null, city = null, state = null, postcode = null,
            country = null, address = null,
        )
        assertEquals("Somewhere, Someplace", bare.fullAddress())
        assertNull(bare.suggestedLabel())
    }

    /**
     * The user's own country is noise on a domestic search and costs ~5 of the
     * ~54 characters a row's second line has. Foreign results keep it.
     */
    @Test
    fun `the home country is omitted, a foreign one is kept`() {
        val home = poi(name = "Pink's Hot Dogs", address = "709 N La Brea Ave", country = "USA")
        assertEquals("709 N La Brea Ave, North Hollywood, CA 91601", home.fullAddress())

        val abroad = poi(
            name = "Pink's", address = "1 High St", city = "London",
            state = "ENG", postcode = "W1", country = "GBR",
        )
        assertEquals("1 High St, London, ENG W1, GBR", abroad.fullAddress())
    }

    @Test
    fun `metric distances step from metres to km`() {
        assertEquals("750 m", formatDistance(0.75, useMiles = false, Locale.US))
        assertEquals("8.4 km", formatDistance(8.42, useMiles = false, Locale.US))
        assertEquals("454 km", formatDistance(453.6, useMiles = false, Locale.US))
    }

    @Test
    fun `imperial distances step from feet to miles`() {
        assertEquals("264 ft", formatDistance(0.0804672, useMiles = true, Locale.US))   // 0.05 mi
        assertEquals("5.2 mi", formatDistance(8.42, useMiles = true, Locale.US))
        assertEquals("282 mi", formatDistance(453.6, useMiles = true, Locale.US))
    }

    /**
     * Each language writes m, km, ft and mi its own way: the unit's short words and the decimal mark are the
     * locale's, not English symbols everywhere. fr keeps a narrow no-break space. These are Android 14's forms (the
     * class runs at SDK 34; its ICU writes pt-BR feet "pés", where CLDR 45 has "ft"): another Android may differ.
     */
    @Test
    fun `each language writes m, km, ft and mi its own way`() {
        val cases = linkedMapOf(
            "en-US" to listOf("750 m", "8.4 km", "264 ft", "5.2 mi", "3 mi"),
            "de-DE" to listOf("750 m", "8,4 km", "264 ft", "5,2 mi", "3 mi"),
            "es-ES" to listOf("750 m", "8,4 km", "264 ft", "5,2 mi", "3 mi"),
            "fr-FR" to listOf("750\u202Fm", "8,4\u202Fkm", "264\u202Fpi", "5,2\u202Fmi", "3\u202Fmi"),
            "ja-JP" to listOf("750 m", "8.4 km", "264 ft", "5.2 mi", "3 mi"),
            "ko-KR" to listOf("750m", "8.4km", "264ft", "5.2mi", "3mi"),
            "pt-BR" to listOf("750 m", "8,4 km", "264 pés", "5,2 mi", "3 mi"),
            "zh-CN" to listOf("750米", "8.4公里", "264英尺", "5.2英里", "3英里"),
        )
        for ((tag, want) in cases) {
            val locale = Locale.forLanguageTag(tag)
            val got = listOf(
                formatDistance(0.75, useMiles = false, locale),
                formatDistance(8.42, useMiles = false, locale),
                formatDistance(0.0804672, useMiles = true, locale), // 0.05 mi
                formatDistance(8.42, useMiles = true, locale),
                formatMileRadius(3, useMiles = true, locale),
            )
            assertEquals(tag, want, got)
        }
    }

    /**
     * The bubble radius is STORED in whole miles, so the miles case must be
     * exact — round-tripping through km would render "3 mi" as "3.0 mi".
     */
    @Test
    fun `a mile-stored radius is exact in miles and converted for km`() {
        assertEquals("3 mi", formatMileRadius(3, useMiles = true, Locale.US))
        assertEquals("4.8 km", formatMileRadius(3, useMiles = false, Locale.US))
        assertEquals("25 mi", formatMileRadius(25, useMiles = true, Locale.US))
        assertEquals("40 km", formatMileRadius(25, useMiles = false, Locale.US))
    }

    /** The whole point: the added text has to leave the line inside budget. */
    @Test
    fun `a neighbourhood row plus distance stays within one line`() {
        val row = poi(
            name = "Pink's Hot Dogs", address = null,
            city = "Fairfax District, Los Angeles", state = "CA", postcode = null,
            country = "USA",
        )
        val line = row.localityLine() + " · " + formatDistance(453.6, useMiles = false, Locale.US)
        assertEquals("Fairfax District, Los Angeles, CA · 454 km", line)
        // ~54 chars fit at 13sp in the 954px the sheet leaves for this line.
        assertTrue("line was ${line.length} chars", line.length <= 50)
    }

    /**
     * The enriched shape, composed the way AddressSearchAdapter does it:
     * "address · locality · distance". This is what the server import buys —
     * the street replaces the neighbourhood, which is the only reason the line
     * still fits once a distance is on it.
     */
    @Test
    fun `an enriched POI row fits on one line with street, locality and distance`() {
        val row = poi(
            name = "Pink's Hot Dogs", address = "709 N La Brea Ave",
            city = "Los Angeles", state = "CA", postcode = "90038", country = "USA",
        )
        val line = listOf(row.address!!, row.localityLine())
            .joinToString(" · ") + " · " + formatDistance(453.6, useMiles = false, Locale.US)
        assertEquals("709 N La Brea Ave · Los Angeles, CA 90038 · 454 km", line)
        assertTrue("line was ${line.length} chars", line.length <= 54)
    }

    /**
     * The same row with the neighbourhood kept beside the street overflows:
     * a guard against adding it, thinking both would fit.
     */
    @Test
    fun `keeping the neighbourhood beside a street would overflow the line`() {
        val row = poi(
            name = "Pink's Hot Dogs", address = "709 N La Brea Ave",
            city = "Fairfax District, Los Angeles", state = "CA", postcode = "90038",
            country = "USA",
        )
        val line = listOf(row.address!!, row.localityLine())
            .joinToString(" · ") + " · " + formatDistance(453.6, useMiles = false, Locale.US)
        assertTrue("would-be line was only ${line.length} chars", line.length > 54)
    }

    @Test
    fun `blank structured fields are dropped rather than leaving stray commas`() {
        val row = poi(
            name = "Pink's Hot Dogs",
            address = "709 N La Brea Ave",
            state = "",
            postcode = "",
            country = "",
        )
        assertEquals("709 N La Brea Ave, North Hollywood", row.fullAddress())
    }

    @Test
    fun `a POI whose name is blank offers no label`() {
        assertNull(poi(name = "", address = "1 Main St").suggestedLabel())
    }

    // --- placardAddress: the map sheets ------------------------------------
    //
    // The member sheet and the tapped-location sheet must show the city
    // and state whichever geocoder answered: rendered from `text` raw,
    // the locality would depend on the geocoder — the tile geocoder
    // appends ", City, State" to `text` on a reverse, the server geocoder
    // deliberately leaves the street alone. The pair of tests below: same
    // address, both wire shapes, one line out.

    /** Server geocoder shape — the street alone in `text`. */
    @Test
    fun `an address row gets its locality from the structured fields`() {
        assertEquals(
            "709 N La Brea Ave, North Hollywood, CA",
            address("709 N La Brea Ave").placardAddress(),
        )
    }

    /** Tile geocoder shape — `text` already carries the locality. Not twice. */
    @Test
    fun `a locality already baked into text is not repeated`() {
        assertEquals(
            "709 N La Brea Ave, North Hollywood, CA",
            address("709 N La Brea Ave, North Hollywood, CA").placardAddress(),
        )
    }

    /**
     * The tapped-location sheet keeps a POI's locality beside its street
     * even on the tile path; reading `address` alone would drop it.
     */
    @Test
    fun `a POI keeps its locality beside the street`() {
        assertEquals(
            "709 N La Brea Ave, North Hollywood, CA",
            poi(name = "Pink's Hot Dogs", address = "709 N La Brea Ave").placardAddress(),
        )
    }

    /**
     * The member and SOS sheets have one line for all of it and no title row,
     * so the POI name has nowhere else to go.
     */
    @Test
    fun `the one-line placards lead with the POI name`() {
        assertEquals(
            "Pink's Hot Dogs, 709 N La Brea Ave, North Hollywood, CA",
            poi(name = "Pink's Hot Dogs", address = "709 N La Brea Ave")
                .placardAddress(withPoiName = true),
        )
    }

    /**
     * Deduping by substring — which is what the tile geocoder itself does —
     * eats a city that happens to appear inside the street name. Segment-wise
     * matching is why this line still says where it is.
     */
    @Test
    fun `a city echoed inside the street name is still printed`() {
        val row = GeocodingResult(
            text = "1 York Street", lat = 0.0, lng = 0.0, resultType = "address",
            confidence = 0.9, name = null, city = "York", state = "ENG",
            postcode = "YO1", country = "GBR", address = null,
        )
        assertEquals("1 York Street, York, ENG, GBR", row.placardAddress())
    }

    /**
     * No postcode on a placard: the sheet already shows the coordinates, and
     * the tiles carry the zip float-formatted — 324,683 of the 344,371 postcodes
     * in the San Francisco tile end in ".0", so "North Hollywood, CA 91601.0"
     * is what this line would otherwise read.
     */
    @Test
    fun `the placard omits the postcode that fullAddress keeps`() {
        val row = address("709 N La Brea Ave")
        assertEquals("709 N La Brea Ave, North Hollywood, CA 91601", row.fullAddress())
        assertEquals("709 N La Brea Ave, North Hollywood, CA", row.placardAddress())
    }

    /** A row with no structured columns still renders. */
    @Test
    fun `a bare row falls back to text`() {
        val bare = GeocodingResult(
            text = "Somewhere, Someplace", lat = 0.0, lng = 0.0, resultType = "address",
            confidence = 0.5, name = null, city = null, state = null, postcode = null,
            country = null, address = null,
        )
        assertEquals("Somewhere, Someplace", bare.placardAddress())
    }

    /**
     * The tapped-location sheet renders the name in its own title row, so a POI
     * with nothing but a name must yield no address line — falling back to
     * `text` there would print the name twice, once per row.
     */
    @Test
    fun `a nameless-only POI yields no line for the sheet that titles it separately`() {
        val row = GeocodingResult(
            text = "Yosemite Falls", lat = 0.0, lng = 0.0, resultType = "poi",
            confidence = 0.5, name = "Yosemite Falls", city = null, state = null,
            postcode = null, country = null, address = null,
        )
        assertNull(row.placardAddress())
        assertEquals("Yosemite Falls", row.placardAddress(withPoiName = true))
    }

    /** A POI with no street still says where it is. */
    @Test
    fun `a POI with no street falls back to its locality`() {
        assertEquals(
            "North Hollywood, CA",
            poi(name = "Pink's Hot Dogs", address = null).placardAddress(),
        )
    }
}
