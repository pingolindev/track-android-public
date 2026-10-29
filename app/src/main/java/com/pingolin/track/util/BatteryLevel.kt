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
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This phone's battery level now, for a location sent outside the tracking service: Send
 * location now, and the map's refresh when it opens. A watcher judges the newest location, so
 * a person low on battery who keeps glancing at Track would send locations with no level
 * exactly then, and their low-battery alert would stay quiet. Whether a circle gets it is
 * each circle's own setting, applied where the location is sealed
 * (LocationRepository.sendLocation).
 */
@Singleton
class BatteryLevel @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** In percent, from Android's sticky battery broadcast; null when it doesn't say. */
    fun now(): Int? = try {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            percent(it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), it.getIntExtra(BatteryManager.EXTRA_SCALE, -1))
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        /** [level] of [scale], in percent, as the tracking service reads it; null when either is missing. */
        fun percent(level: Int, scale: Int): Int? = if (level >= 0 && scale > 0) level * 100 / scale else null
    }
}
