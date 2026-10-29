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
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.MonitoringManager
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Re-check our own alert rules after a contentless wake-up.
 *
 * This is the receiving half of client-evaluated alerts. A silent `alerts_check`
 * push arrives carrying nothing — not who woke us, not why, not which circle —
 * because the server holds no key for any of that and is not told it. All the
 * deciding happens here: refresh the circles we hold rules in, decrypt what we
 * are entitled to decrypt, and compare it against our own thresholds.
 *
 * Being woken and finding nothing is a perfectly ordinary outcome. A member
 * whose rule is about somebody else is woken by every wake-up in the circle and
 * shows the user nothing, and that over-wake is the price of the server not
 * knowing who watches whom.
 */
@HiltWorker
class AlertsCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val monitoringManager: MonitoringManager,
    private val locationRepository: LocationRepository,
    private val sessionManager: SessionManager,
    private val circleRepository: com.pingolin.track.domain.repository.CircleRepository,
    private val alertRulesReload: com.pingolin.track.domain.repository.AlertRulesReload,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now: look again with WorkManager's
        // backoff, never a failure that loses what was asked.
        if (sessionManager.storeRefusal() != null) return Result.retry()
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "alerts_check: not logged in, nothing to evaluate")
            return Result.success()
        }

        // No waiting for the app to be opened: an expired access token is refreshed
        // here like anywhere else, or after a few idle hours a newcomer would wait for
        // somebody to open the app. A kill mid-refresh does not strand the session:
        // the auth server commits a rotation only when the new refresh token is first
        // presented, and the previous one keeps working until then; the new pair
        // reaches disk in one write, both halves read back
        // (SessionManager.saveTokenPair).

        // The same wake-up asks members' apps to hand a circle to whoever is owed it,
        // and tells a newcomer that theirs has arrived. Its own failures never cost
        // the alert evaluation below.
        try {
            circleRepository.onSilentWake()
        } catch (e: Exception) {
            Log.w(TAG, "alerts_check: membership pass failed: ${e.message}")
        }

        return try {
            // The rules judged below, fetched now: the wake-up says something may be due,
            // and the rows here may be none (a sign-in, an install, a database made again)
            // or miss a rule made on another device. A failure keeps the rules already
            // here.
            alertRulesReload.reloadIfStale(force = true)
            val batteryCircles = monitoringManager.batteryAlertCircleIds()
            val inactivityCircles = monitoringManager.inactivityCircleIds()
            // Fetched, not stored: these are other people's rules and they
            // change without us. See MonitoringManager.fetchCircleSelfAlerts.
            val selfAlerts = monitoringManager.fetchCircleSelfAlerts()
            val selfAlertCircles = selfAlerts.map { it.circleId }.toSet()
            val circleIds = batteryCircles + inactivityCircles + selfAlertCircles
            if (circleIds.isEmpty()) return Result.success()

            // Only circles that both reached the server AND decrypted
            // cleanly are judged. Passing a circle we failed to read
            // would let a connectivity gap look like "their battery
            // never came back" or "everyone went silent" — a false
            // alarm is worse than a late one here.
            //
            // Carries WHICH MEMBERS the refresh produced, not just
            // which circles were healthy. A healthy circle still says
            // nothing about a member who was absent from the response
            // — paused, never shared, or filtered server-side — and
            // reading the local cache for those members would find an
            // ageing timestamp that reads as silence. See
            // LocationFetchResult.refreshedUserIds.
            val refreshed = mutableMapOf<String, Set<String>>()
            for (cid in circleIds) {
                val result = locationRepository.refreshLatestLocations(cid).getOrNull()
                if (result != null && result.isComplete) {
                    refreshed[cid] = result.refreshedUserIds
                }
            }

            monitoringManager.evaluateLowBattery(refreshed.filterKeys { it in batteryCircles })
            monitoringManager.evaluateInactivity(refreshed.filterKeys { it in inactivityCircles })
            monitoringManager.evaluateCircleSelfAlerts(selfAlerts, refreshed.filterKeys { it in selfAlertCircles })
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "alerts_check evaluation failed", e)
            // Retry: the wake-up is the only signal we get, so dropping it on a
            // transient network failure means the alert simply never happens.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "AlertsCheckWorker"
        private const val WORK_NAME = "alerts_check"

        /**
         * Run an evaluation pass now, in response to a silent `alerts_check` push.
         *
         * REPLACE rather than KEEP: two wake-ups in quick succession describe the
         * same world, and the later one has the fresher data.
         */
        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val builder = OneTimeWorkRequestBuilder<AlertsCheckWorker>()
                .setConstraints(constraints)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
