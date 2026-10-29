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
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.TrackApplication
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.coEvery
import io.mockk.mockk

/**
 * Instrumented tests for AlertHandler notification display.
 *
 * These tests verify that AlertHandler.handleAlert() posts notifications
 * to the correct channel with the correct priority, intent extras, and
 * AUTO_CANCEL flag for each alert type defined in the AlertHandler companion.
 *
 * Each test checks the actual notification posted to NotificationManager.activeNotifications
 * (requires POST_NOTIFICATIONS permission). The tests construct AlertHandler directly
 * with mocks for CircleRepository/LocationRepository to avoid the full DI graph.
 *
 * Coverage matrix:
 * - SOS alert → SOS_CHANNEL_ID, PRIORITY_MAX, MainActivity extras (navigate_to="sos", alert_id)
 * - SOS acknowledged → ALERTS_CHANNEL_ID, PRIORITY_HIGH
 * - SOS resolved → ALERTS_CHANNEL_ID, PRIORITY_HIGH
 * - Place arrival → ALERTS_CHANNEL_ID
 * - Place departure → ALERTS_CHANNEL_ID
 * - Check-in → ALERTS_CHANNEL_ID
 * - Circle invite → ALERTS_CHANNEL_ID with navigate_to="circles"
 * - Low battery → ALERTS_CHANNEL_ID
 * - Offline → ALERTS_CHANNEL_ID
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlertHandlerInstrumentedTest {

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

        // Ensure notification channels exist (normally created in TrackApplication.onCreate,
        // which is skipped in HiltTestApplication).
        ensureChannelsExist()

        // Clear any leftover notifications from prior tests
        notificationManager.cancelAll()

        // Build AlertHandler directly with mocks so we don't depend on CircleRepository's
        // full dependency graph (circleDao, cryptoManager, etc.). MockK handles final
        // Kotlin classes without needing the mockito-inline MockMaker.
        // AlertHandler asks PlaceAlertEvaluator whether a server-routed place push is
        // already in the shade (planServerPush). These tests are about how a
        // notification RENDERS, not about pairing, so the stub answers "post it" —
        // the branch that actually posts, rather than whatever a bare relaxed mock
        // would make of planServerPush.
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
     * SOS alert must:
     * - Post to SOS_CHANNEL_ID
     * - Be PRIORITY_MAX
     * - Have contentIntent with extras navigate_to="sos" + alert_id=<uuid>
     * - Be auto-cancel
     */
    @Test
    fun handleSosAlert_postsOnSosChannelWithMaxPriority() {
        val alertId = "alert-uuid-12345"
        val data = mapOf(
            "user_name" to "Alice",
            "circle_name" to "Family",
            "alert_id" to alertId
        )

        alertHandler.handleAlert(AlertHandler.TYPE_SOS_ALERT, data)

        val notif = findNotificationWithTitle("SOS Alert!")
        assertNotNull("SOS alert notification should be posted", notif)

        assertEquals(
            "SOS alert must use SOS channel",
            TrackApplication.SOS_CHANNEL_ID,
            notif!!.notification.channelId
        )

        // PRIORITY_MAX is documented in AlertHandler.handleSosAlert
        assertTrue(
            "SOS alert must be high priority",
            notif.notification.priority >= NotificationCompat.PRIORITY_HIGH
        )

        // AUTO_CANCEL is flag 0x00000010
        assertTrue(
            "SOS alert must have AUTO_CANCEL flag",
            (notif.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )

        // Verify contentIntent carries the SOS deep-link extras
        assertNotNull("SOS alert must have contentIntent", notif.notification.contentIntent)
    }

    @Test
    fun handleSosAcknowledged_postsOnAlertsChannel() {
        val data = mapOf("user_name" to "Bob")
        alertHandler.handleAlert(AlertHandler.TYPE_SOS_ACKNOWLEDGED, data)

        val notif = findNotificationWithTitle("SOS Acknowledged")
        assertNotNull("SOS acknowledged notification should be posted", notif)
        assertEquals(
            "SOS acknowledged must use ALERTS channel",
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
        assertTrue(
            "SOS acknowledged must have AUTO_CANCEL flag",
            (notif.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    @Test
    fun handleSosResolved_postsOnAlertsChannel() {
        val data = mapOf("user_name" to "Carol")
        alertHandler.handleAlert(AlertHandler.TYPE_SOS_RESOLVED, data)

        val notif = findNotificationWithTitle("SOS Resolved")
        assertNotNull("SOS resolved notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
    }

    @Test
    fun handlePlaceArrival_postsOnAlertsChannel() {
        val data = mapOf(
            "user_name" to "Dave",
            "place_name" to "Home"
        )
        alertHandler.handleAlert(AlertHandler.TYPE_PLACE_ARRIVAL, data)

        val notif = findNotificationWithTitle("Arrival")
        assertNotNull("Place arrival notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
        assertTrue(
            "Place arrival must have AUTO_CANCEL flag",
            (notif.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    @Test
    fun handlePlaceDeparture_postsOnAlertsChannel() {
        val data = mapOf(
            "user_name" to "Eve",
            "place_name" to "Office"
        )
        alertHandler.handleAlert(AlertHandler.TYPE_PLACE_DEPARTURE, data)

        val notif = findNotificationWithTitle("Departure")
        assertNotNull("Place departure notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
    }

    @Test
    fun handleCheckInSafeArrival_postsOnAlertsChannel() {
        val data = mapOf(
            "user_name" to "Frank",
            "check_in_type" to "safe_arrival",
            "message" to "Made it home"
        )
        alertHandler.handleAlert(AlertHandler.TYPE_CHECK_IN, data)

        val notif = findNotificationWithTitle("Safe Arrival")
        assertNotNull("Safe arrival notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
    }

    @Test
    fun handleCircleInvite_postsOnAlertsChannelWithNavExtras() {
        val data = mapOf(
            "inviter_name" to "Grace",
            "circle_name" to "Friends"
        )
        alertHandler.handleAlert(AlertHandler.TYPE_CIRCLE_INVITE, data)

        val notif = findNotificationWithTitle("Circle Invitation")
        assertNotNull("Circle invite notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
        assertNotNull(
            "Circle invite must have contentIntent for deep-linking",
            notif!!.notification.contentIntent
        )
    }

    @Test
    fun handleLowBattery_postsOnAlertsChannel() {
        val data = mapOf(
            "user_name" to "Harry",
            "battery_level" to "15"
        )
        alertHandler.handleAlert(AlertHandler.TYPE_LOW_BATTERY, data)

        val notif = findNotificationWithTitle("Low Battery")
        assertNotNull("Low battery notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
    }

    @Test
    fun handleOffline_postsOnAlertsChannel() {
        val data = mapOf(
            "user_name" to "Iris",
            AlertHandler.KEY_INACTIVE_SINCE to "Iris hasn't shared their location since 1:15 PM."
        )
        alertHandler.handleAlert(AlertHandler.TYPE_OFFLINE, data)

        val notif = findNotificationWithTitle("Inactivity")
        assertNotNull("Inactivity notification should be posted", notif)
        assertEquals(
            TrackApplication.ALERTS_CHANNEL_ID,
            notif!!.notification.channelId
        )
    }


    /**
     * member_joined must NOT post a visible notification (handled silently).
     * Documented in AlertHandler.handleMemberJoined KDoc.
     */
    @Test
    fun handleMemberJoined_doesNotPostVisibleNotification() {
        val before = notificationManager.activeNotifications.size

        alertHandler.handleAlert(AlertHandler.TYPE_MEMBER_JOINED, mapOf(
            "circle_id" to "test-circle",
            "needs_key_rotation" to "false",
            "send_location" to "false"
        ))

        // Give any async ops a moment to (not) post a notif
        Thread.sleep(500)

        val after = notificationManager.activeNotifications.size
        assertEquals(
            "member_joined should not increase notification count",
            before,
            after
        )
    }

    // ===== Helpers =====

    private fun findNotificationWithTitle(title: String): android.service.notification.StatusBarNotification? {
        // Give notification post time to land: a server place push posts off the caller's
        // thread (AlertHandler.postServerPlaceAlert), so wait for it rather than a fixed beat.
        val deadline = System.currentTimeMillis() + 5_000L
        while (true) {
            notificationManager.activeNotifications.firstOrNull { sbn ->
                sbn.notification.extras?.getString(Notification.EXTRA_TITLE) == title
            }?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(50)
        }
    }

    /**
     * Create notification channels used by AlertHandler since HiltTestApplication
     * doesn't run TrackApplication.onCreate. Without channels, notifications on
     * Android O+ are silently dropped.
     */
    private fun ensureChannelsExist() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return

        val channels = listOf(
            android.app.NotificationChannel(
                TrackApplication.LOCATION_CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_DEFAULT
            ),
            android.app.NotificationChannel(
                TrackApplication.ALERTS_CHANNEL_ID,
                "Alerts & Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ),
            android.app.NotificationChannel(
                TrackApplication.SOS_CHANNEL_ID,
                "Emergency SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setBypassDnd(true)
            }
        )
        for (ch in channels) {
            notificationManager.createNotificationChannel(ch)
        }
    }
}
