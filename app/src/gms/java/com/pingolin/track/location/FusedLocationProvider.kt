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
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * GMS FLAVOUR [LocationProvider] — Google Play services' fused location
 * provider.
 *
 * This class is a translation layer and nothing more: each method below is a
 * direct `FusedLocationProviderClient` call, so the gms build's tracking
 * behaviour — accuracy, cadence, the callback thread, the cached-then-fresh
 * ordering — is fused's own. The `libre` counterpart is
 * `src/libre/…/PlatformLocationProvider.kt`.
 */
@Singleton
class FusedLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocationProvider {

    companion object {
        private const val TAG = "FusedLocationProvider"

        private fun priorityFor(accuracy: LocationAccuracy): Int = when (accuracy) {
            LocationAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            LocationAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
    }

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastLocation(): Location? = suspendCancellableCoroutine { cont ->
        try {
            client.lastLocation
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener {
                    Log.w(TAG, "lastLocation failed: ${it.message}")
                    if (cont.isActive) cont.resume(null)
                }
        } catch (e: Exception) {
            // SecurityException when the permission was revoked mid-flight, and
            // anything the Play services client throws synchronously. Every
            // caller already treats a null fix as "not available right now".
            Log.w(TAG, "lastLocation threw: ${e.message}")
            if (cont.isActive) cont.resume(null)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(accuracy: LocationAccuracy): Location? =
        suspendCancellableCoroutine { cont ->
            // The token is what makes coroutine cancellation actually
            // turn the radio off, rather than merely abandoning the
            // result. Every call gets one, so callers need not build
            // their own.
            val cancellation = CancellationTokenSource()
            cont.invokeOnCancellation { cancellation.cancel() }
            try {
                client.getCurrentLocation(priorityFor(accuracy), cancellation.token)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener {
                        Log.w(TAG, "getCurrentLocation failed: ${it.message}")
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation threw: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    override fun requestUpdates(
        request: LocationUpdateRequest,
        listener: LocationUpdateListener,
    ): LocationUpdateHandle {
        // setWaitForAccurateLocation(false) delivers the first fix without
        // waiting for a better one; min == max == interval allows no faster
        // delivery than requested and no batching. LocationTrackingService is
        // the only caller that subscribes at a rate it changes over time.
        val locationRequest = LocationRequest.Builder(priorityFor(request.accuracy), request.intervalMs)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(request.intervalMs)
            .setMaxUpdateDelayMillis(request.intervalMs)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { listener.onLocation(it) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                listener.onAvailabilityChanged(availability.isLocationAvailable)
            }
        }

        client.requestLocationUpdates(locationRequest, callback, request.looper)

        return object : LocationUpdateHandle {
            override fun cancel() {
                client.removeLocationUpdates(callback)
            }
        }
    }
}
