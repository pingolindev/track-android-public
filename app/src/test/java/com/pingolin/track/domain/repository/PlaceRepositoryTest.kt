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

import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * Unit tests for PlaceRepository focusing on:
 * - isInsidePlace boundary logic at exact radius
 * - isInsidePlace with point very far away
 * - Constants
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceRepositoryTest {

    @MockK
    private lateinit var tracksApi: TrackApi

    @MockK
    private lateinit var placeDao: PlaceDao

    @MockK
    private lateinit var placeAlertDao: PlaceAlertDao

    @MockK
    private lateinit var cryptoManager: CryptoManager

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var sessionManager: com.pingolin.track.util.SessionManager

    @MockK
    private lateinit var circleRepositoryMock: CircleRepository

    private lateinit var repository: PlaceRepository

    private fun makePlace(lat: Double, lng: Double, radius: Int) = PlaceEntity(
        id = "p-1", circleId = "self", name = "X",
        address = null, latitude = lat, longitude = lng,
        radius = radius, icon = null, color = null,
        createdAt = 0, updatedAt = 0
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)

        repository = PlaceRepository(
            tracksApi = tracksApi,
            placeDao = placeDao,
            placeAlertDao = placeAlertDao,
            cryptoManager = cryptoManager,
            locationRepository = locationRepository,
            sessionManager = sessionManager,
            // Lazy dep for the circle-key self-heal. The guards under test all
            // arrange hasCircleKey = true, so the heal is never reached; a
            // relaxed mock keeps that explicit rather than accidental.
            circleRepository = { circleRepositoryMock },
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        )
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `isInsidePlace true when point equals center`() {
        val place = makePlace(37.7749, -122.4194, 100)
        assertTrue(repository.isInsidePlace(place, 37.7749, -122.4194))
    }

    @Test
    fun `isInsidePlace true when well within radius`() {
        val place = makePlace(37.7749, -122.4194, 1000)
        // ~10m away
        assertTrue(repository.isInsidePlace(place, 37.77499, -122.4194))
    }

    @Test
    fun `isInsidePlace false when clearly outside radius`() {
        val place = makePlace(37.7749, -122.4194, 100)
        // Far-away point
        assertFalse(repository.isInsidePlace(place, 40.7128, -74.0060))
    }

    @Test
    fun `isInsidePlace true at or just under radius`() {
        val place = makePlace(0.0, 0.0, 200)
        // Move ~100m north (latitude). 1 deg lat ≈ 111 km. 100m ≈ 0.0009 deg.
        val inside = repository.isInsidePlace(place, 0.0009, 0.0)
        assertTrue("Point ~100m away should be inside 200m radius", inside)
    }

    @Test
    fun `isInsidePlace false just outside radius`() {
        val place = makePlace(0.0, 0.0, 100)
        // Point ~200m away (0.0018 deg lat) should be outside
        val outside = repository.isInsidePlace(place, 0.0018, 0.0)
        assertFalse("Point ~200m away should be outside 100m radius", outside)
    }

    @Test
    fun `isInsidePlace with zero radius only matches exact center`() {
        val place = makePlace(0.0, 0.0, 0)
        assertTrue(repository.isInsidePlace(place, 0.0, 0.0))
        assertFalse(repository.isInsidePlace(place, 0.0001, 0.0))
    }

    // ===== refreshPlaceAlerts prune =====
    //
    // An alert deleted or replaced server-side is pruned from the local
    // place_alerts table. Kept in Room, a phantom subscription would still
    // drive local evaluation and, while the alert's routing circle is still
    // an emit target, still contribute that circle to the crossing emit.

    private fun alertDto(id: String, placeId: String = "p-1") =
        com.pingolin.track.data.remote.dto.PlaceAlertResponse(
            id = id,
            placeId = placeId,
            circleId = null,
            userId = "user-me",
            encryptedTargetUserId = com.pingolin.track.data.remote.dto.EncryptedPayload(
                nonce = "n", ciphertext = "c"
            ),
            alertOnArrival = true,
            alertOnDeparture = false,
            activeDays = null,
            activeStartTime = null,
            activeEndTime = null,
            isOvernight = false,
            notifyPush = true,
            notifyEmail = false,
            notifySms = false,
            isRecurring = true,
            isActive = true,
            expiresAt = null,
            dayBucket = 0,
        )

    private fun stubAlertsResponse(vararg dtos: com.pingolin.track.data.remote.dto.PlaceAlertResponse) {
        coEvery { tracksApi.getPlaceAlerts(any()) } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(
                success = true, data = dtos.toList(), error = null
            )
        )
        coEvery { placeDao.getById(any()) } returns makePlace(0.0, 0.0, 100).copy(placeKey = "pk")
        every { cryptoManager.decryptWithPlaceKeyV2(any(), any(), any()) } returns "user-me"
        coEvery { placeAlertDao.insertAll(any()) } just Runs
        coEvery { placeAlertDao.deleteByIdsChunked(any()) } just Runs
    }

    @Test
    fun `refreshPlaceAlerts prunes local alerts the server no longer returns`() = kotlinx.coroutines.test.runTest {
        stubAlertsResponse(alertDto("alert-live"))
        coEvery { placeAlertDao.getAllIds() } returns listOf("alert-live", "alert-stale")

        repository.refreshPlaceAlerts()

        coVerify(exactly = 1) { placeAlertDao.deleteByIdsChunked(listOf("alert-stale")) }
    }

    @Test
    fun `refreshPlaceAlerts prunes nothing when local matches server`() = kotlinx.coroutines.test.runTest {
        stubAlertsResponse(alertDto("alert-live"))
        coEvery { placeAlertDao.getAllIds() } returns listOf("alert-live")

        repository.refreshPlaceAlerts()

        coVerify(exactly = 0) { placeAlertDao.deleteByIdsChunked(any()) }
    }

    @Test
    fun `refreshPlaceAlerts retains an alert the server returned but we could not decrypt`() =
        kotlinx.coroutines.test.runTest {
            // Prune against SERVER truth, not the decrypted subset: a transiently
            // missing key must not silently delete the user's alert.
            stubAlertsResponse(alertDto("alert-live"))
            every { cryptoManager.decryptWithPlaceKeyV2(any(), any(), any()) } throws
                RuntimeException("key missing")
            coEvery { placeAlertDao.getAllIds() } returns listOf("alert-live")

            repository.refreshPlaceAlerts()

            coVerify(exactly = 0) { placeAlertDao.deleteByIdsChunked(any()) }
        }

    @Test
    fun `refreshPlaceAlerts prunes nothing when the page is full`() = kotlinx.coroutines.test.runTest {
        // GET /place-alerts is paginated (server clamps limit to 1..100). A page
        // this full means there may be more alerts we never saw, so pruning would
        // delete every alert past the page boundary — the same completeFetch guard
        // refreshPlaces uses.
        val fullPage = (1..PlaceRepository.PLACE_ALERT_PAGE_LIMIT)
            .map { alertDto("alert-$it") }
            .toTypedArray()
        stubAlertsResponse(*fullPage)
        coEvery { placeAlertDao.getAllIds() } returns listOf("alert-1", "alert-not-on-this-page")

        repository.refreshPlaceAlerts()

        coVerify(exactly = 0) { placeAlertDao.deleteByIdsChunked(any()) }
    }

    @Test
    fun `refreshPlaceAlerts prunes nothing when the fetch fails`() = kotlinx.coroutines.test.runTest {
        coEvery { tracksApi.getPlaceAlerts(any()) } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse<List<com.pingolin.track.data.remote.dto.PlaceAlertResponse>>(
                success = false, data = null, error = "boom"
            )
        )
        coEvery { placeAlertDao.getAllIds() } returns listOf("alert-live")
        coEvery { placeAlertDao.deleteByIdsChunked(any()) } just Runs

        repository.refreshPlaceAlerts()

        coVerify(exactly = 0) { placeAlertDao.deleteByIdsChunked(any()) }
    }

    // ===== an alert's end date =====
    //
    // The server writes expires_at in RFC 3339 form: "+00:00", fractions when there are
    // some. Instant.parse reads only "Z" before Android 14, and a parse that fell back to
    // the sync time would store every alert with an end date as already over on Android
    // 8-13.

    /** A place alert as the server serialises it. */
    private fun serverAlert(id: String, expiresAt: String?): com.pingolin.track.data.remote.dto.PlaceAlertResponse {
        val end = expiresAt?.let { "\"$it\"" } ?: "null"
        val json = """{"id":"$id","place_id":"p-1","user_id":"user-me",""" +
            """"circle_id":"00000000-0000-0000-0000-000000000000",""" +
            """"encrypted_target_user_id":{"nonce":"6e","ciphertext":"63"},""" +
            """"alert_on_arrival":true,"alert_on_departure":false,"active_days":null,""" +
            """"active_start_time":null,"active_end_time":null,"is_overnight":false,""" +
            """"client_only":true,"suppressed_by_target":false,"notify_push":true,""" +
            """"notify_email":false,"notify_sms":false,"is_recurring":true,"is_active":true,""" +
            """"expires_at":$end,"day_bucket":20375}"""
        return com.google.gson.Gson().fromJson(json, com.pingolin.track.data.remote.dto.PlaceAlertResponse::class.java)
    }

    @Test
    fun `an alert's end date in each form the server writes it decides whether it is live`() = kotlinx.coroutines.test.runTest {
        val now = 1_791_968_400_000L // 2026-10-14T09:00:00Z
        val day = 86_400_000L
        stubAlertsResponse(
            serverAlert("a-plus", "2026-10-15T09:00:00+00:00"),
            serverAlert("a-frac", "2026-10-15T09:00:00.123456+00:00"),
            serverAlert("a-z", "2026-10-15T09:00:00Z"),
            serverAlert("a-past", "2026-10-13T09:00:00+00:00"),
            serverAlert("a-bad", "next week"),
            serverAlert("a-none", null),
        )
        coEvery { placeAlertDao.getAllIds() } returns emptyList()
        val stored = slot<List<com.pingolin.track.data.local.entity.PlaceAlertEntity>>()
        coEvery { placeAlertDao.insertAll(capture(stored)) } just Runs

        repository.refreshPlaceAlerts()

        val byId = stored.captured.associateBy { it.id }
        assertEquals(
            mapOf(
                "a-plus" to now + day, "a-frac" to now + day + 123, "a-z" to now + day,
                "a-past" to now - day, "a-bad" to null, "a-none" to null,
            ),
            byId.mapValues { it.value.expiresAt },
        )
        assertEquals(
            "tomorrow is live, yesterday is over, unreadable is no end date (never the sync time)",
            mapOf("a-plus" to true, "a-frac" to true, "a-z" to true, "a-past" to false, "a-bad" to true, "a-none" to true),
            byId.mapValues { com.pingolin.track.domain.placealerts.PlaceAlertRules.isAlertLiveAt(it.value, now) },
        )
    }

    @Test
    fun `constants are correct`() {
        assertEquals("self", PlaceRepository.PERSONAL_CIRCLE_ID)
        assertEquals("00000000-0000-0000-0000-000000000000", PlaceRepository.PERSONAL_CIRCLE_UUID)
    }

    @Test
    fun `isInsidePlace handles very large radius`() {
        val place = makePlace(0.0, 0.0, 1_000_000) // 1000km radius
        assertTrue(repository.isInsidePlace(place, 1.0, 1.0))
    }

    @Test
    fun `isInsidePlace symmetric across points`() {
        val place1 = makePlace(37.7749, -122.4194, 1000)
        val place2 = makePlace(37.7750, -122.4200, 1000)
        // Approximately same distance in both directions
        val inside1to2 = repository.isInsidePlace(place1, place2.latitude, place2.longitude)
        val inside2to1 = repository.isInsidePlace(place2, place1.latitude, place1.longitude)
        assertEquals(inside1to2, inside2to1)
    }

    // ===== the schedule's timezone reaches the wire =====
    //
    // The server's zone for an alert subscription defaults to 'UTC', and the
    // server judges a "08:00-17:00" window in that zone while this device
    // judges the identical row in ZoneId.systemDefault(): a zone nobody sends
    // gives two different answers. So the test that matters is not "the DTO
    // can carry it" (PlaceAlertTimezoneContractTest covers the wire name and
    // value) but "the repository puts it there". Both calls are driven to a
    // FAILING response on purpose: the request is captured before the result
    // is examined, and a failure keeps the assertion off refreshPlaceAlerts
    // and its own stubbing.

    private fun stubCreateCapture(slot: CapturingSlot<com.pingolin.track.data.remote.dto.CreatePlaceAlertRequest>) {
        coEvery { placeDao.getById(any()) } returns makePlace(0.0, 0.0, 100).copy(placeKey = "pk")
        every { cryptoManager.encryptWithPlaceKeyV2(any(), any(), any()) } returns
            uniffi.pingolin_mobile.EncryptedData("nonce", "ciphertext")
        coEvery { tracksApi.createPlaceAlert(capture(slot)) } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse<String>(
                success = false, data = null, error = "stop here"
            )
        )
    }

    @Test
    fun `creating an alert names the zone its schedule was written in`() =
        kotlinx.coroutines.test.runTest {
            val request = slot<com.pingolin.track.data.remote.dto.CreatePlaceAlertRequest>()
            stubCreateCapture(request)

            repository.createPlaceAlert(
                circleId = "circle-1",
                placeId = "p-1",
                targetUserId = "user-1",
                activeStartTime = "08:00",
                activeEndTime = "17:00",
            )

            assertEquals(
                "the created subscription must carry this device's zone, not the column default",
                java.time.ZoneId.systemDefault().id,
                request.captured.timezone,
            )
        }

    @Test
    fun `a circle-wide alert names the zone too`() = kotlinx.coroutines.test.runTest {
        // The second construction site. It is a separate CreatePlaceAlertRequest
        // literal, so it can be forgotten independently of the first.
        val request = slot<com.pingolin.track.data.remote.dto.CreatePlaceAlertRequest>()
        stubCreateCapture(request)

        repository.createCircleWidePlaceAlert(
            circleId = "circle-1",
            placeId = "p-1",
            activeStartTime = "08:00",
            activeEndTime = "17:00",
        )

        assertEquals(
            java.time.ZoneId.systemDefault().id,
            request.captured.timezone,
        )
    }

    @Test
    fun `editing an alert re-sends the zone`() = kotlinx.coroutines.test.runTest {
        // A subscriber who moves zones and retimes their window is otherwise still
        // judged server-side in the zone they first created the alert in.
        val request = slot<com.pingolin.track.data.remote.dto.UpdatePlaceAlertRequest>()
        coEvery { placeAlertDao.getById(any()) } returns null
        coEvery { tracksApi.updatePlaceAlert(any(), capture(request)) } returns
            retrofit2.Response.error(
                500,
                "".toResponseBody(null),
            )

        repository.updatePlaceAlert(
            alertId = "alert-1",
            alertOnArrival = true,
            alertOnDeparture = false,
            activeStartTime = "08:00",
            activeEndTime = "17:00",
        )

        assertEquals(
            java.time.ZoneId.systemDefault().id,
            request.captured.timezone,
        )
    }
}
