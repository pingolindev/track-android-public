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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.repository.LoginResult
import com.pingolin.track.util.AttestationManager
import com.pingolin.track.util.MapPrewarmer
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SrpClient
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    private val mapPrewarmer: MapPrewarmer,
    private val attestationManager: AttestationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    // Held between the password step and the code, in memory only. The code proves who
    // the user is, but only the password opens their keys, so a two-factor sign-in that
    // dropped it here would finish with no keys at all. Cleared on success, on a
    // failure that ends the attempt, and when the attempt is abandoned. A wrong code
    // leaves the attempt open, and them with it.
    private var pending2faToken: String? = null
    private var pending2faEmail: String? = null
    private var pending2faPassword: CharArray? = null
    private var pending2faMethods: List<String> = emptyList()
    private var pending2faPreferred: String? = null

    // Polling job for email verification
    private var verificationPollingJob: Job? = null

    /**
     * Login with email and password.
     * SECURITY: Password is accepted as CharArray and cleared after use.
     */
    fun login(email: String, password: CharArray) {
        if (email.isBlank() || password.isEmpty()) {
            SrpClient.clearCharArray(password)
            _uiState.value = AuthUiState.Error("Please enter email and password")
            return
        }

        _uiState.value = AuthUiState.Loading

        viewModelScope.launch {
            try {
                // Use SRP login - password NEVER leaves the device
                when (val result = authRepository.loginSrp(email, password)) {
                    is LoginResult.Success -> {
                        // Pre-warm map resources in background to avoid cold start delay
                        mapPrewarmer.prewarm()
                        _uiState.value = AuthUiState.Success
                    }
                    is LoginResult.Requires2FA -> {
                        clearPending2fa()
                        if (offersCodeEntry(result.availableMethods)) {
                            pending2faToken = result.twoFactorToken
                            pending2faEmail = email
                            pending2faPassword = password.copyOf()
                            pending2faMethods = result.availableMethods
                            pending2faPreferred = result.preferredMethod
                            _uiState.value = AuthUiState.Requires2FA(
                                availableMethods = result.availableMethods,
                                preferredMethod = result.preferredMethod
                            )
                        } else {
                            // Nothing typed can finish this sign-in, so nothing is
                            // kept for it and no code box is shown.
                            _uiState.value = AuthUiState.PasskeyOnlySecondStep
                        }
                    }
                    is LoginResult.Error -> {
                        _uiState.value = AuthUiState.Error(result.message)
                    }
                }
            } finally {
                // Clear password from memory
                SrpClient.clearCharArray(password)
            }
        }
    }

    /**
     * Verify the code (an authenticator code or a backup code) to complete login,
     * unlocking the keys with the password held since the first step.
     */
    fun verify2fa(code: String) {
        val token = pending2faToken
        val email = pending2faEmail
        val password = pending2faPassword
        if (token == null || email == null || password == null) {
            clearPending2fa()
            _uiState.value = AuthUiState.Error("No pending 2FA verification")
            return
        }

        if (code.isBlank()) {
            _uiState.value = AuthUiState.Error("Please enter the verification code")
            return
        }

        _uiState.value = AuthUiState.Loading

        viewModelScope.launch {
            // The repository clears the copy it is given. The stash stays only while
            // the attempt does: a wrong code leaves it open, and the same code box
            // comes back; anything else ends it.
            val result = authRepository.verify2fa(token, codeToSend(code), email, password.copyOf())
            if (result is LoginResult.Error && result.attemptStillOpen && pending2faToken == token) {
                _uiState.value = AuthUiState.Requires2FA(pending2faMethods, pending2faPreferred, retryMessage = result.message)
                return@launch
            }
            if (pending2faToken == token) clearPending2fa()
            when (result) {
                is LoginResult.Success -> {
                    // Pre-warm map resources in background to avoid cold start delay
                    mapPrewarmer.prewarm()
                    _uiState.value = AuthUiState.Success
                }
                is LoginResult.Requires2FA -> {
                    // Shouldn't happen after 2FA, but handle gracefully
                    _uiState.value = AuthUiState.Error("Unexpected 2FA state")
                }
                is LoginResult.Error -> {
                    _uiState.value = AuthUiState.Error(result.message)
                }
            }
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
        clearPending2fa()
        stopVerificationPolling()
    }

    private fun clearPending2fa() {
        pending2faPassword?.let { SrpClient.clearCharArray(it) }
        pending2faPassword = null
        pending2faEmail = null
        pending2faToken = null
        pending2faMethods = emptyList()
        pending2faPreferred = null
    }

    fun isLoggedIn(): Boolean = authRepository.isLoggedIn()

    /**
     * The address of an account whose sign-in the app itself ended on this phone
     * (SessionManager.triggerForceLogout keeps the account and ends only the session;
     * a sign-out the person chose clears it). The sign-in form starts from it, so
     * only the password is asked. Null after a chosen sign-out.
     */
    fun keptEmail(): String? = sessionManager.getEmail()?.takeIf { it.isNotBlank() }

    // ===== Email Verification =====

    /**
     * Show email verification screen with the given email.
     */
    fun showEmailVerification(email: String) {
        _uiState.value = AuthUiState.EmailVerificationRequired(email = email)
        startVerificationPolling(email)
    }

    /**
     * Poll the email verification status every [VERIFICATION_POLL_INTERVAL_MS], for
     * at most [VERIFICATION_POLL_WINDOW_MS].
     *
     * Bounded, and only while the screen shows (see [onVerificationScreenStopped]):
     * an unbounded poll on the ViewModel's scope would run on while the user sits in
     * their mail app — which is where they are while verifying — and forever if they
     * never verify. Coming back to the screen checks at once and opens a fresh
     * window, so a link tapped in the mail app is picked up the moment they return.
     *
     * [email] is unused; kept for the call sites.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun startVerificationPolling(email: String, checkImmediately: Boolean = false) {
        stopVerificationPolling()
        verificationPollingJob = viewModelScope.launch {
            if (checkImmediately && verifiedNow()) return@launch
            repeat(VERIFICATION_POLL_WINDOW_MS.toInt() / VERIFICATION_POLL_INTERVAL_MS.toInt()) {
                delay(VERIFICATION_POLL_INTERVAL_MS)
                if (verifiedNow()) return@launch
            }
        }
    }

    /** One status check; on success moves the screen on. True when verified. */
    private suspend fun verifiedNow(): Boolean =
        authRepository.getVerificationStatus().fold(
            onSuccess = { status ->
                if (status.emailVerified) {
                    verificationPollingJob = null
                    onEmailVerified()
                }
                status.emailVerified
            },
            onFailure = { false } // Keep polling within the window.
        )

    /** The verification screen is showing again: check at once, then poll for a fresh window. */
    fun onVerificationScreenStarted() {
        val state = _uiState.value as? AuthUiState.EmailVerificationRequired ?: return
        if (verificationPollingJob?.isActive == true) return
        startVerificationPolling(state.email, checkImmediately = true)
    }

    /** The verification screen is no longer visible: nothing to update, so stop asking. */
    fun onVerificationScreenStopped() {
        stopVerificationPolling()
    }

    private fun stopVerificationPolling() {
        verificationPollingJob?.cancel()
        verificationPollingJob = null
    }

    /**
     * Called when email is verified. Registration and the recovery key
     * belong to the account portal, so verification simply completes.
     */
    private fun onEmailVerified() {
        _uiState.value = AuthUiState.EmailVerified
    }

    /**
     * Resend verification email.
     */
    fun resendVerificationEmail() {
        viewModelScope.launch {
            val currentState = _uiState.value
            val email = if (currentState is AuthUiState.EmailVerificationRequired) {
                currentState.email
            } else {
                sessionManager.getEmail() ?: return@launch
            }

            _uiState.value = AuthUiState.Loading

            authRepository.resendVerification().fold(
                onSuccess = {
                    _uiState.value = AuthUiState.EmailVerificationRequired(
                        email = email,
                        canResend = false  // Disable for a bit
                    )
                    // Re-enable after 60 seconds
                    viewModelScope.launch {
                        delay(60000)
                        val current = _uiState.value
                        if (current is AuthUiState.EmailVerificationRequired && !current.canResend) {
                            _uiState.value = current.copy(canResend = true)
                        }
                    }
                },
                onFailure = { e ->
                    _uiState.value = AuthUiState.Error(e.plainText("Failed to resend verification email"))
                }
            )
        }
    }

    /**
     * Verify email with token from deep link or manual entry.
     */
    fun verifyEmail(token: String) {
        if (token.isBlank()) {
            _uiState.value = AuthUiState.Error("Invalid verification token")
            return
        }

        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading

            authRepository.verifyEmail(token).fold(
                onSuccess = {
                    stopVerificationPolling()
                    onEmailVerified()
                },
                onFailure = { e ->
                    _uiState.value = AuthUiState.Error(e.plainText("Email verification failed"))
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        clearPending2fa()
        stopVerificationPolling()
    }
}

sealed class AuthUiState {
    object Idle : AuthUiState()
    object Loading : AuthUiState()
    object Success : AuthUiState()

    /**
     * 2FA is required to complete login.
     * Show UI to enter verification code.
     */
    data class Requires2FA(
        val availableMethods: List<String>,
        val preferredMethod: String?,
        /** Why the code box is back: the last code wasn't right, and the attempt is still open. */
        val retryMessage: String? = null,
    ) : AuthUiState()

    /**
     * Two-factor is on, but the only second step the account offers is a
     * passkey, which this app cannot use. Explained, never answered with a code
     * box: a typed code cannot satisfy a passkey challenge.
     */
    object PasskeyOnlySecondStep : AuthUiState()

    /**
     * Email verification is required.
     * Show UI to prompt user to verify their email.
     */
    data class EmailVerificationRequired(
        val email: String,
        val canResend: Boolean = true
    ) : AuthUiState()

    /**
     * Email verification succeeded (no recovery key to show).
     */
    object EmailVerified : AuthUiState()



    data class Error(val message: String) : AuthUiState()
}

/**
 * What a typed code is sent as: the whole of it, with only spaces and dashes taken out, as the
 * account portal sends it (`codeToSend`). An authenticator code is 6 digits and a backup code 12
 * characters; nothing is cut short, and the case is left alone (the server uppercases).
 */
internal fun codeToSend(typed: String): String = typed.filterNot { it.isWhitespace() || it == '-' }

/**
 * Whether a second-step challenge offering [availableMethods] can be finished by
 * typing a code. Only `totp` can — backup codes are checked under it too.
 */
internal fun offersCodeEntry(availableMethods: List<String>): Boolean =
    availableMethods.any { it.equals("totp", ignoreCase = true) }

/** How often the email-verification screen asks whether the link was followed. */
internal const val VERIFICATION_POLL_INTERVAL_MS = 5_000L

/** How long one polling window lasts before the screen waits to be shown again. */
internal const val VERIFICATION_POLL_WINDOW_MS = 10L * 60_000L
