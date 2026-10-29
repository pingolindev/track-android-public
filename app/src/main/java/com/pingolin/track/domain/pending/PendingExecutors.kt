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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.dao.PlaceAlertDao
import com.pingolin.track.data.local.dao.PlaceDao
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.data.local.entity.PlaceEntity
import com.pingolin.track.domain.repository.AlertRuleRepository
import com.pingolin.track.domain.repository.CheckInRepository
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.domain.repository.DestinationRepository
import com.pingolin.track.domain.repository.InactivityAlertRepository
import com.pingolin.track.domain.placealerts.PlaceAlertRules
import com.pingolin.track.domain.repository.PlaceRepository
import com.pingolin.track.util.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Each kind's run: the re-check against the server's state as the screens read it — no
 * stronger or weaker than the live path's own trust in it — then the button's own function
 * with the stored inputs. A change that no longer makes sense is dropped with its line.
 *
 * "Deleted", "they left" and "changed" are read only from a list the server gave WHOLE for
 * this re-check: never the local rows, a roster filtered to who is shown here, a refresh
 * that failed or a list with more pages. When there is no such list the change waits
 * ([WAIT]), bounded by its age.
 *
 * Lazy: the repositories keep their changes here ([PendingChanges]), and this runs them.
 */
@Singleton
class PendingExecutors @Inject constructor(
    private val checkIns: dagger.Lazy<CheckInRepository>,
    private val destinations: dagger.Lazy<DestinationRepository>,
    private val places: dagger.Lazy<PlaceRepository>,
    private val alertRules: dagger.Lazy<AlertRuleRepository>,
    private val inactivityAlerts: dagger.Lazy<InactivityAlertRepository>,
    private val circles: dagger.Lazy<CircleRepository>,
    private val placeDao: PlaceDao,
    private val placeAlertDao: PlaceAlertDao,
    private val sessionManager: SessionManager,
    private val auth: dagger.Lazy<com.pingolin.track.domain.repository.AuthRepository>,
) {
    /** The signing key is missing on this phone: a change that signs waits for the password. */
    fun signingKeyMissing(): Boolean = runCatching { auth.get().signingKeyMissing() }.getOrDefault(false)

    fun forKind(kind: PendingKind?): PendingExecutor? = when (kind) {
        PendingKind.CHECK_IN -> checkIn
        PendingKind.DESTINATION -> destination
        PendingKind.ARRIVED -> arrived
        PendingKind.SHARE_PLACE -> sharePlace
        PendingKind.COPY_PLACES -> copyPlaces
        PendingKind.EDIT_PLACE -> editPlace
        PendingKind.MEMBER_PLACE_ALERT, PendingKind.CIRCLE_PLACE_ALERT -> placeAlert
        PendingKind.BATTERY_ALERT -> batteryAlert
        PendingKind.ALERT_CHANGE -> alertChange
        PendingKind.INACTIVITY_ALERT -> inactivityAlert
        PendingKind.INVITE -> invite
        PendingKind.MAKE_ADMIN -> makeAdmin
        PendingKind.LET_IN -> letIn
        PendingKind.RENEW_INVITATION -> renewInvitation
        null -> null
    }

    /**
     * What a failure of the button's function means (see [PendingChangeRunner.classify]); a
     * removal found at the signature ([PendingRun.beforeSend]) gets
     * [PendingLines.memberLeft]'s line, about [member].
     */
    private fun <T> Result<T>.toOutcome(inputs: PendingInputs, member: String? = null): PendingOutcome = fold(
        onSuccess = { PendingOutcome.Done() },
        onFailure = {
            if (it is RemovedSinceDecided) memberLeft(inputs, member)
            else PendingChangeRunner.classify(it, PendingInputs.subject(inputs), inputs.circleName)
        },
    )

    /**
     * The removal check at the signature ([PendingRun.beforeSend]): [userId] removed from the
     * circle's records after the person decided, found only once the statement is signed,
     * stops it there; with no answer, it waits.
     */
    private fun stillDecided(circleId: String, userId: String, tapVersion: Int?, keys: Pair<String, String>? = null): suspend () -> Unit = {
        when (circles.get().removedSinceDecided(circleId, userId, tapVersion, keys)) {
            null -> throw CouldNotCheck()
            true -> throw RemovedSinceDecided()
            false -> Unit
        }
    }

    private fun memberLeft(inputs: PendingInputs, name: String?) = PendingOutcome.Dropped(
        PendingLines.memberLeft(PendingInputs.subject(inputs), name ?: "they", inputs.circleName)
    )

    private fun deleted(inputs: PendingInputs) = PendingOutcome.Dropped(PendingLines.deleted(PendingInputs.subject(inputs)))

    // Check-in: the position goes as the stricter of the setting when queued (already applied
    // to the stored one) and at send (the check-in's own rule, applied again); a place that is
    // no longer on the whole list goes without its id.
    private val checkIn = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.CheckIn
            val placeId = if (i.placeId == null) null else {
                val listed = places.get().refreshPlacesWhole() ?: return WAIT
                i.placeId.takeIf { it in listed }
            }
            return checkIns.get().createCheckIn(
                change.circleId, i.type, i.message, i.latitude, i.longitude, placeId,
                checkedInAt = i.checkedInAt, pendingRun = run,
            ).toOutcome(i)
        }
    }

    // Destination.
    private val destination = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.Destination
            return destinations.get().createDestination(
                change.circleId, i.name, i.latitude, i.longitude, i.etaMs, i.notifyArrival, pendingRun = run,
            ).toOutcome(i)
        }
    }

    // "I've arrived": the destination must still be active and not already arrived. The list
    // leaves arrived destinations out, so one missing from a WHOLE list has ended the way this
    // change would have ended it (missing from a list that may have left some out: it waits);
    // one listed as cancelled or run out was deleted, whole list or not.
    private val arrived = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.Arrived
            val listed = destinations.get().refreshDestinationsListed() ?: return WAIT
            val status = listed.statuses[i.destinationId]
                ?: return if (listed.whole) PendingOutcome.Done() else WAIT
            if (status != DestinationRepository.STATUS_ACTIVE) return deleted(i)
            return destinations.get().reportArrival(
                i.destinationId, change.circleId, i.latitude, i.longitude, occurredAtMs = i.arrivedAt, pendingRun = run,
            ).toOutcome(i)
        }
    }

    // Share a place: the place still exists and is this person's; already
    // shared there is done.
    private val sharePlace = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.SharePlace
            val listed = places.get().refreshPlacesWhole() ?: return WAIT
            if (i.placeId !in listed) return deleted(i)
            // Listed, but not readable on this phone just now.
            val place = placeDao.getById(i.placeId) ?: return WAIT
            // Someone else's place, shared into a circle of theirs: not this person's to share.
            if (place.wrapViaCircle != null) return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            if (change.circleId in sharedWith(place)) {
                // The "already shared" line when someone else did it. After a
                // lost answer it was most likely this change's own earlier try:
                // done, with nothing to say.
                val line = if (change.lastOutcome == PendingChanges.LOST_ANSWER) null else PendingLines.alreadyShared(place.name, i.circleName)
                return PendingOutcome.Done(line)
            }
            // It may have gone out, and the place isn't shared now: the person may have unshared
            // it on another device meanwhile. Never shared again: [PendingLines.notDone]'s line.
            // The share route has no request id to tell the two apart.
            if (change.lastOutcome == PendingChanges.LOST_ANSWER) {
                return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            }
            return places.get().sharePlaceWithCircle(i.placeId, change.circleId, pendingRun = run).toOutcome(i)
        }
    }

    // Copy places: the places still exist; every copy carries its own request id.
    private val copyPlaces = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.CopyPlaces
            val listed = places.get().refreshPlacesWhole() ?: return WAIT
            if (i.placeId != null) {
                if (i.placeId !in listed) return deleted(i)
                val place = placeDao.getById(i.placeId) ?: return WAIT
                return places.get().copyPlaceToCircle(place, change.circleId, pendingRun = run).toOutcome(i)
            }
            val source = i.sourceCircleId ?: return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            val ids = runCatching { com.google.gson.Gson().fromJson(change.seen, Array<String>::class.java).toSet() }
                .getOrNull().orEmpty()
            val still = ids.filter { it in listed }.toSet()
            if (still.isEmpty()) return deleted(i)
            // The copy reads the places here: one still listed but not readable on this phone
            // just now would be left out without a word. It waits, as a single copy does.
            val readable = placeDao.getByCircle(source).map { it.id }.toSet()
            if (!readable.containsAll(still)) return WAIT
            return places.get().copyAllPlacesToCircle(source, change.circleId, pendingRun = run, onlyPlaceIds = still).toOutcome(i)
        }
    }

    // Edit a place: saved only if the place is still as the
    // person saw it.
    private val editPlace = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.EditPlace
            val listed = places.get().refreshPlacesWhole() ?: return WAIT
            if (i.placeId !in listed) return deleted(i)
            val place = placeDao.getById(i.placeId) ?: return WAIT
            val seen = runCatching { com.google.gson.Gson().fromJson(change.seen, PendingInputs.EditPlace::class.java) }.getOrNull()
            if (seen == null || !stillAsSeen(place, seen)) return PendingOutcome.Dropped(PendingLines.placeChanged(i.placeName))
            return places.get().updatePlace(
                i.placeId, i.name, i.notes, i.latitude, i.longitude, i.radius, i.icon, i.color, i.boundaryRadius,
                pendingRun = run,
            ).toOutcome(i)
        }
    }

    // Place alerts: the place is still there for the circle, the member still in it (for an
    // alert about one member), and no identical alert already.
    private val placeAlert = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.PlaceAlert
            val listed = places.get().refreshPlacesWhole() ?: return WAIT
            if (i.placeId !in listed) return deleted(i)
            val place = placeDao.getById(i.placeId) ?: return WAIT
            // No longer shared with this circle: the alert has nothing to watch there.
            if (place.circleId != change.circleId && change.circleId !in sharedWith(place)) {
                return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            }
            if (i.memberId != null) {
                val members = circles.get().memberIdsWhole(change.circleId) ?: return WAIT
                if (i.memberId !in members) return memberLeft(i, i.memberName)
            }
            places.get().refreshPlaceAlerts()
            val target = i.memberId ?: PlaceAlertRules.CIRCLE_WIDE_TARGET
            val identical = placeAlertDao.getActiveByPlace(i.placeId).any {
                it.targetUserId == target && it.alertOnArrival == i.alertOnArrival && it.alertOnDeparture == i.alertOnDeparture
            }
            if (identical) return PendingOutcome.Done()
            val result = if (i.memberId != null) {
                places.get().createPlaceAlert(
                    change.circleId, i.placeId, i.memberId, i.alertOnArrival, i.alertOnDeparture, i.activeDays,
                    i.activeStartTime, i.activeEndTime, i.notifyPush, i.notifyEmail, i.notifySms, i.isRecurring, i.expiresAt,
                    pendingRun = run,
                )
            } else {
                places.get().createCircleWidePlaceAlert(
                    change.circleId, i.placeId, i.alertOnArrival, i.alertOnDeparture, i.activeDays,
                    i.activeStartTime, i.activeEndTime, i.notifyPush, i.notifyEmail, i.notifySms, i.isRecurring, i.expiresAt,
                    pendingRun = run,
                )
            }
            return result.toOutcome(i)
        }
    }

    // Low-battery alert: the member is still in the circle, and no identical
    // alert already.
    private val batteryAlert = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.BatteryAlert
            val members = circles.get().memberIdsWhole(change.circleId) ?: return WAIT
            if (i.memberId !in members) return memberLeft(i, i.memberName)
            val rules = alertRules.get().refreshAlertRules().getOrElse { return WAIT }
            val identical = rules.any {
                it.circleId == change.circleId && it.targetUserId == i.memberId &&
                    it.ruleType == AlertRuleRepository.RULE_TYPE_LOW_BATTERY && it.isActive && it.batteryThreshold == i.threshold
            }
            if (identical) return PendingOutcome.Done()
            return alertRules.get().createLowBatteryAlert(
                change.circleId, i.memberId, i.threshold, i.notifyPush, i.notifyEmail, pendingRun = run,
            ).toOutcome(i)
        }
    }

    // Alert change: the alert still exists; only the changed fields go.
    private val alertChange = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.AlertChange
            val listed = alertRules.get().refreshAlertRulesWhole() ?: return WAIT
            if (i.ruleId !in listed.ids) return deleted(i)
            // The rule as the person saw it when queued: as it was → sent; already as the change
            // makes it → done, nothing sent; anything else → changed on another device meanwhile,
            // never overwritten ([PendingLines.ALERT_CHANGED]). Read from this refresh: listed but
            // not readable here, it waits.
            val seen = runCatching { com.google.gson.Gson().fromJson(change.seen, PendingInputs.AlertChange::class.java) }.getOrNull()
            val current = listed.rules.firstOrNull { it.id == i.ruleId } ?: return WAIT
            fun sameAs(threshold: Int?, push: Boolean?, email: Boolean?) =
                current.batteryThreshold == threshold && current.notifyPush == push && current.notifyEmail == email
            if (seen == null) {
                // No record of the rule as it was shown (kept by an older build): never sent over
                // a rule that may have changed since; done if it is already so.
                val already = current.batteryThreshold == i.threshold &&
                    (i.notifyPush == null || current.notifyPush == i.notifyPush) &&
                    (i.notifyEmail == null || current.notifyEmail == i.notifyEmail)
                return if (already) PendingOutcome.Done() else PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            }
            val target = Triple(i.threshold ?: seen.threshold, i.notifyPush ?: seen.notifyPush, i.notifyEmail ?: seen.notifyEmail)
            when {
                sameAs(seen.threshold, seen.notifyPush, seen.notifyEmail) -> Unit
                sameAs(target.first, target.second, target.third) -> return PendingOutcome.Done()
                else -> return PendingOutcome.Dropped(PendingLines.ALERT_CHANGED)
            }
            return alertRules.get().updateAlertRule(
                i.ruleId,
                notifyPush = i.notifyPush,
                notifyEmail = i.notifyEmail,
                conditions = i.threshold?.let { mapOf("threshold" to it) },
                pendingRun = run,
            ).toOutcome(i)
        }
    }

    // Inactivity alert: the members still in the circle; no identical
    // alert already.
    private val inactivityAlert = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.InactivityAlert
            val members = circles.get().memberIdsWhole(change.circleId) ?: return WAIT
            val self = sessionManager.getUserId()
            val watched = i.watchedUserIds.filter { it in members }
            if (i.watchedUserIds.isNotEmpty() && watched.isEmpty()) {
                return memberLeft(i, i.watchedNames[i.watchedUserIds.first()])
            }
            if (!i.isSelfAlert && i.watchedUserIds.isEmpty() && members.none { it != self }) {
                return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
            }
            val existing = inactivityAlerts.get().refreshInactivityAlerts().getOrElse { return WAIT }
            val targets = when {
                i.isSelfAlert -> listOfNotNull(self)
                watched.isNotEmpty() -> watched
                else -> members.filter { it != self }
            }
            val identical = targets.isNotEmpty() && targets.all { target ->
                existing.any {
                    it.circleId == change.circleId && it.targetUserId == target && it.isSelfAlert == i.isSelfAlert &&
                        it.isActive && it.timeoutHours * 60 == i.timeoutMinutes
                }
            }
            if (identical) return PendingOutcome.Done()
            return inactivityAlerts.get().createInactivityAlert(
                change.circleId, i.targetUserId, i.timeoutMinutes, i.isSelfAlert, i.notifyPush, i.notifyEmail,
                i.notifyOnReturn, i.cooldownMinutes, watched, pendingRun = run,
            ).toOutcome(i)
        }
    }

    /**
     * Still an owner or admin of the circle, from the whole member list: a 403 at send
     * decides nothing. Null: couldn't be read.
     */
    private suspend fun ownerOrAdmin(circleId: String): Boolean? {
        val roles = circles.get().memberRolesWhole(circleId) ?: return null
        val self = sessionManager.getUserId() ?: return null
        val role = roles.entries.firstOrNull { it.key.equals(self, ignoreCase = true) }?.value
        return role == "owner" || role == "admin"
    }

    private fun notOwner(inputs: PendingInputs) =
        PendingOutcome.Dropped(PendingLines.notOwner(PendingInputs.subject(inputs), inputs.circleName))

    // Invite: still an owner or admin; the server's own answers decide "already" (which of
    // this change's statements it holds); "Is this really {email}?" is asked again at most
    // once.
    private val invite = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.Invite
            if (signingKeyMissing()) return PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD)
            when (ownerOrAdmin(change.circleId)) {
                null -> return WAIT
                false -> return notOwner(i)
                true -> Unit
            }
            val confirmedUser = i.confirmedUserId
            val confirmedSharing = i.confirmedSharingKey
            val confirmedSigning = i.confirmedSigningKey
            val result = if (confirmedUser != null && confirmedSharing != null && confirmedSigning != null) {
                // "It's them" to exactly these details: this device takes them now, through the
                // live answer, then the invitation goes out as usual.
                circles.get().confirmInviteeAndInvite(
                    com.pingolin.track.domain.repository.InviteeChangedException(
                        change.circleId, i.email, confirmedUser, confirmedSharing, confirmedSigning, null,
                    ),
                    pendingRun = run.copy(tapVersion = i.tapVersion),
                )
            } else {
                circles.get().inviteToCircle(change.circleId, i.email, pendingRun = run.copy(tapVersion = i.tapVersion))
            }
            return result.fold(onSuccess = { PendingOutcome.Done() }) { e ->
                val code = (e as? com.pingolin.track.data.remote.dto.RefusedException)?.errorCode
                when {
                    e is com.pingolin.track.domain.repository.InviteeChangedException ->
                        // Asked once already: the details keep changing.
                        if (i.confirmedUserId != null) {
                            PendingOutcome.Dropped(PendingLines.inviteDetailsKeepChanging(i.email))
                        } else {
                            PendingOutcome.NeedsPerson(
                                PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.INVITEE, e.userId, e.sharingKeyHex, e.signingKeyHex, e.number)),
                            )
                        }
                    // Removed from the circle's records after the person decided:
                    // [PendingLines.memberLeft]'s line.
                    e is RemovedSinceDecided -> memberLeft(i, i.email)
                    // "It's them" was about another account than the address names now.
                    e is com.pingolin.track.domain.repository.InviteeNotAsConfirmedException ->
                        PendingOutcome.Dropped(PendingLines.inviteDetailsKeepChanging(i.email))
                    // A resend of this change's own request, or someone else's: the look decides.
                    code == PendingChangeRunner.REQUEST_ALREADY_DONE -> PendingOutcome.Already(null)
                    code == "invite_already_exists" -> PendingOutcome.Already(PendingLines.alreadyInvited(i.email, i.circleName))
                    code == "circle_already_member" -> PendingOutcome.Already(PendingLines.alreadyIn(i.email, i.circleName))
                    // Past the inviter's allowance of invitation e-mails: a 429 that can last most
                    // of a day. It ends here with its own line: waiting on it like any other 429
                    // would retry all day and then send the invitation by itself, hours later.
                    code == "invite_limit_reached" -> PendingOutcome.Dropped(PendingLines.inviteLimit(PendingInputs.subject(i)))
                    else -> PendingChangeRunner.classify(e, PendingInputs.subject(i), i.circleName)
                }
            }
        }
    }

    // Make admin: the member still in, this user still the owner, and still confirmed with the
    // keys the person saw; the admission first, then the role. Other keys: asked again once.
    private val makeAdmin = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.MakeAdmin
            if (!run.admissionHeld && signingKeyMissing()) return PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD)
            val roles = circles.get().memberRolesWhole(change.circleId) ?: return WAIT
            if (roles.keys.none { it.equals(i.memberId, ignoreCase = true) }) return memberLeft(i, i.memberName)
            val self = sessionManager.getUserId()
            if (roles.entries.firstOrNull { it.key.equals(self, ignoreCase = true) }?.value != "owner") return notOwner(i)
            val seen = i.sharingKey to i.signingKey
            if (!run.admissionHeld) {
                // Removed from the circle's records after the person decided:
                // [PendingLines.memberLeft]'s line.
                when (circles.get().removedSinceDecided(change.circleId, i.memberId, i.tapVersion)) {
                    null -> return WAIT
                    true -> return memberLeft(i, i.memberName)
                    false -> Unit
                }
                // What this device would sign for them now: the keys it holds for them, as listed.
                val now = circles.get().promotionKeysNow(change.circleId, i.memberId)
                if (now == null || !sameKeys(now, seen)) {
                    val listed = circles.get().listedKeys(change.circleId, i.memberId)
                    when {
                        // "It's them" to exactly the keys listed now (asked
                        // once): taken now, through the live answer, and
                        // only then signed for.
                        i.asked && listed != null && sameKeys(listed, seen) -> {
                            circles.get().acceptMemberKeys(change.circleId, i.memberId, i.memberName, seen)
                            val again = circles.get().promotionKeysNow(change.circleId, i.memberId)
                            if (again == null || !sameKeys(again, seen)) {
                                return PendingOutcome.Dropped(PendingLines.adminDetailsKeepChanging(i.memberName))
                            }
                        }
                        i.asked || listed == null -> return PendingOutcome.Dropped(PendingLines.adminDetailsKeepChanging(i.memberName))
                        else -> return PendingOutcome.NeedsPerson(
                            PendingQuestions.ask(
                                PendingQuestions.Ask(
                                    PendingQuestions.MEMBER, i.memberId, listed.first, listed.second,
                                    circles.get().computeSafetyNumber(i.memberId, listed.first, listed.second),
                                )
                            )
                        )
                    }
                }
            }
            val signing = if (run.admissionHeld) run else run.copy(beforeSend = stillDecided(change.circleId, i.memberId, i.tapVersion))
            return circles.get().updateMemberRole(change.circleId, i.memberId, "admin", decidedKeys = seen, pendingRun = signing)
                .toOutcome(i, i.memberName)
        }
    }

    // Let in, in this order: the member still in the circle; its own admission already stored
    // (the statement look), then only this device's choice is left to record (read back) and
    // the hand-over; else this user still an owner or admin (which gates signing only), the
    // member still waiting to be let in, and the keys the question showed still listed, asked
    // again once with new ones. The run itself signs the admission first, and records the
    // choice only once it is stored.
    private val letIn = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.LetIn
            val members = circles.get().memberIdsWhole(change.circleId) ?: return WAIT
            if (members.none { it.equals(i.memberId, ignoreCase = true) }) return memberLeft(i, i.memberName)
            if (run.admissionHeld) return circles.get().grantKeyAccess(letInRequest(change, i), pendingRun = run).toOutcome(i)
            // Removed from the circle's records after the person decided:
            // [PendingLines.memberLeft]'s line.
            when (circles.get().removedSinceDecided(change.circleId, i.memberId, i.tapVersion)) {
                null -> return WAIT
                true -> return memberLeft(i, i.memberName)
                false -> Unit
            }
            if (signingKeyMissing()) return PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD)
            when (ownerOrAdmin(change.circleId)) {
                null -> return WAIT
                false -> return notOwner(i)
                true -> Unit
            }
            val now = when (i.questionKind) {
                com.pingolin.track.domain.repository.KeyAccessRequest.Reason.LATE_JOINER.name -> circles.get().lateJoinerNow(change.circleId, i.memberId)
                // "It's them" for a member the chain doesn't count: still stuck, with the keys confirmed.
                com.pingolin.track.domain.repository.KeyAccessRequest.Reason.KEY_CHANGE.name ->
                    circles.get().keyChangeLetInNow(change.circleId, i.memberId, i.sharingKey to i.signingKey)
                else -> circles.get().letInNow(change.circleId, i.memberId)
            }
            when (now) {
                null -> return WAIT
                // Let in by someone else meanwhile.
                CircleRepository.LetInNow.LET_IN -> return PendingOutcome.Done(PendingLines.alreadyLetIn(i.memberName))
                // No longer a question here (their invitation no longer holds
                // up, say): [PendingLines.notDone]'s line.
                CircleRepository.LetInNow.NOT_ASKED -> return PendingOutcome.Dropped(PendingLines.notDone(PendingInputs.subject(i)))
                // Still waiting; or this person's own choice is recorded here (by an older
                // version, which recorded it before the admission) but none of the change's
                // admissions is stored: it goes on, and signs one.
                CircleRepository.LetInNow.WAITING, CircleRepository.LetInNow.CHOSEN_HERE -> Unit
            }
            val listed = circles.get().listedKeys(change.circleId, i.memberId) ?: return WAIT
            if (!sameKeys(listed, i.sharingKey to i.signingKey)) {
                return if (i.asked) {
                    PendingOutcome.Dropped(PendingLines.letInDetailsKeepChanging(i.memberName))
                } else {
                    PendingOutcome.NeedsPerson(
                        PendingQuestions.ask(
                            PendingQuestions.Ask(
                                PendingQuestions.LET_IN, i.memberId, listed.first, listed.second,
                                circles.get().computeSafetyNumber(i.memberId, listed.first, listed.second),
                            )
                        )
                    )
                }
            }
            return circles.get().grantKeyAccess(letInRequest(change, i), pendingRun = run.copy(beforeSend = stillDecided(change.circleId, i.memberId, i.tapVersion)))
                .toOutcome(i, i.memberName)
        }
    }

    /**
     * A let-in ending, other than by its own run, with its own admission stored (a cancel, too
     * old, a refusal): the let-in went out, so this device's choice is recorded all the same
     * (read back) and the circle handed over, signing nothing. Only while the circle's complete
     * roster, fetched now, lists the member: not listed, there is nobody to choose, and it ends
     * with no choice; no complete roster, or a choice that didn't reach the disk, it waits
     * (false) and tries again.
     */
    suspend fun recordLetIn(change: PendingChangeEntity): Boolean {
        val i = PendingInputs.read(change) as? PendingInputs.LetIn ?: return true
        val members = circles.get().memberIdsWhole(change.circleId) ?: return false
        if (members.none { it.equals(i.memberId, ignoreCase = true) }) return true
        return try {
            circles.get().recordLetInChoice(change.circleId, i.memberId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** The question a let-in answers, as the live "Let them in" takes it: the keys it
     * showed.
     */
    private fun letInRequest(change: PendingChangeEntity, i: PendingInputs.LetIn) = com.pingolin.track.domain.repository.KeyAccessRequest(
        circleId = change.circleId, circleName = i.circleName, userId = i.memberId, displayName = i.memberName,
        email = null,
        reason = com.pingolin.track.domain.repository.KeyAccessRequest.Reason.entries
            .firstOrNull { it.name == i.questionKind } ?: com.pingolin.track.domain.repository.KeyAccessRequest.Reason.UNCONFIRMED_INVITER,
        sharingKeyHex = i.sharingKey, signingKeyHex = i.signingKey,
    )

    // Renew an invitation: the invitation still pending and this account's own, fetched fresh
    // with its original (never kept with the change); the renewal facts worked out now by the
    // live function; the directory still showing the keys the person saw, else asked again once.
    private val renewInvitation = object : PendingExecutor {
        override suspend fun run(change: PendingChangeEntity, inputs: PendingInputs, run: PendingRun): PendingOutcome {
            val i = inputs as PendingInputs.RenewInvitation
            // Removed from the circle's records after the person decided:
            // [PendingLines.memberLeft]'s line.
            when (circles.get().removedSinceDecided(change.circleId, i.inviteeUserId, i.tapVersion, i.sharingKey to i.signingKey)) {
                null -> return WAIT
                true -> return memberLeft(i, i.email)
                false -> Unit
            }
            if (signingKeyMissing()) return PendingOutcome.NeedsPerson(PendingQuestions.PASSWORD)
            val now = circles.get().renewalNow(
                change.circleId, i.inviteId, i.statementId, i.email, i.inviteeUserId, i.sharingKey, i.signingKey,
            )
            val question = when (now) {
                com.pingolin.track.domain.repository.CircleRepository.RenewalNow.CouldNotRead -> return WAIT
                com.pingolin.track.domain.repository.CircleRepository.RenewalNow.Ended ->
                    return PendingOutcome.Dropped(PendingLines.invitationEnded(i.email))
                is com.pingolin.track.domain.repository.CircleRepository.RenewalNow.Pending -> now.question
            }
            val signing = run.copy(beforeSend = stillDecided(change.circleId, i.inviteeUserId, i.tapVersion, i.sharingKey to i.signingKey))
            return circles.get().renewInvitation(question, pendingRun = signing).fold(onSuccess = { PendingOutcome.Done() }) { e ->
                when {
                    // Removed from the circle's records after the person decided, found at the signature:
                    // [PendingLines.memberLeft]'s line.
                    e is RemovedSinceDecided -> memberLeft(i, i.email)
                    e is com.pingolin.track.domain.repository.InviteeChangedException ->
                        // Another account behind the address, or asked once already: never renewed.
                        if (i.asked || !e.userId.equals(i.inviteeUserId, ignoreCase = true)) {
                            PendingOutcome.Dropped(PendingLines.RENEWAL_DETAILS_KEEP_CHANGING)
                        } else {
                            PendingOutcome.NeedsPerson(
                                PendingQuestions.ask(PendingQuestions.Ask(PendingQuestions.RENEWAL, e.userId, e.sharingKeyHex, e.signingKeyHex, e.number)),
                            )
                        }
                    // Renewed, withdrawn or run out meanwhile.
                    e is com.pingolin.track.data.remote.dto.RefusedException && e.status == 409 ->
                        PendingOutcome.Dropped(PendingLines.invitationEnded(i.email))
                    else -> PendingChangeRunner.classify(e, PendingInputs.subject(i), i.circleName)
                }
            }
        }
    }

    private fun sameKeys(a: Pair<String, String>, b: Pair<String, String>) =
        a.first.equals(b.first, ignoreCase = true) && a.second.equals(b.second, ignoreCase = true)

    private fun sharedWith(place: PlaceEntity): Set<String> =
        place.sharedCircles?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /** The place as the person saw it when queuing an edit ([seen]), field by field. */
    private fun stillAsSeen(place: PlaceEntity, seen: PendingInputs.EditPlace): Boolean {
        fun near(a: Double?, b: Double?) = a == b || (a != null && b != null && abs(a - b) < 1e-9)
        return place.name == seen.name && place.notes.orEmpty() == seen.notes.orEmpty() &&
            near(place.latitude, seen.latitude) && near(place.longitude, seen.longitude) &&
            place.radius == seen.radius && place.icon == seen.icon && place.color == seen.color &&
            place.boundaryRadius == seen.boundaryRadius
    }

    private companion object {
        /** The world couldn't be read whole just now: try again later, nothing sent. */
        val WAIT = PendingOutcome.Transient(maybeSent = false)
    }
}
