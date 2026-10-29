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

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.pingolin.track.domain.repository.InviteRenewalQuestion
import com.pingolin.track.domain.repository.MembershipStatements

/**
 * "Is this really {email}?" questions this device has raised and nobody has answered yet. A
 * question is raised once per invitation; one left unanswered comes back on the next launch
 * exactly as it was raised — the same details, and whether it was already asked again —
 * without a second notification.
 */
interface PendingRenewalQuestions : KeptPerAccount {

    fun put(question: InviteRenewalQuestion)

    fun all(): List<InviteRenewalQuestion>

    fun remove(statementId: String)
}

class SecurePrefsPendingRenewalQuestions(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : PendingRenewalQuestions {

    private val prefs by lazy { prefsProvider(context) }

    /** The canonical account id, its entries under another spelling moved there first (AccountIds). */
    private val account = AccountEntries.Owner("pending renewal questions", { prefs }, ownerUserId)
    private val gson = Gson()

    /** Entries are this account's only: another account on this phone sees none of them. */
    private fun prefix(): String? = account.get()?.let { "$it|" }

    /** As stored: named fields, so an app update's new R8 mapping still reads it. */
    private data class Stored(
        @SerializedName("circle_id") val circleId: String,
        @SerializedName("circle_name") val circleName: String,
        @SerializedName("invite_id") val inviteId: String,
        @SerializedName("statement_id") val statementId: String,
        @SerializedName("email") val email: String,
        @SerializedName("user_id") val userId: String,
        @SerializedName("sharing_key") val sharingKeyHex: String,
        @SerializedName("signing_key") val signingKeyHex: String,
        @SerializedName("number") val number: String?,
        @SerializedName("statement") val statementHex: String,
        @SerializedName("signature") val signatureHex: String,
        @SerializedName("reasked") val reasked: Boolean,
    )

    override fun put(question: InviteRenewalQuestion) {
        val prefix = prefix() ?: return
        val stored = with(question) {
            Stored(
                circleId, circleName, inviteId, statementId, email, userId, sharingKeyHex, signingKeyHex, number,
                original.statementHex, original.signatureHex, reasked,
            )
        }
        runCatching { prefs.edit().putString(prefix + AccountIds.storeKey(question.statementId), gson.toJson(stored)).apply() }
    }

    override fun all(): List<InviteRenewalQuestion> {
        val prefix = prefix() ?: return emptyList()
        return runCatching {
            prefs.all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { json ->
                runCatching {
                    with(gson.fromJson(json as String, Stored::class.java)) {
                        InviteRenewalQuestion(
                            // In its one spelling: one an earlier version kept as
                            // served, too.
                            AccountIds.storeKey(circleId), circleName, inviteId, statementId, email, userId, sharingKeyHex, signingKeyHex,
                            number, MembershipStatements.Signed(statementHex, signatureHex), reasked,
                        )
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    override fun remove(statementId: String) {
        val prefix = prefix() ?: return
        runCatching { prefs.edit().remove(prefix + AccountIds.storeKey(statementId)).apply() }
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, "pending renewal questions", mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val FILE_NAME = "pending_renewal_questions"
    }
}
