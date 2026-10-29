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

import com.pingolin.track.data.local.entity.SosAlertEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SOS position's pure rules: the two markers byte for byte, what this phone keeps of what it
 * sealed, the update rule's truth table and bounds, and what receivers make of a position.
 */
class SosPositionTest {

    private companion object {
        const val PRESS = 1_800_000_000_000L
        const val MIN = 60_000L
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
    }

    // ---- the markers ----

    @Test
    fun `the markers are exactly the contract's bytes - 22 each, the size of a packed position with accuracy`() {
        assertEquals("""{"position":"pending"}""", SosPositionMarker.PENDING)
        assertEquals("""{"position":"unavail"}""", SosPositionMarker.UNAVAILABLE)
        assertEquals(22, SosPositionMarker.PENDING.toByteArray(Charsets.UTF_8).size)
        assertEquals(22, SosPositionMarker.UNAVAILABLE.toByteArray(Charsets.UTF_8).size)
        assertEquals(SosPositionMarker.PENDING, SosPositionMarker.text(SosAlertEntity.POSITION_PENDING))
        assertEquals(SosPositionMarker.UNAVAILABLE, SosPositionMarker.text(SosAlertEntity.POSITION_UNAVAILABLE))
        // Never mistaken for a packed position, whose first byte is 0xB1.
        assertEquals('{'.code.toByte(), SosPositionMarker.PENDING.toByteArray(Charsets.UTF_8)[0])
    }

    @Test
    fun `only text byte-equal to a marker is one - anything else under location is garbage`() {
        assertEquals(SosAlertEntity.POSITION_PENDING, SosPositionMarker.read("""{"position":"pending"}"""))
        assertEquals(SosAlertEntity.POSITION_UNAVAILABLE, SosPositionMarker.read("""{"position":"unavail"}"""))
        for (garbage in listOf(
            """{"position":"pending"} """,
            """ {"position":"pending"}""",
            """{"position": "pending"}""",
            """{"position":"unavailable"}""",
            """{"position":"PENDING"}""",
            """{"unavailable":true}""",
            """{"position":"pending","x":1}""",
            "",
            "37.5,-122.25",
        )) {
            assertNull(garbage, SosPositionMarker.read(garbage))
        }
    }

    // ---- the record ----

    @Test
    fun `a record reads back as written, a point with or without accuracy and either marker`() {
        for (seal in listOf(
            SosSeal.Point(37.3349123456, -122.0090111, PRESS - 5 * MIN, 12.5),
            SosSeal.Point(-33.8688197, 151.2092955, PRESS, null),
            SosSeal.PENDING,
            SosSeal.UNAVAILABLE,
        )) {
            assertEquals(seal, SosSeal.decode(seal.encode()))
        }
        for (bad in listOf("", "p\t1\t2", "p\tx\t2\t3\t", "m\tunavailable", "m\tpending\textra", "q\t1")) {
            assertNull(bad, SosSeal.decode(bad))
        }
    }

    // ---- The update rule ----

    private fun point(takenAt: Long, accuracy: Double? = 10.0, lat: Double = 37.33) = SosSeal.Point(lat, -122.0, takenAt, accuracy)

    private fun next(current: SosSeal, fix: SosSeal.Point?, now: Long = PRESS + MIN, updates: Int = 0, cause: Boolean = false) =
        SosUpdateRule.next(current, fix, cause, PRESS, now, updates)

    @Test
    fun `(a) a marker gives way to a fix taken after the press, whatever its accuracy`() {
        val f = point(PRESS + 10_000, accuracy = 400.0)
        assertEquals(f, next(SosSeal.PENDING, f))
        assertEquals(f, next(SosSeal.UNAVAILABLE, f))
    }

    @Test
    fun `(b) a position taken more than a minute before the press gives way to any fix after it`() {
        val f = point(PRESS + 10_000, accuracy = 400.0)
        assertEquals(f, next(point(PRESS - MIN - 1, accuracy = 5.0), f))
        assertNull("within the minute: not old", next(point(PRESS - MIN, accuracy = 5.0), f))
    }

