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

package com.pingolin.track.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pingolin.track.R
import com.pingolin.track.TrackApplication
import com.pingolin.track.domain.placealerts.PlaceAlertEvaluator
import com.pingolin.track.domain.placealerts.PlaceAlertRules
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.NamedNotificationCopy
import com.pingolin.track.ui.main.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shows alerts as local notifications. Every alert comes in through
 * [handleAlert]: the ones the server stored are fetched and passed in by
 * NotificationTickleFetcher, and the rest are raised on this device by its own
 * monitors and evaluators.
 */
@Singleton
class AlertHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val circleRepository: CircleRepository,
    private val locationRepository: com.pingolin.track.domain.repository.LocationRepository,
    private val sessionManager: SessionManager,
    // Fused on gms, android.location.LocationManager on libre — see LocationProvider.
    private val locationProvider: com.pingolin.track.location.LocationProvider,
    // Lazy: this class -> LocationRepository -> PlaceAlertEvaluator -> AlertHandler
    // is a DI cycle. Used only to claim a server-routed place crossing against the
    // same ledger the local evaluator writes, so the crossing is shown ONCE.
    private val placeAlertEvaluator: dagger.Lazy<com.pingolin.track.domain.placealerts.PlaceAlertEvaluator>,
    // Lazy for the same reason: SosRepository pulls in the crypto manager, and
    // building that eagerly on the notification path would put key work in front
    // of showing an alert.
    private val sosRepository: dagger.Lazy<com.pingolin.track.domain.repository.SosRepository>
) {
    companion object {
        private const val TAG = "AlertHandler"

        /**
         * How long ONE wake refresh attempt may take before it is abandoned and the
         * next attempt scheduled. Generous enough to cover a slow mobile round-trip
         * for a couple of circles, short enough that a stalled network does not eat
         * the whole retry budget in a single try.
         */
        private const val PLACE_WAKE_REFRESH_TIMEOUT_MS = 10_000L

        /**
         * Delay BEFORE each wake refresh attempt, so the first is immediate.
         *
         * Spans ~58s of wall clock, sized against how the mover's position actually
         * arrives. The crossing report carries no location by design, so the position
         * comes on the ordinary tracking stream — and a crossing arms
         * `forceSendNextFix`, so it rides the NEXT fix past the send filter rather
         * than waiting for one the filter happens to allow. Crossing a boundary means
         * moving, so that fix is POLL_MOVING_MS (20s) away at worst, plus the send
         * and ingest. The last attempt sits beyond that.
         *
         * Bounded deliberately: a push wake holding a coroutine for minutes is one
         * Doze will kill anyway, and the scheduled passes (BACKGROUND_EVAL_INTERVAL_MS,
         * then LocationHeartbeatWorker) remain the backstop. The common case — the
         * position already on the server — succeeds on the first attempt and costs
         * nothing extra.
         */
        private val PLACE_WAKE_RETRY_BACKOFF_MS = longArrayOf(0L, 8_000L, 18_000L, 32_000L)

        // Notification types
        const val TYPE_SOS_ALERT = "sos_alert"
        const val TYPE_SOS_ACKNOWLEDGED = "sos_acknowledged"
        const val TYPE_SOS_RESOLVED = "sos_resolved"

        /**
         * What a pushed SOS's check found (SosRepository.checkPushed), put in the alert's data by
         * NotificationTickleFetcher on this device. Never a key a row can carry: the fetcher copies
         * only SERVER_METADATA_KEYS from one. Absent means no check could run, and the alarm rings
         * in the generic words.
         */
        internal const val KEY_SOS_CHECK = "sos_check"
        internal const val SOS_CHECK_ALARMING = "alarming"
        internal const val SOS_CHECK_NOT_LIVE = "not_live"

        /**
         * Set by NotificationTickleFetcher when the person's stored "Member joined" push preference
         * allows a member_joined notice; never carried by a row (SERVER_METADATA_KEYS).
         */
        internal const val KEY_MEMBER_JOINED_SHOWN = "member_joined_shown"

        /**
         * An inactivity alert's words as this phone's evaluator made them
         * (MonitoringManager.inactiveSince). Set only there, and never carried by a row
         * (SERVER_METADATA_KEYS): a server row's "body" is never shown as an inactivity alert.
         */
        internal const val KEY_INACTIVE_SINCE = "inactive_since"

        /** The raiser's and circle's names as this device opened them, for an alarming SOS only. */
        internal const val KEY_SOS_RAISER_NAME = "sos_raiser_name"
        internal const val KEY_SOS_CIRCLE_NAME = "sos_circle_name"

        /**
         * How many others had answered this person's SOS BEFORE the one this notice names (their
         * place in the alert's own list, GET /sos/{id}); and who closed an alert, as this device
         * names them ("Someone" when it can't). Put by NotificationTickleFetcher; never keys a
         * row can carry (SERVER_METADATA_KEYS).
         */
        internal const val KEY_OTHERS_RESPONDING = "others_responding"
        internal const val KEY_CLOSER_NAME = "closer_name"

        /**
         * "1": this SOS's alarm already sounded on this device (SosRepository.ringOnce), so its post
         * is updated with no sound. Put by NotificationTickleFetcher; never a key a row can carry.
         */
        internal const val KEY_SOS_SILENT = "sos_silent"

        /**
         * "true": a local place-alert copy that replaces the server push of the same crossing already in the
         * shade, so it updates that notification without sounding again. Put by PlaceAlertEvaluator; never
         * a key a row or a push can carry (NotificationTickleFetcher.SERVER_METADATA_KEYS).
         */
        internal const val KEY_REPLACES_SHOWN = "replaces_shown"
        /** LOCAL-only types, raised by this device's own subscriber-side evaluator
         * (PlaceAlertEvaluator). The backend does not emit these; see below. */
        const val TYPE_PLACE_ARRIVAL = "place_arrival"
        const val TYPE_PLACE_DEPARTURE = "place_departure"

        /**
         * The strings the backend emits for place alerts. All three occur, and which one
         * arrives depends on the delivery path:
         *
         *  - `place_alert_enter` / `place_alert_exit` — the server's typed payload,
         *    reaching the notification WebSocket and UnifiedPush.
         *  - `place_alert` — the type of the STORED notification row, which the server
         *    sets with NO direction. This is what FCM delivers, because every FCM
         *    message is `type = "tickle"`: the app re-fetches the row and dispatches
         *    on its stored type, so the directional strings never reach FCM at all.
         *
         * All three MUST stay wired into the dispatcher — see the `else` branch there for
         * what a missing one costs.
         */
        const val TYPE_PLACE_ALERT_ENTER = "place_alert_enter"
        const val TYPE_PLACE_ALERT_EXIT = "place_alert_exit"
        const val TYPE_PLACE_ALERT = "place_alert"
        const val TYPE_CHECK_IN = "check_in"
        const val TYPE_CIRCLE_INVITE = "circle_invite"
        const val TYPE_MEMBER_JOINED = "member_joined"
        const val TYPE_LOW_BATTERY = "low_battery"
        const val TYPE_OFFLINE = "offline"
        const val TYPE_INACTIVITY = "inactivity" // Alias for offline alerts from backend
        const val TYPE_BACK_ONLINE = "inactivity_recovered" // "back online" — inverse of offline
        const val TYPE_DESTINATION_SHARED = "destination_shared"
        const val TYPE_DESTINATION_ARRIVAL = "destination_arrival"
        /** The dead man's switch for an unreachable circle owner. */
        const val TYPE_CIRCLE_OWNERSHIP_CLAIM = "circle_ownership_claim"
        const val TYPE_CIRCLE_OWNERSHIP_TRANSFERRED = "circle_ownership_transferred"
        /** The claimant's notice that the owner refused. */
        const val TYPE_CIRCLE_OWNERSHIP_REFUSED = "circle_ownership_refused"
        /**
         * "Update Pingolin": sent to a member whose app is too old for their circle (no
         * signing key). Its audience is old builds, which show the push's own text; a
         * build that knows this type is not too old.
         */
        const val TYPE_UPDATE_REQUIRED = "update_required"

        /** A pause of this account's ran out and sharing started again (the server's notice when a
         * pause expires).
         */
        const val TYPE_PRIVACY_RESUMED = "privacy_resumed"

        // Notification group keys for bundling related notifications
        private const val GROUP_SOS = "com.pingolin.track.SOS"
        /**
         * Every place crossing, whichever path delivered it: a server push and the named local
         * copy that replaces it in place share a group, so the replacement never MOVES it.
         * Moving a posted notification into another group leaves its first group's summary with
         * nothing under it; a device that prunes that summary cancels the notification with it
         * before the move lands, and the copy is then posted as new and sounds again (API 34).
         */
        private const val GROUP_PLACES_LOCAL = "com.pingolin.track.PLACES_LOCAL"
        private const val GROUP_STATUS = "com.pingolin.track.STATUS"
        private const val GROUP_MESSAGES = "com.pingolin.track.MESSAGES"

        // Summary notification IDs (fixed IDs for group summaries)
        private const val SUMMARY_ID_SOS = 10000
        // 10001 is reserved and not reused.
        private const val SUMMARY_ID_STATUS = 10002
        private const val SUMMARY_ID_MESSAGES = 10003
        private const val SUMMARY_ID_PLACES_LOCAL = 10004

        /** Body shown at the "generic" detail level — no names, places,
         *  or message content. Matches the server's privacy-generic copy. */
        internal const val GENERIC_MESSAGE = "Tap to view"

        /** Who and where a place notification says when this device can't name them: the local copy's stand-ins. */
        internal const val UNNAMED_MEMBER = "A member"
        internal const val UNNAMED_PLACE = "a place"

        /**
         * Ids of group summaries with no children left, given everything currently
         * posted. Pure, and in the companion so it can be tested without
         * constructing an AlertHandler — the Android glue is only the mapping from
         * StatusBarNotification and the cancel calls.
         *
         * An ungrouped summary counts as empty: it can never gain a child.
         */
        internal fun emptySummaryIds(active: List<PostedNotification>): List<Int> {
            val groupsWithChildren = active
                .filterNot { it.isSummary }
                .mapNotNull { it.group }
                .toSet()
            return active
                .filter { it.isSummary }
                .filter { it.group == null || it.group !in groupsWithChildren }
                .map { it.id }
        }
    }

    /** A notification currently in the shade, reduced to what pruning needs. */
    internal data class PostedNotification(
        val id: Int,
        val group: String?,
        val isSummary: Boolean,
    )

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wake-retry schedule, overridable so tests do not spend the real ~58 seconds.
     * Production always uses [PLACE_WAKE_RETRY_BACKOFF_MS]; a test that wants the
     * single-attempt behaviour sets `longArrayOf(0L)`.
     */
    @VisibleForTesting
    internal var placeWakeBackoffMs: LongArray = PLACE_WAKE_RETRY_BACKOFF_MS

    fun handleAlert(type: String, data: Map<String, String>) {
        // alert_id is the server-side notification row id when the
        // alert arrived via a tickle re-fetch, or the originating
        // event id for local boundary/SOS pushes. We pass it as the
        // stable dedupe key so Android replaces an existing
        // notification with the same id instead of stacking a new
        // one, so repeated deliveries never pile up as duplicates.
        // Local-event callers that don't set alert_id get the
        // timestamp-based ID of the showNotification default.
        val alertId = data["alert_id"]?.takeIf { it.isNotBlank() }
        when (type) {
            TYPE_SOS_ALERT -> handleSosAlert(data, alertId)
            TYPE_SOS_ACKNOWLEDGED -> handleSosAcknowledged(data, alertId)
            TYPE_SOS_RESOLVED -> handleSosResolved(data, alertId)
            TYPE_PLACE_ARRIVAL, TYPE_PLACE_ALERT_ENTER -> handlePlaceArrival(data, alertId)
            TYPE_PLACE_DEPARTURE, TYPE_PLACE_ALERT_EXIT -> handlePlaceDeparture(data, alertId)
            TYPE_PLACE_ALERT -> handleDirectionlessPlaceAlert(data, alertId)
            TYPE_CHECK_IN -> handleCheckIn(data, alertId)
            TYPE_CIRCLE_INVITE -> handleCircleInvite(data, alertId)
            TYPE_MEMBER_JOINED -> handleMemberJoined(data, alertId)
            TYPE_LOW_BATTERY -> handleLowBattery(data, alertId)
            TYPE_OFFLINE, TYPE_INACTIVITY -> handleOffline(data, alertId)
            TYPE_BACK_ONLINE -> handleBackOnline(data, alertId)
            TYPE_DESTINATION_SHARED -> handleDestinationShared(data, alertId)
            TYPE_DESTINATION_ARRIVAL -> handleDestinationArrival(data, alertId)
            TYPE_CIRCLE_OWNERSHIP_CLAIM -> handleOwnershipClaim(data, alertId)
            TYPE_CIRCLE_OWNERSHIP_TRANSFERRED -> handleOwnershipTransferred(data, alertId)
            TYPE_CIRCLE_OWNERSHIP_REFUSED -> handleOwnershipRefused(data, alertId)
            TYPE_PRIVACY_RESUMED -> handlePrivacyResumed(data, alertId)
            // This app already signs: the request reached it after an update. Nothing to say.
            TYPE_UPDATE_REQUIRED -> Log.i(TAG, "update_required: this app is up to date; not shown")
            // This branch must never be deleted. A `when` used as a STATEMENT need
            // not be exhaustive, so without it an unrecognised type is discarded in
            // total silence — no notification, no log, nothing to notice. That is
            // how a type-string mismatch could go unseen: a server type this
            // dispatcher doesn't match would be dropped here, yet its row would
            // still appear in the in-app list, so the app would look like it
            // received the alert. Log.w (unlike Log.d/Log.v) survives release
            // minification, which keeps such a mismatch visible in logcat.
            else -> Log.w(TAG, "Unhandled alert type '$type' — notification dropped")
        }
    }

    /** Intent for a destination notification tap. When the destination's coordinates
     * were captured at build time (the tickle fetcher folds dest_lat/dest_lng in after
     * resolveNotificationCopy), jump to the map and zoom straight to it; otherwise fall
     * back to the in-app notifications list. */
    private fun destinationTapIntent(data: Map<String, String>): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            val lat = data["dest_lat"]?.toDoubleOrNull()
            val lng = data["dest_lng"]?.toDoubleOrNull()
            if (lat != null && lng != null) {
                putExtra("navigate_to", "destination")
                putExtra("dest_lat", lat)
                putExtra("dest_lng", lng)
            } else {
                putExtra("navigate_to", "notifications")
            }
        }

    private fun handleDestinationShared(data: Map<String, String>, dedupeKey: String?) {
        // The tickle re-fetch path (NotificationTickleFetcher) folds
        // E2EE-decrypted copy into the data map: "actor_name" (creator's
        // display name) and "dest_label" (matched saved-place name, else
        // the decrypted destination name/address). The server payload is
        // content-free, so absent those keys we fall back to generic copy.
        // A tap goes where [destinationTapIntent] says: the map when the
        // coordinates came along, else the in-app notifications list,
        // which holds the matching personalised record.
        val userName = data["actor_name"] ?: "A circle member"
        val destLabel = data["dest_label"]
        val intent = destinationTapIntent(data)
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Destination shared",
            message = withEventStamp(
                if (destLabel != null) {
                    "$userName created a destination for $destLabel"
                } else {
                    "$userName shared a new destination"
                },
                data,
            ),
            intent = intent,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = statusGroup(),
            dedupeKey = dedupeKey,
            namesOnlyMessage = withEventStamp("$userName shared a new destination", data),
        )
    }

    private fun handleDestinationArrival(data: Map<String, String>, dedupeKey: String?) {
        val destLabel = data["dest_label"]

        // The creator's own arrival row (metadata self=true) can reach this
        // path via a later tickle re-fetch — after LocationTrackingService
        // already posted the local "You've arrived!". Reuse that exact
        // dedupe key so the re-fetched copy replaces the local notification
        // instead of stacking a duplicate.
        if (data["self"] == "true") {
            showSelfArrival(data["destination_id"] ?: (dedupeKey ?: ""), destLabel)
            return
        }

        val userName = data["actor_name"] ?: "A circle member"
        val intent = destinationTapIntent(data)
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Arrived",
            message = withEventStamp(
                if (destLabel != null) {
                    "$userName arrived at $destLabel"
                } else {
                    "$userName arrived at their destination"
                },
                data,
            ),
            intent = intent,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = statusGroup(),
            dedupeKey = dedupeKey,
            namesOnlyMessage = withEventStamp("$userName arrived at their destination", data),
        )
    }

    /**
     * Local "You've arrived!" notification for the destination creator.
     * Posted by LocationTrackingService the moment auto-arrival is
     * accepted by the server — works without a push round-trip (the
     * server intentionally sends the creator no arrival push; their
     * in-app record is inserted server-side).
     */
    fun showSelfArrival(destinationId: String, destLabel: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "You've arrived!",
            // Local event: LocationTrackingService posts this the moment arrival is
            // accepted, so an empty map (stamp = now) is the accurate time.
            message = withEventStamp(
                if (destLabel != null) "You arrived at $destLabel" else "You arrived at your destination",
                emptyMap(),
            ),
            intent = intent,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = statusGroup(),
            // Stable per-destination key: the arrival detector is
            // re-entrant across service restarts, and a second post must
            // replace rather than stack.
            dedupeKey = "self-arrival-$destinationId",
            namesOnlyMessage = withEventStamp("You arrived at your destination", emptyMap()),
        )
    }

    /**
     * An SOS push, after its check:
     *  - checked, and alarming: the alarm, named as this device opened the names, else in the
     *    generic words;
     *  - checked, and not live: nothing, and an earlier post of it goes;
     *  - no check could run ([KEY_SOS_CHECK] absent): the alarm in the generic words. A genuine
     *    SOS always rings.
     * The words are this app's own; no name or circle ever comes from the server. Its post is keyed
     * by the SOS, so a later check can take it down ([cancelSos]). One that already sounded on this
     * device ([KEY_SOS_SILENT]) is posted with no sound.
     */
    private fun handleSosAlert(data: Map<String, String>, dedupeKey: String?) {
        val sosId = data["sos_id"]?.takeIf { it.isNotBlank() }

        // Tell the server the push landed, so the person who sent it can be told
        // whether anyone got it. Fired here — on the push path — and nowhere
        // else: reporting a receipt after learning about an alert by polling
        // would confirm a notification that never arrived.
        //
        // Off the notification path entirely. This is bookkeeping for somebody
        // else's screen and must never delay showing the alert on this one.
        sosId?.let { backgroundScope.launch { sosRepository.get().reportSosReceipt(it) } }

        val key = sosId?.let(::sosNotificationKey) ?: dedupeKey
        val check = data[KEY_SOS_CHECK]
        if (check == SOS_CHECK_NOT_LIVE) {
            key?.let { cancelNotification(it) }
            return
        }
        val alarming = check == SOS_CHECK_ALARMING
        postSosAlarm(
            sosId = sosId,
            key = key,
            rowId = dedupeKey,
            raiserName = data[KEY_SOS_RAISER_NAME]?.takeIf { alarming },
            circleName = data[KEY_SOS_CIRCLE_NAME]?.takeIf { alarming },
            silent = data[KEY_SOS_SILENT] == "1",
        )
    }

    /**
     * An SOS a refresh judged alarming, the first time on this device (SosRepository): the checked
     * push's post, with its sound, named as this device opened the names (null: the generic words).
     */
    fun ringSos(sosId: String, raiserName: String?, circleName: String?) = postSosAlarm(
        sosId = sosId,
        key = sosNotificationKey(sosId),
        rowId = null,
        raiserName = raiserName,
        circleName = circleName,
        silent = false,
    )

    /** The SOS alarm's post, on the SOS channel at PRIORITY_MAX, keyed by [key]. */
    private fun postSosAlarm(
        sosId: String?,
        key: String?,
        rowId: String?,
        raiserName: String?,
        circleName: String?,
        silent: Boolean,
    ) {
        val userName = raiserName ?: "A circle member"
        val circle = circleName ?: "your circle"
        // No name at the "generic" detail level, the title's included.
        val title = if (sessionManager.getNotificationDetailLevel() == SessionManager.NOTIF_DETAIL_GENERIC) {
            "A circle member needs attention"
        } else {
            "$userName needs attention"
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "sos")
            putExtra("alert_id", rowId)
            // Carried from the stored notification's metadata (folded into
            // `data` by the tickle fetch). Lets the tap land on the alert's own
            // map marker instead of the Safety tab; absent for older rows.
            sosId?.let { putExtra("sos_id", it) }
        }

        showNotification(
            channelId = TrackApplication.SOS_CHANNEL_ID,
            title = title,
            message = "Asked their circle to check on them in $circle",
            intent = intent,
            priority = NotificationCompat.PRIORITY_MAX,
            group = sosGroup(),
            dedupeKey = key,
            namesOnlyMessage = "$userName needs attention",
            silent = silent,
        )
    }

    /** An SOS's post, by the SOS's id in its one spelling. */
    private fun sosNotificationKey(sosId: String): String = "sos:${AccountIds.storeKey(sosId)}"

    /**
     * Take down this device's post of the SOS [sosId]: a later check says it isn't live, it ended,
     * or the list dropped it (checked and not live: nothing shown).
     */
    fun cancelSos(sosId: String) = cancelNotification(sosNotificationKey(sosId))

    private fun cancelNotification(dedupeKey: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(stableNotificationId(dedupeKey))
        pruneEmptyGroupSummaries(notificationManager)
    }

    /**
     * A notice that follows the alarm: its words, and no sound. Who is responding: "Ana is
     * responding", "Ana and 2 others are responding". The title names nobody: titles survive the
     * generic detail level.
     */
    private fun handleSosAcknowledged(data: Map<String, String>, dedupeKey: String?) {
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Acknowledged",
            message = sosAcknowledgedCopy(data),
            group = sosGroup(),
            dedupeKey = dedupeKey,
            silent = true,
        )
    }

    /** Who is responding, with how many others ([KEY_OTHERS_RESPONDING]); "Someone" when unnamed. */
    internal fun sosAcknowledgedCopy(data: Map<String, String>): String =
        NamedNotificationCopy.respondingCopy(
            data["user_name"]?.takeIf { it.isNotBlank() } ?: "Someone",
            data[KEY_OTHERS_RESPONDING]?.toIntOrNull() ?: 0,
        )

    /**
     * "This alert is over" — sent to THE WHOLE CIRCLE except whoever closed it,
     * because until they hear it every member is still looking at a live
     * "someone needs attention" marker for an emergency that has ended.
     *
     * The copy must therefore be audience-neutral: every member reads it, and the
     * raiser is not even the usual case, since they are excluded unless someone
     * else closed it.
     *
     * The one name it may carry is WHO CLOSED the alert: the stored row's
     * `closed_by`, named by this device ([KEY_CLOSER_NAME]) — "Ana closed the
     * alert". Only the raiser's row carries it, when someone else (an admin)
     * closed it; every other row keeps the server's words. The server lets only
     * the raiser close an alert (alerts are personal), so few rows carry it; it is
     * still read for as long as such rows last. Never the raiser's name here: the
     * raiser is a different person from the one who closed it.
     *
     * It must also be true for a FALSE ALARM. Marking a false alarm deliberately
     * emits this same type — it ends a live alert exactly as a resolution does
     * and leaves the identical stale marker — and nothing in the payload
     * distinguishes the two. That distinction lives in the alert's own status,
     * which the next fetch reads.
     *
     * A notice that follows the alarm rings nowhere: its words, and no sound. The
     * server can send one for an SOS this phone hides.
     */
    private fun handleSosResolved(data: Map<String, String>, dedupeKey: String?) {
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Resolved",
            message = sosResolvedCopy(data),
            group = sosGroup(),
            dedupeKey = dedupeKey,
            silent = true,
        )
    }

    /**
     * The server's own body when it came with one (the FCM tickle path folds the
     * stored row's body in), and the same wording otherwise — UnifiedPush and the
     * WebSocket carry the typed payload, which has no body — so every delivery
     * path reads alike.
     */
    internal fun sosResolvedCopy(data: Map<String, String>): String =
        data[KEY_CLOSER_NAME]?.takeIf { it.isNotBlank() }?.let { NamedNotificationCopy.closedCopy(it) }
            ?: serverCopy(data, "A circle member marked the request to check on them resolved")

    /**
     * Prefer a finished body someone else already wrote over anything assembled
     * here, falling back to [fallback] when there is none.
     *
     * Two suppliers, and which one is in play depends on the delivery path:
     *  - A place crossing this device evaluated LOCALLY passes its finished copy
     *    in "body" — it already knows the real crossing time and whether the
     *    member is still there (see PlaceAlertRules.describe).
     *  - The FCM tickle path folds the STORED notification row's body in (see
     *    NotificationTickleFetcher), so the server's own wording arrives with
     *    it. UnifiedPush and the WebSocket carry the typed payload instead,
     *    which has no body at all — hence a fallback that must read correctly
     *    on its own.
     */
    private fun serverCopy(data: Map<String, String>, fallback: String): String =
        data["body"]?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * Short local date + time for the body of a place or destination
     * notification — "7/28, 7:25 PM".
     *
     * Uses the event's `created_at` when the caller supplied one, falling back to
     * now. Two callers supply it: the tickle re-fetch (the notification row's
     * server timestamp) and PlaceAlertEvaluator (`surfacing.at`, the real crossing
     * time off the member's location points).
     *
     * Do NOT treat the fallback as "this device detected it a moment ago". The local
     * evaluator replays ANOTHER member's shared location batch, which can be up to
     * STALENESS_WINDOW_MS (2h) old before the crossing is silenced, so the moment this
     * device notices a crossing says nothing about when it happened. The fallback is
     * strictly a last-resort guess for a caller that has no event time at all.
     *
     * The stamp matters precisely when a push lands late (device asleep, offline,
     * doze): a notification read at 9pm about a 7:25pm arrival must not imply it
     * just happened.
     *
     * Locale- and settings-aware rather than a hardcoded "M/d, h:mm a": the date
     * skeleton reorders to d/M where that is the convention, and getTimeFormat
     * honours the device's 24-hour setting (so this reads "28/07, 19:25" in the
     * places that expect it).
     */
    private fun eventStamp(data: Map<String, String>): String {
        val millis = ServerTime.epochMs(data["created_at"]) ?: System.currentTimeMillis()
        val locale = java.util.Locale.getDefault()
        val date = java.text.SimpleDateFormat(
            android.text.format.DateFormat.getBestDateTimePattern(locale, "Md"),
            locale,
        ).format(java.util.Date(millis))
        val time = android.text.format.DateFormat.getTimeFormat(context)
            .format(java.util.Date(millis))
        return "$date, $time"
    }

    /** Appends [eventStamp] to a notification body. */
    private fun withEventStamp(text: String, data: Map<String, String>): String =
        "$text · ${eventStamp(data)}"

    /**
     * Show a place crossing once, whichever path delivered it, and never not at all.
     *
     * A crossing this device evaluated itself arrives already claimed and named, carrying
     * `local_evaluated`, and is posted as it is: under its own id, or under the id of the
     * server push of the same crossing already in the shade, which it then replaces in place
     * without sounding again ([KEY_REPLACES_SHOWN]).
     *
     * A server-routed one goes to [postServerPlaceAlert].
     */
    private fun handlePlaceCrossing(
        data: Map<String, String>,
        dedupeKey: String?,
        isArrival: Boolean,
    ) {
        if (data["local_evaluated"] != "true") {
            postServerPlaceAlert(
                data,
                dedupeKey,
                if (isArrival) PlaceAlertRules.Transition.ENTER else PlaceAlertRules.Transition.EXIT,
            )
            return
        }
        val userName = data["user_name"]?.takeIf { it.isNotBlank() } ?: UNNAMED_MEMBER
        val placeName = data["place_name"]?.takeIf { it.isNotBlank() } ?: UNNAMED_PLACE
        val detailed = serverCopy(
            data,
            if (isArrival) "$userName arrived at $placeName" else "$userName left $placeName"
        )
        showNotification(
            // Locally-evaluated crossings get their own channel so a crossing
            // this device detected itself is distinguishable from a
            // server-routed push of the same crossing — from outside the app
            // (`dumpsys notification` exposes `channel=`) as well as in the
            // user's per-channel notification settings. Same importance, so
            // where detection happened never changes how loudly it arrives.
            channelId = TrackApplication.PLACE_ALERTS_LOCAL_CHANNEL_ID,
            // Title stays event-type-only on purpose: titles bypass
            // resolveDisplayMessage, so putting the place name here would leak
            // it on the lock screen even at the "generic" detail level the user
            // explicitly chose. The place name lives in the body, which IS
            // redacted per that setting.
            title = data["title"]?.takeIf { it.isNotBlank() }
                ?: if (isArrival) "Arrival" else "Departure",
            message = withEventStamp(detailed, data),
            group = placesLocalGroup(),
            dedupeKey = dedupeKey,
            // Stamped at every detail level: a time says nothing about who or
            // where, so it survives redaction that removes the place name.
            namesOnlyMessage = withEventStamp(
                if (isArrival) {
                    "$userName arrived at a place"
                } else {
                    "$userName left a place"
                },
                data,
            ),
            onlyAlertOnce = data[KEY_REPLACES_SHOWN] == "true",
        )
    }

    /**
     * A place crossing whose DIRECTION the push doesn't say.
     *
     * The stored row the server writes is `notification_type = 'place_alert'`
     * with the event type kept as a HASH, so enter/exit isn't in the push. This
     * device can often read it back (PlaceAlertEvaluator.resolveServerPushCopy,
     * from its own alert's hashes); when it can't, nothing here guesses one: the push
     * says "Place Alert", and "A member · a place" (detailedPlaceCopy's no-direction
     * shape).
     */
    private fun handleDirectionlessPlaceAlert(data: Map<String, String>, dedupeKey: String?) =
        postServerPlaceAlert(data, dedupeKey, known = null)

    /**
     * A server-routed place push: posted NOW, under its event id ([dedupeKey]), so a crossing is
     * never silent; then the wake drives local evaluation, whose named copy of the same crossing
     * replaces it in place without sounding again. A wake that posted nothing would leave a
     * crossing that local evaluation can't reproduce unheard.
     *
     * Named when this device can name it on its own (PlaceAlertEvaluator.resolveServerPushCopy:
     * the place it holds, the member by a name it opened; else "A member"), else "A member arrived
     * at a place", titled by its event type either way. Not posted when it would only repeat what
     * the shade already shows: this push again, or the local copy of the same crossing
     * (PlaceAlertEvaluator.planServerPush), which never claims anything, so nothing here can
     * swallow a crossing this device saw.
     */
    private fun postServerPlaceAlert(
        data: Map<String, String>,
        dedupeKey: String?,
        known: PlaceAlertRules.Transition?,
    ) {
        val placeId = data["place_id"]
        // The two server shapes carry the member under different keys: the in-app
        // notification metadata uses "target_user_id", the direct push payload uses
        // "crossing_user_id".
        val userId = data["target_user_id"] ?: data["crossing_user_id"] ?: data["user_id"]
        backgroundScope.launch {
            val evaluator = placeAlertEvaluator.get()
            val copy = runCatching { evaluator.resolveServerPushCopy(placeId, userId, data["event_type_hash"]) }
                .onFailure { Log.w(TAG, "place push: couldn't name it here", it) }
                .getOrNull()
            // The push's own type when it has one; else what this device read back from its hash.
            val transition = known ?: copy?.transition
            val outcome = evaluator.planServerPush(
                notificationId = dedupeKey,
                placeId = placeId,
                userId = userId,
                transition = transition,
                // When it happened: the reporter's sealed event time where there is one
                // (NotificationTickleFetcher.applyEncryptedOccurredAt), else when the server was told.
                eventAt = ServerTime.epochMs(data["created_at"]),
            )
            if (outcome == PlaceAlertEvaluator.ServerPushOutcome.ALREADY_SHOWN) return@launch
            // Named when this device can name it, else in this app's own unnamed words, the
            // local copy's shape ("A member arrived at a place"): never the server's, whose
            // row says "Member {} a place" and whose direct push "A family member arrived at
            // a saved location".
            val words = (copy ?: PlaceAlertEvaluator.ServerPushCopy(UNNAMED_MEMBER, UNNAMED_PLACE, transition = null))
                .copy(transition = transition)
            showNotification(
                channelId = TrackApplication.ALERTS_CHANNEL_ID,
                // Event-type only, named or not, as the local copy's: the push and the copy that replaces
                // it carry the same title, and a title never contradicts its body. Never the server's row
                // title ("Member arrived at a place" over "Alice arrived at Home").
                title = when (transition) {
                    PlaceAlertRules.Transition.ENTER -> "Arrival"
                    PlaceAlertRules.Transition.EXIT -> "Departure"
                    null -> "Place Alert"
                },
                message = withEventStamp(detailedPlaceCopy(words), data),
                // The bundle its local copy will be in, so replacing it never moves it (GROUP_PLACES_LOCAL).
                group = placesLocalGroup(),
                dedupeKey = dedupeKey,
                namesOnlyMessage = withEventStamp(namesOnlyPlaceCopy(words), data),
            )
            driveLocalEvaluation(placeId, userId, dedupeKey)
        }
    }

    /**
     * Refresh until subscriber-side evaluation reproduces the crossing, or give up.
     *
     * RETRIED, because a single attempt loses a race it cannot win. The mover reports a
     * crossing the instant it detects one (BoundaryMonitorCallbackImpl.onRequestReportCrossing
     * stamps and sends immediately) but that report carries no location; the mover's
     * POSITION travels on the ordinary tracking stream, up to POLL_MOVING_MS (20s) behind
     * while moving. The server alerts off the crossing report, so the push routinely
     * arrives at this device BEFORE the position that proves the crossing — and one
     * [PLACE_WAKE_REFRESH_TIMEOUT_MS] attempt then finds the mover still outside the
     * place and reproduces nothing.
     *
     * The push is already in the shade, so a miss here costs only the name until the
     * next scheduled pass, which is BACKGROUND_EVAL_INTERVAL_MS (5 min) while the tracking
     * service is alive and LocationHeartbeatWorker's 15 min when it is not; that pass then
     * replaces the push in place. So the wake keeps asking across the window the mover's
     * position actually needs, with a widening gap: cheap when the position is already there,
     * still bounded.
     */
    private suspend fun driveLocalEvaluation(placeId: String?, userId: String?, notificationId: String?) {
        val evaluator = placeAlertEvaluator.get()
        for ((attempt, backoffMs) in placeWakeBackoffMs.withIndex()) {
            if (backoffMs > 0) delay(backoffMs)
            val identified = !placeId.isNullOrBlank() && !userId.isNullOrBlank() && !notificationId.isNullOrBlank()
            val before = evaluator.lastLocalSurfacedAt()
            withTimeoutOrNull(PLACE_WAKE_REFRESH_TIMEOUT_MS) {
                for (circleId in evaluator.watchedCircleIds()) {
                    runCatching { locationRepository.refreshLatestLocations(circleId) }
                        .onFailure { Log.w(TAG, "place wake: refresh failed for $circleId", it) }
                }
            }
            // An identified push: its own named copy has replaced it. Else: anything surfaced here.
            val surfaced = if (identified) evaluator.pushPaired(notificationId) else evaluator.lastLocalSurfacedAt() != before
            if (surfaced) {
                Log.d(TAG, "place wake: local evaluation named the crossing on attempt ${attempt + 1}")
                return
            }
        }
        // The push stays as posted; the next scheduled pass names it in place if the mover's
        // position lands later.
        Log.d(TAG, "place wake: local evaluation could not reproduce the crossing yet; the push stands")
    }

    /**
     * Full-detail body for a server push this device could name: exactly the wording
     * Settings → Notification detail advertises for "Names and places", and the same
     * wording PlaceAlertRules.describe uses for a live local crossing, so the two
     * paths do not read as two different features.
     *
     * A direction the hash would not give up drops the verb rather than inventing
     * one, and the title says "Place Alert". The server's own title ("Member arrived at
     * a place") is not shown: every place push is titled by its event type, as its
     * local copy is.
     */
    internal fun detailedPlaceCopy(copy: PlaceAlertEvaluator.ServerPushCopy): String =
        when (copy.transition) {
            PlaceAlertRules.Transition.ENTER -> "${copy.memberName} arrived at ${copy.placeName}"
            PlaceAlertRules.Transition.EXIT -> "${copy.memberName} left ${copy.placeName}"
            null -> "${copy.memberName} · ${copy.placeName}"
        }

    /** The same at "Names only": who, never where. */
    internal fun namesOnlyPlaceCopy(copy: PlaceAlertEvaluator.ServerPushCopy): String =
        when (copy.transition) {
            PlaceAlertRules.Transition.ENTER -> "${copy.memberName} arrived at a place"
            PlaceAlertRules.Transition.EXIT -> "${copy.memberName} left a place"
            null -> copy.memberName
        }

    private fun handlePlaceArrival(data: Map<String, String>, dedupeKey: String?) =
        handlePlaceCrossing(data, dedupeKey, isArrival = true)

    private fun handlePlaceDeparture(data: Map<String, String>, dedupeKey: String?) =
        handlePlaceCrossing(data, dedupeKey, isArrival = false)

    private fun handleCheckIn(data: Map<String, String>, dedupeKey: String?) {
        val userName = data["user_name"]?.takeIf { it.isNotBlank() } ?: "A member"
        val checkInType = data["check_in_type"] ?: "general"
        val message = data["message"]
        // Where they checked in: a place this device holds (NotificationTickleFetcher), shown
        // only at full detail — names-only says who, never where.
        val placeName = data["place_name"]?.takeIf { it.isNotBlank() }

        val title = when (checkInType) {
            "safe_arrival" -> "Safe Arrival"
            "emergency_ok" -> "All Clear"
            else -> "Check-in"
        }

        val text = when {
            message != null -> "$userName: $message"
            placeName != null -> "$userName checked in at $placeName"
            else -> "$userName checked in"
        }

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = title,
            message = text,
            group = messagesGroup(),
            dedupeKey = dedupeKey,
            // Names-only drops the free-text check-in message — it's the
            // closest thing Track has to chat content.
            namesOnlyMessage = "$userName checked in",
        )
    }

    private fun handleCircleInvite(data: Map<String, String>, dedupeKey: String?) {
        val inviterName = data["inviter_name"] ?: "Someone"
        val circleName = data["circle_name"] ?: "a circle"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "circles")
        }

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Circle Invitation",
            message = "$inviterName invited you to join $circleName",
            intent = intent,
            group = messagesGroup(),
            // One banner per circle's invitation, however often it's sent, so the answer can
            // take it down: [cancelInvite].
            dedupeKey = circleIdOf(data)?.let { inviteNotificationKey(it) } ?: dedupeKey,
            namesOnlyMessage = "$inviterName invited you to a circle",
        )
    }

    /**
     * An invitation to [circleId] was answered here, yes or no: its banner leaves the shade
     * (a declined invitation leaves nothing showing).
     */
    fun cancelInvite(circleId: String) = cancelNotification(inviteNotificationKey(circleId))

    private fun inviteNotificationKey(circleId: String): String =
        "invite:${com.pingolin.track.util.AccountIds.storeKey(circleId)}"

    /**
     * "Someone asked to take over your circle."
     *
     * The owner's only warning that a fourteen-day clock has started, and the reason the
     * tap target here matters more than on any other notification in the app: the server's
     * own copy tells them to "open Pingolin to refuse", so landing them on the
     * notifications list — one screen short of the Refuse control — would keep the promise
     * that copy makes only in the most literal sense. It carries `circle_id`, so the tap
     * opens that circle directly.
     *
     * The body is preferred from the server, which already renders the transfer date into
     * it. The fallback exists because the typed WebSocket and UnifiedPush payloads carry
     * no body at all — only the FCM tickle path folds the stored row's body in.
     */
    private fun handleOwnershipClaim(data: Map<String, String>, dedupeKey: String?) {
        val circleId = circleIdOf(data)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (circleId != null) {
                putExtra("navigate_to", "circle_ownership_claim")
                putExtra("circle_id", circleId)
            } else {
                // No id to open: the circles list is still better than nothing, because
                // every circle's own screen carries the claim banner.
                putExtra("navigate_to", "circles")
            }
        }

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Someone asked to take over your circle",
            message = ownershipClaimNamedCopy(data, withCircle = true) ?: ownershipClaimCopy(data),
            intent = intent,
            group = messagesGroup(),
            dedupeKey = dedupeKey,
            // Names-only: who, but not which circle; generic: the server's own words.
            namesOnlyMessage = ownershipClaimNamedCopy(data, withCircle = false),
        )
    }

    /**
     * Who asked, and which circle, when this phone could tell: the claimant by the name it opened
     * for them and the circle by its own name (NotificationNames, the tickle path), with the
     * deadline. Null when nobody was named, and the server's words stand.
     */
    internal fun ownershipClaimNamedCopy(data: Map<String, String>, withCircle: Boolean): String? {
        val who = data["user_name"]?.takeIf { it.isNotBlank() } ?: return null
        val circle = data["circle_name"]?.takeIf { withCircle && it.isNotBlank() } ?: "one of your circles"
        val deadline = OwnershipClaimFormat.parseTimestamp(data["resolves_at"])
            ?.let { " If you do nothing, it transfers to them on ${OwnershipClaimFormat.deadlineText(it)}." }
            .orEmpty()
        // The app by its own name, as the e-mails say it.
        return "$who asked to take over $circle.$deadline Open Pingolin Track to refuse."
    }

    /**
     * The claim notification's body when this phone couldn't name who asked
     * ([ownershipClaimNamedCopy] when it could).
     *
     * NO name is interpolated here: the push payload and the stored row's metadata are both
     * `{claim_id, resolves_at}`. Reading a name key here would be a lookup that can never
     * hit, whose placeholder every recipient reads instead. A name reaches the notification
     * only as this phone opened it (NotificationNames, on the tickle path), through
     * [ownershipClaimNamedCopy].
     *
     * The deadline IS available, so the fallback uses it: `resolves_at` reaches this map
     * on the tickle path, where NotificationTickleFetcher flattens the row's metadata into
     * `data`. Rendered in the DEVICE's zone, unlike the server's own body, which formats
     * the same instant in UTC and so can name a different calendar day.
     */
    internal fun ownershipClaimCopy(data: Map<String, String>): String =
        serverCopy(data, ownershipClaimFallback(data))

    private fun ownershipClaimFallback(data: Map<String, String>): String {
        val deadline = OwnershipClaimFormat.parseTimestamp(data["resolves_at"])
        val opening = "An admin has asked to take over one of your circles."
        return if (deadline != null) {
            "$opening If you do nothing, it transfers to them on " +
                "${OwnershipClaimFormat.deadlineText(deadline)}. Open Pingolin Track to refuse."
        } else {
            "$opening Open Pingolin Track to refuse."
        }
    }

    /**
     * "Your request to take over was refused": the owner said no to this account's claim.
     * Without this handler the type would be dropped as unknown, and the claimant's phone would
     * say nothing. With full detail it names the circle, by the name this phone keeps for it (as
     * for the claim); otherwise the server's words, which name nobody and no circle, with the
     * same words when none came. The tap opens that circle by the claim's own route, where its
     * sheet says where things stand now.
     */
    private fun handleOwnershipRefused(data: Map<String, String>, dedupeKey: String?) {
        val circleId = circleIdOf(data)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (circleId != null) {
                putExtra("navigate_to", "circle_ownership_claim")
                putExtra("circle_id", circleId)
            } else {
                putExtra("navigate_to", "circles")
            }
        }

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Your request to take over was refused",
            message = ownershipRefusedNamedCopy(data) ?: ownershipRefusedCopy(data),
            intent = intent,
            // News, not a deadline: nothing is asked of them.
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = messagesGroup(),
            dedupeKey = dedupeKey,
            // Names only, and generic: the circle is never named.
            namesOnlyMessage = ownershipRefusedCopy(data),
        )
    }

    /** The refusal naming its circle, when this phone could (NotificationNames, the tickle path); else null. */
    internal fun ownershipRefusedNamedCopy(data: Map<String, String>): String? {
        val circle = data["circle_name"]?.takeIf { it.isNotBlank() } ?: return null
        return "The owner refused your request to take over $circle. It stays theirs."
    }

    /** The refusal's body: the server's, else the same words. */
    internal fun ownershipRefusedCopy(data: Map<String, String>): String =
        serverCopy(data, "The owner refused your request to take over the circle. It stays theirs.")

    /**
     * The claim completed and the circle changed hands.
     *
     * Sent to EVERY active member, and the server writes a different body for the former
     * owner ("You did not respond...") than for everyone else — so the stored copy is
     * preferred and never paraphrased here. The fallback has to be true for both audiences,
     * which is why it names neither.
     */
    private fun handleOwnershipTransferred(data: Map<String, String>, dedupeKey: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "circles")
        }

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Circle ownership changed",
            message = ownershipTransferredCopy(data),
            intent = intent,
            group = messagesGroup(),
            dedupeKey = dedupeKey,
        )
    }

    /**
     * A pause of this account's ran out, and sharing started again. Without this handler the type
     * would be dropped as unknown and the person never told. Said in plain words, at the detail
     * level set here: the circle is named at full detail only. The circle list is read again at
     * once too ([refreshPrivacy]), so this phone's sending goes by the resume now.
     */
    private fun handlePrivacyResumed(data: Map<String, String>, dedupeKey: String?) {
        refreshPrivacy()
        val (detailed, namesOnly) = privacyResumedCopy(data)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "circles")
        }
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Location sharing resumed",
            // Stamped with the row's time: fetched late, it must not read as just now.
            message = withEventStamp(detailed, data),
            intent = intent,
            priority = NotificationCompat.PRIORITY_DEFAULT,
            group = statusGroup(),
            dedupeKey = dedupeKey,
            namesOnlyMessage = withEventStamp(namesOnly, data),
        )
    }

    /** The resumed notice at full detail, naming the circle when this device knows its name, and with no names. */
    internal fun privacyResumedCopy(data: Map<String, String>): Pair<String, String> {
        val circle = data["circle_name"]?.takeIf { it.isNotBlank() }
        val anyCircle = "Your pause has ended, and your circle can see your location again."
        return (if (circle != null) "Your pause in $circle has ended, and $circle can see your location again." else anyCircle) to anyCircle
    }

    /** Read the circle list again, quietly ([com.pingolin.track.worker.PrivacyCheckWorker]); tests watch it. */
    internal var refreshPrivacy: () -> Unit = {
        runCatching { com.pingolin.track.worker.PrivacyCheckWorker.runNow(context) }
            .onFailure { Log.w(TAG, "privacy_resumed: refresh not queued: ${it.javaClass.simpleName}") }
    }

    /**
     * The "it changed hands" body.
     *
     * The server writes a DIFFERENT sentence for the former owner ("You did not respond...")
     * than for everyone else, so its stored body is preferred and never paraphrased. The
     * fallback has to be true for both audiences at once, which is why it names neither
     * the new owner nor the former one — and the payload identifies neither anyway.
     */
    internal fun ownershipTransferredCopy(data: Map<String, String>): String =
        serverCopy(data, "A circle you are in has a new owner.")

    /**
     * A new member joining makes any member's app hand them the circle
     * (`needs_delivery`); that work is silent. The only notification is the
     * "Member joined" notice below, posted when the person's stored push
     * preference allows it.
     *
     * The work runs as expedited WorkManager work ([enqueueMemberJoined]), not in this
     * process's own scope: the push that brings it may have woken a process the system
     * freezes again in seconds, and WorkManager keeps it up until the newcomer has the
     * circle.
     */
    private fun handleMemberJoined(data: Map<String, String>, dedupeKey: String?) {
        val circleId = circleIdOf(data)
        val needsDelivery = data["needs_delivery"] == "true"
        val needsKeyRotation = data["needs_key_rotation"] == "true"
        val sendLocation = data["send_location"] == "true"

        if (circleId == null) {
            Log.w(TAG, "member_joined: it names no circle")
            return
        }

        Log.d(TAG, "member_joined: circleId=$circleId, needsKeyRotation=$needsKeyRotation, sendLocation=$sendLocation")
        enqueueMemberJoined(circleId, needsDelivery, needsKeyRotation, sendLocation)

        // Said as well, by name where this device opened one, but only when the person's
        // stored "Member joined" push preference allows it: the fetcher checks it and says so
        // under a key no row can carry (KEY_MEMBER_JOINED_SHOWN). The history line is the part
        // a member may want to act on.
        if (data[KEY_MEMBER_JOINED_SHOWN] == "1") {
            val (detailed, namesOnly) = memberJoinedCopy(data)
            showNotification(
                channelId = TrackApplication.ALERTS_CHANNEL_ID,
                title = "New circle member",
                message = detailed,
                group = messagesGroup(),
                dedupeKey = dedupeKey,
                namesOnlyMessage = namesOnly,
            )
        }
    }

    /**
     * The member_joined notice at full detail and at "names only": who (`user_name`, an opened
     * name of a member this device shows, else "Someone") and, at full detail only, which circle
     * (`circle_name`).
     */
    internal fun memberJoinedCopy(data: Map<String, String>): Pair<String, String> {
        val who = data["user_name"]?.takeIf { it.isNotBlank() } ?: "Someone"
        val circle = data["circle_name"]?.takeIf { it.isNotBlank() }
        val history = "They can see its location history."
        return "$who joined ${circle ?: "a circle you are in"}. $history" to "$who joined a circle you are in. $history"
    }

    /** Where member_joined's work goes ([performMemberJoined]); tests run it in place. */
    internal var enqueueMemberJoined: (String, Boolean, Boolean, Boolean) -> Unit = { circleId, delivery, rotation, send ->
        com.pingolin.track.worker.MemberJoinedWorker.runNow(context, circleId, delivery, rotation, send)
    }

    /**
     * member_joined's work: hand the newcomer the circle (or, when the push asks for a key
     * rotation instead of a delivery, rotate), then send a fresh position so they see this
     * member at once. Run by [com.pingolin.track.worker.MemberJoinedWorker]. Its failures are
     * logged, never thrown: the server's own wake-ups ask again while anyone is still owed
     * the circle.
     */
    suspend fun performMemberJoined(circleId: String, needsDelivery: Boolean, needsKeyRotation: Boolean, sendLocation: Boolean) {
        if (needsDelivery) {
            try {
                val delivered = circleRepository.deliverOwedAccess(circleId)
                Log.d(TAG, "member_joined: delivery for $circleId handed over $delivered")
            } catch (e: Exception) {
                Log.e(TAG, "member_joined: delivery failed for $circleId", e)
            }
        } else if (needsKeyRotation) {
            // The push asks for a key rotation rather than a delivery.
            try {
                circleRepository.autoRotateIfNeeded(circleId)
                Log.d(TAG, "member_joined: auto key rotation completed for $circleId")
            } catch (e: Exception) {
                Log.e(TAG, "member_joined: auto key rotation failed for $circleId", e)
            }
        }

        // Send location immediately so the new member sees our live marker. Not
        // while a device-wide Pause Tracking holds, until PauseTrackingMigration
        // pauses each circle instead: nothing leaves the phone meanwhile.
        if (sendLocation && !sessionManager.isTrackingPaused()) {
            try {
                val location = locationProvider.lastLocation()
                if (location != null) {
                    val targets = listOf(circleId, com.pingolin.track.domain.repository.LocationRepository.SELF_CIRCLE_ID)
                    locationRepository.sendEnvelopeLocation(targets, location, null)
                    Log.d(TAG, "member_joined: sent immediate location for circle $circleId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "member_joined: failed to send immediate location: ${e.message}")
            }
        }
    }

    private fun handleLowBattery(data: Map<String, String>, dedupeKey: String?) {
        val userName = data["user_name"]?.takeIf { it.isNotBlank() } ?: "A member"
        val batteryLevel = data["battery_level"] ?: "low"

        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Low Battery",
            message = "$userName's phone is at $batteryLevel%",
            group = statusGroup(),
            dedupeKey = dedupeKey,
        )
    }

    private fun handleOffline(data: Map<String, String>, dedupeKey: String?) {
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            // As the bell, the list and the history name it: what was seen, not a cause.
            title = "Inactivity",
            message = inactivityMessage(context, data),
            group = statusGroup(),
            dedupeKey = dedupeKey,
        )
    }

    /** "Back online" — a member who had been flagged inactive is sharing again.
     *  The inverse of [handleOffline]; fired locally by the inactivity evaluator
     *  and by the server's `inactivity_recovered` push. */
    private fun handleBackOnline(data: Map<String, String>, dedupeKey: String?) {
        val userName = data["user_name"]?.takeIf { it.isNotBlank() } ?: "A member"
        showNotification(
            channelId = TrackApplication.ALERTS_CHANNEL_ID,
            title = "Back Online",
            message = "$userName is sharing location again",
            group = statusGroup(),
            dedupeKey = dedupeKey,
        )
    }

    private data class NotificationGroup(
        val key: String,
        val summaryId: Int,
        val summaryTitle: String
    )

    /**
     * Show a notification, optionally bundled into a group.
     *
     * @param dedupeKey Stable string (typically the server alert_id) used as
     *   the Android notification ID. When two posts share the same key the
     *   second replaces the first instead of stacking a new one. Falls back
     *   to a per-call timestamp ID when null (local-event callers that
     *   don't have a stable server id).
     * @param namesOnlyMessage Variant of [message] for the "names only"
     *   detail level: keeps WHO but drops places/locations/message content.
     *   Callers whose message contains no place or content can omit it —
     *   the detailed message is reused.
     * @param silent Shown with no sound or vibration, whatever its channel's
     *   (NotificationCompat's setSilent).
     */
    private fun showNotification(
        channelId: String,
        title: String,
        message: String,
        intent: Intent? = null,
        priority: Int = NotificationCompat.PRIORITY_HIGH,
        group: NotificationGroup? = null,
        dedupeKey: String? = null,
        namesOnlyMessage: String? = null,
        silent: Boolean = false,
        /** An update of a notification the user already heard: replaced, not sounded again. */
        onlyAlertOnce: Boolean = false,
    ) {
        // Display-verbosity is a per-device, render-time choice (mirrors
        // Signal's Settings → Notifications → Show). Titles stay as passed:
        // they only name the event type ("Arrived", "SOS Alert!"), which
        // must survive even the generic level — redacting "SOS Alert!" to
        // "Notification" would bury exactly the alert that must never be
        // ignored. The in-app bell list is untouched; it sits behind the
        // app lock and always shows full detail.
        val displayMessage = resolveDisplayMessage(message, namesOnlyMessage ?: message)
        val pendingIntent = if (intent != null) {
            PendingIntent.getActivity(
                context, System.currentTimeMillis().toInt(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            val defaultIntent = Intent(context, MainActivity::class.java)
            PendingIntent.getActivity(
                context, 0, defaultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(displayMessage)
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSilent(silent)
            .setOnlyAlertOnce(onlyAlertOnce)

        if (group != null) {
            notificationBuilder.setGroup(group.key)
        }

        val notification = notificationBuilder.build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Stable id = hash of dedupeKey when we have one (positive,
        // and clear of the fixed summary IDs in SUMMARY_ID_*,
        // 10000-10003: see stableNotificationId); otherwise a
        // timestamp-based id.
        val notificationId = dedupeKey?.let { stableNotificationId(it) }
            ?: System.currentTimeMillis().toInt()

        notificationManager.notify(notificationId, notification)

        if (group != null) {
            showGroupSummary(notificationManager, channelId, group, priority)
        }
        pruneEmptyGroupSummaries(notificationManager)
    }

    /**
     * Cancel any group summary this app has left behind with no children.
     *
     * A notification that MOVES between groups leaves its first group's summary with nothing
     * under it, and a childless summary still renders: a stray "Location Updates / Tap to
     * view" beside the real "<member> arrived at <place>".
     *
     * Don't move one. Cancelling a summary cancels its group's children, and the active list an
     * app reads already shows a post still on its way (NotificationManagerService's
     * getAppActiveNotifications: "pending update overwrites existing post"): a summary pruned
     * while a child's move is pending takes the child with it, and the move then lands as a NEW
     * post, which sounds again (API 34: notification_canceled reason 12, then
     * notification_alert). So a place push and the copy that replaces it share a group
     * (GROUP_PLACES_LOCAL), and nothing moves.
     *
     * Written against the SUMMARY flag rather than the SUMMARY_ID_* list so a
     * future group gets this for free, and tolerant of activeNotifications
     * throwing (it can, if the listener isn't bound yet) because a leftover
     * summary must never break posting the notification the user actually wants.
     */
    private fun pruneEmptyGroupSummaries(notificationManager: NotificationManager) {
        val active = try {
            notificationManager.activeNotifications
        } catch (e: Exception) {
            return
        }
        val posted = active.map {
            PostedNotification(
                id = it.id,
                group = it.notification.group,
                isSummary = it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )
        }
        emptySummaryIds(posted).forEach { notificationManager.cancel(it) }
    }

    /**
     * Pick the notification body for the user's display-detail level
     * (Settings → Push Notifications → Notification detail):
     * - full (default): the detailed copy ("Alice arrived at Home")
     * - names: the names-only variant ("Alice arrived at their destination")
     * - generic: fixed copy with no names, places, or content
     *
     * Unknown/unset levels resolve to full detail (SessionManager
     * normalises, but stay safe against mocked/legacy values here too).
     */
    internal fun resolveDisplayMessage(detailed: String, namesOnly: String): String =
        when (sessionManager.getNotificationDetailLevel()) {
            SessionManager.NOTIF_DETAIL_NAMES -> namesOnly
            SessionManager.NOTIF_DETAIL_GENERIC -> GENERIC_MESSAGE
            else -> detailed
        }

    /**
     * Derive a stable, positive notification ID from a dedupe key
     * (typically the server-side notification row id). Two posts with
     * the same key collide on the same id so Android replaces the
     * existing notification rather than stacking a duplicate.
     */
    private fun stableNotificationId(key: String): Int {
        // Mask to positive Int; floor at 0x10000 to keep clear of the
        // SUMMARY_ID_* range (10000-10003) reserved for group summaries.
        val positive = key.hashCode() and 0x7fffffff
        return if (positive < 0x10000) positive + 0x10000 else positive
    }

    /**
     * Stable per-(rule, member) dedupe key for the client-side inactivity notification, so a
     * re-post replaces the existing one instead of stacking, and so it can be cancelled on
     * auto-resolve. Pass this as "alert_id" in the [handleAlert] data map.
     */
    fun inactivityDedupeKey(ruleId: String, userId: String): String = "inact:$ruleId:$userId"

    /**
     * The same, for the client-side low-battery notification. Distinct prefix so a
     * member who is both inactive and flat gets one notification for each rather
     * than one silently replacing the other.
     */
    fun lowBatteryDedupeKey(ruleId: String, userId: String): String = "batt:$ruleId:$userId"

    /**
     * Cancel the live low-battery notification for a (rule, member). Called when
     * the watched phone charges back above the threshold, so a stale "phone is at
     * 8%" doesn't sit in the shade next to a phone that is now at 90%.
     */
    fun cancelLowBattery(ruleId: String, userId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(stableNotificationId(lowBatteryDedupeKey(ruleId, userId)))
    }

    /**
     * Cancel the live inactivity notification for a (rule, member). Called on auto-resolve
     * (the member shared location again) so a stale "inactive" notification doesn't linger.
     */
    fun cancelInactivity(ruleId: String, userId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(stableNotificationId(inactivityDedupeKey(ruleId, userId)))
    }

    /**
     * The bundle header Android shows once several notifications share a group.
     */
    private fun showGroupSummary(
        notificationManager: NotificationManager,
        channelId: String,
        group: NotificationGroup,
        priority: Int
    ) {
        val summaryIntent = Intent(context, MainActivity::class.java)
        val summaryPendingIntent = PendingIntent.getActivity(
            context, group.summaryId, summaryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summaryNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(group.summaryTitle)
            .setContentText("Tap to view")
            .setPriority(priority)
            .setAutoCancel(true)
            .setContentIntent(summaryPendingIntent)
            .setGroup(group.key)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .build()

        notificationManager.notify(group.summaryId, summaryNotification)
    }

    // Convenience methods for creating notification groups
    private fun sosGroup() = NotificationGroup(GROUP_SOS, SUMMARY_ID_SOS, "Circle notifications")
    private fun placesLocalGroup() =
        NotificationGroup(GROUP_PLACES_LOCAL, SUMMARY_ID_PLACES_LOCAL, "Place Alerts")
    private fun statusGroup() = NotificationGroup(GROUP_STATUS, SUMMARY_ID_STATUS, "Status Updates")
    private fun messagesGroup() = NotificationGroup(GROUP_MESSAGES, SUMMARY_ID_MESSAGES, "Messages")
}

/**
 * An inactivity alert's words: this phone's evaluator's own, with when the member last shared
 * ([AlertHandler.KEY_INACTIVE_SINCE]), else "{name} hasn't shared their location for a
 * while.". Never a server row's: the tickle fetch hands every row's body in as "body"
 * (NotificationTickleFetcher.alertDataFor), and an inactivity alert says only what this app
 * itself knows. A name not readable yet is "A member".
 */
internal fun inactivityMessage(context: Context, data: Map<String, String>): String =
    data[AlertHandler.KEY_INACTIVE_SINCE]?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.inactivity_no_time, data["user_name"]?.takeIf { it.isNotBlank() } ?: "A member")
