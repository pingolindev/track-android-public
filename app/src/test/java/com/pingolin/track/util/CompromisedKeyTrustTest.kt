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

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * No certificate path to a server may hold a [CompromisedKeys] key: the platform decides first, then
 * the path it BUILT is refused if any certificate on it has a listed key, on every client this app
 * makes.
 *
 * Real public certificates only: a chain Pingolin's dev hosts serve (GTS WE1 under GTS Root R4), and
 * the leaked keys' own public certificates from CCADB. No private key is ever handled.
 */
class CompromisedKeyTrustTest {

    private fun cert(name: String): X509Certificate =
        javaClass.classLoader!!.getResourceAsStream("certs/$name")!!.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private val leaf = cert("pingolindev-leaf.pem")
    private val we1 = cert("gts-we1.pem")
    private val r4 = cert("gts-root-r4.pem")
    /** What Pingolin's hosts serve, as the platform builds it: the leaf, WE1, GTS Root
     * R4.
     */
    private val ours = listOf(leaf, we1, r4)

    /** The platform's own decision, for the checks below: it trusts what it's given, or refuses it. */
    private class Platform(val refuse: Boolean = false) : X509ExtendedTrustManager() {
        var asked = 0
        private fun decide() { asked++; if (refuse) throw CertificateException("the platform's own refusal") }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = decide()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = decide()
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = decide()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: java.net.Socket?) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** The trust under test: [platform] decides, the platform's built path is [built], and [blocked] is listed. */
    private fun trust(
        blocked: Set<String>,
        platform: Platform = Platform(),
        built: (Array<X509Certificate>, String?) -> List<X509Certificate> = { _, _ -> ours },
    ) = CompromisedKeyTrustManager(platform, { chain, _, host -> built(chain, host) }, blocked)

    private fun spki(c: X509Certificate) = CompromisedKeys.spkiSha256(c)

    /** A socket mid-handshake with [host], as Conscrypt hands one over; no session when [host] is null. */
    private fun handshakingWith(host: String?): SSLSocket = mockk {
        every { handshakeSession } returns host?.let { h -> mockk<SSLSession> { every { peerHost } returns h } }
    }

    /** The specified values every implementation shares, base64 as specified. */
    private val leakedInSpec = mapOf(
        "EMDVTLSCAG3B" to "6+1kdcLC2A/LGZ8/6N1uazzmC3frFdTkLD3sDG9bUQU=",
        "EMDVTLSCAG3C" to "Sd01PcK5GaN3BkURfQ5gwWEV59G8JZ7v5zSwDqQ0/l8=",
        "EMEVTLSCAG3B" to "4CVMf83CZeLw+KQo2rt1q0rgObSHLWL17lfwh31aikE=",
        "EMEVTLSCAG3C" to "XAZgorFkkQ2P9YnBjhRVLp4Owjfc7+LLc21BpIEfgaA=",
        "EMOVCSCAG3B" to "7E/6GtCsiAgTFLeq/+9qkfl81XKRu1umhsVBX1609rY=",
        "EMOVCSCAG3C" to "ksBj1n6PFNtx0ojhEmb/2vacvxFfe6sU87wvaNJeDjA=",
        "EMOVTLSCAG3B" to "Q7/6GdbvZRRR9oQeAZkx6W9vLq7/KKtLbxzkV23icXs=",
        "EMOVTLSCAG3C" to "Y+zv7kMdSVI7wRNsTnLQIGpHcIbK9gbNXPAlaGwQnPg=",
    )
    private val g3InSpec = "jUF9st2L9eMITR4/GW1YOEnYG91MAMcLnTk2npa4x4I="
    private val aaaInSpec = "vRU+17BDT2iGsXvOi76E7TQMcTLXAqj0+jGPdW7L1vM="

    private fun hex(base64: String) = java.util.Base64.getDecoder().decode(base64).joinToString("") { "%02x".format(it) }

    @Test
    fun `the list is the eight leaked keys and their root, word for word, and never AAA`() {
        assertEquals((leakedInSpec.values + g3InSpec).map(::hex).toSet(), CompromisedKeys.SPKI_SHA256)
        assertTrue("AAA still anchors good Sectigo chains", hex(aaaInSpec) !in CompromisedKeys.SPKI_SHA256)
    }

    /** A certificate carrying [key]: the leaked keys' own certificates aren't needed, only their public keys. */
    private fun carrying(key: java.security.PublicKey): X509Certificate = mockk { every { publicKey } returns key }

