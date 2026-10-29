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
import com.pingolin.track.domain.repository.DecryptedCheckIn
import com.pingolin.track.domain.repository.DecryptedCircleMembership
import com.pingolin.track.domain.repository.DecryptedDataExport
import com.pingolin.track.domain.repository.DecryptedDevice
import com.pingolin.track.domain.repository.DecryptedLocation
import com.pingolin.track.domain.repository.DecryptedPlace

/**
 * The Track backup file, as written and read on Android.
 *
 * THE FILE IS THE SAME ONE THE WEB WRITES. Not "similar" — the same key names
 * in the same shape, because the whole point of a backup is that it outlives
 * the device that made it. A backup taken here restores on the web and the
 * other way round. That is why every field below is spelled out by hand in
 * snake_case rather than handed to Gson's field-name mapping: Gson would happily
 * emit `radiusMeters` and produce a file only this client can read.
 *
 * Values are DECRYPTED. The file is the user's own plaintext data, which is why
 * the export offers to seal it under a passphrase, and why it is written to a
 * location the user picks rather than into app storage.
 *
 * WHAT IS NOT IN IT is deliberate and documented at each site: attachment-style
 * bulk (there is none here), circle keys, and the encrypted alert targets that
 * stay encrypted because the server is blind to who a rule watches and a
 * plaintext copy on disk would undo that.
 */
object TrackBackupFile {

    const val PRODUCT = "pingolin-track"

    /**
     * Build the file body.
     *
     * @param raw the merged pages, for the config sections that pass through
     *   untouched (they arrive as trees so a field this build predates is
     *   carried rather than dropped).
     * @param decrypted the same export with the encrypted fields opened.
     * @param pagesFetched how many requests it took, for the envelope.
     * @param isComplete whether the CLIENT finished paging. The server reports
     *   completeness per page; what matters in the file is whether the loop that
     *   wrote it ran to the end, because a file that stops early is the failure
     *   this whole mechanism exists to prevent — and it is only discovered at
     *   restore time, when it is too late.
     */
    fun build(
        raw: DataExportResponse,
        decrypted: DecryptedDataExport,
        pagesFetched: Int,
        isComplete: Boolean,
        generatedAt: String,
        incompleteReason: String? = null
    ): JsonObject {
        val root = JsonObject()

        val metadata = JsonObject().apply {
            addProperty("user_id", raw.metadata.userId)
            addProperty("exported_day_bucket", raw.metadata.exportedDayBucket)
            addProperty("start_day_bucket", raw.metadata.startDayBucket)
            addProperty("end_day_bucket", raw.metadata.endDayBucket)
            addProperty("format_version", raw.metadata.formatVersion)
            addProperty("location_count", raw.metadata.locationCount)
        }
        root.add("metadata", metadata)

        val sections = JsonObject()
        decrypted.devices?.let { list ->
            root.add("devices", JsonArray().apply { list.forEach { add(device(it)) } })
            sections.addProperty("devices", list.size)
        }
        decrypted.circles?.let { list ->
            root.add("circles", JsonArray().apply { list.forEach { add(circle(it)) } })
            sections.addProperty("circles", list.size)
        }
        decrypted.places?.let { list ->
            root.add("places", JsonArray().apply { list.forEach { add(place(it)) } })
            sections.addProperty("places", list.size)
        }
        decrypted.checkIns?.let { list ->
            root.add("check_ins", JsonArray().apply { list.forEach { add(checkIn(it)) } })
            sections.addProperty("check_ins", list.size)
        }
        decrypted.locations?.let { list ->
            root.add("locations", JsonArray().apply { list.forEach { add(location(it)) } })
            sections.addProperty("locations", list.size)
        }

        // Config sections pass through as they arrived. They are the part a
        // restore can actually put back, and they are already plaintext apart
        // from the encrypted target ids — which stay encrypted deliberately.
        //
        // Copied BY NAME, section by section: building the file from a fixed
        // list rather than by spreading the response is what stops a section
        // this build does not understand from leaking an unreadable blob into a
        // file the user believes is readable. Adding an export section means
        // adding it here too: a section left out here is missing from every
        // backup file.
        raw.alerts?.let { root.add("alerts", it); sections.addProperty("alerts", it.size()) }
        raw.alertRules?.let { root.add("alert_rules", it); sections.addProperty("alert_rules", it.size()) }
        raw.inactivityAlerts?.let {
            root.add("inactivity_alerts", it); sections.addProperty("inactivity_alerts", it.size())
        }
        raw.privacy?.let { root.add("privacy", it); sections.addProperty("privacy", it.size()) }
        raw.preferences?.let { root.add("preferences", it) }

        // Further sections, copied by name the same way. All are plaintext; the
        // boundary events keep their two encrypted fields sealed, because
        // Android has no place-key unwrap on this path and a blob labelled as
        // such is honest where a silently dropped section would not be.
        // place_shares keeps its wrapped key wrapped on purpose: that is key
        // material, not content.
        raw.profile?.let { root.add("profile", it); sections.addProperty("profile", 1) }
        raw.circleInvites?.let {
            root.add("circle_invites", it); sections.addProperty("circle_invites", it.size())
        }
        raw.alertEvents?.let {
            root.add("alert_events", it); sections.addProperty("alert_events", it.size())
        }
        raw.placeShares?.let {
            root.add("place_shares", it); sections.addProperty("place_shares", it.size())
        }
        raw.webPushSubscriptions?.let {
            root.add("web_push_subscriptions", it)
            sections.addProperty("web_push_subscriptions", it.size())
        }
        raw.boundaryEvents?.let {
            root.add("boundary_events", it); sections.addProperty("boundary_events", it.size())
        }
        raw.destinations?.let {
            root.add("destinations", it); sections.addProperty("destinations", it.size())
        }
        raw.circleNotifications?.let {
            root.add("circle_notifications", it); sections.addProperty("circle_notifications", it.size())
        }
        raw.notificationHistory?.let {
            root.add("notification_history", it); sections.addProperty("notification_history", it.size())
        }
        raw.blockedPeople?.let {
            // Format 1.9: the addresses blocked from inviting this account, as its own list shows them.
            root.add("blocked_people", it); sections.addProperty("blocked_people", it.size())
        }
        raw.circleActivity?.let {
            root.add("circle_activity", it); sections.addProperty("circle_activity", it.size())
        }

        root.add("export", JsonObject().apply {
            addProperty("format_version", raw.metadata.formatVersion)
            addProperty("product", PRODUCT)
            addProperty("generated_at", generatedAt)
            addProperty("generated_by", "android")
            addProperty("pages_fetched", pagesFetched)
            addProperty("is_complete", isComplete)
            add("sections", sections)
            if (incompleteReason != null) addProperty("incomplete_reason", incompleteReason)
        })

        return root
    }

