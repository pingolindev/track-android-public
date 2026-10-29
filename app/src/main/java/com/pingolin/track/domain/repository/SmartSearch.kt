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

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.pingolin_mobile.CityResult
import uniffi.pingolin_mobile.GeocodingResult

/**
 * Smart-fallback address search shared by [com.pingolin.track.ui.places.PlacesFragment]
 * and [com.pingolin.track.ui.map.MapAddressSearchSheet]. The flow is:
 *
 *   1. Run plain forward search ([GeocodingRepository.search]) AND the
 *      cities-tier index ([GeocodingRepository.searchCities]) in
 *      parallel against tiles already on disk + the cities database.
 *   2. If either yields hits, return them directly.
 *   3. If both empty, peel a city/state hint out of the query (commas
 *      OR trailing whitespace tokens), resolve via cities-tier,
 *      surgically download the per-city addr+POI shard's tile_ids
 *      (or fall back to centroid + 8 geohash neighbors), then
 *      re-search the address remainder against the freshly-loaded
 *      tile(s). Return the first candidate that produces results.
 *
 * The helper is UI-free — callers wire results back to their own
 * state. A `progress` callback fires with the resolved scope city
 * while we're loading tiles so the UI can show a "Loading addresses
 * for Austin, TX…" line.
 *
 * Cancellation: callers should run this inside a coroutine they own
 * and cancel the parent Job when starting a newer search; ongoing
 * geocoding I/O cooperates with the standard kotlinx.coroutines
 * cancellation mechanism. Every repository call below goes through
 * [catching], NOT `runCatching` — see the note there.
 */
data class SmartSearchScope(
    val name: String,
    val admin1: String,
    val countryCode: String,
)

/**
 * `runCatching` that stays cancellation-transparent.
 *
 * The stdlib's `runCatching` catches `Throwable`, which includes the
 * `CancellationException` a cancelled coroutine throws out of its next
 * suspension point. Swallowing that turns "the caller went away" into
 * "that call returned nothing" and the function runs on to completion —
 * firing `onProgress` and returning a result to a caller whose views
 * are already torn down (in the map search sheet, an NPE on a
 * `_binding!!` read from the progress callback).
 *
 * Rethrow cancellation, keep swallowing genuine I/O failures.
 */
private inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * The slice of the world a search is confined to — the map viewport the
 * user can actually see.
 *
 * Built by [searchAreaFromViewport], which pads the raw bounds a little
 * (a hit just past the edge of the screen is still "here") and floors the
 * span so a street-level zoom searches its neighbourhood instead of one
 * block.
 */
data class SearchArea(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    /** True when `(lat, lng)` falls inside the box, antimeridian included. */
    fun contains(lat: Double, lng: Double): Boolean {
        if (lat < south || lat > north) return false
        // west > east means the box wraps past ±180 (e.g. a map over Fiji).
        // The inside is then the UNION of [west, 180] and [-180, east] —
        // a plain `in west..east` would test the gap and exclude everything.
        return if (west <= east) lng in west..east else lng >= west || lng <= east
    }
}

/** Slack added to each side of the viewport, as a fraction of its half-span. */
private const val AREA_PAD_FRACTION = 0.25

/**
 * Floor on the search box's half-span. Without it, a user zoomed to a
 * single street searching "starbucks" would be asking about ~200 m of road
 * and get nothing; a couple of km reads as "around here".
 */
private const val AREA_MIN_HALF_SPAN_KM = 2.0

private const val KM_PER_DEGREE_LAT = 111.32

/**
 * Build the [SearchArea] for a map viewport. Inputs are the raw visible
 * bounds; `east < west` is accepted and means the view crosses ±180.
 */
fun searchAreaFromViewport(
    south: Double,
    west: Double,
    north: Double,
    east: Double,
): SearchArea {
    val latCenter = (south + north) / 2
    // Normalize an antimeridian-crossing span to a positive width before
    // halving it, otherwise the centre lands on the far side of the planet.
    val lngSpan = (east - west).let { if (it < 0) it + 360.0 else it }
    val lngCenter = normalizeLng(west + lngSpan / 2)

    var latHalf = ((north - south) / 2) * (1 + AREA_PAD_FRACTION)
    var lngHalf = (lngSpan / 2) * (1 + AREA_PAD_FRACTION)

    // A degree of longitude narrows towards the poles; clamp the cosine so
    // a polar viewport doesn't divide by ~0 and produce an infinite box.
    val kmPerDegreeLng =
        (KM_PER_DEGREE_LAT * Math.cos(Math.toRadians(latCenter))).coerceAtLeast(0.001)
    latHalf = maxOf(latHalf, AREA_MIN_HALF_SPAN_KM / KM_PER_DEGREE_LAT)
    lngHalf = maxOf(lngHalf, AREA_MIN_HALF_SPAN_KM / kmPerDegreeLng)

    val southEdge = (latCenter - latHalf).coerceAtLeast(-90.0)
    val northEdge = (latCenter + latHalf).coerceAtMost(90.0)
    // Once the box spans every meridian, wrapping it would make west > east
    // and read as an antimeridian box that excludes the middle of the world.
    return if (lngHalf >= 180.0) {
        SearchArea(south = southEdge, west = -180.0, north = northEdge, east = 180.0)
    } else {
        SearchArea(
            south = southEdge,
            west = normalizeLng(lngCenter - lngHalf),
            north = northEdge,
            east = normalizeLng(lngCenter + lngHalf),
        )
    }
}

