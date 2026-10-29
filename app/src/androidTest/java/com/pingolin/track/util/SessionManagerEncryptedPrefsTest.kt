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
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for SessionManager backed by real EncryptedSharedPreferences.
 *
 * JVM unit tests use a FakeSharedPreferences that bypasses the Android Keystore.
 * These tests exercise the real Keystore-backed encryption so we verify:
 * - Round-trip of tokens, userId, deviceId, pushToken through actual AES-256-SIV/GCM
 * - Values survive a second SessionManager instance constructed over the same prefs file
 *   (simulates process restart — the encrypted blob on disk must be re-readable)
 * - Push token sync flag lifecycle
 * - clearSession() removes the expected keys but preserves device ID
 *
 * Note: These tests construct a fresh SessionManager directly so we're not subject to
 * the FakeSessionManager override in TestModule.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class SessionManagerEncryptedPrefsTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()

        // Clear any state from prior test runs so each test starts from a known state.
        // We can't easily delete the encrypted prefs file without rebuilding the
        // MasterKey, so we just clearSession() which removes the keys we care about.
        try {
            makeSessionManager().clearSession()
        } catch (e: Exception) {
            // Fresh keystore may not have anything to clear; fine.
        }
    }

    @After
    fun tearDown() {
        try {
            makeSessionManager().clearSession()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }

    /**
     * Round-trip access/refresh token through the encrypted prefs.
     */
    @Test
    fun saveTokens_roundTripsThroughEncryptedPrefs() {
        val sm = makeSessionManager()

        sm.saveTokens(
            accessToken = "my-access-token-123",
            refreshToken = "my-refresh-token-456",
            expiresIn = 3600L
        )

        assertEquals("my-access-token-123", sm.getAccessToken())
        assertEquals("my-refresh-token-456", sm.getRefreshToken())
        assertFalse("Token with 1h expiry should not be expired", sm.isTokenExpired())
    }

    /**
     * Values written by one SessionManager instance must be readable by a fresh one
     * created against the same application context. This simulates process restart,
     * where the in-memory encrypted blob is gone but the on-disk file remains.
     */
    @Test
    fun tokens_survive_newSessionManagerInstance() {
        // First instance writes
        val sm1 = makeSessionManager()
        sm1.saveTokens("persist-access", "persist-refresh", 3600L)
        sm1.saveUser(
            userId = "user-uuid-99",
            email = "user@example.test",
            displayName = "Test User",
            publicKey = "test-public-key"
        )

        // Second instance reads (simulates process restart — fresh MasterKey handle,
        // same on-disk encrypted prefs file).
        val sm2 = makeSessionManager()
        assertEquals("persist-access", sm2.getAccessToken())
        assertEquals("persist-refresh", sm2.getRefreshToken())
        assertEquals("user-uuid-99", sm2.getUserId())
        assertEquals("user@example.test", sm2.getEmail())
        assertEquals("Test User", sm2.getDisplayName())
        assertEquals("test-public-key", sm2.getPublicKey())
    }

    /**
     * Push token and its synced flag must round-trip.
     */
    @Test
    fun pushToken_roundTripsWithSyncedFlag() {
        val sm = makeSessionManager()

        sm.savePushToken("fcm-token-abc")
        assertEquals("fcm-token-abc", sm.getPushToken())
        assertEquals(SessionManager.PUSH_TYPE_FCM, sm.getPushType())
        assertFalse("savePushToken should reset synced flag", sm.isPushTokenSynced())

        // Simulate successful sync
        sm.setPushTokenSynced(true)
        assertTrue("Synced flag should be true after setPushTokenSynced", sm.isPushTokenSynced())

        // Survives new instance
        val sm2 = makeSessionManager()
        assertEquals("fcm-token-abc", sm2.getPushToken())
        assertTrue(sm2.isPushTokenSynced())
    }

    /**
     * Device ID should be generated once and persisted. Calling getDeviceId() a second
     * time should return the same value, even from a new SessionManager instance.
     */
    @Test
    fun deviceId_generatedOnceAndPersists() {
        val sm1 = makeSessionManager()
        val id1 = sm1.getDeviceId()
        assertNotNull(id1)
        assertTrue("Device ID should be a non-empty string", id1.isNotEmpty())

        // Same instance returns same ID
        assertEquals(id1, sm1.getDeviceId())

        // New instance returns same ID
        val sm2 = makeSessionManager()
        assertEquals(id1, sm2.getDeviceId())
    }

    /**
     * clearSession() must remove tokens but preserve the device ID.
     */
    @Test
    fun clearSession_preservesDeviceIdButRemovesTokens() {
        val sm = makeSessionManager()

        sm.saveTokens("clear-test-access", "clear-test-refresh", 3600L)
        sm.saveUser("user-id", "e@x.com", "Name", "pub")
        val deviceIdBefore = sm.getDeviceId()

        sm.clearSession()

        // Tokens gone
        assertNull(sm.getAccessToken())
        assertNull(sm.getRefreshToken())
        assertNull(sm.getUserId())
        assertNull(sm.getEmail())

        // Device ID preserved
        assertEquals(
            "clearSession must not clear device ID",
            deviceIdBefore,
            sm.getDeviceId()
        )
    }

    /**
     * isLoggedIn() depends on both access token AND user ID being present.
     */
    @Test
    fun isLoggedIn_requiresBothTokenAndUserId() {
        val sm = makeSessionManager()

        // Neither → false
        sm.clearSession()
        assertFalse(sm.isLoggedIn())

        // Only token → false
        sm.saveTokens("only-access", "only-refresh", 3600L)
        assertFalse("Token without userId should not count as logged in", sm.isLoggedIn())

        // Token + userId → true
        sm.saveUser("uid", "e@x.com", "Name", "pub")
        assertTrue(sm.isLoggedIn())
    }

    /**
     * Subscription tier round-trips.
     */
    @Test
    fun subscriptionTier_roundTrips() {
        val sm = makeSessionManager()
        // Reset to default since clearSession() doesn't clear the tier key.
        sm.setSubscriptionTier("Free")
        assertEquals("Free", sm.getSubscriptionTier())

        sm.setSubscriptionTier("Premium")
        assertEquals("Premium", sm.getSubscriptionTier())

        // Survives new instance
        val sm2 = makeSessionManager()
        assertEquals("Premium", sm2.getSubscriptionTier())

        // Cleanup for subsequent tests in the same process
        sm2.setSubscriptionTier("Free")
    }

    /**
     * Device registration flag round-trips and is tied to the current device ID.
     */
    @Test
    fun deviceRegistered_flagRoundTrips() {
        val sm = makeSessionManager()
        // Force deviceId to be generated first
        sm.getDeviceId()

        assertFalse("Default: not registered", sm.isDeviceRegistered())

        sm.setDeviceRegistered(true)
        assertTrue("After setDeviceRegistered(true), must be registered", sm.isDeviceRegistered())

        // Survives new instance
        val sm2 = makeSessionManager()
        assertTrue(sm2.isDeviceRegistered())

        sm2.setDeviceRegistered(false)
        assertFalse(sm2.isDeviceRegistered())
    }

    /**
     * App lock PIN hash round-trips.
     */
    @Test
    fun appLockPinHash_roundTrips() {
        val sm = makeSessionManager()

        assertNull("Default: no PIN hash", sm.getAppLockPinHash())

        sm.setAppLockPinHash("sha256-pin-hash-value")
        assertEquals("sha256-pin-hash-value", sm.getAppLockPinHash())

        sm.clearAppLockPinHash()
        assertNull(sm.getAppLockPinHash())
    }

    /**
     * Helper: construct a new SessionManager with a MasterKey. This mirrors what
     * AppModule does in production — each SessionManager created here reads/writes
     * the same encrypted prefs file under the app's internal storage.
     */
    private fun makeSessionManager(): SessionManager {
        // A dagger.Lazy, as AppModule passes it: the key is made at the store's
        // first open, not here.
        val masterKey = dagger.Lazy {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return SessionManager(context, masterKey)
    }
}
