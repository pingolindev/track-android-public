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

package com.pingolin.track.ui.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tests for [notificationRelativeLabel] — the local-timezone relative day
 * label for notification rows. Days are compared as local calendar days, never
 * as UTC day buckets, which would label something that happened today
 * "Yesterday".
 */
class NotificationRelativeLabelTest {

    // UTC-7 in summer (PDT): a zone where the UTC day rolls over in the
    // evening.
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val utc = ZoneId.of("UTC")

    private fun epochMillisAt(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `event earlier today in PDT shows Today even after the UTC day rolled over`() {
        // now = Jun 14 20:12 PDT == Jun 15 03:12 UTC, so the UTC day
        // bucket already reads Jun 15. The arrival was Jun 14 09:00
        // PDT (== Jun 14 16:00 UTC) — UTC bucket Jun 14. A UTC
        // subtraction would say "Yesterday"; local-day math must
        // produce "Today".
        val now = LocalDateTime.of(2026, 6, 14, 20, 12).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 6, 14, 9, 0, pacific)
        assertEquals("Today", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `event late yesterday in PDT shows Yesterday`() {
        val now = LocalDateTime.of(2026, 6, 14, 20, 12).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 6, 13, 22, 0, pacific)
        assertEquals("Yesterday", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `same instant shows Today`() {
        val now = LocalDateTime.of(2026, 6, 14, 12, 0).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 6, 14, 12, 0, pacific)
        assertEquals("Today", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `future timestamp from clock skew shows Today`() {
        val now = LocalDateTime.of(2026, 6, 14, 12, 0).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 6, 14, 12, 5, pacific) // 5 min ahead of now
        assertEquals("Today", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `three days ago`() {
        val now = LocalDateTime.of(2026, 6, 14, 12, 0).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 6, 11, 12, 0, pacific)
        assertEquals("3 days ago", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `two weeks ago`() {
        val now = LocalDateTime.of(2026, 6, 14, 12, 0).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 5, 31, 12, 0, pacific) // 14 local days back
        assertEquals("2 weeks ago", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `two months ago`() {
        val now = LocalDateTime.of(2026, 6, 14, 12, 0).atZone(pacific).toInstant()
        val event = epochMillisAt(2026, 4, 14, 12, 0, pacific) // 61 local days back
        assertEquals("2 months ago", notificationRelativeLabel(event, pacific, now))
    }

    @Test
    fun `UTC user earlier today still shows Today`() {
        val now = LocalDateTime.of(2026, 6, 14, 23, 0).atZone(utc).toInstant()
        val event = epochMillisAt(2026, 6, 14, 1, 0, utc)
        assertEquals("Today", notificationRelativeLabel(event, utc, now))
    }

    // ===== notificationTimestampLabel: day + clock time =====

    /**
     * A place-alert row's text, such as "… left 3 minutes ago", is a
     * relative phrase that PlaceAlertRules.describe freezes at creation.
     * Hours later it would read wrongly under a bare "Today", so the row
     * needs an absolute anchor for when the notification itself landed.
     */
    @Test
    fun timestampLabel_appendsTheClockTimeToTheDayLabel() {
        val event = epochMillisAt(2026, 7, 28, 19, 25, pacific)
        val now = epochMillisAt(2026, 7, 28, 23, 40, pacific).let {
            java.time.Instant.ofEpochMilli(it)
        }

        val label = notificationTimestampLabel(
            event,
            formatTime = { "7:25 PM" },
            zone = pacific,
            now = now,
        )

        assertEquals("Today, 7:25 PM", label)
    }

    /** The day half comes from notificationRelativeLabel. */
    @Test
    fun timestampLabel_keepsTheLocalDayLabelForOlderRows() {
        val event = epochMillisAt(2026, 7, 27, 23, 30, pacific)
        val now = java.time.Instant.ofEpochMilli(epochMillisAt(2026, 7, 28, 0, 30, pacific))

        assertEquals(
            "Yesterday, 11:30 PM",
            notificationTimestampLabel(event, { "11:30 PM" }, pacific, now),
        )
    }

    /**
     * The formatter is injected precisely so a 24-hour device renders 24-hour
     * time; nothing in the label may hardcode a 12-hour assumption.
     */
    @Test
    fun timestampLabel_usesWhateverTheDeviceFormatterProduces() {
        val event = epochMillisAt(2026, 7, 28, 19, 25, pacific)
        val now = java.time.Instant.ofEpochMilli(epochMillisAt(2026, 7, 28, 20, 0, pacific))

        assertEquals(
            "Today, 19:25",
            notificationTimestampLabel(event, { "19:25" }, pacific, now),
        )
    }
}
