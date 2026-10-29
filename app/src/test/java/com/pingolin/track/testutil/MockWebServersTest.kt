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

package com.pingolin.track.testutil

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MockWebServersTest {

    /**
     * The collision, forced: something else holds [::1]:P (as a running
     * emulator's listeners can), and the server takes 127.0.0.1:P. A server
     * started with plain start() loses this request to the other listener,
     * because OkHttp tries ::1 first; one started with [startOnIpv4Loopback] must
     * still get it.
     */
    @Test
    fun `a listener on the same port over IPv6 cannot take the request`() {
        val decoy = runCatching {
            ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("::1"), 0)) }
        }.getOrNull()
        assumeTrue("needs an IPv6 loopback to stand in for the emulators' listeners", decoy != null)
        decoy!!
        thread(isDaemon = true) {
            while (!decoy.isClosed) runCatching {
                decoy.accept().use { s ->
                    s.getOutputStream().write("Android Console: Authentication required\r\nOK\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
        val server = MockWebServer()
        try {
            server.startOnIpv4Loopback(decoy.localPort)
            server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))
            val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

            val response = client.newCall(Request.Builder().url(server.url("/probe")).build()).execute()

            assertEquals("127.0.0.1", server.url("/").host)
            assertEquals(200, response.code)
            assertEquals("OK", response.body?.string())
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            decoy.close()
        }
    }
}
