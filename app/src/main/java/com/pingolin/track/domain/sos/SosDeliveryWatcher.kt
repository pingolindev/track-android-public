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
import com.pingolin.track.util.AccountIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether a help request this phone sent reached anyone, checked for the whole application, as on
 * iOS: checks owned by the Safety tab's ViewModel would end when the person leaves the tab, and
 * the answer would never be learned.
 *
 * Only a request that was SENT (created on the server) is followed: [SosDispatcher] starts the
 * checks from its Sent. One still waiting for a connection says so on its own card.
 *
 * A request that reached nobody (alone, undeliverable), or whose checks ended with no answer at
 * all, is told once, where the person will see it ([verdictOf]):
 *  - the Safety tab's own dialog, when it is on screen and still up;
 *  - otherwise a dialog over whatever screen of Track is in front ([untold], MainActivity);
 *  - otherwise a notification ([notify]).
 * OK on the tab's dialog before the answer only puts it away: the checks go on, and a request
 * that reached nobody is still told. Nothing is told about one that ended (resolved, a false
 * alarm) or after its account signed out.
 */
class SosDeliveryWatcher internal constructor(
    private val fetch: suspend (String) -> SosDeliveryResponse?,
    private val scope: CoroutineScope,
    /** The signed-in account: what one account sent is never said to another. */
    private val account: () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
    /** The notification, when Track isn't in front. */
    private val notify: (Verdict) -> Unit = {},
    /** Takes that notification down: the request it speaks of is over, or its account signed out. */
    private val cancelNotice: () -> Unit = {},
) {
    companion object {
        /** How often to re-ask the server how far the alert got. */
        const val POLL_MS = 3_000L

        /**
         * Stop asking after this long. Past it a device that has not confirmed
         * is almost certainly off, or has no push distributor, rather than being
         * slow — asking again will not improve the answer.
         */
        const val WINDOW_MS = 60_000L

        /**
         * How long before "undeliverable" is believed. Matches the server's
         * confirmation grace window; below it, a bad verdict is more likely to
         * be a dispatch still in progress than a real one.
         */
        const val SETTLE_MS = 30_000L

        /** The settled answers that reached someone, or were sent and wait on a device: nothing to tell. */
        private val ANSWERED = setOf("confirmed", "partial", "no_confirmation")

        /**
         * What to tell once about [status], or null (iOS's SOSManager.deliveryNotice): only once
         * the checks have ended; reached nobody for `alone` and `undeliverable`; nothing for an
         * answer that reached someone or waits on a device; and no answer for the rest, no
         * response at all included.
         */
        fun verdictOf(status: Status): Verdict? {
            if (status.stillChecking) return null
            return when (status.response?.state) {
                "alone" -> Verdict.ALONE
                "undeliverable" -> Verdict.UNDELIVERABLE
                in ANSWERED -> null
                // No answer at all, or one still on its way ("pending") when the checks ended.
                else -> Verdict.NO_ANSWER
            }
        }
    }

    /** What the raiser is shown while, and after, we find out. */
    data class Status(
        val alertId: String,
        /** Null until the first successful check answers. */
        val response: SosDeliveryResponse?,
        /** False once checking has stopped, whether or not it learned anything. */
        val stillChecking: Boolean,
    )

    /** A request that reached nobody, or whose checks never answered: what it is told as. */
    enum class Verdict { ALONE, UNDELIVERABLE, NO_ANSWER }

    /** The Safety tab's "Did it get through?": null when there's nothing to report, or it was put away. */
    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** A verdict for the screen in front to say (MainActivity), then [told]. */
    private val _untold = MutableStateFlow<Verdict?>(null)
    val untold: StateFlow<Verdict?> = _untold.asStateFlow()

    /** The Safety tab is on screen (started): its own dialog says the verdict while it is up. */
    @Volatile
    var safetyTabOpen: Boolean = false

    /**
     * A screen of Track is in front (MainActivity started, not behind the app lock). Not the
     * process's importance: the location service keeps that high with nothing of Track on screen.
     */
    @Volatile
    var trackInFront: Boolean = false

    /** The request being checked: its account, its latest status, and whether the tab's dialog was put away. */
    private class Watch(val alertId: String, val owner: String?) {
        var latest: Status = Status(alertId, null, stillChecking = true)
        var putAway = false
    }

    private var watch: Watch? = null
    private var job: Job? = null

    /** The request the posted notification speaks of, while it may still be up. */
    private var noticeFor: String? = null

    /** Whether checks are running: the job that woke a raise waits for them (SosDispatcher.tryWaiting). */
    private val checking = MutableStateFlow(false)

    /**
     * Ask until the answer can no longer improve. `partial` and `no_confirmation` keep asking because
     * a late confirmation genuinely arrives: a phone coming out of Doze can confirm tens of seconds
     * in. `confirmed` and `alone` are final on sight. `undeliverable` is NOT accepted as final until
     * [SETTLE_MS] has passed: a server may report it for the first moments after sending, before its
     * dispatch record is written, and this wait guards against that.
     */
    @Synchronized
    fun watch(alertId: String) {
        job?.cancel()
        val w = Watch(alertId, account())
        watch = w
        _status.value = w.latest
        checking.value = true
        job = scope.launch {
            val startedAt = now()
            val deadline = startedAt + WINDOW_MS
            while (true) {
                if (!ours(w)) return@launch end(w)
                val result = try {
                    fetch(alertId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
                ensureActive()
                if (result != null) {
                    val settled = now() - startedAt >= SETTLE_MS
                    val terminal = result.state == "confirmed" ||
                        (result.state == "undeliverable" && settled) ||
                        result.state == "alone"
                    show(w, Status(alertId, result, stillChecking = !terminal))
                    if (terminal) return@launch settle(w)
                }
                if (now() >= deadline) {
                    show(w, w.latest.copy(stillChecking = false))
                    return@launch settle(w)
                }
                delay(POLL_MS)
            }
        }
    }

    /**
     * The tab's OK: its dialog goes. Checks still running go on unseen, so a request that reached
     * nobody is still told (MainActivity's dialog, or a notification); once they have ended,
     * nothing is left to report.
     */
    @Synchronized
    fun putAway() {
        val w = watch
        _status.value = null
        if (w == null) return
        if (w.latest.stillChecking) w.putAway = true else watch = null
    }

    /**
     * The request [alertId] ended (resolved, a false alarm), or, with null, its account signed out:
     * no more checks, and nothing more is said of it. A notification about it goes too.
     */
    @Synchronized
    fun stop(alertId: String? = null) {
        // Its notification too, even once the tab's dialog was put away and nothing else is held.
        noticeFor?.let { if (alertId == null || sameAlert(alertId, it)) takeNoticeDown() }
        val w = watch ?: return
        if (alertId != null && !sameAlert(alertId, w.alertId)) return
        job?.cancel()
        job = null
        end(w)
    }

    /** A sign-in or a start (SosDispatcher.resumePending): another account's request is forgotten. */
    fun keepOnlyOurs() {
        val w = watch ?: return
        if (!ours(w)) stop()
    }

    /** MainActivity said [untold]. */
    fun told() {
        _untold.value = null
    }

    /** Waits for running checks to end, for at most [ms]. */
    suspend fun awaitChecks(ms: Long) {
        withTimeoutOrNull(ms) { checking.first { !it } }
    }

    private fun sameAlert(a: String, b: String): Boolean = AccountIds.storeKey(a) == AccountIds.storeKey(b)

    private fun takeNoticeDown() {
        noticeFor = null
        cancelNotice()
    }

    private fun ours(w: Watch): Boolean {
        val signedIn = account() ?: return false
        return AccountIds.same(signedIn, w.owner) || signedIn == w.owner
    }

    @Synchronized
    private fun show(w: Watch, status: Status) {
        if (watch !== w) return
        w.latest = status
        if (!w.putAway) _status.value = status
    }

    /** The checks have ended: said once, where it will be seen, if it reached nobody or never answered. */
    @Synchronized
    private fun settle(w: Watch) {
        if (watch !== w) return
        checking.value = false
        if (!ours(w)) return
        val verdict = verdictOf(w.latest) ?: return
        when {
            // The tab's own dialog is up, and says it.
            safetyTabOpen && !w.putAway -> Unit
            trackInFront -> _untold.value = verdict
            else -> {
                notify(verdict)
                noticeFor = w.alertId
            }
        }
    }

    @Synchronized
    private fun end(w: Watch) {
        if (watch !== w) return
        checking.value = false
        watch = null
        _status.value = null
        _untold.value = null
        if (noticeFor?.let { sameAlert(it, w.alertId) } == true) takeNoticeDown()
    }
}
