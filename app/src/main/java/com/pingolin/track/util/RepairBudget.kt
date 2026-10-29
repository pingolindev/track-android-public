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
 * What this device spent of its repair budget, per account and circle: the rejected versions it
 * repaired (at most one repair each), when it last repaired (at most one an hour), and whom its
 * last repair's rejected record didn't allow (so a repair whose successor is rejected for the
 * same member stops instead of repairing again). Kept on disk: a restart spends nothing again.
 */
interface RepairBudget : KeptPerAccount {

    /** The rejected versions this device repaired. [StoreUnreadable] while the budget can't be read (and below). */
    fun repaired(circleId: String): Set<Int>

    fun lastRepairAt(circleId: String): Long?

    /** The members the rejected record this device last repaired didn't allow. */
    fun lastRepairedFor(circleId: String): Set<String>

    /**
     * A repair of [version] (whose record didn't allow [notAllowed]) landed at [atMs]. Nothing is
     * written while the budget can't be read.
     */
    fun spend(circleId: String, version: Int, notAllowed: Collection<String>, atMs: Long)
}

class SecurePrefsRepairBudget(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : RepairBudget {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    private fun key(circleId: String): String? = account.key(circleId)

    /** [what], for [key]'s circle; [StoreUnreadable] when it can't be read. */
    private fun <T> read(key: String, what: () -> T): T = StoreUnreadable.reading(STORE) {
        account.check(key)
        what()
    }

    override fun repaired(circleId: String): Set<Int> = key(circleId)?.let { key ->
        read(key) { prefs.getStringSet("$key$REPAIRED", null).orEmpty().mapNotNull { it.toIntOrNull() }.toSet() }
    }.orEmpty()

    override fun lastRepairAt(circleId: String): Long? = key(circleId)?.let { key ->
        read(key) { prefs.getLong("$key$AT", -1L) }.takeIf { it >= 0 }
    }

    override fun lastRepairedFor(circleId: String): Set<String> = key(circleId)?.let { key ->
        read(key) { prefs.getStringSet("$key$FOR", null)?.toSet() }
    }.orEmpty()

    override fun spend(circleId: String, version: Int, notAllowed: Collection<String>, atMs: Long) {
        val key = key(circleId) ?: return
        val repaired = try { repaired(circleId) } catch (e: StoreUnreadable) { return }
        runCatching {
            prefs.edit()
                .putStringSet("$key$REPAIRED", repaired.map { it.toString() }.toSet() + version.toString())
                .putLong("$key$AT", atMs)
                .putStringSet("$key$FOR", notAllowed.map(AccountIds::storeKey).toSet())
                .commit()
        }
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "repair budget"
        const val FILE_NAME = "repair_budget"
        const val REPAIRED = "#repaired"
        const val AT = "#at"
        const val FOR = "#for"

        /**
         * A circle's budget kept under two spellings, folded: every version repaired under any of
         * them, the later last repair, and everyone either named.
         */
        val RULES = object : CircleEntries.Rules {
            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap {
                entries[REPAIRED]?.let { put(REPAIRED, CircleEntries.union(it)) }
                entries[AT]?.let { put(AT, CircleEntries.latest(it)) }
                entries[FOR]?.let { put(FOR, CircleEntries.union(it, AccountIds::storeKey)) }
            }
        }
    }
}
