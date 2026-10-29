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

package com.pingolin.track.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Someone who shares with a circle and has only "while using the app" is asked again at
 * each opening, once.
 */
class AllTheTimeAskTest {

    private fun due(
        sdkInt: Int = 34,
        preciseGranted: Boolean = true,
        backgroundGranted: Boolean = false,
        sharesWithACircle: Boolean = true,
        askedThisOpening: Boolean = false,
    ) = AllTheTimeAsk.due(sdkInt, preciseGranted, backgroundGranted, sharesWithACircle, askedThisOpening)

    @Test
    fun `asked at an opening when only while-using is granted and they share with a circle`() {
        assertTrue(due())
    }

    @Test
    fun `not asked twice in one opening`() {
        assertFalse(due(askedThisOpening = true))
    }

    @Test
    fun `not asked once it is granted, before Android 10, without precise location, or without a circle`() {
        assertFalse(due(backgroundGranted = true))
        assertFalse(due(sdkInt = 28))
        assertFalse("the first-run chain asks for location itself", due(preciseGranted = false))
        assertFalse(due(sharesWithACircle = false))
    }

    @Test
    fun `a quick return is the same opening, a longer absence a new one`() {
        assertFalse("a file picker or the settings page the question opened", AllTheTimeAsk.isNewOpening(10_000L))
        assertTrue(AllTheTimeAsk.isNewOpening(AllTheTimeAsk.SAME_OPENING_MS + 1))
    }
}
