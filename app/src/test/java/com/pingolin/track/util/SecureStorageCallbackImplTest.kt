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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SecureStorageCallbackImpl.
 *
 * Tests the SDK secure storage callback that uses EncryptedSharedPreferences.
 * Verifies key prefix isolation and selective cleanup.
 */
class SecureStorageCallbackImplTest {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var secureStorageCallback: SecureStorageCallbackImpl

    companion object {
        private const val SDK_KEY_PREFIX = "sdk_"
    }

    @Before
    fun setUp() {
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit

        secureStorageCallback = SecureStorageCallbackImpl(sharedPreferences)
    }

    @Test
    fun `store adds SDK prefix to key`() {
        // When
        secureStorageCallback.store("circle_key_123", "encrypted_value")

        // Then
        verify { editor.putString("sdk_circle_key_123", "encrypted_value") }
        verify { editor.apply() }
    }

    @Test
    fun `retrieve looks up key with SDK prefix`() {
        // Given
        every { sharedPreferences.getString("sdk_circle_key_123", null) } returns "stored_value"

        // When
        val result = secureStorageCallback.retrieve("circle_key_123")

        // Then
        assertEquals("stored_value", result)
        verify { sharedPreferences.getString("sdk_circle_key_123", null) }
    }

    @Test
    fun `retrieve returns null for missing key`() {
        // Given
        every { sharedPreferences.getString("sdk_missing_key", null) } returns null

        // When
        val result = secureStorageCallback.retrieve("missing_key")

        // Then
        assertNull(result)
    }

    @Test
    fun `delete removes key with SDK prefix`() {
        // When
        secureStorageCallback.delete("circle_key_123")

        // Then
        verify { editor.remove("sdk_circle_key_123") }
        verify { editor.apply() }
    }

    @Test
    fun `contains checks key with SDK prefix`() {
        // Given
        every { sharedPreferences.contains("sdk_existing_key") } returns true
        every { sharedPreferences.contains("sdk_missing_key") } returns false

        // When & Then
        assertTrue(secureStorageCallback.contains("existing_key"))
        assertFalse(secureStorageCallback.contains("missing_key"))
    }

    @Test
    fun `clearAll only removes keys with SDK prefix`() {
        // Given - mix of SDK and app keys
        val allKeys = mapOf(
            "sdk_circle_key_1" to "value1",
            "sdk_circle_key_2" to "value2",
            "app_user_token" to "token_value",
            "app_settings" to "settings_value",
            "sdk_private_key" to "key_value"
        )
        every { sharedPreferences.all } returns allKeys

        // When
        secureStorageCallback.clearAll()

        // Then - only SDK prefixed keys should be removed
        verify { editor.remove("sdk_circle_key_1") }
        verify { editor.remove("sdk_circle_key_2") }
        verify { editor.remove("sdk_private_key") }
        verify(exactly = 0) { editor.remove("app_user_token") }
        verify(exactly = 0) { editor.remove("app_settings") }
        verify { editor.apply() }
    }

    @Test
    fun `clearAll handles empty storage`() {
        // Given
        every { sharedPreferences.all } returns emptyMap()

        // When
        secureStorageCallback.clearAll()

        // Then - should not throw, just apply empty changes
        verify { editor.apply() }
    }

    @Test
    fun `clearAll handles storage with no SDK keys`() {
        // Given - only app keys
        val allKeys = mapOf(
            "app_user_token" to "token_value",
            "app_settings" to "settings_value"
        )
        every { sharedPreferences.all } returns allKeys

        // When
        secureStorageCallback.clearAll()

        // Then - no keys should be removed
        verify(exactly = 0) { editor.remove(any()) }
        verify { editor.apply() }
    }

    @Test
    fun `store handles special characters in key`() {
        // When
        secureStorageCallback.store("key:with/special-chars_123", "value")

        // Then
        verify { editor.putString("sdk_key:with/special-chars_123", "value") }
    }

    @Test
    fun `store handles empty value`() {
        // When
        secureStorageCallback.store("key", "")

        // Then
        verify { editor.putString("sdk_key", "") }
    }

    @Test
    fun `retrieve handles empty stored value`() {
        // Given
        every { sharedPreferences.getString("sdk_key", null) } returns ""

        // When
        val result = secureStorageCallback.retrieve("key")

        // Then
        assertEquals("", result)
    }
}
