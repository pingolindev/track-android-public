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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.ui.sos.SosPositionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

/**
 * An inactivity alert says when the member last shared their location, as a clock time (with the
 * date when it isn't today): "{name} hasn't shared their location since {time}." — never "since
 * 1 hour ago", which reads wrong and goes stale on the bell row as the hours go by. The
 * notification is titled "Inactivity", as the bell, the list and the history name it, not
 * "Offline", which guesses a cause.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InactivityWordsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun todayAt(hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `the clock time of the last share, today`() {
        val at = todayAt(0, 7)
        val words = inactiveSince(context, "Bo", at)
        assertEquals("Bo hasn't shared their location since ${SosPositionText.time(context, at)}.", words)
        assertTrue("a clock time: $words", Regex("""\d{1,2}:07""").containsMatchIn(words))
        assertFalse(words, words.contains(" ago"))
    }

    @Test
    fun `with the date when it wasn't today`() {
        val at = todayAt(9, 30) - 2 * 86_400_000L
        val words = inactiveSince(context, "Bo", at)
        assertEquals("Bo hasn't shared their location since ${SosPositionText.time(context, at)}.", words)
        val month = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(java.util.Date(at))
        assertTrue("the date is in it: $words", words.contains(month))
        assertFalse(words, words.contains(" ago"))
    }

    @Test
    fun `a name not readable yet is "A member" - never a sentence that starts with a space`() {
        val at = todayAt(9, 30)
        assertEquals("A member hasn't shared their location since ${SosPositionText.time(context, at)}.", inactiveSince(context, " ", at))
        assertEquals("A member hasn't shared their location for a while.", inactivityMessage(context, mapOf("user_name" to " ")))
    }

    @Test
    fun `every inactivity alert is said in these words, in the shade and in the bell`() {
        val monitoring = File("src/main/java/com/pingolin/track/util/MonitoringManager.kt").readText()
        // The legacy monitor, a circle-mate's "watch me" request, and this account's rules.
        for (site in listOf("private suspend fun handleInactivityAlert(", "private suspend fun notifySelfAlertInactive(", "private suspend fun notifyInactive(")) {
            val body = monitoring.substringAfter(site).substringBefore("\n    }\n")
            assertTrue(site, body.contains("inactiveSince(context, "))
            assertTrue(site, body.contains("AlertHandler.KEY_INACTIVE_SINCE to "))
            assertFalse(site, body.contains("ago"))
        }
        assertEquals("both bell rows", 2, Regex("""body = since,""").findAll(monitoring).count())
        assertFalse(monitoring.contains("agoLabel"))
        val handler = File("src/main/java/com/pingolin/track/util/AlertHandler.kt").readText()
        val offline = handler.substringAfter("private fun handleOffline(").substringBefore("\n    }\n")
        assertTrue(offline.contains("title = \"Inactivity\","))
        assertTrue(offline.contains("message = inactivityMessage(context, data),"))
    }

    @Test
    fun `a server row's words are never an inactivity alert's - only this phone's evaluator's are`() {
        fun row(body: String?) = com.pingolin.track.service.NotificationTickleFetcher.alertDataFor(
            com.pingolin.track.data.remote.dto.NotificationResponse(
                id = "n-1", circleId = "c0ffee00-0000-4000-8000-0000000000c1", notificationType = AlertHandler.TYPE_INACTIVITY,
                title = "Inactivity", body = body, metadata = mapOf(AlertHandler.KEY_INACTIVE_SINCE to "words of the server's"),
            )
        )
        // As the server writes an inactivity row, and empty: both get this app's words.
        for (body in listOf("A circle member may need attention.", "", null)) {
            assertEquals("A member hasn't shared their location for a while.", inactivityMessage(context, row(body)))
        }
        // This phone's evaluator's words, as MonitoringManager hands them over.
        val at = todayAt(9, 30)
        val since = inactiveSince(context, "Bo", at)
        assertEquals(since, inactivityMessage(context, mapOf("user_name" to "Bo", AlertHandler.KEY_INACTIVE_SINCE to since)))
        // And a row can't carry that key.
        assertFalse(AlertHandler.KEY_INACTIVE_SINCE in com.pingolin.track.service.NotificationTickleFetcher.SERVER_METADATA_KEYS)
    }

    @Test
    fun `the words, word for word`() {
        assertEquals("Bo hasn't shared their location since 3:37 PM.", context.getString(com.pingolin.track.R.string.inactivity_since, "Bo", "3:37 PM"))
        assertEquals("Bo hasn't shared their location for a while.", context.getString(com.pingolin.track.R.string.inactivity_no_time, "Bo"))
    }
}
