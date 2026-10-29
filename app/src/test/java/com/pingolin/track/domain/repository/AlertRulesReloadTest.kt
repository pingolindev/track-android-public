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
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.testutil.pendingChangesIn
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File

/**
 * Low-battery and inactivity alerts are raised from the rule rows this phone holds, so those rows
 * must not wait for the Alerts screen: after a sign-in, a new install or a database made again
 * they would be empty, and a rule made on another device would never arrive. As on iOS: a list
 * not read in this process for this sign-in is stale, and so is one an hour old; the background
 * pass reloads a stale list, the alerts_check wake-up reloads at once, a return to the app
 * reloads one older than five minutes, and a list counts as read only when every rule in it was
 * read here.
 */
class AlertRulesReloadTest {

    private var account: String? = "me"
    private var clock = 1_700_000_000_000L
    private val session = mockk<SessionManager> { every { getUserId() } answers { account } }
    private val rules = mockk<AlertRuleRepository>()
    private val inactivity = mockk<InactivityAlertRepository>()
    private val reload = AlertRulesReload(rules, inactivity, session).also { it.now = { clock } }

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun rule(id: String) = AlertRuleEntity(id = id, userId = "me", circleId = "c-1", targetUserId = "sam", ruleType = "low_battery", dayBucket = 0)

    /** Both lists came whole; [unreadRules]: that many rules listed but not read here (a key not in yet). */
    private fun lists(unreadRules: Int = 0) {
        val read = listOf(rule("r-1"))
        val ids = (read.map { it.id } + (1..unreadRules).map { "r-unread-$it" }).toSet()
        coEvery { rules.refreshAlertRulesWhole() } returns AlertRuleRepository.WholeRules(ids, read)
        coEvery { inactivity.refreshInactivityAlertsWhole() } returns InactivityAlertRepository.WholeAlerts(emptySet(), emptyList())
    }

    @Before
    fun setUp() = lists()

    private fun fetched(times: Int) {
        coVerify(exactly = times) { rules.refreshAlertRulesWhole() }
        coVerify(exactly = times) { inactivity.refreshInactivityAlertsWhole() }
    }

    @Test
    fun `never read in this process is stale - both lists come at the first pass`() = runTest {
        reload.reloadIfStale()
        fetched(1)
    }

    @Test
    fun `within the hour nothing is fetched again - after it, both are`() = runTest {
        // An hour, as iOS's refreshIfStale(maxAge: 3600): in the clock's own units, not the constant's.
        reload.reloadIfStale()
        clock += hour - 1
        reload.reloadIfStale()
        fetched(1)
        clock += 1
        reload.reloadIfStale()
        fetched(2)
    }

    @Test
    fun `the wake-up fetches at once`() = runTest {
        reload.reloadIfStale()
        reload.reloadIfStale(force = true)
        fetched(2)
    }

    @Test
    fun `a return to the app fetches a list five minutes old, and nothing sooner`() = runTest {
        reload.reloadIfStale()
        // A turn of the screen, a dialog closing: nothing.
        clock += 5 * minute - 1
        reload.reloadIfStale(maxAgeMs = AlertRulesReload.RETURN_MAX_AGE_MS)
        fetched(1)
        clock += 1
        reload.reloadIfStale(maxAgeMs = AlertRulesReload.RETURN_MAX_AGE_MS)
        fetched(2)
    }

    @Test
    fun `a failed or cut-short fetch keeps what is here and is tried again at the next pass`() = runTest {
        coEvery { rules.refreshAlertRulesWhole() } returns null
        coEvery { inactivity.refreshInactivityAlertsWhole() } returns null
        reload.reloadIfStale()
        reload.reloadIfStale()
        fetched(2)
        // Each list on its own: the one that came is fresh, the other is tried again.
        coEvery { inactivity.refreshInactivityAlertsWhole() } returns InactivityAlertRepository.WholeAlerts(emptySet(), emptyList())
        reload.reloadIfStale()
        reload.reloadIfStale()
        coVerify(exactly = 4) { rules.refreshAlertRulesWhole() }
        coVerify(exactly = 3) { inactivity.refreshInactivityAlertsWhole() }
    }

