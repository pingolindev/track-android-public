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

import android.util.Base64
import org.mindrot.jbcrypt.BCrypt
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

/**
 * SRP (Secure Remote Password) client implementation for zero-knowledge authentication.
 *
 * The password NEVER leaves the device. Instead:
 * 1. Registration: Client computes verifier from password, sends verifier to server
 * 2. Login: Client proves knowledge of password without revealing it
 *
 * Uses 2048-bit group from RFC 5054 with SHA-256.
 *
 * IMPORTANT: This implementation uses simplified formulas that match the Rust `srp` crate:
 * - M1 = H(A | B | K) instead of RFC 2945 formula
 * - K = S (padded) instead of K = H(S)
 *
 * Includes Proton-style bcrypt preprocessing for extra key stretching.
 *
 * SECURITY: This class uses CharArray for password handling to minimize exposure
 * of sensitive data in memory. Always call clear() methods after use.
 */
class SrpClient {

    companion object {
        // 2048-bit group from RFC 5054
        private val N_HEX =
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6" +
            "AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB6" +
            "94B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"
        private val g_HEX = "02"

        private val N = BigInteger(N_HEX, 16)
        private val g = BigInteger(g_HEX, 16)
        private val N_BYTES = hexToBytes(N_HEX)

        private val random = SecureRandom()

        // BCrypt cost factor (matches Rust's DEFAULT_COST = 12)
        private const val BCRYPT_COST = 12

        // Compute k = H(N | PAD(g)) - RFC 5054 compliant
        private val k: BigInteger by lazy {
            val gPadded = padToLength(hexToBytes(g_HEX), N_BYTES.size)
            val kBytes = sha256(N_BYTES + gPadded)
            BigInteger(1, kBytes)
        }

        /**
         * Generate a proper bcrypt salt string using jBCrypt.
         * Returns full salt format: $2a$12$22charsalt
         */
        fun generateBcryptSalt(): String {
            return BCrypt.gensalt(BCRYPT_COST)
        }

        /**
         * Preprocess password with bcrypt using the given salt string.
         * Salt must be in bcrypt format: $2a$12$22charsalt
         * This provides extra key stretching before SRP (Proton-style).
         *
         * SECURITY: Uses CharArray to minimize password exposure in memory.
         */
        fun bcryptPreprocess(password: CharArray, bcryptSalt: String): String {
            val passwordStr = String(password)
            return try {
                BCrypt.hashpw(passwordStr, bcryptSalt)
            } finally {
                clearCharArray(password)
            }
        }

        /**
         * Generates the registration credentials to send to the server. The password itself
         * never leaves the device — only the verifier derived from it does.
         *
         * @param email doubles as the SRP identity
         * @param password CLEARED before this returns; the caller must not reuse the array
         */
        fun generateRegistrationData(email: String, password: CharArray): SrpRegistrationData {
            // Normalize email to lowercase for consistent SRP computation across platforms
            val normalizedEmail = email.lowercase()

            val bcryptSalt = generateBcryptSalt()
            val preprocessedPassword = bcryptPreprocess(password.copyOf(), bcryptSalt)

            clearCharArray(password)

            // Generate random 32-byte SRP salt (matches web implementation)
            val saltBytes = ByteArray(32)
            random.nextBytes(saltBytes)

            // Compute x = H(s | H(I | ':' | P))
            val x = computeX(saltBytes, normalizedEmail, preprocessedPassword)

            // Compute verifier v = g^x mod N
            val verifier = g.modPow(x, N)

            return SrpRegistrationData(
                salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP),
                verifier = Base64.encodeToString(bigIntToBytes(verifier), Base64.NO_WRAP),
                bcryptSalt = bcryptSalt
            )
        }

        /**
         * Create a new SRP client session for login.
         */
        fun createSession(): SrpClientSession {
            return SrpClientSession()
        }

        /**
         * Securely clear a CharArray by overwriting with zeros.
         */
        fun clearCharArray(chars: CharArray) {
            Arrays.fill(chars, '\u0000')
        }

        /**
         * Securely clear a ByteArray by overwriting with zeros.
         */
        fun clearByteArray(bytes: ByteArray) {
            Arrays.fill(bytes, 0.toByte())
        }

