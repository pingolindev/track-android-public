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

import java.util.Locale

/**
 * What the map may say about one member's latest location.
 *
 * A member who shares an approximate location in a circle ("Bubble Mode") sends a point their
 * phone has already snapped to a grid whose cell is their radius r, so they are anywhere
 * within 0.71 r of it. Drawn as a pin with an address, that point would send people to a
 * street corner nearly a kilometre from the person. So such a member is an area, and nothing
 * finer.
 */
internal sealed interface MemberSpot {

    /**
     * Whether anything finer than the marker may be shown or handed on: the point's address
     * and coordinates, the saved place it falls in, Share as Destination, Copy Address.
     */
    val showsPoint: Boolean

    /** Where their phone put them: the pin, its address and coordinates, "At <Place>". */
    data object Exact : MemberSpot {
        override val showsPoint = true
    }

    /**
     * Somewhere in [radiusMeters] of the point received: an area centred on it, the words
     * "Approximate location", and nothing finer — no address, no coordinates, no place worked
     * out from the point, nothing that hands the point on.
     */
    data class Approximate(val radiusMeters: Double) : MemberSpot {
        override val showsPoint = false
    }

    companion object {
        /**
         * [approximateRadiusMiles] is the largest radius seen while the member shares
         * approximately ([com.pingolin.track.util.ApproximateMembersStore]), null otherwise.
         * This phone's own marker is always exact: it knows where it is.
         */
        fun of(isSelf: Boolean, approximateRadiusMiles: Int?): MemberSpot = when {
            isSelf || approximateRadiusMiles == null -> Exact
            else -> Approximate(ApproximateArea.radiusMeters(approximateRadiusMiles))
        }
    }
}

/** The area drawn for a [MemberSpot.Approximate] member, as plain numbers the tests can check. */
internal object ApproximateArea {

    /**
     * The area is 1.25 × the member's radius. The server varies the radius it sends by ±20 %, so
     * at the low end that is still 1.25 × 0.8 = 1 × r, and the snapping moves nobody further
     * than 0.71 r from the point.
     */
    const val AREA_FACTOR = 1.25

    const val METERS_PER_MILE = 1609.344

    /** Segments in the drawn outline: smooth at any zoom a whole area fits on screen. */
    const val RING_SEGMENTS = 64

    private const val EARTH_RADIUS_M = 6_371_008.8

    /** The area's radius in meters for a member whose radius is [radiusMiles]. */
    fun radiusMeters(radiusMiles: Int): Double = AREA_FACTOR * radiusMiles * METERS_PER_MILE

    /**
     * The area's outline round [lat], [lng]: [segments] points [radiusM] away along great
     * circles, so it stays a circle on the ground at any latitude, closed (the first point
     * again at the end), each as `[lng, lat]` like GeoJSON.
     */
    fun ring(lat: Double, lng: Double, radiusM: Double, segments: Int = RING_SEGMENTS): List<DoubleArray> {
        val lat1 = Math.toRadians(lat)
        val lng1 = Math.toRadians(lng)
        val d = radiusM / EARTH_RADIUS_M
        return (0..segments).map { i ->
            val bearing = 2.0 * Math.PI * (i % segments) / segments
            val lat2 = Math.asin(Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(bearing))
            val lng2 = lng1 + Math.atan2(
                Math.sin(bearing) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2),
            )
            doubleArrayOf(Math.toDegrees(lng2), Math.toDegrees(lat2))
        }
    }

    /** A bounding box in degrees. */
    data class Box(val south: Double, val west: Double, val north: Double, val east: Double)

    /** The box round the outline [ring] draws: what "show everyone" has to fit. */
    fun box(lat: Double, lng: Double, radiusM: Double): Box {
        val points = ring(lat, lng, radiusM)
        return Box(
            south = points.minOf { it[1] },
            west = points.minOf { it[0] },
            north = points.maxOf { it[1] },
            east = points.maxOf { it[0] },
        )
    }

    /**
     * One area as a GeoJSON Feature string for the map's area source. A string, not a
     * MapLibre FeatureCollection: the members' sources are fed strings (see
     * MapFragment.updateMemberMarkers) and numbers are written in plain decimals, never the
     * scientific notation MapLibre's parser rejects. [color] is "#RRGGBB".
     */
    fun featureJson(color: String, lat: Double, lng: Double, radiusM: Double): String {
        val coordinates = ring(lat, lng, radiusM).joinToString(",") {
            String.format(Locale.US, "[%.6f,%.6f]", it[0], it[1])
        }
        return """{"type":"Feature","properties":{"color":"$color"},""" +
            """"geometry":{"type":"Polygon","coordinates":[[$coordinates]]}}"""
    }
}
