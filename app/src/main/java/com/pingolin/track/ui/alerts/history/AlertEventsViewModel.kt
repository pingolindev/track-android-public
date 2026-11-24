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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.AlertEventResponse
import com.pingolin.track.data.remote.dto.InactivityEventResponse
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AlertEventsViewModel @Inject constructor(
    private val alertRuleRepository: AlertRuleRepository,
    private val inactivityAlertRepository: InactivityAlertRepository,
    private val circleRepository: CircleRepository,
    /** The past check-ins of the people this account's check-in rules are about (HistoryCheckIns). */
    private val checkInRepository: CheckInRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    /** The firings this account's devices reported, each with what it was, opened here. */
    private val _alertEvents = MutableStateFlow<List<OpenedAlertEvent>>(emptyList())
    val alertEvents: StateFlow<List<OpenedAlertEvent>> = _alertEvents.asStateFlow()

    private val _inactivityEvents = MutableStateFlow<List<InactivityEventResponse>>(emptyList())
    val inactivityEvents: StateFlow<List<InactivityEventResponse>> = _inactivityEvents.asStateFlow()

    // Local rules + circles, used to resolve each event's circle name for display.
    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val alertRules: StateFlow<List<AlertRuleEntity>> = alertRuleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val inactivityAlerts: StateFlow<List<InactivityAlertEntity>> = inactivityAlertRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Device-local inactivity firings, mapped to history rows (circle name resolved here).
     * Client-side firings are never on the server, so this is their only history source.
     */
    val localEventItems: StateFlow<List<AlertEventItem>> =
        combine(inactivityAlertRepository.observeLocalEvents(), circleRepository.observeCircles()) { events, circles ->
            val nameById = circles.associate { it.id to it.name }
            events.map { e ->
                val resolved = e.resolvedAt != null
                if (InactivityWatches.isCheckIn(e.watches)) {
                    // A missed check-in, in its notification's words (the adapter says them). Its
                    // member's check-in ended a resolved one.
                    AlertEventItem(
                        id = e.id,
                        type = AlertHandler.TYPE_MISSED_CHECK_IN,
                        message = "",
                        dayBucket = e.triggeredDayBucket,
                        isAcknowledged = resolved,
                        circleName = nameById[e.circleId],
                        statusOverride = if (resolved) "Resolved" else null,
                        timestampMillis = e.triggeredAt,
                        memberName = e.targetDisplayName,
                        timeoutHours = e.timeoutHours,
                        // A scheduled rule's: by its time as set.
                        dueMinute = e.dueMinute,
                    )
                } else AlertEventItem(
                    id = e.id,
                    type = "inactivity",
                    message = if (resolved) {
                        "${e.targetDisplayName}: was silent ${e.timeoutHours}h, now active"
                    } else {
                        "${e.targetDisplayName}: no location for ${e.timeoutHours}h"
                    },
                    dayBucket = e.triggeredDayBucket,
                    isAcknowledged = resolved,
                    circleName = nameById[e.circleId],
                    statusOverride = if (resolved) "Resolved" else null,
                    timestampMillis = e.triggeredAt
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The last read of the watched people's check-ins ([loadCheckIns]). */
    private data class CheckInsRead(val rows: List<CheckInEntity>, val names: Map<String, String>, val selfId: String, val failed: Boolean)

    private val checkInsRead = MutableStateFlow<CheckInsRead?>(null)
    private var checkInsLoad: Job? = null

    /** The watched people's past check-ins as history rows, their circles named. */
    val checkInItems: StateFlow<List<AlertEventItem>> =
        combine(checkInsRead, circleRepository.observeCircles()) { read, circles ->
            if (read == null) return@combine emptyList()
            val circleNames = circles.associate { it.id to it.name }
            read.rows.map { checkIn ->
                HistoryCheckIns.item(
                    checkIn, read.selfId,
                    nameOf = { circleId, userId -> read.names["$circleId|${AccountIds.storeKey(userId)}"] },
                    circleName = circleNames[checkIn.circleId],
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Some of them couldn't be read: the history says "Check-ins couldn't be loaded." beside the alerts. */
    val checkInsFailed: StateFlow<Boolean> = checkInsRead.map { it?.failed == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _uiState = MutableStateFlow<AlertEventsUiState>(AlertEventsUiState.Idle)
    val uiState: StateFlow<AlertEventsUiState> = _uiState.asStateFlow()

    init {
        refreshEvents()
    }

    fun refreshEvents() {
        // The watched people's check-ins, read beside the alerts rather than after them.
        checkInsLoad?.cancel()
        checkInsLoad = viewModelScope.launch { loadCheckIns() }
        viewModelScope.launch {
            _uiState.value = AlertEventsUiState.Loading

            // Load alert rule events, each with what it was: sealed, and opened off the Main
            // thread (a decrypt per row is UniFFI CPU).
            val alertResult = alertRuleRepository.getAlertEvents()
            alertResult.onSuccess { events ->
                _alertEvents.value = withContext(Dispatchers.Default) {
                    events.map { OpenedAlertEvent(it, alertRuleRepository.alertTypeOf(it), alertRuleRepository.triggeredAtOf(it)) }
                }
            }

            // Load inactivity events
            val inactivityResult = inactivityAlertRepository.getInactivityEvents()
            inactivityResult.onSuccess { events ->
                _inactivityEvents.value = events
            }

            _uiState.value = AlertEventsUiState.Idle
        }
    }

    /**
     * Read, from the server, the last [HistoryCheckIns.DAYS] days of check-ins in each circle where
     * a check-in rule that can alert this account watches someone, and keep those of the people it
     * watches (HistoryCheckIns.watched). A check-in that doesn't open is left out. A circle whose
     * read fails, or a failed read of the circle-mates' requests, keeps what was read and says the
     * check-ins couldn't be loaded.
     */
    private suspend fun loadCheckIns() {
        checkInsRead.value = try {
            readCheckIns() ?: return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The session store couldn't be opened, say: the alerts stand, and the line says so.
            CheckInsRead(emptyList(), emptyMap(), selfId = "", failed = true)
        }
    }

    /** [loadCheckIns]' read; null signed out. */
    private suspend fun readCheckIns(): CheckInsRead? {
        val selfId = sessionManager.getUserId() ?: return null
        val now = System.currentTimeMillis()
        val own = inactivityAlertRepository.observeAll().first()
        val requests = inactivityAlertRepository.circleCheckInRequests()
        val watched = HistoryCheckIns.watched(own, requests.getOrDefault(emptyList()), selfId)
        var failed = requests.isFailure
        val rows = mutableListOf<CheckInEntity>()
        val names = mutableMapOf<String, String>()
        for (circleId in watched.mapTo(linkedSetOf()) { it.circleId }) {
            val read = checkInRepository.refreshRecentCheckIns(circleId, now, HistoryCheckIns.WINDOW_MS)
            read.onSuccess { rows += HistoryCheckIns.select(it.read, watched, now - HistoryCheckIns.WINDOW_MS) }
                .onFailure { failed = true }
            circleRepository.observeMembers(circleId).first().forEach { member ->
                names["$circleId|${AccountIds.storeKey(member.userId)}"] = member.displayName
            }
        }
        return CheckInsRead(rows, names, selfId, failed)
    }

    fun resetState() {
        _uiState.value = AlertEventsUiState.Idle
    }
}

/**
 * A reported firing with its type and its time as they opened here ([AlertRuleRepository.alertTypeOf],
 * [AlertRuleRepository.triggeredAtOf]); null when they didn't.
 */
data class OpenedAlertEvent(val event: AlertEventResponse, val alertType: String?, val triggeredAt: Long? = null)

sealed class AlertEventsUiState {
    object Idle : AlertEventsUiState()
    object Loading : AlertEventsUiState()
    data class Error(val message: String) : AlertEventsUiState()
}
