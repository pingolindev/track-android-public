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

import android.graphics.Rect
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.math.ceil

/**
 * A destination row's buttons are whole at 360dp, from the usual font to 2.0: in a Flow they wrap onto
 * another line, where one horizontal LinearLayout would squeeze them and push the first off the left edge
 * (in German, so far that the row couldn't even be hidden). Each visible button is on one line, never
 * narrower than its text, inside the bar, clear of the others, and kept to the end. In English, as the
 * adapter shows them: your own active destination, your own finished one, someone else's, each shown or
 * hidden here ("Hide" / "Show again").
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "en-w360dp-h800dp-xxhdpi")
class DestinationButtonsWholeTest {

    private enum class Row(val arrive: Boolean, val cancel: Boolean, val delete: Boolean, val hidden: Boolean) {
        MINE_ACTIVE(arrive = true, cancel = true, delete = false, hidden = false),
        // The widest English row: the adapter sets Hide's label from isHidden whatever the row is, so
        // your own active destination, hidden here, shows Arrive + Cancel + "Show again".
        MINE_ACTIVE_HIDDEN(arrive = true, cancel = true, delete = false, hidden = true),
        MINE_FINISHED(arrive = false, cancel = false, delete = true, hidden = false),
        MINE_FINISHED_HIDDEN(arrive = false, cancel = false, delete = true, hidden = true),
        SHARED(arrive = false, cancel = false, delete = false, hidden = false),
        HIDDEN(arrive = false, cancel = false, delete = false, hidden = true),
    }

    /** The row as DestinationAdapter binds it, in a parent padded as the list is (fragment_destinations' 16dp). */
    private fun rowAt(fontScale: Float, row: Row): View {
        RuntimeEnvironment.setFontScale(fontScale)
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.Theme_Track)
        val list = FrameLayout(activity)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        list.setPadding(pad, pad, pad, pad)
        val item = LayoutInflater.from(activity).inflate(R.layout.item_destination, list, false)
        list.addView(item)
        activity.setContentView(list)
        fun View.shown(on: Boolean) { visibility = if (on) View.VISIBLE else View.GONE }
        item.findViewById<View>(R.id.btnArrive).shown(row.arrive)
        item.findViewById<View>(R.id.btnCancel).shown(row.cancel)
        item.findViewById<View>(R.id.btnDelete).shown(row.delete)
        item.findViewById<MaterialButton>(R.id.btnHide).setText(
            if (row.hidden) R.string.destination_show_again else R.string.destination_hide
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        return item
    }

    private fun assertWhole(fontScale: Float) {
        for (row in Row.values()) {
            val item = rowAt(fontScale, row)
            val bar = item.findViewById<View>(R.id.destButtons)
            val buttons = listOf(R.id.btnArrive, R.id.btnCancel, R.id.btnDelete, R.id.btnHide)
                .map { item.findViewById<MaterialButton>(it) }
                .filter { it.visibility == View.VISIBLE }
            val case = "$row at $fontScale"
            assertTrue("$case laid out", bar.width > 0 && buttons.all { it.width > 0 && it.height > 0 })
            for (b in buttons) {
                val name = "$case: \"${b.text}\""
                assertEquals("$name on one line", 1, b.lineCount)
                assertEquals("$name not cut short", 0, b.layout.getEllipsisCount(0))
                val room = b.width - b.totalPaddingLeft - b.totalPaddingRight
                val text = ceil(b.paint.measureText(b.text.toString())).toInt()
                assertTrue("$name is $room px for $text px of text", room >= text - 1)
                assertTrue(
                    "$name at ${b.left}..${b.right}, ${b.top}..${b.bottom} inside the bar 0..${bar.width}, 0..${bar.height}",
                    b.left >= 0 && b.right <= bar.width && b.top >= 0 && b.bottom <= bar.height,
                )
            }
            for ((i, a) in buttons.withIndex()) for (b in buttons.drop(i + 1)) {
                val one = Rect(a.left, a.top, a.right, a.bottom)
                assertTrue("$case: \"${a.text}\" and \"${b.text}\" overlap", !one.intersect(b.left, b.top, b.right, b.bottom))
            }
            // Kept to the end: each line's last button ends where the bar does, 8dp between buttons.
            val gap = 8 * item.resources.displayMetrics.density
            for (line in buttons.groupBy { it.top }.values) {
                assertEquals("$case: the line with \"${line.first().text}\" ends at the bar's end", bar.width, line.maxOf { it.right })
                for ((a, b) in line.sortedBy { it.left }.zipWithNext()) {
                    assertTrue("$case: \"${a.text}\" and \"${b.text}\" are ${b.left - a.right} px apart", b.left - a.right >= gap - 1)
                }
            }
        }
    }

    @Test fun `at the phone's usual font`() = assertWhole(1.0f)

    @Test fun `at 1_3`() = assertWhole(1.3f)

    @Test fun `at 2_0`() = assertWhole(2.0f)
}
