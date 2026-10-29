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
 * A circle gone from the server's list of ours is not told as "You were removed from {name}":
 * its owner may have deleted it, or this account may have left it on another device, and the
 * app can't tell which from the list. It says only what it knows; a deletion has the
 * server's own notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CircleGoneWordsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notice says only that the circle is gone from yours`() {
        assertEquals("You're no longer in Book Club", context.getString(R.string.circle_gone, "Book Club"))
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        assertTrue(main.contains("is com.pingolin.track.util.UxEvent.CircleGone ->\n                        getString(R.string.circle_gone, event.circleName)"))
        assertFalse("never a removal it can't know of", main.contains("You were removed from"))
    }
}
