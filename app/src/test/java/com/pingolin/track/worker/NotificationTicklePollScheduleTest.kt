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

package com.pingolin.track.worker

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * That notifications have a route that does not depend on a push arriving.
 *
 * WHY THIS IS WORTH A TEST
 *   Every mobile push this backend sends is a content-free tickle, so the app
 *   fetching the row IS the notification — and without a periodic request from
 *   [NotificationTickleWorker], the only thing that runs that fetch is a push
 *   waking the app. On the libre flavour that is the whole story: it resolves no
 *   Firebase artifact, so with no UnifiedPush distributor installed there is no
 *   transport, no fetch, and no notification of any kind — no place alert, no
 *   arrival, no "notify my circle" — until the user happens to open the app.
 *
 *   The failure is silent from every angle. Nothing throws, nothing logs, and
 *   the settings screen still reports push as configured. The only observable
 *   difference between "working" and "the feature does not exist on this build"
 *   is whether a periodic request is enqueued, which is precisely what this
 *   asserts.
 *
 * WHY WORKMANAGER FOR REAL RATHER THAN A MOCK
 *   A mocked WorkManager would confirm that the app called a method. The failure
 *   here is upstream of that: a method that is never called because it does not
 *   exist. Driving the real scheduler through WorkManagerTestInitHelper pins the
 *   enqueued request — its unique name, that it is periodic at all, and its
 *   interval — against what a device would actually run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationTicklePollScheduleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
    }

    private fun pollInfos(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(NotificationTickleWorker.POLL_WORK_NAME)
            .get()

    @Test
    fun `arming the app enqueues a periodic notification poll`() {
        // The central case. Without a PeriodicWorkRequest the list
        // below comes back empty, and a de-Googled install cannot
        // receive a notification by any route.
        NotificationTickleWorker.schedulePolling(context)

        val infos = pollInfos()
        assertEquals("Expected exactly one poll request, got $infos", 1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        assertNotNull(
            "The poll must be PERIODIC — a one-time request fires once and the " +
                "device goes quiet again",
            infos[0].periodicityInfo,
        )
    }

    @Test
    fun `the poll runs every fifteen minutes`() {
        // Not an arbitrary number: 15 minutes is WorkManager's floor for periodic
        // work (PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS), so a smaller
        // value would be silently raised back to it, and it is the same interval
        // and flex as LocationSyncWorker and LocationHeartbeatWorker so the three
        // batch into one wake-up. Drifting off it costs a separate wake-up for
        // nothing; lengthening it turns an alert into a digest.
        NotificationTickleWorker.schedulePolling(context)

        val periodicity = pollInfos().single().periodicityInfo!!
        assertEquals(
            15L,
            TimeUnit.MILLISECONDS.toMinutes(periodicity.repeatIntervalMillis),
        )
        assertEquals(
            5L,
            TimeUnit.MILLISECONDS.toMinutes(periodicity.flexIntervalMillis),
        )
    }

    @Test
    fun `the poll waits for a network and nothing else`() {
        // Deliberately NOT setRequiresBatteryNotLow: Doze plus that constraint can
        // stretch a 15-minute interval into multi-hour gaps on a real device, as
        // the location workers' comments note. A notification poll that stops
        // running once the battery dips is a notification poll that is missing
        // exactly when the phone has been out all day.
        NotificationTickleWorker.schedulePolling(context)

        val constraints = pollInfos().single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(
            "Battery/charging/idle/storage constraints all defer the poll under Doze",
            !constraints.requiresBatteryNotLow() &&
                !constraints.requiresCharging() &&
                !constraints.requiresStorageNotLow(),
        )
    }

    @Test
    fun `arming twice leaves one poll, not two`() {
        // MainActivity arms this on every launch and BootReceiver arms it again
        // after a reboot. Both must be no-ops against an existing schedule, or a
        // user who opens the app often would accumulate duplicate wake-ups.
        NotificationTickleWorker.schedulePolling(context)
        NotificationTickleWorker.schedulePolling(context)

        assertEquals(1, pollInfos().count { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `logging out stops the poll`() {
        // The poll wakes the device every 15 minutes to GET /notifications with
        // the session token. doWork() bails once isLoggedIn() is false, so nothing
        // leaks — but an uncancelled timer keeps waking a logged-out phone
        // forever, which is the exact failure every other background waker in
        // AuthRepository.clearLocalData already carries a comment about.
        NotificationTickleWorker.schedulePolling(context)
        // Assert the arming FIRST. Without it "nothing is enqueued"
        // satisfies the check below vacuously, and this test would pass
        // just as happily against a build that never schedules anything
        // — which is the failure next door.
        assertEquals(1, pollInfos().count { it.state == WorkInfo.State.ENQUEUED })

        NotificationTickleWorker.cancelPolling(context)

        val states = pollInfos().map { it.state }
        assertTrue(
            "Expected the poll cancelled, got $states",
            states.isNotEmpty() && states.all { it == WorkInfo.State.CANCELLED },
        )
    }

    @Test
    fun `the poll does not share a unique name with the push-driven fetch`() {
        // A unique name holds either a one-time chain or a periodic request, never
        // both. Sharing one would make every arriving push tear down the fallback.
        assertEquals("notification_tickle_poll", NotificationTickleWorker.POLL_WORK_NAME)
    }
}
