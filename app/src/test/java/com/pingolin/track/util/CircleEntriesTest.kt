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
import com.pingolin.track.util.CircleEntries.Held
import com.pingolin.track.util.CircleEntries.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One spelling per circle in the stores: what [CircleEntries] folds, how, and what it leaves.
 * Circle ids with hex letters, so upper case is another spelling.
 */
class CircleEntriesTest {

    private val circle = "c1a2b3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
    private val other = "fa0c1e00-0000-4000-8000-0000000000fa"
    private val upper = circle.uppercase()
    private val bare = circle.replace("-", "")

    /** Sets merged, a Long the latest: a store's rules in small. */
    private val rules = object : CircleEntries.Rules {
        override fun rest(rest: String) = if (rest.startsWith("|")) "|${AccountIds.storeKey(rest.substring(1))}" else rest
        override fun fold(entries: Map<String, List<Held>>, spellings: Set<String>) = entries.mapValues { (rest, held) ->
            when {
                rest == "#at" -> CircleEntries.latest(held)
                rest == "#name" -> CircleEntries.agreedOrRefused(held)
                else -> CircleEntries.union(held, AccountIds::storeKey)
            }
        }
    }

    @Test
    fun `a key's circle part is what comes before its first separator, a urn spelling whole`() {
        assertEquals(circle to "", CircleEntries.split("me|$circle", "me"))
        assertEquals(circle to "#at", CircleEntries.split("me|$circle#at", "me"))
        assertEquals(circle to "|sam", CircleEntries.split("me|$circle|sam", "me"))
        assertEquals(circle to ":sam", CircleEntries.split("me|$circle:sam", "me"))
        assertEquals("urn:uuid:$circle" to ":sam", CircleEntries.split("me|urn:uuid:$circle:sam", "me"))
        assertEquals("{$circle}" to "#bfids|sam", CircleEntries.split("me|{$circle}#bfids|sam", "me"))
        assertNull("another account's", CircleEntries.split("you|$circle", "me"))
        assertNull("the account's own entry names no circle", CircleEntries.split("me|#pinseed", "me"))
    }

    @Test
    fun `entries under other spellings of a circle fold under its one spelling, in one commit, the others gone`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$upper"] = setOf("sam")
        prefs.data["me|$bare"] = setOf("DAN")
        prefs.data["me|$circle"] = setOf("eve")
        prefs.data["me|{$circle}#at"] = 100L
        prefs.data["me|$circle#at"] = 200L
        prefs.data["me|urn:uuid:$circle|SAM"] = setOf("x")
        prefs.data["me|$other"] = setOf("ann")

        val folded = CircleEntries.fold("store", { prefs }, "me", rules)

