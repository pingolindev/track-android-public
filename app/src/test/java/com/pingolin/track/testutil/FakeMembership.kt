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

package com.pingolin.track.testutil

import com.pingolin.track.domain.repository.MemberPins
import com.pingolin.track.domain.repository.MembershipStatements
import com.pingolin.track.domain.repository.MembershipStatements.Kind

/**
 * Signed membership statements with the SDK's shape, minus the cryptography: a statement
 * is "kind|circle|id|signer|subject|sharing|signing|email|atVersion|expiresAt", then
 * "|issuedAt" when a test gives one, and its signature is "<signer's signing key>:<statement>",
 * so it verifies under exactly one key.
 * What this device signs is signed with [selfSigningKey] and stamped with [activeVersion].
 */
class FakeMembershipStatements(
    var selfSigningKey: String = "me-s",
    var activeVersion: Int = 1,
    var now: Long = 1_800_000_000L,
) : MembershipStatements {

    private var nextId = 0

    fun statement(
        kind: Kind,
        circleId: String,
        signer: String,
        signerKey: String = "$signer-s",
        subject: String? = null,
        sharing: String? = subject?.let { "$it-x" },
        signing: String? = subject?.let { "$it-s" },
        email: String? = null,
        id: String = "st-${nextId++}",
        atVersion: Int = activeVersion,
        expiresAt: Long = if (kind == Kind.ADMISSION) 0 else now + 7 * 86_400,
        issuedAt: Long? = null,
    ): MembershipStatements.Signed {
        val bytes = listOfNotNull(
            kind.name, circleId, id, signer, subject.orEmpty(), sharing.orEmpty(), signing.orEmpty(),
            email.orEmpty(), atVersion.toString(), expiresAt.toString(), issuedAt?.toString(),
        ).joinToString("|")
        return MembershipStatements.Signed(bytes, "$signerKey:$bytes")
    }

    override fun parse(statementHex: String): MembershipStatements.Statement? {
        val f = statementHex.split("|")
        if (f.size != 10 && f.size != 11) return null
        return MembershipStatements.Statement(
            kind = runCatching { Kind.valueOf(f[0]) }.getOrNull() ?: return null,
            circleId = f[1], statementId = f[2], signerUserId = f[3],
            subjectUserId = f[4].ifEmpty { null }, subjectSharingKeyHex = f[5].ifEmpty { null },
            subjectSigningKeyHex = f[6].ifEmpty { null }, subjectEmail = f[7].ifEmpty { null },
            atVersion = f[8].toInt(), expiresAt = f[9].toLong(), issuedAt = f.getOrNull(10)?.toLong() ?: 0,
        )
    }

    override fun verify(statementHex: String, signatureHex: String, signerSigningKeyHex: String) =
        parse(statementHex) != null && signatureHex == "$signerSigningKeyHex:$statementHex"

    /** Signing keys of this account's identities from before a reset, restored here. */
    val previousSigningKeys = mutableSetOf<String>()

    override fun signedByOwnPreviousKey(statementHex: String, signatureHex: String) =
        previousSigningKeys.any { verify(statementHex, signatureHex, it) } && !verify(statementHex, signatureHex, selfSigningKey)

    /** Everything this device signed, in order. */
    val signed = mutableListOf<MembershipStatements.Signed>()

    override fun signInvitationToAccount(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ) = statement(Kind.INVITATION_TO_ACCOUNT, circleId, signerUserId, selfSigningKey, subjectUserId, subjectSharingKeyHex, subjectSigningKeyHex)
        .also { signed += it }

    override fun signInvitationToEmail(circleId: String, signerUserId: String, email: String) =
        statement(Kind.INVITATION_TO_EMAIL, circleId, signerUserId, selfSigningKey, email = email.trim().lowercase())
            .also { signed += it }

    override fun signAdmission(
        circleId: String,
        signerUserId: String,
        subjectUserId: String,
        subjectSharingKeyHex: String,
        subjectSigningKeyHex: String,
    ) = statement(Kind.ADMISSION, circleId, signerUserId, selfSigningKey, subjectUserId, subjectSharingKeyHex, subjectSigningKeyHex)
        .also { signed += it }

    /** The SDK's rules, minus the cryptography: refuses unless they all hold. */
    override fun renewInvitation(
        circleId: String,
        renewerUserId: String,
        original: MembershipStatements.Signed,
        newSharingKeyHex: String,
        newSigningKeyHex: String,
        facts: MembershipStatements.RenewalFacts,
    ): MembershipStatements.Signed {
        val o = parse(original.statementHex) ?: throw IllegalArgumentException("malformed")
        require(o.kind == Kind.INVITATION_TO_ACCOUNT) { "address invitations are never renewed" }
        require(o.circleId == circleId) { "another circle" }
        // Under this account's own key: the renewals this app makes are of its own
        // invitations, made before a reset too (a restored identity's key). The renewal
        // is signed with the current key.
        require(
            o.signerUserId == renewerUserId && (
                verify(original.statementHex, original.signatureHex, selfSigningKey) ||
                    previousSigningKeys.any { verify(original.statementHex, original.signatureHex, it) }
                ),
        ) { "signature" }
        require(facts.originalSignerIsOwnerOrAdminNow && facts.renewerIsOwnerOrAdmin) { "not an owner or admin now" }
        require(!facts.subjectDepartedOrTombstoned && !facts.invitationWithdrawnOrDead) { "facts" }
        require(o.expiresAt > now) { "expired" }
        require(!(o.subjectSharingKeyHex == newSharingKeyHex && o.subjectSigningKeyHex == newSigningKeyHex)) { "the keys do not change" }
        return statement(
            Kind.INVITATION_TO_ACCOUNT, circleId, renewerUserId, selfSigningKey, o.subjectUserId,
            newSharingKeyHex, newSigningKeyHex, id = o.statementId, atVersion = activeVersion, expiresAt = o.expiresAt,
        ).also { signed += it; renewed += it }
    }

    /** Every renewal this device signed, in order. */
    val renewed = mutableListOf<MembershipStatements.Signed>()
}

/** This device's pins as plain maps: (circle, user) → key; identity pins by user. */
class FakeMemberPins : MemberPins {
    val sharing = mutableMapOf<String, String>()
    val signing = mutableMapOf<String, String>()

    fun pin(userId: String, sharingKey: String = "$userId-x", signingKey: String = "$userId-s") {
        sharing[userId] = sharingKey
        signing[userId] = signingKey
    }

    override fun pinnedSharingKey(circleId: String, userId: String) = sharing[userId]
    override fun pinnedSigningKey(circleId: String, userId: String) = signing[userId]
}
