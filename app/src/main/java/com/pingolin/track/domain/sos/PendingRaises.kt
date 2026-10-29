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
import com.pingolin.track.util.SecurePrefs
import com.pingolin.track.util.StoreUnreadable

/**
 * A help request this phone owes its circle: pressed, and not known to have reached the server.
 * Kept so that a process Android ends while it waits for a connection never loses it silently:
 * the next start sends it on, or tells the person it didn't go.
 */
data class PendingRaise(
    /** The circle it was raised in, and the only one it is ever sealed into. */
    val circleId: String,
    /** The press: the countdown's start. The update rule's window and what the raise may carry run from
     * it.
     */
    val pressAt: Long,
    /**
     * The raise time: when the countdown completed. The alert says it was asked then, however late
     * it goes, and its 24 h count from it.
     */
    val raisedAt: Long,
    val state: State,
    /** The person has been told, outside the app, that it didn't go: once is enough. */
    val told: Boolean = false,
    /**
     * Waiting on this phone's session store, not a connection: a resumed raise says it again in
     * those words.
     */
    val storeRefused: Boolean = false,
) {
    enum class State {
        /** A try is going out. A process that ends now may have reached the server with it. */
        OUT,

        /** Nothing left this phone: it goes as soon as a connection, or the session store, is back. */
        WAITING,

        /** No answer: it may have been stored, so it is never sent again by itself. */
        UNSURE,

        /** Refused, couldn't be made here, or waited out its 24 h: the circle wasn't notified. */
        NOT_SENT,
    }
}

/**
 * The signed-in account's [PendingRaise], at most one: kept per account, in its own encrypted file,
 * and wiped at sign-out, so a request raised by one account never goes out as another's.
 */
interface PendingRaises {
    /** This account's, or null: none, nobody signed in, or a file that doesn't read. */
    fun get(): PendingRaise?

    fun put(raise: PendingRaise)

    fun remove()

    /** Sign-out and erasure: every account's goes. */
    fun wipeAll()
}

class SecurePrefsPendingRaises(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : PendingRaises {

    private val prefs by lazy { prefsProvider(context) }

    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId)

    /** "{owner}|raise": this account's only. */
    private fun key(): String? = account.get()?.let { "$it|raise" }

    override fun get(): PendingRaise? {
        val key = key() ?: return null
        val line = try {
            StoreUnreadable.reading(STORE) { prefs.getString(key, null) }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: no pending request for help read")
            null
        } ?: return null
        return decode(line)
    }

    override fun put(raise: PendingRaise) {
        val key = key() ?: return
        writeIfReadable { putString(key, encode(raise)) }
    }

    override fun remove() {
        val key = key() ?: return
        writeIfReadable { remove(key) }
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

    companion object {
        private const val TAG = "PendingRaises"
        private const val STORE = "pending requests for help"
        internal const val FILE_NAME = "pending_sos_raises"

        /** Waiting on the store gets a sixth field; every other line keeps the five
         * fields.
         */
        private const val STORE_REFUSED = "store"

        internal fun encode(raise: PendingRaise): String =
            listOfNotNull(
                raise.circleId, raise.pressAt, raise.raisedAt, raise.state.name, raise.told,
                STORE_REFUSED.takeIf { raise.storeRefused },
            ).joinToString("\t")

        internal fun decode(line: String): PendingRaise? {
            val parts = line.split('\t')
            if (parts.size != 5 && !(parts.size == 6 && parts[5] == STORE_REFUSED)) return null
            return PendingRaise(
                circleId = parts[0].takeIf { it.isNotEmpty() } ?: return null,
                pressAt = parts[1].toLongOrNull() ?: return null,
                raisedAt = parts[2].toLongOrNull() ?: return null,
                state = runCatching { PendingRaise.State.valueOf(parts[3]) }.getOrNull() ?: return null,
                told = parts[4].toBooleanStrictOrNull() ?: return null,
                storeRefused = parts.size == 6,
            )
        }

        /** The sign-out paths that hold no dispatcher: the whole file goes, whoever's. */
        fun wipe(context: Context) {
            SecurePrefsPendingRaises(context, { null }).wipeAll()
        }
    }
}

/** This process only: tests, and a dispatcher built without the encrypted file. Kept per account all the same. */
internal class MemoryPendingRaises(private val ownerUserId: () -> String?) : PendingRaises {
    private val byOwner = mutableMapOf<String, PendingRaise>()

    private fun owner(): String? = ownerUserId()?.let { com.pingolin.track.util.AccountIds.storeKey(it) }

    override fun get(): PendingRaise? = owner()?.let { byOwner[it] }

    override fun put(raise: PendingRaise) {
        owner()?.let { byOwner[it] = raise }
    }

    override fun remove() {
        owner()?.let { byOwner.remove(it) }
    }

    override fun wipeAll() = byOwner.clear()
}

