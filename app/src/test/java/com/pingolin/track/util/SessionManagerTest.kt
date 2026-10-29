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
import android.content.SharedPreferences
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SessionManager.
 *
 * Tests cover:
 * - Read/write round-trip for tokens, user info, push settings
 * - clearSession removes all user+token keys but preserves device ID
 * - Environment get/set
 * - Subscription tier default (Free) + update
 * - Push token synced flag
 *
 * Notes:
 * - EncryptedSharedPreferences creation requires Android Keystore (real device).
 *   We use a subclass that overrides `prefs` with a plain in-memory SharedPreferences
 *   mock. The encryption itself is tested in androidTest/.
 */
class SessionManagerTest {

    @MockK
    private lateinit var context: Context

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var sessionManager: TestableSessionManager

    /**
     * Minimal SharedPreferences backed by a HashMap — good enough for read/write
     * round-trip tests. We do NOT simulate listener callbacks or commit atomicity.
     */
    private class FakeSharedPreferences : SharedPreferences {
        val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = data.toMap()
        override fun getString(key: String?, defValue: String?): String? =
            data[key] as? String ?: defValue
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

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(k: String?, v: String?) = apply { if (k != null) pending[k] = v }
            override fun putStringSet(k: String?, v: Set<String>?) = apply { if (k != null) pending[k] = v }
            override fun putInt(k: String?, v: Int) = apply { if (k != null) pending[k] = v }
            override fun putLong(k: String?, v: Long) = apply { if (k != null) pending[k] = v }
            override fun putFloat(k: String?, v: Float) = apply { if (k != null) pending[k] = v }
            override fun putBoolean(k: String?, v: Boolean) = apply { if (k != null) pending[k] = v }
            override fun remove(k: String?) = apply { if (k != null) removed.add(k) }
            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
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
     * Testable SessionManager that returns our fake SharedPreferences instead of
     * creating EncryptedSharedPreferences (which requires the Android Keystore).
     */
    private class TestableSessionManager(
        context: Context,
        override val prefs: SharedPreferences
    ) : SessionManager(context)

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakePrefs = FakeSharedPreferences()
        sessionManager = TestableSessionManager(context, fakePrefs)
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    // =============================================================================
    // DISTANCE UNIT
    // =============================================================================

