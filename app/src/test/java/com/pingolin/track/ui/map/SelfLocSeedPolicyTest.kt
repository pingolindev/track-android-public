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

package com.pingolin.track.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map persists its own position as the next cold start's "open here" seed.
 * Rewriting that seed is an EncryptedSharedPreferences write to the same file as
 * the SDK's keys, from a collector that runs in the background too, so it is
 * rewritten only for a real move, never on every emission.
 */
class SelfLocSeedPolicyTest {

    private val home = 37.7749 to -122.4194
    private val t0 = 1_000_000L

    @Test
    fun `the first position of a map session is always written`() {
        assertTrue(MapFragment.shouldPersistSelfLoc(null, 0L, home.first, home.second, t0))
    }

    @Test
    fun `a position that has not moved is never rewritten`() {
        assertFalse(MapFragment.shouldPersistSelfLoc(home, t0, home.first, home.second, t0 + 10 * 60_000L))
    }

    @Test
    fun `a move beyond the threshold is written at once`() {
        // ~0.0002 deg latitude is ~22 m.
        assertTrue(MapFragment.shouldPersistSelfLoc(home, t0, home.first + 0.0002, home.second, t0 + 1_000L))
    }

    @Test
    fun `a small move waits for the interval`() {
        // ~0.00005 deg latitude is ~5.5 m.
        val nudged = home.first + 0.00005
        assertFalse(MapFragment.shouldPersistSelfLoc(home, t0, nudged, home.second, t0 + 10_000L))
        assertTrue(
            MapFragment.shouldPersistSelfLoc(
                home, t0, nudged, home.second, t0 + MapFragment.SELF_LOC_PERSIST_MIN_INTERVAL_MS
            )
        )
    }
}
