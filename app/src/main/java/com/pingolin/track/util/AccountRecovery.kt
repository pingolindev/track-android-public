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

/**
 * Where the web account portal lives, and how to describe the recovery address
 * it manages.
 *
 * This file is deliberately inert. It performs no reset, derives nothing from a
 * password or an address, and holds no crypto: it turns a base URL into a page
 * URL and turns two fields of `GET /api/v1/auth/me` into something a screen can
 * render. Everything dangerous stays in the single web implementation.
 *
 * That is a rule, not a preference. Password reset binds SRP credentials to a
 * specific identity string and hashes an address into a token; a second
 * implementation that disagrees with the first by one byte does not fail
 * loudly, it locks the account out. Adding a reset here would add another
 * implementation that has to match the first byte for byte.
 *
 * The base URL comes from `BuildConfig.ACCOUNT_BASE_URL`, which the product
 * flavor sets (`account.pingolindev.com` for dev, `account.pingolin.com` for
 * prod) — the same field the sign-up screen's "Request a reviewed account" link
 * uses. A prod build therefore cannot point at the dev portal.
 */
object AccountRecovery {

    /**
     * Where accounts are created. The app has no sign-up form: the portal owns
     * registration the same way it owns password reset, including the assisted
     * lane for people who cannot clear the security check, guardian-managed
     * signups, email verification, and the recovery key.
     */
    fun registerUrl(accountBaseUrl: String): String = page(accountBaseUrl, "register")

    /** The portal page that starts a password reset. */
    fun forgotPasswordUrl(accountBaseUrl: String): String = page(accountBaseUrl, "forgot-password")

    /**
     * The portal page where a recovery address is added, verified or removed.
     * Adding one requires being signed in and confirming the password, which is
     * why it is worth nothing unless it is set up BEFORE the lockout.
     */
    fun recoverySettingsUrl(accountBaseUrl: String): String = page(accountBaseUrl, "settings")

    /**
     * The portal page that manages the plan (upgrade, cancel, top up credits).
     * Only the libre distribution links to it from the premium prompt — see
     * [PlanManagement]; the Play build keeps the pointer as plain text.
     */
    fun subscriptionUrl(accountBaseUrl: String): String = page(accountBaseUrl, "subscription")

    /**
     * The portal page where two-factor methods are set up — where somebody whose
     * only second step is a passkey adds the authenticator app this app can use.
     */
    fun securitySettingsUrl(accountBaseUrl: String): String = page(accountBaseUrl, "security")

    /**
     * Where data from before an email password reset is restored with the recovery
     * key the account had then: the "Data from before your password reset" section
     * of the portal's Settings page (element id `pre-reset-data`, which Settings
     * scrolls to), shown only to accounts that have such data — without it the link
     * opens Settings.
     */
    fun preResetRestoreUrl(accountBaseUrl: String): String = page(accountBaseUrl, "settings#pre-reset-data")

    /**
     * The portal's public support page, which works signed out (`/public-support`): where a
     * turned-down applicant asks for help, as the portal's own under-review screen sends people.
     */
    fun publicSupportUrl(accountBaseUrl: String): String = page(accountBaseUrl, "public-support")

    private fun page(accountBaseUrl: String, path: String): String =
        accountBaseUrl.trimEnd('/') + "/" + path

    /**
     * What the server says about this account's recovery address.
     *
     * [Unknown] is a separate state on purpose. Reporting "not set up" when the
     * server simply could not be reached sends someone to add an address they
     * already have — and, worse, tells someone who genuinely has none that the
     * question has been answered.
     */
    sealed class Status {
        object Unknown : Status()
        object NotSet : Status()
        data class Pending(val address: String) : Status()
        data class Verified(val address: String) : Status()
    }

    /**
     * Read the two `/me` fields.
     *
     * The server leaves `recovery_email` out when it is unset, so an account
     * with no recovery address has no such field and the parser hands back
     * null. A blank string is treated the same way rather than rendered as an
     * address.
     */
    fun statusOf(recoveryEmail: String?, verified: Boolean): Status {
        val address = recoveryEmail?.trim().orEmpty()
        if (address.isEmpty()) return Status.NotSet
        return if (verified) Status.Verified(address) else Status.Pending(address)
    }
}
