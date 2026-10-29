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
 * The Places screens' words: the view model hands the screen a resource, never English; a share that didn't
 * happen says why only in this app's words, else a whole line; a title that names the place has its unnamed
 * line; counts are plurals; the day chips are the phone's own weekday names; the alert area's ends follow
 * the distance unit; a place's coordinates read the same in every language.
 */
class PlacesWordsTest {

    private fun src(name: String) = File("src/main/java/com/pingolin/track/ui/places/$name").readText()

    private val fragment = src("PlacesFragment.kt")
    private val model = src("PlacesViewModel.kt")
    private val editor = src("PlaceAlertEditor.kt")
    private val binder = src("PlaceVisibilityBinder.kt")

    @Test
    fun `the view model hands the screen a resource, never English of its own`() {
        assertFalse("PlacesViewModel builds an Error from a literal", Regex("""PlacesUiState\.Error\(\s*"""").containsMatchIn(model))
        assertFalse("PlacesViewModel passes an English fallback to plainText", model.contains(".plainText(\""))
    }

    @Test
    fun `a share that didn't happen says why in this app's words, else a whole line`() {
        for ((name, text) in listOf("PlacesFragment" to fragment, "PlaceVisibilityBinder" to binder)) {
            assertFalse("$name puts English \"unknown\" into a line", Regex(""""unknown( error)?"""").containsMatchIn(text))
        }
        assertTrue(fragment.contains("shareFailedLine(sharing = shouldShare, why = why)"))
        for (key in listOf("places_share_failed", "places_unshare_failed", "places_share_failed_no_reason", "places_unshare_failed_no_reason")) {
            assertTrue("PlacesFragment never says $key", fragment.contains("R.string.$key"))
            assertTrue("PlaceVisibilityBinder never says $key", binder.contains("R.string.$key"))
        }
    }

    @Test
    fun `a title that names the place has its unnamed line`() {
        assertTrue(fragment.contains("R.string.places_manage_sharing_title_unnamed"))
        assertTrue(editor.contains("R.string.place_alert_title_edit_unnamed"))
        assertTrue(editor.contains("R.string.place_alert_title_new_unnamed"))
        assertTrue(fragment.contains("R.string.map_delete_place_confirm_unnamed"))
    }

    @Test
    fun `counts are plurals`() {
        assertTrue(fragment.contains("R.plurals.places_shared_to_circles"))
        assertTrue(fragment.contains("R.plurals.places_shared_partial"))
        assertFalse("PlacesFragment pieces an English plural together", fragment.contains("> 1) \"s\""))
    }

    @Test
    fun `the day chips say the phone's own weekday names`() {
        assertTrue(editor.contains("java.text.DateFormatSymbols.getInstance("))
        assertTrue(editor.contains(").forEach { (chip, day) -> chip.text = weekdays[day] }"))
        val layout = File("src/main/res/layout/dialog_create_place_alert.xml").readText()
        for (day in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
            assertFalse("the layout says $day itself", layout.contains("android:text=\"$day\""))
        }
    }

    @Test
    fun `the alert area's ends follow the distance unit`() {
        assertTrue(editor.contains("dialogBinding.tvBoundaryRadiusMin.text = formatRadius(areaSlider.valueFrom, useMiles)"))
        assertTrue(editor.contains("dialogBinding.tvBoundaryRadiusMax.text = formatRadius(areaSlider.valueTo, useMiles)"))
    }

    @Test
    fun `a place's coordinates read the same in every language`() {
        assertTrue(src("PlacesAdapter.kt").contains("?: com.pingolin.track.util.coordinatesText(place.latitude, place.longitude)"))
    }
}
