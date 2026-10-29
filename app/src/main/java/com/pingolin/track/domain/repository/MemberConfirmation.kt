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

import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.util.KeyAccessApprovals.Origin

/**
 * Which members of a circle this device trusts, and what it may do for them. Pure, so every
 * branch is tested.
 *
 * Starting points: this device, its approvals (see KeyAccessApprovals — seeds from before signed
 * invitations stay, and nothing is seeded by trust on first use) and, only on a device with no
 * such seed for the circle, ANCHORS: whoever signed THIS user's own membership statement, when
 * it names this user with this device's keys (or keys of a previous identity whose private key
 * this device holds; an invitation to an email: this account's email), and members this device
 * already trusts in ANOTHER circle — by its own approval records, never by a pin alone (a pin
 * may have been taken for any listed member, ghosts included) — listed with the keys pinned for
 * them. From there a fixpoint: a member is confirmed by a statement that holds up.
 *
 * A statement holds up when it parses; is about this circle; names this row's member (kinds 1
 * and 3); its id is not a departed member's; an email invitation's id is on no other row; its
 * signer is someone else who is confirmed here and not receive-only; it verifies under the
 * signer's PINNED key (this user's own, for their own statements — or, for one made before a
 * password reset, a restored identity's signing key, which confirms but is never what a
 * hand-over, an approval or a vouch rests on; a signer confirmed in this pass and not pinned yet
 * is taken with the key the member list shows, which is what gets pinned for them); for a member
 * who left and came back, it was made at the version they left at or later; and, when this
 * device has pinned the member, it binds exactly those keys. With no pin, the keys it binds are
 * what gets pinned: they are the keys its signer saw, whatever the member list shows now.
 *
 * Everyone else:
 *  - INVALID — hidden everywhere: provably false here (bad signature under the signer's pin,
 *    another circle, another member, a departed member's id, a reused email-invitation id, too
 *    old for a returning member, a statement about oneself).
 *  - UNCONFIRMABLE — hidden: its signer is not confirmed here or is receive-only
 *    ([Result.signerUnconfirmed]; an owner/admin device asks about the member, unless all they
 *    have is an email invitation), or its signer's key changed since this device pinned it
 *    ([Result.signerChanged]; resolved by confirming the signer).
 *  - STATEMENT-LESS — hidden on every device (an invitation from an app older than signed
 *    invitations), and counted as kept out on owner/admin devices. Nobody is asked.
 *  - SUBJECT CONFLICT — shown, with "Is this really {name}?": a statement binds keys other than
 *    those this device pinned for them. Never confirmed that way, never re-pinned by it.
 */
internal object MemberConfirmation {

    data class Member(
        val userId: String,
        val sharingKeyHex: String?,
        val signingKeyHex: String?,
        /** The version of this member's stored copy; null when they hold none. */
        val keyVersion: Int? = null,
        val statements: List<MembershipStatements.Signed> = emptyList(),
    )

    /** What this device knows that the member list does not say. */
    data class Known(
        val approvals: Map<String, Origin> = emptyMap(),
        /** This device had approvals for the circle from before signed invitations. */
        val preRound2Seed: Boolean = false,
        /** Departed members: user id → this device's version of the circle when they left. */
        val tombstones: Map<String, Int> = emptyMap(),
        val tombstonedStatementIds: Set<String> = emptySet(),
        /** This device's pins: the circle pin, else the identity pin. A lookup, not trust. */
        val pinnedSharingKey: (String) -> String? = { null },
        val pinnedSigningKey: (String) -> String? = { null },
        /** Keys of a previous identity of this account whose PRIVATE key this device holds. */
        val isOwnPreviousIdentity: (sharingHex: String, signingHex: String) -> Boolean = { _, _ -> false },
        /** Members this device's own approval records trust in another circle; read only when needed. */
        val trustedElsewhere: () -> Set<String> = { emptySet() },
        /**
         * Listed members this account's own head vouched for, with the keys the vouch matched: the
         * continuity statement for this account's own devices.
         */
        val vouchedByOwnHead: Map<String, Pair<String, String>> = emptyMap(),
    )

