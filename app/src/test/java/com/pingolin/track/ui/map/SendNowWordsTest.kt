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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What "Send location now" says. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SendNowWordsTest {

    private fun words(id: Int, vararg args: Any) =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id, *args)

    @Test
    fun `each state in its words`() {
        assertEquals("Location sent to Family", words(R.string.send_now_sent, "Family"))
        assertEquals("Waiting to send to Family", words(R.string.send_now_waiting, "Family"))
        assertEquals(
            "Waiting to send to Family: this phone is still catching up with the circle.",
            words(R.string.send_now_waiting_for_circle, "Family"),
        )
        assertEquals("Not sent: location sharing is paused in Family", words(R.string.send_now_not_sent_paused, "Family"))
        assertEquals("Not sent: location sharing is off in Family", words(R.string.send_now_not_sent_off, "Family"))
        assertEquals("Saved to your history", words(R.string.send_now_saved_to_history))
        assertEquals("Waiting to save to your history", words(R.string.send_now_waiting_history))
    }

    @Test
    fun `each state about a circle with no readable name, in a whole line of its own`() {
        assertEquals("Location sent to this circle", words(R.string.send_now_sent_unnamed))
        assertEquals("Waiting to send to this circle", words(R.string.send_now_waiting_unnamed))
        assertEquals(
            "Waiting to send to this circle: this phone is still catching up with it.",
            words(R.string.send_now_waiting_for_circle_unnamed),
        )
        assertEquals("Not sent: location sharing is paused in this circle", words(R.string.send_now_not_sent_paused_unnamed))
        assertEquals("Not sent: location sharing is off in this circle", words(R.string.send_now_not_sent_off_unnamed))
    }
}
