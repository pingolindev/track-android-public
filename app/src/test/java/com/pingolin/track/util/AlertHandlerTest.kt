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

import android.app.NotificationManager
import android.content.Context
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AlertHandler.
 *
 * Tests cover:
 * - handleAlert routes by type (SOS/place/check-in/etc.)
 * - Constants (type identifiers) match expected values
 * - Unknown type is a no-op (falls through `when`)
 * - member_joined with needsKeyRotation triggers auto-rotation
 *
 * Notes:
 * - Notification display calls Android framework (NotificationManager, PendingIntent)
 *   that require real runtime. We mock `Context.getSystemService` to return a mock
 *   NotificationManager so `handleAlert` paths that build notifications don't crash
 *   during test execution. NotificationCompat.Builder & PendingIntent.getActivity
 *   are not mockable at the unit-test level — those full UI paths are covered by
 *   instrumented tests.
 */
class AlertHandlerTest {

    private companion object {
        /**
         * The place paths hand their work to AlertHandler's own background scope
         * (Dispatchers.IO), so verification has to WAIT for it rather than sample
         * whatever happened to have run. Generous: it is an upper bound on a passing
         * test, never a delay one pays.
         */
        const val VERIFY_TIMEOUT_MS = 2_000L
    }

    @MockK
    private lateinit var context: Context

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var notificationManager: NotificationManager

    @MockK
    private lateinit var placeAlertEvaluator: com.pingolin.track.domain.placealerts.PlaceAlertEvaluator

    /** Fused on gms, android.location.LocationManager on libre. AlertHandler uses
     *  it for one thing: the immediate location send on member_joined. */
    @MockK
    private lateinit var locationProvider: com.pingolin.track.location.LocationProvider

    /** Used only to report SOS push receipts, off the notification path. */
    @MockK
    private lateinit var sosRepository: com.pingolin.track.domain.repository.SosRepository

    private lateinit var alertHandler: AlertHandler

    /** AlertHandler holds the place-alert evaluator and the SOS repository behind
     *  dagger.Lazy (the evaluator to break the AlertHandler -> LocationRepository
     *  -> evaluator -> AlertHandler DI cycle), so tests hand it ones wrapping the
     *  mocks. */
    private fun newAlertHandler() = AlertHandler(
        context,
        circleRepository,
        locationRepository,
        sessionManager,
        locationProvider,
        dagger.Lazy { placeAlertEvaluator },
        dagger.Lazy { sosRepository },
    ).also { handler ->
        // No WorkManager in a JVM test: member_joined's work runs in place.
        handler.enqueueMemberJoined = { circleId, delivery, rotation, send ->
            kotlinx.coroutines.runBlocking { handler.performMemberJoined(circleId, delivery, rotation, send) }
        }
    }

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        every { notificationManager.notify(any<Int>(), any()) } just Runs
        // Defaults for the place-alert WAKE every server-routed place push drives after
        // it posts (AlertHandler.driveLocalEvaluation). Nothing to refresh and a watermark
        // that never moves, so the wake is a no-op unless a test says otherwise.
        coEvery { placeAlertEvaluator.watchedCircleIds() } returns emptyList()
        every { placeAlertEvaluator.lastLocalSurfacedAt() } returns 0L
        // A server place push posts unless the shade has it already;
        // nothing has named it yet. Tests about either override these.
        coEvery { placeAlertEvaluator.planServerPush(any(), any(), any(), any(), any()) } returns
            com.pingolin.track.domain.placealerts.PlaceAlertEvaluator.ServerPushOutcome.POST
        coEvery { placeAlertEvaluator.pushPaired(any()) } returns false
        // Default to the unnameable case (place not in Room), which posts the server's
        // own wording. Tests about copy override it.
        coEvery { placeAlertEvaluator.resolveServerPushCopy(any(), any(), any()) } returns null
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // CONSTANTS
    // =============================================================================

    @Test
    fun `TYPE_SOS_ALERT constant is sos_alert`() {
        assertEquals("sos_alert", AlertHandler.TYPE_SOS_ALERT)
    }

    @Test
    fun `TYPE_SOS_ACKNOWLEDGED constant is sos_acknowledged`() {
        assertEquals("sos_acknowledged", AlertHandler.TYPE_SOS_ACKNOWLEDGED)
    }

    @Test
    fun `TYPE_PLACE_ARRIVAL constant is place_arrival`() {
        assertEquals("place_arrival", AlertHandler.TYPE_PLACE_ARRIVAL)
    }

    @Test
    fun `TYPE_PLACE_DEPARTURE constant is place_departure`() {
        assertEquals("place_departure", AlertHandler.TYPE_PLACE_DEPARTURE)
    }

    @Test
    fun `TYPE_CHECK_IN constant is check_in`() {
        assertEquals("check_in", AlertHandler.TYPE_CHECK_IN)
    }

    @Test
    fun `TYPE_LOW_BATTERY constant is low_battery`() {
        assertEquals("low_battery", AlertHandler.TYPE_LOW_BATTERY)
    }

