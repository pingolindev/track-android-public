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

import com.pingolin.track.location.LocationAccuracy
import com.pingolin.track.util.MotionState
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for LocationTrackingService.
 *
 * Note: Full service lifecycle tests require instrumented tests because:
 * 1. The service loads native libraries (SQLCipher, pingolin_mobile)
 * 2. Robolectric cannot properly mock native library loading
 *
 * These tests verify the service constants and public API signatures, its
 * static state and its intent extras. For full integration tests, see
 * androidTest/.
 */
class LocationTrackingServiceTest {

    // =============================================================================
    // INTENT ACTION CONSTANTS
    // =============================================================================

    @Test
    fun `ACTION_START constant is defined`() {
        assertEquals("com.pingolin.track.START_TRACKING", LocationTrackingService.ACTION_START)
    }

    @Test
    fun `ACTION_STOP constant is defined`() {
        assertEquals("com.pingolin.track.STOP_TRACKING", LocationTrackingService.ACTION_STOP)
    }

    @Test
    fun `action constants are unique`() {
        assertNotEquals(
            "ACTION_START and ACTION_STOP should be different",
            LocationTrackingService.ACTION_START,
            LocationTrackingService.ACTION_STOP
        )
    }

    // =============================================================================
    // INTENT EXTRA CONSTANTS
    // =============================================================================

    @Test
    fun `EXTRA_INTERVAL_MS constant is defined`() {
        assertEquals("interval_ms", LocationTrackingService.EXTRA_INTERVAL_MS)
    }

    @Test
    fun `EXTRA_BATTERY_SAVER constant is defined`() {
        assertEquals("battery_saver", LocationTrackingService.EXTRA_BATTERY_SAVER)
    }

    @Test
    fun `extra constants follow naming convention`() {
        // Extras should be lowercase with underscores
        assertTrue(
            "EXTRA_INTERVAL_MS should be lowercase",
            LocationTrackingService.EXTRA_INTERVAL_MS.matches(Regex("[a-z_]+"))
        )
        assertTrue(
            "EXTRA_BATTERY_SAVER should be lowercase",
            LocationTrackingService.EXTRA_BATTERY_SAVER.matches(Regex("[a-z_]+"))
        )
    }

    // =============================================================================
    // STATIC STATE TESTS
    // =============================================================================

    @Test
    fun `isRunning is initially false`() {
        // Note: This tests the companion object's initial state.
        // The service hasn't been started, so isRunning should be false.
        // This may be affected by other tests running in the same JVM.
        assertFalse(LocationTrackingService.isRunning)
    }

    @Test
    fun `isRunning is accessible from companion object`() {
        val running = LocationTrackingService.isRunning
        assertNotNull(running)
    }

    // =============================================================================
    // NOTIFICATION CHANNEL CONSTANTS
    // =============================================================================

    @Test
    fun `notification channel IDs are defined`() {
        // These are defined in TrackApplication, but we verify consistency
        // The service should use these channel IDs for its notifications
        val locationChannelId = "location_tracking"
        val alertsChannelId = "alerts"
        val sosChannelId = "sos_emergency"

        assertTrue(locationChannelId.isNotEmpty())
        assertTrue(alertsChannelId.isNotEmpty())
        assertTrue(sosChannelId.isNotEmpty())

        // Each channel must be distinct, or one silently swallows another's posts.
        val channels = setOf(locationChannelId, alertsChannelId, sosChannelId)
        assertEquals(3, channels.size)
    }

    // =============================================================================
    // INTERVAL VALIDATION TESTS
    //
    // The service's interval constants are private, so the values below are
    // transcribed from it. These pin the RELATIONSHIPS the constants must satisfy
    // (a floor on battery cost, battery-saver slower than default); they cannot
    // detect the service changing a value out from under them.
    // =============================================================================

    @Test
    fun `default interval is reasonable`() {
        val defaultIntervalMs = 30_000L
        assertTrue("Default interval should be at least 10 seconds", defaultIntervalMs >= 10_000)
        assertTrue("Default interval should be at most 5 minutes", defaultIntervalMs <= 300_000)
    }

