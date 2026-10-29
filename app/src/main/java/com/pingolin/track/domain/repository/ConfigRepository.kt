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

import android.util.Log
import com.google.gson.Gson
import com.pingolin.track.data.remote.dto.TracksConfigResponse
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches server configuration via the web URL.
 *
 * The /api/config endpoint is public (no auth required) and served by the
 * web front end's proxy. In the dev environment the direct API host is
 * mTLS-protected and unreachable from the Android app, so the web host is
 * used instead.
 */
@Singleton
class ConfigRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: SessionManager,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "ConfigRepository"
    }

    suspend fun fetchConfig(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = sessionManager.getWebBaseUrl().trimEnd('/')
                val request = Request.Builder()
                    .url("$baseUrl/api/config")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val config = gson.fromJson(resp.body.string(), TracksConfigResponse::class.java)
                        val mapConfig = config.map

                        // Save PMTiles config if available from server
                        mapConfig.pmtiles?.let { pmtiles ->
                            sessionManager.savePMTilesConfig(
                                tilesUrl = pmtiles.tilesUrl,
                                lightStyle = pmtiles.lightStyle,
                                darkStyle = pmtiles.darkStyle,
                                tileTemplate = pmtiles.tileTemplate
                            )
                            Log.d(TAG, "PMTiles config updated from server: ${pmtiles.tilesUrl}")
                        }

                        Result.success(Unit)
                    } else {
                        Log.w(TAG, "Failed to fetch config: ${resp.code}")
                        Result.failure(Exception("Failed to fetch config: ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Config fetch error: ${e.message}")
                Result.failure(e)
            }
        }
    }
}
