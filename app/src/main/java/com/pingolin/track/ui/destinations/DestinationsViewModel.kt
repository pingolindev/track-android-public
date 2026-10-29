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

package com.pingolin.track.ui.destinations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.premiumErrorCode
import com.pingolin.track.data.remote.dto.quotaError
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.HiddenDestinationsStore
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import com.pingolin.track.util.plainTextOrNull
import kotlinx.coroutines.flow.combine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DestinationsViewModel @Inject constructor(
    private val destinationRepository: DestinationRepository,
    private val circleRepository: CircleRepository,
    private val locationRepository: LocationRepository,
    private val alertHandler: AlertHandler,
    private val sessionManager: SessionManager,
    private val hiddenDestinations: HiddenDestinationsStore,
    private val circleMemberDao: com.pingolin.track.data.local.dao.CircleMemberDao,
    pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
) : ViewModel() {

    /** Destinations and arrivals waiting to sync, at the top of the list. */
    val pending = com.pingolin.track.ui.pending.PendingListModel(
        pendingChanges, viewModelScope, circleRepository.observeCircles(),
        setOf(com.pingolin.track.domain.pending.PendingKind.DESTINATION, com.pingolin.track.domain.pending.PendingKind.ARRIVED),
    )

    // Every member's destinations, not just mine.
    //
    // Arrive / Cancel / Delete are creator-only, but hiding is local, so it
    // works on ANY destination — and it is the only thing you can do about
    // somebody else's, which is most of what the map shows. A hidden destination
    // therefore has to be reachable here, or hiding one would be a trapdoor with
    // no way back. Rows you did not create render without the owner-only
    // buttons. The web client lists them the same way.
    val destinations: StateFlow<List<DestinationListItem>> =
        combine(
            destinationRepository.observeAll(),
            hiddenDestinations.hidden,
            // Who isn't sharing where, for the rows of their destinations.
            circleMemberDao.observeAll().orWhenUnopenable(emptyList()).onStart { emit(emptyList()) },
        ) { list, hidden, roster ->
            val me = sessionManager.getUserId()
            val now = System.currentTimeMillis()
            list.map { d ->
                DestinationListItem(
                    destination = d,
                    // Resolve each destination's saved-place name (within ~100m)
                    // so the list can show the place name as the title and the
                    // address line beneath it.
                    placeName = destinationRepository.placeNameForDestination(d),
                    creatorName = if (d.isMine(me)) null else creatorNameFor(d),
                    isMine = d.isMine(me),
                    isHidden = d.id in hidden,
                    creatorSharing = if (d.isMine(me)) com.pingolin.track.ui.circles.SharingStatus.On else roster
                        .firstOrNull {
                            it.circleId == d.circleId &&
                                (it.userId == d.userId || com.pingolin.track.util.AccountIds.same(it.userId, d.userId))
                        }
                        ?.let { com.pingolin.track.ui.circles.MemberSharing.status(it, now) }
                        ?: com.pingolin.track.ui.circles.SharingStatus.On,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Display name for a destination's creator, from the circle roster. Null
     *  when the member isn't cached — the row then says "a circle member"
     *  rather than inventing a name. */
    private suspend fun creatorNameFor(d: DestinationEntity): String? =
        runCatching {
            circleMemberDao.get(d.circleId, d.userId)
                ?.displayName
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    fun toggleHidden(item: DestinationListItem) {
        if (item.isHidden) {
            hiddenDestinations.unhide(item.destination.id)
        } else {
            hiddenDestinations.hide(item.destination.id, item.destination.circleId)
        }
    }

    val activeDestinations: StateFlow<List<DestinationEntity>> = destinationRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow<DestinationsUiState>(DestinationsUiState.Idle)
    val uiState: StateFlow<DestinationsUiState> = _uiState.asStateFlow()

    init {
        refreshDestinations()
    }

    fun refreshDestinations() {
        viewModelScope.launch {
            _uiState.value = DestinationsUiState.Loading
            val result = destinationRepository.refreshDestinations()
            _uiState.value = result.fold(
                onSuccess = { DestinationsUiState.Idle },
                onFailure = { DestinationsUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.destinations_load_failed) }
            )
        }
    }

    fun createDestination(
        circleId: String,
        destName: String?,
        destLatitude: Double,
        destLongitude: Double,
        estimatedArrivalAtMs: Long? = null,
        notifyArrival: Boolean = true
    ) {
        viewModelScope.launch {
            _uiState.value = DestinationsUiState.Loading
            val result = destinationRepository.createDestination(
                circleId = circleId,
                destName = destName,
                destLatitude = destLatitude,
                destLongitude = destLongitude,
                estimatedArrivalAtMs = estimatedArrivalAtMs,
                notifyArrival = notifyArrival,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { DestinationsUiState.DestinationCreated(it) },
                // Carry the 402's quota detail through, the way PlacesViewModel does, so
                // the Fragment can offer the premium sheet rather than a dead-end toast.
                onFailure = {
                    DestinationsUiState.Error(
                        it.plainTextOrNull(),
                        it.quotaError,
                        it.premiumErrorCode,
                        com.pingolin.track.R.string.map_destination_share_failed,
                    )
                }
            )
        }
    }

    fun cancelDestination(destinationId: String) {
        viewModelScope.launch {
            _uiState.value = DestinationsUiState.Loading
            val result = destinationRepository.cancelDestination(destinationId)
            _uiState.value = result.fold(
                onSuccess = {
                    // The local DAO is already updated by cancelDestination/updateDestination,
                    // so Room Flow will emit the updated list automatically.
                    // Do NOT call refreshDestinations() here as it would set _uiState to Loading,
                    // potentially overwriting DestinationCancelled before the fragment observes it,
                    // and could re-fetch stale data from a read replica that overwrites the local update.
                    DestinationsUiState.DestinationCancelled
                },
                onFailure = { DestinationsUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.map_cancel_trip_failed) }
            )
        }
    }

    fun reportArrival(destination: DestinationEntity) {
        viewModelScope.launch {
            _uiState.value = DestinationsUiState.Loading

            // Resolve the label BEFORE reporting: a successful report deletes
            // the local row (arrived destinations leave the list immediately),
            // after which the saved-place / name lookup has nothing to read.
            // Mirrors the auto-arrival path in LocationTrackingService.
            val label = try {
                destinationRepository.labelForDestination(destination)
            } catch (e: Exception) {
                destination.destName
            }
            // A blank name is no name: the arrival line then says "your destination".
            val destLabel = label?.takeIf { it.isNotBlank() }

            // Get current location for arrival report
            val currentLocation = locationRepository.getCurrentLocation()
            val result = destinationRepository.reportArrival(
                destinationId = destination.id,
                circleId = destination.circleId,
                latitude = currentLocation?.latitude,
                longitude = currentLocation?.longitude,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = {
                    // Local "You've arrived!" tray notification,
                    // same as the auto-arrival path — the server
                    // deliberately sends the creator no arrival
                    // push (only the in-app record), and the
                    // stable self-arrival dedupe key means the
                    // self=true row re-fetched by a later tickle
                    // replaces this instead of stacking. Without
                    // this the manual button would produce an
                    // in-app row but no tray notification.
                    alertHandler.showSelfArrival(destination.id, destLabel)
                    DestinationsUiState.ArrivalReported(it.notificationsSent)
                },
                onFailure = { DestinationsUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.destinations_report_arrival_failed) }
            )
        }
    }

    fun deleteDestination(destinationId: String) {
        viewModelScope.launch {
            _uiState.value = DestinationsUiState.Loading
            val result = destinationRepository.deleteDestination(destinationId)
            _uiState.value = result.fold(
                onSuccess = { DestinationsUiState.DestinationDeleted },
                onFailure = { DestinationsUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.destinations_delete_failed) }
            )
        }
    }

    fun resetState() {
        _uiState.value = DestinationsUiState.Idle
    }
}

sealed class DestinationsUiState {
    object Idle : DestinationsUiState()
    object Loading : DestinationsUiState()
    /** [quota]/[errorCode] are non-null when the failure was a subscription
     *  gate (402), letting the Fragment show the premium prompt. [message]: words to
     *  show as they are (this app's own, or none); else the screen says [fallback]. */
    data class Error(
        val message: String?,
        val quota: QuotaError? = null,
        val errorCode: String? = null,
        @androidx.annotation.StringRes val fallback: Int = com.pingolin.track.R.string.destinations_load_failed,
    ) : DestinationsUiState()
    data class DestinationCreated(val destination: DestinationEntity) : DestinationsUiState()
    object DestinationCancelled : DestinationsUiState()
    data class ArrivalReported(val notificationsSent: Int) : DestinationsUiState()
    object DestinationDeleted : DestinationsUiState()
}