    data class Result(
        /** Members this device trusts, itself included, with why. */
        val confirmed: Map<String, Origin>,
        /** Confirmed, or had receive-only lifted, in this pass: to record (and pin). */
        val newlyConfirmed: Map<String, Origin>,
        /** For members newly confirmed by an invitation to an account or an admission: the keys it binds. */
        val boundKeys: Map<String, Pair<String, String>>,
        val invalid: Set<String>,
        /** Unconfirmable: a statement's signer is not confirmed here, or is receive-only. */
        val signerUnconfirmed: Set<String>,
        /** Unconfirmable: a statement's signer signed with a key this device has not accepted. */
        val signerChanged: Set<String>,
        /** Of [signerUnconfirmed]: those with an invitation to an account or an admission. */
        val askSigner: Set<String>,
        val statementLess: Set<String>,
        val subjectConflict: Set<String>,
        /** The statements that held up, by member — what delivery decisions read. */
        val holding: Map<String, List<MembershipStatements.Statement>>,
        /** This user's own app delivers and refreshes nothing. */
        val selfReceiveOnly: Boolean,
        /** The ids of the well-formed statements each member carries, for their tombstone. */
        val statementIds: Map<String, List<String>> = emptyMap(),
        /**
         * Confirmed ONLY by this account's own statements from before a reset. Shown and
         * pinned, but never recorded as an approval, handed the circle or vouched for: an
         * approval would outlive the restored identity, and the backfill would then vouch for
         * them under the current key. Judged again on every pass instead.
         */
        val previousKeyOnly: Set<String> = emptySet(),
        /**
         * Members carrying an admission this account signed (under its current key, or a
         * restored one from before a reset) that binds the keys the member list shows.
         */
        val ownAdmissions: Set<String> = emptySet(),
        /**
         * This user's own row carries an invitation to an e-mail address, for this circle, that is not this
         * account's address as this device knows it: none is known, or another one is (the address changed
         * elsewhere, or the server sent none at sign-in). Its signer anchors nobody here, and nothing may
         * be taken on trust in its place: no first copy on first contact, no first sender anchored. The
         * circle waits until the address is known (the e-mail trust check, as on iOS).
         */
        val ownEmailInvitationUnconfirmed: Boolean = false,
    ) {
        /** Shown in lists, on the map, in counts and pickers. */
        val shown: Set<String> get() = confirmed.keys + subjectConflict

        /** Owner/admin devices ask about these once; member devices do nothing. */
        val askable: Set<String> get() = askSigner

        /**
         * "Pingolin kept {n} person(s) out …" on owner/admin devices, statement-less members
         * included: nobody is asked about them.
         */
        val keptOut: Set<String> get() = invalid + (signerUnconfirmed - askSigner) + statementLess

        /** Whether anyone besides this user is trusted here. */
        val anchored: Boolean get() = confirmed.size > 1

        fun receiveOnly(userId: String) = confirmed[userId].let { it == Origin.KIND2 || it == Origin.OWN_KIND2 }
    }

    private enum class Verdict { INVALID, SIGNER_UNCONFIRMED, SIGNER_CHANGED, CONFLICT }

