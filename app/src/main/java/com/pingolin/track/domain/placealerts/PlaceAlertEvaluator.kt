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

import android.util.Log
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.dao.NotificationDao
import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceAlertEventDao
import com.pingolin.track.data.local.dao.PlaceAlertMemberStateDao
import com.pingolin.track.data.local.dao.PlaceAlertShownDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.LocationEntity
import com.pingolin.track.data.local.entity.NotificationEntity
import com.pingolin.track.data.local.entity.PlaceAlertEntity
import com.pingolin.track.data.local.entity.PlaceAlertEventEntity
import com.pingolin.track.data.local.entity.PlaceAlertMemberStateEntity
import com.pingolin.track.data.local.entity.PlaceAlertShownEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.AlertHandler
import com.pingolin.track.util.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscriber-side place-alert evaluation.
 *
 * The detector runs on the device that OWNS the alert, not on the mover's. That
 * device already receives and decrypts every circle member's location, so it can
 * evaluate them against its own private place with pure local geometry. Nothing
 * new is computed on or revealed to the server: no crossing is reported, no
 * circle-key hash is derived, no `place_shares` row and no wrapped place key are
 * required. A personal place therefore never has to be shared — and so never has
 * its name and coordinates made decryptable by every circle member — just to run
 * an alert on it.
 *
 * Fed from the single persist-and-evaluate choke point in LocationRepository, so
 * every decrypted location this device stores is evaluated exactly once, whether
 * it arrived from the live poll, a history page, or this device's own tracking.
 */
