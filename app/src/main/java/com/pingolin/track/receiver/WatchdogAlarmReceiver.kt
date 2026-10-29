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

package com.pingolin.track.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.NotSharingNotice
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.TrackingEvent
import com.pingolin.track.util.TrackingHealth
import com.pingolin.track.util.TrackingHealthNotifier
import com.pingolin.track.util.TrackingStopReason
import com.pingolin.track.util.storeRefusal
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Third-line defense: an AlarmManager-driven check that fires once tracking
 * has been SILENT for roughly 20 minutes (subject to OS doze relaxation) to
 * verify `LocationTrackingService` is alive and receiving fixes, and re-start
 * it if not.
 *
 * A dead man's switch. `LocationTrackingService` re-arms this alarm 20
 * minutes out on its successful sends (throttled to once per 5 minutes — see
 * `LocationTrackingService.rearmWatchdogIfDue`), so on a healthy phone the
 * alarm never fires at all, rather than spending three wakeups an hour
 * re-arming itself and finding nothing wrong. Only silence lets it fire, and
 * when it does it re-arms itself, so a quiet or dead service keeps being
 * checked every 20 minutes.
 *
 * Why both this AND `LocationHeartbeatWorker`:
 *   - WorkManager's PeriodicWorkRequest is the right primitive for the common
 *     case (network constraints, retry/backoff, battery friendliness). But
 *     under aggressive OEM customizations (Xiaomi, Oppo, Huawei, Samsung's
 *     Power Manager) WorkManager ticks can stretch from 15 min to multi-hour
 *     gaps without the user being told: WorkManager and Doze together can
 *     defer ticks for ~9 hours on a phone left idle overnight, which is also
 *     why `LocationHeartbeatWorker.schedule` sets no
 *     `setRequiresBatteryNotLow(true)` constraint.
 *   - `setAndAllowWhileIdle` (the only path on API 31+, where the app holds
 *     no exact-alarm permission — see [schedule]) still fires during Doze,
 *     rate-limited to roughly one fire per ~9 min per app on stock Android.
 *     That is fine for a 20-minute check and a reasonable backstop against
 *     many hours of silence. Pre-S keeps `setExactAndAllowWhileIdle`.
 *
 * The two paths are intentionally redundant. If WorkManager fires first, the
 * watchdog fires next and finds the service already running → no-op. If
 * WorkManager is asleep, the watchdog brings the service back.
 */
