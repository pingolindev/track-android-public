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
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.domain.placealerts.PlaceAlertRules
import com.pingolin.track.domain.repository.LocationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a server-routed place push posts, read back from the shade. It posts at once, under its event id,
 * named when this device can name it — as a pure wake, a crossing the local evaluation couldn't reproduce
 * would never be heard; the wake then drives local evaluation, and the named local copy of the same
 * crossing replaces it in place without sounding again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ServerPlacePushTest {

    private companion object {
        const val WAIT_MS = 2_000L

        /** Nothing follows a wake that stopped: the time a wrongly continuing one gets to show itself. */
        const val SETTLE_MS = 500L
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val evaluator = mockk<PlaceAlertEvaluator>()
    private val locationRepository = mockk<LocationRepository>()
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private lateinit var handler: AlertHandler

    /** Posted notifications, the bundles' summaries left out. */
    private fun shade(): List<Notification> =
        shadowOf(notificationManager).allNotifications.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun awaitShade(count: Int): List<Notification> {
        val until = System.currentTimeMillis() + WAIT_MS
        while (shade().size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        return shade()
    }

    /** Summaries whose group has nothing under it: each would render on its own ("Location Updates / Tap to view"). */
    private fun childlessSummaries(): List<Notification> {
        val all = shadowOf(notificationManager).allNotifications
        val groupsWithChildren = all.filter { it.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.mapNotNull { it.group }.toSet()
        return all.filter { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 && it.group !in groupsWithChildren }
    }

    /** The id a notification posted under [dedupeKey] gets: AlertHandler's own (a hash of the key, high bit off). */
    private fun stableId(dedupeKey: String): Int =
        AlertHandler::class.java.getDeclaredMethod("stableNotificationId", String::class.java)
            .apply { isAccessible = true }.invoke(handler, dedupeKey) as Int

    private fun await(test: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + WAIT_MS
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(20)
        return test()
    }

    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    @Before
    fun setUp() {
        // The channels TrackApplication makes at start (a plain Application here: it loads native libraries).
        for (id in listOf(TrackApplication.ALERTS_CHANNEL_ID, TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID)) {
            notificationManager.createNotificationChannel(NotificationChannel(id, id, NotificationManager.IMPORTANCE_HIGH))
        }
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        coEvery { evaluator.resolveServerPushCopy(any(), any(), any()) } returns null
        coEvery { evaluator.planServerPush(any(), any(), any(), any(), any()) } returns PlaceAlertEvaluator.ServerPushOutcome.POST
        coEvery { evaluator.pushPaired(any()) } returns false
        coEvery { evaluator.watchedCircleIds() } returns listOf("circle-1")
        every { evaluator.lastLocalSurfacedAt() } returns 0L
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.failure(RuntimeException("offline"))
        handler = AlertHandler(
            context,
            mockk(relaxed = true),
            locationRepository,
            sessionManager,
            mockk(relaxed = true),
            dagger.Lazy { evaluator },
            dagger.Lazy { mockk(relaxed = true) },
        ).also { it.placeWakeBackoffMs = longArrayOf(0L) }
    }

    private val pushData = mapOf(
        "place_id" to "p-1",
        "target_user_id" to "u-1",
        "alert_id" to "row-1",
        "title" to "Member arrived at a place",
        "created_at" to "2026-10-25T21:02:48Z",
    )

    @Test
    fun `every backend place type posts at once, on the server's channel, in this app's own words when nothing here can name it`() {
        // Titled by its event type, never the server's row title ("Member arrived at a place", in pushData).
        val expected = mapOf(
            "row-1" to ("Place Alert" to "A member · a place · "),
            "row-2" to ("Arrival" to "A member arrived at a place · "),
            "row-3" to ("Departure" to "A member left a place · "),
        )
        for ((type, id) in listOf(
            AlertHandler.TYPE_PLACE_ALERT to "row-1",
            AlertHandler.TYPE_PLACE_ALERT_ENTER to "row-2",
            AlertHandler.TYPE_PLACE_ALERT_EXIT to "row-3",
        )) {
            handler.handleAlert(type, pushData + ("alert_id" to id))
        }

        val shade = awaitShade(3)
        assertEquals(3, shade.size)
        for ((id, words) in expected) {
            val posted = shadowOf(notificationManager).getNotification(stableId(id))
            assertNotNull(id, posted)
            assertEquals(TrackApplication.ALERTS_CHANNEL_ID, posted.channelId)
            assertEquals(id, words.first, posted.title())
            assertTrue(posted.text(), posted.text().startsWith(words.second))
            assertEquals("the first one sounds", 0, posted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        }
        // And each drives the wake for its named copy.
        coVerify(timeout = WAIT_MS, exactly = 3) { locationRepository.refreshLatestLocations("circle-1") }
    }

    @Test
    fun `named at once when this device can name it`() {
        coEvery { evaluator.resolveServerPushCopy("p-1", "u-1", null) } returns
            PlaceAlertEvaluator.ServerPushCopy("Alice", "Home", PlaceAlertRules.Transition.ENTER)

        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData)

        val posted = awaitShade(1).single()
        assertEquals("Arrival", posted.title())
        assertTrue(posted.text(), posted.text().startsWith("Alice arrived at Home · "))
    }

    @Test
    fun `a named and an unnamed push read as each detail level says`() {
        // Named: the copy this device can make. Unnamed: the local copy's own stand-ins. Generic: no words at all.
        coEvery { evaluator.resolveServerPushCopy("p-named", "u-1", null) } returns
            PlaceAlertEvaluator.ServerPushCopy("Alice", "Home", PlaceAlertRules.Transition.ENTER)
        val cases = listOf(
            Triple(SessionManager.NOTIF_DETAIL_FULL, "Alice arrived at Home · ", "A member arrived at a place · "),
            Triple(SessionManager.NOTIF_DETAIL_NAMES, "Alice arrived at a place · ", "A member arrived at a place · "),
            Triple(SessionManager.NOTIF_DETAIL_GENERIC, AlertHandler.GENERIC_MESSAGE, AlertHandler.GENERIC_MESSAGE),
        )
        for ((level, named, unnamed) in cases) {
            every { sessionManager.getNotificationDetailLevel() } returns level
            handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData + mapOf("place_id" to "p-named", "alert_id" to "n-$level"))
            handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT_ENTER, pushData + mapOf("alert_id" to "u-$level"))
            assertTrue(await { shadowOf(notificationManager).getNotification(stableId("n-$level")) != null && shadowOf(notificationManager).getNotification(stableId("u-$level")) != null })
            for ((id, words) in listOf("n-$level" to named, "u-$level" to unnamed)) {
                val posted = shadowOf(notificationManager).getNotification(stableId(id))
                assertEquals("$level $id", "Arrival", posted.title())
                assertTrue("$level $id: ${posted.text()}", if (words == AlertHandler.GENERIC_MESSAGE) posted.text() == words else posted.text().startsWith(words))
            }
        }
    }

    @Test
    fun `the push is in the shade before the wake asks for anything`() {
        // Read from the answer itself: mockk records a call before it runs the answer, so a verify
        // can return while the answer hasn't run yet. And by THIS push's id, not the shade's size: a post
        // another test's handler makes late (its scope outlives its test) lands in this test's shade too,
        // which could let an implementation that wakes first pass here.
        val atWake = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        coEvery { locationRepository.refreshLatestLocations(any()) } answers {
            atWake.offer(shadowOf(notificationManager).getNotification(stableId("row-order")) != null)
            Result.failure(RuntimeException("offline"))
        }

        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData + ("alert_id" to "row-order"))

        assertEquals("posted when the wake began", true, atWake.poll(WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a push the shade already shows is not posted again, and drives nothing`() {
        // Observable here: on the JVM (AlertHandlerTest) a post never reaches the mocked NotificationManager,
        // so a "not posted" check there would pass whether or not the push was posted.
        coEvery { evaluator.planServerPush(any(), any(), any(), any(), any()) } returns PlaceAlertEvaluator.ServerPushOutcome.ALREADY_SHOWN

        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData + ("alert_id" to "row-shown"))

        coVerify(timeout = WAIT_MS) { evaluator.planServerPush("row-shown", "p-1", "u-1", null, any()) }
        // Nothing follows the plan on this path: give a post that wrongly went ahead the time to show itself.
        Thread.sleep(SETTLE_MS)
        assertEquals(null, shadowOf(notificationManager).getNotification(stableId("row-shown")))
        coVerify(exactly = 0) { locationRepository.refreshLatestLocations(any()) }
    }

    @Test
    fun `the named local copy replaces it in place, on its own channel, without sounding again`() {
        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData)
        // The wake starts once the push, its bundle and the summary pruning are all done.
        coVerify(timeout = WAIT_MS) { locationRepository.refreshLatestLocations("circle-1") }
        val push = shade().single()
        assertTrue("the push came bundled", shadowOf(notificationManager).allNotifications.any { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 })

        // PlaceAlertEvaluator's hand-off for the same crossing: the push's id, and that it replaces it.
        handler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf(
                "user_name" to "Alice", "place_name" to "Home", "place_id" to "p-1",
                "body" to "Alice arrived at Home", "title" to "Arrival", "alert_id" to "row-1",
                "local_evaluated" to "true", AlertHandler.KEY_REPLACES_SHOWN to "true",
                "created_at" to "2026-10-25T21:02:40Z",
            ),
        )

        val posted = shade().single()
        assertEquals("one notification: replaced, not stacked", 1, shade().size)
        assertTrue(posted.text(), posted.text().startsWith("Alice arrived at Home · "))
        assertEquals(TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID, posted.channelId)
        assertTrue("quietly", posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // In the push's own bundle: a move to another one leaves the first header to be
        // pruned, and on a device the prune cancels the push with it, so the copy posts as
        // new and sounds again (AlertHandler.pruneEmptyGroupSummaries). Robolectric's shade
        // can't show that cascade, so the group is pinned here.
        assertEquals("replacing it moves nothing", push.group, posted.group)
        assertEquals(emptyList<Notification>(), childlessSummaries())
    }

    @Test
    fun `a local copy that replaces nothing sounds as ever`() {
        handler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf(
                "user_name" to "Alice", "place_name" to "Home", "place_id" to "p-1", "body" to "Alice arrived at Home",
                "alert_id" to "local-place:1", "local_evaluated" to "true",
            ),
        )
        assertEquals(0, shade().single().flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun `a blank name or place reads as "A member" and "a place", never " arrived at a place"`() {
        // The stand-ins cover blank as well as null: an empty user_name would otherwise read
        // " arrived at a place".
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_NAMES
        handler.handleAlert(
            AlertHandler.TYPE_PLACE_ARRIVAL,
            mapOf("user_name" to " ", "place_name" to "", "place_id" to "p-1", "alert_id" to "local-place:2", "local_evaluated" to "true"),
        )
        assertTrue(shade().single().text(), shade().single().text().startsWith("A member arrived at a place · "))

        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        handler.handleAlert(
            AlertHandler.TYPE_PLACE_DEPARTURE,
            mapOf("user_name" to "", "place_name" to " ", "place_id" to "p-1", "alert_id" to "local-place:3", "local_evaluated" to "true"),
        )
        assertTrue(shade().any { it.text().startsWith("A member left a place · ") })
    }

    @Test
    fun `a blank name on a check-in, a low battery or a member back online reads as "A member"`() {
        handler.handleAlert(AlertHandler.TYPE_CHECK_IN, mapOf("user_name" to " ", "place_name" to " ", "alert_id" to "c-1"))
        handler.handleAlert(AlertHandler.TYPE_LOW_BATTERY, mapOf("user_name" to "", "battery_level" to "9", "alert_id" to "b-1"))
        handler.handleAlert(AlertHandler.TYPE_BACK_ONLINE, mapOf("user_name" to "  ", "alert_id" to "o-1"))

        val texts = shade().map { it.text() }.toSet()
        assertTrue(texts.toString(), "A member checked in" in texts)
        assertTrue(texts.toString(), "A member's phone is at 9%" in texts)
        assertTrue(texts.toString(), "A member is sharing location again" in texts)
    }

    @Test
    fun `the wake refreshes every watched circle, a failing one included`() {
        // That refresh is what runs subscriber-side evaluation (LocationRepository.persistAndEvaluate).
        coEvery { evaluator.watchedCircleIds() } returns listOf("circle-1", "circle-2")

        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData)

        coVerify(timeout = WAIT_MS) { locationRepository.refreshLatestLocations("circle-1") }
        coVerify(timeout = WAIT_MS) { locationRepository.refreshLatestLocations("circle-2") }
    }

    @Test
    fun `the wake stops once its own push is named, whatever else surfaces meanwhile`() {
        // Someone else's alert firing during the refresh moves the global watermark: an identified
        // push asks only whether ITS copy replaced it.
        handler.placeWakeBackoffMs = longArrayOf(0L, 0L, 0L)
        every { evaluator.lastLocalSurfacedAt() } returnsMany listOf(0L, 999L, 999L, 999L, 999L, 999L)
        coEvery { evaluator.pushPaired("row-1") } returnsMany listOf(false, true)

        handler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, pushData)

        coVerify(timeout = WAIT_MS, exactly = 2) { evaluator.pushPaired("row-1") }
        Thread.sleep(SETTLE_MS)
        coVerify(exactly = 2) { locationRepository.refreshLatestLocations("circle-1") }
        coVerify(exactly = 2) { evaluator.pushPaired("row-1") }
    }
}
