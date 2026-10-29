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

package com.pingolin.track.data.remote.dto

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /api/v1/circles` carries the caller's own privacy posture as `my_privacy`,
 * the same object `GET /api/v1/privacy/circles/{id}` returns, left out when there
 * is none. These pin that the app reads that exact shape, and that a circle
 * without the field reads as null, which CircleRepository.mirrorPrivacy
 * treats as "ask the privacy route".
 */
class CircleResponseMyPrivacyJsonTest {

    // What AppModule provides: a plain GsonBuilder().create().
    private val gson = GsonBuilder().create()

    private val circleFields = "\"id\":\"c-1\",\"encrypted_name\":{\"nonce\":\"n\",\"ciphertext\":\"c\"}," +
        "\"encrypted_key\":null,\"ephemeral_public_key\":null,\"member_count\":2,\"is_owner\":false," +
        "\"day_bucket\":20353,\"key_rotation_needed\":false,\"key_version\":3"

    @Test
    fun `my_privacy parses into the privacy route's own DTO`() {
        val json = "{" + circleFields + ",\"my_privacy\":{" +
            "\"id\":\"8f0c2d52-4bd1-4a8f-9d8e-0b9f4f1e2a10\",\"circle_id\":\"c-1\"," +
            "\"sharing_mode\":\"approximate\",\"approximate_radius_miles\":5," +
            "\"approximate_until\":null,\"paused_until\":\"2026-09-23T08:00:00Z\"," +
            "\"share_realtime\":false,\"share_history\":true,\"share_battery\":false," +
            "\"primary_device_id\":null,\"primary_device_pseudonym\":null," +
            "\"show_all_devices\":true,\"allow_alerts_on_me\":false," +
            "\"created_day_bucket\":20350,\"updated_day_bucket\":20353}}"

        val posture = gson.fromJson(json, CircleResponse::class.java).myPrivacy!!

        assertEquals("c-1", posture.circleId)
        assertEquals("approximate", posture.sharingMode)
        assertEquals(5, posture.approximateRadiusMiles)
        assertEquals("2026-09-23T08:00:00Z", posture.pausedUntil)
        assertFalse(posture.shareRealtime)
        assertTrue(posture.shareHistory)
        assertFalse(posture.shareBattery)
        assertTrue(posture.showAllDevices)
        assertFalse(posture.allowAlertsOnMe)
        assertEquals(20353, posture.updatedDayBucket)
    }

    @Test
    fun `a circle without my_privacy reads it as null`() {
        val dto = gson.fromJson("{" + circleFields + "}", CircleResponse::class.java)

        assertNull(dto.myPrivacy)
        assertEquals("c-1", dto.id)
    }
}
