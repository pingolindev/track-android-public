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
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * A member joined one of this user's circles: hand them the circle, in the background,
 * with nobody opening the app.
 *
 * Expedited work rather than a coroutine in the process that received the push: that
 * process may be frozen again seconds after the push, and WorkManager keeps it up until the
 * work is done. Same shape as [AlertsCheckWorker].
 */
@HiltWorker
class MemberJoinedWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alertHandler: AlertHandler,
    private val sessionManager: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        val circleId = inputData.getString(KEY_CIRCLE_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        alertHandler.performMemberJoined(
            circleId,
            needsDelivery = inputData.getBoolean(KEY_NEEDS_DELIVERY, false),
            needsKeyRotation = inputData.getBoolean(KEY_NEEDS_KEY_ROTATION, false),
            sendLocation = inputData.getBoolean(KEY_SEND_LOCATION, false),
        )
        return Result.success()
    }

    companion object {
        private const val TAG = "MemberJoinedWorker"
        internal const val KEY_CIRCLE_ID = "circle_id"
        internal const val KEY_NEEDS_DELIVERY = "needs_delivery"
        internal const val KEY_NEEDS_KEY_ROTATION = "needs_key_rotation"
        internal const val KEY_SEND_LOCATION = "send_location"

        /** One per circle: a second join in the same circle is served by the same pass. */
        fun runNow(context: Context, circleId: String, needsDelivery: Boolean, needsKeyRotation: Boolean, sendLocation: Boolean) {
            val builder = OneTimeWorkRequestBuilder<MemberJoinedWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(
                    workDataOf(
                        KEY_CIRCLE_ID to circleId,
                        KEY_NEEDS_DELIVERY to needsDelivery,
                        KEY_NEEDS_KEY_ROTATION to needsKeyRotation,
                        KEY_SEND_LOCATION to sendLocation,
                    )
                )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            // APPEND_OR_REPLACE: a join arriving while another's pass runs gets a pass of its
            // own after it, rather than cancelling one that may be half-way through a delivery.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "member_joined:$circleId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                builder.build(),
            )
            Log.d(TAG, "member_joined: queued the handover for $circleId")
        }
    }
}
