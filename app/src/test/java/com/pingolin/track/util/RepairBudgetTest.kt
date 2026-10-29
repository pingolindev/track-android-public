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
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The repair budget a device spent, per account and circle (RepairBudget). */
class RepairBudgetTest {

    private val prefs = InMemoryPrefs()
    private var account = "me"
    private val budget = SecurePrefsRepairBudget(mockk(), { account }, { prefs })

    @Test
    fun `each repair is kept with when, and whom its record didn't allow`() {
        assertEquals(emptySet<Int>(), budget.repaired("c-1"))
        assertNull(budget.lastRepairAt("c-1"))

        budget.spend("C-1", 3, listOf("Bob"), 1_000L)
        budget.spend("c-1", 5, listOf("carol"), 2_000L)

        assertEquals(setOf(3, 5), budget.repaired("c-1"))
        assertEquals(2_000L, budget.lastRepairAt("c-1"))
        assertEquals(setOf("carol"), budget.lastRepairedFor("c-1"))
        assertEquals(emptySet<Int>(), budget.repaired("c-2"))

        account = "other"
        assertEquals("another account on this phone spent nothing", emptySet<Int>(), budget.repaired("c-1"))
    }

    /** What a budget that can't be read must do: never "none spent", and nothing written over it. */
    private fun unreadable(budget: RepairBudget) {
        assertThrows(StoreUnreadable::class.java) { budget.repaired("c-1") }
        assertThrows(StoreUnreadable::class.java) { budget.lastRepairAt("c-1") }
        assertThrows(StoreUnreadable::class.java) { budget.lastRepairedFor("c-1") }
        budget.spend("c-1", 7, listOf("dan"), 9_000L)
    }

    @Test
    fun `a damaged read is never none - nothing is written over it, and it's all there once it reads`() {
        val prefs = DamagedPrefs()
        val budget = SecurePrefsRepairBudget(mockk(), { "me" }, { prefs })
        budget.spend("c-1", 3, listOf("bob"), 1_000L)
        val stored = prefs.data.toMap()

        prefs.damaged = true
        unreadable(budget)
        assertEquals("nothing written over it", stored, prefs.data)

        prefs.damaged = false
        assertEquals(setOf(3), budget.repaired("c-1"))
        assertEquals(1_000L, budget.lastRepairAt("c-1"))
        assertEquals(setOf("bob"), budget.lastRepairedFor("c-1"))
    }

    @Test
    fun `a file that can't be opened - the same, and it's opened again at the next use`() {
        val inner = InMemoryPrefs()
        SecurePrefsRepairBudget(mockk(), { "me" }, { inner }).spend("c-1", 3, listOf("bob"), 1_000L)
        val stored = inner.data.toMap()
        val file = UnopenablePrefs(inner)
        val budget = SecurePrefsRepairBudget(mockk(), { "me" }, file.provider)

        file.unopenable = true
        unreadable(budget)
        assertEquals("nothing written over it", stored, inner.data)

        file.unopenable = false
        assertEquals(setOf(3), budget.repaired("c-1"))
    }
}
