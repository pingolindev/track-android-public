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

import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress

/**
 * Start this server on 127.0.0.1 and make its [MockWebServer.url] name that
 * literal address, instead of `start()`'s "localhost". Every test in this module
 * that talks to a MockWebServer must start it this way.
 *
 * WHY. `start()` binds 127.0.0.1 only, but `url()` names the host "localhost",
 * which resolves to BOTH 127.0.0.1 and ::1. OkHttp 5 (fast fallback, on by
 * default) orders the addresses IPv6-first, so the client's first connect goes
 * to [::1]:<port> — a port the server never bound. Normally nothing listens
 * there, the connect is refused and the client falls back to 127.0.0.1. But the
 * kernel hands out the SAME port number on 127.0.0.1 while another process holds
 * it on ::1 only, and a machine running Android emulators has such listeners
 * (qemu and netsimd) on odd ephemeral ports. When the numbers collide, the
 * test's request goes to an emulator: an "Unexpected status line: Android
 * Console ..." or a wrong response, with the server's requestCount at 0 — a rare
 * failure that passes on retry. A literal IPv4 host leaves the client one route
 * — the address the server actually listens on.
 */
fun MockWebServer.startOnIpv4Loopback(port: Int = 0) {
    // getByAddress keeps the given host string as hostName (no reverse lookup), and
    // MockWebServer builds url() from exactly that hostName.
    start(InetAddress.getByAddress("127.0.0.1", byteArrayOf(127, 0, 0, 1)), port)
}
