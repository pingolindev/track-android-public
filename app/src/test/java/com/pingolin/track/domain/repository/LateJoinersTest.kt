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

import com.pingolin.track.domain.repository.LateJoiners.Case
import com.pingolin.track.domain.repository.LateJoiners.Invitation
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.testutil.FakeMembershipStatements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.MemberEligibility

/** Who is asked "Let {name} into {circle}?", about whom, and how often (LateJoiners). */
class LateJoinersTest {

    private fun e(id: String, expired: Boolean = false, notAdmin: Boolean = false, email: Boolean = false, removed: Boolean = false, eligible: Boolean = false) =
        MemberEligibility(id, eligible, null, expired, notAdmin, removed, if (removed) 3 else null, email)

    private fun inv(id: String, signer: String, person: String, at: Long = 1_000L) = Invitation(id, signer, person, at)

    private val roles = mapOf("me" to CircleRole.OWNER, "ada" to CircleRole.ADMIN, "moe" to CircleRole.MEMBER)
    private fun c(id: String, e: MemberEligibility, invitation: Invitation?) =
        LateJoiners.Candidate(id, e, invitation, invitation?.let { roles[it.signer] })

    private fun asked(self: String, vararg candidates: LateJoiners.Candidate, answered: List<Invitation> = emptyList(), raised: Set<String> = emptySet()) =
        LateJoiners.questions(self, roles[self], candidates.toList(), answered, raised)

    private val fake = FakeMembershipStatements()

    private fun joined(
        member: String,
        vararg statements: MembershipStatements.Signed,
        holds: (MembershipStatements.Statement, MembershipStatements.Signed) -> Boolean = { _, _ -> true },
    ) = LateJoiners.joinedWith(statements.toList(), member, "c-1", fake::parse, holds)?.statementId

    @Test
    fun `the invitation a member joined with - to this circle, of them, and holding here`() {
        val ofBob = fake.statement(Kind.INVITATION_TO_ACCOUNT, "c-1", "ada", subject = "bob", id = "i-bob")
        val toAddress = fake.statement(Kind.INVITATION_TO_EMAIL, "c-1", "ada", email = "bob@x.test", id = "i-mail")
        val otherCircle = fake.statement(Kind.INVITATION_TO_ACCOUNT, "c-2", "ada", subject = "bob", id = "i-c2")
        val ofCy = fake.statement(Kind.INVITATION_TO_ACCOUNT, "c-1", "ada", subject = "cy", id = "i-cy")
        val admission = fake.statement(Kind.ADMISSION, "c-1", "ada", subject = "bob", id = "a-bob")

        assertEquals("i-bob", joined("BOB", ofBob))
        assertEquals("an address invitation names no account", "i-mail", joined("bob", toAddress))
        assertNull("another circle's", joined("bob", otherCircle))
        assertNull("another member's", joined("bob", ofCy))
        assertNull("an admission is no invitation", joined("bob", admission))
        assertNull("malformed", joined("bob", MembershipStatements.Signed("junk", "sig")))
        assertEquals("the first that is one", "i-bob", joined("bob", admission, otherCircle, ofCy, ofBob, toAddress))
        assertNull("one that doesn't hold here", joined("bob", ofBob) { _, _ -> false })
        assertEquals("the first that holds", "i-mail", joined("bob", ofBob, toAddress) { s, _ -> s.statementId == "i-mail" })
        assertNull("a check that fails holds nothing", joined("bob", ofBob) { _, _ -> error("unreadable") })
    }

    @Test
    fun `the three cases, and nobody E already allows`() {
        val questions = asked(
            "me",
            c("bob", e("bob", expired = true), inv("i-bob", "me", "bob")),
            c("cy", e("cy", notAdmin = true), inv("i-cy", "moe", "cy")),
            c("eve", e("eve", email = true), inv("i-eve", "me", "eve@x.test")),
            c("fay", e("fay", expired = true, eligible = true), inv("i-fay", "me", "fay")),
            c("gus", e("gus"), inv("i-gus", "me", "gus")),
            c("hal", e("hal", expired = true), null),
        )

        assertEquals(listOf("bob" to Case.EXPIRED, "eve" to Case.EMAIL_WITHOUT_ID, "cy" to Case.SIGNER_NOT_CHAIN_ADMIN), questions.map { it.userId to it.case })
        assertEquals(listOf(true, true, false), questions.map { it.inviterIsChainAdmin })
    }

