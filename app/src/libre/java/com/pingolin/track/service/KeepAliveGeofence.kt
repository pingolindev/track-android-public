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

package com.pingolin.track.service

import android.content.Context

/**
 * No geofence on builds without Play services: the platform's own proximity alerts carry no
 * allowlist to start a foreground service, so they could bring nothing back.
 * [LocationKeepAlive]'s deliveries do, on this build too.
 */
object KeepAliveGeofence {
    const val ACTION_EXIT = "com.pingolin.track.KEEP_ALIVE_EXIT"

    const val RADIUS_METERS = 200f

    @Suppress("UNUSED_PARAMETER")
    fun arm(context: Context, latitude: Double, longitude: Double) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun disarm(context: Context) = Unit
}
