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

package com.pingolin.track.ui.alerts

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.data.local.entity.InactivityAlertEntity
import com.pingolin.track.util.CheckInCountdown
import com.pingolin.track.util.InactivityWatches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The countdown line under a check-in rule about yourself in Alerts, and near the check-in buttons:
 * shown only with a deadline, redrawn by a tick without rebinding the row, read by screen readers
 * with the rest of what's on screen, and never announced as it changes (no live region).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CheckInCountdownRowTest {

    private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Application>(), R.style.Theme_Track)
    private val hour = 3_600_000L

    private fun rule(self: Boolean = true) = InactivityAlertEntity(
        id = "r-1", userId = "me", circleId = "c-1", targetUserId = "me", timeoutHours = 4, isSelfAlert = self,
        dayBucket = 0, watches = InactivityWatches.CHECK_IN, armedAt = 0L,
    )

    private fun countdownLine(item: UnifiedAlert): TextView {
        val holder = UnifiedAlertAdapter({}, { _, _ -> }, {}).onCreateViewHolder(FrameLayout(context), 0)
        holder.bind(item)
        return holder.itemView.findViewById(R.id.tvCountdown)
    }

    @Test
    fun `a rule about yourself with a deadline shows its line, and no other row does`() {
        val deadline = System.currentTimeMillis() + 3 * hour + 12 * 60_000L + 30_000L
        val line = countdownLine(UnifiedAlert.Inactivity(rule(), "Family", CheckInCountdown.Countdown.Once(deadline)))
        assertEquals(View.VISIBLE, line.visibility)
        assertEquals("Your circle is alerted in 3 h 12 min", line.text.toString())

        assertEquals(View.GONE, countdownLine(UnifiedAlert.Inactivity(rule(), "Family", null)).visibility)
        assertEquals(View.GONE, countdownLine(UnifiedAlert.Inactivity(rule(self = false), "Family")).visibility)
    }

    @Test
    fun `a tick redraws the lines and says when to come back, and a list without one has no tick`() {
        // A first list is taken at once (no diff on another thread).
        fun adapterOver(vararg items: UnifiedAlert) = UnifiedAlertAdapter({}, { _, _ -> }, {}).apply { submitList(items.toList()) }
        val now = System.currentTimeMillis()
        val deadline = now + hour + 30_000L
        val other = now + 2 * hour + 5_000L
        assertEquals(
            "the soonest change of any line",
            minOf(CheckInCountdown.nextChangeIn(deadline, now), CheckInCountdown.nextChangeIn(other, now)),
            adapterOver(
                UnifiedAlert.Inactivity(rule(), "Family", CheckInCountdown.Countdown.Once(deadline)),
                UnifiedAlert.Inactivity(rule().copy(id = "r-2"), "Work", CheckInCountdown.Countdown.Once(other)),
            )
                .tickCountdowns(now),
        )
        assertNull(adapterOver(UnifiedAlert.Inactivity(rule(self = false), "Family")).tickCountdowns(now))
    }

    @Test
    fun `both lines are read by screen readers, and neither is a live region`() {
        val row = LayoutInflater.from(context).inflate(R.layout.item_alert_rule, null).findViewById<TextView>(R.id.tvCountdown)
        val screen = LayoutInflater.from(context).inflate(R.layout.fragment_check_in, null).findViewById<TextView>(R.id.tvSelfCountdown)
        for (line in listOf(row, screen)) {
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, line.accessibilityLiveRegion)
            assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, line.importantForAccessibility)
            assertEquals("hidden until there is a countdown", View.GONE, line.visibility)
        }
    }

    @Test
    fun `the check-in screen reads your check-ins each time it opens, and redraws while it shows`() {
        val fragment = File("src/main/java/com/pingolin/track/ui/checkin/CheckInFragment.kt").readText()
        val started = fragment.substringAfter("repeatOnLifecycle(Lifecycle.State.STARTED) {").substringBefore("\n            }\n        }\n")
        assertTrue(started.contains("viewModel.readCountdown()"))
        assertTrue(started.contains("CheckInCountdown.nextChangeIn("))
        val alerts = File("src/main/java/com/pingolin/track/ui/alerts/AlertsFragment.kt").readText()
        val shown = alerts.substringAfter("repeatOnLifecycle(Lifecycle.State.STARTED) {").substringBefore("\n            }\n        }\n")
        assertTrue(shown.contains("inactivityVm.readSelfCheckIns()"))
        assertTrue(shown.contains("adapter.tickCountdowns()"))
    }
}
