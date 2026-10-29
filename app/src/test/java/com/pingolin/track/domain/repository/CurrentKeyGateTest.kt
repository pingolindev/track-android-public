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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.SosAlertDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.MobileException

/**
 * A device a rotation left behind seals nothing new for that circle: the only key
 * it holds is one a removed member may hold too.
 */
class CurrentKeyGateTest {

    private val circleDao = mockk<CircleDao>()
    private val cryptoManager = mockk<CryptoManager>()
    private val sosAlertDao = mockk<SosAlertDao>()
    private val sessionManager = mockk<SessionManager>().also { every { it.getUserId() } returns "me" }
    private val gate = CurrentKeyGate(circleDao, cryptoManager, sosAlertDao, sessionManager)

    private fun circle(id: String, keyVersion: Int) = CircleEntity(
        id = id, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0, keyVersion = keyVersion,
    )

    private fun listed(id: String, circleVersion: Int, held: Int?) {
        coEvery { circleDao.getById(id) } returns circle(id, circleVersion)
        if (held != null) {
            every { cryptoManager.getActiveVersion(id) } returns held
        } else {
            every { cryptoManager.getActiveVersion(id) } throws MobileException.KeyNotFound("no key")
        }
        every { cryptoManager.newestHeldVersion(id) } returns held
        every { cryptoManager.isBehind(id) } returns false
    }

