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

package com.pingolin.track.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The member marker's vertical layout, which two things depend on agreeing:
 * the bitmap builder that bakes the icon, and the occlusion test that decides
 * whether an avatar is covering a place's pin.
 *
 * The occlusion test compares SCREEN positions, but the marker is anchored by
 * its bitmap's centre while the disc sits at the top with labels hanging below
 * — so the disc renders above the member's actual coordinate. Comparing anchors
 * instead of discs misjudges by half the label stack, which on the 40dp
 * threshold is a ~40% error. That offset is what this file pins.
 */
class MemberMarkerGeometryTest {

    /** A typical phone display density (~xhdpi). */
    private val density = 2.625f

    @Test
    fun `the disc rises above the anchor by half the label stack`() {
        val below = MemberMarkerGeometry.belowDiscPx(density, atPlace = false, hasSos = false)
        val rise = MemberMarkerGeometry.discRiseAboveAnchorPx(density, atPlace = false, hasSos = false)

        assertEquals(below / 2f, rise, 0.01f)
        assertTrue("the rise must be a real offset, not a rounding artefact", rise > 4f)
    }

    @Test
    fun `an at-place marker rises further than a plain one`() {
        // "at <Place>" adds a whole band below the disc, which pushes the disc
        // further up. This is exactly the case the occlusion test runs in — a
        // member standing on a place always has the place label — so getting it
        // wrong would bias every comparison that matters.
        val plain = MemberMarkerGeometry.discRiseAboveAnchorPx(density, atPlace = false, hasSos = false)
        val atPlace = MemberMarkerGeometry.discRiseAboveAnchorPx(density, atPlace = true, hasSos = false)

        assertTrue("at-place: $atPlace should exceed plain: $plain", atPlace > plain)
        assertEquals(
            "the difference is exactly one text band, halved",
            MemberMarkerGeometry.ageBandPx(density) / 2f,
            atPlace - plain,
            0.01f,
        )
    }

    @Test
    fun `an SOS marker has no rise because it is top-padded`() {
        // SOS bitmaps pad the top by the whole label stack so the disc's centre
        // lands ON the anchor — that is what lets the pulse ring the avatar. The
        // occlusion test must not then subtract an offset that isn't there.
        assertEquals(
            0f,
            MemberMarkerGeometry.discRiseAboveAnchorPx(density, atPlace = true, hasSos = true),
            0.01f,
        )
    }

    @Test
    fun `the occlusion threshold is under the disc diameter`() {
        // Above the diameter the two markers would not even touch, so the pin
        // would be hidden while plainly visible beside the avatar.
        assertTrue(
            MemberMarkerGeometry.OCCLUSION_DP < MemberMarkerGeometry.DISC_DP,
        )
        // And above the radius, so a pin is only hidden once it is substantially
        // behind the avatar rather than merely brushing it.
        assertTrue(
            MemberMarkerGeometry.OCCLUSION_DP > MemberMarkerGeometry.DISC_DP / 2f,
        )
    }

    /**
     * The behaviour that matters: the hide distance follows the CAMERA, not the
     * place's radius. Same place, same member, different zoom = a different
     * answer — which is what makes "zoom in to reach the pin" work.
     */
    @Test
    fun `the threshold in metres shrinks as you zoom in`() {
        fun metresAtZoom(zoom: Int): Double {
            // Web Mercator at ~37.6°N — the 0.7923 factor is cos(latitude), so
            // this is a mid-latitude scale — rendered at the device's pixel ratio.
            val metresPerCssPx = 156543.03392 * 0.7923 / Math.pow(2.0, zoom.toDouble())
            val metresPerDevicePx = metresPerCssPx / density
            return MemberMarkerGeometry.OCCLUSION_DP * density * metresPerDevicePx
        }

        val z15 = metresAtZoom(15)
        val z17 = metresAtZoom(17)
        val z18 = metresAtZoom(18)

        assertTrue("zooming in must narrow the hide radius", z17 < z15)
        assertTrue(z18 < z17)
        // Each zoom level halves it.
        assertTrue("z17 should be ~a quarter of z15", abs(z15 / 4 - z17) < 1.0)
        // And at street zoom it must be far under a typical 150 m place
        // radius, rather than hiding the pin across the whole radius at
        // every zoom.
        assertTrue("z17 hide radius $z17 m must be well under a 150 m place", z17 < 60.0)
    }
}
