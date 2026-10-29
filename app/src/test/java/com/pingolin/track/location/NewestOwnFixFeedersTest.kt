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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every path that sends this account's own position feeds [NewestOwnFix], or a stale liveness
 * re-send gets through the one left out. The sends meet in LocationRepository, which records each
 * live fix; the paths outside the tracking service are pinned to it here, and a new place that
 * takes a fix has to say what it does with it.
 */
class NewestOwnFixFeedersTest {

    private fun source(path: String) = File(path).readText()

    private fun main(path: String) = source("src/main/java/com/pingolin/track/$path")

    /** A class member, from its signature to its closing brace. */
    private fun member(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is there", start >= 0)
        return source.substring(start).substringBefore("\n    }\n")
    }

    @Test
    fun `every live send in LocationRepository records the fix it carries, a fix taken to send too, and sign-out drops it`() {
        val repo = main("domain/repository/LocationRepository.kt")
        // Kept when the guards take it, and noted as sent whatever they say.
        val both = "newestOwnFix.offer(location)\n        newestOwnFix.noteSent(location)"
        assertTrue(member(repo, "suspend fun sendLocation(").contains(both))
        assertTrue(member(repo, "suspend fun sendSelfTrackingLocation(").contains(both))
        // Not a check-in re-sending a fix, nor a kept or late point: those are not where the phone is.
        assertTrue(member(repo, "private suspend fun sendEnvelope(").contains(
            "if (!livenessOnly && !isHistoryReplay && !historyOnly) {\n" +
                "            newestOwnFix.offer(location)\n            newestOwnFix.noteSent(location)\n        }"
        ))
        assertTrue(member(repo, "suspend fun getCurrentLocation()").contains("?.also { newestOwnFix.offer(it) }"))
        assertTrue(member(repo, "fun wipeInMemory()").contains("newestOwnFix.clear()"))
    }

    @Test
    fun `the paths outside the service send through those`() {
        // Send-now and the silent map-open refresh.
        val sendNow = member(main("ui/map/MapViewModel.kt"), "fun sendLocationNow(")
        assertTrue(sendNow.contains("locationRepository.sendLocation(") && sendNow.contains("locationRepository.sendSelfTrackingLocation("))
        assertTrue(main("ui/map/MapFragment.kt").contains("viewModel.sendLocationNow(location, target, silent = true)"))
        // member_joined's fresh send.
        assertTrue(main("util/AlertHandler.kt").contains("locationRepository.sendEnvelopeLocation(targets, location, null)"))
        // A switch to approximate: one fresh fix, through the same send.
        assertTrue(main("worker/PrivacyCheckWorker.kt").contains("locationRepository.sendEnvelopeLocation(switched.toList(), fix, null)"))
        // The heartbeat worker: the newer of its fix and the held one, for a check-in as for history.
        val worker = main("worker/LocationHeartbeatWorker.kt")
        assertTrue(worker.contains("val finalLocation = newestOwnFix.newerOf(fetched)"))
        val skip = worker.indexOf("if (decision == LocationSendDecision.LIVENESS && newestOwnFix.goesBack(finalLocation)) {")
        assertTrue("its check-in never goes back on a position already sent",
            skip in 0 until worker.indexOf("locationRepository.sendEnvelopeLivenessRefresh(targets, finalLocation, batteryLevel)"))
        // A check-in, a destination and a needs-attention alert take their fix through getCurrentLocation.
        for (path in listOf("ui/checkin/CheckInViewModel.kt", "ui/destinations/DestinationsViewModel.kt", "domain/sos/SosDispatcher.kt")) {
            val file = main(path)
            assertTrue(path, file.contains("locationRepository.getCurrentLocation()"))
            assertFalse(path, file.contains("locationProvider."))
        }
    }

    @Test
    fun `the wake handlers send nothing themselves - they start the service, whose fixes are offered`() {
        for (path in listOf(
            "src/gms/java/com/pingolin/track/service/TrackFirebaseMessagingService.kt",
            "src/main/java/com/pingolin/track/service/TrackUnifiedPushReceiver.kt",
        )) {
            val wake = member(source(path), "private fun handleWakeLocation(")
            assertTrue(path, wake.contains("LocationTrackingService"))
            assertFalse(path, wake.contains("locationRepository.") || wake.contains("locationProvider."))
        }
    }

    @Test
    fun `a new place that takes a fix has to say what it does with it`() {
        val takers = listOf("src/main/java", "src/gms/java", "src/libre/java")
            .flatMap { File(it).walk().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .filter { f -> f.readText().let { it.contains("locationProvider.currentLocation(") || it.contains("locationProvider.lastLocation()") } }
            .map { it.name }
            .toSortedSet()
        assertEquals(
            sortedSetOf(
                "LocationRepository.kt", // getCurrentLocation: offered
                "LocationTrackingService.kt", // its fixes: offered after the inbound guards
                "LocationHeartbeatWorker.kt", // newerOf; offered when sent
                "AlertHandler.kt", // member_joined: sendEnvelopeLocation, offered
                "MapFragment.kt", // centring sends nothing; send-now and the open refresh go through sendLocationNow
                "PlacesFragment.kt", // the list's distances; nothing sent
                "PrivacyCheckWorker.kt", // a switch to approximate: sendEnvelopeLocation, offered
                "SosPositionChooser.kt", // an SOS's last known position: sealed into the alert only, never sent live
            ),
            takers,
        )
    }
}
