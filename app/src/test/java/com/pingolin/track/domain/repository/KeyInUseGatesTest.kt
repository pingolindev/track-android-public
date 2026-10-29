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

import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.hasKeyInUse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.MobileException

/**
 * After a password reset and a restore, a personal slot can hold only the RETIRED old key.
 * `hasCircleKey` counts it — it still decrypts old rows, so READ gates keep it — but a WRITE
 * gate (seal, mint, wrap, publish) must ask for a key IN USE, or it seals under the old key or
 * never publishes the new one.
 */
class KeyInUseGatesTest {

    private val crypto = mockk<CryptoManager>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val self = LocationRepository.SELF_CIRCLE_ID
    private val nil = LocationRepository.SELF_CIRCLE_UUID

    /** The slot holds only the retired pre-reset key: held, with no active version. */
    private fun onlyRetired(slot: String) {
        every { crypto.hasCircleKey(slot) } returns true
        every { crypto.getActiveVersion(slot) } throws MobileException.KeyNotFound("no key in use")
        every { crypto.hasCircleKeyInUse(slot) } returns false
    }

    private fun inUse(slot: String) {
        every { crypto.hasCircleKey(slot) } returns true
        every { crypto.getActiveVersion(slot) } returns 1
        every { crypto.hasCircleKeyInUse(slot) } returns true
    }

    private lateinit var locations: LocationRepository

    @Before
    fun setUp() {
        every { sessionManager.getUserId() } returns "me"
        locations = LocationRepository(
            locationProvider = mockk(relaxed = true),
            locationDao = mockk(relaxed = true),
            pendingLocationDao = mockk(relaxed = true),
            circleDao = mockk(relaxed = true),
            privacySettingDao = mockk(relaxed = true),
            tracksApi = mockk(relaxed = true),
            authApi = mockk(relaxed = true),
            sessionManager = sessionManager,
            tokenRefresher = mockk(relaxed = true),
            cryptoManager = crypto,
            uxEventBus = com.pingolin.track.util.UxEventBus(),
            queueMonitor = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            placeAlertEvaluator = mockk(relaxed = true),
            currentKeyGate = CurrentKeyGate(mockk(relaxed = true), crypto, mockk(relaxed = true), sessionManager),
            queuedCircleLocationDao = mockk(relaxed = true),
            approximateMembers = com.pingolin.track.util.ApproximateMembersStore.inMemory(),
            newestOwnFix = com.pingolin.track.location.NewestOwnFix(sessionManager),
            pauseIntervals = com.pingolin.track.util.PauseIntervals.inMemory(),
            approximateIntervals = com.pingolin.track.util.ApproximateIntervals.inMemory(),
        )
    }

    @Test
    fun `a key in use is the SDK's - held, and the one it seals under`() {
        onlyRetired(self)
        assertFalse(crypto.hasKeyInUse(self))
        inUse(self)
        assertTrue(crypto.hasKeyInUse(self))
        every { crypto.hasCircleKeyInUse(self) } throws MobileException.InvalidInput("not open")
        assertFalse("an SDK that can't tell says no", crypto.hasKeyInUse(self))
    }

    @Test
    fun `nothing personal is sealed under the retired key`() = runTest {
        onlyRetired(nil)
        every { crypto.encryptLocation(any(), any()) } returns EncryptedData("n", "c")

        locations.storeLocalLocation(mockk(relaxed = true))
        verify(exactly = 0) { crypto.encryptLocation(nil, any()) }

        inUse(nil)
        locations.storeLocalLocation(mockk(relaxed = true))
        verify(exactly = 1) { crypto.encryptLocation(nil, any()) }
    }

    @Test
    fun `personal places with only the retired key still set the new key up`() = runTest {
        onlyRetired(PlaceRepository.PERSONAL_CIRCLE_ID)
        val locationRepository = mockk<LocationRepository>(relaxed = true)
        coEvery { locationRepository.ensurePersonalKeyExists() } returns false
        val places = PlaceRepository(
            tracksApi = mockk(relaxed = true),
            placeDao = mockk(relaxed = true),
            placeAlertDao = mockk(relaxed = true),
            cryptoManager = crypto,
            locationRepository = locationRepository,
            sessionManager = sessionManager,
            circleRepository = { mockk(relaxed = true) },
            pendingChanges = com.pingolin.track.testutil.quietPendingChanges(),
        )

        places.refreshPlaces()

        coVerify(atLeast = 1) { locationRepository.ensurePersonalKeyExists() }
    }
}
