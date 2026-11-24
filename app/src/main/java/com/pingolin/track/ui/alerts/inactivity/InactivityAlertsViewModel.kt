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
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.util.CheckInCountdown
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InactivityAlertsViewModel @Inject constructor(
    private val inactivityAlertRepository: InactivityAlertRepository,
    private val circleRepository: CircleRepository,
    /** This account's newest check-ins, for the countdown on its check-in rules about itself. */
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    val inactivityAlerts: StateFlow<List<InactivityAlertEntity>> = inactivityAlertRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * This account's newest check-in that the server has, in each circle where it has a check-in
     * rule about itself, from the read's start on (CheckInCountdown.readFrom), as this screen last
     * read it (null: none). A circle whose read failed is left out: its rules show no countdown
     * rather than a wrong one.
     */
    private val newestCheckIns = MutableStateFlow<Map<String, Long?>>(emptyMap())

    /** Each of this account's active check-in rules about itself, by id, to its countdown (CheckInCountdown). */
    val selfCheckInCountdowns: StateFlow<Map<String, CheckInCountdown.Countdown>> =
        combine(inactivityAlerts, newestCheckIns) { rules, newest -> CheckInCountdown.countdowns(rules, newest) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private var selfCheckInRead: Job? = null

    /**
     * Read, from the server, this account's newest check-in in each circle where it has an active
     * check-in rule about itself: when the screen opens, and when the rules change. A read under way
     * gives way to the newer one.
     */
    fun readSelfCheckIns() {
        selfCheckInRead?.cancel()
        selfCheckInRead = viewModelScope.launch {
            val rules = inactivityAlertRepository.observeAll().first().filter(CheckInCountdown::appliesTo)
            val now = System.currentTimeMillis()
            for ((circleId, inCircle) in rules.groupBy { it.circleId }) {
                // From the earliest start among them: an older check-in changes no line.
                val since = inCircle.mapNotNull { CheckInCountdown.readFrom(it, now) }.minOrNull() ?: continue
                val read = checkInRepository.newestOwnCheckIn(circleId, since, now)
                newestCheckIns.update { known -> if (read.isSuccess) known + (circleId to read.getOrNull()) else known - circleId }
            }
        }
    }

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
            // The rules as the server has them may count down differently.
            if (result.isSuccess) readSelfCheckIns()
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
        // "No members in circle" and the alert is never created, and a refresh that
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
        watchedUserIds: List<String> = emptyList(),
        /** Location updates or check-ins (InactivityWatches). */
        watches: String = com.pingolin.track.util.InactivityWatches.LOCATION,
        /** A scheduled check-in rule's schedule; none: one-time. */
        schedule: com.pingolin.track.util.CheckInScheduleSetting? = null,
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
                watches = watches,
                schedule = schedule,
            )
            if (result.isSuccess) readSelfCheckIns()
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
            // Turned back on, a check-in rule counts from now; its circle may not have been read.
            if (result.isSuccess) readSelfCheckIns()
            _uiState.value = result.fold(
                onSuccess = { InactivityAlertsUiState.AlertUpdated },
                onFailure = { InactivityAlertsUiState.Error(it.plainText("Failed to update inactivity alert")) }
            )
        }
    }

    /**
     * Edit an existing rule's timeout (null: none, a scheduled check-in rule's), a scheduled
     * check-in rule's [schedule], and the notification channels.
     *
     * Takes no watcher set on purpose: a rule's target is structural (one row per watched
     * member) and an update that carries a watcher blob latches `client_only` server-side,
     * which stops the inactivity worker evaluating that rule for good. See
     * [com.pingolin.track.domain.repository.InactivityAlertRepository.updateInactivityAlert].
     */
    fun updateInactivityRule(
        alertId: String,
        timeoutMinutes: Int?,
        notifyPush: Boolean,
        schedule: com.pingolin.track.util.CheckInScheduleSetting? = null,
    ) {
        viewModelScope.launch {
            _uiState.value = InactivityAlertsUiState.Loading
            val result = inactivityAlertRepository.updateInactivityAlert(
                alertId = alertId,
                timeoutMinutes = timeoutMinutes,
                notifyPush = notifyPush,
                // Push only; an edit also clears an e-mail flag left by an older app version.
                notifyEmail = false,
                schedule = schedule,
            )
            // Resync so the new timeout shows (repo cache-copy doesn't update timeoutHours).
            if (result.isSuccess) inactivityAlertRepository.refreshInactivityAlerts()
            if (result.isSuccess) readSelfCheckIns()
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
