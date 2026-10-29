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
 * What the BACK gesture should close on the map, if anything.
 *
 * The map has two transient overlays with their own close buttons — the
 * History time-range card and the Layers filter popover. Without a callback
 * the gesture falls through to the activity and LEAVES THE APP with the panel
 * still open. BACK is the reflex for "close this"; it has to act on the
 * topmost overlay first and only reach the activity when nothing is open.
 *
 * Pure so the precedence is unit-tested: Layers sits above History (it opens
 * over the upper-right region while the History card lives at the bottom), so
 * with both open one BACK closes Layers and the next closes History.
 */
object MapOverlayBack {

    enum class Target { LAYERS, HISTORY }

    /** The overlay BACK should dismiss, or null when the gesture must pass through. */
    fun dismissTarget(historyOpen: Boolean, layersOpen: Boolean): Target? = when {
        layersOpen -> Target.LAYERS
        historyOpen -> Target.HISTORY
        else -> null
    }

    /** Whether the fragment's callback should intercept BACK at all. */
    fun shouldIntercept(historyOpen: Boolean, layersOpen: Boolean): Boolean =
        dismissTarget(historyOpen, layersOpen) != null
}
