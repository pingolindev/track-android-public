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

package com.pingolin.track.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which members of each circle share only an APPROXIMATE location, and how large to draw them.
 *
 * The server says it beside every row of the latest-locations response: `sharing_mode`
 * "approximate" and `approximate_radius_miles`, the member's radius varied ±20 % afresh on every
 * response so a fixed value can't fingerprint them. The map draws such a member as an area round
 * the point received (ui/map/ApproximateArea.kt).
 *
 * The area must hold still while the wire value wobbles, so per member this keeps the LARGEST
 * radius seen while they stay approximate, and forgets it as soon as a response says they are
 * not ("exact", "paused", or no mode at all). A member missing from a
 * response is kept: their last point is still on the map, and it is still a snapped one.
 *
 * On disk as well as in memory, so a map opened offline, or before its first refresh, draws an
 * approximate member's last point as an area too. Per account: cleared at sign-out with the
 * rest of the location state ([com.pingolin.track.domain.repository.LocationRepository.wipeInMemory]).
 *
 * An area is never centred on a PRECISE point ([displayPoint]): right after someone switches to
 * approximate, the server labels their last stored fix "approximate" though they sent it exact.
 * A point a sender snapped lies on a sender grid and is drawn as is; any other is snapped here
 * once, with the senders' own formula, and held for that fix.
 */
@Singleton
class ApproximateMembersStore private constructor(private val prefs: SharedPreferences?) {

    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** What the server said beside one fix: whose it is, their sharing mode and radius. */
    data class Report(val userId: String, val sharingMode: String?, val radiusMiles: Int?)

    private val lock = Any()

    /** The radius the latest response reported per member ("circle|member"), what a snap uses. */
    private val lastReported = HashMap<String, Int>()

    /** The point drawn for one device's fix ("circle|member|device") that had to be snapped here. */
    private val held = HashMap<String, Held>()

    private data class Held(val timestamp: Long, val lat: Double, val lng: Double)

    private val _areas = MutableStateFlow(load())

    /**
     * Circle -> member -> radius in miles, for approximate members only. Both keyed by
     * [AccountIds.storeKey]; read through [radiusMiles].
     */
    val areas: StateFlow<Map<String, Map<String, Int>>> = _areas.asStateFlow()

    /** [userId]'s radius in [circleId] while they share an approximate location there, else null. */
    fun radiusMiles(circleId: String, userId: String): Int? =
        _areas.value[AccountIds.storeKey(circleId)]?.get(AccountIds.storeKey(userId))

    /** Take in one latest-locations response for [circleId]: a [Report] per row this device opened. */
    fun record(circleId: String, reports: List<Report>) {
        if (reports.isEmpty()) return
        val circle = AccountIds.storeKey(circleId)
        synchronized(lock) {
            for ((member, rows) in reports.groupBy { AccountIds.storeKey(it.userId) }) {
                val key = "$circle$SEPARATOR$member"
                val reported = rows.filter { it.sharingMode == APPROXIMATE }.maxOfOrNull { reportedMiles(it.radiusMiles) }
                if (reported != null) {
                    lastReported[key] = reported
                } else {
                    lastReported.remove(key)
                    held.keys.removeAll { it.startsWith("$key$SEPARATOR") }
                }
            }
            val before = _areas.value[circle].orEmpty()
            val after = merge(before, reports)
            if (after == before) return
            write(circle, after)
        }
    }

    /**
     * Keep only [circleIds], this account's WHOLE circle list: a circle it left, was removed from
     * or that was deleted goes, with its members, radii and held points. Never call it with one
     * page of the list.
     */
    fun retainCircles(circleIds: Collection<String>) {
        val keep = circleIds.mapTo(HashSet()) { AccountIds.storeKey(it) }
        synchronized(lock) {
            val gone = _areas.value.keys.filterNot { it in keep }
            if (gone.isEmpty()) return
            prefs?.edit { gone.forEach { remove(it) } }
            gone.forEach { forgetKeys(it) }
            _areas.value = _areas.value - gone.toSet()
        }
    }

    /**
     * Keep only [userIds], [circleId]'s roster as this device shows it: a member who left, was
     * removed or is not shown any more goes, with their radius and held points.
     */
    fun retainMembers(circleId: String, userIds: Collection<String>) {
        val circle = AccountIds.storeKey(circleId)
        val keep = userIds.mapTo(HashSet()) { AccountIds.storeKey(it) }
        synchronized(lock) {
            val before = _areas.value[circle] ?: return
            val after = before.filterKeys { it in keep }
            if (after == before) return
            (before.keys - after.keys).forEach { forgetKeys("$circle$SEPARATOR$it") }
            write(circle, after)
        }
    }

    /** Forget [circleId] altogether: this phone does not hold it any more. */
    fun forgetCircle(circleId: String) {
        val circle = AccountIds.storeKey(circleId)
        synchronized(lock) {
            forgetKeys(circle)
            if (circle in _areas.value) write(circle, emptyMap())
        }
    }

    /** Drop the reported radii and held points under [prefix] ("circle" or "circle|member"). Under [lock]. */
    private fun forgetKeys(prefix: String) {
        lastReported.keys.removeAll { it == prefix || it.startsWith("$prefix$SEPARATOR") }
        held.keys.removeAll { it.startsWith("$prefix$SEPARATOR") }
    }

    /** Store [circle]'s members as [members], on disk and in [areas]. Under [lock]. */
    private fun write(circle: String, members: Map<String, Int>) {
        prefs?.edit {
            if (members.isEmpty()) remove(circle)
            else putStringSet(circle, members.map { (member, miles) -> "$member$SEPARATOR$miles" }.toSet())
        }
        _areas.value = if (members.isEmpty()) _areas.value - circle else _areas.value + (circle to members)
    }

