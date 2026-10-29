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
 * Read where it is wired: a location goes to the circle the person saw when they asked, taken then,
 * and never to whatever is chosen once it is sent. The view models' own tests hold the decisions
 * (MapViewModelTest, CheckInViewModelTest); these hold the screens to handing them the right circle.
 */
class LocationGoesWhereItWasAskedTest {

    private fun main(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    /** [name]'s body in [file], up to its closing brace at [indent]. */
    private fun body(file: String, name: String, indent: String = "    "): String =
        file.substring(file.indexOf(name)).let { it.substring(0, it.indexOf("\n$indent}\n")) }

    @Test
    fun `both of the map's sends take their circle before the fix wait`() {
        val map = main("ui/map/MapFragment.kt")
        for (send in listOf(body(map, "private fun sendLocationNow()"), body(map, "private fun maybeRefreshOwnLocationOnResume()"))) {
            val taken = send.indexOf("val target = sendTargetNow()")
            assertTrue(taken > 0)
            assertTrue("before the fix", taken < send.indexOf("locationProvider.currentLocation("))
            assertTrue(send.contains("viewModel.sendLocationNow(location, target"))
        }
        // And the view model sends where it was told, reading no selection of its own.
        val model = main("ui/map/MapViewModel.kt")
        val sendNow = model.substring(model.indexOf("fun sendLocationNow("), model.indexOf("fun clearSendLocationResult("))
        assertTrue(sendNow.contains("val circleId = target.circleId"))
        assertFalse(sendNow.contains("_selectedCircleId.value"))
    }

    @Test
    fun `the check-in screen keeps its circle across a list change and never falls to the first`() {
        val screen = main("ui/checkin/CheckInFragment.kt")
        assertFalse(screen.contains("circles.first()"))
        val choice = body(screen, "private fun showCircleChoice(")
        assertTrue(choice.contains("binding.spinnerCircle.setSelection(if (none) 0 else index, false)"))
        assertTrue(choice.contains("send.isEnabled = !none"))
        val listener = screen.substring(screen.indexOf("override fun onItemSelected("))
        assertTrue(listener.contains("val circle = shownCircles.getOrNull(position - shownOffset) ?: return"))
        // Opened from the map's check-in menu, on that menu's circle.
        assertTrue(main("ui/map/MapFragment.kt").contains(
            "androidx.core.os.bundleOf(com.pingolin.track.ui.checkin.CheckInViewModel.ARG_CIRCLE_ID to circleId)"
        ))
    }

    @Test
    fun `the first circle becomes the default only while none was ever settled`() {
        val main = main("ui/main/MainViewModel.kt")
        assertTrue(main.contains("if (list.isNotEmpty() && !sessionManager.isDefaultCircleSettled()) {"))
        assertFalse(main.contains("if (list.isNotEmpty() && sessionManager.getDefaultCircleId() == null) {"))
    }
}
