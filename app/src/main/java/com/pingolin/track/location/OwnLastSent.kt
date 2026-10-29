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

package com.pingolin.track.location

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pingolin.track.util.AccountEntries
import com.pingolin.track.util.SecurePrefs
import com.pingolin.track.util.StoreUnreadable

/**
 * What this phone last sealed and handed the stream for a circle, for the last location a member with
 * real-time sharing off leaves behind there. They send nothing more, so that row stays sealed under
 * the key it went with; after a rotation this phone seals the same point under the new key FROM THIS
 * RECORD, never from the row the server serves back: a served row may be a removed member's forgery.
 */
data class OwnLastSent(
    /** The device's pseudonym in the circle, as the row went under it. */
    val devicePseudonym: String,
    /** The payload as handed to the SDK, as the app had it: its nonce and ciphertext. */
    val nonce: String,
    val ciphertext: String,
    /** The session key the payload was sealed under, or null: the circle key, or an envelope. */
    val keyId: String?,
    /** An envelope's wrap of its message key for this circle, and the wrap's nonce. */
    val wrappedKey: String?,
    val wrappedKeyNonce: String?,
    /** The position as sealed (coarsened where the circle shares approximately), and its sealed time. */
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val timestampMs: Long,
    /** The circle's key version in use when it was sealed (or re-sealed). */
    val keyVersion: Int,
    /**
     * How coarse the position was sealed, in metres (0: the exact point; the circle's bubble
     * otherwise), or null for a record that doesn't say (one an older app version wrote): read as
     * exact. A re-seal never goes finer than the circle gets now.
     */
    val coarsenRadiusMeters: Double?,
    /** A newer version a re-seal was already tried for: once per version, whatever came of it. */
    val triedVersion: Int? = null,
    /**
     * A re-seal sent and not yet answered: its new payload's nonce and ciphertext, and the version it
     * named (`c:{version}`). Kept from before the request goes until its answer: a 200 lost on the way
     * back would otherwise leave this record naming the old bytes, and the next run take the server's
     * re-sealed row, this phone's own, for a stranger's. A later send leaves none.
     */
    val pendingNonce: String? = null,
    val pendingCiphertext: String? = null,
    val pendingVersion: Int? = null,
) {
    /** The re-seal that was pending, taken as landed: what the server holds now. */
    internal fun pendingLanded(): OwnLastSent = copy(
        nonce = pendingNonce ?: nonce, ciphertext = pendingCiphertext ?: ciphertext,
        keyId = pendingVersion?.let { "c:$it" } ?: keyId, wrappedKey = null, wrappedKeyNonce = null,
        keyVersion = pendingVersion ?: keyVersion, triedVersion = null,
        pendingNonce = null, pendingCiphertext = null, pendingVersion = null,
    )

    /** One line, tab-separated; "" for an absent value. */
    internal fun encode(): String = listOf(
        FORMAT, devicePseudonym, nonce, ciphertext, keyId.orEmpty(), wrappedKey.orEmpty(), wrappedKeyNonce.orEmpty(),
        latitude.toString(), longitude.toString(), accuracy?.toString().orEmpty(), timestampMs.toString(),
        keyVersion.toString(), triedVersion?.toString().orEmpty(), coarsenRadiusMeters?.toString().orEmpty(),
        pendingNonce.orEmpty(), pendingCiphertext.orEmpty(), pendingVersion?.toString().orEmpty(),
    ).joinToString("\t")

    internal companion object {
        private const val FORMAT = "3"

        /** Format 2 has no pending re-seal: records read with none. */
        private const val FORMAT_WITHOUT_PENDING = "2"

        /** Format 1 has no radius either: it reads as an unknown radius. */
        private const val FORMAT_WITHOUT_RADIUS = "1"

        /** The record a line holds, or null for one of another format or that doesn't parse. */
        fun decode(line: String): OwnLastSent? {
            val f = line.split('\t')
            val radius = when {
                f.size == 17 && f[0] == FORMAT -> f[13].ifEmpty { null }
                f.size == 14 && f[0] == FORMAT_WITHOUT_PENDING -> f[13].ifEmpty { null }
                f.size == 13 && f[0] == FORMAT_WITHOUT_RADIUS -> null
                else -> return null
            }
            val pending = if (f.size == 17) Triple(f[14].ifEmpty { null }, f[15].ifEmpty { null }, f[16].ifEmpty { null }) else null
            return runCatching {
                OwnLastSent(
                    devicePseudonym = f[1].ifEmpty { return null },
                    nonce = f[2].ifEmpty { return null },
                    ciphertext = f[3].ifEmpty { return null },
                    keyId = f[4].ifEmpty { null },
                    wrappedKey = f[5].ifEmpty { null },
                    wrappedKeyNonce = f[6].ifEmpty { null },
                    latitude = f[7].toDouble(),
                    longitude = f[8].toDouble(),
                    accuracy = f[9].ifEmpty { null }?.toDouble(),
                    timestampMs = f[10].toLong(),
                    keyVersion = f[11].toInt(),
                    coarsenRadiusMeters = radius?.toDouble(),
                    triedVersion = f[12].ifEmpty { null }?.toInt(),
                    pendingNonce = pending?.first,
                    pendingCiphertext = pending?.second,
                    pendingVersion = pending?.third?.toInt(),
                )
            }.getOrNull()
        }
    }
}

