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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceAlertEventDao
import com.pingolin.track.data.local.dao.PlaceAlertMemberStateDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceAlertEventEntity
import com.pingolin.track.data.local.entity.PlaceAlertMemberStateEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.placealerts.EventTypeHasher
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import uniffi.pingolin_mobile.BoundLocation
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.Location

/**
 * Late points: a member whose phone could not share with the circle for a while sends what it
 * kept afterwards, and the server marks those history rows `"history_only": true`. They are
 * shown in history like any other row and never judged for place alerts: a crossing hours old
 * is history, not news.
 *
 * End to end: the history response on the wire (MockWebServer and a real Retrofit
 * [TrackApi]), through [LocationRepository.fetchLocationHistory], into a REAL
 * [PlaceAlertEvaluator] whose posts are recorded.
 */
class HistoryOnlyRowsTest {

    private companion object {
        const val CIRCLE = "circle-1"
        const val ME = "user-me"
        /** A UUID with hex letters: a row naming no account is no member's. */
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val PLACE = "place-home"
    }

    private lateinit var server: MockWebServer
    private lateinit var repository: LocationRepository

    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val locationDao: LocationDao = mockk(relaxed = true)

    /** Every notification the evaluator asked AlertHandler to post. */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()

    /** Every location handed to the local store. */
    private val stored = mutableListOf<LocationEntity>()

    private val home = PlaceEntity(
        id = PLACE, circleId = "self", name = "Home", address = null,
        latitude = 37.0, longitude = -122.0, radius = 150, icon = null, color = null,
        createdAt = 0L, updatedAt = 0L, sharedCircles = null,
    )

    private val alert = PlaceAlertEntity(
        id = "alert-1", placeId = PLACE, circleId = null, userId = ME, targetUserId = ALICE,
        alertOnArrival = true, alertOnDeparture = true, activeDays = null,
        activeStartTime = null, activeEndTime = null, notifyPush = true, dayBucket = 0,
    )

    private class StateTable : PlaceAlertMemberStateDao {
        val rows = mutableMapOf<Pair<String, String>, PlaceAlertMemberStateEntity>()
        override suspend fun get(placeId: String, userId: String) = rows[placeId to userId]
        override suspend fun upsert(state: PlaceAlertMemberStateEntity) { rows[state.placeId to state.userId] = state }
        override suspend fun deleteByPlace(placeId: String) { rows.keys.removeAll { it.first == placeId } }
        override suspend fun deleteAll() = rows.clear()
    }

    private class Ledger : PlaceAlertEventDao {
        val rows = linkedMapOf<String, PlaceAlertEventEntity>()
        override suspend fun claim(event: PlaceAlertEventEntity): Long {
            if (rows.containsKey(event.id)) return -1L
            rows[event.id] = event
            return rows.size.toLong()
        }
        override suspend fun countSurfacedSince(placeId: String, userId: String, transition: String, fromSource: String, since: Long) =
            rows.values.count {
                it.placeId == placeId && it.userId == userId && it.transition == transition &&
                    it.source == fromSource && !it.silent && it.crossedAt >= since
            }
        override fun observeRecent(): Flow<List<PlaceAlertEventEntity>> = flowOf(rows.values.toList())
        override suspend fun deleteOlderThan(minEpochMillis: Long) { rows.values.removeAll { it.crossedAt < minEpochMillis } }
        override suspend fun deleteAll() = rows.clear()
    }

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
        // Opened under the circle's current key.
        every { cryptoManager.isCurrentKeyVersion(any(), any()) } returns true
        // The sealed payload of each row says where Alice was: "inside:<ms>" or "outside:<ms>", and
        // ":<version>" when a version other than the default opened it.
        every { cryptoManager.decryptLocationBoundForUser(any(), any(), any()) } answers {
            val parts = arg<EncryptedData>(1).ciphertext.split(":")
            val lat = if (parts[0] == "inside") 37.0 else 37.02
            val version = parts.getOrNull(2)?.toInt() ?: 0
            BoundLocation(Location(lat, -122.0, null, null, null, null, parts[1].toLong(), null, null), true, version)
        }
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"
        coEvery { locationDao.insertAll(any()) } answers { stored += arg<List<LocationEntity>>(0) }

        val placeDao = mockk<PlaceDao>()
        coEvery { placeDao.getById(PLACE) } returns home
        val placeAlertDao = mockk<PlaceAlertDao>()
        coEvery { placeAlertDao.getActive() } returns listOf(alert)
        val circleMemberDao = mockk<CircleMemberDao>(relaxed = true)
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returns "Alice"
        val alertHandler = mockk<AlertHandler>()
        every { alertHandler.handleAlert(any(), any()) } answers { posted += arg<String>(0) to arg<Map<String, String>>(1) }
        val evaluator = PlaceAlertEvaluator(
            placeDao = placeDao,
            placeAlertDao = placeAlertDao,
            stateDao = StateTable(),
            eventDao = Ledger(),
            shownDao = mockk<com.pingolin.track.data.local.dao.PlaceAlertShownDao>(relaxed = true),
            notificationDao = mockk<NotificationDao>(relaxed = true),
            circleMemberDao = circleMemberDao,
            sessionManager = sessionManager,
            eventTypeHasher = mockk<EventTypeHasher>(relaxed = true),
            alertHandler = dagger.Lazy { alertHandler },
            uxEventBus = mockk<UxEventBus>(relaxed = true),
            circleRepository = dagger.Lazy { mockk(relaxed = true) },
        )

