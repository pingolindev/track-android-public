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
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.util.KeyAccessApprovals
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A "Let them in" choice recorded before its admission landed (as earlier builds did) is a
 * stray choice. Once per circle, on a complete list, never while behind or forked, such a
 * choice goes and its member is asked again. "It's them" answers and own invitees stay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrayChoicesTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun TestScope.harness() = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me")
    }

    /** newcomer, invited by mallory (whom this device doesn't confirm), and let in by an older build here. */
    private fun TrustHarness.strayChoice(vararg vouches: MembershipStatements.Signed) {
        approvals.approve("c-1", listOf("newcomer"), Origin.OWN)
        serverLists(
            listOf(
                rosterMember("me"),
                rosterMember("mallory"),
                rosterMember("newcomer", invitation("newcomer", by = "mallory"), vouches = vouches.toList()),
            )
        )
    }

    private fun TrustHarness.admission(signerKey: String = signing("me"), keysOf: String = "newcomer", circleId: String = "c-1") =
        statements.statement(Kind.ADMISSION, circleId, "me", signerKey, "newcomer", sharing(keysOf), signing(keysOf), id = "adm-newcomer")

    @Test
    fun `a stray choice goes, and its member is asked again, once`() = runTest {
        val h = harness()
        h.strayChoice()

        h.repository.refreshMembers("c-1")

        assertFalse("newcomer" in h.origins())
        assertEquals(listOf("newcomer"), h.repository.keyAccessRequests.value.map { it.userId })
        assertTrue(h.approvals.strayChoicesChecked("c-1"))

        // Once: a choice made from now on (admission-first) is never looked at again.
        h.approvals.approve("c-1", listOf("newcomer"), Origin.OWN)
        h.repository.refreshMembers("c-1")
        assertEquals(Origin.OWN, h.origins()["newcomer"])
    }

    @Test
    fun `a choice with an admission of this account's binding their current keys stays`() = runTest {
        val h = harness()
        h.strayChoice(h.admission())

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue(h.approvals.strayChoicesChecked("c-1"))
    }

    @Test
    fun `an admission signed before a password reset counts too`() = runTest {
        val h = harness()
        h.statements.previousSigningKeys += "me-old-s"
        h.strayChoice(h.admission(signerKey = "me-old-s"))

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.OWN, h.origins()["newcomer"])
    }

    @Test
    fun `an admission of this account's that doesn't hold up here doesn't save the choice`() = runTest {
        val h = harness()
        // Another circle's: it neither confirms them here nor counts as this account's admission.
        h.strayChoice(h.admission(circleId = "c-2"))

        h.repository.refreshMembers("c-1")

        assertFalse("newcomer" in h.origins())
    }

    @Test
    fun `the rule itself - a choice is stray only when asked again without the choices, and with no admission of this account's`() {
        fun judged(askSigner: Set<String>) = MemberConfirmation.Result(
            confirmed = emptyMap(), newlyConfirmed = emptyMap(), boundKeys = emptyMap(), invalid = emptySet(),
            signerUnconfirmed = askSigner, signerChanged = emptySet(), askSigner = askSigner, statementLess = emptySet(),
            subjectConflict = emptySet(), holding = emptyMap(), selfReceiveOnly = false,
        )
        val approvals = mapOf("newcomer" to Origin.OWN, "admitted" to Origin.OWN, "vouched" to Origin.STATEMENT, "kept" to Origin.OWN)

        assertEquals(
            setOf("newcomer"),
            MemberConfirmation.strayChoices(approvals, judged(setOf("newcomer", "admitted", "vouched")), ownAdmissions = setOf("admitted")),
        )
    }

    @Test
    fun `It's them and this user's own invitees stay`() = runTest {
        val h = harness()
        // bob: invited by this user; alice: "It's them" after her keys changed (her invitation binds
        // the keys she had before, so without the answer she'd be a conflict, never asked to be let in).
        h.pins.pin("alice", sharing("alice"), signing("alice"))
        h.approvals.approve("c-1", listOf("bob", "alice"), Origin.OWN)
        h.serverLists(
            listOf(
                h.rosterMember("me"),
                h.rosterMember("bob", h.invitation("bob", by = "me")),
                h.rosterMember("alice", h.invitation("alice", by = "me", keysOf = "alice-old")),
            )
        )

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.OWN, h.origins()["bob"])
        assertEquals(Origin.OWN, h.origins()["alice"])
        assertTrue(h.approvals.strayChoicesChecked("c-1"))
    }

    @Test
    fun `nothing is looked at while this phone is behind, and the look waits for it`() = runTest {
        val h = harness()
        h.strayChoice()
        h.circleVersion = 4

        h.repository.refreshMembers("c-1")

        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertFalse(h.approvals.strayChoicesChecked("c-1"))
    }

    @Test
    fun `a drop that didn't land leaves the look to the next pass`() = runTest {
        val h = harness()
        h.strayChoice()
        h.repository.keyAccessApprovals = object : KeyAccessApprovals by h.approvals {
            override fun dropChoicesDurably(circleId: String, userIds: Collection<String>) = false
        }

        h.repository.refreshMembers("c-1")

        assertFalse(h.approvals.strayChoicesChecked("c-1"))
    }

    /**
     * An older build wrote the choice while this account was an admin, and its admission was
     * refused (403: the role had gone). This device is a plain member now, and newcomer waits
     * for a first copy.
     */
    private fun TestScope.lostItsRole(passAlreadyDone: Boolean) = TrustHarness(selfRole = "member").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "olive")
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.approvals.approve("c-1", listOf("newcomer"), Origin.OWN)
        if (passAlreadyDone) h.approvals.markStrayChoicesChecked("c-1")
        h.serverLists(
            listOf(
                h.rosterMember("olive", role = "owner"),
                h.rosterMember("me"),
                h.rosterMember("mallory"),
                h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null),
            )
        )
    }

    @Test
    fun `a device that lost its role drops its stray choice and hands that member nothing`() = runTest {
        // Without the pass, the stray choice alone would have this device hand newcomer its copy.
        val kept = lostItsRole(passAlreadyDone = true)
        val deliveredAnyway = kept.deliveriesAccepted()
        kept.repository.deliverOwedAccess("c-1")
        advanceUntilIdle()
        assertEquals(setOf("newcomer"), deliveredAnyway.keys)

        val h = lostItsRole(passAlreadyDone = false)
        val delivered = h.deliveriesAccepted()

        h.repository.deliverOwedAccess("c-1")
        advanceUntilIdle()

        assertFalse("the choice goes", "newcomer" in h.origins())
        assertTrue(h.approvals.strayChoicesChecked("c-1"))
        assertTrue("nothing handed over", delivered.isEmpty())
        assertTrue("a plain member's device asks nobody", h.repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `while the approvals can't be read the pass neither finds no strays nor marks itself done, and it looks once they read`() = runTest {
        val h = harness()
        h.strayChoice()
        h.approvalPrefs.damaged = true

        assertTrue(h.repository.refreshMembers("c-1").isFailure)

        h.approvalPrefs.damaged = false
        assertFalse("not marked done", h.approvals.strayChoicesChecked("c-1"))
        assertEquals("nothing dropped blindly", Origin.OWN, h.origins()["newcomer"])

        h.repository.refreshMembers("c-1")
        assertFalse("newcomer" in h.origins())
        assertTrue(h.approvals.strayChoicesChecked("c-1"))
    }

    @Test
    fun `the pass's own read failing marks nothing, even after the list was judged`() = runTest {
        val h = harness()
        h.strayChoice()
        // The judge reads the approvals first; the pass's own read (its second) fails.
        var reads = 0
        h.repository.keyAccessApprovals = object : KeyAccessApprovals by h.approvals {
            override fun origins(circleId: String): Map<String, Origin> =
                if (++reads == 2) throw com.pingolin.track.util.StoreUnreadable("key access approvals") else h.approvals.origins(circleId)
        }

        h.repository.refreshMembers("c-1")

        assertFalse(h.approvals.strayChoicesChecked("c-1"))
        assertEquals(Origin.OWN, h.origins()["newcomer"])
    }
}
