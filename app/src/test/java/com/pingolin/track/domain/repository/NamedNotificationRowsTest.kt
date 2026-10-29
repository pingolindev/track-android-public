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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * The in-app list names its rows: from rows exactly as the server writes them (each kind with
 * its own title, body and metadata: place alerts, check-ins, SOS alerts, circle and
 * ownership-claim events), through MockWebServer and real Retrofit/Gson, into
 * [NotificationRepository.refreshNotifications] and a real [NotificationNames].
 */
class NamedNotificationRowsTest {

    private companion object {
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-000000000b0b"
        const val GHOST = "90570000-0000-4000-8000-000000000905"
    }

    private val home = "4011e000-0000-4000-8000-00000000a0e1"
    private val sosId = "5050a000-0000-4000-8000-00000000505a"

    private lateinit var server: MockWebServer
    private lateinit var repository: NotificationRepository
    private val stored = mutableListOf<NotificationEntity>()
    private var detail = SessionManager.NOTIF_DETAIL_FULL

    private val circleMemberDao: CircleMemberDao = mockk()
    private val circleRepository: CircleRepository = mockk(relaxed = true)

    /** Who asked to be checked on, as the receivers' rules allow it (SosRowRaiserTest has the rules). */
    private val sosRepository: SosRepository = mockk()