    @Test
    fun `a list with a rule not read here yet is read again in five minutes, then less often, up to the hour`() = runTest {
        // Right after a sign-in: the circle's key isn't in, so its rule can't be read yet.
        lists(unreadRules = 1)
        reload.reloadIfStale()
        coVerify(exactly = 1) { rules.refreshAlertRulesWhole() }
        // 5, 10, 20, 40 minutes, then the hour.
        var reads = 1
        for (wait in listOf(5, 10, 20, 40, 60, 60)) {
            clock += wait * minute - 1
            reload.reloadIfStale()
            coVerify(exactly = reads) { rules.refreshAlertRulesWhole() }
            clock += 1
            reload.reloadIfStale()
            coVerify(exactly = ++reads) { rules.refreshAlertRulesWhole() }
        }
        // Once every rule is read, the hour again.
        lists()
        clock += hour
        reload.reloadIfStale()
        clock += hour - 1
        reload.reloadIfStale()
        coVerify(exactly = reads + 1) { rules.refreshAlertRulesWhole() }
    }

    @Test
    fun `another account's lists are stale - signed out, nothing is fetched`() = runTest {
        reload.reloadIfStale()
        account = "you"
        reload.reloadIfStale()
        fetched(2)
        account = null
        reload.reloadIfStale(force = true)
        fetched(2)
    }

    @Test
    fun `after a sign-out the next sign-in reads both at once, this account's own too`() = runTest {
        reload.reloadIfStale()
        reload.forget()
        reload.reloadIfStale()
        fetched(2)
    }

    @Test
    fun `a read under way at a sign-out counts for nothing`() = runTest {
        coEvery { rules.refreshAlertRulesWhole() } coAnswers {
            reload.forget()
            AlertRuleRepository.WholeRules(setOf("r-1"), listOf(rule("r-1")))
        }
        reload.reloadIfStale()
        reload.reloadIfStale()
        coVerify(exactly = 2) { rules.refreshAlertRulesWhole() }
    }

    @Test
    fun `a failed reload keeps the rules and alerts already here`() = runTest {
        // The real repositories, over strict DAOs: any read or write of them would throw.
        val api = mockk<TrackApi>()
        coEvery { api.getAlertRules(any(), any()) } returns Response.error(503, "".toResponseBody(null))
        coEvery { api.getInactivityAlerts(any(), any(), any(), any()) } returns Response.error(503, "".toResponseBody(null))
        val ruleDao = mockk<AlertRuleDao>()
        val inactivityDao = mockk<InactivityAlertDao>()
        val changes = pendingChangesIn(InMemoryPendingChangeDao())
        val real = AlertRulesReload(
            AlertRuleRepository(api, ruleDao, session, mockk(relaxed = true), mockk(relaxed = true), changes),
            InactivityAlertRepository(api, inactivityDao, mockk(relaxed = true), mockk(relaxed = true), session, mockk(relaxed = true), mockk(relaxed = true), changes),
            session,
        )
        real.reloadIfStale(force = true)
        coVerify(exactly = 0) { ruleDao.applyRefresh(any(), any(), any()) }
        coVerify(exactly = 0) { ruleDao.insertAll(any()) }
        coVerify(exactly = 0) { ruleDao.deleteById(any()) }
        coVerify(exactly = 0) { inactivityDao.applyRefresh(any(), any(), any()) }
        coVerify(exactly = 0) { inactivityDao.insertAll(any()) }
        coVerify(exactly = 0) { inactivityDao.deleteById(any()) }
    }

    @Test
    fun `the background pass, a return to the app and a sign-out call it, the pass before reading which circles to judge`() {
        fun src(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
        val pass = src("service/LocationTrackingService.kt").substringAfter("private suspend fun runBackgroundEvaluationPass()")
        assertTrue(pass.indexOf("alertRulesReload.reloadIfStale()") in 0 until pass.indexOf("monitoringManager.inactivityCircleIds()"))
        assertTrue(pass.indexOf("alertRulesReload.reloadIfStale()") < pass.indexOf("monitoringManager.batteryAlertCircleIds()"))
        val onResume = src("ui/main/MainActivity.kt").substringAfter("override fun onResume()").substringBefore("override fun ")
        assertTrue(onResume.contains("alertRulesReload.reloadIfStale(maxAgeMs = com.pingolin.track.domain.repository.AlertRulesReload.RETURN_MAX_AGE_MS)"))
        assertTrue("never forced on a return", !onResume.contains("alertRulesReload.reloadIfStale(force = true)"))
        val signOut = src("domain/repository/AuthRepository.kt").substringAfter("suspend fun clearLocalData()")
        assertTrue(signOut.substringBefore("database.clearAllTables()").contains("alertRulesReload.get().forget()"))
        // The wake-up's order is AlertsCheckWorkerTest's.
    }
}
