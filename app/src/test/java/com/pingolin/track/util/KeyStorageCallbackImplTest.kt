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
import com.pingolin.track.testutil.RefusingPrefs
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.StorageCallbackException
import uniffi.pingolin_mobile.StoredKeyData

/**
 * Unit tests for KeyStorageCallbackImpl: the SDK's key store in EncryptedSharedPreferences, on the
 * SDK's contract for it — null only when nothing is stored, what a store cut short holds with "" for
 * a missing part (doesNotDecode), a read the platform refuses thrown (readFailed), and a delete the
 * platform refuses thrown (deleteFailed).
 */
class KeyStorageCallbackImplTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var keyStorageCallback: KeyStorageCallbackImpl

    @Before
    fun setUp() {
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit
        every { editor.commit() } returns true

        keyStorageCallback = KeyStorageCallbackImpl(sharedPreferences)
    }

    private fun stored(privateKey: String?, circleKeys: String?, pins: String? = null, seed: String? = null) {
        every { sharedPreferences.getString("crypto_private_key", null) } returns privateKey
        every { sharedPreferences.getString("crypto_circle_keys_json", null) } returns circleKeys
        every { sharedPreferences.getString("crypto_pinned_keys_json", null) } returns pins
        every { sharedPreferences.getString("crypto_signing_private_key_b64", null) } returns seed
    }

    @Test
    fun `storeKeys saves privateKey and circleKeysJson`() {
        val testData = StoredKeyData(
            privateKey = "test_private_key_hex",
            circleKeysJson = """{"circle1": "key1", "circle2": "key2"}""",
            pinnedKeysJson = null,
            signingPrivateKeyB64 = null
        )

        keyStorageCallback.storeKeys(testData)

        verify { editor.putString("crypto_private_key", "test_private_key_hex") }
        verify { editor.putString("crypto_circle_keys_json", """{"circle1": "key1", "circle2": "key2"}""") }
        verify { editor.apply() }
    }

    @Test
    fun `loadKeys returns stored data when both values exist`() {
        stored("stored_private_key", """{"circle": "key"}""")

        val result = keyStorageCallback.loadKeys()

        assertNotNull(result)
        assertEquals("stored_private_key", result!!.privateKey)
        assertEquals("""{"circle": "key"}""", result.circleKeysJson)
    }

    // A store cut short is not an empty one: it goes to the SDK as it is, "" for what's missing, and
    // the SDK reports doesNotDecode, which the sign-in clears. Null would read as "nothing stored",
    // and the store would be written over.
    @Test
    fun `a store cut short gives what it has, with an empty private key or circle-key map`() {
        stored(null, """{"circle": "key"}""", pins = "pins")
        assertEquals(StoredKeyData("", """{"circle": "key"}""", "pins", null), keyStorageCallback.loadKeys())

        stored("stored_private_key", null, seed = "seed")
        assertEquals(StoredKeyData("stored_private_key", "", null, "seed"), keyStorageCallback.loadKeys())

        stored(null, null, pins = "pins")
        assertEquals("pins alone are not nothing", StoredKeyData("", "", "pins", null), keyStorageCallback.loadKeys())
    }

    @Test
    fun `loadKeys returns null only when none of the four entries is stored`() {
        stored(null, null)
        assertNull(keyStorageCallback.loadKeys())
    }

    @Test
    fun `deleteKeys removes all four entries, on disk before it returns`() {
        keyStorageCallback.deleteKeys()

        verify { editor.remove("crypto_private_key") }
        verify { editor.remove("crypto_circle_keys_json") }
        verify { editor.remove("crypto_pinned_keys_json") }
        verify { editor.remove("crypto_signing_private_key_b64") }
        verify { editor.commit() }
    }

    @Test
    fun `a delete the platform refuses throws, so the SDK reports deleteFailed`() {
        every { editor.commit() } returns false
        assertThrows(StorageCallbackException.Failed::class.java) { keyStorageCallback.deleteKeys() }

        every { sharedPreferences.edit() } throws SecurityException("Could not encrypt value")
        assertThrows(StorageCallbackException.Failed::class.java) { keyStorageCallback.deleteKeys() }
    }

    @Test
    fun `storeKeys overwrites existing data`() {
        keyStorageCallback.storeKeys(StoredKeyData("first_private_key", """{"first": "data"}""", null, null))
        keyStorageCallback.storeKeys(StoredKeyData("second_private_key", """{"second": "data"}""", null, null))

        verify { editor.putString("crypto_private_key", "second_private_key") }
        verify { editor.putString("crypto_circle_keys_json", """{"second": "data"}""") }
    }

    @Test
    fun `loadKeys handles empty strings as valid values`() {
        stored("", "{}")

        val result = keyStorageCallback.loadKeys()

        assertNotNull(result)
        assertEquals("", result!!.privateKey)
        assertEquals("{}", result.circleKeysJson)
    }

    // ---- a store that cannot be read ----
    //
    // It reads as readFailed, never as "nothing stored": the SDK would then write this
    // session's keys over it, and every circle key and member pin it held would be gone for
    // good. The SDK writes nothing over it until a load succeeds or the keys are cleared.

    private val prefs = RefusingPrefs()
    private val storage = KeyStorageCallbackImpl(prefs)

    private val kept = StoredKeyData(
        privateKey = "private",
        circleKeysJson = """{"c-1":{"1":"key"}}""",
        pinnedKeysJson = """{"c-1:bob":"pin"}""",
        signingPrivateKeyB64 = "seed",
    )

    @Test
    fun `a read the store refuses is an error, not an empty store, and it reads again later`() {
        storage.storeKeys(kept)
        prefs.refuseReads = true

        assertThrows(StorageCallbackException.Failed::class.java) { storage.loadKeys() }

        prefs.refuseReads = false
        assertEquals(kept, storage.loadKeys())
    }

    // ---- the export's one read ----
    //
    // The export file's own key store needs this account's sharing key and nothing else. The
    // read takes that one entry: no other entry is read, and nothing is written, migrated or
    // made.

    @Test
    fun `sharingPrivateKey reads the one entry and nothing else, and writes nothing`() {
        val strict: SharedPreferences = mockk()
        every { strict.getString("crypto_private_key", null) } returns "stored_private_key"

        assertEquals("stored_private_key", KeyStorageCallbackImpl(strict).sharingPrivateKey())

        verify(exactly = 1) { strict.getString("crypto_private_key", null) }
        confirmVerified(strict)
    }

    @Test
    fun `sharingPrivateKey is null when none is stored, when it is blank, and when the read is refused`() {
        val strict: SharedPreferences = mockk()
        every { strict.getString("crypto_private_key", null) } returnsMany listOf<String?>(null, " ") andThenThrows
            SecurityException("Could not decrypt value")
        val reader = KeyStorageCallbackImpl(strict)

        assertNull(reader.sharingPrivateKey())
        assertNull(reader.sharingPrivateKey())
        assertNull(reader.sharingPrivateKey())

        verify(exactly = 3) { strict.getString("crypto_private_key", null) }
        confirmVerified(strict)
    }
}
