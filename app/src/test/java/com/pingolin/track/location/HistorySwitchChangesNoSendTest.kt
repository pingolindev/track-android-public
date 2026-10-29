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

package com.pingolin.track.location

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * The sharing switches are about visibility, not about what is captured: "Location history" off
 * changes nothing this phone sends. The server keeps the circle's history and hides it from the
 * members until it's back on, so a history-off circle still gets every live point, liveness
 * check-in, late point and crossing it gets with history on. No send path reads the switch; its
 * only readers are the switch screen, the privacy row's mapping and restore.
 */
class HistorySwitchChangesNoSendTest {

    @Test
    fun `no send path reads the history switch`() {
        val main = "src/main/java/com/pingolin/track/"
        for (path in listOf(
            "domain/repository/LocationRepository.kt",
            "service/LocationTrackingService.kt",
            "worker/LocationHeartbeatWorker.kt",
            "worker/PrivacyCheckWorker.kt",
            "domain/repository/BoundaryRepository.kt",
            "domain/repository/CheckInRepository.kt",
            "domain/sos/SosDispatcher.kt",
            "util/AlertHandler.kt",
            "domain/pending/PendingExecutors.kt",
        )) {
            val source = File(main + path).readText()
            assertFalse(path, source.contains("shareHistory") || source.contains("share_history"))
        }
    }
}
