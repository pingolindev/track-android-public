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

package com.pingolin.track.data.remote

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.GeocodingResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * Client for the opt-in server-side geocoder. The Track server proxies the
 * request to a Pelias gateway that is not externally reachable. Credits are
 * issued against the user's JWT + active subscription, and each request
 * spends one anonymous VOPRF credit on a session-less endpoint (see
 * [GeocodeTokenManager]); there is no separate domain.
 *
 * Off by default — controlled by [com.pingolin.track.util.SessionManager.getUseServerGeocoder].
 * Callers should still wrap this in a try/catch + local fallback because
 * 402 (Free tier) and 503 (server-side disabled) both legitimately mean
 * "fall back to the on-device geocoder."
 *
 * Privacy: coordinates are truncated to 4 decimals (~11 m) before send —
 * defense in depth on top of the truncation the server also performs.
 */
@Singleton
class ServerGeocoder @Inject constructor(
    private val gson: Gson,
    private val geocodeTokenManager: GeocodeTokenManager
) {
    companion object {
        private const val TAG = "ServerGeocoder"
        /** Round to 4 decimals ≈ 11 m horizontal at the equator. */
        private const val COORD_TRUNC_FACTOR = 10_000.0

        /**
         * Pelias layers that denote a concrete place. Everything else
         * (locality, region, county, country, macroregion, …) is an
         * administrative area owned by the city index — see [isPlaceLayer].
         */
        private val PLACE_LAYERS = setOf("venue", "address", "street")

        /**
         * Did the gateway order these rows itself?
         *
         * A search with a focus point is answered by the geocoder's own ranked
         * route, which marks its reply `geocoding.ranking.version`. A reply
         * without the mark (a server with no ranked route, a search without a
         * focus point, the gateway falling back to its stock query) is in
         * Pelias's order, which this app re-orders; a reply WITH it must not
         * be, because the order carries things this app cannot see in a row:
         * which place is a landmark, which rows matched only by allowing a
         * typo, which are "a pharmacy" rather than called one.
         *
         * Any version from 1 up counts: a later version may order differently,
         * but it is still the gateway's order. Only a NUMBER is a version.
         */
        internal fun isOrdered(root: com.google.gson.JsonObject?): Boolean {
            val ranking = root?.get("geocoding")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("ranking")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
            val version = ranking.get("version")?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?: return false
            return version.isNumber && version.asDouble >= 1.0
        }

        /**
         * The one language code a search carries, e.g. "de", or null.
         *
         * WHY: the backend passes it to the geocoder so that "eiffelturm" finds
         * the tower and the rows come back named in the user's language.
         *
         * WHY SO LITTLE: this request is anonymous on purpose, and OkHttp adds
         * no language header of its own. So the app adds exactly what the
         * search needs and no more: the primary code of the app's language.
         * Not the region ("CH" of "de-CH"), not the user's other languages.
         *
         * `toLanguageTag()` is what turns Android's legacy codes into current
         * ones ("iw" into "he", "in" into "id"); "und" is its word for none.
         */
        internal fun anonymousLanguage(locale: java.util.Locale = java.util.Locale.getDefault()): String? {
            val primary = locale.toLanguageTag().substringBefore('-').lowercase(java.util.Locale.ROOT)
            val letters = primary.all { it in 'a'..'z' }
            return primary.takeIf { it.length in 2..3 && letters && it != "und" }
        }
    }

    /**
     * What the gateway answered for a forward search: the rows, and whether it
     * says they are already in the order to show them in ([isOrdered]).
     */
    data class ServedPlaces(val rows: List<GeocodingResult>, val ordered: Boolean)

    /**
     * Reverse-geocode via the server's geocoding proxy. Returns null on
     * any failure so the caller can fall back to the local geocoder.
     */
    suspend fun reverse(lat: Double, lng: Double): GeocodingResult? = withContext(Dispatchers.IO) {
        val tLat = truncate(lat)
        val tLng = truncate(lng)

        try {
            // Anonymous VOPRF credit on the session-less endpoint — no user_id
            // travels next to the coordinates. There is deliberately no
            // authenticated fallback here: sending the JWT alongside the coords
            // would hand the server exactly the linkage this path exists to
            // avoid. When the private path is unavailable (free tier, no
            // credits, token flow down) this returns null and the caller falls
            // through to the on-device geocoder instead.
            val resp = geocodeTokenManager.redeemReverse(tLat, tLng, size = 1)
                ?: return@withContext null
            if (!resp.isSuccessful) {
                return@withContext null
            }
            val body = resp.body()?.string() ?: return@withContext null
            parsePeliasReverse(body, tLat, tLng)
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed: ${e.message}")
            null
        }
    }

    /**
     * Forward/text search via the server's geocoding proxy.
     *
     * Returns `null` when the server could not answer at all (no credit, HTTP
     * error, unreachable) — the caller should fall back fully. Returns an EMPTY
     * list when it answered and nothing matched: an authoritative "no", which
     * lets the caller skip the speculative tile downloads.
     *
     * PRIVACY: unlike [reverse], this sends the user's QUERY TEXT to the
     * server. The request is session-less and carries no account identifier
     * (one anonymous VOPRF credit is the whole authorization), and the query
     * is never logged here or server-side — but it does leave the device, so
     * this path only runs when the user has opted in to the server-side
     * geocoder. [focusLat]/[focusLng] are truncated exactly like reverse
     * coordinates before they are sent.
     */
    suspend fun forward(
        query: String,
        size: Int,
        focusLat: Double?,
        focusLng: Double?,
    ): ServedPlaces? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        try {
            val resp = geocodeTokenManager.redeemSearch(
                text = q,
                size = size,
                focusLat = focusLat?.let { truncate(it) },
                focusLng = focusLng?.let { truncate(it) },
                language = anonymousLanguage(),
            )
            if (resp == null) return@withContext null
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body()?.string() ?: return@withContext null
            // NOTE the contract: `null` above means the server COULD NOT answer
            // (no credit, HTTP error, unreachable) — callers should fall back
            // fully. An EMPTY list means it answered and nothing matched, which
            // is a very different thing: the planet index has spoken, and there
            // is no point downloading tens of MB of tiles to ask again.
            ServedPlaces(
                rows = parseFeatures(body, fallbackLat = 0.0, fallbackLng = 0.0, placesOnly = true),
                ordered = isOrdered(runCatching {
                    gson.fromJson(body, com.google.gson.JsonObject::class.java)
                }.getOrNull()),
            )
        } catch (e: Exception) {
            // Do NOT include `q` — the message would then carry the query.
            Log.w(TAG, "Forward geocode failed: ${e.message}")
            null
        }
    }

    /**
     * The nearest street ADDRESS to a point, or null.
     *
     * Distinct from [reverse] because an unrestricted reverse at a venue's own
     * coordinates answers with the venue — which carries no street, so it can
     * never supply the one thing the caller wants. Restricting to the address
     * layer is what makes "the street this place sits on" answerable.
     */
    suspend fun nearestAddress(lat: Double, lng: Double): GeocodingResult? =
        withContext(Dispatchers.IO) {
            try {
                val resp = geocodeTokenManager.redeemReverse(
                    truncate(lat), truncate(lng), size = 1, layers = "address",
                ) ?: return@withContext null
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body()?.string() ?: return@withContext null
                parseFeatures(body, lat, lng).firstOrNull()
            } catch (e: Exception) {
                Log.w(TAG, "Nearest-address lookup failed: ${e.message}")
                null
            }
        }

    private fun truncate(coord: Double): Double {
        return (coord * COORD_TRUNC_FACTOR).roundToLong() / COORD_TRUNC_FACTOR
    }

    /**
     * Pelias `/v1/reverse` returns a GeoJSON FeatureCollection. Each
     * feature's `properties` carries `label`, `name`, locality fields,
     * and `confidence`. We lift the first feature into the SDK's
     * [GeocodingResult] shape so callers don't have to know they hit the
     * server instead of the local SQLite.
     */
    private fun parsePeliasReverse(json: String, fallbackLat: Double, fallbackLng: Double): GeocodingResult? =
        parseFeatures(json, fallbackLat, fallbackLng).firstOrNull()

    /**
     * Lift every feature of a Pelias FeatureCollection into the SDK's
     * [GeocodingResult] shape. Shared by reverse (which takes the first) and
     * forward search (which takes them all), so both render identically to a
     * tile-cache row.
     */
    private fun parseFeatures(
        json: String,
        fallbackLat: Double,
        fallbackLng: Double,
        placesOnly: Boolean = false,
    ): List<GeocodingResult> {
        return try {
            val root = gson.fromJson(json, com.google.gson.JsonObject::class.java)
                ?: return emptyList()
            val features = root.getAsJsonArray("features") ?: return emptyList()
            (0 until features.size()).mapNotNull { i ->
                runCatching {
                    val feat = features.get(i).asJsonObject
                    if (placesOnly && !isPlaceLayer(feat)) return@runCatching null
                    parseFeature(feat, fallbackLat, fallbackLng)
                }.getOrNull()
            }
        } catch (e: Exception) {
            // Never include the response body or the query in the message.
            Log.w(TAG, "Parse Pelias response failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Whether a feature is a concrete PLACE (a building you can stand in)
     * rather than an administrative area.
     *
     * Forward search keeps only these. The gateway happily answers "birmingham"
     * with eight localities, but those are exactly the rows the on-device city
     * index already owns — and it owns them BETTER, because it has population
     * and the server does not. Letting them through would put an unranked
     * "Birmingham, England" above a population-ranked "Birmingham, AL" from the
     * city channel. Reverse geocoding still keeps every layer: naming a point
     * legitimately falls back to its locality.
     */
    private fun isPlaceLayer(feat: com.google.gson.JsonObject): Boolean {
        val props = feat.getAsJsonObject("properties") ?: return false
        val layer = if (props.has("layer") && !props.get("layer").isJsonNull) {
            props.get("layer").asString
        } else {
            return false
        }
        return layer in PLACE_LAYERS
    }

    private fun parseFeature(
        feat: com.google.gson.JsonObject,
        fallbackLat: Double,
        fallbackLng: Double,
    ): GeocodingResult? {
        return try {
            val props = feat.getAsJsonObject("properties") ?: return null

            val geom = feat.getAsJsonObject("geometry")
            val coords = geom?.getAsJsonArray("coordinates")
            val lng = if (coords != null && coords.size() >= 1) coords.get(0).asDouble else fallbackLng
            val lat = if (coords != null && coords.size() >= 2) coords.get(1).asDouble else fallbackLat

            fun str(key: String): String? =
                if (props.has(key) && !props.get(key).isJsonNull) props.get(key).asString else null
            fun dbl(key: String): Double? =
                if (props.has(key) && !props.get(key).isJsonNull) props.get(key).asDouble else null

            val layer = str("layer") ?: ""
            val resultType = if (layer == "address" || layer == "street") "address" else "poi"
            // `text` must mean the same thing it means for a tile row, or every
            // consumer double-prints the locality: on an ADDRESS row it is the
            // street line alone, not the full label. Pelias already puts
            // "715 North La Brea Avenue" in `name` for those, while `label`
            // appends ", Los Angeles, CA, USA" — appending our own locality on
            // top of that would produce "…Avenue, Los Angeles, CA, USA,
            // Los Angeles, CA, USA".
            val text = if (resultType == "address") {
                str("name")
                    ?: listOfNotNull(str("housenumber"), str("street")).joinToString(" ")
                        .ifBlank { null }
                    ?: str("label")
                    ?: ""
            } else {
                str("label") ?: str("name") ?: ""
            }
            val name = str("name")
            // POI venues from Pelias carry street/housenumber separately —
            // surface them as the address line (mirrors the tile geocoder so
            // same-named places are distinguishable). Address/street layers
            // already put the street in `text`, so leave theirs null.
            val street = if (resultType == "poi") {
                listOfNotNull(str("housenumber"), str("street"))
                    .joinToString(" ").ifBlank { null }
            } else null
            // A venue row with no street shows name + city and nothing else —
            // and "Pink's Hot Dogs / Los Angeles, CA, USA" five times over is
            // indistinguishable. The gateway does know which part of the city
            // each one is in, so use it. Fairfax District,
            // Toluca Lake, Hollywood Hills, Westchester, Winnetka — those five
            // rows are 1.7-23 km apart and are genuinely different outlets, so
            // the answer is to SHOW the difference, not to collapse them.
            val locality = str("locality") ?: str("localadmin")
            val hood = str("neighbourhood") ?: str("borough")
            val city = when {
                // Once the row HAS a street, the street tells the outlets apart,
                // and it does it better than a neighbourhood name. The second
                // line only fits one of the two — measured at 13sp in the 954px
                // the sheet leaves: street + hood + distance is 62 chars and
                // wraps, street + distance is 44 and fits — so the hood yields.
                street != null -> locality ?: hood
                hood.isNullOrBlank() -> locality
                locality.isNullOrBlank() -> hood
                hood.equals(locality, ignoreCase = true) -> locality
                else -> "$hood, $locality"
            }
            // Prefer the abbreviations. Pelias sends both ("California" +
            // "CA", "United States" + "USA"); the tile geocoder only has the
            // short forms, and server and local rows land in the SAME result
            // list — mixing "Manteca, California" with "Manteca, CA" in one
            // list looks broken.
            val state = str("region_a") ?: str("region")
            val postcode = str("postalcode")
            val country = str("country_a") ?: str("country")

            GeocodingResult(
                text = text,
                lat = lat,
                lng = lng,
                resultType = resultType,
                confidence = dbl("confidence") ?: 0.5,
                name = name,
                city = city,
                state = state,
                postcode = postcode,
                country = country,
                address = street,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Parse Pelias response failed: ${e.message}")
            null
        }
    }
}
