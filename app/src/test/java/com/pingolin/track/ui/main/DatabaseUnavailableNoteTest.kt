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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The main screen's line while the database can't be opened: the screens look again by
 * themselves, so it asks nothing of the person ("Please try again." stays for an action of
 * theirs that failed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabaseUnavailableNoteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the words - Track keeps trying, and nothing is asked`() {
        val words = context.getString(R.string.database_unavailable_note)
        assertEquals("Track couldn't open what it had saved on this phone. It keeps trying by itself.", words)
        assertFalse(words.lowercase().contains("try again"))
        for (word in listOf("key", "keystore", "passphrase", "database")) assertFalse("no key talk: $word", words.lowercase().contains(word))
    }
}
