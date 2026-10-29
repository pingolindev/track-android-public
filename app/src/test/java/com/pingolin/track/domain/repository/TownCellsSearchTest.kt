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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult

/**
 * A town named after a comma is searched in its tiles: its address shard's for the term, else the cities
 * index's tile_ids, and that index leaves out a SPLIT cell. Cupertino's tile_ids are "9q95,9q9m", while
 * the town and 1 Infinite Loop are in 9q9hr, a child of the split 9q9h; San Francisco's leave out 9q8y's
 * children. So the town's own cell, resolved from its point through the manifest, is always searched
 * too; on a miss, the eight around it, started within a window, on any network; and a row is in scope by
 * a prefix.
 *
 * A fake geocoder: a manifest of tile ids (a precision the manifest doesn't hold is a soft miss, as in
 * ensureTileLoadedById, and costs no time), a load that takes [loadMs], and a global search() over every
 * loaded tile and the rows cached from before (a namesake in Seattle), cut by confidence to the limit
 * asked, as the SDK's forward_near is.
 */
class TownCellsSearchTest {

    private fun row(text: String, lat: Double, lng: Double) = GeocodingResult(
        text = text, lat = lat, lng = lng, resultType = "address", confidence = 1.0,
        name = null, city = null, state = null, postcode = null, country = "US", address = null,
    )

    private fun city(name: String, lat: Double, lng: Double, tileId: String, tileIds: List<String>) = CityResult(
        geonameId = name.hashCode().toLong(), name = name, asciiName = name, countryCode = "US", admin1 = "CA",
        population = 60_000, lat = lat, lng = lng, tileId = tileId, tileIds = tileIds, hasShard = false,
    )

    // The four with a source are at their points in the real tiles (checked against the real manifest and
    // tiles): the first two in Cupertino's own 9q9hr, found by the first search; the next in 9q9hq beside
    // it, found on the miss; then SF's in 9q8zn beside its 9q8yy. The rest are fixtures, at points chosen
    // for their cell, not addresses.
    private val infiniteLoop = row("1 Infinite Loop", 37.33168, -122.03037)              // 9q9hr, the town's own
    private val torreAve = row("10800 Torre Avenue", 37.31772, -122.02850)               // 9q9hr, the town's own
    private val stevensCreek = row("21810 Stevens Creek Boulevard", 37.32270, -122.05605) // 9q9hq, beside it
    private val oneMarket = row("1 Market Street", 37.79362, -122.39413)                 // 9q8zn, beside SF's 9q8yy
    private val inSfOwnCell = row("Fixture Street", 37.7650, -122.4219)                  // a fixture in 9q8yy
    private val modesto = row("1010 10th St", 37.6391, -120.9969)                        // a fixture in 9q9y, unsplit
    private val seattle = row("1010 10th St", 47.6062, -122.3321)                        // a fixture, cached, far away

    private val cupertino = city("Cupertino", 37.323, -122.0322, tileId = "9q9hr", tileIds = listOf("9q95", "9q9m"))
    private val sanFrancisco = city("San Francisco", 37.77493, -122.41942, tileId = "9q8yy", tileIds = listOf("9q8v", "9qbb", "9qc0"))
    private val modestoCity = city("Modesto", 37.6391, -120.9969, tileId = "9q9y", tileIds = listOf("9q9y", "9q9v"))

    // 9q9hr's eight, in the grid order geohashNeighbours gives them (9q9hq, Stevens Creek's, is the fourth), and
    // by distance from the town's point, the order they're started in (9q9hq first, 2.5 km; 9q9k8 last, 8 km;
    // checked with an independent haversine).
    private val aroundCupertino = listOf("9q9hn", "9q9hp", "9q9k0", "9q9hq", "9q9k2", "9q9hw", "9q9hx", "9q9k8")
    private val nearestFirst = listOf("9q9hq", "9q9hp", "9q9hn", "9q9k2", "9q9hx", "9q9hw", "9q9k0", "9q9k8")

    // 9q9h, 9q8y and 9q8z are split: only their children are in the manifest.
    private var manifest = setOf("9q9hr", "9q95", "9q9m", "9q8yy", "9q8yt", "9q8yw", "9q8yx", "9q8yv", "9q8yz",
        "9q8zj", "9q8zn", "9q8zp", "9q8v", "9qbb", "9qc0", "9q9y", "9q9v") + aroundCupertino
    private var loadMs = 0L
    private val loaded = linkedSetOf<String>()
    private val rows = mutableListOf(infiniteLoop, torreAve, stevensCreek, oneMarket, inSfOwnCell, modesto, seattle)
    private val cached = mutableSetOf<GeocodingResult>(seattle)
    private val repo = mockk<GeocodingRepository>()

