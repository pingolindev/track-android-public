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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure decision logic for SUBSCRIBER-SIDE place alerts.
 *
 * The subscriber's device already receives and decrypts every circle member's
 * location — that is how it draws them on the map — so it can evaluate those
 * locations against its own private, never-shared place entirely locally. No
 * crossing is routed through the blind server, no circle-key hash is computed,
 * no `place_shares` row and no wrapped place key are needed.
 *
 * Everything here is deliberately free of Android and Room dependencies so the
 * four correctness rules can be tested directly:
 *
 *  1. Stateful, per member    -> [reconcile] carries [MemberPlaceState] (in/out
 *     state + the timestamp of the last location already accounted for).
 *  2. Reconcile in time order -> [reconcile] sorts oldest-to-newest and only
 *     [classify] decides which transitions are recent enough to notify; older
 *     ones come back marked [Decision.silent].
 *  3. Stamp with the real time-> every [Crossing] carries `crossedAt`, the
 *     member location's OWN timestamp, and the copy builders render from it.
 *  4. De-duplicate hard       -> [Crossing.ledgerId] is a deterministic identity
 *     for a crossing, stable across process restarts, so the persistence layer
 *     can claim it exactly once ever.
 */
object PlaceAlertRules {

    /** Target sentinel for a circle-wide alert (matches PlacesViewModel). */
    const val CIRCLE_WIDE_TARGET = "all"

    /**
     * How far back a missed crossing may be and still raise a notification.
     *
     * Product tuning knob. Two hours is chosen because it
     * comfortably covers the outages that actually produce catch-up batches — a
     * commute through poor coverage, a phone asleep in a bag, a brief
     * connectivity drop — while staying short enough that a crossing surfaced as
     * "2h ago" still reads as something worth knowing rather than archaeology.
     * Anything older folds silently into history (a ledger row + a bell entry)
     * instead of a push, so a phone that was off all weekend does not reconnect
     * and dump a pile of ancient pings.
     */
    const val STALENESS_WINDOW_MS: Long = 2 * 60 * 60 * 1000L

    /**
     * Lag below which a crossing is presented with no "ago" qualifier at all.
     * Above it the copy carries the real elapsed time, so a delayed alert never
     * claims to be live.
     */
    const val LIVE_LAG_TOLERANCE_MS: Long = 3 * 60 * 1000L

    /**
     * How far apart the two paths' views of the SAME crossing may be and still be
     * treated as one event. The server-routed push carries no crossing timestamp
     * (the server's notification metadata is place_id / target_user_id /
     * event_type_hash only), so cross-path de-duplication has to match on
     * (place, member, transition) within a window rather than on an exact id.
     *
     * Sized for PUSH LATENCY and nothing else — the two paths see the same
     * crossing seconds apart, not minutes. It must stay well under the shortest
     * realistic gap between two genuine crossings of the same place, because
     * anything inside the window that matches the other path is treated as the
     * same event: a window of many minutes would silently swallow a real arrival
     * whenever someone left and came back within it — moving the car, walking
     * the dog, a school run. See countSurfacedSince: the source filter is the
     * primary defence, this window is the secondary one.
     */
    const val CROSS_PATH_DEDUPE_WINDOW_MS: Long = 90 * 1000L

    /**
     * How far BEFORE a server push was shown the crossing it reported may have happened, for a push that
     * carried no event time ([serverPushToReplace]); one that did is paired by that time instead
     * ([CROSSING_EVENT_MATCH_MS]).
     *
     * As far back as a crossing may wait for a local pass and still raise a notification
     * ([STALENESS_WINDOW_MS]): the local copy can come that late, and a shorter window would let it stack
     * beside the generic push. Widening it is safe in a way widening [CROSS_PATH_DEDUPE_WINDOW_MS] is NOT.
     * That one SUPPRESSES: a too-wide value swallows a real arrival by mistaking it for a duplicate (the
     * leave-and-come-back case). This one only re-uses a notification ID, single use, and only ever looks
     * BACKWARD — see [SERVER_PUSH_SUPERSEDE_SKEW_MS]. Nothing is ever silenced by it.
     */
    const val SERVER_PUSH_SUPERSEDE_WINDOW_MS: Long = STALENESS_WINDOW_MS

