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

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When this account shared each circle approximately, and how wide the bubble was, as far as this
 * phone knows: a point goes to a circle no finer than the bubble in force when it was TAKEN,
 * however late it goes. The send paths coarsen by the circle's setting NOW, so without this a
 * point kept while the member was approximate would go out exact after a switch back to exact;
 * and a bubble set on another phone while this one was offline reaches this one only later.
 *
 * Two sources, read as one ([radiusAt]), as in [PauseIntervals]:
 *  - what the server says of this user's bubbles in the circle ([observe]'s `server`, the privacy
 *    row's `approximate_intervals`): each answer that carries them replaces the last, and a row with
 *    no bubble, or another width, ends one still open as of then (as [PauseIntervals] does);
 *  - what this phone saw itself, at every privacy re-sync and `privacy_check` wake. A bubble began
 *    when the server says it did, from any answer, though never after this phone first saw it; with
 *    no word from the server, when this phone last saw the circle without one. One seen over ended
 *    when the server says, else at its deadline if that has passed, else now. Another width ends
 *    one bubble and starts the next.
 * Within [PauseIntervals.SKEW_MS] of a bubble's ends a point takes it, and where bubbles meet, the
 * wider one.
 *
 * Only the server can say a point's history is complete (a bubble set and undone between two
 * re-syncs is known from nowhere else): [knowsSince] says whether its word came in after a point was
 * taken; a late point it hasn't told this phone about yet goes in the widest bubble.
 *
 * A point the outbox sealed can't be coarsened again: [heldAt] is the bubble this phone itself held at
 * a moment, from when it first saw each bubble on to when it saw it over: its own privacy row then,
 * which every seal takes at the least. Kept per account, as
 * long and as wiped as [PauseIntervals]; a record that can't be read is never "no bubble":
 * [radiusAt] throws [StoreUnreadable].
 */
