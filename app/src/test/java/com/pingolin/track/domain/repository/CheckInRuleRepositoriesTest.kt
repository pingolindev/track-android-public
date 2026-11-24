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

import com.pingolin.track.data.local.dao.CheckInDao
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.dao.UserLastCheckIn
import com.pingolin.track.data.local.entity.CheckInEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CheckInResponse
import com.pingolin.track.data.remote.dto.CreateInactivityAlertRequest
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.InactivityAlertResponse
import com.pingolin.track.data.remote.dto.RefusedException
import com.pingolin.track.data.remote.dto.UpdateInactivityAlertRequest
import com.pingolin.track.util.InactivityWatches
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData

/**
 * What the check-in rules read and keep: the circle's recent check-ins, a page at a time until
 * they reach back past a day (CheckInRepository.refreshRecentCheckIns), each member's newest one
 * held here, and the rules themselves, asked for with check-in rules included, each with its kind
 * and its start (InactivityAlertRepository).
 */
class CheckInRuleRepositoriesTest {

    private companion object {
        const val SELF = "5e1f0000-0000-4000-8000-00000000000e"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
        /** Noon on day 20000: the read reaches back to day 19999. */
        const val NOW = 20_000 * DAY + 12 * HOUR
    }

    // ── the check-ins ────────────────────────────────────────────────────────

    private val api = mockk<TrackApi>()
    private val checkInDao = mockk<CheckInDao>(relaxed = true)
    private val crypto = mockk<CryptoManager>().also { crypto ->
        // A sealed field opens to its own ciphertext here, except "doesn't open".
        every { crypto.decryptDataV2(any(), any(), any()) } answers {
            arg<EncryptedData>(1).ciphertext.also { if (it == "doesn't open") throw IllegalStateException("no key") }
        }
    }

    private fun checkInRepository(session: SessionManager = mockk(relaxed = true)) = CheckInRepository(
        tracksApi = api,
        checkInDao = checkInDao,
        sessionManager = session,
        cryptoManager = crypto,
        privacySettingDao = mockk(relaxed = true),
        circleRepository = mockk(relaxed = true),
        pendingChanges = mockk(relaxed = true),
        pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
    )

    private fun row(id: String, day: Int, sealedTime: String = "${day * DAY}", user: String = ALICE) = CheckInResponse(
        id = id, circleId = CIRCLE, placeId = null, encryptedLocation = null, encryptedMessage = null, checkInType = "general",
        encryptedUserId = EncryptedPayload("n", user), encryptedDeviceId = EncryptedPayload("n", "dev"),
        dayBucket = day, encryptedTimestamp = EncryptedPayload("n", sealedTime),
    )

    private fun page(rows: List<CheckInResponse>) = Response.success(ApiResponse(success = true, data = rows, error = null))

    /** [n] rows on [day], ids "<prefix>-0".. counting down, as the server orders them. */
    private fun rows(prefix: String, n: Int, day: Int) = (0 until n).map { row("$prefix-${n - it}", day) }

    @Test
    fun `a short page is the whole of it`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(rows("a", 3, 20000))

        val read = checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow()

