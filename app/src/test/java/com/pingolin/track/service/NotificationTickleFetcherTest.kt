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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.NotificationResponse
import com.pingolin.track.data.remote.dto.NotificationsListResponse
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import org.junit.Assert.assertNull
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The content-fetching half of a tickle.
 *
 * It is a plain constructor-injected class that both push transports (FCM and
 * UnifiedPush) call, so it can be unit-tested: a service that extends
 * android.app.Service and injects into `lateinit` fields cannot be. What is
 * pinned here: fold the metadata in, show each row once, mark it read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTickleFetcherTest {

    private lateinit var sessionManager: SessionManager
    private lateinit var alertHandler: AlertHandler
    private lateinit var tracksApi: TrackApi
    private lateinit var destinationRepository: DestinationRepository
    private lateinit var sosRepository: SosRepository
    private lateinit var cryptoManager: uniffi.pingolin_mobile.CryptoManager
    private lateinit var notificationNames: com.pingolin.track.domain.repository.NotificationNames
    private lateinit var myAlertResponders: com.pingolin.track.domain.repository.MyAlertResponders

    /** Every notification AlertHandler was asked to post, in order. */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()

    @Before
    fun setup() {
        sessionManager = mockk()
        alertHandler = mockk()
        tracksApi = mockk()
        destinationRepository = mockk()
        sosRepository = mockk()
        cryptoManager = mockk()
        notificationNames = mockk()
        myAlertResponders = mockk()

        every { sessionManager.isLoggedIn() } returns true
        coEvery { tracksApi.markNotificationRead(any()) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))

        val typeSlot = slot<String>()
        val dataSlot = slot<Map<String, String>>()
        every { alertHandler.handleAlert(capture(typeSlot), capture(dataSlot)) } answers {
            posted += typeSlot.captured to dataSlot.captured
        }
    }

    private fun fetcher() = NotificationTickleFetcher(
        sessionManager = sessionManager,
        alertHandler = alertHandler,
        tracksApi = tracksApi,
        destinationRepository = destinationRepository,
        sosRepository = sosRepository,
        cryptoManager = cryptoManager,
        notificationNames = notificationNames,
        myAlertResponders = myAlertResponders,
    )

    /**
     * A stored place-alert row as the server writes it: a direction-less type, a
     * generic title, a NULL body (the place name is encrypted), and the routing ids
     * in metadata.
     */
    private fun placeAlertRow(id: String) = NotificationResponse(
        id = id,
        circleId = "circle-1",
        notificationType = "place_alert",
        title = "Member arrived at a place",
        body = null,
        metadata = mapOf(
            "place_id" to "place-home",
            "target_user_id" to "member-alice",
            "event_type_hash" to "hash-enter",
        ),
    )

    private fun unread(vararg rows: NotificationResponse) =
        Response.success(
            ApiResponse(
                success = true,
                data = NotificationsListResponse(notifications = rows.toList()),
                error = null,
            )
        )

    private fun inviteRow(id: String, circleId: String? = "circle-1") = NotificationResponse(
        id = id,
        circleId = circleId,
        notificationType = "circle_invite",
        title = "Circle invitation",
        body = "You've been invited to join a circle",
        metadata = emptyMap(),
    )

    private fun pendingInvite(circleId: String, circle: String?, inviter: String?) = ReceivedInviteResponse(
        id = "invite-$circleId",
        circleId = circleId,
        invitedBy = "inviter-1",
        inviteType = "email",
        createdDayBucket = 0,
        expiresDayBucket = 7,
        hasEncryptedKey = true,
        circleName = circle,
        inviterDisplayName = inviter,
    )

    private fun pending(vararg invites: ReceivedInviteResponse) =
        Response.success(ApiResponse(success = true, data = invites.toList(), error = null))

    // The row carries no names; the pending invite does. They ride the client's own
    // authenticated fetch, never the push — so the banner can say who and which
    // circle while the relay saw only "Circle invitation".
    @Test
    fun `an invite row is shown with the inviter and circle taken from the pending invite`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(inviteRow("inv-row-1"))
        coEvery { tracksApi.getReceivedInvites() } returns pending(pendingInvite("circle-1", "Friends", "Sam Rivera"))
        fetcher().fetchAndShowNotifications()
        val (type, data) = posted.single()
        assertEquals("circle_invite", type)
        assertEquals("Sam Rivera", data["inviter_name"])
        assertEquals("Friends", data["circle_name"])
    }

    @Test
    fun `a failed invite lookup still shows the row, nameless`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(inviteRow("inv-row-2"))
        coEvery { tracksApi.getReceivedInvites() } throws RuntimeException("offline")
        fetcher().fetchAndShowNotifications()
        val (type, data) = posted.single()
        assertEquals("circle_invite", type)
        assertNull(data["inviter_name"])
        assertNull(data["circle_name"])
    }

    @Test
    fun `inviteNamesFor prefers the row's circle and otherwise accepts only a lone invite`() {
        val friends = pendingInvite("circle-1", "Friends", "Sam")
        val work = pendingInvite("circle-2", "Work", "Alex")
        assertEquals("Sam" to "Friends", NotificationTickleFetcher.inviteNamesFor(listOf(work, friends), "circle-1"))
        assertEquals("Alex" to "Work", NotificationTickleFetcher.inviteNamesFor(listOf(work), "circle-9"))
        assertNull(NotificationTickleFetcher.inviteNamesFor(listOf(work, friends), "circle-9"))
        assertNull(NotificationTickleFetcher.inviteNamesFor(emptyList(), "circle-1"))
        // Blank names read as absent so the fallback copy applies.
        assertEquals(null to "Friends", NotificationTickleFetcher.inviteNamesFor(listOf(pendingInvite("circle-1", "Friends", " ")), "circle-1"))
    }

    @Test
    fun `each unread row reaches AlertHandler with its metadata folded in`() = runTest {
        // The ids the routing needs live in the metadata blob, not the envelope, and
        // the per-type handlers read them off the same flat map — so the fold is what
        // makes a place alert identifiable at all.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("fold-1"))

        fetcher().fetchAndShowNotifications()

        assertEquals(1, posted.size)
        val (type, data) = posted.single()
        assertEquals("place_alert", type)
        assertEquals("place-home", data["place_id"])
        assertEquals("member-alice", data["target_user_id"])
        assertEquals("hash-enter", data["event_type_hash"])
        assertEquals("Member arrived at a place", data["title"])
        assertEquals("fold-1", data["alert_id"])
        // A NULL body arrives as empty, never as the literal "null" —
        // serverCopy gates on isNotBlank and would otherwise render it.
        assertEquals("", data["body"])
    }

    @Test
    fun `a row already shown in this process is not shown again`() = runTest {
        // Two tickles arriving together both fetch unreadOnly before either has
        // marked anything read; without the in-memory set the loser re-posts the row.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("dupe-1"))

        val fetcher = fetcher()
        fetcher.fetchAndShowNotifications()
        fetcher.fetchAndShowNotifications()

        assertEquals(1, posted.size)
    }

    @Test
    fun `the dedupe set is process-wide, not per-instance`() = runTest {
        // A device can hold an FCM registration and a UnifiedPush one at once, and
        // each transport reaches this through its own call path.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("shared-1"))

        fetcher().fetchAndShowNotifications()
        fetcher().fetchAndShowNotifications()

        assertEquals(1, posted.size)
    }

    @Test
    fun `every shown row is marked read server-side`() = runTest {
        // The load-bearing half of keeping a row from being shown twice:
        // unreadOnly is what stops the next tickle returning the same row after
        // a restart, when the in-memory set is gone.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("read-1"))

        fetcher().fetchAndShowNotifications()

        coVerify(exactly = 1) { tracksApi.markNotificationRead("read-1") }
    }

    @Test
    fun `a failed mark-read still leaves the notification shown`() = runTest {
        // Best-effort by design: the user seeing the alert matters more than the
        // bookkeeping that stops it recurring.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("markfail-1"))
        coEvery { tracksApi.markNotificationRead(any()) } throws RuntimeException("offline")

        fetcher().fetchAndShowNotifications()

        assertEquals(1, posted.size)
    }

    @Test
    fun `a logged-out device fetches nothing`() = runTest {
        every { sessionManager.isLoggedIn() } returns false

        fetcher().fetchAndShowNotifications()

        coVerify(exactly = 0) { tracksApi.getNotifications(any(), any(), any()) }
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a place alert never touches the destination repository`() = runTest {
        // Both refreshes are gated on the notification type. An ungated one would put
        // a network round-trip for an unrelated feature in front of every alert —
        // and destinationRepository is a strict mock here, so a stray call throws.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("gate-1"))

        fetcher().fetchAndShowNotifications()

        assertEquals(1, posted.size)
        coVerify(exactly = 0) { destinationRepository.refreshDestinations() }
        coVerify(exactly = 0) { sosRepository.refreshActiveAlerts() }
    }

    @Test
    fun `a failed fetch is reported, not thrown at the caller`() = runTest {
        // The FCM path launches this into a scope with no handler, so it
        // must not throw — but it must still SAY it failed. Returning
        // normally from here would make NotificationTickleWorker's retry
        // branch unreachable: every run would end in Result.success() and
        // the tickle would be dropped in silence. A tickle carries no
        // content, so a dropped one is a notification never seen.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } throws RuntimeException("offline")

        val completed = fetcher().fetchAndShowNotifications()

        assertFalse("a throw must report failure so the worker can retry", completed)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a non-2xx answer is reported as a failed fetch`() = runTest {
        // The other silent path: not an exception, just a response we can't use.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns
            Response.error(503, "".toResponseBody(null))

        val completed = fetcher().fetchAndShowNotifications()

        assertFalse("a non-2xx must report failure so the worker can retry", completed)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `an empty unread list is a completed fetch, not a failure`() = runTest {
        // Nothing to show is a normal outcome. Retrying it would wake the device to
        // ask the same question and get the same answer.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread()

        assertTrue(fetcher().fetchAndShowNotifications())
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `showing a row is a completed fetch`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("ok-1"))

        assertTrue(fetcher().fetchAndShowNotifications())
        assertEquals(1, posted.size)
    }

    @Test
    fun `being logged out is a completed fetch, not a failure`() = runTest {
        // Retrying would not log anybody in, and the worker checks this too.
        every { sessionManager.isLoggedIn() } returns false

        assertTrue(fetcher().fetchAndShowNotifications())
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a fetch asks for more than a handful of unread rows`() = runTest {
        // A fetch asks for more than a handful of unread rows: a limit of 5 is
        // fewer than one day offline can accumulate. Nothing is dropped at the
        // cap — an unshown row is never marked read, so the next fetch takes it —
        // but the drain rate is one batch per fetch, and a fetch happens only when
        // a push arrives or the 15-minute poll fires. At 5 a backlog of 40 needs
        // eight of those, up to two hours, to surface a notification that was
        // already late.
        //
        // Asserted as a floor rather than an exact number: what is wrong with 5 is
        // its order of magnitude, and the server clamps anything above 100 anyway,
        // so the range this pins is the range that behaves.
        val limit = slot<Int>()
        coEvery {
            tracksApi.getNotifications(capture(limit), any(), any())
        } returns unread(placeAlertRow("limit-1"))

        fetcher().fetchAndShowNotifications()

        assertTrue(
            "A fetch that stops at ${limit.captured} rows leaves a real backlog " +
                "draining across hours of pushes",
            limit.captured >= 20,
        )
        assertTrue(
            "The server clamps at 100; asking for more is a silently truncated request",
            limit.captured <= 100,
        )
    }

    @Test
    fun `only unread rows are fetched`() = runTest {
        // The other half of the same call, and the half that stops the batch above
        // re-posting everything the user has already seen.
        val unreadOnly = slot<Boolean>()
        coEvery {
            tracksApi.getNotifications(any(), any(), capture(unreadOnly))
        } returns unread(placeAlertRow("unread-1"))

        fetcher().fetchAndShowNotifications()

        assertTrue(unreadOnly.captured)
    }

    // =========================================================================
    // THE SERVER CANNOT WRITE WHAT THIS DEVICE DECRYPTS
    // =========================================================================

    private fun row(
        id: String,
        type: String,
        metadata: Map<String, Any>,
        body: String? = null,
    ) = NotificationResponse(
        id = id,
        circleId = "circle-1",
        notificationType = type,
        title = "Server title",
        body = body,
        metadata = metadata,
    )

    @Test
    fun `server metadata cannot supply the names this device decrypts`() = runTest {
        // A destination row whose decrypt fails: the copy must fall back to generic,
        // not to whatever the server wrote under the keys the decrypt would have set.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row(
                "forge-dest", "destination_arrival",
                mapOf(
                    "destination_id" to "dest-1",
                    "encrypted_user_id" to "ciphertext",
                    "user_id_nonce" to "nonce",
                    "actor_name" to "Mum",
                    "dest_label" to "Home",
                    "dest_lat" to "48.8584",
                    "dest_lng" to "2.2945",
                ),
            )
        )
        coEvery { destinationRepository.refreshDestinations() } returns Result.success(emptyList())
        coEvery { destinationRepository.resolveNotificationCopy(any()) } returns
            DestinationRepository.NotificationCopy(actorName = null, destLabel = null)

        fetcher().fetchAndShowNotifications()

        val (_, data) = posted.single()
        assertNull("server-written actor_name reached AlertHandler", data["actor_name"])
        assertNull("server-written dest_label reached AlertHandler", data["dest_label"])
        assertNull("server-written dest_lat reached AlertHandler", data["dest_lat"])
        assertNull("server-written dest_lng reached AlertHandler", data["dest_lng"])
        // The opaque ids and ciphertexts the decrypt needs still arrive.
        assertEquals("dest-1", data["destination_id"])
        assertEquals("ciphertext", data["encrypted_user_id"])
    }

    @Test
    fun `names this device decrypts still reach the notification`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("real-dest", "destination_arrival", mapOf("destination_id" to "dest-1"))
        )
        coEvery { destinationRepository.refreshDestinations() } returns Result.success(emptyList())
        coEvery { destinationRepository.resolveNotificationCopy(any()) } returns
            DestinationRepository.NotificationCopy("Alice", "Home", 48.8584, 2.2945)

        fetcher().fetchAndShowNotifications()

        val (_, data) = posted.single()
        assertEquals("Alice", data["actor_name"])
        assertEquals("Home", data["dest_label"])
        assertEquals("48.8584", data["dest_lat"])
    }

    @Test
    fun `a server row cannot pose as a crossing this device detected`() = runTest {
        // local_evaluated makes AlertHandler post at once, on the "detected on this
        // device" channel, with the row's own body. Only PlaceAlertEvaluator sets it.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row(
                "forge-place", "place_arrival",
                mapOf("local_evaluated" to "true", "place_id" to "place-home", "user_name" to "Mum"),
                body = "Mum arrived at the station",
            )
        )

        fetcher().fetchAndShowNotifications()

        val (_, data) = posted.single()
        assertNull(data["local_evaluated"])
        assertNull(data["user_name"])
        assertEquals("place-home", data["place_id"])
    }

    @Test
    fun `an SOS row cannot name who raised it`() = runTest {
        // The real row carries only sos_id and circle_id; a name here could only be
        // the server's invention, rendered as "<name> needs attention".
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row(
                "forge-sos", "sos_alert",
                mapOf("sos_id" to "sos-1", "user_name" to "Mum", "circle_name" to "Family"),
            )
        )
        coEvery { sosRepository.checkPushed(any()) } returns SosRepository.PushCheck.CantCheck

        fetcher().fetchAndShowNotifications()

        val (_, data) = posted.single()
        assertNull(data["user_name"])
        assertNull(data["circle_name"])
        assertEquals("sos-1", data["sos_id"])
    }

    private fun sosRow(id: String, metadata: Map<String, String> = mapOf("sos_id" to "sos-1")) =
        row(id, "sos_alert", metadata)

    @Test
    fun `an SOS is checked before it may ring, and what the check found rides to AlertHandler`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(sosRow("sos-row-1"))
        coEvery { sosRepository.checkPushed("sos-1") } returns SosRepository.PushCheck.Alarming("Alice", "Family")

        fetcher().fetchAndShowNotifications()

        val (type, data) = posted.single()
        assertEquals(AlertHandler.TYPE_SOS_ALERT, type)
        assertEquals(AlertHandler.SOS_CHECK_ALARMING, data[AlertHandler.KEY_SOS_CHECK])
        assertEquals("Alice", data[AlertHandler.KEY_SOS_RAISER_NAME])
        assertEquals("Family", data[AlertHandler.KEY_SOS_CIRCLE_NAME])
        // The check reads the list itself; nothing else refreshes it first.
        coVerify(exactly = 0) { sosRepository.refreshActiveAlerts() }
    }

    @Test
    fun `an SOS checked and not live goes to AlertHandler as such, and one that couldn't be checked carries no outcome`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(sosRow("sos-row-2"))
        coEvery { sosRepository.checkPushed("sos-1") } returns SosRepository.PushCheck.NotLive
        fetcher().fetchAndShowNotifications()
        assertEquals(AlertHandler.SOS_CHECK_NOT_LIVE, posted.single().second[AlertHandler.KEY_SOS_CHECK])

        posted.clear()
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(sosRow("sos-row-3"))
        coEvery { sosRepository.checkPushed("sos-1") } returns SosRepository.PushCheck.CantCheck
        fetcher().fetchAndShowNotifications()
        val data = posted.single().second
        assertNull(data[AlertHandler.KEY_SOS_CHECK])
        assertNull(data[AlertHandler.KEY_SOS_RAISER_NAME])
    }

    @Test
    fun `a row can't carry a check's outcome or its names`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            sosRow(
                "sos-row-4",
                mapOf(
                    "sos_id" to "sos-1",
                    AlertHandler.KEY_SOS_CHECK to AlertHandler.SOS_CHECK_ALARMING,
                    AlertHandler.KEY_SOS_RAISER_NAME to "Mum",
                    AlertHandler.KEY_SOS_CIRCLE_NAME to "Family",
                    AlertHandler.KEY_SOS_SILENT to "1",
                ),
            )
        )
        coEvery { sosRepository.checkPushed("sos-1") } returns SosRepository.PushCheck.CantCheck

        fetcher().fetchAndShowNotifications()

        val data = posted.single().second
        assertNull(data[AlertHandler.KEY_SOS_CHECK])
        assertNull(data[AlertHandler.KEY_SOS_RAISER_NAME])
        assertNull(data[AlertHandler.KEY_SOS_CIRCLE_NAME])
        assertNull("a row can't silence an alarm", data[AlertHandler.KEY_SOS_SILENT])
    }

    @Test
    fun `an SOS alarm goes through the ring routine under the row's ids - with no sound once it rang here`() = runTest {
        val circle = "ca11ab1e-0000-4000-8000-0000000000c1"
        val sos = "0505abcd-0000-4000-8000-00000000abcd"
        var rangHere = false
        every { sosRepository.ringOnce(circle, sos, any(), any()) } answers { arg<(Boolean) -> Unit>(3)(!rangHere) }

        val outcomes = listOf(
            SosRepository.PushCheck.Alarming("Alice", "Family"),
            SosRepository.PushCheck.CantCheck,
        )
        for ((n, outcome) in outcomes.withIndex()) {
            for (rang in listOf(false, true)) {
                rangHere = rang
                posted.clear()
                val row = sosRow("sos-ring-$n-$rang", mapOf("sos_id" to sos.uppercase())).copy(circleId = "{$circle}")
                coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(row)
                coEvery { sosRepository.checkPushed(sos.uppercase()) } returns outcome
                fetcher().fetchAndShowNotifications()
                assertEquals("$outcome, rang here: $rang", if (rang) "1" else null, posted.single().second[AlertHandler.KEY_SOS_SILENT])
            }
        }
        io.mockk.verify(exactly = 4) { sosRepository.ringOnce(circle, sos, any(), any()) }

        // Checked and not live: no ring routine, the post just goes.
        posted.clear()
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(sosRow("sos-ring-not-live", mapOf("sos_id" to sos)).copy(circleId = circle))
        coEvery { sosRepository.checkPushed(sos) } returns SosRepository.PushCheck.NotLive
        fetcher().fetchAndShowNotifications()
        assertEquals(AlertHandler.SOS_CHECK_NOT_LIVE, posted.single().second[AlertHandler.KEY_SOS_CHECK])
        io.mockk.verify(exactly = 4) { sosRepository.ringOnce(any(), any(), any(), any()) }
    }

    @Test
    fun `the ring routine's ids - both UUIDs, in their one spelling, else none`() {
        val circle = "ca11ab1e-0000-4000-8000-0000000000c1"
        val sos = "0505abcd-0000-4000-8000-00000000abcd"
        assertEquals(circle to sos, NotificationTickleFetcher.sosRingIds(mapOf("circle_id" to circle.uppercase(), "sos_id" to "{$sos}")))
        assertNull(NotificationTickleFetcher.sosRingIds(mapOf("circle_id" to "circle-1", "sos_id" to sos)))
        assertNull(NotificationTickleFetcher.sosRingIds(mapOf("circle_id" to circle, "sos_id" to "sos-1")))
        assertNull(NotificationTickleFetcher.sosRingIds(mapOf("circle_id" to "", "sos_id" to sos)))
        assertNull(NotificationTickleFetcher.sosRingIds(mapOf("sos_id" to sos)))
    }

    @Test
    fun `an SOS follow-up still refreshes the list first, and is never checked as an alarm`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(row("sos-row-5", "sos_resolved", mapOf("sos_id" to "sos-1")))
        coEvery { sosRepository.refreshActiveAlerts() } returns Result.success(emptyList())

        fetcher().fetchAndShowNotifications()

        coVerify(exactly = 1) { sosRepository.refreshActiveAlerts() }
        coVerify(exactly = 0) { sosRepository.checkPushed(any()) }
        assertEquals(AlertHandler.TYPE_SOS_RESOLVED, posted.single().first)
    }

    @Test
    fun `metadata cannot overwrite the row's own envelope`() {
        val data = NotificationTickleFetcher.alertDataFor(
            row(
                "env-1", "place_alert",
                mapOf(
                    "alert_id" to "someone-elses-notification",
                    "type" to "sos_alert",
                    "title" to "Forged",
                    "body" to "Forged",
                    "circle_id" to "circle-2",
                    "created_at" to "2020-01-01T00:00:00Z",
                ),
            )
        )
        assertEquals("env-1", data["alert_id"])
        assertEquals("place_alert", data["type"])
        assertEquals("Server title", data["title"])
        assertEquals("", data["body"])
        assertEquals("circle-1", data["circle_id"])
        assertNull(data["created_at"])
    }

    @Test
    fun `every metadata key the backend writes still arrives`() {
        // The allowlist is the list of keys the backend's INSERTs write. Dropping one
        // silently breaks a feature (a receipt, a deep link, a rotation trigger), so
        // each is asserted by name.
        val written = mapOf(
            "sos_id" to "sos-1",
            "destination_id" to "d", "event_id" to "e", "self" to true,
            "encrypted_dest_name" to "c1", "dest_name_nonce" to "n1",
            "encrypted_user_id" to "c2", "user_id_nonce" to "n2",
            "encrypted_occurred_at" to "c3", "occurred_at_nonce" to "n3",
            "place_alert_id" to "pa", "place_id" to "p", "target_user_id" to "u",
            "event_type_hash" to "h",
            "needs_delivery" to true, "needs_key_rotation" to true, "send_location" to true,
            "claim_id" to "cl", "resolves_at" to "2026-10-07T00:00:00Z", "new_owner_id" to "o",
            "check_in_id" to "ci",
        )
        val data = NotificationTickleFetcher.alertDataFor(row("all-1", "member_joined", written))
        for ((key, value) in written) {
            assertEquals(key, value.toString(), data[key])
        }
        assertEquals(written.keys, NotificationTickleFetcher.SERVER_METADATA_KEYS)
    }

    @Test
    fun `a row that fails to mark read is still a completed fetch`() = runTest {
        // The row HAS been shown. Retrying could only show it twice, so a failure to
        // mark it read stays best-effort — the in-process dedupe set covers the rest
        // of this lifetime.
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(placeAlertRow("mark-1"))
        coEvery { tracksApi.markNotificationRead(any()) } throws RuntimeException("offline")

        assertTrue(fetcher().fetchAndShowNotifications())
        assertEquals(1, posted.size)
    }

    // ===== Named notifications =====

    /** A check-in row as the server writes it, with its check-in and place ids and the sender
     * sealed under the circle key.
     */
    private fun checkInRow(id: String, extra: Map<String, Any> = emptyMap()) = row(
        id, "check_in",
        mapOf(
            "check_in_id" to "ci-1", "place_id" to "place-home",
            "encrypted_user_id" to "sealed-bob", "user_id_nonce" to "bm9uY2U=",
        ) + extra,
        body = "A circle member has checked in safely.",
    )

    @Test
    fun `a check-in is named on the push from what this device opens - who, where, and its kind`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(checkInRow("ci-row-1"))
        coEvery { notificationNames.namesFor("check_in", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Bob", place = "Home")

        fetcher().fetchAndShowNotifications()

        val (type, data) = posted.single()
        assertEquals(AlertHandler.TYPE_CHECK_IN, type)
        assertEquals("Bob", data["user_name"])
        assertEquals("Home", data["place_name"])
        assertEquals("safe_arrival", data["check_in_type"])
        // The id survives the allowlist (SERVER_METADATA_KEYS).
        assertEquals("ci-1", data["check_in_id"])
        // The sealed sender reaches the namer, in the row's own words.
        coVerify {
            notificationNames.namesFor(
                "check_in", "circle-1",
                match { it["encrypted_user_id"] == "sealed-bob" && it["user_id_nonce"] == "bm9uY2U=" && it["place_id"] == "place-home" },
                any(),
            )
        }
    }

    @Test
    fun `a check-in row cannot name itself, and nothing resolved leaves AlertHandler's own words`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            checkInRow("ci-row-2", mapOf("user_name" to "Mum", "place_name" to "The bank", "check_in_type" to "emergency_ok"))
        )
        coEvery { notificationNames.namesFor(any(), any(), any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names()

        fetcher().fetchAndShowNotifications()

        val data = posted.single().second
        assertNull(data["user_name"])
        assertNull(data["place_name"])
        // Read back from the server's own sentence, never from a key the row set.
        assertEquals("safe_arrival", data["check_in_type"])
    }

    /**
     * Who asked to take over, and which circle, as this device names them. The alert's named words
     * read `user_name` and `circle_name`, and the fetch is their only writer: without it they are
     * never there, and every claim alert says the server's nameless words.
     */
    @Test
    fun `a takeover request is named on the push from this device - who asked, and which circle`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("claim-row-1", "circle_ownership_claim", mapOf("claim_id" to "k-1", "resolves_at" to "2026-09-06T10:15:30Z", "user_name" to "Mallory"))
        )
        coEvery { notificationNames.namesFor("circle_ownership_claim", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Casey", circle = "Crew")

        fetcher().fetchAndShowNotifications()

        val (type, data) = posted.single()
        assertEquals(AlertHandler.TYPE_CIRCLE_OWNERSHIP_CLAIM, type)
        assertEquals("Casey", data["user_name"])
        assertEquals("Crew", data["circle_name"])
        assertEquals("k-1", data["claim_id"])
        coVerify { notificationNames.namesFor("circle_ownership_claim", "circle-1", match { it["claim_id"] == "k-1" }, any()) }
    }

    @Test
    fun `a refusal is named on the push by its circle, from this device`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("refused-row-1", "circle_ownership_refused", mapOf("claim_id" to "k-1"), body = "The owner refused your request to take over the circle. It stays theirs.")
        )
        coEvery { notificationNames.namesFor("circle_ownership_refused", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(circle = "Crew")

        fetcher().fetchAndShowNotifications()

        val (type, data) = posted.single()
        assertEquals(AlertHandler.TYPE_CIRCLE_OWNERSHIP_REFUSED, type)
        assertEquals("Crew", data["circle_name"])
        assertNull(data["user_name"])
    }

    @Test
    fun `a takeover request this device can't name carries no name at all`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("claim-row-2", "circle_ownership_claim", mapOf("claim_id" to "k-1", "user_name" to "Mallory", "circle_name" to "Fake"))
        )
        coEvery { notificationNames.namesFor(any(), any(), any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names()

        fetcher().fetchAndShowNotifications()

        val data = posted.single().second
        // Never what the row says under those keys (SERVER_METADATA_KEYS).
        assertNull(data["user_name"])
        assertNull(data["circle_name"])
    }

    @Test
    fun `who is responding is named only from this device's roster`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("ack-row-1", "sos_acknowledged", mapOf("sos_id" to "sos-1", "acknowledged_by" to "bob-id", "user_name" to "Mum"))
        )
        coEvery { sosRepository.refreshActiveAlerts() } returns Result.success(emptyList())
        coEvery { notificationNames.namesFor("sos_acknowledged", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Bob")

        fetcher().fetchAndShowNotifications()

        assertEquals("Bob", posted.single().second["user_name"])
    }

    // ===== who is responding, and who closed the alert =====

    private val bob = "b0b00000-0000-4000-8000-000000000b0b"

    @Test
    fun `a response names who answered and how many answered before them, from the alert's own list`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("ack-row-2", "sos_acknowledged", mapOf("sos_id" to "sos-1", "circle_id" to "circle-1", "acknowledged_by" to bob))
        )
        coEvery { sosRepository.refreshActiveAlerts() } returns Result.success(emptyList())
        coEvery { myAlertResponders.earlierThan("sos-1", bob) } returns 2
        coEvery { notificationNames.namesFor("sos_acknowledged", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Bob")

        fetcher().fetchAndShowNotifications()

        val data = posted.single().second
        assertEquals("Bob", data["user_name"])
        assertEquals("2", data[AlertHandler.KEY_OTHERS_RESPONDING])
        // Never the alert's acknowledgment_count, which can count the asker's own tap.
        coVerify(exactly = 0) { sosRepository.getAlert(any()) }
    }

    @Test
    fun `the first to answer, no list, or a list that can't be read says no others`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("ack-row-3", "sos_acknowledged", mapOf("sos_id" to "sos-1", "acknowledged_by" to bob)),
            row("ack-row-4", "sos_acknowledged", mapOf("sos_id" to "sos-2", "acknowledged_by" to bob)),
            row("ack-row-5", "sos_acknowledged", mapOf("sos_id" to "sos-3", "acknowledged_by" to bob)),
        )
        coEvery { sosRepository.refreshActiveAlerts() } returns Result.success(emptyList())
        coEvery { myAlertResponders.earlierThan("sos-1", bob) } returns 0
        coEvery { myAlertResponders.earlierThan("sos-2", bob) } returns null
        coEvery { myAlertResponders.earlierThan("sos-3", bob) } throws IllegalStateException("offline")
        coEvery { notificationNames.namesFor(any(), any(), any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Bob")

        fetcher().fetchAndShowNotifications()

        assertEquals(3, posted.size)
        posted.forEach { (_, data) -> assertNull(data[AlertHandler.KEY_OTHERS_RESPONDING]) }
    }

    @Test
    fun `a closed alert names who closed it, from this device's roster, and never whose it was`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("res-row-1", "sos_resolved", mapOf("sos_id" to "sos-1", "closed_by" to bob)),
            row("res-row-2", "sos_resolved", mapOf("sos_id" to "sos-2", "closed_by" to "90570000-0000-4000-8000-000000000905")),
            row("res-row-3", "sos_resolved", mapOf("sos_id" to "sos-3")),
        )
        coEvery { sosRepository.refreshActiveAlerts() } returns Result.success(emptyList())
        coEvery { notificationNames.namesFor("sos_resolved", "circle-1", match { it["sos_id"] == "sos-1" }, any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Bob", closer = true)
        coEvery { notificationNames.namesFor("sos_resolved", "circle-1", match { it["sos_id"] == "sos-2" }, any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(closer = true)
        // An older row: NotificationNames names whose request it was; that must never be said as the closer.
        coEvery { notificationNames.namesFor("sos_resolved", "circle-1", match { it["sos_id"] == "sos-3" }, any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Alice")

        fetcher().fetchAndShowNotifications()

        assertEquals(listOf("Bob", "Someone", null), posted.map { it.second[AlertHandler.KEY_CLOSER_NAME] })
        posted.forEach { (_, data) -> assertNull(data["user_name"]) }
    }

    @Test
    fun `a batch refetches each circle's roster at most once`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(checkInRow("ci-row-3"), checkInRow("ci-row-4"))
        val passes = mutableListOf<com.pingolin.track.domain.repository.NotificationNames.Pass>()
        coEvery { notificationNames.namesFor(any(), any(), any(), capture(passes)) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names()

        fetcher().fetchAndShowNotifications()

        assertEquals(2, passes.size)
        assertTrue("one pass for the whole batch", passes[0] === passes[1])
    }

    // ===== a member_joined is shown, named, when the stored preference allows it =====

    /** A member_joined row as the server writes it when a member joins. */
    private fun joinedRow(id: String) = row(
        id, "member_joined",
        mapOf(
            "needs_key_rotation" to true, "needs_delivery" to true, "send_location" to true,
            "joined_user_id" to "a0a00000-0000-4000-8000-0000000000a0",
        ),
        body = "Someone joined a circle you are in. They can see its location history.",
    )

    private fun prefs(vararg pairs: Pair<String, Any>) =
        Response.success(ApiResponse(success = true, data = mapOf(*pairs), error = null))

    @Test
    fun `a member_joined is shown, named, when the stored preference allows it`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(joinedRow("mj-1"))
        coEvery { tracksApi.getNotificationPreferences() } returns prefs("push_enabled" to true, "member_joined_push" to true)
        coEvery { notificationNames.namesFor("member_joined", "circle-1", any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names(person = "Ana", circle = "Family")

        fetcher().fetchAndShowNotifications()

        val (type, data) = posted.single()
        assertEquals(AlertHandler.TYPE_MEMBER_JOINED, type)
        assertEquals("1", data[AlertHandler.KEY_MEMBER_JOINED_SHOWN])
        assertEquals("Ana", data["user_name"])
        assertEquals("Family", data["circle_name"])
        // The hand-over keys still ride along.
        assertEquals("true", data["needs_delivery"])
    }

    @Test
    fun `a member_joined is not shown when the preference is off, unset under push off, or unreadable`() = runTest {
        for (stored in listOf(
            prefs("push_enabled" to true, "member_joined_push" to false),
            prefs("push_enabled" to false),
            Response.error<ApiResponse<Map<String, Any>>>(500, "{}".toResponseBody(null)),
        )) {
            posted.clear()
            coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(joinedRow("mj-${posted.size}-${stored.code()}-${stored.body()?.data?.size}"))
            coEvery { tracksApi.getNotificationPreferences() } returns stored

            fetcher().fetchAndShowNotifications()

            val data = posted.single().second
            assertNull("shown for $stored", data[AlertHandler.KEY_MEMBER_JOINED_SHOWN])
            assertNull(data["user_name"])
        }
        // Unset under push on: the server's inherit, so shown.
        posted.clear()
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(joinedRow("mj-inherit"))
        coEvery { tracksApi.getNotificationPreferences() } returns prefs("push_enabled" to true)
        coEvery { notificationNames.namesFor(any(), any(), any(), any()) } returns
            com.pingolin.track.domain.repository.NamedNotificationCopy.Names()
        fetcher().fetchAndShowNotifications()
        assertEquals("1", posted.single().second[AlertHandler.KEY_MEMBER_JOINED_SHOWN])
    }

    @Test
    fun `a row cannot say it may be shown, and the preference is read once per batch`() = runTest {
        coEvery { tracksApi.getNotifications(any(), any(), any()) } returns unread(
            row("mj-forge", "member_joined", mapOf(AlertHandler.KEY_MEMBER_JOINED_SHOWN to "1", "circle_name" to "Bank")),
            joinedRow("mj-a"),
        )
        coEvery { tracksApi.getNotificationPreferences() } returns prefs("member_joined_push" to false)

        fetcher().fetchAndShowNotifications()

        assertEquals(2, posted.size)
        posted.forEach { (_, data) ->
            assertNull(data[AlertHandler.KEY_MEMBER_JOINED_SHOWN])
            assertNull(data["circle_name"])
        }
        coVerify(exactly = 1) { tracksApi.getNotificationPreferences() }
    }
}