    /**
     * How far AFTER a generic push was shown a crossing may have happened and still
     * be treated as the one that push reported. Clock skew only — the mover's device
     * stamps `crossedAt` and ours stamps the marker.
     *
     * This bound is the safety property of the whole supersede mechanism, and it is
     * why [SERVER_PUSH_SUPERSEDE_WINDOW_MS] can be generous. The server can only push
     * about a crossing that has ALREADY happened, so a crossing materially later than
     * the push is a DIFFERENT crossing. Without this bound, an arrival push the local
     * evaluator missed could be superseded by a departure ten minutes later: the
     * departure would take the arrival's notification id, replace it in the shade,
     * and the user would never learn they arrived. Small on purpose — a skewed clock
     * costs one stacked duplicate; a wide forward window costs a swallowed arrival.
     */
    const val SERVER_PUSH_SUPERSEDE_SKEW_MS: Long = 90 * 1000L

    /**
     * How far apart a local crossing's time and a server push's event time may be and still be the one
     * crossing. The push's time is the reporter's sealed event time where there is one, the same instant
     * the mover's own points show, else when its report reached the server; the points trail the report by
     * up to one poll while moving (20 s), and the two clocks may differ by [SERVER_PUSH_SUPERSEDE_SKEW_MS].
     * Tight on purpose: wider, a push whose crossing never reached this device could pair with the next
     * crossing of the same kind.
     */
    const val CROSSING_EVENT_MATCH_MS: Long = 3 * 60 * 1000L

    /**
     * How long a place-alert notification in the shade may still pair with the other path's copy: as
     * long as a crossing may wait for a local pass and still raise one ([STALENESS_WINDOW_MS]), and slack.
     */
    const val SHOWN_PAIRING_MS: Long = STALENESS_WINDOW_MS + 30 * 60 * 1000L

    /**
     * The server push a local crossing's named copy replaces in place, from [unpaired] (shown, not paired
     * yet, this member at this place), or null to post under its own id.
     *
     * A push of the same direction, or one that couldn't read its own. Paired by its event time where it
     * carried one; else by when it was shown: back by [SERVER_PUSH_SUPERSEDE_WINDOW_MS], forward by clock
     * skew only, since a push can only be about a crossing that has already happened. The closest wins.
     * The caller consumes it, so it replaces exactly one.
     */
    fun serverPushToReplace(
        unpaired: List<PlaceAlertShownEntity>,
        transition: String,
        crossedAt: Long,
    ): PlaceAlertShownEntity? = unpaired
        .filter { it.kind == PlaceAlertShownEntity.KIND_SERVER && (it.transition == null || it.transition == transition) }
        .filter { push ->
            val at = push.eventAt
            if (at != null) {
                kotlin.math.abs(crossedAt - at) <= CROSSING_EVENT_MATCH_MS
            } else {
                crossedAt >= push.shownAt - SERVER_PUSH_SUPERSEDE_WINDOW_MS &&
                    crossedAt <= push.shownAt + SERVER_PUSH_SUPERSEDE_SKEW_MS
            }
        }
        .minByOrNull { kotlin.math.abs(crossedAt - (it.eventAt ?: it.shownAt)) }

    /**
     * The local copy, from [unpaired], that a server push about [transition] at [eventAt] would only repeat,
     * or null to post the push. Never on a guess: no direction or no event time, null. A push paired with
     * the wrong crossing isn't shown, and if that crossing then never surfaces here it's never shown at
     * all; a push shown beside the local copy is one notification too many, which is the better failure.
     */
    fun localCopyRepeated(
        unpaired: List<PlaceAlertShownEntity>,
        transition: String?,
        eventAt: Long?,
    ): PlaceAlertShownEntity? {
        if (transition == null || eventAt == null) return null
        return unpaired
            .filter { it.kind == PlaceAlertShownEntity.KIND_LOCAL && it.transition == transition }
            .filter { local -> local.eventAt?.let { kotlin.math.abs(it - eventAt) <= CROSSING_EVENT_MATCH_MS } == true }
            .minByOrNull { kotlin.math.abs(it.eventAt!! - eventAt) }
    }

    /** Mean Earth radius, matching the native BoundaryChecker (boundary.rs). */
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** A place's geofence as the local evaluator sees it. */
    data class PlaceGeofence(
        val placeId: String,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Int,
    )

    enum class Transition {
        ENTER,
        EXIT;

        /** Wire/ledger form, matching BoundaryRepository.EVENT_TYPE_ENTER / _EXIT. */
        val wire: String get() = if (this == ENTER) "enter" else "exit"
    }

