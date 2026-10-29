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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.CheckInExport
import com.pingolin.track.data.remote.dto.DataExportResponse
import com.pingolin.track.data.remote.dto.ExportMetadata
import com.pingolin.track.domain.backup.TrackBackupFile
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * A check-in that belongs to no circle must survive the export.
 *
 * `check_ins.circle_id` is nullable server-side, two different ways: the
 * check-in was made personally, outside any circle, or its circle was later
 * deleted (the server then sets it to null), so an account that merely
 * OUTLIVED a circle has these rows too.
 *
 * A non-null `CheckInExport.circleId` would protect nothing: Gson writes
 * straight through Kotlin's nullability, so the null arrives anyway and goes
 * into `hasCircleKey`, whose UniFFI binding declares a non-null `String`. The
 * parameter intrinsic throws, `decryptCheckIn`'s own
 * `catch (e: Exception) { null }` swallows it, and `exportAndDecryptData`'s
 * `mapNotNull` drops the row.
 *
 * SO THE FAILURE IS SILENT. No crash, no error, no warning — a backup file
 * that looks complete and is missing check-ins.
 *
 * `decryptPlace` has the same fallback for a personal PLACE; the two branches
 * must stay symmetric.
 */
class ExportCirclelessCheckInTest {

    /** Not imported: `ExportRepository.SELF_CIRCLE_UUID` is private. Spelled out
     *  so the test pins the value the file and the key store actually use. */
    private val selfCircle = "00000000-0000-0000-0000-000000000000"
    private val realCircle = "c1111111-1111-4111-8111-111111111111"

    private val api: TrackApi = mockk()
    private val crypto: CryptoManager = mockk(relaxed = true)
    private val repo = ExportRepository(api, crypto, mockk())

    /** This device's own keys only: no export file store. */
    private val liveOnly = ExportRepository.ExportKeys(crypto, null, emptySet())

    /**
     * Stubbed on the EXACT circle id, never `any()`.
     *
     * That is what makes these tests bite: with `any()` a change that passes
     * a null through would still match and still return true, and the row would
     * come back looking fine. Matching the specific id means an unresolved null
     * falls to the relaxed default of `false`, `decryptCheckIn` returns null,
     * and the row disappears — which is exactly the failure in the app.
     */
    private fun holdsKeysFor(vararg circleIds: String) {
        circleIds.forEach { id ->
            every { crypto.hasCircleKey(id) } returns true
            every { crypto.decryptDataV2(id, any(), "message") } returns "made it home"
        }
    }

    /**
     * Built by GSON from wire JSON, not by the Kotlin constructor.
     *
     * That is the whole point. Constructing `CheckInExport(circleId = null)`
     * directly only compiles because the field is nullable, so such a test
     * could not catch a non-null declaration. Production never uses the
     * constructor: Retrofit hands the body to Gson, which writes null into the
     * field whatever Kotlin declares — which is exactly how a null gets past a
     * type that says it cannot.
     */
    private fun checkIn(id: String, circleId: String?): CheckInExport =
        Gson().fromJson(
            JsonObject().apply {
                addProperty("id", id)
                addProperty("circle_id", circleId)
                addProperty("check_in_type", "safe_arrival")
                add("encrypted_message", JsonObject().apply {
                    addProperty("nonce", "n")
                    addProperty("ciphertext", "ct")
                })
                addProperty("created_day_bucket", 20_001)
            },
            CheckInExport::class.java
        )

    @Test
    fun `a circle-less check-in is decrypted under the self key rather than dropped`() {
        holdsKeysFor(selfCircle)

        val decrypted = repo.decryptCheckIn(checkIn("k-personal", circleId = null), liveOnly)

        assertNotNull(
            "a check-in with no circle must not be dropped — that is a backup that looks complete",
            decrypted
        )
        // The FILE records no circle. The self-circle UUID is the decryption
        // domain, not a circle this check-in belongs to, and writing it here
        // would invent a membership in the user's own backup.
        assertNull(decrypted!!.circleId)
        // Content, not just presence: a row that survives with its message lost
        // is the same data loss one layer down.
        assertEquals("made it home", decrypted.message)
        assertEquals("safe_arrival", decrypted.checkInType)
    }

    @Test
    fun `a check-in in a circle still uses its own circle key`() {
        holdsKeysFor(realCircle)

        val decrypted = repo.decryptCheckIn(checkIn("k-circle", circleId = realCircle), liveOnly)

        assertNotNull(decrypted)
        assertEquals(realCircle, decrypted!!.circleId)
        assertEquals("made it home", decrypted.message)
    }

    /**
     * The other half: the row survived, so now the FILE has to say what it is.
     * Absent would read to a restore as "this export predates the field"; null
     * says "this check-in belongs to no circle".
     */
    @Test
    fun `the file writes circle_id null rather than omitting it`() {
        val metadata = ExportMetadata(
            userId = "user-1", exportedDayBucket = 100, startDayBucket = 0, endDayBucket = 100,
            formatVersion = "1.8", locationCount = 0,
            devicesHasMore = false, circlesHasMore = false, placesHasMore = false,
            checkInsHasMore = false, locationsHasMore = false, alertsHasMore = false,
            alertRulesHasMore = false, inactivityAlertsHasMore = false, privacyHasMore = false,
            isComplete = true
        )
        val raw = DataExportResponse(
            metadata = metadata, devices = null, circles = null, places = null,
            checkIns = null, locations = null
        )
        val file = TrackBackupFile.build(
            raw = raw,
            decrypted = DecryptedDataExport(
                metadata = metadata, devices = null, circles = null, places = null,
                checkIns = listOf(
                    DecryptedCheckIn(
                        id = "k-personal", circleId = null, checkInType = "safe_arrival",
                        message = null, latitude = null, longitude = null, createdDayBucket = 20_001
                    ),
                    DecryptedCheckIn(
                        id = "k-circle", circleId = realCircle, checkInType = "general",
                        message = null, latitude = null, longitude = null, createdDayBucket = 20_000
                    )
                ),
                locations = null
            ),
            pagesFetched = 1,
            isComplete = true,
            generatedAt = "2026-08-24T00:00:00Z"
        )

        val rows = file.getAsJsonArray("check_ins")
        assertEquals(2, rows.size())

        val personal = rows.first { it.asJsonObject.get("id").asString == "k-personal" }.asJsonObject
        assertTrue(
            "null, not absent and not a substituted UUID",
            personal.has("circle_id") && personal.get("circle_id").isJsonNull
        )

        val inCircle = rows.first { it.asJsonObject.get("id").asString == "k-circle" }.asJsonObject
        assertEquals(realCircle, inCircle.get("circle_id").asString)
    }
}
