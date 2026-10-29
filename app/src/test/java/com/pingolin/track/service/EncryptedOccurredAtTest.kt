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

import com.pingolin.track.service.EncryptedOccurredAt.OCCURRED_AT_FIELD_TAG
import com.pingolin.track.service.EncryptedOccurredAt.applyEncryptedOccurredAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Opening the sealed crossing time on the RECEIVING side.
 *
 * This is the last hop of the sealed time: the reporting client seals when the
 * crossing actually happened, the server stores and forwards that blob without
 * being able to read it, and here the recipient opens it and replaces
 * `created_at` — which is what AlertHandler.eventStamp renders into the
 * notification body. Without this the only timestamp reaching a subscriber is
 * the notification's `created_at`, a server processing time, so a crossing
 * reported after an offline stretch would announce itself as if it had just
 * happened.
 *
 * The decrypt is INJECTED because unit tests cannot load the UniFFI native
 * library. That splits the coverage deliberately:
 *   - the cipher, under a known circle key, is pinned in the mobile SDK by
 *     `occurred_at_seal_round_trips_and_is_bound_to_its_field_tag`, which also
 *     proves the "occurred_at" tag is AAD-bound and a mismatch FAILS;
 *   - the plumbing — field names, epoch-millis parse, the ISO-8601 form
 *     `eventStamp` parses back, and the leave-it-alone-on-failure rule — is
 *     pinned here.
 *
 * Every failure mode has to leave `created_at` untouched rather than throw or
 * blank it: the stamp is best-effort, and a missing stamp must never cost the
 * user the notification.
 */
class EncryptedOccurredAtTest {

    /** A crossing at 2026-07-30T08:05:45.123Z, the shape clients actually send. */
    private val crossingMs = 1785398745123L
    private val crossingIso = "2026-07-30T08:05:45.123Z"

    private fun metadata(
        circleId: String? = "11111111-1111-1111-1111-111111111111",
        ciphertext: String? = "b33a3bd2c0360599a8ce004993ed4986",
        nonce: String? = "090e02a2afeae1fe8100b25038eee3d4",
        createdAt: String? = "2026-07-30T08:12:00Z",
    ): MutableMap<String, String> = mutableMapOf<String, String>().apply {
        circleId?.let { put("circle_id", it) }
        ciphertext?.let { put("encrypted_occurred_at", it) }
        nonce?.let { put("occurred_at_nonce", it) }
        createdAt?.let { put("created_at", it) }
    }

    /**
     * The headline case: the decrypted crossing time REPLACES the server's row
     * timestamp, and lands in the exact ISO-8601 form eventStamp parses with
     * ServerTime.epochMs.
     */
    @Test
    fun `decrypted crossing time replaces the server stamp`() {
        val data = metadata()
        applyEncryptedOccurredAt(data) { _, _, _ -> crossingMs.toString() }

        assertEquals(crossingIso, data["created_at"])
        assertEquals(
            "must round-trip through the same parse eventStamp uses",
            crossingMs,
            com.pingolin.track.util.ServerTime.epochMs(data["created_at"]),
        )
    }

    /** The decrypt must be handed the circle and the blob from the metadata. */
    @Test
    fun `passes the circle id, nonce and ciphertext through`() {
        var seen: Triple<String, String, String>? = null
        applyEncryptedOccurredAt(metadata()) { c, n, ct -> seen = Triple(c, n, ct); "1" }

        assertEquals(
            Triple(
                "11111111-1111-1111-1111-111111111111",
                "090e02a2afeae1fe8100b25038eee3d4",
                "b33a3bd2c0360599a8ce004993ed4986",
            ),
            seen,
        )
    }

    /** The tag every sender seals with — a drift here fails silently. */
    @Test
    fun `field tag is the one the senders use`() {
        assertEquals("occurred_at", OCCURRED_AT_FIELD_TAG)
    }

    // ===== every failure mode must leave the server stamp intact =====

