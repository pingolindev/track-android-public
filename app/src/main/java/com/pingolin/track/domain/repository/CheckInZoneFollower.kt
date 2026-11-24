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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps this account's scheduled check-in rules about itself that follow the phone ("Phone's time
 * zone") in the phone's zone: when the app starts or comes back, and when the phone's zone changes
 * (CheckInZoneWorker). The server times its wake-ups by the rule's zone, so it is told
 * the new one; the zone alone,
 * nothing else about the rule changes.
 */
@Singleton
class CheckInZoneFollower @Inject constructor(
    private val inactivityAlertDao: InactivityAlertDao,
    private val inactivityAlertRepository: InactivityAlertRepository,
    private val sessionManager: SessionManager,
) {
    companion object {
        /**
         * The rules in [rules] to move to [phoneZone]: [selfId]'s own scheduled check-in rules about
         * itself that follow the phone, on or off, in another zone.
         */
        internal fun toMove(rules: List<InactivityAlertEntity>, selfId: String, phoneZone: String): List<InactivityAlertEntity> =
            rules.filter {
                InactivityWatches.isCheckIn(it.watches) && CheckInModes.isScheduled(it.checkInMode) && it.isSelfAlert &&
                    it.scheduleTzFollowsPhone && AccountIds.same(it.userId, selfId) && it.scheduleTz != phoneZone
            }
    }

    /**
     * Move each rule that follows the phone to [phoneZone] (the phone's own: a bare offset the
     * server wouldn't take moves nothing). False when one failed in a way worth another try (no
     * answer, or the server's own failure); a refusal is not tried again.
     */
    suspend fun follow(phoneZone: String? = CheckInSchedule.phoneZoneId()): Boolean {
        val zone = phoneZone ?: return true
        val selfId = sessionManager.getUserId() ?: return true
        var done = true
        for (rule in toMove(inactivityAlertDao.getAll(), selfId, zone)) {
            val failure = inactivityAlertRepository.updateScheduleZone(rule.id, zone).exceptionOrNull() ?: continue
            if ((failure as? RefusedException)?.status !in 400..499) done = false
        }
        return done
    }
}
