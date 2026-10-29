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
import android.content.Intent
import com.pingolin.track.receiver.WatchdogAlarmReceiver
import com.pingolin.track.worker.LocationHeartbeatWorker
import com.pingolin.track.worker.LocationSyncWorker
import com.pingolin.track.worker.NotificationTickleWorker

/**
 * Everything of Track's that runs by itself for a session, stopped: location sharing, everything that brings
 * it back (the keep-alive request and its geofence, the heartbeat, the watchdog), the queue's sync and the
 * notification poll. The same list BootReceiver arms.
 *
 * Both sign-outs end with it (AuthRepository.clearLocalData, and TrackApplication's forced one), and so does
 * learning that an admin hasn't approved this account
 * ([com.pingolin.track.util.SessionManager.keepRegistrationReview]): an older app version, which couldn't
 * know, may have started it all. It stops, and wipes nothing: the session, the keys and every row stay. Each
 * step on its own, so one that fails stops none of the others.
 */
object TrackingStop {

    fun all(context: Context) {
        // Location sampling first, so nothing it takes is left to be sent. Only while it runs: a plain
        // startService must never cold-start the service from the background.
        runCatching {
            if (LocationTrackingService.isRunning) {
                context.startService(
                    Intent(context, LocationTrackingService::class.java).apply {
                        action = LocationTrackingService.ACTION_STOP
                    }
                )
            }
        }
        // Each of these brings the service back, or calls Track with this session, by itself and for
        // as long as it is left armed: nothing else ever cancels it.
        //  - The keep-alive location request and its geofence.
        runCatching { LocationKeepAlive.unregister(context) }
        runCatching { KeepAliveGeofence.disarm(context) }
        //  - SECURITY: the heartbeat, a 15-minute periodic worker that starts the service again and sends a fix.
        runCatching { LocationHeartbeatWorker.cancel(context) }
        //  - SECURITY: the watchdog, a 20-minute alarm that punches through Doze and starts the service again.
        runCatching { WatchdogAlarmReceiver.cancel(context) }
        //  - The queue's sync, which would wake to send a backlog that nothing may send now.
        runCatching { LocationSyncWorker.cancel(context) }
        //  - The notification poll, which wakes every 15 minutes to ask Track with this session.
        runCatching { NotificationTickleWorker.cancelPolling(context) }
    }
}
