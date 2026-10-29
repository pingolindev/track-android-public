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

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uniffi.pingolin_mobile.BoundaryCrossingType
import uniffi.pingolin_mobile.BoundaryMonitorCallback

/**
 * Implementation of BoundaryMonitorCallback for handling boundary crossing events.
 *
 * E2EE Compliance:
 * - All boundary checking is done client-side
 * - Server never sees plaintext coordinates or location data
 * - Notifications are generated locally based on decrypted data
 *
 * Usage:
 * - Set this callback on BoundaryMonitor to receive crossing events
 * - Provide a CrossingReportHandler to report crossings to the server
 */
class BoundaryMonitorCallbackImpl(
    private val alertHandler: AlertHandler,
    private val displayNameProvider: (String) -> String = { it },
    private val crossingReportHandler: CrossingReportHandler? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BoundaryMonitorCallback {

    companion object {
        private const val TAG = "BoundaryMonitorCallback"
    }

    /**
     * Functional interface for handling crossing report requests.
     * This allows decoupling from BoundaryRepository to avoid circular dependencies.
     */
    fun interface CrossingReportHandler {
        /**
         * Report a boundary crossing to the server.
         *
         * @param crossingType "enter" or "exit"
         * @param occurredAtMs when the crossing was DETECTED on this device, captured
         *   before dispatch so it survives however long connectivity delays the send.
         *   This has to travel with the report: the server can only stamp its own receive
         *   time, so without it a crossing sent after an offline stretch reaches
         *   subscribers looking as though it had just happened.
         */
        suspend fun reportCrossing(
            placeId: String,
            userId: String,
            deviceId: String,
            crossingType: String,
            occurredAtMs: Long
        )
    }

    /**
     * Called when a boundary crossing is detected for a monitored user.
     * Shows a local notification to the current user.
     */
    override fun onCrossingDetected(
        userId: String,
        boundaryName: String,
        crossingType: BoundaryCrossingType,
        distanceMeters: Double
    ) = sdkCallback(TAG, "onCrossingDetected") {
        Log.d(TAG, "Crossing detected: user=$userId, place=$boundaryName, type=$crossingType")

        val displayName = displayNameProvider(userId)
        val alertType = when (crossingType) {
            BoundaryCrossingType.ENTER -> AlertHandler.TYPE_PLACE_ARRIVAL
            BoundaryCrossingType.EXIT -> AlertHandler.TYPE_PLACE_DEPARTURE
        }

        val data = mapOf(
            "user_name" to displayName,
            "place_name" to boundaryName,
            "user_id" to userId,
            "distance" to distanceMeters.toInt().toString()
        )

        alertHandler.handleAlert(alertType, data)
    }

    /**
     * Called when places are loaded into the boundary monitor.
     */
    override fun onPlacesLoaded(count: UInt) = sdkCallback(TAG, "onPlacesLoaded") {
        Log.d(TAG, "Places loaded: $count boundaries registered for monitoring")
    }

    /**
     * Called when an error occurs in the boundary monitor.
     */
    override fun onError(error: String) = sdkCallback(TAG, "onError") {
        Log.e(TAG, "Boundary monitor error: $error")
    }

    /**
     * Called to request reporting a crossing to the server.
     * The SDK calls this after detecting a crossing so the platform can
     * send the encrypted crossing data to the backend.
     *
     * Uses coroutine scope instead of runBlocking to avoid blocking the calling thread.
     * This is important because this callback may be invoked from the main thread or
     * from the native SDK's thread, and blocking could cause ANRs or deadlocks.
     */
    override fun onRequestReportCrossing(
        placeId: String,
        userId: String,
        deviceId: String,
        crossingType: String
    ) = sdkCallback(TAG, "onRequestReportCrossing") {
        Log.d(TAG, "Requesting crossing report: place=$placeId, user=$userId, type=$crossingType")

        // Stamp the crossing NOW, on the detecting thread — not inside the
        // coroutine below and not at the server. Everything after this point
        // (coroutine scheduling, key lookup, encryption, the HTTP round trip and
        // any connectivity wait) happens after the crossing, and would otherwise
        // be folded into the time the subscriber is shown.
        val detectedAtMs = System.currentTimeMillis()

        crossingReportHandler?.let { handler ->
            // Launch in coroutine scope instead of blocking
            coroutineScope.launch {
                try {
                    handler.reportCrossing(placeId, userId, deviceId, crossingType, detectedAtMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to report crossing", e)
                }
            }
        } ?: run {
            Log.w(TAG, "No crossing report handler set, crossing will not be reported to server")
        }
    }
}
