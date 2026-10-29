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
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.pingolin.track.data.remote.GeocodeTokenManager
import com.pingolin.track.di.AppModule
import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A storage refusal never crashes. With keystore2 stopped, a MasterKey made while Hilt injects
 * MainActivity would crash every start, since making it is a Keystore call. So nothing is made
 * with a Keystore call at injection: each holder of the MasterKey takes it lazily and makes it
 * at its store's first use, inside the catch that turns a refusal into the storage family. The
 * starts and every background entry then say so, or wait, instead of crashing, and never take
 * it for "signed out".
 */
class StorageRefusalNeverCrashesTest {

    /** Android's own, with keystore2 down. */
    private val down = NullPointerException("Attempt to invoke interface method 'android.os.IBinder android.system.keystore2.IKeystoreService.asBinder()' on a null object reference")

    /** A MasterKey that can't be made now; counts the asks. */
    private inner class DownKey : dagger.Lazy<MasterKey> {
        var asked = 0
        override fun get(): MasterKey {
            asked++
            throw down
        }
    }

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `the encrypted store opens at its first use - a refusal is the storage family's there, and the next use tries again`() {
        var opens = 0
        var up = false
        val store = LazySecurePrefs {
            opens++
            if (!up) throw down
            InMemoryPrefs()
        }
        assertEquals("nothing opened while made", 0, opens)
        val refused = assertThrows(SecureStorageUnavailableException::class.java) { store.getString("k", null) }
        assertSame(down, refused.cause)
        assertTrue(DatabaseUnavailable.isUnavailable(refused))
        up = true
        assertNull(store.getString("k", null))
        store.edit().putString("k", "v").commit()
        assertEquals("v", store.getString("k", null))
        assertEquals("opened once it could, then kept", 2, opens)
    }

    @Test
    fun `no holder of the MasterKey makes it while made - each at its store's first use, as the storage family`() {
        val key = DownKey()
        val secure = AppModule.provideEncryptedSharedPreferences(context, key)
        val session = AppModule.provideSessionManager(context, key)
        val passphrase = AppModule.provideDatabasePassphraseManager(context, key)
        GeocodeTokenManager(context, key, mockk(relaxed = true), Gson())
        assertEquals("made with no Keystore call", 0, key.asked)

        assertTrue(DatabaseUnavailable.isUnavailable(assertThrows(Exception::class.java) { secure.getBoolean("x", false) }))
        assertTrue(DatabaseUnavailable.isUnavailable(assertThrows(Exception::class.java) { session.isLoggedIn() }))
        assertTrue(DatabaseUnavailable.isUnavailable(assertThrows(Exception::class.java) { passphrase.getPassphrase() }))
        assertEquals("each asked at its first use", 3, key.asked)
    }

    @Test
    fun `crash reporting with its store refused reads as not opted in - it is read at every start`() {
        val refused = mockk<SharedPreferences> {
            every { getBoolean(any(), any()) } throws SecureStorageUnavailableException(down)
        }
        val reporter = CrashReporter(context, refused, mockk(relaxed = true), mockk(relaxed = true))
        assertFalse(reporter.isEnabled())
        assertFalse(reporter.isRemoteReportingEnabled())
    }

