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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * The occurrences of a scheduled check-in rule (CheckInSchedule), which the server
 * (track-workers check_in_schedule.rs), iOS and the web must agree on to the minute: local times in
 * the rule's zone; a time in a spring-forward gap moves forward by the gap, one that happens twice
 * is the earlier; the latest occurrence is the latest deadline at or before now on a scheduled day,
 * its window from that day's from time or its start; the next is the earliest deadline after now.
 * The same cases as the server's tests.
 */
class CheckInScheduleTest {

    private fun utc(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun schedule(days: Int, due: Int, from: Int? = null, tz: String) = CheckInSchedule.of(days, due, from, tz)!!

    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `every day by nine in New York`() {
        val s = schedule(CheckInSchedule.EVERY_DAY, 21 * 60, tz = "America/New_York")
        // 2026-11-24 is EST (UTC-5): 9:00 PM local is 02:00 UTC the next day.
        val before = utc("2026-11-25T01:59:00Z")
        val latest = s.latest(before)!!
        assertEquals("the 23rd's, 9:00 PM", utc("2026-11-24T02:00:00Z"), latest.due)
        assertEquals("from midnight on the 23rd", utc("2026-11-23T05:00:00Z"), latest.windowStart)
        val at = utc("2026-11-25T02:00:00Z")
        assertEquals("a deadline counts at its own minute", at, s.latest(at)!!.due)
        assertEquals(at, s.next(before)!!.due)
        assertEquals("the one after the next", utc("2026-11-26T02:00:00Z"), s.nextAfter(at)!!.due)
    }

    @Test
    fun `a from time starts the window that day`() {
        val s = schedule(CheckInSchedule.EVERY_DAY, 21 * 60, from = 18 * 60, tz = "Europe/Berlin")
        // 2026-11-24, CET (UTC+1): from 6 PM is 17:00 UTC, due 9 PM is 20:00 UTC.
        val o = s.latest(utc("2026-11-24T20:30:00Z"))!!
        assertEquals(utc("2026-11-24T20:00:00Z"), o.due)
        assertEquals(utc("2026-11-24T17:00:00Z"), o.windowStart)
        val next = s.next(utc("2026-11-24T20:30:00Z"))!!
        assertEquals(utc("2026-11-25T20:00:00Z"), next.due)
        assertEquals(utc("2026-11-25T17:00:00Z"), next.windowStart)
    }

    @Test
    fun `weekdays only carry Friday over the weekend, and a single day looks a week back`() {
        val s = schedule(CheckInSchedule.WEEKDAYS, 10 * 60, tz = "UTC")
        // 2026-11-28 is a Saturday, 11-29 a Sunday, 11-30 a Monday.
        assertEquals("Friday's", utc("2026-11-27T10:00:00Z"), s.latest(utc("2026-11-29T12:00:00Z"))!!.due)
        assertEquals("then Monday's", utc("2026-11-30T10:00:00Z"), s.next(utc("2026-11-28T12:00:00Z"))!!.due)
        // Sundays only: bit 6.
        val sundays = schedule(1 shl 6, 10 * 60, tz = "UTC")
        assertEquals("a week back", utc("2026-11-22T10:00:00Z"), sundays.latest(utc("2026-11-28T12:00:00Z"))!!.due)
        assertEquals("and a week on, from just after today's", utc("2026-11-29T10:00:00Z"), sundays.next(utc("2026-11-22T10:00:00Z"))!!.due)
    }

    @Test
    fun `a weekly pattern across a week boundary - Mondays and Fridays`() {
        val s = schedule(CheckInSchedule.bit(DayOfWeek.MONDAY) or CheckInSchedule.bit(DayOfWeek.FRIDAY), 9 * 60, tz = "UTC")
        // Sunday 2026-11-29: the latest is Friday the 27th, the next Monday the 30th.
        val sunday = utc("2026-11-29T15:00:00Z")
        val latest = s.latest(sunday)!!
        assertEquals(utc("2026-11-27T09:00:00Z"), latest.due)
        assertEquals("its window is Friday's, two days back", utc("2026-11-27T00:00:00Z"), latest.windowStart)
        assertEquals(utc("2026-11-30T09:00:00Z"), s.next(sunday)!!.due)
        // Monday after nine: Monday's is the latest, and Friday the next.
        val monday = utc("2026-11-30T09:30:00Z")
        assertEquals(utc("2026-11-30T09:00:00Z"), s.latest(monday)!!.due)
        assertEquals(utc("2026-12-04T09:00:00Z"), s.next(monday)!!.due)
        assertTrue(s.isScheduled(LocalDate.of(2026, 11, 30)))
        assertFalse(s.isScheduled(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `a spring-forward gap moves the time forward by the gap`() {
        // 2026-03-08: New York clocks go 2:00 EST to 3:00 EDT. 2:30 AM doesn't exist; moved by the
        // hour's gap it is 3:30 AM EDT, 07:30 UTC (the server's: read with the offset before the gap).
        val s = schedule(CheckInSchedule.EVERY_DAY, 2 * 60 + 30, tz = "America/New_York")
        assertEquals(utc("2026-03-08T07:30:00Z"), s.instant(LocalDate.of(2026, 3, 8), 2 * 60 + 30))
        assertEquals(utc("2026-03-08T07:30:00Z"), s.latest(utc("2026-03-08T07:30:00Z"))!!.due)
        assertEquals("the day before: EST", utc("2026-03-07T07:30:00Z"), s.latest(utc("2026-03-08T07:29:00Z"))!!.due)
        assertEquals("the day after: EDT", utc("2026-03-09T06:30:00Z"), s.next(utc("2026-03-08T07:30:00Z"))!!.due)
    }

    @Test
    fun `a fall-back repeat takes the earlier time`() {
        // 2026-11-01: New York clocks go 2:00 EDT to 1:00 EST. 1:30 AM happens twice; the earlier
        // is 1:30 EDT, 05:30 UTC.
        val s = schedule(CheckInSchedule.EVERY_DAY, 90, tz = "America/New_York")
        assertEquals(utc("2026-11-01T05:30:00Z"), s.instant(LocalDate.of(2026, 11, 1), 90))
        // Midnight that day is still EDT: the window starts at 04:00 UTC.
        assertEquals(utc("2026-11-01T04:00:00Z"), s.latest(utc("2026-11-01T06:00:00Z"))!!.windowStart)
        assertEquals("and the second 1:30 is no new deadline", utc("2026-11-01T05:30:00Z"), s.latest(utc("2026-11-01T06:30:00Z"))!!.due)
    }

    @Test
    fun `local dates are the zone's`() {
        val s = schedule(CheckInSchedule.EVERY_DAY, 21 * 60, tz = "America/New_York")
        // 03:00 UTC on the 25th is 10:00 PM on the 24th in New York.
        assertEquals(LocalDate.of(2026, 11, 24), s.localDate(utc("2026-11-25T03:00:00Z")))
        assertEquals(newYork, s.zone)
    }

    @Test
    fun `a schedule out of range, or in a zone this phone doesn't know, is no schedule`() {
        assertNull("no day", CheckInSchedule.of(0, 60, null, "UTC"))
        assertNull(CheckInSchedule.of(128, 60, null, "UTC"))
        assertNull(CheckInSchedule.of(1, 1440, null, "UTC"))
        assertNull(CheckInSchedule.of(1, -1, null, "UTC"))
        assertNull("from must be before due", CheckInSchedule.of(1, 60, 60, "UTC"))
        assertNull(CheckInSchedule.of(1, 60, -1, "UTC"))
        assertNull(CheckInSchedule.of(1, 60, null, "Mars/Olympus"))
        assertNull(CheckInSchedule.of(null, 60, null, "UTC"))
        assertNull(CheckInSchedule.of(1, null, null, "UTC"))
        assertNull(CheckInSchedule.of(1, 60, null, null))
        assertEquals(CheckInSchedule(1, 60, 59, ZoneId.of("UTC")), CheckInSchedule.of(1, 60, 59, "UTC"))
    }

    @Test
    fun `the phone's zone is its IANA name, and a bare offset is none`() {
        val was = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("Asia/Tokyo", CheckInSchedule.phoneZoneId())
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
            assertNull(CheckInSchedule.phoneZoneId())
        } finally {
            TimeZone.setDefault(was)
        }
    }
}
