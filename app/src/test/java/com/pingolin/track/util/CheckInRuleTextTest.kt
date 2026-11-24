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

package com.pingolin.track.util

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.util.Locale

/**
 * A check-in rule in words (CheckInRuleText): "Check in within 6 hours"; "Check in by 9:00 PM,
 * every day" with its zone, "(phone's time zone)" when it follows the phone, else its city; the days
 * as "every day", "weekdays", "weekends" or short names; times as set, in this phone's 12- or
 * 24-hour format. And a scheduled rule's missed check-in: "{name} hasn't checked in by 9:00 PM."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInRuleTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun twelveHours() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
    }

    /** A time as the format writes it, its narrow and no-break spaces as plain ones. */
    private fun plain(text: String) = text.replace(' ', ' ').replace(' ', ' ')

    private fun rule(
        mode: String? = CheckInModes.SCHEDULED,
        days: Int = CheckInSchedule.EVERY_DAY,
        due: Int = 21 * 60,
        tz: String = "America/New_York",
        follows: Boolean = false,
        hours: Int = 6,
    ) = InactivityAlertEntity(
        id = "r-1", userId = "me", circleId = "c-1", targetUserId = "me", timeoutHours = hours, isSelfAlert = true,
        dayBucket = 0, watches = InactivityWatches.CHECK_IN, armedAt = 0L, checkInMode = mode,
        scheduleDays = days.takeIf { mode == CheckInModes.SCHEDULED }, scheduleDueMinute = due.takeIf { mode == CheckInModes.SCHEDULED },
        scheduleTz = tz.takeIf { mode == CheckInModes.SCHEDULED }, scheduleTzFollowsPhone = follows,
    )

    @Test
    fun `a time as set, in this phone's format`() {
        assertEquals("9:00 PM", plain(CheckInRuleText.time(context, 21 * 60)))
        assertEquals("12:00 AM", plain(CheckInRuleText.time(context, 0)))
        assertEquals("6:05 AM", plain(CheckInRuleText.time(context, 6 * 60 + 5)))
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertEquals("21:00", plain(CheckInRuleText.time(context, 21 * 60)))
        assertEquals("00:00", plain(CheckInRuleText.time(context, 0)))
    }

    @Test
    fun `a one-time rule's line`() {
        assertEquals("Check in within 6 hours", CheckInRuleText.summary(context, rule(mode = CheckInModes.ONCE, hours = 6)))
        assertEquals("Check in within 1 hour", CheckInRuleText.summary(context, rule(mode = CheckInModes.ONCE, hours = 1)))
        assertEquals("a rule from before Room v51 is one-time", "Check in within 6 hours", CheckInRuleText.summary(context, rule(mode = null)))
    }

    @Test
    fun `a scheduled rule's line, with its days and its zone`() {
        val nine = CheckInRuleText.time(context, 21 * 60)
        assertEquals("Check in by $nine, every day (phone's time zone)", CheckInRuleText.summary(context, rule(follows = true)))
        assertEquals("Check in by $nine, every day (New York)", CheckInRuleText.summary(context, rule()))
        assertEquals("Check in by $nine, weekdays (New York)", CheckInRuleText.summary(context, rule(days = CheckInSchedule.WEEKDAYS)))
        assertEquals("Check in by $nine, weekends (New York)", CheckInRuleText.summary(context, rule(days = CheckInSchedule.WEEKENDS)))
        val monWedFri = CheckInSchedule.bit(DayOfWeek.MONDAY) or CheckInSchedule.bit(DayOfWeek.WEDNESDAY) or CheckInSchedule.bit(DayOfWeek.FRIDAY)
        assertEquals("Check in by $nine, Mon, Wed, Fri (New York)", CheckInRuleText.summary(context, rule(days = monWedFri)))
        assertEquals("Check in by $nine, every day (UTC)", CheckInRuleText.summary(context, rule(tz = "UTC")))
        assertEquals("a schedule that doesn't read says nothing", "", CheckInRuleText.summary(context, rule().copy(scheduleTz = null)))
    }

    @Test
    fun `the days in week order, Monday first, and Sunday last`() {
        val sunMon = CheckInSchedule.bit(DayOfWeek.SUNDAY) or CheckInSchedule.bit(DayOfWeek.MONDAY)
        assertEquals("Mon, Sun", CheckInRuleText.days(context, sunMon))
        assertEquals("Sat", CheckInRuleText.days(context, CheckInSchedule.bit(DayOfWeek.SATURDAY)))
        assertEquals("Monday", CheckInRuleText.dayName(context, DayOfWeek.MONDAY))
    }

    @Test
    fun `a zone by its city, from its IANA name where the phone has no name for it`() {
        assertEquals("New York", CheckInRuleText.zoneName("America/New_York", Locale.US))
        assertEquals("Los Angeles", CheckInRuleText.zoneName("America/Los_Angeles", Locale.US))
        assertEquals("Olympus", CheckInRuleText.zoneName("Mars/Olympus", Locale.US))
    }

    @Test
    fun `a scheduled rule's missed check-in, word for word`() {
        val nine = CheckInRuleText.time(context, 21 * 60)
        assertEquals("Sam hasn't checked in by $nine.", missedCheckInByWords(context, "Sam", 21 * 60))
        assertEquals("A member hasn't checked in by $nine.", missedCheckInByWords(context, " ", 21 * 60))
        assertEquals("%1\$s hasn't checked in by %2\$s.", context.resources.getText(R.string.missed_check_in_by).toString())
    }
}
