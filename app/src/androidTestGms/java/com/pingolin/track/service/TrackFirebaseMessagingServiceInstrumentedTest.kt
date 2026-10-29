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

package com.pingolin.track.service

import android.content.Context
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for TrackFirebaseMessagingService.
 *
 * They run where FirebaseMessagingService (which extends android.app.Service)
 * can actually be instantiated and its @Inject lateinit fields populated, which
 * a JVM test can't do.
 *
 * Core behavior covered:
 * - onNewToken marks device as not registered (setDeviceRegistered(false))
 * - onNewToken sets pushTokenSynced=false
 * - onNewToken saves the token + type=FCM
 *
 * The network-dependent behavior (logged-in-triggered sync calling TrackApi)
 * and tickle dispatch are not verified here: exercising them would require a
 * fully-wired Retrofit against a fake server. The tickle's content half lives in
 * NotificationTickleFetcher and is covered by NotificationTickleFetcherTest in
 * src/test.
 *
 * This test does NOT go through Hilt injection because TestModule is
 * intentionally minimal. Instead, we construct a real SessionManager with
 * a MasterKey and poke it at the TrackFirebaseMessagingService instance via
 * reflection — this is the exact path Hilt would take in production, just
 * without the Application.onCreate/Service.onCreate hooks.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class TrackFirebaseMessagingServiceInstrumentedTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var sessionManager: SessionManager

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        sessionManager = buildSessionManager()
        // Start from a clean-ish session so pre-existing state doesn't affect assertions
        sessionManager.clearSession()
    }

    @After
    fun tearDown() {
        try {
            sessionManager.clearSession()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Invoking onNewToken should:
     * - savePushToken(token) → getPushToken() returns the new token
     * - setDeviceRegistered(false) so next ensureDeviceRegistered() hits the backend
     * - setPushTokenSynced(false) so a sync is attempted
     *
     * onNewToken also calls syncPushTokenWithBackend if isLoggedIn() — we're not
     * logged in here (clearSession above), so that path short-circuits and only
     * the local state changes matter.
     */
    @Test
    fun onNewToken_persistsTokenAndResetsSyncFlags() {
        // Pre-state: previous run may have left device registered. Force a known state.
        sessionManager.setDeviceRegistered(true)
        sessionManager.setPushTokenSynced(true)
        assertTrue("Pre-condition: device must start registered", sessionManager.isDeviceRegistered())
        assertTrue("Pre-condition: push must start synced", sessionManager.isPushTokenSynced())

        val service = buildServiceWithInjections()
        service.onNewToken("new-fcm-token-xyz")

        // onNewToken calls:
        //   sessionManager.savePushToken(token)       → token + type=FCM, synced=false
        //   sessionManager.setDeviceRegistered(false)
        //   sessionManager.setPushTokenSynced(false)  (redundant but present)

        assertFalse(
            "onNewToken must mark device as not registered",
            sessionManager.isDeviceRegistered()
        )
        assertFalse(
            "onNewToken must set pushTokenSynced = false",
            sessionManager.isPushTokenSynced()
        )
        assertEquals("new-fcm-token-xyz", sessionManager.getPushToken())
        assertEquals(SessionManager.PUSH_TYPE_FCM, sessionManager.getPushType())
    }

    /**
     * savePushToken resets the synced flag — a subsequent setPushTokenSynced(true)
     * must persist. This is the success path after a backend sync call.
     */
    @Test
    fun pushTokenSync_setSyncedTrueAfterSave_persists() {
        sessionManager.savePushToken("sync-round-trip-token")
        assertFalse(sessionManager.isPushTokenSynced())

        sessionManager.setPushTokenSynced(true)
        assertTrue(sessionManager.isPushTokenSynced())

        // Round-trip via a new SessionManager instance to make sure it survives
        val sm2 = buildSessionManager()
        assertTrue(sm2.isPushTokenSynced())
        assertEquals("sync-round-trip-token", sm2.getPushToken())
    }

    // ===== Helpers =====

    private fun buildSessionManager(): SessionManager {
        // A dagger.Lazy, as AppModule passes it: the key is made at the store's
        // first open, not here.
        val masterKey = dagger.Lazy {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        return SessionManager(context, masterKey)
    }

    /**
     * Builds TrackFirebaseMessagingService with the @Inject lateinit fields
     * populated by reflection. attachBaseContext is called so applicationContext
     * etc. work.
     *
     * Using reflection here because FirebaseMessagingService's bindService path
     * requires a running app process with Firebase infrastructure, which isn't
     * available in an instrumented test. The fields we populate are exactly the
     * ones Hilt would inject via the generated member-injector.
     */
    private fun buildServiceWithInjections(): TrackFirebaseMessagingService {
        val service = TrackFirebaseMessagingService()

        // Attach the app context so service.applicationContext works
        val attachBaseContext = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attachBaseContext.isAccessible = true
        attachBaseContext.invoke(service, context)

        val sessionManagerField = TrackFirebaseMessagingService::class.java
            .getDeclaredField("sessionManager")
        sessionManagerField.isAccessible = true
        sessionManagerField.set(service, sessionManager)

        val trackApiField = TrackFirebaseMessagingService::class.java
            .getDeclaredField("tracksApi")
        trackApiField.isAccessible = true
        trackApiField.set(
            service,
            io.mockk.mockk<com.pingolin.track.data.remote.api.TrackApi>(relaxed = true)
        )

        val tickleFetcherField = TrackFirebaseMessagingService::class.java
            .getDeclaredField("tickleFetcher")
        tickleFetcherField.isAccessible = true
        tickleFetcherField.set(
            service,
            io.mockk.mockk<NotificationTickleFetcher>(relaxed = true)
        )

        return service
    }
}
