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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pingolin.track.service.NotificationTickleFetcher
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fetch and show the notifications a tickle is telling us about, off the
 * BroadcastReceiver that received it — and, on [schedulePolling]'s timer, ask
 * for them even when no tickle came.
 *
 * Exists for UnifiedPush. The FCM path already has a Service to run in, but
 * TrackUnifiedPushReceiver is a BroadcastReceiver: it must return promptly, and
 * anything it launches into a bare CoroutineScope dies with the process the
 * moment the system decides the receiver is done. The fetch is a network
 * round-trip plus decryption, so it needs somewhere that survives that — which
 * is what WorkManager is for. Same shape as [AlertsCheckWorker], which the
 * `alerts_check` branch of the same receiver already uses.
 */
@HiltWorker
class NotificationTickleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tickleFetcher: NotificationTickleFetcher,
    private val sessionManager: SessionManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A session store that can't be opened now is no "signed out": look again,
        // so a push let go while it refused is fetched once it opens.
        if (sessionManager.storeRefusal() != null) return retryOrGiveUp("the session store can't be opened now")
        if (!sessionManager.isLoggedIn()) {
            Log.d(TAG, "tickle: not logged in, nothing to fetch")
            return Result.success()
        }

        // No waiting for the app to be opened: an expired access token is refreshed
        // here like anywhere else. Deferring "to a foreground pass" would, on a
        // UnifiedPush phone, hold back every notification, member_joined's handover
        // included, until the app was opened. A kill mid-refresh does not strand the
        // session (see AlertsCheckWorker).
        return try {
            if (tickleFetcher.fetchAndShowNotifications()) {
                Result.success()
            } else {
                // Retry rather than drop: a tickle we cannot act on is a
                // notification the user never sees, and the row stays unread
                // server-side until we do. Reached because the fetcher
                // returns false from both of its failure paths.
                retryOrGiveUp("tickle fetch did not complete")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tickle fetch threw", e)
            retryOrGiveUp("tickle fetch threw")
        }
    }

    /**
     * Back off, but not forever: an endlessly-failing fetch (a rejected token, a
     * route that has moved) is capped rather than letting WorkManager wake the
     * device about it indefinitely. Another tickle re-enqueues.
     */
    private fun retryOrGiveUp(reason: String): Result =
        if (runAttemptCount >= MAX_ATTEMPTS) {
            Log.w(TAG, "$reason; giving up after $runAttemptCount attempts")
            Result.failure()
        } else {
            Log.w(TAG, "$reason; retrying (attempt $runAttemptCount)")
            Result.retry()
        }

    companion object {
        private const val TAG = "NotificationTickle"
        private const val WORK_NAME = "notification_tickle_fetch"

        /**
         * Unique name of the periodic fallback. Deliberately NOT [WORK_NAME]: a
         * unique name holds either a one-time chain or a periodic request, never
         * both, so reusing it would make every push-driven [runNow] cancel the
         * poll (or the poll swallow the push, depending on the policy).
         */
        @androidx.annotation.VisibleForTesting
        const val POLL_WORK_NAME = "notification_tickle_poll"

        /** Attempts before a tickle is abandoned. Default backoff is exponential from 30s. */
        private const val MAX_ATTEMPTS = 5

        /**
         * How often the fallback asks for notifications nobody pushed us.
         *
         * 15 minutes, which is also WorkManager's floor for periodic work
         * (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`) — a smaller number
         * here would simply be raised back to this one, so there is no "more
         * frequent" option to weigh. The case against going LONGER is what rides
         * on this path: the same fetch renders "notify my circle", place alerts
         * and arrivals, and an hourly poll would turn an emergency notification
         * into an hourly digest. 15 minutes is a real degradation against push,
         * and it is the best a poll can do.
         *
         * Matching [LocationSyncWorker] and [LocationHeartbeatWorker] exactly —
         * same interval, same flex, same lone network constraint — is itself
         * load-bearing: JobScheduler batches jobs whose constraints and windows
         * coincide, so the marginal cost of this worker on a device that already
         * runs those two is one small request per wake-up it was taking anyway.
         * Picking, say, 20 minutes would buy a third independent wake-up for
         * nothing.
         */
        private const val POLL_INTERVAL_MINUTES = 15L
        private const val POLL_FLEX_MINUTES = 5L

        /**
         * Arm the periodic fallback. Idempotent; call it wherever the other
         * periodic workers are armed.
         *
         * UNCONDITIONAL, on both distribution flavours, and that is the deliberate
         * part.
         *
         * The libre flavour is what makes a fallback necessary at all: it resolves
         * no Firebase artifact (`gmsImplementation` in app/build.gradle.kts), so
         * `NoFcmTokenProvider.isAvailable()` returns false and push is UnifiedPush
         * or nothing. With no distributor installed there is no transport, and
         * without this worker there would be no second route either — a
         * de-Googled install would receive no notification by ANY means.
         *
         * But gating on the flavour would guard the one case we can see and miss
         * the ones we cannot. The flavour is a compile-time constant; it does not
         * describe whether pushes ARRIVE. A gms APK on a de-Googled phone answers
         * false from the same `isAvailable()`; a registration token the server has
         * stopped honouring looks identical from here; and the server deactivates
         * a device after three consecutive push failures — timeouts included —
         * after which it sends nothing until the device re-registers. Every one of
         * those is a gms device that goes silent with no local signal, which is
         * exactly the shape of failure a fallback is for. Gating on
         * `PushNotificationManager.getPreferredProvider()` would be no better: it
         * reports which provider was SELECTED, not whether anything is delivered.
         *
         * The waste on a healthy gms device is bounded and known: one
         * `GET /notifications?unread_only=true`, returning an empty list, on a
         * wake-up the device was already taking for the two location workers.
         */
        fun schedulePolling(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val pollRequest = PeriodicWorkRequestBuilder<NotificationTickleWorker>(
                POLL_INTERVAL_MINUTES, TimeUnit.MINUTES,
                POLL_FLEX_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()

            // UPDATE for the same reason the location workers use it: MainActivity
            // arms this on every launch, so REPLACE would restart the timer each
            // time and a user who opens the app every few minutes would never see
            // it fire; KEEP would pin the interval and constraints of whichever
            // build first ran and ignore any change shipped after.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                POLL_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                pollRequest
            )
            Log.d(TAG, "Notification poll scheduled every $POLL_INTERVAL_MINUTES min")
        }

        /**
         * Stop polling.
         *
         * Must be called from every logout path, like the other periodic workers:
         * [doWork] bails on a logged-out session, but an armed worker still wakes
         * the device every 15 minutes to discover that.
         */
        fun cancelPolling(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(POLL_WORK_NAME)
        }

        fun runNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val builder = OneTimeWorkRequestBuilder<NotificationTickleWorker>()
                .setConstraints(constraints)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }

            // APPEND_OR_REPLACE, not REPLACE: a tickle burst must not cancel a
            // fetch that is already mid-flight — that one may have posted some
            // rows and not yet marked them read. Queued behind it, the second
            // run picks up anything the first did not, and the fetcher's dedupe
            // set makes the overlap a no-op.
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                builder.build()
            )
        }
    }
}
