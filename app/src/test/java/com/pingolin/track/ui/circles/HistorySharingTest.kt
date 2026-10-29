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

package com.pingolin.track.ui.circles

import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.data.local.entity.CircleMemberEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The member list's `history_sharing`, as this phone keeps it, reads it and says it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HistorySharingTest {

    private fun member(history: String?) = CircleMemberEntity(
        circleId = "c", userId = "u", displayName = "Bo", role = "member", joinedAt = 0, historySharing = history,
    )

    @Test
    fun `what a members-list entry's history sharing is kept as`() {
        assertEquals("off", MemberSharing.historyFromWire("off"))
        assertNull(MemberSharing.historyFromWire("on"))
        assertNull("an older server's silence shows it", MemberSharing.historyFromWire(null))
        assertNull("so does a word this build doesn't know", MemberSharing.historyFromWire("later"))
    }

    @Test
    fun `a member list read keeps it on each member`() {
        val circles = File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        assertTrue(circles.contains("historySharing = com.pingolin.track.ui.circles.MemberSharing.historyFromWire(dto.historySharing),"))
    }

    @Test
    fun `whose history is hidden`() {
        assertTrue(MemberSharing.historyOff(member("off")))
        assertFalse(MemberSharing.historyOff(member(null)))
    }

    @Test
    fun `the words, the same on web and iPhone`() {
        assertEquals(
            "Bo doesn't share their location history in this circle.",
            MemberSharing.historyOffSentence(ApplicationProvider.getApplicationContext(), "Bo"),
        )
    }

    @Test
    fun `their card says it in place of View history, and the history view says it in place of a trail`() {
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        assertTrue(map.contains("val historyLine = if (viewModel.historyHidden(circleId, location.userId)) {"))
        assertTrue(map.contains("if (historyLine != null) btnViewHistory.visibility = View.GONE"))
        val footer = map.substring(map.indexOf("private fun renderHistoryFooter()"))
        assertTrue(footer.substring(0, footer.indexOf("val totalPoints")).contains("viewModel.historyHiddenFor(it)"))
    }
}
