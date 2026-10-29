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

package com.pingolin.track.domain.backup

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.AlreadyExistsException
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.PrivacySettingsResponse
import com.pingolin.track.data.remote.dto.UpdatePrivacyRequest
import com.pingolin.track.domain.repository.PlaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Putting a backup back on Android.
 *
 * The decisions worth a test are the ones that separate a restore that helps
 * from one that makes a mess: it keeps the original id so a repeat converges
 * rather than doubling the account, it counts a 409 as "already there" rather
 * than as a failure, it refuses rows it cannot read instead of creating junk,
 * and it never silently drops the sections it does not put back.
 */
class RestoreRepositoryTest {

    private val api: TrackApi = mockk(relaxed = true)
    private val places: PlaceRepository = mockk(relaxed = true)
    private lateinit var repo: RestoreRepository

    private val placeId = "11111111-1111-4111-8111-111111111111"
    private val circleId = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        repo = RestoreRepository(api, places)
        coEvery { places.refreshPlaces(any()) } returns Result.success(emptyList())
        coEvery { places.getAllPlaces() } returns emptyList()
        coEvery { places.refreshPlaceAlerts() } returns Result.success(emptyList())
        coEvery { places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(placeEntity())
    }

    private fun placeEntity(id: String = placeId) = PlaceEntity(
        id = id, circleId = "self", name = "Home", notes = null, address = null,
        latitude = 51.5, longitude = -0.12, radius = 150, icon = null, color = null,
        placeType = "place", boundaryRadius = null, createdAt = 0, updatedAt = 0
    )

    private fun file(build: JsonObject.() -> Unit = {}): JsonObject =
        JsonObject().apply {
            add("export", JsonObject().apply { addProperty("product", "pingolin-track") })
            build()
        }

    private fun placeJson(
        id: String? = placeId,
        name: String? = "Home",
        lat: Double? = 51.5,
        lng: Double? = -0.12,
        circle: String? = null
    ) = JsonObject().apply {
        id?.let { addProperty("id", it) }
        name?.let { addProperty("name", it) }
        circle?.let { addProperty("circle_id", it) }
        if (lat != null && lng != null) {
            add("location", JsonObject().apply {
                addProperty("latitude", lat); addProperty("longitude", lng)
            })
        }
        addProperty("radius_meters", 150)
    }

    private fun withPlaces(vararg rows: JsonObject) = file {
        add("places", JsonArray().apply { rows.forEach { add(it) } })
    }

    // MARK: - Places

