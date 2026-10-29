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

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The re-seal's wiring: a re-seal is looked for on every way a new key version reaches this phone,
 * and when a pause it waited out ends; it never runs for an account that may not track; the records
 * go at sign-out (with the circle: LocationRepositoryTest); and every write to them takes one lock.
 */
class LastKnownResealWiringTest {

    private fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()

    @Test
    fun `a new key version here, by import or by this phone's own rotation, looks for a re-seal`() {
        val circles = source("domain/repository/CircleRepository.kt")
        // A key import that moved a circle's version: a member's rotation, or a key delivered or pulled.
        assertTrue(circles.contains(
            "runCatching { sosRepository.get().resealOwn(moved, keysRead = true) }\n" +
                "                        .onFailure { Log.w(TAG, \"Reseal after a key import failed: \${it.javaClass.simpleName}\") }\n" +
                "                    // And this account's frozen last location there, where its real-time sharing is off.\n" +
                "                    scheduleLastKnownReseal(moved)",
        ))
        // This phone's own rotation, landed.
        assertTrue(circles.contains(
            "runCatching { sosRepository.get().resealOwn(setOf(circleId), keysRead = true) }\n" +
                "                .onFailure { Log.w(TAG, \"Reseal after a rotation failed: \${it.javaClass.simpleName}\") }\n" +
                "            // And this account's frozen last location there, where its real-time sharing is off.\n" +
                "            scheduleLastKnownReseal(listOf(circleId))",
        ))
    }

    @Test
    fun `a re-seal is a send, so never for an account that may not track`() {
        val circles = source("domain/repository/CircleRepository.kt")
        assertTrue(circles.contains("accessOpen = { sessionManager.shouldBeTracking() },"))
        assertTrue(circles.contains("privacyOf = { privacySettingDao.get(it) },"))
    }

    @Test
    fun `a pause's end, or any privacy wake, looks again`() {
        assertTrue(source("worker/PrivacyCheckWorker.kt").contains("runCatching { circleRepository.lastKnownReseal.runAll() }"))
    }

    @Test
    fun `the records go at sign-out, with the account's other positions`() {
        val auth = source("domain/repository/AuthRepository.kt")
        assertTrue(auth.contains("runCatching { circleRepository.get().ownLastSent.wipeAll() }"))
    }

    @Test
    fun `every write to the records takes the one lock, so none lands inside an update`() {
        val store = source("location/OwnLastSent.kt")
        for (write in listOf(
            "put(circleId: String, record: OwnLastSent)",
            "update(circleId: String, from: OwnLastSent, to: OwnLastSent): Boolean",
            "remove(circleId: String)",
            "wipeAll()",
        )) {
            assertTrue(write, store.contains("    @Synchronized\n    override fun $write {"))
        }
    }
}
