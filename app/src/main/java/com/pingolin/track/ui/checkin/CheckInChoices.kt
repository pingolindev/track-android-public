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

package com.pingolin.track.ui.checkin

import com.pingolin.track.domain.repository.CheckInRepository

/**
 * What each check-in choice sends, in one place.
 *
 * The type is a server contract, not a label: the server accepts only safe_arrival, arrived,
 * general and emergency_ok, and turns each into the sentence the whole circle reads.
 * emergency_ok reads as "…has confirmed they are OK." — an all-clear, never what a plain
 * "Checking In" means. A plain one is "general", the type iOS sends for its "General
 * Check-in". The type has no effect beyond its words: no server path, push or client keys
 * anything else on it.
 */
internal object CheckInChoices {

    data class Choice(val label: String, val type: String)

    /** The choices on the map's quick menu, the check-in screen and its dialog, in order. */
    val QUICK: List<Choice> = listOf(
        Choice("I'm Safe", CheckInRepository.TYPE_SAFE_ARRIVAL),
        Choice("Arrived", CheckInRepository.TYPE_ARRIVED),
        Choice("Checking In", CheckInRepository.TYPE_GENERAL),
    )

    /** "Checking In": a plain check-in. */
    val CHECKING_IN: String get() = QUICK.single { it.label == "Checking In" }.type

    /** A check-in in the person's own words is a plain one too. */
    const val MESSAGE_TYPE: String = CheckInRepository.TYPE_GENERAL

    /**
     * A check-in's icon and label in the list. emergency_ok arrives only from the web or an older
     * app, and reads as the server words it and the push titles it: an all clear.
     */
    fun label(type: String): Pair<String, String> = when (type) {
        CheckInRepository.TYPE_SAFE_ARRIVAL -> "✅" to "I'm Safe"
        CheckInRepository.TYPE_ARRIVED -> "🏠" to "Arrived"
        CheckInRepository.TYPE_GENERAL -> "💬" to "Checking In"
        CheckInRepository.TYPE_EMERGENCY_OK -> "✅" to "All Clear"
        else -> "💬" to "Check-In"
    }
}
