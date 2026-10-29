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

import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The map's check-in menu opens the check-in screen, whose buttons send your location with the
 * check-in: otherwise it opens only from someone else's check-in in the bell, and with nobody else
 * checking in, it can't be reached at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class CheckInScreenFromTheMapTest {

    @Test
    fun `the map's check-in menu opens the check-in screen`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val popup = map.substring(map.indexOf("private fun showCheckInPopup("), map.indexOf("private fun showCustomCheckInMessage("))
        // Only while a check-in there could carry the position the entry promises.
        assertTrue(popup.contains("val withPosition = runCatching { checkInRepository.sendsPositionNow(circleId) }.getOrDefault(false)"))
        assertTrue(popup.contains("if (withPosition) popup.menu.add(0, screenId, choices.size + 1, getString(R.string.check_in_screen_entry))"))
        // On the menu's own circle, never the first one.
        assertTrue(popup.contains(
            "item.itemId == screenId -> {\n" +
                "                    findNavController().navigate(\n" +
                "                        R.id.checkInFragment,\n" +
                "                        androidx.core.os.bundleOf(com.pingolin.track.ui.checkin.CheckInViewModel.ARG_CIRCLE_ID to circleId),\n"
        ))
    }

    @Test
    fun `the words`() {
        assertEquals(
            "With my location…",
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.check_in_screen_entry),
        )
    }
}
