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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On Play services builds, a geofence around a recent position brings the location service back on
 * its EXIT, as insurance for the keep-alive. One constant id, moved along a few times an hour at
 * most, never per fix; let go wherever the keep-alive is.
 */
class KeepAliveGeofenceTest {

    private fun file(path: String) = File("src/$path").readText()
    private fun main(path: String) = file("main/java/com/pingolin/track/$path")

    /** [name]'s body in [text], up to its closing brace at [indent]. */
    private fun body(text: String, name: String, indent: String = "    "): String =
        text.substring(text.indexOf(name)).let { it.substring(0, it.indexOf("\n$indent}\n")) }

    @Test
    fun `moved along only once out of it, and no more often than every ten minutes`() {
        val tenMinutes = LocationTrackingService.GEOFENCE_REARM_MIN_MS
        assertFalse("still inside", LocationTrackingService.geofenceRearmDue(150f, 2 * tenMinutes))
        assertFalse("out, but armed just now", LocationTrackingService.geofenceRearmDue(500f, tenMinutes - 1))
        assertTrue(LocationTrackingService.geofenceRearmDue(KeepAliveGeofence.RADIUS_METERS, tenMinutes))
        assertTrue("never under 100 m", KeepAliveGeofence.RADIUS_METERS >= 100f)
    }

    @Test
    fun `one exit-only geofence with a fixed id, to this app's receiver, only with location all the time`() {
        val gms = file("gms/java/com/pingolin/track/service/KeepAliveGeofence.kt")
        assertTrue(gms.contains(".setRequestId(REQUEST_ID)"))
        assertTrue(gms.contains(".setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)"))
        assertTrue(gms.contains(".setExpirationDuration(Geofence.NEVER_EXPIRE)"))
        assertTrue(gms.contains("GeofencingRequest.Builder().setInitialTrigger(0)"))
        assertTrue(gms.contains("Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_EXIT)"))
        assertTrue(body(gms, "fun arm(").contains(
            "if (!LocationPolicy.hasPreciseLocation(context) || !LocationPolicy.hasBackgroundLocation(context)) {\n            disarm(context)"
        ))
        // Builds without Play services hold none.
        val libre = file("libre/java/com/pingolin/track/service/KeepAliveGeofence.kt")
        assertFalse(libre.contains("com.google"))
        assertTrue(libre.contains("fun arm(context: Context, latitude: Double, longitude: Double) = Unit"))

        // Its exits are decided as the keep-alive's are, and let it go where that is let go.
        val receiver = main("receiver/KeepAliveReceiver.kt")
        assertTrue(receiver.contains(
            "if (intent.action != LocationKeepAlive.ACTION_KEEP_ALIVE && intent.action != KeepAliveGeofence.ACTION_EXIT) return"
        ))
        assertTrue(receiver.contains("LocationKeepAlive.unregister(context)\n                KeepAliveGeofence.disarm(context)\n            }\n            Decision.RUNNING"))
        assertTrue(receiver.contains("Decision.NOT_ALLOWED -> {\n                LocationKeepAlive.unregister(context)\n                KeepAliveGeofence.disarm(context)"))
    }

    @Test
    fun `armed from the service's fixes and at boot, let go at a deliberate stop and both sign-outs`() {
        val service = main("service/LocationTrackingService.kt")
        assertTrue(service.contains("            lastKnownFix = location\n            newestOwnFix.offer(location)\n" +
            "            // The keep-alive geofence: around this start's first fix, and again once the phone\n" +
            "            // is out of it. Never per fix.\n            maybeArmGeofence(location)"))
        val arm = body(service, "private fun maybeArmGeofence(")
        assertTrue(arm.contains("if (center != null && !geofenceRearmDue(center.distanceTo(fix), now - geofenceArmedAtElapsed)) return"))
        // Only with location all the time, and armed at the first fix once that is back.
        assertTrue(arm.contains(
            "if (!LocationPolicy.hasPreciseLocation(this) || !LocationPolicy.hasBackgroundLocation(this)) {\n" +
                "            if (geofenceCenter != null) KeepAliveGeofence.disarm(this)\n" +
                "            geofenceCenter = null\n" +
                "            return\n"
        ))
        assertTrue(body(service, "private fun stopTracking()").contains("KeepAliveGeofence.disarm(this)"))

        val boot = main("receiver/BootReceiver.kt")
        assertTrue(boot.contains("sessionManager.getLastHistorySentPosition()?.let {\n                        com.pingolin.track.service.KeepAliveGeofence.arm(context, it.latitude, it.longitude)"))
        // Both sign-outs, and an account an admin hasn't approved, by the one stop.
        assertTrue(main("service/TrackingStop.kt").contains("runCatching { KeepAliveGeofence.disarm(context) }"))
        assertTrue(main("TrackApplication.kt").contains("com.pingolin.track.service.TrackingStop.all(this@TrackApplication)"))
        assertTrue(main("domain/repository/AuthRepository.kt").contains("com.pingolin.track.service.TrackingStop.all(context)"))
    }

    @Test
    fun `location switched off and on again arms it again at the next fix`() {
        // Play services lets it go while location is off (GEOFENCE_NOT_AVAILABLE).
        val service = main("service/LocationTrackingService.kt")
        assertTrue(body(service, "private val locationModeReceiver = ").contains(
            "override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {\n            geofenceCenter = null\n"
        ))
        assertTrue(body(service, "override fun onCreate()").contains(
            "registerReceiver(locationModeReceiver, IntentFilter(android.location.LocationManager.MODE_CHANGED_ACTION).apply {\n" +
                "                addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)\n            })"
        ))
        assertTrue(body(service, "override fun onDestroy()").contains("unregisterReceiver(locationModeReceiver)"))
    }
}
