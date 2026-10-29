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
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.UpdatePushTokenRequest
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Background worker that syncs push notification tokens to the server.
 *
 * This worker is scheduled when a push token sync fails (e.g., due to network issues).
 * It retries with exponential backoff until successful.
 *
 * Features:
 * - Runs when network is available
 * - Retries failed syncs with exponential backoff
 * - Handles both FCM tokens and UnifiedPush endpoints
 */
@HiltWorker
class PushTokenSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val tracksApi: TrackApi,
    private val sessionManager: SessionManager
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "PushTokenSyncWorker"
        private const val WORK_NAME = "push_token_sync"

        /**
         * Schedule a push token sync with retry.
         * Called when initial sync fails in TrackFirebaseMessagingService or TrackUnifiedPushReceiver.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<PushTokenSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )

            Log.d(TAG, "Push token sync scheduled")
        }

        /**
         * Cancel any pending push token sync.
         * Should be called when user logs out.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        // Don't sync if not logged in
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "Not logged in, skipping push token sync")
            return Result.success()
        }

        // Don't sync if already synced
        if (sessionManager.isPushTokenSynced()) {
            Log.d(TAG, "Push token already synced")
            return Result.success()
        }

        val deviceId = sessionManager.getDeviceIdOrNull()
        if (deviceId == null) {
            Log.d(TAG, "No device ID, will sync on device registration")
            return Result.success()
        }

        val pushType = sessionManager.getPushType()
        val pushToken = sessionManager.getPushToken()
        val pushEndpoint = sessionManager.getPushEndpoint()

        // Nothing to sync
        if (pushType == null || (pushToken == null && pushEndpoint == null)) {
            Log.d(TAG, "No push settings to sync")
            return Result.success()
        }

        return try {
            Log.d(TAG, "Syncing push token for device: $deviceId, type: $pushType")

            val response = tracksApi.updateDevicePushToken(
                deviceId = deviceId,
                request = UpdatePushTokenRequest(
                    pushToken = pushToken,
                    pushType = pushType,
                    pushEndpoint = pushEndpoint
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                sessionManager.setPushTokenSynced(true)
                Log.i(TAG, "Push token synced successfully")
                Result.success()
            } else {
                val error = response.body()?.error ?: "Unknown error"
                Log.w(TAG, "Push token sync failed: $error")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push token sync exception", e)
            Result.retry()
        }
    }
}
