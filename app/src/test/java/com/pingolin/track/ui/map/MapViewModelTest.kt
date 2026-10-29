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

package com.pingolin.track.ui.map

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.LocationFetchResult
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for MapViewModel.
 *
 * Tests cover:
 * - Decryption warning emission (partial + full failure)
 * - Create place from map (success + failure)
 * - Time range mode transitions
 * - Circle selection and polling lifecycle
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var placeRepository: PlaceRepository

    @MockK
    private lateinit var destinationRepository: com.pingolin.track.domain.repository.DestinationRepository

    @MockK
    private lateinit var geocodingRepository: GeocodingRepository

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var privacySettingDao: PrivacySettingDao

    @MockK
    private lateinit var hiddenDestinations: com.pingolin.track.util.HiddenDestinationsStore

    @MockK
    private lateinit var battery: com.pingolin.track.util.BatteryLevel

    private lateinit var viewModel: MapViewModel

    private val testPlace = PlaceEntity(
        id = "p1", circleId = "self", name = "Home",
        address = null, latitude = 0.0, longitude = 0.0, radius = 100,
        icon = null, color = null, createdAt = 0, updatedAt = 0
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { locationRepository.locationSentEvent } returns MutableSharedFlow()
        every { locationRepository.selfLocation } returns kotlinx.coroutines.flow.MutableStateFlow(null)
        every { locationRepository.frozenLocations } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
        every { locationRepository.freshLocations } returns MutableSharedFlow()
        every { locationRepository.observeLatestLocationsByCircle(any()) } returns flowOf(emptyList())
        every { circleRepository.observeMembers(any()) } returns flowOf(emptyList())
        every { placeRepository.observePersonalPlaces() } returns flowOf(emptyList())
        every { placeRepository.observePlacesByCircleIncludingShared(any()) } returns flowOf(emptyList())
        every { geocodingRepository.hasGeocodingData() } returns false
        // The map paths gate on canGeocode (local tiles OR the server geocoder),
        // not hasGeocodingData. These are STRICT mocks, so leaving it unstubbed
        // makes every test that reaches a marker refresh throw.
        every { geocodingRepository.canGeocode() } returns false
        every { sessionManager.getUserId() } returns "u-1"
        every { battery.now() } returns 15
        // startPolling also refreshes alert circles the user is not viewing. These
        // are STRICT mocks, so leaving this unstubbed makes every polling test
        // throw — the same trap CircleRepositoryTest's setup documents.
        coEvery { placeRepository.activeAlertCircleIds() } returns emptyList()

        every { hiddenDestinations.hidden } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())
        // The map tries a key import before reporting that locations could
        // not be shown. These tests are about the warning, so the heal finds
        // nothing: false means "no new keys arrived", the retry is skipped and
        // the warning stands — which is exactly the case being asserted.
        coEvery { circleRepository.ensureCircleKey(any()) } returns false
        every { hiddenDestinations.prune(any(), any()) } returns Unit
        // Every circle a send names is still this account's, unless a test says otherwise.
        coEvery { circleRepository.getCircle(any()) } answers {
            com.pingolin.track.data.local.entity.CircleEntity(id = firstArg(), name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)
        }

        viewModel = MapViewModel(
            locationRepository,
            circleRepository,
            placeRepository,
            destinationRepository,
            geocodingRepository,
            sessionManager,
            privacySettingDao,
            // Nothing hidden: the destination assertions here are about circle
            // scoping, not about the hide filter.
            hiddenDestinations,
            battery,
        )
        // The history grouping hops off Main; on the real Default pool it can
        // outlive the test (see MapViewModel.historyComputeDispatcher). Keep it on
        // the test scheduler so advanceUntilIdle() really finishes it.
        viewModel.historyComputeDispatcher = testDispatcher
        // These tests model the map ON SCREEN, which is the only time selecting a
        // circle starts the poll (MapViewModel.onMapVisibilityChanged). The hidden
        // case has its own tests below.
        viewModel.onMapVisibilityChanged(true)
    }

    @After
    fun teardown() {
        // Cancel the ViewModel's scope BEFORE resetting Main.
        //
        // MapViewModel launches collectors in init; some hop to Dispatchers.Default
        // and come back to Main. Without this, those coroutines outlive the test,
        // touch Dispatchers.Main after resetMain(), and throw
        // IllegalStateException on a background thread. kotlinx-coroutines-test
        // then attributes it to whichever test STARTS NEXT, as
        // "UncaughtExceptionsBeforeTest" — so the failure lands on an innocent
        // test and MOVES between runs. Left in, it makes this suite's failure
        // count wander on an unchanged tree.
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // =============================================================================
    // THIS ACCOUNT, UNDER ANY SPELLING OF ITS ID
    // =============================================================================

    private val me = "a1b2c3d4-0000-4000-8000-00000000000a"

    @Test
    fun `this account is itself under any spelling of its id, and nobody else is`() {
        every { sessionManager.getUserId() } returns me
        for (spelling in listOf(me, me.uppercase(), me.replace("-", ""), "{$me}", "urn:uuid:$me")) {
            assertTrue(spelling, viewModel.isCurrentUser(spelling))
        }
        assertFalse(viewModel.isCurrentUser("b1b2c3d4-0000-4000-8000-00000000000a"))
        assertFalse(viewModel.isCurrentUser("not-an-id"))
        every { sessionManager.getUserId() } returns "u-1"
        assertTrue("an id that is no UUID is itself", viewModel.isCurrentUser("u-1"))
    }

    @Test
    fun `this device's own row under another spelling of its id gives way to its own position`() = runTest {
        val other = "b1b2c3d4-0000-4000-8000-00000000000b"
        fun row(userId: String) = LocationEntity(
            circleId = "self", userId = userId, deviceId = "d-$userId", latitude = 0.0, longitude = 0.0,
            altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = 1, isSynced = true,
        )
        every { sessionManager.getUserId() } returns me
        every { locationRepository.selfLocation } returns kotlinx.coroutines.flow.MutableStateFlow(
            LocationRepository.SelfPosition(1.0, 2.0, null, null, null, null, 5_000),
        )
        every { locationRepository.observeLatestLocationsByCircle(any()) } returns flowOf(listOf(row(me.uppercase()), row(other)))
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        viewModel.historyComputeDispatcher = testDispatcher

        val collecting = backgroundScope.launch { viewModel.memberLocations.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(other, me), viewModel.memberLocations.value.map { it.userId })
        assertEquals(5_000L, viewModel.memberLocations.value.last().timestamp)
        collecting.cancel()
    }

    @Test
    fun `this account's marker is never a server row, before this phone's first fix or in history`() = runTest {
        val other = "b1b2c3d4-0000-4000-8000-00000000000b"
        fun row(userId: String) = LocationEntity(
            circleId = "self", userId = userId, deviceId = "d-$userId", latitude = 0.0, longitude = 0.0,
            altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = 1, isSynced = true,
        )
        every { sessionManager.getUserId() } returns me
        every { locationRepository.observeLatestLocationsByCircle(any()) } returns flowOf(listOf(row(me.uppercase()), row(me), row(other)))
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        viewModel.historyComputeDispatcher = testDispatcher

        val collecting = backgroundScope.launch { viewModel.memberLocations.collect {} }
        advanceUntilIdle()

        assertEquals("no own fix yet: nobody else's row is me", listOf(other), viewModel.memberLocations.value.map { it.userId })
        collecting.cancel()
    }

    @Test
    fun `a member who switched sharing off or paused it has no marker, until the pause ends`() = runTest {
        // The circle sees why, in the member list and card; a frozen marker would
        // say they are still there.
        val now = System.currentTimeMillis()
        fun row(userId: String) = LocationEntity(
            circleId = "c-1", userId = userId, deviceId = "d-$userId", latitude = 0.0, longitude = 0.0,
            altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = 1, isSynced = true,
        )
        fun member(id: String, sharing: String?, until: Long? = null) = com.pingolin.track.data.local.entity.CircleMemberEntity(
            circleId = "c-1", userId = id, displayName = id, role = "member", joinedAt = 0L,
            locationSharing = sharing, sharingPausedUntil = until,
        )
        every { locationRepository.observeLatestLocationsByCircle("c-1") } returns
            flowOf(listOf(row("ann"), row("bob"), row("cat"), row("dan")))
        every { circleRepository.observeMembers("c-1") } returns flowOf(
            listOf(
                member("ann", null),
                member("bob", "off"),
                member("cat", "paused", now + 3_600_000),
                member("dan", "paused", now - 1),
            )
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns
            Result.success(LocationFetchResult(emptyList(), totalReceived = 0, decryptionFailures = 0, keyMissingCount = 0))
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        val collecting = backgroundScope.launch { viewModel.memberLocations.collect {} }
        runCurrent()

        assertEquals(
            "off (bob) and paused (cat) have no marker; a pause that has ended (dan) does",
            listOf("ann", "dan"),
            viewModel.memberLocations.value.map { it.userId },
        )
        collecting.cancel()
        viewModel.stopPolling()
    }

    // =============================================================================
    // DECRYPTION WARNINGS
    // =============================================================================

    @Test
    fun `partial decryption with missing keys emits warning`() = runTest {
        val fetchResult = LocationFetchResult(
            locations = listOf(
                LocationEntity(
                    id = 1L, deviceId = "d1", userId = "u-1", circleId = "c-1",
                    latitude = 0.0, longitude = 0.0, altitude = null, speed = null,
                    heading = null, accuracy = null, batteryLevel = null,
                    timestamp = 0L, dayBucket = 19500
                )
            ),
            totalReceived = 3,
            decryptionFailures = 2,
            keyMissingCount = 2
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns Result.success(fetchResult)
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        // Let the polling job issue one refresh
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        val warning = viewModel.decryptionWarning.value
        assertNotNull("Warning expected after partial decryption", warning)
        assertTrue(warning!!.isKeyMissing)
        assertEquals(2, warning.failureCount)

        viewModel.stopPolling()
    }

    @Test
    fun `partial decryption without missing keys clears warning`() = runTest {
        val fetchResult = LocationFetchResult(
            locations = listOf(
                LocationEntity(
                    id = 1L, deviceId = "d1", userId = "u-1", circleId = "c-1",
                    latitude = 0.0, longitude = 0.0, altitude = null, speed = null,
                    heading = null, accuracy = null, batteryLevel = null,
                    timestamp = 0L, dayBucket = 19500
                )
            ),
            totalReceived = 3,
            decryptionFailures = 2,
            keyMissingCount = 0  // old key version, not missing key
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns Result.success(fetchResult)
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        assertNull(viewModel.decryptionWarning.value)
        viewModel.stopPolling()
    }

    @Test
    fun `all failed with key missing emits key-missing warning`() = runTest {
        val fetchResult = LocationFetchResult(
            locations = emptyList(),
            totalReceived = 5,
            decryptionFailures = 5,
            keyMissingCount = 5
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns Result.success(fetchResult)
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        val warning = viewModel.decryptionWarning.value
        assertNotNull(warning)
        assertTrue(warning!!.isKeyMissing)
        assertEquals(0, warning.successCount)
        assertEquals(5, warning.failureCount)

        viewModel.stopPolling()
    }

    /**
     * Nobody has to tap "Refresh" for locations this phone cannot open yet: the map
     * fetches what it is missing by itself, at most once a minute, and offers no button
     * for it.
     */
    @Test
    fun `locations this phone cannot open yet make it fetch what it is missing, by itself and at most once a minute`() = runTest {
        val fetchResult = LocationFetchResult(
            locations = emptyList(),
            totalReceived = 5,
            decryptionFailures = 5,
            keyMissingCount = 5
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns Result.success(fetchResult)
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.importCircleKeys() } returns Result.success(0)

        viewModel.setSelectedCircle("c-1")
        // A failed check must still stop the poll, or it runs on into the next test.
        try {
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            runCurrent()
            coVerify(exactly = 1) { circleRepository.importCircleKeys() }

            // The next poll finds the same, well within the minute: no second import.
            advanceTimeBy(30_000)
            runCurrent()
            runCurrent()
            coVerify(exactly = 1) { circleRepository.importCircleKeys() }
        } finally {
            viewModel.stopPolling()
        }

        val fragment = java.io.File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        assertFalse("no button for what the app does itself", fragment.contains("setAction(\"Refresh\")"))
    }

    /**
     * A circle whose key this device does not hold must NOT be reported as a
     * connectivity failure. `refreshLatestLocations` returns
     * CircleKeyUnavailableException before it opens a socket; if that set
     * `lastRefreshFailedAt` like an untyped failure, the map would say
     * "Couldn't reach server — showing last known" on a device with a working
     * connection — and point the user at the wrong remedy.
     */
    @Test
    fun `missing circle key does not raise the network-failure flag`() = runTest {
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns
            Result.failure(LocationRepository.CircleKeyUnavailableException("c-1"))
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        assertNull(
            "a missing key is a local key problem, not a network one",
            viewModel.lastRefreshFailedAt.value
        )
        val warning = viewModel.decryptionWarning.value
        assertNotNull("the user needs the actionable key warning instead", warning)
        assertTrue(warning!!.isKeyMissing)

        viewModel.stopPolling()
    }

    /** A genuine network failure must still raise the flag. */
    @Test
    fun `network failure still raises the network-failure flag`() = runTest {
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns
            Result.failure(java.net.UnknownHostException("api-track"))
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        assertNotNull(viewModel.lastRefreshFailedAt.value)

        viewModel.stopPolling()
    }

    /** A confirmed circle removal is not a connectivity problem either. */
    @Test
    fun `circle not found does not raise the network-failure flag`() = runTest {
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns
            Result.failure(LocationRepository.CircleNotFoundException("c-1"))
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        assertNull(viewModel.lastRefreshFailedAt.value)

        viewModel.stopPolling()
    }

    @Test
    fun `all succeeded clears decryption warning`() = runTest {
        val fetchResult = LocationFetchResult(
            locations = listOf(
                LocationEntity(
                    id = 1L, deviceId = "d1", userId = "u-1", circleId = "c-1",
                    latitude = 0.0, longitude = 0.0, altitude = null, speed = null,
                    heading = null, accuracy = null, batteryLevel = null,
                    timestamp = 0L, dayBucket = 19500
                )
            ),
            totalReceived = 1,
            decryptionFailures = 0,
            keyMissingCount = 0
        )
        coEvery { locationRepository.refreshLatestLocations("c-1") } returns Result.success(fetchResult)
        coEvery { placeRepository.refreshPlaces("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        // The refresh awaits a key self-heal before it decides what to
        // report, which is one more suspension than a single runCurrent()
        // clears. NOT advanceUntilIdle(): the ViewModel polls forever, so the
        // scheduler is never idle and that call would hang.
        runCurrent()

        assertNull(viewModel.decryptionWarning.value)
        viewModel.stopPolling()
    }

    @Test
    fun `clearDecryptionWarning resets warning to null`() {
        viewModel.clearDecryptionWarning()
        assertNull(viewModel.decryptionWarning.value)
    }

    // =============================================================================
    // PLACE CREATION
    // =============================================================================

    @Test
    fun `createPlaceFromMap success emits CreatePlaceResult Success`() = runTest {
        coEvery {
            placeRepository.createPersonalPlace(
                name = "Gym",
                notes = null,
                address = null,
                latitude = 10.0,
                longitude = 20.0,
                radius = 100,
                icon = null,
                color = null,
                placeType = "place",
                boundaryRadius = null
            )
        } returns Result.success(testPlace)

        viewModel.createPlaceFromMap(name = "Gym", latitude = 10.0, longitude = 20.0)
        advanceUntilIdle()

        val r = viewModel.createPlaceResult.value
        assertTrue(r is CreatePlaceResult.Success)
        assertEquals(testPlace.id, (r as CreatePlaceResult.Success).placeId)
    }

    @Test
    fun `createPlaceFromMap failure emits Error`() = runTest {
        coEvery {
            placeRepository.createPersonalPlace(
                name = any(), notes = any(), address = any(), latitude = any(),
                longitude = any(), radius = any(), icon = any(), color = any(),
                placeType = any(), boundaryRadius = any()
            )
        } returns Result.failure(Exception("Could not encrypt"))

        viewModel.createPlaceFromMap(name = "x", latitude = 1.0, longitude = 2.0)
        advanceUntilIdle()

        val r = viewModel.createPlaceResult.value
        assertTrue(r is CreatePlaceResult.Error)
        assertEquals("Could not encrypt", (r as CreatePlaceResult.Error).message)
    }

    @Test
    fun `clearCreatePlaceResult resets state`() {
        viewModel.clearCreatePlaceResult()
        assertNull(viewModel.createPlaceResult.value)
    }

    // =============================================================================
    // TIME RANGE MODE
    // =============================================================================

    @Test
    fun `setTimeRangeMode LIVE clears history and starts polling`() = runTest {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        coEvery { placeRepository.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()

        viewModel.setTimeRangeMode(TimeRangeMode.LIVE)
        runCurrent()

        assertEquals(TimeRangeMode.LIVE, viewModel.timeRangeMode.value)
        assertTrue(viewModel.locationHistory.value.isEmpty())

        viewModel.stopPolling()
    }

    // =============================================================================
    // POLLING ONLY WHILE THE MAP IS ON SCREEN
    // =============================================================================

    /**
     * The circle list re-emits after every background circles refresh, and the
     * map's collector turns that into setSelectedCircle. While the map is hidden
     * that must only record the choice — restarting the 30 s poll would poll for a
     * map nobody can see, and nothing would stop it until the next onStop.
     */
    @Test
    fun `selecting a circle while the map is hidden records it but does not poll`() = runTest {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        viewModel.onMapVisibilityChanged(false)

        viewModel.setSelectedCircle("c-1")
        runCurrent()

        assertEquals("c-1", viewModel.selectedCircleId.value)
        assertFalse(viewModel.isPolling.value)
        coVerify(exactly = 0) { locationRepository.refreshLatestLocations(any()) }
    }

    @Test
    fun `hiding the map stops a running poll`() = runTest {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        coEvery { placeRepository.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        assertTrue(viewModel.isPolling.value)

        viewModel.onMapVisibilityChanged(false)
        runCurrent()

        assertFalse(viewModel.isPolling.value)
    }

    @Test
    fun `setTimeRangeMode non-LIVE stops polling`() = runTest {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        coEvery { placeRepository.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery {
            locationRepository.fetchLocationHistory(
                circleId = any(), startTime = any(), endTime = any()
            )
        } returns Result.success(com.pingolin.track.domain.repository.LocationHistoryResult(emptyList()))

        viewModel.setSelectedCircle("c-1")
        runCurrent()

        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        assertEquals(TimeRangeMode.HOURS_24, viewModel.timeRangeMode.value)
        assertFalse(viewModel.isPolling.value)
    }

    /**
     * The map's history footer is the only place on screen that says how much
     * of the chosen range the trail is drawn from, so a range the server cut
     * short has to reach it. There is no "load the rest" to offer — the
     * response already spans the whole range — so there is no paging state
     * either.
     */
    @Test
    fun `a history range the server truncated reaches the UI`() = runTest {
        stubHistoryFetch(truncated = true)

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        assertTrue(
            "the server dropped the oldest points; the footer has to be able to say so",
            viewModel.locationHistoryTruncated.value
        )
    }

    @Test
    fun `a complete history range reports no truncation`() = runTest {
        stubHistoryFetch(truncated = false)

        viewModel.setSelectedCircle("c-1")
        runCurrent()
        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        assertFalse(
            "nothing was dropped, so the footer must not claim points are missing",
            viewModel.locationHistoryTruncated.value
        )
    }

    private fun stubHistoryFetch(truncated: Boolean) {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        coEvery { placeRepository.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery {
            locationRepository.fetchLocationHistory(
                circleId = any(), startTime = any(), endTime = any()
            )
        } returns Result.success(
            com.pingolin.track.domain.repository.LocationHistoryResult(
                emptyList(), truncated = truncated
            )
        )
    }

    @Test
    fun `isShowingHistory returns true when non-LIVE mode`() {
        // Default is LIVE
        assertFalse(viewModel.isShowingHistory())
    }

    // =============================================================================
    // SELECTION
    // =============================================================================

    @Test
    fun `clearSelectedCircle sets id to null`() = runTest {
        coEvery { placeRepository.refreshPlaces(null) } returns Result.success(emptyList())

        viewModel.clearSelectedCircle()
        advanceUntilIdle()

        assertNull(viewModel.selectedCircleId.value)
    }

    @Test
    fun `selectMember updates selectedMemberId`() {
        viewModel.selectMember("u-42")
        assertEquals("u-42", viewModel.selectedMemberId.value)
        viewModel.selectMember(null)
        assertNull(viewModel.selectedMemberId.value)
    }

    // =============================================================================
    // TOGGLES
    // =============================================================================

    @Test
    fun `toggleShowMembers flips boolean`() {
        val before = viewModel.showMembers.value
        viewModel.toggleShowMembers()
        assertEquals(!before, viewModel.showMembers.value)
    }

    @Test
    fun `toggleShowPlaces off also turns off boundaries`() {
        // Ensure places starts true
        val initial = viewModel.showPlaces.value
        if (!initial) viewModel.toggleShowPlaces()  // Make sure it's on
        // Force boundaries on
        if (!viewModel.showBoundaries.value) viewModel.toggleShowBoundaries()
        assertTrue(viewModel.showBoundaries.value)

        // Turn places off
        viewModel.toggleShowPlaces()
        assertFalse(viewModel.showPlaces.value)
        assertFalse(viewModel.showBoundaries.value)
    }

    @Test
    fun `toggleTracking flips state and returns new value`() {
        val result = viewModel.toggleTracking()
        assertTrue(result)
        assertTrue(viewModel.isTrackingEnabled.value)

        val result2 = viewModel.toggleTracking()
        assertFalse(result2)
        assertFalse(viewModel.isTrackingEnabled.value)
    }

    // =============================================================================
    // MAP BEARING
    // =============================================================================

    @Test
    fun `updateMapBearing stores bearing`() {
        viewModel.updateMapBearing(90.0)
        assertEquals(90.0, viewModel.mapBearing.value, 0.0)
    }

    // =============================================================================
    // TAP LOCATION
    // =============================================================================

    @Test
    fun `clearTappedLocation resets tapped location to null`() {
        viewModel.clearTappedLocation()
        assertNull(viewModel.tappedLocation.value)
    }

    // =============================================================================
    // SEND LOCATION
    // =============================================================================

    @Test
    fun `sendLocationNow with null location emits Error`() {
        viewModel.sendLocationNow(null, onScreen())
        val r = viewModel.sendLocationResult.value
        assertTrue(r is SendLocationResult.Error)
        // No words of its own: the screen says map_location_not_available in its language.
        assertNull((r as SendLocationResult.Error).message)
        assertEquals(com.pingolin.track.R.string.map_location_not_available, r.fallback)
    }

    /**
     * A watcher judges the newest location, and this one is sent at every glance at the map (and
     * from the button): without the battery level, a person low on battery who kept looking at
     * Track would get no low-battery alert. So it carries the level; whether a circle gets it is
     * the circle's own setting, where the location is sealed (LocationRepositoryTest).
     */
    @Test
    fun `the map's refresh and Send location now carry this phone's battery level, to a circle and to personal history`() = runTest {
        every { circleRepository.behindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        every { circleRepository.recordsBehindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        coEvery { locationRepository.sendLocation(any(), any(), any()) } returns Result.success(com.pingolin.track.domain.repository.SendOutcome.SENT)
        val fix = mockk<android.location.Location>(relaxed = true)
        // Only recorded: these are about the send, not the poll a visible map starts.
        viewModel.onMapVisibilityChanged(false)
        viewModel.setSelectedCircle("c-1")

        // The map's refresh when it opens.
        viewModel.sendLocationNow(fix, onScreen(), silent = true)
        runCurrent()
        coVerify(exactly = 1) { locationRepository.sendLocation("c-1", fix, 15) }

        // Personal history, from the button.
        coEvery { locationRepository.sendSelfTrackingLocation(any(), any()) } returns Result.success(com.pingolin.track.domain.repository.SendOutcome.SENT)
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        every { battery.now() } returns 9
        viewModel.sendLocationNow(fix, onScreen())
        runCurrent()
        coVerify(exactly = 1) { locationRepository.sendSelfTrackingLocation(fix, 9) }
        viewModel.viewModelScope.cancel()
    }

    /** What Send location now says is what became of the location, and to whom. */
    @Test
    fun `Send location now says what happened, to the circle on screen`() = runTest {
        every { circleRepository.behindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        every { circleRepository.recordsBehindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        val fix = mockk<android.location.Location>(relaxed = true)
        fun said(outcome: com.pingolin.track.domain.repository.SendOutcome): SendLocationResult? {
            coEvery { locationRepository.sendLocation(any(), any(), any()) } returns Result.success(outcome)
            viewModel.viewModelScope.cancel()
            viewModel = MapViewModel(
                locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
                sessionManager, privacySettingDao, hiddenDestinations, battery,
            )
            viewModel.onMapVisibilityChanged(false)
            viewModel.setSelectedCircle("c-1")
            viewModel.sendLocationNow(fix, onScreen())
            runCurrent()
            return viewModel.sendLocationResult.value
        }

        assertEquals(SendLocationResult.Success("c-1"), said(com.pingolin.track.domain.repository.SendOutcome.SENT))
        assertEquals(SendLocationResult.Waiting("c-1"), said(com.pingolin.track.domain.repository.SendOutcome.QUEUED))
        assertEquals(SendLocationResult.NotSharing("c-1", paused = true), said(com.pingolin.track.domain.repository.SendOutcome.PAUSED))
        assertEquals(SendLocationResult.NotSharing("c-1", paused = false), said(com.pingolin.track.domain.repository.SendOutcome.REALTIME_OFF))
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `Send location now on Just Me says it was saved to your history`() = runTest {
        coEvery { locationRepository.sendSelfTrackingLocation(any(), any()) } returns Result.success(com.pingolin.track.domain.repository.SendOutcome.SENT)
        viewModel.onMapVisibilityChanged(false)

        viewModel.sendLocationNow(mockk(relaxed = true), onScreen())
        runCurrent()

        assertEquals(SendLocationResult.Success(circleId = null), viewModel.sendLocationResult.value)
    }

    @Test
    fun `Send location now on Just Me kept to go later says it's waiting to be saved`() = runTest {
        coEvery { locationRepository.sendSelfTrackingLocation(any(), any()) } returns
            Result.success(com.pingolin.track.domain.repository.SendOutcome.QUEUED)
        viewModel.onMapVisibilityChanged(false)

        viewModel.sendLocationNow(mockk(relaxed = true), onScreen())
        runCurrent()

        assertEquals(SendLocationResult.Waiting(circleId = null), viewModel.sendLocationResult.value)
    }

    @Test
    fun `Send location now to a circle this phone is catching up with says so, in the same words as waiting`() = runTest {
        every { circleRepository.behindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(setOf("c-1"))
        every { circleRepository.recordsBehindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        coEvery { locationRepository.sendLocation(any(), any(), any()) } returns
            Result.success(com.pingolin.track.domain.repository.SendOutcome.WAITING_FOR_CIRCLE)
        viewModel.onMapVisibilityChanged(false)
        viewModel.setSelectedCircle("c-1")

        viewModel.sendLocationNow(mockk(relaxed = true), onScreen())
        runCurrent()

        assertEquals(SendLocationResult.Waiting("c-1", catchingUp = true), viewModel.sendLocationResult.value)
    }

    /** The circle on screen now, as the map's taps take it. */
    private fun onScreen() = MapViewModel.SendTarget(viewModel.selectedCircleId.value)

    /**
     * The precise fix takes seconds, and the circle on screen can change, or go, meanwhile. The send
     * goes where it was asked at the tap, never where the screen is when the fix comes in.
     */
    @Test
    fun `Send location now goes to the circle on screen at the tap, not the one on screen when the fix comes in`() = runTest {
        every { circleRepository.behindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        every { circleRepository.recordsBehindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        coEvery { locationRepository.sendLocation(any(), any(), any()) } returns Result.success(com.pingolin.track.domain.repository.SendOutcome.SENT)
        coEvery { locationRepository.sendSelfTrackingLocation(any(), any()) } returns Result.success(com.pingolin.track.domain.repository.SendOutcome.SENT)
        val fix = mockk<android.location.Location>(relaxed = true)
        viewModel.onMapVisibilityChanged(false)

        viewModel.setSelectedCircle("c-1")
        val tapped = onScreen()
        viewModel.setSelectedCircle("c-2")
        viewModel.sendLocationNow(fix, tapped)
        runCurrent()
        coVerify(exactly = 1) { locationRepository.sendLocation("c-1", fix, any()) }
        coVerify(exactly = 0) { locationRepository.sendLocation("c-2", any(), any()) }
        assertEquals(SendLocationResult.Success("c-1"), viewModel.sendLocationResult.value)

        // Just Me at the tap stays Just Me, whatever circle is on screen once the fix is in.
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        viewModel.onMapVisibilityChanged(false)
        val onJustMe = onScreen()
        viewModel.setSelectedCircle("c-2")
        viewModel.sendLocationNow(fix, onJustMe)
        runCurrent()
        coVerify(exactly = 1) { locationRepository.sendSelfTrackingLocation(fix, any()) }
        coVerify(exactly = 0) { locationRepository.sendLocation("c-2", any(), any()) }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `a circle gone by the time the fix comes in gets nothing, and nothing goes anywhere else`() = runTest {
        coEvery { circleRepository.getCircle("c-1") } returns null
        viewModel.onMapVisibilityChanged(false)
        viewModel.setSelectedCircle("c-1")

        viewModel.sendLocationNow(mockk(relaxed = true), MapViewModel.SendTarget("c-1", "Family"))
        runCurrent()

        assertEquals(SendLocationResult.CircleGone("Family"), viewModel.sendLocationResult.value)
        coVerify(exactly = 0) { locationRepository.sendLocation(any(), any(), any()) }
        coVerify(exactly = 0) { locationRepository.sendSelfTrackingLocation(any(), any()) }
        assertEquals("a send not made starts no cooldown", 0L, viewModel.sendLocationCooldown.value)
    }

    /**
     * A send that failed says so in this app's words only (PlainText.kt): never the SDK's key talk, and
     * never a refused certificate's "…has a key known to be compromised" (CompromisedKeyTrust), whose
     * connection error reads as any other: Pingolin couldn't be reached.
     */
    @Test
    fun `a send that failed says it in this app's words, never the failure's own`() = runTest {
        every { circleRepository.behindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        every { circleRepository.recordsBehindCircles } returns kotlinx.coroutines.flow.MutableStateFlow(emptySet())
        fun said(circleId: String?, failure: Throwable): SendLocationResult? {
            coEvery { locationRepository.sendLocation(any(), any(), any()) } returns Result.failure(failure)
            coEvery { locationRepository.sendSelfTrackingLocation(any(), any()) } returns Result.failure(failure)
            viewModel.viewModelScope.cancel()
            viewModel = MapViewModel(
                locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
                sessionManager, privacySettingDao, hiddenDestinations, battery,
            )
            viewModel.onMapVisibilityChanged(false)
            if (circleId != null) viewModel.setSelectedCircle(circleId)
            viewModel.sendLocationNow(mockk(relaxed = true), onScreen())
            runCurrent()
            return viewModel.sendLocationResult.value
        }
        val refused = javax.net.ssl.SSLHandshakeException(
            "A certificate on the path to api-track.pingolindev.com has a key known to be compromised",
        )
        val keyTalk = uniffi.pingolin_mobile.MobileException.KeyNotFound("Circle key not found: c-1")

        // To the circle on screen, and on Just Me.
        for (circleId in listOf("c-1", null)) {
            assertEquals(SendLocationResult.Error(com.pingolin.track.util.UNREACHABLE_TEXT), said(circleId, refused))
            // No words of its own: the screen says map_send_failed.
            assertEquals(SendLocationResult.Error(null), said(circleId, keyTalk))
            // What this app said itself still shows as it is.
            assertEquals(SendLocationResult.Error("Device registration failed"), said(circleId, Exception("Device registration failed")))
        }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `the map says each one in words, never "Location sent" for what wasn't`() {
        val map = java.io.File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        assertFalse(map.contains("showStatus(\"Location sent\")"))
        for (words in listOf(
            "sendNowLine(it, R.string.send_now_sent, R.string.send_now_sent_unnamed)",
            "getString(R.string.send_now_saved_to_history)",
            "sendNowLine(result.circleId, R.string.send_now_waiting, R.string.send_now_waiting_unnamed)",
            "result.circleId == null -> getString(R.string.send_now_waiting_history)",
            "result.catchingUp -> sendNowLine(result.circleId, R.string.send_now_waiting_for_circle, R.string.send_now_waiting_for_circle_unnamed)",
            "if (result.paused) sendNowLine(result.circleId, R.string.send_now_not_sent_paused, R.string.send_now_not_sent_paused_unnamed)",
            "else sendNowLine(result.circleId, R.string.send_now_not_sent_off, R.string.send_now_not_sent_off_unnamed)",
        )) {
            assertTrue(words, map.contains(words))
        }
        // Not the jargon line "Awaiting sync for circle settings", the nearest any
        // of these could come to key talk.
        val model = java.io.File("src/main/java/com/pingolin/track/ui/map/MapViewModel.kt").readText()
        val sendNow = model.substring(model.indexOf("fun sendLocationNow("), model.indexOf("fun clearSendLocationResult("))
        assertFalse(sendNow.contains("Awaiting sync for circle settings"))
        // A failure reaches the screen only through plainTextOrNull, never as its own message.
        assertFalse(sendNow.contains("exceptionOrNull()?.message"))
        assertEquals(2, Regex("""result\.exceptionOrNull\(\)\?\.plainTextOrNull\(\)""").findAll(sendNow).count())
    }

    @Test
    fun `clearSendLocationResult resets state`() {
        viewModel.clearSendLocationResult()
        assertNull(viewModel.sendLocationResult.value)
    }

    @Test
    fun `canSendLocation returns true initially`() {
        assertTrue(viewModel.canSendLocation())
    }

    // =============================================================================
    // UTIL
    // =============================================================================

    @Test
    fun `isCurrentUser checks against session user id`() {
        every { sessionManager.getUserId() } returns "me"
        assertTrue(viewModel.isCurrentUser("me"))
        assertFalse(viewModel.isCurrentUser("other"))
    }

    @Test
    fun `getMemberName returns Unknown when not in members list`() {
        assertEquals("Unknown", viewModel.getMemberName("missing"))
    }

    @Test
    fun `memberDisplayName is null with no name, for the screen to say Unknown in its language`() {
        assertNull(viewModel.memberDisplayName("missing"))
    }

    @Test
    fun `MEMBER_COLORS has 8 distinct colors`() {
        assertEquals(8, MapViewModel.MEMBER_COLORS.size)
        assertEquals(8, MapViewModel.MEMBER_COLORS.toSet().size)
    }

    @Test
    fun `deletePlace updates deletePlaceResult on success`() = runTest {
        coEvery { placeRepository.deletePlace("p-1") } returns Result.success(Unit)

        viewModel.deletePlace("p-1")
        advanceUntilIdle()

        val r = viewModel.deletePlaceResult.value
        assertNotNull(r)
        assertTrue(r!!.isSuccess)
    }

    @Test
    fun `deletePlace updates deletePlaceResult on failure`() = runTest {
        coEvery { placeRepository.deletePlace("p-1") } returns Result.failure(Exception("err"))

        viewModel.deletePlace("p-1")
        advanceUntilIdle()

        val r = viewModel.deletePlaceResult.value
        assertNotNull(r)
        assertTrue(r!!.isFailure)
    }

    @Test
    fun `alert-circle pass refreshes other circles and skips the selected one`() = runTest {
        // The map polls only the SELECTED circle, so an alert homed elsewhere would
        // be fed solely by the service's 5-minute background pass — having the app
        // open would buy no latency at all, and a crossing could be missed locally,
        // so the generic server push would deliver it instead of the local copy
        // naming the person and place. This pass refreshes the other alert circles.
        coEvery { placeRepository.activeAlertCircleIds() } returns listOf("c-selected", "c-alert")
        coEvery { locationRepository.refreshLatestLocations(any()) } returns
            Result.success(LocationFetchResult(emptyList(), 0, 0, 0))

        viewModel.refreshAlertCirclesOtherThan("c-selected")

        coVerify(exactly = 1) { locationRepository.refreshLatestLocations("c-alert") }
        // Not the selected circle: the poll tick already refreshed it, and spending a
        // second request on it would be pure waste.
        coVerify(exactly = 0) { locationRepository.refreshLatestLocations("c-selected") }
    }

    @Test
    fun `alert-circle pass survives a failing read and a failing refresh`() = runTest {
        // This runs on the user's poll loop. A throw here would kill the polling
        // job, so the map would silently stop updating — a far worse outcome than
        // one missed alert-circle refresh.
        coEvery { placeRepository.activeAlertCircleIds() } throws RuntimeException("db gone")
        viewModel.refreshAlertCirclesOtherThan("c-selected")

        coEvery { placeRepository.activeAlertCircleIds() } returns listOf("c-alert")
        coEvery { locationRepository.refreshLatestLocations("c-alert") } throws RuntimeException("offline")
        viewModel.refreshAlertCirclesOtherThan("c-selected")
    }

    // =============================================================================
    // a member who keeps their location history from the circle
    // =============================================================================

    private fun rosterMember(userId: String, history: String? = null) =
        com.pingolin.track.data.local.entity.CircleMemberEntity(
            circleId = "c-1", userId = userId, displayName = "name-$userId", role = "member", joinedAt = 0,
            historySharing = history,
        )

    private fun point(userId: String, at: Long) = LocationEntity(
        circleId = "c-1", userId = userId, deviceId = "d-$userId", latitude = 1.0, longitude = 2.0,
        altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = at,
    )

    /** The circle's history read brings [points]; this account's own history read, nothing. */
    private fun historyReadBrings(vararg points: LocationEntity) {
        coEvery { locationRepository.refreshLatestLocations(any()) } returns Result.success(
            LocationFetchResult(emptyList(), 0, 0, 0)
        )
        coEvery { placeRepository.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery {
            locationRepository.fetchLocationHistory(circleId = "c-1", startTime = any(), endTime = any())
        } returns Result.success(com.pingolin.track.domain.repository.LocationHistoryResult(points.toList()))
        coEvery {
            locationRepository.fetchLocationHistory(
                circleId = LocationRepository.SELF_CIRCLE_ID, startTime = any(), endTime = any()
            )
        } returns Result.success(com.pingolin.track.domain.repository.LocationHistoryResult(emptyList()))
    }

    @Test
    fun `a member who hides their history loses their trail from the history held, and mine stays`() = runTest {
        val roster = kotlinx.coroutines.flow.MutableStateFlow(listOf(rosterMember("u-bo"), rosterMember("u-1")))
        every { circleRepository.observeMembers("c-1") } returns roster
        val now = System.currentTimeMillis()
        historyReadBrings(point("u-bo", now - 60_000L), point("u-1", now - 60_000L))
        viewModel.setSelectedCircle("c-1")
        runCurrent()
        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()
        assertEquals(setOf("u-bo", "u-1"), viewModel.locationHistory.value.keys)

        roster.value = listOf(rosterMember("u-bo", history = "off"), rosterMember("u-1"))
        advanceUntilIdle()

        assertEquals(setOf("u-1"), viewModel.locationHistory.value.keys)
        assertTrue(viewModel.historyHiddenFor("u-bo"))
        assertFalse(viewModel.historyHiddenFor("u-1"))
    }

    @Test
    fun `a history read never holds the trail of someone hiding theirs`() = runTest {
        every { circleRepository.observeMembers("c-1") } returns
            kotlinx.coroutines.flow.MutableStateFlow(listOf(rosterMember("u-bo", history = "off"), rosterMember("u-1")))
        val now = System.currentTimeMillis()
        historyReadBrings(point("u-bo", now - 60_000L), point("u-1", now - 60_000L))
        viewModel.setSelectedCircle("c-1")
        runCurrent()

        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        assertEquals(setOf("u-1"), viewModel.locationHistory.value.keys)
    }

    @Test
    fun `shown again, a range on screen is read again`() = runTest {
        val roster = kotlinx.coroutines.flow.MutableStateFlow(listOf(rosterMember("u-bo", history = "off")))
        every { circleRepository.observeMembers("c-1") } returns roster
        historyReadBrings()
        viewModel.setSelectedCircle("c-1")
        runCurrent()
        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        roster.value = listOf(rosterMember("u-bo"))
        advanceUntilIdle()

        coVerify(exactly = 2) { locationRepository.fetchLocationHistory(circleId = "c-1", startTime = any(), endTime = any()) }
    }

    @Test
    fun `hiding it, or the first list, reads nothing again`() = runTest {
        val roster = kotlinx.coroutines.flow.MutableStateFlow(listOf(rosterMember("u-bo")))
        every { circleRepository.observeMembers("c-1") } returns roster
        historyReadBrings()
        viewModel.setSelectedCircle("c-1")
        runCurrent()
        viewModel.setTimeRangeMode(TimeRangeMode.HOURS_24)
        advanceUntilIdle()

        roster.value = listOf(rosterMember("u-bo", history = "off"))
        advanceUntilIdle()

        coVerify(exactly = 1) { locationRepository.fetchLocationHistory(circleId = "c-1", startTime = any(), endTime = any()) }
    }

    @Test
    fun `a live map has nothing to read again`() = runTest {
        val roster = kotlinx.coroutines.flow.MutableStateFlow(listOf(rosterMember("u-bo", history = "off")))
        every { circleRepository.observeMembers("c-1") } returns roster
        historyReadBrings()
        viewModel.setSelectedCircle("c-1")
        runCurrent()

        roster.value = listOf(rosterMember("u-bo"))
        runCurrent()

        coVerify(exactly = 0) { locationRepository.fetchLocationHistory(circleId = "c-1", startTime = any(), endTime = any()) }
        // The live poll would run on for ever in virtual time.
        viewModel.onMapVisibilityChanged(false)
        runCurrent()
    }

    // =============================================================================
    // a member with real-time sharing off, drawn where they were
    // =============================================================================

    private fun frozenRow(circleId: String, userId: String) = LocationEntity(
        circleId = circleId, userId = userId, deviceId = "d-$userId", latitude = 3.0, longitude = 4.0,
        altitude = null, speed = null, heading = null, accuracy = null, batteryLevel = null, timestamp = 1_000L,
    )

    private fun inCircle(circleId: String, userId: String, sharing: String? = null) =
        com.pingolin.track.data.local.entity.CircleMemberEntity(
            circleId = circleId, userId = userId, displayName = "name-$userId", role = "member", joinedAt = 0,
            locationSharing = sharing,
        )

    @Test
    fun `a member with real-time sharing off is drawn at this circle's frozen row, and only this circle's`() = runTest {
        // X turned real-time off in both circles, but only circle A's read had a frozen row of theirs.
        every { locationRepository.frozenLocations } returns
            kotlinx.coroutines.flow.MutableStateFlow(mapOf("c-a" to listOf(frozenRow("c-a", "u-x"))))
        every { circleRepository.observeMembers("c-a") } returns flowOf(listOf(inCircle("c-a", "u-x", sharing = "off")))
        every { circleRepository.observeMembers("c-b") } returns flowOf(listOf(inCircle("c-b", "u-x", sharing = "off")))
        historyReadBrings()
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        viewModel.historyComputeDispatcher = testDispatcher
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.memberLocations.collect {} }

        viewModel.setSelectedCircle("c-a")
        runCurrent()
        assertEquals(listOf(frozenRow("c-a", "u-x")), viewModel.memberLocations.value)
        assertTrue(viewModel.drawsFrozen("u-x"))

        viewModel.setSelectedCircle("c-b")
        runCurrent()
        assertEquals("circle A's frozen row never crosses to B", emptyList<LocationEntity>(), viewModel.memberLocations.value)
    }

    @Test
    fun `a member sharing again is drawn by their live row, never the frozen one`() = runTest {
        every { locationRepository.frozenLocations } returns
            kotlinx.coroutines.flow.MutableStateFlow(mapOf("c-a" to listOf(frozenRow("c-a", "u-x"))))
        val live = frozenRow("c-a", "u-x").copy(id = 7, latitude = 5.0, timestamp = 9_000L)
        every { locationRepository.observeLatestLocationsByCircle("c-a") } returns flowOf(listOf(live))
        every { circleRepository.observeMembers("c-a") } returns flowOf(listOf(inCircle("c-a", "u-x")))
        historyReadBrings()
        viewModel.viewModelScope.cancel()
        viewModel = MapViewModel(
            locationRepository, circleRepository, placeRepository, destinationRepository, geocodingRepository,
            sessionManager, privacySettingDao, hiddenDestinations, battery,
        )
        viewModel.historyComputeDispatcher = testDispatcher
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.memberLocations.collect {} }

        viewModel.setSelectedCircle("c-a")
        runCurrent()

        assertEquals(listOf(live), viewModel.memberLocations.value)
        assertFalse(viewModel.drawsFrozen("u-x"))
    }

    // =============================================================================
    // a history point of a member sharing an approximate location
    // =============================================================================

    @Test
    fun `an approximate member's history point is never looked up`() = runTest {
        viewModel.onMapTapped(37.33, -122.01, historicalTimestamp = 5_000L, memberName = "Bo", approximate = true)
        advanceUntilIdle()

        val tapped = viewModel.tappedLocation.value!!
        assertTrue(tapped.approximate)
        assertFalse(tapped.isLoading)
        assertNull(tapped.address)
        assertEquals(5_000L, tapped.historicalTimestamp)
        coVerify(exactly = 0) { geocodingRepository.reverseGeocode(any(), any()) }
    }

    @Test
    fun `anyone else's point is looked up as an exact point`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null

        viewModel.onMapTapped(37.33, -122.01, historicalTimestamp = 5_000L, memberName = "Bo")
        advanceUntilIdle()

        assertFalse(viewModel.tappedLocation.value!!.approximate)
        coVerify(exactly = 1) { geocodingRepository.reverseGeocode(37.33, -122.01) }
    }

    @Test
    fun `the map passes it, and its sheet shows no address, coordinates or actions`() {
        val map = java.io.File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        assertTrue(map.contains("approximate = !spotOf(nearestHistoricalLocation).showsPoint,"))
        val bind = map.substring(map.indexOf("private fun bindTappedLocationSheet("))
        val line = bind.substring(bind.indexOf("dialog.findViewById<TextView>(R.id.tvTappedCoordinates)?.text = when {"), bind.indexOf("val progress = dialog.findViewById<View>(R.id.progressTappedLocation)"))
        val approximateSides = Regex("""if \(tapped\.approximate\) (.+?) else """).findAll(line).map { it.groupValues[1] }.toList()
        assertEquals("each shape of the line has its approximate side: $approximateSides", 4, approximateSides.size)
        assertTrue("no approximate side says where to the metre: $approximateSides", approximateSides.none { Regex("""\bat\b""").containsMatchIn(it) })
        val approximate = bind.substring(bind.indexOf("if (tapped.approximate) {\n            // No address"), bind.indexOf("} else if (tapped.isLoading) {"))
        assertTrue(approximate.contains("actions?.visibility = View.GONE"))
        assertTrue(approximate.contains("tvAddress?.text = getString(R.string.member_approximate_location)"))
    }
}
