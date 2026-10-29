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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * Something the user writes to a circle — here a check-in, the same gate every other
 * write passes — is refused, not sealed, while this device lacks the circle's current
 * key.
 */
class CheckInCurrentKeyTest {

    private val circleRepository = mockk<CircleRepository>()
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val checkInDao = mockk<CheckInDao>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true).also {
        every { it.getUserId() } returns "me"
        every { it.getDeviceId() } returns "device-1"
    }

    private val repository = CheckInRepository(
        tracksApi = tracksApi,
        checkInDao = checkInDao,
        sessionManager = sessionManager,
        cryptoManager = cryptoManager,
        privacySettingDao = mockk(relaxed = true),
        circleRepository = circleRepository,
        pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    )

    @Test
    fun `a check-in to a circle whose key moved on without this device is refused, and nothing is sealed`() = runTest {
        coEvery { circleRepository.requireCurrentKey("c-1") } throws KeyNotCurrentException()

        val result = repository.createCheckIn("c-1", CheckInRepository.TYPE_SAFE_ARRIVAL, message = "home")

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.createCheckIn(any()) }
        // Not queued either: a queued check-in would be sealed at the next sync.
        coVerify(exactly = 0) { checkInDao.insert(any()) }
    }

    @Test
    fun `a queued check-in waits until this device holds the current key`() = runTest {
        coEvery { checkInDao.getPendingSync() } returns listOf(
            CheckInEntity("q-1", "c-1", "me", CheckInRepository.TYPE_SAFE_ARRIVAL, "home", null, null, null, 1_000L),
        )
        coEvery { circleRepository.holdsCurrentKey("c-1") } returns false

        repository.syncPendingCheckIns()

        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.createCheckIn(any()) }
        coVerify(exactly = 0) { checkInDao.markSynced(any()) }
    }
}
