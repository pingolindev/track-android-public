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

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A database this phone couldn't open: a Keystore refusal that may pass
 * ([DatabaseKeyRefusedException]), a locked phone ([DeviceLockedException]), or the secure storage
 * itself. Room throws the open's failure into every flow's collector, and the main screen collects
 * its flows at a cold start with no handler, so without this a failed open would crash the app at
 * every start. MainActivity says it once, in plain words, and takes that down when the database
 * opens.
 */
object DatabaseUnavailable {
    private const val TAG = "DatabaseUnavailable"

    /** The first look again; each after waits twice as long, to [RETRY_MAX_MS]. */
    internal const val RETRY_FIRST_MS = 2_000L
    internal const val RETRY_MAX_MS = 60_000L

    private val _failure = MutableStateFlow<Throwable?>(null)

    /** Why the database can't be opened, from its first failure until it opens; null while it's open. */
    val failure: StateFlow<Throwable?> = _failure.asStateFlow()

    private val said = AtomicBoolean(false)

    fun report(e: Throwable) {
        // The class only: a message could name a key or a path.
        if (_failure.compareAndSet(null, e)) Log.w(TAG, "The database couldn't be opened: ${e.javaClass.simpleName}")
    }

    /**
     * True the first time it's asked while the database can't be opened, and never again in this
     * process: one flow that keeps failing while the others open must not say it every minute.
     */
    fun takeUnsaid(): Boolean = _failure.value != null && said.compareAndSet(false, true)

    /** A flow read the database: it's open. */
    fun opened() {
        if (_failure.value != null) _failure.value = null
    }

    /**
     * Whether [e] is the database not opening, anywhere in its cause chain, as Room can wrap the
     * open's failure: a Keystore refusal that may pass, a locked phone, the secure storage or its
     * preferences refusing (the passphrase's store turns any failure of its own into a
     * SecurityException), or SQLite's and SQLCipher's own (a seal that opened the wrong database
     * is SQLCipher's SQLiteNotADatabaseException, an SQLiteException). Anything else is an
     * unexpected error, and surfaces as one.
     */
    internal fun isUnavailable(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is DatabaseKeyRefusedException ||
                cur is DeviceLockedException ||
                cur is SecureStorageUnavailableException ||
                cur is SecurityException ||
                cur is android.database.SQLException ||
                // SQLCipher's that aren't SQLiteExceptions, by package, so none is missed.
                cur.javaClass.name.startsWith("net.zetetic.database.")
            ) return true
            cur = cur.cause
        }
        return false
    }

    /** The wait before look again number [attempt] (0 the first). */
    internal fun retryAfterMs(attempt: Long): Long =
        minOf(RETRY_MAX_MS, RETRY_FIRST_MS shl attempt.coerceIn(0L, 5L).toInt())

    @VisibleForTesting
    internal fun reset() {
        _failure.value = null
        said.set(false)
    }
}

/**
 * This Room flow, with [empty] in place of the rows while the database can't be opened: the
 * screen shows nothing rather than the app crashing, [DatabaseUnavailable] says why, and the
 * flow looks again by itself, so every screen fills in once the database opens. Without the look
 * again, a flow shared for the whole screen's life (the unread badge, the Places circles) would
 * stay empty after a refusal that passed, until Track was closed.
 */
fun <T> Flow<T>.orWhenUnopenable(empty: T): Flow<T> =
    onEach { DatabaseUnavailable.opened() }
        .retryWhen { e, attempt ->
            // Only the database not opening: any other error in a flow
            // surfaces.
            if (!DatabaseUnavailable.isUnavailable(e)) return@retryWhen false
            DatabaseUnavailable.report(e)
            emit(empty)
            delay(DatabaseUnavailable.retryAfterMs(attempt))
            true
        }

/**
 * This Room flow of one row, or none, looking again by itself while the database can't be opened,
 * with nothing in the meantime: an empty value would be a null that reads as "gone", and a screen
 * would close or say so. As [orWhenUnopenable] otherwise: [DatabaseUnavailable] says why, and any
 * other error surfaces.
 */
fun <T> Flow<T>.retryWhenUnopenable(): Flow<T> =
    onEach { DatabaseUnavailable.opened() }
        .retryWhen { e, attempt ->
            if (!DatabaseUnavailable.isUnavailable(e)) return@retryWhen false
            DatabaseUnavailable.report(e)
            delay(DatabaseUnavailable.retryAfterMs(attempt))
            true
        }
