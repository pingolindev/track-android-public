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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a device trusts in a circle: every branch. The statements are a stand-in with the
 * SDK's shape — a statement is its fields, and a signature is valid under exactly one key.
 */
class MemberConfirmationTest {

    /** "kind|circle|id|signer|subject|sharing|signing|email|atVersion|expiresAt"; signature = "key:bytes". */
    private object FakeStatements : MembershipStatements {
        override fun parse(statementHex: String): MembershipStatements.Statement? {
            val f = statementHex.split("|")
            if (f.size != 10) return null
            return MembershipStatements.Statement(
                kind = Kind.valueOf(f[0]), circleId = f[1], statementId = f[2], signerUserId = f[3],
                subjectUserId = f[4].ifEmpty { null }, subjectSharingKeyHex = f[5].ifEmpty { null },
                subjectSigningKeyHex = f[6].ifEmpty { null }, subjectEmail = f[7].ifEmpty { null },
                atVersion = f[8].toInt(), expiresAt = f[9].toLong(),
            )
        }
        override fun verify(statementHex: String, signatureHex: String, signerSigningKeyHex: String) =
            parse(statementHex) != null && signatureHex == "$signerSigningKeyHex:$statementHex"
        override fun signInvitationToAccount(circleId: String, signerUserId: String, subjectUserId: String, subjectSharingKeyHex: String, subjectSigningKeyHex: String) = error("unused")
        override fun signInvitationToEmail(circleId: String, signerUserId: String, email: String) = error("unused")
        override fun signAdmission(circleId: String, signerUserId: String, subjectUserId: String, subjectSharingKeyHex: String, subjectSigningKeyHex: String) = error("unused")
    }

    private val circle = "c-1"
    private val now = 1_800_000_000L
    private var nextId = 0

    /** This device's pins (circle pin, else identity pin): the owner and alice. */
    private val pinnedSharing = mutableMapOf("owner" to "owner-x", "alice" to "alice-x")
    private val pinnedSigning = mutableMapOf("owner" to "owner-s", "alice" to "alice-s")

    /** Keys as the member list shows them: "<user>-x" and "<user>-s". */
    private fun member(
        id: String,
        vararg carried: MembershipStatements.Signed,
        keyVersion: Int? = 1,
        sharing: String = "$id-x",
        signing: String = "$id-s",
    ) = MemberConfirmation.Member(id, sharing, signing, keyVersion, carried.toList())

    private fun sign(
        kind: Kind,
        signer: String,
        signerKey: String = "$signer-s",
        subject: String? = null,
        circleId: String = circle,
        id: String = "st-${nextId++}",
        sharing: String? = subject?.let { "$it-x" },
        signing: String? = subject?.let { "$it-s" },
        email: String? = null,
        atVersion: Int = 1,
        expiresAt: Long = if (kind == Kind.ADMISSION) 0 else now + 3600,
    ): MembershipStatements.Signed {
        val bytes = listOf(
            kind.name, circleId, id, signer, subject.orEmpty(), sharing.orEmpty(), signing.orEmpty(),
            email.orEmpty(), atVersion.toString(), expiresAt.toString(),
        ).joinToString("|")
        return MembershipStatements.Signed(bytes, "$signerKey:$bytes")
    }

    private fun evaluate(
        roster: List<MemberConfirmation.Member>,
        approvals: Map<String, Origin> = mapOf("owner" to Origin.PRE_ROUND2),
        preRound2Seed: Boolean = true,
        tombstones: Map<String, Int> = emptyMap(),
        deadIds: Set<String> = emptySet(),
        selfEmail: String? = "me@x.test",
        previousIdentity: Pair<String, String>? = null,
        statements: MembershipStatements = FakeStatements,
    ) = MemberConfirmation.evaluate(
        selfUserId = "me", selfSharingKeyHex = "me-x", selfSigningKeyHex = "me-s", selfEmail = selfEmail,
        circleId = circle, roster = roster,
        known = MemberConfirmation.Known(
            approvals = approvals, preRound2Seed = preRound2Seed, tombstones = tombstones,
            tombstonedStatementIds = deadIds,
            pinnedSharingKey = { pinnedSharing[it] }, pinnedSigningKey = { pinnedSigning[it] },
            isOwnPreviousIdentity = { sharing, signing -> previousIdentity == (sharing to signing) },
        ),
        statements = statements,
    )

    /** FakeStatements, and this account's signing key from before a reset, restored here. */
    private class WithPreviousKey(private val previousKey: String) : MembershipStatements by FakeStatements {
        override fun signedByOwnPreviousKey(statementHex: String, signatureHex: String) =
            FakeStatements.verify(statementHex, signatureHex, previousKey) && !FakeStatements.verify(statementHex, signatureHex, "me-s")
    }

    // ---- confirmed ------------------------------------------------------------------