        assertTrue(folded.done)
        assertEquals(emptySet<String>(), folded.unreadable)
        assertEquals(
            mapOf(
                "me|$circle" to setOf("sam", "dan", "eve"),
                "me|$circle#at" to 200L,
                "me|$circle|sam" to setOf("x"),
                "me|$other" to setOf("ann"),
            ),
            prefs.data,
        )
    }

    @Test
    fun `a second fold finds nothing to do`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$upper"] = setOf("sam")
        prefs.data["me|$circle"] = setOf("eve")
        CircleEntries.fold("store", { prefs }, "me", rules)
        val once = prefs.data.toMap()

        // Nothing to write: a commit that can't land changes nothing, and no circle reads as unreadable.
        prefs.failCommits = true
        val again = CircleEntries.fold("store", { prefs }, "me", rules)

        assertTrue(again.done)
        assertEquals(emptySet<String>(), again.unreadable)
        assertEquals(once, prefs.data)
    }

    @Test
    fun `a key whose circle part is no UUID is kept as it is, matched only by itself`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|C-1"] = setOf("sam")
        prefs.data["me|c-1"] = setOf("dan")

        assertTrue(CircleEntries.fold("store", { prefs }, "me", rules).done)

        assertEquals(mapOf("me|C-1" to setOf("sam"), "me|c-1" to setOf("dan")), prefs.data)
        assertEquals("c-1", AccountIds.storeKey(" C-1 "))
    }

    @Test
    fun `a file that doesn't read moves nothing and has nothing written over it`() {
        val prefs = DamagedPrefs()
        prefs.data["me|$upper"] = setOf("sam")
        prefs.damaged = true

        val folded = CircleEntries.fold("store", { prefs }, "me", rules)

        assertFalse(folded.done)
        assertEquals(mapOf("me|$upper" to setOf("sam")), prefs.data)
    }

    @Test
    fun `a fold that doesn't land leaves both spellings, and the circle reads as unreadable until one does`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$upper"] = setOf("sam")
        prefs.data["me|$circle"] = setOf("eve")
        prefs.data["me|$other"] = setOf("ann")
        val stored = prefs.data.toMap()
        prefs.failCommits = true

        val folded = CircleEntries.fold("store", { prefs }, "me", rules)

        assertFalse(folded.done)
        assertEquals(setOf("me|$circle"), folded.unreadable)
        assertEquals(stored, prefs.data)

        prefs.failCommits = false
        val again = CircleEntries.fold("store", { prefs }, "me", rules)
        assertTrue(again.done)
        assertEquals(emptySet<String>(), again.unreadable)
        assertEquals(setOf("sam", "eve"), prefs.data["me|$circle"])
    }

    @Test
    fun `a circle a rule refuses stays as stored and reads as unreadable, and the others fold`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$upper#name"] = "Family"
        prefs.data["me|$circle#name"] = "Friends"
        prefs.data["me|${other.uppercase()}"] = setOf("ann")

        val folded = CircleEntries.fold("store", { prefs }, "me", rules)

        assertTrue(folded.done)
        assertEquals(setOf("me|$circle"), folded.unreadable)
        assertEquals(
            mapOf("me|$upper#name" to "Family", "me|$circle#name" to "Friends", "me|$other" to setOf("ann")),
            prefs.data,
        )
    }

    @Test
    fun `a damaged value refuses its circle, and nothing is written over it`() {
        val prefs = InMemoryPrefs()
        prefs.data["me|$upper"] = "not a set"
        prefs.data["me|$circle"] = setOf("eve")

        val folded = CircleEntries.fold("store", { prefs }, "me", rules)

        assertEquals(setOf("me|$circle"), folded.unreadable)
        assertEquals(mapOf("me|$upper" to "not a set", "me|$circle" to setOf("eve")), prefs.data)
    }

    @Test
    fun `every key of a circle, under any spelling, and of one entry`() {
        val all = mapOf(
            "me|$upper" to 1, "me|$circle#at" to 2, "me|{$circle}|SAM" to 3, "me|$circle|dan" to 4,
            "me|$other" to 5, "you|$circle" to 6, "me|c-1" to 7,
        )
        assertEquals(
            setOf("me|$upper", "me|$circle#at", "me|{$circle}|SAM", "me|$circle|dan"),
            CircleEntries.keysOf(all, "me", bare).toSet(),
        )
        assertEquals(listOf("me|{$circle}|SAM"), CircleEntries.keysOf(all, "me", circle, rules, "|sam"))
        assertEquals(listOf("me|c-1"), CircleEntries.keysOf(all, "me", "C-1"))
    }

    // ---- the rules the stores are made of ------------------------------------------------------

    private fun held(vararg values: Pair<String, Any?>) = values.mapIndexed { i, (spelling, value) -> Held(spelling, i == 0, value) }

    @Test
    fun `union keeps every spelling's, each id in its one spelling`() {
        assertEquals(
            Outcome.Keep(setOf(circle, "dan")),
            CircleEntries.union(held(circle to setOf("DAN"), upper to setOf(bare)), AccountIds::storeKey),
        )
        assertEquals(Outcome.Refuse, CircleEntries.union(held(circle to setOf("dan"), upper to "dan")))
    }

    @Test
    fun `intersection keeps what every spelling holds, and a spelling without the entry holds none`() {
        val spellings = setOf(circle, upper)
        assertEquals(Outcome.Keep(setOf("a")), CircleEntries.intersection(held(circle to setOf("a", "b"), upper to setOf("A")), spellings) { it.lowercase() })
        assertEquals(Outcome.Drop, CircleEntries.intersection(held(circle to setOf("a")), spellings))
        assertEquals(Outcome.Keep(setOf("a")), CircleEntries.intersection(held(circle to setOf("a")), setOf(circle)))
    }

    @Test
    fun `agreed keeps a value only when every spelling holds it`() {
        val spellings = setOf(circle, upper)
        assertEquals(Outcome.Keep("x"), CircleEntries.agreed(held(circle to "x", upper to "x"), spellings))
        assertEquals(Outcome.Drop, CircleEntries.agreed(held(circle to "x", upper to "y"), spellings))
        assertEquals("one spelling holds none", Outcome.Drop, CircleEntries.agreed(held(circle to "x"), spellings))
        assertEquals(Outcome.Keep("x"), CircleEntries.agreed(held(circle to "x"), setOf(circle)))
        assertEquals(Outcome.Refuse, CircleEntries.agreed(held(circle to "x", upper to 1L), spellings) { it as? String })
    }

    @Test
    fun `agreed or refused refuses two that differ`() {
        assertEquals(Outcome.Keep("x"), CircleEntries.agreedOrRefused(held(circle to "x", upper to "x")))
        assertEquals(Outcome.Refuse, CircleEntries.agreedOrRefused(held(circle to "x", upper to "y")))
    }

    @Test
    fun `a flag stands only where every spelling holds it`() {
        val spellings = setOf(circle, upper)
        assertEquals(Outcome.Keep(true), CircleEntries.all(held(circle to true, upper to true), spellings))
        assertEquals(Outcome.Drop, CircleEntries.all(held(circle to true), spellings))
        assertEquals(Outcome.Drop, CircleEntries.all(held(circle to true, upper to false), spellings))
        assertEquals(Outcome.Refuse, CircleEntries.all(held(circle to true, upper to "true"), spellings))
    }

    @Test
    fun `the latest time stands`() {
        assertEquals(Outcome.Keep(300L), CircleEntries.latest(held(circle to 100L, upper to 300L)))
        assertEquals(Outcome.Refuse, CircleEntries.latest(held(circle to 100L, upper to "300")))
    }

    @Test
    fun `per id, one value is picked among every spelling's, or the circle refused`() {
        val later = { values: List<String> -> values.maxByOrNull { it.toLong() } }
        assertEquals(
            Outcome.Keep(setOf("sam|300", "dan|100")),
            CircleEntries.perId(held(circle to setOf("sam|100", "dan|100"), upper to setOf("SAM|300")), "|", AccountIds::storeKey, later),
        )
        assertEquals(
            Outcome.Refuse,
            CircleEntries.perId(held(circle to setOf("sam=admin"), upper to setOf("sam=member")), "=", AccountIds::storeKey) { it.singleOrNull() },
        )
    }
}
