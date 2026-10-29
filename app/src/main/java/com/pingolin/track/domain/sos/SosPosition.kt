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

import com.pingolin.track.data.local.entity.SosAlertEntity

/**
 * The two sealed "no position" texts, byte for byte: what an SOS's location field holds when the
 * raiser's phone has no position it may send, sealed under the circle's key with the "location" field
 * tag. Each is 22 bytes, the length of a packed position with accuracy, so a position and a marker
 * both seal to 38. Each begins with `{`, which no packed position does (those begin 0xB1), and the
 * SDK's location decode refuses it.
 */
object SosPositionMarker {
    /** No position yet: the raiser's phone is still getting one ("Sending current location"). */
    const val PENDING = """{"position":"pending"}"""

    /** A location can't be sent ("Location unavailable"). */
    const val UNAVAILABLE = """{"position":"unavail"}"""

    /** The marker [state] seals as: [SosAlertEntity.POSITION_PENDING] or [SosAlertEntity.POSITION_UNAVAILABLE]. */
    fun text(state: String): String = when (state) {
        SosAlertEntity.POSITION_PENDING -> PENDING
        SosAlertEntity.POSITION_UNAVAILABLE -> UNAVAILABLE
        else -> throw IllegalArgumentException("no such marker")
    }

    /** The state [text] says when it is exactly one marker; anything else is not a marker (null). */
    fun read(text: String): String? = when (text) {
        PENDING -> SosAlertEntity.POSITION_PENDING
        UNAVAILABLE -> SosAlertEntity.POSITION_UNAVAILABLE
        else -> null
    }
}

/**
 * What the raising phone seals as its own SOS's position, and keeps as its record of what it sealed:
 * a point, with when it was TAKEN and how close, or a marker. As a record, a point is what the
 * location codec carried back: lat/lon on its 1e-7 grid, the accuracy an f32 (SosRepository opens it
 * from the bytes it sealed), so it equals a served copy of itself exactly.
 */
sealed class SosSeal {
    /**
     * A position as sealed: [takenAt] is its capture time (the sealed `Location.timestamp`), never
     * the raise time; [accuracy] in metres, null only for a fix that had none.
     */
    data class Point(val latitude: Double, val longitude: Double, val takenAt: Long, val accuracy: Double?) : SosSeal()

    /** [SosAlertEntity.POSITION_PENDING] or [SosAlertEntity.POSITION_UNAVAILABLE]. */
    data class Marker(val state: String) : SosSeal()

    /** One line for the record store; [decode] reads it back. */
    fun encode(): String = when (this) {
        is Point -> "p\t$latitude\t$longitude\t$takenAt\t${accuracy ?: ""}"
        is Marker -> "m\t$state"
    }

    companion object {
        val PENDING = Marker(SosAlertEntity.POSITION_PENDING)
        val UNAVAILABLE = Marker(SosAlertEntity.POSITION_UNAVAILABLE)

        /** [encode]'s line, or null for one that doesn't read. */
        fun decode(line: String): SosSeal? {
            val parts = line.split('\t')
            return when (parts.firstOrNull()) {
                "p" -> {
                    if (parts.size != 5) return null
                    Point(
                        latitude = parts[1].toDoubleOrNull() ?: return null,
                        longitude = parts[2].toDoubleOrNull() ?: return null,
                        takenAt = parts[3].toLongOrNull() ?: return null,
                        accuracy = parts[4].takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return null },
                    )
                }
                "m" -> parts.getOrNull(1)
                    ?.takeIf { parts.size == 2 && (it == SosAlertEntity.POSITION_PENDING || it == SosAlertEntity.POSITION_UNAVAILABLE) }
                    ?.let { Marker(it) }
                else -> null
            }
        }
    }
}

/**
 * The rule for updating a raised SOS's position: what the raising phone re-seals into its own open
 * alert, given its record of what it sealed last ([current]). Pure: the run that applies it
 * ([com.pingolin.track.domain.repository.SosRepository]) reads the alert, the record and the bounds.
 */
object SosUpdateRule {
    /** Updates go within this long of the press (after a restart: of the sealed raise time). */
    const val WINDOW_MS = 10 * 60_000L

    /** At most this many re-seals by this rule: updates improve the position,
     * they don't follow the raiser.
     */
    const val MAX_UPDATES = 3

    /** A fix taken this long before the press still counts as after it (iOS's freshnessSlack). */
    const val AFTER_PRESS_SLACK_MS = 2_000L

    /** A position taken more than this before the press is old enough to replace
     * (case (b) of [next]).
     */
    const val OLD_BEFORE_PRESS_MS = 60_000L

    /** Within this, in metres, a position is good (case (c) of [next]); receivers draw an area
     * beyond it.
     */
    const val GOOD_ACCURACY_M = 100.0

