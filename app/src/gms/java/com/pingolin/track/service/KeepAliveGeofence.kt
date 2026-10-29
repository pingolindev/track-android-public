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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.pingolin.track.receiver.KeepAliveReceiver

/**
 * The keep-alive's second trigger, on Play services builds: one geofence around a recent
 * position, whose EXIT brings the location service back if it died. A geofence
 * transition is a documented exemption for starting a foreground service from the background, kept
 * as insurance should a platform ever stop allowing it for [LocationKeepAlive]'s deliveries. It
 * covers a person who moves; a still person's last position stays true.
 *
 * One fixed request id, so arming again replaces it; armed around the first fix of each start of the
 * service and again once the phone is out of it ([LocationTrackingService.maybeArmGeofence]), and at
 * boot and update around the last position sent; let go at a deliberate stop and both sign-outs. Its
 * centre goes only to Play services on this phone. Its deliveries reach [KeepAliveReceiver], which
 * decides as for the keep-alive and reads nothing from them.
 */
object KeepAliveGeofence {
    private const val TAG = "KeepAliveGeofence"

    const val ACTION_EXIT = "com.pingolin.track.KEEP_ALIVE_EXIT"
    private const val REQUEST_ID = "keep-alive"
    private const val REQUEST_CODE = 0x4B41_4746 // "KAGF"

    /** Wide enough for the network fixes Play services judges a still phone by: never under 100 m. */
    const val RADIUS_METERS = 200f

    @SuppressLint("MissingPermission")
    fun arm(context: Context, latitude: Double, longitude: Double) {
        // Geofences need location all the time, as the service they bring back does.
        if (!LocationPolicy.hasPreciseLocation(context) || !LocationPolicy.hasBackgroundLocation(context)) {
            disarm(context)
            return
        }
        val geofence = Geofence.Builder()
            .setRequestId(REQUEST_ID)
            .setCircularRegion(latitude, longitude, RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        // No initial trigger: armed where the phone is, it starts inside.
        val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(geofence).build()
        try {
            LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent(context))
                .addOnFailureListener { Log.w(TAG, "Geofence not armed: ${it.javaClass.simpleName}") }
        } catch (e: Exception) {
            Log.w(TAG, "Geofence not armed: ${e.javaClass.simpleName}")
        }
    }

    fun disarm(context: Context) {
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Geofence not let go: ${e.javaClass.simpleName}")
        }
    }

    /** Explicit, to this app's unexported receiver; mutable because Play services fills in the event. */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
}
