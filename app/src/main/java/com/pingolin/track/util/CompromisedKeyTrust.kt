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

import android.os.Build
import okhttp3.OkHttpClient
import java.io.File
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Keys no certificate path to a server may hold.
 *
 * The private halves of these keys are public: the emSign keys that were leaked, which Sectigo's AAA
 * root still reaches through cross-signs, and the emSign ECC Root CA - G3 key. Android checks no
 * revocation, so a chain through any of them is trusted by the system store unless something here
 * says no. Matched by key, not by certificate: every cross-sign of a listed key is refused with it,
 * while AAA itself is not listed, since it still anchors good Sectigo chains.
 */
object CompromisedKeys {
    /**
     * SHA-256 of each key's DER SubjectPublicKeyInfo, lower-case hex (the leaked eight as
     * rustls-webpki 0.103.16 distrusts them).
     */
    val SPKI_SHA256: Set<String> = setOf(
        // The eight leaked emSign intermediates.
        "ebed6475c2c2d80fcb199f3fe8dd6e6b3ce60b77eb15d4e42c3dec0c6f5b5105", // EMDVTLSCAG3B
        "49dd353dc2b919a3770645117d0e60c16115e7d1bc259eefe734b00ea434fe5f", // EMDVTLSCAG3C
        "e0254c7fcdc265e2f0f8a428dabb75ab4ae039b4872d62f5ee57f0877d5a8a41", // EMEVTLSCAG3B
        "5c0660a2b164910d8ff589c18e14552e9e0ec237dcefe2cb736d41a4811f81a0", // EMEVTLSCAG3C
        "ec4ffa1ad0ac88081314b7aaffef6a91f97cd57291bb5ba686c5415f5eb4f6b6", // EMOVCSCAG3B
        "92c063d67e8f14db71d288e11266ffdaf69cbf115f7bab14f3bc2f68d25e0e30", // EMOVCSCAG3C
        "43bffa19d6ef651451f6841e019931e96f6f2eaeff28ab4b6f1ce4576de2717b", // EMOVTLSCAG3B
        "63ecefee431d49523bc1136c4e72d0206a477086caf606cd5cf025686c109cf8", // EMOVTLSCAG3C
        // Their root.
        "8d417db2dd8bf5e3084d1e3f196d583849d81bdd4c00c70b9d39369e96b8c782", // emSign ECC Root CA - G3
    )

    /** [cert]'s key as [SPKI_SHA256] names one. */
    fun spkiSha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
}

/**
 * The platform's own trust decision, then a refusal of any certificate path it BUILT that holds a
 * [CompromisedKeys] key. The built path, never the chain the server sent: a server can send any
 * certificates it likes, and the platform may build through a cross-sign the server never named.
 *
 * The path comes from the platform's hostname-aware check ([builtPath]: X509TrustManagerExtensions),
 * so Network Security Config's per-host rules apply to it as they do to the handshake itself. No host,
 * no pass: without one those rules can't apply, so nothing is checked and everything is refused.
 *
 * Public with the shapes AOSP's RootTrustManager has: X509TrustManagerExtensions finds them by reflection
 * on whatever trust manager it's given (the WebView's, through CompromisedKeyTrustProvider in Note, and
 * OkHttp's chain cleaner), and R8 keeps them (proguard-rules.pro).
 */
