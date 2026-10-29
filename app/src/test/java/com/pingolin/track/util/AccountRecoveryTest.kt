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

import com.pingolin.track.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recovery link-outs.
 *
 * Two things are worth holding still here: the URLs must follow the build
 * environment (a prod build pointing users at the dev portal is a real defect,
 * not a cosmetic one), and "we could not ask" must never be rendered as "you
 * have no recovery address".
 */
class AccountRecoveryTest {

    @Test
    fun `portal pages are appended to the configured base url`() {
        assertEquals(
            "https://account.pingolindev.com/forgot-password",
            AccountRecovery.forgotPasswordUrl("https://account.pingolindev.com/")
        )
        assertEquals(
            "https://account.pingolindev.com/settings",
            AccountRecovery.recoverySettingsUrl("https://account.pingolindev.com/")
        )
        assertEquals(
            "https://account.pingolindev.com/subscription",
            AccountRecovery.subscriptionUrl("https://account.pingolindev.com/")
        )
        // Straight to the portal's restore section (#pre-reset-data).
        assertEquals(
            "https://account.pingolin.com/settings#pre-reset-data",
            AccountRecovery.preResetRestoreUrl("https://account.pingolin.com/")
        )
    }

    /** ACCOUNT_BASE_URL carries a trailing slash in both flavors; a base without
     *  one must not produce a different URL. */
    @Test
    fun `a base url without a trailing slash yields the same page url`() {
        assertEquals(
            AccountRecovery.forgotPasswordUrl("https://account.pingolin.com/"),
            AccountRecovery.forgotPasswordUrl("https://account.pingolin.com")
        )
    }

    /**
     * The URLs come from BuildConfig, so a prod build cannot address the
     * dev portal and vice versa. This is the assertion that catches a
     * hardcoded dev host before it ships to prod users.
     */
    @Test
    fun `portal host follows the build environment`() {
        val forgot = AccountRecovery.forgotPasswordUrl(BuildConfig.ACCOUNT_BASE_URL)

        when (BuildConfig.ENVIRONMENT) {
            "DEV" -> assertTrue(
                "A dev build must address the dev portal, was $forgot",
                forgot.startsWith("https://account.pingolindev.com/")
            )
            "PROD" -> assertTrue(
                "A prod build must NOT address the dev portal, was $forgot",
                forgot.startsWith("https://account.pingolin.com/")
            )
            else -> throw AssertionError("Unknown environment: ${BuildConfig.ENVIRONMENT}")
        }
    }

    @Test
    fun `an absent recovery address reads as not set`() {
        assertEquals(AccountRecovery.Status.NotSet, AccountRecovery.statusOf(null, false))
        assertEquals(AccountRecovery.Status.NotSet, AccountRecovery.statusOf("", false))
        assertEquals(AccountRecovery.Status.NotSet, AccountRecovery.statusOf("   ", false))
    }

    /**
     * A verified flag with no address is still "not set". The server cannot
     * currently produce that pair, but rendering "✓ verified" with nothing to
     * name would be an assurance about an address that does not exist.
     */
    @Test
    fun `verified with no address is still not set`() {
        assertEquals(AccountRecovery.Status.NotSet, AccountRecovery.statusOf(null, true))
    }

    @Test
    fun `an unverified address is pending, not verified`() {
        assertEquals(
            AccountRecovery.Status.Pending("backup@example.com"),
            AccountRecovery.statusOf("backup@example.com", false)
        )
    }

    @Test
    fun `a verified address is verified`() {
        assertEquals(
            AccountRecovery.Status.Verified("backup@example.com"),
            AccountRecovery.statusOf("backup@example.com", true)
        )
    }

    /**
     * Unknown is produced by the caller when /me could not be read, and it is
     * NOT one of the states statusOf can return — statusOf only ever sees an
     * answer. Guards against someone later "simplifying" Unknown away into
     * NotSet, which would tell a user with a perfectly good recovery address
     * that they have none every time they opened Settings offline.
     */
    @Test
    fun `statusOf never returns Unknown`() {
        val everyInput = listOf<Pair<String?, Boolean>>(
            null to false, null to true, "" to false, "" to true,
            "a@b.com" to false, "a@b.com" to true,
        )
        for ((email, verified) in everyInput) {
            assertTrue(
                "statusOf($email, $verified) returned Unknown",
                AccountRecovery.statusOf(email, verified) != AccountRecovery.Status.Unknown
            )
        }
    }
}
