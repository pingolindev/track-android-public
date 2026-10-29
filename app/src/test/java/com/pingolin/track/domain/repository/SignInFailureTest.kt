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
import com.pingolin.track.data.remote.dto.ApiErrors
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.SrpStep1Response
import com.pingolin.track.util.AuthRateLimiter
import com.pingolin.track.util.RateLimitResult
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SrpClientSession
import com.pingolin.track.util.SrpStep2Result
import com.pingolin.track.util.UNREACHABLE_TEXT
import com.pingolin.track.util.plainText
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * A sign-in that the server refuses for too many tries, or can't answer, is told so, and only a
 * wrong password is a wrong password. A refusal at SRP step 1 (a 429, a 503) is not "Invalid
 * email or password", and the sign-in is run again only when the network failed on the way:
 * otherwise every wrong password would reach the server twice, the third typo would meet the auth
 * server's limit of 5 tries in 15 minutes, and after that the correct password would read as
 * wrong too. Only the credentials count against the address, here and on the server.
 */
class SignInFailureTest {

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val srp = mockk<SrpClientSession>()
    private val rateLimiter = mockk<AuthRateLimiter>(relaxed = true)
    private val userDao = mockk<com.pingolin.track.data.local.dao.UserDao>(relaxed = true)
    private lateinit var auth: AuthRepository

    private fun step1() = Response.success(ApiResponse(success = true, data = SrpStep1Response(
        salt = "salt", serverPublic = "B", sessionId = "sid", bcryptSalt = null, email = "dana@x.test",
    ), error = null))

    private fun <T> refused(status: Int, code: String? = null): Response<T> = Response.error(
        status,
        com.google.gson.Gson().toJson(mapOf("success" to false, "error" to "whatever") + (code?.let { mapOf("error_code" to it) } ?: emptyMap()))
            .toResponseBody(null),
    )

    private suspend fun signIn(): LoginResult.Error = auth.loginSrp("dana@x.test", "correct horse".toCharArray()) as LoginResult.Error

    @Before
    fun setUp() {
        every { rateLimiter.checkAttempt(any()) } returns RateLimitResult.Allowed
        every { sessionManager.getEmail() } returns "dana@x.test"
        every { srp.step1(any(), any(), any()) } just Runs
        every { srp.step2(any(), any()) } returns SrpStep2Result(clientPublic = "A", clientProof = "M1")
        coEvery { authApi.srpStep1(any()) } returns step1()
        auth = AuthRepository(
            context = mockk(relaxed = true),
            authApi = authApi,
            tracksApi = mockk(relaxed = true),
            database = mockk(relaxed = true),
            userDao = userDao,
            circleDao = mockk(relaxed = true),
            sessionManager = sessionManager,
            cryptoManager = mockk(relaxed = true),
            keystoreEncryption = mockk(relaxed = true),
            databasePassphraseManager = mockk(relaxed = true),
            encryptedPrefs = mockk(relaxed = true),
            rateLimiter = rateLimiter,
            locationRepository = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            quotaManager = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            monitoringManager = mockk(relaxed = true),
            hiddenDestinations = mockk(relaxed = true),
            geocodeTokenManager = mockk(relaxed = true),
            crashReporter = mockk(relaxed = true),
            retainedTrust = mockk(relaxed = true),
            keyStoreRecovery = mockk(relaxed = true),
            alertRulesReload = mockk(relaxed = true),
        )
        auth.srpSessions = { srp }
    }

    @Test
    fun `step 1 - too many tries and a server in trouble say so, once, and don't count against the address`() = runTest {
        for ((status, code, line) in listOf(
            // auth's per-network and per-(address, network) budgets: a 429 with no code, for minutes.
            Triple(429, null, "Too many attempts. Try again in a few minutes."),
            // The address's budget across networks: a check this app can't show.
            Triple(429, "captcha_required", "Too many attempts for this email address. Try again later."),
            // auth's rate limiter is down: every sign-in is refused.
            Triple(503, null, ApiErrors.TROUBLE),
            Triple(500, "server_error", ApiErrors.TROUBLE),
        )) {
            clearMocks(authApi, rateLimiter, answers = false)
            coEvery { authApi.srpStep1(any()) } returns refused(status, code)
            assertEquals("$status", line, signIn().message)
            coVerify(exactly = 1) { authApi.srpStep1(any()) }
            verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
        }
    }

    @Test
    fun `step 1 - an answer with nothing in it is the server's fault, not the password's`() = runTest {
        coEvery { authApi.srpStep1(any()) } returns Response.success(ApiResponse<SrpStep1Response>(success = true, data = null, error = null))
        assertEquals("Couldn't sign in. Please try again.", signIn().message)
        verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
    }

    @Test
    fun `step 1 - any other refusal is the credentials, asked once and counted once`() = runTest {
        for (status in listOf(400, 401)) {
            clearMocks(authApi, rateLimiter, answers = false)
            coEvery { authApi.srpStep1(any()) } returns refused(status)
            assertEquals("Invalid email or password", signIn().message)
            coVerify(exactly = 1) { authApi.srpStep1(any()) }
            verify(exactly = 1) { rateLimiter.recordFailedAttempt("dana@x.test") }
        }
    }

    @Test
    fun `a wrong password is asked once and counted once - never run again`() = runTest {
        coEvery { authApi.srpStep2(any()) } returns refused(401, "invalid_credentials")
        assertEquals("Invalid email or password", signIn().message)
        coVerify(exactly = 1) { authApi.srpStep1(any()) }
        coVerify(exactly = 1) { authApi.srpStep2(any()) }
        verify(exactly = 1) { rateLimiter.recordFailedAttempt("dana@x.test") }
    }

