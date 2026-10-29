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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The two rules that decide whether this app can produce a usable location, in
 * one place because neither is safe to change without the other.
 *
 * Kept apart, they can disagree invisibly: a permission gate that accepts
 * "Approximate location" beside an accuracy gate that throws away every fix an
 * approximate grant can produce leaves a user who answered the prompt, and holds
 * a foreground-service notification saying location sharing is active, sharing
 * nothing.
 *
 * Lives outside [LocationTrackingService] so it can be tested without the
 * service's native library loading (SQLCipher, pingolin_mobile), and so the
 * heartbeat worker can gate on exactly the same rule as the service it starts
 * rather than on a hand-copied twin of it.
 */
object LocationPolicy {

    // Accuracy thresholds in meters.
    /** Real-time mode: hold fixes to roughly a city block. */
    const val MIN_ACCURACY_HIGH = 100f

    /**
     * Battery-saver mode: accept a rougher fix rather than report nothing.
     *
     * This matches what battery saver ASKS for:
     * [LocationTrackingService.pollProfileFor] gives it a flat
     * `LocationAccuracy.BALANCED` (wifi/cell) poll at the batch interval, the
     * same shape as the iOS app's battery saver
     * (`kCLLocationAccuracyHundredMeters`, accepting 500 m).
     *
     * It is also the bound on a coarse fix that may CONFIRM presence while STILL
     * in real-time mode (`LocationTrackingService.coarseStillFixVerdict`), where
     * the STILL poll is BALANCED too but only fixes within [MIN_ACCURACY_HIGH]
     * count as positions.
     *
     * [com.pingolin.track.worker.LocationHeartbeatWorker] requests
     * `LocationAccuracy.BALANCED` as well, on its own schedule, for every user.
     */
    const val MIN_ACCURACY_BALANCED = 500f

    /**
     * The oldest a fix may be when it arrives and still count as where the phone is now: the
     * service's stale-fix guard (a cached fix delivered on a stream's start would otherwise become a
     * stale trip anchor), and what [com.pingolin.track.location.NewestOwnFix] keeps to as well.
     */
    const val STALE_FIX_MAX_AGE_MS = 30_000L

    /** The worst accuracy, in metres, a fix may have and still be used. */
    fun minAccuracyMeters(batterySaver: Boolean): Float =
        if (batterySaver) MIN_ACCURACY_BALANCED else MIN_ACCURACY_HIGH

    /** Whether a fix of this accuracy survives the filter in the given mode. */
    fun acceptsFix(accuracyMeters: Float, batterySaver: Boolean): Boolean =
        accuracyMeters <= minAccuracyMeters(batterySaver)

    /**
     * Whether tracking may run at all.
     *
     * The PLATFORM would accept either grant — the `location`
     * foreground-service type requires coarse *or* fine ("any of"), so a
     * coarse-only app can legally start the service. It must not. ACCESS_COARSE
     * alone yields kilometre-scale fixes, which [acceptsFix] rejects in every
     * mode, so a service running on coarse burns battery, shows the persistent
     * "Location sharing active" notification, and discards 100% of what it
     * collects. Not running is the more honest state, and it is the one the
     * user can see and act on.
     */
    fun hasPreciseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the user chose "Approximate location" and nothing better — the
     * one state that reads as a grant everywhere and behaves as a denial here.
     */
    fun hasCoarseLocationOnly(context: Context): Boolean =
        !hasPreciseLocation(context) &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * "Allow all the time": what a `location` foreground service started from the background needs on
     * Android 14 ("you cannot create a location foreground service while your app is in the
     * background, unless you've been granted the ACCESS_BACKGROUND_LOCATION runtime permission").
     * Before Android 10 there was no separate grant.
     */
    fun hasBackgroundLocation(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
}
