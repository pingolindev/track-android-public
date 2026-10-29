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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * What this phone lets Track do with its location: what the map's "Location is off" line says,
 * and what turning it on takes, so "Not now" at the first question is never the end of it.
 */
enum class LocationAccess {
    /** Precise location allowed and Location Services on: nothing to say. */
    ON,

    /** No location permission ("Not now", or Don't allow): Track asks again, or Settings where it won't. */
    NOT_ALLOWED,

    /** Approximate only: far too rough to share, so nothing is recorded. Settings, where precise is turned on. */
    APPROXIMATE,

    /** Allowed, but Location Services are off on this phone. */
    SERVICES_OFF;

    companion object {
        fun of(context: Context): LocationAccess {
            fun granted(permission: String) =
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            return of(
                fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
                coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
                servicesOn = servicesOn(context),
            )
        }

        /** Whether Location Services are on on this phone; on when it can't be read. */
        fun servicesOn(context: Context): Boolean = runCatching {
            context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled)
        }.getOrNull() ?: true

        internal fun of(fine: Boolean, coarse: Boolean, servicesOn: Boolean): LocationAccess = when {
            !fine && !coarse -> NOT_ALLOWED
            !fine -> APPROXIMATE
            !servicesOn -> SERVICES_OFF
            else -> ON
        }
    }
}

/**
 * What asking for location shows (MainActivity.askForLocation). [ANDROID]: Android's own
 * question alone, without Track's.
 */
enum class LocationAsk { NOTHING, QUESTION, ANDROID, PRECISE, SERVICES }

/**
 * What asking for location shows with [access]. [askedBefore]: Android's question was asked on this
 * install; [androidExplains]: shouldShowRequestPermissionRationale. That is false before the first
 * ask, and after a refusal for good, but also after an "Only this time" that ended, a question put
 * aside unanswered, and Android's reset of an unused app's permissions, where Android does ask
 * again. So asked before and not explaining means only that Android MAY not ask: its own question
 * goes alone. Track's would end "Android will ask next" just before Android shows nothing. Where
 * Android shows nothing, its answer offers its settings page, the one place "Android won't ask
 * again" is then true; where it asks, the person answers it.
 */
fun locationAsk(access: LocationAccess, askedBefore: Boolean, androidExplains: Boolean): LocationAsk = when (access) {
    LocationAccess.ON -> LocationAsk.NOTHING
    LocationAccess.NOT_ALLOWED -> if (askedBefore && !androidExplains) LocationAsk.ANDROID else LocationAsk.QUESTION
    LocationAccess.APPROXIMATE -> LocationAsk.PRECISE
    LocationAccess.SERVICES_OFF -> LocationAsk.SERVICES
}
