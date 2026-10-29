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

import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MembershipStatementKind
import uniffi.pingolin_mobile.SignedMembershipStatement

/**
 * [MembershipStatements], [MemberPins] and [FirstKeys] on the SDK. The SDK throws on
 * malformed input; here that reads as "not a statement" / "does not verify", which the
 * confirmation rules treat as invalid — never as valid.
 */
class SdkMembership(private val crypto: CryptoManager) : MembershipStatements, MemberPins, FirstKeys {

    override fun parse(statementHex: String): MembershipStatements.Statement? = try {
        val s = crypto.parseMembershipStatement(statementHex)
        MembershipStatements.Statement(
            kind = when (s.kind) {
                MembershipStatementKind.INVITATION_TO_ACCOUNT -> MembershipStatements.Kind.INVITATION_TO_ACCOUNT
                MembershipStatementKind.INVITATION_TO_EMAIL -> MembershipStatements.Kind.INVITATION_TO_EMAIL
                MembershipStatementKind.ADMISSION -> MembershipStatements.Kind.ADMISSION
            },
            circleId = s.circleId,
            statementId = s.statementId,
            signerUserId = s.signerUserId,
            issuedAt = s.issuedAt,
            expiresAt = s.expiresAt,
            atVersion = s.atVersion,
            subjectUserId = s.subjectUserId,
            subjectSharingKeyHex = s.subjectSharingPublicKeyHex,
            subjectSigningKeyHex = s.subjectSigningPublicKeyHex,
            subjectEmail = s.subjectEmail,
        )
    } catch (_: Exception) {
        null
    }

    override fun verify(statementHex: String, signatureHex: String, signerSigningKeyHex: String): Boolean = try {
        crypto.verifyMembershipStatement(statementHex, signatureHex, signerSigningKeyHex)
    } catch (_: Exception) {
        false
    }

    override fun signedByOwnPreviousKey(statementHex: String, signatureHex: String): Boolean = try {
        crypto.verifyOwnMembershipStatement(statementHex, signatureHex) == uniffi.pingolin_mobile.OwnStatementSigner.PREVIOUS_KEY
    } catch (_: Exception) {
        false
    }

    override fun signInvitationToAccount(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ) = crypto.signInvitationToAccount(circleId, signerUserId, subjectUserId, subjectSharingKeyHex, subjectSigningKeyHex).signed()

    override fun signInvitationToEmail(circleId: String, signerUserId: String, email: String) =
        crypto.signInvitationToEmail(circleId, signerUserId, email).signed()

    override fun signAdmission(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ) = crypto.signMembershipAdmission(circleId, signerUserId, subjectUserId, subjectSharingKeyHex, subjectSigningKeyHex).signed()

    override fun pinnedSharingKey(circleId: String, userId: String): String? =
        runCatching { crypto.pinnedSharingKey(circleId, userId) }.getOrNull()

    override fun pinnedSigningKey(circleId: String, userId: String): String? =
        runCatching { crypto.pinnedSigningKey(circleId, userId) }.getOrNull()

    /** The SDK matches the sharing key; a previous identity's signing key is not recovered. */
    override fun isOwnPreviousIdentity(sharingKeyHex: String, signingKeyHex: String): Boolean =
        runCatching { crypto.holdsPreviousIdentity(sharingKeyHex) }.getOrDefault(false)

    override fun storeFirstCircleKey(
        circleId: String,
        encryptedKey: String,
        senderPublicKey: String,
        keyVersion: Int,
        senderUserId: String,
        recipientUserId: String,
        wrapSignatureHex: String,
        confirmedSenderSigningPubkeyHex: String,
        sealedCircleName: uniffi.pingolin_mobile.EncryptedData?,
    ) = crypto.storeFirstCircleKey(
        circleId, encryptedKey, senderPublicKey, keyVersion, senderUserId, recipientUserId,
        wrapSignatureHex, confirmedSenderSigningPubkeyHex, sealedCircleName,
    )

    override fun renewInvitation(
        circleId: String,
        renewerUserId: String,
        original: MembershipStatements.Signed,
        newSharingKeyHex: String,
        newSigningKeyHex: String,
        facts: MembershipStatements.RenewalFacts,
    ) = crypto.renewInvitation(
        circleId, renewerUserId, original.statementHex, original.signatureHex, newSharingKeyHex, newSigningKeyHex,
        uniffi.pingolin_mobile.InvitationRenewalFacts(
            facts.originalSignerIsOwnerOrAdminNow, facts.renewerIsOwnerOrAdmin,
            facts.subjectDepartedOrTombstoned, facts.invitationWithdrawnOrDead,
        ),
    ).signed()

    private fun SignedMembershipStatement.signed() = MembershipStatements.Signed(statementHex, signatureHex)
}
