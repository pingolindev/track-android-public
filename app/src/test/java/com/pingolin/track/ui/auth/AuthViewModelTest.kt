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

package com.pingolin.track.ui.auth

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.UserEntity
import com.pingolin.track.data.remote.dto.EmailVerificationStatusResponse
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.repository.LoginResult
import com.pingolin.track.util.AttestationManager
import com.pingolin.track.util.MapPrewarmer
import com.pingolin.track.util.SessionManager
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for AuthViewModel.
 *
 * Tests cover:
 * - Login flow with SRP (success, 2FA required, error)
 * - 2FA verification
 * - Email verification polling
 * - State management
 * - Password memory clearing
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var authRepository: AuthRepository

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var mapPrewarmer: MapPrewarmer

    @MockK
    private lateinit var attestationManager: AttestationManager

    private lateinit var viewModel: AuthViewModel

    private val testUser = UserEntity(
        id = "user-1",
        email = "test@pingolin.test",
        displayName = null,
        publicKey = "pk",
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Default mock behaviors
        every { sessionManager.getEmail() } returns null
        every { mapPrewarmer.prewarm() } just Runs
        // attestationManager is injected but unused: registration happens in
        // the account portal, and the sign-in path never attests.

        viewModel = AuthViewModel(authRepository, sessionManager, mapPrewarmer, attestationManager)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // =============================================================================
    // LOGIN TESTS
    // =============================================================================

    @Test
    fun `login with empty email shows error`() = runTest {
        val password = "password123".toCharArray()

        viewModel.login("", password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("Please enter email and password", (state as AuthUiState.Error).message)
    }

    @Test
    fun `login with empty password shows error`() = runTest {
        val password = charArrayOf()

        viewModel.login("test@pingolin.test", password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("Please enter email and password", (state as AuthUiState.Error).message)
    }

    @Test
    fun `successful login transitions to Success state`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Success(testUser)

        viewModel.login(email, password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Success", state is AuthUiState.Success)
        verify { mapPrewarmer.prewarm() }
    }

    @Test
    fun `login requiring 2FA transitions to Requires2FA state`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()
        val twoFactorToken = "2fa-token-123"
        val availableMethods = listOf("totp", "backup_code")

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Requires2FA(
            twoFactorToken = twoFactorToken,
            availableMethods = availableMethods,
            preferredMethod = "totp"
        )

        viewModel.login(email, password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Requires2FA", state is AuthUiState.Requires2FA)
        val requires2FA = state as AuthUiState.Requires2FA
        assertEquals(availableMethods, requires2FA.availableMethods)
        assertEquals("totp", requires2FA.preferredMethod)
    }

    @Test
    fun `login error transitions to Error state`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()
        val errorMessage = "Invalid credentials"

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Error(errorMessage)

        viewModel.login(email, password)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals(errorMessage, (state as AuthUiState.Error).message)
    }

    @Test
    fun `login sets Loading state before completing`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()

        coEvery { authRepository.loginSrp(email, any()) } coAnswers {
            // Capture state during execution
            delay(100)
            LoginResult.Success(testUser)
        }

        viewModel.login(email, password)

        // State should be Loading immediately
        assertEquals(AuthUiState.Loading, viewModel.uiState.value)

        advanceUntilIdle()

        // State should be Success after completion
        assertTrue(viewModel.uiState.value is AuthUiState.Success)
    }

    // =============================================================================
    // 2FA VERIFICATION TESTS
    // =============================================================================

    @Test
    fun `verify2fa without pending token shows error`() = runTest {
        viewModel.verify2fa("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("No pending 2FA verification", (state as AuthUiState.Error).message)
    }

    @Test
    fun `verify2fa with empty code shows error`() = runTest {
        // First, trigger 2FA requirement
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Requires2FA(
            twoFactorToken = "token",
            availableMethods = listOf("totp"),
            preferredMethod = "totp"
        )

        viewModel.login(email, password)
        advanceUntilIdle()

        // Now try to verify with empty code
        viewModel.verify2fa("")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("Please enter the verification code", (state as AuthUiState.Error).message)
    }

    @Test
    fun `successful 2FA verification transitions to Success`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()
        val twoFactorToken = "2fa-token"

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Requires2FA(
            twoFactorToken = twoFactorToken,
            availableMethods = listOf("totp"),
            preferredMethod = "totp"
        )
        coEvery { authRepository.verify2fa(twoFactorToken, "123456", email, any()) } returns LoginResult.Success(testUser)

        viewModel.login(email, password)
        advanceUntilIdle()

        viewModel.verify2fa("123456")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Success", state is AuthUiState.Success)
        verify { mapPrewarmer.prewarm() }
    }

    @Test
    fun `failed 2FA verification shows error`() = runTest {
        val email = "test@pingolin.test"
        val password = "password123".toCharArray()
        val twoFactorToken = "2fa-token"

        coEvery { authRepository.loginSrp(email, any()) } returns LoginResult.Requires2FA(
            twoFactorToken = twoFactorToken,
            availableMethods = listOf("totp"),
            preferredMethod = "totp"
        )
        coEvery { authRepository.verify2fa(twoFactorToken, "000000", email, any()) } returns
            LoginResult.Error("Invalid code")

        viewModel.login(email, password)
        advanceUntilIdle()

        viewModel.verify2fa("000000")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("Invalid code", (state as AuthUiState.Error).message)
    }

    // =============================================================================
    // TWO-FACTOR KEEPS THE PASSWORD FOR THE KEYS
    // =============================================================================

    private fun requireCode(methods: List<String> = listOf("totp"), preferred: String? = "totp") {
        coEvery { authRepository.loginSrp(any(), any()) } returns LoginResult.Requires2FA(
            twoFactorToken = "2fa-token",
            availableMethods = methods,
            preferredMethod = preferred
        )
    }

    @Test
    fun `the code step unlocks the keys with the password typed at the first step`() = runTest {
        // The code proves who the user is; only the password opens their
        // keys. Dropped here, it would sign two-factor users in with no
        // keys.
        requireCode()
        val handedOver = mutableListOf<String>()
        coEvery { authRepository.verify2fa("2fa-token", "123456", "test@pingolin.test", any()) } answers {
            handedOver += String(arg<CharArray>(3))
            LoginResult.Success(testUser)
        }

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()
        viewModel.verify2fa("123456")
        advanceUntilIdle()

        assertEquals(listOf("password123"), handedOver)
        assertTrue(viewModel.uiState.value is AuthUiState.Success)
    }

    /** A wrong code leaves the attempt open, so the same code box takes another. */
    @Test
    fun `a wrong code brings the code box back on the same attempt, with the password still held`() = runTest {
        requireCode()
        val handedOver = mutableListOf<Pair<String, String>>()
        coEvery { authRepository.verify2fa("2fa-token", any(), "test@pingolin.test", any()) } answers {
            handedOver += arg<String>(1) to String(arg<CharArray>(3))
            if (arg<String>(1) == "000000") LoginResult.Error("That code isn't right. Please try again.", attemptStillOpen = true)
            else LoginResult.Success(testUser)
        }

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()
        viewModel.verify2fa("000000")
        advanceUntilIdle()

        val again = viewModel.uiState.value as AuthUiState.Requires2FA
        assertEquals("That code isn't right. Please try again.", again.retryMessage)
        assertEquals(listOf("totp"), again.availableMethods)

        viewModel.verify2fa("123456")
        advanceUntilIdle()

        assertEquals(listOf("000000" to "password123", "123456" to "password123"), handedOver)
        assertTrue(viewModel.uiState.value is AuthUiState.Success)
    }

    /** A backup code is 12 characters: sent whole, spaces and dashes out, case as typed. */
    @Test
    fun `a 12-character backup code is sent whole`() = runTest {
        requireCode()
        coEvery { authRepository.verify2fa(any(), any(), any(), any()) } returns LoginResult.Success(testUser)

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()
        viewModel.verify2fa(" abcd-EFGH ijkl ")
        advanceUntilIdle()

        coVerify { authRepository.verify2fa("2fa-token", "abcdEFGHijkl", "test@pingolin.test", any()) }
        assertEquals("ABCDEFGHJKLM", codeToSend("ABCD-EFGH-JKLM"))
        assertEquals("123456", codeToSend("123 456"))
    }

    @Test
    fun `a failed code attempt does not keep the password for another try`() = runTest {
        // A refusal that ends the attempt (too many codes, or it ran out): the held
        // password has nothing left to finish and must not linger.
        requireCode()
        coEvery { authRepository.verify2fa(any(), any(), any(), any()) } returns LoginResult.Error("Couldn't finish signing in. Please sign in again.")

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()
        viewModel.verify2fa("000000")
        advanceUntilIdle()
        viewModel.verify2fa("111111")
        advanceUntilIdle()

        coVerify(exactly = 1) { authRepository.verify2fa(any(), any(), any(), any()) }
        assertEquals("No pending 2FA verification", (viewModel.uiState.value as AuthUiState.Error).message)
    }

    @Test
    fun `cancelling the code step drops the held password`() = runTest {
        requireCode()
        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()

        viewModel.resetState()
        viewModel.verify2fa("123456")
        advanceUntilIdle()

        coVerify(exactly = 0) { authRepository.verify2fa(any(), any(), any(), any()) }
        assertEquals("No pending 2FA verification", (viewModel.uiState.value as AuthUiState.Error).message)
    }

    @Test
    fun `surrounding whitespace in a typed code is not sent`() = runTest {
        requireCode()
        coEvery { authRepository.verify2fa(any(), any(), any(), any()) } returns LoginResult.Success(testUser)
        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()

        viewModel.verify2fa("  123456 ")
        advanceUntilIdle()

        coVerify { authRepository.verify2fa("2fa-token", "123456", any(), any()) }
    }

    // =============================================================================
    // A PASSKEY-ONLY SECOND STEP IS EXPLAINED, NOT ASKED FOR A CODE
    // =============================================================================

    @Test
    fun `a passkey-only second step is explained instead of offering a code box`() = runTest {
        requireCode(methods = listOf("passkey"), preferred = "passkey")

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()

        assertEquals(AuthUiState.PasskeyOnlySecondStep, viewModel.uiState.value)
        // And nothing is held for a code that cannot come.
        viewModel.verify2fa("123456")
        advanceUntilIdle()
        coVerify(exactly = 0) { authRepository.verify2fa(any(), any(), any(), any()) }
    }

    @Test
    fun `an account preferring a passkey but with an authenticator still gets the code box`() = runTest {
        requireCode(methods = listOf("totp", "passkey"), preferred = "passkey")

        viewModel.login("test@pingolin.test", "password123".toCharArray())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AuthUiState.Requires2FA)
    }

    @Test
    fun `only an authenticator method can be finished by typing a code`() {
        assertTrue(offersCodeEntry(listOf("totp")))
        assertTrue(offersCodeEntry(listOf("passkey", "totp")))
        assertTrue(offersCodeEntry(listOf("TOTP")))
        assertFalse(offersCodeEntry(listOf("passkey")))
        assertFalse(offersCodeEntry(emptyList()))
    }

    // =============================================================================
    // EMAIL VERIFICATION TESTS
    // =============================================================================

    @Test
    fun `verifyEmail with empty token shows error`() = runTest {
        viewModel.verifyEmail("")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is AuthUiState.Error)
        assertEquals("Invalid verification token", (state as AuthUiState.Error).message)
    }

    @Test
    fun `successful email verification transitions to EmailVerified`() = runTest {
        coEvery { authRepository.verifyEmail("valid-token") } returns Result.success(Unit)

        viewModel.verifyEmail("valid-token")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be EmailVerified", state is AuthUiState.EmailVerified)
    }

    // =============================================================================
    // STATE MANAGEMENT TESTS
    // =============================================================================

    @Test
    fun `resetState returns to Idle`() = runTest {
        // First, get into an error state
        viewModel.login("", charArrayOf())
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AuthUiState.Error)

        // Reset
        viewModel.resetState()

        assertEquals(AuthUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `isLoggedIn delegates to repository`() {
        every { authRepository.isLoggedIn() } returns true
        assertTrue(viewModel.isLoggedIn())

        every { authRepository.isLoggedIn() } returns false
        assertFalse(viewModel.isLoggedIn())
    }

    // =============================================================================
    // RESEND VERIFICATION TESTS
    // =============================================================================

    @Test
    fun `resendVerificationEmail calls repository`() = runTest {
        every { sessionManager.getEmail() } returns "test@pingolin.test"
        coEvery { authRepository.resendVerification() } returns Result.success(Unit)

        viewModel.resendVerificationEmail()
        advanceUntilIdle()

        coVerify { authRepository.resendVerification() }
    }

    @Test
    fun `resendVerificationEmail disables canResend temporarily`() = runTest {
        val email = "test@pingolin.test"

        // First get into email verification state — this starts the polling loop.
        viewModel.showEmailVerification(email)

        coEvery { authRepository.resendVerification() } returns Result.success(Unit)
        coEvery { authRepository.getVerificationStatus() } returns
            Result.success(EmailVerificationStatusResponse(emailVerified = false, email = "test@pingolin.test", verificationSentAt = null, canResend = true))

        viewModel.resendVerificationEmail()
        // runCurrent() (not advanceUntilIdle) because startVerificationPolling runs a
        // `while(true) { delay(5000) }` loop that would otherwise be advanced forever.
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("State should be EmailVerificationRequired", state is AuthUiState.EmailVerificationRequired)
        assertFalse("canResend should be false", (state as AuthUiState.EmailVerificationRequired).canResend)

        viewModel.resetState()
        runCurrent()
    }

    // =============================================================================
    // VERIFICATION POLL IS BOUNDED AND SCREEN-SCOPED
    // =============================================================================

    private fun notYetVerified() {
        coEvery { authRepository.getVerificationStatus() } returns
            Result.success(EmailVerificationStatusResponse(emailVerified = false, email = "test@pingolin.test", verificationSentAt = null, canResend = true))
    }

    @Test
    fun `the verification poll gives up after its window`() = runTest {
        notYetVerified()
        viewModel.showEmailVerification("test@pingolin.test")

        advanceTimeBy(VERIFICATION_POLL_WINDOW_MS + 1_000L)
        runCurrent()
        val polledInWindow = (VERIFICATION_POLL_WINDOW_MS / VERIFICATION_POLL_INTERVAL_MS).toInt()
        coVerify(exactly = polledInWindow) { authRepository.getVerificationStatus() }

        // Nothing more once the window is spent: the poll is
        // bounded.
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        coVerify(exactly = polledInWindow) { authRepository.getVerificationStatus() }
    }

    @Test
    fun `leaving the screen stops the poll and coming back checks at once`() = runTest {
        notYetVerified()
        viewModel.showEmailVerification("test@pingolin.test")
        runCurrent()

        viewModel.onVerificationScreenStopped()
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        coVerify(exactly = 0) { authRepository.getVerificationStatus() }

        // Back from the mail app, link followed: picked up without waiting a tick.
        coEvery { authRepository.getVerificationStatus() } returns
            Result.success(EmailVerificationStatusResponse(emailVerified = true, email = "test@pingolin.test", verificationSentAt = null, canResend = true))
        viewModel.onVerificationScreenStarted()
        runCurrent()

        coVerify(exactly = 1) { authRepository.getVerificationStatus() }
        assertTrue(viewModel.uiState.value is AuthUiState.EmailVerified)
    }

    @Test
    fun `starting the screen outside verification does not poll`() = runTest {
        notYetVerified()
        viewModel.onVerificationScreenStarted()
        advanceTimeBy(60_000L)
        runCurrent()
        coVerify(exactly = 0) { authRepository.getVerificationStatus() }
    }

    /**
     * After the app ended a sign-in itself it keeps the account (SessionManagerTest:
     * `triggerForceLogout` leaves `getEmail()` = "e@e"; `clearSession` leaves null), and the
     * sign-in form starts from that address, so the password is all that is asked.
     */
    @Test
    fun `the sign-in form starts from the kept address, and from nothing after a chosen sign-out`() {
        every { sessionManager.getEmail() } returns "e@e"
        assertEquals("e@e", viewModel.keptEmail())

        every { sessionManager.getEmail() } returns null
        assertNull(viewModel.keptEmail())

        every { sessionManager.getEmail() } returns " "
        assertNull(viewModel.keptEmail())
    }
}
