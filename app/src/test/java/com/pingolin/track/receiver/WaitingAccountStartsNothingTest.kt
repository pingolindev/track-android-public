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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.SessionManager
import com.pingolin.track.worker.LocationHeartbeatWorker
import com.pingolin.track.worker.LocationSyncWorker
import com.pingolin.track.worker.NotificationTickleWorker
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A boot or an update restarts location sharing for whoever is signed in, except an assisted-lane
 * account an admin hasn't approved yet: the service would run at full accuracy for an account whose
 * every call Track refuses, and that may never have seen the app. Such an account starts nothing; the
 * app starts it all once it opens on the approval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WaitingAccountStartsNothingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setAutoSchedule(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun session(waiting: Boolean, rejected: Boolean = false) = mockk<SessionManager>(relaxed = true) {
        every { isLoggedIn() } returns true
        every { isRegistrationPending() } returns waiting
        every { isRegistrationRejected() } returns rejected
        every { isRegistrationUnapproved() } returns (waiting || rejected)
    }

    /** BootReceiver as Hilt would hand it [session]: its own inject() then does nothing here. */
    private fun receive(action: String, session: SessionManager) {
        val receiver = BootReceiver()
        var type: Class<*>? = receiver.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == "injected" }?.let { it.isAccessible = true; it.setBoolean(receiver, true) }
            type = type.superclass
        }
        receiver.sessionManager = session
        (receiver as BroadcastReceiver).onReceive(context, Intent(action))
    }

    private fun startAsked(): Intent? = shadowOf(context as Application).nextStartedService
    private fun watchdogArmed() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
        shadowOf(it.operation).savedIntent.action == "com.pingolin.track.WATCHDOG_TICK"
    }
    private fun scheduled(worker: Class<*>) = WorkManager.getInstance(context).getWorkInfosByTag(worker.name).get().isNotEmpty()

    @Test
    fun `a boot or an update with a waiting account starts no sharing, no job and no watchdog`() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            receive(action, session(waiting = true))

            assertNull("$action: no location service", startAsked())
            assertFalse("$action: no watchdog", watchdogArmed())
            for (worker in listOf(LocationSyncWorker::class.java, LocationHeartbeatWorker::class.java, NotificationTickleWorker::class.java)) {
                assertFalse("$action: ${worker.simpleName} not scheduled", scheduled(worker))
            }
        }
    }

    @Test
    fun `a boot or an update with a turned-down account starts nothing either`() {
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            receive(action, session(waiting = false, rejected = true))

            assertNull("$action: no location service", startAsked())
            assertFalse("$action: no watchdog", watchdogArmed())
            for (worker in listOf(LocationSyncWorker::class.java, LocationHeartbeatWorker::class.java, NotificationTickleWorker::class.java)) {
                assertFalse("$action: ${worker.simpleName} not scheduled", scheduled(worker))
            }
        }
    }

    @Test
    fun `an approved account's boot restores sharing`() {
        receive(Intent.ACTION_BOOT_COMPLETED, session(waiting = false))

        val start = startAsked()
        assertEquals(LocationTrackingService::class.java.name, start?.component?.className)
        assertEquals(LocationTrackingService.ACTION_START, start?.action)
        assertTrue(watchdogArmed())
        assertTrue(scheduled(LocationSyncWorker::class.java))
        assertTrue(scheduled(LocationHeartbeatWorker::class.java))
    }
}
