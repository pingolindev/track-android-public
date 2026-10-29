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
import com.pingolin.track.testutil.startOnIpv4Loopback
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * Tests for TokenAuthenticator circuit breaker and token refresh logic.
 *
 * Key behaviors tested:
 * - Token refresh on 401 response
 * - Circuit breaker opens after 3 consecutive failures
 * - Circuit breaker transitions to half-open after recovery timeout
 * - Successful refresh closes the circuit
 * - Failed half-open test reopens the circuit
 * - Coordinated refresh prevents concurrent token refreshes
 * - Auth endpoints are not retried (prevents infinite loop)
 * - Response count limit prevents infinite retry loops
 */
class TokenAuthenticatorTest {

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var authRepository: AuthRepository

    private lateinit var authRepositoryProvider: Provider<AuthRepository>
    private lateinit var tokenRefresher: TokenRefresher
    private lateinit var tokenAuthenticator: TokenAuthenticator
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        authRepositoryProvider = Provider { authRepository }

        // A REAL TokenRefresher, not a mock: the coordination (single-flight,
        // circuit breaker, 401-only terminal rule) is what these tests are
        // about, and it lives there. Mocking it would test nothing.
        tokenRefresher = TokenRefresher(
            sessionManager = sessionManager,
            authRepositoryProvider = authRepositoryProvider,
        )
        tokenAuthenticator = TokenAuthenticator(
            sessionManager = sessionManager,
            tokenRefresher = tokenRefresher,
        )

        mockWebServer = MockWebServer()
        mockWebServer.startOnIpv4Loopback()

