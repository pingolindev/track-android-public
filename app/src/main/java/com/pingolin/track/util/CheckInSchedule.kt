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

import com.google.gson.annotations.SerializedName
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A scheduled check-in rule's schedule: "check in by 9:00 PM on these days", counting from the
 * start of the day or a from time, in the rule's IANA zone (the server's `schedule_*` columns,
 * track migration 197).
 *
 * The server, iOS and the web apply the same rules, and they must agree to the minute
 * (track-workers check_in_schedule.rs):
 * - Times are local to [zone]. One that doesn't exist that day (a spring-forward gap) moves
 *   forward by the gap: 2:30 AM on a New York spring-forward night is 3:30 AM summer time. One
 *   that happens twice (fall-back) is the EARLIER. java.time's ZonedDateTime.of does both.
 * - A day is scheduled when its local weekday's bit is set in [days] (bit 0 = Monday).
 * - An occurrence's window, where a check-in counts for it, starts at that day's from time
 *   ([fromMinute]), or at the start of the day.
 */
data class CheckInSchedule(
    /** Local weekdays: bit 0 = Monday ... bit 6 = Sunday, 1..127. */
    val days: Int,
    /** The deadline, in minutes after local midnight (0..1439). */
    val dueMinute: Int,
    /** When a day's check-ins start counting, in minutes after local midnight; null: the start of the day. */
    val fromMinute: Int?,
    val zone: ZoneId,
) {
    /** One occurrence (epoch ms): its deadline, and when its check-ins start counting. */
    data class Occurrence(val due: Long, val windowStart: Long)

    /** Whether [date], a local date in [zone], is one of the schedule's days. */
    fun isScheduled(date: LocalDate): Boolean = days and bit(date.dayOfWeek) != 0

    /** The occurrence on [date], a local date in [zone] (whether or not it is a scheduled day). */
    fun occurrenceOn(date: LocalDate): Occurrence =
        Occurrence(due = instant(date, dueMinute), windowStart = instant(date, fromMinute ?: 0))

    /**
     * The latest occurrence whose deadline is at or before [nowMs] (a deadline counts at its own
     * minute), looking back up to [LOOK_DAYS] days. Null only for a schedule with no day, which
     * [of] never makes.
     */
    fun latest(nowMs: Long): Occurrence? {
        val today = localDate(nowMs)
        return (0..LOOK_DAYS).asSequence()
            .map { today.minusDays(it.toLong()) }
            .filter(::isScheduled)
            .map(::occurrenceOn)
            .firstOrNull { it.due <= nowMs }
    }

    /** The earliest occurrence whose deadline is after [nowMs], looking ahead up to [LOOK_DAYS] days. */
    fun next(nowMs: Long): Occurrence? = nextAfter(nowMs)

    /** The earliest occurrence whose deadline is after [afterMs] (the one after [next] when given its due). */
    fun nextAfter(afterMs: Long): Occurrence? {
        val today = localDate(afterMs)
        return (0..LOOK_DAYS).asSequence()
            .map { today.plusDays(it.toLong()) }
            .filter(::isScheduled)
            .map(::occurrenceOn)
            .firstOrNull { it.due > afterMs }
    }

    /** The local date of [ms] in [zone]. */
    fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * [minute] after local midnight on [date] in [zone], as an instant (epoch ms): in a gap, moved
     * forward by the gap; when it happens twice, the earlier.
     */
    fun instant(date: LocalDate, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone).toInstant().toEpochMilli()

    companion object {
        const val EVERY_DAY = 0b111_1111
        const val WEEKDAYS = 0b001_1111
        const val WEEKENDS = 0b110_0000

        /** How far [latest] and [next] look: past any week. */
        const val LOOK_DAYS = 8

        /** [day]'s bit in a days mask. */
        fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

        /**
         * A schedule from the server's fields, or null when one is missing or out of range, or the
         * zone isn't one this phone knows: such a rule is judged by nobody, as the server wakes
         * nobody for it.
         */
        fun of(days: Int?, dueMinute: Int?, fromMinute: Int?, tz: String?): CheckInSchedule? {
            val mask = days?.takeIf { it in 1..EVERY_DAY } ?: return null
            val due = dueMinute?.takeIf { it in 0 until MINUTES_PER_DAY } ?: return null
            if (fromMinute != null && fromMinute !in 0 until due) return null
            val zone = tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return null
            return CheckInSchedule(mask, due, fromMinute, zone)
        }

        /** A stored rule's schedule: null unless it is a scheduled check-in rule whose schedule reads. */
        fun of(rule: InactivityAlertEntity): CheckInSchedule? =
            if (!InactivityWatches.isCheckIn(rule.watches) || !CheckInModes.isScheduled(rule.checkInMode)) null
            else of(rule.scheduleDays, rule.scheduleDueMinute, rule.scheduleFromMinute, rule.scheduleTz)

        /** A circle-mate's request's schedule, as [of] for a stored rule. */
        fun of(request: CircleSelfAlertDto): CheckInSchedule? =
            if (!request.watchesCheckIns || !CheckInModes.isScheduled(request.checkInMode)) null
            else of(request.scheduleDays, request.scheduleDueMinute, request.scheduleFromMinute, request.scheduleTz)

        /**
         * This phone's IANA zone, as a rule's `schedule_tz` takes it; null when the phone's zone
         * isn't a named one (a bare offset), which the server would refuse.
         */
        fun phoneZoneId(): String? =
            runCatching { ZoneId.systemDefault().id }.getOrNull()?.takeIf { it in ZoneId.getAvailableZoneIds() }

        private const val MINUTES_PER_DAY = 24 * 60
    }
}

/**
 * A scheduled check-in rule's schedule as its editor sets it and the server takes it (the
 * `schedule_*` fields). Kept with a change waiting to sync (PendingInputs.InactivityAlert), hence
 * the names.
 */
data class CheckInScheduleSetting(
    /** Local weekdays: bit 0 = Monday ... bit 6 = Sunday. */
    @SerializedName("days") val days: Int,
    /** The deadline, in minutes after local midnight. */
    @SerializedName("due_minute") val dueMinute: Int,
    /** When a day's check-ins start counting; null: the start of the day. */
    @SerializedName("from_minute") val fromMinute: Int?,
    /** The IANA zone the times are in. */
    @SerializedName("tz") val tz: String,
    /** About me only: the zone follows this account's phone (CheckInZoneFollower). */
    @SerializedName("follows_phone") val followsPhone: Boolean,
) {
    companion object {
        /** A stored scheduled rule's setting, for its editor; null on any other rule. */
        fun of(rule: InactivityAlertEntity): CheckInScheduleSetting? {
            if (!InactivityWatches.isCheckIn(rule.watches) || !CheckInModes.isScheduled(rule.checkInMode)) return null
            return CheckInScheduleSetting(
                days = rule.scheduleDays ?: return null,
                dueMinute = rule.scheduleDueMinute ?: return null,
                fromMinute = rule.scheduleFromMinute,
                tz = rule.scheduleTz ?: return null,
                followsPhone = rule.scheduleTzFollowsPhone,
            )
        }
    }
}
