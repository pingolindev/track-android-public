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

import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.MemberEligibility
import kotlin.math.abs

/**
 * The late joiner: the one question, "Let {name} into {circle}?", about a member this device
 * confirms whom the SDK's eligibility check ([MemberEligibility]) doesn't let have the circle's
 * key, because (the SDK's flags):
 * - their invitation ran out before anyone's phone handed the circle over (the first case,
 *   whoever signed it);
 * - its signer isn't an owner or admin in the chain (the second, asked at once);
 * - it's an address invitation no owner's or admin's statement signs their user id for (the
 *   third, asked at once);
 * - none of those, and this account invited them before its own password reset: since the reset,
 *   the eligibility check doesn't read that invitation as an owner's or admin's.
 * Pure, so every rule is unit-tested; CircleRepository asks the SDK and raises the questions.
 */
object LateJoiners {

    enum class Case { EXPIRED, SIGNER_NOT_CHAIN_ADMIN, EMAIL_WITHOUT_ID, INVITED_BEFORE_RESET }

    /** An invitation as the once-rule reads it: its id, signer, signed subject and when it was made. */
    data class Invitation(val id: String, val signer: String, val person: String, val issuedAt: Long)

    data class Candidate(
        val userId: String,
        val eligibility: MemberEligibility,
        /** The invitation they joined with, as it holds here ([joinedWith]); null: no question. */
        val invitation: Invitation?,
        /** Their inviter's role in the chain (chainRole), or null. */
        val inviterChainRole: CircleRole?,
        /**
         * [invitation] is this account's own from before its password reset (its current key doesn't
         * verify it), and they are confirmed here only through it: a restored key from before the reset
         * verifies it and nothing else confirms them, or this device's own approval of them rests on it
         * by id.
         */
        val ownPreResetBasis: Boolean = false,
        /** [invitation] binds exactly the keys the member list shows for them. */
        val bindsListedKeys: Boolean = false,
        /** The circle's records were imported in this process, the last import not forked. */
        val chainUnforked: Boolean = false,
    )

    data class Question(val userId: String, val case: Case, val invitation: Invitation, val inviterIsChainAdmin: Boolean)

    /** At most this many questions whose inviter isn't a chain owner or admin pending per circle, both cases together. */
    const val CAP = 3

    /** The same invitation: the same signer, person and circle within 8 days (the age limit and a day), in seconds. */
    const val SAME_INVITATION_SECS = 691_200L

    fun sameInvitation(a: Invitation, b: Invitation): Boolean =
        a.id.equals(b.id, ignoreCase = true) ||
            (a.signer.equals(b.signer, ignoreCase = true) && a.person.equals(b.person, ignoreCase = true) &&
                abs(a.issuedAt - b.issuedAt) < SAME_INVITATION_SECS)

    /**
     * The invitation [member] joined with, as the late-joiner question counts it (as the iOS
     * app's lateJoinerInvitation): the first of [statements], as served, that is an invitation to
     * [circleId], of [member] when it's to an account (one to an address names no account), and
     * that [holds] under the key this device trusts for its signer: this account's own, current
     * or from before a reset, when the signer is this account; else the key pinned for the
     * signer. Any other is the server's word, and never says who is asked, what the cap counts or
     * whom a question names. Null: none.
     */
    fun joinedWith(
        statements: List<MembershipStatements.Signed>,
        member: String,
        circleId: String,
        parse: (String) -> MembershipStatements.Statement?,
        holds: (MembershipStatements.Statement, MembershipStatements.Signed) -> Boolean,
    ): MembershipStatements.Statement? = joinedWithSigned(statements, member, circleId, parse, holds)?.first

    /** [joinedWith], with the signed statement it read. */
    fun joinedWithSigned(
        statements: List<MembershipStatements.Signed>,
        member: String,
        circleId: String,
        parse: (String) -> MembershipStatements.Statement?,
        holds: (MembershipStatements.Statement, MembershipStatements.Signed) -> Boolean,
    ): Pair<MembershipStatements.Statement, MembershipStatements.Signed>? = statements.firstNotNullOfOrNull { signed ->
        runCatching { parse(signed.statementHex) }.getOrNull()?.takeIf { s ->
            (s.kind == MembershipStatements.Kind.INVITATION_TO_EMAIL ||
                (s.kind == MembershipStatements.Kind.INVITATION_TO_ACCOUNT && s.subjectUserId.equals(member, ignoreCase = true))) &&
                s.circleId.equals(circleId, ignoreCase = true) &&
                (s.subjectUserId ?: s.subjectEmail) != null &&
                runCatching { holds(s, signed) }.getOrDefault(false)
        }?.let { it to signed }
    }

