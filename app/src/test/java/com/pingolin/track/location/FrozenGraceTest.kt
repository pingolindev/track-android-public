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

package com.pingolin.track.location

import com.pingolin.track.data.local.entity.LocationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's side of re-sealing a frozen last location: across a rotation, a frozen last location this
 * phone already drew stays drawn for at most 15 minutes from when it first carried it, until its
 * member's phone re-seals it under the new key, however many rotations follow.
 */
class FrozenGraceTest {

    private companion object {
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val T0 = 1_760_000_000_000L
        const val MINUTE = 60_000L
    }

    private fun row(user: String) = LocationEntity(
        circleId = CIRCLE, userId = user, deviceId = "", latitude = 37.33, longitude = -122.0,
        altitude = null, speed = null, heading = null, accuracy = 10.0, batteryLevel = null,
        timestamp = T0 - 3_600_000L,
    )

    private val bo = row("bo")

    @Test
    fun `a drawn frozen row stays drawn across a rotation, a while`() {
        val grace = FrozenGrace()

        // The first read after the rotation: Bo's row opens only under the older version.
        assertEquals(listOf(bo), grace.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo")))
        assertEquals(listOf(bo), grace.carried(CIRCLE, 3, T0 + 14 * MINUTE, listOf(bo), emptyList(), setOf("bo")))
    }

    @Test
    fun `never past 15 minutes from its first carry`() {
        val grace = FrozenGrace()
        grace.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo"))

        assertTrue(grace.carried(CIRCLE, 3, T0 + 15 * MINUTE, listOf(bo), emptyList(), setOf("bo")).isEmpty())
    }

    /** Rotations closer together than the grace never stretch it. */
    @Test
    fun `a second rotation doesn't stretch a carried row past 15 minutes from its first carry`() {
        val grace = FrozenGrace()
        grace.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo"))

        // Ten minutes on, the circle rotates again; the row is still carried until its own 15 minutes end.
        assertEquals(listOf(bo), grace.carried(CIRCLE, 4, T0 + 10 * MINUTE, listOf(bo), emptyList(), setOf("bo")))
        assertTrue(grace.carried(CIRCLE, 4, T0 + 16 * MINUTE, listOf(bo), emptyList(), setOf("bo")).isEmpty())
    }

    @Test
    fun `never for someone no longer served frozen, nor once their row opens under the new key`() {
        val grace = FrozenGrace()
        grace.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo"))

        // Sharing again, gone, or someone this phone doesn't show: not served frozen.
        assertTrue(grace.carried(CIRCLE, 3, T0 + MINUTE, listOf(bo), emptyList(), emptySet()).isEmpty())
        // Re-sealed: the new row is drawn, and the earlier one isn't
        // carried beside it.
        val resealed = row("bo").copy(latitude = 37.34)
        assertTrue(grace.carried(CIRCLE, 3, T0 + 2 * MINUTE, listOf(bo), listOf(resealed), setOf("bo")).isEmpty())
    }

    @Test
    fun `a grace that ended starts afresh only after the member was drawn again`() {
        val grace = FrozenGrace()
        grace.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo"))
        assertTrue(grace.carried(CIRCLE, 3, T0 + 20 * MINUTE, listOf(bo), emptyList(), setOf("bo")).isEmpty())

        // Re-sealed and drawn current, then a later rotation: a new grace for the new row.
        grace.carried(CIRCLE, 3, T0 + 21 * MINUTE, emptyList(), listOf(bo), emptySet())
        assertEquals(listOf(bo), grace.carried(CIRCLE, 4, T0 + 60 * MINUTE, listOf(bo), emptyList(), setOf("bo")))
    }

    @Test
    fun `nothing with no row drawn before, with the version unreadable, nor after forget or sign-out`() {
        assertTrue(FrozenGrace().carried(CIRCLE, 3, T0, emptyList(), emptyList(), setOf("bo")).isEmpty())
        assertTrue(FrozenGrace().carried(CIRCLE, null, T0, listOf(bo), emptyList(), setOf("bo")).isEmpty())

        val forgotten = FrozenGrace().also {
            it.carried(CIRCLE, 3, T0, listOf(bo), emptyList(), setOf("bo"))
            it.forget(CIRCLE)
        }
        // The circle's rows went with it (LocationRepository.forgetCircleLocations): nothing drawn to carry.
        assertTrue(forgotten.carried(CIRCLE, 3, T0 + MINUTE, emptyList(), emptyList(), setOf("bo")).isEmpty())
    }
}