        // Internal helper functions

        internal fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        internal fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        }

        internal fun padToLength(bytes: ByteArray, length: Int): ByteArray {
            if (bytes.size >= length) return bytes
            val padded = ByteArray(length)
            System.arraycopy(bytes, 0, padded, length - bytes.size, bytes.size)
            return padded
        }

        internal fun sha256(data: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(data)
        }

        internal fun sha256Concat(vararg arrays: ByteArray): ByteArray {
            val totalLen = arrays.sumOf { it.size }
            val combined = ByteArray(totalLen)
            var offset = 0
            for (arr in arrays) {
                System.arraycopy(arr, 0, combined, offset, arr.size)
                offset += arr.size
            }
            return sha256(combined)
        }

        /**
         * Compute x = H(s | H(I | ':' | P))
         * This matches the Rust srp crate's formula.
         */
        internal fun computeX(salt: ByteArray, identity: String, password: String): BigInteger {
            val identityPassword = "$identity:$password".toByteArray(Charsets.UTF_8)
            val identityHash = sha256(identityPassword)
            val xBytes = sha256Concat(salt, identityHash)
            return BigInteger(1, xBytes)
        }

        /**
         * Compute u = H(PAD(A) | PAD(B))
         */
        internal fun computeU(A: BigInteger, B: BigInteger): BigInteger {
            val aBytes = padToLength(bigIntToBytes(A), N_BYTES.size)
            val bBytes = padToLength(bigIntToBytes(B), N_BYTES.size)
            val uBytes = sha256Concat(aBytes, bBytes)
            return BigInteger(1, uBytes)
        }

        /**
         * Convert BigInteger to byte array, ensuring no leading zero byte for positive numbers
         * that would make the array too long.
         */
        internal fun bigIntToBytes(n: BigInteger): ByteArray {
            var bytes = n.toByteArray()
            // Remove leading zero byte if present (BigInteger adds it for sign)
            if (bytes.size > 1 && bytes[0] == 0.toByte()) {
                bytes = bytes.copyOfRange(1, bytes.size)
            }
            return bytes
        }

        internal fun getN(): BigInteger = N
        internal fun getG(): BigInteger = g
        internal fun getK(): BigInteger = k
        internal fun getNBytes(): ByteArray = N_BYTES.copyOf()
    }
}

data class SrpRegistrationData(
    /** Base64-encoded salt */
    val salt: String,
    /** Base64-encoded verifier */
    val verifier: String,
    /** bcrypt salt for password preprocessing */
    val bcryptSalt: String
)

/**
 * SRP client session for the authentication handshake.
 *
 * SECURITY: Uses CharArray for password handling to minimize memory exposure.
 */
class SrpClientSession {
    private val random = SecureRandom()

    // Client private ephemeral 'a' (random 32 bytes)
    private lateinit var privateKey: BigInteger
    // Client public ephemeral A = g^a mod N
    private lateinit var publicKey: BigInteger
    // Shared session key K
    private lateinit var sessionKey: ByteArray
    // Client proof M1
    private lateinit var clientProofM1: ByteArray

    private var email: String = ""
    // SECURITY: Store password as CharArray to allow secure clearing from memory
    // String objects are immutable and may persist in memory until GC
    private var passwordForSrp: CharArray? = null

    /**
     * Step 1: initialise with the user's credentials. Must precede [step2].
     *
     * @param password CLEARED before this returns. The bcrypt-preprocessed form is held as
     *   a CharArray until [step2] consumes and clears it, so an abandoned session leaves
     *   derived key material in memory — call [step2] or drop the session promptly.
     * @param bcryptSalt when present, stretches the password before SRP sees it
     */
    fun step1(email: String, password: CharArray, bcryptSalt: String? = null) {
        // Normalize email to lowercase for consistent SRP computation across platforms
        this.email = email.lowercase()

        // Preprocess password with bcrypt if salt is provided
        // Store as CharArray to allow secure clearing after use in step2
        this.passwordForSrp = if (bcryptSalt != null) {
            SrpClient.bcryptPreprocess(password.copyOf(), bcryptSalt).toCharArray()
        } else {
            password.copyOf()
        }

        // Clear the original password CharArray
        SrpClient.clearCharArray(password)

        // Generate random 32-byte private ephemeral 'a'
        val aBytes = ByteArray(32)
        random.nextBytes(aBytes)
        privateKey = BigInteger(1, aBytes)

        // Compute A = g^a mod N
        publicKey = SrpClient.getG().modPow(privateKey, SrpClient.getN())
    }

