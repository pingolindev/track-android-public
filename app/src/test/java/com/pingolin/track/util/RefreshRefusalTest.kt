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

import android.content.Context
import android.content.SharedPreferences
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.domain.repository.AuthRepository
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * A refresh ends the session only on the auth server's own refusal: a 401 with its JSON error.
 *
 * WHY THESE EXIST
 *   Track ended the session on ANY 401 from /api/v1/auth/refresh. Every refusal of auth's refresh
 *   route is a JSON 401 (`{"success":false,"error":…}`, application/json); an HTML 401 from
 *   Cloudflare or a proxy in front of auth, or a 401 with no content type, says nothing about the
 *   session, and signing out over one costs the person their session. Note draws the same line
 *   (its TokenAuthenticator.isRefusal).
 *
 * On the wire, as BackgroundRefreshTest: MockWebServer, a real Retrofit [AuthApi], the real
 * [AuthRepository.refreshToken], [TokenRefresher] and [TokenAuthenticator], and a
 * [SessionManager] on in-memory storage. A fresh one for every case: a forced sign-out runs once
 * per session.
 */
class RefreshRefusalTest {

    private val servers = mutableListOf<MockWebServer>()

    /** In-memory storage, enough for a session. */
    private class MemoryPrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = synchronized(data) { data.toMap() }
        override fun getString(key: String?, defValue: String?) = synchronized(data) { data[key] as? String ?: defValue }
        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            synchronized(data) { @Suppress("UNCHECKED_CAST") (data[key] as? Set<String>) ?: defValues }
        override fun getInt(key: String?, defValue: Int) = synchronized(data) { data[key] as? Int ?: defValue }
        override fun getLong(key: String?, defValue: Long) = synchronized(data) { data[key] as? Long ?: defValue }
        override fun getFloat(key: String?, defValue: Float) = synchronized(data) { data[key] as? Float ?: defValue }
        override fun getBoolean(key: String?, defValue: Boolean) = synchronized(data) { data[key] as? Boolean ?: defValue }
        override fun contains(key: String?) = synchronized(data) { data.containsKey(key) }
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            override fun putString(k: String?, v: String?) = apply { pending[k!!] = v }
            override fun putStringSet(k: String?, v: Set<String>?) = apply { pending[k!!] = v }
            override fun putInt(k: String?, v: Int) = apply { pending[k!!] = v }
            override fun putLong(k: String?, v: Long) = apply { pending[k!!] = v }
            override fun putFloat(k: String?, v: Float) = apply { pending[k!!] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { pending[k!!] = v }
            override fun remove(k: String?) = apply { removed += k!! }
            override fun clear() = apply { synchronized(data) { data.clear() } }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() = synchronized(data) {
                removed.forEach { data.remove(it) }
                data.putAll(pending)
            }
        }
    }

    private class TestSession(context: Context, override val prefs: SharedPreferences) : SessionManager(context)

    /** One wiring: the refresh endpoint answers [refreshAnswer] (built fresh for each request),
     *  every other request 401s on the expired access token. */
    private inner class Wire(refreshAnswer: () -> MockResponse) {
        val server = MockWebServer().also { servers += it }
        val session = TestSession(mockk(relaxed = true), MemoryPrefs())
        val refresher: TokenRefresher
        val client: OkHttpClient

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/v1/auth/refresh" -> refreshAnswer()
                    else -> MockResponse().setResponseCode(401)
                }
            }
            server.start()
            // Hours idle: the access token the phone holds has expired.
            session.saveTokens("access-1", "refresh-1", expiresIn = 900)
            val authApi = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create())
                .client(OkHttpClient())
                .build()
                .create(AuthApi::class.java)
            val authRepository = AuthRepository(
                context = mockk(relaxed = true),
                authApi = authApi,
                tracksApi = mockk(relaxed = true),
                database = mockk(relaxed = true),
                userDao = mockk(relaxed = true),
                circleDao = mockk(relaxed = true),
                sessionManager = session,
                cryptoManager = mockk(relaxed = true),
                keystoreEncryption = mockk(relaxed = true),
                databasePassphraseManager = mockk(relaxed = true),
                encryptedPrefs = mockk(relaxed = true),
                rateLimiter = mockk(relaxed = true),
                locationRepository = mockk(relaxed = true),
                circleRepository = mockk(relaxed = true),
                quotaManager = mockk(relaxed = true),
                boundaryRepository = mockk(relaxed = true),
                monitoringManager = mockk(relaxed = true),
                hiddenDestinations = mockk(relaxed = true),
                geocodeTokenManager = mockk(relaxed = true),
                crashReporter = mockk(relaxed = true),
                retainedTrust = mockk(relaxed = true),
                keyStoreRecovery = mockk(relaxed = true),
                alertRulesReload = mockk(relaxed = true),
            )
            refresher = TokenRefresher(session) { authRepository }
            // The app's client in miniature: the stored token on every request, and the
            // authenticator on a 401 (AppModule.provideOkHttpClient).
            client = OkHttpClient.Builder()
                .authenticator(TokenAuthenticator(session, refresher))
                .addInterceptor { chain ->
                    val token = session.getAccessToken()
                    chain.proceed(chain.request().newBuilder().apply { if (token != null) header("Authorization", "Bearer $token") }.build())
                }
                .build()
        }

        /** A request on the expired token: its 401 goes through the authenticator's refresh. */
        fun call() = client.newCall(Request.Builder().url(server.url("/api/v1/circles")).build()).execute().use { it.code }
    }

    @After
    fun tearDown() {
        servers.forEach { it.shutdown() }
    }

    /** A refresh answer: [code], [contentType] (none at all when null), [body]. */
    private fun answer(code: Int, contentType: String?, body: String): () -> MockResponse = {
        MockResponse().setResponseCode(code).setBody(body).apply {
            removeHeader("Content-Type")
            if (contentType != null) setHeader("Content-Type", contentType)
        }
    }

    /** What auth's refresh route answers a refused refresh token with (refresh.rs, `err(StatusCode::UNAUTHORIZED, …)`). */
    private val authRefusal = """{"success":false,"error":"Session revoked"}"""

    @Test
    fun `auth's JSON 401 refuses the session, and the request's 401 signs out, with or without a charset`() {
        for (type in listOf("application/json", "application/json; charset=utf-8")) {
            val outcome = Wire(answer(401, type, authRefusal))
            assertEquals(type, TokenRefresher.Outcome.SessionRejected, outcome.refresher.refreshBlocking("access-1"))

            val signsOut = Wire(answer(401, type, authRefusal))
            assertEquals(type, 401, signsOut.call())
            assertNull("$type: signed out", signsOut.session.getAccessToken())
            assertNull("$type: signed out", signsOut.session.getRefreshToken())
        }
    }

    /** The edge in front of auth, not auth: no answer about the session, which is kept. */
    @Test
    fun `an HTML 401, a 401 with no content type, or a JSON 403 is no answer, and the session is kept`() {
        val notAuths = listOf(
            "an HTML 401" to answer(401, "text/html; charset=UTF-8", "<html><body>Unauthorized</body></html>"),
            "a 401 with no content type" to answer(401, null, ""),
            "a JSON 403" to answer(403, "application/json", """{"success":false,"error":"Forbidden"}"""),
        )
        for ((what, refreshAnswer) in notAuths) {
            val outcome = Wire(refreshAnswer)
            assertEquals(what, TokenRefresher.Outcome.Transient, outcome.refresher.refreshBlocking("access-1"))

            val kept = Wire(refreshAnswer)
            assertEquals(what, 401, kept.call())
            assertEquals("$what: the session kept", "access-1", kept.session.getAccessToken())
            assertEquals("$what: the session kept", "refresh-1", kept.session.getRefreshToken())
        }
    }
}