    /**
     * Unset, the unit follows the phone's REGION, never the language the app's words are in: an English
     * build on a German phone stays on km; en-US and en-GB get miles.
     */
    @Test
    fun `the distance unit's default follows the phone's region`() {
        val original = java.util.Locale.getDefault()
        try {
            for ((tag, miles) in listOf("de-DE" to false, "en-US" to true, "en-GB" to true, "fr-FR" to false, "zh-CN" to false)) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals(tag, miles, sessionManager.useMiles())
            }
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    // =============================================================================
    // TOKENS
    // =============================================================================

    @Test
    fun `saveTokens and getAccessToken round-trip`() {
        sessionManager.saveTokens("access-xyz", "refresh-abc", expiresIn = 3600)

        assertEquals("access-xyz", sessionManager.getAccessToken())
        assertEquals("refresh-abc", sessionManager.getRefreshToken())
    }

    @Test
    fun `saveAccessToken updates token only`() {
        sessionManager.saveTokens("a1", "r1", 60)
        sessionManager.saveAccessToken("a2")

        assertEquals("a2", sessionManager.getAccessToken())
        assertEquals("r1", sessionManager.getRefreshToken())
    }

    @Test
    fun `isTokenExpired returns true when no token saved`() {
        assertTrue(sessionManager.isTokenExpired())
    }

    // =============================================================================
    // USER DATA
    // =============================================================================

    @Test
    fun `saveUser and getters round-trip`() {
        sessionManager.saveUser(
            userId = "0a0a0000-0000-4000-8000-0000000000a1",
            email = "test@pingolin.test",
            displayName = "Alice",
            publicKey = "pk-abc"
        )

        assertEquals("0a0a0000-0000-4000-8000-0000000000a1", sessionManager.getUserId())
        assertEquals("test@pingolin.test", sessionManager.getEmail())
        assertEquals("Alice", sessionManager.getDisplayName())
        assertEquals("pk-abc", sessionManager.getPublicKey())
    }

    @Test
    fun `getUserId returns null when not saved`() {
        assertNull(sessionManager.getUserId())
    }

    @Test
    fun `getSubscriptionTier defaults to Free`() {
        assertEquals("Free", sessionManager.getSubscriptionTier())
    }

    @Test
    fun `setSubscriptionTier persists value`() {
        sessionManager.setSubscriptionTier("Premium")
        assertEquals("Premium", sessionManager.getSubscriptionTier())
    }

    // =============================================================================
    // PUSH TOKEN
    // =============================================================================

    @Test
    fun `savePushToken round-trip`() {
        sessionManager.savePushToken("fcm-tok-1")
        assertEquals("fcm-tok-1", sessionManager.getPushToken())
    }

    @Test
    fun `isPushTokenSynced defaults to false`() {
        assertFalse(sessionManager.isPushTokenSynced())
    }

    @Test
    fun `setPushTokenSynced persists true`() {
        sessionManager.setPushTokenSynced(true)
        assertTrue(sessionManager.isPushTokenSynced())
    }

    @Test
    fun `savePushSettings stores type and endpoint`() {
        sessionManager.savePushSettings("tok", SessionManager.PUSH_TYPE_UNIFIED_PUSH, "https://up.example/")
        assertEquals("tok", sessionManager.getPushToken())
        assertEquals(SessionManager.PUSH_TYPE_UNIFIED_PUSH, sessionManager.getPushType())
        assertEquals("https://up.example/", sessionManager.getPushEndpoint())
    }

    @Test
    fun `clearPushToken resets push fields`() {
        sessionManager.savePushSettings("tok", SessionManager.PUSH_TYPE_FCM, null)
        sessionManager.setPushTokenSynced(true)

        sessionManager.clearPushToken()

        assertNull(sessionManager.getPushToken())
        assertFalse(sessionManager.isPushTokenSynced())
    }

    // =============================================================================
    // DEVICE
    // =============================================================================

    @Test
    fun `saveDeviceId round-trip`() {
        sessionManager.saveDeviceId("dev-1")
        assertEquals("dev-1", sessionManager.getDeviceIdOrNull())
    }

    @Test
    fun `setDeviceRegistered round-trip`() {
        sessionManager.setDeviceRegistered(true)
        assertTrue(sessionManager.isDeviceRegistered())

        sessionManager.setDeviceRegistered(false)
        assertFalse(sessionManager.isDeviceRegistered())
    }

    // =============================================================================
    // CLEAR
    // =============================================================================

    @Test
    fun `clearSession removes user + tokens but keeps device ID`() {
        sessionManager.saveDeviceId("dev-123")
        sessionManager.saveTokens("a1", "r1", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "D", "pk")
        sessionManager.savePushToken("tok")

        sessionManager.clearSession()

        assertNull(sessionManager.getAccessToken())
        assertNull(sessionManager.getRefreshToken())
        assertNull(sessionManager.getUserId())
        assertNull(sessionManager.getEmail())
        assertNull(sessionManager.getDisplayName())
        assertNull(sessionManager.getPushToken())
        // Device ID persists across logout
        assertEquals("dev-123", sessionManager.getDeviceIdOrNull())
    }

    @Test
    fun `an account waiting for an admin's approval goes with its session`() {
        assertFalse("nothing kept is no waiting", sessionManager.isRegistrationPending())
        sessionManager.keepRegistrationReview(pending = true, rejected = false)
        assertTrue(sessionManager.isRegistrationPending())

        sessionManager.clearSession()

        assertFalse("the next account isn't held up", sessionManager.isRegistrationPending())
    }

    @Test
    fun `keeping a held review stops all that runs, wipes nothing, and an approval stops nothing`() {
        mockkObject(com.pingolin.track.service.TrackingStop)
        try {
            every { com.pingolin.track.service.TrackingStop.all(any()) } just Runs
            sessionManager.saveTokens("access-xyz", "refresh-abc", expiresIn = 3600)
            sessionManager.saveUser(
                userId = "0a0a0000-0000-4000-8000-0000000000a1",
                email = "test@pingolin.test",
                displayName = "Alice",
                publicKey = "pk-abc"
            )
            sessionManager.setTrackingEnabled(true)

            sessionManager.keepRegistrationReview(pending = false, rejected = false)
            verify(exactly = 0) { com.pingolin.track.service.TrackingStop.all(any()) }

            // Sharing already running when a review is first kept (started
            // by an earlier version and restored by an update) is stopped.
            sessionManager.keepRegistrationReview(pending = true, rejected = false)
            verify(exactly = 1) { com.pingolin.track.service.TrackingStop.all(context) }
            sessionManager.keepRegistrationReview(pending = false, rejected = true)
            verify(exactly = 2) { com.pingolin.track.service.TrackingStop.all(context) }

            assertTrue("the session stays", sessionManager.isLoggedIn())
            assertEquals("access-xyz", sessionManager.getAccessToken())
            assertEquals("refresh-abc", sessionManager.getRefreshToken())
            assertTrue("and what was chosen, for the approval", sessionManager.isTrackingEnabled())
        } finally {
            unmockkObject(com.pingolin.track.service.TrackingStop)
        }
    }

    @Test
    fun `a default circle switched off is settled, so the first circle is not made the default again`() {
        assertFalse("nothing ever settled", sessionManager.isDefaultCircleSettled())
        sessionManager.setDefaultCircleId("c-1")
        assertTrue(sessionManager.isDefaultCircleSettled())

        sessionManager.setDefaultCircleId(null)
        assertNull(sessionManager.getDefaultCircleId())
        assertTrue("switched off is settled too", sessionManager.isDefaultCircleSettled())

        sessionManager.clearSession()
        assertFalse("the next account starts unsettled", sessionManager.isDefaultCircleSettled())
    }

    @Test
    fun `whether this build read the review at all is kept, and goes with the session`() {
        assertFalse("an earlier build's session: never read", sessionManager.isRegistrationReviewRead())
        sessionManager.keepRegistrationReview(pending = false, rejected = false)
        assertTrue(sessionManager.isRegistrationReviewRead())

        sessionManager.clearSession()

        assertFalse(sessionManager.isRegistrationReviewRead())
    }

    @Test
    fun `an account an admin turned down is unapproved too, goes with its session, and isn't tracked`() {
        sessionManager.saveTokens("access-xyz", "refresh-abc", expiresIn = 3600)
        sessionManager.saveUser(
            userId = "0a0a0000-0000-4000-8000-0000000000a1",
            email = "test@pingolin.test",
            displayName = "Alice",
            publicKey = "pk-abc"
        )
        sessionManager.setTrackingEnabled(true)
        assertFalse(sessionManager.isRegistrationUnapproved())
        assertTrue(sessionManager.shouldBeTracking())

        sessionManager.keepRegistrationReview(pending = false, rejected = true)
        assertTrue(sessionManager.isRegistrationRejected())
        assertTrue(sessionManager.isRegistrationUnapproved())
        assertFalse("the keep-alive, its geofence, the watchdog and boot ask this one rule", sessionManager.shouldBeTracking())

        sessionManager.clearSession()
        assertFalse("the next account isn't held up", sessionManager.isRegistrationRejected())
    }

    @Test
    fun `an account waiting for an admin's approval isn't tracked, by any restart path`() {
        sessionManager.saveTokens("access-xyz", "refresh-abc", expiresIn = 3600)
        sessionManager.saveUser(
            userId = "0a0a0000-0000-4000-8000-0000000000a1",
            email = "test@pingolin.test",
            displayName = "Alice",
            publicKey = "pk-abc"
        )
        sessionManager.setTrackingEnabled(true)
        assertTrue(sessionManager.shouldBeTracking())

        sessionManager.keepRegistrationReview(pending = true, rejected = false)

        // The keep-alive, its geofence, the watchdog and boot all ask this one rule.
        assertFalse(sessionManager.shouldBeTracking())
    }

    @Test
    fun `the guardian line's state follows each answer from the server, as the map's flow`() {
        // /auth/me answers after the map's first layout and its resume: the map binds the line from this flow.
        assertEquals(SessionManager.GuardianDisclosure(managed = false, handoverDate = null), sessionManager.guardianDisclosure.value)
        sessionManager.setGuardianManaged(true)
        assertTrue(sessionManager.isGuardianManaged())
        assertEquals(SessionManager.GuardianDisclosure(managed = true, handoverDate = null), sessionManager.guardianDisclosure.value)
        sessionManager.setGuardianHandoverDate("2030-01-31")
        assertEquals(SessionManager.GuardianDisclosure(managed = true, handoverDate = "2030-01-31"), sessionManager.guardianDisclosure.value)
        sessionManager.setGuardianManaged(false)
        assertEquals(SessionManager.GuardianDisclosure(managed = false, handoverDate = "2030-01-31"), sessionManager.guardianDisclosure.value)
    }

    @Test
    fun `whether a guardian holds the account goes with its session, flow and all`() {
        sessionManager.setGuardianManaged(true)
        sessionManager.setGuardianHandoverDate("2030-01-31")

        sessionManager.clearSession()

        // Kept, the next account's map would say "visible to guardian" until its own
        // /auth/me answered.
        assertFalse(sessionManager.isGuardianManaged())
        assertNull(sessionManager.getGuardianHandoverDate())
        assertEquals(SessionManager.GuardianDisclosure(managed = false, handoverDate = null), sessionManager.guardianDisclosure.value)
    }

    @Test
    fun `the flow starts from what is stored`() {
        fakePrefs.data["guardian_managed"] = true
        val fresh = TestableSessionManager(context, fakePrefs)
        assertEquals(SessionManager.GuardianDisclosure(managed = true, handoverDate = null), fresh.guardianDisclosure.value)
    }

    // =============================================================================
    // ENVIRONMENT
    // =============================================================================

    @Test
    fun `build environment comes from BuildConfig, not from a preference`() {
        assertEquals(
            SessionManager.Environment.valueOf(com.pingolin.track.BuildConfig.ENVIRONMENT),
            sessionManager.getBuildEnvironment()
        )
    }

    // =============================================================================
    // DISPLAY NAME
    // =============================================================================

    @Test
    fun `setDisplayName persists value`() {
        sessionManager.setDisplayName("Bob")
        assertEquals("Bob", sessionManager.getDisplayName())
    }

    @Test
    fun `setEmail replaces the address kept from sign-in, and a sign-out still takes it`() {
        // The e-mail trust check: AuthRepository.validateSession keeps the address /auth/me has now.
        sessionManager.saveUser("0e000000-0000-4000-8000-0000000000e1", "old@x.test", "Bob", "pk")
        sessionManager.setEmail("new@x.test")
        assertEquals("new@x.test", sessionManager.getEmail())
        sessionManager.clearSession()
        assertEquals(null, sessionManager.getEmail())
    }

    // ===== forced sign-out: once per session =====
    //
    // A burst of concurrent 401s runs the teardown once, not once per
    // caller: otherwise one event gets duplicated journal entries and
    // duplicated notifications, and the entry that explains a sign-out is
    // the most valuable one there is.

    @Test
    fun `a forced sign-out runs the teardown once however many callers arrive`() {
        sessionManager.saveAccessToken("token")   // a session exists
        var cleanups = 0
        sessionManager.setForceLogoutStopCallback { cleanups++ }

        repeat(5) { sessionManager.triggerForceLogout() }

        assertEquals("teardown must run once per session, not once per caller", 1, cleanups)
    }

    @Test
    fun `a forced sign-out runs once even when callers arrive concurrently`() {
        sessionManager.saveAccessToken("token")
        val cleanups = java.util.concurrent.atomic.AtomicInteger(0)
        sessionManager.setForceLogoutStopCallback { cleanups.incrementAndGet() }

        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(8)
        repeat(8) {
            Thread {
                start.await()
                sessionManager.triggerForceLogout()
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(1, cleanups.get())
    }

    @Test
    fun `signing in again re-arms the guard so a later sign-out is not swallowed`() {
        sessionManager.saveAccessToken("first-session")
        var cleanups = 0
        sessionManager.setForceLogoutStopCallback { cleanups++ }
        sessionManager.triggerForceLogout()
        assertEquals(1, cleanups)

        // New session established.
        sessionManager.saveAccessToken("second-session")
        sessionManager.triggerForceLogout()

        assertEquals("the second session's sign-out must not be swallowed", 2, cleanups)
    }

    // ===== forced sign-out: end the SESSION, keep the ACCOUNT =====
    //
    // A forced sign-out fires when the server refuses ONE refresh —
    // which can be a rotation race (two refresh paths, the loser holding
    // a retired token) while the server's session row is live the whole
    // time. A teardown that wiped keys, the database and the cached
    // files would turn a recoverable outage into a re-import. These
    // assert what is still on the device afterwards, not merely that
    // some hook ran.

    @Test
    fun `a server-rejected sign-out drops the credentials`() {
        sessionManager.saveTokens("access", "refresh", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "D", "pk")

        sessionManager.triggerForceLogout()

        assertNull("the access token is what the server refused", sessionManager.getAccessToken())
        assertNull("keeping a rotated refresh token would replay it", sessionManager.getRefreshToken())
        assertFalse(
            "isLoggedIn() must answer false so the workers stand down",
            sessionManager.isLoggedIn()
        )
    }

    @Test
    fun `a server-rejected sign-out keeps the account, so signing in again restores the device`() {
        sessionManager.saveTokens("access", "refresh", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "Dana", "pub-key")
        sessionManager.saveEncryptedPrivateKey("sealed-private-key")
        sessionManager.setAppLockMethod(SessionManager.APP_LOCK_PIN)
        sessionManager.setSubscriptionTier("Premium")
        sessionManager.setDefaultCircleId("circle-1")

        sessionManager.triggerForceLogout()

        // The user id is the load-bearing one: AuthRepository compares the
        // incoming id against it to decide whether a sign-in is the same
        // account returning or a switch that must wipe first. Nulling it here
        // would leave that check unable to fire after a forced sign-out.
        assertEquals("0a0a0000-0000-4000-8000-0000000000a1", sessionManager.getUserId())
        assertEquals("e@e", sessionManager.getEmail())
        assertEquals("Dana", sessionManager.getDisplayName())
        assertEquals("pub-key", sessionManager.getPublicKey())
        assertEquals("sealed-private-key", sessionManager.getEncryptedPrivateKey())
        assertEquals(SessionManager.APP_LOCK_PIN, sessionManager.getAppLockMethod())
        assertEquals("Premium", sessionManager.getSubscriptionTier())
        assertEquals("circle-1", sessionManager.getDefaultCircleId())
    }

    @Test
    fun `a server-rejected sign-out stops tracking but records that it was on`() {
        sessionManager.saveTokens("access", "refresh", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "D", "pk")
        sessionManager.setTrackingEnabled(true)

        sessionManager.triggerForceLogout()

        assertFalse(
            "nothing can be uploaded without a session, so stop sampling",
            sessionManager.isTrackingEnabled()
        )
        assertTrue(
            "without this the app cannot tell 'signed out while sharing' from " +
                "'signed out and not sharing', and cannot resume on sign-in",
            sessionManager.trackingWantedAtSignOut()
        )
    }

    @Test
    fun `a sign-out with tracking off does not claim it was on`() {
        sessionManager.saveTokens("access", "refresh", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "D", "pk")
        sessionManager.setTrackingEnabled(false)

        sessionManager.triggerForceLogout()

        assertFalse(sessionManager.trackingWantedAtSignOut())
    }

    @Test
    fun `a deliberate sign-out still removes the account`() {
        // clearSession is the OTHER path — AuthRepository.logout and the
        // account-switch wipe. If someone ever collapses it into
        // clearCredentialsOnly, a device handed to the next user keeps the
        // previous user's identity. Retention belongs to the forced path only.
        sessionManager.saveTokens("access", "refresh", 3600)
        sessionManager.saveUser("0a0a0000-0000-4000-8000-0000000000a1", "e@e", "D", "pk")

        sessionManager.clearSession()

        assertNull("a deliberate logout must not leave the user id behind", sessionManager.getUserId())
        assertNull(sessionManager.getEmail())
        assertNull(sessionManager.getPublicKey())
    }


    // ===== the rotated token must actually reach disk =====

    @Test
    fun `saving a refresh token reports success only when it reads back`() {
        assertTrue(sessionManager.saveRefreshToken("rotated-token"))
        assertEquals("rotated-token", sessionManager.getRefreshToken())
    }

    @Test
    fun `a persist that does not stick is reported as a failure`() {
        // The server has already rotated by this point. A write that
        // silently fails — the keystore being unavailable, for
        // instance ("Required super decryption key is not in memory
        // ... LOCKED") — leaves us desynced from the server and the
        // next refresh is a replay it rejects. That is
        // indistinguishable from success unless it is checked.
        // NOTE the delegation trap: `by fakePrefs.edit()` would make
        // putString() return the DELEGATE's editor, so commit() would
        // land on the real one and the override would never run. Each
        // builder method has to return `this`.
        val swallowing = object : SharedPreferences by fakePrefs {
            override fun edit(): SharedPreferences.Editor =
                object : SharedPreferences.Editor {
                    override fun putString(k: String?, v: String?) = this
                    override fun putStringSet(k: String?, v: MutableSet<String>?) = this
                    override fun putInt(k: String?, v: Int) = this
                    override fun putLong(k: String?, v: Long) = this
                    override fun putFloat(k: String?, v: Float) = this
                    override fun putBoolean(k: String?, v: Boolean) = this
                    override fun remove(k: String?) = this
                    override fun clear() = this
                    override fun commit(): Boolean = false
                    override fun apply() {}
                }
        }
        val manager = TestableSessionManager(context, swallowing)

        assertFalse("a write that did not commit must not report success",
            manager.saveRefreshToken("never-lands"))
    }


    // ---- the account id, canonical (AccountIds) ------------------------------------

    @Test
    fun `an account id saved in another spelling reads back canonical, and is written back so`() {
        for (spelling in listOf(
            "0A0A0000-0000-4000-8000-0000000000A1", "0a0a00000000400080000000000000a1",
            "{0a0a0000-0000-4000-8000-0000000000a1}", "urn:uuid:0A0A0000-0000-4000-8000-0000000000A1",
        )) {
            fakePrefs.data["user_id"] = spelling

            assertEquals(spelling, "0a0a0000-0000-4000-8000-0000000000a1", sessionManager.getUserId())
            assertEquals(spelling, "0a0a0000-0000-4000-8000-0000000000a1", fakePrefs.data["user_id"])
            assertFalse(spelling, sessionManager.savedAccountIdInvalid())
        }
    }

    @Test
    fun `a saved account id that isn't a UUID is no account, and the launch ends the session`() {
        for (id in listOf("u1", "{0a0a00000000400080000000000000a1}", " 0a0a0000-0000-4000-8000-0000000000a1")) {
            fakePrefs.data["user_id"] = id

            assertNull(id, sessionManager.getUserId())
            assertTrue(id, sessionManager.savedAccountIdInvalid())
            assertEquals("never rewritten", id, fakePrefs.data["user_id"])
        }
        fakePrefs.data.remove("user_id")
        assertFalse(sessionManager.savedAccountIdInvalid())
    }

    @Test
    fun `a sign-in saves the canonical id`() {
        sessionManager.saveUser("0A0A0000-0000-4000-8000-0000000000A1", "e@e", "D", "pk")

        assertEquals("0a0a0000-0000-4000-8000-0000000000a1", fakePrefs.data["user_id"])
    }

    // =============================================================================
    // THE LEGACY PAUSE TRACKING SWITCH: one account's, parked while another is signed in
    // =============================================================================

    private val ann = "a1b2c3d4-0000-4000-8000-0000000000a1"
    private val bob = "b1b2c3d4-0000-4000-8000-0000000000b2"

    /** As an earlier build left it: the switch, and no record of whose it
     * is.
     */
    private fun oldSwitchOn() {
        fakePrefs.data["tracking_paused"] = true
    }

    /** A sign-in as AuthRepository.finishSignIn does it: the note on the switch, then the new account saved. */
    private fun signIn(userId: String) {
        sessionManager.noteTrackingPauseOwnerBeforeSignIn()
        sessionManager.saveUser(userId, "$userId@example.com", null, "pk")
    }

    @Test
    fun `the same account signing back in keeps the old switch - no silent resume`() {
        oldSwitchOn()
        signIn(ann)
        assertTrue("the move records it as the signed-in account's", sessionManager.claimTrackingPauseFor(ann))
        sessionManager.clearSession()

        signIn(ann)

        assertTrue(sessionManager.isTrackingPaused())
        assertTrue(sessionManager.claimTrackingPauseFor(ann))
    }

    @Test
    fun `another account tracks as it chose while the switch waits for its owner, who finds it again`() {
        // Ann's move is pending (claimed, not done), she signs out, Bob signs in on this phone.
        oldSwitchOn()
        signIn(ann)
        sessionManager.claimTrackingPauseFor(ann)
        sessionManager.clearSession()

        signIn(bob)
        assertFalse("Bob tracks normally", sessionManager.isTrackingPaused())
        assertFalse("nothing of it moves onto Bob's circles", sessionManager.claimTrackingPauseFor(bob))
        assertEquals("still kept for Ann", true, fakePrefs.data["tracking_paused"])

        sessionManager.clearSession()
        signIn(ann)
        assertTrue("Ann is held again, and her move goes on", sessionManager.isTrackingPaused())
        assertTrue(sessionManager.claimTrackingPauseFor(ann))
    }

    @Test
    fun `after a forced sign-out the id still says whose it is`() {
        // The move never ran (nobody claimed it); a forced sign-out keeps the account id.
        oldSwitchOn()
        sessionManager.saveUser(ann, "ann@example.com", null, "pk")
        sessionManager.clearCredentialsOnly()

        signIn(bob)
        assertFalse("parked for Ann, not Bob's", sessionManager.isTrackingPaused())

        signIn(ann)
        assertTrue(sessionManager.isTrackingPaused())
    }

    @Test
    fun `a switch nobody can place holds for whoever signs in, and the move claims it`() {
        // Updated while signed out: whose it was can't be told. Most likely the same person: it
        // holds, and the move makes it a visible pause of the circles of whoever signed in.
        oldSwitchOn()
        signIn(ann)
        assertTrue(sessionManager.isTrackingPaused())
        assertTrue(sessionManager.claimTrackingPauseFor(ann))

        // From then on it is Ann's: Bob signing in finds it parked.
        sessionManager.clearSession()
        signIn(bob)
        assertFalse(sessionManager.isTrackingPaused())
    }

    @Test
    fun `the move claims an unclaimed switch, and another account's stays put`() {
        oldSwitchOn()
        assertTrue(sessionManager.claimTrackingPauseFor(ann))
        assertTrue(sessionManager.claimTrackingPauseFor(ann))

        assertFalse(sessionManager.claimTrackingPauseFor(bob))
        assertEquals("parked, never dropped", true, fakePrefs.data["tracking_paused"])
        assertEquals(ann, fakePrefs.data["tracking_paused_account"])
    }

    @Test
    fun `the note about the move shows once`() {
        assertNull(sessionManager.takePauseTrackingMovedNote())
        sessionManager.markPauseTrackingMovedNote(hadCircles = true)
        assertEquals(true, sessionManager.takePauseTrackingMovedNote())
        assertNull(sessionManager.takePauseTrackingMovedNote())
        sessionManager.markPauseTrackingMovedNote(hadCircles = false)
        assertEquals(false, sessionManager.takePauseTrackingMovedNote())
    }
}
