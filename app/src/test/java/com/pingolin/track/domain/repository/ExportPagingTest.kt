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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.DataExportResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.ExportMetadata
import com.pingolin.track.data.remote.dto.PlaceExport
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * Paging for the data export.
 *
 * Every section is capped server-side, so a single request would give an
 * account over any cap a short file that looks complete — only discovered at
 * restore time.
 *
 * The three ways a paging loop goes wrong, and all three are covered: it
 * duplicates a finished section, it spins on one that cannot advance, or it
 * pages with half a cursor.
 */
class ExportPagingTest {

    private val api: TrackApi = mockk()
    private val crypto: CryptoManager = mockk(relaxed = true)
    private val repo = ExportRepository(api, crypto, mockk())

    private fun meta(
        places: Boolean = false,
        devices: Boolean = false,
        placeShares: Boolean = false,
        blockedPeople: Boolean = false,
    ) = ExportMetadata(
        userId = "u", exportedDayBucket = 100, startDayBucket = 0, endDayBucket = 100,
        formatVersion = "1.3", locationCount = 0,
        devicesHasMore = devices, circlesHasMore = false, placesHasMore = places,
        checkInsHasMore = false, locationsHasMore = false, alertsHasMore = false,
        alertRulesHasMore = false, inactivityAlertsHasMore = false, privacyHasMore = false,
        placeSharesHasMore = placeShares,
        blockedPeopleHasMore = blockedPeople,
        isComplete = !(places || devices || placeShares || blockedPeople)
    )

    private fun place(n: Int) = PlaceExport(
        id = "p$n", circleId = null,
        encryptedName = EncryptedPayload("n", "c"),
        encryptedLocation = EncryptedPayload("n", "c"),
        radiusMeters = 100, createdDayBucket = 1000 - n
    )

    /** Records every request the loop makes, so the cursor assertions can look
     *  at what was actually sent rather than at a Retrofit matcher. */
    private val requests = mutableListOf<ExportRepository.PageRequest>()

    private fun fakeFetch(
        body: () -> Response<ApiResponse<DataExportResponse>>
    ): suspend (ExportRepository.PageRequest) -> Response<ApiResponse<DataExportResponse>> = { req ->
        requests += req
        body()
    }

    /** Only places are asked for, so a test controls exactly one section. */
    private suspend fun ExportRepository.pageAllPlaces(
        fetch: suspend (ExportRepository.PageRequest) -> Response<ApiResponse<DataExportResponse>>
    ) = pageAll(
        includeDevices = false, includeCircles = false, includePlaces = true,
        includeCheckIns = false, includeLocations = false, fetch = fetch
    )

    private fun device(id: String) = com.pingolin.track.data.remote.dto.DeviceExport(
        id = id, encryptedName = EncryptedPayload("", ""), deviceType = "fcm",
        dayBucket = 100, createdAtMs = 1_700_000_000_000
    )

    private fun page(
        metadata: ExportMetadata,
        places: List<PlaceExport>? = null,
        placeShares: com.google.gson.JsonArray? = null,
        blockedPeople: com.google.gson.JsonArray? = null,
    ) = Response.success(ApiResponse(success = true, data = DataExportResponse(
        metadata = metadata, devices = null, circles = null, places = places,
        checkIns = null, locations = null, placeShares = placeShares, blockedPeople = blockedPeople
    ), error = null))

    /** blocked_people rows as the server serves them: an id, the address, and when it was blocked. */
    private fun blocked(vararg rows: Pair<String, Long>) = com.google.gson.JsonArray().apply {
        rows.forEach { (id, createdAtMs) ->
            add(com.google.gson.JsonObject().apply {
                addProperty("id", id)
                addProperty("blocked_email", "$id@example.test")
                addProperty("created_at_ms", createdAtMs)
            })
        }
    }

    /** place_shares rows, which are passed through as raw JSON. */
    private fun shares(vararg pairs: Pair<String, String>) = com.google.gson.JsonArray().apply {
        pairs.forEach { (placeId, circleId) ->
            add(com.google.gson.JsonObject().apply {
                addProperty("place_id", placeId)
                addProperty("circle_id", circleId)
            })
        }
    }

