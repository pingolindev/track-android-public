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
import java.util.Formatter
import java.util.Locale

/**
 * The countdown on this account's own check-in rule about itself ("Notify my circle if I don't
 * check in"): when its circle is next alerted.
 *
 * Someone who sets up an alert on themselves sees the time until the next
 * alert to the circle.
 *
 * A one-time rule ("check in within N hours") alerts the circle its hours after its start: the
 * server wakes the circle's phones then, and each judges the same sum
 * (MonitoringManager.onceVerdict). Once this account has checked in after the start, the rule has
 * ended and shows no countdown. A scheduled rule ("check in by 9:00 PM") alerts the circle at its
 * time on its days unless this account checked in since that day's window opened
 * (MonitoringManager.scheduledVerdict): its line says which ([scheduledLine]). Only a check-in the
 * server has counts (CheckInRepository.newestOwnCheckIn): one still waiting on this phone is seen
 * by none of them. A location rule about yourself gets no countdown: its clock starts again with
 * every location this phone sends. The same rules and words as the web app's (selfCheckInCountdown.ts).
 */
object CheckInCountdown {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L

    /** This account's own active check-in rule about itself: the kind that counts down. */
    fun appliesTo(rule: InactivityAlertEntity): Boolean =
        rule.isActive && rule.isSelfAlert && InactivityWatches.isCheckIn(rule.watches)

    /** What one rule's countdown line is about. */
    sealed interface Countdown {
        /** A one-time rule: its circle is alerted at [deadline]. */
        data class Once(val deadline: Long) : Countdown

        /**
         * A scheduled rule: its [schedule], its start ([armedAt]), and this account's newest check-in
         * there that the server has, from the read's start on ([readFrom]; null: none).
         */
        data class Scheduled(val schedule: CheckInSchedule, val armedAt: Long, val newest: Long?) : Countdown
    }

    /**
     * When a one-time rule's circle is alerted: the later of [newestAccepted] (this account's
     * newest check-in there that the server has; null: none) and [armedAt] (the rule's start),
     * plus the rule's hours.
     */
    fun deadline(newestAccepted: Long?, armedAt: Long, timeoutHours: Int): Long =
        maxOf(newestAccepted ?: armedAt, armedAt) + timeoutHours.coerceAtLeast(1) * HOUR_MS

    /**
     * Where a counting-down rule's read of this account's check-ins starts at [now]: a one-time
     * rule's start; a scheduled rule's latest occurrence's window (an older check-in counts for no
     * occurrence that is still to come or open), or its look-back when it has none. Null: no read,
     * no line (a rule without a start, or a schedule that doesn't read).
     */
    fun readFrom(rule: InactivityAlertEntity, now: Long): Long? {
        if (!appliesTo(rule)) return null
        val armedAt = rule.armedAt ?: return null
        if (!CheckInModes.isScheduled(rule.checkInMode)) return armedAt
        val schedule = CheckInSchedule.of(rule) ?: return null
        return schedule.latest(now)?.windowStart ?: (now - CheckInSchedule.LOOK_DAYS * 24 * HOUR_MS)
    }

    /**
     * Each counting-down rule in [rules] to its countdown, for the circles a read answered
     * ([newestByCircle]: circle to this account's newest accepted check-in there from the read's
     * start on, null for none). A circle the read didn't answer, a rule without a start, a one-time
     * rule that has ended (checked in after its start), or a schedule that doesn't read, has none:
     * no line rather than a wrong one.
     */
    fun countdowns(rules: List<InactivityAlertEntity>, newestByCircle: Map<String, Long?>): Map<String, Countdown> =
        rules.filter(::appliesTo).mapNotNull { rule ->
            val armedAt = rule.armedAt ?: return@mapNotNull null
            if (rule.circleId !in newestByCircle) return@mapNotNull null
            val newest = newestByCircle[rule.circleId]
            val countdown: Countdown = if (CheckInModes.isScheduled(rule.checkInMode)) {
                Countdown.Scheduled(CheckInSchedule.of(rule) ?: return@mapNotNull null, armedAt, newest)
            } else {
                // Ended at the check-in: the server turns it off, and this says so before that reaches here.
                if (newest != null && newest > armedAt) return@mapNotNull null
                Countdown.Once(deadline(newest, armedAt, rule.timeoutHours))
            }
            rule.id to countdown
        }.toMap()

