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

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.CurrentKeyGate
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager

/**
 * The gate's "behind" set losing a circle is what wakes the changes waiting to sync while the
 * app is alive; and the catch-up a tap waits for is about three seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingChangeTriggersTest {

    @Test
    fun `this phone catching up on a circle runs the waiting changes, and falling behind doesn't`() = runTest {
        var held = 3
        val circleDao = mockk<CircleDao> { coEvery { getById("c-1") } returns CircleEntity("c-1", "Family", null, 0, 0, keyVersion = 4) }
        val crypto = mockk<CryptoManager> {
            every { getActiveVersion("c-1") } answers { held }
            every { isBehind("c-1") } returns false
        }
        val gate = CurrentKeyGate(circleDao, crypto, mockk(relaxed = true), mockk(relaxed = true))
        val scheduler = mockk<PendingChangeScheduler>(relaxed = true)
        val triggers = PendingChangeTriggers(gate, scheduler, mockk<SessionManager> { every { isLoggedIn() } returns true })
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        triggers.scope = scope

        triggers.start()
        gate.holdsCurrentKey("c-1")
        scope.advanceUntilIdle()
        verify(exactly = 0) { scheduler.kick() }

        held = 4
        gate.holdsCurrentKey("c-1")
        scope.advanceUntilIdle()
        verify(exactly = 1) { scheduler.kick() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `this phone's records no longer saying it's behind runs the waiting changes too`() = runTest {
        // Behind by its own records, whatever version the list shows.
        var recordsBehind = true
        val circleDao = mockk<CircleDao> { coEvery { getById("c-1") } returns CircleEntity("c-1", "Family", null, 0, 0, keyVersion = 4) }
        val crypto = mockk<CryptoManager> {
            every { getActiveVersion("c-1") } returns 4
            every { isBehind("c-1") } answers { recordsBehind }
        }
        val gate = CurrentKeyGate(circleDao, crypto, mockk(relaxed = true), mockk(relaxed = true))
        val scheduler = mockk<PendingChangeScheduler>(relaxed = true)
        val triggers = PendingChangeTriggers(gate, scheduler, mockk<SessionManager> { every { isLoggedIn() } returns true })
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        triggers.scope = scope

        triggers.start()
        gate.holdsCurrentKey("c-1")
        scope.advanceUntilIdle()
        verify(exactly = 0) { scheduler.kick() }

        recordsBehind = false
        gate.noteRecords(listOf("c-1"))
        scope.advanceUntilIdle()
        verify(exactly = 1) { scheduler.kick() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `a tap waits about three seconds for a handover already in flight, no longer`() {
        val repository = TrustHarnessAccess.repository()
        assertEquals(3_000L, repository.catchUpPausesMs.sum())
    }
}

/** A CircleRepository as the trust tests build it, for the one field read here. */
private object TrustHarnessAccess {
    fun repository(): CircleRepository = com.pingolin.track.domain.repository.TrustHarness().repository
}
