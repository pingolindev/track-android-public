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

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the SQLCipher database encryption passphrase using Android Keystore.
 *
 * The passphrase is:
 * 1. Generated randomly (32 bytes) on first use
 * 2. Encrypted with AES-256-GCM using a hardware-backed key from Android Keystore
 * 3. Stored in EncryptedSharedPreferences (defense in depth - double encryption)
 *
 * This ensures the database passphrase:
 * - Is unique per installation
 * - Cannot be extracted even with root access (hardware-backed key)
 * - Survives app updates (stored in SharedPreferences)
 * - Has an additional encryption layer via EncryptedSharedPreferences
 *
 * The key is not tied to the screen lock. Android refuses a key made with
 * setUnlockedDeviceRequired(true) on a phone without a secure screen lock, so sign-in would fail
 * after the server had accepted it, and removing the lock would delete the key, and with it every
 * way to open the database. A passphrase sealed under the lock-bound key of earlier versions,
 * [KEYSTORE_ALIAS], is moved to [KEYSTORE_ALIAS_NO_LOCK] at the next unlocked open, failure-safe
 * ([moveKeyIfDue]). The identity keys do not use the setting: they sit in
 * EncryptedSharedPreferences under the MasterKey (SecurePrefs).
 *
 * The database is made again only when its seal will never open: the key is gone, was
 * invalidated or can't be recovered, or the seal itself is damaged. Then it says so once. A refusal
 * that may pass deletes nothing: the open fails ([DatabaseKeyRefusedException]) and the next one
 * tries again; only the same refusal still there 15 minutes after the first, or after a reboot, the
 * phone unlocked each time, makes the database again. Nothing is decided while the phone is locked.
 * A making-again makes and checks its new key and stores its seal before anything is deleted, so a
 * Keystore that can't make one deletes nothing.
 */
