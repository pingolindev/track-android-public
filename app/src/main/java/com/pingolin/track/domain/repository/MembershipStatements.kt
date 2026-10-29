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

/**
 * Signed membership statements, made and checked by the SDK: who invited whom, and who
 * vouched for whom, signed with the signer's identity signing key. Behind an interface
 * so the confirmation rules ([MemberConfirmation]) are tested on their own;
 * [SdkMembershipStatements] is the production implementation.
 */
interface MembershipStatements {

    enum class Kind { INVITATION_TO_ACCOUNT, INVITATION_TO_EMAIL, ADMISSION }

    /** The signed fields, as parsed from the canonical bytes. */
    data class Statement(
        val kind: Kind,
        val circleId: String,
        val statementId: String,
        val signerUserId: String,
        /** Unix seconds; invitations expire 7 days after they were made, admissions never (0). */
        val issuedAt: Long = 0,
        val expiresAt: Long = 0,
        /** The signer's active version of the circle when signing. */
        val atVersion: Int = 0,
        val subjectUserId: String? = null,
        val subjectSharingKeyHex: String? = null,
        val subjectSigningKeyHex: String? = null,
        val subjectEmail: String? = null,
    )

    /** What travels: the exact bytes that were signed, and the signature. */
    data class Signed(val statementHex: String, val signatureHex: String)

    /** The fields, without verifying anything; null when the bytes are malformed. */
    fun parse(statementHex: String): Statement?

    /**
     * True only when the statement is well-formed and its signature verifies under
     * [signerSigningKeyHex]. Pass a PINNED key, never one the server sent next to it.
     */
    fun verify(statementHex: String, signatureHex: String, signerSigningKeyHex: String): Boolean

    /**
     * Whether THIS account signed it before a password reset, with the signing key of an
     * identity restored on this device from the portal's v2 blob (the SDK's
     * `verifyOwnMembershipStatement` says PREVIOUS_KEY). False for the current key, anyone
     * else's, and anything malformed. Such a statement confirms; it never makes anyone this
     * user's own choice, and nothing is handed over or vouched for on it.
     */
    fun signedByOwnPreviousKey(statementHex: String, signatureHex: String): Boolean = false

    // The signers stamp the statement with this device's active version of the circle
    // (at_version) and need its key: never call them while this device is behind.

    fun signInvitationToAccount(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ): Signed

    fun signInvitationToEmail(circleId: String, signerUserId: String, email: String): Signed

    fun signAdmission(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ): Signed

    /**
     * What this device knows about an invitation it is asked to renew, as the circle is NOW:
     * owner or admin if this device created the circle, else by the circle list's role —
     * nothing on a device proves admin, and an admission has no role byte.
     */
    data class RenewalFacts(
        /** The original's signer is an owner or admin now and has not departed. */
        val originalSignerIsOwnerOrAdminNow: Boolean,
        /** This account is an owner or admin now. */
        val renewerIsOwnerOrAdmin: Boolean,
        val subjectDepartedOrTombstoned: Boolean,
        val invitationWithdrawnOrDead: Boolean,
    )

    /**
     * Renew an ACCOUNT invitation (kind 1) for the keys "Is this really {email}?" showed,
     * after "It's them": the subject's keys become these, the signer this account and
     * at_version this device's; the statement id, times, kind and subject user id stay the
     * ORIGINAL's. Throws — signing nothing — unless the original verifies under its signer's
     * pinned key, the [facts] hold, it has not expired and the keys really change. An address
     * invitation (kind 2) is never renewed.
     */
    fun renewInvitation(
        circleId: String,
        renewerUserId: String,
        original: Signed,
        newSharingKeyHex: String,
        newSigningKeyHex: String,
        facts: RenewalFacts,
    ): Signed = throw UnsupportedOperationException("This implementation renews nothing")
}

/**
 * This device's pins, read without recording anything: pinMemberKeys records a first pin
 * as a side effect, so it runs only once a member is confirmed. The circle's own pin
 * first, then the user's identity pin — a key lookup, never a verdict on trust.
 */
interface MemberPins {
    fun pinnedSharingKey(circleId: String, userId: String): String?
    fun pinnedSigningKey(circleId: String, userId: String): String?

    /** Keys of a previous identity of this account whose private key this device holds. */
    fun isOwnPreviousIdentity(sharingKeyHex: String, signingKeyHex: String): Boolean = false
}

/**
 * A circle's FIRST key, taken only from a member this app confirmed: its wrap must verify
 * under [confirmedSenderSigningPubkeyHex] — the confirmed sender's pinned key, never one
 * the server sent — and, when [sealedCircleName] is given (the name sealed at
 * [keyVersion]), the key must open it. Throws on any refusal, and stores nothing then.
 */
interface FirstKeys {
    fun storeFirstCircleKey(
        circleId: String,
        encryptedKey: String,
        senderPublicKey: String,
        keyVersion: Int,
        senderUserId: String,
        recipientUserId: String,
        wrapSignatureHex: String,
        confirmedSenderSigningPubkeyHex: String,
        sealedCircleName: uniffi.pingolin_mobile.EncryptedData?,
    )
}