private fun normalizeLng(lng: Double): Double {
    var l = lng
    while (l > 180.0) l -= 360.0
    while (l < -180.0) l += 360.0
    return l
}

data class SmartSearchResult(
    /** Address/POI hits — from direct search or, if that was empty,
     *  from the resolved scoped search. Empty when neither path
     *  matched. */
    val addresses: List<GeocodingResult>,
    /** City-index hits, only when the direct path produced them.
     *  The scoped fallback never surfaces cities because tapping the
     *  scoping city would drop a pin at the centroid instead of the
     *  typed street address. */
    val cities: List<CityResult>,
    /** When the scoped fallback resolved a city, this is filled in
     *  even if `addresses` is empty — lets the UI show a scope-aware
     *  empty state ("Couldn't find that address in <city>"). */
    val scopedTo: SmartSearchScope?,
)

private val EMPTY = SmartSearchResult(
    addresses = emptyList(),
    cities = emptyList(),
    scopedTo = null,
)

/**
 * One attempt at "resolve this city, download its tiles, search the rest".
 *
 * [isPositionalGuess] marks a hint that was GUESSED from token position rather
 * than marked by the user — the second-to-last token of a whitespace query,
 * taken on the theory that the shape is "<street> <city> <state>". When that
 * theory is wrong the token is just a word from the middle of the query, and
 * resolving it anyway would head "pinks hot dogs" with "In Hohhot, 20".
 * Weak candidates must clear a higher bar — see [cityCorroborates].
 */
internal data class SmartCandidate(
    val hint: String,
    val address: String,
    val isPositionalGuess: Boolean = false,
)

/**
 * Does [city] actually justify the hint that produced it?
 *
 * Only positional guesses are questioned. A hint the user comma-delimited ("350
 * 5th ave, nyc") or one in the trailing "<thing> <city>" slot ("starbucks nyc")
 * is taken at face value — those are real, common inputs that resolve through
 * `alt_names` and would break under any stricter rule.
 *
 * THE BAR FOR A GUESS: the city's own name must BE the hint. Not start with it,
 * not contain it.
 *
 * A prefix test is not enough. "pinks hot dogs" makes the guess "hot", and
 * "hot" is a legitimate prefix of the real city **Hot Springs** — so
 * corroborating against the index alone can never reject it. Nothing in the
 * cities data can tell you "hot" is a hot dog rather than a town; the only
 * usable signal is that a bare one-word city hint should NAME a city outright.
 *
 * Against the tier-1 index this rejects "hot" and "dogs" and keeps springfield,
 * austin, modesto, delhi and ely — which is the whole point of the bare-hint
 * rescue, because "1 main st springfield illinois" has a full state NAME that
 * does not index (only the code does).
 */
internal fun cityCorroborates(
    city: uniffi.pingolin_mobile.CityResult,
    candidate: SmartCandidate,
): Boolean {
    if (!candidate.isPositionalGuess) return true
    val hint = candidate.hint.trim()
    return city.name.equals(hint, ignoreCase = true) ||
        city.asciiName.equals(hint, ignoreCase = true)
}

/**
 * [query] with the comma an IME types in CJK text, the ideographic "、" (U+3001), the fullwidth "，" (U+FF0C)
 * or the halfwidth ideographic "､" (U+FF64), read as the ASCII "," every comma read here splits on:
 * "東京タワー、東京" peels its city hint as "Tokyo Tower, Tokyo" does. The iOS app reads them too.
 */
internal fun normalizeQueryCommas(query: String): String =
    query.replace('\u3001', ',').replace('\uFF0C', ',').replace('\uFF64', ',')

/**
 * Build the candidate list for the smart-fallback search. Comma form is
 * most-specific first ("austin tx") then bare "austin" so a query that misses on
 * a state-coded hint can still resolve. Whitespace form mirrors the comma path's
 * two-step for 3+ tokens; the bare-"<city>" step is critical because alt_names
 * doesn't always carry the state code — and because a full state NAME does not
 * index at all, so "1 main st springfield illinois" resolves ONLY via that step.
 * The 2-token form is just "starbucks austin".
 */
