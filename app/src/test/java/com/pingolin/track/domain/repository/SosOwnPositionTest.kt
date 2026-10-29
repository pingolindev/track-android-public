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
import com.pingolin.track.data.remote.dto.ResealSosRequest
import com.pingolin.track.data.remote.dto.SosAlertResponse
import com.pingolin.track.data.remote.dto.TriggerSosRequest
import com.pingolin.track.domain.repository.SosRepository.OwnRunResult
import com.pingolin.track.domain.sos.OwnSosSeal
import com.pingolin.track.domain.sos.SosPosition
import com.pingolin.track.domain.sos.SosPositionMarker
import com.pingolin.track.domain.sos.SosSeal
import com.pingolin.track.domain.sos.SosUpdateRule
import com.pingolin.track.location.NewestOwnFix
import com.pingolin.track.testutil.FakeSosAlertDao
import com.pingolin.track.testutil.FakeSosKeys
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.testutil.ownSosSeals
import com.pingolin.track.testutil.quietPositions
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.OwedWipe
import com.pingolin.track.util.SecurePrefsSosMarks
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * The raising phone's side of the SOS position rules, through [SosRepository] against a fake
 * server that keeps what POST /sos and PUT /sos/{id}/reseal sent: the raise's sealed position, the
 * one serialized run that re-seals it, with its key read, rotation defer and bounds, and the
 * record of what this phone sealed. And, end to end, what another member sees of it.
 */
class SosOwnPositionTest {

    private companion object {
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"

        /** Another of my circles, where this phone checked in: its "user_id", "device_id" and "timestamp" open there. */
        const val CIRCLE_B = "b0b0b0b0-0000-4000-8000-0000000000b2"
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val OTHER = "a11ce000-0000-4000-8000-00000000a11c"
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val PRESS = 1_800_000_000_000L
        const val RAISED = PRESS + 5_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }

    private var now = RAISED

    /** The elapsed clock fixes are dated on (NewestOwnFix's, and the chooser's). */
    private var elapsedNow = 5_000_000_000_000L

    // The fake server: one alert, as POST /sos made it and every PUT /reseal since.
    private var served: SosAlertResponse? = null
    private val resealed = mutableListOf<ResealSosRequest>()

    /** In order: "keys" for each circle/key read, "put" for each re-seal sent. */
    private val order = mutableListOf<String>()
    private var resealAnswer: suspend (ResealSosRequest) -> Response<ApiResponse<SosAlertResponse>> = { body -> landed(body) }

    private val api = mockk<TrackApi>()
    private val keys = FakeSosKeys(current = 2, held = mutableSetOf(1, 2))
    private val crypto = mockk<CryptoManager>()
    private val sealPrefs = InMemoryPrefs()
    private var rotationNeeded = false
    private var holdsKey = true
    private var cause = false

    private val session = mockk<SessionManager>(relaxed = true)
    private val newest = NewestOwnFix(session)
    private val alerts = mockk<AlertHandler>(relaxed = true)

    private lateinit var repo: SosRepository
    private lateinit var dao: FakeSosAlertDao

    private fun ok(dto: SosAlertResponse?) = Response.success(ApiResponse(success = true, data = dto, error = null))

    /** A re-seal the server takes: the ten sealed columns swapped. */
    private fun landed(body: ResealSosRequest): Response<ApiResponse<SosAlertResponse>> {
        served = served!!.copy(
            encryptedLocation = body.encryptedLocation,
            encryptedDeviceState = body.encryptedDeviceState,
            encryptedUserId = body.encryptedUserId,
            encryptedDeviceId = body.encryptedDeviceId,
            encryptedTimestamp = body.encryptedTimestamp,
        )
        return ok(served)
    }

