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
import com.pingolin.track.data.remote.dto.CreatePlaceAlertRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.UpdatePlaceAlertRequest
import com.pingolin.track.domain.placealerts.PlaceAlertRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * A time-windowed place alert is judged by TWO detectors, and they must agree on
 * what time it is.
 *
 * The subscriber's own device evaluates crossings locally in
 * `ZoneId.systemDefault()` (`PlaceAlertRules.isWithinSchedule`). The server
 * evaluates the same alert in the timezone the client sends, and in UTC without
 * one: a subscriber in America/Los_Angeles who asked for "alert me 08:00-17:00"
 * would get the server-routed push between 00:00 and 09:00 local, with
 * `active_days` off by a day either side of midnight, while the locally-detected
 * crossings for the identical alert arrive correctly. One alert behaving two ways
 * depending on which detector saw the crossing reads as flakiness rather than as
 * something with a cause.
 *
 * These tests pin the wire name and the value: the field is a plain string the
 * server reads, there is no client behaviour to assert beyond "it is sent,
 * spelled this way, with this value".
 *
 * Uses the same default Gson the app's Retrofit converter builds
 * (`di/AppModule.kt`: `GsonConverterFactory.create()`), including its default of
 * omitting null fields.
 */
class PlaceAlertTimezoneContractTest {

    private val gson = GsonBuilder().create()

    private fun encode(request: Any): JsonObject =
        JsonParser.parseString(gson.toJson(request)).asJsonObject

    private fun create(timezone: String? = PlaceAlertRules.deviceTimezoneId()) =
        CreatePlaceAlertRequest(
            placeId = "place-1",
            circleId = "circle-1",
            targetUserId = "user-1",
            encryptedTargetUserId = EncryptedPayload(nonce = "n", ciphertext = "c"),
            alertOnArrival = true,
            alertOnDeparture = false,
            activeDays = listOf("monday", "tuesday"),
            activeStartTime = "08:00",
            activeEndTime = "17:00",
            timezone = timezone,
            notifyPush = true,
            notifyEmail = false,
            notifySms = false,
            isRecurring = true,
            expiresAt = null,
            dayBucket = 20_000,
        )

    private fun update(timezone: String? = PlaceAlertRules.deviceTimezoneId()) =
        UpdatePlaceAlertRequest(
            alertOnArrival = true,
            alertOnDeparture = false,
            activeDays = listOf("monday", "tuesday"),
            activeStartTime = "08:00",
            activeEndTime = "17:00",
            timezone = timezone,
            notifyPush = true,
            notifyEmail = false,
            notifySms = false,
            isRecurring = true,
            isActive = true,
            expiresAt = null,
        )

    /**
     * The key is `timezone` — not `tz`, not `time_zone`. The server silently drops
     * what it does not recognise, so a misspelling here would ship as a no-op that
     * looks exactly like success.
     */
    @Test
    fun `create sends the device zone under the column's own name`() {
        val json = encode(create(timezone = "America/Los_Angeles"))
        assertTrue("the server can only read a key it is given", json.has("timezone"))
        assertEquals("America/Los_Angeles", json.get("timezone").asString)
    }

    /**
     * An edit re-sends it rather than leaving whatever was stored at creation. A
     * subscriber who moves zones and retimes their window is otherwise still judged
     * in the zone they first set the alert in — the same split-brain, just slower to
     * appear.
     */
    @Test
    fun `update sends the device zone too`() {
        val json = encode(update(timezone = "Europe/Berlin"))
        assertTrue(json.has("timezone"))
        assertEquals("Europe/Berlin", json.get("timezone").asString)
    }

    /**
     * What the repository actually passes. Asserting the seam's own output — rather
     * than a hardcoded zone — is what makes this a test of the shipped value: the
     * three call sites in `PlaceRepository` all read this one function.
     */
    @Test
    fun `the value sent is the zone this device evaluates schedules in`() {
        assertEquals(ZoneId.systemDefault().id, PlaceAlertRules.deviceTimezoneId())
        assertEquals(
            PlaceAlertRules.deviceTimezoneId(),
            encode(create()).get("timezone").asString,
        )
        assertEquals(
            PlaceAlertRules.deviceTimezoneId(),
            encode(update()).get("timezone").asString,
        )
    }

    /**
     * A real IANA id, which is what the server-side worker will have to parse. A
     * device reporting a fixed offset ("GMT+02:00") or the literal "SystemV/..."
     * would still be a string, and still be wrong on the far side of a DST change.
     */
    @Test
    fun `the device zone is an id the server can resolve`() {
        val id = PlaceAlertRules.deviceTimezoneId()
        assertTrue(
            "a zone id the JVM cannot resolve is one the worker cannot either: $id",
            ZoneId.getAvailableZoneIds().contains(id) || id == "Z" || id == "UTC",
        )
    }

    /**
     * Additive: the field is optional, so a server that does not know it ignores
     * it and this reaches such a server as a harmless no-op. That only holds while
     * the fields the server DOES read arrive unchanged, so this asserts them.
     */
    @Test
    fun `adding the zone leaves the rest of both payloads untouched`() {
        val created = encode(create(timezone = "America/Los_Angeles"))
        assertEquals("place-1", created.get("place_id").asString)
        assertEquals("circle-1", created.get("circle_id").asString)
        assertEquals("user-1", created.get("target_user_id").asString)
        assertTrue(created.get("alert_on_arrival").asBoolean)
        assertFalse(created.get("alert_on_departure").asBoolean)
        assertEquals("08:00", created.get("active_start_time").asString)
        assertEquals("17:00", created.get("active_end_time").asString)
        assertEquals(2, created.getAsJsonArray("active_days").size())
        assertEquals(20_000, created.get("day_bucket").asInt)

        val updated = encode(update(timezone = "America/Los_Angeles"))
        assertTrue(updated.get("is_active").asBoolean)
        assertEquals("08:00", updated.get("active_start_time").asString)
        assertEquals("17:00", updated.get("active_end_time").asString)
        assertEquals(2, updated.getAsJsonArray("active_days").size())
    }

    /**
     * The field stays optional on the wire. Gson omits nulls, so a caller that does
     * not set it sends no key at all rather than a literal `"timezone": null` — which
     * a server storing it in a NOT NULL column (default UTC) would have to reject or
     * special-case. Nothing in the app takes this path; it is here so that
     * remains true by construction rather than by luck.
     */
    @Test
    fun `an unset zone is absent rather than null`() {
        assertFalse(encode(create(timezone = null)).has("timezone"))
        assertFalse(encode(update(timezone = null)).has("timezone"))
    }
}
