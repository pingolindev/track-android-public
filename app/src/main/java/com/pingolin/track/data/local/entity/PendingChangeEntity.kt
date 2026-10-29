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

package com.pingolin.track.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Something the person asked for that this phone couldn't do yet because it is behind on the
 * circle: kept here and done by the same code the button runs, once this phone holds the
 * circle's current version (see PendingChangeRunner).
 *
 * INPUTS ONLY: what the person chose, as plain values. Never anything sealed or signed, no
 * version number, no wrap, key or token. A change that signs keeps only the ids of the
 * statements its runs signed ([signedIds]), saved before each request went out.
 *
 * In the SQLCipher database, so it is encrypted on disk, and it goes with every other table at
 * sign-out and on an account switch. A session expiry keeps it, like the keys.
 *
 * Done, dropped and cancelled changes are deleted. A dropped one leaves a [DroppedNoticeEntity].
 */
@Entity(tableName = "pending_changes", indices = [Index("account"), Index("circleId")])
data class PendingChangeEntity(
    /** Made on this phone. Also the create's `client_request_id`. */
    @PrimaryKey val id: String,
    /** The signed-in user who asked. A change never runs for anyone else. */
    val account: String,
    val circleId: String,
    /** A [com.pingolin.track.domain.pending.PendingKind] name. */
    val kind: String,
    /** What the person chose, as JSON of the kind's inputs. */
    val inputs: String,
    /** What the re-check compares against at run time, as JSON; null when the kind has none. */
    val seen: String? = null,
    /**
     * Every statement a run signed, saved before its request was sent, as JSON of
     * [com.pingolin.track.domain.pending.SignedStatement]: ids and, for an invitation, whom it names
     * and until when. Empty for the other kinds.
     */
    val signedIds: String = "",
    /** The thing a later change of the same kind merges into; null for a kind never merged. */
    val target: String? = null,
    val createdAt: Long,
    val expiresAt: Long,
    /** waiting | running | needs_person | cancelling | settling. */
    val state: String,
    /** While running: which attempt, and when it started. */
    val attemptId: String? = null,
    val startedAt: Long? = null,
    /** While needs_person: what the person is asked (a question, or the password). */
    val question: String? = null,
    /** Backoff and diagnostics only: nothing ever skips a check on these. */
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastOutcome: String? = null,
)

/**
 * The one line a dropped change leaves: shown in place and in the circle's list until it has
 * been seen, then deleted; at most 7 days either way.
 */
@Entity(tableName = "dropped_notices", indices = [Index("account"), Index("circleId")])
data class DroppedNoticeEntity(
    /** The dropped change's id. */
    @PrimaryKey val id: String,
    val account: String,
    val circleId: String,
    /** The dropped change's kind and target, so the line shows where the change did. */
    val kind: String,
    val target: String? = null,
    val line: String,
    val createdAt: Long,
    val seenAt: Long? = null,
)
