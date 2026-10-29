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
 * Deleting an account deletes the circles it owns, for everyone in them, while the ones you merely
 * belong to stay. So the delete warning says plainly, before the button, that the circles you own end
 * for all their members, and how to keep one going, rather than listing "circles" among "your data".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DeleteSaysOwnedCirclesEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the words are Track's`() {
        assertEquals(
            "Circles you own are deleted for everyone in them, along with all the location history they hold. To keep a circle going, transfer its ownership first.",
            context.getString(R.string.delete_account_owned_circles),
        )
    }

    @Test
    fun `the delete warning says it before the button, after what the account loses`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val warning = settings.substringAfter("private fun showDeleteAccountConfirmation(").substringBefore("\n    private fun ")
        // And the line before it doesn't say "circles" go: only the ones you
        // own do.
        val data = warning.indexOf("All your data, including your location history and settings, will be permanently deleted.")
        assertFalse(warning.contains("All your data including location history, circles, and settings"))
        val circles = warning.indexOf("\"\\n\\n\" + getString(R.string.delete_account_owned_circles) +")
        assertTrue("in the warning", circles > 0)
        assertTrue("after what the account loses", data in 0 until circles)
        assertTrue("in the message, before the button", circles < warning.indexOf(".setPositiveButton(\"Delete\")"))
        assertTrue(warning.indexOf(".setMessage(") < data)
    }
}
