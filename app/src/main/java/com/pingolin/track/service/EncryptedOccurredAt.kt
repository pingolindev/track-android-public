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

package com.pingolin.track.service

import android.util.Log

/**
 * The sealed crossing/arrival time carried on a push payload, opened.
 *
 * WHY IT LIVES HERE AND NOT ON THE FCM SERVICE
 * The FCM service lives in `src/gms`, and a `libre` build has no Firebase and
 * therefore no such class, while this logic has nothing to do with Firebase: it
 * is pure map plumbing over a payload that arrives identically over UnifiedPush.
 * Keeping it in `src/main` is also what lets `EncryptedOccurredAtTest` stay in
 * `src/test` and run under BOTH flavours.
 */
object EncryptedOccurredAt {

    private const val TAG = "EncryptedOccurredAt"

    /**
     * AAD field tag for the sealed crossing/arrival time.
     *
     * MUST match what every sender uses — BoundaryRepository.reportCrossing
     * here, and the iOS app's PlaceManager and DestinationsManager. The tag
     * is AAD-bound (the mobile SDK's `circle_field_write_aad_v2`), so a
     * mismatch does not decrypt to the wrong value, it fails outright — and
     * because this path is best-effort, that failure is SILENT and simply
     * leaves the server-time stamp. Pinned by
     * `occurred_at_seal_round_trips_and_is_bound_to_its_field_tag`.
     */
    const val OCCURRED_AT_FIELD_TAG = "occurred_at"

    /**
     * Replaces `created_at` in [alertData] with the decrypted crossing time,
     * with decryption injected.
     *
     * The injection is what makes the PLUMBING — field names, the epoch-millis
     * parse, the ISO-8601 conversion that `eventStamp` reads back with
     * `ServerTime.epochMs`, and the leave-it-alone-on-failure rule — testable without
     * the UniFFI native library, which unit tests cannot load. The cipher itself
     * is covered in mobile-sdk against a known circle key.
     *
     * @param decrypt (circleId, nonce, ciphertext) -> plaintext, or null.
     */
    @androidx.annotation.VisibleForTesting
    fun applyEncryptedOccurredAt(
        alertData: MutableMap<String, String>,
        decrypt: (circleId: String, nonce: String, ciphertext: String) -> String?,
    ) {
        val circleId = com.pingolin.track.util.circleIdOf(alertData) ?: return
        val ciphertext = alertData["encrypted_occurred_at"]?.takeIf { it.isNotBlank() } ?: return
        val nonce = alertData["occurred_at_nonce"]?.takeIf { it.isNotBlank() } ?: return
        try {
            val plain = decrypt(circleId, nonce, ciphertext) ?: return
            val millis = plain.trim().toLongOrNull() ?: return
            alertData["created_at"] = java.time.Instant.ofEpochMilli(millis).toString()
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt crossing time; keeping the server stamp: ${e.message}")
        }
    }
}
