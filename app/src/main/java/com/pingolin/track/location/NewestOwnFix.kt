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

package com.pingolin.track.location

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.pingolin.track.service.LocationPolicy
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The newest fix of this account's own position the app took to send, by ANY path: the tracking
 * service's own fixes, and every one taken outside it (send-now and the map-open refresh,
 * member_joined's send, the heartbeat worker, a check-in, a needs-attention alert, a destination).
 *
 * Why it exists: a liveness check-in re-sends a CACHED fix stamped now, and a send made outside
 * the service never reaches that cache. After "Send location now" from a new place, the next
 * check-in would put the person back at the old place as current, and a viewer that works
 * crossings out itself (the web client) would show a departure that never happened. So a check-in
 * re-sends the newer of the two.
 *
 * - Kept only when the service's inbound guards, at their strictest, would take it (accuracy
 *   [LocationPolicy.MIN_ACCURACY_HIGH], age [LocationPolicy.STALE_FIX_MAX_AGE_MS]) and only when
 *   newer by [Location.getElapsedRealtimeNanos], the one clock every source in this process
 *   shares. Memory only: nothing here outlives the process, so no clock from before a reboot is
 *   compared.
 * - Per account: held under the canonical id of the account signed in when it was offered, never
 *   given to another; [clear]ed at sign-out with the rest of the in-memory positions.
 * - The RAW fix, as taken: each circle's approximate rule and pause apply when it is sent. Kept as
 *   given; nothing in the app changes a Location after taking it.
 * - Thread-safe: the service, the map (main thread) and workers all write it.
 *
 * Beside it, the time of the newest fix any live send carried, whatever the guards say
 * ([noteSent]): a send-now indoors can go out at 150 m, which the guards refuse to keep, and a
 * check-in must not then re-send an older position stamped now ([goesBack]).
 */
@Singleton
class NewestOwnFix @Inject constructor(private val sessionManager: SessionManager) {

    /** The clock fixes are compared on; tests set it. */
    internal var elapsedNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }

    private var account: String? = null
    private var fix: Location? = null

    private var sentAccount: String? = null
    private var sentNanos: Long = 0L

    private val keptFixes = MutableSharedFlow<Location>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Each fix [offer] keeps, told once: the tracking service takes in one taken outside it, for the
     * same bookkeeping and crossing check as its own (LocationTrackingService.takeOneShotFix).
     */
    val kept: SharedFlow<Location> = keptFixes.asSharedFlow()

    /** Keep [location] for the account signed in now when the guards take it and it is newer. */
    fun offer(location: Location?): Boolean {
        // A Location that can't even be read is no position to keep.
        if (location == null || !runCatching { usable(location, elapsedNanos()) }.getOrDefault(false)) return false
        val me = AccountIds.canonical(sessionManager.getUserId()) ?: return false
        synchronized(this) {
            val held = fix?.takeIf { account == me }
            if (held != null && location.elapsedRealtimeNanos <= held.elapsedRealtimeNanos) return false
            account = me
            fix = location
        }
        keptFixes.tryEmit(location)
        return true
    }

    /** The newest fix held for the account signed in now, or null. */
    fun newest(): Location? {
        val me = AccountIds.canonical(sessionManager.getUserId()) ?: return null
        synchronized(this) {
            return fix?.takeIf { account == me }
        }
    }

    /**
     * [location] went out live: its time is the newest sent when it is newer, whatever the guards
     * say about keeping it. Only a time on the shared clock counts.
     */
    fun noteSent(location: Location?) {
        val at = location?.let { runCatching { it.elapsedRealtimeNanos }.getOrNull() } ?: return
        if (at <= 0L || at > elapsedNanos()) return
        val me = AccountIds.canonical(sessionManager.getUserId()) ?: return
        synchronized(this) {
            if (sentAccount != me) {
                sentAccount = me
                sentNanos = 0L
            }
            if (at > sentNanos) sentNanos = at
        }
    }

    /**
     * Whether re-sending [payload] stamped now would go back on a position already sent for the
     * account signed in now: it is older than the newest fix a live send carried.
     */
    fun goesBack(payload: Location): Boolean {
        val me = AccountIds.canonical(sessionManager.getUserId()) ?: return false
        val sent = synchronized(this) { if (sentAccount == me) sentNanos else 0L }
        return goesBack(payload.elapsedRealtimeNanos, sent)
    }

    /** [candidate], or the fix held here when that one is newer: what saying "I am here now" carries. */
    fun newerOf(candidate: Location): Location {
        offer(candidate)
        val held = newest() ?: return candidate
        return if (held.elapsedRealtimeNanos > candidate.elapsedRealtimeNanos) held else candidate
    }

    fun clear() {
        synchronized(this) {
            account = null
            fix = null
            sentAccount = null
            sentNanos = 0L
        }
    }

    companion object {
        /** Whether a fix taken at [payloadNanos] is older than the newest sent, at [sentNanos] (0: none). */
        internal fun goesBack(payloadNanos: Long, sentNanos: Long): Boolean = sentNanos > 0L && payloadNanos < sentNanos

        /**
         * Whether the service's inbound guards would take [location] whatever its mode: within the
         * real-time accuracy bound and no older than the stale-fix limit, on a clock that compares.
         */
        internal fun usable(location: Location, nowElapsedNanos: Long): Boolean {
            val at = location.elapsedRealtimeNanos
            if (at <= 0L || at > nowElapsedNanos) return false
            if (location.hasAccuracy() && location.accuracy > LocationPolicy.MIN_ACCURACY_HIGH) return false
            return (nowElapsedNanos - at) / 1_000_000L <= LocationPolicy.STALE_FIX_MAX_AGE_MS
        }
    }
}
