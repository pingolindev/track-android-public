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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source-scan tripwire, in the style of SelfPositionPublicationTest: every path that
 * seals something new for a circle asks [CurrentKeyGate] first.
 *
 * The envelope send cannot run in a JVM test (it starts the SDK's native stream), and it
 * is the path every background location takes, so its gate is pinned here; the others
 * also have behavioural tests (LocationRepositoryTest, KeyDeliveryTest,
 * CheckInCurrentKeyTest, BoundaryCrossingEmitTest).
 */
class CurrentKeyGateWiringTest {

    private fun source(name: String) =
        java.io.File("src/main/java/com/pingolin/track/domain/repository/$name.kt").readText()

    /** From [signature] up to the next top-level member, near enough. */
    private fun bodyOf(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("$signature not found — update this test with the new name", start >= 0)
        val next = Regex("""\n    (?:suspend |private |internal |override )*fun """).find(src, start + signature.length)
        return src.substring(start, next?.range?.first ?: src.length)
    }

    @Test
    fun `the envelope send filters its targets through the gate before anything is sealed`() {
        val body = bodyOf(source("LocationRepository"), "private suspend fun sendEnvelope(")
        val filter = body.indexOf("val sealable = targetIds.filter { currentKeyGate.mayStreamLocationTo(it) }")
        val sessionKeys = body.indexOf("for (targetId in sealable)")
        val sdkTargets = body.indexOf("val sdkTargetIds = sealable.map")
        val sealing = body.indexOf("for (targetCircleId in sdkTargetIds)")
        assertTrue("sendEnvelope must filter its targets through currentKeyGate", filter >= 0)
        assertTrue("session keys must be prepared for the filtered targets only", sessionKeys > filter)
        assertTrue("the sealing loop must run over the filtered targets only", sealing > sdkTargets && sdkTargets > filter)
        assertFalse("nothing may be sealed from the unfiltered list", body.contains("in targetIds)"))
    }

    @Test
    fun `every other path that seals for a circle asks the gate`() {
        val gated = mapOf(
            "LocationRepository" to listOf("suspend fun sendLocation(" to "currentKeyGate.mayStreamLocationTo(circleId)"),
            "BoundaryRepository" to listOf(
                "suspend fun reportCrossingFromMonitor(" to "circleRepository.holdsCurrentKey(circleId)",
                "suspend fun reportCrossing(" to "circleRepository.requireCurrentKey(circleId)",
            ),
            "CheckInRepository" to listOf(
                "suspend fun createCheckIn(" to "circleRepository.requireCurrentKey(circleId)",
                "suspend fun syncPendingCheckIns(" to "circleRepository.holdsCurrentKey(checkIn.circleId)",
            ),
            "DestinationRepository" to listOf(
                "suspend fun createDestination(" to "circleRepository.requireCurrentKey(circleId)",
                "suspend fun updateDestination(" to "circleRepository.requireCurrentKey(circleId)",
                "suspend fun reportArrival(" to "circleRepository.requireCurrentKey(circleId)",
            ),
            "PlaceRepository" to listOf(
                "suspend fun createPlace(" to "requireCurrentKey(circleId)",
                "suspend fun updatePlace(" to "requireCurrentKey(existingPlace.circleId)",
                "suspend fun createPlaceAlert(" to "requireCurrentKey(keyId)",
                "suspend fun createCircleWidePlaceAlert(" to "requireCurrentKey(keyId)",
                "suspend fun copyPlaceToCircle(" to "requireCurrentKey(targetCircleId)",
                "suspend fun copyAllPlacesToCircle(" to "requireCurrentKey(targetCircleId)",
                "suspend fun sharePlaceWithCircle(" to "requireCurrentKey(circleId)",
                "private suspend fun doMigrateLegacyPlaceToEnvelope(" to "holdsCurrentKey(sourceKeyId)",
            ),
            "AlertRuleRepository" to listOf(
                "suspend fun createAlertRule(" to "currentKeyGate.requireCurrentKey(encryptionCircleId)",
                "suspend fun updateAlertRule(" to "currentKeyGate.requireCurrentKey(circleId)",
            ),
            "InactivityAlertRepository" to listOf(
                "suspend fun createInactivityAlert(" to "currentKeyGate.requireCurrentKey(circleId)",
            ),
            "CircleRepository" to listOf(
                // A rotation starts from the current version: a re-key this device owes holds its
                // seals back, never the rotation that ends it.
                "private suspend fun rotateCircleKey(" to "currentKeyGate.holdsCurrentVersion(circleId)",
                "suspend fun ejectMember(" to "currentKeyGate.holdsCurrentVersion(circleId)",
                // inviteToCircle and "It's them" (confirmInviteeAndInvite) both invite through it.
                "private suspend fun invite(" to "currentKeyGate.holdsCurrentKey(circleId)",
                "suspend fun inviteToCircle(" to "= invite(circleId, email,",
                "suspend fun confirmInviteeAndInvite(" to "invite(change.circleId, change.email,",
                // This account's sealed name: create, join, a key that moved on and the roster-load
                // backfill all seal it through sealOwnName.
                "private suspend fun sealOwnName(" to "currentKeyGate.holdsCurrentKey(circleId)",
                "suspend fun uploadEncryptedDisplayNames(" to "sealOwnName(circle.id, again = true)",
            ),
        )
        for ((file, checks) in gated) {
            val src = source(file)
            for ((signature, call) in checks) {
                assertTrue("$file: $signature must call $call", bodyOf(src, signature).contains(call))
            }
        }
    }

    @Test
    fun `an SOS is the documented exception`() {
        val body = bodyOf(source("SosRepository"), "suspend fun triggerSos(")
        assertFalse(
            "triggerSos must not be held back by the gate",
            body.contains("holdsCurrentKey(") || body.contains("requireCurrentKey("),
        )
        assertTrue("the exception must stay documented where it is made", body.contains("Deliberately NOT held back"))
    }
}
