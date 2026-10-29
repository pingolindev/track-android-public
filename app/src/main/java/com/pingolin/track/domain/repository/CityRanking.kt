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

package com.pingolin.track.domain.repository

import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult
import java.util.Locale
import kotlin.math.log10

/*
 * Ranking for city candidates — the rows the map/place search offers when the
 * user names a place rather than an address.
 *
 * The index itself can only order by population, which on its own is wrong in
 * both directions: it would bury Delhi, IN under Delhi, CA if a locale tier won
 * by default, and it would happily put Birmingham, England above Birmingham,
 * Alabama for someone standing in Ohio.
 *
 * Three signals, blended on a log-population scale so they are commensurable:
 *
 *   score = log10(population)
 *         - [DISTANCE_WEIGHT] * log10(km from the focus point)
 *         + [HOME_COUNTRY_BONUS] when the row is in the user's own country
 *
 * In plain terms, with both weights at 1.0: **being ten times further away
 * costs exactly what being ten times smaller costs, and being in your own
 * country is worth a tenfold population advantage.** That is deliberately a
 * rule you can hold in your head — the alternative, a pile of hand-tuned
 * thresholds, is unexplainable the first time it ranks something oddly.
 *
 * What it produces (focus = Akron, OH):
 *   - "springfield"  -> Springfield **OH** first, ahead of the bigger MA/MO/IL ones
 *   - "birmingham"   -> Birmingham **AL** first, Birmingham GB second
 *   - "delhi"        -> Delhi **IN** first — 10.9M at 12 000 km still beats
 *                       11k at 3 400 km, so prominence is not smothered
 *   - "london"       -> London **GB** first, not London, OH
 *   - "paris" from Paris, TX -> **Paris, TX** first; from anywhere else, France
 */

/**
 * Cost of distance, in log10(km). At 1.0, a city ten times further away needs
 * ten times the population to hold the same rank.
 */
private const val DISTANCE_WEIGHT = 1.0

/**
 * Worth of a country match, on the same log10 scale. At 1.0 a city in the
 * user's own country ranks as though it had ten times its population — enough
 * to lift Birmingham AL (212k) over Birmingham GB (1.1M), not nearly enough to
 * lift Delhi CA (11k) over Delhi IN (10.9M).
 */
private const val HOME_COUNTRY_BONUS = 1.0

/**
 * Worth of the name being EXACTLY what was typed, rather than containing it,
 * on the same log10 scale. At 0.3 an exact name outranks a longer one that is
 * up to ~2x further away (10^0.3 = 2.0) and loses beyond that.
 *
 * Deliberately small. It is enough to keep "Target" above "Ulta Beauty at
 * Target" when both sit in the same retail park — the case that wants it — and
 * far too small to justify sending a Modesto user to an "In-N-Out" 500 km away
 * ahead of an "In-N-Out Burger" 69 km away, as an absolute preference for
 * exact names would.
 */
private const val EXACT_NAME_BONUS = 0.3

/**
 * Rank [rows] for a user looking at [focusLat]/[focusLng] from a device whose
 * region is [homeCountry].
 *
 * Both context signals are optional and degrade independently: with no focus
 * point the distance term drops out (the create-place and destination dialogs
 * have no map on screen), and with no region the country bonus does. With
 * neither, this is the plain population order the index already returns.
 *
 * The sort is stable, so rows that score identically keep the order they came
 * in — which is population, then tier, from `CityIndex::search`.
 */
internal fun rankCities(
    rows: List<CityResult>,
    focusLat: Double?,
    focusLng: Double?,
    homeCountry: String?,
): List<CityResult> {
    if (rows.size < 2) return rows
    val home = homeCountry?.takeIf { it.isNotBlank() }
    return rows.sortedByDescending { row ->
        // population 0 is common in the long-tail tiers; +1 keeps log10 finite.
        var score = log10(row.population.coerceAtLeast(0L).toDouble() + 1.0)
        if (focusLat != null && focusLng != null) {
            val km = haversineKm(row.lat, row.lng, focusLat, focusLng)
            // +1 so standing on top of a place is a zero penalty, not -infinity.
            score -= DISTANCE_WEIGHT * log10(1.0 + km)
        }
        if (home != null && row.countryCode.equals(home, ignoreCase = true)) {
            score += HOME_COUNTRY_BONUS
        }
        score
    }
}

