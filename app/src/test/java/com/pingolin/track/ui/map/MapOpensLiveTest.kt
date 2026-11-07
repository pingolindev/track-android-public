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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map's view ends any history range before it observes the model
 * (MapViewModel.onMapViewCreated). A Fragment can't be reached from a JVM test here,
 * so the call and its place are checked from the source: after [observeViewModel] the
 * first paint would already have been skipped.
 */
class MapOpensLiveTest {

    private val fragment = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()

    @Test
    fun `the view ends a history range before anything observes the model`() {
        val created = fragment.substringAfter("override fun onViewCreated(").substringBefore("private fun applyStatusBarInsets")
        val ends = created.indexOf("viewModel.onMapViewCreated()")
        assertTrue("onViewCreated no longer opens the map live", ends >= 0)
        assertTrue("it has to come before setupMap and observeViewModel", ends < created.indexOf("setupMap()"))
    }
}
