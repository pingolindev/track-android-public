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
 * Members this user removed from a circle without a key change: revoke-access while this phone
 * was behind. This device names them "removed" in its own next rotation of the circle's refresh
 * records, whatever the server's flag says, and then forgets them. Kept per account, on disk
 * before the removal is reported done: a removal the record never names counts only while
 * they're off the member list.
 *
 * A change the file can't take now (it doesn't read, or the write doesn't land) is held for the
 * session and counts as made: every read makes it on what the file holds, and it goes in with the
 * next write that lands, or as soon as the file reads again. A restart before then loses it.
 * Nothing is ever written over a file that doesn't read.
 */
interface OwnRemovals : KeptPerAccount {

    /**
     * [userId] was removed from [circleId] here without a key change, at [atSecs] (Unix seconds).
     * False when it isn't on disk yet: it's held for the session.
     */
    fun add(circleId: String, userId: String, atSecs: Long): Boolean

    /** The removals of [circleId] this device's next rotation names. [StoreUnreadable] while they can't be read. */
    fun of(circleId: String): Set<String>

    /**
     * When [userId]'s removal from [circleId] was made here; null for none (or one kept without
     * its time). [StoreUnreadable] while they can't be read.
     */
    fun removedAt(circleId: String, userId: String): Long?

    /**
     * A rotation of [circleId] from here landed with [userIds] in its plan: they're named, or never
     * were in the chain. Or this user changed their mind about them. Held for the session while it
     * can't be written.
     */
    fun settled(circleId: String, userIds: Collection<String>)

    /**
     * A signed re-invite of [userId] from this device ([statementId], running until [expiresAtSecs])
     * cleared their removal. The removal is kept against that invitation until it ends: withdrawn
     * from here, it's noted again ([invitationWithdrawn]); run out unused, it comes back at the next
     * plan ([settleRunOut]). Nothing to keep when no removal of them stands.
     */
    fun clearedByInvitation(circleId: String, userId: String, statementId: String, expiresAtSecs: Long)

    /**
     * The invitation [statementId] was withdrawn from here: the removal it cleared is noted again
     * as it was, unless a later removal of them stands, which stays as it is.
     */
    fun invitationWithdrawn(circleId: String, statementId: String)

    /**
     * At a plan, with [circleId]'s complete member list [listed]: a re-invite that ran out (by
     * [nowSecs], with the grace an invitee has to appear) brings back the removal it cleared, as it
     * was, unless they're on the list, having joined with it: then it stays cleared, and they're
     * never removed. Either way its entry goes.
     */
    fun settleRunOut(circleId: String, listed: Collection<String>, nowSecs: Long)

    /** [circleId] is gone from here: its removals go with it, whatever they held. */
    fun forget(circleId: String)

    /** Whether [circleId]'s removals can be read now: until they can, nothing they decide runs. */
    fun readable(circleId: String): Boolean = runCatching { of(circleId) }.isSuccess
}

