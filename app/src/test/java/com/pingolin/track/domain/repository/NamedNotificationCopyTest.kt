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

package com.pingolin.track.domain.repository

import com.pingolin.track.domain.repository.NamedNotificationCopy.Detail
import com.pingolin.track.domain.repository.NamedNotificationCopy.Names
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The words of a named notification row, which the app fills in with names and places itself:
 * from the server's own nameless text and what this device resolved, as far as "Notification
 * detail" allows.
 */
class NamedNotificationCopyTest {

    private fun copy(type: String, title: String, body: String?, names: Names, detail: Detail = Detail.FULL) =
        NamedNotificationCopy.rowCopy(type, title, body, names, detail)

    @Test
    fun `an arrival and a departure name the person and the place`() {
        assertEquals(
            "Alice arrived at Home" to null,
            copy("place_alert", "Member arrived at a place", null, Names(person = "Alice", place = "Home")),
        )
        assertEquals(
            "Alice left Home" to null,
            copy("place_alert", "Member left a place", null, Names(person = "Alice", place = "Home")),
        )
    }

    @Test
    fun `names only says who, never where, and a missing half falls back to plain words`() {
        assertEquals(
            "Alice left a place" to null,
            copy("place_alert", "Member left a place", null, Names(person = "Alice", place = "Home"), Detail.NAMES),
        )
        assertEquals(
            "A member arrived at Home" to null,
            copy("place_alert", "Member arrived at a place", null, Names(place = "Home")),
        )
    }

    @Test
    fun `generic, or nothing resolved, keeps the server's words`() {
        val server = "Member arrived at a place" to null
        assertEquals(server, copy("place_alert", server.first, null, Names(person = "Alice", place = "Home"), Detail.GENERIC))
        assertEquals(server, copy("place_alert", server.first, null, Names()))
        assertEquals(
            "Check-in" to "A circle member has checked in.",
            copy("check_in", "Check-in", "A circle member has checked in.", Names(place = "Home"), Detail.NAMES),
        )
    }

    @Test
    fun `a check-in says who, and where at full detail, in the server's three sentences`() {
        assertEquals(
            "Check-in" to "Alice has checked in at Home.",
            copy("check_in", "Check-in", "A circle member has checked in.", Names(person = "Alice", place = "Home")),
        )
        assertEquals(
            "Check-in" to "Alice has checked in safely at Home.",
            copy("check_in", "Check-in", "A circle member has checked in safely.", Names(person = "Alice", place = "Home")),
        )
        assertEquals(
            "Check-in" to "Alice has confirmed they are OK.",
            copy("check_in", "Check-in", "A circle member has confirmed they are OK.", Names(person = "Alice", place = "Home")),
        )
        assertEquals(
            "Check-in" to "Alice has checked in.",
            copy("check_in", "Check-in", "A circle member has checked in.", Names(person = "Alice", place = "Home"), Detail.NAMES),
        )
        // Words it doesn't know are left as the server wrote them.
        assertEquals(
            "Check-in" to "Something new.",
            copy("check_in", "Check-in", "Something new.", Names(person = "Alice")),
        )
    }

    @Test
    fun `asking to be checked on, and the answer, name the person once known`() {
        assertEquals(
            "Alice needs attention" to "Alice asked their circle to check on them",
            copy("sos_alert", "Someone needs attention", "A circle member asked their circle to check on them", Names(person = "Alice")),
        )
        assertEquals(
            "Bob is responding" to "Bob acknowledged your request to check on you",
            copy("sos_acknowledged", "Someone is responding", "A circle member acknowledged your request to check on you", Names(person = "Bob")),
        )
        // Nobody resolved: the server's words.
        assertEquals(
            "Someone is responding" to "A circle member acknowledged your request to check on you",
            copy("sos_acknowledged", "Someone is responding", "A circle member acknowledged your request to check on you", Names()),
        )
    }

    @Test
    fun `who is responding - one, several, and someone this device can't name`() {
        val title = "Someone is responding"
        val body = "A circle member acknowledged your request to check on you"
        assertEquals(
            "Bob and 1 other are responding" to "Bob acknowledged your request to check on you",
            copy("sos_acknowledged", title, body, Names(person = "Bob", others = 1)),
        )
        assertEquals(
            "Bob and 3 others are responding" to "Bob acknowledged your request to check on you",
            copy("sos_acknowledged", title, body, Names(person = "Bob", others = 3)),
        )
        // Someone this device can't name: the count, and the server's own body (as web and iOS).
        assertEquals(
            "Someone and 2 others are responding" to body,
            copy("sos_acknowledged", title, body, Names(others = 2)),
        )
        // There is no place to drop, so names only says who as well; generic keeps the server's words.
        assertEquals(
            "Bob and 1 other are responding" to "Bob acknowledged your request to check on you",
            copy("sos_acknowledged", title, body, Names(person = "Bob", others = 1), Detail.NAMES),
        )
        assertEquals(title to body, copy("sos_acknowledged", title, body, Names(person = "Bob", others = 1), Detail.GENERIC))
    }

