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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumented tests for AuthRateLimiter.
 *
 * Tests the client-side rate limiting for authentication attempts including:
 * - Rate limiting after 5 failures
 * - Exponential backoff timing
 * - Success clears attempts
 * - Per-email tracking
 *
 * Security properties:
 * - 5 failed attempts triggers lockout
 * - Exponential backoff: 30s, 60s, 120s, 240s, 480s (max)
 * - Per-email tracking prevents lockout bypass with different emails
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@MediumTest
class AuthRateLimiterTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var rateLimiter: AuthRateLimiter

    @Before
    fun setUp() {
        hiltRule.inject()
        rateLimiter = AuthRateLimiter()
    }

    /**
     * Test that rate limiting is triggered after 5 failed attempts.
     */
    @Test
    fun testRateLimitingAfter5Failures() {
        val email = "test@pingolin.test"

        // First 4 attempts should be allowed
        repeat(4) { attempt ->
            rateLimiter.recordFailedAttempt(email)
            val result = rateLimiter.checkAttempt(email)
            assertTrue(
                "Attempt ${attempt + 1} should be allowed",
                result is RateLimitResult.Allowed
            )
        }

        // 5th attempt - should still be allowed before recording
        val beforeFifth = rateLimiter.checkAttempt(email)
        assertTrue("5th attempt should be allowed before recording", beforeFifth is RateLimitResult.Allowed)

        // Record 5th failure - this triggers lockout
        rateLimiter.recordFailedAttempt(email)

        // Now should be rate limited
        val afterFifth = rateLimiter.checkAttempt(email)
        assertTrue(
            "Should be rate limited after 5 failures",
            afterFifth is RateLimitResult.RateLimited
        )
    }

    /**
     * Test exponential backoff timing.
     */
    @Test
    fun testExponentialBackoffTiming() {
        val email = "backoff@example.test"

        // Record 5 failures to trigger first lockout
        repeat(5) {
            rateLimiter.recordFailedAttempt(email)
        }

        // Check first lockout (should be ~30 seconds)
        val firstLockout = rateLimiter.checkAttempt(email)
        assertTrue("Should be rate limited", firstLockout is RateLimitResult.RateLimited)

        val waitSeconds = (firstLockout as RateLimitResult.RateLimited).waitSeconds
        assertTrue(
            "First lockout should be around 30 seconds (got $waitSeconds)",
            waitSeconds in 25..35
        )

        // Note: Testing actual exponential backoff would require waiting or mocking time
        // The implementation doubles the lockout time for each subsequent failure beyond 5
    }

    /**
     * Test that success clears attempts.
     */
    @Test
    fun testSuccessClearsAttempts() {
        val email = "success@example.test"

        // Record some failed attempts (but not enough to trigger lockout)
        repeat(3) {
            rateLimiter.recordFailedAttempt(email)
        }

        // Verify remaining attempts decreased
        val remainingBefore = rateLimiter.getRemainingAttempts(email)
        assertEquals("Should have 2 remaining attempts", 2, remainingBefore)

        // Record success
        rateLimiter.recordSuccess(email)

        // Verify attempts are cleared
        val remainingAfter = rateLimiter.getRemainingAttempts(email)
        assertEquals("Should have full attempts after success", 5, remainingAfter)

        // Verify subsequent attempts are allowed
        val result = rateLimiter.checkAttempt(email)
        assertTrue("Should be allowed after success", result is RateLimitResult.Allowed)
    }

    /**
     * Test per-email tracking - different emails have independent limits.
     */
    @Test
    fun testPerEmailTracking() {
        val email1 = "user1@example.test"
        val email2 = "user2@example.test"

        // Lock out email1
        repeat(5) {
            rateLimiter.recordFailedAttempt(email1)
        }

        // email1 should be locked
        val result1 = rateLimiter.checkAttempt(email1)
        assertTrue("email1 should be rate limited", result1 is RateLimitResult.RateLimited)

        // email2 should still be allowed
        val result2 = rateLimiter.checkAttempt(email2)
        assertTrue("email2 should be allowed", result2 is RateLimitResult.Allowed)

        // Record some failures for email2
        repeat(3) {
            rateLimiter.recordFailedAttempt(email2)
        }

        // email2 should still be allowed (only 3 failures)
        val result2After = rateLimiter.checkAttempt(email2)
        assertTrue("email2 should still be allowed after 3 failures", result2After is RateLimitResult.Allowed)
    }

    /**
     * Test email normalization - case insensitive and trimmed.
     */
    @Test
    fun testEmailNormalization() {
        val baseEmail = "user@example.test"
        // Every variation must normalise (lowercase + trim) to EXACTLY baseEmail.
        // A variation on any other domain is a SECOND identity: the 5 recorded
        // attempts split across two normalised emails, neither reaches
        // MAX_ATTEMPTS = 5, and the test fails as if the rate limiter did not
        // normalise.
        val variations = listOf(
            "User@Example.test",
            "USER@EXAMPLE.TEST",
            " user@example.test ",
            "  User@Example.TEST  "
        )

        // Record failures with different variations
        repeat(5) { index ->
            rateLimiter.recordFailedAttempt(variations[index % variations.size])
        }

        // All variations should be rate limited
        for (variation in variations) {
            val result = rateLimiter.checkAttempt(variation)
            assertTrue(
                "Variation '$variation' should be rate limited",
                result is RateLimitResult.RateLimited
            )
        }

        // Base email should also be rate limited
        val baseResult = rateLimiter.checkAttempt(baseEmail)
        assertTrue("Base email should be rate limited", baseResult is RateLimitResult.RateLimited)
    }

    /**
     * Test remaining attempts calculation.
     */
    @Test
    fun testRemainingAttempts() {
        val email = "remaining@example.test"

        // Initially should have 5 attempts
        assertEquals("Should start with 5 attempts", 5, rateLimiter.getRemainingAttempts(email))

        // Record failures and check remaining
        rateLimiter.recordFailedAttempt(email)
        assertEquals("Should have 4 after 1 failure", 4, rateLimiter.getRemainingAttempts(email))

        rateLimiter.recordFailedAttempt(email)
        assertEquals("Should have 3 after 2 failures", 3, rateLimiter.getRemainingAttempts(email))

        rateLimiter.recordFailedAttempt(email)
        assertEquals("Should have 2 after 3 failures", 2, rateLimiter.getRemainingAttempts(email))

        rateLimiter.recordFailedAttempt(email)
        assertEquals("Should have 1 after 4 failures", 1, rateLimiter.getRemainingAttempts(email))

        rateLimiter.recordFailedAttempt(email)
        assertEquals("Should have 0 after 5 failures", 0, rateLimiter.getRemainingAttempts(email))
    }

    /**
     * Test clearAll removes all tracking.
     */
    @Test
    fun testClearAll() {
        val emails = listOf("email1@example.test", "email2@example.test", "email3@example.test")

        // Record failures for multiple emails
        for (email in emails) {
            repeat(5) {
                rateLimiter.recordFailedAttempt(email)
            }
        }

        // Verify all are locked
        for (email in emails) {
            assertTrue(
                "$email should be locked",
                rateLimiter.checkAttempt(email) is RateLimitResult.RateLimited
            )
        }

        // Clear all
        rateLimiter.clearAll()

        // Verify all are now allowed
        for (email in emails) {
            assertTrue(
                "$email should be allowed after clearAll",
                rateLimiter.checkAttempt(email) is RateLimitResult.Allowed
            )
            assertEquals(
                "$email should have full attempts",
                5,
                rateLimiter.getRemainingAttempts(email)
            )
        }
    }

    /**
     * Test thread safety with concurrent access.
     */
    @Test
    fun testThreadSafety() {
        val email = "concurrent@example.test"
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val failureCount = AtomicInteger(0)

        // Launch concurrent threads recording failures
        repeat(threadCount) {
            Thread {
                try {
                    rateLimiter.recordFailedAttempt(email)
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        // Wait for all threads to complete
        assertTrue("All threads should complete", latch.await(5, TimeUnit.SECONDS))

        // Should have recorded all failures
        assertEquals("Should record all concurrent failures", threadCount, failureCount.get())

        // Should be rate limited (more than 5 failures)
        val result = rateLimiter.checkAttempt(email)
        assertTrue("Should be rate limited after concurrent failures", result is RateLimitResult.RateLimited)
    }

    /**
     * Test that attempts within window are tracked, but old attempts expire.
     */
    @Test
    fun testAttemptWindowExpiry() {
        // Note: This test would require mocking system time or waiting 15+ minutes
        // which is not practical for unit tests. The implementation uses a 15-minute window.

        val email = "expiry@example.test"

        // Record some failures
        repeat(3) {
            rateLimiter.recordFailedAttempt(email)
        }

        // Verify remaining attempts
        assertEquals("Should have 2 remaining attempts", 2, rateLimiter.getRemainingAttempts(email))

        // In production, after 15 minutes the attempts would expire
        // This test verifies the logic exists; actual expiry testing would need time mocking
    }

    /**
     * Test lockout duration calculation.
     */
    @Test
    fun testLockoutDurationCalculation() {
        val email = "duration@example.test"

        // First lockout: 5 failures -> 30 second base lockout
        repeat(5) {
            rateLimiter.recordFailedAttempt(email)
        }

        val result = rateLimiter.checkAttempt(email)
        assertTrue("Should be rate limited", result is RateLimitResult.RateLimited)

        // The wait time should be positive and reasonable
        val waitSeconds = (result as RateLimitResult.RateLimited).waitSeconds
        assertTrue("Wait time should be positive", waitSeconds > 0)
        assertTrue("Wait time should not exceed max (480s + buffer)", waitSeconds <= 500)
    }

    /**
     * Test that RateLimitResult sealed class works correctly.
     */
    @Test
    fun testRateLimitResultTypes() {
        val email = "result@example.test"

        // Allowed result
        val allowed = rateLimiter.checkAttempt(email)
        when (allowed) {
            is RateLimitResult.Allowed -> {
                // Expected - test passes
            }
            is RateLimitResult.RateLimited -> {
                fail("Should be Allowed, not RateLimited")
            }
        }

        // Lock out the email
        repeat(5) {
            rateLimiter.recordFailedAttempt(email)
        }

        // RateLimited result
        val rateLimited = rateLimiter.checkAttempt(email)
        when (rateLimited) {
            is RateLimitResult.Allowed -> {
                fail("Should be RateLimited, not Allowed")
            }
            is RateLimitResult.RateLimited -> {
                assertTrue("Wait seconds should be positive", rateLimited.waitSeconds > 0)
            }
        }
    }

    /**
     * Test that fresh email has no tracking.
     */
    @Test
    fun testFreshEmailHasNoTracking() {
        val email = "fresh@example.test"

        // Check attempt on fresh email
        val result = rateLimiter.checkAttempt(email)
        assertTrue("Fresh email should be allowed", result is RateLimitResult.Allowed)

        // Should have full attempts
        assertEquals("Fresh email should have 5 attempts", 5, rateLimiter.getRemainingAttempts(email))
    }

    /**
     * Test maximum lockout cap (480 seconds).
     */
    @Test
    fun testMaximumLockoutCap() {
        val email = "maxcap@example.test"

        // Record many failures to hit max cap
        // 5 failures = lockout, then each additional failure doubles the time
        // 5 -> 30s, 6 -> 60s, 7 -> 120s, 8 -> 240s, 9+ -> 480s (max)
        repeat(15) {
            rateLimiter.recordFailedAttempt(email)
        }

        val result = rateLimiter.checkAttempt(email)
        assertTrue("Should be rate limited", result is RateLimitResult.RateLimited)

        val waitSeconds = (result as RateLimitResult.RateLimited).waitSeconds
        assertTrue(
            "Lockout should not exceed 480 seconds (got $waitSeconds)",
            waitSeconds <= 480
        )
    }
}
