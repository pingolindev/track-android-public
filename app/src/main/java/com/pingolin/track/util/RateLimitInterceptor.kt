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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * OkHttp interceptor that handles HTTP 429 (Too Many Requests) responses.
 *
 * When the server returns 429, this interceptor:
 * 1. Parses the Retry-After header (if present)
 * 2. Falls back to exponential backoff with jitter if no header
 * 3. Waits and retries the request up to MAX_RETRIES times
 *
 * This prevents the app from hammering the server during rate limiting
 * and provides a better user experience than immediate failure.
 */
@Singleton
class RateLimitInterceptor @Inject constructor() : Interceptor {

    companion object {
        private const val TAG = "RateLimitInterceptor"

        /** Maximum number of retry attempts for 429 responses */
        private const val MAX_RETRIES = 2

        /** Initial backoff delay in milliseconds */
        private const val INITIAL_BACKOFF_MS = 1000L

        /** Maximum backoff delay in milliseconds (10 seconds) */
        private const val MAX_BACKOFF_MS = 10_000L

        /** Maximum Retry-After we'll honor per retry (10 seconds) */
        private const val MAX_RETRY_AFTER_MS = 10_000L

        /**
         * Maximum total retry time across all attempts (20 seconds), kept short
         * so retries don't block OkHttp dispatcher threads.
         * WorkManager will schedule retry for persistent rate limiting.
         */
        private const val MAX_TOTAL_RETRY_TIME_MS = 20_000L

        /** HTTP status code for rate limiting */
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var retryCount = 0
        var totalWaitTime = 0L

        while (response.code == HTTP_TOO_MANY_REQUESTS && retryCount < MAX_RETRIES) {
            retryCount++
            val waitTime = calculateWaitTime(response, retryCount)

            // Check if this wait would exceed our total retry time budget
            if (totalWaitTime + waitTime > MAX_TOTAL_RETRY_TIME_MS) {
                Log.w(TAG, "Rate limit retry would exceed max total time " +
                        "(${totalWaitTime + waitTime}ms > ${MAX_TOTAL_RETRY_TIME_MS}ms) for ${request.url}")
                break
            }

            Log.w(TAG, "Rate limited (429) on ${request.url}. " +
                    "Retry $retryCount/$MAX_RETRIES after ${waitTime}ms (total: ${totalWaitTime + waitTime}ms)")

            // Close the previous response before retrying
            response.close()

            // Wait before retrying
            // NOTE: Thread.sleep is intentional here. OkHttp interceptors run on OkHttp's
            // dispatcher threads (not the main thread), and the OkHttp contract requires
            // interceptors to block until they return a Response. Using coroutines or
            // other async mechanisms here would break the interceptor chain semantics.
            // See: https://square.github.io/okhttp/features/interceptors/
            try {
                Thread.sleep(waitTime)
                totalWaitTime += waitTime
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Rate limit retry interrupted", e)
            }

            // Retry the request
            response = chain.proceed(request)
        }

        if (response.code == HTTP_TOO_MANY_REQUESTS) {
            Log.e(TAG, "Rate limit retry exhausted for ${request.url} after ${totalWaitTime}ms total wait time")
        }

        return response
    }

    /**
     * Calculate wait time based on Retry-After header or exponential backoff.
     *
     * The Retry-After header can be either:
     * - A number of seconds: "120"
     * - An HTTP date: "Wed, 21 Oct 2015 07:28:00 GMT" (we don't support this)
     */
    private fun calculateWaitTime(response: Response, retryCount: Int): Long {
        // Try to parse Retry-After header
        val retryAfter = response.header("Retry-After")
        if (retryAfter != null) {
            try {
                // Parse as seconds
                val seconds = retryAfter.toLong()
                val waitMs = seconds * 1000
                // Cap the wait time to prevent excessive delays
                return min(waitMs, MAX_RETRY_AFTER_MS)
            } catch (e: NumberFormatException) {
                Log.d(TAG, "Could not parse Retry-After header: $retryAfter")
            }
        }

        // Fall back to exponential backoff with jitter
        return calculateExponentialBackoff(retryCount)
    }

    /**
     * Calculate exponential backoff with jitter.
     *
     * Formula: min(MAX_BACKOFF, INITIAL_BACKOFF * 2^(retryCount-1)) + random jitter
     *
     * The jitter helps prevent thundering herd when multiple clients
     * are rate limited simultaneously.
     */
    private fun calculateExponentialBackoff(retryCount: Int): Long {
        val exponentialDelay = INITIAL_BACKOFF_MS * 2.0.pow(retryCount - 1).toLong()
        val cappedDelay = min(exponentialDelay, MAX_BACKOFF_MS)

        // Add jitter: random 0-25% of the delay
        val jitter = (cappedDelay * Random.nextDouble(0.0, 0.25)).toLong()

        return cappedDelay + jitter
    }
}
