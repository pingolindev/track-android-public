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

package com.pingolin.track.domain.sos

import android.location.Location
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.dto.SosDeliveryResponse
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.domain.repository.SosRepository.OwnRunResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raise's timing on this phone: the press starts the precise fix and a privacy read
 * beside the countdown, and cancel stops both; the send never waits for the fix and never
 * fails for want of one; after the create, the update window and the key check for the
 * whole open alert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosDispatcherTest {

    private companion object {
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val PRESS = 1_800_000_000_000L
        const val SEC = 1_000L
        const val MIN = 60 * SEC
        const val HOUR = 60 * MIN
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val OTHER = "a11ce000-0000-4000-8000-00000000a11c"
    }

    /** Who is signed in: tests switch it. */
    private var signedIn: String? = ME

    /** What hasn't gone, kept as the encrypted file would keep it: per account, across a "restart". */
    private val kept = MemoryPendingRaises { signedIn }

    /** What the dispatcher says outside the app, and the job it asks for. */
    private val notices = mutableListOf<SosDispatcher.NotSent?>()
    private var wakes = 0
    private var wakeCancels = 0
    private val outside = object : RaiseOutside {
        override fun notice(notSent: SosDispatcher.NotSent?) {
            notices += notSent
        }

        override fun wakeWhenOnline() {
            wakes++
        }

        override fun cancelWake() {
            wakeCancels++
        }

        override fun deliveryNotice(verdict: SosDeliveryWatcher.Verdict) {
            deliveryNotices += verdict
        }
    }

    /** What the delivery checks answer: every device confirmed, unless a test says otherwise. */
    private var delivery: SosDeliveryResponse? = answer("confirmed")
    private val deliveryNotices = mutableListOf<SosDeliveryWatcher.Verdict>()

    private fun answer(state: String) =
        SosDeliveryResponse(recipients = 1, addressable = 1, dispatched = 1, emailed = 0, confirmed = 0, state = state)

    private val sos = mockk<SosRepository>()
    private val location = mockk<LocationRepository>()
    private val positions = mockk<SosPositionChooser>()
    private val privacyReads = mutableListOf<String>()
    private var privacyRead: suspend (String) -> Unit = { privacyReads += it }
    private val keyReadsAt = mutableListOf<Long>()

    /** When each update run went, on the test clock, and with what fix. */
    private val runs = mutableListOf<Pair<Long, SosSeal.Point?>>()

    private val alert = SosAlertEntity(SOS, CIRCLE, "me", "active", null, null, "", PRESS + 5 * SEC, null)

    private fun TestScope.dispatcher(awaitConnection: suspend (Long) -> Unit = { delay(it) }): SosDispatcher {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        every { positions.exact(any()) } returns null
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.success(alert)
        coEvery { sos.runOwnSos(SOS, any(), any()) } answers {
            runs += testScheduler.currentTime to secondArg()
            OwnRunResult.NOTHING
        }
        every { sos.ownUpdateDue(SOS) } returns false
        coEvery { sos.isOpenHere(SOS) } returns false
        coEvery { sos.getSosDelivery(any()) } coAnswers { delivery }
        return SosDispatcher(
            sos, location, positions, { privacyRead(it) }, { keyReadsAt += testScheduler.currentTime }, scope,
            awaitConnection, { signedIn }, kept, outside,
        ).also { it.wallNow = { PRESS + testScheduler.currentTime } }
    }

    private fun fix(): Location = mockk(relaxed = true)

    // ---- the press, the countdown and the send ----

    @Test
    fun `the press starts the fix and a privacy read at once, and the send goes at the countdown's end without the fix`() = runTest {
        val dispatcher = dispatcher()
        // A cold GPS: the fix never comes.
        coEvery { location.getCurrentLocation() } coAnswers { awaitCancellation() }
        coEvery { positions.forRaise(CIRCLE, PRESS, null) } returns SosSeal.PENDING

        dispatcher.startCountdown(CIRCLE)
        runCurrent()
        coVerify(exactly = 1) { location.getCurrentLocation() }
        assertEquals(listOf(CIRCLE), privacyReads)
        coVerify(exactly = 0) { sos.triggerSos(any(), any(), any(), any()) }

        advanceTimeBy(5 * SEC)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(CIRCLE, SosSeal.PENDING, PRESS, any()) }
        assertEquals("sent, never failed for a location", SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
    }

    @Test
    fun `the press's fix, when it is in by the send, is what the raise is chosen with`() = runTest {
        val dispatcher = dispatcher()
        val pressFix = fix()
        coEvery { location.getCurrentLocation() } coAnswers {
            delay(2 * SEC)
            pressFix
        }
        val chosen = SosSeal.Point(37.33, -122.01, PRESS + 2 * SEC, 8.0)
        coEvery { positions.forRaise(CIRCLE, PRESS, pressFix) } returns chosen

        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(5 * SEC)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(CIRCLE, chosen, PRESS, any()) }
    }

    @Test
    fun `cancel stops the fix and the privacy read the press started`() = runTest {
        val dispatcher = dispatcher()
        var fixStopped = false
        var readStopped = false
        coEvery { location.getCurrentLocation() } coAnswers {
            try {
                awaitCancellation()
            } finally {
                fixStopped = true
            }
        }
        privacyRead = {
            try {
                awaitCancellation()
            } finally {
                readStopped = true
            }
        }
        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(2 * SEC)
        runCurrent()
        assertTrue(dispatcher.cancelCountdown())
        runCurrent()
        assertTrue(fixStopped)
        assertTrue(readStopped)
        advanceTimeBy(10 * SEC)
        runCurrent()
        coVerify(exactly = 0) { sos.triggerSos(any(), any(), any(), any()) }
    }

    @Test
    fun `send now during a countdown keeps its press - the commitment was made when it started`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } coAnswers { awaitCancellation() }
        coEvery { positions.forRaise(CIRCLE, any(), null) } returns SosSeal.PENDING
        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(2 * SEC)
        runCurrent()
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(CIRCLE, SosSeal.PENDING, PRESS, any()) }
        coVerify(exactly = 1) { location.getCurrentLocation() }
        assertEquals(listOf(CIRCLE), privacyReads)
    }

    // ---- The update window ----

    @Test
    fun `while an update is due, one fix every 30 s, each to a run, and a last run as the 10 minutes end - none after`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns SosSeal.PENDING
        every { sos.ownUpdateDue(SOS) } returns true

        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(30 * MIN)
        runCurrent()

        val times = runs.map { it.first }
        assertEquals("the first at the send", 5 * SEC, times.first())
        assertEquals("then every 30 s", (0 until 20).map { 5 * SEC + it * 30 * SEC }, times.dropLast(1))
        assertEquals("the last as the window ends", 10 * MIN, times.last())
        coVerify(exactly = 21) { location.getCurrentLocation() }
    }

    @Test
    fun `the press's fix goes to the first run, each poll's newer fix to the next`() = runTest {
        val dispatcher = dispatcher()
        val pressFix = fix()
        val polled = fix()
        val first = SosSeal.Point(37.33, -122.01, PRESS + SEC, 300.0)
        val second = SosSeal.Point(37.34, -122.02, PRESS + 36 * SEC, 9.0)
        var calls = 0
        coEvery { location.getCurrentLocation() } coAnswers { if (calls++ == 0) pressFix else polled }
        every { positions.exact(pressFix) } returns first
        every { positions.exact(polled) } returns second
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns first
        every { sos.ownUpdateDue(SOS) } returnsMany listOf(true, false)

        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(2 * MIN)
        runCurrent()
        assertEquals(listOf(5 * SEC to first, 35 * SEC to second), runs)
    }

    @Test
    fun `a deferred update runs again within 30 s, and the loop ends once nothing more can improve`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns SosSeal.PENDING
        coEvery { sos.runOwnSos(SOS, any(), any()) } answers {
            runs += testScheduler.currentTime to secondArg()
            if (runs.size == 1) OwnRunResult.DEFERRED else OwnRunResult.UPDATED
        }
        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(5 * MIN)
        runCurrent()
        assertEquals(listOf(5 * SEC, 35 * SEC), runs.map { it.first })
    }

    @Test
    fun `an ended alert ends the loop`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns SosSeal.PENDING
        every { sos.ownUpdateDue(SOS) } returns true
        coEvery { sos.runOwnSos(SOS, any(), any()) } answers {
            runs += testScheduler.currentTime to secondArg()
            OwnRunResult.ENDED
        }
        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(5 * MIN)
        runCurrent()
        assertEquals(1, runs.size)
    }

    // ---- the key check ----

    @Test
    fun `the key check runs every minute while the alert is open, past the 10 minutes, and stops when it closes`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns SosSeal.PENDING
        coEvery { sos.isOpenHere(SOS) } answers { testScheduler.currentTime < 30 * MIN }

        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(2 * 60 * MIN)
        runCurrent()
        assertEquals((1..29).map { 5 * SEC + it * MIN }, keyReadsAt)
    }

    // ---- a raise that hasn't reached the circle ----

    /** Asked at [raisedAt]: Send now's moment, or the countdown's end. */
    private fun unsent(why: SosDispatcher.NotSent.Why, raisedAt: Long = PRESS) = SosDispatcher.NotSent(CIRCLE, why, raisedAt)

    @Test
    fun `offline, the raise waits, says so, and goes by itself once a connection is back`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { ms -> kotlinx.coroutines.withTimeoutOrNull(ms) { back.await() } })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, null) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("Unable to resolve host")),
            Result.success(alert),
        )

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(unsent(SosDispatcher.NotSent.Why.WAITING), dispatcher.notSent.value)
        assertEquals("nothing to show and forget", null, dispatcher.outcome.value)
        assertEquals("the card, not the countdown", null, dispatcher.countdown.value)

        advanceTimeBy(20 * SEC)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }

        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        coVerify(exactly = 2) { sos.triggerSos(CIRCLE, SosSeal.PENDING, PRESS, any()) }
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
        assertEquals(null, dispatcher.notSent.value)
    }

    @Test
    fun `a raise refused at the session store never left - it waits in its own words, kept so, and goes with no connection change`() = runTest {
        // Online all along: nothing says a connection is back, only the wait's own timer runs.
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, null) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(com.pingolin.track.util.StoreRefusedIOException(SecurityException("Cannot initialize secure session storage."))),
            Result.success(alert),
        )

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        // Never "maybe sent", and never "no connection": the phone is online.
        val atTheStore = SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.WAITING, PRESS, storeRefused = true)
        assertEquals(atTheStore, dispatcher.notSent.value)
        assertEquals(listOf<SosDispatcher.NotSent?>(atTheStore), notices)
        assertEquals("kept with its cause", PendingRaise(CIRCLE, PRESS, PRESS, PendingRaise.State.WAITING, storeRefused = true), kept.get())
        assertEquals(1, wakes)

        // The store opens: the wait's next try goes, though no connection came back.
        advanceTimeBy(SosDispatcher.RETRY_MS + SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        coVerify(exactly = 2) { sos.triggerSos(CIRCLE, any(), any(), any()) }
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
        assertEquals(null, dispatcher.notSent.value)
        assertEquals(null, notices.last())
    }

    @Test
    fun `the real wait for a connection ends at its time when none comes back, so a raise waiting online on the store goes`() = runTest {
        // The dispatcher as Hilt makes it: its wait is the connection monitor's, not these tests' timer.
        val online = kotlinx.coroutines.flow.MutableStateFlow(true)
        val monitor = mockk<com.pingolin.track.util.ConnectivityMonitor> { every { isOnline } returns online }
        val made = SosDispatcher(
            mockk(relaxed = true), sos, location, positions, { mockk(relaxed = true) }, { monitor },
            mockk(relaxed = true), mockk(relaxed = true),
        )
        @Suppress("UNCHECKED_CAST")
        val wait = SosDispatcher::class.java.getDeclaredField("awaitConnection").apply { isAccessible = true }.get(made) as suspend (Long) -> Unit

        // Online all along, as when the session store refused: no change comes, and it ends at its time.
        val start = testScheduler.currentTime
        assertEquals(Unit, kotlinx.coroutines.withTimeoutOrNull(SosDispatcher.RETRY_MS + SEC) { wait(SosDispatcher.RETRY_MS) })
        assertEquals(SosDispatcher.RETRY_MS, testScheduler.currentTime - start)
        // Offline, then back: it ends then, not at its time.
        online.value = false
        val back = async { wait(SosDispatcher.RETRY_MS) }
        advanceTimeBy(10 * SEC)
        runCurrent()
        org.junit.Assert.assertFalse(back.isCompleted)
        online.value = true
        runCurrent()
        assertTrue(back.isCompleted)

        // As a raise's wait: refused at the store while online, it goes at its next try, with no change of connection.
        online.value = true
        val dispatcher = dispatcher(awaitConnection = wait)
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), null) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(com.pingolin.track.util.StoreRefusedIOException(SecurityException("Cannot initialize secure session storage."))),
            Result.success(alert),
        )
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(true, dispatcher.notSent.value?.storeRefused)
        advanceTimeBy(SosDispatcher.RETRY_MS + SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        coVerify(exactly = 2) { sos.triggerSos(CIRCLE, any(), any(), any()) }
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
    }

    @Test
    fun `after a restart a raise that waited on the session store says so again, from its record, until a try says otherwise`() = runTest {
        kept.put(record(PendingRaise.State.WAITING).copy(storeRefused = true))
        val dispatcher = dispatcher(awaitConnection = { kotlinx.coroutines.awaitCancellation() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))
        advanceTimeBy(30 * MIN)

        assertTrue(dispatcher.resumePending())
        assertEquals(SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.WAITING, PRESS + 5 * SEC, storeRefused = true), notices.first())

        // Its try meets no connection now: said in those words, and kept so.
        runCurrent()
        assertEquals(SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.WAITING, PRESS + 5 * SEC), dispatcher.notSent.value)
        assertEquals(dispatcher.notSent.value, notices.last())
        assertEquals(false, kept.get()?.storeRefused)
    }

    @Test
    fun `with no word of a connection, a waiting raise tries again on its own timer`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, null) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.ConnectException("Failed to connect")),
            Result.failure(java.net.NoRouteToHostException("No route")),
            Result.success(alert),
        )

        val every = SosDispatcher.RETRY_MS + SosDispatcher.RETRY_GAP_MS
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        advanceTimeBy(every - SEC)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
        advanceTimeBy(SEC)
        runCurrent()
        coVerify(exactly = 2) { sos.triggerSos(any(), any(), any(), any()) }
        assertEquals(unsent(SosDispatcher.NotSent.Why.WAITING), dispatcher.notSent.value)
        advanceTimeBy(every)
        runCurrent()
        coVerify(exactly = 3) { sos.triggerSos(any(), any(), any(), any()) }
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
    }

    @Test
    fun `a raise with no answer may have been stored, so it isn't sent again by itself`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.SocketTimeoutException("timeout"))

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(unsent(SosDispatcher.NotSent.Why.UNSURE), dispatcher.notSent.value)
        advanceTimeBy(30 * MIN)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
        assertEquals(unsent(SosDispatcher.NotSent.Why.UNSURE), dispatcher.notSent.value)
    }

    @Test
    fun `a refused raise says the circle wasn't notified, and Try again asks again now`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(Exception("Could not notify your circle")),
            Result.success(alert),
        )

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(unsent(SosDispatcher.NotSent.Why.NOT_SENT), dispatcher.notSent.value)
        advanceTimeBy(2 * MIN)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }

        dispatcher.tryAgain()
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(CIRCLE, any(), PRESS + 2 * MIN, any()) }
        assertEquals(null, dispatcher.notSent.value)
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
    }

    @Test
    fun `Cancel lets a waiting raise go, and nothing more is sent`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { ms -> kotlinx.coroutines.withTimeoutOrNull(ms) { back.await() } })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        dispatcher.letGo()
        assertEquals(null, dispatcher.notSent.value)

        back.complete(Unit)
        advanceTimeBy(10 * MIN)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
        assertEquals(null, dispatcher.notSent.value)
    }

    @Test
    fun `a raise let go while a retry is out isn't said again, whatever that retry meets`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val retryOut = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { ms -> kotlinx.coroutines.withTimeoutOrNull(ms) { back.await() } })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        var calls = 0
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } coAnswers {
            if (calls++ == 1) retryOut.await()
            Result.failure(java.net.UnknownHostException("x"))
        }

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals("the retry is out", 2, calls)
        dispatcher.letGo()
        retryOut.complete(Unit)
        runCurrent()
        assertEquals(null, dispatcher.notSent.value)
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(2, calls)
    }

    @Test
    fun `a new countdown after Cancel isn't cut short by the raise let go`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { ms -> kotlinx.coroutines.withTimeoutOrNull(ms) { back.await() } })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        dispatcher.letGo()
        dispatcher.startCountdown(CIRCLE)
        runCurrent()
        assertEquals(5, dispatcher.countdown.value)
        // The raise let go wakes between two ticks, and finds itself let go.
        advanceTimeBy(SEC / 2)
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals("2.5 s into the new countdown", 3, dispatcher.countdown.value)
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
        assertTrue(dispatcher.cancelCountdown())
    }

    @Test
    fun `a raise waits as long as an SOS still rings, then says the circle wasn't notified`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        var tries = 0
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } answers {
            tries++
            Result.failure(java.net.UnknownHostException("x"))
        }

        dispatcher.sendNow(CIRCLE)
        advanceTimeBy(com.pingolin.track.util.SosMarks.MAX_SOS_ALARM_AGE_MS - SEC)
        runCurrent()
        assertEquals(unsent(SosDispatcher.NotSent.Why.WAITING), dispatcher.notSent.value)
        advanceTimeBy(2 * SosDispatcher.RETRY_MS)
        runCurrent()
        assertEquals(unsent(SosDispatcher.NotSent.Why.NOT_SENT), dispatcher.notSent.value)
        val after = tries
        advanceTimeBy(60 * MIN)
        runCurrent()
        assertEquals("none after", after, tries)
    }

    // ---- kept until it goes, asked when the countdown completed, the raiser's own ----

    private fun record(state: PendingRaise.State, raisedAt: Long = PRESS + 5 * SEC, told: Boolean = false) =
        PendingRaise(CIRCLE, PRESS, raisedAt, state, told)

    @Test
    fun `a raise that waited seals when the countdown completed, never when it got through`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { ms -> if (ms > 0) back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, any()) } returns SosSeal.PENDING
        val asked = mutableListOf<Long>()
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } answers {
            asked += arg<Long>(3)
            if (asked.size == 1) Result.failure(java.net.UnknownHostException("x")) else Result.success(alert)
        }

        dispatcher.startCountdown(CIRCLE)
        advanceTimeBy(5 * SEC)
        runCurrent()
        // Asked at 10:00; the phone finds a signal at 10:45.
        advanceTimeBy(45 * MIN)
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()

        assertEquals("both tries say when it was asked, the countdown's end", listOf(PRESS + 5 * SEC, PRESS + 5 * SEC), asked)
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
    }

    @Test
    fun `a raise is kept from when it is asked until it goes`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        val keptAtTry = mutableListOf<PendingRaise.State?>()
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } answers {
            keptAtTry += kept.get()?.state
            if (keptAtTry.size == 1) Result.failure(java.net.UnknownHostException("x")) else Result.success(alert)
        }

        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals("a try out: it may reach the server", listOf(PendingRaise.State.OUT), keptAtTry)
        assertEquals(record(PendingRaise.State.WAITING, raisedAt = PRESS), kept.get())

        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals(listOf(PendingRaise.State.OUT, PendingRaise.State.OUT), keptAtTry)
        assertEquals("gone once it went", null, kept.get())
    }

    @Test
    fun `after a restart a waiting raise goes on waiting and goes, as asked, and the notice follows it`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, PRESS, null) } returns SosSeal.UNAVAILABLE
        advanceTimeBy(30 * MIN)

        assertTrue(dispatcher.resumePending())
        assertEquals(SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.WAITING, PRESS + 5 * SEC), dispatcher.notSent.value)
        assertEquals(listOf<SosDispatcher.NotSent?>(dispatcher.notSent.value), notices)
        assertEquals(1, wakes)
        runCurrent()

        // Its key read first, then the try: under the circle's key now, as asked.
        assertEquals(listOf(30 * MIN), keyReadsAt.take(1))
        coVerify(exactly = 1) { sos.triggerSos(CIRCLE, SosSeal.UNAVAILABLE, PRESS, PRESS + 5 * SEC) }
        assertEquals(null, kept.get())
        assertEquals(null, notices.last())
        assertEquals(null, dispatcher.notSent.value)
    }

    @Test
    fun `after a restart a try that was out may have reached the server - never sent again, and the person is told`() = runTest {
        kept.put(record(PendingRaise.State.OUT))
        val dispatcher = dispatcher()
        advanceTimeBy(2 * MIN)

        assertEquals(false, dispatcher.resumePending())
        advanceTimeBy(30 * MIN)
        runCurrent()

        coVerify(exactly = 0) { sos.triggerSos(any(), any(), any(), any()) }
        val unsure = SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.UNSURE, PRESS + 5 * SEC)
        assertEquals(unsure, dispatcher.notSent.value)
        assertEquals(listOf<SosDispatcher.NotSent?>(unsure), notices)
        assertEquals(record(PendingRaise.State.UNSURE, told = true), kept.get())
    }

    @Test
    fun `after a restart past its 24 hours it wasn't sent, and the person is told once`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        advanceTimeBy(com.pingolin.track.util.SosMarks.MAX_SOS_ALARM_AGE_MS + MIN)
        val dispatcher = dispatcher()

        assertEquals(false, dispatcher.resumePending())
        runCurrent()
        coVerify(exactly = 0) { sos.triggerSos(any(), any(), any(), any()) }
        val notSent = SosDispatcher.NotSent(CIRCLE, SosDispatcher.NotSent.Why.NOT_SENT, PRESS + 5 * SEC)
        assertEquals(notSent, dispatcher.notSent.value)

        // The next start: shown again, but not told again.
        dispatcher().resumePending()
        assertEquals(listOf<SosDispatcher.NotSent?>(notSent), notices)
    }

    @Test
    fun `a raise goes out only as the account that raised it`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }

        // Another account signs in here before the connection is back.
        signedIn = OTHER
        back.complete(Unit)
        advanceTimeBy(10 * MIN)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }

        // Its start drops the first account's raise: nothing of it shows, and its own record is empty.
        dispatcher.resumePending()
        assertEquals(null, dispatcher.notSent.value)
        assertEquals(null, kept.get())
    }

    @Test
    fun `sign-out forgets the raise - nothing goes, nothing shows, no record stays`() = runTest {
        // An account that used this phone before kept one too: a sign-out takes every account's.
        signedIn = OTHER
        kept.put(record(PendingRaise.State.WAITING))
        signedIn = ME
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))
        dispatcher.sendNow(CIRCLE)
        runCurrent()

        dispatcher.forgetAccount()
        assertEquals(null, dispatcher.notSent.value)
        assertEquals(null, kept.get())
        signedIn = OTHER
        assertEquals("every account's", null, kept.get())
        signedIn = ME
        assertEquals(null, notices.last())
        assertTrue(wakeCancels > 0)
        back.complete(Unit)
        advanceTimeBy(10 * MIN)
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
    }

    @Test
    fun `waiting shows outside the app and asks to be woken online, and both go once it goes`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("x")),
            Result.success(alert),
        )
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(listOf<SosDispatcher.NotSent?>(unsent(SosDispatcher.NotSent.Why.WAITING)), notices)
        assertEquals(1, wakes)

        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals(null, notices.last())
        assertTrue(wakeCancels > 0)
    }

    @Test
    fun `the job that wakes a waiting raise after a restart waits for its try, and keeps itself while it still waits`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        // A process Android started for the job: nothing of the raise in it but its record.
        val woken = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))
        val first = async { woken.tryWaiting() }
        runCurrent()
        assertEquals(SosDispatcher.Waited.STILL_WAITING, first.await())

        // And the next one, once the connection really is back.
        val again = dispatcher()
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.success(alert)
        val second = async { again.tryWaiting() }
        runCurrent()
        assertEquals(SosDispatcher.Waited.DONE, second.await())
        assertEquals(null, kept.get())
    }

    @Test
    fun `the job also waits for the try of a wait already running in this process`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("x")),
            Result.success(alert),
        )
        dispatcher.sendNow(CIRCLE)
        runCurrent()

        // The phone is online: the job runs, and the wait here wakes too.
        val job = async { dispatcher.tryWaiting() }
        runCurrent()
        assertTrue("it waits for the try", !job.isCompleted)
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals(SosDispatcher.Waited.DONE, job.await())
        coVerify(exactly = 2) { sos.triggerSos(any(), any(), any(), any()) }
    }

    @Test
    fun `every try after the first reads the circle's key first`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        val keysReadByTry = mutableListOf<Int>()
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } answers {
            keysReadByTry += keyReadsAt.size
            if (keysReadByTry.size < 3) Result.failure(java.net.ConnectException("x")) else Result.success(alert)
        }
        dispatcher.sendNow(CIRCLE)
        advanceTimeBy(5 * MIN)
        runCurrent()
        assertEquals("none before the first, one before each later try", listOf(0, 1, 2), keysReadByTry)
    }

    // ---- whether a raise that went reached anyone ----

    @Test
    fun `a raise that went is checked for whom it reached, from the send, and one still waiting never is`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("x")),
            Result.success(alert),
        )
        delivery = answer("alone")
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        advanceTimeBy(2 * MIN)
        runCurrent()
        // Waiting: nothing to check, and nothing said of it but its own card and notice.
        coVerify(exactly = 0) { sos.getSosDelivery(any()) }
        assertEquals(null, dispatcher.deliveries.status.value)
        assertEquals(emptyList<SosDeliveryWatcher.Verdict>(), deliveryNotices)

        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        coVerify(exactly = 1) { sos.getSosDelivery(SOS) }
        assertEquals(SOS, dispatcher.deliveries.status.value?.alertId)
        // No screen of Track in front: the notification, once.
        assertEquals(listOf(SosDeliveryWatcher.Verdict.ALONE), deliveryNotices)
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(listOf(SosDeliveryWatcher.Verdict.ALONE), deliveryNotices)
    }

    @Test
    fun `the job that woke a raise stays while its checks run, so one that never answered is told`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        val woken = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.success(alert)
        delivery = null
        val job = async { woken.tryWaiting() }
        runCurrent()
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS / 2)
        runCurrent()
        assertTrue("it stays while the checks run", !job.isCompleted)
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS)
        runCurrent()
        assertEquals(SosDispatcher.Waited.DONE, job.await())
        assertEquals(listOf(SosDeliveryWatcher.Verdict.NO_ANSWER), deliveryNotices)
    }

    @Test
    fun `the job whose own try sends the raise is never cancelled from here - it ends itself once its checks are done`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        val woken = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.success(alert)
        delivery = null
        val job = async { woken.tryWaiting() }
        runCurrent()
        coVerify(exactly = 1) { sos.triggerSos(any(), any(), any(), any()) }
        // The send does not cancel the job running it (a cancelled job logs
        // "Work [...SosRaiseWorker] was cancelled" right after the send).
        assertEquals("sent, and the job running it is left to end itself", 0, wakeCancels)
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS + SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertEquals(SosDispatcher.Waited.DONE, job.await())
        assertEquals(listOf(SosDeliveryWatcher.Verdict.NO_ANSWER), deliveryNotices)
        assertEquals(0, wakeCancels)
    }

    @Test
    fun `the job isn't cancelled either when this process's own wait sends it while the job waits on that try`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("x")),
            Result.success(alert),
        )
        delivery = null
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        // The job runs; its resume leaves the raise to the wait here (it holds it), and waits on its try.
        val job = async { dispatcher.tryWaiting() }
        runCurrent()
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        coVerify(exactly = 2) { sos.triggerSos(any(), any(), any(), any()) }
        assertEquals(0, wakeCancels)
        assertTrue("it stays while the checks run", !job.isCompleted)
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS + SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertEquals(SosDispatcher.Waited.DONE, job.await())
        assertEquals(0, wakeCancels)
    }

    @Test
    fun `with no job run, a send still cancels the queued job - run later in a refusal, it would say Not sent yet for a raise that went`() = runTest {
        val back = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dispatcher = dispatcher(awaitConnection = { back.await() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.failure(java.net.UnknownHostException("x")),
            Result.success(alert),
        )
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals("the job queued", 1, wakes)
        back.complete(Unit)
        advanceTimeBy(SosDispatcher.RETRY_GAP_MS)
        runCurrent()
        assertEquals(SosDispatcher.Outcome.Sent(alert), dispatcher.outcome.value)
        assertEquals("and cancelled once it went", 1, wakeCancels)
    }

    @Test
    fun `a job run whose own try is refused isn't cancelled either, and ends itself`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        val woken = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(IllegalStateException("refused"))
        val job = async { woken.tryWaiting() }
        runCurrent()
        assertEquals(SosDispatcher.NotSent.Why.NOT_SENT, woken.notSent.value?.why)
        assertEquals(SosDispatcher.Waited.DONE, job.await())
        assertEquals(0, wakeCancels)
    }

    @Test
    fun `a new press that waits while the job's checks run keeps the job, which it asked for and KEEP held out`() = runTest {
        kept.put(record(PendingRaise.State.WAITING))
        val woken = dispatcher(awaitConnection = { kotlinx.coroutines.awaitCancellation() })
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returnsMany listOf(
            Result.success(alert),
            Result.failure(java.net.UnknownHostException("x")),
        )
        delivery = null
        val job = async { woken.tryWaiting() }
        runCurrent()
        // The checks run; a new press now finds no connection.
        woken.sendNow(CIRCLE)
        runCurrent()
        assertEquals(SosDispatcher.NotSent.Why.WAITING, woken.notSent.value?.why)
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS + SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertEquals(SosDispatcher.Waited.STILL_WAITING, job.await())
    }

    @Test
    fun `a job run with nothing pending takes a waiting notice down - one left as its raise went, in a race`() = runTest {
        // The raise went just as the last run returned to wait again; a refusal then would have
        // the job say "Not sent yet". Now the store is open, nothing is kept and nothing is held.
        val woken = dispatcher()
        assertEquals(SosDispatcher.Waited.DONE, woken.tryWaiting())
        assertEquals(listOf<SosDispatcher.NotSent?>(null), notices)
        // With one held, its own notice stays: nothing is taken down for it.
        kept.put(record(PendingRaise.State.WAITING))
        notices.clear()
        val again = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        coEvery { sos.triggerSos(CIRCLE, any(), any(), any()) } returns Result.failure(java.net.UnknownHostException("x"))
        val job = async { again.tryWaiting() }
        runCurrent()
        assertEquals(SosDispatcher.Waited.STILL_WAITING, job.await())
        assertTrue("never taken down while one waits", notices.isNotEmpty() && notices.all { it != null })
    }

    @Test
    fun `sign-out ends the checks and nothing is said, and another account sees none of it`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        delivery = answer("no_confirmation")
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(SOS, dispatcher.deliveries.status.value?.alertId)

        // The forced sign-out.
        dispatcher.forgetAccount()
        assertEquals(null, dispatcher.deliveries.status.value)
        delivery = answer("undeliverable")
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(emptyList<SosDeliveryWatcher.Verdict>(), deliveryNotices)

        // A plain sign-out, then another account in: the next start or sign-in drops what's left.
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        delivery = answer("confirmed")
        advanceTimeBy(SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertEquals("ended, and still shown to its account", SOS, dispatcher.deliveries.status.value?.alertId)
        signedIn = OTHER
        dispatcher.resumePending()
        assertEquals(null, dispatcher.deliveries.status.value)
    }

    @Test
    fun `an alert that ended ends its checks, and nothing is said of it`() = runTest {
        val dispatcher = dispatcher()
        coEvery { location.getCurrentLocation() } returns null
        coEvery { positions.forRaise(CIRCLE, any(), any()) } returns SosSeal.PENDING
        every { sos.ownUpdateDue(SOS) } returns true
        coEvery { sos.runOwnSos(SOS, any(), any()) } returnsMany listOf(OwnRunResult.NOTHING, OwnRunResult.ENDED)
        delivery = answer("pending")
        dispatcher.sendNow(CIRCLE)
        runCurrent()
        assertEquals(SOS, dispatcher.deliveries.status.value?.alertId)
        // The second run, 30 s on, finds it over (closed here or anywhere else).
        advanceTimeBy(SosDispatcher.UPDATE_POLL_MS + SEC)
        runCurrent()
        assertEquals(null, dispatcher.deliveries.status.value)
        advanceTimeBy(10 * MIN)
        runCurrent()
        assertEquals(emptyList<SosDeliveryWatcher.Verdict>(), deliveryNotices)
    }
}
