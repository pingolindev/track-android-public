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

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LIBRE FLAVOUR [LocationProvider] — the platform's own
 * [android.location.LocationManager], with no Google Play services anywhere in
 * the path.
 *
 * WHAT FUSED DOES THAT THIS HAS TO DO BY HAND
 * Fused is a fusion layer: it merges GPS, wifi/cell and the device's motion
 * sensors behind one subscription and hands back a single stream. LocationManager
 * does not fuse anything — it exposes each provider separately — so the two jobs
 * fused does in the gms build are done explicitly here:
 *
 *  1. BOTH PROVIDERS AT ONCE, for a HIGH subscription. `requestUpdates`
 *     subscribes to GPS *and* NETWORK where both exist. Subscribing to GPS alone
 *     goes silent indoors; NETWORK alone is far too coarse for place-alert
 *     geofencing. A BALANCED subscription (STILL, battery saver) takes NETWORK
 *     only — see [subscriptionProvidersFor].
 *  2. PICKING THE BETTER FIX. With two subscriptions the callbacks interleave, so
 *     [isBetterFix] decides which one to forward — newer wins outright once the
 *     old one is stale, otherwise the more accurate one does. Without it a 2km
 *     cell fix arriving after a 5m satellite fix would walk the user's marker
 *     across town.
 *
 * WHAT IT GIVES UP: fused's sensor-assisted dead reckoning between
 * fixes, and its cross-app batching (two apps asking for GPS cost one duty cycle
 * under fused, two under LocationManager). Expect a libre build to be somewhat
 * hungrier while actively tracking. `MotionDetectionManager` (in `src/main`,
 * shared by both flavours) is what keeps a STATIONARY device cheap, which is
 * where the hours go.
 */
@Singleton
class PlatformLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationProvider {

