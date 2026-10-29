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

package com.pingolin.track.data.remote.api

import android.util.Log
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.CreateCircleRequest
import com.pingolin.track.data.remote.dto.MemberPublicKeyResponse
import com.pingolin.track.data.remote.dto.MemberResponse
import com.pingolin.track.data.remote.dto.NotificationResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsRequest
import com.pingolin.track.data.remote.dto.ServedCircleHead
import com.pingolin.track.util.AccountIds
import retrofit2.Response

/**
 * [TrackApi] with every account id that feeds trust in its canonical form (AccountIds) where it
 * is decoded: the member list, the member keys, the copies of a circle's key and who sent them,
 * the heads the records serve, and the notification rows. Every "is this me?" and every compare
 * with a member after this reads one spelling per account: the server can't spell this
 * account's own entry so that it's taken for a stranger, and pinned.
 *
 * A value that is no UUID in any spelling the SDK takes refuses its entry here, counted in the
 * log and never passed on: a member or a key the list drops, as if the server hadn't sent it. The
 * refusal never reads as an answer the server didn't give: a copy riding with a circle is dropped
 * and the circle kept (dropping it would read as "left"), a notification loses the id and keeps
 * its row (a shorter page would read as rows deleted), and the personal copy's reply is no answer
 * at all (an empty one would read as "none published", and a new key would be made over it).
 *
 * A circle, a key copy, a circle's records or an invitation that names the personal key's slot
 * (AccountIds.isPersonalSlot: the nil UUID in any spelling, or "self") is refused here too, counted
 * and logged: it is no circle, and nothing may be wrapped, stored, linked or derived for it as one.
 * A list loses the entry, and the rest of it stands; a reply about that one circle is no answer.
 *
 * Every CIRCLE id a reply names comes out in its one spelling too, one that is no UUID refused the
 * same way: the circle list, one circle and a new one, the key copies, the records, invitations,
 * ownership claims, location consent and the notification rows. A circle the server spells a
 * second way would otherwise be a second circle here, with its real pins, no records and empty
 * trust records.
 */
