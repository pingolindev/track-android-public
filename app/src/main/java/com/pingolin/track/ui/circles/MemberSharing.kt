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

package com.pingolin.track.ui.circles

import android.content.Context
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleMemberEntity
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Whether a member is sharing their location with a circle, as the circle sees it, so others
 * know why the member's location is not updating.
 *
 * The server derives it from the member's privacy row for the circle and sends it on the members
 * list (`location_sharing`): real-time sharing switched off is [Off], and it wins over a pause;
 * a pause is [Paused] until it ends; anything else is [On], an older server's silence included.
 */
sealed interface SharingStatus {
    data object On : SharingStatus
    data object Off : SharingStatus
    /** [untilMs] null: paused until they resume. */
    data class Paused(val untilMs: Long?) : SharingStatus
}

/** The rule and the words for [SharingStatus]. Web and iPhone say the same sentences. */
object MemberSharing {
    const val OFF = "off"
    const val PAUSED = "paused"
    const val APPROXIMATE = "approximate"

    /** [member]'s state at [nowMs]. A timed pause whose end has passed is [SharingStatus.On], as the server reads it. */
    fun status(member: CircleMemberEntity, nowMs: Long): SharingStatus =
        status(member.locationSharing, member.sharingPausedUntil, nowMs)

    fun status(locationSharing: String?, pausedUntilMs: Long?, nowMs: Long): SharingStatus = when (locationSharing) {
        OFF -> SharingStatus.Off
        PAUSED -> if (pausedUntilMs != null && pausedUntilMs <= nowMs) SharingStatus.On else SharingStatus.Paused(pausedUntilMs)
        else -> SharingStatus.On
    }

    /** This account's own state, from its privacy settings for the circle: the server's rule. */
    fun own(shareRealtime: Boolean, sharingMode: String?, pausedUntilMs: Long?, nowMs: Long): SharingStatus = when {
        !shareRealtime -> SharingStatus.Off
        sharingMode == PAUSED && (pausedUntilMs == null || pausedUntilMs > nowMs) -> SharingStatus.Paused(pausedUntilMs)
        else -> SharingStatus.On
    }

    /**
     * What a members-list entry is kept as: "off", "paused" or null (sharing), and a timed
     * pause's end. An unknown value is sharing, so a newer server can't hide anyone here.
     */
    fun fromWire(locationSharing: String?, sharingPausedUntil: String?): Pair<String?, Long?> = when (locationSharing) {
        OFF -> OFF to null
        PAUSED -> PAUSED to epochMs(sharingPausedUntil)
        else -> null to null
    }

    /**
     * What a members-list entry's `history_sharing` is kept as: "off", or null for showing
     * it. An unknown value, or an older server's silence, shows it.
     */
    fun historyFromWire(historySharing: String?): String? = OFF.takeIf { historySharing == OFF }

    /**
     * Whether [member] keeps their location history from the circle: no viewer shows a past
     * position of theirs, only where they are now, until they show it again.
     */
    fun historyOff(member: CircleMemberEntity): Boolean = member.historySharing == OFF

    /** "{name} doesn't share their location history in this circle.": their card, and the history view. */
    fun historyOffSentence(context: Context, name: String): String =
        context.getString(R.string.member_history_off_detail, name)

    /**
     * A pause's end as the server writes it, in epoch ms; null if absent or unreadable. The server
     * writes RFC 3339 with a numeric offset ("…T17:00:00+00:00", or ".123456+00:00" with
     * fractions). Not `Instant.parse`: before Android 14 it reads only "Z" (JDK-8166138) and every
     * end is lost.
     */
    fun epochMs(rfc3339: String?): Long? =
        rfc3339?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * Whether the map leaves the member's live marker out. Yes while "off" or "paused": the label
     * says why. Someone with real-time sharing off is drawn instead where they were when they
     * turned it off, greyed and labelled ([frozenOnMap]: with real time off, the user's last
     * location is the one shown); a pause shows nothing.
     */
    fun hidesMarker(status: SharingStatus): Boolean = status != SharingStatus.On

    /**
     * The (circle, member) pairs whose markers the map leaves out. By circle as well as member:
     * someone paused in one circle is still on the map of another. Never [selfId]: this phone
     * knows where it is.
     */
    fun hiddenOnMap(members: List<CircleMemberEntity>, selfId: String?, nowMs: Long): Set<Pair<String, String>> =
        members.filter { it.userId != selfId && hidesMarker(status(it, nowMs)) }.mapTo(mutableSetOf()) { it.circleId to it.userId }