internal fun buildSmartCandidates(rawQuery: String): List<SmartCandidate> = buildList {
    val query = normalizeQueryCommas(rawQuery)
    // Drop trailing postcode-shaped tokens from a hint string. Postcodes
    // never index against any cities-tier column, so a hint like
    // "Hickman CA 95323" AND'd against name|ascii_name|alt_names|admin1
    // |country_code can never satisfy the "95323" leg and the whole
    // hint misses. Returns the hint without trailing postcode tokens
    // (US ZIPs, ZIP+4, etc.) so we can also try a clean state-hinted
    // form of the same query.
    val postcodeRegex = Regex("""^[0-9]{3,}(?:-[0-9]+)?$""")
    fun stripTrailingPostcode(hint: String): String {
        val tokens = hint.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        while (tokens.isNotEmpty() && postcodeRegex.matches(tokens.last())) {
            tokens.removeAt(tokens.size - 1)
        }
        return tokens.joinToString(" ")
    }
    fun MutableList<SmartCandidate>.pushIfNew(
        hint: String,
        address: String,
        positionalGuess: Boolean = false,
    ) {
        if (hint.isBlank() || address.isBlank()) return
        if (any { it.hint == hint && it.address == address }) return
        add(SmartCandidate(hint, address, positionalGuess))
    }

    if (query.contains(",")) {
        val parts = query.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val address = parts[0]
            if (parts.size >= 3) {
                // Most-specific first: "Hickman CA 95323"
                val fullHint = parts.drop(1).joinToString(" ")
                pushIfNew(fullHint, address)
                // ZIP-stripped variant: "Hickman CA". Critical because
                // the postcode doesn't match any cities-tier column,
                // so the full hint misses; without this step the bare-
                // "<city>" fallback below loses to population sort
                // ("Hickman" → KY 2202 wins over CA 641).
                val zipStripped = stripTrailingPostcode(fullHint)
                pushIfNew(zipStripped, address)
            }
            // Bare "<city>"
            pushIfNew(parts[1], address)
        }
    } else {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size >= 2) {
            if (tokens.size >= 3) {
                val addr = tokens.dropLast(2).joinToString(" ")
                val fullHint = tokens.takeLast(2).joinToString(" ")
                pushIfNew(fullHint, addr)
                val zipStripped = stripTrailingPostcode(fullHint)
                pushIfNew(zipStripped, addr)
                // The only positional guess we make. Everything else is either
                // comma-delimited by the user or sits in the trailing
                // "<thing> <city>" position.
                pushIfNew(tokens[tokens.size - 2], addr, positionalGuess = true)
            } else {
                pushIfNew(tokens.last(), tokens.dropLast(1).joinToString(" "))
            }
        }
    }
}

