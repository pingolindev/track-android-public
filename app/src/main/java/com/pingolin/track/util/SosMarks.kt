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
 * What this device saw of each SOS, one way, per account. An SOS alarms only while its sealed
 * fields opened under the circle's CURRENT key, or once they did here (sticky), and never after it
 * ended:
 *  - [seenCurrent]: the payload hashes this device saw current, the newest [SosMarks.MAX_HASHES]
 *    (a hash seen again is the newest), so a resealed SOS stays sticky across the next rotation;
 *  - [ended]: resolved or called a false alarm, seen here once: it never alarms again, whatever the
 *    server lists later;
 *  - [rosterEnded]: its raiser left the members this device shows in that circle: sticky no more;
 *  - [rang]: its alarm sounded on this device: no pass sounds it again.
 * Keyed by the canonical (circle, SOS id). Kept like the account's other trust that holds an
 * obligation: across this account's sign-out, dropped for another account only where the
 * typed-email guard lets a sign-in drop them (CircleRepository's holdingTrust, as ownRemovals),
 * never adopted by another account, and dropped at erasure. Pruned only once the SOS is older than
 * MAX_SOS_ALARM_AGE: no alarm is left to guard then. A file that doesn't read reads as nothing seen
 * and has nothing written over it, and [readable] says so: the rules then can't tell.
 */
interface SosMarks : KeptPerAccount {

    /** Whether the marks read now: someone is signed in and this file reads. */
    fun readable(): Boolean

    /** Whether the SOS's payload [hash] was seen under the current key here. False while the file doesn't read. */
    fun seenCurrent(circleId: String, sosId: String, hash: String): Boolean

    /** [hash] was seen under the current key; [at] is the SOS's sealed time, for pruning. */
    fun markSeenCurrent(circleId: String, sosId: String, hash: String, at: Long)

    /** Whether this device saw the SOS end. False while the file doesn't read. */
    fun ended(circleId: String, sosId: String): Boolean

    /** The SOS ended; [at] is its sealed time, or now when that didn't open, for pruning. */
    fun markEnded(circleId: String, sosId: String, at: Long)

    /** Whether the SOS's raiser left the members this device shows there. False while the file doesn't read. */
    fun rosterEnded(circleId: String, sosId: String): Boolean

    /** The SOS's raiser left the members shown there; [at] as for [markEnded]. */
    fun markRosterEnded(circleId: String, sosId: String, at: Long)

    /** Whether the SOS's alarm sounded on this device. False while the file doesn't read. */
    fun rang(circleId: String, sosId: String): Boolean

    /** The SOS's alarm sounded here; [at] as for [markEnded]. */
    fun markRang(circleId: String, sosId: String, at: Long)

    /** Drop this account's marks of every SOS dated before [cutoff]. */
    fun pruneBefore(cutoff: Long)

    companion object {
        /** An SOS older than this by its SEALED time never alarms (it's still listed while genuine). As iOS. */
        const val MAX_SOS_ALARM_AGE_MS = 86_400_000L

        /**
         * How many payload hashes one SOS keeps, the newest: each reseal adds one, and the payload
         * served now is the one that matters after the next key change. As iOS.
         */
        const val MAX_HASHES = 16
    }
}

class SecurePrefsSosMarks(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : SosMarks {

    private val prefs by lazy { prefsProvider(context) }

    /** The canonical account id, its entries under another spelling moved there first (AccountIds). */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId)

    /** "{owner}|{circle}|{sos}": this account's only, each id in its one spelling. */
    private fun key(circleId: String, sosId: String): String? =
        account.get()?.let { "$it|${AccountIds.storeKey(circleId)}|${AccountIds.storeKey(sosId)}" }

    /** [read]'s value, or false while the file doesn't read. */
    private fun readOrFalse(read: () -> Boolean): Boolean =
        try { StoreUnreadable.reading(STORE, read) } catch (e: StoreUnreadable) { false }

    override fun readable(): Boolean {
        if (account.get() == null) return false
        return readOrFalse { prefs.all; true }
    }

    /** [write] in one commit, only when the file reads: nothing is written over one that doesn't. */
    private fun writeIfReadable(write: SharedPreferences.Editor.() -> Unit) {
        runCatching {
            prefs.all
            prefs.edit().apply(write).commit()
        }
    }

    /** The SOS's date for pruning, kept from the first mark. */
    private fun SharedPreferences.Editor.dated(key: String, at: Long) {
        if (!prefs.contains("$key$AT")) putLong("$key$AT", at)
    }

    /** The SOS's payload hashes seen current here, oldest first. */
    private fun seen(key: String): List<String> =
        prefs.getString("$key$SEEN", null)?.split(HASH_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    override fun seenCurrent(circleId: String, sosId: String, hash: String): Boolean {
        val key = key(circleId, sosId) ?: return false
        return readOrFalse { hash in seen(key) }
    }

    override fun markSeenCurrent(circleId: String, sosId: String, hash: String, at: Long) {
        val key = key(circleId, sosId) ?: return
        writeIfReadable {
            val seen = seen(key)
            // The newest kept, oldest out; one seen again moves to the newest end.
            if (seen.lastOrNull() != hash) {
                putString("$key$SEEN", (seen - hash + hash).takeLast(SosMarks.MAX_HASHES).joinToString(HASH_SEPARATOR))
            }
            dated(key, at)
        }
    }

    override fun ended(circleId: String, sosId: String): Boolean {
        val key = key(circleId, sosId) ?: return false
        return readOrFalse { prefs.getBoolean("$key$ENDED", false) }
    }

    override fun markEnded(circleId: String, sosId: String, at: Long) = flag(circleId, sosId, ENDED, at)

    override fun rosterEnded(circleId: String, sosId: String): Boolean {
        val key = key(circleId, sosId) ?: return false
        return readOrFalse { prefs.getBoolean("$key$ROSTER_ENDED", false) }
    }

    override fun markRosterEnded(circleId: String, sosId: String, at: Long) = flag(circleId, sosId, ROSTER_ENDED, at)

    override fun rang(circleId: String, sosId: String): Boolean {
        val key = key(circleId, sosId) ?: return false
        return readOrFalse { prefs.getBoolean("$key$RANG", false) }
    }

    override fun markRang(circleId: String, sosId: String, at: Long) = flag(circleId, sosId, RANG, at)

    private fun flag(circleId: String, sosId: String, suffix: String, at: Long) {
        val key = key(circleId, sosId) ?: return
        writeIfReadable {
            putBoolean("$key$suffix", true)
            dated(key, at)
        }
    }

    override fun pruneBefore(cutoff: Long) {
        val owner = account.get() ?: return
        runCatching {
            val all = prefs.all
            val stale = all.filter { (k, v) -> AccountEntries.isOwners(k, owner) && k.endsWith(AT) && v is Long && v < cutoff }
                .keys.map { it.removeSuffix(AT) }.toSet()
            if (stale.isEmpty()) return
            prefs.edit().apply {
                all.keys.filter { k -> k.substringBefore('#') in stale }.forEach { remove(it) }
            }.commit()
        }
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "SOS marks"
        const val FILE_NAME = "sos_marks"
        const val SEEN = "#seen"
        const val HASH_SEPARATOR = ","
        const val ENDED = "#ended"
        const val ROSTER_ENDED = "#rosterEnded"
        const val RANG = "#rang"
        const val AT = "#at"
    }
}
