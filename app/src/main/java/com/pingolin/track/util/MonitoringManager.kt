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

import android.content.Context
import android.util.Log
import com.pingolin.track.R
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.LocalBatteryEventDao
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.dao.MemberNotSharingSeenDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.local.entity.LocalBatteryEventEntity
import com.pingolin.track.data.local.entity.LocalInactivityEventEntity
import com.pingolin.track.data.local.entity.MemberNotSharingSeenEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.data.remote.dto.CreateAlertEventRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.WakeWatchersRequest
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.ui.circles.MemberSharing
import com.pingolin.track.ui.circles.SharingStatus
import com.pingolin.track.ui.sos.SosPositionText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import uniffi.pingolin_mobile.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages client-side battery and inactivity monitoring, and the check-in rules (an inactivity
 * alert that watches check-ins: [evaluateCheckInRules]).
 *
 * Both are client-side because they have to be: a battery level and a
 * last-seen time both live inside the encrypted location payload, and the
 * server holds no key for either. It is told only "wake the watchers in this
 * circle", and never what was decided or about whom.
 *
 * Battery has no SDK monitor (see updateBatteryLevel).
 * @see InactivityMonitor for offline user detection
 */
@Singleton
class MonitoringManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val tracksApi: TrackApi,
    private val circleDao: CircleDao,
    private val circleMemberDao: CircleMemberDao,
    private val sessionManager: SessionManager,
    private val alertHandler: AlertHandler,
    private val inactivityAlertDao: com.pingolin.track.data.local.dao.InactivityAlertDao,
    private val locationDao: com.pingolin.track.data.local.dao.LocationDao,
    private val notificationDao: NotificationDao,
    private val localInactivityEventDao: LocalInactivityEventDao,
    // When this phone last saw each watched member not sharing: see [silenceStart].
    private val memberNotSharingSeenDao: MemberNotSharingSeenDao,
    private val alertRuleDao: com.pingolin.track.data.local.dao.AlertRuleDao,
    private val localBatteryEventDao: LocalBatteryEventDao,
    private val privacySettingDao: com.pingolin.track.data.local.dao.PrivacySettingDao,
    private val cryptoManager: uniffi.pingolin_mobile.CryptoManager,
    // The check-ins the check-in rules are judged by ([evaluateCheckInRules]).
    private val checkInRepository: dagger.Lazy<CheckInRepository>,
) {
    companion object {
        private const val TAG = "MonitoringManager"

        /** How long the background pass reuses the circle-self-alerts list. */
        const val SELF_ALERTS_CACHE_MS = 30L * 60L * 1000L

        /**
         * While a missed check-in is open, how often the background pass reads the circle's
         * check-ins for the one that ends it. The server's wake-up on that check-in is the timely
         * path; this is what catches it when the wake-up didn't come.
         */
        const val CHECK_IN_LAPSE_REFRESH_MS = 30L * 60L * 1000L

        /**
         * What one pass decides about one member of a check-in rule ([checkInVerdict]): whether to
         * end their open lapse, whether to raise the alert, and when their silence counts from.
         */
        internal data class CheckInVerdict(val resolve: Boolean, val fire: Boolean, val since: Long)

        /**
         * A check-in rule's verdict on one member at [nowMs]: [last] is their newest check-in this
         * phone could read in the circle (null: none), [armedAt] the rule's start, [openSince] the
         * start of their open lapse, the silence it fired on (null: none open), [timeoutMs] the
         * rule's limit.
         *
         * Their silence counts from their last check-in, or the rule's start when that is later
         * (or they have none): a check-in from before the rule says nothing about now. A silence
         * that counts from after the open lapse's start ends the lapse: a check-in since, or the
         * rule turned off and back on, its clock started again. With no lapse open, or one this
         * verdict ends, a silence longer than the limit raises the alert: a member who checked in
         * and went quiet again is reported again.
         */
        internal fun checkInVerdict(last: Long?, armedAt: Long, openSince: Long?, timeoutMs: Long, nowMs: Long): CheckInVerdict {
            val since = if (last == null) armedAt else maxOf(last, armedAt)
            val resolve = openSince != null && since > openSince
            val stillOpen = openSince != null && !resolve
            return CheckInVerdict(resolve = resolve, fire = !stillOpen && nowMs - since > timeoutMs, since = since)
        }

        /**
         * A one-time rule's verdict ("check in within N hours"): [checkInVerdict], except that the
         * rule has ended once its member has checked in after its start ([last] > [armedAt]), so it
         * never goes off again. The server turns it off then and wakes the phones that hold it;
         * this is what holds when that wake-up is late or lost. Its open lapse, which counted from
         * the start, ends by [checkInVerdict]'s own rule.
         */
        internal fun onceVerdict(last: Long?, armedAt: Long, openSince: Long?, timeoutMs: Long, nowMs: Long): CheckInVerdict {
            val verdict = checkInVerdict(last, armedAt, openSince, timeoutMs, nowMs)
            val ended = last != null && last > armedAt
            return if (ended) verdict.copy(fire = false) else verdict
        }

        /**
         * A scheduled rule's verdict on one member ("check in by 9:00 PM on these days"): [latest]
         * is the rule's latest occurrence at or before now (CheckInSchedule.latest; null: none),
         * [last] the member's newest check-in here, [armedAt] the rule's start, [openSince] the
         * start of their open lapse (null: none open).
         *
         * An open lapse ends when they've checked in since it started, or when a newer occurrence
         * took over (its deadline is after the lapse's start). With no lapse open, or one this ends,
         * the latest occurrence goes off when the rule was started before its deadline and they
         * haven't checked in since its window opened: once per occurrence, its deadline the lapse's
         * start ([CheckInVerdict.since]).
         */
        internal fun scheduledVerdict(last: Long?, armedAt: Long, openSince: Long?, latest: CheckInSchedule.Occurrence?): CheckInVerdict {
            val resolve = openSince != null &&
                ((last != null && last > openSince) || (latest != null && latest.due > openSince))
            val stillOpen = openSince != null && !resolve
            val missed = latest != null && armedAt < latest.due && (last == null || last < latest.windowStart)
            return CheckInVerdict(resolve = resolve, fire = !stillOpen && missed, since = latest?.due ?: armedAt)
        }

        /** The circle-mates' "watch me" requests judged by location updates ([evaluateCircleSelfAlerts]). */
        fun locationSelfAlerts(alerts: List<CircleSelfAlertDto>): List<CircleSelfAlertDto> = alerts.filter { it.watchesLocation }

        /** Pure freshness rule for [fetchCircleSelfAlertsCached]. */
        internal fun selfAlertsCacheFresh(cachedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
            cachedAtMs > 0L && nowMs >= cachedAtMs && nowMs - cachedAtMs < maxAgeMs

        /**
         * The highest battery threshold a rule can be set to, across clients:
         * the web app's slider stops at 50 and this app's seekbar at 5 + 45.
         * Above it no rule can be firing, which is what makes it both the level
         * to start reporting from and the level to report a recovery at.
         */
        private const val MAX_WATCHABLE_THRESHOLD = 50

        /**
         * Report each further fall of this many points. Matches the step every
         * client's threshold picker moves in, so no rule sits more than one step
         * below the last reported level.
         */
        private const val BATTERY_REPORT_STEP = 5

        /**
         * How long a note of a member not sharing is kept: longer than any timeout (the server
         * allows 24 hours), so an older stretch decides nothing.
         */
        const val NOT_SHARING_KEEP_MS = 8L * 24L * 3_600_000L

        /**
         * The note's next value for a member the roster shows [status] at [nowMs], or null to leave
         * it. While they aren't sharing: now, or a timed pause's planned end when that is later, and
         * never earlier than the note was. Once they're sharing again, a note still ahead of now comes
         * back to now: their stretch ended early, by now at the latest, and a pause planned for the
         * night but ended after a minute mustn't hold their silence back all night.
         */
        internal fun notSharingNote(previous: Long?, status: SharingStatus, nowMs: Long): Long? {
            if (status == SharingStatus.On) return nowMs.takeIf { previous != null && previous > nowMs }
            val until = (status as? SharingStatus.Paused)?.untilMs?.takeIf { it > nowMs } ?: nowMs
            return until.takeIf { previous == null || previous < it }
        }

        /**
         * When a member's silence counts from, as on the server: the later of their last-seen
         * and the end of their latest stretch of not sharing as this phone saw it, and never
         * after now.
         */
        internal fun activeSince(lastSeenMs: Long, notSharingSeenMs: Long?, nowMs: Long): Long =
            minOf(maxOf(lastSeenMs, notSharingSeenMs ?: lastSeenMs), nowMs)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Native SDK monitors. Battery is NOT among them: see updateBatteryLevel
    // for why its thresholds cannot drive this.
    //
    // Built on first use, not at injection. Constructing it crosses into the
    // native SDK over JNA, which a JVM unit test cannot load, and doing that
    // eagerly in the constructor would make this whole class unconstructable
    // off-device, including for the three alert evaluators, which never touch
    // it, and the `refreshedUserIds` guards MonitoringManagerTest covers.
    // `by lazy` is synchronized, and `initialize()` still runs before anything
    // that reads this, so the on-device order is unaffected.
    private val inactivityMonitor: InactivityMonitor by lazy { InactivityMonitor() }

    /**
     * Last own-battery level we asked the server to wake watchers at; null when
     * we are above every watchable threshold. In memory only: a process
     * restart costs at most one extra wake, whereas persisting it would risk
     * the opposite failure: a phone that never reports because it remembers
     * having already done so.
     */
    private var lastReportedBatteryLevel: Int? = null

    // Track initialization state
    private var isInitialized = false

    /** Must run before any other method here; the rest are no-ops until it has. */
    suspend fun initialize() {
        if (isInitialized) return

        try {
            // Set up inactivity alert callback
            // A callback the SDK can't take a throw from: every body is caught (sdkCallback).
            inactivityMonitor.setCallback(object : InactivityAlertCallback {
                override fun onUserInactive(alert: InactivityAlert) = sdkCallback(TAG, "onUserInactive") {
                    scope.launch {
                        handleInactivityAlert(alert)
                    }
                }

                override fun onUserActive(userId: String, displayName: String) = sdkCallback(TAG, "onUserActive") {
                    Log.d(TAG, "User $displayName ($userId) is active again")
                }
            })

            isInitialized = true
            Log.i(TAG, "Monitoring manager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize monitoring manager", e)
        }
    }

    /**
     * Feed in a battery reading and wake the watchers if it has fallen far
     * enough to be worth someone looking. Called from LocationTrackingService,
     * which already reads the level to seal into the location payload, so this
     * runs on background cycles rather than only while the app is open.
     *
     * The SDK's BatteryMonitor does not decide this, for two reasons. Its
     * thresholds are 10% and 5%, so a rule set at 30% (every client's picker
     * offers 5 to 50) would wait for the watcher's next periodic pass instead
     * of being woken. And it drops every alert while its circle set is empty
     * ("No circles configured for battery alerts"); this app never adds
     * circles to it.
     *
     * The ladder used instead is a compromise the privacy shape forces.
     * This device cannot know anyone's threshold (that is exactly the
     * disclosure the design avoids), so it cannot wake precisely when a rule
     * would fire. It reports each further 5-point fall below the highest
     * threshold anyone can set, so a watcher is woken within one step of their
     * own line, and their periodic pass makes a missed step late rather than
     * lost. Matches the iOS app's MonitoringManager.noteOwnBattery.
     *
     * @param level percentage, 0-100
     */
    suspend fun updateBatteryLevel(level: Int, isCharging: Boolean) {
        // Back above every threshold anybody could have set: nobody's rule can
        // still be firing, so wake them once to clear it and re-arm. Charging
        // alone is not enough: a phone charging at 4% is still flat.
        if (level > MAX_WATCHABLE_THRESHOLD) {
            if (lastReportedBatteryLevel != null) {
                lastReportedBatteryLevel = null
                wakeBatteryWatchers()
            }
            return
        }

        // Charging and climbing is somebody else's problem; don't spend a wake
        // on a phone that is visibly recovering.
        if (isCharging) return

        val last = lastReportedBatteryLevel
        if (last != null && level > last - BATTERY_REPORT_STEP) return
        lastReportedBatteryLevel = level
        wakeBatteryWatchers()
    }

    /**
     * Ask the server to wake the watchers, once per circle we actually share
     * battery with.
     *
     * Circles are read here rather than held in a set somewhere: the SDK
     * monitor holds one, which this app never fills, so it never fires. Circles
     * with share_battery off are skipped: the level was stripped from the
     * payload for them (LocationRepository does that on the way out), so nobody
     * there can evaluate anything and the wake would be pure cost to their
     * battery. Absence of a row means the default, TRUE, as on the server.
     *
     * One call per circle rather than one carrying a list: a list of circle ids
     * in a single request is a statement that one person is in all of them.
     */
    private suspend fun wakeBatteryWatchers() {
        val circles = try {
            circleDao.getAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read circles for battery wake", e); return
        }

        for (circle in circles) {
            val sharesBattery = try {
                privacySettingDao.get(circle.id)?.shareBattery ?: true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read battery sharing posture", e); true
            }
            if (!sharesBattery) continue

            try {
                val response = tracksApi.wakeWatchers(WakeWatchersRequest(circleId = circle.id))
                if (!response.isSuccessful) {
                    // 429 is an ordinary answer here, not a failure to chase: the
                    // watchers were woken recently enough that another wake would
                    // tell them nothing new.
                    Log.w(TAG, "wake-watchers returned ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "wake-watchers failed", e)
            }
        }
    }

    /**
     * Start watching a circle member for inactivity.
     *
     * @param timeoutSeconds null uses the default timeout
     */
    suspend fun startMonitoringUser(
        userId: String,
        circleId: String,
        displayName: String,
        timeoutSeconds: Long? = null
    ) {
        try {
            if (timeoutSeconds != null) {
                inactivityMonitor.startMonitoringWithTimeout(
                    userId, circleId, displayName, timeoutSeconds.toULong()
                )
            } else {
                inactivityMonitor.startMonitoring(userId, circleId, displayName)
            }
            Log.d(TAG, "Started monitoring user $displayName ($userId) in circle $circleId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring user $userId", e)
        }
    }

    suspend fun stopMonitoringUser(userId: String) {
        try {
            inactivityMonitor.stopMonitoring(userId)
            Log.d(TAG, "Stopped monitoring user $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop monitoring user $userId", e)
        }
    }

    /**
     * Refresh a user's "last seen" time: call whenever their location arrives, or they
     * will eventually be reported inactive despite actively sharing.
     */
    suspend fun recordUserActivity(userId: String) {
        try {
            val timestamp = System.currentTimeMillis()
            inactivityMonitor.recordActivity(userId, timestamp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record activity for user $userId", e)
        }
    }

    /**
     * Nothing sweeps for inactivity on its own: a caller must drive this periodically,
     * or a member can go silent indefinitely without anyone being told.
     */
    suspend fun checkInactiveUsers(): List<InactivityAlert> {
        return try {
            inactivityMonitor.checkInactiveUsers()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check inactive users", e)
            emptyList()
        }
    }

    suspend fun getAllUserStatus(): List<UserActivityStatus> {
        return try {
            inactivityMonitor.getAllStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user status", e)
            emptyList()
        }
    }

    /**
     * Start monitoring all members in a circle.
     */
    suspend fun startMonitoringCircle(circleId: String) {
        try {
            val members = circleMemberDao.getByCircle(circleId)
            val currentUserId = sessionManager.getUserId()

            for (member in members) {
                // Don't monitor ourselves
                if (member.userId == currentUserId) continue

                startMonitoringUser(
                    userId = member.userId,
                    circleId = circleId,
                    displayName = member.displayName ?: member.userId
                )
            }
            Log.i(TAG, "Started monitoring ${members.size - 1} members in circle $circleId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start monitoring circle $circleId", e)
        }
    }

    /**
     * Stop monitoring all users and clear state.
     * Should be called on logout.
     */
    suspend fun clear() {
        // The check-in rules' memory of reads and starts is this account's, whatever the SDK
        // monitor below does.
        checkInsReadAt.clear()
        selfAlertsFirstSeen.clear()
        try {
            inactivityMonitor.clear()
            // Otherwise the next account inherits account A's "already reported
            // at this level" marker and stays silent through its own first drain.
            lastReportedBatteryLevel = null
            Log.i(TAG, "Monitoring state cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear monitoring state", e)
        }
    }

    /**
     * Report a firing to the server, sealed, so the account's other devices and
     * the web share one history.
     *
     * Best-effort and deliberately silent on failure: the local record and the
     * notification have already happened, and this is the copy for elsewhere.
     * Failing the evaluation because a history write did not land would trade
     * something that works for something that is convenient.
     *
     * Sealed with the SELF key, not the circle key, for two reasons that point
     * the same way. The row is only ever handed back to the account that wrote
     * it (the list retrieves on user_id), so a per-user key is tighter than a
     * per-circle one at no cost. And it is what the reader expects: the web app
     * decrypts these with SELF_CIRCLE_ID, and the web is the client that cannot
     * produce them and most needs to read them: sealing to the circle key
     * would fill its history with rows it could not open.
     */
    private suspend fun reportAlertEvent(
        ruleId: String,
        targetUserId: String,
        alertType: String,
        triggeredAt: Long
    ) {
        try {
            val selfId = sessionManager.getUserId() ?: return
            val keyId = com.pingolin.track.domain.repository.LocationRepository.SELF_CIRCLE_UUID
            fun seal(value: String) = cryptoManager.encryptData(keyId, value).let {
                EncryptedPayload(nonce = it.nonce, ciphertext = it.ciphertext)
            }
            val response = tracksApi.createAlertEvent(
                CreateAlertEventRequest(
                    alertRuleId = ruleId,
                    dayBucket = (triggeredAt / 86_400_000L).toInt(),
                    encryptedUserId = seal(selfId),
                    encryptedTargetUserId = seal(targetUserId),
                    encryptedAlertType = seal(alertType),
                    // ISO-8601, not epoch millis. The exact
                    // moment, readable only by the account
                    // that wrote it; the server's own
                    // triggered_at is arrival time and
                    // differs whenever this reports late.
                    // The web app reads it with new Date(),
                    // which parses a digit string such as
                    // "1755561234567" as a date string
                    // ("Invalid Date").
                    encryptedTriggeredAt = seal(
                        java.time.Instant.ofEpochMilli(triggeredAt).toString()
                    )
                )
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "alert-event report returned ${response.code()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "alert-event report failed", e)
        }
    }

    /** Circle IDs with at least one active low-battery rule (so the caller knows what to refresh). */
    suspend fun batteryAlertCircleIds(): Set<String> =
        try {
            alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY)
                .mapNotNull { it.circleId }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read battery-alert circles", e); emptySet()
        }

    /**
     * Client-side low-battery evaluation: the other half of the wake-up above,
     * and the only half that decides anything.
     *
     * The battery level is inside the location payload, sealed under the circle
     * key, so this comparison cannot happen anywhere but on a device that holds
     * that key. For each of our own active rules whose circle is in
     * [refreshedByCircle] (the caller just refreshed it AND it decrypted
     * cleanly), take the watched member's most recent reported level and compare
     * it to OUR threshold.
     *
     * Fires once per drain and re-arms on recovery, using the same shape as
     * `evaluateInactivity`: an unresolved local event is the "already fired"
     * marker, persisted so a process restart cannot re-fire it. A member whose
     * phone is charging back up crosses the threshold from below, which resolves
     * the event and arms the next one.
     *
     * Circles that failed to refresh or decrypt are skipped by the caller, so a
     * connectivity gap can never read as "everybody's battery is fine" either.
     */
    suspend fun evaluateLowBattery(refreshedByCircle: Map<String, Set<String>>) {
        if (refreshedByCircle.isEmpty()) return
        try {
            val selfId = sessionManager.getUserId()
            val now = System.currentTimeMillis()
            for (rule in alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY)) {
                val circleId = rule.circleId ?: continue
                val refreshed = refreshedByCircle[circleId] ?: continue
                // A rule about ourselves would fire on our own phone about our own
                // phone, which the platform already does better.
                if (rule.targetUserId.isBlank() || rule.targetUserId == selfId) continue
                val threshold = rule.batteryThreshold ?: continue

                // Only members THIS refresh produced a location for. A cached
                // batteryLevel is exactly as stale as a cached timestamp, so
                // without this an old low reading re-raises an alert about a
                // phone that may have been charged hours ago.
                if (rule.targetUserId !in refreshed) continue
                val latest = locationDao.observeLatestByCircle(circleId).first()
                    .firstOrNull { it.userId == rule.targetUserId }
                // No location, or one carrying no level (the member does not share
                // battery in this circle, so the client stripped it): nothing to
                // judge, and silence is the right answer rather than an alarm.
                val level = latest?.batteryLevel ?: continue
                // CONSENT (allow_alerts_on_me). The target's latest fix carries
                // their posture for this circle; an explicit FALSE means they have
                // withdrawn consent to being alerted about, so say nothing. null
                // (backend did not report it) permits, as at every consent gate:
                // most members have no privacy row at all.
                if (latest.allowAlertsOnMe == false) continue

                val member = circleMemberDao.getByCircle(circleId)
                    .firstOrNull { it.userId == rule.targetUserId } ?: continue

                val openEvent = localBatteryEventDao.getOpen(rule.id, rule.targetUserId)
                if (level <= threshold) {
                    if (openEvent == null) notifyLowBattery(rule, member, level, now)
                } else if (openEvent != null) {
                    // Charged back above the line: resolve, clear the stale
                    // notification, and re-arm for the next drain.
                    localBatteryEventDao.resolve(openEvent.id, now)
                    alertHandler.cancelLowBattery(rule.id, rule.targetUserId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Low-battery evaluation failed", e)
        }
    }

    /**
     * Post the low-battery notification, persist a device-local record, and
     * report the firing to the server.
     *
     * The local record is what backs the in-app bell HERE: the bell is otherwise
     * server-fetched, and this alert is decided on-device, so without it the
     * firing would be missing from the bell even though the shade showed it.
     *
     * The firing is also reported: `reportAlertEvent` below seals the who /
     * what / when under the SELF key and posts it, which is how the account's
     * other devices learn about a firing none of them could evaluate. The web
     * app's Alert Events list decrypts exactly those fields with the same key.
     * The server only ever holds the opaque row.
     */
    private suspend fun notifyLowBattery(
        rule: AlertRuleEntity,
        member: CircleMemberEntity,
        level: Int,
        now: Long
    ) {
        alertHandler.handleAlert(
            AlertHandler.TYPE_LOW_BATTERY,
            mapOf(
                "user_name" to member.displayName,
                "battery_level" to level.toString(),
                // Stable per-(rule, member) id so a re-post replaces rather than
                // stacks, and so the recovery path can cancel this exact one.
                "alert_id" to alertHandler.lowBatteryDedupeKey(rule.id, member.userId)
            )
        )

        val dayBucket = (now / 86_400_000L).toInt()
        val recordId = "local-batt:${rule.id}:${member.userId}:$now"
        try {
            // Event first: its presence (unresolved) is the fire-once marker.
            localBatteryEventDao.insert(
                LocalBatteryEventEntity(
                    id = recordId,
                    alertRuleId = rule.id,
                    circleId = rule.circleId ?: "",
                    targetUserId = member.userId,
                    targetDisplayName = member.displayName,
                    batteryLevel = level,
                    triggeredAt = now,
                    triggeredDayBucket = dayBucket
                )
            )
            reportAlertEvent(
                ruleId = rule.id,
                targetUserId = member.userId,
                alertType = AlertHandler.TYPE_LOW_BATTERY,
                triggeredAt = now
            )
            notificationDao.insert(
                NotificationEntity(
                    id = recordId,
                    circleId = rule.circleId,
                    notificationType = AlertHandler.TYPE_LOW_BATTERY,
                    title = "Low Battery",
                    body = "${member.displayName}'s phone is at $level%",
                    metadata = null,
                    isRead = false,
                    createdDayBucket = dayBucket,
                    createdAt = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist local battery record", e)
        }
    }

    /**
     * Handle an inactivity alert by showing a local notification.
     * Note: Inactivity alerts are client-side only for E2EE compliance.
     * We don't notify the server about who we're monitoring or their status.
     */
    private suspend fun handleInactivityAlert(alert: InactivityAlert) {
        // E2EE: do NOT log the decrypted display name: Log.i survives release
        // (proguard only strips Log.d/Log.v). Pseudonymous userId + duration only.
        Log.i(TAG, "Inactivity alert: user ${alert.userId} inactive for ${alert.inactiveDurationSeconds}s")

        // Show local notification only: don't send to server for privacy
        val lastFixAt = System.currentTimeMillis() - alert.inactiveDurationSeconds.toLong() * 1000L

        alertHandler.handleAlert(
            AlertHandler.TYPE_OFFLINE,
            mapOf(
                "user_name" to alert.displayName,
                AlertHandler.KEY_INACTIVE_SINCE to inactiveSince(context, alert.displayName, lastFixAt)
            )
        )
    }

    /**
     * Circle IDs that have at least one active location inactivity rule (so the caller knows what
     * to refresh). A check-in rule reads the circle's check-ins, not its locations
     * ([evaluateCheckInRules]).
     */
    suspend fun inactivityCircleIds(): Set<String> =
        try {
            inactivityAlertDao.getActive().filter { InactivityWatches.isLocation(it.watches) }.map { it.circleId }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read inactivity circles", e); emptySet()
        }

    /**
     * Client-side inactivity evaluation (E2EE: the server can't do this). For each active rule
     * whose circle is in [refreshedByCircle] (the caller just refreshed it AND it decrypted
     * cleanly), judge each watched member on their OWN last-ping timestamp, so our receive
     * latency doesn't matter; circles we couldn't read are skipped, so a connection/decrypt gap
     * never reads as "everyone went silent". Self-alerts are not client-watchable and are skipped.
     * A member paused, or with real-time sharing off, in the circle isn't judged, and after such a
     * stretch their silence counts from its end as this phone saw it.
     *
     * Fires ONCE per lapse and auto-resolves. An unresolved local event is the "already fired"
     * marker: persisted, so it survives process restarts (an in-memory flag would re-fire on
     * restart). When the member shares location again the lapse is resolved, the stale
     * notification cancelled, and the next lapse re-arms.
     */
    suspend fun evaluateInactivity(refreshedByCircle: Map<String, Set<String>>) {
        if (refreshedByCircle.isEmpty()) return
        try {
            val selfId = sessionManager.getUserId()
            val now = System.currentTimeMillis()
            pruneNotSharingNotes(now)
            for (rule in inactivityAlertDao.getActive()) {
                // EVERY watch-someone-else rule is judged here, not just the
                // ones marked client_only. The server only notices that
                // somebody in the circle has gone quiet and sends a
                // contentless wake-up, so it never reads the plaintext
                // timeout or resolves the watched member's pseudonym to a
                // user id; the threshold and the identity are applied here,
                // where the plaintext already is. Skipping these rules here
                // would silently turn inactivity alerts off.
                //
                // Self-alerts stay out, and cannot move here: the rule
                // belongs to the person it watches, so the devices that
                // would have to raise it (everyone else's) do not hold
                // it. Those are still decided server-side.
                if (rule.isSelfAlert) continue
                // A check-in rule is judged by check-ins ([evaluateCheckInRules]): judged by
                // location updates, it would go off when location stops and never when
                // check-ins do. So is any kind this build doesn't know.
                if (!InactivityWatches.isLocation(rule.watches)) continue
                val refreshed = refreshedByCircle[rule.circleId] ?: continue
                val members = circleMemberDao.getByCircle(rule.circleId)
                // No watcher spec means the rule watches exactly ONE
                // member: its own encrypted target, already decrypted into
                // `targetUserId` when the rule was synced. That is every
                // rule this client creates (the create path writes one
                // row per target and stores no spec), so falling back to
                // "everyone in the circle" would turn a rule about one
                // person into a rule about all of them, firing an
                // inactivity alert for members nobody asked to watch. The
                // iOS app resolves the same case the same way
                // (InactivityAlertsManager.watchSpec).
                val watched = if (rule.targetUserIds == null) {
                    members.filter { it.userId == rule.targetUserId && it.userId != selfId }
                } else {
                    val ids = rule.targetUserIds.split(",").map { it.trim() }.toSet()
                    members.filter { it.userId in ids }
                }
                if (watched.isEmpty()) continue
                val latestByUser = locationDao.observeLatestByCircle(rule.circleId).first().associateBy { it.userId }
                val timeoutMs = rule.timeoutHours.toLong() * 3_600_000L
                for (m in watched) {
                    // Paused, or real-time sharing off, in this
                    // circle: nothing goes off about them,
                    // whatever position this phone last had,
                    // and this phone notes that it saw so.
                    // Before the refresh test: a paused member
                    // is absent from the refresh, and is still
                    // noted.
                    if (notSharingNow(m, now)) continue
                    // Only members THIS refresh produced a location for. Absent
                    // means paused, never shared, or filtered server-side, and
                    // all three mean say nothing.
                    if (m.userId !in refreshed) continue
                    // Never seen this member's location → can't fairly judge; skip (no false alarm).
                    val latestForMember = latestByUser[m.userId] ?: continue
                    // CONSENT (allow_alerts_on_me): see evaluateLowBattery. An
                    // explicit FALSE withdraws consent to being alerted about;
                    // null permits.
                    if (latestForMember.allowAlertsOnMe == false) continue
                    val lastSeen = latestForMember.timestamp
                    val openEvent = localInactivityEventDao.getOpen(rule.id, m.userId)
                    // Silence counts from the later of their last
                    // fix and the end of their latest stretch of not
                    // sharing: a pause that ran out on a still phone
                    // isn't silence. The words still say when they
                    // last shared.
                    if (now - silenceStart(m, lastSeen, now) > timeoutMs) {
                        // Fire once per lapse: only when no lapse is already open for this member.
                        if (openEvent == null) notifyInactive(rule, m, now - lastSeen, now)
                    } else if (openEvent != null && now - lastSeen <= timeoutMs) {
                        // Member is sharing again → resolve the
                        // lapse, clear the stale notification, and
                        // re-arm (the next lapse opens a fresh
                        // event). A stretch of not sharing that
                        // ended is no new location: the lapse stays
                        // open until one comes.
                        localInactivityEventDao.resolve(openEvent.id, now)
                        alertHandler.cancelInactivity(rule.id, m.userId)
                        // Opt-in "back online" alert. Fires once per lapse: the
                        // now-resolved event won't re-open this branch next pass.
                        if (rule.notifyOnReturn) notifyBackOnline(rule, m)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inactivity evaluation failed", e)
        }
    }

    /**
     * Whether a watched [member] is not sharing at [now]: true while the roster shows them paused,
     * or with real-time sharing off, in the circle. Notes, either way, what that says of their
     * latest stretch of not sharing ([notSharingNote]); a note that can't be written skips nothing.
     */
    private suspend fun notSharingNow(member: CircleMemberEntity, now: Long): Boolean {
        val status = MemberSharing.status(member, now)
        try {
            val circleId = AccountIds.storeKey(member.circleId)
            val userId = AccountIds.storeKey(member.userId)
            notSharingNote(memberNotSharingSeenDao.get(circleId, userId), status, now)?.let {
                memberNotSharingSeenDao.put(MemberNotSharingSeenEntity(circleId, userId, it))
            }
        } catch (e: Exception) {
            Log.w(TAG, "A member's stretch of not sharing isn't noted (${e.javaClass.simpleName})")
        }
        return status != SharingStatus.On
    }

    /**
     * When [member]'s silence counts from: [activeSince] their [lastSeen] and this phone's note.
     * A note that can't be read throws, and the pass judges nobody: a false alarm is worse than
     * a late one.
     */
    private suspend fun silenceStart(member: CircleMemberEntity, lastSeen: Long, now: Long): Long =
        activeSince(
            lastSeen,
            memberNotSharingSeenDao.get(AccountIds.storeKey(member.circleId), AccountIds.storeKey(member.userId)),
            now,
        )

    /** Notes older than [NOT_SHARING_KEEP_MS] decide nothing. */
    private suspend fun pruneNotSharingNotes(now: Long) {
        try {
            memberNotSharingSeenDao.deleteOlderThan(now - NOT_SHARING_KEEP_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Old notes of members not sharing aren't pruned (${e.javaClass.simpleName})")
        }
    }

    /**
     * The self-alerts other members of our circles have asked us to watch.
     *
     * Fetched rather than stored: these are not our rules, they change without
     * us, and holding a stale copy would have us alerting about somebody who
     * cancelled the request. Empty on any failure: a network blip must not
     * read as "nobody asked".
     */
    suspend fun fetchCircleSelfAlerts(): List<CircleSelfAlertDto> = readCircleSelfAlerts().orEmpty()

    /**
     * [fetchCircleSelfAlerts], with a failure told apart: null. The check-in rules take back a
     * missed check-in whose request is gone only from a list the server gave
     * ([evaluateCheckInRules]); a failed read is not "the request is gone".
     */
    suspend fun readCircleSelfAlerts(): List<CircleSelfAlertDto>? =
        try {
            val response = tracksApi.getCircleSelfAlerts()
            if (response.isSuccessful) {
                response.body()?.data.orEmpty().also { fresh ->
                    // Only a SUCCESSFUL answer is cached. A failure must not be
                    // remembered as "nobody asked to be watched" for half an hour.
                    cachedSelfAlerts = fresh
                    cachedSelfAlertsAtMs = System.currentTimeMillis()
                }
            } else {
                Log.w(TAG, "circle-self-alerts returned ${response.code()}"); null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch circle self-alerts", e); null
        }

    // The last successful circle-self-alerts answer, for [fetchCircleSelfAlertsCached].
    @Volatile private var cachedSelfAlerts: List<CircleSelfAlertDto>? = null
    @Volatile private var cachedSelfAlertsAtMs: Long = 0L

    /**
     * [fetchCircleSelfAlerts], served from the last successful answer while it is
     * younger than [maxAgeMs].
     *
     * For LocationTrackingService's 5-minute background pass: fetching this list on
     * every pass would be twelve requests an hour from a phone on a desk, for a list
     * that changes only when somebody creates or deletes a "watch me" request. The
     * alerts_check push path (AlertsCheckWorker) keeps calling the uncached function,
     * which also refreshes this cache, so a new request reaches the pass as soon as the
     * server says so and within [SELF_ALERTS_CACHE_MS] regardless.
     */
    suspend fun fetchCircleSelfAlertsCached(
        maxAgeMs: Long = SELF_ALERTS_CACHE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CircleSelfAlertDto> = readCircleSelfAlertsCached(maxAgeMs, nowMs).orEmpty()

    /** [fetchCircleSelfAlertsCached], with a failure told apart, as [readCircleSelfAlerts]. */
    suspend fun readCircleSelfAlertsCached(
        maxAgeMs: Long = SELF_ALERTS_CACHE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<CircleSelfAlertDto>? {
        val cached = cachedSelfAlerts
        if (cached != null && selfAlertsCacheFresh(cachedSelfAlertsAtMs, nowMs, maxAgeMs)) return cached
        return readCircleSelfAlerts()
    }

    /**
     * Judge the self-alerts our circle-mates have asked us to watch.
     *
     * The same evaluation as [evaluateInactivity], with one difference that is
     * the whole reason this exists: the rule belongs to the person it watches,
     * so the watched member IS its owner and OUR device is one of the ones that
     * has to raise it. Nobody else can: their phone is the one that went quiet.
     *
     * Fires once per lapse against the same local event store, keyed on the
     * alert's id, so a member watched by both a self-alert and somebody's
     * ordinary rule gets one notification per rule rather than one silently
     * replacing the other.
     */
    suspend fun evaluateCircleSelfAlerts(
        alerts: List<CircleSelfAlertDto>,
        refreshedByCircle: Map<String, Set<String>>
    ) {
        if (alerts.isEmpty() || refreshedByCircle.isEmpty()) return
        try {
            val selfId = sessionManager.getUserId()
            val now = System.currentTimeMillis()
            pruneNotSharingNotes(now)
            for (alert in alerts) {
                // A check-in request is judged by check-ins ([evaluateCheckInRules]).
                if (!alert.watchesLocation) continue
                val refreshed = refreshedByCircle[alert.circleId] ?: continue
                // The server excludes our own, and we check anyway: a rule about
                // us is not one this device should be raising about us.
                if (alert.userId == selfId) continue

                val member = circleMemberDao.getByCircle(alert.circleId)
                    .firstOrNull { it.userId == alert.userId } ?: continue
                // Paused, or real-time sharing off, in this circle: nothing
                // goes off about them, and this phone notes that it saw so,
                // as in [evaluateInactivity].
                if (notSharingNow(member, now)) continue
                // Only members THIS refresh produced a location for. These
                // rules are other people's requests to be watched, so the person
                // who paused is exactly the person a rule here is about.
                if (alert.userId !in refreshed) continue
                val latestByUser = locationDao.observeLatestByCircle(alert.circleId).first()
                    .associateBy { it.userId }
                // Never seen them → cannot judge fairly, and no false alarm.
                val lastSeen = latestByUser[alert.userId]?.timestamp ?: continue

                val timeoutMs = alert.timeoutHours.toLong() * 3_600_000L
                val openEvent = localInactivityEventDao.getOpen(alert.id, alert.userId)
                // From the later of their last fix and their latest stretch of
                // not sharing.
                if (now - silenceStart(member, lastSeen, now) > timeoutMs) {
                    if (openEvent == null) {
                        notifySelfAlertInactive(alert, member, now - lastSeen, now)
                    }
                } else if (openEvent != null && now - lastSeen <= timeoutMs) {
                    localInactivityEventDao.resolve(openEvent.id, now)
                    alertHandler.cancelInactivity(alert.id, alert.userId)
                    if (alert.notifyOnReturn) {
                        alertHandler.handleAlert(
                            AlertHandler.TYPE_BACK_ONLINE,
                            mapOf(
                                "user_name" to member.displayName,
                                "alert_id" to alertHandler.inactivityDedupeKey(alert.id, alert.userId)
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Circle self-alert evaluation failed", e)
        }
    }

    /** Notification + bell row for a circle-mate's own "watch me" request. */
    private suspend fun notifySelfAlertInactive(
        alert: CircleSelfAlertDto,
        member: CircleMemberEntity,
        inactiveMs: Long,
        now: Long
    ) {
        val since = inactiveSince(context, member.displayName, now - inactiveMs)
        alertHandler.handleAlert(
            AlertHandler.TYPE_OFFLINE,
            mapOf(
                "user_name" to member.displayName,
                AlertHandler.KEY_INACTIVE_SINCE to since,
                "alert_id" to alertHandler.inactivityDedupeKey(alert.id, member.userId)
            )
        )

        val dayBucket = (now / 86_400_000L).toInt()
        val recordId = "local-inact:${alert.id}:${member.userId}:$now"
        try {
            localInactivityEventDao.insert(
                LocalInactivityEventEntity(
                    id = recordId,
                    inactivityAlertId = alert.id,
                    circleId = alert.circleId,
                    targetUserId = member.userId,
                    targetDisplayName = member.displayName,
                    timeoutHours = alert.timeoutHours,
                    inactiveSeconds = inactiveMs / 1000L,
                    triggeredAt = now,
                    triggeredDayBucket = dayBucket
                )
            )
            reportAlertEvent(
                ruleId = alert.id,
                targetUserId = member.userId,
                alertType = AlertHandler.TYPE_INACTIVITY,
                triggeredAt = now
            )
            notificationDao.insert(
                NotificationEntity(
                    id = recordId,
                    circleId = alert.circleId,
                    notificationType = AlertHandler.TYPE_INACTIVITY,
                    title = "Inactivity",
                    body = since,
                    metadata = null,
                    isRead = false,
                    createdDayBucket = dayBucket,
                    createdAt = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist local self-alert record", e)
        }
    }

    /**
     * Post the inactivity notification AND persist a device-local record so the firing
     * shows in the in-app notifications bell and the alert-rules history. These rules are
     * evaluated on-device, and the server gets only the sealed copy for this account's other
     * devices ([reportAlertEvent]), so both of those surfaces would show nothing without this.
     */
    private suspend fun notifyInactive(
        rule: InactivityAlertEntity,
        member: CircleMemberEntity,
        inactiveMs: Long,
        now: Long
    ) {
        val displayName = member.displayName
        val since = inactiveSince(context, displayName, now - inactiveMs)

        alertHandler.handleAlert(
            AlertHandler.TYPE_OFFLINE,
            mapOf(
                "user_name" to displayName,
                AlertHandler.KEY_INACTIVE_SINCE to since,
                // Stable per-(rule, member) id so a re-post replaces rather than stacks, and so
                // auto-resolve can cancel this exact notification.
                "alert_id" to alertHandler.inactivityDedupeKey(rule.id, member.userId)
            )
        )

        // Local-only persistence. The id is prefixed "local-inact:" so NotificationRepository
        // skips the (404-ing) server round-trip when this bell row is read or dismissed.
        val dayBucket = (now / 86_400_000L).toInt()
        val recordId = "local-inact:${rule.id}:${member.userId}:$now"
        try {
            // Insert the event first: its presence (unresolved) is the fire-once marker that
            // gates re-firing and survives process restarts.
            localInactivityEventDao.insert(
                LocalInactivityEventEntity(
                    id = recordId,
                    inactivityAlertId = rule.id,
                    circleId = rule.circleId,
                    targetUserId = member.userId,
                    targetDisplayName = displayName,
                    timeoutHours = rule.timeoutHours,
                    inactiveSeconds = inactiveMs / 1000L,
                    triggeredAt = now,
                    triggeredDayBucket = dayBucket
                )
            )
            reportAlertEvent(
                ruleId = rule.id,
                targetUserId = member.userId,
                alertType = AlertHandler.TYPE_INACTIVITY,
                triggeredAt = now
            )
            notificationDao.insert(
                NotificationEntity(
                    id = recordId,
                    circleId = rule.circleId,
                    notificationType = AlertHandler.TYPE_INACTIVITY,
                    title = "Inactivity",
                    body = since,
                    metadata = null,
                    isRead = false,
                    createdDayBucket = dayBucket,
                    createdAt = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist local inactivity record", e)
        }
    }

    /** Post the "back online" notification + a bell record when a flagged member
     *  resumes sharing and the rule opted into notify-on-return. Mirrors
     *  notifyInactive, minus the open-event marker (the lapse is being resolved). */
    private suspend fun notifyBackOnline(rule: InactivityAlertEntity, member: CircleMemberEntity) {
        val displayName = member.displayName
        val now = System.currentTimeMillis()
        val dayBucket = (now / 86_400_000L).toInt()

        alertHandler.handleAlert(
            AlertHandler.TYPE_BACK_ONLINE,
            mapOf(
                "user_name" to displayName,
                // Distinct from the offline dedupe key so the two don't replace each other.
                "alert_id" to "${alertHandler.inactivityDedupeKey(rule.id, member.userId)}:back"
            )
        )

        try {
            notificationDao.insert(
                NotificationEntity(
                    // "local-inact:" prefix → NotificationRepository skips the server round-trip.
                    id = "local-inact:back:${rule.id}:${member.userId}:$now",
                    circleId = rule.circleId,
                    notificationType = AlertHandler.TYPE_BACK_ONLINE,
                    title = "Back Online",
                    body = "$displayName is sharing location again",
                    metadata = null,
                    isRead = false,
                    createdDayBucket = dayBucket,
                    createdAt = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist back-online record", e)
        }
    }

    // ── check-in rules ──────────────────────────────────────────────────────

    /** When this phone last read each circle's check-ins whole for the check-in rules. */
    private val checkInsReadAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * When this process first saw each circle-mate's check-in request that came with no
     * `armed_at`, as its start: never earlier than the request reached this phone. In memory: a
     * new process starts it again, later, never earlier.
     */
    private val selfAlertsFirstSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The check-in rules' clock. A seam for the tests. */
    internal var checkInClock: () -> Long = System::currentTimeMillis

    /**
     * A check-in rule as a pass judges it: the member it watches, its limit, its start, whether it
     * is that member's own request to be watched ([asked]), and a scheduled rule's [schedule] (null:
     * one-time, judged by its hours).
     */
    private class CheckInRule(
        val id: String,
        val circleId: String,
        val memberId: String,
        val timeoutHours: Int,
        val armedAt: Long,
        val asked: Boolean,
        val schedule: CheckInSchedule? = null,
    )

    /** One member of a check-in rule, as a pass judges them: from the roster, with their open lapse. */
    private class CheckInWatch(val rule: CheckInRule, val member: CircleMemberEntity, val open: LocalInactivityEventEntity?)

    /**
     * Judge the check-in rules: an alert when a member hasn't checked in (the "Check in" button)
     * within a one-time rule's hours, or by a scheduled rule's time ([scheduledVerdict]). This
     * account's own active rules about others, and the circle-mates' requests to be watched
     * ([selfAlerts]: null when they couldn't be read); never a rule about this account's own user.
     * A missed check-in open for a rule this phone no longer judges (ended, turned off or deleted,
     * or a request that is gone) is taken back first ([takeBackUnjudged]).
     *
     * Judged by the check-ins this phone opens with the circle's key, as the location rules are by
     * the locations: the server only wakes the phones that might have to say something. Per
     * circle, its check-ins are read first ([CheckInRepository.refreshRecentCheckIns]): always on
     * a [wake] (alerts_check); in the background pass only when what this phone holds says a rule
     * would go off, or every [CHECK_IN_LAPSE_REFRESH_MS] while a lapse is open, back to the
     * earliest window a scheduled rule there counts from, if that is further back than a day. An alert goes off
     * only after a read in the same pass that came back whole: a read that failed, or met a
     * check-in it couldn't open, judges nobody in that circle (a failed read is not "no check-in").
     *
     * A member a rule watches is skipped when they aren't an active member of the circle here, and,
     * for this account's rules, when this phone knows they've turned off alerts about them (the
     * location rules' source: their latest location's allow_alerts_on_me; unknown allows); their own
     * request to be watched is their consent. Not while they've paused or turned off location
     * sharing: a check-in goes without a position. Then the rule's verdict on their newest check-in
     * here (one more than [LiveTime.MAX_FUTURE_SKEW_MS] ahead of this phone's clock doesn't count):
     * [onceVerdict] or [scheduledVerdict]. An alert once per lapse, against the location rules'
     * local store, keyed by rule and member. A lapse ends when the verdict says so, and is taken
     * back with no "back" notice (a check-in tells the circle itself).
     */
    suspend fun evaluateCheckInRules(selfAlerts: List<CircleSelfAlertDto>?, wake: Boolean) {
        val now = checkInClock()
        // Never throws, as the other evaluators: its caller goes on to the location rules.
        val rules = try {
            val selfId = sessionManager.getUserId() ?: return
            val own = inactivityAlertDao.getActive()
                .filter { InactivityWatches.isCheckIn(it.watches) && !it.isSelfAlert }
                // Stored with its start (InactivityAlertRepository.withStart); one without is judged by
                // nobody, and so is a scheduled one whose schedule doesn't read.
                .mapNotNull { r ->
                    val armedAt = r.armedAt ?: return@mapNotNull null
                    val schedule = CheckInSchedule.of(r)
                    if (CheckInModes.isScheduled(r.checkInMode) && schedule == null) return@mapNotNull null
                    CheckInRule(r.id, r.circleId, r.targetUserId, r.timeoutHours, armedAt, asked = false, schedule = schedule)
                }
            val asked = selfAlerts.orEmpty().filter { it.watchesCheckIns }.mapNotNull { a ->
                val schedule = CheckInSchedule.of(a)
                if (CheckInModes.isScheduled(a.checkInMode) && schedule == null) return@mapNotNull null
                // A start that is missing, or doesn't read, is when this phone first saw the request.
                val armedAt = ServerTime.epochMs(a.armedAt) ?: selfAlertsFirstSeen.getOrPut(a.id) { now }
                CheckInRule(a.id, a.circleId, a.userId, a.timeoutHours, armedAt, asked = true, schedule = schedule)
            }
            // The server leaves this user's own requests out of the circle's, and this checks anyway:
            // a rule about this phone's own user is never this phone's to raise.
            val self = AccountIds.storeKey(selfId)
            (own + asked).filter { AccountIds.storeKey(it.memberId) != self }
        } catch (e: Exception) {
            Log.e(TAG, "Check-in rules not read", e)
            return
        }
        try {
            takeBackUnjudged(rules.mapTo(mutableSetOf()) { it.id }, requestsRead = selfAlerts != null, now = now)
        } catch (e: Exception) {
            Log.e(TAG, "Missed check-ins of ended rules not taken back", e)
        }
        for ((circleId, inCircle) in rules.groupBy { it.circleId }) {
            try {
                evaluateCheckInCircle(circleId, inCircle, wake, now)
            } catch (e: Exception) {
                Log.e(TAG, "Check-in rule evaluation failed in a circle", e)
            }
        }
    }

    /**
     * Take back each open missed check-in whose rule this phone no longer judges ([judged]): this
     * account's rule ended (a one-time rule turns itself off at the check-in), turned off or
     * deleted, or a circle-mate's request that is gone. A lapse whose rule isn't one of this
     * account's here may be a request's, so it goes only when the requests were read
     * ([requestsRead]). No "back" notice: nothing came back, the rule just stopped.
     */
    private suspend fun takeBackUnjudged(judged: Set<String>, requestsRead: Boolean, now: Long) {
        val open = localInactivityEventDao.getOpenByWatches(InactivityWatches.CHECK_IN)
        if (open.isEmpty()) return
        val own = inactivityAlertDao.getAll().mapTo(mutableSetOf()) { it.id }
        for (lapse in open) {
            if (lapse.inactivityAlertId in judged) continue
            if (lapse.inactivityAlertId !in own && !requestsRead) continue
            localInactivityEventDao.resolve(lapse.id, now)
            alertHandler.cancelInactivity(lapse.inactivityAlertId, lapse.targetUserId)
        }
    }

    /** [evaluateCheckInRules] in one circle. */
    private suspend fun evaluateCheckInCircle(circleId: String, rules: List<CheckInRule>, wake: Boolean, now: Long) {
        val roster = circleMemberDao.getByCircle(circleId).associateBy { AccountIds.storeKey(it.userId) }
        // CONSENT (allow_alerts_on_me), from the location rules' source: an explicit FALSE on the
        // member's latest location here says nothing goes off about them. Unknown allows: the
        // server holds its wake-ups to the same consent. A member's own request to be watched is
        // their consent, as on the server (a rule whose target is its creator passes there) and in
        // [evaluateCircleSelfAlerts].
        val optedOut = locationDao.observeLatestByCircle(circleId).first()
            .filter { it.allowAlertsOnMe == false }
            .mapTo(mutableSetOf()) { AccountIds.storeKey(it.userId) }
        val watches = rules.mapNotNull { rule ->
            val key = AccountIds.storeKey(rule.memberId)
            val member = roster[key] ?: return@mapNotNull null
            if (!rule.asked && key in optedOut) return@mapNotNull null
            CheckInWatch(rule, member, localInactivityEventDao.getOpen(rule.id, member.userId))
        }
        if (watches.isEmpty()) return

        val checkIns = checkInRepository.get()
        fun verdicts(last: Map<String, Long>) = watches.map { w ->
            val newest = last[AccountIds.storeKey(w.member.userId)]
            val openSince = w.open?.let { lapseStart(it) }
            val schedule = w.rule.schedule
            w to if (schedule != null) {
                scheduledVerdict(newest, w.rule.armedAt, openSince, schedule.latest(now))
            } else {
                onceVerdict(newest, w.rule.armedAt, openSince, w.rule.timeoutHours.coerceAtLeast(1) * 3_600_000L, now)
            }
        }
        // What this phone holds can only miss a check-in, never invent one: when it says nobody's
        // silence is over the limit, a read wouldn't say otherwise. It may end a lapse unread (a
        // check-in it holds, or a rule started again, is as true before a read as after one).
        val held = verdicts(checkIns.lastCheckIns(circleId, now))
        val read = wake || held.any { (_, verdict) -> verdict.fire } ||
            (watches.any { it.open != null } && now - (checkInsReadAt[circleId] ?: 0L) >= CHECK_IN_LAPSE_REFRESH_MS)
        val judged = if (!read) held else {
            // A scheduled rule's occurrence counts the check-ins since its window opened, which a
            // weekly one puts days back: the read reaches the earliest such window, a day at least.
            val earliestWindow = watches.mapNotNull { it.rule.schedule?.latest(now)?.windowStart }.minOrNull()
            val windowMs = maxOf(CheckInRepository.RECENT_MS, earliestWindow?.let { now - it } ?: 0L)
            val fresh = checkIns.refreshRecentCheckIns(circleId, now, windowMs).getOrNull()
            if (fresh == null || !fresh.whole) return
            checkInsReadAt[circleId] = now
            verdicts(checkIns.lastCheckIns(circleId, now))
        }
        for ((w, verdict) in judged) {
            if (verdict.resolve && w.open != null) {
                localInactivityEventDao.resolve(w.open.id, now)
                alertHandler.cancelInactivity(w.rule.id, w.member.userId)
            }
            // Never from what this phone held alone: only after this pass's whole read.
            if (verdict.fire && read) notifyMissedCheckIn(w.rule, w.member, verdict.since, now)
        }
    }

    /** When an open lapse's silence counted from: as it fired, else its firing less the silence. */
    private fun lapseStart(event: LocalInactivityEventEntity): Long =
        event.silentSince ?: (event.triggeredAt - event.inactiveSeconds * 1000L)

    /**
     * Post the missed check-in notification, and record it as [notifyInactive] records an
     * inactivity alert: the open lapse (the fire-once marker, with when the silence counted from),
     * the sealed copy for the account's other devices ([reportAlertEvent]), and the bell's row.
     */
    private suspend fun notifyMissedCheckIn(rule: CheckInRule, member: CircleMemberEntity, since: Long, now: Long) {
        // A scheduled rule's by its time as set ("hasn't checked in by 9:00 PM"), a one-time one's by its hours.
        val words = rule.schedule?.let { missedCheckInByWords(context, member.displayName, it.dueMinute) }
            ?: missedCheckInWords(context, member.displayName, rule.timeoutHours)
        alertHandler.handleAlert(
            AlertHandler.TYPE_MISSED_CHECK_IN,
            mapOf(
                "user_name" to member.displayName,
                AlertHandler.KEY_MISSED_CHECK_IN to words,
                // Per (rule, member), as an inactivity alert's: a re-post replaces it, and the
                // member's check-in takes this one back (cancelInactivity).
                "alert_id" to alertHandler.inactivityDedupeKey(rule.id, member.userId)
            )
        )

        val dayBucket = (now / 86_400_000L).toInt()
        // "local-inact:": NotificationRepository keeps the bell row off the server.
        val recordId = "local-inact:${rule.id}:${member.userId}:$now"
        try {
            // The lapse first: unresolved, it is the fire-once marker.
            localInactivityEventDao.insert(
                LocalInactivityEventEntity(
                    id = recordId,
                    inactivityAlertId = rule.id,
                    circleId = rule.circleId,
                    targetUserId = member.userId,
                    targetDisplayName = member.displayName,
                    timeoutHours = rule.timeoutHours,
                    inactiveSeconds = (now - since) / 1000L,
                    triggeredAt = now,
                    triggeredDayBucket = dayBucket,
                    watches = InactivityWatches.CHECK_IN,
                    silentSince = since,
                    dueMinute = rule.schedule?.dueMinute,
                )
            )
            reportAlertEvent(
                ruleId = rule.id,
                targetUserId = member.userId,
                alertType = AlertHandler.TYPE_MISSED_CHECK_IN,
                triggeredAt = now
            )
            notificationDao.insert(
                NotificationEntity(
                    id = recordId,
                    circleId = rule.circleId,
                    notificationType = AlertHandler.TYPE_MISSED_CHECK_IN,
                    title = context.getString(R.string.missed_check_in_title),
                    body = words,
                    metadata = null,
                    isRead = false,
                    createdDayBucket = dayBucket,
                    createdAt = now
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist local missed check-in record", e)
        }
    }

    /**
     * Get the number of users currently being monitored.
     */
    suspend fun getMonitoredUserCount(): Int {
        return try {
            inactivityMonitor.monitoredUserCount().toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get monitored user count", e)
            0
        }
    }
}

/**
 * "{name} hasn't shared their location since {time}.": {time} is when the member last shared it,
 * the clock time with the date when it isn't today, so the words stay true however long after
 * they're read. A relative time ("since 1 hour ago") would read wrong, and the bell row would
 * keep saying it as the hours went by.
 */
internal fun inactiveSince(context: Context, name: String, lastFixAt: Long): String =
    // A name not readable yet is "A member", as AlertHandler says it: never " hasn't shared…".
    context.getString(R.string.inactivity_since, name.ifBlank { "A member" }, SosPositionText.time(context, lastFixAt))

/**
 * "{name} hasn't checked in for {N} hours.": a missed check-in's words, in the shade, the bell
 * and the history. [hours] is the rule's limit, not how long it has been: the words stay true
 * however long after they're read. A name not readable yet is "A member", as above.
 */
internal fun missedCheckInWords(context: Context, name: String?, hours: Int): String =
    context.resources.getQuantityString(
        R.plurals.missed_check_in_body, hours, name?.takeIf { it.isNotBlank() } ?: "A member", hours,
    )

/**
 * "{name} hasn't checked in by {time}.": a scheduled rule's missed check-in, by the rule's time as
 * set ([dueMinute] after local midnight), in this phone's time format. As above, true however long
 * after it's read.
 */
internal fun missedCheckInByWords(context: Context, name: String?, dueMinute: Int): String =
    context.getString(R.string.missed_check_in_by, name?.takeIf { it.isNotBlank() } ?: "A member", CheckInRuleText.time(context, dueMinute))
