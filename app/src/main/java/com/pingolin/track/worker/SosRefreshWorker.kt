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
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The silent `sos_refresh` push: a raiser sealed an open SOS again under a new key, so fetch the
 * active list again and judge it by the receivers' rules. It carries no ids. An SOS the refresh
 * judges alarming rings once on this device, the first time (SosRepository.refreshActiveAlerts):
 * the phone the reseal was for.
 */
@HiltWorker
class SosRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val sosRepository: SosRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        sosRepository.refreshActiveAlerts()
            // The next push, launch or refresh looks again; a quiet refresh is never retried on its own.
            .onFailure { Log.w(TAG, "sos_refresh: refresh failed: ${it.javaClass.simpleName}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "SosRefreshWorker"
        private const val WORK_NAME = "sos_refresh"

        /** Look again now; a push that arrives while one runs is taken by a fresh run after it. */
        fun runNow(context: Context) {
            val builder = OneTimeWorkRequestBuilder<SosRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
        }
    }
}
