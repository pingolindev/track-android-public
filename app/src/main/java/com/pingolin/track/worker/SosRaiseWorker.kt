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
import com.pingolin.track.domain.sos.SosDispatcher
import com.pingolin.track.domain.sos.SosRaiseOutside
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * A help request waiting for a connection, run once this phone is online again, whether or not
 * the process that waited is still alive: Android ends a backgrounded one, and the card's promise
 * ("Your circle will be notified as soon as this phone is back online") has to hold then too. The
 * dispatcher does the try, from the request's own record; while its own wait is still running in
 * this process, it is left to that.
 */
@HiltWorker
class SosRaiseWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val sosDispatcher: SosDispatcher,
    private val outside: SosRaiseOutside,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with this job's backoff, never a
        // failure, which nothing arms again until Track is opened. The request waits on the store,
        // not a connection (this job runs only online), and its notice says so.
        if (sessionManager.storeRefusal() != null) {
            outside.noticeWaitingOnTheStore()
            return Result.retry()
        }
        if (!sessionManager.isLoggedIn()) return Result.success()
        return when (sosDispatcher.tryWaiting()) {
            // Still nothing left this phone: Android runs it again once it is online again.
            SosDispatcher.Waited.STILL_WAITING -> Result.retry()
            else -> Result.success()
        }.also { Log.i(TAG, "Waiting request for help: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "SosRaiseWorker"
        private const val WORK_NAME = "sos_raise_waiting"

        /** Run the waiting request once this phone is online; one job, however often it is asked for. */
        fun whenOnline(context: Context) {
            val request = OneTimeWorkRequestBuilder<SosRaiseWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
