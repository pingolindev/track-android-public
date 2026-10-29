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

import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.domain.pending.QueuedForSync
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Being behind is the device's own knowledge (the SDK's isBehind). Through the real
 * CircleRepository: while this device's records say it is behind on a circle, whatever version
 * the circle list shows, nothing is handed over and a tapped write waits as a kept change. A
 * rotation still runs and the row isn't locked. A records read notes what the records say now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsBehindTest {

    @After
    fun tearDown() = clearAllMocks()

    private val dao = InMemoryPendingChangeDao()

    /** An owner's phone, at the circle list's version (3), whose records say it is behind. */
    private fun TestScope.harness() = TrustHarness(selfRole = "owner", pendingChanges = pendingChangesIn(dao)).also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice")
        h.approvals.markCreatedHere("c-1")
        h.repository.catchUpPausesMs = emptyList()
        every { h.cryptoManager.isBehind("c-1") } returns true
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(true, AccessRequestResponse(true), null))
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(true, emptyList(), null))
        coEvery { h.tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(true, RefreshRecordsResponse(emptyList(), more = false), null))
        coEvery { h.circleDao.getAll() } answers { listOf(h.circle.copy(keyVersion = h.circleVersion)) }
    }

    @Test
    fun `nothing is handed over, and a tapped invitation waits as a kept change`() = runTest {
        val h = harness()
        // alice holds v2: this phone, which created the circle, owes her the current copy.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice", keyVersion = 2)))
        val delivered = h.deliveriesAccepted()

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        assertTrue(delivered.isEmpty())

        val tapped = h.repository.inviteToCircle("c-1", "dan@x.test", keepIfBehind = true)
        assertTrue(tapped.exceptionOrNull() is QueuedForSync)
        assertEquals(1, dao.changes.value.size)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertEquals(setOf("c-1"), h.repository.recordsBehindCircles.value)

        // Its records caught up: she is handed the circle.
        every { h.cryptoManager.isBehind("c-1") } returns false
        assertEquals(1, h.repository.deliverOwedAccess("c-1"))
        assertEquals(3, delivered.getValue("alice").keyVersion)
        assertEquals(emptySet<String>(), h.repository.recordsBehindCircles.value)
    }

    @Test
    fun `a rotation still runs, and the row isn't locked`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice")))
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue("the SDK's builder is a rotation's gate", sent.isCaptured)
        assertFalse(h.repository.isCircleLocked(h.circle))
        assertEquals(emptySet<String>(), h.repository.behindCircles.value)
    }

    @Test
    fun `a records read notes what the records say now`() = runTest {
        val h = harness()

        h.repository.syncRefreshRecords()
        assertEquals(setOf("c-1"), h.repository.recordsBehindCircles.value)

        every { h.cryptoManager.isBehind("c-1") } returns false
        h.repository.syncRefreshRecords()
        assertEquals(emptySet<String>(), h.repository.recordsBehindCircles.value)
    }
}