    @Test
    fun `(c) one worse than 100 m, or of no accuracy, or coarsened, gives way to a newer fix within 100 m`() {
        val good = point(PRESS + 20_000, accuracy = 100.0)
        assertEquals(good, next(point(PRESS + 5_000, accuracy = 100.5), good))
        assertEquals(good, next(point(PRESS + 5_000, accuracy = null), good))
        assertEquals("a held fix coarsened to a mile", good, next(point(PRESS - 30_000, accuracy = 1609.344), good))
        assertNull("the new one isn't good", next(point(PRESS + 5_000, accuracy = 300.0), point(PRESS + 20_000, accuracy = 150.0)))
        assertNull("the new one has no accuracy", next(point(PRESS + 5_000, accuracy = 300.0), point(PRESS + 20_000, accuracy = null)))
        assertNull("already good", next(point(PRESS + 5_000, accuracy = 100.0), point(PRESS + 20_000, accuracy = 5.0)))
        assertNull("never an older one over a newer", next(point(PRESS + 30_000, accuracy = 300.0), point(PRESS + 20_000, accuracy = 5.0)))
    }

    @Test
    fun `only a fix taken after the press counts - 2 s before it still does`() {
        assertEquals(point(PRESS - 2_000), next(SosSeal.PENDING, point(PRESS - 2_000)))
        assertNull(next(SosSeal.PENDING, point(PRESS - 2_001)))
        assertNull(next(point(PRESS - 10 * MIN), point(PRESS - 2_001)))
        assertTrue(SosUpdateRule.afterPress(point(PRESS - 2_000), PRESS))
        assertFalse(SosUpdateRule.afterPress(point(PRESS - 2_001), PRESS))
    }

    @Test
    fun `(d) pending gives way to unavail on a definite cause, and nothing else does`() {
        assertEquals(SosSeal.UNAVAILABLE, next(SosSeal.PENDING, null, cause = true))
        assertNull(next(SosSeal.PENDING, null, cause = false))
        assertNull("unavail already", next(SosSeal.UNAVAILABLE, null, cause = true))
        assertNull("a position", next(point(PRESS - 10 * MIN), null, cause = true))
        assertEquals("a fix in hand comes first", point(PRESS + 1), next(SosSeal.PENDING, point(PRESS + 1), cause = true))
    }

    @Test
    fun `(e) pending at the window's end is sealed unavail, and nothing else is sealed after it`() {
        val end = PRESS + SosUpdateRule.WINDOW_MS
        assertNull("just before", next(SosSeal.PENDING, null, now = end - 1))
        assertEquals(SosSeal.UNAVAILABLE, next(SosSeal.PENDING, null, now = end))
        assertEquals(SosSeal.UNAVAILABLE, next(SosSeal.PENDING, point(end - 1), now = end + 5 * MIN))
        assertNull(next(SosSeal.UNAVAILABLE, point(end - 1), now = end))
        assertNull("no following past the window", next(point(PRESS - 20 * MIN), point(end + 1), now = end + 1))
    }

    @Test
    fun `at most three updates by the rule, and none past ten minutes`() {
        val f = point(PRESS + 10_000)
        assertEquals(f, next(SosSeal.PENDING, f, updates = 2))
        assertNull(next(SosSeal.PENDING, f, updates = 3))
        assertNull(next(SosSeal.PENDING, null, cause = true, updates = 3))
        assertEquals(10 * MIN, SosUpdateRule.WINDOW_MS)
        assertEquals(f, next(SosSeal.UNAVAILABLE, f, now = PRESS + 10 * MIN - 1))
        assertNull(next(SosSeal.UNAVAILABLE, f, now = PRESS + 10 * MIN))
    }