    fun evaluate(
        selfUserId: String,
        selfSharingKeyHex: String?,
        selfSigningKeyHex: String?,
        selfEmail: String?,
        circleId: String,
        roster: List<Member>,
        known: Known,
        statements: MembershipStatements,
    ): Result {
        val self = selfUserId.lowercase()
        val byId = roster.associateBy { it.userId.lowercase() }
        val tombstones = known.tombstones.mapKeys { it.key.lowercase() }
        val deadIds = known.tombstonedStatementIds.map { it.lowercase() }.toSet()

        class Candidate(val signed: MembershipStatements.Signed, val parsed: MembershipStatements.Statement?)
        val candidates: Map<String, List<Candidate>> = roster.associate { m ->
            m.userId.lowercase() to m.statements.distinct().map { signed ->
                Candidate(signed, runCatching { statements.parse(signed.statementHex) }.getOrNull())
            }
        }
        // Reuse matters for email invitations only: kinds 1 and 3 name their member.
        val emailHolders = mutableMapOf<String, MutableSet<String>>()
        for ((userId, list) in candidates) for (c in list) {
            val p = c.parsed ?: continue
            if (p.kind == Kind.INVITATION_TO_EMAIL) emailHolders.getOrPut(p.statementId.lowercase()) { mutableSetOf() } += userId
        }
        val reusedEmailIds = emailHolders.filterValues { it.size > 1 }.keys

        val confirmed = linkedMapOf<String, Origin>(self to Origin.OWN)
        for ((id, origin) in known.approvals) {
            val lower = id.lowercase()
            if (lower in byId && lower != self) confirmed[lower] = origin
        }
        val newly = linkedMapOf<String, Origin>()
        val bound = mutableMapOf<String, Pair<String, String>>()

        fun receiveOnly(id: String) = confirmed[id] == Origin.KIND2 || confirmed[id] == Origin.OWN_KIND2

        fun same(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)

        fun bindsKeys(p: MembershipStatements.Statement, sharing: String?, signing: String?) =
            same(p.subjectSharingKeyHex, sharing) && same(p.subjectSigningKeyHex, signing)

        fun verifies(c: Candidate, key: String?) = key != null &&
            runCatching { statements.verify(c.signed.statementHex, c.signed.signatureHex, key) }.getOrDefault(false)

        /**
         * This account's own statement, signed before a password reset by a restored
         * identity's signing key ([MembershipStatements.signedByOwnPreviousKey]).
         */
        fun ownFromBeforeReset(c: Candidate) =
            runCatching { statements.signedByOwnPreviousKey(c.signed.statementHex, c.signed.signatureHex) }.getOrDefault(false)

        /**
         * The key a signer's statements are checked under; null when there is none to use.
         * A signer confirmed in this pass is not pinned yet: the key its own statement bound,
         * which is what gets pinned, else the one the member list shows.
         */
        fun signerKey(signer: String): String? = when (signer) {
            self -> selfSigningKeyHex
            else -> known.pinnedSigningKey(signer)
                ?: bound[signer]?.second
                ?: byId[signer]?.signingKeyHex?.takeIf { signer in confirmed }
        }

        fun aboutMe(p: MembershipStatements.Statement): Boolean = when (p.kind) {
            Kind.INVITATION_TO_EMAIL -> selfEmail != null && p.subjectEmail.equals(selfEmail.trim(), ignoreCase = true)
            else -> p.subjectUserId.equals(selfUserId, ignoreCase = true) && (
                bindsKeys(p, selfSharingKeyHex, selfSigningKeyHex) ||
                    (p.subjectSharingKeyHex != null && p.subjectSigningKeyHex != null &&
                        known.isOwnPreviousIdentity(p.subjectSharingKeyHex, p.subjectSigningKeyHex))
                )
        }

        if (!known.preRound2Seed) {
            // Anchors. Whoever signed this user's own statement: their key is the
            // one this device pinned, or the one the member list shows.
            for (c in candidates[self].orEmpty()) {
                val p = c.parsed ?: continue
                if (!p.circleId.equals(circleId, ignoreCase = true) || !aboutMe(p)) continue
                val signer = p.signerUserId.lowercase()
                if (signer == self || signer !in byId || signer in confirmed) continue
                if (verifies(c, known.pinnedSigningKey(signer) ?: byId[signer]?.signingKeyHex)) {
                    confirmed[signer] = Origin.ANCHOR
                    newly[signer] = Origin.ANCHOR
                }
            }
            // Members this account's own head vouches for: a fresh install of this
            // account takes its other devices' word, while the member list shows exactly
            // the keys the vouch matched.
            for ((id, keys) in known.vouchedByOwnHead) {
                val lower = id.lowercase()
                val m = byId[lower] ?: continue
                if (lower == self || lower in confirmed) continue
                if (same(keys.first, m.sharingKeyHex) && same(keys.second, m.signingKeyHex)) {
                    confirmed[lower] = Origin.ANCHOR
                    newly[lower] = Origin.ANCHOR
                }
            }
            // Members this device trusts in another circle, by its own records, listed here
            // with the keys it pinned for them. A pin alone is never trust.
            val elsewhere = known.trustedElsewhere().map { it.lowercase() }.toSet()
            for ((id, m) in byId) {
                if (id == self || id in confirmed || id !in elsewhere) continue
                if (same(known.pinnedSharingKey(id), m.sharingKeyHex) && same(known.pinnedSigningKey(id), m.signingKeyHex)) {
                    confirmed[id] = Origin.ANCHOR
                    newly[id] = Origin.ANCHOR
                }
            }
        }

        /** Null when [c] holds up for [member]; otherwise why not. */
        fun judge(member: Member, c: Candidate): Verdict? {
            val id = member.userId.lowercase()
            val p = c.parsed ?: return Verdict.INVALID
            if (!p.circleId.equals(circleId, ignoreCase = true)) return Verdict.INVALID
            val email = p.kind == Kind.INVITATION_TO_EMAIL
            if (!email && !p.subjectUserId.equals(member.userId, ignoreCase = true)) return Verdict.INVALID
            if (p.statementId.lowercase() in deadIds) return Verdict.INVALID
            if (email && p.statementId.lowercase() in reusedEmailIds) return Verdict.INVALID
            val signer = p.signerUserId.lowercase()
            if (signer == id) return Verdict.INVALID
            if (signer !in confirmed || receiveOnly(signer)) return Verdict.SIGNER_UNCONFIRMED
            val key = signerKey(signer) ?: return Verdict.SIGNER_UNCONFIRMED
            // This account's own statement from before a reset holds up under the
            // restored identity's signing key, with every other check below.
            if (!verifies(c, key) && !(signer == self && ownFromBeforeReset(c))) {
                // Under the key the member list shows now instead: the signer changed their
                // keys since this device pinned them. Never for this user's own statements.
                val current = byId[signer]?.signingKeyHex?.takeIf { signer != self }
                return if (!same(current, key) && verifies(c, current)) Verdict.SIGNER_CHANGED else Verdict.INVALID
            }
            tombstones[id]?.let { leftAt -> if (p.atVersion < leftAt) return Verdict.INVALID }
            if (!email) {
                val pinnedSharing = known.pinnedSharingKey(id)
                val pinnedSigning = known.pinnedSigningKey(id)
                if ((pinnedSharing != null || pinnedSigning != null) && !bindsKeys(p, pinnedSharing, pinnedSigning)) {
                    return Verdict.CONFLICT
                }
            }
            return null
        }

        /** Holds up only through a restored identity's signing key: this account's, from before a reset. */
        fun viaPreviousKey(c: Candidate): Boolean =
            c.parsed?.signerUserId.equals(selfUserId, ignoreCase = true) && !verifies(c, selfSigningKeyHex)

        val holding = mutableMapOf<String, List<MembershipStatements.Statement>>()
        val previousKeyOnly = mutableSetOf<String>()
        var changed = true
        while (changed) {
            changed = false
            for ((id, member) in byId) {
                if (id == self) continue
                val held = candidates[id].orEmpty().filter { judge(member, it) == null }
                val good = held.mapNotNull { it.parsed }
                // What hand-overs and vouching read: never an own statement from
                // before a reset.
                val evidence = held.filterNot(::viaPreviousKey).mapNotNull { it.parsed }
                if (evidence.isNotEmpty()) {
                    holding[id] = evidence
                    // A statement under the current key that holds up later in this pass.
                    previousKeyOnly -= id
                }
                if (id in confirmed) {
                    // An admission lifts receive-only; this user's own email invitee
                    // stays their own choice.
                    if (receiveOnly(id) && evidence.any { it.kind == Kind.ADMISSION }) {
                        val lifted = if (confirmed[id] == Origin.OWN_KIND2) Origin.OWN else Origin.STATEMENT
                        confirmed[id] = lifted
                        newly[id] = lifted
                        changed = true
                    }
                    continue
                }
                if (good.isEmpty()) continue
                // A statement under the current key first: it is what an approval rests on.
                val basis = evidence.ifEmpty { good }
                if (evidence.isEmpty()) previousKeyOnly += id
                val keyed = basis.firstOrNull { it.kind != Kind.INVITATION_TO_EMAIL }
                val origin = if (keyed == null) Origin.KIND2 else Origin.STATEMENT
                confirmed[id] = origin
                newly[id] = origin
                if (keyed?.subjectSharingKeyHex != null && keyed.subjectSigningKeyHex != null) {
                    bound[id] = keyed.subjectSharingKeyHex to keyed.subjectSigningKeyHex
                }
                changed = true
            }
        }

        val invalid = mutableSetOf<String>()
        val signerUnconfirmed = mutableSetOf<String>()
        val signerChanged = mutableSetOf<String>()
        val askSigner = mutableSetOf<String>()
        val conflict = mutableSetOf<String>()
        val statementLess = mutableSetOf<String>()
        for ((id, member) in byId) {
            if (id in confirmed) continue
            val judged = candidates[id].orEmpty().map { it to judge(member, it) }
            val verdicts = judged.map { it.second }
            when {
                judged.isEmpty() -> statementLess += id
                Verdict.CONFLICT in verdicts -> conflict += id
                Verdict.SIGNER_UNCONFIRMED in verdicts -> {
                    signerUnconfirmed += id
                    // Only for a member someone vouched for with keys; an email invitation
                    // alone is never asked about.
                    if (judged.any { (c, v) -> v == Verdict.SIGNER_UNCONFIRMED && c.parsed?.kind != Kind.INVITATION_TO_EMAIL }) {
                        askSigner += id
                    }
                }
                Verdict.SIGNER_CHANGED in verdicts -> signerChanged += id
                else -> invalid += id
            }
        }

        // This user's own app delivers and refreshes nothing while all it holds is an email
        // invitation, or statements about keys it no longer has (a password reset), until
        // an owner/admin admits its current keys. A member from before signed invitations
        // carries none, and is not affected.
        val mine = candidates[self].orEmpty()
        val selfReceiveOnly = mine.isNotEmpty() && mine.none { c ->
            val p = c.parsed ?: return@none false
            val signer = p.signerUserId.lowercase()
            p.kind != Kind.INVITATION_TO_EMAIL &&
                p.circleId.equals(circleId, ignoreCase = true) &&
                p.subjectUserId.equals(selfUserId, ignoreCase = true) &&
                bindsKeys(p, selfSharingKeyHex, selfSigningKeyHex) &&
                signer != self && signer in confirmed && !receiveOnly(signer) &&
                verifies(c, signerKey(signer))
        }

        fun spell(id: String) = byId[id]?.userId ?: if (id == self) selfUserId else id
        val ownEmailInvitationUnconfirmed = candidates[self].orEmpty().any { c ->
            val p = c.parsed
            p != null && p.kind == Kind.INVITATION_TO_EMAIL && p.circleId.equals(circleId, ignoreCase = true) && !aboutMe(p)
        }
        return Result(
            confirmed = confirmed.mapKeys { spell(it.key) },
            newlyConfirmed = newly.mapKeys { spell(it.key) },
            boundKeys = bound.mapKeys { spell(it.key) },
            invalid = invalid.map(::spell).toSet(),
            signerUnconfirmed = signerUnconfirmed.map(::spell).toSet(),
            signerChanged = signerChanged.map(::spell).toSet(),
            askSigner = askSigner.map(::spell).toSet(),
            statementLess = statementLess.map(::spell).toSet(),
            subjectConflict = conflict.map(::spell).toSet(),
            holding = holding.mapKeys { spell(it.key) },
            selfReceiveOnly = selfReceiveOnly,
            statementIds = candidates
                .mapValues { (_, list) -> list.mapNotNull { it.parsed?.statementId } }
                .filterValues { it.isNotEmpty() }
                .mapKeys { spell(it.key) },
            previousKeyOnly = previousKeyOnly.map(::spell).toSet(),
            ownAdmissions = byId.filter { (id, m) ->
                id != self && candidates[id].orEmpty().any { c ->
                    val p = c.parsed
                    p != null && p.kind == Kind.ADMISSION && p.circleId.equals(circleId, ignoreCase = true) &&
                        p.signerUserId.equals(selfUserId, ignoreCase = true) && p.subjectUserId.equals(m.userId, ignoreCase = true) &&
                        p.statementId.lowercase() !in deadIds && bindsKeys(p, m.sharingKeyHex, m.signingKeyHex) &&
                        tombstones[id].let { leftAt -> leftAt == null || p.atVersion >= leftAt } &&
                        (verifies(c, selfSigningKeyHex) || ownFromBeforeReset(c))
                }
            }.keys.map(::spell).toSet(),
            ownEmailInvitationUnconfirmed = ownEmailInvitationUnconfirmed,
        )
    }