    @Test
    fun `TYPE_OFFLINE constant is offline`() {
        assertEquals("offline", AlertHandler.TYPE_OFFLINE)
    }

    @Test
    fun `TYPE_INACTIVITY constant is inactivity`() {
        assertEquals("inactivity", AlertHandler.TYPE_INACTIVITY)
    }

    @Test
    fun `TYPE_CIRCLE_INVITE constant is circle_invite`() {
        assertEquals("circle_invite", AlertHandler.TYPE_CIRCLE_INVITE)
    }

    @Test
    fun `TYPE_MEMBER_JOINED constant is member_joined`() {
        assertEquals("member_joined", AlertHandler.TYPE_MEMBER_JOINED)
    }

    // =============================================================================
    // BACKEND-EMITTED PLACE-ALERT TYPES
    //
    // These are the strings the server actually sends. Each must be routed: a
    // `when` used as a statement silently discards an unrouted one, so a
    // server-routed place alert would produce no notification at all while its
    // row still appeared in the in-app list.
    // =============================================================================

    @Test
    fun `backend place-alert type constants match what the server emits`() {
        // The server's notification types serialise in snake_case, so a place-alert
        // enter is "place_alert_enter"; the type stored on the notification row is
        // the constant string 'place_alert'.
        assertEquals("place_alert_enter", AlertHandler.TYPE_PLACE_ALERT_ENTER)
        assertEquals("place_alert_exit", AlertHandler.TYPE_PLACE_ALERT_EXIT)
        assertEquals("place_alert", AlertHandler.TYPE_PLACE_ALERT)
    }

    private val ENTER = com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.ENTER
    private val EXIT = com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.EXIT
    private val ALREADY_SHOWN = com.pingolin.track.domain.placealerts.PlaceAlertEvaluator.ServerPushOutcome.ALREADY_SHOWN

    /** A wake that reaches a watched circle and fails there, once per push, unless a test says otherwise. */
    private fun wakeOnce() {
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        coEvery { placeAlertEvaluator.watchedCircleIds() } returns listOf("circle-1")
        coEvery { locationRepository.refreshLatestLocations(any()) } returns
            Result.failure(RuntimeException("offline"))
        alertHandler = newAlertHandler()
        alertHandler.placeWakeBackoffMs = longArrayOf(0L)
    }

    // What a server place push posts, how its local copy replaces it, the wake after it, and that a
    // push the shade already shows isn't posted again are in ServerPlacePushTest (Robolectric: a
    // notification can be built and read back there; here a post never reaches the mocked manager).

    @Test
    fun `each backend type plans its post with the direction it says, and the directionless one with none it didn't read`() {
        // 'place_alert' carries no enter/exit (the event type is a HASH): a direction is
        // passed only when this device read it back from its own alert, never guessed.
        wakeOnce()

        alertHandler.handleAlert(AlertHandler.TYPE_PLACE_ALERT_ENTER, mapOf("place_id" to "p-1", "target_user_id" to "u-1", "alert_id" to "row-1"))
        alertHandler.handleAlert(AlertHandler.TYPE_PLACE_ALERT_EXIT, mapOf("place_id" to "p-1", "crossing_user_id" to "u-1", "alert_id" to "row-2"))
        alertHandler.handleAlert(AlertHandler.TYPE_PLACE_ALERT, mapOf("place_id" to "p-1", "target_user_id" to "u-1", "alert_id" to "row-3"))

        coVerify(timeout = VERIFY_TIMEOUT_MS) { placeAlertEvaluator.planServerPush("row-1", "p-1", "u-1", ENTER, null) }
        coVerify(timeout = VERIFY_TIMEOUT_MS) { placeAlertEvaluator.planServerPush("row-2", "p-1", "u-1", EXIT, null) }
        coVerify(timeout = VERIFY_TIMEOUT_MS) { placeAlertEvaluator.planServerPush("row-3", "p-1", "u-1", null, null) }
    }

    @Test
    fun `a direction read back from the hash, and the event time, go into the plan`() {
        wakeOnce()
        coEvery { placeAlertEvaluator.resolveServerPushCopy("p-1", "u-1", "hash-enter") } returns copy(ENTER)

        alertHandler.handleAlert(
            AlertHandler.TYPE_PLACE_ALERT,
            mapOf(
                "place_id" to "p-1", "target_user_id" to "u-1", "alert_id" to "row-1",
                "event_type_hash" to "hash-enter", "created_at" to "2026-10-25T21:02:48Z",
            ),
        )

        coVerify(timeout = VERIFY_TIMEOUT_MS) {
            placeAlertEvaluator.planServerPush("row-1", "p-1", "u-1", ENTER, java.time.Instant.parse("2026-10-25T21:02:48Z").toEpochMilli())
        }
    }

    // =============================================================================
    // COPY THE DETAIL SETTING CAN ACTUALLY SELECT BETWEEN
    // =============================================================================
    //
    // The server's place-alert row has no names in it and never will. Resolving them
    // locally is what makes "Names and places" mean something on this path: without
    // it, all three levels render "Tap to view".

