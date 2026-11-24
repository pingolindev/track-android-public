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
 * The inactivity rule editor says a location rule about others never goes off while they have
 * paused or turned off location sharing in the circle, in the words every client shares; a rule
 * about yourself doesn't say it, nor does a check-in rule, which goes off whatever their sharing.
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
    fun `only for a location rule about others`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/alerts/AlertsFragment.kt").readText()
        // One place decides it, from the rule's kind and whom it's about...
        assertTrue(fragment.contains("b.tvInactivityNotWhileOff.visibility = if (self || checkIn) View.GONE else View.VISIBLE"))
        // ...for a rule opened to edit, and as the type and the box change in a new one.
        assertTrue(fragment.contains("applyKind(checkIn = InactivityWatches.isCheckIn(existing.alert.watches), self = existing.alert.isSelfAlert)"))
        assertTrue(fragment.contains("applyKind(checkIn = checkedId == b.rbCheckIn.id, self = b.checkboxSelfAlert.isChecked)"))
        assertTrue(fragment.contains("applyKind(checkIn = b.rbCheckIn.isChecked, self = checked)"))
    }

    /**
     * A check-in rule (an inactivity alert that watches check-ins) goes off whatever its member's
     * sharing; a one-time one says it ends at the check-in, about others or about yourself, and
     * neither kind offers "back online".
     */
    @Test
    fun `a check-in rule has its own line, and none of a location rule's`() {
        val chooser = editor.findViewById<android.widget.RadioButton>(R.id.rbCheckIn)
        assertEquals("✅ Check-in", chooser.text.toString())
        val line = editor.findViewById<TextView>(R.id.tvCheckInHelp)
        assertEquals("hidden until the kind is chosen", View.GONE, line.visibility)
        assertEquals("Ends when they check in.", line.text.toString())
        val context = editor.context
        assertEquals("Ends when you check in.", context.getString(R.string.check_in_once_help_self))
        assertEquals("Notify my circle if I don't check in", context.getString(R.string.check_in_rule_self))
        assertEquals("Check in within", context.getString(R.string.check_in_within))
        assertEquals("Check-in", context.getString(R.string.check_in_rule_type))
        assertEquals("Check in within 1 hour", context.resources.getQuantityString(R.plurals.check_in_rule_summary_once, 1, 1))
        assertEquals("Check in within 12 hours", context.resources.getQuantityString(R.plurals.check_in_rule_summary_once, 12, 12))

        val fragment = File("src/main/java/com/pingolin/track/ui/alerts/AlertsFragment.kt").readText()
        val kind = fragment.substringAfter("fun applyKind(").substringBefore("\n        }\n")
        // The line is a one-time rule's: a scheduled one has none.
        assertTrue(kind.contains("b.tvCheckInHelp.visibility = if (checkIn && !scheduled) View.VISIBLE else View.GONE"))
        assertTrue(kind.contains("b.tvCheckInHelp.setText(if (self) R.string.check_in_once_help_self else R.string.check_in_once_help)"))
        // The kind goes out with the rule.
        assertTrue(fragment.contains("watches = if (checkIn) InactivityWatches.CHECK_IN else InactivityWatches.LOCATION,"))
        // No "back online" box here at all: the editor sends none.
        assertTrue(!fragment.contains("notifyOnReturn"))
    }
}
