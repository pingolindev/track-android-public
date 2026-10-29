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
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.NotSharingNotice
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import com.pingolin.track.worker.LocationHeartbeatWorker
import com.pingolin.track.worker.LocationSyncWorker
import com.pingolin.track.worker.NotificationTickleWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i("BootReceiver", "Received ${intent.action} — restoring services")
            // The session store can't be opened now: no crash. Sharing doesn't wait
            // silently: the notice says so, from no store, and arms the watchdog's
            // soonest look, which restores tracking once it opens.
            if (sessionManager.storeRefusal() != null) {
                NotSharingNotice.show(context)
                return
            }
            // Open: any notice left from an earlier refusal is taken down. A start below that meets
            // one says it again.
            NotSharingNotice.clear(context)
            // Nothing for an assisted-lane account an admin hasn't approved, waiting or turned
            // down: Track refuses its every call, and its app shows only its own screen. The app
            // starts it all once it opens on an approval.
            if (sessionManager.isRegistrationUnapproved()) return
            // Only restart services if user was logged in
            if (sessionManager.isLoggedIn()) {
                // Always restart tracking — it runs while logged in
                val batterySaver = sessionManager.isBatterySaverEnabled()
                val serviceIntent = Intent(context, LocationTrackingService::class.java).apply {
                    action = LocationTrackingService.ACTION_START
                    putExtra(LocationTrackingService.EXTRA_BATTERY_SAVER, batterySaver)
                }
                context.startForegroundService(serviceIntent)
                // Should that start be refused (a store that won't open yet, the service dying early),
                // the keep-alive brings it back within its interval.
                if (sessionManager.shouldBeTracking()) {
                    com.pingolin.track.service.LocationKeepAlive.register(
                        context, batterySaver, sessionManager.getBatterySaverInterval(),
                    )
                    // A reboot and an update take geofences away: armed again
                    // around the last position sent, until the service's first fix
                    // moves it.
                    sessionManager.getLastHistorySentPosition()?.let {
                        com.pingolin.track.service.KeepAliveGeofence.arm(context, it.latitude, it.longitude)
                    }
                }

                // On a package replacement, WorkManager's UPDATE policy can
                // leave stale JobScheduler entries pointing at nothing:
                // "Job didn't exist in JobStore" warnings, periodic workers
                // that never fire, and a send blackout because
                // LocationSyncWorker can't drain the local pending queue. A
                // clean cancel+enqueue on package update specifically
                // avoids this. The visible cost is one extra "next-run in
                // 15 min" cycle (instead of carrying the existing schedule
                // forward), a reasonable trade for guaranteed JobStore
                // health.
                if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Log.i("BootReceiver", "Package replaced — clearing stale WorkManager schedules before re-arming")
                    LocationHeartbeatWorker.cancel(context)
                    LocationSyncWorker.cancel(context)
                    NotificationTickleWorker.cancelPolling(context)
                }

                // Schedule heartbeat worker for periodic location updates
                // even when the foreground service isn't running
                LocationHeartbeatWorker.schedule(context)

                // Schedule the AlarmManager watchdog as a third-line
                // defense — heartbeat WorkManager ticks can be deferred
                // multi-hour by aggressive OEM Doze, and after a fresh
                // boot the FG service may also fail to start (e.g. the
                // user hasn't unlocked yet). The watchdog fires after ~20
                // minutes without a successful send (the service pushes it
                // out on every send; setAndAllowWhileIdle on API 31+) and
                // re-starts the FG service whenever it's dead.
                com.pingolin.track.receiver.WatchdogAlarmReceiver.schedule(context)

                // Also reschedule the pending-location flush
                // worker so any queued sends that missed the
                // stream before shutdown get drained once the
                // device is back. Without it, the safety-net
                // copies of sends on a dead channel could sit in
                // the local queue indefinitely for users who stay
                // logged in across a reboot without opening the
                // app.
                LocationSyncWorker.schedule(context)

                // And the notification poll, for the same reason: a device that
                // rebooted and was never opened again still has to be told when
                // somebody in its circle raises an alert.
                NotificationTickleWorker.schedulePolling(context)
            }
        }
    }
}
