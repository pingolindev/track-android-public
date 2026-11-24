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

import com.pingolin.track.util.MonitoringManager.Companion.CheckInVerdict
import com.pingolin.track.util.MonitoringManager.Companion.checkInVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One check-in rule's verdict on one member (MonitoringManager.checkInVerdict): the silence
 * counts from the later of their last check-in and the rule's start; a lapse ends on a check-in
 * after its start, or when the rule started again after it; an alert goes off over the limit
 * with no lapse open (or one this verdict ends).
 */
class CheckInVerdictTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val limit = 4 * hour

    private fun verdict(last: Long?, armedAt: Long, openSince: Long? = null) =
        checkInVerdict(last = last, armedAt = armedAt, openSince = openSince, timeoutMs = limit, nowMs = now)

    @Test
    fun `over the limit since the last check-in goes off, within it does not`() {
        assertEquals(CheckInVerdict(resolve = false, fire = true, since = now - 5 * hour), verdict(now - 5 * hour, now - 48 * hour))
        assertEquals(CheckInVerdict(resolve = false, fire = false, since = now - 3 * hour), verdict(now - 3 * hour, now - 48 * hour))
        // Exactly at the limit is not over it, as on the server.
        assertEquals(false, verdict(now - 4 * hour, now - 48 * hour).fire)
    }

    @Test
    fun `the rule's start is the baseline - with no check-in, or only one from before it`() {
        assertEquals(CheckInVerdict(false, false, now - 2 * hour), verdict(null, now - 2 * hour))
        assertEquals(CheckInVerdict(false, true, now - 5 * hour), verdict(null, now - 5 * hour))
        assertEquals(CheckInVerdict(false, false, now - 2 * hour), verdict(now - 30 * hour, now - 2 * hour))
    }

    @Test
    fun `an open lapse goes off no more, until a check-in after its start ends it`() {
        val start = now - 9 * hour
        assertEquals(CheckInVerdict(false, false, start), verdict(start, now - 48 * hour, openSince = start))
        assertEquals(CheckInVerdict(false, false, start - hour), verdict(start - hour, now - 48 * hour, openSince = start))
        assertEquals(CheckInVerdict(true, false, now - hour), verdict(now - hour, now - 48 * hour, openSince = start))
        // No check-in this phone can read is no end.
        assertEquals(CheckInVerdict(false, false, now - 48 * hour), verdict(null, now - 48 * hour, openSince = start))
    }

    @Test
    fun `a member who checked in and went quiet again is reported again`() {
        assertEquals(
            CheckInVerdict(resolve = true, fire = true, since = now - 10 * hour),
            verdict(now - 10 * hour, now - 48 * hour, openSince = now - 30 * hour),
        )
    }

    @Test
    fun `a rule started again after the lapse began ends it, and counts from its new start`() {
        assertEquals(CheckInVerdict(true, false, now - hour), verdict(now - 50 * hour, now - hour, openSince = now - 50 * hour))
        assertEquals(CheckInVerdict(true, true, now - 5 * hour), verdict(null, now - 5 * hour, openSince = now - 50 * hour))
    }

    @Test
    fun `fired, turned off and back on, then quiet - the old lapse ends at once, and a new alert comes after the new start plus the limit`() {
        // It went off: no check-in since the rule started 48 hours ago.
        val firstStart = now - 48 * hour
        val fired = checkInVerdict(last = null, armedAt = firstStart, openSince = null, timeoutMs = limit, nowMs = now)
        assertEquals(CheckInVerdict(resolve = false, fire = true, since = firstStart), fired)
        // Turned back on an hour later, and judged an hour after that: the old lapse ends, nothing new.
        val rearmed = now + hour
        assertEquals(
            CheckInVerdict(resolve = true, fire = false, since = rearmed),
            checkInVerdict(last = null, armedAt = rearmed, openSince = fired.since, timeoutMs = limit, nowMs = rearmed + hour),
        )
        // No lapse open now: nothing up to the limit...
        assertEquals(
            CheckInVerdict(resolve = false, fire = false, since = rearmed),
            checkInVerdict(last = null, armedAt = rearmed, openSince = null, timeoutMs = limit, nowMs = rearmed + limit),
        )
        // ...and a new alert past it.
        assertEquals(
            CheckInVerdict(resolve = false, fire = true, since = rearmed),
            checkInVerdict(last = null, armedAt = rearmed, openSince = null, timeoutMs = limit, nowMs = rearmed + limit + 1),
        )
    }

    @Test
    fun `a lapse ends exactly when the silence counts from after its start`() {
        val start = now - 9 * hour
        val before = start - hour
        val after = start + hour
        // (last check-in, rule's start) to whether the lapse ends.
        val cases = listOf(
            Triple(after, before, true), // a check-in since
            Triple(start, before, false), // the check-in it fired on
            Triple(before, before, false),
            Triple(null, before, false),
            Triple(null, start, false), // the start it fired on
            Triple(null, after, true), // started again since
            Triple(before, after, true),
        )
        for ((last, armedAt, ends) in cases) {
            assertEquals("last=$last armedAt=$armedAt", ends, verdict(last, armedAt, openSince = start).resolve)
        }
    }

    @Test
    fun `a check-in or a start ahead of this phone's clock is no silence`() {
        assertEquals(false, verdict(now + hour, now - 48 * hour).fire)
        assertEquals(false, verdict(null, now + hour).fire)
    }
}
