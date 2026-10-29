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
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.util.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The App Lock switches. Turning biometrics on must not switch the PIN off through the PIN switch's
 * own listener: that would clear the PIN before the prompt, so cancelling the prompt would leave no
 * lock at all, and even a proven prompt would leave biometrics with no PIN to fall back on. Here
 * the switches are real views, tapped as a person taps them, and the PIN dialogs and the prompt
 * answer as scripted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppLockSwitchesTest {

    private companion object {
        const val NONE = SessionManager.APP_LOCK_NONE
        const val PIN = SessionManager.APP_LOCK_PIN
        const val BIOMETRICS = SessionManager.APP_LOCK_BIOMETRICS
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pinSwitch = CheckBox(context)
    private val biometricSwitch = CheckBox(context)

    /** The lock, as SessionManager keeps it. */
    private var method = NONE
    private var hash: String? = null
    private val lock = object : AppLockSwitches.Lock {
        override val method get() = this@AppLockSwitchesTest.method
        override val pinHash get() = hash
        override fun set(method: String, pinHash: String?) {
            this@AppLockSwitchesTest.method = method
            hash = pinHash
        }
    }

    /** What the person does: a PIN typed and confirmed (null: cancelled), the prompt proven or not. */
    private var newPin: String? = "pin-new"
    private var backupPin: String? = "pin-backup"
    private var proven = true
    /** The lock as it is, satisfied or not, and what each proof was asked for. */
    private var currentProven = true
    private val proofs = mutableListOf<AppLockSwitches.Change>()
    private var biometricPrompts = 0
    private val said = mutableListOf<Int>()

    private fun switches() = AppLockSwitches(
        pinSwitch, biometricSwitch, lock,
        askPin = { onSet, onCancel -> newPin?.let(onSet) ?: onCancel() },
        askBackupPin = { onSet, onCancel -> backupPin?.let(onSet) ?: onCancel() },
        proveBiometrics = { onProven, onRefused ->
            biometricPrompts++
            if (proven) onProven() else onRefused()
        },
        proveCurrent = { change, onProven, onRefused ->
            proofs += change
            if (currentProven) onProven() else onRefused()
        },
        say = { said += it },
        shown = {},
    ).also { it.bind() }

    @Test
    fun `turning biometrics on keeps the PIN, and a refused prompt leaves the PIN lock as it was`() {
        method = PIN
        hash = "pin-0"
        switches()
        assertTrue(pinSwitch.isChecked)
        proven = false

        biometricSwitch.performClick()

        assertEquals(PIN, method)
        assertEquals("pin-0", hash)
        assertTrue("the PIN lock still shown", pinSwitch.isChecked)
        assertFalse(biometricSwitch.isChecked)
        assertTrue("nothing said removed", said.isEmpty())
    }

    @Test
    fun `proven, biometrics lock the app, with the same PIN as their fallback`() {
        method = PIN
        hash = "pin-0"
        switches()

        biometricSwitch.performClick()

        assertEquals(BIOMETRICS, method)
        assertEquals("pin-0", hash)
        assertFalse(pinSwitch.isChecked)
        assertTrue(biometricSwitch.isChecked)
        assertEquals(listOf(R.string.app_lock_biometrics_enabled), said)
    }

    @Test
    fun `with no lock, biometrics ask for a backup PIN first, kept only once they are proven`() {
        switches()
        proven = false
        biometricSwitch.performClick()
        assertEquals(NONE, method)
        assertNull("not kept", hash)
        assertFalse(biometricSwitch.isChecked)

        proven = true
        biometricSwitch.performClick()
        assertEquals(BIOMETRICS, method)
        assertEquals("pin-backup", hash)

        // A backup PIN given up on changes nothing either.
        method = NONE
        hash = null
        backupPin = null
        switches()
        biometricSwitch.performClick()
        assertEquals(NONE, method)
        assertNull(hash)
        assertFalse(biometricSwitch.isChecked)
    }

    @Test
    fun `from biometrics, the PIN switch asks for a PIN - given up on, biometrics stay with their PIN`() {
        method = BIOMETRICS
        hash = "pin-0"
        switches()
        newPin = null

        pinSwitch.performClick()
        assertEquals(BIOMETRICS, method)
        assertEquals("pin-0", hash)
        assertFalse(pinSwitch.isChecked)
        assertTrue(biometricSwitch.isChecked)

        newPin = "pin-2"
        pinSwitch.performClick()
        assertEquals(PIN, method)
        assertEquals("pin-2", hash)
        assertTrue(pinSwitch.isChecked)
        assertFalse(biometricSwitch.isChecked)
    }

    @Test
    fun `turning a PIN lock off waits for the lock itself, and a refusal leaves it on`() {
        currentProven = false
        method = PIN
        hash = "pin-0"
        switches()
        pinSwitch.performClick()
        assertEquals(PIN, method)
        assertEquals("pin-0", hash)
        assertTrue("still shown on", pinSwitch.isChecked)
        assertEquals(listOf(AppLockSwitches.Change.TURN_OFF), proofs)
        assertTrue("nothing said removed", said.isEmpty())

        currentProven = true
        pinSwitch.performClick()
        assertEquals(NONE, method)
        assertNull(hash)
    }

    @Test
    fun `turning a biometric lock off waits for the lock itself, and a refusal leaves it on`() {
        currentProven = false
        method = BIOMETRICS
        hash = "pin-0"
        switches()
        biometricSwitch.performClick()
        assertEquals(BIOMETRICS, method)
        assertEquals("pin-0", hash)
        assertTrue("still shown on", biometricSwitch.isChecked)
        assertEquals(listOf(AppLockSwitches.Change.TURN_OFF), proofs)
        assertTrue("nothing said removed", said.isEmpty())
    }

    @Test
    fun `a new PIN over a lock already on waits for that lock`() {
        method = BIOMETRICS
        hash = "pin-0"
        currentProven = false
        switches()
        pinSwitch.performClick()
        assertEquals("not replaced", BIOMETRICS, method)
        assertEquals("pin-0", hash)
        assertEquals(listOf(AppLockSwitches.Change.REPLACE), proofs)

        currentProven = true
        pinSwitch.performClick()
        assertEquals(PIN, method)
        assertEquals("pin-new", hash)
    }

    @Test
    fun `adding biometrics to a PIN lock asks that PIN first, and a refusal leaves them off`() {
        currentProven = false
        method = PIN
        hash = "pin-0"
        switches()
        biometricSwitch.performClick()
        assertEquals(PIN, method)
        assertEquals("pin-0", hash)
        assertFalse("still shown off", biometricSwitch.isChecked)
        assertEquals(listOf(AppLockSwitches.Change.ADD_BIOMETRICS), proofs)
        assertEquals("no biometric prompt before the PIN", 0, biometricPrompts)

        currentProven = true
        biometricSwitch.performClick()
        assertEquals(BIOMETRICS, method)
        assertEquals("the PIN stays behind them", "pin-0", hash)
        assertEquals(1, biometricPrompts)
    }

    @Test
    fun `with no lock on, biometrics go straight to their own prompt`() {
        currentProven = false
        switches()
        biometricSwitch.performClick()
        assertEquals(BIOMETRICS, method)
        assertEquals("pin-backup", hash)
        assertTrue("no lock to satisfy", proofs.isEmpty())
        assertEquals(1, biometricPrompts)
    }

    @Test
    fun `with no lock on, a new PIN asks no proof`() {
        currentProven = false
        switches()
        pinSwitch.performClick()
        assertEquals(PIN, method)
        assertEquals("pin-new", hash)
        assertTrue("no lock to satisfy", proofs.isEmpty())
    }

    @Test
    fun `turning a lock off removes it and its PIN`() {
        method = PIN
        hash = "pin-0"
        switches()
        pinSwitch.performClick()
        assertEquals(NONE, method)
        assertNull(hash)

        method = BIOMETRICS
        hash = "pin-0"
        switches()
        biometricSwitch.performClick()
        assertEquals(NONE, method)
        assertNull(hash)
    }

    @Test
    fun `showing the lock runs neither switch - nothing changes`() {
        method = BIOMETRICS
        hash = "pin-0"
        val shown = switches()
        shown.show()
        method = PIN
        shown.show()
        assertEquals(PIN, method)
        assertEquals("pin-0", hash)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `Settings changes the lock only through these switches, and the unlock never opens unchecked for want of a PIN`() {
        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        assertFalse(settings.contains("switchPinLock.isChecked ="))
        assertFalse(settings.contains("switchBiometric.isChecked ="))
        assertEquals("only the lock's own set()", 1, Regex("""clearAppLockPinHash\(\)""").findAll(settings).count())

        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val biometric = main.substringAfter("private fun showBiometricPrompt()").substringBefore("\n    private fun ")
        // Biometrics unavailable, or their key invalidated, and no PIN: the screen lock, not an open app.
        assertEquals(2, Regex("""\} else \{\s*showDeviceCredentialPrompt\(\)""").findAll(biometric).count())
        assertFalse(Regex("""\} else \{\s*onUnlockSuccess\(\)""").containsMatchIn(biometric))
        val credential = main.substringAfter("private fun showDeviceCredentialPrompt()").substringBefore("\n    private fun showPinPrompt()")
        assertTrue(credential.contains("BiometricManager.Authenticators.DEVICE_CREDENTIAL"))
        assertTrue("opens only with nothing to ask for", credential.contains("isDeviceSecure != true"))
    }
}
