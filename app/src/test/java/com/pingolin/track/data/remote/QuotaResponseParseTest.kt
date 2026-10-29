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

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.QuotaDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape of `GET /api/v1/quota`, for the two fields that drive the
 * history-grace countdown.
 *
 * WHY THE SECOND FIELD NEEDS PINNING SEPARATELY
 *   `history_expires_circle_id` decides which of two remedies the countdown
 *   offers: re-subscribe, or re-subscribe OR have a premium member join your
 *   circle. Null means the clock is on the user's own personal history, where
 *   the circle half is advice that cannot work.
 *
 *   A snake_case mismatch on that name is invisible from every angle. The field
 *   defaults to null, the copy renders, the countdown is right — and every
 *   circle-scoped warning quietly loses the one remedy that a circle member
 *   could act on. There is no error, no log line, and the sentence still reads
 *   as a complete sentence.
 *
 *   `history_expires_in_days` is pinned in the same test because a mismatch
 *   there hides the warning entirely rather than mis-wording it: the field is
 *   nullable with a null default and null means "nothing is counting down".
 *
 * The body below matches what the server serialises: `history_expires_in_days`
 * an optional integer, `history_expires_circle_id` an optional UUID.
 */
class QuotaResponseParseTest {

    private val type = object : TypeToken<ApiResponse<QuotaDto>>() {}.type

    private fun parse(json: String): QuotaDto {
        val envelope: ApiResponse<QuotaDto> = Gson().fromJson(json, type)
        assertTrue("success should be true", envelope.success)
        return requireNotNull(envelope.data) { "data missing — the envelope is not being parsed" }
    }

    private fun body(expiry: String): String = """
        {"success":true,"data":{
          "tier":"free",
          "max_places":2,"current_places":1,
          "max_circles":2,"current_circles":1,
          "max_place_alerts":2,"current_place_alerts":0,
          "max_destinations":1,"current_destinations":0,
          "max_members":5,
          "history_days":2,
          "source":"none",
          "premium_history_days":365,
          "smart_alerts_enabled":false,
          "destinations_enabled":false,
          "web_push_enabled":false
          $expiry
        },"error":null}
    """.trimIndent()

    @Test
    fun `a circle-scoped countdown carries the circle id`() {
        val q = parse(
            body(
                ""","history_expires_in_days":12,
                   "history_expires_circle_id":"6f1c2b4a-8f0d-4a2e-9c31-0d1e2f3a4b5c""""
            )
        )
        assertEquals(12L, q.historyExpiresInDays)
        assertEquals("6f1c2b4a-8f0d-4a2e-9c31-0d1e2f3a4b5c", q.historyExpiresCircleId)
    }

    @Test
    fun `an explicit null circle id is the users own history, not a parse failure`() {
        val q = parse(
            ""","history_expires_in_days":12,"history_expires_circle_id":null"""
                .let { body(it) }
        )
        assertEquals(12L, q.historyExpiresInDays)
        assertNull(q.historyExpiresCircleId)
    }

    @Test
    fun `no countdown at all leaves both fields null`() {
        // What a premium user, and a user who never had premium, both receive:
        // the server omits both fields.
        val q = parse(body(""))
        assertNull(q.historyExpiresInDays)
        assertNull(q.historyExpiresCircleId)
    }

    @Test
    fun `the zero-day countdown survives the parse`() {
        // The server clamps the countdown at zero, so the last day of the grace
        // window really does arrive as 0. Reading it as absent would hide the
        // warning on the one day it matters most.
        val q = parse(""","history_expires_in_days":0""".let { body(it) })
        assertEquals(0L, q.historyExpiresInDays)
    }
}
