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
import com.pingolin.track.util.MonitoringManager.Companion.onceVerdict
import com.pingolin.track.util.MonitoringManager.Companion.scheduledVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two kinds' verdicts on one member (MonitoringManager): a one-time rule is the old check-in
 * verdict, ended for good by the member's first check-in after its start; a scheduled rule raises
 * its latest occurrence when it was started before the deadline and no check-in came since the
 * window opened, once per occurrence, and an open lapse ends at a check-in after its start or when
 * a newer occurrence takes over.
 */
class CheckInKindVerdictsTest {

    private val hour = 3_600_000L
    private val now = 1_000L * hour

    // ── scheduled ────────────────────────────────────────────────────────────

    /** Today's occurrence: due an hour ago, its window from ten hours before that. */
    private val today = CheckInSchedule.Occurrence(due = now - hour, windowStart = now - 11 * hour)

    @Test
    fun `missed - started before the deadline, no check-in since the window opened - goes off at the deadline`() {
        assertEquals(
            CheckInVerdict(resolve = false, fire = true, since = today.due),
            scheduledVerdict(last = today.windowStart - 1, armedAt = now - 48 * hour, openSince = null, latest = today),
        )
        assertTrue("with no check-in at all", scheduledVerdict(null, now - 48 * hour, null, today).fire)
    }

    @Test
    fun `a check-in in the window keeps it quiet, from the window's first moment`() {
        assertFalse(scheduledVerdict(today.windowStart, now - 48 * hour, null, today).fire)
        assertFalse("even after the deadline", scheduledVerdict(now - 1, now - 48 * hour, null, today).fire)
    }

    @Test
    fun `a rule started at or after the deadline isn't raised for it`() {
        assertFalse(scheduledVerdict(null, armedAt = today.due, openSince = null, latest = today).fire)
        assertFalse(scheduledVerdict(null, armedAt = today.due + 1, openSince = null, latest = today).fire)
        assertTrue("a moment before it is", scheduledVerdict(null, armedAt = today.due - 1, openSince = null, latest = today).fire)
    }

    @Test
    fun `once per occurrence - its open lapse holds until something ends it`() {
        assertEquals(
            CheckInVerdict(resolve = false, fire = false, since = today.due),
            scheduledVerdict(last = null, armedAt = now - 48 * hour, openSince = today.due, latest = today),
        )
    }

    @Test
    fun `a check-in after the lapse's start ends it, and raises nothing`() {
        assertEquals(
            CheckInVerdict(resolve = true, fire = false, since = today.due),
            scheduledVerdict(last = today.due + 1, armedAt = now - 48 * hour, openSince = today.due, latest = today),
        )
        assertFalse("one at its start isn't after it", scheduledVerdict(today.due, now - 48 * hour, today.due, today).resolve)
    }

    @Test
    fun `a newer occurrence takes over - yesterday's lapse ends, and today's goes off when missed too`() {
        val yesterday = today.due - 24 * hour
        assertEquals(
            CheckInVerdict(resolve = true, fire = true, since = today.due),
            scheduledVerdict(last = yesterday - hour, armedAt = now - 48 * hour, openSince = yesterday, latest = today),
        )
        // Checked in for today, after yesterday's deadline: both ends, no new one.
        assertEquals(
            CheckInVerdict(resolve = true, fire = false, since = today.due),
            scheduledVerdict(last = today.windowStart + hour, armedAt = now - 48 * hour, openSince = yesterday, latest = today),
        )
    }

    @Test
    fun `no occurrence - nothing`() {
        assertEquals(CheckInVerdict(resolve = false, fire = false, since = now - 48 * hour), scheduledVerdict(null, now - 48 * hour, null, null))
    }

    // ── one-time ─────────────────────────────────────────────────────────────

    @Test
    fun `a one-time rule goes off its hours after its start, from a check-in before the start or none`() {
        val armed = now - 5 * hour
        assertEquals(CheckInVerdict(resolve = false, fire = true, since = armed), onceVerdict(null, armed, null, 4 * hour, now))
        assertTrue(onceVerdict(armed - 30 * hour, armed, null, 4 * hour, now).fire)
        assertFalse("within its hours", onceVerdict(null, now - 3 * hour, null, 4 * hour, now).fire)
    }

    @Test
    fun `it ends at the first check-in after its start - never again, however long the silence after`() {
        val armed = now - 48 * hour
        assertEquals(
            "the rolling rule would say: ten hours past the last check-in",
            CheckInVerdict(resolve = false, fire = false, since = now - 10 * hour),
            onceVerdict(last = now - 10 * hour, armedAt = armed, openSince = null, timeoutMs = 4 * hour, nowMs = now),
        )
        assertTrue("one at the start itself isn't after it", onceVerdict(armed, armed, null, 4 * hour, now).fire)
    }

    @Test
    fun `its open lapse ends at that check-in, and a re-start ends it too`() {
        val armed = now - 48 * hour
        assertEquals(
            CheckInVerdict(resolve = true, fire = false, since = now - 10 * hour),
            onceVerdict(last = now - 10 * hour, armedAt = armed, openSince = armed, timeoutMs = 4 * hour, nowMs = now),
        )
        // Turned off and on an hour ago: a new start, nothing yet.
        assertEquals(
            CheckInVerdict(resolve = true, fire = false, since = now - hour),
            onceVerdict(last = null, armedAt = now - hour, openSince = armed, timeoutMs = 4 * hour, nowMs = now),
        )
    }
}