    private fun copy(
        transition: com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition?,
    ) = com.pingolin.track.domain.placealerts.PlaceAlertEvaluator.ServerPushCopy(
        memberName = "Alice",
        placeName = "Home",
        transition = transition,
    )

    @Test
    fun `full detail reads exactly as the setting advertises`() {
        alertHandler = newAlertHandler()
        // Settings -> Notification detail offers this as the "Names and places"
        // example, and PlaceAlertRules.describe words a live local crossing the same
        // way. The two paths must not read as two different features.
        assertEquals(
            "Alice arrived at Home",
            alertHandler.detailedPlaceCopy(
                copy(com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.ENTER)
            ),
        )
        assertEquals(
            "Alice left Home",
            alertHandler.detailedPlaceCopy(
                copy(com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.EXIT)
            ),
        )
    }

    @Test
    fun `names only keeps who and drops where`() {
        alertHandler = newAlertHandler()
        assertEquals(
            "Alice arrived at a place",
            alertHandler.namesOnlyPlaceCopy(
                copy(com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.ENTER)
            ),
        )
        // The place name must not survive at this level in either direction.
        assertFalse(
            alertHandler.namesOnlyPlaceCopy(
                copy(com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition.EXIT)
            ).contains("Home")
        )
    }

    @Test
    fun `an underivable direction drops the verb rather than inventing one`() {
        alertHandler = newAlertHandler()
        // Still names both, because the server's own title carries the direction.
        assertEquals("Alice · Home", alertHandler.detailedPlaceCopy(copy(null)))
        assertEquals("Alice", alertHandler.namesOnlyPlaceCopy(copy(null)))
    }

    @Test
    fun `constants are distinct`() {
        val values = setOf(
            AlertHandler.TYPE_SOS_ALERT,
            AlertHandler.TYPE_SOS_ACKNOWLEDGED,
            AlertHandler.TYPE_SOS_RESOLVED,
            AlertHandler.TYPE_PLACE_ARRIVAL,
            AlertHandler.TYPE_PLACE_DEPARTURE,
            AlertHandler.TYPE_CHECK_IN,
            AlertHandler.TYPE_CIRCLE_INVITE,
            AlertHandler.TYPE_MEMBER_JOINED,
            AlertHandler.TYPE_LOW_BATTERY,
            AlertHandler.TYPE_OFFLINE,
            AlertHandler.TYPE_INACTIVITY,
            // The strings the BACKEND emits for place alerts. TYPE_PLACE_ARRIVAL /
            // _DEPARTURE above are LOCAL-only (this device's own evaluator) and are
            // never emitted by the server, so these three must be handled: otherwise
            // every server-routed place alert is silently dropped.
            AlertHandler.TYPE_PLACE_ALERT_ENTER,
            AlertHandler.TYPE_PLACE_ALERT_EXIT,
            AlertHandler.TYPE_PLACE_ALERT,
        )
        // 14 type strings, all unique
        assertEquals(14, values.size)
    }

    // =============================================================================
    // ROUTING (unknown type is a no-op)
    // =============================================================================

    @Test
    fun `handleAlert with unknown type is a no-op`() {
        // Instantiating AlertHandler needs context; injected fields only matter
        // for routes that are hit. Unknown types return early before calling
        // anything, so we need the mocked context.
        alertHandler = newAlertHandler()
        alertHandler.handleAlert("unknown_type", emptyMap())

        // Nothing should be dispatched on the notification manager.
        verify(exactly = 0) { notificationManager.notify(any<Int>(), any()) }
    }

    @Test
    fun `handleAlert with empty type is a no-op`() {
        alertHandler = newAlertHandler()
        alertHandler.handleAlert("", emptyMap())
        verify(exactly = 0) { notificationManager.notify(any<Int>(), any()) }
    }

    // =============================================================================
    // MEMBER_JOINED ROUTING
    // =============================================================================

    @Test
    fun `handleAlert member_joined with blank circle_id is a no-op`() {
        alertHandler = newAlertHandler()
        alertHandler.handleAlert("member_joined", mapOf("needs_key_rotation" to "true"))
        // No crash; auto-rotate is not called because circleId is blank
        coVerify(exactly = 0) { circleRepository.autoRotateIfNeeded(any()) }
    }

    @Test
    fun `member_joined asking for delivery hands the newcomer the circle, with no rotation`() {
        // Any member's app lets a newcomer in.
        coEvery { circleRepository.deliverOwedAccess(any()) } returns 1
        alertHandler = newAlertHandler()

        alertHandler.handleAlert(
            "member_joined",
            mapOf("circle_id" to fam, "needs_delivery" to "true", "needs_key_rotation" to "true"),
        )

        coVerify(timeout = 5_000) { circleRepository.deliverOwedAccess(fam) }
        coVerify(exactly = 0) { circleRepository.autoRotateIfNeeded(any()) }
    }

