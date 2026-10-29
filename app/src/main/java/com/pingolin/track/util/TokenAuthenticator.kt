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

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
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
 * Thrown by AuthRepository.refreshToken when the auth server DEFINITIVELY
 * rejects the refresh token (HTTP 401): the session is looked up by
 * refresh-token hash (the JWT signature is ignored), so a 401 means the
 * session is genuinely gone — revoked, expired, or replay-detected.
 *
 * This is deliberately distinct from a TRANSIENT failure (network drop,
 * timeout, 5xx, proxy 502). TokenAuthenticator force-logs-out ONLY on this
 * exception; a transient failure must never wipe a still-valid local session,
 * because doing so disables every location self-heal path (watchdog,
 * heartbeat, boot, wake_location push) — all of which gate on isLoggedIn().
 */
class SessionExpiredException(message: String) : Exception(message)

/**
 * OkHttp Authenticator that automatically refreshes expired tokens.
 *
 * When a 401 response is received:
 * 1. Attempts to refresh the token using the refresh token
 * 2. If successful, retries the original request with the new token
 * 3. If refresh fails, returns null (triggers logout flow)
 *
 * Uses Provider<AuthRepository> to break circular dependency:
 * AuthRepository -> AuthApi -> Retrofit -> OkHttp -> TokenAuthenticator -> AuthRepository
 *
 * Thread Safety:
 * - Uses CountDownLatch to synchronize concurrent 401 handlers
 * - Only one thread performs the refresh, others wait for completion
 * - Prevents OkHttp thread pool exhaustion under concurrent load
 * - Uses a dedicated single-thread executor for coroutine execution to avoid
 *   blocking OkHttp's dispatcher threads with runBlocking
 *
 * Circuit Breaker:
 * - Tracks consecutive failures to prevent hammering the auth server
 * - After 3 consecutive failures, circuit opens for 60 seconds
 * - During open state, refresh attempts fail immediately (no network call)
 * - After recovery timeout, allows one test request (half-open state)
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
    private val tokenRefresher: TokenRefresher,
) : Authenticator {

    // Dedicated executor for token refresh to avoid blocking OkHttp threads
    // Single-threaded to ensure sequential refresh attempts
    private val refreshExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TokenRefresh").apply { isDaemon = true }
    }

    // Thread-safe refresh coordination
    // Uses lock object + latch pattern to avoid OkHttp thread pool exhaustion
    private val refreshLock = Any()
    @Volatile private var refreshLatch: CountDownLatch? = null
    @Volatile private var refreshResult: Boolean = false

    // Circuit breaker state
    private val failureCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    @Volatile private var circuitState = CircuitState.CLOSED

    companion object {
        // Circuit breaker configuration
        private const val FAILURE_THRESHOLD = 3  // Open circuit after 3 failures
        private const val RECOVERY_TIMEOUT_MS = 60_000L  // 60 seconds before trying again
        private const val TAG = "TokenAuthenticator"
    }

    private enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

    /**
     * Check if circuit breaker allows a refresh attempt.
     * Returns true if we should attempt refresh, false if circuit is open.
     */
    private fun isCircuitAllowed(): Boolean {
        return when (circuitState) {
            CircuitState.CLOSED -> true
            CircuitState.OPEN -> {
                val timeSinceFailure = System.currentTimeMillis() - lastFailureTime.get()
                if (timeSinceFailure >= RECOVERY_TIMEOUT_MS) {
                    // Recovery timeout elapsed, transition to half-open
                    circuitState = CircuitState.HALF_OPEN
                    android.util.Log.i(TAG, "Circuit breaker transitioning to HALF_OPEN after ${timeSinceFailure}ms")
                    true
                } else {
                    android.util.Log.d(TAG, "Circuit breaker OPEN, ${(RECOVERY_TIMEOUT_MS - timeSinceFailure) / 1000}s until retry")
                    false
                }
            }
            CircuitState.HALF_OPEN -> true  // Allow one test request
        }
    }

    /**
     * Record a successful refresh - closes the circuit.
     */
    private fun recordSuccess() {
        failureCount.set(0)
        if (circuitState != CircuitState.CLOSED) {
            android.util.Log.i(TAG, "Circuit breaker CLOSED after successful refresh")
            circuitState = CircuitState.CLOSED
        }
    }

    /**
     * Record a failed refresh - may open the circuit.
     */
    private fun recordFailure() {
        val failures = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())

        if (circuitState == CircuitState.HALF_OPEN) {
            // Test request failed, reopen circuit
            circuitState = CircuitState.OPEN
            android.util.Log.w(TAG, "Circuit breaker OPEN after half-open test failed")
        } else if (failures >= FAILURE_THRESHOLD) {
            circuitState = CircuitState.OPEN
            android.util.Log.w(TAG, "Circuit breaker OPEN after $failures consecutive failures")
        }
    }

    /** The 401's body carries the auth server's `error_code`; read with a peek, so the caller
     * still gets it.
     */
    private fun hasErrorCode(response: Response): Boolean = runCatching {
        val body = response.peekBody(8 * 1024).string()
        com.google.gson.JsonParser.parseString(body).asJsonObject.get("error_code")
            ?.takeIf { it.isJsonPrimitive }?.asString?.isNotBlank() == true
    }.getOrDefault(false)

    override fun authenticate(route: Route?, response: Response): Request? {
        // Don't retry if we've already tried with a fresh token
        if (responseCount(response) >= 2) {
            return null
        }

        // Don't retry auth endpoints (would cause infinite loop)
        val path = response.request.url.encodedPath
        // Nor a sign-out: it names its session with the refresh token, and refreshing around it
        // would mint a new session only to end it.
        if (path.contains("/auth/login") ||
            path.contains("/auth/logout") ||
            path.contains("/auth/register") ||
            path.contains("/auth/refresh") ||
            path.contains("/auth/srp/")) {
            return null
        }

        // A re-auth refusal about the PASSWORD step, not the session: the auth server answers it
        // with an `error_code` (invalid_credentials, reauth_session_expired). Refreshing and
        // resending would spend the handshake and turn a wrong password into an expired one. A 401
        // there without a code is the session's, and is refreshed like any other.
        if (path.contains("/auth/reauth/") && hasErrorCode(response)) {
            return null
        }

        // Session-less anonymous requests (e.g. geocode token redemption via
        // POST /geocode/reverse) are sent with NO Authorization on purpose. A
        // 401 there is not a token expiry we can fix by refreshing — and
        // retrying with a Bearer would leak identity onto a deliberately
        // anonymous request. Nothing to refresh onto, so bail.
        if (response.request.header("Authorization") == null) {
            return null
        }

        // We need *some* credential to send to /auth/refresh — either the
        // opaque refresh token (preferred, Proton-style flow) or the
        // (possibly expired) access JWT (legacy fallback). If we have
        // neither, there's nothing to refresh.
        val hasAccessToken = !sessionManager.getAccessToken().isNullOrEmpty()
        val hasRefreshToken = !sessionManager.getRefreshToken().isNullOrEmpty()
        if (!hasAccessToken && !hasRefreshToken) return null

        // Coordination lives in TokenRefresher, shared with the gRPC
        // stream's refresh. Two independent refresh implementations
        // could refresh at overlapping moments, and because the server
        // rotates the refresh token on every call, the loser would be
        // left holding a retired token — the next refresh would come
        // back 401 and the client force-sign-out against a session the
        // server still considers live.
        //
        // What this Authenticator keeps is the OkHttp-specific part:
        // deciding which Request to hand back.
        val requestToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.removePrefix("bearer ")

        return when (val outcome = tokenRefresher.refreshBlocking(requestToken)) {
            is TokenRefresher.Outcome.Refreshed,
            is TokenRefresher.Outcome.AlreadyFresh -> {
                // Retry the request that 401'd, with whatever is stored now.
                // Guarded on the token having actually moved so we never retry
                // with the same dead credential we just failed on.
                val newToken = sessionManager.getAccessToken()
                if (newToken != null && newToken != requestToken) {
                    response.request.newBuilder()
                        .header("Authorization", "Bearer $newToken")
                        .build()
                } else {
                    null
                }
            }

            is TokenRefresher.Outcome.NoTokenAfterRefresh -> {
                android.util.Log.w(TAG, "No token after refresh, forcing logout")
                sessionManager.triggerForceLogout(
                    TrackingStopReason.SESSION_NO_TOKEN,
                    "TokenAuthenticator: refresh reported success but stored no token",
                )
                null
            }

            is TokenRefresher.Outcome.SessionRejected -> {
                // Detail carries what the server actually
                // said. A sign-out like this can happen while
                // the server's session row is STILL LIVE, so
                // "which 401, from where" is the fact that
                // separates a real revocation from a false
                // one. triggerForceLogout is guarded to run
                // once per session however many callers
                // arrive at this line together.
                android.util.Log.w(TAG, "Token refresh rejected by server; forcing logout")
                sessionManager.triggerForceLogout(
                    TrackingStopReason.SESSION_REJECTED,
                    "TokenAuthenticator: refresh rejected (401)",
                )
                null
            }

            is TokenRefresher.Outcome.Transient -> {
                // Keep the session. The next request,
                // heartbeat or watchdog tick retries; wiping
                // tokens on a timeout or a 5xx would leave a
                // device dark against a valid session.
                android.util.Log.w(TAG, "Token refresh failed transiently; keeping session")
                null
            }

            is TokenRefresher.Outcome.CircuitOpen -> {
                android.util.Log.w(TAG, "Circuit breaker OPEN - skipping token refresh")
                null
            }

            is TokenRefresher.Outcome.NoCredentials -> null
        }
    }

    /**
     * Count how many times we've tried to authenticate this request.
     */
    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
