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

package com.pingolin.track.domain.pending

/**
 * What a change waiting to sync does. Android has nothing to queue for three kinds the other
 * clients have: no screen changes a destination's arrival time (`updateDestination` is only
 * called with a status), nothing adds a place owned by a circle (`PlaceRepository.createPlace`
 * has no caller; the Places screen makes personal places), and a circle can't be renamed here.
 */
enum class PendingKind {
    /** Invite someone. */
    INVITE,
    /** Make a member an admin. */
    MAKE_ADMIN,
    /** "Let them in". */
    LET_IN,
    /** "It's them" about a pending invitee, which renews the invitation. */
    RENEW_INVITATION,
    /** Check in. */
    CHECK_IN,
    /** Share a destination. */
    DESTINATION,
    /** "I've arrived". */
    ARRIVED,
    /** Share a place with a circle. */
    SHARE_PLACE,
    /** Copy a place, or all of a circle's places, to a circle. */
    COPY_PLACES,
    /** Edit an older place owned by a circle. */
    EDIT_PLACE,
    /** A place alert about one member. */
    MEMBER_PLACE_ALERT,
    /** A place alert about the whole circle. */
    CIRCLE_PLACE_ALERT,
    /** A low-battery alert. */
    BATTERY_ALERT,
    /** Change an alert's threshold. */
    ALERT_CHANGE,
    /** An inactivity alert. */
    INACTIVITY_ALERT;

    /**
     * For INVITE, MAKE_ADMIN and LET_IN, every statement id a run signs is saved before its
     * request goes out, and after a lost answer the server is asked about them before anything
     * is signed again. A renewal keeps the original invitation's id, so it needs none of this.
     */
    val keepsStatementIds: Boolean get() = this == INVITE || this == MAKE_ADMIN || this == LET_IN

    /** Signs when it runs (INVITE, MAKE_ADMIN, LET_IN, RENEW_INVITATION): without the
     * signing key it waits for the password.
     */
    val signs: Boolean get() = keepsStatementIds || this == RENEW_INVITATION

    /**
     * One change per target: a later one for the same target replaces its inputs, or merges its
     * fields ([mergesFields]), instead of queuing a second.
     */
    val merges: Boolean
        get() = this == INVITE || this == MAKE_ADMIN || this == LET_IN || this == RENEW_INVITATION ||
            this == SHARE_PLACE || this == COPY_PLACES || this == EDIT_PLACE || this == ALERT_CHANGE

    val mergesFields: Boolean get() = this == EDIT_PLACE || this == ALERT_CHANGE

    /**
     * How long it may wait, from when it was made. A renewal waits until its invitation
     * runs out, and a destination until its arrival time passes if that comes first: both are
     * worked out when queued.
     */
    val ageLimitMs: Long
        get() = when (this) {
            CHECK_IN, DESTINATION -> DAY_MS
            ARRIVED -> 2 * HOUR_MS
            else -> 7 * DAY_MS
        }

    companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS

        fun of(name: String): PendingKind? = entries.firstOrNull { it.name == name }
    }
}
