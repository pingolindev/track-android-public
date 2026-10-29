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

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.DestinationEntity
import com.pingolin.track.databinding.FragmentDestinationsBinding
import com.pingolin.track.databinding.DialogCreateDestinationBinding
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.smartSearch
import com.pingolin.track.ui.map.sendNowReadableName
import com.pingolin.track.ui.places.AddressSearchAdapter
import com.pingolin.track.ui.places.fullAddress
import com.pingolin.track.ui.places.suggestedLabel
import com.pingolin.track.ui.start.UnreadableEmptyState
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.DateTimeFormatter
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.plainTextOrNull
import com.pingolin.track.util.showDestructive
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DestinationsFragment : Fragment() {

    private var _binding: FragmentDestinationsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DestinationsViewModel by viewModels()
    private lateinit var adapter: DestinationAdapter
    private lateinit var pendingAdapter: com.pingolin.track.ui.pending.PendingChangesAdapter
    private var shownDestinations = 0
    private var shownPending = 0

    /** Used by the address-search field on the create-destination dialog
     *  — same smart-fallback flow as PlacesFragment + MapAddressSearchSheet
     *  (TileGeocoder + CityIndex + per-city addr+POI shard fallback,
     *  privacy-preserving, query never leaves the device). */
    @Inject
    lateinit var geocodingRepository: GeocodingRepository

    /** Circle-effective premium signal — destinations are freemium (free: 1
     *  active destination at a time, premium: unlimited), so the create flow uses
     *  this to skip the count check for premium users and, for free users, decide
     *  whether to offer cancel-and-replace instead of failing at Share. */
    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var premiumStatusRefresher: com.pingolin.track.util.PremiumStatusRefresher

    /** Reads the current user's OWN active destinations for the freemium gate
     *  (free = 1 active at a time) and cancels one for the cancel-and-replace flow. */
    @Inject
    lateinit var destinationRepository: com.pingolin.track.domain.repository.DestinationRepository

    /** Formats ETA day-buckets into the user's preferred date format. */
    @Inject
    lateinit var dateTimeFormatter: DateTimeFormatter

    /** Latest debounced search job — cancelled when the user keeps
     *  typing so we don't ship stale results into the recycler. */
    private var addressSearchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDestinationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupUI()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = DestinationAdapter(
            onDestinationClick = { /* Show details */ },
            onArriveClick = { destination -> showArriveConfirmation(destination) },
            onCancelClick = { destination -> showCancelConfirmation(destination) },
            onDeleteClick = { destination -> showDeleteConfirmation(destination) },
            onToggleHidden = { item ->
                val wasHidden = item.isHidden
                viewModel.toggleHidden(item)
                if (!wasHidden) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        getString(R.string.destination_hidden_toast),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                    ).show()
                }
            },
            formatEtaDate = { dayBucket -> dateTimeFormatter.formatDayBucket(dayBucket) }
        )

        // Destinations and arrivals waiting to sync go first, where they will appear.
        pendingAdapter = com.pingolin.track.ui.pending.PendingChangesAdapter { viewModel.pending.cancel(it) }
        binding.rvDestinations.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = androidx.recyclerview.widget.ConcatAdapter(pendingAdapter, this@DestinationsFragment.adapter)
        }
    }

    private fun showListOrEmpty() {
        val empty = shownDestinations == 0 && shownPending == 0
        UnreadableEmptyState.show(binding.emptyState, empty, binding.fabCreateDestination)
        binding.rvDestinations.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun setupUI() {
        // Disable FAB until circles are loaded
        binding.fabCreateDestination.isEnabled = false

        binding.fabCreateDestination.setOnClickListener {
            showCreateDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refreshDestinations()
        }

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.circles.collectLatest { circles ->
                // Enable the FAB only when circles are available
                binding.fabCreateDestination.isEnabled = circles.isNotEmpty()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.destinations.collectLatest { destinations ->
                binding.swipeRefresh.isRefreshing = false
                adapter.submitList(destinations)
                shownDestinations = destinations.size
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
                    is DestinationsUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is DestinationsUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                    }
                    is DestinationsUiState.Error -> {
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
                    is DestinationsUiState.DestinationCreated -> {
                        binding.progressBar.visibility = View.GONE
                        // Name the circle that received it, as the map's line does; without a readable name
                        // (none, or a locked circle's list label), the unnamed line.
                        val circleId = AccountIds.circle(state.destination.circleId)
                        val circleName = viewModel.circles.value
                            .firstOrNull { circleId != null && AccountIds.circle(it.id) == circleId }?.name
                        Toast.makeText(
                            requireContext(),
                            sendNowReadableName(circleName)?.let { getString(R.string.map_destination_shared, it) }
                                ?: getString(R.string.map_destination_shared_unnamed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        viewModel.resetState()
                    }
                    is DestinationsUiState.DestinationCancelled -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.destinations_cancelled), Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is DestinationsUiState.ArrivalReported -> {
                        binding.progressBar.visibility = View.GONE
                        val message = if (state.notificationsSent > 0) {
                            resources.getQuantityString(
                                R.plurals.destinations_arrival_reported_sent, state.notificationsSent, state.notificationsSent,
                            )
                        } else {
                            getString(R.string.destinations_arrival_reported)
                        }
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                    is DestinationsUiState.DestinationDeleted -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), getString(R.string.destinations_deleted), Toast.LENGTH_SHORT).show()
                        viewModel.resetState()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showCreateDialog(bypassGate: Boolean = false) {
        val circles = viewModel.circles.value

        if (circles.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.destinations_loading_circles), Toast.LENGTH_SHORT).show()
            return
        }

        // Destinations are freemium: free users get 1 active destination at a
        // time, premium is unlimited. Offer a graceful cancel-and-replace when a
        // free user already has one active instead of a hard wall; re-enter with
        // bypassGate once cleared. No purchase CTA — Play policy forbids directing
        // to an external purchase flow from the app.
        if (!bypassGate && !sessionManager.effectivePremium.value) {
            viewLifecycleOwner.lifecycleScope.launch {
                // The cached flag can be stale-false (fresh login before the
                // startup refresh lands, or subscribed elsewhere just now).
                // Refresh + re-check so a genuinely-premium user isn't shown the
                // free limit; then decide from the user's own active count.
                try { premiumStatusRefresher.refresh() } catch (_: Exception) {}
                if (sessionManager.effectivePremium.value) {
                    showCreateDialog(bypassGate = true)
                    return@launch
                }
                val active = destinationRepository.activeMine()
                if (active.isEmpty()) {
                    showCreateDialog(bypassGate = true)
                } else {
                    showDestinationLimitReplaceDialog(active.first()) {
                        showCreateDialog(bypassGate = true)
                    }
                }
            }
            return
        }

        val dialogBinding = DialogCreateDestinationBinding.inflate(layoutInflater)

        val circleNames = circles.map { it.name }.toTypedArray()
        val circleAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, circleNames)
        dialogBinding.spinnerCircle.adapter = circleAdapter

        // Picked address — populated when the user taps a result row.
        // The Share button uses these coords; if null, it nudges the
        // user to pick a result first, since coordinates are never
        // typed in.
        var pickedLat: Double? = null
        var pickedLng: Double? = null
        var pickedAddressLine: String? = null

        fun showPickedAddress(line: String, lat: Double, lng: Double) {
            pickedLat = lat
            pickedLng = lng
            pickedAddressLine = line
            dialogBinding.tvSelectedAddress.text = line
            dialogBinding.layoutSelectedAddress.visibility = View.VISIBLE
            // Collapse the result list — the user committed to a pick.
            dialogBinding.rvSearchResults.visibility = View.GONE
            dialogBinding.tvSearchEmpty.visibility = View.GONE
            dialogBinding.tvSearchLoading.visibility = View.GONE
            dialogBinding.etSearch.setText("")
            dialogBinding.etSearch.clearFocus()
            val imm = requireContext()
                .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(dialogBinding.etSearch.windowToken, 0)
        }

        // Reuse the same adapter Places + Map use so address/POI/city
        // result rows look identical across every search surface.
        val searchAdapter = AddressSearchAdapter(
            onCachedClick = { hit ->
                showPickedAddress(hit.fullAddress(), hit.lat, hit.lng)
                // Same as create-place: picking a POI should name the
                // destination, unless the user already typed something.
                hit.suggestedLabel()?.let { suggested ->
                    if (dialogBinding.etDestName.text.isNullOrBlank()) {
                        dialogBinding.etDestName.setText(suggested)
                    }
                }
            },
            onCityClick = { city ->
                val label = listOfNotNull(
                    city.name,
                    city.admin1.takeIf { it.isNotBlank() },
                    city.countryCode.takeIf { it.isNotBlank() },
                ).joinToString(", ")
                showPickedAddress(label, city.lat, city.lng)
            },
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
                    delay(250)
                    dialogBinding.tvSearchLoading.text = getString(R.string.places_searching)
                    dialogBinding.tvSearchLoading.visibility = View.VISIBLE

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
                            // The dialog's own lines: no map here to pick a spot on.
                            getString(R.string.destinations_search_no_match_in, scope.name + tail)
                        } ?: getString(R.string.destinations_search_no_match)
                        dialogBinding.tvSearchEmpty.visibility = View.VISIBLE
                    }
                }
            }
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.map_tapped_share_destination))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.share)) { _, _ ->
                val selectedIndex = dialogBinding.spinnerCircle.selectedItemPosition
                if (selectedIndex < 0 || selectedIndex >= circles.size) return@setPositiveButton
                val selectedCircle = circles[selectedIndex]
                val destName = dialogBinding.etDestName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                val lat = pickedLat
                val lng = pickedLng
                if (lat == null || lng == null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.destinations_pick_result_first),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                val notifyArrival = dialogBinding.checkboxNotifyArrival.isChecked
                viewModel.createDestination(
                    circleId = selectedCircle.id,
                    destName = destName ?: pickedAddressLine,
                    destLatitude = lat,
                    destLongitude = lng,
                    notifyArrival = notifyArrival,
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** Free tier includes 1 active destination at a time. When a free user who
     *  already has one active tries to share another, offer to cancel the current
     *  trip and continue (cancel-and-replace) — not a hard wall. The Premium
     *  mention is informational only (no purchase CTA), per Google Play's
     *  external-purchase policy. [onReplaced] runs after the current destination
     *  is cancelled on the server. */
    private fun showDestinationLimitReplaceDialog(
        active: DestinationEntity,
        onReplaced: () -> Unit,
    ) {
        // No stand-in name in the frame: without a name, the unnamed line says it.
        val name = active.destName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.destination_limit_title))
            .setMessage(name?.let { getString(R.string.destination_limit_body, it) } ?: getString(R.string.destination_limit_body_unnamed))
            .setPositiveButton(getString(R.string.destination_limit_replace)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    destinationRepository.cancelDestination(active.id).fold(
                        onSuccess = { onReplaced() },
                        onFailure = { e ->
                            Toast.makeText(requireContext(), e.plainTextOrNull() ?: getString(R.string.map_cancel_trip_failed), Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
            .setNegativeButton(getString(R.string.destination_limit_keep), null)
            .show()
    }

    // Each question names the destination only when it has a name (a blank one would read "…arrived
    // at ?"); otherwise its unnamed line.
    private fun showArriveConfirmation(destination: DestinationEntity) {
        val destName = destination.destName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.destinations_report_arrival_title))
            .setMessage(
                destName?.let { getString(R.string.destinations_report_arrival_body, it) }
                    ?: getString(R.string.destinations_report_arrival_body_unnamed)
            )
            .setPositiveButton(getString(R.string.destinations_arrived_button)) { _, _ ->
                viewModel.reportArrival(destination)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showCancelConfirmation(destination: DestinationEntity) {
        val destName = destination.destName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.destinations_cancel_title))
            .setMessage(
                destName?.let { getString(R.string.destinations_cancel_body, it) }
                    ?: getString(R.string.destinations_cancel_body_unnamed)
            )
            .setPositiveButton(getString(R.string.destinations_cancel_trip)) { _, _ ->
                viewModel.cancelDestination(destination.id)
            }
            .setNegativeButton(getString(R.string.destinations_keep), null)
            .show()
    }

    private fun showDeleteConfirmation(destination: DestinationEntity) {
        val destName = destination.destName?.takeIf { it.isNotBlank() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.destinations_delete_title))
            .setMessage(
                destName?.let { getString(R.string.destinations_delete_body, it) }
                    ?: getString(R.string.destinations_delete_body_unnamed)
            )
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteDestination(destination.id)
            }
            .setNegativeButton(getString(R.string.destinations_keep), null)
            .showDestructive()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
