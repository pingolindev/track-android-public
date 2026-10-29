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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.KeyStoreLoad
import uniffi.pingolin_mobile.KeyStoreStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this app does with the state of the SDK's key store, from
 * [CryptoManager.keyStoreStatus]. Track shares locations, so nobody is signed out while the app
 * still works:
 * - the store doesn't decode (doesNotDecode): this device holds no keys, and nothing works. The
 *   session ends ([signOut]) and the sign-in screen says "Sync error. Please sign in again." The
 *   sign-in then clears the store, and its import writes it whole.
 * - a signing seed or entries that didn't read (signingSeedUnreadable, entriesUnreadable): it still
 *   works, partly. The same line, as a banner on the Map and a row in Settings with the existing
 *   Logout ([syncBanner]). The sign-in drops the entries, and its import replaces the seed.
 * - the platform couldn't read it (readFailed): nothing is shown. It's loaded again at the next
 *   refresh ([retryIfReadFailed]), and never cleared.
 * - a wipe a sign-out couldn't finish ([OwedWipe]) is paid before anything is loaded: at launch,
 *   at a retried load, and first at the sign-in. While it stays owed, the sign-in's keys stay in
 *   memory.
 * While the load has failed, the SDK takes nobody on first contact and every circle waits: the
 * app's existing waiting states, and no pin export at a sign-out ([pinsExportable]).
 */
