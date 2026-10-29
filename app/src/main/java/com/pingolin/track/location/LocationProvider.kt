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

package com.pingolin.track.location

import android.location.Location
import android.os.Looper

/**
 * Where this app gets GPS fixes from, behind an interface so `src/main` never
 * names Google Play services.
 *
 * `src/gms` binds an implementation wrapping `FusedLocationProviderClient`; the
 * `libre` distribution flavour resolves no Play services artifact at all (see
 * `gmsImplementation` in app/build.gradle.kts) and binds one over the
 * platform's own [android.location.LocationManager] instead.
 *
 * The surface below is deliberately only what the call sites use — a cached
 * fix, a fresh single-shot fix, and a periodic subscription — so the two
 * implementations stay small enough to read side by side. Nothing here is an
 * abstraction over tracking policy: interval and accuracy are decided by the
 * callers, as they would be with a fused client directly.
 */
interface LocationProvider {

    /**
     * The most recent fix the system already has, without asking the hardware
     * for a new one. Null when there is none (fresh boot, location off, no
     * permission). Returns immediately — this is the cheap call, and every call
     * site that has one tries it before paying for [currentLocation].
     */
    suspend fun lastLocation(): Location?

    /**
     * A fresh fix, powering up the hardware if it must. Null when one cannot be
     * obtained. Honours coroutine cancellation: cancelling the calling scope
     * cancels the underlying request rather than leaving the radio on.
     */
    suspend fun currentLocation(accuracy: LocationAccuracy = LocationAccuracy.HIGH): Location?

    /**
     * Subscribe to periodic fixes. The returned handle is the ONLY way to stop
     * them; callers hold it for the lifetime of the subscription and re-request
     * (cancel + request) when the interval changes.
     */
    fun requestUpdates(request: LocationUpdateRequest, listener: LocationUpdateListener): LocationUpdateHandle
}

/**
 * How much battery a fix is worth. Maps to fused's `Priority` constants on gms
 * and to a provider choice on libre; the two names are all the call sites ever
 * asked for.
 */
enum class LocationAccuracy {
    /** Satellite fix wanted — fused PRIORITY_HIGH_ACCURACY, libre GPS provider. */
    HIGH,

    /** Cell/wifi is good enough — fused PRIORITY_BALANCED_POWER_ACCURACY,
     *  libre NETWORK provider (falling back to GPS where there is none). */
    BALANCED,
}

/**
 * @param intervalMs desired period between fixes.
 * @param accuracy see [LocationAccuracy].
 * @param looper the thread the callbacks are delivered on. Load-bearing:
 *   `LocationTrackingService` hands its dedicated `LocationCallbackThread` here
 *   so fix processing never runs on the main thread.
 */
data class LocationUpdateRequest(
    val intervalMs: Long,
    val accuracy: LocationAccuracy,
    val looper: Looper,
)

interface LocationUpdateListener {
    fun onLocation(location: Location)

    /**
     * Whether the provider currently believes it can produce fixes. Default
     * no-op because only the foreground service logs it; on libre it is derived
     * from provider enable/disable callbacks rather than fused's
     * `LocationAvailability`.
     */
    fun onAvailabilityChanged(isAvailable: Boolean) = Unit
}

/** Cancels a subscription started by [LocationProvider.requestUpdates]. Idempotent. */
interface LocationUpdateHandle {
    fun cancel()
}
