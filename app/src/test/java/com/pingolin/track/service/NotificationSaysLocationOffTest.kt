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

package com.pingolin.track.service

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
 * With Location switched off on the phone, the ongoing notification says Location is off, not
 * "Location sharing active • Last shared: 19m ago" while the circle sees them freeze; and it
 * changes the moment Location is switched, either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NotificationSaysLocationOffTest {

    private val service = File("src/main/java/com/pingolin/track/service/LocationTrackingService.kt").readText()

    @Test
    fun `the notification says Location is off before anything about sharing`() {
        val text = service.substring(service.indexOf("private fun buildNotificationText(")).let { it.substring(0, it.indexOf("\n    }\n")) }
        val off = text.indexOf("if (!com.pingolin.track.util.LocationAccess.servicesOn(this)) {")
        assertTrue(off > text.indexOf("notSharingText()?.let { return it }"))
        assertTrue(off in 0 until text.indexOf("else \"Location sharing active\""))
        assertTrue(text.contains("return getString(R.string.notification_location_services_off)"))
    }

    @Test
    fun `switching Location brings the notification up to date at once`() {
        val receiver = service.substring(service.indexOf("private val locationModeReceiver = ")).let { it.substring(0, it.indexOf("\n    }\n")) }
        assertTrue(receiver.contains("lastNotificationUpdateTime = 0L\n            updateNotification(null)"))
    }

    @Test
    fun `the words`() {
        assertEquals(
            "Location is off on this phone, so your circles can't see where you are",
            ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.notification_location_services_off),
        )
    }
}
