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
 * The owner's hand-overs this device recorded in a circle's refresh records, until the server's
 * transfer to the same member lands or is refused for good: the record first, then
 * transfer_ownership, retried after a transient failure, keyed to the circle, the hand-over's
 * version and its target. Only this device's own memory of its own hand-over counts: nothing
 * here comes from the server's word. Kept per account.
 */
interface HandOvers : KeptPerAccount {

    /** The record that made [target] the owner, as version [version] of the circle's key. */
    data class HandOver(val version: Int, val target: String)

    /** This device's record handing [circleId] over landed. */
    fun remember(circleId: String, handOver: HandOver)

    /**
     * The hand-over recorded for [circleId], if any. [StoreUnreadable] while it can't be told: the
     * file doesn't read, or two spellings of the circle hold two hand-overs.
     */
    fun of(circleId: String): HandOver?

    /** Every hand-over waiting for its transfer, by circle id: those that can be told. */
    fun all(): Map<String, HandOver>

    /** Its transfer landed, or was refused for good. */
    fun forget(circleId: String)
}

class SecurePrefsHandOvers(
    private val context: Context,
    private val ownerUserId: () -> String?,
    /** Tests pass plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    private val prefsProvider: (Context) -> SharedPreferences = { SecurePrefs.open(it, FILE_NAME) },
) : HandOvers {

    private val prefs by lazy { prefsProvider(context) }

    /**
     * The canonical account id, its entries under another spelling moved there first (AccountIds),
     * and each circle's under its one spelling ([RULES]).
     */
    private val account = AccountEntries.Owner(STORE, { prefs }, ownerUserId, RULES)

    /** This account's only: another account on this phone sees none of them. */
    private fun prefix(): String? = account.get()?.let { "$it|" }

    private fun parse(value: String?): HandOvers.HandOver? =
        value?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (version, target) ->
            version.toIntOrNull()?.let { HandOvers.HandOver(it, target) }
        }

    override fun remember(circleId: String, handOver: HandOvers.HandOver) {
        val key = account.key(circleId) ?: return
        runCatching {
            // Read first: nothing written over a file that doesn't read, or two hand-overs that disagree.
            account.check(key)
            prefs.getString(key, null)
            prefs.edit().putString(key, "${handOver.version}|${AccountIds.storeKey(handOver.target)}").commit()
        }
    }

    override fun of(circleId: String): HandOvers.HandOver? = account.key(circleId)?.let { key ->
        StoreUnreadable.reading(STORE) {
            account.check(key)
            parse(prefs.getString(key, null))
        }
    }

    override fun all(): Map<String, HandOvers.HandOver> {
        val prefix = prefix() ?: return emptyMap()
        return runCatching {
            prefs.all.mapNotNull { (key, value) ->
                if (!key.startsWith(prefix) || runCatching { account.check(key) }.isFailure) return@mapNotNull null
                parse(value as? String)?.let { key.removePrefix(prefix) to it }
            }.toMap()
        }.getOrNull().orEmpty()
    }

    override fun forget(circleId: String) {
        val key = account.key(circleId) ?: return
        // Under every spelling: a fold would bring another spelling's back.
        val keys = runCatching { account.keysOf(circleId) }.getOrDefault(emptyList()) + key
        if (runCatching { prefs.edit().apply { keys.forEach { remove(it) } }.commit() }.getOrDefault(false)) account.forgotten(circleId)
    }

    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        AccountEntries.keepOnly({ prefs }, owner, STORE, mayDelete)
    }

    override fun wipeAll() {
        AccountEntries.wipe(context, { prefs }, FILE_NAME)
    }

    private companion object {
        const val STORE = "hand-overs"
        const val FILE_NAME = "hand_overs"

        /**
         * A circle's hand-over kept under two spellings, folded: kept where they agree; two that
         * differ refuse the circle, so nothing hands it over from here.
         */
        val RULES = object : CircleEntries.Rules {
            override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) = buildMap {
                entries[""]?.let { held ->
                    put("", CircleEntries.agreedOrRefused(held) { value ->
                        (value as? String)?.split('|', limit = 2)?.takeIf { it.size == 2 }?.let { (version, target) -> "$version|${AccountIds.storeKey(target)}" }
                    })
                }
            }
        }
    }
}
