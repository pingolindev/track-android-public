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
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LIBRE FLAVOUR STUB of the `src/gms` [PlayIntegrityManager].
 *
 * Same class, same package, same public API — but it links nothing from the
 * Play Integrity or Play services client libraries, which is the whole point:
 * those artifacts are scoped to `gmsImplementation` in app/build.gradle.kts and
 * are simply not on this variant's classpath.
 *
 * Play Integrity attests that the app came from the Play Store on a Google-
 * certified device. A build that ships outside Play on a de-Googled device can
 * never satisfy that, so there is nothing to degrade gracefully into here —
 * "unavailable" is the honest and permanent answer.
 *
 * [AttestationManager] needs no flavour-specific code as a result. Its
 * `detectPlatform()` reads
 *
 *     playIntegrityManager.isPlayServicesAvailable() -> Platform.PLAY_INTEGRITY
 *     hardwareAttestation.isAvailable() -> Platform.HARDWARE_ATTESTATION
 *     else -> Platform.TURNSTILE_ONLY
 *
 * so a constant `false` below moves every libre device onto Android Keystore
 * hardware attestation, or Turnstile where the keystore cannot attest either.
 * `LibreFlavourAttestationTest` pins exactly that.
 */
@Singleton
class PlayIntegrityManager @Inject constructor(
    // Unused here, kept so the two flavour copies are diff-comparable and
    // AppModule.providePlayIntegrityManager — which is shared and calls
    // PlayIntegrityManager(context) — compiles against either.
    @Suppress("UNUSED_PARAMETER")
    private val context: Context
) {
    companion object {
        private const val TAG = "PlayIntegrityManager"
    }

    /**
     * Always false: this build carries no Play Services client library at all.
     * Google Play services may well be installed on the device — we still
     * cannot call it, and saying otherwise would send [AttestationManager] down
     * a path that can only end in [PlayIntegrityException].
     */
    fun isPlayServicesAvailable(): Boolean = false

    /**
     * Always fails. Mirrors the gms implementation's own
     * [PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE] path, so callers that
     * already handle a Play-Services-less device need no change.
     */
    suspend fun requestIntegrityToken(nonce: String): String {
        Log.d(TAG, "Play Integrity unavailable in the libre build")
        throw PlayIntegrityException(
            PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE,
            "Google Play Integrity is not available in this build"
        )
    }

    /**
     * Always fails, for the same reason as [requestIntegrityToken]. Note it
     * throws BEFORE calling [getChallenge]: there is no point spending a server
     * round trip on a nonce that can never be answered.
     */
    suspend fun performAttestation(
        getChallenge: suspend () -> String,
        verifyToken: suspend (token: String, nonce: String) -> String
    ): String {
        throw PlayIntegrityException(
            PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE,
            "Google Play Integrity is not available in this build"
        )
    }
}
