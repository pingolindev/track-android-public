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
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.SosAlertResponse
import com.pingolin.track.domain.repository.SosRepository.PushCheck
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.FakeSosAlertDao
import com.pingolin.track.testutil.FakeSosKeys
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.OwedWipe
import com.pingolin.track.util.SecurePrefsSosMarks
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * As on iOS, a notification row names who asked to be checked on only by the receivers' rules —
 * the alert, as served, opens under the circle's CURRENT key, or this device saw that payload
 * current before (the alarm path's own sticky mark) — never from the stored alert, which keeps a
 * raiser opened under ANY key this device holds. Through a real [NotificationNames] and
 * [SosRepository], with a circle key the tests rotate ([FakeSosKeys]) and the real SOS marks on
 * an in-memory file.
 */
class SosRowRaiserTest {

    private companion object {
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val OTHER_CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c2"
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val RAISER = "a11ce000-0000-4000-8000-00000000a11c"
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val NOW = 1_800_000_000_000L
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
    private val memberDao = mockk<CircleMemberDao>()

    /** What GET /sos/active and GET /sos/{id} serve. */
    private var served: SosAlertResponse? = null

    private lateinit var repo: SosRepository
    private lateinit var names: NotificationNames

    @Before
    fun setup() {
        keys.install(crypto)
        every { session.isLoggedIn() } returns true
        every { session.getUserId() } returns ME
        every { session.getAccessToken() } returns "a session"
        every { session.getDeviceId() } returns "dev-me"
        every { circles.sosMarks } returns marks
        every { circles.ownSosSeals } returns com.pingolin.track.testutil.ownSosSeals(ME)
        every { circles.memberShownIn(any(), any()) } returns true
        coEvery { circles.importCircleKeys() } returns Result.success(0)
        coEvery { circles.refreshCircles(any()) } returns Result.success(emptyList())
        coEvery { gate.holdsCurrentVersion(any()) } returns true
        coEvery { gate.holdsCurrentKey(any()) } returns true
        every { wipe.isOwed() } returns false
        coEvery { api.getActiveSosAlerts() } answers { Response.success(ApiResponse(success = true, data = listOfNotNull(served), error = null)) }
        coEvery { api.getSosAlert(any()) } answers {
            served?.let { Response.success(ApiResponse(success = true, data = it, error = null)) }
                ?: Response.error(404, "".toResponseBody(null))
        }
        val circleDao = mockk<CircleDao>()
        coEvery { circleDao.getById(CIRCLE) } returns CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)
        coEvery { memberDao.get(CIRCLE, RAISER) } returns CircleMemberEntity(CIRCLE, RAISER, "Alice", "member", joinedAt = 0, nameOpened = true)
        coEvery { memberDao.getByCircle(CIRCLE) } returns listOf(
            CircleMemberEntity(CIRCLE, RAISER, "Alice", "member", joinedAt = 0, nameOpened = true),
        )
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
            positions = com.pingolin.track.testutil.quietPositions(session),
        ).also {
            it.now = { NOW }
            it.openOn = kotlinx.coroutines.Dispatchers.Unconfined
        }
        names = NotificationNames(
            circleMemberDao = memberDao,
            circleDao = circleDao,
            placeDao = mockk<PlaceDao>(),
            tracksApi = api,
            cryptoManager = crypto,
            sessionManager = session,
            circleRepository = dagger.Lazy { circles },
            sosRepository = dagger.Lazy { repo },
        )
    }

    private fun sos(version: Int = 2, status: String = "active", circleId: String = CIRCLE, idVersion: Int = version) =
        FakeSosKeys.sos(SOS, circleId, RAISER, NOW - 60_000L, version, status, idVersion = idVersion)

    /** Who a needs-attention row and its resolution name, as the list names them. */
    private suspend fun named(): List<String?> = listOf("sos_alert", "sos_resolved").map { type ->
        names.namesFor(type, CIRCLE, mapOf("sos_id" to SOS, "circle_id" to CIRCLE), NotificationNames.Pass()).person
    }

    /** The alarm path sees the alert current here: it alarms, and leaves its sticky mark. */
    private suspend fun seenCurrentByTheAlarm() {
        served = sos(version = 2)
        assertTrue(repo.checkPushed(SOS) is PushCheck.Alarming)
    }

    @Test
    fun `an alert under the circle's current key names who asked`() = runTest {
        served = sos(version = 2)
        assertEquals(listOf("Alice", "Alice"), named())
    }

    @Test
    fun `an ended alert under an older key, never seen current here, names nobody - whatever this device stored`() = runTest {
        // Stored as an ended alert is (SosRepository.store keeps ENDED rows without
        // the rules), with the raiser opened under the older key: naming from this row
        // would name Alice.
        dao.insert(SosAlertEntity(SOS, CIRCLE, RAISER, "resolved", 1.0, 2.0, "", NOW - 60_000L, null))
        for (status in listOf("resolved", "false_alarm")) {
            served = sos(version = 1, status = status)
            assertEquals(status, listOf(null, null), named())
        }
    }

    @Test
    fun `an alert seen current here before keeps its name after the key moves on and it ends`() = runTest {
        seenCurrentByTheAlarm()
        keys.current = 3
        keys.held += 3
        // The same payload, now under an older key than the circle's, served as resolved.
        served = sos(version = 2, status = "resolved")
        assertEquals(listOf("Alice", "Alice"), named())
    }

    @Test
    fun `a forgery under an older key naming a current member names nobody`() = runTest {
        // A removed member's seal, or a server replaying history, served as live: every field
        // under the older key, or only the raiser id.
        for (served in listOf(sos(version = 1), sos(idVersion = 1))) {
            this@SosRowRaiserTest.served = served
            assertEquals(listOf(null, null), named())
        }
    }

    @Test
    fun `marks that don't read name nobody for an alert under an older key, even one seen current before`() = runTest {
        seenCurrentByTheAlarm()
        keys.current = 3
        keys.held += 3
        served = sos(version = 2, status = "resolved")
        prefs.damaged = true
        assertEquals(listOf(null, null), named())

        // The current key needs no mark, as on iOS (`guard opened.current || seenCurrent`).
        served = sos(version = 3)
        assertEquals(listOf("Alice", "Alice"), named())
    }

    @Test
    fun `an alert of another circle, or one the server doesn't serve, names nobody`() = runTest {
        served = sos(version = 2, circleId = OTHER_CIRCLE)
        assertNull(repo.raiserToName(SOS, CIRCLE))
        assertEquals(listOf(null, null), named())

        served = null
        assertEquals(listOf(null, null), named())
    }
}
