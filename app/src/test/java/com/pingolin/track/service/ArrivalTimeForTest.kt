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

import com.pingolin.track.service.LocationTrackingService.Companion.arrivalTimeFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which instant an auto-arrival is reported as happening at.
 *
 * WHY IT IS NOT SIMPLY `now()`
 *   `checkOwnLocationAgainstDestinations` un-flags a destination whose report
 *   failed, so a later tick tries again:
 *
 *       // Un-flag so a later tick can retry.
 *       autoArrivedDestinations.remove(dest.id)
 *
 *   The later tick is the moment the network came back. Stamping the arrival
 *   there means an arrival detected offline announces itself to the circle as if
 *   the user had just walked in — which is exactly the failure a sealed
 *   occurred-at exists to prevent, reproduced one layer in. It is also not a
 *   "few seconds off" problem: the gap is however long the device was offline.
 *
 *   The second correction is the same one [LocationTrackingService.crossingTimeFor]
 *   makes for place alerts. The send cycle evaluates a Location that can be
 *   minutes stale (for example `fixAge=555011ms`), so even a first attempt
 *   reported at now() is late by the age of the fix.
 */
class ArrivalTimeForTest {

    private val now = 1785398745123L // 2026-07-30T08:05:45.123Z

    /** THE case the retry memory exists for. */
    @Test
    fun `a retry after an offline stretch reports the original detection, not the reconnect`() {
        val detected = now - 4L * 60 * 60 * 1000 // four hours offline
        // The fix in hand on the retry tick is fresh — it is the reconnect
        // moment — and must not be what gets sealed.
        assertEquals(detected, arrivalTimeFor(remembered = detected, fixTimeMs = now - 500L, now = now))
    }

    /** First attempt: nothing remembered, so the fix's own time is used. */
    @Test
    fun `a first attempt reports the time of the fix it was detected on`() {
        val fix = now - 555_011L // a fix about 9 minutes old
        assertEquals(fix, arrivalTimeFor(remembered = null, fixTimeMs = fix, now = now))
    }

    /**
     * Location.time is 0 on some sources, and a clock ahead of ours would put the
     * arrival in the future — a time the user has not reached yet. Both fall back
     * to now, which is [LocationTrackingService.crossingTimeFor]'s rule; this test
     * pins that arrivals share it rather than growing a second one.
     */
    @Test
    fun `an unusable fix time falls back to now`() {
        assertEquals(now, arrivalTimeFor(remembered = null, fixTimeMs = 0L, now = now))
        assertEquals(now, arrivalTimeFor(remembered = null, fixTimeMs = -1L, now = now))
        assertEquals(now, arrivalTimeFor(remembered = null, fixTimeMs = now + 60_000L, now = now))
    }

    /**
     * A remembered detection is authoritative even when it is old enough that the
     * clamping rules would have rejected a fix of the same age. It was not a fix
     * — it was a decision this device already made.
     */
    @Test
    fun `a remembered detection is not re-clamped against the current fix`() {
        val detected = now - 30L * 24 * 60 * 60 * 1000 // a month
        assertEquals(detected, arrivalTimeFor(remembered = detected, fixTimeMs = 0L, now = now))
    }
}
