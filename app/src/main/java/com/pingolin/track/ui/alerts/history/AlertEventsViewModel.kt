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
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.AlertEventResponse
import com.pingolin.track.data.remote.dto.InactivityEventResponse
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlertEventsViewModel @Inject constructor(
    private val alertRuleRepository: AlertRuleRepository,
    private val inactivityAlertRepository: InactivityAlertRepository,
    private val circleRepository: CircleRepository
) : ViewModel() {

    private val _alertEvents = MutableStateFlow<List<AlertEventResponse>>(emptyList())
    val alertEvents: StateFlow<List<AlertEventResponse>> = _alertEvents.asStateFlow()

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
                AlertEventItem(
                    id = e.id,
                    type = "inactivity",
                    message = if (resolved) {
                        "${e.targetDisplayName} — was silent ${e.timeoutHours}h, now active"
                    } else {
                        "${e.targetDisplayName} — no location for ${e.timeoutHours}h"
                    },
                    dayBucket = e.triggeredDayBucket,
                    isAcknowledged = resolved,
                    circleName = nameById[e.circleId],
                    statusOverride = if (resolved) "Resolved" else null,
                    timestampMillis = e.triggeredAt
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<AlertEventsUiState>(AlertEventsUiState.Idle)
    val uiState: StateFlow<AlertEventsUiState> = _uiState.asStateFlow()

    init {
        refreshEvents()
    }

    fun refreshEvents() {
        viewModelScope.launch {
            _uiState.value = AlertEventsUiState.Loading

            // Load alert rule events
            val alertResult = alertRuleRepository.getAlertEvents()
            alertResult.onSuccess { events ->
                _alertEvents.value = events
            }

            // Load inactivity events
            val inactivityResult = inactivityAlertRepository.getInactivityEvents()
            inactivityResult.onSuccess { events ->
                _inactivityEvents.value = events
            }

            _uiState.value = AlertEventsUiState.Idle
        }
    }

    fun resetState() {
        _uiState.value = AlertEventsUiState.Idle
    }
}

sealed class AlertEventsUiState {
    object Idle : AlertEventsUiState()
    object Loading : AlertEventsUiState()
    data class Error(val message: String) : AlertEventsUiState()
}
