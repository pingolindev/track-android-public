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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * App Lock: an older build's biometric lock with no PIN, on a phone whose biometrics have stopped
 * working and that has no screen lock, would open with nothing checked, every time, in silence.
 * Nothing stored can check the person, so it still opens; but it then asks, until done, for a PIN or
 * for the lock to be off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BrokenLockAsksToBeMendedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the words`() {
        assertEquals("Set a PIN for App Lock", context.getString(R.string.app_lock_mend_title))
        assertEquals(
            "This phone can't unlock Track with a fingerprint or face any more, and there's no PIN to fall back on. Set a PIN to keep Track locked, or turn App Lock off.",
            context.getString(R.string.app_lock_mend_body),
        )
        assertEquals("Set PIN", context.getString(R.string.app_lock_mend_set_pin))
        assertEquals("Turn off App Lock", context.getString(R.string.app_lock_mend_turn_off))
    }

    @Test
    fun `it opens, then asks - Set PIN goes to Settings' App Lock, Turn off turns it off, and nothing dismisses it`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val fallback = main.substringAfter("private fun showDeviceCredentialPrompt() {").substringBefore("\n    }\n")
        assertTrue(fallback.contains("isDeviceSecure != true) {\n            onUnlockSuccess()\n"))
        assertTrue(fallback.substringBefore("return\n").contains("askToMendTheLock()"))
        val ask = main.substringAfter("private fun askToMendTheLock() {").substringBefore("\n    }\n")
        assertTrue(ask.contains(".setCancelable(false)"))
        assertTrue(ask.contains("navBar.selectedItemId = R.id.settingsFragment"))
        assertTrue(ask.contains("sessionManager.clearAppLockPinHash()\n                sessionManager.setAppLockMethod(SessionManager.APP_LOCK_NONE)"))
    }
}
