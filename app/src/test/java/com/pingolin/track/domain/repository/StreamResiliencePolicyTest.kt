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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two decisions the ingest stream's send path makes on its own:
 *
 *  - The reconnect backoff only restarts for a stream that LASTED. Reset the
 *    moment the SDK's start call returns — before it has even tried to connect
 *    — a stream that cannot connect would be retried about once a second,
 *    forever.
 *  - Before a send, the stream's token is checked against its own `exp`, so a
 *    send that raced the refresh does not go out with a dead token and take
 *    the burst down with it.
 */
class StreamResiliencePolicyTest {

    // --- the reconnect backoff ---

    @Test
    fun `a stream that failed quickly keeps climbing the backoff`() {
        val attempts = LocationRepository.reconnectAttemptsAfterFailure(
            attempts = 4, startedAtMs = 10_000L, nowMs = 10_000L + 5_000L
        )
        assertEquals(4, attempts)
    }

    @Test
    fun `a stream that never came up keeps climbing the backoff`() {
        assertEquals(3, LocationRepository.reconnectAttemptsAfterFailure(3, startedAtMs = 0L, nowMs = 99_999L))
    }

    @Test
    fun `a stream that lasted earns a fresh backoff`() {
        val attempts = LocationRepository.reconnectAttemptsAfterFailure(
            attempts = 7,
            startedAtMs = 10_000L,
            nowMs = 10_000L + LocationRepository.STREAM_STABLE_AFTER_MS
        )
        assertEquals(0, attempts)
    }

    // --- the token's own expiry ---

    private val now = 1_800_000_000_000L

    private fun jwt(expSeconds: Long): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"u1","exp":$expSeconds}""".toByteArray())
        return "$header.$payload.sig"
    }

    private val live = jwt(now / 1000 + 900)
    private val dying = jwt(now / 1000 + 20)
    private val alsoLive = jwt(now / 1000 + 86_400)

    @Test
    fun `a live stream token needs nothing`() {
        assertEquals(StreamTokenAction.NONE, LocationRepository.streamTokenAction(live, live, now))
    }

    @Test
    fun `a dying stream token adopts a fresher stored one`() {
        assertEquals(StreamTokenAction.PUSH_STORED, LocationRepository.streamTokenAction(dying, alsoLive, now))
    }

    @Test
    fun `a dying stream token with nothing fresher stored asks for a refresh`() {
        assertEquals(StreamTokenAction.REFRESH, LocationRepository.streamTokenAction(dying, dying, now))
    }

    @Test
    fun `with no stream up the stored token is what the next stream would present`() {
        assertEquals(StreamTokenAction.NONE, LocationRepository.streamTokenAction(null, live, now))
        assertEquals(StreamTokenAction.REFRESH, LocationRepository.streamTokenAction(null, dying, now))
    }

    @Test
    fun `an unreadable token is left to the server`() {
        assertEquals(StreamTokenAction.NONE, LocationRepository.streamTokenAction("not-a-jwt", null, now))
        assertEquals(StreamTokenAction.NONE, LocationRepository.streamTokenAction(null, null, now))
    }
}
