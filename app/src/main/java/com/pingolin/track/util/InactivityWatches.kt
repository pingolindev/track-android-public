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

/**
 * What an inactivity rule counts as a sign of life: the server's `inactivity_alerts.watches`
 * (track migration 196), fixed when the rule is made.
 *
 * Each evaluator judges its own kind only. A kind this build doesn't know is judged by neither:
 * judged by location updates, a rule about something else would go off when location stops and
 * never when its own sign of life does.
 */
object InactivityWatches {
    /** Location updates: every rule made before check-in rules, and a row that doesn't say. */
    const val LOCATION = "location"

    /** Check-ins (the "Check in" button): the rule goes off when the member hasn't checked in for its limit. */
    const val CHECK_IN = "check_in"

    /** Whether [watches] is a location rule's; absent (an older server's row) is. */
    fun isLocation(watches: String?): Boolean = watches == null || watches == LOCATION

    fun isCheckIn(watches: String?): Boolean = watches == CHECK_IN
}

/**
 * A check-in rule's kind: the server's `inactivity_alerts.check_in_mode` (track migration 197),
 * fixed when the rule is made. Two kinds replace the rolling rule.
 */
object CheckInModes {
    /** "Check in within N hours": its hours after the rule's start; it ends at the next check-in. */
    const val ONCE = "once"

    /** "Check in by a time of day on chosen days" (CheckInSchedule). */
    const val SCHEDULED = "scheduled"

    /**
     * The kinds this app judges, for the lists' `check_in_modes`: without it the server lists one-time
     * rules only, for the apps that would judge a scheduled rule by its hours.
     */
    const val JUDGED = "$ONCE,$SCHEDULED"

    /** Scheduled only when it says so: a check-in rule that doesn't say (an older server's, or a row from before Room v51) is one-time. */
    fun isScheduled(mode: String?): Boolean = mode == SCHEDULED
}
