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
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.NotificationResponse
import com.pingolin.track.data.remote.dto.NotificationsListResponse
import com.pingolin.track.data.remote.dto.ResealSosRequest
import com.pingolin.track.data.remote.dto.SosAlertResponse
import com.pingolin.track.domain.repository.SosRepository.PushCheck
import com.pingolin.track.service.NotificationTickleFetcher
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.FakeSosAlertDao
import com.pingolin.track.testutil.FakeSosKeys
import com.pingolin.track.testutil.ownSosSeals
import com.pingolin.track.testutil.quietPositions
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.OwedWipe
import com.pingolin.track.util.SecurePrefsSosMarks
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.KeyStoreLoad

/**
 * The receivers' rules for an SOS: a pushed SOS rings only when it's current or sticky, unless this device
 * can't check it at all, and then it rings anyway; whatever pass finds it alarming, it sounds once on this
 * device; one that doesn't open gets one circle refresh before its verdict, so a key this device hasn't
 * heard of never drops it. Through [SosRepository] with its collaborators faked: a circle key the tests
 * rotate ([FakeSosKeys]), the sos_alerts table in memory, and the real SOS marks on an in-memory file.
 */
class SosReceiverRulesTest {

    private companion object {
        /** Hex letters in every id: upper case is another spelling. */
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val RAISER = "a11ce000-0000-4000-8000-00000000a11c"
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val SOS_2 = "0505abcd-0000-4000-8000-00000000abce"
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }

    private val api = mockk<TrackApi>()
    private val dao = FakeSosAlertDao()
    private val session = mockk<SessionManager>(relaxed = true)
    private val crypto = mockk<CryptoManager>()
    private val keys = FakeSosKeys(current = 2, held = mutableSetOf(1, 2))
    private val prefs = DamagedPrefs()
    private val marks = SecurePrefsSosMarks(mockk(), { ME }, { prefs })
    private val circles = mockk<CircleRepository>(relaxed = true)
    private val gate = mockk<CurrentKeyGate>()
    private val wipe = mockk<OwedWipe>()
    private val alerts = mockk<AlertHandler>(relaxed = true)
    private val circleDao = mockk<CircleDao>()
    private val memberDao = mockk<CircleMemberDao>()

    /** What GET /sos/active serves. */
    private var listed: List<SosAlertResponse> = emptyList()

    /** The roster's answer for the raiser: null while this process hasn't judged the list. */
    private var shown: Boolean? = true

    /** Behind by the circle list's version (CurrentKeyGate). */
    private var behindByList = false

    /** What reached AlertHandler through a push's path, in order. */
    private val posted = mutableListOf<Map<String, String>>()

    private lateinit var repo: SosRepository

