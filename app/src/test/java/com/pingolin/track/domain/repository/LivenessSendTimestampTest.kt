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

package com.pingolin.track.domain.repository

import com.pingolin.track.domain.repository.LocationRepository.Companion.sendTimestampFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A liveness check-in carries the CURRENT time; a history row carries the fix's own.
 *
 * This is the detail that decides whether check-ins work at all. A check-in is
 * normally built from a CACHED fix — the device has not moved, so there may be no
 * newer one — and it asserts "I am here now". The timestamp resolved here is the one
 * that gets encrypted into the payload, so it is what every reader sees: the map's
 * "last updated", and the inactivity logic that decides whether a member has gone
 * quiet. Send the cached fix's original time and a check-in tells the watcher nothing
 * new, and the dot goes grey on a live phone.
 */
class LivenessSendTimestampTest {

    private val fixTakenAt = 1_757_000_000_000L
    private val now = fixTakenAt + 4 * 60_000L

    @Test
    fun aCheckInCarriesTheCurrentTimeNotTheFixTime() {
        assertEquals(now, sendTimestampFor(livenessOnly = true, fixTimeMs = fixTakenAt, nowMs = now))
    }

    /**
     * A history row is a claim about where the device WAS, and the send cycle can
     * process a Location minutes after it was taken, so it keeps the fix's own time.
     */
    @Test
    fun aHistorySendKeepsTheFixTime() {
        assertEquals(fixTakenAt, sendTimestampFor(livenessOnly = false, fixTimeMs = fixTakenAt, nowMs = now))
    }

    /**
     * The staler the cached fix, the more the two answers diverge — which is the
     * whole reason the check-in path cannot simply reuse the history path.
     */
    @Test
    fun anHourOldCachedFixStillChecksInAsNow() {
        val muchLater = fixTakenAt + 60 * 60_000L
        assertEquals(
            muchLater,
            sendTimestampFor(livenessOnly = true, fixTimeMs = fixTakenAt, nowMs = muchLater),
        )
    }

    /** A fix taken this instant gives the same answer either way. */
    @Test
    fun aFreshFixIsTheSameEitherWay() {
        assertEquals(
            sendTimestampFor(livenessOnly = false, fixTimeMs = now, nowMs = now),
            sendTimestampFor(livenessOnly = true, fixTimeMs = now, nowMs = now),
        )
    }
}
