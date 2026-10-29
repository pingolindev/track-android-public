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

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PIN's tries are one count for the unlock and for Settings' turning the lock off or replacing
 * it, with one lockout: five wrong, wherever they were typed, lock it for 30 seconds. So the turn-off
 * dialog is no unlimited guess at a 4-digit PIN. In the unlock's own words.
 */
class PinAttemptsTest {

    private var failed = 0
    private var lockedUntil = 0L
    private val hash = PinHasher.hashPin("2468".toCharArray())
    private val session = mockk<SessionManager>(relaxed = true) {
        every { getAppLockPinHash() } answers { hash }
        every { getPinFailedAttempts() } answers { failed }
        every { setPinFailedAttempts(any()) } answers { failed = firstArg() }
        every { getPinLockoutUntil() } answers { lockedUntil }
        every { setPinLockoutUntil(any()) } answers { lockedUntil = firstArg() }
        every { resetPinLockout() } answers { failed = 0; lockedUntil = 0L }
    }

    @Test
    fun `four wrong count down, the fifth locks for 30 seconds, and the lock ends by itself`() {
        val now = 1_000_000L
        for (left in listOf(4, 3, 2, 1)) {
            assertEquals(PinAttempts.Outcome.Wrong(left), PinAttempts.check(session, "1111".toCharArray(), now))
        }
        assertEquals(PinAttempts.Outcome.LockedNow, PinAttempts.check(session, "1111".toCharArray(), now))
        assertEquals(30L, PinAttempts.lockedFor(session, now))
        assertEquals(1L, PinAttempts.lockedFor(session, now + 29_999))
        assertNull(PinAttempts.lockedFor(session, now + 30_000))
        assertEquals("the count starts again", 0, failed)
    }

    @Test
    fun `the right PIN clears the count`() {
        PinAttempts.check(session, "1111".toCharArray())
        PinAttempts.check(session, "1111".toCharArray())
        assertEquals(PinAttempts.Outcome.Right, PinAttempts.check(session, "2468".toCharArray()))
        assertEquals(0, failed)
    }

    @Test
    fun `the unlock's own lines`() {
        assertEquals("Incorrect PIN. 4 attempts remaining.", PinAttempts.refusal(PinAttempts.Outcome.Wrong(4)))
        assertEquals("Incorrect PIN. 1 attempt remaining.", PinAttempts.refusal(PinAttempts.Outcome.Wrong(1)))
        assertEquals("Too many failed attempts. Locked for 30 seconds.", PinAttempts.refusal(PinAttempts.Outcome.LockedNow))
        assertEquals("Too many failed attempts. Try again in 12 seconds.", PinAttempts.lockedOut(12))
    }

    @Test
    fun `the unlock and Settings' turn-off count through it, both honouring the lockout`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val unlock = main.substringAfter("private fun showPinPrompt() {").substringBefore("\n    }\n")
        assertTrue("a lockout holds the prompt back", unlock.contains("com.pingolin.track.util.PinAttempts.lockedFor(sessionManager)?.let { remainingSeconds ->"))
        assertTrue(unlock.contains("com.pingolin.track.util.PinAttempts.check(sessionManager, enteredPin)"))
        assertTrue("no count of its own", !unlock.contains("setPinFailedAttempts") && !unlock.contains("PinHasher.verifyPin"))

        val settings = File("src/main/java/com/pingolin/track/ui/settings/SettingsFragment.kt").readText()
        val ask = settings.substringAfter("private fun askCurrentPin(").substringBefore("\n    private fun ")
        assertTrue(
            "a lockout refuses at once",
            ask.contains(
                "com.pingolin.track.util.PinAttempts.lockedFor(sessionManager)?.let { seconds ->\n" +
                    "            context?.let { Toast.makeText(it, com.pingolin.track.util.PinAttempts.lockedOut(seconds), Toast.LENGTH_LONG).show() }\n" +
                    "            onRefused()\n            return\n        }"
            ),
        )
        assertTrue(ask.contains("com.pingolin.track.util.PinAttempts.check(sessionManager, pin)"))
        assertTrue(!ask.contains("PinHasher.verifyPin"))
        val prove = settings.substringAfter("private fun proveCurrentLock(").substringBefore("\n    private fun ")
        assertTrue("the biometric the unlock asks for, never the screen lock", prove.contains("allowDeviceCredential = false,"))
    }
}
