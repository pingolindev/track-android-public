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

package com.pingolin.track.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * While the location service is dead the heartbeat is all a circle hears, so it carries the battery
 * level — without it a low-battery alert would stay quiet exactly then — and each circle still gets
 * it only where its own battery switch is on.
 */
class HeartbeatBatteryTest {

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    @Test
    fun `both heartbeat sends carry the battery level read at the send`() {
        val worker = source("worker/LocationHeartbeatWorker.kt")
        assertTrue(worker.contains("val batteryLevel = battery.now()"))
        assertTrue(worker.contains("locationRepository.sendEnvelopeLivenessRefresh(targets, finalLocation, batteryLevel)"))
        assertTrue(worker.contains("locationRepository.sendEnvelopeLocation(targets, finalLocation, batteryLevel)"))
        assertFalse("never without it again", Regex("""sendEnvelope\w*\(targets, finalLocation, null\)""").containsMatchIn(worker))
    }

    @Test
    fun `each circle's copy gets the level only where its battery switch is on, and a paused or real-time-off one gets nothing`() {
        val repo = source("domain/repository/LocationRepository.kt")
        val envelope = repo.substring(repo.indexOf("private suspend fun sendEnvelope(")).let { it.substring(0, it.indexOf("\n    }\n")) }
        val skip = envelope.indexOf("if (paused || privacy.shareRealtime == false) {")
        // The battery switch AND "Allow alerts on me" (BatteryLevelGateTest).
        val switch = envelope.indexOf("targetSharesBattery = privacy.sendsBatteryLevel()")
        val sealed = envelope.indexOf("batteryLevel = if (targetSharesBattery) batteryLevel?.toUByte() else null,")
        assertTrue("a paused or real-time-off circle is skipped", skip >= 0)
        assertTrue("its battery switch is read after that", switch > skip)
        assertTrue("and decides what its sealed copy carries", sealed > switch)
        // Sealed under the circle's key with the location, never sent beside it in the clear.
        assertTrue(envelope.contains("cryptoManager.envelopeEncryptLocationV2(targetLocation, listOf(targetCircleId))"))
        assertEquals(1, Regex("""batteryLevel = if \(targetSharesBattery\)""").findAll(envelope).count())
    }
}
