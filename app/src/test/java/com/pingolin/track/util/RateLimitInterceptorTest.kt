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
import java.util.concurrent.TimeUnit

class RateLimitInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.startOnIpv4Loopback()

        client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor())
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `successful request passes through unchanged`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("OK", response.body?.string())
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `rate limit retries on 429 response`() {
        // First request returns 429, second succeeds
        mockWebServer.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val startTime = System.currentTimeMillis()
        val response = client.newCall(request).execute()
        val duration = System.currentTimeMillis() - startTime

        assertEquals(200, response.code)
        assertEquals(2, mockWebServer.requestCount)
        // Should have waited approximately 1 second (allow some tolerance)
        assertTrue("Expected wait of ~1000ms, got ${duration}ms", duration >= 900)
    }

    @Test
    fun `rate limit respects max retries`() {
        // All requests return 429
        repeat(5) {
            mockWebServer.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        }

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        // Should still return 429 after max retries
        assertEquals(429, response.code)
        // Should have made 3 requests: 1 initial + 2 retries (MAX_RETRIES = 2)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `uses exponential backoff when no Retry-After header`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(429))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val startTime = System.currentTimeMillis()
        val response = client.newCall(request).execute()
        val duration = System.currentTimeMillis() - startTime

        assertEquals(200, response.code)
        assertEquals(2, mockWebServer.requestCount)
        // Initial backoff is ~1000ms, should wait at least 800ms (allowing for jitter)
        assertTrue("Expected wait of ~1000ms, got ${duration}ms", duration >= 800)
    }

    @Test
    fun `non-429 errors are not retried`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(500, response.code)
        assertEquals(1, mockWebServer.requestCount) // No retries
    }
}