    @Test
    fun `the inviter is asked when they're an owner or admin in the chain, else the chain's owner`() {
        val byAda = c("bob", e("bob", expired = true), inv("i-bob", "ada", "bob"))
        val byMe = c("cy", e("cy", expired = true), inv("i-cy", "me", "cy"))
        val byMoe = c("dee", e("dee", notAdmin = true), inv("i-dee", "moe", "dee"))

        assertEquals(listOf("bob"), asked("ada", byAda, byMe, byMoe).map { it.userId })
        assertEquals(listOf("cy", "dee"), asked("me", byAda, byMe, byMoe).map { it.userId })
        assertEquals(emptyList<String>(), asked("moe", byAda, byMe, byMoe).map { it.userId })
    }

    @Test
    fun `never about someone an owner or admin removed when the signer isn't one`() {
        assertEquals(emptyList<String>(), asked("me", c("bob", e("bob", notAdmin = true, removed = true), inv("i-bob", "moe", "bob"))).map { it.userId })
        // A chain owner's own invitation of them still counts, and its running out is asked about.
        assertEquals(listOf("bob"), asked("me", c("bob", e("bob", expired = true, removed = true), inv("i-bob", "me", "bob"))).map { it.userId })
    }

    @Test
    fun `once per invitation - its id, or the same signer and person within 8 days`() {
        val answered = listOf(inv("i-1", "moe", "bob", at = 1_000L))
        fun ask(invitation: Invitation) = asked("me", c("bob", e("bob", notAdmin = true), invitation), answered = answered).map { it.userId }

        assertEquals(emptyList<String>(), ask(inv("I-1", "someone", "else")))
        assertEquals(emptyList<String>(), ask(inv("i-2", "MOE", "BOB", at = 1_000L + LateJoiners.SAME_INVITATION_SECS - 1)))
        assertEquals(emptyList<String>(), ask(inv("i-3", "moe", "bob", at = 1_000L - LateJoiners.SAME_INVITATION_SECS + 1)))
        assertEquals(listOf("bob"), ask(inv("i-4", "moe", "bob", at = 1_000L + LateJoiners.SAME_INVITATION_SECS)))
        assertEquals("another signer's invitation is another", listOf("bob"), ask(inv("i-5", "zoe", "bob")))
    }

    @Test
    fun `one question per invitation among the rows, the row already raised keeping it`() {
        // One address invitation (kind 2) on three rows: it binds no user id.
        val mail = inv("i-mail", "me", "x@x.test")
        val rows = listOf("bob", "cy", "dee").map { id -> c(id, e(id, email = true), mail) }
        assertEquals(listOf("bob"), asked("me", *rows.toTypedArray()).map { it.userId })
        assertEquals("the row asked about keeps it", listOf("dee"), asked("me", *rows.toTypedArray(), raised = setOf("DEE")).map { it.userId })

        // The same invitation by its signer, person and time, capped (a plain member's): one question, one of the cap.
        val byMoe = listOf(
            c("m1", e("m1", email = true), inv("i-a", "moe", "y@x.test", at = 10L)),
            c("m2", e("m2", email = true), inv("i-b", "moe", "y@x.test", at = 20L)),
            c("m3", e("m3", notAdmin = true), inv("i-m3", "moe", "m3", at = 30L)),
            c("m4", e("m4", notAdmin = true), inv("i-m4", "moe", "m4", at = 40L)),
        )
        assertEquals(listOf("m1", "m3", "m4"), asked("me", *byMoe.toTypedArray()).map { it.userId })
        assertEquals(listOf("m2", "m3", "m4"), asked("me", *byMoe.toTypedArray(), raised = setOf("m2")).map { it.userId })
    }

    @Test
    fun `a raised question closes, its invitation answered, when its member got in or left the list - not while E still keeps them out`() {
        val raised = listOf("bob" to inv("i-bob", "me", "bob"), "cy" to inv("i-cy", "me", "cy"), "dee" to inv("i-dee", "me", "dee"))
        val eligibility = listOf(e("BOB", eligible = true), e("dee", expired = true))
        assertEquals(listOf("i-bob", "i-cy"), LateJoiners.closed(raised, eligibility).map { it.id })
    }

