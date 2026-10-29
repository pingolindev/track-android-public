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

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sensor motion detector, pinned.
 *
 * [MotionDetectionManager] lives in `src/main` and BOTH distribution flavours
 * ship it, so these live in `src/test` and run in both unit-test variants: the
 * Play build's variant runs the tests for the detector the Play build actually
 * uses. What they cover is the half that has no hardware in it: the MOVING ->
 * STILL decision, which is the only part that had to be invented (significant
 * motion gives us STILL -> MOVING for free, and it is a hardware trigger with
 * nothing to decide).
 *
 * Getting the conclusion wrong is expensive in a way nothing else here is:
 *   - too eager, and a phone in a steady pocket on a train is called STILL, GPS
 *     backs off to POLL_STILL_MS (5 min), and every circle member watches a
 *     marker that stopped moving;
 *   - too reluctant, and the accelerometer plus a 20 s GPS poll run for hours on
 *     a device sitting on a desk, which is exactly the battery cost the STILL
 *     profile exists to avoid.
 */
class MotionDetectionTest {

    private val window get() = StillnessWindow()

    /** Gravity, as a phone flat on a table reads it. */
    private val g = 9.81

    /**
     * Feed [count] samples [periodMs] apart, jittering the magnitude by
     * ±[amplitude] in an alternating pattern so the variance is non-zero but
     * controlled. Returns the last answer the window gave.
     */
    private fun feed(
        w: StillnessWindow,
        count: Int,
        amplitude: Double,
        startMs: Long = 0L,
        periodMs: Long = 200L,
    ): Boolean {
        var answer = false
        for (i in 0 until count) {
            val sign = if (i % 2 == 0) 1 else -1
            answer = w.onSample(g + sign * amplitude, startMs + i * periodMs)
        }
        return answer
    }

    /** How many samples cover a span, at the real 200 ms sampling period. */
    private fun samplesFor(spanMs: Long) = (spanMs / 200L).toInt() + 1

    /**
     * The dwell clock cannot start until the window holds MIN_SAMPLES — before
     * that its variance means nothing — so a device that has just gone quiet
     * needs the warm-up PLUS the dwell before it can be called STILL.
     */
    private val warmupMs = (MotionDetectionManager.MIN_SAMPLES - 1) * 200L

    @Test
    fun `a quiet device is not called STILL before the dwell elapses`() {
        val w = window
        // Half the dwell of near-perfect quiet: the correct answer
        // is still false.
        val half = MotionDetectionManager.STILL_DWELL_MS / 2
        assertFalse(feed(w, samplesFor(half), amplitude = 0.01))
    }

    @Test
    fun `a quiet device is called STILL once the dwell elapses`() {
        val needed = warmupMs + MotionDetectionManager.STILL_DWELL_MS
        // One sample short of the dwell is still not an answer — this pins the
        // boundary rather than just "eventually true", so a dwell quietly
        // shortened to nothing would fail here.
        assertFalse(
            "the dwell must not be satisfied early",
            feed(window, samplesFor(needed) - 1, amplitude = 0.01),
        )
        assertTrue(feed(window, samplesFor(needed) + 1, amplitude = 0.01))
    }

    @Test
    fun `a moving device is never called STILL however long it moves`() {
        val w = window
        // Walking with the phone in a pocket swings the magnitude by whole m/s^2.
        assertFalse(
            "a walking device must not reach STILL",
            feed(w, samplesFor(MotionDetectionManager.STILL_DWELL_MS * 3), amplitude = 2.0),
        )
    }

    /**
     * The dwell must be CONTINUOUS. Quiet, then one loud sample, then quiet again
     * must NOT add up: that is a phone put down at a red light, picked up, and
     * put down again, and calling it STILL would strand GPS at 5 min for the
     * rest of the drive.
     */
    @Test
    fun `an interruption resets the dwell clock`() {
        val w = window
        val dwell = MotionDetectionManager.STILL_DWELL_MS
        val almost = samplesFor(dwell) - 2

        var t = 0L
        feed(w, almost, amplitude = 0.01, startMs = t)
        t += almost * 200L

        // One burst loud enough to blow the variance budget, spread over the
        // whole window so it cannot be averaged away.
        feed(w, MotionDetectionManager.MIN_SAMPLES + 4, amplitude = 3.0, startMs = t)
        t += (MotionDetectionManager.MIN_SAMPLES + 4) * 200L

        // The same "almost" run again must still not be enough.
        assertFalse(
            "the dwell must restart after motion, not resume",
            feed(w, almost, amplitude = 0.01, startMs = t),
        )

        // But carrying on past a full dwell from the interruption does succeed.
        t += almost * 200L
        assertTrue(feed(w, samplesFor(dwell) + 1, amplitude = 0.01, startMs = t))
    }

