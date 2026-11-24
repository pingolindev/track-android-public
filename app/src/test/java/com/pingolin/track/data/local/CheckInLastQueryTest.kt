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

package com.pingolin.track.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.domain.repository.CheckInRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A member's last check-in, as the check-in rules read it from what this phone holds
 * (CheckInDao.lastByUser, CheckInRepository.lastCheckIns): their newest in the circle, any type,
 * and none sealed more than ten minutes ahead of this phone's clock (LiveTime), which would keep
 * their rules quiet until that date.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInLastQueryTest {

    private companion object {
        const val C1 = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val C2 = "c2c2c2c2-0000-4000-8000-0000000000c2"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-00000000b0b0"
        const val MINUTE = 60_000L
    }

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After
    fun tearDown() = db.close()

    private fun checkIn(id: String, circle: String, user: String, at: Long, type: String = "general") = CheckInEntity(
        id = id, circleId = circle, userId = user, type = type, message = null, latitude = null, longitude = null,
        placeId = null, timestamp = at, isSynced = true,
    )

    @Test
    fun `each member's newest check-in in the circle, of any type, none after the bound`() = runTest {
        val dao = db.checkInDao()
        listOf(
            checkIn("1", C1, ALICE, 1_000),
            checkIn("2", C1, ALICE, 5_000, type = "arrived"),
            checkIn("3", C1, BOB, 3_000),
            checkIn("4", C1, ALICE, 9_000_000),
            checkIn("5", C2, BOB, 8_000),
        ).forEach { dao.insert(it) }

        assertEquals(mapOf(ALICE to 5_000L, BOB to 3_000L), dao.lastByUser(C1, maxAt = 6_000).associate { it.userId to it.lastAt })
        assertEquals(mapOf(ALICE to 9_000_000L, BOB to 3_000L), dao.lastByUser(C1, maxAt = 9_000_000).associate { it.userId to it.lastAt })
        assertEquals(emptyList<Any>(), dao.lastByUser("c-none", maxAt = Long.MAX_VALUE))
    }

    @Test
    fun `a check-in sealed more than ten minutes ahead of this phone's clock is no one's last`() = runTest {
        val now = 1_800_000_000_000L
        val dao = db.checkInDao()
        listOf(
            checkIn("1", C1, ALICE, now - 60 * MINUTE),
            checkIn("2", C1, ALICE, now + 11 * MINUTE),
            checkIn("3", C1, BOB, now + 5 * MINUTE),
        ).forEach { dao.insert(it) }
        val repository = CheckInRepository(
            tracksApi = mockk(relaxed = true),
            checkInDao = dao,
            sessionManager = mockk(relaxed = true),
            cryptoManager = mockk(relaxed = true),
            privacySettingDao = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            pendingChanges = mockk(relaxed = true),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )

        assertEquals(mapOf(ALICE to now - 60 * MINUTE, BOB to now + 5 * MINUTE), repository.lastCheckIns(C1, now))
    }
}
