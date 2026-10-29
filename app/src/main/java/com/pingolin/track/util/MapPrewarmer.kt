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

package com.pingolin.track.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Pre-warms map resources after login to avoid cold start delays when user opens the map.
 *
 * Fetches in background:
 * - Map style JSON
 * - Sprite images
 * - Glyphs (fonts)
 *
 * NOT the PMTiles archive: the map reads tiles from the tile endpoint, so
 * nothing on this device opens planet.pmtiles.
 */
@Singleton
class MapPrewarmer @Inject constructor(
    @param:Named("mapLibreClient") private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "MapPrewarmer"
    }

    /**
     * Start pre-warming map resources in the background.
     * Call this after successful login.
     */
    fun prewarm() {
        scope.launch {
            try {
                Log.d(TAG, "Starting map pre-warming")
                val startTime = System.currentTimeMillis()

                // Get config URLs
                val pmtilesUrl = sessionManager.getPMTilesTilesUrl()
                val isDark = sessionManager.getThemeMode() == SessionManager.THEME_DARK
                val styleUrl = if (isDark) sessionManager.getMapDarkStyle() else sessionManager.getMapLightStyle()

                // The archive header is deliberately NOT fetched:
                // the map reads tiles from the tile endpoint, so
                // nothing on this device opens planet.pmtiles —
                // warming its first 16KB would be a range request
                // against a 134 GB object that the CDN refuses to
                // cache, to fill a cache entry no later request
                // consults.
                //
                // Tiles themselves cannot usefully be pre-warmed
                // here: which ones are needed depends on where the
                // user turns out to be. The edge cache is what makes
                // them fast, and it is filled by whoever looks
                // first, including other users.
                val jobs = listOf(
                    launch { fetchResource("Map style", styleUrl) },
                    launch { fetchSprites(pmtilesUrl) }
                )

                // Wait for all to complete (but don't block the caller)
                jobs.forEach { it.join() }

                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "Map pre-warming completed in ${elapsed}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Map pre-warming failed", e)
            }
        }
    }

    private suspend fun fetchResource(name: String, url: String?, rangeBytes: String? = null) {
        if (url.isNullOrEmpty()) {
            Log.d(TAG, "Skipping $name: no URL")
            return
        }

        try {
            val requestBuilder = Request.Builder().url(url)

            // Use Range header for partial fetches (like PMTiles header)
            if (rangeBytes != null) {
                requestBuilder.header("Range", "bytes=$rangeBytes")
            }

            val request = requestBuilder.build()
            val response = okHttpClient.newCall(request).execute()

            response.use {
                if (it.isSuccessful) {
                    // Read body to ensure full fetch
                    it.body?.bytes()
                    Log.d(TAG, "$name fetched: ${it.code}")
                } else {
                    Log.w(TAG, "$name fetch failed: ${it.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$name fetch error: ${e.message}")
        }
    }

    private suspend fun fetchSprites(pmtilesUrl: String) {
        // Derived from the ARCHIVE url, not the tile template: chopping the last
        // segment off ".../tiles/v4/{z}/{x}/{y}.mvt" would yield ".../{z}/{x}" and
        // a set of broken sprite requests. The archive url is kept for exactly
        // this.
        // Derive sprite URL from PMTiles URL
        // https://pmtiles.pingolin.com/planet.pmtiles -> https://pmtiles.pingolin.com/sprites/v4/dark@2x.png
        val baseUrl = pmtilesUrl.substringBeforeLast("/")
        val isDark = sessionManager.getThemeMode() == SessionManager.THEME_DARK
        val theme = if (isDark) "dark" else "light"

        val spriteUrls = listOf(
            "$baseUrl/sprites/v4/$theme.json",
            "$baseUrl/sprites/v4/$theme.png",
            "$baseUrl/sprites/v4/$theme@2x.png"
        )

        spriteUrls.forEach { url ->
            fetchResource("Sprite", url)
        }
    }
}
