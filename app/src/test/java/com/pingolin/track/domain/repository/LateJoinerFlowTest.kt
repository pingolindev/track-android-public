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
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.EligibilityPurpose
import uniffi.pingolin_mobile.MemberEligibility
import uniffi.pingolin_mobile.RecordMember

/**
 * After a roster pass, the device that is asked raises "Let {name} into {circle}?" about a late
 * joiner, with the lines' facts (the case, the inviter when they aren't a chain owner or admin),
 * and an answer ("Not now" included) is kept for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LateJoinerFlowTest {

    @After
    fun tearDown() = clearAllMocks()

    /** An owner's device on a circle with records; the SDK's eligibility check says who is a late
     * joiner, and why ([flags]).
     */
    private fun harness(flags: (String) -> MemberEligibility) = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() })
        h.recordedCircle()
        h.repository.lateJoinerAnswers = SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { InMemoryPrefs() })
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            arg<List<RecordMember>>(1).map { flags(it.userId) }
        }
    }

    private fun allowed(id: String) = MemberEligibility(id, true, null, false, false, false, null, false)

    @Test
    fun `an owner's device asks about its own invitee whose invitation ran out, and Not now is the answer for good`() = runTest {
        val h = harness { id -> if (id == "bob") MemberEligibility(id, false, null, true, false, false, null, false) else allowed(id) }
        h.earlierSeed("me")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", h.invitation("bob", by = "me", id = "inv-bob"), keyVersion = null)))

        h.repository.refreshMembers("c-1")

        val q = h.repository.keyAccessRequests.value.single()
        assertEquals(KeyAccessRequest.Reason.LATE_JOINER, q.reason)
        assertEquals(LateJoiners.Case.EXPIRED, q.lateCase)
        assertNull("its inviter is a chain owner: not named", q.inviterName)
        assertEquals("inv-bob", q.invitation?.id)
        assertTrue("taken to the person", h.questions.any { it.startsWith("join c-1 Family bob") })

        h.repository.deferKeyAccess(q)
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
        h.repository.refreshMembers("c-1")
        assertTrue("never again for that invitation", h.repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `the chain's owner is asked about a plain member's invitee, naming the inviter`() = runTest {
        val h = harness { id -> if (id == "dan") MemberEligibility(id, false, null, false, true, false, null, false) else allowed(id) }
        h.earlierSeed("me", "carol")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("carol"), h.rosterMember("dan", h.invitation("dan", by = "carol", id = "inv-dan"))))

        h.repository.refreshMembers("c-1")

        val q = h.repository.keyAccessRequests.value.single()
        assertEquals("dan", q.userId)
        assertEquals(LateJoiners.Case.SIGNER_NOT_CHAIN_ADMIN, q.lateCase)
        assertEquals("Carol", q.inviterName)
    }

    @Test
    fun `an inviter this device has no name for is not named - no stand-in`() = runTest {
        val h = harness { id -> if (id == "dan") MemberEligibility(id, false, null, false, true, false, null, false) else allowed(id) }
        h.earlierSeed("me", "carol")
        // Carol, who invited dan, is listed with no name, and none is kept for her here: this device
        // keeps only its stand-in for her, which is no name.
        h.serverLists(
            listOf(h.rosterMember("me"), h.rosterMember("carol").copy(displayName = null), h.rosterMember("dan", h.invitation("dan", by = "carol", id = "inv-dan")))
        )
        coEvery { h.circleMemberDao.anyDisplayName("carol") } answers { h.storedRows.firstOrNull { it.userId == "carol" }?.displayName }

        h.repository.refreshMembers("c-1")

        val q = h.repository.keyAccessRequests.value.single()
        assertEquals("dan", q.userId)
        assertEquals(LateJoiners.Case.SIGNER_NOT_CHAIN_ADMIN, q.lateCase)
        assertNull("no \"A member invited them.\"", q.inviterName)
    }

    /** Whether this owner's device asks about bob, confirmed here and a late joiner by the eligibility
     * check, with [invitation] on his row.
     */
    private suspend fun asksAboutBob(invitation: (TrustHarness) -> MembershipStatements.Signed, setUp: (TrustHarness) -> Unit = {}): Boolean {
        val h = harness { id -> if (id == "bob") MemberEligibility(id, false, null, true, true, false, null, false) else allowed(id) }
        h.earlierSeed("me", "carol", "bob")
        setUp(h)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("carol"), h.rosterMember("bob", invitation(h), keyVersion = null)))
        h.repository.refreshMembers("c-1")
        return h.repository.keyAccessRequests.value.any { it.userId == "bob" && it.reason == KeyAccessRequest.Reason.LATE_JOINER }
    }

    @Test
    fun `only an invitation that holds here asks anyone - its signer's pin, or this account's own key`() = runTest {
        assertTrue("carol's, under her pin", asksAboutBob({ it.invitation("bob", by = "carol") }))
        assertTrue("this account's own", asksAboutBob({ it.invitation("bob", by = "me") }))
        assertTrue(
            "this account's own from before a reset",
            asksAboutBob({ it.invitation("bob", by = "me", signerKey = "old-me-s") }) { it.statements.previousSigningKeys += "old-me-s" },
        )

        assertFalse("forged: carol's pin doesn't hold it", asksAboutBob({ it.invitation("bob", by = "carol", signerKey = "mallory-s") }))
        assertFalse("says it's mine, and this account's key doesn't hold it", asksAboutBob({ it.invitation("bob", by = "me", signerKey = "mallory-s") }))
        assertFalse("an unpinned signer's", asksAboutBob({ it.invitation("bob", by = "zed") }))
        assertFalse(
            "another circle's",
            asksAboutBob({ it.statements.statement(Kind.INVITATION_TO_ACCOUNT, "c-2", "carol", TrustHarness.signing("carol"), "bob", id = "inv-c2") }),
        )
        assertFalse("another member's", asksAboutBob({ it.invitation("cy", by = "carol") }))
    }

    @Test
    fun `one address invitation on three rows asks one question, pass after pass`() = runTest {
        val h = harness { id -> if (id in setOf("bob", "cy", "dee")) MemberEligibility(id, false, null, false, false, false, null, true) else allowed(id) }
        h.earlierSeed("me", "bob", "cy", "dee")
        val mail = h.invitation("x", by = "me", kind = Kind.INVITATION_TO_EMAIL, email = "x@x.test", id = "inv-mail")
        h.serverLists(listOf(h.rosterMember("me")) + listOf("bob", "cy", "dee").map { h.rosterMember(it, mail, keyVersion = null) })

        h.repository.refreshMembers("c-1")
        assertEquals(listOf("bob"), h.repository.keyAccessRequests.value.map { it.userId })
        h.repository.refreshMembers("c-1")
        assertEquals(listOf("bob"), h.repository.keyAccessRequests.value.map { it.userId })
    }

    @Test
    fun `a question closed because its member got in elsewhere or left keeps its invitation answered - a later row with it asks nobody`() = runTest {
        for (leaves in listOf(false, true)) {
            var bobIn = false
            val h = harness { id ->
                when {
                    id == "bob" && bobIn -> allowed(id)
                    id == "bob" || id == "cy" -> MemberEligibility(id, false, null, false, false, false, null, true)
                    else -> allowed(id)
                }
            }
            h.earlierSeed("me", "bob", "cy")
            val mail = h.invitation("x", by = "me", kind = Kind.INVITATION_TO_EMAIL, email = "x@x.test", id = "inv-mail")
            // cy, of the seed from before signed invitations, is on the list all along, first with no
            // invitation on their row.
            val cyAlone = h.rosterMember("cy", keyVersion = null)
            h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", mail, keyVersion = null), cyAlone))
            h.repository.refreshMembers("c-1")
            assertEquals(listOf("bob"), h.repository.keyAccessRequests.value.map { it.userId })

            // bob got in on another device (or left); the invitation then shows up on cy's row.
            bobIn = !leaves
            val bobRow = if (leaves) emptyList() else listOf(h.rosterMember("bob", mail))
            h.serverLists(listOf(h.rosterMember("me"), cyAlone) + bobRow)
            h.repository.refreshMembers("c-1")
            assertTrue("left: $leaves", h.repository.keyAccessRequests.value.isEmpty())
            assertEquals(listOf("inv-mail"), h.repository.lateJoinerAnswers.answered("c-1").map { it.id })

            h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("cy", mail, keyVersion = null)) + bobRow)
            h.repository.refreshMembers("c-1")
            assertTrue("never asked again about that invitation (left: $leaves)", h.repository.keyAccessRequests.value.isEmpty())
            assertTrue("never taken to the person (left: $leaves)", h.questions.none { it.startsWith("join c-1 Family cy") })
        }
    }

    @Test
    fun `while the answers can't be read nobody is asked, and once they read an answered invitation stays answered`() = runTest {
        val h = harness { id -> if (id == "bob" || id == "cy") MemberEligibility(id, false, null, true, false, false, null, false) else allowed(id) }
        val prefs = DamagedPrefs()
        h.repository.lateJoinerAnswers = SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { prefs })
        h.earlierSeed("me")
        val bob = h.rosterMember("bob", h.invitation("bob", by = "me", id = "inv-bob"), keyVersion = null)
        h.serverLists(listOf(h.rosterMember("me"), bob))
        h.repository.refreshMembers("c-1")
        h.repository.deferKeyAccess(h.repository.keyAccessRequests.value.single())

        h.serverLists(listOf(h.rosterMember("me"), bob, h.rosterMember("cy", h.invitation("cy", by = "me", id = "inv-cy"), keyVersion = null)))
        prefs.damaged = true
        val stored = prefs.data.toMap()
        h.repository.refreshMembers("c-1")
        assertTrue("nobody asked while the answers can't be read", h.repository.keyAccessRequests.value.isEmpty())
        assertEquals("nothing written over them", stored, prefs.data)

        prefs.damaged = false
        h.repository.refreshMembers("c-1")
        assertEquals("bob's answer is still there: only cy is asked", listOf("cy"), h.repository.keyAccessRequests.value.map { it.userId })
    }

    @Test
    fun `the close counts in its own pass - the invitation showing up on another row as its member gets in asks nobody`() = runTest {
        var bobIn = false
        val h = harness { id ->
            when {
                id == "bob" && bobIn -> allowed(id)
                id == "bob" || id == "cy" -> MemberEligibility(id, false, null, false, false, false, null, true)
                else -> allowed(id)
            }
        }
        h.earlierSeed("me", "bob", "cy")
        val mail = h.invitation("x", by = "me", kind = Kind.INVITATION_TO_EMAIL, email = "x@x.test", id = "inv-mail")
        // cy, of the seed from before signed invitations, is on the list all along, first with no
        // invitation on their row.
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", mail, keyVersion = null), h.rosterMember("cy", keyVersion = null)))
        h.repository.refreshMembers("c-1")
        assertEquals(listOf("bob"), h.repository.keyAccessRequests.value.map { it.userId })

        // One read: bob got in elsewhere, and the same invitation is on cy's row.
        bobIn = true
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", mail), h.rosterMember("cy", mail, keyVersion = null)))
        h.repository.refreshMembers("c-1")

        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
        assertTrue("never taken to the person, not even for a moment", h.questions.none { it.startsWith("join c-1 Family cy") })
        assertEquals(listOf("inv-mail"), h.repository.lateJoinerAnswers.answered("c-1").map { it.id })
    }

    @Test
    fun `nobody is asked on a circle without records, nor on a plain member's device`() = runTest {
        val h = harness { id -> if (id == "bob") MemberEligibility(id, false, null, true, false, false, null, false) else allowed(id) }
        h.earlierSeed("me")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", h.invitation("bob", by = "me"), keyVersion = null)))
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false
        h.repository.refreshMembers("c-1")
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())

        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns true
        every { h.cryptoManager.chainRole("c-1", "me") } returns uniffi.pingolin_mobile.CircleRole.MEMBER
        h.repository.refreshMembers("c-1")
        assertTrue(h.repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `a joiner whose invitation ran out names whom it asked - the inviter if an owner or admin by its list, else the owner`() = runTest {
        // This device joined c-1 and holds no key for it yet.
        val h = TrustHarness(selfRole = "member")
        h.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() })
        h.heldKeyVersion = 0
        // Its inviter, listed, anchors this device and is pinned with the keys listed: the
        // invitation then holds under that pin.
        fun joinedWith(inviter: String, expiresAt: Long, signerKey: String = TrustHarness.signing(inviter), adaNamed: Boolean = true) = h.serverLists(
            listOf(
                h.rosterMember("olive", role = "owner"),
                h.rosterMember("ada", role = "admin").let { if (adaNamed) it else it.copy(displayName = null) },
                h.rosterMember("moe"),
                h.rosterMember("me", h.invitation("me", by = inviter, signerKey = signerKey, expiresAt = expiresAt), keyVersion = null),
            )
        )

        joinedWith("ada", expiresAt = h.statements.now - 301)
        h.repository.refreshMembers("c-1")
        assertEquals(LockedCircle.RanOut("Ada"), h.repository.invitationRanOutAsked("c-1"))

        joinedWith("ada", expiresAt = h.statements.now - 301, adaNamed = false)
        // What this device keeps for ada then: its stand-in, which is no name.
        coEvery { h.circleMemberDao.get("c-1", "ada") } answers { h.storedRows.firstOrNull { it.userId == "ada" } }
        h.repository.refreshMembers("c-1")
        assertEquals("no name here for whom it asked: nobody named, never \"A member\"", LockedCircle.RanOut(null), h.repository.invitationRanOutAsked("c-1"))

        joinedWith("moe", expiresAt = h.statements.now - 301)
        h.repository.refreshMembers("c-1")
        assertEquals("a plain member's invitation: the owner was asked", LockedCircle.RanOut("Olive"), h.repository.invitationRanOutAsked("c-1"))

        joinedWith("ada", expiresAt = h.statements.now - 299)
        h.repository.refreshMembers("c-1")
        assertNull("not run out yet (5 minutes' grace)", h.repository.invitationRanOutAsked("c-1"))

        joinedWith("ada", expiresAt = h.statements.now - 301, signerKey = "mallory-s")
        h.repository.refreshMembers("c-1")
        assertNull("forged: ada's pin doesn't hold it", h.repository.invitationRanOutAsked("c-1"))

        joinedWith("zed", expiresAt = h.statements.now - 301)
        h.repository.refreshMembers("c-1")
        assertNull("an inviter off the list and unpinned here: the plain line, not the owner", h.repository.invitationRanOutAsked("c-1"))

        joinedWith("ada", expiresAt = h.statements.now - 301)
        h.heldKeyVersion = 3
        h.repository.refreshMembers("c-1")
        assertNull("the circle is here", h.repository.invitationRanOutAsked("c-1"))
    }

    @Test
    fun `the let-in re-check for a late joiner asks E, not whether they're confirmed`() = runTest {
        var bob = MemberEligibility("bob", false, null, true, false, false, null, false)
        val h = harness { id -> if (id == "bob") bob else allowed(id) }
        h.earlierSeed("me")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("bob", h.invitation("bob", by = "me"), keyVersion = null)))

        assertEquals(CircleRepository.LetInNow.WAITING, h.repository.lateJoinerNow("c-1", "bob"))
        bob = allowed("bob")
        assertEquals("let in meanwhile", CircleRepository.LetInNow.LET_IN, h.repository.lateJoinerNow("c-1", "bob"))
        bob = MemberEligibility("bob", false, null, false, false, false, null, false)
        assertEquals(CircleRepository.LetInNow.NOT_ASKED, h.repository.lateJoinerNow("c-1", "bob"))
    }
}
