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

package com.pingolin.track.location

import android.location.LocationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one decision the libre [LocationProvider] has to make that fused made for
 * us.
 *
 * Fused merges GPS and wifi/cell behind a single subscription;
 * [PlatformLocationProvider] subscribes to both providers separately, so their
 * callbacks interleave and something has to choose which fix to forward. Get it
 * wrong and a 2 km cell fix arriving a moment after a 5 m satellite fix walks
 * the user's marker across town — and, worse, feeds that jump to the place-alert
 * evaluator, which would fire an arrival at somewhere the user has never been.
 *
 * `src/testLibre`, because [PlatformLocationProvider] exists only in the libre
 * flavour.
 */
class PlatformLocationProviderTest {

    private fun better(
        candidateAgeMs: Long,
        candidateAccuracyM: Float,
        currentAgeMs: Long,
        currentAccuracyM: Float,
    ) = PlatformLocationProvider.isBetterFix(
        candidateAgeMs, candidateAccuracyM, currentAgeMs, currentAccuracyM
    )

    @Test
    fun `the first fix is always taken`() {
        assertTrue(better(0, 500f, Long.MAX_VALUE, Float.MAX_VALUE))
    }

    /**
     * The real interleaving, and the reason this function exists at all: within
     * the comparable-age band ACCURACY decides, not arrival order. NETWORK
     * routinely resolves a beat AFTER GPS, so "whichever came last" would let a
     * 1500 m cell fix displace the 5 m satellite fix that preceded it by a
     * second — and walk the marker across town every time both providers report.
     *
     * Both directions are asserted with the accuracy and the age pulling in
     * OPPOSITE directions on purpose. Asserting them aligned (newer AND more
     * accurate) is vacuous: an implementation that ignored accuracy entirely
     * would pass it.
     */
    @Test
    fun `at comparable age the more accurate fix wins even when it is slightly older`() {
        // A 5 m satellite fix one second older than a 1500 m cell fix is still
        // the better answer.
        assertTrue(
            "the satellite fix should win despite being a second older",
            better(candidateAgeMs = 2_000, candidateAccuracyM = 5f, currentAgeMs = 1_000, currentAccuracyM = 1500f),
        )
        // And the converse: a newer coarse fix must not displace it.
        assertFalse(
            "the cell fix must not displace the satellite one just by arriving later",
            better(candidateAgeMs = 1_000, candidateAccuracyM = 1500f, currentAgeMs = 2_000, currentAccuracyM = 5f),
        )
    }

    @Test
    fun `a decisively newer fix wins even when it is coarser`() {
        // Ten minutes on, where the user IS matters more than how precisely we
        // knew where they WERE.
        assertTrue(better(candidateAgeMs = 0, candidateAccuracyM = 1500f, currentAgeMs = 10 * 60_000, currentAccuracyM = 5f))
    }

    @Test
    fun `a decisively older fix loses however precise it is`() {
        // A stale satellite fix surfacing late must not rewind the marker.
        assertFalse(better(candidateAgeMs = 10 * 60_000, candidateAccuracyM = 1f, currentAgeMs = 0, currentAccuracyM = 1500f))
    }

    @Test
    fun `equal accuracy goes to the newer fix so the timestamp keeps moving`() {
        assertTrue("the newer of two equally accurate fixes should be forwarded", better(500, 10f, 2_000, 10f))
        assertFalse("the older of two equally accurate fixes should not", better(2_000, 10f, 500, 10f))
    }

    // ---- which providers a subscription listens to -------------------------------

    private val both = setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

    @Test
    fun `a HIGH subscription listens to GPS and NETWORK`() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            PlatformLocationProvider.subscriptionProvidersFor(LocationAccuracy.HIGH, both),
        )
    }

    /**
     * BALANCED is the STILL and battery-saver poll, so it subscribes NETWORK only:
     * subscribing GPS as well would keep the satellite receiver running on every one
     * of those polls and make them cost what a HIGH poll costs on this flavour.
     */
    @Test
    fun `a BALANCED subscription listens to NETWORK only`() {
        assertEquals(
            listOf(LocationManager.NETWORK_PROVIDER),
            PlatformLocationProvider.subscriptionProvidersFor(LocationAccuracy.BALANCED, both),
        )
    }

    @Test
    fun `BALANCED falls back to GPS only on a device with no network provider`() {
        assertEquals(
            listOf(LocationManager.GPS_PROVIDER),
            PlatformLocationProvider.subscriptionProvidersFor(
                LocationAccuracy.BALANCED, setOf(LocationManager.GPS_PROVIDER),
            ),
        )
        assertTrue(PlatformLocationProvider.subscriptionProvidersFor(LocationAccuracy.BALANCED, emptySet()).isEmpty())
    }
}
