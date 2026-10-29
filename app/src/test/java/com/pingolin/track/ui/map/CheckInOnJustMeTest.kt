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

import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The map's quick check-in on Just Me: the selector is on no circle there, and a check-in that fell to the
 * first circle in the list would tell a circle nobody chose. (Send never does that on Just Me: it goes to
 * own history.) So the person picks the circle from a list naming each, even when there's only one, and the
 * menu opens for the one picked. Only circles whose names can be read are listed: a "Locked circle" isn't
 * one the person could know they picked (the same rule as for a send's target). With none the map says why,
 * and nothing is sent: a check-in button that does nothing is no answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInOnJustMeTest {

    private val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()

    @Test
    fun `on Just Me the person picks the circle, by name, and the menu opens for that one`() {
        val popup = map.substring(map.indexOf("private fun showCheckInPopup("), map.indexOf("private fun showCheckInMenu("))
        assertTrue(popup.contains(
            "        val selectedCircle = mainViewModel.selectedCircle.value\n" +
                "        if (selectedCircle == null) {\n" +
                "            chooseCircleForCheckIn(anchor, circles)\n" +
                "            return\n" +
                "        }\n" +
                "        showCheckInMenuFor(anchor, selectedCircle.id)\n",
        ))
        assertTrue(popup.contains(
            "        val choices = checkInChoices(circles)\n" +
                "        if (choices.isEmpty()) {\n" +
                "            showStatus(getString(R.string.check_in_waiting_for_circles))\n" +
                "            return\n" +
                "        }\n" +
                "        MaterialAlertDialogBuilder(requireContext())\n" +
                "            .setTitle(R.string.check_in_pick_circle)\n" +
                "            .setItems(choices.map { it.name }.toTypedArray()) { _, which -> showCheckInMenuFor(anchor, choices[which].id) }\n" +
                "            .setNegativeButton(R.string.cancel, null)\n",
        ))
        // The posture is read for the circle the menu is for, picked or on screen.
        assertTrue(popup.contains("val withPosition = runCatching { checkInRepository.sendsPositionNow(circleId) }.getOrDefault(false)"))
    }

    private fun circle(id: String, name: String) = CircleEntity(id = id, name = name, encryptedKey = null, createdAt = 0, updatedAt = 0)

    @Test
    fun `a circle whose name can't be read is never listed, and with none left there is nothing to pick`() {
        val family = circle("c1", "Family")
        val locked = circle("c2", "Locked circle")
        val older = circle("c3", "Circle (key unavailable)")
        val raw = circle("c4", "[Encrypted] 3q2+7w==")
        val hex = circle("c5", "0123456789abcdef0123456789abcdef")
        val blank = circle("c6", " ")
        assertEquals(listOf(family), checkInChoices(listOf(locked, family, older, raw, hex, blank)))
        assertEquals(emptyList<CircleEntity>(), checkInChoices(listOf(locked, older)))
    }

    @Test
    fun `nothing on the map sends to a circle by its place in the list`() {
        val byPlace = Regex("""circles\.first\(|circles\.firstOrNull\(|circles\.single\(|circles\.singleOrNull\(|circles\[0]|selectedCircleIndex = 0""")
        assertFalse(byPlace.find(map)?.value ?: "", byPlace.containsMatchIn(map))
    }

    @Test
    fun `the words`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Choose a circle to check in to.", context.getString(R.string.check_in_pick_circle))
        assertEquals(
            "Can't check in yet: this phone is still catching up with your circles.",
            context.getString(R.string.check_in_waiting_for_circles),
        )
    }
}
