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

package com.pingolin.track.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 90-day retention cleanups run once a day, not on every sync (the 15-minute
 * periodic tick and every expedited syncNow): the rows only age by the day.
 */
class RetentionCleanupPolicyTest {

    private val day = LocationSyncWorker.RETENTION_CLEANUP_INTERVAL_MS
    private val t0 = 1_800_000_000_000L

    @Test
    fun `never run is due`() {
        assertTrue(LocationSyncWorker.retentionCleanupDue(lastRunMs = 0L, nowMs = t0))
    }

    @Test
    fun `within a day of the last run is not due`() {
        assertFalse(LocationSyncWorker.retentionCleanupDue(lastRunMs = t0, nowMs = t0 + day - 1))
    }

    @Test
    fun `a day after the last run is due`() {
        assertTrue(LocationSyncWorker.retentionCleanupDue(lastRunMs = t0, nowMs = t0 + day))
    }

    @Test
    fun `a clock that went backwards does not postpone the cleanups`() {
        assertTrue(LocationSyncWorker.retentionCleanupDue(lastRunMs = t0, nowMs = t0 - 60_000L))
    }
}
