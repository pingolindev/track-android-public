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

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CityResult
import java.io.File

/**
 * The "Set search area" picker can go away mid-search: a search that swallowed the cancellation
 * (`runCatching`) would run on into `binding`, which onDestroyView has cleared, and crash. The
 * map's search sheet guards the same pattern (MapAddressSearchSheet.withBinding).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CityScopePickerDismissTest {

    @Test
    fun `a search that fails is no matches`() = runTest {
        assertEquals(emptyList<CityResult>(), CityScopePickerSheet.citiesOrNone { throw java.io.IOException("offline") })
        assertEquals(emptyList<CityResult>(), CityScopePickerSheet.citiesOrNone { throw UnsatisfiedLinkError("no index") })
    }

    @Test
    fun `a search's cities pass through`() = runTest {
        val city = mockk<CityResult>()
        assertEquals(listOf(city), CityScopePickerSheet.citiesOrNone { listOf(city) })
    }

    @Test
    fun `a sheet that goes away mid-search stops there, and never goes on as no matches`() = runTest {
        val download = CompletableDeferred<List<CityResult>>()
        var wentOn = false
        val search = launch {
            CityScopePickerSheet.citiesOrNone { download.await() }
            wentOn = true
        }
        runCurrent()

        // What dismissing the sheet does to its search (viewLifecycleOwner's scope, onDestroyView).
        search.cancel()
        advanceUntilIdle()

        assertTrue(search.isCancelled)
        assertFalse("the search ran on after the sheet went away", wentOn)
    }

    /**
     * The sheet itself can't be opened here (no Hilt in the JVM tests), so its two guards are pinned
     * from its source: its only city search runs inside [CityScopePickerSheet.citiesOrNone], and past
     * it the view is read only through `_binding ?: return@launch`.
     */
    @Test
    fun `the sheet's search reads the view only while it's there`() {
        val sheet = File("src/main/java/com/pingolin/track/ui/places/CityScopePickerSheet.kt").readText()
        assertEquals("one city search, no other", 1, Regex("""\.searchCities\(""").findAll(sheet).count())
        val wrapped = sheet.substring(sheet.indexOf("val cities = citiesOrNone {"))
            .let { it.substring(0, it.indexOf("\n                    }\n")) }
        assertTrue("the search runs inside citiesOrNone", wrapped.contains("geocodingRepository.searchCities("))
        assertFalse(sheet.contains("runCatching {"))

        val search = sheet.substring(sheet.indexOf("searchJob = viewLifecycleOwner.lifecycleScope.launch {"))
            .let { it.substring(0, it.indexOf("adapter.submit(emptyList(), cities)")) }
        val after = search.substring(search.indexOf("val cities = citiesOrNone {") + "val cities = citiesOrNone {".length)
            .let { it.substring(it.indexOf("\n                    }\n")) }
        assertTrue(after.contains("val b = _binding ?: return@launch"))
        // Code, not prose: "binding." ends a sentence in the guard's own comment.
        assertFalse("the view read past the search without the guard", Regex("""\bbinding\.\w""").containsMatchIn(after))
    }
}
