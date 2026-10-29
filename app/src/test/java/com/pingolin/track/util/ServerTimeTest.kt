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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server times in both spellings the server writes. The "+00:00" cases pass on this JVM
 * whatever the parser (its Instant.parse reads offsets, JDK 12 and later); Android's before 14
 * reads only "Z". Hence the look at the source as well.
 */
class ServerTimeTest {

    /** 2026-10-14T17:00:00Z. */
    private val five = 1_791_997_200_000L

    @Test
    fun `both spellings, fractions and any offset`() {
        assertEquals("chrono to_rfc3339()", five, ServerTime.epochMs("2026-10-14T17:00:00+00:00"))
        assertEquals(five + 123, ServerTime.epochMs("2026-10-14T17:00:00.123456+00:00"))
        assertEquals("serde DateTime<Utc>", five, ServerTime.epochMs("2026-10-14T17:00:00Z"))
        assertEquals(five + 123, ServerTime.epochMs("2026-10-14T17:00:00.123Z"))
        assertEquals("the offset is applied, not dropped", five, ServerTime.epochMs("2026-10-14T19:00:00+02:00"))
    }

    @Test
    fun `absent or unreadable is null, never now`() {
        assertNull(ServerTime.epochMs(null))
        assertNull(ServerTime.epochMs(""))
        assertNull(ServerTime.epochMs("  "))
        assertNull(ServerTime.epochMs("next week"))
        assertNull("no offset: not a time anyone can place", ServerTime.epochMs("2026-10-14T17:00:00"))
    }

    @Test
    fun `the parser is not Instant parse, which reads only Z before Android 14`() {
        val src = java.io.File("src/main/java/com/pingolin/track/util/ServerTime.kt").readText()
        val start = src.indexOf("fun epochMs(")
        assertTrue("epochMs not found — update this test with the new name", start >= 0)
        val body = src.substring(start).substringBefore("\n\n")
        assertTrue(body, body.contains("OffsetDateTime.parse("))
        assertFalse(body, body.contains("Instant.parse("))
    }

    @Test
    fun `place alerts and notification stamps read server times only through it`() {
        for (path in listOf(
            "src/main/java/com/pingolin/track/domain/repository/PlaceRepository.kt",
            "src/main/java/com/pingolin/track/util/AlertHandler.kt",
        )) {
            val src = java.io.File(path).readText()
            assertTrue(path, src.contains("ServerTime.epochMs("))
            assertFalse(path, src.contains("Instant.parse("))
        }
    }
}
