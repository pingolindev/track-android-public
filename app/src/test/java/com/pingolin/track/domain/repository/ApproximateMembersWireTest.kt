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

import com.google.gson.Gson
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.EncryptedLocationResponse
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.ApproximateMembersStore
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.BoundLocation
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * An approximate member is drawn as an area. Whether they are one comes from the server's
 * latest-locations rows, read here exactly as the server writes them (`sharing_mode`, and
 * `approximate_radius_miles` only under "approximate"; both left out when absent; the
 * `{"success":true,"data":…}` envelope) — not as this app's own DTO would write them, which is
 * how a wrong wire name hides.
 *
 * End to end: MockWebServer, a real Retrofit [TrackApi], [LocationRepository.refreshLatestLocations],
 * and the [ApproximateMembersStore] the map reads.
 */
class ApproximateMembersWireTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val ALEX = "a1e40000-0000-4000-8000-0000000000a1"
        const val ADA = "ada00000-0000-4000-8000-0000000000ad"
        const val BO = "b0000000-0000-4000-8000-0000000000b0"
        const val CY = "c0000000-0000-4000-8000-0000000000c0"
    }

    private lateinit var server: MockWebServer
    private lateinit var repository: LocationRepository
    private val store = ApproximateMembersStore.inMemory()

    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val circles: CircleRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        server = MockWebServer()
        server.startOnIpv4Loopback()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)

        every { cryptoManager.hasCircleKey(any()) } returns true
        every { cryptoManager.isCurrentKeyVersion(any(), any()) } returns true
        // Each row's sealed payload is its fix time; the place is the same for everyone.
        every { cryptoManager.decryptLocationBoundForUser(any(), any(), any()) } answers {
            val at = arg<EncryptedData>(1).ciphertext.toLong()
            BoundLocation(Location(37.3349, -122.009, null, null, null, null, at, null, null), true, 0)
        }
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"

        repository = LocationRepository(
            locationProvider = mockk<LocationProvider>(relaxed = true),
            locationDao = mockk<LocationDao>(relaxed = true),
            pendingLocationDao = mockk<PendingLocationDao>(relaxed = true),
            circleDao = mockk<CircleDao>(relaxed = true),
            privacySettingDao = mockk<PrivacySettingDao>(relaxed = true),
            tracksApi = api,
            authApi = mockk<AuthApi>(relaxed = true),
            sessionManager = sessionManager,
            tokenRefresher = com.pingolin.track.util.TokenRefresher(sessionManager) { mockk(relaxed = true) },
            cryptoManager = cryptoManager,
            uxEventBus = mockk<UxEventBus>(relaxed = true),
            queueMonitor = mockk<QueueMonitor>(relaxed = true),
            circleRepository = dagger.Lazy { circles },
            boundaryRepository = mockk<dagger.Lazy<BoundaryRepository>>(relaxed = true),
            placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true),
            currentKeyGate = CurrentKeyGate(mockk<CircleDao>(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = store,
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private var clock = 1_760_000_000_000L

    /**
     * One row as the server serialises it: `encrypted_user_id`/`encrypted_device_id` are
     * null when absent (never left out), the rest are left out when absent. [extra] is the
     * `sharing_mode` / `approximate_radius_miles` / `allow_alerts_on_me` part, verbatim.
     */
    private fun row(user: String, extra: String): String {
        clock += 1_000L
        return """{"user_id":"$user","encrypted_user_id":null,"encrypted_device_id":null,""" +
            """"day_bucket":${clock / 86_400_000L},"encrypted_payload":{"nonce":"bm9uY2U=","ciphertext":"$clock"}""" +
            (if (extra.isEmpty()) "" else ",$extra") + "}"
    }

    private suspend fun latest(vararg rows: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"locations":[${rows.joinToString(",")}]}}""")
        )
        repository.refreshLatestLocations(CIRCLE).getOrThrow()
    }

    @Test
    fun `the four shapes of a row - approximate with a radius, approximate without, exact, and an older server's`() = runTest {
        latest(
            row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":3,"allow_alerts_on_me":true"""),
            row(ADA, """"sharing_mode":"approximate""""),
            row(BO, """"sharing_mode":"exact","allow_alerts_on_me":false"""),
            row(CY, ""),
        )

        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))
        assertEquals(1, store.radiusMiles(CIRCLE, ADA))
        assertNull(store.radiusMiles(CIRCLE, BO))
        assertNull(store.radiusMiles(CIRCLE, CY))
    }

    @Test
    fun `refresh after refresh the area keeps the largest radius, then goes when the member shares exactly`() = runTest {
        latest(row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":3"""))
        latest(row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":2"""))
        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))

        // Not in this response (gone quiet): still an area.
        latest(row(ADA, """"sharing_mode":"exact""""))
        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))

        latest(row(ALEX, """"sharing_mode":"exact""""))
        assertNull(store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `a row naming this account is never taken as a member's - this phone draws itself`() = runTest {
        latest(row(ME, """"sharing_mode":"approximate","approximate_radius_miles":2"""))
        assertNull(store.radiusMiles(CIRCLE, ME))
    }

    @Test
    fun `the DTO reads the server's names, and an older server's row as no mode at all`() {
        val gson = Gson()
        val approximate = gson.fromJson(
            row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":4"""),
            EncryptedLocationResponse::class.java,
        )
        assertEquals("approximate", approximate.sharingMode)
        assertEquals(4, approximate.approximateRadiusMiles)

        val older = gson.fromJson(row(CY, ""), EncryptedLocationResponse::class.java)
        assertNull(older.sharingMode)
        assertNull(older.approximateRadiusMiles)
    }

    // ===== Leaving, being removed or the circle going forgets it =====

    @Test
    fun `leaving or deleting the circle forgets who was approximate in it`() = runTest {
        latest(row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":3"""))
        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))

        // What CircleRepository calls on leave, delete, consent withdrawn and a local delete.
        repository.forgetCircleLocations(CIRCLE)

        assertNull(store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `a circle the server no longer has (404, not in the list) is forgotten`() = runTest {
        latest(row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":3"""))
        io.mockk.coEvery { circles.isStillAMember(CIRCLE) } returns false
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}"""))

        assert(repository.refreshLatestLocations(CIRCLE).isFailure)

        assertNull(store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `a member the roster refresh drops is forgotten`() = runTest {
        latest(
            row(ALEX, """"sharing_mode":"approximate","approximate_radius_miles":3"""),
            row(ADA, """"sharing_mode":"approximate""""),
        )
        // What CircleRepository calls after every member list it stores.
        repository.pruneLocationsForMissingMembers(CIRCLE, listOf(ADA))
        assertNull(store.radiusMiles(CIRCLE, ALEX))
        assertEquals(1, store.radiusMiles(CIRCLE, ADA))
    }
}
