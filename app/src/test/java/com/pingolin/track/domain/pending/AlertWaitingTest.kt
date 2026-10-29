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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.dao.AlertRuleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CreateAlertRuleRequest
import com.pingolin.track.data.remote.dto.CreateInactivityAlertRequest
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.CurrentKeyGate
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.domain.repository.KeyNotCurrentException
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * Alerts set up or changed while this phone is behind. On Robolectric: the alert's sealed
 * conditions are built with org.json, which is a stub on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class AlertWaitingTest {

    private val tracksApi = mockk<TrackApi>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true) {
        every { encryptData(any(), any()) } returns EncryptedData("n", "c")
        every { deriveUserPseudonym(any(), any()) } returns "p"
    }
    private var isBehind = true
    private val gate = mockk<CurrentKeyGate> {
        coEvery { requireCurrentKey("c-1") } answers { if (isBehind) throw KeyNotCurrentException() }
    }
    private val sessionManager = mockk<SessionManager>(relaxed = true) { every { getUserId() } returns "me" }
    private val dao = InMemoryPendingChangeDao()
    private val alertRuleDao = mockk<AlertRuleDao>(relaxed = true) {
        // A refresh writes through this one transaction, as the DAO does it. Its account check
        // compares UUIDs ("me" is none here) and is AlertListsRefreshTest's.
        coEvery { applyRefresh(any(), any(), any()) } coAnswers {
            firstArg<List<AlertRuleEntity>>().takeIf { it.isNotEmpty() }?.let { insertAll(it) }
            secondArg<List<String>>().forEach { deleteById(it) }
            true
        }
    }
    private val rules = AlertRuleRepository(tracksApi, alertRuleDao, sessionManager, cryptoManager, gate, pendingChangesIn(dao))
    private val members = mockk<CircleMemberDao>(relaxed = true)
    private val inactivity = InactivityAlertRepository(tracksApi, mockk(relaxed = true), mockk(relaxed = true), members, sessionManager, cryptoManager, gate, pendingChangesIn(dao))

    @Test
    fun `a battery alert made while behind is kept, sealing nothing, and goes out with its own request id`() = runTest {
        val result = rules.createLowBatteryAlert("c-1", "sam", 15, notifyPush = true, notifyEmail = false, keepIfBehind = true)

        assertEquals(PendingLines.NEW_ALERT, result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptData(any(), any()) }
        coVerify(exactly = 0) { tracksApi.createAlertRule(any()) }
        val kept = dao.changes.value.single()
        assertEquals(PendingInputs.BatteryAlert("Family", "sam", "Sam", 15, true, false), PendingInputs.read(kept))

        isBehind = false
        val request = io.mockk.slot<CreateAlertRuleRequest>()
        coEvery { tracksApi.createAlertRule(capture(request)) } returns Response.success(ApiResponse(true, "rule-1", null))
        rules.createLowBatteryAlert("c-1", "sam", 15, true, false, pendingRun = PendingRun(kept.id, kept.id))

        assertEquals(kept.id, request.captured.clientRequestId)
    }

    @Test
    fun `a new threshold made while behind is kept with the choices sent with it`() = runTest {
        coEvery { alertRuleDao.getById("rule-1") } returns AlertRuleEntity(id = "rule-1", userId = "me", circleId = "c-1", targetUserId = "sam", ruleType = "low_battery", dayBucket = 0)

        val result = rules.updateAlertRule("rule-1", notifyPush = true, notifyEmail = true, conditions = mapOf("threshold" to 30), keepIfBehind = true)

        assertEquals(PendingLines.ALERT_CHANGE, result.exceptionOrNull()?.message)
        assertEquals(PendingInputs.AlertChange("Family", "rule-1", 30, true, true), PendingInputs.read(dao.changes.value.single()))
        coVerify(exactly = 0) { tracksApi.updateAlertRule(any(), any()) }
    }

    @Test
    fun `a threshold change for a rule deleted meanwhile is dropped as deleted`() = runTest {
        coEvery { alertRuleDao.getById("rule-1") } returns AlertRuleEntity(id = "rule-1", userId = "me", circleId = "c-1", targetUserId = "sam", ruleType = "low_battery", dayBucket = 0)
        rules.updateAlertRule("rule-1", conditions = mapOf("threshold" to 30), keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The server's whole list has other rules, not this one.
        val repository = mockk<AlertRuleRepository> {
            coEvery { refreshAlertRulesWhole() } returns AlertRuleRepository.WholeRules(setOf("rule-2"), emptyList())
        }
        val executors = PendingExecutors(mockk(), mockk(), mockk(), { repository }, mockk(), mockk(), mockk(), mockk(), sessionManager, mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.ALERT_CHANGE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("Your change to this alert wasn't done: the alert was deleted."), outcome)
    }

    private fun rule(threshold: Int?, push: Boolean = true, email: Boolean = false) = AlertRuleEntity(
        id = "rule-1", userId = "me", circleId = "c-1", targetUserId = "sam", ruleType = "low_battery", dayBucket = 0,
        batteryThreshold = threshold, notifyPush = push, notifyEmail = email,
    )

    /**
     * A threshold change from 20 to 30 made while behind, run against the rule as it is [now];
     * [unseen]: kept with no record of the rule as shown, as an earlier version kept it.
     */
    private suspend fun runThresholdChange(now: AlertRuleEntity, unseen: Boolean = false): Pair<PendingOutcome, AlertRuleRepository> {
        coEvery { alertRuleDao.getById("rule-1") } returns rule(20)
        rules.updateAlertRule("rule-1", notifyPush = true, notifyEmail = false, conditions = mapOf("threshold" to 30), keepIfBehind = true)
        val kept = dao.changes.value.single().let { if (unseen) it.copy(seen = null) else it }
        val repository = mockk<AlertRuleRepository>(relaxed = true) {
            coEvery { refreshAlertRulesWhole() } returns AlertRuleRepository.WholeRules(setOf("rule-1"), listOf(now))
            coEvery { updateAlertRule(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns Result.success(Unit)
        }
        val executors = PendingExecutors(mockk(), mockk(), mockk(), { repository }, mockk(), mockk(), mockk(), mockk(), sessionManager, mockk(relaxed = true))
        return executors.forKind(PendingKind.ALERT_CHANGE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id)) to repository
    }

    @Test
    fun `a threshold change goes out over the rule as the person saw it`() = runTest {
        val (outcome, repository) = runThresholdChange(rule(20))

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 1) { repository.updateAlertRule("rule-1", any(), true, false, any(), any(), mapOf("threshold" to 30), any(), any()) }
    }

    @Test
    fun `a rule already as the change makes it is done, and nothing is sent`() = runTest {
        val (outcome, repository) = runThresholdChange(rule(30))

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 0) { repository.updateAlertRule(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a rule changed on another device meanwhile is never overwritten`() = runTest {
        val (outcome, repository) = runThresholdChange(rule(25))

        assertEquals(PendingOutcome.Dropped("Your change to this alert wasn't saved: it was changed on another device in the meantime."), outcome)
        coVerify(exactly = 0) { repository.updateAlertRule(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a change kept with no record of the rule as shown never overwrites it - done if already so, else not done`() = runTest {
        val (outcome, repository) = runThresholdChange(rule(20), unseen = true)
        assertEquals(PendingOutcome.Dropped("Your change to this alert wasn't done."), outcome)
        coVerify(exactly = 0) { repository.updateAlertRule(any(), any(), any(), any(), any(), any(), any(), any(), any()) }

        dao.changes.value.forEach { dao.end(it.id, null) }
        val (already, _) = runThresholdChange(rule(30), unseen = true)
        assertEquals(PendingOutcome.Done(), already)
    }

    @Test
    fun `the change is kept with the rule as it was shown`() = runTest {
        coEvery { alertRuleDao.getById("rule-1") } returns rule(20)

        rules.updateAlertRule("rule-1", conditions = mapOf("threshold" to 30), keepIfBehind = true)

        assertEquals(
            PendingInputs.AlertChange("Family", "rule-1", 20, true, false),
            com.google.gson.Gson().fromJson(dao.changes.value.single().seen, PendingInputs.AlertChange::class.java),
        )
    }

    private fun ruleDto(id: String) = com.pingolin.track.data.remote.dto.AlertRuleResponse(
        id = id, userId = "me", circleId = null, ruleType = "low_battery", isActive = true, notifyPush = true,
        notifyEmail = false, notifySms = false, cooldownMinutes = null, encryptedConditions = null,
        encryptedTargetUserId = com.pingolin.track.data.remote.dto.EncryptedPayload("n", "c"), dayBucket = 0,
    )

    private fun rulePage(ids: List<String>, next: String?) = Response.success(
        ApiResponse(true, com.pingolin.track.data.remote.dto.AlertRulesListResponse(ids.map { ruleDto(it) }, next, hasMore = next != null), null)
    )

    @Test
    fun `the rule list is read to its last page, and is whole only then`() = runTest {
        coEvery { tracksApi.getAlertRules(null, any()) } returns rulePage(listOf("r-1", "r-2"), next = "page-2")
        coEvery { tracksApi.getAlertRules("page-2", any()) } returns rulePage(listOf("r-3"), next = null)

        assertEquals(setOf("r-1", "r-2", "r-3"), rules.refreshAlertRulesWhole()!!.ids)

        // A later page that can't be had: the list isn't whole.
        coEvery { tracksApi.getAlertRules("page-2", any()) } returns
            Response.error(503, "{}".toResponseBody(null))
        assertEquals(null, rules.refreshAlertRulesWhole())
    }

    @Test
    fun `a rule scan the server restarts from the first page is merged by id`() = runTest {
        coEvery { tracksApi.getAlertRules(null, any()) } returns rulePage(listOf("r-1", "r-2"), next = "page-2")
        // An old cursor: the server starts over, repeating the first page.
        coEvery { tracksApi.getAlertRules("page-2", any()) } returns rulePage(listOf("r-1", "r-2", "r-3"), next = null)
        val stored = io.mockk.slot<List<AlertRuleEntity>>()
        coEvery { alertRuleDao.insertAll(capture(stored)) } returns Unit

        assertEquals(setOf("r-1", "r-2", "r-3"), rules.refreshAlertRulesWhole()!!.ids)
        assertEquals(listOf("r-1", "r-2", "r-3"), stored.captured.map { it.id })
    }

    @Test
    fun `a rule list that couldn't be had whole says nothing - the change waits`() = runTest {
        coEvery { alertRuleDao.getById("rule-1") } returns AlertRuleEntity(id = "rule-1", userId = "me", circleId = "c-1", targetUserId = "sam", ruleType = "low_battery", dayBucket = 0)
        rules.updateAlertRule("rule-1", conditions = mapOf("threshold" to 30), keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The refresh failed, or the list had another page.
        val repository = mockk<AlertRuleRepository>(relaxed = true) { coEvery { refreshAlertRulesWhole() } returns null }
        val executors = PendingExecutors(mockk(), mockk(), mockk(), { repository }, mockk(), mockk(), mockk(), mockk(), sessionManager, mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.ALERT_CHANGE)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Transient(maybeSent = false), outcome)
        coVerify(exactly = 0) { repository.updateAlertRule(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an inactivity alert made while behind goes out one row per member, each with its own request id, the same on every run`() = runTest {
        val result = inactivity.createInactivityAlert("c-1", "sam", 120, watchedUserIds = listOf("sam", "kim"), keepIfBehind = true)
        assertEquals(PendingLines.NEW_ALERT, result.exceptionOrNull()?.message)
        verify(exactly = 0) { cryptoManager.encryptData(any(), any()) }
        val kept = dao.changes.value.single()
        isBehind = false
        val sent = mutableListOf<String?>()
        coEvery { tracksApi.createInactivityAlert(any()) } answers {
            sent += firstArg<CreateInactivityAlertRequest>().clientRequestId
            Response.success(ApiResponse(true, "a-${sent.size}", null))
        }
        val run = PendingRun(kept.id, kept.id)

        inactivity.createInactivityAlert("c-1", "sam", 120, watchedUserIds = listOf("sam", "kim"), pendingRun = run)
        inactivity.createInactivityAlert("c-1", "sam", 120, watchedUserIds = listOf("sam", "kim"), pendingRun = run)

        val ids = listOf(InactivityAlertRepository.rowRequestId(kept.id, "sam"), InactivityAlertRepository.rowRequestId(kept.id, "kim"))
        assertEquals(ids + ids, sent)
        assertTrue(ids.distinct().size == 2)
    }

    @Test
    fun `an inactivity alert about members who all left is dropped as no longer in the circle`() = runTest {
        coEvery { members.get("c-1", "sam") } returns CircleMemberEntity("c-1", "sam", "Sam", "member", joinedAt = 0)
        inactivity.createInactivityAlert("c-1", "sam", 120, watchedUserIds = listOf("sam"), keepIfBehind = true)
        val kept = dao.changes.value.single()
        // The server's whole roster, every page, shown here or not.
        val circles = mockk<CircleRepository> { coEvery { memberIdsWhole("c-1") } returns setOf("me") }
        val executors = PendingExecutors(mockk(), mockk(), mockk(), mockk(), mockk(), { circles }, mockk(), mockk(), sessionManager, mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.INACTIVITY_ALERT)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Dropped("This alert wasn't done: Sam is no longer in Family."), outcome)
    }

    @Test
    fun `a member this phone doesn't show is still in the circle - the alert goes out`() = runTest {
        rules.createLowBatteryAlert("c-1", "sam", 15, notifyPush = true, notifyEmail = false, keepIfBehind = true)
        val kept = dao.changes.value.single()
        // Sam is on the server's whole roster, though this phone keeps them hidden (not on the
        // shown roster, which the re-check never reads).
        val circles = mockk<CircleRepository> {
            coEvery { memberIdsWhole("c-1") } returns setOf("me", "sam")
            coEvery { refreshMembers("c-1") } returns Result.success(listOf(CircleMemberEntity("c-1", "me", "Me", "owner", joinedAt = 0)))
        }
        val repository = mockk<AlertRuleRepository> {
            coEvery { refreshAlertRules() } returns Result.success(emptyList())
            coEvery { createLowBatteryAlert(any(), any(), any(), any(), any(), any(), any()) } returns Result.success("rule-1")
        }
        val executors = PendingExecutors(mockk(), mockk(), mockk(), { repository }, mockk(), { circles }, mockk(), mockk(), sessionManager, mockk(relaxed = true))
        val run = PendingRun(kept.id, kept.id)

        val outcome = executors.forKind(PendingKind.BATTERY_ALERT)!!.run(kept, PendingInputs.read(kept)!!, run)

        assertEquals(PendingOutcome.Done(), outcome)
        coVerify(exactly = 1) { repository.createLowBatteryAlert("c-1", "sam", 15, true, false, false, run) }
    }

    @Test
    fun `a roster that never loaded says nothing - the change waits`() = runTest {
        rules.createLowBatteryAlert("c-1", "sam", 15, notifyPush = true, notifyEmail = false, keepIfBehind = true)
        val kept = dao.changes.value.single()
        val circles = mockk<CircleRepository> { coEvery { memberIdsWhole("c-1") } returns null }
        val repository = mockk<AlertRuleRepository>(relaxed = true)
        val executors = PendingExecutors(mockk(), mockk(), mockk(), { repository }, mockk(), { circles }, mockk(), mockk(), sessionManager, mockk(relaxed = true))

        val outcome = executors.forKind(PendingKind.BATTERY_ALERT)!!.run(kept, PendingInputs.read(kept)!!, PendingRun(kept.id, kept.id))

        assertEquals(PendingOutcome.Transient(maybeSent = false), outcome)
        coVerify(exactly = 0) { repository.createLowBatteryAlert(any(), any(), any(), any(), any(), any(), any()) }
    }
}
