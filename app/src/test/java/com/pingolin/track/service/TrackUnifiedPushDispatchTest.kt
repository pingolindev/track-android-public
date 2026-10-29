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

package com.pingolin.track.service

import com.pingolin.track.service.PushSignals.Action
import com.pingolin.track.util.AlertHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a push message makes the app do — UnifiedPush and FCM both decide through
 * [PushSignals].
 *
 * A missing branch here fails silently, and always looks the same from outside: a
 * de-Googled install that receives pushes and shows nothing. The opposite failure is
 * pinned too: a push is never rendered. A UnifiedPush message is an unsigned POST
 * anybody holding the endpoint URL can send, so its fields must never become
 * notification text.
 */
class TrackUnifiedPushDispatchTest {

    @Test
    fun `a tickle fetches the notifications it is telling us about`() {
        // Every mobile push the server sends is a tickle (`"type": "tickle"`), and the
        // push relay sends that same map to the UnifiedPush endpoint, so this is the
        // whole notification pipeline for the libre flavour: if a tickle did
        // nothing, no place alert, SOS or arrival would reach a de-Googled device.
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("tickle"))
    }

    @Test
    fun `the content-free wake-ups keep their own actions`() {
        // Neither is a notification: one restarts tracking, the other re-evaluates
        // this device's own rules against its own data.
        assertEquals(Action.WAKE_LOCATION, PushSignals.actionFor("wake_location"))
        assertEquals(Action.CHECK_OWN_ALERTS, PushSignals.actionFor("alerts_check"))
    }

    @Test
    fun `a typed payload is only a reason to fetch, never rendered`() {
        // Rendered straight from the payload, with the payload's own names,
        // `{"type":"sos_alert","user_name":"Mum"}` from whoever runs the
        // distributor would be a max-priority "Mum needs attention". Fetching
        // instead shows only what the authenticated API returns, and the
        // place-alert types still wake the fetch.
        for (type in listOf(
            AlertHandler.TYPE_SOS_ALERT,
            AlertHandler.TYPE_SOS_ACKNOWLEDGED,
            AlertHandler.TYPE_SOS_RESOLVED,
            AlertHandler.TYPE_PLACE_ARRIVAL,
            AlertHandler.TYPE_PLACE_DEPARTURE,
            AlertHandler.TYPE_PLACE_ALERT_ENTER,
            AlertHandler.TYPE_PLACE_ALERT_EXIT,
            AlertHandler.TYPE_PLACE_ALERT,
            AlertHandler.TYPE_CHECK_IN,
            AlertHandler.TYPE_CIRCLE_INVITE,
            AlertHandler.TYPE_MEMBER_JOINED,
            AlertHandler.TYPE_LOW_BATTERY,
            AlertHandler.TYPE_INACTIVITY,
            AlertHandler.TYPE_OFFLINE,
            AlertHandler.TYPE_BACK_ONLINE,
            "not_a_type",
        )) {
            assertEquals(type, Action.FETCH_NOTIFICATIONS, PushSignals.actionFor(type))
        }
    }

    @Test
    fun `near-misses of the wake-ups do not wake anything`() {
        // Only the exact strings restart tracking or re-evaluate rules; a variant is
        // at most a fetch.
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("Wake_location"))
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("wake_location_"))
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("ALERTS_CHECK"))
    }

    @Test
    fun `a restore's nudge looks again for identities, whatever else the message carries`() {
        assertEquals(Action.CHECK_IDENTITIES, PushSignals.actionFor("identities_check"))
        assertEquals(Action.CHECK_IDENTITIES, PushSignals.actionFor(mapOf("type" to "identities_check")))
        // Extra fields make no difference: only the type is read.
        assertEquals(
            Action.CHECK_IDENTITIES,
            PushSignals.actionFor(mapOf("type" to "identities_check", "user_id" to "u", "identities" to "[...]", "count" to "9")),
        )
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("IDENTITIES_CHECK"))
    }

    @Test
    fun `an SOS sealed again is a quiet look at the SOS list, whatever else the message carries`() {
        assertEquals(Action.REFRESH_SOS, PushSignals.actionFor("sos_refresh"))
        assertEquals(Action.REFRESH_SOS, PushSignals.actionFor(mapOf("type" to "sos_refresh", "alert_type" to "sos_refresh")))
        assertEquals(Action.REFRESH_SOS, PushSignals.actionFor(mapOf("type" to "sos_refresh", "sos_id" to "s", "user_name" to "Mum")))
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("SOS_REFRESH"))
        // Both transports start the same quiet worker for it.
        for (relative in listOf(
            "main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt",
            "gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt",
        )) {
            assertTrue(relative, codeOnly(source(relative)).contains("SosRefreshWorker.runNow("))
        }
    }

    @Test
    fun `a message with no type does nothing`() {
        assertEquals(Action.IGNORE, PushSignals.actionFor(null))
        assertEquals(Action.IGNORE, PushSignals.actionFor(""))
        assertEquals(Action.IGNORE, PushSignals.actionFor("   "))
    }

    @Test
    fun `neither transport hands a push payload to AlertHandler`() {
        // Source-level, because both receivers are Android components a JVM test
        // cannot run. A direct path such as `getAlertHandler(context).handleAlert(`
        // in the UnifiedPush receiver or `alertHandler.handleAlert(type, data)` in
        // the FCM service would hand a push payload to AlertHandler.
        for (relative in listOf(
            "main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt",
            "gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt",
        )) {
            val code = codeOnly(source(relative))
            assertEquals(
                "$relative must not call handleAlert — a push is a signal, not content",
                false,
                code.contains("handleAlert("),
            )
        }
    }

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "app/src/$relative"), File(dir, "src/$relative"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not find src/$relative from ${File("").absolutePath}. Fix the lookup " +
                "rather than deleting the test — an unfindable file guards nothing."
        )
    }

    /** Strip comments: they may name `handleAlert` while
     * explaining why it isn't called.
     */
    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `a privacy change reads the circle list again, quietly, and its near-misses wake nothing`() {
        assertEquals(Action.REFRESH_PRIVACY, PushSignals.actionFor("privacy_check"))
        assertEquals(Action.REFRESH_PRIVACY, PushSignals.actionFor(mapOf("type" to "privacy_check", "alert_type" to "privacy_check")))
        // The wake carries no circle: one it named anyway changes nothing.
        assertEquals(Action.REFRESH_PRIVACY, PushSignals.actionFor(mapOf("type" to "privacy_check", "circle_id" to "c-1")))
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("PRIVACY_CHECK"))
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("privacy_check_"))
        // The resumed notice is a row to fetch and show (AlertHandler), as any other type.
        assertEquals(Action.FETCH_NOTIFICATIONS, PushSignals.actionFor("privacy_resumed"))
    }

    @Test
    fun `both transports hand a privacy change to its quiet worker, and the worker posts nothing`() {
        fun src(path: String) = java.io.File(path).readText()
        assertTrue(src("src/main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt")
            .contains("PushSignals.Action.REFRESH_PRIVACY -> com.pingolin.track.worker.PrivacyCheckWorker.runNow(context)"))
        val fcm = src("src/gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt")
        assertTrue(fcm.contains("if (action == PushSignals.Action.REFRESH_PRIVACY) {"))
        assertTrue(fcm.contains("com.pingolin.track.worker.PrivacyCheckWorker.runNow(applicationContext)"))
        val worker = src("src/main/java/com/pingolin/track/worker/PrivacyCheckWorker.kt")
        for (shows in listOf("NotificationManager", "NotificationCompat", "AlertHandler", "showNotification")) {
            assertFalse(shows, worker.contains(shows))
        }
        assertTrue("the 5-minute refresh's own call", worker.contains("circleRepository.refreshCircles()"))
    }
}