/**
 * Rank concrete places — venues and street addresses, as returned by the
 * server-side geocoder — with the same policy as [rankCities], minus the
 * prominence term.
 *
 * A coffee shop has no population, and the server's rows carry none.
 * `confidence` cannot stand in for it (the gateway returns 1.00 for
 * essentially every row), and neither can the gateway's own rank order, which
 * is provably insufficient: "birmingham" (US one ranked 2nd, 3 109 km) and
 * "paris" (US one ranked 2nd, 2 333 km) have the identical shape but need
 * opposite winners, and only the real population gap (Birmingham UK is 5x
 * Birmingham AL; Paris FR is 86x Paris TX) separates them. No non-negative
 * weights satisfy both.
 *
 * So prominence is left to the channel that can actually price it — the city
 * index, which has population — and this ranks what remains by the two signals
 * that genuinely apply to a specific building: how far it is, and whether it
 * is in the user's country.
 */
internal fun rankPlaces(
    rows: List<GeocodingResult>,
    focusLat: Double?,
    focusLng: Double?,
    homeCountry: String?,
    query: String = "",
): List<GeocodingResult> {
    if (rows.size < 2) return rows
    val home = homeCountry?.takeIf { it.isNotBlank() }
    // The geocoder labels rows with alpha-3 ("USA") while the device region is
    // alpha-2 ("US"). Resolve the device's alpha-3 properly rather than
    // prefix-matching: "CHN".startsWith("CH") is true, so a Swiss user would
    // otherwise count every Chinese row as home.
    val homeAlpha3 = home?.let { runCatching { Locale.Builder().setRegion(it).build().isO3Country }.getOrNull() }
    val queryTokens = normalizeTokens(query)

    // Match quality FIRST, then geography — lexicographic, not blended.
    //
    // Distance alone is not enough: searching "pinks hot dogs" from Modesto,
    // the nearest rows the gateway returns are "Mark's Hot Dogs" (80 km) and
    // "Casper's Hot Dogs" (82 km), which would both outrank every actual
    // Pink's at ~420 km. Name match is the prominence analogue for a venue,
    // so it gates the comparison and distance only orders within a tier.
    return rows.sortedWith(
        // The GATE is "does the name answer the query at all", not "how
        // exactly". An exact name and a name that merely CONTAINS the query
        // both answer it, so they compete on geography; only partial and
        // non-matches are held below, which is what stops "Mark's Hot Dogs"
        // outranking a Pink's.
        compareBy<GeocodingResult> { matchGroup(queryTokens, it.name ?: it.text) }
            .thenByDescending { row ->
                var score = 0.0
                if (focusLat != null && focusLng != null) {
                    score -= DISTANCE_WEIGHT * log10(1.0 + haversineKm(row.lat, row.lng, focusLat, focusLng))
                }
                val cc = row.country?.takeIf { it.isNotBlank() }
                if (cc != null && (cc.equals(home, true) || cc.equals(homeAlpha3, true))) {
                    score += HOME_COUNTRY_BONUS
                }
                if (isExactName(queryTokens, row.name ?: row.text)) {
                    score += EXACT_NAME_BONUS
                }
                score
            }
    ).dropSameAddressDuplicates()
}

/**
 * Collapse POI rows that name the same place at the same street address.
 *
 * The source really does carry duplicates: searching "pinks hot dogs" returns
 * two records for "201 Universal City Plaza" (and the data has other pairs like
 * "1065 Brea Mall"). The only thing separating such a pair is the neighbourhood,
 * and a row that shows its street drops the neighbourhood — the two don't fit
 * side by side — so without this collapse the user sees two identical lines.
 *
 * Runs AFTER the sort so the survivor is the best-ranked one (nearest, for a
 * given match tier) rather than whichever the gateway happened to list first.
 *
 * Deliberately narrow: it requires the same name AND the same non-blank street.
 * Rows with no street are never collapsed, because there the address is not
 * evidence of sameness — two genuinely different outlets in one city would both
 * have a blank street and merging them would hide one.
 */
