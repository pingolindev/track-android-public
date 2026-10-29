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

package com.pingolin.track.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map must not offer to load a "next page" of location history, because
 * there is no next page.
 *
 * `/locations` answers the WHOLE requested day-bucket range in one response,
 * downsampled to a point budget — it emits no `next_cursor` and no `has_more`.
 * A "Load more" button bound to that missing `has_more` could never become
 * visible, and if it were shown, tapping it would re-fetch the same range.
 *
 * WHY A SOURCE TEST
 *   Layouts and Fragments are not reachable from a JVM test here (see
 *   [com.pingolin.track.util.MainSourceSetIsGoogleFreeTest] for the same
 *   reasoning applied to flavours), so a button in a layout and its click
 *   handler in a Fragment can only be checked from the source. What makes the
 *   affordance wrong is that it EXISTS at all, which is a fact about the
 *   source and is checkable here.
 *
 * IF THIS TEST FAILS
 *   Do not add paging to make a button work. The remedy for "I want to see
 *   more" under a downsample is a NARROWER date range: the response already
 *   spans the range that was asked for.
 */
class MapOffersNoLoadMoreTest {

    /** The concrete hooks a re-added button would need. Prose is not checked —
     *  the comments explaining WHY there is no button say its name. */
    private val forbidden = listOf(
        "btnLoadMore",
        "loadMoreHistory",
        "locationHistoryHasMore",
    )

    @Test
    fun `the map layout and fragment offer no load-more affordance for history`() {
        val app = findAppModule()
        val checked = listOf(
            File(app, "src/main/res/layout/fragment_map.xml"),
            File(app, "src/main/java/com/pingolin/track/ui/map/MapFragment.kt"),
            File(app, "src/main/java/com/pingolin/track/ui/map/MapViewModel.kt"),
        )

        val offenders = checked.flatMap { file ->
            assertTrue("expected to find ${file.path}", file.isFile)
            val text = file.readText()
            forbidden.filter { text.contains(it) }.map { "${file.name}: $it" }
        }.sorted()

        assertEquals(
            "History is unpaged: one request returns the whole selected range. A " +
                "'Load more' control has no page to load, and the has_more it was " +
                "bound to is a field the server never sends.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The truncation notice stands in place of the button, so assert it is wired
     * rather than merely that the button is absent — otherwise deleting both
     * would pass this file while leaving the loss silent.
     */
    @Test
    fun `the history footer reports a range the server truncated`() {
        val fragment = File(
            findAppModule(),
            "src/main/java/com/pingolin/track/ui/map/MapFragment.kt"
        ).readText()

        assertTrue(
            "MapFragment must read MapViewModel.locationHistoryTruncated so the point " +
                "count admits when the oldest points were dropped",
            fragment.contains("locationHistoryTruncated")
        )
    }

    /**
     * Walks up from the working directory rather than assuming it, for the same
     * reason [com.pingolin.track.util.MainSourceSetIsGoogleFreeTest] does: a
     * guard that silently scanned nothing would pass forever.
     */
    private fun findAppModule(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val nested = File(dir, "app")
            if (File(nested, "src/main/res/layout/fragment_map.xml").isFile) return nested
            if (File(dir, "src/main/res/layout/fragment_map.xml").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not locate the app module from ${File("").absolutePath}. Fix this " +
                "lookup rather than deleting the test."
        )
    }
}
