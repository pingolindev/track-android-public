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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Removal while behind: this user's removals without a key change (OwnRemovals). */
class OwnRemovalsTest {

    private val prefs = InMemoryPrefs()
    private var account = "me"
    private val removals = SecurePrefsOwnRemovals(mockk(), { account }, { prefs })

    @Test
    fun `a removal is kept per account and circle until a rotation from here names it`() {
        removals.add("c-1", "Sam", 100)
        removals.add("c-1", "dan", 200)
        removals.add("c-2", "eve", 300)

        assertEquals(setOf("sam", "dan"), removals.of("C-1"))
        assertEquals("when it was made, for this account's later word about them", 100L, removals.removedAt("c-1", "SAM"))

        account = "other"
        assertEquals("another account on this phone sees none", emptySet<String>(), removals.of("c-1"))

        account = "me"
        removals.settled("c-1", listOf("SAM"))
        assertEquals(setOf("dan"), removals.of("c-1"))
        removals.settled("c-1", listOf("dan"))
        assertEquals(emptySet<String>(), removals.of("c-1"))
        assertEquals(setOf("eve"), removals.of("c-2"))
    }

    @Test
    fun `nothing is kept without an account`() {
        account = ""
        removals.add("c-1", "sam", 100)
        assertEquals(emptySet<String>(), removals.of("c-1"))
    }

    @Test
    fun `a removal kept without its time is still named, and never taken as reconsidered`() {
        prefs.edit().putStringSet("me|c-1", setOf("sam")).commit()
        assertEquals(setOf("sam"), removals.of("c-1"))
        assertEquals(null, removals.removedAt("c-1", "sam"))
    }

    /**
     * What a store that can't be read must do: never "none", and nothing written over it; the
     * changes made meanwhile are held for the session.
     */
    private fun unreadable(removals: OwnRemovals) {
        assertThrows(StoreUnreadable::class.java) { removals.of("c-1") }
        assertThrows(StoreUnreadable::class.java) { removals.removedAt("c-1", "sam") }
        assertFalse(removals.readable("c-1"))
        assertFalse("held, not on disk", removals.add("c-1", "dan", 200))
        removals.settled("c-1", listOf("sam"))
    }

    @Test
    fun `a damaged read is never none - nothing is written over it, and once it reads the changes held meanwhile go in, in order`() {
        val prefs = DamagedPrefs()
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        assertTrue(removals.add("c-1", "sam", 100))
        val stored = prefs.data.toMap()

        prefs.damaged = true
        unreadable(removals)
        assertEquals("nothing written over it", stored, prefs.data)

        prefs.damaged = false
        assertEquals("dan's removal and sam's settle, held meanwhile", setOf("dan"), removals.of("c-1"))
        assertEquals(200L, removals.removedAt("c-1", "dan"))
        assertEquals("written once it read", mapOf("me|c-1" to setOf("dan|200")), prefs.data)
    }

    @Test
    fun `a file that can't be opened - the same, and it's opened again at the next use`() {
        val file = UnopenablePrefs(InMemoryPrefs().also { it.edit().putStringSet("me|c-1", setOf("sam|100")).commit() })
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, file.provider)

        file.unopenable = true
        unreadable(removals)
        assertEquals("nothing written over it", mapOf("me|c-1" to setOf("sam|100")), (file.prefs as InMemoryPrefs).data)

