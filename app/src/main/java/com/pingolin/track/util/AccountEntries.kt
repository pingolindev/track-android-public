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
import android.util.Log

/**
 * A store kept per account on this phone, as "{owner}|…" entries, the owner the account's canonical
 * id ([AccountIds]). The same account keeps its entries across a sign-out; an account's sign-in
 * deletes every other account's ([keepOnly]), and an erasure deletes them all ([wipeAll]): an erased
 * account's data (other people's user ids among it) never stays on the phone (as the iOS app's
 * RefreshLedger does).
 */
interface KeptPerAccount {

    /**
     * An account signed in here: only [owner]'s entries stay ([AccountEntries.keepOnly]). Another
     * account's go only when [mayDelete] says so for it: the stores whose loss loosens trust keep
     * them unless the person typed another email than that account's (AccountEmails.guard).
     */
    fun keepOnly(owner: String, mayDelete: (String) -> Boolean = { true })

    /** This account was erased: no account's entries stay ([AccountEntries.wipe]). */
    fun wipeAll()
}

/** What [KeptPerAccount]'s stores do with their files. */
internal object AccountEntries {

    private const val TAG = "AccountEntries"

    /** A key's owner part: before its first '|'. */
    fun ownerOf(key: String): String = key.substringBefore('|')

    /**
     * Whether [key] is [owner]'s: its owner part spells the same account (the same UUID's bytes). A
     * part that isn't a UUID is compared ignoring case: it names no account.
     */
    fun isOwners(key: String, owner: String): Boolean {
        val part = ownerOf(key)
        return AccountIds.same(part, owner) || part.equals(owner, ignoreCase = true)
    }

    /**
     * The owner a store reads and writes under: the signed-in account's canonical id, its entries
     * kept under another spelling of it moved there first ([adopt]) and, for a store of circles,
     * each circle's entries folded under its one spelling ([CircleEntries.fold], by [circles]),
     * once per process, again while the file doesn't read or a fold doesn't land.
     */
    class Owner(
        private val store: String,
        private val prefs: () -> SharedPreferences,
        private val ownerUserId: () -> String?,
        private val circles: CircleEntries.Rules? = null,
    ) {
        @Volatile
        private var adopted: String? = null

        /** "{owner}|{circle}" of each circle read as unreadable ([CircleEntries.Folded.unreadable]). */
        @Volatile
        private var unreadable: Set<String> = emptySet()

        fun get(): String? {
            val owner = ownerUserId()?.takeIf { it.isNotEmpty() } ?: return null
            if (adopted != owner) synchronized(this) {
                if (adopted != owner && adopt(prefs, owner, store) && folded(owner)) adopted = owner
            }
            return owner
        }

        private fun folded(owner: String): Boolean {
            val rules = circles ?: return true
            val folded = CircleEntries.fold(store, prefs, owner, rules)
            unreadable = folded.unreadable
            return folded.done
        }

        /** "{owner}|{circle}": [circleId]'s key here, in its one spelling ([AccountIds.storeKey]); null while nobody is signed in. */
        fun key(circleId: String): String? = get()?.let { "$it|${AccountIds.storeKey(circleId)}" }

        /**
         * [StoreUnreadable] when [key], under any spelling of its circle, is an entry of a circle read
         * as unreadable: its entries under two spellings couldn't be folded, or their fold didn't
         * land yet.
         */
        fun check(key: String) {
            val now = unreadable
            if (now.isEmpty()) return
            val owner = ownerOf(key)
            val (part, _) = CircleEntries.split(key, owner) ?: return
            if ("$owner|${AccountIds.canonical(part) ?: part}" in now) throw StoreUnreadable(store)
        }

        /**
         * Every key of [circleId]'s here, under any spelling of it (and with [rest], of that entry
         * only), for a removal that must leave none behind: a fold would bring another spelling's
         * back. [StoreUnreadable] while the file doesn't read.
         */
        fun keysOf(circleId: String, rest: String? = null): List<String> {
            val owner = get() ?: return emptyList()
            val all = StoreUnreadable.reading(store) { prefs().all }
            return CircleEntries.keysOf(all, owner, circleId, circles, rest)
        }

        /** [circleId]'s entries are gone under every spelling: it reads as unreadable no more. */
        fun forgotten(circleId: String) {
            val key = key(circleId) ?: return
            unreadable = unreadable - key
        }
    }

    /**
     * Move every entry kept under another spelling of [owner] (the same account's bytes) to
     * [owner]'s key, dropping nothing: sets are merged; for any other value [owner]'s own stays, and
     * the move is logged. Read first: a file that doesn't read moves nothing and has nothing written
     * over it. True when nothing is left to move.
     */
    fun adopt(prefs: () -> SharedPreferences, owner: String, store: String): Boolean {
        val all = try {
            StoreUnreadable.reading(store) { prefs().all }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: entries under another spelling of this account are moved later")
            return false
        }
        val moving = all.filterKeys { key -> '|' in key && ownerOf(key) != owner && AccountIds.same(ownerOf(key), owner) }
        if (moving.isEmpty()) return true
        val moved = runCatching {
            val editor = prefs().edit()
            for ((key, value) in moving) {
                val target = owner + key.substring(key.indexOf('|'))
                val kept = all[target]
                when {
                    kept == null -> put(editor, target, value)
                    kept is Set<*> && value is Set<*> -> editor.putStringSet(target, (kept + value).filterIsInstance<String>().toSet())
                    else -> Log.w(TAG, "$store: an entry under another spelling of this account kept this account's own value")
                }
                editor.remove(key)
            }
            editor.commit()
        }.getOrDefault(false)
        if (moved) Log.i(TAG, "$store: ${moving.size} entr(ies) moved under this account's canonical id")
        return moved
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            is Long -> editor.putLong(key, value)
            is Int -> editor.putInt(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
        }
    }

    /**
     * Delete every entry of [prefs] that isn't [owner]'s, where [mayDelete] allows it for the
     * account it is ([ownerOf]). Read first: a file that doesn't read loses nothing and has nothing
     * written over it, and the next sign-in tries again. True when none that may go is left.
     */
    fun keepOnly(prefs: () -> SharedPreferences, owner: String, store: String, mayDelete: (String) -> Boolean = { true }): Boolean {
        val others = try {
            StoreUnreadable.reading(store) { prefs().all.keys.filterNot { isOwners(it, owner) } }
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: another account's entries are left until the next sign-in")
            return false
        }
        val going = others.filter { mayDelete(ownerOf(it)) }
        if (going.isEmpty()) return true
        val deleted = runCatching { prefs().edit().apply { going.forEach { remove(it) } }.commit() }.getOrDefault(false)
        if (!deleted) Log.w(TAG, "$store: another account's entries couldn't be deleted; the next sign-in tries again")
        return deleted
    }

    /**
     * Delete every entry of [prefs] without reading it (an erasure), or the file [fileName] itself
     * when it can't be opened. True when nothing is left.
     */
    fun wipe(context: Context, prefs: () -> SharedPreferences, fileName: String): Boolean {
        val wiped = runCatching { prefs().edit().clear().commit() }.getOrDefault(false) ||
            runCatching { context.deleteSharedPreferences(fileName) }.getOrDefault(false)
        if (!wiped) Log.w(TAG, "$fileName couldn't be wiped")
        return wiped
    }
}