@Singleton
class DatabasePassphraseManager internal constructor(
    private val context: Context,
    private val prefsProvider: () -> SharedPreferences,
    private val vault: DbKeyVault,
    private val screenLock: ScreenLock,
    private val clock: BootClock = AndroidBootClock(context),
) {
    @Inject
    constructor(context: Context, masterKey: dagger.Lazy<MasterKey>) : this(
        context,
        // The MasterKey made here, at the store's first open, inside [prefs]' catch.
        { SecurePrefs.open(context, PREFS_NAME, masterKey.get()) },
        AndroidDbKeyVault,
        KeyguardScreenLock(context),
        AndroidBootClock(context),
    )

    companion object {
        private const val TAG = "DatabasePassphraseManager"
        /** The lock-bound key that earlier versions sealed every
         * passphrase under (setUnlockedDeviceRequired on API 28+).
         */
        internal const val KEYSTORE_ALIAS = "pingolin_db_key"
        /** The key every passphrase is sealed under: not tied to the screen lock. */
        internal const val KEYSTORE_ALIAS_NO_LOCK = "pingolin_db_key_no_lock"
        /**
         * The other one: a making-again seals under whichever of the two the old seal
         * doesn't name, so the new key is made and checked while the old one still stands.
         */
        internal const val KEYSTORE_ALIAS_NO_LOCK_B = "pingolin_db_key_no_lock_b"
        internal const val PREFS_NAME = "pingolin_db_prefs_encrypted"
        internal const val PREFS_KEY_PASSPHRASE = "encrypted_passphrase"
        internal const val PREFS_KEY_IV = "passphrase_iv"
        /** Which key sealed the passphrase. Absent: [KEYSTORE_ALIAS], as for every
         * seal an earlier version made.
         */
        internal const val PREFS_KEY_ALIAS = "passphrase_key_alias"
        /** When the database was made again because its key was gone: said once. */
        internal const val PREFS_KEY_REBUILT_AT = "database_rebuilt_at"
        /**
         * A refusal that may pass, the first of its kind: "kind|boot count|elapsed ms"
         * (Settings.Global.BOOT_COUNT, SystemClock.elapsedRealtime).
         */
        internal const val PREFS_KEY_REFUSED = "database_key_refused"
        /** The same refusal this long after its first, on the same boot, is not a passing one. */
        internal const val PERSISTENT_AFTER_MS = 15 * 60_000L
        /**
         * Set with a making-again's new seal, in its one commit: the database on disk is the old
         * one, which the new passphrase can't open. Deleted, and this with it, before any open.
         */
        internal const val PREFS_KEY_STALE_DATABASE = "database_stale"
        private const val PASSPHRASE_LENGTH = 32 // 256 bits
        private const val GCM_TAG_LENGTH = 128
        private const val DATABASE_NAME = "tracks_database"

        /**
         * Securely clears a passphrase from memory by overwriting with zeros.
         * MUST be called after the passphrase has been used to prevent sensitive data
         * from lingering in memory where it could be extracted via memory dumps.
         *
         * @param passphrase The ByteArray to clear
         */
        @JvmStatic
        fun clearPassphraseBytes(passphrase: ByteArray) {
            passphrase.fill(0)
        }
    }

    /**
     * Process-lifetime cache of the decrypted database passphrase.
     *
     * TRADEOFF: Keeps the decrypted 32-byte passphrase resident in heap for the
     * lifetime of the process. The decryption costs ~100ms+ on each call (hardware
     * Keystore Cipher.doFinal), so caching avoids that cost on every app start
     * after the first DB open. Cache is cleared on logout via [clearCache].
     */
    @Volatile
    private var cachedPassphrase: ByteArray? = null


    private val prefs: SharedPreferences by lazy {
        try {
            prefsProvider()
        } catch (e: Exception) {
            // SECURITY: Do NOT fall back to unencrypted storage - this would expose
            // the database encryption key. Instead, log the error and throw.
            // This will prevent the app from starting, which is the correct behavior
            // when we cannot securely store encryption keys.
            Log.e(TAG, "CRITICAL: Failed to create EncryptedSharedPreferences", e)
            throw SecurityException(
                "Cannot initialize secure storage. The app cannot run safely on this device. " +
                "Please ensure your device's security features are enabled and try again.",
                e
            )
        }
    }

    /**
     * Gets the database passphrase, generating one if it doesn't exist.
     *
     * Handles the case where the passphrase was cleared (e.g., during logout)
     * but the database file still exists. In this case, the old database is
     * deleted to prevent a passphrase mismatch crash.
     *
     * Performance: the decrypted passphrase is cached in-memory after the first
     * call to avoid repeated hardware Keystore round-trips. Callers may safely
     * zero the returned ByteArray; a defensive copy is returned each call.
     *
     * @return The passphrase as a ByteArray for SQLCipher
     */
    @Synchronized
    fun getPassphrase(): ByteArray {
        cachedPassphrase?.let { return it.copyOf() }

        val passphrase = loadOrGeneratePassphrase()
        cachedPassphrase = passphrase.copyOf()
        return passphrase
    }

    /**
     * Moves the passphrase in use off the earlier key tied to the screen lock, when the phone is
     * unlocked. Nothing to do once it is off it, or before the database has been opened in this
     * process. The open does this too; this is for when the person is back in the app, should
     * the move at the open have failed. Never throws, and never touches the database.
     */
    @Synchronized
    fun moveKeyIfDue() {
        val passphrase = cachedPassphrase?.copyOf() ?: return
        try {
            moveIfDue(prefs.getString(PREFS_KEY_ALIAS, null) ?: KEYSTORE_ALIAS, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "Database key not moved yet: ${e.javaClass.simpleName}")
        } finally {
            passphrase.fill(0)
        }
    }

    /**
     * True, once, after the database was made again because the key that sealed its passphrase
     * was gone. Whatever only this phone held went with it.
     */
    @Synchronized
    fun takeRebuiltNotice(): Boolean = runCatching {
        // A store that can't be opened now has nothing to say, and is never a crash.
        if (!prefs.contains(PREFS_KEY_REBUILT_AT)) return@runCatching false
        prefs.edit().remove(PREFS_KEY_REBUILT_AT).commit()
        true
    }.getOrDefault(false)

    private fun isDeviceLocked(t: Throwable): Boolean {
        var cur: Throwable? = t
        while (cur != null) {
            // TIRAMISU (33), not S (31): BOTH
            // `android.security.KeyStoreException` and
            // `getNumericErrorCode` were added in 33, so a guard at S
            // would let API 31-32 through to a class that does not
            // exist there.
            //
            // The SDK_INT check is the OUTER condition: an `is`
            // against an unresolvable class is the thing that has to
            // be avoided, not just the method call inside it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (cur is android.security.KeyStoreException && cur.numericErrorCode == -72) {
                    return true
                }
            }
            val msg = cur.message
            if (msg != null && (
                    msg.contains("device is locked", ignoreCase = true) ||
                        msg.contains("Device locked", ignoreCase = false) ||
                        msg.contains("DEVICE_LOCKED")
                    )
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    /**
     * Clear the in-memory passphrase cache. Call on logout so a subsequent
     * decrypted-passphrase access goes through the Keystore again (and so
     * the cleared key material is gone before the user is signed out).
     */
    fun clearCache() {
        cachedPassphrase?.fill(0)
        cachedPassphrase = null
    }

    private fun loadOrGeneratePassphrase(): ByteArray {
        // A making-again that stops after storing its new seal leaves the old database behind.
        dropStaleDatabase()
        val encryptedPassphrase = prefs.getString(PREFS_KEY_PASSPHRASE, null)
        val iv = prefs.getString(PREFS_KEY_IV, null)

        if (encryptedPassphrase == null || iv == null) {
            // No stored passphrase - check if database file exists
            // This can happen after logout when passphrase was cleared but database wasn't
            deleteExistingDatabase()
            return generateAndStorePassphrase()
        }

        val alias = prefs.getString(PREFS_KEY_ALIAS, null) ?: KEYSTORE_ALIAS
        val passphrase = try {
            // Try to decrypt existing passphrase. Null: the key that sealed it is gone.
            openSealed(alias, encryptedPassphrase, iv)
        } catch (e: Exception) {
            // Nothing is decided while the phone is locked: an unlock may be all it
            // takes, and a refusal met then isn't counted.
            if (isDeviceLocked(e) || screenLock.isLocked) {
                Log.w(TAG, "Keystore unavailable (device locked); deferring passphrase access", e)
                throw DeviceLockedException(e)
            }
            when {
                // This seal will never open: as a key that is
                // gone, below.
                e.neverOpens() -> {
                    Log.e(TAG, "The database key can't open its seal (${e.javaClass.simpleName})", e)
                    null
                }
                // The same refusal still there 15 minutes after its first, or after a reboot, the
                // phone unlocked each time: not a moment that passes. (Notes the first when it isn't.)
                refusedLongEnough(e) -> {
                    Log.e(TAG, "The database key still refuses (${e.javaClass.simpleName})", e)
                    null
                }
                // Maybe only this moment: nothing is deleted. This open fails, the next one tries
                // again, and the same refusal 15 minutes on, or after a reboot, decides.
                else -> {
                    Log.w(TAG, "The database key refused (${e.javaClass.simpleName}); trying again later", e)
                    throw DatabaseKeyRefusedException(e)
                }
            }
        }
        if (passphrase == null) {
            // Never decided while the phone is locked: a key can't be told gone from merely
            // unreadable there, and this deletes the database. Wait for the unlock.
            if (screenLock.isLocked) {
                throw DeviceLockedException(IllegalStateException("no usable key under $alias while the phone is locked"))
            }
            // Nothing opens this passphrase again, so nothing opens the database: it is
            // made again, filled from the server, and the person is told once. The key
            // may be gone: Android deletes a key made with setUnlockedDeviceRequired
            // when the screen lock is removed (API 34: keystore2 "User ECDH key
            // missing", then no key under the alias). Or it refused for good. Opening
            // never makes a key: one made here would open nothing.
            Log.w(TAG, "The database can't be opened with its key ($alias); making the database again")
            return rebuild(alias)
        }
        // Opened: whatever refused before was a moment that passed.
        clearRefusals()
        // A move that fails never fails an open that holds a good passphrase.
        try {
            moveIfDue(alias, passphrase)
        } catch (e: Exception) {
            Log.w(TAG, "Database key not moved yet: ${e.javaClass.simpleName}")
        }
        return passphrase
    }

    /**
     * A seal that will never open with this key, whatever is tried: the key was invalidated
     * or can't be recovered, the stored seal is damaged (not Base64), or the key is there and
     * the seal doesn't open with it (not the key that made it, or a changed seal).
     */
    private fun Throwable.neverOpens(): Boolean =
        this is KeyPermanentlyInvalidatedException ||
            this is UnrecoverableKeyException ||
            this is IllegalArgumentException ||
            this is javax.crypto.AEADBadTagException

    /**
     * Whether [e]'s refusal is the same one as a first noted at least [PERSISTENT_AFTER_MS] ago on
     * this boot, or on an earlier boot, the phone unlocked each time. The first of its kind is
     * noted with the boot and the time since boot, which a change of the phone's clock doesn't
     * move; a later refusal of the same kind leaves the first as it is, or steady refusals would
     * never reach the 15 minutes. A refusal of another kind starts again, and an open that works
     * clears it ([clearRefusals]).
     */
    private fun refusedLongEnough(e: Exception): Boolean {
        val kind = e.javaClass.name
        val boot = clock.bootCount ?: -1
        val now = clock.elapsedMs
        val noted = prefs.getString(PREFS_KEY_REFUSED, null)?.split('|')
        val notedBoot = noted?.getOrNull(1)?.toIntOrNull()
        val first = noted?.getOrNull(2)?.toLongOrNull()
        if (noted?.size == 3 && noted[0] == kind && notedBoot != null && first != null) {
            // Time since boot going back is a reboot too, should the boot count be unreadable.
            return notedBoot != boot || now < first || now - first >= PERSISTENT_AFTER_MS
        }
        prefs.edit().putString(PREFS_KEY_REFUSED, "$kind|$boot|$now").commit()
        return false
    }

    private fun clearRefusals() {
        if (prefs.contains(PREFS_KEY_REFUSED)) prefs.edit().remove(PREFS_KEY_REFUSED).commit()
    }

    /**
     * The database made again under a new passphrase, its old one gone for good: a new key under
     * the alias the old seal doesn't name, a new seal made and checked, and seal, alias, the note
     * and [PREFS_KEY_STALE_DATABASE] stored in ONE commit, all before anything is deleted. A
     * Keystore that can't make or check the new key deletes nothing: the open fails (or waits,
     * locked), and the old seal and database stay. Only then the old database goes, and the old key.
     */
    private fun rebuild(oldAlias: String): ByteArray {
        val newAlias = if (oldAlias == KEYSTORE_ALIAS_NO_LOCK) KEYSTORE_ALIAS_NO_LOCK_B else KEYSTORE_ALIAS_NO_LOCK
        val passphrase = ByteArray(PASSPHRASE_LENGTH).also { SecureRandom().nextBytes(it) }
        var stored = false
        try {
            // Nothing names a key there: one left by a making-again that stopped early.
            vault.delete(newAlias)
            val key = vault.create(newAlias)
            val (sealed, iv) = encryptPassphrase(key, passphrase)
            check(decryptPassphrase(key, sealed, iv).contentEquals(passphrase)) { "the new seal doesn't open" }
            stored = prefs.edit()
                .putString(PREFS_KEY_PASSPHRASE, android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP))
                .putString(PREFS_KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                .putString(PREFS_KEY_ALIAS, newAlias)
                .putBoolean(PREFS_KEY_STALE_DATABASE, true)
                .putLong(PREFS_KEY_REBUILT_AT, System.currentTimeMillis())
                .remove(PREFS_KEY_REFUSED)
                .commit()
            check(stored) { "the new seal wasn't stored" }
        } catch (e: Exception) {
            passphrase.fill(0)
            // Nothing names the new key while the old seal stands.
            if (!stored) runCatching { vault.delete(newAlias) }
            if (isDeviceLocked(e) || screenLock.isLocked) throw DeviceLockedException(e)
            Log.w(TAG, "The new database key couldn't be made; nothing deleted", e)
            throw DatabaseKeyRefusedException(e)
        }
        dropStaleDatabase()
        // The old key seals nothing now. One that won't go is removed at the next open (moveIfDue).
        runCatching { vault.delete(oldAlias) }
        return passphrase
    }

    /** The database a making-again replaced, still on disk: it goes, before any open. */
    private fun dropStaleDatabase() {
        if (!prefs.getBoolean(PREFS_KEY_STALE_DATABASE, false)) return
        deleteExistingDatabase()
        prefs.edit().remove(PREFS_KEY_STALE_DATABASE).commit()
    }

    /**
     * Deletes the existing database file if it exists.
     * This is necessary when generating a new passphrase to avoid
     * "file is not a database" errors from passphrase mismatch.
     */
    private fun deleteExistingDatabase() {
        val databasesDir = File(context.applicationInfo.dataDir, "databases")
        val databaseFile = File(databasesDir, DATABASE_NAME)
        val walFile = File(databasesDir, "$DATABASE_NAME-wal")
        val shmFile = File(databasesDir, "$DATABASE_NAME-shm")
        val journalFile = File(databasesDir, "$DATABASE_NAME-journal")

        listOf(databaseFile, walFile, shmFile, journalFile).forEach { file ->
            if (file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "Deleted ${file.name}: $deleted")
            }
        }
    }

    /**
     * Clears the stored passphrase and deletes the encryption key.
     * WARNING: This will make the existing database unreadable!
     * Only call this during account deletion/logout.
     *
     * @param deleteDatabase If true, immediately deletes the database file.
     *                       Recommended for logout/account deletion.
     */
    fun clearPassphrase(deleteDatabase: Boolean = false) {
        // Clear the in-memory cache so subsequent reads go through Keystore.
        clearCache()

        // SECURITY: If requested, delete database files immediately to prevent
        // any possibility of data recovery after logout.
        if (deleteDatabase) {
            deleteExistingDatabase()
        }

        // Remove stored encrypted passphrase using commit() for synchronous deletion
        // (security-critical operations should not use async apply()). A store that
        // can't be opened now doesn't stop the keys going below.
        runCatching {
            prefs.edit()
                .remove(PREFS_KEY_PASSPHRASE)
                .remove(PREFS_KEY_IV)
                .remove(PREFS_KEY_ALIAS)
                .remove(PREFS_KEY_REBUILT_AT)
                .remove(PREFS_KEY_REFUSED)
                .remove(PREFS_KEY_STALE_DATABASE)
                .commit() // Use commit() instead of apply() for immediate effect
        }.onFailure { Log.w(TAG, "Database passphrase store not cleared: ${it.javaClass.simpleName}") }

        // Delete the keystore keys: the earlier lock-bound
        // one and the current ones
        for (alias in listOf(KEYSTORE_ALIAS, KEYSTORE_ALIAS_NO_LOCK, KEYSTORE_ALIAS_NO_LOCK_B)) {
            try {
                vault.delete(alias)
                Log.d(TAG, "Keystore key $alias removed")
            } catch (e: Exception) {
                // Key may not exist, ignore but log
                Log.d(TAG, "Keystore key deletion skipped (may not exist): ${e.message}")
            }
        }
    }

    /**
     * A new random passphrase, sealed under the key not tied to the screen lock and stored. A
     * failure says what it is: [DeviceLockedException] while locked, else
     * [SecureStorageUnavailableException], never a bare Keystore error.
     */
    private fun generateAndStorePassphrase(): ByteArray {
        // Generate random passphrase
        val passphrase = ByteArray(PASSPHRASE_LENGTH)
        SecureRandom().nextBytes(passphrase)
        try {
            val key = vault.key(KEYSTORE_ALIAS_NO_LOCK) ?: vault.create(KEYSTORE_ALIAS_NO_LOCK)
            val (encryptedData, iv) = encryptPassphrase(key, passphrase)
            store(KEYSTORE_ALIAS_NO_LOCK, encryptedData, iv)
            // The old key seals nothing now.
            runCatching { vault.delete(KEYSTORE_ALIAS) }
            return passphrase
        } catch (e: Exception) {
            passphrase.fill(0)
            if (isDeviceLocked(e)) {
                Log.w(TAG, "Keystore unavailable on first-time passphrase generation (device locked)", e)
                throw DeviceLockedException(e)
            }
            throw SecureStorageUnavailableException(e)
        }
    }

    /**
     * A passphrase still sealed under the old key, made with setUnlockedDeviceRequired, moves to
     * the key not tied to the screen lock, while the phone is unlocked, on every API level (the
     * old key had the setting only from API 28; moving every phone leaves one kind of key).
     * Failure-safe: the old seal and its key stay until the new seal is stored and opens from the
     * store; any failure before that puts the old seal back and leaves the old key alone, and the
     * next open tries again. The old key goes only after the move is done. The database is never
     * touched. Its callers catch whatever the restore throws.
     */
    private fun moveIfDue(alias: String, passphrase: ByteArray) {
        if (alias == KEYSTORE_ALIAS_NO_LOCK || alias == KEYSTORE_ALIAS_NO_LOCK_B) {
            // Already off it: an old key left by a move that stopped after storing seals nothing,
            // and nor does the other no-lock key, left by a making-again whose delete failed.
            val other = if (alias == KEYSTORE_ALIAS_NO_LOCK) KEYSTORE_ALIAS_NO_LOCK_B else KEYSTORE_ALIAS_NO_LOCK
            for (stray in listOf(KEYSTORE_ALIAS, other)) runCatching { if (vault.key(stray) != null) vault.delete(stray) }
            return
        }
        // Not while locked: the old key can't open anything then, and nothing is lost by waiting.
        if (screenLock.isLocked) return
        val oldSealed = prefs.getString(PREFS_KEY_PASSPHRASE, null) ?: return
        val oldIv = prefs.getString(PREFS_KEY_IV, null) ?: return
        var stored = false
        try {
            // Nothing is sealed under it yet: a key left by a move that stopped early.
            vault.delete(KEYSTORE_ALIAS_NO_LOCK)
            val key = vault.create(KEYSTORE_ALIAS_NO_LOCK)
            val (sealed, iv) = encryptPassphrase(key, passphrase)
            check(decryptPassphrase(key, sealed, iv).contentEquals(passphrase)) { "the new seal doesn't open" }
            store(KEYSTORE_ALIAS_NO_LOCK, sealed, iv)
            stored = true
            // Read back, as the next start will.
            val reread = openSealed(KEYSTORE_ALIAS_NO_LOCK, prefs.getString(PREFS_KEY_PASSPHRASE, null)!!, prefs.getString(PREFS_KEY_IV, null)!!)
            check(reread?.contentEquals(passphrase) == true) { "the stored seal doesn't open" }
            reread.fill(0)
        } catch (e: Exception) {
            Log.w(TAG, "Database key not moved yet: ${e.javaClass.simpleName}")
            // The old seal back, under the key that still opens it; the new key goes only once
            // nothing names it.
            val restored = !stored || prefs.edit()
                .putString(PREFS_KEY_PASSPHRASE, oldSealed)
                .putString(PREFS_KEY_IV, oldIv)
                .putString(PREFS_KEY_ALIAS, KEYSTORE_ALIAS)
                .commit()
            if (restored) runCatching { vault.delete(KEYSTORE_ALIAS_NO_LOCK) }
            return
        }
        Log.i(TAG, "Database key moved off the screen lock")
        // Done: the old key goes now. A delete that fails leaves a key nothing names, which the
        // next open removes (above), never a move undone.
        runCatching { vault.delete(KEYSTORE_ALIAS) }
    }

    /** The passphrase sealed under [alias], opened; null when that key is gone. Never makes a key. */
    private fun openSealed(alias: String, encryptedPassphrase: String, iv: String): ByteArray? {
        val key = vault.key(alias) ?: return null
        return decryptPassphrase(
            key,
            android.util.Base64.decode(encryptedPassphrase, android.util.Base64.NO_WRAP),
            android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)
        )
    }

    /** The seal and which key made it, together. Throws when they weren't stored. */
    private fun store(alias: String, encryptedData: ByteArray, iv: ByteArray) {
        val stored = prefs.edit()
            .putString(PREFS_KEY_PASSPHRASE, android.util.Base64.encodeToString(encryptedData, android.util.Base64.NO_WRAP))
            .putString(PREFS_KEY_IV, android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            .putString(PREFS_KEY_ALIAS, alias)
            .commit()
        check(stored) { "the sealed passphrase wasn't stored" }
    }

    private fun encryptPassphrase(key: SecretKey, passphrase: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val encryptedData = cipher.doFinal(passphrase)
        val iv = cipher.iv

        return Pair(encryptedData, iv)
    }

    private fun decryptPassphrase(key: SecretKey, encryptedData: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(encryptedData)
    }
}

