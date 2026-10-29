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
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.CreateCircleRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.RefreshRecordPayload
import com.pingolin.track.data.remote.dto.ReservedCircleIdResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleCreationData
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.RecordedCircleCreation
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * A new circle comes with its genesis record, which binds the circle's id, so the server
 * reserves the id first (`POST /api/v1/circles/reserve-id`) and the creation carries both.
 */
class CircleCreationTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val circleDao = mockk<CircleDao>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private lateinit var repository: CircleRepository

    private val creation = CircleCreationData(
        encryptedCircleKey = EncryptedData(nonce = "kn", ciphertext = "kc"),
        ephemeralPublicKey = "ke",
        tempCircleId = "c-new",
    )
    private val sent = slot<CreateCircleRequest>()

    private fun circle(id: String) = CircleResponse(
        id = id,
        encryptedName = EncryptedPayload("nn", "nc"),
        encryptedKey = null,
        ephemeralPublicKey = null,
        memberCount = 1,
        isOwner = true,
        dayBucket = 20000,
        keyRotationNeeded = false,
        keyVersion = 1,
    )

    @Before
    fun setUp() {
        every { sessionManager.getUserId() } returns "me"
        coEvery { tracksApi.reserveCircleId() } returns
            Response.success(ApiResponse(success = true, data = ReservedCircleIdResponse("c-new"), error = null))
        every { cryptoManager.prepareRecordedCircleCreation("me", "c-new") } returns
            RecordedCircleCreation("c-new", creation, SignedRefreshRecord("genesis-hex", "genesis-sig"))
        every { cryptoManager.encryptDataV2("c-new", "Family", "name") } returns EncryptedData("nn", "nc")
        coEvery { circleDao.insert(any()) } just Runs
        coEvery { tracksApi.createCircle(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = circle("c-new"), error = null))
        repository = CircleRepository(
            context = mockk(relaxed = true),
            tracksApi = tracksApi,
            circleDao = circleDao,
            circleSortOrderDao = mockk(relaxed = true),
            circleMemberDao = mockk(relaxed = true),
            pendingInviteDao = mockk(relaxed = true),
            privacySettingDao = mockk(relaxed = true),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            // Typed: a relaxed mock of the generic Lazy hands back an untyped object.
            locationRepository = dagger.Lazy<LocationRepository> { mockk(relaxed = true) },
            previousIdentityLoader = mockk(relaxed = true),
            uxEventBus = UxEventBus(),
            currentKeyGate = CurrentKeyGate(circleDao, cryptoManager, mockk(relaxed = true), mockk(relaxed = true)),
            keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
            sosRepository = dagger.Lazy<SosRepository> { mockk(relaxed = true) },
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @Test
    fun `a new circle is made under the id the server reserved, and goes with its genesis`() = runTest {
        val made = repository.createCircle("Family", "v2")

        assertEquals("c-new", made.getOrThrow().id)
        with(sent.captured) {
            assertEquals("c-new", circleId)
            assertEquals(RefreshRecordPayload("genesis-hex", "genesis-sig"), genesis)
            assertEquals("kn", encryptedCircleKey.nonce)
            assertEquals("kc", encryptedCircleKey.ciphertext)
            assertEquals("ke", ephemeralPublicKey)
            assertEquals("nn", encryptedName.nonce)
            assertEquals("v2", locationConsent?.clientNoticeVersion)
        }
        // Nothing to finalize: the key was made under the reserved id.
        verify(exactly = 0) { cryptoManager.prepareCircleCreation() }
        verify(exactly = 0) { cryptoManager.finalizeCircleCreation(any(), any()) }
        verify(exactly = 0) { cryptoManager.discardCircleCreation(any()) }
        verify { cryptoManager.signCircleCreationWrap(creation, "c-new", "me") }
    }

    @Test
    fun `the records check follows the creation, to publish the account's head`() = runTest {
        repository.deliveryScope = this

        repository.createCircle("Family", "v2")
        advanceUntilIdle()

        coVerify(exactly = 1) { tracksApi.refreshRecords(any()) }
    }

    @Test
    fun `no id reserved, no circle - and the person reads this app's words`() = runTest {
        coEvery { tracksApi.reserveCircleId() } returns Response.error(
            429,
            """{"success":false,"error":"Too many circles being created at once.","error_code":"rate_limit_exceeded"}""".toResponseBody(null),
        )

        val made = repository.createCircle("Family", "v2")

        assertTrue(made.isFailure)
        assertEquals(com.pingolin.track.data.remote.dto.ApiErrors.TOO_MANY, made.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.prepareRecordedCircleCreation(any(), any()) }
        coVerify(exactly = 0) { tracksApi.createCircle(any()) }
    }

    @Test
    fun `a refused creation drops the key and the genesis made for it`() = runTest {
        coEvery { tracksApi.createCircle(any()) } returns Response.error(409, """{"error_code":"circle_id_not_reserved"}""".toResponseBody(null))

        assertTrue(repository.createCircle("Family", "v2").isFailure)

        verify(exactly = 1) { cryptoManager.discardCircleCreation("c-new") }
        coVerify(exactly = 0) { circleDao.insert(any()) }
    }

    @Test
    fun `no answer is not a refusal - the creation is kept, since the server may have taken it`() = runTest {
        coEvery { tracksApi.createCircle(any()) } throws java.io.IOException("timeout")

        assertTrue(repository.createCircle("Family", "v2").isFailure)

        verify(exactly = 0) { cryptoManager.discardCircleCreation(any()) }
    }

    @Test
    fun `a circle the server made under another id than it reserved is not taken`() = runTest {
        coEvery { tracksApi.createCircle(any()) } returns
            Response.success(ApiResponse(success = true, data = circle("c-other"), error = null))

        val made = repository.createCircle("Family", "v2")

        assertEquals(CircleRepository.CIRCLE_NOT_CREATED, made.exceptionOrNull()?.message)
        verify(exactly = 1) { cryptoManager.discardCircleCreation("c-new") }
        coVerify(exactly = 0) { circleDao.insert(any()) }
    }
}
