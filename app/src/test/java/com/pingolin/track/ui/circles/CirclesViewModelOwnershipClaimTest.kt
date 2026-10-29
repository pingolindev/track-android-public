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

package com.pingolin.track.ui.circles

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.OwnershipClaimExistsException
import com.pingolin.track.data.remote.dto.OwnershipClaimResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.CirclesPaginationState
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
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
import org.junit.Rule
import org.junit.Test

/**
 * State transitions for circle ownership claims.
 *
 * The behaviour under test is what an owner sees when their circle is fourteen days from
 * changing hands. Two of these are the whole point:
 *
 *  - a failed READ must not render as "no claim", or a network blip reassures somebody
 *    whose circle is on a clock;
 *  - a successful REFUSE must clear the claim from state without waiting on a re-fetch,
 *    or the owner taps Refuse, the follow-up read fails, and the banner stays up telling
 *    them their circle is still being taken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CirclesViewModelOwnershipClaimTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK private lateinit var circleRepository: CircleRepository
    @MockK private lateinit var placeRepository: PlaceRepository
    @MockK private lateinit var tracksApi: TrackApi
    @MockK private lateinit var sessionManager: SessionManager
    @MockK private lateinit var locationConsentRepository: com.pingolin.track.domain.repository.LocationConsentRepository

    private lateinit var viewModel: CirclesViewModel

    private val circleId = "circle-123"

    private val claim = OwnershipClaimResponse(
        claimId = "claim-1",
        circleId = circleId,
        claimantUserId = "admin-9",
        status = "pending",
        requestedAt = "2026-08-23T10:15:30.123456Z",
        resolvesAt = "2026-09-06T10:15:30.123456Z",
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        every { circleRepository.memberKeyAlerts } returns MutableStateFlow(emptyList())
        every { circleRepository.keyAccessRequests } returns MutableStateFlow(emptyList())
        every { circleRepository.inviteRenewalQuestions } returns MutableStateFlow(emptyList())
        every { circleRepository.memberStates } returns MutableStateFlow(emptyMap())
        every { circleRepository.keyRotationCauses } returns MutableStateFlow(emptyMap())
        every { circleRepository.behindCircles } returns MutableStateFlow(emptySet())
        every { circleRepository.recordsBehindCircles } returns MutableStateFlow(emptySet())
        every { circleRepository.observeMyMemberships() } returns flowOf(emptyList())
        every { circleRepository.paginationState } returns MutableStateFlow(
            CirclesPaginationState(isLoading = false, hasMore = true)
        )
        every { circleRepository.observeMembers(any()) } returns flowOf(emptyList())
        every { circleRepository.observePendingInvites(any()) } returns flowOf(emptyList())
        every { circleRepository.resetPagination() } just Runs
        coEvery { circleRepository.loadInitialCircles() } returns Result.success(emptyList())
        coEvery { circleRepository.fetchReceivedInvites() } returns Result.success(emptyList())
        coEvery { circleRepository.retryPendingAccepts(any()) } returns 0
        every { circleRepository.waitingToJoin() } returns emptySet()
        every { sessionManager.getUserId() } returns "current-user-id"

        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ===== Reading =====

    @Test
    fun `nothing is known about a circle until it has been read`() = runTest {
        advanceUntilIdle()
        assertNull("no claim state before any read", viewModel.ownershipClaim.value)
    }

    @Test
    fun `a live claim lands in state under the circle that was asked about`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)

        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        val state = viewModel.ownershipClaim.value
        assertNotNull(state)
        assertEquals(circleId, state!!.circleId)
        assertEquals("claim-1", state.claim?.claimId)
        assertEquals("2026-09-06T10:15:30.123456Z", state.claim?.resolvesAt)
    }

    /**
     * "Checked, and there is none" is a real answer and must be distinguishable from
     * "not checked" — the banner has to stay hidden for the first and silent for the
     * second, and they are the same nullable claim.
     */
    @Test
    fun `no live claim is recorded as checked-and-empty, not as unknown`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(null)

        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        val state = viewModel.ownershipClaim.value
        assertNotNull("the circle HAS been checked", state)
        assertNull("and there is no claim on it", state!!.claim)
        assertEquals(circleId, state.circleId)
    }

    /**
     * The one that matters most. A read that fails must leave whatever was known in place
     * rather than overwrite it with "no claim": a dropped request is not evidence that
     * nobody is taking the circle, and rendering it as such is a reassurance nobody
     * verified.
     */
    @Test
    fun `a failed read does not erase a claim that is already known`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)
        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        coEvery { circleRepository.getOwnershipClaim(circleId) } returns
            Result.failure(Exception("network unreachable"))
        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        val state = viewModel.ownershipClaim.value
        assertNotNull("the known claim must survive a failed refresh", state)
        assertEquals("claim-1", state!!.claim?.claimId)
    }

    @Test
    fun `clearing drops the claim so it cannot render on another circle`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)
        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        viewModel.clearOwnershipClaim()

        assertNull(viewModel.ownershipClaim.value)
    }

    // ===== Refusing =====

    @Test
    fun `refusing reports success and takes the claim out of state`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)
        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        coEvery { circleRepository.refuseOwnershipClaim(circleId) } returns Result.success(Unit)
        viewModel.refuseOwnershipClaim(circleId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CirclesUiState.OwnershipClaimRefused)
        val state = viewModel.ownershipClaim.value
        assertNotNull(state)
        assertNull("a refused claim is gone, not still pending", state!!.claim)
    }

    /**
     * The server's own sentence is surfaced rather than a paraphrase. It distinguishes
     * "only the owner can refuse" (403) from "there is no live claim" (404), and the
     * second is not really a failure — it is what a claim withdrawn from another device
     * looks like.
     */
    @Test
    fun `a refusal that fails surfaces the server's reason`() = runTest {
        coEvery { circleRepository.refuseOwnershipClaim(circleId) } returns
            Result.failure(Exception("There is no live claim on this circle"))

        viewModel.refuseOwnershipClaim(circleId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CirclesUiState.Error)
        assertEquals("There is no live claim on this circle", (state as CirclesUiState.Error).message)
    }

    // ===== Opening =====

    @Test
    fun `opening a claim reports it and shows it straight away`() = runTest {
        coEvery { circleRepository.claimOwnership(circleId) } returns Result.success(claim)

        viewModel.claimOwnership(circleId)
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui is CirclesUiState.OwnershipClaimOpened)
        assertEquals("claim-1", (ui as CirclesUiState.OwnershipClaimOpened).claim.claimId)
        assertEquals("claim-1", viewModel.ownershipClaim.value?.claim?.claimId)
    }

    /**
     * A 409 is somebody else's claim already running, not a failed tap. It gets its own
     * state AND triggers a re-read, so closing the message shows what exists instead of
     * leaving the person thinking nothing happened.
     */
    @Test
    fun `a duplicate claim is reported as already-asked and re-reads the live one`() = runTest {
        coEvery { circleRepository.claimOwnership(circleId) } returns Result.failure(
            OwnershipClaimExistsException("Somebody has already asked to take over this circle")
        )
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)

        viewModel.claimOwnership(circleId)
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue("409 is not a generic Error", ui is CirclesUiState.OwnershipClaimAlreadyExists)
        assertEquals(
            "Somebody has already asked to take over this circle",
            (ui as CirclesUiState.OwnershipClaimAlreadyExists).message,
        )
        coVerify { circleRepository.getOwnershipClaim(circleId) }
        assertEquals("claim-1", viewModel.ownershipClaim.value?.claim?.claimId)
    }

    @Test
    fun `a refused open surfaces the server's reason`() = runTest {
        coEvery { circleRepository.claimOwnership(circleId) } returns
            Result.failure(Exception("You already own this circle"))

        viewModel.claimOwnership(circleId)
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertTrue(ui is CirclesUiState.Error)
        assertEquals("You already own this circle", (ui as CirclesUiState.Error).message)
    }

    // ===== Withdrawing =====

    @Test
    fun `withdrawing reports success and takes the claim out of state`() = runTest {
        coEvery { circleRepository.getOwnershipClaim(circleId) } returns Result.success(claim)
        viewModel.loadOwnershipClaim(circleId)
        advanceUntilIdle()

        coEvery { circleRepository.withdrawOwnershipClaim(circleId) } returns Result.success(Unit)
        viewModel.withdrawOwnershipClaim(circleId)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CirclesUiState.OwnershipClaimWithdrawn)
        assertNull(viewModel.ownershipClaim.value?.claim)
    }

    // ===== Role, which decides which controls are offered =====

    @Test
    fun `the current user's role is found on the roster`() = runTest {
        val members = listOf(
            member("someone-else", "owner"),
            member("current-user-id", "admin"),
        )
        assertEquals("admin", viewModel.currentUserRoleIn(members))
    }

    /**
     * Not on the roster means no role and therefore no controls. Returning something
     * plausible here would put an "ask to take over" button in front of somebody the
     * server is going to refuse.
     */
    @Test
    fun `somebody absent from the roster has no role`() = runTest {
        assertNull(viewModel.currentUserRoleIn(emptyList()))
        assertNull(viewModel.currentUserRoleIn(listOf(member("another-user", "admin"))))
    }

    private fun member(userId: String, role: String) = CircleMemberEntity(
        circleId = circleId,
        userId = userId,
        displayName = "Name $userId",
        role = role,
        joinedAt = 0L,
    )
}
