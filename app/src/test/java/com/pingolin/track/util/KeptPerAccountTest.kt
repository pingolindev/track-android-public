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
import com.pingolin.track.testutil.UnopenablePrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stores kept per account on this phone, as on iOS: an account's sign-in keeps only its own
 * entries, the account compared ignoring case; the same account keeps them across a sign-out; an
 * erasure leaves no account's; a file that doesn't read loses nothing and has nothing written
 * over it, and is cleaned at the next sign-in that reads it.
 */
class KeptPerAccountTest {

    /** A store: made for the account [owner] names over [prefs]; [write] puts an entry in "c-1"; [holds] says whether one is there. */
    private class Store(
        val name: String,
        val file: String,
        val make: (context: Context, owner: () -> String?, prefs: (Context) -> SharedPreferences) -> KeptPerAccount,
        val write: KeptPerAccount.() -> Unit,
        val holds: KeptPerAccount.() -> Boolean,
    )

    private val question = InviteRenewalQuestion(
        "c-1", "Family", "inv-1", "st-1", "sam@x.test", "sam", "sx", "ss", null, MembershipStatements.Signed("statement", "signature"),
    )

    private val stores = listOf(
        Store("own removals", "own_removals", { c, o, p -> SecurePrefsOwnRemovals(c, o, p) },
            { (this as OwnRemovals).add("c-1", "sam", 100) }, { (this as OwnRemovals).of("c-1").isNotEmpty() }),
        Store("owner role changes", "owner_role_changes", { c, o, p -> SecurePrefsOwnerRoleChanges(c, o, p) },
            { (this as OwnerRoleChanges).put("c-1", "sam", "admin") }, { (this as OwnerRoleChanges).of("c-1").isNotEmpty() }),
        Store("repair budget", "repair_budget", { c, o, p -> SecurePrefsRepairBudget(c, o, p) },
            { (this as RepairBudget).spend("c-1", 3, listOf("sam"), 1_000) }, { (this as RepairBudget).repaired("c-1").isNotEmpty() }),
        Store("late-joiner answers", "late_joiner_answers", { c, o, p -> SecurePrefsLateJoinerAnswers(c, o, p) },
            { (this as LateJoinerAnswers).answer("c-1", LateJoiners.Invitation("st-1", "ann", "sam", 100)) },
            { (this as LateJoinerAnswers).answered("c-1").isNotEmpty() }),
        Store("hand-overs", "hand_overs", { c, o, p -> SecurePrefsHandOvers(c, o, p) },
            { (this as HandOvers).remember("c-1", HandOvers.HandOver(4, "sam")) }, { (this as HandOvers).of("c-1") != null }),
        Store("pending invite accepts", "pending_invite_accepts", { c, o, p -> SecurePrefsPendingInviteAccepts(c, o, p) },
            { (this as PendingInviteAccepts).put(PendingInviteAccepts.Pending("inv-1", "c-1", "v1", null)) },
            { (this as PendingInviteAccepts).all().isNotEmpty() }),
        Store("person-confirmed keys", "person_confirmed_keys", { c, o, p -> SecurePrefsPersonConfirmedKeys(c, o, p) },
            { (this as PersonConfirmedKeys).confirm("c-1", "sam", "sx", "ss") },
            { (this as PersonConfirmedKeys).confirmed("c-1", "sam", "sx", "ss") }),
        Store("pending renewal questions", "pending_renewal_questions", { c, o, p -> SecurePrefsPendingRenewalQuestions(c, o, p) },
            { (this as PendingRenewalQuestions).put(question) }, { (this as PendingRenewalQuestions).all().isNotEmpty() }),
        Store("key access approvals", "key_access_approvals", { c, o, p -> SecurePrefsKeyAccessApprovals(c, o, p) },
            { (this as KeyAccessApprovals).approve("c-1", listOf("sam")) }, { (this as KeyAccessApprovals).approved("c-1").isNotEmpty() }),
    )

