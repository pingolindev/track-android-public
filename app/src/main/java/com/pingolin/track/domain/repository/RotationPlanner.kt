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

/**
 * Who a rotation wraps the new circle key to, and whom it deliberately leaves out.
 * Pure, so every rule is unit-tested.
 *
 * Membership is whatever the server says, and the new key — and through the key chain,
 * all history — must not simply go to everybody `member-public-keys` lists. So:
 *  - an account listed for the key but absent from the circle's member list is never
 *    wrapped to, and is reported;
 *  - a member this device does not confirm (see MemberConfirmation) is left out,
 *    whatever the server says about the wraps or key versions they hold, because the
 *    server can say anything. Whether a member is OWED the key decides only whether a
 *    rotation has anyone to deliver to, never who is trusted.
 * A member with an unapproved key change is left out ("pending") and the rotation goes
 * ahead for everyone else: aborting the whole rotation would let a removed member keep
 * the live key for as long as that lasted.
 */
internal object RotationPlanner {

    data class Plan(
        /** Members the new key is wrapped to, this device's own slot included. */
        val wrap: List<MemberPublicKeyResponse>,
        /** Active members given no wrap; sent as `pending_members`. */
        val pending: List<String>,
        /** Listed for the key but not in the circle's member list: never wrapped, reported. */
        val notInRoster: Set<String>,
        /** On the member list, not confirmed on this device: left pending. */
        val unconfirmed: List<String>,
        /** Nothing worth a new key version: do not rotate. */
        val skip: Boolean,
    ) {
        /** Every member on the list gets the new key: a plain member may refresh. */
        val leavesNobodyPending: Boolean get() = (pending.toSet() - notInRoster).isEmpty()
    }

    /**
     * @param circleKeyVersion the version the circle is at, as this device last saw it
     * @param roster user ids of the circle's member list, fetched complete, just now
     * @param blocked members whose key changed without "It's them", or whose key the
     *   send-side gate could not check
     * @param confirmed members this device confirms (see MemberConfirmation); the only
     *   source of trust for a wrap
     * @param cause `key_rotation_cause` from the circle list, when a rotation is pending
     * @param forDelivery this rotation exists to bring newly approved members up to date,
     *   not because the server flagged the circle
     */
    fun plan(
        selfUserId: String,
        circleKeyVersion: Int,
        memberKeys: List<MemberPublicKeyResponse>,
        roster: Set<String>,
        blocked: Set<String>,
        confirmed: Set<String>,
        cause: String?,
        forDelivery: Boolean,
    ): Plan {
        // Older servers send no wrap versions, so nobody can be told apart as
        // owed: every confirmed member counts as owed there.
        val versionsKnown = memberKeys.any { it.keyVersion != null }
        fun owed(m: MemberPublicKeyResponse) = !versionsKnown || (m.keyVersion ?: 0) < circleKeyVersion

        val wrap = mutableListOf<MemberPublicKeyResponse>()
        val pending = mutableListOf<String>()
        val notInRoster = mutableSetOf<String>()
        val unconfirmed = mutableListOf<String>()
        for (member in memberKeys) {
            when {
                member.userId == selfUserId -> wrap += member
                member.userId !in roster -> {
                    notInRoster += member.userId
                    pending += member.userId
                }
                member.userId in blocked -> pending += member.userId
                member.userId !in confirmed -> {
                    unconfirmed += member.userId
                    pending += member.userId
                }
                else -> wrap += member
            }
        }

        val deliversToSomeoneOwed = wrap.any { it.userId != selfUserId && owed(it) }
        val skip = when {
            // Brought forward by an approval: pointless unless someone gets the key.
            forDelivery -> !deliversToSomeoneOwed
            // Flagged only because somebody joined without a key: if nobody who is owed
            // it gets it, a new version would change nothing but the number. A departure
            // or the periodic cycle always re-keys.
            cause == CAUSE_MEMBER_JOINED -> versionsKnown && !deliversToSomeoneOwed
            else -> false
        }
        return Plan(wrap, pending, notInRoster, unconfirmed, skip)
    }

    const val CAUSE_MEMBER_JOINED = "member_joined"
    const val CAUSE_MEMBER_LEFT = "member_left"
    const val CAUSE_MEMBER_REMOVED = "member_removed"
}
