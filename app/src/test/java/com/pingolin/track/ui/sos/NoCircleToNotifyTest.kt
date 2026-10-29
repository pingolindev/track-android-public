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

package com.pingolin.track.ui.sos

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
 * The help button with no circle to raise in. While this phone can't read its circles, a press
 * can't reach anyone, and says so in safety words: get help another way, then try again. With
 * none to read otherwise (in no circle, or none read yet), its own words, true in each of those,
 * as trying again may not help. Both in a dialog that stays until OK, never a two-second toast.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NoCircleToNotifyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the words, word for word`() {
        assertEquals("Your circle wasn't notified", context.getString(R.string.sos_cant_notify_title))
        assertEquals(
            "Track can't notify anyone right now. If you need help now, call or text someone directly, or contact your local emergency services. Then try again.",
            context.getString(R.string.sos_cant_notify_now),
        )
        assertEquals(
            "Track has no circle to notify on this phone yet. If you need help now, call or text someone directly, or contact your local emergency services.",
            context.getString(R.string.sos_no_circle_to_notify),
        )
        for (id in listOf(R.string.sos_cant_notify_title, R.string.sos_cant_notify_now, R.string.sos_no_circle_to_notify)) {
            val words = context.getString(id).lowercase()
            for (word in listOf("key", "keystore", "passphrase", "database", "saved")) assertFalse("no storage talk: $word", words.contains(word))
        }
    }

    @Test
    fun `can't read the circles, or in none - each its own words, in a dialog that stays until OK`() {
        val press = File("src/main/java/com/pingolin/track/ui/sos/SosFragment.kt").readText()
            .substringAfter("private fun showCircleSelectionDialog()").substringBefore("\n    }\n")
        val none = press.substringAfter("if (circles.isEmpty()) {").substringBefore("            return\n")
        // The two cases apart: only a database that can't be read says "try again".
        assertTrue(none.contains("val unreadable = com.pingolin.track.util.DatabaseUnavailable.failure.value != null"))
        assertTrue(none.contains(".apply { if (unreadable) setTitle(R.string.sos_cant_notify_title) }"))
        assertTrue(none.contains(".setMessage(if (unreadable) R.string.sos_cant_notify_now else R.string.sos_no_circle_to_notify)"))
        assertTrue("stays until OK", none.contains(".setCancelable(false)"))
        assertTrue(none.contains(".setPositiveButton(android.R.string.ok, null)"))
        // Never the toast, nor the storage line.
        assertFalse(none.contains("Toast"))
        assertFalse(press.contains("No circles available"))
        assertFalse(press.contains("DATABASE_KEY_REFUSED_TEXT"))
    }
}
