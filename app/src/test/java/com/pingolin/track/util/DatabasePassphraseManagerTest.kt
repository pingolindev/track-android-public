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
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.domain.repository.isSecureStorageFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The database key is never tied to the screen lock. With setUnlockedDeviceRequired, a phone with
 * no secure lock can't make it (sign-in fails after the server has accepted it), and removing the
 * lock deletes it, and every way to open the database with it. A passphrase still under an old key
 * of that kind moves to the new one at the next unlocked open without losing it; a key that is gone
 * makes the database again, says so once, and never while the phone is locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DatabasePassphraseManagerTest {

    /** Software AES keys standing in for the Keystore's. */
    private class FakeVault : DbKeyVault {
        val keys = mutableMapOf<String, SecretKey>()
        val created = mutableListOf<String>()
        /** Thrown by [create]. */
        var refuse: Exception? = null
        /** After a create, [key] for that alias answers another key (a store that didn't keep it). */
        var answerOtherKeyAfterCreate = false
        /** [key] for this alias throws, as Android does for a permanently invalidated key. */
        var invalidated: String? = null
        /** [delete] for this alias throws. */
        var deleteFails: String? = null
        /** [key] for an alias here throws its exception, until the alias is deleted. */
        val refusals = mutableMapOf<String, Exception>()

        override fun key(alias: String): SecretKey? {
            if (alias == invalidated) throw KeyPermanentlyInvalidatedException()
            refusals[alias]?.let { throw it }
            return keys[alias]
        }

        override fun create(alias: String): SecretKey {
            refuse?.let { throw it }
            created += alias
            val key = aes()
            keys[alias] = if (answerOtherKeyAfterCreate) aes() else key
            return key
        }

        override fun delete(alias: String) {
            if (alias == deleteFails) throw java.security.KeyStoreException("delete failed")
            keys.remove(alias)
            refusals.remove(alias)
            if (alias == invalidated) invalidated = null
        }

        fun aes(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    private class FakeLock(override var isLocked: Boolean = false) : ScreenLock

    /** The boot and the time since it, moved by the tests. */
    private class FakeClock(override var bootCount: Int? = 7, override var elapsedMs: Long = 3_600_000L) : BootClock

    /** The real preferences, except that a commit putting [failAlias] as the key's name fails (or throws). */
    private class FailingPrefs(private val real: android.content.SharedPreferences) : android.content.SharedPreferences by real {
        var failAlias: String? = null
        var throwInstead = false

        override fun edit(): android.content.SharedPreferences.Editor = Editor(real.edit())

        inner class Editor(private val e: android.content.SharedPreferences.Editor) : android.content.SharedPreferences.Editor by e {
            private var alias: String? = null
            override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                if (key == DatabasePassphraseManager.PREFS_KEY_ALIAS) alias = value
                e.putString(key, value)
                return this
            }
            override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor { e.putLong(key, value); return this }
            override fun remove(key: String?): android.content.SharedPreferences.Editor { e.remove(key); return this }
            override fun commit(): Boolean {
                if (alias != null && alias == failAlias) {
                    if (throwInstead) throw SecurityException("commit failed")
                    return false
                }
                return e.commit()
            }
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("db-passphrase-test", Context.MODE_PRIVATE) }
    private val vault = FakeVault()
    private val lock = FakeLock()
    private val clock = FakeClock()
    private val database = File(File(context.applicationInfo.dataDir, "databases"), "tracks_database")

    private fun manager() = DatabasePassphraseManager(context, { prefs }, vault, lock, clock)

    private fun alias() = prefs.getString(DatabasePassphraseManager.PREFS_KEY_ALIAS, null)

    /** The first open: a passphrase made, and the database written with it. */
    private fun firstOpen(): ByteArray = manager().getPassphrase().also { database.writeText("the database") }

    /**
     * A phone from an earlier build: the passphrase sealed under the old key, the seal naming no
     * key, and the database written with it.
     */
    private fun oldPhone(): ByteArray {
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = vault.aes().also { vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS] = it }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val sealed = cipher.doFinal(passphrase)
        prefs.edit()
            .putString(DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP))
            .putString(DatabasePassphraseManager.PREFS_KEY_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .commit()
        database.writeText("the database")
        return passphrase
    }

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        database.parentFile!!.mkdirs()
        database.delete()
    }

    @Test
    fun `a new passphrase is sealed under the key not tied to the screen lock`() {
        val passphrase = manager().getPassphrase()
        assertEquals(32, passphrase.size)
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertEquals(listOf(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK), vault.created)
        assertNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        // The next start opens the same passphrase.
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertFalse("a first open with no seal is no rebuild: no note", manager().takeRebuiltNotice())
    }

    @Test
    fun `nothing here ties the key to the screen lock`() {
        // The Keystore spec is AndroidDbKeyVault.create, which a software vault can't run.
        val code = File("src/main/java/com/pingolin/track/util/DatabasePassphraseManager.kt").readLines()
            .filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }
            .joinToString("\n")
        assertFalse(code.contains("setUnlockedDeviceRequired"))
        assertFalse(code.contains("setUserAuthentication"))
    }

    @Test
    fun `a passphrase under the old key moves to the new one at the next unlocked open - same passphrase, same database`() {
        val passphrase = oldPhone()
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertNull("the old key goes once the new seal is stored", vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        assertTrue(database.exists())
        // Off the old key for good: a later start opens it with the new one.
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertFalse(manager().takeRebuiltNotice())
    }

    @Test
    fun `not while the phone is locked - then when the person is back`() {
        val passphrase = oldPhone()
        lock.isLocked = true
        val m = manager()
        assertArrayEquals(passphrase, m.getPassphrase())
        assertNull("still under the old key", alias())
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        m.moveKeyIfDue()
        assertNull(alias())
        lock.isLocked = false
        m.moveKeyIfDue()
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertArrayEquals(passphrase, manager().getPassphrase())
    }

    @Test
    fun `a move that fails keeps the old seal and the old key, and leaves no new key`() {
        val passphrase = oldPhone()
        vault.refuse = java.security.ProviderException("Keystore key generation failed")
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertNull(alias())
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        assertNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK))

        // A new seal stored that doesn't open from the store: the old one is put back.
        vault.refuse = null
        vault.answerOtherKeyAfterCreate = true
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS, alias())
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        assertNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK))

        // And the next start moves it.
        vault.answerOtherKeyAfterCreate = false
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertTrue(database.exists())
        assertFalse(manager().takeRebuiltNotice())
    }

    @Test
    fun `a seal already moved - an old key left by a move that stopped is removed`() {
        val passphrase = firstOpen()
        vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS] = vault.aes()
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK))
    }

    @Test
    fun `the old key gone (the lock removed before the update) makes the database again, says so once, and opening makes no key`() {
        val passphrase = oldPhone()
        // The lock was removed: Android deleted the old key.
        vault.delete(DatabasePassphraseManager.KEYSTORE_ALIAS)
        val m = manager()
        val again = m.getPassphrase()
        assertFalse(passphrase.contentEquals(again))
        assertFalse("the old database can't be opened: made again", database.exists())
        assertEquals("only the new key is made", listOf(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK), vault.created)
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertTrue(m.takeRebuiltNotice())
        assertFalse("once", m.takeRebuiltNotice())
        assertArrayEquals(again, manager().getPassphrase())
    }

    @Test
    fun `a permanently invalidated key is gone too`() {
        firstOpen()
        vault.invalidated = DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK
        val m = manager()
        m.getPassphrase()
        assertFalse(database.exists())
        assertTrue(m.takeRebuiltNotice())
    }

    @Test
    fun `a key missing while the phone is locked waits for the unlock and deletes nothing`() {
        oldPhone()
        vault.delete(DatabasePassphraseManager.KEYSTORE_ALIAS)
        lock.isLocked = true
        try {
            manager().getPassphrase()
            fail("decided while locked")
        } catch (e: DeviceLockedException) {
            // waits
        }
        assertTrue(database.exists())
        assertNotNull(prefs.getString(DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, null))
        assertTrue("nothing made", vault.created.isEmpty())
        assertFalse(manager().takeRebuiltNotice())
    }

    @Test
    fun `a refusal says what it is - locked waits, anything else is this phone's secure storage`() {
        vault.refuse = java.security.ProviderException("Keystore key generation failed")
        try {
            manager().getPassphrase()
            fail("no key, yet a passphrase")
        } catch (e: SecureStorageUnavailableException) {
            // said as such
        }
        vault.refuse = IllegalStateException("Keystore operation failed: device is locked")
        try {
            manager().getPassphrase()
            fail("no key, yet a passphrase")
        } catch (e: DeviceLockedException) {
            // waits
        }
        assertNull("nothing stored", prefs.getString(DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, null))
    }

    private val minute = 60_000L

    /** The alias the stored seal names now. */
    private fun current() = alias()!!

    /** The database was made again, and the person is told once. */
    private fun assertMadeAgain(m: DatabasePassphraseManager, before: ByteArray, after: ByteArray) {
        assertFalse("a new passphrase", before.contentEquals(after))
        assertFalse("the old database is gone", database.exists())
        assertTrue("said", m.takeRebuiltNotice())
        assertFalse("once", m.takeRebuiltNotice())
    }

    /** Nothing was deleted: the database and its seal are as they were, and nothing is said. */
    private fun assertKept(sealed: String?) {
        assertTrue(database.exists())
        assertEquals(sealed, prefs.getString(DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, null))
        assertFalse(manager().takeRebuiltNotice())
    }

    private fun refused(m: DatabasePassphraseManager = manager()) {
        try {
            m.getPassphrase()
            fail("a refusal that may pass, yet a passphrase")
        } catch (e: DatabaseKeyRefusedException) {
            // the open fails; nothing is deleted
        }
    }

    private fun sealed() = prefs.getString(DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, null)

    @Test
    fun `a seal that will never open makes the database again and says so - unrecoverable, damaged, or not this key's`() {
        // The key can't be recovered.
        var passphrase = firstOpen()
        vault.refusals[current()] = java.security.UnrecoverableKeyException("unrecoverable")
        var m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())

        // The seal is damaged where it is kept.
        passphrase = firstOpen().also { prefs.edit().putString(DatabasePassphraseManager.PREFS_KEY_IV, "not base64 !").commit() }
        m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())

        // The key is there, but the seal doesn't open with it.
        passphrase = firstOpen().also { vault.keys[current()] = vault.aes() }
        m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `a refusal that may pass deletes nothing - the open fails, the next one tries again`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        val m = manager()
        refused(m)
        refused(m)
        assertKept(sealed)
        // It passes: the same database opens.
        vault.refusals.clear()
        assertArrayEquals(passphrase, m.getPassphrase())
        assertKept(sealed)
    }

    @Test
    fun `the same refusal 15 minutes after its first makes the database again - at 14-59 it still waits`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        clock.elapsedMs += 15 * minute - 1
        refused()
        assertKept(sealed)
        clock.elapsedMs += 1
        val m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `starts seconds apart never make the database again`() {
        firstOpen()
        val sealed = sealed()
        vault.refusals[current()] = java.security.ProviderException("keystore2 restarting")
        repeat(5) {
            refused()
            clock.elapsedMs += 3_000L
        }
        assertKept(sealed)
    }

    @Test
    fun `the same refusal after a reboot makes the database again`() {
        val passphrase = firstOpen()
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        clock.bootCount = clock.bootCount!! + 1
        clock.elapsedMs = 20_000L
        val m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `with the boot count unreadable, time since boot going back is a reboot`() {
        val passphrase = firstOpen()
        clock.bootCount = null
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        clock.elapsedMs = 20_000L
        val m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `the same kind again doesn't move its first`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        clock.elapsedMs += 10 * minute
        refused()
        assertKept(sealed)
        clock.elapsedMs += 5 * minute
        val m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `another kind of refusal starts again`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        clock.elapsedMs += 10 * minute
        vault.refusals[current()] = java.security.ProviderException("keystore2 down")
        refused()
        clock.elapsedMs += 10 * minute
        refused()
        assertKept(sealed)
        clock.elapsedMs += 5 * minute
        val m = manager()
        assertMadeAgain(m, passphrase, m.getPassphrase())
    }

    @Test
    fun `an open that works between two refusals clears the count`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        val alias = current()
        vault.refusals[alias] = java.security.KeyStoreException("busy")
        refused()
        vault.refusals.clear()
        clock.elapsedMs += 5 * minute
        assertArrayEquals(passphrase, manager().getPassphrase())
        vault.refusals[alias] = java.security.KeyStoreException("busy")
        clock.elapsedMs += 11 * minute
        refused()
        assertKept(sealed)
    }

    @Test
    fun `nothing is decided while the phone is locked - a refusal then isn't counted`() {
        val passphrase = firstOpen()
        val sealed = sealed()
        lock.isLocked = true
        for (refusal in listOf<Exception>(java.security.KeyStoreException("busy"), java.security.UnrecoverableKeyException("x"))) {
            vault.refusals[current()] = refusal
            try {
                manager().getPassphrase()
                fail("decided while locked")
            } catch (e: DeviceLockedException) {
                // waits
            }
            assertKept(sealed)
            clock.elapsedMs += 20 * minute
        }
        // Unlocked: the first refusal counted is this one.
        lock.isLocked = false
        vault.refusals[current()] = java.security.KeyStoreException("busy")
        refused()
        assertKept(sealed)
        clock.elapsedMs += 15 * minute
        val next = manager()
        assertMadeAgain(next, passphrase, next.getPassphrase())
    }

    @Test
    fun `a making-again whose new key can't be made deletes nothing`() {
        firstOpen()
        val sealed = sealed()
        val old = current()
        // The key is gone, and the Keystore won't make another.
        vault.delete(old)
        vault.refuse = java.security.ProviderException("Keystore key generation failed")
        try {
            manager().getPassphrase()
            fail("made again with no key")
        } catch (e: DatabaseKeyRefusedException) {
            // the plain line; nothing deleted
        }
        assertKept(sealed)
        assertEquals(old, current())
        assertTrue("no new key left behind", vault.keys.isEmpty())
    }

    @Test
    fun `the new key is made under the other alias before the old one goes`() {
        val passphrase = firstOpen()
        val old = current()
        vault.invalidated = old
        val m = manager()
        val again = m.getPassphrase()
        assertMadeAgain(m, passphrase, again)
        val other = if (old == DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK) DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK_B else DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK
        assertEquals(other, current())
        assertEquals(setOf(other), vault.keys.keys)
        assertArrayEquals(again, manager().getPassphrase())
    }

    @Test
    fun `a making-again that stopped after storing its seal - the old database goes before the next open`() {
        val passphrase = firstOpen()
        // As a making-again leaves it when it stops between its one commit and the delete: the
        // new seal stored, the old database still on disk.
        prefs.edit().putBoolean(DatabasePassphraseManager.PREFS_KEY_STALE_DATABASE, true).commit()
        assertTrue(database.exists())
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertFalse("the old database went first", database.exists())
        assertFalse(prefs.contains(DatabasePassphraseManager.PREFS_KEY_STALE_DATABASE))
    }

    @Test
    fun `every making-again says so, in the same commit as its new seal`() {
        val src = File("src/main/java/com/pingolin/track/util/DatabasePassphraseManager.kt").readText()
        val open = src.substringAfter("private fun loadOrGeneratePassphrase(").substringBefore("\n    }\n")
        assertEquals("one making-again", 1, Regex("""rebuild\(""").findAll(open).count())
        val rebuild = src.substringAfter("private fun rebuild(").substringBefore("\n    }\n")
        val commit = rebuild.substringAfter("stored = prefs.edit()").substringBefore(".commit()")
        for (part in listOf("PREFS_KEY_PASSPHRASE", "PREFS_KEY_ALIAS, newAlias", "PREFS_KEY_STALE_DATABASE, true", "PREFS_KEY_REBUILT_AT")) {
            assertTrue(part, commit.contains(part))
        }
        assertTrue("deletes only after the commit", rebuild.indexOf("dropStaleDatabase()") > rebuild.indexOf("check(stored)"))
    }

    @Test
    fun `a refusal says so in plain words, and isn't a wrong password`() {
        val e = DatabaseKeyRefusedException(java.security.KeyStoreException("busy"))
        assertEquals("Track couldn't open what it had saved on this phone. Please try again.", e.plainText("fallback"))
        for (word in listOf("key", "keystore", "passphrase")) {
            assertFalse("no key talk: $word", e.plainText("fallback").lowercase().contains(word))
        }
        assertTrue("never counted as a failed sign-in", e.isSecureStorageFailure())
        // Whatever refused under it: Android's own NullPointerException with keystore2 down,
        // or a new seal that didn't check out, is no Keystore type the sign-in check knows.
        for (cause in listOf(NullPointerException("IKeystoreService"), IllegalStateException("the new seal doesn't open"))) {
            assertTrue("$cause: never a wrong password", DatabaseKeyRefusedException(cause).isSecureStorageFailure())
        }
    }

    @Test
    fun `an old phone with a stray new key - the stray is replaced, never sealed under`() {
        val passphrase = oldPhone()
        val stray = vault.aes().also { vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK] = it }
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertEquals(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertEquals(listOf(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK), vault.created)
        assertFalse(stray === vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK])
        assertArrayEquals(passphrase, manager().getPassphrase())
    }

    @Test
    fun `a restore that doesn't commit keeps the new key, which the store still names`() {
        val passphrase = oldPhone()
        val failing = FailingPrefs(prefs).apply { failAlias = DatabasePassphraseManager.KEYSTORE_ALIAS }
        vault.answerOtherKeyAfterCreate = true
        val m = DatabasePassphraseManager(context, { failing }, vault, lock)
        assertArrayEquals("the open still has its passphrase", passphrase, m.getPassphrase())
        assertEquals("the restore didn't commit: the store names the new key", DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertNotNull("so the new key stays", vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK))
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
    }

    @Test
    fun `an old key that won't delete leaves the move done, and goes at the next open`() {
        val passphrase = oldPhone()
        vault.deleteFails = DatabasePassphraseManager.KEYSTORE_ALIAS
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertEquals("moved, not undone", DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK, alias())
        assertNotNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
        vault.deleteFails = null
        assertArrayEquals(passphrase, manager().getPassphrase())
        assertNull(vault.key(DatabasePassphraseManager.KEYSTORE_ALIAS))
    }

    @Test
    fun `a move that throws never fails an open that has its passphrase`() {
        val passphrase = oldPhone()
        val failing = FailingPrefs(prefs).apply { failAlias = DatabasePassphraseManager.KEYSTORE_ALIAS; throwInstead = true }
        vault.answerOtherKeyAfterCreate = true
        assertArrayEquals(passphrase, DatabasePassphraseManager(context, { failing }, vault, lock).getPassphrase())
    }

    @Test
    fun `the move is the same on every API level`() {
        // An old key has the screen-lock setting only from API 28; every phone is
        // moved, so one kind of key is left.
        val src = File("src/main/java/com/pingolin/track/util/DatabasePassphraseManager.kt").readText()
        val move = src.substringAfter("private fun moveIfDue(").substringBefore("\n    }\n")
        assertFalse(move.contains("SDK_INT"))
    }

    @Test
    fun `the note's words, word for word`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val note = strings.substringAfter("<string name=\"database_rebuilt_note\">").substringBefore("</string>").replace("\\'", "'")
        assertEquals(
            "Track couldn't open what it had saved on this phone, so it started over. Any locations it hadn't sent yet " +
                "were lost, and so were any changes marked “Waiting to sync”. Some earlier entries may also be missing " +
                "from Notifications. Your circles, places and alert settings come back from your account.",
            note,
        )
    }

    @Test
    fun `the app moves the key when the person is back, and says a rebuilt database once`() {
        val activity = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val onResume = activity.substring(activity.indexOf("override fun onResume()"))
        assertTrue(onResume.contains("databasePassphraseManager.moveKeyIfDue()"))
        assertTrue(onResume.contains("databasePassphraseManager.takeRebuiltNotice()"))
        assertTrue(onResume.contains("if (rebuilt) showDatabaseRebuiltNote()"))
        assertTrue(activity.contains(".setMessage(R.string.database_rebuilt_note)"))
    }

    @Test
    fun `its own store refusing is never a crash - no note to say, and sign-out still removes the keys`() {
        manager().getPassphrase()
        vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK_B] = vault.aes()
        val refusing = DatabasePassphraseManager(context, { throw SecurityException("Cannot initialize secure storage") }, vault, lock, clock)
        assertFalse(refusing.takeRebuiltNotice())
        refusing.clearPassphrase()
        assertTrue("the keys still go", vault.keys.isEmpty())
    }

    @Test
    fun `sign-out removes both keys, the seal, and the note`() {
        val m = manager()
        m.getPassphrase()
        vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS] = vault.aes()
        prefs.edit().putLong(DatabasePassphraseManager.PREFS_KEY_REBUILT_AT, 1L).commit()
        prefs.edit().putString(DatabasePassphraseManager.PREFS_KEY_REFUSED, "java.security.KeyStoreException|7|1000").commit()
        prefs.edit().putBoolean(DatabasePassphraseManager.PREFS_KEY_STALE_DATABASE, true).commit()
        vault.keys[DatabasePassphraseManager.KEYSTORE_ALIAS_NO_LOCK_B] = vault.aes()
        m.clearPassphrase()
        assertTrue(vault.keys.isEmpty())
        for (key in listOf(
            DatabasePassphraseManager.PREFS_KEY_PASSPHRASE, DatabasePassphraseManager.PREFS_KEY_IV,
            DatabasePassphraseManager.PREFS_KEY_ALIAS, DatabasePassphraseManager.PREFS_KEY_REBUILT_AT,
            DatabasePassphraseManager.PREFS_KEY_REFUSED, DatabasePassphraseManager.PREFS_KEY_STALE_DATABASE,
        )) {
            assertFalse(key, prefs.contains(key))
        }
    }
}
