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

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams

/**
 * The tops of the map's top-left column, in px: the circle selector at [baseTop], then each view under
 * it [gapPx] below the bottom of the last one shown above it, never above its own floor in [floors] (so
 * nothing jumps while the selector waits for its first circle). [heights] is the column in order, the
 * selector first, null for a view that is gone or not laid out yet: it takes no room.
 */
internal fun topLeftStackTops(baseTop: Int, heights: List<Int?>, floors: List<Int>, gapPx: Int): List<Int> {
    var bottom: Int? = null
    return heights.mapIndexed { index, height ->
        val floor = floors[index]
        val top = if (index == 0) baseTop else maxOf(floor, bottom?.let { it + gapPx } ?: floor)
        if (height != null) bottom = top + height
        top
    }
}

/**
 * The map's top-left column, measured: the circle selector, the guardian line, the sync error and the
 * view controls, each [GAP_DP] under the one above it. At fixed offsets the controls' first button would
 * draw over the guardian line's start and take its taps, the dot menu's labels would cover most of the
 * rest, and a wrapped circle name or a large font would run the selector over its top. Measured, a
 * wrapped name, a large font or a long translation moves what is under it down instead.
 *
 * The two cards that open beside the column follow it. The layers card (top|end) opens under the standing
 * lines, which reach it when they wrap. The refresh pill (top|center_horizontal) is wide enough on a
 * small phone to reach the controls, and it stays under their first two buttons.
 */
internal class TopLeftStack(
    private val selector: View,
    private val guardianLine: View,
    private val syncError: View,
    private val controls: View,
    private val refreshPill: View,
    private val layersCard: View,
    /** The selector's top: the layout's own until the insets come, then the status bar's inset and the card margin. */
    private val baseTopPx: () -> Int,
    /** After each placement: the location line goes under the standing lines. */
    private val onPlaced: () -> Unit,
) {
    private val density = selector.resources.displayMetrics.density

    private fun Int.dp(): Int = (this * density).toInt()

    /** Placed again when one of them changes height: a new circle name, a wrap, the line's own text. */
    fun attach() {
        for (view in listOf(selector, guardianLine, syncError, controls)) {
            view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) place()
            }
        }
    }

    /** Also after any of them shows or goes: a view that goes isn't laid out, so its listener doesn't run. */
    fun place() {
        val base = baseTopPx()
        val gap = GAP_DP.dp()
        val column = listOf(selector, guardianLine, syncError, controls)
        val shown = column.map { view -> view.height.takeIf { view.visibility == View.VISIBLE && it > 0 } }
        // The standing lines run as wide as the map, under the notification bell's column: they start [GAP_DP]
        // under the bell, whose top is the selector's (placeBell, MainActivity.kt), as well as under the selector.
        // The controls, at the start, never reach the bell.
        val slot = base + SELECTOR_SLOT_DP.dp()
        val underBell = base + BELL_DP.dp() + gap
        val tops = topLeftStackTops(base, shown, floors = listOf(base, underBell, underBell, slot), gapPx = gap)
        column.zip(tops).forEach { (view, top) -> view.topMarginTo(top) }

        val linesBottom = (1..2).mapNotNull { index -> shown[index]?.let { tops[index] + it } }.maxOrNull()
        layersCard.topMarginTo(maxOf(base + LAYERS_SLOT_DP.dp(), linesBottom?.let { it + gap } ?: 0))
        // The second button's row, measured: the dot menu's labels make the rows taller at a large font.
        val firstTwo = (controls as? ViewGroup)?.getChildAt(1)?.bottom?.takeIf { it > 0 } ?: FIRST_TWO_BUTTONS_DP.dp()
        refreshPill.topMarginTo(maxOf(base + REFRESH_SLOT_DP.dp(), tops[3] + firstTwo + REFRESH_CLEARANCE_DP.dp()))
        onPlaced()
    }

    private fun View.topMarginTo(top: Int) {
        if ((layoutParams as FrameLayout.LayoutParams).topMargin != top) {
            updateLayoutParams<FrameLayout.LayoutParams> { topMargin = top }
        }
    }

    private companion object {
        const val GAP_DP = 8

        /** The selector card's slot, 56dp from its top: the controls never start higher than
         * this.
         */
        const val SELECTOR_SLOT_DP = 56

        /** The notification bell's container, as MainActivity measures it: the mini FAB and its shadow's room. */
        const val BELL_DP = 56

        /** The layers card's own slot, clear of the notification bell above it. */
        const val LAYERS_SLOT_DP = 84

        /** The refresh pill's own slot, well below the camera cutout. */
        const val REFRESH_SLOT_DP = 148

        /** The controls' first two buttons (40dp, 8dp, 40dp), until the column is laid out. */
        const val FIRST_TWO_BUTTONS_DP = 88

        /** And a few dp more: the clearance the pill's own slot (148dp) has over the first two
         * buttons at the controls' slot (56dp + 88dp).
         */
        const val REFRESH_CLEARANCE_DP = 4
    }
}
