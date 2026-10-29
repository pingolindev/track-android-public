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
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.DestinationResponse
import com.pingolin.track.data.remote.dto.ReportArrivalResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.domain.repository.NotYourDestinationException
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * Only this account's own destination is arrived at, cancelled or deleted from this phone,
 * and nothing is sealed or sent for any other. One whose creator isn't known yet (a row kept
 * from before the server's `user_id`) is no answer, never a no: a queued "I've arrived" then
 * waits for a list that says.
 */
class DestinationOwnershipGuardTest {

    private val me = "b1000000-0000-4000-8000-0000000000e1"
    private val other = "b1000000-0000-4000-8000-0000000000e2"
    private val circle = "b1000000-0000-4000-8000-0000000000c1"
    private val now = 1_700_000_000_000L

    /** The destinations table: what the repository reads and writes. */
    private val rows = linkedMapOf<String, DestinationEntity>()
    private val destinationDao = mockk<DestinationDao>(relaxed = true) {
        coEvery { getById(any()) } answers { rows[firstArg()] }
        coEvery { insert(any()) } answers { firstArg<DestinationEntity>().let { rows[it.id] = it } }
        coEvery { insertAll(any()) } answers { firstArg<List<DestinationEntity>>().forEach { rows[it.id] = it } }
        coEvery { getAllIds() } answers { rows.keys.toList() }
        coEvery { deleteByIdsChunked(any()) } answers { firstArg<List<String>>().count { rows.remove(it) != null } }
        coEvery { deleteById(any()) } answers { rows.remove(firstArg<String>()); Unit }
    }
    private val tracksApi = mockk<TrackApi>()
    private val cryptoManager = mockk<CryptoManager>(relaxed = true) {
        every { encryptDataV2(any(), any(), any()) } answers { EncryptedData("n", "ct:${thirdArg<String>()}") }
        every { computeEventTypeHash(any(), any()) } returns "h".repeat(64)
        // The list's rows open to the id each creator sealed: this account's.
        every { decryptDataV2(any(), any(), "user_id") } returns me
        every { decryptLocation(any(), any()) } returns Location(
            latitude = 52.5, longitude = 13.4, altitude = null, speed = null, heading = null, accuracy = null,
            timestamp = 0L, batteryLevel = null, isCharging = false,
        )
    }
    private var behind = false
    private val circleRepository = mockk<CircleRepository>(relaxed = true) {
        coEvery { requireCurrentKey(any()) } answers { if (behind) throw KeyNotCurrentException() }
        coEvery { ensurePrivacyPrimed(any()) } returns true
    }
    private val privacySettingDao = mockk<PrivacySettingDao>().also { dao ->
        coEvery { dao.get(any()) } returns PrivacySettingEntity(circleId = circle, sharingMode = "exact", updatedAt = 0)
    }
    private val pending = InMemoryPendingChangeDao()
    private val repository = DestinationRepository(
        tracksApi, destinationDao, mockk(relaxed = true), mockk(relaxed = true),
        mockk<SessionManager>(relaxed = true) { every { getUserId() } returns me },
        cryptoManager, privacySettingDao, circleRepository, pendingChangesIn(pending, now = { now }),
    )

    private fun row(id: String, sealed: String, creator: String?) = DestinationEntity(
        id = id, userId = sealed, circleId = circle, destName = "Gym", destLatitude = 52.5, destLongitude = 13.4,
        etaDayBucket = null, startedDayBucket = 0, createdDayBucket = 0, updatedDayBucket = 0, creatorId = creator,
    ).also { rows[id] = it }

    /** A destination as the server lists it, decoded as the app decodes it (Gson). */
    private fun listed(id: String, creator: String?): DestinationResponse = com.google.gson.GsonBuilder().create().fromJson(
        """{"id":"$id",${creator?.let { """"user_id":"$it",""" }.orEmpty()}"encrypted_user_id":{"nonce":"n","ciphertext":"c"},""" +
            """"circle_id":"$circle","encrypted_destination":{"nonce":"n","ciphertext":"c"},"notify_arrival":true,"status":"active",""" +
            """"started_day_bucket":0,"created_day_bucket":0,"updated_day_bucket":0}""",
        DestinationResponse::class.java,
    )

    private fun arrivalsSent() = coVerify(exactly = 0) { tracksApi.reportDestinationArrival(any(), any()) }

