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
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.QueuedCircleLocationEntity
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.OwnLastSent
import com.pingolin.track.location.OwnLastSentStore
import com.pingolin.track.util.ApproximateMembersStore
import com.pingolin.track.util.QueueMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.UxEventBus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * A circle this phone forgets takes this account's privacy row there with it, on every way it is
 * forgotten: left (or the consent withdrawn, which leaves), deleted, gone by a 403 or 404 alone, and
 * gone from the whole list after a 404.
 *
 * The server keeps the posture across a leave and a rejoin, but a kept row is only what this phone
 * last stored: a bubble, a pause or a battery switch set on another device just before the leave
 * never reached it. Every send gate takes a row that is there as read ([CircleRepository.
 * ensurePrivacyPrimed]), so after a rejoin a kept row would send an exact point, or the battery
 * level, until the list's posture was stored again; with the row gone, the first send asks the
 * server and holds back while it can't. The 404 branch also takes the circle's kept points and
 * what this phone last sent there, as the other ways do, through
 * [LocationRepository.forgetCircleLocations].
 *
 * Real Room behind the real repositories; the server and the crypto are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
// Plain Application: TrackApplication loads SQLCipher and the Rust SDK, which cannot load on the JVM.
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class ForgottenCircleTakesItsPostureTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ME = "5e1f0000-0000-4000-8000-0000000000e1"
    }

    private lateinit var db: TrackDatabase
    private lateinit var circles: CircleRepository
    private lateinit var locations: LocationRepository

    private val tracksApi: TrackApi = mockk(relaxed = true)
    private val cryptoManager: CryptoManager = mockk(relaxed = true)
    private val sessionManager: SessionManager = mockk(relaxed = true)
    private val records = Records()

    /** What this phone last sent each circle, in memory. */
    private class Records : OwnLastSentStore {
        val rows = mutableMapOf<String, OwnLastSent>()
        override fun get(circleId: String) = rows[circleId]
        override fun put(circleId: String, record: OwnLastSent) { rows[circleId] = record }
        override fun update(circleId: String, from: OwnLastSent, to: OwnLastSent): Boolean {
            if (rows[circleId] != from) return false
            rows[circleId] = to
            return true
        }
        override fun remove(circleId: String) { rows.remove(circleId) }
        override fun circles() = rows.keys.toList()
        override fun wipeAll() = rows.clear()
    }

    @Before
    fun setup() {
        every { sessionManager.getUserId() } returns ME
        every { sessionManager.getDeviceId() } returns "d-1"
        every { cryptoManager.hasCircleKey(any()) } returns true
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        locations = locationRepository { circles }
        circles = CircleRepository(
            previousIdentityLoader = mockk(relaxed = true),
            context = mockk(relaxed = true),
            tracksApi = tracksApi,
            circleDao = db.circleDao(),
            circleSortOrderDao = db.circleSortOrderDao(),
            circleMemberDao = db.circleMemberDao(),
            pendingInviteDao = db.pendingInviteDao(),
            privacySettingDao = db.privacySettingDao(),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            locationRepository = dagger.Lazy { locations },
            uxEventBus = UxEventBus(),
            currentKeyGate = CurrentKeyGate(db.circleDao(), cryptoManager, mockk(relaxed = true), sessionManager),
            keyStoreRecovery = mockk(relaxed = true),
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
            sosRepository = dagger.Lazy<SosRepository> { mockk(relaxed = true) },
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        ).also { it.ownLastSent = records }
    }

    /** The real [LocationRepository] over the same store, its circle repository [circleRepository]. */
    private fun locationRepository(circleRepository: () -> CircleRepository) = LocationRepository(
        locationProvider = mockk<LocationProvider>(relaxed = true),
        locationDao = db.locationDao(),
        pendingLocationDao = db.pendingLocationDao(),
        circleDao = db.circleDao(),
        privacySettingDao = db.privacySettingDao(),
        tracksApi = tracksApi,
        authApi = mockk<AuthApi>(relaxed = true),
        sessionManager = sessionManager,
        tokenRefresher = com.pingolin.track.util.TokenRefresher(sessionManager) { mockk(relaxed = true) },
        cryptoManager = cryptoManager,
        uxEventBus = mockk<UxEventBus>(relaxed = true),
        queueMonitor = mockk<QueueMonitor>(relaxed = true),
        circleRepository = dagger.Lazy { circleRepository() },
        boundaryRepository = mockk<dagger.Lazy<BoundaryRepository>>(relaxed = true),
        placeAlertEvaluator = mockk<PlaceAlertEvaluator>(relaxed = true),
        currentKeyGate = CurrentKeyGate(db.circleDao(), cryptoManager, mockk(relaxed = true), sessionManager),
        queuedCircleLocationDao = db.queuedCircleLocationDao(),
        approximateMembers = ApproximateMembersStore.inMemory(),
        newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
        pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    )

    @After
    fun teardown() {
        db.close()
    }

    private val circle = CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)

    /** The circle as this phone holds it: its row, an exact posture as last stored, a kept point and what it last sent there. */
    private suspend fun heldHere() {
        db.circleDao().insert(circle)
        db.privacySettingDao().insert(PrivacySettingEntity(circleId = CIRCLE, sharingMode = "exact", updatedAt = 0))
        db.queuedCircleLocationDao().insert(
            QueuedCircleLocationEntity(circleId = CIRCLE, latitude = 37.3349, longitude = -122.009, timestamp = 1_000L, createdAt = 1_000L)
        )
        records.put(
            CIRCLE,
            OwnLastSent(
                devicePseudonym = "device-ps", nonce = "01".repeat(24), ciphertext = "02".repeat(40), keyId = null,
                wrappedKey = null, wrappedKeyNonce = null, latitude = 37.3349, longitude = -122.009, accuracy = 9.0,
                timestampMs = 1_000L, keyVersion = 2, coarsenRadiusMeters = 0.0,
            ),
        )
    }

    /** Nothing of the circle's is left here: no posture, no kept point, no record of what was sent there. */
    private suspend fun assertNothingKept() {
        assertNull("its privacy row", db.privacySettingDao().get(CIRCLE))
        assertEquals("its kept points", 0, db.queuedCircleLocationDao().count(CIRCLE))
        assertNull("what this phone last sent there", records.get(CIRCLE))
    }

    private fun ok(): Response<ApiResponse<Unit>> = Response.success(ApiResponse(success = true, data = Unit, error = null))

    /** What the server says the posture is now: a bubble, set on another device before the leave. */
    private fun bubbleNow(): Response<ApiResponse<PrivacySettingsResponse>> = Response.success(
        ApiResponse(
            success = true,
            data = PrivacySettingsResponse(
                circleId = CIRCLE, sharingMode = "approximate", approximateRadiusMiles = 3,
                shareRealtime = true, shareHistory = true, shareBattery = true, pausedUntil = null, primaryDeviceId = null,
            ),
            error = null,
        )
    )

    @Test
    fun `leaving takes the circle's privacy row, so the first send after a rejoin asks the server and goes under what it says`() = runTest {
        heldHere()
        coEvery { tracksApi.leaveCircle(CIRCLE) } returns ok()

        assertTrue(circles.leaveCircle(CIRCLE).isSuccess)
        assertNothingKept()

        // Back in the circle: the list stores its row again, and the posture there is a bubble now.
        db.circleDao().insert(circle)
        coEvery { tracksApi.getPrivacySettings(CIRCLE) } returns bubbleNow()

        assertTrue(circles.ensurePrivacyPrimed(CIRCLE))
        coVerify(exactly = 1) { tracksApi.getPrivacySettings(CIRCLE) }
        assertEquals("approximate", db.privacySettingDao().get(CIRCLE)?.sharingMode)
        assertTrue("the first point goes as a bubble", locations.coarsenRadiusMetersFor(CIRCLE, System.currentTimeMillis()) > 0.0)
    }

    @Test
    fun `after a rejoin with the server out of reach, the first send holds back rather than go under the old posture`() = runTest {
        heldHere()
        coEvery { tracksApi.leaveCircle(CIRCLE) } returns ok()
        assertTrue(circles.leaveCircle(CIRCLE).isSuccess)

        db.circleDao().insert(circle)
        coEvery { tracksApi.getPrivacySettings(CIRCLE) } throws java.io.IOException("offline")

        assertFalse(circles.ensurePrivacyPrimed(CIRCLE))
        assertNull(db.privacySettingDao().get(CIRCLE))
    }

    @Test
    fun `deleting the circle takes its privacy row`() = runTest {
        heldHere()
        coEvery { tracksApi.deleteCircle(CIRCLE) } returns ok()

        assertTrue(circles.deleteCircle(CIRCLE).isSuccess)

        assertNothingKept()
    }

    @Test
    fun `a circle only a 403 or 404 says is gone takes its privacy row`() = runTest {
        heldHere()

        circles.deleteLocalCircle(CIRCLE)

        assertNothingKept()
        assertNull(db.circleDao().getById(CIRCLE))
    }

    /** A 404 for the circle's locations, with the list's word on whether this account is still in it. */
    private suspend fun gone404(stillAMember: Boolean?): Result<*> {
        val listing = mockk<CircleRepository>(relaxed = true)
        coEvery { listing.isStillAMember(CIRCLE) } returns stillAMember
        every { listing.ownLastSent } returns records
        coEvery { tracksApi.getLatestLocations(CIRCLE) } returns Response.error(404, """{"success":false}""".toResponseBody())
        return locationRepository { listing }.refreshLatestLocations(CIRCLE)
    }

    @Test
    fun `a circle the whole list no longer has after a 404 takes its kept points, its last send and its privacy row`() = runTest {
        heldHere()

        assertTrue(gone404(stillAMember = false).isFailure)

        assertNothingKept()
        assertNull(db.circleDao().getById(CIRCLE))
    }

    @Test
    fun `a 404 the list can't confirm, or contradicts, takes nothing`() = runTest {
        for (word in listOf(null, true)) {
            heldHere()

            assertTrue(gone404(stillAMember = word).isFailure)

            assertNotNull("list said $word: its privacy row", db.privacySettingDao().get(CIRCLE))
            assertEquals("list said $word: its kept points", 1, db.queuedCircleLocationDao().count(CIRCLE))
            assertNotNull("list said $word: what this phone last sent there", records.get(CIRCLE))
            assertNotNull("list said $word: the circle", db.circleDao().getById(CIRCLE))
            db.queuedCircleLocationDao().deleteCircle(CIRCLE)
        }
    }
}
