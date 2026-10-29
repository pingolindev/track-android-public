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
import java.io.File

/**
 * If you decline a share, nothing should show up: an invitation answered here, yes or no, takes its
 * banner off the shade. One banner per circle's invitation, however often it's sent, read back from
 * the platform's notification manager.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class InviteBannerAnsweredTest {

    private companion object {
        const val FAMILY = "f0000000-0000-4000-8000-0000000000f1"
        const val CREW = "c0000000-0000-4000-8000-0000000000c2"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val session = mockk<SessionManager>(relaxed = true)
    private lateinit var handler: AlertHandler

    @Before
    fun setup() {
        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        manager.createNotificationChannel(
            NotificationChannel(TrackApplication.ALERTS_CHANNEL_ID, "Alerts & Notifications", NotificationManager.IMPORTANCE_HIGH)
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
    }

    /** What's posted now, group summaries left out. */
    private fun posted(): List<Notification> =
        manager.activeNotifications.map { it.notification }.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun invite(row: String, circle: String) = handler.handleAlert(
        AlertHandler.TYPE_CIRCLE_INVITE,
        mapOf("alert_id" to row, "circle_id" to circle, "inviter_name" to "Alex", "circle_name" to "Crew"),
    )

    @Test
    fun `an invitation sent again is one banner`() {
        invite("row-1", CREW)
        invite("row-2", CREW)

        assertEquals(1, posted().size)
    }

    @Test
    fun `answering it takes its banner down, and only its own`() {
        invite("row-1", CREW)
        invite("row-2", FAMILY)

        handler.cancelInvite(CREW.uppercase())

        assertEquals(1, posted().size)
        assertTrue(posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("invited you"))
        handler.cancelInvite(FAMILY)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `the circles screen answers with the banner going, both ways`() {
        val screen = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(screen.contains("alertHandler.cancelInvite(state.circleId)"))
        assertTrue(screen.contains("state.circleId?.let { alertHandler.cancelInvite(it) }"))
        assertTrue(screen.contains("viewModel.declineInvite(invite.id, invite.circleId)"))
        val model = File("src/main/java/com/pingolin/track/ui/circles/CirclesViewModel.kt").readText()
        assertTrue(model.contains("CirclesUiState.InviteDeclined(circleId)"))
    }
}
