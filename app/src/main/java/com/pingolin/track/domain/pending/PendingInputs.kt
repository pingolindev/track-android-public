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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.pingolin.track.data.local.entity.PendingChangeEntity

/**
 * What each kind of waiting change keeps: what the person chose, as plain values, plus the
 * names its lines show ("labels": the circle, a place or a member as the person saw them).
 * Never anything sealed, signed or versioned.
 *
 * Stored as JSON with Gson. Every field a reader relies on is written, so a missing one is
 * never read as a default. Every field carries its JSON name ([SerializedName]): a release
 * build renames fields (R8), and a change kept by one build must read the same after an update.
 */
sealed interface PendingInputs {
    /** The circle's name when queued, for lines shown after the circle is gone. */
    val circleName: String

    data class Invite(
        @SerializedName("circle_name") override val circleName: String,
        /** Lower-cased. */
        @SerializedName("email") val email: String,
        /** After "It's them": the account and the keys the person confirmed; else null. */
        @SerializedName("confirmed_user_id") val confirmedUserId: String? = null,
        @SerializedName("confirmed_sharing_key") val confirmedSharingKey: String? = null,
        @SerializedName("confirmed_signing_key") val confirmedSigningKey: String? = null,
        /** Kept because the address couldn't be checked, not for catching up. */
        @SerializedName("address_check") val addressCheck: Boolean = false,
        /** The key version this device held when the person decided; null if unknown. */
        @SerializedName("tap_version") val tapVersion: Int? = null,
    ) : PendingInputs

    data class MakeAdmin(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("member_id") val memberId: String,
        @SerializedName("member_name") val memberName: String,
        /** The keys behind what the person saw: what the admission may sign, and nothing else. */
        @SerializedName("sharing_key") val sharingKey: String,
        @SerializedName("signing_key") val signingKey: String,
        /** Asked again about their keys once already: a second change drops it. */
        @SerializedName("asked") val asked: Boolean = false,
        /** The key version this device held when the person decided; null if unknown. */
        @SerializedName("tap_version") val tapVersion: Int? = null,
    ) : PendingInputs

    data class LetIn(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("member_id") val memberId: String,
        @SerializedName("member_name") val memberName: String,
        /** The question's reason, as KeyAccessRequest.Reason names it. */
        @SerializedName("question_kind") val questionKind: String,
        /** The keys the question showed. */
        @SerializedName("sharing_key") val sharingKey: String,
        @SerializedName("signing_key") val signingKey: String,
        /** Asked again about their keys once already: a second change drops it. */
        @SerializedName("asked") val asked: Boolean = false,
        /** The key version this device held when the person decided; null if unknown. */
        @SerializedName("tap_version") val tapVersion: Int? = null,
    ) : PendingInputs

    data class RenewInvitation(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("invite_id") val inviteId: String,
        @SerializedName("statement_id") val statementId: String,
        @SerializedName("invitee_user_id") val inviteeUserId: String,
        @SerializedName("email") val email: String,
        /** The keys the question showed. */
        @SerializedName("sharing_key") val sharingKey: String,
        @SerializedName("signing_key") val signingKey: String,
        /** Asked again about their keys once already: a second change drops it. */
        @SerializedName("asked") val asked: Boolean = false,
        /** Kept because the address couldn't be checked, not for catching up. */
        @SerializedName("address_check") val addressCheck: Boolean = false,
        /** The key version this device held when the person decided; null if unknown. */
        @SerializedName("tap_version") val tapVersion: Int? = null,
    ) : PendingInputs

    data class CheckIn(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("type") val type: String,
        @SerializedName("message") val message: String?,
        /** As the circle's setting allowed when queued: coarsened, or none. */
        @SerializedName("latitude") val latitude: Double?,
        @SerializedName("longitude") val longitude: Double?,
        @SerializedName("place_id") val placeId: String?,
        @SerializedName("checked_in_at") val checkedInAt: Long,
    ) : PendingInputs