    @Test
    fun `holding the circle's version is holding the current key`() = runTest {
        listed("c", circleVersion = 4, held = 4)

        assertTrue(gate.holdsCurrentKey("c"))
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `a write from a phone behind waits for it to catch up, and goes ahead when it has`() = runTest {
        // No "try again once synced" when catching up takes a moment.
        listed("c", circleVersion = 4, held = 3)
        gate.catchUp = { circleId ->
            every { cryptoManager.getActiveVersion(circleId) } returns 4
            true
        }

        gate.requireCurrentKey("c")
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `a write from a phone that could not catch up is still refused`() = runTest {
        listed("c", circleVersion = 4, held = 3)
        gate.catchUp = { false }

        assertThrows(KeyNotCurrentException::class.java) { kotlinx.coroutines.runBlocking { gate.requireCurrentKey("c") } }
    }

    @Test
    fun `an older key is behind, and the circle is marked so`() = runTest {
        listed("c", circleVersion = 4, held = 3)

        assertFalse(gate.holdsCurrentKey("c"))
        assertEquals(setOf("c"), gate.behind.value)
        assertThrows(KeyNotCurrentException::class.java) { kotlinx.coroutines.runBlocking { gate.requireCurrentKey("c") } }
    }

    @Test
    fun `catching up clears the mark`() = runTest {
        listed("c", circleVersion = 4, held = 3)
        gate.holdsCurrentKey("c")

        listed("c", circleVersion = 4, held = 4)

        assertTrue(gate.holdsCurrentKey("c"))
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `no key at all seals nothing, and is a locked circle rather than a behind one`() = runTest {
        listed("c", circleVersion = 4, held = null)

        assertFalse(gate.holdsCurrentKey("c"))
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `keys held for reading only are behind - a version waiting on its record, refused here, or forked`() = runTest {
        // The SDK names no version to seal under, and holds v5 to read with.
        listed("c", circleVersion = 5, held = null)
        every { cryptoManager.newestHeldVersion("c") } returns 5

        assertFalse(gate.holdsCurrentKey("c"))
        assertEquals(setOf("c"), gate.behind.value)
        assertThrows(KeyNotCurrentException::class.java) { kotlinx.coroutines.runBlocking { gate.requireCurrentKey("c") } }

        gate.clear()
        gate.noteListed(listOf(circle("c", 5)))
        assertEquals(setOf("c"), gate.behind.value)

        // Its record accepted: current again.
        listed("c", circleVersion = 5, held = 5)
        assertTrue(gate.holdsCurrentKey("c"))
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `a re-key this device owes holds its seals back, never the rotation that ends it`() = runTest {
        // As on iPhone.
        listed("c", circleVersion = 4, held = 4)
        gate.reKeyPending = { it.id == "c" }

        assertFalse(gate.holdsCurrentKey("c"))
        assertTrue(gate.holdsCurrentVersion("c"))
        assertEquals("not behind: nothing is missing from another member", emptySet<String>(), gate.behind.value)

        gate.reKeyPending = { false }
        assertTrue(gate.holdsCurrentKey("c"))
    }

    @Test
    fun `a device behind is behind for a rotation too`() = runTest {
        listed("c", circleVersion = 4, held = 3)
        gate.reKeyPending = { true }

        assertFalse(gate.holdsCurrentVersion("c"))
        assertEquals(setOf("c"), gate.behind.value)
    }

    @Test
    fun `the personal key and an unlisted circle are not judged`() = runTest {
        coEvery { circleDao.getById("unlisted") } returns null
        every { cryptoManager.isBehind("unlisted") } returns false

        assertTrue(gate.holdsCurrentKey(LocationRepository.SELF_CIRCLE_ID))
        assertTrue(gate.holdsCurrentKey(LocationRepository.SELF_CIRCLE_UUID))
        assertTrue(gate.holdsCurrentKey("unlisted"))
        verify(exactly = 0) { cryptoManager.isBehind(LocationRepository.SELF_CIRCLE_ID) }
        verify(exactly = 0) { cryptoManager.isBehind(LocationRepository.SELF_CIRCLE_UUID) }
    }

    @Test
    fun `a listing judges every circle at once`() = runTest {
        every { cryptoManager.getActiveVersion("a") } returns 2
        every { cryptoManager.getActiveVersion("b") } returns 5
        every { cryptoManager.getActiveVersion("locked") } throws MobileException.KeyNotFound("no key")
        every { cryptoManager.newestHeldVersion("locked") } returns null
        every { cryptoManager.isBehind(any()) } returns false

        gate.noteListed(listOf(circle("a", 3), circle("b", 5), circle("locked", 1)))
        assertEquals(setOf("a"), gate.behind.value)

        // This device's own rotation of "a" landed.
        gate.noteCurrent("a")
        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `sign-out forgets the marks`() = runTest {
        listed("c", circleVersion = 4, held = 3)
        gate.holdsCurrentKey("c")

        gate.clear()

        assertEquals(emptySet<String>(), gate.behind.value)
    }

    @Test
    fun `an active SOS of this user's keeps its location going to a circle it is behind on`() = runTest {
        // As on iPhone and the web.
        listed("c", circleVersion = 4, held = 3)
        coEvery { sosAlertDao.countActiveRaisedBy("me", "c") } returns 1

        assertTrue(gate.mayStreamLocationTo("c"))
        // Nothing else is let through by it.
        assertFalse(gate.holdsCurrentKey("c"))
    }

    @Test
    fun `without an active SOS a behind circle gets no location`() = runTest {
        listed("c", circleVersion = 4, held = 3)
        coEvery { sosAlertDao.countActiveRaisedBy("me", "c") } returns 0

        assertFalse(gate.mayStreamLocationTo("c"))
    }

    // ---- behind by this device's own records (the SDK's isBehind) ----------

    @Test
    fun `its records saying behind, nothing is sealed whatever version the list shows - and the row isn't locked, a rotation goes on`() = runTest {
        // A server that spoils v3's record (this device waits at v3) and lists the circle at v2.
        listed("c", circleVersion = 2, held = 2)
        every { cryptoManager.isBehind("c") } returns true
        gate.catchUp = { false }

        assertFalse(gate.holdsCurrentKey("c"))
        assertThrows(KeyNotCurrentException::class.java) { kotlinx.coroutines.runBlocking { gate.requireCurrentKey("c") } }
        assertEquals(setOf("c"), gate.recordsBehind.value)
        assertEquals("not locked: behind is about the key", emptySet<String>(), gate.behind.value)
        // The SDK's builder is a rotation's gate, and a repair ends a pass-over.
        assertTrue(gate.holdsCurrentVersion("c"))

        every { cryptoManager.isBehind("c") } returns false
        assertTrue(gate.holdsCurrentKey("c"))
        assertEquals(emptySet<String>(), gate.recordsBehind.value)
    }

    @Test
    fun `its records saying behind, an active SOS of this user's still streams, and nothing else`() = runTest {
        listed("c", circleVersion = 2, held = 2)
        every { cryptoManager.isBehind("c") } returns true
        coEvery { sosAlertDao.countActiveRaisedBy("me", "c") } returns 1

        assertTrue(gate.mayStreamLocationTo("c"))
        assertFalse(gate.holdsCurrentKey("c"))

        coEvery { sosAlertDao.countActiveRaisedBy("me", "c") } returns 0
        assertFalse(gate.mayStreamLocationTo("c"))
    }

    @Test
    fun `records that can't be read are behind`() = runTest {
        listed("c", circleVersion = 2, held = 2)
        every { cryptoManager.isBehind("c") } throws RuntimeException("the SDK couldn't answer")

        assertFalse(gate.holdsCurrentKey("c"))
        assertEquals(setOf("c"), gate.recordsBehind.value)
    }

    @Test
    fun `a listing, a records read and its own rotation read the records again, and sign-out forgets them`() = runTest {
        var behindA = true
        var behindB = false
        every { cryptoManager.getActiveVersion("a") } returns 3
        every { cryptoManager.getActiveVersion("b") } returns 5
        every { cryptoManager.isBehind("a") } answers { behindA }
        every { cryptoManager.isBehind("b") } answers { behindB }

        gate.noteListed(listOf(circle("a", 3), circle("b", 5)))
        assertEquals(setOf("a"), gate.recordsBehind.value)
        assertEquals(emptySet<String>(), gate.behind.value)

        behindA = false
        gate.noteRecords(listOf("a", "b"))
        assertEquals(emptySet<String>(), gate.recordsBehind.value)

        behindB = true
        gate.noteCurrent("b")
        assertEquals(setOf("b"), gate.recordsBehind.value)

        gate.clear()
        assertEquals(emptySet<String>(), gate.recordsBehind.value)
    }
}
