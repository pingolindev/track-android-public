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
 * When this account didn't share with each circle (paused it, or turned real-time sharing off), as
 * far as this phone knows: "pauses" below. A point is never sent to a circle if it was captured
 * inside one of that circle's pauses, however late it goes out: a point kept or queued here, a
 * liveness re-send, a check-in synced later. The send gates read the posture NOW, and ingest can
 * drop only by ARRIVAL (it can't read a capture time inside an envelope), so a point taken during a
 * pause and sent after it would otherwise reach the circle.
 *
 * Two sources, read as one ([covers]):
 *  - what the server says of this user's spans of not sharing with the circle ([observe]'s
 *    `server`, the privacy row's `not_sharing_intervals`): a pause set AND ended on another phone
 *    while this one was offline is known only from there. Each answer that carries it replaces
 *    the last, and a row that says sharing ends any still open as of then;
 *  - what this phone saw itself, at every privacy re-sync and `privacy_check` wake. A pause
 *    began when the server says it did, from any answer, though never after this phone first saw
 *    it; with no word from the server, when this phone last saw the circle sharing, the earliest
 *    it can have. One seen over ended when the server says, else at the deadline it had if that
 *    has passed, else now.
 * A pause's ends are the server's clock and a capture time is this phone's: a point within
 * [SKEW_MS] of either end counts as inside. A pause this phone ended itself ([resumedHere]) has no
 * margin for what it takes after that: its own clock both ended it and took the point.
 *
 * The record knows a pause only once a privacy row from after it came in: a point waiting here
 * goes only when [syncedSince] its capture too. A phone back online would otherwise drain its queue
 * before its first re-sync, and send what it took during a pause set and ended elsewhere meanwhile.
 *
 * Kept per account, under its canonical id, for [RETENTION_MS] after a pause ended: longer than
 * any late point waits here (kept points 48 h, the queue 24 h) and than ingest's 48 h history
 * window. Another account's go at its sign-in, as its queued points do; an erasure takes them all;
 * no backup or restore reads or writes them. A record that can't be read is never "no pause":
 * [covers] throws [StoreUnreadable], and the senders hold what's late.
 */
@Singleton
class PauseIntervals internal constructor(
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
     * A pause from [fromMs] to [toMs] (null: no end known). [running]: still on when last seen here.
     * [seenFromMs]: when this phone itself first saw it, the latest its start can be; null for the server's.
     */
    data class Interval(val fromMs: Long, val toMs: Long?, val running: Boolean = false, val seenFromMs: Long? = null)

    /**
     * A circle's record: what this phone saw, the server's latest word, and when it last saw the circle
     * shared with ([lastUnpausedMs]; [storedUnpausedMs] is that time as the disk holds it).
     */
    private data class State(
        val seen: List<Interval>,
        val server: List<Interval>,
        val lastUnpausedMs: Long?,
        val storedUnpausedMs: Long? = lastUnpausedMs,
    )

    private val prefs: SharedPreferences? by lazy { prefsProvider?.invoke() }

    private val account = AccountEntries.Owner(STORE, { prefs ?: NOTHING_KEPT }, ownerUserId)

    /** Every record read or written in this process, by key. */
    private val cache = HashMap<String, State>()

    /** When each record last took a privacy row, in this process only: a restart waits for the next one. */
    private val syncedAt = HashMap<String, Long>()

    /** When this phone itself last ended each circle's pause ([resumedHere]), in this process only. */
    private val resumedHereAt = HashMap<String, Long>()

    /**
     * What a privacy row of [circleId] said just now: [notSharing] (paused until [deadlineMs], or with
     * no end known when null) or sharing, and [server], its `not_sharing_intervals` when it carried
     * them. Never throws: a record that can't be read now is left as it is.
     */
    @Synchronized
    fun observe(circleId: String, notSharing: Boolean, deadlineMs: Long?, server: List<Interval>? = null) {
        val key = account.key(circleId) ?: return
        val state = try {
            read(key)
        } catch (e: StoreUnreadable) {
            Log.w(TAG, "${e.message}: a pause of this circle isn't recorded now")
            return
        }
        val t = now()
        syncedAt[key] = t
        // A row that says sharing ends what is still open, as of now: a word kept from before a
        // resume, or a row with none in it, never holds a point taken after it back.
        val serverWord = (server ?: state.server).let { spans ->
            if (notSharing) spans else spans.map { if (it.toMs == null || it.toMs > t) it.copy(toMs = t) else it }
        }
        val running = state.seen.lastOrNull { it.running }
        val seen = when {
            notSharing && running == null -> {
                // The server's start of the pause running now (its span with no end, or one still
                // to come), never after now; else the last time this phone saw none.
                val from = serverWord.lastOrNull { it.toMs == null || it.toMs > t }?.fromMs?.let { minOf(it, t) }
                    ?: state.lastUnpausedMs ?: (t - UNKNOWN_START_MS)
                state.seen + Interval(from, deadlineMs, running = true, seenFromMs = t)
            }
            notSharing -> state.seen.map { seen ->
                if (seen !== running) return@map seen
                // A start guessed before the server's word came gives way to it.
                val serverFrom = serverWord.lastOrNull { it.toMs == null || it.toMs > t }?.fromMs
                seen.copy(fromMs = serverFrom?.let { minOf(it, seen.seenFromMs ?: seen.fromMs) } ?: seen.fromMs, toMs = deadlineMs)
            }
            running != null -> {
                // The server's span of the pause this phone saw: its start (never after this phone saw
                // it) and its end; else the start as it stood, and its deadline if passed, else now.
                val sawAt = running.seenFromMs ?: running.fromMs
                val span = serverWord.lastOrNull { it.fromMs <= sawAt + SKEW_MS && (it.toMs == null || it.toMs >= sawAt - SKEW_MS) }
                val from = span?.let { minOf(it.fromMs, sawAt) } ?: running.fromMs
                val to = span?.toMs?.takeIf { it <= t } ?: running.toMs?.takeIf { it <= t } ?: t
                state.seen.map { if (it === running) Interval(from, to, seenFromMs = it.seenFromMs) else it }
            }
            else -> state.seen
        }
        fun kept(i: Interval) = i.running || i.toMs == null || i.toMs >= t - RETENTION_MS
        val next = state.copy(
            seen = seen.filter(::kept),
            server = serverWord.filter(::kept),
            lastUnpausedMs = if (notSharing) state.lastUnpausedMs else t,
        )
        // "Seen shared with" moves at every re-sync: it goes to disk only with a change of the spans,
        // or once it has moved on [UNPAUSED_STEP_MS]. A restart in between reads an older time, which
        // only makes the next pause's start earlier.
        val stepped = (next.lastUnpausedMs ?: 0) - (state.storedUnpausedMs ?: 0) >= UNPAUSED_STEP_MS
        when {
            next.seen != state.seen || next.server != state.server || stepped -> write(key, next)
            next != state -> cache[key] = next
        }
    }

    /**
     * Whether [capturedAtMs] falls inside a pause of [circleId] this phone knows of, within
     * [SKEW_MS] of its ends. [StoreUnreadable] while the record can't be read.
     */
    @Synchronized
    fun covers(circleId: String, capturedAtMs: Long): Boolean {
        val key = account.key(circleId) ?: return false
        val state = read(key)
        // Taken after this phone itself ended the pause: the spans that ended by then, with
        // their margin, are behind it. One that begins after keeps its margin.
        val endedHere = resumedHereAt[key]?.takeIf { capturedAtMs >= it }
        return (state.seen + state.server).any { i ->
            if (endedHere != null && i.fromMs < endedHere && i.toMs != null && i.toMs <= endedHere + SKEW_MS) return@any false
            capturedAtMs >= i.fromMs - SKEW_MS && (i.toMs == null || capturedAtMs < i.toMs + SKEW_MS)
        }
    }

    /**
     * This phone just ended a pause of [circleId] itself, or turned real-time sharing back on (as
     * the iOS app does): a point it takes from now on is outside the spans that ended by now, with
     * no margin, so the first one after a resume made here goes at once. Before the row that says so
     * is stored. The margin stays for an end seen from elsewhere, and for a span that begins after.
     */
    @Synchronized
    fun resumedHere(circleId: String) {
        val key = account.key(circleId) ?: return
        resumedHereAt[key] = now()
    }

    /**
     * Whether a privacy row of [circleId] came in at least [SKEW_MS] after [capturedAtMs], in this
     * process: only then does the record know every pause the point could be inside, one set and
     * ended on another phone while this one was offline included.
     */
    @Synchronized
    fun syncedSince(circleId: String, capturedAtMs: Long): Boolean {
        val key = account.key(circleId) ?: return false
        val at = syncedAt[key] ?: return false
        return at >= capturedAtMs + SKEW_MS
    }

    private fun read(key: String): State {
        cache[key]?.let { return it }
        val p = prefs
        val state = if (p == null) {
            State(emptyList(), emptyList(), null)
        } else {
            StoreUnreadable.reading(STORE) {
                State(
                    seen = p.getStringSet("$key$SEEN", null).orEmpty().mapNotNull(::interval).sortedBy { it.fromMs },
                    server = p.getStringSet("$key$SERVER", null).orEmpty().mapNotNull(::interval).sortedBy { it.fromMs },
                    lastUnpausedMs = p.getString("$key$UNPAUSED", null)?.toLongOrNull(),
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
            if (state.lastUnpausedMs != null) editor.putString("$key$UNPAUSED", state.lastUnpausedMs.toString()) else editor.remove("$key$UNPAUSED")
            editor.commit()
        }.getOrDefault(false)
        if (landed) cache[key] = state.copy(storedUnpausedMs = state.lastUnpausedMs)
        else Log.w(TAG, "A pause record didn't reach the disk; it holds for this run")
    }

    @Synchronized
    override fun keepOnly(owner: String, mayDelete: (String) -> Boolean) {
        cache.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        syncedAt.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        resumedHereAt.keys.removeAll { !AccountEntries.isOwners(it, owner) && mayDelete(AccountEntries.ownerOf(it)) }
        val p = prefs ?: return
        AccountEntries.keepOnly({ p }, owner, STORE, mayDelete)
    }

    @Synchronized
    override fun wipeAll() {
        cache.clear()
        syncedAt.clear()
        resumedHereAt.clear()
        if (prefsProvider == null) return
        val c = context
        if (c != null) {
            AccountEntries.wipe(c, { prefs ?: NOTHING_KEPT }, FILE_NAME)
        } else {
            runCatching { prefs?.edit()?.clear()?.commit() }
        }
    }

    companion object {
        private const val TAG = "PauseIntervals"
        private const val STORE = "pause intervals"
        private const val FILE_NAME = "pause_intervals"
        private const val SEEN = "#seen"
        private const val SERVER = "#server"
        private const val UNPAUSED = "#unpaused"

        /** Kept this long after a pause ended: past every late point's wait here, and ingest's 48 h window. */
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** How far "seen shared with" may move on before it goes to disk again. */
        private const val UNPAUSED_STEP_MS = 30 * 60 * 1000L

        /** Seen running, with no word of when it wasn't: taken to have begun this long ago, the oldest a late point can be. */
        const val UNKNOWN_START_MS = 48 * 60 * 60 * 1000L

        /**
         * A point this close to a pause's start or end counts as inside it: the server's clock, this
         * phone's and a fix's own time against each other. A phone that sets its time itself is within
         * seconds; the cost is a minute of points after a pause that this circle doesn't get.
         */
        const val SKEW_MS = 60 * 1000L

        /** For this process only: nothing is written. */
        fun inMemory(owner: () -> String? = { "me" }, now: () -> Long = System::currentTimeMillis) =
            PauseIntervals(owner, null, now)

        /** Over [prefs], as a test's restart reads it back. */
        internal fun over(prefs: SharedPreferences, owner: () -> String?, now: () -> Long) =
            PauseIntervals(owner, { prefs }, now)

        private fun entry(i: Interval) = "${i.fromMs}\t${i.toMs ?: ""}\t${if (i.running) "1" else ""}\t${i.seenFromMs ?: ""}"

        private fun interval(entry: String): Interval? {
            val f = entry.split('\t')
            val from = f.getOrNull(0)?.toLongOrNull() ?: return null
            return Interval(from, f.getOrNull(1)?.toLongOrNull(), f.getOrNull(2) == "1", f.getOrNull(3)?.toLongOrNull())
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