    /** A phone of [user]'s, over the shared server and circle key; [seals] its record store. */
    private fun phone(user: String, device: String, seals: InMemoryPrefs = sealPrefs, dao: com.pingolin.track.data.local.dao.SosAlertDao = FakeSosAlertDao()): SosRepository {
        val session = if (user == ME) this.session else mockk<SessionManager>(relaxed = true)
        every { session.isLoggedIn() } returns true
        every { session.getUserId() } returns user
        every { session.getAccessToken() } returns "a session"
        every { session.getDeviceId() } returns device
        val circles = mockk<CircleRepository>(relaxed = true)
        every { circles.sosMarks } returns SecurePrefsSosMarks(mockk(), { user }, { InMemoryPrefs() })
        every { circles.ownSosSeals } returns ownSosSeals(user, seals)
        every { circles.memberShownIn(any(), any()) } returns true
        coEvery { circles.refreshCircles(any()) } answers {
            order += "keys"
            Result.success(emptyList())
        }
        val gate = mockk<CurrentKeyGate>()
        coEvery { gate.holdsCurrentKey(any()) } answers { holdsKey }
        coEvery { gate.holdsCurrentVersion(any()) } returns true
        val wipe = mockk<OwedWipe> { every { isOwed() } returns false }
        val circleDao = mockk<CircleDao>()
        coEvery { circleDao.getById(CIRCLE) } answers {
            CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0, keyRotationNeeded = rotationNeeded)
        }
        val memberDao = mockk<CircleMemberDao>()
        coEvery { memberDao.get(CIRCLE, any()) } returns CircleMemberEntity(CIRCLE, ME, "Sam", "member", joinedAt = 0, nameOpened = true)
        return SosRepository(
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
            positions = quietPositions(session, newest = if (user == ME) newest else NewestOwnFix(session), cause = { cause }).also {
                it.wallNow = { now }
                it.elapsedNanos = { elapsedNow }
            },
        ).also {
            it.now = { now }
            it.openOn = kotlinx.coroutines.Dispatchers.Unconfined
        }
    }

    @Before
    fun setup() {
        keys.install(crypto)
        coEvery { api.triggerSos(any()) } answers {
            val r = firstArg<TriggerSosRequest>()
            served = SosAlertResponse(
                id = SOS, circleId = r.circleId, encryptedLocation = r.encryptedLocation,
                encryptedDeviceState = r.encryptedDeviceState, status = "active", encryptedUserId = r.encryptedUserId,
                encryptedDeviceId = r.encryptedDeviceId, dayBucket = r.dayBucket, encryptedTimestamp = r.encryptedTimestamp,
                resolvedDayBucket = null,
            )
            ok(served)
        }
        coEvery { api.getSosAlert(SOS) } answers { served?.let { ok(it) } ?: Response.error(404, "".toResponseBody(null)) }
        coEvery { api.getActiveSosAlerts() } answers { Response.success(ApiResponse(success = true, data = listOfNotNull(served), error = null)) }
        coEvery { api.resealSos(SOS, any()) } coAnswers {
            order += "put"
            resealed += secondArg<ResealSosRequest>()
            resealAnswer(secondArg())
        }
        coEvery { api.markNotificationRead(any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        dao = FakeSosAlertDao()
        newest.elapsedNanos = { elapsedNow }
        repo = phone(ME, "dev-me", dao = dao)
    }

    private suspend fun raise(position: SosSeal): SosAlertEntity {
        now = RAISED
        val alert = repo.triggerSos(CIRCLE, position, pressAt = PRESS, raisedAt = RAISED).getOrThrow()
        order.clear()
        return alert
    }

    private fun lastSealedPosition() = keys.sealedPositions.last()

    private fun rotate() {
        keys.held += 3
        keys.current = 3
    }

    // ---- the raise ----

    @Test
    fun `a raise the server took is sent, though its local record can't be written`() = runTest {
        // A database that can't be opened: the send never needed it, and the circle has the alert.
        val unopenable = object : com.pingolin.track.data.local.dao.SosAlertDao by FakeSosAlertDao() {
            override suspend fun insert(alert: SosAlertEntity) =
                throw com.pingolin.track.util.DatabaseKeyRefusedException(java.security.KeyStoreException("busy"))
        }
        now = RAISED
        val sent = phone(ME, "dev-me", dao = unopenable).triggerSos(CIRCLE, SosSeal.PENDING, pressAt = PRESS, raisedAt = RAISED)
        assertTrue("sent, never \"not sent\": ${sent.exceptionOrNull()}", sent.isSuccess)
        assertEquals(SOS, sent.getOrThrow().id)
        io.mockk.coVerify(exactly = 1) { api.triggerSos(any()) }
    }

    @Test
    fun `a raise that went late seals when it was asked - its record, its row and its day too`() = runTest {
        // Asked at RAISED; the phone found a signal 45 minutes later.
        now = RAISED + 45 * MIN
        val alert = repo.triggerSos(CIRCLE, SosSeal.PENDING, pressAt = PRESS, raisedAt = RAISED).getOrThrow()

        assertTrue("asked at: when it was asked", keys.sealedTexts.contains("timestamp" to RAISED.toString()))
        assertFalse("never the send", keys.sealedTexts.contains("timestamp" to (RAISED + 45 * MIN).toString()))
        assertEquals(RAISED, alert.createdAt)
        assertEquals(RAISED, ownSosSeals(ME, sealPrefs).get(SOS)!!.raisedAt)
        assertEquals((RAISED / 86_400_000L).toInt(), served!!.dayBucket)
    }

    @Test
    fun `the raise seals the position with when it was TAKEN and its accuracy - the raise time stays the sealed timestamp`() = runTest {
        val held = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        val alert = raise(held)

        val sealed = lastSealedPosition()
        assertEquals("taken, never the raise time", PRESS - 10 * MIN, sealed.timestamp)
        assertEquals(6.0, sealed.accuracy!!, 0.0)
        assertNull("every other optional field nil", sealed.altitude ?: sealed.speed ?: sealed.heading ?: sealed.batteryLevel ?: sealed.isCharging)
        assertTrue("asked at: the raise", keys.sealedTexts.contains("timestamp" to RAISED.toString()))

        assertEquals(RAISED, alert.createdAt)
        assertEquals(PRESS - 10 * MIN, alert.fixAt)
        assertEquals(6.0, alert.accuracy!!, 0.0)
        assertEquals(SosPosition.LastKnown(PRESS - 10 * MIN), SosPosition.of(alert, now))
        assertEquals("the record", OwnSosSeal(CIRCLE, RAISED, held), ownSosSeals(ME, sealPrefs).get(SOS))
    }

    @Test
    fun `a raise with no position goes - the marker sealed as the circle's location text, and kept as sent`() = runTest {
        val alert = raise(SosSeal.PENDING)
        assertTrue(keys.sealedTexts.contains("location" to SosPositionMarker.PENDING))
        assertTrue("no stand-in coordinate", keys.sealedPositions.isEmpty())
        assertEquals(SosAlertEntity.POSITION_PENDING, alert.positionState)
        assertNull(alert.latitude)
        assertEquals(SosSeal.PENDING, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)
    }

    // ---- the update rule through one run ----

    @Test
    fun `a fix after the press replaces the marker - keys read first, the ORIGINAL raise time, the record follows`() = runTest {
        raise(SosSeal.PENDING)
        now = PRESS + 40_000
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)

        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, f))
        assertEquals("the key read comes before the seal", listOf("keys", "put"), order)
        val sealed = lastSealedPosition()
        assertEquals(PRESS + 30_000, sealed.timestamp)
        assertEquals(9.0, sealed.accuracy!!, 0.0)
        assertEquals("$RAISED@2", resealed.single().encryptedTimestamp.ciphertext)
        assertEquals(f, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)
        val row = dao.rows.value.getValue(SOS)
        assertEquals(PRESS + 30_000, row.fixAt)
        assertNull(row.positionState)

        // Nothing more: it is good, and taken after the press.
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS, f.copy(takenAt = PRESS + 60_000)))
        assertEquals(1, resealed.size)
    }

    @Test
    fun `a run that just read the keys reads none - nor does one with nothing to seal`() = runTest {
        raise(SosSeal.PENDING)
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        assertEquals(emptyList<String>(), order)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0), keysRead = true))
        assertEquals(listOf("put"), order)
    }

    @Test
    fun `without the current key nothing is sealed`() = runTest {
        raise(SosSeal.PENDING)
        holdsKey = false
        now = PRESS + 40_000
        assertEquals(OwnRunResult.HELD, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)))
        assertTrue(resealed.isEmpty())
    }

    @Test
    fun `a definite cause turns pending into unavail, and pending at the window's end does too`() = runTest {
        raise(SosSeal.PENDING)
        now = PRESS + MIN
        cause = true
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS))
        assertEquals(SosPositionMarker.UNAVAILABLE, keys.sealedTexts.last { it.first == "location" }.second)
        assertEquals(SosAlertEntity.POSITION_UNAVAILABLE, dao.rows.value.getValue(SOS).positionState)

        // Another raise, still pending at the end of its window.
        cause = false
        served = null
        raise(SosSeal.PENDING)
        now = PRESS + SosUpdateRule.WINDOW_MS - 1
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        now = PRESS + SosUpdateRule.WINDOW_MS
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS))
        assertEquals(SosSeal.UNAVAILABLE, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)
    }

    @Test
    fun `no update past ten minutes - a fix after them leaves it as it is`() = runTest {
        raise(SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0))
        now = PRESS + 10 * MIN
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 10 * MIN - 1, 5.0)))
        assertTrue(resealed.isEmpty())
    }

    /** NewestOwnFix takes a fix [agoMs] before now, as it was then (it keeps only fresh ones). */
    private fun newestTook(agoMs: Long) {
        val at = elapsedNow - agoMs * 1_000_000L
        val fix = mockk<android.location.Location> {
            every { latitude } returns 37.33
            every { longitude } returns -122.01
            every { elapsedRealtimeNanos } returns at
            every { hasAccuracy() } returns true
            every { accuracy } returns 7f
        }
        val was = elapsedNow
        elapsedNow = at
        assertTrue(newest.offer(fix))
        elapsedNow = was
    }

    @Test
    fun `a fix NewestOwnFix took after the press counts, and one before it doesn't`() = runTest {
        raise(SosSeal.PENDING)
        now = PRESS + 40_000
        newestTook(agoMs = 45_000)
        assertEquals("taken 5 s before the press", OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        newestTook(agoMs = 1_000)
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS))
        assertEquals(PRESS + 39_000, lastSealedPosition().timestamp)
    }

    // ---- the rotation defer ----

    @Test
    fun `an update waits while the circle shows a rotation due, and goes after 2 minutes anyway`() = runTest {
        raise(SosSeal.PENDING)
        rotationNeeded = true
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        now += 2 * MIN - 1
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        assertTrue(resealed.isEmpty())
        now += 1
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, f))
        assertEquals(1, resealed.size)
    }

    @Test
    fun `while an update waits on a due rotation, an alert behind the key is still re-sealed as it stands`() = runTest {
        raise(SosSeal.PENDING)
        rotate()
        rotationNeeded = true
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        assertEquals("${SosPositionMarker.PENDING}@3", resealed.single().encryptedLocation.ciphertext)
        assertEquals(SosSeal.PENDING, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)
        now += 2 * MIN
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, f))
        assertEquals(PRESS + 30_000, lastSealedPosition().timestamp)
    }

    @Test
    fun `an update that stopped being due leaves no wait behind - the next one waits its own 2 minutes`() = runTest {
        raise(SosSeal.PENDING)
        rotationNeeded = true
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        // No fix at the next run: nothing is due any more.
        now += 30_000
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        // A fix again, past the first wait's 2 minutes: it waits on its own.
        now += 2 * MIN
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        assertTrue(resealed.isEmpty())
    }

    @Test
    fun `a rotation the circle shows due holds no update once it is done`() = runTest {
        raise(SosSeal.PENDING)
        rotationNeeded = true
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.DEFERRED, repo.runOwnSos(SOS, f))
        rotationNeeded = false
        rotate()
        now += 20_000
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, f))
        assertEquals("under the new key", "${RAISED}@3", resealed.single().encryptedTimestamp.ciphertext)
    }

    // ---- the record ----

    @Test
    fun `an unchanged record is never re-sealed, check after check - compared as the codec carries it`() = runTest {
        // Many decimals: no served position equals these doubles.
        raise(SosSeal.Point(37.33491234567891, -122.00901234567891, PRESS - 10 * MIN, 6.123456789))
        repeat(20) {
            now += 30_000
            assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        }
        assertTrue(resealed.isEmpty())
        assertTrue(order.isEmpty())
    }

    @Test
    fun `the record is what this phone sealed, opened back here - never the server's echo of a create or a re-seal`() = runTest {
        val mine = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        val swapped = FakeSosKeys.field("37.0,-121.0,${PRESS - 5 * MIN},3.0", 2, tag = "swap")
        // The server keeps and echoes another position than the one sent.
        coEvery { api.triggerSos(any()) } answers {
            val r = firstArg<TriggerSosRequest>()
            served = SosAlertResponse(
                id = SOS, circleId = r.circleId, encryptedLocation = swapped,
                encryptedDeviceState = r.encryptedDeviceState, status = "active", encryptedUserId = r.encryptedUserId,
                encryptedDeviceId = r.encryptedDeviceId, dayBucket = r.dayBucket, encryptedTimestamp = r.encryptedTimestamp,
                resolvedDayBucket = null,
            )
            ok(served)
        }
        raise(mine)
        assertEquals(mine, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)

        // So the next run sees a served copy that isn't its own, and seals its own back.
        now = PRESS + 20 * MIN
        resealAnswer = { body ->
            landed(body)
            // ...and echoes the swap again, from a re-seal this time.
            served = served!!.copy(encryptedLocation = swapped)
            ok(served)
        }
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS))
        assertEquals(mine.latitude, lastSealedPosition().latitude, 0.0)
        assertEquals(mine, ownSosSeals(ME, sealPrefs).get(SOS)!!.seal)
    }

    @Test
    fun `a served position that isn't what this phone sealed is sealed back to the record, and a rotation seals the record`() = runTest {
        val mine = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        raise(mine)
        // The server swaps in another position under the same key.
        served = served!!.copy(encryptedLocation = FakeSosKeys.field("37.0,-121.0,${PRESS - 5 * MIN},3.0", 2, tag = "swap"))
        now = PRESS + 20 * MIN
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS))
        assertEquals(mine.latitude, lastSealedPosition().latitude, 0.0)
        assertEquals(mine.takenAt, lastSealedPosition().timestamp)

        // Swapped again, and a rotation: still what this phone sealed, under the new key.
        served = served!!.copy(encryptedLocation = FakeSosKeys.field("37.0,-121.0,${PRESS - 5 * MIN},3.0", 2, tag = "swap2"))
        rotate()
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS, keysRead = true))
        assertEquals(mine.latitude, lastSealedPosition().latitude, 0.0)
        assertTrue(resealed.last().encryptedLocation.ciphertext.endsWith("@3"))
    }

    @Test
    fun `a rotation never takes an update back - the record is what was sealed last`() = runTest {
        raise(SosSeal.PENDING)
        now = PRESS + 40_000
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, f))
        rotate()
        now = PRESS + 20 * MIN
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS, keysRead = true))
        assertEquals(PRESS + 30_000, lastSealedPosition().timestamp)
    }

    @Test
    fun `the raiser's own row shows what it sealed, never a served copy or a reseal echo that differs`() = runTest {
        val mine = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        raise(mine)
        val swappedLocation = FakeSosKeys.field("37.0,-121.0,${PRESS - 5 * MIN},3.0", 2, tag = "swap")
        val swappedTime = FakeSosKeys.field("${RAISED + HOUR}", 2, tag = "swap")
        served = served!!.copy(encryptedLocation = swappedLocation, encryptedTimestamp = swappedTime)
        // A server that keeps the swap: the reseal lands, and its echo, and every read after, are the swap again.
        resealAnswer = { body ->
            landed(body)
            served = served!!.copy(encryptedLocation = swappedLocation, encryptedTimestamp = swappedTime)
            ok(served)
        }
        now = PRESS + 20 * MIN
        repo.refreshActiveAlerts()
        assertTrue("it was re-sealed back", resealed.isNotEmpty())
        val row = dao.rows.value.getValue(SOS)
        assertEquals(37.3349, row.latitude!!, 0.0)
        assertEquals(PRESS - 10 * MIN, row.fixAt)
        assertEquals("asked when this phone asked", RAISED, row.createdAt)
    }

    @Test
    fun `a served raise time that isn't the one this phone sealed is sealed back`() = runTest {
        raise(SosSeal.PENDING)
        served = served!!.copy(encryptedTimestamp = FakeSosKeys.field("${RAISED + HOUR}", 2, tag = "later"))
        now = PRESS + MIN
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS))
        assertEquals("the ORIGINAL raise time, as this phone sealed it", "$RAISED@2", resealed.single().encryptedTimestamp.ciphertext)
    }

    @Test
    fun `the record survives a restart, and a phone with none re-seals what it opened`() = runTest {
        val mine = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        raise(mine)
        served = served!!.copy(encryptedLocation = FakeSosKeys.field("37.0,-121.0,${PRESS - 5 * MIN},3.0", 2, tag = "swap"))
        rotate()
        now = PRESS + 20 * MIN

        // A restart: a new repository over the same store.
        val restarted = phone(ME, "dev-me")
        assertEquals(OwnRunResult.RESEALED, restarted.runOwnSos(SOS, keysRead = true))
        assertEquals(mine.latitude, lastSealedPosition().latitude, 0.0)

        // A reinstall: no record, and no press here. What opened is all it has: re-sealed where it opened,
        // and no record is written, since this phone can't name the circle it raised it in.
        keys.current = 4
        keys.held += 4
        val fresh = InMemoryPrefs()
        val reinstalled = phone(ME, "dev-me", seals = fresh)
        assertEquals(OwnRunResult.RESEALED, reinstalled.runOwnSos(SOS, keysRead = true))
        assertEquals(mine.latitude, lastSealedPosition().latitude, 0.0)
        assertTrue(resealed.last().encryptedLocation.ciphertext.endsWith("@4"))
        assertNull(ownSosSeals(ME, fresh).get(SOS))
    }

    @Test
    fun `a marker alert is re-sealed on a rotation, as a marker`() = runTest {
        raise(SosSeal.PENDING)
        rotate()
        now = PRESS + MIN
        assertEquals(OwnRunResult.RESEALED, repo.runOwnSos(SOS, keysRead = true))
        assertEquals("${SosPositionMarker.PENDING}@3", resealed.single().encryptedLocation.ciphertext)
    }

    @Test
    fun `an alert that ended, or a 404, forgets the record - a 429 holds quietly`() = runTest {
        raise(SosSeal.PENDING)
        rotate()
        now = PRESS + 20 * MIN
        resealAnswer = { Response.error(429, "".toResponseBody(null)) }
        assertEquals(OwnRunResult.HELD, repo.runOwnSos(SOS, keysRead = true))
        assertNotNull(ownSosSeals(ME, sealPrefs).get(SOS))
        resealAnswer = { Response.error(404, "".toResponseBody(null)) }
        assertEquals(OwnRunResult.ENDED, repo.runOwnSos(SOS, keysRead = true))
        assertNull(ownSosSeals(ME, sealPrefs).get(SOS))

        served = null
        raise(SosSeal.PENDING)
        served = served!!.copy(status = "resolved")
        assertEquals(OwnRunResult.ENDED, repo.runOwnSos(SOS))
        assertNull(ownSosSeals(ME, sealPrefs).get(SOS))
    }

    @Test
    fun `resolving or calling a false alarm forgets the record`() = runTest {
        raise(SosSeal.PENDING)
        coEvery { api.resolveSos(SOS) } answers { ok(served!!.copy(status = "resolved")) }
        repo.resolveSos(SOS)
        assertNull(ownSosSeals(ME, sealPrefs).get(SOS))
    }

    // ---- only into the circle it was raised in ----

    @Test
    fun `served in another of my circles, it gets no PUT and keeps its record - the catch-up path`() = runTest {
        val mine = SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0)
        raise(mine)
        // The server relabels it into circle B, with a position of B's: its raiser, device and time open
        // there, as a check-in this phone made in B does.
        served = served!!.copy(circleId = CIRCLE_B, encryptedLocation = FakeSosKeys.field("36.0,-120.0,${PRESS - 5 * MIN},3.0", 2, tag = "b"))
        // Past the window: no update is due; only a catch-up would seal.
        now = PRESS + 20 * MIN
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS))
        assertTrue("not even a key read", order.isEmpty())
        // A refresh: no re-seal either, and its row in B never shows the record, which is the raising circle's.
        repo.refreshActiveAlerts()
        assertEquals(36.0, dao.rows.value.getValue(SOS).latitude!!, 0.0)
        rotate()
        assertEquals("nor when it isn't current there", OwnRunResult.NOTHING, repo.runOwnSos(SOS, keysRead = true))
        assertTrue(resealed.isEmpty())
        assertEquals(OwnSosSeal(CIRCLE, RAISED, mine), ownSosSeals(ME, sealPrefs).get(SOS))
    }

    @Test
    fun `served in another of my circles, it gets no PUT and keeps its record - the due-update path`() = runTest {
        raise(SosSeal.PENDING)
        // Relabelled into B as it stands: nothing differs and it is current there; only an update would seal.
        served = served!!.copy(circleId = CIRCLE_B)
        now = PRESS + 40_000
        assertEquals(OwnRunResult.NOTHING, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)))
        assertTrue(resealed.isEmpty())
        assertEquals(OwnSosSeal(CIRCLE, RAISED, SosSeal.PENDING), ownSosSeals(ME, sealPrefs).get(SOS))
    }

    @Test
    fun `with no record and no press circle, a due fix makes no update - what opened may be re-sealed where it opened, and no record is made`() = runTest {
        raise(SosSeal.PENDING)
        // A reinstall: no record, and no press in this process.
        val fresh = InMemoryPrefs()
        val reinstalled = phone(ME, "dev-me", seals = fresh)
        now = PRESS + 40_000
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        assertEquals(OwnRunResult.NOTHING, reinstalled.runOwnSos(SOS, f))
        assertTrue(resealed.isEmpty())

        rotate()
        assertEquals(OwnRunResult.RESEALED, reinstalled.runOwnSos(SOS, f, keysRead = true))
        assertEquals("what opened, not the fix", "${SosPositionMarker.PENDING}@3", resealed.single().encryptedLocation.ciphertext)
        assertNull("a circle it can't name is never written into a record", ownSosSeals(ME, fresh).get(SOS))
    }

    @Test
    fun `the press circle is compared in its one spelling, and the record keeps its own`() = runTest {
        raise(SosSeal.PENDING)
        served = served!!.copy(circleId = CIRCLE.uppercase())
        now = PRESS + 40_000
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)))
        assertEquals(CIRCLE, ownSosSeals(ME, sealPrefs).get(SOS)!!.circleId)
    }

    // ---- one run at a time ----

    @Test
    fun `two runs at once serialize - the second sees what the first sealed, and seals nothing`() = runTest {
        raise(SosSeal.PENDING)
        now = PRESS + 40_000
        resealAnswer = { body ->
            delay(1_000)
            landed(body)
        }
        val f = SosSeal.Point(37.33, -122.01, PRESS + 30_000, 9.0)
        val results = listOf(async { repo.runOwnSos(SOS, f) }, async { repo.runOwnSos(SOS, f) }).awaitAll()
        assertEquals(1, resealed.size)
        assertEquals(setOf(OwnRunResult.UPDATED, OwnRunResult.NOTHING), results.toSet())
    }

    // ---- End to end: another member's phone ----

    @Test
    fun `end to end - a raise with a held fix, then the update - the other member's pin moves, reads as updated, one alarm`() = runTest {
        raise(SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0))
        val theirs = FakeSosAlertDao()
        val other = phone(OTHER, "dev-other", seals = InMemoryPrefs(), dao = theirs)

        now = PRESS + 20_000
        other.refreshActiveAlerts()
        val first = theirs.rows.value.getValue(SOS)
        assertEquals(37.3349, first.latitude!!, 0.0)
        assertEquals(SosPosition.LastKnown(PRESS - 10 * MIN), SosPosition.of(first, now))

        now = PRESS + 100_000
        assertEquals(OwnRunResult.UPDATED, repo.runOwnSos(SOS, SosSeal.Point(37.33, -122.01, PRESS + 90_000, 9.0)))

        // sos_refresh: the other phone loads again.
        other.refreshActiveAlerts()
        val moved = theirs.rows.value.getValue(SOS)
        assertEquals(37.33, moved.latitude!!, 0.0)
        assertEquals(-122.01, moved.longitude!!, 0.0)
        assertEquals(SosPosition.Updated(PRESS + 90_000), SosPosition.of(moved, now))
        assertEquals("still asked when it was asked", RAISED, moved.createdAt)
        verify(exactly = 1) { alerts.ringSos(SOS, any(), any()) }
    }
}
