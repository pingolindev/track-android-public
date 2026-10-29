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
import dagger.hilt.android.qualifiers.ApplicationContext
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trust a user builds on this device, kept across their own sign-out and dropped
 * when another account signs in.
 *
 * Wiping it at every sign-out would reopen first-contact trust each time: every member
 * this user had pinned would be trusted afresh on first sight at the next sign-in,
 * whoever the server named, and every safety number compared would have to be compared
 * again. What is kept is public-key and membership metadata, per account and encrypted
 * at rest:
 *  - the SDK's member pins, exported before sign-out wipes them and imported again at
 *    that same account's next sign-in (the SDK refuses another account's export);
 *  - the "verified" marks ([MemberVerificationStore], bound to the exact keys compared);
 *  - who this user gave circle-key access to ([KeyAccessApprovals]).
 */
@Singleton
class RetainedTrust @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
) {
    /** Tests swap in plain preferences: EncryptedSharedPreferences needs the Android Keystore. */
    internal var pinBlobsFor: (Context) -> SharedPreferences = { SecurePrefs.open(it, PIN_BLOB_FILE) }

    private val pinBlobs: SharedPreferences by lazy { pinBlobsFor(context) }

    /** Tests pass a store over plain preferences, for the same reason. */
    internal var approvalsFor: (String) -> KeyAccessApprovals = { userId ->
        SecurePrefsKeyAccessApprovals(context, { userId })
    }

    /** The email each account typed at its last sign-in here (the typed-email guard). Tests pass plain preferences. */
    internal var emails: AccountEmails = AccountEmails(context)

    /**
     * [userId] (canonical) has signed in, typing [typedEmail], and its keys are loaded — call
     * before any circle key is imported or any member pinned. This account's pins from its last
     * sign-out come back. What another account left goes, except what holds trust (its key-access
     * approvals, its kept pins): those go only when the person typed another email than that
     * account's ([AccountEmails.guard]). Returns that guard, for the other stores that hold trust.
     */
    fun onSignIn(userId: String, typedEmail: String?): (String) -> Boolean {
        val guard = emails.guard(typedEmail)
        MemberVerificationStore.keepOnly(context, userId)
        approvalsFor(userId).keepOnly(userId, guard)
        restorePins(userId, guard)
        emails.keepOnly(userId, guard)
        typedEmail?.let { emails.record(userId, it) }
        return guard
    }

    /**
     * [userId] is about to be signed out and its keys wiped: save its member pins for
     * its next sign-in. Nothing to do without a signed-in account.
     */
    fun onSignOut(userId: String?) {
        if (userId.isNullOrEmpty()) return
        // Only from a store that loaded: otherwise the pins held are not this account's whole set,
        // and the export would replace the ones kept at its last sign-out, which stay.
        if (!KeyStoreRecovery.pinsExportable(runCatching { cryptoManager.keyStoreStatus() }.getOrNull())) {
            Log.w(TAG, "Member pins not exported: the stored keys didn't load")
            return
        }
        val blob = try {
            cryptoManager.exportMemberPins(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Member pins not exported: ${e.message}")
            return
        }
        runCatching { pinBlobs.edit().putString(userId, blob).commit() }
            .onFailure { Log.w(TAG, "Member pins not saved: ${it.message}") }
    }

    /** [userId]'s account was deleted: nothing of theirs is kept, and no account's approvals, pins or emails. */
    fun forget(userId: String) {
        MemberVerificationStore.forget(context, userId)
        approvalsFor(userId).wipeAll()
        AccountEntries.wipe(context, { pinBlobs }, PIN_BLOB_FILE)
        emails.wipeAll()
    }

    /**
     * [userId]'s pins kept at its last sign-out come back, kept under any spelling of its id
     * ([AccountIds]); another account's go only where [mayDelete] says so for it.
     */
    private fun restorePins(userId: String, mayDelete: (String) -> Boolean) {
        runCatching {
            val (own, others) = pinBlobs.all.keys.partition { it == userId || AccountIds.same(it, userId) }
            val going = others.filter(mayDelete)
            if (going.isNotEmpty()) {
                val editor = pinBlobs.edit()
                going.forEach { editor.remove(it) }
                editor.apply()
            }
            val kept = own.mapNotNull { spelling -> pinBlobs.getString(spelling, null)?.let { spelling to it } }
            if (kept.isEmpty()) return
            try {
                val restored = MemberPinBlobs.merged(kept.map { it.second }, userId)?.let { cryptoManager.importMemberPins(userId, it) }
                    ?: kept.sumOf { (spelling, blob) -> cryptoManager.importMemberPins(spelling, blob).toInt() }.toUInt()
                Log.i(TAG, "Restored $restored member pin record(s) kept across sign-out")
            } finally {
                // Imported into the SDK's own store now, or unusable: either way the
                // copies go, so an old one can never overwrite pins trusted later.
                val editor = pinBlobs.edit()
                own.forEach { editor.remove(it) }
                editor.apply()
            }
        }.onFailure { Log.w(TAG, "Member pins not restored: ${it.message}") }
    }

    private companion object {
        const val TAG = "RetainedTrust"
        const val PIN_BLOB_FILE = "member_pins_kept"
    }
}
