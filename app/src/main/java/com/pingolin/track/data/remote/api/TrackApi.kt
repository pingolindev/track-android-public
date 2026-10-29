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

import com.pingolin.track.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface TrackApi {

    // ===== Config =====
    @GET("api/config")
    suspend fun getConfig(): Response<TracksConfigResponse>

    // ===== Profile =====
    @GET("api/v1/profile")
    suspend fun getProfile(): Response<ApiResponse<ProfileResponse>>

    @PATCH("api/v1/profile")
    suspend fun updateProfile(@Body request: UpdateTracksProfileRequest): Response<ApiResponse<ProfileResponse>>

    // ===== Premium / subscription entitlement (own OR circle-derived) =====
    @GET("api/v1/users/me/premium")
    suspend fun getPremiumStatus(): Response<ApiResponse<PremiumStatusResponse>>

    // ===== Quota / usage (drives the Settings plan card) =====
    @GET("api/v1/quota")
    suspend fun getQuota(): Response<ApiResponse<QuotaDto>>

    // ===== Devices =====
    // The Devices UI lists and revokes through the auth server's session
    // endpoints, not through here — hence no getDevices/deleteDevice. The two
    // below register this device and its push token with the Track server,
    // which delivers pushes to the devices registered this way. Skip them and
    // the device receives no pushes.
    @POST("api/v1/devices")
    suspend fun createDevice(@Body request: CreateDeviceRequest): Response<ApiResponse<DeviceResponse>>

    @PUT("api/v1/devices/{id}/push-token")
    suspend fun updateDevicePushToken(
        @Path("id") deviceId: String,
        @Body request: UpdatePushTokenRequest
    ): Response<ApiResponse<Unit>>

    /**
     * Whether sign-outs are currently being enforced.
     *
     * Track's revocation check fails OPEN when auth is unreachable — refusing
     * would take the whole product down over a check that is not the
     * access-control decision — and this is how that stops being silent.
     *
     * READ THE TENSE. `enforcing == false` means the server could not confirm a
     * session had ended RECENTLY, not that something is down right now. The
     * warning copy must match, or it claims a diagnosis nobody has.
     */
    @GET("api/v1/security/revocation-status")
    suspend fun getRevocationStatus(): Response<ApiResponse<com.pingolin.track.data.remote.dto.RevocationStatusResponse>>

    /**
     * Track-only device list. Same caller as auth's `listSessions`, but
     * the server filters to devices that actually push location. Web
     * logins, Note app sessions, and the account/admin dashboards never
     * appear here — those are surfaced on the account site under
     * Security.
     */
    @GET("api/v1/tracking-devices")
    suspend fun listTrackingDevices(): Response<ApiResponse<List<com.pingolin.track.data.remote.dto.TrackingDevice>>>

    /**
     * What the server believes it can deliver to THIS session. The settings
     * screen reconciles its local push state against this so a lost local
     * endpoint/token stops reading as "you get no notifications".
     *
     * Deliberately not [listTrackingDevices]: that one filters out rows with a
     * NULL device_id, which is exactly the shape a UnifiedPush registration
     * takes — it would report "not registered" for a channel that works.
     */
    @GET("api/v1/push/registration")
    suspend fun getPushRegistration(): Response<ApiResponse<List<com.pingolin.track.data.remote.dto.PushRegistration>>>

    // ===== Location consent =====
    //
    // Separate from /privacy on purpose: a privacy SETTING is a preference the
    // user tunes, a consent is a permission they gave or withheld, and the
    // acknowledgement and the optional processing choice must not be the same
    // control.
    @GET("api/v1/consent/location/notice")
    suspend fun getLocationConsentNotice(): Response<ApiResponse<com.pingolin.track.data.remote.dto.LocationConsentNoticeResponse>>

    @GET("api/v1/consent/location/circles/{id}")
    suspend fun getLocationConsent(@Path("id") circleId: String): Response<ApiResponse<com.pingolin.track.data.remote.dto.LocationConsentResponse>>

    // No separate grant: the agreement travels with the accept and the creation.


    // ===== Circles =====
    @POST("api/v1/circles")
    suspend fun createCircle(@Body request: CreateCircleRequest): Response<ApiResponse<CircleResponse>>

    @GET("api/v1/circles")
    suspend fun getCircles(): Response<ApiResponse<PaginatedResponse<CircleResponse>>>

    @GET("api/v1/circles")
    suspend fun getCirclesPaginated(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20
    ): Response<ApiResponse<PaginatedResponse<CircleResponse>>>

    /** Fetch all circle keys for oblivious key distribution */
    @GET("api/v1/circles/keys")
    suspend fun getCircleKeys(): Response<ApiResponse<List<CircleKeyBundleResponse>>>

    @GET("api/v1/circles/{id}")
    suspend fun getCircle(@Path("id") id: String): Response<ApiResponse<CircleResponse>>

    /**
     * Backfill pseudonym history for the caller in a circle.
     *
     * The client submits all pseudonyms it has derived for itself across
     * every circle-key version it holds (native + key-chain-derived). The
     * server records them so the /locations history endpoint can resolve
     * location rows tagged with pseudonyms from pre-rotation key versions.
     * Idempotent — each (circle, pseudonym) pair is stored once, so
     * repeated calls are cheap.
     */
    @POST("api/v1/circles/{id}/pseudonyms/backfill")
    suspend fun backfillPseudonyms(
        @Path("id") circleId: String,
        @Body request: PseudonymBackfillRequest
    ): Response<ApiResponse<PseudonymBackfillResponse>>

    @POST("api/v1/circles/{id}/invite/email")
    suspend fun inviteToCircle(
        @Path("id") circleId: String,
        @Body request: InviteRequest
    ): Response<ApiResponse<InviteResponse>>

    /** Owner-only. */
    @GET("api/v1/circles/{id}/invites")
    suspend fun getCircleInvites(
        @Path("id") circleId: String
    ): Response<ApiResponse<List<PendingInviteResponse>>>

    /**
     * Owner/admin: replace a pending invitation's statement with its renewal — only after
     * "Is this really {email}?" was answered "It's them".
     */
    @PUT("api/v1/circles/{id}/invites/{inviteId}/statement")
    suspend fun renewInvitation(
        @Path("id") circleId: String,
        @Path("inviteId") inviteId: String,
        @Body request: com.pingolin.track.data.remote.dto.RenewInvitationRequest,
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.RenewInvitationResponse>>

    /** Owner-only. */
    @DELETE("api/v1/circles/{id}/invites/{inviteId}")
    suspend fun revokeInvite(
        @Path("id") circleId: String,
        @Path("inviteId") inviteId: String
    ): Response<ApiResponse<Unit>>

    // ===== Received Invites =====

    @GET("api/v1/invites/received")
    suspend fun getReceivedInvites(): Response<ApiResponse<List<ReceivedInviteResponse>>>

    /**
     * Every circle's refresh records above what this device holds, and its members' heads.
     * Read on the primary; paged by the same map.
     */
    @POST("api/v1/circles/refresh-records")
    suspend fun refreshRecords(@Body request: RefreshRecordsRequest): Response<ApiResponse<RefreshRecordsResponse>>

    /** A fresh id for a circle this account is about to create with its genesis record. */
    @POST("api/v1/circles/reserve-id")
    suspend fun reserveCircleId(): Response<ApiResponse<ReservedCircleIdResponse>>

    /** This account's head for [circleId]: forward only, or the same head signed again. */
    @PUT("api/v1/circles/{id}/head")
    suspend fun putCircleHead(@Path("id") circleId: String, @Body request: PutHeadRequest): Response<ApiResponse<PutHeadResponse>>

    /** Sharing public key for an email — lets the inviter wrap the circle key
     *  at invite time, so the server never sees it unwrapped. */
    @GET("api/v1/users/public-key")
    suspend fun lookupPublicKey(@Query("email") email: String): Response<ApiResponse<UserPublicKeyResponse>>

    @POST("api/v1/invites/{id}/accept")
    suspend fun acceptInvite(
        @Path("id") inviteId: String,
        @Body request: com.pingolin.track.data.remote.dto.AcceptInviteRequest,
    ): Response<ApiResponse<Unit>>

    /**
     * This invitation does not name this account as it is now: the server asks the circle
     * to confirm it's them and renew it.
     */
    @POST("api/v1/invites/{id}/mismatch")
    suspend fun reportInviteMismatch(@Path("id") inviteId: String): Response<ApiResponse<com.pingolin.track.data.remote.dto.InviteMismatchResponse>>

    @POST("api/v1/invites/{id}/decline")
    suspend fun declineInvite(@Path("id") inviteId: String): Response<ApiResponse<Unit>>

    // ===== Blocked people =====
    //
    // Addresses whose owners may not invite this person to a circle. The server does all
    // of it: a blocked person's invitation is made and stays pending for THEM, and this
    // person is never told or shown it.

    @GET("api/v1/invites/blocks")
    suspend fun listBlockedPeople(): Response<ApiResponse<List<BlockedPersonResponse>>>

    @POST("api/v1/invites/block")
    suspend fun blockPerson(@Body request: BlockPersonRequest): Response<ApiResponse<Unit>>

    /**
     * The address as the list gave it, in the BODY like a block's, never in a path: a path is
     * what a proxy's access log keeps, so every address a person unblocked would stand there in
     * the clear.
     */
    @POST("api/v1/invites/unblock")
    suspend fun unblockPerson(@Body request: BlockPersonRequest): Response<ApiResponse<Unit>>

    @DELETE("api/v1/circles/{id}/leave")
    suspend fun leaveCircle(@Path("id") circleId: String): Response<ApiResponse<Unit>>

    @POST("api/v1/circles/{id}/transfer-ownership")
    suspend fun transferOwnership(
        @Path("id") circleId: String,
        @Body request: TransferOwnershipRequest
    ): Response<ApiResponse<Unit>>

    // ===== Circle ownership claims =====
    // The dead man's switch for an unreachable owner. See [OwnershipClaimResponse].

    /**
     * The circle's live claim, or `{"success":true,"data":null}` when there is none.
     *
     * Readable by any ACTIVE MEMBER, not just the owner and the claimant — the server
     * checks `["owner","admin","member"]`. That is deliberate: a circle changing hands is
     * not a private negotiation between two people, since everybody in it is about to
     * have a different person able to delete it.
     *
     * The handler reads the PRIMARY rather than a replica, because the first thing a
     * client does after opening a claim is read it back and the replica can lag by more
     * than that round-trip — which would show the claim it just created as "none".
     */
    @GET("api/v1/circles/{id}/claim-ownership")
    suspend fun getOwnershipClaim(
        @Path("id") circleId: String
    ): Response<ApiResponse<OwnershipClaimResponse>>

    /**
     * Open a claim. ADMIN only, and the server is the authority — it answers 403 for a
     * plain member, 400 if the caller already owns the circle, and 409 with
     * `error_code = CIRCLE_OWNERSHIP_CLAIM_EXISTS` when somebody has already asked
     * (two racing admins cannot both open a claim). Answers 201, not 200.
     */
    @POST("api/v1/circles/{id}/claim-ownership")
    suspend fun claimOwnership(
        @Path("id") circleId: String
    ): Response<ApiResponse<OwnershipClaimResponse>>

    /**
     * The owner's veto. One action, permanent: a refused claim is never reopened, and the
     * admin must open a new one — which notifies the owner again.
     *
     * 403 for anyone who is not the owner, 404 when there is no live claim.
     */
    @POST("api/v1/circles/{id}/claim-ownership/refuse")
    suspend fun refuseOwnershipClaim(
        @Path("id") circleId: String
    ): Response<ApiResponse<Unit>>

    /**
     * The claimant thinking better of it. Kept distinct from a refusal so the two stay
     * distinguishable forever after — "the owner said no" and "the admin withdrew" are
     * different facts about the same circle.
     *
     * Authorised purely by `claimant_user_id = you`; there is no role check, so an admin
     * who has since been demoted can still withdraw their own claim. 404 if you have none.
     */
    @DELETE("api/v1/circles/{id}/claim-ownership")
    suspend fun withdrawOwnershipClaim(
        @Path("id") circleId: String
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/circles/{id}")
    suspend fun deleteCircle(@Path("id") circleId: String): Response<ApiResponse<Unit>>

    /** Must follow any departure from the circle: until this runs, the leaver still
     * holds a key that opens new traffic. Any member while the server has flagged the
     * circle (with expected_key_version), owner/admin otherwise. */
    @POST("api/v1/circles/{circleId}/rotate-keys")
    suspend fun rotateCircleKeys(
        @Path("circleId") circleId: String,
        @Body request: RotateKeysRequest
    ): Response<ApiResponse<RotateKeysResponse>>

    /** Any member (emails: owner/admin only). The input to a rotation: the new circle
     *  key is wrapped once per active member, client-side. */
    @GET("api/v1/circles/{id}/member-public-keys")
    suspend fun getMemberPublicKeys(@Path("id") circleId: String): Response<ApiResponse<List<MemberPublicKeyResponse>>>

    @GET("api/v1/circles/{id}/members")
    suspend fun getCircleMembers(
        @Path("id") circleId: String,
        @Query("limit") limit: Int? = null,
        @Query("cursor") cursor: String? = null,
    ): Response<ApiResponse<PaginatedResponse<MemberResponse>>>

    /**
     * **Deprecated** for non-self target — use [ejectAndRotate] instead.
     *
     * The server returns 410 Gone for any non-self DELETE. Self-target
     * (i.e. voluntary leave) returns 400 pointing the caller at
     * `/api/v1/circles/:id/leave`.
     *
     * It stays on the interface so those 410s can be recognised and turned into
     * an "update required" prompt rather than surfacing as a generic failure.
     */
    @Deprecated(
        message = "Returns 410 Gone for non-self target. Use ejectAndRotate for admin-initiated removal.",
        replaceWith = ReplaceWith("ejectAndRotate(circleId, request)")
    )
    @DELETE("api/v1/circles/{id}/members/{userId}")
    suspend fun removeMember(
        @Path("id") circleId: String,
        @Path("userId") userId: String
    ): Response<ApiResponse<Unit>>

    /**
     * Atomically eject a member and rotate the circle key. The caller
     * generates a fresh key locally, wraps it for every surviving member
     * (excluding the ejected user), and submits the bundle. Server enforces
     * coverage and bumps `key_version`.
     *
     * Replaces the legacy `DELETE /circles/:id/members/:user_id` admin path.
     */
    @POST("api/v1/circles/{id}/members/eject-and-rotate")
    suspend fun ejectAndRotate(
        @Path("id") circleId: String,
        @Body request: com.pingolin.track.data.remote.dto.EjectAndRotateRequest
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.EjectAndRotateResponse>>

    /**
     * Remove a member WITHOUT rotating the circle key.
     *
     * Only for when [ejectAndRotate] has actually failed — it needs a fresh
     * wrap for every remaining member, which a survivor with an unverified key
     * pin or an unresolvable identity key makes impossible. The member loses
     * the circle immediately; the key they already hold is unchanged, so
     * whatever they already downloaded stays readable to them. The server
     * returns that sentence in `warning`; show it rather than writing our own.
     */
    @POST("api/v1/circles/{id}/members/{userId}/revoke-access")
    suspend fun revokeMemberAccess(
        @Path("id") circleId: String,
        @Path("userId") userId: String,
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.RevokeAccessResponse>>

    /** Owner-only, and the owner's own role cannot be changed.
     *  Valid roles: "admin", "member". */
    @PUT("api/v1/circles/{circleId}/members/{userId}/role")
    suspend fun updateMemberRole(
        @Path("circleId") circleId: String,
        @Path("userId") userId: String,
        @Body request: UpdateMemberRoleRequest
    ): Response<ApiResponse<Unit>>

    // ===== Encrypted Display Names (E2EE) =====

    @PUT("api/v1/circles/{circleId}/display-name")
    suspend fun setDisplayName(
        @Path("circleId") circleId: String,
        @Body request: @JvmSuppressWildcards Map<String, Any>
    ): Response<ApiResponse<Unit>>

    // ===== Session Keys =====

    @POST("api/v1/circles/{circleId}/session-keys")
    suspend fun publishSessionKey(
        @Path("circleId") circleId: String,
        @Body request: PublishSessionKeyRequest
    ): Response<ApiResponse<PublishSessionKeyResponse>>

    @GET("api/v1/circles/{circleId}/session-keys")
    suspend fun getActiveSessionKeys(
        @Path("circleId") circleId: String
    ): Response<ApiResponse<List<SessionKeyResponse>>>

    @GET("api/v1/circles/{circleId}/session-keys/{sessionKeyId}")
    suspend fun getSessionKey(
        @Path("circleId") circleId: String,
        @Path("sessionKeyId") sessionKeyId: String
    ): Response<ApiResponse<SessionKeyResponse>>

    @DELETE("api/v1/circles/{circleId}/session-keys/{sessionKeyId}")
    suspend fun revokeSessionKey(
        @Path("circleId") circleId: String,
        @Path("sessionKeyId") sessionKeyId: String
    ): Response<ApiResponse<Any>>

    // ===== Locations =====
    @GET("api/v1/circles/{circleId}/locations")
    suspend fun getLocationHistory(
        @Path("circleId") circleId: String,
        @Query("user_id") userId: String? = null,
        @Query("start_time") startTime: String? = null,
        @Query("end_time") endTime: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<ApiResponse<List<EncryptedLocationResponse>>>

    /** Preferred history endpoint: day buckets let the server range-scan instead
     * of filtering on timestamps.
     *
     * UNPAGED, deliberately. The day-bucket range IS the request — one call
     * returns the whole window, downsampled to a fixed point budget. There is
     * no `cursor` (the server has no such parameter and never emits one) and
     * no `limit` (the server accepts and ignores one: a client-supplied row
     * cap would truncate the window the downsample spans). Want more detail?
     * Ask for a NARROWER range, not another page. See [LocationHistoryResponse]. */
    @GET("api/v1/circles/{circleId}/locations")
    suspend fun getLocationHistoryByDayBucket(
        @Path("circleId") circleId: String,
        @Query("user_id") userId: String? = null,
        @Query("start_day_bucket") startDayBucket: Int? = null,
        @Query("end_day_bucket") endDayBucket: Int? = null
    ): Response<ApiResponse<LocationHistoryResponse>>

    @GET("api/v1/circles/{circleId}/locations/latest")
    suspend fun getLatestLocations(
        @Path("circleId") circleId: String
    ): Response<ApiResponse<LatestLocationsWrapper>>

    /**
     * The same read, untouched: a re-seal sends this account's own frozen row's blobs back
     * exactly as they were served, for the server's compare-and-swap.
     */
    @GET("api/v1/circles/{circleId}/locations/latest")
    suspend fun getLatestLocationsAsServed(
        @Path("circleId") circleId: String
    ): Response<com.google.gson.JsonObject>

    /**
     * This account's own last location in a circle where its real-time sharing is off, sealed again
     * under the circle's current key. Every refusal is the same 404.
     */
    @POST("api/v1/circles/{circleId}/locations/last-known/reseal")
    suspend fun resealLastKnown(
        @Path("circleId") circleId: String,
        @Body body: com.google.gson.JsonObject,
    ): Response<com.google.gson.JsonObject>

    // ===== Places =====
    @POST("api/v1/places")
    suspend fun createPlace(@Body request: CreatePlaceRequest): Response<ApiResponse<String>>

    /** Server caps the page at `limit` (default 50, max 100) and paginates by
     *  offset — the client MUST follow pages to exhaustion or places 51+ are
     *  silently dropped (and then pruned locally). See [PlaceRepository.refreshPlaces]. */
    @GET("api/v1/places")
    suspend fun getPlaces(
        @Query("circle_id") circleId: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): Response<ApiResponse<List<PlaceResponse>>>

    @PUT("api/v1/places/{id}")
    suspend fun updatePlace(
        @Path("id") id: String,
        @Body request: UpdatePlaceRequest
    ): Response<ApiResponse<PlaceResponse>>

    @DELETE("api/v1/places/{id}")
    suspend fun deletePlace(@Path("id") id: String): Response<ApiResponse<Unit>>

    @POST("api/v1/places/{id}/share")
    suspend fun sharePlace(
        @Path("id") placeId: String,
        @Body request: SharePlaceRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/places/{id}/share/{circleId}")
    suspend fun unsharePlace(
        @Path("id") placeId: String,
        @Path("circleId") circleId: String
    ): Response<ApiResponse<Unit>>

    /** Atomic legacy-place migration. One server transaction commits the new
     * ciphertexts, the owner wrap and every per-circle share together, so a
     * half-migrated place is unrepresentable. The server migrates only a
     * place that has no wrapped key yet, so two devices racing to migrate
     * the same place cannot both win. */
    @POST("api/v1/places/{id}/migrate-to-envelope")
    suspend fun migratePlaceToEnvelope(
        @Path("id") placeId: String,
        @Body request: MigratePlaceToEnvelopeRequest
    ): Response<ApiResponse<Unit>>

    // ===== Place Alerts =====
    @POST("api/v1/place-alerts")
    suspend fun createPlaceAlert(@Body request: CreatePlaceAlertRequest): Response<ApiResponse<String>>

    /**
     * @param limit server default is 50, max 100. The client asks for the max and
     *   treats a FULL page as "there may be more" — refreshPlaceAlerts only prunes
     *   local rows when it knows it saw the complete set, or it would delete the
     *   alerts that fell off the end of the page.
     */
    @GET("api/v1/place-alerts")
    suspend fun getPlaceAlerts(
        @Query("limit") limit: Int? = null
    ): Response<ApiResponse<List<PlaceAlertResponse>>>

    @GET("api/v1/place-alerts/{id}")
    suspend fun getPlaceAlert(@Path("id") id: String): Response<ApiResponse<PlaceAlertResponse>>

    @PUT("api/v1/place-alerts/{id}")
    suspend fun updatePlaceAlert(
        @Path("id") id: String,
        @Body request: UpdatePlaceAlertRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/place-alerts/{id}")
    suspend fun deletePlaceAlert(@Path("id") id: String): Response<ApiResponse<Unit>>

    @POST("api/v1/place-alerts/{id}/refresh-hashes")
    suspend fun refreshPlaceAlertHashes(
        @Path("id") id: String,
        @Body request: RefreshPlaceAlertHashesRequest
    ): Response<ApiResponse<Unit>>

    @GET("api/v1/places/{placeId}/events")
    suspend fun getPlaceAlertEvents(
        @Path("placeId") placeId: String
    ): Response<ApiResponse<List<PlaceAlertEventResponse>>>

    // ===== Check-ins =====
    @POST("api/v1/check-ins")
    suspend fun createCheckIn(@Body request: CreateCheckInRequest): Response<ApiResponse<CheckInResponse>>

    @GET("api/v1/check-ins")
    suspend fun getMyCheckIns(@Query("limit") limit: Int = 50): Response<ApiResponse<List<CheckInResponse>>>

    @GET("api/v1/circles/{circleId}/check-ins")
    suspend fun getCircleCheckIns(
        @Path("circleId") circleId: String,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<CheckInResponse>>>

    // ===== SOS =====
    @POST("api/v1/sos")
    suspend fun triggerSos(@Body request: TriggerSosRequest): Response<ApiResponse<SosAlertResponse>>

    @GET("api/v1/sos/active")
    suspend fun getActiveSosAlerts(): Response<ApiResponse<List<SosAlertResponse>>>

    /** Single alert, any status. Used to learn the real outcome of an alert that
     *  dropped out of /sos/active instead of guessing at it. */
    @GET("api/v1/sos/{id}")
    suspend fun getSosAlert(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    @POST("api/v1/sos/{id}/acknowledge")
    suspend fun acknowledgeSos(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    /** Per-viewer hide. Records a private dismissal; does NOT close the alert or
     *  change what any other member sees. */
    @POST("api/v1/sos/{id}/dismiss")
    suspend fun dismissSos(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    /** Undo a dismissal within the grace window — restores the list row and
     *  the map marker together. */
    @DELETE("api/v1/sos/{id}/dismiss")
    suspend fun undismissSos(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    /**
     * Confirm to the server that the PUSH for this alert reached this device.
     *
     * Call from the push handler only. Calling it after learning about an alert
     * any other way (a poll, opening the app) would report a receipt for a
     * notification that never arrived, which is exactly the failure the raiser's
     * delivery summary exists to catch.
     *
     * Not acknowledgement: this says bytes landed, not that anyone saw them.
     */
    @POST("api/v1/sos/{id}/receipt")
    suspend fun postSosReceipt(
        @Path("id") id: String,
        @Body request: SosReceiptRequest
    ): Response<ApiResponse<Unit>>

    /** How far MY OWN alert got. 404s for anyone else's. */
    @GET("api/v1/sos/{id}/delivery")
    suspend fun getSosDelivery(@Path("id") id: String): Response<ApiResponse<SosDeliveryResponse>>

    @POST("api/v1/sos/{id}/resolve")
    suspend fun resolveSos(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    @POST("api/v1/sos/{id}/false-alarm")
    suspend fun cancelSos(@Path("id") id: String): Response<ApiResponse<SosAlertResponse>>

    /**
     * The raiser seals its own open SOS again under the circle's current key, the same id.
     * Every refusal is a 404; past ten an hour a 429. The others get a quiet `sos_refresh` push.
     */
    @PUT("api/v1/sos/{id}/reseal")
    suspend fun resealSos(
        @Path("id") id: String,
        @Body request: ResealSosRequest
    ): Response<ApiResponse<SosAlertResponse>>

    // ===== Privacy =====
    @GET("api/v1/privacy/circles/{circleId}")
    suspend fun getPrivacySettings(@Path("circleId") circleId: String): Response<ApiResponse<PrivacySettingsResponse>>

    @PUT("api/v1/privacy/circles/{circleId}")
    suspend fun updatePrivacySettings(
        @Path("circleId") circleId: String,
        @Body request: UpdatePrivacyRequest
    ): Response<ApiResponse<PrivacySettingsResponse>>

    /**
     * The privacy PUT that only CREATES: the row is written only when the account has none in
     * [circleId], and the answer says whether it was. An older server ignores the flag and
     * upserts, answering without [PrivacyWritten.written].
     */
    @PUT("api/v1/privacy/circles/{circleId}")
    suspend fun createPrivacySettings(
        @Path("circleId") circleId: String,
        @Body request: UpdatePrivacyRequest,
        @Query("create_only") createOnly: Boolean = true,
    ): Response<ApiResponse<PrivacyWritten>>

    @POST("api/v1/privacy/circles/{circleId}/bubble")
    suspend fun enableBubble(
        @Path("circleId") circleId: String,
        @Body request: EnableBubbleRequest
    ): Response<ApiResponse<PrivacySettingsResponse>>

    @POST("api/v1/privacy/circles/{circleId}/pause")
    suspend fun pauseSharing(
        @Path("circleId") circleId: String,
        @Body request: PauseSharingRequest
    ): Response<ApiResponse<PrivacySettingsResponse>>

    /** End a pause: the server puts back the mode held before it and answers like the GET. */
    @POST("api/v1/privacy/circles/{circleId}/resume")
    suspend fun resumeSharing(
        @Path("circleId") circleId: String
    ): Response<ApiResponse<PrivacySettingsResponse>>

    // ===== Boundary Monitoring (E2EE) =====

    /** Crossings are detected CLIENT-SIDE and only the outcome is reported —
     *  the server never learns the boundary or the position that crossed it. */
    @POST("api/v1/boundaries/crossing")
    suspend fun reportBoundaryCrossing(
        @Body request: ReportBoundaryCrossingRequest
    ): Response<ApiResponse<ReportBoundaryCrossingResponse>>

    @GET("api/v1/circles/{circleId}/boundary-events")
    suspend fun getBoundaryEvents(
        @Path("circleId") circleId: String,
        @Query("place_id") placeId: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<BoundaryEventResponse>>>

    /** Last known inside/outside state per monitored member. */
    @GET("api/v1/circles/{circleId}/boundary-states")
    suspend fun getBoundaryStates(
        @Path("circleId") circleId: String
    ): Response<ApiResponse<List<BoundaryStateResponse>>>

    // ===== Self Key (Personal Tracking) =====
    @GET("api/v1/users/me/self-key")
    suspend fun getSelfKey(): Response<ApiResponse<CircleKeyBundleResponse?>>

    /**
     * This device cannot use the personal copy it was served (the SDK's adoption check said
     * CANNOT_OPEN or UNSIGNED, or it didn't open at first contact). The server wakes the
     * account's other phones, and the one that holds the recorded key puts the copy right.
     * The body names the copy; a stale one comes back `copy_changed`.
     */
    @POST("api/v1/users/me/self-key/unreadable")
    suspend fun reportSelfKeyUnreadable(
        @Body request: com.pingolin.track.data.remote.dto.SelfKeyUnreadableRequest,
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.SelfKeyUnreadableResponse>>

    /**
     * The account's record names another pseudonym (or none) than the key the stored copy
     * carries, and that key is this device's: set the record, only while that exact copy is
     * stored — 409 `key_wrap_changed` otherwise.
     */
    @PUT("api/v1/users/me/self-key/pseudonym")
    suspend fun recordSelfPseudonym(
        @Body request: com.pingolin.track.data.remote.dto.RecordSelfPseudonymRequest,
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.RecordSelfPseudonymResponse>>

    @POST("api/v1/users/me/self-key")
    suspend fun storeSelfKey(@Body request: StoreSelfKeyRequest): Response<ApiResponse<Unit>>

    /**
     * Attach this caller's signature to its OWN stored wrap of a circle key: the
     * creator's wrap right after a circle is created (its signature binds the circle
     * id, which only exists once the create returns), and on sync any unsigned copy
     * of a key this device already holds. The server stores it only when the stored
     * wrap is exactly these bytes at this version and is unsigned or ours —
     * 409 `key_wrap_changed` otherwise.
     */
    @PUT("api/v1/circles/{id}/wrap-signature")
    suspend fun signOwnWrap(
        @Path("id") circleId: String,
        @Body request: OwnWrapSignatureRequest,
    ): Response<ApiResponse<OwnWrapSignatureResponse>>

    /**
     * Any member hands [userId] the circle's CURRENT copy, signed by this caller. The first
     * delivery wins: 409 `key_already_delivered` after it, 409 `key_version_mismatch` when the
     * circle has moved on.
     */
    @PUT("api/v1/circles/{id}/members/{userId}/access")
    suspend fun deliverAccess(
        @Path("id") circleId: String,
        @Path("userId") userId: String,
        @Body request: DeliverAccessRequest,
    ): Response<ApiResponse<DeliverAccessResponse>>

    /** This device cannot use its copy of the circle and asks for a fresh one. */
    @POST("api/v1/circles/{id}/access/request")
    suspend fun requestAccess(@Path("id") circleId: String): Response<ApiResponse<AccessRequestResponse>>

    /**
     * [userId]'s app is too old for this circle (a sharing key and no signing key): the
     * server tells THEM to update Pingolin, by push and email.
     */
    @POST("api/v1/circles/{id}/members/{userId}/update-request")
    suspend fun requestMemberUpdate(
        @Path("id") circleId: String,
        @Path("userId") userId: String,
    ): Response<ApiResponse<com.pingolin.track.data.remote.dto.MemberUpdateRequestResponse>>

    /** Owner/admin only: store an admission (kind 3) about [userId]. */
    @PUT("api/v1/circles/{id}/members/{userId}/statement")
    suspend fun storeMembershipStatement(
        @Path("id") circleId: String,
        @Path("userId") userId: String,
        @Body request: StoreStatementRequest,
    ): Response<ApiResponse<StoreStatementResponse>>

    /**
     * Which of these statements, all signed by this account, the server holds in [circleId],
     * read from the primary in one snapshot. Any current member may ask; at most 20 ids.
     */
    @POST("api/v1/circles/{id}/statements/held")
    suspend fun statementsHeld(
        @Path("id") circleId: String,
        @Body request: StatementsHeldRequest,
    ): Response<ApiResponse<StatementsHeldResponse>>

    // ===== Geocoding Preferences =====
    @GET("api/v1/users/me/geocoding")
    suspend fun getGeocodingPrefs(): Response<ApiResponse<GeocodingPrefsResponse>>

    @PUT("api/v1/users/me/geocoding")
    suspend fun updateGeocodingPrefs(@Body request: UpdateGeocodingPrefsRequest): Response<ApiResponse<GeocodingPrefsResponse>>

    // No client-side notification fan-out: low-battery alerts go through
    // `wakeWatchers` above, which asks for a wake-up and sends no content at
    // all.

    // ===== GDPR Data Export (Article 20 - Right to Data Portability) =====

    /**
     * Machine-readable export of the account. Everything sensitive comes back
     * still encrypted — the caller must decrypt client-side before it is
     * readable, which is also why the server can produce this at all.
     *
     * Day buckets are days since the Unix epoch; omitting them exports the last
     * 30 days. Every `include*` flag defaults to true server-side.
     */
    @GET("api/v1/export")
    suspend fun exportData(
        @Query("start_day_bucket") startDayBucket: Int? = null,
        @Query("end_day_bucket") endDayBucket: Int? = null,
        @Query("include_devices") includeDevices: Boolean? = null,
        @Query("include_circles") includeCircles: Boolean? = null,
        @Query("include_places") includePlaces: Boolean? = null,
        @Query("include_check_ins") includeCheckIns: Boolean? = null,
        @Query("include_locations") includeLocations: Boolean? = null,
        // Config sections (format 1.2+).
        @Query("include_alerts") includeAlerts: Boolean? = null,
        @Query("include_alert_rules") includeAlertRules: Boolean? = null,
        @Query("include_inactivity_alerts") includeInactivityAlerts: Boolean? = null,
        @Query("include_privacy") includePrivacy: Boolean? = null,
        @Query("include_preferences") includePreferences: Boolean? = null,
        @Query("include_profile") includeProfile: Boolean? = null,
        @Query("include_circle_invites") includeCircleInvites: Boolean? = null,
        @Query("include_alert_events") includeAlertEvents: Boolean? = null,
        @Query("include_place_shares") includePlaceShares: Boolean? = null,
        @Query("include_web_push_subscriptions") includeWebPushSubscriptions: Boolean? = null,
        @Query("include_boundary_events") includeBoundaryEvents: Boolean? = null,
        @Query("cursor_boundary_event_occurred_at_ms") cursorBoundaryEventOccurredAtMs: Long? = null,
        @Query("cursor_boundary_event_id") cursorBoundaryEventId: String? = null,
        @Query("include_destinations") includeDestinations: Boolean? = null,
        @Query("cursor_destination_created_at_ms") cursorDestinationCreatedAtMs: Long? = null,
        @Query("cursor_destination_id") cursorDestinationId: String? = null,
        @Query("include_circle_notifications") includeCircleNotifications: Boolean? = null,
        @Query("cursor_circle_notification_created_at_ms") cursorCircleNotificationCreatedAtMs: Long? = null,
        @Query("cursor_circle_notification_id") cursorCircleNotificationId: String? = null,
        @Query("include_notification_history") includeNotificationHistory: Boolean? = null,
        @Query("cursor_notification_created_at_ms") cursorNotificationCreatedAtMs: Long? = null,
        @Query("cursor_notification_id") cursorNotificationId: String? = null,
        @Query("include_circle_activity") includeCircleActivity: Boolean? = null,
        @Query("cursor_circle_activity_created_at_ms") cursorCircleActivityCreatedAtMs: Long? = null,
        @Query("cursor_circle_activity_id") cursorCircleActivityId: String? = null,
        // Format 1.9, "Blocked people". The one section the server serves ONLY
        // WHEN ASKED FOR: left out (null), it is not in the answer.
        @Query("include_blocked_people") includeBlockedPeople: Boolean? = null,
        @Query("cursor_blocked_person_created_at_ms") cursorBlockedPersonCreatedAtMs: Long? = null,
        @Query("cursor_blocked_person_id") cursorBlockedPersonId: String? = null,
        @Query("cursor_invite_created_at_ms") cursorInviteCreatedAtMs: Long? = null,
        @Query("cursor_invite_id") cursorInviteId: String? = null,
        @Query("cursor_alert_event_triggered_at_ms") cursorAlertEventTriggeredAtMs: Long? = null,
        @Query("cursor_alert_event_id") cursorAlertEventId: String? = null,
        @Query("cursor_place_share_place_id") cursorPlaceSharePlaceId: String? = null,
        @Query("cursor_place_share_circle_id") cursorPlaceShareCircleId: String? = null,
        @Query("cursor_web_push_created_at_ms") cursorWebPushCreatedAtMs: Long? = null,
        @Query("cursor_web_push_id") cursorWebPushId: String? = null,
        // Per-section keyset cursors. Each is the (sort key, id) of the last row
        // received; the id breaks ties, because a day bucket holds many rows and
        // is not a total order on its own. Half a cursor is a WRONG cursor, so
        // the server restarts a section given only one part.
        @Query("cursor_device_created_at_ms") cursorDeviceCreatedAtMs: Long? = null,
        @Query("cursor_device_id") cursorDeviceId: String? = null,
        @Query("cursor_circle_joined_bucket") cursorCircleJoinedBucket: Int? = null,
        @Query("cursor_circle_id") cursorCircleId: String? = null,
        @Query("cursor_place_created_bucket") cursorPlaceCreatedBucket: Int? = null,
        @Query("cursor_place_id") cursorPlaceId: String? = null,
        @Query("cursor_check_in_bucket") cursorCheckInBucket: Int? = null,
        @Query("cursor_check_in_id") cursorCheckInId: String? = null,
        @Query("cursor_alert_created_at_ms") cursorAlertCreatedAtMs: Long? = null,
        @Query("cursor_alert_id") cursorAlertId: String? = null,
        @Query("cursor_alert_rule_created_at_ms") cursorAlertRuleCreatedAtMs: Long? = null,
        @Query("cursor_alert_rule_id") cursorAlertRuleId: String? = null,
        @Query("cursor_inactivity_created_at_ms") cursorInactivityCreatedAtMs: Long? = null,
        @Query("cursor_inactivity_id") cursorInactivityId: String? = null,
        @Query("cursor_privacy_circle_id") cursorPrivacyCircleId: String? = null,
        @Query("cursor_day_bucket") cursorDayBucket: Int? = null,
        @Query("cursor_inserted_at_ms") cursorInsertedAtMs: Long? = null,
        @Query("cursor_row_key") cursorRowKey: String? = null
    ): Response<ApiResponse<DataExportResponse>>

    // ===== Destinations (Travel Intentions) =====

    /**
     * Create a new destination (travel intention).
     * E2EE: All location data is encrypted with circle key.
     */
    @POST("api/v1/destinations")
    suspend fun createDestination(@Body request: CreateDestinationRequest): Response<ApiResponse<DestinationResponse>>

    /**
     * Every destination for this user, across every circle. Deliberately takes
     * no parameters: the server always returns active plus recently-updated
     * (7-day) rows, capped at 100. Adding circle_id/status/limit query params
     * here does nothing — the handler ignores them silently, so a caller that
     * "filters" this way gets the full list back and believes it filtered.
     */
    @GET("api/v1/destinations")
    suspend fun getDestinations(): Response<ApiResponse<List<DestinationResponse>>>

    @GET("api/v1/destinations/{id}")
    suspend fun getDestination(@Path("id") id: String): Response<ApiResponse<DestinationResponse>>

    @PUT("api/v1/destinations/{id}")
    suspend fun updateDestination(
        @Path("id") id: String,
        @Body request: UpdateDestinationRequest
    ): Response<ApiResponse<DestinationResponse>>

    @DELETE("api/v1/destinations/{id}")
    suspend fun deleteDestination(@Path("id") id: String): Response<ApiResponse<Unit>>

    /** Arrival is detected on-device; this only reports the conclusion. */
    @POST("api/v1/destinations/{id}/arrive")
    suspend fun reportDestinationArrival(
        @Path("id") id: String,
        @Body request: ReportArrivalRequest
    ): Response<ApiResponse<ReportArrivalResponse>>

    @GET("api/v1/destinations/{id}/events")
    suspend fun getDestinationEvents(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<DestinationEventResponse>>>

    // ===== Inactivity Alerts =====

    @POST("api/v1/inactivity-alerts")
    suspend fun createInactivityAlert(@Body request: CreateInactivityAlertRequest): Response<ApiResponse<String>>

    /**
     * Only alerts this user created — not alerts others set targeting them. A page holds at most
     * 100. The next page starts after the [cursorDayBucket] and [cursorId] of the previous page's
     * last alert, ordered `day_bucket DESC, id DESC`; given those, the server ignores [offset].
     */
    @GET("api/v1/inactivity-alerts")
    suspend fun getInactivityAlerts(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("cursor_day_bucket") cursorDayBucket: Int? = null,
        @Query("cursor_id") cursorId: String? = null,
    ): Response<ApiResponse<List<InactivityAlertResponse>>>

    @GET("api/v1/inactivity-alerts/{id}")
    suspend fun getInactivityAlert(@Path("id") id: String): Response<ApiResponse<InactivityAlertResponse>>

    @PUT("api/v1/inactivity-alerts/{id}")
    suspend fun updateInactivityAlert(
        @Path("id") id: String,
        @Body request: UpdateInactivityAlertRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/inactivity-alerts/{id}")
    suspend fun deleteInactivityAlert(@Path("id") id: String): Response<ApiResponse<Unit>>

    /**
     * The self-alerts other members of my circles have asked me to watch.
     *
     * Their devices cannot raise these — a phone that has gone quiet cannot
     * report itself — and the server does not decide them, so without this
     * nothing fires them at all.
     */
    @GET("api/v1/inactivity-alerts/circle-self-alerts")
    suspend fun getCircleSelfAlerts(): Response<ApiResponse<List<CircleSelfAlertDto>>>

    /** Firings, not definitions. */
    @GET("api/v1/inactivity-events")
    suspend fun getInactivityEvents(
        @Query("alert_id") alertId: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<List<InactivityEventResponse>>>

    // ===== Smart Alert Rules =====

    /** Supported rule type: 'low_battery'. */
    @POST("api/v1/alert-rules")
    suspend fun createAlertRule(@Body request: CreateAlertRuleRequest): Response<ApiResponse<String>>

    /**
     * Ask the server to send a contentless silent push to the members of this
     * circle who hold an alert rule, so their devices re-check their own rules.
     *
     * Called by the WATCHED device when its own battery crosses a threshold.
     * It reports nothing about what changed, and the reply says nothing about
     * who was woken — that would tell the caller who is watching them.
     */
    @POST("api/v1/alerts/wake-watchers")
    suspend fun wakeWatchers(@Body request: WakeWatchersRequest): Response<ApiResponse<Unit>>

    @GET("api/v1/alert-rules")
    suspend fun getAlertRules(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<AlertRulesListResponse>>

    @GET("api/v1/alert-rules/{id}")
    suspend fun getAlertRule(@Path("id") id: String): Response<ApiResponse<AlertRuleResponse>>

    @PUT("api/v1/alert-rules/{id}")
    suspend fun updateAlertRule(
        @Path("id") id: String,
        @Body request: UpdateAlertRuleRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/alert-rules/{id}")
    suspend fun deleteAlertRule(@Path("id") id: String): Response<ApiResponse<Unit>>

    /** Report a firing this device decided on. See CreateAlertEventRequest. */
    @POST("api/v1/alert-events")
    suspend fun createAlertEvent(@Body request: CreateAlertEventRequest): Response<ApiResponse<String>>

    /** Firings, not definitions. */
    @GET("api/v1/alert-events")
    suspend fun getAlertEvents(
        @Query("rule_id") ruleId: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<ApiResponse<AlertEventsListResponse>>

    // ===== Notification Center =====

    /**
     * Get all notifications for the current user.
     * Supports pagination and filtering by read status.
     */
    @GET("api/v1/notifications")
    suspend fun getNotifications(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("unread_only") unreadOnly: Boolean = false
    ): Response<ApiResponse<NotificationsListResponse>>

    /**
     * Get the count of unread notifications.
     */
    @GET("api/v1/notifications/unread-count")
    suspend fun getUnreadNotificationCount(): Response<ApiResponse<UnreadCountResponse>>

    /**
     * Mark a notification as read.
     */
    @POST("api/v1/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): Response<ApiResponse<Unit>>

    /**
     * Mark all notifications as read. Returns the number of rows affected.
     *
     * `Long`, NOT `Unit`: the body is `{"success":true,"data":2}`. Declared as
     * `Unit`, Gson would try to read that number as an object — "Expected
     * BEGIN_OBJECT but was NUMBER" — and throw INSIDE the Retrofit call, after
     * the 200 had already been logged; the repository's catch would turn that
     * into a silent Result.failure, so the local `isRead` update would never
     * run while the server had in fact marked everything read.
     *
     * The sibling `{id}/read` answers `data: null`, which deserializes into
     * `Unit` fine.
     */
    @POST("api/v1/notifications/read-all")
    suspend fun markAllNotificationsRead(): Response<ApiResponse<Long>>

    /**
     * Delete a notification (swipe-to-dismiss in the notifications list).
     */
    @DELETE("api/v1/notifications/{id}")
    suspend fun deleteNotification(@Path("id") id: String): Response<ApiResponse<Unit>>

    // ===== Notification Preferences =====

    @GET("api/v1/notifications/preferences")
    suspend fun getNotificationPreferences(): Response<ApiResponse<Map<String, Any>>>

    @PUT("api/v1/notifications/preferences")
    suspend fun updateNotificationPreferences(@Body prefs: @JvmSuppressWildcards Map<String, Any>): Response<ApiResponse<Unit>>

    // ---- Privacy-preserving geocode tokens (Privacy-Pass-style VOPRF) --------
    // Splits identity from location: mint anonymous single-use credits while
    // authenticated (issuance), then spend one per reverse-geocode on a
    // session-less endpoint that carries no identity. See GeocodeTokenManager.

    /**
     * Public: fetch + pin the current VOPRF public key + key_id so the client
     * can verify issuance DLEQ proofs and detect rotation. Flat JSON (not
     * ApiResponse-wrapped). 503 if the token flow is unconfigured.
     *
     * ANONYMOUS: the pinned key is the same public value every user sees, so this
     * fetch must carry NO identity. The `X-Pingolin-Anon` header tells AppModule's
     * auth interceptor to send no Authorization — otherwise the bearer would link
     * this device to the subsequent session-less redemptions it pins the key for.
     */
    @Headers("X-Pingolin-Anon: 1")
    @GET("api/v1/geocode/token-key")
    suspend fun geocodeTokenKey(): Response<GeocodeTokenKeyResponse>

    /**
     * Authenticated + premium-gated: BlindEvaluate a batch of client-blinded
     * elements into anonymous single-use credits (with DLEQ proofs). 402 without
     * a subscription, 429 over the issuance rate limit, 503 if unconfigured.
     */
    @POST("api/v1/geocode/tokens")
    suspend fun geocodeIssueTokens(
        @Body request: GeocodeIssueRequest
    ): Response<ApiResponse<GeocodeIssueResponse>>

    /**
     * SESSION-LESS redemption: spend one credit to reverse-geocode. The
     * `X-Pingolin-Anon` header tells AppModule's auth interceptor to send NO
     * Authorization — this request must carry no identity. Returns the raw
     * Pelias GeoJSON FeatureCollection.
     */
    @Headers("X-Pingolin-Anon: 1")
    @POST("api/v1/geocode/reverse")
    suspend fun geocodeRedeemReverse(
        @Body request: GeocodeRedeemRequest
    ): Response<okhttp3.ResponseBody>

    /**
     * SESSION-LESS redemption: spend one credit to run a forward/text search.
     * Same no-identity contract as [geocodeRedeemReverse] — the `X-Pingolin-Anon`
     * header suppresses the Authorization header, so the request carries the
     * search string but nothing that ties it to an account. Returns the raw
     * Pelias GeoJSON FeatureCollection.
     *
     * [language] is ONE language code ("de"), or null to send no header at all:
     * see [com.pingolin.track.data.remote.ServerGeocoder.anonymousLanguage] for
     * what it is for and why it is no more than that.
     */
    @Headers("X-Pingolin-Anon: 1")
    @POST("api/v1/geocode/search")
    suspend fun geocodeRedeemSearch(
        @Header("Accept-Language") language: String?,
        @Body request: GeocodeSearchRequest
    ): Response<okhttp3.ResponseBody>

}
