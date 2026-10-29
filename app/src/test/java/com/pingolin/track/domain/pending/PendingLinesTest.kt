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

import com.pingolin.track.data.local.entity.DroppedNoticeEntity
import com.pingolin.track.data.local.entity.PendingChangeEntity
import com.pingolin.track.ui.pending.PendingItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The waiting lines, word for word, with {device} = "phone", and how a pending item shows
 * its state.
 */
class PendingLinesTest {

    @Test
    fun `the waiting lines`() {
        assertEquals("Waiting to sync. ann@x.test will be invited as soon as this phone catches up.", PendingLines.invite("ann@x.test"))
        assertEquals("Waiting to sync. Sam will be made an admin as soon as this phone catches up.", PendingLines.makeAdmin("Sam"))
        assertEquals("Waiting to sync. Sam will be let in as soon as this phone catches up.", PendingLines.letIn("Sam"))
        assertEquals("Waiting to sync. ann@x.test's invitation will be renewed as soon as this phone catches up.", PendingLines.renew("ann@x.test"))
        assertEquals("Waiting to sync. Your check-in will be sent as soon as this phone catches up, with the time you checked in.", PendingLines.CHECK_IN)
        assertEquals("Waiting to sync. Gym will be shared with Family as soon as this phone catches up.", PendingLines.destination("Gym", "Family"))
        assertEquals("Waiting to sync. Your destination will be shared with Family as soon as this phone catches up.", PendingLines.destination(null, "Family"))
        assertEquals("Waiting to sync. Your arrival will be sent as soon as this phone catches up.", PendingLines.ARRIVED)
        assertEquals("Waiting to sync. Home will be shared with Family as soon as this phone catches up.", PendingLines.sharePlace("Home", "Family"))
        assertEquals("Waiting to sync. Home will be copied to Family as soon as this phone catches up.", PendingLines.copyPlaces("Home", "Family"))
        assertEquals("Waiting to sync. 3 places will be copied to Family as soon as this phone catches up.", PendingLines.copyPlaces(PendingLines.places(3), "Family"))
        assertEquals("Waiting to sync. Your changes to Home will be saved as soon as this phone catches up.", PendingLines.editPlace("Home"))
        assertEquals("Waiting to sync. This alert will be set up as soon as this phone catches up.", PendingLines.NEW_ALERT)
        assertEquals("Waiting to sync. Your changes to this alert will be saved as soon as this phone catches up.", PendingLines.ALERT_CHANGE)
    }

    @Test
    fun `a pending item's state`() {
        assertEquals("Sending…", PendingLines.SENDING)
        assertEquals("Cancelling…", PendingLines.CANCELLING)
        assertEquals("Waiting for your password", PendingLines.WAITING_FOR_PASSWORD)
        assertEquals("1 change waiting to go out from this phone", PendingLines.count(1))
        assertEquals("4 changes waiting to go out from this phone", PendingLines.count(4))
    }

