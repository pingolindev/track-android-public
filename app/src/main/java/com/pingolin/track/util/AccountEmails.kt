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
import java.util.Locale

/**
 * The email each account typed at its last successful sign-in here, canonical (trimmed, lower
 * case), one entry per account ("{owner}|email"). The account id a sign-in names is the
 * server's word, so what holds trust (owed removals and role changes, key-access approvals, the
 * member pins kept across a sign-out) goes for another account only when the person typed
 * another email than that account's ([guard]).
 */
class AccountEmails(
    private val context: Context,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : KeptPerAccount {

    private val prefs by lazy { prefsProvider(context) }

    private fun key(owner: String) = "${AccountIds.canonical(owner) ?: owner}|$EMAIL"

    /** [owner]'s email as typed at its last successful sign-in here; null for none, or while the file doesn't read. */
    fun of(owner: String): String? = runCatching { prefs.getString(key(owner), null) }.getOrNull()

    /** [owner] signed in here, its keys opened, typing [email]. */
    fun record(owner: String, email: String) {
        runCatching { prefs.edit().putString(key(owner), canonical(email)).commit() }
    }

    /**
     * Whether another account's entries that hold trust may go at a sign-in where [typedEmail] was
     * typed: only when an email was typed and that account's saved one differs (a server that names
     * the same person as another account never takes theirs). No typed email (a passkey, SSO, a
     * resumed session), or none saved for that account (an older store, a file that doesn't read):
     * kept.
     */
    fun guard(typedEmail: String?): (String) -> Boolean {
        val typed = typedEmail?.let(::canonical)?.takeIf { it.isNotEmpty() } ?: return { false }
        return { other -> of(other)?.let { it != typed } ?: false }
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, "account emails", mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private fun canonical(email: String) = email.trim().lowercase(Locale.ROOT)

    private companion object {
        const val FILE_NAME = "account_emails"
        const val EMAIL = "email"
    }
}
