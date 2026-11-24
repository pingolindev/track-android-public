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

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.ui.alerts.CheckInScheduleEditor.Choice
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.CheckInScheduleSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * The alert editor's "By a time of day" part (CheckInScheduleEditor, TimeZonePicker): by 9:00 PM
 * every day from the start of the day by default; a rule about yourself follows your phone's zone
 * unless a zone is picked ("Phone's time zone"), one about someone else keeps the zone it was made
 * in, shown by its city; no day, or a from time not before the check-in time, saves nothing and says
 * why. The two kinds, "Within a time" and "By a time of day", are chosen when the rule is made; the
 * type says what it does in a line of its own for either kind, and a one-time rule says under its
 * hours that it ends at the check-in. The zone list has this phone's zone first ("Phone's time zone"
 * before it, about yourself). No cooldown is offered: every rule from here sends 60 minutes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInScheduleEditorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val phone = "Europe/Berlin"

    // ── what the choices make ────────────────────────────────────────────────

    @Test
    fun `about yourself with no zone picked - the phone's, following it`() {
        assertEquals(
            Choice.Ok(CheckInScheduleSetting(CheckInSchedule.EVERY_DAY, 21 * 60, null, phone, followsPhone = true)),
            CheckInScheduleEditor.choice(CheckInSchedule.EVERY_DAY, 21 * 60, null, zone = null, phoneZone = phone, aboutMe = true),
        )
    }

    @Test
    fun `a zone picked is kept, and doesn't follow the phone`() {
        assertEquals(
            Choice.Ok(CheckInScheduleSetting(CheckInSchedule.WEEKDAYS, 9 * 60, 6 * 60, "America/New_York", followsPhone = false)),
            CheckInScheduleEditor.choice(CheckInSchedule.WEEKDAYS, 9 * 60, 6 * 60, "America/New_York", phone, aboutMe = true),
        )
    }

    @Test
    fun `about someone else - this phone's zone by default, never following it`() {
        assertEquals(
            Choice.Ok(CheckInScheduleSetting(CheckInSchedule.EVERY_DAY, 21 * 60, null, phone, followsPhone = false)),
            CheckInScheduleEditor.choice(CheckInSchedule.EVERY_DAY, 21 * 60, null, zone = null, phoneZone = phone, aboutMe = false),
        )
    }

    @Test
    fun `no day, or a from time at or after the check-in time, saves nothing`() {
        assertEquals(Choice.NoDay, CheckInScheduleEditor.choice(0, 21 * 60, null, null, phone, true))
        assertEquals(Choice.FromNotBeforeDue, CheckInScheduleEditor.choice(1, 21 * 60, 21 * 60, null, phone, true))
        assertEquals(Choice.FromNotBeforeDue, CheckInScheduleEditor.choice(1, 21 * 60, 22 * 60, null, phone, true))
        assertTrue(CheckInScheduleEditor.choice(1, 21 * 60, 21 * 60 - 1, null, phone, true) is Choice.Ok)
        assertEquals("Pick at least one day.", context.getString(R.string.check_in_pick_a_day))
        assertEquals("Pick a time before 9:00 PM.", context.getString(R.string.check_in_from_before_due, "9:00 PM"))
    }

    @Test
    fun `the zone row - Phone's time zone when following, else the city`() {
        assertEquals("Phone's time zone", CheckInScheduleEditor.zoneLabel(context, null, "America/New_York", aboutMe = true))
        assertEquals("New York", CheckInScheduleEditor.zoneLabel(context, null, "America/New_York", aboutMe = false))
        assertEquals("Los Angeles", CheckInScheduleEditor.zoneLabel(context, "America/Los_Angeles", "America/New_York", aboutMe = true))
        // An edit starts from the rule's own.
        assertEquals(null, CheckInScheduleEditor.zoneOf(CheckInScheduleSetting(1, 60, null, "Asia/Tokyo", followsPhone = true)))
        assertEquals("Asia/Tokyo", CheckInScheduleEditor.zoneOf(CheckInScheduleSetting(1, 60, null, "Asia/Tokyo", followsPhone = false)))
    }

    // ── the zone list ────────────────────────────────────────────────────────

    @Test
    fun `the zones are places and UTC, by city - this phone's first, and Phone's time zone before it about yourself`() {
        val ids = TimeZonePicker.zoneIds()
        assertTrue("America/New_York" in ids && "Asia/Tokyo" in ids && "UTC" in ids)
        assertTrue("no old aliases", ids.none { it.startsWith("US/") || it.startsWith("Etc/") || it.startsWith("SystemV/") })
        val now = Instant.parse("2026-11-24T12:00:00Z")
        val newYork = TimeZonePicker.Entry("America/New_York", "New York", "UTC−05:00")

        val mine = TimeZonePicker.entries(context, Locale.US, "America/New_York", includePhone = true, now = now)
        assertEquals(listOf(TimeZonePicker.Entry(null, "Phone's time zone", "UTC−05:00"), newYork), mine.take(2))

        val theirs = TimeZonePicker.entries(context, Locale.US, "America/New_York", includePhone = false, now = now)
        assertEquals(newYork, theirs.first())
        assertTrue(theirs.none { it.id == null })
        assertEquals("once", 1, theirs.count { it.id == "America/New_York" })
        val rest = theirs.drop(1).map { it.name.lowercase(Locale.US) }
        assertEquals("the others by name", rest.sorted(), rest)
        assertEquals(TimeZonePicker.Entry("Asia/Tokyo", "Tokyo", "UTC+09:00"), theirs.single { it.id == "Asia/Tokyo" })
        assertEquals("UTC", TimeZonePicker.offset("UTC", now))
    }

    @Test
    fun `a search finds a city by its name or its IANA name, in any case`() {
        val entries = TimeZonePicker.entries(context, Locale.US, "UTC", includePhone = false, now = Instant.parse("2026-11-24T12:00:00Z"))
        assertEquals(listOf("America/New_York"), TimeZonePicker.search(entries, "new york", Locale.US).map { it.id })
        assertEquals(listOf("America/New_York"), TimeZonePicker.search(entries, "new_york", Locale.US).map { it.id })
        assertTrue(TimeZonePicker.search(entries, "  ", Locale.US) == entries)
        assertEquals(emptyList<TimeZonePicker.Entry>(), TimeZonePicker.search(entries, "nowhere at all", Locale.US))
    }

    // ── the editor itself ────────────────────────────────────────────────────

    @Test
    fun `the editor's words and parts`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val editor = android.view.LayoutInflater.from(activity).inflate(R.layout.dialog_alert_editor, null)
        assertEquals("Within a time", editor.findViewById<TextView>(R.id.rbCheckInOnce).text.toString())
        assertEquals("By a time of day", editor.findViewById<TextView>(R.id.rbCheckInScheduled).text.toString())
        assertEquals("hidden until Check-in is chosen", View.GONE, editor.findViewById<View>(R.id.rgCheckInKind).visibility)
        assertEquals(View.GONE, editor.findViewById<View>(R.id.layoutSchedule).visibility)
        assertEquals("Check in by", editor.findViewById<TextView>(R.id.labelDueTime).text.toString())
        assertEquals("Days", editor.findViewById<TextView>(R.id.labelDays).text.toString())
        assertEquals("Counting from", editor.findViewById<TextView>(R.id.labelCountingFrom).text.toString())
        assertEquals("Start of day", editor.findViewById<TextView>(R.id.rbFromStartOfDay).text.toString())
        assertEquals("Time zone", editor.findViewById<TextView>(R.id.labelTimeZone).text.toString())
        // The type's own line, for either kind; the one-time rule's under its hours.
        val about = editor.findViewById<TextView>(R.id.tvCheckInAbout)
        assertEquals("Get notified when this person doesn't check in on time.", about.text.toString())
        assertEquals(View.GONE, about.visibility)
        assertEquals("Your circle is notified if you don't check in on time.", context.getString(R.string.check_in_rule_help_self))
        assertEquals(R.id.layoutTimeout, (editor.findViewById<View>(R.id.tvCheckInHelp).parent as View).id)
        // Every day by default.
        listOf(R.id.chipMonday, R.id.chipTuesday, R.id.chipWednesday, R.id.chipThursday, R.id.chipFriday, R.id.chipSaturday, R.id.chipSunday)
            .forEach { assertTrue(editor.findViewById<com.google.android.material.chip.Chip>(it).isChecked) }
    }

    @Test
    fun `the kind is chosen when the rule is made, and an edit keeps it`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/alerts/AlertsFragment.kt").readText()
        val kind = fragment.substringAfter("fun applyKind(").substringBefore("\n        }\n")
        assertTrue(kind.contains("b.rgCheckInKind.visibility = if (checkIn && !isEdit) View.VISIBLE else View.GONE"))
        assertTrue(kind.contains("b.layoutSchedule.visibility = if (scheduled) View.VISIBLE else View.GONE"))
        assertTrue(fragment.contains("b.rbCheckInScheduled.isChecked = CheckInModes.isScheduled(existing.alert.checkInMode)"))
        // A schedule that can't be saved keeps the dialog open.
        assertTrue(fragment.contains("if (onEditorSave(b, existing, scheduleChoice)) dialog.dismiss()"))
        val kindLines = kind.lines().map { it.trim() }
        assertTrue(kindLines.contains("b.tvCheckInAbout.visibility = if (checkIn) View.VISIBLE else View.GONE"))
        assertTrue(kindLines.contains("b.tvCheckInAbout.setText(if (self) R.string.check_in_rule_help_self else R.string.check_in_rule_help)"))
        // No cooldown here: every rule takes the view model's 60 minutes (the server's minimum is 15).
        assertTrue(!fragment.contains("cooldown", ignoreCase = true))
        assertTrue(!File("src/main/res/layout/dialog_alert_editor.xml").readText().contains("cooldown", ignoreCase = true))
        assertTrue(
            File("src/main/java/com/pingolin/track/ui/alerts/inactivity/InactivityAlertsViewModel.kt").readText()
                .contains("cooldownMinutes: Int = 60,"),
        )
        // Times are picked in this phone's 12- or 24-hour format.
        assertTrue(fragment.contains("if (android.text.format.DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H"))
        assertEquals(CheckInScheduleEditor.DEFAULT_DUE, 21 * 60)
    }
}
