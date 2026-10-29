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

import com.pingolin.track.data.local.entity.LocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two signals the alert evaluators gate on.
 *
 * WHY A SET OF MEMBERS
 *   The local `locations` table is a CACHE of last-known positions, not a
 *   snapshot of one refresh: `persistAndEvaluate` only inserts, and the only
 *   deletes are keyed on circle MEMBERSHIP (`deleteForMissingMembers`) and a
 *   90-day sweep — while an inactivity timeout is measured in hours. So a member
 *   absent from a response keeps a row whose timestamp goes on ageing, and
 *   reading that row would call it silence.
 *
 *   `keyMissingCount == 0 && decryptionFailures == 0` cannot catch it, because
 *   an absent member produces no row to fail. The most reachable cause of
 *   absence is a member who PAUSED sharing, filtered out server-side — so
 *   pausing, the control a person reaches for when they do not want their
 *   whereabouts known, would tell their circle they might need attention.
 *
 *   The iOS app applies the same two-tier rule; see
 *   MemberLocationRefreshGateTests there.
 */
class LocationFetchResultGateTests {

    private fun location(userId: String) = LocationEntity(
        id = 0L, deviceId = "d-$userId", userId = userId, circleId = "c-1",
        latitude = 0.0, longitude = 0.0, altitude = null, speed = null,
        heading = null, accuracy = null, batteryLevel = null,
        timestamp = 0L, dayBucket = 19500
    )

    private fun result(
        seen: List<String>,
        total: Int = 3,
        decryptionFailures: Int = 0,
        keyMissingCount: Int = 0
    ) = LocationFetchResult(
        locations = seen.map(::location),
        totalReceived = total,
        decryptionFailures = decryptionFailures,
        keyMissingCount = keyMissingCount
    )

    // ── refreshedUserIds ────────────────────────────────────────────────────

    @Test
    fun `refreshedUserIds names exactly the members this refresh produced`() {
        assertEquals(setOf("alice", "bob"), result(listOf("alice", "bob")).refreshedUserIds)
    }

    /**
     * The central case. A member the response did not mention is not in the set,
     * so the evaluators skip them instead of reading their ageing cached row.
     */
    @Test
    fun `a member absent from the response is not in the set`() {
        assertFalse(
            "a member this refresh said nothing about must not be judged — they are " +
                "paused, have never shared, or were filtered server-side",
            "carol" in result(listOf("alice", "bob")).refreshedUserIds
        )
    }

    @Test
    fun `a refresh that produced nothing names nobody`() {
        assertTrue(result(emptyList(), total = 0).refreshedUserIds.isEmpty())
    }

    /** Duplicate rows for one member collapse; the set is about presence. */
    @Test
    fun `the set is deduplicated`() {
        assertEquals(setOf("alice"), result(listOf("alice", "alice")).refreshedUserIds)
    }

    // ── isComplete ──────────────────────────────────────────────────────────

    /**
     * A row that could not be opened is indistinguishable from a member who went
     * quiet, so one unopened row disqualifies the whole refresh — including for
     * members it DID decrypt.
     */
    @Test
    fun `either failure kind alone disqualifies the refresh`() {
        assertTrue(result(listOf("alice")).isComplete)
        assertFalse(result(listOf("alice"), keyMissingCount = 1).isComplete)
        assertFalse(result(listOf("alice"), decryptionFailures = 1).isComplete)
        assertFalse(
            result(listOf("alice"), keyMissingCount = 1, decryptionFailures = 1).isComplete
        )
    }

    /**
     * The two are separate signals with the same consequence here. Kept apart so
     * neither can be the one that gets forgotten, and because they are different
     * operator problems.
     */
    @Test
    fun `a decrypted member is still not judgeable when another row failed`() {
        val r = result(listOf("alice"), keyMissingCount = 1)
        assertTrue("alice decrypted", "alice" in r.refreshedUserIds)
        assertFalse("but the refresh as a whole is unusable", r.isComplete)
    }
}
