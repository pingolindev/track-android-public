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

package com.pingolin.track.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pingolin.track.worker.CheckInZoneWorker

/**
 * The phone's time zone changed: this account's check-in rules that follow the phone move with it
 * (CheckInZoneWorker). One of the broadcasts a manifest receiver still gets on Android 8 and later.
 */
class TimeZoneChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        CheckInZoneWorker.kick(context)
    }
}
