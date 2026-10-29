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
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.ApproximateMembersStore
import com.pingolin.track.util.MonitoringManager
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
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
 * A member's battery level, from the server's row to the low-battery alert. A decode that built
 * every member's row with `batteryLevel = null` would make the evaluator's
 * `latest?.batteryLevel ?: continue` skip every rule, while MonitoringManagerTest, which hands the
 * evaluator rows it builds itself, stays green.
 *
 * End to end: the latest-locations and history rows as the server writes them (MockWebServer, a
 * real Retrofit [TrackApi]), the real [LocationRepository] decode, a real in-memory Room (the
 * newest-row query is SQL), and the real [MonitoringManager.evaluateLowBattery], fed what the
 * background pass feeds it. Only the crypto is a stand-in: each row's sealed payload says its
 * fix time, the level sealed in it ("-" for none) and, when not the current one, the key version
 * that opened it.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class LowBatteryWireTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val BO = "b0000000-0000-4000-8000-0000000000b0"
        const val RETIRED_VERSION = 1
    }

    private lateinit var server: MockWebServer
    private lateinit var db: TrackDatabase
    private lateinit var repository: LocationRepository
    private lateinit var monitoring: MonitoringManager

    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)

    /** Every alert AlertHandler was asked to post. */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()

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
            val level = parts[1].toIntOrNull()?.toUByte()
            BoundLocation(
                Location(37.3349, -122.009, null, null, null, null, parts[0].toLong(), level, null),
                true,
                parts.getOrNull(2)?.toInt() ?: 0,
            )
        }
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()

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
            circleRepository = mockk<dagger.Lazy<CircleRepository>>(relaxed = true),
            boundaryRepository = mockk<dagger.Lazy<BoundaryRepository>>(relaxed = true),
            placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true),
            currentKeyGate = CurrentKeyGate(mockk<CircleDao>(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )

        val alertHandler = mockk<AlertHandler>(relaxed = true)
        every { alertHandler.handleAlert(any(), any()) } answers { posted += arg<String>(0) to arg<Map<String, String>>(1) }
        every { alertHandler.lowBatteryDedupeKey(any(), any()) } returns "batt-key"
        monitoring = MonitoringManager(
            context = ApplicationProvider.getApplicationContext(),
            tracksApi = mockk(relaxed = true),
            circleDao = db.circleDao(),
            circleMemberDao = db.circleMemberDao(),
            sessionManager = sessionManager,
            alertHandler = alertHandler,
            inactivityAlertDao = db.inactivityAlertDao(),
            locationDao = db.locationDao(),
            notificationDao = db.notificationDao(),
            localInactivityEventDao = db.localInactivityEventDao(),
            memberNotSharingSeenDao = db.memberNotSharingSeenDao(),
            alertRuleDao = db.alertRuleDao(),
            localBatteryEventDao = db.localBatteryEventDao(),
            privacySettingDao = db.privacySettingDao(),
            cryptoManager = cryptoManager,
        )
    }

    @After
    fun teardown() {
        server.shutdown()
        db.close()
    }

    private val now = System.currentTimeMillis()

    /** My rule: tell me when Bo's phone is at 20% or below. Bo is in the circle. */
    private suspend fun watchingBoAt20() {
        db.circleDao().insert(CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0))
        db.circleMemberDao().insert(CircleMemberEntity(circleId = CIRCLE, userId = BO, displayName = "Bo", role = "member", joinedAt = 0))
        db.alertRuleDao().insert(
            AlertRuleEntity(
                id = "rule-1", userId = ME, circleId = CIRCLE, targetUserId = BO,
                ruleType = AlertRuleRepository.RULE_TYPE_LOW_BATTERY, batteryThreshold = 20, dayBucket = 0,
            )
        )
    }

    /** One of Bo's rows as the server writes it, sealed at [at] with [level] ("-": none) under [version]. */
    private fun row(at: Long, level: String, version: Int? = null, extra: String = "") =
        """{"user_id":"$BO","encrypted_user_id":null,"encrypted_device_id":null,"day_bucket":${at / 86_400_000L},""" +
            """"encrypted_payload":{"nonce":"bm9uY2U=","ciphertext":"$at:$level${version?.let { ":$it" } ?: ""}"}""" +
            (if (extra.isEmpty()) "" else ",$extra") + "}"

    /** The background pass, as LocationTrackingService.runBackgroundEvaluationPass runs it: refresh, then judge. */
    private suspend fun passWith(vararg rows: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"locations":[${rows.joinToString(",")}]}}""")
        )
        val result = repository.refreshLatestLocations(CIRCLE).getOrThrow()
        assertTrue("the refresh is a sound basis for judging", result.isComplete)
        monitoring.evaluateLowBattery(mapOf(CIRCLE to result.refreshedUserIds))
    }

    private suspend fun storedRowsOfBo(): List<LocationEntity> =
        db.locationDao().getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE).filter { it.userId == BO }

    @Test
    fun `a member's level travels from the server's row to the alert`() = runTest {
        watchingBoAt20()

        passWith(row(now - 60_000L, "7"))

        assertEquals(7, storedRowsOfBo().single().batteryLevel)
        assertEquals(listOf(AlertHandler.TYPE_LOW_BATTERY), posted.map { it.first })
        assertEquals("7", posted.single().second["battery_level"])
        assertEquals("Bo", posted.single().second["user_name"])
        assertTrue("fired once, so not again", db.localBatteryEventDao().getOpen("rule-1", BO) != null)
    }

    @Test
    fun `above the line nothing is raised - the same row, only the level differs`() = runTest {
        watchingBoAt20()

        passWith(row(now - 60_000L, "80"))

        assertEquals(80, storedRowsOfBo().single().batteryLevel)
        assertTrue("80% is above the line: $posted", posted.isEmpty())
    }

    @Test
    fun `a member who doesn't share their battery here is never judged on it`() = runTest {
        watchingBoAt20()

        passWith(row(now - 60_000L, "-"))

        assertNull(storedRowsOfBo().single().batteryLevel)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `a row only an older key opened carries no level, and raises nothing`() = runTest {
        watchingBoAt20()
        passWith(row(now - 120_000L, "80"))

        // Newer, and flat, but only a retired version of the circle's key opened it:
        // history, never alert input.
        passWith(row(now - 60_000L, "5", version = RETIRED_VERSION))

        val history = storedRowsOfBo().single { it.timestamp == now - 60_000L }
        assertFalse(history.keyCurrent)
        assertNull("a history row carries no level", history.batteryLevel)
        val judged = db.locationDao().observeLatestByCircle(CIRCLE).first().single { it.userId == BO }
        assertEquals("the evaluator reads Bo's current row, not the newer history one", now - 120_000L, judged.timestamp)
        assertTrue("a history row raises nothing: $posted", posted.isEmpty())
    }

    @Test
    fun `a late point from a history page carries no level, and raises nothing`() = runTest {
        watchingBoAt20()
        passWith(row(now - 120_000L, "80"))

        // A point Bo's phone kept while it couldn't share, sent afterwards as history. It is
        // newer than Bo's last live row, so it is now Bo's newest row here.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"data":{"locations":[${row(now - 60_000L, "5", extra = "\"history_only\":true")}],""" +
                    """"history_days_allowed":365},"error":null}"""
            )
        )
        repository.fetchLocationHistory(CIRCLE).getOrThrow()
        val late = db.locationDao().observeLatestByCircle(CIRCLE).first().single { it.userId == BO }
        assertEquals("the late point is Bo's newest row", now - 60_000L, late.timestamp)
        assertNull("a late point carries no level", late.batteryLevel)

        // The next pass: the server's latest is still the 80% row.
        passWith(row(now - 120_000L, "80"))
        assertTrue("a late point raises nothing: $posted", posted.isEmpty())
    }

    @Test
    fun `a member who has turned off alerts about them is never judged on their level`() = runTest {
        watchingBoAt20()

        passWith(row(now - 60_000L, "5", extra = "\"allow_alerts_on_me\":false"))

        assertEquals(false, storedRowsOfBo().single().allowAlertsOnMe)
        assertTrue("no alert about someone who said no: $posted", posted.isEmpty())
    }
}
