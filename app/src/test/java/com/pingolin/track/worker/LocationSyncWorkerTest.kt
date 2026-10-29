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
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Unit tests for LocationSyncWorker.
 *
 * Tests cover:
 * - doWork returns Result.success when sync succeeds
 * - doWork returns Result.retry on sync failure
 * - Pending queue cleanup, old location cleanup, notification cleanup called
 * - queueMonitor.onSyncCompleted called after sync
 * - Check-in sync invoked
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationSyncWorkerTest {

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var pendingLocationDao: PendingLocationDao

    @MockK
    private lateinit var locationDao: LocationDao

    @MockK
    private lateinit var notificationDao: NotificationDao

    @MockK
    private lateinit var localInactivityEventDao: LocalInactivityEventDao

    @MockK
    private lateinit var queueMonitor: QueueMonitor

    @MockK
    private lateinit var checkInRepository: CheckInRepository

    @MockK
    private lateinit var boundaryRepository: com.pingolin.track.domain.repository.BoundaryRepository

    @MockK
    private lateinit var sessionManager: SessionManager

    private fun newWorkerParameters(): WorkerParameters {
        // Construct via public (internal-API) constructor. All "heavy" dependencies
        // are relaxed MockK objects — the worker doesn't invoke them during doWork()
        // for the code paths we cover.
        return WorkerParameters(
            UUID.randomUUID(),
            Data.EMPTY,
            emptyList<String>(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            mockk<Executor>(relaxed = true),
            kotlinx.coroutines.Dispatchers.Unconfined,
            mockk<TaskExecutor>(relaxed = true),
            mockk<WorkerFactory>(relaxed = true),
            mockk<ProgressUpdater>(relaxed = true),
            mockk<ForegroundUpdater>(relaxed = true)
        )
    }

    private fun newWorker(): LocationSyncWorker {
        return LocationSyncWorker(
            context = context,
            workerParams = newWorkerParameters(),
            locationRepository = locationRepository,
            pendingLocationDao = pendingLocationDao,
            locationDao = locationDao,
            notificationDao = notificationDao,
            localInactivityEventDao = localInactivityEventDao,
            queueMonitor = queueMonitor,
            checkInRepository = checkInRepository,
            boundaryRepository = boundaryRepository,
            sessionManager = sessionManager
        )
    }

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.isTrackingPaused() } returns false
        coEvery { pendingLocationDao.cleanup() } just Runs
        coEvery { locationDao.cleanupOldLocations() } returns 0
        coEvery { notificationDao.deleteOlderThan(any()) } just Runs
        coEvery { checkInRepository.syncPendingCheckIns() } returns Result.success(0)
        coEvery { queueMonitor.onSyncCompleted(any()) } just Runs
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `doWork returns success when syncPendingLocations succeeds`() = runTest {
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(3)

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue("Expected Result.Success, got $result", result is ListenableWorker.Result.Success)
        coVerify { locationRepository.syncPendingLocations() }
        coVerify { pendingLocationDao.cleanup() }
        coVerify { locationDao.cleanupOldLocations() }
    }

    @Test
    fun `the backlog stays on the phone while the old Pause Tracking still holds`() = runTest {
        // Until PauseTrackingMigration has paused every circle, nothing leaves the phone: a flush
        // now would send queued points to circles that aren't paused yet.
        every { sessionManager.isTrackingPaused() } returns true
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(3)

        val result = newWorker().doWork()

        assertTrue("Expected Result.Success, got $result", result is ListenableWorker.Result.Success)
        coVerify(exactly = 0) { locationRepository.syncPendingLocations() }
        coVerify(exactly = 0) { checkInRepository.syncPendingCheckIns() }
    }

    @Test
    fun `doWork returns retry on sync failure`() = runTest {
        coEvery { locationRepository.syncPendingLocations() } returns Result.failure(Exception("net"))

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue("Expected Result.Retry, got $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `doWork invokes queueMonitor onSyncCompleted after sync`() = runTest {
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(0)

        val worker = newWorker()
        worker.doWork()

        coVerify { queueMonitor.onSyncCompleted(any()) }
    }

    @Test
    fun `doWork invokes checkInRepository syncPendingCheckIns`() = runTest {
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(0)

        val worker = newWorker()
        worker.doWork()

        coVerify { checkInRepository.syncPendingCheckIns() }
    }

    @Test
    fun `doWork cleanup failures do not block sync`() = runTest {
        coEvery { pendingLocationDao.cleanup() } throws RuntimeException("boom")
        coEvery { locationDao.cleanupOldLocations() } throws RuntimeException("boom")
        coEvery { notificationDao.deleteOlderThan(any()) } throws RuntimeException("boom")
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(1)

        val worker = newWorker()
        val result = worker.doWork()

        // Even if cleanup steps fail, the worker should still call sync and succeed.
        assertTrue(result is ListenableWorker.Result.Success)
        coVerify { locationRepository.syncPendingLocations() }
    }

    @Test
    fun `doWork returns retry when sync throws runtime exception`() = runTest {
        coEvery { locationRepository.syncPendingLocations() } throws RuntimeException("oops")
        val worker = newWorker()
        val result = worker.doWork()
        assertTrue("Expected Result.Retry, got $result", result is ListenableWorker.Result.Retry)
    }

    @Test
    fun `doWork cleans up old notifications with correct cutoff`() = runTest {
        val cutoffSlot = slot<Int>()
        coEvery { notificationDao.deleteOlderThan(capture(cutoffSlot)) } just Runs
        coEvery { locationRepository.syncPendingLocations() } returns Result.success(0)

        val worker = newWorker()
        worker.doWork()

        // Cutoff should be "now - 90 days" in day buckets.
        val nowDayBucket = (System.currentTimeMillis() / 86400000L).toInt()
        val expected = nowDayBucket - 90
        val actualDayBucket = cutoffSlot.captured
        // Within 1 day of expected (to avoid flakiness near midnight)
        assertTrue(
            "Expected ~$expected day bucket cutoff, got $actualDayBucket",
            kotlin.math.abs(actualDayBucket - expected) <= 1
        )
    }
}
