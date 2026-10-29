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

import android.util.Log
import com.google.gson.JsonObject
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.AlreadyExistsException
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.data.remote.dto.UpdatePrivacyRequest
import com.pingolin.track.domain.backup.TrackBackupFile.arrayOrEmpty
import com.pingolin.track.domain.backup.TrackBackupFile.boolOrNull
import com.pingolin.track.domain.backup.TrackBackupFile.doubleOrNull
import com.pingolin.track.domain.backup.TrackBackupFile.intOrNull
import com.pingolin.track.domain.backup.TrackBackupFile.objOrNull
import com.pingolin.track.domain.backup.TrackBackupFile.stringOrNull
import com.pingolin.track.domain.repository.PlaceRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Restoring a Track backup on Android.
 *
 * This keeps the same order, the same idempotence rules and the same list of
 * things it deliberately does not do as the other clients' restore, so a
 * backup restores the same way whichever client the user happens to have.
 *
 * ORDER MATTERS: places, then privacy and preferences, then alerts. An alert
 * points at a `place_id`, and because places restore under their ORIGINAL ids
 * that reference resolves — but only once the place is actually back.
 *
 * TWO KINDS OF IDEMPOTENCE, for two kinds of endpoint:
 *   - Places take a client-supplied id, so a repeat collides and answers 409,
 *     which is counted as "already there" rather than a failure — but only for
 *     a place this account OWNS here. An id that is someone else's (a 409 on a
 *     place not owned here, or the server's 403 `place_id_taken`) is put back
 *     under a fresh id, and its alerts follow it.
 *   - Everything else mints its own id, so a repeat is avoided by NATURAL KEY:
 *     an alert for the same (place, target) is skipped, and a privacy row the
 *     account already has is kept whole. That is also the better semantics —
 *     someone who changed an alert or a sharing setting since the backup keeps
 *     their change, because a restore fills gaps rather than winding an account
 *     back, and never loosens what a member shares.
 *
 * WHAT IT DOES NOT RESTORE, and why:
 *   - **Circle membership.** It carries a circle key wrapped for you by another
 *     member; no file can recreate that. Rejoining is an invite flow.
 *   - **Check-ins.** A record of something that happened at a time, not
 *     configuration. Re-posting would manufacture events that did not occur.
 *   - **Location history.** The server has a dedicated endpoint for it
 *     (personal history only, deliberately), but this restore does not drive
 *     it, and the summary says so rather than leaving the user to assume it
 *     came back.
 *   - **Condition and inactivity rules.** Both keep their target ENCRYPTED
 *     while both create endpoints want the plaintext for a consent check, so a
 *     restore has to decrypt with the circle key first. The count is reported
 *     here rather than silently dropped.
 *   - **Places only the export's own key opened** (`"key_unverified": true`, which
 *     every client's export writes): the server's word, never made real.
 */
