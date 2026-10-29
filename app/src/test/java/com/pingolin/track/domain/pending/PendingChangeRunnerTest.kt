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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.QuotaException
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Running changes waiting to sync: only when this phone holds the circle's current version,
 * oldest first per circle, ended by age, each with its own outcome.
 */
class PendingChangeRunnerTest {

    private var clock = 10_000_000L
    private val dao = InMemoryPendingChangeDao()
    private var account = "me"
    private val sessionManager = mockk<SessionManager> {
        every { getUserId() } answers { account }
        every { isLoggedIn() } returns true
    }
    private val scheduler = mockk<PendingChangeScheduler>(relaxed = true)
    private fun circle(id: String) = CircleEntity(id = id, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)
    private val circleDao = mockk<CircleDao> { coEvery { getById(any()) } answers { circle(arg(0)) } }
    private val store = PendingChanges(dao, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))
        .also { it.now = { clock } }

    /**
     * The circles the server lists for this account when it gives the list WHOLE (null: the
     * refresh failed, or the list had another page), and the ones this phone is behind on.
     */
    private var listed: Set<String>? = setOf("c-1", "c-2")
    private val behind = mutableSetOf<String>()
    private val circleRepository = mockk<CircleRepository> {
        coEvery { refreshCirclesWhole() } answers { listed }
        coEvery { holdsCurrentKey(any()) } answers { arg<String>(0) !in behind }
    }

    /** What each change's run comes to, in order; Done when nothing is scripted. */
    private val script = mutableMapOf<String, ArrayDeque<() -> PendingOutcome>>()
    private val ran = mutableListOf<Pair<String, PendingRun>>()
    private val executor = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            assertEquals("marked going out before it runs", PendingChanges.RUNNING, dao.get(change.id)?.state)
            ran += change.id to run
            return script[change.id]?.removeFirstOrNull()?.invoke() ?: PendingOutcome.Done()
        }
    }
    private val runner = PendingChangeRunner(dao, store, sessionManager, circleDao, circleRepository, scheduler, mockk(relaxed = true))
        .also { it.executorFor = { executor } }

    private suspend fun keep(circleId: String = "c-1", message: String = "m"): String {
        clock += 3_000
        val inputs = PendingInputs.CheckIn("Family", "safe_arrival", message, null, null, null, clock)
        return (store.queue<Unit>(circleId, inputs).exceptionOrNull() as QueuedForSync).changeId
    }

    /** A share of place p-1 in c-1 (merged per place, 7 days), at the clock as it is. */
    private suspend fun keepShare(name: String): String =
        (store.queue<Unit>("c-1", PendingInputs.SharePlace("Family", "p-1", name)).exceptionOrNull() as QueuedForSync).changeId

    private fun then(id: String, vararg outcomes: () -> PendingOutcome) {
        script.getOrPut(id) { ArrayDeque() }.addAll(outcomes)
    }

    @Test
    fun `nothing runs while this phone is behind, and it runs once through the live function when it catches up`() = runTest {
        val id = keep()
        behind += "c-1"

        runner.runOnce()
        assertTrue(ran.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)

        behind -= "c-1"
        runner.runOnce()
        assertEquals(listOf(id), ran.map { it.first })
        assertEquals("the create carries the change's own id", id, ran.single().second.requestId)
        assertNull(dao.get(id))
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `the live gate saying behind after all puts it back to waiting, and it is not lost`() = runTest {
        val id = keep()
        then(id, { PendingChangeRunner.classify(KeyNotCurrentException(), subject(), "Family") })

        runner.runOnce()

        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertEquals(0, dao.get(id)?.attempts)
    }

    @Test
    fun `too old, dropped with its line and never sent, even while behind`() = runTest {
        val id = keep()
        behind += "c-1"
        clock += PendingKind.DAY_MS + 1

        runner.runOnce()

        assertTrue(ran.isEmpty())
        assertNull(dao.get(id))
        assertEquals("Your check-in wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
    }

    @Test
    fun `oldest first, a transient failure of B keeps C from running before it`() = runTest {
        val a = keep(message = "a")
        val b = keep(message = "b")
        val c = keep(message = "c")
        then(b, { PendingOutcome.Transient(maybeSent = true) })

        runner.runOnce()

        assertEquals(listOf(a, b), ran.map { it.first })
        assertNull(dao.get(a))
        assertEquals(PendingChanges.WAITING, dao.get(c)?.state)
        val waiting = dao.get(b)!!
        assertEquals(1, waiting.attempts)
        assertEquals(clock + 30_000, waiting.nextAttemptAt)
        assertEquals(PendingChanges.LOST_ANSWER, waiting.lastOutcome)
        verify { scheduler.at(clock + 30_000) }

        // Not due yet: nothing after it goes either.
        runner.runOnce()
        assertEquals(2, ran.size)

        clock += 30_001
        runner.runOnce()
        assertEquals(listOf(a, b, b, c), ran.map { it.first })
    }

    @Test
    fun `a permanent failure is dropped with its line and the next one still runs`() = runTest {
        val a = keep(message = "a")
        val b = keep(message = "b")
        then(a, { PendingChangeRunner.classify(RefusedException("x", 400, null), subject(), "Family") })

        runner.runOnce()

        assertEquals(listOf(a, b), ran.map { it.first })
        assertEquals("Your check-in wasn't done.", dao.notices.value.single().line)
        assertTrue(dao.changes.value.isEmpty())
    }

    @Test
    fun `backoff, 30 s, then 1, 2, 4 minutes, at most hourly`() {
        assertEquals(30_000L, PendingChangeRunner.backoffMs(1))
        assertEquals(60_000L, PendingChangeRunner.backoffMs(2))
        assertEquals(120_000L, PendingChangeRunner.backoffMs(3))
        assertEquals(240_000L, PendingChangeRunner.backoffMs(4))
        assertEquals(60 * 60_000L, PendingChangeRunner.backoffMs(40))
    }

    @Test
    fun `a circle this account is no longer in ends its changes as no longer existing`() = runTest {
        val id = keep(circleId = "c-2")
        listed = setOf("c-1")

        runner.runOnce()

        assertTrue(ran.isEmpty())
        assertNull(dao.get(id))
        assertEquals("Your check-in wasn't done: Family no longer exists.", dao.notices.value.single().line)
    }

    @Test
    fun `without a whole circle list, nothing is judged gone`() = runTest {
        val id = keep(circleId = "c-2")
        listed = null
        behind += "c-2"

        runner.runOnce()

        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `a circle with no row on this phone and no whole list to judge by waits`() = runTest {
        val id = keep(circleId = "c-3")
        listed = null
        coEvery { circleDao.getById("c-3") } returns null

        runner.runOnce()

        assertTrue("nothing ran", ran.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `a merge on day 6 of a change that never went out restarts its age limit`() = runTest {
        behind += "c-1"
        val start = clock
        val id = keepShare("Home")
        clock = start + 6 * PendingKind.DAY_MS
        assertEquals("merged into it", id, keepShare("Home, renamed"))

        clock = start + 8 * PendingKind.DAY_MS
        runner.runOnce()
        assertEquals("still waiting on day 8", PendingChanges.WAITING, dao.get(id)?.state)

        clock = start + 13 * PendingKind.DAY_MS + 1
        runner.runOnce()
        assertNull(dao.get(id))
        assertEquals("Sharing Home, renamed wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `with no merge, a 7-day change ends after day 7`() = runTest {
        behind += "c-1"
        val start = clock
        val id = keepShare("Home")

        clock = start + 7 * PendingKind.DAY_MS + 1
        runner.runOnce()

        assertNull(dao.get(id))
        assertEquals("Sharing Home wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
    }

    @Test
    fun `sent with no answer on day 1 and merged on day 6, it keeps its limit, ends after day 7 and is never sent after`() = runTest {
        val start = clock
        val id = keepShare("Home")
        clock = start + PendingKind.DAY_MS
        then(id, { PendingOutcome.Transient(maybeSent = true) })
        runner.runOnce()
        assertEquals(PendingChanges.LOST_ANSWER, dao.get(id)?.lastOutcome)

        behind += "c-1"
        clock = start + 6 * PendingKind.DAY_MS
        assertEquals(id, keepShare("Home, renamed"))
        assertEquals("the server's request-id record still covers every resend", start + 7 * PendingKind.DAY_MS, dao.get(id)?.expiresAt)

        behind -= "c-1"
        clock = start + 7 * PendingKind.DAY_MS + 1
        runner.runOnce()

        assertEquals("only the day-1 try", listOf(id), ran.map { it.first })
        assertNull(dao.get(id))
        assertEquals("Sharing Home, renamed wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
    }

    @Test
    fun `a change found going out as a pass starts may have gone out - a merge then keeps its limit`() = runTest {
        val start = clock
        val id = keepShare("Home")
        dao.update(dao.get(id)!!.copy(state = PendingChanges.RUNNING, attemptId = "old", startedAt = clock))
        behind += "c-1"

        runner.runOnce()

        val marked = dao.get(id)!!
        assertEquals(PendingChanges.WAITING, marked.state)
        assertEquals(PendingChanges.LOST_ANSWER, marked.lastOutcome)
        clock = start + 6 * PendingKind.DAY_MS
        keepShare("Home, renamed")
        assertEquals(start + 7 * PendingKind.DAY_MS, dao.get(id)?.expiresAt)
    }

    @Test
    fun `a running mark that couldn't be written sends nothing`() = runTest {
        val id = keep()
        val failing = object : com.pingolin.track.data.local.dao.PendingChangeDao by dao {
            override suspend fun update(change: PendingChangeEntity) {
                if (change.state == PendingChanges.RUNNING) throw IllegalStateException("disk full")
                dao.update(change)
            }
        }
        val failingStore = PendingChanges(failing, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))
            .also { it.now = { clock } }
        val failingRunner = PendingChangeRunner(failing, failingStore, sessionManager, circleDao, circleRepository, scheduler, mockk(relaxed = true))
            .also { it.executorFor = { executor } }

        runCatching { failingRunner.runOnce() }

        assertTrue("nothing sent", ran.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
    }

    @Test
    fun `found going out after a restart, it is checked and sent again with the same id`() = runTest {
        val id = keep()
        dao.update(dao.get(id)!!.copy(state = PendingChanges.RUNNING, attemptId = "old", startedAt = clock))

        runner.runOnce()

        assertEquals(listOf(id), ran.map { it.first })
        assertEquals(id, ran.single().second.requestId)
        assertNull(dao.get(id))
    }

    @Test
    fun `a session the server refuses stops the run, and nothing else goes`() = runTest {
        val a = keep(circleId = "c-1")
        val b = keep(circleId = "c-2")
        then(a, { PendingChangeRunner.classify(RefusedException("x", 401, null), subject(), "Family") })

        runner.runOnce()

        assertEquals(listOf(a), ran.map { it.first })
        assertEquals(PendingChanges.WAITING, dao.get(a)?.state)
        assertEquals(PendingChanges.WAITING, dao.get(b)?.state)
    }

    @Test
    fun `nothing runs for another account`() = runTest {
        val id = keep()
        account = "someone-else"

        runner.runOnce()

        assertTrue(ran.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
    }

    @Test
    fun `the account is checked again before each change runs`() = runTest {
        val a = keep(message = "a")
        val b = keep(message = "b")
        then(a, { account = "someone-else"; PendingOutcome.Done() })

        runner.runOnce()

        assertEquals(listOf(a), ran.map { it.first })
        assertEquals(PendingChanges.WAITING, dao.get(b)?.state)
    }

    @Test
    fun `a run stopped part-way is never taken as its answer - back to waiting, as a lost answer`() = runTest {
        val id = keep()
        runner.executorFor = {
            object : PendingExecutor {
                override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
                    // The job stops mid-request (no network any more, a sign-out), and the
                    // repository folds that into its failed Result, which reads as a refusal.
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                    return PendingChangeRunner.classify(IllegalStateException("stopped"), subject(), "Family")
                }
            }
        }

        val job = launch { runner.runOnce() }
        job.join()

        assertTrue(job.isCancelled)
        val change = dao.get(id)!!
        assertEquals(PendingChanges.WAITING, change.state)
        assertEquals(PendingChanges.LOST_ANSWER, change.lastOutcome)
        assertNull(change.attemptId)
        assertTrue("dropped with no line, never", dao.notices.value.isEmpty())
    }

    @Test
    fun `a store that can't be read decides nothing - the change waits, nothing sent`() {
        assertEquals(
            PendingOutcome.Transient(maybeSent = false),
            PendingChangeRunner.classify(com.pingolin.track.util.StoreUnreadable("own removals"), subject(), "Family"),
        )
    }

    @Test
    fun `a time limit inside a run, while the job goes on, waits like no answer`() {
        assertEquals(
            PendingOutcome.Transient(maybeSent = true),
            PendingChangeRunner.classify(kotlinx.coroutines.CancellationException("timed out"), subject(), "Family"),
        )
    }

    @Test
    fun `a change that needs a person waits and does not hold the others up`() = runTest {
        val a = keep(message = "a")
        val b = keep(message = "b")
        then(a, { PendingOutcome.NeedsPerson(PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.MEMBER, "sam", "s", "g"))) })

        runner.runOnce()

        assertEquals(PendingChanges.NEEDS_PERSON, dao.get(a)?.state)
        assertNull(dao.get(b))
        runner.runOnce()
        assertEquals("asked, never run again by itself", listOf(a, b), ran.map { it.first })
    }

    @Test
    fun `the server's answers, by status and code, never by text`() {
        val s = subject()
        assertEquals(PendingOutcome.Done(), PendingChangeRunner.classify(RefusedException("x", 409, "request_already_done"), s, "Family"))
        assertEquals(PendingOutcome.Transient(maybeSent = false), PendingChangeRunner.classify(RefusedException("x", 429, null), s, "Family"))
        assertEquals(PendingOutcome.Transient(maybeSent = true), PendingChangeRunner.classify(RefusedException("x", 503, null), s, "Family"))
        assertEquals(
            "a 404 at send is not \"gone\": the next re-check decides from whole lists",
            PendingOutcome.Transient(maybeSent = false),
            PendingChangeRunner.classify(RefusedException("x", 404, null), s, "Family"),
        )
        assertEquals(
            "a 403 at send is \"couldn't run\": the re-check decides who may, from the whole roster",
            PendingOutcome.Transient(maybeSent = false),
            PendingChangeRunner.classify(RefusedException("x", 403, null), s, "Family"),
        )
        assertEquals(PendingOutcome.Transient(maybeSent = false), PendingChangeRunner.classify(java.net.UnknownHostException(), s, "Family"))
        assertEquals(PendingOutcome.Transient(maybeSent = true), PendingChangeRunner.classify(java.net.SocketTimeoutException(), s, "Family"))
        assertEquals(PendingOutcome.Stop, PendingChangeRunner.classify(RefusedException("x", 401, null), s, "Family"))
        assertEquals(
            PendingOutcome.Dropped("Your check-in wasn't done: Family has reached its limit of 5 places on the free plan."),
            PendingChangeRunner.classify(QuotaException("x", "quota_places_exceeded", QuotaError("places", true, 5, 5, "free")), s, "Family"),
        )
        assertEquals(
            "a feature gate has no count to name",
            PendingOutcome.Dropped("Your check-in wasn't done."),
            PendingChangeRunner.classify(QuotaException("x", "premium_required_destinations", QuotaError("destinations", false, 0, 0, "free")), s, "Family"),
        )
        assertEquals(PendingOutcome.Dropped("Your check-in wasn't done."), PendingChangeRunner.classify(RefusedException("Server says: key 00ff", 400, null), s, "Family"))
    }

    private fun subject() = PendingInputs.subject(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0))
}
