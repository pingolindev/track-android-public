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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The libre flavour's attestation half, pinned.
 *
 * These live in `src/testLibre`, so they compile and run ONLY under
 * `:app:test<Env>LibreDebugUnitTest` — which is the point: [PlayIntegrityManager]
 * is a different class in each distribution flavour, and the assertions below
 * ("always unavailable") are true of the libre copy and false of the gms one.
 *
 * What would break without them: someone gives the libre stub a real-looking
 * implementation, or wires [AttestationManager] to Play Integrity
 * unconditionally — and nothing fails, because a libre APK that asks Play
 * Integrity for a token just gets a registration that quietly falls back to
 * Turnstile. The user sees a CAPTCHA and nobody sees a bug.
 */
class LibreFlavourAttestationTest {

    private val context = mockk<Context>(relaxed = true)
    private val playIntegrity = PlayIntegrityManager(context)

    @Test
    fun `Play Services are never available in a libre build`() {
        assertFalse(playIntegrity.isPlayServicesAvailable())
    }

    @Test
    fun `requesting an integrity token fails with PLAY_SERVICES_UNAVAILABLE`() = runTest {
        val thrown = runCatching { playIntegrity.requestIntegrityToken("nonce") }
            .exceptionOrNull()

        assertTrue(
            "expected a PlayIntegrityException, got $thrown",
            thrown is PlayIntegrityException,
        )
        assertEquals(
            PlayIntegrityError.PLAY_SERVICES_UNAVAILABLE,
            (thrown as PlayIntegrityException).error,
        )
    }

    /**
     * It must fail BEFORE asking the server for a challenge — a nonce this build
     * can never answer is a wasted round trip on the registration path.
     */
    @Test
    fun `performAttestation fails without spending a server challenge`() = runTest {
        var challengeRequested = false

        val thrown = runCatching {
            playIntegrity.performAttestation(
                getChallenge = { challengeRequested = true; "nonce" },
                verifyToken = { _, _ -> "unreachable" },
            )
        }.exceptionOrNull()

        assertTrue(thrown is PlayIntegrityException)
        assertFalse("libre must not request an attestation challenge", challengeRequested)
    }

    /**
     * The load-bearing one. [AttestationManager] is shared by both flavours
     * as it is: its `detectPlatform()` already falls through Play Integrity ->
     * hardware attestation -> Turnstile, and the libre stub above simply never
     * satisfies the first branch.
     */
    @Test
    fun `detectPlatform never chooses PLAY_INTEGRITY on libre`() {
        val hardware = mockk<HardwareAttestation>()
        val manager = AttestationManager(
            context = context,
            authApi = mockk(relaxed = true),
            playIntegrityManager = playIntegrity,
            hardwareAttestation = hardware,
        )

        every { hardware.isAvailable() } returns true
        assertEquals(AttestationManager.Platform.HARDWARE_ATTESTATION, manager.detectPlatform())

        every { hardware.isAvailable() } returns false
        assertEquals(AttestationManager.Platform.TURNSTILE_ONLY, manager.detectPlatform())
    }
}
