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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every token refresh in the process goes through ONE lock over ONE stored pair:
 * TokenRefresher. The auth server tolerates racing refreshes but not two code paths that
 * each keep their own copy of the token; they retire each other's, and the first to sit
 * idle past 900 s revokes the session. The OkHttp authenticator, the gRPC stream and the
 * background workers all end up in TokenRefresher.refreshBlocking, whose refresh reads the
 * stored refresh token only once it holds the lock.
 *
 * And nothing logs the refresh token: debug builds log request headers, so X-Refresh-Token
 * is redacted like Authorization.
 */
class SingleRefreshPathTest {

    private val sources = File("src").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/test" !in it.path && "/androidTest" !in it.path }
        .toList()

    private fun filesMatching(pattern: Regex) = sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.toSet()

    @Test
    fun `only TokenRefresher calls the refresh, and only AuthRepository calls the endpoint`() {
        // AuthRepository.refreshToken() takes nothing; the endpoint takes the token.
        assertEquals(setOf("TokenRefresher.kt"), filesMatching(Regex("""\.refreshToken\(\s*\)""")))
        assertEquals(setOf("AuthRepository.kt"), filesMatching(Regex("""authApi\.refreshToken\(""")))
        val refresher = File("src/main/java/com/pingolin/track/util/TokenRefresher.kt").readText()
        assertTrue(refresher.contains("future.complete(runBlocking { authRepositoryProvider.get().refreshToken() })"))
        // The refresh reads the stored refresh token itself, at the moment it runs.
        val repo = File("src/main/java/com/pingolin/track/domain/repository/AuthRepository.kt").readText()
        assertTrue(repo.contains("refreshToken = sessionManager.getRefreshToken(),"))
    }

    @Test
    fun `debug logging never prints the refresh token`() {
        val module = File("src/main/java/com/pingolin/track/di/AppModule.kt").readText()
        val logging = module.substring(module.indexOf("HttpLoggingInterceptor().apply {"))
        val block = logging.substring(0, logging.indexOf("builder.addInterceptor(loggingInterceptor)"))
        assertTrue(block.contains("redactHeader(\"X-Refresh-Token\")"))
        assertTrue(block.contains("redactHeader(\"Authorization\")"))
    }
}
