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
import com.pingolin.track.data.remote.dto.TwoFactorVerifyRequest
import com.pingolin.track.data.remote.dto.TwoFactorVerifyResponse
import com.pingolin.track.util.AuthRateLimiter
import com.pingolin.track.util.RetainedTrust
import com.pingolin.track.util.SessionManager
import com.pingolin.track.worker.LocationSyncWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException

/**
 * A sign-in with two-factor on unlocks the account's keys exactly as one without
 * it does.
 *
 * The password is kept through the code screen: if the code step only saved the
 * session, nothing could open the keys the server sends back, and a two-factor
 * user would sign in to an app where every circle said "key unavailable" — on
 * every new phone, with nothing they could do about it. These drive the real
 * [AuthRepository.verify2fa] against a mocked server and crypto manager.
 */
class TwoFactorSignInTest {

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val rateLimiter = mockk<AuthRateLimiter>(relaxed = true)
    private val retainedTrust = mockk<RetainedTrust>(relaxed = true)
    private val keyStoreRecovery = mockk<com.pingolin.track.util.KeyStoreRecovery>(relaxed = true)
    private val circles = mockk<CircleRepository>(relaxed = true)

    private lateinit var repository: AuthRepository

    private val email = "dana@pingolin.test"

    /** Dana's account id, as the server names it: a UUID (AccountIds). */
    private val danaId = "da1a0000-0000-4000-8000-00000000da1a"
    private val password = "correct horse battery"

    private fun blob(tag: String) = EncryptedBlobResponse(nonce = "n-$tag", ciphertext = "c-$tag", keyId = "v1")

    /** What `POST /auth/login/2fa` answers once the code is accepted. */
    private fun accepted(userId: String = danaId) = Response.success(
        ApiResponse(
            success = true,
            data = TwoFactorVerifyResponse(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresIn = 900L,
                user = SrpUserResponse(id = userId, email = email),
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

    @Before
    fun setUp() {
        // completeLogin schedules the periodic sync; WorkManager has no runtime here.
        mockkObject(LocationSyncWorker.Companion)
        every { LocationSyncWorker.schedule(any()) } just Runs

        every { sessionManager.getUserId() } returns null
        // The key material only opens under the right password.
        every { cryptoManager.decryptWithPassword(match { it.nonce == "n-master" }, password, any()) } returns "MASTER"
        every { cryptoManager.decryptWithPassword(any(), neq(password), any()) } throws
            MobileException.DecryptionFailed("wrong password")
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-sharing" }, "MASTER") } returns "SHARING-PRIVATE"
        every { cryptoManager.decryptWithKey(match { it.nonce == "n-signing" }, "MASTER") } returns "SIGNING-SEED"

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
            circleRepository = dagger.Lazy { circles },
            quotaManager = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            monitoringManager = mockk(relaxed = true),
            hiddenDestinations = mockk(relaxed = true),
            geocodeTokenManager = mockk(relaxed = true),
            crashReporter = mockk(relaxed = true),
            retainedTrust = retainedTrust,
            keyStoreRecovery = keyStoreRecovery,
            alertRulesReload = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        unmockkObject(LocationSyncWorker.Companion)
    }

    @Test
    fun `a two-factor sign-in unlocks the account keys with the password`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns accepted()

        val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        verify { cryptoManager.importPrivateKey("SHARING-PRIVATE") }
        verify { cryptoManager.importSigningPrivateKey("SIGNING-SEED") }
        verify { cryptoManager.persistKeys() }
        // Keys first, session second: a session saved over a failed import is the
        // half-signed-in state this path exists to prevent.
        verifyOrder {
            cryptoManager.importPrivateKey("SHARING-PRIVATE")
            sessionManager.saveAccessToken("access-token")
        }
    }

    @Test
    fun `the code is always sent as totp`() = runTest {
        // The account may PREFER a passkey; a typed code is still an authenticator or
        // backup code, and the server checks both under `totp`.
        val sent = slot<TwoFactorVerifyRequest>()
        coEvery { authApi.verify2fa(capture(sent)) } returns accepted()

        repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertEquals("totp", sent.captured.method)
        assertEquals("123456", sent.captured.code)
        assertEquals("2fa-token", sent.captured.twoFactorToken)
    }

    @Test
    fun `keys that will not open fail the sign-in and save no session`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns accepted()

        val result = repository.verify2fa("2fa-token", "123456", email, "not the password".toCharArray())

        assertTrue("expected Error, got $result", result is LoginResult.Error)
        assertTrue(
            (result as LoginResult.Error).message.startsWith("Couldn't open your data on this phone")
        )
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        // Every clear goes through the owed wipe.
        verify { keyStoreRecovery.clearKeys() }
    }

    @Test
    fun `a rejected code imports nothing and saves nothing`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns
            Response.error(401, """{"success":false,"error":"Invalid 2FA code"}""".toResponseBody(null))

        val result = repository.verify2fa("2fa-token", "000000", email, password.toCharArray())

        assertTrue("expected Error, got $result", result is LoginResult.Error)
        // The attempt is still open for another code.
        assertTrue((result as LoginResult.Error).attemptStillOpen)
        verify(exactly = 0) { cryptoManager.decryptWithPassword(any(), any(), any()) }
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
    }

