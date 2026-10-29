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

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pingolin.track.R
import com.pingolin.track.databinding.DialogCityScopePickerBinding
import com.pingolin.track.domain.repository.GeocodingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult
import javax.inject.Inject

/**
 * Bottom-sheet picker for the address-search "scope" chip on the
 * create-place dialog. The user types a city or region; results come
 * from the global CityIndex (privacy-preserving local FTS — query
 * never leaves the device). Tapping a result calls back to the host
 * with the chosen city/coordinates and dismisses the sheet.
 *
 * Optional GPS shortcut sits at the top: if the host passed in a
 * current GPS fix, "Use my current location" surfaces the
 * reverse-geocoded address as a one-tap pick.
 */
@AndroidEntryPoint
class CityScopePickerSheet : BottomSheetDialogFragment() {

    @Inject
    lateinit var geocodingRepository: GeocodingRepository

    /** Filled by the host fragment via [pick]. We hold a soft
     *  reference here only — the sheet is short-lived. */
    private var listener: OnCityPickedListener? = null

    private var _binding: DialogCityScopePickerBinding? = null
    private val binding get() = _binding!!

    private var searchJob: Job? = null

    fun interface OnCityPickedListener {
        /**
         * Called when the user picks a result. `name` is a display
         * string (e.g. "Seattle, WA, US" or the GPS-reverse-geocoded
         * address); `lat`/`lng` are the chosen coordinates.
         */
        fun onCityPicked(name: String, lat: Double, lng: Double)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogCityScopePickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Default BottomSheetDialog peeks a bit too short for our
        // content. Force a comfortable initial height by expanding once
        // the sheet attaches.
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener {
            dialog.behavior.state =
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // GPS shortcut — visible only if the caller seeded a fix
        val gpsLat = arguments?.getDouble(ARG_GPS_LAT, Double.NaN) ?: Double.NaN
        val gpsLng = arguments?.getDouble(ARG_GPS_LNG, Double.NaN) ?: Double.NaN
        val gpsLabel = arguments?.getString(ARG_GPS_LABEL).orEmpty()
        if (!gpsLat.isNaN() && !gpsLng.isNaN()) {
            binding.layoutUseGps.visibility = View.VISIBLE
            binding.tvGpsLabel.text = gpsLabel.ifBlank { com.pingolin.track.util.coordinatesText(gpsLat, gpsLng) }
            binding.layoutUseGps.setOnClickListener {
                listener?.onCityPicked(
                    name = gpsLabel.ifBlank { getString(R.string.city_scope_current_location) },
                    lat = gpsLat,
                    lng = gpsLng,
                )
                dismiss()
            }
        }

        val adapter = AddressSearchAdapter(
            onCachedClick = { hit -> deliver(hit) },
            onCityClick = { city -> deliver(city) },
        )
        binding.rvScopeResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScopeResults.adapter = adapter

        binding.etScopeQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                if (q.isEmpty()) {
                    adapter.clear()
                    // Back to the start line: it may still say the last "No matches for …".
                    binding.tvScopeHint.setText(R.string.city_scope_start_hint)
                    binding.tvScopeHint.visibility = View.VISIBLE
                    binding.tvScopeLoading.visibility = View.GONE
                    return
                }
                binding.tvScopeHint.visibility = View.GONE
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(250)
                    binding.tvScopeLoading.visibility = View.VISIBLE
                    val cities = citiesOrNone {
                        // Rank toward the user's own fix when the caller seeded
                        // one, so a same-name city near them outranks a bigger
                        // one on another continent.
                        geocodingRepository.searchCities(
                            q,
                            limit = 12,
                            focusLat = gpsLat.takeIf { !it.isNaN() },
                            focusLng = gpsLng.takeIf { !it.isNaN() },
                        )
                    }
                    // Anything past this point runs after a suspend, so the view may be
                    // gone: bail rather than touch a dead binding.
                    val b = _binding ?: return@launch
                    b.tvScopeLoading.visibility = View.GONE
                    if (cities.isEmpty()) {
                        adapter.clear()
                        b.tvScopeHint.text = getString(R.string.city_scope_no_match, q)
                        b.tvScopeHint.visibility = View.VISIBLE
                    } else {
                        // The picker only surfaces city results — local
                        // tile cache hits are address-level and don't
                        // make sense as a "search area" choice. Pass an
                        // empty `cached` list so the section header is
                        // suppressed.
                        adapter.submit(emptyList(), cities)
                    }
                }
            }
        })

        binding.etScopeQuery.requestFocus()
    }

    private fun deliver(hit: GeocodingResult) {
        listener?.onCityPicked(name = hit.text, lat = hit.lat, lng = hit.lng)
        hideKeyboard()
        dismiss()
    }

    private fun deliver(city: CityResult) {
        val label = listOfNotNull(
            city.name,
            city.admin1.takeIf { it.isNotBlank() },
            city.countryCode.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        listener?.onCityPicked(name = label, lat = city.lat, lng = city.lng)
        hideKeyboard()
        dismiss()
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etScopeQuery.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchJob?.cancel()
        searchJob = null
        _binding = null
        listener = null
    }

    fun setOnCityPickedListener(l: OnCityPickedListener) { listener = l }

    companion object {
        /**
         * [search]'s cities, or none when it failed. A cancellation is never a failure: the sheet
         * went away mid-search (a tier download can take seconds), and the search must stop
         * there. Taken for "no matches" (`runCatching`), it would run on into the binding
         * onDestroyView had cleared, and crash the app.
         */
        internal suspend fun citiesOrNone(search: suspend () -> List<CityResult>): List<CityResult> =
            try {
                search()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }

        private const val ARG_GPS_LAT = "gps_lat"
        private const val ARG_GPS_LNG = "gps_lng"
        private const val ARG_GPS_LABEL = "gps_label"

        /**
         * Build the sheet with an optional GPS shortcut. Pass null
         * coordinates to omit the "Use my current location" row.
         */
        fun newInstance(
            gpsLat: Double? = null,
            gpsLng: Double? = null,
            gpsLabel: String? = null,
        ): CityScopePickerSheet = CityScopePickerSheet().apply {
            arguments = Bundle().apply {
                if (gpsLat != null) putDouble(ARG_GPS_LAT, gpsLat)
                if (gpsLng != null) putDouble(ARG_GPS_LNG, gpsLng)
                if (gpsLabel != null) putString(ARG_GPS_LABEL, gpsLabel)
            }
        }
    }
}
