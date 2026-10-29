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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CheckInResponse
import com.pingolin.track.data.remote.dto.CreateCheckInRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * A check-in made while this phone is behind: kept with nothing sealed, its position as the
 * circle's setting allowed then, and sent later with the time the person checked in, through
 * the button's own function, as the stricter of the setting then and now.
 */
class CheckInWaitingTest {

    private val lat = 51.50735
    private val lng = -0.12776
    private val checkedInAt = 1_700_000_000_000L

    private val tracksApi = mockk<TrackApi>()
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val checkInDao = mockk<CheckInDao>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true) {
        every { getUserId() } returns "me"
        every { getDeviceId() } returns "device-1"
    }
    private var privacy: PrivacySettingEntity? = setting("exact")
    private val privacySettingDao = mockk<PrivacySettingDao>().also { dao -> coEvery { dao.get(any()) } answers { privacy } }
    private var behind = true
    private val circleRepository = mockk<CircleRepository>(relaxed = true) {
        coEvery { requireCurrentKey("c-1") } answers { if (behind) throw KeyNotCurrentException() }
        coEvery { ensurePrivacyPrimed(any()) } returns true
    }
    private val dao = InMemoryPendingChangeDao()
    private val pendingChanges = PendingChanges(
        dao, sessionManager, mockk(relaxed = true),
        mockk<CircleDao> { coEvery { getById(any()) } returns CircleEntity("c-1", "Family", null, 0, 0) },
        mockk<CircleMemberDao>(relaxed = true),
    )
    private val repository = CheckInRepository(tracksApi, checkInDao, sessionManager, cryptoManager, privacySettingDao, circleRepository, pendingChanges, com.pingolin.track.util.PauseIntervals.inMemory(), com.pingolin.track.util.ApproximateIntervals.inMemory())

    private fun setting(mode: String, miles: Int? = null) =
        PrivacySettingEntity(circleId = "c-1", sharingMode = mode, approximateRadiusMiles = miles, updatedAt = 0)

    private fun kept(): PendingInputs.CheckIn = PendingInputs.read(dao.changes.value.single()) as PendingInputs.CheckIn

    /** Queue a check-in with a position while behind, then catch up. */
    private suspend fun queueThenCatchUp(): PendingRun {
        val result = repository.createCheckIn("c-1", "safe_arrival", "home", lat, lng, keepIfBehind = true)
        assertTrue(result.exceptionOrNull() is QueuedForSync)
        behind = false
        return PendingRun(dao.changes.value.single().id, dao.changes.value.single().id)
    }

    private fun answered(request: io.mockk.CapturingSlot<CreateCheckInRequest>) {
        val payload = EncryptedPayload("n", "c")
        coEvery { tracksApi.createCheckIn(capture(request)) } returns Response.success(
            ApiResponse(success = true, data = CheckInResponse("ci-1", "c-1", null, null, null, "safe_arrival", payload, payload, 0, payload), error = null),
        )
    }

    @Test
    fun `behind, kept with its line, and nothing is sealed or sent`() = runTest {
        val result = repository.createCheckIn("c-1", "safe_arrival", "home", lat, lng, keepIfBehind = true)

        assertEquals(PendingLines.CHECK_IN, result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.encryptLocation(any(), any()) }
        coVerify(exactly = 0) { tracksApi.createCheckIn(any()) }
        coVerify(exactly = 0) { checkInDao.insert(any()) }
        assertEquals("home", kept().message)
    }

    @Test
    fun `a caller that doesn't ask to keep it is refused and nothing is kept (an automatic send)`() = runTest {
        val result = repository.createCheckIn("c-1", "safe_arrival", "home", lat, lng)

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        assertTrue(dao.changes.value.isEmpty())
    }

    @Test
    fun `sent with the time the person checked in, and the change's own request id`() = runTest {
        repository.createCheckIn("c-1", "safe_arrival", "home", lat, lng, keepIfBehind = true)
        val kept = dao.changes.value.single()
        val stored = kept()
        behind = false
        val request = slot<CreateCheckInRequest>()
        answered(request)

        val result = repository.createCheckIn(
            "c-1", stored.type, stored.message, stored.latitude, stored.longitude, stored.placeId,
            checkedInAt = checkedInAt, pendingRun = PendingRun(kept.id, kept.id),
        )

        assertTrue(result.isSuccess)
        assertEquals(kept.id, request.captured.clientRequestId)
        assertEquals((checkedInAt / 86_400_000L).toInt(), request.captured.dayBucket)
        verify { cryptoManager.encryptDataV2("c-1", checkedInAt.toString(), "timestamp") }
    }

    @Test
    fun `approximate when queued, precise at send, it goes out coarsened`() = runTest {
        privacy = setting("approximate", miles = 5)
        val run = queueThenCatchUp()
        val stored = kept()
        val bubble = LocationRepository.coarsenLocation(lat, lng, 5 * LocationRepository.METERS_PER_MILE)
        assertEquals(bubble.first, stored.latitude!!, 0.0)
        assertEquals(bubble.second, stored.longitude!!, 0.0)
        assertNotEquals(lat, stored.latitude!!, 1e-6)

        privacy = setting("exact")
        val sealed = slot<Location>()
        every { cryptoManager.encryptLocation("c-1", capture(sealed)) } returns EncryptedData("n", "c")
        answered(slot())
        repository.createCheckIn("c-1", stored.type, stored.message, stored.latitude, stored.longitude, null, checkedInAt = stored.checkedInAt, pendingRun = run)

        assertEquals(bubble.first, sealed.captured.latitude, 0.0)
        assertEquals(bubble.second, sealed.captured.longitude, 0.0)
    }

    @Test
    fun `precise when queued, paused at send, no position`() = runTest {
        val run = queueThenCatchUp()
        val stored = kept()
        assertEquals(lat, stored.latitude!!, 0.0)

        privacy = setting("paused")
        val request = slot<CreateCheckInRequest>()
        answered(request)
        repository.createCheckIn("c-1", stored.type, stored.message, stored.latitude, stored.longitude, null, checkedInAt = stored.checkedInAt, pendingRun = run)

        assertNull(request.captured.encryptedLocation)
        verify(exactly = 0) { cryptoManager.encryptLocation(any(), any()) }
    }

    @Test
    fun `paused when queued, no position, whatever the setting at send`() = runTest {
        privacy = setting("paused")
        val run = queueThenCatchUp()
        val stored = kept()
        assertNull(stored.latitude)
        assertNull(stored.longitude)

        privacy = setting("exact")
        val request = slot<CreateCheckInRequest>()
        answered(request)
        repository.createCheckIn("c-1", stored.type, stored.message, stored.latitude, stored.longitude, null, checkedInAt = stored.checkedInAt, pendingRun = run)

        assertNull(request.captured.encryptedLocation)
    }

    @Test
    fun `a run the server refuses is not stored for the sync as well, which would send it twice`() = runTest {
        val run = queueThenCatchUp()
        val stored = kept()
        coEvery { tracksApi.createCheckIn(any()) } returns Response.error(503, "{}".toResponseBody(null))

        val result = repository.createCheckIn("c-1", stored.type, stored.message, stored.latitude, stored.longitude, null, checkedInAt = stored.checkedInAt, pendingRun = run)

        assertEquals(503, (result.exceptionOrNull() as com.pingolin.track.data.remote.dto.RefusedException).status)
        coVerify(exactly = 0) { checkInDao.insert(any()) }
    }
}
