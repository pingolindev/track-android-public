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
import java.util.concurrent.ConcurrentHashMap

/**
 * The viewer's side of the re-seal after a rotation: across a circle's rotation, a frozen last location
 * this phone already opened and drew stays drawn for at most [GRACE_MS], in memory only, until the
 * member's phone re-seals it under the new key. Without it the marker would blink out at every rotation
 * for as long as that takes.
 *
 * Counted per row from when this phone first carried it, which is its first read after this phone holds
 * the newer version (it can't know when the server rotated): never longer, however many rotations
 * follow. Only for a member the server still serves frozen there (real-time sharing still off), whose
 * row now opens only under an older version; never someone this phone doesn't show. Gone with the circle
 * and at sign-out.
 */
internal class FrozenGrace(private val graceMs: Long = GRACE_MS) {

    /** "{circle}|{member}" → when this phone first carried their row. */
    private val firstCarried = ConcurrentHashMap<String, Long>()

    /**
     * The rows of [previous] (what was drawn for [circleId] before this read) still drawn after it: their
     * member isn't among [current] (the rows that open under the current key), is in [staleServed] (served
     * frozen, opening only under an older version), and was first carried less than [graceMs] ago. Nothing
     * while the circle's current version ([active]) can't be read.
     */
    fun carried(
        circleId: String,
        active: Int?,
        now: Long,
        previous: List<LocationEntity>,
        current: List<LocationEntity>,
        staleServed: Set<String>,
    ): List<LocationEntity> {
        if (active == null) return emptyList()
        val drawnNow = current.mapTo(HashSet()) { it.userId }
        // Re-sealed, or no longer served frozen: their grace is over, and a later one starts afresh.
        firstCarried.keys.removeIf { key ->
            val (circle, member) = key.split('|', limit = 2)
            circle == circleId && (member in drawnNow || member !in staleServed)
        }
        return previous.filter { row ->
            if (row.userId in drawnNow || row.userId !in staleServed) return@filter false
            val first = firstCarried.getOrPut("$circleId|${row.userId}") { now }
            now - first < graceMs
        }
    }

    fun forget(circleId: String) {
        firstCarried.keys.removeIf { it.startsWith("$circleId|") }
    }

    fun clear() {
        firstCarried.clear()
    }

    companion object {
        /** The bound on a frozen marker kept across a rotation. */
        const val GRACE_MS = 15 * 60_000L
    }
}
