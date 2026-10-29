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

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side rate limiter for authentication attempts.
 *
 * Prevents brute force attacks by limiting login attempts with exponential backoff.
 * This complements server-side rate limiting and provides immediate feedback to users.
 *
 * Security properties:
 * - 5 failed attempts triggers lockout
 * - Exponential backoff: 30s, 60s, 120s, 240s, 480s (max)
 * - Per-email tracking prevents lockout bypass with different emails
 * - Automatically clears after successful login
 *
 * Thread Safety:
 * - Uses ConcurrentHashMap for lock-free reads
 * - Uses compute() for atomic updates to prevent race conditions
 */
@Singleton
class AuthRateLimiter @Inject constructor() {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_LOCKOUT_MS = 30_000L  // 30 seconds
        private const val MAX_LOCKOUT_MS = 480_000L     // 8 minutes
        private const val ATTEMPT_WINDOW_MS = 900_000L  // 15 minutes - attempts expire after this
    }

    /**
     * Tracks failed attempts for an email.
     */
    private data class AttemptRecord(
        val failedAttempts: Int,
        val lastAttemptTime: Long,
        val lockoutUntil: Long
    )

    // Track attempts per email (normalized to lowercase)
    // Uses ConcurrentHashMap for thread-safe operations without locking on reads
    private val attemptRecords = ConcurrentHashMap<String, AttemptRecord>()

    /**
     * Check if login attempt is allowed for this email.
     *
     * @return RateLimitResult indicating if attempt is allowed and wait time if not
     */
    fun checkAttempt(email: String): RateLimitResult {
        val normalizedEmail = email.lowercase().trim()
        val now = System.currentTimeMillis()
        val record = attemptRecords[normalizedEmail]

        // No record or expired attempts - allow
        if (record == null || (now - record.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
            return RateLimitResult.Allowed
        }

        // Currently locked out
        if (now < record.lockoutUntil) {
            val waitSeconds = ((record.lockoutUntil - now) / 1000).toInt()
            return RateLimitResult.RateLimited(waitSeconds)
        }

        // Under attempt limit
        if (record.failedAttempts < MAX_ATTEMPTS) {
            return RateLimitResult.Allowed
        }

        // Lockout expired, allow retry
        return RateLimitResult.Allowed
    }

    /**
     * Record a failed login attempt.
     * Call this when authentication fails (wrong password, etc.)
     * Uses compute() for atomic update to prevent race conditions.
     */
    fun recordFailedAttempt(email: String) {
        val normalizedEmail = email.lowercase().trim()
        val now = System.currentTimeMillis()

        attemptRecords.compute(normalizedEmail) { _, existingRecord ->
            val newAttempts: Int
            val lockoutUntil: Long

            if (existingRecord == null || (now - existingRecord.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
                // Start fresh
                newAttempts = 1
                lockoutUntil = 0L
            } else {
                newAttempts = existingRecord.failedAttempts + 1
                lockoutUntil = if (newAttempts >= MAX_ATTEMPTS) {
                    // Calculate exponential backoff
                    val lockoutMs = calculateLockoutDuration(newAttempts)
                    now + lockoutMs
                } else {
                    0L
                }
            }

            AttemptRecord(
                failedAttempts = newAttempts,
                lastAttemptTime = now,
                lockoutUntil = lockoutUntil
            )
        }
    }

    /**
     * Record a successful login - clears the attempt record.
     */
    fun recordSuccess(email: String) {
        val normalizedEmail = email.lowercase().trim()
        attemptRecords.remove(normalizedEmail)
    }

    /**
     * Clear all attempt records (e.g., on app restart).
     */
    fun clearAll() {
        attemptRecords.clear()
    }

    /**
     * Get remaining attempts before lockout.
     */
    fun getRemainingAttempts(email: String): Int {
        val normalizedEmail = email.lowercase().trim()
        val now = System.currentTimeMillis()
        val record = attemptRecords[normalizedEmail]

        if (record == null || (now - record.lastAttemptTime) > ATTEMPT_WINDOW_MS) {
            return MAX_ATTEMPTS
        }

        return (MAX_ATTEMPTS - record.failedAttempts).coerceAtLeast(0)
    }

    /**
     * Calculate lockout duration with exponential backoff.
     */
    private fun calculateLockoutDuration(attempts: Int): Long {
        val overLimit = attempts - MAX_ATTEMPTS
        val multiplier = (1 shl overLimit.coerceIn(0, 4)) // 2^0, 2^1, 2^2, 2^3, 2^4
        return (INITIAL_LOCKOUT_MS * multiplier).coerceAtMost(MAX_LOCKOUT_MS)
    }
}

/**
 * Result of rate limit check.
 */
sealed class RateLimitResult {
    /** Attempt is allowed */
    object Allowed : RateLimitResult()

    /** Rate limited - must wait before next attempt */
    data class RateLimited(val waitSeconds: Int) : RateLimitResult()
}
