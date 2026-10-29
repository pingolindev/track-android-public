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

package com.pingolin.track.ui.map

import com.pingolin.track.domain.repository.LockedCircle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Send location now" names the circle on screen by its readable name only: a locked circle's list label
 * would make the map say "Location sent to Locked circle", and "this circle" put into a frame reads wrong
 * in some languages. Without a readable name, each line has a whole unnamed version.
 */
class SendNowReadableNameTest {

    @Test
    fun `a readable name is said, a placeholder or a blank never`() {
        assertEquals("Family", sendNowReadableName("Family"))
        assertNull(sendNowReadableName(LockedCircle.PLACEHOLDER_NAME))
        assertNull(sendNowReadableName("Circle (key unavailable)"))
        assertNull(sendNowReadableName("0123456789abcdef0123456789abcdef"))
        assertNull(sendNowReadableName("   "))
        assertNull(sendNowReadableName(null))
    }

    @Test
    fun `every send-now line that names a circle has a whole unnamed line, and no stand-in name is left`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val named = Regex("""<string name="(send_now_\w+)">[^<]*%1\${'$'}s[^<]*</string>""").findAll(strings).map { it.groupValues[1] }.toList()
        assertTrue("the send-now family is found", named.size >= 6)
        for (name in named) {
            val unnamed = Regex("""<string name="${name}_unnamed">([^<]*)</string>""").find(strings)
            assertTrue("$name has no ${name}_unnamed", unnamed != null)
            assertTrue("${name}_unnamed puts something into a frame", !unnamed!!.groupValues[1].contains("%"))
        }
        assertTrue("send_now_this_circle is back", !strings.contains("name=\"send_now_this_circle\""))
    }

    @Test
    fun `the map's send-now lines take the circle's name through the rule`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val line = map.substring(map.indexOf("private fun sendNowLine(")).let { it.substring(0, it.indexOf("\n\n")) }
        assertTrue(line.contains("sendNowReadableName("))
        assertTrue(line.contains("?.let { getString(named, it) } ?: getString(unnamed)"))
    }
}