class SecurePrefsOwnRemovals(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : OwnRemovals {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    /** A circle's removals, by user id (with when, if kept), and those re-invites cleared, by statement id. */
    private data class State(val removals: Map<String, Long?>, val cleared: Map<String, Cleared>)

    private data class Cleared(val userId: String, val at: Long?, val expiresAtSecs: Long)

    /** The changes each circle's file couldn't take yet, in order: for the session. */
    private val unwritten = mutableMapOf<String, MutableList<(State) -> State>>()

    /** This account's only: another account on this phone sees none of them. */
    private fun key(circleId: String): String? = account.key(circleId)

    /**
     * What the file holds: each removal as "{user id}|{when}", and each cleared one as
     * "{user id}|{when}|{statement id}|{expires at}"; [StoreUnreadable] when it can't be read.
     */
    private fun read(key: String): State = StoreUnreadable.reading(STORE) {
        account.check(key)
        State(
            prefs.getStringSet(key, null).orEmpty().associate { entry ->
                val (id, at) = entry.split('|', limit = 2).let { it[0] to it.getOrNull(1)?.toLongOrNull() }
                id to at
            },
            prefs.getStringSet("$key$CLEARED", null).orEmpty().mapNotNull { entry ->
                val f = entry.split('|')
                if (f.size != 4) return@mapNotNull null
                val expires = f[3].toLongOrNull() ?: return@mapNotNull null
                f[2] to Cleared(f[0], f[1].toLongOrNull(), expires)
            }.toMap(),
        )
    }

    private fun write(key: String, state: State): Boolean = runCatching {
        val editor = prefs.edit()
        if (state.removals.isEmpty()) {
            editor.remove(key)
        } else {
            editor.putStringSet(key, state.removals.map { (id, at) -> if (at == null) id else "$id|$at" }.toSet())
        }
        if (state.cleared.isEmpty()) {
            editor.remove("$key$CLEARED")
        } else {
            editor.putStringSet(
                "$key$CLEARED",
                state.cleared.map { (statement, c) -> "${c.userId}|${c.at ?: ""}|$statement|${c.expiresAtSecs}" }.toSet(),
            )
        }
        editor.commit()
    }.getOrDefault(false)

    /** What stands for [key]: the file's, with what it couldn't take yet made on it; written now if it can be. */
    @Synchronized
    private fun state(key: String): State {
        val held = unwritten[key].orEmpty()
        val made = held.fold(read(key)) { s, change -> change(s) }
        if (held.isNotEmpty() && write(key, made)) unwritten.remove(key)
        return made
    }

    /** [change] made for [key]: on disk now, or held for the session (false). */
    @Synchronized
    private fun change(key: String, change: State.() -> State): Boolean {
        val held = unwritten.getOrPut(key) { mutableListOf() }
        held += change
        val base = try { read(key) } catch (e: StoreUnreadable) { return false }
        if (!write(key, held.fold(base) { s, c -> c(s) })) return false
        unwritten.remove(key)
        return true
    }

    override fun add(circleId: String, userId: String, atSecs: Long): Boolean {
        val key = key(circleId) ?: return false
        return change(key) { copy(removals = removals + (AccountIds.storeKey(userId) to atSecs)) }
    }

    override fun of(circleId: String): Set<String> = key(circleId)?.let { state(it).removals.keys }.orEmpty()

    override fun removedAt(circleId: String, userId: String): Long? = key(circleId)?.let { state(it).removals[AccountIds.storeKey(userId)] }

    override fun settled(circleId: String, userIds: Collection<String>) {
        val key = key(circleId) ?: return
        val done = userIds.map(AccountIds::storeKey).toSet()
        change(key) { copy(removals = removals.filterKeys { it !in done }) }
    }

    override fun clearedByInvitation(circleId: String, userId: String, statementId: String, expiresAtSecs: Long) {
        val key = key(circleId) ?: return
        val id = AccountIds.storeKey(userId)
        change(key) {
            if (id !in removals) {
                this
            } else {
                copy(removals = removals - id, cleared = cleared + (AccountIds.storeKey(statementId) to Cleared(id, removals[id], expiresAtSecs)))
            }
        }
    }

    override fun invitationWithdrawn(circleId: String, statementId: String) {
        val key = key(circleId) ?: return
        val statement = AccountIds.storeKey(statementId)
        change(key) {
            val c = cleared[statement] ?: return@change this
            copy(
                removals = if (c.userId in removals) removals else removals + (c.userId to c.at),
                cleared = cleared - statement,
            )
        }
    }

    override fun settleRunOut(circleId: String, listed: Collection<String>, nowSecs: Long) {
        val key = key(circleId) ?: return
        fun ranOut(c: Cleared) = c.expiresAtSecs + APPEARANCE_GRACE_SECS <= nowSecs
        val due = try { state(key).cleared.values.any(::ranOut) } catch (e: StoreUnreadable) { return }
        if (!due) return
        val on = listed.map(AccountIds::storeKey).toSet()
        change(key) {
            val back = cleared.values.filter { ranOut(it) && it.userId !in on && it.userId !in removals }
            copy(
                removals = removals + back.associate { it.userId to it.at },
                cleared = cleared.filterValues { !ranOut(it) },
            )
        }
    }

    @Synchronized
    override fun forget(circleId: String) {
        val key = key(circleId) ?: return
        unwritten.remove(key)
        // Under every spelling: a fold would bring another spelling's back.
        val keys = runCatching { account.keysOf(circleId) }.getOrDefault(emptyList()) + listOf(key, "$key$CLEARED")
        if (runCatching { prefs.edit().apply { keys.forEach { remove(it) } }.commit() }.getOrDefault(false)) account.forgotten(circleId)
    }

    @Synchronized
    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        unwritten.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    @Synchronized
    override fun wipeAll() {
        unwritten.clear()
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "own removals"
        const val FILE_NAME = "own_removals"
        /** "{owner}|{circle}#cleared": the removals re-invites from here cleared, by statement id. */
        const val CLEARED = "#cleared"
        /** An invitee may appear this long after their invitation's expiry (KeyAccessApprovals.INVITE_APPEARANCE_GRACE_MS). */
        const val APPEARANCE_GRACE_SECS = KeyAccessApprovals.INVITE_APPEARANCE_GRACE_MS / 1000

        /**
         * A circle's removals kept under two spellings, folded: every member removed under any of
         * them stays removed, with the later time; every removal a re-invite cleared stays cleared.
         */
        val RULES = object : CircleEntries.Rules {
            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap {
                entries[""]?.let { held ->
                    put("", CircleEntries.perId(held, "|", AccountIds::storeKey) { ats -> ats.mapNotNull { it.toLongOrNull() }.maxOrNull()?.toString() ?: "" })
                }
                entries[CLEARED]?.let { held ->
                    put(CLEARED, CircleEntries.union(held) { entry ->
                        entry.split('|').takeIf { it.size == 4 }?.let { f -> "${AccountIds.storeKey(f[0])}|${f[1]}|${AccountIds.storeKey(f[2])}|${f[3]}" } ?: entry
                    })
                }
            }
        }
    }
}
