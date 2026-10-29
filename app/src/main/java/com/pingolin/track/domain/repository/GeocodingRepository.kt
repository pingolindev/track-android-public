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

import com.pingolin.track.util.SecurePrefs
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.pingolin.track.data.remote.ServerGeocoder
import com.pingolin.track.data.remote.dto.GeocodingPreferences
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.GeocodingDownloader
import uniffi.pingolin_mobile.GeocodingResult
import uniffi.pingolin_mobile.TileGeocoder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for offline geocoding functionality.
 *
 * PRIVACY: All geocoding is performed locally using downloaded SQLite databases.
 * The server never learns what locations the user is searching for.
 *
 * Preferences (selected regions) are stored encrypted on device using EncryptedSharedPreferences.
 */
@Singleton
class GeocodingRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gson: Gson,
    private val sessionManager: SessionManager,
    private val serverGeocoder: ServerGeocoder
) {
    companion object {
        private const val TAG = "GeocodingRepository"
        private const val PREFS_NAME = "geocoding_prefs_encrypted"
        private const val KEY_PREFERENCES = "preferences"

        /**
         * Floor on how many city rows to pull out of the index before
         * ranking. The index orders by population only, so this is the
         * headroom [rankCities] needs to promote a nearer / same-country
         * city that population alone would have truncated away.
         */
        private const val MIN_CITY_FETCH = 25

        /**
         * Shortest query that may reach the server geocoder.
         *
         * Three characters is where a query starts to be able to identify
         * something. Below it the gateway returns noise anyway, so the round
         * trip and the anonymous credit buy nothing. Two-letter brands exist
         * ("BP"), which is why this gates the SERVER only — the on-device
         * tiles still answer a one-character query, at no cost.
         */
        private const val MIN_SERVER_QUERY_LEN = 3

        /** Recent searches kept for the backspace-and-retype case. */
        private const val SEARCH_CACHE_MAX = 32

        /**
         * How long a memoized search stays good. Long enough to cover editing a
         * query, short enough that a place added today still shows up in the
         * same session.
         */
        private const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L

        // The CITIES_* URLs below are hardcoded to match the published
        // city index files and the manifest's `cities.base_url`. If
        // those files are ever published under different names, update
        // both sides — the URLs don't read the manifest.
        private const val CITIES_TIER1_URL = "https://geocoding.pingolin.com/cities/cities-tier1.sqlite.zst"
        private const val CITIES_TIER2_URL = "https://geocoding.pingolin.com/cities/cities-tier2.sqlite.zst"
        private const val CITIES_TIER3_URL = "https://geocoding.pingolin.com/cities/cities-tier3.sqlite.zst"
        private const val CITIES_BY_COUNTRY_URL_PREFIX = "https://geocoding.pingolin.com/cities/by-country/"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            SecurePrefs.open(context, PREFS_NAME)
        } catch (e: Exception) {
            // SECURITY: Do NOT fall back to unencrypted storage.
            // Geocoding preferences aren't highly sensitive, but for consistency
            // and defense-in-depth, we require encrypted storage.
            Log.e(TAG, "Failed to create EncryptedSharedPreferences for geocoding", e)
            throw SecurityException(
                "Cannot initialize secure storage for geocoding preferences.",
                e
            )
        }
    }
    private val storageDir: File by lazy {
        File(context.filesDir, "geocoding").also { it.mkdirs() }
    }

    private val downloader: GeocodingDownloader by lazy {
        GeocodingDownloader(storageDir.absolutePath)
    }

    /**
     * Tile-based geocoder for on-demand tile downloads.
     * This is the PRIMARY geocoder - always available without manual region downloads.
     */
    private val tileGeocoder: TileGeocoder by lazy {
        TileGeocoder(File(storageDir, "tiles").absolutePath)
    }

    /**
     * Two-tier global city index used as a privacy-preserving "where is
     * this address?" hint. Tier 1 (~7 MB) is lazy-fetched on the first
     * city search; tier 2 (~5 MB) only after tier 1 misses. The query
     * itself never leaves the device — the only network traffic is the
     * static tier downloads (indistinguishable across users).
     */
    private val cityIndex: uniffi.pingolin_mobile.CityIndex by lazy {
        uniffi.pingolin_mobile.CityIndex(File(storageDir, "cities").absolutePath)
    }


    private val _preferences = MutableStateFlow<GeocodingPreferences?>(null)
    val preferences: StateFlow<GeocodingPreferences?> = _preferences.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()


    /**
     * Result of a forward search, plus WHERE it came from.
     *
     * The source matters to the caller: tile rows are whatever happens to be
     * cached on this device, so they need clamping to the map view or a
     * long-dead cached city leaks into the list. Server rows are a deliberate
     * global lookup — clamping those to the screen throws away exactly what the
     * user turned the feature on for.
     */
    data class SearchOutcome(
        val rows: List<GeocodingResult>,
        val fromServer: Boolean,
        /**
         * The server was consulted and its answer stands — whether that answer
         * was rows or an explicit nothing. Distinct from [fromServer]: a
         * conclusive EMPTY answer still yields local rows here, but tells the
         * caller not to go downloading a city's worth of tiles to re-ask a
         * question the planet index has already answered. False when the server
         * is off or was unreachable, where the full local cascade is right.
         */
        val serverConclusive: Boolean = false,
        /**
         * The rows are the gateway's and it ordered them itself: show them as
         * they came ([arrangeServedPlaces]). False for tile rows and for a
         * gateway answer without the mark.
         */
        val serverOrdered: Boolean = false,
    )

    /**
     * Forward geocoding: search for addresses/places matching the query. Rows only,
     * for callers that don't care about the source.
     *
     * Answers from downloaded regions first (the cleanest dataset, present only when
     * the user explicitly downloaded a region), then from tile-based geocoding, which
     * searches every tile already cached on disk by prior reverse-geocoding — that
     * covers everywhere the user has looked at the map, at no extra bandwidth.
     *
     * PRIVACY: geocoding is local BY DEFAULT — the server never learns what the user
     * is searching for. The one exception is the opt-in, premium-gated server-side
     * geocoder ([SessionManager.getUseServerGeocoder], default false): with it on,
     * the query text is sent to the hosted geocoder over a session-less request
     * carrying no account identifier. Everything else on this path stays on-device.
     */
    suspend fun search(
        query: String,
        limit: Int = 10,
        focusLat: Double? = null,
        focusLng: Double? = null,
    ): List<GeocodingResult> = searchWithSource(query, limit, focusLat, focusLng).rows

    /**
     * Short-lived memo of recent searches.
     *
     * Backspacing is the single most common thing people do in a search box, and
     * without a memo, typing "starbucks", backspacing and retyping re-runs the
     * whole thing — a fresh HTTP round trip and, on premium, another spent credit,
     * for a question just asked.
     *
     * Keyed on the normalized query, the focus point rounded to ~100 m (so
     * camera jitter still hits), the fetch size, and whether the server path
     * was on — flipping the toggle must not serve the other tier's answer.
     */
    private class CachedSearch(val outcome: SearchOutcome, val atElapsedMs: Long)

    private val searchCache = object : LinkedHashMap<String, CachedSearch>(
        /* initialCapacity = */ 16,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>) =
            size > SEARCH_CACHE_MAX
    }

    private fun searchCacheKey(
        query: String,
        limit: Int,
        focusLat: Double?,
        focusLng: Double?,
    ): String {
        // 3 decimals ~= 100 m: fine enough that a real pan misses the cache,
        // coarse enough that the camera drifting a few metres does not.
        fun coord(d: Double?) = d?.let { "%.3f".format(it) } ?: "-"
        return buildString {
            append(query.trim().lowercase()); append('|')
            append(limit); append('|')
            append(coord(focusLat)); append(','); append(coord(focusLng)); append('|')
            append(sessionManager.getUseServerGeocoder())
        }
    }

    private fun cachedSearch(key: String): SearchOutcome? = synchronized(searchCache) {
        val hit = searchCache[key] ?: return null
        val ageMs = android.os.SystemClock.elapsedRealtime() - hit.atElapsedMs
        if (ageMs > SEARCH_CACHE_TTL_MS) {
            searchCache.remove(key)
            return null
        }
        hit.outcome
    }

    private fun putCachedSearch(key: String, outcome: SearchOutcome) = synchronized(searchCache) {
        searchCache[key] = CachedSearch(outcome, android.os.SystemClock.elapsedRealtime())
        Unit
    }

    suspend fun searchWithSource(
        query: String,
        limit: Int = 10,
        focusLat: Double? = null,
        focusLng: Double? = null,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val cacheKey = searchCacheKey(query, limit, focusLat, focusLng)
        cachedSearch(cacheKey)?.let { return@withContext it }

        // Opt-in server path first: it answers in ~100 ms against a full
        // planet index, where the local path may have to download a city's
        // tiles to find anything. Any failure falls through to the tiles
        // below, so this is strictly additive.
        var serverConclusive = false
        // Below the threshold the server is not consulted at all. One or two
        // characters cannot identify a place, but they DO cost a network round
        // trip and an anonymous credit each — and the debounce does not help a
        // slow typist, who pauses after "i" and again after "in". The local
        // tiles still answer, so short input is not dead, just free.
        if (sessionManager.getUseServerGeocoder() && query.trim().length >= MIN_SERVER_QUERY_LEN) {
            val serverHits = serverGeocoder.forward(query, limit, focusLat, focusLng)
            when {
                // COULD NOT answer — no credit, HTTP error, unreachable. This
                // is the ONLY case that reaches the local data while the toggle
                // is on, and it is failover, not a blend: the query has already
                // left the device (or failed to), so answering from tiles costs
                // no additional privacy and beats showing nothing.
                serverHits == null ->
                    Log.w(TAG, "search: server unavailable, falling back to tiles")

                // ANSWERED. Its answer is the answer — including an empty one.
                //
                // The toggle selects a SOURCE, not a preference: on means the
                // gateway's planet index decides, off means nothing leaves the
                // device. Do NOT blend the two — blending makes results depend on
                // which tiles a device happens to have cached, so the same query
                // on two phones in the same street returns different things, and
                // it reintroduces cross-source duplicates (the same Walmart as
                // "2225 Plaza Pkwy" and "2225 Plaza Parkway").
                //
                // Trusting the server outright is only safe while it answers
                // focused queries from /v1/autocomplete. /v1/search cannot see
                // the three In-N-Outs in Modesto at all, and a blend is what a
                // gateway that weak would need to compensate for.
                else -> {
                    val shown = arrangeServedPlaces(
                        serverHits.rows,
                        serverHits.ordered,
                        focusLat,
                        focusLng,
                        java.util.Locale.getDefault().country,
                        query,
                    ).take(limit)
                    if (serverHits.rows.isEmpty()) {
                        Log.w(TAG, "search: server found no matches (authoritative)")
                    }
                    return@withContext SearchOutcome(
                        shown,
                        fromServer = true,
                        serverConclusive = true,
                        serverOrdered = serverHits.ordered,
                    ).also { putCachedSearch(cacheKey, it) }
                }
            }
        }

        // Fold in tile cache hits. Pass the focus point: it makes the SDK order
        // each tile's rows by distance, so `limit` returns the NEAREST matches
        // instead of an arbitrary slice the viewport clamp then discards. That
        // slice is what makes "starbucks" from Modesto answer with Minnesota.
        val tileHits = try {
            tileGeocoder.forwardNear(query, limit.toUInt(), focusLat, focusLng)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "Tile forward search failed: ${e.message}")
            emptyList()
        }

        // De-dup by (name, rounded coords): tiles overlap at cell boundaries, so
        // the same venue can arrive from two of them.
        val seen = HashSet<String>()
        val combined = ArrayList<GeocodingResult>(tileHits.size)
        fun key(r: GeocodingResult) =
            "${r.name ?: r.text}|${"%.4f".format(r.lat)}|${"%.4f".format(r.lng)}"
        for (r in tileHits) {
            if (seen.add(key(r))) combined.add(r)
        }

        // Local-only path: the server either was not consulted or could not
        // answer. Ranking still applies — region and tile rows are two sources
        // and proximity should decide between them.
        SearchOutcome(
            combined.take(limit),
            fromServer = false,
            serverConclusive = serverConclusive,
        ).also { putCachedSearch(cacheKey, it) }
    }

    /**
     * Search the global city index for matches. Used by the
     * address-search dialog to disambiguate the rough area before pulling
     * the matching street-level tile.
     *
     * Lazily fetches tier 1 (~7 MB compressed) the first time anyone
     * searches a city-shaped query. If tier 1 returns nothing for a
     * non-empty query, tier 2 (~5 MB delta) is fetched and merged in. Both
     * downloads are static version-pinned files, indistinguishable across
     * users — server never learns what the user typed.
     *
     * Returns an empty list if both tiers fail to load (offline first
     * launch). UI should fall back to local tile cache + the pick-on-map
     * affordance in that case.
     */
    suspend fun searchCities(
        query: String,
        limit: Int = 10,
        focusLat: Double? = null,
        focusLng: Double? = null,
    ): List<uniffi.pingolin_mobile.CityResult> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()

        // Locale fast-path: try the per-country file first when the
        // device locale resolves to a country code. Replaces the
        // first ~7 MB tier-1 download with ~500 KB-2 MB for the
        // typical "user searches their own country" case.
        val country = java.util.Locale.getDefault().country
        if (country.isNotBlank() && country.length == 2) {
            ensureCountryTierLoaded(country)
        }

        // ...but the country file must not be the ONLY thing consulted for a
        // query that names a city. Return early on any country-file hit and
        // "delhi" from a US device matches Delhi, CA, tier 1 never downloads,
        // and Delhi, IN is unreachable — likewise London, Dublin, Berlin.
        // A 1-2 token query is city-shaped: pay the one-time tier-1 download
        // and let the candidates compete on population (CityIndex.search ranks
        // across every loaded tier). Address-shaped input (3+ tokens) keeps the
        // cheap path — it is peeled by buildSmartCandidates before it gets here.
        val cityShaped = q.split(Regex("\\s+")).count { it.isNotEmpty() } <= 2
        if (cityShaped) ensureTier1Loaded()

        // Over-fetch. CityIndex::search can only order by population, so it
        // truncates on the one signal [rankCities] is about to overrule — ask
        // for `limit` and the nearby, smaller, same-country city the user
        // actually meant is dropped before the ranker ever sees it. Same
        // truncate-before-rank trap the address path over-fetches for.
        val fetch = maxOf(limit * 5, MIN_CITY_FETCH)
        fun hits(tier: String): List<uniffi.pingolin_mobile.CityResult> = try {
            cityIndex.search(q, fetch.toUInt())
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "CityIndex $tier search failed: ${e.message}")
            emptyList()
        }
        fun ranked(rows: List<uniffi.pingolin_mobile.CityResult>) =
            rankCities(rows, focusLat, focusLng, country).take(limit)

        // Short-circuit on any hit. For a city-shaped query tier 1 is loaded
        // by now, so these hits are ranked against the global index rather
        // than being the locale file's opinion alone. Tiers 2/3 stay
        // unfetched; they are the sub-5K-population long tail.
        val localeHits = hits("locale")
        if (localeHits.isNotEmpty()) return@withContext ranked(localeHits)

        ensureTier1Loaded()
        val tier1Hits = hits("tier1")
        if (tier1Hits.size >= limit) return@withContext ranked(tier1Hits)

        // Only pay for tier 2 when tier 1 has nothing (or has space). This
        // is the bandwidth-saving payoff of the two-tier design — most
        // users searching for major cities never trigger the tier 2
        // download.
        ensureTier2Loaded()
        val withTier2 = hits("tier2")
        if (withTier2.isNotEmpty()) return@withContext ranked(withTier2)

        // Tier 3 — small unincorporated communities (≥500 pop). Lazy-
        // fetched only after tier 2 missed because the long tail
        // shouldn't cost every user bandwidth they'll never use.
        ensureTier3Loaded()
        val withTier3 = hits("tier3")
        if (withTier3.isNotEmpty()) ranked(withTier3) else ranked(tier1Hits)
    }

    private suspend fun ensureTier1Loaded() = withContext(Dispatchers.IO) {
        if (cityIndex.isTier1Loaded()) return@withContext
        try {
            cityIndex.ensureTier1(CITIES_TIER1_URL)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "CityIndex tier1 load failed: ${e.message}")
        }
    }

    private suspend fun ensureTier2Loaded() = withContext(Dispatchers.IO) {
        if (cityIndex.isTier2Loaded()) return@withContext
        try {
            cityIndex.ensureTier2(CITIES_TIER2_URL)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "CityIndex tier2 load failed: ${e.message}")
        }
    }

    private suspend fun ensureTier3Loaded() = withContext(Dispatchers.IO) {
        if (cityIndex.isTier3Loaded()) return@withContext
        try {
            cityIndex.ensureTier3(CITIES_TIER3_URL)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "CityIndex tier3 load failed: ${e.message}")
        }
    }

    private suspend fun ensureCountryTierLoaded(cc: String) = withContext(Dispatchers.IO) {
        if (cityIndex.isCountryTierLoaded()) return@withContext
        val url = "${CITIES_BY_COUNTRY_URL_PREFIX}${cc.lowercase()}.sqlite.zst"
        try {
            cityIndex.ensureCountryTier(url)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "CityIndex country $cc load failed: ${e.message}")
        }
    }



    /**
     * Per-city addr+POI shard lookup: lazy-fetches the shard
     * for `geonameId` from the CDN, queries the local addr_index +
     * poi_index for the term, returns the deduplicated tile_ids that
     * contain it. Empty list when:
     *   - the shard doesn't exist on CDN (404 — small cities w/o OA)
     *   - the shard has no matches for `term`
     *   - any I/O failure (logged, not raised)
     * Caller treats all three identically and falls back to
     * [ensureTileAndNeighborsLoaded].
     */
    suspend fun lookupCityShardTiles(geonameId: Long, term: String): List<String> =
        withContext(Dispatchers.IO) {
            try {
                tileGeocoder.lookupCityShardTiles(geonameId, term)
            } catch (e: Exception) {
                android.util.Log.w(
                    "GeocodingRepository",
                    "lookupCityShardTiles failed: ${e.message}",
                )
                emptyList()
            }
        }

    /**
     * Download + load a single tile by manifest tile_id. Used in the
     * surgical path: shard says tile X contains the user's term →
     * load only X instead of the centroid + 8 neighbors. Returns
     * `true` iff the tile is loaded into memory after the call.
     */
    suspend fun ensureTileLoadedById(tileId: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                tileGeocoder.ensureTileLoadedById(tileId)
            } catch (e: Exception) {
                android.util.Log.w(
                    "GeocodingRepository",
                    "ensureTileLoadedById($tileId) failed: ${e.message}",
                )
                false
            }
        }

    /**
     * Download + load ONLY the single tile covering `(lat, lng)` — no
     * neighbors. Surgical primitive for defaulting a search scope to the
     * current city/viewport: one precision-4 tile (~760 km²) covers the
     * whole city. Returns true iff a tile was loaded. Callers gate this on
     * an unmetered connection — a dense-metro tile can be tens of MB.
     */
    suspend fun ensureTileForCoordsLoaded(lat: Double, lng: Double): Boolean =
        withContext(Dispatchers.IO) {
            try {
                tileGeocoder.ensureTileForCoordsLoaded(lat, lng)
            } catch (e: Exception) {
                // Do NOT log the precise coordinates — Log.w survives release
                // (proguard only strips Log.d/Log.v). Keep the error message only.
                android.util.Log.w(
                    "GeocodingRepository",
                    "ensureTileForCoordsLoaded failed: ${e.message}",
                )
                false
            }
        }

    /**
     * Download + load the tile containing `(lat, lng)` plus its 8
     * geohash neighbors. Fallback path for the smart-fallback search
     * when the per-city shard is missing or returned no match. Picks
     * up streets that sit on tile boundaries (e.g. "900 East 11th"
     * lives in an Austin neighbor tile, not the centroid). Returns
     * the count of tiles that were loaded into memory; per-neighbor
     * failures are logged but don't fail the whole call.
     */
    suspend fun ensureTileAndNeighborsLoaded(lat: Double, lng: Double): Int =
        withContext(Dispatchers.IO) {
            try {
                tileGeocoder.ensureTileAndNeighborsLoaded(lat, lng).toInt()
            } catch (e: Exception) {
                android.util.Log.w(
                    "GeocodingRepository",
                    "ensureTileAndNeighborsLoaded failed: ${e.message}",
                )
                0
            }
        }

    /**
     * The nearest street address to a point — what a POI needs to show a full
     * address when its own record carries no street.
     *
     * NOT reverseGeocode(): an unrestricted reverse at a venue's coordinates
     * answers with the venue itself, which has no street, so it can never
     * supply the missing piece. The server path asks Pelias for the address
     * layer specifically; the on-device tile geocoder already derives a street
     * from the nearest address in the cell, so it is the natural fallback.
     */
    suspend fun nearestAddress(lat: Double, lng: Double): GeocodingResult? =
        withContext(Dispatchers.IO) {
            if (sessionManager.getUseServerGeocoder()) {
                serverGeocoder.nearestAddress(lat, lng)?.let { return@withContext it }
            }
            reverseGeocode(lat, lng)
        }

    /**
     * Reverse geocoding: the address for a coordinate. Answers from downloaded
     * regions first (local, no network, comprehensive coverage), then from
     * tile-based geocoding, which auto-downloads tiles on demand.
     *
     * PRIVACY: local unless the opt-in server geocoder below is enabled; with it off,
     * the server never learns which locations the user is looking up.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): GeocodingResult? = withContext(Dispatchers.IO) {
        // Opt-in server-side path. Falls back silently to local geocoders
        // on any failure (no subscription, server down, network error) —
        // the user still gets an answer instead of an error.
        if (sessionManager.getUseServerGeocoder()) {
            val serverResult = serverGeocoder.reverse(lat, lng)
            if (serverResult != null) {
                return@withContext serverResult
            }
        }

        // Fall back to tile-based geocoding (auto-downloads on demand)
        try {
            tileGeocoder.reverse(lat, lng)
        } catch (e: Exception) {
            android.util.Log.w("GeocodingRepository", "Tile geocoding failed: ${e.message}")
            null
        }
    }


    /**
     * Load preferences from local storage.
     */
    suspend fun loadPreferences(): Result<GeocodingPreferences?> = withContext(Dispatchers.IO) {
        try {
            val json = prefs.getString(KEY_PREFERENCES, null)
            if (json != null) {
                val geocodingPrefs = gson.fromJson(json, GeocodingPreferences::class.java)
                _preferences.value = geocodingPrefs
                Result.success(geocodingPrefs)
            } else {
                _preferences.value = null
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save preferences to local storage.
     */
    suspend fun savePreferences(geocodingPrefs: GeocodingPreferences): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(geocodingPrefs)
            prefs.edit().putString(KEY_PREFERENCES, json).apply()
            _preferences.value = geocodingPrefs
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mark that the user has been prompted for region selection.
     */
    suspend fun markPrompted(): Result<Unit> {
        val current = _preferences.value ?: GeocodingPreferences(
            regions = emptyList(),
            prompted = false
        )
        return savePreferences(current.copy(prompted = true))
    }

    /**
     * Check if user needs to be prompted for region selection.
     */
    fun needsPrompt(): Boolean {
        return _preferences.value?.prompted != true
    }

    /**
     * Check if LOCAL geocoding data is available. Tiles download on demand, so
     * this is really "do we have a manifest yet".
     *
     * Callers deciding whether to attempt a lookup want [canGeocode] instead —
     * this one answers a narrower question than its name suggests.
     */
    fun hasGeocodingData(): Boolean {
        return tileGeocoder.hasManifest()
    }

    /**
     * Can we resolve an address at all, by any route?
     *
     * Callers ask this, not [hasGeocodingData], which asks only about local
     * tiles: with the server geocoder ON and no manifest yet,
     * [reverseGeocode] answers from the server without touching local data.
     * That is exactly the case the server geocoder exists for: a fresh
     * install or login before the manifest lands, or someone who enabled it
     * precisely to avoid carrying map data.
     *
     * It lives here rather than at the call sites because this class owns the
     * dispatch; callers that each reason about geocoder availability on their
     * own get it wrong the same way.
     */
    fun canGeocode(): Boolean {
        return tileGeocoder.hasManifest() || sessionManager.getUseServerGeocoder()
    }


    /**
     * Initialize repository - call after login.
     */
    suspend fun initialize() {
        loadPreferences()
        initializeTileGeocoder()
    }

    /**
     * Initialize tile-based geocoder by fetching the manifest.
     * This enables on-demand tile downloads for reverse geocoding.
     *
     * Uses exponential backoff retry to handle transient network errors.
     * Failures are logged but not surfaced to the user since tile geocoding
     * is a non-critical background feature.
     */
    private suspend fun initializeTileGeocoder() = withContext(Dispatchers.IO) {
        // Already loaded in this process: nothing to fetch. initialize() runs on
        // every MainViewModel.initialize(), and fetchManifest has no cache of its own
        // on the device, so each call would download and parse the whole manifest
        // (1.15 MB on the wire, 3.4 MB of JSON).
        if (tileGeocoder.hasManifest()) return@withContext
        val maxRetries = 3
        var delayMs = 1000L // Start with 1 second delay

        for (attempt in 1..maxRetries) {
            try {
                android.util.Log.i("GeocodingRepository", "Tile geocoder initialization attempt $attempt/$maxRetries...")
                val result = tileGeocoder.fetchManifest()
                android.util.Log.i("GeocodingRepository", "Tile manifest fetch returned: $result, hasManifest=${tileGeocoder.hasManifest()}")
                return@withContext // Success, exit
            } catch (e: Exception) {
                if (attempt == maxRetries) {
                    // Final attempt failed - log but don't crash
                    android.util.Log.w("GeocodingRepository", "Tile geocoder initialization failed after $maxRetries attempts. " +
                        "Geocoding will be unavailable until next app restart. Error: ${e.message}")
                } else {
                    // Retry with exponential backoff
                    android.util.Log.d("GeocodingRepository", "Tile manifest fetch attempt $attempt failed, retrying in ${delayMs}ms: ${e.message}")
                    delay(delayMs)
                    delayMs *= 2 // Exponential backoff
                }
            }
        }
    }

    /**
     * Check if tile-based geocoding is available.
     */
    fun hasTileGeocoding(): Boolean {
        return tileGeocoder.hasManifest()
    }

    /**
     * Get count of cached geocoding tiles.
     */
    fun getCachedTileCount(): Int {
        return tileGeocoder.cachedTileCount().toInt()
    }

    /**
     * Clear all cached geocoding tiles.
     */
    suspend fun clearTileCache(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            tileGeocoder.clearCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class DownloadProgress(
    val regionId: String,
    val progress: Float,
    val state: DownloadState
)

enum class DownloadState {
    DOWNLOADING,
    COMPLETED,
    FAILED
}
