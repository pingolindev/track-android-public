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

import com.pingolin.track.data.remote.dto.OwnershipClaimResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

/**
 * The deadline an owner reads off the claim screen.
 *
 * Two things can go wrong here and neither announces itself. A parser that accepts only
 * one of chrono's two timestamp spellings drops the deadline entirely; a formatter that
 * ignores the zone shows the wrong day. Both end with somebody not refusing in time.
 */
class OwnershipClaimFormatTest {

    // 2026-09-06T23:30:00Z — late enough in UTC that any eastern zone is already
    // on the following day, which is what makes the zone assertions below mean something.
    private val lateUtcDeadline = Instant.parse("2026-09-06T23:30:00Z")

    /**
     * The server writes sub-second digits only when they are non-zero (chrono's
     * `SecondsFormat::AutoSi`), so BOTH of these come from the same server on
     * different days. With microsecond timestamps the whole-second spelling is rare —
     * and a parse failure that shows up on roughly one claim in a million is one
     * nobody will ever reproduce on demand, so it has to be excluded by construction
     * rather than by testing against a server.
     */
    @Test
    fun `both of chrono's timestamp spellings parse`() {
        val withFraction = OwnershipClaimFormat.parseTimestamp("2026-09-06T23:30:00.123456Z")
        val withoutFraction = OwnershipClaimFormat.parseTimestamp("2026-09-06T23:30:00Z")

        assertEquals(Instant.parse("2026-09-06T23:30:00.123456Z"), withFraction)
        assertEquals(lateUtcDeadline, withoutFraction)
    }

    /** A `+00:00` offset is the same instant as `Z` and must not be rejected. */
    @Test
    fun `an explicit zero offset parses to the same instant as Z`() {
        assertEquals(
            lateUtcDeadline,
            OwnershipClaimFormat.parseTimestamp("2026-09-06T23:30:00+00:00"),
        )
    }

    /**
     * An unreadable deadline degrades to null instead of throwing. The claim still has to
     * reach the owner with its refuse control — a date they cannot see is a much smaller
     * problem than a screen that fails to render.
     */
    @Test
    fun `unusable input degrades to null rather than throwing`() {
        assertNull(OwnershipClaimFormat.parseTimestamp(null))
        assertNull(OwnershipClaimFormat.parseTimestamp(""))
        assertNull(OwnershipClaimFormat.parseTimestamp("   "))
        assertNull(OwnershipClaimFormat.parseTimestamp("next Tuesday"))
        assertNull(OwnershipClaimFormat.parseTimestamp("2026-09-06"))
    }

    /** The deadline is pulled off the DTO's own field, whatever else the claim carries. */
    @Test
    fun `the deadline comes from resolves_at`() {
        val claim = OwnershipClaimResponse(
            claimId = "c1",
            resolvesAt = "2026-09-06T23:30:00Z",
            requestedAt = "2026-08-23T23:30:00Z",
        )
        assertEquals(lateUtcDeadline, OwnershipClaimFormat.deadline(claim))
        assertNull(OwnershipClaimFormat.deadline(OwnershipClaimResponse(claimId = "c1")))
        assertNull(OwnershipClaimFormat.deadline(null))
    }

    /**
     * The zone is the point of the whole helper. The same instant is the 6th in UTC and
     * the 7th in Auckland, and the server's own push says the 6th because it formats in
     * UTC — so the app rendering it in the device's zone is the only place a user in New
     * Zealand is told the right day.
     */
    @Test
    fun `the deadline renders in the device's zone, not UTC`() {
        val inUtc = OwnershipClaimFormat.deadlineText(
            lateUtcDeadline, ZoneId.of("UTC"), Locale.UK,
        )
        val inAuckland = OwnershipClaimFormat.deadlineText(
            lateUtcDeadline, ZoneId.of("Pacific/Auckland"), Locale.UK,
        )

        assertTrue("UTC should read as the 6th: $inUtc", inUtc.contains("6 September 2026"))
        assertTrue(
            "Auckland is a day ahead and should read as the 7th: $inAuckland",
            inAuckland.contains("7 September 2026"),
        )
        assertNotEquals(inUtc, inAuckland)
    }

    /**
     * Floored, so the number never overstates what is left. Thirty hours reads as one day
     * — the answer that gets somebody to act today rather than tomorrow.
     */
    @Test
    fun `days remaining is floored, never rounded up`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")

        assertEquals(1L, OwnershipClaimFormat.daysRemaining(now.plusSeconds(30 * 3600), now))
        assertEquals(0L, OwnershipClaimFormat.daysRemaining(now.plusSeconds(23 * 3600), now))
        assertEquals(14L, OwnershipClaimFormat.daysRemaining(now.plusSeconds(14 * 86400), now))
    }

    /**
     * A claim past its deadline reports 0, not a negative. The server settles a claim on
     * its own schedule, so the window closing is not the same moment as the transfer —
     * and "-2 days left" would read as though the circle were already gone when refusing
     * may still work.
     */
    @Test
    fun `an overdue claim reports zero rather than a negative count`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")

        assertEquals(0L, OwnershipClaimFormat.daysRemaining(now.minusSeconds(2 * 86400), now))
        assertEquals("Due today", OwnershipClaimFormat.daysRemainingText(now.minusSeconds(86400), now))
    }

    /** The urgency line, including the singular that a naive "%d days" gets wrong. */
    @Test
    fun `the remaining-days copy reads correctly at each boundary`() {
        val now = Instant.parse("2026-09-05T12:00:00Z")

        assertEquals("14 days left", OwnershipClaimFormat.daysRemainingText(now.plusSeconds(14 * 86400), now))
        assertEquals("1 day left", OwnershipClaimFormat.daysRemainingText(now.plusSeconds(86400), now))
        assertEquals("Due today", OwnershipClaimFormat.daysRemainingText(now.plusSeconds(3600), now))
    }
}
