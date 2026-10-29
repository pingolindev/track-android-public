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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Retrofit hands a refused save (4xx, 5xx) back as a Response; it doesn't throw. So every save
 * here keeps its answer and checks it before it says "Preferences saved", as the e-mail switch
 * does.
 */
class NotificationSavesCheckedTest {

    @Test
    fun `every notification-preferences save checks the answer before saying it saved`() {
        val source = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val saves = Regex("""(?:val (\w+) = )?tracksApi\.updateNotificationPreferences\(\w+\)""").findAll(source).toList()
        assertTrue("the categories and the e-mail switch", saves.size >= 2)
        for (save in saves) {
            val answer = save.groupValues[1]
            assertTrue("${save.value}: its answer is kept", answer.isNotEmpty())
            val saidAt = source.indexOf("\"Preferences saved\"", save.range.last)
            assertTrue("${save.value}: says it saved", saidAt > 0)
            assertTrue(
                "${save.value}: checked before it says so",
                source.substring(save.range.last, saidAt).contains("if (!$answer.isSuccessful) throw"),
            )
        }
    }
}
