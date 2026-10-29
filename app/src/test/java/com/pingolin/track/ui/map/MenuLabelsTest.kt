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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The dot menu's labels: which show, and the map fitting them whenever its layout changes while the menu
 * is open (MenuLabelsInTheClearTest measures the real layout).
 */
class MenuLabelsTest {

    private fun source(path: String) = File("src/main/$path").readText()

    @Test
    fun `clear means a gap on one side or another, and touching isn't clear`() {
        val a = Box(0, 0, 100, 50)
        assertTrue("8 to the right", a.clearOf(Box(108, 0, 200, 50), 8))
        assertFalse("7 to the right", a.clearOf(Box(107, 0, 200, 50), 8))
        assertTrue("8 below, though over it side to side", a.clearOf(Box(0, 58, 100, 90), 8))
        assertTrue("left and above count too", Box(108, 58, 200, 90).clearOf(a, 8))
        assertFalse("touching", a.clearOf(Box(100, 0, 200, 50), 1))
        assertFalse("over it", a.clearOf(Box(50, 25, 150, 75), 8))
    }

    @Test
    fun `a label over the chrome hides, the ones before it claim their room first, and the rest show`() {
        val selector = Box(48, 120, 439, 297)
        val bellLabel = Box(128, 137, 840, 270)
        val stackLabel = Box(539, 1121, 840, 1254)
        val controlsLabel = Box(192, 1179, 1041, 1312)
        val shortControlsLabel = Box(192, 1022, 501, 1155)
        assertEquals(
            listOf(true, false, false, true),
            menuLabelsShown(listOf(stackLabel, bellLabel, controlsLabel, shortControlsLabel), chrome = listOf(selector), gapPx = 24),
        )
        assertEquals("the same two, the other way round", listOf(true, false), menuLabelsShown(listOf(controlsLabel, stackLabel), emptyList(), 24))
        assertEquals("nothing meets: all show", listOf(true, true), menuLabelsShown(listOf(stackLabel, shortControlsLabel), listOf(selector), 24))
    }

    @Test
    fun `the map fits the open menu's labels after every layout, and its own order and chrome are the ones tested`() {
        val map = source("java/com/pingolin/track/ui/map/MapFragment.kt")
        val expand = map.substring(map.indexOf("private fun expandFabs()"), map.indexOf("private fun collapseFabs()"))
        assertTrue(expand.contains(
            "binding.root.viewTreeObserver.run {\n" +
                "            removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)\n" +
                "            addOnGlobalLayoutListener(fitMenuLabelsOnLayout)\n" +
                "        }",
        ))
        assertTrue(map.contains("private val fitMenuLabelsOnLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener { fitOpenMenuLabels() }"))
        // The window's views, the bell among them: the lists the layout test measures (MENU_LABELS, MENU_CHROME).
        assertTrue(map.contains("        fitMenuLabels(b.root.rootView, gapPx = 8.dpToPx())"))
        val labels = source("java/com/pingolin/track/ui/map/MenuLabels.kt")
        assertTrue(labels.contains("    R.id.labelNotifications,\n"))
        assertTrue(labels.contains("    R.id.notificationBellContainer,\n"))
        val collapse = map.substring(map.indexOf("private fun collapseFabs()"))
        assertTrue(collapse.substring(0, collapse.indexOf("\n    }")).contains("binding.root.viewTreeObserver.removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)"))
    }

    @Test
    fun `the bell's label goes with the map, not left over the next screen`() {
        val map = source("java/com/pingolin/track/ui/map/MapFragment.kt")
        val destroy = map.substring(map.indexOf("override fun onDestroyView()"))
        val body = destroy.substring(0, destroy.indexOf("\n    }"))
        assertTrue(body.contains("binding.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)"))
        assertTrue(body.contains("activity?.findViewById<View>(R.id.labelNotifications)?.visibility = View.GONE"))
        assertTrue("before the binding goes", body.indexOf("R.id.labelNotifications") < body.indexOf("_binding = null"))
    }
}
