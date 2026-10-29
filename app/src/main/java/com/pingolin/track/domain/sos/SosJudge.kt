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
import java.security.MessageDigest

/** What an SOS is to this device. */
enum class SosVerdict {
    /** Not shown at all: ended, or only an old key opened it and it was never current here. */
    HIDDEN,

    /** Listed, no alarm: genuine, and older than [SosMarks.MAX_SOS_ALARM_AGE_MS] by its sealed time. */
    QUIET,

    /** Genuine and recent: the alarm. */
    ALARMING,
}

/**
 * The receivers' rules for an SOS, in the same order as iOS: ended → hidden; not current AND not
 * sticky → hidden, at ANY age, so history is never listed again; older than
 * [SosMarks.MAX_SOS_ALARM_AGE_MS] by its SEALED time → quiet; else alarming. CURRENT means its
 * raiser id, time and position all opened under the circle's current key; STICKY means this device
 * saw that exact payload current, and its raiser is still one of the members it shows.
 */
object SosJudge {

    fun verdict(ended: Boolean, current: Boolean, sticky: Boolean, sealedAt: Long?, now: Long): SosVerdict = when {
        ended -> SosVerdict.HIDDEN
        !current && !sticky -> SosVerdict.HIDDEN
        // No time that opened: no age to bound, so never alarming.
        sealedAt == null -> SosVerdict.HIDDEN
        now - sealedAt > SosMarks.MAX_SOS_ALARM_AGE_MS -> SosVerdict.QUIET
        else -> SosVerdict.ALARMING
    }

    /**
     * The SOS's payload as served: SHA-256 over each sealed field, nonce then ciphertext, each
     * prefixed with its UTF-8 length (4 bytes, big-endian), in hex. The callers pass the raiser id,
     * position, time and device id in that order, as iOS's payloadHash. A reseal gives new bytes, so a
     * new hash.
     */
    fun payloadHash(vararg sealed: EncryptedPayload?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in sealed) {
            for (part in listOf(field?.nonce.orEmpty(), field?.ciphertext.orEmpty())) {
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
