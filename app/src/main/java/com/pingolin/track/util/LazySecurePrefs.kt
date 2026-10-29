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

import android.content.SharedPreferences

/**
 * An encrypted store opened at its first use, not when the app's objects are made. Opening it
 * is a Keystore call, and one made while Hilt injects MainActivity would crash Track at every
 * start while the Keystore is down. A store that can't be opened throws
 * [SecureStorageUnavailableException] at that use, where its callers handle it, and is tried
 * again at the next one: a Keystore back up opens it.
 */
class LazySecurePrefs(private val open: () -> SharedPreferences) : SharedPreferences {
    @Volatile
    private var opened: SharedPreferences? = null

    private fun prefs(): SharedPreferences = opened ?: synchronized(this) {
        opened ?: try {
            open()
        } catch (e: Exception) {
            throw SecureStorageUnavailableException(e)
        }.also { opened = it }
    }

    override fun getAll(): MutableMap<String, *> = prefs().all
    override fun getString(key: String?, defValue: String?): String? = prefs().getString(key, defValue)
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        prefs().getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = prefs().getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = prefs().getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = prefs().getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = prefs().getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = prefs().contains(key)
    override fun edit(): SharedPreferences.Editor = prefs().edit()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        prefs().registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        prefs().unregisterOnSharedPreferenceChangeListener(listener)
}
