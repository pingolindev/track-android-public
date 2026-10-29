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

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages biometric authentication for app unlock.
 *
 * Supports:
 * - Fingerprint
 * - Face recognition
 * - Device credential fallback (PIN/pattern/password)
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val biometricManager = BiometricManager.from(context)

    /**
     * Whether the App Lock can use biometrics here: a strong biometric enrolled, which is what its
     * unlock asks for (MainActivity: BIOMETRIC_STRONG, with a CryptoObject). A phone's screen lock
     * alone does not count: it would make a "biometric" lock that asks for the PIN every time.
     */
    fun canAuthenticate(): BiometricStatus {
        return when (biometricManager.canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NoHardware
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HardwareUnavailable
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NoneEnrolled
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> BiometricStatus.SecurityUpdateRequired
            else -> BiometricStatus.Unknown
        }
    }

    /** Strictly biometrics, ignoring any device-credential fallback. */
    fun canAuthenticateBiometricOnly(): Boolean {
        return biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system biometric prompt.
     *
     * @param negativeButtonText only rendered when [allowDeviceCredential] is false — the
     *   platform supplies its own cancel affordance once a credential fallback is offered
     * @param cryptoObject the unlock's key, for a biometric-only prompt that proves the
     *   App Lock: a success then counts only with its cipher handed back
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Use your biometric credential to continue",
        negativeButtonText: String = "Cancel",
        allowDeviceCredential: Boolean = true,
        cryptoObject: BiometricPrompt.CryptoObject? = null,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        // Use one-shot wrapper to clear lambda references after the callback fires.
        // BiometricViewModel retains mClientCallback for the activity's lifetime,
        // so without this the captured lambdas (and any fragments they reference) leak.
        var successRef: (() -> Unit)? = onSuccess
        var errorRef: ((Int, String) -> Unit)? = onError

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                // Bound to a key, a success counts only with its cipher handed back, as at the unlock: a
                // hooked callback has none to hand.
                if (cryptoObject != null && result.cryptoObject?.cipher == null) {
                    errorRef?.invoke(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, "")
                } else {
                    successRef?.invoke()
                }
                successRef = null
                errorRef = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                errorRef?.invoke(errorCode, errString.toString())
                successRef = null
                errorRef = null
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Don't call onError here - user can retry
                // This is called for each failed attempt, but the prompt stays open
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (allowDeviceCredential) {
            promptInfoBuilder.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        } else {
            // A biometric-only prompt MUST carry a negative button, or the builder throws.
            promptInfoBuilder
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setNegativeButtonText(negativeButtonText)
        }

        val promptInfo = promptInfoBuilder.build()
        if (cryptoObject != null) biometricPrompt.authenticate(promptInfo, cryptoObject) else biometricPrompt.authenticate(promptInfo)
    }

    /** Used on app resume when biometric lock is enabled. */
    fun authenticateForAppUnlock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        authenticate(
            activity = activity,
            title = "Unlock Pingolin Track",
            subtitle = "Authenticate to access your location data",
            allowDeviceCredential = true,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    /** For sensitive but non-cryptographic actions, e.g. viewing location history. */
    fun authenticateForSensitiveOperation(
        activity: FragmentActivity,
        operationDescription: String,
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        authenticate(
            activity = activity,
            title = "Confirm Your Identity",
            subtitle = operationDescription,
            allowDeviceCredential = true,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    /**
     * Authenticate with cryptographic binding using a CryptoObject.
     *
     * Binding the key operation to the prompt is what makes the auth unforgeable: the
     * cipher itself will not work until the platform has authenticated the user, so hooking
     * the success callback gains an attacker nothing.
     *
     * SECURITY: [cipher] MUST come from KeystoreEncryption.getCipherForBiometricAuth(),
     * i.e. be backed by a key created with setUserAuthenticationRequired(true). A plain
     * cipher passed here would restore exactly the bypass this method exists to close.
     */
    fun authenticateWithCrypto(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String = "Authenticate",
        subtitle: String = "Use your biometric credential",
        onSuccess: (Cipher) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val cryptoObject = BiometricPrompt.CryptoObject(cipher)

        var successRef: ((Cipher) -> Unit)? = onSuccess
        var errorRef: ((Int, String) -> Unit)? = onError

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                result.cryptoObject?.cipher?.let { authenticatedCipher ->
                    successRef?.invoke(authenticatedCipher)
                } ?: errorRef?.invoke(BiometricPrompt.ERROR_UNABLE_TO_PROCESS, "No cipher in authentication result")
                successRef = null
                errorRef = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                errorRef?.invoke(errorCode, errString.toString())
                successRef = null
                errorRef = null
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Don't call onError - user can retry
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        // BIOMETRIC_STRONG only: the platform refuses to combine DEVICE_CREDENTIAL with a
        // CryptoObject, so a credential fallback cannot be offered on this path.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, cryptoObject)
    }

    /**
     * [authenticateWithCrypto] with the standard confirm-identity wording, for operations
     * like decrypting keys. [operationDescription] is shown to the user as the subtitle.
     */
    fun authenticateForCryptoOperation(
        activity: FragmentActivity,
        cipher: Cipher,
        operationDescription: String,
        onSuccess: (Cipher) -> Unit,
        onError: (errorCode: Int, errorMessage: String) -> Unit
    ) {
        authenticateWithCrypto(
            activity = activity,
            cipher = cipher,
            title = "Confirm Your Identity",
            subtitle = operationDescription,
            onSuccess = onSuccess,
            onError = onError
        )
    }
}

enum class BiometricStatus {
    Available,
    NoHardware,
    HardwareUnavailable,
    NoneEnrolled,
    SecurityUpdateRequired,
    Unknown
}
