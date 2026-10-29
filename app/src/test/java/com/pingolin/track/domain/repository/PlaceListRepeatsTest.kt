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

import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.PlaceResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * A place id the list serves more than once, in any spelling, has every row of it dropped, never
 * the first or the last kept. The id isn't in a place's sealed fields, so a row copied from
 * another place under this account's place's id would steer its geometry. The place kept from
 * before stays as it was.
 */
class PlaceListRepeatsTest {

    private companion object {
        /** Hex letters in each id: upper case is another spelling. */
        const val HOME = "a0e0aaaa-0000-4000-8000-0000000000aa"
        const val GYM = "b0b0bbbb-0000-4000-8000-0000000000bb"
        const val GONE = "c0c0cccc-0000-4000-8000-0000000000cc"
    }

    private val api = mockk<TrackApi>()
    private val placeDao = mockk<PlaceDao>()
    private val crypto = mockk<CryptoManager>()
    private val written = mutableListOf<PlaceEntity>()
    private val deleted = mutableListOf<String>()
    private lateinit var repository: PlaceRepository

    /** A personal place's row, its sealed position at [latitude]. */
    private fun row(id: String, latitude: Double) = PlaceResponse(
        id = id,
        circleId = null,
        encryptedName = EncryptedPayload("n", "name"),
        encryptedAddress = null,
        encryptedLocation = EncryptedPayload("n", "$latitude"),
        radiusMeters = 150,
        icon = null,
        color = null,
        createdAt = "2026-10-04T00:00:00Z",
    )

    private fun serves(vararg rows: PlaceResponse) {
        coEvery { api.getPlaces(null, any(), any()) } returns Response.success(ApiResponse(success = true, data = rows.toList(), error = null))
    }

    @Before
    fun setup() {
        every { crypto.hasCircleKeyInUse(any()) } returns true
        every { crypto.hasCircleKey(any()) } returns true
        every { crypto.decryptDataV2(any(), any(), "name") } returns "Home"
        every { crypto.decryptLocation(any(), any()) } answers {
            Location(arg<EncryptedData>(1).ciphertext.toDouble(), -122.0, null, null, null, null, 0L, null, null)
        }
        coEvery { placeDao.getByIdsChunked(any()) } returns emptyList()
        coEvery { placeDao.upsertAll(any()) } answers { written += arg<List<PlaceEntity>>(0) }
        coEvery { placeDao.getAllIds() } returns listOf(HOME, GYM, GONE)
        coEvery { placeDao.deleteByIdsChunked(any()) } answers { deleted += arg<List<String>>(0); arg<List<String>>(0).size }
        repository = PlaceRepository(
            tracksApi = api,
            placeDao = placeDao,
            placeAlertDao = mockk(relaxed = true),
            cryptoManager = crypto,
            locationRepository = mockk(relaxed = true),
            sessionManager = mockk(relaxed = true),
            circleRepository = { mockk(relaxed = true) },
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        )
    }

    @Test
    fun `a place id served twice has neither row taken, whichever comes first, and the rest of the list is`() = runTest {
        for (order in listOf(listOf(37.0, 51.5), listOf(51.5, 37.0))) {
            written.clear()
            serves(row(HOME, order[0]), row(GYM, 40.0), row(HOME, order[1]))

            val places = repository.refreshPlaces(null).getOrThrow()

            assertEquals("$order", listOf(GYM), places.map { it.id })
            assertEquals("$order", listOf(GYM), written.map { it.id })
        }
    }

    @Test
    fun `in any spelling of its id`() = runTest {
        for (other in listOf(HOME.uppercase(), "{$HOME}", HOME.replace("-", ""))) {
            written.clear()
            serves(row(HOME, 37.0), row(other, 51.5), row(GYM, 40.0))

            assertEquals(other, listOf(GYM), repository.refreshPlaces(null).getOrThrow().map { it.id })
            assertEquals(other, listOf(GYM), written.map { it.id })
        }
    }

    @Test
    fun `a list of distinct ids is taken whole`() = runTest {
        serves(row(HOME, 37.0), row(GYM, 40.0))

        assertEquals(listOf(HOME, GYM), repository.refreshPlaces(null).getOrThrow().map { it.id })
        assertEquals(listOf(HOME, GYM), written.map { it.id })
    }

    @Test
    fun `a repeated id is still listed - the place kept here stays, and only an id the list dropped is pruned`() = runTest {
        serves(row(HOME, 37.0), row(HOME, 51.5), row(GYM, 40.0))

        val listed = repository.refreshPlacesWhole()

        assertEquals(listOf(GONE), deleted)
        assertTrue(written.none { it.id == HOME })
        assertTrue("never read as deleted: the whole list was read", HOME in listed.orEmpty())
    }
}
