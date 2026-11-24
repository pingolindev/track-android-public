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

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pingolin.track.worker.CheckInZoneWorker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Follow the phone's time zone: when the phone's zone changes, and when the app
 * starts or comes back, this account's check-in rules that follow the phone are moved, once online
 * (CheckInZoneWorker, CheckInZoneFollower).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TimeZoneChangedReceiverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    private fun scheduled() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CheckInZoneWorker.WORK_NAME).get()

    @Test
    fun `the phone's zone changing schedules the move, once online, and nothing else does here`() {
        TimeZoneChangedReceiver().onReceive(context, Intent(Intent.ACTION_LOCALE_CHANGED))
        assertEquals(emptyList<Any>(), scheduled())

        TimeZoneChangedReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        val work = scheduled().single()
        assertEquals(NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun `the receiver is declared for the zone change, and the app's return kicks it too`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val receiver = manifest.substringAfter("android:name=\".receiver.TimeZoneChangedReceiver\"").substringBefore("</receiver>")
        assertTrue(receiver.contains("<action android:name=\"android.intent.action.TIMEZONE_CHANGED\" />"))
        assertTrue(receiver.contains("android:exported=\"false\""))
        val activity = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val resume = activity.substringAfter("override fun onResume()").substringBefore("override fun ")
        assertTrue(resume.contains("if (sessionManager.isLoggedIn()) com.pingolin.track.worker.CheckInZoneWorker.kick(this)"))
    }
}
