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

import com.pingolin.track.data.remote.dto.SosDeliveryResponse
import com.pingolin.track.domain.sos.SosDeliveryWatcher.Status
import com.pingolin.track.domain.sos.SosDeliveryWatcher.Verdict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As on iOS, whether a help request that went reached anyone is checked for the whole
 * application, and a request that reached nobody, or whose checks never answered, is told once,
 * where the person will see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SosDeliveryWatcherTest {

    private companion object {
        const val SOS = "0505abcd-0000-4000-8000-00000000abcd"
        const val ME = "beef0000-0000-4000-8000-00000000000a"
        const val OTHER = "a11ce000-0000-4000-8000-00000000a11c"
        const val SEC = 1_000L
    }

    private var signedIn: String? = ME

    /** What each check answers: a test changes it, or makes a check wait ([held]). */
    private var answer: SosDeliveryResponse? = null
    private var held: CompletableDeferred<Unit>? = null
    private var fetches = 0

    private val notified = mutableListOf<Verdict>()
    private var noticesTakenDown = 0

    private fun answer(state: String?, confirmed: Int = 0) =
        state?.let { SosDeliveryResponse(recipients = 2, addressable = 2, dispatched = 2, emailed = 0, confirmed = confirmed, state = it) }

    private fun TestScope.watcher() = SosDeliveryWatcher(
        fetch = {
            fetches++
            held?.await()
            answer
        },
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        account = { signedIn },
        now = { testScheduler.currentTime },
        notify = { notified += it },
        cancelNotice = { noticesTakenDown++ },
    )

    private fun status(state: String?, stillChecking: Boolean = false) = Status(SOS, answer(state), stillChecking)

    @Test
    fun `what is told once the checks end - reached nobody, or no answer, and nothing for an answer that reached someone`() {
        assertEquals(Verdict.ALONE, SosDeliveryWatcher.verdictOf(status("alone")))
        assertEquals(Verdict.UNDELIVERABLE, SosDeliveryWatcher.verdictOf(status("undeliverable")))
        assertEquals("no answer at all", Verdict.NO_ANSWER, SosDeliveryWatcher.verdictOf(status(null)))
        assertEquals("one still on its way", Verdict.NO_ANSWER, SosDeliveryWatcher.verdictOf(status("pending")))
        for (reached in listOf("confirmed", "partial", "no_confirmation")) {
            assertNull(reached, SosDeliveryWatcher.verdictOf(status(reached)))
        }
        // Never while the checks run.
        assertNull(SosDeliveryWatcher.verdictOf(status("alone", stillChecking = true)))
        assertNull(SosDeliveryWatcher.verdictOf(status(null, stillChecking = true)))
    }

    @Test
    fun `confirmed and alone end the checks on sight, undeliverable only after 30 s, and a minute at most`() = runTest {
        val watcher = watcher()
        answer = answer("confirmed", confirmed = 2)
        watcher.watch(SOS)
        assertEquals("checking from the send", Status(SOS, null, true), watcher.status.value)
        runCurrent()
        assertEquals(Status(SOS, answer, false), watcher.status.value)
        assertEquals(1, fetches)
        assertEquals("it reached someone: nothing to tell", emptyList<Verdict>(), notified)

        fetches = 0
        answer = answer("undeliverable")
        watcher.watch(SOS)
        runCurrent()
        assertTrue("not believed yet", watcher.status.value!!.stillChecking)
        advanceTimeBy(SosDeliveryWatcher.SETTLE_MS + SEC)
        runCurrent()
        assertEquals(Status(SOS, answer, false), watcher.status.value)
        assertEquals(listOf(Verdict.UNDELIVERABLE), notified)

        fetches = 0
        answer = answer("partial", confirmed = 1)
        watcher.watch(SOS)
        runCurrent()
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS + SEC)
        runCurrent()
        assertEquals(Status(SOS, answer, false), watcher.status.value)
        assertEquals("every 3 s for a minute", 21, fetches)
        advanceTimeBy(10 * SosDeliveryWatcher.WINDOW_MS)
        runCurrent()
        assertEquals("then no more", 21, fetches)
        assertEquals("an answer that reached someone: nothing more to tell", listOf(Verdict.UNDELIVERABLE), notified)
    }

    @Test
    fun `told where the person will see it - the Safety tab's own dialog, else a dialog over Track, else a notification`() = runTest {
        val watcher = watcher()
        answer = answer("alone")

        // The Safety tab is up, with its dialog: that says it.
        watcher.safetyTabOpen = true
        watcher.trackInFront = true
        watcher.watch(SOS)
        runCurrent()
        assertNull(watcher.untold.value)
        assertEquals(emptyList<Verdict>(), notified)

        // Another screen of Track: the dialog over it.
        watcher.safetyTabOpen = false
        watcher.watch(SOS)
        runCurrent()
        assertEquals(Verdict.ALONE, watcher.untold.value)
        assertEquals(emptyList<Verdict>(), notified)
        watcher.told()
        assertNull(watcher.untold.value)

        // Nothing of Track in front: the notification.
        watcher.trackInFront = false
        answer = null
        watcher.watch(SOS)
        runCurrent()
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS + SEC)
        runCurrent()
        assertNull(watcher.untold.value)
        assertEquals(listOf(Verdict.NO_ANSWER), notified)
        assertEquals("the card says so too, never Checking… for good", Status(SOS, null, false), watcher.status.value)
    }

    @Test
    fun `said once, however long it is left`() = runTest {
        val watcher = watcher()
        answer = answer("alone")
        watcher.watch(SOS)
        runCurrent()
        advanceTimeBy(5 * SosDeliveryWatcher.WINDOW_MS)
        runCurrent()
        assertEquals(listOf(Verdict.ALONE), notified)
        assertEquals(1, fetches)
    }

    @Test
    fun `OK on the tab's dialog before the answer only puts it away - the checks go on, and reaching nobody is still told`() = runTest {
        val watcher = watcher()
        watcher.safetyTabOpen = true
        watcher.trackInFront = true
        answer = answer("pending")
        watcher.watch(SOS)
        runCurrent()
        watcher.putAway()
        assertNull("the dialog goes", watcher.status.value)

        answer = answer("undeliverable")
        advanceTimeBy(SosDeliveryWatcher.SETTLE_MS + 2 * SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertNull("and doesn't come back at each check", watcher.status.value)
        // The tab is still open, but its dialog was put away: the dialog over Track says it.
        assertEquals(Verdict.UNDELIVERABLE, watcher.untold.value)

        // Once they have ended, OK leaves nothing to report.
        answer = answer("confirmed", confirmed = 2)
        watcher.watch(SOS)
        runCurrent()
        watcher.putAway()
        assertNull(watcher.status.value)
    }

    @Test
    fun `nothing is said of a request that ended, or after its account signed out, and another account sees none of it`() = runTest {
        val watcher = watcher()
        answer = answer("pending")
        watcher.watch(SOS)
        runCurrent()
        // Another alert ending changes nothing here; this one's (in any spelling) ends it.
        watcher.stop("aaaaaaaa-0000-4000-8000-000000000000")
        assertEquals(SOS, watcher.status.value?.alertId)
        watcher.stop(SOS.uppercase())
        assertNull(watcher.status.value)
        assertEquals("none was posted", 0, noticesTakenDown)
        advanceTimeBy(2 * SosDeliveryWatcher.WINDOW_MS)
        runCurrent()
        assertEquals(emptyList<Verdict>(), notified)

        // Signed out while a check is out: what it brings back is said to nobody.
        held = CompletableDeferred()
        answer = answer("alone")
        watcher.watch(SOS)
        runCurrent()
        signedIn = null
        held!!.complete(Unit)
        runCurrent()
        assertEquals(emptyList<Verdict>(), notified)
        assertNull(watcher.untold.value)

        // An answer kept for its account is dropped when another signs in.
        held = null
        signedIn = ME
        answer = answer("confirmed", confirmed = 2)
        watcher.watch(SOS)
        runCurrent()
        watcher.keepOnlyOurs()
        assertEquals(SOS, watcher.status.value?.alertId)
        signedIn = OTHER
        watcher.keepOnlyOurs()
        assertNull(watcher.status.value)
    }

    @Test
    fun `a notification about a request that ended is taken down, even after its dialog was put away`() = runTest {
        val watcher = watcher()
        answer = answer("alone")
        watcher.watch(SOS)
        runCurrent()
        assertEquals(listOf(Verdict.ALONE), notified)
        // Back in Track, the tab's dialog says it too, and OK puts it away: nothing else is held.
        watcher.putAway()
        // Another alert ending leaves it; this one's takes it down, once.
        watcher.stop("aaaaaaaa-0000-4000-8000-000000000000")
        assertEquals(0, noticesTakenDown)
        watcher.stop(SOS)
        assertEquals(1, noticesTakenDown)
        watcher.stop(SOS)
        assertEquals(1, noticesTakenDown)

        // A sign-out takes down whatever is up.
        watcher.watch(SOS)
        runCurrent()
        watcher.stop()
        assertEquals(2, noticesTakenDown)
    }

    @Test
    fun `a check that fails is no answer, and the checks go on`() = runTest {
        var calls = 0
        val watcher = SosDeliveryWatcher(
            fetch = {
                calls++
                if (calls == 1) throw java.io.IOException("x") else answer("alone")
            },
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            account = { signedIn },
            now = { testScheduler.currentTime },
            notify = { notified += it },
        )
        watcher.watch(SOS)
        runCurrent()
        advanceTimeBy(SosDeliveryWatcher.POLL_MS)
        runCurrent()
        assertEquals(listOf(Verdict.ALONE), notified)
    }

    @Test
    fun `the job that woke a raise can wait for the checks to end`() = runTest {
        val watcher = watcher()
        answer = null
        watcher.watch(SOS)
        val waiting = async { watcher.awaitChecks(2 * SosDeliveryWatcher.WINDOW_MS) }
        runCurrent()
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS / 2)
        runCurrent()
        assertTrue(!waiting.isCompleted)
        advanceTimeBy(SosDeliveryWatcher.WINDOW_MS)
        runCurrent()
        assertTrue(waiting.isCompleted)
        assertEquals(listOf(Verdict.NO_ANSWER), notified)
    }
}