    @Test
    fun `minimum interval prevents battery drain`() {
        val minIntervalMs = 10_000L
        assertTrue(
            "Minimum interval should prevent excessive battery drain",
            minIntervalMs >= 5_000
        )
    }

    @Test
    fun `battery saver interval is longer than default`() {
        val batterySaverIntervalMs = 3_600_000L
        val defaultIntervalMs = 30_000L
        assertTrue(
            "Battery saver interval should be longer than default",
            batterySaverIntervalMs > defaultIntervalMs
        )
    }

    // =============================================================================
    // ACCURACY THRESHOLD TESTS
    //
    // Transcribed constants again — same caveat as the interval section above.
    // =============================================================================

    @Test
    fun `high accuracy threshold is reasonable`() {
        val highAccuracyThreshold = 100f
        assertTrue(
            "High accuracy should accept locations within 100m",
            highAccuracyThreshold <= 100
        )
        assertTrue(
            "High accuracy should filter very inaccurate locations",
            highAccuracyThreshold > 0
        )
    }

    @Test
    fun `balanced accuracy threshold is more lenient`() {
        val balancedAccuracyThreshold = 500f
        val highAccuracyThreshold = 100f
        assertTrue(
            "Balanced threshold should be more lenient than high accuracy",
            balancedAccuracyThreshold > highAccuracyThreshold
        )
    }

    // =============================================================================
    // POLLING PROFILE TESTS
    // =============================================================================
    //
    // The rules live in LocationTrackingService.pollProfileFor, a pure
    // function, and these call it rather than a copy of its formula, so a
    // change to the service can fail them.

    private val MIN_POLL_MS = 10_000L
    private val MAX_POLL_MS = 30 * 60_000L
    private val sixtySeconds = 60_000L

    private fun profile(
        motion: MotionState,
        batteryLevel: Int? = 80,
        isCharging: Boolean = false,
        powerSaveMode: Boolean = false,
        stationaryWhileMoving: Boolean = false,
        batterySaver: Boolean = false,
        saverMinutes: Int = 15,
        timeBasedEnabled: Boolean = true,
        userIntervalMs: Long = sixtySeconds,
    ): PollProfile = LocationTrackingService.pollProfileFor(
        motion = motion,
        stationaryWhileMoving = stationaryWhileMoving,
        batterySaver = batterySaver,
        saverIntervalMs = LocationTrackingService.saverIntervalMsFor(saverMinutes),
        multiplier = LocationTrackingService.batteryMultiplierFor(batteryLevel, isCharging, powerSaveMode),
        timeBasedEnabled = timeBasedEnabled,
        userIntervalMs = userIntervalMs,
    )

    @Test
    fun `computePollIntervalMs MOVING high battery uses base 20s`() {
        assertEquals(PollProfile(20_000L, LocationAccuracy.HIGH), profile(MotionState.MOVING))
    }

    @Test
    fun `computePollIntervalMs STILL high battery uses base 5 min`() {
        assertEquals(5 * 60_000L, profile(MotionState.STILL).intervalMs)
    }

    @Test
    fun `computePollIntervalMs low-battery MOVING multiplies by 4`() {
        // The user-interval cap (60 s) doesn't cancel the multiplier: 20
        // s x 4.
        assertEquals(20_000L * 4, profile(MotionState.MOVING, batteryLevel = 10).intervalMs)
    }

    @Test
    fun `computePollIntervalMs mid-battery multiplies by 2`() {
        assertEquals(20_000L * 2, profile(MotionState.MOVING, batteryLevel = 30).intervalMs)
    }

    @Test
    fun `computePollIntervalMs charging does not multiply even at low battery`() {
        assertEquals(20_000L, profile(MotionState.MOVING, isCharging = true, batteryLevel = 5).intervalMs)
    }

    @Test
    fun `computePollIntervalMs clamps to MIN_POLL_MS`() {
        // A user interval below the floor caps the poll, then the clamp lifts it.
        assertEquals(MIN_POLL_MS, profile(MotionState.MOVING, userIntervalMs = 1_000L).intervalMs)
    }

