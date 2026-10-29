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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A pause that ran out: the server's `privacy_resumed` row is posted in plain words at the detail
 * level set here, never dropped as an unknown type, and the circle list is read again at once.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PrivacyResumedNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val session = mockk<SessionManager>(relaxed = true)
    private var level = SessionManager.NOTIF_DETAIL_FULL
    private var refreshed = 0
    private lateinit var handler: AlertHandler

    @Before
    fun setup() {
        every { session.getNotificationDetailLevel() } answers { level }
        manager.createNotificationChannel(
            NotificationChannel(TrackApplication.ALERTS_CHANNEL_ID, "Alerts & Notifications", NotificationManager.IMPORTANCE_HIGH),
        )
        handler = AlertHandler(
            context,
            mockk(relaxed = true),
            mockk(relaxed = true),
            session,
            mockk(relaxed = true),
            dagger.Lazy { mockk(relaxed = true) },
            dagger.Lazy { mockk(relaxed = true) },
        )
        handler.refreshPrivacy = { refreshed++ }
    }

    /** What's posted now, group summaries left out. */
    private fun posted(): List<Notification> =
        manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** As the fetch hands it over: the row's id, its time, and the circle's name when this device knows it. */
    private fun resumed(row: String, circle: String? = "Family") = handler.handleAlert(
        AlertHandler.TYPE_PRIVACY_RESUMED,
        mapOf("alert_id" to row, "created_at" to "2026-10-14T18:25:00.123456Z") +
            listOfNotNull(circle?.let { "circle_name" to it }),
    )

    @Test
    fun `a pause that ran out is said, naming the circle at full detail, and the circle list is read again`() {
        resumed("row-1")

        val notice = posted().single()
        assertEquals(TrackApplication.ALERTS_CHANNEL_ID, notice.channelId)
        assertEquals("Location sharing resumed", notice.title())
        val text = notice.text()!!
        assertTrue(text, text.startsWith("Your pause in Family has ended, and Family can see your location again. · "))
        assertEquals(1, refreshed)
    }

    @Test
    fun `names only leaves the circle out, generic says only that there is something, and the title stays`() {
        level = SessionManager.NOTIF_DETAIL_NAMES
        resumed("row-1")
        level = SessionManager.NOTIF_DETAIL_GENERIC
        resumed("row-2")

        val notices = posted()
        assertEquals(2, notices.size)
        assertTrue(notices.map { it.text() }.toString(), notices.any {
            it.text()!!.startsWith("Your pause has ended, and your circle can see your location again. · ")
        })
        assertTrue(notices.map { it.text() }.toString(), notices.any { it.text() == AlertHandler.GENERIC_MESSAGE })
        for (notice in notices) {
            assertEquals("Location sharing resumed", notice.title())
            assertTrue(notice.text()!!, !notice.text()!!.contains("Family"))
        }
        assertEquals(2, refreshed)
    }

    @Test
    fun `a circle this device can't name is still said, in the words that name none`() {
        resumed("row-1", circle = null)

        assertTrue(posted().single().text()!!.startsWith("Your pause has ended, and your circle can see your location again. · "))
    }

    @Test
    fun `the same row fetched twice is one notice`() {
        resumed("row-1")
        resumed("row-1")

        assertEquals(1, posted().size)
    }
}
