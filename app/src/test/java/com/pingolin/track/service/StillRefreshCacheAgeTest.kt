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

import com.pingolin.track.service.LocationTrackingService.Companion.stillRefreshCacheTooOld
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty guard on the still-refresh.
 *
 * While STILL the poll stretches to POLL_STILL_MS (5min) and sends are filtered
 * from inbound fixes, so peers' "last updated" would stretch with it. The
 * still-refresh re-sends the CACHED fix on the user's own interval to keep that
 * timestamp fresh while GPS stays idle.
 *
 * That is only defensible while the cache genuinely reflects where the user is.
 * If GPS has stopped delivering, re-sending an ancient position as current would
 * be a lie of exactly the kind a location-sharing app must not tell — a peer
 * would see a fresh timestamp on a stale place. Past the cutoff we let the
 * timestamp go stale instead, which lets the stale-send warning fire.
 */
class StillRefreshCacheAgeTest {

    private val fiveMin = 5 * 60_000L
    private val tenMinFloor = 10 * 60_000L

    @Test
    fun freshCache_isUsable() {
        assertFalse(stillRefreshCacheTooOld(cacheAgeMs = 30_000L, pollIntervalMs = fiveMin))
    }

    @Test
    fun cacheAsOldAsOnePollCycle_isUsable() {
        // A 5-minute poll legitimately produces fixes up to ~5 minutes apart.
        assertFalse(stillRefreshCacheTooOld(fiveMin, fiveMin))
    }

    @Test
    fun cacheOlderThanTheFloor_isRejected() {
        assertTrue(stillRefreshCacheTooOld(tenMinFloor + 1, fiveMin))
    }

    /**
     * The floor matters most for SHORT polls: 2 x 20s while moving would reject a
     * 41-second-old fix, which is far too twitchy. The floor keeps the rule from
     * being derived into uselessness.
     */
    @Test
    fun shortPollsAreGovernedByTheFloorNotTheFactor() {
        val movingPoll = 20_000L
        assertFalse(stillRefreshCacheTooOld(60_000L, movingPoll))
        assertFalse(stillRefreshCacheTooOld(tenMinFloor, movingPoll))
        assertTrue(stillRefreshCacheTooOld(tenMinFloor + 1, movingPoll))
    }

    /** A very long poll widens the allowance rather than tripping the floor. */
    @Test
    fun longPollsAreGovernedByTheFactor() {
        val longPoll = 30 * 60_000L
        assertFalse(stillRefreshCacheTooOld(59 * 60_000L, longPoll))
        assertTrue(stillRefreshCacheTooOld(61 * 60_000L, longPoll))
    }

    @Test
    fun exactlyOnTheBoundary_isStillUsable() {
        assertFalse(stillRefreshCacheTooOld(tenMinFloor, fiveMin))
    }
}
