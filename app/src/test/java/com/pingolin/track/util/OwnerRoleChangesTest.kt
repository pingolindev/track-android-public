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

/** The role changes this user made, until its own rotation records them (OwnerRoleChanges). */
class OwnerRoleChangesTest {

    @Test
    fun `the latest change of each member is kept per account and circle until a rotation from here records it`() {
        var account = "me"
        val prefs = InMemoryPrefs()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { account }, { prefs })
        assertTrue(changes.put("c-1", "Ada", "admin"))
        assertTrue(changes.put("c-1", "ada", "member"))
        assertTrue(changes.put("c-1", "bob", "admin"))
        assertEquals(mapOf("ada" to "member", "bob" to "admin"), changes.of("C-1"))

        account = "other"
        assertEquals("another account on this phone sees none", emptyMap<String, String>(), changes.of("c-1"))

        account = "me"
        changes.settled("c-1", mapOf("ADA" to "member"))
        assertEquals(mapOf("bob" to "admin"), changes.of("c-1"))
        changes.forget("c-1")
        assertEquals(emptyMap<String, String>(), changes.of("c-1"))
    }

    /**
     * What a store that can't be read must do: never "none", and nothing written over it; the
     * changes made meanwhile are held for the session.
     */
    private fun unreadable(changes: OwnerRoleChanges) {
        assertThrows(StoreUnreadable::class.java) { changes.of("c-1") }
        assertFalse(changes.readable("c-1"))
        assertFalse("held, not on disk", changes.put("c-1", "bob", "admin"))
        changes.settled("c-1", mapOf("ada" to "member"))
    }

    @Test
    fun `a record settles a change only while it still holds the role written - one made since stays owed`() {
        val prefs = InMemoryPrefs()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { prefs })
        // "Make admin", its record on its way, then "Remove admin" before the server takes it.
        changes.put("c-1", "q", "admin")
        changes.put("c-1", "q", "member")
        changes.settled("c-1", mapOf("q" to "admin"))
        assertEquals("q stays owed his demotion", mapOf("q" to "member"), changes.of("c-1"))

        changes.settled("c-1", mapOf("Q" to "member"))
        assertEquals(emptyMap<String, String>(), changes.of("c-1"))
    }

    @Test
    fun `a damaged read is never none - nothing is written over it, and once it reads the changes held meanwhile go in, in order`() {
        val prefs = DamagedPrefs()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { prefs })
        assertTrue(changes.put("c-1", "ada", "member"))
        val stored = prefs.data.toMap()

        prefs.damaged = true
        unreadable(changes)
        assertEquals("nothing written over it", stored, prefs.data)

        prefs.damaged = false
        assertEquals("bob's change and ada's settle, held meanwhile", mapOf("bob" to "admin"), changes.of("c-1"))
        assertEquals("written once it read", mapOf("me|c-1" to setOf("bob=admin")), prefs.data)
    }

    @Test
    fun `a file that can't be opened - the same, and it's opened again at the next use`() {
        val file = UnopenablePrefs(InMemoryPrefs().also { it.edit().putStringSet("me|c-1", setOf("ada=member")).commit() })
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, file.provider)

        file.unopenable = true
        unreadable(changes)
        assertEquals("nothing written over it", mapOf("me|c-1" to setOf("ada=member")), (file.prefs as InMemoryPrefs).data)

        file.unopenable = false
        assertEquals(mapOf("bob" to "admin"), changes.of("c-1"))
        assertEquals(mapOf("me|c-1" to setOf("bob=admin")), (file.prefs as InMemoryPrefs).data)
    }

    @Test
    fun `a change the file won't take is held for the session and counts as made, and goes in with the next write that lands`() {
        val prefs = InMemoryPrefs()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { prefs })
        prefs.failCommits = true

        assertFalse("not on disk", changes.put("c-1", "ada", "member"))
        assertEquals("it stands all the same", mapOf("ada" to "member"), changes.of("c-1"))
        assertEquals(emptyMap<String, Any?>(), prefs.data)

        prefs.failCommits = false
        assertTrue(changes.put("c-1", "bob", "admin"))
        assertEquals(mapOf("me|c-1" to setOf("ada=member", "bob=admin")), prefs.data)
    }

    @Test
    fun `a forgotten circle's changes held for the session go with it`() {
        val prefs = InMemoryPrefs()
        val changes = SecurePrefsOwnerRoleChanges(mockk(), { "me" }, { prefs })
        prefs.failCommits = true
        assertFalse(changes.put("c-1", "ada", "admin"))
        changes.forget("c-1")
        prefs.failCommits = false

        assertEquals(emptyMap<String, String>(), changes.of("c-1"))
        assertTrue(changes.put("c-1", "bob", "admin"))
        assertEquals("nothing held comes back with the next write", mapOf("me|c-1" to setOf("bob=admin")), prefs.data)
    }
}
