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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The saver switch says what it does. It read "Batch Uploads, Queue locations and sync
 * periodically" while the SDK's batching stayed off (LocationRepository's PrivacyConfig):
 * what it turns on is the flat, coarser poll of battery saver, which the tracking
 * notification already calls by that name.
 */
class BatterySaverSwitchTest {

    private val layout = File("src/main/res/layout/fragment_settings.xml").readText()
    private val fragment = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()

    @Test
    fun `the saver switch is named Battery Saver`() {
        val title = Regex("""android:labelFor="@id/switchBatterySaver"[^>]*?android:text="([^"]+)"""")
            .find(layout)?.groupValues?.get(1)
        assertEquals("Battery Saver", title)
    }

    @Test
    fun `the name appears once, on the switch, not again above it`() {
        assertEquals(1, Regex("""android:text="Battery Saver"""").findAll(layout).count())
    }

    @Test
    fun `its interval is named for what it sets`() {
        val heading = Regex(
            """android:id="@\+id/layoutBatterySaverInterval"[\s\S]*?android:text="([^"]+)"""",
        ).find(layout)?.groupValues?.get(1)
        assertEquals("Update Interval", heading)
    }

    @Test
    fun `nothing the screen says promises batching`() {
        for (words in listOf("Batch Uploads", "Batch Interval", "Queue locations", "will be queued")) {
            assertFalse("the layout still says \"$words\"", layout.contains(words))
            assertFalse("SettingsFragment still says \"$words\"", fragment.contains(words))
        }
    }
}
