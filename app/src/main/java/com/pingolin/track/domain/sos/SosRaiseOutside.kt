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

package com.pingolin.track.domain.sos

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.ui.sos.SosPositionText
import com.pingolin.track.worker.SosRaiseWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a help request that hasn't gone does outside the dispatcher: the notice that says so
 * outside the app, and the job that sends a waiting one once a connection is back, even after
 * Android ended this process. And, for one that went, the notice that it reached nobody or that
 * its checks never answered, when Track isn't in front.
 */
interface RaiseOutside {
    /** Say [notSent] outside the app; null takes the notice down. */
    fun notice(notSent: SosDispatcher.NotSent?)

    /** Run the waiting request once this phone is online again, with or without this process. */
    fun wakeWhenOnline()

    fun cancelWake()

    /** Say outside the app that a request that went reached nobody, or may not have arrived. */
    fun deliveryNotice(verdict: SosDeliveryWatcher.Verdict) = Unit

    /** Take that down: the request is over, or its account signed out. */
    fun cancelDeliveryNotice() = Unit

    /** Nothing outside: a dispatcher in a test, unless the test watches it. */
    object None : RaiseOutside {
        override fun notice(notSent: SosDispatcher.NotSent?) = Unit
        override fun wakeWhenOnline() = Unit
        override fun cancelWake() = Unit
    }
}

@Singleton
class SosRaiseOutside @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : RaiseOutside {

    /**
     * On the SOS channel, where the person looks for their help request, and silent: it reports on
     * their own request, it doesn't ask anything of them. Waiting, it stays until the request goes.
     * The words are the SOS card's.
     */
    override fun notice(notSent: SosDispatcher.NotSent?) {
        if (notSent == null) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTICE_ID)
            return
        }
        post(noticeWords(context, notSent), waiting = notSent.why == SosDispatcher.NotSent.Why.WAITING)
    }

    /**
     * A waiting request's notice while this phone's session store can't be opened: its record
     * can't be read then, but the job that sends it runs only while one waits, and only
     * online, so "no connection" would be false. Read from no store.
     */
    fun noticeWaitingOnTheStore() = post(storeWaitingWords(context), waiting = true)

    private fun post(words: Pair<String, String>, waiting: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (title, text) = words
        val open = PendingIntent.getActivity(
            context, NOTICE_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "sos")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TrackApplication.SOS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setSilent(true)
            .setOngoing(waiting)
            .setAutoCancel(!waiting)
            .build()
        manager.notify(NOTICE_ID, notification)
    }

    /**
     * Once, and not silent: nobody may be coming, and that changes what the person does next. The
     * words are the iPhone's. It opens the Safety tab.
     *
     * On the general alerts channel, with its sound, and never the SOS channel, which is for
     * OTHERS' emergencies and rings through Do Not Disturb: the one person a sound can endanger is
     * a raiser who silenced their phone. iOS's has the default sound and respects Focus too.
     */
    override fun deliveryNotice(verdict: SosDeliveryWatcher.Verdict) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val (title, text) = deliveryWords(verdict).let { (t, b) -> context.getString(t) to context.getString(b) }
        val open = PendingIntent.getActivity(
            context, DELIVERY_NOTICE_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to", "sos")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, TrackApplication.ALERTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(DELIVERY_NOTICE_ID, notification)
    }

    override fun cancelDeliveryNotice() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DELIVERY_NOTICE_ID)
    }

    override fun wakeWhenOnline() = SosRaiseWorker.whenOnline(context)

    override fun cancelWake() = SosRaiseWorker.cancel(context)

    private companion object {
        /** One notice: there is at most one request that hasn't gone. */
        const val NOTICE_ID = 0x5052_4153 // "PRAS"

        /** And one for what the last request that went reached. */
        const val DELIVERY_NOTICE_ID = 0x5044_4C56 // "PDLV"
    }
}

/**
 * A delivery verdict's title and body (the iPhone's words): the same in the dialog over the app and
 * in the notification. Never "try again": the same circle gives the same answer, and raising again
 * doesn't say whether the first one arrived.
 */
fun deliveryWords(verdict: SosDeliveryWatcher.Verdict): Pair<Int, Int> = when (verdict) {
    SosDeliveryWatcher.Verdict.ALONE -> R.string.sos_reached_nobody_title to R.string.sos_reached_nobody_alone
    SosDeliveryWatcher.Verdict.UNDELIVERABLE -> R.string.sos_reached_nobody_title to R.string.sos_reached_nobody_undeliverable
    SosDeliveryWatcher.Verdict.NO_ANSWER -> R.string.sos_no_answer_title to R.string.sos_no_answer_body
}

/**
 * The notice's title and text for [notSent]: the state first, then when it was asked, with the date
 * when that wasn't today. A request that didn't go can be said a day or more after it was asked
 * (the 24 h cap, a restart), when a bare clock time reads as minutes ago.
 */
internal fun noticeWords(context: Context, notSent: SosDispatcher.NotSent): Pair<String, String> {
    val asked = SosPositionText.time(context, notSent.raisedAt)
    return when (notSent.why) {
        SosDispatcher.NotSent.Why.WAITING -> if (notSent.storeRefused) storeWaitingWords(context)
            else context.getString(R.string.sos_notice_waiting_title) to context.getString(R.string.sos_notice_waiting_text)
        SosDispatcher.NotSent.Why.UNSURE ->
            context.getString(R.string.sos_notice_unsure_title, asked) to context.getString(R.string.sos_notice_unsure_text)
        SosDispatcher.NotSent.Why.NOT_SENT ->
            context.getString(R.string.sos_notice_not_sent_title, asked) to context.getString(R.string.sos_notice_not_sent_text)
    }
}

/**
 * A waiting request's notice when it waits on this phone's session store, not a connection: read
 * from no store.
 */
internal fun storeWaitingWords(context: Context): Pair<String, String> =
    context.getString(R.string.sos_notice_waiting_store_title) to context.getString(R.string.sos_notice_waiting_store_text)
