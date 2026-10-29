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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pingolin.track.R
import com.pingolin.track.domain.repository.haversineKm
import com.pingolin.track.util.uiLocale
import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult

/**
 * Two-section results adapter for the address-search dialog:
 *   - "Cached areas" — TileGeocoder forward hits (free, instant, only
 *     covers tiles already on disk)
 *   - "Other locations" — CityIndex hits (privacy-preserving, lazy
 *     downloads ~7 MB tier 1 + ~5 MB tier 2 the first time anyone
 *     uses it, results then come from a local FTS5 index)
 *
 * Sections are hidden when their result list is empty so the user
 * doesn't see "Cached areas" with zero rows.
 */
class AddressSearchAdapter(
    private val onCachedClick: (GeocodingResult) -> Unit,
    private val onCityClick: (CityResult) -> Unit,
    /**
     * Map centre, when the host has one. Rows then carry how far away they are,
     * which is the only thing that separates two branches of the same chain in
     * the same neighbourhood. Null on the create-place and destination dialogs,
     * which have no map on screen — those rows simply omit it.
     */
    private val focusLat: Double? = null,
    private val focusLng: Double? = null,
    /** Settings -> Account -> Distance units. */
    private val useMiles: Boolean = false,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        /** [title] when the caller names the section, else the adapter's own [default] words. */
        data class Section(val title: String?, @androidx.annotation.StringRes val default: Int) : Row()
        data class Cached(val result: GeocodingResult) : Row()
        data class City(val result: CityResult) : Row()
    }

    private var rows: List<Row> = emptyList()

    /**
     * @param cachedSectionTitle Override the default "Cached areas"
     *   section header. Pass null to use the default. The smart-
     *   fallback path uses this to surface the resolved scope as
     *   "In Pleasanton, CA" so the user knows which city/state the
     *   results came from instead of seeing the generic "Cached
     *   areas" label.
     */
    fun submit(
        cached: List<GeocodingResult>,
        cities: List<CityResult>,
        cachedSectionTitle: String? = null,
    ) {
        val next = ArrayList<Row>(cached.size + cities.size + 2)
        if (cached.isNotEmpty()) {
            next.add(Row.Section(cachedSectionTitle, R.string.search_section_cached))
            cached.forEach { next.add(Row.Cached(it)) }
        }
        if (cities.isNotEmpty()) {
            next.add(Row.Section(null, R.string.search_section_other))
            cities.forEach { next.add(Row.City(it)) }
        }
        rows = next
        notifyDataSetChanged()
    }

    fun clear() {
        if (rows.isEmpty()) return
        rows = emptyList()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Section -> TYPE_SECTION
        else -> TYPE_RESULT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionVH(inflater.inflate(R.layout.item_address_search_section, parent, false))
        } else {
            ResultVH(inflater.inflate(R.layout.item_address_search_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Section -> (holder as SectionVH).bind(row.title ?: holder.itemView.context.getString(row.default))
            is Row.Cached -> (holder as ResultVH).bindCached(row.result, onCachedClick, focusLat, focusLng, useMiles)
            is Row.City -> (holder as ResultVH).bindCity(row.result, onCityClick, focusLat, focusLng, useMiles)
        }
    }

    private class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv = view as TextView
        fun bind(title: String) { tv.text = title }
    }

    private class ResultVH(view: View) : RecyclerView.ViewHolder(view) {
        private val primary: TextView = view.findViewById(R.id.tvResultPrimary)
        private val secondary: TextView = view.findViewById(R.id.tvResultSecondary)
        private val root = view

        fun bindCached(
            r: GeocodingResult,
            click: (GeocodingResult) -> Unit,
            focusLat: Double?,
            focusLng: Double?,
            useMiles: Boolean,
        ) {
            // Two-line layout — primary holds the street (address rows)
            // or the POI name without locality (POI rows), secondary
            // holds "City, State, Country" built from the SDK's
            // structured fields. Mirrors the web map-search row shape.
            //
            // POI rows: prefer `name` for primary so we don't repeat
            // "Austin, TX" in both lines (the SDK still sets `text` to
            // "Name, City, State" for legacy callers, so reading `text`
            // would duplicate the locality).
            val isPoi = r.resultType == "poi"
            primary.text = if (isPoi && !r.name.isNullOrBlank()) r.name else r.text
            // One formatter for the locality, shared with the create-place
            // address line — including the rule that the user's own country is
            // left off. Still drop any part that merely repeats the primary
            // line ("Modesto" under "Modesto").
            val locality = r.localityLine()
                .split(", ")
                .filter { it.isNotBlank() && it.lowercase() != primary.text.toString().lowercase() }
                .joinToString(", ")
            // POI rows lead with the street address so two same-named places —
            // e.g. several "Togo's" — are distinguishable at a glance; locality
            // follows. `address` is the POI's OWN street where that is known (the
            // server, and Overture-sourced local tiles) and the nearest address in
            // the cell otherwise, so this line is exact for most rows and
            // approximate for the rest. Address rows
            // keep just the locality (their primary line already is the street).
            val base = if (isPoi && !r.address.isNullOrBlank()) {
                listOf(r.address!!, locality).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                locality
            }
            // Distance last: it is the tiebreaker when everything else about two
            // rows reads the same, and it is the part you can afford to lose to
            // a wrap if the locality runs long.
            val distance = if (focusLat != null && focusLng != null) {
                formatDistance(haversineKm(r.lat, r.lng, focusLat, focusLng), useMiles, uiLocale(root.context))
            } else null
            val secondaryText = listOfNotNull(base.takeIf { it.isNotBlank() }, distance)
                .joinToString(" · ")
            if (secondaryText.isNotBlank()) {
                secondary.text = secondaryText
                secondary.visibility = View.VISIBLE
            } else {
                // Older tiles without locality columns simply have no
                // useful secondary line — hide it instead of showing a
                // bare "Poi" / "Address" label that means nothing to a
                // user.
                secondary.text = ""
                secondary.visibility = View.GONE
            }
            root.setOnClickListener { click(r) }
        }

        fun bindCity(
            r: CityResult,
            click: (CityResult) -> Unit,
            focusLat: Double?,
            focusLng: Double?,
            useMiles: Boolean,
        ) {
            primary.text = r.name
            // Secondary line: "<admin1>, <country> · <population> people"
            // when admin1 is set, otherwise just country + population.
            val parts = ArrayList<String>(2)
            val adminCountry = listOfNotNull(
                r.admin1.takeIf { it.isNotBlank() },
                // Same rule as the address rows: the user's own country is
                // noise on every domestic result.
                r.countryCode.takeIf { it.isNotBlank() && !isHomeCountryCode(it) },
            ).joinToString(", ")
            if (adminCountry.isNotEmpty()) parts += adminCountry
            if (focusLat != null && focusLng != null) {
                parts += formatDistance(haversineKm(r.lat, r.lng, focusLat, focusLng), useMiles, uiLocale(root.context))
            }
            if (r.population > 0) parts += formatPopulation(r.population)
            secondary.text = parts.joinToString(" · ")
            root.setOnClickListener { click(r) }
        }

        // Compact population in the words' language (uiLocale), as the web's (Intl compact, at most
        // one decimal): "1.3M", "737K", "832"; de "1,3 Mio.", ja "126万".
        private fun formatPopulation(pop: Long): String {
            val compact = android.icu.text.CompactDecimalFormat.getInstance(
                uiLocale(root.context), android.icu.text.CompactDecimalFormat.CompactStyle.SHORT,
            ).apply { maximumFractionDigits = 1 }.format(pop)
            return root.context.getString(R.string.search_population, compact)
        }
    }

    companion object {
        private const val TYPE_SECTION = 0
        private const val TYPE_RESULT = 1
    }
}
