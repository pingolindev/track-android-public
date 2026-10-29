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

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMemberState.INCLUDED
import uniffi.pingolin_mobile.RecordMemberState.PENDING
import uniffi.pingolin_mobile.RecordMemberState.REMOVED

/** Whom a key change of a circle with refresh records names (RecordedRotation). */
class RecordedRotationTest {

    /** The chain counts alice, bob and carol, and names this account ("me") its owner. */
    private val chain = mapOf("me" to CircleRole.OWNER, "alice" to CircleRole.ADMIN, "bob" to CircleRole.MEMBER, "carol" to CircleRole.MEMBER)
    private fun counted(id: String) = chain[id]

    @Test
    fun `E_include decides who gets the key among those the rotation plan wraps to, and every other counted member is pending`() {
        // alice: wrapped and allowed. bob: allowed by the eligibility check but his key changed
        // (the plan leaves him out). carol: wrapped, not allowed by the eligibility check. newt:
        // a newcomer nobody let in yet.
        val plan = RecordedRotation.rotation(
            self = "me",
            listed = listOf("me", "alice", "bob", "carol", "newt"),
            wrap = setOf("me", "alice", "carol"),
            eligible = setOf("alice", "bob"),
            chainRole = ::counted,
        )

        assertEquals(
            listOf(PlannedEntry("me", INCLUDED, null), PlannedEntry("alice", INCLUDED, null), PlannedEntry("bob", PENDING, null), PlannedEntry("carol", PENDING, null)),
            plan.entries,
        )
        assertEquals(setOf("me", "alice"), plan.included)
        assertEquals("every listed member not wrapped is named for the server", listOf("bob", "carol", "newt"), plan.requestPending(listOf("me", "alice", "bob", "carol", "newt"), "me"))
    }

    @Test
    fun `this device's own removals are named removed, only when the chain names them`() {
        val plan = RecordedRotation.rotation(
            self = "me",
            listed = listOf("me", "alice", "bob", "newt"),
            wrap = setOf("me", "alice", "bob"),
            eligible = setOf("alice", "bob"),
            chainRole = ::counted,
            // bob: ejected now (still listed until the eject lands). carol: revoked while behind,
            // off the list since. newt: never named by the chain.
            ownRemovals = setOf("bob", "carol", "newt"),
        )

        assertEquals(
            listOf(PlannedEntry("me", INCLUDED, null), PlannedEntry("alice", INCLUDED, null), PlannedEntry("bob", REMOVED, null), PlannedEntry("carol", REMOVED, null)),
            plan.entries,
        )
        assertEquals(setOf("me", "alice"), plan.included)
    }

    @Test
    fun `a removal is named removed only while the chain has this account as its owner or an admin`() {
        // A "removed" without that right is refused everywhere, so a plain
        // member's device leaves its removals to the builder.
        val asAdmin = RecordedRotation.rotation(
            self = "alice",
            listed = listOf("alice", "carol"),
            wrap = setOf("alice", "carol"),
            eligible = setOf("carol"),
            chainRole = ::counted,
            ownRemovals = setOf("carol", "bob"),
        )
        assertEquals(
            listOf(PlannedEntry("alice", INCLUDED, null), PlannedEntry("carol", REMOVED, null), PlannedEntry("bob", REMOVED, null)),
            asAdmin.entries,
        )

        val asMember = RecordedRotation.rotation(
            self = "bob",
            listed = listOf("bob", "carol"),
            wrap = setOf("bob", "carol"),
            eligible = setOf("carol"),
            chainRole = ::counted,
            ownRemovals = setOf("carol", "alice"),
        )
        assertEquals(listOf(PlannedEntry("bob", INCLUDED, null), PlannedEntry("carol", INCLUDED, null)), asMember.entries)
    }

    @Test
    fun `roles change only in a record this account signs as the chain's owner`() {
        val changes = mapOf("bob" to CircleRole.ADMIN, "alice" to CircleRole.MEMBER)
        val asOwner = RecordedRotation.rotation("me", listOf("me", "alice", "bob"), setOf("me", "alice", "bob"), setOf("alice", "bob"), ::counted, ownerRoleChanges = changes)
        assertEquals(listOf(null, CircleRole.MEMBER, CircleRole.ADMIN), asOwner.entries.map { it.role })

        val asAdmin = RecordedRotation.rotation("alice", listOf("alice", "bob"), setOf("alice", "bob"), setOf("bob"), ::counted, ownerRoleChanges = changes)
        assertEquals(listOf(null, null), asAdmin.entries.map { it.role })
    }

    @Test
    fun `a listed member the rejected tip includes is carried pending, or named removed, though the chain doesn't count them`() {
        // The builder builds on that tip, and refuses a plan that leaves out a
        // listed member the tip names. newt: included by it, the eligibility check
        // says no here.
        val plan = RecordedRotation.rotation(
            "me", listOf("me", "alice", "newt"), setOf("me", "alice", "newt"), setOf("alice"), ::counted,
            rejectedIncluded = setOf("newt"),
        )
        assertEquals(listOf(PlannedEntry("me", INCLUDED, null), PlannedEntry("alice", INCLUDED, null), PlannedEntry("newt", PENDING, null)), plan.entries)
        assertEquals(setOf("me", "alice"), plan.included)

        val eject = RecordedRotation.rotation(
            "me", listOf("me", "alice", "newt"), setOf("me", "alice", "newt"), setOf("alice"), ::counted,
            ownRemovals = setOf("newt"), rejectedIncluded = setOf("newt"),
        )
        assertEquals(PlannedEntry("newt", REMOVED, null), eject.entries.last())
    }

    @Test
    fun `an admin in the chain claiming its ownership names itself owner, and changes no other role`() {
        val plan = RecordedRotation.rotation(
            "alice", listOf("alice", "bob"), setOf("alice", "bob"), setOf("bob"), ::counted,
            ownerRoleChanges = mapOf("bob" to CircleRole.ADMIN), claimsOwnership = true,
        )
        assertEquals(listOf(PlannedEntry("alice", INCLUDED, CircleRole.OWNER), PlannedEntry("bob", INCLUDED, null)), plan.entries)
    }

    @Test
    fun `a transition names every listed member with keys, with the list's roles`() {
        val plan = RecordedRotation.transition(
            self = "me",
            listedRoles = mapOf("me" to CircleRole.OWNER, "alice" to CircleRole.ADMIN, "bob" to CircleRole.MEMBER, "kay" to CircleRole.MEMBER, "zed" to CircleRole.MEMBER),
            keyless = setOf("kay"),
            wrap = setOf("me", "alice"),
            removing = "zed",
        )

        assertEquals(
            listOf(PlannedEntry("me", INCLUDED, CircleRole.OWNER), PlannedEntry("alice", INCLUDED, CircleRole.ADMIN), PlannedEntry("bob", PENDING, CircleRole.MEMBER)),
            plan.entries,
        )
        assertEquals(setOf("me", "alice"), plan.included)
    }
}