    @Test
    fun `an account's sign-in keeps only its own entries, the account compared ignoring case`() {
        for (s in stores) {
            val prefs = InMemoryPrefs()
            var owner = "Me"
            val store = s.make(mockk(), { owner }, { prefs })
            s.write(store)
            owner = "other"
            s.write(store)

            store.keepOnly("me")

            owner = "Me"
            assertTrue("${s.name}: its own stay", s.holds(store))
            owner = "other"
            assertFalse("${s.name}: another account's go", s.holds(store))
        }
    }

    @Test
    fun `the same account keeps its entries across a sign-out, and an erasure leaves no account's`() {
        for (s in stores) {
            val prefs = InMemoryPrefs()
            var owner = "me"
            val store = s.make(mockk(), { owner }, { prefs })
            s.write(store)
            owner = "other"
            s.write(store)

            // A sign-out calls nothing: signed in again (a restart, too), it's all there.
            assertTrue(s.name, s.holds(s.make(mockk(), { "me" }, { prefs })))

            store.wipeAll()

            assertEquals("${s.name}: nothing left", emptyMap<String, Any?>(), prefs.data)
            assertFalse(s.name, s.holds(store))
            owner = "me"
            assertFalse(s.name, s.holds(store))
        }
    }

    @Test
    fun `a file that doesn't read loses nothing and has nothing written over it, and is cleaned at the next sign-in that reads it`() {
        for (s in stores) {
            val prefs = DamagedPrefs()
            var owner = "me"
            val store = s.make(mockk(), { owner }, { prefs })
            s.write(store)
            owner = "other"
            s.write(store)
            val stored = prefs.data.toMap()
            prefs.damaged = true

            store.keepOnly("me")

            assertEquals("${s.name}: as it was", stored, prefs.data)
            prefs.damaged = false
            assertTrue(s.name, s.holds(store))
            store.keepOnly("me")
            assertFalse("${s.name}: cleaned once it reads", s.holds(store))
        }
    }

    @Test
    fun `a file that can't be opened is kept at a sign-in, and deleted whole at an erasure`() {
        for (s in stores) {
            val inner = InMemoryPrefs()
            val file = UnopenablePrefs(inner)
            val context = mockk<Context>()
            every { context.deleteSharedPreferences(any()) } returns true
            var owner = "other"
            s.write(s.make(context, { owner }, { inner }))
            val stored = inner.data.toMap()
            file.unopenable = true
            owner = "me"
            val store = s.make(context, { owner }, file.provider)

            store.keepOnly("me")
            assertEquals("${s.name}: kept", stored, inner.data)

            store.wipeAll()
            verify(exactly = 1) { context.deleteSharedPreferences(s.file) }
        }
    }

    @Test
    fun `changes held for the session go with their account, and an erasure drops them all`() {
        for (s in stores.filter { it.name == "own removals" || it.name == "owner role changes" }) {
            val prefs = InMemoryPrefs()
            var owner = "other"
            val store = s.make(mockk(), { owner }, { prefs })
            prefs.failCommits = true
            s.write(store)
            owner = "me"
            s.write(store)

            store.keepOnly("me")
            prefs.failCommits = false

            assertTrue("${s.name}: its own held one stands", s.holds(store))
            owner = "other"
            assertFalse("${s.name}: another account's held one went", s.holds(store))

            val fresh = InMemoryPrefs()
            val again = s.make(mockk(), { "me" }, { fresh })
            fresh.failCommits = true
            s.write(again)
            fresh.failCommits = false
            again.wipeAll()
            assertFalse("${s.name}: no held one outlives an erasure", s.holds(again))
        }
    }

    // ---- the account id, canonical (AccountIds) -----------------------------------------

    private val canonical = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Test
    fun `entries kept under another spelling of the account read under its canonical id, moved there`() {
        for (s in stores) for (spelling in listOf(canonical.uppercase(), canonical.replace("-", ""))) {
            val prefs = InMemoryPrefs()
            s.write(s.make(mockk(), { spelling }, { prefs }))

            val store = s.make(mockk(), { canonical }, { prefs })

            assertTrue("${s.name} / $spelling", s.holds(store))
            assertTrue("${s.name} / $spelling: moved", prefs.data.keys.all { AccountEntries.ownerOf(it) == canonical })
        }
    }

