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

import com.pingolin.track.util.AccountIds
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMemberState

/**
 * Whom a key change of a circle with refresh records names, and how. Pure, so every rule is
 * unit-tested; CircleRepository asks the SDK (refreshEligibility, chainRole) and has it build and
 * check the record (prepareRecordedRotation), as every other device will check it. Members who
 * left unseen are not the app's to name: the SDK's builder fills them in from the record before.
 */
internal object RecordedRotation {

    data class Plan(
        /** Everyone the record names; this account's own entry first, included. */
        val entries: List<PlannedEntry>,
        /** Whom the new key is wrapped to: the included entries, this account among them. */
        val included: Set<String>,
    ) {
        /**
         * `pending_members` for the request: every listed member not given the new key, named on
         * purpose (the server wants each active member either wrapped or named).
         */
        fun requestPending(listed: Collection<String>, self: String): List<String> =
            listed.filter { id -> !id.equals(self, ignoreCase = true) && included.none { it.equals(id, ignoreCase = true) } }
    }

    /**
     * A rotation, a repair or an eject:
     * - included: the members the rotation plan would wrap to ([wrap]: confirmed here, their key
     *   unchanged or accepted, on the complete list) whom the SDK's refresh eligibility also
     *   allows ([eligible]);
     * - removed: this device's own removals ([ownRemovals]: its eject, or its revoke-access since
     *   its last rotation), when the chain names them; a record can't remove someone it never
     *   named. Only while the chain has this account as its owner or an admin: without that right
     *   a "removed" is refused everywhere, so a removal made here is left to the builder, which
     *   names a member off the list departed;
     * - pending: every other listed member the chain counts ([chainRole] not null), and every
     *   listed member the record this device rejected, and builds on, names, included or pending
     *   ([rejectedIncluded]: the report's tipNamedListed): the builder refuses a plan that leaves
     *   one of them out (as on iOS). Removed, they are named removed;
     * - roles: carried over (null), except the changes this account made as the chain's owner
     *   ([ownerRoleChanges]), and an ownership claim ([claimsOwnership]: this account, an admin
     *   in the chain and the owner on the list, names itself owner).
     * A listed member the chain doesn't count and the eligibility check doesn't allow (a newcomer
     * not let in yet) is not named.
     */
    fun rotation(
        self: String,
        listed: Collection<String>,
        wrap: Set<String>,
        eligible: Set<String>,
        chainRole: (String) -> CircleRole?,
        ownRemovals: Set<String> = emptySet(),
        ownerRoleChanges: Map<String, CircleRole> = emptyMap(),
        claimsOwnership: Boolean = false,
        rejectedIncluded: Set<String> = emptySet(),
    ): Plan {
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)
        fun Collection<String>.has(id: String) = any { same(it, id) }
        val owner = chainRole(self) == CircleRole.OWNER
        fun role(id: String): CircleRole? = if (owner) ownerRoleChanges.entries.firstOrNull { same(it.key, id) }?.value else null
        val removals = if (owner || chainRole(self) == CircleRole.ADMIN) ownRemovals else emptySet()
        val entries = mutableListOf(PlannedEntry(self, RecordMemberState.INCLUDED, if (claimsOwnership) CircleRole.OWNER else role(self)))
        val included = linkedSetOf(self)
        for (id in listed.filterNot { same(it, self) }) {
            val named = chainRole(id) != null || rejectedIncluded.has(id)
            when {
                removals.has(id) -> if (named) entries += PlannedEntry(id, RecordMemberState.REMOVED, null)
                wrap.has(id) && eligible.has(id) -> {
                    entries += PlannedEntry(id, RecordMemberState.INCLUDED, role(id))
                    included += id
                }
                named -> entries += PlannedEntry(id, RecordMemberState.PENDING, role(id))
            }
        }
        // Removed while behind, and off the list since: still this device's own removal to name.
        for (id in removals.filterNot { listed.has(it) || same(it, self) }) {
            if (chainRole(id) != null) entries += PlannedEntry(id, RecordMemberState.REMOVED, null)
        }
        return Plan(entries, included)
    }

    /**
     * A circle's first record, written by an owner's or admin's device: the rotation plan's wrap
     * set decides who gets the key ([wrap]), every other listed member WITH keys is pending, and
     * every role is the list's ([listedRoles]). A listed member with no keys is left out: the
     * chain has never named them. [removing]: the member an eject removes, who isn't named at
     * all.
     */
    fun transition(
        self: String,
        listedRoles: Map<String, CircleRole?>,
        keyless: Set<String>,
        wrap: Set<String>,
        removing: String? = null,
    ): Plan {
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)
        val entries = mutableListOf<PlannedEntry>()
        val included = linkedSetOf<String>()
        val selfRole = listedRoles.entries.firstOrNull { same(it.key, self) }?.value
        entries += PlannedEntry(self, RecordMemberState.INCLUDED, selfRole)
        included += self
        for ((id, role) in listedRoles) {
            if (same(id, self) || (removing != null && same(id, removing)) || keyless.any { same(it, id) }) continue
            if (wrap.any { same(it, id) }) {
                entries += PlannedEntry(id, RecordMemberState.INCLUDED, role)
                included += id
            } else {
                entries += PlannedEntry(id, RecordMemberState.PENDING, role)
            }
        }
        return Plan(entries, included)
    }

    /**
     * The `due` of the shared role-changes vector (test resource role-changes-vector.json): whether a
     * record is owed now for this user's own role changes ([owed]). Only on a device whose account is
     * the chain's owner ([myChainRole]); then for an owed change the chain doesn't show: a member it
     * counts, with another role; or one it never named, made admin, once a record built now would
     * include them ([includable]: listed, in the rotation plan's wrap, and allowed by the SDK's refresh
     * eligibility), as on iOS. A never-named member's demotion isn't due: the chain takes a newcomer in
     * as a member. It stays owed till a record names them.
     */
    fun rolesDue(
        myChainRole: CircleRole?,
        owed: Map<String, String>,
        chainRole: (String) -> CircleRole?,
        includable: (String) -> Boolean,
    ): Boolean = myChainRole == CircleRole.OWNER && owed.any { (userId, role) ->
        val id = AccountIds.storeKey(userId)
        val wanted = RefreshRecords.role(role)
        when (val now = chainRole(id)) {
            null -> wanted == CircleRole.ADMIN && includable(id)
            else -> now != wanted
        }
    }

    /**
     * The vector's `settles`, as far as a record built now decides it: of the role changes [owed]
     * when it was planned, each with the role it settles, those the record writes ([plan] names the
     * member with that role), or that never can be from here (this device's account isn't the chain's
     * owner: [chainOwner]; or the member isn't [listed]). OwnerRoleChanges.settled drops one once the
     * server takes the record only while it still holds that role: a change made meanwhile stays
     * owed.
     */
    fun rolesSettle(chainOwner: Boolean, owed: Map<String, String>, listed: Collection<String>, plan: Plan): Map<String, String> {
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)
        return owed.filter { (userId, role) ->
            !chainOwner || listed.none { same(it, userId) } ||
                plan.entries.any { same(it.userId, userId) && it.role != null && it.role == RefreshRecords.role(role) }
        }
    }
}
