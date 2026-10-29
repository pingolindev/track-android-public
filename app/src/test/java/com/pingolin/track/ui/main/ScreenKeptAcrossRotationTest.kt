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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rotation, font or theme change brings MainActivity back with its navigation host, its screens
 * and back stack restored by the fragment manager. The restored host is kept, and a new one is
 * made only on a first create: a new one on every create would drop the open screen for the map,
 * with the tab bar pointing at the earlier tab.
 */
class ScreenKeptAcrossRotationTest {

    @Test
    fun `the restored navigation host is kept, and only a first create makes one`() {
        val activity = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val setup = activity.substring(activity.indexOf("private fun setupNavigation()"))
            .let { it.substring(0, it.indexOf("navController = navHostFragment.navController")) }
        assertTrue(setup.contains("val restored = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment"))
        assertTrue(setup.contains("val navHostFragment = restored ?: NavHostFragment.create(R.navigation.nav_graph)"))
        assertTrue(setup.contains(".apply { if (restored == null) replace(R.id.nav_host_fragment, navHostFragment) }"))
        // The tab bar follows whatever screen is open, the restored one included.
        assertTrue(activity.contains("navController.addOnDestinationChangedListener { _, destination, _ ->"))
    }
}