    @Test
    fun `a destination sealed as this account by a member is never arrived at, cancelled or deleted, and nothing is sealed`() = runTest {
        row("d-1", sealed = me, creator = other)

        assertTrue(repository.reportArrival("d-1", circle, 52.5, 13.4).exceptionOrNull() is NotYourDestinationException)
        assertTrue(repository.cancelDestination("d-1").exceptionOrNull() is NotYourDestinationException)
        assertTrue(repository.deleteDestination("d-1").exceptionOrNull() is NotYourDestinationException)

        arrivalsSent()
        coVerify(exactly = 0) { tracksApi.updateDestination(any(), any()) }
        coVerify(exactly = 0) { tracksApi.deleteDestination(any()) }
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.encryptLocation(any(), any()) }
        // No new words: the person reads the caller's own line.
        assertEquals("Couldn't cancel that trip just now.", NotYourDestinationException().plainText("Couldn't cancel that trip just now."))
    }

    @Test
    fun `one whose creator isn't known yet, or with no row here, is no answer, and nothing goes`() = runTest {
        row("d-1", sealed = me, creator = null)
        for (id in listOf("d-1", "d-none")) {
            assertTrue(id, repository.reportArrival(id, circle, 52.5, 13.4).exceptionOrNull() is CouldNotCheck)
            assertTrue(id, repository.cancelDestination(id).exceptionOrNull() is CouldNotCheck)
            assertTrue(id, repository.deleteDestination(id).exceptionOrNull() is CouldNotCheck)
        }
        // Behind, it isn't kept to sync either.
        behind = true
        assertTrue(repository.reportArrival("d-1", circle, null, null, keepIfBehind = true).exceptionOrNull() is CouldNotCheck)
        assertTrue(pending.changes.value.isEmpty())

        arrivalsSent()
        coVerify(exactly = 0) { tracksApi.updateDestination(any(), any()) }
        coVerify(exactly = 0) { tracksApi.deleteDestination(any()) }
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
    }

    @Test
    fun `this account's own still goes`() = runTest {
        row("d-1", sealed = me.uppercase(), creator = me)
        coEvery { tracksApi.reportDestinationArrival("d-1", any()) } returns
            Response.success(ApiResponse(true, ReportArrivalResponse("d-1", "e-1", 1), null))
        coEvery { tracksApi.updateDestination("d-1", any()) } returns Response.success(ApiResponse(true, listed("d-1", me), null))
        coEvery { tracksApi.deleteDestination("d-1") } returns Response.success(ApiResponse(true, Unit, null))

        assertTrue(repository.cancelDestination("d-1").isSuccess)
        assertTrue(repository.reportArrival("d-1", circle, 52.5, 13.4).isSuccess)
        row("d-1", sealed = me, creator = me)
        assertTrue(repository.deleteDestination("d-1").isSuccess)

        coVerify(exactly = 1) { tracksApi.reportDestinationArrival("d-1", any()) }
        coVerify(exactly = 1) { tracksApi.updateDestination("d-1", any()) }
        coVerify(exactly = 1) { tracksApi.deleteDestination("d-1") }
    }

    @Test
    fun `a destination this account just made is its own, by the server's answer`() = runTest {
        coEvery { tracksApi.createDestination(any()) } returns Response.success(ApiResponse(true, listed("d-new", me), null))

        val made = repository.createDestination(circle, "Gym", 52.5, 13.4).getOrThrow()

        assertTrue(made.isMine(me))
        assertTrue(rows.getValue("d-new").isMine(me))
    }

    /**
     * An "I've arrived" kept while behind, and its row then kept from before the server's
     * `user_id`. A list with no creator: nothing is sent and it waits. The next one naming this
     * account: it goes. One naming another account: dropped, nothing sent.
     */
    @Test
    fun `a kept arrival waits while its creator is unknown, goes once a list names this account, and is dropped once one names another`() = runTest {
        row("d-1", sealed = me, creator = me)
        behind = true
        repository.reportArrival("d-1", circle, null, null, occurredAtMs = now - 60_000, keepIfBehind = true)
        val kept = pending.changes.value.single()
        val inputs = PendingInputs.read(kept)!!
        behind = false
        // The upgrade: the row is kept, with no creator yet.
        row("d-1", sealed = me, creator = null)
        val executors = PendingExecutors(mockk(), { repository }, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true))
        val run = PendingRun(kept.id, kept.id)
        coEvery { tracksApi.reportDestinationArrival("d-1", any()) } returns
            Response.success(ApiResponse(true, ReportArrivalResponse("d-1", "e-1", 1), null))

        // 1. The server's list says nothing of the creator: no answer, so it waits.
        coEvery { tracksApi.getDestinations() } returns Response.success(ApiResponse(true, listOf(listed("d-1", null)), null))
        assertEquals(PendingOutcome.Transient(maybeSent = false), executors.forKind(PendingKind.ARRIVED)!!.run(kept, inputs, run))
        arrivalsSent()

        // 2. The next list names this account: it goes.
        coEvery { tracksApi.getDestinations() } returns Response.success(ApiResponse(true, listOf(listed("d-1", me)), null))
        assertEquals(PendingOutcome.Done(), executors.forKind(PendingKind.ARRIVED)!!.run(kept, inputs, run))
        coVerify(exactly = 1) { tracksApi.reportDestinationArrival("d-1", any()) }

        // 3. Another account, as the list names it: dropped, and nothing more is sent.
        row("d-1", sealed = me, creator = null)
        coEvery { tracksApi.getDestinations() } returns Response.success(ApiResponse(true, listOf(listed("d-1", other)), null))
        val dropped = executors.forKind(PendingKind.ARRIVED)!!.run(kept, inputs, run)
        assertTrue("$dropped", dropped is PendingOutcome.Dropped)
        coVerify(exactly = 1) { tracksApi.reportDestinationArrival("d-1", any()) }
    }
}
