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

import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.PremiumStatusResponse
import com.pingolin.track.data.remote.dto.SubscriptionInfo
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for PremiumStatusRefresher.
 *
 * PremiumStatusRefresher owns the fetch + fallback + SessionManager caching for
 * every caller — SettingsViewModel, MainActivity's startup refresh and the
 * destination premium gates — so the tier-derivation scenarios are asserted
 * here rather than in any one ViewModel's tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumStatusRefresherTest {

    @MockK
    private lateinit var tracksApi: TrackApi

    @MockK
    private lateinit var authApi: AuthApi

    @MockK
    private lateinit var sessionManager: SessionManager

    private lateinit var refresher: PremiumStatusRefresher

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        every { sessionManager.isLoggedIn() } returns true
        every { sessionManager.setEffectivePremium(any()) } just Runs
        every { sessionManager.setPremiumSource(any()) } just Runs
        every { sessionManager.setSubscriptionTier(any()) } just Runs

        refresher = PremiumStatusRefresher(tracksApi, authApi, sessionManager)
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `logged out returns null without touching the network`() = runTest {
        every { sessionManager.isLoggedIn() } returns false

        assertNull(refresher.refresh())

        coVerify(exactly = 0) { tracksApi.getPremiumStatus() }
        coVerify(exactly = 0) { authApi.getSubscriptions() }
    }

    @Test
    fun `prefers effective premium endpoint and caches circle-derived tier`() = runTest {
        coEvery { tracksApi.getPremiumStatus() } returns Response.success(
            ApiResponse(
                success = true,
                data = PremiumStatusResponse(hasPremium = true, source = "circle"),
                error = null
            )
        )

        assertEquals("Premium · Circle", refresher.refresh())

        verify { sessionManager.setEffectivePremium(true) }
        verify { sessionManager.setPremiumSource("circle") }
        verify { sessionManager.setSubscriptionTier("Premium · Circle") }
        // The fallback must not have been consulted.
        coVerify(exactly = 0) { authApi.getSubscriptions() }
    }

    private fun ownSubscriptions(vararg subs: SubscriptionInfo) {
        coEvery { tracksApi.getPremiumStatus() } throws java.io.IOException("endpoint unavailable")
        coEvery { authApi.getSubscriptions() } returns Response.success(ApiResponse(success = true, data = subs.toList(), error = null))
    }

    private fun assertNothingWritten() {
        verify(exactly = 0) { sessionManager.setEffectivePremium(any()) }
        verify(exactly = 0) { sessionManager.setPremiumSource(any()) }
        verify(exactly = 0) { sessionManager.setSubscriptionTier(any()) }
    }

    @Test
    fun `fallback derives Premium from an active Track subscription`() = runTest {
        ownSubscriptions(SubscriptionInfo(status = "active", productSlug = "track"))

        assertEquals("Premium", refresher.refresh())
        verify { sessionManager.setEffectivePremium(true) }
        verify { sessionManager.setPremiumSource("own") }
    }

    @Test
    fun `fallback derives Premium from a trialing Track subscription`() = runTest {
        ownSubscriptions(SubscriptionInfo(status = "trialing", productSlug = "track"))

        assertEquals("Premium", refresher.refresh())
    }

    @Test
    fun `a subscription to another product is not Track Premium`() = runTest {
        ownSubscriptions(SubscriptionInfo(status = "active", productSlug = "note"))

        assertNull(refresher.refresh())
        assertNothingWritten()
    }

    @Test
    fun `an entry naming no product decides nothing, whatever service and plan it names`() = runTest {
        ownSubscriptions(SubscriptionInfo(service = "track", plan = "pro", status = "active"))

        assertNull(refresher.refresh())
        assertNothingWritten()
    }

    @Test
    fun `the fallback never makes the plan Free - a circle's plan can't be seen there`() = runTest {
        ownSubscriptions(SubscriptionInfo(status = "canceled", productSlug = "track"))
        assertNull(refresher.refresh())

        ownSubscriptions()
        assertNull(refresher.refresh())
        assertNothingWritten()
    }

    @Test
    fun `returns null and writes nothing when both endpoints fail`() = runTest {
        // Offline: an inconclusive refresh must not downgrade a cached
        // Premium to Free — callers keep their cached value on null.
        coEvery { tracksApi.getPremiumStatus() } throws java.io.IOException("down")
        coEvery { authApi.getSubscriptions() } throws java.io.IOException("down")

        assertNull(refresher.refresh())

        verify(exactly = 0) { sessionManager.setEffectivePremium(any()) }
        verify(exactly = 0) { sessionManager.setPremiumSource(any()) }
        verify(exactly = 0) { sessionManager.setSubscriptionTier(any()) }
    }
}
