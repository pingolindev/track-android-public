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
import com.pingolin.track.data.remote.dto.DataExportResponse
import com.pingolin.track.data.remote.dto.ExportMetadata
import com.pingolin.track.domain.repository.DecryptedCheckIn
import com.pingolin.track.domain.repository.DecryptedCircleMembership
import com.pingolin.track.domain.repository.DecryptedDataExport
import com.pingolin.track.domain.repository.DecryptedLocation
import com.pingolin.track.domain.repository.DecryptedPlace
import org.junit.Assert.*
import org.junit.Test

/**
 * The backup file Android writes and reads.
 *
 * The property under test throughout is that THE FILE IS THE SAME ONE THE WEB
 * WRITES — a backup taken on a phone has to restore on a laptop, or it is not a
 * backup, it is a device-local convenience. So these assert on the literal key
 * names in the JSON rather than on a round trip through this client's own
 * serialiser, which would pass just as happily on a file only Android can read.
 */
class TrackBackupFileTest {

    private fun metadata() = ExportMetadata(
        userId = "user-1", exportedDayBucket = 100, startDayBucket = 0, endDayBucket = 100,
        formatVersion = "1.2", locationCount = 0,
        devicesHasMore = false, circlesHasMore = false, placesHasMore = false,
        checkInsHasMore = false, locationsHasMore = false, alertsHasMore = false,
        alertRulesHasMore = false, inactivityAlertsHasMore = false, privacyHasMore = false,
        isComplete = true
    )

    private fun place(id: String = "11111111-1111-4111-8111-111111111111") = DecryptedPlace(
        id = id, circleId = null, name = "Home",
        latitude = 51.5, longitude = -0.12, radiusMeters = 150, createdDayBucket = 99
    )

    private fun raw(
        alerts: JsonArray? = null,
        privacy: JsonArray? = null,
        preferences: JsonObject? = null,
        blockedPeople: JsonArray? = null
    ) = DataExportResponse(
        metadata = metadata(), devices = null, circles = null, places = null,
        checkIns = null, locations = null,
        alerts = alerts, alertRules = null, inactivityAlerts = null,
        privacy = privacy, preferences = preferences, blockedPeople = blockedPeople
    )

    private fun location(
        insertedAtMs: Long = 1_755_000_000_000L,
        rowKey: String? = "8e0a1f3c5b7d9e21"
    ) = DecryptedLocation(
        deviceId = "device-1", circleId = "circle-1", dayBucket = 20_312,
        timestamp = 1_755_000_000_000L, latitude = 51.5, longitude = -0.12,
        accuracy = 12.5, keyId = "key-1", insertedAtMs = insertedAtMs, rowKey = rowKey
    )

    private fun built(
        raw: DataExportResponse = raw(),
        places: List<DecryptedPlace>? = listOf(place()),
        locations: List<DecryptedLocation>? = null
    ): JsonObject = TrackBackupFile.build(
        raw = raw,
        decrypted = DecryptedDataExport(
            metadata = raw.metadata, devices = null, circles = null,
            places = places, checkIns = null, locations = locations
        ),
        pagesFetched = 3,
        isComplete = true,
        generatedAt = "2026-08-12T00:00:00Z"
    )

    private fun writtenLocation(
        insertedAtMs: Long = 1_755_000_000_000L,
        rowKey: String? = "8e0a1f3c5b7d9e21"
    ): JsonObject =
        built(locations = listOf(location(insertedAtMs, rowKey)))
            .getAsJsonArray("locations")[0].asJsonObject

    // MARK: - Writing

    @Test
    fun `a place is written under the same key names the web writes`() {
        val p = built().getAsJsonArray("places")[0].asJsonObject

        // snake_case, and the location NESTED — not `radiusMeters` and not a
        // flat latitude/longitude, which is what handing this to Gson's field
        // mapping would have produced.
        assertEquals("11111111-1111-4111-8111-111111111111", p.get("id").asString)
        assertEquals("Home", p.get("name").asString)
        assertEquals(150, p.get("radius_meters").asInt)
        assertEquals(99, p.get("created_day_bucket").asInt)
        assertEquals(51.5, p.getAsJsonObject("location").get("latitude").asDouble, 0.0001)
        assertEquals(-0.12, p.getAsJsonObject("location").get("longitude").asDouble, 0.0001)
    }

