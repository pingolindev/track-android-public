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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.remote.api.AuthApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.SealedPreviousIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identities from before a password reset.
 *
 * An email password reset replaces the account keypair, so every circle key — each
 * ECDH-wrapped to the OLD public key — stops opening. The device keeps a perfectly valid
 * session and shows circles with no name, no places and no locations, while all of that data
 * sits on the server intact.
 *
 * If the user still had their recovery key they can recover that identity in the account
 * portal, which seals it for us under a value the SDK derives from the key it already holds.
 * All the crypto lives in the SDK (`import_previous_identities_v2`); this only fetches and
 * hands it over. The portal's v2 blob also carries that identity's signing PUBLIC key, so
 * this account's own signatures from before the reset count again.
 *
 * A restore reaches a running app straight away: the server's `identities_check` push, or
 * the app coming to the foreground while a circle is still locked since a reset, [nudge]s a
 * re-check — the same signed-in GET and SDK import as at start-up. The nudge carries nothing
 * and grants nothing, so a hostile one costs a request at most: one re-check per
 * [RECHECK_WINDOW_MS]; one refused while another runs, or inside the window, leaves a single
 * pending flag that runs when the window ends (or at the next foreground) and counts as that
 * window's one. The SDK import replaces the set it holds, so it is always given the UNION of
 * everything this session has seen. A load that outlives its session — a sign-out or account
 * switch while it awaits the network — stores nothing.
 */
