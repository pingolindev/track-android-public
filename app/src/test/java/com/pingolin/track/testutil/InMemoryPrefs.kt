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

package com.pingolin.track.testutil

import android.content.SharedPreferences

/**
 * Minimal SharedPreferences backed by a HashMap, for plain JVM tests of code that
 * would open EncryptedSharedPreferences (which needs the Android Keystore). The same
 * shape SessionManagerTest and TrackingJournalRecordingTest keep privately.
 */
class InMemoryPrefs : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    /** While set, commit() writes nothing and says so, as a full disk does. */
    var failCommits = false

    override fun getAll(): Map<String, *> = data.toMap()
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? Set<String> ?: defValues
    }
    override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = Editor(this)

    private class Editor(private val prefs: InMemoryPrefs) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(k: String?, v: String?) = apply { if (k != null) pending[k] = v }
        // Stored as a copy, as the platform does: the caller's set must not alias ours.
        override fun putStringSet(k: String?, v: Set<String>?) = apply { if (k != null) pending[k] = v?.toSet() }
        override fun putInt(k: String?, v: Int) = apply { if (k != null) pending[k] = v }
        override fun putLong(k: String?, v: Long) = apply { if (k != null) pending[k] = v }
        override fun putFloat(k: String?, v: Float) = apply { if (k != null) pending[k] = v }
        override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) pending[k] = v }
        override fun remove(k: String?) = apply { if (k != null) removed.add(k) }
        override fun clear() = apply { clear = true }

        override fun commit(): Boolean {
            if (prefs.failCommits) return false
            apply()
            return true
        }

        override fun apply() {
            if (clear) prefs.data.clear()
            removed.forEach { prefs.data.remove(it) }
            pending.forEach { (k, v) -> prefs.data[k] = v }
        }
    }
}

/**
 * A store the Keystore can refuse to decrypt, as EncryptedSharedPreferences does: while
 * [refuseReads] is set every getString throws. Writes still land in [data].
 */
class RefusingPrefs(private val inner: InMemoryPrefs = InMemoryPrefs()) : SharedPreferences by inner {
    var refuseReads = false
    val data get() = inner.data
    override fun getString(key: String?, defValue: String?): String? {
        if (refuseReads) throw SecurityException("Could not decrypt value")
        return inner.getString(key, defValue)
    }
}

/**
 * A preferences file that goes unreadable, as EncryptedSharedPreferences does when a value in it
 * is damaged or the Keystore refuses it: while [damaged] is set every read throws. Writes still
 * land in [data], so a test sees anything written over it.
 */
class DamagedPrefs(private val inner: InMemoryPrefs = InMemoryPrefs()) : SharedPreferences by inner {
    var damaged = false
    val data get() = inner.data

    /** As [InMemoryPrefs.failCommits]. */
    var failCommits: Boolean
        get() = inner.failCommits
        set(value) { inner.failCommits = value }

    private fun <T> read(value: () -> T): T {
        if (damaged) throw SecurityException("Could not decrypt value")
        return value()
    }

    override fun getAll(): Map<String, *> = read { inner.all }
    override fun getString(key: String?, defValue: String?): String? = read { inner.getString(key, defValue) }
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = read { inner.getStringSet(key, defValues) }
    override fun getInt(key: String?, defValue: Int): Int = read { inner.getInt(key, defValue) }
    override fun getLong(key: String?, defValue: Long): Long = read { inner.getLong(key, defValue) }
    override fun getFloat(key: String?, defValue: Float): Float = read { inner.getFloat(key, defValue) }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read { inner.getBoolean(key, defValue) }
    override fun contains(key: String?): Boolean = read { inner.contains(key) }
}

/**
 * [prefs] behind a file that can't be opened while [unopenable] is set, as SecurePrefs.open
 * throws when the Keystore refuses the master key: the store's provider.
 */
class UnopenablePrefs(val prefs: SharedPreferences = InMemoryPrefs()) {
    var unopenable = false
    val provider: (android.content.Context) -> SharedPreferences = {
        if (unopenable) throw java.security.GeneralSecurityException("The master key can't be used now")
        prefs
    }
}
