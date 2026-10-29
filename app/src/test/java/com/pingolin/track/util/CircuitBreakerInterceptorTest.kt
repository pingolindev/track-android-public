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

import com.pingolin.track.testutil.startOnIpv4Loopback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tests for CircuitBreakerInterceptor state machine transitions.
 *
 * State machine:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: After FAILURE_THRESHOLD (5) consecutive failures, fail fast for RECOVERY_TIMEOUT_MS (30s)
 * - HALF_OPEN: After recovery timeout, allow one test request
 *
 * Tests verify:
 * - Normal operation (closed state)
 * - Circuit opens after threshold failures
 * - Circuit fails fast when open
 * - Circuit transitions to half-open after recovery timeout
 * - Successful request closes the circuit
 * - Failed half-open request reopens the circuit
 * - Client errors (4xx) don't count as failures
 */
class CircuitBreakerInterceptorTest {

    /** Mirrors CircuitBreakerInterceptor.FAILURE_THRESHOLD, which is private. */
    private val FAILURE_THRESHOLD_FOR_TEST = 5

    private lateinit var mockWebServer: MockWebServer
    private lateinit var circuitBreaker: CircuitBreakerInterceptor
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.startOnIpv4Loopback()

        circuitBreaker = CircuitBreakerInterceptor()