        file.unopenable = false
        assertEquals(setOf("dan"), removals.of("c-1"))
        assertTrue(removals.readable("c-1"))
        assertEquals(mapOf("me|c-1" to setOf("dan|200")), (file.prefs as InMemoryPrefs).data)
    }

    @Test
    fun `a removal the file won't take is held for the session and counts as made, and goes in with the next write that lands`() {
        assertTrue(removals.add("c-1", "sam", 100))
        prefs.failCommits = true

        assertFalse("not on disk", removals.add("c-1", "dan", 200))
        assertEquals("it stands all the same", setOf("sam", "dan"), removals.of("c-1"))
        assertEquals(mapOf("me|c-1" to setOf("sam|100")), prefs.data)

        prefs.failCommits = false
        assertTrue(removals.add("c-1", "eve", 300))
        assertEquals(mapOf("me|c-1" to setOf("sam|100", "dan|200", "eve|300")), prefs.data)

        // A restart before then loses what was held (a new store on the same file).
        prefs.failCommits = true
        assertFalse(removals.add("c-1", "fay", 400))
        prefs.failCommits = false
        assertEquals(setOf("sam", "dan", "eve"), SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs }).of("c-1"))
    }

    @Test
    fun `a re-invite's clear is kept against its invitation - withdrawn from here, the removal comes back as it was, once`() {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "SAM", "ST-1", 5_000)
        assertEquals(emptySet<String>(), removals.of("c-1"))

        removals.invitationWithdrawn("c-1", "st-2")
        assertEquals("another invitation brings nothing back", emptySet<String>(), removals.of("c-1"))
        removals.invitationWithdrawn("c-1", "st-1")
        assertEquals(setOf("sam"), removals.of("c-1"))
        assertEquals("as it was", 100L, removals.removedAt("c-1", "sam"))

        removals.settled("c-1", listOf("sam"))
        removals.invitationWithdrawn("c-1", "st-1")
        assertEquals("once only", emptySet<String>(), removals.of("c-1"))
        assertEquals("also after a restart", emptySet<String>(), SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs }).of("c-1"))
    }

    @Test
    fun `a later removal stays as it is when the older re-invite is withdrawn, and a clear with no removal keeps nothing`() {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        removals.add("c-1", "sam", 700)
        removals.invitationWithdrawn("c-1", "st-1")
        assertEquals(700L, removals.removedAt("c-1", "sam"))

        removals.clearedByInvitation("c-1", "dan", "st-2", 5_000)
        removals.invitationWithdrawn("c-1", "st-2")
        assertEquals(setOf("sam"), removals.of("c-1"))
    }

    @Test
    fun `a withdrawal survives a restart, and one made while the file can't be read is made in order`() {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        val restarted = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        restarted.invitationWithdrawn("c-1", "st-1")
        assertEquals(setOf("sam"), restarted.of("c-1"))

        val damaged = DamagedPrefs()
        val held = SecurePrefsOwnRemovals(mockk(), { "me" }, { damaged })
        held.add("c-1", "dan", 200)
        held.clearedByInvitation("c-1", "dan", "st-3", 5_000)
        damaged.damaged = true
        held.invitationWithdrawn("c-1", "st-3")
        held.clearedByInvitation("c-1", "dan", "st-4", 5_000)
        damaged.damaged = false
        assertEquals("withdrawn, then cleared again by another re-invite", emptySet<String>(), held.of("c-1"))
        held.invitationWithdrawn("c-1", "st-4")
        assertEquals(200L, held.removedAt("c-1", "dan"))
    }

    /** A withdrawal while the clears can't be read: held, nothing written over them, made once they read. */
    private fun withdrawnWhileUnreadable(removals: OwnRemovals, data: () -> Map<String, Any?>, unreadable: (Boolean) -> Unit) {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        val stored = data().toMap()
        assertEquals(1, (stored["me|c-1#cleared"] as Set<*>).size)

        unreadable(true)
        removals.invitationWithdrawn("c-1", "st-1")
        assertThrows(StoreUnreadable::class.java) { removals.of("c-1") }
        removals.settleRunOut("c-1", emptyList(), nowSecs = 10_000)
        assertEquals("nothing written over them", stored, data().toMap())

        unreadable(false)
        assertEquals("the withdrawal held meanwhile, made on what they hold", setOf("sam"), removals.of("c-1"))
        assertEquals(100L, removals.removedAt("c-1", "sam"))
    }

    @Test
    fun `the re-invites' clears never read as none - a damaged read`() {
        val prefs = DamagedPrefs()
        withdrawnWhileUnreadable(SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs }), { prefs.data }) { prefs.damaged = it }
    }

    @Test
    fun `the re-invites' clears never read as none - a file that can't be opened`() {
        val inner = InMemoryPrefs()
        val file = UnopenablePrefs(inner)
        // Opened once before (the lazy provider keeps it): unopenable from the next store on.
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, file.provider)
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        val stored = inner.data.toMap()
        file.unopenable = true
        val restarted = SecurePrefsOwnRemovals(mockk(), { "me" }, file.provider)
        restarted.invitationWithdrawn("c-1", "st-1")
        assertThrows(StoreUnreadable::class.java) { restarted.of("c-1") }
        assertEquals("nothing written over them", stored, inner.data.toMap())
        file.unopenable = false
        assertEquals(setOf("sam"), restarted.of("c-1"))
    }

    @Test
    fun `a re-invite that ran out unused brings its removal back at the plan, unless they joined with it`() {
        removals.add("c-1", "sam", 100)
        removals.add("c-1", "dan", 150)
        removals.clearedByInvitation("c-1", "sam", "st-sam", 1_000)
        removals.clearedByInvitation("c-1", "dan", "st-dan", 1_000)
        removals.add("c-1", "eve", 160)
        removals.clearedByInvitation("c-1", "eve", "st-eve", 9_000)

        removals.settleRunOut("c-1", listed = listOf("me", "dan"), nowSecs = 1_000 + 299)
        assertEquals("within the grace: nothing yet", emptySet<String>(), removals.of("c-1"))

        removals.settleRunOut("c-1", listed = listOf("me", "DAN"), nowSecs = 1_000 + 300)
        assertEquals("sam's comes back; dan joined with his; eve's still runs", setOf("sam"), removals.of("c-1"))
        assertEquals(100L, removals.removedAt("c-1", "sam"))

        removals.settled("c-1", listOf("sam"))
        removals.invitationWithdrawn("c-1", "st-sam")
        removals.invitationWithdrawn("c-1", "st-dan")
        assertEquals("their entries went", emptySet<String>(), removals.of("c-1"))
        removals.invitationWithdrawn("c-1", "st-eve")
        assertEquals("a running one untouched", setOf("eve"), removals.of("c-1"))
    }

    @Test
    fun `the re-invites' clears never read as none - one damaged value`() {
        // EncryptedSharedPreferences decrypts value by value: the removals can read while the clears don't.
        var damagedSlot = false
        val inner = InMemoryPrefs()
        val prefs = object : android.content.SharedPreferences by inner {
            override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
                if (damagedSlot && key.orEmpty().endsWith("#cleared")) throw SecurityException("Could not decrypt value")
                else inner.getStringSet(key, defValues)
        }
        val removals = SecurePrefsOwnRemovals(mockk(), { "me" }, { prefs })
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        val stored = inner.data.toMap()
        damagedSlot = true

        removals.invitationWithdrawn("c-1", "st-1")

        assertThrows(StoreUnreadable::class.java) { removals.of("c-1") }
        assertEquals("nothing written over them", stored, inner.data.toMap())
        damagedSlot = false
        assertEquals(setOf("sam"), removals.of("c-1"))
    }

    @Test
    fun `a re-invite that ran out leaves a later removal of them as it is`() {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-sam", 1_000)
        removals.add("c-1", "sam", 500)

        removals.settleRunOut("c-1", emptyList(), 1_000 + 301)

        assertEquals(500L, removals.removedAt("c-1", "sam"))
        removals.invitationWithdrawn("c-1", "st-sam")
        assertEquals("its entry went", 500L, removals.removedAt("c-1", "sam"))
    }

    @Test
    fun `a forgotten circle's removals go, whatever they held`() {
        removals.add("c-1", "sam", 100)
        removals.clearedByInvitation("c-1", "sam", "st-1", 5_000)
        removals.forget("C-1")
        assertEquals(emptySet<String>(), removals.of("c-1"))
        removals.invitationWithdrawn("c-1", "st-1")
        assertEquals(emptySet<String>(), removals.of("c-1"))
    }

    @Test
    fun `a forgotten circle's changes held for the session go with it`() {
        prefs.failCommits = true
        assertFalse(removals.add("c-1", "sam", 100))
        removals.forget("c-1")
        prefs.failCommits = false

        assertEquals(emptySet<String>(), removals.of("c-1"))
        removals.add("c-1", "dan", 200)
        assertEquals("nothing held comes back with the next write", setOf("dan"), removals.of("c-1"))
    }
}
