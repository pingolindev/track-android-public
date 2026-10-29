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

package com.pingolin.track.ui.alerts.rules

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CircleRepository
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
 * Unit tests for AlertRulesViewModel.
 *
 * Tests cover:
 * - createLowBatteryAlert (threshold, success, error)
 * - toggleRule (activate/deactivate)
 * - deleteRule (success + error)
 * - selectCircle refreshes members
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertRulesViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var alertRuleRepository: AlertRuleRepository

    @MockK
    private lateinit var circleRepository: CircleRepository

    private lateinit var viewModel: AlertRulesViewModel

    private val testCircle = CircleEntity(
        id = "c-1", name = "Test", encryptedKey = null,
        createdAt = 0, updatedAt = 0, isOwner = true,
        keyRotationNeeded = false, keyVersion = 1
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { alertRuleRepository.observeAll() } returns flowOf(emptyList())
        every { circleRepository.observeCircles() } returns flowOf(emptyList())
        // loadCircleMembers reads local members first, before the network
        // refresh; stub the local read.
        every { circleRepository.observeMembers(any()) } returns flowOf(emptyList())
        coEvery { alertRuleRepository.refreshAlertRules() } returns Result.success(emptyList())

        viewModel = AlertRulesViewModel(alertRuleRepository, circleRepository, com.pingolin.track.testutil.quietPendingChanges())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // =============================================================================
    // INIT
    // =============================================================================

    @Test
    fun `init triggers refreshAlertRules`() = runTest {
        advanceUntilIdle()
        coVerify { alertRuleRepository.refreshAlertRules() }
    }

    // =============================================================================
    // LOW BATTERY
    // =============================================================================

    @Test
    fun `createLowBatteryAlert success transitions to RuleCreated`() = runTest {
        coEvery {
            alertRuleRepository.createLowBatteryAlert(
                circleId = "c-1",
                targetUserId = "u-1",
                threshold = 15,
                notifyPush = true,
                notifyEmail = false,
                keepIfBehind = true,
            )
        } returns Result.success("rule-1")

        viewModel.createLowBatteryAlert("c-1", "u-1", 15)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AlertRulesUiState.RuleCreated)
        assertEquals("rule-1", (state as AlertRulesUiState.RuleCreated).ruleId)
    }

    /**
     * A low-battery rule has no e-mail (it is judged on the watcher's phone, so the server never
     * learns who watches whom). Saving an edit asks for none, and clears the flag an older app
     * may have stored.
     */
    @Test
    fun `editing a battery rule never asks for an email`() = runTest {
        coEvery {
            alertRuleRepository.updateAlertRule(
                ruleId = "rule-1", notifyPush = true, notifyEmail = false,
                conditions = mapOf("threshold" to 25), keepIfBehind = true,
            )
        } returns Result.success(Unit)

        viewModel.updateBatteryRule("rule-1", threshold = 25, notifyPush = true)
        advanceUntilIdle()

        coVerify {
            alertRuleRepository.updateAlertRule(
                ruleId = "rule-1", notifyPush = true, notifyEmail = false,
                conditions = mapOf("threshold" to 25), keepIfBehind = true,
            )
        }
        assertTrue(viewModel.uiState.value is AlertRulesUiState.RuleUpdated)
    }

    /**
     * A rule stored with e-mail and no push notifies (the server wakes the
     * phones for either flag). The editor shows it with Push on, and its save keeps it notifying:
     * push true, e-mail false — never both false.
     */
    @Test
    fun `an e-mail-only battery rule opened and saved notifies by push, not email`() = runTest {
        coEvery {
            alertRuleRepository.updateAlertRule(
                ruleId = "rule-1", notifyPush = true, notifyEmail = false,
                conditions = mapOf("threshold" to 20), keepIfBehind = true,
            )
        } returns Result.success(Unit)

        val push = com.pingolin.track.ui.alerts.RuleChannels.pushShown(notifyPush = false, notifyEmail = true)
        viewModel.updateBatteryRule("rule-1", threshold = 20, notifyPush = push)
        advanceUntilIdle()

        coVerify {
            alertRuleRepository.updateAlertRule(
                ruleId = "rule-1", notifyPush = true, notifyEmail = false,
                conditions = mapOf("threshold" to 20), keepIfBehind = true,
            )
        }
    }

    @Test
    fun `createLowBatteryAlert failure transitions to Error`() = runTest {
        coEvery {
            alertRuleRepository.createLowBatteryAlert(any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(Exception("Network fail"))

        viewModel.createLowBatteryAlert("c-1", "u-1", 20)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AlertRulesUiState.Error)
        assertEquals("Network fail", (state as AlertRulesUiState.Error).message)
    }

    @Test
    fun `createLowBatteryAlert default threshold is 20`() = runTest {
        coEvery {
            alertRuleRepository.createLowBatteryAlert(
                circleId = "c-1", targetUserId = "u-1",
                threshold = 20, notifyPush = true, notifyEmail = false, keepIfBehind = true,
            )
        } returns Result.success("r-1")

        viewModel.createLowBatteryAlert("c-1", "u-1")
        advanceUntilIdle()

        coVerify {
            alertRuleRepository.createLowBatteryAlert(
                circleId = "c-1", targetUserId = "u-1",
                threshold = 20, notifyPush = true, notifyEmail = false, keepIfBehind = true,
            )
        }
    }




    // =============================================================================
    // TOGGLE
    // =============================================================================

    @Test
    fun `toggleRule success transitions to RuleUpdated`() = runTest {
        coEvery {
            alertRuleRepository.updateAlertRule(ruleId = "r-1", isActive = false)
        } returns Result.success(Unit)

        viewModel.toggleRule("r-1", isActive = false)
        advanceUntilIdle()

        assertEquals(AlertRulesUiState.RuleUpdated, viewModel.uiState.value)
    }

    @Test
    fun `toggleRule failure transitions to Error`() = runTest {
        coEvery { alertRuleRepository.updateAlertRule(any(), any()) } returns Result.failure(Exception("err"))

        viewModel.toggleRule("r-1", true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AlertRulesUiState.Error)
    }

    // =============================================================================
    // DELETE
    // =============================================================================

    @Test
    fun `deleteRule success transitions to RuleDeleted`() = runTest {
        coEvery { alertRuleRepository.deleteAlertRule("r-1") } returns Result.success(Unit)

        viewModel.deleteRule("r-1")
        advanceUntilIdle()

        assertEquals(AlertRulesUiState.RuleDeleted, viewModel.uiState.value)
    }

    @Test
    fun `deleteRule failure transitions to Error`() = runTest {
        coEvery { alertRuleRepository.deleteAlertRule(any()) } returns Result.failure(Exception("notfound"))

        viewModel.deleteRule("r-1")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AlertRulesUiState.Error)
    }

    // =============================================================================
    // CIRCLE SELECTION
    // =============================================================================

    @Test
    fun `selectCircle stores circle and refreshes members`() = runTest {
        coEvery { circleRepository.refreshMembers("c-1") } returns Result.success(emptyList())

        viewModel.selectCircle(testCircle)
        advanceUntilIdle()

        assertEquals(testCircle, viewModel.selectedCircle.value)
        coVerify { circleRepository.refreshMembers("c-1") }
    }

    // =============================================================================
    // REFRESH STATE
    // =============================================================================

    @Test
    fun `refreshAlertRules failure transitions to Error`() = runTest {
        coEvery { alertRuleRepository.refreshAlertRules() } returns Result.failure(Exception("bad"))

        viewModel.refreshAlertRules()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AlertRulesUiState.Error)
        assertEquals("bad", (state as AlertRulesUiState.Error).message)
    }

    @Test
    fun `resetState returns to Idle`() = runTest {
        coEvery { alertRuleRepository.deleteAlertRule(any()) } returns Result.failure(Exception("x"))
        viewModel.deleteRule("r-1")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is AlertRulesUiState.Error)

        viewModel.resetState()
        assertEquals(AlertRulesUiState.Idle, viewModel.uiState.value)
    }

    // =============================================================================
    // ALERT EVENTS
    // =============================================================================

    @Test
    fun `loadAlertEvents fills alertEvents on success`() = runTest {
        coEvery { alertRuleRepository.getAlertEvents() } returns Result.success(emptyList())

        viewModel.loadAlertEvents()
        advanceUntilIdle()

        assertTrue(viewModel.alertEvents.value.isEmpty())
        coVerify { alertRuleRepository.getAlertEvents() }
    }
}
