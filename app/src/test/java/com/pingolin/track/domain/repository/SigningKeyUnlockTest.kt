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
import com.pingolin.track.data.remote.dto.ReauthStep2Response
import com.pingolin.track.data.remote.dto.SrpStep1Response
import com.pingolin.track.domain.repository.AuthRepository.InPlaceUnlock
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SrpClientSession
import com.pingolin.track.util.SrpStep2Result
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException

/**
 * A phone that lost the account's signing key (its key store) gets it back from the
 * password, typed once, on this session — re-auth steps 1 and 2, never a new sign-in. The
 * server's proof (M2) is checked before anything it sent is used; only the auth server's
 * `invalid_credentials` means a wrong password; an expired handshake starts again once; the
 * identity already here is kept.
 */
class SigningKeyUnlockTest {

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val srp = mockk<SrpClientSession>()
    private val rateLimiter = mockk<com.pingolin.track.util.AuthRateLimiter>(relaxed = true)
    private lateinit var repository: AuthRepository

    private fun b64(fill: Int) = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })
    /** The public half of the sharing key the password opens (32 bytes of 7): the identity here. */
    private fun publicOf(fill: Int): String = java.util.Base64.getEncoder()
        .encodeToString(com.google.crypto.tink.subtle.X25519.publicFromPrivate(ByteArray(32) { fill.toByte() }))
    private val identity = publicOf(7)
    private fun blob(tag: String) = EncryptedBlobResponse(nonce = "n-$tag", ciphertext = "c-$tag", keyId = "v1")

    private fun step1() = Response.success(ApiResponse(success = true, data = SrpStep1Response(
        salt = "salt", serverPublic = "B", sessionId = "sid", bcryptSalt = null, email = "current@x.test",
    ), error = null))

    private fun step2(
        signing: EncryptedBlobResponse? = blob("signing"),
        sharingPublic: String? = identity,
    ) = Response.success(ApiResponse(success = true, data = ReauthStep2Response(
        reauthToken = "reauth", serverProof = "M2",
        kdfParams = KdfParamsResponse(salt = "kdf", memoryKib = 65536, iterations = 3, parallelism = 1),
        encryptedKeys = if (signing != null) EncryptedKeysResponse(blob("master"), blob("sharing"), signing) else withoutSigningBlob(),
        sharingPublicKey = sharingPublic, signingPublicKey = b64(3),
    ), error = null))

    /** As Gson reads a reply with no signing blob: the DTO declares it non-null, Gson leaves it null. */
    private fun withoutSigningBlob(): EncryptedKeysResponse = com.google.gson.Gson().fromJson(
        """{"master_key":{"nonce":"n-master","ciphertext":"c-master","key_id":"v1"},
            "sharing_private_key":{"nonce":"n-sharing","ciphertext":"c-sharing","key_id":"v1"}}""",
        EncryptedKeysResponse::class.java,
    )

    private fun refused(code: String?, error: String) = Response.error<ApiResponse<ReauthStep2Response>>(
        401, ("""{"success":false,"error":"$error"""" + (code?.let { ""","error_code":"$it"""" } ?: "") + "}").toResponseBody(null),
    )

    @Before
    fun setUp() {
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.getEmail() } returns "remembered@x.test"
        every { cryptoManager.exportPublicKey() } returns identity
        every { cryptoManager.decryptWithPassword(match { it.nonce == "n-master" }, "correct horse", any()) } returns "MASTER"
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-signing" }, "MASTER") } returns b64(9)
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-sharing" }, "MASTER") } returns b64(7)
        every { srp.step1(any(), any(), any()) } just Runs
        every { srp.step2(any(), any()) } returns SrpStep2Result(clientPublic = "A", clientProof = "M1")
        every { srp.step3("M2") } returns true
        coEvery { authApi.reauthStep1() } returns step1()
        coEvery { authApi.reauthStep2(any()) } returns step2()
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
        repository.srpSessions = { srp }
    }

    private suspend fun unlock(password: String = "correct horse") = password.toCharArray().let { chars ->
        repository.unlockSigningKeyInPlace(chars) to chars
    }

    @Test
    fun `the changes waiting for the password go on once it puts the key back, and not before`() = runTest {
        io.mockk.mockkObject(com.pingolin.track.worker.PendingChangesWorker.Companion)
        try {
            every { com.pingolin.track.worker.PendingChangesWorker.kick(any()) } just Runs
            coEvery { authApi.reauthStep2(any()) } returns refused("invalid_credentials", "Invalid credentials")
            assertEquals(InPlaceUnlock.WRONG_PASSWORD, unlock().first)
            verify(exactly = 0) { com.pingolin.track.worker.PendingChangesWorker.kick(any()) }

            coEvery { authApi.reauthStep2(any()) } returns step2()
            assertEquals(InPlaceUnlock.UNLOCKED, unlock().first)
            verify(exactly = 1) { com.pingolin.track.worker.PendingChangesWorker.kick(any()) }
        } finally {
            io.mockk.unmockkObject(com.pingolin.track.worker.PendingChangesWorker.Companion)
        }
    }

    @Test
    fun `the password puts the signing key back on this session - never a new sign-in`() = runTest {
        val (outcome, chars) = unlock()

        assertEquals(InPlaceUnlock.UNLOCKED, outcome)
        verify { cryptoManager.importSigningPrivateKey(b64(9)) }
        verify { cryptoManager.persistKeys() }
        // The identity here is untouched, and no sign-in route is used.
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        coVerify(exactly = 0) { authApi.srpStep1(any()) }
        coVerify(exactly = 0) { authApi.srpStep2(any()) }
        // The proof is made against the address step 1 names, not the one remembered.
        verify { srp.step1("current@x.test", any(), null) }
        assertTrue("the password is wiped", chars.all { it == '\u0000' })
    }

    @Test
    fun `a server whose proof doesn't verify gets nothing used`() = runTest {
        every { srp.step3("M2") } returns false

        assertEquals(InPlaceUnlock.FAILED, unlock().first)

        verify(exactly = 0) { cryptoManager.decryptWithPassword(any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    @Test
    fun `only auth's invalid_credentials means a wrong password`() = runTest {
        coEvery { authApi.reauthStep2(any()) } returns refused("invalid_credentials", "Invalid credentials")
        assertEquals(InPlaceUnlock.WRONG_PASSWORD, unlock().first)

        // An older server: the same words without a code.
        coEvery { authApi.reauthStep2(any()) } returns refused(null, "Invalid credentials")
        assertEquals(InPlaceUnlock.WRONG_PASSWORD, unlock().first)

        // The session's own 401 is not the password.
        coEvery { authApi.reauthStep2(any()) } returns refused(null, "Session has been revoked")
        assertEquals(InPlaceUnlock.FAILED, unlock().first)
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    @Test
    fun `an expired handshake starts again from step 1, once, with the same password`() = runTest {
        coEvery { authApi.reauthStep2(any()) } returnsMany listOf(refused("reauth_session_expired", "Invalid or expired re-auth session"), step2())

        assertEquals(InPlaceUnlock.UNLOCKED, unlock().first)
        coVerify(exactly = 2) { authApi.reauthStep1() }

        coEvery { authApi.reauthStep2(any()) } returns refused("reauth_session_expired", "Invalid or expired re-auth session")
        assertEquals("not a second time", InPlaceUnlock.FAILED, unlock().first)
    }

    @Test
    fun `a server that asks for a sign-in, or keys of another identity, means a sign-in - nothing is installed`() = runTest {
        coEvery { authApi.reauthStep1() } returns Response.error(409,
            """{"success":false,"error":"Please sign in again before re-authenticating","error_code":"credential_unknown"}""".toResponseBody(null))
        assertEquals(InPlaceUnlock.SIGN_IN_NEEDED, unlock().first)

        // The password opens another generation's sharing key: a sign-in, even though the
        // server serves this identity's public key.
        coEvery { authApi.reauthStep1() } returns step1()
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-sharing" }, "MASTER") } returns b64(8)
        assertEquals(InPlaceUnlock.SIGN_IN_NEEDED, unlock().first)
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    /** The identity is decided from what the password opened, never the served key. */
    @Test
    fun `the served public key decides nothing - the one derived from what the password opened does`() = runTest {
        coEvery { authApi.reauthStep2(any()) } returns step2(sharingPublic = publicOf(8))
        assertEquals(InPlaceUnlock.UNLOCKED, unlock().first)
        verify { cryptoManager.importSigningPrivateKey(b64(9)) }

        // A sharing key that doesn't open: nothing installed, and no sign-in forced either.
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-sharing" }, "MASTER") } throws MobileException.DecryptionFailed("no")
        assertEquals(InPlaceUnlock.FAILED, unlock().first)
        verify(exactly = 1) { cryptoManager.importSigningPrivateKey(any()) }
    }

    @Test
    fun `an account with no signing key to open has nothing to unlock`() = runTest {
        coEvery { authApi.reauthStep2(any()) } returns step2(signing = null)

        assertEquals(InPlaceUnlock.NOTHING_TO_UNLOCK, unlock().first)
        verify(exactly = 0) { cryptoManager.importSigningPrivateKey(any()) }
    }

    /** A sign-out names its session with the refresh token, within four seconds. */
    @Test
    fun `a sign-out sends the refresh token, gives up after four seconds, and never throws`() = runTest {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authApi.logout(any()) } returns Response.success(Unit)
        repository.endServerSession()
        coVerify(exactly = 1) { authApi.logout(com.pingolin.track.data.remote.dto.LogoutRequest("refresh-token")) }

        // A server that doesn't answer: the sign-out goes on here after the cap.
        coEvery { authApi.logout(any()) } coAnswers { kotlinx.coroutines.delay(60_000); Response.success(Unit) }
        val started = testScheduler.currentTime
        repository.endServerSession()
        assertEquals(AuthRepository.LOGOUT_CAP_MS, testScheduler.currentTime - started)

        // Nor does a failure stop it.
        coEvery { authApi.logout(any()) } throws java.io.IOException("offline")
        repository.endServerSession()
    }

    @Test
    fun `the sign-out's body names the refresh token, and nothing when there is none`() {
        val gson = com.google.gson.Gson()
        assertEquals("""{"refresh_token":"rt"}""", gson.toJson(com.pingolin.track.data.remote.dto.LogoutRequest("rt")))
        assertEquals("{}", gson.toJson(com.pingolin.track.data.remote.dto.LogoutRequest(null)))
    }

    /** The server is told first; the wipe after it takes the refresh token with it. */
    @Test
    fun `a sign-out tells the server first, then wipes this phone`() = runTest {
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authApi.logout(any()) } returns Response.success(Unit)
        val signingOut = io.mockk.spyk(repository)
        coEvery { signingOut.clearLocalData() } just Runs

        signingOut.logout()

        io.mockk.coVerifyOrder {
            authApi.logout(com.pingolin.track.data.remote.dto.LogoutRequest("refresh-token"))
            signingOut.clearLocalData()
        }
    }

    /** A sign-in whose server proof doesn't hold says so plainly, nothing more. */
    @Test
    fun `a sign-in whose server proof doesn't hold - Couldn't sign in, and nothing is opened`() = runTest {
        every { srp.step3("M2") } returns false
        coEvery { authApi.srpStep1(any()) } returns step1()
        coEvery { authApi.srpStep2(any()) } returns Response.success(ApiResponse(success = true, data =
            com.pingolin.track.data.remote.dto.SrpStep2Response(
                serverProof = "M2", user = null, kdfParams = null, encryptedKeys = null,
                sharingPublicKey = null, signingPublicKey = null, accessToken = "at", refreshToken = "rt",
                requires2fa = null, twoFactorToken = null, availableMethods = null, preferredMethod = null,
            ), error = null))
        every { rateLimiter.checkAttempt(any()) } returns com.pingolin.track.util.RateLimitResult.Allowed

        val result = repository.loginSrp("dana@x.test", "correct horse".toCharArray())

        assertEquals("Couldn't sign in. Please try again.", (result as LoginResult.Error).message)
        verify(exactly = 0) { cryptoManager.decryptWithPassword(any(), any(), any()) }
        verify(exactly = 0) { sessionManager.saveTokenPair(any(), any()) }
    }

    /** The account-deletion re-auth checks the server's proof too. */
    @Test
    fun `deleting the account needs the server's proof - a failed M2 deletes nothing`() = runTest {
        every { sessionManager.getUserId() } returns "user-dana"
        every { srp.step3("M2") } returns false

        val refused = repository.deleteAccount("correct horse".toCharArray())

        assertEquals("Couldn't check your password just now.", refused.exceptionOrNull()?.message)
        coVerify(exactly = 0) { authApi.deleteAccount(any()) }

        every { srp.step3("M2") } returns true
        repository.deleteAccount("correct horse".toCharArray())
        coVerify(exactly = 1) { authApi.deleteAccount(match { it.reauthToken == "reauth" }) }
        verify { srp.step1("current@x.test", any(), null) }
    }

    @Test
    fun `due only when signed in with the identity here and no signing key`() {
        every { cryptoManager.signingPublicKeyHex() } throws MobileException.KeyNotFound("no signing key")
        assertTrue(repository.signingKeyMissing())

        every { cryptoManager.signingPublicKeyHex() } returns "03".repeat(32)
        assertEquals(false, repository.signingKeyMissing())

        every { cryptoManager.signingPublicKeyHex() } throws MobileException.KeyNotFound("no signing key")
        every { cryptoManager.exportPublicKey() } throws MobileException.KeyNotFound("no identity")
        assertEquals("no identity here: a sign-in's job, not this prompt's", false, repository.signingKeyMissing())

        every { cryptoManager.exportPublicKey() } returns identity
        every { sessionManager.isLoggedIn() } returns false
        assertEquals(false, repository.signingKeyMissing())
    }
}
