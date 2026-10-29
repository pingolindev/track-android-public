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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** This account's own sealed name: which name, and when a circle is owed it. As iOS's ownName / ownNameOwed. */
class OwnNameTest {

    @Test
    fun `the display name, else the address, never a blank one`() {
        assertEquals("Sam", OwnName.of("  Sam ", "sam@x.test"))
        assertEquals("sam@x.test", OwnName.of("   ", " sam@x.test "))
        assertEquals("sam@x.test", OwnName.of(null, "sam@x.test"))
        assertNull(OwnName.of(" ", null))
        assertNull(OwnName.of(null, null))
    }

    @Test
    fun `owed when nothing opens, under an older key, or as another name`() {
        assertTrue("nothing sealed, or it doesn't open", OwnName.owed("Sam", null, holdsCurrentKey = true))
        assertTrue("under a key this device no longer seals with", OwnName.owed("Sam", OwnName.Opened("Sam", current = false), holdsCurrentKey = true))
        assertTrue("renamed since", OwnName.owed("Sam", OwnName.Opened("Samuel", current = true), holdsCurrentKey = true))
    }

    @Test
    fun `not owed when it opens as this name under the current key`() {
        assertFalse(OwnName.owed("Sam", OwnName.Opened("Sam", current = true), holdsCurrentKey = true))
        assertFalse("the name compared as sealed", OwnName.owed(" Sam ", OwnName.Opened("Sam", current = true), holdsCurrentKey = true))
    }

    @Test
    fun `never owed without the current key or without a name`() {
        assertFalse(OwnName.owed("Sam", null, holdsCurrentKey = false))
        assertFalse(OwnName.owed(null, null, holdsCurrentKey = true))
        assertFalse(OwnName.owed("  ", null, holdsCurrentKey = true))
    }
}
