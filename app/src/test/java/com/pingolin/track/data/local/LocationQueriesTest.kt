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

package com.pingolin.track.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every query on the locations table is LIVE or HISTORY, and every LIVE one reads only rows the
 * circle's CURRENT key opened. A row only an older key opened is history: shown in history, never
 * a marker, a "last seen", a member sheet, a widget, a notification or the inactivity check. A
 * query added to LocationDao fails here until it is labelled below.
 */
class LocationQueriesTest {

    /** Feeds a live view: must read `keyCurrent = 1`. */
    private val live = setOf(
        "observeLatestByCircle",
        // A served "no" lowers the row the alert passes judge, the newest current one.
        "lowerAlertsConsent",
    )

    /** A day or time range, the cache's bookkeeping, or the upload queue: every row. */
    private val history = setOf(
        "getByTimeRange", "getPendingSync", "markSynced", "getCount",
        "deleteOlderThan", "deleteForMissingMembers", "deleteAll", "deleteByCircle",
        // The purge: which spellings a circle's rows carry, and what of a member goes.
        "userIdsIn", "deleteAllButNewest", "deleteMember",
        // Which fix times are held with a "no", so storing the same fix again keeps it: every row.
        "refusedAt",
    )

    /** Each @Query on LocationDao, by the function it annotates. */
    private fun queries(): Map<String, String> {
        val source = File("src/main/java/com/pingolin/track/data/local/dao/Daos.kt").readText()
        val start = source.indexOf("interface LocationDao")
        val end = source.indexOf("\ninterface ", start + 1).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)
        val found = linkedMapOf<String, String>()
        Regex("""@Query\((?:\"\"\"([\s\S]*?)\"\"\"|"([^"]*)")\)\s*(?:suspend\s+)?fun\s+(\w+)""").findAll(body).forEach { m ->
            found[m.groupValues[3]] = (m.groupValues[1] + m.groupValues[2])
        }
        return found
    }

    @Test
    fun `every location query is labelled live or history, and every live one reads only current rows`() {
        val queries = queries()
        assertTrue("found the location queries", queries.keys.containsAll(live))
        assertEquals("labelled: $queries", emptySet<String>(), queries.keys - live - history)
        for (name in live) {
            val sql = queries.getValue(name).replace(Regex("\\s+"), " ")
            assertTrue("$name reads only current rows: $sql", Regex("""(?:\bl2\.)?keyCurrent = 1""").containsMatchIn(sql))
        }
        // The latest row per member is picked among current rows only, at every level of the query.
        val latest = queries.getValue("observeLatestByCircle")
        assertEquals(2, Regex("keyCurrent = 1").findAll(latest).count())
    }
}