suspend fun GeocodingRepository.smartSearch(
    query: String,
    limit: Int = 8,
    focusLat: Double? = null,
    focusLng: Double? = null,
    allowMeteredTileLoad: Boolean = true,
    cityWide: Boolean = false,
    area: SearchArea? = null,
    onProgress: ((SmartSearchScope?) -> Unit)? = null,
): SmartSearchResult {
    // Every comma read below (the candidates, the city-wide and on-screen paths, the postcode) reads an IME's
    // comma as ",".
    val q = normalizeQueryCommas(query).trim()
    if (q.isEmpty()) return EMPTY

    val hasFocus = focusLat != null && focusLng != null

    // Expand-to-city: explicitly broaden to the focused city's whole tile set
    // (the "Search all of <City>" control). Resolves the city at the map center
    // and loads every tile it occupies, so a POI/address anywhere in the city is
    // searchable — not just the cell under the cursor.
    if (cityWide && hasFocus) {
        return cityWideSearch(q, limit, focusLat!!, focusLng!!, onProgress)
    }
    // When we can proximity-rank (a map-center focus point is known), over-fetch
    // so the nearest match isn't dropped by the geocoder's confidence /
    // iteration-order truncation before we rank it.
    //
    // 2x is headroom for the rows the viewport clamp and the dedup discard. It can
    // stay this low only because forward_near() orders each tile by distance from
    // the focus point, so the nearest rows arrive at the front. If that ordering
    // ever goes away, `fetch` becomes an ARBITRARY slice of the matches and the
    // multiplier has to grow again to compensate.
    val fetch = if (hasFocus) limit * 2 else limit
    val direct = catching { searchWithSource(q, fetch, focusLat, focusLng) }
        .getOrDefault(GeocodingRepository.SearchOutcome(emptyList(), fromServer = false))
    val cached = direct.rows
    val cities = catching { searchCities(q, limit = 6, focusLat = focusLat, focusLng = focusLng) }
        .getOrDefault(emptyList())

    // Viewport clamp. search() is global over EVERY tile on disk, so without this
    // an unqualified query matches same-named streets and POIs anywhere the user
    // has ever looked — "starbucks" on a Sacramento map surfacing a Tokyo hit some
    // earlier search cached, merely sorted further down. Google Maps semantics
    // instead: what's on screen wins, and you reach anywhere else by NAMING it.
    // Naming still works precisely because clamping to empty falls through to the
    // candidate loop below, which resolves the typed city and is deliberately NOT
    // clamped.
    // Clamp the TILE cache, never a deliberate global lookup. Tile rows are
    // whatever this device happens to have cached — a city visited months ago
    // leaks in, which is what the clamp is for. Server rows were fetched for THIS
    // query against a planet index and are already relevance-ranked, so clamping
    // them to the screen just deletes the answer: "pinks hot dogs" searched from
    // Modesto gets 20 correct LA venues from the server, and a clamp would drop
    // all 20, leaving the user with stale tile junk and no way to tell.
    val clamped = area != null && !direct.fromServer
    val onScreen = if (clamped) cached.filter { area.contains(it.lat, it.lng) } else cached

    if (onScreen.isNotEmpty()) {
        // Rows the gateway ordered itself were asked for `fetch` at a time and
        // are shown as they came; everything else is ranked here.
        val ranked = arrangeServedPlaces(
            onScreen, direct.serverOrdered, focusLat, focusLng, deviceCountry(), q,
        ).take(limit)
        return SmartSearchResult(
            addresses = ranked,
            cities = cities,
            // Attribute hits served from the current map tile/city to
            // "this area" rather than the generic "Cached areas". This
            // is also what keeps the label stable: once the current-area
            // tile is warm, the debounced re-search comes back through
            // this direct path, and a nearest hit close to the map
            // center still reads as the current area instead of
            // reverting to "Cached areas".
            //
            // "Every hit is on screen by construction" holds only when
            // the clamp actually RAN. Server rows deliberately skip it —
            // they are global and clamping deletes the answer — so
            // keying the label on `area != null` alone would put a store
            // hundreds of miles away under the heading "In this area".
            // When the rows were not clamped, let the distance test
            // decide.
            scopedTo = if (clamped) {
                nearbyAreaScope()
            } else {
                currentAreaScope(ranked, focusLat, focusLng)
            },
        )
    }

    // No already-cached address/POI hit, but the global cities index matched. For
    // a one-word query that match is often a DISTANT same-name city (e.g.
    // "firestone" → Firestone, CO) — buildSmartCandidates() below can't peel a
    // city hint off a single token, so without this we'd return only that city
    // and never look where the user is actually pointed. Probe the focused
    // map-area tile for a same-name POI first and surface those above the city.
    // Gated to a known focus point + unmetered (the single precision-4 tile can
    // be tens of MB); when nothing local turns up we fall back to the city alone.
    if (cities.isNotEmpty()) {
        // Skip the probe entirely when the server already answered: it exists
        // ONLY to download the precision-4 tile under the map centre (which the
        // code below calls tens of MB) and search that. The planet index has
        // already covered that ground, so this would be megabytes spent to
        // re-ask a question that has been answered.
        if (hasFocus && allowMeteredTileLoad && !direct.serverConclusive) {
            val near = probeFocusAreaForPoi(q, focusLat!!, focusLng!!, limit, area, onProgress)
            if (!near.isNullOrEmpty()) {
                return SmartSearchResult(
                    addresses = near,
                    cities = cities,
                    scopedTo = nearbyAreaScope(),
                )
            }
        }
        return SmartSearchResult(
            addresses = emptyList(),
            cities = cities,
            scopedTo = null,
        )
    }

    val candidates = buildSmartCandidates(q)
    var triedScope: SmartSearchScope? = null

    // NOTE: nothing below this point is viewport-clamped. Reaching here means
    // the query named somewhere the map isn't looking, which is exactly the
    // "unless a different city/state is stated" escape hatch.
    for (candidate in candidates) {
        val hint = candidate.hint
        val addressPart = candidate.address
        if (hint.isBlank() || addressPart.isBlank()) continue
        // Focus-aware: the top match here decides WHICH city's tiles get
        // downloaded, so "main street, springfield" has to resolve to the
        // Springfield the user is near, not the most populous one on earth.
        val matches = catching { searchCities(hint, limit = 3, focusLat = focusLat, focusLng = focusLng) }
            .getOrDefault(emptyList())
        if (matches.isEmpty()) continue
        val top = matches.first()
        if (top.tileId.isBlank()) continue
        // A positional guess has to be corroborated before we spend a tile
        // download and a header on it.
        if (!cityCorroborates(top, candidate)) continue

        val scope = SmartSearchScope(
            name = top.name,
            admin1 = top.admin1,
            countryCode = top.countryCode,
        )
        onProgress?.invoke(scope)

        // The town's own cell always (loadTownCell), then
        // three tile-loading strategies in priority order:
        //   1. Per-city addr+POI shard — surgical, when it exists.
        //   2. Pre-computed city tile_ids — the build pipeline writes
        //      the set of tiles overlapping the city's extent. Avoids
        //      the blunt centroid+8 neighbors load when the shard isn't
        //      there. Falls through if older cities-tier rebuilds don't
        //      have the column populated. It leaves out split cells.
        //   3. Centroid + 8 geohash neighbors — legacy last resort, when not
        //      even the town's own cell loaded.
        // Skip the shard fetch when the cities row says one was never
        // built — saves a CDN round-trip + 404 for small cities.
        // Same reasoning: these branches exist to DOWNLOAD a city's tiles so the
        // remainder can be searched locally. If the server already answered for
        // this query, that download buys nothing — the re-search below still
        // runs (it asks a different question, the address part alone), it just
        // asks it without first pulling a city's worth of tiles.
        // Cells the results may be filtered to. Null means "loaded, but we do not
        // know which cells" — the neighbours blunderbuss — and the caller must
        // then NOT filter, because it has no cell list to test against.
        var loadedCells: Set<String>? = null
        // The town's own cell, always, beside the shard's or the index's tiles: the index leaves
        // out a split cell, so without it "1 Infinite Loop, Cupertino", in 9q9hr, is never
        // searched. The eight around it come below, on a miss.
        var townCell: String? = null
        if (!direct.serverConclusive) {
            townCell = loadTownCell(top.lat, top.lng, top.tileId)
            val townCells = setOfNotNull(townCell)
            val shardTiles = if (top.hasShard) {
                catching {
                    lookupCityShardTiles(top.geonameId, addressPart)
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            if (shardTiles.isNotEmpty()) {
                for (tileId in shardTiles) {
                    catching { ensureTileLoadedById(tileId) }
                }
                // Union with tile_ids: the shard is per-city, so its tiles are
                // in-city by construction even when the pipeline's tile_ids list
                // does not happen to contain them. Filtering against tile_ids
                // alone would throw away the very rows we just fetched.
                loadedCells = townCells + shardTiles.toSet() + top.tileIds.toSet()
            } else if (top.tileIds.isNotEmpty()) {
                for (tileId in top.tileIds) {
                    catching { ensureTileLoadedById(tileId) }
                }
                loadedCells = townCells + top.tileIds.toSet()
            } else if (townCells.isNotEmpty()) {
                loadedCells = townCells
            } else {
                catching { ensureTileAndNeighborsLoaded(top.lat, top.lng) }
            }
        } else {
            // The server already answered; nothing was downloaded, so the only
            // cells we can claim are the city's own: its tile_ids and, as they
            // leave out a split cell, the precision-4 cell under its point and
            // the eight around it, whatever the manifest holds there (a
            // prefix).
            val own = geohash4(top.lat, top.lng)
            loadedCells = top.tileIds.toSet() + own + geohashNeighbours(own)
        }

        // Fetch more than the user-facing limit so the post-search ranker has room
        // to reorder, and as many as the area search asks for, since the town's own
        // cells are searched too: search() is global over every loaded tile and
        // cuts by confidence before the filter below, so a chain's stores elsewhere
        // could fill a short list ahead of the town's.
        var hits = catching {
            search(addressPart, TOWN_ROWS, top.lat, top.lng)
        }.getOrDefault(emptyList())

        // RESTRICT TO THE CITY'S OWN CELLS. search() is global over every loaded
        // tile, so without this the rows under an "In <city>" header can come from
        // anywhere the device happens to have cached — the header and the rows
        // would disagree, and the header is the only thing telling the user where
        // we looked. cityWideSearch applies the same restriction.
        //
        // Empty is then a truthful "not in this city" rather than cross-region
        // noise. A centroid-only city whose own cell didn't load either (the
        // neighbour blunderbuss loaded the tiles) has no cell list to test against
        // and stays unfiltered. The cells are tile ids at any precision, so the
        // test is a prefix one: a split cell's child never equals a geohash-4.
        val cells = loadedCells
        var scoped = if (cells.isNullOrEmpty()) {
            hits
        } else {
            hits.filter { inCells(it.lat, it.lng, cells) }
        }
        // A miss in the town's own cell and its tiles: the eight cells around its own,
        // within NEIGHBOUR_WINDOW_MS, on any network. 21810 Stevens Creek Blvd is in
        // 9q9hq, beside Cupertino's 9q9hr, and a phone searches on cellular as often as
        // not; the extra tiles are spent only when the town's own found nothing.
        val own = townCell
        if (scoped.isEmpty() && own != null) {
            val around = loadTownNeighbours(own, top.lat, top.lng)
            if (around.isNotEmpty()) {
                val wider = (cells ?: emptySet()) + around
                hits = catching {
                    search(addressPart, TOWN_ROWS, top.lat, top.lng)
                }.getOrDefault(emptyList())
                scoped = hits.filter { inCells(it.lat, it.lng, wider) }
            }
        }
        if (scoped.isNotEmpty()) {
            val ranked = rankScopedResults(scoped, q, top.lat, top.lng)
                .take(limit)
            onProgress?.invoke(null)
            return SmartSearchResult(
                addresses = ranked,
                cities = emptyList(),
                scopedTo = scope,
            )
        }
        triedScope = scope
    }

    // Default the scope to the current city/viewport. Reached only when the
    // direct search and any typed-city candidates produced nothing AND no typed
    // city resolved (triedScope == null), so we never override a city the user
    // explicitly typed. Loads exactly ONE tile (the precision-4 cell under the
    // map center covers the whole city), gated to unmetered networks because
    // that single tile can be tens of MB in a dense metro.
    if (hasFocus && allowMeteredTileLoad && triedScope == null && !direct.serverConclusive) {
        val near = probeFocusAreaForPoi(q, focusLat!!, focusLng!!, limit, area, onProgress)
        if (near != null) {
            // near != null means the tile actually loaded — so an empty result is
            // a real "looked, found nothing" and earns the scope-aware empty state.
            if (near.isNotEmpty()) {
                return SmartSearchResult(
                    addresses = near,
                    cities = emptyList(),
                    scopedTo = nearbyAreaScope(),
                )
            }
            triedScope = nearbyAreaScope()
        }
    }

    onProgress?.invoke(null)
    return SmartSearchResult(
        addresses = emptyList(),
        cities = emptyList(),
        scopedTo = triedScope,
    )
}

/**
 * Expand-to-city: resolve the city at (focusLat, focusLng) and load its entire
 * tile set, then search across them. Precise path = reverse-geocode the center →
 * city name → cities-tier `tile_ids` (the cells the city occupies). Fallback
 * when no city resolves (rural/unincorporated) = the focus cell + 8 neighbors,
 * which over-covers any locality. Reuses the existing tile-load primitives, so
 * the enriched POI tiles ride along (ensureTileLoadedById loads the pois/ tile).
 * The city's own cell is loaded too, and on a miss the eight around it, as the
 * comma path loads them (loadTownCell, loadTownNeighbours): the tile_ids leave
 * out a split cell, so without it "all of Cupertino" would never search 9q9h,
 * the cell the town is in.
 */
private suspend fun GeocodingRepository.cityWideSearch(
    q: String,
    limit: Int,
    focusLat: Double,
    focusLng: Double,
    onProgress: ((SmartSearchScope?) -> Unit)?,
): SmartSearchResult {
    var scope: SmartSearchScope? = null
    // The city's own cells, at any precision, which keep results inside the city.
    var scopeTiles: Set<String>? = null
    var townCell: String? = null
    var town: uniffi.pingolin_mobile.CityResult? = null
    val cityName = catching { reverseGeocode(focusLat, focusLng) }
        .getOrNull()?.city?.takeIf { it.isNotBlank() }
    if (cityName != null) {
        val top = catching { searchCities(cityName, limit = 3, focusLat = focusLat, focusLng = focusLng) }
            .getOrDefault(emptyList())
            .firstOrNull()
        if (top != null) {
            val cityScope = SmartSearchScope(top.name, top.admin1, top.countryCode)
            onProgress?.invoke(cityScope)
            town = top
            townCell = loadTownCell(top.lat, top.lng, top.tileId)
            for (tileId in top.tileIds) {
                catching { ensureTileLoadedById(tileId) }
            }
            val cells = setOfNotNull(townCell) + top.tileIds.toSet()
            if (cells.isNotEmpty()) {
                scope = cityScope
                scopeTiles = cells
            }
        }
    }
    if (scope == null) {
        // No resolvable city (rural/unincorporated) — load the focus cell + 8
        // neighbors, which over-covers any locality. Generic "your area" label.
        scope = nearbyAreaScope()
        onProgress?.invoke(scope)
        catching { ensureTileAndNeighborsLoaded(focusLat, focusLng) }
    }
    // Drop a (possibly mistyped) trailing city hint for the local search.
    val local = q.substringBefore(",").trim().ifBlank { q }
    // As many rows as the comma path asks for: the cut is made before the city filter below.
    var hits = catching { search(local, TOWN_ROWS, focusLat, focusLng) }.getOrDefault(emptyList())
    // search() is global over EVERY loaded tile, so a "whole city" search would
    // otherwise surface same-name hits from unrelated regions that happen to be
    // loaded for other map markers (e.g. circle members in another state) under
    // this city's header. Restrict to the city's own cells: a hit is in-city iff
    // it lies in one of the cells we loaded (the tile_ids and the town's own, by
    // a prefix test: a split cell's child is a precision-5 id). Empty is then a
    // truthful "nothing in this city" rather than cross-region noise. The rural
    // neighbor fallback has no tile set, so it keeps proximity ranking
    // unfiltered.
    val tiles = scopeTiles
    var inScope = if (tiles != null) {
        hits.filter { inCells(it.lat, it.lng, tiles) }
    } else {
        hits
    }
    // A miss in the city's own cell and its tiles: the eight around its own, as the comma path widens.
    val own = townCell
    val point = town
    if (inScope.isEmpty() && tiles != null && own != null && point != null) {
        val around = loadTownNeighbours(own, point.lat, point.lng)
        if (around.isNotEmpty()) {
            val wider = tiles + around
            hits = catching { search(local, TOWN_ROWS, focusLat, focusLng) }.getOrDefault(emptyList())
            inScope = hits.filter { inCells(it.lat, it.lng, wider) }
        }
    }
    onProgress?.invoke(null)
    return SmartSearchResult(
        addresses = rankPlaces(inScope, focusLat, focusLng, deviceCountry(), local).take(limit),
        cities = emptyList(),
        scopedTo = scope,
    )
}

private const val GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

/** Encode (lat,lng) to a precision-4 geohash — the on-device tile granularity,
 * for testing whether a result falls inside a scope's tile set (its cells). */
internal fun geohash4(lat: Double, lng: Double): String = geohash(lat, lng, 4)

/**
 * Whether (lat, lng) lies in one of [cells], tile ids at whatever precision the manifest holds them: 4,
 * a split cell's child at 5 (Cupertino's "9q9hr"), a collapsed parent at 3. A point is in a cell when
 * its own geohash begins with the cell's id; an equality test against `geohash4(...)` never matches a
 * precision-5 id, and would throw away rows in a split cell even when that cell is loaded.
 */
internal fun inCells(lat: Double, lng: Double, cells: Set<String>): Boolean {
    val longest = cells.maxOfOrNull { it.length } ?: return false
    val hash = geohash(lat, lng, longest)
    return cells.any { it.isNotEmpty() && hash.startsWith(it) }
}

/** The centre of geohash cell [hash], as (lat, lng). */
internal fun geohashCentre(hash: String): Pair<Double, Double> {
    var latMin = -90.0
    var latMax = 90.0
    var lngMin = -180.0
    var lngMax = 180.0
    var even = true
    for (c in hash) {
        val value = GEOHASH_BASE32.indexOf(c)
        require(value >= 0) { "not a geohash: $hash" }
        for (bit in 4 downTo 0) {
            val on = (value shr bit) and 1 == 1
            if (even) {
                val mid = (lngMin + lngMax) / 2
                if (on) lngMin = mid else lngMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (on) latMin = mid else latMax = mid
            }
            even = !even
        }
    }
    return (latMin + latMax) / 2 to (lngMin + lngMax) / 2
}

/**
 * The eight cells around [hash], at its own precision: its centre moved by one cell's height and width in
 * each direction, across the antimeridian, and clamped at the poles (where fewer than eight are distinct).
 */
internal fun geohashNeighbours(hash: String): List<String> {
    val (lat, lng) = geohashCentre(hash)
    val bits = hash.length * 5
    val latCell = 180.0 / (1L shl (bits / 2))
    val lngCell = 360.0 / (1L shl ((bits + 1) / 2))
    val out = LinkedHashSet<String>()
    for (dLat in -1..1) {
        for (dLng in -1..1) {
            if (dLat == 0 && dLng == 0) continue
            val nLat = (lat + dLat * latCell).coerceIn(-90.0, 90.0)
            var nLng = lng + dLng * lngCell
            if (nLng >= 180.0) nLng -= 360.0
            if (nLng < -180.0) nLng += 360.0
            out += geohash(nLat, nLng, hash.length)
        }
    }
    out -= hash
    return out.toList()
}

/** Encode (lat,lng) to a geohash of [precision] characters. */
internal fun geohash(lat: Double, lng: Double, precision: Int): String {
    var latMin = -90.0
    var latMax = 90.0
    var lngMin = -180.0
    var lngMax = 180.0
    val sb = StringBuilder(precision)
    var even = true
    var bit = 0
    var ch = 0
    while (sb.length < precision) {
        if (even) {
            val mid = (lngMin + lngMax) / 2
            if (lng >= mid) { ch = ch or (1 shl (4 - bit)); lngMin = mid } else { lngMax = mid }
        } else {
            val mid = (latMin + latMax) / 2
            if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else { latMax = mid }
        }
        even = !even
        if (bit < 4) {
            bit++
        } else {
            sb.append(GEOHASH_BASE32[ch])
            bit = 0
            ch = 0
        }
    }
    return sb.toString()
}

/**
 * A town's own cell, loaded, by the id the manifest holds it under, or null. The cities
 * index's tile_ids leave out a SPLIT cell: Cupertino's are "9q95,9q9m", with no child of 9q9h,
 * where the town is; San Francisco's have no child of 9q8y. So the cell is resolved from the
 * town's point as the manifest's own lookup does (a split cell's child, a collapsed parent),
 * else its stored [tileId].
 */
internal suspend fun GeocodingRepository.loadTownCell(lat: Double, lng: Double, tileId: String): String? =
    loadManifestTileAt(lat, lng)
        ?: tileId.takeIf { it.isNotBlank() && catching { ensureTileLoadedById(it) }.getOrDefault(false) }

/**
 * The eight cells around a town's own [cell], loaded, at its own precision and each resolved from its
 * centre through the manifest as the cell was: San Francisco's 9q8yy has 1 Market Street beside it in
 * 9q8zn, Cupertino's 9q9hr has 21810 Stevens Creek Blvd in 9q9hq. Started only within [windowMs], as
 * iOS starts them within its step's budget: each can be tens of MB, and the SDK has no byte budget for
 * them. One that has started runs to its end. Nearest the town's point ([lat], [lng]) first, so a slow
 * network spends its window on the likeliest cells. The ids that loaded.
 */
internal suspend fun GeocodingRepository.loadTownNeighbours(
    cell: String,
    lat: Double,
    lng: Double,
    windowMs: Long = NEIGHBOUR_WINDOW_MS,
): Set<String> {
    val loaded = linkedSetOf<String>()
    val nearestFirst = geohashNeighbours(cell).sortedBy { neighbour ->
        val (cellLat, cellLng) = geohashCentre(neighbour)
        haversineKm(lat, lng, cellLat, cellLng)
    }
    withTimeoutOrNull(windowMs) {
        for (neighbour in nearestFirst) {
            val (lat, lng) = geohashCentre(neighbour)
            loadManifestTileAt(lat, lng)?.let { loaded += it }
        }
    }
    return loaded
}

/**
 * The manifest's tile at (lat, lng), loaded: tried at the base precision (4), then a split cell's
 * child (5), then a collapsed parent (3), the order the SDK's lookup uses. A precision the
 * manifest doesn't hold is a soft miss in ensureTileLoadedById, with nothing downloaded.
 */
private suspend fun GeocodingRepository.loadManifestTileAt(lat: Double, lng: Double): String? {
    for (precision in listOf(4, 5, 3)) {
        val id = geohash(lat, lng, precision)
        if (catching { ensureTileLoadedById(id) }.getOrDefault(false)) return id
    }
    return null
}

/**
 * Probe the focused map area for a POI matching [q]. Loads the single
 * precision-4 tile under the focus point (callers gate this to unmetered
 * networks — one such tile can be tens of MB) and searches it, dropping any
 * trailing comma hint first. Returns:
 *   - `null`  — the tile couldn't be loaded (already resident, or a network
 *               failure) so there's nothing new to search; and
 *   - a list  — the proximity-ranked local hits, possibly empty.
 * The null-vs-empty distinction lets callers tell "didn't look" from "looked,
 * found nothing" (only the latter earns a scope-aware empty state).
 *
 * [area] clamps the hits the same way the direct path is clamped. A
 * precision-4 tile is ~39×19.5 km, so without it this "current area" probe
 * would happily return something 30 km off the side of the screen.
 */
private suspend fun GeocodingRepository.probeFocusAreaForPoi(
    q: String,
    focusLat: Double,
    focusLng: Double,
    limit: Int,
    area: SearchArea?,
    onProgress: ((SmartSearchScope?) -> Unit)?,
): List<GeocodingResult>? {
    onProgress?.invoke(nearbyAreaScope())
    val loaded = catching { ensureTileForCoordsLoaded(focusLat, focusLng) }
        .getOrDefault(false)
    onProgress?.invoke(null)
    if (!loaded) return null
    val local = q.substringBefore(",").trim().ifBlank { q }
    val near = catching { search(local, limit * 3, focusLat, focusLng) }.getOrDefault(emptyList())
    val onScreen = if (area == null) near else near.filter { area.contains(it.lat, it.lng) }
    return rankPlaces(onScreen, focusLat, focusLng, deviceCountry(), local).take(limit)
}

/**
 * The device's region, for [rankPlaces]' home-country bonus.
 *
 * Read here rather than passed in so every ranking site in this file uses the
 * same signal the server path uses — the whole point is that one policy applies
 * regardless of which channel produced the row.
 */
private fun deviceCountry(): String? = java.util.Locale.getDefault().country

// Ranking goes through rankPlaces at every site, so local and server rows get
// the same policy. Sorting purely by distance would undo its match-quality
// tiering: a nearer row whose name answers nothing ("Mark's Hot Dogs" at 80
// km) would climb back over the row the user actually searched for ("Pink's
// Hot Dogs" at 420 km).

/** Radius around the map center treated as "the current area". A precision-4
 *  geocoding tile is ~39×19.5 km, so this comfortably covers the tile/city the
 *  user is looking at while still excluding hits from a genuinely different
 *  cached region. */
private const val IN_AREA_RADIUS_KM = 40.0

/**
 * Rows asked of search() for a town's cells, after a comma or for the whole city. search() is global
 * over every loaded tile and cuts by confidence before the town's filter, so with more of the town's
 * cells searched, a chain's stores elsewhere could fill a short list.
 */
private const val TOWN_ROWS = 100

/**
 * How long a miss in a town's own cell may spend widening to the eight around it (loadTownNeighbours):
 * iOS's window for the same eight, within its step's 20 s budget. Each can be tens of MB, on cellular as
 * often as not, and only the widening on a miss spends it.
 */
private const val NEIGHBOUR_WINDOW_MS = 10_000L

/** The "In this area" scope, used by the current-city fallback and by the
 *  direct path for hits inside the map viewport. Deliberately "this" and not
 *  "your" — the map is wherever the user panned it, which is often nowhere
 *  near them. */
private fun nearbyAreaScope() =
    SmartSearchScope(name = "this area", admin1 = "", countryCode = "")

/** "In your area" when the nearest (already proximity-ranked) hit sits within
 *  the current tile/city of the focus point; null otherwise (no focus, or the
 *  nearest hit is a far cross-region cached result → generic "Cached areas"). */
private fun currentAreaScope(
    rows: List<uniffi.pingolin_mobile.GeocodingResult>,
    lat: Double?,
    lng: Double?,
): SmartSearchScope? {
    if (lat == null || lng == null) return null
    val nearest = rows.firstOrNull() ?: return null
    return if (haversineKm(nearest.lat, nearest.lng, lat, lng) <= IN_AREA_RADIUS_KM) {
        nearbyAreaScope()
    } else {
        null
    }
}

private val POSTCODE_REGEX = Regex("""^\d{3,}(?:-\d+)?$""")

/**
 * Rank scoped search results so the most-relevant address tops the
 * list. Two signals:
 *   1. Exact postcode match — query token like "95323" matched against
 *      the row's postcode column wins regardless of distance.
 *   2. Haversine to scope centroid — closer addresses come first so
 *      ones near the resolved city outrank tile-edge outliers.
 */
private fun rankScopedResults(
    rows: List<uniffi.pingolin_mobile.GeocodingResult>,
    query: String,
    scopeLat: Double,
    scopeLng: Double,
): List<uniffi.pingolin_mobile.GeocodingResult> {
    val queryPostcode = extractPostcode(query)
    return rows.sortedWith(
        compareByDescending<uniffi.pingolin_mobile.GeocodingResult> { row ->
            queryPostcode != null && row.postcode == queryPostcode
        }.thenBy { row ->
            haversineKm(row.lat, row.lng, scopeLat, scopeLng)
        },
    )
}

private fun extractPostcode(query: String): String? {
    for (tok in query.split(Regex("[\\s,]+"))) {
        if (POSTCODE_REGEX.matches(tok)) return tok
    }
    return null
}

// haversineKm lives in CityRanking.kt — shared with the city ranker.
