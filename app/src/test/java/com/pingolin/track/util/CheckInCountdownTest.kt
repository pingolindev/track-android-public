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
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.util.CheckInCountdown.Left
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The countdown on this account's own one-time check-in rule about itself (CheckInCountdown): the
 * circle is alerted the rule's hours after its start, and a check-in the server has after the
 * start ends the rule (no line); the line says how long is left, in whole minutes rounded down, or,
 * once due, when the circle was alerted. A scheduled rule's lines: CheckInScheduledCountdownTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInCountdownTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val minute = 60_000L
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    private fun rule(
        id: String = "r-1",
        circle: String = "c-1",
        self: Boolean = true,
        active: Boolean = true,
        watches: String = InactivityWatches.CHECK_IN,
        armedAt: Long? = now - 10 * hour,
        hours: Int = 4,
    ) = InactivityAlertEntity(
        id = id, userId = "me", circleId = circle, targetUserId = "me", timeoutHours = hours, isSelfAlert = self,
        isActive = active, dayBucket = 0, watches = watches, armedAt = armedAt,
    )

    // ── the deadline ─────────────────────────────────────────────────────────

    @Test
    fun `the rule's hours after the newest accepted check-in`() {
        assertEquals(now - hour + 4 * hour, CheckInCountdown.deadline(newestAccepted = now - hour, armedAt = now - 10 * hour, timeoutHours = 4))
    }

    @Test
    fun `a rule started after the last check-in counts from its start`() {
        assertEquals(now - hour + 4 * hour, CheckInCountdown.deadline(newestAccepted = now - 30 * hour, armedAt = now - hour, timeoutHours = 4))
        assertEquals("with no check-in at all", now - hour + 4 * hour, CheckInCountdown.deadline(newestAccepted = null, armedAt = now - hour, timeoutHours = 4))
    }

    @Test
    fun `a check-in still waiting on this phone ends nothing - only what the server has counts`() {
        // The server's newest is from before the rule; the one this phone still holds would end it.
        val accepted = now - 30 * hour
        val countdown = CheckInCountdown.countdowns(listOf(rule(hours = 4, armedAt = now - hour)), mapOf("c-1" to accepted))
        assertEquals(mapOf("r-1" to CheckInCountdown.Countdown.Once(now - hour + 4 * hour)), countdown)
    }

    @Test
    fun `a one-time rule ends at the first check-in after its start - no line, not a new countdown`() {
        val armed = now - 10 * hour
        assertEquals(
            emptyMap<String, CheckInCountdown.Countdown>(),
            CheckInCountdown.countdowns(listOf(rule(armedAt = armed)), mapOf("c-1" to armed + 1)),
        )
        assertEquals(
            "a check-in at the very start is not after it",
            mapOf("r-1" to CheckInCountdown.Countdown.Once(armed + 4 * hour)),
            CheckInCountdown.countdowns(listOf(rule(armedAt = armed)), mapOf("c-1" to armed)),
        )
    }

    @Test
    fun `only this account's own active check-in rules about itself count down`() {
        assertTrue(CheckInCountdown.appliesTo(rule()))
        assertFalse("a location rule about yourself restarts with every location", CheckInCountdown.appliesTo(rule(watches = InactivityWatches.LOCATION)))
        assertFalse("off", CheckInCountdown.appliesTo(rule(active = false)))
        assertFalse("about someone else", CheckInCountdown.appliesTo(rule(self = false)))
    }

    @Test
    fun `a circle whose read didn't answer, or a rule without a start, shows nothing`() {
        val rules = listOf(rule("r-1", "c-1"), rule("r-2", "c-2"), rule("r-3", "c-1", armedAt = null), rule("r-4", "c-1", self = false))
        assertEquals(
            mapOf("r-1" to CheckInCountdown.Countdown.Once(now - 10 * hour + 4 * hour)),
            CheckInCountdown.countdowns(rules, mapOf("c-1" to null)),
        )
        assertEquals(emptyMap<String, CheckInCountdown.Countdown>(), CheckInCountdown.countdowns(rules, emptyMap()))
    }

    // ── the time left ────────────────────────────────────────────────────────

    @Test
    fun `whole minutes, rounded down`() {
        val deadline = now + 3 * hour + 12 * minute + 59_000L
        assertEquals(Left.Time(3, 12), CheckInCountdown.left(deadline, now))
        assertEquals(Left.Time(0, 45), CheckInCountdown.left(now + 45 * minute + 30_000L, now))
        assertEquals(Left.Time(1, 0), CheckInCountdown.left(now + hour, now))
        assertEquals(Left.Time(0, 1), CheckInCountdown.left(now + minute, now))
        assertEquals(Left.UnderAMinute, CheckInCountdown.left(now + minute - 1, now))
        assertEquals(Left.UnderAMinute, CheckInCountdown.left(now + 1, now))
    }

    @Test
    fun `at the deadline and after it, it is due`() {
        assertEquals(Left.Due(now), CheckInCountdown.left(now, now))
        assertEquals(Left.Due(now - 2 * hour), CheckInCountdown.left(now - 2 * hour, now))
    }

    @Test
    fun `the next change comes at the next whole minute left, and at most a minute away`() {
        assertEquals(30_001L, CheckInCountdown.nextChangeIn(now + 5 * minute + 30_000L, now))
        assertEquals(1L, CheckInCountdown.nextChangeIn(now + 5 * minute, now))
        assertEquals(60_000L, CheckInCountdown.nextChangeIn(now + 5 * minute - 1, now))
        assertEquals("due: a minute", 60_000L, CheckInCountdown.nextChangeIn(now - hour, now))
        // And the line does change then.
        val deadline = now + 5 * minute + 30_000L
        val next = now + CheckInCountdown.nextChangeIn(deadline, now)
        assertEquals(Left.Time(0, 5), CheckInCountdown.left(deadline, next - 1))
        assertEquals(Left.Time(0, 4), CheckInCountdown.left(deadline, next))
    }

    // ── the words ────────────────────────────────────────────────────────────

    @Test
    fun `the words before the deadline, word for word`() {
        assertEquals("Your circle is alerted in 3 h 12 min", CheckInCountdown.text(context, now + 3 * hour + 12 * minute + 1, now))
        assertEquals("Your circle is alerted in 3 h", CheckInCountdown.text(context, now + 3 * hour + 30_000L, now))
        assertEquals("Your circle is alerted in 45 min", CheckInCountdown.text(context, now + 45 * minute, now))
        assertEquals("Your circle is alerted in less than a minute", CheckInCountdown.text(context, now + 59_000L, now))
    }

    @Test
    fun `each line is a whole sentence of its own for the translators, nothing pieced together`() {
        val english = java.io.File("src/main/res/values/strings.xml").readText()
        val lines = Regex("""<string name="(check_in_countdown\w*)">([^<]*)</string>""").findAll(english)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            setOf(
                "check_in_countdown_in_hours_minutes", "check_in_countdown_in_hours", "check_in_countdown_in_minutes",
                "check_in_countdown_soon", "check_in_countdown_due", "check_in_countdown_due_on",
                // A scheduled rule's (CheckInScheduledCountdownTest).
                "check_in_countdown_by",
                "check_in_countdown_checked_in_next_today", "check_in_countdown_checked_in_next_tomorrow", "check_in_countdown_checked_in_next_on",
                "check_in_countdown_next_today", "check_in_countdown_next_tomorrow", "check_in_countdown_next_on",
            ),
            lines.keys,
        )
        val next = lines.filterKeys { "_next_" in it }
        assertTrue("$lines", (lines - next.keys).values.all { it.startsWith("Your circle ") })
        assertTrue("$next", next.values.all { it.startsWith("Checked in. Next due ") || it.startsWith("Next due ") })
        assertTrue("$next", next.values.all { it.endsWith(".") })
        assertEquals("Your circle is alerted in 3 h 12 min", context.getString(com.pingolin.track.R.string.check_in_countdown_in_hours_minutes, 3, 12))
        assertEquals("Your circle is alerted in 3 h", context.getString(com.pingolin.track.R.string.check_in_countdown_in_hours, 3))
        assertEquals("Your circle is alerted in 45 min", context.getString(com.pingolin.track.R.string.check_in_countdown_in_minutes, 45))
        assertEquals("Your circle is alerted in less than a minute", context.getString(com.pingolin.track.R.string.check_in_countdown_soon))
        assertEquals(
            "Your circle was alerted at 9:40 PM. Check in to clear it.",
            context.getString(com.pingolin.track.R.string.check_in_countdown_due, "9:40 PM"),
        )
        assertEquals(
            "Your circle was alerted on Jan 15 at 9:40 PM. Check in to clear it.",
            context.getString(com.pingolin.track.R.string.check_in_countdown_due_on, "Jan 15", "9:40 PM"),
        )
    }

    @Test
    fun `overdue - when the circle was alerted, in this phone's time format, and what clears it`() {
        // The framework's "today" (DateUtils.isToday) runs on Robolectric's clock: set it to this one's.
        android.os.SystemClock.setCurrentTimeMillis(System.currentTimeMillis())
        val today = startOfToday() + 9 * hour + 40 * minute
        val time = DateUtils.formatDateTime(context, today, DateUtils.FORMAT_SHOW_TIME)
        assertEquals("Your circle was alerted at $time. Check in to clear it.", CheckInCountdown.text(context, today, today + hour))

        val yesterday = today - 24 * hour
        val date = DateUtils.formatDateTime(context, yesterday, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        assertEquals(
            "Your circle was alerted on $date at $time. Check in to clear it.",
            CheckInCountdown.text(context, yesterday, today + hour),
        )
    }

    private fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
