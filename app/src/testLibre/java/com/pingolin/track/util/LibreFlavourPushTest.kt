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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The libre flavour's push half, pinned — companion to
 * [LibreFlavourAttestationTest].
 *
 * [PushNotificationManager] itself is shared by both flavours; what changes is
 * which [FcmTokenProvider] Hilt binds (`src/gms/di/DistributionModule.kt` vs
 * `src/libre/di/DistributionModule.kt`). These tests construct the manager with
 * the libre provider directly, so they assert the wiring's CONSEQUENCE rather
 * than the binding declaration.
 */
class LibreFlavourPushTest {

    private val context = mockk<Context>(relaxed = true)
    private val provider = NoFcmTokenProvider()

    @Test
    fun `FCM is never available in a libre build`() {
        assertFalse(provider.isAvailable())
    }

    @Test
    fun `asking for an FCM token fails rather than returning a fake one`() = runTest {
        val thrown = runCatching { provider.token() }.exceptionOrNull()
        assertTrue(
            "a token that is not a real FCM token must never be returned; got $thrown",
            thrown is UnsupportedOperationException,
        )
    }

    /** Runs on the logout / account-switch teardown path, where a throw would
     *  abort the rest of the unregistration. Nothing to delete is not an error. */
    @Test
    fun `deleting the FCM token is a no-op rather than a failure`() = runTest {
        provider.deleteToken()
    }

    /**
     * The one that matters: FCM must not appear in the provider list at all, so
     * neither `getPreferredProvider()` nor the settings chips (SettingsFragment
     * builds them from this same list) can select it.
     *
     * Asserted as an absence rather than an exact list because UnifiedPush's
     * availability depends on whether the JVM stub of `PackageManager` answers
     * the distributor query — that is genuinely undefined off-device, and it is
     * not what this test is about.
     */
    @Test
    fun `FCM is not offered as a push provider`() {
        val manager = PushNotificationManager(
            context = context,
            sessionManager = mockk(relaxed = true),
            fcmTokenProvider = provider,
        )

        assertFalse(
            manager.detectAvailableProviders()
                .contains(PushNotificationManager.PushProvider.FCM),
        )
    }

    /**
     * And therefore the preferred provider is never FCM. Separate from the test
     * above because `getPreferredProvider` has its own `when` block — someone
     * could add an `available.isEmpty() -> FCM` "safety net" there without
     * touching `detectAvailableProviders`, and on libre that would name a
     * transport this APK cannot use.
     */
    @Test
    fun `the preferred provider is never FCM on libre`() {
        val manager = PushNotificationManager(
            context = context,
            sessionManager = mockk(relaxed = true),
            fcmTokenProvider = provider,
        )

        assertFalse(manager.getPreferredProvider() == PushNotificationManager.PushProvider.FCM)
    }
}
