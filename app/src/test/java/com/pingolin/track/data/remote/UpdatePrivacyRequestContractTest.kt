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

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.data.remote.dto.UpdatePrivacyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PUT /circles/{id}/privacy REPLACES the whole row, so anything this DTO cannot carry
 * is destroyed by every write.
 *
 * `paused_until` and `approximate_until` have to be in the request. The server writes
 * both on every PUT, and an absent key reads as NULL — so pausing for four hours and
 * then flipping any unrelated toggle in the same dialog would rewrite the deadline to
 * NULL, which the server reads as a PERMANENT pause. The client has to be able to
 * express the state it just created.
 *
 * Uses the same plain Gson the app's Retrofit instance does (`di/AppModule.kt`:
 * `GsonBuilder().create()`), including its default of omitting null fields.
 */
class UpdatePrivacyRequestContractTest {

    private val gson = GsonBuilder().create()

    private fun encode(request: UpdatePrivacyRequest): JsonObject =
        JsonParser.parseString(gson.toJson(request)).asJsonObject

    /** The whole point: a deadline handed in must reach the wire under the server's name. */
    @Test
    fun `both deadlines round-trip under their wire names`() {
        val json = encode(
            UpdatePrivacyRequest(
                sharingMode = "paused",
                shareRealtime = true,
                shareHistory = true,
                shareBattery = true,
                pausedUntil = "2026-08-22T18:30:00Z",
                approximateUntil = "2026-08-23T09:00:00Z"
            )
        )
        assertEquals("2026-08-22T18:30:00Z", json.get("paused_until").asString)
        assertEquals("2026-08-23T09:00:00Z", json.get("approximate_until").asString)
    }

    /**
     * Gson omits nulls by default, and the server reads an absent key as no value.
     * That is how "resume" clears the deadline, so it must stay absent rather
     * than become an explicit JSON null the server would have to special-case.
     */
    @Test
    fun `a null deadline is omitted, which the server reads as cleared`() {
        val json = encode(
            UpdatePrivacyRequest(
                sharingMode = "exact",
                shareRealtime = false,
                shareHistory = true,
                shareBattery = true,
                pausedUntil = null,
                approximateUntil = null
            )
        )
        assertFalse(json.has("paused_until"))
        assertFalse(json.has("approximate_until"))
    }

    /**
     * The server rejects `sharing_mode = 'approximate'` with no radius outright (a 400,
     * "approximate_radius_miles is required when sharing_mode is 'approximate'"), so a
     * caller that round-trips the mode must round-trip the radius with it, or the write
     * 400s for everyone currently in a bubble.
     */
    @Test
    fun `approximate mode carries its radius`() {
        val json = encode(
            UpdatePrivacyRequest(
                sharingMode = "approximate",
                shareRealtime = true,
                shareHistory = true,
                shareBattery = true,
                approximateRadiusMiles = 5
            )
        )
        assertEquals("approximate", json.get("sharing_mode").asString)
        assertEquals(5, json.get("approximate_radius_miles").asInt)
    }

    /** Every field the server rewrites on a PUT has to be expressible here. */
    @Test
    fun `the request covers every column the upsert writes`() {
        val json = encode(
            UpdatePrivacyRequest(
                sharingMode = "approximate",
                shareRealtime = true,
                shareHistory = false,
                shareBattery = true,
                approximateRadiusMiles = 3,
                pausedUntil = "2026-08-22T18:30:00Z",
                approximateUntil = "2026-08-23T09:00:00Z",
                primaryDeviceId = "device-1",
                primaryDevicePseudonym = "pseudo-1",
                showAllDevices = true,
                allowAlertsOnMe = false
            )
        )
        listOf(
            "sharing_mode", "approximate_radius_miles", "approximate_until", "paused_until",
            "share_realtime", "share_history", "share_battery", "primary_device_id",
            "primary_device_pseudonym", "show_all_devices", "allow_alerts_on_me"
        ).forEach { column ->
            assertTrue("request cannot express `$column`, which the UPSERT rewrites", json.has(column))
        }
    }
}