    data class Destination(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("name") val name: String?,
        @SerializedName("latitude") val latitude: Double,
        @SerializedName("longitude") val longitude: Double,
        @SerializedName("eta_ms") val etaMs: Long?,
        @SerializedName("notify_arrival") val notifyArrival: Boolean,
    ) : PendingInputs

    data class Arrived(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("destination_id") val destinationId: String,
        @SerializedName("destination_name") val destinationName: String?,
        @SerializedName("arrived_at") val arrivedAt: Long,
        /** As the circle's setting allowed when queued: coarsened, or none. */
        @SerializedName("latitude") val latitude: Double?,
        @SerializedName("longitude") val longitude: Double?,
    ) : PendingInputs

    data class SharePlace(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("place_id") val placeId: String,
        @SerializedName("place_name") val placeName: String,
    ) : PendingInputs

    data class CopyPlaces(
        @SerializedName("circle_name") override val circleName: String,
        /** One place; null for all of [sourceCircleId]'s places. */
        @SerializedName("place_id") val placeId: String?,
        @SerializedName("place_name") val placeName: String?,
        @SerializedName("source_circle_id") val sourceCircleId: String?,
        /** For "all": how many places there were, for the line. */
        @SerializedName("count") val count: Int,
    ) : PendingInputs

    data class EditPlace(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("place_id") val placeId: String,
        @SerializedName("place_name") val placeName: String,
        // Only the fields the person changed; null leaves a field as it is.
        @SerializedName("name") val name: String? = null,
        @SerializedName("notes") val notes: String? = null,
        @SerializedName("latitude") val latitude: Double? = null,
        @SerializedName("longitude") val longitude: Double? = null,
        @SerializedName("radius") val radius: Int? = null,
        @SerializedName("icon") val icon: String? = null,
        @SerializedName("color") val color: String? = null,
        @SerializedName("boundary_radius") val boundaryRadius: Int? = null,
    ) : PendingInputs

    data class PlaceAlert(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("place_id") val placeId: String,
        @SerializedName("place_name") val placeName: String,
        /** The member (MEMBER_PLACE_ALERT); null for
         * CIRCLE_PLACE_ALERT (the whole circle).
         */
        @SerializedName("member_id") val memberId: String?,
        @SerializedName("member_name") val memberName: String?,
        @SerializedName("alert_on_arrival") val alertOnArrival: Boolean,
        @SerializedName("alert_on_departure") val alertOnDeparture: Boolean,
        @SerializedName("active_days") val activeDays: List<String>?,
        @SerializedName("active_start_time") val activeStartTime: String?,
        @SerializedName("active_end_time") val activeEndTime: String?,
        @SerializedName("notify_push") val notifyPush: Boolean,
        @SerializedName("notify_email") val notifyEmail: Boolean,
        @SerializedName("notify_sms") val notifySms: Boolean,
        @SerializedName("is_recurring") val isRecurring: Boolean,
        @SerializedName("expires_at") val expiresAt: String?,
    ) : PendingInputs

    data class BatteryAlert(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("member_id") val memberId: String,
        @SerializedName("member_name") val memberName: String?,
        @SerializedName("threshold") val threshold: Int,
        @SerializedName("notify_push") val notifyPush: Boolean,
        @SerializedName("notify_email") val notifyEmail: Boolean,
    ) : PendingInputs

    data class AlertChange(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("rule_id") val ruleId: String,
        /** The sealed change: the low-battery threshold. */
        @SerializedName("threshold") val threshold: Int? = null,
        /** Sent with it, as the edit sends them; null leaves a choice as it is. */
        @SerializedName("notify_push") val notifyPush: Boolean? = null,
        @SerializedName("notify_email") val notifyEmail: Boolean? = null,
    ) : PendingInputs

