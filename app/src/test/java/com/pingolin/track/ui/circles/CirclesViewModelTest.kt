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
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.CirclesPaginationState
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for CirclesViewModel.
 *
 * Tests cover:
 * - Circle CRUD operations (create, join, leave, delete)
 * - Member management (invite, remove, role update)
 * - Invite revocation
 * - Ownership transfer
 * - Pagination
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CirclesViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var circleRepository: CircleRepository

    @MockK
    private lateinit var placeRepository: PlaceRepository

    @MockK
    private lateinit var tracksApi: TrackApi

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var locationConsentRepository: com.pingolin.track.domain.repository.LocationConsentRepository

    private lateinit var viewModel: CirclesViewModel

    private val testCircle = CircleEntity(
        id = "circle-123",
        name = "Test Circle",
        encryptedKey = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        isOwner = true,
        keyRotationNeeded = false,
        keyVersion = 1
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Default mock behaviors
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

    // =============================================================================
    // INITIALIZATION TESTS
    // =============================================================================

    @Test
    fun `viewModel loads circles on init`() = runTest {
        advanceUntilIdle()

        coVerify { circleRepository.resetPagination() }
        coVerify { circleRepository.loadInitialCircles() }
    }

    // =============================================================================
    // EMPTY STATE
    // =============================================================================

    /**
     * The sequence a fresh account actually goes through: the list is empty
     * while the first page loads, then loading ends and the list is STILL
     * empty. Re-evaluated only on a list emission, the second step would
     * never reach the screen and the tab would stay blank. The flow must
     * flip to true on the loading edge.
     */
    @Test
    fun `empty state appears when loading ends on an empty list`() = runTest {
        val pagination = MutableStateFlow(CirclesPaginationState(isLoading = true, hasMore = true))
        every { circleRepository.paginationState } returns pagination
        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())
        backgroundScope.launch { viewModel.showEmptyState.collect {} }
        advanceUntilIdle()

        assertFalse("still loading: no empty state yet", viewModel.showEmptyState.value)

        pagination.value = CirclesPaginationState(isLoading = false, hasMore = false)
        advanceUntilIdle()

        assertTrue("loading ended with nothing loaded: empty state shows", viewModel.showEmptyState.value)
    }

    @Test
    fun `empty state never shows while a circle exists`() = runTest {
        every { circleRepository.paginationState } returns MutableStateFlow(
            CirclesPaginationState(isLoading = false, hasMore = false)
        )
        every { circleRepository.observeCircles() } returns flowOf(listOf(testCircle))
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())
        backgroundScope.launch { viewModel.showEmptyState.collect {} }
        advanceUntilIdle()

        assertFalse(viewModel.showEmptyState.value)
    }

    // =============================================================================
    // CREATE CIRCLE TESTS
    // =============================================================================

    @Test
    fun `createCircle with empty name shows error`() = runTest {
        viewModel.createCircle("", "v2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Circle name cannot be empty", (state as CirclesUiState.Error).message)
    }

    @Test
    fun `createCircle with blank name shows error`() = runTest {
        viewModel.createCircle("   ", "v2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Circle name cannot be empty", (state as CirclesUiState.Error).message)
    }

    @Test
    fun `successful createCircle transitions to CircleCreated`() = runTest {
        coEvery { circleRepository.createCircle("Family", "v2") } returns Result.success(testCircle)

        viewModel.createCircle("Family", "v2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be CircleCreated", state is CirclesUiState.CircleCreated)
        assertEquals(testCircle, (state as CirclesUiState.CircleCreated).circle)
    }

    @Test
    fun `createCircle failure shows error`() = runTest {
        coEvery { circleRepository.createCircle("Family", "v2") } returns
            Result.failure(Exception("Network error"))

        viewModel.createCircle("Family", "v2")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Network error", (state as CirclesUiState.Error).message)
    }

    // =============================================================================
    // LEAVE CIRCLE TESTS
    // =============================================================================

    @Test
    fun `successful leaveCircle transitions to CircleLeft and clears selection`() = runTest {
        // First select a circle
        coEvery { circleRepository.refreshMembers(testCircle.id) } returns Result.success(emptyList<CircleMemberEntity>())
        viewModel.selectCircle(testCircle)
        advanceUntilIdle()

        assertEquals(testCircle, viewModel.selectedCircle.value)

        // Now leave it
        coEvery { circleRepository.leaveCircle(testCircle.id) } returns Result.success(Unit)

        viewModel.leaveCircle(testCircle.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be CircleLeft", state is CirclesUiState.CircleLeft)
        assertNull("Selected circle should be cleared", viewModel.selectedCircle.value)
    }

    @Test
    fun `leaveCircle failure shows error`() = runTest {
        coEvery { circleRepository.leaveCircle(testCircle.id) } returns
            Result.failure(Exception("Cannot leave: you are the owner"))

        viewModel.leaveCircle(testCircle.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Cannot leave: you are the owner", (state as CirclesUiState.Error).message)
    }

    // =============================================================================
    // DELETE CIRCLE TESTS
    // =============================================================================

    @Test
    fun `successful deleteCircle transitions to CircleDeleted and clears selection`() = runTest {
        // Select the circle first
        coEvery { circleRepository.refreshMembers(testCircle.id) } returns Result.success(emptyList<CircleMemberEntity>())
        viewModel.selectCircle(testCircle)
        advanceUntilIdle()

        // Delete it
        coEvery { circleRepository.deleteCircle(testCircle.id) } returns Result.success(Unit)

        viewModel.deleteCircle(testCircle.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be CircleDeleted", state is CirclesUiState.CircleDeleted)
        assertNull("Selected circle should be cleared", viewModel.selectedCircle.value)
    }

    @Test
    fun `deleteCircle failure shows error`() = runTest {
        coEvery { circleRepository.deleteCircle(testCircle.id) } returns
            Result.failure(Exception("Not authorized"))

        viewModel.deleteCircle(testCircle.id)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Not authorized", (state as CirclesUiState.Error).message)
    }

    // =============================================================================
    // MEMBER MANAGEMENT TESTS
    // =============================================================================

    @Test
    fun `an invitee whose account changed is asked about, and It's them invites`() = runTest {
        // Their number to compare, then this phone accepts their new details.
        val change = com.pingolin.track.domain.repository.InviteeChangedException(
            testCircle.id, "friend@example.test", "u-friend", "a".repeat(64), "b".repeat(64), "12345",
        )
        coEvery { circleRepository.inviteToCircle(testCircle.id, "friend@example.test", keepIfBehind = true) } returns Result.failure(change)
        coEvery { circleRepository.confirmInviteeAndInvite(change, keepIfBehind = true) } returns Result.success(
            InviteResponse(delivery = "notified")
        )

        viewModel.inviteMember(testCircle.id, "friend@example.test")
        advanceUntilIdle()
        val asked = viewModel.uiState.value
        assertTrue("State should ask about the invitee", asked is CirclesUiState.InviteeNeedsConfirmation)
        assertEquals(change, (asked as CirclesUiState.InviteeNeedsConfirmation).change)

        coEvery { circleRepository.refreshPendingInvites(testCircle.id) } returns Result.success(emptyList())
        viewModel.confirmInvitee(change)
        advanceUntilIdle()
        assertTrue("State should be MemberInvited", viewModel.uiState.value is CirclesUiState.MemberInvited)
    }

    @Test
    fun `successful inviteMember transitions to MemberInvited`() = runTest {
        coEvery { circleRepository.inviteToCircle(testCircle.id, "friend@example.test", keepIfBehind = true) } returns
            Result.success(
                InviteResponse(delivery = "notified")
            )

        viewModel.inviteMember(testCircle.id, "friend@example.test")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be MemberInvited", state is CirclesUiState.MemberInvited)
    }

    @Test
    fun `inviteMember failure shows error`() = runTest {
        coEvery { circleRepository.inviteToCircle(testCircle.id, "invalid", keepIfBehind = true) } returns
            Result.failure<InviteResponse>(Exception("Invalid email address"))

        viewModel.inviteMember(testCircle.id, "invalid")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Invalid email address", (state as CirclesUiState.Error).message)
    }

    @Test
    fun `the invite dialog keeps the invitation while this phone is behind`() = runTest {
        val line = "Waiting to sync. friend@example.test will be invited as soon as this phone catches up."
        coEvery { circleRepository.inviteToCircle(testCircle.id, "friend@example.test", keepIfBehind = true) } returns
            Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", line))

        viewModel.createInviteAndRefresh(testCircle.id, "friend@example.test")
        advanceUntilIdle()

        assertEquals(CirclesUiState.Kept(line), viewModel.uiState.value)
        coVerify(exactly = 0) { circleRepository.refreshPendingInvites(any()) }
    }

    @Test
    fun `the invite dialog asks about an invitee whose account changed, never shows it as an error`() = runTest {
        val change = com.pingolin.track.domain.repository.InviteeChangedException(
            testCircle.id, "friend@example.test", "u-friend", "a".repeat(64), "b".repeat(64), "12345",
        )
        coEvery { circleRepository.inviteToCircle(testCircle.id, "friend@example.test", keepIfBehind = true) } returns Result.failure(change)

        viewModel.createInviteAndRefresh(testCircle.id, "friend@example.test")
        advanceUntilIdle()

        assertEquals(change, (viewModel.uiState.value as CirclesUiState.InviteeNeedsConfirmation).change)
    }

    @Test
    fun `the invite dialog refreshes the pending invites once sent`() = runTest {
        coEvery { circleRepository.inviteToCircle(testCircle.id, "friend@example.test", keepIfBehind = true) } returns
            Result.success(InviteResponse(delivery = "notified"))
        coEvery { circleRepository.refreshPendingInvites(testCircle.id) } returns Result.success(emptyList())

        viewModel.createInviteAndRefresh(testCircle.id, "friend@example.test")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CirclesUiState.MemberInvited)
        coVerify(exactly = 1) { circleRepository.refreshPendingInvites(testCircle.id) }
    }

    @Test
    fun `successful removeMember transitions to MemberRemoved`() = runTest {
        coEvery { circleRepository.removeMember(testCircle.id, "member-id") } returns
            Result.success(true)

        viewModel.removeMember(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be MemberRemoved", state is CirclesUiState.MemberRemoved)
    }

    /** Behind, the removal went through at once without a new key, and says so. */
    @Test
    fun `a removal made while behind says who was removed, then the usual line`() = runTest {
        every { circleRepository.observeMembers(testCircle.id) } returns flowOf(
            listOf(CircleMemberEntity(testCircle.id, "member-id", "Sam", "member", joinedAt = 0)),
        )
        coEvery { circleRepository.getCircle(testCircle.id) } returns testCircle
        coEvery { circleRepository.removeMember(testCircle.id, "member-id") } returns Result.success(false)

        viewModel.removeMember(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("a removal without a new key: $state", state is CirclesUiState.MemberRemovedWithoutRotation)
        assertEquals("Sam was removed from ${testCircle.name}.", (state as CirclesUiState.MemberRemovedWithoutRotation).lead)
    }

    /**
     * A refused eject is not a dead end: it offers the fallback and keeps the
     * real reason, so the one person who can fix it (verify the survivor's
     * safety number and retry) is told the right thing, never a blanket "This
     * action requires the latest app — please update".
     */
    @Test
    fun `a refused eject offers the fallback and keeps the real reason`() = runTest {
        coEvery { circleRepository.removeMember(testCircle.id, "member-id") } returns
            Result.failure(Exception("Cannot rotate: 1 remaining member has an unverified key change"))

        viewModel.removeMember(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(
            "A refused eject must offer the no-rotation removal, not just an error: $state",
            state is CirclesUiState.MemberRemovalNeedsFallback,
        )
        state as CirclesUiState.MemberRemovalNeedsFallback
        assertEquals(testCircle.id, state.circleId)
        assertEquals("member-id", state.userId)
        assertTrue(
            "must carry the reason the eject failed, not a canned one: ${state.reason}",
            state.reason.contains("unverified key change"),
        )
    }

    @Test
    fun `revokeMemberAccess surfaces the server warning verbatim`() = runTest {
        val serverWarning = "They can no longer reach this circle, but the circle's key was not " +
            "changed, so anything they already downloaded stays readable to them."
        coEvery { circleRepository.revokeMemberAccess(testCircle.id, "member-id") } returns
            Result.success(serverWarning)

        viewModel.revokeMemberAccess(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be MemberRemovedWithoutRotation", state is CirclesUiState.MemberRemovedWithoutRotation)
        assertEquals(serverWarning, (state as CirclesUiState.MemberRemovedWithoutRotation).warning)
    }

    /**
     * An older server that sends no warning must leave the field null. The
     * dialog then falls back to a plain statement of fact rather than a
     * reassurance nobody verified.
     */
    @Test
    fun `a missing server warning is not invented`() = runTest {
        coEvery { circleRepository.revokeMemberAccess(testCircle.id, "member-id") } returns
            Result.success(null)

        viewModel.revokeMemberAccess(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CirclesUiState.MemberRemovedWithoutRotation)
        assertEquals(null, (state as CirclesUiState.MemberRemovedWithoutRotation).warning)
    }

    @Test
    fun `a failed revoke does not claim the member was removed`() = runTest {
        coEvery { circleRepository.revokeMemberAccess(testCircle.id, "member-id") } returns
            Result.failure(Exception("Circle not found"))

        viewModel.revokeMemberAccess(testCircle.id, "member-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("A failure must not read as a removal: $state", state is CirclesUiState.Error)
    }

    @Test
    fun `Withdraw to a waiting renewal's question withdraws the invitation for good, as the live answer does`() = runTest {
        val dao = com.pingolin.track.testutil.InMemoryPendingChangeDao()
        val store = com.pingolin.track.testutil.pendingChangesIn(dao)
        val id = (
            store.queue<Unit>(
                testCircle.id,
                com.pingolin.track.domain.pending.PendingInputs.RenewInvitation("Family", "inv-1", "st-1", "dan", "dan@x.test", "s1", "g1"),
            ).exceptionOrNull() as com.pingolin.track.domain.pending.QueuedForSync
            ).changeId
        val ask = com.pingolin.track.domain.pending.PendingQuestions.Ask(com.pingolin.track.domain.pending.PendingQuestions.RENEWAL, "dan", "s2", "g2", "1 2 3")
        dao.update(
            dao.get(id)!!.copy(
                state = com.pingolin.track.domain.pending.PendingChanges.NEEDS_PERSON,
                question = com.pingolin.track.domain.pending.PendingQuestions.ask(ask),
            )
        )
        every { circleRepository.pendingQuestionAnswered(testCircle.id, "dan", false) } just Runs
        coEvery { circleRepository.withdrawInvitation(testCircle.id, "inv-1", "st-1") } returns Result.success(Unit)
        val vm = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, store)

        vm.answerPending(id, accept = false, withdrawInvitation = true)
        advanceUntilIdle()

        assertEquals(null, dao.get(id))
        assertTrue("no line: the person decided", dao.notices.value.isEmpty())
        coVerify(exactly = 1) { circleRepository.withdrawInvitation(testCircle.id, "inv-1", "st-1") }
        verify(exactly = 1) { circleRepository.pendingQuestionAnswered(testCircle.id, "dan", false) }
    }

    @Test
    fun `Let them in says why when nothing was kept, says when it waits, and is quiet when it goes now`() = runTest {
        val request = com.pingolin.track.domain.repository.KeyAccessRequest(
            circleId = "circle-123", circleName = "Test Circle", userId = "sam", displayName = "Sam", email = null,
            reason = com.pingolin.track.domain.repository.KeyAccessRequest.Reason.UNCONFIRMED_INVITER, number = "1 2",
            sharingKeyHex = "s", signingKeyHex = "g",
        )
        advanceUntilIdle()
        val before = viewModel.uiState.value

        coEvery { circleRepository.letIn(request) } returns Result.success(Unit)
        viewModel.grantKeyAccess(request)
        advanceUntilIdle()
        assertEquals("it goes now: nothing to say", before, viewModel.uiState.value)

        val waits = "Waiting to sync. Sam will be let in as soon as this phone catches up."
        coEvery { circleRepository.letIn(request) } returns Result.failure(com.pingolin.track.domain.pending.QueuedForSync("ch-1", waits))
        viewModel.grantKeyAccess(request)
        advanceUntilIdle()
        assertEquals(CirclesUiState.Kept(waits), viewModel.uiState.value)

        val tooMany = com.pingolin.track.domain.pending.PendingLines.tooMany("Test Circle")
        coEvery { circleRepository.letIn(request) } returns Result.failure(Exception(tooMany))
        viewModel.grantKeyAccess(request)
        advanceUntilIdle()
        assertEquals(CirclesUiState.Error(tooMany), viewModel.uiState.value)

        // Never anything that isn't this app's own words.
        coEvery { circleRepository.letIn(request) } returns Result.failure(IllegalStateException("internal detail"))
        viewModel.grantKeyAccess(request)
        advanceUntilIdle()
        assertEquals(CirclesUiState.Error("Letting Sam in wasn't done."), viewModel.uiState.value)
    }

    @Test
    fun `Not now to a waiting Let them in question keeps the live question away, as its own Not now does`() = runTest {
        val dao = com.pingolin.track.testutil.InMemoryPendingChangeDao()
        val store = com.pingolin.track.testutil.pendingChangesIn(dao)
        val id = (
            store.queue<Unit>(
                testCircle.id,
                com.pingolin.track.domain.pending.PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s1", "g1"),
            ).exceptionOrNull() as com.pingolin.track.domain.pending.QueuedForSync
            ).changeId
        val ask = com.pingolin.track.domain.pending.PendingQuestions.Ask(com.pingolin.track.domain.pending.PendingQuestions.LET_IN, "sam", "s2", "g2", "4 4")
        dao.update(
            dao.get(id)!!.copy(
                state = com.pingolin.track.domain.pending.PendingChanges.NEEDS_PERSON,
                question = com.pingolin.track.domain.pending.PendingQuestions.ask(ask),
            )
        )
        every { circleRepository.pendingQuestionAnswered(testCircle.id, "sam", true) } just Runs
        val vm = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, store)

        vm.answerPending(id, accept = false)
        advanceUntilIdle()

        assertEquals(null, dao.get(id))
        verify(exactly = 1) { circleRepository.pendingQuestionAnswered(testCircle.id, "sam", true) }
    }

    @Test
    fun `successful updateMemberRole transitions to MemberRoleUpdated`() = runTest {
        coEvery { circleRepository.updateMemberRole(testCircle.id, "member-id", "admin", null, keepIfBehind = true) } returns
            Result.success(Unit)

        viewModel.updateMemberRole(testCircle.id, "member-id", "admin")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be MemberRoleUpdated", state is CirclesUiState.MemberRoleUpdated)
    }

    @Test
    fun `successful revokeInvite transitions to InviteRevoked`() = runTest {
        coEvery { circleRepository.revokeInvite(testCircle.id, "invite-id") } returns
            Result.success(Unit)

        viewModel.revokeInvite(testCircle.id, "invite-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be InviteRevoked", state is CirclesUiState.InviteRevoked)
    }

    // =============================================================================
    // OWNERSHIP TRANSFER TESTS
    // =============================================================================

    @Test
    fun `successful transferOwnership transitions to OwnershipTransferred`() = runTest {
        // Select circle first
        coEvery { circleRepository.refreshMembers(testCircle.id) } returns Result.success(emptyList<CircleMemberEntity>())
        viewModel.selectCircle(testCircle)
        advanceUntilIdle()

        coEvery { circleRepository.transferOwnership(testCircle.id, "new-owner-id") } returns
            Result.success(Unit)

        viewModel.transferOwnership(testCircle.id, "new-owner-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be OwnershipTransferred", state is CirclesUiState.OwnershipTransferred)
        assertNull("Selected circle should be cleared", viewModel.selectedCircle.value)
    }

    // No key-rotation tests here: there is no user-facing rotateCircleKeys path.
    // Rotation is automatic — see CircleRepository.autoRotateIfNeeded.

    // =============================================================================
    // SHARE PLACES TESTS
    // =============================================================================

    @Test
    fun `shareAllPlacesToCircle with places transitions to PlacesShared`() = runTest {
        coEvery { placeRepository.copyAllPlacesToCircle("source-id", "target-id", true, null, null) } returns
            Result.success(5)

        viewModel.shareAllPlacesToCircle("source-id", "target-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be PlacesShared", state is CirclesUiState.PlacesShared)
        assertEquals(5, (state as CirclesUiState.PlacesShared).count)
    }

    @Test
    fun `shareAllPlacesToCircle with no places shows error`() = runTest {
        coEvery { placeRepository.copyAllPlacesToCircle("source-id", "target-id", true, null, null) } returns
            Result.success(0)

        viewModel.shareAllPlacesToCircle("source-id", "target-id")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("No places to share", (state as CirclesUiState.Error).message)
    }

    // =============================================================================
    // STATE MANAGEMENT TESTS
    // =============================================================================

    @Test
    fun `resetState returns to Idle`() = runTest {
        // Get into an error state
        viewModel.createCircle("", "v2")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CirclesUiState.Error)

        viewModel.resetState()

        assertEquals(CirclesUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `getCurrentUserId returns session user id`() {
        every { sessionManager.getUserId() } returns "user-123"

        assertEquals("user-123", viewModel.getCurrentUserId())
    }

    @Test
    fun `selectCircle updates selectedCircle and refreshes members`() = runTest {
        coEvery { circleRepository.refreshMembers(testCircle.id) } returns Result.success(emptyList<CircleMemberEntity>())

        viewModel.selectCircle(testCircle)
        advanceUntilIdle()

        assertEquals(testCircle, viewModel.selectedCircle.value)
        coVerify { circleRepository.refreshMembers(testCircle.id) }
    }

    @Test
    fun `loadMoreCircles calls repository`() = runTest {
        coEvery { circleRepository.loadMoreCircles() } returns Result.success(emptyList())

        viewModel.loadMoreCircles()
        advanceUntilIdle()

        coVerify { circleRepository.loadMoreCircles() }
    }

    @Test
    fun `getOtherCircles excludes specified circle`() = runTest {
        val circle1 = testCircle
        val circle2 = testCircle.copy(id = "circle-456", name = "Other Circle")
        val circle3 = testCircle.copy(id = "circle-789", name = "Third Circle")

        every { circleRepository.observeCircles() } returns flowOf(listOf(circle1, circle2, circle3))

        // Recreate viewModel to pick up new mock
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())
        // `circles` is a StateFlow with SharingStarted.WhileSubscribed — it only collects
        // upstream while someone is subscribed. Subscribe from the test to actually flow.
        backgroundScope.launch { viewModel.circles.collect {} }
        advanceUntilIdle()

        val otherCircles = viewModel.getOtherCircles(circle1.id)

        assertEquals(2, otherCircles.size)
        assertFalse(otherCircles.any { it.id == circle1.id })
        assertTrue(otherCircles.any { it.id == circle2.id })
        assertTrue(otherCircles.any { it.id == circle3.id })
    }

    // =============================================================================
    // LOADING STATE TESTS
    // =============================================================================

    @Test
    fun `operations set Loading state before completing`() = runTest {
        coEvery { circleRepository.createCircle("Test", "v2") } coAnswers {
            delay(100)
            Result.success(testCircle)
        }

        viewModel.createCircle("Test", "v2")
        // CirclesViewModel.createCircle sets uiState = Loading inside viewModelScope.launch,
        // so it only reaches Loading after the dispatcher runs the launched coroutine once.
        // runCurrent() drives the coroutine up to its first suspension (delay(100)).
        runCurrent()

        // State should be Loading immediately
        assertEquals(CirclesUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        // State should be success after completion
        assertTrue(viewModel.uiState.value is CirclesUiState.CircleCreated)
    }

    // =============================================================================
    // INVITE ACCEPT — THE CONSENT GATE
    // =============================================================================

    /**
     * Consent is membership. Joining carries the agreement in the same request:
     * nothing is recorded afterwards and nothing is undone, because there is no
     * "joined, answer not recorded" state for the server to be in.
     */
    @Test
    fun `accepting an invite joins with the notice version shown, in one request`() = runTest(testDispatcher) {
        coEvery { circleRepository.acceptInvite("invite-1", "circle-123", "v2") } returns Result.success(Unit)

        viewModel.acceptInviteAfterConsent("invite-1", "circle-123", "Test Circle", "v2")
        advanceUntilIdle()

        coVerify(exactly = 1) { circleRepository.acceptInvite("invite-1", "circle-123", "v2") }
        coVerify(exactly = 0) { circleRepository.leaveCircle(any()) }
        assertTrue(viewModel.uiState.value is CirclesUiState.InviteAccepted)
    }

    /** A refused accept is not a membership: nothing to record, nothing to leave. */
    @Test
    fun `a refused accept leaves nothing behind`() = runTest(testDispatcher) {
        coEvery { circleRepository.acceptInvite("invite-1", "circle-123", "v2") } returns
            Result.failure(RuntimeException("expired"))

        viewModel.acceptInviteAfterConsent("invite-1", "circle-123", "Test Circle", "v2")
        advanceUntilIdle()

        coVerify(exactly = 0) { circleRepository.leaveCircle(any()) }
        assertTrue(viewModel.uiState.value is CirclesUiState.Error)
    }

    /** A refused accept says so in this app's words: never the SDK's key talk, nor a refused certificate's. */
    @Test
    fun `a refused accept says it in this app's words, never the failure's own`() = runTest(testDispatcher) {
        fun said(failure: Throwable): String? {
            coEvery { circleRepository.acceptInvite("invite-1", "circle-123", "v2") } returns Result.failure(failure)
            viewModel.acceptInviteAfterConsent("invite-1", "circle-123", "Test Circle", "v2")
            advanceUntilIdle()
            return (viewModel.uiState.value as CirclesUiState.Error).message
        }
        assertEquals(
            com.pingolin.track.util.UNREACHABLE_TEXT,
            said(javax.net.ssl.SSLHandshakeException("A certificate on the path to api-track.pingolindev.com has a key known to be compromised")),
        )
        assertEquals("Failed to accept invite", said(uniffi.pingolin_mobile.MobileException.KeyNotFound("Circle key not found: circle-123")))
        // What this app said itself still shows as it is.
        assertEquals("Couldn't join just now.", said(Exception("Couldn't join just now.")))
    }

    @Test
    fun `creating a circle carries the notice version the creator agreed to`() = runTest(testDispatcher) {
        coEvery { circleRepository.createCircle("Hiking", "v2") } returns Result.success(testCircle)

        viewModel.createCircle("Hiking", "v2")
        advanceUntilIdle()

        coVerify(exactly = 1) { circleRepository.createCircle("Hiking", "v2") }
        assertTrue(viewModel.uiState.value is CirclesUiState.CircleCreated)
    }

    /**
     * Nothing on the circle screen withdraws the agreement apart from leaving: the circle's
     * own Leave is how sharing stops for good, and the server closes the agreement then.
     */
    @Test
    fun `nothing here stops sharing apart from leaving`() {
        val methods = CirclesViewModel::class.java.declaredMethods.map { it.name }
        assertTrue(methods.toString(), methods.none { it.startsWith("stopSharing") })
        assertTrue(com.pingolin.track.domain.repository.LocationConsentRepository::class.java.declaredMethods.none { it.name.startsWith("withdraw") })
    }

    // =============================================================================
    // RE-KEY WARNING
    // =============================================================================

    @Test
    fun `the cards offer Invite on an owner's and an admin's circle where it takes effect, looked again as the records are read`() = runTest {
        val owned = testCircle.copy(id = "c-own", isOwner = true)
        val administered = testCircle.copy(id = "c-admin", isOwner = false)
        val memberOnly = testCircle.copy(id = "c-member", isOwner = false)
        // An admin on the server's word, whom the circle's records name a plain member.
        val listOnly = testCircle.copy(id = "c-list-admin", isOwner = false)
        every { circleRepository.observeCircles() } returns flowOf(listOf(owned, administered, memberOnly, listOnly))
        every { circleRepository.observeMyMemberships() } returns flowOf(
            listOf(
                CircleMemberEntity("c-admin", "current-user-id", "Me", "admin", joinedAt = 0L),
                CircleMemberEntity("c-member", "current-user-id", "Me", "member", joinedAt = 0L),
                CircleMemberEntity("c-list-admin", "current-user-id", "Me", "admin", joinedAt = 0L),
            )
        )
        val recordsNoted = MutableStateFlow(0L)
        every { circleRepository.recordsNoted } returns recordsNoted
        var waiting = emptySet<String>()
        every { circleRepository.rightsIn(any(), any()) } answers {
            val circleId = arg<String>(0)
            val records = when (circleId) {
                in waiting -> com.pingolin.track.domain.repository.CircleRights.Records.WAITING
                "c-list-admin" -> com.pingolin.track.domain.repository.CircleRights.Records.READ
                else -> com.pingolin.track.domain.repository.CircleRights.Records.NONE
            }
            com.pingolin.track.domain.repository.CircleRights.of(arg(1), records, uniffi.pingolin_mobile.CircleRole.MEMBER)
        }
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())

        val collecting = launch { viewModel.inviteOffered.collect { } }
        assertEquals(setOf("c-own", "c-admin"), viewModel.inviteOffered.first { it.isNotEmpty() })

        // The records read here now wait on the owner's circle: no Invite there until they're read.
        waiting = setOf("c-own")
        recordsNoted.value = 1
        assertEquals(setOf("c-admin"), viewModel.inviteOffered.first { it != setOf("c-own", "c-admin") })
        collecting.cancel()
    }

    @Test
    fun `the re-key warning shows to owners and admins, and only after a departure`() = runTest {
        val owned = testCircle.copy(id = "c-own", isOwner = true, keyRotationNeeded = true)
        val administered = testCircle.copy(id = "c-admin", isOwner = false, keyRotationNeeded = true)
        val memberOnly = testCircle.copy(id = "c-member", isOwner = false, keyRotationNeeded = true)
        val joinOnly = testCircle.copy(id = "c-join", isOwner = true, keyRotationNeeded = true)
        val alreadyRekeyed = testCircle.copy(id = "c-done", isOwner = true, keyRotationNeeded = false)
        every { circleRepository.observeCircles() } returns
            flowOf(listOf(owned, administered, memberOnly, joinOnly, alreadyRekeyed))
        every { circleRepository.keyRotationCauses } returns MutableStateFlow(
            mapOf(
                "c-own" to "member_removed",
                "c-admin" to "member_left",
                "c-member" to "member_removed",
                "c-join" to "member_joined",
                "c-done" to "member_removed",
            )
        )
        every { circleRepository.observeMyMemberships() } returns flowOf(
            listOf(
                CircleMemberEntity("c-admin", "user-1", "Me", "admin", joinedAt = 0L),
                CircleMemberEntity("c-member", "user-1", "Me", "member", joinedAt = 0L),
            )
        )
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())

        val collecting = launch { viewModel.rekeyWarnings.collect { } }
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "c-own" to com.pingolin.track.R.string.rekey_warning_member_removed,
                "c-admin" to com.pingolin.track.R.string.rekey_warning_member_left,
            ),
            viewModel.rekeyWarnings.value,
        )
        collecting.cancel()
    }

    @Test
    fun `a circle whose key moved on without this phone says so to everyone`() = runTest {
        // Nothing is shared with it from here until the new key arrives; a
        // plain member is told too, and it outranks the owners' re-key
        // warning.
        val behindAsMember = testCircle.copy(id = "c-behind", isOwner = false, keyRotationNeeded = false)
        val behindAsOwner = testCircle.copy(id = "c-behind-own", isOwner = true, keyRotationNeeded = true)
        val current = testCircle.copy(id = "c-current", isOwner = false, keyRotationNeeded = false)
        every { circleRepository.observeCircles() } returns flowOf(listOf(behindAsMember, behindAsOwner, current))
        every { circleRepository.keyRotationCauses } returns MutableStateFlow(mapOf("c-behind-own" to "member_removed"))
        every { circleRepository.observeMyMemberships() } returns flowOf(emptyList())
        every { circleRepository.behindCircles } returns MutableStateFlow(setOf("c-behind", "c-behind-own"))
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())

        val collecting = launch { viewModel.rekeyWarnings.collect { } }
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "c-behind" to com.pingolin.track.R.string.key_behind_warning,
                "c-behind-own" to com.pingolin.track.R.string.key_behind_warning,
            ),
            viewModel.rekeyWarnings.value,
        )
        collecting.cancel()
    }

    @Test
    fun `a circle this phone's records say it's behind on waits to sync the same way`() = runTest {
        // Behind by its own records, whatever version the list shows.
        val waiting = testCircle.copy(id = "c-records", isOwner = false, keyRotationNeeded = false)
        val current = testCircle.copy(id = "c-current", isOwner = false, keyRotationNeeded = false)
        every { circleRepository.observeCircles() } returns flowOf(listOf(waiting, current))
        every { circleRepository.recordsBehindCircles } returns MutableStateFlow(setOf("c-records"))
        viewModel = CirclesViewModel(circleRepository, placeRepository, tracksApi, sessionManager, locationConsentRepository, com.pingolin.track.testutil.quietPendingChanges())

        val collecting = launch { viewModel.rekeyWarnings.collect { } }
        advanceUntilIdle()

        assertEquals(mapOf("c-records" to com.pingolin.track.R.string.key_behind_warning), viewModel.rekeyWarnings.value)
        collecting.cancel()
    }

    // =============================================================================
    // RESUME: back to the mode held before the pause
    // =============================================================================

    private fun restored() = com.pingolin.track.data.remote.dto.PrivacySettingsResponse(
        circleId = "circle-123", sharingMode = "approximate", approximateRadiusMiles = 5,
        shareRealtime = false, shareHistory = true, shareBattery = false,
        pausedUntil = null, modeBeforePause = null, primaryDeviceId = null,
    )

    @Test
    fun `Resume asks the server to end the pause and shows and keeps what it put back`() = runTest {
        coEvery { tracksApi.resumeSharing("circle-123") } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(success = true, data = restored(), error = null)
        )
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        every { circleRepository.resumedHere("circle-123") } just Runs

        viewModel.resumeSharing("circle-123")
        advanceUntilIdle()

        assertEquals(restored(), viewModel.privacySettings.value)
        coVerify(exactly = 1) { circleRepository.persistPrivacyLocally(restored()) }
        // A resume made here: the first point after it goes at once.
        verify(exactly = 1) { circleRepository.resumedHere("circle-123") }
        // No settings PUT naming a mode (it named "exact"), and nothing that could switch
        // real-time sharing back on.
        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `real-time sharing switched back on here is a resume made here, and a save that changes nothing isn't`() = runTest {
        val off = restored().copy(shareRealtime = false)
        val on = restored().copy(shareRealtime = true)
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(success = true, data = on, error = null)
        )
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(success = true, data = restored(), error = null)
        )
        every { circleRepository.resumedHere("circle-123") } just Runs
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        // On already: switching it on again ends nothing.
        viewModel.updatePrivacySettings("circle-123", shareRealtime = true)
        advanceUntilIdle()
        verify(exactly = 0) { circleRepository.resumedHere(any()) }

        coEvery { tracksApi.getPrivacySettings("circle-123") } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(success = true, data = off, error = null)
        )
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()
        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        advanceUntilIdle()
        verify(exactly = 0) { circleRepository.resumedHere(any()) }

        viewModel.updatePrivacySettings("circle-123", shareRealtime = true)
        advanceUntilIdle()
        verify(exactly = 1) { circleRepository.resumedHere("circle-123") }
    }

    @Test
    fun `a server without the resume route shows the error, changes nothing and never falls back to the PUT`() = runTest {
        coEvery { tracksApi.resumeSharing("circle-123") } returns retrofit2.Response.error(
            404, "{}".toResponseBody(null)
        )

        viewModel.resumeSharing("circle-123")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is CirclesUiState.Error)
        assertEquals("Failed to resume sharing", (state as CirclesUiState.Error).message)
        assertNull(viewModel.privacySettings.value)
        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
        coVerify(exactly = 0) { circleRepository.persistPrivacyLocally(any()) }
    }

    // =============================================================================
    // a privacy read that failed shows no form, and nothing writes defaults
    // =============================================================================

    private fun okSettings(circleId: String = "circle-123", body: com.pingolin.track.data.remote.dto.PrivacySettingsResponse = restored().copy(circleId = circleId)) =
        retrofit2.Response.success(com.pingolin.track.data.remote.dto.ApiResponse(success = true, data = body, error = null))

    /** A paused, approximate member with alerts on them OFF: what a write from defaults would undo. */
    private fun guarded() = restored().copy(
        sharingMode = "paused", pausedUntil = "2026-10-15T20:00:00Z", modeBeforePause = "approximate",
        shareRealtime = true, allowAlertsOnMe = false,
    )

    @Test
    fun `a privacy read that throws holds no settings and says the read failed`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } throws java.io.IOException("offline")

        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        assertNull("no defaults to show or save", viewModel.privacySettings.value)
        assertEquals("circle-123", viewModel.privacyLoadFailed.value)
        assertEquals(PrivacyForm.FAILED, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
    }

    @Test
    fun `a server error is a failed read too, not an empty form`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns retrofit2.Response.error(500, "{}".toResponseBody(null))

        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        assertNull(viewModel.privacySettings.value)
        assertEquals("circle-123", viewModel.privacyLoadFailed.value)
    }

    /** The server's own "no row yet": the one 404 that is its default posture. */
    private fun noRowYet() = retrofit2.Response.error<com.pingolin.track.data.remote.dto.ApiResponse<com.pingolin.track.data.remote.dto.PrivacySettingsResponse>>(
        404, """{"success":false,"error":"Privacy settings not found for this circle"}""".toResponseBody(null)
    )

    @Test
    fun `the server's own no-row answer shows its default posture, exact and sharing`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns noRowYet()

        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        val shown = viewModel.privacySettings.value
        assertNotNull(shown)
        assertEquals("exact", shown!!.sharingMode)
        assertTrue(shown.shareRealtime)
        assertNull(viewModel.privacyLoadFailed.value)
    }

    @Test
    fun `any other 404 is a failed read, not the server's defaults`() = runTest {
        // A proxy answering while the server restarts, an empty answer, another route's not-found.
        for (body in listOf("404 page not found", "", """{"success":false,"error":"Not found"}""")) {
            coEvery { tracksApi.getPrivacySettings("circle-123") } returns retrofit2.Response.error(404, body.toResponseBody(null))

            viewModel.loadPrivacySettings("circle-123")
            advanceUntilIdle()

            assertNull("'$body': no defaults to show or save", viewModel.privacySettings.value)
            assertEquals("'$body'", "circle-123", viewModel.privacyLoadFailed.value)
        }
    }

    @Test
    fun `nothing is written from a form that never loaded`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } throws java.io.IOException("offline")
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        viewModel.enableBubble("circle-123", 5)
        viewModel.disableBubble("circle-123")
        viewModel.updateShowAllDevices("circle-123", true)
        advanceUntilIdle()

        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
        assertEquals(PRIVACY_NOT_LOADED, (viewModel.uiState.value as CirclesUiState.Error).message)
    }

    @Test
    fun `opening another circle drops the first one's settings, so none are copied over`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()
        assertEquals(guarded(), viewModel.privacySettings.value)

        coEvery { tracksApi.getPrivacySettings("circle-456") } throws java.io.IOException("offline")
        viewModel.loadPrivacySettings("circle-456")
        // Gone at once, before the read even answers: never "circle-123's form" for circle-456.
        assertNull(viewModel.privacySettings.value)
        advanceUntilIdle()

        assertEquals(PrivacyForm.FAILED, privacyForm("circle-456", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
        viewModel.updatePrivacySettings("circle-456", shareBattery = false)
        advanceUntilIdle()
        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `a write for a circle whose settings aren't the ones held is refused, never copied over`() = runTest {
        // circle-123's settings are held; nothing has read circle-456's. Every writer
        // refuses circle-456 rather than round-trip circle-123's row into it.
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-456", shareHistory = false)
        viewModel.enableBubble("circle-456", 5)
        viewModel.disableBubble("circle-456")
        viewModel.updateShowAllDevices("circle-456", true)
        advanceUntilIdle()

        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
        assertEquals(PRIVACY_NOT_LOADED, (viewModel.uiState.value as CirclesUiState.Error).message)
        assertEquals("circle-123's own copy is untouched", guarded(), viewModel.privacySettings.value)
    }

    @Test
    fun `a write round-trips the loaded pause, approximate mode and alerts-on-me untouched`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        val sent = slot<com.pingolin.track.data.remote.dto.UpdatePrivacyRequest>()
        coEvery { tracksApi.updatePrivacySettings("circle-123", capture(sent)) } returns okSettings(body = guarded())
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        advanceUntilIdle()

        assertEquals("paused", sent.captured.sharingMode)
        assertEquals("2026-10-15T20:00:00Z", sent.captured.pausedUntil)
        assertEquals(false, sent.captured.allowAlertsOnMe)
        assertEquals(false, sent.captured.shareHistory)
    }

    @Test
    fun `a refresh that fails after a write clears the copy from before it`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returnsMany listOf(okSettings(body = guarded())) andThenThrows java.io.IOException("offline")
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } returns okSettings(body = guarded())
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        advanceUntilIdle()

        // The refresh after the write failed: what's held may not match the server's
        // row.
        assertNull(viewModel.privacySettings.value)
        assertEquals("circle-123", viewModel.privacyLoadFailed.value)
        viewModel.updatePrivacySettings("circle-123", shareBattery = false)
        advanceUntilIdle()
        coVerify(exactly = 1) { tracksApi.updatePrivacySettings("circle-123", any()) }
    }

    @Test
    fun `Try again reads again, and the form shows once it loads`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } throws java.io.IOException("offline")
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()
        assertEquals(PrivacyForm.FAILED, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))

        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        viewModel.loadPrivacySettings("circle-123")
        assertEquals("loading again", PrivacyForm.LOADING, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
        advanceUntilIdle()

        assertEquals(PrivacyForm.READY, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
        assertNull(viewModel.privacyLoadFailed.value)
    }

    // A write whose outcome isn't known: the copy from before it is dropped and the row
    // read again, so the next write can't undo what this one did.

    @Test
    fun `a write with no answer drops the copy from before it and reads the row again`() = runTest {
        val after = guarded().copy(shareHistory = false)
        coEvery { tracksApi.getPrivacySettings("circle-123") } returnsMany listOf(okSettings(body = guarded()), okSettings(body = after))
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } throws java.io.IOException("timeout")
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        advanceUntilIdle()

        coVerify(exactly = 2) { tracksApi.getPrivacySettings("circle-123") }
        assertEquals("the server's row, not the copy from before the write", after, viewModel.privacySettings.value)
    }

    @Test
    fun `a pause whose answer was lost can't be undone by the next switch`() = runTest {
        val sharing = guarded().copy(sharingMode = "exact", pausedUntil = null, modeBeforePause = null)
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = sharing) andThenThrows java.io.IOException("offline")
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.pauseSharing("circle-123", any()) } throws java.io.IOException("timeout")
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.pauseSharing("circle-123", 60)
        advanceUntilIdle()

        // The pause may have landed: no form until the row is read again, and that read failed.
        assertNull(viewModel.privacySettings.value)
        assertEquals(PrivacyForm.FAILED, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
        viewModel.updatePrivacySettings("circle-123", shareBattery = false)
        advanceUntilIdle()
        coVerify(exactly = 0) { tracksApi.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `a refused approximate write, and a failed resume, read the row again too`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } returns retrofit2.Response.error(500, "{}".toResponseBody(null))
        coEvery { tracksApi.resumeSharing("circle-123") } throws java.io.IOException("timeout")
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.enableBubble("circle-123", 3)
        advanceUntilIdle()
        coVerify(exactly = 2) { tracksApi.getPrivacySettings("circle-123") }

        viewModel.resumeSharing("circle-123")
        advanceUntilIdle()
        coVerify(exactly = 3) { tracksApi.getPrivacySettings("circle-123") }
        assertEquals(guarded(), viewModel.privacySettings.value)
    }

    private class PrivacyWrite(val name: String, val failure: String, val send: () -> Unit)

    @Test
    fun `every write whose outcome isn't known says so, drops the copy at once and reads the row again`() = runTest {
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        every { circleRepository.resumedHere("circle-123") } just Runs
        val after = guarded().copy(shareBattery = true)
        val writes = listOf(
            PrivacyWrite("a switch", "Failed to update privacy settings") { viewModel.updatePrivacySettings("circle-123", shareHistory = false) },
            PrivacyWrite("approximate on", "Failed to enable bubble mode") { viewModel.enableBubble("circle-123", 3) },
            PrivacyWrite("approximate off", "Failed to disable bubble mode") { viewModel.disableBubble("circle-123") },
            PrivacyWrite("show all devices", "Failed to update device visibility") { viewModel.updateShowAllDevices("circle-123", true) },
            PrivacyWrite("pause", "Failed to pause sharing") { viewModel.pauseSharing("circle-123", 60) },
            PrivacyWrite("resume", "Failed to resume sharing") { viewModel.resumeSharing("circle-123") },
        )
        for (mode in listOf("no answer", "refused")) {
            if (mode == "no answer") {
                coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } throws java.io.IOException("timeout")
                coEvery { tracksApi.pauseSharing("circle-123", any()) } throws java.io.IOException("timeout")
                coEvery { tracksApi.resumeSharing("circle-123") } throws java.io.IOException("timeout")
            } else {
                coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } returns retrofit2.Response.error(500, "{}".toResponseBody(null))
                coEvery { tracksApi.pauseSharing("circle-123", any()) } returns retrofit2.Response.error(500, "{}".toResponseBody(null))
                coEvery { tracksApi.resumeSharing("circle-123") } returns retrofit2.Response.error(500, "{}".toResponseBody(null))
            }
            for (write in writes) {
                val what = "${write.name}, $mode"
                viewModel.resetState()
                coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
                viewModel.loadPrivacySettings("circle-123")
                advanceUntilIdle()
                assertEquals(what, guarded(), viewModel.privacySettings.value)

                val reread = kotlinx.coroutines.CompletableDeferred<Unit>()
                coEvery { tracksApi.getPrivacySettings("circle-123") } coAnswers { reread.await(); okSettings(body = after) }
                write.send()
                advanceUntilIdle()

                // Gone before the read answers: nothing can be saved from it meanwhile.
                assertNull("$what: the copy from before it", viewModel.privacySettings.value)
                assertEquals(what, PrivacyForm.LOADING, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
                assertEquals(what, write.failure, (viewModel.uiState.value as? CirclesUiState.Error)?.message)

                reread.complete(Unit)
                advanceUntilIdle()
                assertEquals("$what: the server's row", after, viewModel.privacySettings.value)
            }
        }
    }

    @Test
    fun `a second change made before the first one's answer is built from the row read after it`() = runTest {
        val start = guarded().copy(sharingMode = "exact", pausedUntil = null, modeBeforePause = null, shareRealtime = true, shareHistory = true)
        val afterFirst = start.copy(shareRealtime = false)
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.getPrivacySettings("circle-123") } returnsMany listOf(
            okSettings(body = start), okSettings(body = afterFirst), okSettings(body = afterFirst.copy(shareHistory = false)),
        )
        val sent = mutableListOf<com.pingolin.track.data.remote.dto.UpdatePrivacyRequest>()
        val firstAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { tracksApi.updatePrivacySettings("circle-123", capture(sent)) } coAnswers {
            if (sent.size == 1) firstAnswer.await()
            okSettings(body = start)
        }
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        // Real-time sharing off, then history off before the first change has its answer.
        viewModel.updatePrivacySettings("circle-123", shareRealtime = false)
        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        advanceUntilIdle()
        assertEquals("the second waits for the first", 1, sent.size)

        firstAnswer.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, sent.size)
        assertEquals(false, sent[1].shareHistory)
        assertEquals("real-time sharing stays off", false, sent[1].shareRealtime)
        assertEquals(afterFirst.copy(shareHistory = false), viewModel.privacySettings.value)
    }

    @Test
    fun `the slider's steps send only the newest radius still waiting`() = runTest {
        val start = guarded().copy(sharingMode = "approximate", pausedUntil = null, modeBeforePause = null, approximateRadiusMiles = 1)
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = start)
        val sent = mutableListOf<com.pingolin.track.data.remote.dto.UpdatePrivacyRequest>()
        val firstAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { tracksApi.updatePrivacySettings("circle-123", capture(sent)) } coAnswers {
            if (sent.size == 1) firstAnswer.await()
            okSettings(body = start)
        }
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.enableBubble("circle-123", 2)
        advanceUntilIdle()
        for (miles in 3..6) viewModel.enableBubble("circle-123", miles)
        advanceUntilIdle()
        firstAnswer.complete(Unit)
        advanceUntilIdle()

        assertEquals("the first step, then only the newest", listOf(2, 6), sent.map { it.approximateRadiusMiles })
    }

    @Test
    fun `a change still out when another circle's dialog opens goes on, and that dialog shows its own row`() = runTest {
        val other = guarded().copy(circleId = "circle-456", allowAlertsOnMe = true)
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded())
        coEvery { tracksApi.getPrivacySettings("circle-456") } returns okSettings(circleId = "circle-456", body = other)
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } coAnswers { answer.await(); okSettings(body = guarded()) }
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        viewModel.updatePrivacySettings("circle-123", shareBattery = false)
        advanceUntilIdle()
        viewModel.loadPrivacySettings("circle-456")
        advanceUntilIdle()
        answer.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { tracksApi.updatePrivacySettings("circle-123", any()) }
        assertEquals("circle-456's own row, not circle-123's later answer", other, viewModel.privacySettings.value)
        assertEquals(PrivacyForm.READY, privacyForm("circle-456", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
    }

    @Test
    fun `a row the server gives after a failed read clears the failure`() = runTest {
        coEvery { circleRepository.persistPrivacyLocally(any()) } just Runs
        every { circleRepository.resumedHere("circle-123") } just Runs
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns okSettings(body = guarded()) andThenThrows java.io.IOException("offline")
        coEvery { tracksApi.updatePrivacySettings("circle-123", any()) } throws java.io.IOException("timeout")
        coEvery { tracksApi.resumeSharing("circle-123") } returns okSettings(body = restored())
        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        // A switch whose answer is lost and whose read fails, then Resume, tapped meanwhile, answers.
        viewModel.updatePrivacySettings("circle-123", shareHistory = false)
        viewModel.resumeSharing("circle-123")
        advanceUntilIdle()

        assertEquals(restored(), viewModel.privacySettings.value)
        assertNull(viewModel.privacyLoadFailed.value)
    }

    @Test
    fun `a read answered without settings is a failed read, not an empty form`() = runTest {
        coEvery { tracksApi.getPrivacySettings("circle-123") } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse<com.pingolin.track.data.remote.dto.PrivacySettingsResponse>(success = true, data = null, error = null)
        )

        viewModel.loadPrivacySettings("circle-123")
        advanceUntilIdle()

        assertEquals(PrivacyForm.FAILED, privacyForm("circle-123", viewModel.privacySettings.value, viewModel.privacyLoadFailed.value))
    }
}