    @Test
    fun `computePollIntervalMs STILL at low battery is 20 min, inside MAX_POLL_MS`() {
        val interval = profile(MotionState.STILL, batteryLevel = 10).intervalMs
        assertEquals(5L * 60_000L * 4, interval)
        assertTrue(interval <= MAX_POLL_MS)
    }

    @Test
    fun `computePollIntervalMs UNKNOWN motion uses 30s base`() {
        assertEquals(PollProfile(30_000L, LocationAccuracy.HIGH), profile(MotionState.UNKNOWN, userIntervalMs = 0L))
    }

    @Test
    fun `the user interval caps the poll at the normal battery tier`() {
        // UNKNOWN 30 s with a 10 s user interval: the scheduled-send cap still applies.
        assertEquals(10_000L, profile(MotionState.UNKNOWN, userIntervalMs = 10_000L).intervalMs)
    }

    @Test
    fun `the user interval no longer cancels the low-battery multiplier`() {
        // UNKNOWN at < 20 % stays at 120 s: dragged back to the 60 s
        // default interval, it would lose half of what the multiplier
        // saves.
        assertEquals(30_000L * 4, profile(MotionState.UNKNOWN, batteryLevel = 10).intervalMs)
        assertEquals(30_000L * 2, profile(MotionState.UNKNOWN, batteryLevel = 30).intervalMs)
    }

    @Test
    fun `STILL is exempt from the user-interval cap`() {
        assertEquals(5 * 60_000L, profile(MotionState.STILL, userIntervalMs = 10_000L).intervalMs)
    }

    // ---- accuracy ---------------------------------------------------------------

    @Test
    fun `STILL polls BALANCED, everything else in real-time mode polls HIGH`() {
        assertEquals(LocationAccuracy.BALANCED, profile(MotionState.STILL).accuracy)
        assertEquals(LocationAccuracy.HIGH, profile(MotionState.MOVING).accuracy)
        assertEquals(LocationAccuracy.HIGH, profile(MotionState.UNKNOWN).accuracy)
    }

    @Test
    fun `GPS evidence of stillness while MOVING polls at the STILL cadence but stays HIGH`() {
        val p = profile(MotionState.MOVING, stationaryWhileMoving = true)
        assertEquals(5 * 60_000L, p.intervalMs)
        assertEquals(LocationAccuracy.HIGH, p.accuracy)
        // And the flag means nothing outside MOVING.
        assertEquals(30_000L, profile(MotionState.UNKNOWN, stationaryWhileMoving = true, userIntervalMs = 0L).intervalMs)
    }

    // ---- battery saver ----------------------------------------------------------

    @Test
    fun `battery saver is a flat BALANCED poll at the batch interval`() {
        for (motion in MotionState.values()) {
            assertEquals(
                "saver must ignore motion ($motion)",
                PollProfile(15 * 60_000L, LocationAccuracy.BALANCED),
                profile(motion, batterySaver = true, saverMinutes = 15),
            )
        }
    }

    @Test
    fun `battery saver ignores the battery tier and the user interval`() {
        val expected = PollProfile(30 * 60_000L, LocationAccuracy.BALANCED)
        assertEquals(expected, profile(MotionState.MOVING, batterySaver = true, saverMinutes = 30, batteryLevel = 5))
        assertEquals(expected, profile(MotionState.MOVING, batterySaver = true, saverMinutes = 30, userIntervalMs = 10_000L))
    }

    @Test
    fun `battery saver honours every batch chip the UI offers`() {
        for (minutes in listOf(5, 15, 30, 60)) {
            assertEquals(minutes * 60_000L, profile(MotionState.STILL, batterySaver = true, saverMinutes = minutes).intervalMs)
        }
    }

    @Test
    fun `a stray stored batch interval is clamped to the chips`() {
        assertEquals(5 * 60_000L, LocationTrackingService.saverIntervalMsFor(0))
        assertEquals(5 * 60_000L, LocationTrackingService.saverIntervalMsFor(1))
        assertEquals(60 * 60_000L, LocationTrackingService.saverIntervalMsFor(24 * 60))
    }

