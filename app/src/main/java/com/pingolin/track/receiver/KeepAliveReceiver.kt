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
import android.util.Log
import androidx.core.content.ContextCompat
import com.pingolin.track.service.KeepAliveGeofence
import com.pingolin.track.service.LocationKeepAlive
import com.pingolin.track.service.LocationPolicy
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.NotSharingNotice
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Where the keep-alive's deliveries land ([LocationKeepAlive]), and its geofence's EXIT on Play
 * services builds ([KeepAliveGeofence]): inside the platform's allowance for each, the location
 * service starts again if sharing is wanted and it isn't running.
 *
 * It decides with the watchdog's one rule, [SessionManager.shouldBeTracking], asked of the store
 * first, and starts with the plain ACTION_START: it never touches a pause, a privacy setting or a
 * circle's sharing (those stay enforced where each position is sent). It never reads the location
 * the platform delivers, and sends or stores nothing.
 */
@AndroidEntryPoint
class KeepAliveReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LocationKeepAlive.ACTION_KEEP_ALIVE && intent.action != KeepAliveGeofence.ACTION_EXIT) return
        // The store first, and each later question only when the one before let it through.
        val decision = decide(
            storeRefused = { sessionManager.storeRefusal() != null },
            wanted = { sessionManager.shouldBeTracking() },
            serviceRunning = { LocationTrackingService.isRunning },
            allowedAllTheTime = {
                LocationPolicy.hasPreciseLocation(context) && LocationPolicy.hasBackgroundLocation(context)
            },
        )
        when (decision) {
            // The store can't be opened now: no start, no crash. The watchdog's soonest look
            // starts tracking once it opens, and says so meanwhile.
            Decision.LOOK_SOON -> WatchdogAlarmReceiver.scheduleSoon(context)
            // Not wanted (signed out, or the device-wide pause an older app version set):
            // nothing to bring back, so the request goes too, and so does a notice that
            // sharing stopped.
            Decision.NOT_WANTED -> {
                NotSharingNotice.clear(context)
                LocationKeepAlive.unregister(context)
                KeepAliveGeofence.disarm(context)
            }
            Decision.RUNNING -> Unit
            // Without precise location all the time the service can't be made from here, and a start
            // would only stop again. The request goes, so nothing wakes for nothing; the next start in
            // front holds it again.
            Decision.NOT_ALLOWED -> {
                LocationKeepAlive.unregister(context)
                KeepAliveGeofence.disarm(context)
            }
            Decision.START -> try {
                val start = Intent(context, LocationTrackingService::class.java)
                    .apply { action = LocationTrackingService.ACTION_START }
                ContextCompat.startForegroundService(context, start)
                Log.i(TAG, "Keep-alive: restarted LocationTrackingService")
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException where a platform gives no allowlist with
                // the delivery: the watchdog's notice and the heartbeat remain the fallback.
                Log.w(TAG, "Keep-alive: start refused: ${e.javaClass.simpleName}")
            }
        }
    }

    internal enum class Decision { LOOK_SOON, NOT_WANTED, RUNNING, NOT_ALLOWED, START }

    internal companion object {
        private const val TAG = "KeepAliveReceiver"

        /** What a delivery does, in this order. Pure: the order is pinned by a test, not by reading. */
        internal fun decide(
            storeRefused: () -> Boolean,
            wanted: () -> Boolean,
            serviceRunning: () -> Boolean,
            allowedAllTheTime: () -> Boolean,
        ): Decision = when {
            storeRefused() -> Decision.LOOK_SOON
            !wanted() -> Decision.NOT_WANTED
            serviceRunning() -> Decision.RUNNING
            !allowedAllTheTime() -> Decision.NOT_ALLOWED
            else -> Decision.START
        }
    }
}
