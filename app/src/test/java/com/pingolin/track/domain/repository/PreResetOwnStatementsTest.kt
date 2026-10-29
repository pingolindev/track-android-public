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

import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import io.mockk.clearAllMocks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On the device: this account's own statements from before a password reset, verified by a
 * restored identity's signing key, confirm. The member is shown and pinned, but never
 * recorded as an approval (it would outlive the restored identity), handed the circle, or
 * vouched for under the current key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreResetOwnStatementsTest {

    @After
    fun tearDown() = clearAllMocks()

    private val oldKey = "me-signing-before-reset"

    private fun kotlinx.coroutines.test.TestScope.harness(restored: Boolean) = TrustHarness().also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "owner")
        if (restored) h.statements.previousSigningKeys += oldKey
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = listOf(
            h.rosterMember("me"),
            h.rosterMember("owner"),
            h.rosterMember("bob", h.invitation("bob", by = "me", signerKey = oldKey), keyVersion = null),
        ))
    }

    @Test
    fun `a member this account invited before a reset is shown and pinned, but never recorded, handed or vouched for`() = runTest {
        val h = harness(restored = true)
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue("shown", h.storedRows.any { it.userId == "bob" })
        assertEquals("pinned", sharing("bob"), h.pins.sharing["bob"])
        assertEquals("never recorded", null, h.origins()["bob"])
        assertFalse("handed nothing", delivered.containsKey("bob"))
        assertFalse("vouched for by nobody", admitted.containsKey("bob"))

        // Judged again on the next pass, the same way.
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertEquals(null, h.origins()["bob"])
        assertFalse(delivered.containsKey("bob"))
    }

    @Test
    fun `without the restored identity, the same member is hidden`() = runTest {
        val h = harness(restored = false)
        h.statementsAccepted()
        h.deliveriesAccepted()

        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertFalse(h.storedRows.any { it.userId == "bob" })
        assertEquals(null, h.pins.sharing["bob"])
    }
}
