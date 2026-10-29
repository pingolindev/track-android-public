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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The address search's words, on the map's sheet, the destination dialog and the search-area picker: every
 * line is a resource shared with the Places dialog; the first hint says "this part of the map" only when the
 * map handed over its view; an emptied query goes back to the start line; populations take the phone's own
 * compact form; a GPS fix reads the same in every language.
 */
class AddressSearchWordsTest {

    private fun src(path: String) = File("src/main/java/com/pingolin/track/ui/$path").readText()

    private val sheet = src("map/MapAddressSearchSheet.kt")
    private val picker = src("places/CityScopePickerSheet.kt")
    private val adapter = src("places/AddressSearchAdapter.kt")

    @Test
    fun `the map sheet's lines are the Places dialog's, and its own`() {
        for (key in listOf("places_search_in", "places_search_loading", "places_search_no_match_in", "places_search_no_match", "places_searching")) {
            assertTrue("MapAddressSearchSheet never says $key", sheet.contains("R.string.$key"))
        }
        assertTrue(sheet.contains("viewport != null && !cityWide -> getString(R.string.map_search_no_match_here, q)"))
        assertTrue(sheet.contains("getString(if (cityWide) R.string.map_search_searching_whole_city else R.string.places_searching)"))
        assertFalse("the sheet still names a US example", sheet.contains("Austin TX"))
    }

    @Test
    fun `the first hint says this part of the map only when the map handed over its view`() {
        assertTrue(sheet.contains("getString(if (viewport != null) R.string.map_search_hint_view else R.string.map_search_hint)"))
        // Set when the view is made, not left to the layout's line, and again for an emptied query.
        assertTrue(sheet.contains("        binding.tvMapSearchHint.text = startHint()\n\n        binding.etMapSearchQuery.addTextChangedListener("))
        assertTrue(sheet.contains("                    binding.tvMapSearchHint.text = startHint()"))
    }

    @Test
    fun `an emptied query in the picker goes back to the start line`() {
        assertTrue(picker.contains("binding.tvScopeHint.setText(R.string.city_scope_start_hint)\n                    binding.tvScopeHint.visibility = View.VISIBLE"))
        assertTrue(picker.contains("b.tvScopeHint.text = getString(R.string.city_scope_no_match, q)"))
    }

    @Test
    fun `a GPS fix reads the same in every language, and its name is the phone's words`() {
        assertTrue(picker.contains("gpsLabel.ifBlank { com.pingolin.track.util.coordinatesText(gpsLat, gpsLng) }"))
        assertTrue(picker.contains("name = gpsLabel.ifBlank { getString(R.string.city_scope_current_location) },"))
    }

    @Test
    fun `the Places dialog knows its chip's own placeholder in every language`() {
        // The chip's text is a resource in the phone's language: comparing it with the English literal would keep
        // the translated placeholder as the GPS row's label and, once picked, the area's name.
        val fragment = src("places/PlacesFragment.kt")
        assertTrue(fragment.contains("it != getString(R.string.places_set_search_area) }"))
        assertFalse(fragment.contains("it != \"Set search area\""))
    }

    @Test
    fun `sections and populations are the phone's words`() {
        assertTrue(adapter.contains("next.add(Row.Section(cachedSectionTitle, R.string.search_section_cached))"))
        assertTrue(adapter.contains("next.add(Row.Section(null, R.string.search_section_other))"))
        assertTrue(adapter.contains("android.icu.text.CompactDecimalFormat.getInstance("))
        assertTrue(adapter.contains(").apply { maximumFractionDigits = 1 }.format(pop)"))
        assertTrue(adapter.contains("root.context.getString(R.string.search_population, compact)"))
        // Code only: the comments may still say "people".
        val code = adapter.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        assertFalse("the adapter still builds English populations", Regex(""""[^"\n]*\bpeople\b[^"\n]*"""").containsMatchIn(code))
    }
}