    @Before
    fun fake() {
        coEvery { repo.searchWithSource(any(), any(), any(), any()) } returns
            GeocodingRepository.SearchOutcome(emptyList(), fromServer = false)
        coEvery { repo.searchCities(any(), any(), any(), any()) } answers {
            listOf(cupertino, sanFrancisco, modestoCity).filter { it.name.equals(firstArg<String>().trim(), ignoreCase = true) }
        }
        coEvery { repo.lookupCityShardTiles(any(), any()) } returns emptyList()
        coEvery { repo.ensureTileLoadedById(any()) } coAnswers {
            val id = firstArg<String>()
            if (id in manifest) {
                delay(loadMs)
                loaded += id
                true
            } else {
                false
            }
        }
        coEvery { repo.ensureTileAndNeighborsLoaded(any(), any()) } returns 0
        coEvery { repo.ensureTileForCoordsLoaded(any(), any()) } returns false
        coEvery { repo.search(any(), any(), any(), any()) } answers {
            val words = firstArg<String>().lowercase()
            rows.filter { r ->
                r.text.lowercase() in words && (r in cached || loaded.any { geohash(r.lat, r.lng, it.length) == it })
            }.sortedByDescending { it.confidence }.take(secondArg<Int>())
        }
    }

    private fun inCupertinoAsTheCityWide() {
        // "Search all of Cupertino" from a map centred there: the city its centre reverse-geocodes to.
        coEvery { repo.reverseGeocode(any(), any()) } returns row("Stevens Creek Blvd", 37.323, -122.0322).copy(city = "Cupertino")
    }

    @Test
    fun `1 Infinite Loop, Cupertino is found in the town's own cell, which the index leaves out`() = runTest {
        val result = repo.smartSearch("1 Infinite Loop, Cupertino")
        assertEquals(listOf(infiniteLoop), result.addresses)
        assertEquals("Cupertino", result.scopedTo?.name)
        // Found in the town's own cell, so nothing more is spent: none of the eight around it.
        assertEquals(setOf("9q9hr", "9q95", "9q9m"), loaded.toSet())
    }

    @Test
    fun `control, without the town's own cell in the manifest, nothing is found`() = runTest {
        manifest = manifest - "9q9hr"
        val result = repo.smartSearch("1 Infinite Loop, Cupertino")
        assertEquals(emptyList<GeocodingResult>(), result.addresses)
        assertEquals("Cupertino", result.scopedTo?.name)
    }

    @Test
    fun `10800 Torre Avenue is in the town's own cell, found by the first search with nothing widened`() = runTest {
        val result = repo.smartSearch("10800 Torre Avenue, Cupertino", allowMeteredTileLoad = false)
        assertEquals(listOf(torreAve), result.addresses)
        assertEquals(setOf("9q9hr", "9q95", "9q9m"), loaded.toSet())
    }

    @Test
    fun `21810 Stevens Creek Boulevard on a metered network, a miss in the town's own cell widens to the one beside it, within the window`() = runTest {
        loadMs = 1_000
        val start = currentTime
        val result = repo.smartSearch("21810 Stevens Creek Boulevard, Cupertino", allowMeteredTileLoad = false)
        assertEquals(listOf(stevensCreek), result.addresses)
        // The eight, nearest the town's point first: Stevens Creek's 9q9hq, 2.5 km away, before 9q9k8, 8 km.
        assertEquals(nearestFirst, loaded.filter { it in aroundCupertino })
        // The town's own and its two index tiles (3 s), then the eight around it (8 s), inside the 10 s window.
        assertTrue("took ${currentTime - start} ms", currentTime - start <= 3_000 + 10_000)
    }

    @Test
    fun `on a slow network the window holds, the nearest cells first, none starting after it`() = runTest {
        loadMs = 4_000
        val start = currentTime
        val result = repo.smartSearch("21810 Stevens Creek Boulevard, Cupertino", allowMeteredTileLoad = false)
        // 9q9hq and 9q9hp, the two nearest, load in 8 s; 9q9hn starts and is cut at 10 s; the rest never start.
        assertEquals(setOf("9q9hq", "9q9hp"), loaded.intersect(aroundCupertino.toSet()))
        assertEquals("Stevens Creek's cell is the nearest, so found even here", listOf(stevensCreek), result.addresses)
        assertTrue("took ${currentTime - start} ms", currentTime - start <= 12_000 + 10_000)
    }

    @Test
    fun `an SF street in the town's own split cell, and 1 Market Street in the cell beside it`() = runTest {
        assertEquals(listOf(inSfOwnCell), repo.smartSearch("Fixture Street, San Francisco").addresses)
        assertTrue("found in SF's own child: none of the eight", "9q8zn" !in loaded)
        assertEquals(listOf(oneMarket), repo.smartSearch("1 Market Street, San Francisco").addresses)
        assertTrue("9q8zn" in loaded)
    }