/**
 * This account's [OwnLastSent] records, by circle. Written at every send that leaves the circle's latest
 * row (live and liveness alike, never a history-only one); removed with the circle (left, deleted, gone)
 * and wholly at sign-out. Kept per account, in its own encrypted file: it holds coordinates. A file that
 * doesn't read reads as no record, and has nothing written over it.
 */
interface OwnLastSentStore {
    fun get(circleId: String): OwnLastSent?

    fun put(circleId: String, record: OwnLastSent)

    /**
     * [to] in place of [from], only while [circleId]'s record is still [from]: a re-seal run never
     * writes over a send that landed while it ran (as iOS's `update(from:to:)`), nor brings back a
     * record removed meanwhile. True when written.
     */
    fun update(circleId: String, from: OwnLastSent, to: OwnLastSent): Boolean

    fun remove(circleId: String)

    /** The circles this account has a record for, as their store keys. */
    fun circles(): List<String>

    /** Sign-out and erasure: every record goes, whoever's. */
    fun wipeAll()
}

class SecurePrefsOwnLastSent(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : OwnLastSentStore {

    private val prefs by lazy { prefsProvider(context) }

    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId)

    /** "{owner}|{circle}": this account's only, the id in its one spelling. */
    private fun key(circleId: String): String? = account.key(circleId)

    override fun get(circleId: String): OwnLastSent? {
        val key = key(circleId) ?: return null
        val line = try {
            StoreUnreadable.reading(STORE) { prefs.getString(key, null) }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: no record of what this phone last sent")
            null
        } ?: return null
        return OwnLastSent.decode(line)
    }

    @Synchronized
    override fun put(circleId: String, record: OwnLastSent) {
        val key = key(circleId) ?: return
        writeIfReadable { putString(key, record.encode()) }
    }

    @Synchronized
    override fun update(circleId: String, from: OwnLastSent, to: OwnLastSent): Boolean {
        if (get(circleId) != from) return false
        put(circleId, to)
        return get(circleId) == to
    }

    // Under update's lock, as the wipe is: one landing between its read and its write would be written back over.
    @Synchronized
    override fun remove(circleId: String) {
        val key = key(circleId) ?: return
        writeIfReadable { remove(key) }
    }

    override fun circles(): List<String> {
        val owner = account.get() ?: return emptyList()
        return runCatching {
            prefs.all.keys.filter { AccountEntries.isOwners(it, owner) }.map { it.substringAfter('|') }
        }.getOrDefault(emptyList())
    }

    @Synchronized
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
        const val TAG = "OwnLastSent"
        const val STORE = "own last sent"
        const val FILE_NAME = "own_last_sent"
    }
}