    /** One detected in/out transition, stamped with when it ACTUALLY happened. */
    data class Crossing(
        val placeId: String,
        val userId: String,
        val transition: Transition,
        /** The member location's own timestamp — never "now". */
        val crossedAt: Long,
    ) {
        /**
         * Stable identity for this crossing. Keyed on (place, member, transition,
         * crossing time) — never on a row id — so the same crossing re-derived
         * after a restart, or from an overlapping batch, collapses onto the same
         * ledger row and can be surfaced at most once, ever.
         */
        val ledgerId: String get() = "$placeId:$userId:${transition.wire}:$crossedAt"
    }

    /**
     * Per-(place, member) reconciliation state. [lastLocationAt] is the watermark:
     * the timestamp of the newest location already folded into [isInside].
     */
    data class MemberPlaceState(
        val isInside: Boolean,
        val lastLocationAt: Long,
    )

    data class Reconciliation(
        val state: MemberPlaceState,
        /** Transitions detected in this batch, oldest to newest. */
        val crossings: List<Crossing>,
    )

    /** A single point handed to the evaluator (a decrypted member location). */
    data class Point(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        /**
         * Reported horizontal accuracy in metres, or null when the sender did not
         * say. See [canResolve] for why it is read and what null means.
         *
         * NULL IS "UNKNOWN", NOT "PERFECT" — and it must stay that way. A sender
         * that omits the field (an older client, or one whose platform gave no
         * accuracy) would otherwise have its watcher's alerts silently disabled,
         * which is the same silent-loss failure this gate exists to avoid, moved
         * somewhere new. Unknown means EVALUATE.
         */
        val accuracyMeters: Double? = null,
    )

    /** How a crossing (or a collapsed pair) should be presented. */
    sealed interface Surfacing {
        /** Every crossing this surfacing accounts for — all must be claimed in the ledger. */
        val crossings: List<Crossing>

        /** The crossing time the copy is stamped with / staleness is judged on. */
        val at: Long

        /** Arrived; [stillInside] means their latest known position is in the zone. */
        data class Arrival(val crossing: Crossing, val stillInside: Boolean) : Surfacing {
            override val crossings get() = listOf(crossing)
            override val at get() = crossing.crossedAt
        }

        /** Left the place. */
        data class Departure(val crossing: Crossing) : Surfacing {
            override val crossings get() = listOf(crossing)
            override val at get() = crossing.crossedAt
        }

        /**
         * A complete pass-through the subscriber missed: enter AND exit landed in
         * the same catch-up batch. Collapsed into ONE past-framed entry rather
         * than two pings.
         */
        data class PassThrough(val enter: Crossing, val exit: Crossing) : Surfacing {
            override val crossings get() = listOf(enter, exit)
            override val at get() = exit.crossedAt
        }
    }

    /**
     * A surfacing plus whether it may raise a notification.
     *
     * [silent] transitions still get a ledger row and a history entry — they are
     * "accounted for", just not pushed. That is what stops a reconnect flood
     * while keeping the at-most-once guarantee for the same crossing.
     */
    data class Decision(
        val surfacing: Surfacing,
        val silent: Boolean,
    )

    /**
     * Great-circle distance in metres. Same formula and Earth radius as the
     * native BoundaryChecker so the local evaluator and the legacy mover-side
     * detector can never disagree about whether a point is in the zone.
     */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(deltaLat / 2).let { it * it } +
            Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.sin(deltaLng / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2.0 * Math.asin(Math.sqrt(a))
    }

    /**
     * Extra distance beyond the radius a member must travel before an EXIT is
     * declared. 20% of the radius, floored at 20 m so small geofences still get a
     * usable buffer, capped at 100 m so a large one doesn't swallow genuine
     * departures.
     *
     * MUST match `exit_margin_meters` in mobile-sdk boundary.rs.
     */
    private const val EXIT_MARGIN_FRACTION = 0.20
    private const val EXIT_MARGIN_MIN_M = 20.0
    private const val EXIT_MARGIN_MAX_M = 100.0

    fun exitMarginMeters(radiusMeters: Double): Double =
        (radiusMeters * EXIT_MARGIN_FRACTION).coerceIn(EXIT_MARGIN_MIN_M, EXIT_MARGIN_MAX_M)

