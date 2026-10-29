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
 * How far ahead of this phone's clock a sealed time may be and still be taken as live (the same
 * constant as the web app's and iOS's). A time beyond it is no fix of now: the replay guard
 * doesn't take or keep it, and the place alerts neither judge it nor keep it as their watermark.
 * Without the bound, one far-future time would drop every genuine fix after it, and silence a
 * member's alerts for good.
 */
object LiveTime {
    /** Ten minutes. */
    const val MAX_FUTURE_SKEW_MS = 600_000L

    /** Whether [timestamp] is no further ahead of [now] than a clock may be off. */
    fun plausible(timestamp: Long, now: Long): Boolean = timestamp <= now + MAX_FUTURE_SKEW_MS
}
