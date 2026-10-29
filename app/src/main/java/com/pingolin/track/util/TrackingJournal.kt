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

import android.content.SharedPreferences

/**
 * A durable, bounded record of why tracking started and stopped.
 *
 * WHY THIS EXISTS: the service logs its reason at each exit path (proguard
 * strips only `Log.d`/`Log.v`, so the `Log.i`/`Log.w` survive the release
 * build), but the device's logcat ring rotates past it within the hour,
 * long before anyone looks. The explanation exists and then evaporates.
 *
 * So the answer is not "log more". It is to keep the last handful of
 * transitions somewhere that outlives a ring buffer, in a form a human can
 * read off the phone. Fifty entries is enough for weeks of ordinary use
 * (a start and a stop per day) and is small enough to sit in prefs.
 *
 * Deliberately an enum reason, not a free-text string: the point is to be able
 * to compare "what happened this time" with "what happened last time", and
 * free text drifts with every edit to the log line.
 */
enum class TrackingEvent {
    /** Tracking began delivering. */
    STARTED,

    /** Tracking ended. Always paired with a [TrackingStopReason]. */
    STOPPED,

    /** The watchdog found tracking down and asked for a restart. */
    RESTART_ATTEMPTED,

    /** A restart was asked for and the next check found tracking still down. */
    RESTART_FAILED,

    /** The service is alive but no fix has arrived in far too long. */
    BLIND_SUSPECTED,

    /** Automatic recovery gave up and the user was asked to intervene. */
    USER_NOTIFIED,

    /**
     * The app cleared its own session. The single most valuable entry here:
     * once the client thinks it is signed out, BootReceiver and the watchdog
     * both refuse to start tracking, so every automatic recovery route becomes
     * a deliberate no-op and only the user can restore it.
     */
    SIGNED_OUT,
}

enum class TrackingStopReason {
    /** Not a stop event. */
    NONE,

    /** The user turned tracking off, or something asked the service to stop. */
    USER_ACTION,

    /** Tracking is paused; a start request was refused because of it. */
    PAUSED,

    /** No session, so there is nobody to report for. */
    NOT_LOGGED_IN,

    /** The runtime location permission is not held. */
    NO_LOCATION_PERMISSION,

    /**
     * Only approximate location is granted. Refused on purpose: every fix
     * would be discarded by the accuracy filter, so starting would show a
     * notification claiming tracking the user is not getting.
     */
    COARSE_LOCATION_ONLY,

    /**
     * `startForeground` was refused — typically
     * ForegroundServiceStartNotAllowedException or a SecurityException from a
     * background start on Android 12+.
     */
    FOREGROUND_START_REFUSED,

    /** The service object was destroyed (system reclaim, app update, kill). */
    SERVICE_DESTROYED,

    /** The user chose to sign out. */
    USER_SIGNED_OUT,

    /**
     * The app signed itself out because the server rejected a token refresh
     * with 401. Note this is decided on ONE 401 with no retry, and the server
     * session row can still be live afterwards.
     */
    SESSION_REJECTED,

    /** A refresh reported success but left no token behind. */
    SESSION_NO_TOKEN,

    /** The stored keys don't decode: nothing works until a sign-in replaces them. */
    KEY_STORE_UNREADABLE,

    /** The saved account id isn't a UUID: nothing is kept under it, and a sign-in saves a canonical one (AccountIds). */
    ACCOUNT_ID_INVALID,

    /** Stopped without any branch claiming it. Should stay rare; if it does not, a path is missing. */
    UNKNOWN,
}

data class TrackingJournalEntry(
    /** Wall clock, for showing the user. Moves if the clock is set. */
    val wallMs: Long,
    /** Monotonic since boot, so ordering survives a clock change. */
    val elapsedMs: Long,
    val event: TrackingEvent,
    val reason: TrackingStopReason,
    /** Age of the last fix when this happened, or [TrackingHealth.NO_FIX]. */
    val lastFixAgeMs: Long,
    /**
     * A short, bounded note for the cases an enum cannot carry: which call
     * site signed out, the HTTP status the server answered with, whether a
     * refresh was attempted. A sign-out can't be explained without the status
     * code. Sanitised by [TrackingJournalCodec].
     */
    val detail: String = "",
)