    @Test
    fun `a closed alert says who closed it when the row says so`() {
        val body = "A circle member marked the request to check on them resolved"
        assertEquals("Alert resolved" to "Bob closed the alert", copy("sos_resolved", "Alert resolved", body, Names(person = "Bob", closer = true)))
        assertEquals("Alert resolved" to "Someone closed the alert", copy("sos_resolved", "Alert resolved", body, Names(closer = true)))
        assertEquals("Alert resolved" to "You closed the alert", copy("sos_resolved", "Alert resolved", body, Names(personIsMe = true, closer = true)))
        assertEquals(
            "Alert resolved" to "Bob closed the alert",
            copy("sos_resolved", "Alert resolved", body, Names(person = "Bob", closer = true), Detail.NAMES),
        )
        assertEquals("Alert resolved" to body, copy("sos_resolved", "Alert resolved", body, Names(person = "Bob", closer = true), Detail.GENERIC))
    }

    @Test
    fun `respondingCopy counts the others, and closedCopy says who`() {
        assertEquals("Ana is responding", NamedNotificationCopy.respondingCopy("Ana", 0))
        assertEquals("Ana is responding", NamedNotificationCopy.respondingCopy("Ana", -1))
        assertEquals("Ana and 1 other are responding", NamedNotificationCopy.respondingCopy("Ana", 1))
        assertEquals("Ana and 4 others are responding", NamedNotificationCopy.respondingCopy("Ana", 4))
        assertEquals("Ana closed the alert", NamedNotificationCopy.closedCopy("Ana"))
    }

    @Test
    fun `an older resolved row, with no closed_by, still names whose request it was`() {
        val body = "A circle member marked the request to check on them resolved"
        assertEquals(
            "Alert resolved" to "Alice's request to be checked on was marked resolved",
            copy("sos_resolved", "Alert resolved", body, Names(person = "Alice")),
        )
        assertEquals(
            "Alert resolved" to "Your request to be checked on was marked resolved",
            copy("sos_resolved", "Alert resolved", body, Names(personIsMe = true)),
        )
    }

    @Test
    fun `a newcomer is named, with the circle at full detail`() {
        val body = "Someone joined a circle you are in. They can see its location history."
        assertEquals(
            "New circle member" to "Ana joined Family. They can see its location history.",
            copy("member_joined", "New circle member", body, Names(person = "Ana", circle = "Family")),
        )
        assertEquals(
            "New circle member" to "Ana joined a circle you are in. They can see its location history.",
            copy("member_joined", "New circle member", body, Names(person = "Ana", circle = "Family"), Detail.NAMES),
        )
    }

    @Test
    fun `ownership changes say who asked and who owns it now`() {
        assertEquals(
            "Bob asked to take over your circle" to "Refuse by Oct 28.",
            copy("circle_ownership_claim", "Someone asked to take over your circle", "Refuse by Oct 28.", Names(person = "Bob")),
        )
        assertEquals(
            "Circle ownership changed" to "You did not respond to the request, so this circle has been transferred to Bob.",
            copy(
                "circle_ownership_transferred", "Circle ownership changed",
                "You did not respond to the request, so this circle has been transferred to an admin.", Names(person = "Bob"),
            ),
        )
        assertEquals(
            "Circle ownership changed" to "Bob is now this circle's owner. The previous owner did not respond to a request to take it over.",
            copy(
                "circle_ownership_transferred", "Circle ownership changed",
                "This circle has a new owner. The previous owner did not respond to a request to take it over.", Names(person = "Bob"),
            ),
        )
        assertEquals(
            "Circle ownership changed" to "You are now this circle's owner. The previous owner did not respond to a request to take it over.",
            copy(
                "circle_ownership_transferred", "Circle ownership changed",
                "This circle has a new owner. The previous owner did not respond to a request to take it over.", Names(personIsMe = true),
            ),
        )
    }

    @Test
    fun `no named sentence says SOS or emergency`() {
        val types = listOf("place_alert", "check_in", "sos_alert", "sos_acknowledged", "sos_resolved", "member_joined",
            "circle_ownership_transferred", "circle_ownership_claim")
        for (type in types) {
            val (title, body) = copy(type, "Member arrived at a place", "A circle member has checked in.", Names(person = "Alice", place = "Home", circle = "Family"))
            listOf("SOS", "emergency").forEach { word ->
                assertFalse("$type: $title / $body", "$title $body".contains(word, ignoreCase = true))
            }
        }
    }

    @Test
    fun `a check-in's kind is read back from the server's words, and only those`() {
        assertEquals("safe_arrival", NamedNotificationCopy.checkInTypeOf("A circle member has checked in safely."))
        assertEquals("emergency_ok", NamedNotificationCopy.checkInTypeOf("A circle member has confirmed they are OK."))
        assertEquals("general", NamedNotificationCopy.checkInTypeOf("A circle member has checked in."))
        assertEquals(null, NamedNotificationCopy.checkInTypeOf(null))
        assertEquals(null, NamedNotificationCopy.checkInTypeOf("Alice says hi"))
    }

    @Test
    fun `the detail setting maps to what a row may name, full when unknown`() {
        assertEquals(Detail.GENERIC, NamedNotificationCopy.detailOf("generic"))
        assertEquals(Detail.NAMES, NamedNotificationCopy.detailOf("names"))
        assertEquals(Detail.FULL, NamedNotificationCopy.detailOf("full"))
        assertEquals(Detail.FULL, NamedNotificationCopy.detailOf("anything else"))
    }
}
