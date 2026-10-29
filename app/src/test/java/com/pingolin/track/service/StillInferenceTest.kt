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

import com.pingolin.track.service.LocationTrackingService.Companion.shouldInferStill
import com.pingolin.track.service.LocationTrackingService.Companion.stillInferRadiusFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that seeds MotionState.STILL when activity recognition never will.
 *
 * Background: MotionDetectionManager registers only
 * requestActivityTransitionUpdates, and the Transition API fires on
 * TRANSITIONS. A device already still when the service registers gets no STILL
 * *ENTER*, so without this rule motionState stays UNKNOWN forever and the poll
 * sits at POLL_UNKNOWN_MS (30s) rather than POLL_STILL_MS (5min): every send
 * logs motion=UNKNOWN, although transition registration succeeded and the
 * permission was granted.
 *
 * The property that makes this safe: it is EVIDENCE-based. A moving device
 * cannot satisfy it, because its fixes leave the radius and reset the anchor.
 * That is the difference between this and optimistically assuming STILL at
 * startup, which would under-poll someone who is driving — the one failure mode
 * that actually matters for a location-sharing product.
 */
class StillInferenceTest {

    private val radius = 50f
    private val span = 3 * 60_000L

    @Test
    fun heldOneSpotLongEnough_infersStill() {
        assertTrue(shouldInferStill(distanceFromAnchorM = 12f, elapsedSinceAnchorMs = span, radiusM = radius, minSpanMs = span))
    }

    @Test
    fun heldLongerThanRequired_stillInfers() {
        assertTrue(shouldInferStill(0f, span * 4, radius, span))
    }

    /** Not convinced yet: right place, not enough time. */
    @Test
    fun sameSpotButTooBriefly_doesNotInfer() {
        assertFalse(shouldInferStill(5f, span - 1, radius, span))
    }

    /**
     * The load-bearing case. Someone driving produces fixes far apart, so the
     * distance test fails no matter how much time passes — which is exactly why
     * this cannot mistake a moving device for a stationary one.
     */
    @Test
    fun movingDevice_neverInfersStillHoweverLongItRuns() {
        assertFalse(shouldInferStill(radius + 1f, span, radius, span))
        assertFalse(shouldInferStill(400f, span * 100, radius, span))
    }

    @Test
    fun exactlyOnBothBoundaries_infers() {
        // Inclusive on both: <= radius and >= span. A fix sitting precisely on the
        // GPS noise radius after precisely the required span is still evidence.
        assertTrue(shouldInferStill(radius, span, radius, span))
    }

    /**
     * GPS jitter while genuinely stationary must not defeat the rule — that is
     * what the radius is for. 50m is wider than typical urban noise and far
     * narrower than any real trip.
     */
    @Test
    fun gpsJitterWhileStationary_doesNotDefeatIt() {
        for (jitter in listOf(3f, 11f, 27f, 49f)) {
            assertTrue("jitter ${jitter}m should still read as stationary", shouldInferStill(jitter, span, radius, span))
        }
    }

    // ===== the anchor radius must scale with how rough the fixes are =====

    /**
     * Battery-saver ACCEPTS fixes up to 500m
     * (LocationPolicy.MIN_ACCURACY_BALANCED), and a 500m fix can never land
     * within 50m of its anchor — so with a constant 50m radius the anchor would
     * reset on every callback, STILL would never be inferred, and the mode that
     * exists to SAVE battery would poll at the UNKNOWN cadence forever. Silently:
     * a rule that never fires logs nothing.
     */
    @Test
    fun coarseFixesGetAnAnchorRadiusTheyCanActuallySatisfy() {
        assertEquals(500f, stillInferRadiusFor(500f), 0f)
        // A stationary device on 500m fixes reads as
        // stationary.
        assertTrue(shouldInferStill(420f, span, stillInferRadiusFor(500f), span))
        // A constant 50m radius would reject the identical evidence.
        assertFalse(shouldInferStill(420f, span, radius, span))
    }

    @Test
    fun preciseFixesKeepTheFixedRadius() {
        // Only ever widens — a precise fix keeps the base radius.
        assertEquals(radius, stillInferRadiusFor(8f), 0f)
        assertEquals(radius, stillInferRadiusFor(0f), 0f)
        assertEquals(radius, stillInferRadiusFor(radius), 0f)
    }

    @Test
    fun unknownAccuracyKeepsTheFixedRadius() {
        // Unknown is not licence to relax the rule.
        assertEquals(radius, stillInferRadiusFor(null), 0f)
    }

    /**
     * Widening must not turn a MOVING device into a still one. It cannot: the
     * anchor test scales with the fix's own error, so a device travelling further
     * than its own accuracy still resets the anchor.
     */
    @Test
    fun aMovingDeviceStillDefeatsTheWidenedRadius() {
        val r = stillInferRadiusFor(500f)
        assertFalse(shouldInferStill(r + 1f, span, r, span))
        assertFalse("driving produces fixes km apart", shouldInferStill(4_000f, span * 10, r, span))
    }
}