    /**
     * Whether a fix of this accuracy can say anything at all about this geofence.
     *
     * A fix whose own error exceeds the radius carries NO information about the
     * ring: the true position is somewhere in a circle larger than the feature
     * being tested, so "inside" and "outside" are both consistent with the
     * measurement. Any alert fired from it is a guess, and a guess is not worth a
     * banner — it is worth saying nothing.
     *
     * This is deliberately a SKIP and not a widened margin. Widening only ever
     * moves the EXIT threshold ([isInside] keeps arrival at the true radius on
     * purpose), so on its own it would stop spurious departures while leaving
     * spurious ARRIVALS untouched — and then hold them, because the same widened
     * margin prevents the exit that would clear them. That converts flapping into
     * one false arrival that sticks, which is worse than what it replaces.
     *
     * Per-place, not global: the same 500 m fix is useless against a 100 m ring
     * and perfectly usable against a 2 km one, so only rings smaller than the
     * fix's own error go dark.
     *
     * Null accuracy means UNKNOWN and evaluates — see [Point.accuracyMeters].
     *
     * WHY THIS IS NOT THEORETICAL. The coarse fixes it screens come from
     * `LocationHeartbeatWorker`'s `LocationAccuracy.BALANCED` request, which on the
     * libre build resolves to `NETWORK_PROVIDER` first — wifi/cell positioning,
     * routinely hundreds of metres — while place-alert rings start at 100 m
     * (`dialog_create_place_alert.xml` `valueFrom="100"`). On gms it is
     * `PRIORITY_BALANCED_POWER_ACCURACY`, nominally ~100 m, sitting exactly on that
     * floor. Battery-saver is NOT the source; the tracking service asks for HIGH in
     * both modes.
     *
     * TESTING NOTE: an Android emulator cannot exercise this. It reports a CONSTANT
     * synthetic accuracy — `hAcc=5.0` for an injected `geo fix`, 100.0 for the
     * fused fallback — and `geo fix` has no accuracy parameter, so the value
     * cannot be varied and cannot differ by request priority: there are no real
     * position sources for a priority to select between. Crafting `geo nmea` HDOP
     * values would only measure numbers chosen in advance. Measuring the real
     * distribution takes a real phone with network location enabled, moving
     * indoors to outdoors, on a debuggable build.
     */
    fun canResolve(geofence: PlaceGeofence, accuracyMeters: Double?): Boolean =
        accuracyMeters == null || accuracyMeters <= geofence.radiusMeters.toDouble()

    /**
     * Whether a point counts as inside the geofence, given what we believed a
     * moment ago.
     *
     * ASYMMETRIC on purpose. You ARRIVE at the true radius, but you must get
     * clearly clear of it before we call it a departure. A bare
     * `distance <= radius` flips on a single noisy fix, and because this feeds a
     * state machine that costs the user BOTH a spurious departure and a spurious
     * arrival when the next fix lands back inside — two false alerts from one
     * wobble. Ordinary GPS error at the edge of a 150 m geofence is ample.
     *
     * Arrival deliberately keeps the true radius: widening it too would make
     * people arrive early, which is a worse failure than flapping.
     *
     * PARITY: must stay identical to `check_location` in mobile-sdk boundary.rs.
     * The two detectors judge the same fixes — the mover for its own crossings,
     * the subscriber for every other member's — so a margin on one side only
     * makes them disagree about whether someone is inside. Treat "both detectors
     * do X" as something to VERIFY against the Rust, never something to assert
     * from here: a parity claim that does not hold (one side defaulting an
     * unknown boundary to "outside" while the other primes) means a phantom
     * arrival on every restart inside a geofence.
     *
     * [wasInside] is what we believed before this fix; null means no prior state,
     * in which case the plain radius applies.
     */
    fun isInside(
        geofence: PlaceGeofence,
        latitude: Double,
        longitude: Double,
        wasInside: Boolean?,
        /**
         * Reported accuracy of THIS fix, widening the exit margin when the fix is
         * rougher than the margin already allows for. A fix precise enough to be
         * evaluated at all ([canResolve]) can still be rougher than a 20 m floor,
         * and a departure declared on noise costs the user two false alerts.
         *
         * Only ever WIDENS: `max` with the geometric margin, so a precise fix
         * behaves exactly as with the geometric margin alone.
         */
        accuracyMeters: Double? = null,
    ): Boolean {
        val distance = distanceMeters(latitude, longitude, geofence.latitude, geofence.longitude)
        val radius = geofence.radiusMeters.toDouble()
        return if (wasInside == true) {
            val margin = maxOf(exitMarginMeters(radius), accuracyMeters ?: 0.0)
            distance <= radius + margin
        } else {
            distance <= radius
        }
    }

