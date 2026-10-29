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
import com.pingolin.track.data.remote.dto.RevokeAccessResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Removing a member while this phone is behind: never kept waiting — it goes through at once
 * without a new key, and this phone still records the departure (tombstone, statement ids) at
 * its next member list, as for any departure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemovalWhileBehindTest {

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `behind, a removal goes through at once without a new key, and the next member list records the departure`() = runTest {
        val h = TrustHarness(selfRole = "owner")
        h.repository.deliveryScope = this
        h.earlierSeed("me", "owner")
        h.repository.catchUpPausesMs = emptyList()
        // sam joined on this user's invitation; the member list records the statement they carry.
        val invite = h.invitation("sam", by = "me", id = "inv-sam")
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner"), h.rosterMember("sam", invite)))
        h.repository.refreshMembers("c-1")
        assertTrue("sam" in h.origins())

        // The circle's key moved on without this phone.
        h.circleVersion = 4
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(true, null, null))
        coEvery { h.tracksApi.revokeMemberAccess("c-1", "sam") } returns
            Response.success(ApiResponse(true, RevokeAccessResponse("sam", true, "warning"), null))

        val removed = h.repository.removeMember("c-1", "sam")

        assertEquals("removed, without a new key", false, removed.getOrNull())
        coVerify(exactly = 1) { h.tracksApi.revokeMemberAccess("c-1", "sam") }
        coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(any(), any()) }

        // The next member list: sam is gone, and this phone records it as any departure.
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("owner", keyVersion = 4)))
        h.repository.refreshMembers("c-1")
        assertTrue("sam" in h.approvals.tombstones("c-1"))
        assertTrue("inv-sam" in h.approvals.tombstonedStatementIds("c-1"))
    }

    @Test
    fun `current, a removal re-keys the circle`() = runTest {
        val h = TrustHarness(selfRole = "owner")
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } throws java.io.IOException("offline")

        val removed = h.repository.removeMember("c-1", "sam")

        assertTrue("the re-keying removal's own failure, never the one without a new key", removed.isFailure)
        coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
    }
}
