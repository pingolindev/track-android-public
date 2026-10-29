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

import com.pingolin.track.data.remote.dto.MemberPublicKeyResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a rotation hands the new circle key to.
 *
 * The server alone decides who is in a circle, and the new key opens every older one
 * through the key chain, so "wrap to whoever member-public-keys lists" would hand a
 * server-added account all history with no user action. And if one member with an
 * unapproved key change aborted the whole rotation, a removed member would keep
 * reading.
 */
class RotationPlannerTest {

    private val v = 3 // the circle's current key version

    private fun member(id: String, keyVersion: Int? = v, email: String? = null) = MemberPublicKeyResponse(
        userId = id,
        sharingPublicKey = "a".repeat(64),
        signingPublicKey = "b".repeat(64),
        keyVersion = keyVersion,
        email = email,
    )

    private fun plan(
        keys: List<MemberPublicKeyResponse>,
        roster: Set<String> = keys.map { it.userId }.toSet(),
        blocked: Set<String> = emptySet(),
        // Confirmed on this device: the members everybody expects to be there.
        confirmed: Set<String> = setOf("alice", "bob"),
        cause: String? = "member_removed",
        forDelivery: Boolean = false,
    ) = RotationPlanner.plan("me", v, keys, roster, blocked, confirmed, cause, forDelivery)

    @Test
    fun `an account the member list does not show gets nothing and is reported`() {
        val result = plan(
            listOf(member("me"), member("alice"), member("ghost", keyVersion = null)),
            roster = setOf("me", "alice"),
        )
        assertEquals(listOf("me", "alice"), result.wrap.map { it.userId })
        assertEquals(setOf("ghost"), result.notInRoster)
        assertEquals(listOf("ghost"), result.pending)
        assertTrue(result.unconfirmed.isEmpty())
        // Not on the member list, so nobody is left pending who is in the circle.
        assertTrue(result.leavesNobodyPending)
    }

    @Test
    fun `a member owed the key whom this device does not confirm is left pending, not wrapped`() {
        val result = plan(
            listOf(member("me"), member("alice"), member("newcomer", keyVersion = null, email = "n@x.test")),
            confirmed = setOf("alice"),
        )
        assertEquals(listOf("me", "alice"), result.wrap.map { it.userId })
        assertEquals(listOf("newcomer"), result.unconfirmed)
        assertEquals(listOf("newcomer"), result.pending)
        // A plain member's app leaves this refresh to an owner or admin.
        assertFalse(result.leavesNobodyPending)
    }

    @Test
    fun `a confirmed member owed the key is wrapped`() {
        val result = plan(listOf(member("me"), member("newcomer", keyVersion = null)), confirmed = setOf("newcomer"))
        assertEquals(listOf("me", "newcomer"), result.wrap.map { it.userId })
        assertTrue(result.pending.isEmpty())
        assertTrue(result.leavesNobodyPending)
    }

    @Test
    fun `an unconfirmed member left behind on an older version is left pending`() {
        val result = plan(listOf(member("me"), member("carol", keyVersion = v - 1)))
        assertEquals(listOf("carol"), result.unconfirmed)
    }

    @Test
    fun `confirmed members are wrapped`() {
        val result = plan(listOf(member("me"), member("alice"), member("bob")))
        assertEquals(listOf("me", "alice", "bob"), result.wrap.map { it.userId })
        assertTrue(result.unconfirmed.isEmpty())
    }

    @Test
    fun `a ghost on the member list that the server says holds the current key is left pending, not wrapped`() {
        // The server controls both lists and every key_version it reports. Trust comes
        // from this device's confirmation alone: a claimed current wrap proves nothing.
        val result = plan(
            listOf(member("me"), member("alice"), member("ghost", keyVersion = v)),
            confirmed = setOf("alice"),
        )
        assertEquals(listOf("me", "alice"), result.wrap.map { it.userId })
        assertEquals(listOf("ghost"), result.unconfirmed)
        assertEquals(listOf("ghost"), result.pending)
    }

