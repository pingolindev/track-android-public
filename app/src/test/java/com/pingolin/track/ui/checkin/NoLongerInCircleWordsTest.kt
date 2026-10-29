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

package com.pingolin.track.ui.checkin

import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A send refused because its circle is gone, and the check-in screen with no circle chosen. One
 * frame for both refusals, "you're no longer in {circle}": true whether they left, were removed or
 * the circle was deleted, without saying which, and right with "this circle" mid-sentence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NoLongerInCircleWordsTest {

    private fun words(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun `each line in its words`() {
        assertEquals("Not sent: you're no longer in Family", words(R.string.send_now_not_sent_gone, "Family"))
        assertEquals("You're no longer in Family. Choose a circle to check in to.", words(R.string.check_in_circle_gone, "Family"))
        assertEquals("Choose a circle", words(R.string.check_in_pick_circle_entry))
        assertEquals("Choose a circle to check in to.", words(R.string.check_in_pick_circle))
        // Without a readable name, lines of their own: no fallback name put into the frames.
        assertEquals("Not sent: you're no longer in that circle", words(R.string.send_now_not_sent_gone_unnamed))
        assertEquals("You're no longer in the circle you chose. Choose a circle to check in to.", words(R.string.check_in_circle_gone_unnamed))
    }

    @Test
    fun `the name is the circle's readable one, else the unnamed line, never the locked label or a fallback in the frame`() {
        val rule = "!com.pingolin.track.domain.repository.LockedCircle.isPlaceholder(it)"
        val screen = File("src/main/java/com/pingolin/track/ui/checkin/CheckInFragment.kt").readText()
        val readable = screen.substring(screen.indexOf("private fun readableName("))
        assertTrue(readable.substring(0, readable.indexOf("\n\n")).contains(rule))
        assertTrue(screen.contains("readableName(gone)?.let { getString(R.string.check_in_circle_gone, it) }"))
        assertTrue(screen.contains("?: getString(R.string.check_in_circle_gone_unnamed)"))
        assertFalse("no fallback name reaches the check-in frame", screen.contains("locked_circle_this_circle"))
        // Said when it appears, while the screen is open.
        val layout = File("src/main/res/layout/fragment_check_in.xml").readText()
        val line = layout.substring(layout.indexOf("android:id=\"@+id/tvCircleChoice\""))
        assertTrue(line.substring(0, line.indexOf("/>")).contains("android:accessibilityLiveRegion=\"polite\""))
        val map = File("src/main/java/com/pingolin/track/ui/map/MapFragment.kt").readText()
        val gone = map.substring(map.indexOf("is SendLocationResult.CircleGone ->")).let { it.substring(0, it.indexOf("is SendLocationResult.RateLimited ->")) }
        // The send-now lines' one rule: CircleGone takes its name through it, and the rule keeps both
        // halves, so neither a blank nor the locked label reaches the frame.
        assertTrue(gone.contains("val name = sendNowReadableName(result.circleName)"))
        val sendNowWords = File("src/main/java/com/pingolin/track/ui/map/SendNowWords.kt").readText()
        assertTrue(sendNowWords.contains("name?.takeIf { it.isNotBlank() && !LockedCircle.isPlaceholder(it) }"))
        assertTrue(gone.contains("name?.let { getString(R.string.send_now_not_sent_gone, it) }"))
        assertTrue(gone.contains("?: getString(R.string.send_now_not_sent_gone_unnamed)"))
        assertFalse("no fallback name reaches the send-now frame", gone.contains("locked_circle_this_circle"))
    }
}