    @Test
    fun `stops after one request when nothing reports more`() = runTest {
        val fetch = fakeFetch { page(meta(), listOf(place(1))) }

        val result = repo.pageAllPlaces(fetch)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().places?.size)
    }

    @Test
    fun `keeps paging until the server stops reporting more, and accumulates`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            if (call == 1) page(meta(places = true), listOf(place(1), place(2)))
            else page(meta(), listOf(place(3)))
        }

        val places = repo.pageAllPlaces(fetch).getOrThrow().places!!

        // Two requests, three places — a single request would have written two
        // and looked finished.
        assertEquals(2, call)
        assertEquals(listOf("p1", "p2", "p3"), places.map { it.id })
        // The finished section stops being requested — keep asking and the
        // server re-serves its first page and the result duplicates.
        assertTrue(requests[0].includePlaces)
        assertTrue(requests[1].includePlaces)
    }

    @Test
    fun `sends BOTH halves of the cursor, from the LAST row of the previous page`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            if (call == 1) page(meta(places = true), listOf(place(1), place(2)))
            else page(meta(), emptyList())
        }

        repo.pageAllPlaces(fetch)

        // A cursor keyed on the sort key alone skips every row sharing that key,
        // so the id must travel with it — and it must be the LAST row of the
        // previous page, not the first.
        assertNull(requests.first().cursorPlaceId)
        assertEquals("p2", requests[1].cursorPlaceId)
        assertEquals(place(2).createdDayBucket, requests[1].cursorPlaceCreatedBucket)
    }

    @Test
    fun `stops requesting a section that is finished, so it cannot be re-served and duplicated`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            // Devices finish on page 1; places keep going.
            if (call == 1) Response.success(ApiResponse(success = true, data = DataExportResponse(
                metadata = meta(places = true), devices = listOf(device("d1")), circles = null,
                places = listOf(place(1)), checkIns = null, locations = null
            ), error = null))
            else page(meta(), listOf(place(2)))
        }

        val result = repo.pageAll(
            includeDevices = true, includeCircles = false, includePlaces = true,
            includeCheckIns = false, includeLocations = false, fetch = fetch
        ).getOrThrow()

        // Keep asking a finished section and the server re-serves its first
        // page every round, and the accumulator fills with duplicates.
        assertTrue(requests[0].includeDevices)
        assertFalse(requests[1].includeDevices)
        assertTrue(requests[1].includePlaces)
        // One device in, one device out — not two.
        assertEquals(1, result.devices?.size)
        assertEquals(2, result.places?.size)
    }

    @Test
    fun `ends a section on an empty page even while the flag still claims more`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            // Always claims more, never returns a row: the cursor comes from the
            // last row received, so an empty page cannot advance it. Believing
            // the flag here is an infinite loop.
            page(meta(places = true), emptyList())
        }

        val result = repo.pageAllPlaces(fetch)

        assertEquals(1, call)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `a server that always claims more FAILS the export rather than returning a short file`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            page(meta(places = true), listOf(place(call)))
        }

        val result = repo.pageAllPlaces(fetch)

        // Returning what it had would be a file that looks whole and is not —
        // the exact failure the paging exists to prevent.
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("incomplete") == true)
    }

    @Test
    fun `place_shares pages on BOTH ids - it has no id column and no timestamp`() = runTest {
        // The odd one out among the sections added in format 1.3. Its primary
        // key is (place_id, circle_id); there is no id column and no created-at
        // tiebreaker to fall back on. Half this cursor would skip every share
        // tied on the same place, which is exactly the shares of a place that
        // reaches several circles — the rows most worth getting right.
        var call = 0
        val fetch = fakeFetch {
            call++
            if (call == 1) page(meta(placeShares = true), placeShares = shares("pl1" to "ci1", "pl1" to "ci2"))
            else page(meta(), placeShares = shares("pl2" to "ci1"))
        }

        val merged = repo.pageAll(
            includeDevices = false, includeCircles = false, includePlaces = false,
            includeCheckIns = false, includeLocations = false, fetch = fetch
        ).getOrThrow()

        assertEquals(2, call)
        assertEquals(3, merged.placeShares!!.size())
        assertNull(requests.first().cursorPlaceSharePlaceId)
        // Both halves, from the LAST row of the previous page.
        assertEquals("pl1", requests[1].cursorPlaceSharePlaceId)
        assertEquals("ci2", requests[1].cursorPlaceShareCircleId)
    }

    /**
     * "Blocked people" (format 1.9). The server serves this section ONLY WHEN ASKED FOR: an app
     * from before it does not know its cursor, and a section it cannot page must not be able to
     * make its export read as incomplete. So, unlike every other section, leaving the flag out is
     * not "yes": every request says it while the section is outstanding.
     */
    @Test
    fun `blocked people are asked for in so many words, and paged on both halves of their cursor`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            if (call == 1) page(meta(blockedPeople = true), blockedPeople = blocked("b3" to 30L, "b2" to 20L))
            else page(meta(), blockedPeople = blocked("b1" to 10L))
        }

        val merged = repo.pageAll(
            includeDevices = false, includeCircles = false, includePlaces = false,
            includeCheckIns = false, includeLocations = false, fetch = fetch
        ).getOrThrow()

        assertEquals(2, call)
        assertEquals(listOf("b3", "b2", "b1"), merged.blockedPeople!!.map { it.asJsonObject.get("id").asString })
        assertTrue(requests[0].includeBlockedPeople)
        assertNull(requests[0].cursorBlockedPersonCreatedAtMs)
        assertNull(requests[0].cursorBlockedPersonId)
        // Still wanted on the second request, with BOTH halves from the LAST row of the first page.
        assertTrue(requests[1].includeBlockedPeople)
        assertEquals(20L, requests[1].cursorBlockedPersonCreatedAtMs)
        assertEquals("b2", requests[1].cursorBlockedPersonId)
    }

    @Test
    fun `a list of nobody ends the section, and is an empty section rather than none`() = runTest {
        var call = 0
        val fetch = fakeFetch {
            call++
            page(meta(), blockedPeople = com.google.gson.JsonArray())
        }

        val merged = repo.pageAll(
            includeDevices = false, includeCircles = false, includePlaces = false,
            includeCheckIns = false, includeLocations = false, fetch = fetch
        ).getOrThrow()

        assertEquals(1, call)
        assertNotNull(merged.blockedPeople)
        assertEquals(0, merged.blockedPeople!!.size())
    }

    /**
     * Read from the server's JSON as the server writes it (`blocked_people`, each row `id`,
     * `blocked_email`, `created_at_ms`; `metadata.blocked_people_has_more`), and not from this
     * app's own objects. A name spelled differently here reads as "no such section" and "no more
     * to come", and every test that builds the objects itself still passes.
     */
    @Test
    fun `the section and its flag are read from the server's own JSON`() {
        val wire = """
            {"metadata":{"user_id":"u","exported_day_bucket":1,"start_day_bucket":0,"end_day_bucket":1,
              "format_version":"1.9","location_count":0,"blocked_people_has_more":true,"is_complete":false},
             "blocked_people":[{"id":"b2","blocked_email":"sam@example.test","created_at_ms":1759824000123}]}
        """.trimIndent()
        val page = com.google.gson.Gson().fromJson(wire, DataExportResponse::class.java)

        assertEquals(1, page.blockedPeople!!.size())
        val row = page.blockedPeople!![0].asJsonObject
        assertEquals("b2", row.get("id").asString)
        assertEquals("sam@example.test", row.get("blocked_email").asString)
        assertEquals(1759824000123L, row.get("created_at_ms").asLong)
        assertTrue(page.metadata.blockedPeopleHasMore)
        assertFalse(page.metadata.isComplete)
    }

    /** What reaches the server: the flag as the request states it, never left out. */
    @Test
    fun `the request to the server names the section's flag and its cursor`() {
        val src = java.io.File("src/main/java/com/pingolin/track/data/remote/api/TrackApi.kt").readText()
        assertTrue(src.contains("@Query(\"include_blocked_people\") includeBlockedPeople: Boolean? = null"))
        assertTrue(src.contains("@Query(\"cursor_blocked_person_created_at_ms\") cursorBlockedPersonCreatedAtMs: Long? = null"))
        assertTrue(src.contains("@Query(\"cursor_blocked_person_id\") cursorBlockedPersonId: String? = null"))
        val repoSrc = java.io.File("src/main/java/com/pingolin/track/domain/repository/ExportRepository.kt").readText()
        assertTrue(repoSrc.contains("includeBlockedPeople = req.includeBlockedPeople,"))
        assertTrue(repoSrc.contains("cursorBlockedPersonCreatedAtMs = req.cursorBlockedPersonCreatedAtMs,"))
        assertTrue(repoSrc.contains("cursorBlockedPersonId = req.cursorBlockedPersonId,"))
    }
}