        repository = LocationRepository(
            locationProvider = mockk<LocationProvider>(relaxed = true),
            locationDao = locationDao,
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
            placeAlertEvaluator = evaluator,
            currentKeyGate = CurrentKeyGate(mockk<CircleDao>(relaxed = true), cryptoManager, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = com.pingolin.track.util.ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    /** One history row of Alice's, [where] ("inside"/"outside") Home at [at], opened under [version] when given. */
    private fun row(where: String, at: Long, historyOnly: Boolean? = null, version: Int? = null) =
        "{\"user_id\":\"$ALICE\",\"device_id\":\"dev-alice\",\"day_bucket\":${at / 86_400_000L}," +
            "\"encrypted_payload\":{\"nonce\":\"n\",\"ciphertext\":\"$where:$at${version?.let { ":$it" } ?: ""}\"}" +
            (historyOnly?.let { ",\"history_only\":$it" } ?: "") + "}"

    private suspend fun historyWith(vararg rows: String): List<LocationEntity> {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                "{\"success\":true,\"data\":{\"locations\":[${rows.joinToString(",")}]," +
                    "\"history_days_allowed\":365},\"error\":null}"
            )
        )
        return repository.fetchLocationHistory(CIRCLE).getOrThrow().locations
    }

    @Test
    fun `a late row inside the alert window raises nothing, and an unflagged one does`() = runTest {
        val now = System.currentTimeMillis()
        // Alice is outside Home.
        historyWith(row("outside", now - 120_000L))
        assertTrue("seeding raises nothing", posted.isEmpty())

        // A late point of hers, inside Home a minute ago: shown, stored, judged by nobody.
        val shown = historyWith(row("inside", now - 60_000L, historyOnly = true))
        assertEquals(listOf(now - 60_000L), shown.map { it.timestamp })
        assertTrue("the late row is kept like any other", stored.any { it.timestamp == now - 60_000L })
        assertTrue("a late row raises no alert: ${posted.map { it.second["body"] }}", posted.isEmpty())

        // The same place, reported live half a minute ago: that one is news.
        historyWith(row("inside", now - 30_000L, historyOnly = false))
        assertEquals(1, posted.size)
        assertEquals(AlertHandler.TYPE_PLACE_ARRIVAL, posted.single().first)
        assertEquals("Alice arrived at Home", posted.single().second["body"])
    }

    @Test
    fun `in one page, only the rows without the flag are judged`() = runTest {
        val now = System.currentTimeMillis()
        historyWith(row("outside", now - 180_000L))

        // A late visit to Home, then a live point outside it. Judged together they would
        // make a pass-through; without the late one, Alice never left "outside".
        val shown = historyWith(
            row("inside", now - 90_000L, historyOnly = true),
            row("outside", now - 40_000L),
        )
        assertEquals(2, shown.size)
        assertTrue("the late visit raises nothing: ${posted.map { it.second["body"] }}", posted.isEmpty())

        // And her state is the live one: arriving now is an arrival.
        historyWith(row("inside", now - 20_000L))
        assertEquals(listOf("Alice arrived at Home"), posted.map { it.second["body"] })
    }

    @Test
    fun `a row only an older key opened, unflagged, is shown in history and judged by nobody`() = runTest {
        val now = System.currentTimeMillis()
        // Version 1 is a retired version of the circle's key here.
        every { cryptoManager.isCurrentKeyVersion(any(), 1) } returns false
        historyWith(row("outside", now - 120_000L))
        assertTrue("seeding raises nothing", posted.isEmpty())

        // Alice inside Home a minute ago, with no late flag, but only the older key opened it:
        // shown and kept as history, judged by nobody.
        val shown = historyWith(row("inside", now - 60_000L, version = 1))
        assertEquals(listOf(now - 60_000L), shown.map { it.timestamp })
        assertTrue("the old-key row is kept, as history", stored.any { it.timestamp == now - 60_000L && !it.keyCurrent })
        assertTrue("an old-key row raises no alert: ${posted.map { it.second["body"] }}", posted.isEmpty())

        // The same place under the current key, half a minute ago: that one is news.
        historyWith(row("inside", now - 30_000L))
        assertEquals(listOf("Alice arrived at Home"), posted.map { it.second["body"] })
    }
}
