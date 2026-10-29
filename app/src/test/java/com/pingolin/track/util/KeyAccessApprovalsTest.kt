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

package com.pingolin.track.util

import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import com.pingolin.track.testutil.UnopenablePrefs
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who a circle is trusted with on this device, and why: seeds an earlier build wrote
 * stay, and nothing new is seeded; every entry has an origin; departures leave
 * tombstones.
 */
class KeyAccessApprovalsTest {

    private val prefs = InMemoryPrefs()
    private var clock = 1_000_000L
    private var owner: String? = "me"

    private val approvals = SecurePrefsKeyAccessApprovals(mockk(), { owner }, { prefs }, { clock })

    /** Unix seconds a week after [clock]: when an invitation made now expires. */
    private fun weekOut() = clock / 1000 + 7 * 86_400

    /** What an earlier build's seed wrote for [circleId] from its first complete member
     * list.
     */
    private fun earlierSeed(circleId: String, userIds: Set<String>) {
        prefs.edit().putStringSet("$owner|$circleId", userIds).putBoolean("$owner|$circleId#seeded", true).apply()
    }

    @Test
    fun `a circle created here is this account's own record, and nothing else claims it`() {
        // What lets a phone serve any member it confirms is its own record of
        // creating the circle, never the role the server reports.
        assertFalse(approvals.createdHere("c"))
        approvals.markCreatedHere("c")

        assertTrue(approvals.createdHere("c"))
        assertFalse(approvals.createdHere("other"))
        owner = "someone-else"
        assertFalse(approvals.createdHere("c"))
        owner = null
        assertFalse(approvals.createdHere("c"))
    }

    @Test
    fun `an earlier build's seed is kept, as approvals from before signed invitations`() {
        earlierSeed("c", setOf("me", "alice"))

        assertTrue(approvals.hasPreRound2Seed("c"))
        assertEquals(mapOf("me" to Origin.PRE_ROUND2, "alice" to Origin.PRE_ROUND2), approvals.origins("c"))
    }

    @Test
    fun `a circle first seen now has no seed, and seeing its member list seeds nothing`() {
        approvals.retain("c", listOf("me", "alice", "ghost"), activeVersion = 1)

        assertFalse(approvals.hasPreRound2Seed("c"))
        assertEquals(emptySet<String>(), approvals.approved("c"))
    }

    @Test
    fun `an earlier build's seed that everyone left still counts as one`() {
        earlierSeed("c", setOf("alice"))
        approvals.retain("c", listOf("me"), activeVersion = 2)

        assertTrue(approvals.hasPreRound2Seed("c"))
    }

    @Test
    fun `each entry keeps its origin, and the strongest wins`() {
        approvals.approve("c", listOf("a"), Origin.KIND2)
        approvals.approve("c", listOf("b"), Origin.ANCHOR)
        approvals.approve("c", listOf("c1"), Origin.STATEMENT)
        approvals.approve("c", listOf("d"), Origin.OWN_KIND2)
        approvals.approve("c", listOf("a", "d"), Origin.OWN)

        assertEquals(
            mapOf("a" to Origin.OWN, "b" to Origin.ANCHOR, "c1" to Origin.STATEMENT, "d" to Origin.OWN),
            approvals.origins("c"),
        )
    }

    @Test
    fun `leaving the member list takes the approval with it and leaves a tombstone`() {
        earlierSeed("c", setOf("me", "alice", "bob"))
        approvals.approve("c", listOf("carol"), Origin.STATEMENT)
        approvals.recordStatementIds("c", mapOf("bob" to listOf("S-1"), "carol" to listOf("s-2", "s-3")))

        approvals.retain("c", listOf("me", "alice"), activeVersion = 7)

        assertEquals(setOf("me", "alice"), approvals.approved("c"))
        assertEquals(mapOf("bob" to 7, "carol" to 7), approvals.tombstones("c"))
        // Ids are kept lower-cased: the evaluator compares them that way.
        assertEquals(setOf("s-1", "s-2", "s-3"), approvals.tombstonedStatementIds("c"))
        // Back again: not approved until a statement made at v7 or later vouches for them.
        approvals.retain("c", listOf("me", "alice", "bob"), activeVersion = 7)
        assertEquals(setOf("me", "alice"), approvals.approved("c"))
    }

