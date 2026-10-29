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

import android.util.Log
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.PendingChangeDao
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.data.remote.dto.QuotaException
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What running one waiting change came to. */
sealed interface PendingOutcome {
    /**
     * Done: the change goes. [line]: someone else had already done it, shown once.
     * [heldId]: for a kind that keeps statement ids, the statement the server holds, named by
     * the 2xx for the request that carried it or by the look.
     */
    data class Done(val line: String? = null, val heldId: String? = null) : PendingOutcome

    /** Dropped with its line; null for none (the person left, or answered "No"). */
    data class Dropped(val line: String?) : PendingOutcome

    /**
     * For a kind that keeps statement ids: the server says it is already so (an "already"
     * code, or this change's request id sent again). Which of this change's own statements the
     * server holds decides: one held is done, with no line; none held ends it with [line].
     */
    data class Already(val line: String?) : PendingOutcome

    /** The live gate said this phone is behind after all: waiting again, and this circle's pass ends. */
    data object Behind : PendingOutcome

    /**
     * Not now (offline, a timeout, 5xx, 429, a 403 or 404 at send): waiting, with backoff, and
     * this circle's pass ends so nothing overtakes it. [maybeSent]: a request went out and got
     * no answer.
     */
    data class Transient(val maybeSent: Boolean) : PendingOutcome

    /** Waits for the person: [question] says what they are asked. */
    data class NeedsPerson(val question: String) : PendingOutcome

    /** The session can't be used (the server refused it): nothing more runs now. */
    data object Stop : PendingOutcome
}

/** What the look said: the server's answer to which of a
 * change's statements it holds.
 */
sealed interface LookAnswer {
    /** The server's answer: which of the ids asked about it holds, as hex (lower case). Empty: none. */
    data class Held(val hexIds: Set<String>) : LookAnswer

    /** 404: not this account's circle, or none such. "Gone" only if the whole circle list agrees. */
    data object NotFound : LookAnswer

    /** No answer, 429, 5xx, anything else: nothing is decided, nothing is killed. */
    data object CouldNotLook : LookAnswer
}

/**
 * One kind's run: re-read the world, then call the button's own function with the stored
 * inputs and [PendingRun]. That function keeps its own gate.
 */
interface PendingExecutor {
    suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome
}

/**
 * Runs the changes waiting to sync, one circle at a time and, within a circle, oldest
 * first. A circle this phone is still behind on is left for the next trigger: the runner
 * asks the same gate as the live path, and nothing is sealed or signed for it until then.
 * A transient failure ends that circle's pass, so a later change never overtakes an
 * earlier one; a permanent one is dropped at once with its line.
 *
 * The kinds that keep statement ids ([PendingKind.keepsStatementIds]) keep the id of every
 * statement their runs sign, saved before the request leaves. Before signing again, and
 * when such a change ends, the server is asked which of them it holds; every other one then
 * goes dead on this device, durably, before the record goes. When that can't be decided (no
 * answer, a 404 the whole circle list doesn't back, a failed write), nothing is killed and
 * the change is parked ("settling", or "Cancelling…").
 */
