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
import android.util.Log
import com.pingolin.track.BuildConfig
import uniffi.pingolin_mobile.SecureStorageCallback
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of SecureStorageCallback using EncryptedSharedPreferences.
 *
 * This callback is registered with the Rust SDK's SecureStorage to enable
 * secure persistence of circle keys and other sensitive data using
 * Android's EncryptedSharedPreferences backed by Android Keystore.
 *
 * Security:
 * - Keys are encrypted with AES-256-SIV
 * - Values are encrypted with AES-256-GCM
 * - Master key is stored in Android Keystore (hardware-backed when available)
 */
@Singleton
class SecureStorageCallbackImpl @Inject constructor(
    private val encryptedSharedPreferences: SharedPreferences
) : SecureStorageCallback {

    companion object {
        private const val TAG = "SecureStorageCallback"

        // Prefix for SDK-related keys to avoid conflicts with app's own keys
        private const val SDK_KEY_PREFIX = "sdk_"
    }

    /**
     * Store a value securely using EncryptedSharedPreferences.
     */
    override fun store(key: String, value: String) {
        try {
            val prefKey = SDK_KEY_PREFIX + key
            encryptedSharedPreferences.edit()
                .putString(prefKey, value)
                .apply()
            if (BuildConfig.DEBUG) Log.d(TAG, "Stored key: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store key: $key", e)
            throw e
        }
    }

    /**
     * Retrieve a value from secure storage.
     */
    override fun retrieve(key: String): String? {
        return try {
            val prefKey = SDK_KEY_PREFIX + key
            val value = encryptedSharedPreferences.getString(prefKey, null)
            if (BuildConfig.DEBUG) Log.d(TAG, "Retrieved key: $key (found: ${value != null})")
            value
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve key: $key", e)
            throw e
        }
    }

    /**
     * Delete a value from secure storage.
     */
    override fun delete(key: String) {
        try {
            val prefKey = SDK_KEY_PREFIX + key
            encryptedSharedPreferences.edit()
                .remove(prefKey)
                .apply()
            if (BuildConfig.DEBUG) Log.d(TAG, "Deleted key: $key")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete key: $key", e)
            throw e
        }
    }

    /**
     * Check if a key exists in secure storage.
     */
    override fun contains(key: String): Boolean {
        return try {
            val prefKey = SDK_KEY_PREFIX + key
            val exists = encryptedSharedPreferences.contains(prefKey)
            if (BuildConfig.DEBUG) Log.d(TAG, "Contains key: $key = $exists")
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check key: $key", e)
            throw e
        }
    }

    /**
     * Clear all SDK-related data from secure storage.
     * Only removes keys with the SDK prefix, preserving app's own data.
     */
    override fun clearAll() {
        try {
            val editor = encryptedSharedPreferences.edit()
            val allKeys = encryptedSharedPreferences.all.keys
            var count = 0

            for (key in allKeys) {
                if (key.startsWith(SDK_KEY_PREFIX)) {
                    editor.remove(key)
                    count++
                }
            }

            editor.apply()
            Log.i(TAG, "Cleared $count SDK keys from secure storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all SDK keys", e)
            throw e
        }
    }
}