    /**
     * Fold a batch of a single member's locations into their state for one place,
     * walking OLDEST to NEWEST.
     *
     * - Points at or before the watermark ([MemberPlaceState.lastLocationAt]) are
     *   already accounted for and are skipped, so an overlapping batch or a
     *   replayed page can never re-drive a transition.
     * - With no prior state the first point SEEDS the state and emits no crossing:
     *   a first-ever fix is not a transition. The SDK's BoundaryChecker primes on
     *   first observation exactly as here; keep the two in step. Defaulting
     *   `was_inside` to false would make a cold start while already inside a
     *   geofence report a phantom ARRIVAL to the server, with a notification
     *   attached — a device that restarts inside a place must not be told it
     *   just got there.
     *
     * Returns null when the batch contributes nothing (all points already accounted
     * for), so the caller can skip the write entirely.
     */
    fun reconcile(
        geofence: PlaceGeofence,
        userId: String,
        prior: MemberPlaceState?,
        points: List<Point>,
    ): Reconciliation? {
        val fresh = points
            .filter { prior == null || it.timestamp > prior.lastLocationAt }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return null

        var state = prior
        val crossings = mutableListOf<Crossing>()

        for (point in fresh) {
            val inside = isInside(
                geofence, point.latitude, point.longitude, state?.isInside, point.accuracyMeters,
            )
            val previous = state
            if (previous != null && inside != previous.isInside) {
                crossings += Crossing(
                    placeId = geofence.placeId,
                    userId = userId,
                    transition = if (inside) Transition.ENTER else Transition.EXIT,
                    crossedAt = point.timestamp,
                )
            }
            state = MemberPlaceState(isInside = inside, lastLocationAt = point.timestamp)
        }

        return Reconciliation(state = state!!, crossings = crossings)
    }

    /**
     * Turn the batch's transitions into presentation decisions.
     *
     * Transitions alternate by construction, so a consecutive (ENTER, EXIT) pair
     * is a complete pass-through and collapses into a single past-framed entry.
     * A trailing unpaired ENTER means they are still there. A leading EXIT means
     * they were already inside when the batch started.
     *
     * Staleness is judged on the MOST RECENT crossing the decision covers — a
     * visit that started three hours ago but ended five minutes ago is a recent
     * miss and still worth surfacing.
     */
    fun classify(
        crossings: List<Crossing>,
        finalInside: Boolean,
        now: Long,
        stalenessWindowMs: Long = STALENESS_WINDOW_MS,
    ): List<Decision> {
        val ordered = crossings.sortedBy { it.crossedAt }
        val decisions = mutableListOf<Decision>()
        var i = 0
        while (i < ordered.size) {
            val current = ordered[i]
            val next = ordered.getOrNull(i + 1)
            val surfacing = if (current.transition == Transition.ENTER &&
                next != null && next.transition == Transition.EXIT
            ) {
                i += 2
                Surfacing.PassThrough(enter = current, exit = next)
            } else {
                i += 1
                when (current.transition) {
                    Transition.ENTER -> Surfacing.Arrival(current, stillInside = finalInside)
                    Transition.EXIT -> Surfacing.Departure(current)
                }
            }
            decisions += Decision(
                surfacing = surfacing,
                silent = now - surfacing.at > stalenessWindowMs,
            )
        }
        return decisions
    }

    /** Whether [alert] is a live subscription at the moment the crossing happened. */
    fun isAlertLiveAt(alert: PlaceAlertEntity, atMillis: Long): Boolean =
        alert.isActive && (alert.expiresAt == null || atMillis <= alert.expiresAt)

