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

import com.pingolin.track.service.LocationTrackingService.Companion.coarseStillFixVerdict
import com.pingolin.track.service.LocationTrackingService.Companion.livenessIntervalMsFor
import com.pingolin.track.service.LocationTrackingService.Companion.preciseFixConfirmsDeparture
import com.pingolin.track.service.LocationTrackingService.Companion.preciseFixRequestDue
import com.pingolin.track.service.LocationTrackingService.Companion.shouldHoldStillCadenceWhileMoving
import com.pingolin.track.service.LocationTrackingService.Companion.shouldPostNotificationUpdate
import com.pingolin.track.service.LocationTrackingService.Companion.watchdogRearmDue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location engine's battery-saving rules, pinned as the pure functions the
 * service calls.
 */
class LocationEngineRulesTest {

    private val minute = 60_000L

    // ---- a coarse fix while STILL (BALANCED poll) ----------------------------------

    @Test
    fun `a coarse fix near the last precise fix confirms presence`() {
        // A 250 m wifi/cell fix 120 m from where the phone was: it has not gone anywhere.
        assertEquals(CoarseStillVerdict.CONFIRMS_PRESENCE, coarseStillFixVerdict(250f, 120f))
        // Right at the 500 m reach is still presence.
        assertEquals(CoarseStillVerdict.CONFIRMS_PRESENCE, coarseStillFixVerdict(300f, 500f))
    }

    @Test
    fun `a coarse fix far from the last precise fix asks for a precise one`() {
        assertEquals(CoarseStillVerdict.SUGGESTS_DEPARTURE, coarseStillFixVerdict(200f, 900f))
    }

    @Test
    fun `with no precise fix to compare against, a precise one is taken`() {
        assertEquals(CoarseStillVerdict.SUGGESTS_DEPARTURE, coarseStillFixVerdict(200f, null))
    }

    @Test
    fun `a fix rougher than the battery-saver bound says nothing at all`() {
        // A kilometre-scale cell fix can neither confirm nor refute a position, so it is
        // not read as a departure. (The service still refreshes the precise fix once
        // it is a whole poll old — at most once per poll, see preciseFixRequestDue.)
        assertEquals(
            CoarseStillVerdict.TOO_ROUGH,
            coarseStillFixVerdict(LocationPolicy.MIN_ACCURACY_BALANCED + 1f, 10f),
        )
        assertEquals(CoarseStillVerdict.TOO_ROUGH, coarseStillFixVerdict(2_000f, 5_000f))
    }

    @Test
    fun `a departure is confirmed only beyond both fixes' error and 200 m`() {
        // Two honest fixes of the same spot never read as a trip.
        assertFalse(preciseFixConfirmsDeparture(distanceM = 150f, fixAccuracyM = 10f, previousAccuracyM = 10f))
        assertFalse(preciseFixConfirmsDeparture(distanceM = 250f, fixAccuracyM = 150f, previousAccuracyM = 120f))
        assertTrue(preciseFixConfirmsDeparture(distanceM = 250f, fixAccuracyM = 10f, previousAccuracyM = 15f))
        assertTrue(preciseFixConfirmsDeparture(distanceM = 900f, fixAccuracyM = 30f, previousAccuracyM = 30f))
    }

    @Test
    fun `precise one-shots are limited to one per poll`() {
        val poll = 5 * minute
        assertTrue(preciseFixRequestDue(nowElapsedMs = 1_000L, lastRequestElapsedMs = 0L, pollIntervalMs = poll))
        // Indoors with nothing to fix on, the 60 s tick must not retry a HIGH attempt
        // every minute.
        assertFalse(preciseFixRequestDue(nowElapsedMs = 10 * minute + 60_000L, lastRequestElapsedMs = 10 * minute, pollIntervalMs = poll))
        assertTrue(preciseFixRequestDue(nowElapsedMs = 15 * minute, lastRequestElapsedMs = 10 * minute, pollIntervalMs = poll))
    }

    // ---- the GPS-evidence exit from MOVING -----------------------------------------