    /**
     * Why [c] is a late joiner, as [self] reads it; null: they aren't one. The eligibility check's three
     * flags first, whoever signed. Then the invited-before-reset case: the eligibility check doesn't let
     * them have the key and raises no flag, nobody an owner or admin removed, and the invitation they joined
     * with is this account's own from before its password reset ([Candidate.ownPreResetBasis]), binding
     * exactly the keys the member list shows, on records imported in this process and not forked. Whoever
     * the chain counts as included with those keys, the eligibility check lets in, so it never gets here.
     */
    fun case(self: String, c: Candidate): Case? {
        val e = c.eligibility
        if (e.eligible) return null
        return when {
            e.invitationExpired -> Case.EXPIRED
            e.invitationSignerNotChainAdmin -> Case.SIGNER_NOT_CHAIN_ADMIN
            e.emailInvitationWithoutSignedId -> Case.EMAIL_WITHOUT_ID
            c.ownPreResetBasis && c.bindsListedKeys && c.chainUnforked && !e.removedByOwnerOrAdmin &&
                c.invitation?.signer?.equals(self, ignoreCase = true) == true -> Case.INVITED_BEFORE_RESET
            else -> null
        }
    }

    /** [statement], an invitation, as the once-rule reads it. */
    fun invitation(statement: MembershipStatements.Statement): Invitation? =
        (statement.subjectUserId ?: statement.subjectEmail)?.let { Invitation(statement.statementId, statement.signerUserId, it, statement.issuedAt) }

    /**
     * The questions this device asks now. [self] is asked about an invitation an owner or admin in
     * the chain signed only when they signed it themselves; about anyone else's, only as the
     * chain's owner ([selfChainRole]). Never about someone an owner or admin removed when the
     * signer isn't one; never again about an invitation already answered here ([answered]), "Not
     * now" included; one question per invitation, the row already [raised] keeping it (an address
     * invitation binds no user id, so one can be on several rows); and of those whose signer isn't
     * a chain owner or admin, at most [CAP] at a time, the ones already [raised] first: the rest
     * wait until one of them is answered.
     */
    fun questions(
        self: String,
        selfChainRole: CircleRole?,
        candidates: List<Candidate>,
        answered: List<Invitation>,
        raised: Set<String> = emptySet(),
    ): List<Question> {
        fun wasRaised(q: Question) = raised.any { it.equals(q.userId, ignoreCase = true) }
        val found = candidates.mapNotNull { c ->
            val e = c.eligibility
            val invitation = c.invitation ?: return@mapNotNull null
            val case = case(self, c) ?: return@mapNotNull null
            val inviterIsChainAdmin = c.inviterChainRole == CircleRole.OWNER || c.inviterChainRole == CircleRole.ADMIN
            if (!inviterIsChainAdmin && e.removedByOwnerOrAdmin) return@mapNotNull null
            val askedHere = if (inviterIsChainAdmin) invitation.signer.equals(self, ignoreCase = true) else selfChainRole == CircleRole.OWNER
            if (!askedHere) return@mapNotNull null
            if (answered.any { sameInvitation(it, invitation) }) return@mapNotNull null
            Question(c.userId, case, invitation, inviterIsChainAdmin)
        }
        val kept = mutableListOf<Question>()
        for (q in found.sortedBy { !wasRaised(it) }) if (kept.none { sameInvitation(it.invitation, q.invitation) }) kept += q
        val (own, others) = found.filter { it in kept }.partition { it.inviterIsChainAdmin }
        return own + others.sortedWith(compareBy({ q -> !wasRaised(q) }, { it.invitation.issuedAt })).take(CAP)
    }

    /**
     * Of the questions raised here ([raised]: each member with their invitation), those closed
     * because their member got in meanwhile (the eligibility check allows them: [eligibility])
     * or left the list (it has nothing on them): their invitations stay answered, so a later row
     * carrying the same one is never asked (as on iOS). A member the eligibility check still
     * doesn't allow closes nothing.
     */
    fun closed(raised: List<Pair<String, Invitation>>, eligibility: List<MemberEligibility>): List<Invitation> =
        raised.filter { (userId, _) -> eligibility.firstOrNull { it.userId.equals(userId, ignoreCase = true) }?.eligible != false }
            .map { it.second }

    /** A pass's questions, and the invitations of the questions it [closed]. */
    data class Pass(val questions: List<Question>, val closed: List<Invitation>)

    /**
     * Whom [q] names as its inviter: nobody when they're an owner or admin in the chain (the one
     * asked, or known to the owner), else the name this device has for them ([named]: its member list,
     * then the names it kept). None known, no inviter line at all: never a stand-in such as "A member"
     * (as on iOS).
     */
    inline fun inviterName(q: Question, named: (signer: String) -> String?): String? =
        if (q.inviterIsChainAdmin) null else named(q.invitation.signer)?.takeIf { it.isNotBlank() }

    /** The question's lines: that the invitation [ranOut], then who [invitedBy] them, when named. */
    fun lines(case: Case?, inviterName: String?, ranOut: String, invitedBy: (String) -> String): List<String> =
        listOfNotNull(ranOut.takeIf { case == Case.EXPIRED }, inviterName?.let(invitedBy))
}
