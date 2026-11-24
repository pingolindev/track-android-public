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
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.CheckInScheduleSetting
import com.pingolin.track.util.InactivityWatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * One-time and scheduled check-in rules on the wire (track migration 197), read and written with
 * the app's plain Gson, which fills no Kotlin default and leaves every null field out: a row's kind
 * and schedule; a new rule's; an edit's whole schedule (from time left out = the start of the day,
 * follow-the-phone always said); a follow-the-phone update's zone alone.
 */
class ScheduledCheckInWireTest {

    private val gson = Gson()
    private val enc = """{"nonce":"n","ciphertext":"c"}"""

    private fun alertRow(extra: String) =
        """{"id":"a-1","user_id":"u-1","circle_id":"c-1","encrypted_target_user_id":$enc,"timeout_hours":1,""" +
            """"is_self_alert":true,"notify_push":true,"notify_email":false,"cooldown_minutes":60,"is_active":true,""" +
            """"last_triggered_day_bucket":null,"day_bucket":20000,"encrypted_conditions":null,"client_only":false,""" +
            """"watches":"check_in","armed_at":"2026-11-24T10:00:00Z"$extra}"""

    private fun json(value: Any) = JsonParser.parseString(gson.toJson(value)).asJsonObject

    @Test
    fun `a scheduled rule's row, with its schedule`() {
        val row = gson.fromJson(
            alertRow(
                ""","check_in_mode":"scheduled","schedule_days":31,"schedule_due_minute":1260,"schedule_from_minute":1080,""" +
                    """"schedule_tz":"America/New_York","schedule_tz_follows_phone":true""",
            ),
            InactivityAlertResponse::class.java,
        )
        assertEquals(CheckInModes.SCHEDULED, row.checkInMode)
        assertEquals(CheckInSchedule.WEEKDAYS, row.scheduleDays)
        assertEquals(21 * 60, row.scheduleDueMinute)
        assertEquals(18 * 60, row.scheduleFromMinute)
        assertEquals("America/New_York", row.scheduleTz)
        assertEquals(true, row.scheduleTzFollowsPhone)
    }