@Singleton
class PlaceAlertEvaluator @Inject constructor(
    private val placeDao: PlaceDao,
    private val placeAlertDao: PlaceAlertDao,
    private val stateDao: PlaceAlertMemberStateDao,
    private val eventDao: PlaceAlertEventDao,
    // The place-alert notifications in the shade, for the other path of a crossing to pair with.
    private val shownDao: PlaceAlertShownDao,
    private val notificationDao: NotificationDao,
    private val circleMemberDao: CircleMemberDao,
    private val sessionManager: SessionManager,
    // Re-derives an alert's own match hashes so a server push can be read back to a
    // direction — see [resolveServerPushCopy]. Nothing is encrypted or decrypted for
    // the evaluator itself; every location it judges arrives already decrypted.
    private val eventTypeHasher: EventTypeHasher,
    // Lazy: AlertHandler -> LocationRepository -> PlaceAlertEvaluator -> AlertHandler
    // is a DI cycle, and this is the edge that breaks it. Matches the existing
    // dagger.Lazy convention in LocationRepository.
    private val alertHandler: dagger.Lazy<AlertHandler>,
    // Tells the user when their own fixes got too coarse to judge a place by, so
    // that going dark is something they are told about rather than something they
    // discover. See [noteCoarseFixes].
    private val uxEventBus: com.pingolin.track.util.UxEventBus,
    // Lazy: CircleRepository -> LocationRepository -> PlaceAlertEvaluator. Only to fetch an alert circle's
    // roster again when a member's name isn't here yet ([displayNameFor]).
    private val circleRepository: dagger.Lazy<com.pingolin.track.domain.repository.CircleRepository>,
) {
    companion object {
        private const val TAG = "PlaceAlertEvaluator"

        /**
         * At most this often per circle, a name this phone hasn't opened yet fetches that circle's roster
         * again ([displayNameFor]): a member whose name never opens here costs one fetch per window, not one
         * per crossing.
         */
        internal const val ROSTER_REFETCH_MIN_MS: Long = 5 * 60 * 1000L

        /** Ledger source for a crossing this device detected locally. */
        const val SOURCE_LOCAL = "local"

        /** Ledger source for a crossing that arrived as a server-routed push. */
        const val SOURCE_SERVER = "server"

        /** Bell-row id prefix. NotificationRepository skips the (404-ing) server
         *  round-trip for ids starting "local-" — a locally evaluated crossing has
         *  no server notification row to read or dismiss. */
        private const val LOCAL_ROW_PREFIX = "local-place"

        /** Event-type strings an ARRIVAL may have been hashed over, newest naming
         *  first. Both are live on the wire — see [deriveTransition]. */
        private val ARRIVAL_EVENT_TYPES = listOf("enter", "arrival")

        /** The departure half of [ARRIVAL_EVENT_TYPES]. */
        private val DEPARTURE_EVENT_TYPES = listOf("exit", "departure")
    }

    /**
     * Serialises the whole decide-then-claim sequence. The ledger claim itself is
     * atomic (INSERT OR IGNORE), but the cross-path window check in
     * [alreadySurfacedNearby] is a separate read, and the local evaluator and the
     * server-push path can run concurrently on different dispatchers. Holding one
     * lock across both makes "check, then claim" safe for both callers.
     */
    private val gate = Mutex()

    /**
     * Serialises the per-(place, member) state read-modify-write in
     * [evaluateMember]. Separate from [gate] because Mutex is not reentrant and the
     * surfacing step takes [gate] — one shared mutex would deadlock.
     */
    private val stateGate = Mutex()

    /**
     * When this device last RAISED a place-alert notification from local evaluation.
     *
     * Read by the wake path in AlertHandler: sample, refresh, sample again. If it moved,
     * local evaluation produced the real notification and the generic fallback must be
     * suppressed; if it did not, nothing reproduced the crossing here and the fallback
     * has to fire so the user is not left with silence.
     *
     * `@Volatile` rather than gate-guarded on purpose — it is a monotonic watermark read
     * from another coroutine, never part of a decide-then-claim sequence, so taking the
     * gate for it would add a deadlock edge for no correctness gain.
     */
    @Volatile
    private var lastLocalSurfacedAt: Long = 0

    /** @see lastLocalSurfacedAt */
    fun lastLocalSurfacedAt(): Long = lastLocalSurfacedAt

    /**
     * Whether the "your fixes are too coarse" notice is currently standing, so it
     * is raised on the transition and not on every stored location.
     * @see noteCoarseFixes
     */
    @Volatile
    private var coarseNoticeEmitted = false

    /**
     * Circles whose members we must refresh to evaluate the place alerts held here.
     *
     * A "Just me" alert carries a null circle and is excluded: its own fixes already
     * reach the evaluator through LocationRepository.sendLocation, so fetching anyone
     * else's location would do nothing for it.
     */
    suspend fun watchedCircleIds(): List<String> = try {
        placeAlertDao.getActive()
            .mapNotNull { alert -> alert.circleId?.takeIf { it.isNotBlank() } }
            .distinct()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read watched circles for place alerts", e)
        emptyList()
    }

    /**
     * Evaluate a freshly persisted batch of decrypted locations against every
     * place this user holds an active alert on.
     *
     * Called for EVERY location this device stores, including its own — that is
     * what makes the "alert me when *I* arrive/leave" case work with no circle
     * and no sharing at all. The one exception is a member's late points
     * (`history_only` on a history row): stored and shown, never judged here.
     *
     * [fromServer]: the batch is the server's rows. Then none naming this account is
     * judged, in any spelling: only this phone's own fix is "me", and the decode
     * dropping a circle's row naming this account is guarded here a second time.
     * LocationRepository.persistAndEvaluate, the one caller, always says which.
     */
    suspend fun onLocationsPersisted(locations: List<LocationEntity>, fromServer: Boolean = false) {
        val me = sessionManager.getUserId()
        // With no account, the server's word about anyone is judged by nobody.
        if (fromServer && me == null) return
        val judged = if (!fromServer) locations else locations.filterNot { it.userId == me || com.pingolin.track.util.AccountIds.same(it.userId, me) }
        judge(judged)
    }

    private suspend fun judge(locations: List<LocationEntity>) {
        if (locations.isEmpty()) return
        val alerts = try {
            placeAlertDao.getActive()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read active place alerts", e)
            return
        }
        if (alerts.isEmpty()) return

        val now = System.currentTimeMillis()
        val alertsByPlace = alerts.groupBy { it.placeId }
        // A point further ahead than a clock may be off is judged by nobody, and never becomes a
        // watermark that would silence the member's alerts after it.
        val plausible = locations.filter { com.pingolin.track.util.LiveTime.plausible(it.timestamp, now) }
        if (plausible.size < locations.size) Log.w(TAG, "${locations.size - plausible.size} location(s) timed beyond this phone's clock not judged")
        // One member's points for one place must be reconciled together and in
        // time order, so group before evaluating rather than walking row by row.
        val pointsByUser = plausible.groupBy { it.userId }
        val selfUserId = sessionManager.getUserId()
        // Places whose ring THIS user's own fixes were too coarse to judge, this
        // batch. Only our own: a member sharing an approximate location has their
        // accuracy floored to the privacy bubble on purpose
        // (LocationRepository.motionFieldsFor), so their coarseness is the feature
        // working, not a fault, and is not the local user's to be told about.
        val goneDark = mutableSetOf<String>()

        for ((placeId, placeAlerts) in alertsByPlace) {
            val place = try {
                placeDao.getById(placeId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load place for alert evaluation", e)
                null
            } ?: continue
            val geofence = geofenceOf(place)

            for ((userId, points) in pointsByUser) {
                // Resolve circle membership ONCE per (member, alert-circle) and pass it
                // down: alertWatches is consulted again per decision, and a DAO hit per
                // consultation would put a query on the location hot path.
                // Consent as the member's own latest fix reported it. Points in this
                // batch all belong to one member, and the flag rides on each fix, so
                // take the NEWEST non-null value: a member who has just opted out must
                // not be judged on the strength of an older fix that predates the
                // change. Null (no fix said) leaves it unknown, which permits.
                val latestConsent = points
                    .sortedBy { it.timestamp }
                    .lastOrNull { it.allowAlertsOnMe != null }
                    ?.allowAlertsOnMe
                val watchedBy = placeAlerts.filter { alert ->
                    PlaceAlertRules.alertWatches(
                        alert, userId, isMemberOf(alert.circleId, userId), latestConsent,
                    )
                }
                // Skip members no alert on this place watches, before doing any geometry.
                if (watchedBy.isEmpty()) continue

                // Drop fixes too rough to say anything about THIS ring, before the
                // geometry rather than after it: a point that cannot resolve the
                // ring must not advance the member's state either, or a later
                // usable fix would be judged against a position we never believed.
                // Filtering here (not inside reconcile) is what keeps the
                // lastLocationAt watermark advancing only over points actually used.
                val usable = points.filter { PlaceAlertRules.canResolve(geofence, it.accuracy) }
                if (usable.size != points.size && userId == selfUserId) {
                    goneDark += placeId
                }
                if (usable.isEmpty()) continue

                try {
                    evaluateMember(geofence, place, watchedBy, userId, usable, now)
                } catch (e: Exception) {
                    Log.e(TAG, "Place-alert evaluation failed for place $placeId", e)
                }
            }
        }

        noteCoarseFixes(goneDark.size)
    }

    /**
     * Tell the user once when their own fixes stop being precise enough to judge
     * some of their places, and once more when they start being precise again.
     *
     * Latched, exactly as `UxEvent.SendingStale` is: this runs on every stored
     * location, so emitting per batch would put a snackbar on screen every fix.
     * The transition is the news; the steady state is not.
     *
     * Saying nothing here would be wrong. When a fix too rough for a ring
     * arrives, [PlaceAlertRules.canResolve] declines to guess with it, and the
     * visible result is a place alert that simply stops arriving; doing that
     * silently would trade one silent failure for another.
     *
     * HOW OFTEN THIS FIRES DEPENDS ON THE FLAVOUR, and the answer is not "rarely"
     * everywhere. The coarse fixes reaching this evaluator come from
     * [com.pingolin.track.worker.LocationHeartbeatWorker]'s
     * `LocationAccuracy.BALANCED` request, via
     * `LocationRepository.persistAndEvaluate`:
     *
     *   gms   -> PRIORITY_BALANCED_POWER_ACCURACY, nominally ~100 m, against rings
     *            that START at 100 m. Right on the boundary, and nominal is a
     *            target rather than a bound.
     *   libre -> NETWORK_PROVIDER first (PlatformLocationProvider), i.e. wifi/cell,
     *            routinely well past 100 m. Here this notice is a normal event.
     *
     * The tracking service is NOT a source: it requests `LocationAccuracy.HIGH` in
     * every mode, battery-saver included (see `LocationPolicy.MIN_ACCURACY_BALANCED`).
     *
     * So write the copy for a de-Googled user who may see it often, not for a GMS
     * user who may never see it.
     */
    private fun noteCoarseFixes(placesGoneDark: Int) {
        if (placesGoneDark > 0) {
            if (!coarseNoticeEmitted) {
                coarseNoticeEmitted = true
                uxEventBus.emit(com.pingolin.track.util.UxEvent.PlaceAlertsTooCoarse(placesGoneDark))
            }
        } else {
            coarseNoticeEmitted = false
        }
    }

    /**
     * The alert fires on the place's effective geofence: the explicit alert ring
     * when one is set, else the place radius. Same precedence the mover-side
     * detector uses (BoundaryRepository.boundariesFromPlaces).
     */
    private fun geofenceOf(place: PlaceEntity) = PlaceAlertRules.PlaceGeofence(
        placeId = place.id,
        latitude = place.latitude,
        longitude = place.longitude,
        radiusMeters = place.boundaryRadius ?: place.radius,
    )

    private suspend fun evaluateMember(
        geofence: PlaceAlertRules.PlaceGeofence,
        place: PlaceEntity,
        alerts: List<PlaceAlertEntity>,
        userId: String,
        points: List<LocationEntity>,
        now: Long,
    ) {
        // Read state, reconcile and write it back as ONE atomic step.
        //
        // The per-circle location refreshes run CONCURRENTLY (MainViewModel fans
        // out with async over every circle), so when a member is reached through
        // two shared circles, two evaluations of the same (place, member) overlap.
        // Unguarded, both read the same pre-crossing state, both detect the SAME
        // physical crossing at their own batch's timestamp, and — because the
        // ledger id includes crossedAt — they claim two different rows and the user
        // gets two notifications for one event.
        //
        // A separate mutex from [gate] on purpose: Mutex is NOT reentrant, and the
        // surfacing step below takes [gate], so sharing one would deadlock.
        val reconciliation = stateGate.withLock {
            val prior = stateDao.get(geofence.placeId, userId)?.let {
                // A watermark kept from beyond the clock reads as none, and where they
                // were stays: no reset, so no arrival fired again.
                val watermark = it.lastLocationAt.takeIf { at -> com.pingolin.track.util.LiveTime.plausible(at, now) } ?: Long.MIN_VALUE
                PlaceAlertRules.MemberPlaceState(isInside = it.isInside, lastLocationAt = watermark)
            }
            val reconciled = PlaceAlertRules.reconcile(
                geofence = geofence,
                userId = userId,
                prior = prior,
                points = points.map {
                    PlaceAlertRules.Point(it.latitude, it.longitude, it.timestamp, it.accuracy)
                },
            ) ?: return // every point was at or before the watermark — nothing new

            // Persist the advanced state FIRST. A crash between here and the
            // notification costs at most one missed alert; the other order would
            // re-drive the same transitions on the next batch.
            stateDao.upsert(
                PlaceAlertMemberStateEntity(
                    placeId = geofence.placeId,
                    userId = userId,
                    isInside = reconciled.state.isInside,
                    lastLocationAt = reconciled.state.lastLocationAt,
                )
            )
            reconciled
        }
        if (reconciliation.crossings.isEmpty()) return

        val decisions = PlaceAlertRules.classify(
            crossings = reconciliation.crossings,
            finalInside = reconciliation.state.isInside,
            now = now,
        )
        val memberName = displayNameFor(userId, homeCircleOf(alerts, place))

        for (decision in decisions) {
            // Which of this place's alerts actually asked for this crossing?
            // `alerts` is already scoped to those that watch this member (the
            // watchedBy filter in onLocationsPersisted), so the remaining tests are
            // liveness, direction and schedule.
            val wanting = alerts.filter { alert ->
                PlaceAlertRules.isAlertLiveAt(alert, decision.surfacing.at) &&
                    PlaceAlertRules.alertWantsSurfacing(alert, decision.surfacing) &&
                    PlaceAlertRules.isWithinSchedule(alert, decision.surfacing.at)
            }
            if (wanting.isEmpty()) continue

            // A crossing no alert wants a PUSH for (schedule matched but every
            // matching alert has notify_push off) still gets a ledger row and a
            // bell entry — it just never reaches the notification shade.
            val push = wanting.any { it.notifyPush }
            surface(
                place = place,
                userId = userId,
                memberName = memberName,
                decision = decision,
                push = push,
                now = now,
            )
        }
    }

    /**
     * Claim the crossing in the ledger and, if this device is the first path to
     * see it, show it. A given crossing surfaces at most once, EVER.
     */
    private suspend fun surface(
        place: PlaceEntity,
        userId: String,
        memberName: String,
        decision: PlaceAlertRules.Decision,
        push: Boolean,
        now: Long,
    ) {
        val surfacing = decision.surfacing
        val body = PlaceAlertRules.describe(
            surfacing = surfacing,
            memberName = memberName,
            placeName = place.name,
            now = now,
            isSelf = userId == sessionManager.getUserId(),
        )

        val claimed = gate.withLock {
            // Cross-path guard: a place that IS shared to a circle can still
            // produce a server-routed push for the same crossing. If that push
            // already landed, drop ours — whichever path is second loses. Checked
            // for EVERY crossing the decision covers: a collapsed pass-through
            // would otherwise still duplicate a server push for its enter half.
            // A silent decision raises no push, so it can't double-notify.
            // Only a SERVER row may suppress us. Our own earlier rows must not:
            // an enter 14 minutes after a previous enter is a real second arrival,
            // and matching it against our own history would cost a user who left
            // and came back their alert entirely. Re-surfacing the exact SAME
            // crossing is prevented by the ledger primary key below, not here.
            val clash = !decision.silent && surfacing.crossings.any {
                alreadySurfacedNearby(place.id, userId, it.transition.wire, SOURCE_SERVER, it.crossedAt)
            }
            if (clash) {
                Log.d(TAG, "Crossing already surfaced by a server push — dropping local copy")
                return@withLock false to null
            }
            // Claim EVERY crossing this decision accounts for (a collapsed
            // pass-through covers two), so neither half can resurface later.
            var first = false
            for (crossing in surfacing.crossings) {
                val inserted = eventDao.claim(
                    PlaceAlertEventEntity(
                        id = crossing.ledgerId,
                        placeId = crossing.placeId,
                        userId = crossing.userId,
                        transition = crossing.transition.wire,
                        crossedAt = crossing.crossedAt,
                        surfacedAt = now,
                        source = SOURCE_LOCAL,
                        silent = decision.silent,
                    )
                )
                if (inserted != -1L) first = true
            }
            // If a server push for this same crossing is already in the shade, take
            // its notification id so this named copy REPLACES it rather than stacking
            // beside it. Consumed, so it can replace only this one crossing.
            if (!first || decision.silent || !push) return@withLock first to null
            // The EARLIEST crossing, not surfacing.at: a collapsed pass-through is
            // stamped with its exit, but the push it replaces was raised for the enter.
            val earliest = surfacing.crossings.minBy { it.crossedAt }
            val replaces = takeServerPush(place.id, userId, earliest.transition.wire, earliest.crossedAt, now)
            // And what this copy shows, crossing by crossing, so the push of a crossing
            // still on its way doesn't repeat it. Under its own id, never a claim: it
            // can only ever stop a push's own post.
            for (crossing in surfacing.crossings) {
                shownDao.insert(
                    PlaceAlertShownEntity(
                        notificationId = "$LOCAL_ROW_PREFIX:${crossing.ledgerId}",
                        kind = PlaceAlertShownEntity.KIND_LOCAL,
                        placeId = place.id,
                        // Canonical, as a push's is ([planServerPush]): one member, one spelling.
                        userId = com.pingolin.track.util.AccountIds.canonical(crossing.userId) ?: crossing.userId,
                        transition = crossing.transition.wire,
                        eventAt = crossing.crossedAt,
                        shownAt = now,
                        consumed = false,
                    )
                )
            }
            first to replaces
        }
        val (claimedFirst, supersedes) = claimed
        if (!claimedFirst) return

        val dayBucket = (surfacing.at / 86_400_000L).toInt()
        val localRowId = "$LOCAL_ROW_PREFIX:${surfacing.crossings.first().ledgerId}"
        // The bell row keeps its own stable id; only the SHADE notification borrows
        // the server push's id, and only to replace it.
        val shadeId = supersedes ?: localRowId
        try {
            notificationDao.insert(
                NotificationEntity(
                    // Always the LOCAL id: the "local-" prefix is what tells
                    // NotificationRepository to skip the (404-ing) server round-trip
                    // on read/dismiss. Borrowing the server's id here would also
                    // collide with the row the server already wrote for that push.
                    id = localRowId,
                    circleId = null,
                    notificationType = notificationTypeFor(surfacing),
                    title = PlaceAlertRules.titleFor(surfacing),
                    body = body,
                    metadata = null,
                    isRead = false,
                    // Ordered by when the crossing HAPPENED, not when we noticed —
                    // a caught-up crossing lands in the timeline where it belongs.
                    createdDayBucket = dayBucket,
                    createdAt = surfacing.at,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist local place-alert record", e)
        }

        // Silent decisions are recorded and shown in the bell, but raise no push:
        // otherwise a phone that was off all weekend reconnects and dumps a pile
        // of ancient pings.
        if (decision.silent || !push) return

        // Watermark for the wake path: AlertHandler samples it before driving a refresh
        // and again after, to stop driving once a pass here surfaced something, for a
        // push it can't identify (an identified one asks [pushPaired]).
        lastLocalSurfacedAt = System.currentTimeMillis()

        alertHandler.get().handleAlert(
            notificationTypeFor(surfacing),
            mapOf(
                "user_name" to memberName,
                "place_name" to place.name,
                "place_id" to place.id,
                "body" to body,
                // "Visit" for a collapsed pass-through, which is neither a plain
                // arrival nor a plain departure.
                "title" to PlaceAlertRules.titleFor(surfacing),
                // Stable per-crossing id, so a re-post replaces rather than stacks —
                // EXCEPT when this crossing replaces a server push already in the
                // shade, in which case it borrows that push's id so Android replaces
                // it and the push the user saw first is named in place instead of
                // joined by a second one.
                "alert_id" to shadeId,
                // Already claimed in the ledger above — tells AlertHandler to post
                // it directly instead of re-claiming (which would drop it as a
                // duplicate of itself).
                "local_evaluated" to "true",
                // WHEN THE CROSSING HAPPENED — not when this device noticed it.
                //
                // `surfacing.at` is `crossing.crossedAt`, taken from the timestamps
                // on the member's own location points, and the ledger row above
                // already stores it for exactly this reason. Without it here,
                // AlertHandler.eventStamp would find no `created_at` and fall back to
                // System.currentTimeMillis(), so the body would read as if the arrival
                // had just happened. A locally-evaluated crossing is not always fresh:
                // it can be stale by up to STALENESS_WINDOW_MS (2h), because this path
                // evaluates ANOTHER member's shared location batch, which can be hours
                // old, and only crossings older than that window are silenced.
                // ISO-8601 because eventStamp reads it with ServerTime.epochMs.
                "created_at" to java.time.Instant.ofEpochMilli(surfacing.at).toString(),
            ) + if (supersedes != null) {
                // Named in place, without sounding again: the push the user heard was this crossing.
                mapOf(AlertHandler.KEY_REPLACES_SHOWN to "true")
            } else {
                emptyMap()
            }
        )
    }

    private fun notificationTypeFor(surfacing: PlaceAlertRules.Surfacing): String =
        when (surfacing) {
            // A collapsed pass-through is reported under the arrival type: it is
            // an arrival that has since ended, and AlertHandler groups both under
            // the same "Place Alerts" bundle anyway.
            is PlaceAlertRules.Surfacing.Departure -> AlertHandler.TYPE_PLACE_DEPARTURE
            else -> AlertHandler.TYPE_PLACE_ARRIVAL
        }

    /**
     * Has the OTHER path — [fromSource], never the caller's own — already shown
     * this (place, member, transition) recently?
     *
     * The server-routed push carries no crossing timestamp (the server's
     * notification metadata is place_id / target_user_id / event_type_hash only),
     * so the two paths cannot be matched by exact crossing id and are matched
     * within a window instead.
     *
     * Scoping to the other source is load-bearing, not defensive: a window match
     * means "a crossing of this shape happened recently", which is precisely what
     * a genuine re-crossing looks like. Checking a path against its own rows
     * would make a real second arrival look like a duplicate of the first.
     */
    private suspend fun alreadySurfacedNearby(
        placeId: String,
        userId: String,
        transition: String,
        fromSource: String,
        at: Long,
    ): Boolean = eventDao.countSurfacedSince(
        placeId = placeId,
        userId = userId,
        transition = transition,
        fromSource = fromSource,
        since = at - PlaceAlertRules.CROSS_PATH_DEDUPE_WINDOW_MS,
    ) > 0


    // SECURITY note on logout: place_alert_member_state, place_alert_events and
    // place_alert_shown hold decrypted, place-linked movement history for named members,
    // so they must not survive a same-process account switch. Both logout doors already
    // cover them —
    // AuthRepository.clearLocalData and the TrackApplication force-logout callback
    // each run `database.clearAllTables()`, which empties every table. No separate
    // wipe hook is wired here on purpose: an uncalled one would read like a
    // safeguard without being one.

    /**
     * Is [userId] a member of [circleId]? Used to scope a CIRCLE-WIDE alert to the
     * circle it is homed to. A null/blank circle answers false; alertWatches treats
     * that case separately (legacy rows stay unscoped rather than silently dying).
     *
     * Failure answers false: a membership lookup we can't complete must not be read
     * as "yes, watch this person" — that is the over-match this scoping exists to
     * prevent. An explicitly targeted alert is unaffected either way, since it never
     * consults membership.
     */
    private suspend fun isMemberOf(circleId: String?, userId: String): Boolean? {
        val circle = circleId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // The personal sentinels are not circles and have no roster to ask about —
        // an alert homed to one is circle-less by definition. Answer "unknown"
        // silently rather than logging a roster warning once per location batch.
        if (circle == PlaceRepository.PERSONAL_CIRCLE_ID || circle == PlaceRepository.PERSONAL_CIRCLE_UUID) {
            return null
        }
        return try {
            // An EMPTY local roster means we never synced this circle's members, not
            // that it has none — nothing refreshes circle_members in the background.
            // Answer "unknown" so the caller declines to scope, rather than scoping
            // everyone out and silencing the alert.
            if (circleMemberDao.countByCircle(circle) == 0) {
                Log.w(TAG, "No local roster for circle $circle — not scoping this alert")
                null
            } else {
                circleMemberDao.get(circle, userId) != null
            }
        } catch (e: Exception) {
            // "Couldn't check" must not read as "definitely not a member" either.
            Log.e(TAG, "Circle membership lookup failed; not scoping this alert", e)
            null
        }
    }

    /**
     * A server-routed place push, before it posts: [ServerPushOutcome.POST] it now, under
     * [notificationId], or [ServerPushOutcome.ALREADY_SHOWN] when this device already shows that crossing:
     * this very push (delivered again; posted again it would put the generic words back over a named copy),
     * or the local copy of the same crossing (same [transition], [eventAt] within
     * [PlaceAlertRules.CROSSING_EVENT_MATCH_MS]: [PlaceAlertRules.localCopyRepeated]).
     *
     * A push that posts leaves a row in the shade's table, so the named local copy of the same crossing
     * replaces it in place when it comes ([takeServerPush]), however late within the staleness window and
     * across a process death. Posting it, rather than treating the push as a pure wake, is what makes a
     * crossing the local evaluation can't reproduce heard at all.
     *
     * TRIPWIRE for whoever tidies this up. It writes NO ledger claim (PlaceAlertEventEntity), and must not:
     * a claim by this path would make the later local copy look like a duplicate and be dropped, which
     * turns "vaguer than we'd like" into silence, and it would have to name a direction this push may not
     * know, so an enter and an exit would share a key and one would silently suppress the other. A row in
     * the shade's table can only ever stop THIS push's own post (above), never a local copy, and a push of
     * unknown direction or time never matches a local copy at all ([PlaceAlertRules.localCopyRepeated]): a
     * stack is recoverable, a swallowed crossing is not.
     *
     * A push it can't identify (no id, no place, or no member) is always posted, and pairs with nothing.
     */
    suspend fun planServerPush(
        notificationId: String?,
        placeId: String?,
        userId: String?,
        transition: PlaceAlertRules.Transition?,
        eventAt: Long?,
    ): ServerPushOutcome {
        if (notificationId.isNullOrBlank()) return ServerPushOutcome.POST
        val now = System.currentTimeMillis()
        return try {
            gate.withLock {
                shownDao.deleteShownBefore(now - PlaceAlertRules.SHOWN_PAIRING_MS)
                if (shownDao.get(notificationId) != null) {
                    Log.d(TAG, "Server place push already shown — not posting it again")
                    return@withLock ServerPushOutcome.ALREADY_SHOWN
                }
                val user = com.pingolin.track.util.AccountIds.canonical(userId)
                if (placeId.isNullOrBlank() || user == null) return@withLock ServerPushOutcome.POST
                val local = PlaceAlertRules.localCopyRepeated(
                    shownDao.unpaired(PlaceAlertShownEntity.KIND_LOCAL, placeId, user),
                    transition?.wire,
                    eventAt,
                )
                shownDao.insert(
                    PlaceAlertShownEntity(
                        notificationId = notificationId,
                        kind = PlaceAlertShownEntity.KIND_SERVER,
                        placeId = placeId,
                        userId = user,
                        transition = transition?.wire,
                        eventAt = eventAt,
                        shownAt = now,
                        // Paired already when the local copy has it: a later local pass pairs with nothing here.
                        consumed = local != null,
                    )
                )
                if (local != null) {
                    shownDao.consume(local.notificationId)
                    Log.d(TAG, "Server place push repeats the local copy already shown — not posting it")
                    ServerPushOutcome.ALREADY_SHOWN
                } else {
                    ServerPushOutcome.POST
                }
            }
        } catch (e: Exception) {
            // Never let the shade's table swallow a real alert.
            Log.e(TAG, "Server place push pairing failed; posting it", e)
            ServerPushOutcome.POST
        }
    }

    /** What [planServerPush] decided. */
    enum class ServerPushOutcome { POST, ALREADY_SHOWN }

    /**
     * Whether the server push posted under [notificationId] has its named local copy now: the wake in
     * AlertHandler stops driving local evaluation once it has.
     */
    suspend fun pushPaired(notificationId: String?): Boolean =
        notificationId?.takeIf { it.isNotBlank() }?.let { id ->
            runCatching { shownDao.get(id)?.consumed == true }.getOrDefault(false)
        } ?: false

    /**
     * Take the notification id of the server push this local crossing REPLACES, or null to post under its
     * own ([PlaceAlertRules.serverPushToReplace]). CONSUMES it: one push is replaced by exactly one crossing,
     * so an enter and a following exit can never share a notification and silently replace each other.
     *
     * Caller must hold [gate].
     */
    private suspend fun takeServerPush(placeId: String, userId: String, transition: String, crossedAt: Long, now: Long): String? {
        shownDao.deleteShownBefore(now - PlaceAlertRules.SHOWN_PAIRING_MS)
        val user = com.pingolin.track.util.AccountIds.canonical(userId) ?: return null
        val push = PlaceAlertRules.serverPushToReplace(
            shownDao.unpaired(PlaceAlertShownEntity.KIND_SERVER, placeId, user),
            transition,
            crossedAt,
        ) ?: return null
        shownDao.consume(push.notificationId)
        return push.notificationId
    }

    /**
     * What this device can say about a server-routed place push on its own, before
     * (or instead of) reproducing the crossing locally.
     *
     * [transition] is null when the direction could not be established. The names are
     * not optional: a copy with no name to show is not worth building, so the whole
     * result is null in that case and the caller keeps its generic wording.
     */
    data class ServerPushCopy(
        val memberName: String,
        val placeName: String,
        val transition: PlaceAlertRules.Transition?,
    )

    /**
     * Name the member and the place a server push is about, from what is already on
     * this device.
     *
     * The server cannot do this and never will — the place name and the member's
     * display name are E2EE, which is why its copy is "Member arrived at a place"
     * with a NULL body. But the subscriber holds both in Room already: the place
     * because it owns the alert, the member because it renders them on the map. So a
     * push carrying nothing but `place_id` and `target_user_id` still has everything
     * needed to render the copy the notification-detail setting promises ("Names and
     * places — Alice arrived at Home"). Without this, that setting would select
     * between three wordings the server path cannot tell apart, and full detail
     * would read exactly like generic.
     *
     * Resolution is best-effort at every step and gives up rather than guessing: an
     * unknown place (never synced, or deleted) returns null and the caller keeps the
     * generic copy, which is worse but never wrong.
     */
    suspend fun resolveServerPushCopy(
        placeId: String?,
        userId: String?,
        eventTypeHash: String?,
    ): ServerPushCopy? {
        if (placeId.isNullOrBlank() || userId.isNullOrBlank()) return null
        val place = try {
            placeDao.getById(placeId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load place for server-push copy", e)
            null
        } ?: return null
        return ServerPushCopy(
            // Never a roster fetch here: the push posts at once, and a fetch (up to the API's timeouts)
            // would hold it back while its row is already marked read, so a process killed meanwhile
            // would never show the crossing at all. The wake's local copy fetches and names it in place.
            memberName = displayNameFor(userId, circleId = null),
            placeName = place.name,
            transition = deriveTransition(placeId, eventTypeHash),
        )
    }

    /**
     * Read a crossing's direction back out of the `event_type_hash` the push carries.
     *
     * Not a guess — the same derivation the server performs, run against the same
     * inputs. The hash is BLAKE3(circle key || event type), the alert stored its own
     * enter/exit hashes at creation time from the very same key
     * (PlaceRepository.eventHash), and the server's alert worker fires by matching
     * one against the other. Recomputing them here therefore either matches exactly
     * or does not match at all; there is no partial answer to be wrong about.
     *
     * Both naming families are tried because both are in the wire: the Android mover
     * hashes "enter"/"exit" (BoundaryRepository) while an alert co-populates
     * "arrival"/"departure" as well, and the server's alert worker checks one pair
     * before falling back to the other.
     *
     * Scoped to the directions an alert actually has ENABLED, mirroring the server's
     * own gate: a toggled-off direction keeps its stale hash server-side, so matching
     * one would name a direction that alert would never have fired on.
     *
     * It pairs a push with the local copy of the same direction in the shade's table,
     * where a match can only stop the push's own post, never a crossing. Never a ledger
     * claim. The result never reaches the ledger: writing a row keyed on a
     * transition is what the tripwire on [planServerPush] forbids,
     * and derived-not-guessed does not change that a wrong answer there suppresses a
     * real crossing of the opposite direction. Wrong copy is recoverable; a swallowed
     * alert is not. Plain equality rather than a constant-time compare for the same
     * reason the server needs one and this does not: nobody is timing a local string
     * comparison against a value the server already handed us.
     */
    private suspend fun deriveTransition(
        placeId: String,
        eventTypeHash: String?,
    ): PlaceAlertRules.Transition? {
        val hash = eventTypeHash?.takeIf { it.isNotBlank() } ?: return null
        val alerts = try {
            placeAlertDao.getActive().filter { it.placeId == placeId }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read alerts for direction derivation", e)
            return null
        }
        for (alert in alerts) {
            // The alert's OWN circle, never the place's: an alert on a personal place
            // is homed to a real circle it is shared to, and the hashes were keyed
            // with that circle's key (see PlaceAlertEntity.circleId).
            val circleId = alert.circleId?.takeIf { it.isNotBlank() } ?: continue
            if (alert.alertOnArrival && matchesAnyHash(circleId, hash, ARRIVAL_EVENT_TYPES)) {
                return PlaceAlertRules.Transition.ENTER
            }
            if (alert.alertOnDeparture && matchesAnyHash(circleId, hash, DEPARTURE_EVENT_TYPES)) {
                return PlaceAlertRules.Transition.EXIT
            }
        }
        return null
    }

    private fun matchesAnyHash(circleId: String, hash: String, eventTypes: List<String>): Boolean =
        eventTypes.any { eventType ->
            try {
                eventTypeHasher.hash(circleId, eventType) == hash
            } catch (e: Exception) {
                // No key for this circle (rotated, never held) — not an error worth
                // logging per push. Unresolved direction just costs a vaguer word.
                false
            }
        }

    /**
     * The member's name as a notification may say it: one this device OPENED with the circle's key, never the
     * server's plaintext fallback (as NotificationNames.stored), else "A member".
     *
     * On a miss, [circleId]'s roster is fetched again first, as NotificationNames.member does, at most once per
     * [ROSTER_REFETCH_MIN_MS]: a member who joined, or sealed their name, after this phone last loaded that
     * roster would otherwise be "A member" until the next load (an alert circle's at most hourly in the
     * background, BoundaryRepository.ROSTER_MAX_AGE_MS). The local copy's path passes the alert's circle; a
     * server push passes none, so it never waits on the network ([resolveServerPushCopy]).
     */
    private suspend fun displayNameFor(userId: String, circleId: String?): String {
        if (userId == sessionManager.getUserId()) return "You"
        openedName(userId)?.let { return it }
        val circle = circleId
            ?.takeUnless { it.isBlank() || com.pingolin.track.util.AccountIds.isPersonalSlot(it) }
            ?.let { com.pingolin.track.util.AccountIds.storeKey(it) }
            ?: return "A member"
        val now = System.currentTimeMillis()
        val last = rosterRefetchedAt[circle]
        if (last != null && now - last in 0 until ROSTER_REFETCH_MIN_MS) return "A member"
        rosterRefetchedAt[circle] = now
        runCatching { circleRepository.get().refreshRoster(circle) }
            .onFailure { Log.w(TAG, "Roster refetch for a name failed: ${it.javaClass.simpleName}") }
        return openedName(userId) ?: "A member"
    }

    private suspend fun openedName(userId: String): String? =
        runCatching { circleMemberDao.anyOpenedDisplayName(userId) }.getOrNull()

    /** When [circleRepository]'s roster was last fetched again for a name, by circle ([displayNameFor]). */
    private val rosterRefetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * The circle whose roster names [alerts]' member: the one an alert routes under (an alert on a personal
     * place is homed to a real circle it is shared to, PlaceAlertEntity.circleId), else the place's own.
     */
    private fun homeCircleOf(alerts: List<PlaceAlertEntity>, place: PlaceEntity): String? =
        alerts.firstNotNullOfOrNull { alert -> alert.circleId?.takeIf { it.isNotBlank() } } ?: place.circleId
}
