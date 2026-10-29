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

/**
 * The keys this device's own person confirmed for a member with "It's them", per account and
 * circle: on this ground alone this device may hand them the circle's key while the records
 * still name them with other keys. A rotation never includes anyone on this ground, only on
 * grounds other devices can check.
 */
interface PersonConfirmedKeys : KeptPerAccount {

    fun confirm(circleId: String, userId: String, sharingHex: String, signingHex: String)

    /** Whether this device's person confirmed exactly these keys for [userId] in [circleId]. */
    fun confirmed(circleId: String, userId: String, sharingHex: String, signingHex: String): Boolean
}

class SecurePrefsPersonConfirmedKeys(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : PersonConfirmedKeys {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    private fun key(circleId: String, userId: String): String? = account.key(circleId)?.let { "$it|${AccountIds.storeKey(userId)}" }

    override fun confirm(circleId: String, userId: String, sharingHex: String, signingHex: String) {
        val key = key(circleId, userId) ?: return
        runCatching {
            // Read first: nothing written over a file, or a circle's entries, that can't be read now.
            account.check(key)
            prefs.getString(key, null)
            prefs.edit().putString(key, "${sharingHex.lowercase()}:${signingHex.lowercase()}").commit()
        }
    }

    override fun confirmed(circleId: String, userId: String, sharingHex: String, signingHex: String): Boolean {
        val key = key(circleId, userId) ?: return false
        return runCatching {
            account.check(key)
            prefs.getString(key, null)
        }.getOrNull() == "${sharingHex.lowercase()}:${signingHex.lowercase()}"
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "person-confirmed keys"
        const val FILE_NAME = "person_confirmed_keys"

        /**
         * A member's confirmed keys kept under two spellings of the circle (or of the member's id),
         * folded: kept only where every spelling holds the same keys; otherwise unconfirmed, and the
         * person is asked again.
         */
        val RULES = object : CircleEntries.Rules {
            override fun rest(rest: String): String = if (rest.startsWith("|")) "|${AccountIds.storeKey(rest.substring(1))}" else rest

            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) =
                entries.filterKeys { it.startsWith("|") }.mapValues { (_, held) -> CircleEntries.agreed(held, spellings) { (it as? String)?.lowercase() } }
        }
    }
}
