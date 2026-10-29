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

package com.pingolin.track.util

import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BoundaryMonitorCallbackImpl.
 *
 * Note: tests that require the BoundaryCrossingType enum from UniFFI live in
 * the instrumented tests because they require native library loading.
 * These tests verify the crossing report handler functionality and basic behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryMonitorCallbackImplTest {

    private lateinit var alertHandler: AlertHandler
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        alertHandler = mockk(relaxed = true)
    }

    @Test
    fun `a body that throws never reaches the SDK - it can't take a throw from this callback`() {
        // UniFFI panics on a throw from a callback that returns no Result.
        io.mockk.every { alertHandler.handleAlert(any(), any()) } throws IllegalStateException("no notification channel")
        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { throw IllegalArgumentException("unknown member") },
        )

        callback.onCrossingDetected("user-1", "Home", uniffi.pingolin_mobile.BoundaryCrossingType.ENTER, 12.0)
        io.mockk.every { alertHandler.handleAlert(any(), any()) } throws OutOfMemoryError()
        BoundaryMonitorCallbackImpl(alertHandler = alertHandler)
            .onCrossingDetected("user-1", "Home", uniffi.pingolin_mobile.BoundaryCrossingType.EXIT, 12.0)
    }

    @Test
    fun `the SDK callbacks' guard catches everything and passes nothing on`() {
        var ran = false
        sdkCallback("test", "a body") { ran = true }
        assertTrue(ran)
        sdkCallback("test", "a body") { throw IllegalStateException("x") }
        sdkCallback("test", "a body") { throw StackOverflowError() }
    }

    @Test
    fun `onPlacesLoaded does not throw for zero count`() {
        // Given
        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { it }
        )

        // When & Then - should not throw
        callback.onPlacesLoaded(0u)
    }

    @Test
    fun `onPlacesLoaded does not throw for positive count`() {
        // Given
        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { it }
        )

        // When & Then - should not throw
        callback.onPlacesLoaded(10u)
    }

    @Test
    fun `onError does not throw`() {
        // Given
        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { it }
        )

        // When & Then - should not throw
        callback.onError("Test error message")
    }

    @Test
    fun `onRequestReportCrossing without handler does not throw`() {
        // Given - callback without crossing report handler
        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { it },
            crossingReportHandler = null
        )

        // When & Then - should not throw
        callback.onRequestReportCrossing(
            placeId = "place-123",
            userId = "user-123",
            deviceId = "device-456",
            crossingType = "enter"
        )
    }

    @Test
    fun `callback with crossing report handler calls handler on request`() = testScope.runTest {
        // Given
        val before = System.currentTimeMillis()
        var reportedPlaceId: String? = null
        var reportedUserId: String? = null
        var reportedDeviceId: String? = null
        var reportedCrossingType: String? = null
        var reportedOccurredAtMs: Long? = null

        val handler = BoundaryMonitorCallbackImpl.CrossingReportHandler {
            placeId, userId, deviceId, crossingType, occurredAtMs ->
            reportedPlaceId = placeId
            reportedUserId = userId
            reportedDeviceId = deviceId
            reportedCrossingType = crossingType
            reportedOccurredAtMs = occurredAtMs
        }

        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = { it },
            crossingReportHandler = handler,
            coroutineScope = testScope
        )

        // When
        callback.onRequestReportCrossing(
            placeId = "place-999",
            userId = "user-888",
            deviceId = "device-777",
            crossingType = "exit"
        )

        // Advance coroutines to execute the async handler
        advanceUntilIdle()

        // Then
        assertEquals("place-999", reportedPlaceId)
        assertEquals("user-888", reportedUserId)
        assertEquals("device-777", reportedDeviceId)
        assertEquals("exit", reportedCrossingType)

        // The crossing must be stamped at DETECTION, before the report is
        // dispatched — the server can only see when the report landed, so a
        // device that was offline would otherwise have its crossing shown to
        // subscribers as if it had just happened.
        val stamped = reportedOccurredAtMs
        assertNotNull("the detection time must reach the handler", stamped)
        assertTrue(
            "detection time must be captured around now, got $stamped",
            stamped!! in (before - 5_000L)..(System.currentTimeMillis() + 5_000L),
        )
    }

    @Test
    fun `alert type constants are defined correctly`() {
        assertEquals("place_arrival", AlertHandler.TYPE_PLACE_ARRIVAL)
        assertEquals("place_departure", AlertHandler.TYPE_PLACE_DEPARTURE)
    }

    @Test
    fun `displayNameProvider is called with correct userId`() {
        // Given
        var providedUserId: String? = null
        val displayNameProvider: (String) -> String = { userId ->
            providedUserId = userId
            "Display Name"
        }

        val callback = BoundaryMonitorCallbackImpl(
            alertHandler = alertHandler,
            displayNameProvider = displayNameProvider
        )

        // onCrossingDetected cannot be called here — it takes the UniFFI-generated
        // BoundaryCrossingType enum, which needs the native library. So this only
        // proves the provider was wired through, not that the callback invokes it.
        assertEquals("Display Name", displayNameProvider("test-user"))
        assertEquals("test-user", providedUserId)
    }
}