        assertEquals(3, read.read.size)
        assertTrue(read.whole)
        coVerify(exactly = 1) { api.getCircleCheckIns(any(), any(), any(), any()) }
        coVerify(exactly = 3) { checkInDao.insert(any<CheckInEntity>()) }
    }

    @Test
    fun `a full page goes on from its last row, until a page reaches back past a day`() = runTest {
        val first = rows("a", 100, 20000)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(first)
        // The next page starts after the previous one's smallest day bucket and last id.
        coEvery { api.getCircleCheckIns(CIRCLE, 100, 20000, first.last().id) } returns page(rows("b", 99, 19999) + row("b-0", 19998))

        val read = checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow()

        assertEquals(200, read.read.size)
        coVerify(exactly = 2) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `five pages at most`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, any(), any()) } returns page(rows("a", 100, 20000))

        val read = checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow()

        assertEquals(500, read.read.size)
        coVerify(exactly = 5) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `a page that fails fails the read`() = runTest {
        val first = rows("a", 100, 20000)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(first)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, 20000, first.last().id) } returns Response.error(503, "".toResponseBody(null))

        assertTrue(checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).isFailure)

        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } throws java.io.IOException("offline")
        assertTrue(checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).isFailure)
    }

    @Test
    fun `a check-in within reach that doesn't open leaves the read not whole, and an older one doesn't`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns
            page(listOf(row("a-2", 20000), row("a-1", 19999, sealedTime = "doesn't open")))
        val within = checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow()
        assertEquals(1, within.unopened)
        assertFalse(within.whole)
        assertEquals(listOf("a-2"), within.read.map { it.id })

        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns
            page(listOf(row("a-2", 20000), row("a-1", 19998, sealedTime = "doesn't open")))
        assertTrue(checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow().whole)
    }

    @Test
    fun `the alert history reads 30 days back the same way, where the rules read about a day`() = runTest {
        val first = rows("a", 100, 19990)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(first)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, 19990, first.last().id) } returns page(rows("b", 99, 19975) + row("b-0", 19969))

        val history = checkInRepository().refreshRecentCheckIns(CIRCLE, NOW, windowMs = 30 * DAY).getOrThrow()

        assertEquals("on to the page that reaches past day 19970", 200, history.read.size)
        coVerify(exactly = 2) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `the rules' read stops at the first page that reaches past a day`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(rows("a", 100, 19990))

        assertEquals(100, checkInRepository().refreshRecentCheckIns(CIRCLE, NOW).getOrThrow().read.size)
        coVerify(exactly = 1) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `the circle-mates' check-in requests, and a failed read is a failure, never nobody asked`() = runTest {
        fun ask(id: String, watches: String?) = com.pingolin.track.data.remote.dto.CircleSelfAlertDto(
            id = id, userId = ALICE, circleId = CIRCLE, timeoutHours = 4, watches = watches,
        )
        coEvery { api.getCircleSelfAlerts(true) } returns
            Response.success(ApiResponse(success = true, data = listOf(ask("s-1", "check_in"), ask("s-2", "location"), ask("s-3", null)), error = null))
        assertEquals(listOf("s-1"), alertRepository().circleCheckInRequests().getOrThrow().map { it.id })

        coEvery { api.getCircleSelfAlerts(true) } returns Response.error(503, "".toResponseBody(null))
        assertTrue(alertRepository().circleCheckInRequests().isFailure)
        coEvery { api.getCircleSelfAlerts(true) } throws java.io.IOException("offline")
        assertTrue(alertRepository().circleCheckInRequests().isFailure)
    }

    @Test
    fun `each member's newest check-in held here, whatever spelling it was sealed with, none from the future`() = runTest {
        coEvery { checkInDao.lastByUser(CIRCLE, NOW + com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS) } returns listOf(
            UserLastCheckIn(ALICE.uppercase(), 7L),
            UserLastCheckIn(ALICE, 5L),
            UserLastCheckIn("not-a-uuid", 3L),
        )

        assertEquals(mapOf(ALICE to 7L, "not-a-uuid" to 3L), checkInRepository().lastCheckIns(CIRCLE, NOW))
    }

    // ── this account's newest, for the countdown ───────────────────────────────

    private val signedInAsAlice = mockk<SessionManager>().also { every { it.getUserId() } returns ALICE.uppercase() }
    private val bob = "b0b00000-0000-4000-8000-00000000b0b0"

    /** Noon on day 20000 as a sealed time, [h] hours later. */
    private fun at(h: Int) = (20_000 * DAY + (12 + h) * HOUR).toString()

    @Test
    fun `this account's newest that the server has, among the circle's, whatever spelling it was sealed with`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(listOf(
            row("k-4", 20000, at(-1), user = bob),
            row("k-3", 20000, at(-3)),
            row("k-2", 20000, at(-2)),
            row("k-1", 19999, at(-20)),
        ))

        val newest = checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, sinceMs = 19_990 * DAY, nowMs = NOW)

        assertEquals(20_000 * DAY + 10 * HOUR, newest.getOrThrow())
        coVerify(exactly = 1) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `a day's check-ins come in no order of time, so the read goes on until a page passes the newest's day`() = runTest {
        val first = (1..99).map { row("a-$it", 20000, at(-5), user = bob) } + row("a-0", 20000, at(-4))
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(first)
        coEvery { api.getCircleCheckIns(CIRCLE, 100, 20000, "a-0") } returns
            page(listOf(row("b-2", 20000, at(-1)), row("b-1", 19999, at(-24))))

        val newest = checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, sinceMs = 19_990 * DAY, nowMs = NOW)

        assertEquals("the later one on the same day", 20_000 * DAY + 11 * HOUR, newest.getOrThrow())
        coVerify(exactly = 2) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `nothing from before the rule's start is looked for`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(
            (1..50).map { row("a-$it", 20000, at(-1), user = bob) } + (1..50).map { row("o-$it", 19998, at(-40)) },
        )

        val newest = checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, sinceMs = 20_000 * DAY, nowMs = NOW)

        assertEquals("none since the start", null, newest.getOrThrow())
        coVerify(exactly = 1) { api.getCircleCheckIns(any(), any(), any(), any()) }
    }

    @Test
    fun `a check-in that doesn't open where it could be the newest is no answer, and one before the range is nothing`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns
            page(listOf(row("k-2", 20000, "doesn't open"), row("k-1", 20000, at(-3))))
        assertTrue(checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, sinceMs = 19_990 * DAY, nowMs = NOW).isFailure)

        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns
            page(listOf(row("k-2", 20000, at(-3)), row("k-1", 19990, "doesn't open")))
        assertEquals(
            20_000 * DAY + 9 * HOUR,
            checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, sinceMs = 19_995 * DAY, nowMs = NOW).getOrThrow(),
        )
    }

    @Test
    fun `one sealed far ahead of this phone's clock isn't counted, a failed page is no answer, and none is none`() = runTest {
        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(listOf(row("k-2", 20000, at(5)), row("k-1", 20000, at(-2))))
        assertEquals(20_000 * DAY + 10 * HOUR, checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, 19_990 * DAY, NOW).getOrThrow())

        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns Response.error(503, "".toResponseBody(null))
        assertTrue(checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, 19_990 * DAY, NOW).isFailure)

        coEvery { api.getCircleCheckIns(CIRCLE, 100, null, null) } returns page(listOf(row("k-1", 20000, at(-1), user = bob)))
        assertEquals(null, checkInRepository(signedInAsAlice).newestOwnCheckIn(CIRCLE, 19_990 * DAY, NOW).getOrThrow())

        val signedOut = mockk<SessionManager>().also { every { it.getUserId() } returns null }
        assertTrue("signed out: no answer", checkInRepository(signedOut).newestOwnCheckIn(CIRCLE, 19_990 * DAY, NOW).isFailure)
    }

    // ── the rules ────────────────────────────────────────────────────────────

    private val session = mockk<SessionManager>().also { every { it.getUserId() } returns SELF }
    private val alertDao = mockk<InactivityAlertDao>()
    private var stored = listOf<InactivityAlertEntity>()
    private val written = mutableListOf<List<InactivityAlertEntity>>()
    private val alertCrypto = mockk<CryptoManager>().also { crypto ->
        every { crypto.decryptData(any(), any()) } answers { arg<EncryptedData>(1).ciphertext }
        every { crypto.encryptData(any(), any()) } answers { EncryptedData(nonce = "n", ciphertext = secondArg()) }
        every { crypto.deriveUserPseudonym(any(), any()) } answers { "pseudonym-${secondArg<String>()}" }
    }

    init {
        coEvery { alertDao.getAll() } answers { stored }
        coEvery { alertDao.applyRefresh(any(), any(), any()) } answers {
            val changed = firstArg<List<InactivityAlertEntity>>()
            written += changed
            stored = stored.filter { s -> changed.none { it.id == s.id } } + changed
            true
        }
    }

    private fun alertRepository() = InactivityAlertRepository(
        api, alertDao, mockk(relaxed = true), mockk(relaxed = true), session, alertCrypto, mockk(relaxed = true), mockk(relaxed = true),
    )

    private fun alertRow(id: String, watches: String?, armedAt: String?) = InactivityAlertResponse(
        id = id, userId = SELF, circleId = CIRCLE, encryptedTargetUserId = EncryptedPayload("n", ALICE), timeoutHours = 4,
        isSelfAlert = false, notifyPush = true, notifyEmail = false, cooldownMinutes = 60, isActive = true,
        lastTriggeredDayBucket = null, dayBucket = 20000, encryptedConditions = null, clientOnly = false,
        watches = watches, armedAt = armedAt,
    )

    private fun listing(vararg rows: InactivityAlertResponse) {
        coEvery { api.getInactivityAlerts(any(), any(), null, null, any()) } returns
            Response.success(ApiResponse(success = true, data = rows.toList(), error = null))
        coEvery { api.getInactivityAlerts(any(), any(), 20000, any(), any()) } returns
            Response.success(ApiResponse(success = true, data = emptyList(), error = null))
    }

    @Test
    fun `every page of the rules asks for check-in rules too, and each keeps its kind and start`() = runTest {
        listing(
            alertRow("r-1", "check_in", "2026-11-23T10:15:30.123456Z"),
            alertRow("r-2", "check_in", null),
            alertRow("r-3", null, null),
        )
        val before = System.currentTimeMillis()

        val alerts = alertRepository().refreshInactivityAlerts().getOrThrow().associateBy { it.id }

        coVerify(exactly = 2) { api.getInactivityAlerts(any(), any(), any(), any(), true) }
        coVerify(exactly = 0) { api.getInactivityAlerts(any(), any(), any(), any(), false) }
        assertEquals(InactivityWatches.CHECK_IN, alerts.getValue("r-1").watches)
        assertEquals(java.time.Instant.parse("2026-11-23T10:15:30.123Z").toEpochMilli(), alerts.getValue("r-1").armedAt)
        // A check-in rule that came without its start counts from when this phone first stored it.
        val firstStored = alerts.getValue("r-2").armedAt!!
        assertTrue(firstStored >= before)
        assertEquals("an older server's row is a location rule", InactivityWatches.LOCATION, alerts.getValue("r-3").watches)
        assertNull(alerts.getValue("r-3").armedAt)
        assertEquals(setOf("r-1", "r-2", "r-3"), written.single().map { it.id }.toSet())

        // Read again later: the start this phone stored stands, never moved later, and nothing is rewritten.
        val again = alertRepository().refreshInactivityAlerts().getOrThrow().associateBy { it.id }
        assertEquals(firstStored, again.getValue("r-2").armedAt)
        assertEquals(emptyList<InactivityAlertEntity>(), written.last())
    }

    @Test
    fun `a start that doesn't read fails nothing but its own rule, which counts from when it was first stored`() = runTest {
        listing(
            alertRow("r-1", "check_in", "not a time"),
            alertRow("r-2", "check_in", "2026-11-23T10:15:30Z"),
        )
        val before = System.currentTimeMillis()

        val alerts = alertRepository().refreshInactivityAlerts().getOrThrow().associateBy { it.id }

        assertTrue(alerts.getValue("r-1").armedAt!! >= before)
        assertEquals("no fraction: the server leaves out a zero one", java.time.Instant.parse("2026-11-23T10:15:30Z").toEpochMilli(), alerts.getValue("r-2").armedAt)
    }

    @Test
    fun `a check-in rule's start is the server's, else the one stored here, else now`() {
        val rule = InactivityAlertEntity(
            id = "r-1", userId = SELF, circleId = CIRCLE, targetUserId = ALICE, timeoutHours = 4, dayBucket = 0,
            watches = InactivityWatches.CHECK_IN,
        )
        val location = rule.copy(id = "r-2", watches = InactivityWatches.LOCATION)
        val withStart = InactivityAlertRepository.withStart(
            listOf(rule, rule.copy(id = "r-3", armedAt = 5L), rule.copy(id = "r-4"), location),
            mapOf("r-4" to rule.copy(id = "r-4", armedAt = 9L), "r-3" to rule.copy(id = "r-3", armedAt = 1L)),
            nowMs = 100L,
        ).associateBy({ it.id }, { it.armedAt })
        assertEquals(mapOf("r-1" to 100L, "r-3" to 5L, "r-4" to 9L, "r-2" to null), withStart)
    }

    @Test
    fun `a new check-in rule says what it watches, names its member's pseudonym, and never asks for back online`() = runTest {
        val sent = mutableListOf<CreateInactivityAlertRequest>()
        coEvery { api.createInactivityAlert(any()) } answers {
            sent += firstArg<CreateInactivityAlertRequest>()
            Response.success(ApiResponse(success = true, data = "new-1", error = null))
        }
        listing()

        val created = alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = ALICE, timeoutMinutes = 240, notifyOnReturn = true,
            watchedUserIds = listOf(ALICE, SELF), watches = InactivityWatches.CHECK_IN,
        )
        assertEquals("new-1", created.getOrThrow())
        val request = sent.single()
        assertEquals("check_in", request.watches)
        assertEquals("pseudonym-$ALICE", request.targetUserPseudonym)
        assertEquals(false, request.notifyOnReturn)
        assertEquals(240, request.timeoutMinutes)

        // A location rule goes out as before: no kind, and back online as asked.
        sent.clear()
        alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = ALICE, timeoutMinutes = 240, notifyOnReturn = true, watchedUserIds = listOf(ALICE),
        )
        assertNull(sent.single().watches)
        assertEquals(true, sent.single().notifyOnReturn)
    }

    @Test
    fun `a check-in rule turned back on starts its clock again here, as on the server`() = runTest {
        val old = 1_000L
        val rule = InactivityAlertEntity(
            id = "r-1", userId = SELF, circleId = CIRCLE, targetUserId = ALICE, timeoutHours = 4, dayBucket = 0,
            isActive = false, watches = InactivityWatches.CHECK_IN, armedAt = old,
        )
        val updated = mutableListOf<InactivityAlertEntity>()
        coEvery { alertDao.getById("r-1") } answers { rule }
        coEvery { alertDao.getById("r-2") } answers { rule.copy(id = "r-2", watches = InactivityWatches.LOCATION, armedAt = null) }
        coEvery { alertDao.update(any()) } answers { updated += firstArg<InactivityAlertEntity>() }
        coEvery { api.updateInactivityAlert(any(), any<UpdateInactivityAlertRequest>()) } returns
            Response.success(ApiResponse(success = true, data = Unit, error = null))
        val before = System.currentTimeMillis()

        alertRepository().updateInactivityAlert("r-1", isActive = true)
        assertTrue(updated.last().isActive)
        assertTrue("from now, not from before it was off", updated.last().armedAt!! >= before)

        // Turned off, or a location rule turned on: the start stays.
        coEvery { alertDao.getById("r-1") } answers { rule.copy(isActive = true) }
        alertRepository().updateInactivityAlert("r-1", isActive = false)
        assertEquals(old, updated.last().armedAt)
        alertRepository().updateInactivityAlert("r-2", isActive = true)
        assertNull(updated.last().armedAt)
    }

    // ── one-time and scheduled check-in rules ────────────────────────────────

    private val weekdaysByNine = com.pingolin.track.util.CheckInScheduleSetting(
        days = com.pingolin.track.util.CheckInSchedule.WEEKDAYS, dueMinute = 21 * 60, fromMinute = null, tz = "Europe/Berlin", followsPhone = true,
    )

    @Test
    fun `every page asks for both kinds, and a scheduled rule is kept with its schedule`() = runTest {
        listing(
            alertRow("r-1", "check_in", "2026-11-24T10:00:00Z").copy(
                checkInMode = "scheduled", scheduleDays = 31, scheduleDueMinute = 1260, scheduleFromMinute = 1080,
                scheduleTz = "Europe/Berlin", scheduleTzFollowsPhone = true,
            ),
            alertRow("r-2", "check_in", "2026-11-24T10:00:00Z").copy(checkInMode = "once"),
        )

        val alerts = alertRepository().refreshInactivityAlerts().getOrThrow().associateBy { it.id }

        coVerify(exactly = 2) { api.getInactivityAlerts(any(), any(), any(), any(), true, "once,scheduled") }
        val scheduled = alerts.getValue("r-1")
        assertEquals("scheduled", scheduled.checkInMode)
        assertEquals(31, scheduled.scheduleDays)
        assertEquals(1260, scheduled.scheduleDueMinute)
        assertEquals(1080, scheduled.scheduleFromMinute)
        assertEquals("Europe/Berlin", scheduled.scheduleTz)
        assertTrue(scheduled.scheduleTzFollowsPhone)
        val once = alerts.getValue("r-2")
        assertEquals("once", once.checkInMode)
        assertNull(once.scheduleDays)
        assertTrue(!once.scheduleTzFollowsPhone)
    }

    @Test
    fun `a new scheduled rule sends its schedule, following the phone only about yourself`() = runTest {
        val sent = mutableListOf<CreateInactivityAlertRequest>()
        coEvery { api.createInactivityAlert(any()) } answers {
            sent += firstArg<CreateInactivityAlertRequest>()
            Response.success(ApiResponse(success = true, data = "new-1", error = null))
        }
        listing()

        alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = SELF, timeoutMinutes = 60, isSelfAlert = true,
            watches = InactivityWatches.CHECK_IN, schedule = weekdaysByNine,
        )
        val mine = sent.single()
        assertEquals("scheduled", mine.checkInMode)
        assertEquals(31, mine.scheduleDays)
        assertEquals(1260, mine.scheduleDueMinute)
        assertNull("the start of the day", mine.scheduleFromMinute)
        assertEquals("Europe/Berlin", mine.scheduleTz)
        assertEquals(true, mine.scheduleTzFollowsPhone)
        assertEquals(60, mine.timeoutMinutes)
        assertEquals("no cooldown is offered for it: 60", 60, mine.cooldownMinutes)

        // About someone else it never follows this phone (the server refuses that).
        sent.clear()
        alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = ALICE, timeoutMinutes = 60, watchedUserIds = listOf(ALICE),
            watches = InactivityWatches.CHECK_IN, schedule = weekdaysByNine,
        )
        assertEquals(false, sent.single().scheduleTzFollowsPhone)

        // A one-time rule says so, with no schedule; a location rule says neither, whatever it was handed.
        sent.clear()
        alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = ALICE, timeoutMinutes = 240, watchedUserIds = listOf(ALICE), watches = InactivityWatches.CHECK_IN,
        )
        assertEquals("once", sent.single().checkInMode)
        assertNull(sent.single().scheduleDays)
        sent.clear()
        alertRepository().createInactivityAlert(
            circleId = CIRCLE, targetUserId = ALICE, timeoutMinutes = 240, watchedUserIds = listOf(ALICE), schedule = weekdaysByNine,
        )
        assertNull(sent.single().checkInMode)
        assertNull(sent.single().scheduleDays)
    }

    @Test
    fun `an edit sends the schedule whole, and keeps it here`() = runTest {
        val rule = InactivityAlertEntity(
            id = "r-1", userId = SELF, circleId = CIRCLE, targetUserId = SELF, timeoutHours = 1, isSelfAlert = true, dayBucket = 0,
            watches = InactivityWatches.CHECK_IN, armedAt = 1_000L, checkInMode = "scheduled", scheduleDays = 127,
            scheduleDueMinute = 9 * 60, scheduleFromMinute = 6 * 60, scheduleTz = "Asia/Tokyo", scheduleTzFollowsPhone = false,
        )
        val sent = mutableListOf<UpdateInactivityAlertRequest>()
        val updated = mutableListOf<InactivityAlertEntity>()
        coEvery { alertDao.getById("r-1") } answers { rule }
        coEvery { alertDao.update(any()) } answers { updated += firstArg<InactivityAlertEntity>() }
        coEvery { api.updateInactivityAlert(any(), any<UpdateInactivityAlertRequest>()) } answers {
            sent += secondArg<UpdateInactivityAlertRequest>()
            Response.success(ApiResponse(success = true, data = Unit, error = null))
        }

        alertRepository().updateInactivityAlert("r-1", notifyPush = true, schedule = weekdaysByNine)

        val request = sent.single()
        assertEquals(31, request.scheduleDays)
        assertEquals(1260, request.scheduleDueMinute)
        assertNull(request.scheduleFromMinute)
        assertEquals("Europe/Berlin", request.scheduleTz)
        assertEquals(true, request.scheduleTzFollowsPhone)
        val kept = updated.single()
        assertEquals(31, kept.scheduleDays)
        assertEquals(1260, kept.scheduleDueMinute)
        assertNull("back to the start of the day", kept.scheduleFromMinute)
        assertEquals("Europe/Berlin", kept.scheduleTz)
        assertTrue(kept.scheduleTzFollowsPhone)
        assertEquals("an edit is no restart", 1_000L, kept.armedAt)
    }

    @Test
    fun `following the phone sends the zone alone and keeps it here, and a refusal says so`() = runTest {
        val rule = InactivityAlertEntity(
            id = "r-1", userId = SELF, circleId = CIRCLE, targetUserId = SELF, timeoutHours = 1, isSelfAlert = true, dayBucket = 0,
            watches = InactivityWatches.CHECK_IN, armedAt = 1_000L, checkInMode = "scheduled", scheduleDays = 127,
            scheduleDueMinute = 9 * 60, scheduleTz = "Asia/Tokyo", scheduleTzFollowsPhone = true,
        )
        val sent = mutableListOf<UpdateInactivityAlertRequest>()
        val updated = mutableListOf<InactivityAlertEntity>()
        coEvery { alertDao.getById("r-1") } answers { rule }
        coEvery { alertDao.update(any()) } answers { updated += firstArg<InactivityAlertEntity>() }
        coEvery { api.updateInactivityAlert(any(), any<UpdateInactivityAlertRequest>()) } answers {
            sent += secondArg<UpdateInactivityAlertRequest>()
            Response.success(ApiResponse(success = true, data = Unit, error = null))
        }

        assertTrue(alertRepository().updateScheduleZone("r-1", "Europe/Lisbon").isSuccess)

        assertEquals(
            UpdateInactivityAlertRequest(null, null, null, null, null, null, null, scheduleTz = "Europe/Lisbon"),
            sent.single(),
        )
        assertEquals(rule.copy(scheduleTz = "Europe/Lisbon"), updated.single())

        coEvery { api.updateInactivityAlert(any(), any<UpdateInactivityAlertRequest>()) } returns
            Response.error(400, """{"success":false,"data":null,"error":"bad zone"}""".toResponseBody("application/json".toMediaType()))
        val refused = alertRepository().updateScheduleZone("r-1", "Mars/Olympus").exceptionOrNull()
        assertEquals(400, (refused as RefusedException).status)
        assertEquals("nothing kept from a refusal", 1, updated.size)
    }
}
