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

package com.pingolin.track.ui.destinations

import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.remote.dto.ReportArrivalResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.AlertHandler
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DestinationsViewModel — focused on the manual-arrival
 * path: it must post the same local "You've arrived!" notification as
 * the auto-arrival path (LocationTrackingService), with the label
 * resolved BEFORE the report deletes the local row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestinationsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var destinationRepository: DestinationRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var locationRepository: LocationRepository

    @MockK
    private lateinit var alertHandler: AlertHandler

    @MockK
    private lateinit var sessionManager: com.pingolin.track.util.SessionManager

    @MockK
    private lateinit var hiddenDestinations: com.pingolin.track.util.HiddenDestinationsStore

    @MockK
    private lateinit var circleMemberDao: com.pingolin.track.data.local.dao.CircleMemberDao

    private lateinit var viewModel: DestinationsViewModel

    private val destination = DestinationEntity(
        id = "dest-1",
        userId = "u-1",
        circleId = "c-1",
        destName = "725 11th Street, Modesto, CA",
        destLatitude = 37.6391,
        destLongitude = -120.99688,
        etaDayBucket = null,
        notifyArrival = true,
        status = "active",
        startedDayBucket = 20600,
        arrivedDayBucket = null,
        createdDayBucket = 20600,
        updatedDayBucket = 20600
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { destinationRepository.observeAll() } returns flowOf(emptyList())
        every { destinationRepository.observeActive() } returns flowOf(emptyList())
        every { destinationRepository.observeMine() } returns flowOf(emptyList())
        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        coEvery { destinationRepository.refreshDestinations() } returns Result.success(emptyList())

        // The list carries every member's destinations and resolves
        // ownership + hidden state, so the VM needs the identity, the hide store
        // and the member lookup. A real store would touch SharedPreferences.
        every { sessionManager.getUserId() } returns "me"
        every { hiddenDestinations.hidden } returns MutableStateFlow(emptySet())
        coEvery { circleMemberDao.get(any(), any()) } returns null
        every { circleMemberDao.observeAll() } returns flowOf(emptyList())

        viewModel = DestinationsViewModel(
            destinationRepository, circleRepository, locationRepository, alertHandler,
            sessionManager, hiddenDestinations, circleMemberDao, com.pingolin.track.testutil.quietPendingChanges(),
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `manual arrival posts local self-arrival notification with resolved label`() = runTest {
        coEvery { destinationRepository.labelForDestination(destination) } returns "Home"
        coEvery { locationRepository.getCurrentLocation() } returns null
        // Fifth argument is occurredAtMs, which the manual path leaves at its
        // "now" default — any(), because the value is a clock read.
        coEvery {
            destinationRepository.reportArrival("dest-1", "c-1", null, null, any(), true, null)
        } returns Result.success(
            ReportArrivalResponse(destinationId = "dest-1", eventId = "ev-1", notificationsSent = 1)
        )
        every { alertHandler.showSelfArrival("dest-1", "Home") } just Runs

        viewModel.reportArrival(destination)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is DestinationsUiState.ArrivalReported)
        verify(exactly = 1) { alertHandler.showSelfArrival("dest-1", "Home") }
        // Label must be resolved before the report (the report deletes the
        // local row, after which labelForDestination has nothing to read).
        coVerifyOrder {
            destinationRepository.labelForDestination(destination)
            destinationRepository.reportArrival("dest-1", "c-1", null, null, any(), true, null)
        }
    }

    @Test
    fun `failed arrival report posts no self-arrival notification`() = runTest {
        coEvery { destinationRepository.labelForDestination(destination) } returns "Home"
        coEvery { locationRepository.getCurrentLocation() } returns null
        coEvery {
            destinationRepository.reportArrival(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("network down"))

        viewModel.reportArrival(destination)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is DestinationsUiState.Error)
        verify(exactly = 0) { alertHandler.showSelfArrival(any(), any()) }
    }
}
