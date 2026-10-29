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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The launch's account-id check: a saved account id that isn't a UUID is no account, so the
 * session ends to the sign-in screen, the stop recorded as a sync error, right after the key
 * store's own check and before anything else of the launch reads the account. MainActivity needs
 * Hilt to run, so its onCreate is read as source (as BackgroundLoopsAreLifecycleBoundTest reads
 * it); SessionManagerTest covers the check itself.
 */
class AccountIdLaunchCheckTest {

    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (root in listOf(File(dir, "app/src/main/java/com/pingolin/track"), File(dir, "src/main/java/com/pingolin/track"))) {
                val file = File(root, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not locate $relative from ${File("").absolutePath}")
    }

    @Test
    fun `a saved account id that isn't a UUID ends the session at launch, after the key store's check`() {
        val text = source("ui/main/MainActivity.kt")
        val onCreate = text.substring(text.indexOf("override fun onCreate("))
        val keyStore = onCreate.indexOf("keyStoreRecovery.check()")
        val check = onCreate.indexOf("if (sessionManager.savedAccountIdInvalid()) {")
        val appLock = onCreate.indexOf("sessionManager.isAppLockEnabled()")
        assertTrue("the key store's check, then this one, then the rest of the launch", keyStore in 0 until check && check < appLock)
        val block = onCreate.substring(check, onCreate.indexOf("\n        }", check))
        for (step in listOf(
            "sessionManager.triggerForceLogout(com.pingolin.track.util.TrackingStopReason.ACCOUNT_ID_INVALID,",
            "startActivity(Intent(this, AuthActivity::class.java))",
            "finish()",
            "return",
        )) {
            assertTrue("the block ends the session: $step", step in block)
        }
        // Recorded as a key store that doesn't read is: a sync error.
        assertEquals("sync error", com.pingolin.track.util.TrackingJournalFormatter.reasonText(com.pingolin.track.util.TrackingStopReason.ACCOUNT_ID_INVALID))
    }
}
