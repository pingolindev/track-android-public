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

import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * Place changes made while this phone is behind (a share, a copy, an edit, alerts): kept with
 * nothing sealed, re-checked when run, and every copy with its own request id.
 */
class PlaceWaitingTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true) {
        every { encryptDataV2(any(), any(), any()) } returns EncryptedData("n", "c")
    }
    private var behind = true
    private val circleRepository = mockk<CircleRepository>(relaxed = true) {
        coEvery { ensureCircleKey(any()) } returns true
        coEvery { requireCurrentKey(any()) } answers { if (behind) throw KeyNotCurrentException() }
    }
    private val places = mutableMapOf<String, PlaceEntity>()
    private val placeDao = mockk<PlaceDao>(relaxed = true).also { dao ->
        coEvery { dao.getById(any()) } answers { places[firstArg()] }
        coEvery { dao.getByCircle(any()) } answers { places.values.filter { it.circleId == firstArg<String>() } }
    }
    private val dao = InMemoryPendingChangeDao()
    private var clock = 1_700_000_000_000L
    private val store = pendingChangesIn(dao, now = { clock })
    private val repository = PlaceRepository(
        tracksApi, placeDao, mockk<PlaceAlertDao>(relaxed = true), cryptoManager, mockk(relaxed = true),
        mockk<SessionManager>(relaxed = true) { every { getUserId() } returns "me" },
        { circleRepository }, store,
    )

    private fun place(id: String, circleId: String, name: String, placeKey: String? = null, shared: String? = null) =
        PlaceEntity(
            id = id, circleId = circleId, name = name, address = null, latitude = 51.5, longitude = -0.1,
            radius = 100, icon = null, color = null, createdAt = 0, updatedAt = 0, placeKey = placeKey, sharedCircles = shared,
        ).also { places[id] = it }

    private fun executors(placeRepository: PlaceRepository = repository, members: List<String> = listOf("me", "sam")) = PendingExecutors(
        mockk(), mockk(), { placeRepository }, mockk(), mockk(),
        {
            mockk<CircleRepository> { coEvery { memberIdsWhole(any()) } returns members.toSet() }
        },
        placeDao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
    )

    @Test
    fun `a share made while behind is kept, and nothing is wrapped or sent`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")

        val result = repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)

        assertEquals("Waiting to sync. Home will be shared with Family as soon as this phone catches up.", result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.wrapPlaceKey(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.sharePlace(any(), any()) }
        assertEquals(PendingInputs.SharePlace("Family", "p-1", "Home"), PendingInputs.read(dao.changes.value.single()))
    }

    @Test
    fun `a place already shared there when it runs is done, saying so`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single()
        val placeRepository = mockk<PlaceRepository> { coEvery { refreshPlacesWhole() } returns setOf("p-1") }
        place("p-1", "self", "Home", placeKey = "pk", shared = "c-1")

        val outcome = executors(placeRepository).forKind(PendingKind.SHARE_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Done("Home is already shared with Family."), outcome)
        coVerify(exactly = 0) { placeRepository.sharePlaceWithCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `shared by this change's own try whose answer was lost, it is done and says nothing`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single().copy(lastOutcome = PendingChanges.LOST_ANSWER)
        val placeRepository = mockk<PlaceRepository> { coEvery { refreshPlacesWhole() } returns setOf("p-1") }
        place("p-1", "self", "Home", placeKey = "pk", shared = "c-1")

        val outcome = executors(placeRepository).forKind(PendingKind.SHARE_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 0) { placeRepository.sharePlaceWithCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `a share that may have gone out, then unshared elsewhere, is never shared again`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single().copy(lastOutcome = PendingChanges.LOST_ANSWER)
        val placeRepository = mockk<PlaceRepository>(relaxed = true) { coEvery { refreshPlacesWhole() } returns setOf("p-1") }
        // Not shared with c-1 now: the person may have unshared it on another device.

        val outcome = executors(placeRepository).forKind(PendingKind.SHARE_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("Sharing Home wasn't done."), outcome)
        coVerify(exactly = 0) { placeRepository.sharePlaceWithCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `a share whose own record fails after its 2xx may have gone - a lost answer, and never shared again once unshared`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val id = dao.changes.value.single().id
        behind = false
        // The server stores the share; this phone's own record of it then fails (a full disk).
        coEvery { tracksApi.sharePlace("p-1", any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        coEvery { placeDao.update(any()) } throws android.database.sqlite.SQLiteFullException("database or disk is full")
        val live = io.mockk.spyk(repository)
        coEvery { live.refreshPlacesWhole() } returns setOf("p-1")
        val runner = PendingChangeRunner(
            dao, store, mockk { every { isLoggedIn() } returns true },
            mockk { coEvery { getById(any()) } answers { com.pingolin.track.data.local.entity.CircleEntity(arg(0), "Family", null, 0, 0) } },
            mockk {
                coEvery { refreshCirclesWhole() } returns null
                coEvery { holdsCurrentKey(any()) } returns true
            },
            mockk(relaxed = true), mockk(relaxed = true),
        ).also { it.executorFor = { kind -> executors(live).forKind(kind) } }

        runner.runOnce()
        coVerify(exactly = 1) { tracksApi.sharePlace("p-1", any()) }
        assertEquals("it may have gone", PendingChanges.LOST_ANSWER, dao.get(id)?.lastOutcome)

        // Its next run, the place not shared there now (unshared on another device meanwhile): never shared again.
        clock += 60_000
        runner.runOnce()
        coVerify(exactly = 1) { tracksApi.sharePlace("p-1", any()) }
        assertNull(dao.get(id))
        assertEquals("Sharing Home wasn't done.", dao.notices.value.single().line)
    }

    @Test
    fun `a share that never went out is shared as asked`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single()
        val placeRepository = mockk<PlaceRepository>(relaxed = true) {
            coEvery { refreshPlacesWhole() } returns setOf("p-1")
            coEvery { sharePlaceWithCircle(any(), any(), any(), any()) } returns Result.success(Unit)
        }
        val run = PendingRun(kept.id, kept.id)

        val outcome = executors(placeRepository).forKind(PendingKind.SHARE_PLACE)!!.run(kept, PendingInputs.read(kept)!!, run)

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 1) { placeRepository.sharePlaceWithCircle("p-1", "c-1", false, run) }
    }

    @Test
    fun `a place list that couldn't be had whole says nothing - the share waits, nothing is dropped`() = runTest {
        place("p-1", "self", "Home", placeKey = "pk")
        repository.sharePlaceWithCircle("p-1", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The refresh failed, or stopped before the last page.
        val placeRepository = mockk<PlaceRepository>(relaxed = true) { coEvery { refreshPlacesWhole() } returns null }

        val outcome = executors(placeRepository).forKind(PendingKind.SHARE_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Transient(maybeSent = false), outcome)
        coVerify(exactly = 0) { placeRepository.sharePlaceWithCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `a place missing from the whole list was deleted, whatever this phone still has`() = runTest {
        place("p-1", "c-1", "Home")
        repository.updatePlace("p-1", name = "House", keepIfBehind = true)
        val kept = dao.changes.value.single()
        // Still on this phone, but no longer on the server's whole list.
        val placeRepository = mockk<PlaceRepository>(relaxed = true) { coEvery { refreshPlacesWhole() } returns setOf("p-2") }

        val outcome = executors(placeRepository).forKind(PendingKind.EDIT_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("Your change to Home wasn't done: Home was deleted."), outcome)
    }

    @Test
    fun `copying all places while behind keeps the places there are, and each copy has its own id, the same on every run`() = runTest {
        place("p-1", "c-src", "Home")
        place("p-2", "c-src", "Work")
        val result = repository.copyAllPlacesToCircle("c-src", "c-1", keepIfBehind = true)
        assertEquals("Waiting to sync. 2 places will be copied to Family as soon as this phone catches up.", result.exceptionOrNull()?.message)
        val kept = dao.changes.value.single()
        behind = false
        val sent = mutableListOf<String?>()
        val spied = io.mockk.spyk(repository)
        coEvery { spied.copyPlaceToCircle(any(), "c-1", false, any()) } answers {
            sent += arg<PendingRun?>(3)?.requestId
            Result.success(firstArg())
        }
        val run = PendingRun(kept.id, kept.id)
        val ids = com.google.gson.Gson().fromJson(kept.seen, Array<String>::class.java).toSet()
        assertEquals(setOf("p-1", "p-2"), ids)

        spied.copyAllPlacesToCircle("c-src", "c-1", pendingRun = run, onlyPlaceIds = ids)
        spied.copyAllPlacesToCircle("c-src", "c-1", pendingRun = run, onlyPlaceIds = ids)

        val first = listOf(repository.copyRequestId(kept.id, "p-1"), repository.copyRequestId(kept.id, "p-2"))
        assertEquals(first + first, sent)
        assertTrue("never the change's own id twice", first.distinct().size == 2 && kept.id !in first)
    }

    @Test
    fun `copying all waits while a place still listed can't be read here, never skipping it quietly`() = runTest {
        place("p-1", "c-src", "Home")
        place("p-2", "c-src", "Work")
        repository.copyAllPlacesToCircle("c-src", "c-1", keepIfBehind = true)
        val kept = dao.changes.value.single()
        // p-2 is still on the server's whole list, but not readable on this phone just now.
        places.remove("p-2")
        val placeRepository = mockk<PlaceRepository>(relaxed = true) { coEvery { refreshPlacesWhole() } returns setOf("p-1", "p-2") }

        val outcome = executors(placeRepository).forKind(PendingKind.COPY_PLACES)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Transient(maybeSent = false), outcome)
        coVerify(exactly = 0) { placeRepository.copyAllPlacesToCircle(any(), any(), any(), any(), any()) }

        // Readable again: every place still listed is copied.
        place("p-2", "c-src", "Work")
        coEvery { placeRepository.copyAllPlacesToCircle(any(), any(), any(), any(), any()) } returns Result.success(2)
        val run = PendingRun(kept.id, kept.id)
        assertEquals(PendingOutcome.Done(), executors(placeRepository).forKind(PendingKind.COPY_PLACES)!!.run(kept, PendingInputs.read(kept)!!, run))
        coVerify(exactly = 1) { placeRepository.copyAllPlacesToCircle("c-src", "c-1", false, run, setOf("p-1", "p-2")) }
    }

    @Test
    fun `a copy that fails fails the run, so it is tried again`() = runTest {
        place("p-1", "c-src", "Home")
        behind = false
        val spied = io.mockk.spyk(repository)
        coEvery { spied.copyPlaceToCircle(any(), "c-1", false, any()) } returns Result.failure(java.net.SocketTimeoutException())

        val result = spied.copyAllPlacesToCircle("c-src", "c-1", pendingRun = PendingRun("ch-1", "ch-1"), onlyPlaceIds = setOf("p-1"))

        assertTrue(result.exceptionOrNull() is java.net.SocketTimeoutException)
    }

    @Test
    fun `an edit of an older circle place made while behind keeps only the changed fields, and the place as shown`() = runTest {
        place("p-1", "c-1", "Home")

        val result = repository.updatePlace("p-1", name = "House", keepIfBehind = true)

        assertEquals("Waiting to sync. Your changes to Home will be saved as soon as this phone catches up.", result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        val kept = dao.changes.value.single()
        val inputs = PendingInputs.read(kept) as PendingInputs.EditPlace
        assertEquals("House", inputs.name)
        assertNull(inputs.radius)
        assertTrue(kept.seen!!.contains("\"name\":\"Home\""))
    }

    @Test
    fun `an edit is not saved over a change made on another device meanwhile`() = runTest {
        place("p-1", "c-1", "Home")
        repository.updatePlace("p-1", name = "House", keepIfBehind = true)
        val kept = dao.changes.value.single()
        val placeRepository = mockk<PlaceRepository> { coEvery { refreshPlacesWhole() } returns setOf("p-1") }
        place("p-1", "c-1", "Home").let { places["p-1"] = it.copy(radius = 300) }

        val outcome = executors(placeRepository).forKind(PendingKind.EDIT_PLACE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("Your changes to Home weren't saved: it was changed on another device in the meantime."), outcome)
        coVerify(exactly = 0) { placeRepository.updatePlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an unchanged place gets only the changed fields`() = runTest {
        place("p-1", "c-1", "Home")
        repository.updatePlace("p-1", name = "House", keepIfBehind = true)
        val kept = dao.changes.value.single()
        val placeRepository = mockk<PlaceRepository> {
            coEvery { refreshPlacesWhole() } returns setOf("p-1")
            coEvery { updatePlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(places["p-1"]!!)
        }

        val run = PendingRun(kept.id, kept.id)
        val outcome = executors(placeRepository).forKind(PendingKind.EDIT_PLACE)!!.run(kept, PendingInputs.read(kept)!!, run)

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify { placeRepository.updatePlace("p-1", "House", null, null, null, null, null, null, null, false, run) }
    }

    @Test
    fun `a member alert on an older circle place waits, and is dropped if the member left`() = runTest {
        place("p-1", "c-1", "Home")
        val result = repository.createPlaceAlert("c-1", "p-1", "sam", keepIfBehind = true)
        assertEquals(PendingLines.NEW_ALERT, result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        val kept = dao.changes.value.single()
        assertEquals(PendingKind.MEMBER_PLACE_ALERT.name, kept.kind)
        val placeRepository = mockk<PlaceRepository>(relaxed = true) { coEvery { refreshPlacesWhole() } returns setOf("p-1") }

        val outcome = executors(placeRepository, members = listOf("me")).forKind(PendingKind.MEMBER_PLACE_ALERT)!!
            .run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("This alert wasn't done: Sam is no longer in Family."), outcome)
        coVerify(exactly = 0) { placeRepository.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }
}
