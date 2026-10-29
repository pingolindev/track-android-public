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

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides AES-256-GCM authenticated encryption keyed from the Android Keystore.
 *
 * Guarantees:
 * - The key lives in the Keystore and is hardware-backed (TEE/StrongBox) where the device offers it,
 *   so it is never exposed to app memory.
 * - Every encryption uses a fresh IV/nonce, enforced by `setRandomizedEncryptionRequired(true)`.
 * - The 128-bit GCM authentication tag makes tampering detectable.
 */
@Singleton
class KeystoreEncryption @Inject constructor() {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }

    /**
     * Encrypts a UTF-8 string with AES-GCM under the hardware-backed key,
     * returning Base64 of `[IV (12 bytes)][ciphertext + auth tag]`.
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts data produced by [encrypt].
     *
     * @throws Exception if decryption fails — a GCM tag mismatch means wrong key or tampered data
     * @throws IllegalArgumentException if the input is too short to contain an IV and a tag
     */
    fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)

        // Bounds-check before slicing: IV (12) + GCM auth tag (16) = 28 bytes minimum.
        if (combined.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException(
                "Encrypted data too short: ${combined.size} bytes (minimum ${GCM_IV_LENGTH + 16})"
            )
        }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    data class EncryptionResult(
        val nonce: String,      // Base64-encoded IV/nonce
        val ciphertext: String  // Base64-encoded ciphertext (includes auth tag)
    )

    /**
     * Encrypts with an extra password-derived layer on top of AES-GCM, so that
     * compromising the Keystore alone is not enough to recover the plaintext.
     *
     * Output is Base64 of `[PBKDF2 salt (16)][IV (12)][ciphertext + auth tag]`.
     *
     * @param password taken as a CharArray so it can be zeroed; this method does NOT clear it —
     *   the caller owns that
     * @param salt user-scoped context (e.g. email); a random per-message salt is mixed in on top
     */
    fun encryptWithPassword(plaintext: String, password: CharArray, salt: String): String {
        val pbkdfSalt = ByteArray(PBKDF2_SALT_LENGTH)
        SecureRandom().nextBytes(pbkdfSalt)

        // Mixing a fresh random salt into the caller's salt keeps the derived key unique
        // even when the same email/password pair encrypts the same data twice.
        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt

        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

            // The XOR keystream must match the plaintext length exactly: XOR against a short
            // repeating key is trivially breakable, so HKDF stretches the PBKDF2 output to fit.
            val expandedKey = hkdfExpand(passwordKey, plaintextBytes.size)

            val xored = ByteArray(plaintextBytes.size)
            for (i in plaintextBytes.indices) {
                xored[i] = (plaintextBytes[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(xored)

            val combined = ByteArray(PBKDF2_SALT_LENGTH + iv.size + ciphertext.size)
            System.arraycopy(pbkdfSalt, 0, combined, 0, PBKDF2_SALT_LENGTH)
            System.arraycopy(iv, 0, combined, PBKDF2_SALT_LENGTH, iv.size)
            System.arraycopy(ciphertext, 0, combined, PBKDF2_SALT_LENGTH + iv.size, ciphertext.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    /**
     * Legacy overload for String password - prefer CharArray version.
     */
    @Deprecated("Use CharArray version for better security", ReplaceWith("encryptWithPassword(plaintext, password.toCharArray(), salt)"))
    fun encryptWithPassword(plaintext: String, password: String, salt: String): String {
        return encryptWithPassword(plaintext, password.toCharArray(), salt)
    }

    /**
     * As [encryptWithPassword], but splits the result for storage schemas that keep nonce and
     * ciphertext in separate fields. The returned nonce is `[PBKDF2 salt (16)][IV (12)]`.
     *
     * @param password NOT cleared by this method — the caller owns zeroing it
     */
    fun encryptWithPasswordSeparate(plaintext: String, password: CharArray, salt: String): EncryptionResult {
        val pbkdfSalt = ByteArray(PBKDF2_SALT_LENGTH)
        SecureRandom().nextBytes(pbkdfSalt)

        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt

        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)

            val expandedKey = hkdfExpand(passwordKey, plaintextBytes.size)

            val xored = ByteArray(plaintextBytes.size)
            for (i in plaintextBytes.indices) {
                xored[i] = (plaintextBytes[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

            val iv = cipher.iv
            val ciphertext = cipher.doFinal(xored)

            val nonceWithSalt = ByteArray(PBKDF2_SALT_LENGTH + iv.size)
            System.arraycopy(pbkdfSalt, 0, nonceWithSalt, 0, PBKDF2_SALT_LENGTH)
            System.arraycopy(iv, 0, nonceWithSalt, PBKDF2_SALT_LENGTH, iv.size)

            return EncryptionResult(
                nonce = Base64.encodeToString(nonceWithSalt, Base64.NO_WRAP),
                ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            )
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    /**
     * Legacy overload for String password - prefer CharArray version.
     */
    @Deprecated("Use CharArray version for better security", ReplaceWith("encryptWithPasswordSeparate(plaintext, password.toCharArray(), salt)"))
    fun encryptWithPasswordSeparate(plaintext: String, password: String, salt: String): EncryptionResult {
        return encryptWithPasswordSeparate(plaintext, password.toCharArray(), salt)
    }

    /**
     * Decrypts data produced by [encryptWithPasswordSeparate].
     *
     * @param salt must be the same user-scoped value used at encryption time (e.g. email);
     *   the random per-message salt is recovered from [nonce]
     */
    fun decryptWithPasswordSeparate(nonce: String, ciphertext: String, password: CharArray, salt: String): String {
        val nonceWithSalt = Base64.decode(nonce, Base64.NO_WRAP)
        val encryptedData = Base64.decode(ciphertext, Base64.NO_WRAP)

        // Bounds-check before slicing: the ciphertext must at minimum hold a 16-byte GCM tag.
        if (encryptedData.size < 16) {
            throw IllegalArgumentException(
                "Ciphertext too short: ${encryptedData.size} bytes (minimum 16)"
            )
        }
        if (nonceWithSalt.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException(
                "Nonce too short: ${nonceWithSalt.size} bytes (minimum $GCM_IV_LENGTH)"
            )
        }

        // A bare-IV nonce means the record predates the PBKDF2 salt, i.e. it was written under a
        // scheme that XORed against a short repeating key. Refuse it rather than decrypt it: the
        // user re-authenticates and the data is rewritten under the current format.
        if (nonceWithSalt.size != PBKDF2_SALT_LENGTH + GCM_IV_LENGTH) {
            throw LegacyEncryptionException(
                "Legacy encryption format detected (nonce length: ${nonceWithSalt.size}); refused."
            )
        }

        val pbkdfSalt = nonceWithSalt.copyOfRange(0, PBKDF2_SALT_LENGTH)
        val iv = nonceWithSalt.copyOfRange(PBKDF2_SALT_LENGTH, nonceWithSalt.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val xored = cipher.doFinal(encryptedData)

        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt

        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val expandedKey = hkdfExpand(passwordKey, xored.size)

            val plaintext = ByteArray(xored.size)
            for (i in xored.indices) {
                plaintext[i] = (xored[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())

            return String(plaintext, Charsets.UTF_8)
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    /**
     * Legacy overload for String password - prefer CharArray version.
     */
    @Deprecated("Use CharArray version for better security", ReplaceWith("decryptWithPasswordSeparate(nonce, ciphertext, password.toCharArray(), salt)"))
    fun decryptWithPasswordSeparate(nonce: String, ciphertext: String, password: String, salt: String): String {
        return decryptWithPasswordSeparate(nonce, ciphertext, password.toCharArray(), salt)
    }

    /**
     * Decrypts data produced by [encryptWithPassword]. [salt] must be the same user-scoped
     * value used at encryption time; the random per-message salt is recovered from the payload.
     */
    fun decryptWithPassword(encryptedData: String, password: CharArray, salt: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)

        if (combined.size < GCM_IV_LENGTH + 16) {
            throw IllegalArgumentException(
                "Encrypted data too short: ${combined.size} bytes (minimum ${GCM_IV_LENGTH + 16})"
            )
        }

        // Anything shorter than [PBKDF2 salt (16)][IV (12)][auth tag (16)] predates the PBKDF2
        // salt, i.e. it was XORed against a short repeating key. Refuse it rather than decrypt it:
        // the user re-authenticates and the data is rewritten under the current format.
        val minNewFormatSize = PBKDF2_SALT_LENGTH + GCM_IV_LENGTH + 16
        if (combined.size < minNewFormatSize) {
            throw LegacyEncryptionException(
                "Legacy encryption format detected (data size: ${combined.size}); refused."
            )
        }

        val pbkdfSalt = combined.copyOfRange(0, PBKDF2_SALT_LENGTH)
        val iv = combined.copyOfRange(PBKDF2_SALT_LENGTH, PBKDF2_SALT_LENGTH + GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(PBKDF2_SALT_LENGTH + GCM_IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

        val xored = cipher.doFinal(ciphertext)

        val combinedSalt = salt.toByteArray(Charsets.UTF_8) + pbkdfSalt

        val passwordKey = deriveKey(password, combinedSalt)
        try {
            val expandedKey = hkdfExpand(passwordKey, xored.size)

            val plaintext = ByteArray(xored.size)
            for (i in xored.indices) {
                plaintext[i] = (xored[i].toInt() xor expandedKey[i].toInt()).toByte()
            }
            Arrays.fill(expandedKey, 0.toByte())

            return String(plaintext, Charsets.UTF_8)
        } finally {
            Arrays.fill(passwordKey, 0.toByte())
        }
    }

    /**
     * Legacy overload for String password - prefer CharArray version.
     */
    @Deprecated("Use CharArray version for better security", ReplaceWith("decryptWithPassword(encryptedData, password.toCharArray(), salt)"))
    fun decryptWithPassword(encryptedData: String, password: String, salt: String): String {
        return decryptWithPassword(encryptedData, password.toCharArray(), salt)
    }

    /**
     * PBKDF2-HMAC-SHA256 at the OWASP-recommended 210,000 iterations.
     *
     * @param salt must contain random bytes, not just the user-scoped context, or precomputed
     *   tables become viable against the low-entropy input
     */
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            password,
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE_BITS
        )
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * RFC 5869 HKDF-Expand over HMAC-SHA256. The Extract step is skipped because the
     * PBKDF2 output fed in here is already a uniform pseudo-random key.
     */
    private fun hkdfExpand(prk: ByteArray, length: Int): ByteArray {
        val hashLen = 32 // SHA-256 output length
        val n = (length + hashLen - 1) / hashLen // Number of blocks needed

        require(n <= 255) { "HKDF-Expand: requested length too large" }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val okm = ByteArray(length)
        var t = ByteArray(0) // T(0) = empty
        var offset = 0

        for (i in 1..n) {
            // T(i) = HMAC-Hash(PRK, T(i-1) | info | i)
            // We use empty info since PBKDF2 salt already provides context
            mac.reset()
            mac.update(t)
            mac.update(i.toByte())
            t = mac.doFinal()

            val copyLen = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }

        return okm
    }

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pingolin_private_key_encryption"
        private const val BIOMETRIC_KEY_ALIAS = "pingolin_biometric_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_IV_LENGTH = 12 // bytes (96 bits, recommended for GCM)
        private const val PBKDF2_ITERATIONS = 210_000  // OWASP 2023 recommendation for PBKDF2-HMAC-SHA256
        private const val KEY_SIZE_BITS = 256
        private const val PBKDF2_SALT_LENGTH = 16  // bytes (128 bits) for random PBKDF2 salt
    }

    private fun getOrCreateKey(): SecretKey {
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true) // Ensures unique IV per encryption
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * The biometric-bound key. Every use of it requires a fresh biometric auth; without one,
     * key operations throw UserNotAuthenticatedException. The Keystore permanently invalidates
     * this key when biometric enrolment changes (e.g. a new fingerprint is added), so callers
     * must be able to recover from the key simply vanishing.
     */
    private fun getOrCreateBiometricKey(): SecretKey {
        val existingKey = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val keySpec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .apply {
                // `setUserAuthenticationParameters` is API 30
                // and minSdk is 26, so on Android 8.0-10 it
                // throws NoSuchMethodError — which the caller's
                // `catch (e: Exception)` can NOT catch, because
                // NoSuchMethodError is an Error. The app-lock
                // unlock path (MainActivity ->
                // getCipherForBiometricAuth) would crash
                // outright on those releases instead of falling
                // back to the PIN.
                //
                // Both branches mean the same thing:
                // authenticate for EVERY use. A 0 timeout on
                // 30+, and -1 pre-30, which is that API's
                // spelling of per-operation auth and is
                // biometric-only. Any positive value would
                // leave the key usable for that many seconds
                // after one prompt.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(-1)
                }
            }
            .setInvalidatedByBiometricEnrollment(true)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    /**
     * An ENCRYPT_MODE cipher to hand to BiometricPrompt inside a CryptoObject. It is only
     * usable from within the BiometricPrompt success callback — that is what binds the
     * cryptographic operation to the authentication rather than merely sequencing it after.
     */
    fun getCipherForBiometricAuth(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateBiometricKey())
        return cipher
    }

    /**
     * The DECRYPT_MODE counterpart to [getCipherForBiometricAuth], for the [iv] recovered
     * from the stored payload.
     */
    fun getCipherForBiometricDecrypt(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateBiometricKey(), spec)
        return cipher
    }

    /**
     * Encrypts using a cipher taken from BiometricPrompt.AuthenticationResult, returning
     * Base64 of `[IV][ciphertext + auth tag]`.
     */
    fun encryptWithBiometric(authenticatedCipher: Cipher, plaintext: String): String {
        val iv = authenticatedCipher.iv
        val ciphertext = authenticatedCipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts using a cipher taken from BiometricPrompt.AuthenticationResult.
     *
     * @param encryptedData raw ciphertext bytes with the leading IV already stripped — the IV
     *   was consumed when the cipher was initialised, so passing it again fails the GCM tag
     */
    fun decryptWithBiometric(authenticatedCipher: Cipher, encryptedData: ByteArray): String {
        val plaintext = authenticatedCipher.doFinal(encryptedData)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Call on logout, or when biometric enrolment changes and the key is already dead.
     */
    fun deleteBiometricKey() {
        if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        }
    }

    /**
     * Call on logout: anything still encrypted under this key becomes permanently unreadable.
     */
    fun deleteKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    fun deleteAllKeys() {
        deleteKey()
        deleteBiometricKey()
    }
}

/**
 * Signals a stored payload whose layout predates the PBKDF2 salt, i.e. one written under a
 * scheme that XORed against a short repeating key and is therefore open to cryptanalysis.
 *
 * Such payloads are deliberately not decryptable: refusing them forces the user to
 * re-authenticate, which rewrites the data under the current HKDF-based format. Callers
 * should treat this as "prompt for login", not as a decryption error.
 */
class LegacyEncryptionException(message: String) : SecurityException(message)
