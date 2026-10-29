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

package com.pingolin.track.ui.start

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.util.DatabaseKeyRefusedException
import com.pingolin.track.util.DatabaseUnavailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * An empty list while the database can't be opened shows nothing because nothing could be read. Its
 * empty state says so, in place of its own words ("No circles yet"), and its create button hides:
 * "Create a circle" then could make a second one. Otherwise Circles, Places, the bell and the Alerts
 * lists would read as fact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class UnreadableEmptyStateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val refused = DatabaseKeyRefusedException(java.security.KeyStoreException("busy"))

    private lateinit var emptyState: LinearLayout
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var steps: TextView
    private val create = View(context)

    @Before
    fun setup() {
        DatabaseUnavailable.reset()
        context.setTheme(R.style.Theme_Track)
        emptyState = LinearLayout(context)
        icon = ImageView(context)
        title = TextView(context).apply { text = "No circles yet" }
        steps = TextView(context).apply { text = "1. Create a circle"; visibility = View.GONE }
        listOf(icon, title, steps).forEach { emptyState.addView(it) }
    }

    @After
    fun tearDown() = DatabaseUnavailable.reset()

    private fun shownWords() = emptyState.children.filter { it.visibility == View.VISIBLE }
        .filterIsInstance<TextView>().map { it.text.toString() }.toList()

    @Test
    fun `can't be read - the line in place of its words, and no create button`() {
        DatabaseUnavailable.report(refused)
        UnreadableEmptyState.show(emptyState, empty = true, create)
        assertTrue(emptyState.visibility == View.VISIBLE)
        assertEquals(listOf("Track couldn't open what it had saved on this phone. It keeps trying by itself."), shownWords())
        assertEquals(View.GONE, icon.visibility)
        assertEquals(View.GONE, create.visibility)
        // Shown again while still unreadable: one line, never two.
        UnreadableEmptyState.show(emptyState, empty = true, create)
        assertEquals(1, shownWords().size)
    }

    @Test
    fun `once the database opens - its own words and its create button, as they were`() {
        DatabaseUnavailable.report(refused)
        UnreadableEmptyState.show(emptyState, empty = true, create)
        DatabaseUnavailable.opened()
        UnreadableEmptyState.show(emptyState, empty = true, create)
        assertEquals(listOf("No circles yet"), shownWords())
        assertEquals(View.VISIBLE, icon.visibility)
        assertEquals("a part its screen kept hidden stays hidden", View.GONE, steps.visibility)
        assertEquals(View.VISIBLE, create.visibility)
        assertEquals(3, emptyState.childCount)
    }

    @Test
    fun `a create button its screen hid stays hidden, and a list with rows shows no empty state`() {
        create.visibility = View.GONE
        DatabaseUnavailable.report(refused)
        UnreadableEmptyState.show(emptyState, empty = false, create)
        assertFalse(emptyState.visibility == View.VISIBLE)
        DatabaseUnavailable.opened()
        UnreadableEmptyState.show(emptyState, empty = false, create)
        assertEquals("never shown by this", View.GONE, create.visibility)
    }

    @Test
    fun `every list screen's empty state goes through it, with its create button`() {
        val ui = "src/main/java/com/pingolin/track/ui"
        val screens = mapOf(
            "circles/CirclesFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, show, binding.fabCreateCircle)",
            "places/PlacesFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreatePlace)",
            "notifications/NotificationsFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, notifications.isEmpty())",
            "alerts/AlertsFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreateRule)",
            "alerts/history/AlertEventsFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, events.isEmpty())",
            "alerts/inactivity/InactivityAlertsFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, alerts.isEmpty(), binding.fabCreateAlert)",
            "checkin/CheckInFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, empty)",
            "destinations/DestinationsFragment.kt" to "UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreateDestination)",
        )
        for ((file, call) in screens) {
            val source = File("$ui/$file").readText()
            assertTrue(file, source.contains(call))
            assertFalse("$file sets its empty state itself", source.contains("binding.emptyState.visibility ="))
        }
        // Check-ins' "Select a circle", shown when there are no circles, the same way.
        val checkIns = File("$ui/checkin/CheckInFragment.kt").readText()
        assertTrue(checkIns.contains("UnreadableEmptyState.show(binding.noCircleState, true)"))
        assertFalse(checkIns.contains("binding.noCircleState.visibility ="))
    }
}
