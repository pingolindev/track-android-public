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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.local.entity.PrivacySettingEntity
import com.pingolin.track.data.local.entity.sendsBatteryLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A circle gets this phone's battery level only while its battery switch AND "Allow alerts on
 * me" are on. The level's one reader is the low-battery alert, and nobody can set one on a
 * person who doesn't allow alerts on them.
 */
class BatteryLevelGateTest {

    private fun row(shareBattery: Boolean, allowAlertsOnMe: Boolean?) =
        PrivacySettingEntity(circleId = "c-1", shareBattery = shareBattery, allowAlertsOnMe = allowAlertsOnMe, updatedAt = 0)

    @Test
    fun `both switches on, or alerts not known yet, and the level goes - either one off and it doesn't`() {
        assertTrue(row(shareBattery = true, allowAlertsOnMe = true).sendsBatteryLevel())
        assertTrue("a row from before it was stored reads as allowed", row(shareBattery = true, allowAlertsOnMe = null).sendsBatteryLevel())
        assertFalse(row(shareBattery = true, allowAlertsOnMe = false).sendsBatteryLevel())
        assertFalse(row(shareBattery = false, allowAlertsOnMe = true).sendsBatteryLevel())
        assertFalse(row(shareBattery = false, allowAlertsOnMe = null).sendsBatteryLevel())
    }

    @Test
    fun `every send path asks the one rule, and none reads the battery switch alone`() {
        val repo = File("src/main/java/com/pingolin/track/domain/repository/LocationRepository.kt").readText()
        // The send-now path, the envelope the heartbeat and the service send, and the offline queue.
        assertTrue(repo.contains("sharesBattery = privacy.sendsBatteryLevel()"))
        assertTrue(repo.contains("targetSharesBattery = privacy.sendsBatteryLevel()"))
        assertTrue(repo.contains("batteryLevel = if (privacy.sendsBatteryLevel()) batteryLevel else null,"))
        assertEquals(3, Regex("""privacy\.sendsBatteryLevel\(\)""").findAll(repo).count())
        assertFalse(repo.contains("privacy.shareBattery"))
    }

    @Test
    fun `the stored row learns Allow alerts on me from every privacy answer`() {
        val circles = File("src/main/java/com/pingolin/track/domain/repository/CircleRepository.kt").readText()
        val map = circles.substring(circles.indexOf("private fun mapPrivacyResponse(")).let { it.substring(0, it.indexOf("\n\n")) }
        assertTrue(map.contains("allowAlertsOnMe = data.allowAlertsOnMe,"))
    }
}
