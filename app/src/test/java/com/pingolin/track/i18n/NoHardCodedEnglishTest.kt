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

package com.pingolin.track.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screens listed in [covered] put no English of their own on screen: every text a
 * layout or a view's code shows comes from a string resource, so it is translated with
 * the rest. [neverShown] keeps a literal nobody sees, with the reason.
 */
class NoHardCodedEnglishTest {

    private val covered = listOf(
        "res/layout/activity_auth.xml",
        "res/layout/activity_main.xml",
        "res/layout-w840dp/activity_main.xml",
        "res/layout/bottom_sheet_tapped_location.xml",
        "res/layout/dialog_pin_input.xml",
        "java/com/pingolin/track/ui/auth/AuthActivity.kt",
        "java/com/pingolin/track/util/QueueMonitor.kt",
        // The map screen
        "res/layout/fragment_map.xml",
        "res/layout/bottom_sheet_place.xml",
        "res/layout/bottom_sheet_member.xml",
        "res/layout/dialog_check_in_message.xml",
        "res/layout/dialog_coordinate_export.xml",
        "java/com/pingolin/track/ui/map/MapFragment.kt",
        "java/com/pingolin/track/ui/map/MapViewModel.kt",
        // The Places screens
        "res/layout/fragment_places.xml",
        "res/layout/item_place.xml",
        "res/layout/dialog_create_place.xml",
        "res/layout/dialog_create_place_alert.xml",
        "res/layout/dialog_share_place.xml",
        "java/com/pingolin/track/ui/places/PlacesFragment.kt",
        "java/com/pingolin/track/ui/places/PlacesViewModel.kt",
        "java/com/pingolin/track/ui/places/PlaceAlertEditor.kt",
        "java/com/pingolin/track/ui/places/PlaceVisibilityBinder.kt",
        "java/com/pingolin/track/ui/places/PlacesAdapter.kt",
        "java/com/pingolin/track/ui/places/CircleSelectionAdapter.kt",
        "java/com/pingolin/track/ui/places/ShareToggleAdapter.kt",
        // Destinations, the map's address search and the search-area picker
        "res/layout/fragment_destinations.xml",
        "res/layout/item_destination.xml",
        "res/layout/dialog_create_destination.xml",
        "res/layout/dialog_map_address_search.xml",
        "res/layout/dialog_city_scope_picker.xml",
        "java/com/pingolin/track/ui/destinations/DestinationsFragment.kt",
        "java/com/pingolin/track/ui/destinations/DestinationsViewModel.kt",
        "java/com/pingolin/track/ui/destinations/DestinationAdapter.kt",
        "java/com/pingolin/track/ui/map/MapAddressSearchSheet.kt",
        "java/com/pingolin/track/ui/places/CityScopePickerSheet.kt",
        "java/com/pingolin/track/ui/places/AddressSearchAdapter.kt",
    )

    private val neverShown = mapOf<String, List<String>>()

    /** A text attribute that isn't a resource reference (tools: attributes are the layout editor's only). */
    private val layoutText = Regex("""(?<![\w:])android:(text|hint|contentDescription|title|label|tooltipText)="(?![@?])[^"]*\p{L}[^"]*"""")

    /** The places a view's code hands a text to the screen, the notification shade or the system's settings. */
    private val codeText = Regex(
        """(\.(text|hint|contentDescription|title)\s*=\s*|Toast\.makeText\([^,]+,\s*|""" +
            """\.set(Title|Message|PositiveButton|NegativeButton|NeutralButton|ContentTitle|ContentText|Text|Hint|Error)\(\s*|""" +
            """NotificationChannel\(\s*[^,]+,\s*|\bdescription\s*=\s*)"[^"]*\p{L}[^"]*"""",
    )

    private fun found(path: String): List<String> {
        val text = File("src/main/$path").readText()
        val pattern = if (path.endsWith(".xml")) layoutText else codeText
        val allowed = neverShown[path].orEmpty()
        return pattern.findAll(text).map { it.value.trim() }
            .filter { hit -> allowed.none { it in hit } }
            .map { "$path: $it" }.toList()
    }

    @Test
    fun `the covered screens show no English of their own`() {
        assertEquals(emptyList<String>(), covered.flatMap(::found))
    }

    @Test
    fun `every covered file and every allowed literal still exists`() {
        covered.forEach { assertTrue("$it is gone: take it off the list", File("src/main/$it").exists()) }
        neverShown.forEach { (path, lines) ->
            val text = File("src/main/$path").readText()
            lines.forEach { assertTrue("$path no longer has $it: take it off neverShown", it in text) }
        }
    }
}
