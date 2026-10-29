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
import com.google.gson.reflect.TypeToken
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EjectAndRotateResponse
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two key-rotation endpoints answer with different shapes; neither sends
 * `key_version` or `key_rotation_needed`.
 *
 * A field name that matches nothing fails silently. Retrofit's Gson is plain
 * (`di/AppModule.kt`: `GsonBuilder().create()`), so it builds the object through
 * Unsafe and leaves such fields at their JVM defaults — version 0, flag false —
 * which the repository would write into Room and then submit as the NEXT
 * rotation's `expected_key_version`, losing that optimistic lock by construction.
 * The server answers a lost race with 200 and `already_rotated: true`, which the
 * client has to read.
 *
 * These parse the payloads the server actually returns, through the same plain
 * Gson.
 */
class RotationResponseParseTest {

    private val gson = GsonBuilder().create()

    private fun rotate(json: String): ApiResponse<RotateKeysResponse> =
        gson.fromJson(json, object : TypeToken<ApiResponse<RotateKeysResponse>>() {}.type)

    private fun eject(json: String): ApiResponse<EjectAndRotateResponse> =
        gson.fromJson(json, object : TypeToken<ApiResponse<EjectAndRotateResponse>>() {}.type)

    /** As the server answers POST /circles/{id}/rotate-keys. */
    @Test
    fun `rotate-keys success carries the new version`() {
        val parsed = rotate("""{"success":true,"data":{"new_key_version":2}}""")
        assertTrue(parsed.success)
        assertEquals(2, parsed.data?.newKeyVersion)
        assertNull("absent must stay distinguishable from false", parsed.data?.alreadyRotated)
    }

    /** The lost race. 200, not 409. */
    @Test
    fun `rotate-keys reports a peer's win as already_rotated`() {
        val parsed = rotate("""{"success":true,"data":{"new_key_version":7,"already_rotated":true}}""")
        assertEquals(true, parsed.data?.alreadyRotated)
        assertEquals(7, parsed.data?.newKeyVersion)
    }

    /**
     * A missing version must read as "the server said nothing", not as version 0.
     * Zero is the value that poisoned the next rotation, so it must never be
     * synthesised — which is why the field is nullable rather than a primitive.
     */
    @Test
    fun `a missing version is null, never zero`() {
        val parsed = rotate("""{"success":true,"data":{}}""")
        assertNotNull(parsed.data)
        assertNull(parsed.data?.newKeyVersion)
    }

    /** As the server answers POST /circles/{id}/members/eject-and-rotate — a different
     * shape.
     */
    @Test
    fun `eject-and-rotate carries the version and the ejected member`() {
        val parsed = eject(
            """{"success":true,"data":{"new_key_version":3,"ejected_user_id":"11111111-2222-3333-4444-555555555555"}}"""
        )
        assertEquals(3, parsed.data?.newKeyVersion)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed.data?.ejectedUserId)
    }

    /** `key_version` and `key_rotation_needed` are not names the server sends. */
    @Test
    fun `the retired field names parse to nothing`() {
        val parsed = rotate("""{"success":true,"data":{"key_version":9,"key_rotation_needed":true}}""")
        assertNull(parsed.data?.newKeyVersion)
        assertNull(parsed.data?.alreadyRotated)
    }
}
