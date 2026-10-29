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

package com.pingolin.track.domain.sos

import android.location.Location
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.location.LocationProvider
import com.pingolin.track.location.NewestOwnFix
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.util.ApproximateIntervals
import com.pingolin.track.util.PauseIntervals
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What an SOS of this phone's seals at the raise: (a) a fix taken after the press, exact; else
 * (b) the newest held fix as its circle allowed it when it was taken; else (c) a marker.
 */
class SosPositionChooserTest {

    private companion object {
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val PRESS = 1_800_000_000_000L
        const val ELAPSED_AT_PRESS = 9_000_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }

    private var wall = PRESS
    private var elapsed = ELAPSED_AT_PRESS
    private var lastKnown: Location? = null
    private var cause = false

    private val session = mockk<SessionManager> { every { getUserId() } returns ME }
    private val newest = NewestOwnFix(session).also { it.elapsedNanos = { elapsed } }
    private val provider = mockk<LocationProvider> { coEvery { lastLocation() } answers { lastKnown } }
    private var pauses = PauseIntervals.inMemory(owner = { ME }, now = { wall })
    private var bubbles = ApproximateIntervals.inMemory(owner = { ME }, now = { wall })

    private fun chooser() = SosPositionChooser(newest, provider, pauses, bubbles) { cause }.also {
        it.wallNow = { wall }
        it.elapsedNanos = { elapsed }
    }

    /** A fix taken [agoMs] before now (negative: in the future, on the elapsed
     * clock).
     */
    private fun fix(lat: Double, lng: Double, agoMs: Long, accuracy: Float? = 8f): Location = mockk {
        every { latitude } returns lat
        every { longitude } returns lng
        every { elapsedRealtimeNanos } returns elapsed - agoMs * 1_000_000L
        every { hasAccuracy() } returns (accuracy != null)
        every { this@mockk.accuracy } returns (accuracy ?: 0f)
    }

    /** [location] offered to NewestOwnFix when it was taken, [agoMs] before now: it holds it since. */
    private fun held(lat: Double, lng: Double, agoMs: Long) {
        val now = elapsed
        elapsed = now - agoMs * 1_000_000L
        newest.offer(fix(lat, lng, 0))
        elapsed = now
    }

    /** A privacy row of the circle came in at [at]: sharing, with [pausedSpans] and [bubbleSpans] in its history. */
    private fun privacyRead(
        at: Long,
        pausedSpans: List<PauseIntervals.Interval> = emptyList(),
        bubbleSpans: List<ApproximateIntervals.Span> = emptyList(),
        pausedNow: Boolean = false,
        bubbleNow: Int? = null,
    ) {
        val was = wall
        wall = at
        pauses.observe(CIRCLE, notSharing = pausedNow, deadlineMs = null, server = pausedSpans)
        bubbles.observe(CIRCLE, radiusMiles = bubbleNow, deadlineMs = null, server = bubbleSpans)
        wall = was
    }

    // ---- (a) ----

