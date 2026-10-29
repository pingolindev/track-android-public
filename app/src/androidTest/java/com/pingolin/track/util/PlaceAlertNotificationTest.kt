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
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.TrackApplication
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that AlertHandler posts place-arrival and place-departure notifications
 * with the expected title/body when this device's own evaluation reports a
 * boundary crossing (`local_evaluated`), on that path's own channel. A
 * server-routed push renders none of the names its data carries: it keeps the
 * server's words or names itself from what this device holds (see
 * ServerPlacePushTest), so these drive the local copy, the one that says them.
 *
 * Note on boundary-cross end-to-end testing:
 *   Boundary crossing is handled by PlaceAlertEvaluator.onLocationsPersisted(),
 *   which:
 *     - needs real Place/PlaceAlert/state/ledger DAOs (Room)
 *     - is reached only through LocationRepository.persistAndEvaluate
 *     - requires place alerts + places in Room with matching IDs
 *
 *   Rather than wiring up that full graph in androidTest, these tests check
 *   the contract at the layer that actually renders the user-visible
 *   notification — AlertHandler — which is the final consumer of both the
 *   boundary event AND of the local evaluator's surfacing path (both end in a
 *   NotificationCompat.Builder with the same channel + priority).
 *
 *   If AlertHandler correctly renders arrival/departure with the right
 *   channel/priority/content, then the place-alert user experience is
 *   validated end-to-end from the point of view of the user seeing a
 *   notification in the shade.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class PlaceAlertNotificationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var alertHandler: AlertHandler

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            for ((id, name) in listOf(
                TrackApplication.ALERTS_CHANNEL_ID to "Alerts & Notifications",
                TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID to "Place Alerts",
            )) {
                notificationManager.createNotificationChannel(
                    android.app.NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }

        notificationManager.cancelAll()

        // AlertHandler asks PlaceAlertEvaluator whether a server-routed place
        // push is already in the shade (planServerPush). These tests are about
        // how a notification RENDERS, not about pairing, so the stub answers
        // "post it" — the branch that actually posts, rather than whatever a
        // bare relaxed mock would make of planServerPush.
        val placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true)
        coEvery { placeAlertEvaluator.planServerPush(any(), any(), any(), any(), any()) } returns
            PlaceAlertEvaluator.ServerPushOutcome.POST

        alertHandler = AlertHandler(
            context = context,
            circleRepository = mockk(relaxed = true),
            locationRepository = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            locationProvider = mockk(relaxed = true),
            placeAlertEvaluator = { placeAlertEvaluator },
            sosRepository = { mockk(relaxed = true) },
        )
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    /**
     * Arrival at a known place → notification with:
     *   - channel = PLACE_ALERTS_LOCAL_CHANNEL_ID (not LOCATION or SOS)
     *   - title = "Arrival"
     *   - text = "<user_name> arrived at <place_name>"
     */
    @Test
    fun placeArrival_rendersWithCorrectChannelAndText() {
        alertHandler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf(
                "user_name" to "Dave Davidson",
                "place_name" to "Grandma's House",
                "local_evaluated" to "true",
            )
        )

        val sbn = findNotification(title = "Arrival")
        assertNotNull("Arrival notification should be posted", sbn)

        assertEquals(
            "Must use the local place-alert channel, not SOS",
            TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID,
            sbn!!.notification.channelId
        )
        assertStampedBody(
            "Dave Davidson arrived at Grandma's House",
            sbn.notification.extras.getString(Notification.EXTRA_TEXT)
        )
        // Auto-cancel set
        assertTrue(
            (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    /**
     * Departure from a known place → symmetric to arrival but with
     * title = "Departure" and text "<user_name> left <place_name>".
     */
    @Test
    fun placeDeparture_rendersWithCorrectChannelAndText() {
        alertHandler.handleAlert(
            AlertHandler.TYPE_PLACE_DEPARTURE,
            mapOf(
                "user_name" to "Eve Everhart",
                "place_name" to "Coffee Shop",
                "local_evaluated" to "true",
            )
        )

        val sbn = findNotification(title = "Departure")
        assertNotNull("Departure notification should be posted", sbn)

        assertEquals(
            TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID,
            sbn!!.notification.channelId
        )
        assertStampedBody(
            "Eve Everhart left Coffee Shop",
            sbn.notification.extras.getString(Notification.EXTRA_TEXT)
        )
    }

    /**
     * Missing user_name falls back to the generic label "A member".
     * This matches AlertHandler.handlePlaceCrossing's fallback for the local copy.
     */
    @Test
    fun placeArrival_missingUserName_fallsBackToGenericLabel() {
        alertHandler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf("place_name" to "Home", "local_evaluated" to "true")
        )

        val sbn = findNotification(title = "Arrival")
        assertNotNull(sbn)
        assertStampedBody(
            "A member arrived at Home",
            sbn!!.notification.extras.getString(Notification.EXTRA_TEXT)
        )
    }

    /**
     * Both place arrival and departure must be grouped under a PLACES
     * notification group so they bundle in the shade (AlertHandler.placesLocalGroup
     * for the local copy). We can verify this via Notification.getGroup().
     */
    @Test
    fun placeArrival_usesPlacesGroup() {
        alertHandler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf("user_name" to "G", "place_name" to "H", "local_evaluated" to "true")
        )
        val sbn = findNotification(title = "Arrival")
        assertNotNull(sbn)
        val group = sbn!!.notification.group
        assertNotNull(
            "Place arrival notification must have a group key for bundling",
            group
        )
        assertTrue(
            "Group key must be in PLACES namespace — was: $group",
            group!!.contains("PLACES")
        )
    }

    // ===== Helpers =====

    /**
     * Place and destination bodies carry a short local date + time —
     * "Dave arrived at Home · 7/28, 7:25 PM". Assert the copy exactly and the
     * stamp by shape: the literal rendering is locale- and settings-dependent
     * (d/M ordering, 24-hour clock), so pinning a fixed string here would make
     * the test pass only on a US-locale, 12-hour device.
     */
    private fun assertStampedBody(expectedCopy: String, actual: String?) {
        assertNotNull("No notification body", actual)
        val separator = actual!!.lastIndexOf(" \u00b7 ")
        assertTrue("Body '$actual' carries no ' \u00b7 <stamp>' suffix", separator > 0)

        assertEquals(expectedCopy, actual.substring(0, separator))

        val stamp = actual.substring(separator + 3)
        // <date>, <time> — digits and separators either side of the comma.
        assertTrue(
            "Stamp '$stamp' is not a short date + time",
            Regex("""^[\d./-]+, .*\d.*$""").matches(stamp),
        )
    }

    /**
     * Polls rather than sleeping once. A place crossing is posted from
     * AlertHandler's backgroundScope (handlePlaceWake -> withTimeoutOrNull ->
     * post), so a single short sleep is a race that any work added to that
     * path tips over. Polling to a deadline still finds a present notification
     * immediately, and an absent one costs the timeout only when the test is
     * going to fail anyway.
     */
    private fun findNotification(title: String): android.service.notification.StatusBarNotification? {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            matchNotification(title)?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    private fun matchNotification(title: String): android.service.notification.StatusBarNotification? {
        return notificationManager.activeNotifications.firstOrNull { sbn ->
            sbn.notification.extras?.getString(Notification.EXTRA_TITLE) == title
        }
    }
}
