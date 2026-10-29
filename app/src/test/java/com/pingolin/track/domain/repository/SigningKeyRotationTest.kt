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
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedBlobResponse
import com.pingolin.track.data.remote.dto.EncryptedKeysResponse
import com.pingolin.track.data.remote.dto.KdfParamsResponse
import com.pingolin.track.data.remote.dto.SrpUserResponse
import com.pingolin.track.data.remote.dto.TwoFactorVerifyResponse
import com.pingolin.track.util.SessionManager
import com.pingolin.track.worker.LocationSyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.SigningKeyPair

/**
 * Sign-in never rotates the account's signing key. A legacy shape (the seed the password
 * opened is the sharing private key) is logged and counted, and a server that reports another
 * `signing_public_key` changes nothing: the opened key is kept and used. Driven through the
 * real [AuthRepository.verify2fa] (both sign-in routes end in the same finishSignIn) against
 * a mocked server and crypto manager.
 */
class SigningKeyRotationTest {

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val crashReporter = mockk<com.pingolin.track.util.CrashReporter>(relaxed = true)
    private lateinit var repository: AuthRepository

    private val email = "dana@pingolin.test"
    private val password = "correct horse battery"
    private fun b64(fill: Int, n: Int = 32) = java.util.Base64.getEncoder().encodeToString(ByteArray(n) { fill.toByte() })
    private val sharingPrivate = b64(7)
    private val realSeed = b64(9)
    private val derivedHex = "03".repeat(32)
    private val otherKeyB64 = b64(4)

    private fun blob(tag: String) = EncryptedBlobResponse(nonce = "n-$tag", ciphertext = "c-$tag", keyId = "v1")

    private fun accepted(serverSigningKeyB64: String?) = Response.success(
        ApiResponse(
            success = true,
            data = TwoFactorVerifyResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 900L,
                // An account id is taken only as a UUID (AccountIds).
                user = SrpUserResponse(id = "da1a0000-0000-4000-8000-00000000da1a", email = email),
                sharingPublicKey = "sharing-public",
                signingPublicKey = serverSigningKeyB64,
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

    private fun opens(signingSeedB64: String) {
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-signing" }, "MASTER") } returns signingSeedB64
    }

    @Before
    fun setUp() {
        mockkObject(LocationSyncWorker.Companion)
        every { LocationSyncWorker.schedule(any()) } just Runs
        every { sessionManager.getUserId() } returns null
        every { cryptoManager.decryptWithPassword(any(), password, any()) } returns "MASTER"
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-sharing" }, "MASTER") } returns sharingPrivate
        every { cryptoManager.signingPublicKeyHex() } returns derivedHex
        every { cryptoManager.generateSigningKeypairDetached() } returns SigningKeyPair(verifyingKeyB64 = b64(5), secretSeedB64 = b64(6))

        repository = AuthRepository(
            context = mockk(relaxed = true),
            authApi = authApi,
            tracksApi = mockk(relaxed = true),
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
            circleRepository = mockk(relaxed = true),
            quotaManager = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            monitoringManager = mockk(relaxed = true),
            hiddenDestinations = mockk(relaxed = true),
            geocodeTokenManager = mockk(relaxed = true),
            crashReporter = { crashReporter },
            retainedTrust = mockk(relaxed = true),
            keyStoreRecovery = mockk(relaxed = true),
            alertRulesReload = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(LocationSyncWorker.Companion)
    }

    private suspend fun signIn(serverSigningKeyB64: String?) {
        coEvery { authApi.verify2fa(any()) } returns accepted(serverSigningKeyB64)
        val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray())
        assertTrue("expected Success, got $result", result is LoginResult.Success)
    }

    @Test
    fun `a signing key the server reports differently is kept - never rotated on the server's word`() = runTest {
        opens(realSeed)

        signIn(serverSigningKeyB64 = otherKeyB64)

        verify(exactly = 1) { cryptoManager.importSigningPrivateKey(realSeed) }
        verify(exactly = 0) { cryptoManager.generateSigningKeypairDetached() }
        verify(exactly = 0) { crashReporter.logException(any(), any()) }
    }

    @Test
    fun `the legacy seed - the sharing key itself - is logged and counted, never rotated`() = runTest {
        opens(sharingPrivate)

        signIn(serverSigningKeyB64 = otherKeyB64)

        verify(exactly = 1) { cryptoManager.importSigningPrivateKey(sharingPrivate) }
        verify(exactly = 0) { cryptoManager.generateSigningKeypairDetached() }
        verify(exactly = 1) { crashReporter.logException(match { it.message == "Legacy signing-key shape at sign-in" }, "signing-key-check") }
    }

    @Test
    fun `the legacy seed held as 64 bytes is counted too, and never rotated`() = runTest {
        val seed64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 } + ByteArray(32) { 3 })
        opens(seed64)

        signIn(serverSigningKeyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 }))

        verify(exactly = 0) { cryptoManager.generateSigningKeypairDetached() }
        verify(exactly = 1) { crashReporter.logException(any(), "signing-key-check") }
    }

    @Test
    fun `a signing key that won't open, or none recorded, never mints one`() = runTest {
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-signing" }, "MASTER") } throws
            MobileException.DecryptionFailed("does not open")

        signIn(serverSigningKeyB64 = otherKeyB64)
        opens(realSeed)
        signIn(serverSigningKeyB64 = null)

        verify(exactly = 0) { cryptoManager.generateSigningKeypairDetached() }
        verify(exactly = 0) { crashReporter.logException(any(), any()) }
    }
}