    /** Whether [fix] was taken after the press at [pressAt]. */
    fun afterPress(fix: SosSeal.Point, pressAt: Long): Boolean = fix.takenAt >= pressAt - AFTER_PRESS_SLACK_MS

    private fun good(point: SosSeal.Point) = point.accuracy?.let { it <= GOOD_ACCURACY_M } == true

    /**
     * What to re-seal in place of [current], or null for nothing: [fix] is the newest exact fix this
     * phone holds (only one taken after the press counts), [cause] whether a definite cause keeps it
     * from getting one (no location permission, or Location Services off), [updates] how many re-seals
     * this rule made already.
     *  - (a) a marker, (b) a position taken more than a minute before the press, or (c) one worse than
     *    100 m (a coarsened held fix is: its accuracy is the bubble), with a fix after the press within
     *    100 m: that fix, when it is newer;
     *  - (d) "pending", and a definite cause: "unavail";
     *  - (e) "pending" when the window ends: "unavail". Only then, and nothing else after it.
     */
    fun next(current: SosSeal, fix: SosSeal.Point?, cause: Boolean, pressAt: Long, now: Long, updates: Int): SosSeal? {
        if (updates >= MAX_UPDATES) return null
        if (now >= pressAt + WINDOW_MS) return if (current == SosSeal.PENDING) SosSeal.UNAVAILABLE else null
        val f = fix?.takeIf { afterPress(it, pressAt) }
        if (f != null) {
            val due = when (current) {
                is SosSeal.Marker -> true
                is SosSeal.Point -> f.takenAt > current.takenAt && (
                    current.takenAt < pressAt - OLD_BEFORE_PRESS_MS || (!good(current) && good(f))
                    )
            }
            if (due) return f
        }
        return if (current == SosSeal.PENDING && cause) SosSeal.UNAVAILABLE else null
    }

    /**
     * Whether a later fix could still change [current] inside the window: the raising phone keeps
     * looking (one fix every 30 s) while this holds. A marker, an old position, or one that isn't good
     * yet can still be replaced; a good fix from after the press can't.
     */
    fun mayImprove(current: SosSeal, pressAt: Long, now: Long, updates: Int): Boolean {
        if (updates >= MAX_UPDATES || now >= pressAt + WINDOW_MS) return false
        return when (current) {
            is SosSeal.Marker -> true
            is SosSeal.Point -> current.takenAt < pressAt - OLD_BEFORE_PRESS_MS || !good(current)
        }
    }
}

/** What an SOS's position says to the people it asks. */
sealed class SosPosition {
    /** The raiser's phone is still getting a location: "Sending current location". No pin. */
    object Pending : SosPosition()

    /** A location can't be sent, or a pending one is past its window: "Location
     * unavailable". No pin.
     */
    object Unavailable : SosPosition()

    /** Taken when it was sent, or a row whose position has no capture time: no qualifier is shown. */
    object AsSent : SosPosition()

    /** Taken a while before it was sent: "Last known location · {time}". */
    data class LastKnown(val at: Long) : SosPosition()

    /** Taken after it was sent, by the raiser's phone: "Location updated · {time}". */
    data class Updated(val at: Long) : SosPosition()

    companion object {
        /** A position this close to the raise counts as taken when it was sent. */
        const val SAME_TIME_MS = 60_000L

        /**
         * A pending marker this long after the SEALED raise time says unavailable: the raiser's
         * phone looks for 10 minutes, and seals "unavail" itself at the end; two more cover one that
         * died or went offline before it could.
         */
        const val PENDING_GIVES_UP_AFTER_MS = SosUpdateRule.WINDOW_MS + 2 * 60_000L

        /** What [alert]'s position says at [now]; its [SosAlertEntity.createdAt] is the sealed raise time. */
        fun of(alert: SosAlertEntity, now: Long): SosPosition {
            val asked = alert.createdAt
            return when (alert.positionState) {
                SosAlertEntity.POSITION_UNAVAILABLE -> Unavailable
                SosAlertEntity.POSITION_PENDING -> if (now - asked >= PENDING_GIVES_UP_AFTER_MS) Unavailable else Pending
                else -> {
                    val fixAt = alert.fixAt
                    when {
                        alert.latitude == null || alert.longitude == null -> AsSent
                        fixAt == null -> AsSent
                        fixAt < asked - SAME_TIME_MS -> LastKnown(fixAt)
                        fixAt > asked + SAME_TIME_MS -> Updated(fixAt)
                        else -> AsSent
                    }
                }
            }
        }

        /**
         * The radius, in metres, of the area [alert]'s position is drawn as: its accuracy past 100
         * m, a coarsened held fix's included; null for a pin.
         */
        fun areaMeters(alert: SosAlertEntity): Double? =
            alert.accuracy?.takeIf { alert.latitude != null && alert.longitude != null && it > SosUpdateRule.GOOD_ACCURACY_M }
    }
}
