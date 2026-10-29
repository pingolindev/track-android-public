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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Collections

/**
 * Background work refreshes an expired token like any other caller, and the rotated pair
 * reaches disk in ONE write before anything presents the new access token.
 *
 * With the auth server's commit-on-first-use refresh (a rotation commits only when the new
 * refresh token is first presented) that is what makes a refresh safe to run in the background:
 * killed before the write, the phone still holds the earlier pair, which the server still
 * honours; killed after it, the phone holds the new pair, both halves read back.
 *
 * On the wire: MockWebServer, a real Retrofit [AuthApi], the real
 * [AuthRepository.refreshToken], [TokenRefresher] and [TokenAuthenticator], and a
 * [SessionManager] whose storage records every write as it lands.
 */
class BackgroundRefreshTest {

    private lateinit var server: MockWebServer

    /** What happened, in order: the storage's writes and the server's requests. */
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** In-memory storage that logs each write ("write:<values>") and can refuse them. */
    private inner class RecordingPrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        var refuseWrites = false

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
                if (refuseWrites) return false
                write()
                return true
            }
            override fun apply() {
                if (!refuseWrites) write()
            }
            private fun write() = synchronized(data) {
                removed.forEach { data.remove(it) }
                data.putAll(pending)
                events += "write:" + pending.values.filterIsInstance<String>().sorted().joinToString(",")
            }
        }
    }

    private class TestSession(context: Context, override val prefs: SharedPreferences) : SessionManager(context)

    private lateinit var prefs: RecordingPrefs
    private lateinit var session: TestSession
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/auth/refresh" -> {
                    events += "refresh:" + request.getHeader("X-Refresh-Token")
                    MockResponse().setResponseCode(200).setBody(
                        """{"success":true,"data":{"access_token":"access-2","refresh_token":"refresh-2"},"error":null}"""
                    )
                }
                else -> {
                    val token = request.getHeader("Authorization")?.removePrefix("Bearer ")
                    events += "request:$token"
                    if (token == "access-2") MockResponse().setResponseCode(200).setBody("{}")
                    else MockResponse().setResponseCode(401)
                }
            }
        }
        server.start()

        prefs = RecordingPrefs()
        session = TestSession(mockk(relaxed = true), prefs)
        // Hours idle: the access token the phone holds has expired.
        session.saveTokens("access-1", "refresh-1", expiresIn = 900)
        events.clear()

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
        val refresher = TokenRefresher(session) { authRepository }
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

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call() = client.newCall(Request.Builder().url(server.url("/api/v1/circles")).build()).execute()

    @Test
    fun `an expired token is refreshed, and the new pair is on disk in one write before anything uses it`() {
        call().use { assertEquals(200, it.code) }

        assertEquals(
            listOf("request:access-1", "refresh:refresh-1", "write:access-2,refresh-2", "request:access-2"),
            events.toList(),
        )
        assertEquals("refresh-2", session.getRefreshToken())
    }

    @Test
    fun `a pair that cannot be stored fails the refresh without signing out, and nothing uses it`() {
        prefs.refuseWrites = true

        call().use { assertEquals(401, it.code) }

        assertEquals(listOf("request:access-1", "refresh:refresh-1"), events.toList())
        // The earlier pair is still what the phone holds, and the session is
        // kept: the server still honours that pair until the new one is used.
        assertEquals("access-1", session.getAccessToken())
        assertEquals("refresh-1", session.getRefreshToken())
        assertFalse(events.any { it.startsWith("write:") })
    }
}
