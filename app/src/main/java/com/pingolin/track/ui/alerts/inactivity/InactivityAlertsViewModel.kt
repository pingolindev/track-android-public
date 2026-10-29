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

package com.pingolin.track.ui.alerts.inactivity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.InactivityEventResponse
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InactivityAlertsViewModel @Inject constructor(
    private val inactivityAlertRepository: InactivityAlertRepository,
    private val circleRepository: CircleRepository
) : ViewModel() {

    val inactivityAlerts: StateFlow<List<InactivityAlertEntity>> = inactivityAlertRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        // Eagerly, not WhileSubscribed: the create dialog reads circles.value on demand at
        // FAB-tap time and nothing collects this flow, so WhileSubscribed would leave
        // .value empty forever and the dialog's "Join or create a circle first" guard would
        // always fire.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _circleMembers = MutableStateFlow<List<CircleMemberEntity>>(emptyList())
    val circleMembers: StateFlow<List<CircleMemberEntity>> = _circleMembers.asStateFlow()
    private var memberLoadJob: Job? = null

    private val _inactivityEvents = MutableStateFlow<List<InactivityEventResponse>>(emptyList())
    val inactivityEvents: StateFlow<List<InactivityEventResponse>> = _inactivityEvents.asStateFlow()

    private val _uiState = MutableStateFlow<InactivityAlertsUiState>(InactivityAlertsUiState.Idle)
    val uiState: StateFlow<InactivityAlertsUiState> = _uiState.asStateFlow()

    init {
        refreshInactivityAlerts()
    }

    fun refreshInactivityAlerts() {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.refreshInactivityAlerts()
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.Idle },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to load inactivity alerts")) }
            )
        }
    }

    fun loadCircleMembers(circleId: String) {
        // Populate from locally-synced members FIRST so the create dialog's target is
        // ready the instant it opens. Waiting on the network refresh alone lands after
        // the user has already tapped Create: circleMembers is empty, the dialog says
        // "No members in circle" and the alert is never created — and a refresh that
        // fails never populates it at all. Cancel any prior load so switching circles
        // can't race old members back in.
        memberLoadJob?.cancel()
        memberLoadJob = viewModelScope.launch {
            circleRepository.observeMembers(circleId).first().let { local ->
                if (local.isNotEmpty()) _circleMembers.value = local
            }
            circleRepository.refreshMembers(circleId).onSuccess { _circleMembers.value = it }
        }
    }

    fun createInactivityAlert(
        circleId: String,
        targetUserId: String,
        timeoutMinutes: Int,
        isSelfAlert: Boolean = false,
        notifyPush: Boolean = true,
        notifyOnReturn: Boolean = false,
        cooldownMinutes: Int = 60,
        watchedUserIds: List<String> = emptyList()
    ) {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.createInactivityAlert(
                circleId = circleId,
                targetUserId = targetUserId,
                timeoutMinutes = timeoutMinutes,
                isSelfAlert = isSelfAlert,
                notifyPush = notifyPush,
                // No e-mail for inactivity: judged where the server can't see who watches whom,
                // and an e-mail would hand it every firing.
                notifyEmail = false,
                notifyOnReturn = notifyOnReturn,
                cooldownMinutes = cooldownMinutes,
                watchedUserIds = watchedUserIds,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.AlertCreated(it) },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to create inactivity alert")) }
            )
        }
    }

    fun toggleAlert(alertId: String, isActive: Boolean) {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.updateInactivityAlert(alertId, isActive = isActive)
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.AlertUpdated },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to update inactivity alert")) }
            )
        }
    }

    /**
     * Edit an existing rule's timeout and notification channels.
     *
     * Takes no watcher set on purpose: a rule's target is structural (one row per watched
     * member) and an update that carries a watcher blob latches `client_only` server-side,
     * which stops the inactivity worker evaluating that rule for good. See
     * [com.pingolin.track.domain.repository.InactivityAlertRepository.updateInactivityAlert].
     */
    fun updateInactivityRule(
        alertId: String,
        timeoutMinutes: Int,
        notifyPush: Boolean,
    ) {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.updateInactivityAlert(
                alertId = alertId,
                timeoutMinutes = timeoutMinutes,
                notifyPush = notifyPush,
                // Push only; an edit also clears an e-mail flag left by an older app version.
                notifyEmail = false
            )
            // Resync so the new timeout shows (repo cache-copy doesn't update timeoutHours).
            if (result.isSuccess) inactivityAlertRepository.refreshInactivityAlerts()
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.AlertUpdated },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to update inactivity alert")) }
            )
        }
    }

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.deleteInactivityAlert(alertId)
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.AlertDeleted },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to delete inactivity alert")) }
            )
        }
    }

    fun loadInactivityEvents() {
        viewModelScope.launch {
            val result = inactivityAlertRepository.getInactivityEvents()
            result.onSuccess { events ->
                _inactivityEvents.value = events
            }
        }
    }

    fun resetState() {
        _uiState.value = InactivityAlertsUiState.Idle
    }
}

sealed class InactivityAlertsUiState {
    object Idle : InactivityAlertsUiState()
    object Loading : InactivityAlertsUiState()
    data class Error(val message: String) : InactivityAlertsUiState()
    data class AlertCreated(val alertId: String) : InactivityAlertsUiState()
    object AlertUpdated : InactivityAlertsUiState()
    object AlertDeleted : InactivityAlertsUiState()
}
