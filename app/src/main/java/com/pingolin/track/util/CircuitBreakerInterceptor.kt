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

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Circuit breaker interceptor to protect against cascading failures.
 *
 * Implements the circuit breaker pattern for API endpoints:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Too many failures, requests fail fast without hitting the server
 * - HALF_OPEN: Testing if service has recovered
 *
 * Configuration:
 * - Failure threshold: 5 consecutive failures to open circuit
 * - Recovery timeout: 30 seconds before attempting recovery
 * - Protected paths: /api/v1/circles/, /api/v1/locations, /api/v1/places
 *
 * Benefits:
 * - Prevents resource exhaustion during outages
 * - Faster failure detection for users
 * - Reduces load on struggling servers
 * - Allows automatic recovery when service is back
 */
@Singleton
class CircuitBreakerInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "CircuitBreaker"

        /** Number of consecutive failures before opening the circuit */
        private const val FAILURE_THRESHOLD = 5

        /** Time in milliseconds before attempting recovery from open state */
        private const val RECOVERY_TIMEOUT_MS = 30_000L

        /** Paths that should be protected by the circuit breaker */
        private val PROTECTED_PATHS = listOf(
            "/api/v1/circles/",
            "/api/v1/locations",
            "/api/v1/places"
        )
    }

    private enum class State {
        CLOSED,    // Normal operation
        OPEN,      // Failing fast
        HALF_OPEN  // Testing recovery
    }

    /**
     * Circuit state for a specific path pattern.
     * Not a data class: copy() would reset the atomic counters to fresh defaults,
     * discarding accumulated failure/timing state.
     */
    private class CircuitState(
        @Volatile var state: State = State.CLOSED,
        val failureCount: AtomicInteger = AtomicInteger(0),
        val lastFailureTime: AtomicLong = AtomicLong(0),
        val openedAt: AtomicLong = AtomicLong(0)
    )

    private val circuits = ConcurrentHashMap<String, CircuitState>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath

        // Check if this path should be protected
        val circuitKey = getCircuitKey(path) ?: return chain.proceed(request)

        val circuit = circuits.getOrPut(circuitKey) { CircuitState() }

        return when (getCurrentState(circuit)) {
            State.OPEN -> {
                Log.w(TAG, "Circuit OPEN for $circuitKey, failing fast")
                throw CircuitBreakerOpenException(
                    "Circuit breaker is open for $circuitKey. Service appears to be unavailable."
                )
            }
            State.HALF_OPEN -> {
                Log.d(TAG, "Circuit HALF_OPEN for $circuitKey, attempting recovery")
                executeWithTracking(chain, circuit, circuitKey)
            }
            State.CLOSED -> {
                executeWithTracking(chain, circuit, circuitKey)
            }
        }
    }

    /**
     * Execute the request and track success/failure for circuit state.
     */
    private fun executeWithTracking(
        chain: Interceptor.Chain,
        circuit: CircuitState,
        circuitKey: String
    ): Response {
        return try {
            val response = chain.proceed(chain.request())

            if (response.isSuccessful || response.code in 400..499) {
                // Success or client error (not server fault) - reset failure count
                onSuccess(circuit, circuitKey)
            } else if (response.code >= 500) {
                // Server error - count as failure
                onFailure(circuit, circuitKey)
            }

            response
        } catch (e: IOException) {
            // A CANCELLED call is not a server failure — it is this
            // app's own doing, and counting it opens the breaker
            // against a healthy server.
            //
            // OkHttp reports cancellation as IOException("Canceled"),
            // which is indistinguishable from a network error at this
            // catch. Leaving the Map tab cancels the in-flight
            // circle/location calls (about 2 per return, against a
            // threshold of 5), so three tab switches in a row could
            // open the circuit and then refuse real requests for the
            // 30 s recovery timeout, on a server that never failed.
            //
            // `chain.call().isCanceled()` is the reliable test;
            // matching on the exception message would break the moment
            // OkHttp reworded it.
            if (chain.call().isCanceled()) {
                Log.d(TAG, "Call cancelled for $circuitKey — not counting as a failure")
                throw e
            }
            // Network error - count as failure
            onFailure(circuit, circuitKey)
            throw e
        }
    }

    /**
     * Get the circuit key for a path, or null if not protected.
     */
    private fun getCircuitKey(path: String): String? {
        return PROTECTED_PATHS.find { path.contains(it) }
    }

    /**
     * Get the current state of a circuit, handling state transitions.
     */
    private fun getCurrentState(circuit: CircuitState): State {
        return when (circuit.state) {
            State.OPEN -> {
                // Check if recovery timeout has passed
                val timeSinceOpen = System.currentTimeMillis() - circuit.openedAt.get()
                if (timeSinceOpen >= RECOVERY_TIMEOUT_MS) {
                    // Transition to half-open to test recovery
                    State.HALF_OPEN
                } else {
                    State.OPEN
                }
            }
            else -> circuit.state
        }
    }

    /**
     * Handle successful request - reset failure count and close circuit.
     */
    private fun onSuccess(circuit: CircuitState, circuitKey: String) {
        if (circuit.failureCount.get() > 0) {
            Log.d(TAG, "Circuit $circuitKey: success, resetting failure count")
        }
        circuit.failureCount.set(0)
        // Update state to closed (in case we were half-open)
        circuit.state = State.CLOSED
    }

    /**
     * Handle failed request - increment failure count and potentially open circuit.
     */
    private fun onFailure(circuit: CircuitState, circuitKey: String) {
        val failures = circuit.failureCount.incrementAndGet()
        circuit.lastFailureTime.set(System.currentTimeMillis())

        Log.w(TAG, "Circuit $circuitKey: failure #$failures")

        if (failures >= FAILURE_THRESHOLD) {
            Log.w(TAG, "Circuit $circuitKey: opening circuit after $failures failures")
            circuit.openedAt.set(System.currentTimeMillis())
            circuit.state = State.OPEN
        }
    }

    /**
     * Get circuit breaker status for monitoring/debugging.
     */
    fun getStatus(): Map<String, CircuitStatus> {
        return circuits.mapValues { (_, circuit) ->
            CircuitStatus(
                state = getCurrentState(circuit).name,
                failureCount = circuit.failureCount.get(),
                lastFailureTime = circuit.lastFailureTime.get(),
                openedAt = circuit.openedAt.get()
            )
        }
    }

    /**
     * Reset all circuits - useful for testing or manual recovery.
     */
    fun resetAll() {
        circuits.clear()
        Log.i(TAG, "All circuits reset")
    }

    /**
     * Reset a specific circuit.
     */
    fun reset(circuitKey: String) {
        circuits.remove(circuitKey)
        Log.i(TAG, "Circuit $circuitKey reset")
    }
}

/**
 * Status of a circuit for monitoring.
 */
data class CircuitStatus(
    val state: String,
    val failureCount: Int,
    val lastFailureTime: Long,
    val openedAt: Long
)

/**
 * Exception thrown when circuit breaker is open.
 */
class CircuitBreakerOpenException(message: String) : IOException(message)
