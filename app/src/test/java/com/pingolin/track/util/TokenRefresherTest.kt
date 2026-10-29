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

import com.pingolin.track.domain.repository.AuthRepository
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.Runs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * The point of TokenRefresher is that two callers cannot refresh at once.
 *
 * The server rotates the refresh token on every call and tolerates only one
 * rotation back, so overlapping refreshes (say, the OkHttp authenticator's and
 * the gRPC stream's) would leave one side holding a retired token: the next
 * refresh would be rejected 401 and the client would sign itself out against a
 * session the server still considers live. So the concurrency is the behaviour
 * under test, not an incidental detail.
 */
class TokenRefresherTest {

    @MockK
    private lateinit var sessionManager: SessionManager

    @MockK
    private lateinit var authRepository: AuthRepository

    private lateinit var refresher: TokenRefresher
    private val serverCalls = AtomicInteger(0)

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        refresher = TokenRefresher(sessionManager, Provider { authRepository })
        serverCalls.set(0)
    }

    @After
    fun teardown() = clearAllMocks()

    /** Models the real contract: a refresh rotates the stored token. */
    private fun storedTokenRotatesOnRefresh(startAt: String = "old", newToken: String = "new") {
        var stored = startAt
        every { sessionManager.getAccessToken() } answers { stored }
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } coAnswers {
            serverCalls.incrementAndGet()
            Thread.sleep(120)   // hold the lock long enough for a second caller to arrive
            stored = newToken
            Result.success(Unit)
        }
    }

    @Test
    fun `two callers refreshing at once produce exactly one server call`() {
        storedTokenRotatesOnRefresh()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val outcomes = java.util.Collections.synchronizedList(mutableListOf<TokenRefresher.Outcome>())

        repeat(2) {
            Thread {
                start.await()
                outcomes.add(refresher.refreshBlocking("old"))
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))

        // THE assertion: one rotation, not two. Two would retire a token the
        // other caller still needs.
        assertEquals("exactly one refresh should reach the server", 1, serverCalls.get())
        assertEquals(2, outcomes.size)
    }

    @Test
    fun `both concurrent callers end up on the same newest token`() {
        storedTokenRotatesOnRefresh()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String?>())

        repeat(2) {
            Thread {
                start.await()
                refresher.refreshBlocking("old")
                seen.add(sessionManager.getAccessToken())
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(2, seen.size)
        assertEquals("both callers must see the same token", seen[0], seen[1])
        assertEquals("new", seen[0])
        assertNotEquals("old", seen[0])
    }

    @Test
    fun `a caller whose token has already moved on does not refresh again`() {
        // The redundant-refresh guard: if someone else already rotated, asking
        // again is exactly what retires a still-needed token.
        every { sessionManager.getAccessToken() } returns "already-new"
        every { sessionManager.getRefreshToken() } returns "refresh-token"

        val outcome = refresher.refreshBlocking("stale-token-my-request-used")

        assertSame(TokenRefresher.Outcome.AlreadyFresh, outcome)
        assertEquals(0, serverCalls.get())
    }

    @Test
    fun `a transient failure does not end the session`() {
        every { sessionManager.getAccessToken() } returns "old"
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } returns
            Result.failure(java.io.IOException("timeout"))

        assertSame(TokenRefresher.Outcome.Transient, refresher.refreshBlocking("old"))
    }

    @Test
    fun `only a 401 is terminal`() {
        every { sessionManager.getAccessToken() } returns "old"
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } returns
            Result.failure(SessionExpiredException("Token refresh rejected: HTTP 401"))

        assertSame(TokenRefresher.Outcome.SessionRejected, refresher.refreshBlocking("old"))
    }

    @Test
    fun `a refresh that stores no token is reported distinctly, not as a rejection`() {
        every { sessionManager.getAccessToken() } returns null
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } returns Result.success(Unit)

        assertSame(TokenRefresher.Outcome.NoTokenAfterRefresh, refresher.refreshBlocking("old"))
    }

    @Test
    fun `the circuit breaker still opens after repeated failures`() {
        every { sessionManager.getAccessToken() } returns "old"
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } coAnswers {
            serverCalls.incrementAndGet()
            Result.failure(java.io.IOException("down"))
        }

        repeat(3) { assertSame(TokenRefresher.Outcome.Transient, refresher.refreshBlocking("old")) }
        assertEquals(3, serverCalls.get())

        // Fourth attempt must not reach the server.
        assertSame(TokenRefresher.Outcome.CircuitOpen, refresher.refreshBlocking("old"))
        assertEquals("circuit should have prevented a fourth call", 3, serverCalls.get())
    }

    @Test
    fun `no credentials is refused without calling the server`() {
        every { sessionManager.getAccessToken() } returns null
        every { sessionManager.getRefreshToken() } returns null

        assertSame(TokenRefresher.Outcome.NoCredentials, refresher.refreshBlocking(null))
        assertEquals(0, serverCalls.get())
    }

    // --- listeners: how the location stream learns a new token ---

    @Test
    fun `a successful refresh hands the new token to every listener`() {
        storedTokenRotatesOnRefresh(newToken = "fresh")
        val seenA = mutableListOf<String>()
        val seenB = mutableListOf<String>()
        refresher.addTokenListener { seenA += it }
        refresher.addTokenListener { seenB += it }

        assertSame(TokenRefresher.Outcome.Refreshed, refresher.refreshBlocking("old"))

        assertEquals(listOf("fresh"), seenA)
        assertEquals(listOf("fresh"), seenB)
    }

    @Test
    fun `a failed refresh tells no listener`() {
        every { sessionManager.getAccessToken() } returns "old"
        every { sessionManager.getRefreshToken() } returns "refresh-token"
        coEvery { authRepository.refreshToken() } coAnswers { Result.failure(java.io.IOException("down")) }
        val seen = mutableListOf<String>()
        refresher.addTokenListener { seen += it }

        assertSame(TokenRefresher.Outcome.Transient, refresher.refreshBlocking("old"))

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a listener that throws does not fail the refresh or starve the others`() {
        storedTokenRotatesOnRefresh(newToken = "fresh")
        val seen = mutableListOf<String>()
        refresher.addTokenListener { throw IllegalStateException("stream gone") }
        refresher.addTokenListener { seen += it }

        assertSame(TokenRefresher.Outcome.Refreshed, refresher.refreshBlocking("old"))
        assertEquals(listOf("fresh"), seen)
    }
}
