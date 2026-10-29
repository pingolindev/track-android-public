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

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.util.AccountIds
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypted data export for client-side use.
 * All sensitive data has been decrypted with the appropriate keys.
 */
data class DecryptedDataExport(
    val metadata: ExportMetadata,
    val devices: List<DecryptedDevice>?,
    val circles: List<DecryptedCircleMembership>?,
    val places: List<DecryptedPlace>?,
    val checkIns: List<DecryptedCheckIn>?,
    val locations: List<DecryptedLocation>?
)

data class DecryptedDevice(
    val id: String,
    val name: String,
    val platform: String?,
    val registeredDayBucket: Int
)

data class DecryptedCircleMembership(
    val circleId: String,
    val name: String,
    val role: String,
    val keyVersion: Int,
    val joinedDayBucket: Int?,
    /** Only the export file's own key opened it: the server's word (see [ExportRepository.decryptExport]). */
    val keyUnverified: Boolean = false
)

data class DecryptedPlace(
    val id: String,
    val circleId: String?,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val createdDayBucket: Int,
    /** Only the export file's own key opened it: the server's word, which no restore makes real. */
    val keyUnverified: Boolean = false
)

/** A decrypted point, from either the SDK Location type or stored JSON. */
internal data class LatLng(val latitude: Double, val longitude: Double)

data class DecryptedCheckIn(
    val id: String,
    /**
     * Nullable like [DecryptedPlace.circleId]: the file records that this
     * check-in belongs to no circle, rather than the self-circle UUID it was
     * DECRYPTED under. Gson's `addProperty` writes a null value as JSON null,
     * which is what the other two clients write too.
     */
    val circleId: String?,
    val checkInType: String,
    val message: String?,
    val latitude: Double?,
    val longitude: Double?,
    val createdDayBucket: Int,
    /** Its message or location opened only with the export file's own key: the server's word. */
    val keyUnverified: Boolean = false
)

data class DecryptedLocation(
    val deviceId: String,
    val circleId: String,
    val dayBucket: Int,
    val timestamp: Long?,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double?,
    val keyId: String,
    /** Server-side insertion time in ms, straight from the export payload's
     *  `inserted_at_ms`. Carried only so a written backup can hand it back on a
     *  restore: it is half of the row identity the restore endpoint de-duplicates
     *  on, and a row without it is refused. 0 means the export did not supply one
     *  (a server predating the field), and is not written to the file. */
    val insertedAtMs: Long = 0,
    /** The exporting row's `row_key`, straight from the export payload. The other
     *  half of that row identity, and the half that actually fires on a repeat: a
     *  restore re-seals every point with a fresh nonce, so nothing about the
     *  ciphertext the server receives can recognise the same point coming back,
     *  while this is computed over the ORIGINAL ciphertext and does not move.
     *  Null when the export did not supply one, and then left out of the file —
     *  the server reads an absent key as "cannot dedupe, insert". */
    val rowKey: String? = null,
    /** Only the export file's own key opened it: the server's word. */
    val keyUnverified: Boolean = false
)

/**
 * Repository for GDPR Article 20 - Right to Data Portability.
 * Exports all user data and decrypts it client-side.
 */