/**
 * Encoding kept pure and separate from storage so the ring behaviour and the
 * format's forward-compatibility can be unit tested without Android.
 *
 * One line per entry, `|`-separated. Every field is a number or an enum name,
 * so nothing needs escaping and a truncated or foreign line can simply be
 * dropped rather than corrupting the rest.
 */
object TrackingJournalCodec {

    const val MAX_ENTRIES = 50
    private const val SEP = '|'

    /** Detail is free text, so it is the one field that must be made safe. */
    const val MAX_DETAIL_CHARS = 120

    fun sanitiseDetail(detail: String): String =
        detail.replace('|', '/')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(MAX_DETAIL_CHARS)

    fun encode(entry: TrackingJournalEntry): String =
        listOf(
            entry.wallMs.toString(),
            entry.elapsedMs.toString(),
            entry.event.name,
            entry.reason.name,
            entry.lastFixAgeMs.toString(),
            sanitiseDetail(entry.detail),
        ).joinToString(SEP.toString())

    /** Returns null for anything unparseable, including entries written by a newer
     * version.
     */
    fun decode(line: String): TrackingJournalEntry? {
        val parts = line.split(SEP)
        if (parts.size < 5) return null
        val wall = parts[0].toLongOrNull() ?: return null
        val elapsed = parts[1].toLongOrNull() ?: return null
        val event = TrackingEvent.entries.firstOrNull { it.name == parts[2] } ?: return null
        val reason = TrackingStopReason.entries.firstOrNull { it.name == parts[3] } ?: return null
        val age = parts[4].toLongOrNull() ?: return null
        // Field 6 is optional: entries written by earlier versions
        // lack it and are still perfectly readable, so its absence is
        // not a parse failure.
        val detail = if (parts.size >= 6) parts[5] else ""
        return TrackingJournalEntry(wall, elapsed, event, reason, age, detail)
    }

    fun encodeAll(entries: List<TrackingJournalEntry>): String =
        entries.joinToString("\n") { encode(it) }

    fun decodeAll(raw: String?): List<TrackingJournalEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { decode(it) }
            .toList()
    }

    /**
     * Append, keeping only the newest [max]. Oldest-first ordering, so the
     * newest entry is always last and a reader can take the tail.
     */
    fun append(
        existing: List<TrackingJournalEntry>,
        entry: TrackingJournalEntry,
        max: Int = MAX_ENTRIES,
    ): List<TrackingJournalEntry> {
        if (max <= 0) return emptyList()
        val combined = existing + entry
        return if (combined.size <= max) combined else combined.takeLast(max)
    }
}

/**
 * Renders the journal for a human. Pure so the wording can be tested; the
 * whole point of the journal is that someone can read the answer off the
 * phone, so the rendering is part of the deliverable, not decoration.
 */
object TrackingJournalFormatter {

    /** Newest first — the last thing that happened is the thing being asked about. */
    fun format(
        entries: List<TrackingJournalEntry>,
        nowMs: Long = System.currentTimeMillis(),
        formatWall: (Long) -> String,
    ): String {
        if (entries.isEmpty()) return ""
        return entries.asReversed().joinToString("\n") { e ->
            val age = relativeAge(nowMs - e.wallMs)
            val what = describe(e)
            "${formatWall(e.wallMs)}  ($age)\n    $what"
        }
    }

