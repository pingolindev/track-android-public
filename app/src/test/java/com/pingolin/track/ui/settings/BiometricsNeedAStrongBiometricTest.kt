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

package com.pingolin.track.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * "Use Biometrics" is offered, and proven, only with a strong biometric enrolled, because the unlock
 * asks for one (MainActivity: BIOMETRIC_STRONG with a CryptoObject). Offered with the phone's screen
 * lock alone, a phone without a fingerprint or face would get a "biometric" lock that asks for the
 * PIN every time. It stays usable to turn it off. (Robolectric's biometric shadow answers any request
 * alike, so these read the source.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BiometricsNeedAStrongBiometricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `offered only with a strong biometric, and proven by one, never by the screen lock`() {
        val manager = File("src/main/java/com/pingolin/track/util/BiometricAuthManager.kt").readText()
        val availability = manager.substringAfter("fun canAuthenticate(): BiometricStatus {").substringBefore("\n    }\n")
        assertTrue(availability.contains("biometricManager.canAuthenticate(BIOMETRIC_STRONG))"))
        assertFalse(availability.contains("DEVICE_CREDENTIAL"))

        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val prove = settings.substringAfter("proveBiometrics = { onProven, onRefused ->").substringBefore("say = { id ->")
        assertTrue(prove.contains("allowDeviceCredential = false,"))
    }

    @Test
    fun `a biometric lock can always be turned off, and the row says how to make it usable`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertTrue(settings.contains("isBiometricAvailable || sessionManager.getAppLockMethod() == SessionManager.APP_LOCK_BIOMETRICS"))
        assertTrue("looked again after every change", settings.contains("shown = {\n                updateAutoLockVisibility()\n                showBiometricSwitchUsable()\n            },"))
        assertEquals("Add a fingerprint in your phone's settings to use this; on some phones face unlock works too", context.getString(R.string.app_lock_none_enrolled))
        assertEquals("This phone can't unlock apps with a fingerprint or face", context.getString(R.string.app_lock_no_hardware))
        assertEquals("Fingerprint or face unlock isn't available right now", context.getString(R.string.app_lock_hardware_unavailable))
    }
}
