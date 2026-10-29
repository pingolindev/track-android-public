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

import com.pingolin.track.service.LocationTrackingService.Companion.decideSend
import com.pingolin.track.service.LocationTrackingService.Companion.decideSendForFix
import com.pingolin.track.service.LocationTrackingService.Companion.livenessDue
import com.pingolin.track.service.LocationTrackingService.Companion.livenessIntervalMsFor
import com.pingolin.track.service.LocationTrackingService.Companion.staleSendThresholdMsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what one fix produces: a history row, a liveness check-in,
 * or nothing.
 *
 * A history row per circle per send interval, moved or not, would have a phone
 * left on a desk overnight write several hundred rows describing one spot. The
 * rule: a green dot is legitimate if the phone is alive and COULD have sent a
 * position, so the phone keeps checking at the same frequency but only SENDS
 * history when it has actually moved — and when it has not, it still says "I am
 * here now" often enough that silence still means the device is inactive.
 */
class LivenessSendDecisionTest {

    private val fiveMin = 5 * 60_000L
    private val stillPoll = 5 * 60_000L
    private val movingPoll = 20_000L
    private val threshold = 100f

    private fun decide(
        movedMeters: Float?,
        msSinceLastSend: Long,
        timeEnabled: Boolean = true,
        distanceEnabled: Boolean = false,
        pollIntervalMs: Long = stillPoll,
    ) = decideSend(
        movedMeters = movedMeters,
        distanceThresholdMeters = threshold,
        msSinceLastSend = msSinceLastSend,
        timeEnabled = timeEnabled,
        distanceEnabled = distanceEnabled,
        livenessIntervalMs = fiveMin,
        pollIntervalMs = pollIntervalMs,
    )

    // ---- moved -> history ----

    @Test
    fun movedBeyondTheThreshold_writesHistory() {
        assertEquals(LocationSendDecision.HISTORY, decide(movedMeters = 250f, msSinceLastSend = 0L))
    }

    /** The threshold is inclusive. */
    @Test
    fun movedExactlyTheThreshold_writesHistory() {
        assertEquals(LocationSendDecision.HISTORY, decide(movedMeters = threshold, msSinceLastSend = 0L))
    }

    /**
     * Movement is judged on its own, not against the clock. A device crossing the
     * threshold one second after its last send gets a history row: that is what
     * keeps a trail through a fast drive intact.
     */
    @Test
    fun movedIsIndependentOfTheCheckInCadence() {
        assertEquals(LocationSendDecision.HISTORY, decide(movedMeters = 500f, msSinceLastSend = 1_000L))
    }

    /**
     * No baseline yet — fresh service, fresh install, or a user who just signed in.
     * The first fix has to anchor one, so it is a real send.
     */
    @Test
    fun noBaseline_writesHistoryToAnchorOne() {
        assertEquals(LocationSendDecision.HISTORY, decide(movedMeters = null, msSinceLastSend = 0L))
    }

    // ---- not moved -> liveness, on the cadence ----

    @Test
    fun notMovedAndCheckInDue_checksIn() {
        assertEquals(LocationSendDecision.LIVENESS, decide(movedMeters = 5f, msSinceLastSend = fiveMin))
    }

    @Test
    fun notMovedAtAllAndCheckInDue_checksIn() {
        assertEquals(LocationSendDecision.LIVENESS, decide(movedMeters = 0f, msSinceLastSend = fiveMin))
    }

    /**
     * A phone sitting still with time-based sending on: between check-ins it says
     * nothing at all, rather than a row every minute.
     */
    @Test
    fun notMovedAndWithinTheCadence_sendsNothing() {
        assertEquals(LocationSendDecision.NONE, decide(movedMeters = 5f, msSinceLastSend = 60_000L))
    }

    /**
     * GPS jitter on a resting phone must not read as movement. A few metres of
     * wander is well inside the threshold, which is exactly why the threshold is
     * not lowered to "send more often".
     */
    @Test
    fun gpsJitterOnARestingPhone_isNotMovement() {
        for (jitter in listOf(1f, 8f, 30f, 99f)) {
            assertEquals(
                "jitter of ${jitter}m must not count as movement",
                LocationSendDecision.NONE,
                decide(movedMeters = jitter, msSinceLastSend = 30_000L),
            )
        }
    }

    // ---- the two toggles keep their meanings ----

    /** Both off means the user asked for no uploads. A check-in is an upload. */
    @Test
    fun bothTogglesOff_sendsNothingEvenWhenMoving() {
        assertEquals(
            LocationSendDecision.NONE,
            decide(movedMeters = 5_000f, msSinceLastSend = fiveMin, timeEnabled = false, distanceEnabled = false),
        )
    }