    /**
     * Stray choices. A "Let them in" choice stored by an older app version may have no admission
     * behind it: that version recorded the live choice before its admission, so one whose
     * admission never landed is left stray. A choice ([Origin.OWN] in [approvals]) is stray when
     * its member, judged without this device's choices ([withoutChoices]), would be asked "Let
     * them in?" again, and carries no admission of this account's binding their current keys
     * ([ownAdmissions]). "It's them" answers and this user's own invitees are never asked that, so
     * they stay.
     */
    fun strayChoices(approvals: Map<String, Origin>, withoutChoices: Result, ownAdmissions: Set<String>): Set<String> =
        approvals.filterValues { it == Origin.OWN }.keys.filter { id ->
            withoutChoices.askSigner.any { it.equals(id, ignoreCase = true) } && ownAdmissions.none { it.equals(id, ignoreCase = true) }
        }.toSet()

    /**
     * Whether this device may hand [member] the circle's current copy itself: a keyless
     * member confirmed by an account or email invitation that has not expired (5 minutes'
     * grace for clocks), a member with an admission made at this device's version or
     * later, or a member this user chose here (own invitation, "Let them in", "It's
     * them"). Anyone else who is owed a copy is left to the device that created the
     * circle, which delivers to every member it confirms ([createdHere]) — by its own
     * record of creating it, never by a role the server reports: a server that called a
     * plain member "admin" would otherwise widen whom that member's app hands the circle
     * to.
     */
    fun mayDeliver(
        member: Member,
        origin: Origin?,
        holding: List<MembershipStatements.Statement>,
        activeVersion: Int,
        nowSecs: Long,
        createdHere: Boolean = false,
    ): Boolean {
        if (origin == null) return false
        if (createdHere || origin == Origin.OWN || origin == Origin.OWN_KIND2) return true
        val firstDelivery = member.keyVersion == null && holding.any {
            (it.kind == Kind.INVITATION_TO_ACCOUNT || it.kind == Kind.INVITATION_TO_EMAIL) &&
                it.expiresAt + EXPIRY_GRACE_SECS >= nowSecs
        }
        return firstDelivery || holding.any { it.kind == Kind.ADMISSION && it.atVersion >= activeVersion }
    }

