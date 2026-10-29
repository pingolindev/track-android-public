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

package com.pingolin.track.location

import android.location.Location
import com.pingolin.track.util.SessionManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The newest fix of this account's own position, from any path, so that a liveness re-send is
 * never stale: kept only when newer on the one clock every source shares, only when the
 * service's inbound guards would take it, only for the account it was taken for.
 */
class NewestOwnFixTest {

    private companion object {
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-000000000b0b"
        const val NOW = 9_000_000_000_000L
        const val SECOND = 1_000_000_000L
    }

    private var signedIn: String? = ALICE
    private val session = mockk<SessionManager> { every { getUserId() } answers { signedIn } }
    private val held = NewestOwnFix(session).also { it.elapsedNanos = { NOW } }

    private fun fix(secondsAgo: Long, accuracy: Float? = 10f, at: Long = NOW - secondsAgo * SECOND) = mockk<Location> {
        every { elapsedRealtimeNanos } returns at
        every { hasAccuracy() } returns (accuracy != null)
        every { this@mockk.accuracy } returns (accuracy ?: 0f)
    }

    @Test
    fun `a newer fix is kept, and an older one or the same moment is not`() {
        val service = fix(secondsAgo = 20)
        val sendNow = fix(secondsAgo = 5)
        assertTrue(held.offer(service))
        assertTrue(held.offer(sendNow))
        assertSame(sendNow, held.newest())

        assertFalse("an older one-shot changes nothing", held.offer(fix(secondsAgo = 10)))
        assertFalse("nor one of the same moment", held.offer(fix(secondsAgo = 5)))
        assertSame(sendNow, held.newest())
    }

    @Test
    fun `a fix the service's guards would refuse is never kept`() {
        assertFalse("rougher than the real-time bound", held.offer(fix(secondsAgo = 1, accuracy = 101f)))
        assertFalse("older than the stale-fix limit", held.offer(fix(secondsAgo = 31)))
        assertFalse("no elapsed time to compare", held.offer(fix(secondsAgo = 0, at = 0L)))
        assertFalse("from after now on this clock", held.offer(fix(secondsAgo = 0, at = NOW + 1)))
        assertNull(held.newest())

        // Within both bounds, as the service takes it; and a fix with no accuracy, as the service does.
        assertTrue(held.offer(fix(secondsAgo = 30, accuracy = 100f)))
        assertTrue(held.offer(fix(secondsAgo = 1, accuracy = null)))
    }

    @Test
    fun `a Location that can't be read is not kept, and nothing is thrown`() {
        assertFalse(held.offer(mockk<Location>()))
        assertFalse(held.offer(null))
        assertNull(held.newest())
    }

    @Test
    fun `per account - another account gets nothing, and sign-out drops it`() {
        val alices = fix(secondsAgo = 5)
        assertTrue(held.offer(alices))

        signedIn = BOB
        assertNull("never given to another account", held.newest())
        val bobs = fix(secondsAgo = 20)
        assertTrue("an older fix, but the first of this account's", held.offer(bobs))
        assertSame(bobs, held.newest())

        signedIn = ALICE.uppercase()
        assertNull("Alice's was replaced by Bob's, never handed back", held.newest())

        signedIn = BOB
        held.clear()
        assertNull(held.newest())
    }

    @Test
    fun `the same account in another spelling is the same account`() {
        val alices = fix(secondsAgo = 5)
        assertTrue(held.offer(alices))
        signedIn = "{${ALICE.uppercase()}}"
        assertSame(alices, held.newest())
    }

    @Test
    fun `nobody signed in, or an id that is no account - nothing kept, nothing given`() {
        signedIn = null
        assertFalse(held.offer(fix(secondsAgo = 1)))
        assertNull(held.newest())
        signedIn = "not-an-account"
        assertFalse(held.offer(fix(secondsAgo = 1)))
        assertNull(held.newest())
    }

