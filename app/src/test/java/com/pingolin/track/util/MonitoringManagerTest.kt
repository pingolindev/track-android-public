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

import com.pingolin.track.data.local.dao.AlertRuleDao
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.InactivityAlertDao
import com.pingolin.track.data.local.dao.LocalBatteryEventDao
import com.pingolin.track.data.local.dao.LocalInactivityEventDao
import com.pingolin.track.data.local.dao.LocationDao
import com.pingolin.track.data.local.dao.MemberNotSharingSeenDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.AlertRuleEntity
import com.pingolin.track.data.local.entity.CircleMemberEntity
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.data.local.entity.LocalBatteryEventEntity
import com.pingolin.track.data.local.entity.LocalInactivityEventEntity
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.MemberNotSharingSeenEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleSelfAlertDto
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.ui.circles.SharingStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.EncryptedData

/**
 * The per-member refresh gate on MonitoringManager's three alert evaluators.
 *
 * WHAT THESE GUARD
 *   The local `locations` table is a CACHE of last-known positions, not a
 *   snapshot of one refresh. A member absent from a response keeps a row whose
 *   timestamp goes on ageing and whose batteryLevel goes on being stale, and an
 *   evaluator that judged those rows would misjudge them. The most reachable
 *   cause of absence is a member who PAUSED sharing — the server filters them
 *   out of the response — so pausing, the control a person reaches for when
 *   they do not want their whereabouts known, would get their circle told they
 *   might need attention.
 *
 *   The guard is one line in each evaluator: `if (x !in refreshed) continue`.
 *   LocationFetchResultGateTests covers the two derived signals those lines
 *   consume (`refreshedUserIds` and `isComplete`); this file covers the three
 *   consumers. The iOS app pins the same rule as a pure function in
 *   CircleManager.shouldJudge.
 *
 * WHY EACH ABSENT-MEMBER TEST IS PAIRED
 *   Every "absent member is not judged" test below has a sibling that differs in
 *   exactly ONE value — the refreshed set — and asserts the alert DOES fire.
 *   Without the pair, an absent-member test passes for any reason at all
 *   (no rule, no member row, no location, a null battery level), and would stay
 *   green with the guard deleted.
 *
 * SEAM
 *   MonitoringManager is a @Singleton over thirteen collaborators, but none of
 *   the three guard paths reaches `cryptoManager` before its `!in refreshed`
 *   check: all three get there through DAO reads plus `sessionManager.getUserId()`.
 *   `cryptoManager` is stubbed here only so the FIRING path can run to
 *   completion — `reportAlertEvent` seals the copy for the account's other
 *   devices — and no assertion depends on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// Robolectric for the inactivity words: they say a clock time (DateUtils).
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34], application = android.app.Application::class)
class MonitoringManagerTest {

    private companion object {
        const val SELF = "self-user"
        const val ALICE = "member-alice"
        const val BOB = "member-bob"
        const val CIRCLE = "circle-1"

        /** A second circle, to show the refresh lookup is per-circle. */
        const val OTHER_CIRCLE = "circle-2"

        const val HOUR_MS = 3_600_000L
    }

    private lateinit var tracksApi: TrackApi
    private lateinit var circleDao: CircleDao
    private lateinit var circleMemberDao: CircleMemberDao
    private lateinit var sessionManager: SessionManager
    private lateinit var alertHandler: AlertHandler
    private lateinit var inactivityAlertDao: InactivityAlertDao
    private lateinit var locationDao: LocationDao
    private lateinit var notificationDao: NotificationDao
    private lateinit var localInactivityEventDao: LocalInactivityEventDao
    private lateinit var alertRuleDao: AlertRuleDao
    private lateinit var localBatteryEventDao: LocalBatteryEventDao
    private lateinit var privacySettingDao: PrivacySettingDao
    private lateinit var cryptoManager: uniffi.pingolin_mobile.CryptoManager

    /** Every alert AlertHandler was asked to post, in order. Empty == nobody judged. */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()

    /** The device-local firing records — the in-app bell / history side of an alert. */
    private val batteryEvents = mutableListOf<LocalBatteryEventEntity>()
    private val inactivityEvents = mutableListOf<LocalInactivityEventEntity>()
    private val notifications = mutableListOf<NotificationEntity>()

    /** Ids of lapses resolved, and (ruleId, userId) of notifications cancelled. */
    private val resolvedEventIds = mutableListOf<String>()
    private val cancelled = mutableListOf<Pair<String, String>>()

    /** When this phone last saw each member not sharing: "circle|user" to the note. */
    private val notSharingNotes = mutableMapOf<String, Long>()
    private val notSharingSeenDao = object : MemberNotSharingSeenDao {
        override suspend fun get(circleId: String, userId: String): Long? = notSharingNotes["$circleId|$userId"]
        override suspend fun put(row: MemberNotSharingSeenEntity) {
            notSharingNotes["${row.circleId}|${row.userId}"] = row.seenUntil
        }
        override suspend fun deleteOlderThan(minEpochMillis: Long) {
            notSharingNotes.values.removeIf { it < minEpochMillis }
        }
    }

    @Before
    fun setup() {
        tracksApi = mockk()
        circleDao = mockk()
        circleMemberDao = mockk()
        sessionManager = mockk()
        alertHandler = mockk()
        inactivityAlertDao = mockk()
        locationDao = mockk()
        notificationDao = mockk()
        localInactivityEventDao = mockk()
        alertRuleDao = mockk()
        localBatteryEventDao = mockk()
        privacySettingDao = mockk()
        cryptoManager = mockk()

        every { sessionManager.getUserId() } returns SELF

        val typeSlot = slot<String>()
        val dataSlot = slot<Map<String, String>>()
        every { alertHandler.handleAlert(capture(typeSlot), capture(dataSlot)) } answers {
            posted += typeSlot.captured to dataSlot.captured
        }
        every { alertHandler.inactivityDedupeKey(any(), any()) } returns "inact-key"
        every { alertHandler.lowBatteryDedupeKey(any(), any()) } returns "batt-key"
        every { alertHandler.cancelInactivity(any(), any()) } answers {
            cancelled += firstArg<String>() to secondArg<String>()
        }
        every { alertHandler.cancelLowBattery(any(), any()) } answers {
            cancelled += firstArg<String>() to secondArg<String>()
        }

        // Nothing already firing unless a test says so: an unresolved local event
        // is the evaluators' "already fired" marker.
        coEvery { localBatteryEventDao.getOpen(any(), any()) } returns null
        coEvery { localInactivityEventDao.getOpen(any(), any()) } returns null
        coEvery { localBatteryEventDao.insert(any()) } answers {
            batteryEvents += firstArg<LocalBatteryEventEntity>()
        }
        coEvery { localInactivityEventDao.insert(any()) } answers {
            inactivityEvents += firstArg<LocalInactivityEventEntity>()
        }
        coEvery { localBatteryEventDao.resolve(any(), any()) } answers {
            resolvedEventIds += firstArg<String>()
        }
        coEvery { localInactivityEventDao.resolve(any(), any()) } answers {
            resolvedEventIds += firstArg<String>()
        }
        coEvery { notificationDao.insert(any()) } answers {
            notifications += firstArg<NotificationEntity>()
        }

        // The sealed copy for the account's other devices. Stubbed so the firing
        // path completes; nothing here is asserted on.
        every { cryptoManager.encryptData(any(), any()) } returns
            EncryptedData(nonce = "nonce", ciphertext = "cipher")
        coEvery { tracksApi.createAlertEvent(any()) } returns
            Response.success(ApiResponse(success = true, data = "ok", error = null))

        // No rules, members or locations by default; each test installs its own.
        coEvery { alertRuleDao.getActiveByType(any()) } returns emptyList()
        coEvery { inactivityAlertDao.getActive() } returns emptyList()
        coEvery { circleMemberDao.getByCircle(any()) } returns emptyList()
        every { locationDao.observeLatestByCircle(any()) } returns flowOf(emptyList())
    }

    private fun manager() = MonitoringManager(
        // Only the inactivity words read it (InactivityWordsTest checks them).
        context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
        tracksApi = tracksApi,
        circleDao = circleDao,
        circleMemberDao = circleMemberDao,
        sessionManager = sessionManager,
        alertHandler = alertHandler,
        inactivityAlertDao = inactivityAlertDao,
        locationDao = locationDao,
        notificationDao = notificationDao,
        localInactivityEventDao = localInactivityEventDao,
        memberNotSharingSeenDao = notSharingSeenDao,
        alertRuleDao = alertRuleDao,
        localBatteryEventDao = localBatteryEventDao,
        privacySettingDao = privacySettingDao,
        cryptoManager = cryptoManager,
    )

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun member(userId: String, circleId: String = CIRCLE) = CircleMemberEntity(
        circleId = circleId,
        userId = userId,
        displayName = "name-$userId",
        role = "member",
        joinedAt = 0L,
    )

    /**
     * A cached row. [ageMs] is how long ago it was reported and [batteryLevel] what
     * it carried — the two fields the evaluators read, and deliberately independent
     * so a test can hold one fixed while moving the other.
     */
    private fun location(
        userId: String,
        ageMs: Long,
        batteryLevel: Int?,
        circleId: String = CIRCLE,
    ) = LocationEntity(
        circleId = circleId,
        userId = userId,
        deviceId = "device-$userId",
        latitude = 0.0,
        longitude = 0.0,
        altitude = null,
        speed = null,
        heading = null,
        accuracy = null,
        batteryLevel = batteryLevel,
        timestamp = System.currentTimeMillis() - ageMs,
    )

    private fun batteryRule(target: String, threshold: Int, circleId: String = CIRCLE) =
        AlertRuleEntity(
            id = "rule-batt-$circleId-$target",
            userId = SELF,
            circleId = circleId,
            targetUserId = target,
            ruleType = AlertRuleRepository.RULE_TYPE_LOW_BATTERY,
            batteryThreshold = threshold,
            dayBucket = 0,
        )

    private fun inactivityRule(
        target: String,
        timeoutHours: Int = 1,
        circleId: String = CIRCLE,
        notifyOnReturn: Boolean = false,
    ) = InactivityAlertEntity(
        id = "rule-inact-$circleId-$target",
        userId = SELF,
        circleId = circleId,
        targetUserId = target,
        timeoutHours = timeoutHours,
        notifyOnReturn = notifyOnReturn,
        dayBucket = 0,
    )

    private fun selfAlert(
        userId: String,
        timeoutHours: Int = 1,
        circleId: String = CIRCLE,
        notifyOnReturn: Boolean = false,
    ) = CircleSelfAlertDto(
        id = "self-alert-$circleId-$userId",
        userId = userId,
        circleId = circleId,
        timeoutHours = timeoutHours,
        notifyOnReturn = notifyOnReturn,
    )

    private fun openInactivityEvent(ruleId: String, userId: String) = LocalInactivityEventEntity(
        id = "local-inact:$ruleId:$userId:1",
        inactivityAlertId = ruleId,
        circleId = CIRCLE,
        targetUserId = userId,
        targetDisplayName = "name-$userId",
        timeoutHours = 1,
        inactiveSeconds = 7200L,
        triggeredAt = 1L,
        triggeredDayBucket = 0,
    )

    /** The user_name of every alert posted, in order. */
    private fun postedNames() = posted.map { it.second["user_name"] }

    // ── low battery: evaluateLowBattery ─────────────────────────────────────

    /**
     * The positive control for the battery guard. Alice is flat AND this refresh
     * produced a location for her, so she is judged.
     */
    @Test
    fun `battery a member this refresh produced a location for is judged`() = runTest {
        coEvery { alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY) } returns
            listOf(batteryRule(ALICE, threshold = 20))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = 5)))

        manager().evaluateLowBattery(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(listOf(AlertHandler.TYPE_LOW_BATTERY), posted.map { it.first })
        assertEquals("5", posted.single().second["battery_level"])
        assertEquals(1, batteryEvents.size)
    }

    /**
     * THE PRIVACY PROPERTY, battery half. Identical to the test above in every
     * value but one: this refresh named Bob, not Alice. Alice's cached row still
     * says 5%, and it must be read as nothing at all.
     *
     * THE FIELD THIS SITE GUARDS IS `?.batteryLevel`, NOT `?.timestamp`. The row
     * below is FRESH (ageMs = 0) and carries a NON-NULL level below the
     * threshold, which is what makes this test exercise the guard. A row copied
     * from an inactivity fixture — stale, `batteryLevel = null`, as
     * LocationFetchResultGateTests builds them — is skipped one line later by
     * `latest?.batteryLevel ?: continue` and would stay green with the guard
     * deleted.
     */
    @Test
    fun `battery a member absent from the refresh is not judged at all`() = runTest {
        coEvery { alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY) } returns
            listOf(batteryRule(ALICE, threshold = 20))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE), member(BOB))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = 5)))

        // A COMPLETE refresh that simply did not mention Alice: she is paused,
        // has never shared, or was filtered server-side. All three mean silence.
        manager().evaluateLowBattery(mapOf(CIRCLE to setOf(BOB)))

        assertTrue(
            "a member this refresh said nothing about must not be judged, however " +
                "flat their cached battery reading is — got $posted",
            posted.isEmpty(),
        )
        assertTrue("no local battery event may be written either", batteryEvents.isEmpty())
        assertTrue("and no bell row", notifications.isEmpty())
    }

    /**
     * The per-member set and the circle-level counters are INDEPENDENT. This
     * refresh was complete and produced a location for somebody, so no
     * circle-level gate stops it; only Alice's absence from the set does.
     *
     * Bob is watched by his own rule and IS in the set, so the pass is doing real
     * work: it fires for him in the same call it stays silent for her.
     */
    @Test
    fun `battery an absent member is not judged even when the refresh was complete`() = runTest {
        coEvery { alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY) } returns
            listOf(batteryRule(ALICE, threshold = 20), batteryRule(BOB, threshold = 20))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE), member(BOB))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(
                listOf(
                    location(ALICE, ageMs = 0L, batteryLevel = 5),
                    location(BOB, ageMs = 0L, batteryLevel = 5),
                ),
            )

        manager().evaluateLowBattery(mapOf(CIRCLE to setOf(BOB)))

        assertEquals(listOf("name-$BOB"), postedNames())
    }

    /**
     * THE CIRCLE-LEVEL GATE, battery half — "judges nobody in that circle, even
     * a member who DID come back".
     *
     * Circle-2's refresh had a key-missing or undecryptable row, so the caller
     * left it out of the map entirely: LocationTrackingService writes
     * `refreshed[cid]` only `if (result.isComplete)`. One unopened row
     * disqualifies the whole refresh, including for the members it did decrypt.
     *
     * ALICE IS THE SUBJECT IN BOTH CIRCLES, and that is the point. She came back
     * in circle-1, so a global "did anyone refresh" set contains her — and
     * judging her in circle-2 on that basis reads her stale circle-2 row. Only a
     * PER-CIRCLE lookup stops it. A test that made circle-2 about somebody who
     * came back nowhere would prove nothing here: the per-member guard one line
     * further down already stops that member, so removing this line would change
     * nothing observable.
     */
    @Test
    fun `battery a circle whose refresh did not decrypt cleanly judges nobody in it`() = runTest {
        coEvery { alertRuleDao.getActiveByType(AlertRuleRepository.RULE_TYPE_LOW_BATTERY) } returns
            listOf(
                batteryRule(ALICE, threshold = 20),
                batteryRule(ALICE, threshold = 20, circleId = OTHER_CIRCLE),
            )
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        coEvery { circleMemberDao.getByCircle(OTHER_CIRCLE) } returns
            listOf(member(ALICE, circleId = OTHER_CIRCLE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = 5)))
        every { locationDao.observeLatestByCircle(OTHER_CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = 5, circleId = OTHER_CIRCLE)))

        manager().evaluateLowBattery(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals("circle-2 must not be judged at all — got $posted", 1, posted.size)
        assertEquals(CIRCLE, batteryEvents.single().circleId)
    }

    // ── inactivity: evaluateInactivity ──────────────────────────────────────

    /** The positive control for the inactivity guard. */
    @Test
    fun `inactivity a member this refresh produced a location for is judged`() = runTest {
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(listOf(AlertHandler.TYPE_OFFLINE), posted.map { it.first })
        assertEquals(1, inactivityEvents.size)
    }

    /**
     * THE PRIVACY PROPERTY, inactivity half. Identical to the test above but for
     * the refreshed set. Alice's cached row is five hours old against a one-hour
     * timeout and must not be read as silence.
     */
    @Test
    fun `inactivity a member absent from the refresh is not judged at all`() = runTest {
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE), member(BOB))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(BOB)))

        assertTrue(
            "pausing must not announce that you may need attention — got $posted",
            posted.isEmpty(),
        )
        assertTrue("no local inactivity event may be written", inactivityEvents.isEmpty())
        assertTrue("and no bell row", notifications.isEmpty())
    }

    /**
     * The guard also covers the RECOVERY branch, not just the firing one. An
     * absent member with a lapse already open must not be resolved, un-notified
     * or announced as back online on the strength of a cached row: the client
     * has not heard from them, so nothing about them has changed.
     */
    @Test
    fun `inactivity an absent member's open lapse is not resolved from a cached row`() = runTest {
        val rule = inactivityRule(ALICE, notifyOnReturn = true)
        coEvery { inactivityAlertDao.getActive() } returns listOf(rule)
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE), member(BOB))
        // Fresh cached row: under the timeout, so the recovery branch is the one
        // that would run if the guard were not there.
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = null)))
        coEvery { localInactivityEventDao.getOpen(rule.id, ALICE) } returns
            openInactivityEvent(rule.id, ALICE)

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(BOB)))

        assertTrue("no lapse may be resolved — got $resolvedEventIds", resolvedEventIds.isEmpty())
        assertTrue("no notification may be cancelled — got $cancelled", cancelled.isEmpty())
        assertTrue("and no back-online alert — got $posted", posted.isEmpty())
    }

    /**
     * THE CIRCLE-LEVEL GATE, inactivity half. Alice is watched in both circles and
     * came back in circle-1 only; circle-2's refresh did not decrypt, so nobody in
     * it may be judged — her included. See the battery sibling for why the subject
     * has to be a member who came back somewhere.
     */
    @Test
    fun `inactivity a circle whose refresh did not decrypt cleanly judges nobody in it`() =
        runTest {
            coEvery { inactivityAlertDao.getActive() } returns
                listOf(inactivityRule(ALICE), inactivityRule(ALICE, circleId = OTHER_CIRCLE))
            coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
            coEvery { circleMemberDao.getByCircle(OTHER_CIRCLE) } returns
                listOf(member(ALICE, circleId = OTHER_CIRCLE))
            every { locationDao.observeLatestByCircle(CIRCLE) } returns
                flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
            every { locationDao.observeLatestByCircle(OTHER_CIRCLE) } returns
                flowOf(
                    listOf(
                        location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null, circleId = OTHER_CIRCLE),
                    ),
                )

            manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

            assertEquals("circle-2 must not be judged at all — got $posted", 1, posted.size)
            assertEquals(CIRCLE, inactivityEvents.single().circleId)
        }

    // ── circle self-alerts: evaluateCircleSelfAlerts ────────────────────────

    /** The positive control for the circle-self guard. */
    @Test
    fun `circle self alert a member this refresh produced a location for is judged`() = runTest {
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateCircleSelfAlerts(
            listOf(selfAlert(ALICE)),
            mapOf(CIRCLE to setOf(ALICE)),
        )

        assertEquals(listOf(AlertHandler.TYPE_OFFLINE), posted.map { it.first })
        assertEquals(1, inactivityEvents.size)
    }

    /**
     * THE PRIVACY PROPERTY, circle-self half — and the sharpest of the three.
     * These rules are other people's standing requests to be watched, so the
     * person who paused is exactly the person the rule is about: without the
     * guard, pausing is what triggers the alert.
     */
    @Test
    fun `circle self alert a member absent from the refresh is not judged at all`() = runTest {
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE), member(BOB))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateCircleSelfAlerts(
            listOf(selfAlert(ALICE)),
            mapOf(CIRCLE to setOf(BOB)),
        )

        assertTrue(
            "the subject of a watch-me request who paused must not be announced — got $posted",
            posted.isEmpty(),
        )
        assertTrue("no local inactivity event may be written", inactivityEvents.isEmpty())
        assertTrue("and no bell row", notifications.isEmpty())
    }

    /**
     * The per-member set is independent of the circle-level counters here too:
     * a complete refresh that produced Bob still judges nobody about Alice.
     */
    @Test
    fun `circle self alert an absent member is not judged even when the refresh was complete`() =
        runTest {
            coEvery { circleMemberDao.getByCircle(CIRCLE) } returns
                listOf(member(ALICE), member(BOB))
            every { locationDao.observeLatestByCircle(CIRCLE) } returns
                flowOf(
                    listOf(
                        location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null),
                        location(BOB, ageMs = 5 * HOUR_MS, batteryLevel = null),
                    ),
                )

            manager().evaluateCircleSelfAlerts(
                listOf(selfAlert(ALICE), selfAlert(BOB)),
                mapOf(CIRCLE to setOf(BOB)),
            )

            assertEquals(listOf("name-$BOB"), postedNames())
        }

    /**
     * THE CIRCLE-LEVEL GATE, circle-self half. Alice has a standing watch-me
     * request in both circles and came back in circle-1 only; circle-2's refresh
     * did not decrypt, so nobody in it may be judged — her included. See the
     * battery sibling for why the subject has to be a member who came back
     * somewhere.
     */
    @Test
    fun `circle self alert a circle whose refresh did not decrypt cleanly judges nobody in it`() =
        runTest {
            coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
            coEvery { circleMemberDao.getByCircle(OTHER_CIRCLE) } returns
                listOf(member(ALICE, circleId = OTHER_CIRCLE))
            every { locationDao.observeLatestByCircle(CIRCLE) } returns
                flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
            every { locationDao.observeLatestByCircle(OTHER_CIRCLE) } returns
                flowOf(
                    listOf(
                        location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null, circleId = OTHER_CIRCLE),
                    ),
                )

            manager().evaluateCircleSelfAlerts(
                listOf(selfAlert(ALICE), selfAlert(ALICE, circleId = OTHER_CIRCLE)),
                mapOf(CIRCLE to setOf(ALICE)),
            )

            assertEquals("circle-2 must not be judged at all — got $posted", 1, posted.size)
            assertEquals(CIRCLE, inactivityEvents.single().circleId)
        }

    // ── circle self-alerts: the background pass's 30-minute cache ─────────────

    private fun selfAlertsOk(vararg alerts: CircleSelfAlertDto) =
        Response.success(ApiResponse(success = true, data = alerts.toList(), error = null))

    @Test
    fun `the background pass reuses a fresh self-alerts list instead of refetching`() = runTest {
        coEvery { tracksApi.getCircleSelfAlerts() } returns selfAlertsOk(selfAlert("peer"))
        val m = manager()
        val now = System.currentTimeMillis()

        val first = m.fetchCircleSelfAlertsCached(nowMs = now)
        val second = m.fetchCircleSelfAlertsCached(nowMs = now + 25 * 60_000L)

        assertEquals(1, first.size)
        assertEquals(first, second)
        coVerify(exactly = 1) { tracksApi.getCircleSelfAlerts() }
    }

    @Test
    fun `the cache expires after thirty minutes`() = runTest {
        coEvery { tracksApi.getCircleSelfAlerts() } returns selfAlertsOk(selfAlert("peer"))
        val m = manager()
        val now = System.currentTimeMillis()

        m.fetchCircleSelfAlertsCached(nowMs = now)
        // A minute past expiry, not a millisecond: the cache is stamped with the clock when
        // the fetch returns, which may be a few milliseconds after `now` on a busy machine.
        m.fetchCircleSelfAlertsCached(nowMs = now + MonitoringManager.SELF_ALERTS_CACHE_MS + 60_000)

        coVerify(exactly = 2) { tracksApi.getCircleSelfAlerts() }
    }

    @Test
    fun `a failed fetch is never cached as an empty list`() = runTest {
        coEvery { tracksApi.getCircleSelfAlerts() } throws java.io.IOException("offline")
        val m = manager()

        assertTrue(m.fetchCircleSelfAlertsCached().isEmpty())
        coEvery { tracksApi.getCircleSelfAlerts() } returns selfAlertsOk(selfAlert("peer"))
        // The next pass asks again rather than trusting "nobody asked to be watched".
        assertEquals(1, m.fetchCircleSelfAlertsCached().size)
        coVerify(exactly = 2) { tracksApi.getCircleSelfAlerts() }
    }

    @Test
    fun `the uncached push path refreshes what the pass sees`() = runTest {
        coEvery { tracksApi.getCircleSelfAlerts() } returns selfAlertsOk()
        val m = manager()
        assertTrue(m.fetchCircleSelfAlertsCached().isEmpty())

        coEvery { tracksApi.getCircleSelfAlerts() } returns selfAlertsOk(selfAlert("peer"))
        m.fetchCircleSelfAlerts() // AlertsCheckWorker, on an alerts_check push

        assertEquals(1, m.fetchCircleSelfAlertsCached().size)
        coVerify(exactly = 2) { tracksApi.getCircleSelfAlerts() }
    }

    @Test
    fun `cache freshness is a strict window from a real timestamp`() {
        val window = 30 * 60_000L
        assertFalse(MonitoringManager.selfAlertsCacheFresh(cachedAtMs = 0L, nowMs = 1_000L, maxAgeMs = window))
        assertTrue(MonitoringManager.selfAlertsCacheFresh(cachedAtMs = 1_000L, nowMs = 1_000L + window - 1, maxAgeMs = window))
        assertFalse(MonitoringManager.selfAlertsCacheFresh(cachedAtMs = 1_000L, nowMs = 1_000L + window, maxAgeMs = window))
        // A clock that went backwards is not "fresh".
        assertFalse(MonitoringManager.selfAlertsCacheFresh(cachedAtMs = 5_000L, nowMs = 1_000L, maxAgeMs = window))
    }

    // ── a stretch of not sharing ─────────────────────────────────────────────────
    //
    // A member paused, or with real-time sharing off, in the circle is never judged, whatever
    // position this phone last had. Once that stretch ends, their silence counts from its end,
    // as the server counts it: the later of their last-seen time and the latest stretch's end.
    // Otherwise a timed pause that runs out on a still phone is "hasn't shared since …" about
    // the pause itself.

    private fun noteOf(userId: String, circleId: String = CIRCLE) = notSharingNotes["$circleId|$userId"]

    @Test
    fun `inactivity a member the roster shows paused is not judged even when the refresh has them, and is noted`() = runTest {
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE).copy(locationSharing = "paused"))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        val before = System.currentTimeMillis()

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("nothing goes off about a paused member, got $posted", posted.isEmpty())
        assertTrue("and this phone notes that it saw her paused", (noteOf(ALICE) ?: 0L) >= before)
    }

    @Test
    fun `inactivity a member with real-time sharing off is not judged either`() = runTest {
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE).copy(locationSharing = "off"))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("got $posted", posted.isEmpty())
        assertTrue(noteOf(ALICE) != null)
    }

    @Test
    fun `inactivity a timed pause that ran out on a still phone counts their silence from its end`() = runTest {
        // Her last fix is 5 hours old against a 1-hour timeout, but this phone saw her paused until
        // half an hour ago: half an hour of silence, not 5 hours.
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - HOUR_MS / 2

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("no alert about the pause itself, got $posted", posted.isEmpty())
    }

    @Test
    fun `inactivity a stretch of not sharing that ended longer ago than the timeout is silence, said from the last fix`() = runTest {
        // The pair of the test above: the stretch ended 2 hours ago.
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - 2 * HOUR_MS

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(listOf(AlertHandler.TYPE_OFFLINE), posted.map { it.first })
        // The words say when she last shared: her last fix, 5 hours ago.
        assertTrue(inactivityEvents.single().inactiveSeconds >= 5 * 3600 - 60)
    }

    @Test
    fun `inactivity a pause's end with no new location leaves an open lapse open`() = runTest {
        val rule = inactivityRule(ALICE, notifyOnReturn = true)
        coEvery { inactivityAlertDao.getActive() } returns listOf(rule)
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        coEvery { localInactivityEventDao.getOpen(rule.id, ALICE) } returns openInactivityEvent(rule.id, ALICE)
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - 10 * 60_000L

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("no lapse resolved, got $resolvedEventIds", resolvedEventIds.isEmpty())
        assertTrue("no notification cancelled, got $cancelled", cancelled.isEmpty())
        assertTrue("and no back-online alert, got $posted", posted.isEmpty())
    }

    @Test
    fun `inactivity a new location resolves an open lapse`() = runTest {
        // The pair of the test above: a fix just now.
        val rule = inactivityRule(ALICE, notifyOnReturn = true)
        coEvery { inactivityAlertDao.getActive() } returns listOf(rule)
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 0L, batteryLevel = null)))
        coEvery { localInactivityEventDao.getOpen(rule.id, ALICE) } returns openInactivityEvent(rule.id, ALICE)
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - 10 * 60_000L

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertEquals(1, resolvedEventIds.size)
        assertEquals(listOf(AlertHandler.TYPE_BACK_ONLINE), posted.map { it.first })
    }

    @Test
    fun `inactivity an early resume brings a planned pause end back to now`() = runTest {
        // Seen paused until 8 hours from now; the roster shows her sharing now, so her stretch
        // ended by now, and her silence mustn't wait for the planned end.
        coEvery { inactivityAlertDao.getActive() } returns listOf(inactivityRule(ALICE))
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() + 8 * HOUR_MS

        manager().evaluateInactivity(mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("got ${noteOf(ALICE)}", noteOf(ALICE)!! <= System.currentTimeMillis())
    }

    @Test
    fun `inactivity a note older than any timeout is dropped`() = runTest {
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - MonitoringManager.NOT_SHARING_KEEP_MS - HOUR_MS

        manager().evaluateInactivity(mapOf(CIRCLE to emptySet()))

        assertNull(noteOf(ALICE))
    }

    @Test
    fun `circle self alert a member the roster shows not sharing is not judged, and is noted`() = runTest {
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns
            listOf(member(ALICE).copy(locationSharing = "paused", sharingPausedUntil = System.currentTimeMillis() + HOUR_MS))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))

        manager().evaluateCircleSelfAlerts(listOf(selfAlert(ALICE)), mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("got $posted", posted.isEmpty())
        // A timed pause notes its planned end.
        assertTrue((noteOf(ALICE) ?: 0L) > System.currentTimeMillis() + HOUR_MS / 2)
    }

    @Test
    fun `circle self alert a timed pause that ran out on a still phone counts their silence from its end`() = runTest {
        coEvery { circleMemberDao.getByCircle(CIRCLE) } returns listOf(member(ALICE))
        every { locationDao.observeLatestByCircle(CIRCLE) } returns
            flowOf(listOf(location(ALICE, ageMs = 5 * HOUR_MS, batteryLevel = null)))
        notSharingNotes["$CIRCLE|$ALICE"] = System.currentTimeMillis() - HOUR_MS / 2

        manager().evaluateCircleSelfAlerts(listOf(selfAlert(ALICE)), mapOf(CIRCLE to setOf(ALICE)))

        assertTrue("got $posted", posted.isEmpty())
    }

    @Test
    fun `silence starts at the later of the last fix and the noted stretch, never after now`() {
        assertEquals(100L, MonitoringManager.activeSince(lastSeenMs = 100L, notSharingSeenMs = null, nowMs = 1_000L))
        assertEquals(500L, MonitoringManager.activeSince(lastSeenMs = 100L, notSharingSeenMs = 500L, nowMs = 1_000L))
        assertEquals(700L, MonitoringManager.activeSince(lastSeenMs = 700L, notSharingSeenMs = 500L, nowMs = 1_000L))
        assertEquals(1_000L, MonitoringManager.activeSince(lastSeenMs = 100L, notSharingSeenMs = 5_000L, nowMs = 1_000L))
    }

    @Test
    fun `what a stretch of not sharing notes`() {
        val now = 10_000L
        // Off, or paused with no end: now.
        assertEquals(now, MonitoringManager.notSharingNote(null, SharingStatus.Off, now))
        assertEquals(now, MonitoringManager.notSharingNote(null, SharingStatus.Paused(null), now))
        // A timed pause: its planned end, being later.
        assertEquals(now + 500, MonitoringManager.notSharingNote(null, SharingStatus.Paused(now + 500), now))
        // While not sharing, never earlier than it was.
        assertNull(MonitoringManager.notSharingNote(now + 500, SharingStatus.Off, now))
        assertEquals(now, MonitoringManager.notSharingNote(now - 1, SharingStatus.Off, now))
        // Sharing: nothing new, but a planned end still ahead comes back to now.
        assertNull(MonitoringManager.notSharingNote(null, SharingStatus.On, now))
        assertNull(MonitoringManager.notSharingNote(now - 1, SharingStatus.On, now))
        assertEquals(now, MonitoringManager.notSharingNote(now + 500, SharingStatus.On, now))
    }
}
