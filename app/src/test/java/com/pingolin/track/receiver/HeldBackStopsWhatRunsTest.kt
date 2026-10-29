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

package com.pingolin.track.receiver

import android.app.AlarmManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pingolin.track.service.KeepAliveGeofence
import com.pingolin.track.service.LocationKeepAlive
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.service.TrackingStop
import com.pingolin.track.util.SessionManager
import com.pingolin.track.worker.LocationHeartbeatWorker
import com.pingolin.track.worker.LocationHeartbeatWorker.Companion.heartbeatGate
import com.pingolin.track.worker.LocationHeartbeatWorker.HeartbeatGate
import com.pingolin.track.worker.LocationSyncWorker
import com.pingolin.track.worker.NotificationTickleWorker
import io.mockk.clearMocks
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

/**
 * Nothing of Track's runs for an account an admin hasn't approved, waiting or turned down. The starts
 * all ask, and learning it must also stop what already runs: after an update neither flag is known
 * yet, so BootReceiver may restore sharing, the keep-alive, the heartbeat and the watchdog, and the
 * sticky restart and the heartbeat don't ask. So the one place the review is kept stops it all, as a
 * sign-out does, and wipes nothing; every door back asks the one rule; and a review this build never
 * read is read once at a start, so it stops whether or not the app is ever opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HeldBackStopsWhatRunsTest {

    private lateinit var context: Context
    private lateinit var session: SessionManager

    private val workers = listOf(
        LocationHeartbeatWorker::class.java, LocationSyncWorker::class.java, NotificationTickleWorker::class.java,
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setAutoSchedule(false)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        val store = context.getSharedPreferences("held-back-test", Context.MODE_PRIVATE)
        session = object : SessionManager(context) {
            override val prefs: SharedPreferences = store
        }
        mockkObject(LocationKeepAlive, KeepAliveGeofence)
        serviceRunning(false)
    }

    @After
    fun tearDown() {
        unmockkObject(LocationKeepAlive, KeepAliveGeofence)
        serviceRunning(false)
    }

    /** The service's own flag (set only by the service): a started service runs, a stopped one doesn't. */
    private fun serviceRunning(running: Boolean) =
        LocationTrackingService::class.java.getDeclaredField("isRunning").apply { isAccessible = true }.setBoolean(null, running)

    private fun signedInSharing() {
        session.saveTokens("access-xyz", "refresh-abc", expiresIn = 3600)
        session.saveUser(
            userId = "da1a0000-0000-4000-8000-00000000da1a",
            email = "dana@pingolin.test",
            displayName = "Dana",
            publicKey = "pk",
        )
        session.setTrackingEnabled(true)
    }

    /** BootReceiver as Hilt would hand it [session]: its own inject() then does nothing here. */
    private fun receive(action: String) {
        val receiver = BootReceiver()
        var type: Class<*>? = receiver.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { it.name == "injected" }?.let { it.isAccessible = true; it.setBoolean(receiver, true) }
            type = type.superclass
        }
        receiver.sessionManager = session
        (receiver as BroadcastReceiver).onReceive(context, Intent(action))
    }

    /** Every service start asked since the last call. */
    private fun started(): List<Intent> =
        generateSequence { shadowOf(context as Application).nextStartedService }.toList()

    private fun watchdogArmed() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
        shadowOf(it.operation).savedIntent.action == "com.pingolin.track.WATCHDOG_TICK"
    }

    /** What the arming itself called (the keep-alive's own re-register lets go first), so a stop's calls are its own. */
    private fun armingForgotten() = clearMocks(LocationKeepAlive, KeepAliveGeofence, answers = false)

    private fun armed(worker: Class<*>) =
        WorkManager.getInstance(context).getWorkInfosByTag(worker.name).get().any { !it.state.isFinished }

    @Test
    fun `an update from a build that never read the review restores sharing, and the first look stops all of it`() {
        signedInSharing()
        assertFalse("an earlier build never wrote it", session.isRegistrationReviewRead())

        receive(Intent.ACTION_MY_PACKAGE_REPLACED)
        // What the update restores, as for anyone: an earlier build's applicant isn't known as one yet.
        assertEquals(listOf(LocationTrackingService.ACTION_START), started().map { it.action })
        assertTrue(watchdogArmed())
        for (worker in workers) assertTrue("${worker.simpleName} armed", armed(worker))
        verify { LocationKeepAlive.register(any(), any(), any()) }
        serviceRunning(true)
        armingForgotten()

        // The first look at /auth/me says the account waits (AuthRepository.keepReview), and the screen shows.
        session.keepRegistrationReview(pending = true, rejected = false)

        assertEquals("the service is stopped", listOf(LocationTrackingService.ACTION_STOP), started().map { it.action })
        assertFalse("no watchdog", watchdogArmed())
        for (worker in workers) assertFalse("${worker.simpleName} cancelled", armed(worker))
        verify { LocationKeepAlive.unregister(any()) }
        verify { KeepAliveGeofence.disarm(any()) }
        assertTrue("the session stays", session.isLoggedIn())
        assertEquals("access-xyz", session.getAccessToken())

        // And nothing brings it back, at any door.
        serviceRunning(false)
        receive(Intent.ACTION_BOOT_COMPLETED)
        assertTrue("a reboot starts nothing", started().isEmpty())
        assertFalse(watchdogArmed())
        for (worker in workers) assertFalse(armed(worker))
        assertFalse("the one rule", session.shouldBeTracking())
        assertEquals(
            "a stalled service's heartbeat neither takes nor sends a fix",
            HeartbeatGate.SKIP_NOT_WANTED,
            heartbeatGate(
                fgsRunning = true, lastSendAgeMs = Long.MAX_VALUE, staleThresholdMs = 1L,
                loggedIn = session.isLoggedIn(), paused = session.isTrackingPaused(), wanted = session.shouldBeTracking(),
            ),
        )
        assertEquals(
            KeepAliveReceiver.Decision.NOT_WANTED,
            KeepAliveReceiver.decide({ false }, { session.shouldBeTracking() }, { false }, { true }),
        )
    }

    @Test
    fun `turned down after waiting stops it all the same, and an approval stops nothing`() {
        signedInSharing()
        receive(Intent.ACTION_BOOT_COMPLETED)
        started()
        armingForgotten()

        session.keepRegistrationReview(pending = false, rejected = false)
        assertTrue("an approved account's sharing stays", watchdogArmed())
        for (worker in workers) assertTrue(armed(worker))
        verify(exactly = 0) { LocationKeepAlive.unregister(any()) }

        session.keepRegistrationReview(pending = false, rejected = true)
        assertFalse(watchdogArmed())
        for (worker in workers) assertFalse(armed(worker))
        verify { LocationKeepAlive.unregister(any()) }
        assertTrue(session.isLoggedIn())
    }

    @Test
    fun `the one stop stops what boot arms, and only while the service runs is it asked to stop`() {
        signedInSharing()
        receive(Intent.ACTION_BOOT_COMPLETED)
        started()
        armingForgotten()

        TrackingStop.all(context)

        assertTrue("a service that doesn't run isn't started to be stopped", started().isEmpty())
        assertFalse(watchdogArmed())
        for (worker in workers) assertFalse("${worker.simpleName} cancelled", armed(worker))
        verify { LocationKeepAlive.unregister(any()) }
        verify { KeepAliveGeofence.disarm(any()) }
    }

    // ---- the doors, read where they are ------------------------------------------------------

    private fun main(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    /** [name]'s body in [file], up to its closing brace at [indent]. */
    private fun body(file: String, name: String, indent: String = "    "): String =
        file.substring(file.indexOf(name)).let { it.substring(0, it.indexOf("\n$indent}\n")) }

    @Test
    fun `both sign-outs and a held-back review stop by the one stop, and it wipes nothing`() {
        val stop = main("service/TrackingStop.kt")
        for (step in listOf(
            "action = LocationTrackingService.ACTION_STOP",
            "runCatching { LocationKeepAlive.unregister(context) }",
            "runCatching { KeepAliveGeofence.disarm(context) }",
            "runCatching { LocationHeartbeatWorker.cancel(context) }",
            "runCatching { WatchdogAlarmReceiver.cancel(context) }",
            "runCatching { LocationSyncWorker.cancel(context) }",
            "runCatching { NotificationTickleWorker.cancelPolling(context) }",
        )) assertTrue(step, stop.contains(step))
        for (wipe in listOf("clearSession(", "clearCredentialsOnly(", "clearAllKeys(", "clearAllTables(", ".wipe(", "setTrackingEnabled(")) {
            assertFalse(wipe, stop.contains(wipe))
        }

        val chosen = body(main("domain/repository/AuthRepository.kt"), "suspend fun clearLocalData()")
        assertTrue(chosen.contains("com.pingolin.track.service.TrackingStop.all(context)"))
        assertTrue("before the keys go", chosen.indexOf("TrackingStop.all(") < chosen.indexOf("keyStoreRecovery.clearKeys()"))
        assertTrue("and the session", chosen.indexOf("TrackingStop.all(") < chosen.indexOf("sessionManager.clearSession()"))
        val forced = main("TrackApplication.kt").let { it.substring(it.indexOf("setForceLogoutStopCallback {")) }
        assertTrue(forced.substring(0, forced.indexOf("\n        }\n")).contains("com.pingolin.track.service.TrackingStop.all(this@TrackApplication)"))

        val keep = body(main("util/SessionManager.kt"), "fun keepRegistrationReview(")
        assertTrue(keep.contains("if (pending || rejected) TrackingStop.all(context)"))
    }

    @Test
    fun `the review is kept in one place, which every look goes through`() {
        val writers = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("keepRegistrationReview(") }
            .map { it.name }.toSet()
        assertEquals(setOf("SessionManager.kt", "AuthRepository.kt"), writers)
        val auth = main("domain/repository/AuthRepository.kt")
        assertEquals("keepReview alone", 1, Regex("keepRegistrationReview\\(").findAll(auth).count())
        assertTrue(body(auth, "private suspend fun keepReview(").contains("sessionManager.keepRegistrationReview(pending = waiting, rejected = rejected)"))
    }

    @Test
    fun `the sticky restart asks the one rule`() {
        val start = body(main("service/LocationTrackingService.kt"), "override fun onStartCommand(")
        val sticky = start.substring(start.indexOf("// Service restarted by Android (START_STICKY) with null intent."))
        assertTrue(sticky.contains("} else if (sessionManager.shouldBeTracking()) {"))
        assertFalse(sticky.contains("} else if (sessionManager.isLoggedIn()) {"))
    }

    @Test
    fun `a review this build never read is read once at a start, in the background, never before it`() {
        val app = main("TrackApplication.kt")
        assertTrue(app.contains(
            "if (session.storeRefusal() == null && session.isLoggedIn() && !session.isRegistrationReviewRead()) {\n" +
                "                reviewScope.launch { runCatching { authRepository.get().lookAtReview() } }"
        ))
        // BootReceiver waits on no network: the update's start stays where it was.
        assertFalse(main("receiver/BootReceiver.kt").contains("lookAtReview"))
    }
}
