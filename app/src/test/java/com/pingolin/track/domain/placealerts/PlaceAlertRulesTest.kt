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

package com.pingolin.track.domain.placealerts

import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceAlertShownEntity
import com.pingolin.track.domain.placealerts.PlaceAlertRules.Point
import com.pingolin.track.domain.placealerts.PlaceAlertRules.Surfacing
import com.pingolin.track.domain.placealerts.PlaceAlertRules.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The four place-alert correctness rules, tested directly against the pure
 * evaluation logic:
 * 1. Each (place, member) pair keeps a watermark, the newest location already
 *    accounted for, so an overlapping batch never re-drives a transition.
 * 2. A batch is walked from oldest to newest.
 * 3. A crossing is stamped with when it happened, never with "now", so a delayed
 *    alert never sounds live.
 * 4. A crossing has a stable identity, so it surfaces at most once, ever.
 */
class PlaceAlertRulesTest {

    private val home = PlaceAlertRules.PlaceGeofence(
        placeId = "place-home",
        latitude = 37.0,
        longitude = -122.0,
        radiusMeters = 150,
    )

    private val utc: ZoneId = ZoneId.of("UTC")

    /** A realistic epoch-millis "now" — small synthetic values would push
     *  "three hours ago" negative and below the watermark. */
    private val NOW = 1_800_000_000_000L

    /** Dead centre of the geofence. */
    private fun inside(ts: Long) = Point(37.0, -122.0, ts)

    /** ~2.2 km north — comfortably outside a 150 m ring. */
    private fun outside(ts: Long) = Point(37.02, -122.0, ts)

    private fun alert(
        id: String = "alert-1",
        targetUserId: String = "member-a",
        alertOnArrival: Boolean = true,
        alertOnDeparture: Boolean = false,
        activeDays: String? = null,
        activeStartTime: String? = null,
        activeEndTime: String? = null,
        isOvernight: Boolean = false,
        isActive: Boolean = true,
        expiresAt: Long? = null,
        circleId: String? = null,
        suppressedByTarget: Boolean? = null,
    ) = PlaceAlertEntity(
        id = id,
        placeId = home.placeId,
        circleId = circleId,
        userId = "me",
        targetUserId = targetUserId,
        alertOnArrival = alertOnArrival,
        alertOnDeparture = alertOnDeparture,
        activeDays = activeDays,
        activeStartTime = activeStartTime,
        activeEndTime = activeEndTime,
        isOvernight = isOvernight,
        isActive = isActive,
        expiresAt = expiresAt,
        dayBucket = 0,
        suppressedByTarget = suppressedByTarget,
    )

    // ===== allow_alerts_on_me: consent is checked on every evaluation =====
    //
    // Parity with the iOS app's place-alert rule tests. Two independent
    // signals, because they fail at different times: the server-computed
    // suppression flag is anchored to the right circle but is only as fresh as
    // the last alert sync, while the per-fix flag is always current but only
    // knows the circle its fix arrived on.

    @Test
    fun `a server-suppressed alert does not watch its target`() {
        val a = alert(targetUserId = "member-a", suppressedByTarget = true)
        assertFalse(PlaceAlertRules.alertWatches(a, "member-a", true))
    }

    @Test
    fun `the per-fix flag blocks before the alert list has caught up`() {
        // The window this closes: the target flips the switch and this device's
        // cached alert row still says "not suppressed" until its next sync. The
        // location itself carries the fresh posture, so it wins immediately.
        val a = alert(targetUserId = "member-a", suppressedByTarget = false)
        assertFalse(
            PlaceAlertRules.alertWatches(a, "member-a", true, targetAllowsAlertsOnLatestFix = false),
        )
    }

