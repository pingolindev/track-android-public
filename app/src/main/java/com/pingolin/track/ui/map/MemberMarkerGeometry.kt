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

/**
 * The vertical layout of a member's map marker, in one place.
 *
 * Two things need this and must agree exactly: `MapFragment.getOrCreateMemberIcon`,
 * which bakes the bitmap, and the occlusion test that decides whether an avatar is
 * covering a place's pin. The test works in screen pixels against a bitmap whose
 * disc is offset from its anchor point, and re-deriving that offset independently
 * is precisely how one layout becomes two that drift.
 *
 * Only heights live here. Widths depend on measured text and stay in the bitmap
 * builder; the vertical layout is fully determined by density, so it can be
 * expressed — and tested — without a Canvas.
 *
 * The marker, top to bottom:
 *
 *     ┌──────────────┐
 *     │  (top pad)   │  SOS markers only — cancels the rise below
 *     │    ( AS )    │  the 52dp disc
 *     │    [SOS]     │  SOS band, when the member has an active alert
 *     │   2m ago     │  age band, always
 *     │   at Home    │  place band, when inside a saved place
 *     └──────────────┘
 */
internal object MemberMarkerGeometry {

    const val DISC_DP = 52
    private const val AGE_TEXT_SP = 11f
    private const val AGE_PAD_TOP_DP = 4f
    private const val SOS_TEXT_SP = 12f
    private const val SOS_PAD_TOP_DP = 3f

    /**
     * How close a member's disc has to come to a place's pin, centre to centre on
     * screen, before the pin counts as covered.
     *
     * Both are [DISC_DP] circles, so they touch at 52dp and are roughly
     * half-covered at 26dp. 40dp is where enough of the pin is behind the avatar
     * that aiming at it stops being realistic — and, being in dp, it scales with
     * ZOOM rather than with the place's radius, which runs to 2 km and has nothing
     * to do with what actually overlaps on screen.
     */
    const val OCCLUSION_DP = 40f

    /** The coloured disc's diameter in px. */
    fun discSizePx(density: Float): Int =
        (DISC_DP * density).toInt().coerceAtLeast(DISC_DP)

    /** Height of one baked text band — the age line, and "at &lt;Place&gt;". */
    fun ageBandPx(density: Float): Int =
        (AGE_TEXT_SP * density * 1.4f).toInt()

    /** Gap above the SOS pill. */
    fun sosPadTopPx(density: Float): Float = SOS_PAD_TOP_DP * density

    /** Text size of the SOS pill's label. */
    fun sosTextSizePx(density: Float): Float = SOS_TEXT_SP * density

    /** Gap above the age line. */
    fun agePadTopPx(density: Float): Float = AGE_PAD_TOP_DP * density

    /** Text size of the age / place lines. */
    fun ageTextSizePx(density: Float): Float = AGE_TEXT_SP * density

    /** The "SOS" pill band including its gap; 0 when the member has no alert. */
    fun sosBandPx(density: Float, hasSos: Boolean): Float =
        if (hasSos) sosPadTopPx(density) + sosTextSizePx(density) * 1.6f else 0f

    /** Everything drawn BELOW the disc: SOS pill, age line, "at &lt;Place&gt;". */
    fun belowDiscPx(density: Float, atPlace: Boolean, hasSos: Boolean): Int =
        sosBandPx(density, hasSos).toInt() +
            agePadTopPx(density).toInt() +
            ageBandPx(density) +
            (if (atPlace) ageBandPx(density) else 0)

    /**
     * How far the disc's centre sits ABOVE the marker's anchor point, in px.
     *
     * The icon is anchored by the bitmap's CENTRE and the label stack hangs off
     * the bottom, so the disc floats above the member's actual coordinate by half
     * that stack. SOS markers are top-padded to cancel exactly this (so their
     * pulse rings the avatar rather than the labels), hence 0 for them.
     */
    fun discRiseAboveAnchorPx(density: Float, atPlace: Boolean, hasSos: Boolean): Float =
        if (hasSos) 0f else belowDiscPx(density, atPlace, hasSos) / 2f
}
