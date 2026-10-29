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

package com.pingolin.track.ui.dialogs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Smart (battery and inactivity) alerts and browser push are free for everyone, on the server's
 * free plan too, so nothing here sells them as Premium.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NothingFreeSoldAsPremiumTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the overview and the paid plan's summary name only what Premium really changes`() {
        assertEquals(
            "Premium removes the free limits on places, circles, alerts, and destinations, and keeps 365 days of history.",
            context.getString(R.string.premium_overview_body),
        )
        assertEquals("Unlimited places, circles, alerts & destinations", context.getString(R.string.plan_paid_summary))
    }

    @Test
    fun `no copy sells smart alerts or browser push`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        for (claim in listOf("smart alerts", "Smart alerts", "Battery alerts for your circle", "Browser push", "Web push")) {
            assertFalse(claim, strings.contains(claim))
        }
        // Neither has a sheet of its own, and a refused alert rule never asks for a subscription.
        val sheet = File("src/main/java/com/pingolin/track/ui/dialogs/TrackPremiumBottomSheet.kt").readText()
        assertFalse(sheet.contains("key.contains(\"smart_alert\")"))
        assertFalse(sheet.contains("key.contains(\"web_push\")"))
        val rules = File("src/main/java/com/pingolin/track/domain/repository/AlertRuleRepository.kt").readText()
        assertFalse(rules.contains("need a subscription"))
    }
}
