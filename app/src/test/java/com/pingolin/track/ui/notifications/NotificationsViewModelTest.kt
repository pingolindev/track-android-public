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

package com.pingolin.track.ui.notifications

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.domain.repository.NotificationRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for NotificationsViewModel.
 *
 * Tests cover:
 * - markAsRead forwards to repo
 * - markAllAsRead forwards to repo
 * - refreshNotifications toggles isRefreshing flag
 * - isRefreshing remains false when exceptions occur
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var notificationRepository: NotificationRepository

    private lateinit var viewModel: NotificationsViewModel

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        every { notificationRepository.observeAll() } returns flowOf(emptyList())
        coEvery { notificationRepository.refreshNotifications(any(), any()) } returns Result.success(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun `init triggers refreshNotifications`() = runTest {
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        coVerify { notificationRepository.refreshNotifications(any(), any()) }
    }

    @Test
    fun `markAsRead forwards to repository`() = runTest {
        coEvery { notificationRepository.markRead("n-1") } returns Result.success(Unit)
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        viewModel.markAsRead("n-1")
        advanceUntilIdle()

        coVerify { notificationRepository.markRead("n-1") }
    }

    @Test
    fun `markAllAsRead forwards to repository`() = runTest {
        coEvery { notificationRepository.markAllRead() } returns Result.success(Unit)
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        viewModel.markAllAsRead()
        advanceUntilIdle()

        coVerify { notificationRepository.markAllRead() }
    }

    @Test
    fun `markAsRead swallows exceptions`() = runTest {
        coEvery { notificationRepository.markRead(any()) } throws RuntimeException("boom")
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        // Should not throw
        viewModel.markAsRead("bad")
        advanceUntilIdle()
    }

    @Test
    fun `markAllAsRead swallows exceptions`() = runTest {
        coEvery { notificationRepository.markAllRead() } throws RuntimeException("boom")
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        viewModel.markAllAsRead()
        advanceUntilIdle()
    }

    @Test
    fun `deleteNotification forwards to repository`() = runTest {
        coEvery { notificationRepository.deleteNotification("n-9") } returns Result.success(Unit)
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        viewModel.deleteNotification("n-9")
        advanceUntilIdle()

        coVerify { notificationRepository.deleteNotification("n-9") }
    }

    @Test
    fun `deleteNotification swallows exceptions`() = runTest {
        coEvery { notificationRepository.deleteNotification(any()) } throws RuntimeException("boom")
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        // Should not throw — repo reverts the optimistic local delete itself.
        viewModel.deleteNotification("bad")
        advanceUntilIdle()
    }

    @Test
    fun `refreshNotifications sets isRefreshing true then false`() = runTest {
        coEvery { notificationRepository.refreshNotifications(any(), any()) } coAnswers {
            delay(100)
            Result.success(emptyList())
        }

        viewModel = NotificationsViewModel(notificationRepository)
        // After init but before the coroutine completes, isRefreshing should be true
        runCurrent()
        assertTrue(viewModel.isRefreshing.value)

        advanceUntilIdle()
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun `refreshNotifications resets isRefreshing on exception`() = runTest {
        coEvery { notificationRepository.refreshNotifications(any(), any()) } throws RuntimeException("fail")

        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun `explicit refreshNotifications call also toggles flag`() = runTest {
        viewModel = NotificationsViewModel(notificationRepository)
        advanceUntilIdle()

        coEvery { notificationRepository.refreshNotifications(any(), any()) } coAnswers {
            delay(100)
            Result.success(emptyList())
        }

        viewModel.refreshNotifications()
        runCurrent()
        assertTrue(viewModel.isRefreshing.value)

        advanceUntilIdle()
        assertFalse(viewModel.isRefreshing.value)
    }
}
