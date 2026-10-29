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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.domain.repository.smartSearch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.databinding.FragmentPlacesBinding
import com.pingolin.track.databinding.DialogSharePlaceBinding
import com.pingolin.track.databinding.DialogCreatePlaceBinding
import com.pingolin.track.databinding.DialogCreatePlaceAlertBinding
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager as ShareLinearLayoutManager
import com.pingolin.track.ui.start.UnreadableEmptyState
import com.pingolin.track.util.plainTextOrNull
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlacesFragment : Fragment() {

    private var _binding: FragmentPlacesBinding? = null
    private val binding get() = _binding!!

    /** For the inline Place Visibility section. PlacesFragment drives
     *  both create and edit dialogs through [viewModel], which returns
     *  the new place via PlacesUiState.PlaceCreated on its own flow;
     *  we stash the pending queue here so the state observer can drain
     *  it when that event arrives. */
    @javax.inject.Inject
    lateinit var placeRepository: com.pingolin.track.domain.repository.PlaceRepository

    /** Used by the address-search field on the create-place dialog —
     *  combines the local TileGeocoder forward search with the global
     *  CityIndex (privacy-preserving, lazy ~7 MB tier-1 download). The
     *  query never leaves the device. */
    @javax.inject.Inject
    lateinit var geocodingRepository: com.pingolin.track.domain.repository.GeocodingRepository

    @javax.inject.Inject
    lateinit var sessionManager: com.pingolin.track.util.SessionManager

    /** Fused on gms, android.location.LocationManager on libre. Used only to
     *  seed the create-place dialog with wherever the user currently is. */
    @javax.inject.Inject
    lateinit var locationProvider: com.pingolin.track.location.LocationProvider

    private var pendingCreateShares: com.pingolin.track.ui.places.PendingShares? = null

    /** Latest debounced search job — cancelled when the user keeps
     *  typing so we don't ship stale results into the recycler. */
    private var addressSearchJob: kotlinx.coroutines.Job? = null

    private val viewModel: PlacesViewModel by viewModels()
    private val mainViewModel: com.pingolin.track.ui.main.MainViewModel by activityViewModels()

    private lateinit var placesAdapter: PlacesAdapter
    private lateinit var pendingAdapter: com.pingolin.track.ui.pending.PendingChangesAdapter
    private var shownPlaces = 0
    private var shownPending = 0
    private lateinit var itemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlacesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        placesAdapter = PlacesAdapter(
            onPlaceClick = { place ->
                viewModel.selectPlace(place)
                // Jump to the map view and fly to the tapped place.
                // MapFragment picks up MainViewModel.mapZoomRequests and
                // flyTo's its coords; we don't do the camera work here so
                // the map's current zoom/bearing stays under MapFragment's
                // authority.
                mainViewModel.requestMapZoom(place.latitude, place.longitude)
                findNavController().navigate(R.id.mapFragment)
            },
            onEditClick = { place -> showEditPlaceDialog(place) },
            onAlertClick = { place -> showPlaceAlertEditor(place, viewModel, sessionManager.useMiles()) },
            onShareClick = { place -> showSharePlaceDialog(place) },
            onDeleteClick = { place -> showDeleteConfirmation(place) },
            onOrderChanged = { places -> viewModel.updatePlaceOrder(places) },
            hasAlertsEnabled = { placeId -> viewModel.hasAlertsEnabled(placeId) },
            allAlertsSuppressed = { placeId -> viewModel.allAlertsSuppressed(placeId) }
        )

        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0 // No swipe
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
                    return false
                }
                // Places move among places only, never over the changes waiting to sync.
                if (viewHolder.bindingAdapter !== placesAdapter || target.bindingAdapter !== placesAdapter) return false
                placesAdapter.moveItem(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used - swipe disabled
            }

            override fun isLongPressDragEnabled(): Boolean {
                // We handle long press manually in the adapter
                return false
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    placesAdapter.onDragStart()
                    viewHolder?.itemView?.alpha = 0.7f
                    viewHolder?.itemView?.scaleX = 1.02f
                    viewHolder?.itemView?.scaleY = 1.02f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                placesAdapter.onDragComplete()
            }
        }

        itemTouchHelper = ItemTouchHelper(touchCallback)
        placesAdapter.setItemTouchHelper(itemTouchHelper)

        // Place changes waiting to sync go first.
        pendingAdapter = com.pingolin.track.ui.pending.PendingChangesAdapter { viewModel.pending.cancel(it) }
        binding.rvPlaces.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = androidx.recyclerview.widget.ConcatAdapter(pendingAdapter, placesAdapter)
        }

        itemTouchHelper.attachToRecyclerView(binding.rvPlaces)
    }

    private fun setupUI() {
        binding.fabCreatePlace.setOnClickListener {
            showCreatePlaceDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshPlaces()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun showCreatePlaceDialog() {
        val dialogBinding = DialogCreatePlaceBinding.inflate(layoutInflater)

        var selectedIcon = "home"
        var selectedColor = "#2196F3"
        // Where the place is: nowhere until a fix arrives or an address is picked — never 0, 0,
        // which would put a place saved before either in the sea off West Africa.
        var currentLat: Double? = null
        var currentLng: Double? = null

        // Asynchronous: the dialog is already on screen and simply fills its
        // coordinates in when a fix arrives, rather than blocking on one.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val location = locationProvider.lastLocation()
                if (location != null) {
                    currentLat = location.latitude
                    currentLng = location.longitude
                    // Reverse-geocode the GPS fix in the background to fill
                    // the scope chip with something useful (the address
                    // string the local geocoder returns for the fix)
                    // rather than "Set search area". Failure is silent —
                    // the chip's placeholder is still actionable.
                    runCatching {
                        geocodingRepository.reverseGeocode(location.latitude, location.longitude)
                    }.getOrNull()?.text?.takeIf { it.isNotBlank() }?.let { line ->
                        // Trim to a manageable length for the chip.
                        // Reverse-geocoder text can include full
                        // street + admin lineage; we keep just the
                        // first ~28 chars so it renders cleanly.
                        dialogBinding.chipSearchScope.text =
                            if (line.length > 28) line.take(28).trimEnd(',', ' ') + "…"
                            else line
                    }
                }
            } catch (_: Exception) { /* location unavailable */ }
        }

        // ---- Address search ------------------------------------------------
        // Debounced FTS over the local tile cache + the global city index.
        // Picking a result overwrites currentLat/currentLng and surfaces
        // the address in the existing layoutAddress block. The query
        // string never leaves the device — see GeocodingRepository.
        val searchAdapter = AddressSearchAdapter(
            onCachedClick = { hit ->
                currentLat = hit.lat
                currentLng = hit.lng
                showSelectedAddress(dialogBinding, hit.fullAddress())
                // Picking "Pink's Hot Dogs" should leave the user with the
                // place already named — they came here to save that POI, not
                // to retype its name. Only when they haven't typed one
                // themselves; their own text always wins.
                hit.suggestedLabel()?.let { suggested ->
                    if (dialogBinding.etPlaceName.text.isNullOrBlank()) {
                        dialogBinding.etPlaceName.setText(suggested)
                    }
                }
                // A POI often has no street of its own — venue records in the
                // geocoder carry none, and a tile can only derive one when the
                // right cell is cached. Resolve it from the coordinates and
                // upgrade the line in place if that turns up something fuller.
                enrichPickedStreet(dialogBinding, hit)
            },
            onCityClick = { city ->
                currentLat = city.lat
                currentLng = city.lng
                val label = listOfNotNull(
                    city.name,
                    city.admin1.takeIf { it.isNotBlank() },
                    city.countryCode.takeIf { it.isNotBlank() }
                ).joinToString(", ")
                showSelectedAddress(dialogBinding, label)
                // Kick off the city's tile download so a follow-up
                // reverse-geocode can refine the address from "Munich, DE"
                // to a specific street. Best-effort, no UI blocking.
                if (city.tileId.isNotBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        runCatching {
                            geocodingRepository.reverseGeocode(city.lat, city.lng)
                        }
                    }
                }
            }
        )
        dialogBinding.rvSearchResults.layoutManager = LinearLayoutManager(requireContext())
        dialogBinding.rvSearchResults.adapter = searchAdapter

        dialogBinding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim()
                addressSearchJob?.cancel()
                if (q.isEmpty()) {
                    searchAdapter.clear()
                    dialogBinding.rvSearchResults.visibility = View.GONE
                    dialogBinding.tvSearchEmpty.visibility = View.GONE
                    dialogBinding.tvSearchLoading.visibility = View.GONE
                    return
                }
                addressSearchJob = viewLifecycleOwner.lifecycleScope.launch {
                    // 250 ms debounce — fast enough to feel live but
                    // sparse enough to avoid spawning an FTS query on
                    // every keystroke for fast typers.
                    kotlinx.coroutines.delay(250)
                    dialogBinding.tvSearchLoading.text = getString(R.string.places_searching)
                    dialogBinding.tvSearchLoading.visibility = View.VISIBLE

                    // One call into the smart-fallback helper: it does the direct +
                    // city search, then the comma/whitespace candidate peel, shard
                    // lookup and re-search. Everything below only translates its
                    // return shape into an adapter submit plus empty-state copy.
                    val result = geocodingRepository.smartSearch(
                        query = q,
                        limit = 6,
                        onProgress = { scope ->
                            if (scope != null) {
                                val adminCountry = listOf(scope.admin1, scope.countryCode)
                                    .filter { it.isNotBlank() }
                                    .joinToString(", ")
                                dialogBinding.tvSearchLoading.text = getString(
                                    R.string.places_search_loading,
                                    scope.name + if (adminCountry.isNotEmpty()) ", $adminCountry" else "",
                                )
                                dialogBinding.tvSearchLoading.visibility = View.VISIBLE
                            } else {
                                dialogBinding.tvSearchLoading.visibility = View.GONE
                            }
                        },
                    )
                    dialogBinding.tvSearchLoading.visibility = View.GONE

                    if (result.addresses.isNotEmpty() || result.cities.isNotEmpty()) {
                        val scopeLabel = result.scopedTo?.let { scope ->
                            getString(R.string.places_search_in, scope.name + if (scope.admin1.isNotBlank()) ", ${scope.admin1}" else "")
                        }
                        searchAdapter.submit(
                            result.addresses,
                            result.cities,
                            cachedSectionTitle = scopeLabel,
                        )
                        dialogBinding.rvSearchResults.visibility = View.VISIBLE
                        dialogBinding.tvSearchEmpty.visibility = View.GONE
                    } else {
                        searchAdapter.clear()
                        dialogBinding.rvSearchResults.visibility = View.GONE
                        dialogBinding.tvSearchEmpty.text = result.scopedTo?.let { scope ->
                            val tail = if (scope.admin1.isNotBlank()) ", ${scope.admin1}" else ""
                            getString(R.string.places_search_no_match_in, scope.name + tail)
                        } ?: getString(R.string.places_search_no_match)
                        dialogBinding.tvSearchEmpty.visibility = View.VISIBLE
                    }
                }
            }
        })

        // City scope picker — tap the chip to open a dedicated bottom-
        // sheet for picking a city/region by name. The user gets a
        // bigger search canvas plus a "Use my current location" row
        // when GPS has a fix. Picking a result writes back to the
        // chip, sets currentLat/currentLng, and surfaces the chosen
        // address in the existing layoutAddress block.
        dialogBinding.chipSearchScope.setOnClickListener {
            // Null until a fix or a pick: 0, 0 is never a stand-in for "none".
            val gpsLat = currentLat
            val gpsLng = currentLng
            val gpsLabel = dialogBinding.chipSearchScope.text?.toString()
                ?.takeIf { it.isNotBlank() && it != getString(android.R.string.untitled) && it != getString(R.string.places_set_search_area) }
            val sheet = CityScopePickerSheet.newInstance(gpsLat, gpsLng, gpsLabel)
            sheet.setOnCityPickedListener { name, lat, lng ->
                currentLat = lat
                currentLng = lng
                // Update the chip with the chosen scope and surface
                // the same address in the form's layoutAddress block —
                // matches what picking from the inline search does.
                dialogBinding.chipSearchScope.text =
                    if (name.length > 28) name.take(28).trimEnd(',', ' ') + "…" else name
                showSelectedAddress(dialogBinding, name)
            }
            sheet.show(childFragmentManager, "scope-picker")
        }
        // ---- /Address search -----------------------------------------------

        // Icon buttons
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
            iconButtons.keys.forEach { it.isSelected = (it == selectedButton) }
        }
        fun updateColorSelection(selectedButton: android.widget.ImageButton) {
            colorButtons.keys.forEach { it.isSelected = (it == selectedButton) }
        }

        iconButtons.forEach { (button, iconName) ->
            button.setOnClickListener { selectedIcon = iconName; updateIconSelection(button) }
        }
        colorButtons.forEach { (button, colorHex) ->
            button.setOnClickListener { selectedColor = colorHex; updateColorSelection(button) }
        }

        dialogBinding.iconHome.isSelected = true
        dialogBinding.colorBlue.isSelected = true

        // Create asks nothing about the alert area: a new place gets the 150 m default
        // radius and is monitored for enter/leave only once an alert targets it ("an
        // alert turns monitoring on"). The area is tuned later from the place's bell
        // (showPlaceAlertEditor), where the alerts themselves live.

        // Inline Place Visibility — queues shares locally because the
        // place doesn't exist yet; the PlaceCreated state-observer
        // drains into sharePlaceWithCircle once viewModel.createPlace
        // returns an id.
        pendingCreateShares = bindPlaceVisibility(
            context = requireContext(),
            binding = dialogBinding,
            editingPlace = null,
            availableCircles = viewModel.circles.value,
            placeRepository = placeRepository,
            scope = viewLifecycleOwner.lifecycleScope,
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener {
            // User backed out — drop the queued shares so they don't
            // leak into the next dialog that opens.
            pendingCreateShares = null
            dialog.dismiss()
        }

        dialogBinding.btnCreate.setOnClickListener {
            val name = dialogBinding.etPlaceName.text?.toString()?.trim() ?: ""
            val notes = dialogBinding.etPlaceNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            if (name.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.place_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Nowhere to put it yet: the dialog stays open with what was typed.
            val latitude = currentLat
            val longitude = currentLng
            if (latitude == null || longitude == null) {
                Toast.makeText(requireContext(), R.string.place_needs_a_position, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            viewModel.createPlace(
                name = name,
                notes = notes,
                latitude = latitude,
                longitude = longitude,
                radius = 150,
                icon = selectedIcon,
                color = selectedColor,
                boundaryRadius = null,
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showEditPlaceDialog(place: PlaceEntity) {
        val dialogBinding = DialogCreatePlaceBinding.inflate(layoutInflater)

        // Editing an existing place — the location is already chosen,
        // so the address-search section would just be confusing. Hide it.
        dialogBinding.layoutSearch.visibility = View.GONE

        var selectedIcon = place.icon ?: "home"
        var selectedColor = place.color ?: "#2196F3"

        dialogBinding.etPlaceName.setText(place.name)
        dialogBinding.etPlaceNotes.setText(place.notes ?: "")
        dialogBinding.tvDialogTitle.text = getString(R.string.places_edit)

        // This editor owns the place's identity only — name, notes, icon, colour.
        // Alerts and the alert-area radius belong to the per-place bell dialog
        // (showPlaceAlertEditor), because the radius IS the alert's trigger ring. So
        // the save below passes radius=null and updatePlace keeps the stored radius.

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
            iconButtons.keys.forEach { it.isSelected = (it == selectedButton) }
        }
        fun updateColorSelection(selectedButton: android.widget.ImageButton) {
            colorButtons.keys.forEach { it.isSelected = (it == selectedButton) }
        }

        iconButtons.forEach { (button, iconName) ->
            button.setOnClickListener { selectedIcon = iconName; updateIconSelection(button) }
        }
        colorButtons.forEach { (button, colorHex) ->
            button.setOnClickListener { selectedColor = colorHex; updateColorSelection(button) }
        }

        iconButtons.entries.find { it.value == selectedIcon }?.key?.isSelected = true
        colorButtons.entries.find { it.value == selectedColor }?.key?.isSelected = true

        // The inline Place Visibility section is deliberately NOT wired up here; it
        // stays GONE per the layout default. An existing place is shared from the
        // per-row Share button (showSharePlaceDialog), which drives the same
        // sharePlaceWithCircle / unshare flow, and two ways to do one thing is how
        // the two diverge. The CREATE dialog does wire it, because share-on-create
        // happens before there is a row to Share from.

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnCreate.text = getString(R.string.save)

        dialogBinding.btnCreate.setOnClickListener {
            val name = dialogBinding.etPlaceName.text?.toString()?.trim() ?: ""
            // Pass notes through as "", not null, when the user clears them
            // so `updatePlace` can tell "clear" apart from "don't touch".
            val notes = dialogBinding.etPlaceNotes.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                Toast.makeText(requireContext(), getString(R.string.place_name_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.updatePlace(
                placeId = place.id,
                name = name,
                notes = notes,
                icon = selectedIcon,
                color = selectedColor,
                radius = null,
                boundaryRadius = null
            )
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showListOrEmpty() {
        val empty = shownPlaces == 0 && shownPending == 0
        UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreatePlace)
        binding.rvPlaces.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun observeViewModel() {
        binding.tvCircleName.text = getString(R.string.places_mine)
        viewModel.refreshPlaces()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.places.collectLatest { places ->
                binding.swipeRefresh.isRefreshing = false
                placesAdapter.submitList(places)
                shownPlaces = places.size
                showListOrEmpty()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pending.items.collectLatest { items ->
                pendingAdapter.submitList(items)
                shownPending = items.size
                showListOrEmpty()
                viewModel.pending.seen(items)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is PlacesUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is PlacesUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is PlacesUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        // A subscription gate (402) carries quota/error_code —
                        // show the premium prompt instead of a bland Toast.
                        if (state.quota != null || state.errorCode != null) {
                            com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet.showForQuota(
                                parentFragmentManager, state.quota, state.errorCode,
                            )
                        } else {
                            Toast.makeText(requireContext(), state.message ?: getString(state.fallback), Toast.LENGTH_LONG).show()
                        }
                        viewModel.resetState()
                    }
                    is PlacesUiState.PlaceCreated -> {
                        binding.progressBar.visibility = View.GONE
                        // Drain any Place-Visibility shares the user
                        // picked in the create dialog before the place had
                        // an id. Run sequentially; individual failures log
                        // but don't block the others.
                        pendingCreateShares?.let { queue ->
                            pendingCreateShares = null
                            viewLifecycleOwner.lifecycleScope.launch {
                                queue.drain { circleId ->
                                    placeRepository.sharePlaceWithCircle(state.place.id, circleId, keepIfBehind = true)
                                }
                            }
                        }
                        Toast.makeText(requireContext(), getString(R.string.place_created), Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is PlacesUiState.PlaceUpdated -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.place_updated), Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is PlacesUiState.PlaceDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.place_deleted), Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is PlacesUiState.PlaceShared -> {
                        binding.progressBar.visibility = View.GONE
                        val message = if (state.successCount == state.totalCount) {
                            resources.getQuantityString(R.plurals.places_shared_to_circles, state.successCount, state.successCount)
                        } else {
                            resources.getQuantityString(R.plurals.places_shared_partial, state.totalCount, state.successCount, state.totalCount)
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is PlacesUiState.Kept -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.line, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is PlacesUiState.AlertsToggled -> {
                        binding.progressBar.visibility = View.GONE
                        val message = getString(if (state.enabled) R.string.places_alerts_on else R.string.places_alerts_off)
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        // The bell icon is derived from the alerts map, not from the
                        // place row, so DiffUtil sees no change — rebind everything.
                        placesAdapter.notifyDataSetChanged()
                        viewModel.resetState()
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                        viewModel.resetState()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.placeAlertsMap.collectLatest {
                placesAdapter.notifyDataSetChanged()
            }
        }
    }

    /** A share or unshare that didn't happen: why, when this app has words for it, else a whole line. */
    private fun shareFailedLine(sharing: Boolean, why: String?): String = when {
        why != null -> getString(if (sharing) R.string.places_share_failed else R.string.places_unshare_failed, why)
        else -> getString(if (sharing) R.string.places_share_failed_no_reason else R.string.places_unshare_failed_no_reason)
    }

    private fun showDeleteConfirmation(place: PlaceEntity) {
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

    /**
     * "Manage sharing" dialog. Each candidate circle carries a Switch reflecting
     * whether the place is currently shared with it, and toggling fires immediately
     * via sharePlaceWithCircle / unsharePlaceFromCircle with optimistic UI and
     * rollback on failure.
     *
     * The place's own circle is hidden (sharing to its source circle is
     * meaningless under the current copy-on-share model), and circles
     * the user doesn't actually have a key for are also hidden — they
     * couldn't be decrypted in either direction.
     */
    private fun showSharePlaceDialog(place: PlaceEntity) {
        val candidates = viewModel.getOtherCircles().filter { it.id != place.circleId }
        if (candidates.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.places_share_no_circles), Toast.LENGTH_SHORT).show()
            return
        }

        val dialogBinding = DialogSharePlaceBinding.inflate(layoutInflater)

        // Live snapshot of which circles this place is shared with.
        // sharedCircles is comma-separated on the entity; rebuild as a
        // mutable set so toggles can mutate it locally before the
        // server confirms.
        val sharedSet = place.sharedCircles
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toMutableSet()
            ?: mutableSetOf()
        // Ids currently mid-flight — disabled in the UI to prevent
        // double-fires while a share/unshare is in-flight.
        val pendingIds = mutableSetOf<String>()

        lateinit var rebuild: () -> Unit
        val adapter = ShareToggleAdapter(onToggle = { circle, shouldShare ->
            if (circle.id in pendingIds) return@ShareToggleAdapter
            // Optimistic: flip local state and re-render.
            pendingIds += circle.id
            if (shouldShare) sharedSet += circle.id else sharedSet -= circle.id
            rebuild()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = if (shouldShare) {
                    placeRepository.sharePlaceWithCircle(place.id, circle.id, keepIfBehind = true)
                } else {
                    placeRepository.unsharePlaceFromCircle(place.id, circle.id)
                }
                pendingIds -= circle.id
                val kept = result.exceptionOrNull() as? com.pingolin.track.domain.pending.QueuedForSync
                if (kept != null) {
                    // Waiting to sync: it stays on, and goes out by itself.
                    Toast.makeText(requireContext(), kept.line, Toast.LENGTH_LONG).show()
                } else if (result.isFailure) {
                    // Roll back the optimistic flip.
                    if (shouldShare) sharedSet -= circle.id else sharedSet += circle.id
                    // The toggle that flipped back says which circle; the line says what didn't happen, and why
                    // when this app has words for it.
                    val why = result.exceptionOrNull()?.plainTextOrNull()
                    Toast.makeText(
                        requireContext(),
                        shareFailedLine(sharing = shouldShare, why = why),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    // Reload so the LIST agrees with what just
                    // happened. This dialog reads
                    // `place.sharedCircles` off the item it was
                    // opened with, so without a reload a
                    // successful share would still show as OFF the
                    // next time the dialog is opened, reading as
                    // "it didn't work" until the next app start.
                    viewModel.refreshPlaces()
                }
                rebuild()
            }
        })

        rebuild = {
            adapter.submitList(candidates.map { circle ->
                ShareToggleAdapter.Row(
                    circle = circle,
                    isShared = circle.id in sharedSet,
                    isPending = circle.id in pendingIds,
                )
            })
        }

        dialogBinding.rvCircles.layoutManager = ShareLinearLayoutManager(requireContext())
        dialogBinding.rvCircles.adapter = adapter
        rebuild()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(
                place.name.takeIf { it.isNotBlank() }?.let { getString(R.string.places_manage_sharing_title, it) }
                    ?: getString(R.string.places_manage_sharing_title_unnamed)
            )
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.done), null)
            .show()
    }

    /**
     * Fill in a picked POI's street when the row didn't carry one.
     *
     * Venue records have no street of their own (the geocoder's venue layer
     * carries none, and the tile path can only derive one when the containing
     * cell is cached), so "Pink's Hot Dogs" can arrive as city + state only.
     * A reverse geocode at its coordinates returns the nearest address record,
     * which does have the street.
     *
     * Best-effort and non-blocking: the address line is already correct-if-terse
     * before this runs, and anything that fails simply leaves it alone. Only
     * upgrades — never replaces a line that already has a street, and never
     * touches the Label.
     */
    private fun enrichPickedStreet(
        b: DialogCreatePlaceBinding,
        hit: uniffi.pingolin_mobile.GeocodingResult,
    ) {
        if (hit.resultType != "poi") return
        if (!hit.address.isNullOrBlank()) return // already has a street
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = runCatching {
                geocodingRepository.nearestAddress(hit.lat, hit.lng)
            }.getOrNull() ?: return@launch
            val street = resolved.address?.takeIf { it.isNotBlank() }
                ?: resolved.text.takeIf { resolved.resultType == "address" && it.isNotBlank() }
                ?: return@launch
            // The view can be gone by now — the reverse call is a network hop.
            val binding = _binding ?: return@launch
            if (binding.root.windowToken == null && !isAdded) return@launch
            val upgraded = listOf(street, hit.localityLine())
                .filter { it.isNotBlank() }
                .joinToString(", ")
            b.tvAddress.text = upgraded
        }
    }

    private fun showSelectedAddress(
        b: DialogCreatePlaceBinding,
        addressLine: String,
    ) {
        b.layoutAddress.visibility = View.VISIBLE
        b.tvAddress.text = addressLine
        b.rvSearchResults.visibility = View.GONE
        b.tvSearchEmpty.visibility = View.GONE
        b.tvSearchLoading.visibility = View.GONE
        // Clear the query and let the user move on. Without this the chosen
        // result is still highlighted and a stray re-search can overwrite
        // their pick.
        b.etSearch.setText("")
        b.etSearch.clearFocus()
        val imm = requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(b.etSearch.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        addressSearchJob?.cancel()
        addressSearchJob = null
        _binding = null
    }
}