/** The Keystore keys under the database passphrase (a seam for the tests). */
internal interface DbKeyVault {
    /** The key under [alias], or null when there is none. */
    fun key(alias: String): SecretKey?

    /** A new AES-256-GCM key under [alias], not tied to the screen lock. */
    fun create(alias: String): SecretKey

    /** Removes [alias]; nothing when there is none. */
    fun delete(alias: String)
}

/** Whether the phone is locked now (a seam for the tests). */
internal interface ScreenLock {
    val isLocked: Boolean
}

/** Which boot this is, and the time since it (a seam for the tests). */
internal interface BootClock {
    /** Settings.Global.BOOT_COUNT; null when it can't be read. */
    val bootCount: Int?
    /** SystemClock.elapsedRealtime: not moved by a change of the phone's clock. */
    val elapsedMs: Long
}

internal class AndroidBootClock(private val context: Context) : BootClock {
    override val bootCount: Int?
        get() = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
        }.getOrNull()
    override val elapsedMs: Long
        get() = android.os.SystemClock.elapsedRealtime()
}

internal class KeyguardScreenLock(private val context: Context) : ScreenLock {
    // No answer is locked: nothing is then decided or moved, only waited for.
    override val isLocked: Boolean
        get() = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
}

internal object AndroidDbKeyVault : DbKeyVault {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun key(alias: String): SecretKey? = keyStore().getKey(alias, null) as SecretKey?

