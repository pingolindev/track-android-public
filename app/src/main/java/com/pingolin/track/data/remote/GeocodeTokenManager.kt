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

package com.pingolin.track.data.remote

import com.pingolin.track.util.SecurePrefs
import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.GeocodeIssueRequest
import com.pingolin.track.data.remote.dto.GeocodeRedeemRequest
import com.pingolin.track.data.remote.dto.GeocodeRedeemToken
import com.pingolin.track.data.remote.dto.GeocodeSearchRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import retrofit2.Response
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.geocodeBlindTokens
import uniffi.pingolin_mobile.geocodeFinalizeTokens
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the privacy-preserving geocode-token flow (Privacy-Pass-style
 * VOPRF). Splits "who you are" from "where you are":
 *
 *  1. **Mint** (authenticated, premium-gated): blind a batch of random nonces
 *     in the SDK, `POST /geocode/tokens`, then finalize — which VERIFIES the
 *     issuance DLEQ proof against the pinned public key — into single-use
 *     anonymous credits, stored encrypted.
 *  2. **Spend** (session-less): pop one credit and `POST /geocode/reverse` with
 *     NO identity (the request is sent with no Authorization; see the
 *     `X-Pingolin-Anon` handling in [com.pingolin.track.di.AppModule]).
 *
 * [redeemReverse] returns the successful session-less response, or `null` when
 * the private path is unavailable (not premium, VOPRF unconfigured, no
 * credits, stale key epoch). The caller ([ServerGeocoder]) then falls back to
 * the authenticated proxy — so this is a strict privacy *upgrade* that
 * degrades gracefully when the server has the token flow unconfigured.
 *
 * KEY PINNING: we pin (`key_id`, `public_key`) from the public
 * `GET /geocode/token-key` (the same value every user sees) and require each
 * issuance to match it. A server trying to tag this user with a *per-user* key
 * to deanonymize them at redemption would diverge from the published key and be
 * refused. (Full assurance against a server that lies consistently at both
 * endpoints needs key transparency — out of scope for the primitive.)
 *
 * Concurrency: a single [Mutex] serializes the whole mint/spend cycle. Geocode
 * redemption is not a high-QPS path, and the lock guarantees no two callers pop
 * the same credit or double-mint.
 */