    @Test
    fun `a member this device never trusted, but saw statements for, is tombstoned when they leave`() {
        approvals.recordStatementIds("c", mapOf("ghost" to listOf("g-1")))

        approvals.retain("c", listOf("me"), activeVersion = 3)

        assertEquals(mapOf("ghost" to 3), approvals.tombstones("c"))
        assertEquals(setOf("g-1"), approvals.tombstonedStatementIds("c"))
    }

    @Test
    fun `a later departure records the later version, and a return on a newer statement clears it`() {
        approvals.approve("c", listOf("bob"), Origin.STATEMENT)
        approvals.retain("c", listOf("me"), activeVersion = 3)
        approvals.approve("c", listOf("bob"), Origin.STATEMENT)
        approvals.retain("c", listOf("me"), activeVersion = 5)
        assertEquals(mapOf("bob" to 5), approvals.tombstones("c"))

        approvals.clearTombstones("c", listOf("bob"))
        assertEquals(emptyMap<String, Int>(), approvals.tombstones("c"))
    }

    @Test
    fun `a member this user removed loses the approval at once`() {
        earlierSeed("c", setOf("me", "alice", "bob"))
        approvals.recordReceiveOnlyKeys("c", "bob", "bx", "bs")

        approvals.revoke("c", listOf("bob"))

        assertEquals(setOf("me", "alice"), approvals.approved("c"))
        assertEquals(emptyMap<String, Pair<String, String>>(), approvals.receiveOnlyKeys("c"))
    }

    @Test
    fun `an invitee who joins with the invitation signed here becomes own, and stays`() {
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())

