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

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The battery level goes to a circle only while "Allow alerts on me" is on too, since its one reader is
 * the low-battery alert. So while that is off the battery switch is greyed out, and says why: "Sends
 * your battery level…" would be false then.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BatteryNeedsAlertsOnMeTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `usable, with what it does, only while alerts on me are allowed`() {
        assertEquals(true to R.string.privacy_low_battery_alerts_body, batteryRow(allowAlertsOnMe = true))
        assertEquals(false to R.string.privacy_low_battery_needs_alerts, batteryRow(allowAlertsOnMe = false))
        assertEquals("Turn on “Allow alerts on me” to use this", context.getString(R.string.privacy_low_battery_needs_alerts))
    }

    @Test
    fun `the row's line is the one the dialog changes`() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).get()
        activity.setTheme(R.style.Theme_Track)
        val dialog = LayoutInflater.from(activity).inflate(R.layout.dialog_circle_privacy, null)
        val line = dialog.findViewById<TextView>(R.id.tvShareBatteryBody)
        assertEquals("Sends your battery level to this circle so members can be alerted when it's low", line.text.toString())
        assertTrue("in the battery switch's row", (dialog.findViewById<android.view.View>(R.id.switchShareBattery).parent as android.view.ViewGroup).findViewById<TextView>(R.id.tvShareBatteryBody) === line)
    }

    @Test
    fun `the dialog shows it as the settings load, and at once as alerts on me are switched`() {
        val screen = File("src/main/java/com/pingolin/track/ui/circles/CirclesFragment.kt").readText()
        assertTrue(
            screen.contains(
                "            val (usable, line) = batteryRow(allowAlertsOnMe)\n" +
                    "            dialogBinding.switchShareBattery.isEnabled = usable\n" +
                    "            dialogBinding.tvShareBatteryBody.setText(line)"
            )
        )
        assertTrue(screen.contains("dialogBinding.switchAllowAlertsOnMe.isChecked = settings.allowAlertsOnMe\n                    showBatteryRow(settings.allowAlertsOnMe)"))
        assertTrue(
            screen.contains(
                "dialogBinding.switchAllowAlertsOnMe.setOnCheckedChangeListener { _, isChecked ->\n            showBatteryRow(isChecked)"
            )
        )
    }
}