    /** How a one-time countdown stands at a moment ([left]). */
    sealed interface Left {
        /** In [hours] h [minutes] min: the time left in whole minutes, rounded down, one or more. */
        data class Time(val hours: Long, val minutes: Long) : Left

        /** Less than a minute left. */
        data object UnderAMinute : Left

        /** The deadline has come: the circle was alerted [at] it. */
        data class Due(val at: Long) : Left
    }

    /** Where the countdown to [deadline] stands at [now]: at the deadline itself, it is due. */
    fun left(deadline: Long, now: Long): Left {
        if (now >= deadline) return Left.Due(deadline)
        val minutes = (deadline - now) / MINUTE_MS
        return if (minutes < 1) Left.UnderAMinute else Left.Time(minutes / 60, minutes % 60)
    }

    /** What a scheduled rule's line says at a moment ([scheduledLine]). */
    sealed interface Line {
        /** The latest occurrence, due at [due], went off: no check-in since its window opened. */
        data class Missed(val due: Long) : Line

        /** The next occurrence's window is open and nothing counts for it yet: the circle is alerted at [due] unless a check-in comes. */
        data class Pending(val due: Long) : Line

        /**
         * Nothing is open: the next occurrence is due at [due]. [checkedIn]: a check-in counts for
         * the occurrence just past (not so for a rule started after it, which has had none).
         */
        data class Next(val due: Long, val checkedIn: Boolean) : Line
    }

    /**
     * Where a scheduled rule's countdown stands at [now], with D its latest occurrence and N its
     * next: missed when the rule was started before D's deadline and no check-in has come since
     * D's window opened (the circle's phones raised it; this wins, or a missed day would hide
     * behind the next); else pending when N's window is open with no check-in in it; else the next
     * deadline nothing counts for yet: the one after N when a check-in counted for N, else N. Null
     * for a schedule with no day ahead, which a valid one never is.
     */
    fun scheduledLine(countdown: Countdown.Scheduled, now: Long): Line? {
        val schedule = countdown.schedule
        val newest = countdown.newest
        val latest = schedule.latest(now)
        if (latest != null && countdown.armedAt < latest.due && (newest == null || newest < latest.windowStart)) {
            return Line.Missed(latest.due)
        }
        val next = schedule.next(now) ?: return null
        if (next.windowStart > now) {
            val countedForLatest = latest != null && newest != null && newest >= latest.windowStart
            return Line.Next(next.due, checkedIn = countedForLatest)
        }
        if (newest == null || newest < next.windowStart) return Line.Pending(next.due)
        val after = schedule.nextAfter(next.due) ?: return null
        return Line.Next(after.due, checkedIn = true)
    }

    /**
     * When the circle is alerted by [countdown] as it stands at [now] (a missed one: when it was):
     * which of several rules in a circle the check-in screen shows, the soonest.
     */
    fun alertAt(countdown: Countdown, now: Long): Long? = when (countdown) {
        is Countdown.Once -> countdown.deadline
        is Countdown.Scheduled -> when (val line = scheduledLine(countdown, now)) {
            is Line.Missed -> line.due
            is Line.Pending -> line.due
            is Line.Next -> line.due
            null -> null
        }
    }

    /**
     * How long until the line for [deadline] reads differently, from [now]: the next whole minute
     * of the time left, and at most a minute (a due line's date can change at midnight).
     */
    fun nextChangeIn(deadline: Long, now: Long): Long {
        if (now >= deadline) return MINUTE_MS
        return minOf((deadline - now) % MINUTE_MS + 1, MINUTE_MS)
    }

