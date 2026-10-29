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
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.AlertHandler
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * On the destinations list, a row shows the owner-only buttons (Arrive, Cancel, Delete) only
 * when the sealed id AND the server's creator both name this account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestinationRowOwnershipTest {

    private val dispatcher = StandardTestDispatcher()
    private val me = "b1000000-0000-4000-8000-0000000000e1"
    private val other = "b1000000-0000-4000-8000-0000000000e2"

    private fun row(id: String, sealed: String, creator: String?) = DestinationEntity(
        id = id, userId = sealed, circleId = "b1000000-0000-4000-8000-0000000000c1", destName = null,
        destLatitude = 0.0, destLongitude = 0.0, etaDayBucket = null,
        startedDayBucket = 0, createdDayBucket = 0, updatedDayBucket = 0, creatorId = creator,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `only this account's own row is its to act on`() = runTest(dispatcher) {
        val rows = listOf(
            row("mine", sealed = me, creator = me.uppercase()),
            row("sealed-as-me", sealed = me, creator = other),
            row("server-says-me", sealed = other, creator = me),
            row("not-known-yet", sealed = me, creator = null),
        )
        val repository = mockk<DestinationRepository>(relaxed = true) {
            every { observeAll() } returns flowOf(rows)
            every { observeActive() } returns flowOf(emptyList())
            coEvery { placeNameForDestination(any()) } returns null
            coEvery { refreshDestinations() } returns Result.success(emptyList())
        }
        val viewModel = DestinationsViewModel(
            repository,
            mockk<CircleRepository>(relaxed = true) { every { observeCircles() } returns flowOf(emptyList()) },
            mockk<LocationRepository>(relaxed = true),
            mockk<AlertHandler>(relaxed = true),
            mockk<com.pingolin.track.util.SessionManager>(relaxed = true) { every { getUserId() } returns me },
            mockk<com.pingolin.track.util.HiddenDestinationsStore>(relaxed = true) { every { hidden } returns MutableStateFlow(emptySet()) },
            mockk<com.pingolin.track.data.local.dao.CircleMemberDao>(relaxed = true) { coEvery { get(any(), any()) } returns null },
            com.pingolin.track.testutil.quietPendingChanges(),
        )
        backgroundScope.launch { viewModel.destinations.collect {} }
        advanceUntilIdle()

        val items = viewModel.destinations.value
        assertEquals(listOf("mine", "sealed-as-me", "server-says-me", "not-known-yet"), items.map { it.destination.id })
        assertEquals(listOf(true, false, false, false), items.map { it.isMine })
    }
}
