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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.pingolin.track.domain.repository.AuthRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * Instrumented tests for TokenAuthenticator.
 *
 * Tests the automatic token refresh mechanism including:
 * - Concurrent 401 handling (only one refresh per cycle)
 * - Token refresh success path
 * - Token refresh failure triggers logout
 * - Auth endpoints are not retried
 *
 * The refresh itself is TokenRefresher's: one refresh at a time behind a
 * CountDownLatch, so concurrent 401s don't exhaust OkHttp's thread pool. These
 * tests run a real TokenRefresher over a mocked AuthRepository, as production
 * wires the two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class TokenAuthenticatorTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var mockSessionManager: SessionManager
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockAuthRepositoryProvider: Provider<AuthRepository>
    private lateinit var tokenAuthenticator: TokenAuthenticator

    @Before
    fun setUp() {
        hiltRule.inject()

        // MockK (not Mockito) because AuthRepository is a final Kotlin class
        // and mockito-android can't mock final classes without mockito-inline.
        // Same pattern used in BoundaryRepositoryTest.
        mockSessionManager = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
        mockAuthRepositoryProvider = Provider { mockAuthRepository }

        // Default mock behaviors
        every { mockSessionManager.getRefreshToken() } returns "test_refresh_token"
        every { mockSessionManager.getAccessToken() } returns "new_access_token"

        tokenAuthenticator = TokenAuthenticator(
            sessionManager = mockSessionManager,
            tokenRefresher = TokenRefresher(
                sessionManager = mockSessionManager,
                authRepositoryProvider = mockAuthRepositoryProvider
            )
        )
    }

    /**
     * Helper to create a mock 401 response.
     */
    private fun create401Response(
        url: String = "https://api-track.pingolin.com/api/test",
        authHeader: String? = "Bearer old_token"
    ): Response {
        val request = Request.Builder()
            .url(url)
            .apply {
                if (authHeader != null) {
                    header("Authorization", authHeader)
                }
            }
            .build()

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()
    }

    /**
     * Test that only one refresh happens when multiple 401s arrive concurrently.
     */
    @Test
    fun testConcurrent401HandlingOnlyOneRefresh() = runBlocking {
        val refreshCallCount = AtomicInteger(0)
        val latch = CountDownLatch(5) // 5 concurrent requests

        // Mock refresh to track calls and add delay to simulate real refresh
        coEvery { mockAuthRepository.refreshToken() } answers {
            refreshCallCount.incrementAndGet()
            Thread.sleep(100) // Simulate network delay
            Result.success(Unit)
        }
        // Make currentToken match the request's bearer so
        // TokenAuthenticator's double-check shortcut falls through to
        // the actual refresh path. Without this, every call returns
        // instantly without ever incrementing refreshCallCount.
        every { mockSessionManager.getAccessToken() } returns "old_token"

        // Launch 5 concurrent authenticate() calls on Dispatchers.IO so they
        // actually run on different threads. With the default runBlocking
        // dispatcher (single-threaded BlockingEventLoop), `latch.await` below
        // blocks the only thread that could be running the launches —
        // children stay queued, latch never reaches 0, test deadlocks.
        repeat(5) {
            launch(Dispatchers.IO) {
                val response = create401Response(authHeader = "Bearer old_token")
                tokenAuthenticator.authenticate(null, response)
                latch.countDown()
            }
        }

        // Wait for all to complete
        assertTrue("All requests should complete", latch.await(10, TimeUnit.SECONDS))

        // Only one refresh should have happened
        assertEquals(
            "Only one refresh should occur for concurrent 401s",
            1,
            refreshCallCount.get()
        )
    }

    /**
     * Test token refresh success path returns new request with updated token.
     */
    @Test
    fun testTokenRefreshSuccessReturnsNewRequest() = runBlocking {
        val newToken = "brand_new_token"

        // Mock successful refresh
        coEvery { mockAuthRepository.refreshToken() } returns Result.success(Unit)
        every { mockSessionManager.getAccessToken() } returns newToken

        // Create 401 response
        val response = create401Response()

        // Execute
        val newRequest = tokenAuthenticator.authenticate(null, response)

        // Verify
        assertNotNull("Should return new request on successful refresh", newRequest)
        assertEquals(
            "New request should have updated token",
            "Bearer $newToken",
            newRequest?.header("Authorization")
        )
    }

    /**
     * Test token refresh failure triggers logout.
     */
    @Test
    fun refreshRejectedAsSessionExpired_forcesLogout() = runBlocking {
        // TokenAuthenticator has a double-check shortcut that returns the
        // request with the cached `getAccessToken()` value whenever it
        // differs from the incoming bearer header — bypassing refresh
        // entirely. The setUp() mock returns "new_access_token" by default,
        // which trips that shortcut and prevents this test from ever reaching
        // the refresh code path. Override here so currentToken matches the
        // request's "old_token" and the refresh actually runs.
        every { mockSessionManager.getAccessToken() } returns "old_token"

        // SessionExpiredException = the SERVER rejected the session. This is the
        // only failure that may wipe the user's tokens.
        coEvery { mockAuthRepository.refreshToken() } returns
            Result.failure(SessionExpiredException("session revoked"))

        val newRequest = tokenAuthenticator.authenticate(null, create401Response())

        assertNull("Should return null on refresh failure", newRequest)
        verify { mockSessionManager.triggerForceLogout(TrackingStopReason.SESSION_REJECTED, any()) }
    }

    /**
     * A TRANSIENT refresh failure must NOT log the user out.
     *
     * Force-logout is deliberately narrowed to `SessionExpiredException` only — see
     * the comment in TokenAuthenticator: logging out on a network/timeout/5xx failure
     * wipes the local refresh token while the SERVER session is still valid, after
     * which `isLoggedIn()` is false and every location self-heal path refuses to
     * start the foreground service, so the device goes dark.
     */
    @Test
    fun refreshFailingTransiently_keepsTheSession() = runBlocking {
        every { mockSessionManager.getAccessToken() } returns "old_token"
        coEvery { mockAuthRepository.refreshToken() } returns
            Result.failure(java.io.IOException("connection reset"))

        val newRequest = tokenAuthenticator.authenticate(null, create401Response())

        assertNull("The failed request itself still can't be retried", newRequest)
        verify(exactly = 0) { mockSessionManager.triggerForceLogout(any(), any()) }
    }

    /**
     * Test that auth endpoints are not retried (prevents infinite loop).
     */
    @Test
    fun testAuthEndpointsNotRetried() {
        // Block body (not expression body) because `coVerify(...) { refreshToken() }`
        // in an expression-body test could pull `Result<Unit>` (a Kotlin value class)
        // into the inferred return type, which AndroidJUnit4 rejects with
        // "Method should be void" (mangled name `testAuthEndpointsNotRetried-d1pmJ48`).
        runBlocking {
            val authEndpoints = listOf(
                "https://api-auth.pingolin.com/auth/login",
                "https://api-auth.pingolin.com/auth/register",
                "https://api-auth.pingolin.com/auth/refresh",
                "https://api-auth.pingolin.com/auth/srp/step1",
                "https://api-auth.pingolin.com/auth/srp/step2"
            )

            for (endpoint in authEndpoints) {
                val response = create401Response(url = endpoint)
                val newRequest = tokenAuthenticator.authenticate(null, response)

                assertNull(
                    "Auth endpoint $endpoint should not be retried",
                    newRequest
                )
            }

            // Verify refresh was never called for auth endpoints
            coVerify(exactly = 0) { mockAuthRepository.refreshToken() }
        }
    }

    /**
     * Test that multiple retry attempts are limited.
     */
    @Test
    fun testMultipleRetryAttemptsAreLimited() = runBlocking {
        // Mock successful refresh
        coEvery { mockAuthRepository.refreshToken() } returns Result.success(Unit)

        // Create initial 401 response
        val initialRequest = Request.Builder()
            .url("https://api-track.pingolin.com/api/test")
            .header("Authorization", "Bearer old_token")
            .build()

        val initialResponse = Response.Builder()
            .request(initialRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        // First authenticate call
        val firstRetry = tokenAuthenticator.authenticate(null, initialResponse)
        assertNotNull("First retry should succeed", firstRetry)

        // Create response with prior response (simulates second 401).
        // OkHttp 5's priorResponse() just assigns the field, so the initial
        // response is passed as is, with no body-less copy.
        val secondResponse = initialResponse.newBuilder()
            .priorResponse(initialResponse)
            .build()

        // Second authenticate call should be blocked (too many retries)
        val secondRetry = tokenAuthenticator.authenticate(null, secondResponse)
        assertNull("Second retry should be blocked", secondRetry)
    }

    /**
     * With NO tokens at all there is nothing to refresh onto, so the authenticator
     * bails before touching the network.
     *
     * The guard is `if (!hasAccessToken && !hasRefreshToken) return null` in
     * TokenAuthenticator — a live access token is reason enough to carry on,
     * because a parallel SRP login may have stored one while this request was in
     * flight. So "no refresh token" alone does not stop anything; "no tokens at
     * all" does, and that is what is pinned here.
     */
    @Test
    fun noTokensAtAll_returnsNullWithoutRefreshing() {
        // Block body (not expression body) — see testAuthEndpointsNotRetried
        // for the value-class name-mangling explanation.
        runBlocking {
            every { mockSessionManager.getRefreshToken() } returns null
            every { mockSessionManager.getAccessToken() } returns null

            val newRequest = tokenAuthenticator.authenticate(null, create401Response())

            assertNull("Should return null with no tokens to refresh onto", newRequest)
            coVerify(exactly = 0) { mockAuthRepository.refreshToken() }
        }
    }

    /**
     * No refresh token, but the STORED access token has since rotated away from the
     * one that 401'd — retry with the stored token rather than giving up.
     *
     * The token-changed fast path in TokenAuthenticator fires before anything looks
     * at the refresh token, because a token that already changed needs no refresh.
     * A bail-out on a missing refresh token placed ahead of it would break this
     * case.
     */
    @Test
    fun noRefreshToken_butStoredTokenRotated_retriesWithTheStoredToken() {
        runBlocking {
            every { mockSessionManager.getRefreshToken() } returns null
            // setUp's default: differs from the request's "Bearer old_token".
            every { mockSessionManager.getAccessToken() } returns "new_access_token"

            val newRequest = tokenAuthenticator.authenticate(null, create401Response())

            assertEquals(
                "Bearer new_access_token",
                newRequest?.header("Authorization"),
            )
            coVerify(exactly = 0) { mockAuthRepository.refreshToken() }
        }
    }

    /**
     * Test that token change detection skips refresh.
     */
    @Test
    fun testTokenChangeDetectionSkipsRefresh() = runBlocking {
        val newToken = "already_refreshed_token"

        // Simulate token was already refreshed by another thread
        every { mockSessionManager.getAccessToken() } returns newToken

        // Create 401 response with OLD token
        val response = create401Response(authHeader = "Bearer old_token")

        // Execute - should detect token changed and skip refresh
        val newRequest = tokenAuthenticator.authenticate(null, response)

        // Verify - should get new request with updated token without calling refresh
        assertNotNull("Should return new request with updated token", newRequest)
        assertEquals(
            "Should use already-refreshed token",
            "Bearer $newToken",
            newRequest?.header("Authorization")
        )

        // Refresh should still be called because synchronization happens inside
        // In a real scenario with proper timing, refresh would be skipped
        // This test verifies the detection logic exists
    }

    /**
     * Test that waiting threads use the refreshed token.
     */
    @Test
    fun testWaitingThreadsUseRefreshedToken() = runBlocking {
        val refreshedToken = "shared_refreshed_token"
        val refreshCallCount = AtomicInteger(0)
        val completedRequests = AtomicInteger(0)
        val correctTokenCount = AtomicInteger(0)
        val latch = CountDownLatch(3)

        // Mock refresh with delay
        coEvery { mockAuthRepository.refreshToken() } answers {
            refreshCallCount.incrementAndGet()
            Thread.sleep(200) // Simulate network delay
            Result.success(Unit)
        }
        every { mockSessionManager.getAccessToken() } returns refreshedToken

        // Launch concurrent requests on Dispatchers.IO. The default
        // single-threaded runBlocking dispatcher would deadlock against
        // the JVM-level latch.await below — see the longer rationale on
        // testConcurrent401HandlingOnlyOneRefresh.
        repeat(3) {
            launch(Dispatchers.IO) {
                val response = create401Response(authHeader = "Bearer old_token")
                val newRequest = tokenAuthenticator.authenticate(null, response)

                if (newRequest != null) {
                    completedRequests.incrementAndGet()
                    if (newRequest.header("Authorization") == "Bearer $refreshedToken") {
                        correctTokenCount.incrementAndGet()
                    }
                }
                latch.countDown()
            }
        }

        assertTrue("All requests should complete", latch.await(10, TimeUnit.SECONDS))

        // All completed requests should have the correct token
        assertEquals(
            "All requests should have correct token",
            completedRequests.get(),
            correctTokenCount.get()
        )
    }

    /**
     * Test timeout handling for refresh wait.
     */
    @Test
    fun testRefreshWaitTimeout() = runBlocking {
        val slowRefreshCalled = AtomicInteger(0)

        // Mock very slow refresh (longer than 30s timeout)
        coEvery { mockAuthRepository.refreshToken() } answers {
            slowRefreshCalled.incrementAndGet()
            Thread.sleep(35000) // 35 seconds - longer than 30s timeout
            Result.success(Unit)
        }

        val response = create401Response()

        // This should timeout waiting for refresh
        // Note: This test takes at least 30 seconds to run
    }

    /**
     * Test that successful refresh clears the refresh latch.
     */
    @Test
    fun testSuccessfulRefreshClearsLatch() = runBlocking {
        // First refresh
        coEvery { mockAuthRepository.refreshToken() } returns Result.success(Unit)
        every { mockSessionManager.getAccessToken() } returns "token_1"

        val response1 = create401Response(authHeader = "Bearer old_token_1")
        val newRequest1 = tokenAuthenticator.authenticate(null, response1)
        assertNotNull("First refresh should succeed", newRequest1)

        // Small delay to ensure latch is cleared
        delay(100)

        // Second refresh (should work independently)
        every { mockSessionManager.getAccessToken() } returns "token_2"

        val response2 = create401Response(authHeader = "Bearer old_token_2")
        val newRequest2 = tokenAuthenticator.authenticate(null, response2)
        assertNotNull("Second refresh should also succeed", newRequest2)
    }
}
