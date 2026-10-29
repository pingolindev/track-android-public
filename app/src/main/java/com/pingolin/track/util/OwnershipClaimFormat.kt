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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turning an ownership claim's deadline into something a worried person can act on.
 *
 * Pure and parameterised on the zone, locale and "now" so the behaviour that matters can
 * be asserted deterministically — the same reason `notificationRelativeLabel` is shaped
 * this way. Nothing here touches Android APIs.
 */
object OwnershipClaimFormat {

    /**
     * Parse an RFC3339 timestamp from the claim routes.
     *
     * Two formats have to be accepted from the SAME server. Its timestamps carry no
     * decimal point at all when the sub-second part is zero, and 3, 6 or 9 fractional
     * digits otherwise. So `...:56Z` and `...:56.123456Z` are both ordinary answers,
     * and a parser that insists on one silently loses the other. Timestamps have
     * microsecond precision, which makes the whole-second case rare rather than
     * impossible — easy to miss, in the one code path whose failure hides a deadline.
     *
     * The [OffsetDateTime] fallback covers a `+00:00` spelling of the offset in place of
     * `Z`. Returns null rather than throwing: a claim whose deadline cannot be read must
     * still reach the owner with its refuse control, just without a date.
     */
    fun parseTimestamp(raw: String?): Instant? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** The moment silence completes [claim], or null if the server sent no usable one. */
    fun deadline(claim: OwnershipClaimResponse?): Instant? = parseTimestamp(claim?.resolvesAt)

    /**
     * The deadline as a real date and time in the DEVICE's zone.
     *
     * The zone is the whole point. The server's own push renders this same instant as
     * a day, month name and year in UTC, so for anybody far enough east or west that
     * date can be a different calendar day from theirs. The app is the surface that
     * can get it right, and a zone-less formatter here would instead read the instant
     * as local time and move the deadline by the offset outright.
     */
    fun deadlineText(
        deadline: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
        .format(deadline)

    /**
     * Whole days from [now] until [deadline], floored, and never negative.
     *
     * Floored rather than rounded so the number never overstates the time left: with
     * thirty hours to go this says "1 day", which is the reading that keeps somebody from
     * putting it off. A claim already past its deadline reports 0 — the server settles on
     * its own schedule, so "due now" is the honest answer, not a negative count.
     */
    fun daysRemaining(deadline: Instant, now: Instant): Long =
        ChronoUnit.DAYS.between(now, deadline).coerceAtLeast(0L)

    /**
     * "14 days left" / "1 day left" / "Due today" — the urgency, in three words.
     *
     * Sits beside [deadlineText] rather than replacing it: a relative count is what makes
     * someone act, an absolute date is what they need in order to plan, and the whole
     * fourteen-day window exists for people who are away and cannot do both from memory.
     */
    fun daysRemainingText(deadline: Instant, now: Instant): String =
        when (val days = daysRemaining(deadline, now)) {
            0L -> "Due today"
            1L -> "1 day left"
            else -> "$days days left"
        }
}