class CompromisedKeyTrustManager internal constructor(
    private val platform: X509TrustManager,
    private val builtPath: (Array<X509Certificate>, String, String) -> List<X509Certificate>,
    private val blocked: Set<String>,
    /** The platform's check given the OCSP response and SCTs the server stapled; [builtPath] when null. */
    private val builtPathWithData: ((Array<X509Certificate>, ByteArray?, ByteArray?, String, String) -> List<X509Certificate>)? = null,
    /** The platform's answer to [isSameTrustConfiguration]; no answer when null. */
    private val sameTrust: ((String?, String?) -> Boolean)? = null,
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket)
            ?: platform.checkServerTrusted(chain, authType)
        checkServerTrusted(chain, authType, (socket as? SSLSocket)?.handshakeSession?.peerHost)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        (platform as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine)
            ?: platform.checkServerTrusted(chain, authType)
        checkServerTrusted(chain, authType, engine?.handshakeSession?.peerHost)
    }

    /** No host to check the path for: refused (RootTrustManager refuses it too, where a domain-config exists). */
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw noHost()

    /** The path the platform builds for [host], refused when any certificate on it has a listed key. */
    fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, host: String?): List<X509Certificate> {
        val forHost = host ?: throw noHost()
        return refusingListed(builtPath(chain, authType, forHost), forHost)
    }

    /**
     * The same, the platform also given the OCSP response and SCTs the server stapled. Android 16's
     * X509TrustManagerExtensions looks this up too, and the WebView asks for it whenever a server staples
     * either: without it, every such page would fail to load.
     */
    fun checkServerTrusted(
        chain: Array<X509Certificate>,
        ocspData: ByteArray?,
        tlsSctData: ByteArray?,
        authType: String,
        host: String?,
    ): List<X509Certificate> {
        val forHost = host ?: throw noHost()
        val path = builtPathWithData?.invoke(chain, ocspData, tlsSctData, authType, forHost) ?: builtPath(chain, authType, forHost)
        return refusingListed(path, forHost)
    }

    private fun refusingListed(path: List<X509Certificate>, host: String): List<X509Certificate> {
        if (path.any { CompromisedKeys.spkiSha256(it) in blocked }) {
            throw CertificateException("A certificate on the path to $host has a key known to be compromised")
        }
        return path
    }

    private fun noHost() = CertificateException("No host to check the certificate path for")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        platform.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        (platform as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, socket)
            ?: platform.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
        (platform as? X509ExtendedTrustManager)?.checkClientTrusted(chain, authType, engine)
            ?: platform.checkClientTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers

    /**
     * Whether [hostname1] and [hostname2] are under the same trust rules: the platform's own answer.
     * False when the platform can't say: a connection isn't then shared across the two, which costs a check.
     */
    fun isSameTrustConfiguration(hostname1: String?, hostname2: String?): Boolean =
        runCatching { sameTrust?.invoke(hostname1, hostname2) }.getOrNull() ?: false

    /** A socket factory whose every handshake goes through this, for a connection OkHttp doesn't make. */
    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@CompromisedKeyTrustManager), null) }.socketFactory

    companion object {
        /** Test-only extra blocked keys (see [blocked]): read in debug builds only. */
        private const val TEST_BLOCKED_FILE = "test-blocked-spki.txt"

        /** Android's own Network Security Config provider, the one every trust decision here starts from. */
        const val PLATFORM_PROVIDER = "AndroidNSSP"

        /**
         * The platform's own trust (Network Security Config's, asked for by name), refusing [CompromisedKeys].
         * By name, never the default factory, which a process-wide provider may already wrap: each layer
         * then checks once, and neither depends on the other.
         */
        fun platform(noBackupDir: File?, debug: Boolean): CompromisedKeyTrustManager {
            val factory = TrustManagerFactory.getInstance("PKIX", PLATFORM_PROVIDER)
            factory.init(null as KeyStore?)
            return wrapping(factory.trustManagers.filterIsInstance<X509TrustManager>().single(), blocked(noBackupDir, debug))
        }

        /** [tm], then a refusal of any path it builds for the handshake's host that holds one of [blocked]. */
        fun wrapping(tm: X509TrustManager, blocked: Set<String>): CompromisedKeyTrustManager {
            val extensions = android.net.http.X509TrustManagerExtensions(tm)
            return CompromisedKeyTrustManager(
                tm,
                { c, a, h -> extensions.checkServerTrusted(c, a, h) },
                blocked,
                { c, ocsp, sct, a, h ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                        extensions.checkServerTrusted(c, ocsp, sct, a, h)
                    } else {
                        extensions.checkServerTrusted(c, a, h)
                    }
                },
                // Public from API 28; Android 8's is hidden, so no answer there.
                { h1, h2 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extensions.isSameTrustConfiguration(h1, h2) },
            )
        }

        /**
         * [CompromisedKeys], and in a debug build also the keys listed one per line in no_backup/[TEST_BLOCKED_FILE],
         * so a proxy's scratch certificate can stand in for a leaked one. A release build never reads it.
         */
        fun blocked(noBackupDir: File?, debug: Boolean): Set<String> =
            CompromisedKeys.SPKI_SHA256 + if (debug) testBlocked(noBackupDir) else emptySet()

        /** The keys listed one per line in [noBackupDir]/[TEST_BLOCKED_FILE], lower-case hex; nothing else on a line counts. */
        internal fun testBlocked(noBackupDir: File?): Set<String> = runCatching {
            File(noBackupDir ?: return emptySet(), TEST_BLOCKED_FILE).takeIf { it.isFile }?.readLines()
                ?.map { it.trim().lowercase() }?.filter { it.matches(Regex("[0-9a-f]{64}")) }?.toSet()
        }.getOrNull().orEmpty()
    }
}

/** This client's TLS goes through [trust]: the platform's own decision, and no path through a compromised key. */
fun OkHttpClient.Builder.refusingCompromisedKeys(trust: CompromisedKeyTrustManager): OkHttpClient.Builder =
    sslSocketFactory(trust.socketFactory(), trust)
