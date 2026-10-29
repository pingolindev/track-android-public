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
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.PendingChangeDao
import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.google.gson.annotations.SerializedName
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The action became a change waiting to sync: it is shown in place, with Cancel, and goes
 * out by itself once this phone catches up. [line] is its waiting line, this app's own. The
 * screen carries on as if the action had succeeded.
 */
class QueuedForSync(
    val changeId: String,
    val line: String,
    /** False when the tap went into a change already waiting (a merge, or the same tap twice). */
    val created: Boolean = true,
) : Exception(line)

/**
 * A run signed a statement whose id couldn't be saved: nothing was sent, and the change
 * waits (fail closed).
 */
class StatementNotSaved : Exception("Couldn't keep this change on this phone just now.")

/** A re-check couldn't read what it needed (the directory, a list): nothing was sent, it waits. */
class CouldNotCheck : Exception("Couldn't check just now.")

/**
 * The member a change that signs would sign for was removed in the circle's refresh
 * records after its person decided. It ends with [PendingLines.memberLeft]'s line and
 * signs nothing.
 */
class RemovedSinceDecided : Exception("Removed since the person decided.")

/**
 * A statement a run of a change that keeps statement ids signed: its id, when its request
 * went out, and for an invitation whom it names and until when, so that the invitee record
 * binds the one the server holds. Never the statement itself.
 */
data class SignedStatement(
    /** As the SDK parses it (a UUID); the look sends it as 16 bytes of hex. */
    @SerializedName("id") val id: String,
    /** When it was saved, just before its request left: no look sooner than 2 minutes after. */
    @SerializedName("at") val at: Long = 0,
    /** Invitation: the account an invitation names; null for one to
     * an email.
     */
    @SerializedName("subject") val subject: String? = null,
    /** Invitation: when the invitation lapses, unix seconds. */
    @SerializedName("expires_at") val expiresAtSecs: Long? = null,
)

/**
 * A waiting change being run: the button's own function is called with this, so it never
 * queues again and its request carries the change's id.
 */
data class PendingRun(
    val changeId: String,
    /** The create's `client_request_id`. */
    val requestId: String,
    /**
     * Kinds that keep statement ids: each statement this run signs, handed over BEFORE its
     * request is sent. It returns once the statement's id is saved; it throws when it can't
     * be, and then nothing may be sent (fail closed).
     */
    val onSigned: (suspend (SignedStatement) -> Unit)? = null,
    /** Make-admin: the look found this change's admission already stored; only the role is left. */
    val admissionHeld: Boolean = false,
    /** Invitation: the version this device had accepted when the person decided. */
    val tapVersion: Int? = null,
    /**
     * Make-admin, let-in and renewal: called once the statement is signed, its version fixed,
     * and before its request leaves: the removal check is asked again, so an eject accepted
     * while the run went on is never signed over. It throws [RemovedSinceDecided] (it ends
     * with [PendingLines.memberLeft]'s line) or [CouldNotCheck] (it waits), and then nothing
     * is sent. An invitation asks inside the invite, which alone knows whom the address names.
     */
    val beforeSend: (suspend () -> Unit)? = null,
)

/**
 * The changes waiting to sync on this phone, per account: queuing with its merges and
 * caps, Cancel, and what the screens show. Running them is PendingChangeRunner's.
 */
