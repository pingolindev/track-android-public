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

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.domain.pending.PendingChangeRunner
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingOutcome
import com.pingolin.track.domain.sos.SosDispatcher
import com.pingolin.track.domain.sos.SosRaiseOutside
import com.pingolin.track.service.PushSignals
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.worker.AlertsCheckWorker
import com.pingolin.track.worker.IdentitiesCheckWorker
import com.pingolin.track.worker.NotificationTickleWorker
import com.pingolin.track.worker.PrivacyCheckWorker
import com.pingolin.track.worker.SosRaiseWorker
import com.pingolin.track.worker.SosRefreshWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * A session store that can't be opened loses nothing, and stops nothing silently. A push that
 * arrives then goes to its own worker, queued with nothing read from a store, and each worker takes
 * the refusal as "look again", so an SOS shows soon after the store opens, not at the 15-minute
 * poll. A request refused at the store never left, so an SOS raise waits rather than reading "maybe
 * sent"; a queued change waits, as one that may have gone. While tracking can't start, a plain
 * notice says the location isn't being shared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoreRefusalKeepsWorkTest {

    private lateinit var context: Context
    private val refused = SecurityException("Cannot initialize secure session storage.", NullPointerException("IKeystoreService"))

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
    }

    private fun queued(worker: Class<*>) = WorkManager.getInstance(context).getWorkInfosByTag(worker.name).get()

    @Test
    fun `a push during a refusal goes to its own worker - nothing dropped, nothing read from a store`() {
        val workers = mapOf(
            PushSignals.Action.FETCH_NOTIFICATIONS to NotificationTickleWorker::class.java,
            PushSignals.Action.CHECK_OWN_ALERTS to AlertsCheckWorker::class.java,
            PushSignals.Action.REFRESH_PRIVACY to PrivacyCheckWorker::class.java,
            PushSignals.Action.REFRESH_SOS to SosRefreshWorker::class.java,
            PushSignals.Action.CHECK_IDENTITIES to IdentitiesCheckWorker::class.java,
        )
        for ((action, worker) in workers) {
            val before = workers.values.associateWith { queued(it).size }
            PushSignals.handOff(context, action)
            for (each in workers.values) {
                assertEquals("$action -> ${each.simpleName}", before.getValue(each) + if (each == worker) 1 else 0, queued(each).size)
            }
        }
        // A location wake is the watchdog's: its next tick starts tracking once the store opens.
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        fun watchdogSet() = alarms.scheduledAlarms.any { shadowOf(it.operation).savedIntent.action == "com.pingolin.track.WATCHDOG_TICK" }
        assertFalse(watchdogSet())
        PushSignals.handOff(context, PushSignals.Action.WAKE_LOCATION)
        assertTrue(watchdogSet())
        // Nothing for a push of no known type.
        val set = alarms.scheduledAlarms.size
        val works = workers.values.sumOf { queued(it).size }
        PushSignals.handOff(context, PushSignals.Action.IGNORE)
        assertEquals(set, alarms.scheduledAlarms.size)
        assertEquals(works, workers.values.sumOf { queued(it).size })
        // And it takes no store at all.
        assertFalse(File("src/main/java/com/pingolin/track/service/PushSignals.kt").readText()
            .substringAfter("fun handOff(").substringBefore("\n    }\n").contains("sessionManager"))
    }

    /** Each worker as Hilt would make it, with [session], [given] where it has one, and the rest stand-ins. */
    private fun workers(session: SessionManager, given: Map<Class<*>, Any> = emptyMap()) = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker {
            val ctor = Class.forName(workerClassName).constructors.single()
            val args = ctor.parameterTypes.map { type ->
                when {
                    type == Context::class.java -> appContext
                    type == WorkerParameters::class.java -> params
                    type == SessionManager::class.java -> session
                    else -> given[type] ?: mockkClass(type.kotlin, relaxed = true)
                }
            }
            return ctor.newInstance(*args.toTypedArray()) as ListenableWorker
        }
    }

    /** Every worker under worker/, a new one included: one per file. */
    private fun workerFiles() = File("src/main/java/com/pingolin/track/worker").listFiles { f -> f.name.endsWith("Worker.kt") }!!.sortedBy { it.name }

    @Test
    fun `every worker takes a refusal as look again, never a failure that loses what was asked`() {
        val session = mockk<SessionManager>(relaxed = true) { every { isLoggedIn() } throws refused }
        // The waiting help request's job too, and every other: failed, a one-time job
        // waits for whatever enqueues it again.
        val all = workerFiles().map { Class.forName("com.pingolin.track.worker." + it.name.removeSuffix(".kt")).asSubclass(ListenableWorker::class.java) }
        assertTrue("the eleven, and any new one", all.size >= 11)
        for (worker in all) {
            val built = TestListenableWorkerBuilder.from(context, worker).setWorkerFactory(workers(session)).build() as androidx.work.CoroutineWorker
            assertEquals(worker.simpleName, ListenableWorker.Result.retry(), runBlocking { built.doWork() })
        }
    }

    @Test
    fun `every worker asks the store first, before anything else it reads there, so a new one can't miss it`() {
        for (file in workerFiles()) {
            val doWork = file.readText().substringAfter("fun doWork(): Result {", "").substringBefore("\n    }\n")
            assertTrue("${file.name}: its doWork", doWork.isNotEmpty())
            val first = doWork.indexOf("sessionManager.")
            assertTrue("${file.name} asks the store first", first < 0 || doWork.startsWith("sessionManager.storeRefusal() != null", first))
        }
    }

    @Test
    fun `a waiting help request's job during a refusal says it waits on Track, not a connection - it runs only online`() {
        val session = mockk<SessionManager>(relaxed = true) { every { isLoggedIn() } throws refused }
        val outside = SosRaiseOutside(context)
        val built = TestListenableWorkerBuilder.from(context, SosRaiseWorker::class.java)
            .setWorkerFactory(workers(session, mapOf(SosRaiseOutside::class.java to outside))).build() as androidx.work.CoroutineWorker
        assertEquals(ListenableWorker.Result.retry(), runBlocking { built.doWork() })
        val notice = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(TrackApplication.SOS_CHANNEL_ID, notice.channelId)
        assertEquals("Not sent yet", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            "Your circle will be notified as soon as Track can send it. If you need them now, call or text them.",
            notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertTrue("it stays while it waits", notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun `both push handlers hand a refused push off, after their own checks of a hostile message`() {
        val fcm = File("src/gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt").readText()
            .substringAfter("override fun onMessageReceived(").substringBefore("\n    }\n")
        assertTrue(fcm.contains("if (sessionManager.storeRefusal() != null) {\n            PushSignals.handOff(applicationContext, PushSignals.actionFor(remoteMessage.data))\n            return\n        }"))
        val unified = File("src/main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt").readText()
            .substringAfter("override fun onMessage(").substringBefore("\n    }\n")
        val handOff = unified.indexOf("PushSignals.handOff(context, PushSignals.actionFor(data))")
        assertTrue(handOff > 0)
        for (check in listOf("isCallerTrusted(context)", "MAX_MESSAGE_SIZE", "parseNotificationData(messageStr)")) {
            assertTrue("$check first", unified.indexOf(check) in 0 until handOff)
        }
    }

    @Test
    fun `a request refused at the store never left - an SOS raise waits, and a queued change waits`() {
        val atTheStore = StoreRefusedIOException(refused)
        assertTrue(atTheStore is java.io.IOException)
        assertEquals(SosDispatcher.NotSent.Why.WAITING, SosDispatcher.whyNotSent(atTheStore))
        // A plain IOException may have reached the server.
        assertEquals(SosDispatcher.NotSent.Why.UNSURE, SosDispatcher.whyNotSent(java.io.IOException("reset")))

        val subject = PendingInputs.subject(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0))
        assertEquals(PendingOutcome.Transient(maybeSent = false), PendingChangeRunner.classify(atTheStore, subject, "Family"))
        // The store itself refusing, or the database: waits too, never dropped, and as one that
        // may have gone, for it may have failed after the request left (PlaceWaitingTest).
        assertEquals(PendingOutcome.Transient(maybeSent = true), PendingChangeRunner.classify(refused, subject, "Family"))
        assertEquals(PendingOutcome.Transient(maybeSent = true), PendingChangeRunner.classify(DatabaseKeyRefusedException(refused), subject, "Family"))
        assertEquals(
            PendingOutcome.Transient(maybeSent = true),
            PendingChangeRunner.classify(android.database.sqlite.SQLiteFullException("database or disk is full"), subject, "Family"),
        )
        // A bug is still dropped.
        assertTrue(PendingChangeRunner.classify(IllegalStateException("a bug"), subject, "Family") is PendingOutcome.Dropped)
        // The interceptor throws it.
        assertTrue(File("src/main/java/com/pingolin/track/di/AppModule.kt").readText()
            .contains("throw com.pingolin.track.util.StoreRefusedIOException(e)"))
    }

    @Test
    fun `while tracking can't start - a plain notice on the location channel, word for word, down when it starts`() {
        assertEquals("Your location isn't being shared", context.getString(R.string.not_sharing_title))
        // The notice's text: nothing about starting to share again, which a boot can't
        // know is wanted, and no "restart your phone" for a refusal that may have passed.
        assertEquals("Track can't send your location right now. Tap to open Track.", context.getString(R.string.not_sharing_text))
        for (id in listOf(R.string.not_sharing_title, R.string.not_sharing_text)) {
            val words = context.getString(id).lowercase()
            for (word in listOf("key", "keystore", "passphrase", "storage", "database")) assertFalse("no key talk: $word", words.contains(word))
        }

        NotSharingNotice.show(context)
        val shade = shadowOf(context.getSystemService(NotificationManager::class.java))
        val notice = shade.getNotification(NotSharingNotice.NOTIFICATION_ID)
        assertEquals("the location channel, never the emergency one", TrackApplication.LOCATION_CHANNEL_ID, notice.channelId)
        assertEquals("Your location isn't being shared", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Track can't send your location right now. Tap to open Track.", notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        @Suppress("DEPRECATION")
        assertEquals(androidx.core.app.NotificationCompat.PRIORITY_LOW, notice.priority)
        assertEquals("it opens Track", MainActivity::class.java.name, shadowOf(notice.contentIntent).savedIntent.component?.className)
        // Said, the watchdog looks again within a minute (RefusalLooksAgainSoonTest).
        val tick = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .single { shadowOf(it.operation).savedIntent.action == "com.pingolin.track.WATCHDOG_TICK" }
        assertTrue(tick.triggerAtTime - SystemClock.elapsedRealtime() <= 60_000L)
        NotSharingNotice.clear(context)
        assertNull(shade.getNotification(NotSharingNotice.NOTIFICATION_ID))
        assertFalse("read from no store", File("src/main/java/com/pingolin/track/util/NotSharingNotice.kt").readText().contains("sessionManager"))

        val service = File("src/main/java/com/pingolin/track/service/LocationTrackingService.kt").readText()
        val start = service.substringAfter("override fun onStartCommand(").substringBefore("\n    }\n")
        val refusedBranch = start.substringAfter("if (sessionManager.storeRefusal() != null) {").substringBefore("\n        }\n")
        assertTrue("posted on a refusal, before the stop", refusedBranch.indexOf("NotSharingNotice.show(this)") in 0 until refusedBranch.indexOf("stopSelf()"))
        assertTrue("down when tracking starts", start.contains("android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION\n            )\n            // Sharing again: the notice that it couldn't be is down.\n            NotSharingNotice.clear(this)"))
    }
}
