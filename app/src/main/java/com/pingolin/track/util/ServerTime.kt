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

import java.time.OffsetDateTime

/**
 * A time the server sent as RFC 3339, in epoch ms.
 *
 * The server writes two spellings: "2025-01-15T17:00:00Z" and "2025-01-15T17:00:00+00:00"
 * (".123456+00:00" with fractions). `Instant.parse` reads only "Z" before Android 14
 * (JDK-8166138), and a unit test on the desktop JDK, which reads both, can't see that.
 * `OffsetDateTime.parse` reads both, and any offset, on every API level.
 */
object ServerTime {

    /** [raw] in epoch ms; null if absent, blank or unreadable. What null means is the caller's call. */
    fun epochMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
    }
}