    @Test
    fun `a blocked member is left pending and the rotation still happens`() {
        // Aborting the rotation here would leave a removed member on the live
        // key.
        val result = plan(listOf(member("me"), member("alice"), member("bob")), blocked = setOf("bob"))
        assertEquals(listOf("me", "alice"), result.wrap.map { it.userId })
        assertEquals(listOf("bob"), result.pending)
        assertFalse(result.skip)
        assertFalse(result.leavesNobodyPending)
    }

    @Test
    fun `a departure always re-keys, even with everyone else pending`() {
        for (cause in listOf("member_removed", "member_left", "periodic", "other", null)) {
            val result = plan(
                listOf(member("me"), member("bob"), member("newcomer", keyVersion = null)),
                blocked = setOf("bob"),
                confirmed = setOf("bob"),
                cause = cause,
            )
            assertFalse("cause=$cause", result.skip)
            assertEquals("cause=$cause", listOf("me"), result.wrap.map { it.userId })
        }
    }

    @Test
    fun `a keyless join that nobody confirms does not bump the version`() {
        val result = plan(
            listOf(member("me"), member("alice"), member("newcomer", keyVersion = null)),
            cause = "member_joined",
        )
        assertTrue(result.skip)
        assertEquals(listOf("newcomer"), result.unconfirmed)
    }

    @Test
    fun `a keyless join with a confirmed newcomer rotates`() {
        val result = plan(
            listOf(member("me"), member("newcomer", keyVersion = null)),
            confirmed = setOf("newcomer"),
            cause = "member_joined",
        )
        assertFalse(result.skip)
    }

    @Test
    fun `a delivery rotation happens only when somebody owed the key gets it`() {
        val nobodyOwed = plan(listOf(member("me"), member("alice")), forDelivery = true, cause = null)
        assertTrue(nobodyOwed.skip)
        // Owed but not confirmed: still nothing to deliver.
        val notApproved = plan(
            listOf(member("me"), member("carol", keyVersion = null)),
            forDelivery = true,
            cause = null,
        )
        assertTrue(notApproved.skip)

        val bobApproved = plan(
            listOf(member("me"), member("bob", keyVersion = v - 1)),
            confirmed = setOf("bob"),
            forDelivery = true,
            cause = null,
        )
        assertFalse(bobApproved.skip)
        assertEquals(listOf("me", "bob"), bobApproved.wrap.map { it.userId })
    }

    @Test
    fun `on an older server with no wrap versions, a delivery rotation still delivers`() {
        // Nobody can be told apart as owed there, so every confirmed member
        // counts as owed: a delivery for them is not skipped.
        val keys = listOf(member("me", keyVersion = null), member("alice", keyVersion = null))
        assertFalse(plan(keys, forDelivery = true, cause = null).skip)
        // With nobody confirmed but this device, there is still nothing to deliver.
        assertTrue(plan(listOf(member("me", keyVersion = null), member("carol", keyVersion = null)), forDelivery = true, cause = null).skip)
    }

    @Test
    fun `an older server with no wrap versions still trusts only confirmed members`() {
        // No key_version anywhere: nobody can be told apart as owed, so a keyless join is
        // not skipped; who is wrapped still comes from this device's confirmation.
        val keys = listOf(member("me", keyVersion = null), member("alice", keyVersion = null), member("carol", keyVersion = null))
        val result = plan(keys, cause = "member_joined")
        assertEquals(listOf("me", "alice"), result.wrap.map { it.userId })
        assertEquals(listOf("carol"), result.unconfirmed)
        assertFalse(result.skip)
    }

    @Test
    fun `this device's own slot is always wrapped`() {
        val result = plan(listOf(member("me", keyVersion = null)), roster = emptySet(), blocked = setOf("me"))
        assertEquals(listOf("me"), result.wrap.map { it.userId })
        assertTrue(result.pending.isEmpty())
    }
}