@Singleton
class ExportRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val cryptoManager: CryptoManager,
    // This account's sharing key, which the export file's own key store needs.
    private val keyStorage: com.pingolin.track.util.KeyStorageCallbackImpl
) {
    /**
     * A new, empty key store: the export file's own ([decryptExport]). A seam for the tests,
     * which can't load the native library; production always gets a bare [CryptoManager].
     */
    internal var newFileKeyStore: () -> CryptoManager = { CryptoManager() }

    /**
     * Export all user data from the server.
     * Returns the raw encrypted response.
     *
     * @param startDayBucket Start of date range (days since Unix epoch). Default: 30 days ago.
     * @param endDayBucket End of date range (days since Unix epoch). Default: today.
     */
    suspend fun exportData(
        startDayBucket: Int? = null,
        endDayBucket: Int? = null,
        includeDevices: Boolean = true,
        includeCircles: Boolean = true,
        includePlaces: Boolean = true,
        includeCheckIns: Boolean = true,
        includeLocations: Boolean = true
    ): Result<DataExportResponse> = exportData(
        startDayBucket, endDayBucket, includeDevices, includeCircles,
        includePlaces, includeCheckIns, includeLocations, onPages = {}
    )

    private suspend fun exportData(
        startDayBucket: Int?,
        endDayBucket: Int?,
        includeDevices: Boolean,
        includeCircles: Boolean,
        includePlaces: Boolean,
        includeCheckIns: Boolean,
        includeLocations: Boolean,
        onPages: (Int) -> Unit
    ): Result<DataExportResponse> {
        return pageAll(includeDevices, includeCircles, includePlaces, includeCheckIns, includeLocations, onPages) { req ->
            tracksApi.exportData(
                startDayBucket = startDayBucket,
                endDayBucket = endDayBucket,
                includeDevices = req.includeDevices,
                includeCircles = req.includeCircles,
                includePlaces = req.includePlaces,
                includeCheckIns = req.includeCheckIns,
                includeLocations = req.includeLocations,
                includeAlerts = req.includeAlerts,
                includeAlertRules = req.includeAlertRules,
                includeInactivityAlerts = req.includeInactivityAlerts,
                includePrivacy = req.includePrivacy,
                includePreferences = req.includePreferences,
                includeProfile = req.includeProfile,
                includeCircleInvites = req.includeCircleInvites,
                includeAlertEvents = req.includeAlertEvents,
                includePlaceShares = req.includePlaceShares,
                includeWebPushSubscriptions = req.includeWebPushSubscriptions,
                includeBoundaryEvents = req.includeBoundaryEvents,
                includeDestinations = req.includeDestinations,
                includeCircleNotifications = req.includeCircleNotifications,
                includeNotificationHistory = req.includeNotificationHistory,
                includeCircleActivity = req.includeCircleActivity,
                includeBlockedPeople = req.includeBlockedPeople,
                cursorDeviceCreatedAtMs = req.cursorDeviceCreatedAtMs,
                cursorDeviceId = req.cursorDeviceId,
                cursorCircleJoinedBucket = req.cursorCircleJoinedBucket,
                cursorCircleId = req.cursorCircleId,
                cursorPlaceCreatedBucket = req.cursorPlaceCreatedBucket,
                cursorPlaceId = req.cursorPlaceId,
                cursorCheckInBucket = req.cursorCheckInBucket,
                cursorCheckInId = req.cursorCheckInId,
                cursorAlertCreatedAtMs = req.cursorAlertCreatedAtMs,
                cursorAlertId = req.cursorAlertId,
                cursorAlertRuleCreatedAtMs = req.cursorAlertRuleCreatedAtMs,
                cursorAlertRuleId = req.cursorAlertRuleId,
                cursorInactivityCreatedAtMs = req.cursorInactivityCreatedAtMs,
                cursorInactivityId = req.cursorInactivityId,
                cursorPrivacyCircleId = req.cursorPrivacyCircleId,
                cursorInviteCreatedAtMs = req.cursorInviteCreatedAtMs,
                cursorInviteId = req.cursorInviteId,
                cursorAlertEventTriggeredAtMs = req.cursorAlertEventTriggeredAtMs,
                cursorAlertEventId = req.cursorAlertEventId,
                cursorPlaceSharePlaceId = req.cursorPlaceSharePlaceId,
                cursorPlaceShareCircleId = req.cursorPlaceShareCircleId,
                cursorWebPushCreatedAtMs = req.cursorWebPushCreatedAtMs,
                cursorWebPushId = req.cursorWebPushId,
                cursorBoundaryEventOccurredAtMs = req.cursorBoundaryEventOccurredAtMs,
                cursorBoundaryEventId = req.cursorBoundaryEventId,
                cursorDestinationCreatedAtMs = req.cursorDestinationCreatedAtMs,
                cursorDestinationId = req.cursorDestinationId,
                cursorCircleNotificationCreatedAtMs = req.cursorCircleNotificationCreatedAtMs,
                cursorCircleNotificationId = req.cursorCircleNotificationId,
                cursorNotificationCreatedAtMs = req.cursorNotificationCreatedAtMs,
                cursorNotificationId = req.cursorNotificationId,
                cursorCircleActivityCreatedAtMs = req.cursorCircleActivityCreatedAtMs,
                cursorCircleActivityId = req.cursorCircleActivityId,
                cursorBlockedPersonCreatedAtMs = req.cursorBlockedPersonCreatedAtMs,
                cursorBlockedPersonId = req.cursorBlockedPersonId,
                cursorDayBucket = req.cursorDayBucket,
                cursorInsertedAtMs = req.cursorInsertedAtMs,
                cursorRowKey = req.cursorRowKey
            )
        }
    }

    /** One page's worth of include-flags and cursors. Exists so the paging loop
     * can be driven in a test without stubbing a thirty-parameter Retrofit
     * signature. */
    internal data class PageRequest(
        val includeDevices: Boolean, val includeCircles: Boolean, val includePlaces: Boolean,
        val includeCheckIns: Boolean, val includeLocations: Boolean,
        val includeAlerts: Boolean, val includeAlertRules: Boolean,
        val includeInactivityAlerts: Boolean, val includePrivacy: Boolean,
        val includePreferences: Boolean,
        val cursorDeviceCreatedAtMs: Long? = null, val cursorDeviceId: String? = null,
        val cursorCircleJoinedBucket: Int? = null, val cursorCircleId: String? = null,
        val cursorPlaceCreatedBucket: Int? = null, val cursorPlaceId: String? = null,
        val cursorCheckInBucket: Int? = null, val cursorCheckInId: String? = null,
        val cursorAlertCreatedAtMs: Long? = null, val cursorAlertId: String? = null,
        val cursorAlertRuleCreatedAtMs: Long? = null, val cursorAlertRuleId: String? = null,
        val cursorInactivityCreatedAtMs: Long? = null, val cursorInactivityId: String? = null,
        val cursorPrivacyCircleId: String? = null,
        val includeProfile: Boolean = true, val includeCircleInvites: Boolean = true,
        val includeAlertEvents: Boolean = true, val includePlaceShares: Boolean = true,
        val includeWebPushSubscriptions: Boolean = true,
        val cursorInviteCreatedAtMs: Long? = null, val cursorInviteId: String? = null,
        val cursorAlertEventTriggeredAtMs: Long? = null, val cursorAlertEventId: String? = null,
        // place_shares has no id column; (place_id, circle_id) IS its primary key,
        // so both halves travel or rows tied on a place are skipped.
        val cursorPlaceSharePlaceId: String? = null, val cursorPlaceShareCircleId: String? = null,
        val cursorWebPushCreatedAtMs: Long? = null, val cursorWebPushId: String? = null,
        val includeBoundaryEvents: Boolean = true,
        val cursorBoundaryEventOccurredAtMs: Long? = null, val cursorBoundaryEventId: String? = null,
        val includeDestinations: Boolean = true,
        val cursorDestinationCreatedAtMs: Long? = null, val cursorDestinationId: String? = null,
        val includeCircleNotifications: Boolean = true,
        val cursorCircleNotificationCreatedAtMs: Long? = null, val cursorCircleNotificationId: String? = null,
        val includeNotificationHistory: Boolean = true,
        val cursorNotificationCreatedAtMs: Long? = null, val cursorNotificationId: String? = null,
        val includeCircleActivity: Boolean = true,
        val cursorCircleActivityCreatedAtMs: Long? = null, val cursorCircleActivityId: String? = null,
        val includeBlockedPeople: Boolean = true,
        val cursorBlockedPersonCreatedAtMs: Long? = null, val cursorBlockedPersonId: String? = null,
        val cursorDayBucket: Int? = null, val cursorInsertedAtMs: Long? = null,
        val cursorRowKey: String? = null,
    )

    /**
     * Fetch every page and merge it.
     *
     * The rules that are easy to get wrong: a section that reports no more data
     * must STOP being requested (keep asking and it re-serves its first page,
     * duplicating into the result); an empty page ends a section even if the
     * flag still says more (the cursor comes from the last row received, so an
     * empty page cannot advance it); and cursors are (sort key, id) pairs,
     * because half a cursor silently skips every row tied on the key.
     */
    internal suspend fun pageAll(
        includeDevices: Boolean,
        includeCircles: Boolean,
        includePlaces: Boolean,
        includeCheckIns: Boolean,
        includeLocations: Boolean,
        /** How many requests it took, for the file's envelope. */
        onPages: (Int) -> Unit = {},
        fetch: suspend (PageRequest) -> retrofit2.Response<com.pingolin.track.data.remote.dto.ApiResponse<DataExportResponse>>
    ): Result<DataExportResponse> {
        return try {
            var merged: DataExportResponse? = null
            val outstanding = mutableSetOf<String>().apply {
                if (includeDevices) add("devices")
                if (includeCircles) add("circles")
                if (includePlaces) add("places")
                if (includeCheckIns) add("check_ins")
                if (includeLocations) add("locations")
                // Config always travels: tiny next to the history sections, and
                // the part a restore can actually use.
                addAll(listOf("alerts", "alert_rules", "inactivity_alerts", "privacy"))
                // Format 1.3: what the service holds ABOUT the user rather than
                // settings they chose. Also small, and also part of the answer
                // to an access request.
                addAll(listOf("circle_invites", "alert_events", "place_shares", "web_push_subscriptions"))
                add("boundary_events")
                // Format 1.5: destinations, circle notifications,
                // notification history and circle activity.
                addAll(listOf("destinations", "circle_notifications", "notification_history", "circle_activity"))
                // Format 1.9: "Blocked people". The server serves it only when the request
                // says so, which every page's request does while it is outstanding.
                add("blocked_people")
            }
            var pages = 0
            var truncated = false

            while (outstanding.isNotEmpty()) {
                if (pages >= MAX_EXPORT_PAGES) { truncated = true; break }
                pages++

                val last = merged
                val response = fetch(PageRequest(
                    includeDevices = outstanding.contains("devices"),
                    includeCircles = outstanding.contains("circles"),
                    includePlaces = outstanding.contains("places"),
                    includeCheckIns = outstanding.contains("check_ins"),
                    includeLocations = outstanding.contains("locations"),
                    includeAlerts = outstanding.contains("alerts"),
                    includeAlertRules = outstanding.contains("alert_rules"),
                    includeInactivityAlerts = outstanding.contains("inactivity_alerts"),
                    includePrivacy = outstanding.contains("privacy"),
                    // Singleton: asked for on the first request only, then
                    // carried forward. Re-requesting refetches the same object.
                    includePreferences = last?.preferences == null,
                    cursorDeviceCreatedAtMs = last?.devices?.lastOrNull()?.createdAtMs,
                    cursorDeviceId = last?.devices?.lastOrNull()?.id,
                    // The server orders a NULL join bucket as 0; mirror it or
                    // the cursor and the ORDER BY disagree and rows are skipped.
                    cursorCircleJoinedBucket = last?.circles?.lastOrNull()?.let { it.joinedDayBucket ?: 0 },
                    cursorCircleId = last?.circles?.lastOrNull()?.circleId,
                    cursorPlaceCreatedBucket = last?.places?.lastOrNull()?.createdDayBucket,
                    cursorPlaceId = last?.places?.lastOrNull()?.id,
                    cursorCheckInBucket = last?.checkIns?.lastOrNull()?.createdDayBucket,
                    cursorCheckInId = last?.checkIns?.lastOrNull()?.id,
                    cursorAlertCreatedAtMs = last?.alerts?.lastLong("created_at_ms"),
                    cursorAlertId = last?.alerts?.lastString("id"),
                    cursorAlertRuleCreatedAtMs = last?.alertRules?.lastLong("created_at_ms"),
                    cursorAlertRuleId = last?.alertRules?.lastString("id"),
                    cursorInactivityCreatedAtMs = last?.inactivityAlerts?.lastLong("created_at_ms"),
                    cursorInactivityId = last?.inactivityAlerts?.lastString("id"),
                    // One row per (user, circle), so the circle id alone is a
                    // total order — the only section needing no tiebreaker.
                    cursorPrivacyCircleId = last?.privacy?.lastString("circle_id"),
                    cursorInviteCreatedAtMs = last?.circleInvites?.lastLong("created_at_ms"),
                    cursorInviteId = last?.circleInvites?.lastString("id"),
                    cursorAlertEventTriggeredAtMs = last?.alertEvents?.lastLong("triggered_at_ms"),
                    cursorAlertEventId = last?.alertEvents?.lastString("id"),
                    cursorPlaceSharePlaceId = last?.placeShares?.lastString("place_id"),
                    cursorPlaceShareCircleId = last?.placeShares?.lastString("circle_id"),
                    cursorWebPushCreatedAtMs = last?.webPushSubscriptions?.lastLong("created_at_ms"),
                    cursorWebPushId = last?.webPushSubscriptions?.lastString("id"),
                    cursorBoundaryEventOccurredAtMs = last?.boundaryEvents?.lastLong("occurred_at_ms"),
                    cursorBoundaryEventId = last?.boundaryEvents?.lastString("id"),
                    includeDestinations = outstanding.contains("destinations"),
                    includeCircleNotifications = outstanding.contains("circle_notifications"),
                    includeNotificationHistory = outstanding.contains("notification_history"),
                    includeCircleActivity = outstanding.contains("circle_activity"),
                    includeBlockedPeople = outstanding.contains("blocked_people"),
                    cursorDestinationCreatedAtMs = last?.destinations?.lastLong("created_at_ms"),
                    cursorDestinationId = last?.destinations?.lastString("id"),
                    cursorCircleNotificationCreatedAtMs = last?.circleNotifications?.lastLong("created_at_ms"),
                    cursorCircleNotificationId = last?.circleNotifications?.lastString("id"),
                    cursorNotificationCreatedAtMs = last?.notificationHistory?.lastLong("created_at_ms"),
                    cursorNotificationId = last?.notificationHistory?.lastString("id"),
                    cursorCircleActivityCreatedAtMs = last?.circleActivity?.lastLong("created_at_ms"),
                    cursorCircleActivityId = last?.circleActivity?.lastString("id"),
                    cursorBlockedPersonCreatedAtMs = last?.blockedPeople?.lastLong("created_at_ms"),
                    cursorBlockedPersonId = last?.blockedPeople?.lastString("id"),
                    cursorDayBucket = last?.locations?.lastOrNull()?.dayBucket,
                    cursorInsertedAtMs = last?.locations?.lastOrNull()?.insertedAtMs,
                    cursorRowKey = last?.locations?.lastOrNull()?.rowKey
                ))

                if (!response.isSuccessful || response.body()?.success != true || response.body()?.data == null) {
                    // In this app's words, never the server's.
                    return Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't export your data just now.")))
                }
                val page = response.body()!!.data!!
                merged = mergePage(merged, page, outstanding)

                val m = page.metadata
                dropFinished(outstanding, "devices", m.devicesHasMore, page.devices?.size ?: 0)
                dropFinished(outstanding, "circles", m.circlesHasMore, page.circles?.size ?: 0)
                dropFinished(outstanding, "places", m.placesHasMore, page.places?.size ?: 0)
                dropFinished(outstanding, "check_ins", m.checkInsHasMore, page.checkIns?.size ?: 0)
                dropFinished(outstanding, "locations", m.locationsHasMore, page.locations?.size ?: 0)
                dropFinished(outstanding, "alerts", m.alertsHasMore, page.alerts?.size() ?: 0)
                dropFinished(outstanding, "alert_rules", m.alertRulesHasMore, page.alertRules?.size() ?: 0)
                dropFinished(outstanding, "inactivity_alerts", m.inactivityAlertsHasMore, page.inactivityAlerts?.size() ?: 0)
                dropFinished(outstanding, "privacy", m.privacyHasMore, page.privacy?.size() ?: 0)
                dropFinished(outstanding, "circle_invites", m.circleInvitesHasMore, page.circleInvites?.size() ?: 0)
                dropFinished(outstanding, "alert_events", m.alertEventsHasMore, page.alertEvents?.size() ?: 0)
                dropFinished(outstanding, "place_shares", m.placeSharesHasMore, page.placeShares?.size() ?: 0)
                dropFinished(outstanding, "web_push_subscriptions", m.webPushSubscriptionsHasMore, page.webPushSubscriptions?.size() ?: 0)
                dropFinished(outstanding, "boundary_events", m.boundaryEventsHasMore, page.boundaryEvents?.size() ?: 0)
                dropFinished(outstanding, "destinations", m.destinationsHasMore, page.destinations?.size() ?: 0)
                dropFinished(outstanding, "circle_notifications", m.circleNotificationsHasMore, page.circleNotifications?.size() ?: 0)
                dropFinished(outstanding, "notification_history", m.notificationHistoryHasMore, page.notificationHistory?.size() ?: 0)
                dropFinished(outstanding, "circle_activity", m.circleActivityHasMore, page.circleActivity?.size() ?: 0)
                dropFinished(outstanding, "blocked_people", m.blockedPeopleHasMore, page.blockedPeople?.size() ?: 0)
            }

            if (truncated) {
                return Result.failure(Exception(
                    "Export incomplete: stopped after $MAX_EXPORT_PAGES pages while the server " +
                    "still reported more data. Try a shorter date range."
                ))
            }
            onPages(pages)
            merged?.let { Result.success(it) }
                ?: Result.failure(Exception("Export returned no data"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Drop a section once the server stops reporting more, or hands back none. */
    private fun dropFinished(
        outstanding: MutableSet<String>,
        key: String,
        hasMore: Boolean,
        rows: Int
    ) {
        if (!outstanding.contains(key)) return
        if (!hasMore || rows == 0) outstanding.remove(key)
    }

    /** Accumulate a page onto what we already have, section by section. */
    private fun mergePage(
        soFar: DataExportResponse?,
        page: DataExportResponse,
        outstanding: Set<String>
    ): DataExportResponse {
        if (soFar == null) return page
        fun <T> join(a: List<T>?, b: List<T>?, active: Boolean): List<T>? =
            if (!active) a else (a ?: emptyList()) + (b ?: emptyList())
        fun joinArr(a: com.google.gson.JsonArray?, b: com.google.gson.JsonArray?, active: Boolean): com.google.gson.JsonArray? {
            if (!active) return a
            val out = com.google.gson.JsonArray()
            a?.forEach { out.add(it) }
            b?.forEach { out.add(it) }
            return out
        }
        return page.copy(
            devices = join(soFar.devices, page.devices, outstanding.contains("devices")),
            circles = join(soFar.circles, page.circles, outstanding.contains("circles")),
            places = join(soFar.places, page.places, outstanding.contains("places")),
            checkIns = join(soFar.checkIns, page.checkIns, outstanding.contains("check_ins")),
            locations = join(soFar.locations, page.locations, outstanding.contains("locations")),
            alerts = joinArr(soFar.alerts, page.alerts, outstanding.contains("alerts")),
            alertRules = joinArr(soFar.alertRules, page.alertRules, outstanding.contains("alert_rules")),
            inactivityAlerts = joinArr(soFar.inactivityAlerts, page.inactivityAlerts, outstanding.contains("inactivity_alerts")),
            privacy = joinArr(soFar.privacy, page.privacy, outstanding.contains("privacy")),
            circleInvites = joinArr(soFar.circleInvites, page.circleInvites, outstanding.contains("circle_invites")),
            alertEvents = joinArr(soFar.alertEvents, page.alertEvents, outstanding.contains("alert_events")),
            placeShares = joinArr(soFar.placeShares, page.placeShares, outstanding.contains("place_shares")),
            webPushSubscriptions = joinArr(soFar.webPushSubscriptions, page.webPushSubscriptions, outstanding.contains("web_push_subscriptions")),
            boundaryEvents = joinArr(soFar.boundaryEvents, page.boundaryEvents, outstanding.contains("boundary_events")),
            destinations = joinArr(soFar.destinations, page.destinations, outstanding.contains("destinations")),
            circleNotifications = joinArr(soFar.circleNotifications, page.circleNotifications, outstanding.contains("circle_notifications")),
            notificationHistory = joinArr(soFar.notificationHistory, page.notificationHistory, outstanding.contains("notification_history")),
            circleActivity = joinArr(soFar.circleActivity, page.circleActivity, outstanding.contains("circle_activity")),
            blockedPeople = joinArr(soFar.blockedPeople, page.blockedPeople, outstanding.contains("blocked_people")),
            // The singletons are taken once and kept.
            preferences = soFar.preferences ?: page.preferences,
            profile = soFar.profile ?: page.profile
        )
    }

    private fun com.google.gson.JsonArray.lastString(field: String): String? =
        lastOrNull()?.asJsonObject?.get(field)?.takeIf { !it.isJsonNull }?.asString

    private fun com.google.gson.JsonArray.lastLong(field: String): Long? =
        lastOrNull()?.asJsonObject?.get(field)?.takeIf { !it.isJsonNull }?.asLong

    /**
     * Export and decrypt all user data.
     * This decrypts all encrypted fields client-side.
     *
     * @param startDayBucket Start of date range (days since Unix epoch). Default: 30 days ago.
     * @param endDayBucket End of date range (days since Unix epoch). Default: today.
     */
    suspend fun exportAndDecryptData(
        startDayBucket: Int? = null,
        endDayBucket: Int? = null,
        includeDevices: Boolean = true,
        includeCircles: Boolean = true,
        includePlaces: Boolean = true,
        includeCheckIns: Boolean = true,
        includeLocations: Boolean = true
    ): Result<DecryptedDataExport> {
        val exportResult = exportData(
            startDayBucket = startDayBucket,
            endDayBucket = endDayBucket,
            includeDevices = includeDevices,
            includeCircles = includeCircles,
            includePlaces = includePlaces,
            includeCheckIns = includeCheckIns,
            includeLocations = includeLocations
        )

        return exportResult.mapCatching { export -> decryptExport(export) }
    }

    /** An export plus the file body to write. */
    data class BuiltBackup(
        val decrypted: DecryptedDataExport,
        val fileBody: String
    )

    /**
     * Export, decrypt, and build the file to write.
     *
     * The counts are a side effect; the FILE is the product. Counting the
     * rows of an export that is never written would report "exported
     * successfully" for a backup that exists nowhere.
     */
    suspend fun exportAndBuildBackup(
        startDayBucket: Int? = null,
        endDayBucket: Int? = null,
        includeDevices: Boolean = true,
        includeCircles: Boolean = true,
        includePlaces: Boolean = true,
        includeCheckIns: Boolean = true,
        includeLocations: Boolean = true,
        generatedAt: String = java.time.Instant.now().toString()
    ): Result<BuiltBackup> {
        var pages = 0
        return exportData(
            startDayBucket = startDayBucket,
            endDayBucket = endDayBucket,
            includeDevices = includeDevices,
            includeCircles = includeCircles,
            includePlaces = includePlaces,
            includeCheckIns = includeCheckIns,
            includeLocations = includeLocations,
            onPages = { pages = it }
        ).mapCatching { raw ->
            val decrypted = decryptExport(raw)
            BuiltBackup(
                decrypted = decrypted,
                fileBody = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
                    com.pingolin.track.domain.backup.TrackBackupFile.build(
                        raw = raw,
                        decrypted = decrypted,
                        pagesFetched = pages,
                        // pageAll FAILS rather than returning a short export, so
                        // reaching here means the client finished paging.
                        isComplete = true,
                        generatedAt = generatedAt
                    )
                )
            )
        }
    }

    /**
     * Self/personal key UUID - used for user-specific encryption like device names.
     * This is the same as LocationRepository.SELF_CIRCLE_UUID.
     */
    companion object {
        private const val TAG = "ExportRepository"
        private const val SELF_CIRCLE_UUID = "00000000-0000-0000-0000-000000000000"

        /**
         * Stop rather than spin. The loop ends on the server's own has_more
         * flags; this only catches a server that keeps claiming more without
         * advancing. Hitting it FAILS the export rather than returning a short
         * file, because an export that looks whole when it is not is only
         * discovered at restore time.
         */
        private const val MAX_EXPORT_PAGES = 500
    }

    /**
     * The export, opened.
     *
     * The export's circle rows carry this account's membership key wraps with NO signer, so each
     * one is the server's word. Put into the live key store, a circle with no key yet would take
     * one as a first contact, and this phone would then seal that circle's traffic under a key
     * the server chose. So:
     *  - a circle this device holds ANY key for is read with this device's keys only; the file's
     *    wrap for it is never opened;
     *  - the wraps of the rest are opened in the file's own store, a bare [CryptoManager] made
     *    here for this one call: no storage, no pins, no signing key, closed before this
     *    returns;
     *  - a row only that store opened is marked (`keyUnverified`), and the file says
     *    `"key_unverified": true`, which no client's restore makes real.
     * Rows are read under the circle's canonical id: another spelling of a held circle is still
     * that circle, never a new one.
     */
    private fun decryptExport(raw: DataExportResponse): DecryptedDataExport {
        val circles = raw.circles?.let { circleRowsOf(it) }
        val fileOnly = circles.orEmpty().filterNot { cryptoManager.hasCircleKey(it.circleId) }
        val fileStore = if (fileOnly.isEmpty()) null else newFileKeyStore()
        try {
            val keys = ExportKeys(cryptoManager, fileStore, fileStore?.let { openFileKeys(it, fileOnly) }.orEmpty())
            return DecryptedDataExport(
                metadata = raw.metadata,
                devices = raw.devices?.mapNotNull { decryptDevice(it) },
                circles = circles?.mapNotNull { decryptCircleMembership(it, keys) },
                places = raw.places?.mapNotNull { decryptPlace(it, keys) },
                checkIns = raw.checkIns?.mapNotNull { decryptCheckIn(it, keys) },
                locations = raw.locations?.mapNotNull { decryptLocation(it, keys) }
            )
        } finally {
            fileStore?.close()
        }
    }

    /**
     * The circle rows that name a circle, each under its canonical id. A row naming the personal
     * key's slot (any spelling) or no UUID names none: it's left out of the file, and its wrap is
     * opened nowhere.
     */
    private fun circleRowsOf(rows: List<CircleMembershipExport>): List<CircleMembershipExport> {
        val named = rows.mapNotNull { row ->
            AccountIds.canonical(row.circleId)
                ?.takeUnless { AccountIds.isPersonalSlot(it) }
                ?.let { row.copy(circleId = it) }
        }
        if (named.size < rows.size) {
            android.util.Log.w(TAG, "export: ${rows.size - named.size} circle row(s) name no circle; left out")
        }
        return named
    }

    /**
     * Open the wraps of [fileOnly], circles this device holds no key for, in [store]: this
     * account's sharing key and those wraps, nothing else. A bare store holds no pins, so each
     * wrap is a first contact there, and only there. The circles it opened, or none when this
     * account's key can't be read here (their rows then stay unread, like any row no key opens).
     */
    private fun openFileKeys(store: CryptoManager, fileOnly: List<CircleMembershipExport>): Set<String> {
        try {
            val privateKey = keyStorage.sharingPrivateKey() ?: run {
                android.util.Log.w(TAG, "export: this account's key couldn't be read here; ${fileOnly.size} circle(s) with no key on this device stay unread")
                return emptySet()
            }
            store.importPrivateKey(privateKey)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "export: the file's own key store couldn't be opened (${e.javaClass.simpleName}); ${fileOnly.size} circle(s) stay unread")
            return emptySet()
        }
        val opened = HashSet<String>()
        for (row in fileOnly) {
            try {
                // The SDK takes hex(nonce || ciphertext). The export names no sender and no
                // signature: nothing vouches for this key but the server.
                store.storeCircleKey(
                    row.circleId,
                    "${row.encryptedCircleKey.nonce}${row.encryptedCircleKey.ciphertext}",
                    row.ephemeralPublicKey,
                    row.keyVersion,
                    "",
                    "",
                    null,
                    null,
                )
                opened.add(row.circleId)
            } catch (e: Exception) {
                // Not opened: that circle's rows stay unread.
            }
        }
        if (opened.isNotEmpty()) {
            android.util.Log.i(TAG, "export: ${opened.size} circle(s) read only with the file's own keys; their rows are marked")
        }
        return opened
    }

    /**
     * Which store reads an export row: this device's own for a circle it holds ANY key for,
     * the file's own for a circle whose wrap it opened, else none. Built and dropped inside
     * [decryptExport], like the file's store itself.
     */
    internal class ExportKeys(
        private val live: CryptoManager,
        private val file: CryptoManager?,
        private val fileCircles: Set<String>,
    ) {
        /** The store for [keyId]'s rows, and whether it's the file's; null when neither holds a key. */
        fun storeFor(keyId: String): Pair<CryptoManager, Boolean>? = when {
            live.hasCircleKey(keyId) -> live to false
            file != null && keyId in fileCircles -> file to true
            else -> null
        }
    }

    /**
     * The key id a row is read under: the personal key's for a row with no circle, else the
     * circle's canonical id (any spelling of the nil UUID is the personal key's); null for
     * anything that is no UUID, a row no key reads.
     */
    private fun keyIdOf(circleId: String?): String? =
        if (circleId == null) SELF_CIRCLE_UUID else AccountIds.canonical(circleId)

    private fun decryptDevice(device: DeviceExport): DecryptedDevice? {
        return try {
            // Device names are encrypted with the user's self key (personal key)
            // The self key is stored under the nil UUID (SELF_CIRCLE_UUID)
            val decryptedName = if (cryptoManager.hasCircleKey(SELF_CIRCLE_UUID)) {
                try {
                    cryptoManager.decryptDataV2(
                        SELF_CIRCLE_UUID,
                        EncryptedData(
                            nonce = device.encryptedName.nonce,
                            ciphertext = device.encryptedName.ciphertext
                        ),
                        "device_name"
                    )
                } catch (e: Exception) {
                    // Placeholder rather than a failed export. It goes into a
                    // file the user opens later with no app around to explain
                    // it, so it says what it is and nothing about why.
                    "[unavailable]"
                }
            } else {
                "[unavailable]"
            }

            DecryptedDevice(
                id = device.id,
                name = decryptedName,
                // The server sends `device_type` (the push channel)
                // and `day_bucket`; it sends no `platform` or
                // `registered_day_bucket`, which would read as null/0
                // in every export.
                platform = device.deviceType,
                registeredDayBucket = device.dayBucket
            )
        } catch (e: Exception) {
            null
        }
    }

    /** [membership] names its circle by the canonical id ([circleRowsOf]). */
    private fun decryptCircleMembership(membership: CircleMembershipExport, keys: ExportKeys): DecryptedCircleMembership? {
        return try {
            val (store, fromFile) = keys.storeFor(membership.circleId) ?: return null

            // Decrypt circle name
            val decryptedName = store.decryptDataV2(
                membership.circleId,
                EncryptedData(
                    nonce = membership.encryptedName.nonce,
                    ciphertext = membership.encryptedName.ciphertext
                ),
                "name"
            )

            DecryptedCircleMembership(
                circleId = membership.circleId,
                name = decryptedName,
                role = membership.role,
                keyVersion = membership.keyVersion,
                joinedDayBucket = membership.joinedDayBucket,
                keyUnverified = fromFile
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptPlace(place: PlaceExport, keys: ExportKeys): DecryptedPlace? {
        return try {
            // A PERSONAL place carries `circle_id: null` and is sealed under a
            // per-place key wrapped to the self key; a circle place may carry
            // such a wrapped key too (the envelope path). So a null circle_id
            // is not a reason to drop the row, and both paths are handled here.
            // Mirrors PlaceRepository.refreshPlaces.
            val circleId = keyIdOf(place.circleId)
            // The circle the file names, in its one spelling. A row whose circle id is no id a
            // circle can have is not a place of the export; no circle id stays none: a personal
            // place.
            val fileCircleId = place.circleId?.let { AccountIds.canonical(it) ?: return null }

            // The place's own key is unwrapped under THIS device's personal key only.
            val placeKey = place.wrappedKey?.let { wrapped ->
                val nonce = place.wrappedKeyNonce ?: return@let null
                if (!cryptoManager.hasCircleKey(SELF_CIRCLE_UUID)) return@let null
                try {
                    cryptoManager.unwrapPlaceKey(SELF_CIRCLE_UUID, wrapped, nonce)
                } catch (e: Exception) {
                    // A populated wrap on a row that is actually legacy is
                    // survivable — fall through and try the circle key.
                    null
                }
            }

            val encryptedName = EncryptedData(place.encryptedName.nonce, place.encryptedName.ciphertext)
            val encryptedLocation =
                EncryptedData(place.encryptedLocation.nonce, place.encryptedLocation.ciphertext)
            val decryptedName: String
            val decryptedLocation: LatLng
            var keyUnverified = false
            if (placeKey != null) {
                decryptedName = cryptoManager.decryptWithPlaceKeyV2(placeKey, encryptedName, "name")
                decryptedLocation = parseExportedLocation(
                    cryptoManager.decryptWithPlaceKeyV2(placeKey, encryptedLocation, "location")
                ) ?: return null
            } else {
                // Only the legacy path needs the circle key; the envelope path is
                // already satisfied by the unwrap above.
                val keyId = circleId ?: return null
                val (store, fromFile) = keys.storeFor(keyId) ?: return null
                decryptedName = store.decryptDataV2(keyId, encryptedName, "name")
                decryptedLocation = store.decryptLocation(keyId, encryptedLocation)
                    .let { LatLng(it.latitude, it.longitude) }
                keyUnverified = fromFile
            }

            DecryptedPlace(
                id = place.id,
                circleId = fileCircleId,
                name = decryptedName,
                latitude = decryptedLocation.latitude,
                longitude = decryptedLocation.longitude,
                radiusMeters = place.radiusMeters,
                createdDayBucket = place.createdDayBucket,
                keyUnverified = keyUnverified
            )
        } catch (e: Exception) {
            null
        }
    }

    /** A place-key location decrypts to the JSON the writer stored, not to the
     *  SDK's Location type, so it is parsed rather than typed. */
    internal fun parseExportedLocation(json: String): LatLng? = try {
        val o = com.google.gson.JsonParser.parseString(json).asJsonObject
        val lat = o.get("latitude") ?: o.get("lat")
        val lng = o.get("longitude") ?: o.get("lng") ?: o.get("lon")
        if (lat == null || lng == null) null else LatLng(lat.asDouble, lng.asDouble)
    } catch (e: Exception) {
        null
    }

    /** `internal` so a unit test can drive the real mapping, like
     * [parseExportedLocation]. A failure here is invisible from outside: the
     * row is dropped by the `mapNotNull` in [exportAndDecryptData], so the
     * file is simply short and nothing reports it. */
    internal fun decryptCheckIn(checkIn: CheckInExport, keys: ExportKeys): DecryptedCheckIn? {
        return try {
            // A check-in with no circle is sealed under the SELF key, the
            // same way a personal place is — decryptPlace resolves it
            // identically. `checkIn.circleId` must not reach hasCircleKey
            // as is: its UniFFI binding declares a non-null String, so a
            // null throws in the parameter intrinsic, the catch below
            // swallows it, and the check-in vanishes from the backup with
            // nothing reported. A file that looks complete and is not is
            // worse than an error.
            val circleId = keyIdOf(checkIn.circleId) ?: return null
            val fileCircleId = checkIn.circleId?.let { AccountIds.canonical(it) ?: return null }

            // Check if we have the circle key
            val (store, fromFile) = keys.storeFor(circleId) ?: return null

            // Decrypt message if present
            val decryptedMessage = checkIn.encryptedMessage?.let { msg ->
                try {
                    store.decryptDataV2(
                        circleId,
                        EncryptedData(nonce = msg.nonce, ciphertext = msg.ciphertext),
                        "message"
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // Decrypt location if present
            val decryptedLocation = checkIn.encryptedLocation?.let { loc ->
                try {
                    store.decryptLocation(
                        circleId,
                        EncryptedData(nonce = loc.nonce, ciphertext = loc.ciphertext)
                    )
                } catch (e: Exception) {
                    null
                }
            }

            DecryptedCheckIn(
                id = checkIn.id,
                // The ORIGINAL (in its one spelling), not the resolved one — the
                // self-circle UUID is the decryption domain, not this check-in's circle.
                circleId = fileCircleId,
                checkInType = checkIn.checkInType,
                message = decryptedMessage,
                latitude = decryptedLocation?.latitude,
                longitude = decryptedLocation?.longitude,
                createdDayBucket = checkIn.createdDayBucket,
                keyUnverified = fromFile && (decryptedMessage != null || decryptedLocation != null)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptLocation(location: LocationExport, keys: ExportKeys): DecryptedLocation? {
        return try {
            val circleId = keyIdOf(location.circleId) ?: return null

            // Check if we have the circle key
            val (store, fromFile) = keys.storeFor(circleId) ?: return null

            // Decrypt timestamp if present
            val decryptedTimestamp = location.encryptedTimestamp?.let { ts ->
                try {
                    store.decryptDataV2(
                        circleId,
                        EncryptedData(nonce = ts.nonce, ciphertext = ts.ciphertext),
                        "timestamp"
                    ).toLong()
                } catch (e: Exception) {
                    null
                }
            }

            // Decrypt location payload
            val decryptedPayload = store.decryptLocation(
                circleId,
                EncryptedData(
                    nonce = location.encryptedPayload.nonce,
                    ciphertext = location.encryptedPayload.ciphertext
                )
            )

            DecryptedLocation(
                deviceId = location.deviceId,
                circleId = circleId,
                dayBucket = location.dayBucket,
                timestamp = decryptedTimestamp,
                latitude = decryptedPayload.latitude,
                longitude = decryptedPayload.longitude,
                accuracy = decryptedPayload.accuracy,
                keyId = location.keyId,
                insertedAtMs = location.insertedAtMs,
                rowKey = location.rowKey,
                keyUnverified = fromFile
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compute day bucket (days since Unix epoch).
     */
    fun computeDayBucket(timestampMs: Long = System.currentTimeMillis()): Int {
        return (timestampMs / 86400000).toInt()
    }

    /**
     * Get default date range for export (last 30 days).
     */
    fun getDefaultDateRange(): Pair<Int, Int> {
        val today = computeDayBucket()
        return Pair(today - 30, today)
    }
}