    companion object {
        private const val TAG = "PlatformLocationProvider"

        /**
         * A fix this much newer than the one we last forwarded wins regardless of
         * accuracy: past this age the old fix describes where the user WAS, and a
         * coarse fix of where they are now is the more truthful answer. Two
         * minutes is the interval Android's own "better location" guidance uses.
         */
        private const val SIGNIFICANTLY_NEWER_MS = 2 * 60 * 1000L

        /**
         * Which fix to forward when GPS and NETWORK callbacks interleave. Pure, so
         * `PlatformLocationProviderTest` can pin it without a device.
         *
         * @param candidateAgeMs / [currentAgeMs] fix age at the moment of
         *   comparison (elapsed-realtime based, so wall-clock changes can't
         *   invert it).
         */
        @JvmStatic
        internal fun isBetterFix(
            candidateAgeMs: Long,
            candidateAccuracyM: Float,
            currentAgeMs: Long,
            currentAccuracyM: Float,
        ): Boolean {
            // Nothing to compare against.
            if (currentAgeMs == Long.MAX_VALUE) return true
            val newerBy = currentAgeMs - candidateAgeMs
            // Decisively newer: take it even if it is coarser.
            if (newerBy > SIGNIFICANTLY_NEWER_MS) return true
            // Decisively older: never take it, however precise.
            if (newerBy < -SIGNIFICANTLY_NEWER_MS) return false
            // Comparable age — accuracy decides. Ties go to the newer one, which
            // keeps the timestamp everyone else sees moving.
            if (candidateAccuracyM < currentAccuracyM) return true
            return candidateAccuracyM == currentAccuracyM && newerBy >= 0
        }

        /**
         * Which providers a periodic subscription at [accuracy] listens to, given the
         * enabled ones. Pure, so `PlatformLocationProviderTest` can pin it.
         *
         *  - HIGH: GPS AND NETWORK, fanned in through [isBetterFix] — GPS alone goes
         *    silent indoors, NETWORK alone is too coarse for geofencing.
         *  - BALANCED: ONE provider, NETWORK where it exists. Subscribing GPS as well
         *    would keep the satellite receiver running on every BALANCED poll, making
         *    the STILL and battery-saver polls cost what a HIGH poll costs. GPS is
         *    only the fallback on a device with no network location provider at all.
         */
        @JvmStatic
        internal fun subscriptionProvidersFor(accuracy: LocationAccuracy, enabled: Set<String>): List<String> =
            when (accuracy) {
                LocationAccuracy.HIGH ->
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in enabled }
                LocationAccuracy.BALANCED ->
                    listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in enabled }.take(1)
            }
    }

    private val locationManager: LocationManager? by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    /** Runs the compat callbacks inline on whichever thread delivers them. */
    private val directExecutor = Executor { it.run() }

    /**
     * The providers to use for [accuracy], best first, filtered to those the
     * device actually has. GPS_PROVIDER and NETWORK_PROVIDER are both optional —
     * a tablet with no radio has neither, and `getProviders` is the only honest
     * way to find out.
     */
    private fun providersFor(accuracy: LocationAccuracy): List<String> {
        val lm = locationManager ?: return emptyList()
        val available = try {
            lm.getProviders(/* enabledOnly = */ true).toSet()
        } catch (e: Exception) {
            Log.w(TAG, "getProviders failed: ${e.message}")
            emptySet<String>()
        }
        val preferred = when (accuracy) {
            LocationAccuracy.HIGH -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            LocationAccuracy.BALANCED -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        }
        return preferred.filter { it in available }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): Location? {
        val lm = locationManager ?: return null
        // Every provider, not just the preferred one: this call is the cheap
        // "have you got anything at all" path, and refusing a slightly older
        // NETWORK fix because GPS has none would make it answer null far more
        // often than fused's lastLocation does.
        var best: Location? = null
        var bestAge = Long.MAX_VALUE
        for (provider in providersFor(LocationAccuracy.HIGH)) {
            val candidate = try {
                lm.getLastKnownLocation(provider)
            } catch (e: Exception) {
                Log.w(TAG, "getLastKnownLocation($provider) failed: ${e.message}")
                null
            } ?: continue
            val candidateAge = ageMs(candidate)
            if (isBetterFix(candidateAge, candidate.accuracy, bestAge, best?.accuracy ?: Float.MAX_VALUE)) {
                best = candidate
                bestAge = candidateAge
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(accuracy: LocationAccuracy): Location? {
        val lm = locationManager ?: return null
        // Ordered by preference and tried in turn: getCurrentLocation resolves
        // with null when a provider cannot produce a fix in its own timeout
        // (indoors on GPS, say), and falling through to the next one is what
        // fused does internally.
        for (provider in providersFor(accuracy)) {
            val fix = awaitCurrentLocation(lm, provider)
            if (fix != null) return fix
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation {
                // Cancels the request itself, so an abandoned caller does not
                // leave the GPS powered up until the provider times out.
                runCatching { signal.cancel() }
            }
            try {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, directExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } catch (e: Exception) {
                // SecurityException on a revoked permission, IllegalArgumentException
                // if the provider disappeared between getProviders and here.
                Log.w(TAG, "getCurrentLocation($provider) threw: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    override fun requestUpdates(
        request: LocationUpdateRequest,
        listener: LocationUpdateListener,
    ): LocationUpdateHandle {
        val lm = locationManager
        val providers = subscriptionProvidersFor(request.accuracy, providersFor(request.accuracy).toSet())
        if (lm == null || providers.isEmpty()) {
            Log.w(TAG, "No location provider available; updates will not start")
            listener.onAvailabilityChanged(false)
            return object : LocationUpdateHandle {
                override fun cancel() = Unit
            }
        }

        // Mirrors the request FusedLocationProvider makes: no minimum distance
        // (the send filter does distance itself), min == max == the requested
        // interval so the OS neither delays nor accelerates it.
        val locationRequest = LocationRequestCompat.Builder(request.intervalMs)
            .setQuality(
                when (request.accuracy) {
                    LocationAccuracy.HIGH -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                    LocationAccuracy.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                }
            )
            .setMinUpdateIntervalMillis(request.intervalMs)
            .setMaxUpdateDelayMillis(request.intervalMs)
            .setMinUpdateDistanceMeters(0f)
            .build()

        // One listener object shared across both provider subscriptions, so the
        // GPS-vs-NETWORK comparison in isBetterFix sees every fix. Guarded by the
        // listener's own monitor rather than assuming a single callback thread:
        // requestLocationUpdates is called once per provider and the framework
        // does not promise they interleave on one thread.
        val fanIn = object : LocationListenerCompat {
            private var lastForwarded: Location? = null

            override fun onLocationChanged(location: Location) {
                val forward = synchronized(this) {
                    val current = lastForwarded
                    val better = isBetterFix(
                        candidateAgeMs = ageMs(location),
                        candidateAccuracyM = location.accuracy,
                        currentAgeMs = current?.let { ageMs(it) } ?: Long.MAX_VALUE,
                        currentAccuracyM = current?.accuracy ?: Float.MAX_VALUE,
                    )
                    if (better) lastForwarded = location
                    better
                }
                if (forward) listener.onLocation(location)
            }

            override fun onProviderEnabled(provider: String) {
                listener.onAvailabilityChanged(true)
            }

            override fun onProviderDisabled(provider: String) {
                // Only report unavailable once NOTHING is left — with two
                // subscriptions, GPS going dark while NETWORK still works is not
                // an outage, and reporting one would just spam the service log.
                listener.onAvailabilityChanged(providersFor(request.accuracy).isNotEmpty())
            }
        }

        var subscribed = 0
        for (provider in providers) {
            try {
                LocationManagerCompat.requestLocationUpdates(
                    lm, provider, locationRequest, fanIn, request.looper
                )
                subscribed++
            } catch (e: Exception) {
                Log.w(TAG, "requestLocationUpdates($provider) failed: ${e.message}")
            }
        }
        Log.i(TAG, "Subscribed to $subscribed provider(s) $providers at ${request.intervalMs}ms")

        return object : LocationUpdateHandle {
            override fun cancel() {
                // One removeUpdates drops the listener from EVERY provider it was
                // registered with — LocationManager keys its bookkeeping on the
                // listener, not on the (provider, listener) pair.
                runCatching { LocationManagerCompat.removeUpdates(lm, fanIn) }
                    .onFailure { Log.w(TAG, "removeUpdates failed: ${it.message}") }
            }
        }
    }
}