@AndroidEntryPoint
class WatchdogAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sessionManager: SessionManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCHDOG_TICK) return

        Log.d(TAG, "Watchdog tick — fgRunning=${LocationTrackingService.isRunning}")

        // The session store can't be opened now: no crash, and the next tick looks again, so
        // tracking comes back once it opens: soon, not at the 20-minute mark.
        if (sessionManager.storeRefusal() != null) {
            scheduleRetry(context)
            return
        }

        if (!sessionManager.isLoggedIn()) {
            // Signed out. If tracking was ON when that happened, there is
            // no tracking and no automatic route back (every self-heal
            // path gates on isLoggedIn(), including this one), so the
            // user has to be told. Re-raise the notification so a
            // dismissed one does not bury the problem, then stop —
            // correctly, because nothing here can sign the user back in.
            if (sessionManager.trackingWantedAtSignOut()) {
                Log.w(TAG, "Watchdog: signed out while tracking was on — telling the user")
                TrackingHealthNotifier.showSignedOut(context)
                journal(TrackingEvent.USER_NOTIFIED)
            } else {
                Log.d(TAG, "Watchdog: not logged in, skipping")
            }
            // Nothing to share: a notice from a refusal that sharing stopped is down.
            NotSharingNotice.clear(context)
            // The alarm chain deliberately stops here: re-arming after a
            // genuine sign-out would defeat cancel(). Sign-in re-schedules it.
            return
        }

        // Re-arm the next tick now that we know the user is logged in, so we
        // never lose a logged-in user's schedule even if anything below
        // throws. Deliberately placed AFTER the isLoggedIn() gate so an
        // in-flight tick delivered at logout does NOT resurrect the alarm
        // (which would defeat WatchdogAlarmReceiver.cancel()).
        schedule(context)

        if (sessionManager.isTrackingPaused()) {
            Log.d(TAG, "Watchdog: tracking paused, skipping")
            NotSharingNotice.clear(context)
            return
        }

        // FIRST: judge the PREVIOUS tick's restart attempt, before starting
        // anything new. A watchdog that fires a start and assumes it worked
        // can "recover" tracking at every tick through an outage of many
        // hours, with the alarm scheduled the whole time, because no tick
        // ever checks. An attempt is only a success if the next tick finds
        // the service running AND fixes arriving.
        val verdict = TrackingHealth.assess(
            shouldBeTracking = sessionManager.shouldBeTracking(),
            serviceRunning = LocationTrackingService.isRunning,
            lastFixAtMs = sessionManager.lastLocationFixAt(),
            nowMs = System.currentTimeMillis(),
            // The poll the service is really using, not just the send interval: a
            // STILL phone below 20 % battery polls every 20 minutes and a battery
            // saver one up to hourly. See TrackingHealth.watchdogPollIntervalMs.
            pollIntervalMs = TrackingHealth.watchdogPollIntervalMs(
                userIntervalMs = sessionManager.getTrackingIntervalMs(),
                livePollIntervalMs = LocationTrackingService.publishedPollIntervalMs,
            ),
            consecutiveRestartFailures = sessionManager.trackingRestartFailures(),
            signedIn = true,
            trackingWanted = sessionManager.isTrackingEnabled(),
        )

        if (sessionManager.isTrackingRestartPending()) {
            if (verdict is TrackingHealth.Verdict.Healthy) {
                // The previous attempt worked. Reset the count so a future
                // failure gets its own full grace before bothering the user.
                sessionManager.clearTrackingRecoveryState()
                TrackingHealthNotifier.clear(context)
                Log.i(TAG, "Watchdog: previous restart succeeded")
            } else {
                val failures = sessionManager.trackingRestartFailures() + 1
                sessionManager.setTrackingRestartFailures(failures)
                sessionManager.setTrackingRestartPending(false)
                journal(TrackingEvent.RESTART_FAILED)
                rearmBackgroundPromptAt(failures)
                Log.w(TAG, "Watchdog: restart did not take (failures=$failures, verdict=$verdict)")
            }
        }

        // Re-read: the block above may have just changed the failure count.
        val failuresNow = sessionManager.trackingRestartFailures()

        if (verdict is TrackingHealth.Verdict.Blind) {
            // Running but receiving nothing. Re-issuing ACTION_START on a live
            // service makes it re-attach its location callback, which is the
            // one thing that can recover a service that came up without
            // location capability. Recorded so a blind spell is visible later.
            journal(TrackingEvent.BLIND_SUSPECTED)
            Log.w(TAG, "Watchdog: service running but no recent fix — re-attaching")
        } else if (verdict is TrackingHealth.Verdict.Healthy) {
            // Sharing, or not wanted. A start below takes the notice down only once tracking
            // starts: one the platform refuses leaves it up, still true.
            NotSharingNotice.clear(context)
            return
        }

        // Tell the user only once automatic recovery has genuinely failed
        // twice over. A background-started FGS can be denied location by the
        // platform, so more retries would not help — only a foreground start
        // will, and only the user can produce one.
        if (failuresNow >= TrackingHealth.ESCALATE_AFTER_FAILURES) {
            TrackingHealthNotifier.show(context)
            journal(TrackingEvent.USER_NOTIFIED)
        }

        try {
            val serviceIntent = Intent(context, LocationTrackingService::class.java)
                .apply { action = LocationTrackingService.ACTION_START }
            ContextCompat.startForegroundService(context, serviceIntent)
            sessionManager.setTrackingRestartPending(true)
            journal(TrackingEvent.RESTART_ATTEMPTED)
            Log.i(TAG, "Watchdog: restarted LocationTrackingService")
        } catch (e: Exception) {
            // Most likely ForegroundServiceStartNotAllowedException on
            // Android 12+ when the app isn't in an allowed state. Count it
            // immediately — the start never happened, so there is nothing for
            // the next tick to judge.
            val failures = sessionManager.trackingRestartFailures() + 1
            sessionManager.setTrackingRestartFailures(failures)
            sessionManager.setTrackingRestartPending(false)
            journal(TrackingEvent.RESTART_FAILED)
            rearmBackgroundPromptAt(failures)
            Log.w(TAG, "Watchdog: failed to start LocationTrackingService (failures=$failures)", e)
            if (failures >= TrackingHealth.ESCALATE_AFTER_FAILURES) {
                TrackingHealthNotifier.show(context)
                journal(TrackingEvent.USER_NOTIFIED)
            }
        }
    }

    /**
     * Sharing has really stopped: the count reached the user's notice. Then the once-only "let
     * Track run in the background" question may ask once more, at the next app open; once an
     * outage, at the crossing only, so a long one never nags.
     */
    private fun rearmBackgroundPromptAt(failures: Int) {
        if (failures == TrackingHealth.ESCALATE_AFTER_FAILURES) sessionManager.setBatteryOptShown(false)
    }

    private fun journal(event: TrackingEvent) {
        try {
            val at = sessionManager.lastLocationFixAt()
            val age = if (at <= 0L) TrackingHealth.NO_FIX
            else (System.currentTimeMillis() - at).coerceAtLeast(0L)
            sessionManager.trackingJournal.record(event, TrackingStopReason.NONE, age)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write watchdog journal entry: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "WatchdogAlarm"
        private const val ACTION_WATCHDOG_TICK = "com.pingolin.track.WATCHDOG_TICK"
        private const val REQUEST_CODE = 0x1A0C
        private const val INTERVAL_MS: Long = 20L * 60L * 1000L // 20 min

        /**
         * While the session store can't be opened, the first look again; each next one twice
         * as far, up to [INTERVAL_MS].
         */
        private const val RETRY_FIRST_MS: Long = 60L * 1000L // 1 min

        /**
         * When the next tick is due, and how far a refusal's looks have gone: in plain
         * preferences, never the session store, which is what refuses. AlarmManager can't say when
         * an app's own alarm is due.
         */
        private const val STATE_PREFS = "watchdog_alarm"
        private const val KEY_NEXT_AT = "next_at"
        private const val KEY_RETRY_STEP = "retry_step"

        private fun state(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

        /**
         * Schedule (or re-schedule) the next watchdog tick: the 20-minute chain. A refusal's looks
         * end, so a later one starts at a minute again.
         */
        @Synchronized
        fun schedule(context: Context) = arm(context, SystemClock.elapsedRealtime() + INTERVAL_MS, retryStep = null)

        /**
         * While the session store can't be opened: the next tick a minute out, or where it is when
         * already due sooner, never later. Saying that sharing stopped asks for it
         * ([NotSharingNotice]), and so does a location wake then, so tracking comes back, and the
         * notice down, soon after the store opens. One due sooner is armed again where it is:
         * remembered, but gone with a restart, it comes back.
         */
        @Synchronized
        fun scheduleSoon(context: Context) {
            val now = SystemClock.elapsedRealtime()
            val soon = now + RETRY_FIRST_MS
            val due = state(context).getLong(KEY_NEXT_AT, 0L)
            arm(context, if (due in (now + 1)..soon) due else soon, retryStep = 0)
        }

        /** A tick that met a refusal: the next 1, 2, 4, 8, 16, then every 20 minutes. */
        @Synchronized
        private fun scheduleRetry(context: Context) {
            val step = state(context).getInt(KEY_RETRY_STEP, -1) + 1
            arm(context, SystemClock.elapsedRealtime() + retryDelayMs(step), step)
        }

        private fun retryDelayMs(step: Int): Long = minOf(INTERVAL_MS, RETRY_FIRST_MS shl step.coerceIn(0, 5))

        /**
         * The next tick at [triggerAt], [retryStep] into a refusal's looks (null: none).
         *
         * In practice this always takes the `setAndAllowWhileIdle` path on
         * API 31+: the manifest asks for no exact-alarm permission, because
         * Play restricts those to alarm, timer and calendar apps and refuses
         * publication to everything else. That costs precision, not the
         * feature — `setAndAllowWhileIdle` is still doze-aware, so the tick
         * lands in a window around the 20-minute mark rather than on it, which
         * is all a liveness check needs. The exact branch is kept for pre-S,
         * where exact alarms are not gated, and so that granting the
         * permission out of band still works.
         */
        private fun arm(context: Context, triggerAt: Long, retryStep: Int?) {
            val mgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
                .apply { action = ACTION_WATCHDOG_TICK }
            // FLAG_IMMUTABLE is required on Android 12+. FLAG_UPDATE_CURRENT
            // ensures we replace any earlier scheduled intent rather than
            // accumulating duplicates.
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mgr.canScheduleExactAlarms()
            } else {
                true
            }

            try {
                if (canExact) {
                    mgr.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi,
                    )
                } else {
                    mgr.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pi,
                    )
                    Log.i(TAG, "Watchdog: exact alarms not permitted, using inexact")
                }
            } catch (e: SecurityException) {
                // Defensive fallback in case the OEM strips the
                // permission between canScheduleExactAlarms() and
                // setExactAndAllowWhileIdle().
                Log.w(TAG, "Watchdog: setExact threw, retrying inexact", e)
                mgr.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pi,
                )
            }
            val edit = state(context).edit().putLong(KEY_NEXT_AT, triggerAt)
            if (retryStep == null) edit.remove(KEY_RETRY_STEP) else edit.putInt(KEY_RETRY_STEP, retryStep)
            edit.apply()
        }

        @Synchronized
        fun cancel(context: Context) {
            state(context).edit().clear().apply()
            val mgr = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
                .apply { action = ACTION_WATCHDOG_TICK }
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                mgr.cancel(pi)
                pi.cancel()
            }
        }
    }
}