    @Test
    fun `the phone keeps looking while a later fix could change it, and stops when one can't`() {
        val inWindow = PRESS + MIN
        assertTrue(SosUpdateRule.mayImprove(SosSeal.PENDING, PRESS, inWindow, 0))
        assertTrue(SosUpdateRule.mayImprove(SosSeal.UNAVAILABLE, PRESS, inWindow, 0))
        assertTrue("old", SosUpdateRule.mayImprove(point(PRESS - 2 * MIN), PRESS, inWindow, 0))
        assertTrue("coarse", SosUpdateRule.mayImprove(point(PRESS + 1_000, accuracy = 400.0), PRESS, inWindow, 0))
        assertFalse("good, after the press", SosUpdateRule.mayImprove(point(PRESS + 1_000, accuracy = 30.0), PRESS, inWindow, 0))
        assertFalse("three made", SosUpdateRule.mayImprove(SosSeal.PENDING, PRESS, inWindow, 3))
        assertFalse("past the window", SosUpdateRule.mayImprove(SosSeal.PENDING, PRESS, PRESS + 10 * MIN, 0))
    }

    // ---- what receivers make of it ----

    private fun row(
        fixAt: Long? = null,
        accuracy: Double? = null,
        state: String? = null,
        asked: Long = PRESS,
        lat: Double? = if (state == null) 37.33 else null,
    ) = SosAlertEntity(
        id = SOS, circleId = CIRCLE, userId = "u", status = "active",
        latitude = lat, longitude = lat?.let { -122.0 }, acknowledgedBy = "", createdAt = asked, resolvedAt = null,
        fixAt = fixAt, accuracy = accuracy, positionState = state,
    )

    @Test
    fun `pending reads pending until the SEALED raise time plus 12 minutes, then unavailable - unavail at once`() {
        val pending = row(state = SosAlertEntity.POSITION_PENDING)
        assertEquals(SosPosition.Pending, SosPosition.of(pending, PRESS))
        assertEquals(SosPosition.Pending, SosPosition.of(pending, PRESS + 12 * MIN - 1))
        assertEquals(SosPosition.Unavailable, SosPosition.of(pending, PRESS + 12 * MIN))
        assertEquals(SosPosition.Unavailable, SosPosition.of(row(state = SosAlertEntity.POSITION_UNAVAILABLE), PRESS))
        assertNull("no area for a marker", SosPosition.areaMeters(pending))
    }

    @Test
    fun `last known, as sent or updated by when it was taken, and a row with no time of its own reads as sent`() {
        assertEquals(SosPosition.LastKnown(PRESS - MIN - 1), SosPosition.of(row(fixAt = PRESS - MIN - 1), PRESS))
        assertEquals(SosPosition.AsSent, SosPosition.of(row(fixAt = PRESS - MIN), PRESS))
        assertEquals("a raise seals fixAt == asked", SosPosition.AsSent, SosPosition.of(row(fixAt = PRESS), PRESS))
        assertEquals(SosPosition.AsSent, SosPosition.of(row(fixAt = PRESS + MIN), PRESS))
        assertEquals(SosPosition.Updated(PRESS + MIN + 1), SosPosition.of(row(fixAt = PRESS + MIN + 1), PRESS))
        assertEquals("a row from before positions carried their time", SosPosition.AsSent, SosPosition.of(row(fixAt = null), PRESS))
    }

    @Test
    fun `an accuracy past 100 m is an area of that radius - never at or under 100 m, or without one`() {
        assertEquals(100.5, SosPosition.areaMeters(row(fixAt = PRESS, accuracy = 100.5))!!, 0.0)
        assertEquals(1609.344, SosPosition.areaMeters(row(fixAt = PRESS - 5 * MIN, accuracy = 1609.344))!!, 0.0)
        assertNull(SosPosition.areaMeters(row(fixAt = PRESS, accuracy = 100.0)))
        assertNull(SosPosition.areaMeters(row(fixAt = PRESS, accuracy = null)))
    }
}
