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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The ONE place this app builds an [EncryptedSharedPreferences].
 *
 * WHY THIS EXISTS
 *   `EncryptedSharedPreferences` and `MasterKey` are deprecated: androidx.security
 *   is retired and gets no more releases. It backs the most sensitive storage the
 *   app has (session tokens, the SDK's E2EE key material, the SQLCipher
 *   passphrase), so every store is built here, in one seam: replacing it means one
 *   implementation behind this function, not five call sites each getting the
 *   data migration right.
 *
 *   It is deliberately NOT a migration: same file names, same default master key,
 *   same AES256_SIV/AES256_GCM schemes, so every existing install reads exactly
 *   the bytes it wrote. Nothing to migrate, nothing to lose.
 *
 * WHY THAT CAUTION MATTERS
 *   Moving a store onto a new file and clearing the old one can wipe the SDK's
 *   key file on first launch, and with it users' E2EE keys — they would have to
 *   sign out and back in to re-derive them from the server. Any change here
 *   migrates LAZILY on read, never bulk-converts-then-clears, and never deletes
 *   the old file. See the note in AppModule.provideEncryptedSharedPreferences.
 *
 * CALLERS KEEP THEIR OWN ERROR HANDLING
 *   This only constructs. It does not catch: SessionManager turns a failure into a
 *   user-facing SecurityException, DatabasePassphraseManager distinguishes
 *   device-locked from real corruption. Swallowing exceptions here would erase
 *   distinctions the callers depend on.
 */
object SecurePrefs {

    /**
     * The app-wide master key, resolved once.
     *
     * It is the default `_androidx_security_master_key_` (`MasterKey.Builder(context)`
     * with no alias), so every store shares one key on disk; one instance just stops
     * rebuilding it per store.
     */
    @Volatile
    private var cachedMasterKey: MasterKey? = null

    /**
     * The shared master key. Public so the few callers that need a [MasterKey]
     * directly (Hilt providers, an injectable test override) take THIS one rather
     * than building their own — otherwise `MasterKey.Builder` leaks back out and
     * the single seam is gone.
     */
    fun masterKey(context: Context): MasterKey =
        cachedMasterKey ?: synchronized(this) {
            cachedMasterKey ?: MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
                .also { cachedMasterKey = it }
        }

    /**
     * Open (or create) the encrypted preferences file [fileName].
     *
     * @param masterKey overrides the shared key — tests inject their own; production
     *   callers omit it and get the default, which is what every existing file was
     *   written under.
     */
    fun open(
        context: Context,
        fileName: String,
        masterKey: MasterKey? = null,
    ): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey ?: masterKey(context),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}
