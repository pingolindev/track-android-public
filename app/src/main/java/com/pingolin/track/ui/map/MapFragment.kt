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

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.dto.premiumErrorCode
import com.pingolin.track.data.remote.dto.quotaError
import com.pingolin.track.databinding.FragmentMapBinding
import com.pingolin.track.databinding.DialogCreatePlaceBinding
import com.pingolin.track.databinding.DialogCoordinateExportBinding
import com.pingolin.track.databinding.DialogCreateDestinationBinding
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.service.LocationTrackingService
import com.pingolin.track.ui.main.MainViewModel
import com.pingolin.track.ui.places.showPlaceAlertEditor
import com.pingolin.track.util.PlaceIconUtils
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainText
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import com.pingolin.track.BuildConfig
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import javax.inject.Inject
import javax.inject.Named
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Lightweight tuple used by the history-trail observer to combine four flows. */
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** Coarse state of the map's refresh-status pill. */
private enum class RefreshPillState { UPDATING, FAILED, HIDDEN }

/**
 * How long a refresh failure must persist before the red "couldn't reach server" pill is
 * shown. Opening the app or switching back to it often drops and re-establishes the
 * connection within a second or two; debouncing keeps those blips from flashing the pill.
 */
private const val FAILURE_PILL_GRACE_MS = 4000L