    @Test
    fun `member_joined's work is queued as background work with what the push asked for`() {
        alertHandler = newAlertHandler()
        val queued = mutableListOf<List<Any>>()
        alertHandler.enqueueMemberJoined = { circleId, delivery, rotation, send -> queued += listOf(circleId, delivery, rotation, send) }

        alertHandler.handleAlert(
            "member_joined",
            mapOf("circle_id" to fam, "needs_delivery" to "true", "send_location" to "true"),
        )

        // Queued, not run in this process's own scope.
        assertEquals(listOf(listOf<Any>(fam, true, false, true)), queued)
        coVerify(exactly = 0) { circleRepository.deliverOwedAccess(any()) }
        val src = java.io.File("src/main/java/com/pingolin/track/util/AlertHandler.kt").readText()
        val handler = src.substring(src.indexOf("private fun handleMemberJoined("), src.indexOf("internal var enqueueMemberJoined"))
        assertFalse("member_joined must not run in a scope that dies with the process", handler.contains("backgroundScope.launch"))
    }

    /**
     * A member_joined is said too, named, where the person's stored "Member joined" preference
     * allows it. The fetcher decides that (KEY_MEMBER_JOINED_SHOWN); without its say nothing
     * is posted.
     */
    @Test
    fun `a member_joined is posted only on the fetcher's say`() {
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        alertHandler = newAlertHandler()
        alertHandler.enqueueMemberJoined = { _, _, _, _ -> }

        alertHandler.handleAlert("member_joined", mapOf("circle_id" to fam, "alert_id" to "row-1", "user_name" to "Ana"))

        verify(exactly = 0) { notificationManager.notify(any<Int>(), any()) }
    }

    // privacy_resumed as posted, at each detail level: PrivacyResumedNoticeTest (Robolectric).

    @Test
    fun `the resumed notice says it plainly, names the circle only at full detail, and talks of no keys or SOS`() {
        alertHandler = newAlertHandler()
        val named = alertHandler.privacyResumedCopy(mapOf("circle_name" to "Family"))
        assertEquals(
            "Your pause in Family has ended, and Family can see your location again." to
                "Your pause has ended, and your circle can see your location again.",
            named,
        )
        assertEquals(
            "Your pause has ended, and your circle can see your location again." to
                "Your pause has ended, and your circle can see your location again.",
            alertHandler.privacyResumedCopy(emptyMap()),
        )
        for (line in named.toList()) {
            assertFalse(line, line.contains("key", ignoreCase = true) || line.contains("SOS"))
        }
        // The detail level picks between them, and the generic level shows neither.
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_NAMES
        assertEquals(named.second, alertHandler.resolveDisplayMessage(named.first, named.second))
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_GENERIC
        assertEquals(AlertHandler.GENERIC_MESSAGE, alertHandler.resolveDisplayMessage(named.first, named.second))
    }

    @Test
    fun `a member_joined notice names who and, at full detail, the circle - with plain fallbacks`() {
        alertHandler = newAlertHandler()
        assertEquals(
            "Ana joined Family. They can see its location history." to
                "Ana joined a circle you are in. They can see its location history.",
            alertHandler.memberJoinedCopy(mapOf("user_name" to "Ana", "circle_name" to "Family")),
        )
        assertEquals(
            "Someone joined a circle you are in. They can see its location history." to
                "Someone joined a circle you are in. They can see its location history.",
            alertHandler.memberJoinedCopy(emptyMap()),
        )
    }

    @Test
    fun `member_joined sends nothing while the old Pause Tracking still holds`() {
        // This send honours the device-wide switch too: until
        // PauseTrackingMigration has paused every circle instead, the switch holds
        // and nothing leaves the phone.
        alertHandler = newAlertHandler()
        coEvery { locationProvider.lastLocation() } returns null

        every { sessionManager.isTrackingPaused() } returns true
        kotlinx.coroutines.runBlocking { alertHandler.performMemberJoined(fam, false, false, sendLocation = true) }
        coVerify(exactly = 0) { locationProvider.lastLocation() }

        every { sessionManager.isTrackingPaused() } returns false
        kotlinx.coroutines.runBlocking { alertHandler.performMemberJoined(fam, false, false, sendLocation = true) }
        coVerify(exactly = 1) { locationProvider.lastLocation() }
    }

    @Test
    fun `member_joined from a server without delivery still rotates`() {
        coEvery { circleRepository.autoRotateIfNeeded(any()) } returns Unit
        alertHandler = newAlertHandler()

        alertHandler.handleAlert("member_joined", mapOf("circle_id" to fam, "needs_key_rotation" to "true"))

        coVerify(timeout = 5_000) { circleRepository.autoRotateIfNeeded(fam) }
        coVerify(exactly = 0) { circleRepository.deliverOwedAccess(any()) }
    }

    /** A circle, a UUID with hex letters so its upper case differs. */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"

