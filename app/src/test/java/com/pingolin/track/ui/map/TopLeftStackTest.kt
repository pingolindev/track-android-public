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
 * The guardian line is a standing disclosure, so the top-left column is measured: each view under the
 * bottom of the one above it, whatever wraps, so no control draws over the line or takes its taps
 * (GuardianLineInTheClearTest measures the real layout). Here: the arithmetic, and the map placing it
 * every time any of it changes.
 */
class TopLeftStackTest {

    private fun source(path: String) = File("src/main/$path").readText()

    // The selector and the bell at 100, the selector's slot and the bell's bottom 56 under that, an 8 px gap:
    // the selector, the guardian line, the sync error, the controls.
    private fun tops(vararg heights: Int?) =
        topLeftStackTops(baseTop = 100, heights = heights.toList(), floors = listOf(100, 164, 164, 156), gapPx = 8)

    @Test
    fun `each view goes under the bottom of the one above it`() {
        assertEquals("the selector, the line, the sync error, the controls", listOf(100, 164, 206, 248), tops(48, 34, 34, 184))
        assertEquals("a wrapped circle name pushes the line and the controls down", listOf(100, 188, 230, 230), tops(80, 34, null, 184))
        assertEquals("a wrapped line pushes the controls down", listOf(100, 164, 246, 246), tops(48, 74, null, 184))
    }

    @Test
    fun `a gone view takes no room, the lines start under the bell, and the controls never above the selector's slot`() {
        assertEquals("no guardian line: the sync error takes its place", listOf(100, 164, 164, 206), tops(48, null, 34, 184))
        assertEquals("neither line: the controls in their usual place", listOf(100, 164, 164, 156), tops(48, null, null, 184))
        assertEquals("a short selector: the line 8 under the bell", listOf(100, 164, 206, 206), tops(20, 34, null, 184))
        assertEquals("before the first circle, the line waits under the bell", listOf(100, 164, 206, 206), tops(null, 34, null, 184))
    }

    @Test
    fun `the map places the column from the insets, and again whenever a line or the selector shows or goes`() {
        val map = source("java/com/pingolin/track/ui/map/MapFragment.kt")
        val insets = map.substring(map.indexOf("private fun applyStatusBarInsets"), map.indexOf("private fun Int.dpToPx()"))
        assertTrue(insets.contains("stackBaseTopPx = topMargin\n            topLeftStack?.place()"))
        // No fixed places in the column, where the controls and the line
        // would share one.
        assertFalse(insets.contains("this.topMargin = insets.top + 72.dpToPx()"))
        assertFalse(insets.contains("binding.tvGuardianManaged.updateLayoutParams"))
        assertFalse(insets.contains("binding.tvSyncError.updateLayoutParams"))
        assertFalse(insets.contains("binding.mapControlsLeft.updateLayoutParams"))

        val setup = map.substring(map.indexOf("private fun setupUI()"))
        assertTrue(setup.contains(
            "topLeftStack = TopLeftStack(\n" +
                "            selector = binding.cardCircleSelector,\n" +
                "            guardianLine = binding.tvGuardianManaged,\n" +
                "            syncError = binding.tvSyncError,\n" +
                "            controls = binding.mapControlsLeft,\n" +
                "            refreshPill = binding.cardRefreshStatus,\n" +
                "            layersCard = binding.cardMapLayers,\n" +
                "            baseTopPx = { stackBaseTopPx },\n" +
                "            onPlaced = { placeLocationOff() },\n" +
                "        ).also { it.attach() }",
        ))
        // Made before the lines bind, from the selector's own slot until the insets come.
        assertTrue(setup.indexOf("stackBaseTopPx = (binding.cardCircleSelector.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin") in 0 until setup.indexOf("topLeftStack = TopLeftStack("))
        assertTrue(setup.indexOf("topLeftStack = TopLeftStack(") < setup.indexOf("bindGuardianDisclosure()"))

        // A view that goes isn't laid out, so its own listener can't move the rest: each place that shows or hides one.
        val guardian = map.substring(map.indexOf("private fun bindGuardianDisclosure()"))
        assertTrue(guardian.contains(
            "binding.tvGuardianManaged.visibility = if (managed) View.VISIBLE else View.GONE\n" +
                "        // Shown or gone, what is under it in the column moves.\n" +
                "        topLeftStack?.place()\n" +
                "        if (!managed) return",
        ))
        assertTrue(map.contains("_binding?.tvSyncError?.visibility = if (shown) View.VISIBLE else View.GONE\n                    topLeftStack?.place()"))
        assertTrue(map.contains("b.mapControlsLeft.visibility = View.VISIBLE\n                        topLeftStack?.place()"))
        val destroy = map.substring(map.indexOf("override fun onDestroyView()"))
        val destroyBody = destroy.substring(0, destroy.indexOf("\n    }"))
        assertTrue(destroyBody.indexOf("topLeftStack = null") in 0 until destroyBody.indexOf("_binding = null"))
    }

    @Test
    fun `the line shows when the account's guardian state changes, not at the next resume`() {
        // /auth/me answers at app start (validateSession), after the map's first layout and its
        // resume: a line placed only at resume would not show for a guardian-managed account's
        // first session after sign-in until the map resumed again.
        val map = source("java/com/pingolin/track/ui/map/MapFragment.kt")
        val setup = map.substring(map.indexOf("private fun setupUI()"))
        assertTrue(setup.contains(
            "        bindGuardianDisclosure()\n" +
                "        // Again on every change: /auth/me's answer can come after the first layout and the resume.\n" +
                "        viewLifecycleOwner.lifecycleScope.launch {\n" +
                "            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {\n" +
                "                sessionManager.guardianDisclosure.collect { bindGuardianDisclosure() }\n" +
                "            }\n" +
                "        }",
        ))
        // It reads what it shows from the stored flag and day, which the flow follows.
        val guardian = map.substring(map.indexOf("private fun bindGuardianDisclosure()"))
        assertTrue(guardian.contains("val managed = sessionManager.isGuardianManaged()"))
        assertTrue(guardian.contains("val handoverDue = sessionManager.isGuardianHandoverDue()"))
    }

    @Test
    fun `the line is 48dp to tap, its pill drawn at the top of that`() {
        val layout = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(File("src/main/res/layout/fragment_map.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val views = layout.getElementsByTagName("TextView")
        val line = (0 until views.length).map { views.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(android, "id") == "@+id/tvGuardianManaged" }
        assertEquals("48dp", line.getAttributeNS(android, "minHeight"))
        // The 5dp the other lines' pills have inside theirs, above and below the words; under the pill,
        // the 22dp it isn't drawn on.
        assertEquals("5dp", line.getAttributeNS(android, "paddingTop"))
        assertEquals("27dp", line.getAttributeNS(android, "paddingBottom"))
        assertEquals("", line.getAttributeNS(android, "paddingVertical"))
        assertEquals("@drawable/bg_guardian_pill_touch", line.getAttributeNS(android, "background"))
        val inset = source("res/drawable/bg_guardian_pill_touch.xml")
        assertTrue(inset.contains("android:drawable=\"@drawable/bg_guardian_pill\"\n    android:insetBottom=\"22dp\" />"))
        assertFalse("drawn from the top, where the line always was", inset.contains("insetTop"))
    }
}
