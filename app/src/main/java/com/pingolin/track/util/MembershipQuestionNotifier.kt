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
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pingolin.track.R

/**
 * The two questions about who is in a circle that only a person can answer: "Is this
 * really {name}?" after a member reset their password, and, on owner and admin phones,
 * "Let {name} into {circle}?".
 *
 * The app raises them by itself, in the background too. Shown only on the Circles screen,
 * they would leave a member who reset their password waiting, locked out, until somebody
 * happened to open it. A notification takes the question to the person instead — at most
 * once a day per question (as often as the in-app one asks), never while the Circles
 * screen is showing (the question is on it already), and gone once it is answered.
 */
interface MembershipQuestionNotifier {
    /** "{name} reset their password. Check it's really them." ("A member" when unnamed). */
    fun memberReset(circleId: String, userId: String, name: String?)

    /** "{name} is waiting to join {circle}." */
    fun waitingToJoin(circleId: String, circleName: String, userId: String, name: String)

    /**
     * "Is this really {email}?" about an invitation this account made, which does not name
     * its account as it is now. Keyed by the invitee's account ([userId]).
     */
    fun inviteeChanged(circleId: String, userId: String, email: String) {}

    /** The question about [userId] in [circleId] was answered: its notification goes. */
    fun answered(circleId: String, userId: String)

    /** Sign-out: forget which questions were posted when. */
    fun clear()

    companion object {
        /** Whether the Circles screen is on screen now; set by CirclesFragment. */
        @Volatile
        var circlesScreenShowing = false
    }
}

class AndroidMembershipQuestionNotifier(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val now: () -> Long = System::currentTimeMillis,
) : MembershipQuestionNotifier {

    /**
     * When each question was last posted: "<kind>|<circle>|<user>" → millis, the circle and user in
     * their one spelling. One posted under another spelling by an earlier version is folded there
     * first, the later post standing.
     */
    private val posted by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { prefs ->
            for (kind in KINDS) CircleEntries.fold(STORE, { prefs }, kind, RULES)
        }
    }

    override fun memberReset(circleId: String, userId: String, name: String?) {
        val who = name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.membership_question_unnamed_member)
        val text = context.getString(R.string.membership_question_reset, who)
        post(RESET, circleId, userId, detailed = text, namesOnly = text)
    }

    override fun waitingToJoin(circleId: String, circleName: String, userId: String, name: String) {
        post(
            JOIN, circleId, userId,
            detailed = context.getString(R.string.membership_question_join, name, circleName),
            namesOnly = context.getString(R.string.membership_question_join_names_only, name),
        )
    }

    override fun inviteeChanged(circleId: String, userId: String, email: String) {
        val text = context.getString(R.string.membership_question_invitee_changed, email)
        post(RENEW, circleId, userId, detailed = text, namesOnly = text)
    }

    override fun answered(circleId: String, userId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(tag(circleId, userId), NOTIFICATION_ID) }
    }

    override fun clear() {
        runCatching { posted.edit().clear().apply() }
    }

    private fun post(kind: String, circleId: String, userId: String, detailed: String, namesOnly: String) {
        if (MembershipQuestionNotifier.circlesScreenShowing) return
        val key = "$kind|${AccountIds.storeKey(circleId)}|${AccountIds.storeKey(userId)}"
        val at = now()
        val last = runCatching { posted.getLong(key, 0L) }.getOrDefault(0L)
        if (last != 0L && at - last < ONCE_A_DAY_MS) return
        // As every other notification here, per Settings → Notification detail; the generic
        // level says what it is without a name (the same words as on iPhone).
        val text = when (sessionManager.getNotificationDetailLevel()) {
            SessionManager.NOTIF_DETAIL_NAMES -> namesOnly
            SessionManager.NOTIF_DETAIL_GENERIC -> context.getString(R.string.membership_question_generic)
            else -> detailed
        }
        try {
            ensureChannel()
            val open = Intent().apply {
                setClassName(context, "com.pingolin.track.ui.main.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("navigate_to", NAVIGATE_TO)
                putExtra("circle_id", circleId)
            }
            val tap = android.app.PendingIntent.getActivity(
                context,
                key.hashCode(),
                open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(tag(circleId, userId), NOTIFICATION_ID, notification)
            posted.edit().putLong(key, at).apply()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the question still waits on the Circles screen.
            Log.w(TAG, "Cannot post a circle question: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post a circle question: ${e.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.membership_question_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.membership_question_channel_description) }
        )
    }

    private fun tag(circleId: String, userId: String) = "circle-question:${AccountIds.storeKey(circleId)}:${AccountIds.storeKey(userId)}"

    companion object {
        private const val TAG = "MembershipQuestion"
        private const val STORE = "circle questions posted"
        private const val RESET = "reset"
        private const val JOIN = "join"
        private const val RENEW = "renew"
        private val KINDS = listOf(RESET, JOIN, RENEW)

        /** A question posted under two spellings of its circle or member: the later post stands. */
        internal val RULES = object : CircleEntries.Rules {
            override fun rest(rest: String): String = if (rest.startsWith("|")) "|${AccountIds.storeKey(rest.substring(1))}" else rest

            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) =
                entries.filterKeys { it.startsWith("|") }.mapValues { (_, held) -> CircleEntries.latest(held) }
        }
        const val CHANNEL_ID = "circle_requests"
        const val NAVIGATE_TO = "circle_question"
        private const val NOTIFICATION_ID = 1004
        private const val PREFS = "circle_questions_posted"
        internal const val ONCE_A_DAY_MS = 24 * 60 * 60_000L
    }
}