        // Not a member yet: approved by nothing, recorded, and never counted as departed —
        // which would tombstone them and refuse their invitation's renewal.
        approvals.retain("c", listOf("me"))
        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
        assertEquals(mapOf("dan" to "st-dan"), approvals.pendingInvitees("c"))
        assertEquals(emptyMap<String, Int>(), approvals.tombstones("c"))

        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "ST-DAN"))
        clock += 30L * 86_400_000
        // Joined: an ordinary approval now, which does not lapse; the record is gone.
        assertEquals(mapOf("dan" to Origin.OWN), approvals.origins("c"))
        assertEquals(emptyMap<String, String>(), approvals.pendingInvitees("c"))
    }

    /** Only the invitation signed here, and only in time. */
    @Test
    fun `an invitee who appears without the invitation signed here gets nothing, and the record goes`() {
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())

        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "st-other"))

        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
        assertEquals("appearing uses it up", emptyMap<String, String>(), approvals.pendingInvitees("c"))
        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "st-dan"))
        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
    }

    @Test
    fun `an invitation lapses at its own expiry, plus five minutes`() {
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())

        clock += 7L * 86_400_000 + KeyAccessApprovals.INVITE_APPEARANCE_GRACE_MS - 1
        assertEquals(mapOf("dan" to "st-dan"), approvals.pendingInvitees("c"))
        clock += 2
        assertEquals(emptyMap<String, String>(), approvals.pendingInvitees("c"))
        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "st-dan"))
        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
    }

    @Test
    fun `a withdrawn invitation's record goes - the invitee is nobody's own if listed later`() {
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())
        approvals.approveInvitee("c", null, "erin@x.test", "st-erin", weekOut())

        approvals.withdrawInvitation("c", "ST-DAN")
        approvals.withdrawInvitation("c", "st-erin")

        assertEquals(emptyMap<String, String>(), approvals.pendingInvitees("c"))
        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "st-dan"))
        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
        assertFalse(approvals.hasInvitedEmails("c"))
    }

    @Test
    fun `a record from before the invitation binding approves nobody`() {
        // "<user>\t<millis>": the 14-day record older builds wrote, with no statement id.
        prefs.edit().putStringSet("me|c#invited", setOf("dan\t${clock + 86_400_000}")).apply()

        approvals.retain("c", listOf("me", "dan"), invitationIdByUser = mapOf("dan" to "st-dan"))

        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
    }

    @Test
    fun `an invitee who had no account is matched by the email this user invited, receive-only`() {
        approvals.approveInvitee("c", null, "  Erin@X.test ", "st-erin", weekOut())
        assertTrue(approvals.hasInvitedEmails("c"))

        // The server stores the email trimmed and lower-cased.
        val matched = approvals.approveInvitedEmails(
            "c", mapOf("erin" to "erin@x.test", "carol" to "carol@x.test"), mapOf("erin" to "st-erin"),
        )

        assertEquals(setOf("erin"), matched)
        assertEquals(mapOf("erin" to Origin.OWN_KIND2), approvals.origins("c"))
        // One invite, one account: the email is used up.
        assertFalse(approvals.hasInvitedEmails("c"))
        assertEquals(emptySet<String>(), approvals.approveInvitedEmails("c", mapOf("ghost" to "erin@x.test"), mapOf("ghost" to "st-erin")))
    }

    @Test
    fun `an invited email matches only the account carrying the invitation signed here`() {
        approvals.approveInvitee("c", null, "erin@x.test", "st-erin", weekOut())

        assertEquals(emptySet<String>(), approvals.approveInvitedEmails("c", mapOf("erin" to "erin@x.test"), mapOf("erin" to "st-other")))
        assertEquals(emptySet<String>(), approvals.approveInvitedEmails("c", mapOf("erin" to "erin@x.test"), emptyMap()))
        assertEquals(setOf("erin"), approvals.approveInvitedEmails("c", mapOf("erin" to "erin@x.test"), mapOf("erin" to "st-erin")))
    }

    @Test
    fun `an invited email the server gives to two accounts approves neither`() {
        approvals.approveInvitee("c", null, "erin@x.test", "st-erin", weekOut())

        val matched = approvals.approveInvitedEmails(
            "c", mapOf("erin" to "erin@x.test", "ghost" to "Erin@x.test"), mapOf("erin" to "st-erin", "ghost" to "st-erin"),
        )

        assertEquals(emptySet<String>(), matched)
        assertEquals(emptySet<String>(), approvals.approved("c"))
    }

    @Test
    fun `an invite by account never approves by email`() {
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())

        assertFalse(approvals.hasInvitedEmails("c"))
        assertEquals(emptySet<String>(), approvals.approveInvitedEmails("c", mapOf("ghost" to "dan@x.test"), mapOf("ghost" to "st-dan")))
    }

    @Test
    fun `an invited email lapses too`() {
        approvals.approveInvitee("c", null, "erin@x.test", "st-erin", weekOut())
        clock += 7L * 86_400_000 + KeyAccessApprovals.INVITE_APPEARANCE_GRACE_MS + 1

        assertFalse(approvals.hasInvitedEmails("c"))
        assertEquals(emptySet<String>(), approvals.approveInvitedEmails("c", mapOf("erin" to "erin@x.test"), mapOf("erin" to "st-erin")))
    }

    @Test
    fun `an email invitee's keys are this device's record, replaced only on purpose, gone when they leave`() {
        approvals.approve("c", listOf("erin"), Origin.KIND2)
        approvals.recordReceiveOnlyKeys("c", "erin", "EX", "ES")
        approvals.recordReceiveOnlyKeys("c", "erin", "ex2", "es2")
        assertEquals(mapOf("erin" to ("ex2" to "es2")), approvals.receiveOnlyKeys("c"))

        approvals.retain("c", listOf("me"), activeVersion = 2)
        assertEquals(emptyMap<String, Pair<String, String>>(), approvals.receiveOnlyKeys("c"))
    }

    @Test
    fun `a backfill is recorded once, and a question's time is remembered`() {
        approvals.approve("c", listOf("old"), Origin.STATEMENT)
        approvals.markBackfilled("c", listOf("old"))
        assertEquals(setOf("old"), approvals.backfilled("c"))

        assertNull(approvals.lastAsked("c", "old"))
        approvals.markAsked("c", "old")
        clock += 5
        approvals.markAsked("c", "old")
        assertEquals(clock, approvals.lastAsked("c", "old"))

        // Both go with the member.
        approvals.retain("c", listOf("me"), activeVersion = 1)
        assertEquals(emptySet<String>(), approvals.backfilled("c"))
        assertNull(approvals.lastAsked("c", "old"))
    }

    @Test
    fun `whom this account trusts in its other circles, receive-only and other accounts left out`() {
        earlierSeed("a", setOf("me", "alice"))
        approvals.approve("b", listOf("bob"), Origin.STATEMENT)
        approvals.approve("b", listOf("erin"), Origin.KIND2)
        approvals.approve("c", listOf("carl"), Origin.ANCHOR)
        // A pending invitation trusts nobody yet.
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())
        approvals.approve("here", listOf("zed"), Origin.OWN)
        owner = "other"
        approvals.approve("b", listOf("mallory"), Origin.OWN)
        owner = "me"

        assertEquals(setOf("alice", "bob", "carl"), approvals.trustedInOtherCircles("here"))
    }

    @Test
    fun `the pins held here seed circles with no records, once per account`() {
        earlierSeed("a", setOf("me", "alice"))
        approvals.approve("b", listOf("bob"), Origin.OWN)

        val seeded = approvals.seedFromHeldPins(mapOf(
            "a" to setOf("ghost"),   // seeded already
            "b" to setOf("ghost"),   // it has a record already
            "c" to setOf("carol", "dan"),
        ))

        assertEquals(1, seeded)
        assertTrue(approvals.hasPreRound2Seed("c"))
        // Held until the circle's first complete member list: dan isn't on it — an
        // invitee still pending, or someone long gone — and gets nothing: no entry, no
        // tombstone.
        assertEquals(emptyMap<String, Origin>(), approvals.origins("c"))
        approvals.retain("c", listOf("me", "carol"), activeVersion = 4)
        assertEquals(mapOf("carol" to Origin.PRE_ROUND2), approvals.origins("c"))
        assertEquals(emptyMap<String, Int>(), approvals.tombstones("c"))
        assertEquals(emptyMap<String, String>(), approvals.pendingInvitees("c"))
        // Listed later, they are nobody's approval: the current
        // rules decide.
        approvals.retain("c", listOf("me", "carol", "dan"), activeVersion = 4)
        assertEquals(mapOf("carol" to Origin.PRE_ROUND2), approvals.origins("c"))
        assertEquals(setOf("me", "alice"), approvals.approved("a"))
        assertEquals(setOf("bob"), approvals.approved("b"))
        assertTrue(approvals.heldPinsSeeded())
        // Once: a later call seeds nothing, whatever it is given.
        assertEquals(0, approvals.seedFromHeldPins(mapOf("d" to setOf("erin"))))
        assertEquals(emptySet<String>(), approvals.approved("d"))
        // Per account: another account on this device takes its own.
        owner = "other"
        assertFalse(approvals.heldPinsSeeded())
    }

    @Test
    fun `approvals are per circle`() {
        earlierSeed("c", setOf("me", "alice"))
        approvals.approve("d", listOf("bob"), Origin.OWN)

        assertEquals(setOf("bob"), approvals.approved("d"))
        assertFalse(approvals.hasPreRound2Seed("d"))
    }

    @Test
    fun `another account signing in drops them, and an erasure drops every account's`() {
        earlierSeed("c", setOf("me", "alice"))
        approvals.approveInvitee("c", "dan", "dan@x.test", "st-dan", weekOut())
        owner = "other"
        earlierSeed("c", setOf("other", "alice"))

        approvals.keepOnly("other")
        owner = "me"
        assertEquals(emptySet<String>(), approvals.approved("c"))
        assertFalse(approvals.hasPreRound2Seed("c"))

        owner = "other"
        assertEquals(setOf("other", "alice"), approvals.approved("c"))
        approvals.wipeAll()
        assertEquals(emptySet<String>(), approvals.approved("c"))
        assertFalse(approvals.hasPreRound2Seed("c"))
    }

    @Test
    fun `nothing is recorded or read without a signed-in account`() {
        owner = null
        approvals.approve("c", listOf("ghost"))
        approvals.approveInvitee("c", "ghost", "g@x.test", "st-ghost", weekOut())
        approvals.retain("c", listOf("ghost"), activeVersion = 1)
        approvals.recordStatementIds("c", mapOf("ghost" to listOf("x")))

        assertTrue(prefs.data.isEmpty())
        assertEquals(emptySet<String>(), approvals.approved("c"))
    }

    /** Every kind of record this store keeps, for [c], written while it reads. */
    private fun everyRecord(approvals: KeyAccessApprovals, c: String = "c") {
        approvals.approve(c, listOf("alice"), Origin.OWN)
        approvals.approve(c, listOf("bob"), Origin.STATEMENT)
        approvals.approveInvitee(c, "cy", "cy@x.test", "st-cy", weekOut())
        approvals.recordStatementIds(c, mapOf("bob" to listOf("st-bob")))
        approvals.retain(c, listOf("me", "alice", "cy"), 3, mapOf("cy" to "st-cy"))
        approvals.recordReceiveOnlyKeys(c, "dee", "dx", "ds")
        approvals.markBackfilled(c, listOf("alice"))
        assertTrue(approvals.addBackfillId(c, "alice", "st-bf"))
        approvals.markInvitationDead(c, "st-dead")
        approvals.markRenewalAsked(c, "st-asked")
        approvals.markAsked(c, "alice")
        approvals.markCreatedHere(c)
        assertTrue(approvals.markStrayChoicesChecked(c))
    }

    /**
     * What records that can't be read must do: no read is "nobody" or "none" (a tombstone or a
     * dead invitation read as none would let a statement vouch again), and no write writes
     * over them (a durable one says false).
     */
    private fun unreadable(approvals: KeyAccessApprovals) {
        val reads = listOf<() -> Any?>(
            { approvals.origins("c") }, { approvals.tombstones("c") }, { approvals.tombstonedStatementIds("c") },
            { approvals.deadInvitations("c") }, { approvals.invitationDead("c", "st-dead") }, { approvals.pendingInvitees("c") },
            { approvals.receiveOnlyKeys("c") }, { approvals.backfilled("c") }, { approvals.backfillIds("c", "alice") },
            { approvals.hasPreRound2Seed("c") }, { approvals.hasInvitedEmails("c") }, { approvals.trustedInOtherCircles("other") },
            { approvals.lastAsked("c", "alice") }, { approvals.createdHere("c") }, { approvals.strayChoicesChecked("c") },
            { approvals.renewalAsked("c", "st-asked") }, { approvals.heldPinsSeeded() },
            { approvals.seedFromHeldPins(mapOf("c2" to setOf("zed"))) },
        )
        for ((i, read) in reads.withIndex()) assertThrows("read $i", StoreUnreadable::class.java) { read() }

        approvals.approve("c", listOf("eve"), Origin.OWN)
        assertFalse(approvals.approveDurably("c", listOf("eve"), Origin.OWN))
        approvals.revoke("c", listOf("alice"))
        approvals.approveInvitee("c", "fay", "fay@x.test", "st-fay", weekOut())
        approvals.withdrawInvitation("c", "st-cy")
        approvals.approveInvitedEmails("c", mapOf("gus" to "gus@x.test"), emptyMap())
        approvals.retain("c", listOf("me"), 4, emptyMap())
        approvals.recordStatementIds("c", mapOf("alice" to listOf("st-new")))
        approvals.clearTombstones("c", listOf("bob"))
        approvals.recordReceiveOnlyKeys("c", "dee", "dx2", "ds2")
        approvals.markBackfilled("c", listOf("bob"))
        assertFalse(approvals.addBackfillId("c", "alice", "st-bf2"))
        assertFalse(approvals.dropChoicesDurably("c", listOf("alice")))
        approvals.markInvitationDead("c", "st-dead2")
        assertFalse(approvals.markDeadDurably("c", listOf("st-dead3")))
        approvals.markRenewalAsked("c", "st-asked2")
        approvals.markAsked("c", "bob")
        assertFalse(approvals.markStrayChoicesChecked("c"))
        // A circle whose pass never ran: its mark would be a write, over records that can't be read.
        assertFalse(approvals.markStrayChoicesChecked("c2"))
    }

    /** Everything [everyRecord] kept, read back. */
    private fun allThere(approvals: KeyAccessApprovals) {
        assertEquals("cy appeared with the invitation signed here", mapOf("alice" to Origin.OWN, "cy" to Origin.OWN), approvals.origins("c"))
        assertEquals(emptyMap<String, String>(), approvals.pendingInvitees("c"))
        assertEquals(mapOf("bob" to 3), approvals.tombstones("c"))
        assertEquals(setOf("st-bob"), approvals.tombstonedStatementIds("c"))
        assertEquals(setOf("st-dead"), approvals.deadInvitations("c"))
        assertEquals(mapOf("dee" to ("dx" to "ds")), approvals.receiveOnlyKeys("c"))
        assertEquals(setOf("alice"), approvals.backfilled("c"))
        assertEquals(setOf("st-bf"), approvals.backfillIds("c", "alice"))
        assertTrue(approvals.renewalAsked("c", "st-asked"))
        assertEquals(clock, approvals.lastAsked("c", "alice"))
        assertTrue(approvals.createdHere("c"))
        assertTrue(approvals.strayChoicesChecked("c"))
    }

    @Test
    fun `a damaged read is never nobody or none - nothing is written over it, and it's all there once it reads`() {
        val damaged = DamagedPrefs()
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { damaged }, { clock })
        everyRecord(approvals)
        allThere(approvals)
        val stored = damaged.data.toMap()

        damaged.damaged = true
        unreadable(approvals)
        assertEquals("nothing written over it", stored, damaged.data)

        damaged.damaged = false
        allThere(approvals)
    }

    @Test
    fun `a file that can't be opened - the same, and it's opened again at the next use`() {
        val inner = InMemoryPrefs()
        everyRecord(SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { inner }, { clock }))
        val stored = inner.data.toMap()
        val file = UnopenablePrefs(inner)
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, file.provider, { clock })

        file.unopenable = true
        unreadable(approvals)
        assertEquals("nothing written over it", stored, inner.data)

        file.unopenable = false
        allThere(approvals)
    }

    // ---- which own statements each approval rests on (a late joiner after a reset) ----------------

    private val maya = "cccccccc-0000-4000-8000-00000000000c"
    private val ben = "dddddddd-0000-4000-8000-00000000000d"
    private val st1 = "11111111-0000-4000-8000-000000000001"
    private val st2 = "22222222-0000-4000-8000-000000000002"

    @Test
    fun `an approval's basis - recorded by id, added to, canonical, per account`() {
        approvals.recordApprovalBasis("c", mapOf(maya.uppercase() to listOf(st1.uppercase())))
        approvals.recordApprovalBasis("c", mapOf(maya to listOf(st2), ben to emptyList()))

        assertEquals("added to, never replaced; a member with none records nothing", mapOf(maya to setOf(st1, st2)), approvals.approvalBasis("c"))
        assertEquals(emptyMap<String, Set<String>>(), approvals.approvalBasis("other"))
        owner = "someone-else"
        assertEquals("another account's", emptyMap<String, Set<String>>(), approvals.approvalBasis("c"))
    }

    @Test
    fun `an approval's basis goes with the approval and with its member's departure, and stays across a pass that keeps them`() {
        approvals.approve("c", listOf(maya, ben), Origin.STATEMENT)
        approvals.recordApprovalBasis("c", mapOf(maya to listOf(st1), ben to listOf(st2)))

        approvals.retain("c", listOf("me", maya, ben), activeVersion = 3)
        assertEquals(mapOf(maya to setOf(st1), ben to setOf(st2)), approvals.approvalBasis("c"))

        approvals.revoke("c", listOf(maya))
        assertEquals("revoked", mapOf(ben to setOf(st2)), approvals.approvalBasis("c"))

        approvals.retain("c", listOf("me"), activeVersion = 4)
        assertEquals("departed", emptyMap<String, Set<String>>(), approvals.approvalBasis("c"))
    }

    @Test
    fun `an approval's basis goes with everything else`() {
        approvals.approve("c", listOf(maya), Origin.STATEMENT)
        approvals.recordApprovalBasis("c", mapOf(maya to listOf(st1)))

        approvals.wipeAll()

        assertEquals(emptyMap<String, Set<String>>(), approvals.approvalBasis("c"))
    }
}
