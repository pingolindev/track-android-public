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

import com.pingolin.track.data.local.dao.PendingLocationDao
import com.pingolin.track.data.local.dao.QueuedCircleLocationDao
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When this account didn't share with each circle: what a late point is held against. Kept per
 * account, from what this phone saw and what the server says, every guess towards "inside", with a
 * margin at both ends, for a week, and read back after a restart.
 */
class PauseIntervalsTest {

    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val other = "07e40000-0000-4000-8000-0000000000e7"
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"

    private val minute = 60_000L
    private val hour = 60 * minute
    private var now = 1_800_000_000_000L
    private var account = me
    private val prefs = InMemoryPrefs()
    private fun store() = PauseIntervals.over(prefs, { account }, { now })

    @Test
    fun `a pause seen running and then seen over holds what was taken in it, with a minute either side`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        val sharedAt = now
        now += 30 * minute
        pauses.observe(fam, notSharing = true, deadlineMs = null)
        now += 2 * hour
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        val seenOverAt = now

        // It began, at the earliest, when this phone last saw the circle shared with.
        assertFalse(pauses.covers(fam, sharedAt - PauseIntervals.SKEW_MS - 1))
        assertTrue("within the margin before it", pauses.covers(fam, sharedAt - PauseIntervals.SKEW_MS))
        assertTrue(pauses.covers(fam, sharedAt + 45 * minute))
        assertTrue("within the margin after it", pauses.covers(fam, seenOverAt + PauseIntervals.SKEW_MS - 1))
        assertFalse("taken after it: sent", pauses.covers(fam, seenOverAt + PauseIntervals.SKEW_MS))
        // A phone's clock against the server's: half a minute either side is inside.
        assertTrue(pauses.covers(fam, sharedAt - 30_000L))
        assertTrue(pauses.covers(fam, seenOverAt + 30_000L))
        assertFalse("another circle", pauses.covers("c0000000-0000-4000-8000-0000000000c0", sharedAt + 45 * minute))
    }

    @Test
    fun `a pause set and ended on another phone while this one was offline is learnt from the server`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        // Offline for a day; meanwhile the other phone paused 10:00-12:00. This one sees only "sharing".
        val from = now + 3 * hour
        val to = from + 2 * hour
        now += 24 * hour
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(from, to)))

        assertTrue(pauses.covers(fam, from + hour))
        assertTrue(pauses.covers(fam, to + PauseIntervals.SKEW_MS - 1))
        assertFalse(pauses.covers(fam, to + PauseIntervals.SKEW_MS))
        assertFalse(pauses.covers(fam, from - PauseIntervals.SKEW_MS - 1))
        // A later answer without the field keeps the server's word; one with it replaces it.
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertTrue(pauses.covers(fam, from + hour))
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = emptyList())
        assertFalse(pauses.covers(fam, from + hour))
    }

    @Test
    fun `a running pause starts where the server says, and with no word at all, the oldest a late point can be`() {
        val pauses = store()
        val serverStart = now - 10 * minute
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(serverStart, null)))
        assertTrue(pauses.covers(fam, serverStart))
        assertFalse(pauses.covers(fam, serverStart - PauseIntervals.SKEW_MS - 1))

        // No word at all: from as far back as a kept point can be (48 h by capture), so none goes.
        val unknown = store().also { it.observe("c0000000-0000-4000-8000-0000000000c0", notSharing = true, deadlineMs = null) }
        assertTrue(unknown.covers("c0000000-0000-4000-8000-0000000000c0", now - 47 * hour))
        assertTrue(unknown.covers("c0000000-0000-4000-8000-0000000000c0", now - QueuedCircleLocationDao.MAX_AGE_MS))
        assertFalse(unknown.covers("c0000000-0000-4000-8000-0000000000c0", now - PauseIntervals.UNKNOWN_START_MS - PauseIntervals.SKEW_MS - 1))
    }

    @Test
    fun `the server's start wins over a guessed one, from any later answer, and so does its end`() {
        // A restart between each step: store() reads the record back.
        store().observe(fam, notSharing = false, deadlineMs = null)
        val lastShared = now
        now += 2 * hour
        // First seen in an answer with no word from the server: from the last time seen sharing.
        store().observe(fam, notSharing = true, deadlineMs = null)
        assertTrue(store().covers(fam, lastShared + 30 * minute))

        // The next answer says it began 90 minutes after that: a point kept from before then goes.
        val realStart = lastShared + 90 * minute
        store().observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(realStart, null)))
        assertFalse(store().covers(fam, lastShared + 30 * minute))
        assertTrue(store().covers(fam, realStart + minute))

        // Seen over, with the server's span: its start and its end.
        now += hour
        val end = now - 10 * minute
        store().observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(realStart, end)))
        assertFalse(store().covers(fam, lastShared + 30 * minute))
        assertTrue(store().covers(fam, end - minute))
        assertFalse(store().covers(fam, end + 2 * minute))
    }

    @Test
    fun `a guessed start first met by the server's word when the pause is seen over is corrected then`() {
        val circle = "c0000000-0000-4000-8000-0000000000c0"
        store().observe(circle, notSharing = false, deadlineMs = null)
        val lastShared = now
        now += 2 * hour
        store().observe(circle, notSharing = true, deadlineMs = null)
        now += hour
        val realStart = lastShared + 90 * minute
        store().observe(circle, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(realStart, now - 10 * minute)))

        assertFalse(store().covers(circle, lastShared + 30 * minute))
        assertTrue(store().covers(circle, realStart + minute))
    }

    @Test
    fun `a server start ahead of this phone's clock, or after the moment it saw the pause, starts it no later than that`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        now += hour
        val sawAt = now
        // 20 s from now by this phone's clock: a clock that is behind.
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(sawAt + 20_000L, null)))
        assertTrue(pauses.covers(fam, sawAt))
        assertFalse("not from the last time seen sharing", pauses.covers(fam, sawAt - 30 * minute))

        // A later answer that moves the start past what this phone saw doesn't move it there.
        now += minute
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(sawAt + hour, null)))
        assertTrue("seen paused then", pauses.covers(fam, sawAt))
        now += hour
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(sawAt + hour, now - minute)))
        assertTrue("seen paused then, after it ended too", pauses.covers(fam, sawAt))
    }

    @Test
    fun `a row that says sharing ends what the stored word still has open, as of then`() {
        store().observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour, null)))
        now += 10 * minute
        val resumedAt = now
        // A row that says sharing and has no spans in it: one kept here, or an older server's.
        store().observe(fam, notSharing = false, deadlineMs = null)

        assertTrue("taken during it", store().covers(fam, resumedAt - minute))
        assertFalse("taken after it", store().covers(fam, resumedAt + PauseIntervals.SKEW_MS))
        now += hour
        store().observe(fam, notSharing = false, deadlineMs = null)
        assertFalse(store().covers(fam, resumedAt + 30 * minute))
        assertTrue(store().covers(fam, resumedAt - minute))
    }

    @Test
    fun `a resume made here ends its own pause for what is taken after it, with no margin - one seen from elsewhere keeps it`() {
        val pauses = store()
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour, null)))
        now += minute
        pauses.resumedHere(fam)
        val resumedAt = now
        // The /resume answer: ended when the server ended it, a moment earlier by its clock.
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour - minute, resumedAt - 1_000L)))

        assertFalse("taken just after it", pauses.covers(fam, resumedAt + 1_000L))
        assertTrue("taken just before it", pauses.covers(fam, resumedAt - 5_000L))
        assertTrue("the same end seen from elsewhere (another phone, or after a restart)", store().covers(fam, resumedAt + 1_000L))
    }

    @Test
    fun `a resume made here while real-time sharing stayed off ends nothing - what is taken after it is inside until sharing starts`() {
        val pauses = store()
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour, null)))
        now += minute
        pauses.resumedHere(fam)
        val resumedAt = now
        // Real-time sharing off still: not sharing goes on, open.
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour - minute, null)))
        assertTrue(pauses.covers(fam, resumedAt + 2 * minute))
        // Switched on elsewhere five minutes later.
        now += 10 * minute
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(resumedAt - hour - minute, resumedAt + 5 * minute)))

        assertTrue("taken after the resume here, before sharing started", pauses.covers(fam, resumedAt + 2 * minute))
        assertFalse(pauses.covers(fam, resumedAt + 7 * minute))
    }

    @Test
    fun `a pause set elsewhere just after a resume made here holds what was taken in it, with its margin`() {
        val pauses = store()
        pauses.observe(fam, notSharing = true, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour, null)))
        now += minute
        pauses.resumedHere(fam)
        val resumedAt = now
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(PauseIntervals.Interval(now - hour - minute, resumedAt)))
        // Paused again on another phone 10 s after it, for 20 s; seen here 40 s after the resume.
        now += 40_000L
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(
            PauseIntervals.Interval(resumedAt - hour - minute, resumedAt),
            PauseIntervals.Interval(resumedAt + 10_000L, resumedAt + 30_000L),
        ))

        assertTrue("inside it", pauses.covers(fam, resumedAt + 20_000L))
        assertTrue("within its margin", pauses.covers(fam, resumedAt + 5_000L))
    }

    @Test
    fun `a timed pause seen over after its deadline ended at the deadline, and one still running ends there as planned`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        now += minute
        val deadline = now + hour
        pauses.observe(fam, notSharing = true, deadlineMs = deadline)
        assertTrue(pauses.covers(fam, deadline - minute))
        assertFalse("planned end, while it runs", pauses.covers(fam, deadline + PauseIntervals.SKEW_MS))

        now = deadline + 3 * hour
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertFalse("ended at the deadline, not when seen", pauses.covers(fam, deadline + hour))
    }

    @Test
    fun `a pause is kept past the oldest a late point can be - the kept queue's 48 h, the outbox's 24 h`() {
        for (age in listOf(QueuedCircleLocationDao.MAX_AGE_MS, PendingLocationDao.MAX_AGE_MS, 48 * hour)) {
            val pauses = store()
            pauses.observe(fam, notSharing = false, deadlineMs = null)
            pauses.observe(fam, notSharing = true, deadlineMs = null)
            val inside = now
            now += minute
            pauses.observe(fam, notSharing = false, deadlineMs = null)

            now += age
            pauses.observe(fam, notSharing = false, deadlineMs = null)
            assertTrue("$age ms on", pauses.covers(fam, inside))
            assertTrue("$age ms on, read back", store().covers(fam, inside))
            pauses.wipeAll()
        }
    }

    @Test
    fun `a pause is kept a week after it ended, and then goes`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        pauses.observe(fam, notSharing = true, deadlineMs = null)
        val inside = now
        now += minute
        pauses.observe(fam, notSharing = false, deadlineMs = null)

        now += PauseIntervals.RETENTION_MS - hour
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertTrue(pauses.covers(fam, inside))
        now += 2 * hour
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertFalse(pauses.covers(fam, inside))
    }

    @Test
    fun `read back after a restart, per account under its canonical id, and only an erasure deletes them`() {
        store().apply {
            observe(fam, notSharing = false, deadlineMs = null)
            observe(fam, notSharing = true, deadlineMs = null)
        }
        val inside = now + minute

        assertTrue("a restart reads it back", store().covers(fam, inside))
        account = me.uppercase()
        assertTrue("the same account, another spelling", store().covers(fam, inside))
        account = other
        assertFalse("another account sees none", store().covers(fam, inside))

        // Another account signs in: this account's go, as its queued points do.
        store().keepOnly(other)
        account = me
        assertFalse(store().covers(fam, inside))

        store().apply {
            observe(fam, notSharing = false, deadlineMs = null)
            observe(fam, notSharing = true, deadlineMs = null)
            wipeAll()
        }
        assertFalse(store().covers(fam, inside))
    }

    @Test
    fun `a record that doesn't read is never 'no pause', and nothing is written over it`() {
        val damaged = DamagedPrefs()
        val pauses = PauseIntervals.over(damaged, { me }, { now })
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        pauses.observe(fam, notSharing = true, deadlineMs = null)
        val stored = damaged.data.toMap()
        damaged.damaged = true

        val fresh = PauseIntervals.over(damaged, { me }, { now })
        assertThrows(StoreUnreadable::class.java) { fresh.covers(fam, now) }
        fresh.observe(fam, notSharing = false, deadlineMs = null)
        assertEquals(stored, damaged.data)
    }

    @Test
    fun `a point is placed only by a re-sync after it was taken - a pause set and ended elsewhere while offline`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null, server = emptyList())
        val lastSync = now
        // Offline from here. Another phone pauses from +30 min to +2 h; this one takes a point at +1 h.
        val taken = now + hour
        now += 3 * hour

        // Back online, before any re-sync: nothing says "paused", so only the sync time holds it back.
        assertFalse(pauses.covers(fam, taken))
        assertFalse(pauses.syncedSince(fam, taken))
        assertTrue("taken before the last re-sync", pauses.syncedSince(fam, lastSync - PauseIntervals.SKEW_MS))
        assertFalse("within the margin of it", pauses.syncedSince(fam, lastSync - PauseIntervals.SKEW_MS + 1))

        pauses.observe(fam, notSharing = false, deadlineMs = null, server = listOf(
            PauseIntervals.Interval(lastSync + 30 * minute, lastSync + 2 * hour),
        ))
        assertTrue(pauses.syncedSince(fam, taken))
        assertTrue("now known: inside", pauses.covers(fam, taken))
        assertFalse("taken after it: goes", pauses.covers(fam, lastSync + 2 * hour + 30 * minute))

        // A restart knows no re-sync yet; another account's never counts.
        assertFalse(store().syncedSince(fam, lastSync - hour))
        account = other
        assertFalse(pauses.syncedSince(fam, lastSync - hour))
        account = me
        pauses.keepOnly(other)
        assertFalse("another account signed in", pauses.syncedSince(fam, lastSync - hour))
    }

    @Test
    fun `seen sharing again and again goes to disk only once it has moved on half an hour`() {
        val pauses = store()
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        val written = prefs.data.toMap()
        now += 5 * minute
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertEquals("a 5-minute re-sync writes nothing", written, prefs.data)
        now += 30 * minute
        pauses.observe(fam, notSharing = false, deadlineMs = null)
        assertTrue(written != prefs.data)
    }
}
