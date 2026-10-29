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
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.LocationConsentNoticeResponse
import com.pingolin.track.data.remote.dto.LocationConsentResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consent to location processing, per circle.
 *
 * A concise just-in-time notice comes before collection, stating what leaves
 * the device, recipients (circle members and vendors), purposes,
 * history/retention and controls; any consent the applicable state/EU law
 * requires is obtained and logged.
 *
 * "Before collection" is why this is not at signup. At signup nothing is
 * collected and there are no recipients to name; sharing begins when a circle
 * is created or joined, and only then can the notice say who reads it and for
 * how long it is kept.
 *
 * Nothing is cached locally. The consent state is small, it is read once per
 * join, and a stale local copy could cause the one failure that matters —
 * skipping the notice for a circle that never answered it.
 */
@Singleton
class LocationConsentRepository @Inject constructor(
    private val tracksApi: TrackApi,
) {
    companion object {
        private const val TAG = "LocationConsentRepo"
    }

    /**
     * The notice this server serves, or null if it could not be fetched.
     *
     * Null is not fatal: the dialog falls back to its bundled English copy, and
     * a consent granted against that is still stamped server-side with whatever
     * text the server is actually serving, so the record stays accurate even
     * when this call did not.
     */
    suspend fun notice(): LocationConsentNoticeResponse? = try {
        val response = tracksApi.getLocationConsentNotice()
        response.body()?.takeIf { response.isSuccessful && it.success }?.data
    } catch (e: Exception) {
        Log.w(TAG, "Could not fetch the location notice: ${e.message}")
        null
    }

    /**
     * The current consent row for a circle, or null if it could not be read.
     *
     * For DISPLAY, so it fails to null: a screen that renders a state because a
     * request timed out would be telling somebody something about their location
     * that is not so.
     */
    suspend fun state(circleId: String): LocationConsentResponse? = try {
        val response = tracksApi.getLocationConsent(circleId)
        val body = response.body()
        if (response.isSuccessful && body?.success == true) body.data else null
    } catch (e: Exception) {
        Log.w(TAG, "Consent state for $circleId unavailable: ${e.message}")
        null
    }
}
