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

package com.pingolin.track.ui.alerts

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The inactivity rule editor says a rule about others never goes off while they have paused or
 * turned off location sharing in the circle, in the words every client shares; a rule about
 * yourself doesn't say it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InactivityEditorNotWhileOffTest {

    private val editor: View by lazy {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        LayoutInflater.from(activity).inflate(R.layout.dialog_alert_editor, null)
    }

    @Test
    fun `the editor's line`() {
        assertEquals(
            "Doesn't go off while they've paused or turned off location sharing in this circle.",
            editor.findViewById<TextView>(R.id.tvInactivityNotWhileOff).text.toString(),
        )
    }

    @Test
    fun `only for a rule about others`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/alerts/AlertsFragment.kt").readText()
        assertTrue(fragment.contains("b.tvInactivityNotWhileOff.visibility = if (existing.alert.isSelfAlert) View.GONE else View.VISIBLE"))
        assertTrue(fragment.contains("b.tvInactivityNotWhileOff.visibility = if (checked) View.GONE else View.VISIBLE"))
    }
}
