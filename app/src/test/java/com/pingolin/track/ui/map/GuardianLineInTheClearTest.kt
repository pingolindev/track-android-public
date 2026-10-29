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

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "Location and history visible to guardian" is a standing disclosure, so nothing on the map may cover
 * it: not the controls' first button (which would take its taps), not the dot menu's labels, and not a
 * wrapped circle name running the selector over its top. This is the real map layout, measured on a
 * small phone (360dp) at three font scales, with the longest line any language has. The line is under
 * the selector, every view of the column is under the one above it, and nothing drawn after it covers
 * any of it: the selector, the sync error, the location line, the controls and their labels, the
 * refresh pill, the layers card. All of it shows at once, the worst case.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w360dp-h800dp-xxhdpi")
class GuardianLineInTheClearTest {

    private companion object {
        const val SHORT_NAME = "Family"
        // As long as a circle's name may be (dialog_create_circle.xml: maxLength 50).
        const val LONG_NAME = "The Hendersons, the Okafors and the Lakehouse gang"
    }

    private lateinit var map: MapLayout
    private val activity get() = map.activity
    private val stack get() = map.stack
    private val base get() = map.base

    private val Int.dp get() = (this * map.density).toInt()
    private fun <T : View> view(id: Int): T = map.view(id)

    /** The map's layout in the activity's, as the app has them (MapLayout). */
    private fun mapAt(fontScale: Float) {
        map = MapLayout(fontScale)
    }

    private fun settle() = map.settle()

    /** Everything that can show in the map's top half, shown, the guardian line saying [line]. */
    private fun showEverything(line: String, name: String = SHORT_NAME) {
        view<TextView>(R.id.tvCircleName).text = name
        view<View>(R.id.cardCircleSelector).visibility = View.VISIBLE
        view<View>(R.id.mapControlsLeft).visibility = View.VISIBLE
        view<TextView>(R.id.tvGuardianManaged).apply { text = line; visibility = View.VISIBLE }
        view<View>(R.id.tvSyncError).visibility = View.VISIBLE
        view<View>(R.id.tvLocationOff).visibility = View.VISIBLE
        // The dot menu open: the controls' labels.
        for (id in listOf(R.id.labelCenterOnAll, R.id.labelRecenterOnMe, R.id.labelLayers, R.id.labelCompass)) view<View>(id).visibility = View.VISIBLE
        view<View>(R.id.cardMapLayers).visibility = View.VISIBLE
        // The refresh pill as it is after a failed poll: the warning icon and the long line.
        view<View>(R.id.iconRefreshStatus).visibility = View.VISIBLE
        view<TextView>(R.id.tvRefreshStatus).setText(R.string.map_refresh_status_offline)
        view<View>(R.id.cardRefreshStatus).visibility = View.VISIBLE
        stack.place()
        settle()
    }

    private fun rectOf(v: View): Rect = map.rectOf(v)

    private fun assertInTheClear(case: String) {
        val guardian = view<View>(R.id.tvGuardianManaged)
        val line = rectOf(guardian)
        assertTrue("$case: laid out", line.height() > 0 && line.width() > 0)
        assertTrue("$case: the line is ${line.height()} px tall to tap, under 48dp", line.height() >= 48.dp - 1)

        // The column, each under the one above it.
        val column = listOf(R.id.cardCircleSelector, R.id.tvGuardianManaged, R.id.tvSyncError, R.id.mapControlsLeft)
            .map { view<View>(it) }.filter { it.isShown }
        column.zipWithNext().forEach { (above, under) ->
            assertTrue(
                "$case: ${above.resources.getResourceEntryName(under.id)} at ${rectOf(under).top} isn't under " +
                    "${above.resources.getResourceEntryName(above.id)}'s bottom ${rectOf(above).bottom}",
                rectOf(under).top >= rectOf(above).bottom + 8.dp - 1,
            )
        }

        // Nothing drawn after it covers any of it.
        val after = listOf(
            R.id.cardCircleSelector, R.id.tvSyncError, R.id.tvLocationOff, R.id.cardRefreshStatus, R.id.cardMapLayers,
            R.id.btnCenterOnAll, R.id.labelCenterOnAll, R.id.btnRecenterOnMe, R.id.labelRecenterOnMe,
            R.id.btnLayers, R.id.labelLayers, R.id.btnCompass, R.id.labelCompass, R.id.cardTimeRange, R.id.fabStack,
        )
        for (id in after) {
            val other = view<View>(id)
            if (!other.isShown) continue
            assertFalse(
                "$case: ${other.resources.getResourceEntryName(id)} ${rectOf(other)} covers the line $line",
                Rect.intersects(line, rectOf(other)),
            )
        }

        // The bell, the activity's, drawn over the map: the line starts 8dp under it, and the selector, which
        // names the circle the dot menu's sends go to, ends 8dp short of its column.
        val bell = rectOf(map.bell)
        assertTrue("$case: the line's top ${line.top} isn't 8dp under the bell's bottom ${bell.bottom}", line.top >= bell.bottom + 8.dp - 1)
        val selector = rectOf(view(R.id.cardCircleSelector))
        assertTrue("$case: the selector's end ${selector.right} isn't 8dp short of the bell ${bell.left}", selector.right <= bell.left - 8.dp + 1)

        // The refresh pill still clears the controls' first two buttons, as at their fixed place.
        val pill = rectOf(view(R.id.cardRefreshStatus))
        for (id in listOf(R.id.btnCenterOnAll, R.id.btnRecenterOnMe)) {
            assertFalse("$case: the refresh pill $pill over ${activity.resources.getResourceEntryName(id)}", Rect.intersects(pill, rectOf(view(id))))
        }
    }

