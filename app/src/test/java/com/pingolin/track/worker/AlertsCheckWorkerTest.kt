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
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.TrustHarness
import com.pingolin.track.util.MonitoringManager
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The silent `alerts_check` wake-up hands over access in the background: whatever the
 * access token's age, nobody has to open the app for a newcomer to be let in.
 *
 * Driven through the real worker and the real CircleRepository (TrustHarness): the circle
 * list says someone is owed the circle, the member list says who, and what goes on the
 * wire is checked. The refresh itself (an expired token answered with 401, then refreshed
 * and retried) is BackgroundRefreshTest's.
 */
class AlertsCheckWorkerTest {

    @After
    fun tearDown() = clearAllMocks()

    private val monitoring = mockk<MonitoringManager>(relaxed = true)
    private val reload = mockk<com.pingolin.track.domain.repository.AlertRulesReload>(relaxed = true)

    private val nowSecs get() = System.currentTimeMillis() / 1000

    /** A JWT whose only readable claim is `exp`, as JwtExpiry reads it. */
    private fun jwt(expSeconds: Long): String {
        fun b64(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        return "${b64("""{"alg":"none"}""")}.${b64("""{"sub":"me","exp":$expSeconds}""")}.sig"
    }

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

    /** A plain member's phone of "me" in a circle where the owner invited dan, who has no copy yet. */
    private fun TestScope.newcomerOwed(): TrustHarness {
        val h = TrustHarness(selfRole = "member").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner")
        }
        h.circle = h.circle.copy(isOwner = false, keyRotationNeeded = false)
        coEvery { h.circleDao.getAll() } answers { listOf(h.circle) }
        coEvery { h.circleDao.insertAll(any()) } just Runs
        coEvery { h.tracksApi.getCircles() } returns Response.success(
            ApiResponse(
                success = true,
                data = PaginatedResponse(
                    items = listOf(
                        CircleResponse(
                            id = "c-1",
                            encryptedName = EncryptedPayload(nonce = "nn", ciphertext = "cc"),
                            encryptedKey = EncryptedPayload(nonce = "kn", ciphertext = "kc"),
                            ephemeralPublicKey = "epk",
                            memberCount = 3,
                            isOwner = false,
                            dayBucket = 20000,
                            keyRotationNeeded = false,
                            keyVersion = h.circleVersion,
                            memberKeyVersion = h.circleVersion,
                            deliveryNeeded = true,
                        )
                    ),
                    hasMore = false,
                ),
                error = null,
            )
        )
        val invite = h.invitation("dan", by = "owner")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("dan", invite, keyVersion = null)))
        return h
    }

    private fun worker(h: TrustHarness): AlertsCheckWorker {
        coEvery { monitoring.batteryAlertCircleIds() } returns emptySet()
        coEvery { monitoring.inactivityCircleIds() } returns emptySet()
        coEvery { monitoring.fetchCircleSelfAlerts() } returns emptyList()
        return AlertsCheckWorker(
            context = mockk<Context>(relaxed = true),
            params = params(),
            monitoringManager = monitoring,
            locationRepository = mockk<LocationRepository>(relaxed = true),
            sessionManager = h.sessionManager,
            circleRepository = h.repository,
            alertRulesReload = reload,
        )
    }

    @Test
    fun `the wake-up fetches the rules it judges before it reads which circles to judge`() = runTest {
        // Android raised low-battery and inactivity alerts only from rows the Alerts screen had
        // loaded: after a sign-in, an install or a database made again there were none, and a
        // rule made on another device never arrived.
        val h = newcomerOwed()
        every { h.sessionManager.getAccessToken() } returns jwt(nowSecs + 3600)
        every { h.sessionManager.getRefreshToken() } returns "refresh-1"
        h.deliveriesAccepted()

        worker(h).doWork()

        io.mockk.coVerifyOrder {
            reload.reloadIfStale(force = true)
            monitoring.batteryAlertCircleIds()
            monitoring.inactivityCircleIds()
        }
    }

    @Test
    fun `a wake-up hours after the last use hands the newcomer the circle, expired token and all`() = runTest {
        val h = newcomerOwed()
        // Idle for hours: the access token expired long ago.
        every { h.sessionManager.getAccessToken() } returns jwt(nowSecs - 3 * 3600)
        every { h.sessionManager.getRefreshToken() } returns "refresh-1"
        val delivered = h.deliveriesAccepted()

        val result = worker(h).doWork()

        assertTrue("$result", result is ListenableWorker.Result.Success)
        assertEquals(h.circleVersion, delivered.getValue("dan").keyVersion)
    }

    @Test
    fun `member_joined's queued work hands the newcomer the circle, in the background`() = runTest {
        val h = newcomerOwed()
        every { h.sessionManager.getAccessToken() } returns jwt(nowSecs - 3 * 3600)
        val delivered = h.deliveriesAccepted()
        val alertHandler = com.pingolin.track.util.AlertHandler(
            mockk(relaxed = true), h.repository, mockk(relaxed = true), h.sessionManager,
            mockk(relaxed = true), dagger.Lazy { mockk(relaxed = true) }, dagger.Lazy { mockk(relaxed = true) },
        )
        val work = MemberJoinedWorker(
            mockk<Context>(relaxed = true),
            WorkerParameters(
                UUID.randomUUID(),
                Data.Builder()
                    .putString(MemberJoinedWorker.KEY_CIRCLE_ID, "c-1")
                    .putBoolean(MemberJoinedWorker.KEY_NEEDS_DELIVERY, true)
                    .build(),
                emptyList<String>(), WorkerParameters.RuntimeExtras(), 0, 0,
                mockk<Executor>(relaxed = true), kotlinx.coroutines.Dispatchers.Unconfined,
                mockk<TaskExecutor>(relaxed = true), mockk<WorkerFactory>(relaxed = true),
                mockk<ProgressUpdater>(relaxed = true), mockk<ForegroundUpdater>(relaxed = true),
            ),
            alertHandler,
            h.sessionManager,
        )

        assertTrue(work.doWork() is ListenableWorker.Result.Success)
        assertEquals(h.circleVersion, delivered.getValue("dan").keyVersion)
    }

    @Test
    fun `a wake-up on a fresh token hands the newcomer the circle too`() = runTest {
        val h = newcomerOwed()
        every { h.sessionManager.getAccessToken() } returns jwt(nowSecs + 3600)
        every { h.sessionManager.getRefreshToken() } returns "refresh-1"
        val delivered = h.deliveriesAccepted()

        worker(h).doWork()

        assertEquals(h.circleVersion, delivered.getValue("dan").keyVersion)
    }
}