@Singleton
class ApproximateIntervals internal constructor(
    private val ownerUserId: () -> String?,
    /** Null: kept for this process only ([inMemory]). */
    private val prefsProvider: (() -> SharedPreferences)?,
    private val now: () -> Long,
    /** For an erasure of a file that can't be opened: deleted whole. */
    private val context: Context? = null,
) : KeptPerAccount {

    @Inject
    constructor(@ApplicationContext context: Context, sessionManager: SessionManager) :
        this({ sessionManager.getUserId() }, { SecurePrefs.open(context, FILE_NAME) }, System::currentTimeMillis, context)

    /**
     * A bubble [radiusMiles] wide from [fromMs] to [toMs] (null: no end known). [running]: still on
     * when last seen here. [seenFromMs] and [seenToMs]: when this phone itself first saw it on, the
     * latest its start can be, and saw it over; null for the server's.
     */
    data class Span(
        val fromMs: Long,
        val toMs: Long?,
        val radiusMiles: Int,
        val running: Boolean = false,
        val seenFromMs: Long? = null,
        val seenToMs: Long? = null,
    )

    /**
     * A circle's record: what this phone saw, the server's latest word, and when it last saw the circle
     * with no bubble ([lastExactMs]; [storedExactMs] is that time as the disk holds it).
     */
    private data class State(
        val seen: List<Span>,
        val server: List<Span>,
        val lastExactMs: Long?,
        val storedExactMs: Long? = lastExactMs,
    )

    private val prefs: SharedPreferences? by lazy { prefsProvider?.invoke() }

    private val account = AccountEntries.Owner(STORE, { prefs ?: NOTHING_KEPT }, ownerUserId)

    /** Every record read or written in this process, by key. */
    private val cache = HashMap<String, State>()

    /** When each record last took the server's `approximate_intervals`, in this process only ([knowsSince]). */
    private val serverWordAt = HashMap<String, Long>()

    /**
     * What a privacy row of [circleId] said just now: a bubble [radiusMiles] wide (until [deadlineMs],
     * or with no end known when null), or none when [radiusMiles] is null; and [server], its
     * `approximate_intervals` when it carried them. Never throws: a record that can't be read now is
     * left as it is.
     */
    @Synchronized
    fun observe(circleId: String, radiusMiles: Int?, deadlineMs: Long?, server: List<Span>? = null) {
        val key = account.key(circleId) ?: return
        val state = try {
            read(key)
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: a bubble of this circle isn't recorded now")
            return
        }
        val t = now()
        if (server != null) serverWordAt[key] = t
        // A row with no bubble, or another width, ends what is still open as of now: a word kept from
        // before the change never coarsens a point taken after it.
        val serverWord = (server ?: state.server).map { s ->
            if ((s.toMs == null || s.toMs > t) && s.radiusMiles != radiusMiles) s.copy(toMs = t) else s
        }
        val running = state.seen.lastOrNull { it.running }
        // The server's bubble of this width on now (with no end, or one still to come).
        val serverOpen = radiusMiles?.let { width -> serverWord.lastOrNull { it.radiusMiles == width && (it.toMs == null || it.toMs > t) } }
        val seen = when {
            radiusMiles != null && running == null ->
                state.seen + opened(serverOpen, state.lastExactMs, radiusMiles, deadlineMs, t)
            radiusMiles != null && running!!.radiusMiles == radiusMiles -> state.seen.map { seen ->
                if (seen !== running) return@map seen
                // A start guessed before the server's word came gives way to it.
                seen.copy(fromMs = serverOpen?.let { minOf(it.fromMs, seen.seenFromMs ?: seen.fromMs) } ?: seen.fromMs, toMs = deadlineMs)
            }
            // Another width: the earlier one held until now; the new one began when the
            // server says, else (no word) as far back as the earlier one, so that where
            // they meet the wider counts.
            radiusMiles != null -> state.seen.map { if (it === running) closed(it, serverWord, t) else it } +
                opened(serverOpen, running!!.fromMs, radiusMiles, deadlineMs, t)
            running != null -> state.seen.map { if (it === running) closed(it, serverWord, t) else it }
            else -> state.seen
        }
        // Kept a week after it ended, and after this phone saw it end: what it held is read for as
        // long as anything it sealed can wait.
        fun kept(s: Span) = s.running || s.toMs == null || maxOf(s.toMs, s.seenToMs ?: s.toMs) >= t - PauseIntervals.RETENTION_MS
        val next = state.copy(
            seen = seen.filter(::kept),
            server = serverWord.filter(::kept),
            lastExactMs = if (radiusMiles != null) state.lastExactMs else t,
        )
        // "Seen without a bubble" moves at every re-sync: as in [PauseIntervals], it goes to disk only
        // with a change of the spans, or once it has moved on [EXACT_STEP_MS].
        val stepped = (next.lastExactMs ?: 0) - (state.storedExactMs ?: 0) >= EXACT_STEP_MS
        when {
            next.seen != state.seen || next.server != state.server || stepped -> write(key, next)
            next != state -> cache[key] = next
        }
    }

    /** A bubble this phone sees on now, first: from the server's start (never after now), else [fallbackFrom]. */
    private fun opened(serverOpen: Span?, fallbackFrom: Long?, radiusMiles: Int, deadlineMs: Long?, t: Long) = Span(
        fromMs = serverOpen?.fromMs?.let { minOf(it, t) } ?: fallbackFrom ?: (t - PauseIntervals.UNKNOWN_START_MS),
        toMs = deadlineMs,
        radiusMiles = radiusMiles,
        running = true,
        seenFromMs = t,
    )

    /**
     * [running], seen over at [t]: the server's span of that bubble gives its start (never after this
     * phone saw it) and its end; else the start as it stood, and its deadline if passed, else now.
     */
    private fun closed(running: Span, serverWord: List<Span>, t: Long): Span {
        val sawAt = running.seenFromMs ?: running.fromMs
        val span = serverWord.lastOrNull {
            it.radiusMiles == running.radiusMiles &&
                it.fromMs <= sawAt + PauseIntervals.SKEW_MS && (it.toMs == null || it.toMs >= sawAt - PauseIntervals.SKEW_MS)
        }
        return Span(
            fromMs = span?.let { minOf(it.fromMs, sawAt) } ?: running.fromMs,
            toMs = span?.toMs?.takeIf { it <= t } ?: running.toMs?.takeIf { it <= t } ?: t,
            radiusMiles = running.radiusMiles,
            seenFromMs = running.seenFromMs,
            seenToMs = t,
        )
    }

    /**
     * The widest bubble of [circleId] in force at [capturedAtMs] that this phone knows of, within
     * [PauseIntervals.SKEW_MS] of its ends, in miles; null when none was. [StoreUnreadable] while the
     * record can't be read.
     */
    @Synchronized
    fun radiusAt(circleId: String, capturedAtMs: Long): Int? {
        val key = account.key(circleId) ?: return null
        val state = read(key)
        return (state.seen + state.server).filter { s ->
            capturedAtMs >= s.fromMs - PauseIntervals.SKEW_MS && (s.toMs == null || capturedAtMs < s.toMs + PauseIntervals.SKEW_MS)
        }.maxOfOrNull { it.radiusMiles }
    }

    /**
     * The bubble of [circleId] this phone itself held at [atMs], in miles, null for none: the one it
     * had seen on by then and not yet seen over, so its own privacy row then. Only from when this phone
     * stored those rows, never a start or end the server dates back, nor the server's spans: a bubble
     * set elsewhere that this phone didn't know of was never what it sealed in.
     * [StoreUnreadable] while the record can't be read.
     */
    @Synchronized
    fun heldAt(circleId: String, atMs: Long): Int? {
        val key = account.key(circleId) ?: return null
        return read(key).seen.filter { s ->
            s.seenFromMs != null && s.seenFromMs <= atMs && (s.seenToMs == null || atMs < s.seenToMs)
        }.maxOfOrNull { it.radiusMiles }
    }

    /**
     * Whether the server's bubbles of [circleId] came in at least [PauseIntervals.SKEW_MS] after
     * [capturedAtMs], in this process: only then is the point's history complete. A restart waits for
     * the next one.
     */
    @Synchronized
    fun knowsSince(circleId: String, capturedAtMs: Long): Boolean {
        val key = account.key(circleId) ?: return false
        val at = serverWordAt[key] ?: return false
        return at >= capturedAtMs + PauseIntervals.SKEW_MS
    }

    private fun read(key: String): State {
        cache[key]?.let { return it }
        val p = prefs
        val state = if (p == null) {
            State(emptyList(), emptyList(), null)
        } else {
            StoreUnreadable.reading(STORE) {
                State(
                    seen = p.getStringSet("$key$SEEN", null).orEmpty().mapNotNull(::span).sortedBy { it.fromMs },
                    server = p.getStringSet("$key$SERVER", null).orEmpty().mapNotNull(::span).sortedBy { it.fromMs },
                    lastExactMs = p.getString("$key$EXACT", null)?.toLongOrNull(),
                )
            }
        }
        cache[key] = state
        return state
    }

    /** Kept for this process at once, and on disk when that lands: it reads back at the next start. */
    private fun write(key: String, state: State) {
        cache[key] = state
        val p = prefs ?: return
        val landed = runCatching {
            val editor = p.edit()
                .putStringSet("$key$SEEN", state.seen.map(::entry).toSet())
                .putStringSet("$key$SERVER", state.server.map(::entry).toSet())
            if (state.lastExactMs != null) editor.putString("$key$EXACT", state.lastExactMs.toString()) else editor.remove("$key$EXACT")
            editor.commit()
        }.getOrDefault(false)
        if (landed) cache[key] = state.copy(storedExactMs = state.lastExactMs)
        else Log.w(TAG, "A bubble record didn't reach the disk; it holds for this run")
    }

    @Synchronized
    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        cache.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        serverWordAt.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        val p = prefs ?: return
        AccountEntries.keepOnly({ p }, owner, STORE, mayDelete)
    }

    @Synchronized
    override fun wipeAll() {
        cache.clear()
        serverWordAt.clear()
        if (prefsProvider == null) return
        val c = context
        if (c != null) {
            AccountEntries.wipe(c, { prefs ?: NOTHING_KEPT }, FILE_NAME)
        } else {
            runCatching { prefs?.edit()?.clear()?.commit() }
        }
    }

    companion object {
        private const val TAG = "ApproximateIntervals"
        private const val STORE = "bubble intervals"
        private const val FILE_NAME = "approximate_intervals"
        private const val SEEN = "#seen"
        private const val SERVER = "#server"
        private const val EXACT = "#exact"

        /** How far "seen without a bubble" may move on before it goes to disk again. */
        private const val EXACT_STEP_MS = 30 * 60 * 1000L

        /** For this process only: nothing is written. */
        fun inMemory(owner: () -> String? = { "me" }, now: () -> Long = System::currentTimeMillis) =
            ApproximateIntervals(owner, null, now)

        /** Over [prefs], as a test's restart reads it back. */
        internal fun over(prefs: SharedPreferences, owner: () -> String?, now: () -> Long) =
            ApproximateIntervals(owner, { prefs }, now)

        private fun entry(s: Span) =
            "${s.fromMs}\t${s.toMs ?: ""}\t${s.radiusMiles}\t${if (s.running) "1" else ""}\t${s.seenFromMs ?: ""}\t${s.seenToMs ?: ""}"

        private fun span(entry: String): Span? {
            val f = entry.split('\t')
            val from = f.getOrNull(0)?.toLongOrNull() ?: return null
            val radius = f.getOrNull(2)?.toIntOrNull() ?: return null
            return Span(from, f.getOrNull(1)?.toLongOrNull(), radius, f.getOrNull(3) == "1", f.getOrNull(4)?.toLongOrNull(), f.getOrNull(5)?.toLongOrNull())
        }

        /** The file of a record kept for this process only: holds nothing. */
        private val NOTHING_KEPT: SharedPreferences = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
            override fun getString(key: String?, defValue: String?) = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
            override fun getInt(key: String?, defValue: Int) = defValue
            override fun getLong(key: String?, defValue: Long) = defValue
            override fun getFloat(key: String?, defValue: Float) = defValue
            override fun getBoolean(key: String?, defValue: Boolean) = defValue
            override fun contains(key: String?) = false
            override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException("kept for this process only")
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        }
    }
}