    /**
     * Distance-only is "put me on the map when I move". Sends on a timer are what
     * that toggle turns off, and a check-in is a timed send, so a stationary
     * distance-only user stays silent.
     */
    @Test
    fun distanceOnlyAndStationary_staysSilent() {
        assertEquals(
            LocationSendDecision.NONE,
            decide(movedMeters = 5f, msSinceLastSend = 10 * fiveMin, timeEnabled = false, distanceEnabled = true),
        )
    }

    @Test
    fun distanceOnlyAndMoved_writesHistory() {
        assertEquals(
            LocationSendDecision.HISTORY,
            decide(movedMeters = 250f, msSinceLastSend = 0L, timeEnabled = false, distanceEnabled = true),
        )
    }

    /** Both toggles on behaves like time-on: movement earns history, stillness a check-in. */
    @Test
    fun bothTogglesOn_movementWinsAndStillnessChecksIn() {
        assertEquals(
            LocationSendDecision.HISTORY,
            decide(movedMeters = 250f, msSinceLastSend = 0L, timeEnabled = true, distanceEnabled = true),
        )
        assertEquals(
            LocationSendDecision.LIVENESS,
            decide(movedMeters = 5f, msSinceLastSend = fiveMin, timeEnabled = true, distanceEnabled = true),
        )
    }

    // ---- the two overrides ----

    /**
     * A transition into MOVING arms forceSendNextFix, and a place crossing does the
     * same. Both must produce a real history row: a trip with no start point, and an
     * alert whose position was never kept, are the failures they exist to prevent.
     */
    @Test
    fun aForcedSendIsAlwaysHistory() {
        assertEquals(
            LocationSendDecision.HISTORY,
            decideSendForFix(
                forceSend = true,
                isLivenessCheckIn = false,
                // Everything else says "say nothing": has not moved, nothing is due,
                // and both toggles are off.
                movedMeters = 0f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = 0L,
                timeEnabled = false,
                distanceEnabled = false,
                livenessIntervalMs = fiveMin,
                pollIntervalMs = stillPoll,
            ),
        )
    }

    /** The forced send outranks the check-in flag, not the other way round. */
    @Test
    fun aForcedSendOutranksACheckIn() {
        assertEquals(
            LocationSendDecision.HISTORY,
            decideSendForFix(
                forceSend = true,
                isLivenessCheckIn = true,
                movedMeters = 0f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = 0L,
                timeEnabled = true,
                distanceEnabled = false,
                livenessIntervalMs = fiveMin,
                pollIntervalMs = stillPoll,
            ),
        )
    }

    /**
     * The tick-driven path has already established the cadence elapsed. Re-testing
     * it here would make alternate refreshes lose by milliseconds and halve the
     * effective rate.
     */
    @Test
    fun anExplicitCheckInIsNotRetested() {
        assertEquals(
            LocationSendDecision.LIVENESS,
            decideSendForFix(
                forceSend = false,
                isLivenessCheckIn = true,
                movedMeters = 0f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = 0L,
                timeEnabled = true,
                distanceEnabled = false,
                livenessIntervalMs = fiveMin,
                pollIntervalMs = stillPoll,
            ),
        )
    }

    /** With neither override set, the fix goes through the ordinary rule. */
    @Test
    fun withNoOverride_theOrdinaryRuleDecides() {
        assertEquals(
            LocationSendDecision.NONE,
            decideSendForFix(
                forceSend = false,
                isLivenessCheckIn = false,
                movedMeters = 5f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = 1_000L,
                timeEnabled = true,
                distanceEnabled = false,
                livenessIntervalMs = fiveMin,
                pollIntervalMs = stillPoll,
            ),
        )
    }

    // ---- the due test ----

    @Test
    fun aFullPeriodIsDue() {
        assertTrue(livenessDue(fiveMin, fiveMin, stillPoll))
    }

    @Test
    fun freshlySentIsNotDue() {
        assertFalse(livenessDue(0L, fiveMin, stillPoll))
    }

    /**
     * The slack, and why it exists: the due test runs ON a tick of the very period
     * it measures, so a bare `elapsed >= interval` makes ordinary timer jitter miss
     * by milliseconds and halve the rate: sends 120s apart on a 60s interval.
     */
    @Test
    fun aTickThatArrivesMillisecondsEarly_isStillDue() {
        assertTrue(livenessDue(fiveMin - 5L, fiveMin, movingPoll))
    }

