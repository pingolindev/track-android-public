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

import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.SosAlertResponse
import com.pingolin.track.util.SessionManager
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Closing out SOS alerts that dropped off `/sos/active`.
 *
 * The sos_acknowledged / sos_resolved pushes only draw a notification, so
 * without this sweep an alert closed by another member would stay `active` on
 * every other device forever: a permanent SOS marker and edge arrow on the
 * map.
 *
 * The sweep is the dangerous half — sweeping too eagerly hides a live
 * emergency — so these tests pin exactly which ids it may touch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosReconcileTest {

    @MockK private lateinit var tracksApi: TrackApi
    @MockK private lateinit var sosAlertDao: SosAlertDao
    @MockK private lateinit var sessionManager: SessionManager
    @MockK private lateinit var cryptoManager: uniffi.pingolin_mobile.CryptoManager

    private lateinit var repo: SosRepository

    private fun blob() = EncryptedPayload(nonce = "00", ciphertext = "00")

    private fun dto(id: String, status: String = "active") = SosAlertResponse(
        id = id,
        circleId = "c1",
        encryptedLocation = blob(),
        encryptedDeviceState = null,
        status = status,
        encryptedUserId = blob(),
        encryptedDeviceId = blob(),
        dayBucket = 20660,
        encryptedTimestamp = blob(),
        resolvedDayBucket = null,
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        // Ciphertext is fake, so every decrypt throws — which is also the
        // realistic worst case. The user_id decrypt failing drops the alert from
        // the mapped list, and the sweep must NOT treat that as "server closed
        // it": that's the whole reason it keys off the raw DTO ids.
        coEvery { cryptoManager.decryptDataV2(any(), any(), any()) } throws RuntimeException("no key")
        coEvery { cryptoManager.decryptLocation(any(), any()) } throws RuntimeException("no key")
        coEvery { sosAlertDao.getById(any()) } returns null
        // The receivers' rules' collaborators, out of the way: an SOS that doesn't open
        // here is left as it is (this device may be behind on its key), never closed
        // out.
        val circles = io.mockk.mockk<CircleRepository>(relaxed = true)
        io.mockk.every { circles.sosMarks } returns
            com.pingolin.track.util.SecurePrefsSosMarks(io.mockk.mockk(), { "me" }, { com.pingolin.track.testutil.InMemoryPrefs() })
        io.mockk.every { circles.ownSosSeals } returns com.pingolin.track.testutil.ownSosSeals("me")
        repo = SosRepository(
            tracksApi = tracksApi,
            sosAlertDao = sosAlertDao,
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            circleRepository = dagger.Lazy { circles },
            currentKeyGate = io.mockk.mockk(relaxed = true),
            owedWipe = io.mockk.mockk(relaxed = true),
            alertHandler = dagger.Lazy { io.mockk.mockk(relaxed = true) },
            circleDao = io.mockk.mockk(relaxed = true),
            circleMemberDao = io.mockk.mockk(relaxed = true),
            positions = com.pingolin.track.testutil.quietPositions(sessionManager),
        )
    }

    private fun serverReturns(vararg ids: String) {
        coEvery { tracksApi.getActiveSosAlerts() } returns
            Response.success(ApiResponse(success = true, data = ids.map { dto(it) }, error = null))
    }

    /** An alert the server stopped listing is closed out with its REAL status. */
    @Test
    fun alert_missing_from_active_is_closed_with_its_real_status() = runTest {
        coEvery { sosAlertDao.getActiveIds() } returns listOf("gone")
        serverReturns()
        coEvery { tracksApi.getSosAlert("gone") } returns
            Response.success(ApiResponse(success = true, data = dto("gone", "resolved"), error = null))

        repo.refreshActiveAlerts()

        coVerify(exactly = 1) { sosAlertDao.setStatus("gone", "resolved") }
    }

    /**
     * When the per-alert re-fetch can't tell us why it went away, we record
     * "closed" rather than guessing "resolved" — the UI must never assert an
     * outcome the server didn't state.
     */
    @Test
    fun unknowable_outcome_is_recorded_as_closed_not_guessed() = runTest {
        coEvery { sosAlertDao.getActiveIds() } returns listOf("gone")
        serverReturns()
        coEvery { tracksApi.getSosAlert("gone") } returns Response.error(
            404,
            "".toResponseBody(null),
        )

        repo.refreshActiveAlerts()

        coVerify(exactly = 1) { sosAlertDao.setStatus("gone", SosRepository.STATUS_CLOSED) }
    }

    /** An alert the server still lists is left alone. */
    @Test
    fun still_active_alert_is_not_swept() = runTest {
        coEvery { sosAlertDao.getActiveIds() } returns listOf("live")
        serverReturns("live")

        repo.refreshActiveAlerts()

        coVerify(exactly = 0) { sosAlertDao.setStatus(any(), any()) }
    }

    /**
     * THE ONE THAT MATTERS. The id snapshot is taken BEFORE the request goes
     * out, so an alert raised on this device while the request was in flight
     * isn't in it — and can't be closed by a response that predates it. Taking
     * the snapshot after the response would silently kill the user's own SOS.
     */
    @Test
    fun alert_created_while_the_request_was_in_flight_is_not_swept() = runTest {
        // Snapshot is empty: at request time this alert did not exist yet.
        coEvery { sosAlertDao.getActiveIds() } returns emptyList()
        serverReturns()

        repo.refreshActiveAlerts()

        coVerify(exactly = 0) { sosAlertDao.setStatus(any(), any()) }
        coVerify(exactly = 0) { tracksApi.getSosAlert(any()) }
    }

    /**
     * An alert the server DID return but whose user_id wouldn't decrypt is
     * dropped from the local list — it must still not be sweep-eligible, since
     * the server plainly still considers it active.
     */
    @Test
    fun undecryptable_but_still_listed_alert_is_not_swept() = runTest {
        coEvery { sosAlertDao.getActiveIds() } returns listOf("opaque")
        serverReturns("opaque")

        val result = repo.refreshActiveAlerts()

        // Dropped from the mapped entities...
        assert(result.getOrNull()?.isEmpty() == true)
        // ...but NOT closed out.
        coVerify(exactly = 0) { sosAlertDao.setStatus(any(), any()) }
    }
}
