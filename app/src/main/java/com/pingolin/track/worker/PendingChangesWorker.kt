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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pingolin.track.domain.pending.PendingChangeRunner
import com.pingolin.track.domain.pending.PendingChangeScheduler
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the changes waiting to sync. Scheduled by [PendingChangeScheduler] only; every run
 * re-checks everything, so running when there is nothing to do costs a look at the database
 * and nothing else.
 */
@HiltWorker
class PendingChangesWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val runner: PendingChangeRunner,
    private val scheduler: PendingChangeScheduler,
    private val sessionManager: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        // A trigger that came during a run runs it again, so none is lost; bounded, as a
        // trigger can't arrive faster than a run finds nothing to do.
        var rounds = 0
        do {
            scheduler.takeRequest()
            try {
                runner.runOnce()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped: the network went (WorkManager runs the job again when it is back), or
                // a sign-out cancelled it. Nothing more runs in this job.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Waiting changes: run failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } while (scheduler.takeRequest() && ++rounds < MAX_ROUNDS)
        return Result.success()
    }

    companion object {
        private const val TAG = "PendingChangesWorker"
        private const val MAX_ROUNDS = 5

        /** A trigger from somewhere without injection (a worker built by tests, a sign-out). */
        fun kick(context: Context) = PendingChangeScheduler(context.applicationContext).kick()

        /** Sign-out: every scheduled run goes. */
        fun cancelAll(context: Context) = PendingChangeScheduler(context.applicationContext).cancel()
    }
}
