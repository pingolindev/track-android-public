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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the pause predicate every outbound send path gates on.
 *
 * A pause is a MODE plus an OPTIONAL deadline. `pausedUntil != null && pausedUntil > now`
 * reads an indefinite pause — `sharing_mode = 'paused'` with a NULL deadline, the row the
 * server writes for "paused until I resume" — as NOT paused, and would keep uploading exact
 * fixes to a circle the user had stopped sharing with.
 *
 * The cases below are the server's own visibility rule, stated from the sender's side: a
 * `paused` row counts as sharing again only when its `paused_until` is set and has passed.
 */
class SharingPauseStateTest {

    private val now = 1_700_000_000_000L

    private fun row(mode: String, pausedUntil: Long?) = PrivacySettingEntity(
        circleId = "circle-1",
        sharingMode = mode,
        pausedUntil = pausedUntil,
        updatedAt = now
    )

    /** The case this whole predicate exists for. */
    @Test
    fun `paused with no deadline is paused`() {
        assertTrue(row(PrivacySettingEntity.MODE_PAUSED, null).isSharingPausedAt(now))
    }

    @Test
    fun `paused with a future deadline is paused`() {
        assertTrue(row(PrivacySettingEntity.MODE_PAUSED, now + 60_000).isSharingPausedAt(now))
    }

    /** Auto-resume: the server shows a paused member again once the deadline passes. */
    @Test
    fun `paused with an elapsed deadline is not paused`() {
        assertFalse(row(PrivacySettingEntity.MODE_PAUSED, now - 60_000).isSharingPausedAt(now))
    }

    /** The deadline is exclusive on both sides, matching `paused_until <= NOW()`. */
    @Test
    fun `paused exactly at the deadline is not paused`() {
        assertFalse(row(PrivacySettingEntity.MODE_PAUSED, now).isSharingPausedAt(now))
    }

    /**
     * A deadline left behind by an earlier pause does not re-pause a circle whose mode
     * has moved on — the server reads `sharing_mode` alone to decide posture, and the
     * privacy PUT round-trips `paused_until` through mode changes.
     */
    @Test
    fun `a stale future deadline in another mode is not paused`() {
        assertFalse(row(PrivacySettingEntity.MODE_EXACT, now + 60_000).isSharingPausedAt(now))
        assertFalse(row(PrivacySettingEntity.MODE_APPROXIMATE, now + 60_000).isSharingPausedAt(now))
    }

    @Test
    fun `the ordinary sharing modes are not paused`() {
        assertFalse(row(PrivacySettingEntity.MODE_EXACT, null).isSharingPausedAt(now))
        assertFalse(row(PrivacySettingEntity.MODE_APPROXIMATE, null).isSharingPausedAt(now))
    }

    /** The entity default must not read as paused for a freshly seeded row. */
    @Test
    fun `the entity default mode is not paused`() {
        assertFalse(PrivacySettingEntity(circleId = "c", updatedAt = now).isSharingPausedAt(now))
    }

    /**
     * When a pause ends the phone sends again before its row says anything but "paused", so the
     * bubble comes from the mode held before the pause, as the server restores it.
     */
    @Test
    fun `a bubble goes out for approximate and for a pause that held a bubble before it`() {
        assertTrue(row(PrivacySettingEntity.MODE_APPROXIMATE, null).sendsBubble())
        assertFalse(row(PrivacySettingEntity.MODE_EXACT, null).sendsBubble())
        val ended = row(PrivacySettingEntity.MODE_PAUSED, now - 1)
        assertFalse(ended.isSharingPausedAt(now))
        assertTrue(ended.copy(modeBeforePause = PrivacySettingEntity.MODE_APPROXIMATE).sendsBubble())
        assertFalse(ended.copy(modeBeforePause = PrivacySettingEntity.MODE_EXACT).sendsBubble())
        assertFalse("not known: exact, as the server restores it", ended.sendsBubble())
        assertFalse("a stale mode outside a pause counts for nothing",
            row(PrivacySettingEntity.MODE_EXACT, null).copy(modeBeforePause = PrivacySettingEntity.MODE_APPROXIMATE).sendsBubble())
    }
}
