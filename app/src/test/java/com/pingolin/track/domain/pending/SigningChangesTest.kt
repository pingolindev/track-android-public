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
import com.pingolin.track.data.local.dao.PendingChangeDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The changes that sign, and their statement ids: every statement id a run signs is saved
 * before its request leaves; after a lost answer the server is asked before anything is
 * signed again; when the change ends, every saved id but the one the server holds goes dead,
 * durably, and only on a positive answer; the look waits 2 minutes after a send; at most 20
 * ids per change; Cancel and a look that can't run park the change with nothing killed.
 */
class SigningChangesTest {

    private var clock = 50_000_000L
    private val dao = InMemoryPendingChangeDao()
    private val sessionManager = mockk<SessionManager> {
        every { getUserId() } returns "me"
        every { isLoggedIn() } returns true
    }
    private val scheduler = mockk<PendingChangeScheduler>(relaxed = true)
    private val circleDao = mockk<CircleDao> {
        coEvery { getById(any()) } answers { CircleEntity(id = arg(0), name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0) }
    }
    private val store = PendingChanges(dao, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))
        .also { it.now = { clock } }
    private var listed: Set<String>? = setOf("c-1")
    private var behind = false
    private val circleRepository = mockk<CircleRepository> {
        coEvery { refreshCirclesWhole() } answers { listed }
        coEvery { holdsCurrentKey(any()) } answers { !behind }
    }

    /** What the server holds, as the look reads it; null: the look can't run; "404": not found. */
    private var held: Set<String>? = emptySet()
    private var lookNotFound = false
    private val looks = mutableListOf<List<String>>()

    /** What each run of the executor does: sign (save an id first) and answer. */
    private var nextStatement = 1
    private val signedByRuns = mutableListOf<String>()
    private val runs = mutableListOf<PendingRun>()
    private var answers = ArrayDeque<suspend (PendingRun) -> PendingOutcome>()
    private val executor = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            runs += run
            return answers.removeFirstOrNull()?.invoke(run) ?: PendingOutcome.Done()
        }
    }

    /** The settles the runner did: the id kept alive, and the ids killed. */
    private val settled = mutableListOf<Pair<String?, List<String>>>()
    private var settleWorks = true

    private fun runner(changes: PendingChangeDao = dao, changeStore: PendingChanges = store) =
        PendingChangeRunner(changes, changeStore, sessionManager, circleDao, circleRepository, scheduler, mockk(relaxed = true)).also { r ->
            r.executorFor = { executor }
            r.look = { _, ids ->
                looks += ids
                when {
                    lookNotFound -> LookAnswer.NotFound
                    held == null -> LookAnswer.CouldNotLook
                    else -> LookAnswer.Held(ids.map { PendingChanges.hexId(it) }.filter { it in held!!.map { h -> PendingChanges.hexId(h) } }.toSet())
                }
            }
            r.settle = { _, h, dead -> if (settleWorks) { settled += h?.id to dead; true } else false }
            r.signingKeyMissing = { false }
            r.notifyQuestion = { _, _, _ -> }
            r.recordLetIn = { change -> recordedChoices += change.id; recordWorks }
        }

    /** A cancelled "Let them in" whose own admission was held: the choice is
     * recorded all the same.
     */
    private val recordedChoices = mutableListOf<String>()
    private var recordWorks = true

    private val runner = runner()

    /** A statement signed and saved before its request leaves, as the live functions do. */
    private suspend fun sign(run: PendingRun): String {
        val id = "00000000-0000-4000-8000-" + (nextStatement++).toString().padStart(12, '0')
        run.onSigned!!.invoke(SignedStatement(id = id, subject = "dan", expiresAtSecs = 1_900_000_000))
        signedByRuns += id
        return id
    }

    private suspend fun keepInvite(): String =
        (store.queue<Unit>("c-1", PendingInputs.Invite("Family", "dan@x.test")).exceptionOrNull() as QueuedForSync).changeId

    private fun afterLookWindow() {
        clock += PendingChangeRunner.LOOK_AFTER_SEND_MS + 1
    }

    @Test
    fun `5xx twice, then 2xx - exactly one id is alive, the two spares go dead`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        afterLookWindow()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        afterLookWindow()
        answers += { r -> sign(r); PendingOutcome.Done() }
        runner.runOnce()

        assertNull("done", dao.get(id))
        assertEquals(3, signedByRuns.size)
        assertEquals(listOf(signedByRuns[2] to listOf(signedByRuns[0], signedByRuns[1])), settled)
        assertEquals("looked before each new signature", 2, looks.size)
    }

    @Test
    fun `a lost answer the server stored - no second signature, done with that id`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        held = setOf(signedByRuns.single())
        afterLookWindow()

        runner.runOnce()

        assertEquals("never run again", 1, runs.size)
        assertNull(dao.get(id))
        assertEquals(listOf(signedByRuns.single() to emptyList<String>()), settled)
    }

    @Test
    fun `no look sooner than 2 minutes after the newest send`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        clock += 31_000

        runner.runOnce()

        assertTrue("not looked yet", looks.isEmpty())
        assertEquals(1, runs.size)
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertTrue(dao.get(id)!!.nextAttemptAt >= PendingChanges.signed(dao.get(id)!!)!!.single().at + PendingChangeRunner.LOOK_AFTER_SEND_MS)
    }

    @Test
    fun `a look that can't run kills nothing and signs nothing - the change waits`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        held = null
        afterLookWindow()

        runner.runOnce()

        assertEquals(1, runs.size)
        assertTrue(settled.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertEquals(1, PendingChanges.signedIds(dao.get(id)!!).size)
    }

    @Test
    fun `a look's 404 while the circle is still listed - parked, ids kept, nothing dropped`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        lookNotFound = true
        afterLookWindow()

        runner.runOnce()

        assertTrue(settled.isEmpty())
        assertEquals(1, PendingChanges.signedIds(dao.get(id)!!).size)
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `a parked change whose look answers 404 stays parked with nothing killed, until the whole circle list agrees`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        store.cancel(id)
        lookNotFound = true
        afterLookWindow()

        runner.runOnce()

        assertEquals("still listed: couldn't look", PendingChanges.CANCELLING, dao.get(id)?.state)
        assertTrue(settled.isEmpty())
        listed = null
        runner.runOnce()
        assertEquals("no whole list: nothing decided", PendingChanges.CANCELLING, dao.get(id)?.state)
        // Listed as the pass starts; by the look, the whole list read again says it's gone.
        var reads = 0
        coEvery { circleRepository.refreshCirclesWhole() } answers { if (reads++ == 0) setOf("c-1") else setOf("c-2") }
        runner.runOnce()
        assertNull("gone for this account, as the whole list says", dao.get(id))
        assertTrue("nothing settled against a circle that isn't this account's", settled.isEmpty())
    }

    @Test
    fun `at 20 saved ids and none held, it is dropped as not done after its settling look, never signed again`() = runTest {
        val id = keepInvite()
        var change = dao.get(id)!!
        repeat(PendingChangeRunner.MAX_SIGNED) { n ->
            change = PendingChanges.withSigned(change, SignedStatement(id = "00000000-0000-4000-9000-${n.toString().padStart(12, '0')}", at = clock))
        }
        dao.update(change.copy(lastOutcome = PendingChanges.LOST_ANSWER))
        afterLookWindow()

        runner.runOnce()

        assertTrue("never signed again", runs.isEmpty())
        assertNull(dao.get(id))
        assertEquals("dan@x.test's invitation wasn't done.", dao.notices.value.single().line)
        assertEquals(PendingChangeRunner.MAX_SIGNED, settled.single().second.size)
    }

    @Test
    fun `a dead-id write that fails parks the change - it ends only once the ids are saved dead`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); sign(r); PendingOutcome.Done() }
        settleWorks = false

        runner.runOnce()

        assertEquals(PendingChanges.SETTLING, dao.get(id)?.state)
        assertTrue(dao.notices.value.isEmpty())
        settleWorks = true
        held = setOf(signedByRuns[1])
        afterLookWindow()
        runner.runOnce()
        assertNull(dao.get(id))
        assertEquals(listOf(signedByRuns[1] to listOf(signedByRuns[0])), settled)
    }

    @Test
    fun `Cancel after a lost answer - Cancelling, then already gone out when the server holds one of its statements`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        assertEquals(PendingChanges.Cancelled.CANCELLING, store.cancel(id))
        held = setOf(signedByRuns.single())
        afterLookWindow()

        runner.runOnce()

        assertNull(dao.get(id))
        assertEquals("dan@x.test's invitation had already gone out, so it couldn't be cancelled.", dao.notices.value.single().line)
        assertEquals(1, runs.size)
    }

    @Test
    fun `Cancel after a lost answer - removed with its ids dead when the server holds none`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        store.cancel(id)
        afterLookWindow()

        runner.runOnce()

        assertNull(dao.get(id))
        assertTrue(dao.notices.value.isEmpty())
        assertEquals(listOf(null to signedByRuns.toList()), settled)
    }

    @Test
    fun `Cancel whose look can't run stays Cancelling, with nothing killed, even while behind`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        store.cancel(id)
        held = null
        behind = true
        afterLookWindow()

        runner.runOnce()

        assertEquals(PendingChanges.CANCELLING, dao.get(id)?.state)
        assertTrue(settled.isEmpty())
        held = emptySet()
        runner.runOnce()
        assertNull("settled by the next look, behind or not", dao.get(id))
    }

    @Test
    fun `an invite refused as already asks the look - its own held is done quietly, else its line`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        afterLookWindow()
        answers += { r -> sign(r); PendingOutcome.Already("dan@x.test already has an invitation to Family.") }
        held = emptySet()
        // The second run's "already": the look, answered at once, finds the first one held.
        runner.look = { _, ids -> looks += ids; if (looks.size == 1) LookAnswer.Held(emptySet()) else LookAnswer.Held(setOf(PendingChanges.hexId(signedByRuns[0]))) }

        runner.runOnce()

        assertNull(dao.get(id))
        assertTrue("its own invitation: no line", dao.notices.value.isEmpty())
        assertEquals(listOf(signedByRuns[0] to listOf(signedByRuns[1])), settled)
    }

    @Test
    fun `a promotion - the look finds its admission stored, and only the role is left`() = runTest {
        val id = (store.queue<Unit>("c-1", PendingInputs.MakeAdmin("Family", "sam", "Sam", "s", "g")).exceptionOrNull() as QueuedForSync).changeId
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        held = setOf(signedByRuns.single())
        afterLookWindow()
        answers += { r -> assertTrue("only the role left", r.admissionHeld); PendingOutcome.Done() }

        runner.runOnce()

        assertEquals(2, runs.size)
        assertNull(dao.get(id))
        assertEquals(listOf(signedByRuns.single() to emptyList<String>()), settled)
    }

    @Test
    fun `a let-in whose member someone else let in after a lost answer - already let in, and its own ids go dead`() = runTest {
        val id = (store.queue<Unit>("c-1", PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s", "g")).exceptionOrNull() as QueuedForSync).changeId
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        afterLookWindow()
        answers += { PendingOutcome.Done("Sam was already let in.") }

        runner.runOnce()

        assertNull(dao.get(id))
        assertEquals("Sam was already let in.", dao.notices.value.single().line)
        assertEquals(listOf(null to signedByRuns.toList()), settled)
        assertEquals("one look decides", 1, looks.size)
    }

    @Test
    fun `a question is taken to the person once, by the existing notifier`() = runTest {
        val id = keepInvite()
        val ask = PendingQuestions.Ask(PendingQuestions.INVITEE, "u-dan", "a".repeat(64), "b".repeat(64), "1 2 3")
        answers += { PendingOutcome.NeedsPerson(PendingQuestions.ask(ask)) }
        val notified = mutableListOf<PendingQuestions.Ask>()
        runner.notifyQuestion = { _, _, a -> notified += a }

        runner.runOnce()
        runner.runOnce()

        assertEquals(listOf(ask), notified)
        assertEquals("never run while it waits for them", 1, runs.size)
        assertEquals(PendingChanges.NEEDS_PERSON, dao.get(id)?.state)
    }

    @Test
    fun `a change that signed and can't be read settles its ids before it goes, with no line`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        dao.update(dao.get(id)!!.copy(inputs = "{broken"))
        afterLookWindow()

        runner.runOnce()

        assertEquals("never run again", 1, runs.size)
        assertNull(dao.get(id))
        assertEquals(listOf(null to signedByRuns.toList()), settled)
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `one that can't be read and whose ids can't be settled now stays parked, with nothing killed`() = runTest {
        val id = keepInvite()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        dao.update(dao.get(id)!!.copy(inputs = "{broken"))
        afterLookWindow()
        settleWorks = false

        runner.runOnce()

        assertEquals(PendingChanges.SETTLING, dao.get(id)?.state)
        assertEquals(1, PendingChanges.signedIds(dao.get(id)!!).size)
        settleWorks = true
        runner.runOnce()
        assertNull(dao.get(id))
        assertEquals(listOf(null to signedByRuns.toList()), settled)
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `a let-in - the look finds its admission stored - only the choice is left, nothing signed again, no line`() = runTest {
        val id = (store.queue<Unit>("c-1", PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s", "g")).exceptionOrNull() as QueuedForSync).changeId
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        held = setOf(signedByRuns.single())
        afterLookWindow()
        answers += { r -> assertTrue("only the choice left", r.admissionHeld); PendingOutcome.Done() }

        runner.runOnce()

        assertEquals(2, runs.size)
        assertNull(dao.get(id))
        assertTrue(dao.notices.value.isEmpty())
        assertEquals(listOf(signedByRuns.single() to emptyList<String>()), settled)
    }

    private suspend fun keepLetIn(): String =
        (store.queue<Unit>("c-1", PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s", "g")).exceptionOrNull() as QueuedForSync).changeId

    @Test
    fun `a let-in stopped after its admission's 2xx but before the change ended - the next pass looks, and only records the choice`() = runTest {
        val id = keepLetIn()
        val admission = "00000000-0000-4000-8000-000000000077"
        // The run signed and sent, the server stored it, and the app stopped mid-run.
        dao.update(
            PendingChanges.withSigned(dao.get(id)!!, SignedStatement(admission, at = clock))
                .copy(state = PendingChanges.RUNNING, attemptId = "a-1", startedAt = clock)
        )
        held = setOf(admission)
        afterLookWindow()
        answers += { r -> assertTrue("only the choice left", r.admissionHeld); PendingOutcome.Done() }

        runner.runOnce()

        assertEquals(1, runs.size)
        assertNull(dao.get(id))
        assertTrue(dao.notices.value.isEmpty())
        assertEquals(listOf(admission to emptyList<String>()), settled)
    }

    /**
     * The real "Let them in" run over a member list whose verdict WOULD say "already let in"
     * (this device's own admission confirms them): a held admission must still record the
     * choice.
     */
    private val letInCircles = mockk<CircleRepository>(relaxed = true) {
        coEvery { memberIdsWhole("c-1") } returns setOf("me", "sam")
        coEvery { memberRolesWhole("c-1") } returns mapOf("me" to "admin", "sam" to "member")
        coEvery { letInNow("c-1", "sam") } returns CircleRepository.LetInNow.LET_IN
        coEvery { grantKeyAccess(any(), any()) } returns Result.success(Unit)
    }

    private fun realLetInRunner() = runner().also { r ->
        val executors = PendingExecutors(
            mockk(), mockk(), mockk(), mockk(), mockk(), { letInCircles }, mockk(), mockk(), sessionManager,
            { mockk<com.pingolin.track.domain.repository.AuthRepository> { every { signingKeyMissing() } returns false } },
        )
        r.executorFor = { executors.forKind(it) }
    }

    private suspend fun stoppedLetIn(state: String): Pair<String, String> {
        val id = keepLetIn()
        val admission = "00000000-0000-4000-8000-000000000088"
        val stopped = PendingChanges.withSigned(dao.get(id)!!, SignedStatement(admission, at = clock))
        dao.update(
            if (state == PendingChanges.RUNNING) stopped.copy(state = PendingChanges.RUNNING, attemptId = "a-1", startedAt = clock)
            else stopped.copy(lastOutcome = PendingChanges.LOST_ANSWER)
        )
        held = setOf(admission)
        afterLookWindow()
        return id to admission
    }

    @Test
    fun `a let-in's stop path, the 2xx came and the app stopped - the choice is recorded, never already let in`() = runTest {
        val (id, admission) = stoppedLetIn(PendingChanges.RUNNING)

        realLetInRunner().runOnce()

        assertNull(dao.get(id))
        assertTrue("no already-let-in line for its own let-in", dao.notices.value.isEmpty())
        coVerify(exactly = 1) { letInCircles.grantKeyAccess(match { it.userId == "sam" }, match { it.admissionHeld }) }
        coVerify(exactly = 0) { letInCircles.letInNow(any(), any()) }
        assertEquals(listOf(admission to emptyList<String>()), settled)
    }

    @Test
    fun `a let-in's stop path, a lost answer the look finds held - the choice is recorded, never already let in`() = runTest {
        val (id, admission) = stoppedLetIn(PendingChanges.WAITING)

        realLetInRunner().runOnce()

        assertNull(dao.get(id))
        assertTrue("no already-let-in line for its own let-in", dao.notices.value.isEmpty())
        coVerify(exactly = 1) { letInCircles.grantKeyAccess(match { it.userId == "sam" }, match { it.admissionHeld }) }
        coVerify(exactly = 0) { letInCircles.letInNow(any(), any()) }
        assertEquals(listOf(admission to emptyList<String>()), settled)
    }

    @Test
    fun `a let-in cancelled after its admission went out - the choice is recorded all the same, and it had already gone out`() = runTest {
        val id = keepLetIn()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        assertEquals(PendingChanges.Cancelled.CANCELLING, store.cancel(id))
        held = setOf(signedByRuns.single())
        afterLookWindow()
        recordWorks = false

        runner.runOnce()

        assertEquals("a choice not on disk: still Cancelling, nothing settled", PendingChanges.CANCELLING, dao.get(id)?.state)
        assertTrue(settled.isEmpty())
        recordWorks = true
        runner.runOnce()
        assertNull(dao.get(id))
        assertEquals(listOf(id, id), recordedChoices)
        assertEquals("Letting Sam in had already gone out, so it couldn't be cancelled.", dao.notices.value.single().line)
        assertEquals(listOf(signedByRuns.single() to emptyList<String>()), settled)
        assertEquals("never run again", 1, runs.size)
    }

    @Test
    fun `a let-in too old with its own admission held - the choice is recorded at that end too, or it waits`() = runTest {
        val id = keepLetIn()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        held = setOf(signedByRuns.single())
        clock = dao.get(id)!!.expiresAt + 1
        recordWorks = false

        runner.runOnce()

        assertEquals("no choice yet: parked, nothing settled", PendingChanges.SETTLING, dao.get(id)?.state)
        assertTrue(settled.isEmpty())
        recordWorks = true
        runner.runOnce()
        assertNull(dao.get(id))
        assertEquals(listOf(id, id), recordedChoices)
        assertTrue("its let-in went out: no line", dao.notices.value.isEmpty())
        assertEquals(listOf(signedByRuns.single() to emptyList<String>()), settled)
        assertEquals("never run again", 1, runs.size)
    }

    @Test
    fun `a let-in cancelled with none of its admissions held - no choice is left behind, and no line`() = runTest {
        val id = keepLetIn()
        // Its admission didn't go out (offline, a 5xx, a refusal): nothing held.
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        assertEquals(PendingChanges.Cancelled.CANCELLING, store.cancel(id))
        afterLookWindow()

        runner.runOnce()

        assertNull(dao.get(id))
        assertTrue("no choice", recordedChoices.isEmpty())
        assertTrue(dao.notices.value.isEmpty())
        assertEquals(listOf(null to signedByRuns.toList()), settled)
    }

    @Test
    fun `a let-in too old with none of its admissions held - no choice is left behind, and too long to catch up`() = runTest {
        val id = keepLetIn()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        clock = dao.get(id)!!.expiresAt + 1

        runner.runOnce()

        assertNull(dao.get(id))
        assertTrue("no choice", recordedChoices.isEmpty())
        assertEquals("Letting Sam in wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
        assertEquals(listOf(null to signedByRuns.toList()), settled)
    }

    @Test
    fun `a let-in answered already, its first admission stored by then - the end records the choice, no line`() = runTest {
        val id = keepLetIn()
        answers += { r -> sign(r); PendingOutcome.Transient(maybeSent = true) }
        runner.runOnce()
        afterLookWindow()
        // The pass's look finds nothing yet; the run signs again and is told it was already
        // done: the first admission landed meanwhile, as the end's own look finds.
        answers += { r -> sign(r); held = setOf(signedByRuns.first()); PendingOutcome.Already(null) }

        runner.runOnce()

        assertNull(dao.get(id))
        assertEquals(listOf(id), recordedChoices)
        assertTrue(dao.notices.value.isEmpty())
        assertEquals(listOf(signedByRuns.first() to listOf(signedByRuns.last())), settled)
    }

    @Test
    fun `an address that can't be checked for 7 days ends as too long to catch up - never sent another way`() = runTest {
        val kept = PendingInputs.Invite("Family", "dan@x.test", addressCheck = true)
        val id = (store.queue<Unit>("c-1", kept).exceptionOrNull() as QueuedForSync).changeId
        assertEquals(
            "Waiting to sync. dan@x.test will be invited as soon as Pingolin can check the address.",
            PendingInputs.waitingLine(PendingInputs.read(dao.get(id)!!)!!),
        )
        // Every run: the directory couldn't look, and the run waits (as the invite path answers).
        repeat(3) {
            answers += { PendingChangeRunner.classify(CouldNotCheck(), PendingInputs.subject(kept), "Family") }
            runner.runOnce()
            clock += 2 * PendingKind.DAY_MS
        }
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)

        clock += 2 * PendingKind.DAY_MS
        runner.runOnce()

        assertNull(dao.get(id))
        assertEquals("dan@x.test's invitation wasn't done: this phone took too long to catch up.", dao.notices.value.single().line)
        assertEquals(3, runs.size)
        assertTrue("nothing signed, ever", signedByRuns.isEmpty())
    }

    @Test
    fun `a statement id that can't be saved sends nothing, and the change waits`() = runTest {
        val id = keepInvite()
        val failing = object : PendingChangeDao by dao {
            override suspend fun update(change: PendingChangeEntity) {
                if (change.signedIds.isNotBlank()) throw IllegalStateException("disk full")
                dao.update(change)
            }
        }
        val sent = mutableListOf<String>()
        answers += { r ->
            try {
                sign(r)
                sent += "request"
                PendingOutcome.Done()
            } catch (e: StatementNotSaved) {
                PendingChangeRunner.classify(e, PendingInputs.subject(PendingInputs.Invite("Family", "dan@x.test")), "Family")
            }
        }

        runner(changes = failing).runOnce()

        assertTrue("nothing sent", sent.isEmpty())
        assertEquals(PendingChanges.WAITING, dao.get(id)?.state)
        assertTrue(dao.get(id)!!.signedIds.isBlank())
    }

    @Test
    fun `waiting for the password - it goes on by itself once the key is back`() = runTest {
        val id = keepInvite()
        answers += { PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD) }
        var missing = true
        runner.signingKeyMissing = { missing }

        runner.runOnce()
        assertEquals(PendingChanges.NEEDS_PERSON, dao.get(id)?.state)
        runner.runOnce()
        assertEquals("not run while the key is missing", 1, runs.size)

        missing = false
        runner.runOnce()
        assertEquals(2, runs.size)
        assertNull(dao.get(id))
    }
}
