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
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.PauseIntervals
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * The map's check-in menu offers the screen that sends a position ("With my location…") only
 * while a check-in made now to that circle could carry one, by the same posture rules the send
 * applies (CheckInRepository.positionAllowed). Never a promise the phone will then break.
 */
class CheckInPositionOfferTest {

    private val privacySettingDao = mockk<PrivacySettingDao>()
    private val circleRepository = mockk<CircleRepository>(relaxed = true)
    private var pauses = PauseIntervals.inMemory()

    private fun repository() = CheckInRepository(
        tracksApi = mockk<TrackApi>(relaxed = true),
        checkInDao = mockk<CheckInDao>(relaxed = true),
        sessionManager = mockk<SessionManager>(relaxed = true).also { every { it.getUserId() } returns "me" },
        cryptoManager = mockk<CryptoManager>(relaxed = true),
        privacySettingDao = privacySettingDao,
        circleRepository = circleRepository,
        pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        pauseIntervals = pauses,
        approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    )

    private val plain = PrivacySettingEntity(circleId = "c-1", updatedAt = 0L)

    @Test
    fun `offered while sharing there as normal, with the record of pauses saying so`() = runTest {
        coEvery { privacySettingDao.get("c-1") } returns plain
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = emptyList())

        assertTrue(repository().sendsPositionNow("c-1"))
    }

    @Test
    fun `never while paused, with real-time sharing off, or with no posture to read`() = runTest {
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = emptyList())
        coEvery { privacySettingDao.get("c-1") } returns plain.copy(sharingMode = PrivacySettingEntity.MODE_PAUSED)
        assertFalse(repository().sendsPositionNow("c-1"))

        coEvery { privacySettingDao.get("c-1") } returns plain.copy(shareRealtime = false)
        assertFalse(repository().sendsPositionNow("c-1"))

        coEvery { privacySettingDao.get("c-1") } returns null
        assertFalse("unknown is never a yes", repository().sendsPositionNow("c-1"))
    }

    @Test
    fun `never while the record of pauses puts now inside a pause, whatever the row says`() = runTest {
        coEvery { privacySettingDao.get("c-1") } returns plain
        val now = System.currentTimeMillis()
        pauses.observe("c-1", notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - 60_000L, null)))

        assertFalse(repository().sendsPositionNow("c-1"))
    }

    @Test
    fun `this account's own history always may`() = runTest {
        assertTrue(repository().sendsPositionNow(LocationRepository.SELF_CIRCLE_ID))
    }
}
