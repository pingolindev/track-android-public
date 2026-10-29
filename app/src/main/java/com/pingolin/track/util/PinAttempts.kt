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

/**
 * The App Lock PIN's tries, one count for everything that checks it: the unlock, and Settings before
 * it turns the lock off or replaces it. Five wrong in a row lock it for 30 seconds,
 * wherever they were typed, so no second dialog is an unlimited guess at a 4-digit PIN.
 */
object PinAttempts {

    const val MAX = 5
    const val LOCKOUT_MS = 30_000L

    sealed class Outcome {
        object Right : Outcome()
        data class Wrong(val remaining: Int) : Outcome()
        /** That was the fifth wrong one: locked for [LOCKOUT_MS] from now. */
        object LockedNow : Outcome()
    }

    /** Seconds left of a lockout, or null when there is none; an ended one is cleared. */
    fun lockedFor(session: SessionManager, now: Long = System.currentTimeMillis()): Long? {
        val until = session.getPinLockoutUntil()
        if (session.getPinFailedAttempts() < MAX) return null
        if (now < until) return ((until - now) / 1000).coerceAtLeast(1)
        session.resetPinLockout()
        return null
    }

    /** [entered] against the PIN kept, counted. [entered] is the caller's to wipe. */
    fun check(session: SessionManager, entered: CharArray, now: Long = System.currentTimeMillis()): Outcome {
        val stored = session.getAppLockPinHash()
        // PinHasher accepts only the PBKDF2 salt$hash format.
        if (stored != null && PinHasher.verifyPin(entered, stored)) {
            session.resetPinLockout()
            return Outcome.Right
        }
        val failed = session.getPinFailedAttempts() + 1
        session.setPinFailedAttempts(failed)
        if (failed >= MAX) {
            session.setPinLockoutUntil(now + LOCKOUT_MS)
            return Outcome.LockedNow
        }
        return Outcome.Wrong(MAX - failed)
    }

    /** The unlock's own words for a refused try. */
    fun refusal(outcome: Outcome): String? = when (outcome) {
        Outcome.Right -> null
        is Outcome.Wrong -> "Incorrect PIN. ${outcome.remaining} attempt${if (outcome.remaining != 1) "s" else ""} remaining."
        Outcome.LockedNow -> "Too many failed attempts. Locked for 30 seconds."
    }

    /** The unlock's words while a lockout lasts. */
    fun lockedOut(seconds: Long): String = "Too many failed attempts. Try again in $seconds seconds."
}