@Singleton
class PendingChanges @Inject constructor(
    private val dao: PendingChangeDao,
    private val sessionManager: SessionManager,
    private val scheduler: PendingChangeScheduler,
    private val circleDao: CircleDao,
    private val circleMemberDao: CircleMemberDao,
) {
    /** The phone's clock; tests move it. */
    internal var now: () -> Long = { System.currentTimeMillis() }

    /** Queuing and ending are one at a time, so a merge never races a second tap or a run. */
    internal val lock = Mutex()

    /** The account whose changes and lines under another spelling of its id were moved to its canonical id. */
    @Volatile
    private var adopted: String? = null

    /**
     * This account (canonical, AccountIds), its changes and lines kept under another spelling of
     * its id moved there first, once per process.
     */
    internal suspend fun account(): String? {
        val account = sessionManager.getUserId() ?: return null
        if (adopted != account) {
            val moved = runCatching {
                for (spelling in dao.accounts()) {
                    if (spelling != account && com.pingolin.track.util.AccountIds.same(spelling, account)) dao.reassign(spelling, account)
                }
            }
            // Tables that can't be read or written now: moved at a later call, and nothing is lost.
            if (moved.isSuccess) adopted = account
        }
        return account
    }

    /**
     * Keep [inputs] as a change waiting for [circleId] to sync, and return the failure the
     * action reports: [QueuedForSync] with its waiting line, or — at the caps —
     * [PendingLines.tooMany]'s line and nothing kept. [expiresAt] ends it sooner than the
     * kind's age limit (a renewal's invitation running out, a destination's arrival time).
     * [seen] is what the run compares against.
     */
    suspend fun <T> queue(
        circleId: String,
        inputs: PendingInputs,
        seen: String? = null,
        expiresAt: Long? = null,
    ): Result<T> {
        val account = account()
            ?: return Result.failure(IllegalStateException("Not signed in"))
        val kind = PendingInputs.kindOf(inputs)
        val json = PendingInputs.toJson(inputs)
        val target = PendingInputs.target(inputs)
        val at = now()
        // The kind's age limit from this tap, or the sooner end the tap gives.
        val limit = minOf(expiresAt ?: Long.MAX_VALUE, at + kind.ageLimitMs)
        var created = false
        val kept = lock.withLock {
            val existing = dao.forAccount(account).filter { it.circleId == circleId && it.kind == kind.name }
            // One change per target. A change already going out is left alone: the new
            // one follows it, and its re-check finds anything already done.
            val same = if (kind.merges && target != null) {
                existing.firstOrNull { it.target == target && it.state in MERGEABLE }
            } else {
                val tap = PendingInputs.toJson(PendingInputs.sameTap(inputs))
                existing.firstOrNull { old ->
                    at - old.createdAt in 0..SAME_TAP_MS && old.state in MERGEABLE &&
                        PendingInputs.read(old)?.let { PendingInputs.toJson(PendingInputs.sameTap(it)) } == tap
                }
            }
            if (same != null && !kind.merges) {
                // The same tap twice: one change.
                same
            } else if (same != null) {
                val merged = if (kind.mergesFields) PendingInputs.mergeFields(same.inputs, json) else json
                // The id, the time it was made (its place in the queue) and any statement ids
                // stay. The new tap is the person asking again, so the age limit restarts
                // from it, but only while the change has never gone out: once a run may
                // have reached the server, it keeps its own limit, so the server's 8-day
                // request-id record still covers every resend.
                val restarted = if (mayHaveGoneOut(same)) same.expiresAt else maxOf(same.expiresAt, limit)
                same.copy(inputs = merged, seen = seen ?: same.seen, expiresAt = restarted).also { dao.update(it) }
            } else {
                if (dao.countInCircle(account, circleId) >= MAX_PER_CIRCLE || dao.countForAccount(account) >= MAX_PER_ACCOUNT) {
                    return Result.failure(Exception(PendingLines.tooMany(inputs.circleName)))
                }
                created = true
                PendingChangeEntity(
                    id = UUID.randomUUID().toString(),
                    account = account,
                    circleId = circleId,
                    kind = kind.name,
                    inputs = json,
                    seen = seen,
                    target = target,
                    createdAt = at,
                    expiresAt = limit,
                    state = WAITING,
                ).also { dao.insert(it) }
            }
        }
        Log.i(TAG, "Kept ${kind.name} for $circleId until this phone catches up (${kept.id})")
        scheduler.kick()
        return Result.failure(QueuedForSync(kept.id, PendingInputs.waitingLine(inputs), created))
    }

    /** The circle's name as this phone shows it, for the lines. */
    suspend fun circleName(circleId: String): String =
        runCatching { circleDao.getById(circleId)?.name }.getOrNull().orEmpty()

    /** A member's name as this phone shows it, for the lines. */
    suspend fun memberName(circleId: String, userId: String): String? =
        runCatching { circleMemberDao.get(circleId, userId)?.displayName }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Something may let a change run now (a trigger). */
    fun kick() = scheduler.kick()

    /** Whom or what this account's [kind] changes in [circleId] are about (their targets). */
    suspend fun waitingTargets(circleId: String, kind: PendingKind): Set<String> {
        val account = account() ?: return emptySet()
        return dao.forAccount(account).filter { it.circleId == circleId && it.kind == kind.name }.mapNotNull { it.target }.toSet()
    }

    /** As [waitingTargets], in every circle. */
    suspend fun waitingTargets(kind: PendingKind): Set<String> {
        val account = account() ?: return emptySet()
        return dao.forAccount(account).filter { it.kind == kind.name }.mapNotNull { it.target }.toSet()
    }

    /** This account's changes, oldest first, for the screens. */
    fun observe(): Flow<List<PendingChangeEntity>> = flow {
        val account = account() ?: return@flow emit(emptyList())
        emitAll(dao.observeForAccount(account))
    }.orWhenUnopenable(emptyList()) // covers the account's read too

    /** This account's dropped lines not yet cleared. */
    fun observeNotices(): Flow<List<DroppedNoticeEntity>> = flow {
        val account = account() ?: return@flow emit(emptyList())
        emitAll(dao.observeNotices(account))
    }.orWhenUnopenable(emptyList())

    /** How many changes are waiting (for the sign-out line); settling records don't count. */
    suspend fun waitingCount(): Int {
        val account = account() ?: return 0
        return dao.countForAccount(account)
    }

    /** What Cancel did. */
    enum class Cancelled { REMOVED, CANCELLING, SENDING, GONE }

    /**
     * Cancel, with no confirmation. A change going out can't be cancelled. A change that keeps
     * statement ids and may have gone out waits for the runner to look first ("Cancelling…").
     * Anything else goes at once; after a lost answer it leaves
     * [PendingLines.cancelledMaybeSent]'s line, since it may already have gone out, and the app
     * never undoes it by itself.
     */
    suspend fun cancel(changeId: String): Cancelled {
        val result = lock.withLock {
            val change = dao.get(changeId) ?: return Cancelled.GONE
            if (change.account != account()) return Cancelled.GONE
            val kind = PendingKind.of(change.kind)
            when {
                change.state == RUNNING -> Cancelled.SENDING
                change.state == CANCELLING || change.state == SETTLING -> Cancelled.CANCELLING
                kind?.keepsStatementIds == true && change.signedIds.isNotBlank() -> {
                    dao.update(change.copy(state = CANCELLING))
                    Cancelled.CANCELLING
                }
                else -> {
                    val notice = if (change.lastOutcome == LOST_ANSWER) {
                        PendingInputs.read(change)?.let { notice(change, PendingLines.cancelledMaybeSent(PendingInputs.subject(it))) }
                    } else null
                    dao.end(change.id, notice)
                    Cancelled.REMOVED
                }
            }
        }
        if (result == Cancelled.CANCELLING) scheduler.kick()
        return result
    }

    /**
     * A change a save kept and then gave up with the rest of that save, which failed (a save
     * that fails after keeping some creates drops them). It goes with no line, but only as it
     * was kept: not once a run has tried it. Returns whether it went.
     */
    suspend fun discard(changeId: String): Boolean = lock.withLock {
        val change = dao.get(changeId) ?: return@withLock true
        if (change.account != account() || change.state != WAITING || change.attempts > 0 ||
            change.lastOutcome != null
        ) return@withLock false
        dao.delete(changeId)
        true
    }

    /**
     * The person answered what a waiting change asked. "It's them" continues it with exactly
     * the details shown, asked once (the answer is taken only when it runs, through the live
     * answer path). Anything else ends it with no line: the person decided. A change that
     * signed settles its statement ids first: the runner does that. Returns the change as it
     * was asked, or null when it no longer waits for them.
     */
    suspend fun answer(changeId: String, accept: Boolean): PendingChangeEntity? {
        val asked = lock.withLock {
            val change = dao.get(changeId)
                ?.takeIf { it.state == NEEDS_PERSON && it.account == account() }
                ?: return null
            val ask = PendingQuestions.readAsk(change.question) ?: return null
            if (accept) {
                val inputs = PendingInputs.read(change) ?: return null
                val answered: PendingInputs = when (inputs) {
                    is PendingInputs.Invite -> inputs.copy(
                        confirmedUserId = ask.userId, confirmedSharingKey = ask.sharingKey, confirmedSigningKey = ask.signingKey,
                    )
                    is PendingInputs.MakeAdmin -> inputs.copy(sharingKey = ask.sharingKey, signingKey = ask.signingKey, asked = true)
                    is PendingInputs.LetIn -> inputs.copy(sharingKey = ask.sharingKey, signingKey = ask.signingKey, asked = true)
                    is PendingInputs.RenewInvitation -> inputs.copy(sharingKey = ask.sharingKey, signingKey = ask.signingKey, asked = true)
                    else -> return null
                }
                dao.update(
                    change.copy(
                        inputs = PendingInputs.toJson(answered), state = WAITING, question = null, nextAttemptAt = 0,
                    )
                )
            } else if (change.signedIds.isBlank()) {
                dao.end(change.id, null)
            } else {
                // Its statement ids are settled before it goes: no line whatever the look says.
                dao.update(change.copy(state = SETTLING, question = PendingQuestions.ending("DROPPED", null)))
            }
            change
        }
        scheduler.kick()
        return asked
    }

    /** The screens showed these lines: they clear next time. */
    suspend fun noticesSeen(ids: List<String>) {
        if (ids.isEmpty()) return
        dao.markNoticesSeen(ids, now())
    }

    /**
     * This account left [circleId], or it's gone: its changes and lines go with the circle's
     * other local state, with no line.
     */
    suspend fun forgetCircle(circleId: String) {
        val account = account() ?: return
        lock.withLock {
            dao.deleteCircle(account, circleId)
            dao.deleteNoticesOfCircle(account, circleId)
        }
    }

    internal fun notice(change: PendingChangeEntity, line: String) = DroppedNoticeEntity(
        id = change.id,
        account = change.account,
        circleId = change.circleId,
        kind = change.kind,
        target = change.target,
        line = line,
        createdAt = now(),
    )

    companion object {
        private const val TAG = "PendingChanges"

        const val WAITING = "waiting"
        const val RUNNING = "running"
        const val NEEDS_PERSON = "needs_person"
        const val CANCELLING = "cancelling"
        const val SETTLING = "settling"

        /** [PendingChangeEntity.lastOutcome] after a request that went out and got no answer. */
        const val LOST_ANSWER = "lost_answer"

        /** A merge only goes into a change that isn't going out or ending. */
        private val MERGEABLE = setOf(WAITING, NEEDS_PERSON)

        /** At most this many changes wait per circle. */
        const val MAX_PER_CIRCLE = 20
        const val MAX_PER_ACCOUNT = 100

        /** Two identical taps within this are one. */
        const val SAME_TAP_MS = 2_000L

        private val gson = com.google.gson.Gson()

        /**
         * The statements [change]'s runs signed, oldest first; null when the stored list can't be
         * read, which never counts as "none" (nothing is settled against it).
         */
        fun signed(change: PendingChangeEntity): List<SignedStatement>? =
            if (change.signedIds.isBlank()) emptyList()
            else runCatching { gson.fromJson(change.signedIds, Array<SignedStatement>::class.java)?.toList() }.getOrNull()

        fun signedIds(change: PendingChangeEntity): List<String> = signed(change)?.map { it.id }.orEmpty()

        /** [change] with [statement] saved among its signed ones. */
        fun withSigned(change: PendingChangeEntity, statement: SignedStatement): PendingChangeEntity =
            change.copy(signedIds = gson.toJson((signed(change) ?: error("unreadable signed list")) + statement))

        /** A statement id as the look sends and answers it: 16 bytes of hex, lower case. */
        fun hexId(id: String): String = id.replace("-", "").lowercase()

        /**
         * A run of [change] may have reached the server: a send got no answer, a run was found
         * in progress after a restart (both leave [LOST_ANSWER]), or it saved a statement id.
         * Written durably before the request leaves (the running mark), never cleared.
         */
        fun mayHaveGoneOut(change: PendingChangeEntity): Boolean =
            change.lastOutcome == LOST_ANSWER || change.signedIds.isNotBlank()
    }
}
