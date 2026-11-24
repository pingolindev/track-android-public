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
import android.text.format.DateUtils
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import java.text.DateFormatSymbols
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Formatter
import java.util.Locale

/**
 * How a check-in rule is put in words: its summary in the alert list, and the pieces the editor
 * and the countdown show (a time as set, the days, the zone). Times are the rule's as set, in this
 * phone's time format (12 or 24 hours); names of days and zones in the language of the app's words
 * ([uiLocale]).
 */
object CheckInRuleText {

    /**
     * [minute] after local midnight as a clock time in this phone's format ("9:00 PM", "21:00"):
     * the time as set, in no zone. Formatted as that minute of a day in UTC, which has no gap.
     */
    fun time(context: Context, minute: Int): String {
        val ms = minute * 60_000L
        return DateUtils.formatDateRange(
            context, Formatter(StringBuilder(), Locale.getDefault()), ms, ms, DateUtils.FORMAT_SHOW_TIME, "UTC",
        ).toString()
    }

    /**
     * A days mask (CheckInSchedule.days) in words: "every day", "weekdays", "weekends", or the
     * days' short names in week order, Monday first ("Mon, Wed, Fri").
     */
    fun days(context: Context, mask: Int): String = when (mask and CheckInSchedule.EVERY_DAY) {
        CheckInSchedule.EVERY_DAY -> context.getString(R.string.check_in_days_every_day)
        CheckInSchedule.WEEKDAYS -> context.getString(R.string.check_in_days_weekdays)
        CheckInSchedule.WEEKENDS -> context.getString(R.string.check_in_days_weekends)
        else -> {
            val names = DateFormatSymbols.getInstance(uiLocale(context)).shortWeekdays
            DayOfWeek.values()
                .filter { mask and CheckInSchedule.bit(it) != 0 }
                .joinToString(context.getString(R.string.check_in_days_separator)) { names[calendarDay(it)] }
        }
    }

    /** [day]'s full name as a sentence takes it ("Monday"), in the language of the app's words. */
    fun dayName(context: Context, day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, uiLocale(context))

    /**
     * A zone as people know it: its city ("New York"), in [locale]'s words where the phone has
     * them, else the city in its IANA name ("Ho Chi Minh" for Asia/Ho_Chi_Minh); "UTC" as it is.
     */
    fun zoneName(id: String, locale: Locale): String =
        runCatching { android.icu.text.TimeZoneNames.getInstance(locale).getExemplarLocationName(id) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('/').replace('_', ' ')

    /**
     * A check-in rule's line in the alert list: "Check in within 6 hours", or "Check in by 9:00 PM,
     * every day" with its zone, "(phone's time zone)" when it follows this phone, else its city
     * ("(New York)"). Each a whole sentence of its own for the translators. Empty for a scheduled
     * rule whose schedule doesn't read.
     */
    fun summary(context: Context, rule: InactivityAlertEntity): String {
        if (!CheckInModes.isScheduled(rule.checkInMode)) {
            val hours = rule.timeoutHours
            return context.resources.getQuantityString(R.plurals.check_in_rule_summary_once, hours, hours)
        }
        val setting = CheckInScheduleSetting.of(rule) ?: return ""
        val time = time(context, setting.dueMinute)
        val days = days(context, setting.days)
        return if (setting.followsPhone) {
            context.getString(R.string.check_in_rule_summary_scheduled_phone, time, days)
        } else {
            context.getString(R.string.check_in_rule_summary_scheduled, time, days, zoneName(setting.tz, uiLocale(context)))
        }
    }

    /** Calendar's number for [day] (SUNDAY = 1 ... SATURDAY = 7), as DateFormatSymbols indexes its names. */
    private fun calendarDay(day: DayOfWeek): Int = day.value % 7 + 1
}
