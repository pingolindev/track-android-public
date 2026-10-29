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

package com.pingolin.track.ui.main

import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The first circle someone joins becomes their default on the map, once. A guard of
 * `getDefaultCircleId() == null` is not enough: that is also what "Default on map" switched off
 * leaves, so the next change to the circle list would make the first circle the default again, and
 * move the map onto it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCircleSwitchedOffStaysOffTest {

    private val circles = MutableStateFlow<List<CircleEntity>>(emptyList())

    private fun circle(id: String) = CircleEntity(id = id, name = id, encryptedKey = null, createdAt = 0, updatedAt = 0)

    private fun main(session: SessionManager) = MainViewModel(
        context = mockk(relaxed = true),
        circleRepository = mockk<CircleRepository>(relaxed = true) { every { observeCircles() } returns circles },
        locationRepository = mockk(relaxed = true),
        sosRepository = mockk(relaxed = true),
        configRepository = mockk(relaxed = true),
        geocodingRepository = mockk(relaxed = true),
        notificationRepository = mockk(relaxed = true),
        tracksApi = mockk(relaxed = true),
        sessionManager = session,
        destinationRepository = mockk(relaxed = true),
        hiddenDestinations = mockk(relaxed = true),
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a default switched off stays off when the circle list changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = mockk<SessionManager>(relaxed = true) {
            every { getDefaultCircleId() } returns null
            every { isDefaultCircleSettled() } returns true
        }
        val main = main(session)
        try {
            main.initialize()
            backgroundScope.launch { main.selectedCircle.collect {} }
            circles.value = listOf(circle("c-1"), circle("c-2"))
            advanceUntilIdle()

            assertNull("the map stays where the person left it", main.selectedCircleId.value)
            verify(exactly = 0) { session.setDefaultCircleId(any()) }
        } finally {
            main.viewModelScope.cancel()
        }
    }

    @Test
    fun `the first circle someone ever joins still becomes the default, so the test above can't pass for another reason`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = mockk<SessionManager>(relaxed = true) {
            every { getDefaultCircleId() } returns null
            every { isDefaultCircleSettled() } returns false
        }
        val main = main(session)
        try {
            main.initialize()
            circles.value = listOf(circle("c-1"), circle("c-2"))
            advanceUntilIdle()

            assertEquals("c-1", main.selectedCircleId.value)
            verify(exactly = 1) { session.setDefaultCircleId("c-1") }
        } finally {
            main.viewModelScope.cancel()
        }
    }
}