@Singleton
class GeocodeTokenManager @Inject constructor(
    @ApplicationContext context: Context,
    masterKey: dagger.Lazy<MasterKey>,
    private val trackApi: TrackApi,
    private val gson: Gson,
) {
    companion object {
        private const val TAG = "GeocodeTokenMgr"
        /** Dedicated encrypted store — bearer credits kept away from SDK keys. */
        private const val PREFS_FILE = "geocode_token_store"
        private const val KEY_ID = "key_id"
        private const val PUBLIC_KEY = "public_key"
        private const val POOL = "pool"
        /** Credits minted per refill (backend caps a batch at 128). */
        private const val REFILL_BATCH = 40
        /** Refill when the pool drops to/below this. */
        private const val LOW_WATERMARK = 8
        /** Cap redeem retries when consecutive credits come back spent (409). */
        private const val MAX_REDEEM_RETRIES = 4

        /**
         * Ceiling on one spend, mutex wait included. Generous enough to cover a
         * first-use mint on a slow connection, short enough that a user typing
         * in the search box never waits on a stalled request — they get the
         * on-device answer instead.
         */
        private const val SPEND_TIMEOUT_MS = 10_000L

        /**
         * How long a 402 ("not entitled") is believed before asking again.
         *
         * Entitlement cannot change between keystrokes, so a 402 must be cached:
         * without this, typing "walmart" on a free account fires seven issuance
         * requests, each a full round trip, every one a 402, and each one holds
         * that keystroke's results behind a network call that was never going to
         * succeed.
         *
         * Ten minutes is short enough that someone who subscribes mid-session is
         * not stuck waiting long, and [prewarm] clears it outright on a fresh
         * launch so an upgrade is never stuck behind it at all.
         */
        private const val NOT_ENTITLED_COOLDOWN_MS = 10 * 60 * 1000L
    }

    /**
     * `elapsedRealtime()` deadline before which minting is known to be pointless.
     * Volatile rather than mutex-guarded on purpose: it is read on the fast path
     * to AVOID taking the lock, and a stale read costs at most one wasted mint.
     */
    @Volatile
    private var notEntitledUntilMs = 0L

    /** True when a recent 402 says this account cannot mint credits right now. */
    private fun knownNotEntitled(): Boolean =
        android.os.SystemClock.elapsedRealtime() < notEntitledUntilMs

    /** A stored credit: `(nonce_b64, output_b64=W)`. Short field names keep the
     * (already-encrypted) blob compact and semantics-free. */
    private data class StoredToken(val n: String, val w: String)

    // Opened at its first use, never while Hilt injects.
    private val prefs by lazy { SecurePrefs.open(context, PREFS_FILE, masterKey.get()) }

    private val mutex = Mutex()

    /**
     * Reverse-geocode privately by spending one anonymous credit. Returns the
     * successful session-less [Response] (raw Pelias GeoJSON), or `null` if the
     * private path is unavailable — the caller must then fall back.
     */
    suspend fun redeemReverse(
        lat: Double,
        lng: Double,
        size: Int,
        layers: String? = null,
    ): Response<ResponseBody>? =
        spendCredit { tok, keyId ->
            trackApi.geocodeRedeemReverse(
                GeocodeRedeemRequest(
                    token = tok,
                    keyId = keyId,
                    lat = lat,
                    lng = lng,
                    size = size,
                    layers = layers,
                )
            )
        }

    /**
     * Forward-search privately by spending one anonymous credit. Returns the
     * successful session-less [Response] (raw Pelias GeoJSON), or `null` if the
     * private path is unavailable — the caller must then fall back to the
     * on-device tile search.
     *
     * The query text leaves the device on this call. It carries no identity
     * (see [TrackApi.geocodeRedeemSearch]) and is never logged here.
     */
    suspend fun redeemSearch(
        text: String,
        size: Int,
        focusLat: Double?,
        focusLng: Double?,
        language: String? = null,
    ): Response<ResponseBody>? =
        spendCredit { tok, keyId ->
            trackApi.geocodeRedeemSearch(
                language,
                GeocodeSearchRequest(
                    token = tok,
                    keyId = keyId,
                    text = text,
                    size = size,
                    focusLat = focusLat,
                    focusLng = focusLng,
                )
            )
        }

    /**
     * Stock the credit pool ahead of the first search.
     *
     * Minting is the expensive half of this flow — a round trip plus 40 VOPRF
     * blinds — and minting lazily would put that cost on whichever search
     * happens to be first, competing with someone actively typing. Run at
     * launch from an application-scoped coroutine, where nothing cancels it
     * and nobody is waiting.
     *
     * Silent and free when the pool is already stocked: no network, no log.
     * Bounded like a spend, because it takes the SAME lock — a prewarm that
     * hung would be holding the door shut on every search behind it.
     *
     * Callers gate this on the toggle. Minting is premium-gated, so running it
     * unconditionally would be a guaranteed 402 on every launch for everyone
     * who has not turned the feature on.
     */
    suspend fun prewarm() {
        // A launch is the natural moment to re-test entitlement: someone who
        // just subscribed must not sit behind a cooldown started before they
        // paid. Cheap, since prewarm already runs at most once per foreground.
        notEntitledUntilMs = 0L
        val done = withTimeoutOrNull(SPEND_TIMEOUT_MS) {
            mutex.withLock {
                if (prefs.getString(KEY_ID, null) == null && !fetchAndPinKeyLocked()) {
                    Log.w(TAG, "prewarm: no pinned key")
                    return@withLock
                }
                if (loadPool().size > LOW_WATERMARK) return@withLock // already stocked
                refillLocked()
            }
        }
        if (done == null) Log.w(TAG, "prewarm: timed out after ${SPEND_TIMEOUT_MS}ms")
    }

    /**
     * Spend exactly one anonymous credit on [call].
     *
     * Holds the whole credit state machine — key pinning, top-up, the
     * spend/retry loop, and the 401 re-pin — in ONE place so reverse and search
     * cannot drift apart. [call] receives the credit and the pinned key epoch
     * and does nothing but issue its request.
     */
    private suspend fun spendCredit(
        call: suspend (GeocodeRedeemToken, String) -> Response<ResponseBody>,
    ): Response<ResponseBody>? {
        // HARD BOUND on the whole spend, including the wait for the mutex.
        //
        // Everything here is serialized behind one lock, and the HTTP calls
        // inside it carry OkHttp's 30-60 s timeouts — so one slow request would
        // not just delay itself: it would hold the lock and silently starve
        // every later geocode call behind it. A search that hangs is worse than
        // a search that falls back: the on-device tiles are right there.
        //
        // The timeout wraps the lock acquisition too, so a stuck holder bounds
        // the waiters, and cancellation propagates INTO the held section so the
        // lock is actually released rather than leaked.
        val result = withTimeoutOrNull(SPEND_TIMEOUT_MS) { spendCreditLocked(call) }
        if (result == null) {
            Log.w(TAG, "spend: timed out after ${SPEND_TIMEOUT_MS}ms — falling back to tiles")
        }
        return result
    }

    private suspend fun spendCreditLocked(
        call: suspend (GeocodeRedeemToken, String) -> Response<ResponseBody>,
    ): Response<ResponseBody>? =
        mutex.withLock {
            // 1. Pin the current key (once, then cached until rotation/401).
            if (prefs.getString(KEY_ID, null) == null && !fetchAndPinKeyLocked()) {
                // Every bail-out below is logged: a silent fallback is
                // indistinguishable from "the server answered".
                Log.w(TAG, "spend: no pinned key (token flow unconfigured/unreachable)")
                return null
            }

            // 2. Top up if we're low or empty.
            var pool = loadPool()
            if (pool.size <= LOW_WATERMARK) {
                refillLocked() // best-effort; re-read below
                pool = loadPool()
            }
            if (pool.isEmpty()) {
                Log.w(TAG, "spend: credit pool empty after refill (free tier / 503?)")
                return null
            }

            val keyId = prefs.getString(KEY_ID, null) ?: run {
                Log.w(TAG, "spend: key id vanished between pin and spend")
                return null
            }

            // 3. Spend credits until one redeems; drop spent (409) ones.
            var attempts = 0
            var result: Response<ResponseBody>? = null
            while (pool.isNotEmpty() && attempts < MAX_REDEEM_RETRIES) {
                attempts++
                val idx = pool.size - 1
                val tok = pool[idx]
                val resp = try {
                    call(GeocodeRedeemToken(nonceB64 = tok.n, evaluatedB64 = tok.w), keyId)
                } catch (e: Exception) {
                    // Transient (network) — keep the credit for a later retry.
                    Log.w(TAG, "geocode redeem error: ${e.message}")
                    break
                }
                if (resp.isSuccessful) {
                    pool.removeAt(idx) // single-use — consumed
                    result = resp
                    break
                }
                when (resp.code()) {
                    409 -> pool.removeAt(idx) // already spent — drop, try next
                    401 -> {
                        // Stale/rotated key epoch: our pinned key is no longer
                        // current. Wipe and fall back this round; the next call
                        // re-pins + re-mints under the fresh epoch.
                        Log.w(TAG, "spend: 401 — key epoch rotated, re-pinning next call")
                        clearPinAndPoolLocked()
                        return null
                    }
                    else -> {
                        Log.w(TAG, "spend: redeem HTTP ${resp.code()} — falling back")
                        break // 503 / 5xx / other — keep credits, fall back
                    }
                }
            }
            savePool(pool) // persist consumed/spent removals
            if (result == null) Log.w(TAG, "spend: gave up after $attempts attempt(s)")
            result
        }

    /** Fetch + pin (key_id, public_key) from the public token-key endpoint. */
    private suspend fun fetchAndPinKeyLocked(): Boolean {
        val resp = try {
            trackApi.geocodeTokenKey()
        } catch (e: Exception) {
            Log.w(TAG, "token-key fetch failed: ${e.message}")
            return false
        }
        if (!resp.isSuccessful) return false
        val body = resp.body() ?: return false
        val kid = body.keyId ?: return false
        val pk = body.publicKey ?: return false
        prefs.edit().putString(KEY_ID, kid).putString(PUBLIC_KEY, pk).apply()
        return true
    }

    /**
     * Mint a fresh batch of credits. Verifies the issuance key against the
     * pinned/published key (per-user-key defense) and the DLEQ proof (in
     * [geocodeFinalizeTokens]) before storing anything.
     */
    private suspend fun refillLocked(): Boolean {
        // Cheapest possible check first: a recent 402 means minting cannot
        // succeed, so skip the blind + the round trip entirely. Without this,
        // every keystroke on a non-entitled account would pay for a doomed
        // request.
        if (knownNotEntitled()) return false

        val pinnedKeyId = prefs.getString(KEY_ID, null) ?: run {
            Log.w(TAG, "refill: no pinned key id"); return false
        }
        val pinnedPk = prefs.getString(PUBLIC_KEY, null) ?: run {
            Log.w(TAG, "refill: no pinned public key"); return false
        }

        val blind = try {
            geocodeBlindTokens(REFILL_BATCH.toUInt())
        } catch (e: Exception) {
            Log.w(TAG, "blind failed: ${e.message}")
            return false
        }
        val issueResp = try {
            trackApi.geocodeIssueTokens(GeocodeIssueRequest(blind.blinded))
        } catch (e: Exception) {
            Log.w(TAG, "token issue failed: ${e.message}")
            return false
        }
        if (!issueResp.isSuccessful) {
            // The status is the whole diagnosis: 402 = not premium (or a stale
            // entitlement claim), 429 = issuance rate limit, 503 = VOPRF
            // unconfigured. Silently returning false here would make all three
            // look identical to "the server just had no results".
            Log.w(TAG, "refill: issuance HTTP ${issueResp.code()}")
            // 402 is a statement about the ACCOUNT, not about this request, so
            // it is the one status worth remembering: re-asking on the next
            // keystroke can only get the same answer. 429/503 are transient and
            // deliberately excluded — backing off on those would turn a blip
            // into a ten-minute outage of a feature the user is paying for.
            if (issueResp.code() == 402) {
                notEntitledUntilMs =
                    android.os.SystemClock.elapsedRealtime() + NOT_ENTITLED_COOLDOWN_MS
            }
            return false
        }
        val data = issueResp.body()?.data ?: run {
            Log.w(TAG, "refill: issuance body empty")
            return false
        }

        // Per-user-key defense: issuance must use the public, everyone-sees-the-
        // same key we pinned. On divergence, trust ONLY the public endpoint.
        var pkForFinalize = pinnedPk
        if (data.keyId != pinnedKeyId || data.publicKey != pinnedPk) {
            if (!fetchAndPinKeyLocked()) return false
            val freshKeyId = prefs.getString(KEY_ID, null)
            val freshPk = prefs.getString(PUBLIC_KEY, null)
            if (freshKeyId != data.keyId || freshPk != data.publicKey) {
                // Published key still disagrees with what we were handed →
                // refuse (potential per-user key). Drop the whole batch.
                Log.w(TAG, "issuance key disagrees with published key; refusing tokens")
                return false
            }
            // Legitimate rotation: old credits are invalid under the new epoch.
            savePool(mutableListOf())
            pkForFinalize = data.publicKey
        }

        val tokens = try {
            geocodeFinalizeTokens(blind.states, data.evaluated, data.proofs, pkForFinalize)
        } catch (e: MobileException) {
            // DLEQ proof verification failed — evaluation inconsistent with the
            // pinned key. Store nothing.
            Log.w(TAG, "token finalize/DLEQ verify failed: ${e.message}")
            return false
        }

        val pool = loadPool()
        tokens.forEach { pool.add(StoredToken(it.nonceB64, it.outputB64)) }
        savePool(pool)
        return true
    }

    private fun clearPinAndPoolLocked() {
        prefs.edit().remove(KEY_ID).remove(PUBLIC_KEY).remove(POOL).apply()
    }

    /**
     * Wipe all persisted geocode state — the pinned (key_id, public_key) and the
     * whole credit pool — on logout / account switch. The credits are anonymous
     * single-use bearer tokens minted under the user's premium entitlement; if left
     * in "geocode_token_store" they would survive a same-device account switch and
     * let the NEXT user spend the prior user's credits. Called from
     * [com.pingolin.track.domain.repository.AuthRepository.clearLocalData]; serialized
     * with the mint/spend cycle via the same [mutex].
     */
    suspend fun clearAll() = mutex.withLock {
        clearPinAndPoolLocked()
    }

    private fun loadPool(): MutableList<StoredToken> {
        val json = prefs.getString(POOL, null) ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<StoredToken>?>(
                json,
                object : TypeToken<MutableList<StoredToken>>() {}.type,
            ) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun savePool(pool: List<StoredToken>) {
        prefs.edit().putString(POOL, gson.toJson(pool)).apply()
    }
}