@Singleton
class KeyStoreRecovery @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val owedWipe: OwedWipe,
    @param:ApplicationContext private val context: Context,
) {
    /** What a signed-in session does with the store as a load found it. */
    enum class Verdict {
        CARRY_ON,
        /** The store doesn't decode: the session ends, with the sync error. */
        SIGN_OUT,
        /** A seed or entries that didn't read: the sync error's banner. */
        BANNER,
    }

    /** What the sign-in does before any of its keys goes into the SDK. */
    enum class BeforeImport {
        /** Go on with the import. */
        IMPORT_KEYS,
        /** The platform couldn't read the store, which is never cleared: the sign-in stops. */
        STOP,
    }

    /** Tests pass plain preferences. */
    internal var noticePrefs: () -> SharedPreferences = { context.getSharedPreferences(NOTICE_FILE, Context.MODE_PRIVATE) }

    private val _syncBanner = MutableStateFlow(false)

    /** The sync error's banner is up: a signing seed or entries that didn't read. */
    val syncBanner: StateFlow<Boolean> = _syncBanner.asStateFlow()

    private fun status(): KeyStoreStatus? = runCatching { cryptoManager.keyStoreStatus() }.getOrNull()

    /** For a signed-in session: the verdict now, with the banner raised or lowered to match. */
    fun check(): Verdict {
        val verdict = status()?.let(::verdict) ?: Verdict.CARRY_ON
        _syncBanner.value = verdict == Verdict.BANNER
        return verdict
    }

    /**
     * A store the platform couldn't read ([KeyStoreLoad.READ_FAILED]) is loaded again, never cleared:
     * a wipe still owed is paid first, and while it stays owed nothing is loaded. Then [check].
     */
    fun retryIfReadFailed(): Verdict {
        if (status()?.load == KeyStoreLoad.READ_FAILED && (!owedWipe.isOwed() || owedWipe.clear(cryptoManager))) {
            val loaded = runCatching { cryptoManager.loadPersistedKeys() }
            if (loaded.isSuccess) Log.i(TAG, "Stored keys read again (${status()?.load})")
        }
        return check()
    }

    /**
     * The store doesn't decode: the session ends (its credentials only: what this account keeps on
     * this phone stays for its sign-in), and the sign-in screen shows the sync error until the next
     * sign-in.
     */
    fun signOut(sessionManager: SessionManager) {
        Log.w(TAG, "Stored keys don't decode: signing out, to sign in again")
        runCatching { noticePrefs().edit().putBoolean(KEY_NOTICE, true).commit() }
        _syncBanner.value = false
        sessionManager.triggerForceLogout(TrackingStopReason.KEY_STORE_UNREADABLE, "the stored keys don't decode")
    }

    /** Whether the sign-in screen shows the sync error: the session ended because the store didn't decode. */
    fun signInNoticeDue(): Boolean = runCatching { noticePrefs().getBoolean(KEY_NOTICE, false) }.getOrDefault(false)

    /** Signed in: no notice, and the banner as the store is now. */
    fun signedIn() {
        runCatching { noticePrefs().edit().remove(KEY_NOTICE).commit() }
        check()
    }

    /** Signed out: no banner. */
    fun signedOut() {
        _syncBanner.value = false
    }

    /** Every clear of the keys and their store ([OwedWipe]): false when the platform refused the delete, and the wipe is owed. */
    fun clearKeys(): Boolean = owedWipe.clear(cryptoManager)

    /**
     * An explicit sign-in, before any of its keys goes into the SDK (after an account switch's
     * wipe). A wipe still owed is paid first; while it stays owed nothing is loaded or written (the
     * SDK refuses both) and the sign-in's keys stay in memory. Then the store is read again: one
     * that still doesn't decode is cleared, so the import writes it whole; entries that didn't read
     * go, before the sign-in's key sync; a kept seed is replaced by the import itself. One the
     * platform couldn't read is never cleared: the sign-in stops.
     */
    fun beforeSignInImport(): BeforeImport {
        if (owedWipe.isOwed() && !owedWipe.clear(cryptoManager)) return BeforeImport.IMPORT_KEYS
        runCatching { cryptoManager.loadPersistedKeys() }
        val status = status() ?: return BeforeImport.STOP
        when (status.load) {
            KeyStoreLoad.READ_FAILED -> return BeforeImport.STOP
            KeyStoreLoad.DOES_NOT_DECODE -> {
                Log.w(TAG, "Stored keys still don't decode at sign-in: replaced with the account's keys")
                owedWipe.clear(cryptoManager)
            }
            KeyStoreLoad.NOT_LOADED, KeyStoreLoad.NOTHING_STORED, KeyStoreLoad.LOADED, KeyStoreLoad.DELETE_FAILED -> {
                if (status.entriesUnreadable) {
                    runCatching { cryptoManager.dropUnreadableEntries() }
                        .onSuccess { Log.i(TAG, "$it stored entr(ies) that didn't read dropped at sign-in") }
                        // Kept as they were: the banner stays, and the next sign-in tries again.
                        .onFailure { Log.w(TAG, "The stored entries that didn't read couldn't be dropped") }
                }
            }
        }
        return BeforeImport.IMPORT_KEYS
    }

    companion object {
        /** The same words, verbatim, on the sign-in screen, the Map's banner and the Settings row. */
        const val SYNC_ERROR = "Sync error. Please sign in again."

        private const val TAG = "KeyStoreRecovery"
        private const val NOTICE_FILE = "key_store_recovery"
        private const val KEY_NOTICE = "sign_in_notice"

        /**
         * The launch's load (AppModule): a wipe still owed is paid first, and while it stays owed
         * nothing is loaded (the SDK refuses loads then anyway). What the load found is
         * keyStoreStatus()'s to say. True when it loaded keys.
         */
        fun loadAtLaunch(cryptoManager: CryptoManager, owedWipe: OwedWipe): Boolean {
            if (owedWipe.isOwed() && !owedWipe.clear(cryptoManager)) {
                Log.w(TAG, "A wipe of the stored keys is still owed: nothing loaded")
                return false
            }
            return runCatching { cryptoManager.loadPersistedKeys() }
                .onFailure { Log.e(TAG, "Stored keys not loaded (${runCatching { cryptoManager.keyStoreStatus().load }.getOrNull()})") }
                .getOrDefault(false)
        }

        fun verdict(status: KeyStoreStatus): Verdict = when (status.load) {
            KeyStoreLoad.DOES_NOT_DECODE -> Verdict.SIGN_OUT
            KeyStoreLoad.LOADED, KeyStoreLoad.NOTHING_STORED ->
                if (status.signingSeedUnreadable || status.entriesUnreadable) Verdict.BANNER else Verdict.CARRY_ON
            // Not read yet: loaded again at the next refresh. A wipe still owed: paid at the next sign-in.
            KeyStoreLoad.NOT_LOADED, KeyStoreLoad.READ_FAILED, KeyStoreLoad.DELETE_FAILED -> Verdict.CARRY_ON
        }

        /**
         * Whether the member pins may be exported at a sign-out: only from a store that loaded.
         * Otherwise the pins held are not this account's whole set, and the export would replace
         * the ones kept at its last sign-out.
         */
        fun pinsExportable(status: KeyStoreStatus?): Boolean =
            status?.load == KeyStoreLoad.LOADED || status?.load == KeyStoreLoad.NOTHING_STORED
    }
}