    /** Not reached is not refused: the attempt is still open for the code. */
    @Test
    fun `a code step that wasn't reached keeps the attempt, in this app's words`() = runTest {
        coEvery { authApi.verify2fa(any()) } throws java.net.UnknownHostException("Unable to resolve host \"api-auth.pingolindev.com\"")

        val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray()) as LoginResult.Error

        assertEquals("Pingolin couldn't be reached. Check your connection and try again.", result.message)
        assertTrue(result.attemptStillOpen)
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
    }

    @Test
    fun `the password is wiped whatever the outcome`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns accepted()
        val forSuccess = password.toCharArray()
        repository.verify2fa("2fa-token", "123456", email, forSuccess)
        assertTrue("password left in memory after a success", forSuccess.all { it == '\u0000' })

        coEvery { authApi.verify2fa(any()) } returns
            Response.error(401, """{"success":false}""".toResponseBody(null))
        val forFailure = password.toCharArray()
        repository.verify2fa("2fa-token", "000000", email, forFailure)
        assertTrue("password left in memory after a failure", forFailure.all { it == '\u0000' })
    }

    @Test
    fun `a two-factor sign-in by another account wipes the previous one before importing keys`() = runTest {
        // The password route does this, and the code route must too: otherwise an
        // account switch through two-factor would keep the previous user's
        // decrypted rows.
        every { sessionManager.getUserId() } returns "50e0b0d1-0000-4000-8000-000000000e15"
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)
        val spy = spyk(repository)
        coEvery { spy.clearLocalData() } just Runs

        val result = spy.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        coVerifyOrder {
            spy.clearLocalData()
            cryptoManager.importPrivateKey("SHARING-PRIVATE")
        }
    }

    @Test
    fun `a saved account id that isn't a UUID is another account - the sign-in wipes it first`() = runTest {
        // getUserId reads such an id as none; the switch check must not.
        every { sessionManager.getUserId() } returns null
        every { sessionManager.savedAccountIdInvalid() } returns true
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)
        val spy = spyk(repository)
        coEvery { spy.clearLocalData() } just Runs

        val result = spy.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        coVerifyOrder {
            spy.clearLocalData()
            cryptoManager.importPrivateKey("SHARING-PRIVATE")
        }
    }

    @Test
    fun `the account signing in is handed to RetainedTrust before its session starts`() = runTest {
        // onSignIn keeps this account's own pins, verified marks and
        // key-access approvals and drops every other account's.
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)

        repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        verifyOrder {
            retainedTrust.onSignIn(danaId, email)
            sessionManager.saveAccessToken("access-token")
        }
    }

    @Test
    fun `the stored keys are seen to before the account's keys are installed`() = runTest {
        // An owed wipe paid, a store that doesn't decode cleared, entries that
        // didn't read dropped — before any of this sign-in's keys goes in.
        coEvery { authApi.verify2fa(any()) } returns accepted()
        every { keyStoreRecovery.beforeSignInImport() } returns com.pingolin.track.util.KeyStoreRecovery.BeforeImport.IMPORT_KEYS

        repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        verifyOrder {
            keyStoreRecovery.beforeSignInImport()
            cryptoManager.importPrivateKey("SHARING-PRIVATE")
            // Pins kept at the last sign-out come back after, from their own file.
            retainedTrust.onSignIn(danaId, email)
            keyStoreRecovery.signedIn()
        }
    }

    @Test
    fun `a store the platform couldn't read stops the sign-in - nothing imported, no session, the store never cleared`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns accepted()
        every { keyStoreRecovery.beforeSignInImport() } returns com.pingolin.track.util.KeyStoreRecovery.BeforeImport.STOP

        val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertEquals("Couldn't sign in. Please try again.", (result as LoginResult.Error).message)
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
        verify(exactly = 0) { keyStoreRecovery.clearKeys() }
    }

    @Test
    fun `a sign-in keeps only its account's entries on this phone, once its keys opened`() = runTest {
        // As on iOS: every other account's owed removals, role changes and the
        // rest kept per account go at this account's sign-in.
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)

        repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        verifyOrder {
            cryptoManager.importPrivateKey("SHARING-PRIVATE")
            retainedTrust.onSignIn(danaId, email)
            circles.keepOnlyAccount(danaId, any())
            sessionManager.saveAccessToken("access-token")
        }
    }

    @Test
    fun `a sign-in whose account id isn't a UUID signs nobody in - nothing imported, tidied or saved`() = runTest {
        // The account id is the server's word, taken only as a UUID.
        for (id in listOf("user-dana", "{${danaId.replace("-", "")}}", "URN:UUID:$danaId", " $danaId", "")) {
            coEvery { authApi.verify2fa(any()) } returns accepted(userId = id)

            val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

            assertEquals(id, "Couldn't sign in. Please try again.", (result as LoginResult.Error).message)
        }
        verify(exactly = 0) { cryptoManager.importPrivateKey(any()) }
        verify(exactly = 0) { retainedTrust.onSignIn(any(), any()) }
        verify(exactly = 0) { circles.keepOnlyAccount(any(), any()) }
        verify(exactly = 0) { sessionManager.saveAccessToken(any()) }
        verify(exactly = 0) { sessionManager.saveUser(any(), any(), any(), any()) }
    }

    @Test
    fun `a sign-in keeps the account's canonical id, whatever spelling the server sent, and passes the typed-email guard on`() = runTest {
        val guard: (String) -> Boolean = { false }
        every { retainedTrust.onSignIn(any(), any()) } returns guard
        val spellings = listOf(danaId.uppercase(), danaId.replace("-", ""), "{$danaId}", "urn:uuid:${danaId.uppercase()}")
        for (spelling in spellings) {
            coEvery { authApi.verify2fa(any()) } returns accepted(userId = spelling)

            repository.verify2fa("2fa-token", "123456", email, password.toCharArray())
        }

        verify(exactly = spellings.size) { retainedTrust.onSignIn(danaId, email) }
        verify(exactly = spellings.size) { circles.keepOnlyAccount(danaId, guard) }
        verify(exactly = spellings.size) { sessionManager.saveUser(danaId, any(), any(), any()) }
    }

    @Test
    fun `a trust tidy that fails lets nothing that holds trust go for another account, and the sign-in goes on`() = runTest {
        every { retainedTrust.onSignIn(any(), any()) } throws IllegalStateException("a store that doesn't read")
        val guard = slot<(String) -> Boolean>()
        every { circles.keepOnlyAccount(danaId, capture(guard)) } just Runs
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)

        val result = repository.verify2fa("2fa-token", "123456", email, password.toCharArray())

        assertTrue("expected Success, got $result", result is LoginResult.Success)
        assertFalse(guard.captured("50e0b0d1-0000-4000-8000-000000000e15"))
    }

    @Test
    fun `a wrong password leaves every account's entries as they are`() = runTest {
        // Never on a failed attempt (B's wrong password leaves A's owed removals).
        coEvery { authApi.verify2fa(any()) } returns accepted(userId = danaId)

        repository.verify2fa("2fa-token", "123456", email, "not the password".toCharArray())

        verify(exactly = 0) { circles.keepOnlyAccount(any(), any()) }
        verify(exactly = 0) { circles.wipeAccountEntries() }
    }

    @Test
    fun `a sign-in whose keys will not open never reads the stored keys again`() = runTest {
        coEvery { authApi.verify2fa(any()) } returns accepted()

        repository.verify2fa("2fa-token", "123456", email, "not the password".toCharArray())

        verify(exactly = 0) { keyStoreRecovery.beforeSignInImport() }
    }
}
