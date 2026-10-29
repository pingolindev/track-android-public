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

import com.pingolin.track.util.AlertHandler.PostedNotification
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A notification can MOVE between groups, which strands the summary of the
 * group it left.
 *
 * The local place-alert evaluator supersedes a server push by re-posting under
 * that push's own notification id, and the two paths deliberately live in
 * different groups (server: PLACES on the `alerts` channel; local: PLACES_LOCAL
 * on `place_alerts_local`). Android honours the move, so the user correctly
 * ends up with ONE alert — the richer one — but the PLACES summary stays behind
 * with nothing under it, and a childless summary still renders.
 *
 * On a device, one crossing would leave a stray "Location Updates / Tap to
 * view" sitting next to the real "<member> arrived at <place>".
 */
class EmptyGroupSummaryTest {

    private fun emptySummaryIds(l: List<PostedNotification>) = AlertHandler.emptySummaryIds(l)

    private fun summary(id: Int, group: String?) = PostedNotification(id, group, isSummary = true)
    private fun child(id: Int, group: String?) = PostedNotification(id, group, isSummary = false)

    @Test
    fun `a superseded child moved groups, and its old summary is stranded`() {
        val ids = emptySummaryIds(
            listOf(
                summary(10001, "com.pingolin.track.PLACES"),        // server path, child moved away
                summary(10004, "com.pingolin.track.PLACES_LOCAL"),  // local path
                child(1271072114, "com.pingolin.track.PLACES_LOCAL"), // the surviving rich copy
                child(1001, null),                                   // foreground service, ungrouped
            )
        )
        assertEquals(listOf(10001), ids)
    }

    @Test
    fun `a summary with children is kept`() {
        val ids = emptySummaryIds(
            listOf(
                summary(10001, "G"),
                child(1, "G"),
            )
        )
        assertEquals(emptyList<Int>(), ids)
    }

    @Test
    fun `only the empty group's summary is cancelled when several groups are live`() {
        val ids = emptySummaryIds(
            listOf(
                summary(10001, "PLACES"),
                summary(10002, "STATUS"),
                child(1, "STATUS"),
            )
        )
        assertEquals(listOf(10001), ids)
    }

    @Test
    fun `an ungrouped summary counts as empty - it can never gain a child`() {
        assertEquals(listOf(10001), emptySummaryIds(listOf(summary(10001, null))))
    }

    @Test
    fun `a child does not keep a DIFFERENT group's summary alive`() {
        // The mistake in one line: matching on "some child exists"
        // rather than "a child of THIS group exists" would return
        // nothing here.
        val ids = emptySummaryIds(
            listOf(
                summary(10001, "PLACES"),
                child(1, "PLACES_LOCAL"),
            )
        )
        assertEquals(listOf(10001), ids)
    }

    @Test
    fun `nothing posted, nothing cancelled`() {
        assertEquals(emptyList<Int>(), emptySummaryIds(emptyList()))
    }
}
