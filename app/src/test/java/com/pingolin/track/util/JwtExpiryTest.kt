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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the background-rotation rule. The refresh token is single-use and
 * rotates on every /auth/refresh; a background task that rotates and is then
 * frozen before the new token reaches disk leaves the next launch holding a
 * consumed token. So a token we cannot read must never be treated as usable —
 * every malformed shape here has to answer "no", not throw and not guess.
 */
class JwtExpiryTest {

    private fun tokenWithPayload(payloadJson: String): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.signature-not-verified-here"
    }

    @Test
    fun `reads exp from a well-formed token`() {
        val token = tokenWithPayload("""{"sub":"u1","exp":1788456713}""")
        assertEquals(1788456713L, JwtExpiry.expiryEpochSeconds(token))
    }

    @Test
    fun `reads a floating point exp`() {
        // Some issuers emit exp as a float; truncating is correct and beats
        // refusing a token that is genuinely valid.
        val token = tokenWithPayload("""{"exp":1788456713.0}""")
        assertEquals(1788456713L, JwtExpiry.expiryEpochSeconds(token))
    }

    @Test
    fun `every unreadable shape is null rather than an exception`() {
        assertNull("null token", JwtExpiry.expiryEpochSeconds(null))
        assertNull("empty token", JwtExpiry.expiryEpochSeconds(""))
        assertNull("blank token", JwtExpiry.expiryEpochSeconds("   "))
        assertNull("not a JWT", JwtExpiry.expiryEpochSeconds("just-a-string"))
        assertNull("two segments", JwtExpiry.expiryEpochSeconds("header.payload"))
        assertNull("four segments", JwtExpiry.expiryEpochSeconds("a.b.c.d"))
        assertNull("empty payload segment", JwtExpiry.expiryEpochSeconds("header..sig"))
        assertNull("payload not base64", JwtExpiry.expiryEpochSeconds("header.!!!not-base64!!!.sig"))
        assertNull("payload not json", JwtExpiry.expiryEpochSeconds(tokenWithPayload("not json at all")))
        assertNull("no exp claim", JwtExpiry.expiryEpochSeconds(tokenWithPayload("""{"sub":"u1"}""")))
        assertNull("non-numeric exp", JwtExpiry.expiryEpochSeconds(tokenWithPayload("""{"exp":"soon"}""")))
    }

    @Test
    fun `padding is handled whatever the segment length`() {
        // base64url drops padding, and the payload length varies with the
        // claims. Each of these exercises a different length modulo 4.
        for (sub in listOf("a", "ab", "abc", "abcd", "abcde")) {
            val token = tokenWithPayload("""{"sub":"$sub","exp":1700000000}""")
            assertEquals("sub=$sub", 1700000000L, JwtExpiry.expiryEpochSeconds(token))
        }
    }

    // ---- the actual decision ----

    @Test
    fun `a token with plenty of life left lets background work proceed`() {
        val nowMs = 1_000_000_000_000L
        val exp = nowMs / 1000 + 3600            // an hour left
        val token = tokenWithPayload("""{"exp":$exp}""")
        assertTrue(JwtExpiry.isValidForAtLeast(token, 300, nowMs))
    }

    @Test
    fun `a token about to expire defers background work`() {
        // This is the case that matters: proceeding would rotate, and a freeze
        // before the new token lands signs the user out.
        val nowMs = 1_000_000_000_000L
        val exp = nowMs / 1000 + 60              // one minute left
        val token = tokenWithPayload("""{"exp":$exp}""")
        assertFalse(JwtExpiry.isValidForAtLeast(token, 300, nowMs))
    }

    @Test
    fun `an already expired token defers background work`() {
        val nowMs = 1_000_000_000_000L
        val token = tokenWithPayload("""{"exp":${nowMs / 1000 - 10}}""")
        assertFalse(JwtExpiry.isValidForAtLeast(token, 300, nowMs))
    }

    @Test
    fun `exactly at the margin still counts as enough`() {
        val nowMs = 1_000_000_000_000L
        val token = tokenWithPayload("""{"exp":${nowMs / 1000 + 300}}""")
        assertTrue(JwtExpiry.isValidForAtLeast(token, 300, nowMs))
    }

    @Test
    fun `a token we cannot read is treated as not usable`() {
        // "Don't know" must behave like "no". Skipping background work is
        // cheap; a bogus sign-out is not.
        val nowMs = 1_000_000_000_000L
        assertFalse(JwtExpiry.isValidForAtLeast(null, 300, nowMs))
        assertFalse(JwtExpiry.isValidForAtLeast("garbage", 300, nowMs))
        assertFalse(JwtExpiry.isValidForAtLeast(tokenWithPayload("""{"sub":"x"}"""), 300, nowMs))
    }
}
