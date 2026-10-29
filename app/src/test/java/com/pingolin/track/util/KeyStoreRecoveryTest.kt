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

import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.KeyStoreLoad
import uniffi.pingolin_mobile.KeyStoreStatus
import uniffi.pingolin_mobile.MobileException
import java.io.File

/**
 * The key store's recovery, as on iOS: nobody is signed out while the app works; a store that
 * doesn't decode ends the session and is cleared at the sign-in; a seed or entries that didn't
 * read raise the sync error's banner and are dropped or replaced at the sign-in; a store the
 * platform couldn't read is loaded again and never cleared; a wipe the platform refused is owed,
 * and paid before anything is loaded.
 */
class KeyStoreRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The SDK as far as recovery sees it: a status that loads, clears and drops change. */
    private var status = KeyStoreStatus(KeyStoreLoad.LOADED, signingSeedUnreadable = false, entriesUnreadable = false)
    /** What a load finds; what a clear leaves (deleteFailed when the platform refuses the delete). */
    private var loadFinds = KeyStoreLoad.LOADED
    private var clearLeaves = KeyStoreLoad.LOADED
    private val cryptoManager = mockk<CryptoManager>().also { sdk ->
        every { sdk.keyStoreStatus() } answers { status }
        every { sdk.loadPersistedKeys() } answers {
            status = status.copy(load = loadFinds)
            if (loadFinds == KeyStoreLoad.READ_FAILED || loadFinds == KeyStoreLoad.DOES_NOT_DECODE) throw MobileException.StorageException("load")
            true
        }
        every { sdk.clearAllKeys() } answers { status = KeyStoreStatus(clearLeaves, signingSeedUnreadable = false, entriesUnreadable = false) }
        every { sdk.dropUnreadableEntries() } answers { status = status.copy(entriesUnreadable = false); 2u }
    }

    private val owedWipe = OwedWipe(mockk()).also { it.mark = { File(tmp.root, "owed") } }
    private val notice = InMemoryPrefs()
    private val recovery = KeyStoreRecovery(cryptoManager, owedWipe, mockk()).also { it.noticePrefs = { notice } }

    private fun status(load: KeyStoreLoad, seed: Boolean = false, entries: Boolean = false) = KeyStoreStatus(load, seed, entries)

    @Test
    fun `the verdict for every state - sign out only when nothing works, a banner when it partly does`() {
        val v = KeyStoreRecovery.Companion::verdict
        assertEquals(KeyStoreRecovery.Verdict.SIGN_OUT, v(status(KeyStoreLoad.DOES_NOT_DECODE)))
        assertEquals(KeyStoreRecovery.Verdict.CARRY_ON, v(status(KeyStoreLoad.LOADED)))
        assertEquals(KeyStoreRecovery.Verdict.CARRY_ON, v(status(KeyStoreLoad.NOTHING_STORED)))
        assertEquals(KeyStoreRecovery.Verdict.BANNER, v(status(KeyStoreLoad.LOADED, seed = true)))
        assertEquals(KeyStoreRecovery.Verdict.BANNER, v(status(KeyStoreLoad.LOADED, entries = true)))
        assertEquals(KeyStoreRecovery.Verdict.BANNER, v(status(KeyStoreLoad.NOTHING_STORED, seed = true)))
        for (load in listOf(KeyStoreLoad.NOT_LOADED, KeyStoreLoad.READ_FAILED, KeyStoreLoad.DELETE_FAILED)) {
            assertEquals(load.name, KeyStoreRecovery.Verdict.CARRY_ON, v(status(load, seed = true, entries = true)))
        }
    }

    @Test
    fun `a status that can't be read is never a sign-out or a banner, and the sign-in stops on it`() {
        every { cryptoManager.keyStoreStatus() } throws IllegalStateException("no answer")

        assertEquals(KeyStoreRecovery.Verdict.CARRY_ON, recovery.check())
        assertFalse(recovery.syncBanner.value)
        assertEquals("no answer is never a store that can be imported over", KeyStoreRecovery.BeforeImport.STOP, recovery.beforeSignInImport())
    }

    @Test
    fun `the pins are exported only from a store that loaded`() {
        assertTrue(KeyStoreRecovery.pinsExportable(status(KeyStoreLoad.LOADED, entries = true)))
        assertTrue(KeyStoreRecovery.pinsExportable(status(KeyStoreLoad.NOTHING_STORED)))
        for (load in listOf(KeyStoreLoad.NOT_LOADED, KeyStoreLoad.READ_FAILED, KeyStoreLoad.DOES_NOT_DECODE, KeyStoreLoad.DELETE_FAILED)) {
            assertFalse(load.name, KeyStoreRecovery.pinsExportable(status(load)))
        }
        assertFalse("no answer is not a store that loaded", KeyStoreRecovery.pinsExportable(null))
    }

    @Test
    fun `the banner is up while a seed or entries didn't read, and down once they're gone or the account signed out`() {
        status = status(KeyStoreLoad.LOADED, entries = true)
        assertEquals(KeyStoreRecovery.Verdict.BANNER, recovery.check())
        assertTrue(recovery.syncBanner.value)

        recovery.signedOut()
        assertFalse(recovery.syncBanner.value)

        status = status(KeyStoreLoad.LOADED, seed = true)
        recovery.check()
        assertTrue(recovery.syncBanner.value)
        status = status(KeyStoreLoad.LOADED)
        recovery.signedIn()
        assertFalse(recovery.syncBanner.value)
    }

    @Test
    fun `a store that doesn't decode ends the session, and the sign-in screen says so until the next sign-in`() {
        val sessionManager = mockk<SessionManager>(relaxed = true)
        status = status(KeyStoreLoad.DOES_NOT_DECODE)
        assertEquals(KeyStoreRecovery.Verdict.SIGN_OUT, recovery.check())

        recovery.signOut(sessionManager)

        verify { sessionManager.triggerForceLogout(TrackingStopReason.KEY_STORE_UNREADABLE, any()) }
        assertTrue(recovery.signInNoticeDue())
        assertTrue("a fresh process still shows it", KeyStoreRecovery(cryptoManager, owedWipe, mockk()).also { it.noticePrefs = { notice } }.signInNoticeDue())
        verify(exactly = 0) { cryptoManager.clearAllKeys() }

        recovery.signedIn()
        assertFalse(recovery.signInNoticeDue())
    }

    @Test
    fun `a store the platform couldn't read is loaded again at a refresh, and never cleared`() {
        status = status(KeyStoreLoad.READ_FAILED)
        loadFinds = KeyStoreLoad.READ_FAILED
        assertEquals(KeyStoreRecovery.Verdict.CARRY_ON, recovery.retryIfReadFailed())
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }

        loadFinds = KeyStoreLoad.LOADED
        assertEquals(KeyStoreRecovery.Verdict.CARRY_ON, recovery.retryIfReadFailed())
        assertEquals(KeyStoreLoad.LOADED, status.load)
        verify(exactly = 0) { cryptoManager.clearAllKeys() }

        // Loaded: nothing is loaded again (only readFailed is).
        recovery.retryIfReadFailed()
        verify(exactly = 2) { cryptoManager.loadPersistedKeys() }
    }

    @Test
    fun `a retried load pays a wipe still owed first, and loads nothing while it stays owed`() {
        File(tmp.root, "owed").createNewFile()
        status = status(KeyStoreLoad.READ_FAILED)
        clearLeaves = KeyStoreLoad.DELETE_FAILED
        recovery.retryIfReadFailed()
        verify(exactly = 1) { cryptoManager.clearAllKeys() }
        verify(exactly = 0) { cryptoManager.loadPersistedKeys() }
        assertTrue(owedWipe.isOwed())

        status = status(KeyStoreLoad.READ_FAILED)
        clearLeaves = KeyStoreLoad.LOADED
        recovery.retryIfReadFailed()
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }
        assertFalse(owedWipe.isOwed())
    }

    @Test
    fun `the sign-in - a store that doesn't decode is cleared, so the import writes it whole`() {
        status = status(KeyStoreLoad.DOES_NOT_DECODE)
        loadFinds = KeyStoreLoad.DOES_NOT_DECODE
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }
        verify(exactly = 1) { cryptoManager.clearAllKeys() }
        assertEquals(KeyStoreLoad.LOADED, status.load)
    }

    @Test
    fun `the sign-in - a store the platform couldn't read is never cleared, and the sign-in stops`() {
        status = status(KeyStoreLoad.READ_FAILED)
        loadFinds = KeyStoreLoad.READ_FAILED
        assertEquals(KeyStoreRecovery.BeforeImport.STOP, recovery.beforeSignInImport())
        verify(exactly = 0) { cryptoManager.clearAllKeys() }

        // Read again at the next sign-in, it goes on.
        loadFinds = KeyStoreLoad.LOADED
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
    }

    @Test
    fun `the sign-in - entries that didn't read go before the key sync, and a failed drop keeps them and the banner`() {
        status = status(KeyStoreLoad.LOADED, entries = true)
        loadFinds = KeyStoreLoad.LOADED
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
        verify(exactly = 1) { cryptoManager.dropUnreadableEntries() }
        assertFalse(status.entriesUnreadable)
        verify(exactly = 0) { cryptoManager.clearAllKeys() }

        every { cryptoManager.dropUnreadableEntries() } throws MobileException.StorageException("write")
        status = status(KeyStoreLoad.LOADED, entries = true)
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
        recovery.signedIn()
        assertTrue("kept as they were: the banner stays", recovery.syncBanner.value)
    }

    @Test
    fun `the sign-in pays a wipe owed first - paid, it loads, and still owed, the keys stay in memory and nothing loads`() {
        File(tmp.root, "owed").createNewFile()
        clearLeaves = KeyStoreLoad.DELETE_FAILED
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
        verify(exactly = 1) { cryptoManager.clearAllKeys() }
        verify(exactly = 0) { cryptoManager.loadPersistedKeys() }
        assertTrue(owedWipe.isOwed())

        clearLeaves = KeyStoreLoad.LOADED
        assertEquals(KeyStoreRecovery.BeforeImport.IMPORT_KEYS, recovery.beforeSignInImport())
        verify(exactly = 2) { cryptoManager.clearAllKeys() }
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }
        assertFalse(owedWipe.isOwed())
    }

    @Test
    fun `every clear goes through the owed wipe - a refused delete is owed, a clear that loads pays it`() {
        clearLeaves = KeyStoreLoad.DELETE_FAILED
        assertFalse(recovery.clearKeys())
        assertTrue(owedWipe.isOwed())
        assertTrue("still owed in a fresh process", OwedWipe(mockk()).also { it.mark = { File(tmp.root, "owed") } }.isOwed())

        clearLeaves = KeyStoreLoad.LOADED
        assertTrue(recovery.clearKeys())
        assertFalse(owedWipe.isOwed())
    }

    @Test
    fun `the launch pays a wipe owed before its load, and loads nothing while it stays owed`() {
        assertTrue(KeyStoreRecovery.loadAtLaunch(cryptoManager, owedWipe))
        verify(exactly = 0) { cryptoManager.clearAllKeys() }
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }

        File(tmp.root, "owed").createNewFile()
        clearLeaves = KeyStoreLoad.DELETE_FAILED
        assertFalse(KeyStoreRecovery.loadAtLaunch(cryptoManager, owedWipe))
        verify(exactly = 1) { cryptoManager.clearAllKeys() }
        verify(exactly = 1) { cryptoManager.loadPersistedKeys() }

        clearLeaves = KeyStoreLoad.LOADED
        assertTrue(KeyStoreRecovery.loadAtLaunch(cryptoManager, owedWipe))
        verify(exactly = 2) { cryptoManager.clearAllKeys() }
        verify(exactly = 2) { cryptoManager.loadPersistedKeys() }
        assertFalse(owedWipe.isOwed())

        loadFinds = KeyStoreLoad.READ_FAILED
        assertFalse("a load that fails says nothing but its status", KeyStoreRecovery.loadAtLaunch(cryptoManager, owedWipe))
        assertEquals(KeyStoreLoad.READ_FAILED, status.load)
    }

    @Test
    fun `a mark that can't be looked at counts as owed - no answer is never not owed`() {
        val unreadable = object : File(tmp.root, "owed") {
            override fun exists(): Boolean = throw SecurityException("no access")
        }
        val wipe = OwedWipe(mockk()).also { it.mark = { unreadable } }
        assertTrue(wipe.isOwed())
    }

    @Test
    fun `only a clear that leaves the store loaded pays - one whose outcome can't be read, or isn't loaded, is owed`() {
        every { cryptoManager.keyStoreStatus() } throws IllegalStateException("no answer")
        assertFalse(recovery.clearKeys())
        assertTrue(owedWipe.isOwed())

        every { cryptoManager.keyStoreStatus() } answers { status }
        clearLeaves = KeyStoreLoad.NOT_LOADED
        assertFalse(recovery.clearKeys())
        assertTrue(owedWipe.isOwed())

        clearLeaves = KeyStoreLoad.LOADED
        assertTrue(recovery.clearKeys())
        assertFalse(owedWipe.isOwed())
    }
}