    @Test
    fun `a newcomer invited by a confirmed member is confirmed with no question`() {
        val result = evaluate(listOf(member("me"), member("owner"), member("bob", sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob"), keyVersion = null)))
        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertEquals(mapOf("bob" to Origin.STATEMENT), result.newlyConfirmed)
        assertEquals("bob-x" to "bob-s", result.boundKeys["bob"])
        assertTrue("bob" in result.shown)
        assertTrue(result.askable.isEmpty() && result.keptOut.isEmpty())
    }

    @Test
    fun `an invitation this user signed counts, under this device's own key`() {
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "me", subject = "bob")
        assertTrue("bob" in evaluate(listOf(member("me"), member("bob", invite)), approvals = emptyMap()).confirmed)
        // Not under a key the member list shows for this user.
        val other = sign(Kind.INVITATION_TO_ACCOUNT, "me", signerKey = "server-s", subject = "carol")
        assertEquals(setOf("carol"), evaluate(listOf(member("me", signing = "server-s"), member("carol", other)), approvals = emptyMap()).invalid)
    }

    @Test
    fun `an approval from before signed invitations needs no statement`() {
        val result = evaluate(listOf(member("owner"), member("alice")), approvals = mapOf("owner" to Origin.PRE_ROUND2, "alice" to Origin.PRE_ROUND2))
        assertEquals(setOf("me", "owner", "alice"), result.confirmed.keys)
        assertTrue(result.newlyConfirmed.isEmpty())
    }

    @Test
    fun `a newcomer invited by another newcomer is confirmed in the same pass`() {
        val bob = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val carol = sign(Kind.INVITATION_TO_ACCOUNT, "bob", subject = "carol")
        // carol comes first: only a fixpoint gets her. bob is not pinned yet — he is taken
        // with the keys the owner's invitation binds, which is what gets pinned for him.
        val result = evaluate(listOf(member("carol", carol), member("owner"), member("bob", bob)))
        assertEquals(setOf("bob", "carol"), result.newlyConfirmed.keys)
    }

    @Test
    fun `with no pin, the keys the inviter saw are what count, whatever the member list shows now`() {
        // The member list shows bob with other keys than the owner's invitation bound (a
        // later reset, or a lie now): bob is confirmed with the bound keys, which get
        // pinned; the difference is then "Is this really bob?".
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val result = evaluate(listOf(member("owner"), member("bob", invite, sharing = "bob-new-x", signing = "bob-new-s")))
        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertEquals("bob-x" to "bob-s", result.boundKeys["bob"])
    }

    // ---- invalid: hidden everywhere --------------------------------------------------

    @Test
    fun `a forged statement is invalid`() {
        val forged = sign(Kind.INVITATION_TO_ACCOUNT, "owner", signerKey = "forger-s", subject = "ghost")
        val result = evaluate(listOf(member("owner"), member("ghost", forged)))
        assertEquals(setOf("ghost"), result.invalid)
        assertFalse("ghost" in result.shown)
        assertFalse("ghost" in result.askable)
        assertTrue("ghost" in result.keptOut)
    }

    @Test
    fun `a statement for another circle or another member is invalid for that row`() {
        val otherCircle = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "g1", circleId = "c-2")
        val otherSubject = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "someone-else")
        val result = evaluate(listOf(member("owner"), member("g1", otherCircle), member("g2", otherSubject)))
        assertEquals(setOf("g1", "g2"), result.invalid)
    }

    @Test
    fun `an email invitation carried by two members is invalid for both`() {
        val a = sign(Kind.INVITATION_TO_EMAIL, "owner", id = "same", email = "a@x.test")
        val b = sign(Kind.INVITATION_TO_EMAIL, "owner", id = "same", email = "b@x.test")
        assertEquals(setOf("a", "b"), evaluate(listOf(member("owner"), member("a", a), member("b", b))).invalid)
    }

    @Test
    fun `a member's account invitation copied to another row hides only the copy`() {
        val bobs = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val result = evaluate(listOf(member("owner"), member("bob", bobs), member("copycat", bobs)))
        assertTrue("bob" in result.confirmed)
        assertEquals(setOf("copycat"), result.invalid)
    }

    @Test
    fun `a departed member's statements never vouch again, and a returning member needs a newer one`() {
        val old = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "x", id = "dead", atVersion = 3)
        assertEquals(setOf("x"), evaluate(listOf(member("owner"), member("x", old)), deadIds = setOf("dead")).invalid)
        // Moved to another row as an email invitation: still dead.
        val moved = sign(Kind.INVITATION_TO_EMAIL, "owner", id = "dead2", email = "y@x.test")
        assertEquals(setOf("y"), evaluate(listOf(member("owner"), member("y", moved)), deadIds = setOf("dead2")).invalid)

