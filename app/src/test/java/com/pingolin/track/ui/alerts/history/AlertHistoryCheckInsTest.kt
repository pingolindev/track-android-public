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

package com.pingolin.track.ui.alerts.history

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Alerts > History reads the past check-ins of the people this account's check-in rules are about
 * when it opens (AlertEventsViewModel): every circle such a rule watches someone in, 30 days back,
 * keeping only the watched people's; a read that fails keeps the rest and says the check-ins
 * couldn't be loaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertHistoryCheckInsTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private companion object {
        const val ME = "5e1f0000-0000-4000-8000-00000000000e"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-00000000b0b0"
        const val CARA = "ca7a0000-0000-4000-8000-0000000ca7a0"
        const val C1 = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val C2 = "c2c2c2c2-0000-4000-8000-0000000000c2"
        const val HOUR = 3_600_000L
    }

    private val dispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()

    private val alertRules = mockk<AlertRuleRepository>()
    private val inactivity = mockk<InactivityAlertRepository>()
    private val circles = mockk<CircleRepository>()
    private val checkIns = mockk<CheckInRepository>()
    private val session = mockk<SessionManager>()

    private var rules = listOf<InactivityAlertEntity>()
    private var requests: Result<List<CircleSelfAlertDto>> = Result.success(emptyList())
    /** Each circle's read: its check-ins as the server has them, or none for a failed read. */
    private val onServer = mutableMapOf<String, List<CheckInEntity>?>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        every { session.getUserId() } returns ME
        coEvery { alertRules.getAlertEvents(any()) } returns Result.success(emptyList())
        every { alertRules.observeAll() } returns flowOf(emptyList())
        every { inactivity.observeAll() } answers { flowOf(rules) }
        every { inactivity.observeLocalEvents() } returns flowOf(emptyList())
        coEvery { inactivity.getInactivityEvents(any()) } returns Result.success(emptyList())
        coEvery { inactivity.circleCheckInRequests() } answers { requests }
        every { circles.observeCircles() } returns flowOf(listOf(circle(C1, "Family"), circle(C2, "Work")))
        every { circles.observeMembers(C1) } returns flowOf(listOf(member(C1, ALICE, "Alice"), member(C1, BOB, "Bob"), member(C1, CARA, "Cara")))
        every { circles.observeMembers(C2) } returns flowOf(listOf(member(C2, ME, "Me")))
        coEvery { checkIns.refreshRecentCheckIns(any(), any(), any()) } answers {
            onServer[firstArg()]?.let { Result.success(CheckInRepository.RecentCheckIns(it, unopened = 0)) }
                ?: Result.failure(java.io.IOException("offline"))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun circle(id: String, name: String) = CircleEntity(id = id, name = name, encryptedKey = null, createdAt = 0, updatedAt = 0)
    private fun member(circle: String, user: String, name: String) =
        CircleMemberEntity(circleId = circle, userId = user, displayName = name, role = "member", joinedAt = 0L)

    private fun rule(id: String, circle: String, target: String, self: Boolean = false) = InactivityAlertEntity(
        id = id, userId = ME, circleId = circle, targetUserId = target, timeoutHours = 4, isSelfAlert = self,
        isActive = false, dayBucket = 0, watches = InactivityWatches.CHECK_IN, armedAt = 0L,
    )

    private fun checkIn(id: String, circle: String, user: String, hoursAgo: Long, type: String = "general") = CheckInEntity(
        id = id, circleId = circle, userId = user, type = type, message = null, latitude = null, longitude = null,
        placeId = null, timestamp = now - hoursAgo * HOUR, isSynced = true,
    )

    private fun TestScope.opened(): AlertEventsViewModel {
        val history = AlertEventsViewModel(alertRules, inactivity, circles, checkIns, session)
        backgroundScope.launch(dispatcher) { history.checkInItems.collect {} }
        backgroundScope.launch(dispatcher) { history.checkInsFailed.collect {} }
        advanceUntilIdle()
        return history
    }

    @Test
    fun `the watched people's check-ins, named and in their circles, and nobody else's`() = runTest {
        rules = listOf(rule("r-1", C1, ALICE), rule("r-2", C2, ME, self = true))
        requests = Result.success(listOf(CircleSelfAlertDto(id = "ask", userId = CARA, circleId = C1, timeoutHours = 4, watches = "check_in")))
        onServer[C1] = listOf(
            checkIn("k-1", C1, ALICE, 1, type = "arrived"),
            checkIn("k-2", C1, BOB, 2),
            checkIn("k-3", C1, CARA, 3),
        )
        onServer[C2] = listOf(checkIn("k-4", C2, ME, 4))

        val history = opened()

        val rows = history.checkInItems.value.associateBy { it.id }
        assertEquals(setOf("check-in:k-1", "check-in:k-3", "check-in:k-4"), rows.keys)
        assertEquals("Alice", rows.getValue("check-in:k-1").memberName)
        assertEquals("Family", rows.getValue("check-in:k-1").circleName)
        assertEquals("arrived", rows.getValue("check-in:k-1").checkInType)
        assertEquals("Cara", rows.getValue("check-in:k-3").memberName)
        assertTrue(rows.getValue("check-in:k-4").ownCheckIn)
        assertEquals("Work", rows.getValue("check-in:k-4").circleName)
        assertFalse(history.checkInsFailed.value)
        coVerify { checkIns.refreshRecentCheckIns(C1, any(), HistoryCheckIns.WINDOW_MS) }
        coVerify { checkIns.refreshRecentCheckIns(C2, any(), HistoryCheckIns.WINDOW_MS) }
    }

    @Test
    fun `a failed read of the circle-mates' requests keeps the rest, and says the check-ins couldn't be loaded`() = runTest {
        rules = listOf(rule("r-1", C1, ALICE))
        requests = Result.failure(java.io.IOException("offline"))
        onServer[C1] = listOf(checkIn("k-1", C1, ALICE, 1), checkIn("k-3", C1, CARA, 3))

        val history = opened()

        assertEquals(listOf("check-in:k-1"), history.checkInItems.value.map { it.id })
        assertTrue(history.checkInsFailed.value)
    }

    @Test
    fun `a circle whose read fails says so, and the other circle's still show`() = runTest {
        rules = listOf(rule("r-1", C1, ALICE), rule("r-2", C2, ME, self = true))
        onServer[C1] = null
        onServer[C2] = listOf(checkIn("k-4", C2, ME, 4))

        val history = opened()

        assertEquals(listOf("check-in:k-4"), history.checkInItems.value.map { it.id })
        assertTrue(history.checkInsFailed.value)
    }

    @Test
    fun `with no check-in rule, nothing is read and nothing failed`() = runTest {
        val history = opened()

        assertEquals(emptyList<AlertEventItem>(), history.checkInItems.value)
        assertFalse(history.checkInsFailed.value)
        coVerify(exactly = 0) { checkIns.refreshRecentCheckIns(any(), any(), any()) }
    }

    @Test
    fun `a session store that can't be opened keeps the alerts, says the check-ins couldn't be loaded, and breaks nothing`() = runTest {
        every { session.getUserId() } throws IllegalStateException("the store refused")

        val history = opened()

        assertEquals(emptyList<AlertEventItem>(), history.checkInItems.value)
        assertTrue(history.checkInsFailed.value)
    }

    @Test
    fun `a refresh reads again`() = runTest {
        rules = listOf(rule("r-1", C1, ALICE))
        onServer[C1] = listOf(checkIn("k-1", C1, ALICE, 1))
        val history = opened()
        onServer[C1] = listOf(checkIn("k-1", C1, ALICE, 1), checkIn("k-5", C1, ALICE, 0))

        history.refreshEvents()
        advanceUntilIdle()

        assertEquals(setOf("check-in:k-1", "check-in:k-5"), history.checkInItems.value.map { it.id }.toSet())
    }
}