    @Test
    fun `step 2 - too many tries and trouble don't count against the address either`() = runTest {
        for ((status, line) in listOf(429 to ApiErrors.TOO_MANY, 503 to ApiErrors.TROUBLE)) {
            clearMocks(authApi, rateLimiter, answers = false)
            coEvery { authApi.srpStep1(any()) } returns step1()
            coEvery { authApi.srpStep2(any()) } returns refused(status)
            assertEquals("$status", line, signIn().message)
            coVerify(exactly = 1) { authApi.srpStep1(any()) }
            verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
        }
    }

    @Test
    fun `a network that failed on the way is asked once more, and never counts against the address`() = runTest {
        var calls = 0
        coEvery { authApi.srpStep1(any()) } coAnswers {
            if (calls++ == 0) throw java.io.IOException("connection reset") else step1()
        }
        coEvery { authApi.srpStep2(any()) } returns refused(401)
        // The second attempt went through to an answer: that answer stands, counted once.
        assertEquals("Invalid email or password", signIn().message)
        coVerify(exactly = 2) { authApi.srpStep1(any()) }
        verify(exactly = 1) { rateLimiter.recordFailedAttempt("dana@x.test") }

        // Failing on the way both times: unreachable, and nothing counted.
        clearMocks(authApi, rateLimiter, answers = false)
        coEvery { authApi.srpStep1(any()) } throws java.io.IOException("timeout")
        assertEquals(UNREACHABLE_TEXT, signIn().message)
        coVerify(exactly = 2) { authApi.srpStep1(any()) }
        verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
    }

    @Test
    fun `a sign-in whose database won't open keeps nothing - all or nothing`() = runTest {
        // The password was right (SRP 200/200), then this phone's database wouldn't open. The
        // database is opened before the session is saved: otherwise the phone would be signed in
        // with data it couldn't open.
        coEvery { authApi.srpStep2(any()) } returns Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.SrpStep2Response(
            serverProof = "proof", user = com.pingolin.track.data.remote.dto.SrpUserResponse(id = "a1111111-1111-4111-8111-111111111111", email = "dana@x.test"),
            kdfParams = null, encryptedKeys = null, sharingPublicKey = null, signingPublicKey = null,
            accessToken = "access", refreshToken = "refresh", requires2fa = null, twoFactorToken = null,
            availableMethods = null, preferredMethod = null,
        ), error = null))
        every { srp.step3(any()) } returns true
        coEvery { userDao.getById(any()) } throws com.pingolin.track.util.SecureStorageUnavailableException(java.security.ProviderException("Keystore key generation failed"))
        assertEquals("Track couldn't set up its secure storage on this phone. Please try again.", signIn().message)
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
        verify(exactly = 0) { sessionManager.saveRefreshToken(any()) }
        verify(exactly = 0) { sessionManager.saveUser(any(), any(), any(), any()) }
        coVerify(exactly = 0) { userDao.insert(any()) }
        verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
        coVerify(exactly = 1) { authApi.srpStep1(any()) }
    }

    @Test
    fun `the code step says this phone's storage plainly too, and keeps nothing`() = runTest {
        // finishSignIn opens the database first; on the code step its failure is said in this
        // phone's storage words, not passed to plainText(TWO_STEP_ENDED), which shows this
        // app's own exceptions' words as they are.
        coEvery { authApi.verify2fa(any()) } returns Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.TwoFactorVerifyResponse(
            accessToken = "access", refreshToken = "refresh", expiresIn = null,
            user = com.pingolin.track.data.remote.dto.SrpUserResponse(id = "a1111111-1111-4111-8111-111111111111", email = "dana@x.test"),
            sharingPublicKey = null, signingPublicKey = null, kdfParams = null, encryptedKeys = null,
        ), error = null))
        for (failure in listOf(
            com.pingolin.track.util.SecureStorageUnavailableException(java.security.ProviderException("Keystore key generation failed")),
            com.pingolin.track.util.DeviceLockedException(java.security.KeyStoreException("x")),
        )) {
            clearMocks(sessionManager, userDao, answers = false)
            coEvery { userDao.getById(any()) } throws failure
            val result = auth.verify2fa("two-factor", "123456", "dana@x.test", "correct horse".toCharArray()) as LoginResult.Error
            assertEquals("Track couldn't set up its secure storage on this phone. Please try again.", result.message)
            verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
            verify(exactly = 0) { sessionManager.saveUser(any(), any(), any(), any()) }
        }
    }

    @Test
    fun `the storage exceptions' own words are plain, wherever plainText shows them`() {
        for (e in listOf(
            com.pingolin.track.util.SecureStorageUnavailableException(java.security.ProviderException("x")),
            com.pingolin.track.util.DeviceLockedException(java.security.KeyStoreException("x")),
        )) {
            val shown = e.plainText("Couldn't do that just now.")
            for (word in listOf("passphrase", "Keystore", "key", "Database")) assertFalse(shown, shown.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `this phone's secure storage failing is said plainly, asked once, and never counted`() = runTest {
        // On a phone with no screen lock the database's key can fail after the server
        // has accepted the password: that is no failed try, or five of them would lock
        // sign-in out.
        for (failure in listOf(
            java.security.ProviderException("Keystore key generation failed"),
            IllegalStateException("Room couldn't open", com.pingolin.track.util.SecureStorageUnavailableException(java.security.ProviderException("x"))),
            com.pingolin.track.util.DeviceLockedException(java.security.KeyStoreException("x")),
        )) {
            clearMocks(authApi, rateLimiter, answers = false)
            coEvery { authApi.srpStep1(any()) } throws failure
            assertEquals(
                "${failure.javaClass.simpleName}",
                "Track couldn't set up its secure storage on this phone. Please try again.",
                signIn().message,
            )
            coVerify(exactly = 1) { authApi.srpStep1(any()) }
            verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }
        }
    }
}
