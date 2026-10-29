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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The claimant's notice that the owner refused their request to take over a circle: a known type,
 * never dropped as unknown ("Unhandled alert type … notification dropped"), so the claimant's phone
 * says so. Read back from the platform's notification manager.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OwnershipRefusedAlertTest {

    private companion object {
        const val CREW = "c0000000-0000-4000-8000-0000000000c2"
        const val WORDS = "The owner refused your request to take over the circle. It stays theirs."
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

    /** The row as the tickle fetch hands it over (NotificationTickleFetcher.alertDataFor), named or not. */
    private fun refused(circle: String = CREW, body: String = WORDS, circleName: String? = null) = handler.handleAlert(
        AlertHandler.TYPE_CIRCLE_OWNERSHIP_REFUSED,
        mapOf(
            "alert_id" to "row-1", "circle_id" to circle, "claim_id" to "k-1",
            "title" to "Your request to take over was refused", "body" to body,
        ) + listOfNotNull(circleName?.let { "circle_name" to it }),
    )

    @Test
    fun `the refusal is shown in its own words, and its tap opens that circle`() {
        refused(circle = CREW.uppercase())

        val shown = posted().single()
        assertEquals("Your request to take over was refused", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(WORDS, shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val tap = shadowOf(shown.contentIntent).savedIntent
        // The claim's own route: MainActivity opens the circles tab and that circle's sheet.
        assertEquals("circle_ownership_claim", tap.getStringExtra("navigate_to"))
        assertEquals(CREW, tap.getStringExtra("circle_id"))
    }

    @Test
    fun `with full detail it names the circle, and names only keeps the row's words`() {
        refused(circleName = "Crew")
        assertEquals(
            "The owner refused your request to take over Crew. It stays theirs.",
            posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )

        manager.cancelAll()
        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_NAMES
        refused(circleName = "Crew")
        assertEquals(WORDS, posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `with no circle it can name, the tap opens the circles tab`() {
        refused(circle = "")

        assertEquals("circles", shadowOf(posted().single().contentIntent).savedIntent.getStringExtra("navigate_to"))
    }

    @Test
    fun `at the generic level the lock screen says only what happened`() {
        every { session.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_GENERIC
        refused()

        val shown = posted().single()
        assertEquals("Your request to take over was refused", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(AlertHandler.GENERIC_MESSAGE, shown.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun `the claim's tap route and the bell row both open the circle`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val route = main.substring(main.indexOf("\"circle_ownership_claim\" -> {"))
        assertTrue(route.substring(0, route.indexOf("\n            }\n")).contains("viewModel.requestOwnershipClaimScreen(circleId)"))
        val bell = File("src/main/java/com/pingolin/track/ui/notifications/NotificationsFragment.kt").readText()
        val row = bell.substring(bell.indexOf("\"circle_ownership_claim\", \"circle_ownership_refused\" -> {"))
        assertTrue(row.substring(0, row.indexOf("\n            }\n")).contains("mainViewModel.requestOwnershipClaimScreen(circleId)"))
    }
}
