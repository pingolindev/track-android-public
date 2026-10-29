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

package com.pingolin.track.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.premiumErrorCode
import com.pingolin.track.data.remote.dto.quotaError
import com.pingolin.track.domain.pending.QueuedForSync
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.plainTextOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placeRepository: PlaceRepository,
    private val circleRepository: CircleRepository,
    private val geocodingRepository: GeocodingRepository,
    private val sessionManager: com.pingolin.track.util.SessionManager,
    private val pendingChanges: com.pingolin.track.domain.pending.PendingChanges,
) : ViewModel() {

    /** Place changes waiting to sync, at the top of the list. */
    val pending = com.pingolin.track.ui.pending.PendingListModel(
        pendingChanges, viewModelScope, circleRepository.observeCircles(),
        setOf(
            com.pingolin.track.domain.pending.PendingKind.SHARE_PLACE,
            com.pingolin.track.domain.pending.PendingKind.COPY_PLACES,
            com.pingolin.track.domain.pending.PendingKind.EDIT_PLACE,
            com.pingolin.track.domain.pending.PendingKind.MEMBER_PLACE_ALERT,
            com.pingolin.track.domain.pending.PendingKind.CIRCLE_PLACE_ALERT,
        ),
    )

    /** This user's own id — the target of a circle-less "alert me about my own
     *  place" subscription, which has no member list to pick from. */
    fun currentUserId(): String? = sessionManager.getUserId()

    companion object {
        /** Local target sentinel for a circle-wide alert. On the wire the target is
         *  null; refreshPlaceAlerts decrypts the encrypted "all" sentinel back to this,
         *  so a circle-wide alert is stored locally with targetUserId == "all". */
        private const val CIRCLE_WIDE_TARGET = "all"
    }

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * All places visible to this user — their own personal places AND any place
     * shared into a circle they belong to.
     *
     * It must stay on observePlaces() (a thin wrapper over placeDao.observeAll()),
     * never on observePersonalPlaces(): that one filters to circleId="self" and
     * silently drops every place a circle peer shared with the user.
     */
    val places: StateFlow<List<PlaceEntity>> = placeRepository.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedPlace = MutableStateFlow<PlaceEntity?>(null)
    val selectedPlace: StateFlow<PlaceEntity?> = _selectedPlace.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedPlaceAlerts: StateFlow<List<PlaceAlertEntity>> = _selectedPlace
        .filterNotNull()
        .flatMapLatest { place ->
            placeRepository.observeAlertsByPlace(place.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** placeId -> that place's alerts. Empty list and absent both mean "no alerts". */
    private val _placeAlertsMap = MutableStateFlow<Map<String, List<PlaceAlertEntity>>>(emptyMap())
    val placeAlertsMap: StateFlow<Map<String, List<PlaceAlertEntity>>> = _placeAlertsMap.asStateFlow()

    init {
        viewModelScope.launch {
            placeRepository.refreshPlaceAlerts()
        }
    }

    private val _uiState = MutableStateFlow<PlacesUiState>(PlacesUiState.Idle)
    val uiState: StateFlow<PlacesUiState> = _uiState.asStateFlow()

    fun selectPlace(place: PlaceEntity?) {
        _selectedPlace.value = place
    }

    fun refreshPlaces() {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            // null circle id = every personal place, unfiltered.
            val result = placeRepository.refreshPlaces(null)
            _uiState.value = result.fold(
                onSuccess = { PlacesUiState.Idle },
                onFailure = { PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.places_load_failed) }
            )
            // Populate the alerts StateFlow once places (and their per-place keys) are loaded,
            // so the bell dialog pre-fills existing alerts on a fresh visit — not only after an
            // alert CRUD op. refreshPlaceAlerts decrypts each alert against its place's key, so
            // it must run AFTER the places refresh.
            if (result.isSuccess) {
                refreshPlaceAlertsMap()
            }
        }
    }

    /** Create a place. Places are always created personal; sharing to circles is a
     * separate step.
     */
    fun createPlace(
        name: String,
        notes: String? = null,
        latitude: Double,
        longitude: Double,
        radius: Int,
        icon: String? = null,
        color: String? = null,
        boundaryRadius: Int? = null
    ) {
        if (name.isBlank()) {
            _uiState.value = PlacesUiState.Error(null, fallback = com.pingolin.track.R.string.place_name_required)
            return
        }

        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading

            val address = try {
                geocodingRepository.reverseGeocode(latitude, longitude)?.text
            } catch (_: Exception) {
                null
            }

            val result = placeRepository.createPersonalPlace(
                name = name,
                notes = notes,
                address = address,
                latitude = latitude,
                longitude = longitude,
                radius = radius,
                icon = icon,
                color = color,
                boundaryRadius = boundaryRadius,
            )
            _uiState.value = result.fold(
                onSuccess = { PlacesUiState.PlaceCreated(it) },
                onFailure = { PlacesUiState.Error(it.plainTextOrNull(), it.quotaError, it.premiumErrorCode, com.pingolin.track.R.string.place_create_failed) }
            )
        }
    }

    fun updatePlace(
        placeId: String,
        name: String? = null,
        notes: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        radius: Int? = null,
        icon: String? = null,
        color: String? = null,
        boundaryRadius: Int? = null
    ) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val result = placeRepository.updatePlace(
                placeId = placeId,
                name = name,
                notes = notes,
                latitude = latitude,
                longitude = longitude,
                radius = radius,
                icon = icon,
                color = color,
                boundaryRadius = boundaryRadius,
                keepIfBehind = true,
            )
            _uiState.value = result.fold(
                onSuccess = { PlacesUiState.PlaceUpdated(it) },
                onFailure = {
                    if (it is QueuedForSync) PlacesUiState.Kept(it.line)
                    else PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.place_update_failed)
                }
            )
        }
    }

    /**
     * A circle's members. Kicks off a server refresh as a side effect and returns
     * the Room flow, so the first emission can be a stale or empty cache.
     */
    fun getCircleMembers(circleId: String): Flow<List<CircleMemberEntity>> {
        viewModelScope.launch {
            circleRepository.refreshMembers(circleId)
        }
        return circleRepository.observeMembers(circleId)
    }

    fun deletePlace(placeId: String) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val result = placeRepository.deletePlace(placeId)
            _uiState.value = result.fold(
                onSuccess = {
                    _selectedPlace.value = null
                    // The Room flow should already reflect the delete; this covers the
                    // cases where the server's view and the local rows disagree.
                    viewModelScope.launch { placeRepository.refreshPlaces(null) }
                    PlacesUiState.PlaceDeleted
                },
                onFailure = { PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.place_delete_failed) }
            )
        }
    }

    /**
     * Create or replace a place's alerts in one shot — the unified alert editor's save action.
     * Reconciles the existing alert set to the desired one:
     *   - targetUserIds empty   -> one circle-wide alert (target_user_id = null, encrypts "all")
     *   - targetUserIds present -> one alert per selected member (target_user_id = member)
     * A target that already has an alert is UPDATED in place (quota-neutral, so editing an
     * existing alert can't trip the per-user quota); a new target is created (respecting the
     * quota); a removed target is deleted. Creates run first so a failed create rolls back
     * cleanly without ever destroying the existing set. Carries the full schedule +
     * notification preferences for either path.
     *
     * Runs in viewModelScope, NOT the caller's, so awaiting it from a fragment scope
     * can't cancel an in-flight save on rotation.
     *
     * @param circleId the circle the alert routes under, or NULL for a purely local
     *   alert on the user's own personal place — subscriber-side evaluation needs no
     *   circle at all.
     * @return the save outcome. It MUST be returned rather than swallowed: the editor
     *   keeps itself open and shows the server's reason on failure, and a
     *   fire-and-forget save that dismisses on tap turns a rejection (say the
     *   backend's 400 "Chosen circle is not a valid home for this place") into a lost
     *   edit that reads as "it saved, then silently un-saved itself".
     */
    fun savePlaceAlerts(
        placeId: String,
        circleId: String?,
        targetUserIds: List<String>,
        alertType: String,
        activeDays: List<String>?,
        activeStartTime: String?,
        activeEndTime: String?,
        isRecurring: Boolean,
        expiresAt: String?,
        isActive: Boolean,
        notifyPush: Boolean,
        notifyEmail: Boolean,
        notifySms: Boolean
    ): kotlinx.coroutines.Deferred<Result<Unit>> =
        viewModelScope.async {
            _uiState.value = PlacesUiState.Loading
            val alertOnArrival = alertType == "arrival" || alertType == "both"
            val alertOnDeparture = alertType == "departure" || alertType == "both"

            val existing = _placeAlertsMap.value[placeId] ?: emptyList()

            // The desired target set. An empty targetUserIds means ONE circle-wide alert,
            // modeled locally as a single alert whose target is the "all" sentinel (see
            // refreshPlaceAlerts, which decrypts a circle-wide alert's target back to
            // CIRCLE_WIDE_TARGET).
            val desiredTargets: List<String> =
                if (targetUserIds.isEmpty()) listOf(CIRCLE_WIDE_TARGET) else targetUserIds.distinct()

            // Reuse an existing alert whose target is unchanged: editing the SAME target
            // must be an in-place UPDATE (quota-neutral), NOT create-new + delete-old.
            // Create-then-delete transiently holds (existing + new) alerts, so a
            // free-tier user at the per-user alert cap gets a 402 for an edit that
            // doesn't change the net count at all. An UPDATE never raises the count; a
            // genuinely NEW target goes through create() and legitimately respects the
            // quota.
            val existingByTarget = existing.groupBy { it.targetUserId }
            // First existing alert per desired target — the row we UPDATE in place. Any
            // extra duplicate rows for that target are pruned in the delete step below.
            val reuseFor = desiredTargets
                .mapNotNull { t -> existingByTarget[t]?.firstOrNull()?.let { t to it } }
                .toMap()

            val createdIds = mutableListOf<String>()
            // Keep the throwable (not just its message) so a 402 quota gate carries
            // error_code/quota through to the premium prompt.
            var lastFailure: Throwable? = null
            // A create kept to go out once this phone catches up on the circle never
            // lets this save delete or replace anything: it is kept only when the place
            // has no alert to update or delete. Otherwise the whole save is refused
            // while this phone is behind, and nothing changes.
            val mayKeep = existing.isEmpty()
            val kept = mutableListOf<String>()
            // The kept changes this save made, dropped again if the save fails.
            val keptIds = mutableListOf<String>()

            // 1) CREATE genuinely-new targets first — the only quota-sensitive step. Doing
            //    every create before touching any existing alert is what guarantees a
            //    FAILED create never destroys existing alerts: on failure we roll back
            //    only the partial creates and leave the original set intact.
            for (target in desiredTargets) {
                if (lastFailure != null) break
                if (reuseFor.containsKey(target)) continue // an edit — handled by UPDATE below
                val result = if (target == CIRCLE_WIDE_TARGET) {
                    placeRepository.createCircleWidePlaceAlert(
                        circleId = circleId,
                        placeId = placeId,
                        alertOnArrival = alertOnArrival,
                        alertOnDeparture = alertOnDeparture,
                        activeDays = activeDays,
                        activeStartTime = activeStartTime,
                        activeEndTime = activeEndTime,
                        notifyPush = notifyPush,
                        notifyEmail = notifyEmail,
                        notifySms = notifySms,
                        isRecurring = isRecurring,
                        expiresAt = expiresAt,
                        keepIfBehind = mayKeep,
                    )
                } else {
                    placeRepository.createPlaceAlert(
                        circleId = circleId,
                        placeId = placeId,
                        targetUserId = target,
                        alertOnArrival = alertOnArrival,
                        alertOnDeparture = alertOnDeparture,
                        activeDays = activeDays,
                        activeStartTime = activeStartTime,
                        activeEndTime = activeEndTime,
                        notifyPush = notifyPush,
                        notifyEmail = notifyEmail,
                        notifySms = notifySms,
                        isRecurring = isRecurring,
                        expiresAt = expiresAt,
                        keepIfBehind = mayKeep,
                    )
                }
                result.fold(
                    onSuccess = { createdIds.add(it) },
                    onFailure = {
                        if (it is QueuedForSync) {
                            kept += it.line
                            if (it.created) keptIds += it.changeId
                        } else {
                            lastFailure = it
                        }
                    },
                )
            }

            if (lastFailure != null) {
                // A create failed (quota/offline/…) — roll back the partial new creates, and the
                // ones kept for later (a failed save never goes out in part), and leave every
                // existing alert untouched, then surface the error.
                for (id in createdIds) {
                    placeRepository.deletePlaceAlert(id)
                }
                for (id in keptIds) pendingChanges.discard(id)
                refreshPlaceAlertsMap()
                _uiState.value = PlacesUiState.Error(
                    lastFailure?.plainTextOrNull(),
                    lastFailure?.quotaError,
                    lastFailure?.premiumErrorCode,
                    com.pingolin.track.R.string.place_alert_save_failed,
                )
                return@async Result.failure(
                    lastFailure ?: Exception("Failed to save alert")
                )
            }

            // 2) UPDATE reused alerts in place — quota-neutral (no new row), so editing an
            //    existing target never trips the quota. Also re-syncs the match hashes to
            //    the new toggles (updatePlaceAlert re-derives them under the routing circle).
            //    Fold each Result into lastFailure: a failed edit (offline / 5xx) must NOT
            //    fall through to the delete step and report success. Skip the rest once one
            //    fails so a later delete can't run on a half-applied save.
            for ((_, alert) in reuseFor) {
                if (lastFailure != null) break
                placeRepository.updatePlaceAlert(
                    alertId = alert.id,
                    alertOnArrival = alertOnArrival,
                    alertOnDeparture = alertOnDeparture,
                    activeDays = activeDays,
                    activeStartTime = activeStartTime,
                    activeEndTime = activeEndTime,
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    isRecurring = isRecurring,
                    isActive = isActive,
                    expiresAt = expiresAt
                ).onFailure { lastFailure = it }
            }

            if (lastFailure != null) {
                // An UPDATE failed — roll back the partial new creates from step 1 (a failed
                // updatePlaceAlert leaves the OLD row intact, so the updates themselves need
                // no rollback) and surface the error WITHOUT touching the delete step. This
                // stops a failed edit from both reporting success AND destroying siblings.
                for (id in createdIds) {
                    placeRepository.deletePlaceAlert(id)
                }
                for (id in keptIds) pendingChanges.discard(id)
                refreshPlaceAlertsMap()
                _uiState.value = PlacesUiState.Error(
                    lastFailure?.plainTextOrNull(),
                    lastFailure?.quotaError,
                    lastFailure?.premiumErrorCode,
                    com.pingolin.track.R.string.place_alert_save_failed,
                )
                return@async Result.failure(
                    lastFailure ?: Exception("Failed to save alert")
                )
            }

            // 3) DELETE existing alerts we didn't reuse — targets no longer selected, plus
            //    any duplicate rows for a reused target. Done last, only after every
            //    create/update succeeded, so the place is never transiently left with no
            //    alerts.
            val reusedIds = reuseFor.values.mapTo(HashSet()) { it.id }
            for (alert in existing) {
                if (alert.id !in reusedIds) {
                    placeRepository.deletePlaceAlert(alert.id)
                }
            }

            refreshPlaceAlertsMap()
            _uiState.value = kept.firstOrNull()?.let { PlacesUiState.Kept(it) } ?: PlacesUiState.AlertCreated("")
            Result.success(Unit)
        }

    /**
     * Remove every alert for a place — the unified editor's "Remove" action.
     *
     * Never discard deletePlaceAlert's Result and report AlertDeleted regardless: a
     * delete the server rejected then tells the user "Alert removed" while the alert
     * keeps firing, and reporting success you did not verify is worse than reporting
     * nothing. The loop still attempts every alert rather than stopping at the first
     * error, so one bad row can't strand the rest.
     */
    fun removePlaceAlerts(placeId: String) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val existing = _placeAlertsMap.value[placeId] ?: emptyList()
            var lastFailure: Throwable? = null
            for (alert in existing) {
                placeRepository.deletePlaceAlert(alert.id).onFailure { lastFailure = it }
            }
            refreshPlaceAlertsMap()
            if (lastFailure != null) {
                _uiState.value = PlacesUiState.Error(
                    lastFailure?.plainTextOrNull(),
                    lastFailure?.quotaError,
                    lastFailure?.premiumErrorCode,
                    com.pingolin.track.R.string.place_alert_remove_failed,
                )
                return@launch
            }
            _uiState.value = PlacesUiState.AlertDeleted
        }
    }

    fun deleteAlert(alertId: String) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val result = placeRepository.deletePlaceAlert(alertId)
            _uiState.value = result.fold(
                onSuccess = { PlacesUiState.AlertDeleted },
                onFailure = { PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.place_alert_remove_failed) }
            )
        }
    }

    /** Reads the cached map, so it is only as fresh as the last refresh. */
    fun hasAlertsEnabled(placeId: String): Boolean {
        return !_placeAlertsMap.value[placeId].isNullOrEmpty()
    }

    /**
     * Every alert on this place is DORMANT because the person it watches has
     * switched off `allow_alerts_on_me` in the alert's consent circle.
     *
     * Distinct from "no alerts": the alerts exist and are enabled, they simply
     * cannot fire. Saying so is the point: an alert that silently stops working
     * is indistinguishable from a broken feature.
     *
     * Requires at least one alert, so a place with none is never "suppressed".
     */
    fun allAlertsSuppressed(placeId: String): Boolean {
        val alerts = _placeAlertsMap.value[placeId]
        if (alerts.isNullOrEmpty()) return false
        return alerts.all { it.suppressedByTarget == true }
    }

    /** Refresh the alerts map for all places. A failed refresh leaves it as it was. */
    private suspend fun refreshPlaceAlertsMap() {
        val result = placeRepository.refreshPlaceAlerts()
        if (result.isSuccess) {
            val alerts = result.getOrNull() ?: emptyList()
            _placeAlertsMap.value = alerts.groupBy { it.placeId }
        }
    }

    fun resetState() {
        _uiState.value = PlacesUiState.Idle
    }

    /** Share a place to several circles; it is copied and re-encrypted for each one. */
    fun sharePlaceToCircles(place: PlaceEntity, targetCircleIds: Set<String>) {
        if (targetCircleIds.isEmpty()) {
            _uiState.value = PlacesUiState.Error(null, fallback = com.pingolin.track.R.string.places_share_none_selected)
            return
        }

        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            var successCount = 0
            val totalCount = targetCircleIds.size
            // Keep the most recent failure so the error carries the real reason
            // ("Source circle key not available", "Target circle key not available",
            // anything from createPlace) rather than a bare "Failed to share place",
            // which is undiagnosable.
            var lastFailure: Throwable? = null
            // Copies kept to go out once this phone catches up on their circle.
            val kept = mutableListOf<String>()

            for (circleId in targetCircleIds) {
                val result = placeRepository.copyPlaceToCircle(place, circleId, keepIfBehind = true)
                if (result.isSuccess) {
                    successCount++
                } else if (result.exceptionOrNull() is QueuedForSync) {
                    kept += (result.exceptionOrNull() as QueuedForSync).line
                } else {
                    lastFailure = result.exceptionOrNull()
                    android.util.Log.e(
                        "PlacesViewModel",
                        "sharePlaceToCircles: copy to circle=$circleId failed",
                        lastFailure,
                    )
                }
            }

            if (successCount == 0 && kept.isNotEmpty() && lastFailure == null) {
                _uiState.value = PlacesUiState.Kept(kept.first())
            } else if (successCount == 0) {
                _uiState.value = PlacesUiState.Error(
                    lastFailure?.plainTextOrNull(), fallback = com.pingolin.track.R.string.places_share_failed_no_reason
                )
            } else {
                _uiState.value = PlacesUiState.PlaceShared(successCount, totalCount)
            }
        }
    }

    /** Share a place with a single circle — the Place Visibility section's toggle. */
    fun sharePlaceWithCircle(placeId: String, circleId: String) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val result = placeRepository.sharePlaceWithCircle(placeId, circleId, keepIfBehind = true)
            _uiState.value = result.fold(
                onSuccess = {
                    // Refresh places to update sharedCircles
                    placeRepository.refreshPlaces(null)
                    PlacesUiState.PlaceShared(1, 1)
                },
                onFailure = {
                    if (it is QueuedForSync) PlacesUiState.Kept(it.line)
                    else PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.places_share_failed_no_reason)
                }
            )
        }
    }

    /** Unshare a place from a circle — the Place Visibility section's toggle. */
    fun unsharePlaceFromCircle(placeId: String, circleId: String) {
        viewModelScope.launch {
            _uiState.value = PlacesUiState.Loading
            val result = placeRepository.unsharePlaceFromCircle(placeId, circleId)
            _uiState.value = result.fold(
                onSuccess = {
                    // Refresh places to update sharedCircles
                    placeRepository.refreshPlaces(null)
                    PlacesUiState.PlaceUpdated(PlaceEntity(
                        id = placeId, circleId = "", name = "", address = null,
                        latitude = 0.0, longitude = 0.0, radius = 0,
                        icon = null, color = null, createdAt = 0, updatedAt = 0
                    ))
                },
                onFailure = { PlacesUiState.Error(it.plainTextOrNull(), fallback = com.pingolin.track.R.string.places_unshare_failed_no_reason) }
            )
        }
    }

    fun getOtherCircles(): List<CircleEntity> {
        return circles.value
    }

    /** Persist the user's preferred place ordering to the local database only. */
    fun updatePlaceOrder(orderedPlaces: List<PlaceEntity>) {
        viewModelScope.launch {
            placeRepository.updatePlaceSortOrder(orderedPlaces)
        }
    }
}

sealed class PlacesUiState {
    object Idle : PlacesUiState()
    object Loading : PlacesUiState()
    /**
     * [quota]/[errorCode] are non-null when the failure was a subscription
     * gate (402), letting the Fragment show the premium prompt.
     *
     * [message]: words to show as they are (this app's own, or none); else the screen says [fallback].
     */
    data class Error(
        val message: String?,
        val quota: QuotaError? = null,
        val errorCode: String? = null,
        @androidx.annotation.StringRes val fallback: Int = com.pingolin.track.R.string.places_load_failed,
    ) : PlacesUiState()
    data class PlaceCreated(val place: PlaceEntity) : PlacesUiState()
    data class PlaceUpdated(val place: PlaceEntity) : PlacesUiState()
    object PlaceDeleted : PlacesUiState()
    data class AlertCreated(val alertId: String) : PlacesUiState()
    /** Kept to go out once this phone catches up: [line] is its waiting line. */
    data class Kept(val line: String) : PlacesUiState()
    object AlertDeleted : PlacesUiState()
    data class PlaceShared(val successCount: Int, val totalCount: Int) : PlacesUiState()
    data class AlertsToggled(val enabled: Boolean) : PlacesUiState()
}
