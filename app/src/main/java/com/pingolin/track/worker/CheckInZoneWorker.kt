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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.track.domain.repository.CheckInZoneFollower
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Moves this account's check-in rules that follow the phone to its zone ([CheckInZoneFollower]),
 * once online: kicked when the app starts or comes back (MainActivity) and when the phone's zone
 * changes (TimeZoneChangedReceiver), and tried again, with WorkManager's backoff, after a failure
 * worth another try.
 */
@HiltWorker
class CheckInZoneWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val follower: CheckInZoneFollower,
    private val sessionManager: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again later.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        return if (follower.follow()) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "CheckInZoneWorker"
        internal const val WORK_NAME = "check_in_zone_follow"

        /** Run soon, once online; a run waiting or under way gives way, so the zone is read again. */
        fun kick(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<CheckInZoneWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                )
            }.onFailure { Log.w(TAG, "Couldn't schedule the time zone check: ${it.message}") }
        }
    }
}
