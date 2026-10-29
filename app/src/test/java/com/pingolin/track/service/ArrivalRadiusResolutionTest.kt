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

import com.pingolin.track.service.LocationTrackingService.Companion.canResolveArrivalRadius
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a fix is precise enough to decide a destination arrival.
 *
 * Auto-arrival is a 100m test (DESTINATION_ARRIVAL_RADIUS_METERS) run on every
 * accepted fix. Battery-saver ACCEPTS fixes up to 500m
 * (LocationPolicy.MIN_ACCURACY_BALANCED), and a fix whose own error exceeds the
 * ring is consistent with being inside OR outside it — so firing from one is a
 * guess.
 *
 * What makes this worth a gate rather than a shrug: reportArrival announces the
 * arrival to the user's whole circle and flips the destination's status. There is
 * no undo, and the user is not the one who pressed anything.
 *
 * Same rule as PlaceAlertRules.canResolve, kept deliberately identical in shape so
 * the two do not drift into disagreeing about what "too coarse" means.
 */
class ArrivalRadiusResolutionTest {

    @Test
    fun aFixRougherThanTheArrivalRadiusCannotDecideIt() {
        assertFalse("battery-saver's 500m fix must not fire a 100m arrival", canResolveArrivalRadius(500f))
        assertFalse(canResolveArrivalRadius(100.001f))
    }

    @Test
    fun aFixAtOrInsideTheArrivalRadiusDecidesIt() {
        // Inclusive at the boundary, matching PlaceAlertRules.canResolve.
        assertTrue(canResolveArrivalRadius(100f))
        assertTrue(canResolveArrivalRadius(12f))
        assertTrue(canResolveArrivalRadius(0f))
    }

    @Test
    fun unknownAccuracyStillEvaluates() {
        // A fix with no reported accuracy must not disable arrivals — unknown is not
        // the same as too coarse, and treating it as such would silently break
        // auto-arrival on any provider that omits the field.
        assertTrue(canResolveArrivalRadius(null))
    }
}
