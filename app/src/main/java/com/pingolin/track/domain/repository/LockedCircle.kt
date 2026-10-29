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

package com.pingolin.track.domain.repository

import androidx.annotation.StringRes
import com.pingolin.track.R

/**
 * A circle this device cannot open: the one name it shows, and why it is locked.
 *
 * The explanation is chosen by what actually happened to this circle's key on this
 * device, and an unreadable circle looks the same on every platform. One
 * explanation for every cause ("Waiting for the circle owner … your key for it is
 * created on their device") would tell an owner who reset their password by email
 * to wait for themselves, while the real way back (their old recovery key, in the
 * account portal) goes unmentioned — and a circle that looks empty is exactly what
 * persuades somebody to throw that recovery key away.
 */
object LockedCircle {

    /** The name a locked circle shows. The iPhone app uses the same words. */
    const val PLACEHOLDER_NAME = "Locked circle"

    /** What older builds stored as a locked circle's name; rows written by them persist. */
    private const val LEGACY_PLACEHOLDER_NAME = "Circle (key unavailable)"

    /** A legitimate circle name is never a 32+ character all-hex string. */
    private val HEX_PATTERN = Regex("^[0-9a-fA-F]{32,}$")

    /**
     * Whether [name] is a placeholder rather than a decrypted name: this version's,
     * an older app version's, or a raw shape an older version stored for a failed
     * decrypt.
     *
     * A NAME test, and so only a fallback: somebody can call their circle "Locked
     * circle". CircleRepository.isCircleLocked prefers what this process actually
     * decrypted, and only falls back to this for rows it has not looked at yet.
     */
    fun isPlaceholder(name: String): Boolean =
        name == PLACEHOLDER_NAME ||
            name == LEGACY_PLACEHOLDER_NAME ||
            name.startsWith("[Encrypted]") ||
            name.startsWith("[Key") ||
            HEX_PATTERN.matches(name)

    /** What happened to this circle's key the last time this device looked. */
    enum class WrapOutcome {
        /** The server holds no key for us in this circle: we joined without one. */
        NO_WRAP,

        /** A key was there, but its signature did not verify, so it was refused. */
        REFUSED,

        /** A key was there and does not open with this account's keys. */
        UNOPENABLE,
    }

    enum class Cause {
        /** Joined without a key; the circle's owner or an admin has to give us access. */
        AWAITING_ACCESS,

        /** Our copy of the key failed verification and was refused. */
        KEY_REFUSED,

        /** Our copy is locked to this account's keys from before an email password reset. */
        OWN_RESET,

        /** Locked for a reason this device cannot name. */
        UNREADABLE,

        /**
         * The circle's key was renewed and the new one has not reached this device, so
         * nothing is shared with the circle from here (CurrentKeyGate).
         */
        BEHIND,
    }

    /**
     * [outcome] is what the last look at the circle found (null when this process has
     * not looked yet); [preResetDataExists] whether the account has data from before an
     * email reset that its old recovery key can restore (null when that is unknown).
     *
     * A key that does not open is blamed on the reset only when the account really had
     * one. Anything else would send somebody hunting for a recovery key that cannot help.
     */
    fun causeOf(outcome: WrapOutcome?, preResetDataExists: Boolean?): Cause = when (outcome) {
        WrapOutcome.NO_WRAP -> Cause.AWAITING_ACCESS
        WrapOutcome.REFUSED -> Cause.KEY_REFUSED
        WrapOutcome.UNOPENABLE, null ->
            if (preResetDataExists == true) Cause.OWN_RESET else Cause.UNREADABLE
    }

    /** The dialog that explains a locked circle; both texts take the circle's name as %1$s. */
    data class Explanation(
        @StringRes val title: Int,
        @StringRes val body: Int,
        /** Offer the account portal's "Data from before your password reset" restore. */
        val offersRestore: Boolean,
        /** What [body] names instead of the circle: whom a late joiner's device says was asked. */
        val bodyName: String? = null,
    )

    /**
     * This account's invitation ran out before any phone handed it the circle: whom that asks to let it in,
     * by name as this device's member list has it, or null when it has none for them.
     */
    data class RanOut(val askedName: String?)

    /**
     * The explanation for [cause]. None of them talks about keys, and none sends
     * anybody to an owner or an admin: another member's app, online, is what opens the
     * circle now. An owner never "joined" their own circle, so a missing copy there is
     * told as waiting to sync.
     */
    fun explanationFor(cause: Cause, isOwner: Boolean, ranOut: RanOut? = null): Explanation = when (cause) {
        Cause.OWN_RESET -> Explanation(
            R.string.locked_circle_reset_title,
            R.string.locked_circle_reset_body,
            offersRestore = true,
        )
        // The late joiner's first case: this account's invitation ran out before any phone handed it
        // the circle, and [ranOut] says whom that asked to let it in. With no name here for them,
        // the line names nobody, never a stand-in such as "A member".
        Cause.AWAITING_ACCESS -> if (isOwner) waitingToSync() else if (ranOut != null) Explanation(
            R.string.locked_circle_awaiting_title,
            if (ranOut.askedName != null) R.string.late_joiner_waiting_body else R.string.late_joiner_waiting_body_unnamed,
            offersRestore = false,
            bodyName = ranOut.askedName,
        ) else Explanation(
            R.string.locked_circle_awaiting_title,
            R.string.locked_circle_awaiting_body,
            offersRestore = false,
        )
        // One wording for behind, refused and unopenable alike.
        Cause.KEY_REFUSED, Cause.UNREADABLE, Cause.BEHIND -> waitingToSync()
    }

    private fun waitingToSync() = Explanation(
        R.string.locked_circle_sync_title,
        R.string.locked_circle_sync_body,
        offersRestore = false,
    )
}
