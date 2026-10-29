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
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.dto.AndroidKeystoreVerifyRequest
import com.pingolin.track.data.remote.dto.GoogleIntegrityVerifyRequest
import com.pingolin.track.data.remote.dto.TurnstileComboVerifyRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates platform-specific attestation for registration CAPTCHA bypass.
 *
 * Attestation flow by platform:
 * - Play Services available: Use Play Integrity API
 * - Degoogled device: Use Turnstile WebView + hardware attestation (risk scoring)
 * - Fallback: Regular Turnstile token (passed in registration request)
 *
 * After successful attestation, the token is passed in the X-Attestation-Token header
 * during registration to bypass the Turnstile CAPTCHA requirement.
 */
@Singleton
class AttestationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authApi: AuthApi,
    private val playIntegrityManager: PlayIntegrityManager,
    private val hardwareAttestation: HardwareAttestation
) {
    companion object {
        private const val TAG = "AttestationManager"
    }
    /**
     * Result of attestation attempt
     */
    sealed class AttestationResult {
        /** Attestation successful - token can be used for registration */
        data class Success(val token: String, val platform: String) : AttestationResult()
        /** Attestation failed - should fall back to Turnstile in registration form */
        data class RequiresTurnstile(val siteKey: String?) : AttestationResult()
        /** Unrecoverable error */
        data class Error(val message: String) : AttestationResult()
    }

    /**
     * Detected platform type
     */
    enum class Platform {
        PLAY_INTEGRITY,
        HARDWARE_ATTESTATION,
        TURNSTILE_ONLY
    }

    /**
     * Detect which attestation platform is available on this device.
     */
    fun detectPlatform(): Platform {
        return when {
            playIntegrityManager.isPlayServicesAvailable() -> Platform.PLAY_INTEGRITY
            hardwareAttestation.isAvailable() -> Platform.HARDWARE_ATTESTATION
            else -> Platform.TURNSTILE_ONLY
        }
    }

    /**
     * Perform attestation using the best available method.
     *
     * This automatically selects the appropriate attestation method based on
     * device capabilities and server configuration.
     */
    suspend fun performAttestation(): AttestationResult {
        // 1. Check server attestation status
        val statusResponse = try {
            val response = authApi.getAttestationStatus()
            if (response.isSuccessful && response.body()?.success == true) {
                response.body()?.data
            } else {
                Log.w(TAG, "Failed to get attestation status: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking attestation status", e)
            null
        }

        // 2. Select attestation method
        val platform = detectPlatform()
        Log.d(TAG, "Detected platform: $platform, status: $statusResponse")

        // Try attestation methods in order of preference:
        // 1. Play Integrity (if available on device AND enabled on server)
        // 2. Android Keystore hardware attestation (if enabled on server)
        // 3. Turnstile WebView fallback

        if (platform == Platform.PLAY_INTEGRITY && statusResponse?.googleIntegrityEnabled == true) {
            val result = performPlayIntegrityAttestation()
            if (result is AttestationResult.Success) return result
        }

        if (statusResponse?.androidKeystoreEnabled == true && hardwareAttestation.isAvailable()) {
            Log.d(TAG, "Trying Android Keystore attestation")
            val result = performKeystoreAttestation()
            if (result is AttestationResult.Success) return result
        }

        return if (statusResponse?.turnstileEnabled == true) {
            AttestationResult.RequiresTurnstile(null)
        } else {
            AttestationResult.Error("No attestation method available")
        }
    }

    /**
     * Perform Play Integrity attestation.
     */
    private suspend fun performPlayIntegrityAttestation(): AttestationResult {
        return try {
            // 1. Get challenge from server
            val challengeResponse = authApi.getAttestationChallenge("google")
            if (!challengeResponse.isSuccessful || challengeResponse.body()?.data == null) {
                Log.w(TAG, "Failed to get challenge: ${challengeResponse.code()}")
                return AttestationResult.RequiresTurnstile(null)
            }
            val challenge = challengeResponse.body()!!.data!!

            // 2. Request integrity token
            val integrityToken = playIntegrityManager.requestIntegrityToken(challenge.challenge)

            // 3. Verify with server
            val verifyResponse = authApi.verifyGoogleIntegrity(
                GoogleIntegrityVerifyRequest(
                    integrityToken = integrityToken,
                    nonce = challenge.challenge
                )
            )

            if (verifyResponse.isSuccessful && verifyResponse.body()?.data != null) {
                val result = verifyResponse.body()!!.data!!
                AttestationResult.Success(result.attestationToken, result.platform)
            } else {
                Log.w(TAG, "Play Integrity verification failed: ${verifyResponse.code()}")
                AttestationResult.RequiresTurnstile(null)
            }
        } catch (e: PlayIntegrityException) {
            Log.e(TAG, "Play Integrity error", e)
            AttestationResult.RequiresTurnstile(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during Play Integrity attestation", e)
            AttestationResult.Error("Attestation failed: ${e.message}")
        }
    }

    /**
     * Perform Android Keystore hardware attestation.
     */
    private suspend fun performKeystoreAttestation(): AttestationResult {
        return try {
            // 1. Get challenge from server
            val challengeResponse = authApi.getAttestationChallenge("android_hardware")
            if (!challengeResponse.isSuccessful || challengeResponse.body()?.data == null) {
                Log.w(TAG, "Failed to get keystore challenge: ${challengeResponse.code()}")
                return AttestationResult.RequiresTurnstile(null)
            }
            val challenge = challengeResponse.body()!!.data!!

            // 2. Generate attested key with hardware-backed keystore
            val attestation = hardwareAttestation.generateAttestedKey(challenge.challenge)
            Log.d(TAG, "Keystore attestation: securityLevel=${attestation.securityLevel}, chain=${attestation.certificateChain.size}")

            // 3. Verify with server
            val verifyResponse = authApi.verifyAndroidKeystore(
                AndroidKeystoreVerifyRequest(
                    certificateChain = attestation.certificateChain,
                    challenge = challenge.challenge
                )
            )

            // 4. Cleanup attestation key
            hardwareAttestation.cleanup()

            if (verifyResponse.isSuccessful && verifyResponse.body()?.data != null) {
                val result = verifyResponse.body()!!.data!!
                Log.i(TAG, "Keystore attestation verified: securityLevel=${result.securityLevel}")
                AttestationResult.Success(result.attestationToken, result.platform)
            } else {
                Log.w(TAG, "Keystore verification failed: ${verifyResponse.code()}")
                AttestationResult.RequiresTurnstile(null)
            }
        } catch (e: HardwareAttestationException) {
            Log.e(TAG, "Hardware attestation error: ${e.error}", e)
            AttestationResult.RequiresTurnstile(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during keystore attestation", e)
            AttestationResult.RequiresTurnstile(null)
        }
    }

    /**
     * Complete Turnstile + hardware attestation for degoogled devices.
     *
     * @param turnstileToken Token from Turnstile WebView
     * @return AttestationResult with the final attestation token
     */
    suspend fun completeTurnstileCombo(turnstileToken: String): AttestationResult {
        return try {
            // Try to get hardware attestation if available
            var certificateChain: List<String>? = null
            var challenge: String? = null

            if (hardwareAttestation.isAvailable()) {
                try {
                    // Get challenge for hardware attestation
                    val challengeResponse = authApi.getAttestationChallenge("android_hardware")
                    if (challengeResponse.isSuccessful && challengeResponse.body()?.data != null) {
                        challenge = challengeResponse.body()!!.data!!.challenge

                        // Generate attested key
                        val attestationResult = hardwareAttestation.generateAttestedKey(challenge)
                        certificateChain = attestationResult.certificateChain

                        Log.d(TAG, "Hardware attestation: level=${attestationResult.securityLevel}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Hardware attestation failed, continuing with Turnstile only", e)
                } finally {
                    // Clean up attestation key
                    hardwareAttestation.cleanup()
                }
            }

            // Verify combo with server
            val verifyResponse = authApi.verifyTurnstileCombo(
                TurnstileComboVerifyRequest(
                    turnstileToken = turnstileToken,
                    certificateChain = certificateChain,
                    challenge = challenge,
                    deviceFingerprint = null
                )
            )

            if (verifyResponse.isSuccessful && verifyResponse.body()?.data != null) {
                val result = verifyResponse.body()!!.data!!
                AttestationResult.Success(result.attestationToken, result.platform)
            } else {
                val errorBody = verifyResponse.errorBody()?.string()
                Log.w(TAG, "Turnstile combo verification failed: ${verifyResponse.code()} - $errorBody")
                AttestationResult.Error("Verification failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during Turnstile combo attestation", e)
            AttestationResult.Error("Attestation failed: ${e.message}")
        }
    }
}
