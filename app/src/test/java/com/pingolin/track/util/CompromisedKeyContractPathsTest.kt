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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The 30 certificate paths every implementation must agree on, on real public certificates: the leaked
 * keys' own certificates by AAA and by emSign, their ECDSA twin, their root, and what must still go
 * through (GTS, Let's Encrypt, Sectigo, and AAA itself where it cross-signs a key that isn't listed).
 *
 * The rule reads keys, never signatures or dates, so each path is handed over as the platform's built
 * path.
 */
class CompromisedKeyContractPathsTest {

    private val dir = "certs/contract"

    private fun bytes(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("$dir/$name")!!.use { it.readBytes() }

    private fun cert(name: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(bytes(name).inputStream()) as X509Certificate

    private class Path(val id: String, val refuse: Boolean, val what: String, val files: List<String>)

    private val paths = listOf(
        Path("R1", true, "AAA cross-sign path (Android 8.0 and later, Apple)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-AAA.pem", "root-AAA_Certificate_Services.pem")),
        Path("R2", true, "emSign path (Android 11 and later, Apple)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-emSign_G3.pem", "parent-emSign_Root_TLS_CA_G3-by-emSign_ECC_Root_CA_G3.pem", "root-emSign_ECC_Root_CA_G3.pem")),
        Path("R3", true, "the ECDSA twin alone: another fingerprint, the same key (a fingerprint list misses it)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-emSign_G3-ecdsa-twin.pem")),
        Path("R4", true, "the emSign G3 hierarchy without a leaked intermediate: the root key alone", listOf("accept-gts-1-leaf-pingolindev.com.pem", "parent-emSign_Root_TLS_CA_G3-by-emSign_ECC_Root_CA_G3.pem", "root-emSign_ECC_Root_CA_G3.pem")),
        Path("R5", true, "a code-signing key over AAA (all 8 keys are listed, CS included)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_CS_CA_G3C-by-AAA.pem", "root-AAA_Certificate_Services.pem")),
        Path("A1", false, "the GTS chain the app's hosts serve, ending at GTS Root R4", listOf("accept-gts-1-leaf-pingolindev.com.pem", "accept-gts-2-WE1.pem", "root-GTS_Root_R4.pem")),
        Path("A2", false, "the same chain over the GlobalSign cross (a device without GTS Root R4)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "accept-gts-2-WE1.pem", "accept-gts-3-GTS_Root_R4-by-GlobalSign_Root_CA.pem", "root-GlobalSign_Root_CA.pem")),
        Path("A3", false, "Let's Encrypt RSA (YR2 <- Root YR cross <- ISRG Root X1)", listOf("accept-le-x1-1-leaf-valid-isrgrootx1.letsencrypt.org.pem", "accept-le-x1-2-YR2.pem", "accept-le-x1-3-Root_YR-by-ISRG_Root_X1.pem", "root-ISRG_Root_X1.pem")),
        Path("A4", false, "Let's Encrypt ECDSA (YE1 <- Root YE cross <- ISRG Root X2)", listOf("accept-le-x2-1-leaf-valid-isrgrootx2.letsencrypt.org.pem", "accept-le-x2-2-YE1.pem", "accept-le-x2-3-Root_YE-by-ISRG_Root_X2.pem", "root-ISRG_Root_X2.pem")),
        Path("A5", false, "Sectigo (sectigo.com as served: OV R36 <- R46 cross <- USERTrust RSA)", listOf("accept-sectigo-1-leaf-sectigo.com.pem", "accept-sectigo-2-Sectigo_Public_Server_Authentication_CA_OV_R36.pem", "accept-sectigo-3-Sectigo_R46-by-USERTrust_RSA.pem", "accept-sectigo-4-USERTrust_RSA_Certification_Authority.pem")),
        Path("A6", false, "AAA in the path, no leaked key: an SSL.com Transit CA cross-signed by AAA (Cloudflare's SSL.com chains)", listOf("accept-gts-1-leaf-pingolindev.com.pem", "accept-SSL.com_TLS_Transit_ECC_CA_R2-by-AAA.pem", "root-AAA_Certificate_Services.pem")),
        Path("A7", false, "AAA in the path, no leaked key: Sectigo over the USERTrust RSA cross by AAA", listOf("accept-sectigo-1-leaf-sectigo.com.pem", "accept-sectigo-2-Sectigo_Public_Server_Authentication_CA_OV_R36.pem", "accept-sectigo-3-Sectigo_R46-by-USERTrust_RSA.pem", "accept-USERTrust_RSA_Certification_Authority-by-AAA.pem", "root-AAA_Certificate_Services.pem")),
        Path("A8", false, "an emSign G1 path: G1 is not on the list", listOf("accept-gts-1-leaf-pingolindev.com.pem", "accept-emSign_SSL_CA_G1-by-emSign_Root_CA_G1.pem", "root-emSign_Root_CA_G1.pem")),
        Path("L1", true, "EM DV TLS CA - G3B by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-AAA.pem")),
        Path("L2", true, "EM DV TLS CA - G3B by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-emSign_G3-ecdsa-twin.pem")),
        Path("L3", true, "EM DV TLS CA - G3B by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3B-by-emSign_G3.pem")),
        Path("L4", true, "EM DV TLS CA - G3C by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3C-by-AAA.pem")),
        Path("L5", true, "EM DV TLS CA - G3C by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_DV_TLS_CA_G3C-by-emSign_G3.pem")),
        Path("L6", true, "EM EV TLS CA - G3B by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_EV_TLS_CA_G3B-by-AAA.pem")),
        Path("L7", true, "EM EV TLS CA - G3B by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_EV_TLS_CA_G3B-by-emSign_G3.pem")),
        Path("L8", true, "EM EV TLS CA - G3C by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_EV_TLS_CA_G3C-by-AAA.pem")),
        Path("L9", true, "EM EV TLS CA - G3C by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_EV_TLS_CA_G3C-by-emSign_G3.pem")),
        Path("L10", true, "EM OV CS CA - G3B by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_CS_CA_G3B-by-AAA.pem")),
        Path("L11", true, "EM OV CS CA - G3B by emSign Root CS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_CS_CA_G3B-by-emSign_G3.pem")),
        Path("L12", true, "EM OV CS CA - G3C by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_CS_CA_G3C-by-AAA.pem")),
        Path("L13", true, "EM OV CS CA - G3C by emSign Root CS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_CS_CA_G3C-by-emSign_G3.pem")),
        Path("L14", true, "EM OV TLS CA - G3B by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_TLS_CA_G3B-by-AAA.pem")),
        Path("L15", true, "EM OV TLS CA - G3B by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_TLS_CA_G3B-by-emSign_G3.pem")),
        Path("L16", true, "EM OV TLS CA - G3C by AAA Certificate Services alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_TLS_CA_G3C-by-AAA.pem")),
        Path("L17", true, "EM OV TLS CA - G3C by emSign Root TLS CA - G3 alone after a leaf", listOf("accept-gts-1-leaf-pingolindev.com.pem", "leaked-EM_OV_TLS_CA_G3C-by-emSign_G3.pem")),
    )

    /** The platform trusts what it's given: the verdict below is the list's alone. */
    private object Trusting : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `the material matches its SHA256SUMS, byte for byte`() {
        val sums = String(bytes("SHA256SUMS")).lines().filter { it.isNotBlank() }.map { it.split(Regex("\\s+")) }
        assertEquals(49, sums.size)
        for ((sum, name) in sums) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) }
            assertEquals(name, sum, actual)
        }
    }

    @Test
    fun `every path gets the contract's verdict`() {
        assertEquals(30, paths.size)
        for (path in paths) {
            val built = path.files.map(::cert)
            val trust = CompromisedKeyTrustManager(Trusting, { _, _, _ -> built }, CompromisedKeys.SPKI_SHA256)
            val sent = arrayOf(built.first())
            val label = "${path.id}: ${path.what}"
            if (path.refuse) {
                assertThrows(label, CertificateException::class.java) { trust.checkServerTrusted(sent, "ECDHE_ECDSA", "pingolindev.com") }
            } else {
                assertEquals(label, built, trust.checkServerTrusted(sent, "ECDHE_ECDSA", "pingolindev.com"))
            }
        }
    }

    /** The key function, over RSA and EC keys alike: each leaked certificate and the root are listed, nothing else. */
    @Test
    fun `the leaked keys' certificates and their root are listed, and no other certificate here is`() {
        val names = String(bytes("SHA256SUMS")).lines().filter { it.isNotBlank() }.map { it.split(Regex("\\s+"))[1] }
        for (name in names) {
            val listed = CompromisedKeys.spkiSha256(cert(name)) in CompromisedKeys.SPKI_SHA256
            val shouldBe = name.startsWith("leaked-") || name == "root-emSign_ECC_Root_CA_G3.pem"
            assertEquals(name, shouldBe, listed)
        }
        assertTrue(names.count { it.startsWith("leaked-") } == 17)
    }
}
