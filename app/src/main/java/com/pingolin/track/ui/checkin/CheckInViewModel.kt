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

package com.pingolin.track.ui.checkin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingKind
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.ui.pending.PendingListModel
import com.pingolin.track.util.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CheckInViewModel @Inject constructor(
    private val checkInRepository: CheckInRepository,
    private val circleRepository: CircleRepository,
    private val placeRepository: PlaceRepository,
    private val locationRepository: LocationRepository,
    private val sessionManager: com.pingolin.track.util.SessionManager,
    private val pendingChanges: PendingChanges,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        /** The circle the screen is opened for (the map's check-in menu): the choice it opens on. */
        const val ARG_CIRCLE_ID = "circle_id"
    }

    /** The circle this screen was opened for, if it was opened for one. */
    private val openedFor: String? = savedStateHandle.get<String>(ARG_CIRCLE_ID)

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCircleId = MutableStateFlow<String?>(null)
    val selectedCircleId: StateFlow<String?> = _selectedCircleId.asStateFlow()

    /**
     * The name of the chosen circle that went (left, removed or deleted) while this screen was open.
     * The choice goes with it, and nothing is sent until another circle is picked; it never falls to
     * the first circle. Null once a circle is picked, or when none was lost.
     */
    private val _goneCircleName = MutableStateFlow<String?>(null)
    val goneCircleName: StateFlow<String?> = _goneCircleName.asStateFlow()

    /** The chosen circle's name, kept while it is chosen: what the screen says should it go. */
    private var chosenName: String? = null

    /** Whether the screen has made its opening choice: the circle it was opened for, or the only one. */
    private var opened = false

    init {
        // The table itself, not [circles]: its first value is the store's, never stateIn's empty start.
        viewModelScope.launch { circleRepository.observeCircles().collect { onCircles(it) } }
    }

    /**
     * The circles as they are now. The choice stays while its circle does. It opens on the circle the
     * screen was opened for, else on the only circle; with several, and none named, nothing is chosen
     * until the person picks. A chosen circle that goes takes the choice with it.
     */
    internal fun onCircles(list: List<CircleEntity>) {
        val chosen = _selectedCircleId.value
        if (chosen == null) {
            if (opened || list.isEmpty()) return
            opened = true
            val first = openedFor?.let { id -> list.firstOrNull { it.id == id } } ?: list.singleOrNull()
            if (first != null) {
                setSelectedCircle(first.id)
                chosenName = first.name
            }
            return
        }
        opened = true
        val still = list.firstOrNull { it.id == chosen }
        if (still != null) {
            chosenName = still.name
            return
        }
        _goneCircleName.value = chosenName.orEmpty()
        _selectedCircleId.value = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val checkIns: StateFlow<List<CheckInEntity>> = _selectedCircleId
        .filterNotNull()
        .flatMapLatest { circleId ->
            checkInRepository.observeCheckInsByCircle(circleId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val places: StateFlow<List<PlaceEntity>> = _selectedCircleId
        .filterNotNull()
        .flatMapLatest { circleId ->
            placeRepository.observePlacesByCircle(circleId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val members: StateFlow<List<CircleMemberEntity>> = _selectedCircleId
        .filterNotNull()
        .flatMapLatest { circleId ->
            circleRepository.observeMembers(circleId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Check-ins waiting to sync in the selected circle, at the top of its list. */
    val pending = PendingListModel(pendingChanges, viewModelScope, circleRepository.observeCircles(), setOf(PendingKind.CHECK_IN), _selectedCircleId)

    private val _uiState = MutableStateFlow<CheckInUiState>(CheckInUiState.Idle)
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    fun setSelectedCircle(circleId: String) {
        opened = true
        chosenName = circles.value.firstOrNull { it.id == circleId }?.name
        _goneCircleName.value = null
        _selectedCircleId.value = circleId
        refreshCheckIns()
        // Also refresh members so display names are available for check-in entries
        viewModelScope.launch {
            circleRepository.refreshMembers(circleId)
        }
    }

    fun refreshCheckIns() {
        val circleId = _selectedCircleId.value ?: return
        viewModelScope.launch {
            checkInRepository.refreshCheckIns(circleId)
        }
    }

    fun quickCheckIn(type: String) {
        val circleId = _selectedCircleId.value ?: return

        viewModelScope.launch {
            _uiState.value = CheckInUiState.Sending
            val result = checkInRepository.createQuickCheckIn(circleId, type, keepIfBehind = true)
            _uiState.value = result.fold(
                onSuccess = { CheckInUiState.Sent(it) },
                onFailure = { CheckInUiState.Error(it.plainText("Failed to send check-in")) }
            )
        }
    }

    fun checkInWithLocation(type: String) {
        val circleId = _selectedCircleId.value ?: return

        viewModelScope.launch {
            _uiState.value = CheckInUiState.Sending

            val location = locationRepository.getCurrentLocation()
            if (location == null) {
                _uiState.value = CheckInUiState.Error("Could not get current location")
                return@launch
            }

            val result = checkInRepository.createCheckInWithLocation(
                circleId = circleId,
                type = type,
                latitude = location.latitude,
                longitude = location.longitude,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { CheckInUiState.Sent(it) },
                onFailure = { CheckInUiState.Error(it.plainText("Failed to send check-in")) }
            )
        }
    }

    fun checkInAtPlace(placeId: String, message: String? = null) {
        val circleId = _selectedCircleId.value ?: return

        viewModelScope.launch {
            _uiState.value = CheckInUiState.Sending
            val result = checkInRepository.createCheckInAtPlace(
                circleId = circleId,
                placeId = placeId,
                message = message,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { CheckInUiState.Sent(it) },
                onFailure = { CheckInUiState.Error(it.plainText("Failed to send check-in")) }
            )
        }
    }

    fun checkInWithMessage(type: String, message: String) {
        val circleId = _selectedCircleId.value ?: return

        viewModelScope.launch {
            _uiState.value = CheckInUiState.Sending

            val location = locationRepository.getCurrentLocation()

            val result = checkInRepository.createCheckIn(
                circleId = circleId,
                type = type,
                message = message,
                latitude = location?.latitude,
                longitude = location?.longitude,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { CheckInUiState.Sent(it) },
                onFailure = { CheckInUiState.Error(it.plainText("Failed to send check-in")) }
            )
        }
    }

    fun resetState() {
        _uiState.value = CheckInUiState.Idle
    }

    fun getMemberName(userId: String): String {
        // First try to find in circle members list
        members.value.find { it.userId == userId }?.displayName?.let {
            if (it != "Unknown") return it
        }
        // If the user is the current user, use the session display name
        val currentUserId = sessionManager.getUserId()
        if (currentUserId != null && userId == currentUserId) {
            return sessionManager.getDisplayName() ?: "You"
        }
        // If no match found but members haven't loaded yet, indicate loading state
        // rather than showing a confusing "Unknown" label
        if (members.value.isEmpty()) {
            // Members not loaded yet - check if this is the current user by ID prefix match
            // (the decrypted user ID should match exactly, but be defensive)
            if (currentUserId != null && userId == currentUserId) {
                return sessionManager.getDisplayName() ?: "You"
            }
            return "Loading..."
        }
        return "Member"
    }

    fun getPlaceName(placeId: String): String? {
        return places.value.find { it.id == placeId }?.name
    }
}

sealed class CheckInUiState {
    object Idle : CheckInUiState()
    object Sending : CheckInUiState()
    data class Sent(val checkIn: CheckInEntity) : CheckInUiState()
    data class Error(val message: String) : CheckInUiState()
}