    /**
     * The frozen rows the map draws: of [frozen], the server's `last_known` rows, those of members
     * whose list entry in that same circle says real-time sharing is off now. Never one of another
     * circle's: someone off in one circle may be sharing live in the one on screen. Never someone
     * paused (a pause shows nothing), nor sharing again (their live row is theirs), nor [selfId].
     */
    fun frozenOnMap(
        members: List<CircleMemberEntity>,
        frozen: List<com.pingolin.track.data.local.entity.LocationEntity>,
        selfId: String?,
        nowMs: Long,
    ): List<com.pingolin.track.data.local.entity.LocationEntity> = frozen.filter { row ->
        row.userId != selfId && !com.pingolin.track.util.AccountIds.same(row.userId, selfId) &&
            members.any { m ->
                m.circleId == row.circleId &&
                    (m.userId == row.userId || com.pingolin.track.util.AccountIds.same(m.userId, row.userId)) &&
                    status(m, nowMs) == SharingStatus.Off
            }
    }

    /** Whether [aMs] and [bMs] fall on the same calendar day in [zone]. */
    fun sameDay(aMs: Long, bMs: Long, zone: TimeZone): Boolean {
        val a = Calendar.getInstance(zone).apply { timeInMillis = aMs }
        val b = Calendar.getInstance(zone).apply { timeInMillis = bMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * A pause's end: "5:00 PM" today, "Tue 5:00 PM" another day, in the device's locale and its
     * 12/24-hour setting.
     */
    fun until(context: Context, untilMs: Long, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val locale = Locale.getDefault()
        val hours = if (android.text.format.DateFormat.is24HourFormat(context)) "Hmm" else "hmm"
        val skeleton = if (sameDay(untilMs, nowMs, zone)) hours else "EEE$hours"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(Date(untilMs))
    }

    /** The line under another member's name: "Location sharing off", "Paused until …", "Paused"; null while sharing. */
    fun rowLabel(context: Context, status: SharingStatus, nowMs: Long): String? = when (status) {
        SharingStatus.On -> null
        SharingStatus.Off -> context.getString(R.string.member_sharing_off)
        is SharingStatus.Paused -> status.untilMs
            ?.let { context.getString(R.string.member_sharing_paused_until, until(context, it, nowMs)) }
            ?: context.getString(R.string.member_sharing_paused)
    }

    /** The sentence on another member's card; null while sharing. */
    fun detailSentence(context: Context, name: String, status: SharingStatus, nowMs: Long): String? = when (status) {
        SharingStatus.On -> null
        SharingStatus.Off -> context.getString(R.string.member_sharing_off_detail, name)
        is SharingStatus.Paused -> status.untilMs
            ?.let { context.getString(R.string.member_sharing_paused_until_detail, name, until(context, it, nowMs)) }
            ?: context.getString(R.string.member_sharing_paused_detail, name)
    }

    /**
     * The same, with this account's location history in the circle: with real-time sharing off as well,
     * one sentence for both; history off alone, its own; else as the other [ownSentence]. A pause is said
     * as a pause.
     */
    fun ownSentence(context: Context, status: SharingStatus, shareHistory: Boolean, nowMs: Long): String? = when {
        shareHistory -> ownSentence(context, status, nowMs)
        status == SharingStatus.Off -> context.getString(R.string.own_sharing_and_history_off)
        status == SharingStatus.On -> context.getString(R.string.own_history_off)
        else -> ownSentence(context, status, nowMs)
    }

    /** What this account is told about its own sharing in a circle, and what the others see; null while sharing. */
    fun ownSentence(context: Context, status: SharingStatus, nowMs: Long): String? = when (status) {
        SharingStatus.On -> null
        SharingStatus.Off -> context.getString(R.string.own_sharing_off)
        is SharingStatus.Paused -> status.untilMs
            ?.let { context.getString(R.string.own_sharing_paused_until, until(context, it, nowMs)) }
            ?: context.getString(R.string.own_sharing_paused)
    }

    /**
     * The line beside Resume in this account's privacy dialog, or null. The sentence at the top
     * ([ownSentence]) is the one statement of a pause; this line says it only when real-time
     * sharing is off as well, so that sentence says that instead.
     */
    fun pauseLine(context: Context, shareRealtime: Boolean, pausedUntilMs: Long?, nowMs: Long): String? {
        val pause = status(PAUSED, pausedUntilMs, nowMs)
        return if (!shareRealtime && pause is SharingStatus.Paused) rowLabel(context, pause, nowMs) else null
    }

    /**
     * Whether this account's approximate-location switch shows on. While paused it shows the mode
     * Resume goes back to (`mode_before_pause`), so a pause can't look as if it had turned the
     * bubble off.
     */
    fun bubbleOn(sharingMode: String?, modeBeforePause: String?): Boolean =
        sharingMode == APPROXIMATE || (sharingMode == PAUSED && modeBeforePause == APPROXIMATE)

    /**
     * Whether the approximate switch and its radius can be changed: not while paused. Either one
     * writes an explicit mode, which the server takes as the person's choice and which ends the
     * pause, so switching off a bubble shown for after the pause would share the exact position
     * at once.
     */
    fun bubbleEditable(sharingMode: String?): Boolean = sharingMode != PAUSED
}
