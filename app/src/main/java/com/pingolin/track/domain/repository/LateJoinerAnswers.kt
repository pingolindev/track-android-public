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

import android.content.Context
import android.content.SharedPreferences
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.CircleEntries
import com.pingolin.track.util.SecurePrefs

/**
 * The invitations this device's person answered the late-joiner question about ("Let them in" or
 * "Not now"), per account and circle: the question is asked once. An answer covers the
 * invitation and every spare of it (LateJoiners.sameInvitation). On disk: a restart asks nothing
 * again.
 */
interface LateJoinerAnswers : com.pingolin.track.util.KeptPerAccount {

    /** [com.pingolin.track.util.StoreUnreadable] while the answers can't be read: the late-joiner
     * question then asks nothing.
     */
    fun answered(circleId: String): List<LateJoiners.Invitation>

    /**
     * On disk before this returns and read back: false when it didn't land, or the answers can't
     * be read now (nothing is written over them).
     */
    fun answer(circleId: String, invitation: LateJoiners.Invitation): Boolean
}

class SecurePrefsLateJoinerAnswers(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : LateJoinerAnswers {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = com.pingolin.track.util.AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    private fun key(circleId: String): String? = account.key(circleId)

    private fun read(key: String): Set<String> = com.pingolin.track.util.StoreUnreadable.reading(STORE) {
        account.check(key)
        prefs.getStringSet(key, null)?.toSet()
    }.orEmpty()

    override fun answered(circleId: String): List<LateJoiners.Invitation> = key(circleId)?.let(::read).orEmpty().mapNotNull { entry ->
        val f = entry.split(SEPARATOR)
        if (f.size != 4) return@mapNotNull null
        LateJoiners.Invitation(f[0], f[1], f[2], f[3].toLongOrNull() ?: return@mapNotNull null)
    }

    override fun answer(circleId: String, invitation: LateJoiners.Invitation): Boolean {
        val key = key(circleId) ?: return false
        val entry = entryOf(listOf(invitation.id, invitation.signer, invitation.person, invitation.issuedAt.toString()))
        return runCatching { prefs.edit().putStringSet(key, read(key) + entry).commit() && entry in read(key) }.getOrDefault(false)
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        com.pingolin.track.util.AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        com.pingolin.track.util.AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "late-joiner answers"
        const val FILE_NAME = "late_joiner_answers"
        const val SEPARATOR = "\t"

        /** An answer as kept: the invitation's id, signer, person and time, each id in its one spelling. */
        fun entryOf(fields: List<String>): String = fields.joinToString(SEPARATOR) { AccountIds.storeKey(it) }

        /**
         * A circle's answers kept under two spellings, folded: an answer is kept only where every
         * spelling holds it; otherwise the question is asked again.
         */
        val RULES = object : CircleEntries.Rules {
            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap {
                entries[""]?.let { held ->
                    put("", CircleEntries.intersection(held, spellings) { entry -> entry.split(SEPARATOR).let { if (it.size == 4) entryOf(it) else entry } })
                }
            }
        }
    }
}