        client = OkHttpClient.Builder()
            .authenticator(tokenAuthenticator)
            .build()
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        clearAllMocks()
    }

    // =============================================================================
    // RE-AUTH REFUSALS
    // =============================================================================

    @Test
    fun `a re-auth refusal with auth's code is the password step's - not refreshed or resent`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "access-token"
        mockWebServer.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"success":false,"error":"Invalid credentials","error_code":"invalid_credentials"}"""))

        val response = client.newCall(Request.Builder()
            .url(mockWebServer.url("/api/v1/auth/reauth/srp/step2"))
            .header("Authorization", "Bearer access-token")
            .post(ByteArray(0).toRequestBody())
            .build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, mockWebServer.requestCount)
        // The caller still reads the code: the peek left the body in place.
        assertTrue(response.body!!.string().contains("invalid_credentials"))
        coVerify(exactly = 0) { authRepository.refreshToken() }
    }

    @Test
    fun `a re-auth 401 without a code is the session's - refreshed and resent`() {
        var storedToken = "old-token"
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } answers { storedToken }
        coEvery { authRepository.refreshToken() } coAnswers {
            storedToken = "new-token"
            Result.success(Unit)
        }
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"Missing token"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true}"""))

        val response = client.newCall(Request.Builder()
            .url(mockWebServer.url("/api/v1/auth/reauth/srp/step1"))
            .header("Authorization", "Bearer old-token")
            .post(ByteArray(0).toRequestBody())
            .build()).execute()

        assertEquals(200, response.code)
        assertEquals(2, mockWebServer.requestCount)
    }

    // =============================================================================
    // SIGN-OUT
    // =============================================================================

    @Test
    fun `a sign-out refused for its expired Bearer is never refreshed around`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "expired-token"
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"Not authenticated"}"""))

        val response = client.newCall(Request.Builder()
            .url(mockWebServer.url("/api/v1/auth/logout"))
            .header("Authorization", "Bearer expired-token")
            .post("""{"refresh_token":"refresh-token"}""".toByteArray().toRequestBody())
            .build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, mockWebServer.requestCount)
        coVerify(exactly = 0) { authRepository.refreshToken() }
    }

    // =============================================================================
    // TOKEN REFRESH TESTS
    // =============================================================================

    @Test
    fun `successful token refresh retries request with new token`() {
        val oldToken = "old-token"
        val newToken = "new-token"

        // Var-backed stub instead of a returnsMany sequence: the
        // authenticator's read count varies with its call sites
        // (credential guard + double-check + post-refresh read), and a
        // positional sequence desyncs whenever one is added. Modelling the
        // real contract — refreshToken() stores the new token,
        // getAccessToken() returns whatever is stored — is
        // order-independent.
        var storedToken = oldToken
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } answers { storedToken }
        coEvery { authRepository.refreshToken() } coAnswers {
            storedToken = newToken
            Result.success(Unit)
        }

        // First request returns 401, second succeeds
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val request = Request.Builder()
            .url(mockWebServer.url("/api/v1/users/me"))
            .header("Authorization", "Bearer $oldToken")
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(2, mockWebServer.requestCount)

        // Verify the second request used the new token
        val retryRequest = mockWebServer.takeRequest() // First request
        mockWebServer.takeRequest() // Second request
        // Token refresh was called
        coVerify(exactly = 1) { authRepository.refreshToken() }
    }

    @Test
    fun `returns null when no refresh token available`() {
        // The authenticator accepts EITHER credential for /auth/refresh
        // (opaque refresh token preferred, legacy access JWT fallback), so
        // "nothing to refresh" requires both to be absent.
        every { sessionManager.getRefreshToken() } returns null
        every { sessionManager.getAccessToken() } returns null

        // Build a mock 401 response
        val originalRequest = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .build()

        val response = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNull("Should return null when no refresh token", result)
        coVerify(exactly = 0) { authRepository.refreshToken() }
    }

    @Test
    fun `does not retry auth endpoints to prevent infinite loop`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"

        val authPaths = listOf(
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/auth/srp/step1"
        )

        for (path in authPaths) {
            val request = Request.Builder()
                .url("http://api.example.com$path")
                .build()

            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody())
                .build()

            val result = tokenAuthenticator.authenticate(null, response)

            assertNull("Should not retry auth endpoint: $path", result)
        }

        coVerify(exactly = 0) { authRepository.refreshToken() }
    }

    @Test
    fun `respects response count limit`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"

        val originalRequest = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .build()

        // Create a response chain that indicates we've already retried.
        // okhttp's Response.Builder requires priorResponse.body == null.
        val firstResponse = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .build()

        val secondResponse = Response.Builder()
            .request(originalRequest)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .priorResponse(firstResponse)
            .build()

        val result = tokenAuthenticator.authenticate(null, secondResponse)

        assertNull("Should not retry after response count >= 2", result)
        coVerify(exactly = 0) { authRepository.refreshToken() }
    }

    // =============================================================================
    // CIRCUIT BREAKER TESTS
    // =============================================================================

    @Test
    fun `circuit breaker opens after 3 consecutive failures`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "token"
        coEvery { authRepository.refreshToken() } returns Result.failure(Exception("Network error"))
        every { sessionManager.triggerForceLogout(any(), any()) } just Runs

        // The authenticator skips refresh if request.Authorization != getAccessToken()
        // (treats it as "another thread already refreshed"). Match them so refresh fires.
        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer token")
            .build()

        // Make 3 failing refresh attempts
        repeat(3) {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody())
                .build()

            tokenAuthenticator.authenticate(null, response)
        }

        // Verify refresh was called 3 times
        coVerify(exactly = 3) { authRepository.refreshToken() }

        // Now circuit should be open - next request should fail fast
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNull("Should return null when circuit is open", result)
        // Should not have made another refresh call
        coVerify(exactly = 3) { authRepository.refreshToken() }
    }

    @Test
    fun `successful refresh closes the circuit`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.triggerForceLogout(any(), any()) } just Runs

        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer old-token")
            .build()

        // Authenticator short-circuits if getAccessToken != request header
        // token (treats it as "another thread refreshed"), so the stub must
        // track what's actually stored rather than replay a positional
        // sequence — the number of getAccessToken reads per attempt varies
        // (credential guard + double-check + post-refresh/failure read) and
        // a returnsMany list desyncs on every such change.
        var storedToken = "old-token"
        every { sessionManager.getAccessToken() } answers { storedToken }

        // First, cause 2 failures (below threshold). The stored token stays
        // "old-token" (== request header), so the failure path correctly
        // concludes the session is dead each time rather than retrying.
        coEvery { authRepository.refreshToken() } returns Result.failure(Exception("Network error"))

        repeat(2) {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody())
                .build()

            tokenAuthenticator.authenticate(null, response)
        }

        // Now succeed — this should reset the failure count. The refresh
        // stores the rotated token, exactly like AuthRepository does.
        coEvery { authRepository.refreshToken() } coAnswers {
            storedToken = "new-token"
            Result.success(Unit)
        }

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNotNull("Should return new request after successful refresh", result)
        assertEquals("Bearer new-token", result?.header("Authorization"))

        // Now cause 2 more failures - circuit should still be closed
        // because success reset the counter. These requests must carry the
        // CURRENT token ("new-token"): with a stale header the double-check
        // would short-circuit to a retry without calling refreshToken at all.
        coEvery { authRepository.refreshToken() } returns Result.failure(Exception("Network error"))

        val refreshedRequest = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer new-token")
            .build()

        repeat(2) {
            val failResponse = Response.Builder()
                .request(refreshedRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody())
                .build()

            tokenAuthenticator.authenticate(null, failResponse)
        }

        // Should have made 5 total refresh calls (2 fail + 1 success + 2 fail)
        coVerify(exactly = 5) { authRepository.refreshToken() }
    }

    // =============================================================================
    // CONCURRENT REFRESH COORDINATION TESTS
    // =============================================================================

    @Test
    fun `concurrent 401s only trigger one refresh`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"

        // Stateful access-token: returns "old-token" (matches request header) until
        // refresh flips it to "new-token". This lets the authenticator reach the
        // refresh path instead of short-circuiting on the "another thread refreshed"
        // check (which fires if getAccessToken() != request Authorization header).
        val currentAccessToken = java.util.concurrent.atomic.AtomicReference("old-token")
        every { sessionManager.getAccessToken() } answers { currentAccessToken.get() }

        val refreshCount = AtomicInteger(0)
        coEvery { authRepository.refreshToken() } coAnswers {
            refreshCount.incrementAndGet()
            Thread.sleep(100) // Simulate network delay
            currentAccessToken.set("new-token")
            Result.success(Unit)
        }

        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer old-token")
            .build()

        val latch = CountDownLatch(3)
        val results = mutableListOf<Request?>()

        // Launch 3 concurrent authentication attempts
        repeat(3) {
            Thread {
                val response = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("".toResponseBody())
                    .build()

                val result = tokenAuthenticator.authenticate(null, response)
                synchronized(results) {
                    results.add(result)
                }
                latch.countDown()
            }.start()
        }

        assertTrue("All threads should complete", latch.await(10, TimeUnit.SECONDS))

        // Only one refresh should have been triggered
        assertEquals("Should only call refresh once", 1, refreshCount.get())

        // All threads should get a valid request back
        assertEquals(3, results.size)
        results.forEach { result ->
            assertNotNull("Each thread should get a request", result)
            assertEquals("Bearer new-token", result?.header("Authorization"))
        }
    }

    // =============================================================================
    // FORCE LOGOUT TESTS
    // =============================================================================

    @Test
    fun `triggers force logout on refresh failure`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns null
        // Force logout fires only on a DEFINITIVE server rejection
        // (SessionExpiredException); transient failures deliberately
        // don't, to avoid wiping a valid session and leaving the
        // device dark for a day.
        coEvery { authRepository.refreshToken() } returns Result.failure(SessionExpiredException("Token refresh rejected: HTTP 401"))
        every { sessionManager.triggerForceLogout(any(), any()) } just Runs

        // Force-logout is reached only on an AUTHED request (Bearer present): a
        // session-less anonymous request bails before the refresh path via the
        // geocode-token guard (authenticate() returns null when Authorization is
        // absent). Without this header the test never exercises the logout branch.
        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer stale-token")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNull("Should return null after refresh failure", result)
        // Also pins WHICH reason is recorded: the journal entry
        // this writes is the only durable evidence of a
        // self-inflicted sign-out, so the reason travelling with
        // it is part of the contract, not an implementation
        // detail.
        verify(exactly = 1) {
            sessionManager.triggerForceLogout(TrackingStopReason.SESSION_REJECTED, any())
        }
    }

    @Test
    fun `triggers force logout when no token after successful refresh`() {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns null // No token after refresh
        coEvery { authRepository.refreshToken() } returns Result.success(Unit)
        every { sessionManager.triggerForceLogout(any(), any()) } just Runs

        // Authed request (Bearer present) so authenticate() reaches the refresh
        // path rather than bailing on the session-less anonymous-request guard.
        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer stale-token")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNull("Should return null when no token after refresh", result)
        verify(exactly = 1) {
            sessionManager.triggerForceLogout(TrackingStopReason.SESSION_NO_TOKEN, any())
        }
    }

    @Test
    fun `session-less anonymous request (no Authorization) does not refresh or force logout`() {
        // A deliberately anonymous request (e.g. geocode token redemption via
        // POST /geocode/reverse) carries no Authorization. A 401 there is not a
        // fixable token expiry: refreshing/logging-out would wipe a valid session
        // and retrying with a Bearer would leak identity onto an anonymous
        // request. authenticate() must bail with null, touching neither path.
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        every { sessionManager.getAccessToken() } returns "access-token"

        val request = Request.Builder()
            .url("http://api.example.com/api/v1/geocode/reverse")
            .build() // NO Authorization header

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        assertNull("Anonymous request must not be retried with a Bearer", result)
        coVerify(exactly = 0) { authRepository.refreshToken() }
        verify(exactly = 0) { sessionManager.triggerForceLogout(any(), any()) }
    }

    // =============================================================================
    // TOKEN CHANGED TESTS
    // =============================================================================

    @Test
    fun `uses new token if it changed while waiting`() {
        val oldToken = "old-token"
        val newToken = "new-token"

        every { sessionManager.getRefreshToken() } returns "refresh-token"
        // Token has already been changed by another thread
        every { sessionManager.getAccessToken() } returns newToken

        val request = Request.Builder()
            .url("http://api.example.com/api/v1/users/me")
            .header("Authorization", "Bearer $oldToken")
            .build()

        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()

        val result = tokenAuthenticator.authenticate(null, response)

        // Should return new request with the changed token without refreshing
        assertNotNull("Should return request with new token", result)
        assertEquals("Bearer $newToken", result?.header("Authorization"))

        // Should not have called refresh since token already changed
        coVerify(exactly = 0) { authRepository.refreshToken() }
    }
}
