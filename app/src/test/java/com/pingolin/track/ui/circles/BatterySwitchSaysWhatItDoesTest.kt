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
 * No screen on any platform shows a member's battery: the level only feeds the low-battery alert, so a
 * circle's battery switch is named for that, not "Share Battery Level / Let others see your battery
 * status", with the same title and line in every app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BatterySwitchSaysWhatItDoesTest {

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> view.children.flatMap { texts(it).asSequence() }.toList()
        else -> emptyList()
    }

    @Test
    fun `the battery switch's row names the low-battery alert`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val dialog = LayoutInflater.from(activity).inflate(R.layout.dialog_circle_privacy, null)
        val row = dialog.findViewById<View>(R.id.switchShareBattery).parent as ViewGroup

        assertEquals(
            listOf("Allow low-battery alerts about me", "Sends your battery level to this circle so members can be alerted when it's low"),
            texts(row).filter { it.isNotEmpty() },
        )
    }

    @Test
    fun `nothing says the level is shared or shown`() {
        val words = File("src/main/res/layout/dialog_circle_privacy.xml").readText() + File("src/main/res/values/strings.xml").readText()
        for (claim in listOf("Share Battery", "battery status", "see your battery")) {
            assertFalse(claim, words.contains(claim, ignoreCase = true))
        }
    }
}
