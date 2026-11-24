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

package com.pingolin.track.ui.alerts.history

import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.InactivityWatches

/**
 * The past check-ins in Alerts > History, beside the alert entries.
 *
 * The next check-in shows up in the alert box, but past check-ins go in history.
 * Only the people the check-in rules that can alert this account are about, in
 * those rules' circles: this account's own check-in rules about others (on or off), its own about
 * itself ("You checked in"), and its circle-mates' requests to be watched. Nobody else's: the
 * check-in screen keeps the circle's full list. The last [DAYS] days, merged by time with the
 * alert entries, newest first.
 */
object HistoryCheckIns {

    /** How far back the history shows check-ins. */
    const val DAYS = 30

    /** [DAYS] in ms: the window the history reads (CheckInRepository.refreshRecentCheckIns). */
    const val WINDOW_MS = DAYS * 86_400_000L

    private const val DAY_MS = 86_400_000L

    /** One member whose check-ins the history shows, in one circle ([userId] in its store key). */
    data class Watched(val circleId: String, val userId: String)

    /**
     * Whose check-ins the history shows: the member each check-in rule that can alert this account
     * is about, in its circle. This account's own check-in rules ([own]; on or off): the member a
     * rule about others watches, and this account ([selfId]) for one about itself. And its
     * circle-mates' check-in requests ([requests], circle-self-alerts): each the member who made it.
     * Location rules watch no check-ins.
     */
    fun watched(own: List<InactivityAlertEntity>, requests: List<CircleSelfAlertDto>, selfId: String): Set<Watched> {
        val byRule = own.filter { InactivityWatches.isCheckIn(it.watches) }
            .map { Watched(it.circleId, AccountIds.storeKey(if (it.isSelfAlert) selfId else it.targetUserId)) }
        val asked = requests.filter { it.watchesCheckIns }.map { Watched(it.circleId, AccountIds.storeKey(it.userId)) }
        return (byRule + asked).toSet()
    }

    /**
     * The check-ins of [watched] people among [checkIns] (opened, from the server), from [sinceMs]
     * on: one row each, however many rules watch its sender.
     */
    fun select(checkIns: List<CheckInEntity>, watched: Set<Watched>, sinceMs: Long): List<CheckInEntity> =
        checkIns.filter { it.timestamp >= sinceMs && Watched(it.circleId, AccountIds.storeKey(it.userId)) in watched }
            .distinctBy { it.id }

    /**
     * [checkIn] as a history row: "You checked in" when it is [selfId]'s, else "{name} checked in"
     * ([nameOf]: the member's name in the circle, null when unknown), its type when it isn't the
     * plain one, its time, and its circle.
     */
    fun item(checkIn: CheckInEntity, selfId: String, nameOf: (circleId: String, userId: String) -> String?, circleName: String?) =
        AlertEventItem(
            id = "check-in:${checkIn.id}",
            type = AlertHandler.TYPE_CHECK_IN,
            message = "",
            dayBucket = (checkIn.timestamp / DAY_MS).toInt(),
            isAcknowledged = false,
            circleName = circleName,
            timestampMillis = checkIn.timestamp,
            memberName = nameOf(checkIn.circleId, checkIn.userId),
            checkInType = checkIn.type,
            ownCheckIn = AccountIds.storeKey(checkIn.userId) == AccountIds.storeKey(selfId),
        )

    /** Check-in types the history names beside the row: every one but the plain one ("Checking In"). */
    val NAMED_TYPES: Set<String> =
        setOf(CheckInRepository.TYPE_SAFE_ARRIVAL, CheckInRepository.TYPE_ARRIVED, CheckInRepository.TYPE_EMERGENCY_OK)

    /**
     * The history, newest first: the alert entries and the check-ins merged by time. An entry known
     * by its day alone (an older server row, or a time that doesn't open) sorts at its day's middle.
     */
    fun merged(items: List<AlertEventItem>): List<AlertEventItem> =
        items.sortedByDescending { it.timestampMillis ?: (it.dayBucket * DAY_MS + DAY_MS / 2) }
}
