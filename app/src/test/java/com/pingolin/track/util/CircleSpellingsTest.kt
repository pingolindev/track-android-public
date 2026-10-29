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

import android.content.Context
import android.content.SharedPreferences
import com.pingolin.track.domain.repository.InviteRenewalQuestion
import com.pingolin.track.domain.repository.LateJoinerAnswers
import com.pingolin.track.domain.repository.LateJoiners
import com.pingolin.track.domain.repository.MembershipStatements
import com.pingolin.track.domain.repository.SecurePrefsLateJoinerAnswers
import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stores keep a circle under its ONE spelling: whatever spelling a caller passes, and
 * whatever an earlier build kept, folded at load by each store's rules. Ids with hex letters,
 * so upper case is another spelling.
 */
class CircleSpellingsTest {

    private val circle = "c1a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val upper = circle.uppercase()
    private val bare = circle.replace("-", "")
    private val spellings = listOf(upper, bare, "{$circle}", "urn:uuid:$circle")

    private val sam = "5a3e0b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"
    private val dan = "dade0b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"
    private val eve = "e7e00b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"
    private val st1 = "5d1c0e2f-a3b4-4c5d-8e6f-7a8b9c0d1e2f"
    private val st2 = "5d2c0e2f-a3b4-4c5d-8e6f-7a8b9c0d1e2f"

    /**
     * A store over [prefs] for the account "me": [write] puts an entry in a circle, [holds] says
     * whether it's there (an unreadable circle holds nothing), [kept] is an entry as an earlier
     * build kept it, under a circle's [spelling].
     */
    private class Store(
        val name: String,
        val make: (prefs: SharedPreferences) -> Any,
        val write: Any.(circleId: String) -> Unit,
        val holds: Any.(circleId: String) -> Boolean,
        val kept: (spelling: String) -> Pair<String, Any>,
    )

    private fun <T> readOrNone(read: () -> T): T? = try { read() } catch (e: StoreUnreadable) { null }