@Singleton
class PreviousIdentityLoader @Inject constructor(
    private val authApi: AuthApi,
    private val cryptoManager: CryptoManager
) {
    internal companion object {
        private const val TAG = "PreviousIdentity"

        /** At most one re-check per device in this long. */
        const val RECHECK_WINDOW_MS = 3 * 60_000L
    }

    @Volatile
    private var loaded = false

    /** Guards the fields below, and each store into the SDK. */
    private val lock = Any()

    /** Bumped by [reset]: a load started before it stores nothing. */
    private var generation = 0L

    /** Every sealed identity this session has seen: what the SDK import is given (it replaces its set). */
    private val held = LinkedHashSet<SealedPreviousIdentity>()

    private var running = false
    private var lastRecheckAt: Long? = null
    private var pending = false
    private var pendingRun: Job? = null

    /** A monotonic clock, in millis; tests move it. */
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** Where a pending re-check waits for its window to end; tests pass their own. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called after a re-check opened something new: the circles are loaded again (set by CircleRepository). */
    @Volatile
    internal var onNewlyOpened: (suspend () -> Unit)? = null

    /** Answer of [preResetDataExists] for this session; null until asked successfully. */
    @Volatile
    private var preResetData: Boolean? = null

    /**
     * Load once per session, before circle keys are imported.
     *
     * Never throws and never blocks a login: for almost every account this
     * returns 0, and a failure here must only mean that older data stays
     * unreadable — not that the app stops working.
     */
    suspend fun ensureLoaded(): Int {
        if (loaded) return cryptoManager.previousIdentityCount().toInt()
        val session = synchronized(lock) { generation }
        return try {
            val response = authApi.getPreviousIdentities()
            val sealed = sealedIn(response)
            // Stored only into the session it was asked for.
            val stored = store(session, sealed, markLoaded = true) ?: return 0
            // INFO, not debug: release builds strip Log.d, and this is the only
            // signal that says whether a device can read pre-reset data.
            android.util.Log.i(TAG, "previous identities: ${stored.second} of ${sealed.size} sealed opened")
            stored.second.identities
        } catch (e: Exception) {
            // Leave `loaded` false so a later refresh can try again — a transient
            // network failure should not permanently hide recovered data.
            android.util.Log.i(TAG, "previous identities unavailable: ${e.message}")
            0
        }
    }

    /**
     * Whether an email password reset left data on this account that its old recovery
     * key can restore in the account portal — the one thing that makes "restore it in
     * Settings" the right thing to tell somebody looking at a locked circle.
     *
     * Asked only when a locked circle is being explained, and at most once per session
     * while it succeeds: the endpoint returns wrapped key material and is rate-limited
     * to 20 calls an hour. Null when it could not be asked (offline, rate-limited);
     * callers then say nothing about a reset rather than guess.
     */
    suspend fun preResetDataExists(): Boolean? {
        preResetData?.let { return it }
        return try {
            val response = authApi.getArchivedRecovery()
            val body = response.body()
            if (!response.isSuccessful || body?.success != true || body.data == null) {
                android.util.Log.i(TAG, "archived recovery unavailable: HTTP ${response.code()}")
                null
            } else {
                body.data.archived.isNotEmpty().also { preResetData = it }
            }
        } catch (e: Exception) {
            android.util.Log.i(TAG, "archived recovery unavailable: ${e.message}")
            null
        }
    }

    /**
     * "Check again", from the `identities_check` push or the foreground: the re-check runs now
     * — and this returns once it is done — unless one ran less than [RECHECK_WINDOW_MS] ago or
     * is running; then ONE pending flag is left, run when the window ends.
     */
    suspend fun nudge() {
        val go = synchronized(lock) {
            val last = lastRecheckAt
            val now = clock()
            when {
                running -> { pending = true; false }
                last != null && now - last < RECHECK_WINDOW_MS -> {
                    pending = true
                    schedulePendingLocked(last + RECHECK_WINDOW_MS - now)
                    false
                }
                else -> { startLocked(now); true }
            }
        }
        if (go) runRecheck()
    }

    /**
     * The app came to the foreground: a re-check if [lockedSinceReset] (a circle this account
     * can't open since a reset), or if a pending one is due.
     */
    suspend fun onForeground(lockedSinceReset: Boolean) {
        if (lockedSinceReset || synchronized(lock) { pending }) nudge()
    }

    private fun startLocked(now: Long) {
        running = true
        pending = false
        lastRecheckAt = now
    }

    private fun schedulePendingLocked(delayMs: Long) {
        if (pendingRun?.isActive == true) return
        pendingRun = scope.launch {
            delay(delayMs.coerceAtLeast(0))
            val go = synchronized(lock) {
                if (pending && !running) { startLocked(clock()); true } else false
            }
            if (go) runRecheck()
        }
    }

    private suspend fun runRecheck() {
        try {
            recheck()
        } finally {
            synchronized(lock) {
                running = false
                // A nudge while it ran: one more run, when this
                // window ends.
                val last = lastRecheckAt
                if (pending && last != null) schedulePendingLocked(last + RECHECK_WINDOW_MS - clock())
            }
        }
    }

    private suspend fun recheck() {
        val session = synchronized(lock) { generation }
        val sealed = try {
            val response = authApi.getPreviousIdentities()
            if (!response.isSuccessful) {
                android.util.Log.i(TAG, "re-check: previous identities unavailable: HTTP ${response.code()}")
                return
            }
            sealedIn(response)
        } catch (e: Exception) {
            android.util.Log.i(TAG, "re-check: previous identities unavailable: ${e.message}")
            return
        }
        val (before, after) = store(session, sealed, markLoaded = false) ?: return
        android.util.Log.i(TAG, "re-check: $after previous identities open (was $before)")
        if (after.total > before.total) runCatching { onNewlyOpened?.invoke() }
    }

    /** Per identity, v2 when the answer has it, else v1. */
    private fun sealedIn(response: retrofit2.Response<com.pingolin.track.data.remote.dto.ApiResponse<com.pingolin.track.data.remote.dto.PreviousIdentitiesResponse>>) =
        response.body()?.data?.identities.orEmpty().mapNotNull { row ->
            row.sealedForThisApp?.let { SealedPreviousIdentity(nonce = it.nonce, ciphertext = it.ciphertext) }
        }

    /**
     * What the SDK holds open: identities, and the signing keys their v2 blobs brought. A v1
     * identity that gains its signing key counts as more opened: that is what lets an owner's
     * own circle, signed before the reset, open.
     */
    internal data class Opened(val identities: Int, val signingKeys: Int) {
        val total get() = identities + signingKeys
        override fun toString() = "$identities (with $signingKeys signing keys)"
    }

    private fun openedNow() = Opened(
        cryptoManager.previousIdentityCount().toInt(),
        runCatching { cryptoManager.previousSigningPublicKeys().size }.getOrDefault(0),
    )

    /**
     * Adds [sealed] to what this session holds and gives the SDK the union — only for the
     * session [session] was taken in. (before, after); null when it has ended. The import
     * is v2's, which prefers a v2 blob over a v1 blob of the same identity, so a later
     * v1-only answer never drops a signing key an earlier one brought.
     */
    private fun store(session: Long, sealed: List<SealedPreviousIdentity>, markLoaded: Boolean): Pair<Opened, Opened>? =
        synchronized(lock) {
            if (generation != session) {
                android.util.Log.i(TAG, "previous identities for a session that has ended: dropped")
                return null
            }
            if (markLoaded) loaded = true
            val before = openedNow()
            val grew = held.addAll(sealed)
            if (grew) cryptoManager.importPreviousIdentitiesV2(held.toList())
            before to (if (grew) openedNow() else before)
        }

    /**
     * Forget everything on logout and account switch; the SDK's clear_all_keys drops the
     * keys themselves. Without this, the next account signed in on the same process would
     * skip its own previous identities (and inherit this one's reset answer). A load still
     * waiting on the network stores nothing, and the pending re-check and the held blobs go.
     */
    fun reset() {
        synchronized(lock) {
            generation++
            held.clear()
            pending = false
            pendingRun?.cancel()
            pendingRun = null
            loaded = false
            preResetData = null
        }
    }
}
