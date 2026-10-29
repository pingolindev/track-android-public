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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.TrackApplication
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What an SOS push posts after its check, read back from the platform's notification manager: the
 * alarm keeps its usual words and channel, names only what this device opened, rings in the
 * generic words when nothing could be checked, posts nothing for an SOS that isn't live, and the
 * follow-ups keep their words with no sound. A refresh's first alarm is the same post, and one
 * that already rang here has no sound.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SosAlarmNotificationTest {

    private companion object {
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val session = mockk<SessionManager>(relaxed = true)
    private var level = SessionManager.NOTIF_DETAIL_FULL
    private lateinit var handler: AlertHandler

    @Before
    fun setup() {
        every { session.getNotificationDetailLevel() } answers { level }
        // The two channels as TrackApplication makes them.
        manager.createNotificationChannels(listOf(
            NotificationChannel(TrackApplication.SOS_CHANNEL_ID, "Circle emergency alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(TrackApplication.ALERTS_CHANNEL_ID, "Alerts & Notifications", NotificationManager.IMPORTANCE_HIGH),
        ))
        handler = AlertHandler(
            context,
            mockk(relaxed = true),
            mockk(relaxed = true),
            session,
            mockk(relaxed = true),
            dagger.Lazy { mockk(relaxed = true) },
            dagger.Lazy { mockk(relaxed = true) },
        )
    }

    /** What's posted now, group summaries left out. */
    private fun posted(): List<Notification> =
        manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun push(vararg extra: Pair<String, String>) =
        handler.handleAlert(AlertHandler.TYPE_SOS_ALERT, mapOf("alert_id" to "row-1", "sos_id" to SOS) + extra)

    private fun alarming(raiser: String? = "Alice", circle: String? = "Family") = arrayOf(
        AlertHandler.KEY_SOS_CHECK to AlertHandler.SOS_CHECK_ALARMING,
        *listOfNotNull(raiser?.let { AlertHandler.KEY_SOS_RAISER_NAME to it }, circle?.let { AlertHandler.KEY_SOS_CIRCLE_NAME to it }).toTypedArray(),
    )

    @Test
    fun `no check could run - the alarm, in the generic words, on the SOS channel, with its sound`() {
        push()
        val alarm = posted().single()
        assertEquals(TrackApplication.SOS_CHANNEL_ID, alarm.channelId)
        assertEquals("A circle member needs attention", alarm.title())
        assertEquals("Asked their circle to check on them in your circle", alarm.text())
        assertEquals("it rings", Notification.GROUP_ALERT_ALL, alarm.groupAlertBehavior)
    }

    @Test
    fun `checked and alarming - named as this device opened the names`() {
        push(*alarming())
        val alarm = posted().single()
        assertEquals(TrackApplication.SOS_CHANNEL_ID, alarm.channelId)
        assertEquals("Alice needs attention", alarm.title())
        assertEquals("Asked their circle to check on them in Family", alarm.text())
        assertEquals(Notification.GROUP_ALERT_ALL, alarm.groupAlertBehavior)

        handler.cancelSos(SOS)
        push(*alarming(raiser = null, circle = null))
        assertEquals("no name opened: the generic words", "A circle member needs attention", posted().single().title())
        assertEquals("Asked their circle to check on them in your circle", posted().single().text())
    }

    @Test
    fun `names only with an alarming check, never the row's words`() {
        push(AlertHandler.KEY_SOS_RAISER_NAME to "Mallory", AlertHandler.KEY_SOS_CIRCLE_NAME to "Forged", "user_name" to "Mum", "circle_name" to "Family")
        val alarm = posted().single()
        assertEquals("A circle member needs attention", alarm.title())
        assertEquals("Asked their circle to check on them in your circle", alarm.text())
    }

    @Test
    fun `the detail levels - no name at all at generic, the title included, and only who at names`() {
        level = SessionManager.NOTIF_DETAIL_GENERIC
        push(*alarming())
        assertEquals("A circle member needs attention", posted().single().title())
        assertEquals(AlertHandler.GENERIC_MESSAGE, posted().single().text())

        handler.cancelSos(SOS)
        level = SessionManager.NOTIF_DETAIL_NAMES
        push(*alarming())
        assertEquals("Alice needs attention", posted().single().title())
        assertEquals("Alice needs attention", posted().single().text())
    }

    @Test
    fun `checked and not live - nothing posted, and an earlier post of it goes`() {
        push()
        assertEquals(1, posted().size)
        push(AlertHandler.KEY_SOS_CHECK to AlertHandler.SOS_CHECK_NOT_LIVE)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a later check takes the post down by the SOS in any spelling`() {
        push()
        handler.cancelSos(SOS.uppercase())
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `an SOS that already rang here - its post again, with no sound`() {
        push(*alarming(), AlertHandler.KEY_SOS_SILENT to "1")
        val again = posted().single()
        assertEquals(TrackApplication.SOS_CHANNEL_ID, again.channelId)
        assertEquals("Alice needs attention", again.title())
        assertEquals("silenced", Notification.GROUP_ALERT_SUMMARY, again.groupAlertBehavior)
        assertNull(again.sound)
        assertNull(again.vibrate)

        handler.cancelSos(SOS)
        push(AlertHandler.KEY_SOS_SILENT to "1")
        assertEquals("can't check, rang here: no sound", Notification.GROUP_ALERT_SUMMARY, posted().single().groupAlertBehavior)
    }

    @Test
    fun `a refresh's first alarm - the checked push's post, its words, channel and sound, keyed by the SOS`() {
        handler.ringSos(SOS, "Alice", "Family")
        val alarm = posted().single()
        assertEquals(TrackApplication.SOS_CHANNEL_ID, alarm.channelId)
        assertEquals("Alice needs attention", alarm.title())
        assertEquals("Asked their circle to check on them in Family", alarm.text())
        assertEquals("it rings", Notification.GROUP_ALERT_ALL, alarm.groupAlertBehavior)

        handler.cancelSos(SOS.uppercase())
        assertTrue("one post per SOS, taken down by its id", posted().isEmpty())
        handler.ringSos(SOS, null, null)
        assertEquals("no name opened: the generic words", "A circle member needs attention", posted().single().title())
        assertEquals("Asked their circle to check on them in your circle", posted().single().text())

        handler.cancelSos(SOS)
        level = SessionManager.NOTIF_DETAIL_GENERIC
        handler.ringSos(SOS, "Alice", "Family")
        assertEquals("A circle member needs attention", posted().single().title())
    }

    @Test
    fun `the follow-ups keep their words, with no sound`() {
        handler.handleAlert(AlertHandler.TYPE_SOS_ACKNOWLEDGED, mapOf("alert_id" to "row-2", "sos_id" to SOS))
        handler.handleAlert(AlertHandler.TYPE_SOS_RESOLVED, mapOf("alert_id" to "row-3", "sos_id" to SOS))
        val byTitle = posted().associateBy { it.title() }
        assertEquals(setOf("Acknowledged", "Resolved"), byTitle.keys)
        // Who is responding: "Someone" when the push names nobody.
        assertEquals("Someone is responding", byTitle.getValue("Acknowledged").text())
        for (followUp in byTitle.values) {
            assertEquals("silenced in its group", Notification.GROUP_ALERT_SUMMARY, followUp.groupAlertBehavior)
            assertNull(followUp.sound)
            assertNull(followUp.vibrate)
        }
    }
}