    private fun device(d: DecryptedDevice) = JsonObject().apply {
        addProperty("id", d.id)
        addProperty("name", d.name)
        addProperty("device_type", d.platform)
        addProperty("day_bucket", d.registeredDayBucket)
    }

    private fun circle(c: DecryptedCircleMembership) = JsonObject().apply {
        addProperty("circle_id", c.circleId)
        addProperty("name", c.name)
        addProperty("role", c.role)
        addProperty("key_version", c.keyVersion)
        addProperty("joined_day_bucket", c.joinedDayBucket)
        markUnverified(c.keyUnverified)
    }

    /**
     * `"key_unverified": true` on a row only the export file's own key opened: the server's
     * word, since the export's key wraps carry no signer. A JSON boolean, the field every
     * client's restore reads; left out otherwise.
     */
    private fun JsonObject.markUnverified(unverified: Boolean) {
        if (unverified) addProperty("key_unverified", true)
    }

    private fun place(p: DecryptedPlace) = JsonObject().apply {
        addProperty("id", p.id)
        addProperty("circle_id", p.circleId)
        addProperty("name", p.name)
        // Nested, matching web: the location is whatever JSON the writer sealed,
        // and a restore reads latitude/longitude back out of it.
        add("location", JsonObject().apply {
            addProperty("latitude", p.latitude)
            addProperty("longitude", p.longitude)
        })
        addProperty("radius_meters", p.radiusMeters)
        addProperty("created_day_bucket", p.createdDayBucket)
        markUnverified(p.keyUnverified)
    }