    @Test
    fun `an account's sign-in keeps its entries under another spelling of its id`() {
        // Kept before anything reads them under the canonical id (which would move them first).
        for (spelling in listOf(canonical.uppercase(), canonical.replace("-", ""), "{$canonical}", "urn:uuid:$canonical")) {
            for (s in stores) {
                val prefs = InMemoryPrefs()
                s.write(s.make(mockk(), { spelling }, { prefs }))

                s.make(mockk(), { "other" }, { prefs }).keepOnly(canonical)

                assertTrue("${s.name}, $spelling", s.holds(s.make(mockk(), { canonical }, { prefs })))
            }
        }
    }

    @Test
    fun `a file that doesn't read moves nothing, and moves once it reads`() {
        for (s in stores) {
            val prefs = DamagedPrefs()
            s.write(s.make(mockk(), { canonical.uppercase() }, { prefs }))
            val stored = prefs.data.toMap()
            prefs.damaged = true
            val store = s.make(mockk(), { canonical }, { prefs })

            runCatching { s.holds(store) }

            assertEquals(s.name, stored, prefs.data)
            prefs.damaged = false
            assertTrue(s.name, s.holds(store))
        }
    }

    @Test
    fun `a set kept under two spellings is merged, and a value kept under both keeps the canonical one`() {
        val prefs = InMemoryPrefs()
        prefs.data["${canonical.uppercase()}|c-1"] = setOf("sam|100")
        prefs.data["$canonical|c-1"] = setOf("dan|200")
        prefs.data["${canonical.uppercase()}|c-1#at"] = 1_000L
        prefs.data["$canonical|c-1#at"] = 2_000L

        assertEquals(setOf("sam", "dan"), SecurePrefsOwnRemovals(mockk(), { canonical }, { prefs }).of("c-1"))
        assertEquals(2_000L, SecurePrefsRepairBudget(mockk(), { canonical }, { prefs }).lastRepairAt("c-1"))
        assertTrue(prefs.data.keys.none { it.startsWith(canonical.uppercase()) })
    }

    /** A sign-in's typed email against the one another account saved here (the typed-email guard). */
    private data class GuardCase(val name: String, val saved: String?, val typed: String?, val gone: Boolean)

    @Test
    fun `another account's owed removals, role changes and approvals go at a sign-in only when another email was typed`() {
        // End to end: each store with the guard AccountEmails gives.
        val ann = "a11ce000-0000-4000-8000-00000000000a"
        val ben = "b0b00000-0000-4000-8000-00000000000b"
        for (s in stores.filter { it.name in setOf("own removals", "owner role changes", "key access approvals") }) {
            for (case in listOf(
                GuardCase("another email typed", saved = "ann@x.test", typed = "ben@x.test", gone = true),
                GuardCase("ann's email typed, under a new id", saved = "ann@x.test", typed = " ANN@x.test", gone = false),
                GuardCase("no email typed", saved = "ann@x.test", typed = null, gone = false),
                GuardCase("no email saved for ann", saved = null, typed = "ben@x.test", gone = false),
            )) {
                val prefs = InMemoryPrefs()
                val store = s.make(mockk(), { ann }, { prefs })
                s.write(store)
                val emails = AccountEmails(mockk()) { InMemoryPrefs() }
                case.saved?.let { emails.record(ann, it) }

                store.keepOnly(ben, emails.guard(case.typed))

                assertEquals("${s.name}: ${case.name}", !case.gone, s.holds(store))
            }
        }
    }

    @Test
    fun `owed removals and role changes go for another account only where the typed-email guard says so`() {
        for (s in stores.filter { it.name == "own removals" || it.name == "owner role changes" }) {
            for (held in listOf(false, true)) {
                val prefs = InMemoryPrefs()
                val store = s.make(mockk(), { "other" }, { prefs })
                // Held for the session: the file takes no write while this runs.
                prefs.failCommits = held
                s.write(store)

                store.keepOnly("me") { false }
                assertTrue("${s.name}, held $held: kept", s.holds(store))

                store.keepOnly("me") { it == "other" }
                assertFalse("${s.name}, held $held: gone", s.holds(store))
            }
        }
    }
}
