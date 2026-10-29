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

package com.pingolin.track.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.mockk.coEvery
import io.mockk.coVerify
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.repository.BoundaryRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
import retrofit2.Response
import uniffi.pingolin_mobile.BoundaryChecker
import uniffi.pingolin_mobile.BoundaryMonitor
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject

/**
 * Instrumented tests for BoundaryRepository.
 *
 * Tests the boundary monitoring functionality including:
 * - Circle membership fetching
 * - Boundary crossing detection
 * - Cache behavior for boundaries
 * - Event type hash computation
 *
 * All boundary checking is done client-side to maintain E2EE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class BoundaryRepositoryTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    // Mocks for dependencies
    private lateinit var mockTrackApi: TrackApi
    private lateinit var mockPlaceRepository: PlaceRepository
    private lateinit var mockSessionManager: SessionManager
    private lateinit var mockCryptoManager: CryptoManager
    private lateinit var mockBoundaryChecker: BoundaryChecker
    private lateinit var mockBoundaryMonitor: BoundaryMonitor
    private lateinit var mockPrivacySettingDao: com.pingolin.track.data.local.dao.PrivacySettingDao
    private lateinit var mockCircleRepository: com.pingolin.track.domain.repository.CircleRepository
    private lateinit var mockPendingCrossingDao: com.pingolin.track.data.local.dao.PendingCrossingDao

    private lateinit var boundaryRepository: BoundaryRepository

    @Before
    fun setUp() {
        hiltRule.inject()

        // Create mocks
        mockTrackApi = mockk(relaxed = true)
        mockPlaceRepository = mockk(relaxed = true)
        mockSessionManager = mockk(relaxed = true)
        mockCryptoManager = mockk(relaxed = true)
        mockBoundaryChecker = mockk(relaxed = true)
        mockBoundaryMonitor = mockk(relaxed = true)
        mockPrivacySettingDao = mockk(relaxed = true)
        mockCircleRepository = mockk(relaxed = true)
        mockPendingCrossingDao = mockk(relaxed = true)

        // Setup default mock behaviors
        every { mockSessionManager.getDeviceId() } returns "test_device_id"
        every { mockSessionManager.getUserId() } returns "test_user_id"
        every { mockCryptoManager.hasCircleKey(any()) } returns true

        // Create repository with mocks
        boundaryRepository = BoundaryRepository(
            tracksApi = mockTrackApi,
            placeRepository = mockPlaceRepository,
            sessionManager = mockSessionManager,
            cryptoManager = mockCryptoManager,
            boundaryChecker = mockBoundaryChecker,
            boundaryMonitor = mockBoundaryMonitor,
            privacySettingDao = mockPrivacySettingDao,
            circleRepository = mockCircleRepository,
            pendingCrossingDao = mockPendingCrossingDao,
            // Kept for this process only: nothing here pauses a circle.
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory()
        )
    }

    /**
     * Test that circle membership fetching works correctly.
     */
    @Test
    fun testLoadBoundariesForCircle() = runTest {
        val circleId = "test_circle_id"
        val places = listOf(
            com.pingolin.track.data.local.entity.PlaceEntity(
                id = "place_1",
                circleId = circleId,
                name = "Home",
                address = "123 Main St",
                latitude = 37.7749,
                longitude = -122.4194,
                radius = 100,
                icon = null,
                color = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                // Load-bearing: boundariesFromPlaces filters on alertsEnabled,
                // which defaults to false. Without it this fixture builds ZERO
                // boundaries and the test reads "expected:<2> but was:<0>".
                alertsEnabled = true
            ),
            com.pingolin.track.data.local.entity.PlaceEntity(
                id = "place_2",
                circleId = circleId,
                name = "Work",
                address = "456 Office Blvd",
                latitude = 37.7849,
                longitude = -122.4094,
                radius = 200,
                icon = null,
                color = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                alertsEnabled = true
            )
        )

        // Mock place repository response
        coEvery { mockPlaceRepository.refreshPlaces(circleId) } returns Result.success(places)

        // Execute
        val result = boundaryRepository.loadBoundariesForCircle(circleId)

        // Verify
        assertTrue("Load should succeed", result.isSuccess)
        assertEquals("Should load 2 boundaries", 2, result.getOrNull())

        // Verify boundary checker was called
        coVerify {
            mockBoundaryChecker.loadBoundaries(match<List<uniffi.pingolin_mobile.Boundary>> { boundaries ->
                boundaries.size == 2 &&
                boundaries.any { it.name == "Home" } &&
                boundaries.any { it.name == "Work" }
            })
        }
    }

    /**
     * The other side of the alertsEnabled gate — "an alert turns monitoring on".
     *
     * boundariesFromPlaces filters on alertsEnabled, so a fixture that never
     * sets it builds no boundaries. This test pins the rule directly, so a
     * change to the gate fails HERE, saying what it means, instead of only
     * showing up as a confusing count mismatch elsewhere.
     */
    @Test
    fun placesWithoutAnAlertAreNotMonitored() = runTest {
        val circleId = "test_circle_id"
        val places = listOf(
            com.pingolin.track.data.local.entity.PlaceEntity(
                id = "unmonitored_place", circleId = circleId, name = "No Alert Here",
                address = null, latitude = 37.7749, longitude = -122.4194, radius = 100,
                icon = null, color = null, createdAt = 0, updatedAt = 0,
                alertsEnabled = false
            )
        )
        coEvery { mockPlaceRepository.refreshPlaces(circleId) } returns Result.success(places)

        val result = boundaryRepository.loadBoundariesForCircle(circleId)

        assertTrue("Load should succeed", result.isSuccess)
        assertEquals(
            "A place with no alert targeting it must not be geofenced",
            0, result.getOrNull()
        )
        coVerify {
            mockBoundaryChecker.loadBoundaries(
                match<List<uniffi.pingolin_mobile.Boundary>> { it.isEmpty() }
            )
        }
    }

    /**
     * Test boundary crossing detection.
     */
    @Test
    fun testBoundaryCrossingDetection() = runTest {
        val latitude = 37.7749
        val longitude = -122.4194

        val expectedCrossings = listOf(
            uniffi.pingolin_mobile.BoundaryCrossing(
                boundaryId = "place_1",
                boundaryName = "Home",
                crossingType = uniffi.pingolin_mobile.BoundaryCrossingType.ENTER,
                distanceMeters = 0.0
            )
        )

        // Mock boundary checker response
        coEvery { mockBoundaryChecker.checkLocation(latitude, longitude) } returns expectedCrossings

        // Execute
        val crossings = boundaryRepository.checkLocation(latitude, longitude)

        // Verify
        assertEquals("Should detect 1 crossing", 1, crossings.size)
        assertEquals(
            "Crossing type should be enter",
            uniffi.pingolin_mobile.BoundaryCrossingType.ENTER,
            crossings[0].crossingType
        )
        assertEquals("Boundary name should match", "Home", crossings[0].boundaryName)
    }

    /**
     * Test that empty crossings are returned when outside all boundaries.
     */
    @Test
    fun testNoCrossingsOutsideBoundaries() = runTest {
        val latitude = 40.0000  // Far from any boundary
        val longitude = -75.0000

        // Mock no crossings
        coEvery { mockBoundaryChecker.checkLocation(latitude, longitude) } returns emptyList()

        // Execute
        val crossings = boundaryRepository.checkLocation(latitude, longitude)

        // Verify
        assertTrue("Should return empty list outside boundaries", crossings.isEmpty())
    }

    /**
     * Test cache behavior - boundaries should be cached per circle.
     */
    @Test
    fun testBoundaryCacheBehavior() = runTest {
        val circleId1 = "circle_1"
        val circleId2 = "circle_2"

        // Mock different places for different circles
        // alertsEnabled = true for the same reason as testLoadBoundariesForCircle:
        // without it both circles load ZERO boundaries, and this test would still
        // pass — it only counts loadBoundaries CALLS — while verifying cache
        // behaviour over empty boundary sets.
        val places1 = listOf(
            com.pingolin.track.data.local.entity.PlaceEntity(
                id = "place_1", circleId = circleId1, name = "Home 1",
                address = null, latitude = 37.7749, longitude = -122.4194, radius = 100,
                icon = null, color = null, createdAt = 0, updatedAt = 0,
                alertsEnabled = true
            )
        )
        val places2 = listOf(
            com.pingolin.track.data.local.entity.PlaceEntity(
                id = "place_2", circleId = circleId2, name = "Home 2",
                address = null, latitude = 38.7749, longitude = -121.4194, radius = 100,
                icon = null, color = null, createdAt = 0, updatedAt = 0,
                alertsEnabled = true
            )
        )

        coEvery { mockPlaceRepository.refreshPlaces(circleId1) } returns Result.success(places1)
        coEvery { mockPlaceRepository.refreshPlaces(circleId2) } returns Result.success(places2)

        // Load boundaries for both circles
        val result1 = boundaryRepository.loadBoundariesForCircle(circleId1)
        val result2 = boundaryRepository.loadBoundariesForCircle(circleId2)

        // Both should succeed
        assertTrue("Circle 1 load should succeed", result1.isSuccess)
        assertTrue("Circle 2 load should succeed", result2.isSuccess)

        // Verify boundary checker was called twice (once per circle)
        coVerify(exactly = 2) { mockBoundaryChecker.loadBoundaries(any()) }
    }

    /**
     * Test handling of place repository failure.
     *
     * BoundaryRepository.loadBoundariesForCircle propagates the underlying
     * exception from placeRepository.refreshPlaces rather than wrapping it
     * with a fixed string — see its
     * `Result.failure(refreshResult.exceptionOrNull() ?: Exception("Failed to load places"))`.
     * Assert on the mocked cause rather than a specific wrapper message.
     */
    @Test
    fun testLoadBoundariesHandlesPlaceRepositoryFailure() = runTest {
        val circleId = "failing_circle"

        // Mock failure
        coEvery { mockPlaceRepository.refreshPlaces(circleId) } returns Result.failure(Exception("Network error"))

        // Execute
        val result = boundaryRepository.loadBoundariesForCircle(circleId)

        // Verify the failure is propagated with the cause intact.
        assertTrue("Should return failure", result.isFailure)
        assertEquals(
            "Inner exception message should be preserved",
            "Network error",
            result.exceptionOrNull()?.message,
        )
    }

    /**
     * Test reporting a boundary crossing.
     */
    @Test
    fun testReportCrossing() = runTest {
        val placeId = "place_1"
        val circleId = "circle_1"
        val crossingUserId = "user_1"
        val eventType = "enter"

        // Mock crypto operations
        val encryptedData = uniffi.pingolin_mobile.EncryptedData(
            nonce = "test_nonce",
            ciphertext = "test_ciphertext"
        )
        every { mockCryptoManager.encryptData(eq(circleId), any()) } returns encryptedData
        every { mockCryptoManager.computeEventTypeHash(circleId, eventType) } returns "event_hash"

        // Mock API response
        val response = ApiResponse(
            success = true,
            data = ReportBoundaryCrossingResponse(eventId = "event_123", notificationsSent = 1),
            error = null
        )
        coEvery { mockTrackApi.reportBoundaryCrossing(any()) } returns Response.success(response)

        // Execute
        val result = boundaryRepository.reportCrossing(
            placeId = placeId,
            circleId = circleId,
            crossingUserId = crossingUserId,
            eventType = eventType
        )

        // Verify
        assertTrue("Report should succeed", result.isSuccess)
        assertEquals("Should return event ID", "event_123", result.getOrNull()?.eventId)
    }


    /**
     * Test user display name retrieval.
     */
    @Test
    fun testGetUserDisplayName() {
        val userId = "user_123"

        // Initially should return userId as fallback
        val fallbackName = boundaryRepository.getUserDisplayName(userId)
        assertEquals("Should fallback to userId", userId, fallbackName)

        // Set display names
        boundaryRepository.setUserDisplayNames(mapOf(
            userId to "John Doe",
            "user_456" to "Jane Doe"
        ))

        // Now should return the set display name
        val displayName = boundaryRepository.getUserDisplayName(userId)
        assertEquals("Should return set display name", "John Doe", displayName)
    }

    /**
     * Test checking own location against boundaries.
     */
    @Test
    fun testCheckOwnLocation() = runTest {
        val latitude = 37.7749
        val longitude = -122.4194

        val expectedCrossings = listOf(
            uniffi.pingolin_mobile.BoundaryCrossing(
                boundaryId = "place_1",
                boundaryName = "Home",
                crossingType = uniffi.pingolin_mobile.BoundaryCrossingType.EXIT,
                distanceMeters = 10.0
            )
        )

        // Mock boundary monitor response
        coEvery { mockBoundaryMonitor.checkOwnLocation(latitude, longitude) } returns expectedCrossings

        // Execute
        val crossings = boundaryRepository.checkOwnLocation(latitude, longitude)

        // Verify
        assertEquals("Should detect 1 crossing", 1, crossings.size)
        assertEquals(
            "Crossing type should be exit",
            uniffi.pingolin_mobile.BoundaryCrossingType.EXIT,
            crossings[0].crossingType
        )
    }

    /**
     * Test distance to boundary calculation.
     */
    @Test
    fun testDistanceToBoundary() = runTest {
        val boundaryId = "place_1"
        val latitude = 37.7749
        val longitude = -122.4194
        val expectedDistance = 50.0

        // Mock boundary checker response
        coEvery { mockBoundaryChecker.distanceToBoundary(boundaryId, latitude, longitude) } returns expectedDistance

        // Execute
        val distance = boundaryRepository.distanceToBoundary(boundaryId, latitude, longitude)

        // Verify
        assertNotNull("Distance should not be null", distance)
        assertEquals("Distance should match", expectedDistance, distance!!, 0.01)
    }

    /**
     * Test is inside boundary check.
     */
    @Test
    fun testIsInsideBoundary() = runTest {
        val boundaryId = "place_1"

        // Mock being inside
        coEvery { mockBoundaryChecker.isInsideBoundary(boundaryId) } returns true

        // Execute
        val isInside = boundaryRepository.isInsideBoundary(boundaryId)

        // Verify
        assertTrue("Should be inside boundary", isInside)

        // Mock being outside
        coEvery { mockBoundaryChecker.isInsideBoundary(boundaryId) } returns false

        // Execute again
        val isOutside = !boundaryRepository.isInsideBoundary(boundaryId)

        // Verify
        assertTrue("Should be outside boundary", isOutside)
    }
}