    private fun checkIn(c: DecryptedCheckIn) = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("circle_id", c.circleId)
        addProperty("check_in_type", c.checkInType)
        c.message?.let { addProperty("message", it) }
        if (c.latitude != null && c.longitude != null) {
            add("location", JsonObject().apply {
                addProperty("latitude", c.latitude)
                addProperty("longitude", c.longitude)
            })
        }
        addProperty("created_day_bucket", c.createdDayBucket)
        markUnverified(c.keyUnverified)
    }

    private fun location(l: DecryptedLocation) = JsonObject().apply {
        // Written for format stability, NOT for its value. The server's export
        // names the device by `device_pseudonym`, which this client's DTO does
        // not read (it reads `device_id`), so what lands here is null, as in the
        // iOS app's export. The key stays because every reader expects the
        // shape, and no reader uses it. Renaming it is a format decision for
        // all three clients at once.
        addProperty("device_id", l.deviceId)
        addProperty("circle_id", l.circleId)
        addProperty("day_bucket", l.dayBucket)
        addProperty("key_id", l.keyId)
        l.timestamp?.let { addProperty("timestamp", it) }
        // The server's original insertion time, under the name the export publishes
        // it as. A history point is only restorable WITH it: the restore path drops
        // any row where it is missing, and the endpoint refuses one where it is <= 0,
        // because it is what makes a restored row identifiable as the same row rather
        // than a duplicate. A file without it restores no history, silently; only the
        // reader says so.
        //
        // Omitted rather than zeroed when we do not have it (an export from an older
        // server that lacks the field): a 0 would claim an insertion time of the
        // epoch, pass the reader's type check, and then be counted as `skipped`
        // server-side. An absent key is refused by the reader for the same reason the
        // endpoint would refuse the value, which is the honest answer.
        if (l.insertedAtMs > 0) addProperty("inserted_at_ms", l.insertedAtMs)
        // What keeps a SECOND restore of the same file from doubling the history.
        // The restore re-seals every point with a fresh nonce before sending it, so
        // the ciphertext the server receives is different every time and can never
        // identify a repeat; this key is computed over the row's ORIGINAL ciphertext
        // — which the export threw away when it decrypted — so it still names the
        // source row, and it is the same value on every restore of this file.
        //
        // Omitted, never blank, when we do not have it: the server reads an absent
        // key as "cannot dedupe, INSERT", which errs toward a duplicate point rather
        // than toward silently dropping a real one.
        l.rowKey?.takeIf { it.isNotBlank() }?.let { addProperty("row_key", it) }
        add("location", JsonObject().apply {
            addProperty("latitude", l.latitude)
            addProperty("longitude", l.longitude)
            l.accuracy?.let { addProperty("accuracy", it) }
        })
        markUnverified(l.keyUnverified)
    }

    // ===== Reading =====

    sealed class ReadResult {
        /** The file is a sealed container: ask for the passphrase and retry. */
        object NeedsPassphrase : ReadResult()
        data class Ok(val file: JsonObject) : ReadResult()
        data class Failed(val message: String) : ReadResult()
    }

    /**
     * Parse and sanity-check a backup file.
     *
     * Strict about the envelope, forgiving about the contents. The file is a
     * decrypted dump with no guarantees beyond what the writer put in it, so a
     * missing field degrades to "skip that row" rather than "reject the file".
     * The one hard refusal is a file that is not a Track export at all: silently
     * finding zero places in someone's NOTE backup is worse than saying so.
     */
    fun read(text: String): ReadResult {
        val parsed = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return ReadResult.Failed("Couldn't read that file: ${e.message}")
        }
        if (!parsed.isJsonObject) return ReadResult.Failed(NOT_A_BACKUP)
        val file = parsed.asJsonObject

        // A sealed container is also JSON and comes from the same file picker,
        // so branch on the content rather than on the name.
        // A sealed container reaches here only if the caller did not unseal it
        // first — SettingsViewModel.restoreBackup does, given a passphrase — so
        // this means "we need the passphrase".
        if (file.stringOrNull("format") == "pingolin-encrypted-backup") {
            return ReadResult.NeedsPassphrase
        }

        // Exports written before the `export` envelope existed are still valid
        // backups, so accept a file that merely looks like one.
        val looksLikeTrack =
            file.getAsJsonObject("export")?.stringOrNull("product") == PRODUCT ||
                file.getAsJsonObject("metadata")?.stringOrNull("user_id") != null ||
                file.get("places")?.isJsonArray == true
        if (!looksLikeTrack) return ReadResult.Failed(NOT_A_BACKUP)

        return ReadResult.Ok(file)
    }

    /** What the file itself admits it is missing, for the user to see up front. */
    fun warnings(file: JsonObject): List<String> {
        val export = file.getAsJsonObject("export") ?: return emptyList()
        val complete = export.get("is_complete")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        if (complete) return emptyList()
        return listOf(
            export.stringOrNull("incomplete_reason")
                ?: "This export is incomplete — it was cut short when it was written, so some data was never in the file."
        )
    }

    private const val NOT_A_BACKUP = "That file isn't a Pingolin Track export."

    internal fun JsonObject.stringOrNull(field: String): String? =
        get(field)?.takeIf { it.isJsonPrimitive }?.asString

    internal fun JsonObject.doubleOrNull(field: String): Double? =
        get(field)?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asDouble } catch (e: Exception) { null }
        }

    internal fun JsonObject.intOrNull(field: String): Int? =
        get(field)?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asInt } catch (e: Exception) { null }
        }

    internal fun JsonObject.boolOrNull(field: String): Boolean? =
        get(field)?.takeIf { it.isJsonPrimitive }?.let {
            try { it.asBoolean } catch (e: Exception) { null }
        }

    internal fun JsonObject.objOrNull(field: String): JsonObject? =
        get(field)?.takeIf { it.isJsonObject }?.asJsonObject

    internal fun JsonObject.arrayOrEmpty(field: String): JsonArray =
        get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private val UUID_RE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isUuid(s: String?): Boolean = s != null && UUID_RE.matches(s)
}

/**
 * Whether a picked file is a sealed container, decided WITHOUT the native SDK.
 *
 * The crypto belongs in one shared place — that is the whole point of the SDK
 * module — but the detection is a single field compare, and doing it through
 * the binding would make every plain-backup parse load
 * `libpingolin_mobile.so`. On a JVM unit test there is no such library, so a
 * pure-parsing class would be untestable; on a device it is wasted work on the
 * common path. Detect here, decrypt there.
 */
fun looksLikeSealedBackup(text: String): Boolean = runCatching {
    val root = com.google.gson.JsonParser.parseString(text)
    root.isJsonObject &&
        root.asJsonObject.get("format")?.takeIf { it.isJsonPrimitive }?.asString ==
        "pingolin-encrypted-backup"
}.getOrDefault(false)
