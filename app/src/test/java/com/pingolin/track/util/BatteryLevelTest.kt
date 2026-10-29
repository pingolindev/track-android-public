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

package com.pingolin.track.util

import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The battery level a location sent outside the tracking service carries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BatteryLevelTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the level in percent, as the tracking service reads it, and none when Android doesn't say`() {
        assertEquals(15, BatteryLevel.percent(15, 100))
        assertEquals(50, BatteryLevel.percent(25, 50))
        assertNull(BatteryLevel.percent(-1, 100))
        assertNull(BatteryLevel.percent(15, 0))
    }

    @Test
    fun `read now from Android's battery broadcast`() {
        @Suppress("DEPRECATION")
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 15).putExtra(BatteryManager.EXTRA_SCALE, 100)
        )
        assertEquals(15, BatteryLevel(context).now())
    }
}