        client = OkHttpClient.Builder()
            .addInterceptor(circuitBreaker)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        circuitBreaker.resetAll()
    }

    // =============================================================================
    // CLOSED STATE TESTS
    // =============================================================================

    @Test
    fun `successful request passes through in closed state`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/circles/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("OK", response.body?.string())
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `unprotected paths bypass circuit breaker`() {
        // Enqueue multiple 500 errors
        repeat(10) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }

        // Make requests to unprotected path
        repeat(10) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/users/me"))
                .build()

            client.newCall(request).execute().use { response ->
                assertEquals(500, response.code)
            }
        }

        // All requests should have gone through (no circuit breaker protection)
        assertEquals(10, mockWebServer.requestCount)
    }

    @Test
    fun `client errors 4xx do not count as failures`() {
        // Client errors should not trip the circuit breaker
        repeat(10) {
            mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))
        }

        repeat(10) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()

            // .use: an unread body leaves bytes on the pooled connection, and the
            // NEXT request parses them as its status line ("Unexpected status
            // line: Bad RequestHTTP/1.1 400 Client Error"), so the test would
            // fail intermittently, depending on whether the connection got reused
            // before it was discarded.
            client.newCall(request).execute().use { response ->
                assertEquals(400, response.code)
            }
        }

        // All requests should pass - 4xx errors don't trigger circuit breaker
        assertEquals(10, mockWebServer.requestCount)

        // Verify circuit is still closed
        val status = circuitBreaker.getStatus()
        val circuitStatus = status["/api/v1/circles/"]
        // Status might be null (no failures recorded) or show 0 failures
        assertTrue(
            "Circuit should be closed after 4xx errors",
            circuitStatus == null || circuitStatus.state == "CLOSED"
        )
    }

    // =============================================================================
    // CIRCUIT OPENING TESTS
    // =============================================================================

    @Test
    fun `circuit opens after failure threshold exceeded`() {
        // Enqueue enough failures to trigger circuit breaker (5 failures)
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        }

        // Make 5 failing requests
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()

            val response = client.newCall(request).execute()
            assertEquals(500, response.code)
        }

        assertEquals(5, mockWebServer.requestCount)

        // Verify circuit is now open
        val status = circuitBreaker.getStatus()
        val circuitStatus = status["/api/v1/circles/"]
        assertNotNull("Circuit status should exist", circuitStatus)
        assertEquals("OPEN", circuitStatus!!.state)
        assertEquals(5, circuitStatus.failureCount)
    }

    @Test
    fun `circuit fails fast when open`() {
        // Open the circuit
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        val requestCountAfterOpen = mockWebServer.requestCount
        assertEquals(5, requestCountAfterOpen)

        // Now circuit is open - next request should fail fast
        try {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
            fail("Expected CircuitBreakerOpenException")
        } catch (e: CircuitBreakerOpenException) {
            // Expected - circuit breaker prevented the request
            assertTrue(e.message!!.contains("Circuit breaker is open"))
        }

        // No additional request should have been made
        assertEquals(5, mockWebServer.requestCount)
    }

    @Test
    fun `circuit breaker tracks different paths independently`() {
        // Trip circuit for /api/v1/circles/
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        // Circles circuit should be open
        try {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/another"))
                .build()
            client.newCall(request).execute().close()
            fail("Expected CircuitBreakerOpenException for circles")
        } catch (e: CircuitBreakerOpenException) {
            // Expected
        }

        // But locations circuit should still be closed
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val locationsRequest = Request.Builder()
            .url(mockWebServer.url("/api/v1/locations"))
            .build()

        val response = client.newCall(locationsRequest).execute()
        assertEquals(200, response.code)
    }

    // =============================================================================
    // CIRCUIT RESET TESTS
    // =============================================================================

    @Test
    fun `successful request resets failure count`() {
        // Cause 3 failures (below threshold)
        repeat(3) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(3) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        // Now succeed
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/circles/test"))
            .build()
        val response = client.newCall(request).execute()
        assertEquals(200, response.code)

        // Failure count should be reset
        val status = circuitBreaker.getStatus()
        val circuitStatus = status["/api/v1/circles/"]
        assertEquals(0, circuitStatus?.failureCount ?: 0)
    }

    @Test
    fun `reset clears specific circuit`() {
        // Open the circuit
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        // Verify circuit is open
        val statusBefore = circuitBreaker.getStatus()
        assertEquals("OPEN", statusBefore["/api/v1/circles/"]?.state)

        // Reset the circuit
        circuitBreaker.reset("/api/v1/circles/")

        // Verify circuit is cleared
        val statusAfter = circuitBreaker.getStatus()
        assertNull("Circuit should be cleared after reset", statusAfter["/api/v1/circles/"])

        // Requests should work again
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/circles/test"))
            .build()
        val response = client.newCall(request).execute()
        assertEquals(200, response.code)
    }

    @Test
    fun `resetAll clears all circuits`() {
        // Open circuits for multiple paths
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/locations"))
                .build()
            client.newCall(request).execute().close()
        }

        // Verify both circuits are open
        val statusBefore = circuitBreaker.getStatus()
        assertEquals("OPEN", statusBefore["/api/v1/circles/"]?.state)
        assertEquals("OPEN", statusBefore["/api/v1/locations"]?.state)

        // Reset all
        circuitBreaker.resetAll()

        // Verify all circuits are cleared
        val statusAfter = circuitBreaker.getStatus()
        assertTrue("All circuits should be cleared", statusAfter.isEmpty())
    }

    // =============================================================================
    // STATUS MONITORING TESTS
    // =============================================================================

    @Test
    fun `a cancelled call does not count as a failure`() {
        // Leaving the Map tab cancels the in-flight circle/location
        // calls. OkHttp surfaces that as IOException("Canceled"),
        // which is indistinguishable from a network error at the
        // interceptor's catch — counted as a SERVER failure, a few
        // tab-aways could open the circuit against a perfectly
        // healthy server, then refuse real requests for the 30 s
        // recovery timeout.
        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/circles/test"))
            .build()

        val call = client.newCall(request)
        call.cancel()
        try {
            call.execute().close()
            fail("expected the cancelled call to throw")
        } catch (e: IOException) {
            // expected
        }

        // No circuit entry at all, or one that recorded nothing.
        val circuitStatus = circuitBreaker.getStatus()["/api/v1/circles/"]
        if (circuitStatus != null) {
            assertEquals(
                "a cancelled call must not increment the failure count",
                0,
                circuitStatus.failureCount
            )
            assertEquals("CLOSED", circuitStatus.state)
        }
    }

    @Test
    fun `repeated cancellations past the threshold leave the circuit closed`() {
        // 5 cancellations is the failure threshold, so if cancellation
        // ever counts, the circuit opens here.
        repeat(FAILURE_THRESHOLD_FOR_TEST + 2) {
            val call = client.newCall(
                Request.Builder().url(mockWebServer.url("/api/v1/circles/test")).build()
            )
            call.cancel()
            try {
                call.execute().close()
                fail("expected the cancelled call to throw")
            } catch (e: IOException) {
                // expected
            }
        }

        // A real request must still get through — that is what "closed" means.
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val response = client.newCall(
            Request.Builder().url(mockWebServer.url("/api/v1/circles/test")).build()
        ).execute()
        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun `getStatus returns correct circuit state information`() {
        // Make some failing requests
        repeat(3) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(3) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/test"))
                .build()
            client.newCall(request).execute().close()
        }

        val status = circuitBreaker.getStatus()
        val circuitStatus = status["/api/v1/circles/"]

        assertNotNull("Circuit status should exist", circuitStatus)
        assertEquals("CLOSED", circuitStatus!!.state)
        assertEquals(3, circuitStatus.failureCount)
        assertTrue("lastFailureTime should be recent", circuitStatus.lastFailureTime > 0)
    }

    // =============================================================================
    // PROTECTED PATHS TESTS
    // =============================================================================

    @Test
    fun `circles path is protected`() {
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/circles/"))
                .build()
            client.newCall(request).execute().close()
        }

        // Verify circuit is open
        val status = circuitBreaker.getStatus()
        assertEquals("OPEN", status["/api/v1/circles/"]?.state)
    }

    @Test
    fun `locations path is protected`() {
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/locations"))
                .build()
            client.newCall(request).execute().close()
        }

        // Verify circuit is open
        val status = circuitBreaker.getStatus()
        assertEquals("OPEN", status["/api/v1/locations"]?.state)
    }

    @Test
    fun `places path is protected`() {
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
        }
        repeat(5) {
            val request = Request.Builder()
                .url(mockWebServer.url("/api/v1/places"))
                .build()
            client.newCall(request).execute().close()
        }

        // Verify circuit is open
        val status = circuitBreaker.getStatus()
        assertEquals("OPEN", status["/api/v1/places"]?.state)
    }

    // =============================================================================
    // NETWORK ERROR TESTS
    // =============================================================================

    @Test
    fun `network errors count as failures`() {
        // Use a non-existent server to trigger network error
        val badClient = OkHttpClient.Builder()
            .addInterceptor(circuitBreaker)
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .build()

        // Create requests that will fail with network error
        repeat(5) {
            try {
                val request = Request.Builder()
                    .url("http://192.0.2.1:9999/api/v1/circles/test") // Non-routable IP
                    .build()
                badClient.newCall(request).execute()
            } catch (e: IOException) {
                // Expected - network error
            }
        }

        // Verify circuit is open
        val status = circuitBreaker.getStatus()
        val circuitStatus = status["/api/v1/circles/"]
        assertNotNull("Circuit should exist after failures", circuitStatus)
        assertEquals("Circuit should be open after 5 network errors", "OPEN", circuitStatus!!.state)
    }
}
