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

import android.content.Context
import android.location.Location
import android.util.Log
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.SosRepository
import com.pingolin.track.util.ConnectivityMonitor
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SosMarks
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the countdown, and the send at the end of it, for the whole application.
 *
 * WHY THIS IS NOT IN THE VIEWMODEL
 *   `SosFragment` scopes its ViewModel with `by viewModels()`, so leaving the
 *   Safety tab destroys it and cancels `viewModelScope`. A send sitting five
 *   one-second delays inside that scope would be cancelled mid-countdown by
 *   pressing the button and then switching to the Map tab to watch what happens:
 *   no send, no error, no toast, nothing. The most natural thing a frightened
 *   person does after pressing the button must not stop it working.
 *
 *   So the countdown outlives any screen. A @Singleton with its own scope is
 *   enough for a five-second window with the app in the foreground (and the
 *   location foreground service usually holding the process up anyway); the only
 *   thing that can still stop it is the process actually dying.
 *
 * WHAT SURVIVES WHAT
 *   [cancelCountdown] is the user's deliberate abort and stays working for the
 *   whole countdown. Once the countdown reaches zero nothing cancels the send —
 *   it is launched into [scope] rather than as a child of the countdown, so
 *   neither a cancel, nor starting another countdown, nor any screen going away
 *   can interrupt a request that is already on its way.
 *
 * REPORTING BACK
 *   [outcome] is deliberately a StateFlow that has to be [consumeOutcome]d rather
 *   than a one-shot event: when the send lands there may be no UI attached — that
 *   is the entire point of this class — and the raiser still needs to be told
 *   what happened when they come back.
 *
 * NOT SENT
 *   A raise that didn't reach the server is never a toast that's gone in three
 *   seconds. [notSent] holds it: the SOS card shows it for as long as it lasts,
 *   and so does a notice outside the app. When nothing left this phone (no
 *   connection), it waits and goes by itself once a connection is back, as long
 *   as an SOS still rings (24 h from when it was asked) or until its raiser lets
 *   it go. When it may have reached the server (no answer), it is never sent
 *   again by itself: the circle could get two.
 *   It is kept ([PendingRaises], per account, wiped at sign-out) from the moment
 *   it is raised until it goes, so a process Android ends while it waits never
 *   loses it silently: a job sends it once the phone is online ([RaiseOutside]),
 *   and the next start goes on waiting ([resumePending]), or tells the person it
 *   may not have gone. It goes out only as the account that raised it.
 *   Its raise time is when the countdown completed, never the send: an ask at
 *   10:00 that gets through at 10:45 still says 10:00. A late try reads the
 *   circle's key first, and seals into the raised circle only.
 *
 * WHERE THE POSITION COMES FROM
 *   The send never waits for a fix: waiting would abandon the alert when none
 *   comes. The press (the countdown's start, or Send now) starts the precise fix
 *   and one read of the circle's privacy row, beside the countdown; the send
 *   seals whatever [SosPositionChooser] picks from what this phone holds then.
 *   Once the alert exists, a better position replaces it within 10 minutes, at
 *   most 3 times (the update rule, [SosRepository.runOwnSos]), and the circle's
 *   key is checked every minute while it stays open.
 */
@Singleton
class SosDispatcher internal constructor(
    private val sosRepository: SosRepository,
    private val locationRepository: LocationRepository,
    private val positions: SosPositionChooser,
    /** One read of a circle's privacy row: CircleRepository.refreshPrivacy. */
    private val readPrivacy: suspend (String) -> Unit,
    /** The circle list, and a key import where a version moved: CircleRepository.refreshCircles. */
    private val readKeys: suspend () -> Unit,
    /**
     * Application-lifetime by construction. Injected only so tests can drive
     * the five one-second delays with a TestScope instead of waiting them out
     * — which is also the only way to assert the thing that matters here: that
     * the send does not belong to any screen's scope.
     */
    private val scope: CoroutineScope,
    /**
     * A raise waiting for a connection: returns once one is back, or after the time given,
     * whichever is first. A timer as well as the signal, as a connection can come back
     * unannounced.
     */
    private val awaitConnection: suspend (Long) -> Unit = { delay(it) },
    /** The signed-in account: a raise goes out only as the account that raised it. */
    private val account: () -> String? = { "me" },
    /** What hasn't gone yet, kept per account so a process that ends doesn't lose it ([PendingRaise]). */
    private val pendingRaises: PendingRaises = MemoryPendingRaises(account),
    /** The notice outside the app, and the job that sends a waiting raise once the phone is online. */
    private val outside: RaiseOutside = RaiseOutside.None,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        sosRepository: SosRepository,
        locationRepository: LocationRepository,
        positions: SosPositionChooser,
        circleRepository: dagger.Lazy<CircleRepository>,
        connectivity: dagger.Lazy<ConnectivityMonitor>,
        sessionManager: SessionManager,
        outside: SosRaiseOutside,
    ) : this(
        sosRepository,
        locationRepository,
        positions,
        { circleRepository.get().refreshPrivacy(it) },
        { circleRepository.get().refreshCircles() },
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        // The next time the phone is online again: one already online counts only when it comes back.
        { ms -> withTimeoutOrNull(ms) { connectivity.get().isOnline.drop(1).first { it } } },
        { sessionManager.getUserId() },
        SecurePrefsPendingRaises(context, { sessionManager.getUserId() }),
        outside,
    )

    companion object {
        private const val TAG = "SosDispatcher"
        const val COUNTDOWN_SECONDS = 5

        /** How long the press's precise fix may take: the platform sets no bound of its own. */
        const val PRECISE_FIX_TIMEOUT_MS = 60_000L

        /** While an update is due, one fix this often (the update rule's Android source), and a deferred one
         * re-checked.
         */
        const val UPDATE_POLL_MS = 30_000L

        /** While this phone's own alert is open, its circle's key state this often. */
        const val KEY_CHECK_MS = 60_000L

        /** A raise waiting for a connection tries again at least this often. */
        const val RETRY_MS = 60_000L

        /**
         * And at least this long after the last try: a network that just came back gets a moment to
         * settle, and a signal that comes again and again can't make a tight loop.
         */
        const val RETRY_GAP_MS = 2_000L

        /** A late try's one circle/key read gets this long: never a reason not to send. */
        const val KEY_READ_MS = 10_000L

        /** How long the job that woke a raise waits for its try after a restart ([tryWaiting]). */
        const val WOKEN_TRY_MS = 90_000L

        /**
         * Whether [failure] means the raise never left this phone, so sending it again can't make a
         * second alert: no connection to make it on (PendingChangeRunner.classify's maybeSent = false).
         */
        internal fun neverLeft(failure: Throwable?): Boolean =
            failure is java.net.UnknownHostException || failure is java.net.ConnectException ||
                failure is java.net.NoRouteToHostException ||
                // Refused before it left: the session store couldn't be opened.
                failure is com.pingolin.track.util.StoreRefusedIOException

        /** Why a raise that failed with [failure] hasn't reached the circle. */
        internal fun whyNotSent(failure: Throwable?): NotSent.Why = when {
            neverLeft(failure) -> NotSent.Why.WAITING
            // No answer: it may have been stored, and a second would ring everyone again.
            failure is java.io.IOException -> NotSent.Why.UNSURE
            else -> NotSent.Why.NOT_SENT
        }
    }

    /** The wall clock the press is dated on; tests set it. */
    internal var wallNow: () -> Long = System::currentTimeMillis

    /**
     * Whether a raise that went reached anyone (as on iOS): checked for the whole
     * application from its Sent, and told once wherever the person is when it reached
     * nobody.
     */
    val deliveries = SosDeliveryWatcher(
        { sosRepository.getSosDelivery(it) }, scope, account, { wallNow() },
        { outside.deliveryNotice(it) }, { outside.cancelDeliveryNotice() },
    )

    /**
     * The raiser's commitment: when, in which circle, and the two reads it started, the precise fix
     * and the circle's privacy row. Neither blocks the send.
     */
    private class Press(val circleId: String, val at: Long, val fix: Deferred<Location?>, val privacy: Job) {
        fun cancel() {
            fix.cancel()
            privacy.cancel()
        }
    }

    /** The press a running countdown holds, for [cancelCountdown] and [sendNow]. */
    @Volatile
    private var press: Press? = null

    private fun press(circleId: String): Press = Press(
        circleId = circleId,
        at = wallNow(),
        fix = scope.async {
            try {
                withTimeoutOrNull(PRECISE_FIX_TIMEOUT_MS) { locationRepository.getCurrentLocation() }
            } catch (e: Exception) {
                // No fix is all a failure means here: the send goes without it.
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
        },
        privacy = scope.launch {
            try {
                readPrivacy(circleId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Privacy read at the press failed: ${e.javaClass.simpleName}")
            }
        },
    )

    /** What a finished send has to say for itself; one that didn't reach the circle is [notSent]. */
    sealed class Outcome {
        /** The server stored the alert. Says nothing yet about who it reached. */
        data class Sent(val alert: SosAlertEntity) : Outcome()
    }

    /**
     * A raise in [circleId], asked at [raisedAt], that hasn't reached the circle, and why.
     * [storeRefused]: waiting on this phone's session store, which couldn't be opened, and not on
     * a connection. Its words say so: "no connection" would be false, the phone is online.
     */
    data class NotSent(val circleId: String, val why: Why, val raisedAt: Long, val storeRefused: Boolean = false) {
        enum class Why {
            /**
             * Nothing left this phone, for want of a connection or of the session store
             * ([storeRefused]): it goes by itself once it can.
             */
            WAITING,
            /** No answer: it may have been stored, so it isn't sent again by itself. */
            UNSURE,
            /** Refused, or it couldn't be made here: the circle wasn't notified. */
            NOT_SENT,
        }
    }

    /**
     * Seconds remaining, or null when no countdown is running. Zero means the
     * countdown finished and the send is in flight — still worth showing, but
     * no longer cancellable.
     */
    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    /** The raise that hasn't reached the circle, until it does or its raiser lets it go. */
    private val _notSent = MutableStateFlow<NotSent?>(null)
    val notSent: StateFlow<NotSent?> = _notSent.asStateFlow()

    /**
     * A raise: its press, when it was asked (the countdown's end), and the account that asked.
     * Kept as a [PendingRaise] from then until it goes.
     */
    private class Raise(val press: Press, val raisedAt: Long, val account: String) {
        val circleId: String get() = press.circleId

        fun record(state: PendingRaise.State, told: Boolean = false, storeRefused: Boolean = false) =
            PendingRaise(circleId, press.at, raisedAt, state, told, storeRefused)
    }

    /**
     * The raise being sent, or waiting to be, or said not to have gone: it goes on, and is shown,
     * only while it is still this one. [letGo] and a sent alert clear it.
     */
    @Volatile
    private var raising: Raise? = null

    /** Tries made in this process: what the job that woke a raise waits on ([tryWaiting]). */
    private val tries = MutableStateFlow(0)

    /** The job's runs inside [tryWaiting] now: none of them is cancelled from here. */
    private val jobRuns = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The job that sends a waiting raise, cancelled only while none of its runs is in progress. A
     * run ends by itself once its try and the delivery checks are done: cancelled, the checks
     * would end with it, whichever try sent the raise. A queued one goes: run later during a store
     * refusal, it would say "Not sent yet" for a raise that went.
     */
    private fun cancelQueuedWake() {
        if (jobRuns.get() == 0) outside.cancelWake()
    }

    /** What the job that runs a waiting raise found ([tryWaiting]). */
    enum class Waited { DONE, STILL_WAITING }

    private var countdownJob: Job? = null

    /** True while the user can still call it off. */
    val isCancellable: Boolean get() = (_countdown.value ?: 0) > 0

    fun startCountdown(circleId: String) {
        countdownJob?.cancel()
        press?.cancel()
        letGo()
        _outcome.value = null
        _countdown.value = COUNTDOWN_SECONDS
        // The moment of commitment: the fix gets the countdown for free.
        val pressed = press(circleId).also { press = it }

        countdownJob = scope.launch {
            for (i in COUNTDOWN_SECONDS downTo 1) {
                _countdown.value = i
                delay(1000)
            }
            // Zero, not null: the countdown is spent but the send has not
            // finished, and the raiser should not see the button they just
            // pressed offering itself again.
            _countdown.value = 0
            countdownJob = null
            press = null

            // Launched into `scope`, NOT as a child of this coroutine.
            // From here the request is beyond the reach of every cancel
            // in the app. Asked now: the countdown completed.
            raise(pressed)
        }
    }

    /** Skip the countdown entirely. */
    fun sendNow(circleId: String) {
        countdownJob?.cancel()
        countdownJob = null
        // A countdown's press for this circle is the commitment already made; else this is.
        val pressed = press?.takeIf { it.circleId == circleId } ?: run {
            press?.cancel()
            press(circleId)
        }
        press = null
        letGo()
        _outcome.value = null
        _countdown.value = 0
        raise(pressed)
    }

    /** [pressed] is asked now, by the account signed in now; sent, and kept until it goes. */
    private fun raise(pressed: Press) {
        val asked = wallNow()
        val who = account()
        if (who == null) {
            // Nobody to raise it as: nothing can be sealed, so it is said not to have gone.
            _countdown.value = null
            _notSent.value = NotSent(pressed.circleId, NotSent.Why.NOT_SENT, asked)
            return
        }
        val raise = Raise(pressed, asked, who)
        raising = raise
        pendingRaises.put(raise.record(PendingRaise.State.OUT))
        scope.launch { send(raise, late = false) }
    }

    /**
     * The raiser lets a raise that hasn't reached the circle go: one waiting for a connection
     * is not sent, and its record and notice go. Nothing else is undone; one that may have been
     * stored stays so.
     */
    fun letGo() {
        val had = raising != null || _notSent.value != null || pendingRaises.get() != null
        raising?.press?.cancel()
        raising = null
        _notSent.value = null
        if (!had) return
        pendingRaises.remove()
        outside.notice(null)
        outside.cancelWake()
    }

    /** Sign-out: no raise of this account's goes out, or is shown, afterwards; every record goes. */
    fun forgetAccount() {
        letGo()
        pendingRaises.wipeAll()
        // Nor what one that went reached.
        deliveries.stop()
    }

    /**
     * After a restart, or a sign-in: the raise this account left, from its record.
     *  - Waiting for a connection, within its 24 h: it goes on waiting, and goes.
     *  - A try that was out when the process ended may have reached the server: never sent again
     *    by itself; it shows as "couldn't tell", and the person is told.
     *  - Refused, or past its 24 h: it shows as not sent, and the person is told.
     * Another account's raise held in this process is dropped first: it never shows, or goes, as
     * this one's. Returns whether a wait started again here.
     */
    fun resumePending(): Boolean {
        // What another account sent is never shown or said here either.
        deliveries.keepOnlyOurs()
        val who = account() ?: return false
        raising?.let { held ->
            if (sameAccount(held.account, who)) return false
            held.press.cancel()
            raising = null
            _notSent.value = null
        }
        val record = pendingRaises.get() ?: return false
        val pressed = Press(
            circleId = record.circleId,
            at = record.pressAt,
            fix = kotlinx.coroutines.CompletableDeferred<Location?>(null),
            privacy = Job().apply { complete() },
        )
        val raise = Raise(pressed, record.raisedAt, who)
        val expired = wallNow() - record.raisedAt >= SosMarks.MAX_SOS_ALARM_AGE_MS
        val why = when {
            record.state == PendingRaise.State.WAITING && !expired -> NotSent.Why.WAITING
            record.state == PendingRaise.State.OUT || record.state == PendingRaise.State.UNSURE -> NotSent.Why.UNSURE
            else -> NotSent.Why.NOT_SENT
        }
        raising = raise
        // Said again with its own cause's words: the record keeps it.
        val storeRefused = why == NotSent.Why.WAITING && record.storeRefused
        val notSent = NotSent(record.circleId, why, record.raisedAt, storeRefused)
        _notSent.value = notSent
        if (why == NotSent.Why.WAITING) {
            outside.notice(notSent)
            outside.wakeWhenOnline()
            scope.launch { send(raise, late = true) }
            return true
        }
        val state = if (why == NotSent.Why.UNSURE) PendingRaise.State.UNSURE else PendingRaise.State.NOT_SENT
        if (!record.told) outside.notice(notSent)
        pendingRaises.put(raise.record(state, told = true))
        cancelQueuedWake()
        return false
    }

    /**
     * The job that runs a waiting raise once the phone is online ([RaiseOutside.wakeWhenOnline]):
     * after a restart it takes the raise from its record. Either way it waits for the wait's next
     * try, which goes now the phone is online: Android may end a process whose job is done before
     * that try goes (the start's own resume can take the raise first, and the job would return
     * before the try). STILL_WAITING keeps the job for the next time. Once one went, the job also
     * stays while its delivery checks run, and nothing here cancels it meanwhile: it ends itself.
     */
    suspend fun tryWaiting(): Waited {
        jobRuns.incrementAndGet()
        try {
            val before = tries.value
            resumePending()
            val raise = raising
            // Nothing pending, nothing held, and the store open (the job asked it first): a
            // waiting notice left by a run that returned as its raise went comes down.
            if (raise == null && pendingRaises.get() == null) outside.notice(null)
            if (raise != null && _notSent.value?.why == NotSent.Why.WAITING) withTimeoutOrNull(WOKEN_TRY_MS) { tries.first { it > before } }
            if (raise != null && raising === raise && _notSent.value?.why == NotSent.Why.WAITING) return Waited.STILL_WAITING
            // One that went stays followed while the job runs, for the same reason: a request
            // that reached nobody is told, and the process may end once the job is done.
            deliveries.awaitChecks(SosDeliveryWatcher.WINDOW_MS + SosDeliveryWatcher.POLL_MS)
            // A new press waiting by now asked for this job while it ran, and was kept out (KEEP):
            // the job stays for it.
            if (raising != null && _notSent.value?.why == NotSent.Why.WAITING) return Waited.STILL_WAITING
            return Waited.DONE
        } finally {
            jobRuns.decrementAndGet()
        }
    }

    /** Try again after a raise that didn't reach the circle: a new press, made now, in the same circle. */
    fun tryAgain() {
        val circleId = _notSent.value?.circleId ?: return
        sendNow(circleId)
    }

    /**
     * The user's abort. Only a running countdown can be called off — once the
     * send has started there is nothing left to stop, and cancelling then would
     * abandon a half-issued alert.
     *
     * @return whether anything was actually cancelled.
     */
    fun cancelCountdown(): Boolean {
        if (!isCancellable) return false
        countdownJob?.cancel()
        countdownJob = null
        // And both reads the press started.
        press?.cancel()
        press = null
        _countdown.value = null
        return true
    }

    /** Call once the outcome has been shown to the user. */
    fun consumeOutcome() {
        _outcome.value = null
    }

    /** Still the raise being sent, and still the account that raised it, signed in. */
    private fun ours(raise: Raise): Boolean = raising === raise && sameAccount(account(), raise.account)

    /** [a] and [b] are one account: the same bytes in any spelling, or the session's same string. */
    private fun sameAccount(a: String?, b: String?): Boolean =
        a != null && (com.pingolin.track.util.AccountIds.same(a, b) || a == b)

    /**
     * Sends [raise] until it goes, can't, or is let go, keeping its record as it goes. A [late]
     * raise (resumed after a restart), and every try after the first, reads the circle's key
     * first, so it is sealed under the circle's key now.
     */
    private suspend fun send(raise: Raise, late: Boolean) {
        var tried = 0
        try {
            while (true) {
                if (late || tried > 0) {
                    withTimeoutOrNull(KEY_READ_MS) {
                        try {
                            readKeys()
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Log.w(TAG, "Key read before a late raise failed: ${e.javaClass.simpleName}")
                        }
                    }
                    // Let go, a new press made, or another account signed in while it waited or read:
                    // nothing more to send.
                    if (!ours(raise)) return
                }
                // A try is out: a process that ends now may have reached the server with it.
                pendingRaises.put(raise.record(PendingRaise.State.OUT))
                // Never waited for: what this phone holds now, the press's fix if it came in. A
                // raise with no position still goes, with the marker. As of the window, never
                // later.
                val position = positions.forRaise(raise.circleId, raise.press.at, raise.press.fix.inHand())
                val result = sosRepository.triggerSos(
                    circleId = raise.circleId,
                    position = position,
                    pressAt = raise.press.at,
                    raisedAt = raise.raisedAt,
                )
                tried++
                val alert = result.getOrNull()
                if (alert != null) {
                    if (raising === raise) {
                        raising = null
                        pendingRaises.remove()
                        _notSent.value = null
                        outside.notice(null)
                        cancelQueuedWake()
                    }
                    // Said, and followed, only while the account that raised it is the one signed in.
                    if (sameAccount(account(), raise.account)) {
                        _outcome.value = Outcome.Sent(alert)
                        // Whether it reached anyone, whichever screen is up,
                        // or none.
                        deliveries.watch(alert.id)
                        // The update needs the alert's id: after the create's answer, and never
                        // without one.
                        follow(alert, raise.press)
                    }
                    // After the checks start: the job waiting on this try then waits for them.
                    tries.value++
                    return
                }
                val failure = result.exceptionOrNull()
                // The class only: a socket's message can name hosts and addresses.
                Log.w(TAG, "Notify-circle send failed: ${failure?.javaClass?.simpleName}")
                // Let go, or a new press made, while it was out: nothing more to say or send.
                if (!ours(raise)) {
                    tries.value++
                    return
                }
                var why = whyNotSent(failure)
                // Waiting goes on as long as an SOS still rings, from when it was asked: past that,
                // nobody would hear it.
                if (why == NotSent.Why.WAITING && wallNow() - raise.raisedAt >= SosMarks.MAX_SOS_ALARM_AGE_MS) {
                    why = NotSent.Why.NOT_SENT
                }
                // Waiting on the session store, not a connection: said so, and kept so.
                val storeRefused = why == NotSent.Why.WAITING && failure is com.pingolin.track.util.StoreRefusedIOException
                val notSent = NotSent(raise.circleId, why, raise.raisedAt, storeRefused)
                pendingRaises.put(raise.record(stateOf(why), told = why != NotSent.Why.WAITING, storeRefused = storeRefused))
                _countdown.value = null
                _notSent.value = notSent
                outside.notice(notSent)
                tries.value++
                if (why != NotSent.Why.WAITING) {
                    cancelQueuedWake()
                    return
                }
                outside.wakeWhenOnline()
                awaitConnection(RETRY_MS)
                delay(RETRY_GAP_MS)
            }
        } catch (e: Exception) {
            // Nothing may escape: this coroutine has no UI behind it to report
            // a crash, and the raiser must still be told the alert failed.
            Log.e(TAG, "Notify-circle send threw", e)
            tries.value++
            if (raising === raise) {
                val notSent = NotSent(raise.circleId, NotSent.Why.NOT_SENT, raise.raisedAt)
                pendingRaises.put(raise.record(PendingRaise.State.NOT_SENT, told = true))
                _notSent.value = notSent
                outside.notice(notSent)
                cancelQueuedWake()
            }
        } finally {
            // Not while another press holds the card: a countdown, or a send of its own.
            if (press == null && raising.let { it == null || it === raise }) _countdown.value = null
        }
    }

    private fun stateOf(why: NotSent.Why): PendingRaise.State = when (why) {
        NotSent.Why.WAITING -> PendingRaise.State.WAITING
        NotSent.Why.UNSURE -> PendingRaise.State.UNSURE
        NotSent.Why.NOT_SENT -> PendingRaise.State.NOT_SENT
    }

    /** The press's fix when it already came in, else null: the send never waits for it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun Deferred<Location?>.inHand(): Location? =
        if (isCompleted && !isCancelled) runCatching { getCompleted() }.getOrNull() else null

    /** After the create: the update window, and the key check for as long as the alert is open. */
    private fun follow(alert: SosAlertEntity, pressed: Press) {
        scope.launch { updates(alert.id, pressed) }
        scope.launch { keyChecks(alert) }
    }

    /**
     * The update rule's driver on this phone ([SosUpdateRule]): the press's fix (up to 60 s), then,
     * while an update can still change the alert, one fix every 30 s (Android has no emergency
     * cadence), each given to a run of [SosRepository.runOwnSos], which decides. A deferred update is
     * run again at the next one. The window's end gets one last run: a raise still "pending" then is
     * sealed "unavail".
     */
    private suspend fun updates(alertId: String, pressed: Press) {
        val end = pressed.at + SosUpdateRule.WINDOW_MS
        var fix = try {
            pressed.fix.await()?.let(positions::exact)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && !pressed.fix.isCancelled) throw e
            null
        }
        while (true) {
            val result = try {
                sosRepository.runOwnSos(alertId, fix)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Position update run failed: ${e.javaClass.simpleName}")
                null
            }
            if (result == SosRepository.OwnRunResult.ENDED) {
                // Over, from here or elsewhere: nothing more to say of whom it reached.
                deliveries.stop(alertId)
                return
            }
            if (wallNow() >= end) return
            if (result != SosRepository.OwnRunResult.DEFERRED && !sosRepository.ownUpdateDue(alertId)) return
            // At most every 30 s, and once more when the window ends.
            val wake = minOf(wallNow() + UPDATE_POLL_MS, end)
            val polled = withTimeoutOrNull((wake - wallNow()).coerceAtLeast(1L)) { locationRepository.getCurrentLocation() }
                ?.let(positions::exact)
            if (polled != null && (fix == null || polled.takenAt > fix.takenAt)) fix = polled
            delay((wake - wallNow()).coerceAtLeast(0L))
        }
    }

    /**
     * While this phone's own alert is open, until it passes its 24 h alarm age, its circle's key
     * state every minute: a version that moved is imported, and the import re-seals the alert
     * (SosRepository.resealOwn), so a rotation leaves it hidden on receivers for about a minute at
     * most while this phone lives. After a restart the next wake (push or launch) does it.
     */
    private suspend fun keyChecks(alert: SosAlertEntity) {
        val until = alert.createdAt + SosMarks.MAX_SOS_ALARM_AGE_MS
        while (wallNow() < until) {
            delay(KEY_CHECK_MS)
            if (!sosRepository.isOpenHere(alert.id)) return
            try {
                readKeys()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Key check failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
