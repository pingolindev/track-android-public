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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.TrackDatabase
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.ApproximateMembersStore
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.BoundLocation
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * `/latest`'s `last_known`: where each member who turned real-time sharing off in the circle was
 * when they did. Kept apart from every other row: never in Room's cache, never among the members a
 * refresh produced, never handed to place alerts, so no inactivity, low-battery or place alert
 * ever reads a frozen position as a fresh one. Only a row the circle's CURRENT key opens, the
 * newest per member, per circle, and each read replaces its circle's.
 *
 * End to end as HiddenHistoryCacheTest is: the server's rows (MockWebServer), the real repository and
 * Room, a stand-in crypto (each payload says its fix time and, when not current, the key version).
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class FrozenLastKnownTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val BO = "b0000000-0000-4000-8000-0000000000b0"
        const val RETIRED_VERSION = 1
    }

    private lateinit var server: MockWebServer
    private lateinit var db: TrackDatabase
    private lateinit var repository: LocationRepository

    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val circleRepository: CircleRepository = mockk(relaxed = true)
    private val placeAlertEvaluator: PlaceAlertEvaluator = mockk(relaxed = true)

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
        every { cryptoManager.isCurrentKeyVersion(any(), RETIRED_VERSION) } returns false
        every { cryptoManager.decryptLocationBoundForUser(any(), any(), any()) } answers {
            val parts = arg<EncryptedData>(1).ciphertext.split(":")
            BoundLocation(
                Location(37.3349, -122.009, null, null, null, null, parts[0].toLong(), null, null),
                true,
                parts.getOrNull(1)?.toInt() ?: 0,
            )
        }
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        kotlinx.coroutines.runBlocking {
            db.circleDao().insert(CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0))
        }
        // The member list as last stored, which the latest and history reads purge against.
        coEvery { circleRepository.storedMembers(CIRCLE) } coAnswers { db.circleMemberDao().getByCircle(CIRCLE) }

        repository = LocationRepository(
            locationProvider = mockk<LocationProvider>(relaxed = true),
            locationDao = db.locationDao(),
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
            circleRepository = dagger.Lazy { circleRepository },
            boundaryRepository = mockk<dagger.Lazy<BoundaryRepository>>(relaxed = true),
            placeAlertEvaluator = placeAlertEvaluator,
            currentKeyGate = CurrentKeyGate(mockk<CircleDao>(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
        db.close()
    }

    private val now = System.currentTimeMillis()

    /** One of [user]'s rows as the server writes it, sealed at [at], opened by [version] when not the current one. */
    private fun row(at: Long, user: String = BO, version: Int? = null) =
        """{"user_id":"$user","encrypted_user_id":null,"encrypted_device_id":null,"day_bucket":${at / 86_400_000L},""" +
            """"encrypted_payload":{"nonce":"bm9uY2U=","ciphertext":"$at${version?.let { ":$it" } ?: ""}"}}"""


    private suspend fun timesOf(user: String = BO): List<Long> =
        db.locationDao().getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE).filter { it.userId == user }.map { it.timestamp }.sorted()

    private suspend fun latest(circleId: String = CIRCLE, live: List<String> = emptyList(), frozen: List<String> = emptyList()) =
        run {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"success":true,"data":{"locations":[${live.joinToString(",")}]""" +
                        (if (frozen.isEmpty()) "" else ""","last_known":[${frozen.joinToString(",")}]""") + "}}"
                )
            )
            repository.refreshLatestLocations(circleId).getOrThrow()
        }

    private fun frozenOf(circleId: String = CIRCLE) = repository.frozenLocations.value[circleId].orEmpty()

    @Test
    fun `a frozen row is kept apart, and nothing judges it`() = runTest {
        val result = latest(frozen = listOf(row(now - 3_600_000L)))

        assertEquals(listOf(now - 3_600_000L), frozenOf().map { it.timestamp })
        assertFalse("never among the members a refresh produced", BO in result.refreshedUserIds)
        assertTrue("never in the cache the evaluators read", timesOf().isEmpty())
        coVerify(exactly = 0) { placeAlertEvaluator.onLocationsPersisted(any(), any()) }
    }

    @Test
    fun `only a row the circle's current key opens is kept`() = runTest {
        latest(frozen = listOf(row(now - 3_600_000L, version = RETIRED_VERSION)))

        assertNull(repository.frozenLocations.value[CIRCLE])
    }

    @Test
    fun `the newest per member, and each read replaces the circle's`() = runTest {
        latest(frozen = listOf(row(now - 7_200_000L), row(now - 3_600_000L)))
        assertEquals(listOf(now - 3_600_000L), frozenOf().map { it.timestamp })

        latest()

        assertNull("sharing again, or gone from last_known: no frozen row stays", repository.frozenLocations.value[CIRCLE])
    }

    @Test
    fun `per circle, never by member alone`() = runTest {
        val other = "c0ffee00-0000-4000-8000-0000000000c2"
        latest(frozen = listOf(row(now - 3_600_000L)))

        latest(circleId = other, live = listOf(row(now - 60_000L)))

        assertEquals(listOf(now - 3_600_000L), frozenOf().map { it.timestamp })
        assertTrue(frozenOf(other).isEmpty())
    }

    @Test
    fun `a circle forgotten, or a sign-out, takes them`() = runTest {
        latest(frozen = listOf(row(now - 3_600_000L)))
        repository.forgetCircleLocations(CIRCLE)
        assertNull(repository.frozenLocations.value[CIRCLE])

        latest(frozen = listOf(row(now - 3_600_000L)))
        repository.wipeInMemory()
        assertTrue(repository.frozenLocations.value.isEmpty())
    }
}
