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
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedBlobResponse
import com.pingolin.track.data.remote.dto.EncryptedKeysResponse
import com.pingolin.track.data.remote.dto.KdfParamsResponse
import com.pingolin.track.data.remote.dto.SrpUserResponse
import com.pingolin.track.data.remote.dto.TwoFactorVerifyResponse
import com.pingolin.track.data.remote.dto.UpdateTracksProfileRequest
import com.pingolin.track.data.remote.dto.UserResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.worker.LocationSyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * An assisted-lane account an admin hasn't approved yet signs in, but the auth server's token carries
 * no Track audience until the approval, so Track refuses its every call. The sign-in and Track's start
 * both keep /auth/me's `registration_status`, for the start's under-review screen; the sign-in sends
 * Track nothing it would refuse, and the approval itself sends the display name the sign-in couldn't.
 * These drive the real [AuthRepository] against a mocked server.
 */
class RegistrationReviewTest {

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)

    private lateinit var repository: AuthRepository

    private val email = "dana@pingolin.test"
    private val danaId = "da1a0000-0000-4000-8000-00000000da1a"
    private val password = "correct horse battery"

    private fun blob(tag: String) = EncryptedBlobResponse(nonce = "n-$tag", ciphertext = "c-$tag", keyId = "v1")

    /** A two-factor sign-in's accepted code: the shortest way through to completeLogin. */
    private fun accepted() = Response.success(
        ApiResponse(
            success = true,
            data = TwoFactorVerifyResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 900L,
                user = SrpUserResponse(id = danaId, email = email, displayName = "Dana"),
                sharingPublicKey = "sharing-public",
                signingPublicKey = null,
                kdfParams = KdfParamsResponse(salt = "salt", memoryKib = 65536, iterations = 3, parallelism = 1),
                encryptedKeys = EncryptedKeysResponse(
                    masterKey = blob("master"),
                    sharingPrivateKey = blob("sharing"),
                    signingPrivateKey = blob("signing"),
                ),
            ),
            error = null,
        )
    )

    /** What /auth/me says about Dana. */
    private fun me(status: String?, displayName: String? = "Dana", address: String = email) = Response.success(
        ApiResponse(
            success = true,
            data = UserResponse(
                id = danaId, email = address, displayName = displayName, publicKey = "pk",
                encryptedPrivateKey = null, emailVerified = true, createdAt = "2026-10-19T00:00:00Z",
                registrationStatus = status,
            ),
            error = null,
        )
    )

    @Before
    fun setUp() {
        mockkObject(LocationSyncWorker.Companion)
        every { LocationSyncWorker.schedule(any()) } just Runs
        every { sessionManager.getUserId() } returns null
        every { sessionManager.getAccessToken() } returns "access-token"
        every { cryptoManager.decryptWithPassword(any(), password, any()) } returns "MASTER"
        every { cryptoManager.decryptWithKey(any(), "MASTER") } returns "SECRET"
        coEvery { authApi.verify2fa(any()) } returns accepted()

        repository = AuthRepository(
            context = mockk(relaxed = true),
            authApi = authApi,
            tracksApi = tracksApi,
            database = mockk(relaxed = true),
            userDao = mockk(relaxed = true),
            circleDao = mockk(relaxed = true),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            keystoreEncryption = mockk(relaxed = true),
            databasePassphraseManager = mockk(relaxed = true),
            encryptedPrefs = mockk(relaxed = true),
            rateLimiter = mockk(relaxed = true),
            locationRepository = mockk(relaxed = true),
            circleRepository = dagger.Lazy { mockk<CircleRepository>(relaxed = true) },
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
    }

    @After
    fun tearDown() {
        unmockkObject(LocationSyncWorker.Companion)
    }

    private suspend fun signIn() =
        assertTrue(repository.verify2fa("2fa-token", "123456", email, password.toCharArray()) is LoginResult.Success)

    @Test
    fun `a sign-in waiting for an admin is kept as waiting, and Track is sent nothing it would refuse`() = runTest {
        coEvery { authApi.getCurrentUser() } returns me("pending")

        signIn()

        verify { sessionManager.keepRegistrationReview(pending = true, rejected = false) }
        coVerify(exactly = 0) { tracksApi.updateProfile(any()) }
    }

    @Test
    fun `a sign-in an admin turned down is kept as turned down, and Track is sent nothing`() = runTest {
        coEvery { authApi.getCurrentUser() } returns me("rejected")

        signIn()

        verify { sessionManager.keepRegistrationReview(pending = false, rejected = true) }
        coVerify(exactly = 0) { tracksApi.updateProfile(any()) }
        assertEquals(AuthRepository.Review.REJECTED, repository.lookAtReview())
    }

    @Test
    fun `waiting, then turned down - kept as turned down, and nothing sent`() = runTest {
        every { sessionManager.isRegistrationPending() } returns true
        coEvery { authApi.getCurrentUser() } returns me("rejected")

        assertEquals(AuthRepository.Review.REJECTED, repository.lookAtReview())

        verify { sessionManager.keepRegistrationReview(pending = false, rejected = true) }
        coVerify(exactly = 0) { tracksApi.updateProfile(any()) }
    }

    @Test
    fun `an approved account's sign-in sends its display name`() = runTest {
        coEvery { authApi.getCurrentUser() } returns me("approved")

        signIn()

        verify { sessionManager.keepRegistrationReview(pending = false, rejected = false) }
        coVerify(exactly = 1) { tracksApi.updateProfile(UpdateTracksProfileRequest(displayName = "Dana")) }
    }

    @Test
    fun `a look that can't be made keeps what was kept - and the sign-in goes on`() = runTest {
        coEvery { authApi.getCurrentUser() } throws java.io.IOException("offline")
        assertEquals(AuthRepository.Review.UNKNOWN, repository.lookAtReview())
        coEvery { authApi.getCurrentUser() } returns Response.error(503, "".toResponseBody(null))
        assertEquals(AuthRepository.Review.UNKNOWN, repository.lookAtReview())

        signIn()

        verify(exactly = 0) { sessionManager.keepRegistrationReview(any(), any()) }
        coVerify(exactly = 1) { tracksApi.updateProfile(UpdateTracksProfileRequest(displayName = "Dana")) }
    }

    @Test
    fun `the approval itself sends the display name the waiting sign-in couldn't`() = runTest {
        every { sessionManager.isRegistrationPending() } returns true
        coEvery { authApi.getCurrentUser() } returns me("pending")
        assertEquals(AuthRepository.Review.WAITING, repository.lookAtReview())
        coVerify(exactly = 0) { tracksApi.updateProfile(any()) }

        // Approved, with the name the person has since given themselves on the web.
        coEvery { authApi.getCurrentUser() } returns me("approved", displayName = "Dana R")
        assertEquals(AuthRepository.Review.NOT_WAITING, repository.lookAtReview())
        verify { sessionManager.keepRegistrationReview(pending = false, rejected = false) }
        coVerify(exactly = 1) { tracksApi.updateProfile(UpdateTracksProfileRequest(displayName = "Dana R")) }
    }

    @Test
    fun `an account that never waited sends nothing on a look`() = runTest {
        every { sessionManager.isRegistrationPending() } returns false
        coEvery { authApi.getCurrentUser() } returns me("approved")
        assertEquals(AuthRepository.Review.NOT_WAITING, repository.lookAtReview())
        // An auth that doesn't name the status yet is no waiting either.
        coEvery { authApi.getCurrentUser() } returns me(null)
        assertEquals(AuthRepository.Review.NOT_WAITING, repository.lookAtReview())
        coVerify(exactly = 0) { tracksApi.updateProfile(any()) }
    }

    @Test
    fun `Track's start keeps it too, from the auth-me it already reads`() = runTest {
        coEvery { authApi.getCurrentUser() } returns me("pending")

        assertTrue("a waiting account is still a session", repository.validateSession())

        verify { sessionManager.keepRegistrationReview(pending = true, rejected = false) }
        coVerify(exactly = 1) { authApi.getCurrentUser() }
    }

    @Test
    fun `Track's start keeps the address the server has now, trimmed and lower-case`() = runTest {
        // The e-mail trust check: the address kept from sign-in goes stale after a change made
        // elsewhere, and an invitation to the new one would then name no one here.
        every { sessionManager.getEmail() } returns email
        coEvery { authApi.getCurrentUser() } returns me("approved", address = " Dana.New@Pingolin.TEST ")

        assertTrue(repository.validateSession())

        verify { sessionManager.setEmail("dana.new@pingolin.test") }
    }

    @Test
    fun `an unchanged address isn't written again, and a blank one keeps what's there`() = runTest {
        every { sessionManager.getEmail() } returns email
        coEvery { authApi.getCurrentUser() } returns me("approved")
        assertTrue(repository.validateSession())
        coEvery { authApi.getCurrentUser() } returns me("approved", address = "  ")
        assertTrue(repository.validateSession())

        verify(exactly = 0) { sessionManager.setEmail(any()) }
    }

    @Test
    fun `a sign-in held back arms no queue sync - an approved one does`() = runTest {
        for (status in listOf("pending", "rejected")) {
            coEvery { authApi.getCurrentUser() } returns me(status)
            signIn()
        }
        verify(exactly = 0) { LocationSyncWorker.schedule(any()) }

        coEvery { authApi.getCurrentUser() } returns me("approved")
        signIn()
        verify(exactly = 1) { LocationSyncWorker.schedule(any()) }
    }
}
