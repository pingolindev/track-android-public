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

package com.pingolin.track.ui.circles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The notice the app falls back to when it cannot fetch the served one is the served text
 * itself, byte for byte. This is v1.5: "How long we keep it" says your history's two days
 * or a year and that the last location you shared is kept for up to 13 months; "Stopping"
 * says what real-time sharing off and location history off each do; and after the
 * membership sentence come an owner's ways out, "As its owner, you delete it, or transfer
 * ownership and then leave." It is the text the server serves, kept as
 * res/raw/location_notice_v1_5.md, 1518 bytes, whose SHA-256 is pinned here and in the iOS
 * app's ConsentMembershipTests.
 *
 * A new notice version means copying the server's new notice text into res/raw as
 * `location_notice_v*.md` and pinning its hash here — never editing the copy.
 */
class LocationNoticeBundledTest {

    private val file = File("src/main/res/raw/location_notice_v1_5.md")

    @Test
    fun `the bundled notice is the served v1_5 text, byte for byte`() {
        val bytes = file.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("72ff91c7529121149e6a5760584ec52e95e43e87eb6abca0fa5701c02b974193", sha256)
        assertEquals(1518, bytes.size)
        // One fallback only: no older copy left beside it to be shown by mistake.
        assertEquals(listOf("location_notice_v1_5.md"), File("src/main/res/raw").list()!!.filter { it.startsWith("location_notice") })
    }

    @Test
    fun `it declares its own version, and the membership sentence comes before the question`() {
        val text = file.readText()
        assertEquals("1.5", LocationConsentDialog.versionOf(text))
        val sentence = text.indexOf("Sharing your location is part of being in a circle.")
        val question = text.indexOf("## The question")
        assertTrue("sentence at $sentence, question at $question", sentence in 0 until question)
        // v1.4 and later: an owner's ways out, on their own line after it.
        assertTrue(text.contains("you leave the circle.\nAs its owner, you delete it, or transfer ownership and then leave.\n"))
    }

    /** The sentence ends at "anywhere."; and no key talk is left in what people read. */
    @Test
    fun `it says what happens without talking about keys, and no longer says we can't read it`() {
        val text = file.readText()
        assertTrue(text.contains("Your position is encrypted on your phone before it goes anywhere.\nTravelling alongside"))
        assertFalse("\"we can't read it.\" is gone", Regex("can.t\\s+read it").containsMatchIn(text))
        assertTrue("key talk: " + Regex("\\bkeys?\\b", RegexOption.IGNORE_CASE).find(text)?.value,
            !Regex("\\bkeys?\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
}
