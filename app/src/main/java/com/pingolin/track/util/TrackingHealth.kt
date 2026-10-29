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

/**
 * Is tracking actually working? Pure decision logic, no Android types, so the
 * rules can be tested directly.
 *
 * WHY THIS EXISTS: tracking can stop with nothing saying so. The foreground
 * service can end CLEANLY — its notification removed, with no crash, no ANR,
 * no `Force stopping` and no `killDueTo…` — and the process be frozen minutes
 * later, while the session is live, every location permission is granted and
 * the battery is full, and the watchdog's alarm need not bring it back.
 *
 * Two separate gaps make that silent, and this file addresses the second:
 *   1. Nothing records WHY tracking stopped ([TrackingJournal] covers that).
 *   2. Nothing asks whether tracking is still working. A watchdog that only
 *      asks "is the service object alive?" and treats a start attempt as
 *      success misses a service that is alive and receiving nothing —
 *      Android denies while-in-use location to a foreground service started
 *      from a background broadcast (`Foreground service started from
 *      background can not have location/camera/microphone access`). So
 *      "running" is not "working".
 */
object TrackingHealth {

    /**
     * A fix is overdue once this many polling intervals have passed. Four is
     * deliberately generous: a single missed fix is normal (tunnel, indoors,
     * a doze window that deferred the request), and a false "tracking is
     * broken" notification is worse than a late true one.
     */
    const val BLIND_INTERVAL_MULTIPLE = 4

    /**
     * …but never call it blind sooner than this, however short the interval.
     * At a one-minute poll, four intervals is four minutes, which any lift
     * or car park would trip.
     */
    const val MIN_BLIND_GRACE_MS: Long = 10L * 60L * 1000L

    /**
     * Consecutive failed watchdog cycles before the user is told. Two, not
     * one: a single refusal is usually a transient FGS-eligibility state that
     * the next tick recovers from by itself, and the whole point of the
     * automatic path is to not bother the user for those. Two consecutive
     * failures (~40 minutes at the 20-minute cadence) means the automatic
     * path is not working and only the user can fix it.
     */
    const val ESCALATE_AFTER_FAILURES = 2

    /** Sentinel for "this device has never produced a fix". */
    const val NO_FIX: Long = -1L

    sealed interface Verdict {
        /** Tracking is off by choice, or on and delivering. Say nothing. */
        object Healthy : Verdict

        /**
         * The app has signed itself out while tracking was meant to be on.
         *
         * Distinct from [NotRunning] because it is NOT recoverable
         * automatically and must not be reported as if it were: both
         * `BootReceiver` and the watchdog gate on `isLoggedIn()`, so once the
         * client believes it is signed out every self-heal path becomes a
         * deliberate no-op — while the server's session row may still be live
         * the whole time.
         */
        object SignedOut : Verdict

        /** Should be tracking, but the service is not up. The watchdog retries. */
        object NotRunning : Verdict

        /** The service is up but no fix has arrived in far too long. */
        object Blind : Verdict

        /**
         * Automatic recovery has been tried and failed repeatedly. Only a
         * foreground start can reliably restore location access, and only the
         * user can produce one.
         */
        object NeedsUser : Verdict
    }

    /**
     * @param shouldBeTracking the user's intent: logged in, enabled, not paused.
     *        When false the answer is always [Verdict.Healthy] — tracking that
     *        is off on purpose is not a fault, and reporting it would train the
     *        user to ignore the notification that matters.
     * @param serviceRunning whether the foreground service object is alive.
     * @param lastFixAgeMs how long since the last accepted fix, or [NO_FIX] if
     *        none is known. A service that has only just started has no fix
     *        yet; the caller passes [NO_FIX] and gets no "blind" verdict for it.
     * @param pollIntervalMs the configured polling interval.
     * @param consecutiveRestartFailures how many watchdog cycles in a row have
     *        tried and failed to get tracking working again.
     */
    fun assess(
        shouldBeTracking: Boolean,
        serviceRunning: Boolean,
        lastFixAgeMs: Long,
        pollIntervalMs: Long,
        consecutiveRestartFailures: Int,
        signedIn: Boolean = true,
        trackingWanted: Boolean = shouldBeTracking,
    ): Verdict {
        // A signed-out app with tracking switched on is the one case that
        // looks idle and is not: no automatic path will ever restart it.
        // Checked before the intent gate below precisely because
        // `shouldBeTracking` is itself false when signed out, so this
        // state would otherwise be invisible.
        if (!signedIn && trackingWanted) return Verdict.SignedOut

        // Paused or disabled is a healthy state whatever else is true,
        // including a stale last fix left from before the user turned it off.
        if (!shouldBeTracking) return Verdict.Healthy

        if (consecutiveRestartFailures >= ESCALATE_AFTER_FAILURES) return Verdict.NeedsUser

        if (!serviceRunning) return Verdict.NotRunning

        if (lastFixAgeMs != NO_FIX && lastFixAgeMs > blindThresholdMs(pollIntervalMs)) {
            return Verdict.Blind
        }

        return Verdict.Healthy
    }

    /**
     * Convenience overload for callers holding timestamps rather than an age.
     * `lastFixAtMs <= 0` means "never".
     */
    fun assess(
        shouldBeTracking: Boolean,
        serviceRunning: Boolean,
        lastFixAtMs: Long,
        nowMs: Long,
        pollIntervalMs: Long,
        consecutiveRestartFailures: Int,
        signedIn: Boolean = true,
        trackingWanted: Boolean = shouldBeTracking,
    ): Verdict = assess(
        shouldBeTracking = shouldBeTracking,
        serviceRunning = serviceRunning,
        lastFixAgeMs = if (lastFixAtMs <= 0L) NO_FIX else (nowMs - lastFixAtMs).coerceAtLeast(0L),
        pollIntervalMs = pollIntervalMs,
        consecutiveRestartFailures = consecutiveRestartFailures,
        signedIn = signedIn,
        trackingWanted = trackingWanted,
    )

    /** How old a fix may get before the service counts as blind. */
    fun blindThresholdMs(pollIntervalMs: Long): Long =
        maxOf(pollIntervalMs * BLIND_INTERVAL_MULTIPLE, MIN_BLIND_GRACE_MS)

    /**
     * The polling interval to judge blindness against: the LONGER of the user's send
     * interval and the poll the running service is actually using
     * (`LocationTrackingService.publishedPollIntervalMs`, 0 when it is not running).
     *
     * The send interval alone is not enough. At the 60 s default that is a 10-minute
     * threshold, while a STILL phone below 20 % battery polls every 20 minutes — so a
     * perfectly healthy phone would be called blind on most ticks, and each verdict
     * would re-subscribe its location updates, doubling the fixes the low-battery
     * multiplier exists to save (or postponing them, which risks a false "Location
     * sharing has stopped"). In battery saver the poll can be an hour.
     */
    fun watchdogPollIntervalMs(userIntervalMs: Long, livePollIntervalMs: Long): Long =
        maxOf(userIntervalMs, livePollIntervalMs)

    /** Whether a verdict is worth telling the user about. */
    fun warrantsUserAttention(verdict: Verdict): Boolean =
        verdict is Verdict.NeedsUser || verdict is Verdict.SignedOut
}
