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

import com.pingolin.track.worker.LocationHeartbeatWorker.Companion.heartbeatGate
import com.pingolin.track.worker.LocationHeartbeatWorker.Companion.heartbeatStaleThresholdMsFor
import com.pingolin.track.worker.LocationHeartbeatWorker.HeartbeatGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a heartbeat tick takes a fix and sends it.
 *
 * The case that matters most is PAUSE. "Pause tracking" stops the foreground
 * service; a worker that checked the pause flag only around restarting that
 * service would then take a fix and upload it to every circle anyway, about every
 * 15 minutes, for as long as the user stayed paused — circle members seeing a
 * fresh position from someone who had asked to share nothing.
 */
class HeartbeatGateTest {

    private val minute = 60_000L
    private val stale = 45 * minute

    private fun gate(
        fgsRunning: Boolean = false,
        lastSendAgeMs: Long = Long.MAX_VALUE,
        loggedIn: Boolean = true,
        paused: Boolean = false,
        wanted: Boolean = true,
    ) = heartbeatGate(fgsRunning, lastSendAgeMs, stale, loggedIn, paused, wanted)

    @Test
    fun `a paused user gets no heartbeat`() {
        assertEquals(HeartbeatGate.SKIP_PAUSED, gate(paused = true))
    }

    @Test
    fun `pause wins even over a stalled foreground service`() {
        // Pause stops the service, so this should not happen — but if the service is
        // somehow still up and silent, a paused user still shares nothing.
        assertEquals(HeartbeatGate.SKIP_PAUSED, gate(fgsRunning = true, lastSendAgeMs = stale + minute, paused = true))
    }

    @Test
    fun `the paired unpaused case runs, so the pause test cannot pass for another reason`() {
        assertEquals(HeartbeatGate.RUN, gate(paused = false))
    }

    @Test
    fun `a healthy foreground service needs no heartbeat`() {
        assertEquals(HeartbeatGate.SKIP_SERVICE_HEALTHY, gate(fgsRunning = true, lastSendAgeMs = 10 * minute))
    }

    @Test
    fun `a stalled foreground service is covered while sharing is wanted`() {
        assertEquals(HeartbeatGate.RUN_FOR_STALLED_SERVICE, gate(fgsRunning = true, lastSendAgeMs = stale))
    }

    @Test
    fun `an account an admin hasn't approved gets no heartbeat, a stalled service's neither`() {
        // Ticks scheduled by an earlier version, which started the service for an
        // applicant it couldn't know about, are refused too: the one rule says no,
        // and nothing is taken, started or sent.
        assertEquals(HeartbeatGate.SKIP_NOT_WANTED, gate(wanted = false))
        assertEquals(HeartbeatGate.SKIP_NOT_WANTED, gate(fgsRunning = true, lastSendAgeMs = stale, wanted = false))
    }

    @Test
    fun `the tick asks the one rule, and an unwanted one ends before any start or fix`() {
        val worker = java.io.File("src/main/java/com/pingolin/track/worker/LocationHeartbeatWorker.kt").readText()
        val work = worker.substring(worker.indexOf("override suspend fun doWork()"))
        assertTrue(work.contains("wanted = sessionManager.shouldBeTracking(),"))
        val skip = work.indexOf("HeartbeatGate.SKIP_NOT_WANTED -> {")
        assertTrue(skip > 0)
        assertTrue(work.substring(skip, work.indexOf("}", skip)).contains("return Result.success()"))
        assertTrue("before the start", skip < work.indexOf("startForegroundService("))
        assertTrue("and before the fix", skip < work.indexOf("locationProvider.currentLocation("))
        // No path of the tick writes a setting, setTrackingEnabled
        // included.
        assertFalse(work.contains("setTrackingEnabled("))
    }

    @Test
    fun `signed out, nothing runs`() {
        assertEquals(HeartbeatGate.SKIP_NOT_LOGGED_IN, gate(loggedIn = false))
        assertEquals(HeartbeatGate.SKIP_NOT_LOGGED_IN, gate(fgsRunning = true, lastSendAgeMs = stale, loggedIn = false))
    }

    @Test
    fun `tracking switched off, nothing runs`() {
        assertEquals(HeartbeatGate.SKIP_NOT_WANTED, gate(wanted = false))
    }

    // ---- the staleness threshold ------------------------------------------------------

    @Test
    fun `real-time mode keeps the 45-minute threshold`() {
        assertEquals(45 * minute, heartbeatStaleThresholdMsFor(batterySaver = false, saverIntervalMinutes = 60))
    }

    @Test
    fun `battery saver widens the threshold to two batch intervals plus slack`() {
        // A healthy saver service sends every batch interval; at the 30- and 60-minute
        // chips a constant 45 minutes would call it stale between its own sends.
        assertEquals(45 * minute, heartbeatStaleThresholdMsFor(batterySaver = true, saverIntervalMinutes = 5))
        assertEquals(45 * minute, heartbeatStaleThresholdMsFor(batterySaver = true, saverIntervalMinutes = 15))
        assertEquals(75 * minute, heartbeatStaleThresholdMsFor(batterySaver = true, saverIntervalMinutes = 30))
        assertEquals(135 * minute, heartbeatStaleThresholdMsFor(batterySaver = true, saverIntervalMinutes = 60))
    }
}
