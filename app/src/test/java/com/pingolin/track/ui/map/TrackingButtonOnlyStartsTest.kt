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

package com.pingolin.track.ui.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map's tracking button only ever starts tracking.
 *
 * Tracking runs whenever someone is signed in, and this button is hidden (fragment_map.xml keeps it
 * `gone`). A stop from it would not be recorded (MainViewModel.stopTracking writes nothing), so the
 * keep-alive would undo it at its next delivery, and nobody else could see it. The stops others can
 * see are per circle: a pause, the real-time switch, leaving.
 *
 * WHY A SOURCE TEST
 *   As [MapOffersNoLoadMoreTest]: instrumentation tests need a device, and a layout's button and a
 *   Fragment's click handler are not otherwise reachable from a JVM test.
 */
class TrackingButtonOnlyStartsTest {

    private val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()

    @Test
    fun `the map's tracking button starts tracking and never stops it`() {
        val handler = map.substring(map.indexOf("private fun startContinuousTracking()"))
            .let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(handler.contains("if (mainViewModel.isTracking.value) return\n        mainViewModel.startTracking()"))
        assertFalse(handler.contains("stopTracking()"))
        assertFalse(map.contains("\"Tracking stopped\""))
        assertFalse(map.contains("Stop continuous tracking"))
    }
}
