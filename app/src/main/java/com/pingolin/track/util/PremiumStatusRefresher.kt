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
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the effective-premium refresh.
 *
 * Fetches the user's effective entitlement from the server — own
 * subscription OR circle-derived (any member of a circle the user is in
 * is subscribed) — and caches flag + source + tier label on
 * [SessionManager], which premium gates read reactively.
 *
 * Kept out of SettingsViewModel so the refresh isn't tied to the user
 * happening to open Settings: SessionManager seeds `effectivePremium`
 * from prefs, which on a fresh login is `false` until the first fetch
 * lands, and a genuinely-premium user who went straight to Share
 * Destination would hit the premium-feature wall. MainActivity
 * refreshes at startup, and the destination gates kick a refresh when
 * they block, so a stale `false` self-heals.
 */
@Singleton
class PremiumStatusRefresher @Inject constructor(
    private val tracksApi: TrackApi,
    private val authApi: AuthApi,
    private val sessionManager: SessionManager,
) {
    companion object {
        private const val TAG = "PremiumRefresher"

        /** Track's product in the auth server's catalogue; the track server asks
         * for the same one.
         */
        internal const val TRACK_PRODUCT = "track"
    }

    /**
     * Refresh the cached entitlement. Returns the derived tier label
     * ("Premium · Circle" / "Premium" / "Free"), or null when nothing
     * could be determined (logged out, or offline with the fallback also
     * failing) — callers should keep their cached value in that case.
     *
     * Falls back to the user's OWN subscriptions (auth billing API) if the
     * effective-premium endpoint is unavailable, so a premium user isn't
     * shown as Free; circle-derived premium just isn't reflected until the
     * server endpoint is reachable. That fallback counts only Track's own
     * product, and never makes the plan Free.
     */
    suspend fun refresh(): String? {
        if (!sessionManager.isLoggedIn()) return null
        return try {
            val response = tracksApi.getPremiumStatus()
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()?.data
                applyPremium(data?.hasPremium == true, data?.source ?: "none")
            } else {
                refreshFromOwnSubscriptions()
            }
        } catch (_: Exception) {
            refreshFromOwnSubscriptions()
        }
    }

    /**
     * The auth server's list is this account's subscriptions to every product, so counting every
     * entry would read a Note subscription as Track Premium. Only an active Track one counts, and it
     * can only make the plan Premium: a circle's plan can't be seen here, so nothing in this list
     * makes it Free. An entry naming no product decides nothing (the auth server may send no
     * `product_slug`): the cached plan stays, as when both reads fail.
     */
    private suspend fun refreshFromOwnSubscriptions(): String? {
        return try {
            val response = authApi.getSubscriptions()
            if (response.isSuccessful) {
                val track = response.body()?.data.orEmpty().filter { it.productSlug == TRACK_PRODUCT }
                if (track.any { it.status == "active" || it.status == "trialing" }) applyPremium(true, "own") else null
            } else {
                null
            }
        } catch (e: Exception) {
            // Non-critical — keep cached value.
            Log.d(TAG, "Premium refresh failed: ${e.message}")
            null
        }
    }

    private fun applyPremium(hasPremium: Boolean, source: String): String {
        val tier = when {
            source == "circle" -> "Premium · Circle"
            hasPremium -> "Premium"
            else -> "Free"
        }
        sessionManager.setEffectivePremium(hasPremium)
        sessionManager.setPremiumSource(source)
        sessionManager.setSubscriptionTier(tier)
        return tier
    }
}