    /** Sign-out: the next account must inherit nothing about this one's circles. */
    fun clear() {
        synchronized(lock) {
            prefs?.edit { clear() }
            lastReported.clear()
            held.clear()
            _areas.value = emptyMap()
        }
    }

    /**
     * Where to draw one fix, [lat]/[lng] from [userId]'s device [deviceId] at [timestamp], in
     * [circleId]. Not an approximate member: as is. On a sender grid ([onSenderGrid]): as is — a
     * sender snapped it. Otherwise it is a precise point labelled approximate, and it is snapped
     * ONCE with the senders' formula, at the radius the response reported (the largest seen
     * before any response, as on a cold start), and that point is held for this fix so a later
     * refresh, reporting another radius, doesn't move it.
     */
    fun displayPoint(
        circleId: String,
        userId: String,
        deviceId: String,
        timestamp: Long,
        lat: Double,
        lng: Double,
    ): Pair<Double, Double> {
        val largest = radiusMiles(circleId, userId) ?: return lat to lng
        if (onSenderGrid(lat)) return lat to lng
        val member = "${AccountIds.storeKey(circleId)}$SEPARATOR${AccountIds.storeKey(userId)}"
        val key = "$member$SEPARATOR$deviceId"
        synchronized(lock) {
            held[key]?.takeIf { it.timestamp == timestamp }?.let { return it.lat to it.lng }
            val miles = lastReported[member] ?: largest
            val snapped = com.pingolin.track.domain.repository.LocationRepository.coarsenLocation(
                lat, lng, miles * com.pingolin.track.domain.repository.LocationRepository.METERS_PER_MILE,
            )
            held[key] = Held(timestamp, snapped.first, snapped.second)
            return snapped
        }
    }

    private fun load(): Map<String, Map<String, Int>> =
        prefs?.all.orEmpty().mapNotNull { (circle, value) ->
            val members = (value as? Set<*>).orEmpty().mapNotNull { parse(it as? String) }.toMap()
            if (members.isEmpty()) null else circle to members
        }.toMap()

    companion object {
        const val APPROXIMATE = "approximate"

        /** The senders' default radius, for an approximate row that carries none. */
        const val DEFAULT_RADIUS_MILES = 1

        /**
         * A sanity bound, far above the server's own 25-mile ceiling: only ever applied to a
         * nonsensical value, which still draws a larger area, never a smaller one.
         */
        const val MAX_RADIUS_MILES = 1000

        private const val PREFS = "approximate_members"
        private const val SEPARATOR = '|'

        /** A store that keeps nothing on disk, for tests. */
        fun inMemory() = ApproximateMembersStore(null)

        /** Every whole-mile radius a sender may snap with: the server's 1..25. */
        private val SENDER_RADII = 1..25

        /**
         * How far from a grid line, in cells, still counts as on it: a snapped latitude travels as
         * i32 × 1e7 (the mobile SDK's location codec), at most 5e-8° off, which is 3.5e-6 of the
         * smallest (1-mile) cell. A precise latitude passes for one of 25 grids about 5e-4 of the
         * time.
         */
        private const val ON_GRID_TOLERANCE = 1e-5

        /**
         * Whether [lat] lies on the grid a sender snaps to for any radius in [SENDER_RADII]
         * (LocationRepository.coarsenLocation: round(lat / cell) × cell, cell = r × 1609.344 m /
         * 111320). Latitude only: a longitude cell depends on the sender's true latitude, which
         * the viewer doesn't have. ALL radii, because the server rounds its ±20 % jitter, so a
         * radius of 3 can be reported as 2 and a snapped point must not be snapped again.
         */
        fun onSenderGrid(lat: Double): Boolean = SENDER_RADII.any { r ->
            val cell = r * com.pingolin.track.domain.repository.LocationRepository.METERS_PER_MILE / 111320.0
            val q = lat / cell
            Math.abs(q - Math.rint(q)) < ON_GRID_TOLERANCE
        }

        /** A reported radius as a snap uses it: the senders' default mile when absent or nonsensical. */
        private fun reportedMiles(radiusMiles: Int?): Int =
            (radiusMiles?.takeIf { it > 0 } ?: DEFAULT_RADIUS_MILES).coerceAtMost(MAX_RADIUS_MILES)

        /**
         * One circle's approximate members after a response's [reports], from [before].
         *
         * A member reported "approximate" keeps the largest radius seen, the one in this response
         * included (absent: [DEFAULT_RADIUS_MILES]); a member reported anything else is forgotten;
         * a member not reported is kept as they were. Rows of one member (one per device) all
         * carry their mode for the circle; should they ever disagree, approximate wins.
         */
        internal fun merge(before: Map<String, Int>, reports: List<Report>): Map<String, Int> {
            val after = before.toMutableMap()
            for ((member, rows) in reports.groupBy { AccountIds.storeKey(it.userId) }) {
                val approximate = rows.filter { it.sharingMode == APPROXIMATE }
                if (approximate.isEmpty()) {
                    after.remove(member)
                    continue
                }
                val received = approximate.maxOf { reportedMiles(it.radiusMiles) }
                after[member] = maxOf(before[member] ?: 0, received)
            }
            return after
        }

        private fun parse(entry: String?): Pair<String, Int>? {
            val i = entry?.lastIndexOf(SEPARATOR) ?: return null
            if (i <= 0) return null
            val miles = entry.substring(i + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
            return entry.substring(0, i) to miles
        }
    }
}
