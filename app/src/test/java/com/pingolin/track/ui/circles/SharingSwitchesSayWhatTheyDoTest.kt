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

package com.pingolin.track.ui.circles

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The two sharing switches say what turning them off does, in the words every client shares: "Let
 * others see where you've been" would not say that the history is kept, and comes back when the switch
 * does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SharingSwitchesSayWhatTheyDoTest {

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> view.children.flatMap { texts(it).asSequence() }.toList()
        else -> emptyList()
    }

    private val dialog: View by lazy {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        LayoutInflater.from(activity).inflate(R.layout.dialog_circle_privacy, null)
    }

    private fun row(switchId: Int) = texts(dialog.findViewById<View>(switchId).parent as ViewGroup).filter { it.isNotEmpty() }

    @Test
    fun `the real-time switch`() {
        assertEquals(
            listOf(
                "Share real-time location",
                "When it's off, Track stops sending this circle your location. Members may still see the last location you shared, unless location history is off too.",
            ),
            row(R.id.switchShareRealtime),
        )
    }

    @Test
    fun `the history switch`() {
        assertEquals(
            listOf(
                "Share location history",
                "When it's off, members see only where you are now. Track still keeps your history, and turning it back on shows them all of it, including the time it was off.",
            ),
            row(R.id.switchShareHistory),
        )
    }

    @Test
    fun `no label leaves out what turning a switch off does`() {
        val words = File("src/main/res/layout/dialog_circle_privacy.xml").readText() + File("src/main/res/values/strings.xml").readText()
        for (old in listOf("Share Realtime Location", "Let others see your live location", "Share Location History", "Let others see where you've been")) {
            assertFalse(old, words.contains(old))
        }
    }
}