    /** The slack is capped, so a long poll cannot buy itself a large head start. */
    @Test
    fun theSlackIsCappedRegardlessOfPoll() {
        val hourPoll = 60 * 60_000L
        assertFalse(livenessDue(fiveMin - 61_000L, fiveMin, hourPoll))
        assertTrue(livenessDue(fiveMin - 60_000L, fiveMin, hourPoll))
    }

    /** A short poll gets a proportionally small slack, not the cap. */
    @Test
    fun aShortPollGetsHalfOfItselfAsSlack() {
        assertFalse(livenessDue(fiveMin - 11_000L, fiveMin, movingPoll))
        assertTrue(livenessDue(fiveMin - 10_000L, fiveMin, movingPoll))
    }

    // ---- the widened staleness watchdog ----

    /**
     * A stationary device's sends arrive on the check-in cadence, not on the
     * user's interval, so the stale threshold must clear the cadence: at the
     * 60-second default, a 5-minute threshold against a 5-minute cadence would
     * cry wolf on one tick of jitter every time the user sat down.
     */
    @Test
    fun theStaleWarningClearsTheCheckInCadence() {
        val threshold = staleSendThresholdMsFor(
            timeBasedEnabled = true, intervalMs = 60_000L, livenessIntervalMs = fiveMin,
        )
        assertEquals(2 * fiveMin, threshold)
    }

    /** Battery saver checks in every 15 minutes, so the warning has to allow 30. */
    @Test
    fun batterySaverCadenceWidensItFurther() {
        val batterySaverCadence = 3 * fiveMin
        assertEquals(
            2 * batterySaverCadence,
            staleSendThresholdMsFor(
                timeBasedEnabled = true, intervalMs = 60_000L, livenessIntervalMs = batterySaverCadence,
            ),
        )
    }

    /**
     * A long user interval still wins when it is the larger of the two clocks.
     *
     * `livenessIntervalMsFor` guarantees the check-in period is never shorter than
     * the user's interval, so a real caller cannot produce this pair. The property
     * is kept because this is a pure function over two independent arguments and
     * should stay sane for any of them.
     */
    @Test
    fun aLongUserIntervalStillGoverns() {
        val fifteenMin = 15 * 60_000L
        assertEquals(
            2 * fifteenMin,
            staleSendThresholdMsFor(
                timeBasedEnabled = true, intervalMs = fifteenMin, livenessIntervalMs = fiveMin,
            ),
        )
    }

    // ---- the cadence never beats the user's own interval ----

    /**
     * The reason the cadence takes a max.
     *
     * A user who set the 15-minute maximum has asked to be sent every 15 minutes.
     * A flat 5-minute check-in floor would send them three times as often as that,
     * defeating a rule meant to send less. With the max they get exactly the
     * cadence they chose, and still write no history at all while stationary.
     */
    @Test
    fun aFifteenMinuteUserChecksInEveryFifteenMinutes() {
        val fifteenMin = 15 * 60_000L
        assertEquals(
            fifteenMin,
            livenessIntervalMsFor(userIntervalMs = fifteenMin, batterySaver = false),
        )
    }

    /** And the whole rule holds end to end for them: nothing is due before 15 minutes. */
    @Test
    fun aFifteenMinuteUserIsNotCheckedInAtFive() {
        val fifteenMin = 15 * 60_000L
        val cadence = livenessIntervalMsFor(userIntervalMs = fifteenMin, batterySaver = false)
        assertEquals(
            LocationSendDecision.NONE,
            decideSend(
                movedMeters = 5f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = fiveMin,
                timeEnabled = true,
                distanceEnabled = false,
                livenessIntervalMs = cadence,
                pollIntervalMs = stillPoll,
            ),
        )
        assertEquals(
            LocationSendDecision.LIVENESS,
            decideSend(
                movedMeters = 5f,
                distanceThresholdMeters = threshold,
                msSinceLastSend = fifteenMin,
                timeEnabled = true,
                distanceEnabled = false,
                livenessIntervalMs = cadence,
                pollIntervalMs = stillPoll,
            ),
        )
    }

    /** The default 60-second user still gets the 5-minute floor — that is the saving. */
    @Test
    fun theDefaultUserGetsTheFiveMinuteFloor() {
        assertEquals(fiveMin, livenessIntervalMsFor(userIntervalMs = 60_000L, batterySaver = false))
    }

    /**
     * Every interval the settings screen actually offers, in seconds. Taken from the
     * chip group in SettingsFragment (10 / 30 / 60 / 300 / 600 / 900) rather than
     * invented, so this test is about real settings and not a plausible-looking range.
     */
    private val offeredIntervalsMs =
        listOf(10L, 30L, 60L, 300L, 600L, 900L).map { it * 1000L }

