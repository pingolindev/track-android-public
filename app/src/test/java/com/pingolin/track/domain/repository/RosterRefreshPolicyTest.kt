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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary reload re-reads the alerted circles' rosters when the alerted
 * circles change, and otherwise once an hour rather than on every tick, to save
 * battery: a member who joins is still in an alert's scope within the hour
 * without anyone opening a screen.
 */
class RosterRefreshPolicyTest {

    private val hour = BoundaryRepository.ROSTER_MAX_AGE_MS

    @Test
    fun `nothing to read when no alert names a circle`() {
        assertFalse(BoundaryRepository.rosterRefreshDue(emptySet(), emptySet(), 0L, 5_000L))
    }

    @Test
    fun `the first reload reads the rosters`() {
        assertTrue(BoundaryRepository.rosterRefreshDue(setOf("c-1"), emptySet(), 0L, 5_000L))
    }

    @Test
    fun `an unchanged alert set inside the hour skips the read`() {
        assertFalse(BoundaryRepository.rosterRefreshDue(setOf("c-1"), setOf("c-1"), 1_000L, 1_000L + hour - 1))
    }

    @Test
    fun `an unchanged alert set is re-read once the hour is up`() {
        assertTrue(BoundaryRepository.rosterRefreshDue(setOf("c-1"), setOf("c-1"), 1_000L, 1_000L + hour))
    }

    @Test
    fun `a new alerted circle is read at once`() {
        assertTrue(BoundaryRepository.rosterRefreshDue(setOf("c-1", "c-2"), setOf("c-1"), 1_000L, 1_001L))
    }
}
