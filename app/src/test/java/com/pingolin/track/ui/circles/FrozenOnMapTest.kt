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

package com.pingolin.track.ui.circles

import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.LocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Someone with real-time sharing off is drawn where they were when they turned it off, from the
 * server's frozen row of THAT circle (per circle, never by member alone), greyed and labelled
 * "Location sharing off", and never as where they are now.
 */
class FrozenOnMapTest {

    private val now = 10_000L

    private fun member(circleId: String, sharing: String?, pausedUntil: Long? = null, userId: String = "x") =
        CircleMemberEntity(
            circleId = circleId, userId = userId, displayName = "X", role = "member", joinedAt = 0,
            locationSharing = sharing, sharingPausedUntil = pausedUntil,
        )

    private fun row(circleId: String, userId: String = "x") = LocationEntity(
        circleId = circleId, userId = userId, deviceId = "d", latitude = 1.0, longitude = 2.0,
        altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = 1L,
    )

    @Test
    fun `a member with real-time sharing off here is drawn at their frozen row`() {
        assertEquals(listOf(row("a")), MemberSharing.frozenOnMap(listOf(member("a", "off")), listOf(row("a")), "me", now))
    }

    @Test
    fun `never another circle's frozen row`() {
        assertEquals(emptyList<LocationEntity>(), MemberSharing.frozenOnMap(listOf(member("b", "off")), listOf(row("a")), "me", now))
    }

    @Test
    fun `never someone paused, sharing again, or this account`() {
        assertEquals(emptyList<LocationEntity>(), MemberSharing.frozenOnMap(listOf(member("a", "paused")), listOf(row("a")), "me", now))
        assertEquals(emptyList<LocationEntity>(), MemberSharing.frozenOnMap(listOf(member("a", null)), listOf(row("a")), "me", now))
        assertEquals(
            emptyList<LocationEntity>(),
            MemberSharing.frozenOnMap(listOf(member("a", "off", userId = "me")), listOf(row("a", userId = "me")), "me", now),
        )
    }

    @Test
    fun `the map draws it greyed and labelled, and never as where they are now`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        // The marker: grey, "Location sharing off", no place, no heading or accuracy, no SOS.
        assertTrue(map.contains("val isStale = frozen || ageMs > 15 * 60_000L"))
        assertTrue(map.contains("val caption = if (frozen) getString(R.string.member_sharing_off) else formatRelativeAge(ageMs)"))
        assertTrue(map.contains("val atPlaceName = if (!showsPoint || frozen) null"))
        assertTrue(map.contains("if (showsPoint && !frozen) {"))
        assertTrue(map.contains("val sosIds = sosMemberIds().filterNot { viewModel.drawsFrozen(it) }.toSet()"))
        // It covers no place pin and takes no SOS merge.
        assertTrue(map.contains("val memberLocations = viewModel.memberLocations.value.filterNot { viewModel.drawsFrozen(it.userId) }"))
        assertTrue(map.contains("firstOrNull { it.userId == alert.userId && !viewModel.drawsFrozen(it.userId) }"))
        // Its card: no "At <Place>", nothing to share as where they are.
        assertTrue(map.contains("val atPlace = if (!showsPoint || frozen) null"))
        assertTrue(map.contains("if (frozen) btnShareDest.visibility = View.GONE"))
    }
}
