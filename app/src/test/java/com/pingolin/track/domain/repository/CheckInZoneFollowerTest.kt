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

import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Phone's time zone" (CheckInZoneFollower): this account's own scheduled check-in rules about
 * itself that follow the phone move to the phone's zone when it differs, on or off, by the zone
 * alone; nothing else does. A failure worth another try says so; a refusal isn't tried again; a
 * phone zone the server wouldn't take moves nothing.
 */
class CheckInZoneFollowerTest {

    private companion object {
        const val ME = "5e1f0000-0000-4000-8000-00000000000e"
        const val OTHER = "a11ce000-0000-4000-8000-00000000a11c"
    }

    private val dao = mockk<InactivityAlertDao>()
    private val repository = mockk<InactivityAlertRepository>()
    private val session = mockk<SessionManager>()
    private var stored = listOf<InactivityAlertEntity>()

    private fun follower(): CheckInZoneFollower {
        every { session.getUserId() } returns ME
        coEvery { dao.getAll() } answers { stored }
        return CheckInZoneFollower(dao, repository, session)
    }

    private fun rule(
        id: String,
        follows: Boolean = true,
        tz: String = "Europe/Berlin",
        self: Boolean = true,
        mode: String? = CheckInModes.SCHEDULED,
        owner: String = ME,
        active: Boolean = true,
        watches: String = InactivityWatches.CHECK_IN,
    ) = InactivityAlertEntity(
        id = id, userId = owner, circleId = "c-1", targetUserId = owner, timeoutHours = 1, isSelfAlert = self, isActive = active,
        dayBucket = 0, watches = watches, armedAt = 0L, checkInMode = mode, scheduleDays = CheckInSchedule.EVERY_DAY,
        scheduleDueMinute = 21 * 60, scheduleTz = tz, scheduleTzFollowsPhone = follows,
    )

    @Test
    fun `only this account's own scheduled rules about itself that follow the phone, in another zone`() {
        val rules = listOf(
            rule("moves"),
            rule("off-moves-too", active = false),
            rule("there-already", tz = "Asia/Tokyo"),
            rule("fixed-zone", follows = false),
            rule("about-someone", self = false),
            rule("one-time", mode = CheckInModes.ONCE),
            rule("location", watches = InactivityWatches.LOCATION),
            rule("not-mine", owner = OTHER),
        )
        assertEquals(
            listOf("moves", "off-moves-too"),
            CheckInZoneFollower.toMove(rules, ME.uppercase(), "Asia/Tokyo").map { it.id },
        )
    }

    @Test
    fun `each moves by its zone alone, and then it is done`() = runTest {
        stored = listOf(rule("a"), rule("b", tz = "Asia/Tokyo"), rule("c", follows = false))
        coEvery { repository.updateScheduleZone(any(), any()) } returns Result.success(Unit)

        assertTrue(follower().follow("Asia/Tokyo"))

        coVerify(exactly = 1) { repository.updateScheduleZone("a", "Asia/Tokyo") }
        coVerify(exactly = 1) { repository.updateScheduleZone(any(), any()) }
    }

    @Test
    fun `no answer, or the server's own failure, is tried again - a refusal isn't`() = runTest {
        stored = listOf(rule("a"))
        val follower = follower()

        coEvery { repository.updateScheduleZone(any(), any()) } returns Result.failure(java.io.IOException("offline"))
        assertFalse(follower.follow("Asia/Tokyo"))
        coEvery { repository.updateScheduleZone(any(), any()) } returns Result.failure(RefusedException("x", 503, null))
        assertFalse(follower.follow("Asia/Tokyo"))
        coEvery { repository.updateScheduleZone(any(), any()) } returns Result.failure(RefusedException("x", 400, "bad_request"))
        assertTrue(follower.follow("Asia/Tokyo"))
    }

    @Test
    fun `a phone zone the server wouldn't take, or nobody signed in, moves nothing`() = runTest {
        stored = listOf(rule("a"))
        val follower = follower()
        assertTrue(follower.follow(null))
        every { session.getUserId() } returns null
        assertTrue(follower.follow("Asia/Tokyo"))
        coVerify(exactly = 0) { repository.updateScheduleZone(any(), any()) }
    }
}
