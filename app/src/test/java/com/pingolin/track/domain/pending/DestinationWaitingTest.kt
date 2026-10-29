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

import com.pingolin.track.data.local.dao.DestinationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CreateDestinationRequest
import com.pingolin.track.data.remote.dto.DestinationResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.ReportArrivalRequest
import com.pingolin.track.data.remote.dto.ReportArrivalResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/** A destination and "I've arrived" made while this phone is behind. */
class DestinationWaitingTest {

    private val now = 1_700_000_000_000L
    private val tracksApi = mockk<TrackApi>()
    private val cryptoManager = mockk<CryptoManager>(relaxed = true) {
        every { encryptDataV2(any(), any(), any()) } answers { EncryptedData("n", "ct:${thirdArg<String>()}:${secondArg<String>()}") }
    }
    private var behind = true
    private val circleRepository = mockk<CircleRepository>(relaxed = true) {
        coEvery { requireCurrentKey("c-1") } answers { if (behind) throw KeyNotCurrentException() }
        coEvery { ensurePrivacyPrimed(any()) } returns true
    }
    private var privacy = PrivacySettingEntity(circleId = "c-1", sharingMode = "approximate", approximateRadiusMiles = 2, updatedAt = 0)
    private val privacySettingDao = mockk<PrivacySettingDao>().also { dao -> coEvery { dao.get(any()) } answers { privacy } }
    private val me = "a1b2c3d4-0000-4000-8000-00000000000e"
    // This account's own destination, which is all an arrival is ever reported for.
    private val destinationDao = mockk<DestinationDao>(relaxed = true) {
        coEvery { getById("dest-1") } returns com.pingolin.track.data.local.entity.DestinationEntity(
            id = "dest-1", userId = me, circleId = "c-1", destName = null, destLatitude = 0.0, destLongitude = 0.0,
            etaDayBucket = null, startedDayBucket = 0, createdDayBucket = 0, updatedDayBucket = 0, creatorId = me,
        )
    }
    private val dao = InMemoryPendingChangeDao()
    private val repository = DestinationRepository(
        tracksApi, destinationDao, mockk(relaxed = true), mockk(relaxed = true),
        mockk<SessionManager>(relaxed = true) { every { getUserId() } returns me },
        cryptoManager, privacySettingDao, circleRepository, pendingChangesIn(dao, now = { now }),
    )

    @Test
    fun `a destination made while behind is kept, sealed nothing, and waits until its arrival time at most`() = runTest {
        val eta = now + 2 * PendingKind.HOUR_MS
        val result = repository.createDestination("c-1", "Gym", 51.5, -0.1, eta, notifyArrival = true, keepIfBehind = true)

        assertEquals("Waiting to sync. Gym will be shared with Family as soon as this phone catches up.", result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptLocation(any(), any()) }
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        val kept = dao.changes.value.single()
        assertEquals(eta, kept.expiresAt)
        assertEquals(PendingInputs.Destination("Family", "Gym", 51.5, -0.1, eta, true), PendingInputs.read(kept))
    }

    @Test
    fun `it goes out through the same function with the change's own request id`() = runTest {
        repository.createDestination("c-1", null, 51.5, -0.1, keepIfBehind = true)
        val kept = dao.changes.value.single()
        assertEquals(now + PendingKind.DAY_MS, kept.expiresAt)
        behind = false
        val request = slot<CreateDestinationRequest>()
        val payload = EncryptedPayload("n", "c")
        coEvery { tracksApi.createDestination(capture(request)) } returns Response.success(
            ApiResponse(true, DestinationResponse("d-1", payload, "c-1", payload, null, null, null, true, "active", 0, null, 0, 0), null),
        )

        val result = repository.createDestination("c-1", null, 51.5, -0.1, pendingRun = PendingRun(kept.id, kept.id))

        assertTrue(result.isSuccess)
        assertEquals(kept.id, request.captured.clientRequestId)
    }

    @Test
    fun `I've arrived while behind is kept with its time and the position the setting allows then`() = runTest {
        val arrivedAt = now - 60_000
        val result = repository.reportArrival("dest-1", "c-1", 51.50735, -0.12776, occurredAtMs = arrivedAt, keepIfBehind = true)

        assertEquals(PendingLines.ARRIVED, result.exceptionOrNull()?.message)
        val kept = PendingInputs.read(dao.changes.value.single()) as PendingInputs.Arrived
        assertEquals(arrivedAt, kept.arrivedAt)
        val bubble = LocationRepository.coarsenLocation(51.50735, -0.12776, 2 * LocationRepository.METERS_PER_MILE)
        assertEquals(bubble.first, kept.latitude!!, 0.0)
        assertEquals(bubble.second, kept.longitude!!, 0.0)
        assertEquals(now + 2 * PendingKind.HOUR_MS, dao.changes.value.single().expiresAt)
        coVerify(exactly = 0) { tracksApi.reportDestinationArrival(any(), any()) }
    }

    @Test
    fun `the automatic arrival is never kept, it retries by itself`() = runTest {
        val result = repository.reportArrival("dest-1", "c-1", 51.5, -0.1)

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        assertTrue(dao.changes.value.isEmpty())
    }

    @Test
    fun `the kept arrival goes out with the time the person arrived`() = runTest {
        val arrivedAt = now - 60_000
        repository.reportArrival("dest-1", "c-1", null, null, occurredAtMs = arrivedAt, keepIfBehind = true)
        val kept = dao.changes.value.single()
        behind = false
        val request = slot<ReportArrivalRequest>()
        coEvery { tracksApi.reportDestinationArrival("dest-1", capture(request)) } returns
            Response.success(ApiResponse(true, ReportArrivalResponse("dest-1", "e-1", 1), null))

        val stored = PendingInputs.read(kept) as PendingInputs.Arrived
        repository.reportArrival("dest-1", "c-1", stored.latitude, stored.longitude, stored.arrivedAt, pendingRun = PendingRun(kept.id, kept.id))

        assertEquals("ct:occurred_at:$arrivedAt", request.captured.encryptedOccurredAt?.ciphertext)
        assertNull(request.captured.encryptedLocation)
    }

