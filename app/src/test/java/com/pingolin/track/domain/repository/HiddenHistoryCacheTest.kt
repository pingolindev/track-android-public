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
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.LocationEntity
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A viewer's copies of a member who keeps their location history from the circle (the member
 * list's `history_sharing` "off"): only their newest position stays on this phone, and none
 * while their real-time sharing is off too. Level-triggered: every latest read, history read and
 * member list read, so what an older build stored goes too, and each position then replaces
 * theirs instead of adding a trail that would rebuild their history here.
 *
 * End to end as LowBatteryWireTest is: the rows as the server writes them (MockWebServer, a real
 * Retrofit [TrackApi]), the real [LocationRepository], and a real in-memory Room. Only the crypto
 * is a stand-in: each row's sealed payload says its fix time and, when not the current one, the
 * key version that opened it.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class HiddenHistoryCacheTest {

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
            placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true),
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

    private suspend fun bo(history: String? = "off", sharing: String? = null) =
        db.circleMemberDao().insert(
            CircleMemberEntity(
                circleId = CIRCLE, userId = BO, displayName = "Bo", role = "member", joinedAt = 0,
                locationSharing = sharing, historySharing = history,
            )
        )

    /** One of [user]'s rows as the server writes it, sealed at [at], opened by [version] when not the current one. */
    private fun row(at: Long, user: String = BO, version: Int? = null) =
        """{"user_id":"$user","encrypted_user_id":null,"encrypted_device_id":null,"day_bucket":${at / 86_400_000L},""" +
            """"encrypted_payload":{"nonce":"bm9uY2U=","ciphertext":"$at${version?.let { ":$it" } ?: ""}"}}"""

    private suspend fun latestWith(vararg rows: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"locations":[${rows.joinToString(",")}]}}""")
        )
        repository.refreshLatestLocations(CIRCLE).getOrThrow()
    }

    private suspend fun stored(user: String = BO, keyCurrent: Boolean = true, vararg at: Long) {
        db.locationDao().insertAll(at.map {
            LocationEntity(
                circleId = CIRCLE, userId = user, deviceId = "d-$user", latitude = 1.0, longitude = 2.0,
                altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null,
                timestamp = it, keyCurrent = keyCurrent,
            )
        })
    }

    private suspend fun timesOf(user: String = BO): List<Long> =
        db.locationDao().getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE).filter { it.userId == user }.map { it.timestamp }.sorted()

    @Test
    fun `each latest read replaces the position of a member hiding their history, never adds to it`() = runTest {
        bo()

        latestWith(row(now - 120_000L))
        latestWith(row(now - 60_000L))

        assertEquals(listOf(now - 60_000L), timesOf())
    }

    @Test
    fun `a member showing their history keeps every position, the control`() = runTest {
        bo(history = null)

        latestWith(row(now - 120_000L))
        latestWith(row(now - 60_000L))

        assertEquals(listOf(now - 120_000L, now - 60_000L), timesOf())
    }

    @Test
    fun `what an older build kept of them goes at the next read, but for the newest`() = runTest {
        bo()
        stored(BO, true, now - 3_600_000L, now - 1_800_000L, now - 600_000L)

        latestWith()

        assertEquals(listOf(now - 600_000L), timesOf())
    }

    @Test
    fun `with real-time sharing off too nothing of theirs stays`() = runTest {
        // The server sends them in neither array, so they're drawn nowhere.
        bo(sharing = "off")
        stored(BO, true, now - 1_800_000L, now - 600_000L)

        latestWith(row(now - 60_000L, user = ME))

        assertEquals(emptyList<Long>(), timesOf())
    }

    @Test
    fun `the position kept is the one the map shows, never a newer one only an older key opened`() = runTest {
        bo()
        stored(BO, true, now - 1_800_000L)
        stored(BO, false, now - 600_000L)

        latestWith()

        assertEquals(listOf(now - 1_800_000L), timesOf())
    }

    @Test
    fun `this account's own positions are never purged`() = runTest {
        db.circleMemberDao().insert(
            CircleMemberEntity(circleId = CIRCLE, userId = ME, displayName = "Me", role = "member", joinedAt = 0, historySharing = "off")
        )
        stored(ME, true, now - 1_800_000L, now - 600_000L)

        latestWith()

        assertEquals(listOf(now - 1_800_000L, now - 600_000L), timesOf(ME))
    }

    @Test
    fun `a history read in flight while they hid it leaves no trail of theirs`() = runTest {
        bo()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"locations":[${row(now - 7_200_000L)},${row(now - 3_600_000L)},${row(now - 60_000L)}],""" +
                    """"history_days_allowed":365},"error":null}"""
            )
        )

        repository.fetchLocationHistory(CIRCLE).getOrThrow()

        assertEquals(listOf(now - 60_000L), timesOf())
    }

    @Test
    fun `a member list read purges as well`() = runTest {
        bo()
        stored(BO, true, now - 1_800_000L, now - 600_000L)

        val gone = repository.purgeHiddenHistory(CIRCLE, db.circleMemberDao().getByCircle(CIRCLE))

        assertEquals(1, gone)
        assertEquals(listOf(now - 600_000L), timesOf())
    }

    @Test
    fun `a member list read is where both off goes, with no position to read`() = runTest {
        bo(sharing = "off")
        stored(BO, true, now - 600_000L)

        repository.purgeHiddenHistory(CIRCLE, db.circleMemberDao().getByCircle(CIRCLE))

        assertTrue(timesOf().isEmpty())
    }
}