    private fun member(userId: String, name: String, opened: Boolean = true) = CircleMemberEntity(
        circleId = CIRCLE, userId = userId, displayName = name, role = "member", joinedAt = 0L, nameOpened = opened,
    )

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)

        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(
            member(ALICE, "Alice"),
            member(BOB, "Bob"),
            // The server's plain fallback name, which never
            // names anyone.
            member(GHOST, "Ghost", opened = false),
        )
        val placeDao = mockk<PlaceDao>()
        coEvery { placeDao.getById(any()) } returns null
        coEvery { placeDao.getById(home) } returns PlaceEntity(
            id = home, circleId = "self", name = "Home", address = null, latitude = 37.0, longitude = -122.0,
            radius = 150, icon = null, color = null, createdAt = 0L, updatedAt = 0L, sharedCircles = null,
        )
        val circleDao = mockk<CircleDao>()
        coEvery { circleDao.getById(CIRCLE) } returns mockk<CircleEntity> { every { name } returns "Family" }
        coEvery { sosRepository.raiserToName(any(), any()) } returns null
        coEvery { sosRepository.raiserToName(sosId, CIRCLE) } returns ALICE
        val crypto = mockk<CryptoManager>()
        // A check-in's sender, sealed under the circle key with the field tag "user_id".
        every { crypto.decryptDataV2(CIRCLE, EncryptedData(nonce = "bm9uY2U=", ciphertext = "sealed-bob"), "user_id") } returns BOB
        val session = mockk<SessionManager>()
        every { session.getUserId() } returns ME
        every { session.getNotificationDetailLevel() } answers { detail }

        val notificationDao = mockk<NotificationDao>(relaxed = true)
        coEvery { notificationDao.insertAll(any()) } answers { stored += arg<List<NotificationEntity>>(0) }

        val names = NotificationNames(
            circleMemberDao = circleMemberDao,
            circleDao = circleDao,
            placeDao = placeDao,
            tracksApi = api,
            cryptoManager = crypto,
            sessionManager = session,
            circleRepository = dagger.Lazy { circleRepository },
            sosRepository = dagger.Lazy { sosRepository },
        )
        repository = NotificationRepository(api, notificationDao, placeDao, mockk(relaxed = true), names, session)
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private var rowCount = 0

    /** One row as the server serialises it: `body` and `metadata` are null when absent, never left out. */
    private fun row(type: String, title: String, body: String?, metadata: String?): String {
        rowCount++
        return """{"id":"00000000-0000-4000-8000-${rowCount.toString().padStart(12, '0')}","circle_id":"$CIRCLE",""" +
            """"notification_type":"$type","title":"$title","body":${body?.let { "\"$it\"" } ?: "null"},""" +
            """"metadata":${metadata ?: "null"},"is_read":false,"created_day_bucket":20375,""" +
            """"created_at":"2026-10-14T12:00:00.123456Z"}"""
    }

    private suspend fun refresh(vararg rows: String): List<NotificationEntity> {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"notifications":[${rows.joinToString(",")}],"unread_count":${rows.size},"has_more":false}}"""
            )
        )
        stored.clear()
        repository.refreshNotifications().getOrThrow()
        return stored.toList()
    }

    private val placeAlertRow get() = row(
        "place_alert", "Member arrived at a place", null,
        """{"place_alert_id":"pa-1","place_id":"$home","target_user_id":"$ALICE","event_type_hash":"h"}""",
    )

    @Test
    fun `the list names who arrived where, who checked in, who asked, and who joined`() = runTest {
        val rows = refresh(
            placeAlertRow,
            // The check-in's id, place and sealed sender, copied from the
            // request.
            row("check_in", "Check-in", "A circle member has checked in safely.",
                """{"check_in_id":"ci-1","place_id":"$home","encrypted_user_id":"sealed-bob","user_id_nonce":"bm9uY2U="}"""),
            row("sos_alert", "Someone needs attention", "A circle member asked their circle to check on them",
                """{"sos_id":"$sosId","circle_id":"$CIRCLE"}"""),
            row("member_joined", "New circle member", "Someone joined a circle you are in. They can see its location history.",
                """{"needs_key_rotation":true,"needs_delivery":true,"send_location":true,"joined_user_id":"$BOB"}"""),
        )

        assertEquals("Alice arrived at Home", rows[0].title)
        assertEquals(null, rows[0].body)
        assertEquals("Bob has checked in safely at Home.", rows[1].body)
        assertEquals("Alice needs attention", rows[2].title)
        assertEquals("Alice asked their circle to check on them", rows[2].body)
        assertEquals("Bob joined Family. They can see its location history.", rows[3].body)
    }

    @Test
    fun `names only drops the place, and generic keeps every row as the server wrote it`() = runTest {
        detail = SessionManager.NOTIF_DETAIL_NAMES
        assertEquals("Alice arrived at a place", refresh(placeAlertRow).single().title)

        detail = SessionManager.NOTIF_DETAIL_GENERIC
        assertEquals("Member arrived at a place", refresh(placeAlertRow).single().title)
    }

    @Test
    fun `a name the server supplied, an unknown place or a departed member stays generic`() = runTest {
        val rows = refresh(
            // Ghost's only name is the server's plain fallback; the roster refetch doesn't open it.
            row("place_alert", "Member left a place", null, """{"place_id":"$home","target_user_id":"$GHOST"}"""),
            // A place this device doesn't hold; and someone not in the roster
            // at all.
            row("place_alert", "Member arrived at a place", null,
                """{"place_id":"0ff00000-0000-4000-8000-0000000000ff","target_user_id":"d1ed0000-0000-4000-8000-0000000000d1"}"""),
        )
        assertEquals("A member left Home", rows[0].title)
        assertEquals("Member arrived at a place", rows[1].title)
        // One refetch of the roster for the whole page, not one per row.
        coVerify(exactly = 1) { circleRepository.refreshRoster(CIRCLE) }
    }

    /**
     * A needs-attention row names who asked only as the receivers' rules allow it (an alert
     * under the current key, or one seen current here before), however this device stored the
     * alert; otherwise the row keeps the server's words. One look-up per SOS for the page.
     */
    @Test
    fun `a needs-attention raiser the receivers' rules don't allow is not named`() = runTest {
        val unchecked = "5050a000-0000-4000-8000-00000000505b"
        val rows = refresh(
            row("sos_alert", "Someone needs attention", "A circle member asked their circle to check on them",
                """{"sos_id":"$unchecked","circle_id":"$CIRCLE"}"""),
            // The server's resolution fan-out, word for word.
            row("sos_resolved", "Alert resolved", "A circle member marked the request to check on them resolved",
                """{"sos_id":"$unchecked","circle_id":"$CIRCLE"}"""),
        )

        assertEquals("Someone needs attention", rows[0].title)
        assertEquals("A circle member asked their circle to check on them", rows[0].body)
        assertEquals("A circle member marked the request to check on them resolved", rows[1].body)
        coVerify(exactly = 1) { sosRepository.raiserToName(unchecked, CIRCLE) }
    }

    /**
     * The raiser's bell says who is responding and how many others have, and every member's
     * says who closed the alert. Rows exactly as the server writes them (`acknowledged_by`, one
     * acknowledger per row, only the raiser gets it; `closed_by`), lowercase ids, newest first
     * as the server lists them. Names only from this device's confirmed members, else
     * "Someone".
     */
    @Test
    fun `the bell says who is responding, how many others, and who closed the alert`() = runTest {
        val otherSos = "5050a000-0000-4000-8000-00000000505c"
        val responding = "Someone is responding"
        val acknowledged = "A circle member acknowledged your request to check on you"
        val resolved = "A circle member marked the request to check on them resolved"
        val rows = refresh(
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$BOB"}"""),
            // A member this device doesn't confirm: still counted, never named.
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$GHOST"}"""),
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$ALICE"}"""),
            row("sos_resolved", "Alert resolved", resolved,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","closed_by":"$BOB"}"""),
            row("sos_resolved", "Alert resolved", resolved,
                """{"sos_id":"$otherSos","circle_id":"$CIRCLE","closed_by":"$GHOST"}"""),
            // An older server's rows: no acknowledged_by, no closed_by; the first one about the
            // same request as the rows above, before them.
            row("sos_acknowledged", responding, acknowledged, """{"sos_id":"$otherSos","circle_id":"$CIRCLE"}"""),
            row("sos_resolved", "Alert resolved", resolved, """{"sos_id":"$sosId","circle_id":"$CIRCLE"}"""),
            row("sos_acknowledged", responding, acknowledged, """{"sos_id":"$sosId","circle_id":"$CIRCLE"}"""),
        )

        assertEquals("Bob and 2 others are responding", rows[0].title)
        assertEquals("Bob acknowledged your request to check on you", rows[0].body)
        assertEquals("Someone and 1 other are responding", rows[1].title)
        assertEquals("the server's body for someone unnamed", acknowledged, rows[1].body)
        assertEquals("Alice is responding", rows[2].title)
        assertEquals("Bob closed the alert", rows[3].body)
        assertEquals("Alert resolved", rows[3].title)
        assertEquals("Someone closed the alert", rows[4].body)
        assertEquals(responding to acknowledged, rows[5].title to rows[5].body)
        // No closed_by: whose request it was, by the receivers' rules.
        assertEquals("Alice's request to be checked on was marked resolved", rows[6].body)
        // A row without its own acknowledged_by keeps the server's words, even with named rows after it.
        assertEquals(responding to acknowledged, rows[7].title to rows[7].body)

        detail = SessionManager.NOTIF_DETAIL_GENERIC
        val generic = refresh(
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$BOB"}"""),
            row("sos_resolved", "Alert resolved", resolved, """{"sos_id":"$sosId","circle_id":"$CIRCLE","closed_by":"$BOB"}"""),
        )
        assertEquals(responding to acknowledged, generic[0].title to generic[0].body)
        assertEquals("Alert resolved" to resolved, generic[1].title to generic[1].body)
    }

    /**
     * A row without its own `acknowledged_by` (an older server's, or a server put back for a while)
     * keeps the server's words wherever it falls, and is never counted among the others.
     */
    @Test
    fun `a row without its own responder keeps the server's words and counts for no one`() = runTest {
        val responding = "Someone is responding"
        val acknowledged = "A circle member acknowledged your request to check on you"
        val rows = refresh(
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$BOB"}"""),
            row("sos_acknowledged", responding, acknowledged, """{"sos_id":"$sosId","circle_id":"$CIRCLE"}"""),
            row("sos_acknowledged", responding, acknowledged,
                """{"sos_id":"$sosId","circle_id":"$CIRCLE","acknowledged_by":"$ALICE"}"""),
        )
        assertEquals("Bob and 1 other are responding", rows[0].title)
        assertEquals(responding to acknowledged, rows[1].title to rows[1].body)
        assertEquals("Alice is responding", rows[2].title)
    }

    /**
     * The push path reads a row through NotificationTickleFetcher.SERVER_METADATA_KEYS, an
     * allowlist: a check-in row as the server writes it must keep every key the naming
     * needs — the check-in's id, its place and its sealed sender.
     */
    @Test
    fun `a server-shaped check-in row keeps the naming keys on the push path`() {
        val json = row(
            "check_in", "Check-in", "A circle member has checked in safely.",
            """{"check_in_id":"ci-1","place_id":"$home","encrypted_user_id":"sealed-bob","user_id_nonce":"bm9uY2U="}""",
        )
        val dto = com.google.gson.Gson().fromJson(json, com.pingolin.track.data.remote.dto.NotificationResponse::class.java)

        val data = com.pingolin.track.service.NotificationTickleFetcher.alertDataFor(dto)

        assertEquals("ci-1", data["check_in_id"])
        assertEquals(home, data["place_id"])
        assertEquals("sealed-bob", data["encrypted_user_id"])
        assertEquals("bm9uY2U=", data["user_id_nonce"])
        assertEquals(CIRCLE, data["circle_id"])
    }
}