    /**
     * Whether [alert] watches [userId] — an explicit target, or circle-wide.
     *
     * A CIRCLE-WIDE alert means "everyone in THIS circle", so it must be scoped to
     * the circle it is homed to. Without that scope it would match every member
     * whose location the device happens to hold: an alert set for your family
     * circle would also fire when a coworker from an unrelated work circle reached
     * your home. Not a data leak — those locations are already decrypted here — but
     * the wrong set of people, and surprising. The server requires and stores that
     * circle precisely so this bound exists; ignoring it client-side would leave
     * the bound enforced on one side only.
     *
     * @param targetIsInAlertCircle whether [userId] is a member of [alert]'s own
     *   circle, or NULL when the roster is UNKNOWN locally. Deliberately a required
     *   parameter with no default: membership needs a DAO lookup the caller must do,
     *   and a default would let a new call site silently reintroduce the over-match.
     *
     *   The null case is load-bearing. Nothing refreshes `circle_members` in the
     *   background — every caller of refreshMembers is a UI ViewModel — so a device
     *   that hasn't opened the right screen holds no roster at all. Treating that as
     *   "not a member" would make a correctly-configured alert silently never fire,
     *   which is indistinguishable from the feature being broken. Absence of
     *   knowledge is not knowledge of absence.
     */
    fun alertWatches(
        alert: PlaceAlertEntity,
        userId: String,
        targetIsInAlertCircle: Boolean?,
        /**
         * `allow_alerts_on_me` as carried by the member's OWN latest fix, for the
         * circle that fix arrived on (`LocationEntity.allowAlertsOnMe`).
         *
         * The never-stale half of the consent check. [PlaceAlertEntity.suppressedByTarget]
         * is authoritative about the ANCHOR circle but only as fresh as the last
         * alert-list sync; this one rides on the location itself, so it closes the
         * window between the target flipping the switch and this device re-fetching
         * its alerts.
         *
         * `false` withholds consent. `true` and `null` both permit — a member with
         * no privacy row at all is the DEFAULT state, so reading absence as denial
         * would silently disable alerts across the estate, and a silently disabled
         * alert is indistinguishable from a broken feature.
         */
        targetAllowsAlertsOnLatestFix: Boolean? = null,
    ): Boolean = when {
        // CONSENT FIRST, and it beats every targeting rule below: a circle-wide
        // alert names nobody, so a check wired into the targeted branch alone would
        // let it sweep up someone who has opted out.
        alert.suppressedByTarget == true -> false
        targetAllowsAlertsOnLatestFix == false -> false
        // An explicitly targeted alert names a PERSON. Which circle's feed carried
        // the fix is irrelevant — it is the same human at the same place — and the
        // backend already verified that target shares the alert's circle.
        alert.targetUserId != CIRCLE_WIDE_TARGET -> alert.targetUserId == userId
        // Circle-wide with no home circle: only reachable for rows created before
        // the backend required one. Left UNSCOPED on purpose — silently matching
        // nobody would turn a legacy alert into one that never fires, which is the
        // exact failure this whole model exists to eliminate. New alerts always
        // carry a circle, so this self-heals as rows refresh.
        alert.circleId.isNullOrBlank() -> true
        // Roster unknown → do NOT scope. Scoping on a roster we don't have would
        // convert "we haven't synced members yet" into "this alert matches nobody",
        // and the user cannot tell that apart from the feature not working.
        targetIsInAlertCircle == null -> true
        else -> targetIsInAlertCircle
    }

    /** Whether [alert] opted into the direction this surfacing represents. */
    fun alertWantsSurfacing(alert: PlaceAlertEntity, surfacing: Surfacing): Boolean =
        when (surfacing) {
            is Surfacing.Arrival -> alert.alertOnArrival
            is Surfacing.Departure -> alert.alertOnDeparture
            // A pass-through IS an arrival that has since ended, so an
            // arrival-only alert still wants it; a departure-only alert wants it
            // for the exit half. Either way it stays ONE entry, never two.
            is Surfacing.PassThrough -> alert.alertOnArrival || alert.alertOnDeparture
        }

    /**
     * The IANA zone id this device judges schedules in — the same
     * `ZoneId.systemDefault()` [isWithinSchedule] defaults to, named here so a
     * subscription can TELL the server which zone its window was written in.
     *
     * The server's subscription timezone defaults to 'UTC', so without it the
     * server-side worker would read "08:00-17:00" as a UTC window while this
     * evaluator reads the identical row locally: for a subscriber in
     * America/Los_Angeles the server-routed push would arrive between 00:00 and
     * 09:00 local, and [activeDays] would disagree by a day either side of
     * midnight. One alert behaving two ways depending on which detector saw the
     * crossing is indistinguishable from flakiness.
     *
     * Kept beside [isWithinSchedule] on purpose: the zone we send and the zone we
     * evaluate in are the same decision, and they must not drift apart.
     */
    fun deviceTimezoneId(): String = ZoneId.systemDefault().id

