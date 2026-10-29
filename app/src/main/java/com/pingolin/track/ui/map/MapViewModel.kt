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

package com.pingolin.track.ui.map

import androidx.annotation.VisibleForTesting
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.premiumErrorCode
import com.pingolin.track.data.remote.dto.quotaError
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.LocationFetchResult
import com.pingolin.track.domain.repository.LocationHistoryResult
import com.pingolin.track.domain.repository.LocationRepository
import com.pingolin.track.domain.repository.LocationRepository.CircleKeyUnavailableException
import com.pingolin.track.domain.repository.LocationRepository.CircleNotFoundException
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.domain.repository.SendOutcome
import com.pingolin.track.ui.places.placardAddress
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainTextOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * Time range modes for location history display.
 */
enum class TimeRangeMode {
    LIVE,      // Real-time locations only
    HOUR_1,    // Last 1 hour
    HOURS_24,  // Last 24 hours
    TODAY,     // Since midnight today
    DAYS_7,    // Last 7 days
    CUSTOM     // User-selected date range
}

/** What "Send location now" says, as it happened. */
sealed class SendLocationResult {
    /** Sent to [circleId], the circle on screen; or, with none (Just Me), saved to this account's own history. */
    data class Success(val circleId: String? = null) : SendLocationResult()
    /**
     * Kept on this phone to go when it can: for [circleId], or with none (Just Me) for this
     * account's own history; offline, or the connection dropped. [catchingUp]: kept because this
     * phone is still catching up with the circle.
     */
    data class Waiting(val circleId: String?, val catchingUp: Boolean = false) : SendLocationResult()
    /** Not sent to [circleId]: sharing there is paused ([paused]), or real-time sharing there is off. */
    data class NotSharing(val circleId: String, val paused: Boolean) : SendLocationResult()
    /**
     * Not sent: the circle it was asked for ([circleName], as the screen showed it) is no longer one of
     * this account's circles by the time the fix came in. Never sent anywhere else instead.
     */
    data class CircleGone(val circleName: String?) : SendLocationResult()
    data class RateLimited(val waitSeconds: Int) : SendLocationResult()
    /** [message]: words to show as they are (this app's own, or none); else the screen says [fallback]. */
    data class Error(val message: String?, @androidx.annotation.StringRes val fallback: Int = com.pingolin.track.R.string.map_send_failed) : SendLocationResult()
}

/**
 * Result of creating a place from map.
 */
sealed class CreatePlaceResult {
    /** Returns the created place's id so callers can chain post-create
     *  operations like draining pending shares into sharePlaceWithCircle. */
    data class Success(val placeId: String) : CreatePlaceResult()
    /** [quota]/[errorCode] are non-null when the failure was a subscription
     *  gate (402, place-count limit), so the Fragment can show the premium
     *  prompt instead of a status message. */
    data class Error(
        /** Words to show as they are; null: the screen says place_create_failed. */
        val message: String?,
        val quota: QuotaError? = null,
        val errorCode: String? = null,
    ) : CreatePlaceResult()
}

/**
 * Warning about decryption failures that should be shown to user.
 */
