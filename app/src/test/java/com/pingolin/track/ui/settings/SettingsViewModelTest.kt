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

package com.pingolin.track.ui.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.ProfileResponse
import com.pingolin.track.data.remote.dto.SubscriptionInfo
import com.pingolin.track.data.remote.dto.UpdateTracksProfileRequest
import com.pingolin.track.domain.repository.AccountDeletionOutcome
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.repository.ExportRepository
import com.pingolin.track.util.PremiumStatusRefresher
import com.pingolin.track.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for SettingsViewModel.
 *
 * Tests cover:
 * - Account deletion (password CharArray passed to auth repo)
 * - Display name update (success / failure)
 * - Subscription tier fetch (Premium vs Free based on status)
 * - Logout
 * - Environment change
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var authRepository: AuthRepository

    @MockK
    private lateinit var exportRepository: ExportRepository

    @MockK
    private lateinit var tracksApi: TrackApi

    @MockK
    private lateinit var premiumStatusRefresher: PremiumStatusRefresher

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var restoreRepository: com.pingolin.track.domain.backup.RestoreRepository

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { sessionManager.getSubscriptionTier() } returns "Free"
        every { sessionManager.getEmail() } returns "test@pingolin.test"
        every { sessionManager.getDisplayName() } returns "Tester"
        every { sessionManager.getBuildEnvironment() } returns SessionManager.Environment.DEV

        viewModel = SettingsViewModel(
            authRepository, exportRepository, restoreRepository, tracksApi,
            sessionManager, premiumStatusRefresher, com.pingolin.track.testutil.quietPendingChanges(),
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // =============================================================================
    // DELETE ACCOUNT
    // =============================================================================

    @Test
    fun `deleteAccount success transitions to AccountDeleted`() = runTest {
        val password = "mypassword".toCharArray()
        coEvery { authRepository.deleteAccount(password) } returns Result.success(AccountDeletionOutcome())

        viewModel.deleteAccount(password)
        advanceUntilIdle()

        assertEquals(SettingsUiState.AccountDeleted(AccountDeletionOutcome()), viewModel.uiState.value)
        coVerify { authRepository.deleteAccount(password) }
    }

    @Test
    fun `deleteAccount carries the billing outcome through to the UI state`() = runTest {
        // The case that matters: the account is gone and the user is
        // still being billed by Apple. If this does not reach the
        // fragment there is no second chance to tell them — they have no
        // account left to log back into.
        val password = "mypassword".toCharArray()
        val outcome = AccountDeletionOutcome(
            appStoreSubscriptionsRemaining = 1,
            cancellationsFailed = 0,
            needsAttention = true
        )
        coEvery { authRepository.deleteAccount(password) } returns Result.success(outcome)

        viewModel.deleteAccount(password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.AccountDeleted)
        assertEquals(outcome, (state as SettingsUiState.AccountDeleted).billing)
        assertTrue(state.billing.needsAttention)
    }

    @Test
    fun `deleteAccount reports a failed cancellation as needing attention`() = runTest {
        val password = "mypassword".toCharArray()
        val outcome = AccountDeletionOutcome(cancellationsFailed = 1, needsAttention = true)
        coEvery { authRepository.deleteAccount(password) } returns Result.success(outcome)

        viewModel.deleteAccount(password)
        advanceUntilIdle()

        val state = viewModel.uiState.value as SettingsUiState.AccountDeleted
        // No App Store row, so the fragment shows the "write to us" branch.
        assertEquals(0, state.billing.appStoreSubscriptionsRemaining)
        assertTrue(state.billing.needsAttention)
    }

    @Test
    fun `deleteAccount failure transitions to Error`() = runTest {
        val password = "mypassword".toCharArray()
        coEvery { authRepository.deleteAccount(any()) } returns Result.failure(Exception("Wrong password"))

        viewModel.deleteAccount(password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Error)
        assertEquals("Wrong password", (state as SettingsUiState.Error).message)
    }

    @Test
    fun `deleteAccount exception transitions to Error`() = runTest {
        val password = "pwd".toCharArray()
        coEvery { authRepository.deleteAccount(any()) } throws RuntimeException("Unexpected")

        viewModel.deleteAccount(password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Error)
        // A runtime's own words never show: this app's line instead.
        assertEquals("Failed to delete account", (state as SettingsUiState.Error).message)
    }

    @Test
    fun `deleteAccount sets DeletingAccount before completing`() = runTest {
        val password = "pwd".toCharArray()
        coEvery { authRepository.deleteAccount(any()) } coAnswers {
            kotlinx.coroutines.delay(100)
            Result.success(AccountDeletionOutcome())
        }

        viewModel.deleteAccount(password)
        runCurrent()

        assertEquals(SettingsUiState.DeletingAccount, viewModel.uiState.value)

        advanceUntilIdle()
        assertEquals(SettingsUiState.AccountDeleted(AccountDeletionOutcome()), viewModel.uiState.value)
    }

    // =============================================================================
    // DISPLAY NAME
    // =============================================================================

    @Test
    fun `updateDisplayName success transitions to DisplayNameUpdated`() = runTest {
        val profile = ProfileResponse(displayName = "New Name")
        val response = Response.success(ApiResponse(success = true, data = profile, error = null))
        coEvery { tracksApi.updateProfile(UpdateTracksProfileRequest("New Name")) } returns response
        every { sessionManager.setDisplayName("New Name") } just Runs

        viewModel.updateDisplayName("New Name")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.DisplayNameUpdated)
        assertEquals("New Name", (state as SettingsUiState.DisplayNameUpdated).newName)
        verify { sessionManager.setDisplayName("New Name") }
    }

    @Test
    fun `updateDisplayName api-not-successful shows Error, in this app's words`() = runTest {
        val response = Response.success(ApiResponse<ProfileResponse>(success = false, data = null, error = "Rate limited"))
        coEvery { tracksApi.updateProfile(any()) } returns response

        viewModel.updateDisplayName("Anything")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Error)
        // Never the server's text.
        assertEquals("Couldn't change your name just now.", (state as SettingsUiState.Error).message)
    }

    @Test
    fun `updateDisplayName network exception shows Error`() = runTest {
        coEvery { tracksApi.updateProfile(any()) } throws java.io.IOException("Network down")

        viewModel.updateDisplayName("Any")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is SettingsUiState.Error)
        // The socket's words never show.
        assertEquals("Pingolin couldn't be reached. Check your connection and try again.", (state as SettingsUiState.Error).message)
    }

    @Test
    fun `fetchDisplayName updates sessionManager when server returns value`() = runTest {
        val profile = ProfileResponse(displayName = "ServerName")
        coEvery { tracksApi.getProfile() } returns Response.success(
            ApiResponse(success = true, data = profile, error = null)
        )
        every { sessionManager.setDisplayName(any()) } just Runs

        viewModel.fetchDisplayName()
        advanceUntilIdle()

        verify { sessionManager.setDisplayName("ServerName") }
    }

    @Test
    fun `fetchDisplayName does not overwrite local when server returns null`() = runTest {
        val profile = ProfileResponse(displayName = null)
        coEvery { tracksApi.getProfile() } returns Response.success(
            ApiResponse(success = true, data = profile, error = null)
        )

        viewModel.fetchDisplayName()
        advanceUntilIdle()

        verify(exactly = 0) { sessionManager.setDisplayName(any()) }
    }

    // =============================================================================
    // SUBSCRIPTION TIER
    // =============================================================================

    @Test
    fun `fetchSubscriptionTier mirrors the refreshed tier into screen state`() = runTest {
        coEvery { premiumStatusRefresher.refresh() } returns "Premium \u00b7 Circle"

        viewModel.fetchSubscriptionTier()
        advanceUntilIdle()

        assertEquals("Premium \u00b7 Circle", viewModel.subscriptionTier.value)
        coVerify(exactly = 1) { premiumStatusRefresher.refresh() }
    }

    @Test
    fun `fetchSubscriptionTier keeps cached tier when refresh is inconclusive`() = runTest {
        // null = offline / nothing determined; the cached label must survive.
        coEvery { premiumStatusRefresher.refresh() } returns null

        val before = viewModel.subscriptionTier.value
        viewModel.fetchSubscriptionTier()
        advanceUntilIdle()

        assertEquals(before, viewModel.subscriptionTier.value)
    }

    // =============================================================================
    // LOGOUT
    // =============================================================================

    @Test
    fun `logout calls authRepository and transitions to LoggedOut`() = runTest {
        coEvery { authRepository.logout() } returns Result.success(Unit)

        viewModel.logout()
        advanceUntilIdle()

        assertEquals(SettingsUiState.LoggedOut, viewModel.uiState.value)
        coVerify { authRepository.logout() }
    }

    // =============================================================================
    // ENVIRONMENT
    // =============================================================================

    @Test
    fun `environment is the build's, so a prod build never shows DEV`() {
        every { sessionManager.getBuildEnvironment() } returns SessionManager.Environment.PROD

        assertEquals(SessionManager.Environment.PROD, viewModel.environment)

        // Settings reads the environment from BuildConfig, not from a stored
        // preference: a preference seeded with a constant would make a prod
        // build display "DEV" while talking to production.
        verify { sessionManager.getBuildEnvironment() }
    }

    @Test
    fun `resetState returns to Idle`() = runTest {
        coEvery { authRepository.deleteAccount(any()) } returns Result.failure(Exception("err"))
        viewModel.deleteAccount("pwd".toCharArray())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is SettingsUiState.Error)

        viewModel.resetState()
        assertEquals(SettingsUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `userEmail delegates to session`() {
        every { sessionManager.getEmail() } returns "x@y.z"
        assertEquals("x@y.z", viewModel.userEmail)
    }

    @Test
    fun `userDisplayName delegates to session`() {
        every { sessionManager.getDisplayName() } returns "Alice"
        assertEquals("Alice", viewModel.userDisplayName)
    }
}
