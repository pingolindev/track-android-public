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
 * Track's start while this phone's session store can't be opened: its own words in place of the
 * app, all four always shown, with no button: Track looks again by itself and opens what was
 * asked for once the store opens. Never the sign-in screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CantStartScreenTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the words, word for word, with no key talk`() {
        assertEquals("Track can't start right now", context.getString(R.string.cant_start_title))
        assertEquals("If you need help now, call or text someone directly, or contact your local emergency services.", context.getString(R.string.cant_start_help))
        assertEquals("Track keeps trying by itself. If this doesn't clear within a few minutes, restart your phone.", context.getString(R.string.cant_start_trying))
        assertEquals(
            "If it's still here after a restart, uninstall and reinstall Track, then sign in. Your circles, places and alert settings come back from your account. What was only on this phone is lost, including anything still waiting to be sent.",
            context.getString(R.string.cant_start_reinstall),
        )
        for (id in listOf(R.string.cant_start_title, R.string.cant_start_help, R.string.cant_start_trying, R.string.cant_start_reinstall)) {
            val words = context.getString(id).lowercase()
            for (word in listOf("key", "keystore", "passphrase", "storage", "database")) assertFalse("no key talk: $word", words.contains(word))
            assertFalse("Track tries again itself", words.contains("try again"))
        }
    }

    @Test
    fun `words only - no button, and all four always shown`() {
        val layout = File("src/main/res/layout/activity_cant_start.xml").readText()
        assertFalse(layout.contains("Button"))
        for (id in listOf("cant_start_title", "cant_start_help", "cant_start_trying", "cant_start_reinstall")) {
            assertTrue(id, layout.contains("android:text=\"@string/$id\""))
        }
        assertFalse("nothing hidden", layout.contains("android:visibility"))
    }

    @Test
    fun `it looks again by itself while on screen, and opens what was asked for - never sign-in`() {
        val screen = File("src/main/java/com/pingolin/track/ui/start/CantStartActivity.kt").readText()
        val loop = screen.substringAfter("repeatOnLifecycle(Lifecycle.State.STARTED) {").substringBefore("\n        }\n")
        assertTrue(loop.contains("if (sessionManager.storeRefusal() == null) {\n                        openAsked()"))
        assertTrue(loop.contains("delay(DatabaseUnavailable.retryAfterMs(attempt++))"))
        assertFalse(screen.contains("AuthActivity"))
        assertTrue("made again in a fresh task", screen.contains(".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)"))
        assertFalse("nothing injected that could need the Keystore", screen.contains("@AndroidEntryPoint"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".ui.start.CantStartActivity\"\n            android:exported=\"false\""))
    }
}