    /**
     * The keys a promotion's admission signs — [held], the keys this device holds for the
     * member, only while the member list shows exactly those ([listed]) and, when the person
     * was shown a number, they are the keys behind it ([decided]). Null: nothing is signed,
     * and the role doesn't change. Hex case aside.
     */
    fun promotionAdmissionKeys(
        held: Pair<String, String>?,
        listed: Pair<String, String>?,
        decided: Pair<String, String>?,
    ): Pair<String, String>? {
        fun same(a: Pair<String, String>, b: Pair<String, String>) =
            a.first.equals(b.first, ignoreCase = true) && a.second.equals(b.second, ignoreCase = true)
        if (held == null || listed == null || !same(held, listed)) return null
        if (decided != null && !same(held, decided)) return null
        return held
    }

    /**
     * Whether an invitation this account received names it: this circle, and an
     * invitation to THIS account with exactly this device's keys, or to this account's
     * email. Anything else — malformed, an admission, other keys — does not.
     */
    fun invitationNamesMe(
        statement: MembershipStatements.Statement?,
        circleId: String,
        selfUserId: String,
        selfSharingKeyHex: String?,
        selfSigningKeyHex: String?,
        selfEmail: String?,
    ): Boolean {
        val p = statement ?: return false
        if (!p.circleId.equals(circleId, ignoreCase = true)) return false
        return when (p.kind) {
            Kind.INVITATION_TO_ACCOUNT -> p.subjectUserId.equals(selfUserId, ignoreCase = true) &&
                selfSharingKeyHex != null && selfSigningKeyHex != null &&
                p.subjectSharingKeyHex.equals(selfSharingKeyHex, ignoreCase = true) &&
                p.subjectSigningKeyHex.equals(selfSigningKeyHex, ignoreCase = true)
            Kind.INVITATION_TO_EMAIL -> selfEmail != null && p.subjectEmail.equals(selfEmail.trim(), ignoreCase = true)
            Kind.ADMISSION -> false
        }
    }

    private const val EXPIRY_GRACE_SECS = 300L
}
