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

package com.pingolin.track.ui.destinations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Destinations screen's words: the view model hands the screen a resource, never English; a line that
 * names the destination or the circle has its unnamed line, and no stand-in name goes into a frame; the
 * arrival count is a plural; a row's status, arrival time and coordinates are the phone's words.
 */
class DestinationsWordsTest {

    private fun src(name: String) = File("src/main/java/com/pingolin/track/ui/destinations/$name").readText()

    private val fragment = src("DestinationsFragment.kt")
    private val model = src("DestinationsViewModel.kt")
    private val adapter = src("DestinationAdapter.kt")

    /** [name]'s body in the fragment: from its declaration to the next member at the same indent. */
    private fun body(name: String): String {
        val start = fragment.indexOf("    private fun $name(")
        assertTrue("DestinationsFragment has no $name", start >= 0)
        val end = Regex("""\n {4}(private |override |internal )?fun """).find(fragment, start + 1)?.range?.first ?: fragment.length
        return fragment.substring(start, end)
    }

    @Test
    fun `the view model hands the screen a resource, never English of its own`() {
        assertFalse("DestinationsViewModel builds an Error from a literal", Regex("""DestinationsUiState\.Error\(\s*"""").containsMatchIn(model))
        assertFalse("DestinationsViewModel passes an English fallback to plainText", model.contains(".plainText(\""))
        assertTrue(model.contains("@androidx.annotation.StringRes val fallback: Int = com.pingolin.track.R.string.destinations_load_failed"))
        assertTrue(fragment.contains("Toast.makeText(requireContext(), state.message ?: getString(state.fallback), Toast.LENGTH_LONG).show()"))
    }

    @Test
    fun `a question that names the destination has its unnamed line`() {
        for ((fn, key) in listOf(
            "showArriveConfirmation" to "destinations_report_arrival_body",
            "showCancelConfirmation" to "destinations_cancel_body",
            "showDeleteConfirmation" to "destinations_delete_body",
        )) {
            val b = body(fn)
            // A blank name is no name: "…arrived at ?" read wrong.
            assertTrue("$fn names a blank destination", b.contains("val destName = destination.destName?.takeIf { it.isNotBlank() }\n"))
            assertTrue("$fn never names it", b.contains("destName?.let { getString(R.string.$key, it) }"))
            assertTrue("$fn has no unnamed line", b.contains("?: getString(R.string.${key}_unnamed)"))
        }
        val limit = body("showDestinationLimitReplaceDialog")
        assertTrue(limit.contains("val name = active.destName?.takeIf { it.isNotBlank() }\n"))
        assertTrue(limit.contains("name?.let { getString(R.string.destination_limit_body, it) } ?: getString(R.string.destination_limit_body_unnamed)"))
        assertFalse("a stand-in name still goes into a frame", fragment.contains("?: \"this destination\""))
        assertFalse("a stand-in name still goes into a frame", fragment.contains("?: \"your current destination\""))
        // The arrival notification the view model asks for names it the same way.
        assertTrue(model.contains("val destLabel = label?.takeIf { it.isNotBlank() }\n"))
    }

    @Test
    fun `the shared line names the circle that received it, else the unnamed line`() {
        assertTrue(fragment.contains("sendNowReadableName(circleName)?.let { getString(R.string.map_destination_shared, it) }"))
        assertTrue(fragment.contains("?: getString(R.string.map_destination_shared_unnamed)"))
        // The circle is found by its one spelling (AccountIds), not by the server's.
        assertTrue(fragment.contains("val circleId = AccountIds.circle(state.destination.circleId)"))
        assertTrue(fragment.contains(".firstOrNull { circleId != null && AccountIds.circle(it.id) == circleId }?.name"))
    }

    @Test
    fun `the share dialog's no-match lines give no map advice`() {
        // The dialog has no map to pick a spot on: its own lines, not the Places dialog's.
        assertTrue(fragment.contains("getString(R.string.destinations_search_no_match_in, scope.name + tail)"))
        assertTrue(fragment.contains("} ?: getString(R.string.destinations_search_no_match)"))
        assertFalse(fragment.contains("R.string.places_search_no_match"))
    }

    @Test
    fun `the arrival count is a plural`() {
        assertTrue(fragment.contains("R.plurals.destinations_arrival_reported_sent, state.notificationsSent, state.notificationsSent,"))
        assertFalse(fragment.contains("notification(s)"))
    }

    @Test
    fun `a row's status, arrival time and coordinates are the phone's words`() {
        for (key in listOf("destinations_status_en_route", "destinations_status_arrived", "destinations_status_cancelled", "destinations_status_expired")) {
            assertTrue("DestinationAdapter never says $key", adapter.contains("getString(R.string.$key)"))
        }
        assertTrue(adapter.contains("binding.tvEta.text = binding.root.context.getString(R.string.destinations_eta, formatEtaDate(etaBucket))"))
        assertTrue(adapter.contains("?: com.pingolin.track.util.coordinatesText(destination.destLatitude, destination.destLongitude)"))
        // A blank name is no name: the title falls through to the address line, then the coordinates.
        assertTrue(adapter.contains("val placeName = item.placeName?.takeIf { it.isNotBlank() }\n"))
        assertTrue(adapter.contains("val addressLine = destination.destName?.takeIf { it.isNotBlank() }\n"))
    }
}
