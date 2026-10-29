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

import com.pingolin.track.util.SessionManager

/**
 * The words of a notification row once this device has put names to it.
 *
 * The server writes every row with nobody in it — "Member arrived at a place", "A circle
 * member has checked in." — because it holds no names: the roster and the places are
 * end-to-end encrypted. The ids it does write (`target_user_id`, `place_id`, `joined_user_id`,
 * …) are turned into names here only from what this device opens itself ([NotificationNames]),
 * and only as far as the lock-screen detail setting allows: "Notification detail" governs the
 * list the same way it governs the shade (AlertHandler.resolveDisplayMessage). An unknown
 * person is "A member", an unknown place "a place". Never "SOS": the feature asks a circle to
 * check on someone.
 *
 * Pure, so every sentence is tested without a device.
 */
object NamedNotificationCopy {

    /** What the setting lets a row name. */
    enum class Detail { GENERIC, NAMES, FULL }

    fun detailOf(level: String): Detail = when (level) {
        SessionManager.NOTIF_DETAIL_GENERIC -> Detail.GENERIC
        SessionManager.NOTIF_DETAIL_NAMES -> Detail.NAMES
        else -> Detail.FULL
    }

    /**
     * What this device resolved for one row. [person] is whoever the row is about (the
     * crosser, the one checking in, the one who asked to be checked on, the responder, the
     * newcomer, the new owner, the claimant, the one who closed an alert); [personIsMe] when
     * that is this account.
     */
    data class Names(
        val person: String? = null,
        val personIsMe: Boolean = false,
        val place: String? = null,
        val circle: String? = null,
        /** A response to my request (sos_acknowledged): how many others had answered it by then. */
        val others: Int = 0,
        /** A closed alert (sos_resolved): [person] is who CLOSED it, from the row's `closed_by`. */
        val closer: Boolean = false,
    )

    private const val SOMEONE = "A member"
    private const val A_PLACE = "a place"

    /** Who answered or closed a request, when this device can't name them. */
    private const val UNNAMED = "Someone"

    /**
     * Who is responding to a request to be checked on: "Ana is responding", "Ana and 1 other are
     * responding", "Ana and 3 others are responding".
     */
    fun respondingCopy(who: String, others: Int): String = when {
        others <= 0 -> "$who is responding"
        others == 1 -> "$who and 1 other are responding"
        else -> "$who and $others others are responding"
    }

    /** Who closed a request to be checked on: "Ana closed the alert". */
    fun closedCopy(who: String): String = "$who closed the alert"

    /**
     * The row's (title, body) after naming: the server's own words when the setting is
     * generic or nothing was resolved, and for any type or wording this does not know.
     */
    fun rowCopy(type: String, title: String, body: String?, names: Names, detail: Detail): Pair<String, String?> {
        if (detail == Detail.GENERIC) return title to body
        val place = names.place.takeIf { detail == Detail.FULL }
        val circle = names.circle.takeIf { detail == Detail.FULL }
        // Who is responding and who closed the alert are said even when this device can't name
        // them ("Someone"): the server's words say neither how many answered nor that anyone closed it.
        if (type == "sos_acknowledged" && (names.person != null || names.others > 0)) {
            // Only a named responder changes the body; "Someone" keeps the server's (as on iOS).
            return respondingCopy(names.person ?: UNNAMED, names.others) to
                (names.person?.let { "$it acknowledged your request to check on you" } ?: body)
        }
        if (type == "sos_resolved" && names.closer) {
            return title to closedCopy(if (names.personIsMe) "You" else names.person ?: UNNAMED)
        }
        if (names.person == null && !names.personIsMe && place == null) return title to body
        val who = names.person ?: SOMEONE
        return when (type) {
            "place_alert" -> when {
                title.contains("arrived") -> "$who arrived at ${place ?: A_PLACE}" to body
                title.contains("left") -> "$who left ${place ?: A_PLACE}" to body
                else -> title to body
            }
            "check_in" -> title to checkInBody(body, who, place)
            "sos_alert" -> if (names.person == null) title to body
                else "$who needs attention" to "$who asked their circle to check on them"
            "sos_resolved" -> when {
                names.personIsMe -> title to "Your request to be checked on was marked resolved"
                names.person != null -> title to "$who's request to be checked on was marked resolved"
                else -> title to body
            }
            "member_joined" -> if (names.person == null) title to body
                else title to "$who joined ${circle ?: "a circle you are in"}. They can see its location history."
            "circle_ownership_transferred" -> when {
                names.personIsMe ->
                    title to "You are now this circle's owner. The previous owner did not respond to a request to take it over."
                names.person == null -> title to body
                body?.startsWith("You did not respond") == true ->
                    title to "You did not respond to the request, so this circle has been transferred to $who."
                else -> title to "$who is now this circle's owner. The previous owner did not respond to a request to take it over."
            }
            "circle_ownership_claim" -> if (names.person == null) title to body
                else "$who asked to take over your circle" to body
            else -> title to body
        }
    }

    /**
     * A check-in's body from the server's three ("A circle member has checked in." /
     * "…checked in safely." / "…confirmed they are OK."): the same sentence with the name,
     * and where they checked in when the setting allows.
     */
    private fun checkInBody(body: String?, who: String, place: String?): String? = when (checkInTypeOf(body)) {
        "safe_arrival" -> "$who has checked in safely${place?.let { " at $it" } ?: ""}."
        "emergency_ok" -> "$who has confirmed they are OK."
        "general" -> "$who has checked in${place?.let { " at $it" } ?: ""}."
        else -> body
    }

    /** The check-in's kind, read back from the server's body; null for words it doesn't write. */
    fun checkInTypeOf(body: String?): String? = when (body) {
        "A circle member has checked in safely." -> "safe_arrival"
        "A circle member has confirmed they are OK." -> "emergency_ok"
        "A circle member has checked in." -> "general"
        else -> null
    }
}
