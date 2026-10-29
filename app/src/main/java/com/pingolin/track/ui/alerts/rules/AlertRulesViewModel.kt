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

package com.pingolin.track.ui.alerts.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.remote.dto.AlertEventResponse
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CircleRepository
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
class AlertRulesViewModel @Inject constructor(
    private val alertRuleRepository: AlertRuleRepository,
    private val circleRepository: CircleRepository,
    pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
) : ViewModel() {

    /** Alerts waiting to sync (battery, a threshold change, inactivity), at the top. */
    val pending = com.pingolin.track.ui.pending.PendingListModel(
        pendingChanges, viewModelScope, circleRepository.observeCircles(),
        setOf(
            com.pingolin.track.domain.pending.PendingKind.BATTERY_ALERT,
            com.pingolin.track.domain.pending.PendingKind.ALERT_CHANGE,
            com.pingolin.track.domain.pending.PendingKind.INACTIVITY_ALERT,
        ),
    )

    val alertRules: StateFlow<List<AlertRuleEntity>> = alertRuleRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        // Eagerly, not WhileSubscribed: the create dialog reads circles.value on demand at
        // FAB-tap time and nothing collects this flow, so WhileSubscribed would leave
        // .value empty forever and the dialog's "Join or create a circle first" guard would
        // always fire.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedCircle = MutableStateFlow<CircleEntity?>(null)
    val selectedCircle: StateFlow<CircleEntity?> = _selectedCircle.asStateFlow()

    private val _circleMembers = MutableStateFlow<List<CircleMemberEntity>>(emptyList())
    val circleMembers: StateFlow<List<CircleMemberEntity>> = _circleMembers.asStateFlow()
    private var memberLoadJob: Job? = null

    private val _alertEvents = MutableStateFlow<List<AlertEventResponse>>(emptyList())
    val alertEvents: StateFlow<List<AlertEventResponse>> = _alertEvents.asStateFlow()

    private val _uiState = MutableStateFlow<AlertRulesUiState>(AlertRulesUiState.Idle)
    val uiState: StateFlow<AlertRulesUiState> = _uiState.asStateFlow()

    init {
        refreshAlertRules()
    }

    fun refreshAlertRules() {
        viewModelScope.launch {
            _uiState.value = AlertRulesUiState.Loading
            val result = alertRuleRepository.refreshAlertRules()
            _uiState.value = result.fold(
                onSuccess = { AlertRulesUiState.Idle },
                onFailure = { AlertRulesUiState.Error(it.plainText("Failed to load alert rules")) }
            )
        }
    }

    fun selectCircle(circle: CircleEntity) {
        _selectedCircle.value = circle
        loadCircleMembers(circle.id)
    }

    private fun loadCircleMembers(circleId: String) {
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

    /**
     * Push only: a low-battery rule has no e-mail. It is judged on the watcher's phone, so the
     * server never learns who watches whom; an e-mail would hand it every firing.
     */
    fun createLowBatteryAlert(
        circleId: String,
        targetUserId: String,
        threshold: Int = 20,
        notifyPush: Boolean = true,
    ) {
        viewModelScope.launch {
            _uiState.value = AlertRulesUiState.Loading
            val result = alertRuleRepository.createLowBatteryAlert(
                circleId = circleId,
                targetUserId = targetUserId,
                threshold = threshold,
                notifyPush = notifyPush,
                notifyEmail = false,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { AlertRulesUiState.RuleCreated(it) },
                onFailure = { AlertRulesUiState.Error(it.plainText("Failed to create alert rule")) }
            )
        }
    }


    fun toggleRule(ruleId: String, isActive: Boolean) {
        viewModelScope.launch {
            _uiState.value = AlertRulesUiState.Loading
            val result = alertRuleRepository.updateAlertRule(ruleId, isActive = isActive)
            _uiState.value = result.fold(
                onSuccess = { AlertRulesUiState.RuleUpdated },
                onFailure = { AlertRulesUiState.Error(it.plainText("Failed to update alert rule")) }
            )
        }
    }

    /** Push only, as [createLowBatteryAlert]: an edit also clears an e-mail flag left by an older app
     * version.
     */
    fun updateBatteryRule(ruleId: String, threshold: Int, notifyPush: Boolean) {
        viewModelScope.launch {
            _uiState.value = AlertRulesUiState.Loading
            val result = alertRuleRepository.updateAlertRule(
                ruleId = ruleId,
                notifyPush = notifyPush,
                notifyEmail = false,
                conditions = mapOf("threshold" to threshold),
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { AlertRulesUiState.RuleUpdated },
                onFailure = { AlertRulesUiState.Error(it.plainText("Failed to update alert rule")) }
            )
        }
    }

    /** Fetch the current battery threshold for pre-filling the edit dialog. */
    suspend fun fetchBatteryThreshold(ruleId: String): Int? =
        alertRuleRepository.getBatteryThreshold(ruleId)

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            _uiState.value = AlertRulesUiState.Loading
            val result = alertRuleRepository.deleteAlertRule(ruleId)
            _uiState.value = result.fold(
                onSuccess = { AlertRulesUiState.RuleDeleted },
                onFailure = { AlertRulesUiState.Error(it.plainText("Failed to delete alert rule")) }
            )
        }
    }

    fun loadAlertEvents() {
        viewModelScope.launch {
            val result = alertRuleRepository.getAlertEvents()
            result.onSuccess { events ->
                _alertEvents.value = events
            }
        }
    }

    fun resetState() {
        _uiState.value = AlertRulesUiState.Idle
    }


}

sealed class AlertRulesUiState {
    object Idle : AlertRulesUiState()
    object Loading : AlertRulesUiState()
    data class Error(val message: String) : AlertRulesUiState()
    data class RuleCreated(val ruleId: String) : AlertRulesUiState()
    object RuleUpdated : AlertRulesUiState()
    object RuleDeleted : AlertRulesUiState()
}
