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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Android hardware keystore attestation for devices without Google Play Services.
 *
 * Hardware attestation provides cryptographic proof that:
 * - A key was generated in secure hardware (TEE or StrongBox)
 * - The device security state (verified boot, lock status)
 * - The OS version and patch level
 *
 * This is used as part of the registration flow on degoogled devices (LineageOS, GrapheneOS, etc.)
 * in combination with Turnstile CAPTCHA for risk scoring.
 */
@Singleton
class HardwareAttestation @Inject constructor() {

    companion object {
        private const val TAG = "HardwareAttestation"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ATTESTATION_KEY_ALIAS = "pingolin_attestation_key"
    }

    /**
     * Security level of the attestation
     */
    enum class SecurityLevel {
        /** Key stored in software only (no TEE/StrongBox) */
        SOFTWARE,
        /** Key stored in Trusted Execution Environment */
        TRUSTED_ENVIRONMENT,
        /** Key stored in StrongBox (dedicated secure hardware) */
        STRONG_BOX
    }

    /**
     * Result of hardware attestation
     */
    data class AttestationResult(
        /** Base64 DER-encoded certificate chain */
        val certificateChain: List<String>,
        /** Security level achieved */
        val securityLevel: SecurityLevel
    )

    /**
     * Check if hardware-backed keystore attestation is available on this device.
     * Requires Android 8.0+ (API 26) and hardware-backed keystore.
     */
    fun isAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    /**
     * Generate an attested key pair and return the certificate chain.
     *
     * @param challenge Server-provided challenge from /api/v1/auth/attest/challenge?platform=android_hardware
     * @return AttestationResult containing the certificate chain and security level
     * @throws HardwareAttestationException if attestation fails
     */
    fun generateAttestedKey(challenge: String): AttestationResult {
        if (!isAvailable()) {
            throw HardwareAttestationException(
                HardwareAttestationError.NOT_AVAILABLE,
                "Hardware attestation requires Android 8.0+"
            )
        }

        // Clean up any existing attestation key
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(ATTESTATION_KEY_ALIAS)) {
                keyStore.deleteEntry(ATTESTATION_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up existing attestation key", e)
        }

        // Try to generate key with StrongBox first, fall back to TEE
        val securityLevel = try {
            generateKeyWithStrongBox(challenge)
            SecurityLevel.STRONG_BOX
        } catch (e: Exception) {
            Log.d(TAG, "StrongBox not available, trying TEE: ${e.message}")
            try {
                generateKeyWithTEE(challenge)
                SecurityLevel.TRUSTED_ENVIRONMENT
            } catch (e2: Exception) {
                Log.d(TAG, "TEE attestation failed, trying software: ${e2.message}")
                generateKeyWithSoftware(challenge)
                SecurityLevel.SOFTWARE
            }
        }

        // Get the certificate chain
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val chain = keyStore.getCertificateChain(ATTESTATION_KEY_ALIAS)
            ?: throw HardwareAttestationException(
                HardwareAttestationError.CERTIFICATE_CHAIN_MISSING,
                "Failed to get certificate chain"
            )

        // Convert to base64 DER format
        val base64Chain = chain.map { cert ->
            Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        }

        Log.d(TAG, "Hardware attestation successful: securityLevel=$securityLevel, chainLength=${chain.size}")

        return AttestationResult(
            certificateChain = base64Chain,
            securityLevel = securityLevel
        )
    }

    private fun generateKeyWithStrongBox(challenge: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw UnsupportedOperationException("StrongBox requires Android 9.0+")
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            ATTESTATION_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray())
            .setIsStrongBoxBacked(true)
            .build()

        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
    }

    private fun generateKeyWithTEE(challenge: String) {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            ATTESTATION_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray())

        // For Android 9+, explicitly disable StrongBox to ensure we get TEE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            specBuilder.setIsStrongBoxBacked(false)
        }

        keyPairGenerator.initialize(specBuilder.build())
        keyPairGenerator.generateKeyPair()
    }

    private fun generateKeyWithSoftware(challenge: String) {
        // Generate key without attestation challenge - software fallback
        // Note: This won't have attestation extension, server should detect this
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )

        val spec = KeyGenParameterSpec.Builder(
            ATTESTATION_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Omit attestation challenge for software-only key
            .build()

        keyPairGenerator.initialize(spec)
        keyPairGenerator.generateKeyPair()
    }

    /**
     * Clean up the attestation key after use.
     */
    fun cleanup() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(ATTESTATION_KEY_ALIAS)) {
                keyStore.deleteEntry(ATTESTATION_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup attestation key", e)
        }
    }
}

/**
 * Hardware attestation error types
 */
enum class HardwareAttestationError {
    NOT_AVAILABLE,
    KEY_GENERATION_FAILED,
    CERTIFICATE_CHAIN_MISSING,
    VERIFICATION_FAILED
}

/**
 * Exception thrown by hardware attestation operations
 */
class HardwareAttestationException(
    val error: HardwareAttestationError,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