    @Test
    fun `consent beats targeting - a circle-wide alert cannot sweep up an opted-out member`() {
        // Circle-wide alerts name nobody, so a consent check wired into the
        // targeted branch alone would let them through.
        val a = alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET, circleId = "circle-1")
        assertFalse(
            PlaceAlertRules.alertWatches(a, "member-a", true, targetAllowsAlertsOnLatestFix = false),
        )
        assertTrue(
            PlaceAlertRules.alertWatches(a, "member-a", true, targetAllowsAlertsOnLatestFix = true),
        )
    }

    @Test
    fun `absence permits - unknown consent must not silently disable alerts`() {
        // Most members have no privacy_settings row at all, and an older backend
        // omits both fields. Reading either as denial would turn the feature off
        // across the estate, and a silently disabled alert is indistinguishable
        // from a broken one.
        val a = alert(targetUserId = "member-a")
        assertTrue(PlaceAlertRules.alertWatches(a, "member-a", true))
        assertTrue(PlaceAlertRules.alertWatches(a, "member-a", true, targetAllowsAlertsOnLatestFix = null))
        assertTrue(PlaceAlertRules.alertWatches(a, "member-a", true, targetAllowsAlertsOnLatestFix = true))
    }

    @Test
    fun `only an explicit denial suppresses`() {
        val a = alert(targetUserId = "member-a", suppressedByTarget = false)
        assertTrue(PlaceAlertRules.alertWatches(a, "member-a", true))
    }

    // ===== Geometry: parity with the native BoundaryChecker =====

    @Test
    fun `inside is distance within radius, matching the native checker`() {
        // With no prior state the plain radius applies on both sides.
        assertTrue(PlaceAlertRules.isInside(home, 37.0, -122.0, wasInside = null))
        assertFalse(PlaceAlertRules.isInside(home, 37.02, -122.0, wasInside = null))
    }

    @Test
    fun `distance uses the same haversine as the native checker`() {
        // 0.01 degrees of latitude is ~1111 m on a 6_371_000 m sphere.
        val d = PlaceAlertRules.distanceMeters(37.0, -122.0, 37.01, -122.0)
        assertTrue("expected ~1111m, got $d", d > 1105.0 && d < 1118.0)
    }

    // ===== Rule 1 + 2: stateful, reconciled in time order =====

    @Test
    fun `a fresh enter is detected as an ENTER transition`() {
        val result = PlaceAlertRules.reconcile(
            geofence = home,
            userId = "member-a",
            prior = PlaceAlertRules.MemberPlaceState(isInside = false, lastLocationAt = 500L),
            points = listOf(inside(1_000L)),
        )!!

        assertEquals(1, result.crossings.size)
        assertEquals(Transition.ENTER, result.crossings.single().transition)
        assertEquals(1_000L, result.crossings.single().crossedAt)
        assertTrue(result.state.isInside)
        assertEquals(1_000L, result.state.lastLocationAt)
    }

    @Test
    fun `an exit is detected as an EXIT transition`() {
        val result = PlaceAlertRules.reconcile(
            geofence = home,
            userId = "member-a",
            prior = PlaceAlertRules.MemberPlaceState(isInside = true, lastLocationAt = 500L),
            points = listOf(outside(1_000L)),
        )!!

        assertEquals(Transition.EXIT, result.crossings.single().transition)
        assertFalse(result.state.isInside)
    }

    @Test
    fun `the first ever fix seeds state and raises no crossing`() {
        // A first-ever observation is not a transition — the native path models this
        // the same way, as an initial state report that creates no event.
        val result = PlaceAlertRules.reconcile(home, "member-a", prior = null, points = listOf(inside(1_000L)))!!

        assertTrue(result.crossings.isEmpty())
        assertTrue(result.state.isInside)
    }

    @Test
    fun `an unsorted batch is reconciled oldest to newest`() {
        // Deliberately shuffled: the walk must be by timestamp, not list order,
        // or the derived transitions are nonsense.
        val result = PlaceAlertRules.reconcile(
            geofence = home,
            userId = "member-a",
            prior = PlaceAlertRules.MemberPlaceState(isInside = false, lastLocationAt = 0L),
            points = listOf(outside(3_000L), inside(1_000L), inside(2_000L)),
        )!!

        assertEquals(
            listOf(Transition.ENTER to 1_000L, Transition.EXIT to 3_000L),
            result.crossings.map { it.transition to it.crossedAt },
        )
        assertEquals(3_000L, result.state.lastLocationAt)
    }

    @Test
    fun `points at or before the watermark are already accounted for and skipped`() {
        // Rule 1's watermark: an overlapping batch must not re-drive a transition.
        val result = PlaceAlertRules.reconcile(
            geofence = home,
            userId = "member-a",
            prior = PlaceAlertRules.MemberPlaceState(isInside = true, lastLocationAt = 2_000L),
            points = listOf(outside(1_000L), outside(2_000L)),
        )

        assertNull("batch was entirely at or before the watermark", result)
    }

    @Test
    fun `a replayed overlapping batch yields the crossing exactly once`() {
        val prior = PlaceAlertRules.MemberPlaceState(isInside = false, lastLocationAt = 0L)
        val batch = listOf(outside(1_000L), inside(2_000L))

        val first = PlaceAlertRules.reconcile(home, "member-a", prior, batch)!!
        assertEquals(1, first.crossings.size)

        // The same page delivered again, now against the advanced state.
        val second = PlaceAlertRules.reconcile(home, "member-a", first.state, batch)
        assertNull("replayed page contributes nothing", second)
    }

    // ===== Rule 2 + notification rules: what may be surfaced =====

    @Test
    fun `an old inside point does not fire a live alert`() {
        // A batch replayed as if each point were "now" would make an hours-old
        // inside point look like a fresh arrival.
        val now = NOW
        val threeHoursAgo = now - 3 * 60 * 60 * 1000L
        val twoAndAHalfHoursAgo = now - 150 * 60 * 1000L

        val reconciled = PlaceAlertRules.reconcile(
            geofence = home,
            userId = "member-a",
            prior = PlaceAlertRules.MemberPlaceState(isInside = false, lastLocationAt = 0L),
            points = listOf(inside(threeHoursAgo), outside(twoAndAHalfHoursAgo)),
        )!!

        val decisions = PlaceAlertRules.classify(reconciled.crossings, reconciled.state.isInside, now)

        assertEquals(1, decisions.size)
        assertTrue("an hours-old visit must fold into history, not push", decisions.single().silent)
    }

    @Test
    fun `entered and still inside notifies with the real elapsed time`() {
        val now = NOW
        val enteredAt = now - 15 * 60 * 1000L

        val decisions = PlaceAlertRules.classify(
            crossings = listOf(crossing(Transition.ENTER, enteredAt)),
            finalInside = true,
            now = now,
        )

        val decision = decisions.single()
        assertFalse(decision.silent)
        val surfacing = decision.surfacing as Surfacing.Arrival
        assertTrue(surfacing.stillInside)
        // Rule 3: stamped with the real crossing time, never live-sounding language.
        assertEquals(
            "Alice arrived at Home 15 minutes ago and is still there",
            PlaceAlertRules.describe(surfacing, "Alice", "Home", now, utc),
        )
    }

    @Test
    fun `a crossing within the lag tolerance reads as live`() {
        val now = NOW
        val decisions = PlaceAlertRules.classify(
            crossings = listOf(crossing(Transition.ENTER, now - 30_000L)),
            finalInside = true,
            now = now,
        )

        assertEquals(
            "Alice arrived at Home",
            PlaceAlertRules.describe(decisions.single().surfacing, "Alice", "Home", now, utc),
        )
    }

    @Test
    fun `enter plus exit collapses into ONE past-framed entry`() {
        val now = NOW
        val enteredAt = now - 40 * 60 * 1000L
        val leftAt = now - 10 * 60 * 1000L

        val decisions = PlaceAlertRules.classify(
            crossings = listOf(
                crossing(Transition.ENTER, enteredAt),
                crossing(Transition.EXIT, leftAt),
            ),
            finalInside = false,
            now = now,
        )

        assertEquals("a complete pass-through is one entry, not two pings", 1, decisions.size)
        val surfacing = decisions.single().surfacing
        assertTrue(surfacing is Surfacing.PassThrough)
        // Both halves must still be claimed in the ledger so neither can resurface.
        assertEquals(2, surfacing.crossings.size)
        val copy = PlaceAlertRules.describe(surfacing, "Alice", "Home", now, utc)
        assertTrue("expected a past-framed span, got: $copy", copy.startsWith("Alice was at Home, "))
        assertTrue("expected a clock range, got: $copy", copy.contains("-"))
    }

    @Test
    fun `a pass-through is judged stale on when it ENDED, not when it started`() {
        val now = NOW
        // Started well outside the window, but ended five minutes ago: a recent miss.
        val decisions = PlaceAlertRules.classify(
            crossings = listOf(
                crossing(Transition.ENTER, now - 5 * 60 * 60 * 1000L),
                crossing(Transition.EXIT, now - 5 * 60 * 1000L),
            ),
            finalInside = false,
            now = now,
        )

        assertFalse(decisions.single().silent)
    }

    @Test
    fun `staleness window boundary`() {
        val now = NOW
        val window = PlaceAlertRules.STALENESS_WINDOW_MS

        val atBoundary = PlaceAlertRules.classify(
            crossings = listOf(crossing(Transition.EXIT, now - window)),
            finalInside = false,
            now = now,
        ).single()
        assertFalse("exactly at the window still notifies", atBoundary.silent)

        val pastBoundary = PlaceAlertRules.classify(
            crossings = listOf(crossing(Transition.EXIT, now - window - 1)),
            finalInside = false,
            now = now,
        ).single()
        assertTrue("one millisecond past the window folds silently", pastBoundary.silent)
    }

    // ===== Rule 4: the crossing identity used for de-duplication =====

    @Test
    fun `ledger id is derived from the crossing, not from a row id`() {
        val a = crossing(Transition.ENTER, 1_700_000_000_000L)
        val b = crossing(Transition.ENTER, 1_700_000_000_000L)
        assertEquals("the same crossing must produce the same id across restarts", a.ledgerId, b.ledgerId)
        assertEquals("place-home:member-a:enter:1700000000000", a.ledgerId)

        // Every component participates, so distinct crossings never collide.
        assertTrue(a.ledgerId != crossing(Transition.EXIT, 1_700_000_000_000L).ledgerId)
        assertTrue(a.ledgerId != crossing(Transition.ENTER, 1_700_000_000_001L).ledgerId)
        assertTrue(a.ledgerId != a.copy(userId = "member-b").ledgerId)
    }

    // ===== Alert-config gating =====

    @Test
    fun `arrival-only alert ignores a departure and vice versa`() {
        val arrivalOnly = alert(alertOnArrival = true, alertOnDeparture = false)
        val departureOnly = alert(alertOnArrival = false, alertOnDeparture = true)
        val arrival = Surfacing.Arrival(crossing(Transition.ENTER, 1_000L), stillInside = true)
        val departure = Surfacing.Departure(crossing(Transition.EXIT, 1_000L))

        assertTrue(PlaceAlertRules.alertWantsSurfacing(arrivalOnly, arrival))
        assertFalse(PlaceAlertRules.alertWantsSurfacing(arrivalOnly, departure))
        assertFalse(PlaceAlertRules.alertWantsSurfacing(departureOnly, arrival))
        assertTrue(PlaceAlertRules.alertWantsSurfacing(departureOnly, departure))
    }

    @Test
    fun `a pass-through satisfies an arrival-only alert`() {
        val passThrough = Surfacing.PassThrough(
            enter = crossing(Transition.ENTER, 1_000L),
            exit = crossing(Transition.EXIT, 2_000L),
        )
        assertTrue(PlaceAlertRules.alertWantsSurfacing(alert(alertOnArrival = true, alertOnDeparture = false), passThrough))
        assertTrue(PlaceAlertRules.alertWantsSurfacing(alert(alertOnArrival = false, alertOnDeparture = true), passThrough))
    }

    @Test
    fun `an alert with no schedule is always in window`() {
        assertTrue(PlaceAlertRules.isWithinSchedule(alert(), atMillis = 1_700_000_000_000L, zone = utc))
    }

    @Test
    fun `time window gates on the crossing time`() {
        val scheduled = alert(activeStartTime = "08:00", activeEndTime = "18:00")
        assertTrue(PlaceAlertRules.isWithinSchedule(scheduled, utcAt("2026-07-22", 9, 30), utc))
        assertFalse(PlaceAlertRules.isWithinSchedule(scheduled, utcAt("2026-07-22", 7, 59), utc))
        assertFalse(PlaceAlertRules.isWithinSchedule(scheduled, utcAt("2026-07-22", 18, 1), utc))
    }

    @Test
    fun `day window gates on the crossing day`() {
        // 2026-07-22 is a Wednesday, 2026-07-25 a Saturday.
        val weekdays = alert(activeDays = "monday,tuesday,wednesday,thursday,friday")
        assertTrue(PlaceAlertRules.isWithinSchedule(weekdays, utcAt("2026-07-22", 9, 0), utc))
        assertFalse(PlaceAlertRules.isWithinSchedule(weekdays, utcAt("2026-07-25", 9, 0), utc))
    }

    @Test
    fun `overnight window spans midnight and belongs to the day it starts on`() {
        val overnight = alert(
            activeDays = "friday",
            activeStartTime = "22:00",
            activeEndTime = "06:00",
            isOvernight = true,
        )
        // 2026-07-24 is a Friday.
        assertTrue("late Friday evening", PlaceAlertRules.isWithinSchedule(overnight, utcAt("2026-07-24", 23, 0), utc))
        // Saturday 02:00 is still inside FRIDAY's overnight window.
        assertTrue("small hours of Saturday", PlaceAlertRules.isWithinSchedule(overnight, utcAt("2026-07-25", 2, 0), utc))
        // Saturday 23:00 starts SATURDAY's window, which isn't scheduled.
        assertFalse("Saturday evening", PlaceAlertRules.isWithinSchedule(overnight, utcAt("2026-07-25", 23, 0), utc))
        // Midday Friday is outside the time window entirely.
        assertFalse("Friday midday", PlaceAlertRules.isWithinSchedule(overnight, utcAt("2026-07-24", 12, 0), utc))
    }

    @Test
    fun `an inactive or expired alert is not live at the crossing time`() {
        assertFalse(PlaceAlertRules.isAlertLiveAt(alert(isActive = false), 1_000L))
        assertTrue(PlaceAlertRules.isAlertLiveAt(alert(expiresAt = 2_000L), 1_000L))
        assertFalse(PlaceAlertRules.isAlertLiveAt(alert(expiresAt = 2_000L), 2_001L))
    }

    @Test
    fun `circle-wide alert watches every member, a targeted one watches only its target`() {
        // A circle-wide alert homed to a circle watches only THAT circle's members.
        val circleWide = alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET)
            .copy(circleId = "circle-family")
        assertTrue(PlaceAlertRules.alertWatches(circleWide, "anyone", targetIsInAlertCircle = true))
        assertFalse(
            "a member of an unrelated circle must NOT be watched",
            PlaceAlertRules.alertWatches(circleWide, "coworker", targetIsInAlertCircle = false),
        )

        // An explicitly targeted alert names a PERSON; membership is not consulted.
        assertTrue(
            PlaceAlertRules.alertWatches(
                alert(targetUserId = "member-a"), "member-a", targetIsInAlertCircle = false
            )
        )
        assertFalse(
            PlaceAlertRules.alertWatches(
                alert(targetUserId = "member-a"), "member-b", targetIsInAlertCircle = true
            )
        )

        // Legacy circle-wide row with no home circle stays unscoped, so it keeps
        // firing rather than silently matching nobody.
        val legacy = alert(targetUserId = PlaceAlertRules.CIRCLE_WIDE_TARGET).copy(circleId = null)
        assertTrue(PlaceAlertRules.alertWatches(legacy, "anyone", targetIsInAlertCircle = false))

        // UNKNOWN roster (null) must not scope either. Nothing refreshes
        // circle_members in the background, so "we haven't synced members yet" would
        // otherwise silence a correctly-configured alert — and the user cannot tell
        // that apart from the feature being broken.
        assertTrue(
            "an unknown roster must not silence the alert",
            PlaceAlertRules.alertWatches(circleWide, "anyone", targetIsInAlertCircle = null),
        )
    }

    // ===== helpers =====

    private fun crossing(transition: Transition, at: Long) = PlaceAlertRules.Crossing(
        placeId = home.placeId,
        userId = "member-a",
        transition = transition,
        crossedAt = at,
    )

    private fun utcAt(isoDate: String, hour: Int, minute: Int): Long =
        java.time.LocalDate.parse(isoDate)
            .atTime(hour, minute)
            .atZone(utc)
            .toInstant()
            .toEpochMilli()

    /** Guards the assumption the schedule tests are written against. */
    @Test
    fun `test calendar assumptions hold`() {
        assertEquals(java.time.DayOfWeek.WEDNESDAY, java.time.LocalDate.parse("2026-07-22").dayOfWeek)
        assertEquals(java.time.DayOfWeek.FRIDAY, java.time.LocalDate.parse("2026-07-24").dayOfWeek)
        assertEquals(java.time.DayOfWeek.SATURDAY, java.time.LocalDate.parse("2026-07-25").dayOfWeek)
    }

    @Test
    fun `no crossings means no decisions`() {
        assertTrue(PlaceAlertRules.classify(emptyList(), finalInside = false, now = NOW).isEmpty())
    }
    // ---- exit hysteresis (parity with mobile-sdk boundary.rs) ----------------

    private fun geo(radius: Int = 150) =
        PlaceAlertRules.PlaceGeofence("p1", 37.7699, -122.4469, radius)

    /** ~1 degree of latitude is ~111.32 km. */
    private fun northOfCentre(metres: Double) = 37.7699 + metres / 111_320.0

    @Test
    fun `the exit margin matches the native rule exactly`() {
        // Mirrors exit_margin_meters in mobile-sdk boundary.rs. If these drift the
        // two detectors disagree about whether a member is inside.
        assertEquals(30.0, PlaceAlertRules.exitMarginMeters(150.0), 0.0001)
        assertEquals(20.0, PlaceAlertRules.exitMarginMeters(50.0), 0.0001)
        assertEquals(100.0, PlaceAlertRules.exitMarginMeters(5000.0), 0.0001)
    }

    @Test
    fun `a fix just outside the radius still counts as inside`() {
        // 165m is past the 150m radius but inside the 30m margin.
        assertTrue(PlaceAlertRules.isInside(geo(), northOfCentre(165.0), -122.4469, wasInside = true))
    }

    @Test
    fun `a fix beyond the margin counts as outside`() {
        assertFalse(PlaceAlertRules.isInside(geo(), northOfCentre(250.0), -122.4469, wasInside = true))
    }

    @Test
    fun `arrival still uses the true radius, not radius plus margin`() {
        // Coming from outside, the margin band must NOT count as arrived —
        // widening arrival would make people arrive early.
        assertFalse(PlaceAlertRules.isInside(geo(), northOfCentre(165.0), -122.4469, wasInside = false))
        assertTrue(PlaceAlertRules.isInside(geo(), northOfCentre(120.0), -122.4469, wasInside = false))
    }

    @Test
    fun `a wobble out and back produces no crossings at all`() {
        // One noisy fix must not cost the user a spurious departure AND a
        // spurious arrival when the next fix lands back inside.
        val g = geo()
        val prior = PlaceAlertRules.MemberPlaceState(isInside = true, lastLocationAt = 1_000L)
        val r = PlaceAlertRules.reconcile(
            geofence = g,
            userId = "u1",
            prior = prior,
            points = listOf(
                PlaceAlertRules.Point(northOfCentre(165.0), -122.4469, 2_000L),
                PlaceAlertRules.Point(37.7699, -122.4469, 3_000L),
            ),
        )
        assertTrue("a wobble produced ${r?.crossings}", r?.crossings.isNullOrEmpty())
    }

    @Test
    fun `a real departure is still reported`() {
        val g = geo()
        val prior = PlaceAlertRules.MemberPlaceState(isInside = true, lastLocationAt = 1_000L)
        val r = PlaceAlertRules.reconcile(
            geofence = g,
            userId = "u1",
            prior = prior,
            points = listOf(PlaceAlertRules.Point(northOfCentre(400.0), -122.4469, 2_000L)),
        )
        assertEquals(1, r?.crossings?.size)
        assertEquals(PlaceAlertRules.Transition.EXIT, r?.crossings?.first()?.transition)
    }

    // ===== accuracy: a fix rougher than the ring decides nothing =====

    @Test
    fun `canResolve rejects a fix whose own error exceeds the radius`() {
        val g = geo(radius = 100)
        // Battery-saver keeps fixes to 500m (LocationPolicy.MIN_ACCURACY_BALANCED).
        assertFalse(PlaceAlertRules.canResolve(g, 500.0))
        assertFalse(PlaceAlertRules.canResolve(g, 100.001))
    }

    @Test
    fun `canResolve accepts a fix at or inside the radius`() {
        val g = geo(radius = 100)
        assertTrue(PlaceAlertRules.canResolve(g, 100.0))
        assertTrue(PlaceAlertRules.canResolve(g, 12.0))
    }

    @Test
    fun `canResolve is per-place, so a coarse fix still judges a large ring`() {
        // The whole reason this is a per-place gate and not a global one: only rings
        // SMALLER than the fix's own error go dark.
        assertFalse(PlaceAlertRules.canResolve(geo(radius = 100), 500.0))
        assertTrue(PlaceAlertRules.canResolve(geo(radius = 2000), 500.0))
    }

    @Test
    fun `unknown accuracy evaluates - null is not treated as too coarse`() {
        // A sender that omits accuracy must not have its watcher's alerts silently
        // disabled. Unknown means evaluate.
        assertTrue(PlaceAlertRules.canResolve(geo(radius = 50), null))
    }

    @Test
    fun `a rough but usable fix widens the exit margin instead of departing`() {
        val g = geo(radius = 150) // geometric margin = 30m
        // 120m accuracy is usable against a 150m ring, but far rougher than 30m.
        // At 200m out we are past radius+30 but inside radius+120: no departure.
        assertTrue(
            "a 120m-accurate fix 200m out must not read as a departure",
            PlaceAlertRules.isInside(g, northOfCentre(200.0), -122.4469, wasInside = true, accuracyMeters = 120.0),
        )
        // Same fix, same place, precise: this IS a departure.
        assertFalse(
            PlaceAlertRules.isInside(g, northOfCentre(200.0), -122.4469, wasInside = true, accuracyMeters = 5.0),
        )
    }

    @Test
    fun `widening never narrows - a precise fix behaves exactly as one with no accuracy`() {
        val g = geo(radius = 150)
        for (d in listOf(10.0, 149.0, 175.0, 179.0, 181.0, 400.0)) {
            assertEquals(
                "accuracy 0 must not move the boundary at ${d}m",
                PlaceAlertRules.isInside(g, northOfCentre(d), -122.4469, wasInside = true),
                PlaceAlertRules.isInside(g, northOfCentre(d), -122.4469, wasInside = true, accuracyMeters = 0.0),
            )
        }
    }

    @Test
    fun `widening does not move ARRIVAL - you still arrive at the true radius`() {
        // Deliberately asymmetric: widening arrival would make people arrive early,
        // a worse failure than flapping.
        val g = geo(radius = 150)
        assertFalse(
            "a 140m-accurate fix 200m out must not read as an arrival",
            PlaceAlertRules.isInside(g, northOfCentre(200.0), -122.4469, wasInside = false, accuracyMeters = 140.0),
        )
    }

    @Test
    fun `reconcile carries a point's accuracy into the margin`() {
        // End-to-end through the reconciler, not just isInside: proves Point.accuracyMeters
        // actually reaches the geometry rather than being dropped on the way.
        val g = geo(radius = 150)
        val prior = PlaceAlertRules.MemberPlaceState(isInside = true, lastLocationAt = 1_000L)
        val rough = PlaceAlertRules.reconcile(
            geofence = g,
            userId = "u1",
            prior = prior,
            points = listOf(Point(northOfCentre(200.0), -122.4469, 2_000L, accuracyMeters = 120.0)),
        )
        assertTrue("a rough fix must not manufacture a departure", rough?.crossings.isNullOrEmpty())

        val precise = PlaceAlertRules.reconcile(
            geofence = g,
            userId = "u1",
            prior = prior,
            points = listOf(Point(northOfCentre(200.0), -122.4469, 2_000L, accuracyMeters = 5.0)),
        )
        assertEquals(1, precise?.crossings?.size)
        assertEquals(Transition.EXIT, precise?.crossings?.first()?.transition)
    }


    // ===== Pairing a server push with the local copy of the same crossing =====

    private fun shown(
        id: String,
        kind: String,
        transition: String?,
        eventAt: Long?,
        shownAt: Long = 1_000_000L,
    ) = PlaceAlertShownEntity(id, kind, "place", "member", transition, eventAt, shownAt, consumed = false)

    private val server = PlaceAlertShownEntity.KIND_SERVER
    private val local = PlaceAlertShownEntity.KIND_LOCAL

    @Test
    fun `a local crossing replaces the push of its own direction and event time, the closest`() {
        val t = 50_000_000L
        val pushes = listOf(
            shown("far", server, "enter", t - PlaceAlertRules.CROSSING_EVENT_MATCH_MS - 1),
            shown("near", server, "enter", t + 30_000L),
            shown("nearer", server, "enter", t - 10_000L),
            shown("other-way", server, "exit", t),
            shown("a-trace", local, "enter", t),
        )
        assertEquals("nearer", PlaceAlertRules.serverPushToReplace(pushes, "enter", t)?.notificationId)
        assertNull(PlaceAlertRules.serverPushToReplace(listOf(pushes[0], pushes[3], pushes[4]), "enter", t))
    }

    @Test
    fun `a push that couldn't read its direction pairs with either, by its time`() {
        val t = 50_000_000L
        assertEquals("p", PlaceAlertRules.serverPushToReplace(listOf(shown("p", server, null, t)), "exit", t)?.notificationId)
    }

    @Test
    fun `a push with no event time reaches back the staleness window and forward only by skew`() {
        val shownAt = 50_000_000L
        val push = listOf(shown("p", server, "enter", eventAt = null, shownAt = shownAt))
        assertEquals("p", PlaceAlertRules.serverPushToReplace(push, "enter", shownAt - PlaceAlertRules.STALENESS_WINDOW_MS)?.notificationId)
        assertNull(PlaceAlertRules.serverPushToReplace(push, "enter", shownAt - PlaceAlertRules.STALENESS_WINDOW_MS - 1))
        assertEquals("p", PlaceAlertRules.serverPushToReplace(push, "enter", shownAt + PlaceAlertRules.SERVER_PUSH_SUPERSEDE_SKEW_MS)?.notificationId)
        assertNull(PlaceAlertRules.serverPushToReplace(push, "enter", shownAt + PlaceAlertRules.SERVER_PUSH_SUPERSEDE_SKEW_MS + 1))
    }

    @Test
    fun `a push repeats a local copy only of its own direction and time, never on a guess`() {
        val t = 50_000_000L
        val copies = listOf(
            shown("enter-copy", local, "enter", t),
            shown("exit-copy", local, "exit", t),
            shown("a-push", server, "enter", t),
        )
        assertEquals("enter-copy", PlaceAlertRules.localCopyRepeated(copies, "enter", t + 20_000L)?.notificationId)
        assertNull("no direction: never a guess", PlaceAlertRules.localCopyRepeated(copies, null, t))
        assertNull("no time: never a guess", PlaceAlertRules.localCopyRepeated(copies, "enter", null))
        assertNull(PlaceAlertRules.localCopyRepeated(copies, "enter", t + PlaceAlertRules.CROSSING_EVENT_MATCH_MS + 1))
    }
}