    /**
     * How long until [countdown]'s line reads differently, from [now]: a one-time rule's as above; a
     * scheduled rule's at its next deadline or window opening, and at most a minute (its day words
     * change at midnight).
     */
    fun nextChangeIn(countdown: Countdown, now: Long): Long = when (countdown) {
        is Countdown.Once -> nextChangeIn(countdown.deadline, now)
        is Countdown.Scheduled -> {
            val next = countdown.schedule.next(now)
            val boundary = listOfNotNull(next?.due, next?.windowStart).filter { it > now }.minOrNull()
            if (boundary == null) MINUTE_MS else minOf(boundary - now + 1, MINUTE_MS)
        }
    }

    /**
     * "Your circle is alerted in 3 h 12 min" (or "in 3 h", "in 45 min", "in less than a minute"),
     * or once due "Your circle was alerted at 9:40 PM. Check in to clear it." (on its date too when
     * it wasn't today), in this phone's time format. Each a whole sentence of its own for the
     * translators: nothing is pieced together.
     */
    fun text(context: Context, deadline: Long, now: Long): String = when (val left = left(deadline, now)) {
        is Left.Time -> when {
            left.hours < 1 -> context.getString(R.string.check_in_countdown_in_minutes, left.minutes)
            left.minutes == 0L -> context.getString(R.string.check_in_countdown_in_hours, left.hours)
            else -> context.getString(R.string.check_in_countdown_in_hours_minutes, left.hours, left.minutes)
        }
        Left.UnderAMinute -> context.getString(R.string.check_in_countdown_soon)
        is Left.Due -> {
            val time = DateUtils.formatDateTime(context, left.at, DateUtils.FORMAT_SHOW_TIME)
            if (DateUtils.isToday(left.at)) {
                context.getString(R.string.check_in_countdown_due, time)
            } else {
                val date = DateUtils.formatDateTime(context, left.at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
                context.getString(R.string.check_in_countdown_due_on, date, time)
            }
        }
    }

    /**
     * [countdown]'s line at [now]: a one-time rule's as [text] above. A scheduled rule's says its
     * time as set, its days judged in its zone:
     * - missed: "Your circle was alerted at 9:00 PM. Check in to clear it." (on its date too when
     *   that wasn't today);
     * - pending: "Your circle is alerted at 9:00 PM unless you check in.";
     * - next: "Checked in. Next due tomorrow by 9:00 PM." (today, tomorrow, or the day's name), or
     *   without "Checked in." when nothing counted for the occurrence just past.
     * Null when it has no line ([scheduledLine]).
     */
    fun text(context: Context, countdown: Countdown, now: Long): String? = when (countdown) {
        is Countdown.Once -> text(context, countdown.deadline, now)
        is Countdown.Scheduled -> scheduledText(context, countdown.schedule, scheduledLine(countdown, now), now)
    }

    private fun scheduledText(context: Context, schedule: CheckInSchedule, line: Line?, now: Long): String? {
        val time = CheckInRuleText.time(context, schedule.dueMinute)
        val today = schedule.localDate(now)
        return when (line) {
            null -> null
            is Line.Missed -> {
                val day = schedule.localDate(line.due)
                if (day == today) {
                    context.getString(R.string.check_in_countdown_due, time)
                } else {
                    val date = DateUtils.formatDateRange(
                        context, Formatter(StringBuilder(), Locale.getDefault()), line.due, line.due,
                        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH, schedule.zone.id,
                    ).toString()
                    context.getString(R.string.check_in_countdown_due_on, date, time)
                }
            }
            is Line.Pending -> context.getString(R.string.check_in_countdown_by, time)
            is Line.Next -> {
                val day = schedule.localDate(line.due)
                when {
                    day == today -> context.getString(
                        if (line.checkedIn) R.string.check_in_countdown_checked_in_next_today else R.string.check_in_countdown_next_today,
                        time,
                    )
                    day == today.plusDays(1) -> context.getString(
                        if (line.checkedIn) R.string.check_in_countdown_checked_in_next_tomorrow else R.string.check_in_countdown_next_tomorrow,
                        time,
                    )
                    else -> context.getString(
                        if (line.checkedIn) R.string.check_in_countdown_checked_in_next_on else R.string.check_in_countdown_next_on,
                        CheckInRuleText.dayName(context, day.dayOfWeek),
                        time,
                    )
                }
            }
        }
    }
}
