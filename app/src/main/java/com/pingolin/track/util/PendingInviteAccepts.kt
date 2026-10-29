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

/**
 * Invitations this person agreed to join ("Join and share my location") that could not be
 * accepted yet, because the invitation does not name this account as it is now: they reset
 * their password after being invited.
 *
 * The circle is asked to confirm it's them and renew the invitation; this app accepts by
 * itself once a renewed invitation names this account, with the consent the person
 * already gave. Once per distinct statement: [Pending.triedStatement] is the hash of the
 * last statement it tried with.
 */
interface PendingInviteAccepts : KeptPerAccount {

    /** Stored as JSON: the fields are named, so an app update's new R8 mapping still reads them. */
    data class Pending(
        @SerializedName("invite_id") val inviteId: String,
        @SerializedName("circle_id") val circleId: String,
        /** The location notice the person agreed to, sent with the accept when it goes. */
        @SerializedName("notice_version") val noticeVersion: String,
        /** sha256 of the statement last tried (or reported); a new one is worth a try. */
        @SerializedName("tried_statement") val triedStatement: String?,
    )

    fun put(pending: Pending)

    fun all(): List<Pending>

    fun remove(inviteId: String)
}

class SecurePrefsPendingInviteAccepts(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : PendingInviteAccepts {

    private val prefs by lazy { prefsProvider(context) }

    /** The canonical account id, its entries under another spelling moved there first (AccountIds). */
    private val account = AccountEntries.Owner("pending invite accepts", { prefs }, ownerUserId)
    private val gson = Gson()

    /** Entries are this account's only: another account on this phone sees none of them. */
    private fun prefix(): String? = account.get()?.let { "$it|" }

    override fun put(pending: PendingInviteAccepts.Pending) {
        val prefix = prefix() ?: return
        runCatching { prefs.edit().putString(prefix + pending.inviteId, gson.toJson(pending)).apply() }
    }

    /** Each with its circle in its one spelling (AccountIds.storeKey): one an earlier version kept
     * as served, too.
     */
    override fun all(): List<PendingInviteAccepts.Pending> {
        val prefix = prefix() ?: return emptyList()
        return runCatching {
            prefs.all.filterKeys { it.startsWith(prefix) }.values.mapNotNull { json ->
                runCatching {
                    val pending = gson.fromJson(json as String, PendingInviteAccepts.Pending::class.java)
                    val circle = AccountIds.storeKey(pending.circleId)
                    if (circle == pending.circleId) pending else pending.copy(circleId = circle)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    override fun remove(inviteId: String) {
        val prefix = prefix() ?: return
        runCatching { prefs.edit().remove(prefix + inviteId).apply() }
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, "pending invite accepts", mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val FILE_NAME = "pending_invite_accepts"
    }
}
