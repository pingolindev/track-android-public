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
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.util.Log
import com.pingolin.track.receiver.KeepAliveReceiver

/**
 * Brings the location service back after it dies, while sharing is wanted.
 *
 * WHY THIS: since Android 12 an app in the background may start a foreground service only from an
 * exempt moment, and since 14 a `location` one only with "Allow all the time" as well. Once the
 * service dies (an OEM kill, a crash, a store that won't open), nothing else this app has can bring it
 * back: the watchdog's inexact alarm and the heartbeat's job are not exempt ("Background started FGS:
 * Disallowed"), and the server's wake comes after a day by design. A location delivered to a
 * PendingIntent is: the platform sends it with a 10 s allowlist to start a foreground service
 * (LocationProviderManager: "allows apps to start a fg service in response to a location PI",
 * REASON_LOCATION_PROVIDER). So one low-power request, delivered to [KeepAliveReceiver], restarts the
 * service within its interval, moving or still, on both builds.
 *
 * It holds no position: the receiver reads no location from the delivery and sends or stores nothing
 * (every position still goes through the service's own gated pipeline). One request, one
 * PendingIntent, registered again with the same one (idempotent). It is held for as long as sharing is
 * wanted, the service running or not: a kill gives the service no last word, so a request made only on
 * its way out would never be made.
 */
object LocationKeepAlive {
    private const val TAG = "LocationKeepAlive"

    const val ACTION_KEEP_ALIVE = "com.pingolin.track.KEEP_ALIVE"
    private const val REQUEST_CODE = 0x4B41_4C56 // "KALV"

    /** At most this often: the keep-alive is a backstop, not a second tracker. */
    const val INTERVAL_MS = 15L * 60L * 1000L

    /**
     * Never more often than the service's own cadence: in battery saver that is its batch interval, up
     * to an hour, and a keep-alive at 15 minutes would add the wakes the person turned down.
     */
    fun intervalMsFor(batterySaver: Boolean, saverIntervalMinutes: Int): Long =
        if (!batterySaver) INTERVAL_MS
        else maxOf(INTERVAL_MS, saverIntervalMinutes.coerceAtLeast(1) * 60_000L)

    /**
     * Which provider: the platform's fused one where there is one (Android 12+), else the network one,
     * else the passive one, which costs nothing and hears only what others ask for. Never GPS.
     */
    internal fun providerFor(hasFused: Boolean, networkEnabled: Boolean): String = when {
        hasFused -> LocationManager.FUSED_PROVIDER
        networkEnabled -> LocationManager.NETWORK_PROVIDER
        else -> LocationManager.PASSIVE_PROVIDER
    }

    /**
     * Hold the request, or hold it again with the current interval. Only with precise location allowed
     * all the time: without it a location service can't be made from the background at all, so a
     * request would only wake this app for nothing.
     */
    @SuppressLint("MissingPermission")
    fun register(context: Context, batterySaver: Boolean, saverIntervalMinutes: Int) {
        if (!LocationPolicy.hasPreciseLocation(context) || !LocationPolicy.hasBackgroundLocation(context)) {
            unregister(context)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val interval = intervalMsFor(batterySaver, saverIntervalMinutes)
        try {
            val hasFused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)
            val provider = providerFor(hasFused, lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = LocationRequest.Builder(interval)
                    .setQuality(LocationRequest.QUALITY_LOW_POWER)
                    .setMinUpdateIntervalMillis(interval)
                    .build()
                lm.requestLocationUpdates(provider, request, pendingIntent(context))
            } else {
                lm.requestLocationUpdates(provider, interval, 0f, pendingIntent(context))
            }
            Log.i(TAG, "Keep-alive held: $provider every ${interval / 60_000} min")
        } catch (e: Exception) {
            // SecurityException (a permission taken away just now), IllegalArgumentException (no such
            // provider): nothing held, and the service's next start tries again.
            Log.w(TAG, "Keep-alive not held: ${e.javaClass.simpleName}")
        }
    }

    /** Let it go: sign-out, a deliberate stop, or sharing no longer wanted. Safe when none is held. */
    fun unregister(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        try {
            lm.removeUpdates(pendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Keep-alive not let go: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Explicit, to this app's own unexported receiver. Mutable because the platform fills in the
     * location it delivers (the receiver never reads it).
     */
    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_KEEP_ALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
}