    /**
     * No setting checks in more often than its own send interval. That is the
     * property the max exists to guarantee, so assert it for every setting a
     * user can actually pick rather than at one point.
     */
    @Test
    fun noSettingChecksInMoreOftenThanItSendsToday() {
        for (userInterval in offeredIntervalsMs) {
            for (batterySaver in listOf(false, true)) {
                val cadence = livenessIntervalMsFor(userInterval, batterySaver)
                assertTrue(
                    "cadence ${cadence}ms must not be shorter than the user's ${userInterval}ms interval",
                    cadence >= userInterval,
                )
            }
        }
    }

    /**
     * The stale-send warning is derived from the cadence, and the cadence can be
     * larger than the floor — so check the arithmetic still lands where it should
     * for every real setting, rather than trusting that it does.
     *
     * The property: the threshold is EXACTLY two check-in periods, everywhere. If it
     * were less, a device checking in on schedule would still trip the warning.
     */
    @Test
    fun theStaleThresholdIsAlwaysExactlyTwoCheckInPeriods() {
        for (userInterval in offeredIntervalsMs) {
            for (batterySaver in listOf(false, true)) {
                val cadence = livenessIntervalMsFor(userInterval, batterySaver)
                assertEquals(
                    "interval=${userInterval}ms batterySaver=$batterySaver",
                    2 * cadence,
                    staleSendThresholdMsFor(
                        timeBasedEnabled = true,
                        intervalMs = userInterval,
                        livenessIntervalMs = cadence,
                    ),
                )
            }
        }
    }

    /**
     * And it never grows past the heartbeat worker's own 45-minute watchdog, or the
     * two would race: the worker would fire a staleness heartbeat for a device whose
     * foreground service had not yet decided anything was wrong.
     */
    @Test
    fun theStaleThresholdStaysInsideTheHeartbeatWatchdog() {
        val heartbeatWatchdogMs = 45 * 60_000L
        for (userInterval in offeredIntervalsMs) {
            for (batterySaver in listOf(false, true)) {
                val threshold = staleSendThresholdMsFor(
                    timeBasedEnabled = true,
                    intervalMs = userInterval,
                    livenessIntervalMs = livenessIntervalMsFor(userInterval, batterySaver),
                )!!
                assertTrue(
                    "threshold ${threshold}ms (interval=${userInterval}ms, batterySaver=$batterySaver) must stay under the worker's ${heartbeatWatchdogMs}ms watchdog",
                    threshold < heartbeatWatchdogMs,
                )
            }
        }
    }

    /**
     * Battery saver checks in on its BATCH interval (the 5/15/30/60-minute
     * chips), which is also its poll. The real-time send interval is not in play
     * there: a setting the saver does not use must not decide the saver's
     * cadence. Defaults to the 15-minute chip.
     */
    @Test
    fun batterySaverChecksInOnItsBatchInterval() {
        val fifteenMin = 15 * 60_000L
        val thirtyMin = 30 * 60_000L
        // The default batch interval, whatever the real-time interval says.
        assertEquals(fifteenMin, livenessIntervalMsFor(userIntervalMs = 60_000L, batterySaver = true))
        assertEquals(fifteenMin, livenessIntervalMsFor(userIntervalMs = thirtyMin, batterySaver = true))
        // A chosen batch interval governs.
        assertEquals(
            thirtyMin,
            livenessIntervalMsFor(userIntervalMs = 60_000L, batterySaver = true, saverIntervalMs = thirtyMin),
        )
    }

    /**
     * A missing or nonsensical stored interval falls back to the floor rather than
     * producing a zero-length cadence that would check in on every single fix.
     */
    @Test
    fun anAbsentIntervalFallsBackToTheFloor() {
        assertEquals(fiveMin, livenessIntervalMsFor(userIntervalMs = 0L, batterySaver = false))
        assertEquals(fiveMin, livenessIntervalMsFor(userIntervalMs = -1L, batterySaver = false))
    }

    /**
     * Distance-only stays suppressed. Those users send nothing while stationary —
     * no history and no check-in — so there is no expected cadence to be late
     * against and the warning would be pure noise.
     */
    @Test
    fun distanceOnlySuppressesTheWarning() {
        assertNull(
            staleSendThresholdMsFor(
                timeBasedEnabled = false, intervalMs = 60_000L, livenessIntervalMs = fiveMin,
            ),
        )
    }
}