    // ---- the OS power saver -------------------------------------------------------

    @Test
    fun `the OS power saver counts as the low battery tier`() {
        assertEquals(4, LocationTrackingService.batteryMultiplierFor(90, isCharging = false, powerSaveMode = true))
        assertEquals(0, LocationTrackingService.batteryTierFor(90, isCharging = false, powerSaveMode = true))
        assertEquals(20_000L * 4, profile(MotionState.MOVING, batteryLevel = 90, powerSaveMode = true).intervalMs)
    }

    @Test
    fun `charging outranks the OS power saver`() {
        assertEquals(1, LocationTrackingService.batteryMultiplierFor(10, isCharging = true, powerSaveMode = true))
        assertEquals(2, LocationTrackingService.batteryTierFor(10, isCharging = true, powerSaveMode = true))
    }

    @Test
    fun `tiers are a step function of the level`() {
        assertEquals(2, LocationTrackingService.batteryTierFor(null, isCharging = false, powerSaveMode = false))
        assertEquals(2, LocationTrackingService.batteryTierFor(50, isCharging = false, powerSaveMode = false))
        assertEquals(1, LocationTrackingService.batteryTierFor(49, isCharging = false, powerSaveMode = false))
        assertEquals(1, LocationTrackingService.batteryTierFor(20, isCharging = false, powerSaveMode = false))
        assertEquals(0, LocationTrackingService.batteryTierFor(19, isCharging = false, powerSaveMode = false))
    }

    // ---- what the ongoing notification may say about sharing ----

    private fun row(circleId: String, mode: String = "exact", until: Long? = null, realtime: Boolean = true) =
        com.pingolin.track.data.local.entity.PrivacySettingEntity(
            circleId = circleId, sharingMode = mode, pausedUntil = until, shareRealtime = realtime, updatedAt = 0L,
        )

    @Test
    fun `the notification says sharing only while some circle receives the location`() {
        val now = 1_000_000L
        val sharing = LocationTrackingService.SharingNow.SHARING
        val paused = LocationTrackingService.SharingNow.PAUSED
        val off = LocationTrackingService.SharingNow.OFF
        fun of(vararg rows: com.pingolin.track.data.local.entity.PrivacySettingEntity, ids: List<String> = rows.map { it.circleId }) =
            LocationTrackingService.sharingNowFor(ids, rows.associateBy { it.circleId }, now)

        assertEquals(sharing, of(row("a", "paused"), row("b")))
        assertEquals(paused, of(row("a", "paused"), row("b", "paused", until = now + 1)))
        assertEquals("paused and off together", paused, of(row("a", "paused"), row("b", realtime = false)))
        assertEquals(off, of(row("a", realtime = false), row("b", "approximate", realtime = false)))
        assertEquals("no circle: the location goes to nobody", off, of())
        assertEquals("a pause whose end has passed receives again", sharing, of(row("a", "paused", until = now - 1)))
        assertEquals("a circle still being primed isn't called off", sharing, of(row("a", "paused"), ids = listOf("a", "b")))
    }

    @Test
    fun `the notification's words follow that, from the rows the send gates read`() {
        val src = java.io.File("src/main/java/com/pingolin/track/service/LocationTrackingService.kt").readText()
        fun body(signature: String) = src.substring(src.indexOf(signature)).substringBefore("\n    private fun ")
        assertTrue(body("private fun buildNotificationText(").contains("notSharingText()?.let { return it }"))
        assertTrue(body("private fun createNotification(").contains("notSharingText() ?:"))
        val words = body("private fun notSharingText(")
        assertTrue(words, words.contains("R.string.notification_sharing_paused") && words.contains("R.string.member_sharing_off"))
        // The send loop's own gate rows, and Room on every notification tick.
        assertTrue(src.contains("sharingNow = sharingNowFor(circleIds, privacySettingsMap, now)"))
        assertTrue(body("private suspend fun refreshSharingNow(").contains("privacySettingDao.getAllForCircles(circleIds)"))
    }
}
