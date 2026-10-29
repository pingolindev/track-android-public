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

import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who is responding to MY request to be checked on, for the screens that would otherwise show
 * the person who asked only a count — the Safety list ("2 member(s) acknowledged") and the
 * alert's sheet on the map ("2 members acknowledged"): "Ana and 1 other are responding"
 * instead.
 *
 * GET /sos/{id} gives the raiser alone the ids of who acknowledged (`acknowledged_by`, oldest
 * first); every other member, and every other route, get the count only. Names come only from
 * this device's roster ([NotificationNames.respondingCopy]). Anything else — another member's
 * alert, an older server, no answer — leaves the count as it was.
 */
@Singleton
class MyAlertResponders @Inject constructor(
    private val tracksApi: TrackApi,
    private val notificationNames: NotificationNames,
    private val sessionManager: SessionManager,
) {
    /** The words for [alert] when it is this account's own and the server said who answered; else null. */
    suspend fun copyFor(alert: SosAlertEntity): String? {
        if (!AccountIds.same(alert.userId, sessionManager.getUserId())) return null
        val ids = acknowledgers(alert.id)?.takeIf { it.isNotEmpty() } ?: return null
        return notificationNames.respondingCopy(alert.circleId, ids)
    }

    /**
     * How many answered my request [sosId] BEFORE [acknowledger] did: the people ahead of them in
     * GET /sos/{id}'s list (oldest first; the raiser never in it). The rule the bell and the other
     * apps use for "and N others" ("earlier responders"), for a notice about [acknowledger]. Null
     * when there is no list (another member's alert, an older server, no answer) or they aren't
     * on it.
     */
    suspend fun earlierThan(sosId: String, acknowledger: String?): Int? {
        val who = AccountIds.canonical(acknowledger) ?: return null
        return acknowledgers(sosId)?.indexOf(who)?.takeIf { it >= 0 }
    }

    /** GET /sos/{id}'s `acknowledged_by`, each id in its one spelling; null when absent or unanswered. */
    internal suspend fun acknowledgers(sosId: String): List<String>? {
        val dto = runCatching { tracksApi.getSosAlert(sosId) }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.data ?: return null
        if (!AccountIds.same(dto.id, sosId)) return null
        return dto.acknowledgedBy?.mapNotNull { AccountIds.canonical(it) }
    }
}