    @Test
    fun `newerOf gives the held fix only when it is newer than the candidate`() {
        val sendNow = fix(secondsAgo = 5)
        held.offer(sendNow)

        val staleCached = fix(secondsAgo = 600)
        assertSame("the worker's cached fix loses to the newer one", sendNow, held.newerOf(staleCached))

        val fresher = fix(secondsAgo = 1)
        assertSame(fresher, held.newerOf(fresher))
        assertSame("and it is kept", fresher, held.newest())

        held.clear()
        val only = fix(secondsAgo = 900)
        assertSame("nothing held: the candidate as it is", only, held.newerOf(only))
    }

    /**
     * A send-now indoors goes out at 150 m, which is not kept; its time is noted all the same, and
     * a check-in of an older fix would go back on it.
     */
    @Test
    fun `a live send is noted whatever the guards say, and re-sending an older fix would go back on it`() {
        val precise = fix(secondsAgo = 120)
        held.elapsedNanos = { NOW - 115 * SECOND } // kept when it was fresh, as the service offers it
        assertTrue(held.offer(precise))
        held.elapsedNanos = { NOW }
        val indoors = fix(secondsAgo = 10, accuracy = 150f)
        assertFalse("too rough to keep", held.offer(indoors))
        held.noteSent(indoors)

        assertSame("the payload stays the precise fix", precise, held.newest())
        assertTrue("but it is older than a position already sent", held.goesBack(precise))
        assertFalse(held.goesBack(indoors))
        assertFalse("a newer fix goes back on nothing", held.goesBack(fix(secondsAgo = 1)))

        held.noteSent(fix(secondsAgo = 300))
        assertTrue("an older send changes nothing", held.goesBack(precise))
    }

    @Test
    fun `the newest sent is per account, needs the shared clock, and goes at sign-out`() {
        val old = fix(secondsAgo = 60)
        held.noteSent(fix(secondsAgo = 0, at = 0L))
        held.noteSent(fix(secondsAgo = 0, at = NOW + 1))
        held.noteSent(mockk<Location>())
        assertFalse("nothing noted without a time on the shared clock", held.goesBack(old))

        held.noteSent(fix(secondsAgo = 5))
        assertTrue(held.goesBack(old))
        signedIn = BOB
        assertFalse("Alice's sends say nothing about Bob's check-ins", held.goesBack(old))
        signedIn = ALICE
        held.clear()
        assertFalse(held.goesBack(old))
    }

    @Test
    fun `goesBack - strictly older than the newest sent, and nothing sent goes back on nothing`() {
        assertTrue(NewestOwnFix.goesBack(payloadNanos = 1, sentNanos = 2))
        assertFalse(NewestOwnFix.goesBack(payloadNanos = 2, sentNanos = 2))
        assertFalse(NewestOwnFix.goesBack(payloadNanos = 3, sentNanos = 2))
        assertFalse(NewestOwnFix.goesBack(payloadNanos = 1, sentNanos = 0))
    }

    /** The tracking service takes in each fix kept here once (LocationTrackingService.takeOneShotFix). */
    @Test
    fun `each fix kept is told once, and one not kept is not told`() = runTest {
        val told = mutableListOf<Location>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { held.kept.collect { told += it } }
        val first = fix(secondsAgo = 20)
        val second = fix(secondsAgo = 5)
        held.offer(first)
        held.offer(second)
        held.offer(fix(secondsAgo = 10))
        held.offer(fix(secondsAgo = 5))
        held.offer(fix(secondsAgo = 1, accuracy = 500f))
        assertEquals(listOf(first, second), told)
    }

    @Test
    fun `many writers at once - the newest is what is held`() {
        val fixes = (1..400).map { fix(secondsAgo = 0, at = NOW - it * 1_000_000L) }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        fixes.shuffled().forEach { f -> pool.execute { start.await(); held.offer(f) } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertSame(fixes.first(), held.newest())
    }
}