    @Test
    fun `too few samples is not an answer`() {
        val w = window
        // Below MIN_SAMPLES the variance of the window means nothing, so even
        // perfectly identical readings taken over a long span must not qualify.
        var answer = false
        for (i in 0 until MotionDetectionManager.MIN_SAMPLES - 1) {
            answer = w.onSample(g, i * MotionDetectionManager.STILL_DWELL_MS)
        }
        assertFalse(answer)
    }

    @Test
    fun `reset forgets the dwell`() {
        val w = window
        val dwell = MotionDetectionManager.STILL_DWELL_MS
        feed(w, samplesFor(dwell), amplitude = 0.01)
        w.reset()
        assertFalse(
            "after reset the window must start counting again from zero",
            feed(w, samplesFor(dwell / 2), amplitude = 0.01, startMs = dwell + 10_000L),
        )
    }

    /**
     * A gap in the sample TIMESTAMPS longer than the window means samples were lost,
     * not merely delivered late, and nothing observed during it may count towards
     * the dwell. Without the reset, a quiet start, a long gap and then eight quiet
     * samples would satisfy a dwell whose start predates the gap — while the device
     * could have been anywhere during it.
     */
    @Test
    fun `a gap in the samples restarts the dwell`() {
        val w = window
        val dwell = MotionDetectionManager.STILL_DWELL_MS
        // Warm up and go quiet...
        feed(w, MotionDetectionManager.MIN_SAMPLES + 2, amplitude = 0.01)
        // ...then nothing for longer than the dwell, then a handful of quiet samples.
        val afterGap = dwell + MotionDetectionManager.WINDOW_MS * 2
        assertFalse(
            "samples after a gap must not inherit a dwell started before it",
            feed(w, MotionDetectionManager.MIN_SAMPLES + 2, amplitude = 0.01, startMs = afterGap),
        )
        // Carrying on quietly for a full dwell after the gap still succeeds.
        val resumeAt = afterGap + (MotionDetectionManager.MIN_SAMPLES + 2) * 200L
        assertTrue(feed(w, samplesFor(dwell) + 1, amplitude = 0.01, startMs = resumeAt))
    }

    /**
     * Batching delays DELIVERY, not the samples: timestamps arrive continuous but
     * late, and the dwell must be measured on them exactly as if they had streamed.
     */
    @Test
    fun `batched delivery measures the dwell by sample time`() {
        val w = window
        val needed = warmupMs + MotionDetectionManager.STILL_DWELL_MS
        // The same continuous run, "delivered" in one late burst: same answer.
        assertTrue(feed(w, samplesFor(needed) + 1, amplitude = 0.01, startMs = 1_000_000L))
    }

    @Test
    fun `magnitude is the euclidean norm, so orientation does not matter`() {
        // A phone at rest reads ~9.81 total however it is lying: flat on its
        // back puts it all on Z, stood on edge puts it all on Y. If the detector
        // used a single axis, standing a still phone up would look like motion.
        val flat = MotionDetectionManager.magnitudeOf(0f, 0f, 9.81f)
        val onEdge = MotionDetectionManager.magnitudeOf(0f, 9.81f, 0f)
        val tilted = MotionDetectionManager.magnitudeOf(5.66f, 5.66f, 5.66f)
        assertEquals(9.81, flat, 0.001)
        assertEquals(9.81, onEdge, 0.001)
        assertEquals(9.80, tilted, 0.01)
    }

    /**
     * No sensors, no opinion — never a crash, and never a guess.
     *
     * `LocationTrackingService` treats UNKNOWN as "poll at POLL_UNKNOWN_MS and
     * let maybeInferStill decide from GPS evidence", which is exactly right for
     * a device that cannot measure motion. Guessing STILL here would pin such a
     * device at a 5-minute GPS poll forever.
     */
    @Test
    fun `a device with no motion sensors stays UNKNOWN`() {
        val context = mockk<android.content.Context>(relaxed = true)
        // getSystemService returns a relaxed mock by default; make it null so
        // both getDefaultSensor lookups come back null.
        every { context.getSystemService(android.content.Context.SENSOR_SERVICE) } returns null
        val sessionManager = mockk<SessionManager>(relaxed = true)
        every { sessionManager.getLastMotionState() } returns MotionState.MOVING

        val manager = MotionDetectionManager(context, sessionManager)
        manager.start()

        assertEquals(
            "with no sensors, start() must not restore or invent a state",
            MotionState.UNKNOWN,
            manager.motionState.value,
        )
        // stop() on something that never started must be a no-op, not a throw —
        // LocationTrackingService calls it from onDestroy unconditionally.
        manager.stop()
    }
}
