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

import com.pingolin.track.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The one place a token refresh happens.
 *
 * WHY THIS EXISTS. Two callers need refreshes: `TokenAuthenticator` for OkHttp
 * traffic, and the gRPC location stream, which needs its own because the stream
 * is not an OkHttp call so the Authenticator never fires for it. Both come
 * through here: single-flight, a circuit breaker, and a double-check for the
 * token another thread has already fetched.
 *
 * Refreshing twice is not harmless. The server rotates the refresh token on
 * every refresh; a presented refresh token stays good until the one it returned
 * is used, and near-simultaneous refreshes are both served. But two code paths
 * that each keep their own copy of the token still retire each other's, and the
 * first to sit idle past 900 s revokes the session. The client then
 * force-signs-out against a session the server may still consider live, and
 * because every self-heal path is gated on being signed in, the device stays
 * dark.
 *
 * One lock, one in-flight refresh, one place that persists the result.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val sessionManager: SessionManager,
    private val authRepositoryProvider: Provider<AuthRepository>,
) {

    sealed interface Outcome {
        /** This caller performed the refresh; a new token is stored. */
        object Refreshed : Outcome

        /** Someone else refreshed; the stored token is already newer. */
        object AlreadyFresh : Outcome

        /** Network, timeout, 5xx. The session is intact — retry later. */
        object Transient : Outcome

        /** The server rejected the refresh with 401. Terminal. */
        object SessionRejected : Outcome

        /**
         * The refresh reported success but stored no token. Also terminal, and
         * kept distinct from [SessionRejected] because the two have different
         * causes and the journal entry naming which one it was is the whole
         * point of recording it.
         */
        object NoTokenAfterRefresh : Outcome

        /** Too many recent failures; not attempting. */
        object CircuitOpen : Outcome

        /** Nothing to refresh with. */
        object NoCredentials : Outcome
    }

    /** True when the caller may proceed with a newer token than it had. */
    fun Outcome.hasFreshToken(): Boolean =
        this is Outcome.Refreshed || this is Outcome.AlreadyFresh

    // Single-threaded on purpose: refreshes are sequential, and this keeps
    // the work off OkHttp's dispatcher threads.
    private val refreshExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokenRefresh").apply { isDaemon = true }
    }

    private val refreshLock = Any()
    @Volatile private var refreshLatch: CountDownLatch? = null
    @Volatile private var lastOutcome: Outcome = Outcome.Transient

    /**
     * Told the new access token after every refresh that stored one. The gRPC
     * location stream is the listener: it presents the token it was built with on
     * every RPC, so a refresh it is not told about leaves it sending an expired
     * token until the server refuses one.
     */
    private val tokenListeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addTokenListener(listener: (String) -> Unit) {
        tokenListeners.add(listener)
    }

    /** A listener's failure is its own business; it must never fail the refresh. */
    private fun notifyTokenListeners() {
        val token = sessionManager.getAccessToken() ?: return
        for (listener in tokenListeners) {
            try {
                listener(token)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Token listener failed: ${e.message}")
            }
        }
    }

    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    @Volatile private var circuitState = CircuitState.CLOSED

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    /**
     * Coordinated refresh for callers that can block (OkHttp's Authenticator
     * runs on a background thread and is expected to block).
     *
     * @param tokenSeenByCaller the access token the caller's failed request
     *        actually used. Used for the double-check: if the stored token has
     *        moved on since, someone else already refreshed and the caller
     *        should simply retry rather than refresh again — which is precisely
     *        the redundant refresh that retires a still-needed token.
     */
    fun refreshBlocking(tokenSeenByCaller: String?): Outcome {
        val stored = sessionManager.getAccessToken()
        val refreshToken = sessionManager.getRefreshToken()
        if (stored.isNullOrEmpty() && refreshToken.isNullOrEmpty()) return Outcome.NoCredentials

        var latchToWaitOn: CountDownLatch? = null
        var weRefresh = false

        synchronized(refreshLock) {
            val current = sessionManager.getAccessToken()
            if (current != null && tokenSeenByCaller != null && current != tokenSeenByCaller) {
                return Outcome.AlreadyFresh
            }
            val existing = refreshLatch
            if (existing != null) {
                latchToWaitOn = existing
            } else {
                if (!isCircuitAllowed()) return Outcome.CircuitOpen
                refreshLatch = CountDownLatch(1)
                weRefresh = true
            }
        }

        if (!weRefresh) {
            val latch = latchToWaitOn ?: return Outcome.Transient
            val completed = try {
                latch.await(30, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.Transient
            }
            if (!completed) return Outcome.Transient

            // Judge by the token, not only by the leader's verdict: a parallel
            // sign-in may have stored a brand-new token while the refresh was
            // failing on the stale one.
            val after = sessionManager.getAccessToken()
            return if (after != null && after != tokenSeenByCaller) Outcome.AlreadyFresh
            else lastOutcome
        }

        return try {
            val future = CompletableFuture<Result<Unit>>()
            refreshExecutor.execute {
                try {
                    future.complete(runBlocking { authRepositoryProvider.get().refreshToken() })
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            val result = try {
                future.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Token refresh failed with exception", e)
                Result.failure(e)
            }
            classify(result).also { lastOutcome = it }
        } finally {
            synchronized(refreshLock) {
                refreshLatch?.countDown()
                refreshLatch = null
            }
        }
    }

    /**
     * Coordinated refresh for coroutine callers — the gRPC stream. Shares the
     * lock, the latch and the circuit breaker with [refreshBlocking], which is
     * the entire point: the two must never refresh simultaneously.
     */
    suspend fun refresh(tokenSeenByCaller: String? = sessionManager.getAccessToken()): Outcome =
        withContext(Dispatchers.IO) { refreshBlocking(tokenSeenByCaller) }

    private fun classify(result: Result<Unit>): Outcome {
        if (result.isSuccess) {
            recordSuccess()
            if (sessionManager.getAccessToken() == null) return Outcome.NoTokenAfterRefresh
            notifyTokenListeners()
            return Outcome.Refreshed
        }
        recordFailure()
        // Only a 401 is a definitive rejection. Everything else — 5xx, 429, a
        // proxy hiccup, a timeout — is transient and must NOT cost a session
        // the server still considers valid.
        return if (result.exceptionOrNull() is SessionExpiredException) Outcome.SessionRejected
        else Outcome.Transient
    }

    private fun isCircuitAllowed(): Boolean = when (circuitState) {
        CircuitState.CLOSED -> true
        CircuitState.OPEN -> {
            if (System.currentTimeMillis() - lastFailureTime.get() >= RECOVERY_TIMEOUT_MS) {
                circuitState = CircuitState.HALF_OPEN
                android.util.Log.i(TAG, "Circuit breaker HALF_OPEN")
                true
            } else {
                false
            }
        }
        CircuitState.HALF_OPEN -> true
    }

    private fun recordSuccess() {
        failureCount.set(0)
        circuitState = CircuitState.CLOSED
    }

    private fun recordFailure() {
        lastFailureTime.set(System.currentTimeMillis())
        if (failureCount.incrementAndGet() >= FAILURE_THRESHOLD) {
            circuitState = CircuitState.OPEN
            android.util.Log.w(TAG, "Circuit breaker OPEN after ${failureCount.get()} failures")
        }
    }

    /** Test seam: reset breaker state between cases. */
    internal fun resetForTest() {
        failureCount.set(0)
        lastFailureTime.set(0)
        circuitState = CircuitState.CLOSED
        synchronized(refreshLock) { refreshLatch = null }
        lastOutcome = Outcome.Transient
    }

    companion object {
        private const val TAG = "TokenRefresher"

        private const val FAILURE_THRESHOLD = 3
        private const val RECOVERY_TIMEOUT_MS = 60_000L
    }
}
