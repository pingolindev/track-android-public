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

package com.pingolin.track.ui.checkin

import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
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
 * Unit tests for CheckInViewModel.
 *
 * Tests cover:
 * - quickCheckIn / checkInWithLocation / checkInAtPlace / checkInWithMessage
 * - Error state when repository returns failure
 * - Location unavailable path
 * - Missing selected circle no-ops
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var checkInRepository: CheckInRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var placeRepository: PlaceRepository

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var sessionManager: SessionManager

    private lateinit var viewModel: CheckInViewModel

    private val testCheckIn = CheckInEntity(
        id = "ci-1",
        circleId = "c-1",
        userId = "u-1",
        type = "safe_arrival",
        message = null,
        latitude = null,
        longitude = null,
        placeId = null,
        timestamp = 0L
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        every { checkInRepository.observeCheckInsByCircle(any()) } returns flowOf(emptyList())
        every { placeRepository.observePlacesByCircle(any()) } returns flowOf(emptyList())
        every { circleRepository.observeMembers(any()) } returns flowOf(emptyList())

        viewModel = CheckInViewModel(
            checkInRepository, circleRepository, placeRepository, locationRepository, sessionManager,
            com.pingolin.track.testutil.quietPendingChanges(), androidx.lifecycle.SavedStateHandle(),
        )
    }

    private fun circle(id: String, name: String) =
        com.pingolin.track.data.local.entity.CircleEntity(id = id, name = name, encryptedKey = null, createdAt = 0, updatedAt = 0)

    /** The screen over [circles] as the store has them, opened for [openedFor] (the map's check-in menu). */
    private fun screenOver(
        circles: kotlinx.coroutines.flow.MutableStateFlow<List<com.pingolin.track.data.local.entity.CircleEntity>>,
        openedFor: String? = null,
    ): CheckInViewModel {
        every { circleRepository.observeCircles() } returns circles
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        return CheckInViewModel(
            checkInRepository, circleRepository, placeRepository, locationRepository, sessionManager,
            com.pingolin.track.testutil.quietPendingChanges(),
            androidx.lifecycle.SavedStateHandle(openedFor?.let { mapOf(CheckInViewModel.ARG_CIRCLE_ID to it) } ?: emptyMap()),
        )
    }

    @Test
    fun `it opens on the circle it was opened for, else on the only one, and with several on none`() = runTest {
        val two = kotlinx.coroutines.flow.MutableStateFlow(listOf(circle("c-1", "One"), circle("c-2", "Two")))
        val openedOnTwo = screenOver(two, openedFor = "c-2")
        advanceUntilIdle()
        assertEquals("the map's circle, not the first", "c-2", openedOnTwo.selectedCircleId.value)

        val severalNoneNamed = screenOver(two)
        advanceUntilIdle()
        assertNull("several circles, none named: a pick first", severalNoneNamed.selectedCircleId.value)
        assertNull("nothing was lost", severalNoneNamed.goneCircleName.value)

        val onlyOne = screenOver(kotlinx.coroutines.flow.MutableStateFlow(listOf(circle("c-1", "One"))))
        advanceUntilIdle()
        assertEquals("c-1", onlyOne.selectedCircleId.value)
    }

    @Test
    fun `a list change keeps the circle chosen, and a chosen circle that goes takes the choice with it`() = runTest {
        val circles = kotlinx.coroutines.flow.MutableStateFlow(listOf(circle("c-1", "One"), circle("c-2", "Two")))
        val screen = screenOver(circles)
        advanceUntilIdle()
        screen.setSelectedCircle("c-2")
        advanceUntilIdle()

        // Another circle comes first now: the choice stays, rather than falling
        // to the first.
        circles.value = listOf(circle("c-0", "Zero"), circle("c-1", "One"), circle("c-2", "Two"))
        advanceUntilIdle()
        assertEquals("c-2", screen.selectedCircleId.value)

        // The chosen circle goes: no choice, never the first circle, and it says which went.
        circles.value = listOf(circle("c-0", "Zero"), circle("c-1", "One"))
        advanceUntilIdle()
        assertNull(screen.selectedCircleId.value)
        assertEquals("Two", screen.goneCircleName.value)

        // Nothing is sent until a pick.
        screen.quickCheckIn("safe_arrival")
        advanceUntilIdle()
        coVerify(exactly = 0) { checkInRepository.createQuickCheckIn(any(), any(), any()) }

        // A pick, and the line goes.
        coEvery { checkInRepository.createQuickCheckIn("c-1", "safe_arrival", true) } returns Result.success(testCheckIn)
        screen.setSelectedCircle("c-1")
        screen.quickCheckIn("safe_arrival")
        advanceUntilIdle()
        assertNull(screen.goneCircleName.value)
        coVerify(exactly = 1) { checkInRepository.createQuickCheckIn("c-1", "safe_arrival", true) }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `setSelectedCircle stores id and triggers refresh`() = runTest {
        coEvery { checkInRepository.refreshCheckIns("c-1") } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        assertEquals("c-1", viewModel.selectedCircleId.value)
        coVerify { checkInRepository.refreshCheckIns("c-1") }
        coVerify { circleRepository.refreshMembers("c-1") }
    }

    @Test
    fun `quickCheckIn success transitions to Sent`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { checkInRepository.createQuickCheckIn("c-1", "safe_arrival", true) } returns Result.success(testCheckIn)

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.quickCheckIn("safe_arrival")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CheckInUiState.Sent)
        assertEquals(testCheckIn, (state as CheckInUiState.Sent).checkIn)
    }

    @Test
    fun `quickCheckIn without selected circle is a no-op`() = runTest {
        viewModel.quickCheckIn("safe_arrival")
        advanceUntilIdle()

        coVerify(exactly = 0) { checkInRepository.createQuickCheckIn(any(), any(), any()) }
        assertEquals(CheckInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `quickCheckIn failure transitions to Error`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { checkInRepository.createQuickCheckIn(any(), any(), any()) } returns Result.failure(Exception("api fail"))

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.quickCheckIn("safe_arrival")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CheckInUiState.Error)
        assertEquals("api fail", (state as CheckInUiState.Error).message)
    }

    @Test
    fun `checkInWithLocation emits Error when getCurrentLocation returns null`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { locationRepository.getCurrentLocation() } returns null

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.checkInWithLocation("safe_arrival")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CheckInUiState.Error)
        assertEquals("Could not get current location", (state as CheckInUiState.Error).message)
    }

    @Test
    fun `checkInWithLocation success passes coordinates to repository`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        val location = mockk<Location>(relaxed = true) {
            every { latitude } returns 1.5
            every { longitude } returns 2.5
        }
        coEvery { locationRepository.getCurrentLocation() } returns location
        coEvery { checkInRepository.createCheckInWithLocation("c-1", "safe_arrival", 1.5, 2.5, true) } returns Result.success(testCheckIn)

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.checkInWithLocation("safe_arrival")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CheckInUiState.Sent)
        coVerify { checkInRepository.createCheckInWithLocation("c-1", "safe_arrival", 1.5, 2.5, true) }
    }

    @Test
    fun `checkInAtPlace success transitions to Sent`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { checkInRepository.createCheckInAtPlace("c-1", "p-1", null, true) } returns Result.success(testCheckIn)

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.checkInAtPlace("p-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CheckInUiState.Sent)
    }

    @Test
    fun `checkInAtPlace failure transitions to Error`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { checkInRepository.createCheckInAtPlace(any(), any(), any(), any()) } returns Result.failure(Exception("nope"))

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.checkInAtPlace("p-1", "hey")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CheckInUiState.Error)
    }

    @Test
    fun `checkInWithMessage uses null coordinates when location missing`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { locationRepository.getCurrentLocation() } returns null
        coEvery {
            checkInRepository.createCheckIn(
                circleId = "c-1", type = "general", message = "hi",
                latitude = null, longitude = null, keepIfBehind = true,
            )
        } returns Result.success(testCheckIn)

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()

        viewModel.checkInWithMessage("general", "hi")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CheckInUiState.Sent)
    }

    @Test
    fun `resetState returns to Idle`() = runTest {
        coEvery { checkInRepository.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { circleRepository.refreshMembers(any()) } returns Result.success(emptyList())
        coEvery { checkInRepository.createQuickCheckIn(any(), any(), any()) } returns Result.failure(Exception("x"))

        viewModel.setSelectedCircle("c-1")
        advanceUntilIdle()
        viewModel.quickCheckIn("safe_arrival")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is CheckInUiState.Error)

        viewModel.resetState()
        assertEquals(CheckInUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `getMemberName returns session display name for current user when members empty`() {
        every { sessionManager.getUserId() } returns "u-1"
        every { sessionManager.getDisplayName() } returns "Alice"

        assertEquals("Alice", viewModel.getMemberName("u-1"))
    }

    @Test
    fun `getPlaceName returns null when place missing`() {
        assertNull(viewModel.getPlaceName("p-x"))
    }
}
