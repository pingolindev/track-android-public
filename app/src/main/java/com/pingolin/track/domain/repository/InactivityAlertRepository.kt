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

import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.local.entity.LocalInactivityEventEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInScheduleSetting
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.ServerTime
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InactivityAlertRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val inactivityAlertDao: InactivityAlertDao,
    private val localInactivityEventDao: LocalInactivityEventDao,
    private val circleMemberDao: CircleMemberDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val currentKeyGate: CurrentKeyGate,
    /** Where an inactivity alert set up while this phone is behind waits. */
    private val pendingChanges: PendingChanges,
) {
    companion object {
        /** Milliseconds per day for day bucket calculation */
        private const val MS_PER_DAY = 86400000L

        /** The most the server's list hands out at once. */
        internal const val ALERT_PAGE = 100

        /** A bound on the pages one refresh will follow (2,000 alerts). */
        private const val MAX_ALERT_PAGES = 20

        /**
         * One row's request id within a waiting inactivity alert, which makes a row per
         * watched member: made from the change's id and the member's, so a second run sends
         * the same ones and makes no second row.
         */
        internal fun rowRequestId(changeId: String, memberId: String): String =
            java.util.UUID.nameUUIDFromBytes("$changeId:$memberId".toByteArray(Charsets.UTF_8)).toString()

        /**
         * [read] with every check-in rule's start: the server's `armed_at`, else the one this
         * phone stored ([here]), else [nowMs], when it first stores the rule. Never earlier: a
         * rule counted from before it existed would go off about a check-in missed before anyone
         * asked for one.
         */
        internal fun withStart(
            read: List<InactivityAlertEntity>,
            here: Map<String, InactivityAlertEntity>,
            nowMs: Long,
        ): List<InactivityAlertEntity> = read.map { alert ->
            if (!InactivityWatches.isCheckIn(alert.watches) || alert.armedAt != null) alert
            else alert.copy(armedAt = here[alert.id]?.armedAt ?: nowMs)
        }
    }

    /**
     * Observe all inactivity alerts.
     */
    fun observeAll(): Flow<List<InactivityAlertEntity>> = inactivityAlertDao.observeAll().orWhenUnopenable(emptyList())

    /**
     * Observe active inactivity alerts.
     */
    fun observeActive(): Flow<List<InactivityAlertEntity>> = inactivityAlertDao.observeActive().orWhenUnopenable(emptyList())

    /**
     * Observe inactivity alerts for a specific circle.
     */
    fun observeByCircle(circleId: String): Flow<List<InactivityAlertEntity>> =
        inactivityAlertDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /**
     * Observe device-local inactivity firings. Client-side evaluation records nothing on the
     * server, so this is the only source for the bell + alert-rules history.
     */
    fun observeLocalEvents(): Flow<List<LocalInactivityEventEntity>> =
        localInactivityEventDao.observeAll().orWhenUnopenable(emptyList())

    /**
     * Compute day bucket from timestamp.
     */
    private fun computeDayBucket(timestampMs: Long): Int =
        (timestampMs / MS_PER_DAY).toInt()

    /**
     * Decrypt a string from encrypted payload.
     */
    private fun decryptString(circleId: String, encrypted: EncryptedPayload): String? {
        return try {
            cryptoManager.decryptData(
                circleId,
                uniffi.pingolin_mobile.EncryptedData(
                    nonce = encrypted.nonce,
                    ciphertext = encrypted.ciphertext
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decode a stored watch spec into the entity's targetUserIds (null = everyone).
     *
     * Read-only: this client never WRITES a watch spec. Doing so latches `client_only`
     * on the server and permanently stops the server's inactivity worker evaluating the
     * rule. See [updateInactivityAlert]. Specs written by older app versions (or
     * another client) still decode here so their rules keep behaving as their author
     * intended.
     */
    private fun decodeWatchSpec(spec: String?): String? =
        if (spec == null || spec == "all" || spec.isBlank()) null else spec

    /**
     * Create inactivity alert rule(s), one server row per watched member.
     *
     * Each row targets exactly ONE member and carries that member's per-circle pseudonym, so the
     * server can notice that member going quiet without learning the real user ID; what it does
     * then is send a contentless wake-up. The rule itself is judged on the watcher's device
     * (MonitoringManager.evaluateInactivity judges every watch-someone-else location rule,
     * evaluateCheckInRules every check-in rule), from the rows [AlertRulesReload] keeps here. A self-alert is the exception: the devices that would raise
     * it are the other members', and they don't hold it, so it is still decided server-side. A
     * "watch everyone/members" request EXPANDS into one create call per watched member (self
     * excluded). Rows are created with clientOnly=false and no encrypted_conditions.
     *
     * @param watchedUserIds explicit members to watch; empty = everyone in the circle (minus
     * self).
     * @param watches what the rule watches ([InactivityWatches]): location updates, or check-ins.
     * A check-in rule never alerts "back online": a check-in already tells the circle.
     * @param schedule a check-in rule's schedule: a scheduled rule ("check in by 9:00 PM"), whose
     * [timeoutMinutes] the server ignores. None: a one-time rule ("check in within N hours").
     */
    suspend fun createInactivityAlert(
        circleId: String,
        targetUserId: String,
        timeoutMinutes: Int,
        isSelfAlert: Boolean = false,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        notifyOnReturn: Boolean = false,
        cooldownMinutes: Int = 60,
        watchedUserIds: List<String> = emptyList(),
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
        watches: String = InactivityWatches.LOCATION,
        schedule: CheckInScheduleSetting? = null,
    ): Result<String> {
        return try {
            val checkIns = InactivityWatches.isCheckIn(watches)
            // A schedule makes sense on a check-in rule alone.
            val scheduled = schedule?.takeIf { checkIns }
            val checkInMode = if (!checkIns) null else if (scheduled != null) CheckInModes.SCHEDULED else CheckInModes.ONCE
            // Nothing is sealed under a key a removed member may hold. The person's new
            // alert ([keepIfBehind]) is kept while this phone is behind.
            try {
                currentKeyGate.requireCurrentKey(circleId)
            } catch (e: KeyNotCurrentException) {
                if (!keepIfBehind || pendingRun != null) throw e
                return pendingChanges.queue(
                    circleId,
                    PendingInputs.InactivityAlert(
                        pendingChanges.circleName(circleId), targetUserId, timeoutMinutes, isSelfAlert, notifyPush,
                        notifyEmail, notifyOnReturn && !checkIns, cooldownMinutes, watchedUserIds,
                        watchedUserIds.mapNotNull { id -> pendingChanges.memberName(circleId, id)?.let { id to it } }.toMap(),
                        watches = watches,
                        schedule = scheduled,
                    ),
                )
            }
            val dayBucket = computeDayBucket(System.currentTimeMillis())
            val selfId = sessionManager.getUserId()

            // Resolve the concrete member(s) to create a server-eval row for. A self rule
            // watches the creator (selfId). A watch-others rule uses the explicit picks or,
            // when none were picked, everyone in the circle; self is always excluded (you
            // can't be inactive "to yourself"). "Everyone" expands from the locally-synced
            // circle members, the same source client-side evaluation uses.
            val targets: List<String> = when {
                isSelfAlert -> listOf(selfId ?: targetUserId)
                watchedUserIds.isNotEmpty() -> watchedUserIds.filter { it != selfId }
                else -> circleMemberDao.getByCircle(circleId).map { it.userId }.filter { it != selfId }
            }
            if (targets.isEmpty()) {
                return Result.failure(Exception("No members to watch for inactivity"))
            }

            // Each target is an independent create call whose encryptData +
            // deriveUserPseudonym are synchronous JNI (UniFFI). Run the whole fan-out off the
            // Main thread, and DON'T abort on one member's failure: a mid-loop return would
            // leave the earlier members' rows created on the server while reporting outright
            // failure (and skipping the refresh). Instead attempt every target, remember the
            // first error, and always refresh at the end so the local cache matches exactly
            // what the server accepted.
            val (lastAlertId, firstError) = try {
                withContext(Dispatchers.IO) {
                    var lastId: String? = null
                    var firstErr: Exception? = null
                    for (memberId in targets) {
                        try {
                            // E2EE: encrypt the target user ID with the circle key. The backend requires
                            // encrypted_target_user_id + day_bucket (there is no plaintext target_user_id);
                            // without these the request is rejected before the handler runs.
                            val encData = cryptoManager.encryptData(circleId, memberId)
                            val encryptedTargetUserId = EncryptedPayload(
                                nonce = encData.nonce,
                                ciphertext = encData.ciphertext
                            )
                            // Server-eval routing: the server matches inactivity by the target's per-circle
                            // pseudonym, so it never sees the raw user ID.
                            val targetPseudonym = cryptoManager.deriveUserPseudonym(circleId, memberId)

                            val response = tracksApi.createInactivityAlert(
                                CreateInactivityAlertRequest(
                                    circleId = circleId,
                                    targetUserId = memberId,
                                    timeoutMinutes = timeoutMinutes,
                                    isSelfAlert = isSelfAlert,
                                    notifyPush = notifyPush,
                                    notifyEmail = notifyEmail,
                                    notifyOnReturn = notifyOnReturn && !checkIns,
                                    cooldownMinutes = cooldownMinutes,
                                    // No sealed conditions: the row names its one member (see above).
                                    encryptedConditions = null,
                                    clientOnly = false,
                                    // A check-in rule needs it: the server finds the member's
                                    // check-ins by it alone.
                                    targetUserPseudonym = targetPseudonym,
                                    encryptedTargetUserId = encryptedTargetUserId,
                                    dayBucket = dayBucket,
                                    clientRequestId = pendingRun?.let { rowRequestId(it.requestId, memberId) },
                                    // A location rule says nothing, as every older app's.
                                    watches = InactivityWatches.CHECK_IN.takeIf { checkIns },
                                    checkInMode = checkInMode,
                                    scheduleDays = scheduled?.days,
                                    scheduleDueMinute = scheduled?.dueMinute,
                                    // Absent: the start of the day.
                                    scheduleFromMinute = scheduled?.fromMinute,
                                    scheduleTz = scheduled?.tz,
                                    // Only about yourself: the server refuses it on a rule about someone else.
                                    scheduleTzFollowsPhone = scheduled?.let { it.followsPhone && isSelfAlert },
                                )
                            )

                            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                                lastId = response.body()!!.data!!
                            } else {
                                android.util.Log.w("InactivityAlertRepo", "createInactivityAlert HTTP not OK: code=${response.code()}")
                                if (firstErr == null) {
                                    firstErr = RefusedException(when (response.code()) {
                                        402 -> "Inactivity alerts need a subscription. If anyone in this circle subscribes, the whole circle gets them."
                                        403 -> "You can only create alerts for members of your circle."
                                        else -> "Failed to create inactivity alert"
                                    }, response.code(), ApiErrors.codeOf(response))
                                }
                            }
                        } catch (e: Exception) {
                            // A THROWN failure mid-loop (IOException on a
                            // network drop, or a JNI throw from encryptData /
                            // deriveUserPseudonym) must NOT abort the
                            // remaining members, which would strand the rows
                            // already created. Record the first error and
                            // continue so every target is attempted; the
                            // successes are still cached by the refresh in
                            // the finally below.
                            android.util.Log.w("InactivityAlertRepo", "createInactivityAlert member failed, continuing", e)
                            if (firstErr == null) firstErr = e
                            continue
                        }
                    }
                    lastId to firstErr
                }
            } finally {
                // Refresh to get the full entities from server. ALWAYS, even on partial
                // failure or if the whole fan-out threw, so the successfully-created
                // rows show up locally instead of waiting for a manual refresh. In a
                // `finally` (not merely after the block) because an unexpected throw
                // would otherwise skip it and strand the rows already created.
                refreshInactivityAlerts()
            }

            // Surface a failure if any target could not be created (the successful ones were still
            // created and are now cached by the refresh above); otherwise report the last alert id.
            firstError?.let { Result.failure<String>(it) } ?: Result.success(lastAlertId ?: "")
        } catch (e: Exception) {
            android.util.Log.e("InactivityAlertRepo", "createInactivityAlert threw", e)
            Result.failure(e)
        }
    }

    /**
     * Update an inactivity alert. Partial: only the fields passed are changed.
     *
     * This DELIBERATELY sends no `encrypted_conditions`, and takes no watcher set to put
     * in one. The server turns server-side evaluation OFF for any rule that arrives with
     * a non-null value, and nothing turns it back on, so one edit that carried a value
     * would disable that alert's evaluation permanently, on every device the user owns,
     * not just the one that made the edit.
     *
     * There is nothing to gain from it either: [createInactivityAlert] models a watcher
     * set as ONE ROW PER TARGET, each carrying its own `encrypted_target_user_id`, and
     * creates them with `encryptedConditions = null, clientOnly = false`. A conditions
     * blob written by an edit could only contradict the row it was attached to. The iOS
     * app's update path passes no `encryptedConditions` for the same reason.
     *
     * [schedule]: a scheduled check-in rule's new schedule, sent whole (the server replaces it
     * whole: a from time or a follow-the-phone left out would be dropped). Its kind never changes.
     */
    suspend fun updateInactivityAlert(
        alertId: String,
        timeoutMinutes: Int? = null,
        isActive: Boolean? = null,
        notifyPush: Boolean? = null,
        notifyEmail: Boolean? = null,
        notifyOnReturn: Boolean? = null,
        cooldownMinutes: Int? = null,
        schedule: CheckInScheduleSetting? = null,
    ): Result<Unit> {
        return try {
            val response = tracksApi.updateInactivityAlert(
                alertId,
                UpdateInactivityAlertRequest(
                    timeoutMinutes = timeoutMinutes,
                    isActive = isActive,
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifyOnReturn = notifyOnReturn,
                    cooldownMinutes = cooldownMinutes,
                    encryptedConditions = null,
                    scheduleDays = schedule?.days,
                    scheduleDueMinute = schedule?.dueMinute,
                    scheduleFromMinute = schedule?.fromMinute,
                    scheduleTz = schedule?.tz,
                    scheduleTzFollowsPhone = schedule?.followsPhone,
                )
            )

            if (response.isSuccessful) {
                // Update local cache
                inactivityAlertDao.getById(alertId)?.let { existing ->
                    // Turned back on: the server starts a check-in rule's clock again (armed_at),
                    // so a rule off for a week doesn't go off the moment it's back. So does this
                    // phone, from a moment no earlier than the server's, until a refresh brings it.
                    val rearmed = isActive == true && !existing.isActive && InactivityWatches.isCheckIn(existing.watches)
                    // A new schedule, on a scheduled rule only, as the server takes it.
                    val newSchedule = schedule?.takeIf { CheckInModes.isScheduled(existing.checkInMode) }
                    val updated = existing.copy(
                        isActive = isActive ?: existing.isActive,
                        notifyPush = notifyPush ?: existing.notifyPush,
                        notifyEmail = notifyEmail ?: existing.notifyEmail,
                        notifyOnReturn = notifyOnReturn ?: existing.notifyOnReturn,
                        cooldownMinutes = cooldownMinutes ?: existing.cooldownMinutes,
                        armedAt = if (rearmed) System.currentTimeMillis() else existing.armedAt,
                        scheduleDays = newSchedule?.days ?: existing.scheduleDays,
                        scheduleDueMinute = newSchedule?.dueMinute ?: existing.scheduleDueMinute,
                        scheduleFromMinute = if (newSchedule != null) newSchedule.fromMinute else existing.scheduleFromMinute,
                        scheduleTz = newSchedule?.tz ?: existing.scheduleTz,
                        scheduleTzFollowsPhone = newSchedule?.followsPhone ?: existing.scheduleTzFollowsPhone,
                    )
                    inactivityAlertDao.update(updated)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update inactivity alert"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Move a scheduled rule that follows this phone to the phone's zone ([tz]): the zone alone, as
     * the server takes it, so nothing else about the rule changes (CheckInZoneFollower).
     */
    suspend fun updateScheduleZone(alertId: String, tz: String): Result<Unit> {
        return try {
            val response = tracksApi.updateInactivityAlert(
                alertId,
                UpdateInactivityAlertRequest(
                    timeoutMinutes = null,
                    isActive = null,
                    notifyPush = null,
                    notifyEmail = null,
                    notifyOnReturn = null,
                    cooldownMinutes = null,
                    encryptedConditions = null,
                    scheduleTz = tz,
                ),
            )
            // Refused (a 4xx): asking again changes nothing. Otherwise worth another try.
            if (!response.isSuccessful) {
                return Result.failure(RefusedException("Failed to update the alert's time zone", response.code(), ApiErrors.codeOf(response)))
            }
            inactivityAlertDao.getById(alertId)?.let { inactivityAlertDao.update(it.copy(scheduleTz = tz)) }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Delete an inactivity alert.
     */
    suspend fun deleteInactivityAlert(alertId: String): Result<Unit> {
        return try {
            val response = tracksApi.deleteInactivityAlert(alertId)
            if (response.isSuccessful) {
                inactivityAlertDao.deleteById(alertId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete inactivity alert"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refresh inactivity alerts from the server: every page, to the last (a page holds at
     * most [ALERT_PAGE]), so an account watching more people than a page holds gets every
     * alert on this phone.
     *
     * What it writes, as AlertRuleRepository.refreshAlertRules: only the alerts that changed;
     * never a weaker copy (an alert whose sealed target or watch list doesn't open here keeps
     * the row this phone has, which a missing watch list would have narrowed to one person);
     * from a whole list, the alerts missing from it go, and from a partial one, nothing; all
     * in one transaction, for the account the refresh began for only.
     */
    suspend fun refreshInactivityAlerts(): Result<List<InactivityAlertEntity>> = refreshAlerts().map { it.alerts }

    /** [refreshInactivityAlerts], with every alert id the server listed, when it gave the list whole. Null otherwise. */
    suspend fun refreshInactivityAlertsWhole(): WholeAlerts? =
        refreshAlerts().getOrNull()?.takeIf { it.whole }?.let { WholeAlerts(it.ids, it.alerts) }

    /** An alert list the server gave whole: every id it listed, and those alerts readable here, fresh. */
    data class WholeAlerts(val ids: Set<String>, val alerts: List<InactivityAlertEntity>) {
        /** Every alert listed was read here: none waits for a circle key, or has a sealed part that won't open. */
        val allRead: Boolean get() = alerts.size == ids.size
    }

    private class RefreshedAlerts(val ids: Set<String>, val alerts: List<InactivityAlertEntity>, val whole: Boolean)

    private suspend fun refreshAlerts(): Result<RefreshedAlerts> {
        val account = sessionManager.getUserId() ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val dtos = mutableListOf<InactivityAlertResponse>()
            // By id: a page the server starts again repeats alerts.
            val seen = mutableSetOf<String>()
            var whole = false
            var last: InactivityAlertResponse? = null
            for (page in 1..MAX_ALERT_PAGES) {
                val response = tracksApi.getInactivityAlerts(
                    limit = ALERT_PAGE, cursorDayBucket = last?.dayBucket, cursorId = last?.id,
                )
                val items = response.body()?.takeIf { response.isSuccessful && it.success }?.data
                if (items == null) {
                    if (page == 1) return Result.failure(Exception("Failed to fetch inactivity alerts"))
                    break
                }
                // To an empty page, not a short one: a page can come back short of what the server
                // sent (an entry naming no circle is refused on the way in), and stopping there
                // would take a list that has more for whole.
                if (items.isEmpty()) {
                    whole = true
                    break
                }
                items.filterTo(dtos) { seen.add(it.id) }
                last = items.last()
            }
            // Per-alert decrypt is pure CPU (UniFFI); move it off the Main
            // thread so a large alert set can't jank the caller.
            val read = withContext(Dispatchers.Default) { dtos.mapNotNull { readAlert(it) } }
            val listed = dtos.mapTo(mutableSetOf()) { it.id }
            val here = inactivityAlertDao.getAll().associateBy { it.id }
            val alerts = withStart(read, here, System.currentTimeMillis())
            val changed = alerts.filter { here[it.id] != it }
            // A new alert waiting to sync has no row here yet, so nothing waiting can be lost.
            val gone = if (whole) here.keys.filter { it !in listed } else emptyList()
            if (!inactivityAlertDao.applyRefresh(changed, gone) { AccountIds.same(sessionManager.getUserId(), account) }) {
                return Result.failure(IllegalStateException("Signed out during the refresh"))
            }
            Result.success(RefreshedAlerts(listed, alerts, whole))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** A listed alert as this phone reads it, or null when a sealed part of it doesn't open here. */
    private fun readAlert(dto: InactivityAlertResponse): InactivityAlertEntity? {
        // E2EE: Decrypt target user ID
        val decryptedTargetUserId = decryptString(dto.circleId, dto.encryptedTargetUserId) ?: return null
        // E2EE: the watcher set rides in encrypted_conditions (client-only data).
        val watchSpec = dto.encryptedConditions?.let { decryptString(dto.circleId, it) ?: return null }
        return InactivityAlertEntity(
            id = dto.id,
            userId = dto.userId,
            circleId = dto.circleId,
            targetUserId = decryptedTargetUserId,
            timeoutHours = dto.timeoutHours,
            isSelfAlert = dto.isSelfAlert,
            notifyPush = dto.notifyPush,
            notifyEmail = dto.notifyEmail,
            notifyOnReturn = dto.notifyOnReturn,
            cooldownMinutes = dto.cooldownMinutes,
            isActive = dto.isActive,
            lastTriggeredDayBucket = dto.lastTriggeredDayBucket,
            dayBucket = dto.dayBucket,
            clientOnly = dto.clientOnly,
            targetUserIds = decodeWatchSpec(watchSpec),
            // An older server's row says nothing: a location rule, as all of them were.
            watches = dto.watches ?: InactivityWatches.LOCATION,
            // Fractions of a second vary, and the offset may be "+00:00" (ServerTime).
            armedAt = ServerTime.epochMs(dto.armedAt),
            // A check-in rule's kind, and a scheduled one's schedule; none on a location rule.
            checkInMode = dto.checkInMode,
            scheduleDays = dto.scheduleDays,
            scheduleDueMinute = dto.scheduleDueMinute,
            scheduleFromMinute = dto.scheduleFromMinute,
            scheduleTz = dto.scheduleTz,
            scheduleTzFollowsPhone = dto.scheduleTzFollowsPhone ?: false,
        )
    }

    /**
     * The check-in requests circle-mates have made of this account's circles ("Notify my circle
     * if I don't check in"), from the server. A failure is a failure, never "nobody asked": the
     * alert history then says the check-ins couldn't be loaded (HistoryCheckIns).
     */
    suspend fun circleCheckInRequests(): Result<List<CircleSelfAlertDto>> {
        return try {
            val response = tracksApi.getCircleSelfAlerts()
            val requests = response.body()?.takeIf { response.isSuccessful && it.success }?.data
                ?: return Result.failure(Exception("Failed to fetch circle self-alerts"))
            Result.success(requests.filter { it.watchesCheckIns })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get inactivity events for a specific alert.
     */
    suspend fun getInactivityEvents(alertId: String? = null): Result<List<InactivityEventResponse>> {
        return try {
            val response = tracksApi.getInactivityEvents(alertId = alertId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception("Failed to fetch inactivity events"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
