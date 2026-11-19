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
package com.pingolin.track.ui.sos

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The notify button carries "Not emergency services" as a second, smaller line of its own text
 * (two lines on the button, no dash). The layout holds only the first line,
 * so the fragment must set the label, or the caveat disappears from the button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosButtonLabelTest {

    private val context = Robolectric.buildActivity(AppCompatActivity::class.java).get()
    private val action = "Notify my circle"

    @Test
    fun `the button says what it does, then what it is not, on two lines`() {
        assertEquals("$action\nNot emergency services", sosButtonLabel(context).toString())
    }

    @Test
    fun `only the action is bold, and only the caveat is smaller`() {
        val label = sosButtonLabel(context) as Spanned

        val bold = label.getSpans(0, label.length, StyleSpan::class.java).single()
        assertEquals(Typeface.BOLD, bold.style)
        assertEquals(0, label.getSpanStart(bold))
        assertEquals(action.length, label.getSpanEnd(bold))

        val smaller = label.getSpans(0, label.length, RelativeSizeSpan::class.java).single()
        assertTrue("the caveat is smaller than the action", smaller.sizeChange < 1f)
        assertEquals(action.length + 1, label.getSpanStart(smaller))
        assertEquals(label.length, label.getSpanEnd(smaller))
    }

    @Test
    fun `the fragment puts both lines on the button`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/sos/SosFragment.kt").readText()
        assertTrue(fragment.contains("binding.btnSos.text = sosButtonLabel(requireContext())"))
    }
}