    /**
     * Step 2: process the server's challenge (salt and B), returning A and the client
     * proof M1. Clears the stored password as soon as x is computed, including on the
     * invalid-B error path.
     *
     * @throws IllegalStateException if [step1] was not called first
     */
    fun step2(saltBase64: String, serverPublicBase64: String): SrpStep2Result {
        val passwordChars = this.passwordForSrp
            ?: throw IllegalStateException("step1 must be called before step2")

        val salt = Base64.decode(saltBase64, Base64.DEFAULT)
        val serverPublicBytes = Base64.decode(serverPublicBase64, Base64.DEFAULT)
        val B = BigInteger(1, serverPublicBytes)

        val N = SrpClient.getN()
        val g = SrpClient.getG()
        val k = SrpClient.getK()
        val nBytes = SrpClient.getNBytes()

        // Verify B % N > 0
        if (B.mod(N) == BigInteger.ZERO) {
            // Clear password before throwing
            SrpClient.clearCharArray(passwordChars)
            this.passwordForSrp = null
            throw IllegalArgumentException("Invalid server public ephemeral")
        }

        // Compute u = H(PAD(A) | PAD(B))
        val u = SrpClient.computeU(publicKey, B)

        // Compute x = H(s | H(I | ':' | P))
        // Convert CharArray to String only at point of use, then immediately clear
        val x = SrpClient.computeX(salt, email, String(passwordChars))

        // SECURITY: Clear the password CharArray immediately after use
        SrpClient.clearCharArray(passwordChars)
        this.passwordForSrp = null

        // Compute S = (B - k*g^x)^(a + u*x) mod N
        val gx = g.modPow(x, N)
        val kgx = k.multiply(gx).mod(N)
        var base = B.subtract(kgx)
        if (base < BigInteger.ZERO) {
            base = base.add(N)
        }
        val exp = privateKey.add(u.multiply(x)).mod(N.subtract(BigInteger.ONE))
        val S = base.modPow(exp, N)

        // K = S (padded) - standard SRP mode (NOT RFC 5054 which uses K = H(S))
        sessionKey = SrpClient.padToLength(SrpClient.bigIntToBytes(S), nBytes.size)

        // Compute M1 = H(A | B | K) - simplified formula used by Rust srp crate
        val aBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(publicKey), nBytes.size)
        val bBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(B), nBytes.size)
        clientProofM1 = SrpClient.sha256Concat(aBytes, bBytes, sessionKey)

        return SrpStep2Result(
            clientPublic = Base64.encodeToString(SrpClient.bigIntToBytes(publicKey), Base64.NO_WRAP),
            clientProof = Base64.encodeToString(clientProofM1, Base64.NO_WRAP)
        )
    }

    /**
     * Step 3: verify the server's proof M2, confirming it also knows the verifier.
     * Authentication is only mutual once this returns true — a caller that skips it has
     * proved itself to the server but learned nothing about who it is talking to.
     */
    fun step3(serverProofBase64: String): Boolean {
        val serverProof = Base64.decode(serverProofBase64, Base64.DEFAULT)
        val nBytes = SrpClient.getNBytes()

        // Compute expected M2 = H(A | M1 | K)
        val aBytes = SrpClient.padToLength(SrpClient.bigIntToBytes(publicKey), nBytes.size)
        val expectedM2 = SrpClient.sha256Concat(aBytes, clientProofM1, sessionKey)

        return serverProof.contentEquals(expectedM2)
    }

    /**
     * Get the shared session key after successful authentication.
     * This key can be used for additional encryption if needed.
     */
    fun getSessionKey(): ByteArray {
        return sessionKey.copyOf()
    }
}

data class SrpStep2Result(
    /** Base64-encoded client public value A */
    val clientPublic: String,
    /** Base64-encoded client proof M1 */
    val clientProof: String
)
