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
import com.pingolin.track.R
import com.pingolin.track.util.CheckInRuleText
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.CheckInScheduleSetting
import com.pingolin.track.util.uiLocale

/**
 * The rules of the alert editor's "By a time of day" part (AlertsFragment.showEditor): what its
 * choices make, and what its time zone row says.
 *
 * The zone is the phone's, set by itself; a rule about yourself follows your
 * phone when it changes zone ("Phone's time zone"), and a rule about someone else keeps the zone it
 * was made in (shown by its city) unless another is picked.
 */
object CheckInScheduleEditor {

    /** A new rule's check-in time: 9:00 PM. */
    const val DEFAULT_DUE = 21 * 60

    /** The "Counting from" time offered until one is picked: 6:00 PM. */
    const val DEFAULT_FROM = 18 * 60

    /** What the editor's choices make. */
    sealed interface Choice {
        data class Ok(val setting: CheckInScheduleSetting) : Choice

        /** No day picked. */
        data object NoDay : Choice

        /** "Counting from" a time that isn't before the check-in time. */
        data object FromNotBeforeDue : Choice
    }

    /**
     * The schedule [days], [dueMinute], [fromMinute] (null: the start of the day) and the zone
     * picked ([zone]; null: none, the phone's [phoneZone]) make, for a rule about yourself
     * ([aboutMe]) or someone else. About yourself with no zone picked, it follows the phone.
     */
    fun choice(days: Int, dueMinute: Int, fromMinute: Int?, zone: String?, phoneZone: String, aboutMe: Boolean): Choice {
        val mask = days and CheckInSchedule.EVERY_DAY
        if (mask == 0) return Choice.NoDay
        if (fromMinute != null && fromMinute >= dueMinute) return Choice.FromNotBeforeDue
        return Choice.Ok(
            CheckInScheduleSetting(
                days = mask,
                dueMinute = dueMinute,
                fromMinute = fromMinute,
                tz = zone ?: phoneZone,
                followsPhone = aboutMe && zone == null,
            )
        )
    }

    /** The zone row's words: "Phone's time zone" when the rule follows it, else its zone's city. */
    fun zoneLabel(context: Context, zone: String?, phoneZone: String, aboutMe: Boolean): String =
        if (aboutMe && zone == null) context.getString(R.string.check_in_phone_time_zone)
        else CheckInRuleText.zoneName(zone ?: phoneZone, uiLocale(context))

    /** The zone a stored rule's editor starts from: none picked when it follows the phone, else its own. */
    fun zoneOf(setting: CheckInScheduleSetting): String? = if (setting.followsPhone) null else setting.tz
}
