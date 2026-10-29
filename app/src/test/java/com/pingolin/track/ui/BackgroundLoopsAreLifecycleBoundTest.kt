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

package com.pingolin.track.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screen-owned loops must stop when the screen stops.
 *
 * The foreground location service keeps this app's process — and so its
 * activity, fragments and activity-scoped ViewModels — alive in the background.
 * A loop bound to a VIEW's lifetime rather than its VISIBILITY therefore runs
 * on after the user leaves the app, for as long as the process lives. These pin
 * each such loop to its screen's visibility.
 *
 * WHY A SOURCE TEST
 *   Fragment and activity lifecycles are not reachable from a JVM test here, and
 *   what makes each of these wrong is a fact about the source: which scope a
 *   loop is launched on.
 */
class BackgroundLoopsAreLifecycleBoundTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (root in listOf(File(dir, "app/src/main/java/com/pingolin/track"), File(dir, "src/main/java/com/pingolin/track"))) {
                val file = File(root, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate $relative from ${File("").absolutePath}. Fix this lookup " +
                "rather than deleting the test."
        )
    }

    /** The body of [functionName], up to the next top-level member of the class. */
    private fun body(text: String, functionName: String): String {
        val start = text.indexOf("fun $functionName(")
        assertTrue("expected to find fun $functionName", start >= 0)
        val next = Regex("\n    (override |private |internal |public )?fun ").find(text, start + 1)
        return text.substring(start, next?.range?.first ?: text.length)
    }

    @Test
    fun `the Circles tab polls invites only while resumed`() {
        val fragment = source("ui/circles/CirclesFragment.kt")
        assertFalse(
            "CirclesFragment must not launch a loop from onResume: that loop is bound to the " +
                "VIEW's lifetime, survives backgrounding, and each resume stacks another.",
            fragment.contains("override fun onResume(")
        )
        val poll = body(fragment, "pollInvitesWhileResumed")
        assertTrue(poll.contains("repeatOnLifecycle(Lifecycle.State.RESUMED)"))
    }

    @Test
    fun `the map follows the selected circle only while started`() {
        val fragment = source("ui/map/MapFragment.kt")
        val observe = body(fragment, "observeViewModel")
        val collector = observe.indexOf("mainViewModel.selectedCircle")
        assertTrue("expected the selectedCircle collector in observeViewModel", collector >= 0)
        val scoped = observe.lastIndexOf("repeatOnLifecycle(Lifecycle.State.STARTED)", collector)
        assertTrue(
            "The selectedCircle collector must run inside repeatOnLifecycle(STARTED): unscoped, " +
                "every background circles refresh restarts the 30 s poll.",
            scoped >= 0 && observe.substring(scoped, collector).count { it == '}' } == 0
        )
    }

    @Test
    fun `the map marks itself hidden in onStop, and the SOS halo stops with it`() {
        val fragment = source("ui/map/MapFragment.kt")
        val onStop = body(fragment, "onStop")
        assertTrue(onStop.contains("viewModel.onMapVisibilityChanged(false)"))
        assertTrue("the SOS pulse redraws the map every frame", onStop.contains("sosPulseAnimator?.pause()"))
        assertTrue(body(fragment, "onDestroyView").contains("sosPulseAnimator?.cancel()"))
        assertTrue(body(fragment, "onStart").contains("viewModel.onMapVisibilityChanged(true)"))
    }

    @Test
    fun `the SOS badge and Safety list stop their minute ticker when hidden`() {
        val activity = body(source("ui/main/MainActivity.kt"), "observeViewModel")
        val alerts = activity.indexOf("viewModel.activeAlerts")
        assertTrue(alerts >= 0)
        val scoped = activity.indexOf(".flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)", alerts)
        val collected = activity.indexOf(".collectLatest", alerts)
        assertTrue(
            "MainActivity's activeAlerts collector must be STARTED-scoped",
            scoped in (alerts + 1) until collected
        )
        val sos = body(source("ui/sos/SosFragment.kt"), "observeViewModel")
        val rows = sos.indexOf("viewModel.alertRows")
        assertTrue(rows >= 0)
        assertTrue(sos.lastIndexOf("repeatOnLifecycle(Lifecycle.State.STARTED)", rows) >= 0)
    }
}
