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

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * SQLCipher open-helper factory that defers passphrase decryption (a hardware
 * Keystore op) until the database is actually opened, not when the Hilt graph
 * is constructed.
 *
 * Why: a [DatabasePassphraseManager] key made by an earlier version with
 * setUnlockedDeviceRequired(true) stays in use until its first unlocked open
 * moves it. If the OS cold-launches the app while the phone is locked (after
 * an APK update, deep-doze kill, or reboot), eagerly calling
 * [DatabasePassphraseManager.getPassphrase] inside provideDatabase crashes the
 * app at start and tracking never recovers. Deferring the call lets the Hilt
 * graph finish so the foreground service can start; the first DB access may
 * still fail with [DeviceLockedException], but the app survives and resumes
 * once unlocked.
 */
class LazyPassphraseOpenHelperFactory(
    private val passphraseManager: DatabasePassphraseManager
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        return LazyOpenHelper(passphraseManager, configuration)
    }

    private class LazyOpenHelper(
        private val passphraseManager: DatabasePassphraseManager,
        private val config: SupportSQLiteOpenHelper.Configuration
    ) : SupportSQLiteOpenHelper {

        @Volatile private var delegate: SupportSQLiteOpenHelper? = null
        @Volatile private var writeAheadLoggingEnabled: Boolean = false

        override val databaseName: String?
            get() = config.name

        override val readableDatabase: SupportSQLiteDatabase
            get() = requireDelegate().readableDatabase

        override val writableDatabase: SupportSQLiteDatabase
            get() = requireDelegate().writableDatabase

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            writeAheadLoggingEnabled = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }

        override fun close() {
            delegate?.close()
            delegate = null
        }

        private fun requireDelegate(): SupportSQLiteOpenHelper {
            delegate?.let { return it }
            synchronized(this) {
                delegate?.let { return it }
                val passphrase = passphraseManager.getPassphrase()
                try {
                    val d = SupportOpenHelperFactory(passphrase).create(config)
                    d.setWriteAheadLoggingEnabled(writeAheadLoggingEnabled)
                    delegate = d
                    return d
                } finally {
                    DatabasePassphraseManager.clearPassphraseBytes(passphrase)
                }
            }
        }

        companion object {
            private const val TAG = "LazyOpenHelper"
        }
    }
}
