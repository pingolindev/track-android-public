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

import android.location.Location
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PendingLocationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.DeviceResponse
import com.pingolin.track.data.remote.dto.EncryptedLocationResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.LatestLocationsWrapper
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.*
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * Unit tests for LocationRepository.
 *
 * These tests verify location handling, encryption, and API communication.
 * Note: Tests for gRPC streaming require integration tests with the native SDK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepositoryTest {

    @Mock
    private lateinit var locationProvider: LocationProvider

    @Mock
    private lateinit var locationDao: LocationDao

    @Mock
    private lateinit var pendingLocationDao: PendingLocationDao

    @Mock
    private lateinit var circleDao: CircleDao

    @Mock
    private lateinit var privacySettingDao: PrivacySettingDao

    @Mock
    private lateinit var tracksApi: TrackApi

    @Mock
    private lateinit var authApi: com.pingolin.track.data.remote.api.AuthApi

    @Mock
    private lateinit var sessionManager: SessionManager

    @Mock
    private lateinit var cryptoManager: CryptoManager

    @Mock
    private lateinit var queueMonitor: com.pingolin.track.util.QueueMonitor

    @Mock
    private lateinit var circleRepositoryLazy: dagger.Lazy<CircleRepository>

    @Mock
    private lateinit var boundaryRepositoryLazy: dagger.Lazy<BoundaryRepository>

    @Mock
    private lateinit var placeAlertEvaluator: com.pingolin.track.domain.placealerts.PlaceAlertEvaluator

    @Mock
    private lateinit var sosAlertDao: com.pingolin.track.data.local.dao.SosAlertDao

    @Mock
    private lateinit var queuedCircleLocationDao: com.pingolin.track.data.local.dao.QueuedCircleLocationDao

    private lateinit var locationRepository: LocationRepository

    /** The newest own fix, on a clock the tests set. */
    private lateinit var newestOwnFix: com.pingolin.track.location.NewestOwnFix

    /** When this account paused each circle: what a late point is held against. Its file a test damages. */
    private val pausePrefs = com.pingolin.track.testutil.DamagedPrefs()
    private val pauseIntervals = com.pingolin.track.util.PauseIntervals.over(pausePrefs, { "test-user-id" }, System::currentTimeMillis)
    /** How wide a bubble each circle got when. Its file a test damages. */
    private val bubblePrefs = com.pingolin.track.testutil.DamagedPrefs()
    /** The bubble record's clock, when a test sets it. */
    private var bubbleNow: Long? = null
    private val approximateIntervals = com.pingolin.track.util.ApproximateIntervals.over(bubblePrefs, { "test-user-id" }, { bubbleNow ?: System.currentTimeMillis() })
    private val nowNanos = 9_000_000_000_000L

    private val testEncrypted = EncryptedData(nonce = "test-nonce", ciphertext = "test-cipher")

    /** A real bus, heard by the test that needs it. */
    private val uxEventBus = com.pingolin.track.util.UxEventBus()

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)

        // Default session state
        whenever(sessionManager.getDeviceId()).thenReturn("test-device-id")
        whenever(sessionManager.getUserId()).thenReturn("test-user-id")
        whenever(sessionManager.getAccessToken()).thenReturn("test-token")
        whenever(sessionManager.isDeviceRegistered()).thenReturn(true)
        whenever(sessionManager.getIngestUrl()).thenReturn("https://ingest.example.com")
        whenever(sessionManager.getAuthBaseUrl()).thenReturn("https://auth.example.com")
        whenever(sessionManager.getApiBaseUrl()).thenReturn("https://api.example.com")

        // A personal key that exists and round-trips, which ensureDeviceRegistered needs.
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID)).thenReturn(true)
        // Every row these tests decode opened under the circle's current key, unless a test says otherwise.
        whenever(cryptoManager.isCurrentKeyVersion(any(), any())).thenReturn(true)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID)).thenReturn(true)
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_UUID)).thenReturn(true)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_UUID)).thenReturn(true)
        whenever(cryptoManager.encryptDataV2(eq(LocationRepository.SELF_CIRCLE_ID), eq("keycheck"), eq("key_check"))).thenReturn(testEncrypted)
        whenever(cryptoManager.decryptDataV2(eq(LocationRepository.SELF_CIRCLE_ID), eq(testEncrypted), eq("key_check"))).thenReturn("keycheck")

        newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager).also { it.elapsedNanos = { nowNanos } }
        locationRepository = LocationRepository(
            locationProvider = locationProvider,
            locationDao = locationDao,
            pendingLocationDao = pendingLocationDao,
            circleDao = circleDao,
            privacySettingDao = privacySettingDao,
            tracksApi = tracksApi,
            authApi = authApi,
            sessionManager = sessionManager,
            // Never exercised by these tests; the stream refresh has its own suite.
            tokenRefresher = com.pingolin.track.util.TokenRefresher(sessionManager) { io.mockk.mockk(relaxed = true) },
            cryptoManager = cryptoManager,
            uxEventBus = uxEventBus,
            queueMonitor = queueMonitor,
            circleRepository = circleRepositoryLazy,
            boundaryRepository = boundaryRepositoryLazy,
            placeAlertEvaluator = placeAlertEvaluator,
            currentKeyGate = CurrentKeyGate(circleDao, cryptoManager, sosAlertDao, sessionManager),
            queuedCircleLocationDao = queuedCircleLocationDao,
            approximateMembers = com.pingolin.track.util.ApproximateMembersStore.inMemory(),
            newestOwnFix = newestOwnFix,
            pauseIntervals = pauseIntervals,
            approximateIntervals = approximateIntervals,
        )
    }

    @Test
    fun `sendLocation returns failure when user not authenticated`() = runTest {
        // Given: No user ID in session
        whenever(sessionManager.getUserId()).thenReturn(null)

        val location = createMockLocation()

        // When: Attempting to send location
        val result = locationRepository.sendLocation("circle-123", location, batteryLevel = 80)

        // Then: Should fail with appropriate error
        assertTrue(result.isFailure)
        assertEquals("No user", result.exceptionOrNull()?.message)
    }

    @Test
    fun `sendLocation returns failure when circle key not available`() = runTest {
        // Given: User is authenticated, device registered, but circle key is missing
        // Note: Personal key (SELF_CIRCLE_ID) is set up in @Before, device is registered
        whenever(cryptoManager.hasCircleKey("circle-123")).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse("circle-123")).thenReturn(false)

        val location = createMockLocation()

        // When: Attempting to send location
        val result = locationRepository.sendLocation("circle-123", location, batteryLevel = 80)

        // Then: Should fail because circle key is not available
        assertTrue(result.isFailure)
        assertEquals(
            "Awaiting sync for circle settings from another member. Locations will be queued until synced.",
            result.exceptionOrNull()?.message,
        )
        assertTrue(result.exceptionOrNull() is LocationRepository.CircleKeyUnavailableException)
    }

    /**
     * A fix sent outside the tracking service (send-now, the map-open refresh), or taken here to
     * send (a check-in, a needs-attention alert, a destination), is the newest own position a
     * liveness check-in may re-send, even when the circle can't take it yet; sign-out drops it.
     */
    @Test
    fun `a fix sent outside the tracking service, or taken to send, is the newest own position`() = runTest {
        whenever(sessionManager.getUserId()).thenReturn("a11ce000-0000-4000-8000-00000000a11c")
        fun fixAt(secondsAgo: Long) = createMockLocation().also {
            whenever(it.elapsedRealtimeNanos).thenReturn(nowNanos - secondsAgo * 1_000_000_000L)
        }
        whenever(cryptoManager.hasCircleKey("circle-123")).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse("circle-123")).thenReturn(false)

        val sendNow = fixAt(secondsAgo = 2)
        locationRepository.sendLocation("circle-123", sendNow, batteryLevel = null)
        assertSame(sendNow, newestOwnFix.newest())

        val forACheckIn = fixAt(secondsAgo = 1)
        whenever(locationProvider.currentLocation(com.pingolin.track.location.LocationAccuracy.HIGH)).thenReturn(forACheckIn)
        assertSame(forACheckIn, locationRepository.getCurrentLocation())
        assertSame(forACheckIn, newestOwnFix.newest())

        locationRepository.wipeInMemory()
        assertNull(newestOwnFix.newest())
    }

    /** A send-now rougher than the holder keeps still counts as sent. */
    @Test
    fun `a send-now too rough to keep is still noted as sent, so an older fix is not re-sent as now`() = runTest {
        whenever(sessionManager.getUserId()).thenReturn("a11ce000-0000-4000-8000-00000000a11c")
        fun fixAt(secondsAgo: Long, accuracy: Float) = createMockLocation().also {
            whenever(it.elapsedRealtimeNanos).thenReturn(nowNanos - secondsAgo * 1_000_000_000L)
            whenever(it.accuracy).thenReturn(accuracy)
        }
        whenever(cryptoManager.hasCircleKey("circle-123")).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse("circle-123")).thenReturn(false)
        val precise = fixAt(secondsAgo = 120, accuracy = 8f)
        newestOwnFix.elapsedNanos = { nowNanos - 115_000_000_000L }
        assertTrue(newestOwnFix.offer(precise)) // the service's own fix, kept when it was fresh
        newestOwnFix.elapsedNanos = { nowNanos }

        locationRepository.sendLocation("circle-123", fixAt(secondsAgo = 10, accuracy = 150f), batteryLevel = null)

        assertSame("not kept: too rough", precise, newestOwnFix.newest())
        assertTrue("but noted as sent", newestOwnFix.goesBack(precise))
    }

    private suspend fun behindOnCircle123(held: Int) {
        whenever(cryptoManager.hasCircleKey("circle-123")).thenReturn(true)
        whenever(cryptoManager.hasCircleKeyInUse("circle-123")).thenReturn(true)
        whenever(circleDao.getById("circle-123")).thenReturn(
            CircleEntity(id = "circle-123", name = "Family", encryptedKey = null, createdAt = 0L, updatedAt = 0L, keyVersion = 4)
        )
        whenever(cryptoManager.getActiveVersion("circle-123")).thenReturn(held)
    }

    @Test
    fun `sendLocation seals nothing for a circle whose key moved on without this device`() = runTest {
        // This device holds v3, the circle is at v4 — the only key it could seal
        // under is one a member removed since may hold too.
        behindOnCircle123(held = 3)

        val result = locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)

        // Dropped like a paused circle's fix: not an error, and nothing was sealed.
        assertTrue(result.isSuccess)
        verify(cryptoManager, never()).encryptLocationWithSessionKey(any(), any())
        verify(cryptoManager, never()).encryptLocationWithSessionKeyBound(any(), any(), any())
        verify(cryptoManager, never()).encryptLocation(any(), any())
        verify(cryptoManager, never()).encryptLocationBound(any(), any(), any())
    }

    @Test
    fun `sharing resumes by itself once the new key arrives`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 3)
        locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)
        // Kept on the phone, nothing sealed.
        verify(queuedCircleLocationDao, times(1)).insert(any())
        verify(cryptoManager, never()).encryptLocationWithSessionKeyBound(any(), any(), any())

        // The next import brings v4: the very next fix goes on through the live send
        // path, no restart and no user action, and is not kept.
        whenever(cryptoManager.getActiveVersion("circle-123")).thenReturn(4)
        locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)

        verify(queuedCircleLocationDao, times(1)).insert(any())
    }

    @Test
    fun `a location for a circle this phone cannot share with yet is kept with the circle's privacy applied`() = runTest {
        // "Locations will be queued until synced" holds: an approximate circle keeps
        // the coarsened point with the motion fields stripped, and no battery level
        // when the circle does not get one.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(
                circleId = "circle-123", sharingMode = "approximate", approximateRadiusMiles = 5,
                shareBattery = false, updatedAt = 0L,
            )
        )
        behindOnCircle123(held = 3)
        val fix = createMockLocation()

        locationRepository.sendLocation("circle-123", fix, batteryLevel = 80)

        val kept = argumentCaptor<com.pingolin.track.data.local.entity.QueuedCircleLocationEntity>()
        verify(queuedCircleLocationDao).insert(kept.capture())
        val row = kept.firstValue
        assertEquals("circle-123", row.circleId)
        assertTrue("kept the exact point", row.latitude != fix.latitude || row.longitude != fix.longitude)
        assertNull(row.speed)
        assertNull(row.heading)
        assertNull(row.batteryLevel)
        assertEquals(fix.time, row.timestamp)
    }

    /**
     * Send location now and the map's refresh go this way, with this phone's battery level.
     * Whether a circle gets it is that circle's own setting, applied here, before the bytes
     * are sealed: the level rides inside the encrypted location, so nothing later can take it
     * out.
     */
    @Test
    fun `a sent location carries the battery level to a circle that shares it, and none to one that doesn't`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        behindOnCircle123(held = 4)
        val sealed = argumentCaptor<uniffi.pingolin_mobile.Location>()

        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", shareBattery = true, updatedAt = 0L)
        )
        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 15)
        verify(cryptoManager, times(1)).encryptLocationWithSessionKey(eq("circle-123"), sealed.capture())
        assertEquals(15.toUByte(), sealed.lastValue.batteryLevel)

        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", shareBattery = false, updatedAt = 0L)
        )
        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 15)
        verify(cryptoManager, times(2)).encryptLocationWithSessionKey(eq("circle-123"), sealed.capture())
        assertNull("the circle doesn't get it", sealed.lastValue.batteryLevel)
    }

    @Test
    fun `a circle keeps at most 5,000 points, the oldest going first`() = runTest {
        // The same caps as on iPhone: 48 h by fix time, and 5,000 per circle.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 3)
        whenever(queuedCircleLocationDao.count("circle-123")).thenReturn(5_002)

        locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)

        verify(queuedCircleLocationDao).dropOldest("circle-123", 2)
        verify(queuedCircleLocationDao).dropOlderThan(any())
    }

    // ===== A point taken during a pause never goes to that circle =====

    private val hourMs = 3_600_000L

    /** The server's word: circle-123 wasn't shared with from 3 h ago to 1 h ago; it is now. */
    private fun pausedEarlier(): Long {
        val now = System.currentTimeMillis()
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(now - 3 * hourMs, now - hourMs),
        ))
        return now
    }

    private fun fixAt(time: Long): Location = createMockLocation().also { whenever(it.time).thenReturn(time) }

    @Test
    fun `a point taken during a pause of a circle never goes there however late, one taken after it does, and personal history is unaffected`() {
        val now = pausedEarlier()
        val gate = { circleId: String, at: Long -> locationRepository.pauseGate(circleId, at, waits = true) }

        assertEquals(LocationRepository.PauseGate.SKIP, gate("circle-123", now - 2 * hourMs))
        assertEquals("within the margin of its end", LocationRepository.PauseGate.SKIP, gate("circle-123", now - hourMs + 30_000L))
        assertEquals(LocationRepository.PauseGate.SEND, gate("circle-123", now - 30 * 60_000L))
        assertEquals(LocationRepository.PauseGate.SEND, gate("circle-123", now - 4 * hourMs))
        // Personal history, even were the record to hold a pause for it.
        for (self in listOf(LocationRepository.SELF_CIRCLE_ID, LocationRepository.SELF_CIRCLE_UUID)) {
            pauseIntervals.observe(self, notSharing = true, deadlineMs = null)
            assertTrue(pauseIntervals.covers(self, now - 2 * hourMs))
            assertEquals("personal history", LocationRepository.PauseGate.SEND, gate(self, now - 2 * hourMs))
        }
    }

    @Test
    fun `a fix taken in the last two minutes of a pause doesn't go after a resume seen from elsewhere`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 4)
        val resumedAt = System.currentTimeMillis()
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(resumedAt - hourMs, resumedAt),
        ))

        assertEquals(LocationRepository.PauseGate.SKIP, locationRepository.pauseGate("circle-123", resumedAt - 90_000L, waits = false))
        assertTrue(locationRepository.sendLocation("circle-123", fixAt(resumedAt - 90_000L), batteryLevel = 80).isSuccess)
        verify(cryptoManager, never()).encryptLocationWithSessionKey(any(), any())
        verify(pendingLocationDao, never()).insert(any())
    }

    @Test
    fun `after a resume made here the next live point goes at once whatever the row carried, nothing waits for a re-sync, and one taken in the pause doesn't`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 4)
        val pausedAt = System.currentTimeMillis() - hourMs
        pauseIntervals.observe("circle-123", notSharing = true, deadlineMs = null, server = listOf(
            com.pingolin.track.util.PauseIntervals.Interval(pausedAt, null),
        ))
        // Resumed here, and then a row that says sharing, with no spans in it.
        pauseIntervals.resumedHere("circle-123")
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null)

        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80)
        verify(cryptoManager, times(1)).encryptLocationWithSessionKey(eq("circle-123"), any())
        // Taken three minutes ago, in the pause, and sent now: not there.
        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis() - 3 * 60_000L), batteryLevel = 80)
        verify(cryptoManager, times(1)).encryptLocationWithSessionKey(eq("circle-123"), any())
    }

    @Test
    fun `a held or cached fix the record can't place yet doesn't go there now, a fresh one does, and nothing waits`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 4)
        val now = System.currentTimeMillis()
        // Back online, no privacy row read yet: a pause set and ended meanwhile may cover it.
        assertEquals(LocationRepository.PauseGate.HOLD, locationRepository.pauseGate("circle-123", now - 3 * 60_000L, waits = false))

        assertTrue(locationRepository.sendLocation("circle-123", fixAt(now - 3 * 60_000L), batteryLevel = 80).isFailure)
        verify(cryptoManager, never()).encryptLocationWithSessionKey(any(), any())
        verify(pendingLocationDao, never()).insert(any())
        verify(queuedCircleLocationDao, never()).insert(any())
        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80)
        verify(cryptoManager, times(1)).encryptLocationWithSessionKey(eq("circle-123"), any())
    }

    @Test
    fun `kept points the record can't place yet bring one privacy re-read before they go, none again within the minute, none when placed`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        behindOnCircle123(held = 4)
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = emptyList())
        val syncedAt = System.currentTimeMillis()
        fun kept(at: Long) = com.pingolin.track.data.local.entity.QueuedCircleLocationEntity(
            id = 1, circleId = "circle-123", latitude = 1.0, longitude = 2.0, timestamp = at, createdAt = at,
        )
        whenever(queuedCircleLocationDao.circleIds()).thenReturn(listOf("circle-123"))

        // Taken before the last re-read: placed, nothing to read.
        whenever(queuedCircleLocationDao.oldest(eq("circle-123"), any())).thenReturn(listOf(kept(syncedAt - hourMs)), emptyList())
        locationRepository.flushQueued { _, _ -> true }
        verify(circleRepo, never()).refreshCircles(any())

        // Taken since (kept while offline): one re-read first.
        whenever(queuedCircleLocationDao.oldest(eq("circle-123"), any())).thenReturn(listOf(kept(System.currentTimeMillis())), emptyList())
        locationRepository.flushQueued { _, _ -> true }
        verify(circleRepo, times(1)).refreshCircles(any())

        // Again within the minute: no second one.
        whenever(queuedCircleLocationDao.oldest(eq("circle-123"), any())).thenReturn(listOf(kept(System.currentTimeMillis())), emptyList())
        locationRepository.flushQueued { _, _ -> true }
        verify(circleRepo, times(1)).refreshCircles(any())
    }

    @Test
    fun `the outbox drain re-reads privacy once it is online, before its rows go`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        val start = src.indexOf("suspend fun syncPendingLocations(")
        val drain = src.substring(start, minOf(start + 12000, src.length))
        val reread = drain.indexOf("rereadPrivacyBefore(pending.map { it.circleId to it.timestamp })")
        assertTrue(reread > 0)
        assertTrue("online", drain.indexOf("if (!canSend) {") < reread)
        assertTrue("before the rows go", reread < drain.indexOf("for (pendingLocation in pending)"))
    }

    @Test
    fun `while the record of pauses can't be read, a waiting point waits, a fresh live one goes, an old live one doesn't`() {
        pausePrefs.damaged = true
        val now = System.currentTimeMillis()

        assertEquals(LocationRepository.PauseGate.HOLD, locationRepository.pauseGate("circle-123", now - 2 * hourMs, waits = true))
        assertEquals(LocationRepository.PauseGate.SEND, locationRepository.pauseGate("circle-123", now, waits = false))
        assertEquals(LocationRepository.PauseGate.HOLD, locationRepository.pauseGate("circle-123", now - 2 * hourMs, waits = false))
    }

    @Test
    fun `a waiting point taken since the last privacy re-sync waits for the next one, and a live one goes`() {
        // Back online, the queue must not drain before the record has heard of a pause set
        // and ended on another phone meanwhile.
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = emptyList())
        val synced = System.currentTimeMillis()

        assertEquals(LocationRepository.PauseGate.HOLD, locationRepository.pauseGate("circle-123", synced, waits = true))
        assertEquals(LocationRepository.PauseGate.SEND, locationRepository.pauseGate("circle-123", synced, waits = false))
        assertEquals(
            "taken well before it",
            LocationRepository.PauseGate.SEND, locationRepository.pauseGate("circle-123", synced - 2 * 60_000L, waits = true),
        )
        assertEquals(
            "a circle never re-synced",
            LocationRepository.PauseGate.HOLD, locationRepository.pauseGate("circle-456", synced - hourMs, waits = true),
        )
    }

    @Test
    fun `a fix taken during a pause is never sealed for that circle, and one taken after it is`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        // Shared with now: only the record says the fix was taken during a pause.
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = 4)
        val now = pausedEarlier()

        assertTrue(locationRepository.sendLocation("circle-123", fixAt(now - 2 * hourMs), batteryLevel = 80).isSuccess)

        verify(cryptoManager, never()).encryptLocationWithSessionKey(any(), any())
        verify(cryptoManager, never()).encryptLocation(any(), any())
        verify(pendingLocationDao, never()).insert(any())
        verify(queuedCircleLocationDao, never()).insert(any())

        locationRepository.sendLocation("circle-123", fixAt(now - 30 * 60_000L), batteryLevel = 80)

        verify(cryptoManager, atLeastOnce()).encryptLocationWithSessionKey(eq("circle-123"), any())
    }

    @Test
    fun `every send to a circle is held against when its point was taken - live, re-sent, kept and queued`() {
        // Before ANY send to that circle. The envelope path carries the live point, the
        // check-in's re-sent fix, a kept point (historyOnly) and the trip start; the bare path
        // the map's send; the drain the queue, whose taken-during-a-pause rows go for good.
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        fun bodyOf(signature: String, chars: Int): String {
            val start = src.indexOf(signature)
            assertTrue("$signature not found — update this test with the new name", start >= 0)
            return src.substring(start, minOf(start + chars, src.length))
        }
        val envelope = bodyOf("private suspend fun sendEnvelope(", 30000)
        assertTrue(envelope.contains("when (pauseGate(targetCircleId, location.time, waits = historyOnly)) {"))
        assertTrue("a kept point that waits stays kept", envelope.contains("PauseGate.HOLD -> {\n                            if (historyOnly) historyUndelivered = true"))
        // Before anything is sealed for the target.
        assertTrue(envelope.indexOf("pauseGate(targetCircleId") < envelope.indexOf("coarsenRadiusMetersFor(targetCircleId"))
        val bare = bodyOf("suspend fun sendLocation(", 9000)
        assertTrue(bare.contains("when (pauseGate(circleId, location.time, waits = false)) {"))
        assertTrue(bare.indexOf("pauseGate(circleId") < bare.indexOf("coarsenRadiusMetersFor(circleId"))
        val drain = bodyOf("suspend fun syncPendingLocations(", 9000)
        assertTrue(drain.contains("when (pauseGate(circleId, pendingLocation.timestamp, waits = true)) {"))
        assertTrue("one that waits stays queued", drain.contains("PauseGate.HOLD -> continue"))
        assertTrue("dropped for good", drain.contains("PauseGate.SKIP -> {\n                            Log.d(TAG, \"syncPendingLocations: id=${'$'}{pendingLocation.id} was taken during a pause of ${'$'}circleId — dropped\")\n                            pendingLocationDao.deleteById(pendingLocation.id)"))
        assertTrue("before the posture check and the send", drain.indexOf("pauseGate(circleId") < drain.indexOf("mayEmitPresenceTo(circleId)"))
    }

    // ===== A point goes no finer than the bubble in force when it was taken =====

    /** The server's word: circle-123 was shared in a 5-mile bubble from 3 h ago to 1 h ago; it's exact now. */
    private fun bubbleEarlier(): Long {
        val now = System.currentTimeMillis()
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = emptyList())
        approximateIntervals.observe("circle-123", radiusMiles = null, deadlineMs = null, server = listOf(
            com.pingolin.track.util.ApproximateIntervals.Span(now - 3 * hourMs, now - hourMs, 5),
        ))
        return now
    }

    private suspend fun exactNowOnCircle123(held: Int) {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        behindOnCircle123(held = held)
    }

    private val inFiveMiles = LocationRepository.coarsenLocation(37.7749, -122.4194, 5 * LocationRepository.METERS_PER_MILE)

    @Test
    fun `a fix taken inside a bubble is sealed in it, even after a switch back to exact, and one taken after it exactly`() = runTest {
        exactNowOnCircle123(held = 4)
        val now = bubbleEarlier()

        locationRepository.sendLocation("circle-123", fixAt(now - 2 * hourMs), batteryLevel = 80)
        locationRepository.sendLocation("circle-123", fixAt(now - 30 * 60_000L), batteryLevel = 80)

        val sealed = argumentCaptor<uniffi.pingolin_mobile.Location>()
        verify(cryptoManager, times(2)).encryptLocationWithSessionKey(eq("circle-123"), sealed.capture())
        val (inBubble, after) = sealed.allValues
        assertEquals(inFiveMiles.first, inBubble.latitude, 0.0)
        assertEquals(inFiveMiles.second, inBubble.longitude, 0.0)
        assertNull("no speed inside a bubble", inBubble.speed)
        assertEquals(37.7749, after.latitude, 0.0)
        assertEquals(-122.4194, after.longitude, 0.0)
    }

    @Test
    fun `a fix kept while this phone is behind is kept in the bubble in force when it was taken`() = runTest {
        exactNowOnCircle123(held = 3)
        val now = bubbleEarlier()

        locationRepository.sendLocation("circle-123", fixAt(now - 2 * hourMs), batteryLevel = 80)

        val kept = argumentCaptor<com.pingolin.track.data.local.entity.QueuedCircleLocationEntity>()
        verify(queuedCircleLocationDao).insert(kept.capture())
        assertEquals(inFiveMiles.first, kept.firstValue.latitude, 0.0)
        assertEquals(inFiveMiles.second, kept.firstValue.longitude, 0.0)
    }

    @Test
    fun `a queued row sealed exact is not sent once the circle went approximate before it drained`() = runTest {
        val now = System.currentTimeMillis()
        // Exact when the point was taken, as this phone saw it, and as the server says.
        approximateIntervals.observe("circle-123", radiusMiles = null, deadlineMs = null, server = emptyList())
        // A 5-mile bubble since, before the queue drained.
        whenever(privacySettingDao.get("circle-123")).thenReturn(com.pingolin.track.data.local.entity.PrivacySettingEntity(
            circleId = "circle-123", sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_APPROXIMATE,
            approximateRadiusMiles = 5, updatedAt = 0L,
        ))
        assertEquals(LocationRepository.PauseGate.SKIP, locationRepository.sealedBubbleGate("circle-123", now - 10 * 60_000L, now - 10 * 60_000L))
        // Exact again before it drained: it goes.
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        )
        assertEquals(LocationRepository.PauseGate.SEND, locationRepository.sealedBubbleGate("circle-123", now - 10 * 60_000L, now - 10 * 60_000L))
    }

    @Test
    fun `a queued row sealed finer than the bubble in force when it was taken never goes, and waits while that can't be read`() = runTest {
        val now = System.currentTimeMillis()
        // This phone sees a 5-mile bubble now; the server says it began 10 minutes ago.
        approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(
            com.pingolin.track.util.ApproximateIntervals.Span(now - 10 * 60_000L, null, 5),
        ))
        // Queued as it was taken.
        val gate: suspend (String, Long) -> LocationRepository.PauseGate = { circleId, at -> locationRepository.sealedBubbleGate(circleId, at, at) }

        assertEquals("sealed exact inside it", LocationRepository.PauseGate.SKIP, gate("circle-123", now - 5 * 60_000L))
        assertEquals("sealed in it", LocationRepository.PauseGate.SEND, gate("circle-123", now + 2 * 60_000L))
        assertEquals("taken while exact", LocationRepository.PauseGate.SEND, gate("circle-123", now - hourMs))
        assertEquals("personal history", LocationRepository.PauseGate.SEND, gate(LocationRepository.SELF_CIRCLE_ID, now - 5 * 60_000L))
        bubblePrefs.damaged = true
        assertEquals(LocationRepository.PauseGate.HOLD, gate("circle-456", now - 5 * 60_000L))
    }

    @Test
    fun `a late fix whose history the server hasn't told this phone yet goes in the widest bubble`() = runTest {
        exactNowOnCircle123(held = 4)
        // The pause record has a row read after it; no word on bubbles came with it.
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = emptyList())
        approximateIntervals.observe("circle-123", radiusMiles = null, deadlineMs = null)
        val now = System.currentTimeMillis()

        locationRepository.sendLocation("circle-123", fixAt(now - 2 * hourMs), batteryLevel = 80)

        val sealed = argumentCaptor<uniffi.pingolin_mobile.Location>()
        verify(cryptoManager).encryptLocationWithSessionKey(eq("circle-123"), sealed.capture())
        val widest = LocationRepository.coarsenLocation(37.7749, -122.4194, 25 * LocationRepository.METERS_PER_MILE)
        assertEquals(widest.first, sealed.firstValue.latitude, 0.0)
        assertEquals(widest.second, sealed.firstValue.longitude, 0.0)
    }

    @Test
    fun `a queued row sealed in 5 miles goes nowhere when 8 were in force when it was taken`() = runTest {
        val t = System.currentTimeMillis() - 5 * 60_000L
        fun span(from: Long, to: Long?, miles: Int) = com.pingolin.track.util.ApproximateIntervals.Span(from, to, miles)
        // This phone holds 5 miles from t; the row is taken and queued at t + 2 min.
        bubbleNow = t
        approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(span(t - hourMs, null, 5)))
        val taken = t + 2 * 60_000L
        bubbleNow = t + 4 * 60_000L
        approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(span(t - hourMs, null, 5)))
        assertEquals(LocationRepository.PauseGate.SEND, locationRepository.sealedBubbleGate("circle-123", taken, taken))
        // The server says another phone widened it to 8 from t + 1 min to t + 3 min.
        bubbleNow = t + 5 * 60_000L
        approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(
            span(t - hourMs, t + 60_000L, 5), span(t + 60_000L, t + 3 * 60_000L, 8), span(t + 3 * 60_000L, null, 5),
        ))
        bubbleNow = null
        assertEquals(LocationRepository.PauseGate.SKIP, locationRepository.sealedBubbleGate("circle-123", taken, taken))
    }

    @Test
    fun `a row queued after this phone saw the bubble end was sealed exact, whatever it held when the point was taken`() = runTest {
        val t = System.currentTimeMillis() - 2 * hourMs
        // Held 5 miles from t; seen exact at t + 30 min (the server ended it at t + 20 min).
        bubbleNow = t
        approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(
            com.pingolin.track.util.ApproximateIntervals.Span(t, null, 5),
        ))
        bubbleNow = t + 30 * 60_000L
        approximateIntervals.observe("circle-123", radiusMiles = null, deadlineMs = null, server = listOf(
            com.pingolin.track.util.ApproximateIntervals.Span(t, t + 20 * 60_000L, 5),
        ))
        bubbleNow = null
        // A 5-mile bubble again now.
        whenever(privacySettingDao.get("circle-123")).thenReturn(com.pingolin.track.data.local.entity.PrivacySettingEntity(
            circleId = "circle-123", sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_APPROXIMATE,
            approximateRadiusMiles = 5, updatedAt = 0L,
        ))
        val taken = t + 25 * 60_000L

        // Sealed and queued at t + 40 min, when this phone's row said exact: it can't go.
        assertEquals(LocationRepository.PauseGate.SKIP, locationRepository.sealedBubbleGate("circle-123", taken, t + 40 * 60_000L))
        // Queued at t + 26 min, while this phone still held 5: sealed in 5, it goes.
        assertEquals(LocationRepository.PauseGate.SEND, locationRepository.sealedBubbleGate("circle-123", taken, t + 26 * 60_000L))
    }

    @Test
    fun `while the record of bubbles can't be read, a fix taken a while ago goes in the widest bubble`() = runTest {
        exactNowOnCircle123(held = 4)
        pauseIntervals.observe("circle-123", notSharing = false, deadlineMs = null, server = emptyList())
        bubblePrefs.damaged = true
        val now = System.currentTimeMillis()

        locationRepository.sendLocation("circle-123", fixAt(now - 2 * hourMs), batteryLevel = 80)
        locationRepository.sendLocation("circle-123", fixAt(now), batteryLevel = 80)

        val sealed = argumentCaptor<uniffi.pingolin_mobile.Location>()
        verify(cryptoManager, times(2)).encryptLocationWithSessionKey(eq("circle-123"), sealed.capture())
        val widest = LocationRepository.coarsenLocation(37.7749, -122.4194, 25 * LocationRepository.METERS_PER_MILE)
        assertEquals(widest.first, sealed.firstValue.latitude, 0.0)
        assertEquals("a fresh one goes by the setting now", 37.7749, sealed.secondValue.latitude, 0.0)
    }

    @Test
    fun `every coarsening goes by when the point was taken, and the drain drops a row sealed too fine`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        assertTrue(src.contains("val coarsenRadiusMeters = coarsenRadiusMetersFor(circleId, location.time)"))
        assertTrue(src.contains("if (isSelfTarget) 0.0 else coarsenRadiusMetersFor(targetCircleId, location.time)"))
        assertTrue(src.contains("val radius = coarsenRadiusMetersFor(circleId, location.time)"))
        // The setting alone: inside the capture-time one, and as the "now" of a sealed row's check.
        assertEquals(2, Regex("""coarsenRadiusMetersFor\(\w+\)""").findAll(src).count())
        assertTrue(src.contains("        val now = coarsenRadiusMetersFor(circleId)\n        if (circleId == SELF_CIRCLE_ID || circleId == SELF_CIRCLE_UUID) return now"))
        val start = src.indexOf("suspend fun syncPendingLocations(")
        val drain = src.substring(start, minOf(start + 12000, src.length))
        assertTrue(drain.contains("when (sealedBubbleGate(circleId, pendingLocation.timestamp, pendingLocation.createdAt)) {"))
        assertTrue(drain.contains("was sealed finer than its bubble then — dropped\")\n                            pendingLocationDao.deleteById(pendingLocation.id)"))
        assertTrue("after the pause gate, before the send", drain.indexOf("pauseGate(circleId") < drain.indexOf("sealedBubbleGate(circleId"))
        assertTrue(drain.indexOf("sealedBubbleGate(circleId") < drain.indexOf("mayEmitPresenceTo(circleId)"))
    }

    // ===== A queued copy is stamped with its seal's moment =====

    @Test
    fun `a queued row is stamped when it was sealed - a refresh past a minute with a switch to approximate inside doesn't make an exact row go`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        var posture = com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)
        whenever(privacySettingDao.get("circle-123")).thenAnswer { posture }
        behindOnCircle123(held = 4)
        val start = System.currentTimeMillis()
        var clock = start
        locationRepository.wallNow = { clock }
        bubbleNow = start
        approximateIntervals.observe("circle-123", radiusMiles = null, deadlineMs = null, server = emptyList())
        // Sealed under the circle key (no session key here), exact.
        var sealed = false
        whenever(cryptoManager.encryptLocation(eq("circle-123"), any())).thenAnswer { sealed = true; testEncrypted }
        // The token check before the queue runs 90 s: ten seconds in, this phone sees the switch to 5 miles.
        whenever(sessionManager.getAccessToken()).thenAnswer {
            if (sealed && clock == start) {
                clock += 10_000L
                bubbleNow = clock
                approximateIntervals.observe("circle-123", radiusMiles = 5, deadlineMs = null, server = listOf(
                    com.pingolin.track.util.ApproximateIntervals.Span(clock - 1_000L, null, 5),
                ))
                posture = posture.copy(
                    sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_APPROXIMATE, approximateRadiusMiles = 5,
                )
                clock += 80_000L
            }
            null
        }

        locationRepository.sendLocation("circle-123", fixAt(start), batteryLevel = 80)

        val queued = argumentCaptor<com.pingolin.track.data.local.entity.PendingLocationEntity>()
        verify(pendingLocationDao).insert(queued.capture())
        assertEquals("stamped when sealed, not after the refresh", start, queued.firstValue.createdAt)
        assertEquals(
            LocationRepository.PauseGate.SKIP,
            locationRepository.sealedBubbleGate("circle-123", queued.firstValue.timestamp, queued.firstValue.createdAt),
        )
    }

    // ===== What a send leaves as the circle's latest row is recorded, as it went =====

    /** The record store, in memory. */
    private class RecordedSends : com.pingolin.track.location.OwnLastSentStore {
        val rows = mutableMapOf<String, com.pingolin.track.location.OwnLastSent>()
        override fun get(circleId: String) = rows[circleId]
        override fun put(circleId: String, record: com.pingolin.track.location.OwnLastSent) { rows[circleId] = record }
        override fun update(circleId: String, from: com.pingolin.track.location.OwnLastSent, to: com.pingolin.track.location.OwnLastSent): Boolean {
            if (rows[circleId] != from) return false
            rows[circleId] = to
            return true
        }
        override fun remove(circleId: String) { rows.remove(circleId) }
        override fun circles() = rows.keys.toList()
        override fun wipeAll() = rows.clear()
    }

    @Test
    fun `a send records what it sealed for the circle, as it went, for a re-seal after a rotation`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L),
        )
        behindOnCircle123(held = 4)
        val records = RecordedSends()
        whenever(circleRepo.ownLastSent).thenReturn(records)
        // Sealed under the circle key (no session key here); no stream, so it's kept to go.
        whenever(cryptoManager.encryptLocation(eq("circle-123"), any())).thenReturn(testEncrypted)
        whenever(cryptoManager.deriveDevicePseudonym("circle-123", "test-device-id")).thenReturn("device-ps")
        whenever(sessionManager.getAccessToken()).thenReturn(null)
        val taken = System.currentTimeMillis() - 5_000L

        locationRepository.sendLocation("circle-123", fixAt(taken), batteryLevel = 80)

        val record = records.rows.getValue("circle-123")
        assertEquals("device-ps", record.devicePseudonym)
        assertEquals(testEncrypted.nonce, record.nonce)
        assertEquals(testEncrypted.ciphertext, record.ciphertext)
        assertNull("sealed under the circle key: no session key named", record.keyId)
        assertNull(record.wrappedKey)
        assertEquals(37.7749, record.latitude, 0.0)
        assertEquals(-122.4194, record.longitude, 0.0)
        assertEquals(taken, record.timestampMs)
        assertEquals(4, record.keyVersion)
        assertEquals("sealed exact", 0.0, record.coarsenRadiusMeters)
        // A new send leaves no re-seal pending.
        assertNull(record.pendingNonce)
        assertNull(record.pendingVersion)
    }

    /** The record keeps how coarse the point went, so a re-seal never goes finer. */
    @Test
    fun `a send to a circle sharing approximately records the bubble it sealed, and the point as sealed`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(
                circleId = "circle-123", sharingMode = "approximate", approximateRadiusMiles = 5, updatedAt = 0L,
            ),
        )
        behindOnCircle123(held = 4)
        val records = RecordedSends()
        whenever(circleRepo.ownLastSent).thenReturn(records)
        val sealed = argumentCaptor<uniffi.pingolin_mobile.Location>()
        whenever(cryptoManager.encryptLocation(eq("circle-123"), sealed.capture())).thenReturn(testEncrypted)
        whenever(cryptoManager.deriveDevicePseudonym("circle-123", "test-device-id")).thenReturn("device-ps")
        whenever(sessionManager.getAccessToken()).thenReturn(null)
        val radius = locationRepository.coarsenRadiusMetersFor("circle-123", System.currentTimeMillis())
        assertTrue("the circle gets a bubble", radius > 0.0)

        locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = null)

        val record = records.rows.getValue("circle-123")
        assertEquals(radius, record.coarsenRadiusMeters!!, 0.0)
        assertEquals("the point as sealed, not the raw fix", sealed.firstValue.latitude, record.latitude, 0.0)
        assertEquals(sealed.firstValue.longitude, record.longitude, 0.0)
        assertTrue(record.latitude != 37.7749 || record.longitude != -122.4194)
    }

    @Test
    fun `a record that can't be written never fails the send it follows`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        whenever(privacySettingDao.get("circle-123")).thenReturn(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L),
        )
        behindOnCircle123(held = 4)
        whenever(circleRepo.ownLastSent).thenThrow(IllegalStateException("store gone"))
        whenever(cryptoManager.encryptLocation(eq("circle-123"), any())).thenReturn(testEncrypted)
        whenever(sessionManager.getAccessToken()).thenReturn(null)

        val result = locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80)

        assertEquals(SendOutcome.QUEUED, result.getOrNull())
    }

    @Test
    fun `every send that leaves a circle's latest row records it, and nothing else does`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        // The bare send, sent or queued.
        assertTrue(src.contains("noteLastSent(circleId, sealedUnder, encryptedData, locationKeyId, null, null, sdkLocation, coarsenRadiusMeters)"))
        // The envelope, live and liveness alike, sent or queued: never a history point, nor a check-in dropped unsent.
        assertTrue(src.contains("if (!historyOnly && !isHistoryReplay && (handedOff || needsQueue)) {"))
        assertTrue(src.contains("serverCircleId, sealedUnder, encryptedLocation.location, null,\n                        keyWrap.wrappedKey, keyWrap.wrappedKeyNonce, targetLocation, coarsenRadiusMeters,"))
        // The version and pseudonym a seal goes under are read before it, on both paths.
        val bare = src.indexOf("val sealedUnder = sealedUnder(circleId, deviceId)")
        assertTrue(bare in 0 until src.indexOf("cryptoManager.encryptLocationWithSessionKeyBound(circleId, sdkLocation, senderPseudonym)"))
        val enveloped = src.indexOf("val sealedUnder = if (isSelfTarget) null else sealedUnder(targetCircleId, deviceId)")
        assertTrue(enveloped in 0 until src.indexOf("cryptoManager.envelopeEncryptLocationBound("))
        assertEquals(2, Regex("""noteLastSent\(""").findAll(src).count() - 1)
        // Gone with the circle, and the grace with it.
        val forget = src.substring(src.indexOf("suspend fun forgetCircleLocations(circleId: String) {"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(forget.contains("runCatching { circleRepository.get().ownLastSent.remove(circleId) }"))
        assertTrue(forget.contains("frozenGrace.forget(circleId)"))
        assertTrue(src.substring(src.indexOf("fun wipeInMemory() {")).substringBefore("\n    }\n").contains("frozenGrace.clear()"))
        // The viewer's grace across a rotation, on each latest read.
        assertTrue(src.contains("val carried = frozenGrace.carried("))
        assertTrue(src.contains("val frozen = currentFrozen + carried"))
    }

    @Test
    fun `both send paths stamp a queued copy with the moment just after the posture was read for its seal`() {
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        val bare = src.indexOf("val coarsenRadiusMeters = coarsenRadiusMetersFor(circleId, location.time)")
        assertTrue(bare > 0 && src.indexOf("            val sealedAt = wallNow()", bare) in bare until bare + 700)
        val envelope = src.indexOf("if (isSelfTarget) 0.0 else coarsenRadiusMetersFor(targetCircleId, location.time)")
        assertTrue(envelope > 0 && src.indexOf("                val sealedAt = wallNow()", envelope) in envelope until envelope + 200)
        assertEquals(2, Regex("""createdAt = sealedAt""").findAll(src).count())
    }

    @Test
    fun `a point fresh when its bubble is judged stays fresh where the record then fails, though two minutes pass meanwhile`() = runTest {
        bubblePrefs.damaged = true
        val taken = System.currentTimeMillis()
        val reads = ArrayDeque(listOf(taken + 119_000L))
        locationRepository.wallNow = { reads.removeFirstOrNull() ?: (taken + 121_000L) }

        // No row here (exact), and the record can't be read: a fresh point goes by the bubble now.
        assertEquals(0.0, locationRepository.coarsenRadiusMetersFor("circle-123", taken), 0.0)
    }

    @Test
    fun `a privacy row that can't be read gets the widest bubble - a missing one stays exact`() = runTest {
        val taken = System.currentTimeMillis()
        // No row at all: the server's no-row default, exact.
        whenever(privacySettingDao.get("circle-123")).thenReturn(null)
        assertEquals(0.0, locationRepository.coarsenRadiusMetersFor("circle-123", taken), 0.0)
        // The lookup throws: a posture this phone doesn't know, never the raw point.
        whenever(privacySettingDao.get("circle-123")).thenThrow(IllegalStateException("database closed"))
        assertEquals(
            LocationRepository.MAX_BUBBLE_RADIUS_MILES * LocationRepository.METERS_PER_MILE,
            locationRepository.coarsenRadiusMetersFor("circle-123", taken),
            0.0,
        )
    }

    @Test
    fun `a paused or realtime-off circle keeps nothing`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        behindOnCircle123(held = 3)
        for (privacy in listOf(
            com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", shareRealtime = false, updatedAt = 0L),
            com.pingolin.track.data.local.entity.PrivacySettingEntity(
                circleId = "circle-123", sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_PAUSED, updatedAt = 0L,
            ),
        )) {
            whenever(privacySettingDao.get("circle-123")).thenReturn(privacy)
            locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)
        }

        verify(queuedCircleLocationDao, never()).insert(any())
    }

    /** Just Me's send says kept-to-go too; its success path can't run here (native encryptLocation). */
    @Test
    fun `Just Me's send says whether its location was kept to go`() {
        val repo = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        val self = repo.substring(repo.indexOf("suspend fun sendSelfTrackingLocation("), repo.indexOf("suspend fun storeLocalLocation("))
        assertTrue(self.contains("Result.success(if (needsQueue) SendOutcome.QUEUED else SendOutcome.SENT)"))
    }

    /**
     * Send-now says what became of its location. Nothing streams in these tests, so a location
     * that goes is kept for the drain: "waiting to send", never "sent".
     */
    @Test
    fun `send-now says whether its location was kept to go, waits for the circle, or wasn't sent`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        behindOnCircle123(held = 4)
        // Sealed under the circle key (no session key here), and no stream to hand it to.
        whenever(cryptoManager.encryptLocation(eq("circle-123"), any())).thenReturn(testEncrypted)
        whenever(sessionManager.getAccessToken()).thenReturn(null)
        suspend fun sharing(row: com.pingolin.track.data.local.entity.PrivacySettingEntity) =
            whenever(privacySettingDao.get("circle-123")).thenReturn(row)
        val plain = com.pingolin.track.data.local.entity.PrivacySettingEntity(circleId = "circle-123", updatedAt = 0L)

        sharing(plain)
        assertEquals(SendOutcome.QUEUED, locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80).getOrNull())
        verify(pendingLocationDao, times(1)).insert(any())

        sharing(plain.copy(sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_PAUSED))
        assertEquals(SendOutcome.PAUSED, locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80).getOrNull())

        // Real-time off wins over a pause, as the server reads the two.
        sharing(plain.copy(shareRealtime = false, sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_PAUSED))
        assertEquals(SendOutcome.REALTIME_OFF, locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80).getOrNull())
        verify(pendingLocationDao, times(1)).insert(any())

        sharing(plain)
        behindOnCircle123(held = 3)
        assertEquals(SendOutcome.WAITING_FOR_CIRCLE, locationRepository.sendLocation("circle-123", fixAt(System.currentTimeMillis()), batteryLevel = 80).getOrNull())
    }

    @Test
    fun `a pause that has ended goes out as the mode held before it, a bubble for a bubble`() = runTest {
        // When a timed pause ends the phone sends again while its row still says "paused"
        // until the next privacy sync; coarsening that read only "approximate" would send
        // the exact point in that window. So the mode held before the pause decides.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.ensurePrivacyPrimed("circle-123")).thenReturn(true)
        behindOnCircle123(held = 3)
        val ended = com.pingolin.track.data.local.entity.PrivacySettingEntity(
            circleId = "circle-123", sharingMode = com.pingolin.track.data.local.entity.PrivacySettingEntity.MODE_PAUSED,
            pausedUntil = System.currentTimeMillis() - 60_000L, approximateRadiusMiles = 5, updatedAt = 0L,
        )
        val fix = createMockLocation()

        whenever(privacySettingDao.get("circle-123")).thenReturn(ended.copy(modeBeforePause = "approximate"))
        locationRepository.sendLocation("circle-123", fix, batteryLevel = 80)
        whenever(privacySettingDao.get("circle-123")).thenReturn(ended.copy(modeBeforePause = "exact"))
        locationRepository.sendLocation("circle-123", fix, batteryLevel = 80)

        val kept = argumentCaptor<com.pingolin.track.data.local.entity.QueuedCircleLocationEntity>()
        verify(queuedCircleLocationDao, times(2)).insert(kept.capture())
        val (bubble, exact) = kept.allValues
        assertTrue("a bubble before the pause: never the exact point", bubble.latitude != fix.latitude || bubble.longitude != fix.longitude)
        assertEquals(fix.latitude, exact.latitude, 0.0)
        assertEquals(fix.longitude, exact.longitude, 0.0)
    }

    @Test
    fun `every send-path bubble is decided by sendsBubble, never by reading the mode again`() {
        // PrivacySettingEntity.sendsBubble: approximate, or a pause that held a bubble before it.
        for (path in listOf(
            "src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt",
            "src/main/java/com/pingolin/track/domain/repository/CheckInRepository.kt",
            "src/main/java/com/pingolin/track/domain/repository/DestinationRepository.kt",
        )) {
            val src = java.io.File(path).readText()
            assertTrue(path, src.contains(".sendsBubble()"))
            assertFalse(path, Regex("""sharingMode\s*[!=]=\s*("approximate"|PrivacySettingEntity\.MODE_APPROXIMATE)""").containsMatchIn(src))
        }
    }

    private fun kept(id: Long, circleId: String, at: Long) = com.pingolin.track.data.local.entity.QueuedCircleLocationEntity(
        id = id, circleId = circleId, latitude = 1.0, longitude = 2.0, timestamp = at, createdAt = at,
    )

    @Test
    fun `kept locations go out as history, oldest first, once the phone can share with the circle again`() = runTest {
        behindOnCircle123(held = 4) // circle-123 is current again
        whenever(circleDao.getById("circle-behind")).thenReturn(
            CircleEntity(id = "circle-behind", name = "B", encryptedKey = null, createdAt = 0L, updatedAt = 0L, keyVersion = 4)
        )
        whenever(cryptoManager.getActiveVersion("circle-behind")).thenReturn(3)
        whenever(queuedCircleLocationDao.circleIds()).thenReturn(listOf("circle-123", "circle-behind"))
        whenever(queuedCircleLocationDao.oldest("circle-123", 50))
            .thenReturn(listOf(kept(1, "circle-123", 100L), kept(2, "circle-123", 200L)), emptyList())
        val sent = mutableListOf<Pair<String, Long>>()

        locationRepository.flushQueued { circleId, row -> sent += circleId to row.timestamp; true }

        assertEquals(listOf("circle-123" to 100L, "circle-123" to 200L), sent)
        verify(queuedCircleLocationDao).deleteById(1)
        verify(queuedCircleLocationDao).deleteById(2)
        // Still behind: nothing of it went out.
        verify(queuedCircleLocationDao, never()).oldest(eq("circle-behind"), any())
    }

    @Test
    fun `kept locations go out through the history send, just before the next live point, never after`() {
        // A kept point is HISTORY (no live update, no alerts), sent just BEFORE the next
        // live point or check-in to its circle — as on iPhone — so on a server that takes
        // history for live, the live point is the one that lands last (latest_locations
        // keeps the newest insert). Never through the live replay queue.
        val src = java.io.File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        fun bodyOf(signature: String, chars: Int): String {
            val start = src.indexOf(signature)
            assertTrue("$signature not found — update this test with the new name", start >= 0)
            return src.substring(start, minOf(start + chars, src.length))
        }
        assertTrue(bodyOf("private suspend fun sendKept(", 1200).contains("historyOnly = true"))
        // Paced: a share of the backlog per live point, never all of it at once.
        assertTrue(bodyOf("private suspend fun sendKeptBefore(", 1500).contains("flushQueued(only = due, pace = keptPace)"))
        val send = bodyOf("private suspend fun sendEnvelope(", 30000)
        val before = send.indexOf("if (!isHistoryReplay) sendKeptBefore(sealable)")
        val live = send.indexOf("locationManager?.sendEnvelopeLocation(")
        val checkIn = send.indexOf("locationManager?.sendEnvelopeLivenessRefresh(")
        assertTrue("the kept points go first", before in 0 until live && before < checkIn)
        assertTrue(send.contains("} else if (historyOnly) {\n                            locationManager?.sendEnvelopeHistoryLocation("))
        assertTrue(send.contains("if (needsQueue && historyOnly) {"))
        assertTrue(send.contains("if (historyUndelivered) return Result.failure("))
        // Nothing sends them after a live point or on a refresh with no live point behind it.
        assertFalse(src.contains("scheduleQueuedLocationFlush"))
        val manual = bodyOf("suspend fun sendLocation(", 6000)
        assertTrue(manual.indexOf("sendKeptBefore(listOf(circleId))") in 0 until manual.indexOf("ensurePrivacyPrimed(circleId)"))
    }

    @Test
    fun `kept locations go out only for the circles about to get a live point`() = runTest {
        behindOnCircle123(held = 4)
        whenever(circleDao.getById("circle-other")).thenReturn(
            CircleEntity(id = "circle-other", name = "O", encryptedKey = null, createdAt = 0L, updatedAt = 0L, keyVersion = 1)
        )
        whenever(cryptoManager.getActiveVersion("circle-other")).thenReturn(1)
        whenever(queuedCircleLocationDao.circleIds()).thenReturn(listOf("circle-123", "circle-other"))
        whenever(queuedCircleLocationDao.oldest("circle-123", 50)).thenReturn(listOf(kept(1, "circle-123", 100L)), emptyList())
        val sent = mutableListOf<String>()

        locationRepository.flushQueued(only = setOf("circle-123")) { circleId, _ -> sent += circleId; true }

        assertEquals(listOf("circle-123"), sent)
        verify(queuedCircleLocationDao, never()).oldest(eq("circle-other"), any())
    }

    @Test
    fun `a kept location that does not go out stays, with everything after it`() = runTest {
        behindOnCircle123(held = 4)
        whenever(queuedCircleLocationDao.circleIds()).thenReturn(listOf("circle-123"))
        whenever(queuedCircleLocationDao.oldest("circle-123", 50))
            .thenReturn(listOf(kept(1, "circle-123", 100L), kept(2, "circle-123", 200L)))

        locationRepository.flushQueued { _, _ -> false }

        verify(queuedCircleLocationDao, never()).deleteById(any())
    }

    @Test
    fun `a long backlog goes out a share a minute, oldest first, and the rest waits for later live points`() = runTest {
        // The server counts late points against the same per-user minute as live ones
        // (120 over all of the user's devices), so a backlog must never take all of it.
        behindOnCircle123(held = 4)
        whenever(queuedCircleLocationDao.circleIds()).thenReturn(listOf("circle-123"))
        whenever(queuedCircleLocationDao.oldest("circle-123", 2))
            .thenReturn(listOf(kept(1, "circle-123", 100L), kept(2, "circle-123", 200L)))
            .thenReturn(listOf(kept(3, "circle-123", 300L), kept(4, "circle-123", 400L)))
        var now = 1_000_000L
        val pace = KeptPointPace(perMinute = 2, now = { now })
        val sent = mutableListOf<Long>()

        locationRepository.flushQueued(pace = pace) { _, row -> sent += row.timestamp; true }
        assertEquals(listOf(100L, 200L), sent)

        // The next live point, half a minute later: none of the rest goes with it.
        now += 30_000L
        locationRepository.flushQueued(pace = pace) { _, row -> sent += row.timestamp; true }
        assertEquals(listOf(100L, 200L), sent)

        // A minute after the first share, the next one goes.
        now += 30_000L
        locationRepository.flushQueued(pace = pace) { _, row -> sent += row.timestamp; true }
        assertEquals(listOf(100L, 200L, 300L, 400L), sent)
        verify(queuedCircleLocationDao, times(4)).deleteById(any())
        // A third of the server's minute at most; the live points keep the rest.
        assertTrue(LocationRepository.KEPT_POINTS_PER_MINUTE in 1..40)
    }

    @Test
    fun `an active SOS keeps its location going to a circle this device is behind on`() = runTest {
        // As on iPhone and the web: reaching the circle in an emergency matters
        // more than who else may read it.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        behindOnCircle123(held = 3)
        // Raised by this user (setup signs in as test-user-id) and still active.
        whenever(sosAlertDao.countActiveRaisedBy("test-user-id", "circle-123")).thenReturn(1)

        locationRepository.sendLocation("circle-123", createMockLocation(), batteryLevel = 80)

        verify(circleRepo).ensurePrivacyPrimed("circle-123")
    }

    @Test
    fun `sendLocation returns failure when device registration fails`() = runTest {
        // Given: Personal key doesn't exist and can't be fetched
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)

        // Mock API response for key fetch (no key on server)
        val emptyResponse: Response<ApiResponse<com.pingolin.track.data.remote.dto.CircleKeyBundleResponse?>> = Response.success(
            ApiResponse(success = true, data = null, error = null)
        )
        whenever(tracksApi.getSelfKey()).thenReturn(emptyResponse)

        val location = createMockLocation()

        // When: Attempting to send location
        val result = locationRepository.sendLocation("circle-123", location, batteryLevel = 80)

        // Then: Should fail because device registration failed (no personal key)
        assertTrue(result.isFailure)
        assertEquals("Device registration failed", result.exceptionOrNull()?.message)
    }

    // sendSelfTrackingLocation's success path reaches the native
    // CryptoManager.encryptLocation(), which is UniFFI-generated and cannot be
    // mocked with Mockito — so only its failure paths are covered here.

    @Test
    fun `sendSelfTrackingLocation fails when personal key missing`() = runTest {
        // Given: Personal key does not exist
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)

        // Mock API response for key fetch (no key on server)
        val emptyResponse: Response<ApiResponse<com.pingolin.track.data.remote.dto.CircleKeyBundleResponse?>> = Response.success(
            ApiResponse(success = true, data = null, error = null)
        )
        whenever(tracksApi.getSelfKey()).thenReturn(emptyResponse)

        val location = createMockLocation()

        // When: Attempting to send self-tracking location
        val result = locationRepository.sendSelfTrackingLocation(location, batteryLevel = 80)

        // Then: Should fail because personal key creation failed
        assertTrue(result.isFailure)
    }

    @Test
    fun `ensureDeviceRegistered returns true when already registered`() = runTest {
        // Given: Device is already registered and personal key exists (set up in @Before)

        // When: Checking registration
        val result = locationRepository.ensureDeviceRegistered()

        // Then: Should return true without making API calls
        assertTrue(result)
        verify(tracksApi, never()).createDevice(any())
    }

    @Test
    fun `ensureDeviceRegistered registers device when not registered`() = runTest {
        // Given: Device is not registered but personal key exists
        whenever(sessionManager.isDeviceRegistered()).thenReturn(false)

        // Mock encryption for device registration
        whenever(cryptoManager.encryptDataV2(eq(LocationRepository.SELF_CIRCLE_ID), any(), any())).thenReturn(testEncrypted)

        // Mock successful device registration
        val encryptedBlob = com.pingolin.track.data.remote.dto.EncryptedBlobResponse(
            nonce = "test-nonce",
            ciphertext = "test-cipher",
            keyId = "test-key-id"
        )
        val deviceResponse = DeviceResponse(
            id = "test-device-id",
            encryptedDeviceName = encryptedBlob,
            encryptedDeviceType = encryptedBlob,
            isActive = true,
            lastSeenDayBucket = null,
            dayBucket = 19500,
            pushRegistered = true,
        )
        val response: Response<ApiResponse<DeviceResponse>> = Response.success(
            ApiResponse(success = true, data = deviceResponse, error = null)
        )
        whenever(tracksApi.createDevice(any())).thenReturn(response)

        // When: Ensuring device is registered
        val result = locationRepository.ensureDeviceRegistered()

        // Then: Should register successfully
        assertTrue(result)
        verify(tracksApi).createDevice(any())
        verify(sessionManager).setDeviceRegistered(true)
    }

    @Test
    fun `hasPersonalKey delegates to cryptoManager`() {
        // Given: Personal key exists (set up in @Before)

        // When: Checking for personal key
        val result = locationRepository.hasPersonalKey()

        // Then: Should return true
        assertTrue(result)
        verify(cryptoManager).hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID)
    }

    @Test
    fun `isStreaming returns false initially`() {
        // When: Checking streaming state before starting
        val result = locationRepository.isStreaming()

        // Then: Should be false
        assertFalse(result)
    }

    @Test
    fun `cleanup stops streaming and clears resources`() {
        // When: Cleaning up repository
        locationRepository.cleanup()

        // Then: Should have stopped streaming
        assertFalse(locationRepository.isStreaming())
    }

    @Test
    fun `syncPendingLocations returns success-zero when not authenticated`() = runTest {
        // Given: No user ID
        whenever(sessionManager.getUserId()).thenReturn(null)

        // When: Syncing pending locations
        val result = locationRepository.syncPendingLocations()

        // Then: Should succeed with zero synced (nothing to do when logged out)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `syncPendingLocations processes pending locations`() = runTest {
        // Given: Some pending locations
        val pendingLocations = listOf(
            PendingLocationEntity(
                id = 1,
                circleId = "circle-123",
                encryptedPayload = "nonce:ciphertext",
                timestamp = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
                retryCount = 0
            )
        )
        whenever(pendingLocationDao.getPending(100)).thenReturn(pendingLocations)

        // When: Syncing pending locations (without active stream, so they won't be sent)
        val result = locationRepository.syncPendingLocations()

        // Then: Should succeed with count of synced locations (0 because no stream)
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrNull())

        // syncPendingLocations deletes nothing by retry count: a 5-retry cap
        // would evict legitimately-queued locations for users offline ~1h+,
        // and age+size bounds already keep the queue finite.
    }

    @Test
    fun `SELF_CIRCLE_ID and SELF_CIRCLE_UUID constants are correct`() {
        assertEquals("self", LocationRepository.SELF_CIRCLE_ID)
        assertEquals("00000000-0000-0000-0000-000000000000", LocationRepository.SELF_CIRCLE_UUID)
    }

    // ===== Replay / reorder guard (filterReplayedMemberLocations, exercised via refreshLatestLocations) =====
    //
    // A member's location is E2E-encrypted and its timestamp lives inside the AEAD
    // (decrypted.timestamp), so the server can't forge it — but a compromised server
    // can REPLAY or REORDER old, validly-encrypted envelopes. The guard drops any
    // decrypted member location whose authenticated timestamp is <= the newest already
    // accepted for that (circle, user, device), so a stale envelope never rewinds the
    // displayed marker or pollutes the local cache. We observe accept/drop through
    // locationDao.insertAll (which gates BOTH the DB cache and the display fast-path
    // emit in refreshLatestLocations) and the freshLocations replay cache.

    /** Member ids with hex letters: upper case is another spelling of the same account. */
    private val userA = "a1b2c3d4-0000-4000-8000-00000000000a"
    private val ghost = "9b05e0d4-0000-4000-8000-00000000000b"

    /**
     * An envelope-encrypted latest-location DTO for [userId]/[deviceId]. The wrapped-key
     * fields route decryption through cryptoManager.unwrapAndDecryptLocationV2, stubbed
     * per test to yield a Location with a chosen authenticated timestamp.
     */
    private fun envelopeDto(userId: String, deviceId: String) = EncryptedLocationResponse(
        encryptedUserId = null,
        encryptedDeviceId = null,
        userId = userId,
        deviceId = deviceId,
        dayBucket = null,
        encryptedTimestamp = null,
        encryptedPayload = EncryptedPayload(
            nonce = "payload-nonce",
            ciphertext = "payload-cipher",
            wrappedMsgKey = "wrapped-key",
            wrappedMsgKeyNonce = "wrapped-nonce"
        )
    )

    private fun decryptedLocationAt(timestamp: Long) = uniffi.pingolin_mobile.Location(
        latitude = 37.0,
        longitude = -122.0,
        altitude = null,
        speed = null,
        heading = null,
        accuracy = null,
        timestamp = timestamp,
        batteryLevel = null,
        isCharging = null
    )

    /**
     * Drives one refreshLatestLocations() pass: the server returns [dto] and the
     * (authenticated, in-AEAD) decrypt yields [decryptedTs]. Returns the fetch result.
     */
    private suspend fun runRefresh(
        circleId: String,
        dto: EncryptedLocationResponse,
        decryptedTs: Long
    ): LocationFetchResult? {
        whenever(cryptoManager.hasCircleKey(circleId)).thenReturn(true)
        whenever(cryptoManager.hasCircleKeyInUse(circleId)).thenReturn(true)
        // The sender-bound envelope read (unwrapAndDecryptLocationBoundForUser) — the
        // decrypt path decryptLocationResponseWithResult actually takes for a wrapped
        // payload. senderVerified=false mirrors a legacy v2/v1 row.
        whenever(cryptoManager.unwrapAndDecryptLocationBoundForUser(any(), any(), any(), any(), any(), any()))
            .thenReturn(uniffi.pingolin_mobile.BoundLocation(decryptedLocationAt(decryptedTs), false))
        val response: Response<ApiResponse<LatestLocationsWrapper>> = Response.success(
            ApiResponse(
                success = true,
                data = LatestLocationsWrapper(locations = listOf(dto)),
                error = null
            )
        )
        whenever(tracksApi.getLatestLocations(circleId)).thenReturn(response)
        return locationRepository.refreshLatestLocations(circleId).getOrNull()
    }

    /** The latest (circleId, locations) the repo pushed to the map display fast-path. */
    private fun lastEmittedDisplay(): Pair<String, List<LocationEntity>>? =
        locationRepository.freshLocations.replayCache.lastOrNull()

    @Test
    fun `refreshLatestLocations accepts first-seen member location`() = runTest {
        val result = runRefresh("circle-1", envelopeDto(userA, "devA"), decryptedTs = 1000L)

        // First-seen member -> accepted: cached and pushed to the display fast-path.
        assertEquals(1, result?.successCount)
        verify(locationDao, times(1)).insertAll(any())
        assertEquals(1000L, lastEmittedDisplay()?.second?.single()?.timestamp)
    }

    @Test
    fun `refreshLatestLocations drops replayed older authenticated timestamp`() = runTest {
        val dto = envelopeDto(userA, "devA")

        runRefresh("circle-1", dto, decryptedTs = 1000L)
        val replay = runRefresh("circle-1", dto, decryptedTs = 500L)

        // The older (replayed) envelope is dropped: no second cache write, and the
        // display still shows the newer position — the marker never rewinds.
        verify(locationDao, times(1)).insertAll(any())
        assertEquals(1000L, lastEmittedDisplay()?.second?.single()?.timestamp)
        // Telemetry is unaffected — a replay-drop is NOT counted as a decryption failure.
        assertEquals(1, replay?.successCount)
        assertEquals(0, replay?.decryptionFailures)
    }

    @Test
    fun `refreshLatestLocations drops replayed equal authenticated timestamp`() = runTest {
        val dto = envelopeDto(userA, "devA")

        runRefresh("circle-1", dto, decryptedTs = 1000L)
        runRefresh("circle-1", dto, decryptedTs = 1000L)

        // An identical re-delivered envelope (same authenticated ts) is a replay -> dropped.
        verify(locationDao, times(1)).insertAll(any())
    }

    @Test
    fun `refreshLatestLocations accepts newer authenticated timestamp`() = runTest {
        val dto = envelopeDto(userA, "devA")

        runRefresh("circle-1", dto, decryptedTs = 1000L)
        runRefresh("circle-1", dto, decryptedTs = 2000L)

        // A genuinely newer update advances the guard and is displayed.
        verify(locationDao, times(2)).insertAll(any())
        assertEquals(2000L, lastEmittedDisplay()?.second?.single()?.timestamp)
    }

    @Test
    fun `refreshLatestLocations does not guard self circle`() = runTest {
        val dto = envelopeDto(userA, "devA")

        runRefresh(LocationRepository.SELF_CIRCLE_ID, dto, decryptedTs = 1000L)
        runRefresh(LocationRepository.SELF_CIRCLE_ID, dto, decryptedTs = 500L)

        // Self is device-local (selfLocation) and never server-decrypted for display,
        // so the guard is skipped and both writes go through — self path left untouched.
        verify(locationDao, times(2)).insertAll(any())
    }

    @Test
    fun `refreshLatestLocations leaves off the map a member this device does not show`() = runTest {
        // Somebody on the member list whom this device does not confirm is hidden
        // everywhere, their position included.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.membersHiddenIn("circle-1")).thenReturn(setOf(ghost))

        val result = runRefresh("circle-1", envelopeDto(ghost, "devG"), decryptedTs = 1000L)

        verify(locationDao, never()).insertAll(any())
        assertNull(lastEmittedDisplay())
        // It decrypted fine: not a failure, just not shown.
        assertEquals(1, result?.successCount)
    }

    // ===== One spelling per account in the rows =====

    @Test
    fun `a member's rows under two spellings of their id are one member's - an older one is a replay`() = runTest {
        runRefresh("circle-1", envelopeDto(userA.uppercase(), "devA"), decryptedTs = 1000L)
        val replay = runRefresh("circle-1", envelopeDto(userA.replace("-", ""), "devA"), decryptedTs = 500L)

        verify(locationDao, times(1)).insertAll(any())
        assertEquals(userA, lastEmittedDisplay()?.second?.single()?.userId)
        assertEquals(0, replay?.decryptionFailures)
    }

    @Test
    fun `a member this device does not show stays off the map under another spelling of their id`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.membersHiddenIn("circle-1")).thenReturn(setOf(ghost))

        runRefresh("circle-1", envelopeDto("{${ghost.uppercase()}}", "devG"), decryptedTs = 1000L)

        verify(locationDao, never()).insertAll(any())
        assertNull(lastEmittedDisplay())
    }

    @Test
    fun `the place alerts judge a member under their one spelling`() = runTest {
        runRefresh("circle-1", envelopeDto("urn:uuid:$userA", "devA"), decryptedTs = 1000L)

        argumentCaptor<List<LocationEntity>>().apply {
            verify(placeAlertEvaluator).onLocationsPersisted(capture(), any())
            assertEquals(userA, firstValue.single().userId)
        }
    }

    @Test
    fun `a row naming no account is no member's - dropped, and counted as a row that didn't open`() = runTest {
        for (named in listOf("userA", " $userA", "")) {
            val result = runRefresh("circle-1", envelopeDto(named, "devA"), decryptedTs = 1000L)

            assertEquals(named, 0, result?.successCount)
            assertEquals(named, 1, result?.decryptionFailures)
        }
        verify(locationDao, never()).insertAll(any())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())
    }

    // ===== Only a row the CURRENT key opened is live =====

    /** [runRefresh] where the payload opened under [version], and only [current] versions are current. */
    private suspend fun runRefreshAt(dto: EncryptedLocationResponse, decryptedTs: Long, version: Int, current: Set<Int> = setOf(7)): LocationFetchResult? {
        whenever(cryptoManager.isCurrentKeyVersion(any(), any())).thenAnswer { it.getArgument<Int>(1) in current }
        whenever(cryptoManager.hasCircleKey("circle-1")).thenReturn(true)
        whenever(cryptoManager.unwrapAndDecryptLocationBoundForUser(any(), any(), any(), any(), any(), any()))
            .thenReturn(uniffi.pingolin_mobile.BoundLocation(decryptedLocationAt(decryptedTs), true, version))
        whenever(tracksApi.getLatestLocations("circle-1")).thenReturn(
            Response.success(ApiResponse(success = true, data = LatestLocationsWrapper(locations = listOf(dto)), error = null))
        )
        return locationRepository.refreshLatestLocations("circle-1").getOrNull()
    }

    private suspend fun inserted(): List<LocationEntity> {
        val all = mutableListOf<LocationEntity>()
        argumentCaptor<List<LocationEntity>>().apply {
            verify(locationDao, atLeast(0)).insertAll(capture())
            allValues.forEach { all += it }
        }
        return all
    }

    @Test
    fun `a row only an older key opened is history - kept, never the marker, never alert input, never the guard's, no failure`() = runTest {
        val result = runRefreshAt(envelopeDto(userA, "devA"), decryptedTs = 5000L, version = 6)

        assertEquals("heard from, and no failure", 1, result?.successCount)
        assertEquals(0, result?.decryptionFailures)
        assertEquals(listOf(false), inserted().map { it.keyCurrent })
        assertNull("never the map's live fast path", lastEmittedDisplay())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())

        // The guard didn't take its time: a CURRENT fix older than it is still live.
        runRefreshAt(envelopeDto(userA, "devA"), decryptedTs = 2000L, version = 7)
        assertEquals(2000L, lastEmittedDisplay()?.second?.single()?.timestamp)
        assertEquals(listOf(false, true), inserted().map { it.keyCurrent })
    }

    @Test
    fun `a sealed id the current key didn't open makes the row history, and one that doesn't open drops it`() = runTest {
        val sealed = envelopeDto(userA, "devA").copy(encryptedUserId = EncryptedPayload("n", "c"))
        whenever(cryptoManager.decryptDataV2Opened(any(), any(), eq("user_id"))).thenReturn(uniffi.pingolin_mobile.OpenedText(userA, 6))

        runRefreshAt(sealed, decryptedTs = 5000L, version = 7)
        assertEquals(listOf(false), inserted().map { it.keyCurrent })

        whenever(cryptoManager.decryptDataV2Opened(any(), any(), eq("user_id"))).thenThrow(RuntimeException("no key opens it"))
        val dropped = runRefreshAt(sealed, decryptedTs = 6000L, version = 7)
        assertEquals(1, dropped?.decryptionFailures)
        assertEquals(0, dropped?.successCount)
        assertEquals("nothing more stored", 1, inserted().size)
    }

    @Test
    fun `a web row read through the JSON path is judged by the version that opened it`() = runTest {
        whenever(cryptoManager.isCurrentKeyVersion(any(), any())).thenAnswer { it.getArgument<Int>(1) == 7 }
        whenever(cryptoManager.hasCircleKey("circle-1")).thenReturn(true)
        whenever(cryptoManager.decryptLocationBoundForUser(any(), any(), any())).thenThrow(RuntimeException("not SDK format"))
        whenever(cryptoManager.decryptDataOpened(any(), any())).thenReturn(uniffi.pingolin_mobile.OpenedText("""{"lat":1.0,"lng":2.0,"timestamp":5000}""", 6))
        val plain = envelopeDto(userA, "devA").copy(encryptedPayload = EncryptedPayload(nonce = "n", ciphertext = "c"))
        whenever(tracksApi.getLatestLocations("circle-1")).thenReturn(
            Response.success(ApiResponse(success = true, data = LatestLocationsWrapper(locations = listOf(plain)), error = null))
        )

        locationRepository.refreshLatestLocations("circle-1")

        assertEquals(listOf(false), inserted().map { it.keyCurrent })
    }

    // ===== A circle's row naming this account is never this device's fix =====

    private val me = "0e0e0b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"

    @Test
    fun `a row naming this account, plainly or sealed, in any spelling, is dropped before it opens and never counted`() = runTest {
        whenever(sessionManager.getUserId()).thenReturn(me)
        for (named in listOf(me, me.uppercase(), me.replace("-", ""))) {
            val plainly = runRefreshAt(envelopeDto(named, "devX"), decryptedTs = 5000L, version = 7)
            assertEquals(named, 0, plainly?.totalReceived)
            assertEquals(named, 0, plainly?.decryptionFailures)
            assertEquals(named, false, plainly?.allFailed)
        }
        whenever(cryptoManager.decryptDataV2Opened(any(), any(), eq("user_id"))).thenReturn(uniffi.pingolin_mobile.OpenedText("{$me}", 7))
        val sealed = runRefreshAt(envelopeDto(userA, "devA").copy(encryptedUserId = EncryptedPayload("n", "c")), decryptedTs = 5000L, version = 7)

        assertEquals(0, sealed?.totalReceived)
        verify(cryptoManager, never()).unwrapAndDecryptLocationBoundForUser(any(), any(), any(), any(), any(), any())
        verify(locationDao, never()).insertAll(any())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())
    }

    @Test
    fun `with no account, no circle's row is taken - nothing to tell this account's rows by`() = runTest {
        whenever(sessionManager.getUserId()).thenReturn(null)

        val result = runRefreshAt(envelopeDto(userA, "devA"), decryptedTs = 5000L, version = 7)

        assertEquals(0, result?.totalReceived)
        assertEquals(0, result?.decryptionFailures)
        verify(locationDao, never()).insertAll(any())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())
    }

    @Test
    fun `this account's own personal history is its own, and never judged as the server's word`() = runTest {
        whenever(sessionManager.getUserId()).thenReturn(me)
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID)).thenReturn(true)
        whenever(cryptoManager.unwrapAndDecryptLocationBoundForUser(any(), any(), any(), any(), any(), any()))
            .thenReturn(uniffi.pingolin_mobile.BoundLocation(decryptedLocationAt(5000L), true, 7))
        whenever(tracksApi.getLatestLocations(LocationRepository.SELF_CIRCLE_ID)).thenReturn(
            Response.success(ApiResponse(success = true, data = LatestLocationsWrapper(locations = listOf(envelopeDto(me, "devX"))), error = null))
        )

        val result = locationRepository.refreshLatestLocations(LocationRepository.SELF_CIRCLE_ID).getOrNull()

        assertEquals(1, result?.successCount)
        verify(placeAlertEvaluator).onLocationsPersisted(any(), eq(true))
    }

    @Test
    fun `this phone's own fix is judged as its own`() = runTest {
        whenever(cryptoManager.encryptLocation(eq(LocationRepository.SELF_CIRCLE_UUID), any())).thenReturn(testEncrypted)

        locationRepository.storeLocalLocation(createMockLocation())

        verify(placeAlertEvaluator).onLocationsPersisted(any(), eq(false))
    }

    // ===== A time beyond this phone's clock is no fix of now =====

    @Test
    fun `a location timed beyond the clock is dropped, and never blocks the genuine fix after it`() = runTest {
        val farFuture = System.currentTimeMillis() + com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS + 60_000

        val result = runRefreshAt(envelopeDto(userA, "devA"), decryptedTs = farFuture, version = 7)
        assertEquals("heard from", 1, result?.successCount)
        assertNull(lastEmittedDisplay())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())

        val now = System.currentTimeMillis()
        runRefreshAt(envelopeDto(userA, "devA"), decryptedTs = now, version = 7)
        assertEquals(now, lastEmittedDisplay()?.second?.single()?.timestamp)
    }

    @Test
    fun `the clock's allowance is ten minutes`() {
        assertEquals(600_000L, com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS)
        assertTrue(com.pingolin.track.util.LiveTime.plausible(1_600_000, 1_000_000))
        assertFalse(com.pingolin.track.util.LiveTime.plausible(1_600_001, 1_000_000))
    }

    // ===== A session key opens only its own circle's rows, and only the key asked for is taken =====

    private fun sessionDto() = envelopeDto(userA, "devA").copy(
        encryptedPayload = EncryptedPayload(nonce = "n", ciphertext = "c", keyId = "s:5e551011-0000-4000-8000-00000000000a"),
    )

    private fun sessionKeyAnswer(id: String) = Response.success(ApiResponse(
        success = true,
        data = com.pingolin.track.data.remote.dto.SessionKeyResponse(
            sessionKeyId = id, encryptedSessionKey = "k", keyNonce = "kn", keyVersion = 7, userId = userA, deviceId = "devA",
            createdAt = "2026-09-30T00:00:00Z", expiresAt = "2026-10-01T00:00:00Z",
        ),
        error = null,
    ))

    @Test
    fun `a session key is looked for in the row's own circle, and taken under the id asked for`() = runTest {
        whenever(cryptoManager.hasPeerSessionKeyFor("circle-1", "s:5e551011-0000-4000-8000-00000000000a")).thenReturn(false)
        whenever(tracksApi.getSessionKey("circle-1", "5e551011-0000-4000-8000-00000000000a"))
            .thenReturn(sessionKeyAnswer("5E551011-0000-4000-8000-00000000000A"))
        whenever(cryptoManager.decryptLocationWithSessionKeyBoundForUser(any(), any(), any(), any()))
            .thenReturn(uniffi.pingolin_mobile.BoundLocation(decryptedLocationAt(5000L), true, 7))
        whenever(tracksApi.getLatestLocations("circle-1")).thenReturn(
            Response.success(ApiResponse(success = true, data = LatestLocationsWrapper(locations = listOf(sessionDto())), error = null))
        )
        whenever(cryptoManager.hasCircleKey("circle-1")).thenReturn(true)

        locationRepository.refreshLatestLocations("circle-1")

        verify(cryptoManager, never()).hasPeerSessionKey(any())
        verify(cryptoManager).importPeerSessionKey(eq("circle-1"), eq("5e551011-0000-4000-8000-00000000000a"), any(), any(), eq(7), any())
    }

    @Test
    fun `an answer about another session key than the one asked for is never taken`() = runTest {
        whenever(cryptoManager.hasPeerSessionKeyFor(any(), any())).thenReturn(false)
        whenever(tracksApi.getSessionKey(any(), any())).thenReturn(sessionKeyAnswer("0dd0e000-0000-4000-8000-00000000000b"))
        whenever(cryptoManager.decryptLocationWithSessionKeyBoundForUser(any(), any(), any(), any())).thenThrow(RuntimeException("no key"))
        whenever(cryptoManager.decryptLocationBoundForUser(any(), any(), any())).thenThrow(RuntimeException("no key"))
        whenever(tracksApi.getLatestLocations("circle-1")).thenReturn(
            Response.success(ApiResponse(success = true, data = LatestLocationsWrapper(locations = listOf(sessionDto())), error = null))
        )
        whenever(cryptoManager.hasCircleKey("circle-1")).thenReturn(true)

        locationRepository.refreshLatestLocations("circle-1")

        verify(cryptoManager, never()).importPeerSessionKey(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `refreshLatestLocations guards per device so a second device is not dropped`() = runTest {
        // A member's SECOND device with a genuinely-older timestamp must not be dropped
        // by the first device's newer one — the guard key is (circle, user, device).
        runRefresh("circle-1", envelopeDto(userA, "devA"), decryptedTs = 1000L)
        runRefresh("circle-1", envelopeDto(userA, "devB"), decryptedTs = 500L)

        verify(locationDao, times(2)).insertAll(any())
    }

    // ===== Sender-side coarsening for "approximate" (bubble) sharing (coarsenLocation) =====
    //
    // Deterministic snap-to-grid: a stationary sender always reports the SAME point
    // (so a viewer can't average many updates back to the truth), the point stays
    // within the bubble radius, and radius<=0 is the identity. Pure companion helper,
    // so these run without the native SDK or Android runtime.

    @Test
    fun `coarsenLocation is identity when radius is zero or negative`() {
        val lat = 37.7749
        val lng = -122.4194

        // radius 0 (exact/self path returns 0) and any negative radius are pass-through.
        assertEquals(lat to lng, LocationRepository.coarsenLocation(lat, lng, 0.0))
        assertEquals(lat to lng, LocationRepository.coarsenLocation(lat, lng, -500.0))
    }

    @Test
    fun `coarsenLocation keeps the reported point within the bubble radius`() {
        val lat = 37.7749
        val lng = -122.4194
        val radiusMeters = 1609.344 // 1 mile

        val (cLat, cLng) = LocationRepository.coarsenLocation(lat, lng, radiusMeters)

        // Snap displacement is at most half a cell per axis (~0.71*radius on the
        // diagonal), so the coarsened point is always inside the bubble.
        assertTrue(
            "coarsened point must stay within the radius",
            haversineMeters(lat, lng, cLat, cLng) <= radiusMeters
        )
        // And it actually moved off the exact point (not a no-op for a real bubble).
        assertNotEquals(lat, cLat, 0.0)
    }

    @Test
    fun `coarsenLocation is deterministic and collapses same-cell points to one output`() {
        val radiusMeters = 1609.344 // 1 mile
        val baseLat = 40.0
        val baseLng = -75.0

        val snapped = LocationRepository.coarsenLocation(baseLat, baseLng, radiusMeters)

        // Deterministic: a stationary sender always reports the SAME point (no random
        // jitter), so a viewer cannot average repeated updates back toward the truth.
        assertEquals(snapped, LocationRepository.coarsenLocation(baseLat, baseLng, radiusMeters))

        // A small east-west move (well inside one ~1.6 km cell) snaps to the SAME point,
        // so minor real movement doesn't reveal a finer position. Longitude is nudged
        // with latitude held fixed, since the east-west cell size is latitude-dependent
        // by design (a degree of longitude shrinks toward the poles).
        val nudgedEast = LocationRepository.coarsenLocation(baseLat, baseLng + 0.00002, radiusMeters)
        assertEquals(snapped, nudgedEast)

        // A small north-south move inside the same cell snaps to the SAME latitude.
        val nudgedNorth = LocationRepository.coarsenLocation(baseLat + 0.00002, baseLng, radiusMeters)
        assertEquals(snapped.first, nudgedNorth.first, 0.0)
    }

    /** Great-circle distance in meters (test-only, for the within-radius assertion). */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ===== 404 handling: confirm before destroying local state =====
    //
    // A 404 from the per-circle locations endpoint is ambiguous — "you were removed"
    // and "that pod is mid-restart" are identical at this call site, and a rolling
    // deploy produces the second routinely. Deleting on one such response would cost
    // a device its circle: it would stop publishing location there and tell the user
    // it had been removed. Only the circle LIST is authoritative.

    private suspend fun run404(circleId: String = "circle-1") {
        val notFound: Response<ApiResponse<LatestLocationsWrapper>> =
            Response.error(404, okhttp3.ResponseBody.create(null, ""))
        whenever(cryptoManager.hasCircleKey(circleId)).thenReturn(true)
        whenever(cryptoManager.hasCircleKeyInUse(circleId)).thenReturn(true)
        whenever(tracksApi.getLatestLocations(circleId)).thenReturn(notFound)
        locationRepository.refreshLatestLocations(circleId)
    }

    @Test
    fun `a 404 does NOT delete the circle when the list still contains it`() = runTest {
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.isStillAMember("circle-1")).thenReturn(true)

        run404()

        verify(circleDao, never()).deleteById(any())
    }

    @Test
    fun `a 404 does NOT delete the circle when membership cannot be determined`() = runTest {
        // Network down, list fetch failed — inconclusive must never destroy state.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.isStillAMember("circle-1")).thenReturn(null)

        run404()

        verify(circleDao, never()).deleteById(any())
    }

    @Test
    fun `a 404 DOES delete the circle once the list confirms removal`() = runTest {
        // The genuine case must still work, or we trade a false delete for a
        // permanent stale circle.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.isStillAMember("circle-1")).thenReturn(false)
        // The row has to BE there: the delete and the toast fire on the
        // transition, so an already-absent circle is deliberately a no-op.
        whenever(circleDao.getById("circle-1")).thenReturn(
            CircleEntity(
                id = "circle-1",
                name = "Book Club",
                encryptedKey = null,
                createdAt = 0L,
                updatedAt = 0L,
            )
        )

        run404()

        // The toast is emitted in the same branch as the delete, so the delete
        // standing in for it is faithful — and uxEventBus here is a real bus,
        // not a mock, so it cannot be verified directly.
        verify(circleDao, times(1)).deleteById("circle-1")
    }

    @Test
    fun `a circle gone from the whole list is told as gone, never as a removal`() = runTest {
        // Removed, left on another device, or deleted by its owner: the list can't say which.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.isStillAMember("circle-1")).thenReturn(false)
        whenever(circleDao.getById("circle-1")).thenReturn(
            CircleEntity(id = "circle-1", name = "Book Club", encryptedKey = null, createdAt = 0L, updatedAt = 0L)
        )
        val heard = mutableListOf<com.pingolin.track.util.UxEvent>()
        val listening = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { uxEventBus.events.collect { heard += it } }

        run404()

        assertEquals(listOf(com.pingolin.track.util.UxEvent.CircleGone("Book Club")), heard)
        listening.cancel()
    }

    @Test
    fun `a circle already gone locally is not re-announced`() = runTest {
        // The ids refreshed here come from cached ALERT rows, not the circle
        // table, so an alert pointing at a deleted circle drives this branch on
        // every pass. Re-announcing would turn that into an endless
        // "Removed from Unknown circle" toast — Unknown precisely because the
        // row it wanted to name had already been deleted on the pass before.
        val circleRepo = mock<CircleRepository>()
        whenever(circleRepositoryLazy.get()).thenReturn(circleRepo)
        whenever(circleRepo.isStillAMember("circle-1")).thenReturn(false)
        whenever(circleDao.getById("circle-1")).thenReturn(null)

        run404()

        verify(circleDao, never()).deleteById(any())
    }

    // ===== persistAndEvaluate: the single choke point =====
    //
    // Every decrypted location this device stores must reach the subscriber-side
    // place-alert evaluator. A location that bypasses it is a location no alert is
    // evaluated against — the silent-failure mode the whole model exists to
    // prevent — so both shapes of call site are covered here: the member-location
    // poll and this device's own fix.

    @Test
    fun `refreshLatestLocations feeds decrypted member locations to the place-alert evaluator`() = runTest {
        runRefresh("circle-1", envelopeDto(userA, "devA"), decryptedTs = 1000L)

        argumentCaptor<List<LocationEntity>>().apply {
            verify(placeAlertEvaluator).onLocationsPersisted(capture(), any())
            assertEquals(1, firstValue.size)
            assertEquals(userA, firstValue.single().userId)
            assertEquals(1000L, firstValue.single().timestamp)
        }
    }

    @Test
    fun `a row flagged history_only on the live poll is shown but never judged`() = runTest {
        // The latest endpoint never returns a late point. If it did, the row would be
        // stored like any other and still raise no alert.
        runRefresh("circle-1", envelopeDto(userA, "devA").copy(historyOnly = true), decryptedTs = 1000L)

        verify(locationDao, times(1)).insertAll(any())
        verify(placeAlertEvaluator, never()).onLocationsPersisted(any(), any())
    }

    @Test
    fun `storeLocalLocation feeds this device's own fix to the place-alert evaluator`() = runTest {
        // The "alert me when *I* arrive" case: the subscriber IS the mover, and the
        // alert is evaluated against this row with no circle involved at all.
        whenever(cryptoManager.encryptLocation(eq(LocationRepository.SELF_CIRCLE_UUID), any()))
            .thenReturn(testEncrypted)

        locationRepository.storeLocalLocation(createMockLocation())

        argumentCaptor<List<LocationEntity>>().apply {
            verify(placeAlertEvaluator).onLocationsPersisted(capture(), any())
            assertEquals("test-user-id", firstValue.single().userId)
        }
    }

    @Test
    fun `an evaluator failure never fails the location write`() = runTest {
        // Evaluation is a local convenience computed on data already in hand; it must
        // never be able to fail (or roll back) the location the user is depending on.
        whenever(placeAlertEvaluator.onLocationsPersisted(any(), any()))
            .thenThrow(RuntimeException("boom"))

        val result = runRefresh("circle-1", envelopeDto(userA, "devA"), decryptedTs = 1000L)

        assertEquals(1, result?.successCount)
        verify(locationDao, times(1)).insertAll(any())
    }

    // ---- The self key must never be minted over ------------------------------
    //
    // The personal key is the only thing that can open a user's personal location
    // history, and it lives nowhere but the phone. Minting a second one does not
    // fail loudly — it silently makes every personal location row on the account
    // permanently unreadable.
    //
    // Retrofit does not throw on a non-2xx: a 500, a 401 and a 503 all arrive as
    // an ordinary Response with isSuccessful == false, and none of them may fall
    // into the "no self key on server" branch and MINT. The reachable window is
    // narrow — ensurePersonalKeyExists returns early whenever a local key exists,
    // so this needs a device holding none: a fresh install, a re-login, cleared
    // data — but in that state one 5xx would be enough. iOS and the web follow
    // the same rule.
    //
    // The rule under test: mint ONLY when the server authoritatively says there
    // is no key (2xx + success + data == null). Anything else mints nothing.

    private fun noLocalSelfKey() {
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_ID)).thenReturn(false)
        whenever(cryptoManager.hasCircleKey(LocationRepository.SELF_CIRCLE_UUID)).thenReturn(false)
        whenever(cryptoManager.hasCircleKeyInUse(LocationRepository.SELF_CIRCLE_UUID)).thenReturn(false)
    }

    private fun errorSelfKeyResponse(code: Int): Response<ApiResponse<com.pingolin.track.data.remote.dto.CircleKeyBundleResponse?>> =
        Response.error(
            code,
            """{"error":"boom"}""".toResponseBody("application/json".toMediaTypeOrNull())
        )

    @Test
    fun `a server error never mints a self key`() = runTest {
        // 500 and 503 are the live-data cases: the account may well HAVE a key
        // and the server simply could not say so. 401 is the same shape — an
        // expired token must not be read as "this account has no key".
        listOf(500, 503, 401, 502, 429).forEach { code ->
            noLocalSelfKey()
            whenever(tracksApi.getSelfKey()).thenReturn(errorSelfKeyResponse(code))

            val result = locationRepository.ensurePersonalKeyExists()

            assertFalse("HTTP $code must not report a usable personal key", result)
        }

        // prepareCircleCreation is the assertion that bites. storeSelfKey alone
        // would not: here a wrongly entered mint dies on the first native call,
        // so it never reaches the wire and a storeSelfKey-only check would pass.
        // Entering the branch at all is the defect — on a real device that native
        // call succeeds and the key is gone.
        verify(cryptoManager, never()).prepareCircleCreation()
        verify(tracksApi, never()).storeSelfKey(any())
    }

    @Test
    fun `a 2xx envelope reporting failure never mints a self key`() = runTest {
        // The other half of "not authoritative": HTTP said 200 but the envelope
        // says the call did not succeed, so `data == null` carries no meaning.
        noLocalSelfKey()
        whenever(tracksApi.getSelfKey()).thenReturn(
            Response.success(ApiResponse(success = false, data = null, error = "server unavailable"))
        )

        assertFalse(locationRepository.ensurePersonalKeyExists())
        verify(tracksApi, never()).storeSelfKey(any())
        verify(cryptoManager, never()).prepareCircleCreation()
    }

    @Test
    fun `only an authoritative empty answer reaches the mint`() = runTest {
        // 2xx + success + no key: the server's own statement that this account
        // has none. This is the ONE case that may create one. The guard has to
        // leave it working — a fresh install with no key anywhere must still be
        // able to make one, or personal tracking never starts.
        //
        // Asserted on prepareCircleCreation, the first thing
        // createAndUploadSelfKey does: whether the mint then COMPLETES depends
        // on native crypto that cannot be faked here, so what this pins is which
        // branch was taken. The wire shape of that mint — no `replace` flag, so
        // a server holding a key can refuse it — is pinned separately by
        // StoreSelfKeyRequestContractTest.
        noLocalSelfKey()
        whenever(tracksApi.getSelfKey()).thenReturn(
            Response.success(ApiResponse(success = true, data = null, error = null))
        )

        locationRepository.ensurePersonalKeyExists()

        verify(cryptoManager, atLeastOnce()).prepareCircleCreation()
    }

    private fun createMockLocation(): Location {
        return mock<Location> {
            on { latitude } doReturn 37.7749
            on { longitude } doReturn -122.4194
            on { time } doReturn System.currentTimeMillis()
            on { hasAltitude() } doReturn false
            on { hasSpeed() } doReturn false
            on { hasBearing() } doReturn false
            on { hasAccuracy() } doReturn true
            on { accuracy } doReturn 10f
        }
    }

    // ===== first sign-in: the device's push route registers, and a refusal can't loop =====
    // (the server refuses every send of a device without a push route)

    private fun registeredDevice() = DeviceResponse(
        id = "test-device-id",
        encryptedDeviceName = com.pingolin.track.data.remote.dto.EncryptedBlobResponse(nonce = "", ciphertext = "", keyId = ""),
        encryptedDeviceType = com.pingolin.track.data.remote.dto.EncryptedBlobResponse(nonce = "", ciphertext = "", keyId = ""),
        isActive = true,
        lastSeenDayBucket = null,
        dayBucket = 19500,
        pushRegistered = true,
    )

    /** No personal key on this phone yet, and the server's copy can't be read now. */
    private suspend fun personalKeyNotHereYet() {
        noLocalSelfKey()
        whenever(tracksApi.getSelfKey()).thenReturn(
            Response.success(ApiResponse(success = false, data = null, error = "server unavailable"))
        )
    }

    @Test
    fun `a registration carrying a push route is sent before the personal key is here`() = runTest {
        // The server keeps only the route; the encrypted name and type are accepted and
        // discarded, empty ones too.
        whenever(sessionManager.isDeviceRegistered()).thenReturn(false)
        personalKeyNotHereYet()
        whenever(sessionManager.getPushToken()).thenReturn("fcm-token")
        whenever(sessionManager.getPushType()).thenReturn("fcm")
        whenever(tracksApi.createDevice(any())).thenReturn(Response.success(ApiResponse(success = true, data = registeredDevice(), error = null)))

        assertTrue(locationRepository.ensureDeviceRegistered())

        val sent = argumentCaptor<com.pingolin.track.data.remote.dto.CreateDeviceRequest>()
        verify(tracksApi).createDevice(sent.capture())
        assertEquals("fcm-token", sent.firstValue.pushToken)
        assertEquals(com.pingolin.track.data.remote.dto.EncryptedBlobRequest(nonce = "", ciphertext = ""), sent.firstValue.encryptedDeviceName)
        assertEquals(com.pingolin.track.data.remote.dto.EncryptedBlobRequest(nonce = "", ciphertext = ""), sent.firstValue.encryptedDeviceType)
        verify(cryptoManager, never()).encryptDataV2(eq(LocationRepository.SELF_CIRCLE_ID), any(), eq("device_name"))
    }

    @Test
    fun `a new push token re-registers before the personal key is here`() = runTest {
        personalKeyNotHereYet()
        whenever(sessionManager.getPushEndpoint()).thenReturn("https://push.example.com/up/abc")
        whenever(sessionManager.getPushType()).thenReturn("unifiedpush")
        whenever(tracksApi.createDevice(any())).thenReturn(Response.success(ApiResponse(success = true, data = registeredDevice(), error = null)))

        assertTrue(locationRepository.forceDeviceReregistration())

        verify(tracksApi).createDevice(argThat { pushEndpoint == "https://push.example.com/up/abc" })
    }

    @Test
    fun `a registration taken without a push route is not registered, and the next token registers it`() = runTest {
        // As on iOS: a token-less POST is answered 201 push_registered:false and writes
        // nothing; counted as registered, every fix after it would be refused.
        var now = 1_000L
        locationRepository.elapsedNow = { now }
        var registered = false
        whenever(sessionManager.isDeviceRegistered()).thenAnswer { registered }
        doAnswer { registered = it.getArgument(0); null }.whenever(sessionManager).setDeviceRegistered(any())
        whenever(cryptoManager.encryptDataV2(eq(LocationRepository.SELF_CIRCLE_ID), any(), any())).thenReturn(testEncrypted)
        whenever(tracksApi.createDevice(any())).thenReturn(
            Response.success(ApiResponse(success = true, data = registeredDevice().copy(pushRegistered = false), error = null)),
            Response.success(ApiResponse(success = true, data = registeredDevice(), error = null)),
        )

        assertFalse("no push route: not registered", locationRepository.ensureDeviceRegistered())
        assertFalse(registered)
        now += 60_000L
        assertFalse("and not asked again on every send", locationRepository.ensureDeviceRegistered())
        verify(tracksApi, times(1)).createDevice(any())

        // The token arrives: the very next send registers, cooldown or not.
        whenever(sessionManager.getPushToken()).thenReturn("fcm-token")
        whenever(sessionManager.getPushType()).thenReturn("fcm")
        assertTrue(locationRepository.ensureDeviceRegistered())
        assertTrue(registered)
        verify(tracksApi, times(2)).createDevice(any())
    }

    @Test
    fun `with neither the personal key nor a push route, nothing is sent`() = runTest {
        whenever(sessionManager.isDeviceRegistered()).thenReturn(false)
        personalKeyNotHereYet()

        assertFalse(locationRepository.ensureDeviceRegistered())
        assertFalse(locationRepository.forceDeviceReregistration())

        verify(tracksApi, never()).createDevice(any())
    }

    @Test
    fun `a device the server keeps refusing re-registers once per cooldown, not on every refused fix`() {
        // The re-registration itself is launched on Main: a dispatcher that never runs it keeps
        // this to the decision (and no coroutine fails later, out of this test).
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher())
        try {
            var now = 1_000L
            locationRepository.elapsedNow = { now }
            val callback = locationRepository.LocationStreamErrorCallback()

            callback.onLocationRejected("test-device-id", "Device not found or not owned by user")
            now += 60_000L
            callback.onLocationRejected("test-device-id", "Device not found or not owned by user")
            verify(sessionManager, times(1)).setDeviceRegistered(false)

            now = 1_000L + LocationRepository.REJECTION_REREGISTRATION_COOLDOWN_MS
            callback.onLocationRejected("test-device-id", "Device not found or not owned by user")
            verify(sessionManager, times(2)).setDeviceRegistered(false)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `the refusal cooldown`() {
        assertTrue("never yet", LocationRepository.rejectionReregistrationDue(0L, null))
        assertFalse(LocationRepository.rejectionReregistrationDue(299_999L, 0L))
        assertTrue(LocationRepository.rejectionReregistrationDue(300_000L, 0L))
    }

    @Test
    fun `a stream callback whose body throws never reaches the SDK - it can't take a throw from it`() {
        // UniFFI panics on a throw from a callback that returns no Result.
        doThrow(SecurityException("Could not encrypt value")).whenever(sessionManager).setDeviceRegistered(false)

        locationRepository.LocationStreamErrorCallback().onLocationRejected("test-device-id", "Device not found or not owned by user")
    }
}
