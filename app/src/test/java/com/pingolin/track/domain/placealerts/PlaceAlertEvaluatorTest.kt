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

package com.pingolin.track.domain.placealerts

import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceAlertEventDao
import com.pingolin.track.data.local.dao.PlaceAlertMemberStateDao
import com.pingolin.track.data.local.dao.PlaceAlertShownDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceAlertEventEntity
import com.pingolin.track.data.local.entity.PlaceAlertMemberStateEntity
import com.pingolin.track.data.local.entity.PlaceAlertShownEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end behaviour of the subscriber-side evaluator against in-memory Room
 * fakes. The fakes (rather than mocks) matter here: the state table and the
 * de-duplication ledger ARE the mechanism under test, and "survives a restart"
 * is expressed as "rebuild the evaluator over the same tables".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceAlertEvaluatorTest {

    private companion object {
        const val PLACE_ID = "place-home"
        const val ME = "user-me"
        // A real account id: the shade's table pairs canonical ids only (AccountIds: a non-UUID is no one).
        const val ALICE = "a11ce000-0000-4000-8000-00000000a11c"
        const val BOB = "b0b00000-0000-4000-8000-000000000b0b"
        const val CIRCLE_ID = "circle-1"
        val NOW get() = System.currentTimeMillis()
    }

    // ===== in-memory fakes for the member-state and ledger tables =====

    private class FakeStateDao : PlaceAlertMemberStateDao {
        val rows = mutableMapOf<Pair<String, String>, PlaceAlertMemberStateEntity>()
        override suspend fun get(placeId: String, userId: String) = rows[placeId to userId]
        override suspend fun upsert(state: PlaceAlertMemberStateEntity) {
            rows[state.placeId to state.userId] = state
        }
        override suspend fun deleteByPlace(placeId: String) {
            rows.keys.filter { it.first == placeId }.forEach { rows.remove(it) }
        }
        override suspend fun deleteAll() = rows.clear()
    }

    private class FakeEventDao : PlaceAlertEventDao {
        val rows = linkedMapOf<String, PlaceAlertEventEntity>()
        /** INSERT OR IGNORE semantics: -1 when the crossing id is already claimed. */
        override suspend fun claim(event: PlaceAlertEventEntity): Long {
            if (rows.containsKey(event.id)) return -1L
            rows[event.id] = event
            return rows.size.toLong()
        }
        override suspend fun countSurfacedSince(
            placeId: String,
            userId: String,
            transition: String,
            fromSource: String,
            since: Long
        ): Int = rows.values.count {
            it.placeId == placeId && it.userId == userId &&
                it.transition == transition && it.source == fromSource &&
                !it.silent && it.crossedAt >= since
        }
        override fun observeRecent(): Flow<List<PlaceAlertEventEntity>> = flowOf(rows.values.toList())
        override suspend fun deleteOlderThan(minEpochMillis: Long) {
            rows.values.filter { it.crossedAt < minEpochMillis }.forEach { rows.remove(it.id) }
        }
        override suspend fun deleteAll() = rows.clear()
    }

    /** The shade's table, in memory: INSERT OR IGNORE, as Room's. */
    private class FakeShownDao : PlaceAlertShownDao {
        val rows = linkedMapOf<String, PlaceAlertShownEntity>()
        override suspend fun get(notificationId: String) = rows[notificationId]
        override suspend fun insert(row: PlaceAlertShownEntity): Long {
            if (rows.containsKey(row.notificationId)) return -1L
            rows[row.notificationId] = row
            return rows.size.toLong()
        }
        override suspend fun unpaired(kind: String, placeId: String, userId: String) =
            rows.values.filter { it.kind == kind && it.placeId == placeId && it.userId == userId && !it.consumed }
                .sortedBy { it.shownAt }
        override suspend fun consume(notificationId: String) {
            rows[notificationId]?.let { rows[notificationId] = it.copy(consumed = true) }
        }
        override suspend fun deleteShownBefore(before: Long) {
            rows.values.filter { it.shownAt < before }.forEach { rows.remove(it.notificationId) }
        }
    }

    private lateinit var stateDao: FakeStateDao
    private lateinit var eventDao: FakeEventDao
    private lateinit var shownDao: FakeShownDao
    private lateinit var placeDao: PlaceDao
    private lateinit var placeAlertDao: PlaceAlertDao
    private lateinit var notificationDao: NotificationDao
    private lateinit var circleMemberDao: CircleMemberDao
    private lateinit var sessionManager: SessionManager
    /** Real hashing is UniFFI and unit tests cannot load it; this seam is why. */
    private lateinit var eventTypeHasher: EventTypeHasher
    private lateinit var alertHandler: AlertHandler
    /** Only for an alert circle's roster fetched again on a name miss. */
    private lateinit var circleRepository: com.pingolin.track.domain.repository.CircleRepository

    private lateinit var uxEventBus: com.pingolin.track.util.UxEventBus

    /**
     * Every UxEvent emitted, in order.
     *
     * Captured off a mocked bus rather than collected from the real one: the real
     * UxEventBus is replay=0, so an event emitted with no collector attached is
     * dropped on the floor by design — a test that collected it would be racing
     * its own subscription and could pass while asserting nothing.
     */
    private val uxEvents = mutableListOf<com.pingolin.track.util.UxEvent>()

    /** Every notification AlertHandler was asked to post, in order. */
    private val posted = mutableListOf<Pair<String, Map<String, String>>>()

    /** Every bell row written. */
    private val bellRows = mutableListOf<NotificationEntity>()

    private val home = PlaceEntity(
        id = PLACE_ID,
        // "self" — a PERSONAL place: never shared, never wrapped for a circle.
        circleId = "self",
        name = "Home",
        address = null,
        latitude = 37.0,
        longitude = -122.0,
        radius = 150,
        icon = null,
        color = null,
        createdAt = 0L,
        updatedAt = 0L,
        // The leak this model closes: no circle in sharedCircles, no place_shares row.
        sharedCircles = null,
    )

    @Before
    fun setup() {
        stateDao = FakeStateDao()
        eventDao = FakeEventDao()
        shownDao = FakeShownDao()
        placeDao = mockk()
        placeAlertDao = mockk()
        notificationDao = mockk()
        circleMemberDao = mockk()
        sessionManager = mockk()
        eventTypeHasher = mockk()
        alertHandler = mockk()
        uxEventBus = mockk()
        circleRepository = mockk()
        coEvery { circleRepository.refreshRoster(any()) } returns Result.success(emptyList())

        uxEvents.clear()
        val uxSlot = slot<com.pingolin.track.util.UxEvent>()
        every { uxEventBus.emit(capture(uxSlot)) } answers { uxEvents += uxSlot.captured }

        coEvery { placeDao.getById(PLACE_ID) } returns home
        // Opened with the circle's key: the only name a notification may say.
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returns "Alice"
        every { sessionManager.getUserId() } returns ME

        val bellSlot = slot<NotificationEntity>()
        coEvery { notificationDao.insert(capture(bellSlot)) } answers { bellRows += bellSlot.captured }

        val typeSlot = slot<String>()
        val dataSlot = slot<Map<String, String>>()
        every { alertHandler.handleAlert(capture(typeSlot), capture(dataSlot)) } answers {
            posted += typeSlot.captured to dataSlot.captured
        }
    }

    /** A fresh evaluator over the SAME tables — i.e. a process restart. */
    private fun newEvaluator() = PlaceAlertEvaluator(
        placeDao = placeDao,
        placeAlertDao = placeAlertDao,
        stateDao = stateDao,
        eventDao = eventDao,
        shownDao = shownDao,
        notificationDao = notificationDao,
        circleMemberDao = circleMemberDao,
        sessionManager = sessionManager,
        eventTypeHasher = eventTypeHasher,
        alertHandler = dagger.Lazy { alertHandler },
        uxEventBus = uxEventBus,
        circleRepository = dagger.Lazy { circleRepository },
    )

    private fun alert(
        targetUserId: String = ALICE,
        alertOnArrival: Boolean = true,
        alertOnDeparture: Boolean = true,
        activeDays: String? = null,
        activeStartTime: String? = null,
        activeEndTime: String? = null,
        notifyPush: Boolean = true,
    ) = PlaceAlertEntity(
        id = "alert-1",
        placeId = PLACE_ID,
        // NO routing circle: a purely local alert on an unshared personal place.
        circleId = null,
        userId = ME,
        targetUserId = targetUserId,
        alertOnArrival = alertOnArrival,
        alertOnDeparture = alertOnDeparture,
        activeDays = activeDays,
        activeStartTime = activeStartTime,
        activeEndTime = activeEndTime,
        notifyPush = notifyPush,
        dayBucket = 0,
    )

    private fun withAlerts(vararg alerts: PlaceAlertEntity) {
        coEvery { placeAlertDao.getActive() } returns alerts.toList()
    }

    private fun loc(userId: String, lat: Double, ts: Long) = LocationEntity(
        circleId = "self",
        userId = userId,
        deviceId = "device-1",
        latitude = lat,
        longitude = -122.0,
        altitude = null,
        speed = null,
        heading = null,
        accuracy = null,
        batteryLevel = null,
        timestamp = ts,
    )

    private fun inside(userId: String, ts: Long) = loc(userId, 37.0, ts)
    private fun outside(userId: String, ts: Long) = loc(userId, 37.02, ts)

    // ===== Only this phone's own fix is "me" =====

    @Test
    fun `a server batch naming this account is judged by nobody, in any spelling, and this phone's own fix still is`() = runTest {
        val me = "0e0e0b1c-d2e3-4f40-9a1b-c2d3e4f5a6b7"
        every { sessionManager.getUserId() } returns me
        withAlerts(alert(targetUserId = me))
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(me, now - 120_000L)), fromServer = false)

        evaluator.onLocationsPersisted(listOf(inside(me, now - 60_000L), inside(me.uppercase(), now - 50_000L)), fromServer = true)
        assertTrue("the server's word about me moves nothing", posted.isEmpty())
        assertEquals(now - 120_000L, stateDao.rows[PLACE_ID to me]?.lastLocationAt)

        evaluator.onLocationsPersisted(listOf(inside(me, now - 20_000L)), fromServer = false)
        assertEquals(listOf(AlertHandler.TYPE_PLACE_ARRIVAL), posted.map { it.first })
    }

    @Test
    fun `with no account, a server batch is judged by nobody`() = runTest {
        every { sessionManager.getUserId() } returns null
        withAlerts(alert())
        val evaluator = newEvaluator()
        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 120_000L)), fromServer = true)
        evaluator.onLocationsPersisted(listOf(inside(ALICE, NOW - 60_000L)), fromServer = true)

        assertTrue(posted.isEmpty())
        assertNull("no state taken from the server", stateDao.rows[PLACE_ID to ALICE])
    }

    // ===== A time beyond this phone's clock is no fix of now =====

    @Test
    fun `a point timed beyond the clock is judged by nobody and never becomes the watermark`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now + com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS + 60_000L)))
        assertTrue(posted.isEmpty())
        assertEquals(now - 120_000L, stateDao.rows[PLACE_ID to ALICE]?.lastLocationAt)

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 10_000L)))
        assertEquals(listOf(AlertHandler.TYPE_PLACE_ARRIVAL), posted.map { it.first })
    }

    @Test
    fun `a watermark kept from beyond the clock reads as none, and where they were stays`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        stateDao.rows[PLACE_ID to ALICE] = PlaceAlertMemberStateEntity(
            placeId = PLACE_ID, userId = ALICE, isInside = true,
            lastLocationAt = now + com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS + 3_600_000L,
        )

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))
        assertTrue("still inside: no arrival fired again", posted.isEmpty())

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 10_000L)))
        assertEquals(listOf(AlertHandler.TYPE_PLACE_DEPARTURE), posted.map { it.first })
    }

    @Test
    fun `a watermark kept from beyond the clock keeps where they were - a first point outside is a departure, not a new start`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        stateDao.rows[PLACE_ID to ALICE] = PlaceAlertMemberStateEntity(
            placeId = PLACE_ID, userId = ALICE, isInside = true,
            lastLocationAt = now + com.pingolin.track.util.LiveTime.MAX_FUTURE_SKEW_MS + 3_600_000L,
        )

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 10_000L)))

        assertEquals(listOf(AlertHandler.TYPE_PLACE_DEPARTURE), posted.map { it.first })
    }

    // ===== the case the whole model exists for =====

    @Test
    fun `an alert on an UNSHARED personal place fires with no circle at all`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        // Seed: Alice is outside.
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        assertTrue("seeding must not notify", posted.isEmpty())

        // She arrives.
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals(1, posted.size)
        assertEquals(AlertHandler.TYPE_PLACE_ARRIVAL, posted.single().first)
        assertEquals("Alice arrived at Home", posted.single().second["body"])
        // Proof the place stayed private: no share, no routing circle, and the
        // detection never left the device.
        assertEquals(null, home.sharedCircles)
        assertEquals(null, alert().circleId)
    }

    @Test
    fun `alert me about MYSELF works with no circle and no members`() = runTest {
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 20_000L)))

        assertEquals(1, posted.size)
        assertEquals("You arrived at Home", posted.single().second["body"])
    }

    @Test
    fun `a lagged self arrival reads in the second person`() = runTest {
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 60 * 60 * 1000L)))
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 15 * 60 * 1000L)))

        assertEquals(
            "You arrived at Home 15 minutes ago and are still there",
            posted.single().second["body"],
        )
    }

    @Test
    fun `a self crossing is not double-counted when the server echoes it back`() = runTest {
        // For the SELF case the subscriber and the mover are the same device, so the
        // same fix can reach the choke point twice: once as this device's own
        // location (circleId "self") and once as a member location echoed back by
        // the per-circle poll. Reconciliation state is keyed on (place, member) and
        // NOT on circle, so both feed one state row: the echo is at or before the
        // watermark and contributes nothing.
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 120_000L)))
        // Own fix.
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 30_000L)))
        assertEquals(1, posted.size)

        // The same fix, echoed back under a real circle.
        evaluator.onLocationsPersisted(
            listOf(inside(ME, now - 30_000L).copy(circleId = "circle-1"))
        )
        assertEquals("the echo must not re-alert", 1, posted.size)

        // And a LATER echo that is still inside is not a transition either.
        evaluator.onLocationsPersisted(
            listOf(inside(ME, now - 10_000L).copy(circleId = "circle-1"))
        )
        assertEquals("staying inside is not a new crossing", 1, posted.size)
    }

    @Test
    fun `a departure notifies`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 20_000L)))

        assertEquals(1, posted.size)
        assertEquals(AlertHandler.TYPE_PLACE_DEPARTURE, posted.single().first)
        assertEquals("Alice left Home", posted.single().second["body"])
    }

    // ===== Rule 2: catch-up batches =====

    @Test
    fun `a catch-up batch with an old inside point raises no live alert`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        // Alice visited three hours ago and left two and a half hours ago; the whole
        // batch lands at once when the subscriber reconnects. Replayed point-by-point
        // as if each were "now", the inside point would read as a fresh arrival.
        evaluator.onLocationsPersisted(
            listOf(
                outside(ALICE, now - 4 * 60 * 60 * 1000L),
                inside(ALICE, now - 3 * 60 * 60 * 1000L),
                outside(ALICE, now - 150 * 60 * 1000L),
            )
        )

        assertTrue("nothing older than the staleness window may push", posted.isEmpty())
        // It is still recorded — the feature never SILENTLY misses that someone came by.
        assertEquals(1, bellRows.size)
        assertTrue(bellRows.single().body!!.startsWith("Alice was at Home, "))
    }

    @Test
    fun `enter plus exit in one batch collapses to a single past-framed notification`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60 * 1000L)))
        posted.clear()

        // A complete pass-through the subscriber missed, still inside the window.
        evaluator.onLocationsPersisted(
            listOf(
                inside(ALICE, now - 40 * 60 * 1000L),
                outside(ALICE, now - 10 * 60 * 1000L),
            )
        )

        assertEquals("one entry, not two pings", 1, posted.size)
        val body = posted.single().second["body"]!!
        assertTrue("expected a past-framed span, got: $body", body.startsWith("Alice was at Home, "))
    }

    @Test
    fun `arrival lag is stated, never presented as live`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60 * 1000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 15 * 60 * 1000L)))

        assertEquals(
            "Alice arrived at Home 15 minutes ago and is still there",
            posted.single().second["body"],
        )
    }

    // ===== Rule 4: de-duplication =====

    @Test
    fun `the same crossing never surfaces twice, even across a restart`() = runTest {
        withAlerts(alert())
        val now = NOW
        val seed = outside(ALICE, now - 120_000L)
        val arrival = inside(ALICE, now - 30_000L)

        newEvaluator().onLocationsPersisted(listOf(seed, arrival))
        assertEquals(1, posted.size)

        // Process restart: a brand-new evaluator over the SAME tables, handed an
        // overlapping batch that re-delivers the same points.
        newEvaluator().onLocationsPersisted(listOf(seed, arrival))
        assertEquals("the watermark must suppress the replay", 1, posted.size)

        // And even if state were somehow rewound, the ledger still holds the claim.
        stateDao.rows.clear()
        newEvaluator().onLocationsPersisted(listOf(seed, arrival))
        assertEquals("the ledger must suppress the re-derived crossing", 1, posted.size)
    }

    @Test
    fun `overlapping batches do not re-alert`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 180_000L), inside(ALICE, now - 120_000L)))
        assertEquals(1, posted.size)

        // A second page overlapping the first, plus one genuinely new point.
        evaluator.onLocationsPersisted(
            listOf(outside(ALICE, now - 180_000L), inside(ALICE, now - 120_000L), inside(ALICE, now - 60_000L))
        )
        assertEquals("no new transition, so no new alert", 1, posted.size)
    }

    // ===== A server push posts at once; the named local copy replaces it in place =====
    //
    // A server-routed push is posted the moment it lands (AlertHandler), under its own
    // event id: a crossing local evaluation can't reproduce is never silent. The local copy
    // of the same crossing then REPLACES it in the shade, under that id and without sounding
    // again; or, when the local copy came first, the push isn't posted at all. Neither path
    // ever claims for the other in the ledger, so nothing a push leaves can swallow a crossing
    // this device saw: the local copy always posts. A visible duplicate is recoverable; a
    // swallowed alert is not.

    /** A server push about Alice at Home, as AlertHandler plans it before posting. */
    private suspend fun PlaceAlertEvaluator.push(
        id: String?,
        transition: PlaceAlertRules.Transition?,
        eventAt: Long?,
        placeId: String? = PLACE_ID,
        userId: String? = ALICE,
    ) = planServerPush(id, placeId, userId, transition, eventAt)

    private val POST = PlaceAlertEvaluator.ServerPushOutcome.POST
    private val ALREADY_SHOWN = PlaceAlertEvaluator.ServerPushOutcome.ALREADY_SHOWN
    private val ENTER = PlaceAlertRules.Transition.ENTER
    private val EXIT = PlaceAlertRules.Transition.EXIT

    @Test
    fun `push-first, the push posts and the named local copy replaces it in place without sounding again`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        assertEquals("the push posts at once: never silent", POST, evaluator.push("row-1", ENTER, now - 6_000L))
        assertFalse(evaluator.pushPaired("row-1"))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))

        assertEquals(1, posted.size)
        // Same notification id => Android replaces rather than stacks...
        assertEquals("row-1", posted.single().second["alert_id"])
        // ...quietly: the user heard this crossing once already...
        assertEquals("true", posted.single().second[AlertHandler.KEY_REPLACES_SHOWN])
        // ...and ends up with the NAMED copy.
        assertEquals("Alice arrived at Home", posted.single().second["body"])
        assertTrue("the wake stops driving once it is named", evaluator.pushPaired("row-1"))
    }

    @Test
    fun `the push survives a process death and is still replaced on a later pass`() = runTest {
        withAlerts(alert())
        val now = NOW
        newEvaluator().onLocationsPersisted(listOf(outside(ALICE, now - 20 * 60_000L)))
        assertEquals(POST, newEvaluator().push("row-1", ENTER, now - 6 * 60_000L))

        // A new process: the 5-minute pass, or the 15-minute heartbeat, finally reproduces it.
        newEvaluator().onLocationsPersisted(listOf(inside(ALICE, now - 6 * 60_000L)))

        assertEquals("row-1", posted.single().second["alert_id"])
        assertEquals("true", posted.single().second[AlertHandler.KEY_REPLACES_SHOWN])
    }

    @Test
    fun `local-first, the push of the same crossing is not posted`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))
        assertEquals(1, posted.size)
        assertTrue(posted.single().second["alert_id"]!!.startsWith("local-"))
        assertNull("a local copy that replaced nothing doesn't say so", posted.single().second[AlertHandler.KEY_REPLACES_SHOWN])

        // The push for that same crossing lands after: the shade has it already, named.
        assertEquals(ALREADY_SHOWN, evaluator.push("row-1", ENTER, now - 4_000L))
        // Single use: a second push can't pair with the same local copy.
        assertEquals(POST, evaluator.push("row-2", ENTER, now - 4_000L))
    }

    @Test
    fun `a push that can't say its direction or its time is posted even beside the local copy`() = runTest {
        // Never pair on a guess: an earlier local "arrived" swallowing a later departure's
        // push is a crossing never heard, if that departure doesn't reproduce here.
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))

        assertEquals(POST, evaluator.push("row-1", transition = null, eventAt = now - 4_000L))
        assertEquals(POST, evaluator.push("row-2", ENTER, eventAt = null))
        assertEquals("the other direction is another crossing", POST, evaluator.push("row-3", EXIT, now - 4_000L))
    }

    @Test
    fun `a push delivered hours late pairs with its own crossing, never the next one of its kind`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val first = now - 100 * 60_000L
        val again = now - 10 * 60_000L
        evaluator.onLocationsPersisted(listOf(outside(ALICE, first - 60_000L), inside(ALICE, first)))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, first + 30 * 60_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, again)))
        assertEquals(3, posted.size)

        // The first arrival's push, reaching this phone only now: its event time is its own.
        assertEquals(ALREADY_SHOWN, evaluator.push("row-first", ENTER, first + 20_000L))
        // The second arrival is still there to pair with its own push...
        assertEquals(ALREADY_SHOWN, evaluator.push("row-again", ENTER, again - 20_000L))
        // ...and a push about neither, between them, pairs with nothing.
        assertEquals(POST, evaluator.push("row-between", ENTER, first + 50 * 60_000L))
    }

    @Test
    fun `nothing a push leaves ever stops a local copy`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        // Pushes of every kind already in the shade.
        evaluator.push("row-1", ENTER, now - 6_000L)
        evaluator.push("row-2", null, null)
        evaluator.push("row-3", EXIT, now - 60_000L)

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 1_000L)))

        assertEquals("each local crossing posts", 2, posted.size)
        assertTrue("and nothing was claimed for the pushes", eventDao.rows.values.all { it.source == PlaceAlertEvaluator.SOURCE_LOCAL })
    }

    @Test
    fun `an enter and a following exit never collapse onto one notification`() = runTest {
        // THE safety test. A push is replaced by at most one crossing; if both reused it,
        // the exit would replace the arrival in the shade and the user would never learn
        // Alice arrived.
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.push("row-1", transition = null, eventAt = now - 40_000L)
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 40_000L)))   // enter
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 5_000L)))   // exit

        assertEquals(2, posted.size)
        val ids = posted.map { it.second["alert_id"] }
        assertEquals("the two crossings must not share a notification id", 2, ids.toSet().size)
        assertEquals("row-1", ids.first())
        assertTrue("the exit posts under its own id", ids[1]!!.startsWith("local-"))
    }

    @Test
    fun `a push of the other direction is never replaced by this crossing`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.push("row-exit", EXIT, now - 6_000L)
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))

        assertTrue(posted.single().second["alert_id"]!!.startsWith("local-"))
        assertFalse(evaluator.pushPaired("row-exit"))
    }

    @Test
    fun `a crossing far from the push's event time posts under its own id`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60_000L)))
        evaluator.push("row-1", ENTER, now - 30 * 60_000L)
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30 * 60_000L + PlaceAlertRules.CROSSING_EVENT_MATCH_MS + 60_000L)))

        assertTrue(posted.single().second["alert_id"]!!.startsWith("local-"))
    }

    @Test
    fun `a push with no event time is replaced across the staleness reach, never by a later crossing`() = runTest {
        // Back as far as a crossing may wait for a local pass; forward by clock skew only,
        // as a push is about a crossing that has already happened.
        withAlerts(alert())
        val now = NOW
        newEvaluator().onLocationsPersisted(listOf(outside(ALICE, now - 100 * 60_000L)))
        newEvaluator().push("row-1", ENTER, eventAt = null)
        newEvaluator().onLocationsPersisted(listOf(inside(ALICE, now - 90 * 60_000L)))
        assertEquals("row-1", posted.single().second["alert_id"])

        posted.clear()
        newEvaluator().push("row-2", ENTER, eventAt = null)
        newEvaluator().onLocationsPersisted(listOf(outside(ALICE, now - 80 * 60_000L)))
        newEvaluator().onLocationsPersisted(
            listOf(inside(ALICE, now + PlaceAlertRules.SERVER_PUSH_SUPERSEDE_SKEW_MS + 60_000L))
        )
        assertTrue("a later crossing must not take the push", posted.last().second["alert_id"]!!.startsWith("local-"))
    }

    @Test
    fun `a collapsed pass-through replaces the push raised for its enter`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val enter = now - 35 * 60_000L

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60_000L)))
        evaluator.push("row-1", ENTER, enter + 10_000L)
        evaluator.onLocationsPersisted(listOf(inside(ALICE, enter), outside(ALICE, now - 60_000L)))

        assertEquals("enter and exit collapse into one visit", 1, posted.size)
        assertEquals("row-1", posted.single().second["alert_id"])
    }

    @Test
    fun `the same push delivered again is not posted again, before or after it was named`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        assertEquals(POST, evaluator.push("row-1", ENTER, now - 6_000L))
        assertEquals(ALREADY_SHOWN, evaluator.push("row-1", ENTER, now - 6_000L))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))
        // Posted again it would put the generic words back over the named copy.
        assertEquals(ALREADY_SHOWN, evaluator.push("row-1", ENTER, now - 6_000L))
    }

    @Test
    fun `a push about another place or member pairs with nothing`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))

        assertEquals(POST, evaluator.push("row-1", ENTER, now - 4_000L, placeId = "other-place"))
        assertEquals(POST, evaluator.push("row-2", ENTER, now - 4_000L, userId = BOB))
    }

    @Test
    fun `an unidentifiable push is always posted, and pairs with nothing`() = runTest {
        val evaluator = newEvaluator()
        val now = NOW
        // Dropping an alert we cannot identify would be worse than a rare duplicate.
        assertEquals(POST, evaluator.push(null, ENTER, now))
        assertEquals(POST, evaluator.push("", ENTER, now))
        assertEquals(POST, evaluator.push("row-1", ENTER, now, placeId = null))
        assertEquals(POST, evaluator.push("row-2", ENTER, now, userId = ""))
        assertTrue("nothing left to pair", shownDao.rows.keys.none { it == "row-1" || it == "row-2" })
    }

    @Test
    fun `a silently folded crossing leaves nothing for a live push to pair with`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val ancient = now - 29 * 60 * 60 * 1000L

        // An ancient visit, folded into history rather than pushed.
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 30 * 60 * 60 * 1000L), inside(ALICE, ancient)))
        assertTrue(posted.isEmpty())

        assertEquals("a silent history row must not shadow a genuine push", POST, evaluator.push("row-1", ENTER, ancient))
    }

    // ===== the local path alone =====

    @Test
    fun `leaving and coming back soon after still alerts on the second arrival`() = runTest {
        // Moving the car, walking the dog, a school run: a cross-path window matching
        // (place, member, transition) with no source filter would take the SECOND
        // genuine arrival for a duplicate of the first and drop it — no alert for
        // actually coming home.
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 600_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 540_000L)))   // arrive
        assertEquals(1, posted.size)

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 300_000L)))  // pop out
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 60_000L)))    // come back

        assertEquals(
            "a genuine second arrival must alert, even minutes after the first",
            3,
            posted.size,
        )
        assertEquals(AlertHandler.TYPE_PLACE_ARRIVAL, posted.last().first)
    }


    @Test
    fun `a rapid re-entry inside the dedupe window still alerts`() = runTest {
        // Even well inside CROSS_PATH_DEDUPE_WINDOW_MS, a distinct crossing is a
        // distinct crossing: only the OTHER path's rows may suppress ours.
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ME, now - 60_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 45_000L)))
        evaluator.onLocationsPersisted(listOf(outside(ME, now - 30_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ME, now - 5_000L)))

        assertEquals(3, posted.size)
    }


    @Test
    fun `the exact same crossing is still surfaced only once`() = runTest {
        // The source filter must not weaken Rule 4: identical crossings still
        // collapse, via the ledger primary key rather than the window.
        withAlerts(alert(targetUserId = ME))
        val now = NOW
        val seed = outside(ME, now - 120_000L)
        val arrival = inside(ME, now - 30_000L)

        newEvaluator().onLocationsPersisted(listOf(seed, arrival))
        stateDao.rows.clear() // force re-derivation of the identical crossing
        newEvaluator().onLocationsPersisted(listOf(seed, arrival))

        assertEquals(1, posted.size)
    }


    // ===== naming a server push this device could not reproduce =====
    //
    // The server's copy is "Member arrived at a place" with a NULL body, because the
    // place name and the member's display name are E2EE. This device holds both. So
    // the fallback notification is named from Room rather than shipped generic, which
    // is what gives the notification-detail setting something to select between.

    @Test
    fun `a server push is named from what this device already holds`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()

        val copy = evaluator.resolveServerPushCopy(PLACE_ID, ALICE, eventTypeHash = null)

        assertNotNull(copy)
        assertEquals("Alice", copy!!.memberName)
        assertEquals("Home", copy.placeName)
        // No hash to read, so no direction is claimed. The server's own title still
        // carries it; this just declines to invent one.
        assertEquals(null, copy.transition)
    }

    @Test
    fun `a name the circle key didn't open is never what an alert says`() = runTest {
        // The server's plaintext fallback name stays off notifications, on both paths: never
        // any stored name (anyDisplayName), only one the circle key opened.
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returns null
        coEvery { circleMemberDao.anyDisplayName(ALICE) } returns "Server Alice"
        withAlerts(alert())
        val evaluator = newEvaluator()

        assertEquals("A member", evaluator.resolveServerPushCopy(PLACE_ID, ALICE, eventTypeHash = null)?.memberName)

        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, NOW - 30_000L)))
        val data = posted.single().second
        assertEquals("A member", data["user_name"])
        assertFalse(data.values.any { it.contains("Server Alice") })
    }

    // ===== a name not here yet: the alert circle's roster is fetched again (the receiver's side) =====

    @Test
    fun `a name not here yet fetches the alert circle's roster once, and says the name it opens`() = runTest {
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returnsMany listOf(null, "Alice")
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, NOW - 30_000L)))

        assertEquals("Alice", posted.single().second["user_name"])
        coVerify(exactly = 1) { circleRepository.refreshRoster(CIRCLE_ID) }
    }

    @Test
    fun `a name that still doesn't open fetches nothing more within the window`() = runTest {
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returns null
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 300_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, NOW - 200_000L)))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 30_000L)))

        assertEquals(listOf("A member", "A member"), posted.map { it.second["user_name"] })
        coVerify(exactly = 1) { circleRepository.refreshRoster(CIRCLE_ID) }
    }

    @Test
    fun `no circle to fetch, nothing fetched - an alert homed to none on a personal place`() = runTest {
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returns null
        withAlerts(alert())
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(outside(ALICE, NOW - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, NOW - 30_000L)))

        assertEquals("A member", posted.single().second["user_name"])
        coVerify(exactly = 0) { circleRepository.refreshRoster(any()) }
    }

    @Test
    fun `a server push naming a member not here yet posts at once as "A member", fetching nothing`() = runTest {
        // A fetch here would hold the push back while its row is already marked read. The wake's
        // local copy fetches, and names it in place (the test above).
        coEvery { circleMemberDao.anyOpenedDisplayName(ALICE) } returnsMany listOf(null, "Alice")
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        val evaluator = newEvaluator()

        assertEquals("A member", evaluator.resolveServerPushCopy(PLACE_ID, ALICE, eventTypeHash = null)?.memberName)
        coVerify(exactly = 0) { circleRepository.refreshRoster(any()) }
    }

    @Test
    fun `the direction is read back out of the event type hash`() = runTest {
        // Not a guess: the same BLAKE3(circle key || event type) the alert stored at
        // creation and the worker fired on. Recomputing it either matches or does not.
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        every { eventTypeHasher.hash(CIRCLE_ID, "enter") } returns "hash-enter"
        every { eventTypeHasher.hash(CIRCLE_ID, "exit") } returns "hash-exit"
        every { eventTypeHasher.hash(CIRCLE_ID, "arrival") } returns "hash-arrival"
        every { eventTypeHasher.hash(CIRCLE_ID, "departure") } returns "hash-departure"
        val evaluator = newEvaluator()

        assertEquals(
            PlaceAlertRules.Transition.ENTER,
            evaluator.resolveServerPushCopy(PLACE_ID, ALICE, "hash-enter")?.transition,
        )
        assertEquals(
            PlaceAlertRules.Transition.EXIT,
            evaluator.resolveServerPushCopy(PLACE_ID, ALICE, "hash-exit")?.transition,
        )
    }

    @Test
    fun `the legacy arrival naming is matched too`() = runTest {
        // The Android mover hashes "enter"/"exit" while alerts co-populate
        // "arrival"/"departure"; both are on the wire, so both are tried.
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        every { eventTypeHasher.hash(CIRCLE_ID, "enter") } returns "hash-enter"
        every { eventTypeHasher.hash(CIRCLE_ID, "arrival") } returns "hash-arrival"
        val evaluator = newEvaluator()

        assertEquals(
            PlaceAlertRules.Transition.ENTER,
            evaluator.resolveServerPushCopy(PLACE_ID, ALICE, "hash-arrival")?.transition,
        )
    }

    @Test
    fun `a hash matching a DISABLED direction names no direction`() = runTest {
        // Toggling a direction off leaves its hash on the server, so a stale
        // match must not name a direction the alert would never have fired
        // on. Mirrors the server's own enable gate.
        withAlerts(alert(alertOnDeparture = false).copy(circleId = CIRCLE_ID))
        every { eventTypeHasher.hash(CIRCLE_ID, "enter") } returns "hash-enter"
        every { eventTypeHasher.hash(CIRCLE_ID, "arrival") } returns "hash-arrival"
        every { eventTypeHasher.hash(CIRCLE_ID, "exit") } returns "hash-exit"
        every { eventTypeHasher.hash(CIRCLE_ID, "departure") } returns "hash-departure"
        val evaluator = newEvaluator()

        val copy = evaluator.resolveServerPushCopy(PLACE_ID, ALICE, "hash-exit")
        assertNotNull(copy)
        assertEquals(null, copy!!.transition)
    }

    @Test
    fun `a circle we hold no key for costs the direction, not the names`() = runTest {
        withAlerts(alert().copy(circleId = CIRCLE_ID))
        every { eventTypeHasher.hash(any(), any()) } returns null
        val evaluator = newEvaluator()

        val copy = evaluator.resolveServerPushCopy(PLACE_ID, ALICE, "hash-enter")
        assertNotNull(copy)
        assertEquals("Alice", copy!!.memberName)
        assertEquals("Home", copy.placeName)
        assertEquals(null, copy.transition)
    }

    @Test
    fun `an unknown place yields no local copy at all`() = runTest {
        // Never synced, or since deleted. Half a name is worse than the server's
        // wording, so this gives up rather than rendering "Alice at null".
        coEvery { placeDao.getById(PLACE_ID) } returns null
        withAlerts(alert())
        val evaluator = newEvaluator()

        assertEquals(null, evaluator.resolveServerPushCopy(PLACE_ID, ALICE, null))
    }

    @Test
    fun `a marker for a different member is not used`() = runTest {
        withAlerts(alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = null))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.push("other-row", transition = null, eventAt = NOW - 5_000L, userId = BOB)
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 5_000L)))

        assertEquals(1, posted.size)
        assertTrue(posted.single().second["alert_id"]!!.startsWith("local-"))
    }

    /**
     * Wraps the state DAO so a read yields control, letting a second concurrent
     * evaluation run before the first writes its state back. That is the real
     * interleaving: the per-circle location refreshes fan out with `async`
     * (MainViewModel), so a member reached through two shared circles is evaluated
     * twice at once.
     */
    private class YieldingStateDao(private val inner: FakeStateDao) : PlaceAlertMemberStateDao {
        override suspend fun get(placeId: String, userId: String): PlaceAlertMemberStateEntity? {
            val row = inner.get(placeId, userId)
            kotlinx.coroutines.yield()
            return row
        }
        override suspend fun upsert(state: PlaceAlertMemberStateEntity) = inner.upsert(state)
        override suspend fun deleteByPlace(placeId: String) = inner.deleteByPlace(placeId)
        override suspend fun deleteAll() = inner.deleteAll()
    }

    @Test
    fun `two concurrent evaluations of one crossing surface it once`() = runTest {
        // What this pins: a departure must not fire TWICE. Unguarded, both
        // evaluations would read the same pre-exit state, both detect the SAME
        // physical exit at their own batch's timestamp, and because the ledger
        // id includes crossedAt they would claim two different rows — so the
        // dedupe could not collapse them.
        withAlerts(alert())
        stateDao = FakeStateDao()
        val yielding = YieldingStateDao(stateDao)
        val evaluator = PlaceAlertEvaluator(
            placeDao = placeDao,
            placeAlertDao = placeAlertDao,
            stateDao = yielding,
            eventDao = eventDao,
            shownDao = shownDao,
            notificationDao = notificationDao,
            circleMemberDao = circleMemberDao,
            sessionManager = sessionManager,
            eventTypeHasher = eventTypeHasher,
            alertHandler = dagger.Lazy { alertHandler },
        uxEventBus = uxEventBus,
            circleRepository = dagger.Lazy { circleRepository },
        )
        val now = NOW

        // Establish "inside" first.
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 300_000L)))
        posted.clear()

        // Two circles deliver the same member's exit at slightly different times,
        // concurrently — the shape MainViewModel's per-circle async fan-out creates.
        val a = launch { evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 20_000L))) }
        val b = launch { evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 10_000L))) }
        a.join(); b.join()

        assertEquals("one physical exit must surface once", 1, posted.size)
    }

    @Test
    fun `a personal-sentinel alert circle is not treated as a roster to look up`() = runTest {
        // The nil-UUID personal sentinel is not a circle and has no roster. Asking
        // about it would log a warning once per location batch.
        withAlerts(
            alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET)
                .copy(circleId = "00000000-0000-0000-0000-000000000000")
        )
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        // Still fires (unknown roster => unscoped), but never consults the DAO.
        assertEquals(1, posted.size)
        coVerify(exactly = 0) { circleMemberDao.countByCircle(any()) }
    }

    @Test
    fun `an arrival-only alert ignores departures`() = runTest {
        withAlerts(alert(alertOnArrival = true, alertOnDeparture = false))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 30_000L)))

        assertTrue("departure must not notify an arrival-only alert", posted.isEmpty())
    }

    @Test
    fun `a departure-only alert ignores arrivals`() = runTest {
        withAlerts(alert(alertOnArrival = false, alertOnDeparture = true))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertTrue("arrival must not notify a departure-only alert", posted.isEmpty())
    }

    @Test
    fun `a crossing outside the schedule window does not notify`() = runTest {
        val at = java.time.LocalDate.now()
            .atTime(3, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        withAlerts(alert(activeStartTime = "08:00", activeEndTime = "18:00"))
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(outside(ALICE, at - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, at)))

        assertTrue("03:00 is outside an 08:00-18:00 window", posted.isEmpty())
    }

    @Test
    fun `a crossing on an unscheduled day does not notify`() = runTest {
        val today = java.time.LocalDate.now().dayOfWeek
        // Every day EXCEPT today.
        val days = java.time.DayOfWeek.values()
            .filter { it != today }
            .joinToString(",") { it.name.lowercase() }
        withAlerts(alert(activeDays = days))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertTrue("today is not in the schedule", posted.isEmpty())
    }

    @Test
    fun `an alert with push off still records history but posts nothing`() = runTest {
        withAlerts(alert(notifyPush = false))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertTrue(posted.isEmpty())
        assertEquals(1, bellRows.size)
    }

    @Test
    fun `a circle-wide alert does NOT fire for a member of a different circle`() = runTest {
        // The bound the backend enforces (the target must be in the alert's
        // circle) has to hold client-side too, or "everyone in my family circle"
        // silently means "everyone whose location this device holds" — so a coworker
        // from an unrelated work circle arriving at your home would alert you.
        coEvery { circleMemberDao.countByCircle("circle-family") } returns 3
        coEvery { circleMemberDao.get("circle-family", ALICE) } returns null
        withAlerts(
            alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = "circle-family")
        )
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertTrue("a non-member must not trigger a circle-wide alert", posted.isEmpty())
        assertTrue("and no state should be tracked for them", stateDao.rows.isEmpty())
    }

    @Test
    fun `a circle-wide alert DOES fire for a member of its own circle`() = runTest {
        coEvery { circleMemberDao.countByCircle("circle-family") } returns 3
        coEvery { circleMemberDao.get("circle-family", ALICE) } returns mockk(relaxed = true)
        withAlerts(
            alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = "circle-family")
        )
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals(1, posted.size)
        assertEquals(AlertHandler.TYPE_PLACE_ARRIVAL, posted.single().first)
    }

    @Test
    fun `an explicitly targeted alert fires regardless of the alert's circle`() = runTest {
        // Naming a PERSON is not a circle-scoped question: it is the same human at
        // the same place whichever circle's feed carried the fix, and the backend
        // already verified they share the alert's circle.
        coEvery { circleMemberDao.countByCircle(any()) } returns 3
        coEvery { circleMemberDao.get(any(), any()) } returns null
        withAlerts(alert(targetUserId = ALICE).copy(circleId = "circle-family"))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals(1, posted.size)
    }

    @Test
    fun `a legacy circle-wide alert with no circle still fires`() = runTest {
        // Rows created before the backend required a home circle must not silently
        // stop firing — that is the failure mode this whole model exists to remove.
        coEvery { circleMemberDao.countByCircle(any()) } returns 3
        coEvery { circleMemberDao.get(any(), any()) } returns null
        withAlerts(alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = null))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals(1, posted.size)
    }

    @Test
    fun `an UNSYNCED roster does not silence a circle-wide alert`() = runTest {
        // Scoping makes the evaluator depend on circle_members, and every
        // refreshMembers caller is a UI ViewModel — a device that hasn't opened the
        // right screen holds no roster at all. Treating that as "not a member" would
        // stop a correctly-configured cross-user alert from ever firing, and look
        // identical to the feature being broken.
        //
        // This pins a latent hazard: cheap to keep, and the failure it guards
        // against would be indistinguishable from the feature being dead.
        coEvery { circleMemberDao.countByCircle("circle-family") } returns 0
        coEvery { circleMemberDao.get(any(), any()) } returns null
        withAlerts(
            alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = "circle-family")
        )
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals("an unknown roster must not scope everyone out", 1, posted.size)
    }

    @Test
    fun `a failed roster lookup does not silence a circle-wide alert`() = runTest {
        // "Couldn't check" must not read as "definitely not a member" either.
        coEvery { circleMemberDao.countByCircle("circle-family") } throws RuntimeException("db down")
        withAlerts(
            alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = "circle-family")
        )
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals(1, posted.size)
    }

    @Test
    fun `a member no alert watches is never evaluated`() = runTest {
        withAlerts(alert(targetUserId = ALICE))
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside("member-bob", now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside("member-bob", now - 30_000L)))

        assertTrue(posted.isEmpty())
        assertTrue("no state should be tracked for an unwatched member", stateDao.rows.isEmpty())
    }

    @Test
    fun `with no active alerts nothing is evaluated`() = runTest {
        withAlerts()
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(inside(ALICE, now)))

        assertTrue(posted.isEmpty())
        assertTrue(stateDao.rows.isEmpty())
    }

    // ===== history / bell record =====

    @Test
    fun `the bell row is stamped with the real crossing time, not now`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val crossedAt = now - 20 * 60 * 1000L

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60 * 1000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, crossedAt)))

        assertEquals(crossedAt, bellRows.single().createdAt)
    }

    @Test
    fun `the bell row id is local-prefixed so it skips the server round-trip`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertTrue(
            "id was ${bellRows.single().id}",
            bellRows.single().id.startsWith("local-"),
        )
    }

    @Test
    fun `a locally evaluated post is flagged so AlertHandler does not re-claim it`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        assertEquals("true", posted.single().second["local_evaluated"])
        assertEquals(PLACE_ID, posted.single().second["place_id"])
    }

    @Test
    fun `the notification carries the place name so it is externally assertable`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 120_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now - 30_000L)))

        val data = posted.single().second
        assertEquals("Home", data["place_name"])
        assertTrue("body must name the place", data["body"]!!.contains("Home"))
        assertEquals("Arrival", data["title"])
    }

    @Test
    fun `a collapsed pass-through is titled Visit, not Arrival`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 60 * 60 * 1000L)))
        evaluator.onLocationsPersisted(
            listOf(inside(ALICE, now - 40 * 60 * 1000L), outside(ALICE, now - 10 * 60 * 1000L))
        )

        assertEquals("Visit", posted.single().second["title"])
    }

    @Test
    fun `a single fix inside the radius is enough - there is no extra dwell requirement`() = runTest {
        // The evaluator adds NO debounce or minimum dwell of its own: one location
        // that lands inside the geofence flips the state and raises the crossing.
        // The only cadence that matters is how often locations arrive at all.
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(outside(ALICE, now - 1_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, now)))

        assertEquals(1, posted.size)
    }

    // ===== the notification stamp must be the CROSSING time =====

    /**
     * The body is stamped with when the crossing HAPPENED, not when this device
     * got round to replaying it.
     *
     * This path evaluates another member's shared location batch, so the points
     * can be hours old by the time they arrive — and only crossings older than
     * STALENESS_WINDOW_MS (2h) are silenced, so everything inside that window
     * still raises a push. Without a `created_at` in the map handed to
     * AlertHandler, eventStamp falls back to System.currentTimeMillis(), and a
     * 90-minute-old arrival is announced as if it had just happened.
     */
    @Test
    fun `stamp carries the crossing time, not the time we noticed`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val crossedAt = now - 90 * 60_000L // 90 min ago: stale, but inside the window

        evaluator.onLocationsPersisted(listOf(outside(ALICE, crossedAt - 60_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, crossedAt)))

        assertEquals(1, posted.size)
        val stamped = posted.single().second["created_at"]
        assertNotNull("the crossing time must be passed to AlertHandler", stamped)
        assertEquals(crossedAt, java.time.Instant.parse(stamped).toEpochMilli())
        // And emphatically NOT "now".
        assertTrue(
            "stamp must not be the evaluation time",
            now - java.time.Instant.parse(stamped).toEpochMilli() > 60 * 60_000L,
        )
    }

    /** The notification and the in-app bell row must agree on when it happened. */
    @Test
    fun `stamp matches the bell row's createdAt`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val crossedAt = now - 45 * 60_000L

        evaluator.onLocationsPersisted(listOf(outside(ALICE, crossedAt - 60_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, crossedAt)))

        assertEquals(1, bellRows.size)
        assertEquals(crossedAt, bellRows.single().createdAt)
        assertEquals(
            bellRows.single().createdAt,
            java.time.Instant.parse(posted.single().second["created_at"]).toEpochMilli(),
        )
    }

    /** A genuinely fresh crossing still stamps its own time, not a rounded "now". */
    @Test
    fun `a fresh crossing is stamped with its own point timestamp`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW
        val crossedAt = now - 5_000L

        evaluator.onLocationsPersisted(listOf(outside(ALICE, crossedAt - 30_000L)))
        evaluator.onLocationsPersisted(listOf(inside(ALICE, crossedAt)))

        assertEquals(
            crossedAt,
            java.time.Instant.parse(posted.single().second["created_at"]).toEpochMilli(),
        )
    }

    // ===== accuracy gate: declining to guess, and saying so =====

    /** Same fixture as [loc] but with a reported accuracy. `home` has radius 150. */
    private fun locAcc(userId: String, lat: Double, ts: Long, accuracy: Double?) =
        loc(userId, lat, ts).copy(accuracy = accuracy)

    @Test
    fun `a fix too coarse for the ring raises no alert`() = runTest {
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        // Seed precisely: Alice is outside and we believe it.
        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.02, now - 120_000L, 10.0)))
        assertTrue("seeding must not notify", posted.isEmpty())

        // She "arrives" — but on a 500m fix against a 150m ring, which says nothing.
        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.0, now - 30_000L, 500.0)))

        assertTrue("a 500m fix must not fire a 150m geofence: $posted", posted.isEmpty())
    }

    @Test
    fun `a coarse fix does not advance state, so the next good fix still fires`() = runTest {
        // The reason the gate filters BEFORE reconcile rather than after: a skipped
        // point must not move the watermark, or the usable fix behind it would be
        // judged against a position we never believed.
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.02, now - 120_000L, 10.0)))
        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.0, now - 60_000L, 500.0)))
        assertTrue(posted.isEmpty())

        // A precise fix at the same place must still read as a fresh arrival.
        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.0, now - 30_000L, 10.0)))

        assertEquals(1, posted.size)
        assertEquals(AlertHandler.TYPE_PLACE_ARRIVAL, posted.single().first)
    }

    @Test
    fun `unknown accuracy still evaluates`() = runTest {
        // Null accuracy is the common case for a sender that never reported it, and
        // it must not disable the watcher's alerts.
        withAlerts(alert())
        val evaluator = newEvaluator()
        val now = NOW

        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.02, now - 120_000L, null)))
        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.0, now - 30_000L, null)))

        assertEquals(1, posted.size)
    }

    @Test
    fun `going dark on my OWN fixes tells the user`() = runTest {
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(locAcc(ME, 37.0, NOW, 500.0)))

        val notice = uxEvents.filterIsInstance<com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse>()
        assertEquals("the user must be told the alert went dark: $uxEvents", 1, notice.size)
        assertEquals(1, notice.single().placeCount)
    }

    @Test
    fun `a MEMBER's coarse fix is not my problem and raises no notice`() = runTest {
        // A member sharing approximately has their accuracy floored to the privacy
        // bubble on purpose (LocationRepository.motionFieldsFor). That is the feature
        // working. Telling the local user their alerts are broken would be wrong.
        withAlerts(alert())
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(locAcc(ALICE, 37.0, NOW, 1609.0)))

        assertTrue(
            "a member's bubble must not raise a local notice: $uxEvents",
            uxEvents.none { it is com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse },
        )
    }

    @Test
    fun `the notice is latched - it fires on the transition, not on every fix`() = runTest {
        // This runs on EVERY stored location. Emitting per batch would put a snackbar
        // on screen every fix.
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()

        repeat(5) { i ->
            evaluator.onLocationsPersisted(listOf(locAcc(ME, 37.0, NOW + i * 1_000L, 500.0)))
        }

        assertEquals(
            "five coarse batches must raise ONE notice: $uxEvents",
            1,
            uxEvents.count { it is com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse },
        )
    }

    @Test
    fun `the notice re-arms once precision comes back`() = runTest {
        withAlerts(alert(targetUserId = ME))
        val evaluator = newEvaluator()

        evaluator.onLocationsPersisted(listOf(locAcc(ME, 37.0, NOW, 500.0)))
        // Precision returns — the latch must clear...
        evaluator.onLocationsPersisted(listOf(locAcc(ME, 37.0, NOW + 1_000L, 10.0)))
        // ...so a SECOND lapse is news again.
        evaluator.onLocationsPersisted(listOf(locAcc(ME, 37.0, NOW + 2_000L, 500.0)))

        assertEquals(
            "a second lapse after recovery must be reported again: $uxEvents",
            2,
            uxEvents.count { it is com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse },
        )
    }
}