    @Before
    fun setup() {
        keys.install(crypto)
        every { session.isLoggedIn() } returns true
        every { session.getUserId() } returns ME
        every { session.getAccessToken() } returns "a session"
        every { session.getDeviceId() } returns "dev-me"
        every { circles.sosMarks } returns marks
        every { circles.ownSosSeals } returns ownSosSeals(ME)
        every { circles.memberShownIn(any(), any()) } answers { shown }
        coEvery { circles.importCircleKeys() } returns Result.success(0)
        // A circle refresh that answers and changes nothing, unless a test says
        // otherwise.
        coEvery { circles.refreshCircles(any()) } returns Result.success(emptyList())
        coEvery { gate.holdsCurrentVersion(any()) } answers { !behindByList }
        coEvery { gate.holdsCurrentKey(any()) } answers { !behindByList }
        every { wipe.isOwed() } returns false
        coEvery { api.getActiveSosAlerts() } answers { Response.success(ApiResponse(success = true, data = listed, error = null)) }
        coEvery { api.markNotificationRead(any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        every { alerts.handleAlert(any(), any()) } answers { posted += secondArg<Map<String, String>>().toMap() }
        coEvery { circleDao.getById(CIRCLE) } returns CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)
        coEvery { memberDao.get(CIRCLE, RAISER) } returns
            CircleMemberEntity(CIRCLE, RAISER, "Alice", "member", joinedAt = 0, nameOpened = true)
        repo = SosRepository(
            tracksApi = api,
            sosAlertDao = dao,
            sessionManager = session,
            cryptoManager = crypto,
            circleRepository = dagger.Lazy { circles },
            currentKeyGate = gate,
            owedWipe = wipe,
            alertHandler = dagger.Lazy { alerts },
            circleDao = circleDao,
            circleMemberDao = memberDao,
            positions = quietPositions(session),
        ).also {
            it.now = { NOW }
            it.openOn = kotlinx.coroutines.Dispatchers.Unconfined
        }
    }

    private fun sos(
        sealedAt: Long = NOW - 60_000L,
        version: Int = 2,
        status: String = "active",
        raiser: String = RAISER,
        tag: String = "a",
        idVersion: Int = version,
        timeVersion: Int = version,
        placeVersion: Int = version,
        device: String = "dev-raiser",
    ) = FakeSosKeys.sos(
        SOS, CIRCLE, raiser, sealedAt, version, status, device,
        idVersion = idVersion, timeVersion = timeVersion, placeVersion = placeVersion, tag = tag,
    )

    private suspend fun pushed(vararg served: SosAlertResponse): PushCheck {
        listed = served.toList()
        return repo.checkPushed(SOS)
    }

    // ---- the SOS location field: what it says ----

    private fun marked(state: String, version: Int = 2) =
        sos().copy(encryptedLocation = FakeSosKeys.field(com.pingolin.track.domain.sos.SosPositionMarker.text(state), version))

    @Test
    fun `a raise with no position yet alarms and is listed, with no pin - opened, never hidden as unopened`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(marked(SosAlertEntity.POSITION_PENDING)))
        val row = dao.rows.value.getValue(SOS)
        assertEquals(SosAlertEntity.POSITION_PENDING, row.positionState)
        assertNull(row.latitude)
        assertNull(row.longitude)
        assertEquals(NOW - 60_000L, row.createdAt)

        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(marked(SosAlertEntity.POSITION_UNAVAILABLE)))
        assertEquals(SosAlertEntity.POSITION_UNAVAILABLE, dao.rows.value.getValue(SOS).positionState)
    }

    @Test
    fun `a marker's key version counts toward current, as a position's does - one sealed under an older version is history`() = runTest {
        // The raiser's id and time under the current version, the marker under the one a rotation left behind.
        assertEquals(PushCheck.NotLive, pushed(marked(SosAlertEntity.POSITION_PENDING, version = 1)))
        assertNull(dao.rows.value[SOS])
    }

    @Test
    fun `behind, a marker an OLDER held version opened is history all the same, as a position is - opened, checked, no refresh`() = runTest {
        behindByList = true
        val old = sos(version = 1).copy(encryptedLocation = FakeSosKeys.field(com.pingolin.track.domain.sos.SosPositionMarker.PENDING, 1))
        assertEquals(PushCheck.NotLive, pushed(old))
        coVerify(exactly = 0) { circles.importCircleKeys() }
        coVerify(exactly = 0) { circles.refreshCircles(any()) }
    }

    @Test
    fun `a location field that is neither a position nor a marker doesn't open, and is hidden`() = runTest {
        val odd = sos().copy(encryptedLocation = FakeSosKeys.field("""{"elsewhere":true}""", 2))
        assertEquals(PushCheck.NotLive, pushed(odd))
        assertNull(dao.rows.value[SOS])
    }

    @Test
    fun `a position keeps when it was taken and how close, and a later one replaces both with no second alarm`() = runTest {
        val taken = NOW - 600_000L
        listed = listOf(sos().copy(encryptedLocation = FakeSosKeys.field("37.5,-122.25,$taken,35.0", 2)))
        repo.refreshActiveAlerts()
        val row = dao.rows.value.getValue(SOS)
        assertEquals(taken, row.fixAt)
        assertEquals(35.0, row.accuracy!!, 0.0)
        assertNull(row.positionState)

        // The raiser's phone found it: the same SOS, resealed with a fix after the press.
        listed = listOf(sos().copy(encryptedLocation = FakeSosKeys.field("37.51,-122.26,$NOW,8.0", 2, tag = "b")))
        repo.refreshActiveAlerts()
        val updated = dao.rows.value.getValue(SOS)
        assertEquals(NOW, updated.fixAt)
        assertEquals(8.0, updated.accuracy!!, 0.0)
        assertEquals(37.51, updated.latitude!!, 0.0)
        assertEquals("still asked when it was asked", NOW - 60_000L, updated.createdAt)
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }

        // A marker that replaces a position takes the pin away (no stale pin under "Sending current location").
        listed = listOf(marked(SosAlertEntity.POSITION_UNAVAILABLE))
        repo.refreshActiveAlerts()
        val gone = dao.rows.value.getValue(SOS)
        assertNull(gone.latitude)
        assertNull(gone.fixAt)
        assertEquals(SosAlertEntity.POSITION_UNAVAILABLE, gone.positionState)
    }

    @Test
    fun `an update sealed under a key this device hasn't got yet opens after ONE circle refresh, moves the pin, and rings no more`() = runTest {
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }

        // The raiser's phone rotated the circle's key, then re-sealed its alert with a fix from after
        // the press: this device holds neither the new key nor the new payload yet. sos_refresh.
        refreshBrings(3)
        listed = listOf(sos(version = 3).copy(encryptedLocation = FakeSosKeys.field("37.51,-122.26,${NOW - 10_000L},8.0", 3, tag = "b")))
        repo.refreshActiveAlerts()

        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        val row = dao.rows.value.getValue(SOS)
        assertEquals(37.51, row.latitude!!, 0.0)
        assertEquals(NOW - 10_000L, row.fixAt)
        verify(exactly = 1) { alerts.ringSos(any(), any(), any()) }
    }

    @Test
    fun `a position whose time is 0 has no time - never 1970`() = runTest {
        listed = listOf(sos().copy(encryptedLocation = FakeSosKeys.field("37.5,-122.25,0,null", 2)))
        repo.refreshActiveAlerts()
        assertNull(dao.rows.value.getValue(SOS).fixAt)
    }

    // ---- CURRENT, HISTORY, ENDED ----

    @Test
    fun `a current SOS alarms, named as this device opened the names, and is kept under its sealed time`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        val row = dao.rows.value.getValue(SOS)
        assertEquals("the SEALED time, never the server's day or now", NOW - 60_000L, row.createdAt)
        assertEquals(RAISER, row.userId)
        assertEquals(37.5, row.latitude!!, 0.0)
    }

    @Test
    fun `a name the server gave, or a circle name that doesn't open, is never shown - the generic words instead`() = runTest {
        coEvery { memberDao.get(CIRCLE, RAISER) } returns
            CircleMemberEntity(CIRCLE, RAISER, "Mallory's pick", "member", joinedAt = 0, nameOpened = false)
        coEvery { circleDao.getById(CIRCLE) } returns
            CircleEntity(id = CIRCLE, name = LockedCircle.PLACEHOLDER_NAME, encryptedKey = null, createdAt = 0, updatedAt = 0)
        assertEquals(PushCheck.Alarming(null, null), pushed(sos()))

        // A raiser this device doesn't show has no row: no name either.
        coEvery { memberDao.get(CIRCLE, RAISER) } returns null
        assertEquals(PushCheck.Alarming(null, null), pushed(sos()))
    }

    @Test
    fun `history - an SOS any field of which only an older key opened, never seen current here, is hidden at any age`() = runTest {
        for ((label, served) in listOf(
            "every field" to sos(version = 1),
            "the raiser id" to sos(idVersion = 1),
            "the time" to sos(timeVersion = 1),
            "the position" to sos(placeVersion = 1),
            "every field, 30 hours old" to sos(version = 1, sealedAt = NOW - 30 * HOUR),
        )) {
            assertEquals(label, PushCheck.NotLive, pushed(served))
            assertNull("$label: not listed, not on the map", dao.rows.value[SOS])
        }
        verify(atLeast = 1) { alerts.cancelSos(SOS) }
        verify(exactly = 0) { alerts.handleAlert(any(), any()) }
    }

    @Test
    fun `a row kept from an earlier sighting goes once the rules hide it`() = runTest {
        dao.insert(SosAlertEntity(SOS, CIRCLE, RAISER, "active", 1.0, 2.0, "", NOW - HOUR, null))
        assertEquals(PushCheck.NotLive, pushed(sos(version = 1)))
        assertEquals(listOf(SOS), dao.deleted)
    }

    @Test
    fun `ended - seen resolved or called a false alarm once, it never alarms again, whatever the server lists later`() = runTest {
        for (ending in listOf("resolved", "false_alarm")) {
            prefs.data.clear()
            assertEquals(PushCheck.NotLive, pushed(sos(status = ending)))
            assertEquals("its row keeps the ending", ending, dao.rows.value[SOS]?.status)
            assertEquals("$ending, listed live and current again", PushCheck.NotLive, pushed(sos()))
            assertTrue(marks.ended(CIRCLE.uppercase(), SOS.uppercase()))
        }
    }

    @Test
    fun `ended - an SOS the list drops and the server then calls resolved is ended here for good`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        coEvery { api.getSosAlert(SOS) } returns Response.success(ApiResponse(success = true, data = sos(status = "resolved"), error = null))
        listed = emptyList()
        assertTrue(repo.refreshActiveAlerts().isSuccess)
        assertEquals("resolved", dao.rows.value[SOS]?.status)
        verify { alerts.cancelSos(SOS) }
        assertEquals("listed live again", PushCheck.NotLive, pushed(sos()))
    }

    @Test
    fun `absent from the active list - checked, and not live`() = runTest {
        assertEquals(PushCheck.NotLive, pushed())
    }

    // ---- STICKY, RESEAL, ROSTER ----

    @Test
    fun `sticky - an SOS seen current here alarms on after a rotation, and one never seen current doesn't`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        keys.held += 3
        keys.current = 3
        assertEquals("seen current under v2, v3 is current now", PushCheck.Alarming("Alice", "Family"), pushed(sos()))

        prefs.data.clear()
        assertEquals("never seen current here", PushCheck.NotLive, pushed(sos()))
    }

    @Test
    fun `sticky through a reseal - seen as A, resealed as B, another rotation, B still alarms, and a payload never seen doesn't`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos(tag = "a")))
        keys.held += 3
        keys.current = 3
        assertEquals("B, sealed again under v3", PushCheck.Alarming("Alice", "Family"), pushed(sos(version = 3, tag = "b")))
        keys.held += 4
        keys.current = 4
        assertEquals("B after the next rotation", PushCheck.Alarming("Alice", "Family"), pushed(sos(version = 3, tag = "b")))
        assertEquals("C, never seen current here", PushCheck.NotLive, pushed(sos(version = 3, tag = "c")))
    }

    @Test
    fun `a payload is every sealed field - one whose sealed device id changed was never seen current here`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        keys.held += 3
        keys.current = 3
        val seen = sos()
        assertEquals("the same payload, sticky", PushCheck.Alarming("Alice", "Family"), pushed(seen))
        val swapped = seen.copy(encryptedDeviceId = FakeSosKeys.field("dev-other", 2))
        assertEquals("another device id: another payload", PushCheck.NotLive, pushed(swapped))
    }

    @Test
    fun `the roster - stickiness ends, one way, once the raiser is off this device's complete roster, and no roster is no decision`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        keys.held += 3
        keys.current = 3
        shown = null
        assertEquals("no member list judged in this process: no decision", PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        shown = false
        assertEquals(PushCheck.NotLive, pushed(sos()))
        assertTrue(marks.rosterEnded(CIRCLE, SOS))
        shown = true
        assertEquals("one way", PushCheck.NotLive, pushed(sos()))
    }

    @Test
    fun `the roster only ends stickiness - a current SOS alarms whatever it says`() = runTest {
        shown = false
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
    }

    // ---- AGE ----

    @Test
    fun `older than 24 h by its sealed time - listed quietly, no alarm, and its post goes`() = runTest {
        // The server's day says today: only the sealed time dates it.
        val old = sos(sealedAt = NOW - 25 * HOUR).copy(dayBucket = (NOW / 86_400_000L).toInt())
        assertEquals(PushCheck.NotLive, pushed(old))
        assertEquals("listed", NOW - 25 * HOUR, dao.rows.value[SOS]?.createdAt)
        verify { alerts.cancelSos(SOS) }
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos(sealedAt = NOW - 23 * HOUR)))
    }

    @Test
    fun `a quiet SOS is listed and never pinned on the map`() = runTest {
        assertEquals(PushCheck.NotLive, pushed(sos(sealedAt = NOW - 25 * HOUR)))
        assertEquals(1, dao.observeActive(0L).first().size)
        assertTrue(repo.observeMappableAlerts().first().isEmpty())
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos(sealedAt = NOW - 23 * HOUR)))
        assertEquals(listOf(SOS), repo.observeMappableAlerts().first().map { it.id })
    }

    @Test
    fun `the alarm age is 24 hours`() {
        assertEquals(86_400_000L, com.pingolin.track.util.SosMarks.MAX_SOS_ALARM_AGE_MS)
    }

    @Test
    fun `a time that doesn't open has no age - never alarming`() = runTest {
        val served = sos().copy(encryptedTimestamp = FakeSosKeys.field("not a time", 2))
        assertEquals(PushCheck.NotLive, pushed(served))
    }

    // ---- WHAT THIS DEVICE CAN'T CHECK RINGS ----

    @Test
    fun `marks that don't read - a live SOS within 24 h can't be checked, past it nothing rings, and nothing is written over them`() = runTest {
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos()))
        val stored = prefs.data.toMap()
        prefs.damaged = true
        assertEquals("current", PushCheck.CantCheck, pushed(sos()))
        assertEquals("history: maybe sticky", PushCheck.CantCheck, pushed(sos(version = 1)))
        assertEquals("25 hours old", PushCheck.NotLive, pushed(sos(sealedAt = NOW - 25 * HOUR)))
        assertEquals("resolved", PushCheck.NotLive, pushed(sos(status = "resolved")))
        assertEquals("nothing written over them", stored, prefs.data)
    }

    @Test
    fun `no usable answer in time - every transport failure, status and answer that doesn't decode can't be checked`() = runTest {
        val failures = listOf<Pair<String, () -> Response<ApiResponse<List<SosAlertResponse>>>>>(
            "500" to { Response.error(500, "".toResponseBody(null)) },
            "503" to { Response.error(503, "".toResponseBody(null)) },
            "429" to { Response.error(429, "".toResponseBody(null)) },
            "401 a token refresh didn't clear" to { Response.error(401, "".toResponseBody(null)) },
            "403" to { Response.error(403, "".toResponseBody(null)) },
            "404 on the list" to { Response.error(404, "".toResponseBody(null)) },
            "success false" to { Response.success(ApiResponse(success = false, data = listOf(sos()), error = "no")) },
            "no data" to { Response.success(ApiResponse<List<SosAlertResponse>>(success = true, data = null, error = null)) },
        )
        for ((label, answer) in failures) {
            coEvery { api.getActiveSosAlerts() } answers { answer() }
            assertEquals(label, PushCheck.CantCheck, repo.checkPushed(SOS))
        }
        for (thrown in listOf(java.io.IOException("offline"), java.net.UnknownHostException("dns"),
            javax.net.ssl.SSLHandshakeException("tls"), com.google.gson.JsonSyntaxException("doesn't decode"))) {
            coEvery { api.getActiveSosAlerts() } throws thrown
            assertEquals(thrown.javaClass.simpleName, PushCheck.CantCheck, repo.checkPushed(SOS))
        }
        coEvery { api.getActiveSosAlerts() } coAnswers {
            delay(SosRepository.CHECK_BUDGET_MS + 1_000L)
            Response.success(ApiResponse(success = true, data = listOf(sos(version = 1)), error = null))
        }
        assertEquals("past the budget", PushCheck.CantCheck, repo.checkPushed(SOS))
        verify(exactly = 0) { alerts.handleAlert(any(), any()) }
    }

    @Test
    fun `this device can't run the check - keys that don't load whole, a wipe owed, no session - it rings`() = runTest {
        listed = listOf(sos(version = 1))
        for (load in listOf(KeyStoreLoad.NOT_LOADED, KeyStoreLoad.NOTHING_STORED, KeyStoreLoad.READ_FAILED, KeyStoreLoad.DOES_NOT_DECODE, KeyStoreLoad.DELETE_FAILED)) {
            keys.load = load
            assertEquals(load.name, PushCheck.CantCheck, repo.checkPushed(SOS))
        }
        keys.load = KeyStoreLoad.LOADED
        keys.entriesUnreadable = true
        assertEquals("entries that didn't read", PushCheck.CantCheck, repo.checkPushed(SOS))
        keys.entriesUnreadable = false
        every { wipe.isOwed() } returns true
        assertEquals("a wipe owed", PushCheck.CantCheck, repo.checkPushed(SOS))
        every { wipe.isOwed() } returns false
        every { session.getAccessToken() } returns null
        assertEquals("no session", PushCheck.CantCheck, repo.checkPushed(SOS))
        every { session.getAccessToken() } returns "a session"
        every { session.getUserId() } returns null
        assertEquals("nobody signed in", PushCheck.CantCheck, repo.checkPushed(SOS))
        every { session.getUserId() } returns ME
        assertEquals("the control: checked", PushCheck.NotLive, repo.checkPushed(SOS))
        coVerify(exactly = 1) { api.getActiveSosAlerts() }
    }

    @Test
    fun `a push naming no SOS, or no UUID, can't be checked`() = runTest {
        listed = listOf(sos())
        assertEquals(PushCheck.CantCheck, repo.checkPushed(null))
        assertEquals(PushCheck.CantCheck, repo.checkPushed(""))
        assertEquals(PushCheck.CantCheck, repo.checkPushed("sos-1"))
        assertEquals("any spelling of a UUID", PushCheck.Alarming("Alice", "Family"), repo.checkPushed(SOS.uppercase()))
    }

    // ---- BEHIND ON THE CIRCLE'S KEY ----

    @Test
    fun `behind - an SOS under a version this device doesn't hold yet gets one key refresh, and rings if it stays behind`() = runTest {
        behindByList = true
        assertEquals(PushCheck.CantCheck, pushed(sos(version = 3)))
        coVerify(exactly = 1) { circles.importCircleKeys() }

        coEvery { circles.importCircleKeys() } answers {
            keys.held += 3
            keys.current = 3
            behindByList = false
            Result.success(1)
        }
        assertEquals("the refresh brought v3: checked, and current", PushCheck.Alarming("Alice", "Family"), pushed(sos(version = 3)))
    }

    @Test
    fun `behind by the SDK's own records counts too`() = runTest {
        keys.behind = true
        assertEquals(PushCheck.CantCheck, pushed(sos(version = 3)))
    }

    @Test
    fun `not behind - fields that don't open under a version held here are checked and refused after one circle refresh`() = runTest {
        val tampered = sos().copy(encryptedUserId = com.pingolin.track.data.remote.dto.EncryptedPayload("n", "garbage"))
        assertEquals(PushCheck.NotLive, pushed(tampered))
        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        coVerify(exactly = 0) { circles.importCircleKeys() }
    }

    @Test
    fun `behind, but an SOS an OLDER held version opened is history all the same - checked, no refresh`() = runTest {
        behindByList = true
        assertEquals(PushCheck.NotLive, pushed(sos(version = 1)))
        coVerify(exactly = 0) { circles.importCircleKeys() }
    }

    // ---- A KEY THIS DEVICE HASN'T HEARD OF ----
    // Another member rotated, nothing told this device, and an SOS sealed under the new key doesn't open
    // here.

    /** The circle refresh brings [version]'s key: the listed version moved, and the import took it. */
    private fun refreshBrings(version: Int) {
        coEvery { circles.refreshCircles(any()) } answers {
            keys.held += version
            keys.current = version
            Result.success(emptyList())
        }
    }

    /** The circle refresh lists a newer version whose key isn't here yet: behind. */
    private fun refreshListsNewerOnly() {
        coEvery { circles.refreshCircles(any()) } answers {
            behindByList = true
            Result.success(emptyList())
        }
    }

    @Test
    fun `an SOS under a key this device hadn't heard of gets one circle refresh, and alarms by name once the key arrives`() = runTest {
        refreshBrings(3)
        assertEquals(PushCheck.Alarming("Alice", "Family"), pushed(sos(version = 3)))
        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        coVerify(exactly = 0) { circles.importCircleKeys() }
        assertNotNull("kept", dao.rows.value[SOS])
    }

    @Test
    fun `an unknown key - the refresh lists the newer version but its key isn't here - can't check, the generic alarm`() = runTest {
        refreshListsNewerOnly()
        assertEquals(PushCheck.CantCheck, pushed(sos(version = 3)))
        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        coVerify(exactly = 0) { circles.importCircleKeys() }
    }

    @Test
    fun `an unknown key - a circle refresh that fails or throws leaves it can't-check, never hidden`() = runTest {
        coEvery { circles.refreshCircles(any()) } returns Result.failure(java.io.IOException("offline"))
        assertEquals(PushCheck.CantCheck, pushed(sos(version = 3)))
        repo.now = { NOW + 61_000L }
        coEvery { circles.refreshCircles(any()) } throws java.io.IOException("reset")
        assertEquals(PushCheck.CantCheck, pushed(sos(version = 3)))
        coVerify(exactly = 2) { circles.refreshCircles(any()) }
    }

    @Test
    fun `never nothing - a real SOS under a key this device hadn't heard of is never dropped - named, or at worst the generic alarm`() = runTest {
        val cases = listOf<Pair<String, () -> Unit>>(
            "the key arrives" to { refreshBrings(3) },
            "the newer version listed, its key not here" to { refreshListsNewerOnly() },
            "the refresh fails" to { coEvery { circles.refreshCircles(any()) } returns Result.failure(java.io.IOException("offline")) },
        )
        for ((i, case) in cases.withIndex()) {
            val (what, arrange) = case
            keys.current = 2
            keys.held.retainAll(setOf(1, 2))
            behindByList = false
            prefs.data.clear()
            val at = NOW + i * 120_000L
            repo.now = { at }
            arrange()
            posted.clear()
            listed = listOf(sos(version = 3, sealedAt = at - 60_000L))
            pushArrives()
            assertEquals("$what: one post", 1, posted.size)
            assertNotEquals("$what: never dropped", AlertHandler.SOS_CHECK_NOT_LIVE, posted.single()[AlertHandler.KEY_SOS_CHECK])
        }
        verify(exactly = 0) { alerts.cancelSos(any()) }
    }

    @Test
    fun `an unknown key's throttle - one circle refresh a minute per SOS, its answer standing in between, and one per pass for many`() = runTest {
        // A row that never opens: refreshed once and hidden; within the minute, a pass and a push refresh nothing more.
        val garbage = sos().copy(encryptedUserId = com.pingolin.track.data.remote.dto.EncryptedPayload("n", "garbage"))
        assertEquals(PushCheck.NotLive, pushed(garbage))
        repo.refreshActiveAlerts()
        assertEquals(PushCheck.NotLive, pushed(garbage))
        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        // A failed refresh's answer stands too: can't check within the minute, with no new refresh.
        repo.now = { NOW + 61_000L }
        coEvery { circles.refreshCircles(any()) } returns Result.failure(java.io.IOException("offline"))
        assertEquals(PushCheck.CantCheck, pushed(garbage))
        assertEquals(PushCheck.CantCheck, pushed(garbage))
        coVerify(exactly = 2) { circles.refreshCircles(any()) }
        // Past the minute: refreshed again.
        repo.now = { NOW + 122_000L }
        assertEquals(PushCheck.CantCheck, pushed(garbage))
        coVerify(exactly = 3) { circles.refreshCircles(any()) }
        // Two SOS that don't open, in one pass: one refresh.
        repo.now = { NOW + 200_000L }
        listed = listOf(sos(version = 3), FakeSosKeys.sos(SOS_2, CIRCLE, RAISER, NOW - 60_000L, version = 3, tag = "other"))
        repo.refreshActiveAlerts()
        coVerify(exactly = 4) { circles.refreshCircles(any()) }
    }

    @Test
    fun `an unknown key in the foreground - a pass whose SOS doesn't open refreshes the circles once, and rings it by name when the key arrives`() = runTest {
        refreshBrings(3)
        listed = listOf(sos(version = 3))
        repo.refreshActiveAlerts()
        coVerify(exactly = 1) { circles.refreshCircles(any()) }
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }
        assertNotNull("kept", dao.rows.value[SOS])
    }

    @Test
    fun `an unknown key's store order - a pass that refreshes for an SOS doesn't take its earlier post down first`() = runTest {
        refreshListsNewerOnly()
        listed = listOf(sos(version = 3))
        pushArrives()
        assertNull("the generic alarm", posted.single()[AlertHandler.KEY_SOS_CHECK])
        // A minute on, a pass with the stale list again: hidden at first sight, until its refresh answers.
        behindByList = false
        repo.now = { NOW + 61_000L }
        repo.refreshActiveAlerts()
        coVerify(exactly = 2) { circles.refreshCircles(any()) }
        verify(exactly = 0) { alerts.cancelSos(any()) }
    }

    @Test
    fun `an unknown key and keysMoved - an SOS a pass left waiting on a key is judged again as soon as the key arrives, once`() = runTest {
        // The pass runs in the background, as in the app: runCurrent runs it (advanceUntilIdle leaves background work).
        repo.passScope = backgroundScope
        refreshListsNewerOnly()
        listed = listOf(sos(version = 3))
        repo.refreshActiveAlerts()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }
        // The key arrives another way (a delivery, the map's import): the import says which circles moved.
        keys.held += 3
        keys.current = 3
        behindByList = false
        repo.keysMoved(setOf("0ff1ce00-0000-4000-8000-0000000000c2"))
        testScheduler.runCurrent()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }
        repo.keysMoved(setOf(CIRCLE.uppercase()))
        testScheduler.runCurrent()
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }
        // Nothing waits any more: no pass.
        repo.keysMoved(setOf(CIRCLE))
        testScheduler.runCurrent()
        verify(exactly = 1) { alerts.ringSos(any(), any(), any()) }
        coVerify(exactly = 2) { api.getActiveSosAlerts() }
    }

    // ---- A REFRESH LISTS; MUTATIONS ----

    @Test
    fun `a refresh lists what the rules keep, and takes down posts of what isn't live`() = runTest {
        listed = listOf(sos())
        assertEquals(1, repo.refreshActiveAlerts().getOrThrow().size)
        listed = listOf(sos(sealedAt = NOW - 25 * HOUR))
        repo.refreshActiveAlerts()
        verify { alerts.cancelSos(SOS) }
        verify(exactly = 0) { alerts.handleAlert(any(), any()) }
    }

    // ---- ONE RING PER SOS ON THIS DEVICE ----

    private var rows = 0

    /** The push for [SOS] reaching this device: its stored row fetched and shown, as the tickle does. */
    private suspend fun pushArrives(circle: String = CIRCLE) {
        val row = NotificationResponse(
            id = "sos-row-${++rows}-${System.nanoTime()}",
            circleId = circle,
            notificationType = AlertHandler.TYPE_SOS_ALERT,
            title = "Someone needs attention",
            body = "A circle member asked their circle to check on them",
            metadata = mapOf("sos_id" to SOS),
        )
        coEvery { api.getNotifications(any(), any(), any()) } returns
            Response.success(ApiResponse(success = true, data = NotificationsListResponse(notifications = listOf(row)), error = null))
        assertTrue(NotificationTickleFetcher(session, alerts, api, mockk(), repo, crypto, mockk(), mockk()).fetchAndShowNotifications())
    }

    @Test
    fun `one ring - the push judged not live, the raiser's reseal, then sos_refresh - exactly one ring, and none after`() = runTest {
        // The push reaches this phone just after a key change: the SOS opened under v2, which it
        // never saw current, and the phone seals under v3. History: nothing shown.
        keys.held += 3
        keys.current = 3
        listed = listOf(sos(version = 2))
        pushArrives()
        assertEquals(AlertHandler.SOS_CHECK_NOT_LIVE, posted.single()[AlertHandler.KEY_SOS_CHECK])
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }

        // The raiser seals it again under v3, and the server's quiet sos_refresh runs a refresh.
        listed = listOf(sos(version = 3, tag = "resealed"))
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }

        repo.refreshActiveAlerts()
        // Sealed again (another payload): the same SOS, which rang.
        listed = listOf(sos(version = 3, tag = "resealed-again"))
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(any(), any(), any()) }
        assertTrue(marks.rang(CIRCLE, SOS))
    }

    @Test
    fun `one ring - a push after a refresh rang is posted with no sound`() = runTest {
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }
        pushArrives()
        val again = posted.single()
        assertEquals(AlertHandler.SOS_CHECK_ALARMING, again[AlertHandler.KEY_SOS_CHECK])
        assertEquals("rang here already", "1", again[AlertHandler.KEY_SOS_SILENT])
    }

    @Test
    fun `one ring - a push that rings first has its sound, and nothing rings it again`() = runTest {
        listed = listOf(sos())
        pushArrives()
        assertEquals(AlertHandler.SOS_CHECK_ALARMING, posted.single()[AlertHandler.KEY_SOS_CHECK])
        assertNull("its sound", posted.single()[AlertHandler.KEY_SOS_SILENT])
        assertTrue(marks.rang(CIRCLE.uppercase(), SOS))
        repo.refreshActiveAlerts()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }
        pushArrives()
        assertEquals("a second push", "1", posted.last()[AlertHandler.KEY_SOS_SILENT])
    }

    @Test
    fun `one ring - sticky after a rotation, it rang once and doesn't ring again`() = runTest {
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        keys.held += 3
        keys.current = 3
        repo.refreshActiveAlerts()
        assertEquals("still alarming: sticky", PushCheck.Alarming("Alice", "Family"), repo.checkPushed(SOS))
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(any(), any(), any()) }
    }

    @Test
    fun `one ring - a refresh never rings what is quiet, hidden, not live, or can't be checked`() = runTest {
        for (served in listOf(
            sos(sealedAt = NOW - 25 * HOUR), // quiet
            sos(version = 1), // history: hidden
            sos(status = "resolved"), // ended: hidden
            sos(status = "acknowledged"), // not live
        )) {
            prefs.data.clear()
            listed = listOf(served)
            repo.refreshActiveAlerts()
        }
        prefs.data.clear()
        behindByList = true
        listed = listOf(sos(version = 3))
        repo.refreshActiveAlerts()
        behindByList = false
        prefs.damaged = true
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }

        // The control: current, and the marks read.
        prefs.damaged = false
        repo.refreshActiveAlerts()
        verify(exactly = 1) { alerts.ringSos(SOS, "Alice", "Family") }
    }

    @Test
    fun `one ring - this account's own SOS never rings here, in any spelling`() = runTest {
        listed = listOf(sos(raiser = ME.uppercase()))
        repo.refreshActiveAlerts()
        listed = listOf(sos(raiser = ME))
        repo.refreshActiveAlerts()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }
        assertNotNull("listed all the same", dao.rows.value[SOS])
    }

    @Test
    fun `one ring - a push check's pass rings the other SOS it judges alarming, and leaves the pushed one to its push`() = runTest {
        val other = FakeSosKeys.sos(SOS_2, CIRCLE, RAISER, NOW - 60_000L, tag = "other")
        listed = listOf(sos(), other)
        assertEquals(PushCheck.Alarming("Alice", "Family"), repo.checkPushed(SOS.uppercase()))
        verify(exactly = 1) { alerts.ringSos(SOS_2, "Alice", "Family") }
        verify(exactly = 0) { alerts.ringSos(SOS, any(), any()) }
        assertFalse("the pushed one rings from its push", marks.rang(CIRCLE, SOS))
    }

    @Test
    fun `one ring - a post that fails leaves no mark, and the next pass rings it`() = runTest {
        var failures = 1
        every { alerts.ringSos(any(), any(), any()) } answers { if (failures-- > 0) throw IllegalStateException("not posted") }
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        assertFalse(marks.rang(CIRCLE, SOS))
        repo.refreshActiveAlerts()
        assertTrue(marks.rang(CIRCLE, SOS))
        repo.refreshActiveAlerts()
        verify(exactly = 2) { alerts.ringSos(SOS, "Alice", "Family") }
    }

    @Test
    fun `one ring - a push that can't check rings and notes it - no refresh rings it again, and a later push has no sound`() = runTest {
        coEvery { api.getActiveSosAlerts() } throws java.io.IOException("offline")
        pushArrives()
        assertNull("can't check: no outcome", posted.single()[AlertHandler.KEY_SOS_CHECK])
        assertNull("its sound", posted.single()[AlertHandler.KEY_SOS_SILENT])
        assertTrue(marks.rang(CIRCLE, SOS))

        coEvery { api.getActiveSosAlerts() } answers { Response.success(ApiResponse(success = true, data = listed, error = null)) }
        listed = listOf(sos())
        repo.refreshActiveAlerts()
        verify(exactly = 0) { alerts.ringSos(any(), any(), any()) }

        coEvery { api.getActiveSosAlerts() } throws java.io.IOException("offline")
        pushArrives()
        assertNull(posted.last()[AlertHandler.KEY_SOS_CHECK])
        assertEquals("rang here already", "1", posted.last()[AlertHandler.KEY_SOS_SILENT])
    }

    @Test
    fun `one ring - a push whose row ids aren't UUIDs rings with its sound each time, and notes nothing`() = runTest {
        coEvery { api.getActiveSosAlerts() } throws java.io.IOException("offline")
        pushArrives(circle = "circle-1")
        pushArrives(circle = "circle-1")
        assertEquals(2, posted.size)
        assertTrue("its sound each time", posted.all { it[AlertHandler.KEY_SOS_SILENT] == null })
        assertFalse(marks.rang(CIRCLE, SOS))
    }

    @Test
    fun `a mutation's answer is judged too - an SOS hidden here isn't kept for a button, and the call still succeeds`() = runTest {
        coEvery { api.dismissSos(SOS) } returns Response.success(ApiResponse(success = true, data = sos(version = 1), error = null))
        assertTrue(repo.dismissSos(SOS).isSuccess)
        assertNull(dao.rows.value[SOS])

        coEvery { api.resolveSos(SOS) } returns Response.success(ApiResponse(success = true, data = sos(status = "resolved"), error = null))
        assertTrue(repo.resolveSos(SOS).isSuccess)
        assertTrue("an ending noted", marks.ended(CIRCLE, SOS))
    }

    // ---- THE RAISER'S RESEAL ----

    private fun ownRow(sealedAt: Long) = SosAlertEntity(SOS, CIRCLE, ME, "active", 37.5, -122.25, "", sealedAt, null)

    private fun mine(sealedAt: Long, version: Int = 2, device: String = "dev-me", status: String = "active", raiser: String = ME) =
        sos(sealedAt = sealedAt, version = version, raiser = raiser, device = device, status = status)

    @Test
    fun `the raiser's own open SOS is sealed again under the new key with its ORIGINAL time, never now`() = runTest {
        val raised = NOW - 2 * HOUR
        dao.insert(ownRow(raised))
        coEvery { api.getSosAlert(SOS) } returns Response.success(ApiResponse(success = true, data = mine(raised), error = null))
        val sent = slot<ResealSosRequest>()
        coEvery { api.resealSos(SOS, capture(sent)) } answers {
            val r = sent.captured
            Response.success(ApiResponse(success = true, data = mine(raised, version = 3).copy(
                encryptedUserId = r.encryptedUserId, encryptedTimestamp = r.encryptedTimestamp,
                encryptedLocation = r.encryptedLocation, encryptedDeviceId = r.encryptedDeviceId,
            ), error = null))
        }
        keys.held += 3
        keys.current = 3

        repo.resealOwn()

        assertTrue(sent.isCaptured)
        assertEquals("the original time", "$raised@3", sent.captured.encryptedTimestamp.ciphertext)
        assertEquals("$ME@3", sent.captured.encryptedUserId.ciphertext)
        assertEquals("dev-me@3", sent.captured.encryptedDeviceId.ciphertext)
        assertEquals("37.5,-122.25@3", sent.captured.encryptedLocation.ciphertext)
        assertNull("no device state, none sent", sent.captured.encryptedDeviceState)
        assertFalse("never now", keys.sealedTexts.any { it == ("timestamp" to NOW.toString()) })
        assertEquals("kept, still dated by its raise", raised, dao.rows.value[SOS]?.createdAt)
    }

    @Test
    fun `a reseal goes only for this account's own open SOS, raised on this device, behind the key, and not past ten an hour`() = runTest {
        val raised = NOW - HOUR
        dao.insert(ownRow(raised))
        coEvery { api.resealSos(SOS, any()) } returns Response.error(404, "".toResponseBody(null))
        keys.held += 3
        keys.current = 3
        for ((_, served) in listOf(
            "raised on another device" to mine(raised, device = "dev-other"),
            "someone else's" to mine(raised, raiser = RAISER),
            "current already" to mine(raised, version = 3),
            "resolved" to mine(raised, status = "resolved"),
        )) {
            coEvery { api.getSosAlert(SOS) } returns Response.success(ApiResponse(success = true, data = served, error = null))
            repo.resealOwn()
            // [label]: nothing sent.
            coVerify(exactly = 0) { api.resealSos(any(), any()) }
        }
        coEvery { api.getSosAlert(SOS) } returns Response.error(404, "".toResponseBody(null))
        repo.resealOwn()
        coVerify(exactly = 0) { api.resealSos(any(), any()) }

        coEvery { api.getSosAlert(SOS) } returns Response.success(ApiResponse(success = true, data = mine(raised), error = null))
        behindByList = true
        repo.resealOwn()
        coVerify(exactly = 0) { api.resealSos(any(), any()) }
        behindByList = false

        repeat(12) { repo.resealOwn() }
        coVerify(exactly = SosRepository.MAX_RESEALS_PER_HOUR) { api.resealSos(SOS, any()) }
        assertNotNull("a 404 ends it quietly", dao.rows.value[SOS])
    }

    @Test
    fun `a resealed SOS older than 24 h by its original time doesn't alarm`() = runTest {
        keys.held += 3
        keys.current = 3
        assertEquals(PushCheck.NotLive, pushed(sos(sealedAt = NOW - 25 * HOUR, version = 3, tag = "resealed")))
    }
}
