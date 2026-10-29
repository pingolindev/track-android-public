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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pingolin.track.data.remote.ServerGeocoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.GeocodingResult
import java.util.Locale

/**
 * The three things the app does with the gateway's ranked search:
 *
 *   1. it recognises a reply the gateway ordered itself,
 *   2. it shows those rows in the order they came, and
 *   3. it tells the backend one language code and nothing more.
 *
 * Getting (2) wrong is invisible in every way but one: the right place is on
 * the list, lower down. The gateway puts the Eiffel Tower first for "eiffel
 * tower" typed in California; this app's own nearest-first pass puts a
 * preschool in Hayward above it, because "Tour Eiffel" does not contain the
 * words "eiffel tower". Nothing errors and nothing is missing.
 */
class ServedOrderTest {

    private val cupertinoLat = 37.3349
    private val cupertinoLng = -122.0090

    private fun place(name: String, lat: Double, lng: Double) = GeocodingResult(
        text = name,
        lat = lat,
        lng = lng,
        resultType = "poi",
        confidence = 1.0,
        name = name,
        city = null,
        state = null,
        postcode = null,
        country = null,
        address = null,
    )

    /** The gateway's answer to "eiffel tower" from Cupertino, in its order. */
    private val eiffel = listOf(
        place("Tour Eiffel", 48.8584, 2.2945),
        place("Eiffel Tower Montessori Preschool and Daycare", 37.6330, -122.0760),
        place("Hôtel Novotel Paris Tour Eiffel", 48.8500, 2.2830),
    )

    private fun reply(geocoding: String): JsonObject? = Gson().fromJson(
        """{"geocoding":$geocoding,"type":"FeatureCollection","features":[]}""",
        JsonObject::class.java,
    )

    // ---- 1. recognising the gateway's order ----

    @Test
    fun `a reply the gateway marked is ordered`() {
        assertTrue(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":1}}""")))
        // a later version orders differently, but it is still the gateway's order
        assertTrue(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":2}}""")))
        assertTrue(
            ServerGeocoder.isOrdered(
                reply("""{"version":"0.2","ranking":{"version":1,"debug":{"rounds":2}}}"""),
            ),
        )
    }

    /**
     * An older backend, a search without a focus point, and the gateway's own
     * fallback all answer WITHOUT the mark, in Pelias's order.
     */
    @Test
    fun `a reply without the mark is not ordered`() {
        assertFalse(ServerGeocoder.isOrdered(reply("""{"version":"0.2"}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":{}}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":0}}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":null}""")))
        assertFalse(
            ServerGeocoder.isOrdered(
                Gson().fromJson("""{"type":"FeatureCollection","features":[]}""", JsonObject::class.java),
            ),
        )
        assertFalse(ServerGeocoder.isOrdered(null))
    }

    @Test
    fun `only a number is a version`() {
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":true}}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":"1"}}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":{"version":[1]}}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":1}""")))
        assertFalse(ServerGeocoder.isOrdered(reply("""{"ranking":[1]}""")))
        assertFalse(ServerGeocoder.isOrdered(Gson().fromJson("""{"geocoding":7}""", JsonObject::class.java)))
    }

    // ---- 2. showing it in that order ----

    /**
     * The whole point. This app's own ranking would move the preschool up:
     * that is asserted first, so the test cannot pass by the two orders
     * happening to agree.
     */
    @Test
    fun `rows the gateway ordered are shown as they came`() {
        val own = rankPlaces(eiffel, cupertinoLat, cupertinoLng, "US", "eiffel tower")
        assertEquals("Eiffel Tower Montessori Preschool and Daycare", own.first().name)

        val shown = arrangeServedPlaces(
            eiffel, ordered = true, cupertinoLat, cupertinoLng, "US", "eiffel tower",
        )
        assertEquals(eiffel.map { it.name }, shown.map { it.name })
    }

    /** Without the mark nothing changes: the app ranks as it always has. */
    @Test
    fun `rows without the mark are still ranked by the app`() {
        val shown = arrangeServedPlaces(
            eiffel, ordered = false, cupertinoLat, cupertinoLng, "US", "eiffel tower",
        )
        val own = rankPlaces(eiffel, cupertinoLat, cupertinoLng, "US", "eiffel tower")
        assertEquals(own.map { it.name }, shown.map { it.name })
        assertEquals("Eiffel Tower Montessori Preschool and Daycare", shown.first().name)
    }

    @Test
    fun `an ordered nothing and an ordered one are returned as they are`() {
        assertTrue(arrangeServedPlaces(emptyList(), true, null, null, null, "zzyzx").isEmpty())
        val one = listOf(eiffel.first())
        assertEquals(one, arrangeServedPlaces(one, true, null, null, null, "eiffel tower"))
    }

    // ---- 3. one language code ----

    @Test
    fun `the language is the primary code of the app's language`() {
        assertEquals("de", ServerGeocoder.anonymousLanguage(Locale.forLanguageTag("de-CH")))
        assertEquals("en", ServerGeocoder.anonymousLanguage(Locale.US))
        assertEquals("zh", ServerGeocoder.anonymousLanguage(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("fil", ServerGeocoder.anonymousLanguage(Locale.forLanguageTag("fil-PH")))
        assertEquals("pt", ServerGeocoder.anonymousLanguage(Locale("pt", "BR")))
        assertEquals("ja", ServerGeocoder.anonymousLanguage(Locale.JAPAN))
    }

    /** Android still hands out three codes that were replaced decades ago. */
    @Test
    fun `legacy language codes are sent as their current ones`() {
        assertEquals("he", ServerGeocoder.anonymousLanguage(Locale("iw", "IL")))
        assertEquals("id", ServerGeocoder.anonymousLanguage(Locale("in", "ID")))
        assertEquals("yi", ServerGeocoder.anonymousLanguage(Locale("ji")))
    }

    @Test
    fun `a locale without a language sends nothing`() {
        assertNull(ServerGeocoder.anonymousLanguage(Locale.ROOT))
        assertNull(ServerGeocoder.anonymousLanguage(Locale("", "US")))
        assertNull(ServerGeocoder.anonymousLanguage(Locale("x")))
    }

    /** Whatever the locale holds, nothing but two or three letters can come out. */
    @Test
    fun `nothing but a short code can be sent`() {
        val locales = listOf(
            Locale.forLanguageTag("de-CH-u-ca-gregory"),
            Locale.forLanguageTag("sr-Latn-RS"),
            Locale.forLanguageTag("en-US-POSIX"),
            Locale("th", "TH", "TH"),
        )
        for (locale in locales) {
            val code = ServerGeocoder.anonymousLanguage(locale)!!
            assertTrue("$locale -> $code", Regex("[a-z]{2,3}").matches(code))
        }
    }
}