    @Test
    fun `a certificate with any of the eight leaked keys, on the built path, is refused`() {
        val trust = trust(CompromisedKeys.SPKI_SHA256)
        for ((name, inSpec) in leakedInSpec) {
            // The real public key, as rustls-webpki 0.103.16 distrusts it (CCADB's), read the way Android reads a certificate's.
            val der = javaClass.classLoader!!.getResourceAsStream("certs/leaked/$name.spki.der")!!.use { it.readBytes() }
            val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(der))
            val leaked = carrying(key)
            assertEquals(name, hex(inSpec), spki(leaked))
            val viaLeaked = trust(CompromisedKeys.SPKI_SHA256) { _, _ -> listOf(leaf, leaked, r4) }
            assertThrows(name, CertificateException::class.java) { viaLeaked.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA", "pingolindev.com") }
        }
        // And what Pingolin's hosts serve still goes through the
        // same list.
        assertEquals(ours, trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", "pingolindev.com"))
    }

    @Test
    fun `their root is refused by its real certificate, and AAA, which cross-signed them, still anchors a path`() {
        val g3 = cert("emsign-ecc-root-ca-g3.pem")
        assertEquals(hex(g3InSpec), spki(g3))
        val viaG3 = trust(CompromisedKeys.SPKI_SHA256) { _, _ -> listOf(leaf, we1, g3) }
        assertThrows(CertificateException::class.java) { viaG3.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA", "pingolindev.com") }

        val aaa = cert("aaa-certificate-services.pem")
        assertEquals(hex(aaaInSpec), spki(aaa))
        val viaAaa = trust(CompromisedKeys.SPKI_SHA256) { _, _ -> listOf(leaf, we1, aaa) }
        assertEquals(listOf(leaf, we1, aaa), viaAaa.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA", "pingolindev.com"))
    }

    @Test
    fun `a chain the app's hosts serve goes through, the platform deciding first`() {
        val platform = Platform()
        val trust = trust(CompromisedKeys.SPKI_SHA256, platform)

        trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", handshakingWith("api-track.pingolindev.com"))

        assertEquals("the platform's own check ran", 1, platform.asked)
        assertEquals(ours, trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", "api-track.pingolindev.com"))
    }

    @Test
    fun `a listed key anywhere on the built path is refused, by key`() {
        for (listed in ours) {
            val trust = trust(setOf(spki(listed)))
            assertThrows(CertificateException::class.java) {
                trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", "api-track.pingolindev.com")
            }
        }
        // By key, not by certificate: GTS Root R4's cross-sign by GlobalSign carries R4's own key.
        val crossSigned = cert("gts-root-r4-cross-signed.pem")
        assertEquals(spki(r4), spki(crossSigned))
        val viaCrossSign = trust(setOf(spki(r4))) { _, _ -> listOf(leaf, we1, crossSigned) }
        assertThrows(CertificateException::class.java) { viaCrossSign.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA", "pingolindev.com") }
    }

    @Test
    fun `the built path decides, never the chain the server sent`() {
        // Sent only the leaf, built through a listed intermediate: refused.
        val builtThroughListed = trust(setOf(spki(we1))) { _, _ -> ours }
        assertThrows(CertificateException::class.java) {
            builtThroughListed.checkServerTrusted(arrayOf(leaf), "ECDHE_ECDSA", "api-track.pingolindev.com")
        }
        // Sent a listed certificate the platform didn't build on: the path is clean, so it goes through.
        val sentButUnused = trust(setOf(spki(r4))) { _, _ -> listOf(leaf, we1) }
        assertEquals(listOf(leaf, we1), sentButUnused.checkServerTrusted(arrayOf(leaf, we1, r4), "ECDHE_ECDSA", "api-track.pingolindev.com"))
    }

    @Test
    fun `the platform's own refusal stands, and nothing is listed past it`() {
        val platform = Platform(refuse = true)
        var built = 0
        val trust = trust(emptySet(), platform) { _, _ -> built++; ours }

        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", null as java.net.Socket?) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", null as SSLEngine?) }
        assertEquals(0, built)
    }

    /** No host, no pass: Network Security Config's per-host rules need one. */
    @Test
    fun `with no host to check the path for, the platform isn't asked for one and everything is refused`() {
        var built = 0
        val trust = trust(emptySet()) { _, _ -> built++; ours }
        // An engine's own peerHost is only what it was made with, not who answered: no host to check for.
        val engine = mockk<SSLEngine> { every { handshakeSession } returns null; every { peerHost } returns "api-track.pingolindev.com" }

        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", null as String?) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), null, null, "ECDHE_ECDSA", null) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", handshakingWith(null)) }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", engine) }
        assertEquals(0, built)
    }

    /**
     * Android 16: the WebView hands over what the server stapled (an OCSP response, SCTs) whenever there is
     * any. The platform's own check is given it, and the path it builds is refused by key all the same.
     */
    @Test
    fun `given what the server stapled, the platform checks with it, and the path it builds is still refused by key`() {
        val ocsp = byteArrayOf(1, 2, 3)
        val sct = byteArrayOf(4, 5)
        val asked = mutableListOf<Triple<ByteArray?, ByteArray?, String>>()
        fun stapled(blocked: Set<String>) = CompromisedKeyTrustManager(
            Platform(), { _, _, _ -> throw AssertionError("what was stapled must reach the platform") }, blocked,
            builtPathWithData = { _, o, s, _, host -> asked += Triple(o, s, host); ours },
        )

        assertEquals(ours, stapled(emptySet()).checkServerTrusted(arrayOf(leaf, we1), ocsp, sct, "ECDHE_ECDSA", "api-track.pingolindev.com"))
        assertSame(ocsp, asked.single().first)
        assertSame(sct, asked.single().second)
        assertEquals("api-track.pingolindev.com", asked.single().third)
        assertThrows(CertificateException::class.java) {
            stapled(setOf(spki(we1))).checkServerTrusted(arrayOf(leaf), ocsp, null, "ECDHE_ECDSA", "api-track.pingolindev.com")
        }
        // Where the platform has no such check (before Android 16), its host-aware one builds the path.
        assertEquals(ours, trust(emptySet()).checkServerTrusted(arrayOf(leaf, we1), ocsp, sct, "ECDHE_ECDSA", "api-track.pingolindev.com"))
        assertThrows(CertificateException::class.java) {
            trust(setOf(spki(r4))).checkServerTrusted(arrayOf(leaf, we1), ocsp, sct, "ECDHE_ECDSA", "api-track.pingolindev.com")
        }
    }

    @Test
    fun `the handshake's host is the one the built path is asked for, on a socket and on an engine`() {
        val hosts = mutableListOf<String?>()
        val trust = trust(emptySet()) { _, host -> hosts += host; ours }
        val session = mockk<SSLSession> { every { peerHost } returns "api-track.pingolindev.com" }
        val socket = mockk<SSLSocket> { every { handshakeSession } returns session }
        val engine = mockk<SSLEngine> { every { handshakeSession } returns session; every { peerHost } returns "other" }

        trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", socket)
        trust.checkServerTrusted(arrayOf(leaf, we1), "ECDHE_ECDSA", engine)

        assertEquals(listOf("api-track.pingolindev.com", "api-track.pingolindev.com"), hosts)
    }

    @Test
    fun `the test-only list reads hex keys one per line, and nothing else`() {
        val dir = java.nio.file.Files.createTempDirectory("blocked").toFile()
        try {
            File(dir, "test-blocked-spki.txt").writeText("${"AB".repeat(32)}\nnot a key\n${"cd".repeat(31)}\n  ${"0f".repeat(32)}  \n")
            assertEquals(setOf("ab".repeat(32), "0f".repeat(32)), CompromisedKeyTrustManager.testBlocked(dir))
            assertTrue(CompromisedKeyTrustManager.testBlocked(File(dir, "missing")).isEmpty())
            assertTrue(CompromisedKeyTrustManager.testBlocked(null).isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * X509TrustManagerExtensions takes a trust manager that isn't the platform's by looking these up by
     * reflection, as AOSP's RootTrustManager has them: from Android 8 the host-aware check (it throws
     * without) and isSameTrustConfiguration, from Android 16 also the check given what the server stapled
     * (the WebView's pages fail without it). The app never calls them from outside, so R8 must keep them.
     */
    @Test
    fun `what X509TrustManagerExtensions looks up by reflection is public, in RootTrustManager's shapes, and kept`() {
        val wrapper = CompromisedKeyTrustManager::class.java
        val check = wrapper.getMethod("checkServerTrusted", Array<X509Certificate>::class.java, String::class.java, String::class.java)
        assertEquals(List::class.java, check.returnType)
        val stapled = wrapper.getMethod(
            "checkServerTrusted", Array<X509Certificate>::class.java, ByteArray::class.java, ByteArray::class.java, String::class.java, String::class.java,
        )
        assertEquals(List::class.java, stapled.returnType)
        val same = wrapper.getMethod("isSameTrustConfiguration", String::class.java, String::class.java)
        assertEquals(java.lang.Boolean.TYPE, same.returnType)

        val rules = File("proguard-rules.pro").readText()
        assertTrue(
            rules.contains(
                """
                -keepclassmembers class com.pingolin.track.util.CompromisedKeyTrustManager {
                    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], java.lang.String, java.lang.String);
                    public java.util.List checkServerTrusted(java.security.cert.X509Certificate[], byte[], byte[], java.lang.String, java.lang.String);
                    public boolean isSameTrustConfiguration(java.lang.String, java.lang.String);
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `whether two hosts share trust rules is the platform's answer, and no when it can't say`() {
        for (answer in listOf(true, false)) {
            val trust = CompromisedKeyTrustManager(Platform(), { c, _, _ -> c.toList() }, emptySet(), sameTrust = { _, _ -> answer })
            assertEquals(answer, trust.isSameTrustConfiguration("api-track.pingolindev.com", "api-auth.pingolindev.com"))
        }
        val failing = CompromisedKeyTrustManager(Platform(), { c, _, _ -> c.toList() }, emptySet(), sameTrust = { _, _ -> error("no answer") })
        assertEquals(false, failing.isSameTrustConfiguration("api-track.pingolindev.com", "api-auth.pingolindev.com"))
        assertEquals(false, trust(emptySet()).isSameTrustConfiguration("api-track.pingolindev.com", "api-auth.pingolindev.com"))
    }

    @Test
    fun `the per-client trust starts from the platform's own factory, by name, never the default, and its own checks`() {
        val trust = File(main, "util/CompromisedKeyTrust.kt").readText()
        assertTrue(trust.contains("val factory = TrustManagerFactory.getInstance(\"PKIX\", PLATFORM_PROVIDER)"))
        assertTrue(trust.contains("const val PLATFORM_PROVIDER = \"AndroidNSSP\""))
        assertFalse(trust.contains("TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())"))
        // Each answer is the platform's own, through X509TrustManagerExtensions on its manager.
        assertTrue(trust.contains("{ c, a, h -> extensions.checkServerTrusted(c, a, h) },"))
        assertTrue(trust.contains("extensions.checkServerTrusted(c, ocsp, sct, a, h)"))
        assertTrue(trust.contains("{ h1, h2 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extensions.isSameTrustConfiguration(h1, h2) },"))
    }

    private val main = File("src/main/java/com/pingolin/track")

    @Test
    fun `the test-only list is read in debug builds only`() {
        val dir = java.nio.file.Files.createTempDirectory("blocked").toFile()
        try {
            File(dir, "test-blocked-spki.txt").writeText("ab".repeat(32) + "\n")
            assertEquals(CompromisedKeys.SPKI_SHA256, CompromisedKeyTrustManager.blocked(dir, debug = false))
            assertEquals(CompromisedKeys.SPKI_SHA256 + "ab".repeat(32), CompromisedKeyTrustManager.blocked(dir, debug = true))
        } finally {
            dir.deleteRecursively()
        }
        val module = File(main, "di/AppModule.kt").readText()
        assertTrue(module.contains("CompromisedKeyTrustManager.platform(context.noBackupFilesDir, debug = BuildConfig.DEBUG)"))
    }

    @Test
    fun `every OkHttp client this app builds goes through it`() {
        val builders = listOf("main", "gms", "libre").map { File("src/$it/java") }.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .flatMap { file -> Regex("""OkHttpClient\.Builder\(\)""").findAll(file.readText()).map { file.name to it.range.first }.toList() }
        assertEquals("the API client, the map style client and MapLibre's", 3, builders.size)
        val module = File(main, "di/AppModule.kt").readText()
        assertEquals(3, Regex("""OkHttpClient\.Builder\(\)\s*\n\s*\.refusingCompromisedKeys\(trust\)""").findAll(module).count())

        val trust = com.pingolin.track.testutil.plainTrust()
        val api = com.pingolin.track.di.AppModule.provideOkHttpClient(
            mockk(relaxed = true), mockk(relaxed = true), RateLimitInterceptor(), CircuitBreakerInterceptor(), okhttp3.ConnectionPool(), trust,
        )
        assertSame(trust, api.x509TrustManager)
        // Its copies (the auth client's, the crash
        // reporter's) keep it.
        assertSame(trust, com.pingolin.track.di.AppModule.withoutRateLimitRetries(api).x509TrustManager)
    }

    @Test
    fun `no other HTTP stack ships, so nothing reaches a host past it`() {
        val deps = Regex("""^\s*\w*[iI]mplementation\("([^"]+)"\)""", RegexOption.MULTILINE)
            .findAll(File("build.gradle.kts").readText()).map { it.groupValues[1] }.toList()
        assertTrue("the dependency list is read", deps.size > 20)
        // Coil's network module, Ktor, Cronet, the URLConnection bridge: each makes connections of its own.
        val stacks = Regex("""coil-network|io\.ktor|cronet|okhttp-urlconnection""")
        assertEquals(emptyList<String>(), deps.filter { stacks.containsMatchIn(it) })
        // Coil's own network fetcher, with its own client on system trust, is not on the classpath at all.
        assertThrows(ClassNotFoundException::class.java) { Class.forName("coil3.network.okhttp.OkHttpNetworkFetcherFactory") }
    }
}
