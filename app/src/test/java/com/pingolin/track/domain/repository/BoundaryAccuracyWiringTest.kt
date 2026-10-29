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

import com.pingolin.track.data.local.dao.PrivacySettingDao
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.BoundaryChecker
import uniffi.pingolin_mobile.BoundaryMonitor
import uniffi.pingolin_mobile.CryptoManager

/**
 * That the fix's accuracy actually REACHES the native checker.
 *
 * This asserts wiring, not compilation, and the distinction is the whole point.
 * The UniFFI binding declares `accuracyMeters: Double? = null`, so a version of
 * `checkLocation` that drops the argument on the floor compiles perfectly and
 * behaves as if there were no accuracy gate — every test still green, every
 * crossing still evaluated off a 500 m fix, and every one of them broadcast to
 * the mover's circle.
 *
 * The gate itself is tested in the mobile SDK. What cannot be tested there is
 * whether this app ever gives it the number, and that is what these two tests
 * pin.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoundaryAccuracyWiringTest {

    private lateinit var boundaryChecker: BoundaryChecker
    private lateinit var repo: BoundaryRepository

    @Before
    fun setup() {
        boundaryChecker = mockk(relaxed = true)
        coEvery { boundaryChecker.checkLocation(any(), any(), any()) } returns emptyList()

        repo = BoundaryRepository(
            tracksApi = mockk<TrackApi>(relaxed = true),
            placeRepository = mockk<PlaceRepository>(relaxed = true),
            sessionManager = mockk<SessionManager>(relaxed = true),
            cryptoManager = mockk<CryptoManager>(relaxed = true),
            boundaryChecker = boundaryChecker,
            boundaryMonitor = mockk<BoundaryMonitor>(relaxed = true),
            privacySettingDao = mockk<PrivacySettingDao>(relaxed = true),
            circleRepository = mockk<CircleRepository>(relaxed = true),
            pendingCrossingDao = mockk<com.pingolin.track.data.local.dao.PendingCrossingDao>(relaxed = true),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
        )
    }

    @Test
    fun `the fix's accuracy is handed to the native checker, not dropped`() = runTest {
        repo.checkLocation(latitude = 37.7699, longitude = -122.4469, accuracyMeters = 500.0)

        coVerify(exactly = 1) {
            boundaryChecker.checkLocation(37.7699, -122.4469, 500.0)
        }
        // The failure this guards against is not an exception — it is the
        // argument silently becoming the binding's default, which is
        // indistinguishable from correct behaviour everywhere else.
        coVerify(exactly = 0) {
            boundaryChecker.checkLocation(any(), any(), null)
        }
    }

    @Test
    fun `a fix with no reported accuracy passes null through, and still evaluates`() = runTest {
        // Unknown is not "too coarse": the checker treats null as usable. A
        // caller with no accuracy to give must reach the checker unchanged
        // rather than being turned into some stand-in value here.
        repo.checkLocation(latitude = 37.7699, longitude = -122.4469, accuracyMeters = null)

        coVerify(exactly = 1) {
            boundaryChecker.checkLocation(37.7699, -122.4469, null)
        }
    }
}
