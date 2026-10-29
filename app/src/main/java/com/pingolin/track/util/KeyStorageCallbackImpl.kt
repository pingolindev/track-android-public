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
import uniffi.pingolin_mobile.KeyStorageCallback
import uniffi.pingolin_mobile.StorageCallbackException
import uniffi.pingolin_mobile.StoredKeyData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android implementation of KeyStorageCallback for CryptoManager key persistence.
 *
 * This callback is registered with the CryptoManager to enable secure persistence
 * of the user's private key and circle keys using Android's EncryptedSharedPreferences.
 *
 * Security:
 * - Keys are encrypted with AES-256-SIV
 * - Values are encrypted with AES-256-GCM
 * - Master key is stored in Android Keystore (hardware-backed when available)
 *
 * Storage layout:
 * - `crypto_private_key` — base64 X25519 sharing private key
 * - `crypto_circle_keys_json` — versioned circle keys, JSON
 * - `crypto_pinned_keys_json` — TOFU pin records. Optional, null until pinning
 *   has run at least once.
 * - `crypto_signing_private_key_b64` — base64 Ed25519 secret seed. Optional,
 *   null until a sign-in has stored one.
 *
 * The implementation is a transparent passthrough — unknown fields, missing
 * fields, and any future SDK additions round-trip via the four explicit slots.
 *
 * A store that cannot be READ is never reported as empty. A failed read — the
 * Keystore or EncryptedSharedPreferences refusing to decrypt — throws, and the SDK
 * writes nothing over the store until a load succeeds or the keys are cleared (it
 * reports readFailed; see KeyStoreRecovery, which never clears such a store).
 * Answering null, "nothing stored", would let the SDK write whatever this session
 * imported over the unreadable store, and every circle key and member pin in it
 * would be gone for good.
 */
@Singleton
class KeyStorageCallbackImpl @Inject constructor(
    private val encryptedSharedPreferences: SharedPreferences
) : KeyStorageCallback {

    companion object {
        private const val TAG = "KeyStorageCallback"
        private const val KEY_PRIVATE_KEY = "crypto_private_key"
        private const val KEY_CIRCLE_KEYS_JSON = "crypto_circle_keys_json"
        private const val KEY_PINNED_KEYS_JSON = "crypto_pinned_keys_json"
        private const val KEY_SIGNING_PRIVATE_KEY_B64 = "crypto_signing_private_key_b64"
    }

    override fun storeKeys(data: StoredKeyData) {
        try {
            val editor = encryptedSharedPreferences.edit()
                .putString(KEY_PRIVATE_KEY, data.privateKey)
                .putString(KEY_CIRCLE_KEYS_JSON, data.circleKeysJson)

            // Pinned keys + signing key are optional — write or remove based
            // on whether the SDK provided a value. This keeps storage in
            // sync with whatever state the SDK is currently holding.
            if (data.pinnedKeysJson != null) {
                editor.putString(KEY_PINNED_KEYS_JSON, data.pinnedKeysJson)
            } else {
                editor.remove(KEY_PINNED_KEYS_JSON)
            }
            if (data.signingPrivateKeyB64 != null) {
                editor.putString(KEY_SIGNING_PRIVATE_KEY_B64, data.signingPrivateKeyB64)
            } else {
                editor.remove(KEY_SIGNING_PRIVATE_KEY_B64)
            }
            editor.apply()
            Log.d(TAG, "Stored crypto keys (pinned=${data.pinnedKeysJson != null}, signing=${data.signingPrivateKeyB64 != null})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store crypto keys", e)
            throw e
        }
    }

    /**
     * The stored keys, or null only when NONE of the four entries is stored. A store cut short
     * gives what it has, with "" for a missing private key or circle-key map: the SDK then
     * reports doesNotDecode, which the sign-in clears, where null would read as "nothing
     * stored" and be written over. A read the Keystore or EncryptedSharedPreferences refuses
     * throws: the SDK reports readFailed, which is loaded again and never cleared. Whatever
     * this throws reaches the SDK as a storage failure (the callback declares
     * StorageCallbackError).
     */
    override fun loadKeys(): StoredKeyData? {
        return try {
            val privateKey = encryptedSharedPreferences.getString(KEY_PRIVATE_KEY, null)
            val circleKeysJson = encryptedSharedPreferences.getString(KEY_CIRCLE_KEYS_JSON, null)
            val pinnedKeysJson = encryptedSharedPreferences.getString(KEY_PINNED_KEYS_JSON, null)
            val signingPrivateKeyB64 = encryptedSharedPreferences.getString(KEY_SIGNING_PRIVATE_KEY_B64, null)
            if (privateKey == null && circleKeysJson == null && pinnedKeysJson == null && signingPrivateKeyB64 == null) {
                Log.d(TAG, "No crypto keys found")
                null
            } else {
                if (privateKey == null || circleKeysJson == null) {
                    Log.w(TAG, "Only part of the stored keys is present: handed over as it is")
                }
                Log.d(TAG, "Loaded crypto keys (pinned=${pinnedKeysJson != null}, signing=${signingPrivateKeyB64 != null})")
                StoredKeyData(
                    privateKey = privateKey ?: "",
                    circleKeysJson = circleKeysJson ?: "",
                    pinnedKeysJson = pinnedKeysJson,
                    signingPrivateKeyB64 = signingPrivateKeyB64,
                )
            }
        } catch (e: Exception) {
            // The exception's type only: its message can quote what failed to decrypt.
            Log.e(TAG, "Stored crypto keys could not be read (${e.javaClass.simpleName}); not writing over them")
            throw StorageCallbackException.Failed()
        }
    }

    /**
     * This account's sharing private key as stored, and nothing more: no other entry is read,
     * and nothing is written, migrated or made. For the export file's own key store
     * (ExportRepository.decryptExport), which opens the file's key wraps apart from the
     * live store. Null when none is stored or it can't be read. Never logged.
     */
    fun sharingPrivateKey(): String? = try {
        encryptedSharedPreferences.getString(KEY_PRIVATE_KEY, null)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // The exception's type only: its message can quote what failed to decrypt.
        Log.w(TAG, "The stored sharing key could not be read (${e.javaClass.simpleName})")
        null
    }

    /**
     * On disk before this returns: a delete the platform refuses throws, so the SDK reports
     * deleteFailed and the wipe is owed (OwedWipe), never taken as done.
     */
    override fun deleteKeys() {
        val deleted = try {
            encryptedSharedPreferences.edit()
                .remove(KEY_PRIVATE_KEY)
                .remove(KEY_CIRCLE_KEYS_JSON)
                .remove(KEY_PINNED_KEYS_JSON)
                .remove(KEY_SIGNING_PRIVATE_KEY_B64)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete crypto keys (${e.javaClass.simpleName})")
            false
        }
        if (!deleted) throw StorageCallbackException.Failed()
        Log.d(TAG, "Deleted crypto keys")
    }
}