data class DecryptionWarning(
    /** How many locations can't be opened yet; null when none can (or this phone has no key for the circle at all). */
    val waitingCount: Int?,
    val successCount: Int,
    val failureCount: Int,
    val isKeyMissing: Boolean
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val circleRepository: CircleRepository,
    private val placeRepository: PlaceRepository,
    private val destinationRepository: com.pingolin.track.domain.repository.DestinationRepository,
    private val geocodingRepository: GeocodingRepository,
    private val sessionManager: SessionManager,
    private val privacySettingDao: com.pingolin.track.data.local.dao.PrivacySettingDao,
    private val hiddenDestinations: com.pingolin.track.util.HiddenDestinationsStore,
    /** This phone's battery level, for the sends made here. */
    private val battery: com.pingolin.track.util.BatteryLevel,
) : ViewModel() {

    // Where the history grouping in fetchLocationHistory runs: the Default pool in
    // the app. A test sets its own test dispatcher here, and has to. On the real
    // pool, advanceUntilIdle() returns while the grouping is still running on a
    // pool thread; the test's teardown then resets Main, and the grouping's resume
    // throws on that thread. kotlinx-coroutines-test pins that on whichever test
    // STARTS NEXT, as UncaughtExceptionsBeforeTest, so an unrelated test fails
    // intermittently.
    @VisibleForTesting
    internal var historyComputeDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * [userId]'s location sharing in [circleId], for the map's member card. This account's
     * own comes from its privacy settings on this phone; another member's from the member
     * list, where the server reports it.
     *
     * Real-time sharing off wins over a pause, as on the server; a pause counts by its mode
     * as well as its end, so one with no end ("until I resume") is still a pause.
     */
    suspend fun sharingStatus(circleId: String, userId: String): com.pingolin.track.ui.circles.SharingStatus {
        val now = System.currentTimeMillis()
        if (userId == sessionManager.getUserId()) {
            val settings = try { privacySettingDao.get(circleId) } catch (_: Exception) { null }
                ?: return com.pingolin.track.ui.circles.SharingStatus.On
            return com.pingolin.track.ui.circles.MemberSharing.own(settings.shareRealtime, settings.sharingMode, settings.pausedUntil, now)
        }
        // Read from the stored list for THIS circle, not [members]: right after a circle switch
        // that still holds the previous circle's.
        val member = try { circleRepository.member(circleId, userId) } catch (_: Exception) { null }
            ?: return com.pingolin.track.ui.circles.SharingStatus.On
        return com.pingolin.track.ui.circles.MemberSharing.status(member, now)
    }

    companion object {
        /** getMemberName's word for no name: kept in English in the exported file; the screen asks memberDisplayName. */
        private const val UNKNOWN_NAME = "Unknown"

        private const val POLLING_INTERVAL_MS = 30_000L // 30 seconds
        private const val TAG = "MapViewModel"
        /** Refresh non-selected alert circles every Nth poll tick (2 => 60 s). */
        private const val ALERT_CIRCLE_TICKS = 2L
        private const val SEND_LOCATION_RATE_LIMIT_MS = 15_000L // 15 seconds
        /** Maximum addresses to cache for geocoded locations */
        private const val MAX_CACHED_ADDRESSES = 500
        /** Between two imports the map starts by itself for locations it cannot open. */
        internal const val AUTO_KEY_IMPORT_INTERVAL_MS = 60_000L

        // Colors for different members (in ARGB format) - bright variants for dark mode visibility
        val MEMBER_COLORS = listOf(
            0xFF818CF8.toInt(), // Indigo (brighter variant for contrast)
            0xFF4ADE80.toInt(), // Emerald (brighter)
            0xFFF9A8D4.toInt(), // Pink (brighter)
            0xFFFCD34D.toInt(), // Amber (brighter)
            0xFF93C5FD.toInt(), // Blue (brighter)
            0xFFC4B5FD.toInt(), // Purple (brighter)
            0xFFFCA5A5.toInt(), // Red (brighter)
            0xFF5EEAD4.toInt()  // Teal (brighter)
        )
    }

    private var pollingJob: Job? = null

    /** Fires each time a location is successfully sent to the server (epoch ms). */
    val locationSentEvent: SharedFlow<Long> = locationRepository.locationSentEvent

    /** This device's own last-known position — the map persists it as the
     *  cold-start "open to my location" seed. */
    val selfLocation: StateFlow<com.pingolin.track.domain.repository.LocationRepository.SelfPosition?> =
        locationRepository.selfLocation

    // Continuous tracking state
    private val _isTrackingEnabled = MutableStateFlow(false)
    val isTrackingEnabled: StateFlow<Boolean> = _isTrackingEnabled.asStateFlow()

    private val _selectedCircleId = MutableStateFlow<String?>(null)
    val selectedCircleId: StateFlow<String?> = _selectedCircleId.asStateFlow()

    /**
     * Camera position saved when the map fragment goes away (e.g. a bottom-nav
     * tab switch recreates it). Restored on the next map open so returning to
     * the Map tab keeps the user's view instead of re-fitting to the circle.
     * Null on a cold start, so the first open still frames the circle.
     */
    data class SavedCamera(
        val lat: Double, val lng: Double, val zoom: Double, val bearing: Double, val tilt: Double
    )
    var savedCamera: SavedCamera? = null

    /**
     * Decrypted locations from the most recent network refresh of the selected
     * circle. Used as a cold-start fast-path: the Room observe-Flow's first
     * emission can lag ~8s on a cold launch (the warm StateFlow masks it on tab
     * switches), so we paint these in-hand locations immediately and let the
     * Room flow take over once it catches up. Cleared on circle change so it
     * never shows a previous circle's members.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val freshLocations: StateFlow<List<LocationEntity>> = selectedCircleId
        .flatMapLatest { circleId ->
            val effective = circleId ?: LocationRepository.SELF_CIRCLE_ID
            locationRepository.freshLocations
                .filter { it.first == effective }
                .map { it.second }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Time range mode
    private val _timeRangeMode = MutableStateFlow(TimeRangeMode.LIVE)
    val timeRangeMode: StateFlow<TimeRangeMode> = _timeRangeMode.asStateFlow()

    // Custom date range (millis)
    private var customStartTime = 0L
    private var customEndTime = 0L

    // Loading state for history fetch
    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    // Location history for trails (grouped by userId)
    private val _locationHistory = MutableStateFlow<Map<String, List<LocationEntity>>>(emptyMap())
    val locationHistory: StateFlow<Map<String, List<LocationEntity>>> = _locationHistory.asStateFlow()

    /**
     * The selected circle and its members who keep their location history from it (the member
     * list's `history_sharing` "off"), never this account. Their trails are never held here: see
     * [onRosterForHistory].
     */
    private val _hiddenHistory = MutableStateFlow<Pair<String, Set<String>>?>(null)
    val hiddenHistory: StateFlow<Pair<String, Set<String>>?> = _hiddenHistory.asStateFlow()

    /**
     * The server dropped the oldest points of the requested range to stay under
     * its row cap, so the trail on screen stops short of the range the user
     * picked. There is no next page to offer — the response already spans the
     * whole window — so the UI says so and points at a narrower range instead.
     */
    private val _locationHistoryTruncated = MutableStateFlow(false)
    val locationHistoryTruncated: StateFlow<Boolean> = _locationHistoryTruncated.asStateFlow()

    private var lastSendLocationTime = 0L
    private val _sendLocationCooldown = MutableStateFlow(0L)
    val sendLocationCooldown: StateFlow<Long> = _sendLocationCooldown.asStateFlow()

    private val _isSendingLocation = MutableStateFlow(false)
    val isSendingLocation: StateFlow<Boolean> = _isSendingLocation.asStateFlow()

    private val _sendLocationResult = MutableStateFlow<SendLocationResult?>(null)
    val sendLocationResult: StateFlow<SendLocationResult?> = _sendLocationResult.asStateFlow()

    private val _createPlaceResult = MutableStateFlow<CreatePlaceResult?>(null)
    val createPlaceResult: StateFlow<CreatePlaceResult?> = _createPlaceResult.asStateFlow()

    private val _mapBearing = MutableStateFlow(0.0)
    val mapBearing: StateFlow<Double> = _mapBearing.asStateFlow()

    fun updateMapBearing(bearing: Double) {
        _mapBearing.value = bearing
    }

    // Delete place result
    private val _deletePlaceResult = MutableStateFlow<Result<Unit>?>(null)
    val deletePlaceResult: StateFlow<Result<Unit>?> = _deletePlaceResult.asStateFlow()

    fun deletePlace(placeId: String) {
        viewModelScope.launch {
            _deletePlaceResult.value = placeRepository.deletePlace(placeId)
        }
    }

    fun clearDeletePlaceResult() {
        _deletePlaceResult.value = null
    }

    // Declared before [memberLocations], which reads it: properties initialise in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    val members: StateFlow<List<CircleMemberEntity>> = selectedCircleId
        .filterNotNull()
        .flatMapLatest { circleId ->
            circleRepository.observeMembers(circleId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val memberLocations: StateFlow<List<LocationEntity>> = combine(
        selectedCircleId.flatMapLatest { circleId ->
            // Just Me mode (no circle selected): fall back to the user's
            // own self-tracked location so a fresh, circle-less account
            // still sees a marker on the map.
            val effective = circleId ?: com.pingolin.track.domain.repository.LocationRepository.SELF_CIRCLE_ID
            // Seed an immediate empty so combine() doesn't block on the Room
            // observe-Flow's slow first emit (~8s cold) — that would defeat the
            // freshLocations fast-path below.
            locationRepository.observeLatestLocationsByCircle(effective).onStart { emit(emptyList()) }
        },
        freshLocations,
        locationRepository.selfLocation,
        members,
        locationRepository.frozenLocations,
    ) { roomLocations, fresh, selfPos, circleMembers, frozenByCircle ->
        // The Room observe-Flow is authoritative, but its first emission lags several
        // seconds on a cold launch — fall back to the freshly-fetched
        // (already-decrypted) locations until it catches up so markers paint promptly.
        // Every consumer (the marker combine, centerOnAllMembers,
        // selectedMemberLocation) then sees them without extra wiring.
        //
        // A member who switched location sharing off here, or paused it, has no marker:
        // their last position would read as where they are now. The member list and
        // card say why. Judged at every emission, so a timed pause that has ended lets
        // the member back on with the next location refresh.
        val now = System.currentTimeMillis()
        val hidden = com.pingolin.track.ui.circles.MemberSharing.hiddenOnMap(
            circleMembers, sessionManager.getUserId(), now
        )
        // Someone who turned real-time sharing off here is drawn where they were when they
        // did: the server's frozen row for THIS circle, never another circle's, and greyed
        // and labelled by the map ([drawsFrozen]).
        val frozen = circleMembers.firstOrNull()?.circleId?.let { circleId ->
            com.pingolin.track.ui.circles.MemberSharing.frozenOnMap(
                circleMembers, frozenByCircle[circleId].orEmpty(), sessionManager.getUserId(), now
            )
        }.orEmpty()
        mergeSelfLocation(roomLocations.ifEmpty { fresh }, selfPos)
            .filterNot { (it.circleId to it.userId) in hidden } + frozen
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Replace the self entry with this device's own position. The server
     * stores our location re-encrypted per circle-key version; right after a
     * key rotation a cold-started client may not yet hold the version our last
     * upload was wrapped under, so decrypting our OWN row can fail for a few
     * seconds until a fresh upload (or the key chain) lands, and the own
     * marker would be missing meanwhile. We don't need the server to tell us
     * where we are: drive the self marker straight from the position we
     * publish on every send ([LocationRepository.selfLocation]). History mode
     * is left untouched so the recorded path still renders.
     */
    private fun mergeSelfLocation(
        base: List<LocationEntity>,
        selfPos: com.pingolin.track.domain.repository.LocationRepository.SelfPosition?,
    ): List<LocationEntity> {
        val myUserId = sessionManager.getUserId() ?: return base
        // Only this phone's own position is "me": a server row naming this account never draws this
        // account's marker, before the first fix or in history either. The map's own location dot
        // shows until this phone has a fix.
        val others = base.filterNot { it.userId == myUserId || com.pingolin.track.util.AccountIds.same(it.userId, myUserId) }
        if (selfPos == null || isShowingHistory()) return others
        val circleId = _selectedCircleId.value
            ?: com.pingolin.track.domain.repository.LocationRepository.SELF_CIRCLE_ID
        val selfEntry = LocationEntity(
            circleId = circleId,
            userId = myUserId,
            deviceId = "self-local",
            latitude = selfPos.latitude,
            longitude = selfPos.longitude,
            altitude = selfPos.altitude,
            speed = selfPos.speed,
            heading = selfPos.heading,
            accuracy = selfPos.accuracy,
            batteryLevel = null,
            timestamp = selfPos.timestamp,
            isSynced = true,
        )
        return others + selfEntry
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val places: StateFlow<List<PlaceEntity>> = selectedCircleId
        .flatMapLatest { circleId ->
            if (circleId == null) {
                // "Just Me" mode - show personal places
                placeRepository.observePersonalPlaces()
            } else {
                // Include places shared with this circle from other circles
                placeRepository.observePlacesByCircleIncludingShared(circleId)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Destinations currently shared within the selected circle — used
     *  by MapFragment to render a flag marker per destination, labeled
     *  with the creator's name. Empty in "Just Me" mode since
     *  destinations are always a circle-scoped concept. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val destinations: StateFlow<List<com.pingolin.track.data.local.entity.DestinationEntity>> = selectedCircleId
        .flatMapLatest { circleId ->
            if (circleId == null) kotlinx.coroutines.flow.flowOf(emptyList())
            else destinationRepository.observeByCircle(circleId)
        }
        // Drop the ones the user has hidden on this device. Combined rather than
        // filtered once, so hiding from the destinations list clears the marker
        // straight away instead of at the next refresh. Also the point where the
        // hide set is pruned: this circle's rows are all here, so a hide whose
        // destination has since arrived can be forgotten.
        .combine(hiddenDestinations.hidden) { list, hidden ->
            selectedCircleId.value?.let { circleId ->
                hiddenDestinations.prune(circleId, list.map { it.id })
            }
            list.filterNot { it.id in hidden }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedMemberId = MutableStateFlow<String?>(null)
    val selectedMemberId: StateFlow<String?> = _selectedMemberId.asStateFlow()

    val selectedMemberLocation: StateFlow<LocationEntity?> = combine(
        memberLocations,
        selectedMemberId
    ) { locations, memberId ->
        locations.find { it.userId == memberId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Map layer visibility toggles.
    private val _showMembers = MutableStateFlow(true)
    val showMembers: StateFlow<Boolean> = _showMembers.asStateFlow()

    private val _showPlaces = MutableStateFlow(true)
    val showPlaces: StateFlow<Boolean> = _showPlaces.asStateFlow()

    /** Independent toggle for places another circle member shared with
     *  this user (wrap_via_circle is set on the entity). When false,
     *  shared places are filtered out of the map markers + place list
     *  while user-owned places stay visible. Matches the "Shared
     *  Places" filter on web. */
    private val _showSharedPlaces = MutableStateFlow(true)
    val showSharedPlaces: StateFlow<Boolean> = _showSharedPlaces.asStateFlow()

    private val _showDestinations = MutableStateFlow(true)
    val showDestinations: StateFlow<Boolean> = _showDestinations.asStateFlow()

    private val _showBoundaries = MutableStateFlow(true)
    val showBoundaries: StateFlow<Boolean> = _showBoundaries.asStateFlow()

    // Member addresses cache: maps "userId-lat-lng" to address string
    // Uses bounded LRU cache to prevent memory leaks from unbounded growth
    // Max 500 entries (~50KB memory at ~100 bytes per address string)
    private val memberAddressCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHED_ADDRESSES
        }
    }

    init {
        // Every member list read, while the map lives: see [onRosterForHistory].
        viewModelScope.launch { members.collect { onRosterForHistory(it) } }
    }

    // Tapped location state
    data class TappedLocation(
        val latitude: Double,
        val longitude: Double,
        /**
         * Formal name of the place when one is available (POI name from the map
         * tiles, e.g. "Yosemite Falls" or "Starbucks").
         *
         * Kept SEPARATE from [address] so the placard can render it as the
         * prominent title with the street address on a secondary line. Folding it
         * into `address` produces one long mashed "POI, 123 Street, City" line.
         */
        val name: String? = null,
        val address: String? = null,
        val isLoading: Boolean = true,
        val isOwnLocation: Boolean = false,
        val historicalTimestamp: Long? = null,
        /** Display name of the member whose point was tapped. Null for non-member taps. */
        val memberName: String? = null,
        /**
         * A point of a member who shares only an approximate location there: never looked up, and
         * the sheet shows no address, no coordinates, and nothing that hands it on.
         */
        val approximate: Boolean = false,
    )

    private val _tappedLocation = MutableStateFlow<TappedLocation?>(null)
    val tappedLocation: StateFlow<TappedLocation?> = _tappedLocation.asStateFlow()

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling.asStateFlow()

    private val _lastRefreshTime = MutableStateFlow<Long?>(null)
    val lastRefreshTime: StateFlow<Long?> = _lastRefreshTime.asStateFlow()

    // True while a single refresh round-trip is in flight (one call inside
    // the polling loop, or a manual pull-to-refresh). Distinct from
    // _isPolling, which is true for the *entire* polling lifecycle. The map
    // header strip uses this to show "Updating…" only during the network
    // call.
    private val _isRefreshingLocations = MutableStateFlow(false)
    val isRefreshingLocations: StateFlow<Boolean> = _isRefreshingLocations.asStateFlow()

    // Non-null when the most recent refresh attempt failed for a reason
    // that suggests the network is the problem (i.e., NOT a known
    // CircleNotFoundException, which is a server-state issue not a
    // connectivity one). Cleared on the next successful refresh. The map
    // header strip turns into "Couldn't reach server" while this is set.
    private val _lastRefreshFailedAt = MutableStateFlow<Long?>(null)
    val lastRefreshFailedAt: StateFlow<Long?> = _lastRefreshFailedAt.asStateFlow()

    // Decryption warning state - shown when some locations fail to decrypt
    private val _decryptionWarning = MutableStateFlow<DecryptionWarning?>(null)
    val decryptionWarning: StateFlow<DecryptionWarning?> = _decryptionWarning.asStateFlow()

    // Tracks consecutive refreshes that returned only undecryptable rows with
    // no missing key — usually a single stale legacy row that the next upload
    // will overwrite. Used to gate the "couldn't decode" snackbar so we don't
    // flash it on one-off transient failures.
    private var consecutiveDecryptOnlyFailures: Int = 0

    /** When [importKeysSoon] last started an import. */
    private var lastAutoKeyImportAtMs = 0L

    /**
     * Locations this device cannot open yet: fetch what it is missing, by itself —
     * nobody should have to tap for what the app can do. At most once a minute; what
     * arrives is shown at once.
     */
    private fun importKeysSoon() {
        val now = System.currentTimeMillis()
        if (now - lastAutoKeyImportAtMs < AUTO_KEY_IMPORT_INTERVAL_MS) return
        lastAutoKeyImportAtMs = now
        viewModelScope.launch {
            val imported = runCatching { circleRepository.importCircleKeys().getOrNull() ?: 0 }
                .onFailure { Log.w(TAG, "Automatic key import failed: ${it.message}") }
                .getOrDefault(0)
            if (imported > 0) _selectedCircleId.value?.let { refreshLocationsInternal(it) }
        }
    }

    /** The address cache's key for [userId] at a position: theirs, and where. */
    private fun memberAddressKey(userId: String, latitude: Double, longitude: Double): String =
        "${userId}-${String.format("%.5f", latitude)}-${String.format("%.5f", longitude)}"

    /**
     * Get the cached address for a member location, if available.
     */
    fun getMemberAddress(userId: String, latitude: Double, longitude: Double): String? {
        val locationKey = memberAddressKey(userId, latitude, longitude)
        return synchronized(memberAddressCache) {
            memberAddressCache[locationKey]
        }
    }

    /**
     * On-demand geocode for the member-tap bottom sheet. Returns the cached
     * address synchronously if present, otherwise reverse-geocodes and caches
     * the result (so subsequent renders of the same location pick it up via
     * getMemberAddress).
     *
     * This is the ONLY thing that geocodes a member's position. Nothing runs
     * in the background: a tap is itself the request for the address, and no
     * member's live position is geocoded as it arrives. Same shape on iOS
     * (MemberDetailSheet) and on the web client.
     */
    suspend fun resolveMemberAddress(
        userId: String,
        latitude: Double,
        longitude: Double,
    ): String? {
        val locationKey = memberAddressKey(userId, latitude, longitude)

        synchronized(memberAddressCache) {
            memberAddressCache[locationKey]?.let { return it }
        }

        // canGeocode, not hasGeocodingData: the latter asks only about local
        // tiles, so with the server geocoder on and no manifest no address
        // would ever appear.
        if (!geocodingRepository.canGeocode()) return null

        val result = try {
            geocodingRepository.reverseGeocode(latitude, longitude)
        } catch (_: Exception) {
            null
        } ?: return null

        // The member sheet has one line for all of this, so the POI name rides
        // along with the street and locality rather than being dropped.
        val line = result.placardAddress(withPoiName = true) ?: return null

        synchronized(memberAddressCache) {
            if (!memberAddressCache.containsKey(locationKey)) {
                memberAddressCache[locationKey] = line
            }
        }
        return line
    }

    /**
     * Reverse-geocode an SOS alert's coordinates.
     *
     * Shares the member-address cache but under a distinct key prefix: an SOS is
     * a fixed snapshot from when the button was pressed, not the raiser's live
     * position, so caching the two under one key would have each overwrite the
     * other as the member moves.
     */
    suspend fun resolveSosAddress(alertId: String, latitude: Double, longitude: Double): String? =
        resolveMemberAddress("sos:$alertId", latitude, longitude)

    /**
     * Handle map tap - geocode the location and update UI.
     * @param isOwnLocation true if the user tapped on their own location marker
     * @param historicalTimestamp optional timestamp if this is a historical location point
     * @param tileGeocodeFallback optional fallback that queries map tiles for place names
     * @param poiNameExtractor optional callback to extract POI name from map tiles
     */
    fun onMapTapped(
        latitude: Double,
        longitude: Double,
        isOwnLocation: Boolean = false,
        historicalTimestamp: Long? = null,
        memberName: String? = null,
        tileGeocodeFallback: (suspend () -> String?)? = null,
        poiNameExtractor: (suspend () -> String?)? = null,
        approximate: Boolean = false,
    ) {
        // A point of someone sharing an approximate location: no lookup at all, as their
        // marker's card does none. The sheet says it's approximate, with its time.
        if (approximate) {
            _tappedLocation.value = TappedLocation(
                latitude = latitude,
                longitude = longitude,
                isLoading = false,
                historicalTimestamp = historicalTimestamp,
                memberName = memberName,
                approximate = true,
            )
            return
        }
        // Publish the loading state first: the sheet opens on this emission and
        // then updates in place when the geocode below lands.
        _tappedLocation.value = TappedLocation(
            latitude = latitude,
            longitude = longitude,
            address = null,
            isLoading = true,
            isOwnLocation = isOwnLocation,
            historicalTimestamp = historicalTimestamp,
            memberName = memberName
        )

        viewModelScope.launch {
            try {
                // Try offline geocoding first. When the SDK resolved a POI (the
                // tap landed within a few meters of one), prefer its name + the
                // derived street address over the basemap's own label, so the
                // placard shows e.g. "Togo's" with the street underneath.
                val result = geocodingRepository.reverseGeocode(latitude, longitude)
                val sdkPoiName = result
                    ?.takeIf { it.resultType == "poi" }
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                // Street + "City, State", composed from the structured fields —
                // reading `text` raw would make the locality depend on which
                // geocoder answered (tiles bake it in, the server geocoder does
                // not), and on a POI it would be dropped outright in favour of the
                // bare street. withPoiName stays false: the name has its own row
                // on this sheet.
                var addressText = result?.placardAddress()

                if (addressText == null && tileGeocodeFallback != null) {
                    addressText = tileGeocodeFallback()
                }

                // POI name: prefer the SDK's resolved POI, then the basemap tile
                // extractor, which covers POIs drawn on the map that aren't in the
                // geocoding data at all (e.g. "Yosemite Falls").
                val poiName = sdkPoiName ?: if (poiNameExtractor != null) {
                    try {
                        poiNameExtractor()
                    } catch (e: Exception) {
                        null
                    }
                } else null

                // With neither a name nor an address, both stay null: the sheet says what it is
                // (your location, a history point, no address), and nothing copies, prefills or
                // saves that label as if it were an address.

                _tappedLocation.value = TappedLocation(
                    latitude = latitude,
                    longitude = longitude,
                    name = poiName,
                    address = addressText,
                    isLoading = false,
                    isOwnLocation = isOwnLocation,
                    historicalTimestamp = historicalTimestamp,
                    memberName = memberName
                )
            } catch (e: Exception) {
                _tappedLocation.value = TappedLocation(
                    latitude = latitude,
                    longitude = longitude,
                    name = null,
                    address = null,
                    isLoading = false,
                    isOwnLocation = isOwnLocation,
                    historicalTimestamp = historicalTimestamp,
                    memberName = memberName
                )
            }
        }
    }

    fun clearTappedLocation() {
        _tappedLocation.value = null
    }

    /**
     * One-shot reverse geocode for the create-place flow: offline data first, then
     * the tile fallback, with any POI name prepended.
     *
     * Unlike [onMapTapped] this returns a single combined line rather than a
     * separate name and address, because it becomes the dialog's address field.
     */
    suspend fun geocodeLocation(
        latitude: Double,
        longitude: Double,
        tileGeocodeFallback: (suspend () -> String?)? = null,
        poiNameExtractor: (suspend () -> String?)? = null
    ): String? {
        var addressText = try {
            geocodingRepository.reverseGeocode(latitude, longitude)?.text
        } catch (e: Exception) {
            null
        }

        if (addressText == null && tileGeocodeFallback != null) {
            addressText = try {
                tileGeocodeFallback()
            } catch (e: Exception) {
                null
            }
        }

        val poiName = if (poiNameExtractor != null) {
            try {
                poiNameExtractor()
            } catch (e: Exception) {
                null
            }
        } else null

        return when {
            poiName != null && addressText != null -> "$poiName, $addressText"
            poiName != null -> poiName
            else -> addressText
        }
    }

    /**
     * True while the map is on screen (MapFragment onStart..onStop). This VM
     * is activity-scoped and outlives the map's view, so without it anything
     * that selects a circle in the background — the circle list re-emitting
     * after a refresh — would start the 30 s poll for a map nobody can see,
     * and nothing would stop it until the next onStop.
     */
    @Volatile private var mapVisible = false

    fun onMapVisibilityChanged(visible: Boolean) {
        mapVisible = visible
        if (!visible) stopPolling()
    }

    fun setSelectedCircle(circleId: String) {
        _selectedCircleId.value = circleId
        // A selection made while the map is hidden is only recorded: onResume
        // starts the poll when the map is actually showing again.
        if (mapVisible) startPolling()
    }

    fun clearSelectedCircle() {
        _selectedCircleId.value = null
        stopPolling()
        // Refresh personal places so they show on the map in "Just Me" mode
        refreshPersonalPlaces()
    }

    /**
     * Refresh personal places from the server.
     * Called when switching to "Just Me" mode so personal places appear on the map.
     */
    private fun refreshPersonalPlaces() {
        viewModelScope.launch {
            placeRepository.refreshPlaces(null) // null = personal places
        }
    }

    fun selectMember(memberId: String?) {
        _selectedMemberId.value = memberId
    }

    fun toggleShowMembers() {
        _showMembers.value = !_showMembers.value
    }

    fun toggleShowPlaces() {
        val newValue = !_showPlaces.value
        _showPlaces.value = newValue
        // When places are hidden, also hide boundaries (boundaries are a subset of places)
        if (!newValue) {
            _showBoundaries.value = false
        }
    }

    fun toggleShowSharedPlaces() {
        _showSharedPlaces.value = !_showSharedPlaces.value
    }

    fun toggleShowDestinations() {
        _showDestinations.value = !_showDestinations.value
    }

    fun toggleShowBoundaries() {
        _showBoundaries.value = !_showBoundaries.value
    }

    fun startPolling() {
        stopPolling()
        val circleId = _selectedCircleId.value ?: return

        pollingJob = viewModelScope.launch {
            _isPolling.value = true
            var tick = 0L
            while (isActive) {
                refreshLocationsInternal(circleId)
                if (tick % ALERT_CIRCLE_TICKS == 0L) {
                    refreshAlertCirclesOtherThan(circleId)
                }
                tick++
                delay(POLLING_INTERVAL_MS)
            }
        }
    }

    /**
     * Refresh member locations for circles this user holds place alerts on but is
     * NOT currently looking at.
     *
     * Without this, the map polls only the selected circle, so an alert homed to
     * any other circle is fed solely by the service's 5-minute background pass —
     * meaning having the app OPEN gives no better alert latency than having it
     * closed. With the map on a different circle, the alert's circle can be sampled
     * only twice in eight minutes, the crossing missed locally, and the generic
     * server push arrive instead of the local notification that names the person
     * and the place.
     *
     * Every SECOND tick, not every tick: the selected circle is what the user is
     * watching and keeps the full 30 s cadence, while these are a background
     * concern that still ends up 5× fresher than the service pass. Cost scales with
     * the number of alert circles, which is why they get half the rate rather than
     * all of them being refreshed alongside every selected-circle poll.
     *
     * Deliberately NOT routed through refreshLocationsInternal: that drives
     * user-facing state (the refreshing spinner, last-refresh time, decryption
     * warnings), and a circle the user isn't viewing must not move any of it. The
     * refresh still runs subscriber-side evaluation, because LocationRepository
     * evaluates every location it persists — the same reason the background pass
     * needs no explicit evaluate call.
     */
    internal suspend fun refreshAlertCirclesOtherThan(selectedCircleId: String) {
        val others = try {
            placeRepository.activeAlertCircleIds()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read alert circles for the foreground pass", e)
            return
        }.filter { it != selectedCircleId }

        for (cid in others) {
            runCatching { locationRepository.refreshLatestLocations(cid) }
                .onFailure { Log.d(TAG, "Alert-circle refresh failed for $cid: ${it.message}") }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _isPolling.value = false
    }

    fun refreshLocations() {
        val circleId = _selectedCircleId.value ?: return
        viewModelScope.launch {
            refreshLocationsInternal(circleId)
        }
    }

    private suspend fun refreshLocationsInternal(circleId: String) {
        _isRefreshingLocations.value = true
        try {
        var locationsResult = locationRepository.refreshLatestLocations(circleId)

        // Before telling the user anything couldn't be shown, try the one thing
        // that fixes it. Two shapes reach here for the same underlying reason —
        // rows that wouldn't decrypt, and CircleKeyUnavailableException, which
        // the repository raises BEFORE opening a socket when it holds no key for
        // the circle at all. The second is the commonest: a circle joined on
        // another device, which this screen has no other way to pick up.
        //
        // Retry only when keys actually arrived; otherwise it would decrypt the
        // same rows with the same keys and fail identically.
        val keysWouldHelp = (locationsResult.getOrNull()?.keyMissingCount ?: 0) > 0 ||
            locationsResult.exceptionOrNull() is CircleKeyUnavailableException
        if (keysWouldHelp && circleRepository.ensureCircleKey(circleId)) {
            locationsResult = locationRepository.refreshLatestLocations(circleId)
        }

        locationsResult.onSuccess { fetchResult ->
            _lastRefreshTime.value = System.currentTimeMillis()
            _lastRefreshFailedAt.value = null

            // Surface decryption warnings to UI
            when {
                fetchResult.hasPartialSuccess -> {
                    // Partial success is expected after key rotation — some locations
                    // were encrypted with a previous key version. Only warn if keys are missing.
                    if (fetchResult.keyMissingCount > 0) {
                        _decryptionWarning.value = DecryptionWarning(
                            waitingCount = fetchResult.keyMissingCount,
                            successCount = fetchResult.successCount,
                            failureCount = fetchResult.keyMissingCount,
                            isKeyMissing = true
                        )
                        importKeysSoon()
                        consecutiveDecryptOnlyFailures = 0
                    } else {
                        _decryptionWarning.value = null
                        consecutiveDecryptOnlyFailures = 0
                    }
                }
                fetchResult.allFailed -> {
                    if (fetchResult.keyMissingCount > 0) {
                        // Nothing for the person to do: importKeysSoon fetches the
                        // missing key version by itself.
                        _decryptionWarning.value = DecryptionWarning(
                            waitingCount = null,
                            successCount = 0,
                            failureCount = fetchResult.totalReceived,
                            isKeyMissing = true
                        )
                        importKeysSoon()
                        consecutiveDecryptOnlyFailures = 0
                    } else {
                        // Key is present but every payload still
                        // failed to decrypt — these are
                        // unrecoverable stale rows in a much older
                        // format (the SDK already tries every
                        // known circle-key version). The server
                        // replaces them on the next upload under
                        // the same pseudonym, but after a
                        // pseudonym rotation the older rows stay
                        // orphaned for good. There's nothing the
                        // user can do, so suppress the warning
                        // entirely instead of nagging on every
                        // poll.
                        consecutiveDecryptOnlyFailures += 1
                        _decryptionWarning.value = null
                    }
                }
                else -> {
                    _decryptionWarning.value = null
                    consecutiveDecryptOnlyFailures = 0
                }
            }
        }

        // Flag a network-level failure so the header strip can show the "showing
        // last known" warning until a future poll succeeds — but ONLY for failures
        // that really are about the network. The two below are not, and reporting
        // either as "Couldn't reach server" points the user at the wrong remedy.
        locationsResult.onFailure { e ->
            when (e) {
                // A real "circle is gone" 404, not a connectivity problem.
                is CircleNotFoundException -> {}

                // No key for this circle ON THIS DEVICE. The repository returns this
                // BEFORE it opens a socket, so the network is not the problem;
                // surface the same actionable warning the key-missing decrypt path
                // uses instead.
                is CircleKeyUnavailableException -> {
                    // Clear any flag left by an EARLIER failure. Without this a
                    // keyless circle keeps a stale banner forever: the flag is
                    // only cleared on a successful refresh (see onSuccess), and
                    // a circle with no key never has one.
                    _lastRefreshFailedAt.value = null
                    _decryptionWarning.value = DecryptionWarning(
                        waitingCount = null,
                        successCount = 0,
                        failureCount = 0,
                        isKeyMissing = true
                    )
                    importKeysSoon()
                }

                else -> _lastRefreshFailedAt.value = System.currentTimeMillis()
            }
        }

        placeRepository.refreshPlaces(circleId)
        circleRepository.refreshMembers(circleId)
        } finally {
            _isRefreshingLocations.value = false
        }
    }

    /**
     * Clear the decryption warning after user dismisses it.
     */
    fun clearDecryptionWarning() {
        _decryptionWarning.value = null
    }

    /** [userId]'s name, or "Unknown" (kept in English for the exported file; the screen asks [memberDisplayName]). */
    fun getMemberName(userId: String): String = memberDisplayName(userId) ?: UNKNOWN_NAME

    /** [userId]'s name as this phone knows it, or null: the screen then says member_unknown in its language. */
    fun memberDisplayName(userId: String): String? {
        members.value.find { it.userId == userId }?.displayName?.let { return it }
        // Own-user fast path: the members flow hasn't fanned out yet on a
        // cold map open, but SessionManager has cached our display name
        // (and email) locally — fall back to that so the own marker's
        // initial doesn't render as "U" (from "Unknown") for the first
        // second or two until the circle members list arrives.
        if (userId == sessionManager.getUserId()) {
            sessionManager.getDisplayName()?.takeIf { it.isNotBlank() }?.let { return it }
            sessionManager.getEmail()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun isCurrentUser(userId: String): Boolean {
        // Any spelling of this account's id is this account.
        val me = sessionManager.getUserId()
        return userId == me || com.pingolin.track.util.AccountIds.same(userId, me)
    }

    /**
     * On every member list read of the selected circle: whoever keeps their location history from
     * it loses their trail from the history held here (level-triggered, so whatever a read
     * brought before is gone too) and the addresses of where they were, all but where they are
     * now. Their live position is not history and stays. When the list shows someone's history
     * again, a time range on screen is read again: the server brings it all back, the time it was
     * off included. A live map has nothing to read again.
     */
    private fun onRosterForHistory(roster: List<CircleMemberEntity>) {
        val circleId = roster.firstOrNull()?.circleId ?: return
        val hidden = roster
            .filter { com.pingolin.track.ui.circles.MemberSharing.historyOff(it) && !isCurrentUser(it.userId) }
            .mapTo(HashSet()) { it.userId }
        _locationHistory.update { withoutHiddenHistories(it, hidden) }
        forgetHiddenAddresses(hidden)
        val before = _hiddenHistory.value
        _hiddenHistory.value = circleId to hidden
        val shownAgain = before != null && before.first == circleId && (before.second - hidden).isNotEmpty()
        if (shownAgain && isShowingHistory() && _selectedCircleId.value == circleId) {
            fetchLocationHistory(_timeRangeMode.value)
        }
    }

    /** Who keeps their history from [circleId], as the member list last said: none for another circle. */
    private fun hiddenHistoryIn(circleId: String): Set<String> =
        _hiddenHistory.value?.takeIf { it.first == circleId }?.second.orEmpty()

    /** [history] without the trail of anyone in [hidden], in any spelling of their id; the same map when nothing goes. */
    private fun withoutHiddenHistories(
        history: Map<String, List<LocationEntity>>,
        hidden: Set<String>,
    ): Map<String, List<LocationEntity>> {
        if (hidden.isEmpty()) return history
        val kept = history.filterKeys { key -> hidden.none { it == key || com.pingolin.track.util.AccountIds.same(it, key) } }
        return if (kept.size == history.size) history else kept
    }

    /** The addresses of where [hidden] were, all but where the map shows them now. */
    private fun forgetHiddenAddresses(hidden: Set<String>) {
        if (hidden.isEmpty()) return
        val now = memberLocations.value
            .filter { loc -> hidden.any { it == loc.userId || com.pingolin.track.util.AccountIds.same(it, loc.userId) } }
        val keep = now.mapTo(HashSet()) { memberAddressKey(it.userId, it.latitude, it.longitude) }
        val spellings = hidden + now.map { it.userId }
        synchronized(memberAddressCache) {
            memberAddressCache.keys.removeAll { key -> key !in keep && spellings.any { key.startsWith("$it-", ignoreCase = true) } }
        }
    }

    /**
     * Whether the map draws [userId] where they were when they turned real-time sharing off in
     * the selected circle: greyed, labelled "Location sharing off", and never at a place, an
     * SOS's marker or a place pin's cover. The member list says they're off here, so any row of
     * theirs on the map is the server's frozen one ([memberLocations] leaves their live rows
     * out).
     */
    fun drawsFrozen(userId: String): Boolean {
        if (isCurrentUser(userId)) return false
        val circleId = _selectedCircleId.value ?: return false
        val member = members.value.firstOrNull {
            it.circleId == circleId && (it.userId == userId || com.pingolin.track.util.AccountIds.same(it.userId, userId))
        } ?: return false
        return com.pingolin.track.ui.circles.MemberSharing.status(member, System.currentTimeMillis()) ==
            com.pingolin.track.ui.circles.SharingStatus.Off
    }

    /** Whether the latest read left a frozen row of [userId] in [circleId] to show. */
    fun hasFrozenRow(circleId: String, userId: String): Boolean =
        locationRepository.frozenLocations.value[circleId].orEmpty()
            .any { it.userId == userId || com.pingolin.track.util.AccountIds.same(it.userId, userId) }

    /**
     * Whether the member list says [userId] keeps their location history from the selected
     * circle, for the history view's line in place of their trail.
     */
    fun historyHiddenFor(userId: String): Boolean {
        val (circleId, hidden) = _hiddenHistory.value ?: return false
        return circleId == _selectedCircleId.value && hidden.any { it == userId || com.pingolin.track.util.AccountIds.same(it, userId) }
    }

    /**
     * Whether [userId] keeps their location history from [circleId], for the member card, from the
     * stored list for THIS circle as [sharingStatus] reads it. Never this account.
     */
    suspend fun historyHidden(circleId: String, userId: String): Boolean {
        if (isCurrentUser(userId)) return false
        val member = try { circleRepository.member(circleId, userId) } catch (_: Exception) { null } ?: return false
        return com.pingolin.track.ui.circles.MemberSharing.historyOff(member)
    }

    /**
     * Get a consistent color for a member based on their index in the member list.
     */
    fun getMemberColor(userId: String): Int {
        val index = members.value.indexOfFirst { it.userId == userId }
        return if (index >= 0) {
            MEMBER_COLORS[index % MEMBER_COLORS.size]
        } else {
            MEMBER_COLORS[0]
        }
    }

    /**
     * Set a custom date range and fetch history for it.
     */
    fun setCustomTimeRange(startMillis: Long, endMillis: Long) {
        customStartTime = startMillis
        customEndTime = endMillis
        setTimeRangeMode(TimeRangeMode.CUSTOM)
    }

    /**
     * Set the time range mode and fetch location history if not live mode.
     */
    fun setTimeRangeMode(mode: TimeRangeMode) {
        _timeRangeMode.value = mode

        if (mode == TimeRangeMode.LIVE) {
            // Clear history and resume live polling
            _locationHistory.value = emptyMap()
            startPolling()
        } else {
            // Stop polling and fetch history
            stopPolling()
            fetchLocationHistory(mode)
        }
    }

    /**
     * Fetch location history based on the selected time range.
     *
     * When a shared circle is selected we ALSO fetch the viewer's self-tracking
     * history (SELF_CIRCLE) in parallel and merge it in, because self-tracked
     * points are stored under the nil-UUID circle and never appear in a shared
     * circle's rows. Without this, the user's own 7d trail shows only what they
     * shared with the selected circle — typically the last day or so — even when
     * a full 7 days of self-tracking history exists on the server.
     *
     * One request per circle covers the whole selected range — the server
     * downsamples rather than paginating, so there is no second page to ask for.
     */
    private fun fetchLocationHistory(mode: TimeRangeMode) {
        // Use selected circle or fall back to self-tracking for "Just Me" mode
        val circleId = _selectedCircleId.value ?: LocationRepository.SELF_CIRCLE_ID
        val shouldFetchSelfAlongside = circleId != LocationRepository.SELF_CIRCLE_ID

        android.util.Log.d("MapViewModel", "fetchLocationHistory: mode=$mode, circleId=$circleId, fetchSelf=$shouldFetchSelfAlongside")

        viewModelScope.launch {
            _isLoadingHistory.value = true

            try {
                val (startTime, endTime) = getTimeRange(mode)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val startIso = dateFormat.format(Date(startTime))
                val endIso = dateFormat.format(Date(endTime))

                android.util.Log.d("MapViewModel", "fetchLocationHistory: startTime=$startIso, endTime=$endIso")

                // Clear the previous range's truncation notice before asking; it
                // belongs to the range it came from, not to this one.
                _locationHistoryTruncated.value = false

                val circleDeferred = async {
                    locationRepository.fetchLocationHistory(
                        circleId = circleId,
                        startTime = startIso,
                        endTime = endIso
                    )
                }
                val selfDeferred = if (shouldFetchSelfAlongside) {
                    async {
                        locationRepository.fetchLocationHistory(
                            circleId = LocationRepository.SELF_CIRCLE_ID,
                            startTime = startIso,
                            endTime = endIso
                        )
                    }
                } else null

                val circleResult = circleDeferred.await()
                val selfResult = selfDeferred?.await()

                if (circleResult.isSuccess) {
                    val historyResult = circleResult.getOrNull()!!

                    // Either fetch can come back short of the range: the trail is
                    // drawn from both, so either one's loss is the user's.
                    _locationHistoryTruncated.value =
                        historyResult.truncated || selfResult?.getOrNull()?.truncated == true

                    // Filter to the exact window, then group circle + self by
                    // userId (dedupe on timestamp so the viewer's points — which
                    // may appear in both streams — don't duplicate in the trail).
                    // Run off the Main thread: this filter/groupBy/distinctBy/
                    // sortedBy sweeps the whole response, which the server's point
                    // budget puts at up to ~5,000 points per circle.
                    val combined = withContext(historyComputeDispatcher) {
                        val circleLocations = historyResult.locations.filter {
                            it.timestamp in startTime..endTime
                        }
                        val selfLocations = selfResult?.getOrNull()?.locations
                            ?.filter { it.timestamp in startTime..endTime }
                            ?: emptyList()
                        android.util.Log.d("MapViewModel", "fetchLocationHistory: circle=${historyResult.locations.size}→${circleLocations.size}, self=${selfLocations.size}")
                        (circleLocations + selfLocations)
                            .groupBy { it.userId }
                            .mapValues { (_, locs) ->
                                locs.distinctBy { it.timestamp }.sortedBy { it.timestamp }
                            }
                    }

                    // Never a trail of someone hiding their history, whatever the
                    // read brought while they turned it off.
                    _locationHistory.value = withoutHiddenHistories(combined, hiddenHistoryIn(circleId))
                    android.util.Log.d("MapViewModel", "fetchLocationHistory: grouped into ${_locationHistory.value.size} users")
                } else {
                    android.util.Log.e("MapViewModel", "fetchLocationHistory: failed - ${circleResult.exceptionOrNull()?.message ?: "Unknown error"}")
                }
            } catch (e: Exception) {
                android.util.Log.e("MapViewModel", "fetchLocationHistory: exception", e)
            } finally {
                _isLoadingHistory.value = false
            }
        }
    }

    /**
     * Get start and end timestamps for the given time range mode.
     */
    private fun getTimeRange(mode: TimeRangeMode): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        val startTime = when (mode) {
            TimeRangeMode.LIVE -> now - 60_000 // Last minute for live
            TimeRangeMode.HOUR_1 -> now - (60 * 60 * 1000)
            TimeRangeMode.HOURS_24 -> now - (24 * 60 * 60 * 1000)
            TimeRangeMode.TODAY -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            TimeRangeMode.DAYS_7 -> now - (7 * 24 * 60 * 60 * 1000)
            TimeRangeMode.CUSTOM -> customStartTime
        }

        val endTime = if (mode == TimeRangeMode.CUSTOM) customEndTime else now
        return Pair(startTime, endTime)
    }

    fun isShowingHistory(): Boolean = _timeRangeMode.value != TimeRangeMode.LIVE

    /**
     * Where a "Send location now" goes, taken when it is asked for: the circle on screen then
     * ([circleId], named [circleName] as the screen showed it), or with none (Just Me) this account's
     * own history. Never what is on screen once the precise fix comes in, seconds later, when the circle
     * on screen may have changed or gone.
     */
    data class SendTarget(val circleId: String?, val circleName: String? = null)

    /**
     * Push a one-shot location upload, rate limited to once every 15 seconds.
     * Encrypted to the selected circle, or stored locally in "Just Me" mode.
     *
     * @param silent skip all user-visible state (success toast, rate-limit notice,
     *   error snackbar) and don't start the visible cooldown timer — for the
     *   refresh-on-resume path, which must not pop a "Location sent" toast every
     *   time the app foregrounds.
     */
    fun sendLocationNow(location: android.location.Location?, target: SendTarget, silent: Boolean = false) {
        if (location == null) {
            if (!silent) _sendLocationResult.value = SendLocationResult.Error(null, com.pingolin.track.R.string.map_location_not_available)
            return
        }

        // Check rate limit
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastSendLocationTime
        if (timeSinceLastSend < SEND_LOCATION_RATE_LIMIT_MS) {
            if (!silent) {
                val waitSeconds = ((SEND_LOCATION_RATE_LIMIT_MS - timeSinceLastSend) / 1000).toInt() + 1
                _sendLocationResult.value = SendLocationResult.RateLimited(waitSeconds)
            }
            return
        }

        viewModelScope.launch {
            _isSendingLocation.value = true
            try {
                val circleId = target.circleId
                // A circle no longer this account's by now gets nothing, and nothing goes
                // elsewhere in its place: not to Just Me, not to whichever circle is on screen
                // now.
                if (circleId != null && circleRepository.getCircle(circleId) == null) {
                    if (!silent) _sendLocationResult.value = SendLocationResult.CircleGone(target.circleName)
                    return@launch
                }
                // With this phone's battery level, as the tracking service's sends carry it:
                // a watcher judges the newest location, so one sent with none here, at every
                // glance at the map, would keep a low-battery alert quiet. Whether the
                // circle gets it is its own setting, applied where the location is sealed
                // (LocationRepository.sendLocation).
                val batteryLevel = battery.now()

                if (circleId != null) {
                    // Send to selected circle
                    val result = locationRepository.sendLocation(
                        circleId = circleId,
                        location = location,
                        batteryLevel = batteryLevel
                    )

                    // Kept on the phone rather than sent: say so instead
                    // of "sent".
                    val waiting = circleRepository.behindCircles.value.contains(circleId) ||
                        circleRepository.recordsBehindCircles.value.contains(circleId) ||
                        result.exceptionOrNull() is CircleKeyUnavailableException ||
                        result.getOrNull() == SendOutcome.WAITING_FOR_CIRCLE
                    if (waiting) {
                        if (!silent) {
                            _sendLocationResult.value = SendLocationResult.Waiting(circleId, catchingUp = true)
                        }
                    } else if (result.isSuccess) {
                        // Said as it happened: sent to the circle on screen, kept to
                        // go when it can, or not sent because sharing there is
                        // paused or off.
                        val outcome = result.getOrNull()
                        val notSharing = outcome == SendOutcome.PAUSED || outcome == SendOutcome.REALTIME_OFF
                        if (!notSharing) lastSendLocationTime = System.currentTimeMillis()
                        if (!silent) {
                            _sendLocationResult.value = when (outcome) {
                                SendOutcome.PAUSED -> SendLocationResult.NotSharing(circleId, paused = true)
                                SendOutcome.REALTIME_OFF -> SendLocationResult.NotSharing(circleId, paused = false)
                                SendOutcome.QUEUED -> SendLocationResult.Waiting(circleId)
                                else -> SendLocationResult.Success(circleId)
                            }
                            if (!notSharing) startCooldownTimer()
                        }
                    } else if (!silent) {
                        // In this app's words only (PlainText.kt): never the SDK's key talk, nor a refused
                        // certificate's "…has a key known to be compromised" (CompromisedKeyTrust).
                        _sendLocationResult.value = SendLocationResult.Error(
                            result.exceptionOrNull()?.plainTextOrNull()
                        )
                    }
                } else {
                    // Self-tracking mode - send encrypted location to server
                    val result = locationRepository.sendSelfTrackingLocation(
                        location = location,
                        batteryLevel = batteryLevel
                    )

                    if (result.isSuccess) {
                        lastSendLocationTime = System.currentTimeMillis()
                        if (!silent) {
                            // Just Me: this account's own history, which
                            // nobody else sees; saved, or kept to be saved
                            // when it can.
                            _sendLocationResult.value = if (result.getOrNull() == SendOutcome.QUEUED) {
                                SendLocationResult.Waiting(circleId = null)
                            } else {
                                SendLocationResult.Success(circleId = null)
                            }
                            startCooldownTimer()
                        }
                    } else if (!silent) {
                        _sendLocationResult.value = SendLocationResult.Error(
                            result.exceptionOrNull()?.plainTextOrNull()
                        )
                    }
                }
            } catch (e: Exception) {
                if (!silent) _sendLocationResult.value = SendLocationResult.Error(e.plainTextOrNull())
            } finally {
                _isSendingLocation.value = false
            }
        }
    }

    /**
     * Start cooldown timer to update UI.
     */
    private fun startCooldownTimer() {
        viewModelScope.launch {
            val endTime = lastSendLocationTime + SEND_LOCATION_RATE_LIMIT_MS
            while (System.currentTimeMillis() < endTime) {
                _sendLocationCooldown.value = endTime - System.currentTimeMillis()
                delay(1000)
            }
            _sendLocationCooldown.value = 0
        }
    }

    /**
     * Clear the send location result after displaying.
     */
    fun clearSendLocationResult() {
        _sendLocationResult.value = null
    }

    /**
     * Check if send location button should be enabled.
     */
    fun canSendLocation(): Boolean {
        return _sendLocationCooldown.value == 0L && !_isSendingLocation.value
    }

    /**
     * Toggle continuous tracking on/off.
     * Returns the new tracking state so the Fragment can start/stop the service.
     */
    fun toggleTracking(): Boolean {
        val newState = !_isTrackingEnabled.value
        _isTrackingEnabled.value = newState
        return newState
    }

    /**
     * Set tracking enabled state (called from service binding).
     */
    fun setTrackingEnabled(enabled: Boolean) {
        _isTrackingEnabled.value = enabled
    }

    /**
     * Create a personal place from map long-press location.
     * Places are always personal - they can be shared to circles later.
     */
    fun createPlaceFromMap(
        name: String,
        notes: String? = null,
        address: String? = null,
        latitude: Double,
        longitude: Double,
        icon: String? = null,
        color: String? = null,
        placeType: String = "place",
        boundaryRadius: Int? = null
    ) {
        viewModelScope.launch {
            val result = placeRepository.createPersonalPlace(
                name = name,
                notes = notes,
                address = address,
                latitude = latitude,
                longitude = longitude,
                radius = 100, // Default radius (backend requires 50-2000 meters)
                icon = icon,
                color = color,
                placeType = placeType,
                boundaryRadius = boundaryRadius
            )
            _createPlaceResult.value = result.fold(
                onSuccess = { CreatePlaceResult.Success(it.id) },
                onFailure = { CreatePlaceResult.Error(it.plainTextOrNull(), it.quotaError, it.premiumErrorCode) }
            )
        }
    }

    /**
     * Clear the create place result after displaying.
     */
    fun clearCreatePlaceResult() {
        _createPlaceResult.value = null
    }

    // --- Coordinate Export ---

    /**
     * Build a JSON string of coordinates from current location history.
     * Runs on Dispatchers.IO to avoid blocking the main thread with large JSON building.
     * @param memberFilter "__all__" for all, "__you__" for current user, or a specific userId
     * @return Pair of (jsonString, pointCount)
     */
    suspend fun getExportCoordinates(memberFilter: String): Pair<String, Int> = withContext(Dispatchers.IO) {
        val history = _locationHistory.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val currentUserId = sessionManager.getUserId()

        val entries = mutableListOf<JSONObject>()

        val filteredHistory = when (memberFilter) {
            "__all__" -> history
            "__you__" -> history.filterKeys { it == currentUserId }
            else -> history.filterKeys { it == memberFilter }
        }

        for ((userId, locations) in filteredHistory) {
            val memberName = if (userId == currentUserId) "You" else getMemberName(userId)
            for (loc in locations) {
                entries.add(JSONObject().apply {
                    put("member", memberName)
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("timestamp", dateFormat.format(Date(loc.timestamp)))
                })
            }
        }

        entries.sortBy { it.getString("timestamp") }
        val jsonArray = JSONArray(entries)
        Pair(jsonArray.toString(2), entries.size)
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
