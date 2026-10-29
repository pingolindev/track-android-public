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

import android.location.Location
import com.pingolin.track.service.LocationTrackingService.Companion.freshFixIsNews
import com.pingolin.track.service.LocationTrackingService.Companion.livenessPayload
import com.pingolin.track.service.LocationTrackingService.Companion.oneShotIsNews
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fix taken and sent outside the tracking service gets the service's bookkeeping and
 * place-crossing check, and no second send. Otherwise a send-now ARRIVAL is never evaluated: if the
 * service's own poll delivered two minutes before and slept on, the arrival would come only with the
 * service's next fix, minutes late.
 *
 * The service can't run under the JVM (its native libraries), so its wiring is pinned here from its
 * source, beside the pure rules. That a later fix at the same place reports nothing again is the
 * native checker's own state, which every fix moves (the mobile SDK's `check_location`).
 */
class OneShotFixTest {

    private fun fix(at: Long) = mockk<Location> { every { elapsedRealtimeNanos } returns at }

    @Test
    fun `a fix taken outside the service is news to it only when newer than the last one it took in`() {
        assertTrue(oneShotIsNews(known = null, fix = fix(1)))
        assertTrue(oneShotIsNews(known = fix(1), fix = fix(2)))
        assertFalse("the same fix the service already took in itself", oneShotIsNews(known = fix(2), fix = fix(2)))
        assertFalse(oneShotIsNews(known = fix(3), fix = fix(2)))
    }

    @Test
    fun `one of the service's own is news unless it is older than one already taken in`() {
        assertTrue(freshFixIsNews(known = null, fix = fix(1)))
        assertTrue("the same moment: the fused provider can hand one fix to both", freshFixIsNews(known = fix(2), fix = fix(2)))
        assertTrue(freshFixIsNews(known = fix(1), fix = fix(2)))
        assertFalse(freshFixIsNews(known = fix(3), fix = fix(2)))
    }

    // ===== the service's own code =====

    private val source = File("src/main/java/com/pingolin/track/service/LocationTrackingService.kt").readText()

    /** A member function of the service, from its signature to its closing brace. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is in the service", start >= 0)
        return source.substring(start).substringBefore("\n    }\n")
    }

    private val handle get() = body("private fun handleLocationUpdate(")

    @Test
    fun `each fix kept outside the service is taken in, once per service, only while tracking and only when news`() {
        assertEquals(1, Regex("""newestOwnFix\.kept\.collect \{ fix -> takeOneShotFix\(fix\) \}""").findAll(source).count())
        assertTrue(body("override fun onCreate(").contains("newestOwnFix.kept.collect { fix -> takeOneShotFix(fix) }"))
        val take = body("private fun takeOneShotFix(")
        assertTrue(take.contains("if (!isTracking || !oneShotIsNews(lastKnownFix, fix)) return"))
        assertTrue(take.trimEnd().endsWith("handleLocationUpdate(fix, alreadySent = true)"))
    }

    @Test
    fun `it gets the bookkeeping and the place check of any fix, then stops - nothing is sent again`() {
        val noSend = handle.indexOf("if (alreadySent) return")
        assertTrue(noSend > 0)
        // The bookkeeping every fresh fix gets, and the crossing check, whose report goes out
        // as usual.
        val before = handle.substring(0, noSend)
        for (step in listOf("lastKnownFix = location", "maybeInferStill(location)", "stampFixHealth()", "checkOwnLocationAgainstBoundaries(")) {
            assertTrue(step, before.contains(step))
        }
        // Every send and store of the function comes after it.
        for (call in listOf(
            "locationRepository.sendEnvelopeLocation(", "locationRepository.sendEnvelopeLivenessRefresh(",
            "locationRepository.sendSelfTrackingLocation(", "locationRepository.storeLocalLocation(",
        )) {
            assertFalse(call, before.contains(call))
            assertTrue(call, handle.indexOf(call) > noSend)
        }
    }

    @Test
    fun `a fix older than one already taken in is skipped before it can move any place's state`() {
        val guard = handle.indexOf("if (!isLivenessCheckIn && !freshFixIsNews(lastKnownFix, location))")
        assertTrue(guard > 0)
        assertTrue(guard < handle.indexOf("lastKnownFix = location"))
        assertTrue(guard < handle.indexOf("checkOwnLocationAgainstBoundaries("))
    }

    /**
     * An armed trip start ("Trip start — bypassing distance/time filter") would otherwise send the
     * check-in's CACHED fix of the earlier place as history. A fix taken outside the service never
     * uses the trip start up, and the check-in hands over only the newest fix.
     */
    @Test
    fun `a pending trip start sends the newest fix, never an older cached one`() {
        assertTrue(handle.indexOf("if (alreadySent) return") < handle.indexOf("val tripStartSend = forceSendNextFix"))
        val liveness = body("private fun maybeSendLivenessCheckIn(")
        assertTrue(liveness.indexOf("adoptNewerOwnFix()") < liveness.indexOf("val cached = lastKnownFix ?: return"))
        assertEquals(1, Regex("""handleLocationUpdate\(""").findAll(liveness).count())
        assertTrue(liveness.contains("handleLocationUpdate(cached, isLivenessCheckIn = true)"))

        val oldPlace = fix(at = 1_000)
        val sendNow = fix(at = 2_000)
        assertSame(sendNow, livenessPayload(own = oldPlace, held = sendNow))
    }
}
