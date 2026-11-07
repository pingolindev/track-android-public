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

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.ui.main.MainViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Changing a tracking setting never stops sharing. Battery Saver, its interval, the update interval
 * and the distance switches each sent a stop and then a start. Both reached the service before it
 * handled the stop, whose stopSelf() took the service down just after the start had run, and nothing
 * was shared until the heartbeat brought it back: ten minutes on a test phone, for every change. The
 * running service now re-reads its settings in place.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SettingsChangeKeepsSharingTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        serviceRunning(false)
    }

    @After
    fun tearDown() {
        serviceRunning(false)
        Dispatchers.resetMain()
    }

    /** The service's own flag, set only by the service. */
    private fun serviceRunning(running: Boolean) =
        LocationTrackingService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, running)

    private fun main() = MainViewModel(
        context = context,
        circleRepository = mockk(relaxed = true),
        locationRepository = mockk(relaxed = true),
        sosRepository = mockk(relaxed = true),
        configRepository = mockk(relaxed = true),
        geocodingRepository = mockk(relaxed = true),
        notificationRepository = mockk(relaxed = true),
        tracksApi = mockk(relaxed = true),
        sessionManager = mockk(relaxed = true),
        destinationRepository = mockk(relaxed = true),
        hiddenDestinations = mockk(relaxed = true),
    )

    /** What reached the service, in order: plain starts and foreground starts alike. */
    private fun sent(): List<String?> =
        generateSequence { shadowOf(context as Application).nextStartedService }.map { it.action }.toList()

    @Test
    fun `a setting changed while sharing reaches the running service, and nothing stops it`() {
        serviceRunning(true)
        main().applyTrackingSettings()
        assertEquals(listOf(LocationTrackingService.ACTION_UPDATE_SETTINGS), sent())
    }

    @Test
    fun `the pair Settings sent before began with a stop`() {
        // The control: this record does show a stop, so the test above can't pass by missing one.
        serviceRunning(true)
        val vm = main()
        vm.stopTracking()
        vm.startTracking()
        assertEquals(listOf(LocationTrackingService.ACTION_STOP, LocationTrackingService.ACTION_START), sent())
    }

    @Test
    fun `with the service gone, a setting change starts sharing`() {
        main().applyTrackingSettings()
        assertEquals(listOf(LocationTrackingService.ACTION_START), sent())
    }

    @Test
    fun `no Settings control stops the service to apply a change`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertFalse(settings.contains("mainViewModel.stopTracking()"))
        assertEquals(
            "time-based, update interval, distance on and its threshold, Battery Saver and its interval",
            6,
            Regex("""mainViewModel\.applyTrackingSettings\(\)""").findAll(settings).count(),
        )
    }
}