@Singleton
class RestoreRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val placeRepository: PlaceRepository
) {

    data class Summary(
        var placesRestored: Int = 0,
        var placesAlreadyPresent: Int = 0,
        var placesSkipped: Int = 0,
        /** Places the file marks as read only with the export's own key: never restored. */
        var placesUnverified: Int = 0,
        var alertsRestored: Int = 0,
        var alertsSkipped: Int = 0,
        var privacyRestored: Int = 0,
        /** Circles whose live privacy row was kept as it is: a restore never loosens one. */
        var privacyKept: Int = 0,
        var privacySkipped: Int = 0,
        var preferencesRestored: Boolean = false,
        /** Things the file admitted it was missing, plus what this chose not to touch. */
        val notes: MutableList<String> = mutableListOf()
    ) {
        val totalRestored: Int get() = placesRestored + alertsRestored + privacyRestored
    }

    /**
     * Put back everything this client knows how to put back.
     *
     * Never throws for one bad row: a restore that stops halfway through leaves
     * an account in a state nobody chose. Every failure is counted and reported.
     */
    suspend fun restore(file: JsonObject): Summary {
        val summary = Summary(notes = TrackBackupFile.warnings(file).toMutableList())

        val placed = restorePlaces(file, summary, ownedPlaces())
        restorePrivacy(file, summary)
        restorePreferences(file, summary)
        restoreAlerts(file, summary, placed)

        // Say plainly what did not come back, every time. A restore that
        // quietly returns without mentioning these invites the assumption that
        // they did.
        val rules = file.arrayOrEmpty("alert_rules").size() +
            file.arrayOrEmpty("inactivity_alerts").size()
        if (rules > 0) {
            summary.notes.add(
                "$rules condition/inactivity rule(s) were in the backup and were not restored here — " +
                    "restore them from the web."
            )
        }
        val history = file.arrayOrEmpty("locations").size()
        if (history > 0) {
            summary.notes.add(
                "$history location history point(s) were in the backup and were not restored — " +
                    "putting history back is available on the web."
            )
        }
        if (file.arrayOrEmpty("check_ins").size() > 0) {
            summary.notes.add(
                "Check-ins are a record of something that happened, not a setting, so they are not restored."
            )
        }
        if (file.arrayOrEmpty("circles").size() > 0) {
            summary.notes.add(
                "Circle membership can't be restored from a file. Ask a member of the circle for a new invitation."
            )
        }

        return summary
    }

    /**
     * The places this account owns here, read fresh before any goes in; null when they can't be
     * read. A 409 is "already there" only for one of these, and an alert goes only onto one.
     */
    private suspend fun ownedPlaces(): Set<String>? {
        placeRepository.refreshPlaces(null).getOrNull() ?: return null
        return placeRepository.getAllPlaces().filter { it.isOwner }.mapTo(HashSet()) { it.id }
    }

    /** Where the place step left the file's places: each one put back here, by its id in the file. */
    private class Placed(
        /** The file's place id → the place it is here now: the same id, or the fresh one it went back under. */
        val ids: Map<String, String>,
        /** Every place this account owns here after the place step. */
        val owned: Set<String>,
    )

    /**
     * Every place is re-created as PERSONAL, even if the backup says it belonged
     * to a circle. Re-creating it inside the circle would need that circle to
     * still exist AND this device to hold its key — neither of which a restore
     * can assume, and the failure if it guesses wrong is a place nobody can
     * decrypt. A personal place the user can see and re-share is strictly
     * better than an unreadable one, so the summary says how many moved.
     */
    private suspend fun restorePlaces(file: JsonObject, summary: Summary, owned: Set<String>?): Placed {
        var movedOutOfCircle = 0
        val ids = linkedMapOf<String, String>()

        for (element in file.arrayOrEmpty("places")) {
            val p = element.takeIf { it.isJsonObject }?.asJsonObject ?: run {
                summary.placesSkipped++; continue
            }
            // A place the file says only the export's OWN key opened is the server's word: the
            // export's key wraps carry no signer, so whoever serves the export can seal a place
            // under a key of its choosing. Never made real here, whatever else the row says.
            // Checked first, and counted on its own.
            if (p.boolOrNull("key_unverified") == true) {
                summary.placesUnverified++
                continue
            }
            val name = p.stringOrNull("name")
            val location = p.objOrNull("location")
            val lat = location?.doubleOrNull("latitude")
            val lng = location?.doubleOrNull("longitude")

            // A place with no readable name or coordinates is not a place. This
            // is the shape a file takes when it was written without the key —
            // the writer records "[unavailable]" rather than failing — and
            // restoring that creates junk the user must delete. Files written
            // by older versions say "[encrypted - key unavailable]" instead,
            // so both prefixes are skipped.
            if (name.isNullOrBlank() ||
                name.startsWith("[encrypted") || name.startsWith("[unavailable") ||
                lat == null || lng == null || !lat.isFinite() || !lng.isFinite()
            ) {
                summary.placesSkipped++
                continue
            }

            if (p.stringOrNull("circle_id") != null) movedOutOfCircle++

            // A malformed id is worse than none: it would be rejected and lose the place, where
            // dropping it only costs idempotence for that one row.
            val id = p.stringOrNull("id")?.takeIf { TrackBackupFile.isUuid(it) }
            suspend fun create(restoreId: String?) = placeRepository.createPersonalPlace(
                name = name,
                latitude = lat,
                longitude = lng,
                radius = p.intOrNull("radius_meters") ?: DEFAULT_RADIUS_METERS,
                restoreId = restoreId
            )

            val first = create(id)
            first.getOrNull()?.let { place ->
                summary.placesRestored++
                id?.let { ids[it] = place.id }
            }
            val failure = first.exceptionOrNull() ?: continue
            when {
                // Already there, and this account's own: the same place.
                failure is AlreadyExistsException && id != null && owned != null && id in owned -> {
                    summary.placesAlreadyPresent++
                    ids[id] = id
                }
                // Whose it is can't be told now: never made twice, and its alerts aren't put back.
                failure is AlreadyExistsException && owned == null -> summary.placesSkipped++
                // Someone else's id: put back under a fresh one, its alerts following it.
                id != null && (failure is AlreadyExistsException || failure.placeIdTaken()) -> {
                    create(null).fold(
                        onSuccess = { place ->
                            summary.placesRestored++
                            ids[id] = place.id
                        },
                        onFailure = { summary.placesSkipped++ }
                    )
                }
                else -> summary.placesSkipped++
            }
        }

        if (summary.placesUnverified > 0) {
            Log.w(TAG, "restore: ${summary.placesUnverified} place(s) in the file were read only with the export's own key: not restored")
        }
        if (movedOutOfCircle > 0) {
            summary.notes.add(
                "$movedOutOfCircle place(s) belonged to a circle and were restored as personal places. " +
                    "Share them with the circle again to put them back."
            )
        }
        if (summary.placesSkipped > 0) {
            summary.notes.add(
                "${summary.placesSkipped} place(s) couldn't be restored — no readable name or location in the file."
            )
        }
        val moved = ids.count { (from, to) -> from != to }
        if (moved > 0) Log.i(TAG, "restore: $moved place(s) whose id was someone else's went back under a fresh id")
        return Placed(ids, owned.orEmpty() + ids.values)
    }

    /** The server's 403 for a place id that isn't this account's. */
    private fun Throwable.placeIdTaken(): Boolean = (this as? RefusedException)?.let { it.status == 403 && it.errorCode == PLACE_ID_TAKEN } == true

    /**
     * Per-circle privacy: a restore fills gaps only, and never loosens what a member shares now.
     * A circle whose row the account has keeps it whole: nothing is sent, so a pause, an
     * approximate location, or history or real time turned off after the backup stays as it
     * is. Only where the account has no row (the server's 404) does the file's go in, as the
     * create-only PUT, and what the file doesn't carry is taken on the strict side: no battery,
     * nobody's alerts on them, one device shown and none pinned (a missing row is the loosest
     * default there is, so anything the file says is at least as tight). The read comes first,
     * so an older server without the create-only PUT (which upserts) is sent a row only where
     * there was none; on one that has it, a row made between the read and the write is never
     * overwritten. A 403 (not a member) or any other answer is skipped.
     *
     * An expired pause or bubble is not resurrected — the export omits deadlines
     * that have passed, so restoring cannot re-pause sharing the user resumed.
     */
    private suspend fun restorePrivacy(file: JsonObject, summary: Summary) {
        for (element in file.arrayOrEmpty("privacy")) {
            val row = element.takeIf { it.isJsonObject }?.asJsonObject ?: run {
                summary.privacySkipped++; continue
            }
            val circleId = row.stringOrNull("circle_id")
            val sharingMode = row.stringOrNull("sharing_mode")
            val shareRealtime = row.boolOrNull("share_realtime")
            val shareHistory = row.boolOrNull("share_history")
            // Every export writes these three: a row without one is no privacy row.
            if (circleId == null || sharingMode == null || shareRealtime == null || shareHistory == null) {
                summary.privacySkipped++; continue
            }

            try {
                // Commonly a circle the user is no longer in (a 403). Their membership
                // cannot be restored from a file either, so this is expected
                // rather than an error.
                val current = tracksApi.getPrivacySettings(circleId)
                if (current.isSuccessful && current.body()?.data != null) {
                    summary.privacyKept++
                    continue
                }
                if (current.code() != 404) { summary.privacySkipped++; continue }

                val response = tracksApi.createPrivacySettings(
                    circleId,
                    UpdatePrivacyRequest(
                        sharingMode = sharingMode,
                        shareRealtime = shareRealtime,
                        shareHistory = shareHistory,
                        shareBattery = row.boolOrNull("share_battery") ?: false,
                        approximateRadiusMiles = row.intOrNull("approximate_radius_miles"),
                        pausedUntil = row.stringOrNull("paused_until"),
                        approximateUntil = row.stringOrNull("approximate_until"),
                        primaryDeviceId = null,
                        primaryDevicePseudonym = null,
                        showAllDevices = false,
                        allowAlertsOnMe = row.boolOrNull("allow_alerts_on_me") ?: false
                    ),
                    createOnly = true
                )
                val written = response.body()?.takeIf { response.isSuccessful && it.success }?.let { it.data?.written }
                when {
                    !response.isSuccessful || response.body()?.success != true -> summary.privacySkipped++
                    // One was made meanwhile: it stands, untouched.
                    written == false -> summary.privacyKept++
                    else -> {
                        summary.privacyRestored++
                        if (written == null) Log.w(TAG, "restore: a privacy row went to a server that doesn't say whether it wrote")
                    }
                }
            } catch (e: Exception) {
                summary.privacySkipped++
            }
        }
        if (summary.privacyKept > 0) {
            Log.i(TAG, "restore: ${summary.privacyKept} circle privacy row(s) this account has now kept as they are")
        }

        if (summary.privacySkipped > 0) {
            summary.notes.add(
                "${summary.privacySkipped} circle privacy setting(s) couldn't be applied — usually a circle you're no longer in."
            )
        }
    }

    /**
     * Notification channels — a PUT over the whole object, so idempotent by
     * construction.
     *
     * The encrypted geocoding blob is exported but NOT restored: it is sealed
     * under a key this account may no longer hold, and writing back a blob we
     * cannot read would leave settings that never decrypt.
     */
    private suspend fun restorePreferences(file: JsonObject, summary: Summary) {
        val notifications = file.objOrNull("preferences")?.objOrNull("notifications") ?: return
        val asMap = mutableMapOf<String, Any>()
        for ((key, value) in notifications.entrySet()) {
            if (!value.isJsonPrimitive) continue
            val primitive = value.asJsonPrimitive
            asMap[key] = when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber
                else -> primitive.asString
            }
        }
        if (asMap.isEmpty()) return

        try {
            val response = tracksApi.updateNotificationPreferences(asMap)
            if (response.isSuccessful) summary.preferencesRestored = true
        } catch (e: Exception) {
            // Counted by its absence from the summary rather than as a failure.
        }
    }

    /**
     * Place and boundary alerts.
     *
     * Hashes are RE-DERIVED from the current circle key by the create path, not
     * replayed from the file. The exported ones are keyed to the key version
     * that was live when the backup was written, so after a rotation they would
     * match nothing and the alert would exist while never firing — the worst
     * kind of restored setting, because it looks present.
     */
    private suspend fun restoreAlerts(file: JsonObject, summary: Summary, placed: Placed) {
        val rows = file.arrayOrEmpty("alerts")
        if (rows.size() == 0) return

        // What is already here, so a second restore adds nothing. Keyed on
        // (place, target) — the pair that identifies an alert to a user,
        // whatever id the server happened to mint for it.
        //
        // Read from the LOCAL table, not from the API response: the server is
        // blind to who an alert watches, so `PlaceAlertResponse` carries only
        // `encrypted_target_user_id`. The refresh below is what decrypts it.
        val existing = mutableSetOf<String>()
        try {
            placeRepository.refreshPlaceAlerts().getOrNull()?.forEach {
                existing.add("${it.placeId}|${it.targetUserId}")
            }
        } catch (e: Exception) {
            // An empty set only risks re-creating an alert that is already
            // there, which is better than abandoning the section entirely.
        }

        for (element in rows) {
            val a = element.takeIf { it.isJsonObject }?.asJsonObject ?: run {
                summary.alertsSkipped++; continue
            }
            // The place as it is here now: under a fresh id when its own was someone else's.
            val placeId = a.stringOrNull("place_id")?.let { placed.ids[it] ?: it }
            if (placeId == null) { summary.alertsSkipped++; continue }
            // Only onto a place this account owns here: an alert whose place is gone
            // would be invisible and unfirable, and one put on a place shared into a
            // circle, or on another account's, is no setting of this backup.
            if (placeId !in placed.owned) { summary.alertsSkipped++; continue }

            // NORMALISE BOTH SIDES to the same value. The export writes
            // `target_user_id: null` for a circle-wide alert, while the local
            // table holds the DECRYPTED target, which for that case is the
            // sentinel "all". Comparing them raw never matches, so every
            // circle-wide alert would be re-created on every restore — and a
            // duplicated alert fires twice.
            val targetUserId = a.stringOrNull("target_user_id") ?: CIRCLE_WIDE_TARGET
            val key = "$placeId|$targetUserId"
            if (key in existing) { summary.alertsSkipped++; continue }

            val result = placeRepository.createPlaceAlert(
                circleId = a.stringOrNull("circle_id"),
                placeId = placeId,
                targetUserId = targetUserId,
                alertOnArrival = a.boolOrNull("alert_on_arrival") ?: true,
                alertOnDeparture = a.boolOrNull("alert_on_departure") ?: false,
                notifyPush = a.boolOrNull("notify_push") ?: true,
                notifyEmail = a.boolOrNull("notify_email") ?: false,
                isRecurring = a.boolOrNull("is_recurring") ?: false
            )
            result.fold(
                onSuccess = { summary.alertsRestored++; existing.add(key) },
                onFailure = { summary.alertsSkipped++ }
            )
        }
    }

    companion object {
        private const val TAG = "RestoreRepository"
        private const val DEFAULT_RADIUS_METERS = 100
        /**
         * The server's error_code for a place id another account holds, byte for byte as the server
         * sends it. Compared exactly, like every other code in this app; the tests answer with the
         * server's real body.
         */
        private const val PLACE_ID_TAKEN = "place_id_taken"
        /** The sentinel the create path encrypts for a circle-wide alert. */
        const val CIRCLE_WIDE_TARGET = "all"
    }
}
