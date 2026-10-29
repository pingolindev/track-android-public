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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import android.util.Log
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GMS FLAVOUR. Manages Google Play Integrity API interactions for
 * attestation-based CAPTCHA bypass.
 *
 * Play Integrity provides cryptographic verification that:
 * - The app is genuine (installed from Play Store)
 * - The device meets integrity requirements (not rooted, verified boot)
 * - The app is running on a real device (not an emulator)
 *
 * For degoogled devices without Play Services, use [HardwareAttestation] + Turnstile instead.
 *
 * `src/libre` holds a same-named, same-API copy that reports permanently
 * unavailable — the `libre` distribution flavour resolves neither
 * `com.google.android.play:integrity` nor `com.google.android.gms`. Keep the two
 * constructors and method signatures identical: `AppModule.providePlayIntegrityManager`
 * is shared and calls `PlayIntegrityManager(context)` in both.
 */
@Singleton
class PlayIntegrityManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PlayIntegrityManager"
    }

    private val integrityManager by lazy {
        IntegrityManagerFactory.create(context)
    }

    /**
     * Check if Google Play Services is available on this device.
     * Returns false on degoogled devices (LineageOS, GrapheneOS, etc.)
     */
    fun isPlayServicesAvailable(): Boolean {
        return try {
            val result = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            result == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Log.d(TAG, "Play Services availability check failed: ${e.message}")
            false
        }
    }

    /**
     * Request an integrity token from Google Play Integrity API.
     *
     * @param nonce Server-generated nonce from /api/v1/auth/attest/challenge?platform=google
     * @return Integrity token to be verified by the server
     * @throws PlayIntegrityException if the request fails
     */
    suspend fun requestIntegrityToken(nonce: String): String {
        if (!isPlayServicesAvailable()) {
            throw PlayIntegrityException(
                PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE,
                "Google Play Services not available"
            )
        }

        return try {
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .build()

            val response = integrityManager.requestIntegrityToken(request).await()
            response.token()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request integrity token", e)
            throw PlayIntegrityException(
                PlayIntegrityError.TOKEN_REQUEST_FAILED,
                "Failed to request integrity token: ${e.message}",
                e
            )
        }
    }

    /**
     * Full attestation flow: get challenge, request token, verify with server.
     *
     * @param getChallenge Lambda to fetch challenge from server
     * @param verifyToken Lambda to verify token with server
     * @return Attestation token for use in registration
     */
    suspend fun performAttestation(
        getChallenge: suspend () -> String,
        verifyToken: suspend (token: String, nonce: String) -> String
    ): String {
        // 1. Get challenge (nonce) from server
        val nonce = getChallenge()

        // 2. Request integrity token from Play Integrity API
        val integrityToken = requestIntegrityToken(nonce)

        // 3. Verify token with server and get attestation token
        return verifyToken(integrityToken, nonce)
    }
}
