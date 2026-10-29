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

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pingolin.track.R
import com.pingolin.track.databinding.DialogMapAddressSearchBinding
import com.pingolin.track.domain.repository.GeocodingRepository
import com.pingolin.track.domain.repository.SearchArea
import com.pingolin.track.domain.repository.SmartSearchScope
import com.pingolin.track.domain.repository.searchAreaFromViewport
import com.pingolin.track.domain.repository.smartSearch
import com.pingolin.track.ui.places.AddressSearchAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult
import javax.inject.Inject

/**
 * How long typing must pause before a search runs.
 *
 * It must stay comfortably longer than the gap between keystrokes. At 250 ms a
 * ten-character query fires ten searches, each cancelling the last mid-flight,
 * and on the premium path each one also spends (or tries to mint) an anonymous
 * credit. 350 ms is the usual search-as-you-type figure: still feels immediate,
 * but a continuous typist produces one request rather than one per character.
 */
private const val SEARCH_DEBOUNCE_MS = 350L

/**
 * Map-screen address search bottom sheet. Mirrors the smart-fallback search
 * PlacesFragment uses for create-place — the user types an address (or POI, or
 * city) and the sheet:
 *   1. Runs the local TileGeocoder forward search across cached tiles, confined
 *      to the map viewport handed over by [setViewport].
 *   2. Runs the global CityIndex search for city candidates.
 *   3. If both come back empty, parses a "&lt;address&gt;, &lt;city&gt;[, &lt;state&gt;]" or
 *      "&lt;words…&gt; &lt;city&gt;[ &lt;state&gt;]" hint, resolves the city, surgically loads
 *      the per-city addr+POI shard's tile_ids (or the centroid + 8 geohash
 *      neighbours as a fallback), then re-searches the remainder against the
 *      freshly-loaded tile.
 *
 * The viewport clamp in step 1 is what makes this behave like Google Maps: an
 * unqualified query only returns what is on screen, and you reach anywhere else
 * by naming it — which drops through to step 3, where no clamp applies.
 *
 * Picking a result calls back to the host fragment with the chosen coordinates +
 * a display label; the host flies the map there and drops a tapped marker (the
 * same state as if the user had tapped that spot).
 */