private fun List<GeocodingResult>.dropSameAddressDuplicates(): List<GeocodingResult> {
    if (size < 2) return this
    val seen = HashSet<String>(size)
    return filter { row ->
        val street = row.address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val name = row.name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (row.resultType != "poi" || street == null || name == null) {
            true
        } else {
            seen.add("$name|$street")
        }
    }
}

/**
 * Split to comparable tokens. Apostrophes are DELETED rather than turned into
 * separators, so "Pink's" becomes `pinks` and matches a user who typed
 * "pinks" — split on the apostrophe instead and it becomes `pink` + `s`,
 * which matches nothing and silently drops the exact hit to the bottom tier.
 */
private fun normalizeTokens(s: String): List<String> =
    s.lowercase()
        .replace("'", "")
        .replace("’", "")
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }

/**
 * How well a row's name answers the query. Lower is better:
 *   0 — the name IS the query ("pinks hot dogs" → "Pink's Hot Dogs")
 *   1 — the name contains every query token
 *   2 — it contains some of them
 *   3 — none, or nothing to compare
 *
 * With an empty query every row lands in the same tier, so ranking degrades
 * to pure geography rather than to an arbitrary order.
 */
private fun matchTier(queryTokens: List<String>, name: String?): Int {
    if (queryTokens.isEmpty()) return 0
    val nameTokens = normalizeTokens(name.orEmpty())
    if (nameTokens.isEmpty()) return 3
    if (nameTokens == queryTokens) return 0
    val nameSet = nameTokens.toSet()
    val hits = queryTokens.count { it in nameSet }
    return when {
        hits == queryTokens.size -> 1
        hits > 0 -> 2
        else -> 3
    }
}

/**
 * Coarser than [matchTier], and it is the coarseness that matters: tiers 0 and
 * 1 both mean "the name contains everything the user typed", so they belong in
 * ONE group that competes on distance.
 *
 * Keeping them apart would let an exact name beat a fuller one at any
 * distance: searching "in n out" from Modesto would rank an "In-N-Out" in
 * Crane, Missouri — 2,419 km away — above every "In-N-Out Burger" in
 * California, including one 69 km away, because the shorter name happens to
 * equal the query exactly.
 */
private fun matchGroup(queryTokens: List<String>, name: String?): Int =
    when (matchTier(queryTokens, name)) {
        0, 1 -> 0   // the name answers the query in full
        2 -> 1      // partial
        else -> 2   // no overlap
    }

/** True when the name is exactly what was typed, no extra words. */
private fun isExactName(queryTokens: List<String>, name: String?): Boolean =
    queryTokens.isNotEmpty() && normalizeTokens(name.orEmpty()) == queryTokens

/** Great-circle distance in km. Shared by the city and address rankers. */
internal fun haversineKm(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val lat1 = Math.toRadians(aLat)
    val lat2 = Math.toRadians(bLat)
    val a = Math.sin(dLat / 2).let { it * it } +
        Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2).let { it * it }
    return 2 * r * Math.asin(minOf(1.0, Math.sqrt(a)))
}

/**
 * The rows of a server answer in the order the list shows them.
 *
 * When the gateway ordered them itself ([ordered]; see
 * [com.pingolin.track.data.remote.ServerGeocoder.isOrdered]) that order is
 * final and nothing here touches it. For searches for famous places from
 * far away, the gateway's ranked route puts the place first far more often
 * than Pelias's own order does, or [rankPlaces] applied to it. The gateway
 * knows what a row cannot say: that "Tour Eiffel" answers "eiffel tower"
 * through its English name, that it is a landmark, that a row was only
 * reached by allowing a typo.
 *
 * Without the mark the rows are in Pelias's own order and [rankPlaces]
 * applies.
 */
internal fun arrangeServedPlaces(
    rows: List<GeocodingResult>,
    ordered: Boolean,
    focusLat: Double?,
    focusLng: Double?,
    homeCountry: String?,
    query: String,
): List<GeocodingResult> =
    if (ordered) rows else rankPlaces(rows, focusLat, focusLng, homeCountry, query)