    @Test
    fun `a one-time rule's row says its kind and no schedule, and an older server's says neither`() {
        val once = gson.fromJson(alertRow(""","check_in_mode":"once""""), InactivityAlertResponse::class.java)
        assertEquals(CheckInModes.ONCE, once.checkInMode)
        assertNull(once.scheduleDays)
        assertNull(once.scheduleTzFollowsPhone)
        val older = gson.fromJson(alertRow(""), InactivityAlertResponse::class.java)
        assertNull(older.checkInMode)
        assertFalse("one-time, as the server reads it", CheckInModes.isScheduled(older.checkInMode))
    }

    @Test
    fun `a circle-mate's scheduled request carries its schedule`() {
        val request = gson.fromJson(
            """{"id":"s-1","user_id":"u-2","circle_id":"c-1","timeout_hours":1,"watches":"check_in","armed_at":"2026-11-24T10:00:00Z",""" +
                """"check_in_mode":"scheduled","schedule_days":127,"schedule_due_minute":540,"schedule_tz":"Europe/Berlin",""" +
                """"schedule_tz_follows_phone":true}""",
            CircleSelfAlertDto::class.java,
        )
        assertEquals(CheckInSchedule(CheckInSchedule.EVERY_DAY, 9 * 60, null, ZoneId.of("Europe/Berlin")), CheckInSchedule.of(request))
        assertNull("one-time", CheckInSchedule.of(request.copy(checkInMode = CheckInModes.ONCE)))
        assertNull("a location request", CheckInSchedule.of(request.copy(watches = "location")))
    }

    private fun create(mode: String?, days: Int? = null, from: Int? = null, follows: Boolean? = null) = CreateInactivityAlertRequest(
        circleId = "c-1", targetUserId = "u-2", timeoutMinutes = 60, isSelfAlert = true, notifyPush = true,
        notifyEmail = false, notifyOnReturn = false, cooldownMinutes = 60, encryptedConditions = null, clientOnly = false,
        targetUserPseudonym = "p-1", encryptedTargetUserId = EncryptedPayload("n", "c"), dayBucket = 20000,
        watches = InactivityWatches.CHECK_IN, checkInMode = mode, scheduleDays = days, scheduleDueMinute = days?.let { 21 * 60 },
        scheduleFromMinute = from, scheduleTz = days?.let { "Asia/Tokyo" }, scheduleTzFollowsPhone = follows,
    )

    @Test
    fun `a new scheduled rule sends its schedule, a from time only when it has one`() {
        val scheduled = json(create(CheckInModes.SCHEDULED, days = CheckInSchedule.EVERY_DAY, follows = true))
        assertEquals("scheduled", scheduled.get("check_in_mode").asString)
        assertEquals(127, scheduled.get("schedule_days").asInt)
        assertEquals(1260, scheduled.get("schedule_due_minute").asInt)
        assertFalse("the start of the day", scheduled.has("schedule_from_minute"))
        assertEquals("Asia/Tokyo", scheduled.get("schedule_tz").asString)
        assertEquals(true, scheduled.get("schedule_tz_follows_phone").asBoolean)
        assertEquals("ignored, but within the server's range", 60, scheduled.get("timeout_minutes").asInt)
        assertEquals(1080, json(create(CheckInModes.SCHEDULED, days = 1, from = 1080)).get("schedule_from_minute").asInt)

        val once = json(create(CheckInModes.ONCE))
        assertEquals("once", once.get("check_in_mode").asString)
        assertFalse(once.keySet().any { it.startsWith("schedule_") })
    }

    @Test
    fun `an edit sends the schedule whole, and following the phone the zone alone`() {
        val whole = json(
            UpdateInactivityAlertRequest(
                timeoutMinutes = null, isActive = null, notifyPush = true, notifyEmail = false, notifyOnReturn = null,
                cooldownMinutes = null, encryptedConditions = null, scheduleDays = 31, scheduleDueMinute = 1260,
                scheduleFromMinute = null, scheduleTz = "Europe/Berlin", scheduleTzFollowsPhone = false,
            )
        )
        assertEquals(setOf("notify_push", "notify_email", "schedule_days", "schedule_due_minute", "schedule_tz", "schedule_tz_follows_phone"), whole.keySet())
        assertEquals("said, or the server would take it for false anyway", false, whole.get("schedule_tz_follows_phone").asBoolean)

        val zone = json(
            UpdateInactivityAlertRequest(
                timeoutMinutes = null, isActive = null, notifyPush = null, notifyEmail = null, notifyOnReturn = null,
                cooldownMinutes = null, encryptedConditions = null, scheduleTz = "Asia/Tokyo",
            )
        )
        assertEquals("""{"schedule_tz":"Asia/Tokyo"}""", zone.toString())
    }

    @Test
    fun `a scheduled rule waiting to sync keeps its schedule, and one kept from before is one-time`() {
        fun change(inputs: String) = PendingChangeEntity(
            id = "ch-1", account = "me", circleId = "c-1", kind = PendingKind.INACTIVITY_ALERT.name, inputs = inputs,
            createdAt = 1, expiresAt = 2, state = "waiting",
        )
        val waiting = PendingInputs.InactivityAlert(
            "Family", "me", 60, true, true, false, false, 60, emptyList(), emptyMap(),
            watches = InactivityWatches.CHECK_IN,
            schedule = CheckInScheduleSetting(CheckInSchedule.WEEKDAYS, 21 * 60, 18 * 60, "Europe/Berlin", followsPhone = true),
        )
        val stored = PendingInputs.toJson(waiting)
        assertEquals(waiting, PendingInputs.read(change(stored)))
        val schedule = JsonParser.parseString(stored).asJsonObject.getAsJsonObject("check_in_schedule")
        assertEquals(setOf("days", "due_minute", "from_minute", "tz", "follows_phone"), schedule.keySet())

        val before = JsonParser.parseString(stored).asJsonObject.apply { remove("check_in_schedule") }.toString()
        assertNull((PendingInputs.read(change(before)) as PendingInputs.InactivityAlert).schedule)
    }
}