@AndroidEntryPoint
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    /** Tapped-location detail sheet (bottom sheet, mirrors the Place/Member sheets).
     *  Driven by [MapViewModel.tappedLocation]: created on first tap, updated in place
     *  as the address geocodes, dismissed when the tapped state clears. */
    private var tappedLocationSheet: BottomSheetDialog? = null

    // Activity-scoped so the decrypted member locations/members survive a tab
    // switch (the bottom nav recreates this fragment). On return, the cached
    // markers repaint as soon as the style is ready instead of waiting on a
    // fresh network fetch + ~5s SDK re-decrypt. Polling is tied to the view
    // lifecycle below (onResume starts, onStop stops) so it doesn't run while
    // the map isn't visible.
    private val viewModel: MapViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()

    /**
     * Backs the place-alert editor opened from the map's place sheet.
     *
     * activityViewModels (not viewModels) so it is the SAME instance the Places
     * screen uses: the editor reads `placeAlertsMap` to pre-fill an existing
     * alert, and that map is populated by PlacesViewModel's refresh. A
     * fragment-scoped instance would start empty, so opening the editor from the
     * map would offer to CREATE an alert that already exists and then replace it.
     */
    private val placesViewModel: com.pingolin.track.ui.places.PlacesViewModel by activityViewModels()

    // Place ids THIS user has an active alert on (per-user). Drives the per-user
    // geofence ring in updateBoundaryCircles; kept in sync by an observer on
    // placeRepository.observeAlertedPlaceIds().
    private var viewerAlertPlaceIds: Set<String> = emptySet()

    @Inject
    lateinit var sessionManager: SessionManager

    /** The sync error's banner (KeyStoreRecovery): stored keys that need a sign-in. */
    @Inject
    lateinit var keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery

    /** App-wide EncryptedSharedPreferences (AES-256-GCM, Keystore-backed) from
     *  AppModule — the same encrypted store used for the SDK's crypto keys. Used
     *  here to persist the user's own last coordinates (a secret at rest). */
    @Inject
    lateinit var encryptedPrefs: SharedPreferences

    @Inject
    lateinit var premiumStatusRefresher: com.pingolin.track.util.PremiumStatusRefresher

    /** Who shares only an approximate location, and how large their area is. */
    @Inject
    lateinit var approximateMembers: com.pingolin.track.util.ApproximateMembersStore

    @Inject
    lateinit var checkInRepository: CheckInRepository

    @Inject
    lateinit var destinationRepository: DestinationRepository

    /** For the inline Place Visibility section in the create-from-map
     *  dialog — queued shares drain into sharePlaceWithCircle after the
     *  place itself is created. */
    @Inject
    lateinit var placeRepository: com.pingolin.track.domain.repository.PlaceRepository

    @Inject
    @Named("mapClient")
    lateinit var mapOkHttpClient: okhttp3.OkHttpClient

    private var mapLibreMap: MapLibreMap? = null
    private var mapStyle: Style? = null
    /** The style, but only while it is still the live one and fully loaded. Every
     *  source/layer update in this file goes through it. */
    private val safeStyle: Style?
        get() = mapStyle?.takeIf { it.isFullyLoaded }
    private var currentStyleUrl: String? = null
    private var locationComponentActivated = false
    // True when we've already pointed the camera at a user-requested target
    // (e.g., tapping a row in Places) and the upcoming
    // enableLocationComponent activation should NOT silently fly back to
    // the user's GPS. Reset on view destroy / next setupMap.
    private var consumedPendingZoom = false

    /** Fused on gms, android.location.LocationManager on libre. */
    @Inject
    lateinit var locationProvider: com.pingolin.track.location.LocationProvider

    /** Who is responding to my own request, by name. */
    @Inject
    lateinit var myAlertResponders: com.pingolin.track.domain.repository.MyAlertResponders
    private var hasInitiallyZoomedToMembers = false
    // Cold start only: center the map on the user's own location (seeded from
    // the last-known self loc to avoid a world-view flash, then refined to live
    // GPS). Stays false on a tab return so the current view is preserved.
    private var coldStartRecenterSelf = false
    // True when the most recently started camera move was a user touch gesture
    // (vs a programmatic move); gates saving the camera for tab-return restore.
    private var lastCameraMoveWasGesture = false

    /**
     * The user's own last coordinates are a secret at rest: they belong in the
     * app's EncryptedSharedPreferences, never a plaintext prefs file. A
     * plaintext "map_view" file written by older app versions may still hold
     * them, so anything still there is migrated into the encrypted store and
     * purged on first access.
     */
    private val legacyCameraPrefs by lazy {
        requireContext().getSharedPreferences("map_view", android.content.Context.MODE_PRIVATE)
    }
    private fun persistSelfLoc(lat: Double, lng: Double) {
        encryptedPrefs.edit().putString(KEY_SELF_LOC, "$lat,$lng").apply()
    }

    private var lastPersistedSelfLoc: Pair<Double, Double>? = null
    private var lastPersistedSelfLocAtMs = 0L

    /** [persistSelfLoc], unless [shouldPersistSelfLoc] says the stored seed is still good. */
    private fun maybePersistSelfLoc(lat: Double, lng: Double) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!shouldPersistSelfLoc(lastPersistedSelfLoc, lastPersistedSelfLocAtMs, lat, lng, now)) return
        persistSelfLoc(lat, lng)
        lastPersistedSelfLoc = lat to lng
        lastPersistedSelfLocAtMs = now
    }
    private fun loadSelfLoc(): Pair<Double, Double>? {
        val raw = encryptedPrefs.getString(KEY_SELF_LOC, null)
            ?: migrateLegacySelfLoc()
            ?: return null
        val parts = raw.split(",")
        if (parts.size != 2) return null
        return try {
            val lat = parts[0].toDouble()
            val lng = parts[1].toDouble()
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) null else lat to lng
        } catch (_: Exception) {
            null
        }
    }
    /** One-time move of any cleartext self_loc from the legacy "map_view" file
     *  into the encrypted store, then wipe the plaintext copy. */
    private fun migrateLegacySelfLoc(): String? {
        val legacy = legacyCameraPrefs.getString("self_loc", null) ?: return null
        encryptedPrefs.edit().putString(KEY_SELF_LOC, legacy).apply()
        legacyCameraPrefs.edit().remove("self_loc").apply()
        return legacy
    }
    private var statusBarInsetPx = 0

    /** The location line's own slot, under the guardian and sync-error lines. */
    private var locationOffSlotPx = 0

    /** The selector's top, which the top-left column hangs from: the layout's own until the insets come. */
    private var stackBaseTopPx = 0

    /** The map's top-left column, measured: placed again whenever any part of it changes. */
    private var topLeftStack: TopLeftStack? = null

    companion object {
        /** A move beyond this always refreshes the persisted cold-start seed. */
        internal const val SELF_LOC_PERSIST_MIN_MOVE_M = 10.0

        /** A smaller move is persisted once this long has passed since the last write. */
        internal const val SELF_LOC_PERSIST_MIN_INTERVAL_MS = 60_000L

        /**
         * Should the cold-start seed be rewritten for a new own position?
         *
         * It is only where the map opens next time, so metres of drift don't need a
         * write each — but the first position of a map session always does, and a
         * position that has not moved never does.
         */
        internal fun shouldPersistSelfLoc(
            last: Pair<Double, Double>?,
            lastAtMs: Long,
            lat: Double,
            lng: Double,
            nowMs: Long,
        ): Boolean {
            if (last == null) return true
            val metres = com.pingolin.track.domain.placealerts.PlaceAlertRules
                .distanceMeters(last.first, last.second, lat, lng)
            if (metres > SELF_LOC_PERSIST_MIN_MOVE_M) return true
            return metres > 1.0 && nowMs - lastAtMs >= SELF_LOC_PERSIST_MIN_INTERVAL_MS
        }

        // Zoom used when centering on the user's own location — both the "my
        // location" button and the cold-start (app-open) recenter. 14 is
        // close-neighborhood: enough context to see nearby streets without
        // landing at house level (15 is claustrophobic, 13 shows a quarter of
        // the city). Shared so app-open matches pressing the button.
        private const val SELF_LOCATION_ZOOM = 14.0

        // How long a "show this member" tap waits for the member's position to
        // decrypt before giving up and telling the user there isn't one. Long
        // enough to cover a cold open (the Room flow's first emission lags
        // several seconds — see MapViewModel.memberLocations), short enough
        // that a member who never shares doesn't hang the screen.
        private const val MEMBER_FOCUS_TIMEOUT_MS = 8_000L
        private const val KEY_SELF_LOC = "self_loc"
        private const val TRAILS_SOURCE_ID = "trails-source"
        private const val TRAILS_LAYER_ID = "trails-layer"
        private const val POINTS_SOURCE_ID = "points-source"
        private const val POINTS_LAYER_ID = "points-layer"
        private const val ARROWS_SOURCE_ID = "arrows-source"
        private const val ARROWS_LAYER_ID = "arrows-layer"
        private const val ARROW_ICON_ID = "arrow-icon"
        private const val TAPPED_MARKER_SOURCE_ID = "tapped-marker-source"
        private const val TAPPED_MARKER_LAYER_ID = "tapped-marker-layer"
        private const val PLACES_SOURCE_ID = "places-source"
        private const val PLACES_LAYER_ID = "places-layer"
        private const val PLACES_LABEL_LAYER_ID = "places-label-layer"
        private const val PLACE_ICON_ID = "place-icon"
        private const val BOUNDARY_CIRCLES_SOURCE_ID = "boundary-circles-source"
        private const val BOUNDARY_CIRCLES_FILL_LAYER_ID = "boundary-circles-fill-layer"
        private const val BOUNDARY_CIRCLES_STROKE_LAYER_ID = "boundary-circles-stroke-layer"
        private const val DESTINATIONS_SOURCE_ID = "destinations-source"
        private const val DESTINATIONS_LAYER_ID = "destinations-layer"
        private const val DESTINATIONS_LABEL_LAYER_ID = "destinations-label-layer"
        // Two pulse halos so a destination that lands on a saved Place pulses
        // differently from an ad-hoc location (see updateDestinationMarkers +
        // startDestinationPulse).
        private const val DESTINATIONS_PULSE_PLACE_LAYER_ID = "destinations-pulse-place-layer"
        private const val DESTINATIONS_PULSE_ADHOC_LAYER_ID = "destinations-pulse-adhoc-layer"
        private const val MEMBERS_SOURCE_ID = "members-source"
        private const val MEMBERS_LAYER_ID = "members-layer"
        private const val MEMBERS_ACCURACY_LAYER_ID = "members-accuracy-layer"
        // The area an approximate member is drawn as, under the member layers.
        private const val MEMBERS_AREA_SOURCE_ID = "members-area-source"
        private const val MEMBERS_AREA_FILL_LAYER_ID = "members-area-fill-layer"
        private const val MEMBERS_AREA_LINE_LAYER_ID = "members-area-line-layer"
        private const val FAN_SOURCE_ID = "members-fan-source"
        private const val FAN_LINE_LAYER_ID = "members-fan-line-layer"
        private const val FAN_MARKER_LAYER_ID = "members-fan-marker-layer"
        private const val MEMBERS_AGE_LAYER_ID = "members-age-layer"
        private const val MEMBERS_CLUSTER_LAYER_ID = "members-cluster-layer"
        private const val MEMBERS_CLUSTER_COUNT_LAYER_ID = "members-cluster-count-layer"
        private const val SOS_SOURCE_ID = "sos-source"
        private const val SOS_PULSE_LAYER_ID = "sos-pulse-layer"
        private const val SOS_LAYER_ID = "sos-layer"
        private const val SOS_AREA_SOURCE_ID = "sos-area-source"
        private const val SOS_AREA_FILL_LAYER_ID = "sos-area-fill-layer"
        private const val SOS_AREA_LINE_LAYER_ID = "sos-area-line-layer"
        private const val SOS_ICON_ID = "sos-icon"

        /**
         * The red every attention marker is drawn on — disc, pulse and the pill
         * under a member's initials. The warning triangle paints its
         * exclamation in this same colour to fake a cut-out, so the constant has
         * to stay in step across all of them.
         */
        private const val MARKER_RED = "#e53935"

        // A member who has an active SOS is drawn from a source of their own,
        // NOT the clustered members source: clustering would collapse the
        // raiser into a count badge and take the emergency off the map with
        // them. Same bitmaps as any other member (plus an SOS band), so a
        // raiser looks like themselves, only marked.
        private const val MEMBERS_SOS_SOURCE_ID = "members-sos-source"
        private const val MEMBERS_SOS_PULSE_LAYER_ID = "members-sos-pulse-layer"
        private const val MEMBERS_SOS_LAYER_ID = "members-sos-layer"

        /**
         * How close an alert's recorded position has to be to the raiser's live
         * position for the two to be treated as the same spot.
         *
         * An SOS location is a snapshot from the moment the button was pressed
         * and never updates, so it and the raiser's marker are the same thing
         * only while they haven't moved. Within this distance the standalone
         * SOS disc would just sit on top of their avatar — which is the overlap
         * being removed — so it is dropped and the avatar carries the alert.
         * Beyond it the disc is real information the avatar doesn't have, and
         * it stays.
         */
        private const val SOS_MERGE_DISTANCE_METERS = 75.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(requireContext())

        // Tile caching is MapLibre's process-wide ambient cache.

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * BACK closes the open map overlay (Layers first, then History) instead of
     * leaving the app. Enabled only while one is open — see [MapOverlayBack] —
     * so on a bare map the gesture still reaches the activity. Kept in step
     * by [syncOverlayBackCallback] at every place those overlays show or hide.
     */
    private val overlayBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (_binding == null) { isEnabled = false; return }
            when (MapOverlayBack.dismissTarget(
                historyOpen = binding.cardTimeRange.visibility == View.VISIBLE,
                layersOpen = binding.cardMapLayers.visibility == View.VISIBLE,
            )) {
                MapOverlayBack.Target.LAYERS -> binding.cardMapLayers.visibility = View.GONE
                MapOverlayBack.Target.HISTORY -> hideHistoryPanel()
                null -> Unit
            }
            syncOverlayBackCallback()
        }
    }

    private fun syncOverlayBackCallback() {
        val b = _binding ?: run { overlayBackCallback.isEnabled = false; return }
        overlayBackCallback.isEnabled = MapOverlayBack.shouldIntercept(
            historyOpen = b.cardTimeRange.visibility == View.VISIBLE,
            layersOpen = b.cardMapLayers.visibility == View.VISIBLE,
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The view is recreated on every navigation back to this tab, so the expand
        // state has to be reset to match the freshly-inflated (collapsed) FABs.
        isFabExpanded = false

        // Scoped to the view lifecycle: removed with the view, re-added with it.
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, overlayBackCallback)
        syncOverlayBackCallback()

        // Touch the places ViewModel now so its circle/alert flows are already
        // warm when the place sheet's Alerts button is tapped. The editor waits
        // for the circle list either way, but creating the ViewModel here means
        // it almost never has to.
        placesViewModel

        applyStatusBarInsets(view)
        binding.mapView.onCreate(savedInstanceState)
        setupMap()
        setupUI()
        observeViewModel()
    }

    private fun applyStatusBarInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            statusBarInsetPx = insets.top
            val defaultMargin = resources.getDimensionPixelSize(R.dimen.card_margin_top)
            val topMargin = insets.top + defaultMargin

            // Location off, right of the controls, under the standing lines.
            locationOffSlotPx = topMargin + 56.dpToPx() + 64.dpToPx()
            // The selector, the guardian line, the sync error and the controls, each under
            // the one above it, and the refresh pill and the layers card clear of them
            // (TopLeftStack): the guardian line is a standing disclosure, never under the
            // controls, their labels or a wrapped circle name.
            stackBaseTopPx = topMargin
            topLeftStack?.place()
            placeLocationOff()

            windowInsets
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun setupMap() {
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            // Reset per view-creation: did THIS cycle apply a pending place-zoom?
            // applyMapStyle's saved-camera restore and the auto-fit-to-members path
            // both honor this flag so they don't clobber a tap-to-place zoom. Without
            // the reset a retained fragment instance keeps a stale `true`.
            consumedPendingZoom = false

            // Cold start (in-memory savedCamera is gone after process death):
            // center on the user's own location. Seed from the last-known self
            // loc so applyMapStyle opens there instantly with no world-view
            // flash, and flag a live-GPS refine. A tab return keeps its in-memory
            // savedCamera, so this is skipped and the current view is preserved.
            if (viewModel.savedCamera == null) {
                coldStartRecenterSelf = true
                loadSelfLoc()?.let { (lat, lng) ->
                    viewModel.savedCamera = MapViewModel.SavedCamera(lat, lng, SELF_LOCATION_ZOOM, 0.0, 0.0)
                }
            }

            // The map-controls chrome renders its own compass (binding.btnCompass),
            // so MapLibre's built-in overlay would be a duplicate that pops in
            // whenever the user rotates the map.
            map.uiSettings.isCompassEnabled = false

            // Apply any pending map-zoom request that arrived before the map was
            // ready. PlacesFragment fires requestMapZoom and immediately navigates
            // here, so the StateFlow already holds a value by the time onViewCreated
            // runs — but the collector in observeViewModel needs mapLibreMap to be
            // non-null, and getMapAsync is async, so without this catch-up the first
            // emission is dropped on the floor. Consume the request once applied so
            // it doesn't re-fire on the next view recreation.
            val pendingZoom = mainViewModel.mapZoomRequests.value
            if (pendingZoom != null) {
                map.animateCamera(cameraFor(pendingZoom), 1000)
                mainViewModel.consumeMapZoomRequest(pendingZoom.token)
                consumedPendingZoom = true
                // The user picked the target — don't let the auto-fit-to-members
                // path in updateMemberMarkers yank the camera back to GPS / the
                // first member when locations decrypt.
                hasInitiallyZoomedToMembers = true
            }
            // NOTE: the saved-camera restore is NOT done here — setStyle() (called
            // below in loadMapStyle) resets the camera to the style's default when
            // it loads, which would clobber it. It's restored in applyMapStyle,
            // once the style has loaded, instead.

            var lastZoomForFan = map.cameraPosition.zoom
            map.addOnCameraMoveListener {
                viewModel.updateMapBearing(map.cameraPosition.bearing)
                // Panning alone changes which SOS alerts are off screen.
                updateSosOffscreenIndicators()
                // Dismiss any active cluster fan-out on zoom so users who scroll in
                // naturally get back to the real per-member markers. A pure pan
                // (same zoom) leaves the fan alone so the user can explore around it.
                val z = map.cameraPosition.zoom
                if (Math.abs(z - lastZoomForFan) > 0.1) {
                    clearFannedCluster()
                    lastZoomForFan = z
                }
            }

            // Remember the camera only when the USER moves it (a touch gesture),
            // so returning to the Map tab restores the user's deliberate view —
            // NOT a transient programmatic camera (the world-view default, the GPS
            // snap, setStyle's reset, the auto-fit, or the restore itself), any of
            // which would otherwise get saved and then restored as garbage.
            map.addOnCameraMoveStartedListener { reason ->
                lastCameraMoveWasGesture =
                    reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            }
            map.addOnCameraIdleListener {
                if (lastCameraMoveWasGesture) {
                    val cp = map.cameraPosition
                    cp.target?.let { t ->
                        val sc = MapViewModel.SavedCamera(
                            t.latitude, t.longitude, cp.zoom, cp.bearing, cp.tilt
                        )
                        viewModel.savedCamera = sc
                    }
                }
                lastCameraMoveWasGesture = false
                // A programmatic camera change (fly-to, auto-fit) emits idle
                // without necessarily emitting move, so refresh here too.
                updateSosOffscreenIndicators()
                // Whether an avatar is covering a place's pin is a SCREEN-space
                // question, so the answer changes with the camera and nothing
                // else re-asks it: place markers are only rebuilt when the place
                // or member data changes. Without this, zooming in to separate an
                // avatar from the pin it is sitting on would leave the pin hidden
                // — the whole point of the screen-space rule. On idle rather than
                // on move: one rebuild per gesture, not one per frame.
                if (viewModel.showPlaces.value) {
                    updatePlaceMarkers(filterVisiblePlaces(viewModel.places.value))
                }
            }

            map.addOnMapClickListener { latLng ->
                collapseFabs()

                // Tap-outside-to-dismiss for the layers filter popover. The
                // toggle that opens it lives in the left control stack while
                // the chip card appears on the right, so without this the only
                // way to close it is to rediscover that far-away button (every
                // other map overlay, tapped-location and history, has a close
                // affordance of its own). Treat a map tap as a pure dismiss
                // (consume it; don't also drop a pin) so the filters can never
                // sit open over other controls.
                if (binding.cardMapLayers.visibility == View.VISIBLE) {
                    binding.cardMapLayers.visibility = View.GONE
                    syncOverlayBackCallback()
                    return@addOnMapClickListener true
                }

                val screenPoint = map.projection.toScreenLocation(latLng)
                val tapPoint = LatLng(latLng.latitude, latLng.longitude)

                val tapRect = android.graphics.RectF(
                    screenPoint.x - 20f, screenPoint.y - 20f,
                    screenPoint.x + 20f, screenPoint.y + 20f
                )

                // SOS outranks everything, including places. A member marker or
                // place pin sitting on the same spot must not swallow the tap:
                // this is the one marker the user is trying to reach.
                try {
                    val sosFeatures = map.queryRenderedFeatures(tapRect, SOS_LAYER_ID)
                    val sosId = sosFeatures.firstOrNull()?.getStringProperty("sosId")
                    if (sosId != null) {
                        val alert = mainViewModel.mappableSosAlerts.value.find { it.id == sosId }
                        if (alert != null) {
                            showSosBottomSheet(alert)
                            return@addOnMapClickListener true
                        }
                    }
                } catch (_: Exception) {}

                // A raiser whose alert was merged into their avatar has no
                // separate disc to tap, so their marker has to open the SOS
                // callout — otherwise the emergency becomes unreachable from
                // the map and the tap lands on the ordinary member sheet.
                try {
                    val sosMemberFeatures = map.queryRenderedFeatures(tapRect, MEMBERS_SOS_LAYER_ID)
                    val userId = sosMemberFeatures.firstOrNull()?.getStringProperty("userId")
                    if (userId != null) {
                        val alert = mainViewModel.mappableSosAlerts.value
                            .filter { it.userId == userId }
                            .maxByOrNull { it.createdAt }
                        if (alert != null) {
                            clearFannedCluster()
                            showSosBottomSheet(alert)
                            return@addOnMapClickListener true
                        }
                    }
                } catch (_: Exception) {}

                try {
                    val placeFeatures = map.queryRenderedFeatures(tapRect, PLACES_LAYER_ID)
                    if (placeFeatures.isNotEmpty()) {
                        // Resolve to the nearest hit place whose pin is actually visible. A
                        // covered place hides its pin (a member avatar sits on top), but a
                        // 0-opacity symbol is still returned by queryRenderedFeatures — so a tap
                        // there must fall through to the member on top, not open the invisible
                        // place underneath.
                        val locations = viewModel.memberLocations.value
                        val visibleHit = placeFeatures
                            .mapNotNull { f ->
                                val id = f.getStringProperty("id") ?: return@mapNotNull null
                                val p = viewModel.places.value.find { it.id == id } ?: return@mapNotNull null
                                if (isPlaceCoveredByMember(p, locations)) null else p
                            }
                            .minByOrNull { screenDistanceTo(map, screenPoint, it.latitude, it.longitude) }
                        if (visibleHit != null) {
                            // Precedence: when a member's avatar is ALSO under the finger,
                            // whichever is nearer to where they actually tapped wins.
                            // Deciding by query order instead would hand the tap to the
                            // place pin even when the avatar is drawn on top of it — the
                            // visible thing losing to the thing behind it.
                            val nearerMember = nearestMemberAt(map, screenPoint, memberTapRectFor(screenPoint))
                            val placeDist = screenDistanceTo(map, screenPoint, visibleHit.latitude, visibleHit.longitude)
                            if (nearerMember != null && nearerMember.second <= placeDist) {
                                clearFannedCluster()
                                showMemberBottomSheet(nearerMember.first)
                                return@addOnMapClickListener true
                            }
                            clearFannedCluster()
                            showPlaceBottomSheet(visibleHit)
                            return@addOnMapClickListener true
                        }
                    }
                } catch (_: Exception) {}

                // A tap on a cluster badge fans the co-located members out into a ring
                // of spider-leg markers. Zooming in past the cluster threshold
                // dismisses the fan on its own (the cluster dissolves into real
                // markers).
                try {
                    val clusterFeatures = map.queryRenderedFeatures(tapRect, MEMBERS_CLUSTER_LAYER_ID)
                    if (clusterFeatures.isNotEmpty()) {
                        val cf = clusterFeatures.first()
                        val g = cf.geometry()
                        if (g is Point) {
                            fanOutCluster(LatLng(g.latitude(), g.longitude()))
                        } else {
                            fanOutCluster(latLng)
                        }
                        return@addOnMapClickListener true
                    }
                } catch (_: Exception) {}

                try {
                    val fanFeatures = map.queryRenderedFeatures(tapRect, FAN_MARKER_LAYER_ID)
                    if (fanFeatures.isNotEmpty()) {
                        val userId = fanFeatures.first().getStringProperty("userId")
                        if (userId != null) {
                            val loc = viewModel.memberLocations.value.find { it.userId == userId }
                            if (loc != null) {
                                // Picking one co-located member collapses the rest.
                                clearFannedCluster()
                                showMemberBottomSheet(loc)
                                return@addOnMapClickListener true
                            }
                        }
                    }
                } catch (_: Exception) {}

                // Tap on the map (not on a cluster or fanned member) dismisses any
                // active fan before falling through to other tap handlers.
                clearFannedCluster()

                val memberTapRect = memberTapRectFor(screenPoint)
                val myUserId = sessionManager.getUserId()
                try {
                    val nearest = nearestMemberAt(map, screenPoint, memberTapRect)
                    if (nearest != null) {
                        showMemberBottomSheet(nearest.first)
                        return@addOnMapClickListener true
                    }
                } catch (_: Exception) {}

                // The own-GPS "you are here" path only applies while the user has NO
                // coloured marker of their own. Once that marker exists the tap
                // belongs to the member handler above, and the 15 m proximity check
                // below would steal it and show the address dialog instead.
                val selfMarkerVisible = myUserId != null &&
                    viewModel.memberLocations.value.any { it.userId == myUserId }
                val userLocation = if (locationComponentActivated && !selfMarkerVisible) {
                    map.locationComponent.lastKnownLocation
                } else null

                if (userLocation != null) {
                    val userPoint = LatLng(userLocation.latitude, userLocation.longitude)
                    val distanceMeters = tapPoint.distanceTo(userPoint)

                    if (distanceMeters < 15) {
                        val userScreenPoint = map.projection.toScreenLocation(userPoint)
                        viewModel.onMapTapped(
                            userLocation.latitude,
                            userLocation.longitude,
                            isOwnLocation = true,
                            tileGeocodeFallback = { queryPlaceNameFromTiles(map, userScreenPoint) },
                            poiNameExtractor = { queryPoiNameFromTiles(map, userScreenPoint) }
                        )
                        updateTappedMarker(userLocation.latitude, userLocation.longitude)
                        return@addOnMapClickListener true
                    }
                }

                if (viewModel.isShowingHistory()) {
                    // ~44 dp: a fingertip. Material puts the minimum touch target
                    // at 48 dp; history points sit on a dense trail, so this is
                    // pulled in slightly to reduce grabbing a neighbour, and
                    // nearest-wins sorts out the rest.
                    val historyTolerancePx = 22f * resources.displayMetrics.density
                    val nearestHistoricalLocation =
                        findNearestHistoricalLocation(screenPoint, historyTolerancePx)
                    if (nearestHistoricalLocation != null) {
                        val histPoint = LatLng(nearestHistoricalLocation.latitude, nearestHistoricalLocation.longitude)
                        val histScreenPoint = map.projection.toScreenLocation(histPoint)
                        val tappedMemberName = if (viewModel.isCurrentUser(nearestHistoricalLocation.userId)) {
                            getString(R.string.map_you)
                        } else {
                            memberName(nearestHistoricalLocation.userId)
                        }
                        viewModel.onMapTapped(
                            nearestHistoricalLocation.latitude,
                            nearestHistoricalLocation.longitude,
                            isOwnLocation = false,
                            historicalTimestamp = nearestHistoricalLocation.timestamp,
                            memberName = tappedMemberName,
                            tileGeocodeFallback = { queryPlaceNameFromTiles(map, histScreenPoint) },
                            poiNameExtractor = { queryPoiNameFromTiles(map, histScreenPoint) },
                            // Someone sharing an approximate location: their
                            // point is never looked up or handed on, as their
                            // marker's isn't.
                            approximate = !spotOf(nearestHistoricalLocation).showsPoint,
                        )
                        updateTappedMarker(nearestHistoricalLocation.latitude, nearestHistoricalLocation.longitude)
                        return@addOnMapClickListener true
                    }
                }

                viewModel.onMapTapped(
                    latLng.latitude,
                    latLng.longitude,
                    tileGeocodeFallback = { queryPlaceNameFromTiles(map, screenPoint) },
                    poiNameExtractor = { queryPoiNameFromTiles(map, screenPoint) }
                )
                updateTappedMarker(latLng.latitude, latLng.longitude)
                true
            }

            map.addOnMapLongClickListener { latLng ->
                showMapLongPressMenu(latLng.latitude, latLng.longitude)
                true
            }

            loadMapStyle(map)
        }
    }

    /**
     * Open the map-screen address search bottom sheet. Picking a result behaves
     * exactly like tapping that location on the map: fly + drop a tapped marker +
     * surface the location card with Add-to-Places / Share / Copy actions.
     */
    private fun showAddressSearchSheet() {
        val sheet = MapAddressSearchSheet()
        // The sheet needs the current map centre (to proximity-rank toward it, and
        // to default the scope to the current city on a miss) plus the visible
        // bounds, so that an unqualified query only returns what is on screen —
        // Google Maps' rule: the view wins unless the query names somewhere else.
        mapLibreMap?.let { map ->
            map.cameraPosition.target?.let { sheet.setFocus(it.latitude, it.longitude) }
            val bounds = try {
                map.projection.visibleRegion.latLngBounds
            } catch (e: Exception) {
                // Projection isn't usable until the style/camera settle; an
                // unclamped search is a better failure than no search.
                null
            }
            if (bounds != null) {
                sheet.setViewport(
                    south = bounds.latitudeSouth,
                    west = bounds.longitudeWest,
                    north = bounds.latitudeNorth,
                    east = bounds.longitudeEast,
                )
            }
        }
        sheet.setOnLocationPickedListener { _, lat, lng ->
            val map = mapLibreMap ?: return@setOnLocationPickedListener
            val target = org.maplibre.android.geometry.LatLng(lat, lng)
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(target, 16.0),
                600
            )
            // Reuse the same tap-handler entry point so the location
            // card, snackbar, and viewModel.tappedLocation flow are
            // identical to a deliberate map tap. The result already
            // carries an address line so the geocode is mostly a
            // no-op; tile fallbacks resolve any POI name on top of it.
            val screenPoint = map.projection.toScreenLocation(target)
            viewModel.onMapTapped(
                lat,
                lng,
                tileGeocodeFallback = { queryPlaceNameFromTiles(map, screenPoint) },
                poiNameExtractor = { queryPoiNameFromTiles(map, screenPoint) }
            )
            updateTappedMarker(lat, lng)
        }
        sheet.show(childFragmentManager, "map-address-search")
    }

    /** Long-press on the map: reverse-geocode the spot, then offer to save it as a place. */
    private fun showMapLongPressMenu(latitude: Double, longitude: Double) {
        val map = mapLibreMap ?: return
        val screenPoint = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(latitude, longitude))

        val bottomNav = activity?.findViewById<View>(R.id.bottom_nav)
        val loadingSnackbar = Snackbar.make(binding.mapView, getString(R.string.map_getting_address), Snackbar.LENGTH_INDEFINITE).apply {
            if (bottomNav != null) anchorView = bottomNav
        }
        loadingSnackbar.show()

        // Same geocoding strategy as a map tap: offline data first, rendered tiles
        // as the fallback, plus a POI name from the tiles to prepend to the address.
        viewLifecycleOwner.lifecycleScope.launch {
            val address = viewModel.geocodeLocation(
                latitude, longitude,
                tileGeocodeFallback = {
                    queryPlaceNameFromTiles(map, screenPoint)
                },
                poiNameExtractor = {
                    queryPoiNameFromTiles(map, screenPoint)
                }
            )

            loadingSnackbar.dismiss()

            val displayText = if (address != null) {
                "$address\n\n${coordinates(latitude, longitude)}"
            } else {
                coordinates(latitude, longitude)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.map_add_to_places_title))
                .setMessage(displayText)
                .setPositiveButton(getString(R.string.map_add)) { _, _ ->
                    showCreatePlaceFromMapDialog(latitude, longitude, address)
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    /**
     * Create a place from a map location. Places are personal by default and can
     * be shared to circles later.
     */
    private fun showCreatePlaceFromMapDialog(
        latitude: Double,
        longitude: Double,
        address: String? = null,
    ) {
        val dialogBinding = DialogCreatePlaceBinding.inflate(layoutInflater)

        // No address search in this flow — the user already picked the location by
        // tapping/long-pressing the map, so a second way to choose is just clutter.
        // The search section belongs to the FAB-driven flow in PlacesFragment.
        dialogBinding.layoutSearch.visibility = View.GONE

        if (address != null) {
            dialogBinding.layoutAddress.visibility = View.VISIBLE
            dialogBinding.tvAddress.text = address
        }

        var selectedIcon = "home"
        var selectedColor = "#2196F3"

        // Create asks nothing about the alert area (matching the Places create flow):
        // the place gets the 150 m default and is monitored only once an alert targets
        // it. The area is tuned later from the place's bell dialog (showPlaceAlertEditor).

        val iconButtons = mapOf(
            dialogBinding.iconHome to "home",
            dialogBinding.iconWork to "work",
            dialogBinding.iconSchool to "school",
            dialogBinding.iconGym to "gym",
            dialogBinding.iconRestaurant to "restaurant",
            dialogBinding.iconCoffee to "coffee",
            dialogBinding.iconShopping to "shopping",
            dialogBinding.iconMedical to "medical",
            dialogBinding.iconPark to "park",
            dialogBinding.iconBar to "bar",
            dialogBinding.iconGasStation to "gas_station",
            dialogBinding.iconAirport to "airport",
            dialogBinding.iconHotel to "hotel",
            dialogBinding.iconParking to "parking",
            dialogBinding.iconChurch to "church",
            dialogBinding.iconLibrary to "library",
            dialogBinding.iconStar to "star",
            dialogBinding.iconHeart to "heart"
        )

        val colorButtons = mapOf(
            dialogBinding.colorRed to "#F44336",
            dialogBinding.colorPink to "#E91E63",
            dialogBinding.colorPurple to "#9C27B0",
            dialogBinding.colorBlue to "#2196F3",
            dialogBinding.colorTeal to "#009688",
            dialogBinding.colorGreen to "#4CAF50",
            dialogBinding.colorOrange to "#FF9800"
        )

        fun updateIconSelection(selectedButton: android.widget.ImageButton) {
            iconButtons.keys.forEach { button ->
                button.isSelected = (button == selectedButton)
            }
        }

        fun updateColorSelection(selectedButton: android.widget.ImageButton) {
            colorButtons.keys.forEach { button ->
                button.isSelected = (button == selectedButton)
            }
        }

        iconButtons.forEach { (button, iconName) ->
            button.setOnClickListener {
                selectedIcon = iconName
                updateIconSelection(button)
            }
        }

        colorButtons.forEach { (button, colorHex) ->
            button.setOnClickListener {
                selectedColor = colorHex
                updateColorSelection(button)
            }
        }

        dialogBinding.iconHome.isSelected = true
        dialogBinding.colorBlue.isSelected = true

        // Inline Place Visibility — queues shares locally because the
        // place doesn't exist yet; the btnCreate handler drains after
        // createPlaceFromMap succeeds.
        val pendingShares = com.pingolin.track.ui.places.bindPlaceVisibility(
            context = requireContext(),
            binding = dialogBinding,
            editingPlace = null,
            availableCircles = mainViewModel.circles.value,
            placeRepository = placeRepository,
            scope = viewLifecycleOwner.lifecycleScope,
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCreate.setOnClickListener {
            val name = dialogBinding.etPlaceName.text?.toString()?.trim() ?: ""
            val notes = dialogBinding.etPlaceNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            if (name.isBlank()) {
                showStatus(getString(R.string.place_name_required))
                return@setOnClickListener
            }

            // The repo is called directly (rather than viewModel.createPlaceFromMap)
            // so the actual PlaceEntity can be awaited and the pending shares
            // drained against its real id before dismissing. The viewModel path
            // pushes results through a StateFlow that arrives after the dialog is
            // gone, which loses the local `pendingShares` reference.
            dialog.dismiss()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = placeRepository.createPersonalPlace(
                    name = name,
                    notes = notes,
                    address = address,
                    latitude = latitude,
                    longitude = longitude,
                    radius = 150,
                    icon = selectedIcon,
                    color = selectedColor,
                    placeType = "place",
                    boundaryRadius = null,
                )
                result.fold(
                    onSuccess = { created ->
                        pendingShares.drain { circleId ->
                            placeRepository.sharePlaceWithCircle(created.id, circleId, keepIfBehind = true)
                        }
                        showStatus(getString(R.string.place_created))
                    },
                    onFailure = { err ->
                        // A place-count 402 carries quota/error_code — show the
                        // premium prompt rather than a terse status message.
                        val quota = err.quotaError
                        val code = err.premiumErrorCode
                        if (quota != null || code != null) {
                            com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.showForQuota(
                                parentFragmentManager, quota, code,
                            )
                        } else {
                            showStatus(err.plainText(getString(R.string.place_create_failed)))
                        }
                    }
                )
            }
        }

        dialog.setOnShowListener {
            dialog.window?.let { window ->
                window.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)

                val params = window.attributes
                params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT
                // WRAP_CONTENT so the dialog is full size while the keyboard is closed.
                params.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                params.horizontalMargin = 0f
                params.y = 0
                window.attributes = params

                // ADJUST_RESIZE shrinks the dialog when the keyboard opens; the
                // NestedScrollView inside then scrolls within the reduced space.
                @Suppress("DEPRECATION")
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }

        dialog.show()
    }

    private fun shouldUseDarkMode(): Boolean {
        return when (sessionManager.getThemeMode()) {
            SessionManager.THEME_DARK -> true
            SessionManager.THEME_LIGHT -> false
            else -> {
                // THEME_SYSTEM: defer to the system UI mode.
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun loadMapStyle(map: MapLibreMap) {
        // A new style needs the location component activated against it again, and
        // nothing may touch the old style object from here until the new one loads.
        locationComponentActivated = false
        mapStyle = null

        val isDark = shouldUseDarkMode()
        loadPMTilesStyle(map, isDark)
    }

    /** Fetch the style JSON and patch its source URL to the self-hosted PMTiles archive. */
    private fun loadPMTilesStyle(map: MapLibreMap, isDark: Boolean) {
        val styleUrl = if (isDark) {
            sessionManager.getMapDarkStyle()
        } else {
            sessionManager.getMapLightStyle()
        }
        val tileTemplate = sessionManager.getPMTilesTileTemplate()

        currentStyleUrl = styleUrl

        // The basemap style almost never changes, so cache the patched JSON
        // and apply it immediately on the next launch — the map (and cached
        // member markers) paint without waiting on a network round trip. A
        // fresh copy is still fetched in the background to keep the cache
        // current.
        // Keyed on the PATCH INPUTS as well as the style URL: keyed on the URL
        // alone, the cache would outlive a change to what gets patched IN, and
        // an app update that changes the tile source would replay the previous
        // version's patched style, tile source and all, on its next launch.
        val cacheKey = "${styleUrl.hashCode()}-${tileTemplate.hashCode()}"
        val cacheFile = java.io.File(requireContext().cacheDir, "map-style-$cacheKey.json")

        viewLifecycleOwner.lifecycleScope.launch {
            // 1) Cached style → apply instantly.
            val cached = withContext(Dispatchers.IO) {
                try { if (cacheFile.exists()) cacheFile.readText() else null } catch (e: Exception) { null }
            }
            if (cached != null) {
                applyMapStyle(map, cached, retryCount = 0, fromCache = true)
            }

            // 2) Revalidate: fetch the latest style, refresh the cache for next
            //    launch, and re-apply only if it changed (or we had no cache to
            //    show). Fewer retries once a cached style is already on screen —
            //    there's no rush. Retry with exponential backoff otherwise
            //    (important for first login when the network may not be ready).
            val maxRetries = if (cached != null) 2 else 5
            var retryCount = 0
            var delayMs = 500L

            while (retryCount < maxRetries) {
                try {
                    val patchedStyle = fetchAndPatchPMTilesStyle(styleUrl, tileTemplate)
                    if (patchedStyle != null) {
                        withContext(Dispatchers.IO) {
                            try { cacheFile.writeText(patchedStyle) } catch (e: Exception) { /* cache is best-effort */ }
                        }
                        if (cached == null || patchedStyle != cached) {
                            applyMapStyle(map, patchedStyle, retryCount, fromCache = false)
                        }
                        return@launch
                    } else {
                        android.util.Log.w("MapFragment", "Failed to fetch PMTiles style (attempt ${retryCount + 1}/$maxRetries)")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MapFragment", "Failed to load PMTiles style (attempt ${retryCount + 1}/$maxRetries): ${e.message}")
                }

                retryCount++
                if (retryCount < maxRetries) {
                    kotlinx.coroutines.delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(5000L)
                }
            }

            if (cached == null) {
                android.util.Log.e("MapFragment", "Failed to load PMTiles style after $maxRetries attempts")
            }
        }
    }

    /**
     * Apply a (patched) PMTiles style JSON to the map. Shared by the cached and
     * freshly-fetched paths. Repaints cached member markers as soon as the style
     * is ready so they don't wait for the next location emit.
     */
    private fun applyMapStyle(map: MapLibreMap, styleJson: String, retryCount: Int, fromCache: Boolean) {
        // Debug-only timing for the map's cold start, so "the map takes a while
        // the first time" can be measured. Logs once per style application; the
        // listener removes itself.
        if (BuildConfig.DEBUG) {
            val styleAppliedAt = System.currentTimeMillis()
            var logged = false
            var listener: MapView.OnDidFinishRenderingMapListener? = null
            listener = MapView.OnDidFinishRenderingMapListener { fully ->
                // Fires per frame; only the first FULLY rendered one is the
                // user-visible "map is done" moment.
                if (fully && !logged) {
                    logged = true
                    android.util.Log.i(
                        "MapDiag",
                        "map.firstFullyRendered ms=${System.currentTimeMillis() - styleAppliedAt} " +
                            "fromCache=$fromCache"
                    )
                    listener?.let { binding.mapView.removeOnDidFinishRenderingMapListener(it) }
                }
            }
            binding.mapView.addOnDidFinishRenderingMapListener(listener)
            android.util.Log.i("MapDiag", "style.assign fromCache=$fromCache")
        }
        map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
            if (style.isFullyLoaded) {
                mapStyle = style
                setupMapLayers(style)
                enableLocationComponent(style)
                // setStyle (above) just reset the camera to the style's default.
                // If the user has a saved (gestured) view, restore it now — must be
                // after setStyle, or it gets clobbered. If not (cold start, or they
                // never panned), leave it so the auto-fit below frames the circle.
                if (viewModel.savedCamera != null && mainViewModel.mapZoomRequests.value == null && !consumedPendingZoom) {
                    viewModel.savedCamera?.let { sc ->
                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(sc.lat, sc.lng))
                                    .zoom(sc.zoom)
                                    .bearing(sc.bearing)
                                    .tilt(sc.tilt)
                                    .build()
                            )
                        )
                        hasInitiallyZoomedToMembers = true
                    }
                }
                // updateMemberMarkers() bails when the style is null, and the
                // memberLocations combine won't re-fire on style-ready — so
                // paint whatever cached locations we already have right now.
                if (viewModel.showMembers.value && !viewModel.isShowingHistory()) {
                    val locs = viewModel.memberLocations.value
                    // Mirror the combine's guard: don't paint members before the
                    // member list has loaded (e.g. on a tab return, when the
                    // member table is briefly empty), or getMemberName() returns
                    // "Unknown" and peer markers flash a "U" initial for a frame.
                    if (locs.isEmpty() || viewModel.members.value.isNotEmpty()) {
                        updateMemberMarkers(locs)
                    }
                }
                // Same deal for every other runtime source:
                // setStyle wipes them and setupMapLayers recreates
                // them EMPTY, but their StateFlows are conflated
                // and won't re-emit on style-ready. Without this
                // repaint, a second style apply (cached style
                // followed by a differing fresh fetch in
                // loadPMTilesStyle) would silently blank
                // places/destinations/boundaries until the next
                // data change.
                val visiblePlaces = filterVisiblePlaces(viewModel.places.value)
                if (viewModel.showPlaces.value) {
                    updatePlaceMarkers(visiblePlaces)
                }
                if (viewModel.showBoundaries.value) {
                    updateBoundaryCircles(visiblePlaces)
                }
                if (viewModel.showDestinations.value) {
                    updateDestinationMarkers(viewModel.destinations.value)
                }
                // No layer-visibility gate: an active SOS is not something the
                // user can toggle off along with places or destinations.
                updateSosMarkers(mainViewModel.mappableSosAlerts.value)
                android.util.Log.d("MapFragment", "PMTiles style loaded successfully" +
                    (if (fromCache) " (from cache)" else "") +
                    (if (retryCount > 0) " (after $retryCount retries)" else ""))
            } else {
                android.util.Log.e("MapFragment", "PMTiles style failed to load fully")
            }
        }
    }

    /** Fetch a style JSON and repoint its "protomaps" source at [tileTemplate]. */
    private suspend fun fetchAndPatchPMTilesStyle(styleUrl: String, tileTemplate: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(styleUrl).build()
                val response = mapOkHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    android.util.Log.e("MapFragment", "Failed to fetch style: ${response.code}")
                    return@withContext null
                }

                val styleJson = response.body?.string() ?: return@withContext null
                val style = JSONObject(styleJson)

                val sources = style.optJSONObject("sources")
                val protomaps = sources?.optJSONObject("protomaps")
                if (protomaps != null) {
                    // Individual tiles, not the archive. The archive is 134 GB,
                    // which Cloudflare will not cache, so reading it directly would
                    // make every directory walk and every tile an origin round trip
                    // from this device that no other device could reuse. The tile
                    // server serves the same data a tile at a time, small enough to
                    // cache at the edge like the sprites and fonts.
                    //
                    // `url` must be REMOVED, not merely overwritten: with both keys
                    // present MapLibre prefers `url` and this does nothing at all.
                    protomaps.remove("url")
                    protomaps.put("tiles", org.json.JSONArray().put(tileTemplate))
                    // Free when the source reads `url` — MapLibre takes them from
                    // the archive header. Nothing supplies them with `tiles`.
                    protomaps.put("minzoom", SessionManager.PMTILES_MIN_ZOOM)
                    protomaps.put("maxzoom", SessionManager.PMTILES_MAX_ZOOM)
                }

                style.toString()
            } catch (e: Exception) {
                android.util.Log.e("MapFragment", "Failed to fetch/patch PMTiles style: ${e.message}")
                null
            }
        }
    }

    /**
     * Best place name for a screen point, read off the rendered tiles. Used when
     * the offline geocoder has no data for the area.
     *
     * Names are preferred in specificity order: POI/place label, then road/street,
     * then neighbourhood/locality, then city/town.
     */
    private suspend fun queryPlaceNameFromTiles(map: MapLibreMap, screenPoint: android.graphics.PointF): String? {
        return withContext(Dispatchers.Main) {
            try {
                val rect = android.graphics.RectF(
                    screenPoint.x - 20f,
                    screenPoint.y - 20f,
                    screenPoint.x + 20f,
                    screenPoint.y + 20f
                )

                val features = map.queryRenderedFeatures(rect)

                if (features.isEmpty()) {
                    return@withContext null
                }

                var placeName: String? = null
                var streetName: String? = null
                var localityName: String? = null

                for (feature in features) {
                    val props = feature.properties() ?: continue

                    val name = props.get("name")?.asString
                        ?: props.get("name:en")?.asString
                        ?: props.get("name_en")?.asString

                    if (name.isNullOrBlank()) continue

                    val layer = feature.geometry()?.type() ?: ""
                    val featureClass = props.get("class")?.asString ?: ""
                    val featureType = props.get("type")?.asString ?: ""

                    when {
                        featureClass in listOf("poi", "place", "landmark") ||
                        featureType in listOf("poi", "place", "address") -> {
                            if (placeName == null) placeName = name
                        }
                        featureClass in listOf("street", "road", "highway", "path") ||
                        featureType in listOf("street", "road") -> {
                            if (streetName == null) streetName = name
                        }
                        featureClass in listOf("neighbourhood", "suburb", "locality", "village", "town", "city") -> {
                            if (localityName == null) localityName = name
                        }
                        // Unclassified: only usable if nothing better turned up at all.
                        else -> {
                            if (placeName == null && streetName == null && localityName == null) {
                                placeName = name
                            }
                        }
                    }
                }

                when {
                    placeName != null && streetName != null -> "$placeName, $streetName"
                    placeName != null && localityName != null -> "$placeName, $localityName"
                    placeName != null -> placeName
                    streetName != null && localityName != null -> "$streetName, $localityName"
                    streetName != null -> streetName
                    localityName != null -> localityName
                    else -> null
                }
            } catch (e: Exception) {
                android.util.Log.e("MapFragment", "Failed to query tile features: ${e.message}")
                null
            }
        }
    }

    /**
     * The POI name at a screen point, read off the rendered tiles — never a street
     * or locality. Prepended to a geocoded address so "Blue Bottle Coffee" leads
     * rather than the street number it sits at.
     */
    private suspend fun queryPoiNameFromTiles(map: MapLibreMap, screenPoint: android.graphics.PointF): String? {
        return withContext(Dispatchers.Main) {
            try {
                val rect = android.graphics.RectF(
                    screenPoint.x - 20f,
                    screenPoint.y - 20f,
                    screenPoint.x + 20f,
                    screenPoint.y + 20f
                )

                val features = map.queryRenderedFeatures(rect)

                if (features.isEmpty()) {
                    return@withContext null
                }

                val poiClasses = setOf(
                    "poi", "place", "landmark", "shop", "amenity", "building",
                    "school", "hospital", "restaurant", "hotel", "bank",
                    "library", "museum", "park", "stadium", "airport",
                    "railway", "bus_station", "parking", "fuel", "pharmacy",
                    "supermarket", "cafe", "bar", "cinema", "theatre",
                    "church", "cemetery", "government", "courthouse", "police",
                    "fire_station", "post_office", "college", "university"
                )

                val poiTypes = setOf(
                    "poi", "place", "address", "amenity", "shop", "building"
                )

                for (feature in features) {
                    val props = feature.properties() ?: continue

                    val name = props.get("name")?.asString
                        ?: props.get("name:en")?.asString
                        ?: props.get("name_en")?.asString

                    if (name.isNullOrBlank()) continue

                    val featureClass = props.get("class")?.asString ?: ""
                    val featureType = props.get("type")?.asString ?: ""
                    val featureSubclass = props.get("subclass")?.asString ?: ""

                    if (featureClass in poiClasses ||
                        featureType in poiTypes ||
                        featureSubclass in poiClasses) {
                        return@withContext name
                    }
                }

                null
            } catch (e: Exception) {
                android.util.Log.e("MapFragment", "Failed to query POI from tiles: ${e.message}")
                null
            }
        }
    }

    private fun getStyleUrl(isDark: Boolean): String {
        return if (isDark) {
            sessionManager.getMapDarkStyle()
        } else {
            sessionManager.getMapLightStyle()
        }
    }

    /** The style URL the current settings call for — compared on resume to detect a theme change. */
    private fun getExpectedStyleUrl(): String {
        return getStyleUrl(shouldUseDarkMode())
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        val map = mapLibreMap ?: return

        if (!style.isFullyLoaded) {
            return
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Fetch a fix BEFORE activating the component: the camera move below has to
        // happen in the same pass, or the map visibly sits on its default view first.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var location = locationProvider.lastLocation()
                if (location == null) {
                    location = locationProvider.currentLocation(
                        com.pingolin.track.location.LocationAccuracy.HIGH
                    )
                }

                // Cold start only: centre on the user's own location, refining the
                // (possibly stale) persisted seed to the live fix. Saving it makes
                // the position sticky — the auto-fit won't reframe to all members,
                // and a tab return restores it — and persisting it seeds the next
                // cold start.
                //
                // Both guards matter. consumedPendingZoom means the user navigated
                // here aiming at a specific target (a row in Places); snapping back
                // to GPS would erase that intent. A non-null savedCamera means this
                // is a tab return with a view already framed. Both are re-checked
                // HERE, after the location await, so anything that landed while the
                // fix was pending isn't clobbered.
                if (!consumedPendingZoom &&
                    (coldStartRecenterSelf || viewModel.savedCamera == null)
                ) {
                    location?.let {
                        map.moveCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(LatLng(it.latitude, it.longitude))
                                    .zoom(SELF_LOCATION_ZOOM)
                                    .build()
                            )
                        )
                        viewModel.savedCamera =
                            MapViewModel.SavedCamera(it.latitude, it.longitude, SELF_LOCATION_ZOOM, 0.0, 0.0)
                        hasInitiallyZoomedToMembers = true
                        persistSelfLoc(it.latitude, it.longitude)
                    }
                    coldStartRecenterSelf = false
                }

                val locationComponentOptions = LocationComponentOptions.builder(requireContext())
                    .pulseEnabled(true)
                    .accuracyAlpha(0.15f)
                    .build()

                val activationOptions = LocationComponentActivationOptions.builder(requireContext(), style)
                    .locationComponentOptions(locationComponentOptions)
                    .useDefaultLocationEngine(true)
                    .build()

                // Activated but kept visually DISABLED. The blue GPS dot + accuracy
                // ring duplicates the user's own coloured circle marker, and before
                // that marker arrives (members still decrypting) a lone unexplained
                // dot is worse than nothing. Activation is still required, because
                // `lastKnownLocation` reads — the tap-on-self and fit-bounds
                // fallbacks — only work on an activated component.
                map.locationComponent.apply {
                    activateLocationComponent(activationOptions)
                    locationComponentActivated = true
                    isLocationComponentEnabled = false
                    cameraMode = CameraMode.NONE
                    renderMode = RenderMode.NORMAL

                    location?.let { loc ->
                        forceLocationUpdate(loc)
                    }
                }

                viewModel.refreshLocations()

            } catch (e: Exception) {
                // If location fetch fails, still activate the component so
                // lastKnownLocation polling works once a fix arrives —
                // but keep the visual disabled, same as the success path.
                val locationComponentOptions = LocationComponentOptions.builder(requireContext())
                    .pulseEnabled(true)
                    .accuracyAlpha(0.15f)
                    .build()

                val activationOptions = LocationComponentActivationOptions.builder(requireContext(), style)
                    .locationComponentOptions(locationComponentOptions)
                    .useDefaultLocationEngine(true)
                    .build()

                map.locationComponent.apply {
                    activateLocationComponent(activationOptions)
                    locationComponentActivated = true
                    isLocationComponentEnabled = false
                    cameraMode = CameraMode.NONE
                    renderMode = RenderMode.NORMAL
                }

                viewModel.refreshLocations()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerOnMyLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // No location permission: ask, as the map's line does.
            askForLocation()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Cached fix first — it comes back immediately; only pay for a fresh
                // one when there isn't one.
                var location = locationProvider.lastLocation()

                if (location == null) {
                    location = locationProvider.currentLocation(
                        com.pingolin.track.location.LocationAccuracy.BALANCED
                    )
                }

                location?.let {
                    mapLibreMap?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(it.latitude, it.longitude))
                                .zoom(SELF_LOCATION_ZOOM)
                                .build()
                        )
                    )
                }
            } catch (e: Exception) {
                // Silently fail - location will be shown when available
            }
        }
    }

    private fun setupMapLayers(style: Style) {
        style.addSource(GeoJsonSource(TRAILS_SOURCE_ID))
        style.addSource(GeoJsonSource(POINTS_SOURCE_ID))
        style.addSource(GeoJsonSource(ARROWS_SOURCE_ID))

        createArrowIcon(style)

        // Trails need more weight on the dark basemap to hold the same contrast.
        val isDarkMode = shouldUseDarkMode()
        val lineWidth = if (isDarkMode) 4f else 3f
        val circleRadius = if (isDarkMode) 6f else 5f
        val strokeWidth = if (isDarkMode) 2.5f else 2f

        val trailsLayer = LineLayer(TRAILS_LAYER_ID, TRAILS_SOURCE_ID).withProperties(
            PropertyFactory.lineWidth(lineWidth),
            PropertyFactory.lineOpacity(if (isDarkMode) 0.95f else 0.8f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(
                Expression.get("color")
            )
        )
        style.addLayer(trailsLayer)

        val pointsLayer = CircleLayer(POINTS_LAYER_ID, POINTS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(circleRadius),
            PropertyFactory.circleOpacity(if (isDarkMode) 1.0f else 0.9f),
            PropertyFactory.circleStrokeWidth(strokeWidth),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleColor(
                Expression.get("color")
            )
        )
        style.addLayer(pointsLayer)

        val arrowsLayer = SymbolLayer(ARROWS_LAYER_ID, ARROWS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(ARROW_ICON_ID),
            PropertyFactory.iconSize(0.6f),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconOpacity(if (isDarkMode) 0.9f else 0.7f),
            PropertyFactory.iconColor(Expression.get("color"))
        )
        style.addLayer(arrowsLayer)

        style.addSource(GeoJsonSource(TAPPED_MARKER_SOURCE_ID))
        val tappedMarkerLayer = CircleLayer(TAPPED_MARKER_LAYER_ID, TAPPED_MARKER_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(13f),
            PropertyFactory.circleColor(Color.parseColor("#FF5722")), // Orange marker
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleOpacity(1.0f)
        )
        style.addLayer(tappedMarkerLayer)

        // Add boundary circles source and layers (rendered BELOW place markers)
        style.addSource(GeoJsonSource(BOUNDARY_CIRCLES_SOURCE_ID))
        val boundaryFillLayer = FillLayer(BOUNDARY_CIRCLES_FILL_LAYER_ID, BOUNDARY_CIRCLES_SOURCE_ID).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.15f)
        )
        style.addLayer(boundaryFillLayer)
        val boundaryStrokeLayer = LineLayer(BOUNDARY_CIRCLES_STROKE_LAYER_ID, BOUNDARY_CIRCLES_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.6f)
        )
        style.addLayer(boundaryStrokeLayer)

        // Per-place icons are generated on demand by updatePlaceMarkers, which puts
        // each bitmap's id in the feature's "icon" property for the expression below.
        style.addSource(GeoJsonSource(PLACES_SOURCE_ID))
        val placesLayer = SymbolLayer(PLACES_LAYER_ID, PLACES_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconSize(1.0f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
            // Hide the point pin when a member is inside this place (occupied): the zone
            // circle + name label become the place's representation and the member avatar
            // sits on top, so the pin would just be a duplicate hidden behind the avatar.
            PropertyFactory.iconOpacity(Expression.get("iconOpacity"))
        )
        style.addLayer(placesLayer)

        // Place-name label rendered below the marker, matching the
        // destination-label pattern. textOptional + textAllowOverlap=
        // false makes MapLibre auto-suppress labels that would collide;
        // the minZoom gate hides them at city/region zoom where the
        // labels would just be noise and the icon-only marker is
        // already enough to identify a known place.
        try { style.removeLayer(PLACES_LABEL_LAYER_ID) } catch (_: Exception) {}
        val placesLabelLayer = SymbolLayer(PLACES_LABEL_LAYER_ID, PLACES_SOURCE_ID).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            // CRITICAL: must name a font stack the style's glyph
            // endpoint actually hosts. Without textFont, MapLibre
            // requests its default stack ("Open Sans Regular,Arial
            // Unicode MS Regular"), which 404s on pmtiles.pingolin.com
            // — and a failed glyph fetch errors the whole GeoJSON
            // source's tile layout, taking the icon-only places layer
            // down with it. Because this layer is minZoom-gated at 12,
            // the failure only shows at z>=12, as Places disappearing
            // on zoom-in.
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(Color.parseColor("#1F1B36")),
            PropertyFactory.textHaloColor(Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textIgnorePlacement(false),
            PropertyFactory.textOptional(true),
            // Hide the place's own name label when occupied (its pin is hidden and the
            // member's baked "at <Place>" carries the name) — reuses the icon's occupied
            // flag so the label and pin fade together.
            PropertyFactory.textOpacity(Expression.get("iconOpacity"))
        )
        placesLabelLayer.minZoom = 12f
        style.addLayer(placesLabelLayer)

        // Destinations source + layers. One data-driven icon per
        // destination (color-matched to the creator, with a small "flag"
        // glyph to distinguish from places), plus a text label showing
        // the creator's display name so it's obvious WHO is heading
        // there. Both layers are dynamic — updateDestinationMarkers
        // feeds them features from the DestinationRepository flow.
        style.addSource(GeoJsonSource(DESTINATIONS_SOURCE_ID))
        // Pulsing "live" halos UNDER the destination icons (added first so the
        // icon + label draw on top). Two layers, filtered by the per-feature
        // is_place flag, so a destination sitting on a saved Place pulses bigger +
        // softer + in the place's color, while an ad-hoc location pulses tighter +
        // crisper + in the creator's color. Radius/opacity are animated by
        // startDestinationPulse(); is_place + pulse_color come from
        // updateDestinationMarkers().
        val destPulsePlace = CircleLayer(DESTINATIONS_PULSE_PLACE_LAYER_ID, DESTINATIONS_SOURCE_ID).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("pulse_color"))),
            PropertyFactory.circleRadius(0f),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleBlur(0.35f),
        )
        destPulsePlace.setFilter(Expression.eq(Expression.get("is_place"), Expression.literal(true)))
        style.addLayer(destPulsePlace)
        val destPulseAdhoc = CircleLayer(DESTINATIONS_PULSE_ADHOC_LAYER_ID, DESTINATIONS_SOURCE_ID).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("pulse_color"))),
            PropertyFactory.circleRadius(0f),
            PropertyFactory.circleOpacity(0f),
            PropertyFactory.circleBlur(0.05f),
        )
        destPulseAdhoc.setFilter(Expression.eq(Expression.get("is_place"), Expression.literal(false)))
        style.addLayer(destPulseAdhoc)
        val destIconLayer = SymbolLayer(DESTINATIONS_LAYER_ID, DESTINATIONS_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconSize(1.0f),
            dataDrivenIconOpacity(),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM)
        )
        style.addLayer(destIconLayer)
        val destLabelLayer = SymbolLayer(DESTINATIONS_LABEL_LAYER_ID, DESTINATIONS_SOURCE_ID).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            // Same glyph constraint as the places label layer above — and
            // this layer has no minZoom gate, so a default-font 404 would
            // kill destination markers at EVERY zoom level.
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor(Color.parseColor("#1F1B36")),
            PropertyFactory.textHaloColor(Color.WHITE),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textOffset(arrayOf(0f, 1.0f)),
            PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textIgnorePlacement(false),
            PropertyFactory.textOptional(true),
        )
        style.addLayer(destLabelLayer)

        // The member source and its clustering layers are deliberately NOT created
        // here. updateMemberMarkers owns them: it builds them on first use and then
        // updates in place with setGeoJson. Adding a second copy here is how the two
        // definitions drift apart (different cluster radius/zoom, different layer
        // order) while only one of them is ever the one on screen. Nothing renders
        // members before updateMemberMarkers runs — applyMapStyle calls it right
        // after this function, and it rebuilds the source whenever it is missing.

        // Fan-out overlay: temporarily shown when a cluster is tapped. Holds both
        // the spider-leg LineString features (connecting fanned markers back to
        // the shared cluster center) and the fanned member Point features.
        try { style.removeLayer(FAN_LINE_LAYER_ID) } catch (_: Exception) {}
        try { style.removeLayer(FAN_MARKER_LAYER_ID) } catch (_: Exception) {}
        try { style.removeSource(FAN_SOURCE_ID) } catch (_: Exception) {}
        style.addSource(GeoJsonSource(FAN_SOURCE_ID))
        val fanLineLayer = LineLayer(FAN_LINE_LAYER_ID, FAN_SOURCE_ID).withProperties(
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineColor(Color.parseColor("#cccccc")),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND)
        )
        style.addLayer(fanLineLayer)
        val fanMarkerLayer = SymbolLayer(FAN_MARKER_LAYER_ID, FAN_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(Expression.get("iconId")),
            dataDrivenIconOpacity(),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
        )
        fanMarkerLayer.setFilter(Expression.has("iconId"))
        style.addLayer(fanMarkerLayer)

        // SOS — added LAST so it draws above every other layer, including member markers and
        // clusters. iconAllowOverlap + iconIgnorePlacement are not cosmetic here: symbol collision
        // would let a place label suppress an active emergency marker, which is the one thing on
        // this map that must never be hidden. Cross-circle by design — updateSosMarkers ignores the
        // selected-circle filter every other layer honours. An SOS whose position is known only to
        // within more than 100 m, or that was coarsened to a bubble, is an AREA, under its disc,
        // drawn as an approximate member's: never a bare pin.
        try { style.removeLayer(SOS_AREA_LINE_LAYER_ID) } catch (_: Exception) {}
        try { style.removeLayer(SOS_AREA_FILL_LAYER_ID) } catch (_: Exception) {}
        try { style.removeSource(SOS_AREA_SOURCE_ID) } catch (_: Exception) {}
        style.addSource(GeoJsonSource(SOS_AREA_SOURCE_ID))
        style.addLayer(
            FillLayer(SOS_AREA_FILL_LAYER_ID, SOS_AREA_SOURCE_ID).withProperties(
                PropertyFactory.fillColor(Expression.get("color")),
                PropertyFactory.fillOpacity(0.16f),
            )
        )
        style.addLayer(
            LineLayer(SOS_AREA_LINE_LAYER_ID, SOS_AREA_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(2f),
                PropertyFactory.lineOpacity(0.7f),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            )
        )
        try { style.removeLayer(SOS_PULSE_LAYER_ID) } catch (_: Exception) {}
        try { style.removeLayer(SOS_LAYER_ID) } catch (_: Exception) {}
        try { style.removeSource(SOS_SOURCE_ID) } catch (_: Exception) {}
        style.addSource(GeoJsonSource(SOS_SOURCE_ID))
        style.addLayer(
            CircleLayer(SOS_PULSE_LAYER_ID, SOS_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(Color.parseColor(MARKER_RED)),
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleBlur(0.15f),
            // An area's label has no pulse: a dot at its centre would read as an exact pin.
            ).withFilter(Expression.not(Expression.has("area")))
        )
        createSosIcon(style)
        style.addLayer(
            SymbolLayer(SOS_LAYER_ID, SOS_SOURCE_ID).withProperties(
                // Per-feature icon, because the label is drawn INTO the bitmap.
                //
                // A SymbolLayer textField is the obvious way to do this and it
                // does not work here: it makes the marker vanish altogether — not
                // just the text, the disc too. MapLibre needs a glyph/font stack
                // for text, and this style provides none the layer can resolve, so
                // the whole symbol fails to render.
                //
                // Member markers already bake their age line into their bitmap for
                // the same reason; this follows that.
                PropertyFactory.iconImage(Expression.get("iconId")),
                PropertyFactory.iconSize(1.0f),
                dataDrivenIconOpacity(),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
            )
        )

        // Members who are themselves raising an SOS. Their avatar is drawn
        // from here rather than from the clustered members source, so nothing
        // can collapse it into a count badge, and it sits at the very top of
        // the stack. The red halo pulses outward from under the avatar — the
        // icon bitmap is padded so the disc's centre lands on the coordinate
        // this circle is drawn around.
        try { style.removeLayer(MEMBERS_SOS_PULSE_LAYER_ID) } catch (_: Exception) {}
        try { style.removeLayer(MEMBERS_SOS_LAYER_ID) } catch (_: Exception) {}
        try { style.removeSource(MEMBERS_SOS_SOURCE_ID) } catch (_: Exception) {}
        style.addSource(GeoJsonSource(MEMBERS_SOS_SOURCE_ID))
        style.addLayer(
            CircleLayer(MEMBERS_SOS_PULSE_LAYER_ID, MEMBERS_SOS_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(Color.parseColor(MARKER_RED)),
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleBlur(0.15f),
            )
        )
        style.addLayer(
            SymbolLayer(MEMBERS_SOS_LAYER_ID, MEMBERS_SOS_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get("iconId")),
                dataDrivenIconOpacity(),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
            )
        )
    }

    /**
     * The attention marker bitmap: a red disc with a white ring and a warning
     * triangle across it. Deliberately not the member-marker shape — someone
     * asking for attention should not read as "one more person on the map" at a
     * glance.
     *
     * The mark is a triangle rather than the word "SOS", for three reasons, in
     * order of weight: a glyph carries no language; the marker is 96px and a
     * phrase cannot be set in it, so text here could only ever be an
     * abbreviation; and a warning triangle claims attention without claiming
     * rescue, which is the only thing Track can honestly promise.
     *
     * Point-UP, not the point-down give-way sign: point-up with an exclamation
     * is ISO 7010 W001, read as "general warning" nearly everywhere, whereas
     * point-down specifically means "yield to other traffic".
     */
    private fun createSosIcon(style: Style) {
        if (style.getImage(SOS_ICON_ID) != null) return
        val size = 96
        val bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor(MARKER_RED)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 6f
        p.color = Color.WHITE
        c.drawCircle(size / 2f, size / 2f, size / 2f - 3f, p)
        p.style = android.graphics.Paint.Style.FILL
        drawWarningTriangle(c, size / 2f, size / 2f, size * 0.46f)
        style.addImage(SOS_ICON_ID, bm)
    }

    /**
     * The attention marker WITH its "Sent from here · 12m" line baked in, and
     * the id it was registered under.
     *
     * One bitmap per distinct label, cached by that label — the age only
     * changes on the minute, and there are rarely more than one or two open
     * notifications, so this stays a handful of small bitmaps rather than one
     * per redraw.
     *
     * Baked rather than a SymbolLayer textField because a textField makes the
     * marker disappear outright (no glyph stack in this style). See the layer
     * setup for the full note.
     */
    private val sosIconIds = mutableSetOf<String>()

    private fun getOrCreateSosIcon(style: Style, label: String): String {
        val id = "sos-icon-" + label.hashCode().toString().replace("-", "n")
        if (id in sosIconIds && style.getImage(id) != null) return id

        val disc = 96
        val textSize = 30f
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
        }
        val gap = 10f
        val fm = textPaint.fontMetrics
        val textH = (fm.descent - fm.ascent)
        val w = maxOf(disc.toFloat(), textPaint.measureText(label) + 16f).toInt()
        val h = (disc + gap + textH).toInt()

        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val cx = w / 2f

        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor(MARKER_RED)
        c.drawCircle(cx, disc / 2f, disc / 2f - 3f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 6f
        p.color = Color.WHITE
        c.drawCircle(cx, disc / 2f, disc / 2f - 3f, p)
        drawWarningTriangle(c, cx, disc / 2f, disc * 0.46f)

        // Halo first, so the line reads over any basemap colour — the same
        // two-pass trick the member marker's age label uses.
        // `this.style`, not `style`: inside apply{} a bare `style` resolves to
        // this function's MapLibre Style parameter, not the Paint's property.
        val halo = android.graphics.Paint(textPaint).apply {
            this.style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.BLACK
        }
        val baseline = disc + gap - fm.ascent
        c.drawText(label, cx, baseline, halo)
        c.drawText(label, cx, baseline, textPaint)

        style.addImage(id, bm)
        sosIconIds.add(id)
        return id
    }

    /**
     * An area alert's label: its lines, white on the marker red, in a rounded box centred on the
     * area, as the web draws it. No disc and no dot: nothing at the centre reads as an exact pin.
     * Cached by label like [getOrCreateSosIcon].
     */
    private fun getOrCreateSosAreaLabel(style: Style, label: String): String {
        val id = "sos-area-" + label.hashCode().toString().replace("-", "n")
        if (id in sosIconIds && style.getImage(id) != null) return id

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
            )
        }
        val lines = label.split("\n")
        val fm = textPaint.fontMetrics
        val lineH = fm.descent - fm.ascent
        val pad = 14f
        val w = (lines.maxOf { textPaint.measureText(it) } + 2 * pad).toInt()
        val h = (lines.size * lineH + 2 * pad).toInt()

        val bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val box = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(MARKER_RED)
        }
        val rect = android.graphics.RectF(1.5f, 1.5f, w - 1.5f, h - 1.5f)
        c.drawRoundRect(rect, 18f, 18f, box)
        box.style = android.graphics.Paint.Style.STROKE
        box.strokeWidth = 3f
        box.color = Color.WHITE
        c.drawRoundRect(rect, 18f, 18f, box)
        lines.forEachIndexed { i, line -> c.drawText(line, w / 2f, pad + i * lineH - fm.ascent, textPaint) }

        style.addImage(id, bm)
        sosIconIds.add(id)
        return id
    }

    /**
     * A white warning triangle with an exclamation mark, centred on (cx, cy) and
     * `width` wide.
     *
     * The exclamation is PAINTED in the marker red, not punched out with a
     * PorterDuff.CLEAR xfermode. Clearing would cut through everything already
     * on the bitmap — the triangle, the red disc under it, and the bitmap's own
     * alpha — leaving a transparent exclamation-shaped hole with the basemap
     * showing through. Both call sites put this glyph on the same
     * MARKER_RED field, so painting the mark in that colour gives the intended
     * cut-out look with none of the compositing.
     */
    private fun drawWarningTriangle(
        c: android.graphics.Canvas,
        cx: Float,
        cy: Float,
        width: Float
    ) {
        // Apex angle chosen to stay legible when the whole glyph is only a
        // couple of dozen pixels across.
        val h = width * 0.88f
        val r = width * 0.10f // corner rounding, so it does not read as a shard

        val path = android.graphics.Path().apply {
            moveTo(cx, cy - h / 2f)
            lineTo(cx + width / 2f, cy + h / 2f)
            lineTo(cx - width / 2f, cy + h / 2f)
            close()
        }
        val body = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = android.graphics.Paint.Style.FILL
            pathEffect = android.graphics.CornerPathEffect(r)
        }
        c.drawPath(path, body)

        val mark = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(MARKER_RED)
            style = android.graphics.Paint.Style.FILL
        }
        val barW = width * 0.11f
        val barTop = cy - h * 0.10f
        val barBottom = cy + h * 0.18f
        c.drawRoundRect(
            cx - barW / 2f, barTop, cx + barW / 2f, barBottom,
            barW / 2f, barW / 2f, mark
        )
        c.drawCircle(cx, cy + h * 0.31f, barW * 0.62f, mark)
    }

    /**
     * Draw every active SOS the viewer should see.
     *
     * Deliberately NOT filtered by the selected circle, unlike members, places
     * and destinations: an SOS permeates beyond circles, so an emergency in
     * circle B stays on screen while the map is showing circle A. The alerts
     * arrive pre-filtered for dismissal/acknowledgement and for having
     * decryptable coordinates (SosAlertDao.observeActiveMappable).
     */
    private fun updateSosMarkers(alerts: List<com.pingolin.track.data.local.entity.SosAlertEntity>) {
        val style = safeStyle ?: return

        val standalone = alerts.filterNot { isMergedIntoMemberMarker(it) }
        val now = System.currentTimeMillis()
        val useMiles = sessionManager.useMiles()
        val features = standalone.mapNotNull { alert ->
            val lat = alert.latitude ?: return@mapNotNull null
            val lng = alert.longitude ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lng, lat)).apply {
                addStringProperty("sosId", alert.id)
                if (com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null) {
                    // An AREA: its label at the centre, no disc, no pulse.
                    val label = com.pingolin.track.ui.sos.SosPositionText.areaLabel(requireContext(), alert, useMiles, now)
                    addStringProperty("iconId", getOrCreateSosAreaLabel(style, label))
                    addBooleanProperty("area", true)
                } else {
                    // Last known, updated, or the usual "Sent from here".
                    val label = com.pingolin.track.ui.sos.SosPositionText.mapLabel(requireContext(), alert, useMiles, now) {
                        getString(R.string.sos_sent_from_here, formatRelativeAge(now - alert.createdAt))
                    }
                    addStringProperty("iconId", getOrCreateSosIcon(style, label))
                }
            }
        }
        val areas = standalone.mapNotNull { alert ->
            val radius = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) ?: return@mapNotNull null
            ApproximateArea.featureJson(MARKER_RED, alert.latitude ?: return@mapNotNull null, alert.longitude ?: return@mapNotNull null, radius)
        }

        // Fetched by id each time: setGeoJson on a held source doesn't propagate (updateApproximateAreas).
        style.getSourceAs<GeoJsonSource>(SOS_AREA_SOURCE_ID)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[${areas.joinToString(",")}]}""")
        style.getSourceAs<GeoJsonSource>(SOS_SOURCE_ID)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))

        // The pulse belongs to whichever representation is on screen: the
        // standalone disc, the raiser's own avatar, or both.
        if (features.isEmpty() && sosMemberIds().isEmpty()) stopSosPulse() else startSosPulse()
        updateSosOffscreenIndicators()
    }

    /**
     * User ids of members who currently have an active SOS the viewer can see.
     *
     * Drives both the "SOS" band baked under their initials and the pulse
     * around their avatar. Cross-circle like the alerts themselves — a member
     * raising an emergency in another circle is still marked.
     */
    private fun sosMemberIds(): Set<String> =
        mainViewModel.mappableSosAlerts.value.map { it.userId }.toSet()

    /**
     * Is this alert already represented by its raiser's own marker?
     *
     * True only when the raiser has a live location AND it is essentially where
     * the alert was raised — see [SOS_MERGE_DISTANCE_METERS]. A raiser who has
     * since moved, or who has no marker at all (no recent location, or a
     * location that never decrypted), keeps their standalone SOS disc: it is
     * the only thing on the map holding the emergency's position.
     *
     * The Members layer being switched off (or history mode freezing it) takes
     * every avatar off the map, so it takes the merge with it — an SOS is not
     * something the user can hide by unchecking a layer.
     */
    private fun isMergedIntoMemberMarker(
        alert: com.pingolin.track.data.local.entity.SosAlertEntity
    ): Boolean {
        if (!viewModel.showMembers.value || viewModel.isShowingHistory()) return false
        // An area keeps its own area and label: a distance to it says nothing.
        if (com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null) return false
        val alertLat = alert.latitude ?: return false
        val alertLng = alert.longitude ?: return false
        // Never into a frozen marker: that is where they were, not where they raised it.
        val loc = viewModel.memberLocations.value.firstOrNull { it.userId == alert.userId && !viewModel.drawsFrozen(it.userId) }
            ?.let { displayed(it) }
            ?: return false
        val out = FloatArray(1)
        android.location.Location.distanceBetween(alertLat, alertLng, loc.latitude, loc.longitude, out)
        return out[0] <= SOS_MERGE_DISTANCE_METERS
    }

    /** Urgent double-time pulse under the SOS markers. Same mechanism as the
     *  destination pulse but faster and red, so an SOS reads as an SOS. */
    private var sosPulseAnimator: android.animation.ValueAnimator? = null

    private fun startSosPulse() {
        if (sosPulseAnimator != null) return
        sosPulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val style = safeStyle ?: return@addUpdateListener
                val f = va.animatedFraction
                // Both halos run off one animator so the standalone
                // disc and a raiser's avatar pulse in step. Either
                // layer may be empty; a source with no features just
                // draws nothing.
                //
                // They do NOT share a radius. circleRadius is in raw
                // screen pixels, and the two things they ring are
                // sized differently: the standalone SOS bitmap is a
                // fixed 96px (48px radius), while a member avatar is
                // 52dp — 68px on a 2.6-density phone, which would
                // swallow a 52px halo whole: the pulse would render
                // and be completely invisible.
                val opacity = PropertyFactory.circleOpacity(0.5f * (1f - f))
                (style.getLayer(SOS_PULSE_LAYER_ID) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius(18f + 34f * f),
                    opacity,
                )
                // Starts at the avatar's own edge and reaches well past it, so
                // what you see is the halo emerging from under the dot.
                val density = resources.displayMetrics.density
                (style.getLayer(MEMBERS_SOS_PULSE_LAYER_ID) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius((26f + 40f * f) * density),
                    PropertyFactory.circleOpacity(0.55f * (1f - f)),
                )
            }
            start()
        }
    }

    private fun stopSosPulse() {
        sosPulseAnimator?.cancel()
        sosPulseAnimator = null
        val style = safeStyle ?: return
        for (layerId in listOf(SOS_PULSE_LAYER_ID, MEMBERS_SOS_PULSE_LAYER_ID)) {
            (style.getLayer(layerId) as? CircleLayer)?.setProperties(
                PropertyFactory.circleRadius(0f),
                PropertyFactory.circleOpacity(0f),
            )
        }
    }

    /**
     * Refresh the edge arrows that point at active SOS alerts currently outside
     * the viewport. Called whenever the alert set changes and on every camera
     * move, since panning alone changes which alerts are off screen.
     */
    private fun updateSosOffscreenIndicators() {
        val binding = _binding ?: return
        val map = mapLibreMap ?: return
        val alerts = mainViewModel.mappableSosAlerts.value
        if (alerts.isEmpty()) {
            binding.sosEdgeIndicators.setIndicators(emptyList())
            return
        }

        val projection = map.projection
        val cameraBearing = map.cameraPosition.bearing
        val center = map.cameraPosition.target ?: return
        val viewWidth = binding.sosEdgeIndicators.width
        val viewHeight = binding.sosEdgeIndicators.height
        if (viewWidth == 0 || viewHeight == 0) return

        val indicators = alerts.mapNotNull { alert ->
            val lat = alert.latitude ?: return@mapNotNull null
            val lng = alert.longitude ?: return@mapNotNull null

            val screen = projection.toScreenLocation(LatLng(lat, lng))
            val onScreen = !screen.x.isNaN() && !screen.y.isNaN() &&
                screen.x >= 0f && screen.x <= viewWidth &&
                screen.y >= 0f && screen.y <= viewHeight
            if (onScreen) return@mapNotNull null

            // Screen-space direction = geographic bearing minus how far the
            // camera itself is rotated.
            val bearing = bearingBetween(center.latitude, center.longitude, lat, lng) - cameraBearing
            SosEdgeIndicatorView.Indicator(alert.id, bearing)
        }

        binding.sosEdgeIndicators.setIndicators(indicators)
    }

    /** Initial great-circle bearing from one coordinate to another, in degrees
     *  clockwise from north. */
    private fun bearingBetween(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Double {
        val phi1 = Math.toRadians(fromLat)
        val phi2 = Math.toRadians(toLat)
        val deltaLambda = Math.toRadians(toLng - fromLng)
        val y = Math.sin(deltaLambda) * Math.cos(phi2)
        val x = Math.cos(phi1) * Math.sin(phi2) -
            Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    /** No-op: there is no default place icon — [getOrCreatePlaceIcon] bakes one per
     *  colour + emoji combination on demand from updatePlaceMarkers. */
    private fun createPlaceIcon(style: Style) {
    }


    /**
     * Generate (or fetch from cache) a bitmap icon for a member cluster. Same
     * 52dp white-bordered circle as individual markers, with the count drawn
     * inside (e.g. "2", "3", "99+"). Shown when two or more members sit at the
     * same spot; tapping it fans them out (see [fanOutCluster]).
     */
    private fun getOrCreateClusterIcon(style: Style, count: Int): String {
        val iconId = if (count > 99) "member-cluster-99plus" else "member-cluster-$count"
        if (style.getImage(iconId) != null) return iconId

        val density = resources.displayMetrics.density
        val size = (52 * density).toInt().coerceAtLeast(52)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val bgColor = Color.parseColor("#6d4aff")
        val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            this.style = android.graphics.Paint.Style.FILL
        }
        val cx = size / 2f
        val cy = size / 2f
        val borderWidth = 3 * density
        val radius = size / 2f - borderWidth
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = borderWidth
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val label = if (count > 99) "99+" else count.toString()
        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size * (if (label.length <= 2) 0.42f else 0.30f)
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, cx, textY, textPaint)

        style.addImage(iconId, bitmap)
        return iconId
    }

    /**
     * Generate (or fetch from cache) a bitmap icon for a member marker: a 52dp
     * coloured circle + 2-char initials, with an age line below and an optional
     * "at &lt;Place&gt;" line when the member is inside a saved place.
     *
     * The text is BAKED INTO THE BITMAP rather than rendered by a SymbolLayer
     * textField. Evaluating a textField makes MapLibre fetch glyph PBFs, and a
     * failed glyph fetch errors the whole source's tile layout — every symbol on
     * that source disappears, not just the label. Baked text can't fail that way.
     * See [MemberMarkerGeometry] for the vertical layout this produces.
     */
    private fun getOrCreateMemberIcon(
        style: Style,
        color: Int,
        initials: String,
        isStale: Boolean,
        ageLabel: String,
        atPlace: String? = null,
        hasSos: Boolean = false,
    ): String {
        val stalePart = if (isStale) "stale" else "live"
        // Truncated so the baked label stays a reasonable width.
        val placeLabel = atPlace?.takeIf { it.isNotBlank() }?.let { n ->
            "at " + (if (n.length > 16) n.take(15).trimEnd() + "…" else n)
        }
        val sosPart = if (hasSos) "-sos" else ""
        val iconId = "member-icon-$color-$initials-$stalePart-$ageLabel-${placeLabel ?: ""}$sosPart"
        if (style.getImage(iconId) != null) {
            registeredMemberIconIds.add(iconId)
            return iconId
        }

        val density = resources.displayMetrics.density
        val circleSize = MemberMarkerGeometry.discSizePx(density)
        val ageTextSize = MemberMarkerGeometry.ageTextSizePx(density)
        val agePadTop = MemberMarkerGeometry.agePadTopPx(density)
        val ageHeight = MemberMarkerGeometry.ageBandPx(density)
        val pad = (6 * density)

        // White text paints (also used to measure widths before sizing the bitmap).
        val ageWhite = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = ageTextSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val placeWhite = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = ageTextSize
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        // A warning triangle in a filled pill directly under the initials. A red
        // glyph on its own would have to survive whatever the basemap puts
        // behind it; white-on-red inside a pill reads at any zoom, over any
        // tile.
        //
        // A glyph rather than a word, sized directly: the label it would
        // otherwise have to carry is a phrase ("Notify my circle"), which at
        // marker scale would either be unreadable or would widen every marker
        // far past the 52dp a plain one is held to.
        val sosTextSize = MemberMarkerGeometry.sosTextSizePx(density)
        val sosGlyphWidth = sosTextSize * 1.15f
        val sosPillHeight = if (hasSos) (sosTextSize * 1.6f) else 0f
        val sosPillWidth = if (hasSos) sosGlyphWidth + 2 * pad else 0f
        val sosPadTop = if (hasSos) MemberMarkerGeometry.sosPadTopPx(density) else 0f
        val sosBand = MemberMarkerGeometry.sosBandPx(density, hasSos)

        // A plain marker stays exactly 52dp wide; only the extra lines, or a caption longer than
        // an age ("Location sharing off"), widen it.
        val placeBand = if (placeLabel != null) ageHeight else 0
        val width = maxOf(
            circleSize.toFloat(),
            ageWhite.measureText(ageLabel) + 2 * pad,
            if (placeLabel != null) placeWhite.measureText(placeLabel) + 2 * pad else 0f,
            sosPillWidth,
        ).toInt()

        // Everything drawn under the disc, in draw order: the SOS pill, then
        // the age line, then "at <Place>".
        val belowHeight = MemberMarkerGeometry.belowDiscPx(density, atPlace = placeLabel != null, hasSos = hasSos)

        // The marker is anchored by the bitmap's CENTRE (iconAnchor CENTER), so with
        // labels hanging off the bottom the disc renders above the member's actual
        // coordinate by half the label stack. Harmless on its own — but the SOS pulse
        // is a CircleLayer centred on the coordinate, so it would ring the labels
        // rather than the avatar. Padding the top by the same amount puts the disc's
        // centre back ON the anchor. Only SOS markers pay for it; every other icon
        // keeps its geometry unchanged.
        val topPad = if (hasSos) belowHeight else 0
        val totalHeight = topPad + circleSize + belowHeight

        val bitmap = android.graphics.Bitmap.createBitmap(width, totalHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val bgColor = if (isStale) Color.parseColor("#9e9e9e") else color
        val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = bgColor
            this.style = android.graphics.Paint.Style.FILL
        }
        val cx = width / 2f
        val cy = topPad + circleSize / 2f
        val borderWidth = 3 * density
        val radius = circleSize / 2f - borderWidth
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = borderWidth
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val initialsPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = circleSize * 0.38f
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        val initialsY = cy - (initialsPaint.descent() + initialsPaint.ascent()) / 2
        canvas.drawText(initials, cx, initialsY, initialsPaint)

        if (hasSos) {
            val pillTop = topPad + circleSize + sosPadTop
            val pillRect = android.graphics.RectF(
                cx - sosPillWidth / 2f,
                pillTop,
                cx + sosPillWidth / 2f,
                pillTop + sosPillHeight,
            )
            val pillRadius = sosPillHeight / 2f
            val pillFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.parseColor(MARKER_RED)
                this.style = android.graphics.Paint.Style.FILL
            }
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillFill)
            val pillBorder = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                this.style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = 1.5f * density
            }
            canvas.drawRoundRect(pillRect, pillRadius, pillRadius, pillBorder)
            drawWarningTriangle(canvas, cx, pillRect.centerY(), sosGlyphWidth)
        }

        // Halo: a second pass with a thicker stroke, so the text reads against any
        // map colour beneath it.
        val ageY = topPad + circleSize + sosBand + agePadTop + ageTextSize
        val ageHalo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.BLACK
            this.style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = 3 * density
            textSize = ageTextSize
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(ageLabel, cx, ageY, ageHalo)
        canvas.drawText(ageLabel, cx, ageY, ageWhite)

        if (placeLabel != null) {
            val placeY = ageY + ageHeight
            val placeHalo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.BLACK
                this.style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = 3 * density
                textSize = ageTextSize
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            canvas.drawText(placeLabel, cx, placeY, placeHalo)
            canvas.drawText(placeLabel, cx, placeY, placeWhite)
        }

        style.addImage(iconId, bitmap)
        registeredMemberIconIds.add(iconId)
        return iconId
    }

    private fun getOrCreatePlaceIcon(style: Style, color: String, emoji: String, isShared: Boolean = false): String {
        val sharedSuffix = if (isShared) "-shared" else ""
        val iconId = "place-icon-${color.removePrefix("#")}-${emoji.hashCode()}$sharedSuffix"
        if (style.getImage(iconId) != null) return iconId

        val density = resources.displayMetrics.density
        val size = (52 * density).toInt().coerceAtLeast(52)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val bgColor = try { Color.parseColor(color) } catch (_: Exception) { Color.parseColor("#6D4AFF") }
        val circlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        circlePaint.color = bgColor
        circlePaint.style = android.graphics.Paint.Style.FILL
        val cx = size / 2f
        val cy = size / 2f
        val borderWidth = 3 * density
        val radius = size / 2f - borderWidth
        canvas.drawCircle(cx, cy, radius, circlePaint)

        // Solid border for your own places, dashed for ones shared with you.
        val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        borderPaint.color = Color.WHITE
        borderPaint.style = android.graphics.Paint.Style.STROKE
        borderPaint.strokeWidth = borderWidth
        if (isShared) {
            borderPaint.pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(6 * density, 4 * density), 0f
            )
        }
        canvas.drawCircle(cx, cy, radius, borderPaint)

        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        textPaint.textSize = size * 0.45f
        textPaint.textAlign = android.graphics.Paint.Align.CENTER
        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(emoji, cx, textY, textPaint)

        style.addImage(iconId, bitmap)
        return iconId
    }

    /** The chevron stamped along history trails to show direction of travel. */
    private fun createArrowIcon(mapStyle: Style) {
        if (mapStyle.getImage(ARROW_ICON_ID) != null) return

        val size = 24
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }

        // Draw a chevron pointing right (will be rotated by bearing)
        val path = android.graphics.Path().apply {
            moveTo(size * 0.3f, size * 0.2f)
            lineTo(size * 0.7f, size * 0.5f)
            lineTo(size * 0.3f, size * 0.8f)
        }
        canvas.drawPath(path, paint)

        mapStyle.addImage(ARROW_ICON_ID, bitmap, true) // true = SDF for color tinting
    }

    private fun updateTappedMarker(latitude: Double, longitude: Double) {
        val style = safeStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(TAPPED_MARKER_SOURCE_ID) ?: return

        val point = Point.fromLngLat(longitude, latitude)
        val feature = Feature.fromGeometry(point)
        source.setGeoJson(FeatureCollection.fromFeatures(listOf(feature)))
    }

    private fun clearTappedMarker() {
        val style = safeStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(TAPPED_MARKER_SOURCE_ID) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private var isFabExpanded = false

    /** The sync error, while the stored keys need a sign-in: a tap opens Settings, where Logout is. */
    private fun bindSyncError() {
        binding.tvSyncError.setOnClickListener { findNavController().navigate(R.id.settingsFragment) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                keyStoreRecovery.syncBanner.collect { shown ->
                    _binding?.tvSyncError?.visibility = if (shown) View.VISIBLE else View.GONE
                    topLeftStack?.place()
                }
            }
        }
    }

    /**
     * While Track has no usable location, the map says so until it does, and a tap asks for it:
     * otherwise "Not now" at the first question would leave a world map with nothing on it saying
     * why.
     */
    private fun bindLocationOff() {
        val b = _binding ?: return
        val line = locationOffLine(
            com.pingolin.track.util.LocationAccess.of(requireContext()),
            hasCircles = mainViewModel.circles.value.isNotEmpty(),
        )
        // Not while the controls' labels are open over its place: collapsing them binds it again.
        b.tvLocationOff.visibility = if (line != null && !isFabExpanded) View.VISIBLE else View.GONE
        if (line == null) return
        b.tvLocationOff.setText(line)
        b.tvLocationOff.setOnClickListener { askForLocation() }
        placeLocationOff()
    }

    /** The location line's place: its slot, or lower when a line above wraps into it. */
    private fun placeLocationOff() {
        val b = _binding ?: return
        val above = listOf(b.tvGuardianManaged, b.tvSyncError)
            .filter { it.visibility == View.VISIBLE && it.height > 0 }
            .map { (it.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin to it.height }
        val top = locationOffTop(locationOffSlotPx, above, gapPx = 8.dpToPx())
        if ((b.tvLocationOff.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin != top) {
            b.tvLocationOff.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { topMargin = top }
        }
    }

    /** Ask for location: the activity holds Android's question and the dialogs around it. */
    private fun askForLocation() {
        (activity as? com.pingolin.track.ui.main.MainActivity)?.askForLocation()
    }

    /**
     * The standing disclosure for an account somebody else set up and holds the
     * password to.
     *
     * Bound from setupUI's collector of SessionManager.guardianDisclosure: at
     * every start of the view, and on every change while it shows. Not from the
     * window-insets listener (it fires when insets change, not when the account
     * state does), and not only on resume: /auth/me answers at app start, after
     * this fragment's first layout and its resume, so a guardian-managed
     * account's first session after sign-in would show no line until the map's
     * next resume.
     */
    private fun bindGuardianDisclosure() {
        if (_binding == null) return
        val managed = sessionManager.isGuardianManaged()
        binding.tvGuardianManaged.visibility = if (managed) View.VISIBLE else View.GONE
        // Shown or gone, what is under it in the column moves.
        topLeftStack?.place()
        if (!managed) return

        // Once the day the guardian named has passed, the same line says the
        // account is theirs rather than that it is watched. Same surface on
        // purpose — a second control competing for the same corner would be one
        // the reader learns to ignore.
        val handoverDue = sessionManager.isGuardianHandoverDue()
        binding.tvGuardianManaged.setText(
            if (handoverDue) R.string.guardian_handover_line else R.string.guardian_map_line
        )
        binding.tvGuardianManaged.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(
                    if (handoverDue) R.string.guardian_handover_title
                    else R.string.guardian_detail_title
                )
                .setMessage(
                    if (handoverDue) {
                        getString(R.string.guardian_handover_detail) + "\n\n" +
                            getString(R.string.guardian_detail_body)
                    } else {
                        getString(R.string.guardian_detail_body)
                    }
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun setupUI() {
        // The location line goes under the guardian and sync-error lines however many lines they wrap to
        // (a large font, a small screen), and says "your circles" only to someone in one. Its slot is
        // the layout's own until the insets come: never 0, which would put it over the circle selector.
        locationOffSlotPx = (binding.tvLocationOff.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
        // The top-left column, before its lines bind, so each shows in its place (TopLeftStack).
        stackBaseTopPx = (binding.cardCircleSelector.layoutParams as android.widget.FrameLayout.LayoutParams).topMargin
        topLeftStack = TopLeftStack(
            selector = binding.cardCircleSelector,
            guardianLine = binding.tvGuardianManaged,
            syncError = binding.tvSyncError,
            controls = binding.mapControlsLeft,
            refreshPill = binding.cardRefreshStatus,
            layersCard = binding.cardMapLayers,
            baseTopPx = { stackBaseTopPx },
            onPlaced = { placeLocationOff() },
        ).also { it.attach() }
        bindGuardianDisclosure()
        // Again on every change: /auth/me's answer can come after the first layout and the resume.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                sessionManager.guardianDisclosure.collect { bindGuardianDisclosure() }
            }
        }
        bindSyncError()
        binding.tvGuardianManaged.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeLocationOff() }
        binding.tvSyncError.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeLocationOff() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                mainViewModel.circles.collect { bindLocationOff() }
            }
        }
        // Tapping an off-screen SOS arrow flies the camera to that alert and
        // opens its callout, so the arrow is a route to the emergency rather
        // than just an ambient "something is out there" cue.
        binding.sosEdgeIndicators.onIndicatorClick = { sosId ->
            val alert = mainViewModel.mappableSosAlerts.value.find { it.id == sosId }
            val lat = alert?.latitude
            val lng = alert?.longitude
            if (alert != null && lat != null && lng != null) {
                // An area is fitted whole, never zoomed to its centre.
                mapLibreMap?.animateCamera(
                    cameraFor(
                        com.pingolin.track.ui.main.MainViewModel.MapZoomRequest(
                            lat, lng, radiusMeters = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert),
                        )
                    )
                )
                showSosBottomSheet(alert)
            }
        }

        // Every map-overlay control dismisses an active cluster fan-out first
        // (clearFannedCluster is a cheap idempotent no-op when none is open).
        binding.fabExpand.setOnClickListener { clearFannedCluster(); toggleFabExpand() }

        binding.fabHistory.setOnClickListener {
            clearFannedCluster()
            collapseFabs()
            toggleHistoryPanel()
        }
        binding.fabQuickCheckIn.setOnClickListener { view ->
            clearFannedCluster()
            showCheckInPopup(view)
        }
        binding.fabDestinations.setOnClickListener {
            clearFannedCluster()
            collapseFabs()
            findNavController().navigate(R.id.destinationsFragment)
        }
        // Attach the "others heading out" dot to the always-visible expand FAB
        // once it's laid out (BadgeDrawable positions off the anchor's bounds).
        binding.fabExpand.doOnLayout { setupDestinationsBadge() }
        binding.fabSearch.setOnClickListener {
            clearFannedCluster()
            collapseFabs()
            showAddressSearchSheet()
        }

        binding.btnCenterOnAll.setOnClickListener {
            clearFannedCluster()
            centerOnAllMembers()
        }

        binding.btnRecenterOnMe.setOnClickListener {
            clearFannedCluster()
            centerOnMyLocation()
        }

        binding.btnCompass.setOnClickListener {
            clearFannedCluster()
            mapLibreMap?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .bearing(0.0)
                        .tilt(0.0)
                        .build()
                ),
                300
            )
        }

        binding.cardCircleSelector.setOnClickListener {
            clearFannedCluster()
            showCircleSelectorDialog()
        }

        binding.btnCloseHistory.setOnClickListener {
            hideHistoryPanel()
        }

        setupTimeRangeChips()

        setupSendLocationButton()

        setupTrackingToggle()

        setupLayerToggles()

        binding.btnExportCoordinates.setOnClickListener {
            showCoordinateExportDialog()
        }

        setupFabLabels()
    }

    /** Expandable FAB rows — must not be lazy since binding changes on view recreation */
    private val expandableRows get() = listOf(
        binding.fabRowHistory,
        binding.fabRowDestinations
    )

    private fun toggleFabExpand() {
        isFabExpanded = !isFabExpanded
        if (isFabExpanded) expandFabs() else collapseFabs()
    }

    /**
     * Labels next to always-visible map buttons that the user benefits
     * from seeing only while the dot menu is expanded. Includes the
     * bottom always-visible FABs (tracking, quick check-in, send), the
     * top-left icon-only cards (center-on-all, compass), and the
     * notification bell in activity_main.xml. The expandable row labels
     * inside the speed dial above aren't in this list — those are part
     * of the row itself and toggled by the row's own visibility.
     *
     * Only includes labels whose binding actually exists; the bell label
     * lives in MainActivity's layout and may be null if MapFragment is
     * embedded elsewhere.
     */
    private fun alwaysVisibleFabLabels(): List<View> = listOfNotNull(
        // labelTracking deliberately omitted — fabTracking itself is only
        // visible when tracking is active, so the FAB's presence already
        // conveys the state and a duplicate label is redundant.
        binding.labelQuickCheckIn,
        binding.labelSendLocation,
        binding.labelSearch,
        binding.labelCenterOnAll,
        binding.labelRecenterOnMe,
        binding.labelLayers,
        binding.labelCompass,
        activity?.findViewById(R.id.labelNotifications),
    )

    private fun expandFabs() {
        isFabExpanded = true
        binding.fabExpand.setImageResource(R.drawable.ic_close)
        // The controls' labels open over the location line's place: it's back on collapse.
        binding.tvLocationOff.visibility = View.GONE
        expandableRows.forEachIndexed { index, row ->
            row.alpha = 0f
            row.translationY = 40f
            row.visibility = View.VISIBLE
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 30).toLong())
                .setDuration(150)
                .start()
        }
        alwaysVisibleFabLabels().forEach { label ->
            label.animate().cancel()
            label.alpha = 0f
            label.visibility = View.VISIBLE
            label.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }
        // Each label that would cover something hides, after this layout and every one while the menu is open.
        binding.root.viewTreeObserver.run {
            removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)
            addOnGlobalLayoutListener(fitMenuLabelsOnLayout)
        }
    }

    /** Fits the open menu's labels (fitMenuLabels) after each layout: a column that moves, a name, a font. */
    private val fitMenuLabelsOnLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener { fitOpenMenuLabels() }

    private fun fitOpenMenuLabels() {
        val b = _binding ?: return
        if (!isFabExpanded) return
        // The window's views: the map's, and the activity's bell and its label.
        fitMenuLabels(b.root.rootView, gapPx = 8.dpToPx())
    }

    private fun collapseFabs() {
        isFabExpanded = false
        binding.root.viewTreeObserver.removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)
        binding.fabExpand.setImageResource(R.drawable.ic_more_vert)
        bindLocationOff()
        expandableRows.forEach { row ->
            row.animate().cancel()
            row.visibility = View.GONE
            row.alpha = 0f
            row.translationY = 40f
        }
        alwaysVisibleFabLabels().forEach { label ->
            label.animate().cancel()
            label.visibility = View.GONE
            label.alpha = 0f
        }
    }

    /** No-op: FAB labels are revealed by [expandFabs], not by long-pressing. */
    private fun setupFabLabels() {
    }

    /** Fade a FAB label in, then fade it back out on its own. */
    private fun showFabLabel(label: View) {
        label.handler?.removeCallbacksAndMessages(null)

        label.alpha = 0f
        label.visibility = View.VISIBLE
        label.animate()
            .alpha(1f)
            .setDuration(150)
            .start()

        label.postDelayed({
            label.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    label.visibility = View.GONE
                }
                .start()
        }, 2000)
    }

    private fun showCircleSelectorDialog() {
        val circles = mainViewModel.circles.value

        val options = mutableListOf(getString(R.string.map_just_me))
        options.addAll(circles.map { it.name })

        val currentCircleId = mainViewModel.selectedCircle.value?.id

        // Index 0 is "Just me", so every circle sits one further down the list.
        val checkedItem = if (currentCircleId == null) {
            0
        } else {
            circles.indexOfFirst { it.id == currentCircleId }.takeIf { it >= 0 }?.plus(1) ?: 0
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.map_select_circle))
            .setSingleChoiceItems(options.toTypedArray(), checkedItem) { dialog, which ->
                if (which == 0) {
                    mainViewModel.clearSelectedCircle()
                } else {
                    mainViewModel.selectCircle(circles[which - 1].id)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // Repositions the FAB stack to sit just above the history panel whenever the
    // panel's height changes (e.g. the count / "Load more" footer that appears
    // once history loads at 1h+), so a taller panel never slides under the FABs.
    private val historyCardLayoutListener =
        View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> repositionFabsAboveHistory() }

    private fun repositionFabsAboveHistory() {
        // Reached via a posted Runnable / layout-change listener that can fire after
        // the view is torn down; guard the binding (an _binding!! read would NPE
        // there).
        val b = _binding ?: return
        if (b.cardTimeRange.visibility != View.VISIBLE) return
        val cardLp = b.cardTimeRange.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val gap = (12 * resources.displayMetrics.density).toInt()
        val target = b.cardTimeRange.height + cardLp.bottomMargin + gap
        val fabParams = b.fabStack.layoutParams
            as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        if (fabParams.bottomMargin != target) {
            fabParams.bottomMargin = target
            b.fabStack.layoutParams = fabParams
        }
    }

    private fun toggleHistoryPanel() {
        if (binding.cardTimeRange.visibility == View.VISIBLE) {
            hideHistoryPanel()
        } else {
            showHistoryPanel()
        }
    }

    private fun showHistoryPanel() {
        binding.cardTimeRange.visibility = View.VISIBLE
        binding.fabHistory.setImageResource(R.drawable.ic_close)
        binding.cardTimeRange.addOnLayoutChangeListener(historyCardLayoutListener)
        binding.cardTimeRange.post { repositionFabsAboveHistory() }
        syncOverlayBackCallback()
    }

    private fun hideHistoryPanel() {
        binding.cardTimeRange.removeOnLayoutChangeListener(historyCardLayoutListener)
        binding.cardTimeRange.visibility = View.GONE
        syncOverlayBackCallback()
        binding.chipLive.isChecked = true
        binding.chipCustomRange.text = getString(R.string.map_custom_range)
        binding.btnExportCoordinates.visibility = View.GONE
        viewModel.setTimeRangeMode(TimeRangeMode.LIVE)
        // Drop any single-member filter so the next open defaults to all members.
        viewModel.selectMember(null)
        binding.fabHistory.setImageResource(R.drawable.ic_history)
        val fabParams = binding.fabStack.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        fabParams.bottomMargin = resources.getDimensionPixelSize(R.dimen.fab_margin_default)
        binding.fabStack.layoutParams = fabParams
    }

    private var previousTimeRangeChipId = R.id.chipLive

    private fun setupTimeRangeChips() {
        binding.chipGroupTimeRange.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipCustomRange)) {
                showDateRangePicker()
                return@setOnCheckedStateChangeListener
            }
            val mode = when {
                checkedIds.contains(R.id.chipLive) -> TimeRangeMode.LIVE
                checkedIds.contains(R.id.chip1Hour) -> TimeRangeMode.HOUR_1
                checkedIds.contains(R.id.chip24Hours) -> TimeRangeMode.HOURS_24
                checkedIds.contains(R.id.chipToday) -> TimeRangeMode.TODAY
                checkedIds.contains(R.id.chip7Days) -> TimeRangeMode.DAYS_7
                else -> TimeRangeMode.LIVE
            }
            previousTimeRangeChipId = checkedIds.firstOrNull() ?: R.id.chipLive
            binding.btnExportCoordinates.visibility = if (mode == TimeRangeMode.LIVE) View.GONE else View.VISIBLE
            viewModel.setTimeRangeMode(mode)
        }
    }

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(getString(R.string.map_select_date_range))
            .build()

        picker.addOnPositiveButtonClickListener { selection ->
            val startMillis = selection.first
            val endMillis = selection.second + (24 * 60 * 60 * 1000 - 1) // End of day
            viewModel.setCustomTimeRange(startMillis, endMillis)
            previousTimeRangeChipId = R.id.chipCustomRange

            val dateFormat = localFormat("MMMd")
            binding.chipCustomRange.text = getString(
                R.string.map_date_range,
                dateFormat.format(java.util.Date(startMillis)),
                dateFormat.format(java.util.Date(endMillis)),
            )

            binding.btnExportCoordinates.visibility = View.VISIBLE
        }

        picker.addOnNegativeButtonClickListener {
            binding.chipGroupTimeRange.check(previousTimeRangeChipId)
        }

        picker.addOnCancelListener {
            binding.chipGroupTimeRange.check(previousTimeRangeChipId)
        }

        picker.show(childFragmentManager, "dateRangePicker")
    }

    private fun setupSendLocationButton() {
        binding.fabSendLocation.setOnClickListener {
            clearFannedCluster()
            sendLocationNow()
        }
    }

    private fun setupTrackingToggle() {
        binding.fabTracking.setOnClickListener {
            clearFannedCluster()
            startContinuousTracking()
        }
    }

    private fun startContinuousTracking() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askForLocation()
            return
        }

        // Tracking runs whenever someone is signed in, so this button only ever starts it. A stop
        // here would be recorded nowhere, so the keep-alive (LocationKeepAlive) would undo it, and
        // nobody else could see it: the stops others see are per circle (a pause, the real-time
        // switch, leaving).
        // Routed through MainViewModel so the Settings screen's toggle stays in sync.
        if (mainViewModel.isTracking.value) return
        mainViewModel.startTracking()
        showStatus(getString(R.string.map_tracking_started))
        updateTrackingFabIcon(true)
    }

    private fun updateTrackingFabIcon(isTracking: Boolean) {
        // Reached from the mainViewModel.isTracking collector too; no-op if the view's gone.
        val b = _binding ?: return
        if (isTracking) {
            b.fabTracking.setImageResource(R.drawable.ic_play_with_arrows_animated)
            b.fabTracking.contentDescription = getString(R.string.map_tracking_active_cd)
            // post() so the drawable is fully inflated before start() is called.
            b.fabTracking.post {
                // The view can be torn down before a posted Runnable runs (a quick
                // map → other-tab switch), so re-read the binding rather than
                // trusting the one captured above — `binding` would NPE here.
                val pb = _binding ?: return@post
                (pb.fabTracking.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.start()
            }
        } else {
            b.fabTracking.setImageResource(R.drawable.ic_play_with_arrows)
            b.fabTracking.contentDescription = getString(R.string.map_start_tracking_cd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendLocationNow() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            askForLocation()
            return
        }

        binding.fabSendLocation.isEnabled = false
        // Where it goes is decided now, at the tap: the circle on screen, or on Just Me this account's
        // own history. The precise fix can take seconds, and the circle on screen can change, or go,
        // meanwhile.
        val target = sendTargetNow()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val location = locationProvider.currentLocation(
                    com.pingolin.track.location.LocationAccuracy.HIGH
                )

                viewModel.sendLocationNow(location, target)
            } catch (e: Exception) {
                showStatus(getString(R.string.map_location_failed))
                binding.fabSendLocation.isEnabled = true
            }
        }
    }

    private fun observeViewModel() {
        // Keep the persisted "open to my location" seed fresh: store our own position
        // whenever it updates, so the next cold start centers on where we actually
        // are rather than where we last cold-started.
        // STARTED-scoped: the seed only matters while the map is showing, and the
        // write is an EncryptedSharedPreferences rewrite of the file that also holds
        // the SDK's keys — so it is rate-limited too.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.selfLocation.collect { pos ->
                    if (pos != null) maybePersistSelfLoc(pos.latitude, pos.longitude)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // STARTED-scoped, and acting only on a change of circle: the circle
            // list re-emits whenever a refresh rewrites a row, so a collector
            // living from onViewCreated to onDestroyView that called
            // setSelectedCircle -> startPolling on EVERY emission would restart
            // the 30 s poll after each background circles refresh, for a map
            // nobody can see. onResume starts the poll when the map is visible;
            // this only has to follow a different circle being picked.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var firstEmission = true
                mainViewModel.selectedCircle
                    // A refreshed copy of the same circle changes nothing here but,
                    // possibly, the name on the chip.
                    .distinctUntilChanged { old, new -> old?.id == new?.id && old?.name == new?.name }
                    .collectLatest { circle ->
                        if (circle != null) {
                            if (viewModel.selectedCircleId.value != circle.id) {
                                viewModel.setSelectedCircle(circle.id)
                            }
                        } else if (firstEmission || viewModel.selectedCircleId.value != null) {
                            // "Just me": clearing also refreshes personal places, which
                            // is worth doing when the map comes up in that mode.
                            viewModel.clearSelectedCircle()
                        }
                        firstEmission = false
                        // View touches only if the view is still alive:
                        // collectLatest resumes on Main.immediate, and on a
                        // cold start from a notification this activity-scoped
                        // flow can re-emit while the fragment's view is being
                        // torn down/recreated — accessing binding (_binding!!)
                        // then would crash with an NPE.
                        val b = _binding ?: return@collectLatest
                        b.tvCircleName.text = if (circle != null) circle.name else getString(R.string.map_just_me)
                        // The layers chip card is NOT revealed here — it stays hidden until
                        // the user taps the Layers control in the left stack.
                        b.cardCircleSelector.visibility = View.VISIBLE
                        b.mapControlsLeft.visibility = View.VISIBLE
                        topLeftStack?.place()
                    }
            }
        }

        // Zoom requests from other fragments (e.g. tapping a row in the Places list).
        // The request is held as STATE in MainViewModel rather than sent as an event:
        // PlacesFragment fires it and navigates immediately, so an event would be
        // emitted before this fragment had a subscriber attached and would be lost.
        // As state, whatever is pending is picked up here and then consumed.
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.mapZoomRequests
                .filterNotNull()
                .collectLatest { req ->
                    val map = mapLibreMap
                    if (map != null) {
                        map.animateCamera(cameraFor(req), 1000)
                        mainViewModel.consumeMapZoomRequest(req.token)
                        consumedPendingZoom = true
                        hasInitiallyZoomedToMembers = true
                    }
                }
        }

        // "Show this member on the map" — raised by tapping a member row in the
        // circles sheet. Same state-not-event reasoning as the zoom requests
        // above; the difference is that this one has to WAIT. The caller only
        // knows the member's id: their position is per-circle encrypted state
        // that lands in memberLocations after the circle switch re-subscribes
        // the Room flow and the rows decrypt, which is not instant on a cold
        // open. collectLatest cancels an in-flight wait if a second member is
        // tapped meanwhile.
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.memberFocusRequests
                .filterNotNull()
                .collectLatest { req ->
                    // Sharing off or paused in this circle: there is no marker to
                    // show, so say why at once instead of waiting out a position
                    // that isn't coming. Real-time off with a frozen row to show
                    // goes on to it: that's where they were when they turned it
                    // off, and the marker says so.
                    val status = viewModel.sharingStatus(req.circleId, req.userId)
                    val notSharing = com.pingolin.track.ui.circles.MemberSharing.detailSentence(
                        requireContext(),
                        memberName(req.userId),
                        status,
                        System.currentTimeMillis(),
                    ).takeIf {
                        req.userId != sessionManager.getUserId() &&
                            !(status == com.pingolin.track.ui.circles.SharingStatus.Off && viewModel.hasFrozenRow(req.circleId, req.userId))
                    }
                    if (notSharing != null) {
                        mainViewModel.consumeMemberFocusRequest(req.token)
                        Toast.makeText(requireContext(), notSharing, Toast.LENGTH_LONG).show()
                        return@collectLatest
                    }
                    val location = withTimeoutOrNull(MEMBER_FOCUS_TIMEOUT_MS) {
                        // getMapAsync may still be in flight — without the map
                        // there is nothing to animate and the request would be
                        // consumed for nothing.
                        while (mapLibreMap == null) delay(50)
                        // Match the circle too: memberLocations is a stateIn
                        // cache, so right after the circle switch it still
                        // holds the PREVIOUS circle's rows, and a member who
                        // is in both would otherwise be focused at whatever
                        // position that circle's privacy settings allowed.
                        viewModel.memberLocations
                            .mapNotNull { locs ->
                                locs.firstOrNull {
                                    it.userId == req.userId && it.circleId == req.circleId
                                }
                            }
                            .first()
                    }
                    mainViewModel.consumeMemberFocusRequest(req.token)
                    if (location == null) {
                        // Nothing decrypted for them: they've never shared a
                        // position, or are paused. Say so rather than leaving
                        // the tap looking broken.
                        Toast.makeText(
                            requireContext(),
                            getString(
                                R.string.member_focus_no_location,
                                memberName(req.userId),
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@collectLatest
                    }
                    val map = mapLibreMap ?: return@collectLatest
                    // An approximate member is the whole of their area: frame it,
                    // rather than closing in on its centre as if that were where
                    // they stand.
                    val spot = spotOf(location)
                    val focus = if (spot is MemberSpot.Approximate) {
                        val shown = displayed(location)
                        val box = ApproximateArea.box(shown.latitude, shown.longitude, spot.radiusMeters)
                        CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.Builder()
                                .include(LatLng(box.south, box.west))
                                .include(LatLng(box.north, box.east))
                                .build(),
                            120,
                        )
                    } else {
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(location.latitude, location.longitude))
                                .zoom(16.0)
                                .build()
                        )
                    }
                    map.animateCamera(focus, 1000)
                    // Same as the pending-zoom path: the user picked this
                    // target, so don't let the auto-fit-to-members camera take
                    // it back when the rest of the circle decrypts.
                    consumedPendingZoom = true
                    hasInitiallyZoomedToMembers = true
                    showMemberBottomSheet(location)
                }
        }

        // Locations and members MUST be collected together, not as two collectors
        // that each call updateMemberMarkers. On a warm resume Room rehydrates the
        // locations flow a beat before members, so a locations-only collector would
        // render a populated location list while members.value was still the
        // WhileSubscribed seed `emptyList()` — getMemberName returns "Unknown" for
        // every peer and each marker flashes a "U" initial until the other collector
        // catches up. Combined, the render is coherent: either both, or wait.
        //
        // No debounce: these are conflated StateFlows and collectLatest already drops
        // intermediate values, while the underlying Room flow emits cached rows the
        // instant it is subscribed — a debounce would only delay the first paint.
        viewLifecycleOwner.lifecycleScope.launch {
            // Only collect while the fragment is at least STARTED. While backgrounded
            // this hot collector — and the updateMemberMarkers work it drives — is
            // suspended, then resumes and re-renders from the conflated StateFlows on
            // return to the foreground.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(
                    viewModel.memberLocations,
                    viewModel.members,
                    // A member turning approximate (or back) redraws them as an area (or a pin).
                    approximateMembers.areas,
                ) { locations, members, _ -> locations to members }
                    .collectLatest { (locations, members) ->
                        // In history mode the live marker must stay frozen at
                        // whatever position it held when the user opened
                        // history — otherwise a stray server-side location
                        // update rearranges the marker the user is visually
                        // anchored to. When history mode ends (back to LIVE),
                        // the next tick resumes updates.
                        if (!viewModel.showMembers.value || viewModel.isShowingHistory()) return@collectLatest

                        // Guard against the locations-rehydrated-before-
                        // members frame. Skipping this render is safe — the
                        // very next emission has both populated, and the
                        // canvas keeps whatever it last drew until then.
                        if (locations.isNotEmpty() && members.isEmpty()) return@collectLatest

                        updateMemberMarkers(locations)
                        clearTrails()
                        // Members moved -> recompute place occupancy so the zone circles and
                        // hidden pins track who is currently inside each place.
                        if (viewModel.showPlaces.value) {
                            updatePlaceMarkers(filterVisiblePlaces(viewModel.places.value))
                        }
                    }
            }
        }


        // Periodic re-render so the "5m ago" label keeps aging honestly even when no
        // new location arrives (user stationary). Skipped in history mode for the
        // same freeze-the-marker reason above.
        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle cancels this loop when the fragment stops and restarts
            // it on the next start, so the 60s re-render (which wakes
            // updateMemberMarkers) doesn't keep firing while the map is backgrounded.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    kotlinx.coroutines.delay(60_000L)
                    if (viewModel.showMembers.value && !viewModel.isShowingHistory()) {
                        val locs = viewModel.memberLocations.value
                        if (locs.isEmpty() || viewModel.members.value.isNotEmpty()) {
                            updateMemberMarkers(locs)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.places
                .collectLatest { places ->
                    val visiblePlaces = filterVisiblePlaces(places)
                    if (viewModel.showPlaces.value) {
                        updatePlaceMarkers(visiblePlaces)
                    }
                    if (viewModel.showBoundaries.value) {
                        updateBoundaryCircles(visiblePlaces)
                    }
                }
        }

        // Per-user geofence ring: track the places THIS user has an active alert on and
        // redraw the boundary circles when that set changes (e.g. the user creates or
        // removes an alert). Gating the ring on this — not the place-level alertsEnabled —
        // stops a share recipient from seeing a ring drawn from the owner's alert.
        viewLifecycleOwner.lifecycleScope.launch {
            placeRepository.observeAlertedPlaceIds().collectLatest { ids ->
                viewerAlertPlaceIds = ids.toSet()
                if (viewModel.showBoundaries.value) {
                    updateBoundaryCircles(filterVisiblePlaces(viewModel.places.value))
                }
            }
        }

        // Destinations: refresh the server-side list when a circle is
        // selected (observeByCircle reads local DB; the repository syncs
        // in the background), then mirror active destinations to the map
        // as flag markers showing the creator's name.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedCircleId.collectLatest { circleId ->
                if (circleId != null) {
                    try { destinationRepository.refreshDestinations() } catch (_: Exception) {}
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.destinations
                .collectLatest { destinations ->
                    // Badge the always-visible ⋮ FAB when OTHER members have an
                    // active destination (yours you already know). Independent of
                    // the layer toggle — an awareness cue, not a marker.
                    val selfId = sessionManager.getUserId()
                    updateDestinationsBadge(destinations.any { it.status == "active" && !it.isMine(selfId) })
                    if (viewModel.showDestinations.value) {
                        updateDestinationMarkers(destinations)
                    }
                }
        }

        // Active SOS alerts. Activity-scoped and cross-circle: unlike every
        // other layer here this one is NOT re-filtered when the selected circle
        // changes, because an emergency in another circle still has to show.
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.mappableSosAlerts.collectLatest { alerts ->
                updateSosMarkers(alerts)
                // The raiser's own avatar carries the SOS (band +
                // pulse), so a change to the alert set has to
                // re-bake the member markers too — otherwise a new
                // emergency shows no marking until the next
                // unrelated location tick, and a resolved one keeps
                // its pulse.
                if (viewModel.showMembers.value && !viewModel.isShowingHistory()) {
                    updateMemberMarkers(viewModel.memberLocations.value)
                }
            }
        }

        // Refresh-status pill. Cached markers paint immediately when the
        // map opens, which is great when the network is fine — but on a
        // spotty connection the user might mistake stale cached positions
        // for live ones. This strip makes the *state of the refresh
        // attempt* visible:
        //   in flight → small spinner + "Updating…"
        //   most recent attempt failed → warning icon + "Couldn't reach
        //     server — showing last known"
        //   steady (last attempt succeeded) → hidden
        // We don't celebrate success; the absence of the pill is the
        // success signal.
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                viewModel.isRefreshingLocations,
                viewModel.lastRefreshFailedAt,
            ) { refreshing, failedAt ->
                when {
                    // A pending failure outranks an in-flight retry: during an outage each
                    // retry briefly flips refreshing=true, but we must not flip the pill back
                    // to "Updating" (which would restart the debounce below). failedAt stays
                    // non-null for the whole outage and is cleared only by a successful poll.
                    failedAt != null -> RefreshPillState.FAILED
                    refreshing -> RefreshPillState.UPDATING
                    else -> RefreshPillState.HIDDEN
                }
            }
                .distinctUntilChanged()
                .collectLatest { state ->
                    if (_binding == null) return@collectLatest
                    val card = binding.cardRefreshStatus
                    val spinner = binding.progressRefreshStatus
                    val icon = binding.iconRefreshStatus
                    val text = binding.tvRefreshStatus
                    when (state) {
                        RefreshPillState.UPDATING -> {
                            spinner.visibility = View.VISIBLE
                            icon.visibility = View.GONE
                            text.setText(R.string.map_refresh_status_updating)
                            text.setTextColor(
                                com.google.android.material.color.MaterialColors.getColor(
                                    card,
                                    com.google.android.material.R.attr.colorOnSurface
                                )
                            )
                            card.visibility = View.VISIBLE
                        }
                        RefreshPillState.FAILED -> {
                            // Debounce: hold off a few seconds before showing the red
                            // "couldn't reach server" pill. If the connection re-establishes
                            // within the grace window the status leaves FAILED and
                            // collectLatest cancels this coroutine before it ever shows — so
                            // brief open/resume interruptions stay silent, while a real outage
                            // still surfaces after the grace period.
                            delay(FAILURE_PILL_GRACE_MS)
                            if (_binding == null) return@collectLatest
                            spinner.visibility = View.GONE
                            icon.visibility = View.VISIBLE
                            text.setText(R.string.map_refresh_status_offline)
                            text.setTextColor(
                                com.google.android.material.color.MaterialColors.getColor(
                                    card,
                                    androidx.appcompat.R.attr.colorError
                                )
                            )
                            card.visibility = View.VISIBLE
                        }
                        RefreshPillState.HIDDEN -> {
                            card.visibility = View.GONE
                        }
                    }
                }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showDestinations.collectLatest { show ->
                if (show) updateDestinationMarkers(viewModel.destinations.value)
                else clearDestinationMarkers()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showBoundaries.collectLatest { show ->
                if (_binding == null) return@collectLatest
                // Detach listener to prevent infinite toggle loop
                binding.chipLayerBoundaries.setOnCheckedChangeListener(null)
                binding.chipLayerBoundaries.isChecked = show
                binding.chipLayerBoundaries.setOnCheckedChangeListener { _, _ ->
                    viewModel.toggleShowBoundaries()
                }
                if (show) {
                    // Filter to the currently-visible place set (same as the sibling
                    // call sites) so toggling the layer doesn't draw rings for hidden
                    // shared places.
                    updateBoundaryCircles(filterVisiblePlaces(viewModel.places.value))
                } else {
                    clearBoundaryCircles()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showPlaces.collectLatest { show ->
                if (_binding == null) return@collectLatest
                // Detach listener to prevent infinite toggle loop
                binding.chipLayerPlaces.setOnCheckedChangeListener(null)
                binding.chipLayerPlaces.isChecked = show
                binding.chipLayerPlaces.setOnCheckedChangeListener { _, _ ->
                    viewModel.toggleShowPlaces()
                }
                if (show) {
                    updatePlaceMarkers(filterVisiblePlaces(viewModel.places.value))
                } else {
                    clearPlaceMarkers()
                }
            }
        }

        // Independent toggle for received shares — re-runs the marker
        // update with the current sharedPlaces filter applied. The
        // toggle is purely a filter; user-owned places stay visible.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showSharedPlaces.collectLatest { show ->
                if (_binding == null) return@collectLatest
                binding.chipLayerSharedPlaces.setOnCheckedChangeListener(null)
                binding.chipLayerSharedPlaces.isChecked = show
                binding.chipLayerSharedPlaces.setOnCheckedChangeListener { _, _ ->
                    viewModel.toggleShowSharedPlaces()
                }
                if (viewModel.showPlaces.value) {
                    updatePlaceMarkers(filterVisiblePlaces(viewModel.places.value))
                }
                if (viewModel.showBoundaries.value) {
                    updateBoundaryCircles(filterVisiblePlaces(viewModel.places.value))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.showMembers.collectLatest { show ->
                if (_binding == null) return@collectLatest
                // Detach while syncing, or the programmatic set bounces straight back
                // through toggleShowMembers.
                binding.chipLayerMembers.setOnCheckedChangeListener(null)
                binding.chipLayerMembers.isChecked = show
                binding.chipLayerMembers.setOnCheckedChangeListener { _, _ ->
                    viewModel.toggleShowMembers()
                }
                if (show) {
                    // Re-render from the cached locations. The combined
                    // locations+members collector only redraws when one of those
                    // flows emits, and flipping this filter back on doesn't make
                    // them re-emit — without an explicit redraw here the markers
                    // stay cleared and never come back. Mirror that collector's
                    // guards: skip in history mode (the live marker is frozen
                    // there), and never paint a locations-without-members frame
                    // (it would flash "Unknown").
                    if (!viewModel.isShowingHistory()) {
                        val locs = viewModel.memberLocations.value
                        if (locs.isEmpty() || viewModel.members.value.isNotEmpty()) {
                            updateMemberMarkers(locs)
                            clearTrails()
                        }
                    }
                } else {
                    updateMemberMarkers(emptyList())
                }
            }
        }

        // Observe location history for trails. Re-run on selectedMemberId or
        // memberLocations change too so (a) filtering to a single member and
        // (b) extending the trail to the current marker both stay live.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                viewModel.locationHistory,
                viewModel.selectedMemberId,
                viewModel.memberLocations,
                viewModel.timeRangeMode
            ) { history, selectedId, current, mode ->
                Quadruple(history, selectedId, current, mode)
            }.collectLatest { (history, selectedMemberId, currentLocations, mode) ->
                if (!viewModel.isShowingHistory()) return@collectLatest

                val filtered: Map<String, List<LocationEntity>> = if (selectedMemberId != null) {
                    history.filterKeys { it == selectedMemberId }
                } else {
                    history
                }

                // For time ranges that extend to "now" (1h / 24h / today / 7d /
                // a custom range ending in the present), make sure each rendered
                // trail reaches the current marker — `fetchLocationHistory` can
                // return data slightly older than the live poll, which leaves a
                // visible gap between the trail end and the marker.
                //
                // ONLY paths that already have historical points may be extended.
                // When the user first taps a history chip, the combined flow fires
                // with an empty `filtered` while the fetch is still in flight;
                // injecting live markers into that empty map makes drawLocationTrails
                // fitBounds across just the current members — usually two
                // close-together points — and zoom to house level. The real history
                // lands milliseconds later and fitBounds kicks back out to the full
                // extent, which reads on screen as a zoom-in/zoom-out flicker.
                val extendsToNow = mode != TimeRangeMode.LIVE && mode != TimeRangeMode.CUSTOM
                val extended = if (extendsToNow && filtered.isNotEmpty()) {
                    val merged = filtered.toMutableMap()
                    currentLocations.forEach { curLoc ->
                        if (selectedMemberId != null && curLoc.userId != selectedMemberId) return@forEach
                        val existing = merged[curLoc.userId] ?: return@forEach
                        if (curLoc.timestamp > existing.last().timestamp) {
                            merged[curLoc.userId] = existing + curLoc
                        }
                    }
                    merged
                } else {
                    filtered
                }

                if (extended.isNotEmpty()) drawLocationTrails(extended) else clearTrails()
            }
        }

        // Show a "Viewing history for X" label in the history panel whenever a
        // specific member is selected (and clear it otherwise).
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedMemberId.collectLatest { id ->
                if (_binding == null) return@collectLatest
                val label = binding.tvHistoryMemberFilter
                if (id != null) {
                    label.text = getString(R.string.map_history_only_member, memberName(id))
                    label.visibility = View.VISIBLE
                } else {
                    label.visibility = View.GONE
                }
                // Theirs may be a history they keep from the circle.
                renderHistoryFooter()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoadingHistory.collectLatest { isLoading ->
                if (_binding == null) return@collectLatest
                binding.progressHistory.visibility = if (isLoading) View.VISIBLE else View.GONE
            }
        }

        // Either flow can land last, and the footer's text depends on both.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.locationHistory.collectLatest {
                renderHistoryFooter()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.locationHistoryTruncated.collectLatest {
                renderHistoryFooter()
            }
        }
        // And whose history is hidden, which can change while a range is on screen.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hiddenHistory.collectLatest {
                renderHistoryFooter()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.timeRangeMode.collectLatest { mode ->
                if (_binding == null) return@collectLatest
                if (mode == TimeRangeMode.LIVE) {
                    clearTrails()
                    binding.layoutHistoryFooter.visibility = View.GONE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sendLocationResult.collectLatest { result ->
                if (_binding == null) return@collectLatest
                when (result) {
                    // Said as it happened, to whom: the circle on screen, or on
                    // Just Me this account's own history; kept to go when it can;
                    // or not sent.
                    is SendLocationResult.Success -> {
                        showStatus(
                            result.circleId?.let { sendNowLine(it, R.string.send_now_sent, R.string.send_now_sent_unnamed) }
                                ?: getString(R.string.send_now_saved_to_history)
                        )
                        viewModel.clearSendLocationResult()
                    }
                    is SendLocationResult.Waiting -> {
                        showStatus(
                            when {
                                result.circleId == null -> getString(R.string.send_now_waiting_history)
                                result.catchingUp -> sendNowLine(result.circleId, R.string.send_now_waiting_for_circle, R.string.send_now_waiting_for_circle_unnamed)
                                else -> sendNowLine(result.circleId, R.string.send_now_waiting, R.string.send_now_waiting_unnamed)
                            }
                        )
                        viewModel.clearSendLocationResult()
                        if (result.catchingUp) binding.fabSendLocation.isEnabled = true
                    }
                    is SendLocationResult.NotSharing -> {
                        showStatus(
                            if (result.paused) sendNowLine(result.circleId, R.string.send_now_not_sent_paused, R.string.send_now_not_sent_paused_unnamed)
                            else sendNowLine(result.circleId, R.string.send_now_not_sent_off, R.string.send_now_not_sent_off_unnamed)
                        )
                        viewModel.clearSendLocationResult()
                        binding.fabSendLocation.isEnabled = true
                    }
                    is SendLocationResult.CircleGone -> {
                        // Its readable name, never the "Locked circle" label; without one,
                        // the unnamed line, and no fallback name in the frame (in some
                        // languages that reads as "your circle").
                        val name = sendNowReadableName(result.circleName)
                        showStatus(
                            name?.let { getString(R.string.send_now_not_sent_gone, it) }
                                ?: getString(R.string.send_now_not_sent_gone_unnamed)
                        )
                        viewModel.clearSendLocationResult()
                        binding.fabSendLocation.isEnabled = true
                    }
                    is SendLocationResult.RateLimited -> {
                        showStatus(resources.getQuantityString(R.plurals.send_now_not_sent_wait, result.waitSeconds, result.waitSeconds))
                        viewModel.clearSendLocationResult()
                        binding.fabSendLocation.isEnabled = true
                    }
                    is SendLocationResult.Error -> {
                        // "No circle" is a setup problem, not a failure — say what to do.
                        val message = if (result.message?.contains("No circle", ignoreCase = true) == true) {
                            getString(R.string.map_join_circle_to_share)
                        } else {
                            result.message ?: getString(result.fallback)
                        }
                        showStatus(message)
                        viewModel.clearSendLocationResult()
                        binding.fabSendLocation.isEnabled = true
                    }
                    null -> { /* No result */ }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.sendLocationCooldown.collectLatest { cooldownMs ->
                if (_binding == null) return@collectLatest
                binding.fabSendLocation.isEnabled = cooldownMs == 0L && !viewModel.isSendingLocation.value
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSendingLocation.collectLatest { isSending ->
                if (_binding == null) return@collectLatest
                binding.fabSendLocation.isEnabled = !isSending && viewModel.sendLocationCooldown.value == 0L
            }
        }

        // MainViewModel owns tracking state, so the FAB follows the Settings toggle too.
        viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.isTracking.collectLatest { isTracking ->
                updateTrackingFabIcon(isTracking)
            }
        }

        // Show brief status when a location is sent to the server during tracking
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.locationSentEvent.collectLatest { timestampMs ->
                showStatus(getString(R.string.map_location_sent_at, localTime(timestampMs, withSeconds = true)))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.createPlaceResult.collectLatest { result ->
                when (result) {
                    is CreatePlaceResult.Success -> {
                        showStatus(getString(R.string.place_created))
                        viewModel.clearCreatePlaceResult()
                    }
                    is CreatePlaceResult.Error -> {
                        // A place-count 402 carries quota/error_code — show the
                        // premium prompt rather than a terse status message.
                        if (result.quota != null || result.errorCode != null) {
                            com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.showForQuota(
                                parentFragmentManager, result.quota, result.errorCode,
                            )
                        } else {
                            showStatus(result.message ?: getString(R.string.place_create_failed))
                        }
                        viewModel.clearCreatePlaceResult()
                    }
                    null -> { /* No result */ }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.decryptionWarning.collectLatest { warning ->
                if (_binding == null) return@collectLatest
                if (warning != null) {
                    val snackbar = Snackbar.make(
                        binding.root,
                        warning.waitingCount?.let { resources.getQuantityString(R.plurals.map_locations_waiting_for_sync, it, it) }
                            ?: getString(R.string.map_awaiting_circle_sync),
                        Snackbar.LENGTH_LONG
                    )
                    // Nothing to tap when locations can't be opened yet: the app fetches
                    // what it is missing by itself (MapViewModel.importKeysSoon).
                    if (!warning.isKeyMissing) {
                        snackbar.setAction(getString(R.string.map_dismiss)) {
                            viewModel.clearDecryptionWarning()
                        }
                    }
                    snackbar.show()
                    viewModel.clearDecryptionWarning()
                }
            }
        }

        // The tapped-location sheet is created on first tap and updated in place as
        // the address geocodes; a null state (user dismissed, or dismissTappedLocation)
        // tears it down.
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tappedLocation.collectLatest { tapped ->
                if (tapped == null) {
                    tappedLocationSheet?.dismiss()
                    return@collectLatest
                }
                if (!isAdded) return@collectLatest
                val sheet = tappedLocationSheet
                    ?: createTappedLocationSheet().also { tappedLocationSheet = it }
                bindTappedLocationSheet(sheet, tapped)
                if (!sheet.isShowing) sheet.show()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mapBearing.collectLatest { bearing ->
                if (_binding == null) return@collectLatest
                binding.ivCompassArrow.rotation = -bearing.toFloat()
                // Tinted primary while rotated so the reset-north affordance stands out.
                val colorAttr = if (Math.abs(bearing) > 0.5) {
                    androidx.appcompat.R.attr.colorPrimary
                } else {
                    android.R.attr.textColorSecondary
                }
                val typedArray = requireContext().obtainStyledAttributes(intArrayOf(colorAttr))
                val color = typedArray.getColor(0, Color.GRAY)
                typedArray.recycle()
                binding.ivCompassArrow.setColorFilter(color)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deletePlaceResult.collectLatest { result ->
                if (result != null) {
                    result.fold(
                        onSuccess = {
                            showStatus(getString(R.string.place_deleted))
                        },
                        onFailure = { e ->
                            showStatus(e.plainText(getString(R.string.place_delete_failed)))
                        }
                    )
                    viewModel.clearDeletePlaceResult()
                }
            }
        }

    }

    /**
     * The history panel's footer: how many points the trail is drawn from, and
     * whether that is all of them.
     *
     * There is no "Load more": the response already spans the whole selected
     * range, downsampled, so there is no later page holding the rest. The honest
     * affordance when a range is too big is a SHORTER range, so when the server
     * reports it dropped the oldest points the count line says so and says that.
     */
    private fun renderHistoryFooter() {
        if (_binding == null) return
        // One member's history, and they keep it from the circle: the view says so, not an
        // empty trail or an error.
        val hiddenMember = viewModel.selectedMemberId.value
            ?.takeIf { viewModel.isShowingHistory() && viewModel.historyHiddenFor(it) }
        if (hiddenMember != null) {
            binding.layoutHistoryFooter.visibility = View.VISIBLE
            binding.tvHistoryCount.text = com.pingolin.track.ui.circles.MemberSharing
                .historyOffSentence(requireContext(), memberName(hiddenMember))
            return
        }
        val totalPoints = viewModel.locationHistory.value.values.sumOf { it.size }
        if (viewModel.isShowingHistory() && totalPoints > 0) {
            binding.layoutHistoryFooter.visibility = View.VISIBLE
            binding.tvHistoryCount.text = resources.getQuantityString(
                if (viewModel.locationHistoryTruncated.value) R.plurals.map_history_points_truncated else R.plurals.map_history_points_loaded,
                totalPoints,
                totalPoints,
            )
        } else {
            binding.layoutHistoryFooter.visibility = View.GONE
        }
    }

    /** The circle on screen now, as a send's destination, with the name the screen shows. */
    private fun sendTargetNow(): MapViewModel.SendTarget {
        val circleId = viewModel.selectedCircleId.value
        return MapViewModel.SendTarget(circleId, circleId?.let { id -> mainViewModel.circles.value.firstOrNull { com.pingolin.track.util.AccountIds.same(it.id, id) }?.name })
    }

    /**
     * A "Send location now" line about the circle on screen: [named] with its readable name, else the whole
     * [unnamed] line. Never a stand-in put into the frame, and never the locked circle's "Locked circle" label;
     * the circle is found by its canonical id, so another spelling of it still finds its name.
     */
    private fun sendNowLine(circleId: String, @androidx.annotation.StringRes named: Int, @androidx.annotation.StringRes unnamed: Int): String =
        sendNowReadableName(mainViewModel.circles.value.firstOrNull { com.pingolin.track.util.AccountIds.same(it.id, circleId) }?.name)
            ?.let { getString(named, it) } ?: getString(unnamed)

    /** Clear the tapped-location state + its map marker. The bottom sheet observing
     *  [MapViewModel.tappedLocation] dismisses itself when the state goes null. Shared
     *  by any flow that needs the placard gone (e.g. opening the layers filter). */
    private fun dismissTappedLocation() {
        viewModel.clearTappedLocation()
        clearTappedMarker()
    }

    /** Build the tapped-location bottom sheet (mirrors [showPlaceBottomSheet]). Action
     *  buttons read the latest [MapViewModel.tappedLocation] at click time. A user
     *  dismiss (swipe / scrim / back) clears the tapped state + its map marker. */
    private fun createTappedLocationSheet(): BottomSheetDialog {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_tapped_location, null)
        dialog.setContentView(view)

        view.findViewById<MaterialButton>(R.id.btnAddToPlaces).setOnClickListener {
            val tapped = viewModel.tappedLocation.value ?: return@setOnClickListener
            showCreatePlaceFromMapDialog(tapped.latitude, tapped.longitude, tapped.address)
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btnShareDestination).setOnClickListener {
            val tapped = viewModel.tappedLocation.value ?: return@setOnClickListener
            showShareDestinationDialog(tapped.latitude, tapped.longitude, tapped.address)
            dialog.dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btnCopyCoords).setOnClickListener {
            val tapped = viewModel.tappedLocation.value ?: return@setOnClickListener
            copyAddressToClipboard(tapped.address)
            dialog.dismiss()
        }

        // A user dismiss (swipe / scrim / back) clears the tapped state + marker.
        // onDestroyView nulls this listener first, so a teardown-dismiss doesn't
        // reach the ViewModel.
        dialog.setOnDismissListener {
            tappedLocationSheet = null
            viewModel.clearTappedLocation()
            clearTappedMarker()
        }
        return dialog
    }

    /** Bind the current tapped-location state into the sheet — called on every emission,
     *  so the "Looking up address…" spinner transitions to the resolved address while
     *  the same sheet stays open. */
    private fun bindTappedLocationSheet(dialog: BottomSheetDialog, tapped: MapViewModel.TappedLocation) {
        // Prefix: "<member> · <date> <time>: " for a tapped history point, "You are here: "
        // for own GPS, else empty for a plain map tap.
        // Whose and when; where to the metre only when the position isn't approximate.
        val at = coordinates(tapped.latitude, tapped.longitude)
        val ts = tapped.historicalTimestamp?.let { localDateTime(it) }
        val who = tapped.memberName?.takeIf { it.isNotBlank() }
        dialog.findViewById<TextView>(R.id.tvTappedCoordinates)?.text = when {
            tapped.isOwnLocation ->
                if (tapped.approximate) getString(R.string.map_tapped_you_are_here_approx) else getString(R.string.map_tapped_you_are_here, at)
            ts != null && who != null ->
                if (tapped.approximate) getString(R.string.map_tapped_member_at_approx, who, ts) else getString(R.string.map_tapped_member_at, who, ts, at)
            ts != null -> if (tapped.approximate) ts else getString(R.string.map_tapped_at, ts, at)
            else -> if (tapped.approximate) "" else at
        }

        val progress = dialog.findViewById<View>(R.id.progressTappedLocation)
        val tvName = dialog.findViewById<TextView>(R.id.tvTappedName)
        val tvAddress = dialog.findViewById<TextView>(R.id.tvTappedAddress)
        val actions = dialog.findViewById<View>(R.id.tappedLocationActions)
        if (tapped.approximate) {
            // No address, and nothing that hands the point on: Add to Places, Share, Copy.
            progress?.visibility = View.GONE
            tvName?.visibility = View.GONE
            tvAddress?.text = getString(R.string.member_approximate_location)
            actions?.visibility = View.GONE
        } else if (tapped.isLoading) {
            progress?.visibility = View.VISIBLE
            tvName?.visibility = View.GONE
            tvAddress?.text = getString(R.string.map_looking_up_address)
            actions?.visibility = View.GONE
        } else {
            progress?.visibility = View.GONE
            // Formal POI name gets the prominent slot; hide it entirely when absent so
            // the address reads as the title rather than sitting under a blank row.
            val name = tapped.name
            if (!name.isNullOrBlank()) {
                tvName?.visibility = View.VISIBLE
                tvName?.text = name
            } else {
                tvName?.visibility = View.GONE
            }
            tvAddress?.text = tapped.address ?: getString(
                when {
                    tapped.isOwnLocation -> R.string.map_your_location
                    tapped.historicalTimestamp != null -> R.string.map_historical_location
                    else -> R.string.map_address_unavailable
                }
            )
            actions?.visibility = View.VISIBLE
        }
    }

    // --- Layer Toggles ---

    private fun setupLayerToggles() {
        // The Layers control lives in the left stack and shows/hides the whole chip
        // card over on the right.
        binding.btnLayers.setOnClickListener {
            clearFannedCluster()
            val card = binding.cardMapLayers
            if (card.visibility == View.VISIBLE) {
                card.visibility = View.GONE
            } else {
                // Opening the filter: clear the tapped-location card first so
                // the two overlays (which share the upper-right region) can't
                // stack — the chip card's higher elevation would otherwise
                // cover that card's Share/Add-to-Places action buttons.
                dismissTappedLocation()
                card.visibility = View.VISIBLE
            }
            syncOverlayBackCallback()
        }

        binding.btnCloseLayers.setOnClickListener {
            binding.cardMapLayers.visibility = View.GONE
            syncOverlayBackCallback()
        }

        binding.chipLayerMembers.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowMembers()
        }
        binding.chipLayerPlaces.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowPlaces()
        }
        binding.chipLayerSharedPlaces.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowSharedPlaces()
        }
        binding.chipLayerDestinations.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowDestinations()
        }
        binding.chipLayerBoundaries.setOnCheckedChangeListener { _, _ ->
            viewModel.toggleShowBoundaries()
        }
    }

    // --- Coordinate Export ---

    private fun showCoordinateExportDialog() {
        val dialogBinding = DialogCoordinateExportBinding.inflate(layoutInflater)

        val members = viewModel.members.value
        val filterLabels = mutableListOf(getString(R.string.coord_export_all), getString(R.string.coord_export_you))
        val filterValues = mutableListOf("__all__", "__you__")
        members.forEach { member ->
            filterLabels.add(member.displayName)
            filterValues.add(member.userId)
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, filterLabels)
        dialogBinding.actvMemberFilter.setAdapter(adapter)
        dialogBinding.actvMemberFilter.setText(filterLabels[0], false)

        var currentFilter = "__all__"
        updateExportPreview(dialogBinding, currentFilter)

        dialogBinding.actvMemberFilter.setOnItemClickListener { _, _, position, _ ->
            currentFilter = filterValues[position]
            updateExportPreview(dialogBinding, currentFilter)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.coord_export_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.coord_export_button) { _, _ ->
                lifecycleScope.launch {
                    val (json, _) = viewModel.getExportCoordinates(currentFilter)
                    shareJsonExport(json)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateExportPreview(dialogBinding: DialogCoordinateExportBinding, memberFilter: String) {
        lifecycleScope.launch {
            val (json, count) = viewModel.getExportCoordinates(memberFilter)
            dialogBinding.tvPointCount.text = getString(R.string.coord_export_points, count)
            if (count > 0) {
                val previewLines = json.lines().take(100)
                val preview = if (previewLines.size < json.lines().size) {
                    previewLines.joinToString("\n") + "\n..."
                } else {
                    json
                }
                dialogBinding.tvJsonPreview.text = preview
                dialogBinding.tvJsonPreview.visibility = View.VISIBLE
                dialogBinding.tvEmptyState.visibility = View.GONE
            } else {
                dialogBinding.tvJsonPreview.visibility = View.GONE
                dialogBinding.tvEmptyState.visibility = View.VISIBLE
            }
        }
    }

    private fun shareJsonExport(json: String) {
        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "coordinates-$dateStr.json")
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.coord_export_title)))
        } catch (e: Exception) {
            showStatus(getString(R.string.coord_export_no_app))
        }
    }

    /**
     * icon-opacity for the SymbolLayers whose icons are bitmaps this fragment
     * adds at runtime (style.addImage) and selects per feature with
     * iconImage(get(...)).
     *
     * Not cosmetic. On this SDK (MapLibre 11.11.0) such a layer paints NOTHING
     * while icon-opacity is left at its default or set to a constant: the
     * symbol is placed — queryRenderedFeatures returns it and taps land on it —
     * but no pixels are drawn. Give the property a data-driven expression and
     * the same layer draws. It holds both ways: this expression alone makes an
     * invisible member layer draw, and replacing the places layer's
     * get("iconOpacity") with the constant 1.0f makes it vanish.
     *
     * [fallback] is what a feature without an "iconOpacity" property gets —
     * every feature these layers draw (a cluster badge carries no per-feature
     * properties at all). The get() is what keeps the expression data-driven;
     * the fallback is the value.
     */
    private fun dataDrivenIconOpacity(fallback: Float = 1.0f) =
        PropertyFactory.iconOpacity(
            Expression.coalesce(Expression.get("iconOpacity"), Expression.literal(fallback))
        )

    /**
     * Dim the cluster badge while a fan-out is active so the fanned members read
     * as the primary representation and the "2" bubble fades into a breadcrumb.
     * Also shrinks the icon to ~40% so it doesn't overlap and block taps on the
     * fanned member markers nearby.
     */
    private fun setClusterLayerDimmed(dimmed: Boolean) {
        val style = safeStyle ?: return
        val layer = style.getLayer(MEMBERS_CLUSTER_LAYER_ID) ?: return
        layer.setProperties(
            // Constant here would blank the badge, not dim it — see dataDrivenIconOpacity.
            dataDrivenIconOpacity(if (dimmed) 0.35f else 1.0f),
            PropertyFactory.iconSize(if (dimmed) 0.4f else 1.0f)
        )
    }

    // Tracks whether a cluster fan-out is currently visible. Needed so
    // updateMemberMarkers can re-apply the dimmed-cluster styling after rebuilding
    // the source + layers (which happens on every location poll). Without this,
    // the first periodic update after a fan-tap would reset the cluster badge back
    // to full size while the fanned markers are still on screen.
    @Volatile private var fanActive: Boolean = false

    // Every distinct member-icon bitmap registered via style.addImage in
    // getOrCreateMemberIcon. The icon id encodes the member's relative-age label
    // ("1m ago" → "2m ago" …), which rolls ~once a minute, so without pruning the
    // style's native image table would grow without bound — one leaked bitmap per
    // member per minute, never freed. updateMemberMarkers removes the ids no
    // longer referenced by a visible marker. Only touched on the map/main thread.
    private val registeredMemberIconIds = mutableSetOf<String>()

    /**
     * Fan a tapped cluster's members out around the cluster centre, with connector
     * lines back to it so it stays obvious that the markers share one real
     * location. Collapsed by a tap elsewhere, or by the camera listener in
     * setupMap when the zoom changes enough to dissolve the cluster.
     */
    private fun fanOutCluster(center: LatLng) {
        val map = mapLibreMap ?: return
        val style = safeStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(FAN_SOURCE_ID) ?: return

        // Clustering groups points within a screen-pixel radius, so membership of
        // this badge has to be recomputed the same way — in screen space.
        val centerPx = map.projection.toScreenLocation(center)
        val clusterPixelRadius = 60f
        // SOS raisers are drawn from their own unclustered source, so they are
        // never folded into this badge — fanning them out here would draw a
        // second, unmarked copy of an avatar that is already on screen.
        val sosIds = sosMemberIds()
        val members = viewModel.memberLocations.value.map { displayed(it) }.filter { loc ->
            if (loc.userId in sosIds) return@filter false
            val px = map.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
            val dx = px.x - centerPx.x
            val dy = px.y - centerPx.y
            Math.hypot(dx.toDouble(), dy.toDouble()) <= clusterPixelRadius
        }
        if (members.size < 2) {
            clearFannedCluster()
            return
        }

        val nowMs = System.currentTimeMillis()

        // Pass 1: bake each member's marker and measure its rendered footprint. MapLibre
        // draws an addImage bitmap at 1 bitmap-pixel : 1 screen-pixel (iconSize 1.0) — the
        // same units the fan radius is in — so the baked bitmap's width/height ARE the
        // marker's on-screen size, including the "at <Place>" line when present.
        var maxMarkerW = 0
        var maxMarkerH = 0
        val baked = members.map { loc ->
            val colorInt = viewModel.getMemberColor(loc.userId)
            val name = memberName(loc.userId)
            val initials = computeMemberInitials(name)
            val ageMs = nowMs - loc.timestamp
            val isStale = ageMs > 15 * 60_000L
            // "at <Place>" from the member's TRUE location (the fan marker is drawn
            // offset); never from an approximate member's snapped point.
            val atPlaceName = if (!spotOf(loc).showsPoint) null
                else placeContaining(loc.latitude, loc.longitude, viewModel.places.value)?.name
            val iconId = getOrCreateMemberIcon(style, colorInt, initials, isStale, formatRelativeAge(ageMs), atPlaceName)
            style.getImage(iconId)?.let {
                maxMarkerW = maxOf(maxMarkerW, it.width)
                maxMarkerH = maxOf(maxMarkerH, it.height)
            }
            loc to iconId
        }

        // Fan radius sized so no two marker bounding boxes can touch, at ANY count.
        // Adjacent fan points are 2*R*sin(π/n) apart; requiring that chord to clear
        // the marker diagonal (plus a small gap) gives disjoint boxes whatever the
        // angle between neighbours — covering both the n=2 vertical
        // "text-into-the-next-circle" case and angular crowding at higher counts.
        // Floored at 90px so small markers don't collapse into the centre.
        val n = members.size
        val gapPx = 8 * resources.displayMetrics.density
        val minChord = Math.hypot(maxMarkerW.toDouble(), maxMarkerH.toDouble()) + gapPx
        val fanRadiusPx = maxOf(90.0, minChord / (2.0 * Math.sin(Math.PI / n))).toFloat()

        // Pass 2: position each marker on the fan circle and draw its connector line.
        val fanFeatures = mutableListOf<String>()
        baked.forEachIndexed { i, pair ->
            val loc = pair.first
            val iconId = pair.second
            // Distribute evenly around the cluster; start from the top (-90°).
            val angle = 2.0 * Math.PI * i / n - Math.PI / 2.0
            val offsetPx = android.graphics.PointF(
                (centerPx.x + fanRadiusPx * Math.cos(angle)).toFloat(),
                (centerPx.y + fanRadiusPx * Math.sin(angle)).toFloat()
            )
            val fanLatLng = map.projection.fromScreenLocation(offsetPx)

            fanFeatures.add(
                """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":[[${center.longitude},${center.latitude}],[${fanLatLng.longitude},${fanLatLng.latitude}]]}}"""
            )
            fanFeatures.add(
                """{"type":"Feature","properties":{"iconId":"$iconId","userId":"${loc.userId}"},"geometry":{"type":"Point","coordinates":[${fanLatLng.longitude},${fanLatLng.latitude}]}}"""
            )
        }

        val geojson = """{"type":"FeatureCollection","features":[${fanFeatures.joinToString(",")}]}"""
        source.setGeoJson(geojson)
        fanActive = true
        setClusterLayerDimmed(true)
    }

    private fun clearFannedCluster() {
        val style = safeStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(FAN_SOURCE_ID) ?: return
        source.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        fanActive = false
        setClusterLayerDimmed(false)
    }

    private fun updateMemberMarkers(received: List<LocationEntity>) {
        val map = mapLibreMap ?: return
        val style = safeStyle ?: return
        // Where each member is drawn: an approximate member's point never as a precise one.
        val locations = received.map { displayed(it) }

        val nowMs = System.currentTimeMillis()

        // Pre-register cluster bitmaps for any count that could occur (2..N + 99+).
        // MapLibre renders cluster features with a synthetic `point_count` property;
        // the layer's icon-image expression concatenates that into a bitmap id.
        if (locations.size >= 2) {
            for (count in 2..locations.size.coerceAtMost(99)) {
                getOrCreateClusterIcon(style, count)
            }
            if (locations.size >= 100) {
                getOrCreateClusterIcon(style, 100) // registers the "99+" fallback id
            }
        }

        val places = viewModel.places.value
        // Members raising an SOS get "SOS" baked under their initials and are routed to
        // their own unclustered source below, so the emergency can't be collapsed into a
        // cluster badge.
        // A frozen marker takes no SOS: the alert's own disc holds its position.
        val sosIds = sosMemberIds().filterNot { viewModel.drawsFrozen(it) }.toSet()
        // An approximate member is an area round the point received, with their avatar at
        // its centre. That point is a grid snap, not where they are: no place is worked
        // out from it, and no accuracy ring or heading is drawn on it.
        val spots = locations.map { spotOf(it) }
        val features = locations.mapIndexed { i, loc ->
            val showsPoint = spots[i].showsPoint
            val colorInt = viewModel.getMemberColor(loc.userId)
            val name = memberName(loc.userId)
            val initials = computeMemberInitials(name)
            val ageMs = nowMs - loc.timestamp
            // Where they were when they turned real-time sharing off here: greyed and
            // labelled, never an age or "at <Place>", so it never reads as where they are
            // now.
            val frozen = viewModel.drawsFrozen(loc.userId)
            val isStale = frozen || ageMs > 15 * 60_000L
            val atPlaceName = if (!showsPoint || frozen) null else placeContaining(loc.latitude, loc.longitude, places)?.name
            val hasSos = loc.userId in sosIds
            val caption = if (frozen) getString(R.string.member_sharing_off) else formatRelativeAge(ageMs)
            val iconId = getOrCreateMemberIcon(
                style, colorInt, initials, isStale, caption, atPlaceName, hasSos
            )

            // No geographic offset — members render at their TRUE location, and
            // co-located ones are collapsed by the clustering source into a badge
            // the tap handler fans out. Nudging coordinates apart to "unstack" them
            // would put people where they are not.
            val point = Point.fromLngLat(loc.longitude, loc.latitude)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("userId", loc.userId)
            feature.addStringProperty("name", name)
            feature.addStringProperty("initials", initials)
            feature.addStringProperty("iconId", iconId)
            if (showsPoint && !frozen) {
                // heading: GPS course over ground, 0-360 degrees (null when stationary
                // or first fix). Drives the rotating chevron on the heading layer.
                loc.heading?.takeIf { it.isFinite() }?.let { feature.addNumberProperty("heading", it) }
                // accuracy: GPS horizontal accuracy in meters. Drives the translucent
                // ring underneath the marker. Skip obviously bogus values.
                loc.accuracy?.takeIf { it.isFinite() && it > 0 && it < 1000 }?.let { feature.addNumberProperty("accuracy", it) }
            }
            // Gson serialises large numeric properties in scientific notation
            // (1.776754445209E12 for an epoch-ms value), which MapLibre's GeoJSON
            // parser will not accept. The raw-string builder below formats every
            // numeric field explicitly for exactly that reason.
            feature
        }

        // This source is fed a raw GeoJSON STRING, not FeatureCollection.fromFeatures(...).
        // On this SDK/style combination the FeatureCollection path stops re-rendering
        // once setGeoJson([]) has cleared the source, so the markers never come back.
        fun featureJson(f: Feature): String {
            val p = f.geometry() as Point
            val lng = p.longitude()
            val lat = p.latitude()
            // Props are built by hand with known-safe escaping. JsonElement.toString()
            // produces something MapLibre's GeoJSON parser rejects SILENTLY — the whole
            // feature collection simply doesn't render, with no error anywhere.
            val iconId = f.getStringProperty("iconId") ?: ""
            val userId = f.getStringProperty("userId") ?: ""
            val heading = f.getNumberProperty("heading")?.toDouble()
            val accuracy = f.getNumberProperty("accuracy")?.toDouble()
            // heading/accuracy are emitted only when present, so the layers' has()
            // filters gate rendering correctly. Age is deliberately NOT a property:
            // it is baked into the icon bitmap, since a textField would need glyphs
            // (see getOrCreateMemberIcon).
            val parts = mutableListOf(
                """"iconId":"$iconId"""",
                """"userId":"$userId""""
            )
            if (heading != null) parts.add(""""heading":${String.format(java.util.Locale.US, "%.1f", heading)}""")
            if (accuracy != null) parts.add(""""accuracy":${String.format(java.util.Locale.US, "%.1f", accuracy)}""")
            val propsJson = parts.joinToString(",")
            return """{"type":"Feature","properties":{$propsJson},"geometry":{"type":"Point","coordinates":[$lng,$lat]}}"""
        }

        // Split by SOS. The clustered source keeps everyone else; a raiser goes
        // to the unclustered SOS-member source so their avatar (and the halo
        // pulsing out from under it) always renders, at any zoom, whoever else
        // is standing next to them.
        //
        // If that source somehow isn't on the style yet, everyone goes back in
        // the clustered one — an unmarked avatar is a far better failure than a
        // raiser held out of a source that isn't there to draw them.
        val sosSource = style.getSourceAs<GeoJsonSource>(MEMBERS_SOS_SOURCE_ID)
        val (sosFeatures, plainFeatures) = if (sosSource != null) {
            features.partition { it.getStringProperty("userId") in sosIds }
        } else {
            emptyList<Feature>() to features
        }
        val rawGeoJson =
            """{"type":"FeatureCollection","features":[${plainFeatures.joinToString(",") { featureJson(it) }}]}"""
        sosSource?.setGeoJson(
            """{"type":"FeatureCollection","features":[${sosFeatures.joinToString(",") { featureJson(it) }}]}"""
        )

        // The areas go first: on a fresh style their layers then sit below the member
        // layers created next, so each avatar is drawn on top of its own area.
        updateApproximateAreas(style, locations.mapIndexedNotNull { i, loc ->
            val spot = spots[i] as? MemberSpot.Approximate ?: return@mapIndexedNotNull null
            val stale = nowMs - loc.timestamp > 15 * 60_000L || viewModel.drawsFrozen(loc.userId)
            // The avatar's colour, or its grey once stale, so the two read as one.
            val color = if (stale) "#9E9E9E" else String.format(
                java.util.Locale.US, "#%06X", 0xFFFFFF and viewModel.getMemberColor(loc.userId)
            )
            ApproximateArea.featureJson(color, loc.latitude, loc.longitude, spot.radiusMeters)
        })

        // Update the members source IN PLACE. The source and its three layers are
        // built only on the first paint of a style (getSourceAs returns null), or
        // after a style reload leaves them missing — never per location tick, which
        // would rebuild four objects several times a minute.
        //
        // Note that the source must be re-fetched by id each time: calling setGeoJson
        // on a HELD GeoJsonSource reference does not propagate on this SDK, while a
        // freshly fetched one does.
        val existingSource = style.getSourceAs<GeoJsonSource>(MEMBERS_SOURCE_ID)
        if (existingSource != null) {
            existingSource.setGeoJson(rawGeoJson)
        } else {
            // Defensive: on a fresh style nothing exists so these removes are
            // no-ops; kept so a partially torn-down style still rebuilds cleanly.
            try { style.removeLayer(MEMBERS_ACCURACY_LAYER_ID) } catch (_: Exception) {}
            try { style.removeLayer(MEMBERS_LAYER_ID) } catch (_: Exception) {}
            try { style.removeLayer(MEMBERS_CLUSTER_LAYER_ID) } catch (_: Exception) {}
            try { style.removeSource(MEMBERS_SOURCE_ID) } catch (_: Exception) {}

            val membersOptions = GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(22)
                .withClusterMaxZoom(14)
            style.addSource(GeoJsonSource(MEMBERS_SOURCE_ID, rawGeoJson, membersOptions))

            // The member layers are created HERE, lazily, on the first update —
            // i.e. after setupMapLayers has already run. So "SOS is added last"
            // does not make it topmost: plain addLayer would stack members over
            // it, and an SOS raised at a member's own position (the common case,
            // since the raiser IS a member) would be hidden behind their avatar.
            // Anchor each member layer below the SOS pulse instead. Falls back to
            // a plain append if the SOS layer somehow isn't there.
            fun addBelowSos(layer: org.maplibre.android.style.layers.Layer) {
                if (style.getLayer(SOS_PULSE_LAYER_ID) != null) {
                    style.addLayerBelow(layer, SOS_PULSE_LAYER_ID)
                } else {
                    style.addLayer(layer)
                }
            }

            val accuracyLayer = CircleLayer(MEMBERS_ACCURACY_LAYER_ID, MEMBERS_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.exponential(2f),
                        Expression.zoom(),
                        Expression.stop(14, Expression.product(Expression.toNumber(Expression.get("accuracy")), Expression.literal(0.38f))),
                        Expression.stop(20, Expression.product(Expression.toNumber(Expression.get("accuracy")), Expression.literal(24f)))
                    )
                ),
                PropertyFactory.circleColor(Color.parseColor("#6d4aff")),
                PropertyFactory.circleOpacity(0.12f),
                PropertyFactory.circleStrokeColor(Color.parseColor("#6d4aff")),
                PropertyFactory.circleStrokeWidth(1f),
                PropertyFactory.circleStrokeOpacity(0.35f)
            )
            accuracyLayer.setFilter(
                Expression.all(
                    Expression.not(Expression.has("point_count")),
                    Expression.has("accuracy")
                )
            )
            addBelowSos(accuracyLayer)

            val clusterSymLayer = SymbolLayer(MEMBERS_CLUSTER_LAYER_ID, MEMBERS_SOURCE_ID)
                .withProperties(
                    PropertyFactory.iconImage(
                        Expression.step(
                            Expression.toNumber(Expression.get("point_count")),
                            Expression.concat(
                                Expression.literal("member-cluster-"),
                                Expression.toString(Expression.get("point_count"))
                            ),
                            Expression.stop(100, "member-cluster-99plus")
                        )
                    ),
                    dataDrivenIconOpacity(),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
                )
            clusterSymLayer.setFilter(Expression.has("point_count"))
            addBelowSos(clusterSymLayer)

            val membersSymLayer = SymbolLayer(MEMBERS_LAYER_ID, MEMBERS_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get("iconId")),
                dataDrivenIconOpacity(),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER)
            )
            membersSymLayer.setFilter(Expression.not(Expression.has("point_count")))
            addBelowSos(membersSymLayer)
        }

        android.util.Log.d("MapFragment", "Updated ${features.size} member markers on map")

        // The markers are pictures a screen reader cannot reach, so the map itself says who
        // is on it, and who is only approximately there.
        _binding?.mapView?.contentDescription = if (locations.isEmpty()) null else getString(
            R.string.map_members_a11y,
            locations.mapIndexed { i, loc ->
                val name = if (viewModel.isCurrentUser(loc.userId)) getString(R.string.map_member_you_a11y)
                    else memberName(loc.userId)
                if (spots[i] is MemberSpot.Approximate) getString(R.string.member_map_approximate_a11y, name) else name
            }.joinToString("; "),
        )

        // Free member-icon bitmaps no longer referenced by any visible marker. The
        // icon id embeds the rolling "Nm ago" age label, so each member churns a new
        // id ~once a minute; without this, style.addImage accumulates stale bitmaps
        // forever (native memory leak). Skipped while a cluster fan is open — its
        // markers reference icons that may not be in this pass's live set (the fan
        // was baked at a slightly different age); the next non-fan pass prunes them.
        if (!fanActive) {
            val liveIconIds = features.mapNotNull { it.getStringProperty("iconId") }.toSet()
            val staleIconIds = registeredMemberIconIds - liveIconIds
            for (id in staleIconIds) {
                try { style.removeImage(id) } catch (_: Exception) {}
            }
            registeredMemberIconIds.removeAll(staleIconIds)
        }

        // If a fan is currently active (user tapped the cluster), re-dim the badge.
        // On the create-once path the cluster layer is added at full size; on the
        // in-place setGeoJson path it keeps its dim state and this is a harmless
        // no-op — either way the badge stays dimmed behind the fanned markers.
        if (fanActive) setClusterLayerDimmed(true)

        // Which alerts still need their own disc depends on what was just drawn
        // here — the raiser moving away from the alert's recorded position, or
        // the Members layer going away entirely, both hand the emergency back to
        // the standalone marker. Re-deciding from the end of this pass keeps the
        // two in step no matter which path drove it. Cheap and skipped outright
        // in the overwhelmingly common case of no live alerts.
        val liveSosAlerts = mainViewModel.mappableSosAlerts.value
        if (liveSosAlerts.isNotEmpty()) updateSosMarkers(liveSosAlerts)

        // Last-resort framing: fit the whole circle only when nothing else has
        // claimed the camera. A saved (gestured) view means the user chose this
        // view and applyMapStyle already restored it; coldStartRecenterSelf means
        // the GPS snap is about to aim at the user themselves, so this only runs
        // if that couldn't get a fix. hasInitiallyZoomedToMembers then stops it
        // re-firing on every subsequent marker update.
        if (!hasInitiallyZoomedToMembers && locations.isNotEmpty() &&
            viewModel.savedCamera == null && !coldStartRecenterSelf) {
            hasInitiallyZoomedToMembers = true
            centerOnAllMembers(animate = false)
        }
    }

    /**
     * How the map may show [loc]: an area for a member the server reports as sharing an
     * approximate location in that circle; a point for everyone else and for this phone.
     */
    private fun spotOf(loc: LocationEntity): MemberSpot = MemberSpot.of(
        isSelf = viewModel.isCurrentUser(loc.userId),
        approximateRadiusMiles = approximateMembers.radiusMiles(loc.circleId, loc.userId),
    )

    /**
     * [loc] where the map draws it: an approximate member's point as is when a sender snapped
     * it, else snapped here once and held for that fix — an area is never centred on a precise
     * point (ApproximateMembersStore.displayPoint). Everyone else, and this phone, as is.
     */
    private fun displayed(loc: LocationEntity): LocationEntity {
        if (viewModel.isCurrentUser(loc.userId)) return loc
        val (lat, lng) = approximateMembers.displayPoint(
            loc.circleId, loc.userId, loc.deviceId, loc.timestamp, loc.latitude, loc.longitude,
        )
        return if (lat == loc.latitude && lng == loc.longitude) loc else loc.copy(latitude = lat, longitude = lng)
    }

    /**
     * Draw [features] (from [ApproximateArea.featureJson]) as translucent areas with a dashed
     * edge. Built on the first paint of a style, under whichever member layer exists, then
     * updated in place from a string like the members source, for the same reasons.
     */
    private fun updateApproximateAreas(style: Style, features: List<String>) {
        val geoJson = """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""
        val existing = style.getSourceAs<GeoJsonSource>(MEMBERS_AREA_SOURCE_ID)
        if (existing != null) {
            existing.setGeoJson(geoJson)
            return
        }
        style.addSource(GeoJsonSource(MEMBERS_AREA_SOURCE_ID, geoJson))
        val fill = FillLayer(MEMBERS_AREA_FILL_LAYER_ID, MEMBERS_AREA_SOURCE_ID).withProperties(
            PropertyFactory.fillColor(Expression.get("color")),
            PropertyFactory.fillOpacity(0.16f),
        )
        val edge = LineLayer(MEMBERS_AREA_LINE_LAYER_ID, MEMBERS_AREA_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.7f),
            // Dashed: an edge nobody is standing on, unlike a place's solid geofence ring.
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        )
        val below = listOf(MEMBERS_ACCURACY_LAYER_ID, MEMBERS_CLUSTER_LAYER_ID, MEMBERS_LAYER_ID, SOS_PULSE_LAYER_ID)
            .firstOrNull { style.getLayer(it) != null }
        if (below != null) {
            style.addLayerBelow(fill, below)
            style.addLayerBelow(edge, below)
        } else {
            style.addLayer(fill)
            style.addLayer(edge)
        }
    }

    /**
     * Apply the current visibility toggles to a place list. The only
     * filter that drops rows is the "Shared Places" toggle — received
     * shares (wrapViaCircle non-null) are removed when the chip is
     * unchecked. User-owned places always pass.
     */
    private fun filterVisiblePlaces(places: List<PlaceEntity>): List<PlaceEntity> {
        val showShared = viewModel.showSharedPlaces.value
        return if (showShared) places else places.filter { it.wrapViaCircle.isNullOrBlank() }
    }

    private fun updatePlaceMarkers(places: List<PlaceEntity>) {
        val style = safeStyle ?: return
        val currentCircleId = mainViewModel.selectedCircle.value?.id
        // A frozen marker covers no pin: it says no one is at the place now.
        val memberLocations = viewModel.memberLocations.value.filterNot { viewModel.drawsFrozen(it.userId) }

        val features = places.map { place ->
            val point = Point.fromLngLat(place.longitude, place.latitude)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("name", place.name)
            feature.addStringProperty("id", place.id)
            val isShared = currentCircleId != null && place.circleId != currentCircleId &&
                place.sharedCircles?.split(",")?.contains(currentCircleId) == true
            val color = place.color ?: "#6D4AFF"
            val emoji = PlaceIconUtils.getEmoji(place.icon)
            val iconId = getOrCreatePlaceIcon(style, color, emoji, isShared)
            feature.addStringProperty("icon", iconId)
            // Covered = a member is inside this place AND their avatar is sitting on
            // its pin right now. Hide the pin and the name label (opacity 0) so the
            // member's baked "at <Place>" carries the place instead of the pin being
            // a duplicate behind the avatar. Screen-space, so zooming in separates
            // them and brings the pin back.
            val covered = isPlaceCoveredByMember(place, memberLocations)
            feature.addNumberProperty("iconOpacity", if (covered) 0f else 1f)
            feature
        }

        val source = style.getSourceAs<GeoJsonSource>(PLACES_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(features))

        android.util.Log.d("MapFragment", "Updated ${places.size} place markers on map")
    }

    private fun clearPlaceMarkers() {
        val style = safeStyle ?: return

        val source = style.getSourceAs<GeoJsonSource>(PLACES_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /**
     * Is this place's pin actually COVERED by a member's avatar right now?
     *
     * Containment AND on-screen occlusion, and it must stay both:
     *
     *  - Containment alone hides the pin whenever anyone is anywhere inside the
     *    radius. That radius runs to 2 km, so a member a kilometre away — several
     *    screens off at street zoom — erases a pin they are nowhere near, and no
     *    amount of zooming brings it back, because the test never looks at the
     *    camera.
     *  - Occlusion alone would hide pins for members merely passing over them at
     *    low zoom, and those members' sheets carry no "At &lt;Place&gt;" row — so the
     *    pin would vanish with no route back to it. Requiring containment too
     *    makes hidden-pins a strict subset of has-a-row, which is what turns that
     *    row into a guarantee rather than a usually.
     *
     * Screen-space, so it re-decides as the camera moves; the camera-idle listener
     * in setupMap re-runs [updatePlaceMarkers] for that reason.
     */
    private fun isPlaceCoveredByMember(place: PlaceEntity, locations: List<LocationEntity>): Boolean {
        if (place.radius <= 0) return false
        val map = mapLibreMap ?: return false
        val density = resources.displayMetrics.density
        val thresholdPx = MemberMarkerGeometry.OCCLUSION_DP * density
        val placePoint = map.projection.toScreenLocation(LatLng(place.latitude, place.longitude))
        val center = LatLng(place.latitude, place.longitude)
        val radius = place.radius.toDouble()
        val sosIds = sosMemberIds()
        val places = viewModel.places.value

        return locations.any { loc ->
            // An approximate member's point is a grid snap: their sheet has no "At
            // <Place>" row, so they never hide a pin either — the row must stay a
            // superset.
            if (!spotOf(loc).showsPoint) return@any false
            // Containment first — it's cheap and it's the gate that keeps the
            // "At <Place>" row a superset of the pins we hide.
            if (center.distanceTo(LatLng(loc.latitude, loc.longitude)) > radius) return@any false
            val memberPoint = map.projection.toScreenLocation(LatLng(loc.latitude, loc.longitude))
            // Measure to the avatar's DISC, not its anchor: the label stack under it
            // pushes the disc above the member's coordinate (see
            // MemberMarkerGeometry.discRiseAboveAnchorPx). Comparing anchors would be
            // off by half a label stack — ~17dp with an "at <Place>" line, against a
            // 40dp threshold.
            val atPlace = placeContaining(loc.latitude, loc.longitude, places) != null
            val rise = MemberMarkerGeometry.discRiseAboveAnchorPx(density, atPlace, loc.userId in sosIds)
            val dx = memberPoint.x - placePoint.x
            val dy = (memberPoint.y - rise) - placePoint.y
            Math.hypot(dx.toDouble(), dy.toDouble()) <= thresholdPx
        }
    }

    /**
     * The member tap box: generous, so two close-but-separated markers are easy
     * to disambiguate and a tap on an icon never falls through to the own-GPS
     * address handler.
     */
    private fun memberTapRectFor(screenPoint: android.graphics.PointF): android.graphics.RectF =
        android.graphics.RectF(
            screenPoint.x - 32f, screenPoint.y - 32f,
            screenPoint.x + 32f, screenPoint.y + 32f,
        )

    /** Distance in screen px from [screenPoint] to a coordinate's projected point. */
    private fun screenDistanceTo(
        map: MapLibreMap,
        screenPoint: android.graphics.PointF,
        lat: Double,
        lng: Double,
    ): Float {
        val p = map.projection.toScreenLocation(LatLng(lat, lng))
        return Math.hypot((p.x - screenPoint.x).toDouble(), (p.y - screenPoint.y).toDouble()).toFloat()
    }

    /**
     * The member marker nearest the tap within [rect], with its screen distance.
     *
     * Nearest rather than first: with several markers in the box, "whichever the
     * renderer happened to return first" picks a neighbour over the one actually
     * aimed at. The distance comes back too so the caller can weigh this member
     * against a place pin under the same finger.
     */
    private fun nearestMemberAt(
        map: MapLibreMap,
        screenPoint: android.graphics.PointF,
        rect: android.graphics.RectF,
    ): Pair<LocationEntity, Float>? {
        val features = try {
            map.queryRenderedFeatures(rect, MEMBERS_LAYER_ID)
        } catch (_: Exception) {
            return null
        }
        if (features.isEmpty()) return null
        return features
            .mapNotNull { f ->
                val userId = f.getStringProperty("userId") ?: return@mapNotNull null
                val loc = viewModel.memberLocations.value.find { it.userId == userId }
                    ?.let { displayed(it) }
                    ?: return@mapNotNull null
                loc to screenDistanceTo(map, screenPoint, loc.latitude, loc.longitude)
            }
            .minByOrNull { it.second }
    }

    /** The saved place a point sits inside (within its geofence radius), nearest first, or null. */
    private fun placeContaining(lat: Double, lng: Double, places: List<PlaceEntity>): PlaceEntity? {
        val p = LatLng(lat, lng)
        return places.mapNotNull { place ->
            if (place.radius <= 0) return@mapNotNull null
            val d = LatLng(place.latitude, place.longitude).distanceTo(p)
            if (d <= place.radius.toDouble()) place to d else null
        }.minByOrNull { it.second }?.first
    }

    /**
     * Render destination markers: one icon per active destination for the current
     * circle, labelled with the creator's display name ("Alice's destination") so
     * it is obvious at a glance WHO is heading where.
     */
    private fun updateDestinationMarkers(destinations: List<com.pingolin.track.data.local.entity.DestinationEntity>) {
        val style = safeStyle ?: return
        val selfUserId = sessionManager.getUserId()

        val places = viewModel.places.value
        val features = destinations
            .filter { it.status == "active" }
            .map { dest ->
                val point = Point.fromLngLat(dest.destLongitude, dest.destLatitude)
                val feature = Feature.fromGeometry(point)
                val name = if (dest.isMine(selfUserId)) {
                    getString(R.string.map_your_destination)
                } else {
                    viewModel.memberDisplayName(dest.userId)?.let { getString(R.string.map_member_destination, it) }
                        ?: getString(R.string.map_member_destination_unnamed)
                }
                feature.addStringProperty("id", dest.id)
                feature.addStringProperty("label", dest.destName?.ifBlank { null } ?: name)
                val color = viewModel.getMemberColor(dest.userId)
                val colorHex = String.format("#%06X", 0xFFFFFF and color)
                feature.addStringProperty("icon", getOrCreateDestinationIcon(style, colorHex))
                // Live-pulse differentiation: a destination that lands inside a
                // saved Place pulses in that place's color (the place "breathes");
                // an ad-hoc location pulses in the creator's color. The two pulse
                // CircleLayers filter on this flag.
                val matchedPlace = placeContaining(dest.destLatitude, dest.destLongitude, places)
                feature.addBooleanProperty("is_place", matchedPlace != null)
                feature.addStringProperty("pulse_color", matchedPlace?.color ?: colorHex)
                feature
            }

        val source = style.getSourceAs<GeoJsonSource>(DESTINATIONS_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(features))

        // Run the "live" pulse only while there are active destinations on screen.
        if (features.isEmpty()) stopDestinationPulse() else startDestinationPulse()

        android.util.Log.d("MapFragment", "Updated ${features.size} destination markers on map")
    }

    private fun clearDestinationMarkers() {
        stopDestinationPulse()
        val style = safeStyle ?: return
        val source = style.getSourceAs<GeoJsonSource>(DESTINATIONS_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Infinite "breathing" animator that grows + fades the destination pulse
     *  halos. ONE animator drives BOTH pulse layers; each computes its own
     *  radius/opacity so a place destination and an ad-hoc one visibly differ.
     *  Started while active destinations are shown, paused off-screen (onStop),
     *  cancelled in onDestroyView. Idempotent. */
    private var destinationPulseAnimator: android.animation.ValueAnimator? = null

    private fun startDestinationPulse() {
        if (destinationPulseAnimator != null) return
        destinationPulseAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { va ->
                val style = safeStyle ?: return@addUpdateListener
                val f = va.animatedFraction
                // Place: bigger, softer halo — the saved place "breathes".
                (style.getLayer(DESTINATIONS_PULSE_PLACE_LAYER_ID) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius(14f + 26f * f),
                    PropertyFactory.circleOpacity(0.38f * (1f - f)),
                )
                // Ad-hoc location: tighter, crisper ping.
                (style.getLayer(DESTINATIONS_PULSE_ADHOC_LAYER_ID) as? CircleLayer)?.setProperties(
                    PropertyFactory.circleRadius(6f + 12f * f),
                    PropertyFactory.circleOpacity(0.60f * (1f - f)),
                )
            }
            start()
        }
    }

    private fun stopDestinationPulse() {
        destinationPulseAnimator?.cancel()
        destinationPulseAnimator = null
        val style = safeStyle ?: return
        (style.getLayer(DESTINATIONS_PULSE_PLACE_LAYER_ID) as? CircleLayer)?.setProperties(PropertyFactory.circleOpacity(0f))
        (style.getLayer(DESTINATIONS_PULSE_ADHOC_LAYER_ID) as? CircleLayer)?.setProperties(PropertyFactory.circleOpacity(0f))
    }

    /** Dot badge signalling that OTHER members have an active destination right
     *  now (yours you already know). The destinations FAB lives in the collapsed
     *  speed-dial, so the dot rides the always-visible ⋮ expand FAB. */
    private var destinationsBadge: com.google.android.material.badge.BadgeDrawable? = null

    private fun setupDestinationsBadge() {
        if (destinationsBadge != null || _binding == null) return
        val badge = com.google.android.material.badge.BadgeDrawable.create(requireContext())
        badge.isVisible = false
        badge.backgroundColor = com.google.android.material.color.MaterialColors.getColor(
            binding.fabExpand, androidx.appcompat.R.attr.colorError
        )
        com.google.android.material.badge.BadgeUtils.attachBadgeDrawable(badge, binding.fabExpand)
        destinationsBadge = badge
    }

    private fun updateDestinationsBadge(othersActive: Boolean) {
        destinationsBadge?.isVisible = othersActive
    }

    private fun detachDestinationsBadge() {
        val badge = destinationsBadge ?: return
        _binding?.let { com.google.android.material.badge.BadgeUtils.detachBadgeDrawable(badge, it.fabExpand) }
        destinationsBadge = null
    }

    /** Make (and cache) a per-color flag glyph for destinations. Colors
     *  derive from the creator's member-color slot so each person's
     *  destinations read visually "owned" by them. */
    private fun getOrCreateDestinationIcon(style: Style, colorHex: String): String {
        val iconId = "destination-icon-$colorHex"
        if (style.getImage(iconId) != null) return iconId
        val size = 88
        val bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bm)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.parseColor(colorHex)
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2f, p)
        p.style = android.graphics.Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = android.graphics.Color.WHITE
        c.drawCircle(size / 2f, size / 2f, size / 2f - 2f, p)
        p.style = android.graphics.Paint.Style.FILL
        p.color = android.graphics.Color.WHITE
        p.textSize = 44f
        p.textAlign = android.graphics.Paint.Align.CENTER
        val fm = p.fontMetrics
        val textY = size / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText("🚩", size / 2f, textY, p)
        style.addImage(iconId, bm)
        return iconId
    }

    /**
     * A [numPoints]-gon approximating a circle of [radiusMeters] on the Earth's
     * surface — a great-circle walk in every direction, so the polygon stays a
     * true circle on the ground rather than a lat/lng ellipse at high latitudes.
     */
    private fun generateCirclePolygonPoints(
        centerLng: Double,
        centerLat: Double,
        radiusMeters: Double,
        numPoints: Int = 64
    ): List<Point> {
        val earthRadius = 6371000.0 // meters
        val latRad = Math.toRadians(centerLat)
        val lngRad = Math.toRadians(centerLng)
        val angularDistance = radiusMeters / earthRadius

        val points = mutableListOf<Point>()
        for (i in 0..numPoints) {
            val bearing = 2.0 * Math.PI * i / numPoints
            val ptLat = Math.asin(
                Math.sin(latRad) * Math.cos(angularDistance) +
                Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearing)
            )
            val ptLng = lngRad + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latRad),
                Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(ptLat)
            )
            points.add(Point.fromLngLat(Math.toDegrees(ptLng), Math.toDegrees(ptLat)))
        }
        return points
    }

    /** Draw the translucent geofence ring around each place the viewer has an alert on. */
    private fun updateBoundaryCircles(places: List<PlaceEntity>) {
        val style = safeStyle ?: return

        val features = places.mapNotNull { place ->
            // Ring the geofence only for places THIS user has an alert on — alerts
            // are per user, per place. place.alertsEnabled must NOT be used here:
            // it is place-LEVEL (true if ANYONE has an alert), so a share
            // recipient would see a ring drawn from the owner's alert before
            // setting one of their own. viewerAlertPlaceIds comes from the local
            // place_alerts table, which holds only this user's subscriptions.
            //
            // radius_meters (place.radius) IS the geofence; boundaryRadius is a
            // legacy field that syncs back null, hence the fallback below.
            if (place.id !in viewerAlertPlaceIds) return@mapNotNull null
            val radius = place.boundaryRadius ?: place.radius
            if (radius <= 0) return@mapNotNull null

            val circlePoints = generateCirclePolygonPoints(
                place.longitude, place.latitude, radius.toDouble()
            )
            val polygon = Polygon.fromLngLats(listOf(circlePoints))
            val feature = Feature.fromGeometry(polygon)
            feature.addStringProperty("color", place.color ?: "#6D4AFF")
            feature.addStringProperty("placeId", place.id)
            feature.addStringProperty("name", place.name)
            feature
        }

        val source = style.getSourceAs<GeoJsonSource>(BOUNDARY_CIRCLES_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(features))

        android.util.Log.d("MapFragment", "Updated ${features.size} boundary circles on map")
    }

    private fun clearBoundaryCircles() {
        val style = safeStyle ?: return

        val source = style.getSourceAs<GeoJsonSource>(BOUNDARY_CIRCLES_SOURCE_ID)
        source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /** Draw each member's history as a trail line, a dot per fix, and direction arrows. */
    private suspend fun drawLocationTrails(history: Map<String, List<LocationEntity>>) {
        val style = safeStyle
        if (style == null) {
            android.util.Log.w("MapFragment", "drawLocationTrails: mapStyle is null or stale, skipping")
            return
        }

        android.util.Log.d("MapFragment", "drawLocationTrails: Starting with ${history.size} users")

        val trailFeatures = mutableListOf<Feature>()
        val pointFeatures = mutableListOf<Feature>()
        val arrowFeatures = mutableListOf<Feature>()
        val boundsBuilder = LatLngBounds.Builder()
        var hasPoints = false

        // Build all Features off the Main thread — for a multi-day trail this is
        // up to ~10k point/segment/arrow Features plus the bounds math, all pure
        // data construction (no map-SDK calls). Only the setGeoJson + camera
        // animation below touch the map and must stay on Main.
        withContext(Dispatchers.Default) {
        history.forEach { (userId, locations) ->
            if (locations.isEmpty()) return@forEach

            val color = viewModel.getMemberColor(userId)
            val colorHex = String.format("#%06X", 0xFFFFFF and color)
            val memberName = memberName(userId)

            locations.forEach { loc ->
                boundsBuilder.include(LatLng(loc.latitude, loc.longitude))
                hasPoints = true

                val point = Point.fromLngLat(loc.longitude, loc.latitude)
                val pointFeature = Feature.fromGeometry(point)
                pointFeature.addStringProperty("color", colorHex)
                pointFeature.addStringProperty("userId", userId)
                pointFeature.addStringProperty("memberName", memberName)
                pointFeature.addNumberProperty("timestamp", loc.timestamp)
                pointFeatures.add(pointFeature)
            }

            if (locations.size >= 2) {
                val points = locations.map { loc ->
                    Point.fromLngLat(loc.longitude, loc.latitude)
                }

                val lineString = LineString.fromLngLats(points)
                val lineFeature = Feature.fromGeometry(lineString)
                lineFeature.addStringProperty("color", colorHex)
                lineFeature.addStringProperty("userId", userId)
                trailFeatures.add(lineFeature)

                // One direction arrow at the midpoint of every segment.
                for (i in 0 until locations.size - 1) {
                    val from = locations[i]
                    val to = locations[i + 1]

                    val midLat = (from.latitude + to.latitude) / 2
                    val midLng = (from.longitude + to.longitude) / 2

                    val bearing = calculateBearing(from.latitude, from.longitude, to.latitude, to.longitude)

                    val arrowPoint = Point.fromLngLat(midLng, midLat)
                    val arrowFeature = Feature.fromGeometry(arrowPoint)
                    arrowFeature.addStringProperty("color", colorHex)
                    arrowFeature.addStringProperty("userId", userId)
                    arrowFeature.addNumberProperty("bearing", bearing)
                    arrowFeatures.add(arrowFeature)
                }
            }
        }
        } // end withContext(Dispatchers.Default)

        val trailsSource = style.getSourceAs<GeoJsonSource>(TRAILS_SOURCE_ID)
        trailsSource?.setGeoJson(FeatureCollection.fromFeatures(trailFeatures))

        val pointsSource = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID)
        pointsSource?.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))

        val arrowsSource = style.getSourceAs<GeoJsonSource>(ARROWS_SOURCE_ID)
        arrowsSource?.setGeoJson(FeatureCollection.fromFeatures(arrowFeatures))

        android.util.Log.d("MapFragment", "Drew ${trailFeatures.size} trails, ${pointFeatures.size} points, ${arrowFeatures.size} arrows for ${history.size} users")

        // Same framing rule as centerOnAllMembers: for a tight trail (<600 m
        // diagonal — someone who stayed home for the window, or 1h mode on a
        // stationary member) fitBounds computes zoom 17-20 and lands "inside the
        // house", so fall back to a fixed zoom 14 on the midpoint. The 250px
        // padding keeps the outermost points off the screen edges, where they
        // would clip against the FABs and chips.
        if (hasPoints) {
            try {
                val allPoints = pointFeatures.mapNotNull { f ->
                    (f.geometry() as? Point)?.let { LatLng(it.latitude(), it.longitude()) }
                }
                val diagonalMeters = boundsDiagonalMeters(allPoints)
                if (diagonalMeters < 600.0 && allPoints.isNotEmpty()) {
                    val midLat = allPoints.map { it.latitude }.average()
                    val midLng = allPoints.map { it.longitude }.average()
                    mapLibreMap?.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(LatLng(midLat, midLng))
                                .zoom(14.0)
                                .build()
                        ),
                        1000
                    )
                } else {
                    mapLibreMap?.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 250),
                        1000
                    )
                }
            } catch (e: Exception) {
                // Bounds might be invalid if all points are the same
            }
        }
    }

    /**
     * The history point nearest the tap, hit-tested in SCREEN space.
     *
     * The tolerance must stay in pixels, never metres. A geographic radius is
     * zoom-independent, so at the zoom you actually review a day's trail at, a
     * 10 m radius is a fraction of a pixel and the only way to land a tap is to
     * hit the exact GPS coordinate — history points become untappable. Every
     * other marker here hit-tests in screen space too (member markers use a
     * ±32 px rect): a finger-sized target, identical at every zoom.
     *
     * Nearest-wins, so on a dense trail the tap resolves to the closest point
     * rather than whichever the iteration happened to reach first.
     */
    private fun findNearestHistoricalLocation(
        tapScreenPoint: android.graphics.PointF,
        tolerancePx: Float
    ): com.pingolin.track.data.local.entity.LocationEntity? {
        val history = viewModel.locationHistory.value
        if (history.isEmpty()) return null
        val map = mapLibreMap ?: return null
        val projection = map.projection

        var nearestLocation: com.pingolin.track.data.local.entity.LocationEntity? = null
        var nearestDistancePx = Float.MAX_VALUE

        history.values.flatten().forEach { location ->
            val px = projection.toScreenLocation(LatLng(location.latitude, location.longitude))
            // Points behind the camera / off the projection can come back NaN;
            // those aren't on screen to be tapped.
            if (px.x.isNaN() || px.y.isNaN()) return@forEach
            val dx = px.x - tapScreenPoint.x
            val dy = px.y - tapScreenPoint.y
            val distance = kotlin.math.sqrt(dx * dx + dy * dy)
            if (distance <= tolerancePx && distance < nearestDistancePx) {
                nearestDistancePx = distance
                nearestLocation = location
            }
        }

        return nearestLocation
    }

    /** Bearing from one point to another, in degrees clockwise from north (0-360). */
    private fun calculateBearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLng = Math.toRadians(lng2 - lng1)

        val x = Math.sin(dLng) * Math.cos(lat2Rad)
        val y = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLng)

        var bearing = Math.toDegrees(Math.atan2(x, y))
        bearing = (bearing + 360) % 360
        return bearing
    }

    private fun clearTrails() {
        val style = safeStyle ?: return

        val trailsSource = style.getSourceAs<GeoJsonSource>(TRAILS_SOURCE_ID)
        trailsSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))

        val pointsSource = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE_ID)
        pointsSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))

        val arrowsSource = style.getSourceAs<GeoJsonSource>(ARROWS_SOURCE_ID)
        arrowsSource?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    // ==================== Quick Action Dialogs ====================

    /**
     * Quick check-in types, posted to the circle the map's selector is on. On Just Me it is on none, and
     * the person picks one first, from a list that names each: never the first circle by default, which
     * would tell a circle nobody chose. Send never does that on Just Me either (it goes to this account's
     * own history).
     */
    private fun showCheckInPopup(anchor: android.view.View) {
        val circles = mainViewModel.circles.value
        if (circles.isEmpty()) {
            showStatus(getString(R.string.map_join_circle_first))
            return
        }

        val selectedCircle = mainViewModel.selectedCircle.value
        if (selectedCircle == null) {
            chooseCircleForCheckIn(anchor, circles)
            return
        }
        showCheckInMenuFor(anchor, selectedCircle.id)
    }

    /**
     * Just Me: the circles by name, even only one; the menu then opens for the one picked, and none is the
     * default. Only those whose names can be read (checkInChoices): with none, the map says why, and
     * nothing is sent. A check-in button that does nothing is no answer in a safety app.
     */
    private fun chooseCircleForCheckIn(anchor: android.view.View, circles: List<CircleEntity>) {
        val choices = checkInChoices(circles)
        if (choices.isEmpty()) {
            showStatus(getString(R.string.check_in_waiting_for_circles))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.check_in_pick_circle)
            .setItems(choices.map { it.name }.toTypedArray()) { _, which -> showCheckInMenuFor(anchor, choices[which].id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCheckInMenuFor(anchor: android.view.View, circleId: String) {
        // The check-in screen sends a position; it's offered only while a check-in to this
        // circle could carry one. Its posture is read first, so the menu never promises one.
        viewLifecycleOwner.lifecycleScope.launch {
            val withPosition = runCatching { checkInRepository.sendsPositionNow(circleId) }.getOrDefault(false)
            if (!isAdded) return@launch
            showCheckInMenu(anchor, circleId, withPosition)
        }
    }

    private fun showCheckInMenu(anchor: android.view.View, circleId: String, withPosition: Boolean) {
        val popup = android.widget.PopupMenu(requireContext(), anchor, android.view.Gravity.START)
        // The same choices, and types, as the check-in screen (CheckInChoices): "Checking In" is
        // a plain check-in, never the all-clear.
        val choices = com.pingolin.track.ui.checkin.CheckInChoices.QUICK
        choices.forEachIndexed { i, choice -> popup.menu.add(0, i + 1, i, choice.label) }
        val customId = choices.size + 1
        popup.menu.add(0, customId, choices.size, getString(R.string.map_custom_message))
        // The check-in screen: its buttons send your location with the check-in, and it lists the
        // circle's recent ones. Reachable from here as well as from someone else's check-in in the
        // bell.
        val screenId = choices.size + 2
        if (withPosition) popup.menu.add(0, screenId, choices.size + 1, getString(R.string.check_in_screen_entry))

        popup.setOnMenuItemClickListener { item ->
            val choice = choices.getOrNull(item.itemId - 1)
            when {
                choice != null -> { sendQuickCheckIn(circleId, choice.type); true }
                item.itemId == customId -> { showCustomCheckInMessage(circleId); true }
                // Opened on this circle, the one the menu was for, never the first one.
                item.itemId == screenId -> {
                    findNavController().navigate(
                        R.id.checkInFragment,
                        androidx.core.os.bundleOf(com.pingolin.track.ui.checkin.CheckInViewModel.ARG_CIRCLE_ID to circleId),
                    )
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showCustomCheckInMessage(circleId: String) {
        val inputView = layoutInflater.inflate(R.layout.dialog_check_in_message, null)
        val messageInput = inputView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etMessage)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.map_check_in_message_title))
            .setView(inputView)
            .setPositiveButton(getString(R.string.map_send)) { _, _ ->
                val message = messageInput?.text?.toString()?.trim() ?: ""
                if (message.isNotBlank()) {
                    sendQuickCheckInWithMessage(circleId, com.pingolin.track.ui.checkin.CheckInChoices.MESSAGE_TYPE, message)
                } else {
                    sendQuickCheckIn(circleId, com.pingolin.track.ui.checkin.CheckInChoices.MESSAGE_TYPE)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun sendQuickCheckIn(circleId: String, type: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            showStatus(getString(R.string.map_sending_check_in))
            val result = checkInRepository.createQuickCheckIn(circleId, type, keepIfBehind = true)
            result.fold(
                onSuccess = {
                    showStatus(getString(R.string.map_check_in_sent))
                },
                onFailure = { e ->
                    showStatus(e.plainText(getString(R.string.map_check_in_failed)))
                }
            )
        }
    }

    private fun sendQuickCheckInWithMessage(circleId: String, type: String, message: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            showStatus(getString(R.string.map_sending_check_in))
            val result = checkInRepository.createCheckIn(
                circleId = circleId,
                type = type,
                message = message,
                latitude = null,
                longitude = null,
                keepIfBehind = true,
            )
            result.fold(
                onSuccess = {
                    showStatus(getString(R.string.map_check_in_sent))
                },
                onFailure = { e ->
                    showStatus(e.plainText(getString(R.string.map_check_in_failed)))
                }
            )
        }
    }

    /**
     * Create and share a destination with a circle, pre-filled from map context.
     * With no coordinates the dialog can still be opened, but Share refuses —
     * there is deliberately no way to type a lat/lng in by hand.
     */
    private fun showShareDestinationDialog(
        latitude: Double?,
        longitude: Double?,
        addressHint: String?
    ) {
        val circles = mainViewModel.circles.value
        if (circles.isEmpty()) {
            showStatus(getString(R.string.map_join_circle_first))
            return
        }

        // Destinations are freemium: free users get 1 active destination at a
        // time, premium is unlimited. Gate every map entry point (tapped location,
        // my-location, place) here so a free user who already has one active is
        // offered a graceful cancel-and-replace rather than being walked through
        // the flow only to 402 at Share. No purchase CTA — Play policy forbids
        // directing to an external purchase flow from the app.
        if (sessionManager.effectivePremium.value) {
            openShareDestinationDialog(latitude, longitude, addressHint)
            return
        }
        // Not premium per the cached flag — but it can be stale-false (fresh login
        // before the startup refresh lands, or subscribed elsewhere just now).
        // Refresh once and re-check so a genuinely-premium user is never shown the
        // free limit; then decide from the user's own active-destination count.
        viewLifecycleOwner.lifecycleScope.launch {
            try { premiumStatusRefresher.refresh() } catch (_: Exception) {}
            if (sessionManager.effectivePremium.value) {
                openShareDestinationDialog(latitude, longitude, addressHint)
                return@launch
            }
            val active = destinationRepository.activeMine()
            if (active.isEmpty()) {
                // Their one free destination — proceed; the server enforces the cap.
                openShareDestinationDialog(latitude, longitude, addressHint)
            } else {
                showDestinationLimitReplaceDialog(active.first()) {
                    openShareDestinationDialog(latitude, longitude, addressHint)
                }
            }
        }
    }

    /**
     * Build and show the share-destination dialog (map-context flow: pre-filled
     * coords + reverse-geocoded address line). Reached only after the freemium
     * gate in [showShareDestinationDialog] has cleared.
     */
    private fun openShareDestinationDialog(
        latitude: Double?,
        longitude: Double?,
        addressHint: String?
    ) {
        val circles = mainViewModel.circles.value
        if (circles.isEmpty()) {
            showStatus(getString(R.string.map_join_circle_first))
            return
        }

        val dialogBinding = DialogCreateDestinationBinding.inflate(layoutInflater)

        val circleNames = circles.map { it.name }.toTypedArray()
        val circleAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, circleNames)
        dialogBinding.spinnerCircle.adapter = circleAdapter

        val selectedCircle = mainViewModel.selectedCircle.value
        if (selectedCircle != null) {
            val idx = circles.indexOfFirst { it.id == selectedCircle.id }
            if (idx >= 0) {
                dialogBinding.spinnerCircle.setSelection(idx)
            }
        }

        // The location is already known here, so the dialog's address-search section
        // is hidden and the resolved location shown instead. The name stays editable;
        // the coordinates are not, by design.
        //
        // Hide the WHOLE TextInputLayout, not just the inner EditText: the
        // magnifying-glass startIcon, the outlined box and the hint all belong to the
        // TextInputLayout, so hiding etSearch alone leaves a stranded magnifying glass.
        dialogBinding.tilSearch.visibility = android.view.View.GONE
        dialogBinding.tvSearchLoading.visibility = android.view.View.GONE
        dialogBinding.tvSearchEmpty.visibility = android.view.View.GONE
        dialogBinding.rvSearchResults.visibility = android.view.View.GONE

        if (latitude != null && longitude != null) {
            dialogBinding.layoutSelectedAddress.visibility = android.view.View.VISIBLE
            val line = if (!addressHint.isNullOrBlank()) {
                addressHint
            } else {
                coordinates(latitude, longitude)
            }
            dialogBinding.tvSelectedAddress.text = line
        }

        if (!addressHint.isNullOrBlank()) {
            dialogBinding.etDestName.setText(addressHint)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.map_tapped_share_destination))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.share)) { _, _ ->
                val selectedIndex = dialogBinding.spinnerCircle.selectedItemPosition
                if (selectedIndex < 0 || selectedIndex >= circles.size) {
                    showStatus(getString(R.string.map_please_select_circle))
                    return@setPositiveButton
                }

                val circle = circles[selectedIndex]
                val destName = dialogBinding.etDestName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val lat = latitude
                val lng = longitude
                if (lat == null || lng == null) {
                    showStatus(getString(R.string.map_destination_tap_first))
                    return@setPositiveButton
                }

                val notifyArrival = dialogBinding.checkboxNotifyArrival.isChecked

                viewLifecycleOwner.lifecycleScope.launch {
                    showStatus(getString(R.string.map_sharing_destination))
                    val result = destinationRepository.createDestination(
                        circleId = circle.id,
                        destName = destName,
                        destLatitude = lat,
                        destLongitude = lng,
                        notifyArrival = notifyArrival,
                        keepIfBehind = true,
                    )
                    result.fold(
                        onSuccess = {
                            // Confirm in place and STAY on the map: the new destination's
                            // marker appears here anyway via the destinations flow, so
                            // there is nothing to navigate to. Name the circle, because
                            // this is a broadcast and the user should see who received it.
                            showStatus(
                                sendNowReadableName(circle.name)?.let { getString(R.string.map_destination_shared, it) }
                                    ?: getString(R.string.map_destination_shared_unnamed)
                            )
                        },
                        onFailure = { e ->
                            // Destinations are freemium: this can be a 402 carrying
                            // quota/error_code. Show the premium prompt rather than a
                            // terse status line, the same as the place-create path above.
                            val quota = e.quotaError
                            val code = e.premiumErrorCode
                            if (quota != null || code != null) {
                                com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.showForQuota(
                                    parentFragmentManager, quota, code,
                                )
                            } else {
                                showStatus(e.plainText(getString(R.string.map_destination_share_failed)))
                            }
                        }
                    )
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Free tier includes 1 active destination at a time. When a free user who
     * already has one active tries to share another, offer to cancel the current
     * trip and continue (cancel-and-replace) — not a hard wall. The Premium
     * mention is informational only (no purchase CTA), per Google Play's
     * external-purchase policy. [onReplaced] runs after the current destination
     * is cancelled on the server.
     */
    private fun showDestinationLimitReplaceDialog(
        active: com.pingolin.track.data.local.entity.DestinationEntity,
        onReplaced: () -> Unit,
    ) {
        val name = active.destName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.destination_limit_title))
            .setMessage(name?.let { getString(R.string.destination_limit_body, it) } ?: getString(R.string.destination_limit_body_unnamed))
            .setPositiveButton(getString(R.string.destination_limit_replace)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    showStatus(name?.let { getString(R.string.map_cancelling_trip, it) } ?: getString(R.string.map_cancelling_trip_unnamed))
                    destinationRepository.cancelDestination(active.id).fold(
                        onSuccess = { onReplaced() },
                        onFailure = { e -> showStatus(e.plainText(getString(R.string.map_cancel_trip_failed))) },
                    )
                }
            }
            .setNegativeButton(getString(R.string.destination_limit_keep), null)
            .show()
    }

    // ==================== Bottom Sheets ====================

    private fun showMemberBottomSheet(location: LocationEntity) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_member, null)
        dialog.setContentView(view)

        val name = memberName(location.userId)
        val color = viewModel.getMemberColor(location.userId)
        // A member sharing an approximate location: "Approximate location" and nothing finer
        // — no address, no coordinates, no place worked out from the snapped point, and
        // nothing that hands the point on (Share as Destination, Copy Address).
        val showsPoint = spotOf(location).showsPoint
        // Where they were when they turned real-time sharing off here: its time, its address and
        // why, but no "At <Place>" and nothing to share as where they are now.
        val frozen = viewModel.drawsFrozen(location.userId)
        val address = if (!showsPoint) null
            else viewModel.getMemberAddress(location.userId, location.latitude, location.longitude)

        val indicator = view.findViewById<View>(R.id.viewMemberColor)
        val bg = indicator.background
        if (bg is GradientDrawable) {
            bg.setColor(color)
        }

        view.findViewById<TextView>(R.id.tvMemberName).text = name

        view.findViewById<TextView>(R.id.tvMemberLastSeen).text = getString(R.string.map_last_seen, formatTimestamp(location.timestamp))

        // Whether they are sharing. Your own pin says what you set and what the others
        // see; another member's says why their location isn't updating — the circle sees
        // the same words in its member list.
        val tvStatus = view.findViewById<TextView>(R.id.tvMemberStatus)
        tvStatus.visibility = View.GONE
        val btnViewHistory = view.findViewById<MaterialButton>(R.id.btnMemberViewHistory)
        val circleId = viewModel.selectedCircleId.value
        if (circleId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val status = viewModel.sharingStatus(circleId, location.userId)
                val now = System.currentTimeMillis()
                val text = if (location.userId == sessionManager.getUserId()) {
                    com.pingolin.track.ui.circles.MemberSharing.ownSentence(requireContext(), status, now)
                } else {
                    com.pingolin.track.ui.circles.MemberSharing.detailSentence(requireContext(), name, status, now)
                }
                // Someone keeping their location history from the circle: their card says so,
                // in place of a history there is none of to see.
                val historyLine = if (viewModel.historyHidden(circleId, location.userId)) {
                    com.pingolin.track.ui.circles.MemberSharing.historyOffSentence(requireContext(), name)
                } else null
                if (historyLine != null) btnViewHistory.visibility = View.GONE
                val lines = listOfNotNull(text, historyLine)
                if (lines.isNotEmpty()) {
                    tvStatus.text = lines.joinToString("\n")
                    tvStatus.visibility = View.VISIBLE
                }
            }
        }

        // "At <Place>", and a way INTO that place. A member standing on a place can
        // be covering its pin — updatePlaceMarkers hides it in exactly that case —
        // so this row is the guaranteed route to the place's own sheet. It is driven
        // by containment, NOT by whether a pin was actually hidden: containment is a
        // superset, so every hidden pin has a row here, and that is what makes the
        // route a guarantee rather than a usually.
        val atPlace = if (!showsPoint || frozen) null
            else placeContaining(location.latitude, location.longitude, viewModel.places.value)
        val btnAtPlace = view.findViewById<MaterialButton>(R.id.btnMemberAtPlace)
        if (atPlace != null) {
            btnAtPlace.visibility = View.VISIBLE
            btnAtPlace.text = getString(R.string.member_at_place, atPlace.name)
            btnAtPlace.setOnClickListener {
                dialog.dismiss()
                showPlaceBottomSheet(atPlace)
            }
        } else {
            btnAtPlace.visibility = View.GONE
        }

        // Show any cached address immediately, otherwise a placeholder while the
        // reverse geocode runs. The resolved value goes into a holder so the action
        // handlers below (Share, Copy) read the freshest copy rather than the null
        // they closed over.
        val tvAddress = view.findViewById<TextView>(R.id.tvMemberAddress)
        val resolvedAddress = arrayOf<String?>(address)
        if (!showsPoint) {
            tvAddress.text = getString(R.string.member_approximate_location)
            tvAddress.visibility = View.VISIBLE
        } else if (address != null) {
            tvAddress.text = address
            tvAddress.visibility = View.VISIBLE
        } else {
            tvAddress.text = getString(R.string.map_looking_up_address)
            tvAddress.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val fetched = viewModel.resolveMemberAddress(
                    location.userId,
                    location.latitude,
                    location.longitude,
                )
                if (fetched != null) {
                    resolvedAddress[0] = fetched
                    tvAddress.text = fetched
                } else {
                    tvAddress.visibility = View.GONE
                }
            }
        }

        val tvCoords = view.findViewById<TextView>(R.id.tvMemberCoords)
        if (!showsPoint) {
            tvCoords.visibility = View.GONE
        } else {
            tvCoords.text = coordinates(location.latitude, location.longitude)
        }

        btnViewHistory.setOnClickListener {
            viewModel.selectMember(location.userId)
            showHistoryPanel()
            dialog.dismiss()
        }

        val btnShareDest = view.findViewById<MaterialButton>(R.id.btnMemberShareDest)
        val btnCopyAddress = view.findViewById<MaterialButton>(R.id.btnMemberCopyCoords)
        if (!showsPoint) {
            btnShareDest.visibility = View.GONE
            btnCopyAddress.visibility = View.GONE
        } else {
            if (frozen) btnShareDest.visibility = View.GONE
            btnShareDest.setOnClickListener {
                dialog.dismiss()
                showShareDestinationDialog(location.latitude, location.longitude, resolvedAddress[0])
            }
            btnCopyAddress.setOnClickListener {
                copyAddressToClipboard(resolvedAddress[0])
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * Where a zoom request points the camera: its point at its zoom, or, for an area (an SOS known only
     * to within a radius), all of the area, as an approximate member's.
     */
    private fun cameraFor(req: com.pingolin.track.ui.main.MainViewModel.MapZoomRequest): org.maplibre.android.camera.CameraUpdate {
        val radius = req.radiusMeters ?: return CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder().target(LatLng(req.lat, req.lng)).zoom(req.zoom).build()
        )
        val box = ApproximateArea.box(req.lat, req.lng, radius)
        return CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().include(LatLng(box.south, box.west)).include(LatLng(box.north, box.east)).build(),
            120,
        )
    }

    /**
     * Callout for an SOS marker: who, when, where, and a private Dismiss.
     *
     * Everything shown here is resolved on this device. The server holds the
     * alert's timestamp and location encrypted and cannot supply either, so the
     * time comes from the decrypted entity and the address from an on-device
     * reverse geocode of the decrypted coordinates.
     */
    private fun showSosBottomSheet(alert: com.pingolin.track.data.local.entity.SosAlertEntity) {
        val lat = alert.latitude ?: return
        val lng = alert.longitude ?: return

        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_sos, null)
        dialog.setContentView(view)

        val isMine = com.pingolin.track.util.AccountIds.same(alert.userId, sessionManager.getUserId())
        view.findViewById<TextView>(R.id.tvSosTitle).text = if (isMine) {
            getString(R.string.sos_from_you)
        } else {
            viewModel.memberDisplayName(alert.userId)?.let { getString(R.string.sos_from_member, it) }
                ?: getString(R.string.sos_from_member_unnamed)
        }

        // Absolute clock time AND elapsed time. "14:32" alone is useless when
        // you've just woken up to the notification; "8 minutes ago" alone is
        // useless when you're relaying it to someone else.
        val clock = android.text.format.DateFormat.getTimeFormat(requireContext())
            .format(java.util.Date(alert.createdAt))
        view.findViewById<TextView>(R.id.tvSosTime).text =
            getString(R.string.sos_triggered_at, clock, formatTimestamp(alert.createdAt))

        // An area has no address or coordinates: its centre is nobody's street.
        val area = com.pingolin.track.domain.sos.SosPosition.areaMeters(alert) != null
        view.findViewById<TextView>(R.id.tvSosCoords).apply {
            text = coordinates(lat, lng)
            visibility = if (area) View.GONE else View.VISIBLE
        }

        // Where the position stands: last known, updated, an area's size; else the usual words for
        // one taken as it was sent. The raiser's own words for mine.
        val now = System.currentTimeMillis()
        view.findViewById<TextView>(R.id.tvSosSnapshotNote).text = listOfNotNull(
            com.pingolin.track.ui.sos.SosPositionText.status(
                requireContext(), com.pingolin.track.domain.sos.SosPosition.of(alert, now), isMine,
            // "Sent from here." is a pin's; an area says only how far.
            ) ?: getString(R.string.sos_location_snapshot).takeUnless { area },
            com.pingolin.track.ui.sos.SosPositionText.within(requireContext(), alert, sessionManager.useMiles()),
        ).joinToString("\n")

        val tvAcknowledged = view.findViewById<TextView>(R.id.tvSosAcknowledged)
        if (alert.acknowledgmentCount > 0) {
            tvAcknowledged.text = resources.getQuantityString(
                R.plurals.sos_members_acknowledged,
                alert.acknowledgmentCount,
                alert.acknowledgmentCount,
            )
            tvAcknowledged.visibility = View.VISIBLE
        }
        // My own request: who is responding, by name, where the server told me who.
        if (isMine) {
            viewLifecycleOwner.lifecycleScope.launch {
                myAlertResponders.copyFor(alert)?.let {
                    tvAcknowledged.text = it
                    tvAcknowledged.visibility = View.VISIBLE
                }
            }
        }

        val tvAddress = view.findViewById<TextView>(R.id.tvSosAddress)
        tvAddress.text = getString(R.string.sos_looking_up_address)
        tvAddress.visibility = if (area) View.GONE else View.VISIBLE
        if (!area) viewLifecycleOwner.lifecycleScope.launch {
            val resolved = viewModel.resolveSosAddress(alert.id, lat, lng)
            if (resolved != null) {
                tvAddress.text = resolved
            } else {
                // No geocoding data downloaded, or the lookup failed. The
                // coordinates are still on screen, so drop the line rather than
                // leave "Looking up address…" sitting there forever.
                tvAddress.visibility = View.GONE
            }
        }

        view.findViewById<MaterialButton>(R.id.btnSosDismiss).setOnClickListener {
            mainViewModel.dismissSos(alert.id)
            dialog.dismiss()
        }

        // The raiser's phone may replace the position while this is open: the sheet opens again on the new
        // one, or goes when the alert leaves the map.
        val follow = viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.mappableSosAlerts.collect { alerts ->
                val now = alerts.firstOrNull { it.id == alert.id }
                if (now == null) {
                    dialog.dismiss()
                } else if (now.latitude != alert.latitude || now.longitude != alert.longitude || now.fixAt != alert.fixAt ||
                    now.accuracy != alert.accuracy || now.positionState != alert.positionState
                ) {
                    dialog.dismiss()
                    showSosBottomSheet(now)
                }
            }
        }
        dialog.setOnDismissListener { follow.cancel() }

        dialog.show()
    }

    private fun showPlaceBottomSheet(place: PlaceEntity) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_place, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.tvPlaceName).text = place.name

        val tvAddress = view.findViewById<TextView>(R.id.tvPlaceAddress)
        if (place.address != null) {
            tvAddress.text = place.address
            tvAddress.visibility = View.VISIBLE
        }

        // Share attribution, shown only for a received share (wrap_via_circle is set).
        // Both ids resolve against local caches, so either lookup can miss — a circle
        // just removed, a member not yet synced — hence the placeholders.
        val tvShared = view.findViewById<TextView>(R.id.tvPlaceShared)
        if (!place.wrapViaCircle.isNullOrBlank()) {
            // Readable names only: a locked circle's list label or a blank never goes into the line.
            val circleName = sendNowReadableName(
                mainViewModel.circles.value.firstOrNull { com.pingolin.track.util.AccountIds.same(it.id, place.wrapViaCircle) }?.name
            )
            val ownerName = place.ownerUserId?.let { id ->
                viewModel.members.value.firstOrNull { com.pingolin.track.util.AccountIds.same(it.userId, id) }?.displayName
            }?.takeIf { it.isNotBlank() }
            tvShared.text = when {
                circleName != null && ownerName != null -> getString(R.string.map_shared_with_by, circleName, ownerName)
                circleName != null -> getString(R.string.map_shared_with_by_another, circleName)
                ownerName != null -> getString(R.string.map_shared_with_a_circle_by, ownerName)
                else -> getString(R.string.map_shared_with_a_circle_by_another)
            }
            tvShared.visibility = View.VISIBLE
        } else {
            tvShared.visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.tvPlaceCoords).text =
            coordinates(place.latitude, place.longitude)

        val tvNotes = view.findViewById<TextView>(R.id.tvPlaceNotes)
        if (!place.notes.isNullOrBlank()) {
            tvNotes.text = place.notes
            tvNotes.visibility = View.VISIBLE
        }

        val tvIcon = view.findViewById<TextView>(R.id.tvPlaceIcon)
        tvIcon.text = PlaceIconUtils.getEmoji(place.icon)

        // Alerts stay visible for received shares TOO. Edit and Delete are hidden for
        // a share, so without this a member who tapped a place shared with them would
        // have no way to set an alert at all. The editor already handles recipients:
        // it hides the alert-AREA tuner for non-owners, since radius_meters is one
        // per-place value only the owner may change.
        //
        // Uses the SAME editor as the Places list — a map-local copy of this dialog
        // is how the two screens drift apart.
        view.findViewById<MaterialButton>(R.id.btnPlaceAlert).setOnClickListener {
            dialog.dismiss()
            showPlaceAlertEditor(place, placesViewModel, sessionManager.useMiles())
        }

        // Edit and Delete are both hidden for a received share: the viewer doesn't
        // own the underlying row, so either action would fail or stomp the owner's data.
        val btnEdit = view.findViewById<MaterialButton>(R.id.btnPlaceEdit)
        val isReceivedShare = !place.wrapViaCircle.isNullOrBlank()
        if (isReceivedShare) {
            btnEdit.visibility = View.GONE
        } else {
            btnEdit.setOnClickListener {
                findNavController().navigate(R.id.placesFragment)
                dialog.dismiss()
            }
        }

        view.findViewById<MaterialButton>(R.id.btnPlaceShareDest).setOnClickListener {
            dialog.dismiss()
            showShareDestinationDialog(place.latitude, place.longitude, place.address ?: place.name)
        }

        view.findViewById<MaterialButton>(R.id.btnPlaceCopyCoords).setOnClickListener {
            copyAddressToClipboard(place.address ?: place.name)
            dialog.dismiss()
        }

        val btnDelete = view.findViewById<MaterialButton>(R.id.btnPlaceDelete)
        if (isReceivedShare) {
            btnDelete.visibility = View.GONE
        } else {
            btnDelete.setOnClickListener {
                dialog.dismiss()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.places_delete))
                    .setMessage(
                        place.name.takeIf { it.isNotBlank() }?.let { getString(R.string.map_delete_place_confirm, it) }
                            ?: getString(R.string.map_delete_place_confirm_unnamed)
                    )
                    .setPositiveButton(getString(R.string.delete)) { _, _ ->
                        viewModel.deletePlace(place.id)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .showDestructive()
            }
        }

        dialog.show()
    }

    private fun getPlaceIconResource(icon: String?): Int {
        // All place types use the generic place icon
        return R.drawable.ic_place
    }

    // ==================== Map Controls ====================

    /**
     * Frame all member locations. [animate] smoothly flies (the "center on all"
     * button); when false it jumps instantly (the on-open auto-fit — avoids a
     * disorienting 1s zoom out of the style's world-view default on every tab
     * return). Either way it records the framed camera as [MapViewModel.savedCamera]
     * so a subsequent tab return restores this view directly instead of re-fitting.
     */
    private fun centerOnAllMembers(animate: Boolean = true) {
        val map = mapLibreMap ?: return
        val locations = viewModel.memberLocations.value.map { displayed(it) }
        if (locations.isEmpty()) {
            if (animate) showStatus(getString(R.string.map_no_member_locations))
            return
        }

        val points = locations.map { LatLng(it.latitude, it.longitude) }.toMutableList()
        if (locationComponentActivated) {
            map.locationComponent.lastKnownLocation?.let { points.add(LatLng(it.latitude, it.longitude)) }
        }
        // An approximate member is their whole area, not its centre: fit all of it.
        val areas = locations.mapNotNull { loc ->
            (spotOf(loc) as? MemberSpot.Approximate)?.let { ApproximateArea.box(loc.latitude, loc.longitude, it.radiusMeters) }
        }
        areas.forEach { box ->
            points.add(LatLng(box.south, box.west))
            points.add(LatLng(box.north, box.east))
        }

        // 14 = close-neighborhood. When everyone is within ~600 m, fitBounds would
        // compute an "interior of the house" zoom (17-20), so fall back to a fixed
        // 14 on the midpoint — matching the single-location path.
        val (update, framed) = when {
            locations.size == 1 && areas.isEmpty() -> {
                val cp = CameraPosition.Builder()
                    .target(LatLng(locations[0].latitude, locations[0].longitude)).zoom(14.0).build()
                CameraUpdateFactory.newCameraPosition(cp) to cp
            }
            boundsDiagonalMeters(points) < 600.0 -> {
                val cp = CameraPosition.Builder()
                    .target(LatLng(points.map { it.latitude }.average(), points.map { it.longitude }.average()))
                    .zoom(14.0).build()
                CameraUpdateFactory.newCameraPosition(cp) to cp
            }
            else -> {
                val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
                // 250px padding ≈ one 52dp marker plus breathing room.
                val cp = try { map.getCameraForLatLngBounds(bounds, intArrayOf(250, 250, 250, 250)) } catch (_: Exception) { null }
                CameraUpdateFactory.newLatLngBounds(bounds, 250) to cp
            }
        }
        framed?.let { f ->
            f.target?.let { t ->
                val sc = MapViewModel.SavedCamera(t.latitude, t.longitude, f.zoom, f.bearing, f.tilt)
                viewModel.savedCamera = sc
            }
        }
        try {
            if (animate) map.animateCamera(update, 1000) else map.moveCamera(update)
        } catch (_: Exception) {}
    }

    /**
     * Approximate diagonal of the bounding box of a set of points, in meters.
     * Used to decide whether fitBounds would produce an absurdly close zoom.
     */
    private fun boundsDiagonalMeters(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        val lats = points.map { it.latitude }
        val lngs = points.map { it.longitude }
        val sw = LatLng(lats.min(), lngs.min())
        val ne = LatLng(lats.max(), lngs.max())
        return sw.distanceTo(ne)
    }

    /**
     * Copy the address to the clipboard.
     *
     * There is deliberately NO coordinate fallback. The button says "Copy
     * Address", and a lat/lng pair silently taking its place is both a lie and
     * the wrong currency for this product — an address is what a person can
     * paste into a message, a taxi app, or a search box. If the reverse
     * geocode has not landed yet (or returned nothing), say so and copy
     * nothing rather than handing over numbers nobody asked for.
     */
    private fun copyAddressToClipboard(address: String?) {
        val text = address?.takeIf { it.isNotBlank() } ?: run {
            showStatus(getString(R.string.map_address_unavailable))
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.map_address_clip_label), text))
        showStatus(getString(R.string.map_address_copied))

        // SECURITY: Auto-clear clipboard after 60 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val currentClip = clipboard.primaryClip
                if (currentClip != null && currentClip.itemCount > 0) {
                    val currentText = currentClip.getItemAt(0).text?.toString()
                    if (currentText == text) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
            } catch (_: Exception) {}
        }, 60_000L)
    }

    /** Snackbar, anchored above the bottom navigation bar so it doesn't cover it. */
    private fun showStatus(message: String) {
        // Reached from deferred result collectors; no-op once the view is gone.
        val b = _binding ?: return
        val bottomNav = activity?.findViewById<View>(R.id.bottom_nav)
        Snackbar.make(b.mapView, message, Snackbar.LENGTH_SHORT).apply {
            if (bottomNav != null) anchorView = bottomNav
        }.show()
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - timestampMs
        val diffSec = diffMs / 1000
        val diffMin = diffSec / 60
        val diffHour = diffMin / 60

        return when {
            diffSec < 60 -> getString(R.string.map_just_now)
            diffMin < 60 -> getString(R.string.map_minutes_ago, diffMin.toInt())
            diffHour < 24 -> getString(R.string.map_hours_ago, diffHour.toInt())
            else -> localDateTime(timestampMs)
        }
    }

    /** A member's name for a line on the map: their name, or "Unknown" in this phone's language. */
    private fun memberName(userId: String): String =
        viewModel.memberDisplayName(userId) ?: getString(R.string.member_unknown)

    /** Coordinates as the map shows them: the app's one form, whatever the phone's language (util/Coordinates.kt). */
    private fun coordinates(latitude: Double, longitude: Double): String = com.pingolin.track.util.coordinatesText(latitude, longitude)

    /** A date format from a skeleton ("MMMd", "jm"), in this phone's language and order. */
    private fun localFormat(skeleton: String): java.text.SimpleDateFormat {
        val locale = androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.getDefault()
        return java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** [ms] as this phone shows a time: its language, and its 12- or 24-hour setting. */
    private fun localTime(ms: Long, withSeconds: Boolean = false): String {
        val hours = if (android.text.format.DateFormat.is24HourFormat(requireContext())) "Hm" else "hm"
        return localFormat(hours + if (withSeconds) "s" else "").format(java.util.Date(ms))
    }

    /** [ms] as a short date and a time, as this phone shows them ("Apr 22, 3:15 PM", "22. Apr., 15:15"). */
    private fun localDateTime(ms: Long): String {
        val hours = if (android.text.format.DateFormat.is24HourFormat(requireContext())) "Hm" else "hm"
        return localFormat("MMMd$hours").format(java.util.Date(ms))
    }

    /**
     * A display name as the marker's 1-2 char initials: "Alice Smith" → "AS",
     * "Bob" → "B", "" → "?".
     */
    private fun computeMemberInitials(name: String): String {
        if (name.isBlank()) return "?"
        val parts = name.trim().split(Regex("\\s+"))
        val letters = parts.mapNotNull { it.firstOrNull()?.uppercaseChar() }
        return when (letters.size) {
            0 -> "?"
            1 -> letters[0].toString()
            else -> "${letters[0]}${letters[1]}"
        }
    }

    /**
     * Compact age string for the member-marker age label (tight on screen real estate).
     * "now" / "5m" / "2h" / "3d" — no trailing " ago" because space is scarce below the pin.
     */
    private fun formatRelativeAge(diffMs: Long): String {
        val diffSec = diffMs / 1000
        val diffMin = diffSec / 60
        val diffHour = diffMin / 60
        val diffDay = diffHour / 24
        return when {
            diffSec < 60 -> getString(R.string.map_age_now)
            diffMin < 60 -> getString(R.string.map_age_minutes, diffMin.toInt())
            diffHour < 24 -> getString(R.string.map_age_hours, diffHour.toInt())
            diffDay < 365 -> getString(R.string.map_age_days, diffDay.toInt())
            else -> getString(R.string.map_age_years, (diffDay / 365).toInt())
        }
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        viewModel.onMapVisibilityChanged(true)
    }

    override fun onResume() {
        super.onResume()
        // Re-read on resume: location changes in Android's settings, and after Android's own question.
        bindLocationOff()
        binding.mapView.onResume()
        destinationPulseAnimator?.resume()
        sosPulseAnimator?.resume()

        // The VM is activity-scoped (survives tab switches), so its poll loop is
        // gated on the view lifecycle instead of VM clearing: resume it while the
        // map is visible, stop it in onStop. startPolling() is idempotent.
        if (viewModel.selectedCircleId.value != null) viewModel.startPolling()

        // The service may have been started or stopped while this fragment was away.
        mainViewModel.syncTrackingState()

        // Explicit, because the StateFlow observer won't re-emit an unchanged value
        // and the animation needs restarting after the view was recreated.
        updateTrackingFabIcon(mainViewModel.isTracking.value)

        maybeRefreshOwnLocationOnResume()

        val map = mapLibreMap ?: return

        // The theme (and so the style) can have changed in Settings while away.
        val expectedStyleUrl = getExpectedStyleUrl()
        if (currentStyleUrl != null && currentStyleUrl != expectedStyleUrl) {
            loadMapStyle(map)
            return
        }

        // Permission can be granted after the style has already loaded, so the
        // location component has to be retried here rather than only on style load.
        val style = safeStyle ?: return

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Gate on locationComponentActivated, NOT isLocationComponentEnabled:
            // the latter stays false on purpose even after activation (no blue GPS
            // dot), so testing it would re-run enableLocationComponent every resume.
            if (!locationComponentActivated) {
                enableLocationComponent(style)
            }
        }
    }

    /**
     * Maximum age of the own marker's last upload before onResume nudges a fresh
     * one — otherwise "moved five minutes ago, opened the app, marker is where I
     * was" persists until the foreground service's next 15-minute tick.
     *
     * 3 minutes is the compromise: shorter (60s) thrashes GPS on every quick
     * foreground, longer (15m) leaves the marker obviously wrong. The nudge is
     * silent and rate-limited by sendLocationNow's own 15s window.
     */
    private val ownMarkerStaleThresholdMs: Long = 3L * 60_000L

    @SuppressLint("MissingPermission")
    private fun maybeRefreshOwnLocationOnResume() {
        // Silent best-effort: skip if we don't have permission, no userId,
        // or the marker is already fresh enough. None of these states are
        // an error worth surfacing.
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        // An older app version's device-wide Pause Tracking, until each circle
        // is paused instead (PauseTrackingMigration): nothing leaves the phone
        // meanwhile.
        if (sessionManager.isTrackingPaused()) return

        val myUserId = sessionManager.getUserId() ?: return
        val ownLoc = viewModel.memberLocations.value.find { it.userId == myUserId }
        val ageMs = ownLoc?.timestamp?.let { System.currentTimeMillis() - it }
        if (ageMs != null && ageMs < ownMarkerStaleThresholdMs) return
        // The circle whose marker was found stale, taken before the fix as a tap's is.
        val target = sendTargetNow()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val location = locationProvider.currentLocation(
                    com.pingolin.track.location.LocationAccuracy.HIGH
                )
                if (location != null) {
                    viewModel.sendLocationNow(location, target, silent = true)
                }
            } catch (_: Exception) {
                // The foreground service's scheduled cadence is the safety net, so
                // there is nothing here worth interrupting the user about.
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
        destinationPulseAnimator?.pause()
        // The SOS halo is the same mechanism as the destination pulse and must
        // be paused too: left running, it redraws the full map every frame with
        // nothing on screen.
        sosPulseAnimator?.pause()
        // VM is activity-scoped, so it won't be cleared when this fragment goes
        // away on a tab switch — stop the poll loop here so it doesn't keep
        // hitting the network while the map isn't on screen. Marking the map
        // hidden also stops a background re-selection from restarting it.
        viewModel.onMapVisibilityChanged(false)
        // (Camera is saved continuously on user-gesture idle — see getMapAsync.
        // Not saved here, since onStop would capture whatever programmatic state
        // the camera happens to be in.)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop running AnimatedVectorDrawable to prevent native animation reference
        // from retaining the view hierarchy (CoordinatorLayout leak via FAB)
        (binding.fabTracking.drawable as? android.graphics.drawable.AnimatedVectorDrawable)?.stop()
        destinationPulseAnimator?.cancel()
        destinationPulseAnimator = null
        // Its update listener reaches for resources and the style: it must not
        // outlive the view that owns them.
        sosPulseAnimator?.cancel()
        sosPulseAnimator = null
        detachDestinationsBadge()
        binding.mapView.onDestroy()
        // Drop the tapped-location sheet so its window doesn't leak past the view; null
        // the dismiss listener first so this teardown-dismiss doesn't poke the ViewModel.
        tappedLocationSheet?.setOnDismissListener(null)
        tappedLocationSheet?.dismiss()
        tappedLocationSheet = null
        topLeftStack = null
        binding.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(fitMenuLabelsOnLayout)
        // The bell's label is the activity's: left open with the menu, it would stay on every other screen,
        // over Settings among them, and on the map again with the menu shut.
        activity?.findViewById<View>(R.id.labelNotifications)?.visibility = View.GONE
        _binding = null
    }
}

/**
 * The circles the Just Me check-in chooser lists: those whose names can be read (sendNowReadableName, the
 * same rule as for a send's target). A locked circle's "Locked circle" isn't one the person could know
 * they picked.
 */
internal fun checkInChoices(circles: List<CircleEntity>): List<CircleEntity> =
    circles.filter { sendNowReadableName(it.name) != null }

/**
 * The map's line while Track has no usable location, or null when it has. "Your circles can't see
 * where you are" only for someone in a circle: the map of someone in none says "Just me".
 */
@androidx.annotation.StringRes
internal fun locationOffLine(access: com.pingolin.track.util.LocationAccess, hasCircles: Boolean): Int? = when (access) {
    com.pingolin.track.util.LocationAccess.ON -> null
    com.pingolin.track.util.LocationAccess.NOT_ALLOWED ->
        if (hasCircles) R.string.map_location_not_allowed else R.string.map_location_not_allowed_no_circles
    com.pingolin.track.util.LocationAccess.APPROXIMATE ->
        if (hasCircles) R.string.map_location_approximate else R.string.map_location_approximate_no_circles
    com.pingolin.track.util.LocationAccess.SERVICES_OFF ->
        if (hasCircles) R.string.map_location_services_off else R.string.map_location_services_off_no_circles
}

/**
 * The location line's top margin: its own [slot], or [gapPx] below the lowest of the standing
 * lines [above] it (each a top and a height, in px) when one of them reaches further, as a wrapped
 * sync-error line does at a large font on a small screen.
 */
internal fun locationOffTop(slot: Int, above: List<Pair<Int, Int>>, gapPx: Int): Int =
    maxOf(slot, above.maxOfOrNull { (top, height) -> top + height + gapPx } ?: 0)
