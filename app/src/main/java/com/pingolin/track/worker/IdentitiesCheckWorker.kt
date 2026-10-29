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
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The silent `identities_check` push: somebody restored this account's data from before a
 * password reset in the account portal, so look again for those identities — through the
 * ordinary signed-in call, at most once per three minutes (see
 * PreviousIdentityLoader.nudge). The push carries nothing and grants nothing, and nothing
 * is shown: a circle that opens simply opens.
 */
@HiltWorker
class IdentitiesCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val circleRepository: CircleRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        try {
            circleRepository.onIdentitiesCheck()
        } catch (e: Exception) {
            // The next push or foreground looks again; a nudge is never retried on its own.
            Log.w(TAG, "identities_check: re-check failed: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "IdentitiesCheckWorker"
        private const val WORK_NAME = "identities_check"

        /**
         * Look again now. APPEND, not KEEP: a push that arrives while a re-check runs must
         * reach the loader, which turns it into its one further run.
         */
        fun runNow(context: Context) {
            val builder = OneTimeWorkRequestBuilder<IdentitiesCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
        }
    }
}
