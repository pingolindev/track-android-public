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

package com.pingolin.track.service

import android.util.Log
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The other half of a tickle, for every transport that can deliver one.
 *
 * The push itself carries no content — Google, Apple and any UnifiedPush
 * distributor see only `{type: "tickle"}` — so the real notification is fetched
 * from our own API over an authenticated TLS connection and rendered here.
 *
 * Lives in `main` rather than beside the FCM service in `gms` because it is NOT
 * FCM-specific: in the gms source set the libre build could not reach it, and a
 * de-Googled install would receive no place alert, no SOS and no arrival until
 * the user happened to open the app. The transport-specific halves (token sync,
 * wake_location, distributor trust) stay with their receivers; this half is
 * shared.
 */
@Singleton
class NotificationTickleFetcher @Inject constructor(
    private val sessionManager: SessionManager,
    private val alertHandler: AlertHandler,
    private val tracksApi: TrackApi,
    private val destinationRepository: com.pingolin.track.domain.repository.DestinationRepository,
    private val sosRepository: com.pingolin.track.domain.repository.SosRepository,
    /** Used to open the sealed crossing/arrival time — see [applyEncryptedOccurredAt]. */
    private val cryptoManager: uniffi.pingolin_mobile.CryptoManager,
    /** Who checked in and where, who is responding: from this device's own data only. */
    private val notificationNames: com.pingolin.track.domain.repository.NotificationNames,
    /** Who answered my request before this one, from the alert's own list. */
    private val myAlertResponders: com.pingolin.track.domain.repository.MyAlertResponders,
) {

    companion object {
        private const val TAG = "TickleFetcher"

        /**
         * In-memory dedupe of notification IDs shown by a tickle-driven
         * fetch in this process. Two tickles arriving in rapid succession
         * both race to fetch unreadOnly notifications; without this, the
         * losing call would re-post the same row before the
         * markNotificationRead round-trip from the winner had landed on
         * the server. Bounded LinkedHashSet evicts the oldest id when it
         * fills — the 200-item cap comfortably outlives any plausible
         * tickle burst while keeping the memory cost negligible.
         *
         * Process-wide, not per-transport: a device can hold an FCM
         * registration and a UnifiedPush one at once, and the same row
         * arriving down both must still be shown once.
         */
        private const val RECENT_SHOWN_MAX = 200

        /**
         * How many unread rows one fetch will take.
         *
         * Nothing is DROPPED at the cap — a row that is not shown is never marked
         * read, so the next fetch takes it — but the drain rate is one batch per
         * fetch, and a fetch happens only when a push arrives or
         * `NotificationTickleWorker.schedulePolling`'s 15-minute timer fires. A
         * small batch is too slow: at 5, a backlog of 40 needs eight of those,
         * i.e. up to two hours, to surface a notification that was already late.
         *
         * 25 rather than the server's maximum of 100: the reason is local — every
         * row in the batch becomes a status-bar notification, and a pathological
         * backlog should not empty itself into the shade in one go. 25 clears a
         * realistic absence in a single pass and leaves the rest for the next one.
         */
        private const val FETCH_LIMIT = 25

        /**
         * The notification-row metadata keys a fetch copies into the map it hands
         * AlertHandler: every key the server writes into a notification row, and
         * nothing else.
         *
         * An allowlist, because the map also carries what this device derived from
         * end-to-end encrypted data — `actor_name` and `dest_label` decrypted with
         * the circle key, `local_evaluated`, which only this device's own evaluator
         * sets — and copying everything would let the server simply write those keys
         * itself: a forged "Mum arrived at Home", or a place alert posted on the
         * "detected on this device" channel with the server's own text. What the
         * server writes is ids, ciphertexts and flags. Names come only from
         * decryption, below, and the envelope fields (type, title, body, circle_id,
         * alert_id, created_at) come from the row itself.
         */
        internal val SERVER_METADATA_KEYS: Set<String> = setOf(
            // SOS: the delivery receipt and the tap target
            "sos_id",
            // Destinations: ids, plus the ciphertexts resolveNotificationCopy opens
            "destination_id", "event_id", "self",
            "encrypted_dest_name", "dest_name_nonce",
            "encrypted_user_id", "user_id_nonce",
            // The sealed event time EncryptedOccurredAt opens
            "encrypted_occurred_at", "occurred_at_nonce",
            // Place alerts: the routing ids local evaluation needs
            "place_alert_id", "place_id", "target_user_id", "event_type_hash",
            // member_joined: the delivery, rotation and fresh-position triggers
            "needs_delivery", "needs_key_rotation", "send_location",
            // Ownership claims
            "claim_id", "resolves_at", "new_owner_id",
            // Check-ins: which check-in; its place and sealed sender are the
            // place_id / encrypted_user_id + user_id_nonce above
            "check_in_id",
        )

        /**
         * [notif] as the flat map AlertHandler reads: the row's own envelope fields,
         * then its metadata, restricted to [SERVER_METADATA_KEYS]. Pure so the
         * boundary is unit-tested.
         */
        internal fun alertDataFor(notif: com.pingolin.track.data.remote.dto.NotificationResponse): HashMap<String, String> =
            HashMap<String, String>().apply {
                put("type", notif.notificationType)
                put("title", notif.title)
                put("body", notif.body ?: "")
                put("circle_id", notif.circleId ?: "")
                put("alert_id", notif.id)
                // WHEN the event happened, not when we got round to rendering it.
                // Place and destination notifications stamp this into their body; a
                // push delivered late (device asleep, offline, doze) would otherwise
                // read as if the arrival had just occurred.
                notif.createdAt?.let { put("created_at", it) }
                // Stringified: notification copy never needs structured types.
                notif.metadata?.forEach { (k, v) ->
                    if (k in SERVER_METADATA_KEYS) put(k, v.toString())
                }
            }

        /**
         * The pending invite a `circle_invite` row is about — the one for the row's
         * circle, else the only pending one — as (inviter display name, circle name).
         * Null when nothing matches, in which case AlertHandler's "Someone" / "a circle"
         * fallbacks apply. Pure so the choice is unit-tested.
         */
        fun inviteNamesFor(
            invites: List<ReceivedInviteResponse>,
            circleId: String?,
        ): Pair<String?, String?>? {
            val match = invites.firstOrNull { !circleId.isNullOrEmpty() && it.circleId == circleId }
                ?: invites.singleOrNull()
                ?: return null
            return match.inviterDisplayName?.takeIf { it.isNotBlank() } to
                match.circleName?.takeIf { it.isNotBlank() }
        }

        /**
         * An SOS row's (circle, SOS) ids, each in its one spelling: the circle as a push names one
         * (circleIdOf), the SOS a UUID. Null when either isn't.
         */
        internal fun sosRingIds(alertData: Map<String, String>): Pair<String, String>? {
            val circleId = com.pingolin.track.util.circleIdOf(alertData) ?: return null
            val sosId = com.pingolin.track.util.AccountIds.canonical(alertData["sos_id"]) ?: return null
            return circleId to sosId
        }

        private val recentShownAlertIds: MutableSet<String> =
            java.util.Collections.synchronizedSet(
                object : java.util.LinkedHashSet<String>() {
                    override fun add(element: String): Boolean {
                        val added = super.add(element)
                        if (size > RECENT_SHOWN_MAX) {
                            val it = iterator()
                            if (it.hasNext()) {
                                it.next(); it.remove()
                            }
                        }
                        return added
                    }
                }
            )
    }

    /**
     * Fetch every unread notification row and render it locally. Safe to call from any
     * transport's tickle branch, and safe to call concurrently: the dedupe set above
     * and the server-side read-marking below each stop a row being shown twice.
     *
     * @return true when the fetch itself completed — including "nothing unread" and
     *   "not logged in", neither of which retrying would improve. False when the fetch
     *   could not be made: a non-2xx answer, or a throw on the way to one. The caller
     *   MUST act on false: NotificationTickleWorker retries on it, and a dropped tickle
     *   is a notification the user never sees, since the transport does not carry the
     *   content and will not send another.
     *
     *   Per-notification failures (a refresh, a decrypt, marking one row read) stay
     *   best-effort and do NOT make this false: those rows have already been shown, and
     *   a retry would only risk showing them twice.
     */
    suspend fun fetchAndShowNotifications(): Boolean {
        try {
            if (!sessionManager.isLoggedIn()) {
                Log.d(TAG, "Not logged in, ignoring tickle")
                return true
            }

            val response = tracksApi.getNotifications(limit = FETCH_LIMIT, unreadOnly = true)
            if (response.isSuccessful && response.body()?.success == true) {
                val notifications = response.body()!!.data?.notifications ?: emptyList()
                Log.d(TAG, "Fetched ${notifications.size} unread notifications")

                // One roster refetch per circle for this whole batch (NotificationNames.Pass).
                val namesPass = com.pingolin.track.domain.repository.NotificationNames.Pass()
                // The stored "Member joined" push preference, read once per batch and only if a
                // member_joined row is in it.
                var memberJoinedAllowed: Boolean? = null
                for (notif in notifications) {
                    // Skip rows that this process has already shown via a
                    // previous tickle in the same lifetime. The server-
                    // side markNotificationRead call below tells the next
                    // tickle this row is done, but two tickles arriving
                    // within a few hundred ms race that round-trip —
                    // recentShownAlertIds catches the loser.
                    if (!recentShownAlertIds.add(notif.id)) {
                        Log.d(TAG, "Skipping notification ${notif.id} — already shown in this process")
                        continue
                    }

                    // Show each unread notification via AlertHandler, from the
                    // row's envelope plus the metadata keys the server is allowed
                    // to supply (see SERVER_METADATA_KEYS). Personalised copy
                    // ("X arrived at Y") is added below only from what this device
                    // decrypts itself.
                    val alertData = alertDataFor(notif)

                    // Prefer the SEALED event time over the row's
                    // created_at.
                    //
                    // The row's created_at is the moment the server wrote
                    // the row, which is the moment it was TOLD, not the
                    // moment the crossing happened. When the reporting
                    // device was offline the two are far apart. The
                    // reporting client seals the real time with the
                    // circle key; the server stores and forwards it
                    // without being able to read it, and we open it here.
                    applyEncryptedOccurredAt(alertData)

                    val type = notif.notificationType

                    // Who checked in and where, and who is responding: AlertHandler's
                    // `user_name` / `place_name`, keys no row can carry
                    // (SERVER_METADATA_KEYS), filled only from what this device opens
                    // itself. Who asked to take over a circle, and which circle; which
                    // circle a refusal is about: the alerts' named words read these keys,
                    // and this is their only writer.
                    if (type == AlertHandler.TYPE_CHECK_IN || type == AlertHandler.TYPE_SOS_ACKNOWLEDGED ||
                        type == AlertHandler.TYPE_SOS_RESOLVED || type == AlertHandler.TYPE_PRIVACY_RESUMED ||
                        type == AlertHandler.TYPE_CIRCLE_OWNERSHIP_CLAIM || type == AlertHandler.TYPE_CIRCLE_OWNERSHIP_REFUSED
                    ) {
                        nameForAlertHandler(notif, alertData, namesPass)
                    }
                    // A member joined: shown too, named, but only when the person's
                    // stored "Member joined" preference allows it. A row reaches this
                    // fetch by any tickle or the poll, not only by the push that
                    // preference gates, so it is checked here; unknown (the read failed)
                    // shows nothing.
                    if (type == AlertHandler.TYPE_MEMBER_JOINED) {
                        val allowed = memberJoinedAllowed ?: memberJoinedPushAllowed().also { memberJoinedAllowed = it }
                        if (allowed) {
                            nameForAlertHandler(notif, alertData, namesPass)
                            alertData[AlertHandler.KEY_MEMBER_JOINED_SHOWN] = "1"
                        }
                    }

                    // Pull the alert itself into Room BEFORE the notification is
                    // posted, so the map marker and the Safety entry already exist when
                    // the user taps through. This call site is load-bearing: the only
                    // other caller of refreshActiveAlerts is
                    // MainViewModel.initialize(), guarded by `if (initialized) return`
                    // and so run once per process — without the refresh here, an SOS
                    // arriving while the app is alive is never fetched and the push is
                    // its only trace. The ack/resolve types come through here too,
                    // because the refresh is what closes out a stale local row.
                    //
                    // An SOS itself is checked before it may ring: the same list,
                    // judged by the receivers' rules. What the check found goes to
                    // AlertHandler in the alert's data, under keys no row can carry
                    // (SERVER_METADATA_KEYS). No outcome means it couldn't be checked,
                    // and it rings regardless.
                    if (type == AlertHandler.TYPE_SOS_ALERT) {
                        when (val check = sosRepository.checkPushed(alertData["sos_id"])) {
                            is com.pingolin.track.domain.repository.SosRepository.PushCheck.Alarming -> {
                                alertData[AlertHandler.KEY_SOS_CHECK] = AlertHandler.SOS_CHECK_ALARMING
                                check.raiserName?.let { alertData[AlertHandler.KEY_SOS_RAISER_NAME] = it }
                                check.circleName?.let { alertData[AlertHandler.KEY_SOS_CIRCLE_NAME] = it }
                            }
                            com.pingolin.track.domain.repository.SosRepository.PushCheck.NotLive ->
                                alertData[AlertHandler.KEY_SOS_CHECK] = AlertHandler.SOS_CHECK_NOT_LIVE
                            com.pingolin.track.domain.repository.SosRepository.PushCheck.CantCheck -> Unit
                        }
                    } else if (type == AlertHandler.TYPE_SOS_ACKNOWLEDGED || type == AlertHandler.TYPE_SOS_RESOLVED) {
                        try {
                            sosRepository.refreshActiveAlerts()
                        } catch (e: Exception) {
                            Log.w(TAG, "SOS refresh before alert failed: ${e.message}")
                        }
                        // "…and N others are responding": the people who answered
                        // before this one, from the alert's own list (GET /sos/{id},
                        // mine only) — the bell's rule. Never acknowledgment_count,
                        // which can count my own tap. No list, or they aren't on it:
                        // no "and N others".
                        if (type == AlertHandler.TYPE_SOS_ACKNOWLEDGED) {
                            val earlier = alertData["sos_id"]?.let { sos ->
                                runCatching {
                                    myAlertResponders.earlierThan(sos, notif.metadata?.get("acknowledged_by")?.toString())
                                }.getOrNull()
                            }
                            if (earlier != null && earlier > 0) alertData[AlertHandler.KEY_OTHERS_RESPONDING] = earlier.toString()
                        }
                    }

                    val isDestinationType = type == AlertHandler.TYPE_DESTINATION_SHARED ||
                        type == AlertHandler.TYPE_DESTINATION_ARRIVAL
                    if (isDestinationType) {
                        // Sync the destinations cache BEFORE display so the
                        // map/list show the new (or removed) destination by
                        // the time the user taps the notification, and so
                        // the saved-place match in resolveNotificationCopy
                        // has the row's coordinates available.
                        try {
                            destinationRepository.refreshDestinations()
                        } catch (e: Exception) {
                            Log.w(TAG, "Destination refresh before alert failed: ${e.message}")
                        }
                        // E2EE personalisation: decrypt actor + destination
                        // name from the metadata ciphertexts and hand the
                        // plaintext to AlertHandler ("Alice arrived at
                        // Home"). Best-effort — on any failure the handler
                        // falls back to generic copy.
                        try {
                            val copy = destinationRepository.resolveNotificationCopy(alertData)
                            copy.actorName?.let { alertData["actor_name"] = it }
                            copy.destLabel?.let { alertData["dest_label"] = it }
                            // Carry the destination's coordinates so tapping the
                            // notification zooms the map straight to it (AlertHandler
                            // builds a navigate_to=destination intent; MainActivity zooms).
                            copy.destLat?.let { alertData["dest_lat"] = it.toString() }
                            copy.destLng?.let { alertData["dest_lng"] = it.toString() }
                        } catch (e: Exception) {
                            Log.w(TAG, "Destination copy resolution failed: ${e.message}")
                        }
                    }

                    if (type == AlertHandler.TYPE_CIRCLE_INVITE) {
                        // Neither the push nor the row carries names —
                        // the server keeps them off the relay and out of
                        // the 90-day notification row — but the pending
                        // invite itself has both, fetched here over this
                        // client's own authenticated connection.
                        // Best-effort: on any failure the banner falls
                        // back to "Someone" / "a circle", and the detail
                        // level (SessionManager) still decides what the
                        // lock screen shows. Mirrors the iOS app's
                        // NotificationExtension.
                        try {
                            val invites = tracksApi.getReceivedInvites()
                            if (invites.isSuccessful && invites.body()?.success == true) {
                                inviteNamesFor(invites.body()?.data ?: emptyList(), notif.circleId)
                                    ?.let { (inviter, circle) ->
                                        inviter?.let { alertData["inviter_name"] = it }
                                        circle?.let { alertData["circle_name"] = it }
                                    }
                            } else {
                                Log.w(TAG, "Invite name lookup returned ${invites.code()}")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Invite name lookup failed: ${e.message}")
                        }
                    }
                    // An SOS alarm sounds once per SOS on this device: checked and
                    // alarming, or not checkable, its post goes through the ring routine
                    // under the row's circle and SOS ids, and one that sounded here
                    // already is posted with no sound. Ids that aren't UUIDs: it rings,
                    // with no once-only mark.
                    val ringIds = if (type == AlertHandler.TYPE_SOS_ALERT &&
                        alertData[AlertHandler.KEY_SOS_CHECK] != AlertHandler.SOS_CHECK_NOT_LIVE
                    ) {
                        sosRingIds(alertData)
                    } else {
                        null
                    }
                    if (ringIds != null) {
                        sosRepository.ringOnce(ringIds.first, ringIds.second) { sound ->
                            if (!sound) alertData[AlertHandler.KEY_SOS_SILENT] = "1"
                            alertHandler.handleAlert(notif.notificationType, alertData)
                        }
                    } else {
                        alertHandler.handleAlert(notif.notificationType, alertData)
                    }

                    // Mark the notification read on the server so
                    // the next tickle's unreadOnly fetch doesn't
                    // return it again — this is the load-bearing
                    // half of the duplicate suppression. Failures
                    // here are non-fatal (the in-memory dedupe set
                    // still suppresses the duplicate locally for
                    // the rest of the process lifetime), but they
                    // mean the duplicate can reappear after a
                    // restart, so we log them.
                    try {
                        val mr = tracksApi.markNotificationRead(notif.id)
                        if (!mr.isSuccessful || mr.body()?.success != true) {
                            Log.w(TAG, "markNotificationRead ${notif.id} returned ${mr.code()} ${mr.body()?.error}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "markNotificationRead ${notif.id} threw: ${e.message}")
                    }

                }
                return true
            } else {
                Log.w(TAG, "Failed to fetch notifications: ${response.code()}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching notifications after tickle", e)
            return false
        }
    }

    /**
     * Put this device's own names into [alertData] for [notif]: the person ([AlertHandler] reads
     * `user_name`), the place (`place_name`), the circle (`circle_name`, a member_joined's) and, for
     * a check-in, its kind (`check_in_type`), read back from the server's words. Best-effort:
     * nothing resolved leaves AlertHandler's "A member" / "Someone", and the detail setting still
     * decides what the shade shows.
     */
    private suspend fun nameForAlertHandler(
        notif: com.pingolin.track.data.remote.dto.NotificationResponse,
        alertData: MutableMap<String, String>,
        pass: com.pingolin.track.domain.repository.NotificationNames.Pass,
    ) {
        val metadata = notif.metadata.orEmpty().mapValues { it.value.toString() }
        val names = try {
            notificationNames.namesFor(notif.notificationType, notif.circleId, metadata, pass)
        } catch (e: Exception) {
            Log.w(TAG, "Naming ${notif.notificationType} failed: ${e.message}")
            return
        }
        if (notif.notificationType == AlertHandler.TYPE_SOS_RESOLVED) {
            // Only who CLOSED it, never whose request it was: AlertHandler.sosResolvedCopy.
            if (names.closer) alertData[AlertHandler.KEY_CLOSER_NAME] = names.person ?: "Someone"
            return
        }
        names.person?.let { alertData["user_name"] = it }
        names.place?.let { alertData["place_name"] = it }
        names.circle?.let { alertData["circle_name"] = it }
        if (notif.notificationType == AlertHandler.TYPE_CHECK_IN) {
            com.pingolin.track.domain.repository.NamedNotificationCopy.checkInTypeOf(notif.body)
                ?.let { alertData["check_in_type"] = it }
        }
    }

    /**
     * Whether the person's stored preference lets a member_joined notice show: `member_joined_push`,
     * or `push_enabled` when that is unset (the server's own fallback). False when it can't be read.
     */
    private suspend fun memberJoinedPushAllowed(): Boolean = try {
        val response = tracksApi.getNotificationPreferences()
        val prefs = response.body()?.data?.takeIf { response.isSuccessful && response.body()?.success == true }
        prefs != null && ((prefs["member_joined_push"] as? Boolean) ?: (prefs["push_enabled"] as? Boolean) ?: true)
    } catch (e: Exception) {
        Log.w(TAG, "Notification preferences unreadable; member_joined not shown: ${e.message}")
        false
    }

    /**
     * Replace `created_at` with the decrypted event time when the metadata carries
     * one, in place.
     *
     * E2EE: the pair is `encrypted_occurred_at` + `occurred_at_nonce`, sealed with
     * the circle key by whichever client detected the crossing or arrival — the
     * server cannot read it, which is the point. Same convention as the
     * `encrypted_dest_name` / `dest_name_nonce` pair the destination path already
     * decrypts.
     *
     * Best-effort by design. A failure here (missing circle key, rotated key, a client
     * that never sealed the field) leaves the server-side `created_at` in place and the
     * notification still ships: a slightly-late stamp beats no notification.
     */
    private fun applyEncryptedOccurredAt(alertData: MutableMap<String, String>) {
        EncryptedOccurredAt.applyEncryptedOccurredAt(alertData) { circleId, nonce, ciphertext ->
            cryptoManager.decryptDataV2(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(nonce = nonce, ciphertext = ciphertext),
                EncryptedOccurredAt.OCCURRED_AT_FIELD_TAG,
            )
        }
    }

}
