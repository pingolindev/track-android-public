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
 * Reads `exp` out of a JWT. Pure — no Android types, no keychain, no session
 * state — so every malformed shape can be tested directly.
 *
 * The location stream uses it to tell whether the token it is about to present has
 * life left (LocationRepository.streamTokenAction). Background workers refresh rather
 * than put work off "to a foreground pass": a refresh killed half-way does not
 * strand the session.
 *
 * Everything returns null rather than throwing: a token we cannot read is one
 * we must not gamble on.
 */
object JwtExpiry {

    /**
     * Seconds-since-epoch from the token's `exp` claim, or null if the token is
     * absent, malformed, or carries no numeric `exp`.
     */
    fun expiryEpochSeconds(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        // header.payload.signature — anything else is not a JWT we can read.
        if (parts.size != 3) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        return extractExp(payload)
    }

    /**
     * Does the token have at least [marginSeconds] of life left at [nowMs]?
     *
     * False for a missing or unreadable token. "Don't know" must behave like
     * "no": skipping background work is cheap, a spurious sign-out is not.
     */
    fun isValidForAtLeast(token: String?, marginSeconds: Long, nowMs: Long): Boolean {
        val exp = expiryEpochSeconds(token) ?: return false
        val remainingSeconds = exp - (nowMs / 1000L)
        return remainingSeconds >= marginSeconds
    }

    private fun decodeBase64Url(segment: String): String? {
        if (segment.isEmpty()) return null
        return try {
            // JWT uses base64url WITHOUT padding; java.util.Base64's url decoder
            // tolerates its absence, but pad anyway so any decoder behaves.
            val padded = when (segment.length % 4) {
                2 -> "$segment=="
                3 -> "$segment="
                0 -> segment
                else -> return null   // length % 4 == 1 is never valid base64
            }
            String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Pull a numeric `exp` out of the payload without a JSON dependency.
     * Deliberately strict: a non-numeric or absent `exp` is null, not a guess.
     */
    private fun extractExp(payload: String): Long? {
        val match = EXP_PATTERN.find(payload) ?: return null
        val raw = match.groupValues[1]
        // Some issuers emit exp as a float ("exp":1788456713.0).
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
    }

    private val EXP_PATTERN = Regex("\"exp\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
}
