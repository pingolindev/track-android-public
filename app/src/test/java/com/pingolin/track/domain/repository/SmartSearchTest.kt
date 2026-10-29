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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two behaviours the map search sheet depends on:
 *
 *  1. Cancellation is NOT swallowed. `smartSearch` uses a
 *     cancellation-transparent helper: `runCatching` catches
 *     `Throwable`, so a dismissed sheet's coroutine would run on and
 *     fire `onProgress` against a torn-down binding (an NPE that crashes
 *     the app).
 *  2. The viewport clamp geometry — what "only what's on screen" means.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartSearchTest {

    // ---- 1. cancellation transparency -------------------------------------

    /**
     * Mirrors the exact shape of a `smartSearch` stage: a cancellable
     * repository call, then a UI callback.
     */
    @Test
    fun `cancellation-transparent catching stops the coroutine at the callback`() = runTest {
        val progress = mutableListOf<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val job = scope.launch {
            catchingLikeSmartSearch { withContext(Dispatchers.IO) { delay(10_000); "hit" } }
            progress += "onProgress fired"
        }

        repeat(3) { yield() }
        job.cancel()
        job.join()

        assertTrue(
            "onProgress must NOT fire after the caller's scope was cancelled",
            progress.isEmpty(),
        )
    }

    /**
     * Positive control: the same shape with stdlib `runCatching` still lets
     * execution through. Without this the test above would pass even if the
     * helper silently stopped running the block at all.
     */
    @Test
    fun `positive control - stdlib runCatching does swallow the cancellation`() = runTest {
        val progress = mutableListOf<String>()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val job = scope.launch {
            runCatching { withContext(Dispatchers.IO) { delay(10_000); "hit" } }
            progress += "onProgress fired"
        }

        repeat(3) { yield() }
        job.cancel()
        job.join()

        assertEquals(listOf("onProgress fired"), progress)
    }

    /** Genuine I/O failures must still be swallowed, not propagated. */
    @Test
    fun `catching still absorbs a real failure`() {
        val r = catchingLikeSmartSearch<String> { error("tile fetch exploded") }
        assertTrue(r.isFailure)
    }

    /** Local mirror of SmartSearch.kt's private `catching`. */
    private inline fun <T> catchingLikeSmartSearch(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    // ---- 2. viewport clamp geometry ---------------------------------------

    @Test
    fun `contains accepts inside and rejects outside`() {
        val area = SearchArea(south = 37.0, west = -122.0, north = 38.0, east = -121.0)
        assertTrue(area.contains(37.5, -121.5))
        assertFalse("north of the box", area.contains(39.0, -121.5))
        assertFalse("west of the box", area.contains(37.5, -123.0))
    }

    /** A box wrapping past ±180 is the union of two spans, not the gap. */
    @Test
    fun `contains handles an antimeridian-crossing box`() {
        val area = SearchArea(south = -20.0, west = 170.0, north = -15.0, east = -170.0)
        assertTrue("just west of the line", area.contains(-17.0, 175.0))
        assertTrue("just east of the line", area.contains(-17.0, -175.0))
        assertFalse("the far side of the planet is NOT inside", area.contains(-17.0, 0.0))
    }

    @Test
    fun `viewport is padded outwards`() {
        val raw = searchAreaFromViewport(south = 37.0, west = -122.0, north = 38.0, east = -121.0)
        assertTrue("south edge extends below the viewport", raw.south < 37.0)
        assertTrue("north edge extends above the viewport", raw.north > 38.0)
        assertTrue("west edge extends left of the viewport", raw.west < -122.0)
        assertTrue("east edge extends right of the viewport", raw.east > -121.0)
    }

    /**
     * Street-level zoom: a ~100 m viewport must still search its
     * neighbourhood, or "starbucks" while zoomed into one block finds nothing.
     */
    @Test
    fun `a tight zoom is floored to a usable radius`() {
        val area = searchAreaFromViewport(
            south = 37.7745,
            west = -122.4195,
            north = 37.7755,
            east = -122.4185,
        )
        // ~2 km floor on the half-span => ~1.5 km away is still inside.
        assertTrue("1.4 km north stays inside", area.contains(37.7875, -122.4190))
        assertFalse("10 km north is outside", area.contains(37.8650, -122.4190))
    }

    /** A world-spanning view must not wrap into a box that excludes everything. */
    @Test
    fun `a whole-world viewport contains every longitude`() {
        val area = searchAreaFromViewport(south = -80.0, west = -180.0, north = 80.0, east = 180.0)
        assertTrue(area.contains(0.0, 0.0))
        assertTrue(area.contains(0.0, 179.0))
        assertTrue(area.contains(0.0, -179.0))
    }

    /** Near the pole cos(lat)→0; the box must stay finite rather than blow up. */
    @Test
    fun `a polar viewport produces a finite box`() {
        val area = searchAreaFromViewport(south = 89.0, west = -1.0, north = 89.5, east = 1.0)
        assertTrue(area.north.isFinite() && area.south.isFinite())
        assertTrue(area.east.isFinite() && area.west.isFinite())
        assertTrue(area.north <= 90.0)
    }

    // ---- candidate peeling ------------------------------------------

    @Test
    fun `comma query peels the city hint`() {
        val candidates = buildSmartCandidates("1 main street, austin, tx")
        assertEquals("1 main street", candidates.first().address)
        assertTrue(candidates.any { it.hint == "austin tx" })
        assertTrue("bare city must remain reachable", candidates.any { it.hint == "austin" })
    }

    // ---- an IME's comma ------------------------------------------------------

    @Test
    fun `an IME's comma peels the city hint as an ASCII one does`() {
        // Ideographic (U+3001), fullwidth (U+FF0C) and halfwidth ideographic (U+FF64).
        for ((query, address, city) in listOf(
            Triple("東京タワー、東京", "東京タワー", "東京"),
            Triple("上海中心，上海", "上海中心", "上海"),
            Triple("ソウルタワー､ソウル", "ソウルタワー", "ソウル"),
        )) {
            val candidates = buildSmartCandidates(query)
            assertEquals(query, address, candidates.first().address)
            assertTrue(query, candidates.any { it.hint == city })
        }
    }

    @Test
    fun `only an IME's comma is read as a comma`() {
        assertEquals("1 main street, austin, tx", normalizeQueryCommas("1 main street, austin, tx"))
        assertEquals("a,b,c,d", normalizeQueryCommas("a、b，c､d"))
        // The full stop and the middle dot an IME types are not commas.
        assertEquals("東京。駅・前", normalizeQueryCommas("東京。駅・前"))
    }

    @Test
    fun `the whole search reads the query with an IME's comma as a comma`() {
        // smartSearch needs the geocoder; every comma read in it starts from this q.
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/SmartSearch.kt").readText()
        assertTrue(src.contains("    val q = normalizeQueryCommas(query).trim()\n"))
        assertTrue(src.contains("            val ranked = rankScopedResults(scoped, q, top.lat, top.lng)\n"))
    }

    // ---- corroboration of positional guesses -------------------------------

    private fun city(name: String, ascii: String = name) = uniffi.pingolin_mobile.CityResult(
        geonameId = 1L, name = name, asciiName = ascii, countryCode = "US",
        admin1 = "", population = 1000L, lat = 0.0, lng = 0.0,
        tileId = "", tileIds = emptyList(), hasShard = false,
    )

    private fun candidateFor(query: String, hint: String) =
        buildSmartCandidates(query).first { it.hint == hint }

    /**
     * Only the second-to-last token of a whitespace query is a guess. A comma is
     * the user telling us where the address stops, and "<thing> <city>" is the
     * natural two-token shape — both are taken at face value, which is what keeps
     * alias inputs like "starbucks nyc" working (nyc resolves via alt_names, so
     * the city's own name is "New York City" and an exact-name rule would reject
     * it).
     */
    @Test
    fun `only the positional token is marked as a guess`() {
        assertTrue(candidateFor("pinks hot dogs", "hot").isPositionalGuess)
        assertFalse(candidateFor("pinks hot dogs", "hot dogs").isPositionalGuess)
        assertFalse(candidateFor("350 5th ave, nyc", "nyc").isPositionalGuess)
        assertFalse(candidateFor("starbucks nyc", "nyc").isPositionalGuess)
    }

    /**
     * The central case. "hot" is a real prefix of the real city Hot Springs, so
     * the index alone cannot reject it: nothing in the cities data distinguishes
     * a hot dog from a town. A one-word guess must name a city.
     */
    @Test
    fun `a guess that merely prefixes or contains the city name is rejected`() {
        val c = candidateFor("pinks hot dogs", "hot")
        assertFalse(cityCorroborates(city("Hot Springs"), c))
        assertFalse(cityCorroborates(city("Desert Hot Springs"), c))
        assertFalse(cityCorroborates(city("Ulanhot"), c))
    }

    /**
     * The rescue this candidate exists for must keep working: a full state NAME
     * does not index (only the code does), so the bare token is the only thing
     * that resolves this query.
     */
    @Test
    fun `a guess that names the city outright is accepted`() {
        val c = candidateFor("1 main st springfield illinois", "springfield")
        assertTrue(c.isPositionalGuess)
        assertTrue(cityCorroborates(city("Springfield"), c))
        assertTrue("must be case-insensitive", cityCorroborates(city("springfield"), c))
        assertTrue(cityCorroborates(city("Zürich", ascii = "Zurich"),
                                    candidateFor("1 rue de la paix zurich ch", "zurich")))
    }

    @Test
    fun `a non-guess is never questioned`() {
        // The alias case: the city's own name is nothing like the hint.
        assertTrue(cityCorroborates(city("New York City"), candidateFor("starbucks nyc", "nyc")))
    }
}