    /**
     * The restore path drops a history point that has no `inserted_at_ms`, and the
     * endpoint behind it refuses one where the value is <= 0. A file without the
     * key would restore ZERO history points — not an error, not a warning, just a
     * number that comes back smaller than it went in. The value exists in the
     * export payload (`LocationExport.insertedAtMs`); the file has to carry it.
     */
    @Test
    fun `a location carries the insertion time a restore needs to identify it`() {
        val l = writtenLocation(insertedAtMs = 1_755_000_000_000L)

        assertTrue(
            "a history point without inserted_at_ms is dropped by the reader",
            l.has("inserted_at_ms"),
        )
        assertEquals(1_755_000_000_000L, l.get("inserted_at_ms").asLong)
    }

    /**
     * Additive. The other six keys are what every existing reader of this file
     * matches on, so `row_key` is only safe if nothing else changes.
     */
    @Test
    fun `the rest of a location row is written field for field`() {
        val l = writtenLocation()

        assertEquals("device-1", l.get("device_id").asString)
        assertEquals("circle-1", l.get("circle_id").asString)
        assertEquals(20_312, l.get("day_bucket").asInt)
        assertEquals("key-1", l.get("key_id").asString)
        assertEquals(1_755_000_000_000L, l.get("timestamp").asLong)
        // Nested, like the place row: the reader looks inside `location`.
        assertEquals(51.5, l.getAsJsonObject("location").get("latitude").asDouble, 0.0001)
        assertEquals(-0.12, l.getAsJsonObject("location").get("longitude").asDouble, 0.0001)
        assertEquals(12.5, l.getAsJsonObject("location").get("accuracy").asDouble, 0.0001)
    }

    /**
     * Restoring the same backup twice must not double the history. A dedupe on the
     * encrypted payload cannot fire: a restore re-seals every point with a fresh
     * nonce, so the ciphertext differs on every run of the same file. `row_key` is
     * computed over the ORIGINAL ciphertext, so it still names the source row after
     * the re-seal, and is identical on every restore. The export publishes it, and
     * this client's DTO reads it for the pager's cursor; the file has to carry it
     * too, as it carries `inserted_at_ms`.
     */
    @Test
    fun `a location carries the row key a repeated restore is recognised by`() {
        val l = writtenLocation(rowKey = "8e0a1f3c5b7d9e21")

        assertTrue(
            "without row_key a second restore of this file duplicates every point",
            l.has("row_key"),
        )
        assertEquals("8e0a1f3c5b7d9e21", l.get("row_key").asString)
    }

    /**
     * Absent, not blank, when the export did not supply one. The server reads a
     * missing key as "cannot dedupe, INSERT" — it would rather write a duplicate
     * than drop a real point — and it reaches that branch by testing the trimmed
     * value for emptiness, so a blank string is at best the same answer written
     * less honestly. Emitting the key with nothing in it also claims an identity
     * the row does not have.
     */
    @Test
    fun `an unknown row key is left out rather than written blank`() {
        assertFalse(
            "a null row key must not become an empty row_key",
            writtenLocation(rowKey = null).has("row_key"),
        )
        assertFalse(
            "a blank row key identifies nothing and must not be written",
            writtenLocation(rowKey = "   ").has("row_key"),
        )
        // Still a well-formed row otherwise — the dedupe is lost, not the point.
        val l = writtenLocation(rowKey = null)
        assertEquals("device-1", l.get("device_id").asString)
        assertEquals(1_755_000_000_000L, l.get("inserted_at_ms").asLong)
    }

    /**
     * An export that lacks the field leaves `insertedAtMs` at its 0
     * default, and 0 is not an answer — it claims an insertion time of the epoch.
     * Writing it would sail past the reader's `typeof === 'number'` check and then
     * be counted as `skipped` by an endpoint that refuses `inserted_at_ms <= 0`.
     * Leaving the key out refuses it one step earlier, for the same reason.
     */
    @Test
    fun `an unknown insertion time is left out rather than written as zero`() {
        val l = writtenLocation(insertedAtMs = 0L)

        assertFalse(
            "0 is not an insertion time; the restore endpoint rejects it either way",
            l.has("inserted_at_ms"),
        )
        // Still a well-formed row otherwise — the point is dropped, not the file.
        assertEquals("device-1", l.get("device_id").asString)
        assertEquals(20_312, l.get("day_bucket").asInt)
    }

