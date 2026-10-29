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
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.QuotaDto
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and caches the user's current subscription tier + usage from
 * `GET /api/v1/quota` so Settings can render a proactive plan/usage card
 * (Places X/Y, Circles X/Y, Place alerts X/Y for free users) without
 * discovering limits only via a 402.
 *
 * Mirrors the Note Android app's QuotaManager. Server stays the authority
 * — this is a refresh-on-demand hint, not a long-lived cache.
 * Effective-premium gating still flows through
 * [SessionManager.effectivePremium] /
 * [com.pingolin.track.util.PremiumStatusRefresher]; this is purely for the
 * usage display.
 */
@Singleton
class QuotaManager @Inject constructor(
    private val tracksApi: TrackApi,
    private val sessionManager: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    // Bumped by clear() on logout/account-switch. An in-flight refreshNow() captures
    // this before its round-trip and only publishes if it hasn't changed, so a refresh
    // that started under the prior account can't repopulate _quota after clear() nulled
    // it. A plain isLoggedIn() re-check wouldn't close this: clear() runs BEFORE
    // sessionManager.clearSession() in clearLocalData, so isLoggedIn() is still true in
    // the racy window.
    private val clearGeneration = AtomicInteger(0)

    private val _quota = MutableStateFlow<QuotaDto?>(null)
    /** Latest known quota, or `null` before the first fetch lands (or when
     *  signed out). Callers should treat `null` as "assume free" — the same
     *  posture the server's fallback takes. */
    val quota: StateFlow<QuotaDto?> = _quota.asStateFlow()

    /** Fire-and-forget refresh. Safe from any thread; concurrent callers
     *  collapse to a single round-trip. */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    /** Suspending refresh for callers that want the latest tier before
     *  deciding how to surface something (e.g. after a 402). */
    suspend fun refreshNow() {
        if (!sessionManager.isLoggedIn()) {
            _quota.value = null
            return
        }
        val gen = clearGeneration.get()
        refreshLock.withLock {
            try {
                val resp = tracksApi.getQuota()
                if (resp.isSuccessful) {
                    // Don't repopulate a prior account's usage if a concurrent clear()
                    // landed while this round-trip was in flight (see clearGeneration).
                    resp.body()?.data?.let {
                        if (clearGeneration.get() == gen) _quota.value = it
                    }
                } else {
                    // 401 self-heals via TokenAuthenticator on the next
                    // request; older backends without /quota just leave the
                    // card in its neutral free framing.
                    Log.w(TAG, "refreshNow: getQuota returned ${resp.code()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshNow: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Drop cached state on sign-out so the next login starts clean. The generation
     *  bump makes any in-flight refreshNow() discard its result instead of racing a
     *  stale value back in. */
    fun clear() {
        clearGeneration.incrementAndGet()
        _quota.value = null
    }

    companion object {
        const val TIER_FREE = "free"
        const val TIER_PAID = "paid"
        private const val TAG = "QuotaManager"
    }
}
