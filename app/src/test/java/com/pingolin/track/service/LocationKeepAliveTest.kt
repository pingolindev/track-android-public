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

import android.location.LocationManager
import com.pingolin.track.receiver.KeepAliveReceiver
import com.pingolin.track.receiver.KeepAliveReceiver.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The keep-alive: a low-power location request delivered to a PendingIntent brings the location
 * service back after it dies, inside the platform's allowlist for that delivery, only while
 * sharing is wanted, and never as a second tracker.
 */
class LocationKeepAliveTest {

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    /** [name]'s body in [file], up to its closing brace at [indent]. */
    private fun body(file: String, name: String, indent: String = "    "): String =
        file.substring(file.indexOf(name)).let { it.substring(0, it.indexOf("\n$indent}\n")) }

    @Test
    fun `never more often than the service's own cadence, and never GPS`() {
        assertEquals(15 * 60_000L, LocationKeepAlive.intervalMsFor(batterySaver = false, saverIntervalMinutes = 60))
        assertEquals("a 5-minute saver batch", 15 * 60_000L, LocationKeepAlive.intervalMsFor(true, 5))
        assertEquals(30 * 60_000L, LocationKeepAlive.intervalMsFor(true, 30))
        assertEquals("an hourly saver gets no extra wakes", 60 * 60_000L, LocationKeepAlive.intervalMsFor(true, 60))

        assertEquals(LocationManager.FUSED_PROVIDER, LocationKeepAlive.providerFor(hasFused = true, networkEnabled = true))
        assertEquals(LocationManager.NETWORK_PROVIDER, LocationKeepAlive.providerFor(hasFused = false, networkEnabled = true))
        assertEquals(LocationManager.PASSIVE_PROVIDER, LocationKeepAlive.providerFor(hasFused = false, networkEnabled = false))
        val keepAlive = source("service/LocationKeepAlive.kt")
        assertTrue(keepAlive.contains(".setQuality(LocationRequest.QUALITY_LOW_POWER)"))
        assertFalse(keepAlive.contains("GPS_PROVIDER"))
        assertFalse(keepAlive.contains("QUALITY_HIGH_ACCURACY"))
    }

    @Test
    fun `a delivery asks the store first, then the watchdog's one rule, and starts only what is wanted and allowed`() {
        fun decide(refused: Boolean = false, wanted: Boolean = true, running: Boolean = false, allowed: Boolean = true) =
            KeepAliveReceiver.decide({ refused }, { wanted }, { running }, { allowed })

        assertEquals(Decision.START, decide())
        assertEquals("the store can't be opened: no start, the watchdog looks soon", Decision.LOOK_SOON, decide(refused = true))
        assertEquals("signed out, or the old pause", Decision.NOT_WANTED, decide(wanted = false))
        assertEquals(Decision.RUNNING, decide(running = true))
        assertEquals("not allowed all the time", Decision.NOT_ALLOWED, decide(allowed = false))
        // The order: nothing else is asked of a store that refused, nor of a sharing that isn't wanted.
        assertEquals(
            Decision.LOOK_SOON,
            KeepAliveReceiver.decide({ true }, { error("asked") }, { error("asked") }, { error("asked") }),
        )
        assertEquals(
            Decision.NOT_WANTED,
            KeepAliveReceiver.decide({ false }, { false }, { error("asked") }, { error("asked") }),
        )
    }

    @Test
    fun `the receiver decides with the one rule, never reads or sends the delivered location, and starts plainly`() {
        val receiver = source("receiver/KeepAliveReceiver.kt")
        val onReceive = body(receiver, "override fun onReceive(")
        assertTrue(onReceive.contains("storeRefused = { sessionManager.storeRefusal() != null }"))
        assertTrue(onReceive.contains("wanted = { sessionManager.shouldBeTracking() }"))
        // No copy of the rule: none of its parts read here on their own.
        for (part in listOf("isLoggedIn()", "isTrackingEnabled()", "isTrackingPaused()", "setTracking", "Paused(")) {
            assertFalse(part, onReceive.contains(part))
        }
        // The location the platform delivers is never read, sent or kept.
        for (read in listOf("KEY_LOCATION_CHANGED", "getParcelableExtra", "LocationResult", "sendLocation", "locationRepository")) {
            assertFalse(read, receiver.contains(read))
        }
        assertTrue(onReceive.contains(".apply { action = LocationTrackingService.ACTION_START }"))
        assertTrue(onReceive.contains("Decision.LOOK_SOON -> WatchdogAlarmReceiver.scheduleSoon(context)"))
        assertTrue(onReceive.contains("NotSharingNotice.clear(context)\n                LocationKeepAlive.unregister(context)"))
        assertTrue(onReceive.contains("Decision.NOT_ALLOWED -> {\n                LocationKeepAlive.unregister(context)"))

        // Explicit, to this app's own unexported receiver.
        val keepAlive = source("service/LocationKeepAlive.kt")
        assertTrue(keepAlive.contains("Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_KEEP_ALIVE)"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substring(manifest.indexOf("android:name=\".receiver.KeepAliveReceiver\""))
            .let { it.substring(0, it.indexOf("/>")) }
        assertTrue(entry.contains("android:exported=\"false\""))
        // Held only with precise location all the time; let go otherwise.
        val register = body(keepAlive, "fun register(")
        assertTrue(register.contains("if (!LocationPolicy.hasPreciseLocation(context) || !LocationPolicy.hasBackgroundLocation(context)) {\n            unregister(context)"))
    }

    @Test
    fun `held at the service's start, boot, update and app start - let go at a deliberate stop and both sign-outs`() {
        val service = source("service/LocationTrackingService.kt")
        // Where only a start that passed every gate arrives, at the cadence in force...
        assertTrue(body(service, "private fun startTracking(").contains(
            "LocationKeepAlive.register(this, batterySaver, sessionManager.getBatterySaverInterval())\n        if (isTracking) {"
        ))
        // ...so a start that is refused (signed out, paused, the store) or a stop never holds it.
        assertFalse(body(service, "override fun onStartCommand(").contains("LocationKeepAlive.register("))
        assertTrue(body(service, "private fun reloadDistanceSettings()").contains("LocationKeepAlive.register(this, saver, sessionManager.getBatterySaverInterval())"))
        assertTrue(body(service, "private fun stopTracking()").contains("LocationKeepAlive.unregister(this)"))

        val boot = source("receiver/BootReceiver.kt")
        assertTrue(boot.contains("if (sessionManager.shouldBeTracking()) {\n                    com.pingolin.track.service.LocationKeepAlive.register("))
        val app = source("TrackApplication.kt")
        assertTrue(app.contains("if (session.storeRefusal() == null && session.shouldBeTracking()) {\n                com.pingolin.track.service.LocationKeepAlive.register("))
        // Both sign-outs (the forced one here, the chosen one in clearLocalData) and an account
        // an admin hasn't approved let it go by the one stop that holds it.
        assertTrue(source("service/TrackingStop.kt").contains("runCatching { LocationKeepAlive.unregister(context) }"))
        assertTrue(app.contains("com.pingolin.track.service.TrackingStop.all(this@TrackApplication)"))
        val auth = source("domain/repository/AuthRepository.kt")
        assertTrue(auth.contains("com.pingolin.track.service.TrackingStop.all(context)"))
    }
}
