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

import com.pingolin.track.ui.map.MapViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every member avatar carries initials that clear WCAG AA.
 *
 * White initials fail on every one of the eight bubbles — the palette is
 * deliberately made of "bright variants", which is exactly what makes white
 * illegible.
 *
 * The palette is read from `MapViewModel.MEMBER_COLORS` rather than copied,
 * so adding a ninth colour without checking it fails here.
 */
class A11yInkTest {

    /** Body-size text. 14sp bold sits near the large-text boundary depending on
     *  how sp maps to points, so the stricter threshold is used deliberately. */
    private val AA = 4.5

    @Test
    fun `every palette colour gets legible initials`() {
        for ((i, bubble) in MapViewModel.MEMBER_COLORS.withIndex()) {
            val ink = A11yInk.ink(bubble)
            val ratio = A11yInk.contrast(bubble, ink)
            assertTrue(
                "MEMBER_COLORS[$i] = #%06X gets %.2f:1, under AA".format(bubble and 0xFFFFFF, ratio),
                ratio >= AA,
            )
        }
    }

    @Test
    fun `white would have failed every one of them`() {
        // If someone goes back to a constant white ink, the test
        // above fails; this one records WHY, with numbers, so the
        // next reader does not have to re-derive them.
        for (bubble in MapViewModel.MEMBER_COLORS) {
            assertTrue(
                "white unexpectedly passes on #%06X".format(bubble and 0xFFFFFF),
                A11yInk.contrast(bubble, A11yInk.WHITE) < AA,
            )
        }
    }

    @Test
    fun `the stale grey bubble is legible too`() {
        // Not in MEMBER_COLORS, but it is what a member with no recent fix
        // renders as, so it needs the same treatment.
        val stale = 0xFF9E9E9E.toInt()
        assertTrue(A11yInk.contrast(stale, A11yInk.ink(stale)) >= AA)
    }

    @Test
    fun `ink flips to white on a dark bubble`() {
        // The rule is luminance, not "always dark" — a future palette entry
        // dark enough to need white initials has to get them.
        assertEquals(A11yInk.WHITE, A11yInk.ink(0xFF1A1040.toInt()))
        assertEquals(A11yInk.DARK, A11yInk.ink(0xFFFCD34D.toInt()))
    }

    @Test
    fun `luminance matches the WCAG reference values`() {
        // Anchors the formula itself, so a refactor that breaks the maths is
        // caught here rather than by every other assertion drifting together.
        assertEquals(0.0, A11yInk.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, A11yInk.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        // Pure white on pure black is the maximum possible ratio, 21:1.
        assertEquals(21.0, A11yInk.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test
    fun `matches the iOS ink value, so a circle looks the same on both`() {
        // The iOS app's MemberPalette.ink(on:) uses #1C1C1E. A divergence
        // here means the same person's avatar differs between platforms.
        assertEquals(0xFF1C1C1E.toInt(), A11yInk.DARK)
    }
}
