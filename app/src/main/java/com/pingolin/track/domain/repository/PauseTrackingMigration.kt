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

import android.util.Log
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.PauseSharingRequest
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves an older app version's device-wide "Pause Tracking" switch to each circle's own pause: a
 * person pauses each circle instead, as on iPhone.
 *
 * An install that had the switch on gets every circle paused on the server with no end, so the
 * others see "Paused", and this phone's copy of each circle's settings saying so; only then is
 * the switch's flag cleared, and the note that says so (and that the person's own history records
 * again) is due once. Until then the flag still stops all tracking
 * ([SessionManager.isTrackingPaused]), so nothing is sent in between: no silent resume. Anything
 * short of that (offline, a page of the circle list it couldn't get, one pause refused) keeps the
 * flag for the next start, which pauses them all again; a pause already in place stays. Only the
 * account the switch belongs to is moved; while another is signed in it is parked
 * ([SessionManager.isTrackingPaused], [SessionManager.claimTrackingPauseFor]).
 */
@Singleton
class PauseTrackingMigration @Inject constructor(
    private val sessionManager: SessionManager,
    private val circleRepository: CircleRepository,
    private val tracksApi: TrackApi,
    private val privacySettingDao: PrivacySettingDao,
) {
    enum class Outcome { NOTHING_TO_MOVE, MOVED, NOT_YET }

    private val running = Mutex()

    suspend fun runIfNeeded(): Outcome = running.withLock {
        // Another account's switch reads false here: parked for its owner, never moved onto this
        // account's circles.
        if (!sessionManager.isTrackingPaused()) return@withLock Outcome.NOTHING_TO_MOVE
        val me = sessionManager.getUserId()
        if (!sessionManager.isLoggedIn() || me == null) return@withLock Outcome.NOT_YET
        if (!sessionManager.claimTrackingPauseFor(me)) return@withLock Outcome.NOTHING_TO_MOVE
        // Every circle, or none: one left out would get the location once the switch is gone.
        val circleIds = allCircleIds() ?: return@withLock Outcome.NOT_YET
        var all = true
        for (circleId in circleIds) {
            if (!pause(circleId)) all = false
        }
        if (!all) return@withLock Outcome.NOT_YET
        sessionManager.clearTrackingPaused()
        // Said even with no circle: tracking starts again, and the person's own history with it.
        sessionManager.markPauseTrackingMovedNote(hadCircles = circleIds.isNotEmpty())
        Log.i(TAG, "Pause Tracking moved: ${circleIds.size} circle(s) paused")
        Outcome.MOVED
    }

    /**
     * Every circle of this account, through every page of the list; null if any page fails.
     * There is no cap to lean on: a paid account's circles are unlimited (the server's quota
     * says `max_circles: -1`), and one page holds at most 100.
     */
    private suspend fun allCircleIds(): Set<String>? {
        val ids = linkedSetOf<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            // Offline: no answer, as a refused page, never a crash on the return that runs this.
            val response = runCatching { tracksApi.getCirclesPaginated(cursor = cursor, limit = PAGE_SIZE) }.getOrNull() ?: return null
            val page = response.body()?.takeIf { response.isSuccessful && it.success }?.data ?: return null
            page.items.mapTo(ids) { it.id }
            if (!page.hasMore) return ids
            cursor = page.cursor ?: return null
        }
        return null
    }

    /**
     * [circleId] paused on the server with no end, and this phone's copy of its settings saying
     * so (the gate every send checks). A timed pause becomes one with no end: ending by itself
     * would be the silent resume this move exists to avoid.
     */
    private suspend fun pause(circleId: String): Boolean = try {
        val paused = tracksApi.pauseSharing(circleId, PauseSharingRequest(resumeAt = null))
        when {
            paused.isSuccessful && paused.body()?.success == true -> {
                val read = tracksApi.getPrivacySettings(circleId)
                val settings = read.body()?.data?.takeIf { read.isSuccessful && read.body()?.success == true }
                if (settings != null) circleRepository.persistPrivacyLocally(settings)
                val kept = privacySettingDao.get(circleId)
                kept != null && kept.sharingMode == PrivacySettingEntity.MODE_PAUSED && kept.pausedUntil == null
            }
            // Not a member there any more (the server answers "Not a member of this circle"):
            // nothing goes to it, so nothing to pause. Waiting on it would keep this phone
            // stopped for good, with no switch left to end it.
            paused.code() == 403 || paused.code() == 404 -> true
            else -> {
                Log.w(TAG, "Pause Tracking move: pause refused for $circleId (${paused.code()})")
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Pause Tracking move: $circleId not paused yet: ${e.message}")
        false
    }

    private companion object {
        const val TAG = "PauseTrackingMigration"
        // The list's largest page (the server's maximum).
        const val PAGE_SIZE = 100
        // A list that never ends is no answer: the switch stays.
        const val MAX_PAGES = 1_000
    }
}