    override fun create(alias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)

        // Security configuration for the database encryption key:
        //
        // 1. Hardware-backed Keystore (key cannot be extracted even with root)
        // 2. App sandbox isolation
        // 3. Device encryption (FBE/FDE)
        // 4. SQLCipher database encryption
        //
        // Not tied to the screen lock: no setUnlockedDeviceRequired. With it, a
        // phone with no secure lock can't make the key, and removing the lock
        // deletes it, and the database with it.
        //
        // Note: We don't use setUserAuthenticationRequired(true) with BiometricPrompt because:
        // - Database is initialized during Hilt DI before any Activity is available
        // - For app-level biometric lock, use BiometricAuthManager separately (App Lock feature)
        val keySpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    override fun delete(alias: String) {
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }
}

/**
 * Thrown when the database passphrase cannot be decrypted because the device is locked
 * (the earlier Keystore key, made with setUnlockedDeviceRequired(true), until it is
 * moved; or a key that can't be judged gone while locked). Callers should defer
 * the DB access until ACTION_USER_PRESENT.
 *
 * Critically: this is recoverable. The local DB and Keystore key are intact; only the
 * current decrypt operation is refused by Keystore. Do NOT wipe local data.
 */
class DeviceLockedException(cause: Throwable) :
    // Plain words: plainText shows this app's own exceptions' messages as they are.
    Exception("Unlock this phone, then try again.", cause)

/**
 * Thrown when this phone's Keystore refused the database key in a way that may pass: the open
 * fails, and nothing is deleted. The next open tries again. The same refusal on the next start,
 * the phone unlocked both times, makes the database again, and says so. Not a wrong password,
 * and never counted as one.
 */
class DatabaseKeyRefusedException(cause: Throwable) :
    // Plain words: plainText shows this app's own exceptions' messages as they are.
    Exception(DATABASE_KEY_REFUSED_TEXT, cause)

/**
 * Thrown when this phone's Keystore wouldn't make or use the database key for a reason other
 * than a locked device. Not a wrong password, and never counted as one.
 */
class SecureStorageUnavailableException(cause: Throwable) :
    // Plain words: plainText shows this app's own exceptions' messages as they are.
    Exception(SECURE_STORAGE_TEXT, cause)