    @Test
    fun `the not-done lines`() {
        val checkIn = PendingInputs.subject(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0))
        val alert = PendingInputs.subject(PendingInputs.BatteryAlert("Family", "sam", "Sam", 20, true, false))
        val destination = PendingInputs.subject(PendingInputs.Arrived("Family", "d", null, 0, null, null))
        val place = PendingInputs.subject(PendingInputs.SharePlace("Family", "p", "Home"))
        assertEquals("Your check-in wasn't done: this phone took too long to catch up.", PendingLines.tooOld(checkIn))
        assertEquals("ann@x.test is already in Family.", PendingLines.alreadyIn("ann@x.test", "Family"))
        assertEquals("ann@x.test already has an invitation to Family.", PendingLines.alreadyInvited("ann@x.test", "Family"))
        assertEquals("Sam was already let in.", PendingLines.alreadyLetIn("Sam"))
        assertEquals("Home is already shared with Family.", PendingLines.alreadyShared("Home", "Family"))
        assertEquals("This alert wasn't done: Sam is no longer in Family.", PendingLines.memberLeft(alert, "Sam", "Family"))
        assertEquals("ann@x.test's invitation wasn't renewed: it was withdrawn or ran out.", PendingLines.invitationEnded("ann@x.test"))
        assertEquals("Sharing Home wasn't done: Home was deleted.", PendingLines.deleted(place))
        assertEquals("Your arrival wasn't done: the destination was deleted.", PendingLines.deleted(destination))
        assertEquals("This alert wasn't done: the alert was deleted.", PendingLines.deleted(alert))
        assertEquals("Your changes to Home weren't saved: it was changed on another device in the meantime.", PendingLines.placeChanged("Home"))
        assertEquals("This alert wasn't done: you're no longer an owner or admin of Family.", PendingLines.notOwner(alert, "Family"))
        assertEquals("This alert wasn't done: Family has reached its limit of 3 alerts on the free plan.", PendingLines.limit(alert, "Family", 3, "alerts"))
        assertEquals("This alert wasn't done: Family no longer exists.", PendingLines.circleGone(alert, "Family"))
        assertEquals("Their details keep changing, so ann@x.test wasn't invited.", PendingLines.inviteDetailsKeepChanging("ann@x.test"))
        assertEquals("Their details keep changing, so Sam wasn't made an admin.", PendingLines.adminDetailsKeepChanging("Sam"))
        assertEquals("Their details keep changing, so Sam wasn't let in.", PendingLines.letInDetailsKeepChanging("Sam"))
        assertEquals("Their details keep changing, so the invitation wasn't renewed.", PendingLines.RENEWAL_DETAILS_KEEP_CHANGING)
        assertEquals("Your check-in wasn't done.", PendingLines.notDone(checkIn))
    }

    @Test
    fun `an invitation past the invitation e-mail cap, with no day named`() {
        assertEquals(
            "ann@x.test's invitation wasn't done: too many invitations in the last 24 hours. Try again later.",
            PendingLines.inviteLimit(PendingInputs.subject(PendingInputs.Invite("Family", "ann@x.test"))),
        )
    }

    @Test
    fun `the {what} of every kind, at the start of a line and in lower case inside one`() {
        fun what(inputs: PendingInputs) = PendingInputs.subject(inputs).let { it.what to it.whatLower }
        val alert = "This alert" to "this alert"
        assertEquals("ann@x.test's invitation" to "ann@x.test's invitation", what(PendingInputs.Invite("Family", "ann@x.test")))
        assertEquals("Making Sam an admin" to "making Sam an admin", what(PendingInputs.MakeAdmin("Family", "sam", "Sam", "s", "g")))
        assertEquals("Letting Sam in" to "letting Sam in", what(PendingInputs.LetIn("Family", "sam", "Sam", "q", "s", "g")))
        assertEquals(
            "Renewing ann@x.test's invitation" to "renewing ann@x.test's invitation",
            what(PendingInputs.RenewInvitation("Family", "i", "st", "ann", "ann@x.test", "s", "g")),
        )
        assertEquals("Your check-in" to "your check-in", what(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0)))
        assertEquals(
            "a named destination is still \"Your destination\"",
            "Your destination" to "your destination",
            what(PendingInputs.Destination("Family", "Gym", 1.0, 2.0, null, true)),
        )
        assertEquals("Your arrival" to "your arrival", what(PendingInputs.Arrived("Family", "d", "Gym", 0, null, null)))
        assertEquals("Sharing Home" to "sharing Home", what(PendingInputs.SharePlace("Family", "p", "Home")))
        assertEquals("Copying Home" to "copying Home", what(PendingInputs.CopyPlaces("Family", "p", "Home", "c-2", 1)))
        assertEquals("Copying 3 places" to "copying 3 places", what(PendingInputs.CopyPlaces("Family", null, null, "c-2", 3)))
        assertEquals("Your change to Home" to "your change to Home", what(PendingInputs.EditPlace("Family", "p", "Home", name = "Home 2")))
        assertEquals(alert, what(PendingInputs.PlaceAlert("Family", "p", "Home", "sam", "Sam", true, false, null, null, null, true, false, false, true, null)))
        assertEquals(alert, what(PendingInputs.PlaceAlert("Family", "p", "Home", null, null, true, false, null, null, null, true, false, false, true, null)))
        assertEquals(alert, what(PendingInputs.BatteryAlert("Family", "sam", "Sam", 20, true, false)))
        assertEquals("Your change to this alert" to "your change to this alert", what(PendingInputs.AlertChange("Family", "r", threshold = 15)))
        assertEquals(alert, what(PendingInputs.InactivityAlert("Family", "sam", 60, false, true, false, false, 60, listOf("sam"))))

        assertEquals(
            "Your change to Home wasn't done: this phone took too long to catch up.",
            PendingLines.tooOld(PendingInputs.subject(PendingInputs.EditPlace("Family", "p", "Home", name = "Home 2"))),
        )
        assertEquals(
            "Cancelled, but your change to this alert may already have gone out. If it did, it shows as usual.",
            PendingLines.cancelledMaybeSent(PendingInputs.subject(PendingInputs.AlertChange("Family", "r", threshold = 15))),
        )
    }

    @Test
    fun `Cancel after a lost answer, refused at once, sign-out and removal`() {
        val invite = PendingInputs.subject(PendingInputs.Invite("Family", "ann@x.test"))
        val checkIn = PendingInputs.subject(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0))
        assertEquals("ann@x.test's invitation had already gone out, so it couldn't be cancelled.", PendingLines.alreadyWentOut(invite))
        assertEquals("Cancelled, but your check-in may already have gone out. If it did, it shows as usual.", PendingLines.cancelledMaybeSent(checkIn))
        assertEquals("Not done: 20 changes are already waiting to go out for Family from this phone.", PendingLines.tooMany("Family"))
        assertEquals("1 change waiting to sync on this phone won't be made.", PendingLines.signOut(1))
        assertEquals("2 changes waiting to sync on this phone won't be made.", PendingLines.signOut(2))
        assertEquals("Sam was removed from Family.", PendingLines.removed("Sam", "Family"))
    }

    @Test
    fun `an item shows what its change is doing, and a settling record shows nothing`() {
        fun change(id: String, state: String, question: String? = null) = PendingChangeEntity(
            id = id, account = "me", circleId = "c-1", kind = PendingKind.CHECK_IN.name,
            inputs = PendingInputs.toJson(PendingInputs.CheckIn("Family", "t", null, null, null, null, 0)),
            createdAt = 0, expiresAt = 1, state = state, question = question,
        )
        val items = PendingItem.of(
            listOf(
                change("w", PendingChanges.WAITING),
                change("r", PendingChanges.RUNNING),
                change("c", PendingChanges.CANCELLING),
                change("p", PendingChanges.NEEDS_PERSON, PendingItem.PASSWORD),
                change("s", PendingChanges.SETTLING),
            ),
            listOf(
                DroppedNoticeEntity("n1", "me", "c-1", PendingKind.CHECK_IN.name, null, "fresh", 0),
                DroppedNoticeEntity("n2", "me", "c-1", PendingKind.CHECK_IN.name, null, "seen long ago", 0, seenAt = 0),
            ),
            mapOf("c-1" to "Family"),
            now = 10 * 60_000L,
        )

        assertEquals(
            listOf(
                PendingItem.Waiting("w", "c-1", "Waiting to sync", PendingLines.CHECK_IN, cancellable = true),
                PendingItem.Waiting("r", "c-1", "Sending…", PendingLines.CHECK_IN, cancellable = false),
                PendingItem.Waiting("c", "c-1", "Cancelling…", PendingLines.CHECK_IN, cancellable = false),
                PendingItem.Waiting("p", "c-1", "Waiting for your password", PendingLines.CHECK_IN, cancellable = true),
                PendingItem.Notice("n1", "c-1", "fresh"),
            ),
            items,
        )
    }
}
