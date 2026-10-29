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

import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.ui.circles.SharingStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * While the person going to an active destination is paused, or has real-time sharing off, in its
 * circle, its row says so ("Paused until …", "Location sharing off") in place of an ETA nothing
 * moves on. The destination stays active: the server neither ends nor claims it meanwhile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DestinationNotSharingTest {

    private val dispatcher = StandardTestDispatcher()
    private val me = "b1000000-0000-4000-8000-0000000000e1"
    private val bo = "b1000000-0000-4000-8000-0000000000b0"
    private val circle = "b1000000-0000-4000-8000-0000000000c1"

    private fun destination(owner: String, status: String = DestinationRepository.STATUS_ACTIVE) = DestinationEntity(
        id = "d-$owner-$status", userId = owner, circleId = circle, destName = "Gym",
        destLatitude = 0.0, destLongitude = 0.0, etaDayBucket = 20_600, status = status,
        startedDayBucket = 0, createdDayBucket = 0, updatedDayBucket = 0, creatorId = owner,
    )

    private fun item(status: SharingStatus, mine: Boolean = false, destinationStatus: String = DestinationRepository.STATUS_ACTIVE) =
        DestinationListItem(destination(bo, destinationStatus), placeName = null, isMine = mine, creatorSharing = status)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `another member's active destination says why while they aren't sharing`() {
        assertEquals(SharingStatus.Paused(5_000L), item(SharingStatus.Paused(5_000L)).notSharingStatus())
        assertEquals(SharingStatus.Off, item(SharingStatus.Off).notSharingStatus())
    }

    @Test
    fun `the ETA otherwise, and never for your own or one that has ended`() {
        assertNull(item(SharingStatus.On).notSharingStatus())
        assertNull(item(SharingStatus.Off, mine = true).notSharingStatus())
        assertNull(item(SharingStatus.Off, destinationStatus = DestinationRepository.STATUS_ARRIVED).notSharingStatus())
    }

    @Test
    fun `each row carries its traveller's sharing from their circle's member list`() = runTest(dispatcher) {
        val roster = MutableStateFlow(
            listOf(
                CircleMemberEntity(circleId = circle, userId = bo, displayName = "Bo", role = "member", joinedAt = 0, locationSharing = "off"),
            )
        )
        val repository = mockk<DestinationRepository>(relaxed = true) {
            every { observeAll() } returns flowOf(listOf(destination(bo), destination(me)))
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
            mockk<com.pingolin.track.data.local.dao.CircleMemberDao>(relaxed = true) {
                coEvery { get(any(), any()) } returns null
                every { observeAll() } returns roster
            },
            com.pingolin.track.testutil.quietPendingChanges(),
        )
        backgroundScope.launch { viewModel.destinations.collect {} }
        advanceUntilIdle()

        assertEquals(listOf(SharingStatus.Off, SharingStatus.On), viewModel.destinations.value.map { it.creatorSharing })

        roster.value = listOf(roster.value.single().copy(locationSharing = null))
        advanceUntilIdle()

        assertEquals("sharing again: the ETA again", listOf(SharingStatus.On, SharingStatus.On), viewModel.destinations.value.map { it.creatorSharing })
    }

    @Test
    fun `the row shows it in place of the ETA`() {
        val adapter = File("src/main/java/com/pingolin/track/ui/destinations/DestinationAdapter.kt").readText()
        val notSharing = adapter.indexOf("val notSharing = item.notSharingStatus()?.let {")
        val shown = adapter.indexOf("binding.tvEta.text = notSharing")
        val eta = adapter.indexOf("binding.tvEta.text = binding.root.context.getString(R.string.destinations_eta, formatEtaDate(etaBucket))")
        assertTrue(notSharing in 0 until shown && shown < eta)
        assertTrue(adapter.contains("MemberSharing.rowLabel(binding.root.context, it, System.currentTimeMillis())"))
    }
}
