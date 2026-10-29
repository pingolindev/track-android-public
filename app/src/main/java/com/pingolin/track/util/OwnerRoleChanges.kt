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
 * The role changes this user made on the server in a circle with refresh records, until a
 * rotation of this device's records them: only a record signed by the chain's owner changes a
 * role, and no device copies a role from the server's list. The owner's device rotates right
 * after a promotion or demotion, or at its next rotation while it's behind. Kept per account,
 * as "owner", "admin" or "member", the latest change of each member winning.
 *
 * A change the file can't take now (it doesn't read, or the write doesn't land) is held for the
 * session and counts as made: every read makes it on what the file holds, and it goes in with the
 * next write that lands, or as soon as the file reads again. Nothing is ever written over a file
 * that doesn't read.
 */
interface OwnerRoleChanges : KeptPerAccount {

    /**
     * This user changed [userId]'s role in [circleId] to [role] on the server. False when it isn't
     * on disk yet: it's held for the session.
     */
    fun put(circleId: String, userId: String, role: String): Boolean

    /**
     * The changes of [circleId] not recorded yet, by user id (in its one spelling). [StoreUnreadable]
     * while they can't be read.
     */
    fun of(circleId: String): Map<String, String>

    /**
     * A record of [circleId] from here was taken: each of [written]'s members' changes is recorded, or
     * never can be from here. Only while the change still holds the role written: one made since stays
     * owed (a demotion made while the promotion's record was on its way). Held for the session while
     * it can't be written.
     */
    fun settled(circleId: String, written: Map<String, String>)

    /** [circleId] is gone from here: its changes go with it, whatever they held. */
    fun forget(circleId: String)

    /** Whether [circleId]'s changes can be read now: until they can, nothing they decide runs. */
    fun readable(circleId: String): Boolean = runCatching { of(circleId) }.isSuccess
}

class SecurePrefsOwnerRoleChanges(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : OwnerRoleChanges {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    /** This account's only: another account on this phone sees none of them. */
    private fun key(circleId: String): String? = account.key(circleId)

    /** [StoreUnreadable] when they can't be read, or two spellings of the circle hold two roles for one member. */
    private fun read(key: String): Map<String, String> = StoreUnreadable.reading(STORE) {
        account.check(key)
        prefs.getStringSet(key, null).orEmpty().mapNotNull { entry ->
            entry.split('=', limit = 2).takeIf { it.size == 2 }?.let { (id, role) -> id to role }
        }.toMap()
    }

    private fun write(key: String, changes: Map<String, String>): Boolean = runCatching {
        if (changes.isEmpty()) {
            prefs.edit().remove(key).commit()
        } else {
            prefs.edit().putStringSet(key, changes.map { (id, role) -> "$id=$role" }.toSet()).commit()
        }
    }.getOrDefault(false)

    /** The changes each circle's file couldn't take yet, in order: for the session. */
    private val unwritten = mutableMapOf<String, MutableList<(Map<String, String>) -> Map<String, String>>>()

    /** What stands for [key]: the file's, with what it couldn't take yet made on it; written now if it can be. */
    @Synchronized
    private fun state(key: String): Map<String, String> {
        val held = unwritten[key].orEmpty()
        val made = held.fold(read(key)) { s, change -> change(s) }
        if (held.isNotEmpty() && write(key, made)) unwritten.remove(key)
        return made
    }

    /** [change] made for [key]: on disk now, or held for the session (false). */
    @Synchronized
    private fun change(key: String, change: (Map<String, String>) -> Map<String, String>): Boolean {
        val held = unwritten.getOrPut(key) { mutableListOf() }
        held += change
        val base = try { read(key) } catch (e: StoreUnreadable) { return false }
        if (!write(key, held.fold(base) { s, c -> c(s) })) return false
        unwritten.remove(key)
        return true
    }

    override fun put(circleId: String, userId: String, role: String): Boolean {
        val key = key(circleId) ?: return false
        return change(key) { it + (AccountIds.storeKey(userId) to role) }
    }

    override fun of(circleId: String): Map<String, String> = key(circleId)?.let(::state).orEmpty()

    override fun settled(circleId: String, written: Map<String, String>) {
        val key = key(circleId) ?: return
        val done = written.mapKeys { (userId, _) -> AccountIds.storeKey(userId) }
        change(key) { changes -> changes.filter { (userId, role) -> done[userId] != role } }
    }

    @Synchronized
    override fun forget(circleId: String) {
        val key = key(circleId) ?: return
        unwritten.remove(key)
        // Under every spelling: a fold would bring another spelling's back.
        val keys = runCatching { account.keysOf(circleId) }.getOrDefault(emptyList()) + key
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
        const val STORE = "owner role changes"
        const val FILE_NAME = "owner_role_changes"

        /**
         * A circle's role changes kept under two spellings, folded: each member's, where they
         * agree; two roles for one member refuse the circle, so nothing they decide runs.
         */
        val RULES = object : CircleEntries.Rules {
            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap {
                entries[""]?.let { held -> put("", CircleEntries.perId(held, "=", AccountIds::storeKey) { roles -> roles.singleOrNull() }) }
            }
        }
    }
}
