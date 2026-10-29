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

package com.pingolin.track.domain.sos

import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.util.SosMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The receivers' rules for an SOS, in their order, and the payload a mark binds. */
class SosJudgeTest {

    private val now = 1_800_000_000_000L
    private val fresh = now - 60_000L
    private val old = now - SosMarks.MAX_SOS_ALARM_AGE_MS - 1

    @Test
    fun `ended hides, whatever else holds`() {
        assertEquals(SosVerdict.HIDDEN, SosJudge.verdict(ended = true, current = true, sticky = true, sealedAt = fresh, now = now))
    }

    @Test
    fun `neither current nor sticky is hidden at ANY age - history is never listed again`() {
        assertEquals(SosVerdict.HIDDEN, SosJudge.verdict(ended = false, current = false, sticky = false, sealedAt = fresh, now = now))
        assertEquals(SosVerdict.HIDDEN, SosJudge.verdict(ended = false, current = false, sticky = false, sealedAt = old, now = now))
    }

    @Test
    fun `current or sticky alarms while young, and is listed quietly past the alarm age by its sealed time`() {
        for ((current, sticky) in listOf(true to false, false to true, true to true)) {
            assertEquals(SosVerdict.ALARMING, SosJudge.verdict(false, current, sticky, fresh, now))
            assertEquals(SosVerdict.ALARMING, SosJudge.verdict(false, current, sticky, now - SosMarks.MAX_SOS_ALARM_AGE_MS, now))
            assertEquals(SosVerdict.QUIET, SosJudge.verdict(false, current, sticky, old, now))
        }
    }

    @Test
    fun `no time that opened has no age - never alarming`() {
        assertEquals(SosVerdict.HIDDEN, SosJudge.verdict(ended = false, current = true, sticky = true, sealedAt = null, now = now))
    }

    @Test
    fun `the payload hash - SHA-256 over each field's nonce then ciphertext, length-prefixed, in hex`() {
        // Computed apart from this code (Python's hashlib and struct.pack('>I', len)).
        assertEquals(
            "b2fabf8e6196f5d61e66adba680aab6d27d4cbb9ff445a438fc0a5458116c809",
            SosJudge.payloadHash(EncryptedPayload("n1", "c1"), EncryptedPayload("n2", "c2"), EncryptedPayload("n3", "c3"), EncryptedPayload("n4", "c4")),
        )
        assertEquals(
            "a missing field hashes as empty parts",
            "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925",
            SosJudge.payloadHash(null, null, null, null),
        )
    }

    @Test
    fun `every byte of every field is in the hash, and its place`() {
        val fields = listOf(EncryptedPayload("n1", "c1"), EncryptedPayload("n2", "c2"), EncryptedPayload("n3", "c3"), EncryptedPayload("n4", "c4"))
        val base = SosJudge.payloadHash(*fields.toTypedArray())
        for (i in fields.indices) {
            val nonce = fields.toMutableList().also { it[i] = it[i].copy(nonce = it[i].nonce + "0") }
            val cipher = fields.toMutableList().also { it[i] = it[i].copy(ciphertext = it[i].ciphertext + "0") }
            assertNotEquals("field $i nonce", base, SosJudge.payloadHash(*nonce.toTypedArray()))
            assertNotEquals("field $i ciphertext", base, SosJudge.payloadHash(*cipher.toTypedArray()))
        }
        // Lengths keep a byte from moving between parts unseen.
        assertNotEquals(
            SosJudge.payloadHash(EncryptedPayload("ab", "c")),
            SosJudge.payloadHash(EncryptedPayload("a", "bc")),
        )
        assertNotEquals("the order", base, SosJudge.payloadHash(*fields.reversed().toTypedArray()))
    }
}
