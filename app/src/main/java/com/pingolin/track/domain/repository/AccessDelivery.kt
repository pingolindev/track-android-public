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

/** A circle's complete member list as this device last judged it. */
internal data class CircleTrustSnapshot(
    val result: MemberConfirmation.Result,
    val roster: List<MemberConfirmation.Member>,
    /** Members whose app asked for a fresh copy. */
    val accessRequested: Set<String>,
    /**
     * This user is the circle's owner or an admin, AS THE SERVER SAYS: good for who is asked
     * questions and who signs admissions, never for who is handed the circle.
     */
    val ownerOrAdmin: Boolean,
    /**
     * This device created the circle (its own record) and the server still lists this user
     * as its owner: the one case in which it hands the circle to any member it confirms.
     */
    val createdHere: Boolean = false,
    /** The member list's rows as the server sent them, by user id (their stored copies). */
    val rows: Map<String, com.pingolin.track.data.remote.dto.MemberResponse> = emptyMap(),
)

/**
 * Whom this device hands the circle's current copy to, and under which keys. Pure, so every
 * rule is unit-tested; CircleRepository wraps and sends.
 *
 * A member gets one when they are confirmed here, owed it (no copy, an older one, or
 * their app asked for a fresh one) and [MemberConfirmation.mayDeliver] allows it. The copy
 * is wrapped to the key this device trusts for them — the pin, or an email invitee's
 * recorded key — and only while the member list shows the same key: a member whose key
 * changed waits for "It's them".
 */
internal object AccessDelivery {

    data class Target(val userId: String, val sharingKeyHex: String, val signingKeyHex: String)

    fun plan(
        selfUserId: String,
        result: MemberConfirmation.Result,
        roster: List<MemberConfirmation.Member>,
        accessRequested: Set<String>,
        activeVersion: Int,
        nowSecs: Long,
        createdHere: Boolean,
        pinnedSharingKey: (String) -> String?,
        receiveOnlyKeys: Map<String, Pair<String, String>>,
        /**
         * On a circle with refresh records, the members its records allow a delivery to: it replaces
         * the confirmation cases and the creator's exemption. Null on a circle without records.
         */
        eDeliver: Set<String>? = null,
    ): List<Target> = roster.mapNotNull { member ->
        val userId = member.userId
        if (userId.equals(selfUserId, ignoreCase = true)) return@mapNotNull null
        val origin = result.confirmed[userId] ?: return@mapNotNull null
        // Nothing is handed over on this account's own statements from before a reset.
        if (userId in result.previousKeyOnly) return@mapNotNull null
        val owed = member.keyVersion == null || member.keyVersion < activeVersion || userId in accessRequested
        if (!owed) return@mapNotNull null
        val holding = result.holding[userId].orEmpty()
        val allowed = if (eDeliver != null) eDeliver.any { it.equals(userId, ignoreCase = true) }
        else MemberConfirmation.mayDeliver(member, origin, holding, activeVersion, nowSecs, createdHere)
        if (!allowed) return@mapNotNull null
        val sharing = member.sharingKeyHex ?: return@mapNotNull null
        val signing = member.signingKeyHex ?: return@mapNotNull null
        val trusted = if (result.receiveOnly(userId)) receiveOnlyKeys[userId]?.first else pinnedSharingKey(userId)
        if (trusted == null || !trusted.equals(sharing, ignoreCase = true)) return@mapNotNull null
        if (result.receiveOnly(userId) && receiveOnlyKeys[userId]?.second?.equals(signing, ignoreCase = true) != true) {
            return@mapNotNull null
        }
        Target(userId, trusted, signing)
    }
}

/**
 * Whom an owner/admin device vouches for once with an admission, so that other members'
 * apps — which cannot confirm them on their own — can. Pure, so every rule is unit-tested;
 * CircleRepository signs and stores.
 *
 * Only members confirmed here with nothing that other devices could check, and only on
 * evidence:
 *  - a member of this device's legacy seed (the original one, or the one-time seed from
 *    the pins it already held): the seed itself is the evidence for this transition
 *    (legacy wraps are often unsigned);
 *  - a member confirmed here once by a statement that no longer holds up (its signer left,
 *    or signed with a key since replaced): only when a member confirmed here handed them
 *    the copy they hold now ([handedOverByConfirmed]).
 * Never an email invitee, never a member whose key changed without "It's them" (the
 * admission binds their CURRENT keys), and once each.
 */
internal object AdmissionBackfill {

    fun candidates(
        selfUserId: String,
        result: MemberConfirmation.Result,
        roster: List<MemberConfirmation.Member>,
        preRound2Seed: Boolean,
        ownerOrAdmin: Boolean,
        alreadyBackfilled: Set<String>,
        pinnedSharingKey: (String) -> String?,
        pinnedSigningKey: (String) -> String?,
        handedOverByConfirmed: (MemberConfirmation.Member) -> Boolean,
    ): List<MemberConfirmation.Member> {
        if (!ownerOrAdmin || result.selfReceiveOnly) return emptyList()
        return roster.filter { member ->
            val userId = member.userId
            !userId.equals(selfUserId, ignoreCase = true) &&
                result.holding[userId].isNullOrEmpty() &&
                // Never under the current key for a confirmation resting on this
                // account's own statements from before a reset.
                userId !in result.previousKeyOnly &&
                userId !in alreadyBackfilled &&
                member.sharingKeyHex != null && member.signingKeyHex != null &&
                pinnedSharingKey(userId).equals(member.sharingKeyHex, ignoreCase = true) &&
                pinnedSigningKey(userId).equals(member.signingKeyHex, ignoreCase = true) &&
                when (result.confirmed[userId]) {
                    com.pingolin.track.util.KeyAccessApprovals.Origin.PRE_ROUND2 -> preRound2Seed
                    com.pingolin.track.util.KeyAccessApprovals.Origin.STATEMENT -> handedOverByConfirmed(member)
                    else -> false
                }
        }
    }
}
