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

package com.pingolin.track.ui.places

import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Add Place" before a fix arrives, with no address picked, has no position until a fix or a pick
 * gives it one (never 0, 0, in the sea off West Africa), and Create says so and keeps the dialog,
 * with what was typed, instead of saving.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PlaceNeedsAPositionTest {

    private val fragment = File("src/main/java/com/pingolin/track/ui/places/PlacesFragment.kt").readText()

    @Test
    fun `no position until a fix or a pick, and never 0, 0`() {
        assertTrue(fragment.contains("var currentLat: Double? = null"))
        assertTrue(fragment.contains("var currentLng: Double? = null"))
        assertFalse(fragment.contains("var currentLat = 0.0"))
        assertFalse(fragment.contains("!= 0.0"))
    }

    @Test
    fun `Create without one says so and saves nothing`() {
        val create = fragment.substring(fragment.indexOf("dialogBinding.btnCreate.setOnClickListener {"))
        val guard = create.indexOf("if (latitude == null || longitude == null) {")
        val toast = create.indexOf("R.string.place_needs_a_position")
        val save = create.indexOf("viewModel.createPlace(")
        assertTrue(guard in 0 until toast && toast < save)
        assertTrue(create.substring(toast, save).contains("return@setOnClickListener"))
    }

    @Test
    fun `the words`() {
        assertEquals(
            "Search for the place's address first: Track doesn't have your location yet.",
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.place_needs_a_position),
        )
    }
}