class CanonicalIdsTrackApi(
    private val api: TrackApi,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) : TrackApi by api {

    override suspend fun getCircleMembers(circleId: String, limit: Int?, cursor: String?) =
        api.getCircleMembers(circleId, limit, cursor).canonical("member list") { page, refused ->
            page.copy(items = page.items.mapNotNull { member(it, refused) })
        }

    override suspend fun getMemberPublicKeys(circleId: String) =
        api.getMemberPublicKeys(circleId).canonical("member keys") { keys, refused -> keys.mapNotNull { memberKey(it, refused) } }

    override suspend fun getCircleKeys() =
        api.getCircleKeys().canonical("circle keys") { bundles, refused ->
            bundles.mapNotNull { b -> b.inCircle(b.circleId, refused) { copy(circleId = it) }?.let { bundle(it, refused) } }
        }

    override suspend fun getSelfKey() =
        api.getSelfKey().canonical<CircleKeyBundleResponse?>("personal copy") { copy, refused -> copy?.let { bundle(it, refused) ?: throw Refused() } }

    override suspend fun createCircle(request: CreateCircleRequest) =
        api.createCircle(request).canonical("new circle") { circle, refused -> circle(circle, refused) ?: throw Refused() }

    override suspend fun getCircles() =
        api.getCircles().canonical("circle list") { page, refused -> page.copy(items = page.items.mapNotNull { circle(it, refused) }) }

    override suspend fun getCirclesPaginated(cursor: String?, limit: Int) =
        api.getCirclesPaginated(cursor, limit).canonical("circle list") { page, refused -> page.copy(items = page.items.mapNotNull { circle(it, refused) }) }

    override suspend fun getCircle(id: String) =
        api.getCircle(id).canonical("circle") { circle, refused -> circle(circle, refused) ?: throw Refused() }

    override suspend fun refreshRecords(request: RefreshRecordsRequest) =
        api.refreshRecords(request).canonical("refresh records") { reply, refused ->
            reply.copy(
                circles = reply.circles.mapNotNull { circle ->
                    val id = circleId(circle.circleId, refused) ?: return@mapNotNull null
                    circle.copy(circleId = id, heads = circle.heads.mapNotNull { head(it, refused) })
                },
            )
        }

    override suspend fun getReceivedInvites() =
        api.getReceivedInvites().canonical("received invitations") { invites, refused ->
            invites.mapNotNull { it.inCircle(it.circleId, refused) { c -> copy(circleId = c) } }
        }

    /**
     * The invitation just sent. The server's answer names no circle ([com.pingolin.track.data.remote.dto.InviteResponse]):
     * it is the answer for the circle it was sent to. One that does name a circle must name that
     * one, in its one spelling; any other answer is none.
     */
    override suspend fun inviteToCircle(circleId: String, request: com.pingolin.track.data.remote.dto.InviteRequest) =
        api.inviteToCircle(circleId, request).canonical("invitation") { invite, refused ->
            val raw = invite.circleId ?: return@canonical invite
            val named = invite.inCircle(raw, refused) { copy(circleId = it) } ?: throw Refused()
            if (!AccountIds.same(named.circleId, circleId)) throw Refused("it names another circle than the one it was sent in")
            named
        }

    override suspend fun getOwnershipClaim(circleId: String) =
        api.getOwnershipClaim(circleId).canonical("ownership claim") { claim, refused -> ownershipClaim(claim, refused) }

    override suspend fun claimOwnership(circleId: String) =
        api.claimOwnership(circleId).canonical("ownership claim") { claim, refused -> ownershipClaim(claim, refused) }

    override suspend fun getLocationConsent(circleId: String) =
        api.getLocationConsent(circleId).canonical("location consent") { consent, refused ->
            consent.inCircle(consent.circleId, refused) { copy(circleId = it) } ?: throw Refused()
        }

    // ---- content: one namespace in Room ----
    //
    // Not trust-bearing, but the SDK finds a key by any spelling, so a row filed here
    // under a second spelling would decrypt into a second namespace: rules, alerts, destinations and
    // consent checks scoped to a circle that isn't one. A row naming no circle a circle may have is
    // refused as the trust entries' are.

    override suspend fun getPlaces(circleId: String?, limit: Int?, offset: Int?) =
        api.getPlaces(circleId, limit, offset).canonical("places") { places, refused -> places.mapNotNull { place(it, refused) } }

    override suspend fun updatePlace(id: String, request: com.pingolin.track.data.remote.dto.UpdatePlaceRequest) =
        api.updatePlace(id, request).canonical("place") { place, refused -> place(place, refused) ?: throw Refused() }

    override suspend fun getPlaceAlerts(limit: Int?) =
        api.getPlaceAlerts(limit).canonical("place alerts") { alerts, refused ->
            alerts.mapNotNull { a -> a.inOptionalCircle(a.circleId, refused) { copy(circleId = it) } }
        }

    override suspend fun getPlaceAlert(id: String) =
        api.getPlaceAlert(id).canonical("place alert") { a, refused -> a.inOptionalCircle(a.circleId, refused) { copy(circleId = it) } ?: throw Refused() }

    override suspend fun createCheckIn(request: com.pingolin.track.data.remote.dto.CreateCheckInRequest) =
        api.createCheckIn(request).canonical("check-in") { c, refused -> checkIn(c, refused) ?: throw Refused() }

    override suspend fun getMyCheckIns(limit: Int) =
        api.getMyCheckIns(limit).canonical("check-ins") { list, refused -> list.mapNotNull { checkIn(it, refused) } }

    override suspend fun getCircleCheckIns(circleId: String, limit: Int) =
        api.getCircleCheckIns(circleId, limit).canonical("check-ins") { list, refused -> list.mapNotNull { checkIn(it, refused) } }

    override suspend fun triggerSos(request: com.pingolin.track.data.remote.dto.TriggerSosRequest) = api.triggerSos(request).oneSos()
    override suspend fun getActiveSosAlerts() =
        api.getActiveSosAlerts().canonical("SOS alerts") { list, refused -> list.mapNotNull { s -> s.inCircle(s.circleId, refused) { copy(circleId = it) } } }
    override suspend fun getSosAlert(id: String) = api.getSosAlert(id).oneSos()
    override suspend fun acknowledgeSos(id: String) = api.acknowledgeSos(id).oneSos()
    override suspend fun dismissSos(id: String) = api.dismissSos(id).oneSos()
    override suspend fun undismissSos(id: String) = api.undismissSos(id).oneSos()
    override suspend fun resolveSos(id: String) = api.resolveSos(id).oneSos()
    override suspend fun cancelSos(id: String) = api.cancelSos(id).oneSos()
    override suspend fun resealSos(id: String, request: com.pingolin.track.data.remote.dto.ResealSosRequest) =
        api.resealSos(id, request).oneSos()

    private fun Response<ApiResponse<com.pingolin.track.data.remote.dto.SosAlertResponse>>.oneSos() =
        canonical("SOS alert") { s, refused -> s.inCircle(s.circleId, refused) { copy(circleId = it) } ?: throw Refused() }

    override suspend fun getPrivacySettings(circleId: String) = api.getPrivacySettings(circleId).onePrivacy()
    override suspend fun updatePrivacySettings(circleId: String, request: com.pingolin.track.data.remote.dto.UpdatePrivacyRequest) =
        api.updatePrivacySettings(circleId, request).onePrivacy()
    override suspend fun enableBubble(circleId: String, request: com.pingolin.track.data.remote.dto.EnableBubbleRequest) =
        api.enableBubble(circleId, request).onePrivacy()
    override suspend fun pauseSharing(circleId: String, request: com.pingolin.track.data.remote.dto.PauseSharingRequest) =
        api.pauseSharing(circleId, request).onePrivacy()
    override suspend fun resumeSharing(circleId: String) = api.resumeSharing(circleId).onePrivacy()

    private fun Response<ApiResponse<com.pingolin.track.data.remote.dto.PrivacySettingsResponse>>.onePrivacy() =
        canonical("privacy") { p, refused -> p.inCircle(p.circleId, refused) { copy(circleId = it) } ?: throw Refused() }

    override suspend fun getBoundaryEvents(circleId: String, placeId: String?, limit: Int) =
        api.getBoundaryEvents(circleId, placeId, limit).canonical("boundary events") { list, refused ->
            list.mapNotNull { e -> e.inCircle(e.circleId, refused) { copy(circleId = it) } }
        }

    override suspend fun getBoundaryStates(circleId: String) =
        api.getBoundaryStates(circleId).canonical("boundary states") { list, refused ->
            list.mapNotNull { e -> e.inCircle(e.circleId, refused) { copy(circleId = it) } }
        }

    override suspend fun createDestination(request: com.pingolin.track.data.remote.dto.CreateDestinationRequest) = api.createDestination(request).oneDestination()
    override suspend fun getDestinations() =
        api.getDestinations().canonical("destinations") { list, refused -> list.mapNotNull { destination(it, refused) } }
    override suspend fun getDestination(id: String) = api.getDestination(id).oneDestination()
    override suspend fun updateDestination(id: String, request: com.pingolin.track.data.remote.dto.UpdateDestinationRequest) =
        api.updateDestination(id, request).oneDestination()

    private fun Response<ApiResponse<com.pingolin.track.data.remote.dto.DestinationResponse>>.oneDestination() =
        canonical("destination") { d, refused -> destination(d, refused) ?: throw Refused() }

    /**
     * [destination] with its circle and its creator (the server's `user_id`) in their one
     * spelling; null, refused, when either is there and names no circle or account. A creator
     * the server left out stays out: then the destination is nobody's here.
     */
    private fun destination(
        destination: com.pingolin.track.data.remote.dto.DestinationResponse,
        refusals: Refusals,
    ): com.pingolin.track.data.remote.dto.DestinationResponse? {
        val named = destination.inCircle(destination.circleId, refusals) { copy(circleId = it) } ?: return null
        val raw = named.userId ?: return named
        val creator = AccountIds.canonical(raw) ?: return refusals.refuse()
        return if (creator == raw) named else named.copy(userId = creator)
    }

    override suspend fun getInactivityAlerts(limit: Int, offset: Int, cursorDayBucket: Int?, cursorId: String?) =
        api.getInactivityAlerts(limit, offset, cursorDayBucket, cursorId).canonical("inactivity alerts") { list, refused ->
            list.mapNotNull { a -> a.inCircle(a.circleId, refused) { copy(circleId = it) } }
                // A page every entry of which is refused is no answer, not an empty
                // page: the refresh reads to an empty page and takes the list for
                // whole there, and a whole list drops what it doesn't name.
                .also { if (it.isEmpty() && list.isNotEmpty()) throw Refused("every entry refused") }
        }

    override suspend fun getInactivityAlert(id: String) =
        api.getInactivityAlert(id).canonical("inactivity alert") { a, refused -> a.inCircle(a.circleId, refused) { copy(circleId = it) } ?: throw Refused() }

    override suspend fun getCircleSelfAlerts() =
        api.getCircleSelfAlerts().canonical("circle self alerts") { list, refused ->
            list.mapNotNull { a -> a.inCircle(a.circleId, refused) { copy(circleId = it) } }
        }

    override suspend fun getAlertRules(cursor: String?, limit: Int) =
        api.getAlertRules(cursor, limit).canonical("alert rules") { page, refused ->
            page.copy(items = page.items.mapNotNull { r -> r.inOptionalCircle(r.circleId, refused) { copy(circleId = it) } })
        }

    override suspend fun getAlertRule(id: String) =
        api.getAlertRule(id).canonical("alert rule") { r, refused -> r.inOptionalCircle(r.circleId, refused) { copy(circleId = it) } ?: throw Refused() }

    /** A row whose circle may be absent (a personal one): none stays none; one naming no circle refuses the row. */
    private inline fun <T> T.inOptionalCircle(raw: String?, refusals: Refusals, copy: T.(String) -> T): T? =
        if (raw == null) this else inCircle(raw, refusals, copy)

    /**
     * [place] with its circles in their one spelling. Its own circle and the circle it's unwrapped
     * through may be the nil UUID, the personal key's (PlaceRepository's PERSONAL_CIRCLE_UUID):
     * any spelling of it comes out as that. Anything else that is no UUID refuses the row; a circle
     * it's shared into that is none leaves that share out.
     */
    private fun place(place: com.pingolin.track.data.remote.dto.PlaceResponse, refusals: Refusals): com.pingolin.track.data.remote.dto.PlaceResponse? {
        val circle = place.circleId?.let { personalOrCircle(it, refusals) ?: return null }
        val via = place.wrapViaCircle?.let { personalOrCircle(it, refusals) ?: return null }
        val shared = place.sharedCircles?.mapNotNull { circleId(it, refusals) }
        return if (circle == place.circleId && via == place.wrapViaCircle && shared == place.sharedCircles) place
        else place.copy(circleId = circle, wrapViaCircle = via, sharedCircles = shared)
    }

    /** A check-in's circle, which may be the personal key's (a personal check-in), as a place's. */
    private fun checkIn(checkIn: com.pingolin.track.data.remote.dto.CheckInResponse, refusals: Refusals): com.pingolin.track.data.remote.dto.CheckInResponse? {
        val raw = checkIn.circleId ?: return checkIn
        val circle = personalOrCircle(raw, refusals) ?: return null
        return if (circle == raw) checkIn else checkIn.copy(circleId = circle)
    }

    /** [raw] where the nil UUID names the personal key: any UUID spelling in its one spelling; null, counted, for anything else. */
    private fun personalOrCircle(raw: String, refusals: Refusals): String? = AccountIds.canonical(raw) ?: refusals.notACircle()

    override suspend fun getNotifications(limit: Int, offset: Int, unreadOnly: Boolean) =
        api.getNotifications(limit, offset, unreadOnly).canonical("notifications") { list, refused ->
            list.copy(notifications = list.notifications.map { notification(it, refused) })
        }

    /** Counts the ids a reply's entries were refused for. */
    private class Refusals {
        var count = 0

        /** Entries refused for naming the personal key's slot as a circle. */
        var slots = 0

        /** Entries refused for a circle id that is no UUID. */
        var circles = 0

        /** One more refused; null, for the entry that goes. */
        fun <T> refuse(): T? {
            count++
            return null
        }

        /** One more refused for naming the personal key's slot; null, for the entry that goes. */
        fun <T> slot(): T? {
            slots++
            return null
        }

        /** One more refused for a circle id that is no UUID; null, for the entry that goes. */
        fun <T> notACircle(): T? {
            circles++
            return null
        }
    }

    /** A reply whose ids can't be taken at all: no answer. [why] says so in the log where no count does. */
    private class Refused(val why: String? = null) : RuntimeException()

    /**
     * This reply with [map] applied to its data, and its refusals logged. An error, or a reply
     * without data, passes as it is. One [map] can't read (a field the server left out that the
     * app can't do without, or [Refused]) makes the reply no answer: never a partial one.
     */
    private inline fun <T> Response<ApiResponse<T>>.canonical(
        what: String,
        map: (T, Refusals) -> T,
    ): Response<ApiResponse<T>> {
        val body = body()
        val data = body?.data
        if (!isSuccessful || body == null || data == null) return this
        val refusals = Refusals()
        val mapped = try {
            map(data, refusals)
        } catch (e: RuntimeException) {
            log(
                when {
                    e is Refused && e.why != null -> "$what: ${e.why}; not taken"
                    refusals.slots > 0 -> "$what: it names the personal key's slot as a circle; not taken"
                    refusals.circles > 0 -> "$what: its circle id isn't a UUID; not taken"
                    else -> "$what: its account ids couldn't be read; not taken"
                }
            )
            // No message of the server's own: each caller says it couldn't load, in its own words.
            return Response.success(body.copy(success = false, data = null, error = null), raw())
        }
        if (refusals.count > 0) log("$what: ${refusals.count} account id(s) that aren't UUIDs; their entries refused")
        if (refusals.slots > 0) log("$what: ${refusals.slots} entr(ies) naming the personal key's slot as a circle; refused")
        if (refusals.circles > 0) log("$what: ${refusals.circles} circle id(s) that aren't UUIDs; their entries refused")
        return Response.success(body.copy(data = mapped), raw())
    }

    private fun member(member: MemberResponse, refusals: Refusals): MemberResponse? {
        val id = AccountIds.canonical(member.userId) ?: return refusals.refuse()
        // No signer on record is the strict reading: the copy they hold then proves nothing.
        val signer = member.wrapSignerUserId?.let { AccountIds.canonical(it) ?: refusals.refuse() }
        return if (id == member.userId && signer == member.wrapSignerUserId) member else member.copy(userId = id, wrapSignerUserId = signer)
    }

    private fun memberKey(key: MemberPublicKeyResponse, refusals: Refusals): MemberPublicKeyResponse? {
        val id = AccountIds.canonical(key.userId) ?: return refusals.refuse()
        return if (id == key.userId) key else key.copy(userId = id)
    }

    /** [bundle] with its sender and recipient canonical; null when either is there and isn't a UUID. */
    private fun bundle(bundle: CircleKeyBundleResponse, refusals: Refusals): CircleKeyBundleResponse? {
        val sender = bundle.senderUserId?.let { AccountIds.canonical(it) ?: return refusals.refuse() }
        val recipient = bundle.recipientUserId?.let { AccountIds.canonical(it) ?: return refusals.refuse() }
        return if (sender == bundle.senderUserId && recipient == bundle.recipientUserId) bundle
        else bundle.copy(senderUserId = sender, recipientUserId = recipient)
    }

    /**
     * [raw], a circle's id as served, in its one spelling (AccountIds.circle); null, refused and
     * counted, when it names no circle: the personal key's slot in any spelling, or no UUID.
     */
    private fun circleId(raw: String, refusals: Refusals): String? = when {
        AccountIds.isPersonalSlot(raw) -> refusals.slot()
        else -> AccountIds.circle(raw) ?: refusals.notACircle()
    }

    /** This entry with [raw], its circle id, in its one spelling ([circleId]); null when that names no circle. */
    private inline fun <T> T.inCircle(raw: String, refusals: Refusals, copy: T.(String) -> T): T? =
        circleId(raw, refusals)?.let { if (it == raw) this else copy(it) }

    /** [claim] with its circle, if it names one, in its one spelling; no answer when that is no circle. */
    private fun ownershipClaim(claim: com.pingolin.track.data.remote.dto.OwnershipClaimResponse, refusals: Refusals) =
        claim.circleId?.let { raw -> claim.inCircle(raw, refusals) { copy(circleId = it) } ?: throw Refused() } ?: claim

    /**
     * [circle] with its id in its one spelling and its copy's sender canonical, or without the copy
     * when the sender isn't a UUID; null when its id names no circle ([circleId]).
     */
    private fun circle(circle: CircleResponse, refusals: Refusals): CircleResponse? {
        val named = circle.inCircle(circle.id, refusals) { copy(id = it) } ?: return null
        val raw = named.senderUserId ?: return named
        val sender = AccountIds.canonical(raw)
            ?: return named.copy(encryptedKey = null, ephemeralPublicKey = null, senderUserId = refusals.refuse(), wrapSignature = null)
        return if (sender == raw) named else named.copy(senderUserId = sender)
    }

    private fun head(head: ServedCircleHead, refusals: Refusals): ServedCircleHead? {
        val id = AccountIds.canonical(head.userId) ?: return refusals.refuse()
        return if (id == head.userId) head else head.copy(userId = id)
    }

    /**
     * [notification] with its circle, where it names one, in its one spelling, and the account ids
     * in its metadata canonical. An id that is none of these is left out and the row kept: a
     * shorter page would read as rows deleted.
     */
    private fun notification(notification: NotificationResponse, refusals: Refusals): NotificationResponse {
        val circle = notification.circleId?.let { circleId(it, refusals) }
        val named = if (circle == notification.circleId) notification else notification.copy(circleId = circle)
        val metadata = named.metadata ?: return named
        if (NOTIFICATION_ACCOUNT_IDS.none { it in metadata } && NOTIFICATION_CIRCLE_ID !in metadata) return named
        val canonical = LinkedHashMap(metadata)
        for (key in NOTIFICATION_ACCOUNT_IDS) {
            val raw = canonical[key] ?: continue
            val id = (raw as? String)?.let { AccountIds.canonical(it) }
            if (id != null) canonical[key] = id else canonical.remove(key).also { refusals.refuse<Unit>() }
        }
        canonical[NOTIFICATION_CIRCLE_ID]?.let { raw ->
            val id = if (raw is String) circleId(raw, refusals) else refusals.notACircle()
            if (id != null) canonical[NOTIFICATION_CIRCLE_ID] = id else canonical.remove(NOTIFICATION_CIRCLE_ID)
        }
        return named.copy(metadata = canonical)
    }

    companion object {
        private const val TAG = "CanonicalIds"

        /** The metadata keys a notification row names an account under (place alerts, ownership claims). */
        val NOTIFICATION_ACCOUNT_IDS = listOf("target_user_id", "new_owner_id", "crossing_user_id", "user_id")

        /** The metadata key a notification row names a circle under (what circleIdOf reads from a row's metadata). */
        const val NOTIFICATION_CIRCLE_ID = "circle_id"
    }
}