    /**
     * Whether the crossing falls inside the alert's active schedule, evaluated at
     * the REAL crossing time rather than "now" — a catch-up batch must be judged
     * against the window that was in force when the member actually crossed.
     *
     * An overnight window (e.g. 22:00-06:00) belongs to the day it STARTS on, so a
     * 02:00 crossing is matched against the previous day's entry in [activeDays].
     */
    fun isWithinSchedule(
        alert: PlaceAlertEntity,
        atMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val days = alert.activeDays
            ?.split(",")
            ?.mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }
            ?.toSet()
            ?: emptySet()
        val start = parseTime(alert.activeStartTime)
        val end = parseTime(alert.activeEndTime)
        if (days.isEmpty() && start == null && end == null) return true

        val local = Instant.ofEpochMilli(atMillis).atZone(zone)
        val time = local.toLocalTime()

        // start > end also means overnight even if the flag was never set.
        val overnight = start != null && end != null && (alert.isOvernight || start > end)

        val inWindow = when {
            start == null && end == null -> true
            start != null && end == null -> !time.isBefore(start)
            start == null && end != null -> !time.isAfter(end)
            overnight -> !time.isBefore(start!!) || !time.isAfter(end!!)
            else -> !time.isBefore(start!!) && !time.isAfter(end!!)
        }
        if (!inWindow) return false
        if (days.isEmpty()) return true

        // For an overnight window, the small hours belong to the PREVIOUS day's slot.
        val scheduleDay = if (overnight && end != null && !time.isAfter(end)) {
            local.dayOfWeek.minus(1)
        } else {
            local.dayOfWeek
        }
        return dayName(scheduleDay) in days
    }

    private fun parseTime(raw: String?): LocalTime? {
        val parts = raw?.trim()?.split(":") ?: return null
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun dayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "monday"
        DayOfWeek.TUESDAY -> "tuesday"
        DayOfWeek.WEDNESDAY -> "wednesday"
        DayOfWeek.THURSDAY -> "thursday"
        DayOfWeek.FRIDAY -> "friday"
        DayOfWeek.SATURDAY -> "saturday"
        DayOfWeek.SUNDAY -> "sunday"
    }

    /** "5 minutes ago" / "2 hours ago" — correctly singularised. */
    fun agoLabel(elapsedMs: Long): String {
        val minutes = (elapsedMs / 60_000L).coerceAtLeast(1L)
        val (n, unit) = when {
            minutes < 60L -> minutes to "minute"
            minutes < 1440L -> (minutes / 60L) to "hour"
            else -> (minutes / 1440L) to "day"
        }
        return "$n $unit${if (n == 1L) "" else "s"} ago"
    }

    /**
     * Notification body for a surfacing. Past crossings always carry their real
     * elapsed time or clock range and never live-sounding language; a
     * pass-through renders as a single "was at X" span, not two pings.
     */
    fun describe(
        surfacing: Surfacing,
        memberName: String,
        placeName: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        /** True when the subject is the reader themselves ("You"), which takes a
         *  second-person verb — "you ARE still there", not "you IS still there". */
        isSelf: Boolean = false,
    ): String = when (surfacing) {
        is Surfacing.Arrival -> {
            val lag = now - surfacing.at
            when {
                lag <= LIVE_LAG_TOLERANCE_MS -> "$memberName arrived at $placeName"
                surfacing.stillInside -> {
                    val verb = if (isSelf) "are" else "is"
                    "$memberName arrived at $placeName ${agoLabel(lag)} and $verb still there"
                }
                else -> "$memberName arrived at $placeName ${agoLabel(lag)}"
            }
        }
        is Surfacing.Departure -> {
            val lag = now - surfacing.at
            if (lag <= LIVE_LAG_TOLERANCE_MS) {
                "$memberName left $placeName"
            } else {
                "$memberName left $placeName ${agoLabel(lag)}"
            }
        }
        is Surfacing.PassThrough ->
            "$memberName was at $placeName, " +
                "${clockLabel(surfacing.enter.crossedAt, zone)}-${clockLabel(surfacing.exit.crossedAt, zone)}"
    }

    /** Notification title for a surfacing. */
    fun titleFor(surfacing: Surfacing): String = when (surfacing) {
        is Surfacing.Arrival -> "Arrival"
        is Surfacing.Departure -> "Departure"
        is Surfacing.PassThrough -> "Visit"
    }

    private fun clockLabel(millis: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        return String.format("%02d:%02d", t.hour, t.minute)
    }
}