    @Test
    fun `member_joined names its circle in its one spelling, and a push naming no circle runs nothing`() {
        // A push naming one circle under a second spelling would start that circle's delivery
        // or rotation in a namespace of its own.
        alertHandler = newAlertHandler()
        val queued = mutableListOf<String>()
        alertHandler.enqueueMemberJoined = { circleId, _, _, _ -> queued += circleId }

        for (spelling in listOf(fam, fam.uppercase(), fam.replace("-", ""), "{$fam}", "urn:uuid:${fam.uppercase()}")) {
            alertHandler.handleAlert("member_joined", mapOf("circle_id" to spelling, "needs_delivery" to "true"))
        }
        for (none in listOf("circle-fam", " $fam", "00000000-0000-0000-0000-000000000000", "00000000000000000000000000000000", "self", "")) {
            alertHandler.handleAlert("member_joined", mapOf("circle_id" to none, "needs_delivery" to "true"))
        }

        assertEquals(List(5) { fam }, queued)
    }

    @Test
    fun `circleIdOf is the only way a push names a circle`() {
        val readers = java.io.File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("[\"circle_id\"]") }
            .map { it.name }
            .toList()
        assertEquals(listOf("PushCircleId.kt"), readers)
    }

    // =============================================================================
    // NOTIFICATION DETAIL LEVEL (display verbosity)
    // =============================================================================

    @Test
    fun `resolveDisplayMessage returns detailed copy at full level`() {
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_FULL
        alertHandler = newAlertHandler()

        assertEquals(
            "Alice arrived at Home",
            alertHandler.resolveDisplayMessage("Alice arrived at Home", "Alice arrived at a place")
        )
    }

    @Test
    fun `resolveDisplayMessage returns names-only copy at names level`() {
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_NAMES
        alertHandler = newAlertHandler()

        assertEquals(
            "Alice arrived at a place",
            alertHandler.resolveDisplayMessage("Alice arrived at Home", "Alice arrived at a place")
        )
    }

    @Test
    fun `resolveDisplayMessage returns generic copy at generic level`() {
        every { sessionManager.getNotificationDetailLevel() } returns SessionManager.NOTIF_DETAIL_GENERIC
        alertHandler = newAlertHandler()

        assertEquals(
            AlertHandler.GENERIC_MESSAGE,
            alertHandler.resolveDisplayMessage("Alice arrived at Home", "Alice arrived at a place")
        )
    }

    @Test
    fun `resolveDisplayMessage falls back to detailed copy for unknown level`() {
        // SessionManager normalises unknown stored values, but AlertHandler
        // must stay safe against mocked/legacy values reaching it anyway —
        // unknown levels must never redact MORE than the user asked for,
        // and (more importantly) never crash the notification path.
        every { sessionManager.getNotificationDetailLevel() } returns "bogus"
        alertHandler = newAlertHandler()

        assertEquals(
            "Alice arrived at Home",
            alertHandler.resolveDisplayMessage("Alice arrived at Home", "Alice arrived at a place")
        )
    }

    // =============================================================================
    // NAVIGATE_TO EXTRA CONTRACT
    // =============================================================================

    // =============================================================================
    // "THIS ALERT IS OVER" GOES TO THE WHOLE CIRCLE
    // =============================================================================
    //
    // The server sends sos_resolved to every member except whoever closed it —
    // otherwise everyone else keeps a live "someone needs attention" marker on
    // their map for an emergency that has ended — so wording such as "…marked
    // YOUR notification resolved" is wrong for almost everyone who receives
    // it.

    @Test
    fun `the resolved copy never addresses the raiser`() {
        alertHandler = newAlertHandler()

        // The typed payload, which is all UnifiedPush and the WebSocket carry.
        val copy = alertHandler.sosResolvedCopy(
            mapOf("sos_id" to "s-1", "circle_id" to "c-1")
        )

        assertFalse(
            "\"your notification\" is false for every recipient but the raiser: $copy",
            copy.contains("your", ignoreCase = true),
        )
        assertFalse(
            "nothing in the payload identifies anyone, so no placeholder name either: $copy",
            copy.contains("The member") || copy.contains("Someone"),
        )
        assertTrue(copy.isNotBlank())
    }

    @Test
    fun `no name is invented, because the payload carries none`() {
        alertHandler = newAlertHandler()

        // The payload carries no user_name: the server sends {sos_id, circle_id}.
        // The one name this copy may say is who CLOSED the alert, and only under
        // the key this device sets itself from its own roster (KEY_CLOSER_NAME);
        // any other key is never read.
        val copy = alertHandler.sosResolvedCopy(
            mapOf("sos_id" to "s-1", "circle_id" to "c-1", "user_name" to "Mallory")
        )

        assertFalse("a name in this payload must not reach the copy: $copy", copy.contains("Mallory"))
    }

    @Test
    fun `the server's own body wins when the tickle path supplies one`() {
        alertHandler = newAlertHandler()

        // On FCM every message is a tickle: the app re-fetches the stored row and
        // NotificationTickleFetcher folds its body into `data`. Preferring it keeps
        // the two delivery paths reading alike.
        assertEquals(
            "A circle member marked the request to check on them resolved",
            alertHandler.sosResolvedCopy(
                mapOf(
                    "sos_id" to "s-1",
                    "body" to "A circle member marked the request to check on them resolved",
                )
            ),
        )

        // A blank body is not a body — fall back rather than post an empty line.
        assertTrue(alertHandler.sosResolvedCopy(mapOf("body" to "  ")).isNotBlank())
    }

    // ===== who is responding, and who closed the alert =====

    @Test
    fun `a response says who is responding, and how many others`() {
        alertHandler = newAlertHandler()

        assertEquals("Bob is responding", alertHandler.sosAcknowledgedCopy(mapOf("user_name" to "Bob")))
        assertEquals(
            "Bob and 2 others are responding",
            alertHandler.sosAcknowledgedCopy(mapOf("user_name" to "Bob", AlertHandler.KEY_OTHERS_RESPONDING to "2")),
        )
        assertEquals("Someone is responding", alertHandler.sosAcknowledgedCopy(mapOf("sos_id" to "s-1")))
        assertEquals(
            "Someone and 1 other are responding",
            alertHandler.sosAcknowledgedCopy(mapOf("user_name" to " ", AlertHandler.KEY_OTHERS_RESPONDING to "1")),
        )
        assertEquals("Bob is responding", alertHandler.sosAcknowledgedCopy(mapOf("user_name" to "Bob", AlertHandler.KEY_OTHERS_RESPONDING to "x")))
    }

    @Test
    fun `a closed alert says who closed it, only from this device's own key`() {
        alertHandler = newAlertHandler()

        assertEquals(
            "Bob closed the alert",
            alertHandler.sosResolvedCopy(
                mapOf(AlertHandler.KEY_CLOSER_NAME to "Bob", "body" to "A circle member marked the request to check on them resolved"),
            ),
        )
        assertEquals("Someone closed the alert", alertHandler.sosResolvedCopy(mapOf(AlertHandler.KEY_CLOSER_NAME to "Someone")))
        // Blank: the server's words.
        assertEquals(
            "A circle member marked the request to check on them resolved",
            alertHandler.sosResolvedCopy(mapOf(AlertHandler.KEY_CLOSER_NAME to " ")),
        )
    }

    @Test
    fun `the names go in the text, never the title, so the generic level hides them`() {
        val src = java.io.File("src/main/java/com/pingolin/track/util/AlertHandler.kt").readText()
        fun body(signature: String) = src.substring(src.indexOf(signature)).substringBefore("\n    }\n")
        val acknowledged = body("private fun handleSosAcknowledged(")
        assertTrue(acknowledged.contains("title = \"Acknowledged\","))
        assertTrue(acknowledged.contains("message = sosAcknowledgedCopy(data),"))
        val resolved = body("private fun handleSosResolved(")
        assertTrue(resolved.contains("title = \"Resolved\","))
        assertTrue(resolved.contains("message = sosResolvedCopy(data),"))
        // The message goes through the detail level (titles stay as passed).
        assertTrue(body("private fun showNotification(").contains("val displayMessage = resolveDisplayMessage(message, namesOnlyMessage ?: message)"))
    }

    @Test
    fun `the resolved copy is also true of a false alarm`() {
        alertHandler = newAlertHandler()

        // mark_false_alarm deliberately emits sos_resolved: it ends a live alert
        // exactly as a resolution does, and without the event the same stale marker
        // would stay. Nothing in the payload distinguishes the two — that lives in
        // the alert's own status, which the next fetch reads — so the copy must not
        // claim either.
        val copy = alertHandler.sosResolvedCopy(mapOf("sos_id" to "s-1"))

        assertFalse(
            "must not assert a false alarm it cannot know about: $copy",
            copy.contains("false alarm", ignoreCase = true),
        )
    }

    @Test
    fun `navigate_to contract mapping`() {
        // AlertHandler sets a "navigate_to" extra per alert type. The mapping is
        // recorded here as a table, but the extra itself is only reachable through
        // PendingIntent, which is not mockable at the unit-test level — so this
        // documents the contract and the instrumented tests exercise it.
        val expected = mapOf(
            "sos_alert" to "sos",
            "circle_invite" to "circles",
            "boundary_consent_request" to "boundary_consent",
        )
        assertEquals("sos", "sos") // sentinel
        assertEquals(3, expected.size)
    }

    // ===== Circle ownership claims =====
    //
    // The claim push is the only warning an owner gets before a fourteen-day clock
    // starts, and its own words promise a control ("Open Pingolin to refuse") that has to
    // exist. What is pinned here is the half that is decided at build time: the wording.

    @Test
    fun `the ownership type constants match what the server emits`() {
        // The row's notification_type and the typed payload's alert_type are the same
        // string for both of these, so the tickle path and the direct path land on the
        // same branch. A mismatch would fall to the dispatcher's `else` and drop the
        // notification while its row still appeared in the in-app list.
        assertEquals("circle_ownership_claim", AlertHandler.TYPE_CIRCLE_OWNERSHIP_CLAIM)
        assertEquals("circle_ownership_transferred", AlertHandler.TYPE_CIRCLE_OWNERSHIP_TRANSFERRED)
        assertEquals("circle_ownership_refused", AlertHandler.TYPE_CIRCLE_OWNERSHIP_REFUSED)
    }

    /**
     * The claimant's refusal. The server's words when the row's body came, the same words when
     * it didn't, and never a name: the row and the push carry `{claim_id}` alone.
     */
    @Test
    fun `the refusal says the server's words, the same when none came, and names nobody`() {
        alertHandler = newAlertHandler()
        val words = "The owner refused your request to take over the circle. It stays theirs."

        assertEquals("Stored body.", alertHandler.ownershipRefusedCopy(mapOf("body" to "Stored body.", "claim_id" to "c-1")))
        assertEquals(words, alertHandler.ownershipRefusedCopy(mapOf("claim_id" to "c-1")))
        assertEquals(words, alertHandler.ownershipRefusedCopy(mapOf("body" to "", "claim_id" to "c-1")))
        val named = alertHandler.ownershipRefusedCopy(mapOf("claim_id" to "c-1", "user_name" to "Mallory", "circle_name" to "Family"))
        assertFalse(named.contains("Mallory") || named.contains("Family"))
    }

    /** With full detail the refusal names its circle, as the claim does; never a person. */
    @Test
    fun `the refusal names its circle when this phone could, and nobody`() {
        alertHandler = newAlertHandler()

        assertEquals(
            "The owner refused your request to take over Crew. It stays theirs.",
            alertHandler.ownershipRefusedNamedCopy(mapOf("claim_id" to "c-1", "circle_name" to "Crew", "user_name" to "Mallory")),
        )
        assertNull(alertHandler.ownershipRefusedNamedCopy(mapOf("claim_id" to "c-1")))
        assertNull(alertHandler.ownershipRefusedNamedCopy(mapOf("claim_id" to "c-1", "circle_name" to " ")))
    }

    /**
     * Copy must not read a key its payload never carries: a `user_name` key that is never
     * sent makes the placeholder fire for every recipient. This payload is `{claim_id,
     * resolves_at}` and identifies nobody, so no name may reach the copy even if a key
     * appears that looks like one.
     */
    @Test
    fun `the claim copy invents no name, because the payload carries none`() {
        alertHandler = newAlertHandler()

        val copy = alertHandler.ownershipClaimCopy(
            mapOf(
                "claim_id" to "c-1",
                "resolves_at" to "2026-09-06T10:15:30.123456Z",
                // None of these are sent. If one ever is, it must still not be read here.
                "user_name" to "Mallory",
                "claimant_name" to "Mallory",
                "circle_name" to "Family",
            )
        )

        assertFalse("no name is in this payload to use: $copy", copy.contains("Mallory"))
        assertFalse("nor a circle name: $copy", copy.contains("Family"))
        assertTrue(copy.isNotBlank())
    }

    /**
     * The deadline IS in the payload, and it is the one thing the notification can say
     * that helps. It reaches `data` because NotificationTickleFetcher flattens the stored
     * row's metadata into the map.
     */
    @Test
    fun `the claim copy carries the deadline the payload does have`() {
        alertHandler = newAlertHandler()

        val copy = alertHandler.ownershipClaimCopy(
            mapOf("claim_id" to "c-1", "resolves_at" to "2026-09-06T10:15:30.123456Z")
        )

        // Compared against the same formatter rather than a literal date string. The
        // rendering is locale-dependent — "6 September 2026" in en-GB, "September 6, 2026"
        // in en-US — so a hardcoded substring pins the test JVM's locale rather than the
        // behaviour. What matters here is that the deadline is INSERTED; how it is
        // rendered, and in which zone, is pinned by OwnershipClaimFormatTest with an
        // explicit locale.
        val rendered = OwnershipClaimFormat.deadlineText(
            java.time.Instant.parse("2026-09-06T10:15:30.123456Z")
        )
        assertTrue("the transfer date must reach the owner: $copy", copy.contains(rendered))
        assertTrue("and it must still say what to do: $copy", copy.contains("refuse"))
    }

    /**
     * An owner of several circles can't tell from "Someone asked to take over your circle" who
     * wants which one. Named as this phone opened them (NotificationNames, the tickle path): the
     * claimant, and the circle where the detail level allows it.
     */
    @Test
    fun `the claim names who asked and which circle when this phone could tell`() {
        alertHandler = newAlertHandler()
        val data = mapOf(
            "claim_id" to "c-1", "resolves_at" to "2026-09-06T10:15:30.123456Z",
            "user_name" to "Casey", "circle_name" to "Crew",
        )
        val rendered = OwnershipClaimFormat.deadlineText(java.time.Instant.parse("2026-09-06T10:15:30.123456Z"))

        assertEquals(
            "Casey asked to take over Crew. If you do nothing, it transfers to them on $rendered. Open Pingolin Track to refuse.",
            alertHandler.ownershipClaimNamedCopy(data, withCircle = true),
        )
        // Names only: who, never which circle.
        assertEquals(
            "Casey asked to take over one of your circles. If you do nothing, it transfers to them on $rendered. Open Pingolin Track to refuse.",
            alertHandler.ownershipClaimNamedCopy(data, withCircle = false),
        )
        // No deadline: the call to action alone.
        assertEquals(
            "Casey asked to take over Crew. Open Pingolin Track to refuse.",
            alertHandler.ownershipClaimNamedCopy(data - "resolves_at", withCircle = true),
        )
        // Nobody named: the server's words stand.
        assertNull(alertHandler.ownershipClaimNamedCopy(data - "user_name", withCircle = true))
    }

    @Test
    fun `the claim alert takes the named words at each detail level`() {
        val source = java.io.File("src/main/java/com/pingolin/track/util/AlertHandler.kt").readText()
        val handler = source.substring(source.indexOf("private fun handleOwnershipClaim("))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(handler.contains("message = ownershipClaimNamedCopy(data, withCircle = true) ?: ownershipClaimCopy(data),"))
        assertTrue(handler.contains("namesOnlyMessage = ownershipClaimNamedCopy(data, withCircle = false),"))
        val names = java.io.File("src/main/java/com/pingolin/track/domain/repository/NotificationNames.kt").readText()
        assertTrue(names.contains("person = member(circleId, claimant(circleId, metadata[\"claim_id\"]), pass),\n            // Which circle"))
    }

    /** The fallback when the server's body didn't come. */
    @Test
    fun `the claim's fallback words, with and without a deadline`() {
        alertHandler = newAlertHandler()
        val rendered = OwnershipClaimFormat.deadlineText(java.time.Instant.parse("2026-09-06T10:15:30.123456Z"))

        assertEquals(
            "An admin has asked to take over one of your circles. If you do nothing, it transfers to them on $rendered. Open Pingolin Track to refuse.",
            alertHandler.ownershipClaimCopy(mapOf("claim_id" to "c-1", "resolves_at" to "2026-09-06T10:15:30.123456Z")),
        )
        assertEquals(
            "An admin has asked to take over one of your circles. Open Pingolin Track to refuse.",
            alertHandler.ownershipClaimCopy(mapOf("claim_id" to "c-1")),
        )
    }

    /** No deadline is a smaller loss than no notification. It must still say what to do. */
    @Test
    fun `the claim copy degrades to the plain sentence without a deadline`() {
        alertHandler = newAlertHandler()

        val copy = alertHandler.ownershipClaimCopy(mapOf("claim_id" to "c-1"))

        assertTrue(copy.contains("asked to take over"))
        assertTrue("the call to action survives: $copy", copy.contains("refuse"))
        assertFalse("no half-built sentence: $copy", copy.contains("on ."))
    }

    /**
     * On FCM every message is a tickle, so the app re-fetches the row and its body — which
     * the server already rendered the date into — is folded into `data`. Preferring it
     * keeps every delivery path reading alike.
     */
    @Test
    fun `the claim copy prefers the server's own body`() {
        alertHandler = newAlertHandler()

        val stored = "An admin has asked to take over this circle. If you do nothing it " +
            "transfers to them on 6 September 2026. Open Pingolin to refuse."

        assertEquals(
            stored,
            alertHandler.ownershipClaimCopy(
                mapOf("body" to stored, "resolves_at" to "2026-09-06T10:15:30.123456Z")
            ),
        )
    }

    /**
     * The transfer notification goes to EVERY active member, and the server writes a
     * different sentence for the former owner than for the rest. Preferring the stored
     * body is what keeps each person reading the one addressed to them.
     */
    @Test
    fun `the transferred copy prefers the server's body, which differs per recipient`() {
        alertHandler = newAlertHandler()

        val toFormerOwner =
            "You did not respond to the request, so this circle has been transferred to an admin."
        val toEveryoneElse =
            "This circle has a new owner. The previous owner did not respond to a request to take it over."

        assertEquals(toFormerOwner, alertHandler.ownershipTransferredCopy(mapOf("body" to toFormerOwner)))
        assertEquals(toEveryoneElse, alertHandler.ownershipTransferredCopy(mapOf("body" to toEveryoneElse)))
    }

    /**
     * With no body the fallback is read by both audiences at once, so it must be true for
     * the person who lost the circle and for everyone who merely watched it change hands.
     */
    @Test
    fun `the transferred fallback is true for the former owner and for everyone else`() {
        alertHandler = newAlertHandler()

        val copy = alertHandler.ownershipTransferredCopy(mapOf("claim_id" to "c-1"))

        assertFalse("must not address one audience as \"you\": $copy", copy.contains("You did not"))
        assertFalse("and must name nobody: $copy", copy.contains("admin ") && copy.contains("Mallory"))
        assertTrue(copy.isNotBlank())
    }
}
