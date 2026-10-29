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
import com.pingolin.track.ui.settings.AppLockSwitches.Proof
import com.pingolin.track.util.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Before Settings turns the lock off, or replaces it with a new PIN, the lock itself is asked for, the
 * way the unlock asks for it, so the proof is never the weaker door: the same order (biometrics bound
 * to the unlock's key, its PIN, the phone's screen lock), and the PIN's tries counted with the unlock's
 * (PinAttemptsTest). Without that, anyone holding the app open could turn the lock off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppLockProofTest {

    private companion object {
        const val NONE = SessionManager.APP_LOCK_NONE
        const val PIN = SessionManager.APP_LOCK_PIN
        const val BIOMETRICS = SessionManager.APP_LOCK_BIOMETRICS
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the unlock's order - a PIN lock asks its PIN, a biometric lock its biometrics, else its PIN, else the screen lock`() {
        for (hasPin in listOf(true, false)) for (run in listOf(true, false)) for (screen in listOf(true, false)) {
            val case = "pin=$hasPin biometricsRun=$run screenLock=$screen"
            assertEquals(case, Proof.PIN, AppLockSwitches.proofFor(PIN, hasPin, run, screen))
            assertEquals(case, Proof.NOTHING, AppLockSwitches.proofFor(NONE, hasPin, run, screen))
            val expected = when {
                run -> Proof.BIOMETRICS
                hasPin -> Proof.PIN
                screen -> Proof.SCREEN_LOCK
                else -> Proof.NOTHING
            }
            assertEquals(case, expected, AppLockSwitches.proofFor(BIOMETRICS, hasPin, run, screen))
        }
    }

    @Test
    fun `that is the unlock's own order`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val prompt = main.substringAfter("private fun showAppLockPrompt() {").substringBefore("\n    }\n")
        assertTrue(prompt.contains("SessionManager.APP_LOCK_BIOMETRICS -> showBiometricPrompt()\n            SessionManager.APP_LOCK_PIN -> showPinPrompt()"))
        val biometric = main.substringAfter("private fun showBiometricPrompt() {").substringBefore("\n    private fun ")
        val fallback = "if (sessionManager.getAppLockPinHash() != null) {\n                showPinPrompt()\n            } else {\n                showDeviceCredentialPrompt()\n            }"
        assertTrue("biometrics that can't run", biometric.substringBefore("val cipher = try {").contains(fallback))
        assertTrue("their key retired", biometric.substringAfter("val cipher = try {").substringBefore("val executor").contains(fallback))
        val screen = main.substringAfter("private fun showDeviceCredentialPrompt() {").substringBefore("\n    }\n")
        assertTrue("no screen lock: nothing to ask", screen.contains("isDeviceSecure != true) {\n            onUnlockSuccess()"))
    }

    @Test
    fun `Settings asks in that order, its biometrics bound to the unlock's key`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertTrue(settings.contains("proveCurrent = ::proveCurrentLock,"))
        val prove = settings.substringAfter("private fun proveCurrentLock(").substringBefore("\n    private fun ")
        assertTrue(prove.contains("when (AppLockSwitches.proofFor(method, hasPin, biometricsRun = cipher != null, screenLock = screenLock)) {"))
        assertTrue(
            "the unlock's key, only where a strong biometric runs",
            prove.contains(
                "val cipher = if (method == SessionManager.APP_LOCK_BIOMETRICS && biometricAuthManager.canAuthenticate() == BiometricStatus.Available) {\n" +
                    "            runCatching { keystoreEncryption.getCipherForBiometricAuth() }.getOrNull()"
            ),
        )
        assertTrue(prove.contains("getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true"))
        val biometric = prove.substringAfter("AppLockSwitches.Proof.BIOMETRICS ->").substringBefore("AppLockSwitches.Proof.PIN ->")
        assertTrue("never the screen lock in its place", biometric.contains("allowDeviceCredential = false,"))
        assertTrue(biometric.contains("cryptoObject = BiometricPrompt.CryptoObject(checkNotNull(cipher)),"))
        assertTrue("as at the unlock: a cancel is a no, else the PIN", biometric.contains("if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && hasPin && _binding != null) {\n                        askCurrentPin(change, proven, refused)"))
        assertTrue(prove.contains("AppLockSwitches.Proof.PIN -> askCurrentPin(change, proven, refused)"))
        assertTrue(prove.contains("AppLockSwitches.Proof.SCREEN_LOCK -> proveScreenLock(change, proven, refused)"))
        assertTrue("nothing changes once the screen is gone", prove.contains("val proven = { if (_binding != null) onProven() }"))
        val screen = settings.substringAfter("private fun proveScreenLock(").substringBefore("\n    private fun ")
        assertTrue(screen.contains("androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL\n"))
    }

    @Test
    fun `a longer Auto-Lock asks for the lock first - a shorter or equal one, or no lock, asks nothing`() {
        val minute = 60_000L
        assertTrue(AppLockSwitches.autoLockNeedsProof(current = 0L, chosen = 60 * minute, lockOn = true))
        assertTrue(AppLockSwitches.autoLockNeedsProof(current = minute, chosen = 2 * minute, lockOn = true))
        assertFalse("equal", AppLockSwitches.autoLockNeedsProof(current = 5 * minute, chosen = 5 * minute, lockOn = true))
        assertFalse("shorter", AppLockSwitches.autoLockNeedsProof(current = 5 * minute, chosen = minute, lockOn = true))
        assertFalse("no lock on", AppLockSwitches.autoLockNeedsProof(current = 0L, chosen = 60 * minute, lockOn = false))

        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val dialog = settings.substringAfter("private fun showAutoLockTimeoutDialog()").substringBefore("\n    private fun ")
        assertTrue(dialog.contains("if (AppLockSwitches.autoLockNeedsProof(currentTimeout, chosen, sessionManager.isAppLockEnabled())) {"))
        assertTrue("a refusal keeps the wait it had", dialog.contains("proveCurrentLock(AppLockSwitches.Change.LENGTHEN_AUTO_LOCK, set) {}"))
        assertEquals("set only through the proof's way or the plain one", 1, Regex("""sessionManager\.setAutoLockTimeout\(""").findAll(dialog).count())
    }

    @Test
    fun `a key-bound prompt counts a success only with the key's cipher handed back`() {
        val manager = File("src/main/java/com/pingolin/track/util/BiometricAuthManager.kt").readText()
        val authenticate = manager.substringAfter("fun authenticate(").substringBefore("\n    fun ")
        assertTrue(authenticate.contains("if (cryptoObject != null && result.cryptoObject?.cipher == null) {\n                    errorRef?.invoke("))
        assertTrue(authenticate.contains("if (cryptoObject != null) biometricPrompt.authenticate(promptInfo, cryptoObject) else biometricPrompt.authenticate(promptInfo)"))
    }

    @Test
    fun `the words`() {
        assertEquals("Turn off App Lock", context.getString(R.string.app_lock_prove_off_title))
        assertEquals("Change App Lock", context.getString(R.string.app_lock_prove_change_title))
        assertEquals("Confirm it's you", context.getString(R.string.app_lock_prove_subtitle))
        assertEquals("Use PIN", context.getString(R.string.app_lock_prove_use_pin))
        assertEquals("Use your phone's screen lock", context.getString(R.string.app_lock_prove_screen_lock_subtitle))
        assertEquals("Enter your PIN", context.getString(R.string.app_lock_prove_pin_title))
        assertEquals("To turn off App Lock, enter your PIN.", context.getString(R.string.app_lock_prove_off_pin_body))
        assertEquals("To change App Lock, enter your PIN.", context.getString(R.string.app_lock_prove_change_pin_body))
        assertEquals("To unlock Track with a fingerprint or face, enter your PIN.", context.getString(R.string.app_lock_prove_add_biometrics_pin_body))
        assertEquals("To change when Track locks, enter your PIN.", context.getString(R.string.app_lock_prove_auto_lock_pin_body))
    }
}
