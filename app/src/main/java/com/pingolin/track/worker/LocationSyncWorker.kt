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

package com.pingolin.track.worker

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Background worker that syncs pending locations to the server.
 *
 * This worker runs periodically to ensure locations are uploaded even if
 * the app crashes or loses network connectivity during streaming.
 *
 * Features:
 * - Runs every 15 minutes when network is available
 * - Retries failed uploads with exponential backoff
 * - Cleans up locations that have failed too many times
 * - Cleans up old pending locations to prevent unbounded queue growth
 * - Cleans up old cached locations (>90 days) to prevent database bloat
 * - Battery-efficient with network constraints
 * - Expedited work when connectivity is restored
 */
@HiltWorker
class LocationSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val locationRepository: LocationRepository,
    private val pendingLocationDao: PendingLocationDao,
    private val locationDao: LocationDao,
    private val notificationDao: NotificationDao,
    private val localInactivityEventDao: LocalInactivityEventDao,
    private val queueMonitor: QueueMonitor,
    private val checkInRepository: com.pingolin.track.domain.repository.CheckInRepository,
    private val boundaryRepository: com.pingolin.track.domain.repository.BoundaryRepository,
    private val sessionManager: SessionManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LocationSyncWorker"
        private const val PERIODIC_WORK_NAME = "location_sync_periodic"
        private const val IMMEDIATE_WORK_NAME = "location_sync_immediate"

        /** Plain prefs: it holds one timestamp, nothing about the user. */
        private const val MAINTENANCE_PREFS = "location_sync_maintenance"
        private const val KEY_RETENTION_CLEANUP_AT = "retention_cleanup_at_ms"

        /** The retention cleanups prune by the day, so a daily pass keeps them exact enough. */
        internal const val RETENTION_CLEANUP_INTERVAL_MS = 24L * 60 * 60 * 1000

        /**
         * Are the 90-day cleanups due? Never-run is due, and so is a clock that went
         * backwards (a stored time in the future would otherwise postpone them for
         * however far the clock had jumped).
         */
        internal fun retentionCleanupDue(lastRunMs: Long, nowMs: Long): Boolean =
            lastRunMs <= 0L || nowMs < lastRunMs || nowMs - lastRunMs >= RETENTION_CLEANUP_INTERVAL_MS

        /**
         * Schedule the periodic location sync worker.
         * Should be called when user logs in.
         */
        fun schedule(context: Context) {
            // No setRequiresBatteryNotLow(true) constraint:
            // Doze + low battery stretch worker intervals into
            // multi-hour gaps. Use UPDATE (not REPLACE) so the
            // periodic timer isn't reset every time
            // MainActivity.onCreate calls schedule(); with
            // REPLACE, a user who opens the app every 5-15 min
            // would see the 15-min interval continually pushed
            // forward and the worker effectively never fires.
            // UPDATE preserves the next-run clock while still
            // letting code/constraint changes propagate on
            // upgrades.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<LocationSyncWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                syncRequest
            )
        }

        /**
         * Trigger an immediate sync when connectivity is restored.
         * Uses expedited work on Android 12+ to reduce deferral by OS.
         */
        fun syncNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequestBuilder = OneTimeWorkRequestBuilder<LocationSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )

            // Use expedited work on Android 12+ for faster execution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                workRequestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequestBuilder.build()
            )
        }

        /**
         * Cancel all location sync workers.
         * Should be called when user logs out.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        // Gate the whole worker on login. Both the periodic tick and
        // ConnectivityMonitor.onAvailable -> syncNow enqueue this regardless of auth
        // state, and the DB cleanup below runs before the login guard buried inside
        // syncPendingLocations — so a logged-out run would churn the DB (and wake the
        // sync path) for no reason. Parity with PushTokenSyncWorker / LocationHeartbeatWorker.
        if (!sessionManager.isLoggedIn()) {
            android.util.Log.d(TAG, "Not logged in, skipping location sync")
            return Result.success()
        }
        // The device-wide Pause Tracking earlier versions offered, until each circle
        // is paused instead (PauseTrackingMigration): the backlog stays on the phone
        // meanwhile, whoever asks for the flush (the service's start, the periodic
        // tick, a network change).
        if (sessionManager.isTrackingPaused()) {
            android.util.Log.d(TAG, "Pause Tracking not moved yet, keeping the backlog")
            return Result.success()
        }
        return withTimeoutOrNull(120_000L) {
            try {
                // First, cleanup the pending queue (remove old entries, trim to max size)
                try {
                    pendingLocationDao.cleanup()
                    android.util.Log.d(TAG, "Pending location queue cleaned up")
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Queue cleanup failed", e)
                }

                // The 90-day retention cleanups run at most once a day: on
                // EVERY sync — the 15-minute periodic tick plus each
                // expedited syncNow (every service start, every network
                // change) — they would run 96+ times a day, each a job's
                // worth of DB work for rows that only age by the day. The
                // pending-queue cleanup above stays per run: it bounds the
                // queue this run is about to drain.
                // A prefs failure costs, at worst, an extra cleanup — never
                // the sync.
                val maintenancePrefs = runCatching {
                    applicationContext.getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
                }.getOrNull()
                val nowMs = System.currentTimeMillis()
                val lastCleanupMs = runCatching {
                    maintenancePrefs?.getLong(KEY_RETENTION_CLEANUP_AT, 0L) ?: 0L
                }.getOrDefault(0L)
                if (retentionCleanupDue(lastCleanupMs, nowMs)) {
                    // Cleanup old cached locations (>90 days) to prevent database bloat
                    // This runs periodically with the sync to avoid separate maintenance workers
                    try {
                        val deletedCount = locationDao.cleanupOldLocations()
                        if (deletedCount > 0) {
                            android.util.Log.i(TAG, "Cleaned up $deletedCount old cached locations")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Location cache cleanup failed", e)
                    }

                    // Cleanup old notifications (>90 days) to prevent unbounded table growth
                    try {
                        val cutoffDayBucket = ((System.currentTimeMillis() - (90L * 86400000L)) / 86400000L).toInt()
                        notificationDao.deleteOlderThan(cutoffDayBucket)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Notification cleanup failed", e)
                    }

                    // Cleanup old client-side inactivity events (>90 days). This table
                    // is scanned per-member during inactivity evaluation
                    // (MonitoringManager), so trim it alongside the other caches to
                    // keep that scan cheap and the table bounded.
                    try {
                        val cutoffMillis = System.currentTimeMillis() - (90L * 86400000L)
                        localInactivityEventDao.deleteOlderThan(cutoffMillis)
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "Inactivity event cleanup failed", e)
                    }
                    runCatching { maintenancePrefs?.edit()?.putLong(KEY_RETENTION_CLEANUP_AT, nowMs)?.apply() }
                }

                // Sync pending locations
                val result = locationRepository.syncPendingLocations()

                // Sync pending check-ins
                try {
                    checkInRepository.syncPendingCheckIns()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Check-in sync failed", e)
                }

                // And the changes waiting for this phone to catch up: their
                // own job, which never runs twice at once.
                runCatching { PendingChangesWorker.kick(applicationContext) }

                // Replay queued boundary crossings. Drained here, alongside
                // locations and check-ins, because this worker is what runs when
                // connectivity comes back — and a crossing is queued precisely
                // because it did not. Its own age bound (2h, far shorter than the
                // locations queue's 24h) decides what is still worth sending, so a
                // long outage discards rather than floods.
                try {
                    boundaryRepository.syncPendingCrossings()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Crossing replay failed", e)
                }

                // Update queue monitor after sync attempt
                try {
                    queueMonitor.onSyncCompleted()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Queue monitor update failed", e)
                }

                result.fold(
                    onSuccess = { syncedCount ->
                        if (syncedCount > 0) {
                            android.util.Log.i(TAG, "Synced $syncedCount pending locations")
                        }
                        Result.success()
                    },
                    onFailure = { error ->
                        android.util.Log.e(TAG, "Sync failed", error)
                        Result.retry()
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Worker failed", e)
                Result.retry()
            }
        } ?: run {
            android.util.Log.w(TAG, "Worker timed out after 2 minutes, will retry")
            Result.retry()
        }
    }
}
