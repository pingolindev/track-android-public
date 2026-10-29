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

import android.net.http.X509TrustManagerExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Android's own X509TrustManagerExtensions over the wrapper, as OkHttp's chain cleaner makes one over a
 * client's trust manager. It finds the wrapper's methods by reflection, as Android 8 to 15 all do: the
 * host-aware check is required (it throws without it), isSameTrustConfiguration optional. Android 16's
 * stapled check isn't here (Robolectric 4.14.1 has no SDK 36 image): CompromisedKeyTrustTest pins its
 * shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CompromisedKeyTrustOnAndroidTest {

    private fun cert(name: String): X509Certificate =
        javaClass.classLoader!!.getResourceAsStream("certs/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val leaf = cert("pingolindev-leaf.pem")
    private val we1 = cert("gts-we1.pem")
    private val r4 = cert("gts-root-r4.pem")
    private val ours = listOf(leaf, we1, r4)

    /** The platform trusts what it's given: what's decided below is the wrapper's. */
    private object Trusting : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun wrapper(blocked: Set<String>, same: Boolean = true) =
        CompromisedKeyTrustManager(Trusting, { _, _, _ -> ours }, blocked, sameTrust = { _, _ -> same })

    @Test
    fun `Android's X509TrustManagerExtensions takes the wrapper, and its host-aware check is the wrapper's`() {
        val clean = X509TrustManagerExtensions(wrapper(CompromisedKeys.SPKI_SHA256))
        assertEquals(ours, clean.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", "api-track.pingolindev.com"))

        val refusing = X509TrustManagerExtensions(wrapper(setOf(CompromisedKeys.spkiSha256(we1))))
        val refused = assertThrows(CertificateException::class.java) {
            refusing.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", "api-track.pingolindev.com")
        }
        assertTrue(refused.message!!.contains("known to be compromised"))

        // No host, no pass, through the platform's class too.
        assertThrows(CertificateException::class.java) { clean.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", null) }
    }

    @Test
    fun `whether two hosts share trust rules reaches the wrapper's answer`() {
        assertEquals(true, X509TrustManagerExtensions(wrapper(emptySet(), same = true)).isSameTrustConfiguration("a.example", "b.example"))
        assertEquals(false, X509TrustManagerExtensions(wrapper(emptySet(), same = false)).isSameTrustConfiguration("a.example", "b.example"))
    }
}
