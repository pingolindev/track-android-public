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

package com.pingolin.track.ui.main

/**
 * Whether this opening of the app asks for "Allow all the time" again: someone who chose
 * "Not now" is asked again at the next app open.
 *
 * Asked when someone who shares with a circle has only "while using the app": without
 * the background permission their location stops as soon as the app is closed. At most
 * once per opening, and "Not now" only puts it off to the next one. Pure, so the rule is
 * unit-tested; MainActivity supplies the facts.
 */
internal object AllTheTimeAsk {

    /** Android 10 (API 29) is where "Allow all the time" became its own permission. */
    private const val FIRST_SDK_WITH_BACKGROUND_LOCATION = 29

    /**
     * A return within this long of leaving is the same opening: a file picker, a
     * browser tab, the settings page this question sent them to. Longer is a new one.
     */
    const val SAME_OPENING_MS = 30_000L

    fun due(
        sdkInt: Int,
        preciseGranted: Boolean,
        backgroundGranted: Boolean,
        sharesWithACircle: Boolean,
        askedThisOpening: Boolean,
    ): Boolean =
        sdkInt >= FIRST_SDK_WITH_BACKGROUND_LOCATION &&
            preciseGranted &&
            !backgroundGranted &&
            sharesWithACircle &&
            !askedThisOpening

    /** Whether coming back after [awayMs] in the background is a new opening. */
    fun isNewOpening(awayMs: Long): Boolean = awayMs > SAME_OPENING_MS
}
