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

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingKind
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.ServerTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Check-in rules on the wire (track migration 196): a row says what it watches, and a check-in
 * rule when it started; a row from an older server says neither and is a location rule. A new
 * location rule goes out as every older app's does, with no `watches`; a check-in rule says so.
 * Read with the app's plain Gson, which fills no Kotlin default.
 */
class CheckInRuleWireTest {

    private val gson = Gson()
    private val enc = """{"nonce":"n","ciphertext":"c"}"""

    private fun alertRow(extra: String) =
        """{"id":"a-1","user_id":"u-1","circle_id":"c-1","encrypted_target_user_id":$enc,"timeout_hours":4,""" +
            """"is_self_alert":false,"notify_push":true,"notify_email":false,"cooldown_minutes":60,"is_active":true,""" +
            """"last_triggered_day_bucket":null,"day_bucket":20000,"encrypted_conditions":null,"client_only":false$extra}"""

    @Test
    fun `an alert row from an older server is a location rule with no start`() {
        val row = gson.fromJson(alertRow(""), InactivityAlertResponse::class.java)
        assertNull(row.watches)
        assertNull(row.armedAt)
        assertTrue(InactivityWatches.isLocation(row.watches))
        assertFalse(InactivityWatches.isCheckIn(row.watches))
    }

    @Test
    fun `a check-in rule's row says so, with its start, fractions of a second as they come`() {
        val row = gson.fromJson(alertRow(""","watches":"check_in","armed_at":"2026-11-23T10:15:30.123456Z""""), InactivityAlertResponse::class.java)
        assertEquals("check_in", row.watches)
        assertTrue(InactivityWatches.isCheckIn(row.watches))
        assertEquals(java.time.Instant.parse("2026-11-23T10:15:30.123Z").toEpochMilli(), ServerTime.epochMs(row.armedAt))
        // Other spellings of the same moment.
        assertEquals(ServerTime.epochMs(row.armedAt), ServerTime.epochMs("2026-11-23T10:15:30.123+00:00"))
        assertEquals(java.time.Instant.parse("2026-11-23T10:15:30Z").toEpochMilli(), ServerTime.epochMs("2026-11-23T10:15:30Z"))
        val location = gson.fromJson(alertRow(""","watches":"location""""), InactivityAlertResponse::class.java)
        assertTrue(InactivityWatches.isLocation(location.watches))
        assertNull(location.armedAt)
    }

    @Test
    fun `a circle-mate's request, with and without its kind`() {
        val older = gson.fromJson("""{"id":"s-1","user_id":"u-2","circle_id":"c-1","timeout_hours":2}""", CircleSelfAlertDto::class.java)
        assertTrue(older.watchesLocation)
        assertFalse(older.watchesCheckIns)
        assertNull(older.armedAt)
        assertEquals(false, older.notifyOnReturn)

        val checkIn = gson.fromJson(
            """{"id":"s-2","user_id":"u-2","circle_id":"c-1","timeout_hours":2,"notify_on_return":false,""" +
                """"watches":"check_in","armed_at":"2026-11-23T10:15:30Z"}""",
            CircleSelfAlertDto::class.java,
        )
        assertTrue(checkIn.watchesCheckIns)
        assertFalse(checkIn.watchesLocation)
        assertEquals("2026-11-23T10:15:30Z", checkIn.armedAt)

        val unknown = checkIn.copy(watches = "geofence")
        assertFalse("judged by neither", unknown.watchesLocation || unknown.watchesCheckIns)
    }

    private fun create(watches: String?) = CreateInactivityAlertRequest(
        circleId = "c-1", targetUserId = "u-2", timeoutMinutes = 240, isSelfAlert = false, notifyPush = true,
        notifyEmail = false, notifyOnReturn = false, cooldownMinutes = 60, encryptedConditions = null, clientOnly = false,
        targetUserPseudonym = "p-1", encryptedTargetUserId = EncryptedPayload("n", "c"), dayBucket = 20000, watches = watches,
    )

    @Test
    fun `a new location rule goes out as an older app's does, and a check-in rule says what it watches`() {
        val location = JsonParser.parseString(gson.toJson(create(null))).asJsonObject
        assertFalse(location.has("watches"))
        val checkIn = JsonParser.parseString(gson.toJson(create(InactivityWatches.CHECK_IN))).asJsonObject
        assertEquals("check_in", checkIn.get("watches").asString)
        assertEquals("p-1", checkIn.get("target_user_pseudonym").asString)
    }

    @Test
    fun `a firing a device reported has no plain words`() {
        val row = gson.fromJson(
            """{"id":"e-1","alert_rule_id":"a-1","encrypted_user_id":$enc,"encrypted_target_user_id":$enc,"alert_type_hash":"",""" +
                """"encrypted_alert_type":$enc,"metadata":null,"day_bucket":20000,"encrypted_triggered_at":$enc,""" +
                """"acknowledged_day_bucket":null,"notified_push":false,"notified_email":false,"notified_sms":false}""",
            AlertEventResponse::class.java,
        )
        assertNull(row.message)
    }

    @Test
    fun `a check-in rule waiting to sync keeps its kind, and one kept from before is a location rule`() {
        fun change(inputs: String) = PendingChangeEntity(
            id = "ch-1", account = "me", circleId = "c-1", kind = PendingKind.INACTIVITY_ALERT.name, inputs = inputs,
            createdAt = 1, expiresAt = 2, state = "waiting",
        )
        val waiting = PendingInputs.InactivityAlert(
            "Family", "u-2", 240, false, true, false, false, 60, listOf("u-2"), mapOf("u-2" to "Bo"),
            watches = InactivityWatches.CHECK_IN,
        )
        assertEquals(waiting, PendingInputs.read(change(PendingInputs.toJson(waiting))))

        val before = JsonParser.parseString(PendingInputs.toJson(waiting)).asJsonObject.apply { remove("watches") }.toString()
        val read = PendingInputs.read(change(before)) as PendingInputs.InactivityAlert
        assertNull(read.watches)
    }
}
