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

package com.pingolin.track.domain.pending

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pingolin.track.worker.PendingChangesWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When the changes waiting to sync run: ONE unique WorkManager job with a network
 * constraint, so two triggers never run them twice. A trigger that arrives while a run is
 * under way is not lost: the job runs again straight after ([takeRequest]).
 */
@Singleton
open class PendingChangeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Something changed that may let a change run: this phone caught up, the app came back, … */
    open fun kick() {
        requested.set(true)
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NOW,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PendingChangesWorker>().setConstraints(online()).build(),
            )
        }.onFailure { Log.w(TAG, "Couldn't schedule the waiting changes: ${it.message}") }
    }

    /** The next try after a transient failure (the runner's backoff), at
     * [atMs].
     */
    open fun at(atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_LATER,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PendingChangesWorker>()
                    .setConstraints(online())
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "Couldn't schedule the next try: ${it.message}") }
    }

    /** Whether a trigger came since the last call: the job runs once more if so. */
    open fun takeRequest(): Boolean = requested.getAndSet(false)

    /** Sign-out, or the session ended: nothing runs until the next sign-in's triggers. */
    open fun cancel() {
        requested.set(false)
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
            WorkManager.getInstance(context).cancelUniqueWork(WORK_LATER)
        }
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    companion object {
        private const val TAG = "PendingChangeScheduler"

        /** Shared by every instance: a kick from a worker built without injection counts too. */
        private val requested = AtomicBoolean(false)
        const val WORK_NOW = "pending_changes"
        const val WORK_LATER = "pending_changes_later"
    }
}