@Singleton
class PendingChangeRunner @Inject constructor(
    private val dao: PendingChangeDao,
    private val store: PendingChanges,
    private val sessionManager: SessionManager,
    private val circleDao: CircleDao,
    private val circleRepository: CircleRepository,
    private val scheduler: PendingChangeScheduler,
    private val executors: PendingExecutors,
) {
    private val mutex = Mutex()

    /** Each kind's run; tests put their own in. */
    internal var executorFor: (PendingKind?) -> PendingExecutor? = { executors.forKind(it) }

    /** The look at which statements the server holds; tests put
     * their own in.
     */
    internal var look: suspend (circleId: String, ids: List<String>) -> LookAnswer =
        { circleId, ids -> circleRepository.statementsHeld(circleId, ids) }

    /**
     * When a change that signs ends: [dead] go dead on this device, durably (false when that
     * write failed: nothing is ended then), and an invitation the server holds becomes this
     * user's invitee record, bound to it ([held]). Tests put their own in.
     */
    internal var settle: (change: PendingChangeEntity, held: SignedStatement?, dead: List<String>) -> Boolean =
        { change, held, dead ->
            val email = (PendingInputs.read(change) as? PendingInputs.Invite)?.email
            circleRepository.settleSignedStatements(change.circleId, email, held, dead)
        }

    /** A make-admin change cancelled with its admission stored: is the member an admin now?
     * Null when that can't be read whole.
     */
    internal var madeAdmin: suspend (circleId: String, userId: String) -> Boolean? =
        { circleId, userId -> circleRepository.memberRolesWhole(circleId)?.let { it[userId] == "admin" } }

    /**
     * A let-in ending with its own admission held, other than by its own run: this device's
     * choice is recorded all the same (read back), while the circle's complete roster lists
     * the member; false: it waits. Tests put their own in.
     */
    internal var recordLetIn: suspend (change: PendingChangeEntity) -> Boolean = { change -> executors.recordLetIn(change) }

    /** The signing key is missing here: a change that signs waits for the password. */
    internal var signingKeyMissing: () -> Boolean = { executors.signingKeyMissing() }

    /** A change asks the person again: the existing question notifier, once. */
    internal var notifyQuestion: (change: PendingChangeEntity, inputs: PendingInputs, ask: PendingQuestions.Ask) -> Unit =
        { change, inputs, ask -> circleRepository.notifyPendingQuestion(change.circleId, inputs, ask) }

    /** How a change ends: what the run (or the person) came to, before its ids settle. */
    private enum class How { DONE, DROPPED, ALREADY, CANCELLED }

    /** What [runOne] tells the pass to do next. */
    private enum class Step { NEXT, END_CIRCLE, STOP }

    /**
     * Run every change that can run now. Returns how many ended (done or dropped), for tests and
     * logs. Nothing runs for anyone but the signed-in account.
     */
    suspend fun runOnce(): Int = mutex.withLock {
        val account = store.account() ?: return 0
        if (!sessionManager.isLoggedIn()) return 0
        val now = store.now()
        dao.pruneNotices(seenBefore = now - NOTICE_SEEN_KEEP_MS, createdBefore = now - NOTICE_MAX_AGE_MS)
        if (dao.forAccount(account).isEmpty()) return 0
        // A change found going out as a pass starts was left so by a run that never ended
        // (the app was stopped mid-request): it may have reached the server. Marked so,
        // durably, before anything else: a merge then keeps its age limit, and Cancel says
        // it may already have gone out.
        store.lock.withLock {
            for (change in dao.forAccount(account)) if (change.state == PendingChanges.RUNNING) {
                dao.update(change.copy(state = PendingChanges.WAITING, attemptId = null, startedAt = null, lastOutcome = PendingChanges.LOST_ANSWER))
            }
        }
        val mine = dao.forAccount(account)

        // The circle list, fresh: the gate judges against the version it reports, and
        // only a list the server gave WHOLE says a circle is gone for this account.
        // Without one, nothing is judged gone.
        val listed = circleRepository.refreshCirclesWhole()
        var ended = 0
        var stop = false
        for ((circleId, changes) in mine.groupBy { it.circleId }) {
            if (stop) break
            val gone = listed != null && circleId !in listed
            if (gone) {
                // Gone, or this account is no longer in it: the circle's other
                // local state goes with it, and so do these. A change still
                // waiting says so once. Statement ids aren't settled against a
                // circle that isn't this account's any more.
                for (change in changes) {
                    val inputs = PendingInputs.read(change)
                    val line = if (change.state in SHOWN && inputs != null) {
                        PendingLines.circleGone(PendingInputs.subject(inputs), inputs.circleName)
                    } else null
                    remove(change, line)
                    ended++
                }
                continue
            }
            val live = mutableListOf<PendingChangeEntity>()
            for (change in changes.sortedWith(compareBy({ it.createdAt }, { it.id }))) {
                // Too old ends it even while this phone is behind: a server
                // that keeps it behind gets these lines, never a late
                // surprise. One that signed settles its statement ids first.
                if (change.state in SHOWN && now > change.expiresAt) {
                    val subject = PendingInputs.read(change)?.let { PendingInputs.subject(it) }
                    if (end(change, How.DROPPED, subject?.let { PendingLines.tooOld(it) }, now)) ended++
                    continue
                }
                live += change
            }
            // Parked changes settle whenever their look can run, behind or
            // not: a look signs nothing.
            for (change in live) {
                when (change.state) {
                    PendingChanges.CANCELLING -> if (end(change, How.CANCELLED, null, now)) ended++
                    PendingChanges.SETTLING -> {
                        val parked = parkedAs(change)
                        if (end(change, parked.first, parked.second, now)) ended++
                    }
                }
            }
            // Not on this phone, and no whole list to say whether it's gone: wait (the gate has
            // nothing to judge a circle it hasn't stored by).
            if (circleDao.getById(circleId) == null) continue
            if (!circleRepository.holdsCurrentKey(circleId)) continue
            for (snapshot in live) {
                val change = dao.get(snapshot.id) ?: continue
                when (change.state) {
                    PendingChanges.CANCELLING, PendingChanges.SETTLING -> continue
                    // Waiting for the password: it goes on by itself once the key is back.
                    PendingChanges.NEEDS_PERSON ->
                        if (change.question != PendingQuestions.PASSWORD || signingKeyMissing()) continue
                }
                // Its next try isn't due: nothing after it goes first.
                if (change.state == PendingChanges.WAITING && change.nextAttemptAt > now) break
                val step = runOne(change, now)
                if (step == Step.NEXT) {
                    if (dao.get(change.id) == null) ended++
                    continue
                }
                if (step == Step.STOP) stop = true
                break
            }
        }
        // One next try for everything waiting on its backoff or its look: the earliest one due.
        dao.forAccount(account)
            .filter { it.state == PendingChanges.WAITING && it.nextAttemptAt > now }
            .minOfOrNull { it.nextAttemptAt }
            ?.let { scheduler.at(it) }
        dao.forAccount(account)
            .filter { it.state == PendingChanges.SETTLING || it.state == PendingChanges.CANCELLING }
            .mapNotNull { PendingChanges.signed(it)?.let { s -> lookAllowedAt(s) } }
            .filter { it > now }
            .minOrNull()
            ?.let { scheduler.at(it) }
        if (ended > 0) Log.i(TAG, "$ended waiting change(s) ended")
        ended
    }

    /**
     * One change: the look first for a change that already signed, then its kind's re-check
     * and the button's own function.
     */
    private suspend fun runOne(snapshot: PendingChangeEntity, now: Long): Step {
        val kind = PendingKind.of(snapshot.kind)
        val signing = kind?.keepsStatementIds == true
        val signed = PendingChanges.signed(snapshot)
            // A list of statement ids this build can't read: never run, never settled against.
            ?: return Step.NEXT.also { Log.w(TAG, "A waiting change's statement ids couldn't be read; left as it is") }
        var admissionHeld: SignedStatement? = null
        var noneHeld = false
        if (signing && signed.isNotEmpty()) {
            // A request carrying one of these may have been stored: look
            // before signing another, once any request still in flight has
            // ended.
            val lookAt = lookAllowedAt(signed)
            if (now < lookAt) {
                release(snapshot, nextAttemptAt = lookAt)
                return Step.END_CIRCLE
            }
            when (val answer = look(snapshot.circleId, signed.map { it.id })) {
                is LookAnswer.Held -> {
                    val held = signed.firstOrNull { PendingChanges.hexId(it.id) in answer.hexIds }
                    when {
                        held != null && kind != PendingKind.MAKE_ADMIN && kind != PendingKind.LET_IN -> {
                            end(snapshot, How.DONE, null, now, heldId = held.id, answered = true)
                            return Step.NEXT
                        }
                        // The admission is stored: a make-admin change has
                        // only the role left, a let-in only this device's
                        // choice, which a run stopped part-way may not have
                        // written. Nothing is signed again.
                        held != null -> admissionHeld = held
                        else -> {
                            noneHeld = true
                            if (signed.size >= MAX_SIGNED) {
                                val subject = PendingInputs.read(snapshot)?.let { PendingInputs.subject(it) }
                                end(snapshot, How.DROPPED, subject?.let { PendingLines.notDone(it) }, now, noneHeld = true)
                                return Step.NEXT
                            }
                        }
                    }
                }
                // Couldn't look (a 404 alone included): nothing decided, it waits. A circle that
                // is gone for this account is known from the whole list at the next pass.
                LookAnswer.NotFound, LookAnswer.CouldNotLook -> {
                    release(snapshot, nextAttemptAt = now + backoffMs(maxOf(1, snapshot.attempts)))
                    return Step.END_CIRCLE
                }
            }
        }

        // Marked going out as it is NOW, under the queue's lock: from here Cancel says
        // "Sending…" and a later tap makes a new change instead of merging into this one.
        // Durable before anything is sent: a change found going out after a restart is
        // checked again.
        val running = store.lock.withLock {
            val change = dao.get(snapshot.id)
                ?.takeIf {
                    it.state == PendingChanges.WAITING || it.state == PendingChanges.RUNNING ||
                        (it.state == PendingChanges.NEEDS_PERSON && it.question == PendingQuestions.PASSWORD)
                }
                // The account is checked again before every run.
                ?.takeIf { it.account == store.account() }
                ?: return Step.NEXT
            change.copy(state = PendingChanges.RUNNING, attemptId = UUID.randomUUID().toString(), startedAt = now, question = null)
                .also { dao.update(it) }
        }
        val inputs = PendingInputs.read(running)
        if (inputs == null) {
            Log.w(TAG, "A waiting change (${running.kind}) couldn't be read")
            // It goes with no line. One that signed settles its ids first, by this
            // pass's look when it had one, or stays parked with nothing killed.
            end(running, How.DROPPED, null, now, noneHeld = noneHeld)
            return Step.NEXT
        }
        val subject = PendingInputs.subject(inputs)
        val executor = executorFor(kind)
        if (executor == null) {
            end(running, How.DROPPED, PendingLines.notDone(subject), now, noneHeld = noneHeld, answered = true)
            return Step.NEXT
        }
        // A statement this run signed, saved before its request leaves.
        var savedThisRun: SignedStatement? = null
        val run = PendingRun(
            changeId = running.id,
            requestId = running.id,
            onSigned = if (!signing) null else { statement ->
                // Fails closed: whatever keeps the id from being saved, nothing is sent.
                store.lock.withLock {
                    val current = dao.get(running.id)
                        ?.takeIf { it.state == PendingChanges.RUNNING && it.attemptId == running.attemptId }
                        ?: throw StatementNotSaved()
                    val saved = PendingChanges.signed(current) ?: throw StatementNotSaved()
                    // The bound: at most 20 statement ids per change.
                    if (saved.size >= MAX_SIGNED) throw StatementNotSaved()
                    val stamped = statement.copy(at = store.now())
                    try {
                        dao.update(PendingChanges.withSigned(current, stamped))
                    } catch (e: Exception) {
                        throw StatementNotSaved()
                    }
                    savedThisRun = stamped
                }
            },
            admissionHeld = admissionHeld != null,
        )
        val outcome = try {
            // The repositories fold a cancellation into their Result, where it reads as a
            // refusal: a run stopped meanwhile is told apart here, never taken as the answer.
            executor.run(running, inputs, run).also { currentCoroutineContext().ensureActive() }
        } catch (e: CancellationException) {
            // A time limit inside the run, while the job goes on: like no answer.
            if (!currentCoroutineContext().isActive) {
                // The job was stopped (it lost its network, the person signed out): the request
                // may have gone out. Back to waiting as a lost answer, so Cancel works again and
                // the next run starts with the look and the re-check. Never dropped for it.
                withContext(NonCancellable) {
                    store.lock.withLock {
                        dao.get(running.id)?.takeIf { it.attemptId == running.attemptId }?.let {
                            dao.update(it.copy(state = PendingChanges.WAITING, attemptId = null, startedAt = null, lastOutcome = PendingChanges.LOST_ANSWER))
                        }
                    }
                }
                throw e
            }
            classify(e, subject, inputs.circleName)
        } catch (e: Exception) {
            classify(e, subject, inputs.circleName)
        }
        // As it is now: this run may have saved statement ids to it.
        val after = dao.get(running.id) ?: return Step.NEXT
        val stillNoneHeld = noneHeld && savedThisRun == null
        return when (outcome) {
            is PendingOutcome.Done -> {
                end(
                    after, How.DONE, outcome.line, now, heldId = outcome.heldId ?: savedThisRun?.id ?: admissionHeld?.id,
                    noneHeld = stillNoneHeld, answered = true,
                )
                Step.NEXT
            }
            is PendingOutcome.Already -> {
                end(after, How.ALREADY, outcome.line, now, noneHeld = stillNoneHeld, answered = true)
                Step.NEXT
            }
            is PendingOutcome.Dropped -> {
                end(after, How.DROPPED, outcome.line, now, noneHeld = stillNoneHeld, answered = true)
                Step.NEXT
            }
            is PendingOutcome.NeedsPerson -> {
                dao.update(after.copy(state = PendingChanges.NEEDS_PERSON, question = outcome.question, attemptId = null, startedAt = null))
                PendingQuestions.readAsk(outcome.question)?.let { ask -> runCatching { notifyQuestion(after, inputs, ask) } }
                Step.NEXT
            }
            PendingOutcome.Behind -> {
                dao.update(after.copy(state = PendingChanges.WAITING, attemptId = null, startedAt = null))
                Step.END_CIRCLE
            }
            is PendingOutcome.Transient -> {
                val attempts = after.attempts + 1
                // A statement saved this run: its request may have left, whatever the error says.
                val maybeSent = outcome.maybeSent || savedThisRun != null || after.lastOutcome == PendingChanges.LOST_ANSWER
                dao.update(
                    after.copy(
                        state = PendingChanges.WAITING, attemptId = null, startedAt = null,
                        attempts = attempts, nextAttemptAt = now + backoffMs(attempts),
                        lastOutcome = if (maybeSent) PendingChanges.LOST_ANSWER else NOT_SENT,
                    )
                )
                Step.END_CIRCLE
            }
            PendingOutcome.Stop -> {
                dao.update(after.copy(state = PendingChanges.WAITING, attemptId = null, startedAt = null))
                Step.STOP
            }
        }
    }

    /**
     * The change ends: done, dropped, "already" or cancelled. For a kind that keeps statement
     * ids, they are settled first: the one the server holds (named by the 2xx that carried it,
     * or by the look) stays alive, and every other goes dead on this device, durably, before
     * the record goes. When that can't be decided now, the record is parked with nothing
     * killed. Returns whether the record is gone.
     */
    private suspend fun end(
        change: PendingChangeEntity,
        how: How,
        line: String?,
        now: Long,
        heldId: String? = null,
        noneHeld: Boolean = false,
        answered: Boolean = false,
    ): Boolean {
        val kind = PendingKind.of(change.kind)
        if (kind?.keepsStatementIds != true || change.signedIds.isBlank()) {
            remove(change, if (how == How.CANCELLED) null else line)
            return true
        }
        val signed = PendingChanges.signed(change) ?: return park(change, how, line).let { false }
        // The server's own answer to the request that carried it: that id is held. (A let-in run
        // that ends here has recorded its choice already: its admission, then the choice, read
        // back.)
        if (how == How.DONE && heldId != null) {
            val held = signed.firstOrNull { it.id.equals(heldId, ignoreCase = true) }
            if (held != null) {
                if (!settle(change, held, signed.filter { it !== held }.map { it.id })) return park(change, how, line).let { false }
                remove(change, line)
                return true
            }
        }
        val answer: LookAnswer = when {
            noneHeld -> LookAnswer.Held(emptySet())
            // A request of it may still be in flight: nothing is decided yet.
            !answered && now < lookAllowedAt(signed) -> return park(change, how, line).let { false }
            else -> look(change.circleId, signed.map { it.id })
        }
        if (answer !is LookAnswer.Held) {
            if (answer == LookAnswer.NotFound && circleRepository.refreshCirclesWhole()?.let { change.circleId !in it } == true) {
                // Not this account's circle any more, as the whole circle list says too: nothing
                // is settled against it.
                remove(change, if (how == How.DROPPED || how == How.ALREADY) line else null)
                return true
            }
            // Couldn't look (a 404 alone included): nothing is killed, it waits.
            return park(change, how, line).let { false }
        }
        val held = signed.firstOrNull { PendingChanges.hexId(it.id) in answer.hexIds }
        // A let-in: its own admission is held, so the let-in went out, and it never ends
        // without this device's choice: a cancel, too old, a refusal. Recorded here unless
        // the member is no longer listed; else it stays parked ("Cancelling…" for a
        // cancel). With none of its own held, it leaves no choice behind.
        if (held != null && kind == PendingKind.LET_IN && !recordLetIn(change)) return park(change, how, line).let { false }
        val dead = signed.filter { it !== held }.map { it.id }
        val subject = PendingInputs.read(change)?.let { PendingInputs.subject(it) }
        val shown: String? = when {
            held != null && how == How.CANCELLED ->
                if (kind == PendingKind.MAKE_ADMIN) {
                    // A stored admission isn't a promotion; only a changed role is.
                    val userId = (PendingInputs.read(change) as? PendingInputs.MakeAdmin)?.memberId
                    when (userId?.let { madeAdmin(change.circleId, it) }) {
                        true -> subject?.let { PendingLines.alreadyWentOut(it) }
                        false -> null
                        null -> return park(change, how, line).let { false }
                    }
                } else {
                    // A let-in's choice is recorded above. The "already went
                    // out" line either way.
                    subject?.let { PendingLines.alreadyWentOut(it) }
                }
            // Make-admin: a stored admission isn't a promotion; how the run ended
            // stands.
            held != null && kind == PendingKind.MAKE_ADMIN -> line
            // One of its own is held: it is done, whatever this run was told.
            held != null -> if (how == How.DONE) line else null
            // None of its own is held: a cancel says nothing; otherwise the run's own line
            // (someone else had done it, or why it wasn't done).
            else -> if (how == How.CANCELLED) null else line
        }
        if (!settle(change, held, dead)) return park(change, how, line).let { false }
        remove(change, shown)
        return true
    }

    /**
     * Parked, nothing killed: "Cancelling…", or settling (no longer shown as waiting). How it
     * ends is kept with it, so it never runs again and settles by the next look that can run.
     */
    private suspend fun park(change: PendingChangeEntity, how: How, line: String?) {
        val state = if (how == How.CANCELLED) PendingChanges.CANCELLING else PendingChanges.SETTLING
        val ending = if (how == How.CANCELLED) null else PendingQuestions.ending(how.name, line)
        store.lock.withLock {
            val current = dao.get(change.id) ?: return@withLock
            dao.update(current.copy(state = state, question = ending, attemptId = null, startedAt = null))
        }
        Log.i(TAG, "Waiting change ${change.kind} for ${change.circleId} parked (${how.name.lowercase()}) until it can be settled")
    }

    /** How a settling change ends, as it was parked. */
    private fun parkedAs(change: PendingChangeEntity): Pair<How, String?> {
        val (how, line) = PendingQuestions.readEnding(change.question) ?: return How.DROPPED to null
        return (How.entries.firstOrNull { it.name == how } ?: How.DROPPED) to line
    }

    /** Back to waiting, as it was, until [nextAttemptAt] (its look, or its backoff). */
    private suspend fun release(change: PendingChangeEntity, nextAttemptAt: Long) {
        store.lock.withLock {
            val current = dao.get(change.id) ?: return@withLock
            if (current.state != change.state) return@withLock
            dao.update(current.copy(state = PendingChanges.WAITING, attemptId = null, startedAt = null, nextAttemptAt = nextAttemptAt))
        }
    }

    /** Deleted, leaving [line] as a notice when there is one. */
    private suspend fun remove(change: PendingChangeEntity, line: String?) {
        store.lock.withLock { dao.end(change.id, line?.let { store.notice(change, it) }) }
        Log.i(TAG, "Waiting change ${change.kind} for ${change.circleId} ended")
    }

    companion object {
        private const val TAG = "PendingChangeRunner"

        /** [PendingChangeEntity.lastOutcome] after a try that never reached the server. */
        const val NOT_SENT = "not_sent"

        /** A line that has been seen clears a minute later, the next time the lists are read. */
        const val NOTICE_SEEN_KEEP_MS = 60 * 1000L

        /** States a person sees as a waiting change. */
        private val SHOWN = setOf(PendingChanges.WAITING, PendingChanges.RUNNING, PendingChanges.NEEDS_PERSON)

        /** Shown until seen, at most 7 days. */
        const val NOTICE_MAX_AGE_MS = 7 * PendingKind.DAY_MS

        /** At most 20 signed statement ids per change, one look. */
        const val MAX_SIGNED = 20

        /**
         * No look sooner than this after the change's newest statement went out: a request that
         * got no answer may still be in flight on the server (client and server time out at 30 s
         * each).
         */
        const val LOOK_AFTER_SEND_MS = 2 * 60_000L

        /** When the look may run for a change that signed
         * [signed].
         */
        fun lookAllowedAt(signed: List<SignedStatement>): Long = (signed.maxOfOrNull { it.at } ?: 0L) + LOOK_AFTER_SEND_MS

        /** 30 s, then 1, 2, 4… minutes, at most hourly; bounded by age, never by a count. */
        fun backoffMs(attempts: Int): Long = when {
            attempts <= 1 -> 30_000L
            else -> minOf(60 * 60_000L, 60_000L shl minOf(attempts - 2, 6))
        }

        /**
         * What a failure of the button's own function means for the change, by the
         * refusal's status and code, never its text: 401 stops the run; 403, 404, 429,
         * 5xx and no answer wait; "already done" is done; the rest is permanent, with
         * its line.
         */
        fun classify(e: Throwable, subject: PendingLines.Subject, circleName: String): PendingOutcome = when (e) {
            is KeyNotCurrentException, is QueuedForSync -> PendingOutcome.Behind
            // Nothing was sent (an unsaved statement id fails closed; a store that can't be read
            // decides nothing): it waits.
            is StatementNotSaved, is CouldNotCheck, is com.pingolin.track.util.StoreUnreadable -> PendingOutcome.Transient(maybeSent = false)
            is RefusedException -> refused(e, subject, circleName)
            is java.net.ConnectException, is java.net.UnknownHostException, is java.net.NoRouteToHostException,
            // Refused before it left: the session store couldn't be opened.
            is com.pingolin.track.util.StoreRefusedIOException ->
                PendingOutcome.Transient(maybeSent = false)
            is java.io.IOException -> PendingOutcome.Transient(maybeSent = true)
            is uniffi.pingolin_mobile.MobileException.NetworkException -> PendingOutcome.Transient(maybeSent = true)
            // A time limit inside the run, not a refusal (a stopped job never gets here, see runOne).
            is CancellationException -> PendingOutcome.Transient(maybeSent = true)
            // A store or database that couldn't be opened, read or written decides nothing: it
            // waits, never dropped. It may have failed after the request left (a share's own
            // record, written after its 2xx), so, as an IOException, it may have gone.
            else -> if (com.pingolin.track.util.DatabaseUnavailable.isUnavailable(e)) PendingOutcome.Transient(maybeSent = true)
            else PendingOutcome.Dropped(PendingLines.notDone(subject))
        }

        fun refused(e: RefusedException, subject: PendingLines.Subject, circleName: String): PendingOutcome = when {
            e.status == 401 -> PendingOutcome.Stop
            e.status == 429 -> PendingOutcome.Transient(maybeSent = false)
            e.status >= 500 -> PendingOutcome.Transient(maybeSent = true)
            // Not "gone", "deleted" or "not allowed": the next re-check decides that from
            // whole lists. Refused, so nothing was stored.
            e.status == 404 || e.status == 403 -> PendingOutcome.Transient(maybeSent = false)
            e.errorCode == REQUEST_ALREADY_DONE -> PendingOutcome.Done()
            e.status == 402 -> {
                val quota = (e as? QuotaException)?.quota
                if (quota != null && quota.isCountLimit && quota.limit > 0) {
                    PendingOutcome.Dropped(PendingLines.limit(subject, circleName, quota.limit, things(quota.resource)))
                } else {
                    PendingOutcome.Dropped(PendingLines.notDone(subject))
                }
            }
            else -> PendingOutcome.Dropped(PendingLines.notDone(subject))
        }

        /** The name of what a plan limit is on, for
         * [PendingLines.limit], from the 402's
         * `quota.resource`.
         */
        fun things(resource: String): String = when (resource) {
            "place_alerts" -> "place alerts"
            "alert_rules" -> "alerts"
            "inactivity_alerts" -> "alerts"
            "circles" -> "circles"
            else -> resource.replace('_', ' ')
        }

        /** A create sent again with the same `client_request_id`. */
        const val REQUEST_ALREADY_DONE = "request_already_done"
    }
}