        val tooOld = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "x", atVersion = 3)
        assertEquals(setOf("x"), evaluate(listOf(member("owner"), member("x", tooOld)), tombstones = mapOf("x" to 4)).invalid)

        val fresh = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "x", atVersion = 4)
        assertTrue("x" in evaluate(listOf(member("owner"), member("x", fresh)), tombstones = mapOf("x" to 4)).confirmed)
    }

    @Test
    fun `nobody vouches for themselves`() {
        pinnedSigning["selfie"] = "selfie-s"
        pinnedSharing["selfie"] = "selfie-x"
        val selfAdmission = sign(Kind.ADMISSION, "selfie", subject = "selfie")
        assertEquals(setOf("selfie"), evaluate(listOf(member("owner"), member("selfie", selfAdmission))).invalid)
    }

    // ---- A statement whose signer is its subject never confirms, approves, binds keys or holds up as
    // evidence; an owner's or admin's own admission of its new keys counts only in the SDK's eligibility
    // check, never here.

    @Test
    fun `a confirmed member's own admission, verifying under their pin, is never evidence`() {
        pinnedSharing["dave"] = "dave-x"
        pinnedSigning["dave"] = "dave-s"
        val own = sign(Kind.ADMISSION, "dave", subject = "dave")
        val result = evaluate(
            listOf(member("owner"), member("dave", own)),
            approvals = mapOf("owner" to Origin.PRE_ROUND2, "dave" to Origin.STATEMENT),
        )
        assertEquals(Origin.STATEMENT, result.confirmed["dave"])
        assertTrue("nothing for a hand-over or a vouch rests on it", result.holding["dave"].isNullOrEmpty())
        assertTrue(result.newlyConfirmed.isEmpty() && result.boundKeys.isEmpty())
    }

    @Test
    fun `a member whose only statement is their own is invalid, never asked about, pin or none`() {
        val own = sign(Kind.ADMISSION, "erin", subject = "erin")
        for (pinned in listOf(false, true)) {
            if (pinned) {
                pinnedSharing["erin"] = "erin-x"
                pinnedSigning["erin"] = "erin-s"
            }
            val result = evaluate(listOf(member("owner"), member("erin", own)))
            assertEquals("pinned=$pinned", setOf("erin"), result.invalid)
            assertFalse("pinned=$pinned", "erin" in result.confirmed)
            assertFalse("pinned=$pinned", "erin" in result.askable)
            assertTrue("pinned=$pinned", result.newlyConfirmed.isEmpty() && result.boundKeys.isEmpty())
        }
    }

    @Test
    fun `a newcomer confirmed by an invitation binds the invitation's keys, never those of their own admission`() {
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val own = sign(Kind.ADMISSION, "bob", signerKey = "bob-new-s", subject = "bob", sharing = "bob-new-x", signing = "bob-new-s")
        val result = evaluate(listOf(member("owner"), member("bob", invite, own, keyVersion = null)))
        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertEquals("bob-x" to "bob-s", result.boundKeys["bob"])
        assertEquals(listOf(Kind.INVITATION_TO_ACCOUNT), result.holding["bob"]?.map { it.kind })
    }

    @Test
    fun `a member's own admission of OTHER keys changes no verdict - confirmed by approval or by an invitation, the same`() {
        // Approved here, the member list showing the pinned keys.
        pinnedSharing["dave"] = "dave-x"
        pinnedSigning["dave"] = "dave-s"
        val daveOwn = sign(Kind.ADMISSION, "dave", signerKey = "dave-new-s", subject = "dave", sharing = "dave-new-x", signing = "dave-new-s")
        val approvals = mapOf("owner" to Origin.PRE_ROUND2, "dave" to Origin.OWN)
        val withoutDave = evaluate(listOf(member("owner"), member("dave")), approvals = approvals)
        val withDave = evaluate(listOf(member("owner"), member("dave", daveOwn)), approvals = approvals)
        assertEquals(withoutDave.confirmed, withDave.confirmed)
        assertTrue(withDave.holding["dave"].isNullOrEmpty())
        assertFalse("dave" in withDave.subjectConflict || "dave" in withDave.invalid)

        // Confirmed by an owner's invitation (another basis), with no pin: the same verdict and the same bound keys.
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val bobOwn = sign(Kind.ADMISSION, "bob", signerKey = "bob-new-s", subject = "bob", sharing = "bob-new-x", signing = "bob-new-s")
        val without = evaluate(listOf(member("owner"), member("bob", invite, keyVersion = null)))
        val with = evaluate(listOf(member("owner"), member("bob", invite, bobOwn, keyVersion = null)))
        assertEquals(without.confirmed, with.confirmed)
        assertEquals(without.boundKeys, with.boundKeys)
        assertEquals(without.holding["bob"]?.map { it.statementId }, with.holding["bob"]?.map { it.statementId })
    }

    @Test
    fun `the member list's keys against the pin make the conflict, with or without the member's own admission`() {
        // frank is pinned with his old keys; the list shows new ones, which the owner's invitation binds.
        pinnedSharing["frank"] = "frank-x"
        pinnedSigning["frank"] = "frank-s"
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "frank", sharing = "frank-new-x", signing = "frank-new-s")
        val own = sign(Kind.ADMISSION, "frank", signerKey = "frank-new-s", subject = "frank", sharing = "frank-new-x", signing = "frank-new-s")
        fun listed(vararg st: MembershipStatements.Signed) = member("frank", *st, sharing = "frank-new-x", signing = "frank-new-s")
        val without = evaluate(listOf(member("owner"), listed(invite)))
        val with = evaluate(listOf(member("owner"), listed(invite, own)))
        assertEquals(setOf("frank"), without.subjectConflict)
        assertEquals(setOf("frank"), with.subjectConflict)
        assertFalse("frank" in with.confirmed)
    }

    @Test
    fun `a receive-only member's own admission never lifts receive-only`() {
        pinnedSharing["pal"] = "pal-x"
        pinnedSigning["pal"] = "pal-s"
        val own = sign(Kind.ADMISSION, "pal", subject = "pal")
        val result = evaluate(
            listOf(member("owner"), member("pal", own)),
            approvals = mapOf("owner" to Origin.PRE_ROUND2, "pal" to Origin.KIND2),
        )
        assertEquals(Origin.KIND2, result.confirmed["pal"])
        assertTrue(result.receiveOnly("pal"))
        assertTrue(result.newlyConfirmed.isEmpty())
    }

    @Test
    fun `a pin alone never confirms a member`() {
        // Pinned with exactly the keys the member list shows, and no approval or statement.
        pinnedSharing["zed"] = "zed-x"
        pinnedSigning["zed"] = "zed-s"
        val result = evaluate(listOf(member("owner"), member("zed")))
        assertFalse("zed" in result.confirmed)
        assertEquals(setOf("zed"), result.statementLess)
    }

    @Test
    fun `malformed bytes are invalid`() {
        val garbage = MembershipStatements.Signed("not-a-statement", "owner-s:not-a-statement")
        assertEquals(setOf("x"), evaluate(listOf(member("owner"), member("x", garbage))).invalid)
    }

    // ---- unconfirmable: hidden everywhere, asked about on admin devices ------------------
    // ---- statement-less: hidden everywhere, never asked about ---------------------------

    @Test
    fun `a member with no statement is hidden, counted as kept out, and never asked about`() {
        val result = evaluate(listOf(member("owner"), member("old")))
        assertEquals(setOf("old"), result.statementLess)
        assertFalse("old" in result.askable)
        assertFalse("old" in result.shown)
        assertTrue("old" in result.keptOut)
    }

    @Test
    fun `a member let in earlier stays confirmed, statement or not`() {
        // A "Let them in" made before statement-less members were hidden
        // recorded a permanent own approval.
        val result = evaluate(listOf(member("owner"), member("old")), approvals = mapOf("owner" to Origin.PRE_ROUND2, "old" to Origin.OWN))
        assertEquals(Origin.OWN, result.confirmed["old"])
        assertTrue("old" in result.shown)
        assertFalse("old" in result.keptOut)
        assertTrue(result.statementLess.isEmpty())
    }

    @Test
    fun `a signer this device does not confirm leaves the member askable, an email invitee never`() {
        val ghostInvite = sign(Kind.INVITATION_TO_ACCOUNT, "ghost", subject = "friend")
        val ghostEmail = sign(Kind.INVITATION_TO_EMAIL, "ghost", email = "pal@x.test")
        val result = evaluate(listOf(member("owner"), member("ghost"), member("friend", ghostInvite), member("pal", ghostEmail)))
        assertEquals(setOf("friend", "pal"), result.signerUnconfirmed)
        assertEquals(setOf("friend"), result.askSigner)
        assertTrue("friend" in result.askable)
        assertFalse("pal" in result.askable)
        assertTrue("pal" in result.keptOut)
        assertTrue("ghost" in result.statementLess)
        assertEquals(setOf("friend"), result.askable)
    }

    @Test
    fun `an inviter who has left no longer vouches, even with an identity pin`() {
        // alice is still pinned (identity pin) but not on the member list.
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "alice", subject = "bob")
        val result = evaluate(listOf(member("owner"), member("bob", invite)), approvals = mapOf("owner" to Origin.PRE_ROUND2, "alice" to Origin.PRE_ROUND2))
        assertEquals(setOf("bob"), result.signerUnconfirmed)
        assertFalse("bob" in result.confirmed)
    }

    @Test
    fun `a signer whose key changed since this device pinned it leaves the invitee waiting, not invalid`() {
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", signerKey = "owner-new-s", subject = "bob")
        val result = evaluate(listOf(member("owner", signing = "owner-new-s"), member("bob", invite)))
        assertEquals(setOf("bob"), result.signerChanged)
        assertTrue(result.askable.isEmpty() && result.keptOut.isEmpty() && result.invalid.isEmpty())
    }

    // ---- subject conflict: shown, with "Is this really {name}?" ----------------------

    @Test
    fun `keys other than this device's pin for the member are a conflict, never a confirmation`() {
        pinnedSharing["bob"] = "bob-old-x"
        pinnedSigning["bob"] = "bob-old-s"
        val invite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val result = evaluate(listOf(member("owner"), member("bob", invite)))
        assertEquals(setOf("bob"), result.subjectConflict)
        assertTrue("bob" in result.shown)
        assertFalse("bob" in result.confirmed)
        assertFalse("bob" in result.askable)
    }

    // ---- kind 2: receive-only --------------------------------------------------------

    @Test
    fun `an email invitation confirms receive-only, and a receive-only member vouches for nobody`() {
        val grandma = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "grandma@x.test")
        val herInvite = sign(Kind.INVITATION_TO_ACCOUNT, "grandma", subject = "cousin")
        val result = evaluate(listOf(member("owner"), member("grandma", grandma), member("cousin", herInvite)))
        assertEquals(Origin.KIND2, result.confirmed["grandma"])
        assertTrue(result.receiveOnly("grandma"))
        assertTrue("grandma" !in result.boundKeys)
        assertEquals(setOf("cousin"), result.signerUnconfirmed)
    }

    @Test
    fun `an admission lifts receive-only, and this user's own email invitee stays their own choice`() {
        val admission = sign(Kind.ADMISSION, "owner", subject = "grandma", atVersion = 2)
        val lifted = evaluate(listOf(member("owner"), member("grandma", admission)), approvals = mapOf("owner" to Origin.PRE_ROUND2, "grandma" to Origin.KIND2))
        assertEquals(Origin.STATEMENT, lifted.confirmed["grandma"])
        assertEquals(mapOf("grandma" to Origin.STATEMENT), lifted.newlyConfirmed)

        val own = evaluate(listOf(member("owner"), member("grandma", admission)), approvals = mapOf("owner" to Origin.PRE_ROUND2, "grandma" to Origin.OWN_KIND2))
        assertEquals(Origin.OWN, own.confirmed["grandma"])
    }

    // ---- anchors: a device with no seed from before signed invitations ---------------

    @Test
    fun `with no seed from before signed invitations, trust starts from whoever signed this user's own invitation`() {
        pinnedSigning.clear(); pinnedSharing.clear()
        val mine = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me")
        val bobs = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "bob")
        val result = evaluate(
            listOf(member("me", mine), member("owner"), member("bob", bobs), member("ghost")),
            approvals = emptyMap(), preRound2Seed = false,
        )
        assertEquals(Origin.ANCHOR, result.confirmed["owner"])
        assertEquals(setOf("me", "owner", "bob"), result.confirmed.keys)
        // Nobody is trusted merely for being on the list.
        assertTrue("ghost" in result.statementLess)
    }

    @Test
    fun `an own invitation that names other keys anchors nothing, unless they are a previous identity held here`() {
        pinnedSigning.clear(); pinnedSharing.clear()
        val swapped = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me", sharing = "old-x", signing = "old-s")
        assertEquals(setOf("me"), evaluate(listOf(member("me", swapped), member("owner")), approvals = emptyMap(), preRound2Seed = false).confirmed.keys)
        // This device holds that previous identity's private key.
        val anchored = evaluate(listOf(member("me", swapped), member("owner")), approvals = emptyMap(), preRound2Seed = false, previousIdentity = "old-x" to "old-s")
        assertEquals(Origin.ANCHOR, anchored.confirmed["owner"])
    }

    @Test
    fun `an own email invitation anchors only for this account's email`() {
        pinnedSigning.clear(); pinnedSharing.clear()
        val mine = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "me@x.test")
        assertTrue("owner" in evaluate(listOf(member("me", mine), member("owner")), approvals = emptyMap(), preRound2Seed = false).confirmed)
        val notMine = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "other@x.test")
        assertFalse("owner" in evaluate(listOf(member("me", notMine), member("owner")), approvals = emptyMap(), preRound2Seed = false).confirmed)
    }

    @Test
    fun `an own email invitation to another address, or with no address here, anchors nobody and says so`() {
        // The e-mail trust check (as on iOS): that is what keeps the first copy from being taken on trust.
        pinnedSigning.clear(); pinnedSharing.clear()
        val notMine = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "other@x.test")
        val other = evaluate(listOf(member("me", notMine), member("owner")), approvals = emptyMap(), preRound2Seed = false)
        assertFalse(other.anchored)
        assertTrue(other.ownEmailInvitationUnconfirmed)

        val mine = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "me@x.test")
        val none = evaluate(listOf(member("me", mine), member("owner")), approvals = emptyMap(), preRound2Seed = false, selfEmail = null)
        assertFalse(none.anchored)
        assertTrue(none.ownEmailInvitationUnconfirmed)

        val known = evaluate(listOf(member("me", mine), member("owner")), approvals = emptyMap(), preRound2Seed = false, selfEmail = " ME@x.test ")
        assertTrue(known.anchored)
        assertFalse(known.ownEmailInvitationUnconfirmed)
    }

    @Test
    fun `only this circle's email invitation on this user's own row counts for the e-mail trust check`() {
        pinnedSigning.clear(); pinnedSharing.clear()
        val elsewhere = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "other@x.test", circleId = "c-2")
        assertFalse(evaluate(listOf(member("me", elsewhere), member("owner")), approvals = emptyMap(), preRound2Seed = false).ownEmailInvitationUnconfirmed)
        val anothers = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "alice@x.test")
        assertFalse(evaluate(listOf(member("me"), member("owner"), member("alice", anothers)), approvals = emptyMap(), preRound2Seed = false).ownEmailInvitationUnconfirmed)
        val account = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me", sharing = "old-x", signing = "old-s")
        assertFalse(evaluate(listOf(member("me", account), member("owner")), approvals = emptyMap(), preRound2Seed = false).ownEmailInvitationUnconfirmed)
    }

    @Test
    fun `a pin alone anchors nothing, a member trusted in another circle does`() {
        // owner and alice are pinned with the keys the list shows (builds before signed
        // invitations pinned everyone they were shown, ghosts too); only the owner is
        // trusted elsewhere by this device's records.
        val none = evaluate(listOf(member("owner"), member("alice")), approvals = emptyMap(), preRound2Seed = false)
        assertEquals(setOf("me"), none.confirmed.keys)
        assertFalse(none.anchored)

        val result = MemberConfirmation.evaluate(
            selfUserId = "me", selfSharingKeyHex = "me-x", selfSigningKeyHex = "me-s", selfEmail = "me@x.test",
            circleId = circle, roster = listOf(member("owner"), member("alice")),
            known = MemberConfirmation.Known(
                preRound2Seed = false,
                pinnedSharingKey = { pinnedSharing[it] }, pinnedSigningKey = { pinnedSigning[it] },
                trustedElsewhere = { setOf("OWNER") },
            ),
            statements = FakeStatements,
        )
        assertEquals(mapOf("me" to Origin.OWN, "owner" to Origin.ANCHOR), result.confirmed)
        // Trusted elsewhere but listed here with other keys: not an anchor.
        pinnedSharing["owner"] = "owner-old-x"
        val changed = MemberConfirmation.evaluate(
            selfUserId = "me", selfSharingKeyHex = "me-x", selfSigningKeyHex = "me-s", selfEmail = "me@x.test",
            circleId = circle, roster = listOf(member("owner")),
            known = MemberConfirmation.Known(
                preRound2Seed = false,
                pinnedSharingKey = { pinnedSharing[it] }, pinnedSigningKey = { pinnedSigning[it] },
                trustedElsewhere = { setOf("owner") },
            ),
            statements = FakeStatements,
        )
        assertFalse("owner" in changed.confirmed)
    }

    @Test
    fun `members this account's own head vouches for are anchors, with exactly the keys it matched`() {
        // The continuity statement for this account's own devices.
        fun judged(vouched: Map<String, Pair<String, String>>, roster: List<MemberConfirmation.Member>, seed: Boolean = false) =
            MemberConfirmation.evaluate(
                selfUserId = "me", selfSharingKeyHex = "me-x", selfSigningKeyHex = "me-s", selfEmail = "me@x.test",
                circleId = circle, roster = roster,
                known = MemberConfirmation.Known(preRound2Seed = seed, vouchedByOwnHead = vouched),
                statements = FakeStatements,
            )
        // A fresh install: no pins, no records, nothing signed about bob.
        val fresh = judged(mapOf("BOB" to ("bob-x" to "bob-s")), listOf(member("me"), member("bob"), member("carol")))
        assertEquals(mapOf("me" to Origin.OWN, "bob" to Origin.ANCHOR), fresh.confirmed)
        assertEquals(mapOf("bob" to Origin.ANCHOR), fresh.newlyConfirmed)

        // Listed now with other keys than the vouch matched: not an anchor.
        assertFalse("bob" in judged(mapOf("bob" to ("bob-x" to "bob-s")), listOf(member("bob", sharing = "bob-new-x"))).confirmed)
        // Not listed: nothing to confirm.
        assertEquals(setOf("me"), judged(mapOf("dan" to ("dan-x" to "dan-s")), listOf(member("bob"))).confirmed.keys)
        // A device with a seed from before signed invitations starts from the seed,
        // as for every anchor.
        assertFalse("bob" in judged(mapOf("bob" to ("bob-x" to "bob-s")), listOf(member("bob")), seed = true).confirmed)
    }

    @Test
    fun `this account's own admissions that hold up for the keys listed now are named, for the stray-choice look`() {
        val mine = sign(Kind.ADMISSION, "me", subject = "bob", atVersion = 3)
        val otherKeys = sign(Kind.ADMISSION, "me", subject = "carol", sharing = "carol-old-x", signing = "carol-old-s", atVersion = 3)
        val beforeLeaving = sign(Kind.ADMISSION, "me", subject = "dan", atVersion = 1)
        val byOwner = sign(Kind.ADMISSION, "owner", subject = "erin", atVersion = 3)

        val result = evaluate(
            listOf(member("me"), member("owner"), member("bob", mine), member("carol", otherKeys), member("dan", beforeLeaving), member("erin", byOwner)),
            tombstones = mapOf("dan" to 2),
        )

        assertEquals(setOf("bob"), result.ownAdmissions)
    }

    @Test
    fun `a device with a seed from before signed invitations starts from the seed, not from anchors`() {
        val result = evaluate(listOf(member("owner"), member("alice"), member("bob")), approvals = mapOf("owner" to Origin.PRE_ROUND2))
        assertEquals(setOf("me", "owner"), result.confirmed.keys)
        assertFalse(evaluate(listOf(member("zed")), approvals = emptyMap(), preRound2Seed = false).anchored)
    }

    // ---- this user's own app: receive-only ------------------------------------

    @Test
    fun `this user's own app is receive-only on an email invitation or statements about old keys, until an admission`() {
        val emailInvite = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "me@x.test")
        assertTrue(evaluate(listOf(member("me", emailInvite), member("owner"))).selfReceiveOnly)

        val oldKeys = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me", sharing = "me-old-x", signing = "me-old-s")
        assertTrue(evaluate(listOf(member("me", oldKeys), member("owner"))).selfReceiveOnly)

        val admission = sign(Kind.ADMISSION, "owner", subject = "me")
        assertFalse(evaluate(listOf(member("me", oldKeys, admission), member("owner"))).selfReceiveOnly)
        // A current invitation, or none at all (from before signed invitations), is fine.
        assertFalse(evaluate(listOf(member("me", sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me")), member("owner"))).selfReceiveOnly)
        assertFalse(evaluate(listOf(member("me"), member("owner"))).selfReceiveOnly)
        // A forged admission lifts nothing.
        val forged = sign(Kind.ADMISSION, "owner", signerKey = "forger-s", subject = "me")
        assertTrue(evaluate(listOf(member("me", oldKeys, forged), member("owner"))).selfReceiveOnly)
    }

    // ---- who may deliver ----------------------------------------------------------

    private fun statement(kind: Kind, atVersion: Int = 1, expiresAt: Long = now + 3600) =
        MembershipStatements.Statement(kind, circle, "s", "owner", atVersion = atVersion, expiresAt = expiresAt)

    @Test
    fun `a keyless member invited recently is delivered to by anyone`() {
        val keyless = member("bob", keyVersion = null)
        assertTrue(MemberConfirmation.mayDeliver(keyless, Origin.STATEMENT, listOf(statement(Kind.INVITATION_TO_ACCOUNT)), activeVersion = 5, nowSecs = now))
        assertTrue(MemberConfirmation.mayDeliver(keyless, Origin.KIND2, listOf(statement(Kind.INVITATION_TO_EMAIL)), 5, now))
        // Within five minutes' grace for clocks; not after.
        assertTrue(MemberConfirmation.mayDeliver(keyless, Origin.STATEMENT, listOf(statement(Kind.INVITATION_TO_ACCOUNT, expiresAt = now - 300)), 5, now))
        assertFalse(MemberConfirmation.mayDeliver(keyless, Origin.STATEMENT, listOf(statement(Kind.INVITATION_TO_ACCOUNT, expiresAt = now - 301)), 5, now))
        // Not keyless (left pending by a rotation): left to the device that created the circle.
        assertFalse(MemberConfirmation.mayDeliver(member("bob", keyVersion = 4), Origin.STATEMENT, listOf(statement(Kind.INVITATION_TO_ACCOUNT)), 5, now))
        assertTrue(MemberConfirmation.mayDeliver(member("bob", keyVersion = 4), Origin.PRE_ROUND2, emptyList(), 5, now, createdHere = true))
    }

    @Test
    fun `an admission counts from its own version on, and this user's own choices always do`() {
        val pending = member("bob", keyVersion = 4)
        assertTrue(MemberConfirmation.mayDeliver(pending, Origin.STATEMENT, listOf(statement(Kind.ADMISSION, atVersion = 5)), activeVersion = 5, nowSecs = now))
        assertFalse(MemberConfirmation.mayDeliver(pending, Origin.STATEMENT, listOf(statement(Kind.ADMISSION, atVersion = 4)), activeVersion = 5, nowSecs = now))
        assertTrue(MemberConfirmation.mayDeliver(pending, Origin.OWN, emptyList(), activeVersion = 5, nowSecs = now))
        assertTrue(MemberConfirmation.mayDeliver(pending, Origin.OWN_KIND2, emptyList(), activeVersion = 5, nowSecs = now))
        assertFalse(MemberConfirmation.mayDeliver(pending, Origin.PRE_ROUND2, emptyList(), activeVersion = 5, nowSecs = now))
        assertFalse(MemberConfirmation.mayDeliver(pending, Origin.ANCHOR, emptyList(), activeVersion = 5, nowSecs = now))
        assertFalse(MemberConfirmation.mayDeliver(pending, null, emptyList(), activeVersion = 5, nowSecs = now, createdHere = true))
    }

    // ---- this account's own statements from before a reset ----------------------------

    @Test
    fun `an own invitation signed before a reset confirms, but nothing is handed over on it`() {
        val old = sign(Kind.INVITATION_TO_ACCOUNT, "me", signerKey = "me-old-s", subject = "bob")
        val roster = listOf(member("me"), member("owner"), member("bob", old, keyVersion = null))
        val result = evaluate(roster, statements = WithPreviousKey("me-old-s"))

        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertTrue("bob" in result.shown)
        assertEquals(setOf("bob"), result.previousKeyOnly)
        assertEquals("pinned with the keys it bound", "bob-x" to "bob-s", result.boundKeys["bob"])
        assertTrue("no evidence for a hand-over", result.holding["bob"].isNullOrEmpty())
        // Not handed over, not even by the device that created the circle; never vouched for.
        assertTrue(AccessDelivery.plan(
            "me", result, roster, emptySet(), activeVersion = 1, nowSecs = now, createdHere = true,
            pinnedSharingKey = { "$it-x" }, receiveOnlyKeys = emptyMap(),
        ).none { it.userId == "bob" })
        assertTrue(AdmissionBackfill.candidates(
            "me", result, roster, preRound2Seed = true, ownerOrAdmin = true, alreadyBackfilled = emptySet(),
            pinnedSharingKey = { "$it-x" }, pinnedSigningKey = { "$it-s" }, handedOverByConfirmed = { true },
        ).none { it.userId == "bob" })
    }

    @Test
    fun `without the restored identity, an own statement from before a reset is invalid`() {
        val old = sign(Kind.INVITATION_TO_ACCOUNT, "me", signerKey = "me-old-s", subject = "bob")
        assertEquals(setOf("bob"), evaluate(listOf(member("me"), member("bob", old))).invalid)
        // Nor does a restored key speak for anyone else.
        val notOurs = sign(Kind.INVITATION_TO_ACCOUNT, "mallory", signerKey = "me-old-s", subject = "carol")
        assertTrue("carol" !in evaluate(listOf(member("me"), member("mallory"), member("carol", notOurs)), statements = WithPreviousKey("me-old-s")).confirmed)
    }

    @Test
    fun `a statement under the current key is what counts when there is one`() {
        val old = sign(Kind.INVITATION_TO_ACCOUNT, "me", signerKey = "me-old-s", subject = "bob")
        val admission = sign(Kind.ADMISSION, "owner", subject = "bob")
        val result = evaluate(listOf(member("me"), member("owner"), member("bob", old, admission)), statements = WithPreviousKey("me-old-s"))

        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertTrue(result.previousKeyOnly.isEmpty())
        assertEquals(listOf(Kind.ADMISSION), result.holding["bob"]?.map { it.kind })

        // And this account's own statement under its current key is unaffected
        // by the restored key.
        val current = sign(Kind.INVITATION_TO_ACCOUNT, "me", subject = "carol")
        val mine = evaluate(listOf(member("me"), member("carol", current, keyVersion = null)), approvals = emptyMap(), statements = WithPreviousKey("me-old-s"))
        assertTrue(mine.previousKeyOnly.isEmpty())
        assertEquals(1, mine.holding["carol"]?.size)
    }

    @Test
    fun `a statement under the current key that holds up later in the pass lifts the mark`() {
        // bob: this account's invitation from before the reset, and an admission by carol,
        // whom this pass confirms only after bob.
        val old = sign(Kind.INVITATION_TO_ACCOUNT, "me", signerKey = "me-old-s", subject = "bob")
        val byCarol = sign(Kind.ADMISSION, "carol", subject = "bob")
        val carolsInvite = sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "carol")
        val result = evaluate(
            listOf(member("me"), member("owner"), member("bob", old, byCarol), member("carol", carolsInvite)),
            statements = WithPreviousKey("me-old-s"),
        )

        assertEquals(Origin.STATEMENT, result.confirmed["bob"])
        assertTrue(result.previousKeyOnly.isEmpty())
        assertEquals(listOf(Kind.ADMISSION), result.holding["bob"]?.map { it.kind })
    }

    @Test
    fun `an own admission from before a reset lifts no email invitee's receive-only`() {
        val email = sign(Kind.INVITATION_TO_EMAIL, "owner", email = "dan@x.test")
        val oldAdmission = sign(Kind.ADMISSION, "me", signerKey = "me-old-s", subject = "dan")
        val result = evaluate(listOf(member("me"), member("owner"), member("dan", email, oldAdmission)), statements = WithPreviousKey("me-old-s"))
        assertTrue(result.receiveOnly("dan"))
    }

    // ---- the newcomer's own invitation ----------------------------------------------

    @Test
    fun `an invitation names this account only with this device's keys, or its email`() {
        fun names(signed: MembershipStatements.Signed, circleId: String = circle) = MemberConfirmation.invitationNamesMe(
            FakeStatements.parse(signed.statementHex), circleId, "me", "me-x", "me-s", " Me@X.test ",
        )
        assertTrue(names(sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me")))
        assertFalse(names(sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me", sharing = "server-x")))
        assertFalse(names(sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "someone-else")))
        assertFalse(names(sign(Kind.INVITATION_TO_ACCOUNT, "owner", subject = "me"), circleId = "c-2"))
        assertTrue(names(sign(Kind.INVITATION_TO_EMAIL, "owner", email = "me@x.test")))
        assertFalse(names(sign(Kind.INVITATION_TO_EMAIL, "owner", email = "you@x.test")))
        assertFalse(names(sign(Kind.ADMISSION, "owner", subject = "me")))
        assertFalse(MemberConfirmation.invitationNamesMe(null, circle, "me", "me-x", "me-s", "me@x.test"))
    }
}
