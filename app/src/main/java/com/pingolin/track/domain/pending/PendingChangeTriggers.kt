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

package com.pingolin.track.domain.pending

import com.pingolin.track.domain.repository.CurrentKeyGate
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What wakes the changes waiting to sync while the app is alive: a circle leaving the gate's
 * behind sets, on its key or by its records, meaning this phone caught up on it. The silent
 * wake, the location sync worker and the app coming back kick [PendingChangeScheduler]
 * themselves.
 */
@Singleton
class PendingChangeTriggers @Inject constructor(
    private val currentKeyGate: CurrentKeyGate,
    private val scheduler: PendingChangeScheduler,
    private val sessionManager: SessionManager,
) {
    /** Where the watch runs; tests pass their own. */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var started = false

    /** Watch the gate for a circle this phone caught up on. Once per process. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Behind on its key, or by its records: either way nothing waiting can go.
            var before = currentKeyGate.behind.value + currentKeyGate.recordsBehind.value
            combine(currentKeyGate.behind, currentKeyGate.recordsBehind) { onKey, byRecords -> onKey + byRecords }.collect { now ->
                // A session store that can't be opened now is no session, and no crash.
                if ((before - now).isNotEmpty() && sessionManager.storeRefusal() == null && sessionManager.isLoggedIn()) scheduler.kick()
                before = now
            }
        }
    }
}
