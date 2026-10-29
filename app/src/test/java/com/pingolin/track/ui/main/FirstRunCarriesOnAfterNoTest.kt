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
 * A no to Android's location question, asked from the first-run chain, is that chain's "Not now".
 * The question doesn't come back at every opening (the map's "Location is off" line and the
 * actions that share location still ask), and the rest of first-run setup carries on:
 * notifications, the battery question, push delivery. Going to Settings from the answer carries
 * it on at the next opening, never on top of Settings.
 */
class FirstRunCarriesOnAfterNoTest {

    private val activity = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()

    private val answer: String = activity.substring(activity.indexOf("private val locationPermissionRequest = registerForActivityResult(")).let {
        it.substring(0, it.indexOf("\n    }\n"))
    }

    @Test
    fun `only the first-run chain's question carries the chain on`() {
        assertTrue(answer.contains("val atStartup = locationAskedAtStartup.also { locationAskedAtStartup = false }"))
        assertTrue(answer.contains("val carryOn: () -> Unit = { if (atStartup) checkNotificationPermission() }"))
        val first = activity.substring(activity.indexOf("private fun checkPermissions()"))
        assertTrue(first.contains("locationAskedAtStartup = true\n            showLocationRationale(onNotNow = {\n                locationAskedAtStartup = false"))
    }

    @Test
    fun `any answer but yes stops the question at every opening, and carries on`() {
        assertTrue(answer.contains("if (!fineLocationGranted && atStartup) sessionManager.setDeclinedLocationRationale(true)"))
        assertTrue(answer.contains("coarseLocationGranted -> showPreciseLocationRequiredDialog(onNotNow = carryOn)"))
        assertTrue(answer.contains("showLocationSettingsDialog(onNotNow = carryOn)"))
        assertTrue(answer.contains("else -> carryOn()"))
    }

    @Test
    fun `the dialogs carry it on when closed, not on the way to Settings`() {
        for (dialog in listOf("private fun showLocationSettingsDialog(", "private fun showPreciseLocationRequiredDialog(")) {
            val body = activity.substring(activity.indexOf(dialog)).let { it.substring(0, it.indexOf("\n    }\n")) }
            assertEquals(dialog, 2, Regex("""onNotNow\(\)""").findAll(body).count())
            assertTrue(dialog, body.contains("{ dialog, _ -> dialog.dismiss(); onNotNow() }"))
            assertTrue(dialog, body.contains(".setOnCancelListener { onNotNow() }"))
            assertTrue(dialog, body.contains("dialog.dismiss()\n                openAppSettings()\n            }"))
        }
    }
}