    @Test
    fun `a fix taken after the press goes EXACT, even in a circle paused and approximate now`() = runTest {
        privacyRead(PRESS - 30_000, pausedSpans = listOf(PauseIntervals.Interval(PRESS - HOUR, null)), pausedNow = true,
            bubbleSpans = listOf(ApproximateIntervals.Span(PRESS - HOUR, null, 3)), bubbleNow = 3)
        wall = PRESS + 4_000
        elapsed = ELAPSED_AT_PRESS + 4_000_000_000L
        val seal = chooser().forRaise(CIRCLE, PRESS, inHand = fix(37.3349, -122.009, agoMs = 1_000, accuracy = 12f))
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS + 3_000, 12.0), seal)
    }

    @Test
    fun `one taken up to 2 s before the press counts as after it, from any source`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 2_000)
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS - 2_000, 8.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    // ---- (b) ----

    @Test
    fun `the newest held fix by when it was taken, dated now minus its age - never the raise time`() = runTest {
        held(37.30, -122.00, agoMs = 15 * MIN)
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN, accuracy = 6f)
        privacyRead(PRESS - 30_000)
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 6.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))

        // NewestOwnFix's, when it is the newer.
        held(37.31, -122.01, agoMs = 5 * MIN)
        assertEquals(SosSeal.Point(37.31, -122.01, PRESS - 5 * MIN, 8.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a held fix taken inside a pause of the circle is not sent - pending, or unavail on a definite cause`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        privacyRead(PRESS - 30_000, pausedSpans = listOf(PauseIntervals.Interval(PRESS - 20 * MIN, PRESS - 5 * MIN)))
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        cause = true
        assertEquals(SosSeal.UNAVAILABLE, chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `the pause the circle is in now - the held fix is skipped, and the first fix after the press goes exact`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        privacyRead(PRESS - 30_000, pausedSpans = listOf(PauseIntervals.Interval(PRESS - HOUR, null)), pausedNow = true)
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        assertEquals(
            SosSeal.Point(37.34, -122.01, PRESS, 8.0),
            chooser().forRaise(CIRCLE, PRESS, inHand = fix(37.34, -122.01, agoMs = 0)),
        )
    }

    @Test
    fun `a held fix the record can't place is not sent - no privacy read since it was taken, with the margin`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        // No read at all in this process (a restart).
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        // One read 59 s after it was taken: inside the margin.
        privacyRead(PRESS - 10 * MIN + 59_000)
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        // 60 s after: placed.
        privacyRead(PRESS - 10 * MIN + 60_000)
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 8.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a held fix is not sent while the pause record doesn't read`() = runTest {
        val damaged = DamagedPrefs().also { it.damaged = true }
        pauses = PauseIntervals.over(damaged, { ME }, { wall })
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        privacyRead(PRESS - 30_000)
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `the newest held fix decides - no older one is searched for`() = runTest {
        held(37.30, -122.00, agoMs = 25 * MIN)
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        privacyRead(PRESS - 30_000, pausedSpans = listOf(PauseIntervals.Interval(PRESS - 20 * MIN, PRESS - 5 * MIN)))
        assertEquals("the older one was taken before the pause, and isn't used", SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a held fix taken under a bubble goes only as that area - coarsened as the live path does, accuracy the radius`() = runTest {
        lastKnown = fix(37.33491234, -122.00912345, agoMs = 10 * MIN, accuracy = 9f)
        privacyRead(PRESS - 30_000, bubbleSpans = listOf(ApproximateIntervals.Span(PRESS - HOUR, PRESS - 2 * MIN, 2)))
        val radius = 2 * LocationRepository.METERS_PER_MILE
        val (lat, lng) = LocationRepository.coarsenLocation(37.33491234, -122.00912345, radius)
        assertEquals(SosSeal.Point(lat, lng, PRESS - 10 * MIN, radius), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `the bubble NOW doesn't apply to a held fix taken while exact`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        privacyRead(PRESS - 30_000, bubbleSpans = listOf(ApproximateIntervals.Span(PRESS - MIN, null, 3)), bubbleNow = 3)
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS - 10 * MIN, 8.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a held fix whose bubble history isn't known goes as the widest bubble, 25 mi`() = runTest {
        lastKnown = fix(37.3349, -122.009, agoMs = 10 * MIN)
        // The pause record is read, the bubbles never were.
        val was = wall
        wall = PRESS - 30_000
        pauses.observe(CIRCLE, notSharing = false, deadlineMs = null, server = emptyList())
        wall = was
        val radius = LocationRepository.MAX_BUBBLE_RADIUS_MILES * LocationRepository.METERS_PER_MILE
        val (lat, lng) = LocationRepository.coarsenLocation(37.3349, -122.009, radius)
        assertEquals(SosSeal.Point(lat, lng, PRESS - 10 * MIN, radius), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    // ---- (c) ----

    @Test
    fun `nothing held - pending, or unavail when location is off or not permitted`() = runTest {
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        cause = true
        assertEquals(SosSeal.UNAVAILABLE, chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `accuracy is sealed when the fix has one, and none only when it has none`() = runTest {
        val chooser = chooser()
        assertEquals(4.5, chooser.exact(fix(37.0, -122.0, agoMs = 0, accuracy = 4.5f))!!.accuracy!!, 0.0)
        assertNull(chooser.exact(fix(37.0, -122.0, agoMs = 0, accuracy = null))!!.accuracy)
    }

    @Test
    fun `a fix the shared clock can't date isn't used`() = runTest {
        val chooser = chooser()
        val fromBeforeABoot = mockk<Location> {
            every { latitude } returns 37.0
            every { longitude } returns -122.0
            every { elapsedRealtimeNanos } returns elapsed + 1
            every { hasAccuracy() } returns true
            every { accuracy } returns 5f
        }
        assertNull(chooser.exact(fromBeforeABoot))
        lastKnown = fromBeforeABoot
        assertEquals(SosSeal.PENDING, chooser.forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a fix NewestOwnFix took after the press is one the update may seal - one before it isn't`() {
        held(37.30, -122.00, agoMs = 5_000)
        assertNull(chooser().newestAfterPress(PRESS))
        wall = PRESS + 20_000
        elapsed = ELAPSED_AT_PRESS + 20_000_000_000L
        held(37.31, -122.01, agoMs = 1_000)
        assertEquals(SosSeal.Point(37.31, -122.01, PRESS + 19_000, 8.0), chooser().newestAfterPress(PRESS))
    }

    // ---- a raise that waited carries what the window gave, never later ----

    @Test
    fun `a raise that waited past the window never seals a fix from after it - only one from inside it`() = runTest {
        // Sent 45 minutes after the press; the phone holds a fix from 40 minutes after it.
        wall = PRESS + 45 * MIN
        elapsed = ELAPSED_AT_PRESS + 45 * MIN * 1_000_000L
        lastKnown = fix(37.40, -122.10, agoMs = 5 * MIN)
        privacyRead(PRESS - 30_000)
        assertEquals("nothing from the window: unavail, never the fix from after it", SosSeal.UNAVAILABLE,
            chooser().forRaise(CIRCLE, PRESS, inHand = null))

        // One taken inside the window, 3 minutes after the press, is what it carries: exact.
        held(37.3349, -122.009, agoMs = 42 * MIN)
        assertEquals(SosSeal.Point(37.3349, -122.009, PRESS + 3 * MIN, 8.0), chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }

    @Test
    fun `a raise still pending when it goes after the window carries unavail - inside it, pending`() = runTest {
        wall = PRESS + 9 * MIN
        elapsed = ELAPSED_AT_PRESS + 9 * MIN * 1_000_000L
        assertEquals(SosSeal.PENDING, chooser().forRaise(CIRCLE, PRESS, inHand = null))
        wall = PRESS + 10 * MIN
        elapsed = ELAPSED_AT_PRESS + 10 * MIN * 1_000_000L
        assertEquals(SosSeal.UNAVAILABLE, chooser().forRaise(CIRCLE, PRESS, inHand = null))
    }
}

