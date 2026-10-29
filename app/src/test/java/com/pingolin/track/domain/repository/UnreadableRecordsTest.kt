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

import io.mockk.clearAllMocks
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Records that can't be read decide nothing: while this device's approvals (who it trusts,
 * tombstones, dead invitations, the backfill's ids) can't be read, confirmation, the deliveries
 * after it and the backfill wait; nothing is written over them, and once they read, everything
 * goes on as usual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnreadableRecordsTest {

    @After
    fun tearDown() = clearAllMocks()

    /** An owner's device with a seed of alice and bob; [aliceVersion]: the copy alice holds (the circle is at
     * 3).
     */
    private fun TestScope.harness(aliceVersion: Int = 3) = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice", "bob")
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", keyVersion = aliceVersion), h.rosterMember("bob")))
    }

    @Test
    fun `confirmation waits - the list isn't judged, nothing is written or pinned, and it's judged once they read`() = runTest {
        val h = harness()
        h.approvalPrefs.damaged = true
        val stored = h.approvalPrefs.data.toMap()
        val pins = h.pins.signing.toMap()

        assertTrue(h.repository.refreshMembers("c-1").isFailure)
        advanceUntilIdle()

        assertEquals("nothing written over them", stored, h.approvalPrefs.data)
        assertEquals("nobody pinned", pins, h.pins.signing)
        assertTrue("no member rows replaced", h.storedRows.isEmpty())

        h.approvalPrefs.damaged = false
        assertTrue(h.repository.refreshMembers("c-1").isSuccess)
        assertEquals(setOf("me", "alice", "bob"), h.storedRows.map { it.userId }.toSet())
    }

    @Test
    fun `deliveries wait - nothing is handed over while they can't be read, and it goes once they read`() = runTest {
        val h = harness(aliceVersion = 2)
        // This device created the circle: it hands the current copy to anyone it confirms.
        h.approvals.markCreatedHere("c-1")
        val delivered = h.deliveriesAccepted()
        h.approvalPrefs.damaged = true

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        advanceUntilIdle()
        assertTrue(delivered.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }

        h.approvalPrefs.damaged = false
        h.repository.deliverOwedAccess("c-1")
        advanceUntilIdle()
        assertEquals(setOf("alice"), delivered.keys)
    }

    @Test
    fun `the backfill waits - it signs nothing while they can't be read, and vouches once they read`() = runTest {
        // As SignaturesNoOneDecidesTest's owner: alice and bob are confirmed by the seed
        // alone.
        val h = harness()
        h.recordedCircle()
        h.deliveriesAccepted()
        val admitted = h.statementsAccepted()

        h.repository.refreshMembers("c-1")
        // Judged, and the backfill scheduled; the file goes unreadable before it runs.
        h.approvalPrefs.damaged = true
        advanceUntilIdle()
        assertTrue(admitted.isEmpty())
        assertTrue("nothing signed", h.statements.signed.isEmpty())

        h.approvalPrefs.damaged = false
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertEquals(setOf("alice", "bob"), admitted.keys)
    }
}
