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

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Location sharing has stopped" says why when the cause is "Allow all the
 * time": the watchdog's own verdicts cannot tell, the permissions can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TrackingHealthNotifierTest {

    private lateinit var context: Context
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun shownText() = shadowOf(manager).allNotifications.single()
        .extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `only while-using granted - the notification names Allow all the time`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(context as android.app.Application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        TrackingHealthNotifier.show(context)

        assertEquals(
            "Location access isn't set to \"Allow all the time\". Tap to allow it, and sharing restarts by itself.",
            shownText(),
        )
    }

    @Test
    fun `any other cause keeps the general words`() {
        shadowOf(context as android.app.Application).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )

        TrackingHealthNotifier.show(context)

        // It names the button, and promises nothing a pause would make untrue.
        assertEquals("Track tried to restart it and couldn't. Tap “Try again”, or open Track.", shownText())
    }

    /**
     * One tap on the notification restarts sharing right there, as opening the app does (the plain
     * ACTION_START, a foreground start a notification action is allowed even from the background), and
     * nothing else: no pause lifted, no setting changed.
     */
    @Test
    fun `one action restarts sharing with the plain start, explicitly and immutably`() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        TrackingHealthNotifier.show(context)

        val notification = shadowOf(manager).allNotifications.single()
        val action = notification.actions.single()
        // Neutral: true for a paused person too, and when the restart fails again.
        assertEquals("Try again", action.title.toString())
        val pending = shadowOf(action.actionIntent)
        org.junit.Assert.assertTrue("a foreground-service start", pending.isForegroundServiceIntent)
        org.junit.Assert.assertTrue(pending.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = pending.savedIntent
        assertEquals(com.pingolin.track.service.LocationTrackingService.ACTION_START, intent.action)
        assertEquals(com.pingolin.track.service.LocationTrackingService::class.java.name, intent.component?.className)
        org.junit.Assert.assertTrue("nothing else rides along", intent.extras == null || intent.extras!!.isEmpty)
    }

    @Test
    fun `it is shown only once the automatic restart failed twice`() {
        assertEquals(2, TrackingHealth.ESCALATE_AFTER_FAILURES)
        val watchdog = java.io.File("src/main/java/com/pingolin/track/receiver/WatchdogAlarmReceiver.kt").readText()
        assertEquals(
            2,
            Regex("""if \(failures(Now)? >= TrackingHealth\.ESCALATE_AFTER_FAILURES\) \{\s*TrackingHealthNotifier\.show\(context\)""").findAll(watchdog).count(),
        )
        assertEquals("the watchdog is its only caller", 2, Regex("""TrackingHealthNotifier\.show\(""").findAll(watchdog).count())
    }

    @Test
    fun `its start never outlives a session - gone at both sign-outs, and a start for no session starts nothing`() {
        // Both sign-outs take every notification down, this one with its action.
        val main = "src/main/java/com/pingolin/track/"
        org.junit.Assert.assertTrue(java.io.File(main + "domain/repository/AuthRepository.kt").readText()
            .contains("runCatching { NotificationManagerCompat.from(context).cancelAll() }"))
        org.junit.Assert.assertTrue(java.io.File(main + "TrackApplication.kt").readText()
            .contains("runCatching { NotificationManagerCompat.from(this@TrackApplication).cancelAll() }"))
        // And a start for no session, or for an account still under review, is refused
        // before tracking is switched on.
        val service = java.io.File(main + "service/LocationTrackingService.kt").readText()
        val start = service.substring(service.indexOf("            ACTION_START -> {"))
        val refusal = start.indexOf(
            "                if (!sessionManager.isLoggedIn() || sessionManager.isRegistrationUnapproved()) {\n" +
                "                    Log.i(TAG, \"ACTION_START: not signed in, or still under review; ignoring start\")\n" +
                "                    journalStop(TrackingStopReason.NOT_LOGGED_IN)\n" +
                "                    stopSelf()\n" +
                "                    return START_NOT_STICKY\n"
        )
        org.junit.Assert.assertTrue("refused", refusal >= 0)
        org.junit.Assert.assertTrue("before it is switched on", refusal < start.indexOf("sessionManager.setTrackingEnabled(true)"))
    }
}