    @Test
    fun `a failed decrypt keeps the server stamp`() {
        val data = metadata()
        applyEncryptedOccurredAt(data) { _, _, _ -> null }
        assertEquals("2026-07-30T08:12:00Z", data["created_at"])
    }

    @Test
    fun `a throwing decrypt keeps the server stamp instead of propagating`() {
        val data = metadata()
        applyEncryptedOccurredAt(data) { _, _, _ -> throw IllegalStateException("no circle key") }
        assertEquals("2026-07-30T08:12:00Z", data["created_at"])
    }

    /** A client that predates the field sends neither half. */
    @Test
    fun `no blob leaves the server stamp alone`() {
        val data = metadata(ciphertext = null, nonce = null)
        applyEncryptedOccurredAt(data) { _, _, _ -> error("must not be called") }
        assertEquals("2026-07-30T08:12:00Z", data["created_at"])
    }

    @Test
    fun `half a blob is ignored`() {
        for (data in listOf(metadata(nonce = null), metadata(ciphertext = null))) {
            applyEncryptedOccurredAt(data) { _, _, _ -> error("must not be called") }
            assertEquals("2026-07-30T08:12:00Z", data["created_at"])
        }
    }

    /** Blank is not the same as absent, and must be treated as absent. */
    @Test
    fun `blank fields are treated as absent`() {
        val data = metadata(circleId = "", ciphertext = "  ".trim(), nonce = "")
        applyEncryptedOccurredAt(data) { _, _, _ -> error("must not be called") }
        assertEquals("2026-07-30T08:12:00Z", data["created_at"])
    }

    /** A notification with no circle can't be decrypted against any key. */
    @Test
    fun `missing circle id short-circuits`() {
        val data = metadata(circleId = null)
        applyEncryptedOccurredAt(data) { _, _, _ -> error("must not be called") }
        assertEquals("2026-07-30T08:12:00Z", data["created_at"])
    }

    /**
     * Garbage plaintext must not become a timestamp. A wrong-key decrypt that
     * somehow returned bytes would otherwise stamp the notification with an
     * absurd date rather than falling back.
     */
    @Test
    fun `non numeric plaintext is rejected`() {
        for (junk in listOf("", "not-a-time", "12.5", "1785398745123x", "0x1", "-")) {
            val data = metadata()
            applyEncryptedOccurredAt(data) { _, _, _ -> junk }
            assertEquals("rejected: '$junk'", "2026-07-30T08:12:00Z", data["created_at"])
        }
    }

    /** Surrounding whitespace is tolerated — it is still a valid time. */
    @Test
    fun `surrounding whitespace is tolerated`() {
        val data = metadata()
        applyEncryptedOccurredAt(data) { _, _, _ -> "  $crossingMs\n" }
        assertEquals(crossingIso, data["created_at"])
    }

    /**
     * When the row carried no created_at at all, a decrypted time still gets
     * set — that is the case where the stamp matters most, since eventStamp
     * would otherwise fall back to "now".
     */
    @Test
    fun `sets created_at even when the row had none`() {
        val data = metadata(createdAt = null)
        assertNull(data["created_at"])
        applyEncryptedOccurredAt(data) { _, _, _ -> crossingMs.toString() }
        assertEquals(crossingIso, data["created_at"])
    }

    /**
     * The point of the whole feature, stated as a test: a crossing reported
     * while offline must render as when it HAPPENED, not when the row was
     * written. 3h07m apart here.
     */
    @Test
    fun `a late-reported crossing shows the old time, not the row time`() {
        val data = metadata(createdAt = "2026-07-30T11:12:00Z")
        applyEncryptedOccurredAt(data) { _, _, _ -> crossingMs.toString() }

        val shown = java.time.Instant.parse(data["created_at"])
        val rowWritten = java.time.Instant.parse("2026-07-30T11:12:00Z")
        assertEquals(crossingIso, shown.toString())
        assertEquals(
            "the stamp must be the crossing, hours before the row was written",
            11174877L,
            rowWritten.toEpochMilli() - shown.toEpochMilli(),
        )
    }
}
