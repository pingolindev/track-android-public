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

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * No tap to repair what the app does by itself: Security details has no "Repair my other devices"
 * button asking people to diagnose a sync problem, because the check runs by itself
 * (LocationRepository.verifySelfKeySlots, on the personal key's own path, and at once when the
 * server asks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NoRepairButtonTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> view.children.flatMap { texts(it).asSequence() }.toList()
        else -> emptyList()
    }

    @Test
    fun `Security details offer no repair, and the Settings row promises none`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val page = LayoutInflater.from(activity).inflate(R.layout.fragment_my_keys, null)
        assertFalse(texts(page).any { it.contains("repair", ignoreCase = true) })
        assertEquals("What other members compare to confirm it's you", context.getString(R.string.my_keys_row_description))

        val words = File("src/main/res/values/strings.xml").readText() + File("src/main/res/layout/fragment_my_keys.xml").readText()
        for (gone in listOf("Repair my other devices", "repair your other devices", "my_keys_personal_", "btnRepublishSelfKey")) {
            assertFalse(gone, words.contains(gone, ignoreCase = true))
        }
    }

    @Test
    fun `the check the button ran still runs by itself`() {
        val locations = File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        // On the personal key's own path, every time it's made sure of.
        assertTrue(locations.contains("                migratePersonalKeyToNilUuid()\n            }\n            verifySelfKeySlots()\n            return true"))
        // And at once when the server asks a device for the copy.
        assertTrue(locations.contains("if (bundle.republishRequested"))
    }
}
