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

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Ink that can actually be read on a coloured background.
 *
 * White bold initials fail WCAG AA on every bubble in
 * `MapViewModel.MEMBER_COLORS` — they are deliberately "bright variants for
 * dark mode visibility", which is exactly what makes white illegible on them:
 *
 *     Amber #FCD34D 1.44:1   Teal #5EEAD4 1.48:1   Emerald #4ADE80 1.74:1
 *     Blue  #93C5FD 1.80:1   Pink #F9A8D4 1.81:1   Purple  #C4B5FD 1.85:1
 *     Red   #FCA5A5 1.90:1   grey #9E9E9E 2.68:1   Indigo  #818CF8 2.98:1
 *
 * Not one reaches the 4.5:1 that avatar initials need, and not one reaches the
 * lenient 3:1 either, so the conclusion does not depend on where 14sp bold
 * falls against the large-text threshold.
 *
 * Chosen by luminance rather than hard-coded, so a future palette entry dark
 * enough to need white initials gets them. This is a port of the iOS app's
 * `MemberPalette.ink(on:)` — same rule, same [DARK] value — because the
 * members of a circle should see the same avatars on every platform.
 *
 * Deliberately free of `android.graphics.Color`: plain integer arithmetic runs
 * in a normal JVM unit test, and `A11yInkTest` pins the whole palette at AA.
 */
object A11yInk {

    /** Near-black. The same value the iOS app uses, not pure black,
     * so the initials sit slightly softer on light bubbles. */
    const val DARK = 0xFF1C1C1E.toInt()

    const val WHITE = 0xFFFFFFFF.toInt()

    /** Whichever of [DARK] and [WHITE] reads better on [background]. */
    fun ink(background: Int): Int =
        if (contrast(background, DARK) >= contrast(background, WHITE)) DARK else WHITE

    /** WCAG 2.x contrast ratio between two opaque ARGB colours. */
    fun contrast(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG relative luminance. Alpha is ignored: these are opaque fills. */
    fun relativeLuminance(color: Int): Double {
        fun channel(component: Int): Double {
            val c = component / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
