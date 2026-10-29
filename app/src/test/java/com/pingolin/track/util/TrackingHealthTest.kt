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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether tracking is actually working.
 *
 * "Is the service object alive?" is not enough: tracking can stop cleanly and
 * stay dead for hours while a watchdog that asks only that keeps "recovering"
 * it.
 */
class TrackingHealthTest {

    private val minute = 60_000L
    private val poll = 5 * minute

    // ---- the state that must never be reported as a fault ----

    @Test
    fun `tracking off by choice is healthy whatever else is true`() {
        // Paused/disabled/signed out with a service that is down AND a fix that
        // is days old — still healthy, because the user asked for this. Getting
        // it wrong trains people to ignore the one notification that matters.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = false,
            serviceRunning = false,
            lastFixAgeMs = 3 * 24 * 60 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 99,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    // ---- signed out ----

    @Test
    fun `signed out with tracking switched on is SignedOut, never merely NotRunning`() {
        // The distinction matters because nothing automatic will fix this:
        // BootReceiver and the watchdog both gate on being signed in, so a
        // "not running, we'll retry" verdict would be a false promise.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = false,   // false BECAUSE signed out
            serviceRunning = false,
            lastFixAgeMs = 15 * 60 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
            signedIn = false,
            trackingWanted = true,
        )
        assertEquals(TrackingHealth.Verdict.SignedOut, verdict)
        assertTrue(TrackingHealth.warrantsUserAttention(verdict))
    }

    @Test
    fun `signed out outranks a pending restart count`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = false,
            serviceRunning = false,
            lastFixAgeMs = TrackingHealth.NO_FIX,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 5,
            signedIn = false,
            trackingWanted = true,
        )
        assertEquals(TrackingHealth.Verdict.SignedOut, verdict)
    }

    @Test
    fun `signed out with tracking switched OFF stays healthy`() {
        // Someone who signed out and was not sharing anyway must not be nagged.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = false,
            serviceRunning = false,
            lastFixAgeMs = TrackingHealth.NO_FIX,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
            signedIn = false,
            trackingWanted = false,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    @Test
    fun `paused while signed in is healthy, not SignedOut`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = false,
            serviceRunning = false,
            lastFixAgeMs = 60 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 3,
            signedIn = true,
            trackingWanted = true,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
        assertFalse(TrackingHealth.warrantsUserAttention(verdict))
    }

    // ---- the ordinary working case ----

    @Test
    fun `running with a recent fix is healthy`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAgeMs = minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    @Test
    fun `a service that has just started and has no fix yet is not blind`() {
        // NO_FIX means "none known", not "none arriving". A fresh start must
        // get its first interval before anything is concluded.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAgeMs = TrackingHealth.NO_FIX,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    // ---- not running, blind, and failing to recover ----

    @Test
    fun `service down while it should be tracking is NotRunning`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = false,
            lastFixAgeMs = 15 * 60 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.NotRunning, verdict)
    }

    @Test
    fun `alive but receiving nothing is Blind`() {
        // The case a liveness-only check cannot see: Android denies while-in-use
        // location to a foreground service started from a background broadcast,
        // so the service is up and reporting nothing.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAgeMs = 40 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Blind, verdict)
    }

    @Test
    fun `repeated recovery failure escalates to NeedsUser and outranks the rest`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = false,
            lastFixAgeMs = 60 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = TrackingHealth.ESCALATE_AFTER_FAILURES,
        )
        assertEquals(TrackingHealth.Verdict.NeedsUser, verdict)
        assertTrue(TrackingHealth.warrantsUserAttention(verdict))
    }

    @Test
    fun `one failure is not enough to bother the user`() {
        // A single refusal is usually a transient FGS-eligibility state the next
        // tick heals. Escalating on the first would make the notification noise.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = false,
            lastFixAgeMs = 30 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 1,
        )
        assertEquals(TrackingHealth.Verdict.NotRunning, verdict)
        assertFalse(TrackingHealth.warrantsUserAttention(verdict))
    }

    // ---- boundaries ----

    @Test
    fun `blind threshold is four intervals but never under the floor`() {
        // A long interval scales with the interval...
        assertEquals(4 * poll, TrackingHealth.blindThresholdMs(poll))
        // ...and a short one is held up by the floor, so a lift or a car park
        // cannot trip it.
        assertEquals(TrackingHealth.MIN_BLIND_GRACE_MS, TrackingHealth.blindThresholdMs(minute))
    }

    @Test
    fun `exactly at the threshold is still healthy and one past it is blind`() {
        val threshold = TrackingHealth.blindThresholdMs(poll)
        assertEquals(
            TrackingHealth.Verdict.Healthy,
            TrackingHealth.assess(true, true, threshold, poll, 0),
        )
        assertEquals(
            TrackingHealth.Verdict.Blind,
            TrackingHealth.assess(true, true, threshold + 1, poll, 0),
        )
    }

    // ---- the timestamp overload ----

    @Test
    fun `timestamp overload treats a zero last-fix as never`() {
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAtMs = 0L,
            nowMs = 1_000_000L,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    @Test
    fun `timestamp overload converts an old fix into Blind`() {
        val now = 10_000_000L
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAtMs = now - (45 * minute),
            nowMs = now,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Blind, verdict)
    }

    @Test
    fun `a clock that jumped backwards does not read as a future fix`() {
        // Wall clock can move. A negative age must clamp to 0, not wrap into
        // something enormous that would falsely report Blind.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAtMs = 20_000_000L,
            nowMs = 10_000_000L,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
    }

    /**
     * The watchdog judges a phone against its live poll, never the send interval: a
     * STILL phone below 20 % battery polls every 20 minutes, and against the 60 s send
     * interval's 10-minute threshold a healthy phone would be called blind on most
     * ticks and have its location updates re-subscribed each time.
     */
    @Test
    fun `the watchdog judges blindness against the live poll, not the send interval`() {
        val minute = 60_000L
        val poll = TrackingHealth.watchdogPollIntervalMs(userIntervalMs = minute, livePollIntervalMs = 20 * minute)
        assertEquals(20 * minute, poll)
        // A 15-minute-old fix is normal at a 20-minute poll...
        val verdict = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAgeMs = 15 * minute,
            pollIntervalMs = poll,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Healthy, verdict)
        // ...and would have been Blind against the send interval alone.
        val before = TrackingHealth.assess(
            shouldBeTracking = true,
            serviceRunning = true,
            lastFixAgeMs = 15 * minute,
            pollIntervalMs = minute,
            consecutiveRestartFailures = 0,
        )
        assertEquals(TrackingHealth.Verdict.Blind, before)
    }

    @Test
    fun `with no running service the send interval still sets the poll`() {
        assertEquals(
            60_000L,
            TrackingHealth.watchdogPollIntervalMs(userIntervalMs = 60_000L, livePollIntervalMs = 0L),
        )
    }
}
