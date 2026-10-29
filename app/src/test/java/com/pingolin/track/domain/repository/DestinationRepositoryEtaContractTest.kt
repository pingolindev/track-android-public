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

package com.pingolin.track.domain.repository

import org.junit.Assert.*
import org.junit.Test

/**
 * Locks the cross-platform wire contract for the destination "eta" field.
 *
 * The precise ETA is E2EE and travels as the plaintext of the "eta" AAD field.
 * iOS reads it back with `Int64(plaintext)`, so DestinationRepository must
 * encode epoch-millis as a plain base-10 integer string: an ISO-8601 string
 * fails that parse and the ETA silently vanishes, and this test fails.
 */
class DestinationRepositoryEtaContractTest {

    @Test
    fun `eta field encodes epoch-millis as a bare decimal integer, Int64-parseable`() {
        val samples = listOf(0L, 1L, 1_727_700_000_000L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)
        for (ms in samples) {
            val encoded = DestinationRepository.encodeEtaFieldPlaintext(ms)
            // Must round-trip exactly through integer parsing — mirrors iOS Int64(plaintext).
            assertEquals("eta plaintext must round-trip via Long parse", ms, encoded.toLong())
            // Must be a bare base-10 integer: no ISO-8601, no locale grouping/spaces/decimals.
            assertTrue(
                "eta plaintext must be a bare decimal integer, was: \"$encoded\"",
                encoded.matches(Regex("-?\\d+"))
            )
        }
    }
}
