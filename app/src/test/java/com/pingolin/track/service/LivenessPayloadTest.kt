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
import com.pingolin.track.service.LocationTrackingService.Companion.livenessPayload
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A liveness check-in re-sends a cached fix stamped now, so it must see every fix sent, those sent
 * outside the service too (send-now, the map-open refresh, a check-in...): otherwise, after "Send
 * location now" from a new place, the next check-in would put the person back at the earlier place
 * as current, and members would see a departure that never happened. The check-in re-sends the
 * newer of the service's own fix and the newest any path took (NewestOwnFix), through the
 * check-in's usual gates.
 */
class LivenessPayloadTest {

    private fun fix(at: Long) = mockk<Location> { every { elapsedRealtimeNanos } returns at }

    @Test
    fun `send-now newer than the service's fix - the check-in re-sends the newer one`() {
        val serviceFix = fix(at = 1_000)
        val sendNow = fix(at = 2_000)
        assertSame(sendNow, livenessPayload(own = serviceFix, held = sendNow))
    }

    @Test
    fun `an older fix taken elsewhere never replaces the service's newer one, and a tie keeps the service's`() {
        val serviceFix = fix(at = 2_000)
        assertSame(serviceFix, livenessPayload(own = serviceFix, held = fix(at = 1_000)))
        assertSame(serviceFix, livenessPayload(own = serviceFix, held = fix(at = 2_000)))
    }

    @Test
    fun `with only one of the two, that one, and with neither, nothing`() {
        val only = fix(at = 5)
        assertSame(only, livenessPayload(own = null, held = only))
        assertSame(only, livenessPayload(own = only, held = null))
        assertNull(livenessPayload(own = null, held = null))
    }

    /**
     * The scenario: indoors at B, send-now delivers a 150 m fix. It goes out, but the holder doesn't
     * keep it, so the check-in's payload is still the service's older precise fix at A. Stamped now
     * that would put the person back at A: it is skipped. The service's next accepted fix resumes
     * the check-ins.
     */
    @Test
    fun `a 150 m send-now after a precise older fix - nothing older is re-sent, and the next accepted fix resumes`() {
        val session = mockk<com.pingolin.track.util.SessionManager> { every { getUserId() } returns "a11ce000-0000-4000-8000-00000000a11c" }
        val now = 9_000_000_000_000L
        val held = com.pingolin.track.location.NewestOwnFix(session).also { it.elapsedNanos = { now } }
        fun taken(secondsAgo: Long, accuracy: Float) = mockk<Location> {
            every { elapsedRealtimeNanos } returns now - secondsAgo * 1_000_000_000L
            every { hasAccuracy() } returns true
            every { this@mockk.accuracy } returns accuracy
        }

        val atA = taken(secondsAgo = 120, accuracy = 8f)
        held.elapsedNanos = { now - 115_000_000_000L }
        assertTrue(held.offer(atA)) // the service's own accepted fix, kept when it was fresh
        held.elapsedNanos = { now }
        val atB = taken(secondsAgo = 10, accuracy = 150f)
        held.offer(atB) // as LocationRepository.sendLocation does: not kept, too rough...
        held.noteSent(atB) // ...but it went out

        val payload = livenessPayload(own = atA, held = held.newest())!!
        assertSame(atA, payload)
        assertTrue("re-sending A now would go back on B: skipped", held.goesBack(payload))

        val next = taken(secondsAgo = 1, accuracy = 30f)
        held.offer(next) // the service takes in its next fix
        val resumed = livenessPayload(own = next, held = held.newest())!!
        assertSame(next, resumed)
        assertFalse("the check-ins resume", held.goesBack(resumed))
    }

    // ===== the service's own code (it can't run under the JVM: native libraries) =====

    private val source = File("src/main/java/com/pingolin/track/service/LocationTrackingService.kt").readText()

    /** A member function of the service, from its signature to its closing brace. */
    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is in the service", start >= 0)
        return source.substring(start).substringBefore("\n    }\n") + "\n    }"
    }

    @Test
    fun `the check-in adopts the newest fix before its due test and honesty guard, and still sends through the gates`() {
        val liveness = body("private fun maybeSendLivenessCheckIn(")
        val adopt = liveness.indexOf("adoptNewerOwnFix()")
        assertTrue("adopts", adopt >= 0)
        assertTrue("before it reads the cache", adopt < liveness.indexOf("val cached = lastKnownFix ?: return"))
        assertTrue("the honesty guard measures the fix it re-sends",
            liveness.indexOf("val cached = lastKnownFix ?: return") < liveness.indexOf("stillRefreshCacheTooOld(cacheAgeMs"))
        // Never older than a position already sent; checked before anything is sent.
        val back = liveness.indexOf("if (newestOwnFix.goesBack(cached)) {")
        assertTrue(back > liveness.indexOf("livenessDue("))
        assertTrue(back < liveness.indexOf("handleLocationUpdate(cached, isLivenessCheckIn = true)"))
        // The per-circle pause, real-time and approximate rules still apply.
        assertTrue(liveness.trimEnd().endsWith("handleLocationUpdate(cached, isLivenessCheckIn = true)\n    }"))
    }

    @Test
    fun `adopting a newer fix drops a presence confirmed about the older one`() {
        val adopt = body("private fun adoptNewerOwnFix(")
        assertTrue(adopt.contains("livenessPayload(own, newestOwnFix.newest())"))
        assertTrue(adopt.contains("if (newest === own) return"))
        assertTrue(adopt.contains("lastKnownFix = newest"))
        assertTrue(adopt.contains("lastPresenceConfirmedElapsedNanos = 0L"))
    }

    @Test
    fun `a coarse STILL fix is measured from the newest position, so the presence it confirms is the re-sent fix's`() {
        val coarse = body("private fun handleCoarseStillFix(")
        assertTrue(coarse.indexOf("adoptNewerOwnFix()") in 0 until coarse.indexOf("val precise = lastKnownFix"))
    }

    @Test
    fun `every fix the service takes in is offered, after the guards, and a re-sent one is not`() {
        val handle = body("private fun handleLocationUpdate(")
        val offered = handle.indexOf("newestOwnFix.offer(location)")
        assertTrue(offered > handle.indexOf("fixAgeMs > STALE_FIX_MAX_AGE_MS"))
        assertTrue(offered > handle.indexOf("location.accuracy > minAccuracy"))
        val block = handle.substring(handle.indexOf("if (!isLivenessCheckIn) {"), offered)
        assertTrue("inside the fresh-fix block, beside lastKnownFix", block.contains("lastKnownFix = location") && !block.contains("}\n"))
        assertFalse(source.contains("STALE_FIX_MAX_AGE_MS = 30_000L"))
    }
}
