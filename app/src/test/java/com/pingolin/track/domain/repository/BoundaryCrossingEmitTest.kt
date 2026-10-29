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

import com.pingolin.track.data.local.dao.PendingCrossingDao
import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.local.entity.PendingCrossingEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.PauseIntervals
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.BoundaryChecker
import uniffi.pingolin_mobile.BoundaryMonitor
import uniffi.pingolin_mobile.CryptoManager

/**
 * Which circles a boundary crossing may be reported to.
 *
 * This is a PRIVACY boundary, not a routing detail. Reporting a crossing to a
 * circle encrypts and hashes "this user entered/exited place X" under that
 * circle's key, so every member of it can decrypt that fact — and the row lands
 * on the server. For a PERSONAL place that is only acceptable for circles the
 * place is actually shared to, which can already see it.
 *
 * An unshared personal place must produce no boundary_event at all, because its
 * alerts are evaluated on the subscriber's own device (PlaceAlertEvaluator).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryCrossingEmitTest {

    private lateinit var tracksApi: TrackApi
    private lateinit var placeRepository: PlaceRepository
    private lateinit var cryptoManager: CryptoManager
    private lateinit var privacySettingDao: PrivacySettingDao
    private lateinit var circleRepository: CircleRepository
    private lateinit var pendingCrossingDao: PendingCrossingDao
    private lateinit var pauseIntervals: PauseIntervals
    private lateinit var repo: BoundaryRepository

    private companion object {
        const val PLACE_ID = "place-home"
        const val ME = "user-me"
        const val CIRCLE = "circle-family"
    }

    private fun personalPlace(
        sharedCircles: String? = null,
        wrapViaCircle: String? = null,
    ) = PlaceEntity(
        id = PLACE_ID,
        circleId = PlaceRepository.PERSONAL_CIRCLE_ID,
        name = "PrivateHome",
        address = null,
        latitude = 37.7699,
        longitude = -122.4469,
        radius = 150,
        icon = null,
        color = null,
        createdAt = 0L,
        updatedAt = 0L,
        sharedCircles = sharedCircles,
        wrapViaCircle = wrapViaCircle,
    )

    @Before
    fun setup() {
        tracksApi = mockk(relaxed = true)
        placeRepository = mockk()
        cryptoManager = mockk()
        privacySettingDao = mockk()
        circleRepository = mockk(relaxed = true)
        // This device holds every circle's current key unless a test says otherwise.
        coEvery { circleRepository.holdsCurrentKey(any()) } returns true
        pendingCrossingDao = mockk(relaxed = true)

        every { cryptoManager.hasCircleKey(any()) } returns true
        // reportCrossing encrypts the event type + device id and derives the routing
        // hash before it POSTs; unstubbed these throw and the emit is swallowed by
        // reportCrossing's catch, which would make the "should emit" cases pass for
        // entirely the wrong reason.
        every { cryptoManager.encryptDataV2(any(), any(), any()) } returns
            uniffi.pingolin_mobile.EncryptedData(nonce = "n", ciphertext = "c")
        every { cryptoManager.computeEventTypeHash(any(), any()) } returns "hash"
        coEvery { privacySettingDao.get(any()) } returns PrivacySettingEntity(
            circleId = CIRCLE,
            shareRealtime = true,
            updatedAt = 0L,
        )

        // A privacy row of the circle came in just now with no pause in it, so every crossing before
        // now is placed: these cases are about where a crossing goes, not when.
        pauseIntervals = PauseIntervals.inMemory()
        pauseIntervals.observe(CIRCLE, notSharing = false, deadlineMs = null, server = emptyList())

        repo = BoundaryRepository(
            tracksApi = tracksApi,
            placeRepository = placeRepository,
            sessionManager = mockk<SessionManager>(relaxed = true),
            cryptoManager = cryptoManager,
            boundaryChecker = mockk<BoundaryChecker>(relaxed = true),
            boundaryMonitor = mockk<BoundaryMonitor>(relaxed = true),
            privacySettingDao = privacySettingDao,
            circleRepository = circleRepository,
            pendingCrossingDao = pendingCrossingDao,
            pauseIntervals = pauseIntervals,
        )
    }

    @Test
    fun `an UNSHARED personal place emits nothing`() = runTest {
        // The headline privacy case: no share, no boundary_event, nothing on the
        // wire. The crossing is detected and notified entirely on this device.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace()

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `an alert's routing circle cannot influence the emit decision at all`() {
        // A structural guard, not a behavioural one. The alert editor lets a
        // personal place be homed to ANY circle the user belongs to, not just
        // circles the place is shared to. If that routing circle were an emit
        // target, a circle that cannot see the place and was never shared it would
        // receive a decryptable "this user entered place X".
        //
        // BoundaryRepository takes no PlaceAlertDao, so there is no seam through
        // which an alert can reach the emit decision. Asserting on the constructor
        // is what this test CAN cover; it cannot cover the leak behaviourally,
        // because with no seam to inject an alert through a behavioural test would
        // only re-run the unshared-place case above.
        val takesPlaceAlertDao = BoundaryRepository::class.java.constructors.any { ctor ->
            ctor.parameterTypes.any { it.simpleName == "PlaceAlertDao" }
        }
        org.junit.Assert.assertFalse(
            "BoundaryRepository must not consult place alerts when deciding where to emit",
            takesPlaceAlertDao,
        )
    }

    @Test
    fun `a SHARED personal place still emits, so remote subscribers keep working`() = runTest {
        // The legitimate case must survive: a circle the place is shared to can
        // already see it, and may hold a remote subscriber that needs the
        // blind-server route.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `a received share still emits via wrapViaCircle`() = runTest {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(wrapViaCircle = CIRCLE)

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `a CIRCLE place is unaffected`() = runTest {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns
            personalPlace().copy(circleId = CIRCLE)

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `a shared circle that is paused still suppresses the emit`() = runTest {
        // The privacy-posture gate still applies on top of the
        // shared-circles rule.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { privacySettingDao.get(CIRCLE) } returns PrivacySettingEntity(
            circleId = CIRCLE,
            shareRealtime = false,
            updatedAt = 0L,
        )

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `a crossing for a circle whose key moved on without this device is held, not dropped`() = runTest {
        // The only key this device could seal "this user entered place X" under is
        // one a member removed since may hold too. Not a decision to stay quiet,
        // like a pause: the crossing is queued, and sealed once the key arrives.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { circleRepository.holdsCurrentKey(CIRCLE) } returns false

        val result = repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter", occurredAtMs = 1_700_000_000_000L)

        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }
        verify(exactly = 0) { cryptoManager.encryptDataV2(any(), any(), any()) }
        coVerify(exactly = 1) {
            pendingCrossingDao.insert(match<PendingCrossingEntity> { it.occurredAtMs == 1_700_000_000_000L })
        }
        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
    }

    @Test
    fun `a held crossing stays queued until the key arrives, then goes out`() = runTest {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { circleRepository.holdsCurrentKey(CIRCLE) } returns false

        // The replay (enqueueOnFailure = false) while still behind: a failure, so
        // syncPendingCrossings keeps the row.
        assertTrue(repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter", enqueueOnFailure = false).isFailure)
        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }

        coEvery { circleRepository.holdsCurrentKey(CIRCLE) } returns true
        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter", enqueueOnFailure = false)

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }

    // ── The outbox ────────────────────────────────────────────────────────────
    //
    // A crossing report goes through an outbox, as LOCATIONS do through
    // pendingLocationDao: a bare call whose Result is discarded, with no queue
    // and no retry, would make a lost request a lost alert, and an invisible
    // one — an alert that never fires looks exactly like one nobody subscribed
    // to.
    //
    // What must be queued is a report that reached NOBODY. What must NOT be queued
    // is anything that was never meant to be sent, or there is a retry loop against
    // a decision the code made on purpose.

    @Test
    fun `a report that reaches nobody is queued for retry`() = runTest {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { tracksApi.reportBoundaryCrossing(any()) } throws java.io.IOException("radio down")

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter", occurredAtMs = 1_700_000_000_000L)

        // The INPUTS are queued, not a ciphertext: the replay re-derives the circle
        // set and every blob under whatever key is current, so a rotation between
        // crossing and replay is a non-event.
        coVerify(exactly = 1) {
            pendingCrossingDao.insert(
                match<PendingCrossingEntity> {
                    it.placeId == PLACE_ID &&
                        it.userId == ME &&
                        it.crossingType == "enter" &&
                        // The crossing's own time, so the replay stamps when it
                        // HAPPENED rather than when it was retried.
                        it.occurredAtMs == 1_700_000_000_000L
                }
            )
        }
    }

    @Test
    fun `a report that succeeds is not queued`() = runTest {
        // Control for the test above: if the success path queued too, that test
        // would pass without the failure having anything to do with it.
        //
        // The success response has to be stubbed EXPLICITLY. `tracksApi` is a
        // relaxed mock, so an unstubbed `reportBoundaryCrossing` answers with a
        // default whose `isSuccessful` is false — which means every other test in
        // this class proves the POST was ATTEMPTED, not that it landed. That is
        // fine for those (they are about which circles are targeted) and fatal
        // here, because "attempted" and "succeeded" are the two states this test
        // exists to tell apart.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { tracksApi.reportBoundaryCrossing(any()) } returns retrofit2.Response.success(
            com.pingolin.track.data.remote.dto.ApiResponse(
                success = true,
                data = com.pingolin.track.data.remote.dto.ReportBoundaryCrossingResponse(
                    eventId = "event-1",
                    notificationsSent = 0,
                ),
                error = null,
            )
        )

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
        coVerify(exactly = 0) { pendingCrossingDao.insert(any()) }
    }

    @Test
    fun `an unshared personal place is not queued`() = runTest {
        // There was no failure — there was nothing to send. Queueing here would
        // retry, forever, a crossing that must never leave the device.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace()

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 0) { pendingCrossingDao.insert(any()) }
    }

    @Test
    fun `a posture-suppressed circle is not queued`() = runTest {
        // Same principle: a paused circle is a decision, not a delivery failure.
        // Queueing it would resend the crossing the moment the user unpaused,
        // disclosing presence from the window they chose to hide.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { privacySettingDao.get(CIRCLE) } returns PrivacySettingEntity(
            circleId = CIRCLE,
            shareRealtime = false,
            updatedAt = 0L,
        )

        repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter")

        coVerify(exactly = 0) { pendingCrossingDao.insert(any()) }
    }

    @Test
    fun `a failed replay updates its own row instead of queueing a second one`() = runTest {
        // enqueueOnFailure=false is what syncPendingCrossings passes. Without it the
        // queue grows a row per attempt, and the age bound then evicts the ORIGINAL
        // first while its copies live on.
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        coEvery { tracksApi.reportBoundaryCrossing(any()) } throws java.io.IOException("still down")

        repo.reportCrossingFromMonitor(
            PLACE_ID, ME, "device-1", "enter",
            occurredAtMs = 1_700_000_000_000L,
            enqueueOnFailure = false,
        )

        coVerify(exactly = 0) { pendingCrossingDao.insert(any()) }
    }

    // ── A replay is judged at its own time ───────────────────────────────────
    //
    // Crossing times are sealed, so the server judges a replayed crossing only by the posture now. A
    // pause set and ended on another phone while this one was offline would let a crossing from inside
    // it through. The phone judges it against the not-sharing spans at its own time, as a late point.

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun replayAt(occurredAtMs: Long) = run {
        repo.wallNow = { now }
        kotlinx.coroutines.runBlocking {
            repo.reportCrossingFromMonitor(PLACE_ID, ME, "device-1", "enter", occurredAtMs = occurredAtMs, enqueueOnFailure = false)
        }
    }

    @Test
    fun `a crossing replayed late that happened while not sharing there is never sent, and leaves the queue`() {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        pauseIntervals = PauseIntervals.inMemory(now = { now })
        pauseIntervals.observe(CIRCLE, notSharing = false, deadlineMs = null,
            server = listOf(PauseIntervals.Interval(now - 60 * minute, now - 30 * minute)))
        repo = BoundaryRepository(tracksApi, placeRepository, mockk(relaxed = true), cryptoManager, mockk(relaxed = true),
            mockk(relaxed = true), privacySettingDao, circleRepository, pendingCrossingDao, pauseIntervals)

        val result = replayAt(now - 45 * minute)

        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }
        assertTrue("decided, so the replay drops it", result.isSuccess)
    }

    @Test
    fun `a late crossing the pause record can't place yet is held in the queue`() {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        pauseIntervals = PauseIntervals.inMemory(now = { now })
        repo = BoundaryRepository(tracksApi, placeRepository, mockk(relaxed = true), cryptoManager, mockk(relaxed = true),
            mockk(relaxed = true), privacySettingDao, circleRepository, pendingCrossingDao, pauseIntervals)

        val result = replayAt(now - 10 * minute)

        coVerify(exactly = 0) { tracksApi.reportBoundaryCrossing(any()) }
        assertTrue(result.exceptionOrNull() is CrossingNotPlacedException)
    }

    @Test
    fun `a late crossing outside every not-sharing span, once the record knows, goes`() {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        pauseIntervals = PauseIntervals.inMemory(now = { now })
        pauseIntervals.observe(CIRCLE, notSharing = false, deadlineMs = null,
            server = listOf(PauseIntervals.Interval(now - 60 * minute, now - 30 * minute)))
        repo = BoundaryRepository(tracksApi, placeRepository, mockk(relaxed = true), cryptoManager, mockk(relaxed = true),
            mockk(relaxed = true), privacySettingDao, circleRepository, pendingCrossingDao, pauseIntervals)

        replayAt(now - 10 * minute)

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }

    @Test
    fun `a crossing just detected goes by the posture now, with no wait for the record`() {
        coEvery { placeRepository.getPlace(PLACE_ID) } returns personalPlace(sharedCircles = CIRCLE)
        pauseIntervals = PauseIntervals.inMemory(now = { now })
        repo = BoundaryRepository(tracksApi, placeRepository, mockk(relaxed = true), cryptoManager, mockk(relaxed = true),
            mockk(relaxed = true), privacySettingDao, circleRepository, pendingCrossingDao, pauseIntervals)

        replayAt(now - 30_000L)

        coVerify(exactly = 1) { tracksApi.reportBoundaryCrossing(any()) }
    }
}