    data class InactivityAlert(
        @SerializedName("circle_name") override val circleName: String,
        @SerializedName("target_user_id") val targetUserId: String,
        @SerializedName("timeout_minutes") val timeoutMinutes: Int,
        @SerializedName("is_self_alert") val isSelfAlert: Boolean,
        @SerializedName("notify_push") val notifyPush: Boolean,
        @SerializedName("notify_email") val notifyEmail: Boolean,
        @SerializedName("notify_on_return") val notifyOnReturn: Boolean,
        @SerializedName("cooldown_minutes") val cooldownMinutes: Int,
        /** Empty: everyone in the circle but this user, as the members are when it runs. */
        @SerializedName("watched_user_ids") val watchedUserIds: List<String>,
        /** Their names as shown, for
         * [PendingLines.memberLeft] after they left.
         */
        @SerializedName("watched_names") val watchedNames: Map<String, String> = emptyMap(),
    ) : PendingInputs

    companion object {
        private val gson = Gson()

        fun kindOf(inputs: PendingInputs): PendingKind = when (inputs) {
            is Invite -> PendingKind.INVITE
            is MakeAdmin -> PendingKind.MAKE_ADMIN
            is LetIn -> PendingKind.LET_IN
            is RenewInvitation -> PendingKind.RENEW_INVITATION
            is CheckIn -> PendingKind.CHECK_IN
            is Destination -> PendingKind.DESTINATION
            is Arrived -> PendingKind.ARRIVED
            is SharePlace -> PendingKind.SHARE_PLACE
            is CopyPlaces -> PendingKind.COPY_PLACES
            is EditPlace -> PendingKind.EDIT_PLACE
            is PlaceAlert -> if (inputs.memberId != null) PendingKind.MEMBER_PLACE_ALERT else PendingKind.CIRCLE_PLACE_ALERT
            is BatteryAlert -> PendingKind.BATTERY_ALERT
            is AlertChange -> PendingKind.ALERT_CHANGE
            is InactivityAlert -> PendingKind.INACTIVITY_ALERT
        }

        private fun classOf(kind: PendingKind): Class<out PendingInputs> = when (kind) {
            PendingKind.INVITE -> Invite::class.java
            PendingKind.MAKE_ADMIN -> MakeAdmin::class.java
            PendingKind.LET_IN -> LetIn::class.java
            PendingKind.RENEW_INVITATION -> RenewInvitation::class.java
            PendingKind.CHECK_IN -> CheckIn::class.java
            PendingKind.DESTINATION -> Destination::class.java
            PendingKind.ARRIVED -> Arrived::class.java
            PendingKind.SHARE_PLACE -> SharePlace::class.java
            PendingKind.COPY_PLACES -> CopyPlaces::class.java
            PendingKind.EDIT_PLACE -> EditPlace::class.java
            PendingKind.MEMBER_PLACE_ALERT, PendingKind.CIRCLE_PLACE_ALERT -> PlaceAlert::class.java
            PendingKind.BATTERY_ALERT -> BatteryAlert::class.java
            PendingKind.ALERT_CHANGE -> AlertChange::class.java
            PendingKind.INACTIVITY_ALERT -> InactivityAlert::class.java
        }

        fun toJson(inputs: PendingInputs): String = gson.toJson(inputs)

        /** Null for a record this build can't read (another kind, or damaged). */
        fun read(change: PendingChangeEntity): PendingInputs? {
            val kind = PendingKind.of(change.kind) ?: return null
            return runCatching { gson.fromJson(change.inputs, classOf(kind)) }.getOrNull()
                ?.takeIf { kindOf(it) == kind || (kind == PendingKind.CIRCLE_PLACE_ALERT && it is PlaceAlert) }
        }

        /**
         * [old]'s fields with [new]'s set ones over them (EDIT_PLACE and ALERT_CHANGE merge
         * fields). Both JSON objects of the same kind's inputs.
         */
        fun mergeFields(old: String, new: String): String {
            val merged: JsonObject = JsonParser.parseString(old).asJsonObject
            val newer = JsonParser.parseString(new).asJsonObject
            for ((name, value) in newer.entrySet()) if (!value.isJsonNull) merged.add(name, value)
            return merged.toString()
        }

        /** The thing a later change of the same kind merges into; null when never merged. */
        fun target(inputs: PendingInputs): String? = when (inputs) {
            is Invite -> inputs.email
            is MakeAdmin -> inputs.memberId
            is LetIn -> inputs.memberId
            is RenewInvitation -> inputs.inviteId
            is SharePlace -> inputs.placeId
            is CopyPlaces -> inputs.placeId ?: "all:${inputs.sourceCircleId}"
            is EditPlace -> inputs.placeId
            is AlertChange -> inputs.ruleId
            else -> null
        }

        /**
         * The inputs without the time they were made, so two identical taps within 2 s are one
         * change: a check-in's and an arrival's own times differ between the taps.
         */
        fun sameTap(inputs: PendingInputs): PendingInputs = when (inputs) {
            is CheckIn -> inputs.copy(checkedInAt = 0)
            is Arrived -> inputs.copy(arrivedAt = 0)
            else -> inputs
        }

        /** The let-in subject: "Letting {name} in", also for a tap that
         * kept nothing.
         */
        fun letInSubject(name: String) = PendingLines.Subject("Letting $name in", "letting $name in", name)

        /**
         * How the lines name it ({what}): the same phrase for each kind on iOS, Android and the
         * web. [PendingLines.cancelledMaybeSent] takes it with a lower-case first letter. Place
         * edits and alert changes are singular here ("{what} wasn't done" needs a singular
         * subject); their waiting lines keep the plural, "Your changes to …".
         */
        fun subject(inputs: PendingInputs): PendingLines.Subject = when (inputs) {
            is Invite -> PendingLines.Subject("${inputs.email}'s invitation", "${inputs.email}'s invitation", "the invitation")
            is MakeAdmin -> PendingLines.Subject("Making ${inputs.memberName} an admin", "making ${inputs.memberName} an admin", inputs.memberName)
            is LetIn -> letInSubject(inputs.memberName)
            is RenewInvitation -> PendingLines.Subject("Renewing ${inputs.email}'s invitation", "renewing ${inputs.email}'s invitation", "the invitation")
            is CheckIn -> PendingLines.Subject("Your check-in", "your check-in", "the place")
            is Destination -> PendingLines.Subject("Your destination", "your destination", "the destination")
            is Arrived -> PendingLines.Subject("Your arrival", "your arrival", "the destination")
            is SharePlace -> PendingLines.Subject("Sharing ${inputs.placeName}", "sharing ${inputs.placeName}", inputs.placeName)
            is CopyPlaces -> {
                val what = inputs.placeName ?: PendingLines.places(inputs.count)
                PendingLines.Subject("Copying $what", "copying $what", inputs.placeName ?: "the place")
            }
            is EditPlace -> PendingLines.Subject("Your change to ${inputs.placeName}", "your change to ${inputs.placeName}", inputs.placeName)
            is PlaceAlert, is BatteryAlert, is InactivityAlert -> PendingLines.Subject("This alert", "this alert", "the alert")
            is AlertChange -> PendingLines.Subject("Your change to this alert", "your change to this alert", "the alert")
        }

        /** The waiting line, with the circle's name as shown now. */
        fun waitingLine(inputs: PendingInputs, circleName: String = inputs.circleName): String = when (inputs) {
            is Invite -> if (inputs.addressCheck) PendingLines.inviteWhenChecked(inputs.email) else PendingLines.invite(inputs.email)
            is MakeAdmin -> PendingLines.makeAdmin(inputs.memberName)
            is LetIn -> PendingLines.letIn(inputs.memberName)
            is RenewInvitation -> if (inputs.addressCheck) PendingLines.renewWhenChecked(inputs.email) else PendingLines.renew(inputs.email)
            is CheckIn -> PendingLines.CHECK_IN
            is Destination -> PendingLines.destination(inputs.name, circleName)
            is Arrived -> PendingLines.ARRIVED
            is SharePlace -> PendingLines.sharePlace(inputs.placeName, circleName)
            is CopyPlaces -> PendingLines.copyPlaces(inputs.placeName ?: PendingLines.places(inputs.count), circleName)
            is EditPlace -> PendingLines.editPlace(inputs.placeName)
            is PlaceAlert, is BatteryAlert, is InactivityAlert -> PendingLines.NEW_ALERT
            is AlertChange -> PendingLines.ALERT_CHANGE
        }
    }
}
