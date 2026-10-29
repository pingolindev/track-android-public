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

import com.pingolin.track.data.local.dao.QueuedCircleLocationDao
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.util.ApproximateIntervals.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How wide a bubble each circle got, and when: what a late point is coarsened to, at the least. From
 * what this phone saw and what the server says, the wider wherever they meet, with a margin at both
 * ends; and what this phone itself sealed in, for the outbox.
 */
class ApproximateIntervalsTest {

    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val other = "07e40000-0000-4000-8000-0000000000e7"
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"
    private val work = "c0000000-0000-4000-8000-0000000000c0"

    private val minute = 60_000L
    private val hour = 60 * minute
    private val skew = PauseIntervals.SKEW_MS
    private var now = 1_800_000_000_000L
    private var account = me
    private val prefs = InMemoryPrefs()

    /** A restart at every call: the record read back. */
    private fun store() = ApproximateIntervals.over(prefs, { account }, { now })

    @Test
    fun `a bubble seen on and then off is what a point taken in it gets, half a minute either side too`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        val exactAt = now
        now += 30 * minute
        store().observe(fam, radiusMiles = 5, deadlineMs = null)
        now += 2 * hour
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        val offAt = now

        // It began, at the earliest, when this phone last saw the circle without one.
        assertNull(store().radiusAt(fam, exactAt - skew - 1))
        assertEquals(5, store().radiusAt(fam, exactAt - 30_000L))
        assertEquals(5, store().radiusAt(fam, exactAt + hour))
        assertEquals(5, store().radiusAt(fam, offAt + 30_000L))
        assertNull("taken after it", store().radiusAt(fam, offAt + skew))
        assertNull("another circle", store().radiusAt(work, exactAt + hour))
    }

    @Test
    fun `a bubble set and undone on another phone while this one was offline is learnt from the server, width and all`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        val from = now + 3 * hour
        val to = from + 2 * hour
        now += 24 * hour
        store().observe(fam, radiusMiles = null, deadlineMs = null, server = listOf(Span(from, to, 10)))

        assertEquals(10, store().radiusAt(fam, from + hour))
        assertNull(store().radiusAt(fam, to + skew))
        // A later answer without the field keeps the server's word; one with it replaces it.
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        assertEquals(10, store().radiusAt(fam, from + hour))
        store().observe(fam, radiusMiles = null, deadlineMs = null, server = emptyList())
        assertNull(store().radiusAt(fam, from + hour))
    }

    @Test
    fun `where two bubbles meet the wider counts, and a change of width seen late takes the wider back to the first`() {
        val b = now - hour
        store().observe(fam, radiusMiles = 10, deadlineMs = null, server = listOf(Span(b - hour, b, 2), Span(b, null, 10)))
        assertEquals(10, store().radiusAt(fam, b))
        assertEquals(10, store().radiusAt(fam, b - 30_000L))
        assertEquals(2, store().radiusAt(fam, b - 30 * minute))

        // No word from the server: 3 miles, then 8, seen an hour apart.
        store().observe(work, radiusMiles = null, deadlineMs = null)
        val exactAt = now
        now += minute
        store().observe(work, radiusMiles = 3, deadlineMs = null)
        now += hour
        store().observe(work, radiusMiles = 8, deadlineMs = null)
        assertEquals("the 8 miles began some time in that hour", 8, store().radiusAt(work, exactAt + 30 * minute))
        now += hour
        store().observe(work, radiusMiles = null, deadlineMs = null)
        assertEquals(8, store().radiusAt(work, now - 30 * minute))
        assertNull(store().radiusAt(work, now + skew))
    }

    @Test
    fun `the server's start wins over a guessed one, from any later answer, though never after this phone saw it`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        val lastExact = now
        now += 2 * hour
        val sawAt = now
        store().observe(fam, radiusMiles = 5, deadlineMs = null)
        assertEquals(5, store().radiusAt(fam, lastExact + 30 * minute))

        val realStart = lastExact + 90 * minute
        now += minute
        store().observe(fam, radiusMiles = 5, deadlineMs = null, server = listOf(Span(realStart, null, 5)))
        assertNull("a point kept from before then goes exact", store().radiusAt(fam, lastExact + 30 * minute))
        assertEquals(5, store().radiusAt(fam, realStart + minute))

        now += minute
        store().observe(fam, radiusMiles = 5, deadlineMs = null, server = listOf(Span(sawAt + hour, null, 5)))
        assertEquals("seen on then", 5, store().radiusAt(fam, sawAt))

        // Seen off with the server's span, first met then on another circle: corrected then.
        store().observe(work, radiusMiles = null, deadlineMs = null)
        val workExact = now
        now += 2 * hour
        store().observe(work, radiusMiles = 5, deadlineMs = null)
        now += hour
        store().observe(work, radiusMiles = null, deadlineMs = null, server = listOf(Span(workExact + 90 * minute, now - 10 * minute, 5)))
        assertNull(store().radiusAt(work, workExact + 30 * minute))
        assertEquals(5, store().radiusAt(work, now - 11 * minute))
        assertNull(store().radiusAt(work, now - 8 * minute))
    }

    @Test
    fun `a bubble with an end seen off late ended at that end, and with no end seen on it runs on`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        now += minute
        val until = now + hour
        store().observe(fam, radiusMiles = 4, deadlineMs = until)
        assertEquals(4, store().radiusAt(fam, until - minute))
        assertNull("its planned end, while it runs", store().radiusAt(fam, until + skew))

        now = until + 3 * hour
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        assertNull("ended at its end, not when seen", store().radiusAt(fam, until + hour))
    }

    @Test
    fun `a bubble is kept past the oldest a late point can be, and a week on it goes`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        store().observe(fam, radiusMiles = 6, deadlineMs = null)
        val inside = now
        now += minute
        store().observe(fam, radiusMiles = null, deadlineMs = null)

        now += QueuedCircleLocationDao.MAX_AGE_MS
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        assertEquals(6, store().radiusAt(fam, inside))
        now += PauseIntervals.RETENTION_MS
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        assertNull(store().radiusAt(fam, inside))
    }

    @Test
    fun `per account under its canonical id, another account's sign-in takes this one's, and an erasure all`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        store().observe(fam, radiusMiles = 5, deadlineMs = null)
        val inside = now + minute

        assertEquals(5, store().radiusAt(fam, inside))
        account = me.uppercase()
        assertEquals("the same account, another spelling", 5, store().radiusAt(fam, inside))
        account = other
        assertNull("another account sees none", store().radiusAt(fam, inside))

        store().keepOnly(other)
        account = me
        assertNull(store().radiusAt(fam, inside))

        store().observe(fam, radiusMiles = null, deadlineMs = null)
        store().observe(fam, radiusMiles = 5, deadlineMs = null)
        store().wipeAll()
        assertNull(store().radiusAt(fam, inside))
    }

    @Test
    fun `a record that doesn't read is never 'no bubble', and nothing is written over it`() {
        val damaged = DamagedPrefs()
        ApproximateIntervals.over(damaged, { me }, { now }).apply {
            observe(fam, radiusMiles = null, deadlineMs = null)
            observe(fam, radiusMiles = 5, deadlineMs = null)
        }
        val stored = damaged.data.toMap()
        damaged.damaged = true

        val fresh = ApproximateIntervals.over(damaged, { me }, { now })
        assertThrows(StoreUnreadable::class.java) { fresh.radiusAt(fam, now) }
        assertThrows(StoreUnreadable::class.java) { fresh.heldAt(fam, now) }
        fresh.observe(fam, radiusMiles = null, deadlineMs = null)
        assertEquals(stored, damaged.data)
    }

    @Test
    fun `what this phone sealed in when a point was taken - the bubble it had itself seen on by then, and not yet over`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        now += hour
        // Set 10 minutes ago on another phone; this one sees it now.
        store().observe(fam, radiusMiles = 5, deadlineMs = null, server = listOf(Span(now - 10 * minute, null, 5)))
        val sawAt = now

        assertNull("exact, well before", store().heldAt(fam, sawAt - hour))
        assertNull("before this phone saw it, though in force", store().heldAt(fam, sawAt - 5 * minute))
        assertEquals(5, store().radiusAt(fam, sawAt - 5 * minute))
        assertEquals(5, store().heldAt(fam, sawAt + minute))

        // Widened to 10 on another phone; this one sees that an hour on, sealing in 5 until then.
        now += hour
        val widenedAt = now - 20 * minute
        store().observe(fam, radiusMiles = 10, deadlineMs = null, server = listOf(Span(sawAt - 10 * minute, widenedAt, 5), Span(widenedAt, null, 10)))
        assertEquals(5, store().heldAt(fam, widenedAt + 5 * minute))
        assertEquals(10, store().radiusAt(fam, widenedAt + 5 * minute))
        assertEquals(10, store().heldAt(fam, now + minute))

        // Narrowed to 3 on another phone; this one sealed in 10 until it saw that.
        now += hour
        val narrowedAt = now - 30 * minute
        store().observe(fam, radiusMiles = 3, deadlineMs = null, server = listOf(Span(widenedAt, narrowedAt, 10), Span(narrowedAt, null, 3)))
        assertEquals(10, store().heldAt(fam, narrowedAt + 10 * minute))

        // Seen off 20 s after the server ended it: sealed in it until then, exact after.
        now += hour
        val offAt = now
        store().observe(fam, radiusMiles = null, deadlineMs = null, server = listOf(Span(narrowedAt, offAt - 20_000L, 3)))
        assertEquals(3, store().heldAt(fam, offAt - 10_000L))
        assertNull(store().heldAt(fam, offAt + 10_000L))
        assertEquals("still inside its margin", 3, store().radiusAt(fam, offAt + 10_000L))
    }

    @Test
    fun `what this phone held is only what its own rows said - never the server's word nor a start it dates back`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        now += 4 * hour
        val sawAt = now
        // The server: 8 miles three hours ago, and 5 since half an hour ago, both set elsewhere.
        store().observe(fam, radiusMiles = 5, deadlineMs = null, server = listOf(
            Span(sawAt - 3 * hour, sawAt - 2 * hour, 8), Span(sawAt - 30 * minute, null, 5),
        ))

        assertEquals(5, store().radiusAt(fam, sawAt - 10 * minute))
        assertNull("in force, but not this phone's row: sealed exact", store().heldAt(fam, sawAt - 10 * minute))
        assertEquals(8, store().radiusAt(fam, sawAt - 150 * minute))
        assertNull(store().heldAt(fam, sawAt - 150 * minute))
        assertEquals(5, store().heldAt(fam, sawAt))
    }

    @Test
    fun `what this phone held is kept a week after it saw it end, however early the server ended it`() {
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        val heldFrom = now
        store().observe(fam, radiusMiles = 5, deadlineMs = null)
        // Offline eight days; back, it sees exact: the server ended it an hour after it began.
        now += 8 * 24 * hour
        store().observe(fam, radiusMiles = null, deadlineMs = null, server = listOf(Span(heldFrom, heldFrom + hour, 5)))

        assertEquals("a row it queued an hour before it saw the end", 5, store().heldAt(fam, now - hour))
        now += PauseIntervals.RETENTION_MS + minute
        store().observe(fam, radiusMiles = null, deadlineMs = null, server = emptyList())
        assertNull("a week on", store().heldAt(fam, now - PauseIntervals.RETENTION_MS - 2 * hour))
    }

    @Test
    fun `a point's history is known once the server's bubbles came in after it, not before, nor across a restart`() {
        val pauses = store()
        val taken = now
        assertFalse(pauses.knowsSince(fam, taken))
        now += minute
        pauses.observe(fam, radiusMiles = null, deadlineMs = null)
        assertFalse("a row without the field", pauses.knowsSince(fam, taken))
        pauses.observe(fam, radiusMiles = null, deadlineMs = null, server = emptyList())
        assertTrue(pauses.knowsSince(fam, taken))
        assertFalse("within the margin", pauses.knowsSince(fam, now - 30_000L))
        assertFalse("another circle", pauses.knowsSince(work, taken))
        assertFalse("a restart", store().knowsSince(fam, taken))
    }

    @Test
    fun `a row with no bubble ends what the stored word still has open, and one with another width that width's, as of then`() {
        store().observe(fam, radiusMiles = 5, deadlineMs = null, server = listOf(Span(now - hour, null, 5)))
        now += 10 * minute
        val exactAt = now
        // A row with no spans in it: one kept here, or an older server's.
        store().observe(fam, radiusMiles = null, deadlineMs = null)
        assertEquals("taken in it", 5, store().radiusAt(fam, exactAt - minute))
        assertNull("taken after it", store().radiusAt(fam, exactAt + skew))

        store().observe(work, radiusMiles = 10, deadlineMs = null, server = listOf(Span(now - hour, null, 10)))
        now += 10 * minute
        val narrowedAt = now
        store().observe(work, radiusMiles = 3, deadlineMs = null)
        assertEquals(10, store().radiusAt(work, narrowedAt - minute))
        assertEquals(3, store().radiusAt(work, narrowedAt + skew))
    }
}
