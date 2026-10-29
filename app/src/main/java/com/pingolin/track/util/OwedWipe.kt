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
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.KeyStoreLoad
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A wipe of the SDK's key store that the platform refused (after clearAllKeys the store says
 * deleteFailed, so it may still hold what was cleared). The wipe is owed, and paid before
 * anything is loaded: at the next launch, and first at the next sign-in. While it's owed
 * nothing is loaded (the SDK refuses loads and writes then), and only a clear that leaves the
 * store loaded pays it. EVERY clear of the key store goes through [clear].
 *
 * The mark is a file of its own, outside the stores that may be failing, holding nothing: a
 * SharedPreferences file that doesn't parse reads as empty, which would say "not owed". A look
 * that fails counts as owed: no answer is never "not owed".
 */
@Singleton
class OwedWipe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Tests point this elsewhere. */
    internal var mark: () -> File = { File(context.noBackupFilesDir, MARK_FILE) }

    /** Whether a wipe of the key store is owed. */
    fun isOwed(): Boolean = runCatching { mark().exists() }.getOrDefault(true)

    /**
     * Clear [cryptoManager]'s keys and its store. True when the store is cleared (loaded, holding
     * nothing): any wipe owed is paid. False when the platform refused the delete: the wipe is
     * owed from now on.
     */
    fun clear(cryptoManager: CryptoManager): Boolean {
        cryptoManager.clearAllKeys()
        val cleared = runCatching { cryptoManager.keyStoreStatus().load }.getOrNull() == KeyStoreLoad.LOADED
        if (cleared) {
            if (runCatching { mark().let { !it.exists() || it.delete() } }.getOrDefault(false).not()) {
                Log.w(TAG, "The stored keys were cleared, but the mark of the wipe owed is still there")
            }
        } else {
            Log.w(TAG, "The stored keys couldn't be deleted: a wipe is owed before anything is loaded")
            if (runCatching { mark().let { it.exists() || it.createNewFile() } }.getOrDefault(false).not()) {
                Log.e(TAG, "The wipe owed couldn't be marked")
            }
        }
        return cleared
    }

    private companion object {
        const val TAG = "OwedWipe"
        const val MARK_FILE = "key_store_wipe_owed"
    }
}
