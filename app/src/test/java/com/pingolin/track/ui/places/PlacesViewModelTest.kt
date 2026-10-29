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

package com.pingolin.track.ui.places

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.PlaceRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for PlacesViewModel.
 *
 * Tests cover:
 * - createPlace (validation + repo forward)
 * - Empty name rejection
 * - Alert deletion
 * - hasAlertsEnabled
 * - sharePlaceToCircles success + failure
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlacesViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var placeRepository: PlaceRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var geocodingRepository: GeocodingRepository

    @MockK
    private lateinit var sessionManager: com.pingolin.track.util.SessionManager

    private lateinit var viewModel: PlacesViewModel

    /** Changes waiting to sync: nothing waits, and what the screen asks of it is seen. */
    private val pendingChanges = com.pingolin.track.testutil.quietPendingChanges()

    private val testPlace = PlaceEntity(
        id = "place-1",
        circleId = "self",
        name = "Home",
        address = "123 Main St",
        latitude = 37.0,
        longitude = -122.0,
        radius = 100,
        icon = null,
        color = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        every { placeRepository.observePersonalPlaces() } returns flowOf(emptyList())
        every { placeRepository.observePlaces() } returns flowOf(emptyList())
        every { placeRepository.observeAlertsByPlace(any()) } returns flowOf(emptyList())
        coEvery { placeRepository.refreshPlaceAlerts() } returns Result.success(emptyList())

        viewModel = PlacesViewModel(placeRepository, circleRepository, geocodingRepository, sessionManager, pendingChanges)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // =============================================================================
    // CREATE PLACE
    // =============================================================================

    @Test
    fun `createPlace with blank name shows Error`() = runTest {
        viewModel.createPlace(name = "", latitude = 1.0, longitude = 2.0, radius = 100)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.Error)
        // No words of its own: the screen says place_name_required in its language.
        assertNull((state as PlacesUiState.Error).message)
        assertEquals(com.pingolin.track.R.string.place_name_required, state.fallback)
    }

    @Test
    fun `createPlace with whitespace name shows Error`() = runTest {
        viewModel.createPlace(name = "   ", latitude = 1.0, longitude = 2.0, radius = 100)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PlacesUiState.Error)
    }

    @Test
    fun `successful createPlace transitions to PlaceCreated`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null
        coEvery {
            placeRepository.createPersonalPlace(
                name = "Home",
                notes = null,
                address = null,
                latitude = 37.0,
                longitude = -122.0,
                radius = 100,
                icon = null,
                color = null
            )
        } returns Result.success(testPlace)

        viewModel.createPlace(name = "Home", latitude = 37.0, longitude = -122.0, radius = 100)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.PlaceCreated)
        assertEquals(testPlace, (state as PlacesUiState.PlaceCreated).place)
    }

    @Test
    fun `createPlace failure transitions to Error`() = runTest {
        coEvery { geocodingRepository.reverseGeocode(any(), any()) } returns null
        coEvery {
            placeRepository.createPersonalPlace(
                name = any(), notes = any(), address = any(), latitude = any(),
                longitude = any(), radius = any(), icon = any(), color = any()
            )
        } returns Result.failure(Exception("Key not available"))

        viewModel.createPlace(name = "Home", latitude = 1.0, longitude = 2.0, radius = 100)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.Error)
        assertEquals("Key not available", (state as PlacesUiState.Error).message)
    }

    // =============================================================================
    // SELECTION
    // =============================================================================

    @Test
    fun `selectPlace updates selectedPlace`() {
        viewModel.selectPlace(testPlace)
        assertEquals(testPlace, viewModel.selectedPlace.value)

        viewModel.selectPlace(null)
        assertNull(viewModel.selectedPlace.value)
    }

    // =============================================================================
    // DELETE
    // =============================================================================

    @Test
    fun `deletePlace success transitions to PlaceDeleted and clears selection`() = runTest {
        coEvery { placeRepository.deletePlace("place-1") } returns Result.success(Unit)
        coEvery { placeRepository.refreshPlaces(null) } returns Result.success(emptyList())

        viewModel.selectPlace(testPlace)
        viewModel.deletePlace("place-1")
        advanceUntilIdle()

        assertEquals(PlacesUiState.PlaceDeleted, viewModel.uiState.value)
        assertNull(viewModel.selectedPlace.value)
    }

    @Test
    fun `deletePlace failure transitions to Error`() = runTest {
        coEvery { placeRepository.deletePlace("place-1") } returns Result.failure(Exception("Err"))

        viewModel.deletePlace("place-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PlacesUiState.Error)
    }

    // =============================================================================
    // ALERTS
    // =============================================================================

    @Test
    fun `deleteAlert success transitions to AlertDeleted`() = runTest {
        coEvery { placeRepository.deletePlaceAlert("a-1") } returns Result.success(Unit)

        viewModel.deleteAlert("a-1")
        advanceUntilIdle()

        assertEquals(PlacesUiState.AlertDeleted, viewModel.uiState.value)
    }

    @Test
    fun `deleteAlert failure transitions to Error`() = runTest {
        coEvery { placeRepository.deletePlaceAlert(any()) } returns Result.failure(Exception("fail"))

        viewModel.deleteAlert("a-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is PlacesUiState.Error)
    }

    @Test
    fun `hasAlertsEnabled returns false when no alerts`() {
        assertFalse(viewModel.hasAlertsEnabled("place-xxx"))
    }

    // =============================================================================
    // SHARE
    // =============================================================================

    @Test
    fun `sharePlaceToCircles empty set shows Error`() = runTest {
        viewModel.sharePlaceToCircles(testPlace, emptySet())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.Error)
        assertNull((state as PlacesUiState.Error).message)
        assertEquals(com.pingolin.track.R.string.places_share_none_selected, state.fallback)
    }

    @Test
    fun `sharePlaceToCircles all success counts correctly`() = runTest {
        coEvery { placeRepository.copyPlaceToCircle(testPlace, "c1", true, null) } returns Result.success(testPlace)
        coEvery { placeRepository.copyPlaceToCircle(testPlace, "c2", true, null) } returns Result.success(testPlace)

        viewModel.sharePlaceToCircles(testPlace, setOf("c1", "c2"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.PlaceShared)
        assertEquals(2, (state as PlacesUiState.PlaceShared).successCount)
        assertEquals(2, state.totalCount)
    }

    @Test
    fun `sharePlaceToCircles partial success still reports PlaceShared`() = runTest {
        coEvery { placeRepository.copyPlaceToCircle(testPlace, "c1", true, null) } returns Result.success(testPlace)
        coEvery { placeRepository.copyPlaceToCircle(testPlace, "c2", true, null) } returns Result.failure(Exception("x"))

        viewModel.sharePlaceToCircles(testPlace, setOf("c1", "c2"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.PlaceShared)
        assertEquals(1, (state as PlacesUiState.PlaceShared).successCount)
        assertEquals(2, state.totalCount)
    }

    @Test
    fun `sharePlaceToCircles all failures shows Error`() = runTest {
        coEvery { placeRepository.copyPlaceToCircle(testPlace, any(), any(), any()) } returns Result.failure(Exception("x"))

        viewModel.sharePlaceToCircles(testPlace, setOf("c1"))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is PlacesUiState.Error)
    }

    // =============================================================================
    // PLACE ALERTS WHILE THIS PHONE IS BEHIND
    // =============================================================================

    private fun alert(id: String, target: String) = com.pingolin.track.data.local.entity.PlaceAlertEntity(
        id = id, placeId = "place-1", circleId = "c-1", userId = "me", targetUserId = target, dayBucket = 0,
    )

    /** The place's alerts as the screen has them, [existing]. */
    private fun TestScope.alertsAre(vararg existing: com.pingolin.track.data.local.entity.PlaceAlertEntity) {
        coEvery { placeRepository.refreshPlaces(null) } returns Result.success(emptyList())
        coEvery { placeRepository.refreshPlaceAlerts() } returns Result.success(existing.toList())
        viewModel.refreshPlaces()
        advanceUntilIdle()
    }

    private fun save(vararg targets: String) = viewModel.savePlaceAlerts(
        "place-1", "c-1", targets.toList(), "arrival", null, null, null, isRecurring = true, expiresAt = null,
        isActive = true, notifyPush = true, notifyEmail = false, notifySms = false,
    )

    private fun created(target: String, result: Result<String>, keepIfBehind: Boolean? = null) = coEvery {
        placeRepository.createPlaceAlert(
            any(), "place-1", target, any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            if (keepIfBehind == null) any() else keepIfBehind, any(),
        )
    } returns result

    @Test
    fun `replacing a place's alerts while behind keeps the current ones until the replacement is made`() = runTest {
        alertsAre(alert("a-sam", "sam"))
        // Behind: asked to keep it, the create would be kept; asked not to, it is refused.
        created(
            "kim",
            Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", com.pingolin.track.domain.pending.PendingLines.NEW_ALERT)),
            keepIfBehind = true,
        )
        created("kim", Result.failure(com.pingolin.track.domain.repository.KeyNotCurrentException()), keepIfBehind = false)
        coEvery { placeRepository.deletePlaceAlert(any()) } returns Result.success(Unit)

        val result = save("kim")
        advanceUntilIdle()

        assertTrue("refused whole", result.await().isFailure)
        coVerify(exactly = 0) { placeRepository.deletePlaceAlert(any()) }
        coVerify(exactly = 0) {
            placeRepository.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), true, any())
        }
        assertTrue(viewModel.uiState.value is PlacesUiState.Error)
    }

    @Test
    fun `behind, two per-person alerts switched to the whole circle both stay`() = runTest {
        alertsAre(alert("a-sam", "sam"), alert("a-kim", "kim"))
        // Behind: asked to keep it, the circle-wide alert would be kept; asked not to, refused.
        coEvery {
            placeRepository.createCircleWidePlaceAlert(any(), "place-1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), true, any())
        } returns Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", com.pingolin.track.domain.pending.PendingLines.NEW_ALERT))
        coEvery {
            placeRepository.createCircleWidePlaceAlert(any(), "place-1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false, any())
        } returns Result.failure(com.pingolin.track.domain.repository.KeyNotCurrentException())
        coEvery { placeRepository.deletePlaceAlert(any()) } returns Result.success(Unit)

        // No member picked: one alert for the whole circle.
        val result = save()
        advanceUntilIdle()

        assertTrue(result.await().isFailure)
        coVerify(exactly = 1) {
            placeRepository.createCircleWidePlaceAlert(any(), "place-1", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), false, any())
        }
        coVerify(exactly = 0) { placeRepository.deletePlaceAlert(any()) }
        coVerify(exactly = 0) { pendingChanges.discard(any()) }
    }

    @Test
    fun `a new alert on a place with none is kept while behind, and nothing else is touched`() = runTest {
        alertsAre()
        val line = com.pingolin.track.domain.pending.PendingLines.NEW_ALERT
        created("kim", Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", line)), keepIfBehind = true)

        val result = save("kim")
        advanceUntilIdle()

        assertTrue(result.await().isSuccess)
        assertEquals(PlacesUiState.Kept(line), viewModel.uiState.value)
        coVerify(exactly = 0) { placeRepository.deletePlaceAlert(any()) }
        coVerify(exactly = 0) { pendingChanges.discard(any()) }
    }

    @Test
    fun `a save that fails after keeping a create drops the kept one`() = runTest {
        alertsAre()
        val line = com.pingolin.track.domain.pending.PendingLines.NEW_ALERT
        created("kim", Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", line)))
        created("sam", Result.failure(Exception("Couldn't save the alert just now.")))

        val result = save("kim", "sam")
        advanceUntilIdle()

        assertTrue(result.await().isFailure)
        coVerify(exactly = 1) { pendingChanges.discard("ch-1") }
        assertTrue(viewModel.uiState.value is PlacesUiState.Error)
    }

    @Test
    fun `a failed save drops only the creates it kept itself, never one it went into`() = runTest {
        alertsAre()
        val line = com.pingolin.track.domain.pending.PendingLines.NEW_ALERT
        // The same tap twice: the create went into the change an earlier save kept.
        created("kim", Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-earlier", line, created = false)))
        created("sam", Result.failure(Exception("Couldn't save the alert just now.")))

        val result = save("kim", "sam")
        advanceUntilIdle()

        assertTrue(result.await().isFailure)
        coVerify(exactly = 0) { pendingChanges.discard(any()) }
    }

    // =============================================================================
    // STATE
    // =============================================================================

    @Test
    fun `resetState returns to Idle`() = runTest {
        viewModel.createPlace(name = "", latitude = 0.0, longitude = 0.0, radius = 1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is PlacesUiState.Error)

        viewModel.resetState()
        assertEquals(PlacesUiState.Idle, viewModel.uiState.value)
    }
}
