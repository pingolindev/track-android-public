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

package com.pingolin.track.ui.alerts.history

import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.ui.alerts.history.HistoryCheckIns.Watched
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.InactivityWatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose past check-ins Alerts > History shows, and where they go (HistoryCheckIns): the people the
 * check-in rules that can alert this account are about, in those rules' circles, from all three
 * kinds of rule; one row per check-in in the last 30 days; merged with the alert entries by time,
 * newest first.
 */
class HistoryCheckInsTest {

    private companion object {
        const val ME = "5e1f0000-0000-4000-8000-00000000000e"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-00000000b0b0"
        const val CARA = "ca7a0000-0000-4000-8000-0000000ca7a0"
        const val C1 = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val C2 = "c2c2c2c2-0000-4000-8000-0000000000c2"
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }

    private val now = 20_000 * DAY + 12 * HOUR

    private fun rule(id: String, circle: String, target: String, self: Boolean = false, active: Boolean = true, watches: String = InactivityWatches.CHECK_IN) =
        InactivityAlertEntity(
            id = id, userId = ME, circleId = circle, targetUserId = target, timeoutHours = 4, isSelfAlert = self,
            isActive = active, dayBucket = 0, watches = watches, armedAt = 0L,
        )

    private fun request(userId: String, circle: String, watches: String? = "check_in") =
        CircleSelfAlertDto(id = "ask-$userId", userId = userId, circleId = circle, timeoutHours = 4, watches = watches)

    private fun checkIn(id: String, circle: String, user: String, at: Long, type: String = "general") = CheckInEntity(
        id = id, circleId = circle, userId = user, type = type, message = null, latitude = null, longitude = null,
        placeId = null, timestamp = at, isSynced = true,
    )

    // ── whose ────────────────────────────────────────────────────────────────

    @Test
    fun `the people the check-in rules that can alert this account are about, from all three kinds, in their circles`() {
        val watched = HistoryCheckIns.watched(
            own = listOf(
                rule("r-1", C1, ALICE),                       // about someone else
                rule("r-2", C2, BOB, active = false),          // off, still watched here
                rule("r-3", C1, ME, self = true),             // about this account
                rule("r-4", C2, CARA, watches = InactivityWatches.LOCATION), // a location rule: no check-ins
            ),
            requests = listOf(
                request(CARA, C1),                            // a circle-mate's request
                request(BOB, C1, watches = "location"),       // a location request: no check-ins
            ),
            selfId = ME,
        )
        assertEquals(setOf(Watched(C1, ALICE), Watched(C2, BOB), Watched(C1, ME), Watched(C1, CARA)), watched)
    }

    @Test
    fun `a member is the same member whatever spelling an id comes in`() {
        val watched = HistoryCheckIns.watched(listOf(rule("r-1", C1, ALICE.uppercase())), listOf(request(BOB.uppercase(), C1)), ME.uppercase())
        assertEquals(setOf(Watched(C1, ALICE), Watched(C1, BOB)), watched)
        val selfRule = HistoryCheckIns.watched(listOf(rule("r-2", C1, "whatever", self = true)), emptyList(), ME.uppercase())
        assertEquals("about itself: this account, whatever the rule's target says", setOf(Watched(C1, ME)), selfRule)
    }

    // ── which check-ins ──────────────────────────────────────────────────────

    @Test
    fun `only the watched people's, in the circle they're watched in, from the last 30 days, once each`() {
        val watched = setOf(Watched(C1, ALICE), Watched(C1, ME))
        val since = now - HistoryCheckIns.WINDOW_MS
        val rows = listOf(
            checkIn("k-1", C1, ALICE, now - HOUR),
            checkIn("k-2", C1, BOB, now - HOUR),          // nobody watches Bob
            checkIn("k-3", C2, ALICE, now - HOUR),        // Alice isn't watched in C2
            checkIn("k-4", C1, ME.uppercase(), now - 2 * HOUR),
            checkIn("k-5", C1, ALICE, since - 1),         // older than 30 days
            checkIn("k-1", C1, ALICE, now - HOUR),        // the same check-in read twice
        )
        assertEquals(listOf("k-1", "k-4"), HistoryCheckIns.select(rows, watched, since).map { it.id })
    }

    // ── the row ──────────────────────────────────────────────────────────────

    @Test
    fun `a row says whose, what kind, where and when`() {
        val names = mapOf(ALICE to "Alice")
        val nameOf = { _: String, user: String -> names[user] }
        val someone = HistoryCheckIns.item(checkIn("k-1", C1, ALICE, now - HOUR, type = "arrived"), ME, nameOf, "Family")
        assertEquals(AlertHandler.TYPE_CHECK_IN, someone.type)
        assertEquals("Alice", someone.memberName)
        assertFalse(someone.ownCheckIn)
        assertEquals("arrived", someone.checkInType)
        assertEquals(now - HOUR, someone.timestampMillis)
        assertEquals("Family", someone.circleName)
        assertEquals(20_000, someone.dayBucket)
        assertEquals("check-in:k-1", someone.id)

        val mine = HistoryCheckIns.item(checkIn("k-2", C1, ME.uppercase(), now), ME, nameOf, "Family")
        assertTrue(mine.ownCheckIn)
        assertTrue("the plain one isn't named", "general" !in HistoryCheckIns.NAMED_TYPES)
        assertTrue(HistoryCheckIns.NAMED_TYPES.containsAll(listOf("safe_arrival", "arrived", "emergency_ok")))
    }

    // ── the order ────────────────────────────────────────────────────────────

    @Test
    fun `alert entries and check-ins merge by time, newest first, a day-only entry at its day's middle`() {
        fun entry(id: String, at: Long?, day: Int = (at!! / DAY).toInt()) =
            AlertEventItem(id = id, type = "x", message = "", dayBucket = day, isAcknowledged = false, timestampMillis = at)
        val items = listOf(
            entry("alert-morning", now - 4 * HOUR),
            HistoryCheckIns.item(checkIn("k-noon", C1, ALICE, now + 60_000L), ME, { _, _ -> "Alice" }, null),
            entry("alert-yesterday", null, day = 19_999),
            HistoryCheckIns.item(checkIn("k-dawn", C1, ALICE, now - 11 * HOUR), ME, { _, _ -> "Alice" }, null),
            entry("day-only-today", null, day = 20_000),
            entry("alert-night", now + 8 * HOUR),
        )
        assertEquals(
            listOf("alert-night", "check-in:k-noon", "day-only-today", "alert-morning", "check-in:k-dawn", "alert-yesterday"),
            HistoryCheckIns.merged(items).map { it.id },
        )
    }
}