@AndroidEntryPoint
class MapAddressSearchSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var geocodingRepository: GeocodingRepository

    @Inject
    lateinit var sessionManager: com.pingolin.track.util.SessionManager

    private var listener: OnLocationPickedListener? = null

    // Map centre, set by the host before the sheet is shown. Lets the search
    // proximity-rank toward what's on screen and, on a miss, default the scope to
    // the current city.
    private var focusLat: Double? = null
    private var focusLng: Double? = null

    // Visible map bounds, set by the host before the sheet is shown. Confines an
    // unqualified query to what the user can actually see.
    private var viewport: SearchArea? = null

    private var _binding: DialogMapAddressSearchBinding? = null
    private val binding get() = _binding!!

    /**
     * Run [block] only while the sheet's view is alive.
     *
     * `binding` is `_binding!!` and the search coroutine can outlive
     * `onDestroyView` — cancelling the scope is NOT a guarantee that it stops,
     * because a `catch (Throwable)` anywhere downstream swallows the
     * cancellation. Reaching `binding` directly from a suspend path crashes with
     * an NPE a second or so after dismissal, from whichever callback ran last.
     * Every such access has to go through this.
     */
    private inline fun withBinding(block: (DialogMapAddressSearchBinding) -> Unit) {
        _binding?.let(block)
    }

    private var searchJob: Job? = null

    fun interface OnLocationPickedListener {
        /**
         * Called when the user picks a result. `label` is the display
         * line (street + locality, or city/state, or POI name +
         * locality); `lat`/`lng` are the chosen coordinates. The host
         * should fly the map there and drop a tapped marker so the
         * location-card actions surface like a deliberate map tap.
         */
        fun onLocationPicked(label: String, lat: Double, lng: Double)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogMapAddressSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener {
            // A FIXED tall height, so the search input can't shift as the result
            // list grows beneath it. With wrap_content the sheet is anchored to the
            // bottom of the screen and creeps upward each time results arrive, which
            // is jarring mid-type; every map search keeps the input as a stable
            // visual anchor.
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            if (bottomSheet != null) {
                // 90%, not 100%: the strip of dimmed map left at the top is what
                // makes tap-outside-to-dismiss discoverable.
                val targetHeight = (resources.displayMetrics.heightPixels * 0.9).toInt()
                bottomSheet.layoutParams.height = targetHeight
                bottomSheet.requestLayout()
                dialog.behavior.peekHeight = targetHeight
            }
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = AddressSearchAdapter(
            onCachedClick = { hit -> deliverGeocoding(hit) },
            onCityClick = { city -> deliverCity(city) },
            // Only this surface has a map behind it, so only its rows show a
            // distance — it is what separates two branches of the same chain.
            focusLat = focusLat,
            focusLng = focusLng,
            useMiles = sessionManager.useMiles(),
        )
        binding.rvMapSearchResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvMapSearchResults.adapter = adapter
        // Not the layout's line: the map hands over no view while it has none yet (MapFragment's
        // showAddressSearchSheet), and then "this part of the map" is wrong from the start.
        binding.tvMapSearchHint.text = startHint()

        binding.etMapSearchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                if (q.isEmpty()) {
                    adapter.clear()
                    binding.tvMapSearchHint.text = startHint()
                    binding.tvMapSearchHint.visibility = View.VISIBLE
                    binding.tvMapSearchLoading.visibility = View.GONE
                    return
                }
                binding.tvMapSearchHint.visibility = View.GONE
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    runSmartFallbackSearch(q, adapter)
                }
            }
        })

        binding.etMapSearchQuery.requestFocus()
    }

    /**
     * Run the shared smart-fallback search. The helper owns the whole flow (local +
     * cities, then the comma/whitespace candidate loop with shard lookup or
     * neighbour fallback); this layer only feeds the result into the adapter and
     * updates the loading / empty-state messaging.
     */
    private suspend fun runSmartFallbackSearch(
        q: String,
        adapter: AddressSearchAdapter,
        cityWide: Boolean = false,
    ) {
        withBinding { b ->
            b.tvMapSearchExpand.visibility = View.GONE
            b.tvMapSearchLoading.text =
                getString(if (cityWide) R.string.map_search_searching_whole_city else R.string.places_searching)
            b.tvMapSearchLoading.visibility = View.VISIBLE
        }

        // The current-city auto-scope may pull a large single tile, so it is limited
        // to unmetered connections. A city the user typed explicitly still downloads
        // on any network — they asked for it.
        val unmetered = try {
            val cm = context?.getSystemService(android.net.ConnectivityManager::class.java)
            cm != null && !cm.isActiveNetworkMetered
        } catch (e: Exception) {
            false
        }

        val result = geocodingRepository.smartSearch(
            query = q,
            limit = 8,
            focusLat = focusLat,
            focusLng = focusLng,
            allowMeteredTileLoad = unmetered,
            cityWide = cityWide,
            // "Search the whole area" is the user explicitly asking to leave
            // the viewport, so it runs unclamped.
            area = if (cityWide) null else viewport,
            onProgress = ::showProgress,
        )

        // Anything past this point runs after a suspend, so the view may be
        // gone: bail rather than touch a dead binding.
        val b = _binding ?: return
        b.tvMapSearchLoading.visibility = View.GONE

        val hasFocus = focusLat != null && focusLng != null

        if (result.addresses.isNotEmpty() || result.cities.isNotEmpty()) {
            val scopeLabel = result.scopedTo?.let { scope ->
                getString(R.string.places_search_in, scope.name + if (scope.admin1.isNotBlank()) ", ${scope.admin1}" else "")
            }
            adapter.submit(result.addresses, result.cities, cachedSectionTitle = scopeLabel)
            b.tvMapSearchHint.visibility = View.GONE
            // Local search only covers the cell under the cursor, so offer to broaden
            // to the whole city — pointless after an already city-wide run.
            if (!cityWide && hasFocus) showExpandControl(q, adapter)
            return
        }

        adapter.clear()
        b.tvMapSearchHint.text = emptyStateFor(q, result.scopedTo, cityWide)
        b.tvMapSearchHint.visibility = View.VISIBLE
        // Even on a miss, let the user widen to the whole city in one tap.
        if (!cityWide && hasFocus) showExpandControl(q, adapter)
    }

    /**
     * Progress line shown while the search downloads tiles for a resolved
     * scope. `null` clears it. Invoked from inside the search coroutine, so
     * it must tolerate a dismissed sheet.
     */
    private fun showProgress(scope: SmartSearchScope?) = withBinding { b ->
        if (scope == null) {
            b.tvMapSearchLoading.visibility = View.GONE
            return@withBinding
        }
        val adminCountry = listOf(scope.admin1, scope.countryCode)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        b.tvMapSearchLoading.text = getString(
            R.string.places_search_loading,
            scope.name + if (adminCountry.isNotEmpty()) ", $adminCountry" else "",
        )
        b.tvMapSearchLoading.visibility = View.VISIBLE
    }

    /**
     * Empty-state copy. When the viewport clamp is what emptied the list the
     * user needs to know results were restricted to the screen — otherwise
     * "0 results" reads as "this place doesn't exist" and hides the two ways
     * out (name a city, or widen).
     */
    private fun emptyStateFor(q: String, scope: SmartSearchScope?, cityWide: Boolean): String =
        when {
            scope != null -> {
                val tail = if (scope.admin1.isNotBlank()) ", ${scope.admin1}" else ""
                getString(R.string.places_search_no_match_in, scope.name + tail)
            }
            viewport != null && !cityWide -> getString(R.string.map_search_no_match_here, q)
            else -> getString(R.string.places_search_no_match)
        }

    /**
     * Reveal the "search the whole city" control. Tapping re-runs the same
     * query city-wide (loads the focused city's full tile set, then searches).
     */
    private fun showExpandControl(q: String, adapter: AddressSearchAdapter) = withBinding { b ->
        b.tvMapSearchExpand.visibility = View.VISIBLE
        b.tvMapSearchExpand.setOnClickListener {
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                runSmartFallbackSearch(q, adapter, cityWide = true)
            }
        }
    }

    private fun deliverGeocoding(hit: GeocodingResult) {
        listener?.onLocationPicked(label = hit.text, lat = hit.lat, lng = hit.lng)
        hideKeyboard()
        dismiss()
    }

    private fun deliverCity(city: CityResult) {
        val label = listOfNotNull(
            city.name,
            city.admin1.takeIf { it.isNotBlank() },
            city.countryCode.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        listener?.onLocationPicked(label = label, lat = city.lat, lng = city.lng)
        hideKeyboard()
        dismiss()
    }

    private fun hideKeyboard() = withBinding { b ->
        val imm = context
            ?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(b.etMapSearchQuery.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        searchJob = null
        _binding = null
        listener = null
    }

    fun setOnLocationPickedListener(l: OnLocationPickedListener) {
        listener = l
    }

    /** What the empty sheet says: "this part of the map" only once the map handed over its view ([setViewport]). */
    private fun startHint(): String =
        getString(if (viewport != null) R.string.map_search_hint_view else R.string.map_search_hint)

    /** Set the map center so the search can rank toward / default to it. */
    fun setFocus(lat: Double, lng: Double) {
        focusLat = lat
        focusLng = lng
    }

    /**
     * Set the visible map bounds. An unqualified query then only returns
     * results inside them (padded a little — see [searchAreaFromViewport]).
     * Leave unset to search every cached tile, which is what the create-place
     * and destination dialogs want since they have no map on screen.
     */
    fun setViewport(south: Double, west: Double, north: Double, east: Double) {
        viewport = searchAreaFromViewport(south, west, north, east)
    }
}
