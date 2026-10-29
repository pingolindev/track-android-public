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

package com.pingolin.track.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal is the answer to "why did tracking stop?" after the logcat
 * ring has rotated, which can take the app's own explanation away within the
 * hour. These cover the parts that decide whether that answer survives — the
 * ring, the format, and its tolerance of bad input.
 */
class TrackingJournalCodecTest {

    private fun entry(
        event: TrackingEvent = TrackingEvent.STOPPED,
        reason: TrackingStopReason = TrackingStopReason.USER_ACTION,
        wall: Long = 1_000L,
        elapsed: Long = 2_000L,
        age: Long = 30_000L,
        detail: String = "",
    ) = TrackingJournalEntry(wall, elapsed, event, reason, age, detail)

    @Test
    fun `an entry survives a round trip`() {
        val e = entry(detail = "TokenAuthenticator: refresh 401")
        val decoded = TrackingJournalCodec.decode(TrackingJournalCodec.encode(e))
        assertEquals(e, decoded)
    }

    @Test
    fun `the ring keeps the newest and drops the oldest`() {
        var list = emptyList<TrackingJournalEntry>()
        repeat(TrackingJournalCodec.MAX_ENTRIES + 10) { i ->
            list = TrackingJournalCodec.append(list, entry(wall = i.toLong()))
        }
        assertEquals(TrackingJournalCodec.MAX_ENTRIES, list.size)
        // 60 written with wall 0..59, newest 50 kept → 10..59. Oldest-first
        // ordering, so the newest write is last.
        assertEquals(10L, list.first().wallMs)
        assertEquals((TrackingJournalCodec.MAX_ENTRIES + 9).toLong(), list.last().wallMs)
    }

    @Test
    fun `a corrupt line is dropped without taking the rest with it`() {
        // The whole point is to still have an answer. One unreadable line must
        // not cost the entries around it.
        val good = TrackingJournalCodec.encode(entry(wall = 1L))
        val alsoGood = TrackingJournalCodec.encode(entry(wall = 2L))
        val decoded = TrackingJournalCodec.decodeAll("$good\nnot-a-journal-line\n$alsoGood")
        assertEquals(2, decoded.size)
        assertEquals(listOf(1L, 2L), decoded.map { it.wallMs })
    }

    @Test
    fun `an entry from an older build without the detail field still decodes`() {
        // Entries written by older versions have no detail column. They are
        // the ones present during an upgrade, and losing them would defeat the
        // journal exactly when it is needed.
        val legacy = "1000|2000|STOPPED|USER_ACTION|30000"
        val decoded = TrackingJournalCodec.decode(legacy)
        assertNotNull(decoded)
        assertEquals("", decoded!!.detail)
        assertEquals(TrackingStopReason.USER_ACTION, decoded.reason)
    }

    @Test
    fun `an unknown enum name is refused rather than guessed`() {
        assertNull(TrackingJournalCodec.decode("1|2|TELEPORTED|USER_ACTION|0"))
        assertNull(TrackingJournalCodec.decode("1|2|STOPPED|BECAUSE_REASONS|0"))
    }

    @Test
    fun `detail cannot break the format or grow without bound`() {
        val hostile = "a|b\nc\rd" + "x".repeat(500)
        val line = TrackingJournalCodec.encode(entry(detail = hostile))
        assertEquals(6, line.split('|').size)
        assertTrue(line.lineSequence().count() == 1)
        val decoded = TrackingJournalCodec.decode(line)!!
        assertTrue(decoded.detail.length <= TrackingJournalCodec.MAX_DETAIL_CHARS)
    }

    @Test
    fun `empty storage reads as no entries, not as a broken journal`() {
        assertEquals(emptyList<TrackingJournalEntry>(), TrackingJournalCodec.decodeAll(null))
        assertEquals(emptyList<TrackingJournalEntry>(), TrackingJournalCodec.decodeAll("   "))
    }

    // ---- what a human actually reads ----

    @Test
    fun `a forced sign-out reads as the fault it is, with the server's answer`() {
        val text = TrackingJournalFormatter.describe(
            entry(
                event = TrackingEvent.SIGNED_OUT,
                reason = TrackingStopReason.SESSION_REJECTED,
                detail = "TokenAuthenticator: refresh 401",
            )
        )
        assertTrue(text, text.contains("Signed out"))
        assertTrue(text, text.contains("rejected"))
        assertTrue(text, text.contains("refresh 401"))
    }

    @Test
    fun `a deliberate sign-out does not read like a fault`() {
        val text = TrackingJournalFormatter.describe(
            entry(event = TrackingEvent.SIGNED_OUT, reason = TrackingStopReason.USER_SIGNED_OUT)
        )
        assertTrue(text, text.contains("you signed out"))
    }

    @Test
    fun `the newest entry is shown first`() {
        val entries = listOf(entry(wall = 1L), entry(wall = 2L), entry(wall = 3L))
        val out = TrackingJournalFormatter.format(entries, nowMs = 10L) { it.toString() }
        assertTrue(out.startsWith("3"))
    }

    @Test
    fun `the fix age reads as a time, not a doubled phrase`() {
        // relativeAge already ends in "ago"/"just now", so appending
        // "earlier" would produce "last location just now earlier".
        val recent = TrackingJournalFormatter.describe(entry(age = 5_000L))
        assertTrue(recent, recent.contains("last location just now"))
        assertFalse(recent, recent.contains("earlier"))

        val older = TrackingJournalFormatter.describe(entry(age = 20 * 60_000L))
        assertTrue(older, older.contains("last location 20m ago"))
        assertFalse(older, older.contains("earlier"))
    }

    @Test
    fun `an entry with no fix yet says so rather than showing a bogus age`() {
        val text = TrackingJournalFormatter.describe(entry(age = TrackingHealth.NO_FIX))
        assertTrue(text, text.contains("no location yet"))
    }
}
