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

import uniffi.pingolin_mobile.CircleRole

/**
 * What this account may do in a circle: as the server allows it, and where it takes effect.
 * Admins invite, manage the invitations and remove members; only the owner changes roles,
 * hands the circle on or deletes it. The server's own checks: invitations (sending, listing,
 * withdrawing) want an owner or admin, a removal an owner or admin, and the rest the owner
 * (changing roles, transferring ownership, deleting a circle).
 *
 * On a circle with refresh records an action counts only where the chain lets it: an
 * invitation admits its invitee only from an owner or admin in the chain (else it goes without the
 * key and the invitee waits: [CircleRepository]'s invitationKeyMayGo), and only the chain's owner
 * changes a role. A removal goes through for the server's owner or admin, with a new key where this
 * device may name it and without one otherwise (its next rotation names it: removeMember). While the
 * records wait (not read here yet, or forked), nothing is offered.
 *
 * Only which controls to OFFER: the server checks every one of them again.
 */
object CircleRights {

    const val OWNER = "owner"
    const val ADMIN = "admin"

    /** A circle's refresh records, as this device has them. */
    enum class Records { NONE, WAITING, READ }

    /**
     * [manageInvites]: see and withdraw the circle's pending invitations, which the server does for
     * its owner or admins whoever sent them.
     */
    data class Rights(val invite: Boolean, val manageInvites: Boolean, val remove: Boolean, val changeRoles: Boolean) {
        companion object {
            val NONE = Rights(invite = false, manageInvites = false, remove = false, changeRoles = false)
        }
    }

    /**
     * [serverRole]: this account's role in the server's member list ("owner", "admin", "member"),
     * or null when it isn't on it. [chainRole]: its role in the circle's records, read only when
     * [records] is READ.
     */
    fun of(serverRole: String?, records: Records, chainRole: CircleRole?): Rights {
        val ownerOrAdmin = serverRole == OWNER || serverRole == ADMIN
        if (!ownerOrAdmin || records == Records.WAITING) return Rights.NONE
        val noRecords = records == Records.NONE
        return Rights(
            invite = noRecords || chainRole == CircleRole.OWNER || chainRole == CircleRole.ADMIN,
            manageInvites = true,
            remove = true,
            changeRoles = serverRole == OWNER && (noRecords || chainRole == CircleRole.OWNER),
        )
    }
}
