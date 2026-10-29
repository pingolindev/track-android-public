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

import com.pingolin.track.data.local.dao.AlertRuleDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.*
import com.pingolin.track.domain.pending.PendingChanges
import com.pingolin.track.domain.pending.PendingInputs
import com.pingolin.track.domain.pending.PendingKind
import com.pingolin.track.domain.pending.PendingRun
import com.pingolin.track.util.AccountIds
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.orWhenUnopenable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRuleRepository @Inject constructor(
    private val tracksApi: TrackApi,
    private val alertRuleDao: AlertRuleDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val currentKeyGate: CurrentKeyGate,
    /** Where an alert set up or changed while this phone is behind waits. */
    private val pendingChanges: PendingChanges,
) {
    companion object {
        const val RULE_TYPE_LOW_BATTERY = "low_battery"
        // There is no RULE_TYPE_OFFLINE: the server accepts one rule type and
        // refuses "offline" by name, so a constant for it could only describe a
        // rule that cannot be created.

        /** Milliseconds per day for day bucket calculation */
        private const val MS_PER_DAY = 86400000L

        /** A bound on the pages one rule refresh will follow (1,000 rules). */
        private const val MAX_RULE_PAGES = 20
    }

    /**
     * Observe all alert rules.
     */
    fun observeAll(): Flow<List<AlertRuleEntity>> = alertRuleDao.observeAll().orWhenUnopenable(emptyList())

    /**
     * Observe active alert rules.
     */
    fun observeActive(): Flow<List<AlertRuleEntity>> = alertRuleDao.observeActive().orWhenUnopenable(emptyList())

    /**
     * Observe alert rules for a specific circle.
     */
    fun observeByCircle(circleId: String): Flow<List<AlertRuleEntity>> =
        alertRuleDao.observeByCircle(circleId).orWhenUnopenable(emptyList())

    /**
     * Observe alert rules by type.
     */
    fun observeByType(ruleType: String): Flow<List<AlertRuleEntity>> =
        alertRuleDao.observeByType(ruleType).orWhenUnopenable(emptyList())

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
     * The battery threshold out of a rule's sealed conditions, or null when
     * there is none to read (an old rule, or a circle key we do not hold).
     */
    private fun decryptThreshold(circleId: String?, encrypted: EncryptedPayload?): Int? {
        if (circleId == null || encrypted == null) return null
        return decryptString(circleId, encrypted)?.let { thresholdIn(it) }
    }

    /** The battery threshold in a rule's opened conditions, or null when they hold none. */
    private fun thresholdIn(json: String): Int? = try {
        org.json.JSONObject(json).optInt("threshold", -1).takeIf { it >= 0 }
    } catch (e: Exception) {
        null
    }

    /**
     * Create a new alert rule.
     * @param ruleType RULE_TYPE_LOW_BATTERY — the only type the server accepts.
     * @param conditions Map of conditions (e.g., {"threshold": 20} for low battery)
     */
    suspend fun createAlertRule(
        circleId: String?,
        targetUserId: String,
        ruleType: String,
        conditions: Map<String, Any>,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        notifySms: Boolean = false,
        cooldownMinutes: Int? = null,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<String> {
        return try {
            val dayBucket = computeDayBucket(System.currentTimeMillis())

            // The circle to use for encryption - use the first active circle if null
            val encryptionCircleId = circleId
                ?: return Result.failure(Exception("Circle ID is required for E2EE"))
            // Nothing is sealed under a key a removed member may hold. The person's new
            // low-battery alert ([keepIfBehind]) is kept while this phone is behind.
            try {
                currentKeyGate.requireCurrentKey(encryptionCircleId)
            } catch (e: KeyNotCurrentException) {
                val threshold = (conditions["threshold"] as? Number)?.toInt()
                if (!keepIfBehind || pendingRun != null || ruleType != RULE_TYPE_LOW_BATTERY || threshold == null) throw e
                return pendingChanges.queue(
                    encryptionCircleId,
                    PendingInputs.BatteryAlert(
                        pendingChanges.circleName(encryptionCircleId), targetUserId,
                        pendingChanges.memberName(encryptionCircleId, targetUserId), threshold, notifyPush, notifyEmail,
                    ),
                )
            }

            // E2EE: Encrypt target user ID
            val encryptedTargetUserIdData = cryptoManager.encryptData(encryptionCircleId, targetUserId)
            val encryptedTargetUserId = EncryptedPayload(
                nonce = encryptedTargetUserIdData.nonce,
                ciphertext = encryptedTargetUserIdData.ciphertext
            )

            // E2EE: the threshold too. In the plaintext `conditions`
            // column the server could read a number it has no use
            // for — nothing there evaluates it, the comparison happens
            // on the watching device. Sent as JSON so it decrypts to
            // the same {"threshold": N} shape the other clients write
            // and read.
            val conditionsJson = org.json.JSONObject(conditions).toString()
            val encryptedConditionsData = cryptoManager.encryptData(encryptionCircleId, conditionsJson)
            val encryptedConditions = EncryptedPayload(
                nonce = encryptedConditionsData.nonce,
                ciphertext = encryptedConditionsData.ciphertext
            )

            val response = tracksApi.createAlertRule(
                CreateAlertRuleRequest(
                    circleId = circleId,
                    targetUserId = targetUserId,
                    ruleType = ruleType,
                    // Empty: the column is NOT NULL and the value is not the
                    // server's. The real one is in encryptedConditions.
                    conditions = emptyMap(),
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    cooldownMinutes = cooldownMinutes,
                    encryptedConditions = encryptedConditions,
                    encryptedTargetUserId = encryptedTargetUserId,
                    dayBucket = dayBucket,
                    clientRequestId = pendingRun?.requestId,
                )
            )

            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val ruleId = response.body()!!.data!!

                // Refresh to get the full entity from server
                refreshAlertRules()

                Result.success(ruleId)
            } else {
                android.util.Log.w("AlertRuleRepo", "createAlertRule HTTP not OK: code=${response.code()}")
                // Surface the real reason instead of a generic failure. No subscription is
                // needed: alert rules are free for everyone (the server applies no gate), so
                // nothing here says otherwise.
                Result.failure(RefusedException(when (response.code()) {
                    403 -> "You can only create alerts for members of your circle."
                    else -> "Failed to create alert rule"
                }, response.code(), ApiErrors.codeOf(response)))
            }
        } catch (e: Exception) {
            android.util.Log.e("AlertRuleRepo", "createAlertRule threw (likely encryptData)", e)
            Result.failure(e)
        }
    }

    /**
     * Create a low battery alert rule.
     * @param threshold Battery percentage threshold (e.g., 20 for 20%)
     */
    suspend fun createLowBatteryAlert(
        circleId: String,
        targetUserId: String,
        threshold: Int = 20,
        notifyPush: Boolean = true,
        notifyEmail: Boolean = false,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<String> {
        return createAlertRule(
            circleId = circleId,
            targetUserId = targetUserId,
            ruleType = RULE_TYPE_LOW_BATTERY,
            conditions = mapOf("threshold" to threshold),
            notifyPush = notifyPush,
            notifyEmail = notifyEmail,
            cooldownMinutes = 60, // 1 hour cooldown for battery alerts
            keepIfBehind = keepIfBehind,
            pendingRun = pendingRun,
        )
    }

    /**
     * Update an alert rule.
     */
    suspend fun updateAlertRule(
        ruleId: String,
        isActive: Boolean? = null,
        notifyPush: Boolean? = null,
        notifyEmail: Boolean? = null,
        notifySms: Boolean? = null,
        cooldownMinutes: Int? = null,
        conditions: Map<String, Any>? = null,
        keepIfBehind: Boolean = false,
        pendingRun: PendingRun? = null,
    ): Result<Unit> {
        return try {
            // Editing the threshold has to seal it the same way creating it does,
            // or the first edit puts the number the server cannot use back in the
            // clear. The rule's circle is needed to encrypt, so read it locally.
            val local = conditions?.let { alertRuleDao.getById(ruleId) }
            val circleId = local?.circleId
            if (conditions != null && local != null && circleId != null) {
                try {
                    currentKeyGate.requireCurrentKey(circleId)
                } catch (e: KeyNotCurrentException) {
                    // The person's new threshold is kept while this phone is
                    // behind, and only the threshold is sent when it runs.
                    val threshold = (conditions["threshold"] as? Number)?.toInt()
                    if (!keepIfBehind || pendingRun != null || threshold == null ||
                        isActive != null || notifySms != null || cooldownMinutes != null
                    ) throw e
                    // With the rule as the person saw it: changed elsewhere
                    // meanwhile, it isn't overwritten.
                    return pendingChanges.queue(
                        circleId,
                        PendingInputs.AlertChange(pendingChanges.circleName(circleId), ruleId, threshold, notifyPush, notifyEmail),
                        seen = PendingInputs.toJson(
                            PendingInputs.AlertChange(pendingChanges.circleName(circleId), ruleId, local.batteryThreshold, local.notifyPush, local.notifyEmail)
                        ),
                    )
                }
            }
            val encryptedConditions = if (conditions != null && circleId != null) {
                try {
                    val json = org.json.JSONObject(conditions).toString()
                    val blob = cryptoManager.encryptData(circleId, json)
                    EncryptedPayload(nonce = blob.nonce, ciphertext = blob.ciphertext)
                } catch (e: Exception) {
                    android.util.Log.e("AlertRuleRepo", "Failed to encrypt conditions", e); null
                }
            } else null

            val response = tracksApi.updateAlertRule(
                ruleId,
                UpdateAlertRuleRequest(
                    isActive = isActive,
                    notifyPush = notifyPush,
                    notifyEmail = notifyEmail,
                    notifySms = notifySms,
                    cooldownMinutes = cooldownMinutes,
                    encryptedConditions = encryptedConditions,
                    // Only sent when we could not seal it; an unsealed threshold
                    // beats an edit that silently keeps the old one.
                    conditions = if (encryptedConditions == null) conditions else emptyMap()
                )
            )

            if (response.isSuccessful) {
                // Update local cache
                alertRuleDao.getById(ruleId)?.let { existing ->
                    val updated = existing.copy(
                        isActive = isActive ?: existing.isActive,
                        notifyPush = notifyPush ?: existing.notifyPush,
                        notifyEmail = notifyEmail ?: existing.notifyEmail,
                        notifySms = notifySms ?: existing.notifySms,
                        cooldownMinutes = cooldownMinutes ?: existing.cooldownMinutes
                    )
                    alertRuleDao.update(updated)
                }
                Result.success(Unit)
            } else {
                Result.failure(RefusedException("Failed to update alert rule", response.code(), ApiErrors.codeOf(response)))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * A rule's current battery threshold, so the edit dialog can pre-fill it.
     *
     * Sealed copy first. The plaintext `conditions` column holds nothing for a
     * rule whose threshold is sealed under the circle key, so reading only that
     * column would open the dialog on the default, and saving would write that
     * default back over whatever the user had chosen. Same fallback order as the
     * sync mapping; the plaintext column is still read for older rules that have
     * no sealed copy.
     *
     * Prefers the locally synced value when there is one: it is already
     * decrypted, and this is called to open a dialog.
     */
    suspend fun getBatteryThreshold(ruleId: String): Int? {
        alertRuleDao.getById(ruleId)?.batteryThreshold?.let { return it }
        return try {
            val response = tracksApi.getAlertRule(ruleId)
            if (response.isSuccessful && response.body()?.success == true) {
                val dto = response.body()?.data
                val circleId = dto?.circleId ?: alertRuleDao.getById(ruleId)?.circleId
                decryptThreshold(circleId, dto?.encryptedConditions)
                    ?: (dto?.conditions?.get("threshold") as? Number)?.toInt()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete an alert rule.
     */
    suspend fun deleteAlertRule(ruleId: String): Result<Unit> {
        return try {
            val response = tracksApi.deleteAlertRule(ruleId)
            if (response.isSuccessful) {
                alertRuleDao.deleteById(ruleId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete alert rule"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * [refreshAlertRules], then every rule id the server listed, when it gave the list whole
     * (every page, to the last): the only list that says a rule was deleted. A rule listed but
     * not readable here is in it. Null otherwise.
     */
    suspend fun refreshAlertRulesWhole(): WholeRules? =
        refreshRules().getOrNull()?.takeIf { it.whole }?.let { WholeRules(it.ids, it.rules) }

    /** A rule list the server gave whole: every id it listed, and those rules readable here, fresh. */
    data class WholeRules(val ids: Set<String>, val rules: List<AlertRuleEntity>) {
        /** Every rule listed was read here: none waits for a circle key, or has a sealed part that won't open. */
        val allRead: Boolean get() = rules.size == ids.size
    }

    /**
     * Refresh alert rules from the server: every page, followed by `next_cursor` (a page holds
     * at most 50). A page that can't be had ends the refresh with the rules read so far, and
     * the list isn't whole.
     *
     * What it writes ([AlertRulesReload] runs this hourly): only the rules that changed, so an
     * unchanged list writes nothing and nothing watching the table hears from it. Never a weaker
     * copy: a rule whose sealed target or threshold doesn't open here keeps the row this phone
     * has. From a whole list, the rules the server no longer lists go, except one with a change
     * waiting to sync; from a partial one, nothing goes. All in one transaction, and nothing at
     * all when the account isn't the one the refresh began for (a sign-out, another sign-in).
     */
    suspend fun refreshAlertRules(): Result<List<AlertRuleEntity>> = refreshRules().map { it.rules }

    private class RefreshedRules(val ids: Set<String>, val rules: List<AlertRuleEntity>, val whole: Boolean)

    private suspend fun refreshRules(): Result<RefreshedRules> {
        val account = sessionManager.getUserId() ?: return Result.failure(IllegalStateException("Not signed in"))
        return try {
            val response = tracksApi.getAlertRules()
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                val dtos = response.body()!!.data!!.items.toMutableList()
                // By id: a scan the server restarts from the first page repeats rules.
                val seen = dtos.mapTo(mutableSetOf()) { it.id }
                var page = response.body()!!.data!!
                var whole = true
                var pages = 1
                while (page.hasMore) {
                    val cursor = page.nextCursor
                    if (cursor == null || ++pages > MAX_RULE_PAGES) {
                        whole = false
                        break
                    }
                    val next = runCatching { tracksApi.getAlertRules(cursor = cursor) }.getOrNull()
                    val body = next?.body()?.takeIf { next.isSuccessful && it.success }?.data
                    if (body == null) {
                        whole = false
                        break
                    }
                    dtos += body.items.filter { seen.add(it.id) }
                    page = body
                }
                // Per-rule decrypt is pure CPU (UniFFI); move it off the Main
                // thread so a large rule set can't jank the caller.
                val rules = withContext(Dispatchers.Default) { dtos.mapNotNull { readRule(it) } }
                val listed = dtos.mapTo(mutableSetOf()) { it.id }
                val here = alertRuleDao.getAll().associateBy { it.id }
                val changed = rules.filter { here[it.id] != it }
                // A rule with its threshold change waiting to sync stays until that change
                // runs: the run is what finds the rule gone, and says so.
                val gone = if (whole) {
                    val waiting = pendingChanges.waitingTargets(PendingKind.ALERT_CHANGE)
                    here.keys.filter { it !in listed && it !in waiting }
                } else {
                    emptyList()
                }
                if (!alertRuleDao.applyRefresh(changed, gone) { AccountIds.same(sessionManager.getUserId(), account) }) {
                    return Result.failure(IllegalStateException("Signed out during the refresh"))
                }
                Result.success(RefreshedRules(listed, rules, whole))
            } else {
                Result.failure(Exception("Failed to fetch alert rules"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * A listed rule as this phone reads it, or null when a sealed part of it doesn't open here
     * (its target, or its threshold): the row this phone has is kept, never written over with a
     * rule about nobody or with no threshold, which the evaluator would skip.
     */
    private fun readRule(dto: AlertRuleResponse): AlertRuleEntity? {
        // For rules without a circle, use a personal key or skip E2EE decryption
        val circleId = dto.circleId
        // E2EE: Decrypt target user ID if we have a circle. For personal rules without a
        // circle there is nothing to decrypt it with.
        val decryptedTargetUserId = circleId?.let { decryptString(it, dto.encryptedTargetUserId) ?: return null }
        // Kept on the device because the device is what compares it: a silent wake-up arrives
        // with nothing in it, and the evaluation has to work from local state. Sealed copy
        // first; the plaintext column is only the fallback for older rules, whose threshold
        // was not sealed under the circle key (new rules carry no content there).
        val sealed = dto.encryptedConditions?.let { encrypted -> circleId?.let { decryptString(it, encrypted) ?: return null } }
        return AlertRuleEntity(
            id = dto.id,
            userId = dto.userId,
            circleId = circleId,
            targetUserId = decryptedTargetUserId ?: "",
            ruleType = dto.ruleType,
            isActive = dto.isActive,
            notifyPush = dto.notifyPush,
            notifyEmail = dto.notifyEmail,
            notifySms = dto.notifySms,
            cooldownMinutes = dto.cooldownMinutes,
            batteryThreshold = sealed?.let { thresholdIn(it) } ?: (dto.conditions?.get("threshold") as? Number)?.toInt(),
            dayBucket = dto.dayBucket
        )
    }

    /**
     * Get alert events.
     */
    suspend fun getAlertEvents(ruleId: String? = null): Result<List<AlertEventResponse>> {
        return try {
            val response = tracksApi.getAlertEvents(ruleId = ruleId)
            if (response.isSuccessful && response.body()?.success == true && response.body()?.data != null) {
                Result.success(response.body()!!.data!!.items)
            } else {
                Result.failure(Exception("Failed to fetch alert events"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