    @Test
    fun `the envelope says what the file is and whether it is whole`() {
        val export = built().getAsJsonObject("export")

        assertEquals("pingolin-track", export.get("product").asString)
        assertEquals("1.2", export.get("format_version").asString)
        assertEquals(3, export.get("pages_fetched").asInt)
        // The one field that matters at restore time: a file that stopped early
        // looks exactly like a complete one without it.
        assertTrue(export.get("is_complete").asBoolean)
        assertEquals(1, export.getAsJsonObject("sections").get("places").asInt)
    }

    @Test
    fun `config sections are carried through untouched`() {
        val alerts = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("place_id", "p1")
                // A field this build does not model. It has to survive: the
                // sections are parsed as trees precisely so a newer server's
                // data is not quietly dropped on its way into a backup.
                addProperty("some_future_field", "keep me")
            })
        }
        val file = built(raw = raw(alerts = alerts))

        assertEquals(
            "keep me",
            file.getAsJsonArray("alerts")[0].asJsonObject.get("some_future_field").asString
        )
        assertEquals(1, file.getAsJsonObject("export").getAsJsonObject("sections").get("alerts").asInt)
    }

    /** Format 1.9: "Blocked people" reaches the file as the server served it, with its count. */
    @Test
    fun `blocked people are written to the file as they were served`() {
        val list = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("id", "b1")
                addProperty("blocked_email", "sam@example.test")
                addProperty("created_at_ms", 1_759_824_000_123L)
            })
        }
        val file = built(raw = raw(blockedPeople = list))

        assertEquals(list, file.getAsJsonArray("blocked_people"))
        assertEquals(1, file.getAsJsonObject("export").getAsJsonObject("sections").get("blocked_people").asInt)
        // A list of nobody is an empty section; a response without the section leaves none.
        val empty = built(raw = raw(blockedPeople = JsonArray()))
        assertEquals(0, empty.getAsJsonArray("blocked_people").size())
        assertEquals(0, empty.getAsJsonObject("export").getAsJsonObject("sections").get("blocked_people").asInt)
        val older = built(raw = raw())
        assertFalse(older.has("blocked_people"))
        assertFalse(older.getAsJsonObject("export").getAsJsonObject("sections").has("blocked_people"))
    }

    @Test
    fun `a section that was not requested is absent, not empty`() {
        // Absent means "not asked for"; an empty array means "asked, and there
        // is none". A restore reading the second as the first would report
        // nothing to restore for data that was never fetched.
        val file = built(places = null)
        assertFalse(file.has("places"))
        assertFalse(file.getAsJsonObject("export").getAsJsonObject("sections").has("places"))
    }

    // MARK: - key_unverified

    /**
     * A row only the export file's own key opened is the server's word, and says so the way the
     * web's file does: `"key_unverified": true`, a JSON BOOLEAN (the web's restore checks
     * `=== true`, so a string would restore). A row read with this device's keys carries no such
     * key at all, as the web leaves it out.
     */
    @Test
    fun `every row type only the file's key opened says key_unverified true, and no other row says anything`() {
        fun rows(unverified: Boolean): JsonObject {
            val raw = raw()
            return TrackBackupFile.build(
                raw = raw,
                decrypted = DecryptedDataExport(
                    metadata = raw.metadata, devices = null,
                    circles = listOf(DecryptedCircleMembership(
                        circleId = "22222222-2222-4222-8222-222222222222", name = "Family", role = "member",
                        keyVersion = 3, joinedDayBucket = 99, keyUnverified = unverified
                    )),
                    places = listOf(place().copy(keyUnverified = unverified)),
                    checkIns = listOf(DecryptedCheckIn(
                        id = "k-1", circleId = "22222222-2222-4222-8222-222222222222", checkInType = "general",
                        message = "home", latitude = null, longitude = null, createdDayBucket = 99,
                        keyUnverified = unverified
                    )),
                    locations = listOf(location().copy(keyUnverified = unverified))
                ),
                pagesFetched = 1,
                isComplete = true,
                generatedAt = "2026-09-29T00:00:00Z"
            )
        }
        val sections = listOf("circles", "places", "check_ins", "locations")

        val marked = rows(unverified = true)
        for (section in sections) {
            val mark = marked.getAsJsonArray(section)[0].asJsonObject.get("key_unverified")
            assertTrue("$section: a JSON boolean", mark != null && mark.isJsonPrimitive && mark.asJsonPrimitive.isBoolean)
            assertTrue("$section: true", mark.asBoolean)
        }

        val unmarked = rows(unverified = false)
        for (section in sections) {
            assertFalse("$section: no key at all", unmarked.getAsJsonArray(section)[0].asJsonObject.has("key_unverified"))
        }
    }

    // MARK: - Reading

    @Test
    fun `reads a file this client wrote`() {
        val text = built().toString()
        val result = TrackBackupFile.read(text)
        assertTrue(result is TrackBackupFile.ReadResult.Ok)
        assertEquals(
            "Home",
            (result as TrackBackupFile.ReadResult.Ok)
                .file.getAsJsonArray("places")[0].asJsonObject.get("name").asString
        )
    }

    @Test
    fun `reads an export written before the envelope existed`() {
        // Older exports have no `export` block at all. They are still perfectly
        // good backups and refusing them would strand the users who most need
        // this — the ones with a file from months ago.
        val old = JsonObject().apply {
            add("metadata", JsonObject().apply { addProperty("user_id", "u") })
            add("places", JsonArray())
        }
        assertTrue(TrackBackupFile.read(old.toString()) is TrackBackupFile.ReadResult.Ok)
    }

    @Test
    fun `refuses a file that is not a track export`() {
        // Silently finding zero places in someone's NOTE backup is worse than
        // telling them they picked the wrong file.
        val note = JsonObject().apply {
            addProperty("format", "pingolin-note-backup")
            addProperty("formatVersion", 2)
        }
        val result = TrackBackupFile.read(note.toString())
        assertTrue(result is TrackBackupFile.ReadResult.Failed)
        assertTrue((result as TrackBackupFile.ReadResult.Failed).message.contains("Track export"))
    }

    @Test
    fun `tells the user when the backup is sealed rather than calling it corrupt`() {
        // A sealed container is also JSON and arrives from the same picker, so
        // the reader has to branch on content.
        val sealed = JsonObject().apply {
            addProperty("format", "pingolin-encrypted-backup")
            addProperty("ciphertext", "x")
        }
        // Android can open a sealed file, so the honest answer is that a
        // passphrase is needed — and the caller
        // (SettingsViewModel.restoreBackup) prompts on exactly this.
        assertTrue(TrackBackupFile.read(sealed.toString()) is TrackBackupFile.ReadResult.NeedsPassphrase)
    }

    /** Detection must not need the native SDK: a plain-backup parse that loaded
     *  libpingolin_mobile.so made this whole class untestable on the JVM. */
    @Test
    fun `a sealed container is recognised without the native library`() {
        assertTrue(looksLikeSealedBackup("""{"format":"pingolin-encrypted-backup","ciphertext":"x"}"""))
        assertTrue(!looksLikeSealedBackup("""{"export":{"product":"pingolin-track"},"places":[]}"""))
        assertTrue(!looksLikeSealedBackup("not json"))
    }

    @Test
    fun `reports unreadable json instead of throwing`() {
        assertTrue(TrackBackupFile.read("{ not json") is TrackBackupFile.ReadResult.Failed)
        assertTrue(TrackBackupFile.read("[]") is TrackBackupFile.ReadResult.Failed)
    }

    @Test
    fun `surfaces an incomplete export before anything is restored`() {
        val file = JsonParser.parseString(built().toString()).asJsonObject
        assertTrue(TrackBackupFile.warnings(file).isEmpty())

        file.getAsJsonObject("export").addProperty("is_complete", false)
        file.getAsJsonObject("export").addProperty("incomplete_reason", "stopped after 500 pages")
        // The user has to be told BEFORE restoring: data that was never in the
        // file cannot come back, and a silent partial restore reads as a
        // complete one.
        assertEquals(listOf("stopped after 500 pages"), TrackBackupFile.warnings(file))
    }

    @Test
    fun `a uuid check that accepts only a real uuid`() {
        assertTrue(TrackBackupFile.isUuid("11111111-1111-4111-8111-111111111111"))
        assertFalse(TrackBackupFile.isUuid("not-a-uuid"))
        assertFalse(TrackBackupFile.isUuid(null))
        assertFalse(TrackBackupFile.isUuid(""))
    }
}
