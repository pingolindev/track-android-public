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

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.util.ApproximateMembersStore.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who is drawn as an area, and how large: the largest radius seen while a member shares
 * approximately, forgotten when they stop. What the latest-locations response says, one
 * response at a time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApproximateMembersStoreTest {

    private companion object {
        const val CIRCLE = "c0ffee00-0000-4000-8000-0000000000c1"
        const val ALEX = "a1e40000-0000-4000-8000-0000000000a1"
        const val ADA = "ada00000-0000-4000-8000-0000000000ad"
    }

    private val store = ApproximateMembersStore.inMemory()

    private fun approximate(user: String, miles: Int?) = Report(user, "approximate", miles)

    @Test
    fun `an approximate member keeps the largest radius seen, so the area holds still`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 3)))
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))

        store.record(CIRCLE, listOf(approximate(ALEX, 4)))
        assertEquals(4, store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `an approximate row without a radius is the senders' default mile`() {
        store.record(CIRCLE, listOf(approximate(ALEX, null)))
        assertEquals(1, store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `exact, paused or an older server's silence forgets the member, and a new bubble starts afresh`() {
        for (mode in listOf("exact", "paused", null)) {
            store.record(CIRCLE, listOf(approximate(ALEX, 5)))
            store.record(CIRCLE, listOf(Report(ALEX, mode, null)))
            assertNull("after $mode", store.radiusMiles(CIRCLE, ALEX))
        }
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        assertEquals(2, store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `a member missing from a response is kept - their last point is still a snapped one`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 3), Report(ADA, "exact", null)))
        store.record(CIRCLE, listOf(Report(ADA, "exact", null)))
        assertEquals(3, store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `one member's rows from two devices and two spellings are one member, at the larger radius`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 2), approximate(ALEX.uppercase(), 3)))
        assertEquals(3, store.radiusMiles(CIRCLE.uppercase(), ALEX))
    }

    @Test
    fun `a nonsensical radius still draws an area, never a smaller one`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 0), approximate(ADA, -4)))
        assertEquals(1, store.radiusMiles(CIRCLE, ALEX))
        assertEquals(1, store.radiusMiles(CIRCLE, ADA))
        store.record(CIRCLE, listOf(approximate(ALEX, Int.MAX_VALUE)))
        assertEquals(ApproximateMembersStore.MAX_RADIUS_MILES, store.radiusMiles(CIRCLE, ALEX))
    }

    @Test
    fun `circles are separate - approximate in one, a pin in another`() {
        val other = "0be00000-0000-4000-8000-0000000000b2"
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        store.record(other, listOf(Report(ALEX, "exact", null)))
        assertEquals(2, store.radiusMiles(CIRCLE, ALEX))
        assertNull(store.radiusMiles(other, ALEX))
    }

    @Test
    fun `it survives the app being closed, and sign-out leaves nothing for the next account`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        ApproximateMembersStore(context).record(CIRCLE, listOf(approximate(ALEX, 2)))

        val reopened = ApproximateMembersStore(context)
        assertEquals(2, reopened.radiusMiles(CIRCLE, ALEX))

        reopened.clear()
        assertTrue(reopened.areas.value.isEmpty())
        assertNull(ApproximateMembersStore(context).radiusMiles(CIRCLE, ALEX))
    }

    // ===== an area is never centred on a precise point =====

    private val mile = com.pingolin.track.domain.repository.LocationRepository.METERS_PER_MILE
    private fun cell(r: Int) = r * mile / 111320.0
    private fun snappedLat(lat: Double, r: Int) = Math.round(lat / cell(r)) * cell(r)
    private fun onGrid(lat: Double, r: Int) = Math.abs(lat / cell(r) - Math.rint(lat / cell(r))) < 1e-9

    /** A precise fix: on none of the 25 sender grids (checked, not assumed). */
    private val preciseLat = 37.3364512
    private val preciseLng = -122.0110021

    @Test
    fun `a precise point labelled approximate is snapped onto the reported grid`() {
        assertFalse(ApproximateMembersStore.onSenderGrid(preciseLat))
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))

        val shown = store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng)

        assertEquals(com.pingolin.track.domain.repository.LocationRepository.coarsenLocation(preciseLat, preciseLng, 2 * mile), shown)
        assertTrue(onGrid(shown.first, 2))
        assertTrue(shown.first != preciseLat)
    }

    @Test
    fun `a point a sender snapped is drawn exactly as it came, whatever radius is reported`() {
        // Snapped by a sender whose radius is 3; the server's rounded jitter reports 2.
        val lat = snappedLat(preciseLat, 3)
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        assertEquals(lat to preciseLng, store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, lat, preciseLng))
    }

    @Test
    fun `a sender's point is still on its grid after the payload's packing`() {
        // Latitude travels as i32 × 1e7 (mobile SDK location codec).
        for (r in 1..25) {
            assertTrue("r=$r", ApproximateMembersStore.onSenderGrid(Math.round(snappedLat(preciseLat, r) * 1e7) / 1e7))
        }
    }

    @Test
    fun `the snapped point holds for its fix whatever later responses report, and a new fix is snapped afresh`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        val first = store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng)

        store.record(CIRCLE, listOf(approximate(ALEX, 3)))
        assertEquals(first, store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng))

        val next = store.displayPoint(CIRCLE, ALEX, "dev-1", 2_000L, preciseLat, preciseLng)
        assertTrue(onGrid(next.first, 3))
    }

    @Test
    fun `a cold start that never saw the member exact guards the first point it is shown`() {
        // A new store, and the first response already labels the stored precise fix approximate.
        val cold = ApproximateMembersStore.inMemory()
        cold.record(CIRCLE, listOf(approximate(ALEX, null)))
        assertTrue(onGrid(cold.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng).first, 1))
    }

    @Test
    fun `someone not sharing approximately is drawn as is`() {
        store.record(CIRCLE, listOf(Report(ALEX, "exact", null)))
        assertEquals(preciseLat to preciseLng, store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng))
        assertEquals(preciseLat to preciseLng, store.displayPoint(CIRCLE, ADA, "dev-2", 1_000L, preciseLat, preciseLng))
    }

    /**
     * Accepted: a member who has since reduced their radius sent this fix snapped on their
     * older, larger grid. It is on a sender grid, so it is drawn as is, with the area of the
     * radius this device has seen; they may be up to 0.71 × 5 miles from it — outside a
     * 1.25-mile area — until their next fix. Coarse either way: nothing precise is shown.
     */
    @Test
    fun `a fix snapped under an older larger radius is drawn as is with the smaller area`() {
        val lat = snappedLat(preciseLat, 5)
        val reduced = ApproximateMembersStore.inMemory()
        reduced.record(CIRCLE, listOf(approximate(ALEX, 1)))
        assertEquals(lat to preciseLng, reduced.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, lat, preciseLng))
        assertEquals(1, reduced.radiusMiles(CIRCLE, ALEX))
    }

    // ===== nothing outlives the circle or the membership it is about =====

    @Test
    fun `a circle this account is no longer in is forgotten, with its held points`() {
        val other = "0be00000-0000-4000-8000-0000000000b2"
        store.record(CIRCLE, listOf(approximate(ALEX, 2)))
        store.record(other, listOf(approximate(ADA, 4)))
        val held = store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng)

        store.retainCircles(listOf(other.uppercase()))

        assertNull(store.radiusMiles(CIRCLE, ALEX))
        assertEquals(4, store.radiusMiles(other, ADA))
        // Back in the circle later, the same fix is snapped afresh: nothing was held over.
        store.record(CIRCLE, listOf(approximate(ALEX, 3)))
        val again = store.displayPoint(CIRCLE, ALEX, "dev-1", 1_000L, preciseLat, preciseLng)
        assertTrue(again != held)
        assertTrue(onGrid(again.first, 3))
    }

    @Test
    fun `a member no longer in the circle's roster is forgotten`() {
        store.record(CIRCLE, listOf(approximate(ALEX, 2), approximate(ADA, 3)))
        store.retainMembers(CIRCLE, listOf(ADA.uppercase()))
        assertNull(store.radiusMiles(CIRCLE, ALEX))
        assertEquals(3, store.radiusMiles(CIRCLE, ADA))
    }

    @Test
    fun `leaving or deleting a circle forgets it on disk too`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val onDisk = ApproximateMembersStore(context)
        onDisk.record(CIRCLE, listOf(approximate(ALEX, 2)))

        onDisk.forgetCircle(CIRCLE)

        assertNull(onDisk.radiusMiles(CIRCLE, ALEX))
        assertNull(ApproximateMembersStore(context).radiusMiles(CIRCLE, ALEX))
        assertTrue(ApproximateMembersStore(context).areas.value.isEmpty())
    }
}
