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

package com.pingolin.track.service

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The permission gate and the accuracy gate have to agree.
 *
 * A coarse-only grant is not success, for MainActivity or the service:
 * otherwise a boot receiver, watchdog alarm or push wake would start tracking
 * and raise the persistent "Location sharing active" notification — and then
 * every fix that grant can produce would be silently thrown away by an accuracy
 * filter that rejects anything worse than 100m, while a coarse fix is
 * kilometre-scale. The user would answer the prompt, see the notification,
 * share nothing, and be told nothing.
 *
 * Separately, each mode has its own threshold: with the real-time threshold in
 * both modes, battery-saver would discard every fix between 100 m and 500 m,
 * reporting nothing rather than something approximate.
 *
 * Not because battery-saver requests rougher fixes; it does not. See
 * LocationPolicy.MIN_ACCURACY_BALANCED for what that threshold actually is and
 * where Android's coarse fixes really originate.
 *
 * These tests pin both halves and, more importantly, pin them TOGETHER: what
 * makes the coarse grant unusable is the number the filter enforces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = android.app.Application::class)
class LocationPolicyTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun grant(vararg permissions: String) {
        val app = shadowOf(context)
        app.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (permissions.isNotEmpty()) app.grantPermissions(*permissions)
    }

    // ---- the permission gate --------------------------------------------------

    @Test
    fun `approximate location alone is not enough to track`() {
        // In one line: the system calls this a grant; this app must not,
        // because nothing it produces survives the accuracy filter.
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertFalse(
            "a coarse-only grant must never start tracking",
            LocationPolicy.hasPreciseLocation(context)
        )
        assertTrue(
            "and it must be recognisable as its own state, so the user can be told",
            LocationPolicy.hasCoarseLocationOnly(context)
        )
    }

    @Test
    fun `precise location tracks`() {
        // Android grants COARSE implicitly alongside FINE; the policy must read
        // that as precise, not as the coarse-only case.
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertTrue(LocationPolicy.hasPreciseLocation(context))
        assertFalse(
            "holding FINE is not the state that needs explaining to the user",
            LocationPolicy.hasCoarseLocationOnly(context)
        )
    }

    @Test
    fun `no location grant is distinct from an approximate one`() {
        // Two different messages: "we need permission" vs "we need PRECISE
        // permission, which you have to change in Settings".
        grant()

        assertFalse(LocationPolicy.hasPreciseLocation(context))
        assertFalse(LocationPolicy.hasCoarseLocationOnly(context))
    }

    // ---- the accuracy gate ----------------------------------------------------

    @Test
    fun `battery saver mode uses its own threshold`() {
        // Each mode reads its own constant: with MIN_ACCURACY_HIGH in
        // both, battery-saver would reject everything its own
        // low-power provider returns between 100m and 500m. Matches
        // the iOS app.
        assertEquals(LocationPolicy.MIN_ACCURACY_HIGH, LocationPolicy.minAccuracyMeters(batterySaver = false), 0f)
        assertEquals(LocationPolicy.MIN_ACCURACY_BALANCED, LocationPolicy.minAccuracyMeters(batterySaver = true), 0f)

        val roughButUsable = 250f
        assertFalse(
            "250m is not good enough for real-time sharing",
            LocationPolicy.acceptsFix(roughButUsable, batterySaver = false)
        )
        assertTrue(
            "but battery-saver asked for exactly this and must keep it",
            LocationPolicy.acceptsFix(roughButUsable, batterySaver = true)
        )
    }

    @Test
    fun `a coarse fix is discarded in every mode`() {
        // This is why the permission gate has to refuse coarse rather than
        // merely warn about it: there is no mode in which the fixes arrive.
        // ACCESS_COARSE_LOCATION is documented as accurate to about a city
        // block at best and is routinely kilometre-scale.
        listOf(1_000f, 3_000f, 10_000f).forEach { coarse ->
            assertFalse(
                "${coarse}m must not be shared as someone's position",
                LocationPolicy.acceptsFix(coarse, batterySaver = false)
            )
            assertFalse(
                "${coarse}m must not be shared as someone's position in battery-saver either",
                LocationPolicy.acceptsFix(coarse, batterySaver = true)
            )
        }
    }

    @Test
    fun `the thresholds are inclusive at the boundary`() {
        assertTrue(LocationPolicy.acceptsFix(LocationPolicy.MIN_ACCURACY_HIGH, batterySaver = false))
        assertFalse(LocationPolicy.acceptsFix(LocationPolicy.MIN_ACCURACY_HIGH + 1f, batterySaver = false))
        assertTrue(LocationPolicy.acceptsFix(LocationPolicy.MIN_ACCURACY_BALANCED, batterySaver = true))
        assertFalse(LocationPolicy.acceptsFix(LocationPolicy.MIN_ACCURACY_BALANCED + 1f, batterySaver = true))
    }
}
