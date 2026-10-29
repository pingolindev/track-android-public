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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.receiver.WatchdogAlarmReceiver
import com.pingolin.track.ui.main.MainActivity

/**
 * That this phone's location isn't being shared: tracking couldn't start on a session store that
 * can't be opened, and sharing mustn't stop silently. Read from no store, and not the location
 * service's own, so its refusal and one at boot post the same. On the location channel, where
 * "Location sharing is active" lives, so a refusal that passes in seconds rings nothing; under one
 * id, in its own words. It opens Track.
 *
 * Saying it arms the watchdog's soonest look ([WatchdogAlarmReceiver.scheduleSoon]), so it comes
 * down soon after the store opens, not at the 20-minute tick: when tracking starts (the service), or
 * when there is nothing to share, signed out, paused or off (the watchdog), or at a boot or an
 * update with the store open. A start the platform refuses leaves it up, still true.
 */
object NotSharingNotice {
    private const val TAG = "NotSharingNotice"

    /** Its own id: one notice, however often it is said. */
    const val NOTIFICATION_ID = 1005

    fun show(context: Context) {
        try {
            val body = context.getString(R.string.not_sharing_text)
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, TrackApplication.LOCATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.not_sharing_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted (13+): nothing more to say.
            Log.w(TAG, "Not posted: ${e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "Not posted: ${e.javaClass.simpleName}")
        }
        // Said or not, tracking is looked at again soon: a phone that may not post
        // still gets its sharing back once the store opens.
        WatchdogAlarmReceiver.scheduleSoon(context)
    }

    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }
}
