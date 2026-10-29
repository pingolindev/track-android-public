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

import java.security.MessageDigest

/**
 * Is this account's signing key the legacy X25519-as-seed one? Decided from the keys this
 * sign-in has just opened, never from what the server says (as the account portal decides it).
 *
 * Some earlier mobile registrations stored the 32-byte X25519 sharing private key as the
 * signing seed, so its verifying key never matched a real Ed25519 point, and such a key is
 * replaced at sign-in. Decided from the server's `signing_public_key` alone, a server that
 * merely reported another key would force a real rotation at every sign-in, and with it "Is
 * this really {name}?" on every member's app.
 *
 * Still possible for a fully hostile server: serving the sharing key's own blob in the
 * signing slot (both are sealed under the master key) makes a real key look legacy. It
 * gains a rotation, never a key.
 */
object SigningKeyCheck {

    enum class Verdict {
        /** The opened seed IS the sharing private key: replace it. */
        LEGACY,

        /** A real key, and nothing says otherwise. Nothing to do. */
        MATCHES,

        /**
         * A real key the server's record does not match. The record is wrong, not the key:
         * keep using the key that opened, and never rotate on the server's word.
         */
        SERVER_DISAGREES,
    }

    /** The 32-byte seed, whether held as the seed or as libsodium's 64-byte seed‖verifying key. */
    fun seedOf(signingPrivateKey: ByteArray): ByteArray =
        if (signingPrivateKey.size == 64) signingPrivateKey.copyOf(32) else signingPrivateKey

    /**
     * [signingPrivateKey] and [sharingPrivateKey] as opened with the password; the verifying
     * keys as hex — [derivedVerifyingKeyHex] from the opened seed, [serverVerifyingKeyHex]
     * the server's record (null when either is unknown).
     */
    fun verdict(
        signingPrivateKey: ByteArray,
        sharingPrivateKey: ByteArray,
        derivedVerifyingKeyHex: String?,
        serverVerifyingKeyHex: String?,
    ): Verdict {
        val seed = seedOf(signingPrivateKey)
        // Constant time: these are the account's private keys.
        if (seed.size == 32 && sharingPrivateKey.size == 32 && MessageDigest.isEqual(seed, sharingPrivateKey)) {
            return Verdict.LEGACY
        }
        return if (derivedVerifyingKeyHex != null && serverVerifyingKeyHex != null &&
            !derivedVerifyingKeyHex.equals(serverVerifyingKeyHex, ignoreCase = true)
        ) {
            Verdict.SERVER_DISAGREES
        } else {
            Verdict.MATCHES
        }
    }
}
