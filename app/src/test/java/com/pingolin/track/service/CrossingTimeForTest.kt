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

package com.pingolin.track.service

import com.pingolin.track.service.LocationTrackingService.Companion.crossingTimeFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which instant a self-detected crossing is reported as happening at.
 *
 * `checkOwnLocationAgainstBoundaries` evaluates a Location that the send cycle
 * may only get to minutes after the fix was taken: for example
 * `fixAge=555011ms`, i.e. **9¼ minutes**. Reported with
 * `reportCrossingFromMonitor`'s default `System.currentTimeMillis()`, the
 * sealed "when it happened" would be the moment the report was assembled, up
 * to nine minutes after the crossing it describes.
 *
 * The whole reason a crossing time is sealed and sent is so a subscriber sees
 * when the crossing HAPPENED rather than when it was processed, so it is never
 * stamped at processing time.
 */
class CrossingTimeForTest {

    private val now = 1785398745123L // 2026-07-30T08:05:45.123Z

    /** The case this rule exists for: an old fix keeps its own
     * time.
     */
    @Test
    fun `a stale fix reports its own time, not now`() {
        val fix = now - 555_011L // about 9 minutes
        assertEquals(fix, crossingTimeFor(fix, now))
    }

    @Test
    fun `a fresh fix reports its own time too`() {
        val fix = now - 1_200L
        assertEquals(fix, crossingTimeFor(fix, now))
    }

    /**
     * Location.time is 0 on some sources; there is nothing better than now.
     */
    @Test
    fun `a missing fix time falls back to now`() {
        assertEquals(now, crossingTimeFor(0L, now))
        assertEquals(now, crossingTimeFor(-1L, now))
    }

    /**
     * A clock ahead of ours must not produce a crossing in the FUTURE — that
     * would render as a time the user has not reached yet. Clamp to now.
     */
    @Test
    fun `a future fix time is clamped to now`() {
        assertEquals(now, crossingTimeFor(now + 60_000L, now))
    }

    /** Exactly now is not in the future. */
    @Test
    fun `a fix taken exactly now is kept`() {
        assertEquals(now, crossingTimeFor(now, now))
    }

    /**
     * Age is never capped. A crossing found in a genuinely old fix is still
     * reported at its real time — the subscriber-side staleness window decides
     * whether to surface it, not this.
     */
    @Test
    fun `an hours-old fix is not clamped`() {
        val fix = now - 3 * 60 * 60 * 1000L
        assertEquals(fix, crossingTimeFor(fix, now))
    }
}
