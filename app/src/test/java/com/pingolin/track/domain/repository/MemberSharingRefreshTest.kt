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

import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * The member list's sharing state reaches the stored rows the UI reads, through the
 * real CircleRepository.refreshMembers: the existing refresh, with no loop of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberSharingRefreshTest {

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `each member's sharing state is kept with their row, and silence is sharing`() = runTest {
        val h = TrustHarness(self = "me")
        h.repository.deliveryScope = this
        h.earlierSeed("me", "ann", "bob", "cat", "dan")
        coEvery { h.tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(emptyList(), more = false), error = null))
        h.serverLists(
            listOf(
                h.rosterMember("me", role = "owner"),
                h.rosterMember("ann").copy(locationSharing = "off"),
                h.rosterMember("bob").copy(locationSharing = "paused", sharingPausedUntil = "2026-10-14T17:00:00+00:00"),
                h.rosterMember("cat").copy(locationSharing = "paused"),
                // A server from before the field.
                h.rosterMember("dan"),
            )
        )

        h.repository.refreshMembers(h.circleId)
        advanceUntilIdle()

        val rows = h.storedRows.associateBy { it.userId }
        assertEquals(setOf("me", "ann", "bob", "cat", "dan"), rows.keys)
        assertEquals(null to null, rows.getValue("me").let { it.locationSharing to it.sharingPausedUntil })
        assertEquals("off" to null, rows.getValue("ann").let { it.locationSharing to it.sharingPausedUntil })
        assertEquals("paused" to 1_791_997_200_000L, rows.getValue("bob").let { it.locationSharing to it.sharingPausedUntil })
        assertEquals("paused" to null, rows.getValue("cat").let { it.locationSharing to it.sharingPausedUntil })
        assertEquals(null to null, rows.getValue("dan").let { it.locationSharing to it.sharingPausedUntil })
    }
}