    @Test
    fun `MOVING that has held one spot long enough polls at the STILL cadence`() {
        assertTrue(
            shouldHoldStillCadenceWhileMoving(
                movingForMs = 6 * minute,
                distanceFromAnchorM = 12f,
                elapsedSinceAnchorMs = 3 * minute,
                radiusM = 50f,
            )
        )
    }

    @Test
    fun `a MOVING that is too young is left alone however still the fixes are`() {
        // The motion detector gets its first five minutes: most trips have a pause in
        // them, and the 45 s accelerometer dwell is the right judge of those.
        assertFalse(
            shouldHoldStillCadenceWhileMoving(
                movingForMs = 4 * minute,
                distanceFromAnchorM = 0f,
                elapsedSinceAnchorMs = 4 * minute,
                radiusM = 50f,
            )
        )
    }

    @Test
    fun `fixes that left the radius, or held it too briefly, keep the moving cadence`() {
        assertFalse(
            shouldHoldStillCadenceWhileMoving(
                movingForMs = 30 * minute, distanceFromAnchorM = 60f,
                elapsedSinceAnchorMs = 10 * minute, radiusM = 50f,
            )
        )
        assertFalse(
            shouldHoldStillCadenceWhileMoving(
                movingForMs = 30 * minute, distanceFromAnchorM = 5f,
                elapsedSinceAnchorMs = 2 * minute, radiusM = 50f,
            )
        )
    }

    // ---- battery-saver liveness -------------------------------------------------------

    @Test
    fun `battery saver checks in on its batch interval`() {
        for (minutes in listOf(15L, 30L, 60L)) {
            assertEquals(
                minutes * minute,
                livenessIntervalMsFor(
                    userIntervalMs = 60_000L, batterySaver = true, saverIntervalMs = minutes * minute,
                ),
            )
        }
    }

    @Test
    fun `battery saver never checks in faster than the five-minute floor`() {
        assertEquals(
            5 * minute,
            livenessIntervalMsFor(userIntervalMs = 60_000L, batterySaver = true, saverIntervalMs = 60_000L),
        )
    }

    @Test
    fun `the real-time send interval plays no part in battery saver`() {
        // The saver cadence is the batch interval, not max(15 min, user interval).
        assertEquals(
            5 * minute,
            livenessIntervalMsFor(userIntervalMs = 15 * minute, batterySaver = true, saverIntervalMs = 5 * minute),
        )
    }

    @Test
    fun `real-time liveness is unchanged`() {
        assertEquals(5 * minute, livenessIntervalMsFor(userIntervalMs = 60_000L, batterySaver = false))
        assertEquals(15 * minute, livenessIntervalMsFor(userIntervalMs = 15 * minute, batterySaver = false))
    }

    // ---- the ongoing notification -------------------------------------------------------

    @Test
    fun `the notification is not re-posted with the screen off`() {
        assertFalse(shouldPostNotificationUpdate(isInteractive = false, text = "new", lastPostedText = "old"))
    }

    @Test
    fun `the notification is not re-posted when it would say the same thing`() {
        assertFalse(shouldPostNotificationUpdate(isInteractive = true, text = "same", lastPostedText = "same"))
    }

    @Test
    fun `a visible change is posted, including the first one`() {
        assertTrue(shouldPostNotificationUpdate(isInteractive = true, text = "new", lastPostedText = "old"))
        assertTrue(shouldPostNotificationUpdate(isInteractive = true, text = "first", lastPostedText = null))
    }

    // ---- the watchdog dead man's switch ------------------------------------------------

    @Test
    fun `the first successful send always re-arms the watchdog`() {
        assertTrue(watchdogRearmDue(nowElapsedMs = 1_000L, lastRearmElapsedMs = 0L))
    }

    @Test
    fun `re-arming is throttled to once per five minutes`() {
        val last = 10 * minute
        assertFalse(watchdogRearmDue(nowElapsedMs = last + 20_000L, lastRearmElapsedMs = last))
        assertFalse(watchdogRearmDue(nowElapsedMs = last + 5 * minute - 1, lastRearmElapsedMs = last))
        assertTrue(watchdogRearmDue(nowElapsedMs = last + 5 * minute, lastRearmElapsedMs = last))
    }
}
