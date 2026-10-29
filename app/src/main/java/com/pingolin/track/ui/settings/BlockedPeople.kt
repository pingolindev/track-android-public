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

package com.pingolin.track.ui.settings

import com.pingolin.track.data.remote.dto.BlockedPersonResponse
import com.pingolin.track.ui.auth.AuthField
import com.pingolin.track.ui.auth.AuthFormValidation
import java.util.Locale

/**
 * The two small decisions of the "Blocked people" screen. What a block DOES is the server's work:
 * a blocked person's invitation is made and stays pending for them, and this person is never told
 * or shown it.
 */
object BlockedPeople {

    /**
     * The address to send for what was typed, or null when there is nothing to send:
     *
     * - not an address (the sign-in form's rule: one place decides what a usable address is);
     * - this person's own, which the server refuses (here the button simply stays off);
     * - one that is on the list already, whatever its case: the server keeps lower case;
     * - or the list has not been read yet ([blocked] is null), so "already on it" is not known.
     */
    fun addressToBlock(typed: String, blocked: List<String>?, ownEmail: String?): String? {
        if (blocked == null) return null
        val address = typed.trim()
        if (AuthFormValidation.forLogin(address, passwordLength = 1).any { it.field == AuthField.EMAIL }) return null
        if (ownEmail != null && ownEmail.trim().equals(address, ignoreCase = true)) return null
        if (blocked.any { it.equals(address, ignoreCase = true) }) return null
        return address
    }

    /**
     * The server's code for a block refused because the list holds as many addresses as it may
     * (a 409).
     */
    const val LIST_FULL_CODE = "blocked_people_limit"

    /**
     * Was a block refused because the list is full? By its CODE: the screen then says so in its own
     * words (strings.xml `blocked_people_full`). Told only that "Pingolin is having trouble just
     * now", a person would try again for ever: nothing is wrong, and nothing they are shown says
     * what to do.
     */
    fun isListFull(errorCode: String?): Boolean = errorCode == LIST_FULL_CODE

    /** The list as it is shown: by address, whatever its case, the same in every language. */
    fun inListOrder(people: List<BlockedPersonResponse>): List<BlockedPersonResponse> =
        people.sortedBy { it.blockedEmail.lowercase(Locale.ROOT) }
}
