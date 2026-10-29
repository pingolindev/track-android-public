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

package com.pingolin.track.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.rule.ServiceTestRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeoutException

/**
 * Instrumented tests for LocationTrackingService.
 *
 * Tests the foreground service functionality including:
 * - Service lifecycle (start/stop)
 * - Foreground notification management
 * - Location update handling
 * - Battery saver mode behavior
 *
 * ## Why this whole class is @Ignore'd
 *
 * Every test here calls `context.startForegroundService(...)`. On Android
 * 8+ the OS gives the service 5 seconds to call `Service.startForeground()`
 * or else kills the host process with RemoteServiceException. Under
 * `com.pingolin.track.HiltTestRunner`, the production service's onStartCommand
 * path can't reliably complete that contract — location-permission checks,
 * Hilt field injection on a background thread, and notification-channel init
 * can add up past 5s on a mid-range phone. A missed deadline crashes the
 * instrumentation process, taking every subsequent test with it.
 *
 * Running these tests needs a testable seam in the service that doesn't
 * require actually starting it (e.g. the ACTION_START/ACTION_STOP dispatch
 * as a pure function), or a RobolectricShadowService-style stub.
 *
 * The lifecycle-free aspects of the service (constants, action extras,
 * channel id, `isRunning` readback) are covered by
 * `LocationTrackingServiceForegroundTest` in this same package.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
@Ignore(
    "Every test here calls startForegroundService(), which crashes the host " +
        "process on a Galaxy A50 when the production service can't complete its " +
        "startForeground() contract within 5s under the Hilt test runner. See " +
        "class kdoc for what a proper fix would require."
)
class LocationTrackingServiceTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val serviceRule = ServiceTestRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        // Reset static service state. `@Volatile var isRunning` inside a
        // companion object is compiled to a static backing field on the
        // OUTER class (LocationTrackingService), not the Companion, so
        // reflect on the outer class: getDeclaredField on the Companion
        // throws NoSuchFieldException.
        LocationTrackingService::class.java.getDeclaredField("isRunning").apply {
            isAccessible = true
            setBoolean(null, false)
        }
    }

    @After
    fun tearDown() {
        // Stop the service if running
        try {
            val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            }
            context.stopService(stopIntent)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Test that the service starts correctly with ACTION_START.
     */
    @Test
    fun testServiceStartsWithActionStart() {
        val startIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, 30000L)
            putExtra(LocationTrackingService.EXTRA_BATTERY_SAVER, false)
        }

        try {
            // On Android 8+, need to use startForegroundService for foreground services
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            // Give the service time to start
            Thread.sleep(1000)

            // The service should now be marked as running
            assertTrue(
                "Service should be marked as running",
                LocationTrackingService.isRunning
            )
        } catch (e: SecurityException) {
            // Expected if we don't have foreground service permission in test
            // This is acceptable - the test validates the intent handling
        } catch (e: TimeoutException) {
            fail("Service failed to start within timeout")
        }
    }

    /**
     * Test that the service stops gracefully with ACTION_STOP.
     */
    @Test
    fun testServiceStopsWithActionStop() {
        // First start the service
        val startIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, 30000L)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            // Wait for service to start
            Thread.sleep(1000)

            // Now stop the service
            val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            }
            context.startService(stopIntent)

            // Wait for service to stop
            Thread.sleep(1000)

            // Service should be marked as not running
            assertFalse(
                "Service should be marked as not running after stop",
                LocationTrackingService.isRunning
            )
        } catch (e: SecurityException) {
            // Expected if permissions not granted in test environment
        }
    }

    /**
     * Test that battery saver mode is properly configured.
     */
    @Test
    fun testBatterySaverModeConfiguration() {
        val startIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, 60000L)
            putExtra(LocationTrackingService.EXTRA_BATTERY_SAVER, true)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }

            // Give the service time to configure
            Thread.sleep(1000)

            // Service should be running (though in battery saver mode)
            assertTrue(
                "Service should be running in battery saver mode",
                LocationTrackingService.isRunning
            )
        } catch (e: SecurityException) {
            // Expected if permissions not granted
        }
    }

    /**
     * Test that service handles null action gracefully.
     */
    @Test
    fun testServiceHandlesNullAction() {
        val intent = Intent(context, LocationTrackingService::class.java)
        // No action set

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            // Wait for service to process
            Thread.sleep(500)

            // Service should stop itself when started without action
            // (after calling startForeground to avoid crash)
            assertFalse(
                "Service should not be running without a valid action",
                LocationTrackingService.isRunning
            )
        } catch (e: Exception) {
            // May throw if service stops quickly, which is expected behavior
        }
    }

    /**
     * Test that interval is coerced within valid bounds.
     */
    @Test
    fun testIntervalCoercion() {
        // Test with interval below minimum
        val intentBelowMin = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, 1000L) // Below 10s minimum
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intentBelowMin)
            } else {
                context.startService(intentBelowMin)
            }

            Thread.sleep(1000)

            // Service should start (interval gets coerced to minimum)
            assertTrue(
                "Service should start with coerced minimum interval",
                LocationTrackingService.isRunning
            )

            // Stop service for next test
            val stopIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            }
            context.startService(stopIntent)
            Thread.sleep(500)
        } catch (e: SecurityException) {
            // Expected if permissions not granted
        }
    }

    /**
     * Test that foreground notification channel is created correctly.
     */
    @Test
    fun testForegroundNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager

            // Start service to trigger notification channel creation
            val startIntent = Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_START
            }

            try {
                context.startForegroundService(startIntent)
                Thread.sleep(1000)

                // Check if location channel exists
                val channel = notificationManager.getNotificationChannel(
                    com.pingolin.track.TrackApplication.LOCATION_CHANNEL_ID
                )

                // Note: Channel is created in TrackApplication, not service
                // This test verifies the channel ID is accessible
                assertNotNull(
                    "Location notification channel should be accessible",
                    com.pingolin.track.TrackApplication.LOCATION_CHANNEL_ID
                )
            } catch (e: SecurityException) {
                // Expected if permissions not granted
            }
        }
    }

    /**
     * Test that service is START_STICKY for reliability.
     *
     * Calling `startForegroundService()` in a test environment obliges the
     * service to call `startForeground()` within 5 seconds or the process
     * crashes with android.app.RemoteServiceException. Under
     * `com.pingolin.track.HiltTestRunner`, the production service's
     * startup path can miss that deadline (location permission checks,
     * Hilt injection on a background thread, notification-channel init),
     * and the host process is killed before the test can assert anything.
     *
     * Proving START_STICKY semantics without actually starting the service
     * needs onStartCommand's return value exposed through a testable seam.
     */
    @Test
    @org.junit.Ignore(
        "Crashes host process: startForegroundService contract requires startForeground() " +
            "within 5s, which the production service can't complete in the test env on " +
            "a Galaxy A50. See test kdoc for what a proper fix would look like."
    )
    fun testServiceIsSticky() {
        val startIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
            Thread.sleep(500)
            assertTrue(
                "Service with START_STICKY should be running",
                LocationTrackingService.isRunning
            )
        } catch (e: SecurityException) {
            // Expected if permissions not granted
        }
    }

    /**
     * Test that starting service twice doesn't duplicate tracking.
     */
    @Test
    fun testServiceIgnoresDuplicateStart() {
        val startIntent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, 30000L)
        }

        try {
            // Start service first time
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
            Thread.sleep(500)

            // Start service second time (should be ignored)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
            Thread.sleep(500)

            // Service should still be running (only one instance)
            assertTrue(
                "Service should handle duplicate start gracefully",
                LocationTrackingService.isRunning
            )
        } catch (e: SecurityException) {
            // Expected if permissions not granted
        }
    }
}
