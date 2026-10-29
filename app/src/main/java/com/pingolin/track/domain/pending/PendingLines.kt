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

/**
 * Every line a change waiting to sync shows, with {device} = "phone".
 */
object PendingLines {

    const val DEVICE = "phone"

    /** The tag a waiting item carries. */
    const val TAG = "Waiting to sync"

    /** How a change is named in the lines about it: "{what}", and in lower case for
     * [cancelledMaybeSent].
     */
    data class Subject(
        val what: String,
        val whatLower: String,
        /** [deleted]'s "{place / the destination / the
         * alert}".
         */
        val thing: String,
    )

    // Waiting, in place, with the tag and Cancel.
    fun invite(email: String) = "Waiting to sync. $email will be invited as soon as this $DEVICE catches up."
    fun makeAdmin(name: String) = "Waiting to sync. $name will be made an admin as soon as this $DEVICE catches up."
    fun letIn(name: String) = "Waiting to sync. $name will be let in as soon as this $DEVICE catches up."
    fun renew(email: String) = "Waiting to sync. $email's invitation will be renewed as soon as this $DEVICE catches up."

    /** The invite and renewal lines when the address couldn't be checked. */
    fun inviteWhenChecked(email: String) = "Waiting to sync. $email will be invited as soon as Pingolin can check the address."
    fun renewWhenChecked(email: String) = "Waiting to sync. $email's invitation will be renewed as soon as Pingolin can check the address."
    val CHECK_IN = "Waiting to sync. Your check-in will be sent as soon as this $DEVICE catches up, with the time you checked in."
    fun destination(name: String?, circle: String) =
        "Waiting to sync. ${name ?: "Your destination"} will be shared with $circle as soon as this $DEVICE catches up."
    val ARRIVED = "Waiting to sync. Your arrival will be sent as soon as this $DEVICE catches up."
    fun sharePlace(place: String, circle: String) =
        "Waiting to sync. $place will be shared with $circle as soon as this $DEVICE catches up."
    fun copyPlaces(placeOrCount: String, circle: String) =
        "Waiting to sync. $placeOrCount will be copied to $circle as soon as this $DEVICE catches up."
    fun places(n: Int) = "$n places"
    fun editPlace(place: String) = "Waiting to sync. Your changes to $place will be saved as soon as this $DEVICE catches up."
    val NEW_ALERT = "Waiting to sync. This alert will be set up as soon as this $DEVICE catches up."
    val ALERT_CHANGE = "Waiting to sync. Your changes to this alert will be saved as soon as this $DEVICE catches up."

    // A pending item's state.
    const val SENDING = "Sending…"
    const val CANCELLING = "Cancelling…"
    const val WAITING_FOR_PASSWORD = "Waiting for your password"

    /** On the circle's "Waiting to sync" notice. */
    fun count(n: Int) = "$n ${if (n == 1) "change" else "changes"} waiting to go out from this $DEVICE"

    // Not done; one line replaces the pending item.
    fun tooOld(s: Subject) = "${s.what} wasn't done: this $DEVICE took too long to catch up."
    fun alreadyIn(email: String, circle: String) = "$email is already in $circle."
    fun alreadyInvited(email: String, circle: String) = "$email already has an invitation to $circle."
    fun alreadyLetIn(name: String) = "$name was already let in."
    fun alreadyShared(place: String, circle: String) = "$place is already shared with $circle."
    fun memberLeft(s: Subject, name: String, circle: String) = "${s.what} wasn't done: $name is no longer in $circle."
    fun invitationEnded(email: String) = "$email's invitation wasn't renewed: it was withdrawn or ran out."
    fun deleted(s: Subject) = "${s.what} wasn't done: ${s.thing} was deleted."
    fun placeChanged(place: String) = "Your changes to $place weren't saved: it was changed on another device in the meantime."
    /** [placeChanged]'s line for an alert change, in the singular. */
    const val ALERT_CHANGED = "Your change to this alert wasn't saved: it was changed on another device in the meantime."
    fun notOwner(s: Subject, circle: String) = "${s.what} wasn't done: you're no longer an owner or admin of $circle."
    fun limit(s: Subject, circle: String, n: Long, things: String) =
        "${s.what} wasn't done: $circle has reached its limit of $n $things on the free plan."
    fun circleGone(s: Subject, circle: String) = "${s.what} wasn't done: $circle no longer exists."
    fun inviteDetailsKeepChanging(email: String) = "Their details keep changing, so $email wasn't invited."
    fun adminDetailsKeepChanging(name: String) = "Their details keep changing, so $name wasn't made an admin."
    fun letInDetailsKeepChanging(name: String) = "Their details keep changing, so $name wasn't let in."
    const val RENEWAL_DETAILS_KEEP_CHANGING = "Their details keep changing, so the invitation wasn't renewed."
    fun notDone(s: Subject) = "${s.what} wasn't done."
    /**
     * The server's cap on invitation e-mails (`invite_limit_reached`); the words follow the
     * item's {what}. Both windows slide, so the line names no day.
     */
    fun inviteLimit(s: Subject) = "${s.what} wasn't done: too many invitations in the last 24 hours. Try again later."

    // Cancel after a lost answer.
    fun alreadyWentOut(s: Subject) = "${s.what} had already gone out, so it couldn't be cancelled."
    fun cancelledMaybeSent(s: Subject) = "Cancelled, but ${s.whatLower} may already have gone out. If it did, it shows as usual."

    /** Refused at once, nothing queued. */
    fun tooMany(circle: String) = "Not done: 20 changes are already waiting to go out for $circle from this $DEVICE."

    /** The sign-out confirmation's extra line. */
    fun signOut(n: Int) = "$n ${if (n == 1) "change" else "changes"} waiting to sync on this $DEVICE won't be made."

    /** Removing a member while behind; the removal's own line follows it. */
    fun removed(name: String, circle: String) = "$name was removed from $circle."
}
