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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Legacy" is decided from the opened keys, never from the server's record. */
class SigningKeyCheckTest {

    private fun bytes(fill: Int, n: Int = 32) = ByteArray(n) { fill.toByte() }
    private val sharing = bytes(7)
    private val seed = bytes(9)
    private val derived = "03".repeat(32)

    @Test
    fun `a seed that IS the sharing key is legacy, whatever the server reports`() {
        for (server in listOf(derived, "04".repeat(32), null)) {
            assertEquals(SigningKeyCheck.Verdict.LEGACY, SigningKeyCheck.verdict(sharing.copyOf(), sharing, derived, server))
        }
        // Held as libsodium's 64-byte secret: the first 32 bytes are the seed.
        val secret64 = sharing + bytes(3)
        assertEquals(SigningKeyCheck.Verdict.LEGACY, SigningKeyCheck.verdict(secret64, sharing, derived, derived))
    }

    @Test
    fun `a real key is never rotated because the server reports another`() {
        assertEquals(SigningKeyCheck.Verdict.SERVER_DISAGREES, SigningKeyCheck.verdict(seed, sharing, derived, "04".repeat(32)))
        assertEquals(SigningKeyCheck.Verdict.SERVER_DISAGREES, SigningKeyCheck.verdict(seed, sharing, derived, "03".repeat(16)))
    }

    @Test
    fun `a real key the server agrees with, or says nothing about, needs nothing`() {
        assertEquals(SigningKeyCheck.Verdict.MATCHES, SigningKeyCheck.verdict(seed, sharing, derived, derived.uppercase()))
        assertEquals(SigningKeyCheck.Verdict.MATCHES, SigningKeyCheck.verdict(seed, sharing, derived, null))
        assertEquals(SigningKeyCheck.Verdict.MATCHES, SigningKeyCheck.verdict(seed, sharing, null, derived))
    }

    @Test
    fun `keys that did not decode are never legacy`() {
        assertEquals(SigningKeyCheck.Verdict.MATCHES, SigningKeyCheck.verdict(ByteArray(0), ByteArray(0), derived, derived))
        val last = bytes(7).also { it[31] = 8 }
        assertEquals(SigningKeyCheck.Verdict.MATCHES, SigningKeyCheck.verdict(last, sharing, derived, derived))
        assertArrayEquals(seed, SigningKeyCheck.seedOf(seed))
        assertArrayEquals(sharing, SigningKeyCheck.seedOf(sharing + bytes(1)))
    }
}
