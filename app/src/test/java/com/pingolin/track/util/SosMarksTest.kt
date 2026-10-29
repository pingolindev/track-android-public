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

import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this device saw of each SOS: per account, under the one spelling of each id, one way,
 * pruned only past the alarm age, and a file that doesn't read is nothing seen with nothing
 * written over it.
 */
class SosMarksTest {

    private companion object {
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val OTHER = "0ccc0000-0000-4000-8000-00000000000b"
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val SOS_2 = "0505abcd-0000-4000-8000-00000000abce"
        const val DAY = 86_400_000L
    }

    private val prefs = DamagedPrefs()
    private var account: String? = ME
    private val marks = SecurePrefsSosMarks(mockk(), { account }, { prefs })

    @Test
    fun `each mark is this account's, under the one spelling of the circle and the SOS`() {
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        marks.markEnded(CIRCLE.uppercase(), SOS, 1_000L)
        marks.markRosterEnded(CIRCLE, "{${SOS.uppercase()}}", 1_000L)
        marks.markRang(CIRCLE.uppercase(), "{$SOS}", 1_000L)

        assertTrue(marks.seenCurrent(CIRCLE.uppercase(), SOS.uppercase(), "hash-a"))
        assertTrue(marks.ended(CIRCLE, SOS))
        assertTrue(marks.rosterEnded(CIRCLE, SOS))
        assertTrue(marks.rang(CIRCLE, SOS.uppercase()))
        assertFalse("another SOS", marks.ended(CIRCLE, SOS_2))
        assertFalse("another SOS", marks.rang(CIRCLE, SOS_2))

        account = OTHER
        assertFalse("another account adopts nothing", marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertFalse(marks.ended(CIRCLE, SOS))
        assertFalse(marks.rosterEnded(CIRCLE, SOS))
        assertFalse(marks.rang(CIRCLE, SOS))

        account = ME.uppercase()
        assertTrue("this account in another spelling is this account", marks.ended(CIRCLE, SOS))
        assertTrue(marks.rang(CIRCLE, SOS))
    }

    @Test
    fun `the newest payloads seen current are kept, sixteen as iOS, and one seen again is the newest`() {
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        marks.markSeenCurrent(CIRCLE, SOS, "hash-b", 1_000L)
        assertTrue("the first payload is still seen after the reseal's", marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertTrue(marks.seenCurrent(CIRCLE, SOS, "hash-b"))
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "hash-c"))
        assertEquals(16, SosMarks.MAX_HASHES)

        // hash-a served again: the newest now, so the next fifteen push hash-b out, not hash-a.
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        repeat(SosMarks.MAX_HASHES - 1) { marks.markSeenCurrent(CIRCLE, SOS, "more-$it", 1_000L) }
        assertTrue("seen again, kept", marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertFalse("the oldest goes", marks.seenCurrent(CIRCLE, SOS, "hash-b"))
        assertTrue(marks.seenCurrent(CIRCLE, SOS, "more-0"))

        repeat(SosMarks.MAX_HASHES) { marks.markSeenCurrent(CIRCLE, SOS, "later-$it", 1_000L) }
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "more-14"))
        assertTrue("the newest sixteen", (0 until SosMarks.MAX_HASHES).all { marks.seenCurrent(CIRCLE, SOS, "later-$it") })
    }

    @Test
    fun `the alarm sounded here - one way, the SOS's whatever its payload`() {
        assertFalse(marks.rang(CIRCLE, SOS))
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        marks.markRang(CIRCLE, SOS, 1_000L)
        assertTrue(marks.rang(CIRCLE, SOS))
        marks.markSeenCurrent(CIRCLE, SOS, "hash-b", 1_000L)
        marks.markRang(CIRCLE, SOS, 2_000L)
        assertTrue("a reseal's payload changes nothing", marks.rang(CIRCLE, SOS))
        assertFalse(marks.rang(CIRCLE, SOS_2))
    }

    @Test
    fun `nobody signed in - nothing seen, nothing marked`() {
        account = null
        assertFalse(marks.readable())
        marks.markEnded(CIRCLE, SOS, 1_000L)
        marks.markRang(CIRCLE, SOS, 1_000L)
        assertFalse(marks.ended(CIRCLE, SOS))
        assertFalse(marks.rang(CIRCLE, SOS))
        assertTrue(prefs.data.isEmpty())
    }

    @Test
    fun `a file that doesn't read - not readable, nothing seen, and nothing written over it`() {
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        marks.markEnded(CIRCLE, SOS, 1_000L)
        marks.markRang(CIRCLE, SOS, 1_000L)
        assertTrue(marks.readable())
        val stored = prefs.data.toMap()

        prefs.damaged = true
        assertFalse(marks.readable())
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertFalse(marks.ended(CIRCLE, SOS))
        assertFalse(marks.rosterEnded(CIRCLE, SOS))
        assertFalse("doesn't read: never rang, as far as this device can tell", marks.rang(CIRCLE, SOS))
        marks.markSeenCurrent(CIRCLE, SOS, "hash-b", 1_000L)
        marks.markEnded(CIRCLE, SOS_2, 1_000L)
        marks.markRosterEnded(CIRCLE, SOS, 1_000L)
        marks.markRang(CIRCLE, SOS_2, 1_000L)
        marks.pruneBefore(Long.MAX_VALUE)
        assertEquals("nothing written over it", stored, prefs.data)

        prefs.damaged = false
        assertTrue("as it was", marks.ended(CIRCLE, SOS))
        assertTrue(marks.rang(CIRCLE, SOS))
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "hash-b"))
        assertFalse(marks.rang(CIRCLE, SOS_2))
    }

    @Test
    fun `a file one entry of which doesn't read - the whole file counts as unreadable, and nothing is written to it`() {
        // EncryptedSharedPreferences opens one key at a time, and getAll every key: an entry that
        // doesn't decrypt fails getAll only. The file is read whole before any write.
        val inner = InMemoryPrefs()
        val partly = object : android.content.SharedPreferences by inner {
            override fun getAll(): Map<String, *> = throw SecurityException("one entry doesn't decrypt")
        }
        val marks = SecurePrefsSosMarks(mockk(), { ME }, { partly })

        assertFalse(marks.readable())
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 1_000L)
        marks.markEnded(CIRCLE, SOS, 1_000L)
        marks.markRosterEnded(CIRCLE, SOS, 1_000L)
        marks.markRang(CIRCLE, SOS, 1_000L)

        assertTrue("nothing written", inner.data.isEmpty())
    }

    @Test
    fun `pruned only past the alarm age, by the date of the first mark, and only this account's`() {
        marks.markEnded(CIRCLE, SOS, 10 * DAY)
        marks.markSeenCurrent(CIRCLE, SOS, "hash-a", 20 * DAY)
        marks.markRang(CIRCLE, SOS, 20 * DAY)
        marks.markSeenCurrent(CIRCLE, SOS_2, "hash-b", 12 * DAY)
        marks.markRang(CIRCLE, SOS_2, 12 * DAY)
        account = OTHER
        marks.markEnded(CIRCLE, SOS, 1 * DAY)

        account = ME
        marks.pruneBefore(11 * DAY)
        assertFalse("dated by its first mark, 10 days", marks.ended(CIRCLE, SOS))
        assertFalse(marks.seenCurrent(CIRCLE, SOS, "hash-a"))
        assertFalse(marks.rang(CIRCLE, SOS))
        assertTrue(marks.seenCurrent(CIRCLE, SOS_2, "hash-b"))
        assertTrue(marks.rang(CIRCLE, SOS_2))
        account = OTHER
        assertTrue("another account's marks aren't this one's to prune", marks.ended(CIRCLE, SOS))
    }

    @Test
    fun `another account's sign-in drops them only where the guard lets it, and erasure drops every one`() {
        marks.markEnded(CIRCLE, SOS, 1_000L)
        account = OTHER
        marks.markEnded(CIRCLE, SOS_2, 1_000L)

        marks.keepOnly(OTHER) { false }
        account = ME
        assertTrue("the typed-email guard kept them", marks.ended(CIRCLE, SOS))

        marks.keepOnly(OTHER) { it == ME }
        assertFalse(marks.ended(CIRCLE, SOS))
        account = OTHER
        assertTrue("the signed-in account's own stay", marks.ended(CIRCLE, SOS_2))

        marks.wipeAll()
        assertTrue(prefs.data.isEmpty())
    }
}
