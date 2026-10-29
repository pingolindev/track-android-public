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
 * Single source of truth for the device-local "I verified this member's safety
 * number out of band" marker shown in [com.pingolin.track.ui.circles.MemberDetailFragment].
 *
 * A mark vouches for the EXACT keys that were compared: it is stored with the member's
 * sharing and signing keys, and reads as unverified the moment the keys differ. That
 * is what lets it survive a sign-out of the same account: a mark not bound to keys would
 * vouch for whatever key the next login trusted on first use, so it would have to be
 * wiped at every sign-out, and the work of comparing safety numbers lost every time.
 * Marks are kept per account and dropped when a different account signs in on this
 * device ([keepOnly]).
 *
 * Encrypted at rest: a member id next to their public keys is social-graph metadata.
 *
 * Kept as "{owner}|{circle}:{member}", the circle and member in their one spelling
 * ([AccountIds.storeKey]); what an earlier version kept under another is folded there at
 * load ([RULES]).
 */
object MemberVerificationStore {
    private const val STORE = "verified marks"
    private const val FILE_NAME = "member_verification_v2"

    /** Unbound marks written by earlier versions; they cannot be trusted across a
     * sign-out, so they go.
     */
    private const val LEGACY_PREFS_NAME = "member_verification"

    @Volatile
    private var opened: SharedPreferences? = null

    /**
     * The account whose marks under another spelling of its id were moved to its canonical id
     * (AccountIds), and whose marks under another spelling of a circle or member were folded
     * under their one spelling ([RULES]).
     */
    @Volatile
    private var adopted: String? = null

    /** "{owner}|{circle}" of each circle whose marks read as none: their fold didn't land yet. */
    @Volatile
    private var unreadable: Set<String> = emptySet()

    /**
     * A member's marks kept under two spellings of the circle (or of the member's id),
     * folded: kept only where every spelling holds the same mark; otherwise unticked, and the
     * person compares again.
     */
    internal val RULES = object : CircleEntries.Rules {
        override fun rest(rest: String): String = if (rest.startsWith(":")) ":${AccountIds.storeKey(rest.substring(1))}" else rest

        override fun fold(entries: Map<String, List<CircleEntries.Held>>, spellings: Set<String>) =
            entries.filterKeys { it.startsWith(":") }.mapValues { (_, held) -> CircleEntries.agreed(held, spellings) { it as? String } }
    }

    /** Tests swap in plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    @Volatile
    internal var prefsFor: (Context) -> SharedPreferences = { context ->
        opened ?: synchronized(this) {
            opened ?: run {
                runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
                SecurePrefs.open(context.applicationContext, FILE_NAME)
            }.also { opened = it }
        }
    }
        set(value) {
            field = value
            adopted = null
            unreadable = emptySet()
        }

    /**
     * [ownerUserId]'s marks, those kept under another spelling of its id moved there first, and
     * those under another spelling of a circle or member folded, once per process.
     */
    private fun prefs(context: Context, ownerUserId: String): SharedPreferences {
        if (adopted != ownerUserId) synchronized(this) {
            if (adopted != ownerUserId && AccountEntries.adopt({ prefsFor(context) }, ownerUserId, STORE) &&
                CircleEntries.fold(STORE, { prefsFor(context) }, ownerUserId, RULES).also { unreadable = it.unreadable }.done
            ) {
                adopted = ownerUserId
            }
        }
        return prefsFor(context)
    }

    private fun key(ownerUserId: String, circleId: String, userId: String) =
        "$ownerUserId|${AccountIds.storeKey(circleId)}:${AccountIds.storeKey(userId)}"

    /** Whether the marks of [circleId] can't be read now: its fold didn't land yet. */
    private fun unreadable(ownerUserId: String, circleId: String) = "$ownerUserId|${AccountIds.storeKey(circleId)}" in unreadable

    private fun keysOf(sharingHex: String, signingHex: String) =
        "${sharingHex.lowercase()}|${signingHex.lowercase()}"

    /**
     * Epoch millis [ownerUserId] marked the member verified, or 0 if they did not, or if
     * the member's keys are no longer the ones that were verified.
     */
    fun getVerifiedAt(
        context: Context,
        ownerUserId: String,
        circleId: String,
        userId: String,
        sharingHex: String,
        signingHex: String,
    ): Long {
        if (sharingHex.isBlank() || signingHex.isBlank()) return 0L
        val stored = runCatching {
            val prefs = prefs(context, ownerUserId)
            if (unreadable(ownerUserId, circleId)) null else prefs.getString(key(ownerUserId, circleId, userId), null)
        }.getOrNull() ?: return 0L
        val at = stored.substringBefore('|').toLongOrNull() ?: return 0L
        return if (stored.substringAfter('|') == keysOf(sharingHex, signingHex)) at else 0L
    }

    /** Mark the member verified now, for exactly these keys. */
    fun setVerified(
        context: Context,
        ownerUserId: String,
        circleId: String,
        userId: String,
        sharingHex: String,
        signingHex: String,
        whenMs: Long,
    ) {
        if (sharingHex.isBlank() || signingHex.isBlank()) return
        runCatching {
            val prefs = prefs(context, ownerUserId)
            // Nothing written for a circle whose marks can't be read now.
            if (unreadable(ownerUserId, circleId)) return
            prefs.edit()
                .putString(key(ownerUserId, circleId, userId), "$whenMs|${keysOf(sharingHex, signingHex)}")
                .apply()
        }
    }

    /** Drop a single member's verification marker (e.g. after their pin changes), under every spelling. */
    fun clearVerified(context: Context, ownerUserId: String, circleId: String, userId: String) {
        runCatching {
            val prefs = prefs(context, ownerUserId)
            val keys = runCatching { CircleEntries.keysOf(prefs.all, ownerUserId, circleId, RULES, ":$userId") }.getOrDefault(emptyList())
            prefs.edit().apply { (keys + key(ownerUserId, circleId, userId)).forEach { remove(it) } }.apply()
        }
    }

    /** Drop every marker that does not belong to [ownerUserId] under any spelling of its id (another account signed in). */
    fun keepOnly(context: Context, ownerUserId: String) {
        removeWhere(context) { !AccountEntries.isOwners(it, ownerUserId) }
    }

    /** Drop [ownerUserId]'s markers, under any spelling of its id (their account was deleted). */
    fun forget(context: Context, ownerUserId: String) {
        removeWhere(context) { AccountEntries.isOwners(it, ownerUserId) }
    }

    private fun removeWhere(context: Context, predicate: (String) -> Boolean) {
        runCatching {
            val prefs = prefsFor(context)
            val doomed = prefs.all.keys.filter(predicate)
            if (doomed.isNotEmpty()) {
                val editor = prefs.edit()
                doomed.forEach { editor.remove(it) }
                editor.apply()
            }
        }
    }
}
