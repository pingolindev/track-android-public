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

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Secure PIN hashing using PBKDF2-HMAC-SHA256.
 *
 * Security considerations:
 * - Uses random 16-byte salt per PIN to prevent rainbow table attacks
 * - 210,000 iterations per OWASP 2023 recommendations
 * - Stores salt with hash in format: salt$hash (both hex-encoded)
 * - No legacy hash support - all PINs must use PBKDF2 format
 */
object PinHasher {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DELIMITER = "$"

    /**
     * Hash a PIN with a random salt using PBKDF2.
     * Returns a string in format: salt$hash (both hex-encoded).
     * The caller retains ownership of [pin] and is responsible for wiping it.
     */
    fun hashPin(pin: CharArray): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)

        val hash = deriveKey(pin, salt)

        return salt.toHex() + DELIMITER + hash.toHex()
    }

    /**
     * Verifies a PIN, accepting ONLY the PBKDF2 `salt$hash` format. A bare SHA-256 hash is
     * rejected rather than verified, so a user carrying one must re-set their PIN.
     *
     * The caller retains ownership of [pin] and is responsible for wiping it.
     */
    fun verifyPin(pin: CharArray, storedHash: String): Boolean {
        // No delimiter means the legacy unsalted format: refuse it.
        if (!storedHash.contains(DELIMITER)) {
            return false
        }

        val parts = storedHash.split(DELIMITER)
        if (parts.size != 2) return false

        val salt = try {
            parts[0].hexToBytes()
        } catch (e: Exception) {
            return false
        }

        val expectedHash = try {
            parts[1].hexToBytes()
        } catch (e: Exception) {
            return false
        }

        val computedHash = deriveKey(pin, salt)

        // Constant-time comparison to prevent timing attacks
        return constantTimeEquals(computedHash, expectedHash)
    }

    /**
     * Check if a stored hash uses the legacy format.
     * Used to prompt users to re-set their PIN.
     */
    fun isLegacyHash(storedHash: String): Boolean {
        return !storedHash.contains(DELIMITER)
    }

    private fun deriveKey(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM)
            return factory.generateSecret(spec).encoded
        } finally {
            // Wipe the internal CharArray copy PBEKeySpec makes.
            spec.clearPassword()
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
