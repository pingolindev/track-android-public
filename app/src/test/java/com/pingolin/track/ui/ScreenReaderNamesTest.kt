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

package com.pingolin.track.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A screen reader must hear more than "switch, off" for a Settings switch, or "unlabelled button"
 * for a back arrow: each Settings switch is named by its row's title (labelFor), and every
 * toolbar's back arrow is "Back".
 */
class ScreenReaderNamesTest {

    private val layouts = File("src/main/res/layout")

    @Test
    fun `every Settings switch is named by its row's title`() {
        val settings = File(layouts, "fragment_settings.xml").readText()
        val switches = Regex("""<com\.google\.android\.material\.materialswitch\.MaterialSwitch\s+android:id="@\+id/(\w+)"""")
            .findAll(settings).map { it.groupValues[1] }.toList()
        assertEquals(9, switches.size)
        assertEquals(emptyList<String>(), switches.filterNot { settings.contains("android:labelFor=\"@id/$it\"") })
    }

    @Test
    fun `every toolbar's back arrow has a name`() {
        val unnamed = layouts.listFiles()!!.filter { it.name.endsWith(".xml") }
            .filter { it.readText().contains("app:navigationIcon=") && !it.readText().contains("app:navigationContentDescription=") }
            .map { it.name }
        assertEquals(emptyList<String>(), unnamed)
    }
}