    fun describe(entry: TrackingJournalEntry): String {
        val head = when (entry.event) {
            TrackingEvent.STARTED -> "Tracking started"
            TrackingEvent.STOPPED -> "Tracking stopped — ${reasonText(entry.reason)}"
            TrackingEvent.RESTART_ATTEMPTED -> "Automatic restart attempted"
            TrackingEvent.RESTART_FAILED -> "Automatic restart did not work"
            TrackingEvent.BLIND_SUSPECTED -> "Running but no recent location"
            TrackingEvent.USER_NOTIFIED -> "Asked you to reopen the app"
            TrackingEvent.SIGNED_OUT -> "Signed out — ${reasonText(entry.reason)}"
        }
        // No trailing "earlier": relativeAge already reads as a time ("3m ago",
        // "just now"), and appending to it would read "last location just now
        // earlier".
        val fix = when {
            entry.lastFixAgeMs == TrackingHealth.NO_FIX -> "no location yet"
            else -> "last location ${relativeAge(entry.lastFixAgeMs)}"
        }
        val note = if (entry.detail.isBlank()) "" else " (${entry.detail})"
        return "$head; $fix$note"
    }

    fun reasonText(reason: TrackingStopReason): String = when (reason) {
        TrackingStopReason.NONE -> "no reason recorded"
        TrackingStopReason.USER_ACTION -> "turned off"
        TrackingStopReason.PAUSED -> "tracking is paused"
        TrackingStopReason.NOT_LOGGED_IN -> "signed out"
        TrackingStopReason.NO_LOCATION_PERMISSION -> "location permission not granted"
        TrackingStopReason.COARSE_LOCATION_ONLY -> "only approximate location allowed"
        TrackingStopReason.FOREGROUND_START_REFUSED -> "Android refused to start it"
        TrackingStopReason.SERVICE_DESTROYED -> "Android shut it down"
        TrackingStopReason.USER_SIGNED_OUT -> "you signed out"
        TrackingStopReason.SESSION_REJECTED -> "the server rejected this device's session"
        TrackingStopReason.SESSION_NO_TOKEN -> "the sign-in token went missing"
        TrackingStopReason.KEY_STORE_UNREADABLE -> "sync error"
        TrackingStopReason.ACCOUNT_ID_INVALID -> "sync error"
        TrackingStopReason.UNKNOWN -> "reason unknown"
    }

    /** Coarse on purpose: the question is "roughly when", not "to the second". */
    fun relativeAge(ageMs: Long): String {
        if (ageMs < 0) return "just now"
        val minutes = ageMs / 60_000L
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ${minutes % 60}m ago"
            else -> "${minutes / (60 * 24)}d ${(minutes % (60 * 24)) / 60}h ago"
        }
    }
}

/**
 * Prefs-backed store. Thin on purpose — all the behaviour worth testing is in
 * [TrackingJournalCodec].
 */
class TrackingJournal(private val prefs: SharedPreferences) {

    /**
     * Whether new entries are written at all. Off until someone turns it on:
     * a journal of every start, stop and sign-out is a record of when a person
     * was and was not being located, so it is collected on request rather than
     * by default.
     *
     * READING is deliberately not gated. Turning recording off does not erase
     * what was already recorded — someone who switches it off after reading an
     * incident still has the incident in front of them.
     */
    fun isRecordingEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setRecordingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Oldest first. */
    fun entries(): List<TrackingJournalEntry> =
        TrackingJournalCodec.decodeAll(prefs.getString(KEY, null))

    fun record(
        event: TrackingEvent,
        reason: TrackingStopReason = TrackingStopReason.NONE,
        lastFixAgeMs: Long = TrackingHealth.NO_FIX,
        detail: String = "",
        wallMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        // The gate lives HERE, not at the call sites. Four of them write to
        // this journal — the service, the watchdog, AuthRepository and
        // SessionManager's own sign-out — and a check any one of them can
        // forget is not a check.
        if (!isRecordingEnabled()) return
        val entry = TrackingJournalEntry(wallMs, elapsedMs, event, reason, lastFixAgeMs, detail)
        val updated = TrackingJournalCodec.append(entries(), entry)
        // commit(), not apply(): several of these are written on the way out of
        // a service that is about to be destroyed or a process about to be
        // frozen, and an async write is exactly the one that would be lost —
        // which is the failure this journal exists to stop.
        prefs.edit().putString(KEY, TrackingJournalCodec.encodeAll(updated)).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        private const val KEY = "tracking_journal_v1"
        private const val KEY_ENABLED = "tracking_journal_enabled"
    }
}
