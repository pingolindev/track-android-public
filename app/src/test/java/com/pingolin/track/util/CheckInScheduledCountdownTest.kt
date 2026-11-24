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
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.util.CheckInCountdown.Countdown
import com.pingolin.track.util.CheckInCountdown.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * The countdown on this account's own scheduled check-in rule about itself (CheckInCountdown): with
 * D its latest occurrence and N its next, "Your circle was alerted at 9:00 PM. Check in to clear it."
 * when D was missed (on its date when that wasn't today; this wins), "Your circle is alerted at
 * 9:00 PM unless you check in." while N's window is open with no check-in in it, else "Checked in.
 * Next due {today | tomorrow | Monday} by 9:00 PM.", without "Checked in." when nothing counted for
 * the occurrence just past (a rule made after it). Times as set, days judged in the rule's zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInScheduledCountdownTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()
    private val longAgo = utc("2026-11-01T00:00:00Z")

    /** Every day by 9:00 PM UTC; [from] a from time. */
    private fun everyDay(from: Int? = null, days: Int = CheckInSchedule.EVERY_DAY) = CheckInSchedule(days, 21 * 60, from, ZoneId.of("UTC"))

    private fun scheduled(newest: String?, armedAt: Long = longAgo, schedule: CheckInSchedule = everyDay()) =
        Countdown.Scheduled(schedule, armedAt, newest?.let(::utc))

    private val nine get() = CheckInRuleText.time(context, 21 * 60)

    // ── the lines ────────────────────────────────────────────────────────────

    @Test
    fun `before the time, nothing for today yet - alerted at its time unless you check in`() {
        // Yesterday's counted (checked in at 10:00 PM); today's window is open since midnight.
        val countdown = scheduled(newest = "2026-11-23T22:00:00Z")
        val now = utc("2026-11-24T15:00:00Z")
        assertEquals(Line.Pending(utc("2026-11-24T21:00:00Z")), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Your circle is alerted at $nine unless you check in.", CheckInCountdown.text(context, countdown, now))
    }

    @Test
    fun `checked in for today - next due tomorrow`() {
        val countdown = scheduled(newest = "2026-11-24T10:00:00Z")
        val now = utc("2026-11-24T15:00:00Z")
        assertEquals(Line.Next(utc("2026-11-25T21:00:00Z"), checkedIn = true), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Checked in. Next due tomorrow by $nine.", CheckInCountdown.text(context, countdown, now))
        // And after today's time, the same.
        assertEquals("Checked in. Next due tomorrow by $nine.", CheckInCountdown.text(context, countdown, utc("2026-11-24T22:00:00Z")))
    }

    @Test
    fun `checked in for the last one, and the next window not open yet - next due today`() {
        // From 6:00 PM: yesterday's counted at 7:00 PM; today's window opens at 6:00 PM.
        val countdown = scheduled(newest = "2026-11-23T19:00:00Z", schedule = everyDay(from = 18 * 60))
        val now = utc("2026-11-24T15:00:00Z")
        assertEquals(Line.Next(utc("2026-11-24T21:00:00Z"), checkedIn = true), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Checked in. Next due today by $nine.", CheckInCountdown.text(context, countdown, now))
        // A check-in at 2:00 PM, before the window, counts for yesterday's, not today's.
        val early = scheduled(newest = "2026-11-24T14:00:00Z", schedule = everyDay(from = 18 * 60))
        assertEquals(
            Line.Pending(utc("2026-11-24T21:00:00Z")),
            CheckInCountdown.scheduledLine(early, utc("2026-11-24T18:30:00Z")),
        )
    }

    @Test
    fun `missed today - alerted at its time, check in to clear it`() {
        val countdown = scheduled(newest = "2026-11-23T22:00:00Z")
        val now = utc("2026-11-24T21:30:00Z")
        assertEquals(Line.Missed(utc("2026-11-24T21:00:00Z")), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Your circle was alerted at $nine. Check in to clear it.", CheckInCountdown.text(context, countdown, now))
        // A check-in clears it: next due tomorrow.
        assertEquals("Checked in. Next due tomorrow by $nine.", CheckInCountdown.text(context, scheduled("2026-11-24T21:35:00Z"), now + 600_000L))
    }

    @Test
    fun `missed on an earlier day - its date too, and it wins over the next one's line`() {
        // From 6:00 PM: yesterday's missed (last check-in at noon, before its window). Today's
        // window is open too, yet the missed one shows: a check-in clears it.
        val countdown = scheduled(newest = "2026-11-24T12:00:00Z", schedule = everyDay(from = 18 * 60))
        val now = utc("2026-11-25T19:00:00Z")
        assertEquals(Line.Missed(utc("2026-11-24T21:00:00Z")), CheckInCountdown.scheduledLine(countdown, now))
        val date = android.text.format.DateUtils.formatDateRange(
            context, java.util.Formatter(StringBuilder(), java.util.Locale.getDefault()), utc("2026-11-24T21:00:00Z"), utc("2026-11-24T21:00:00Z"),
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH, "UTC",
        ).toString()
        assertEquals("the date of its zone's day", true, date.startsWith("Nov 24"))
        assertEquals("Your circle was alerted on $date at $nine. Check in to clear it.", CheckInCountdown.text(context, countdown, now))
    }

    @Test
    fun `a rule made after today's time hasn't missed it, and says no Checked in`() {
        val countdown = scheduled(newest = null, armedAt = utc("2026-11-24T21:30:00Z"))
        val now = utc("2026-11-24T22:00:00Z")
        assertEquals(Line.Next(utc("2026-11-25T21:00:00Z"), checkedIn = false), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Next due tomorrow by $nine.", CheckInCountdown.text(context, countdown, now))
        // Made at 2:00 PM with a 6:00 PM from time: waiting for today's window.
        val fresh = scheduled(newest = null, armedAt = utc("2026-11-24T14:00:00Z"), schedule = everyDay(from = 18 * 60))
        assertEquals("Next due today by $nine.", CheckInCountdown.text(context, fresh, utc("2026-11-24T15:00:00Z")))
    }

    @Test
    fun `a day further off says its name`() {
        // Mondays only. Tuesday 2026-11-24: Monday's counted; the next is Monday the 30th.
        val mondays = everyDay(days = CheckInSchedule.bit(DayOfWeek.MONDAY))
        val countdown = scheduled(newest = "2026-11-23T20:00:00Z", schedule = mondays)
        val now = utc("2026-11-24T22:00:00Z")
        assertEquals(Line.Next(utc("2026-11-30T21:00:00Z"), checkedIn = true), CheckInCountdown.scheduledLine(countdown, now))
        assertEquals("Checked in. Next due Monday by $nine.", CheckInCountdown.text(context, countdown, now))
        assertEquals("Next due Monday by $nine.", CheckInCountdown.text(context, scheduled(null, armedAt = now - 1, schedule = mondays), now))
    }

    @Test
    fun `today and tomorrow are the rule's zone's`() {
        // Every day by 9:00 PM in Tokyo (UTC+9). 2026-11-24 15:00 UTC is 0:00 on the 25th there:
        // checked in at 0:10 there (the 25th's window), the next is the 26th's, tomorrow there.
        val tokyo = CheckInSchedule(CheckInSchedule.EVERY_DAY, 21 * 60, null, ZoneId.of("Asia/Tokyo"))
        val countdown = scheduled(newest = "2026-11-24T15:10:00Z", schedule = tokyo)
        assertEquals("Checked in. Next due tomorrow by $nine.", CheckInCountdown.text(context, countdown, utc("2026-11-24T15:20:00Z")))
    }

    // ── when the line reads differently ──────────────────────────────────────

    @Test
    fun `the next change comes at the next deadline or window opening, and at most a minute away`() {
        val countdown = scheduled(newest = "2026-11-23T22:00:00Z")
        assertEquals(30_001L, CheckInCountdown.nextChangeIn(countdown, utc("2026-11-24T20:59:30Z")))
        assertEquals(60_000L, CheckInCountdown.nextChangeIn(countdown, utc("2026-11-24T15:00:00Z")))
        val fromSix = scheduled(newest = "2026-11-23T19:00:00Z", schedule = everyDay(from = 18 * 60))
        assertEquals("the window opening", 10_001L, CheckInCountdown.nextChangeIn(fromSix, utc("2026-11-24T17:59:50Z")))
        // And at that moment it does read differently.
        assertEquals(Line.Next(utc("2026-11-24T21:00:00Z"), true), CheckInCountdown.scheduledLine(fromSix, utc("2026-11-24T17:59:59Z")))
        assertEquals(Line.Pending(utc("2026-11-24T21:00:00Z")), CheckInCountdown.scheduledLine(fromSix, utc("2026-11-24T18:00:00Z")))
    }

    @Test
    fun `which of several shows - the soonest alert, a missed one first`() {
        val now = utc("2026-11-24T21:30:00Z")
        assertEquals(utc("2026-11-24T21:00:00Z"), CheckInCountdown.alertAt(scheduled("2026-11-23T22:00:00Z"), now))
        assertEquals(utc("2026-11-25T21:00:00Z"), CheckInCountdown.alertAt(scheduled("2026-11-24T21:10:00Z"), now))
        assertEquals(now + 1, CheckInCountdown.alertAt(Countdown.Once(now + 1), now))
    }

    // ── what it reads, and from when ─────────────────────────────────────────

    private fun rule(mode: String?, armedAt: Long? = longAgo, active: Boolean = true, tz: String = "UTC") = InactivityAlertEntity(
        id = "r-1", userId = "me", circleId = "c-1", targetUserId = "me", timeoutHours = 1, isSelfAlert = true,
        isActive = active, dayBucket = 0, watches = InactivityWatches.CHECK_IN, armedAt = armedAt, checkInMode = mode,
        scheduleDays = CheckInSchedule.EVERY_DAY, scheduleDueMinute = 21 * 60, scheduleTz = tz,
    )

    @Test
    fun `a scheduled rule's read starts at its latest occurrence's window, a one-time rule's at its start`() {
        val now = utc("2026-11-24T15:00:00Z")
        assertEquals(utc("2026-11-23T00:00:00Z"), CheckInCountdown.readFrom(rule(CheckInModes.SCHEDULED), now))
        assertEquals(longAgo, CheckInCountdown.readFrom(rule(CheckInModes.ONCE), now))
        assertEquals("a rule from before Room v51 is one-time", longAgo, CheckInCountdown.readFrom(rule(null), now))
        assertNull("off", CheckInCountdown.readFrom(rule(CheckInModes.SCHEDULED, active = false), now))
        assertNull("no start", CheckInCountdown.readFrom(rule(CheckInModes.SCHEDULED, armedAt = null), now))
        assertNull("a schedule that doesn't read", CheckInCountdown.readFrom(rule(CheckInModes.SCHEDULED, tz = "Mars/Olympus"), now))
    }

    @Test
    fun `a scheduled rule counts down by its schedule, from what the read found`() {
        val scheduledRule = rule(CheckInModes.SCHEDULED)
        assertEquals(
            mapOf("r-1" to Countdown.Scheduled(everyDay(), longAgo, utc("2026-11-24T10:00:00Z"))),
            CheckInCountdown.countdowns(listOf(scheduledRule), mapOf("c-1" to utc("2026-11-24T10:00:00Z"))),
        )
        assertEquals(
            "a schedule that doesn't read shows nothing",
            emptyMap<String, Countdown>(),
            CheckInCountdown.countdowns(listOf(rule(CheckInModes.SCHEDULED, tz = "Mars/Olympus")), mapOf("c-1" to null)),
        )
    }
}
