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

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.pingolin_mobile.MobileException

/**
 * The SDK's and the system's text, as iOS's `Error.plainText` treats it: what a
 * person reads about a failure is this app's own words.
 */
class PlainTextTest {

    private val fallback = "Couldn't do that just now."

    @Test
    fun `the SDK's own words never show`() {
        assertEquals(fallback, MobileException.KeyNotFound("Circle key not found: c-1").plainText(fallback))
        assertEquals(fallback, MobileException.SignatureInvalid("wrap does not verify under our own signing key").plainText(fallback))
        assertEquals(UNREACHABLE_TEXT, MobileException.NetworkException("status: Unavailable, message: \"upstream says hi\"").plainText(fallback))
    }

    @Test
    fun `a socket's or a parser's words never show`() {
        assertEquals(UNREACHABLE_TEXT, java.net.UnknownHostException("Unable to resolve host \"api.pingolin.com\"").plainText(fallback))
        assertEquals(UNREACHABLE_TEXT, java.net.SocketTimeoutException("timeout").plainText(fallback))
        // A refused certificate (CompromisedKeyTrust) is a connection that failed, never key talk.
        assertEquals(
            UNREACHABLE_TEXT,
            javax.net.ssl.SSLHandshakeException("A certificate on the path to api-track.pingolindev.com has a key known to be compromised")
                .plainText(fallback),
        )
        assertEquals(fallback, IllegalStateException("Expected BEGIN_OBJECT but was STRING").plainText(fallback))
        assertEquals(fallback, com.google.gson.JsonSyntaxException("malformed").plainText(fallback))
    }

    @Test
    fun `what this app said stays said`() {
        assertEquals("Couldn't load your circles just now.", Exception("Couldn't load your circles just now.").plainText(fallback))
        assertEquals(
            "Not done: this phone is still waiting to sync this circle's settings from another member.",
            com.pingolin.track.domain.repository.KeyNotCurrentException().plainText(fallback),
        )
        assertEquals(fallback, Exception().plainText(fallback))
        assertEquals(fallback, Exception("  ").plainText(fallback))
    }
}
