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

import android.widget.CompoundButton
import com.pingolin.track.R
import com.pingolin.track.util.SessionManager

/**
 * What Settings' two App Lock switches do: a PIN alone, or biometrics with that PIN to fall back
 * on (a new fingerprint, a sensor failure).
 *
 * Only what the person does changes the lock, and a change waits for its proof: a PIN once it is
 * confirmed, biometrics once their prompt succeeds, and turning a lock off, replacing it with a new
 * PIN, or adding biometrics to a PIN lock, once the lock itself is satisfied (otherwise anyone
 * holding the app open could remove the lock for later, or add their own way in: every fingerprint
 * or face on the phone opens a biometric lock). A cancel, a mismatch or a refused prompt leaves the
 * lock as it was. The switches only show the lock: setting them runs neither listener, so turning
 * biometrics on never clears the PIN before the prompt, and cancelling that prompt never leaves the
 * app with no lock at all.
 */
internal class AppLockSwitches(
    private val pinSwitch: CompoundButton,
    private val biometricSwitch: CompoundButton,
    private val lock: Lock,
    /** Asks for a new PIN and its confirmation: its hash, or nothing on a cancel or a mismatch. */
    private val askPin: (onSet: (hash: String) -> Unit, onCancel: () -> Unit) -> Unit,
    /** The same, for the PIN that biometrics need to fall back on. */
    private val askBackupPin: (onSet: (hash: String) -> Unit, onCancel: () -> Unit) -> Unit,
    /** Biometrics' own prompt: proven, or refused (cancelled, failed, unavailable). */
    private val proveBiometrics: (onProven: () -> Unit, onRefused: () -> Unit) -> Unit,
    /** The lock as it is now, satisfied for [Change] as the unlock checks it ([proofFor]), or refused. */
    private val proveCurrent: (change: Change, onProven: () -> Unit, onRefused: () -> Unit) -> Unit,
    private val say: (Int) -> Unit,
    /** After every change shown: the auto-lock row follows whether a lock is on. */
    private val shown: () -> Unit,
) {
    /** What a proof of the lock as it is is asked for. */
    enum class Change { TURN_OFF, REPLACE, ADD_BIOMETRICS, LENGTHEN_AUTO_LOCK }

    /** How the lock as it is is checked. */
    enum class Proof { BIOMETRICS, PIN, SCREEN_LOCK, NOTHING }

    /** The lock itself: [SessionManager]'s method and PIN hash. */
    interface Lock {
        val method: String
        val pinHash: String?
        fun set(method: String, pinHash: String?)
    }

    private var showing = false

    fun bind() {
        show()
        pinSwitch.setOnCheckedChangeListener { _, on ->
            if (showing) return@setOnCheckedChangeListener
            if (on) pinOn() else pinOff()
        }
        biometricSwitch.setOnCheckedChangeListener { _, on ->
            if (showing) return@setOnCheckedChangeListener
            if (on) biometricsOn() else biometricsOff()
        }
    }

    /** The switches set to the lock as it is, with neither listener run. */
    fun show() {
        showing = true
        try {
            pinSwitch.isChecked = lock.method == SessionManager.APP_LOCK_PIN
            biometricSwitch.isChecked = lock.method == SessionManager.APP_LOCK_BIOMETRICS
        } finally {
            showing = false
        }
        shown()
    }

    private fun pinOn() {
        // A PIN of its own, asked for even when biometrics hold one; confirmed, it is the lock. Over a
        // lock already on, only once that lock is satisfied: a new PIN replaces it.
        val ask = {
            askPin({ hash ->
                lock.set(SessionManager.APP_LOCK_PIN, hash)
                say(R.string.app_lock_pin_set)
                show()
            }, ::show)
        }
        if (lock.method == SessionManager.APP_LOCK_NONE) ask() else proveCurrent(Change.REPLACE, ask, ::show)
    }

    private fun pinOff() {
        proveCurrent(Change.TURN_OFF, {
            lock.set(SessionManager.APP_LOCK_NONE, null)
            say(R.string.app_lock_pin_removed)
            show()
        }, ::show)
    }

    private fun biometricsOn() {
        if (lock.pinHash != null) {
            // Over a PIN lock, only once that PIN is given: biometrics let in every
            // fingerprint or face the phone has. With no lock on, the phone's own
            // enrolment is the gate.
            if (lock.method == SessionManager.APP_LOCK_PIN) proveCurrent(Change.ADD_BIOMETRICS, { prove(backup = null) }, ::show) else prove(backup = null)
            return
        }
        // No PIN to fall back on yet: one first. It is kept only once biometrics are proven.
        show()
        askBackupPin({ hash -> prove(backup = hash) }, ::show)
    }

    private fun prove(backup: String?) {
        proveBiometrics({
            lock.set(SessionManager.APP_LOCK_BIOMETRICS, backup ?: lock.pinHash)
            say(R.string.app_lock_biometrics_enabled)
            show()
        }, ::show)
    }

    private fun biometricsOff() {
        proveCurrent(Change.TURN_OFF, {
            lock.set(SessionManager.APP_LOCK_NONE, null)
            say(R.string.app_lock_biometrics_disabled)
            show()
        }, ::show)
    }

    companion object {
        /**
         * The unlock's own order (MainActivity.showAppLockPrompt, showBiometricPrompt and
         * showDeviceCredentialPrompt), so a proof is never weaker than the unlock: a PIN lock asks its PIN;
         * a biometric lock its biometrics where they run with the unlock's key (a fingerprint added since
         * retires it), else its PIN, else the phone's screen lock, else nothing.
         */
        fun proofFor(method: String, hasPin: Boolean, biometricsRun: Boolean, screenLock: Boolean): Proof = when (method) {
            SessionManager.APP_LOCK_PIN -> Proof.PIN
            SessionManager.APP_LOCK_BIOMETRICS -> when {
                biometricsRun -> Proof.BIOMETRICS
                hasPin -> Proof.PIN
                screenLock -> Proof.SCREEN_LOCK
                else -> Proof.NOTHING
            }
            else -> Proof.NOTHING
        }

        /**
         * Whether choosing [chosen] for the auto-lock over [current] (both in ms) asks for the lock first:
         * only a strictly longer wait while a lock is on, which weakens it. A shorter or equal one keeps or
         * strengthens it.
         */
        fun autoLockNeedsProof(current: Long, chosen: Long, lockOn: Boolean): Boolean = lockOn && chosen > current
    }
}
