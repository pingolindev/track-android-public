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

import com.pingolin.track.util.CompromisedKeyTrustManager
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * A [CompromisedKeyTrustManager] for tests about something else: the JVM's own trust, its built path
 * taken as the chain sent, and nothing listed. The platform's (X509TrustManagerExtensions) is Android's
 * alone.
 */
fun plainTrust(blocked: Set<String> = emptySet()): CompromisedKeyTrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    val jvm = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    return CompromisedKeyTrustManager(jvm, { chain: Array<X509Certificate>, _, _ -> chain.toList() }, blocked)
}
