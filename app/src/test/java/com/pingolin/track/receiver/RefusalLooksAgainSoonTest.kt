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

package com.pingolin.track.receiver

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.service.PushSignals
import com.pingolin.track.util.NotSharingNotice
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * While the session store can't be opened, the watchdog looks again soon, not at its 20-minute
 * tick, so sharing comes back, and the notice that it isn't comes down, soon after the store
 * opens. A boot meeting a refusal says sharing isn't, and the notice comes down wherever the
 * store opens with nothing to share: a person who paused, or signed out, never keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RefusalLooksAgainSoonTest {

    private companion object {
        const val MIN = 60_000L
        const val TICK = "com.pingolin.track.WATCHDOG_TICK"
    }

    private lateinit var context: Context
    private val refused = SecurityException("Cannot initialize secure session storage.", NullPointerException("IKeystoreService"))

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setAutoSchedule(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    /** The session store refusing, or [open]: signed in or not, paused, and whether tracking is wanted. */
    private fun store(open: Boolean, signedIn: Boolean = true, paused: Boolean = false, tracking: Boolean = false) =
        mockk<SessionManager>(relaxed = true) {
            if (open) every { isLoggedIn() } returns signedIn else every { isLoggedIn() } throws refused
            every { isTrackingPaused() } returns paused
            every { shouldBeTracking() } returns tracking
        }

    /** [receiver] as Hilt would hand it [session]: its own inject() then does nothing here. */
    private fun <T : BroadcastReceiver> injected(receiver: T, session: SessionManager): T {
        var type: Class<*>? = receiver.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == "injected" }?.let { it.isAccessible = true; it.setBoolean(receiver, true) }
            type = type.superclass
        }
        when (receiver) {
            is WatchdogAlarmReceiver -> receiver.sessionManager = session
            is BootReceiver -> receiver.sessionManager = session
        }
        return receiver
    }

    private val alarmManager: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private fun watchdog() = shadowOf(alarmManager).scheduledAlarms.single { shadowOf(it.operation).savedIntent.action == TICK }
    private fun armed() = shadowOf(alarmManager).scheduledAlarms.any { shadowOf(it.operation).savedIntent.action == TICK }

    /** How far off the next tick is. */
    private fun gap() = watchdog().triggerAtTime - SystemClock.elapsedRealtime()

    /** The watchdog's tick goes off with the store as [session] says: its alarm gone, the receiver given its intent. */
    private fun tick(session: SessionManager) {
        val alarm = watchdog()
        ShadowSystemClock.advanceBy(Duration.ofMillis(alarm.triggerAtTime - SystemClock.elapsedRealtime()))
        alarmManager.cancel(alarm.operation!!)
        injected(WatchdogAlarmReceiver(), session).onReceive(context, shadowOf(alarm.operation).savedIntent)
    }

    private fun boot(session: SessionManager) =
        injected(BootReceiver(), session).onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    private fun notice() = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(NotSharingNotice.NOTIFICATION_ID)

    private fun startAsked(): Intent? = shadowOf(context as Application).nextStartedService

    @Test
    fun `while the store refuses, the watchdog looks again 1, 2, 4, 8, 16, then every 20 minutes`() {
        WatchdogAlarmReceiver.schedule(context)
        assertEquals(20 * MIN, gap())
        val refusing = store(open = false)
        val gaps = (1..8).map { tick(refusing); gap() }
        assertEquals(listOf(1L, 2, 4, 8, 16, 20, 20, 20).map { it * MIN }, gaps)
        assertNull("nothing starts while it refuses", startAsked())
    }

    @Test
    fun `once the store opens, the 20-minute chain again, and a later refusal starts at a minute again`() {
        WatchdogAlarmReceiver.schedule(context)
        repeat(3) { tick(store(open = false)) }
        assertEquals(4 * MIN, gap())
        tick(store(open = true))
        assertEquals(20 * MIN, gap())
        tick(store(open = false))
        assertEquals(1 * MIN, gap())
    }

    @Test
    fun `a location wake during a refusal never moves a look due sooner later, and brings a later one sooner`() {
        WatchdogAlarmReceiver.schedule(context)
        PushSignals.handOff(context, PushSignals.Action.WAKE_LOCATION)
        assertEquals("the 20-minute tick comes sooner", 1 * MIN, gap())
        val due = watchdog().triggerAtTime
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        PushSignals.handOff(context, PushSignals.Action.WAKE_LOCATION)
        assertEquals("a look due sooner stays where it is", due, watchdog().triggerAtTime)
        // A look remembered but gone (a restart cleared the alarm) is armed again where it was due.
        alarmManager.cancel(watchdog().operation!!)
        PushSignals.handOff(context, PushSignals.Action.WAKE_LOCATION)
        assertEquals(due, watchdog().triggerAtTime)
    }

    @Test
    fun `saying sharing stopped arms a look within a minute, never later than one due`() {
        WatchdogAlarmReceiver.schedule(context)
        NotSharingNotice.show(context)
        assertNotNull(notice())
        assertEquals(1 * MIN, gap())
        val due = watchdog().triggerAtTime
        ShadowSystemClock.advanceBy(Duration.ofSeconds(40))
        NotSharingNotice.show(context)
        assertEquals(due, watchdog().triggerAtTime)
    }

    @Test
    fun `with notifications refused, saying sharing stopped still arms the look within a minute`() {
        // POST_NOTIFICATIONS not granted (13+): posting throws, and the look is armed all the same.
        val refusedNotices = mockk<NotificationManager>(relaxed = true) {
            every { notify(any(), any(), any()) } throws SecurityException("Permission denial: POST_NOTIFICATIONS")
        }
        val noPosting = object : android.content.ContextWrapper(context) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) refusedNotices else super.getSystemService(name)
        }
        WatchdogAlarmReceiver.schedule(context)
        NotSharingNotice.show(noPosting)
        io.mockk.verify { refusedNotices.notify(any(), NotSharingNotice.NOTIFICATION_ID, any()) }
        assertNull("not posted", notice())
        assertEquals(1 * MIN, gap())
    }

    @Test
    fun `a refusal at boot says sharing isn't, from no store, looks again in a minute, and starts nothing`() {
        boot(store(open = false))
        assertNotNull(notice())
        assertEquals(1 * MIN, gap())
        assertNull(startAsked())
    }

    @Test
    fun `once the store opens with sharing paused, or signed out, the notice is down and nothing starts`() {
        for (open in listOf(store(open = true, paused = true), store(open = true, signedIn = false))) {
            boot(store(open = false))
            assertNotNull(notice())
            tick(open)
            assertNull("down", notice())
            assertNull("nothing started", startAsked())
        }
        // Signed out, the chain stops there: sign-in arms it again.
        assertFalse(armed())
    }

    @Test
    fun `once the store opens with tracking off, the notice is down - with it wanted, the start takes it down, and one refused leaves it`() {
        boot(store(open = false))
        tick(store(open = true, tracking = false))
        assertNull(notice())
        assertNull(startAsked())

        boot(store(open = false))
        tick(store(open = true, tracking = true))
        assertEquals(LocationTrackingService::class.java.name, startAsked()?.component?.className)
        assertNotNull("the service takes it down once it starts tracking; a start the platform refuses leaves it up", notice())
    }

    @Test
    fun `a boot with the store open takes down a notice left from before, and starts tracking`() {
        NotSharingNotice.show(context)
        boot(store(open = true))
        assertNull(notice())
        assertEquals(LocationTrackingService::class.java.name, startAsked()?.component?.className)
        assertEquals("the 20-minute chain", 20 * MIN, gap())
    }
}