    @Test
    fun `a town whose own cell isn't split is searched in its base cell, and a far namesake stays out`() = runTest {
        val result = repo.smartSearch("1010 10th St, Modesto")
        assertEquals("the Modesto row, not the cached Seattle one", listOf(modesto), result.addresses)
        assertTrue("its own cell at precision 4, the base", "9q9y" in loaded)
    }

    @Test
    fun `the town's cell comes from its point through the manifest, else its stored id`() = runTest {
        // The point resolves at precision 5 (9q9h is split): no precision-4 tile was asked for at all.
        repo.smartSearch("1 Infinite Loop, Cupertino")
        assertTrue("9q9h" !in loaded && "9q9hr" in loaded)
        // A point the manifest holds nothing at: the cities index's stored id is loaded instead.
        loaded.clear()
        val elsewhere = city("Cupertino", 10.0, 10.0, tileId = "9q9hr", tileIds = listOf("9q95", "9q9m"))
        coEvery { repo.searchCities(any(), any(), any(), any()) } answers {
            if (firstArg<String>().trim().equals("cupertino", true)) listOf(elsewhere) else emptyList()
        }
        assertEquals(listOf(infiniteLoop), repo.smartSearch("1 Infinite Loop, Cupertino").addresses)
        assertTrue("9q9hr" in loaded)
    }

    @Test
    fun `a stale stored id is followed, the manifest's tile at the town's point is the one loaded`() = runTest {
        // The index was made before 9q9h was split, so its stored id is the parent, which the
        // manifest doesn't have.
        val beforeTheSplit = city("Cupertino", 37.323, -122.0322, tileId = "9q9h", tileIds = listOf("9q95", "9q9m"))
        coEvery { repo.searchCities(any(), any(), any(), any()) } answers {
            if (firstArg<String>().trim().equals("cupertino", true)) listOf(beforeTheSplit) else emptyList()
        }
        assertEquals(listOf(infiniteLoop), repo.smartSearch("1 Infinite Loop, Cupertino").addresses)
        assertTrue("9q9hr" in loaded)
    }

    @Test
    fun `a chain's stores cached elsewhere don't crowd the town's one out before the town's filter`() = runTest {
        // Thirty Safeways the device has cached from Seattle, each a surer match than Cupertino's own.
        val elsewhere = (1..30).map { row("Safeway", 47.6 + it * 0.001, -122.33).copy(confidence = 2.0) }
        val own = row("Safeway", 37.3319, -122.0301)                                  // in 9q9hr
        rows += elsewhere + own
        cached += elsewhere
        assertEquals(listOf(own), repo.smartSearch("Safeway, Cupertino").addresses)
    }

    @Test
    fun `when the server already answered, the town's own cell still counts as the town's`() = runTest {
        // Nothing is downloaded on this path; 9q9hr is on the device from before.
        coEvery { repo.searchWithSource(any(), any(), any(), any()) } returns
            GeocodingRepository.SearchOutcome(emptyList(), fromServer = true, serverConclusive = true)
        loaded += "9q9hr"
        val result = repo.smartSearch("1 Infinite Loop, Cupertino")
        assertEquals(listOf(infiniteLoop), result.addresses)
        assertEquals("Cupertino", result.scopedTo?.name)
    }

    @Test
    fun `the whole city searches the city's own cell too, by the same rule`() = runTest {
        inCupertinoAsTheCityWide()
        val result = repo.smartSearch("1 Infinite Loop", focusLat = 37.323, focusLng = -122.0322, cityWide = true)
        assertEquals("the Cupertino row, and not the cached Seattle one", listOf(infiniteLoop), result.addresses)
        assertEquals("Cupertino", result.scopedTo?.name)
        assertEquals(setOf("9q9hr", "9q95", "9q9m"), loaded.toSet())
    }

    @Test
    fun `the whole city widens to the cells beside its own on a miss, within the window`() = runTest {
        inCupertinoAsTheCityWide()
        loadMs = 1_000
        val start = currentTime
        val result = repo.smartSearch("21810 Stevens Creek Boulevard", focusLat = 37.323, focusLng = -122.0322, cityWide = true)
        assertEquals(listOf(stevensCreek), result.addresses)
        assertTrue("took ${currentTime - start} ms", currentTime - start <= 3_000 + 10_000)
    }

    @Test
    fun `control, the whole city without its own cell finds nothing`() = runTest {
        manifest = manifest - "9q9hr"
        inCupertinoAsTheCityWide()
        val result = repo.smartSearch("1 Infinite Loop", focusLat = 37.323, focusLng = -122.0322, cityWide = true)
        assertEquals(emptyList<GeocodingResult>(), result.addresses)
        assertEquals("Cupertino", result.scopedTo?.name)
    }
}
