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
import com.pingolin.track.data.local.entity.InactivityAlertEntity
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
 * A member who turns "Allow alerts on me" off sends no fix, and the server serves their last fix
 * again with the new "no" beside it (the server serves `allow_alerts_on_me` as it is now). This
 * phone's replay guard drops that row for not being newer; if the consent went with it, the
 * inactivity and battery passes would go on judging the quiet member on the earlier "yes": "may need
 * attention", about someone who has said no. Going quiet is exactly when an inactivity rule goes
 * off.
 *
 * So a dropped row's "no" lowers the row the circle holds, one way only, as on iOS: a "yes", or no
 * word, from a dropped row raises nothing; only the member's next fix can.
 *
 * End to end, as LowBatteryWireTest: the rows as the server writes them (MockWebServer and a real
 * Retrofit [TrackApi]), the real [LocationRepository] decode and replay guard, a real in-memory
 * Room, and the real [MonitoringManager] passes fed what the background pass feeds them. Only the
 * crypto is a stand-in: each row's sealed payload says its fix time and the level sealed in it.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class ServedNoOutlivesTheReplayDropTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
        const val BO = "b0000000-0000-4000-8000-0000000000b0"
        const val CY = "c0000000-0000-4000-8000-0000000000c0"
        const val HOUR = 3_600_000L
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
        trackApi = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TrackApi::class.java)

        every { cryptoManager.hasCircleKey(any()) } returns true
        every { cryptoManager.isCurrentKeyVersion(any(), any()) } returns true
        every { cryptoManager.decryptLocationBoundForUser(any(), any(), any()) } answers {
            val parts = arg<EncryptedData>(1).ciphertext.split(":")
            BoundLocation(
                Location(37.3349, -122.009, null, null, null, null, parts[0].toLong(), parts[1].toIntOrNull()?.toUByte(), null),
                true,
                0,
            )
        }
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"

        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = newProcess()

        val alertHandler = mockk<AlertHandler>(relaxed = true)
        every { alertHandler.handleAlert(any(), any()) } answers { posted += arg<String>(0) to arg<Map<String, String>>(1) }
        every { alertHandler.lowBatteryDedupeKey(any(), any()) } returns "batt-key"
        every { alertHandler.inactivityDedupeKey(any(), any()) } returns "inact-key"
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

    private lateinit var trackApi: TrackApi

    /**
     * The real [LocationRepository] over the same store, as a process would make it: a new one starts
     * with an empty replay guard, the guard being in memory.
     */
    private fun newProcess() = LocationRepository(
        locationProvider = mockk<LocationProvider>(relaxed = true),
        locationDao = db.locationDao(),
        pendingLocationDao = mockk<PendingLocationDao>(relaxed = true),
        circleDao = mockk<CircleDao>(relaxed = true),
        privacySettingDao = mockk<PrivacySettingDao>(relaxed = true),
        tracksApi = trackApi,
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

    @After
    fun teardown() {
        server.shutdown()
        db.close()
    }

    private val now = System.currentTimeMillis()

    /** Bo's one fix, two hours old: past my one-hour inactivity rule, and at 10%, under my 20% one. */
    private val quietSince = now - 2 * HOUR

    /** My rules about Bo: tell me when Bo goes quiet for an hour, and when Bo's phone is at 20% or below. */
    private suspend fun watchingBo() {
        db.circleDao().insert(CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0))
        db.circleMemberDao().insert(CircleMemberEntity(circleId = CIRCLE, userId = BO, displayName = "Bo", role = "member", joinedAt = 0))
        db.inactivityAlertDao().insert(
            InactivityAlertEntity(id = "rule-quiet", userId = ME, circleId = CIRCLE, targetUserId = BO, timeoutHours = 1, dayBucket = 0)
        )
        db.alertRuleDao().insert(
            AlertRuleEntity(
                id = "rule-batt", userId = ME, circleId = CIRCLE, targetUserId = BO,
                ruleType = AlertRuleRepository.RULE_TYPE_LOW_BATTERY, batteryThreshold = 20, dayBucket = 0,
            )
        )
    }

    /** One of [who]'s rows as the server writes it, sealed at [at] with [level], [consent] beside it (null: no word). */
    private fun row(at: Long, consent: Boolean?, level: String = "10", who: String = BO) =
        """{"user_id":"$who","encrypted_user_id":null,"encrypted_device_id":null,"day_bucket":${at / 86_400_000L},""" +
            """"encrypted_payload":{"nonce":"bm9uY2U=","ciphertext":"$at:$level"}""" +
            (consent?.let { ""","allow_alerts_on_me":$it""" } ?: "") + "}"

    /** A refresh, as the background pass makes it, and the refresh's word on whom it saw. */
    private suspend fun refreshWith(vararg rows: String): Set<String> {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"success":true,"data":{"locations":[${rows.joinToString(",")}]}}""")
        )
        val result = repository.refreshLatestLocations(CIRCLE).getOrThrow()
        assertTrue("the refresh is a sound basis for judging", result.isComplete)
        return result.refreshedUserIds
    }

    /** The background pass: refresh, then judge, as LocationTrackingService.runBackgroundEvaluationPass runs it. */
    private suspend fun passWith(vararg rows: String): Set<String> {
        val refreshed = refreshWith(*rows)
        monitoring.evaluateInactivity(mapOf(CIRCLE to refreshed))
        monitoring.evaluateLowBattery(mapOf(CIRCLE to refreshed))
        return refreshed
    }

    private suspend fun heldRowOfBo(): LocationEntity =
        db.locationDao().observeLatestByCircle(CIRCLE).first().single { it.userId == BO }

    @Test
    fun `a quiet member who turns alerts on them off is told on no more, though their last fix comes back unchanged`() = runTest {
        watchingBo()
        // Bo's last fix, with alerts on Bo allowed; this phone takes it.
        refreshWith(row(quietSince, consent = true))
        // Bo turns "Allow alerts on me" off and stays quiet: the same fix again, with the new "no".
        val refreshed = passWith(row(quietSince, consent = false))

        assertEquals(false, heldRowOfBo().allowAlertsOnMe)
        assertTrue("Bo still counts as refreshed: the drop isn't silence", BO in refreshed)
        assertTrue("nothing said about Bo: $posted", posted.isEmpty())
    }

    @Test
    fun `the paired case, a yes served again, is still judged, so the test above can't pass for another reason`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true))
        passWith(row(quietSince, consent = true))

        assertEquals(true, heldRowOfBo().allowAlertsOnMe)
        assertEquals(
            setOf(AlertHandler.TYPE_OFFLINE, AlertHandler.TYPE_LOW_BATTERY),
            posted.map { it.first }.toSet(),
        )
    }

    @Test
    fun `one way only - a dropped yes raises nothing`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = false))
        // The server alone says yes again, with no newer fix from Bo's phone: nothing changes.
        passWith(row(quietSince, consent = true))

        assertEquals(false, heldRowOfBo().allowAlertsOnMe)
        assertTrue("nothing said about Bo: $posted", posted.isEmpty())
    }

    @Test
    fun `after a restart the same fix served with a yes keeps the no held - only a new fix carries its word`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true))
        refreshWith(row(quietSince, consent = false))
        assertEquals(false, heldRowOfBo().allowAlertsOnMe)

        // A new process: the replay guard is empty, so the same fix is taken, not dropped, and
        // REPLACE would write the served word over the "no".
        repository = newProcess()
        passWith(row(quietSince, consent = true))
        assertEquals(false, heldRowOfBo().allowAlertsOnMe)
        repository = newProcess()
        refreshWith(row(quietSince, consent = null))
        assertEquals("no word raises nothing either", false, heldRowOfBo().allowAlertsOnMe)
        assertTrue("nothing said about Bo: $posted", posted.isEmpty())

        repository = newProcess()
        refreshWith(row(quietSince + 60_000L, consent = true))
        assertEquals("a new fix carries its word", true, heldRowOfBo().allowAlertsOnMe)
    }

    @Test
    fun `no word from a dropped row keeps what is held, a yes or a no`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true))
        refreshWith(row(quietSince, consent = null))
        assertEquals("a held yes stays, as held", true, heldRowOfBo().allowAlertsOnMe)

        refreshWith(row(quietSince, consent = false))
        refreshWith(row(quietSince, consent = null))
        assertEquals("a held no stays", false, heldRowOfBo().allowAlertsOnMe)
    }

    @Test
    fun `only the consent changes - the fix, its time and its battery stay, and no row is added`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true, level = "10"))
        val before = db.locationDao().getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE)

        refreshWith(row(quietSince, consent = false, level = "10"))

        val after = db.locationDao().getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE)
        assertEquals(before.map { it.copy(allowAlertsOnMe = false) }, after)
    }

    @Test
    fun `the next fix decides, a yes included`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true))
        refreshWith(row(quietSince, consent = false))
        assertEquals(false, heldRowOfBo().allowAlertsOnMe)

        // Bo's phone sends a newer fix, with alerts on Bo allowed again.
        refreshWith(row(quietSince + 60_000L, consent = true))

        val held = heldRowOfBo()
        assertEquals(quietSince + 60_000L, held.timestamp)
        assertEquals(true, held.allowAlertsOnMe)
    }

    @Test
    fun `a served no in any spelling of the member's id lowers the row held`() = runTest {
        watchingBo()
        refreshWith(row(quietSince, consent = true))
        refreshWith(row(quietSince, consent = false, who = BO.uppercase()))

        assertEquals(false, heldRowOfBo().allowAlertsOnMe)
    }

    @Test
    fun `a no lowers only that member's newest current row, and makes none where nothing is held`() = runTest {
        val dao = db.locationDao()
        assertEquals("nothing held, nothing made", 0, dao.lowerAlertsConsent(CIRCLE, BO))
        assertTrue(dao.getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE).isEmpty())

        fun fix(who: String, at: Long, current: Boolean = true) = LocationEntity(
            circleId = CIRCLE, userId = who, deviceId = "d", latitude = 0.0, longitude = 0.0,
            altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = 10,
            timestamp = at, allowAlertsOnMe = true, keyCurrent = current,
        )
        dao.insertAll(listOf(
            fix(BO, quietSince - HOUR),
            fix(BO, quietSince),
            fix(BO, quietSince + HOUR, current = false),
            fix(CY, quietSince),
        ))

        assertEquals(1, dao.lowerAlertsConsent(CIRCLE, BO))

        val rows = dao.getByTimeRange(CIRCLE, 0L, Long.MAX_VALUE).associateBy { it.userId to it.timestamp }
        assertEquals("Bo's newest current row", false, rows.getValue(BO to quietSince).allowAlertsOnMe)
        assertEquals("an older one stays as fetched", true, rows.getValue(BO to quietSince - HOUR).allowAlertsOnMe)
        assertEquals("a row only an older key opened stays", true, rows.getValue(BO to quietSince + HOUR).allowAlertsOnMe)
        assertEquals("another member at the same time stays", true, rows.getValue(CY to quietSince).allowAlertsOnMe)
        assertEquals("a no held is no lower", 0, dao.lowerAlertsConsent(CIRCLE, BO))
    }
}