    private val stores = listOf(
        Store("own removals", { p -> SecurePrefsOwnRemovals(mockk(), { "me" }, { p }) },
            { (this as OwnRemovals).add(it, sam, 100) }, { readOrNone { (this as OwnRemovals).of(it) }.orEmpty() == setOf(sam) },
            { "me|$it" to setOf("$sam|100") }),
        Store("owner role changes", { p -> SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { p }) },
            { (this as OwnerRoleChanges).put(it, sam, "admin") }, { readOrNone { (this as OwnerRoleChanges).of(it) } == mapOf(sam to "admin") },
            { "me|$it" to setOf("$sam=admin") }),
        Store("repair budget", { p -> SecurePrefsRepairBudget(mockk(), { "me" }, { p }) },
            { (this as RepairBudget).spend(it, 3, listOf(sam), 1_000) }, { readOrNone { (this as RepairBudget).repaired(it) } == setOf(3) },
            { "me|$it#repaired" to setOf("3") }),
        Store("late-joiner answers", { p -> SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { p }) },
            { (this as LateJoinerAnswers).answer(it, LateJoiners.Invitation(st1, dan, sam, 100)) },
            { readOrNone { (this as LateJoinerAnswers).answered(it) } == listOf(LateJoiners.Invitation(st1, dan, sam, 100)) },
            { "me|$it" to setOf("$st1\t$dan\t$sam\t100") }),
        Store("hand-overs", { p -> SecurePrefsHandOvers(mockk(), { "me" }, { p }) },
            { (this as HandOvers).remember(it, HandOvers.HandOver(4, sam)) }, { readOrNone { (this as HandOvers).of(it) } == HandOvers.HandOver(4, sam) },
            { "me|$it" to "4|$sam" }),
        Store("person-confirmed keys", { p -> SecurePrefsPersonConfirmedKeys(mockk(), { "me" }, { p }) },
            { (this as PersonConfirmedKeys).confirm(it, sam, "aa", "bb") }, { (this as PersonConfirmedKeys).confirmed(it, sam, "aa", "bb") },
            { "me|$it|$sam" to "aa:bb" }),
        Store("key access approvals", { p -> SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { p }) },
            { (this as KeyAccessApprovals).approve(it, listOf(sam)) }, { readOrNone { (this as KeyAccessApprovals).approved(it) } == setOf(sam) },
            { "me|$it#own" to setOf(sam) }),
    )

    /** Every key's circle part in its one spelling. */
    private fun oneSpelling(prefs: InMemoryPrefs) = prefs.data.keys.none { key -> spellings.any { key.startsWith("me|$it") } }

    @Test
    fun `an entry written under any spelling of a circle is kept under its one spelling and read under every one`() {
        for (s in stores) for (spelling in spellings) {
            val prefs = InMemoryPrefs()
            val store = s.make(prefs)
            s.write(store, spelling)

            assertTrue("${s.name} / $spelling", s.holds(store, circle))
            for (other in spellings) assertTrue("${s.name} / $spelling read as $other", s.holds(store, other))
            assertTrue("${s.name} / $spelling: ${prefs.data.keys}", oneSpelling(prefs))
        }
    }

    @Test
    fun `an entry an earlier build kept under another spelling is folded under the one spelling`() {
        for (s in stores) for (spelling in spellings) {
            val prefs = InMemoryPrefs()
            s.kept(spelling).let { (key, value) -> prefs.data[key] = value }

            val store = s.make(prefs)

            assertTrue("${s.name} / $spelling", s.holds(store, circle))
            assertTrue("${s.name} / $spelling: ${prefs.data.keys}", oneSpelling(prefs))
        }
    }

    @Test
    fun `a file that doesn't read folds nothing and has nothing written over it, and folds once it reads`() {
        for (s in stores) {
            val prefs = DamagedPrefs()
            s.kept(upper).let { (key, value) -> prefs.data[key] = value }
            val stored = prefs.data.toMap()
            prefs.damaged = true
            val store = s.make(prefs)

            runCatching { s.holds(store, circle) }
            runCatching { s.write(store, circle) }

            assertEquals(s.name, stored, prefs.data)
            prefs.damaged = false
            assertTrue(s.name, s.holds(store, circle))
            assertTrue(s.name, prefs.data.keys.none { it.startsWith("me|$upper") })
        }
    }

    @Test
    fun `a second fold finds nothing to do`() {
        for (s in stores) {
            val prefs = InMemoryPrefs()
            s.kept(bare).let { (key, value) -> prefs.data[key] = value }
            s.holds(s.make(prefs), circle)
            val once = prefs.data.toMap()

            // Nothing to write: with commits failing, the circle still reads, and nothing changes.
            prefs.failCommits = true
            assertTrue(s.name, s.holds(s.make(prefs), circle))
            assertEquals(s.name, once, prefs.data)
        }
    }

    @Test
    fun `a fold that doesn't land leaves both spellings and the circle holds nothing until it lands`() {
        for (s in stores) {
            val prefs = InMemoryPrefs()
            s.kept(upper).let { (key, value) -> prefs.data[key] = value }
            val stored = prefs.data.toMap()
            prefs.failCommits = true
            val store = s.make(prefs)

            assertFalse(s.name, s.holds(store, circle))
            assertEquals(s.name, stored, prefs.data)

            prefs.failCommits = false
            assertTrue(s.name, s.holds(store, circle))
        }
    }

    @Test
    fun `a circle id that is no UUID keys as itself, trimmed and lower-cased`() {
        for (s in stores) {
            val prefs = InMemoryPrefs()
            val store = s.make(prefs)
            s.write(store, " C-1 ")

            assertTrue(s.name, s.holds(store, "c-1"))
            assertFalse(s.name, s.holds(store, "c-2"))
            assertTrue("${s.name}: ${prefs.data.keys}", prefs.data.keys.all { it.startsWith("me|c-1") })
        }
    }

    @Test
    fun `while a fold hasn't landed, a circle is never read from its one spelling alone`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$circle"] = setOf("$sam|100")
        prefs.data["me|$bare"] = setOf("$dan|200")
        prefs.data["me|$circle#tomb"] = setOf("$sam\t1")
        prefs.data["me|$upper#tomb"] = setOf("$dan\t2")
        prefs.failCommits = true

        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        assertThrows(StoreUnreadable::class.java) { removals.of(circle) }
        assertFalse(removals.readable(circle))
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })
        assertThrows(StoreUnreadable::class.java) { approvals.tombstones(circle) }

        prefs.failCommits = false
        assertEquals(setOf(sam, dan), removals.of(circle))
        assertEquals(mapOf(sam to 1, dan to 2), approvals.tombstones(circle))
    }

    /** [inner], whose next [failing] commits write nothing and say so, as a full disk does. */
    private class FailingCommits(val inner: InMemoryPrefs = InMemoryPrefs()) : SharedPreferences by inner {
        var failing = 0
        val data get() = inner.data

        override fun edit(): SharedPreferences.Editor {
            val editor = inner.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean = if (failing > 0) { failing--; false } else editor.commit()
            }
        }
    }

    @Test
    fun `a circle forgotten while its fold hasn't landed is gone under every spelling, and no fold brings it back`() {
        val prefs = FailingCommits()
        prefs.data["me|$bare"] = setOf("$sam|100")
        prefs.data["me|$bare#cleared"] = setOf("$dan|20|$st1|999")
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        // The fold doesn't land, twice; the forgetting does.
        prefs.failing = 2
        assertFalse(removals.readable(circle))

        removals.forget(upper)

        assertEquals(emptyMap<String, Any?>(), prefs.data)
        assertEquals(emptySet<String>(), removals.of(circle))
    }

    @Test
    fun `a circle forgotten while its file doesn't read still goes under its one spelling`() {
        val prefs = DamagedPrefs()
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        assertTrue(removals.add(circle, sam, 100))
        prefs.damaged = true

        removals.forget(circle)

        assertEquals(emptyMap<String, Any?>(), prefs.data)
    }

    // ---- each store's rules --------------------------------------------------------------------

    @Test
    fun `own removals - every member removed under any spelling stays removed, with the later time`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare"] = setOf("$sam|100", dan)
        prefs.data["me|$circle"] = setOf("${sam.uppercase()}|300", "$eve|50")
        prefs.data["me|$upper#cleared"] = setOf("$dan|20|${st1.uppercase()}|999")
        prefs.data["me|$circle#cleared"] = setOf("$eve|10|$st2|888")
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })

        assertEquals("a removal settled under one spelling stays pending", setOf(sam, dan, eve), removals.of(circle))
        assertEquals(300L, removals.removedAt(circle, sam))
        assertNull(removals.removedAt(circle, dan))
        assertEquals(setOf("$dan|20|$st1|999", "$eve|10|$st2|888"), prefs.data["me|$circle#cleared"])

        removals.forget(upper)
        assertEquals(emptyMap<String, Any?>(), prefs.data)
    }

    @Test
    fun `owner role changes - agreeing roles are kept, and two roles for one member refuse the circle`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare"] = setOf("$sam=admin", "$eve=member")
        prefs.data["me|$circle"] = setOf("${sam.uppercase()}=admin", "$dan=owner")
        assertEquals(
            mapOf(sam to "admin", eve to "member", dan to "owner"),
            SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { prefs }).of(circle),
        )

        val clash = InMemoryPrefs()
        clash.data["me|$bare"] = setOf("$sam=admin")
        clash.data["me|$circle"] = setOf("$sam=member")
        val stored = clash.data.toMap()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { clash })

        assertThrows(StoreUnreadable::class.java) { changes.of(circle) }
        assertFalse(changes.readable(upper))
        assertFalse("held, never written over them", changes.put(circle, dan, "admin"))
        assertEquals(stored, clash.data)

        changes.forget(circle)
        assertEquals("gone under every spelling", emptyMap<String, Any?>(), clash.data)
        assertEquals(emptyMap<String, String>(), changes.of(circle))
    }

    @Test
    fun `repair budget - every version repaired, the later repair, and everyone either named`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare#repaired"] = setOf("3")
        prefs.data["me|$circle#repaired"] = setOf("5")
        prefs.data["me|$upper#at"] = 9_000L
        prefs.data["me|$circle#at"] = 1_000L
        prefs.data["me|{$circle}#for"] = setOf(sam.uppercase())
        prefs.data["me|$circle#for"] = setOf(dan)
        val budget = SecurePrefsRepairBudget(mockk(), { "me" }, { prefs })

        assertEquals(setOf(3, 5), budget.repaired(circle))
        assertEquals(9_000L, budget.lastRepairAt(circle))
        assertEquals(setOf(sam, dan), budget.lastRepairedFor(circle))
    }

    @Test
    fun `late-joiner answers - an answer is kept only where every spelling holds it`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare"] = setOf("${st1.uppercase()}\t$dan\t$sam\t100", "$st2\t$dan\t$eve\t200")
        prefs.data["me|$circle"] = setOf("$st1\t$dan\t$sam\t100")

        assertEquals(
            listOf(LateJoiners.Invitation(st1, dan, sam, 100)),
            SecurePrefsLateJoinerAnswers(mockk(), { "me" }, { prefs }).answered(circle),
        )
    }

    @Test
    fun `person-confirmed keys - confirmed only where every spelling holds the same keys`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare|${sam.uppercase()}"] = "aa:bb"
        prefs.data["me|$circle|$sam"] = "aa:bb"
        prefs.data["me|$bare|$dan"] = "cc:dd"
        prefs.data["me|$circle|$dan"] = "cc:ee"
        prefs.data["me|$circle|$eve"] = "ff:00"
        val keys = SecurePrefsPersonConfirmedKeys(mockk(), { "me" }, { prefs })

        assertTrue(keys.confirmed(upper, sam, "AA", "BB"))
        assertFalse(keys.confirmed(circle, dan, "cc", "dd"))
        assertFalse(keys.confirmed(circle, dan, "cc", "ee"))
        assertFalse("under one spelling only", keys.confirmed(circle, eve, "ff", "00"))
    }

    @Test
    fun `hand-overs - kept where they agree, and two that differ refuse the circle`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare"] = "4|${sam.uppercase()}"
        prefs.data["me|$circle"] = "4|$sam"
        assertEquals(HandOvers.HandOver(4, sam), SecurePrefsHandOvers(mockk(), { "me" }, { prefs }).of(circle))

        val clash = InMemoryPrefs()
        clash.data["me|$bare"] = "4|$sam"
        clash.data["me|$circle"] = "5|$dan"
        clash.data["me|c-2"] = "2|$eve"
        val stored = clash.data.toMap()
        val handOvers = SecurePrefsHandOvers(mockk(), { "me" }, { clash })

        assertThrows(StoreUnreadable::class.java) { handOvers.of(circle) }
        assertEquals("the one that can't be told is left out", mapOf("c-2" to HandOvers.HandOver(2, eve)), handOvers.all())
        handOvers.remember(circle, HandOvers.HandOver(6, eve))
        assertEquals("nothing written over them", stored, clash.data)

        handOvers.forget(upper)
        assertEquals(mapOf("me|c-2" to "2|$eve"), clash.data)
        assertNull(handOvers.of(circle))
    }

    @Test
    fun `a hand-over that can't be read is never taken as none`() {
        val prefs = DamagedPrefs()
        prefs.data["me|$circle"] = "4|$sam"
        val handOvers = SecurePrefsHandOvers(mockk(), { "me" }, { prefs })
        handOvers.of(circle)
        prefs.damaged = true

        assertThrows(StoreUnreadable::class.java) { handOvers.of(circle) }
    }

    @Test
    fun `key access approvals - tombstones and every list merge, and a tombstoned member keeps no approval`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare#tomb"] = setOf("${sam.uppercase()}\t3", "$eve\t1")
        prefs.data["me|$circle#tomb"] = setOf("$sam\t5")
        prefs.data["me|$bare#tombIds"] = setOf(st1.uppercase())
        prefs.data["me|$circle#tombIds"] = setOf(st2)
        prefs.data["me|$bare#deadInvites"] = setOf(st1)
        prefs.data["me|$circle#own"] = setOf(sam, dan)
        prefs.data["me|$bare#stmt"] = setOf(eve)
        prefs.data["me|$bare#ids"] = setOf("$dan\t${st2.uppercase()}")
        prefs.data["me|$upper#asked"] = setOf("$dan\t500")
        prefs.data["me|$circle#asked"] = setOf("$dan\t900")
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })

        assertEquals(mapOf(sam to 5, eve to 1), approvals.tombstones(circle))
        assertEquals(setOf(st1, st2), approvals.tombstonedStatementIds(circle))
        assertTrue(approvals.invitationDead(circle, st1.uppercase()))
        assertEquals("the removals win", mapOf(dan to KeyAccessApprovals.Origin.OWN), approvals.origins(circle))
        assertEquals(setOf("$dan\t$st2"), prefs.data["me|$circle#ids"])
        assertEquals(900L, approvals.lastAsked(circle, dan))
    }

    @Test
    fun `key access approvals - where spellings disagree the one spelling's record stands, and with none of it, no approval`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$circle#own"] = setOf(sam)
        prefs.data["me|$bare#anchor"] = setOf(sam, dan)
        prefs.data["me|$bare#kind2"] = setOf(eve)
        prefs.data["me|$upper#stmt"] = setOf(eve)
        prefs.data["me|$bare#rokeys"] = setOf("$dan\taa\tbb")
        assertEquals(
            // sam: the one spelling's OWN; dan: under one spelling only, as it holds him; eve: two others disagree, none.
            mapOf(sam to KeyAccessApprovals.Origin.OWN, dan to KeyAccessApprovals.Origin.ANCHOR),
            SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs }).origins(circle),
        )
        assertEquals(setOf("$dan\taa\tbb"), prefs.data["me|$circle#rokeys"])
    }

    @Test
    fun `key access approvals - invitations by statement, the earlier lapse, none where two name different people`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare#invited"] = setOf("${sam.uppercase()}\t${st1.uppercase()}\t9000000000000", "$dan\t$st2\t9000000000000")
        prefs.data["me|$circle#invited"] = setOf("$sam\t$st1\t8000000000000", "$eve\t$st2\t9000000000000")
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs }, now = { 1_000L })

        assertEquals(mapOf(sam to st1), approvals.pendingInvitees(circle))
        assertEquals(setOf("$sam\t$st1\t8000000000000"), prefs.data["me|$circle#invited"])
    }

    @Test
    fun `key access approvals - the seed and the flags only where every spelling holds them, backfills merged and capped`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare#heldSeed"] = setOf(sam.uppercase(), dan)
        prefs.data["me|$circle#heldSeed"] = setOf(sam, eve)
        prefs.data["me|$bare#seeded"] = true
        prefs.data["me|$circle#seeded"] = true
        prefs.data["me|$circle#created"] = true
        prefs.data["me|$bare#strayChecked"] = true
        prefs.data["me|$bare#backfilled"] = setOf(dan)
        prefs.data["me|$bare#bfids|${sam.uppercase()}"] = (0 until 15).map { "b%07x-0000-4000-8000-000000000000".format(it) }.toSet()
        val mine = (0 until 15).map { "a%07x-0000-4000-8000-000000000000".format(it) }.toSet()
        prefs.data["me|$circle#bfids|$sam"] = mine
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })

        assertTrue(approvals.hasPreRound2Seed(circle))
        assertEquals("the members every spelling holds", setOf(sam), prefs.data["me|$circle#heldSeed"])
        assertFalse("created here under one spelling only", approvals.createdHere(circle))
        assertFalse(approvals.strayChoicesChecked(circle))
        assertEquals(setOf(dan), approvals.backfilled(circle))
        val ids = approvals.backfillIds(circle, sam)
        assertEquals("at most the look's", com.pingolin.track.domain.repository.CircleRepository.MAX_BACKFILL_IDS, ids.size)
        assertTrue("the one spelling's first", ids.containsAll(mine))
    }

    @Test
    fun `key access approvals - one spelling keeps what it holds, a tombstoned member's approval too`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$circle#tomb"] = setOf("$sam\t3")
        prefs.data["me|$circle#own"] = setOf(sam.uppercase(), dan)
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })

        assertEquals(mapOf(sam to KeyAccessApprovals.Origin.OWN, dan to KeyAccessApprovals.Origin.OWN), approvals.origins(circle))
        assertEquals(mapOf(sam to 3), approvals.tombstones(circle))
    }

    @Test
    fun `key access approvals - member and statement ids are kept in their one spelling`() {
        val prefs = InMemoryPrefs()
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })
        approvals.approve(upper, listOf(sam.uppercase()))
        approvals.markInvitationDead(bare, st1.uppercase())
        approvals.approveInvitee(circle, dan.uppercase(), "d@x.test", st2.uppercase(), 9_000_000_000)

        assertEquals(setOf(sam), prefs.data["me|$circle#own"])
        assertTrue(approvals.invitationDead(circle, st1))
        assertEquals(mapOf(dan to st2), approvals.pendingInvitees(circle))
        approvals.revoke(circle, listOf(sam.replace("-", "")))
        assertEquals(emptySet<String>(), approvals.approved(circle))
        approvals.approve(circle, listOf(dan))
        approvals.approve("c-9", listOf(eve))
        assertEquals("this circle, however spelled, is not another", setOf(eve), approvals.trustedInOtherCircles(upper))
    }

    /** [test] with MemberVerificationStore over [prefs]; the store's own afterwards. */
    private fun withMarks(prefs: InMemoryPrefs, test: (Context) -> Unit) {
        val original = MemberVerificationStore.prefsFor
        MemberVerificationStore.prefsFor = { prefs }
        try {
            test(mockk())
        } finally {
            MemberVerificationStore.prefsFor = original
        }
    }

    @Test
    fun `verified marks - kept in their one spelling, and only where every spelling holds the same mark`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$bare:${sam.uppercase()}"] = "100|aa|bb"
        prefs.data["me|$circle:$sam"] = "100|aa|bb"
        prefs.data["me|$bare:$dan"] = "100|aa|bb"
        prefs.data["me|$circle:$dan"] = "200|aa|bb"
        withMarks(prefs) { context ->
            assertEquals(100L, MemberVerificationStore.getVerifiedAt(context, "me", upper, sam.uppercase(), "aa", "bb"))
            assertEquals(0L, MemberVerificationStore.getVerifiedAt(context, "me", circle, dan, "aa", "bb"))

            MemberVerificationStore.setVerified(context, "me", "{$circle}", eve.uppercase(), "cc", "dd", 300L)
            assertEquals("300|cc|dd", prefs.data["me|$circle:$eve"])
        }
    }

    @Test
    fun `verified marks - a fold that doesn't land reads as unticked, and a cleared mark stays cleared under every spelling`() {
        withMarks(InMemoryPrefs().apply { data["me|$upper:$sam"] = "100|aa|bb" }) { context ->
            assertEquals("folded when it lands", 100L, MemberVerificationStore.getVerifiedAt(context, "me", circle, sam, "aa", "bb"))
        }

        val prefs = InMemoryPrefs()
        prefs.data["me|$upper:$sam"] = "100|aa|bb"
        prefs.failCommits = true
        withMarks(prefs) { context ->
            assertEquals(0L, MemberVerificationStore.getVerifiedAt(context, "me", circle, sam, "aa", "bb"))
            MemberVerificationStore.setVerified(context, "me", circle, eve, "cc", "dd", 300L)
            assertEquals("nothing written meanwhile", setOf("me|$upper:$sam"), prefs.data.keys)

            MemberVerificationStore.clearVerified(context, "me", bare, sam)
            assertEquals(emptySet<String>(), prefs.data.keys)
            prefs.failCommits = false
            assertEquals("never brought back", 0L, MemberVerificationStore.getVerifiedAt(context, "me", circle, sam, "aa", "bb"))
        }
    }

    @Test
    fun `key access approvals - a circle that can't be read vouches for nobody elsewhere, and never stops the others`() {
        val broken = "b2a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        val prefs = InMemoryPrefs()
        prefs.data["me|$circle#own"] = setOf(sam)
        prefs.data["me|${broken.uppercase()}#own"] = "not a set"
        prefs.data["me|$broken#own"] = setOf(dan)
        val approvals = SecurePrefsKeyAccessApprovals(mockk(), { "me" }, { prefs })

        assertEquals(setOf(sam), approvals.trustedInOtherCircles("c-9"))
        assertThrows(StoreUnreadable::class.java) { approvals.origins(broken) }
    }

    @Test
    fun `verified marks - while their fold hasn't landed, a mark is never read from the one spelling alone`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$circle:$sam"] = "100|aa|bb"
        prefs.data["me|$upper:$sam"] = "200|aa|bb"
        prefs.failCommits = true
        withMarks(prefs) { context ->
            assertEquals(0L, MemberVerificationStore.getVerifiedAt(context, "me", circle, sam, "aa", "bb"))
        }
    }

    @Test
    fun `pending invite accepts and renewal questions read their circle in its one spelling`() {
        val prefs = InMemoryPrefs()
        val accepts = SecurePrefsPendingInviteAccepts(mockk(), { "me" }, { prefs })
        accepts.put(PendingInviteAccepts.Pending("inv-1", upper, "v1", null))
        assertEquals(circle, accepts.all().single().circleId)

        val questions = SecurePrefsPendingRenewalQuestions(mockk(), { "me" }, { InMemoryPrefs() })
        questions.put(
            InviteRenewalQuestion(bare, "Family", "inv-1", st1.uppercase(), "s@x.test", sam, "sx", "ss", null, MembershipStatements.Signed("st", "sig"), false),
        )
        assertEquals(circle, questions.all().single().circleId)
        questions.remove(st1)
        assertEquals(emptyList<InviteRenewalQuestion>(), questions.all())
    }
}