    @Test
    fun `at most 3 whose signer isn't a chain owner or admin, those raised first - the chain's own uncapped`() {
        val others = (1..5).map { n -> c("m$n", e("m$n", notAdmin = true), inv("i-m$n", "moe", "m$n", at = n.toLong())) }
        val own = (1..4).map { n -> c("o$n", e("o$n", expired = true), inv("i-o$n", "me", "o$n")) }

        assertEquals(
            listOf("o1", "o2", "o3", "o4", "m1", "m2", "m3"),
            asked("me", *(own + others).toTypedArray()).map { it.userId },
        )
        assertEquals(
            listOf("o1", "o2", "o3", "o4", "m5", "m1", "m2"),
            asked("me", *(own + others).toTypedArray(), raised = setOf("M5")).map { it.userId },
        )
    }

    // ---- After this account's own password reset -------------------------------------------------------

    /** A member invited by this account ("me", the chain's owner) before its reset, with each fact the
     * reset rule weighs given as a parameter.
     */
    private fun reset(
        id: String = "bob",
        e: MemberEligibility = e(id),
        signer: String = "me",
        basis: Boolean = true,
        binds: Boolean = true,
        unforked: Boolean = true,
    ) = LateJoiners.Candidate(id, e, inv("i-$id", signer, id), roles[signer], ownPreResetBasis = basis, bindsListedKeys = binds, chainUnforked = unforked)

    @Test
    fun `invited before this account's reset - asked, E not eligible with no flag, its own invitation, keys bound, unforked`() {
        val q = asked("me", reset()).single()
        assertEquals("bob", q.userId)
        assertEquals(Case.INVITED_BEFORE_RESET, q.case)
        assertEquals("i-bob", q.invitation.id)
        assertEquals("its inviter is this account, a chain owner: no inviter line", true, q.inviterIsChainAdmin)
        assertEquals(Case.INVITED_BEFORE_RESET, LateJoiners.case("me", reset()))
        assertEquals("an admin who invited them is asked too", Case.INVITED_BEFORE_RESET, LateJoiners.questions("ada", CircleRole.ADMIN, listOf(reset(signer = "ada")), emptyList()).single().case)
    }

    @Test
    fun `invited before the reset - never when E lets them in, nor without the own pre-reset basis, nor for another signer's`() {
        assertEquals(emptyList<LateJoiners.Question>(), asked("me", reset(e = e("bob", eligible = true))))
        assertEquals("nothing here backs it: the server's word", emptyList<LateJoiners.Question>(), asked("me", reset(basis = false)))
        assertEquals("another signer's (an admin's)", emptyList<LateJoiners.Question>(), asked("me", reset(signer = "ada")))
        assertNull(LateJoiners.case("me", reset(signer = "ada")))
        assertNull("no invitation at all", LateJoiners.case("me", reset().copy(invitation = null)))
    }

    @Test
    fun `invited before the reset - never when removed, keys unbound, or the chain forked or not imported`() {
        assertNull("an owner or admin removed them", LateJoiners.case("me", reset(e = e("bob", removed = true))))
        assertNull("it binds other keys than the list shows: It's them decides", LateJoiners.case("me", reset(binds = false)))
        assertNull("forked, or not imported in this process", LateJoiners.case("me", reset(unforked = false)))
        assertEquals(emptyList<LateJoiners.Question>(), asked("me", reset(e = e("bob", removed = true)), reset("cy", binds = false), reset("dee", unforked = false)))
    }

    @Test
    fun `invited before the reset - E's own flags keep their cases, and the once-rule holds`() {
        assertEquals(Case.EXPIRED, LateJoiners.case("me", reset(e = e("bob", expired = true))))
        assertEquals(Case.EMAIL_WITHOUT_ID, LateJoiners.case("me", reset(e = e("bob", email = true))))
        assertEquals(Case.SIGNER_NOT_CHAIN_ADMIN, LateJoiners.case("me", reset(e = e("bob", notAdmin = true))))
        assertEquals("answered once, never again", emptyList<LateJoiners.Question>(), asked("me", reset(), answered = listOf(inv("i-bob", "me", "bob"))))
    }
}
