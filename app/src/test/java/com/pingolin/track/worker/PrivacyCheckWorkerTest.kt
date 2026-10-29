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
import android.location.Location
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.util.SessionManager
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The silent `privacy_check` wake: the circle list is read again at once, through the same call as
 * the 5-minute refresh, and nothing is posted. A circle that went from exact to approximate gets
 * one fresh fix, through the normal send, which coarsens it.
 */
class PrivacyCheckWorkerTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun params() = WorkerParameters(
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
        mockk<ForegroundUpdater>(relaxed = true),
    )

    private val context = mockk<Context>(relaxed = true)
    private val session = mockk<SessionManager> {
        every { isLoggedIn() } returns true
        every { isTrackingPaused() } returns false
    }
    private val circles = mockk<CircleRepository>()
    private val circleDao = mockk<CircleDao> {
        coEvery { getAll() } returns listOf("a", "b", "c").map { CircleEntity(it, it, null, 0, 0) }
    }
    private val privacy = mockk<PrivacySettingDao>()
    private val provider = mockk<LocationProvider>()
    private val locations = mockk<LocationRepository>()

    private fun row(circleId: String, mode: String) = PrivacySettingEntity(circleId = circleId, sharingMode = mode, updatedAt = 0L)

    /** The rows before the refresh, then the rows it stored. */
    private fun rows(before: List<PrivacySettingEntity>, after: List<PrivacySettingEntity>) {
        var refreshed = false
        coEvery { circles.refreshCircles() } answers { refreshed = true; Result.success(emptyList()) }
        // The look for a frozen last location to re-seal: its own tests are LastKnownResealTest.
        every { circles.lastKnownReseal } returns mockk(relaxed = true)
        coEvery { privacy.getAllForCircles(any()) } answers { if (refreshed) after else before }
    }

    private fun worker() = PrivacyCheckWorker(context, params(), session, circles, circleDao, privacy, provider, locations)

    @Test
    fun `the wake reads the circle list again and posts nothing`() = runTest {
        rows(before = listOf(row("a", "exact")), after = listOf(row("a", "paused")))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        coVerify(exactly = 1) { circles.refreshCircles() }
        verify(exactly = 0) { context.getSystemService(any<String>()) }
        coVerify(exactly = 0) { provider.currentLocation(any()) }
        coVerify(exactly = 0) { locations.sendEnvelopeLocation(any(), any(), any()) }
    }

    @Test
    fun `a circle that went from exact to approximate gets one fresh fix, through the normal send, and no other does`() = runTest {
        rows(
            before = listOf(row("a", "exact"), row("b", "approximate"), row("c", "exact")),
            after = listOf(row("a", "approximate"), row("b", "approximate"), row("c", "paused")),
        )
        val fix = mockk<Location>(relaxed = true)
        coEvery { provider.currentLocation(any()) } returns fix
        coEvery { locations.sendEnvelopeLocation(any(), any(), any()) } returns Result.success(Unit)

        worker().doWork()

        coVerify(exactly = 1) { locations.sendEnvelopeLocation(listOf("a"), fix, null) }
    }

    @Test
    fun `no fresh fix while the old device-wide Pause Tracking still holds, nor when signed out`() = runTest {
        rows(before = listOf(row("a", "exact")), after = listOf(row("a", "approximate")))
        every { session.isTrackingPaused() } returns true

        worker().doWork()
        coVerify(exactly = 0) { provider.currentLocation(any()) }

        every { session.isLoggedIn() } returns false
        worker().doWork()
        coVerify(exactly = 1) { circles.refreshCircles() }
    }

    @Test
    fun `switched to approximate means shared exactly before and approximately after, neither paused`() {
        val now = System.currentTimeMillis()
        fun r(mode: String, before: String? = null) = PrivacySettingEntity(circleId = "x", sharingMode = mode, modeBeforePause = before, updatedAt = 0L)
        val cases = listOf(
            Triple(r("exact"), r("approximate"), true),
            Triple(r("approximate"), r("approximate"), false),
            Triple(r("exact"), r("exact"), false),
            Triple(r("paused", before = "exact"), r("approximate"), false),
            Triple(r("exact"), r("paused", before = "approximate"), false),
        )
        for ((before, after, switched) in cases) {
            assertEquals(
                "$before -> $after",
                if (switched) setOf("x") else emptySet<String>(),
                PrivacyCheckWorker.switchedToApproximate(mapOf("x" to before), mapOf("x" to after), now),
            )
        }
        assertEquals("a circle new here", emptySet<String>(), PrivacyCheckWorker.switchedToApproximate(emptyMap(), mapOf("x" to r("approximate")), now))
    }
}
