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

package com.pingolin.track.domain.pending

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * What a change waiting to sync keeps in [com.pingolin.track.data.local.entity.PendingChangeEntity.question]:
 * what the person is asked while it waits for them, or how a parked one ends.
 */
object PendingQuestions {

    /** The signing key is missing on this phone: "Waiting for your password". */
    const val PASSWORD = "password"

    /**
     * A change that signs asks again about someone whose keys aren't the ones the person
     * saw, once: the account and the keys shown now, and their number. The answer continues
     * the change with exactly these keys, or ends it.
     */
    data class Ask(
        /** [INVITEE], [MEMBER], [LET_IN] or [RENEWAL]. */
        @SerializedName("ask") val ask: String,
        @SerializedName("user_id") val userId: String,
        @SerializedName("sharing_key") val sharingKey: String,
        @SerializedName("signing_key") val signingKey: String,
        @SerializedName("number") val number: String? = null,
    )

    /** "Is this really {email}?" about the account an invitation goes to. */
    const val INVITEE = "invitee"

    /** "Is this really {name}?" about a member to make an admin. */
    const val MEMBER = "member"

    /** "Let {name} into {circle}?" again, with their new number. */
    const val LET_IN = "let_in"

    /** "Is this really {email}?" about an invitee whose invitation is renewed. */
    const val RENEWAL = "renewal"

    private data class Stored(
        @SerializedName("ask") val ask: Ask? = null,
        @SerializedName("ending") val ending: String? = null,
        @SerializedName("line") val line: String? = null,
    )

    private val gson = Gson()

    fun ask(ask: Ask): String = gson.toJson(Stored(ask = ask))

    /** The question [question] holds, or null (the password, a parked ending, nothing). */
    fun readAsk(question: String?): Ask? =
        question?.takeIf { it.startsWith("{") }?.let { runCatching { gson.fromJson(it, Stored::class.java)?.ask }.getOrNull() }

    /** How a parked change ends ([how]), and the line it leaves if nothing of it is held. */
    fun ending(how: String, line: String?): String = gson.toJson(Stored(ending = how, line = line))

    fun readEnding(question: String?): Pair<String, String?>? =
        question?.takeIf { it.startsWith("{") }
            ?.let { runCatching { gson.fromJson(it, Stored::class.java) }.getOrNull() }
            ?.let { stored -> stored.ending?.let { it to stored.line } }
}