    @Test
    fun `run from the queue, the arrival carries the time the person arrived and the position kept then`() = runTest {
        val arrivedAt = now - 60_000
        repository.reportArrival("dest-1", "c-1", 51.50735, -0.12776, occurredAtMs = arrivedAt, keepIfBehind = true)
        val kept = dao.changes.value.single()
        val stored = PendingInputs.read(kept) as PendingInputs.Arrived
        val destinations = mockk<DestinationRepository> {
            coEvery { refreshDestinationsListed() } returns DestinationRepository.ListedDestinations(mapOf("dest-1" to DestinationRepository.STATUS_ACTIVE), whole = true)
            coEvery { reportArrival(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(ReportArrivalResponse("dest-1", "e-1", 1))
        }
        val executors = PendingExecutors(mockk(), { destinations }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))
        val run = PendingRun(kept.id, kept.id)

        val outcome = executors.forKind(PendingKind.ARRIVED)!!.run(kept, stored, run)

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 1) { destinations.reportArrival("dest-1", "c-1", stored.latitude, stored.longitude, arrivedAt, false, run) }
    }

    @Test
    fun `a kept arrival whose destination was cancelled meanwhile is dropped as deleted`() = runTest {
        repository.reportArrival("dest-1", "c-1", null, null, occurredAtMs = now, keepIfBehind = true)
        val kept = dao.changes.value.single()
        val destinations = mockk<DestinationRepository> {
            coEvery { refreshDestinationsListed() } returns DestinationRepository.ListedDestinations(mapOf("dest-1" to "cancelled"), whole = true)
        }
        val executors = PendingExecutors(mockk(), { destinations }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.ARRIVED)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("Your arrival wasn't done: the destination was deleted."), outcome)
        coVerify(exactly = 0) { destinations.reportArrival(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a kept arrival whose destination already arrived is done, with no line`() = runTest {
        repository.reportArrival("dest-1", "c-1", null, null, occurredAtMs = now, keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The whole list leaves arrived destinations out.
        val destinations = mockk<DestinationRepository> { coEvery { refreshDestinationsListed() } returns DestinationRepository.ListedDestinations(emptyMap(), whole = true) }
        val executors = PendingExecutors(mockk(), { destinations }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.ARRIVED)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Done(), outcome)
    }

    @Test
    fun `a destination list is whole only under the server's cap of 100, and gives every listed status either way`() = runTest {
        val payload = EncryptedPayload("n", "c")
        fun rows(n: Int) = (1..n).map { DestinationResponse("d-$it", payload, "c-1", payload, null, null, null, true, "active", 0, null, 0, 0) }
        coEvery { tracksApi.getDestinations() } returns Response.success(ApiResponse(true, rows(99), null))
        val some = repository.refreshDestinationsListed()!!
        assertTrue(some.whole)
        assertEquals(99, some.statuses.size)

        coEvery { tracksApi.getDestinations() } returns Response.success(ApiResponse(true, rows(100), null))
        val full = repository.refreshDestinationsListed()!!
        assertTrue("the server stops at 100: some may be left out", !full.whole)
        assertEquals(DestinationRepository.STATUS_ACTIVE, full.statuses["d-100"])
    }

    @Test
    fun `a destination list that couldn't be had says nothing - the arrival waits`() = runTest {
        repository.reportArrival("dest-1", "c-1", null, null, occurredAtMs = now, keepIfBehind = true)
        val kept = dao.changes.value.single()
        val destinations = mockk<DestinationRepository>(relaxed = true) { coEvery { refreshDestinationsListed() } returns null }
        val executors = PendingExecutors(mockk(), { destinations }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.ARRIVED)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Transient(maybeSent = false), outcome)
        coVerify(exactly = 0) { destinations.reportArrival(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a full list that leaves the destination out says nothing - but one that lists it is enough`() = runTest {
        repository.reportArrival("dest-1", "c-1", null, null, occurredAtMs = now, keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The server stops at 100 rows: absent from a full list is not "arrived".
        val full = mockk<DestinationRepository>(relaxed = true) {
            coEvery { refreshDestinationsListed() } returns DestinationRepository.ListedDestinations(mapOf("dest-2" to DestinationRepository.STATUS_ACTIVE), whole = false)
        }
        val waiting = PendingExecutors(mockk(), { full }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))
            .forKind(PendingKind.ARRIVED)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))
        assertEquals(PendingOutcome.Transient(maybeSent = false), waiting)
        coVerify(exactly = 0) { full.reportArrival(any(), any(), any(), any(), any(), any(), any()) }

        // Listed, and active, in a full list: the arrival goes out.
        val listed = mockk<DestinationRepository>(relaxed = true) {
            coEvery { refreshDestinationsListed() } returns DestinationRepository.ListedDestinations(mapOf("dest-1" to DestinationRepository.STATUS_ACTIVE), whole = false)
            coEvery { reportArrival(any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(ReportArrivalResponse("dest-1", "e-1", 1))
        }
        val sent = PendingExecutors(mockk(), { listed }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))
            .forKind(PendingKind.ARRIVED)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))
        assertEquals(PendingOutcome.Done(), sent)
        coVerify(exactly = 1) { listed.reportArrival("dest-1", "c-1", any(), any(), any(), any(), any()) }
    }
}
