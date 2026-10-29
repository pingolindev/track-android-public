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

package com.pingolin.track.di

import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.dto.TwoFactorVerifyRequest
import com.pingolin.track.testutil.startOnIpv4Loopback
import com.pingolin.track.util.RateLimitInterceptor
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The auth server's 429s are answers, never a load to wait out: after the fifth wrong code the
 * person reads "too many wrong codes" at once, instead of waiting up to 20 s for the shared
 * client's retries and then reading "that sign-in has ended". The API client keeps them.
 */
class AuthClientRateLimitTest {

    private val server = MockWebServer().apply { startOnIpv4Loopback() }
    /** As AppModule's: other interceptors (here one stamping a header) and the retries. */
    private val shared = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Stamp", "1").build()) }
        .addInterceptor(RateLimitInterceptor())
        .build()

    @After
    fun tearDown() = server.shutdown()

    private fun tooMany() = MockResponse().setResponseCode(429).setHeader("Retry-After", "0")
        .setHeader("Content-Type", "application/json").setBody("""{"success":false,"error_code":"rate_limited"}""")

    @Test
    fun `the code step's 429 reaches sign-in at once - tried once, never again`() = runTest {
        repeat(3) { server.enqueue(tooMany()) }
        // The app's own auth Retrofit, over the shared client.
        val sessionManager = io.mockk.mockk<com.pingolin.track.util.SessionManager> {
            io.mockk.every { getAuthBaseUrl() } returns server.url("/").toString()
        }
        val authApi = AppModule.provideAuthApi(AppModule.provideAuthRetrofit(shared, sessionManager))

        val response = authApi.verify2fa(TwoFactorVerifyRequest(twoFactorToken = "t", code = "123456", method = "totp"))

        assertEquals(429, response.code())
        assertEquals("one try", 1, server.requestCount)
        assertEquals("the other interceptors still run", "1", server.takeRequest().getHeader("X-Stamp"))
    }

    @Test
    fun `the shared client still tries a 429 again elsewhere`() {
        repeat(3) { server.enqueue(tooMany()) }

        shared.newCall(Request.Builder().url(server.url("/api/v1/circles")).build()).execute().close()

        assertEquals("the first try and 2 more", 3, server.requestCount)
    }
}
