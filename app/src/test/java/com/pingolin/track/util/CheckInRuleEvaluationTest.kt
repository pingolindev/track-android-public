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

import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.local.entity.LocalInactivityEventEntity
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.domain.repository.CheckInRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import uniffi.pingolin_mobile.EncryptedData
import java.time.Instant

/**
 * Check-in rules, judged on this phone (MonitoringManager.evaluateCheckInRules): a one-time rule's
 * alert when a member hasn't checked in within its hours of its start, and never after their first
 * check-in since (it ends there); a scheduled rule's when they haven't checked in by its time since
 * that day's window opened, once per occurrence. Never without a read of the circle's check-ins in
 * the same pass that came back whole (back to a scheduled rule's window); once per lapse, ended with
 * no "back" notice. A missed check-in of a rule no longer judged here is taken back. A member who
 * paused location sharing is still judged; this phone's own user never is; location rules are judged
 * by locations only, and check-in rules by check-ins only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInRuleEvaluationTest {

    private companion object {
        const val SELF = "5e1f0000-0000-4000-8000-00000000000e"
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-00000000b0b0"
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val HOUR = 3_600_000L
    }

    private val now = System.currentTimeMillis()

    /** The evaluator's clock (MonitoringManager.checkInClock): [now] unless a test moves it. */
    private var clock = now

    private val tracksApi = mockk<TrackApi>()
    private val circleMemberDao = mockk<CircleMemberDao>()
    private val sessionManager = mockk<SessionManager>()
    private val alertHandler = mockk<AlertHandler>()
    private val inactivityAlertDao = mockk<InactivityAlertDao>()
    private val locationDao = mockk<LocationDao>()
    private val notificationDao = mockk<NotificationDao>()
    private val localInactivityEventDao = mockk<LocalInactivityEventDao>()
    private val cryptoManager = mockk<uniffi.pingolin_mobile.CryptoManager>()
    private val checkIns = mockk<CheckInRepository>()

    /** Every alert AlertHandler was asked to post: (type, data). */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()
    private val cancelled = mutableListOf<Pair<String, String>>()
    private val bell = mutableListOf<NotificationEntity>()
    /** Every plaintext sealed for the account's other devices (reportAlertEvent). */
    private val sealed = mutableListOf<String>()

    /** The local lapse store: open lapses by "rule|member", every one inserted, ids resolved. */
    private val open = mutableMapOf<String, LocalInactivityEventEntity>()
    private val inserted = mutableListOf<LocalInactivityEventEntity>()
    private val resolved = mutableListOf<String>()

    private var rules = listOf<InactivityAlertEntity>()
    private var members = listOf<CircleMemberEntity>()
    private var locations = listOf<LocationEntity>()

    /** What this phone holds: each member's newest check-in (CheckInRepository.lastCheckIns). */
    private val held = mutableMapOf<String, Long>()
    /** What a read of the circle's check-ins brings. */
    private val onServer = mutableMapOf<String, Long>()
    /** How a read goes: null fails it; otherwise how many check-ins within reach didn't open. */
    private var unopened: Int? = 0
    private var reads = 0
    /** How far back each read reached (CheckInRepository.refreshRecentCheckIns' windowMs). */
    private val windows = mutableListOf<Long>()
    /** This account's rules on this phone that aren't on (InactivityAlertDao.getAll, beside [rules]). */
    private var off = listOf<InactivityAlertEntity>()

    @Before
    fun setup() {
        every { sessionManager.getUserId() } returns SELF
        every { alertHandler.handleAlert(any(), any()) } answers { posted += firstArg<String>() to secondArg<Map<String, String>>() }
        every { alertHandler.inactivityDedupeKey(any(), any()) } answers { "inact:${firstArg<String>()}:${secondArg<String>()}" }
        every { alertHandler.cancelInactivity(any(), any()) } answers { cancelled += firstArg<String>() to secondArg<String>() }
        coEvery { inactivityAlertDao.getActive() } answers { rules }
        coEvery { inactivityAlertDao.getAll() } answers { rules + off }
        coEvery { localInactivityEventDao.getOpenByWatches(any()) } answers { open.values.filter { it.watches == firstArg<String>() } }
        coEvery { circleMemberDao.getByCircle(any()) } answers { members }
        every { locationDao.observeLatestByCircle(any()) } answers { flowOf(locations) }
        coEvery { localInactivityEventDao.getOpen(any(), any()) } answers { open["${firstArg<String>()}|${secondArg<String>()}"] }
        coEvery { localInactivityEventDao.insert(any()) } answers {
            val event = firstArg<LocalInactivityEventEntity>()
            inserted += event
            open["${event.inactivityAlertId}|${event.targetUserId}"] = event
        }
        coEvery { localInactivityEventDao.resolve(any(), any()) } answers {
            resolved += firstArg<String>()
            open.values.removeIf { it.id == firstArg<String>() }
        }
        coEvery { notificationDao.insert(any()) } answers { bell += firstArg<NotificationEntity>() }
        every { cryptoManager.encryptData(any(), any()) } answers {
            sealed += secondArg<String>()
            EncryptedData(nonce = "n", ciphertext = "c")
        }
        coEvery { tracksApi.createAlertEvent(any()) } returns Response.success(ApiResponse(success = true, data = "ok", error = null))
        coEvery { checkIns.lastCheckIns(any(), any()) } answers { held.toMap() }
        coEvery { checkIns.refreshRecentCheckIns(any(), any(), any()) } answers {
            reads++
            windows += thirdArg<Long>()
            when (val n = unopened) {
                null -> Result.failure(java.io.IOException("offline"))
                else -> {
                    held.putAll(onServer)
                    Result.success(CheckInRepository.RecentCheckIns(emptyList(), n))
                }
            }
        }
        members = listOf(member(ALICE, "Alice"), member(BOB, "Bob"))
    }

    private fun manager() = MonitoringManager(
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        tracksApi = tracksApi,
        circleDao = mockk(),
        circleMemberDao = circleMemberDao,
        sessionManager = sessionManager,
        alertHandler = alertHandler,
        inactivityAlertDao = inactivityAlertDao,
        locationDao = locationDao,
        notificationDao = notificationDao,
        localInactivityEventDao = localInactivityEventDao,
        memberNotSharingSeenDao = mockk(relaxed = true),
        alertRuleDao = mockk(),
        localBatteryEventDao = mockk(),
        privacySettingDao = mockk(),
        cryptoManager = cryptoManager,
        checkInRepository = dagger.Lazy { checkIns },
    ).also { it.checkInClock = { clock } }

    private fun member(userId: String, name: String, sharing: String? = null) = CircleMemberEntity(
        circleId = CIRCLE, userId = userId, displayName = name, role = "member", joinedAt = 0L, locationSharing = sharing,
    )

    private fun checkInRule(
        target: String,
        hours: Int = 4,
        armedAgo: Long = 48 * HOUR,
        self: Boolean = false,
        watches: String = InactivityWatches.CHECK_IN,
    ) = InactivityAlertEntity(
        id = "rule-$target", userId = SELF, circleId = CIRCLE, targetUserId = target, timeoutHours = hours,
        isSelfAlert = self, dayBucket = 0, watches = watches,
        armedAt = (now - armedAgo).takeIf { InactivityWatches.isCheckIn(watches) },
    )

    private fun request(userId: String, hours: Int = 4, armedAt: String? = Instant.ofEpochMilli(now - 48 * HOUR).toString()) =
        CircleSelfAlertDto(id = "ask-$userId", userId = userId, circleId = CIRCLE, timeoutHours = hours, watches = "check_in", armedAt = armedAt)

    private fun lapse(ruleId: String, userId: String, since: Long) = LocalInactivityEventEntity(
        id = "local-inact:$ruleId:$userId:1", inactivityAlertId = ruleId, circleId = CIRCLE, targetUserId = userId,
        targetDisplayName = "Alice", timeoutHours = 4, inactiveSeconds = 0, triggeredAt = since + 5 * HOUR,
        triggeredDayBucket = 0, watches = InactivityWatches.CHECK_IN, silentSince = since,
    ).also { open["$ruleId|$userId"] = it }

    private fun location(userId: String, allowAlertsOnMe: Boolean?) = LocationEntity(
        circleId = CIRCLE, userId = userId, deviceId = "d", latitude = 0.0, longitude = 0.0, altitude = null, speed = null,
        heading = null, accuracy = null, batteryLevel = null, timestamp = now - HOUR, allowAlertsOnMe = allowAlertsOnMe,
    )

    // ── firing ────────────────────────────────────────────────────────────────

    @Test
    fun `a member silent past the limit is reported once, after a read, in the words every surface says`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        // From before the rule: it says nothing about now.
        onServer[ALICE] = now - 30 * HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(1, reads)
        assertEquals(listOf(AlertHandler.TYPE_MISSED_CHECK_IN), posted.map { it.first })
        val data = posted.single().second
        assertEquals("Alice hasn't checked in for 4 hours.", data[AlertHandler.KEY_MISSED_CHECK_IN])
        assertEquals("inact:rule-$ALICE:$ALICE", data["alert_id"])
        val event = inserted.single()
        assertEquals(InactivityWatches.CHECK_IN, event.watches)
        assertEquals("the silence counted from the rule's start", now - 5 * HOUR, event.silentSince)
        assertEquals("a one-time rule's words are its hours", null, event.dueMinute)
        assertEquals("as far back as a day and a bit", CheckInRepository.RECENT_MS, windows.single())
        assertEquals("rule-$ALICE", event.inactivityAlertId)
        val row = bell.single()
        assertEquals(AlertHandler.TYPE_MISSED_CHECK_IN, row.notificationType)
        assertEquals("Missed check-in", row.title)
        assertEquals("Alice hasn't checked in for 4 hours.", row.body)
        assertTrue("a bell row of this phone's own", row.id.startsWith("local-"))
        assertTrue("the other devices' copy says what it was", AlertHandler.TYPE_MISSED_CHECK_IN in sealed)

        // Once per lapse: the next pass finds it open.
        posted.clear()
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(emptyList<Any>(), posted)
    }

    @Test
    fun `within the limit nothing goes off`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 3 * HOUR))
        onServer[ALICE] = now - 30 * HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(1, reads)
        assertEquals(emptyList<Any>(), posted)
        assertEquals(emptyList<Any>(), inserted)
    }

    @Test
    fun `one hour reads as one hour`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 1, armedAgo = 2 * HOUR))

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals("Alice hasn't checked in for 1 hour.", posted.single().second[AlertHandler.KEY_MISSED_CHECK_IN])
    }

    // ── a read that failed judges nothing ────────────────────────────────────

    @Test
    fun `a failed read judges nobody - neither an alert nor an end`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4), checkInRule(BOB, hours = 4))
        // Bob's lapse is open, and this phone already holds a check-in of his after it.
        lapse("rule-$BOB", BOB, since = now - 20 * HOUR)
        held[BOB] = now - HOUR
        unopened = null

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(1, reads)
        assertEquals("no check-in is not no read", emptyList<Any>(), posted)
        assertEquals(emptyList<Any>(), resolved)
        assertEquals(emptyList<Any>(), cancelled)
    }

    @Test
    fun `a read that met a check-in it couldn't open judges nobody`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        unopened = 1

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(1, reads)
        assertEquals(emptyList<Any>(), posted)
    }

    // ── the rule's start ─────────────────────────────────────────────────────

    @Test
    fun `with no check-in since the rule started, the silence counts from its start`() = runTest {
        // Alice's last check-in was long before the rule: it says nothing about now.
        onServer[ALICE] = now - 30 * HOUR
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 3 * HOUR))
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("not yet: 3 hours since the rule started", emptyList<Any>(), posted)

        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(1, posted.size)
        assertEquals("from the rule's start", now - 5 * HOUR, inserted.single().silentSince)
    }

    @Test
    fun `a member with no check-in at all counts from the rule's start`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 2 * HOUR))
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(emptyList<Any>(), posted)

        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 6 * HOUR))
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(listOf("Alice"), posted.map { it.second["user_name"] })
    }

    @Test
    fun `a circle-mate's request with no start counts from when this phone first saw it`() = runTest {
        val ask = request(BOB, hours = 1, armedAt = null)
        val monitoring = manager()
        monitoring.evaluateCheckInRules(listOf(ask), wake = true)
        assertEquals("never from before it reached this phone", emptyList<Any>(), posted)

        clock = now + 2 * HOUR
        monitoring.evaluateCheckInRules(listOf(ask), wake = true)
        assertEquals("from then, the hour is over", listOf("Bob"), posted.map { it.second["user_name"] })
        assertEquals(now, inserted.single().silentSince)
    }

    @Test
    fun `a start that doesn't read counts from when this phone first saw it too, and the others are judged`() = runTest {
        onServer[ALICE] = now - 5 * HOUR
        onServer[BOB] = now - 5 * HOUR
        // Whole seconds: the server leaves out a fraction that is zero.
        val started = Instant.ofEpochMilli(now - 3 * HOUR).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)

        manager().evaluateCheckInRules(
            listOf(request(BOB, hours = 1, armedAt = "not a time"), request(ALICE, hours = 1, armedAt = started.toString())),
            wake = true,
        )

        assertEquals(listOf("Alice"), posted.map { it.second["user_name"] })
        assertTrue(started.toString(), !started.toString().contains('.'))
        assertEquals("from the start the server gave", started.toEpochMilli(), inserted.single().silentSince)
    }

    // ── a lapse's end ────────────────────────────────────────────────────────

    @Test
    fun `a check-in after the lapse began ends it and takes back its notification, with no back notice`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        onServer[ALICE] = now - HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf(lapse.id), resolved)
        assertEquals(listOf("rule-$ALICE" to ALICE), cancelled)
        assertEquals("the check-in told the circle already", emptyList<Any>(), posted)
    }

    @Test
    fun `no check-in after the lapse began leaves it open, with nothing new`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4))
        lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        onServer[ALICE] = now - 9 * HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(emptyList<Any>(), resolved)
        assertEquals(emptyList<Any>(), posted)
    }

    @Test
    fun `a one-time rule ends at the member's check-in - its lapse ends, and it never goes off again`() = runTest {
        // Fired from its start; then Alice checked in, and has been quiet for ten hours since.
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 30 * HOUR))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 30 * HOUR)
        onServer[ALICE] = now - 10 * HOUR
        val monitoring = manager()

        monitoring.evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf(lapse.id), resolved)
        assertEquals("the server turns it off at the check-in; this phone holds it ended until then", emptyList<Any>(), posted)

        clock = now + 20 * HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("not a rolling limit", emptyList<Any>(), posted)
    }

    @Test
    fun `a rule turned back on ends a lapse from before it, and starts counting again`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = HOUR))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 50 * HOUR)
        onServer[ALICE] = now - 50 * HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf(lapse.id), resolved)
        assertEquals(listOf("rule-$ALICE" to ALICE), cancelled)
        assertEquals("an hour since it was turned back on", emptyList<Any>(), posted)
    }

    @Test
    fun `fired, turned off and back on - the old lapse ends, nothing until the new start plus the limit, then a new alert`() = runTest {
        // Alice has never checked in. The rule went off, as she was quiet past its limit.
        val start = now - 48 * HOUR
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = now - start))
        val monitoring = manager()
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        val first = inserted.single()
        assertEquals(start, first.silentSince)

        // Turned off and back on an hour later: the server starts its clock again.
        val rearmed = now + HOUR
        rules = listOf(checkInRule(ALICE, hours = 4).copy(armedAt = rearmed))
        clock = rearmed + HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("the old lapse is over", listOf(first.id), resolved)
        assertEquals("and its notification taken back", listOf("rule-$ALICE" to ALICE), cancelled)
        assertEquals("nothing new an hour after the new start", 1, posted.size)

        clock = rearmed + 4 * HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("nor at the limit itself", 1, posted.size)

        clock = rearmed + 4 * HOUR + 60_000L
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("past the new start plus the limit: a new alert", 2, posted.size)
        assertEquals("counted from the new start", rearmed, inserted.last().silentSince)
        assertEquals(listOf(first.id), resolved)
    }

    @Test
    fun `a rule turned back on ends the old lapse in the next background pass, with no read`() = runTest {
        // Alice's lapse is open: her last check-in was nine hours ago.
        rules = listOf(checkInRule(ALICE, hours = 4))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        held[ALICE] = now - 9 * HOUR
        onServer[ALICE] = now - 9 * HOUR
        val monitoring = manager()
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("still open", emptyList<Any>(), resolved)
        assertEquals(1, reads)

        // Turned off and back on: the rule list brings its new start.
        rules = listOf(checkInRule(ALICE, hours = 4).copy(armedAt = now))
        clock = now + 60_000L
        monitoring.evaluateCheckInRules(emptyList(), wake = false)

        assertEquals("a read a minute ago is fresh enough", 1, reads)
        assertEquals(listOf(lapse.id), resolved)
        assertEquals(emptyList<Any>(), posted)
    }

    // ── whom ─────────────────────────────────────────────────────────────────

    @Test
    fun `a member who paused or turned off location sharing is still judged`() = runTest {
        members = listOf(member(ALICE, "Alice", sharing = "paused"), member(BOB, "Bob", sharing = "off"))
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR), checkInRule(BOB, hours = 4, armedAgo = 5 * HOUR))

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(setOf("Alice", "Bob"), posted.map { it.second["user_name"] }.toSet())
    }

    @Test
    fun `this phone's own user is never judged, by a rule of its own or a request`() = runTest {
        members = members + member(SELF, "Me")
        rules = listOf(checkInRule(SELF, hours = 1))
        // Spelled another way, it is still this account.
        val mine = request(SELF.uppercase(), hours = 1)

        manager().evaluateCheckInRules(listOf(mine), wake = true)

        assertEquals(emptyList<Any>(), posted)
        assertEquals("nothing to read for", 0, reads)
    }

    @Test
    fun `this account's own request to be watched is the others' to judge, not this phone's`() = runTest {
        rules = listOf(checkInRule(SELF, hours = 1, self = true))
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(emptyList<Any>(), posted)
        assertEquals(0, reads)
    }

    @Test
    fun `a circle-mate's request is judged as this phone's own rules are`() = runTest {
        onServer[BOB] = now - 30 * HOUR

        manager().evaluateCheckInRules(listOf(request(BOB, hours = 4, armedAt = Instant.ofEpochMilli(now - 5 * HOUR).toString())), wake = true)

        assertEquals(listOf("Bob"), posted.map { it.second["user_name"] })
        assertEquals("ask-$BOB", inserted.single().inactivityAlertId)
    }

    @Test
    fun `a member who turned off alerts about them is not judged, and one who didn't say is`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR), checkInRule(BOB, hours = 4, armedAgo = 5 * HOUR))
        locations = listOf(location(ALICE, allowAlertsOnMe = false))

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf("Bob"), posted.map { it.second["user_name"] })
    }

    @Test
    fun `a member's own request to be watched is their consent, whatever their switch says`() = runTest {
        // Alice turned off alerts about her: this account's rule about her says nothing; her own
        // request to her circle still does, as on the server.
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        locations = listOf(location(ALICE, allowAlertsOnMe = false))

        manager().evaluateCheckInRules(listOf(request(ALICE, hours = 4, armedAt = Instant.ofEpochMilli(now - 5 * HOUR).toString())), wake = true)

        assertEquals(listOf("ask-$ALICE"), inserted.map { it.inactivityAlertId })
    }

    @Test
    fun `a member no longer in the circle here is not judged`() = runTest {
        members = listOf(member(BOB, "Bob"))
        rules = listOf(checkInRule(ALICE, hours = 4))
        onServer[ALICE] = now - 5 * HOUR

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(emptyList<Any>(), posted)
        assertEquals(0, reads)
    }

    // ── the background pass ──────────────────────────────────────────────────

    @Test
    fun `the background pass reads only when what this phone holds says a rule would go off`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 3 * HOUR))
        val monitoring = manager()
        monitoring.evaluateCheckInRules(emptyList(), wake = false)
        assertEquals("nothing to read for", 0, reads)

        // Held: over the limit. The read finds a check-in since the start: nothing goes off.
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        onServer[ALICE] = now - 2 * HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = false)
        assertEquals(1, reads)
        assertEquals(emptyList<Any>(), posted)

        // Held and read: over the limit.
        held.clear()
        onServer.clear()
        monitoring.evaluateCheckInRules(emptyList(), wake = false)
        assertEquals(2, reads)
        assertEquals(1, posted.size)
    }

    @Test
    fun `the background pass never raises an alert from what it held alone`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 5 * HOUR))
        unopened = null

        manager().evaluateCheckInRules(emptyList(), wake = false)

        assertEquals(1, reads)
        assertEquals(emptyList<Any>(), posted)
    }

    @Test
    fun `while a lapse is open the background pass reads at most every half hour`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4))
        lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        held[ALICE] = now - 9 * HOUR
        onServer[ALICE] = now - 9 * HOUR
        val monitoring = manager()

        monitoring.evaluateCheckInRules(emptyList(), wake = false)
        monitoring.evaluateCheckInRules(emptyList(), wake = false)
        assertEquals(1, reads)

        // The wake-up always reads.
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(2, reads)
    }

    @Test
    fun `the background pass ends a lapse from a check-in this phone already holds`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        val monitoring = manager()
        monitoring.evaluateCheckInRules(emptyList(), wake = true) // read just now
        assertEquals(1, reads)

        // The Check-ins screen read Alice's check-in since.
        held[ALICE] = now - HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = false)

        assertEquals(1, reads)
        assertEquals(listOf(lapse.id), resolved)
    }

    // ── each evaluator its own kind ──────────────────────────────────────────

    @Test
    fun `a location rule is not judged by check-ins`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 1, watches = InactivityWatches.LOCATION))
        onServer[ALICE] = now - 5 * HOUR

        manager().evaluateCheckInRules(
            listOf(request(BOB, hours = 1).copy(watches = "location")),
            wake = true,
        )

        assertEquals(emptyList<Any>(), posted)
        assertEquals(0, reads)
    }

    @Test
    fun `a check-in rule is not judged by location updates, nor read for them`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 1))
        // Alice's location is five hours old, and this refresh produced it.
        locations = listOf(location(ALICE, allowAlertsOnMe = null).copy(timestamp = now - 5 * HOUR))
        val monitoring = manager()

        monitoring.evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))
        monitoring.evaluateCircleSelfAlerts(listOf(request(ALICE, hours = 1)), mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(emptyList<Any>(), posted)
        assertEquals("its circle is no location rule's", emptySet<String>(), monitoring.inactivityCircleIds())
        assertEquals(emptyList<Any>(), MonitoringManager.locationSelfAlerts(listOf(request(ALICE))))
        val older = CircleSelfAlertDto(id = "old", userId = BOB, circleId = CIRCLE, timeoutHours = 2)
        assertEquals("an older server's row is a location one", listOf(older), MonitoringManager.locationSelfAlerts(listOf(older)))
    }

    @Test
    fun `a kind this build doesn't know is judged by neither`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 1).copy(watches = "geofence"))
        onServer[ALICE] = now - 5 * HOUR
        locations = listOf(location(ALICE, allowAlertsOnMe = null).copy(timestamp = now - 5 * HOUR))
        val monitoring = manager()

        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        monitoring.evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(emptyList<Any>(), posted)
        assertEquals(emptySet<String>(), monitoring.inactivityCircleIds())
    }

    // ── scheduled rules ──────────────────────────────────────────────────────

    /** 2026-11-24 20:30 UTC, a Tuesday: the scheduled rules' clock. */
    private val tuesday = Instant.parse("2026-11-24T20:30:00Z").toEpochMilli()
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** A scheduled rule about [target] by [due] (minutes after midnight) on [days], in UTC, started [armedAt]. */
    private fun scheduledRule(
        target: String,
        due: Int = 20 * 60,
        days: Int = CheckInSchedule.EVERY_DAY,
        from: Int? = null,
        armedAt: Long = tuesday - 48 * HOUR,
    ) = checkInRule(target).copy(
        armedAt = armedAt, checkInMode = CheckInModes.SCHEDULED, scheduleDays = days, scheduleDueMinute = due,
        scheduleFromMinute = from, scheduleTz = "UTC",
    )

    @Test
    fun `a scheduled rule goes off at its time when no check-in came since the day began, once, in its time's words`() = runTest {
        clock = tuesday
        rules = listOf(scheduledRule(ALICE, due = 20 * 60))
        onServer[ALICE] = at("2026-11-23T23:59:00Z")
        val monitoring = manager()

        monitoring.evaluateCheckInRules(emptyList(), wake = true)

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // The rule's time as set, in this phone's format (CheckInRuleTextTest: "8:00 PM", or "20:00").
        val words = "Alice hasn't checked in by ${CheckInRuleText.time(context, 20 * 60)}."
        assertEquals(words, posted.single().second[AlertHandler.KEY_MISSED_CHECK_IN])
        val event = inserted.single()
        assertEquals("the lapse starts at the deadline", at("2026-11-24T20:00:00Z"), event.silentSince)
        assertEquals("its time as set, for the history's words", 20 * 60, event.dueMinute)
        assertEquals(words, bell.single().body)

        // Once per occurrence.
        clock = tuesday + 3 * HOUR
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(1, posted.size)
    }

    @Test
    fun `a check-in since the window opened keeps it quiet - from the start of the day, or from its from time`() = runTest {
        clock = tuesday
        // Alice checked in at midnight, the window's first moment: it counts.
        rules = listOf(scheduledRule(ALICE))
        onServer[ALICE] = at("2026-11-24T00:00:00Z")
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(emptyList<Any>(), posted)

        // From 6:00 PM: Bob's at 5:59 PM is before it, Alice's at 6:00 PM in it.
        rules = listOf(scheduledRule(ALICE, from = 18 * 60), scheduledRule(BOB, from = 18 * 60))
        onServer[ALICE] = at("2026-11-24T18:00:00Z")
        onServer[BOB] = at("2026-11-24T17:59:00Z")
        manager().evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(listOf("Bob"), posted.map { it.second["user_name"] })
    }

    @Test
    fun `a rule started after the latest deadline waits for the next one`() = runTest {
        clock = tuesday
        val rule = scheduledRule(ALICE, armedAt = at("2026-11-24T20:01:00Z"))
        rules = listOf(rule)
        val monitoring = manager()
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("never about a deadline before it existed", emptyList<Any>(), posted)

        clock = at("2026-11-25T20:00:00Z")
        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals("the next day's, at its own minute", listOf("Alice"), posted.map { it.second["user_name"] })
        assertEquals(at("2026-11-25T20:00:00Z"), inserted.single().silentSince)
    }

    @Test
    fun `a check-in after the deadline ends the missed one, with no back notice and nothing new`() = runTest {
        clock = tuesday
        rules = listOf(scheduledRule(ALICE))
        val lapse = lapse("rule-$ALICE", ALICE, since = at("2026-11-24T20:00:00Z"))
        onServer[ALICE] = at("2026-11-24T20:10:00Z")

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf(lapse.id), resolved)
        assertEquals(listOf("rule-$ALICE" to ALICE), cancelled)
        assertEquals(emptyList<Any>(), posted)
    }

    @Test
    fun `the next occurrence takes over from a missed one - it ends, and the new one goes off when missed too`() = runTest {
        clock = tuesday
        rules = listOf(scheduledRule(ALICE))
        val yesterday = lapse("rule-$ALICE", ALICE, since = at("2026-11-23T20:00:00Z"))

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(listOf(yesterday.id), resolved)
        assertEquals(1, posted.size)
        assertEquals(at("2026-11-24T20:00:00Z"), inserted.single().silentSince)
    }

    @Test
    fun `a weekly rule reads back to its day's window, past the day and a bit`() = runTest {
        clock = tuesday
        // Sundays only: the latest is Sunday 2026-11-22 at 8:00 PM, its window from midnight.
        rules = listOf(scheduledRule(ALICE, days = CheckInSchedule.bit(java.time.DayOfWeek.SUNDAY), armedAt = tuesday - 7 * 24 * HOUR))
        onServer[ALICE] = at("2026-11-21T12:00:00Z")

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(tuesday - at("2026-11-22T00:00:00Z"), windows.single())
        assertTrue(windows.single() > CheckInRepository.RECENT_MS)
        assertEquals("Saturday's check-in was before Sunday's window", 1, posted.size)
    }

    @Test
    fun `a scheduled rule whose schedule doesn't read is judged by nobody`() = runTest {
        clock = tuesday
        rules = listOf(scheduledRule(ALICE).copy(scheduleTz = "Mars/Olympus"), scheduledRule(BOB).copy(scheduleDays = 0))

        manager().evaluateCheckInRules(emptyList(), wake = true)

        assertEquals(emptyList<Any>(), posted)
        assertEquals(0, reads)
    }

    // ── rules no longer judged here ──────────────────────────────────────────

    @Test
    fun `a missed check-in of a rule ended or turned off is taken back, whatever the requests' read`() = runTest {
        // The server turned the one-time rule off at Alice's check-in; it reached this phone off.
        off = listOf(checkInRule(ALICE).copy(isActive = false))
        val lapse = lapse("rule-$ALICE", ALICE, since = now - 9 * HOUR)
        // A location lapse is the location rules' business.
        val location = lapse("rule-$BOB", BOB, since = now - 9 * HOUR).copy(watches = InactivityWatches.LOCATION)
            .also { open["rule-$BOB|$BOB"] = it }

        manager().evaluateCheckInRules(null, wake = true)

        assertEquals(listOf(lapse.id), resolved)
        assertEquals(listOf("rule-$ALICE" to ALICE), cancelled)
        assertEquals(emptyList<Any>(), posted)
        assertTrue(location.id !in resolved)
    }

    @Test
    fun `one of a request that is gone, or a rule deleted here, is taken back only from a list that was read`() = runTest {
        val lapse = lapse("ask-$BOB", BOB, since = now - 9 * HOUR)
        val monitoring = manager()

        monitoring.evaluateCheckInRules(null, wake = true)
        assertEquals("a failed read is not \"the request is gone\"", emptyList<Any>(), resolved)

        monitoring.evaluateCheckInRules(emptyList(), wake = true)
        assertEquals(listOf(lapse.id), resolved)
        assertEquals(listOf("ask-$BOB" to BOB), cancelled)
    }

    @Test
    fun `a missed check-in of a rule still judged stays open`() = runTest {
        rules = listOf(checkInRule(ALICE, hours = 4, armedAgo = 30 * HOUR))
        lapse("rule-$ALICE", ALICE, since = now - 30 * HOUR)
        val ask = request(BOB, hours = 4, armedAt = Instant.ofEpochMilli(now - 30 * HOUR).toString())
        lapse("ask-$BOB", BOB, since = now - 30 * HOUR)

        manager().evaluateCheckInRules(listOf(ask), wake = true)

        assertEquals(emptyList<Any>(), resolved)
        assertEquals(emptyList<Any>(), posted)
    }

    @Test
    fun `the words, word for word`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals("Bo hasn't checked in for 1 hour.", missedCheckInWords(context, "Bo", 1))
        assertEquals("Bo hasn't checked in for 24 hours.", missedCheckInWords(context, "Bo", 24))
        assertEquals("A member hasn't checked in for 4 hours.", missedCheckInWords(context, " ", 4))
        assertEquals("Missed check-in", context.getString(com.pingolin.track.R.string.missed_check_in_title))
        assertFalse(AlertHandler.TYPE_MISSED_CHECK_IN == AlertHandler.TYPE_INACTIVITY)
        assertEquals("missed_check_in", AlertHandler.TYPE_MISSED_CHECK_IN)
    }
}