    @Test
    fun `a request with the session store refused fails as a lost connection would, never on OkHttp's thread`() {
        val session = mockk<SessionManager>(relaxed = true)
        every { session.getAccessToken() } throws SecurityException("Cannot initialize secure session storage.", down)
        val client = AppModule.provideOkHttpClient(session, mockk(relaxed = true), RateLimitInterceptor(), CircuitBreakerInterceptor(), okhttp3.ConnectionPool(), com.pingolin.track.testutil.plainTrust())
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            val call = client.newCall(okhttp3.Request.Builder().url(server.url("/api/v1/circles")).build())
            // An IOException that never left: an SOS raise waits, a queued change
            // waits.
            val failed = assertThrows(StoreRefusedIOException::class.java) { call.execute() }
            assertTrue(DatabaseUnavailable.isUnavailable(failed))
            assertEquals("nothing sent", 0, server.requestCount)
            // A bug in the store isn't a lost connection: it surfaces.
            every { session.getAccessToken() } throws IllegalStateException("a bug")
            assertThrows(IllegalStateException::class.java) {
                client.newCall(okhttp3.Request.Builder().url(server.url("/api/v1/circles")).build()).execute()
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused session store is said, never taken for signed out - a bug still throws`() {
        val session = mockk<SessionManager>()
        every { session.isLoggedIn() } throws SecurityException("Cannot initialize secure session storage.", down)
        assertTrue(session.storeRefusal() is SecurityException)
        every { session.isLoggedIn() } returns false
        assertNull("signed out is no refusal", session.storeRefusal())
        every { session.isLoggedIn() } throws IllegalStateException("a bug")
        assertThrows(IllegalStateException::class.java) { session.storeRefusal() }
    }

    @Test
    fun `every start and background entry asks the store first`() {
        fun src(path: String) = File(path).readText()
        val main = "src/main/java/com/pingolin/track"
        fun body(file: String, signature: String) = src(file).substringAfter(signature).substringBefore("\n    }\n")
        fun guardedBefore(text: String, guard: String, before: String) {
            assertTrue(guard, text.contains(guard))
            assertTrue("$guard before $before", text.indexOf(guard) < text.indexOf(before))
        }
        // The starts: the screen in place of the app or sign-in, before anything reads the store.
        val mainStart = body("$main/ui/main/MainActivity.kt", "override fun onCreate(savedInstanceState: Bundle?)")
        guardedBefore(mainStart, "if (sessionManager.storeRefusal() != null) {\n            com.pingolin.track.ui.start.CantStartActivity.showFor(this)\n            return\n        }", "if (!sessionManager.isLoggedIn())")
        val authStart = body("$main/ui/auth/AuthActivity.kt", "override fun onCreate(savedInstanceState: Bundle?)")
        guardedBefore(authStart, "if (sessionManager.storeRefusal() != null) {\n            com.pingolin.track.ui.start.CantStartActivity.showFor(this)\n            return\n        }", "binding = ActivityAuthBinding.inflate")
        // The location service: first of all, and still meeting startForegroundService()'s
        // contract with words that read nothing from the store (a notification that read
        // it would leave the contract unmet, and the platform would kill the app), then
        // stopped.
        val service = body("$main/service/LocationTrackingService.kt", "override fun onStartCommand(")
        val refused = service.substringAfter("if (sessionManager.storeRefusal() != null) {").substringBefore("\n        }\n")
        assertTrue("before the first code", service.indexOf("if (sessionManager.storeRefusal() != null) {") in 0 until service.indexOf("if (!hasPreciseLocationPermission()) {"))
        assertFalse("nothing read before it", service.substringBefore("if (sessionManager.storeRefusal() != null) {").contains("sessionManager."))
        assertTrue(refused.contains("buildNotification(getString(R.string.cant_start_title))"))
        assertTrue("its declared type first", refused.indexOf("FOREGROUND_SERVICE_TYPE_LOCATION") in 0 until refused.indexOf("FOREGROUND_SERVICE_TYPE_NONE"))
        assertFalse(refused.contains("createNotification()"))
        assertTrue(refused.contains("stopSelf()\n            return START_NOT_STICKY"))
        // The watchdog keeps ticking, soon (RefusalLooksAgainSoonTest), so tracking comes
        // back once the store opens; a boot says sharing isn't, which arms it.
        guardedBefore(body("$main/receiver/WatchdogAlarmReceiver.kt", "override fun onReceive("), "if (sessionManager.storeRefusal() != null) {\n            scheduleRetry(context)\n            return\n        }", "if (!sessionManager.isLoggedIn())")
        guardedBefore(body("$main/receiver/BootReceiver.kt", "override fun onReceive("), "if (sessionManager.storeRefusal() != null) {\n                NotSharingNotice.show(context)\n                return\n            }", "if (sessionManager.isLoggedIn())")
        // The pushes: a token or an endpoint let go (registered again at the next start);
        // a message handed to its worker (StoreRefusalKeepsWorkTest).
        val unified = src("$main/service/TrackUnifiedPushReceiver.kt")
        for (handler in listOf("override fun onNewEndpoint(", "override fun onUnregistered(")) {
            assertTrue(handler, unified.substringAfter(handler).substringBefore("\n    }\n").contains("storeRefusal() != null) return"))
        }
        // Each asks the store before anything reads it, and before the message is handled.
        val unifiedMessage = unified.substringAfter("override fun onMessage(").substringBefore("\n    }\n")
        guardedBefore(unifiedMessage, "if (sessionManager.storeRefusal() != null) {", "sessionManager.")
        guardedBefore(unifiedMessage, "if (sessionManager.storeRefusal() != null) {", "handleNotification(")
        val fcm = src("src/gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt")
        guardedBefore(fcm.substringAfter("override fun onNewToken(").substringBefore("\n    }\n"), "if (sessionManager.storeRefusal() != null) return", "sessionManager.")
        val fcmMessage = fcm.substringAfter("override fun onMessageReceived(").substringBefore("\n    }\n")
        guardedBefore(fcmMessage, "if (sessionManager.storeRefusal() != null) {", "sessionManager.")
        guardedBefore(fcmMessage, "if (sessionManager.storeRefusal() != null) {", "handleDataMessage(")
        // The app's own start: every read of the store asks it first, on that line (the
        // theme's read is one, and would crash every start). And the pending changes' trigger.
        val app = src("$main/TrackApplication.kt").lines().filter { it.contains("sessionManager.get().") }
        assertTrue(app.size >= 3)
        for (line in app) {
            assertTrue(line.trim(), line.contains("setForceLogoutStopCallback") || line.contains("sessionManager.get().storeRefusal() == null"))
        }
        assertTrue(src("$main/domain/pending/PendingChangeTriggers.kt").contains("sessionManager.storeRefusal() == null && sessionManager.isLoggedIn()"))
        // No holder takes the MasterKey itself, and the shared store is the lazy one.
        val module = src("$main/di/AppModule.kt")
        assertEquals(3, Regex("""masterKey: dagger\.Lazy<MasterKey>""").findAll(module).count())
        assertFalse(module.contains("masterKey: MasterKey\n"))
        assertTrue(module.contains("): android.content.SharedPreferences = LazySecurePrefs {"))
        assertTrue(src("$main/data/remote/GeocodeTokenManager.kt").contains("private val prefs by lazy { SecurePrefs.open(context, PREFS_FILE, masterKey.get()) }"))
    }
}
