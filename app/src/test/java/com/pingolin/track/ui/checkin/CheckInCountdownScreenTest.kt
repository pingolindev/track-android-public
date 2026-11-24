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

package com.pingolin.track.ui.checkin

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.ui.alerts.inactivity.InactivityAlertsViewModel
import com.pingolin.track.util.CheckInCountdown.Countdown
import com.pingolin.track.util.CheckInModes
import com.pingolin.track.util.CheckInSchedule
import com.pingolin.track.util.InactivityWatches
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Where the countdown on a check-in rule about yourself shows: on the check-in screen, for the
 * circle being checked in to (CheckInViewModel.countdown), and in the rule's row in Alerts
 * (InactivityAlertsViewModel.selfCheckInCountdowns). Your newest check-in is read from the server
 * when the screen opens; a failed read shows nothing. A one-time rule ends at an accepted check-in
 * after its start (no line), and one still waiting on this phone doesn't end it. A scheduled rule's
 * read starts at its latest occurrence's window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckInCountdownScreenTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private companion object {
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val OTHER = "c2c2c2c2-0000-4000-8000-0000000000c2"
        const val HOUR = 3_600_000L
    }

    private val dispatcher = StandardTestDispatcher()
    private val now = System.currentTimeMillis()
    private val armed = now - 10 * HOUR

    private val checkIns = mockk<CheckInRepository>()
    private val circles = mockk<CircleRepository>()
    private val alerts = mockk<InactivityAlertRepository>()
    private val rules = MutableStateFlow(listOf<InactivityAlertEntity>())

    /** How a read of this account's newest check-in answers, in order. */
    private val answers = ArrayDeque<Result<Long?>>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        every { circles.observeCircles() } returns flowOf(listOf(CircleEntity(id = CIRCLE, name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)))
        every { circles.observeMembers(any()) } returns flowOf(emptyList())
        coEvery { circles.refreshMembers(any()) } returns Result.success(emptyList())
        every { checkIns.observeCheckInsByCircle(any()) } returns flowOf(emptyList())
        coEvery { checkIns.refreshCheckIns(any()) } returns Result.success(emptyList())
        coEvery { checkIns.newestOwnCheckIn(any(), any(), any()) } answers { answers.removeFirstOrNull() ?: Result.failure(IllegalStateException("no answer")) }
        every { alerts.observeActive() } answers { rules }
        every { alerts.observeAll() } answers { rules }
        coEvery { alerts.refreshInactivityAlerts() } returns Result.success(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun selfCheckInRule(id: String = "r-1", circle: String = CIRCLE, hours: Int = 12, self: Boolean = true, watches: String = InactivityWatches.CHECK_IN) =
        InactivityAlertEntity(
            id = id, userId = "me", circleId = circle, targetUserId = "me", timeoutHours = hours, isSelfAlert = self,
            dayBucket = 0, watches = watches, armedAt = armed, checkInMode = CheckInModes.ONCE,
        )

    private fun checkInScreen() = CheckInViewModel(
        checkIns, circles, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        com.pingolin.track.testutil.quietPendingChanges(), alerts, androidx.lifecycle.SavedStateHandle(),
    )

    private fun accepted(at: Long) = CheckInEntity(
        id = "k-new", circleId = CIRCLE, userId = "me", type = "general", message = null, latitude = null, longitude = null,
        placeId = null, timestamp = at, isSynced = true,
    )

    // ── the check-in screen ──────────────────────────────────────────────────

    @Test
    fun `the circle being checked in to shows when its circle is alerted, read from the rule's start`() = runTest {
        rules.value = listOf(selfCheckInRule(hours = 12))
        answers += Result.success(null)
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()

        assertEquals(Countdown.Once(armed + 12 * HOUR), screen.countdown.value)
        coVerify { checkIns.newestOwnCheckIn(CIRCLE, armed, any()) }
    }

    @Test
    fun `the earliest of several, counted from the start - a check-in from before it ends nothing`() = runTest {
        rules.value = listOf(selfCheckInRule("r-1", hours = 8), selfCheckInRule("r-2", hours = 2))
        answers += Result.success(now - 30 * HOUR)
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()

        assertEquals("overdue since the start plus two hours", Countdown.Once(armed + 2 * HOUR), screen.countdown.value)
    }

    @Test
    fun `a failed read shows nothing rather than a wrong time`() = runTest {
        rules.value = listOf(selfCheckInRule())
        answers += Result.failure(java.io.IOException("offline"))
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()

        assertNull(screen.countdown.value)
    }

    @Test
    fun `no check-in rule about yourself in this circle - nothing shown, and nothing read`() = runTest {
        rules.value = listOf(
            selfCheckInRule("r-1", circle = OTHER),
            selfCheckInRule("r-2", self = false),
            selfCheckInRule("r-3", watches = InactivityWatches.LOCATION),
        )
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()

        assertNull(screen.countdown.value)
        coVerify(exactly = 0) { checkIns.newestOwnCheckIn(any(), any(), any()) }
    }

    @Test
    fun `an accepted check-in ends a one-time rule's line, and one waiting on this phone doesn't`() = runTest {
        rules.value = listOf(selfCheckInRule(hours = 12))
        answers += Result.success(null)
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()
        val before = screen.countdown.value
        assertEquals(Countdown.Once(armed + 12 * HOUR), before)

        // Still waiting on this phone: the circle's phones can't see it, so nothing moves.
        coEvery { checkIns.createQuickCheckIn(CIRCLE, any(), true) } returns
            Result.failure(Exception("Failed to send check-in, saved locally"))
        screen.quickCheckIn("general")
        advanceUntilIdle()
        assertEquals(before, screen.countdown.value)
        coVerify(exactly = 1) { checkIns.newestOwnCheckIn(any(), any(), any()) }

        // Accepted: read again, and the rule has ended (the server turns it off).
        coEvery { checkIns.createQuickCheckIn(CIRCLE, any(), true) } returns Result.success(accepted(now))
        answers += Result.success(now)
        screen.quickCheckIn("general")
        advanceUntilIdle()
        assertNull(screen.countdown.value)
        coVerify(exactly = 2) { checkIns.newestOwnCheckIn(any(), any(), any()) }
    }

    @Test
    fun `each time the screen opens, it reads again`() = runTest {
        rules.value = listOf(selfCheckInRule(hours = 12))
        answers += Result.success(null)
        answers += Result.success(now - HOUR)
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()
        assertEquals(Countdown.Once(armed + 12 * HOUR), screen.countdown.value)
        screen.readCountdown()
        advanceUntilIdle()

        assertNull("a check-in made on another phone ended it", screen.countdown.value)
    }

    @Test
    fun `a scheduled rule reads from its latest occurrence's window, and counts down by its schedule`() = runTest {
        val rule = selfCheckInRule().copy(
            checkInMode = CheckInModes.SCHEDULED, scheduleDays = CheckInSchedule.EVERY_DAY, scheduleDueMinute = 21 * 60,
            scheduleTz = "UTC",
        )
        rules.value = listOf(rule)
        answers += Result.success(now - HOUR)
        val screen = checkInScreen()
        backgroundScope.launch(dispatcher) { screen.countdown.collect {} }
        advanceUntilIdle()

        val schedule = CheckInSchedule.of(rule)!!
        assertEquals(Countdown.Scheduled(schedule, armed, now - HOUR), screen.countdown.value)
        val window = schedule.latest(now)!!.windowStart
        coVerify { checkIns.newestOwnCheckIn(CIRCLE, window, any()) }
    }

    // ── the rule's row in Alerts ─────────────────────────────────────────────

    @Test
    fun `each rule about yourself shows its countdown, and a circle whose read failed shows nothing`() = runTest {
        rules.value = listOf(
            selfCheckInRule("r-1", hours = 4),
            selfCheckInRule("r-2", circle = OTHER, hours = 4),
            selfCheckInRule("r-3", self = false),
        )
        coEvery { checkIns.newestOwnCheckIn(CIRCLE, any(), any()) } returns Result.success(null)
        coEvery { checkIns.newestOwnCheckIn(OTHER, any(), any()) } returns Result.failure(java.io.IOException("offline"))
        val list = InactivityAlertsViewModel(alerts, circles, checkIns)
        backgroundScope.launch(dispatcher) { list.selfCheckInCountdowns.collect {} }
        advanceUntilIdle()
        list.readSelfCheckIns()
        advanceUntilIdle()

        assertEquals(mapOf("r-1" to Countdown.Once(armed + 4 * HOUR)), list.selfCheckInCountdowns.value)
    }
}