    @Test
    fun `a restored place keeps its original id`() = runTest {
        val id = slot<String>()
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), capture(id))
        } returns Result.success(placeEntity())

        val summary = repo.restore(withPlaces(placeJson()))

        // This is what makes restoring the same file twice converge instead of
        // doubling the account: the server's insert does nothing on conflict.
        assertEquals(placeId, id.captured)
        assertEquals(1, summary.placesRestored)
    }

    @Test
    fun `a 409 on a place this account owns counts as already there, not as a failure`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(AlreadyExistsException("A place with that id already exists"))

        val summary = repo.restore(withPlaces(placeJson()))

        // Without this a clean second restore reports as a total failure, which
        // is the reading a user would act on.
        assertEquals(1, summary.placesAlreadyPresent)
        assertEquals(0, summary.placesSkipped)
        assertEquals(0, summary.placesRestored)
    }

    @Test
    fun `a malformed id is dropped rather than sent`() = runTest {
        val id = slot<String?>()
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(id))
        } returns Result.success(placeEntity())

        repo.restore(withPlaces(placeJson(id = "not-a-uuid")))

        // A bad id would be rejected and LOSE the place; dropping it only costs
        // idempotence for that one row.
        assertNull(id.captured)
    }

    @Test
    fun `a place the export could not decrypt is skipped, not restored as junk`() = runTest {
        val summary = repo.restore(withPlaces(
            placeJson(name = "[encrypted - key unavailable]"),
            placeJson(lat = null, lng = null),
            placeJson(name = "")
        ))

        // Restoring these creates entries with no readable name or a location
        // at null island, which the user then has to find and delete by hand.
        assertEquals(3, summary.placesSkipped)
        assertEquals(0, summary.placesRestored)
        coVerify(exactly = 0) {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a circle place is restored as personal, and the summary says so`() = runTest {
        val summary = repo.restore(withPlaces(placeJson(circle = circleId)))

        // Re-creating it inside the circle needs that circle to still exist AND
        // this device to hold its key. Guess wrong and the place is one nobody
        // can decrypt; a personal place the user can re-share is strictly better.
        assertEquals(1, summary.placesRestored)
        assertTrue(summary.notes.any { it.contains("personal places") })
    }

    @Test
    fun `one bad row does not abandon the rest`() = runTest {
        var call = 0
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            call++
            if (call == 1) Result.failure(Exception("network")) else Result.success(placeEntity())
        }

        val summary = repo.restore(withPlaces(placeJson(), placeJson(id = null), placeJson()))

        // A restore that stops halfway leaves an account in a state nobody chose.
        assertEquals(2, summary.placesRestored)
        assertEquals(1, summary.placesSkipped)
    }

    // MARK: - Places only the export's own key opened

    private val otherPlaceId = "33333333-3333-4333-8333-333333333333"

    /** Every id a restore handed to createPersonalPlace, in order. */
    private fun restoredIds(): MutableList<String?> {
        val ids = mutableListOf<String?>()
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(ids))
        } returns Result.success(placeEntity())
        return ids
    }

    @Test
    fun `a place the file marks key_unverified is never restored, and is counted on its own`() = runTest {
        val ids = restoredIds()

        val summary = repo.restore(withPlaces(
            // A circle place, readable in every other way: the mark alone keeps it out.
            placeJson(circle = circleId).apply { addProperty("key_unverified", true) },
            placeJson(id = otherPlaceId)
        ))

        // The mark says only the export's own key, the server's word, opened the row. Creating it
        // would make a place the server chose into one of the user's own.
        assertEquals(listOf<String?>(otherPlaceId), ids)
        assertEquals(1, summary.placesUnverified)
        assertEquals(1, summary.placesRestored)
        assertEquals("not counted as unreadable", 0, summary.placesSkipped)
        assertFalse("nothing moved out of a circle", summary.notes.any { it.contains("personal places") })
    }

    @Test
    fun `a place marked false, or not marked, restores like any other`() = runTest {
        val ids = restoredIds()

        val summary = repo.restore(withPlaces(
            placeJson().apply { addProperty("key_unverified", false) },
            placeJson(id = otherPlaceId)
        ))

        assertEquals(listOf<String?>(placeId, otherPlaceId), ids)
        assertEquals(2, summary.placesRestored)
        assertEquals(0, summary.placesUnverified)
    }

    @Test
    fun `a marked place that is also unreadable counts as unverified, as the web counts it`() = runTest {
        val summary = repo.restore(withPlaces(
            placeJson(name = "[unavailable]").apply { addProperty("key_unverified", true) }
        ))

        assertEquals(1, summary.placesUnverified)
        assertEquals(0, summary.placesSkipped)
    }

    @Test
    fun `the string true is a mark too`() = runTest {
        val ids = restoredIds()

        // Only an edited file writes a string; skipping it only skips more.
        val summary = repo.restore(withPlaces(placeJson().apply { addProperty("key_unverified", "true") }))

        assertTrue(ids.isEmpty())
        assertEquals(1, summary.placesUnverified)
    }

    // MARK: - Privacy

    private fun livePrivacy() = PrivacySettingsResponse(
        circleId = circleId, sharingMode = "exact", shareRealtime = true, shareHistory = true,
        shareBattery = true, pausedUntil = null, primaryDeviceId = null
    )

    private fun withPrivacy(row: JsonObject) = file {
        add("privacy", JsonArray().apply { add(row) })
    }

    private fun privacyRow(build: JsonObject.() -> Unit = {}) = JsonObject().apply {
        addProperty("circle_id", circleId)
        addProperty("sharing_mode", "exact")
        addProperty("share_realtime", true)
        addProperty("share_history", true)
        build()
    }

    private fun liveIs(settings: PrivacySettingsResponse?, status: Int = 200) {
        coEvery { api.getPrivacySettings(circleId) } returns
            if (status == 200) Response.success(ApiResponse(success = true, data = settings, error = null))
            else Response.error(status, "{}".toResponseBody(null))
    }

    @Test
    fun `a restore never loosens a live privacy row - nothing is sent over it`() = runTest {
        // Whatever the member turned down after the backup stays down.
        val live = listOf(
            livePrivacy().copy(sharingMode = "paused", pausedUntil = null),
            livePrivacy().copy(shareHistory = false),
            livePrivacy().copy(shareRealtime = false),
            livePrivacy().copy(sharingMode = "approximate", approximateRadiusMiles = 5),
            livePrivacy().copy(allowAlertsOnMe = false),
            // The battery switch too.
            livePrivacy().copy(shareBattery = false),
        )
        for (settings in live) {
            liveIs(settings)

            val summary = repo.restore(withPrivacy(privacyRow { addProperty("allow_alerts_on_me", true) }))

            assertEquals(1, summary.privacyKept)
            assertEquals(0, summary.privacyRestored)
            assertEquals(0, summary.privacySkipped)
        }
        coVerify(exactly = 0) { api.updatePrivacySettings(any(), any()) }
        coVerify(exactly = 0) { api.createPrivacySettings(any(), any(), any()) }
    }

    @Test
    fun `a circle with no row gets the file's, what the file doesn't carry on the strict side, only if none is made meanwhile`() = runTest {
        liveIs(null, status = 404)
        val sent = mutableListOf<UpdatePrivacyRequest>()
        coEvery { api.createPrivacySettings(circleId, capture(sent), true) } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.PrivacyWritten(true), error = null))

        val bare = repo.restore(withPrivacy(privacyRow { addProperty("sharing_mode", "approximate"); addProperty("approximate_radius_miles", 3) }))
        val whole = repo.restore(withPrivacy(privacyRow {
            addProperty("share_battery", true)
            addProperty("allow_alerts_on_me", true)
            addProperty("paused_until", "2026-10-01T00:00:00Z")
        }))

        // A file that has history and the battery off restores them off.
        val off = repo.restore(withPrivacy(privacyRow {
            addProperty("share_history", false)
            addProperty("share_battery", false)
        }))

        assertEquals(1, bare.privacyRestored)
        assertEquals(1, whole.privacyRestored)
        assertEquals(1, off.privacyRestored)
        with(sent[0]) {
            assertEquals("approximate", sharingMode)
            assertEquals(3, approximateRadiusMiles)
            assertFalse("the file doesn't say: none", shareBattery)
            assertFalse("the file doesn't say: nobody's alerts on them", allowAlertsOnMe)
            assertFalse(showAllDevices)
            assertNull(primaryDeviceId)
            assertNull(primaryDevicePseudonym)
        }
        with(sent[1]) {
            assertTrue(shareBattery)
            assertTrue(allowAlertsOnMe)
            assertEquals("2026-10-01T00:00:00Z", pausedUntil)
        }
        with(sent[2]) {
            assertFalse("history off in the file stays off", shareHistory)
            assertFalse(shareBattery)
        }
        coVerify(exactly = 0) { api.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `a row made between the read and the write stands, and a server that doesn't say is never sent the plain PUT`() = runTest {
        liveIs(null, status = 404)
        coEvery { api.createPrivacySettings(circleId, any(), true) } returnsMany listOf(
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.PrivacyWritten(false), error = null)),
            Response.success(ApiResponse(success = true, data = null, error = null)),
        )

        val raced = repo.restore(withPrivacy(privacyRow()))
        val older = repo.restore(withPrivacy(privacyRow()))

        assertEquals(1, raced.privacyKept)
        assertEquals(0, raced.privacyRestored)
        assertEquals(1, older.privacyRestored)
        coVerify(exactly = 0) { api.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `a 403, any other answer, or a row missing what every export writes is skipped`() = runTest {
        for (status in listOf(403, 500)) {
            liveIs(null, status = status)
            assertEquals(1, repo.restore(withPrivacy(privacyRow())).privacySkipped)
        }
        liveIs(null, status = 404)
        for (missing in listOf("circle_id", "sharing_mode", "share_realtime", "share_history")) {
            assertEquals(missing, 1, repo.restore(withPrivacy(privacyRow { remove(missing) })).privacySkipped)
        }
        coVerify(exactly = 0) { api.createPrivacySettings(any(), any(), any()) }
        coVerify(exactly = 0) { api.updatePrivacySettings(any(), any()) }
    }

    @Test
    fun `a circle the user has left is counted, not treated as an error`() = runTest {
        coEvery { api.getPrivacySettings(circleId) } throws RuntimeException("403")

        val summary = repo.restore(withPrivacy(privacyRow()))

        // Their membership cannot be restored from a file either, so this is
        // expected rather than a failure.
        assertEquals(1, summary.privacySkipped)
        assertTrue(summary.notes.any { it.contains("no longer in") })
    }

    // MARK: - Alerts

    private fun withAlert(placeIdValue: String = placeId, target: String? = null) = file {
        add("alerts", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("place_id", placeIdValue)
                target?.let { addProperty("target_user_id", it) }
                addProperty("alert_on_arrival", true)
                addProperty("alert_on_departure", true)
            })
        })
    }

    @Test
    fun `an alert is restored only once its place is back`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        coEvery {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("alert-1")

        val summary = repo.restore(withAlert())

        assertEquals(1, summary.alertsRestored)
    }

    @Test
    fun `an alert whose place is gone is skipped rather than created invisible`() = runTest {
        coEvery { places.getAllPlaces() } returns emptyList()

        val summary = repo.restore(withAlert())

        assertEquals(1, summary.alertsSkipped)
        assertEquals(0, summary.alertsRestored)
        coVerify(exactly = 0) {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `an alert that already exists is not created a second time`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        coEvery { places.refreshPlaceAlerts() } returns Result.success(listOf(
            com.pingolin.track.data.local.entity.PlaceAlertEntity(
                id = "existing", placeId = placeId, circleId = null, userId = "me",
                targetUserId = "all", alertOnArrival = true, alertOnDeparture = false,
                activeDays = null, activeStartTime = null, activeEndTime = null,
                isOvernight = false, notifyPush = true, notifyEmail = false,
                notifySms = false, isRecurring = false, isActive = true,
                expiresAt = null, dayBucket = 0
            )
        ))

        val summary = repo.restore(withAlert())

        // Idempotence here is by NATURAL KEY — (place, target) — because the
        // endpoint mints its own id. Creating it again would fire twice.
        assertEquals(1, summary.alertsSkipped)
        assertEquals(0, summary.alertsRestored)
    }

    // MARK: - Someone else's place id

    private val freshId = "44444444-4444-4444-8444-444444444444"

    /** The place's first create answers [first]; a create without an id makes [freshId]. Every id asked, in order. */
    private fun firstCreateAnswers(first: Throwable): MutableList<String?> {
        val ids = mutableListOf<String?>()
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(ids))
        } answers { if (ids.last() == null) Result.success(placeEntity(freshId)) else Result.failure(first) }
        coEvery {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success("alert-1")
        return ids
    }

    private fun withPlaceAndAlert() = withAlert().apply { add("places", JsonArray().apply { add(placeJson()) }) }

    private fun alertPlaced(): String? {
        val placed = mutableListOf<String>()
        coVerify(atLeast = 0, atMost = 1) {
            places.createPlaceAlert(any(), capture(placed), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        return placed.singleOrNull()
    }

    @Test
    fun `a 409 on a place this account doesn't own goes back under a fresh id, its alert following`() = runTest {
        val ids = firstCreateAnswers(AlreadyExistsException("A place with that id already exists"))

        val summary = repo.restore(withPlaceAndAlert())

        assertEquals(listOf(placeId, null), ids)
        assertEquals(1, summary.placesRestored)
        assertEquals(0, summary.placesAlreadyPresent)
        assertEquals(1, summary.alertsRestored)
        assertEquals(freshId, alertPlaced())
    }

    // The server's refusals exactly as it sends them. Each goes through the app's own parse,
    // ApiErrors.toException, which is what createPersonalPlace returns on a refused create. They
    // are never hand-made exceptions: those can only agree with the app's own spelling, and
    // "PLACE_ID_TAKEN" would pass here while the server says "place_id_taken".

    /** The server's refusal for a place id that belongs to someone else, byte for byte. */
    private val placeIdTakenBody =
        """{"success":false,"error":"One or more places do not belong to you","error_code":"place_id_taken"}"""

    /** The server's refusal for a place id that already exists, byte for byte. */
    private val placeAlreadyExistsBody =
        """{"success":false,"error":"A place with that id already exists","error_code":"place_already_exists"}"""

    private fun serverRefusal(status: Int, body: String): Exception =
        com.pingolin.track.data.remote.dto.ApiErrors.toException(
            Response.error<Any>(status, body.toResponseBody(null)),
            "Couldn't save the place just now."
        )

    @Test
    fun `the server's 403 place_id_taken, in its own bytes, goes back under a fresh id with its alert`() = runTest {
        val ids = firstCreateAnswers(serverRefusal(403, placeIdTakenBody))

        val summary = repo.restore(withPlaceAndAlert())

        assertEquals(listOf(placeId, null), ids)
        assertEquals(1, summary.placesRestored)
        assertEquals(0, summary.placesSkipped)
        assertEquals(0, summary.placesAlreadyPresent)
        assertEquals(1, summary.alertsRestored)
        assertEquals(freshId, alertPlaced())
    }

    @Test
    fun `any other 403, with another code or none, is not restored and gets no alert`() = runTest {
        for (body in listOf(
            """{"success":false,"error":"Forbidden","error_code":"auth_insufficient_permissions"}""",
            """{"success":false,"error":"Forbidden"}""",
        )) {
            val ids = firstCreateAnswers(serverRefusal(403, body))

            val summary = repo.restore(withPlaceAndAlert())

            assertEquals(body, listOf(placeId), ids)
            assertEquals(body, 1, summary.placesSkipped)
            assertEquals(body, 0, summary.placesRestored)
            assertEquals(body, 1, summary.alertsSkipped)
        }
        coVerify(exactly = 0) {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `a 403 place_id_taken is never already there, even on an id this account's list says it owns`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        val ids = firstCreateAnswers(serverRefusal(403, placeIdTakenBody))

        val summary = repo.restore(withPlaceAndAlert())

        // The server decided on its primary that the id is someone else's; a list read earlier can't
        // overrule that. The place goes back under a fresh id, and the alert onto that one.
        assertEquals(listOf(placeId, null), ids)
        assertEquals(0, summary.placesAlreadyPresent)
        assertEquals(1, summary.placesRestored)
        assertEquals(freshId, alertPlaced())
    }

    @Test
    fun `when the fresh create fails too, the place is skipped and nothing goes onto the taken id`() = runTest {
        val ids = mutableListOf<String?>()
        coEvery {
            places.createPersonalPlace(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), captureNullable(ids))
        } answers {
            if (ids.last() == null) Result.failure(serverRefusal(500, """{"success":false,"error":"Failed to create place"}"""))
            else Result.failure(serverRefusal(403, placeIdTakenBody))
        }

        val summary = repo.restore(withPlaceAndAlert())

        assertEquals(listOf(placeId, null), ids)
        assertEquals(1, summary.placesSkipped)
        assertEquals(0, summary.placesRestored)
        assertEquals(1, summary.alertsSkipped)
        coVerify(exactly = 0) {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `the server's 409, in its own bytes, on a place this account owns is already there`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        val ids = firstCreateAnswers(serverRefusal(409, placeAlreadyExistsBody))

        val summary = repo.restore(withPlaceAndAlert())

        assertEquals(listOf(placeId), ids)
        assertEquals(1, summary.placesAlreadyPresent)
        assertEquals(0, summary.placesRestored)
        assertEquals(placeId, alertPlaced())
    }

    @Test
    fun `while what this account owns can't be read, a 409 is never made twice and no alert goes onto it`() = runTest {
        coEvery { places.refreshPlaces(any()) } returns Result.failure(Exception("offline"))
        coEvery { places.getAllPlaces() } returns listOf(placeEntity())
        val ids = firstCreateAnswers(AlreadyExistsException("A place with that id already exists"))

        val summary = repo.restore(withPlaceAndAlert())

        assertEquals(listOf(placeId), ids)
        assertEquals(1, summary.placesSkipped)
        assertEquals(0, summary.placesAlreadyPresent)
        assertEquals(1, summary.alertsSkipped)
        assertNull(alertPlaced())
    }

    @Test
    fun `an alert never goes onto a place shared into a circle`() = runTest {
        coEvery { places.getAllPlaces() } returns listOf(placeEntity().copy(isOwner = false, wrapViaCircle = circleId))

        val summary = repo.restore(withAlert())

        assertEquals(1, summary.alertsSkipped)
        coVerify(exactly = 0) {
            places.createPlaceAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // MARK: - What it does not restore

    @Test
    fun `every section it cannot put back is named, never silently dropped`() = runTest {
        val summary = repo.restore(file {
            add("alert_rules", JsonArray().apply { add(JsonObject()) })
            add("inactivity_alerts", JsonArray().apply { add(JsonObject()) })
            add("locations", JsonArray().apply { add(JsonObject()); add(JsonObject()) })
            add("check_ins", JsonArray().apply { add(JsonObject()) })
            add("circles", JsonArray().apply { add(JsonObject()) })
        })

        // A restore that returns quietly invites the assumption that everything
        // in the file came back. Each of these is data the user can SEE in
        // their backup and would otherwise expect to find in the account.
        val notes = summary.notes.joinToString("\n")
        assertTrue(notes.contains("2 condition/inactivity rule(s)"))
        assertTrue(notes.contains("2 location history point(s)"))
        assertTrue(notes.contains("Check-ins"))
        assertTrue(notes.contains("Circle membership"))
    }

    @Test
    fun `an incomplete backup warns before anything is written`() = runTest {
        val f = JsonParser.parseString(
            """{"export":{"product":"pingolin-track","is_complete":false,
                 "incomplete_reason":"stopped after 500 pages"}}"""
        ).asJsonObject

        assertEquals("stopped after 500 pages", repo.restore(f).notes.first())
    }

    @Test
    fun `preferences are applied as one put, and the geocoding blob is left alone`() = runTest {
        val sent = slot<Map<String, Any>>()
        coEvery { api.updateNotificationPreferences(capture(sent)) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))

        val summary = repo.restore(file {
            add("preferences", JsonObject().apply {
                add("notifications", JsonObject().apply {
                    addProperty("push_enabled", true)
                    addProperty("quiet_hours_start", "22:00")
                })
                // Sealed under a key this account may no longer hold. Writing
                // back a blob we cannot read leaves settings that never decrypt.
                add("encrypted_geocoding", JsonObject().apply {
                    addProperty("nonce", "n"); addProperty("ciphertext", "c")
                })
            })
        })

        assertTrue(summary.preferencesRestored)
        assertEquals(true, sent.captured["push_enabled"])
        assertEquals("22:00", sent.captured["quiet_hours_start"])
        assertFalse(sent.captured.containsKey("encrypted_geocoding"))
    }
}
