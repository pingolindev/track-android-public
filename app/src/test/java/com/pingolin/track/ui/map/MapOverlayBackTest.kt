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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BACK on the map closes the open overlay (the History card or the Layers
 * popover) instead of leaving the app.
 *
 * These pin the precedence the fragment's OnBackPressedCallback follows, and
 * the one case where BACK must NOT be intercepted — otherwise a
 * permanently-enabled callback would swallow the gesture on a bare map and
 * the user could never leave the tab with BACK.
 */
class MapOverlayBackTest {

    @Test
    fun `nothing open means BACK passes through`() {
        assertNull(MapOverlayBack.dismissTarget(historyOpen = false, layersOpen = false))
        assertFalse(MapOverlayBack.shouldIntercept(historyOpen = false, layersOpen = false))
    }

    @Test
    fun `history open means BACK closes history`() {
        assertEquals(MapOverlayBack.Target.HISTORY, MapOverlayBack.dismissTarget(historyOpen = true, layersOpen = false))
        assertTrue(MapOverlayBack.shouldIntercept(historyOpen = true, layersOpen = false))
    }

    @Test
    fun `layers open means BACK closes layers`() {
        assertEquals(MapOverlayBack.Target.LAYERS, MapOverlayBack.dismissTarget(historyOpen = false, layersOpen = true))
    }

    /** Layers draws above History, so it goes first; the next BACK gets History. */
    @Test
    fun `both open means layers first then history`() {
        assertEquals(MapOverlayBack.Target.LAYERS, MapOverlayBack.dismissTarget(historyOpen = true, layersOpen = true))
        assertEquals(MapOverlayBack.Target.HISTORY, MapOverlayBack.dismissTarget(historyOpen = true, layersOpen = false))
    }
}
