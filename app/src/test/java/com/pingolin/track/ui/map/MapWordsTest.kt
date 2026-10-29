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

package com.pingolin.track.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map's words: a name goes into a line only when it is readable, and without one the line has a whole
 * unnamed version; no English label stands in for an address; a send's circle is found by its canonical id;
 * coordinates read the same in every language.
 */
class MapWordsTest {

    private val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
    private val model = File("src/main/java/com/pingolin/track/ui/map/MapViewModel.kt").readText()

    /** An expression function: from [start] to the first blank line. */
    private fun block(source: String, start: String): String =
        source.substring(source.indexOf(start)).let { it.substring(0, it.indexOf("\n\n").takeIf { i -> i > 0 } ?: it.length) }

    /** A function with a body: from [start] to the brace that closes it (a blank line inside doesn't end it). */
    private fun body(source: String, start: String): String {
        val from = source.indexOf(start)
        require(from >= 0) { "no $start" }
        var depth = 0
        for (i in source.indexOf('{', from) until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(from, i + 1)
            }
        }
        error("$start has no closing brace")
    }

    @Test
    fun `the view model puts no label into the tapped place's address`() {
        for (label in listOf("Your location", "Historical location", "Address not available", "Unable to get address")) {
            assertFalse("MapViewModel writes \"$label\" as an address", model.contains("\"$label\""))
        }
    }

    @Test
    fun `the sheet says what an empty address is, and copy, prefill and save take a real address only`() {
        assertTrue(map.contains("tvAddress?.text = tapped.address ?: getString("))
        val label = map.substring(map.indexOf("tvAddress?.text = tapped.address ?: getString(")).let { it.substring(0, it.indexOf(")\n") + 1) }
        for (id in listOf("R.string.map_your_location", "R.string.map_historical_location", "R.string.map_address_unavailable")) {
            assertTrue(label.contains(id))
        }
        assertTrue(map.contains("copyAddressToClipboard(tapped.address)"))
        assertTrue(map.contains("showCreatePlaceFromMapDialog(tapped.latitude, tapped.longitude, tapped.address)"))
        assertTrue(body(map, "private fun copyAddressToClipboard(").contains("address?.takeIf { it.isNotBlank() }"))
        val share = body(map, "private fun openShareDestinationDialog(")
        assertTrue(share.contains("if (!addressHint.isNullOrBlank()) {\n            dialogBinding.etDestName.setText(addressHint)"))
        assertFalse("the destination prefill compares with an English label again", share.contains("addressHint != \""))
    }

    @Test
    fun `coordinates read the same in every language`() {
        assertTrue(block(map, "private fun coordinates(").contains("com.pingolin.track.util.coordinatesText(latitude, longitude)"))
        val helper = File("src/main/java/com/pingolin/track/util/Coordinates.kt").readText()
        assertTrue(helper.contains("String.format(java.util.Locale.ROOT, \"%.5f, %.5f\", latitude, longitude)"))
        assertFalse("a coordinate line formats in the phone's locale", map.contains("String.format(\"%.5f"))
    }

    @Test
    fun `a send's circle is found by its canonical id`() {
        val target = body(map, "private fun sendTargetNow(")
        assertTrue(target.contains("com.pingolin.track.util.AccountIds.same(it.id, id)"))
        assertFalse(target.contains("it.id == id"))
    }

    @Test
    fun `no stand-in name is put into a frame, and each line has its unnamed one`() {
        for (standIn in listOf("a circle", "another member", "your current destination", "A member", "a circle member")) {
            assertFalse("MapFragment hands \"$standIn\" to a line", map.contains("\"$standIn\""))
        }
        for (unnamed in listOf(
            "R.string.map_member_destination_unnamed",
            "R.string.map_shared_with_a_circle_by_another",
            "R.string.map_shared_with_by_another",
            "R.string.map_shared_with_a_circle_by",
            "R.string.map_cancelling_trip_unnamed",
            "R.string.destination_limit_body_unnamed",
            "R.string.map_destination_shared_unnamed",
            "R.string.sos_from_member_unnamed",
            "R.string.map_delete_place_confirm_unnamed",
            "R.string.member_unknown",
        )) {
            assertTrue("MapFragment never says $unnamed", map.contains(unnamed))
        }
    }

    @Test
    fun `every map line that names someone or something has its unnamed line`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for ((named, unnamed) in listOf(
            "map_member_destination" to "map_member_destination_unnamed",
            "map_destination_shared" to "map_destination_shared_unnamed",
            "map_cancelling_trip" to "map_cancelling_trip_unnamed",
            "destination_limit_body" to "destination_limit_body_unnamed",
            "sos_from_member" to "sos_from_member_unnamed",
            "map_delete_place_confirm" to "map_delete_place_confirm_unnamed",
        )) {
            val line = Regex("""<string name="$unnamed">([^<]*)</string>""").find(strings)
            assertTrue("$named has no $unnamed", line != null)
            assertFalse("$unnamed puts something into a frame", line!!.groupValues[1].contains("%"))
        }
    }
}
