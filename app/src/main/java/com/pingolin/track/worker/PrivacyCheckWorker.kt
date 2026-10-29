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
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.isSharingPausedAt
import com.pingolin.track.data.local.entity.sendsBubble
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The silent `privacy_check` wake: this account's privacy changed in a circle, on this phone or
 * another one, or a pause ran out. Read the circle list again at once, the same call the tracking
 * service makes every 5 minutes, which carries `my_privacy` for every circle: the send gates read
 * the stored rows, so a pause stops this phone's points within seconds rather than minutes, and a
 * resume starts them. The wake carries no ids, and nothing is shown.
 *
 * A circle that went from exact to approximate gets one fresh fix at once, through the normal gates
 * and so coarsened: until this phone sends again, the circle shows its last precise fix labelled
 * approximate.
 */
@HiltWorker
class PrivacyCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sessionManager: SessionManager,
    private val circleRepository: CircleRepository,
    private val circleDao: CircleDao,
    private val privacySettingDao: PrivacySettingDao,
    private val locationProvider: LocationProvider,
    private val locationRepository: LocationRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) return Result.success()
        val before = postures()
        circleRepository.refreshCircles()
            // The next wake, the 5-minute refresh or a launch looks again; a quiet refresh is never retried on its own.
            .onFailure { Log.w(TAG, "privacy_check: refresh failed: ${it.javaClass.simpleName}") }
        // A frozen last location left behind under an old key while sharing there was paused, now
        // due: the re-seal waits out a pause, and a pause's end wakes this.
        runCatching { circleRepository.lastKnownReseal.runAll() }
            .onFailure { Log.w(TAG, "privacy_check: re-seal not looked for: ${it.javaClass.simpleName}") }
        val switched = switchedToApproximate(before, postures(), System.currentTimeMillis())
        if (switched.isNotEmpty() && !sessionManager.isTrackingPaused()) {
            val fix = runCatching { withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) { locationProvider.currentLocation() } }.getOrNull()
            if (fix != null) {
                locationRepository.sendEnvelopeLocation(switched.toList(), fix, null)
                    .onFailure { Log.w(TAG, "privacy_check: the fresh fix didn't go: ${it.javaClass.simpleName}") }
            }
        }
        return Result.success()
    }

    private suspend fun postures(): Map<String, PrivacySettingEntity> = runCatching {
        privacySettingDao.getAllForCircles(circleDao.getAll().map { it.id }).associateBy { it.circleId }
    }.getOrDefault(emptyMap())

    companion object {
        private const val TAG = "PrivacyCheckWorker"
        private const val WORK_NAME = "privacy_check"

        /** How long a fresh fix may take before the wake gives up on it: the next regular send carries the new posture. */
        private const val FRESH_FIX_TIMEOUT_MS = 30_000L

        /**
         * Circles that shared exactly before the refresh and approximately after it, neither paused:
         * the ones that need a fresh, coarsened fix now.
         */
        internal fun switchedToApproximate(
            before: Map<String, PrivacySettingEntity>,
            after: Map<String, PrivacySettingEntity>,
            now: Long,
        ): Set<String> = after.filter { (circleId, row) ->
            val was = before[circleId] ?: return@filter false
            !row.isSharingPausedAt(now) && row.sendsBubble() && !was.isSharingPausedAt(now) && !was.sendsBubble()
        }.keys

        /** Look now; a wake that arrives while one runs is taken by a fresh run after it. */
        fun runNow(context: Context) {
            val builder = OneTimeWorkRequestBuilder<PrivacyCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, builder.build())
        }
    }
}