    /** The longest translation of the line in any language the app has. */
    private fun longestTranslatedLine(): String = File("src/main/res").listFiles()!!
        .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        .mapNotNull { dir ->
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
            val strings = doc.getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) as org.w3c.dom.Element }
                .firstOrNull { it.getAttribute("name") == "guardian_map_line" }?.textContent
        }
        .also { assertTrue("translations of the line found", it.isNotEmpty()) }
        .maxByOrNull { it.length }!!

    private fun englishLine() = activity.getString(R.string.guardian_map_line)

    @Test
    fun `at the usual font`() {
        mapAt(1.0f)
        showEverything(englishLine())
        assertInTheClear("1.0")
        // Its pill is drawn from the top of its 48dp: with a one-line name, 8dp under the bell, whose 56dp
        // container starts where the selector does.
        assertEquals("the line 8dp under the bell", base + 64.dp, view<View>(R.id.tvGuardianManaged).top)
    }

    @Test
    fun `at 1_3`() {
        mapAt(1.3f)
        showEverything(englishLine())
        assertInTheClear("1.3")
    }

    @Test
    fun `at 2_0, the largest Android offers`() {
        mapAt(2.0f)
        showEverything(englishLine())
        assertInTheClear("2.0")
    }

    @Test
    fun `the longest translation, wrapped, at the usual font and at 2_0`() {
        for (scale in listOf(1.0f, 2.0f)) {
            mapAt(scale)
            val line = longestTranslatedLine()
            showEverything(line)
            assertTrue("it wraps at $scale", view<TextView>(R.id.tvGuardianManaged).lineCount > 1 || scale == 1.0f)
            assertInTheClear("\"$line\" at $scale")
        }
    }

    @Test
    fun `the handover line, in the same place`() {
        mapAt(1.0f)
        showEverything(activity.getString(R.string.guardian_handover_line))
        assertInTheClear("handover")
    }

    @Test
    fun `a circle name as long as a name may be`() {
        for (scale in listOf(1.0f, 1.3f, 2.0f)) {
            mapAt(scale)
            showEverything(englishLine(), name = LONG_NAME)
            // Cut at its end, on one line, short of the bell's column (assertInTheClear): its start shows, the
            // arrow too, and TalkBack has it whole.
            val name = view<TextView>(R.id.tvCircleName)
            assertEquals("one line at $scale", 1, name.lineCount)
            assertTrue("cut at its end at $scale", name.layout.getEllipsisCount(0) > 0)
            assertEquals("whole for TalkBack at $scale", LONG_NAME, name.text.toString())
            val arrow = (name.parent as android.view.ViewGroup).getChildAt(1)
            assertTrue("the arrow shows whole at $scale", arrow.width == 18.dp && rectOf(arrow).right <= rectOf(view(R.id.cardCircleSelector)).right)
            assertInTheClear("long name at $scale")
        }
    }

    @Test
    fun `the column follows when the selector or the line changes size, with no call from the map`() {
        mapAt(1.0f)
        showEverything(englishLine())
        assertInTheClear("before")
        // The selected circle changes to one with a long name: only the selector's own size moves the rest.
        view<TextView>(R.id.tvCircleName).text = LONG_NAME
        settle()
        assertInTheClear("after the selector grew")
        // The line's own words change to longer ones that wrap: its own size moves the rest.
        view<TextView>(R.id.tvGuardianManaged).text = longestTranslatedLine()
        settle()
        assertInTheClear("after the line wrapped")
    }

    @Test
    fun `a line that shows again moves what is under it, as the map places the column after each`() {
        mapAt(1.0f)
        showEverything(englishLine())
        // As bindSyncError's collector does (TopLeftStackTest pins it): a view that goes isn't laid out,
        // and one that comes back where it was isn't either, so the map places the column itself.
        view<View>(R.id.tvSyncError).visibility = View.GONE
        stack.place()
        settle()
        assertInTheClear("the sync error gone")
        view<View>(R.id.tvSyncError).visibility = View.VISIBLE
        stack.place()
        settle()
        assertInTheClear("the sync error back")
        view<View>(R.id.tvGuardianManaged).visibility = View.GONE
        stack.place()
        settle()
        view<View>(R.id.tvGuardianManaged).visibility = View.VISIBLE
        stack.place()
        settle()
        assertInTheClear("the line back")
    }
}
