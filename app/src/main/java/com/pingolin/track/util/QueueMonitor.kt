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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pingolin.track.R
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors the pending location queue and sync status for users.
 *
 * The queue has a maximum size of 1000 locations. This monitor:
 * - Emits warnings at 80% capacity (800 locations)
 * - Shows notifications at 100% capacity when locations are being dropped
 * - Provides queue status for UI display
 * - Tracks overall sync status (synced, syncing, pending, error)
 *
 * This helps users understand why their location history might have gaps
 * during extended offline periods.
 */
@Singleton
class QueueMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pendingLocationDao: PendingLocationDao
) {
    companion object {
        private const val TAG = "QueueMonitor"
        private const val CHANNEL_ID = "queue_status"
        private const val NOTIFICATION_ID = 2001

        /** Warning threshold as percentage of max queue size */
        private const val WARNING_THRESHOLD_PERCENT = 0.8f

        /** Max queue size from PendingLocationDao */
        private const val MAX_QUEUE_SIZE = PendingLocationDao.MAX_QUEUE_SIZE
    }

    /**
     * Overall sync status for UI display.
     */
    enum class SyncState {
        /** All locations synced, queue empty */
        SYNCED,
        /** Currently syncing locations */
        SYNCING,
        /** Locations pending, waiting to sync */
        PENDING,
        /** Sync failed, will retry */
        ERROR,
        /** Offline, locations being queued */
        OFFLINE
    }

    /**
     * Detailed sync status including last sync time and error info.
     */
    data class SyncStatus(
        val state: SyncState,
        val pendingCount: Int,
        val lastSyncTime: Long?,
        val lastSyncError: String?,
        val lastSyncedCount: Int
    ) {
        companion object {
            val INITIAL = SyncStatus(
                state = SyncState.SYNCED,
                pendingCount = 0,
                lastSyncTime = null,
                lastSyncError = null,
                lastSyncedCount = 0
            )
        }
    }

    /**
     * Current queue status.
     */
    data class QueueStatus(
        val currentSize: Int,
        val maxSize: Int,
        val percentFull: Float,
        val isWarning: Boolean,
        val isFull: Boolean
    ) {
        companion object {
            val EMPTY = QueueStatus(
                currentSize = 0,
                maxSize = MAX_QUEUE_SIZE,
                percentFull = 0f,
                isWarning = false,
                isFull = false
            )
        }
    }

    private val _queueStatus = MutableStateFlow(QueueStatus.EMPTY)
    val queueStatus: StateFlow<QueueStatus> = _queueStatus.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus.INITIAL)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var hasShownFullNotification = false
    private var lastWarningSize = 0

    init {
        createNotificationChannel()
    }

    /**
     * Check the current queue status and update state.
     * Call this periodically (e.g., after each location enqueue or sync attempt).
     */
    suspend fun checkQueueStatus() {
        try {
            val count = pendingLocationDao.getCount()
            val percentFull = count.toFloat() / MAX_QUEUE_SIZE
            val isWarning = percentFull >= WARNING_THRESHOLD_PERCENT
            val isFull = count >= MAX_QUEUE_SIZE

            val status = QueueStatus(
                currentSize = count,
                maxSize = MAX_QUEUE_SIZE,
                percentFull = percentFull,
                isWarning = isWarning,
                isFull = isFull
            )

            _queueStatus.value = status

            // Log warnings
            if (isWarning && count > lastWarningSize) {
                Log.w(TAG, "Location queue at ${(percentFull * 100).toInt()}% capacity ($count/$MAX_QUEUE_SIZE)")
                lastWarningSize = count
            }

            // Show notification when full (only once per full episode)
            if (isFull && !hasShownFullNotification) {
                showQueueFullNotification()
                hasShownFullNotification = true
            }

            // Reset notification flag when queue drains below warning
            if (!isWarning) {
                hasShownFullNotification = false
                lastWarningSize = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check queue status", e)
        }
    }

    /**
     * Called when a location is enqueued. Updates status and checks thresholds.
     */
    suspend fun onLocationEnqueued() {
        checkQueueStatus()
        updateSyncStatus(SyncState.PENDING, null)
    }

    /**
     * Called when sync starts.
     */
    fun onSyncStarted() {
        updateSyncStatus(SyncState.SYNCING, null)
    }

    /**
     * Called after locations are synced successfully.
     *
     * @param syncedCount Number of locations synced in this batch
     */
    suspend fun onSyncCompleted(syncedCount: Int = 0) {
        checkQueueStatus()
        val currentStatus = _syncStatus.value
        val newState = if (_queueStatus.value.currentSize == 0) SyncState.SYNCED else SyncState.PENDING
        _syncStatus.value = currentStatus.copy(
            state = newState,
            pendingCount = _queueStatus.value.currentSize,
            lastSyncTime = System.currentTimeMillis(),
            lastSyncError = null,
            lastSyncedCount = syncedCount
        )
    }

    /**
     * Called when sync fails.
     *
     * @param error Error message for debugging
     */
    suspend fun onSyncFailed(error: String) {
        checkQueueStatus()
        val currentStatus = _syncStatus.value
        _syncStatus.value = currentStatus.copy(
            state = SyncState.ERROR,
            pendingCount = _queueStatus.value.currentSize,
            lastSyncError = error
        )
    }

    /**
     * Called when device goes offline.
     */
    suspend fun onOffline() {
        checkQueueStatus()
        updateSyncStatus(SyncState.OFFLINE, null)
    }

    /**
     * Called when device comes online.
     */
    suspend fun onOnline() {
        checkQueueStatus()
        val newState = if (_queueStatus.value.currentSize > 0) SyncState.PENDING else SyncState.SYNCED
        updateSyncStatus(newState, null)
    }

    private fun updateSyncStatus(state: SyncState, error: String?) {
        val currentStatus = _syncStatus.value
        _syncStatus.value = currentStatus.copy(
            state = state,
            pendingCount = _queueStatus.value.currentSize,
            lastSyncError = error
        )
    }

    /**
     * Create notification channel for queue status notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_queue),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_queue_desc)
            }

            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Show notification that queue is full and locations may be dropped.
     */
    private fun showQueueFullNotification() {
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_queue_full_title))
            .setContentText(context.getString(R.string.notification_queue_full_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Clear the queue full notification.
     */
    fun clearNotification() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
