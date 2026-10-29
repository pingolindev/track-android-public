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

package com.pingolin.track.ui.main

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.SosAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.domain.repository.*
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val circleRepository: CircleRepository,
    private val locationRepository: LocationRepository,
    private val sosRepository: SosRepository,
    private val configRepository: ConfigRepository,
    private val geocodingRepository: GeocodingRepository,
    private val notificationRepository: NotificationRepository,
    private val tracksApi: TrackApi,
    private val sessionManager: SessionManager,
    private val destinationRepository: com.pingolin.track.domain.repository.DestinationRepository,
    private val hiddenDestinations: com.pingolin.track.util.HiddenDestinationsStore,
) : ViewModel() {

    val circles: StateFlow<List<CircleEntity>> = circleRepository.observeCircles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAlerts: StateFlow<List<SosAlertEntity>> = sosRepository.observeActiveAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * SOS alerts the map should draw — undismissed, unacknowledged by this user,
     * and with coordinates that decrypted.
     *
     * Activity-scoped and deliberately NOT filtered by [selectedCircleId]: an
     * SOS permeates beyond circles, so an emergency in a circle the map isn't
     * currently showing still renders. Every other map layer is circle-scoped.
     */
    val mappableSosAlerts: StateFlow<List<SosAlertEntity>> = sosRepository.observeMappableAlerts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Per-viewer hide, from the map marker's callout. See SosRepository.dismissSos. */
    fun dismissSos(alertId: String) {
        viewModelScope.launch {
            runCatching { sosRepository.dismissSos(alertId) }
        }
    }

    /** Unread notification count for badge display */
    val unreadNotificationCount: StateFlow<Int> = notificationRepository.observeUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _isTracking = MutableStateFlow(LocationTrackingService.isRunning)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _selectedCircleId = MutableStateFlow<String?>(null)
    val selectedCircleId: StateFlow<String?> = _selectedCircleId.asStateFlow()

    val selectedCircle: StateFlow<CircleEntity?> = combine(
        circles,
        selectedCircleId
    ) { circleList, circleId ->
        if (circleId == null) {
            null // "Just Me" mode - no circle selected
        } else {
            circleList.find { it.id == circleId }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * One-shot events asking the map fragment to fly to a specific point.
     * Emitted from places list taps so the map can zoom directly to the
     * picked place without a manual pan. Stored as state with a manual
     * consume step (rather than a one-shot SharedFlow) so the request
     * survives the navigation gap: PlacesFragment fires this and immediately
     * navigates to MapFragment, so a SharedFlow emits before MapFragment can
     * subscribe and the request is dropped — the map zooms to nothing and
     * loads the default view.
     *
     * Token bumps on every requestMapZoom so two consecutive taps on
     * the same place re-trigger the camera animation (StateFlow
     * collapses identical values; including the token prevents that).
     */
    data class MapZoomRequest(
        val lat: Double,
        val lng: Double,
        val zoom: Double = 16.0,
        val token: Long = System.nanoTime(),
        /** An area of this radius round the point: the map fits all of it rather than zooming to its centre. */
        val radiusMeters: Double? = null,
    )
    private val _mapZoomRequests = MutableStateFlow<MapZoomRequest?>(null)
    val mapZoomRequests: StateFlow<MapZoomRequest?> = _mapZoomRequests.asStateFlow()
    fun requestMapZoom(lat: Double, lng: Double, zoom: Double = 16.0, radiusMeters: Double? = null) {
        _mapZoomRequests.value = MapZoomRequest(lat, lng, zoom, radiusMeters = radiusMeters)
    }
    /** Called by MapFragment after applying a zoom request so it isn't
     *  re-applied on every view recreation. */
    fun consumeMapZoomRequest(token: Long) {
        if (_mapZoomRequests.value?.token == token) {
            _mapZoomRequests.value = null
        }
    }

    /**
     * A request to point the map at one member: fly to their last known
     * position and open their callout. Raised by tapping a member row in the
     * circle sheet.
     *
     * Held as state with a manual consume step for the same reason as
     * [MapZoomRequest] — the caller navigates to the map immediately after
     * raising it, so a one-shot event would fire before MapFragment has a
     * subscriber and be dropped.
     *
     * Carries no coordinates on purpose: locations are only ever decrypted
     * on-device into MapViewModel's per-circle flow, so the map resolves the
     * position itself once the member's row lands.
     */
    data class MemberFocusRequest(
        val circleId: String,
        val userId: String,
        val token: Long = System.nanoTime(),
    )
    private val _memberFocusRequests = MutableStateFlow<MemberFocusRequest?>(null)
    val memberFocusRequests: StateFlow<MemberFocusRequest?> = _memberFocusRequests.asStateFlow()

    /** Select [circleId] and ask the map to focus [userId] within it. The
     *  circle switch is part of the request: the map only ever renders the
     *  selected circle's members, so focusing someone from another circle
     *  would otherwise land on an empty map. */
    fun requestMemberFocus(circleId: String, userId: String) {
        selectCircle(circleId)
        _memberFocusRequests.value = MemberFocusRequest(circleId, userId)
    }

    fun consumeMemberFocusRequest(token: Long) {
        if (_memberFocusRequests.value?.token == token) {
            _memberFocusRequests.value = null
        }
    }

    /**
     * Open a circle's own screen because an ownership claim needs answering.
     *
     * Raised by tapping the `circle_ownership_claim` notification. Held as state with a
     * manual consume step for the same reason as [MemberFocusRequest]: the tap navigates
     * to the circles tab immediately after raising it, so a one-shot event would fire
     * before CirclesFragment has a subscriber and be dropped — and this is the one
     * notification in the app whose whole purpose is to land somebody on a control.
     */
    data class OwnershipClaimRequest(
        val circleId: String,
        val token: Long = System.nanoTime(),
    )
    private val _ownershipClaimRequests = MutableStateFlow<OwnershipClaimRequest?>(null)
    val ownershipClaimRequests: StateFlow<OwnershipClaimRequest?> =
        _ownershipClaimRequests.asStateFlow()

    /** Select [circleId] and ask the circles tab to open it. */
    fun requestOwnershipClaimScreen(circleId: String) {
        selectCircle(circleId)
        _ownershipClaimRequests.value = OwnershipClaimRequest(circleId)
    }

    fun consumeOwnershipClaimRequest(token: Long) {
        if (_ownershipClaimRequests.value?.token == token) {
            _ownershipClaimRequests.value = null
        }
    }

    /**
     * A tapped circle question ("{name} reset their password…", "{name} is waiting to
     * join…"): the Circles tab asks it again for [circleId], so the question is on screen
     * even when the app was started by the tap.
     */
    private val _circleQuestionRequests = MutableStateFlow<OwnershipClaimRequest?>(null)
    val circleQuestionRequests: StateFlow<OwnershipClaimRequest?> = _circleQuestionRequests.asStateFlow()

    fun requestCircleQuestion(circleId: String) {
        _circleQuestionRequests.value = OwnershipClaimRequest(circleId)
    }

    fun consumeCircleQuestionRequest(token: Long) {
        if (_circleQuestionRequests.value?.token == token) {
            _circleQuestionRequests.value = null
        }
    }

    /**
     * Point the map at a destination, by id.
     *
     * Coordinates only exist locally — the server holds them encrypted — so this
     * resolves from Room, refreshing once if the row has never been seen by this
     * process (tapping the tray notification on a cold start). Unhides it too:
     * asking to see a destination is a clear enough answer to having hidden it,
     * and flying to a marker that is not drawn would look broken.
     */
    fun requestMapZoomToDestination(destinationId: String) {
        viewModelScope.launch {
            var dest = destinationRepository.getById(destinationId)
            if (dest == null) {
                runCatching { destinationRepository.refreshDestinations() }
                dest = destinationRepository.getById(destinationId)
            }
            val d = dest ?: return@launch
            hiddenDestinations.unhide(destinationId)
            selectCircle(d.circleId)
            requestMapZoom(d.destLatitude, d.destLongitude)
        }
    }

    /**
     * A tapped SOS notification: the map is pointed at the alert's position, or fitted to its area
     * when it is one, and true says so. False when it has no position to show ("Sending current
     * location", or one that didn't open here): the caller opens the Safety tab, where its row
     * says why.
     */
    suspend fun zoomToSos(sosId: String): Boolean {
        // A database that can't be opened is no row here, never a crash.
        var alert = runCatching { sosRepository.getAlert(sosId) }.getOrNull()
        // A row that says it has no position yet goes to the Safety tab at once, with no wait on
        // the network: its row there follows an update itself.
        if (alert != null && alert.positionState != null) return false
        if (alert?.latitude == null || alert.longitude == null) {
            runCatching { sosRepository.refreshActiveAlerts() }
            alert = runCatching { sosRepository.getAlert(sosId) }.getOrNull()
        }
        val lat = alert?.latitude ?: return false
        val lng = alert.longitude ?: return false
        requestMapZoom(lat, lng, radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert))
        return true
    }

    private var initialized = false

    /**
     * Initialize data loading. Must be called after session validation succeeds.
     * This prevents API calls from firing before we know the session is valid,
     * which would cause requests with cleared tokens and wasted network calls.
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        fetchConfig()
        fetchProfile()
        refreshData()
        initializeGeocoding()

        // Auto-select default circle if set
        sessionManager.getDefaultCircleId()?.let { circleId ->
            _selectedCircleId.value = circleId
        }

        // First circle the user ever joins (or creates) becomes their default on the map.
        // Subsequent joins leave the existing default alone — if the user wants a
        // different circle as the default they set it manually from the circles screen.
        // Guarded by `isDefaultCircleSettled()` so this never reassigns, and a default
        // switched off ("Default on map") stays off: `getDefaultCircleId() == null` alone
        // also means "switched off", so testing it alone would put the first circle back
        // at the next change to the list, and the map on it.
        viewModelScope.launch {
            circles.collect { list ->
                if (list.isNotEmpty() && !sessionManager.isDefaultCircleSettled()) {
                    val first = list.first()
                    sessionManager.setDefaultCircleId(first.id)
                    _selectedCircleId.value = first.id
                }
            }
        }
    }

    /**
     * Sync the display name from the user's profile. A null from the server is
     * ignored rather than written through, so a locally-set name isn't wiped
     * before it has synced up.
     */
    private fun fetchProfile() {
        viewModelScope.launch {
            try {
                val response = tracksApi.getProfile()
                if (response.isSuccessful && response.body()?.data != null) {
                    val displayName = response.body()!!.data!!.displayName
                    if (displayName != null) {
                        sessionManager.setDisplayName(displayName)
                    }
                }
            } catch (e: Exception) {
                // Non-critical - use existing display name
            }
        }
    }

    private fun initializeGeocoding() {
        viewModelScope.launch {
            try {
                geocodingRepository.initialize()
                android.util.Log.i("MainViewModel", "Geocoding initialized successfully")
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to initialize geocoding: ${e.message}")
            }
        }
    }

    private fun fetchConfig() {
        viewModelScope.launch {
            try {
                configRepository.fetchConfig()
            } catch (e: Exception) {
                // Config fetch failure is non-fatal, use defaults
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                coroutineScope {
                    // SOS alerts and notifications have nothing to do with the
                    // map's first paint, so they must NOT be chained ahead of it.
                    // Run them concurrently with the circle → members/locations
                    // path and the user sees markers as soon as the member/location
                    // fan-out finishes, however slowly these two respond.
                    launch { runCatching { sosRepository.refreshActiveAlerts() } }
                    launch { runCatching { notificationRepository.refreshNotifications() } }

                    // Member + location fan-out depends on the circle list,
                    // so circles still has to land first — but only this
                    // chain is on the critical path for map paint.
                    circleRepository.refreshCircles()
                    circles.value.map { circle ->
                        async {
                            circleRepository.refreshMembers(circle.id)
                            locationRepository.refreshLatestLocations(circle.id)
                        }
                    }.awaitAll()
                }
            } catch (e: Exception) {
                // Swallowed: a failed refresh leaves the cached data on screen.
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Re-sync active SOS alerts on their own, without the full [refreshData]
     * fan-out. Called on every foreground: one GET, and the repository's stale
     * sweep closes out anything another member handled while we were away.
     */
    fun refreshSosAlerts() {
        viewModelScope.launch {
            runCatching { sosRepository.refreshActiveAlerts() }
        }
    }

    /** See [CircleRepository.onForegroundIdentities]. */
    fun recheckPreviousIdentities() {
        viewModelScope.launch {
            runCatching { circleRepository.onForegroundIdentities() }
        }
    }

    /** Refresh notifications from server */
    fun refreshNotifications() {
        viewModelScope.launch {
            try {
                notificationRepository.refreshNotifications()
            } catch (e: Exception) {
                // Non-critical - notifications will be refreshed on next full refresh
            }
        }
    }

    /** Mark all notifications as read */
    fun markAllNotificationsRead() {
        viewModelScope.launch {
            try {
                notificationRepository.markAllRead()
            } catch (e: Exception) {
                // Swallowed: the rows stay unread and the next refresh re-syncs them.
            }
        }
    }

    fun selectCircle(circleId: String) {
        _selectedCircleId.value = circleId
    }

    fun clearSelectedCircle() {
        _selectedCircleId.value = null
    }

    fun startTracking(intervalMs: Long? = null) {
        // Persist the user's choice so tracking resumes after reboot/app restart
        sessionManager.setTrackingEnabled(true)

        // Battery-saver stores its interval in MINUTES; everything else is millis.
        val effectiveInterval = intervalMs ?: if (sessionManager.isBatterySaverEnabled()) {
            sessionManager.getBatterySaverInterval() * 60 * 1000L
        } else {
            sessionManager.getTrackingIntervalMs()
        }

        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra(LocationTrackingService.EXTRA_INTERVAL_MS, effectiveInterval)
            putExtra(LocationTrackingService.EXTRA_BATTERY_SAVER, sessionManager.isBatterySaverEnabled())
        }
        context.startForegroundService(intent)
        _isTracking.value = true
    }

    fun stopTracking() {
        val intent = Intent(context, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_STOP
        }
        context.startService(intent)
        _isTracking.value = false
    }

    /** Call from onResume: the service can outlive or predate this ViewModel. */
    fun syncTrackingState() {
        _isTracking.value = LocationTrackingService.isRunning
    }

    fun getUserDisplayName(): String? = sessionManager.getDisplayName()
    fun getUserEmail(): String? = sessionManager.getEmail()
}
