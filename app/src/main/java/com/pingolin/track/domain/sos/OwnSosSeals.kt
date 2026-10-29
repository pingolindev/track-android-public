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

package com.pingolin.track.domain.sos

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pingolin.track.util.AccountEntries
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SecurePrefs
import com.pingolin.track.util.StoreUnreadable

/** What this phone last sealed into its own SOS: in [circleId], raised at [raisedAt] (the sealed raise time). */
data class OwnSosSeal(val circleId: String, val raisedAt: Long, val seal: SosSeal)

/**
 * Per own SOS, the position this phone last sealed into it, or the marker, never the row the
 * server serves back. A rotation's re-seal and an update seal from this, so a position the server
 * swapped in is never re-sealed under the new key; a served position that differs from it is
 * re-sealed back.
 *
 * Written after every create and re-seal that landed; removed when the alert ends (a 404 on
 * re-seal, a resolve, a false alarm, or 24 h past its raise), and wholly at sign-out with the
 * rest of the account's positions. Kept per account, in its own encrypted file: it holds
 * coordinates. A file that doesn't read reads as no record, and has nothing written over it.
 */
interface OwnSosSeals {
    /** The record of [sosId], or null: none (a reinstall, or an alert raised by an app version that kept no
     * record), or one that doesn't read.
     */
    fun get(sosId: String): OwnSosSeal?

    fun put(sosId: String, record: OwnSosSeal)

    fun remove(sosId: String)

    /** Drop every record of an SOS raised before [cutoff]. */
    fun pruneBefore(cutoff: Long)

    /** Sign-out and erasure: every record goes, whoever's. */
    fun wipeAll()
}

class SecurePrefsOwnSosSeals(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : OwnSosSeals {

    private val prefs by lazy { prefsProvider(context) }

    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId)

    /** "{owner}|{sos}": this account's only, the id in its one spelling. */
    private fun key(sosId: String): String? = account.get()?.let { "$it|${AccountIds.storeKey(sosId)}" }

    override fun get(sosId: String): OwnSosSeal? {
        val key = key(sosId) ?: return null
        val line = try {
            StoreUnreadable.reading(STORE) { prefs.getString(key, null) }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: no record of what this phone sealed")
            null
        } ?: return null
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return null
        val raisedAt = parts[1].toLongOrNull() ?: return null
        val seal = SosSeal.decode(parts[2]) ?: return null
        return OwnSosSeal(parts[0], raisedAt, seal)
    }

    override fun put(sosId: String, record: OwnSosSeal) {
        val key = key(sosId) ?: return
        writeIfReadable { putString(key, "${record.circleId}\t${record.raisedAt}\t${record.seal.encode()}") }
    }

    override fun remove(sosId: String) {
        val key = key(sosId) ?: return
        writeIfReadable { remove(key) }
    }

    override fun pruneBefore(cutoff: Long) {
        val owner = account.get() ?: return
        writeIfReadable {
            for ((k, v) in prefs.all) {
                if (!AccountEntries.isOwners(k, owner)) continue
                val raisedAt = (v as? String)?.split('\t', limit = 3)?.getOrNull(1)?.toLongOrNull()
                if (raisedAt == null || raisedAt < cutoff) remove(k)
            }
        }
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    /** [write] in one commit, only when the file reads: nothing is written over one that doesn't. */
    private fun writeIfReadable(write: SharedPreferences.Editor.() -> Unit) {
        runCatching {
            prefs.all
            prefs.edit().apply(write).commit()
        }.onFailure { Log.w(TAG, "$STORE not written: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "OwnSosSeals"
        const val STORE = "own SOS seals"
        const val FILE_NAME = "own_sos_seals"
    }
}
