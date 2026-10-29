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

package com.pingolin.track.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pingolin.track.R

/**
 * Tells the user when tracking has stopped and automatic recovery has given up.
 *
 * WHY THIS EXISTS: when the watchdog's automatic restarts can't bring tracking
 * back, the user must not find out by noticing their own absence from the map.
 *
 * Why a notification rather than another silent retry: the one start that
 * reliably regains location access is a start from the FOREGROUND. Android
 * denies while-in-use location to a foreground service started from a
 * background broadcast, so the watchdog can keep trying forever and still come
 * back blind. Opening the app is the remedy, and only the user can do it.
 *
 * Fires only after [TrackingHealth.ESCALATE_AFTER_FAILURES] consecutive failed
 * recovery cycles, so a transient refusal that the next tick heals never
 * reaches the user.
 */
object TrackingHealthNotifier {

    private const val TAG = "TrackingHealthNotifier"

    /** Own channel so this can be silenced without silencing tracking itself. */
    const val CHANNEL_ID = "tracking_health"
    private const val NOTIFICATION_ID = 1002

    /** Own id so a sign-out and a stalled service do not overwrite each other. */
    private const val SIGNED_OUT_NOTIFICATION_ID = 1003

    /** The stopped notification's one-tap restart. */
    private const val RESTART_REQUEST_CODE = 1004

    /** Extra read by MainActivity to re-start tracking from the foreground. */
    const val EXTRA_RESUME_TRACKING = "resume_tracking"

    /**
     * Whether one of this notifier's notifications may be showing. Starts TRUE: a
     * notification posted by an earlier process survives that process, and this one
     * cannot know about it until it has cleared once. Set by [show] and
     * [showSignedOut], cleared by [clear]. Only [clearIfShown] reads it.
     */
    @Volatile
    private var mayBeShowing = true

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        // DEFAULT, not LOW: this is the app admitting it has stopped doing the
        // one thing it was asked to do. It should reach the shade properly.
        // Not HIGH — it is not an emergency and must not interrupt.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.tracking_health_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.tracking_health_channel_description)
            setShowBadge(true)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Post the "tracking stopped" notification. Idempotent: the same id is
     * reused, so repeated calls update rather than stack.
     */
    fun show(context: Context) {
        try {
            ensureChannel(context)

            // Launch MainActivity with the resume flag. A start from an
            // activity is a foreground start, which is the whole point.
            val launch = Intent().apply {
                setClassName(context, "com.pingolin.track.ui.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_RESUME_TRACKING, true)
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // Say the cause when it is known: only "while using the app" was granted,
            // so a start from the background gets no location. Opening the app
            // restarts sharing and asks for "Allow all the time" again (MainActivity).
            val body = context.getString(
                if (needsAllTheTime(context)) R.string.tracking_health_all_the_time_body
                else R.string.tracking_health_stopped_body
            )
            // And a one-tap restart right here: a foreground service started from a
            // notification action is allowed from the background, location included, even
            // with only "While using the app". The plain ACTION_START, as opening the app
            // does: it lifts no pause and changes no setting. Explicit and immutable.
            val restart = PendingIntent.getForegroundService(
                context,
                RESTART_REQUEST_CODE,
                Intent(context, com.pingolin.track.service.LocationTrackingService::class.java)
                    .setAction(com.pingolin.track.service.LocationTrackingService.ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.tracking_health_stopped_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .addAction(0, context.getString(R.string.tracking_health_restart_action), restart)
                // Not ongoing — an undismissable notification about a thing the
                // user may deliberately leave off would be a trap. But it does
                // survive a swipe-to-clear-all being auto-cancelled only on tap,
                // and the app cancels it the moment a fix arrives.
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            mayBeShowing = true
            Log.i(TAG, "Posted tracking-stopped notification")
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on 13+. Nothing to do; the
            // journal still records that recovery failed.
            Log.w(TAG, "Cannot post tracking-stopped notification: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post tracking-stopped notification: ${e.message}")
        }
    }

    /**
     * The app signed itself out while tracking was switched on.
     *
     * Separate from [show] because the two are not the same problem and must
     * not read the same. "Stopped, tap to resume" is a lie here: nothing will
     * resume until the user signs in again, and no automatic path is even
     * trying, because BootReceiver and the watchdog both gate on being signed
     * in. Tapping lands on the sign-in screen, which is the actual fix.
     */
    fun showSignedOut(context: Context) {
        try {
            ensureChannel(context)

            val launch = Intent().apply {
                setClassName(context, "com.pingolin.track.ui.auth.AuthActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(
                context,
                SIGNED_OUT_NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val body = context.getString(R.string.tracking_health_signed_out_body)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.tracking_health_signed_out_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(context).notify(SIGNED_OUT_NOTIFICATION_ID, notification)
            mayBeShowing = true
            Log.i(TAG, "Posted signed-out notification")
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post signed-out notification: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post signed-out notification: ${e.message}")
        }
    }

    /**
     * Whether the restarts fail for want of "Allow all the time": precise location is
     * granted but background location is not (Android 10+). The watchdog cannot tell
     * that from its own verdicts — the service comes up blind (API 30–33) or is refused
     * (34+) — so it is read from the permissions themselves.
     */
    internal fun needsAllTheTime(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Take it down — tracking is working again. Safe to call when nothing is showing. */
    fun clear(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            NotificationManagerCompat.from(context).cancel(SIGNED_OUT_NOTIFICATION_ID)
            mayBeShowing = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear tracking-stopped notification: ${e.message}")
        }
    }

    /**
     * [clear], but only when something may be showing. LocationTrackingService calls
     * this on every accepted fix, and [clear] is two NotificationManager IPCs — 360
     * an hour while moving, for a notification that is almost never there.
     */
    fun clearIfShown(context: Context) {
        if (mayBeShowing) clear(context)
    }
}
