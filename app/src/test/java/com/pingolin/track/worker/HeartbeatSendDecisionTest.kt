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

package com.pingolin.track.worker

import com.pingolin.track.service.LocationSendDecision
import com.pingolin.track.worker.LocationHeartbeatWorker.Companion.heartbeatDecision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The heartbeat worker applies the same moved / not-moved rule as the foreground
 * service, with its own threshold.
 *
 * It matters because this worker is what runs when the foreground service is dead
 * or has gone silent: an unconditional history row every 15 minutes would be four
 * an hour, per circle, from a phone left on a table. The service is the bigger
 * source, but a device whose service the OS killed sends nothing BUT these.
 */
class HeartbeatSendDecisionTest {

    private val threshold = 250f

    @Test
    fun movedFarEnough_writesHistory() {
        assertEquals(LocationSendDecision.HISTORY, heartbeatDecision(400f, threshold))
    }

    @Test
    fun exactlyTheThreshold_writesHistory() {
        assertEquals(LocationSendDecision.HISTORY, heartbeatDecision(threshold, threshold))
    }

    @Test
    fun notMoved_checksIn() {
        assertEquals(LocationSendDecision.LIVENESS, heartbeatDecision(0f, threshold))
    }

    /**
     * The threshold is a constant here rather than the user's own distance setting,
     * and this is why: the heartbeat's fix is the app's only genuinely coarse
     * location request, routinely hundreds of metres on the libre flavour. Judged
     * against a 50 m user setting, that provider's own noise would become a stream
     * of history rows from a stationary phone.
     */
    @Test
    fun coarseProviderNoise_isNotMovement() {
        for (noise in listOf(40f, 120f, 249f)) {
            assertEquals(
                "a ${noise}m wander on a coarse fix must not write history",
                LocationSendDecision.LIVENESS,
                heartbeatDecision(noise, threshold),
            )
        }
    }

    /**
     * No baseline yet: this install has never recorded a history send. That first
     * send is what anchors the baseline, so it has to be a real one.
     */
    @Test
    fun noBaseline_writesHistory() {
        assertEquals(LocationSendDecision.HISTORY, heartbeatDecision(null, threshold))
    }

    /**
     * There is deliberately no "send nothing" outcome. This worker only runs when
     * the device would otherwise be silent, so saying nothing is the failure it
     * exists to prevent.
     */
    @Test
    fun thereIsNoSilentOutcome() {
        for (moved in listOf(null, 0f, 1f, threshold, 10_000f)) {
            val decision = heartbeatDecision(moved, threshold)
            assertEquals(
                "moved=$moved must still produce a send",
                false,
                decision == LocationSendDecision.NONE,
            )
        }
    }
}
