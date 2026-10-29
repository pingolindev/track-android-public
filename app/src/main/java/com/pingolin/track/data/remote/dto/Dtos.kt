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

package com.pingolin.track.data.remote.dto

import com.google.gson.annotations.SerializedName

// ===== API Response Wrapper =====

/** Every endpoint wraps its payload in this envelope. */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: String?,
    /** Canonical error code on a gated failure (e.g. `quota_places_exceeded`,
     *  `premium_required_destinations`). Present on 402 quota/premium responses. */
    @SerializedName("error_code") val errorCode: String? = null,
    /** Structured quota detail on a 402. See [QuotaError]. */
    val quota: QuotaError? = null
)

/** Cursor-paginated list envelope (/circles, /circles/{id}/members). */
data class PaginatedResponse<T>(
    val items: List<T>,
    @SerializedName("has_more") val hasMore: Boolean,
    /**
     * The server's `next_cursor`. The explicit name is required: without it Gson would not
     * fill the field, and no list here would get past its first page.
     */
    @SerializedName("next_cursor") val cursor: String? = null
)

/**
 * Current subscription tier + usage for the signed-in user, from
 * `GET /api/v1/quota`. Drives the Settings plan/usage card and the
 * proactive usage bars.
 *
 * `tier` is `"free"` or `"paid"`. Count fields (`max_*` = -1 for unlimited)
 * are enforced per-user across all circles; `max_members` is the per-circle
 * cap; the `*_enabled` flags are circle-effective feature gates. All fields
 * carry defaults so a slightly different server shape still deserializes.
 */
data class QuotaDto(
    val tier: String = "free",
    @SerializedName("max_places") val maxPlaces: Long = 2,
    @SerializedName("current_places") val currentPlaces: Long = 0,
    @SerializedName("max_circles") val maxCircles: Long = 2,
    @SerializedName("current_circles") val currentCircles: Long = 0,
    @SerializedName("max_place_alerts") val maxPlaceAlerts: Long = 2,
    @SerializedName("current_place_alerts") val currentPlaceAlerts: Long = 0,
    @SerializedName("max_destinations") val maxDestinations: Long = 1,
    @SerializedName("current_destinations") val currentDestinations: Long = 0,
    @SerializedName("max_members") val maxMembers: Long = 5,
    @SerializedName("history_days") val historyDays: Long = 2,
    /** "own" | "circle" | "none". `tier` reads "premium" for BOTH own and circle, so
     *  this is the only way to tell a subscriber from someone riding a circle-mate's
     *  subscription — which matters because the latter can vanish without any action
     *  by this user. */
    val source: String = "none",
    /** Days until extended history is DELETED, for a user who recently lost premium.
     *  Null while premium is active, and null when there was never premium to lose.
     *  Distinct from [historyDays], which is what is readable right now: visibility
     *  drops immediately, the data survives until this reaches zero. */
    @SerializedName("history_expires_in_days") val historyExpiresInDays: Long? = null,
    /** WHICH subject [historyExpiresInDays] is counting down: the circle whose history
     * goes first, or null when it is the user's OWN personal history. The countdown has
     * two possible subjects and the remedy differs between them — nobody joining a circle
     * protects personal history. See [PlanCardCopy.historyExpiring]. */
    @SerializedName("history_expires_circle_id") val historyExpiresCircleId: String? = null,
    @SerializedName("smart_alerts_enabled") val smartAlertsEnabled: Boolean = false,
    @SerializedName("destinations_enabled") val destinationsEnabled: Boolean = false,
    @SerializedName("web_push_enabled") val webPushEnabled: Boolean = false,
)

// ===== Auth Config =====

/** Auth service configuration — currently just the Turnstile site key. */
data class AuthConfigResponse(
    @SerializedName("turnstileSiteKey") val turnstileSiteKey: String?
)

// ===== Auth DTOs =====


data class LoginRequest(
    val email: String,
    @SerializedName("password_hash") val passwordHash: String
)

data class AuthResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    val user: UserResponse
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

/**
 * Body for PATCH /api/v1/auth/sessions/:id — renames the user-visible
 * "device" label that appears in the Devices section of the account UI.
 * Null clears the override and falls back to the auto-detected
 * User-Agent string.
 */
data class RenameSessionRequest(
    @SerializedName("device_name") val deviceName: String?
)

/** Single row from GET /api/v1/auth/sessions. */
data class SessionInfo(
    val id: String,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("ip_address") val ipAddress: String?,
    @SerializedName("last_used_at") val lastUsedAt: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("is_current") val isCurrent: Boolean,
)

data class SessionsListResponse(
    val sessions: List<SessionInfo>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * Single row from the Track server's `GET /api/v1/tracking-devices`.
 * Unlike the auth server's sessions, this only includes devices that
 * actually push location into the user's account (active push
 * registrations). Web logins / Note app logins / admin dashboards never
 * appear here.
 *
 * The session_id back-link is the canonical identifier for revocation;
 * sign-out still flows through `DELETE /api/v1/auth/sessions/:id`, and
 * revoking the session deactivates this device's push registration.
 */
data class TrackingDevice(
    val id: String,
    @SerializedName("session_id") val sessionId: String?,
    val platform: String,
    @SerializedName("push_type") val pushType: String,
    @SerializedName("last_seen_day_bucket") val lastSeenDayBucket: Int?,
    @SerializedName("created_day_bucket") val createdDayBucket: Int,
    @SerializedName("is_current") val isCurrent: Boolean,
)

/**
 * One active push registration the SERVER holds for this session, from
 * `GET /api/v1/push/registration`.
 *
 * Exists because local state alone cannot tell "push was never set up" apart
 * from "this install lost track of a registration that works" — and reporting
 * the second as the first would tell a user with working notifications that
 * they had none.
 *
 * Unlike [TrackingDevice] this is not filtered on device_id, so a UnifiedPush
 * row registered through the endpoint path (device_id NULL) is included.
 */
data class PushRegistration(
    @SerializedName("push_type") val pushType: String,
    /** Delivery address; `webpush` only. Used to restore a lost local copy. */
    val endpoint: String?,
    /** A device token is on file. The token itself is never sent back. */
    @SerializedName("has_token") val hasToken: Boolean,
    val paused: Boolean,
    /** Last push this channel accepted; survives the 7-day queue retention. */
    @SerializedName("last_success_at") val lastSuccessAt: String?,
)

/**
 * Body returned by `POST /api/v1/auth/refresh`.
 *
 * Two flows are supported by the server:
 *  - **Refresh-token flow (preferred):** client sends `X-Refresh-Token`,
 *    server rotates it via atomic CAS and returns the new value here.
 *  - **Legacy JWT flow:** client sends only the (possibly expired) JWT
 *    via Authorization; the server attaches a freshly generated
 *    refresh token to the session — also returned here — and from
 *    then on the client MUST use the refresh-token flow.
 *
 * `refreshToken` is therefore always populated on a 200 response;
 * clients persist it and send it back on the next refresh.
 */
data class RefreshAccessTokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String
)

data class SubscriptionInfo(
    val service: String = "",
    val plan: String = "",
    val status: String = "",
    @SerializedName("product_slug") val productSlug: String? = null,
)

data class UserResponse(
    val id: String,
    val email: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("public_key") val publicKey: String,
    @SerializedName("encrypted_private_key") val encryptedPrivateKey: String?,
    @SerializedName("email_verified") val emailVerified: Boolean,
    @SerializedName("created_at") val createdAt: String,
    val subscriptions: List<SubscriptionInfo>? = null,
    /** Optional second address that can receive a password-reset link. Omitted
     * by the server when unset, so null here means "no recovery address", not
     * "field missing". */
    @SerializedName("recovery_email") val recoveryEmail: String? = null,
    @SerializedName("recovery_email_verified") val recoveryEmailVerified: Boolean = false,
    /**
     * Somebody else set this account up and holds its password — a guardian who
     * created it for a young person.
     *
     * Read back from the server so the app can tell the account's own user.
     * Drives the standing line on the map.
     */
    @SerializedName("guardian_managed") val guardianManaged: Boolean = false,
    /**
     * The day the creating guardian named as the handover, "YYYY-MM-DD", or
     * null if they named none.
     *
     * Kept as the server's string and compared lexicographically against
     * today's ISO date. Parsing it into a date type would buy nothing and
     * introduce a timezone: a DATE has no zone, and turning it into an instant
     * is how a prompt fires a day early in one place and a day late in another.
     */
    @SerializedName("guardian_handover_date") val guardianHandoverDate: String? = null,
    /**
     * "pending" while an assisted-lane account waits for an admin's approval: the auth server's
     * token then carries no Track audience, so every Track call is refused until it is approved.
     */
    @SerializedName("registration_status") val registrationStatus: String? = null,
) {
    /** True if user has any active or trialing subscription */
    val isPremium: Boolean
        get() = subscriptions?.any { it.status == "active" || it.status == "trialing" } == true

    val tierLabel: String
        get() = if (isPremium) "Premium" else "Free"
}

/** Effective premium entitlement from the Track server: own subscription OR
 * circle-derived (any member of a circle the user is in is subscribed).
 * `source` is "own", "circle", or "none". */
data class PremiumStatusResponse(
    @SerializedName("has_premium") val hasPremium: Boolean,
    val source: String,
)

data class UpdateProfileRequest(
    @SerializedName("display_name") val displayName: String?
)

// ===== Track Profile (separate from auth) =====

/** Display name lives in track, not auth: it identifies a member within a
 *  circle, and auth deliberately holds no circle context. */
data class ProfileResponse(
    @SerializedName("display_name") val displayName: String?
)

data class UpdateTracksProfileRequest(
    @SerializedName("display_name") val displayName: String?
)

data class ChangePasswordRequest(
    @SerializedName("current_password_hash") val currentPasswordHash: String,
    @SerializedName("new_password_hash") val newPasswordHash: String
)

data class PasswordResetRequest(
    val email: String
)

data class VerifyEmailRequest(
    val token: String
)

data class ResendVerificationRequest(
    val email: String
)

data class EmailVerificationStatusResponse(
    @SerializedName("email_verified") val emailVerified: Boolean,
    val email: String,
    @SerializedName("verification_sent_at") val verificationSentAt: String?,
    @SerializedName("can_resend") val canResend: Boolean
)

// ===== SRP (Secure Remote Password) Auth DTOs =====

/**
 * SRP Step 1 Request: Client initiates authentication
 */
data class SrpStep1Request(
    val email: String
)

/**
 * POST /api/v1/auth/logout: the session this refresh token belongs to is ended, whether or
 * not the access token sent with it is still valid. Null: only the Bearer names it.
 */
data class LogoutRequest(
    @SerializedName("refresh_token") val refreshToken: String?
)

/**
 * SRP Step 1 Response: Server returns salt, public value B, and bcrypt salt
 */
data class SrpStep1Response(
    val salt: String,
    @SerializedName("server_public") val serverPublic: String,
    @SerializedName("session_id") val sessionId: String,
    /** bcrypt salt for Proton-style password preprocessing (may be null for legacy accounts) */
    @SerializedName("bcrypt_salt") val bcryptSalt: String?,
    /**
     * Re-auth step 1 only: the address this challenge's verifier was made with — the current
     * one, after an email change too. The proof is made against it, not the one remembered.
     */
    val email: String? = null,
)

/**
 * SRP Step 2 Request: Client sends public value A and proof M1
 */
data class SrpStep2Request(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("client_public") val clientPublic: String,
    @SerializedName("client_proof") val clientProof: String
)

/**
 * SRP Step 2 Response: Server proof and login data
 */
data class SrpStep2Response(
    @SerializedName("server_proof") val serverProof: String,
    val user: SrpUserResponse?,
    @SerializedName("kdf_params") val kdfParams: KdfParamsResponse?,
    @SerializedName("encrypted_keys") val encryptedKeys: EncryptedKeysResponse?,
    @SerializedName("sharing_public_key") val sharingPublicKey: String?,
    @SerializedName("signing_public_key") val signingPublicKey: String?,
    @SerializedName("access_token") val accessToken: String?,
    /**
     * Opaque refresh token bound to this session — clients send it to
     * /auth/refresh via `X-Refresh-Token`. Nullable because the 2FA
     * branch of this response doesn't include it (the user hasn't
     * completed login yet); only the success branch sets it.
     */
    @SerializedName("refresh_token") val refreshToken: String?,
    // 2FA fields (if required)
    @SerializedName("requires_2fa") val requires2fa: Boolean?,
    @SerializedName("two_factor_token") val twoFactorToken: String?,
    @SerializedName("available_methods") val availableMethods: List<String>?,
    @SerializedName("preferred_method") val preferredMethod: String?
)

/**
 * Re-auth SRP Step 2 Response: short-lived reauth token that stands in
 * for the raw password on sensitive operations (e.g. account deletion).
 */
data class ReauthStep2Response(
    @SerializedName("reauth_token") val reauthToken: String,
    @SerializedName("server_proof") val serverProof: String? = null,
    /**
     * What the password opens, on THIS session: as in the sign-in reply.
     * Absent only for an account with no keys at all.
     */
    @SerializedName("kdf_params") val kdfParams: KdfParamsResponse? = null,
    @SerializedName("encrypted_keys") val encryptedKeys: EncryptedKeysResponse? = null,
    @SerializedName("sharing_public_key") val sharingPublicKey: String? = null,
    @SerializedName("signing_public_key") val signingPublicKey: String? = null,
)

data class SrpUserResponse(
    val id: String,
    val email: String,
    @SerializedName("display_name") val displayName: String? = null
)

data class KdfParamsResponse(
    val salt: String,
    @SerializedName("memory_kib") val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int
)

data class EncryptedKeysResponse(
    @SerializedName("master_key") val masterKey: EncryptedBlobResponse,
    @SerializedName("sharing_private_key") val sharingPrivateKey: EncryptedBlobResponse,
    @SerializedName("signing_private_key") val signingPrivateKey: EncryptedBlobResponse
)

data class EncryptedBlobResponse(
    val nonce: String,
    val ciphertext: String,
    @SerializedName("key_id") val keyId: String
)


data class KdfParamsRequest(
    val salt: String,
    @SerializedName("memory_kib") val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int
)

data class EncryptedBlobRequest(
    val nonce: String,
    val ciphertext: String
)

data class StoreSelfKeyRequest(
    @SerializedName("encrypted_self_key") val encryptedSelfKey: EncryptedBlobRequest,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    /**
     * True only when re-publishing a key this device already holds, which is the one
     * overwrite that is safe, or minting over a copy that opens only through an identity
     * from before a password reset (the server keeps the replaced copy, retired). Every
     * other mint leaves it absent so the server is free to refuse it; see
     * [com.pingolin.track.domain.repository.LocationRepository.republishSelfKey].
     *
     * Nullable so Gson omits it for those callers rather than sending `false`.
     */
    @SerializedName("replace") val replace: Boolean? = null,
    /**
     * Our Ed25519 signature over this exact wrap, as our own: without one, a device
     * holding no personal key would accept a wrap the server made up. Re-publishing the
     * IDENTICAL wrap with a signature is how an unsigned server copy gets signed; the
     * replace guard always allows identical bytes. Omitted when null.
     */
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
    /**
     * The account's pseudonym under exactly these bytes (the SDK's `publishablePersonalKey`),
     * so the server records it atomically with them: the record follows the PUBLISHED copy.
     * Omitted when null.
     */
    @SerializedName("user_pseudonym") val userPseudonym: String? = null,
)

/**
 * `POST /users/me/self-key/unreadable`: which copy this device could not use — sha256
 * over the raw bytes ciphertext ‖ nonce ‖ ephemeral public key, hex.
 */
data class SelfKeyUnreadableRequest(@SerializedName("copy_sha256") val copySha256: String)

data class SelfKeyUnreadableResponse(
    val requested: Boolean = false,
    /** This report woke the account's other phones (not more than once per copy, or per 15 min). */
    val woken: Boolean = false,
    /** The copy reported is no longer the stored one: nothing was asked; read it again. */
    @SerializedName("copy_changed") val copyChanged: Boolean = false,
)

/**
 * `PUT /users/me/self-key/pseudonym`: point the account's record at the pseudonym under
 * the key the stored copy carries — only while that exact copy is stored (a
 * compare-and-set on its bytes).
 */
data class RecordSelfPseudonymRequest(
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedBlobRequest,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("user_pseudonym") val userPseudonym: String,
)

data class RecordSelfPseudonymResponse(val recorded: Boolean = false)

/** Body of `PUT /api/v1/circles/{id}/wrap-signature` — see TrackApi.signOwnWrap. */
data class OwnWrapSignatureRequest(
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("wrap_signature") val wrapSignature: String,
)

data class OwnWrapSignatureResponse(
    val signed: Boolean = false,
)

data class SrpRegisterResponse(
    @SerializedName("user_id") val userId: String,
    val email: String,
    @SerializedName("email_verification_sent") val emailVerificationSent: Boolean,
    @SerializedName("access_token") val accessToken: String,
    /** Opaque refresh token; persist alongside accessToken. */
    @SerializedName("refresh_token") val refreshToken: String
)

// ===== Device DTOs =====

/**
 * Device name and type travel encrypted under the user's master key, so the
 * server holds a device list it cannot read.
 *
 * Push notification types:
 * - "fcm": Firebase Cloud Messaging (Google)
 * - "unifiedpush": UnifiedPush protocol (self-hosted, ntfy, etc.)
 * - null: no push notifications
 */
data class CreateDeviceRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("encrypted_device_name") val encryptedDeviceName: EncryptedBlobRequest,
    @SerializedName("encrypted_device_type") val encryptedDeviceType: EncryptedBlobRequest,
    @SerializedName("push_token") val pushToken: String?,
    @SerializedName("push_type") val pushType: String?,
    @SerializedName("push_endpoint") val pushEndpoint: String? = null
)

/** Device name and type come back encrypted; only this user can read them. */
data class DeviceResponse(
    val id: String,
    @SerializedName("encrypted_device_name") val encryptedDeviceName: EncryptedBlobResponse,
    @SerializedName("encrypted_device_type") val encryptedDeviceType: EncryptedBlobResponse,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("last_seen_day_bucket") val lastSeenDayBucket: Int?,
    @SerializedName("day_bucket") val dayBucket: Int,
    /**
     * Whether the server actually registered this device for push.
     * FALSE means the id is real but NOT addressable: nothing can be
     * pushed to it, and every device-gated endpoint (SOS, check-ins, ingest)
     * will answer "Device not found".
     *
     * Nullable because older backends omit it — callers fall back to inferring
     * it from whether the request carried a token.
     */
    @SerializedName("push_registered") val pushRegistered: Boolean? = null
)

/** Sent when a push service issues a new token or endpoint. Same provider
 *  values as [CreateDeviceRequest]. */
data class UpdatePushTokenRequest(
    @SerializedName("push_token") val pushToken: String?,
    @SerializedName("push_type") val pushType: String,
    @SerializedName("push_endpoint") val pushEndpoint: String? = null
)

// ===== Circle DTOs =====

data class CreateCircleRequest(
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    /** The creator's agreement to the location notice, recorded with the creation. */
    @SerializedName("location_consent") val locationConsent: GrantLocationConsentRequest? = null,
    /** The id reserve-id gave this account, and the genesis record that binds it. Together or neither. */
    @SerializedName("circle_id") val circleId: String? = null,
    val genesis: RefreshRecordPayload? = null,
)

/** `POST /api/v1/circles/reserve-id`: an id for a circle this account is about to create. */
data class ReservedCircleIdResponse(
    @SerializedName("circle_id") val circleId: String,
    /** Live for 10 minutes, used once. */
    @SerializedName("expires_at") val expiresAt: String? = null,
)

/** A refresh record as a creation or a key change carries it (`SignedRefreshRecord`), both hex. */
data class RefreshRecordPayload(
    val record: String,
    val signature: String,
)

/** Accepting an invitation IS agreeing to share with the circle. */
data class AcceptInviteRequest(
    @SerializedName("location_consent") val locationConsent: GrantLocationConsentRequest,
)

data class CircleResponse(
    val id: String,
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    @SerializedName("encrypted_key") val encryptedKey: EncryptedPayload?,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String?,
    @SerializedName("member_count") val memberCount: Int,
    @SerializedName("is_owner") val isOwner: Boolean,
    /** Day bucket (days since Unix epoch) when circle was created */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** True if key rotation is needed (e.g., a member left) */
    @SerializedName("key_rotation_needed") val keyRotationNeeded: Boolean?,
    /** Current key version for this circle */
    @SerializedName("key_version") val keyVersion: Int?,
    /** Pseudonymous user id of the member who wrapped this circle key for
     * the current user. Fed into [CryptoManager.storeCircleKey] for
     * verify-on-store. Null on legacy wraps. */
    @SerializedName("sender_user_id") val senderUserId: String? = null,
    /** Hex-encoded Ed25519 signature over the wrap context, verified against
     *  the sender's locally-pinned signing key. Null on legacy wraps → the SDK
     *  grace-accepts. */
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
    /** The caller's OWN privacy posture in this circle: the same object
     *  GET /api/v1/privacy/circles/{id} returns (the server builds both from one
     *  column list and one serializer). Absent from older servers, and absent when
     *  the caller has no privacy row — exactly when that route answers 404 — so a
     *  null here always means "ask the privacy route", never "no restrictions". */
    @SerializedName("my_privacy") val myPrivacy: PrivacySettingsResponse? = null,
    /**
     * Why a rotation is pending, while [keyRotationNeeded] is set: `member_left`,
     * `member_removed`, `member_joined`, `periodic` or `other`. After a departure the
     * person who left still holds the live key until the circle is re-keyed, which
     * owners and admins are told.
     */
    @SerializedName("key_rotation_cause") val keyRotationCause: String? = null,
    /**
     * Version of the caller's OWN stored wrap, which [keyVersion] is not: that one is
     * where the circle has moved to. They differ for a member a rotation left pending.
     * Absent when the caller holds no wrap, and from older servers.
     */
    @SerializedName("member_key_version") val memberKeyVersion: Int? = null,
    /**
     * The key version the circle's name is sealed under: a first copy of the circle is
     * checked against the name only when it is this version. Absent from older servers;
     * then nothing is checked.
     */
    @SerializedName("name_key_version") val nameKeyVersion: Int? = null,
    /**
     * Some OTHER member is owed the circle's current copy: they hold none, an older one,
     * or asked for a fresh one. Which of them, and whether this device
     * may hand it over, comes from the member list. Absent (false) from older servers.
     */
    @SerializedName("delivery_needed") val deliveryNeeded: Boolean = false,
    /**
     * Owners/admins: a pending invitation of this circle waits to be renewed. Whether this
     * device asks about it comes from the invitation itself.
     */
    @SerializedName("invites_need_renewal") val invitesNeedRenewal: Boolean = false,
)

/** Circle key bundle for oblivious key distribution.
 *
 * The provenance fields (`senderUserId`, `recipientUserId`, `wrapSignature`,
 * `senderSigningPubkey`) are populated for keys wrapped with a wrap
 * signature. They feed into [CryptoManager.storeCircleKey]
 * for verify-on-store. Null on legacy keys —
 * the SDK falls back to "unsigned" handling and a warning log line.
 */
data class CircleKeyBundleResponse(
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("key_chain") val keyChain: List<KeyChainLinkResponse>? = null,
    @SerializedName("sender_user_id") val senderUserId: String? = null,
    @SerializedName("recipient_user_id") val recipientUserId: String? = null,
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
    @SerializedName("sender_signing_pubkey") val senderSigningPubkey: String? = null,
    /**
     * SELF KEY ONLY: the self-tracking pseudonym the server has recorded, i.e.
     * the one derived from the key this account's devices are actually
     * ENCRYPTING under. Null for circle keys and for a self key nothing has ever
     * sent under.
     *
     * The name must match the server's `user_pseudonym` exactly — Gson drops
     * unknown fields, so a typo reads as null and every check below silently
     * passes.
     */
    @SerializedName("user_pseudonym") val userPseudonym: String? = null,
    /**
     * SELF KEY ONLY: another device of this account could not use the served copy and
     * asked (within 7 days) for it to be put right.
     */
    @SerializedName("republish_requested") val republishRequested: Boolean = false,
)

/**
 * `POST /api/v1/circles/refresh-records`. [since] is, per circle, the head this device
 * accepted (the SDK's `refreshHeadVersions`); a circle it doesn't name is served from 0.
 * The reply always covers every circle this account is an active member of, so the
 * server can't tell which circle is about to be used.
 */
data class RefreshRecordsRequest(
    val since: Map<String, Int>,
)

data class RefreshRecordsResponse(
    val circles: List<CircleRefreshRecords> = emptyList(),
    /** More records wait: ask again with each circle's since raised to the last version served. */
    val more: Boolean = false,
)

data class CircleRefreshRecords(
    @SerializedName("circle_id") val circleId: String,
    /** The circle's records above its since, oldest first. */
    val records: List<ServedRefreshRecord> = emptyList(),
    /** The heads of the circle's active members, this account's own included; with the last page only. */
    val heads: List<ServedCircleHead> = emptyList(),
    /** Every record above since is in this reply, and so are the heads. */
    val complete: Boolean = false,
)

/** A record as served: `BODY || key_tag` (a transition: `|| anchor_tag`) and its signature, both hex. */
data class ServedRefreshRecord(
    val version: Int,
    val record: String,
    val signature: String,
)

/** An account head as served: the 69 signed bytes and the signature, both hex. */
data class ServedCircleHead(
    @SerializedName("user_id") val userId: String,
    val head: String,
    val signature: String,
)

/** `PUT /api/v1/circles/{id}/head`: this account's head, hex. */
data class PutHeadRequest(
    val head: String,
    val signature: String,
)

data class PutHeadResponse(
    /** Kept: a first head, a higher version, or the same head signed again (after a reset). */
    val stored: Boolean = false,
)

data class KeyChainLinkResponse(
    @SerializedName("from_version") val fromVersion: Int,
    @SerializedName("to_version") val toVersion: Int,
    @SerializedName("encrypted_key") val encryptedKey: String,
    val nonce: String
)

data class InviteRequest(
    val email: String,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload? = null,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String? = null,
    /** Only for an address the lookup answered has no account: e-mailed to them, kept nowhere. */
    @SerializedName("circle_name") val circleName: String? = null,
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
    /**
     * The signed invitation: canonical statement bytes and the
     * inviter's signature, hex. Both or neither; older servers ignore them.
     */
    val statement: String? = null,
    @SerializedName("statement_signature") val statementSignature: String? = null,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

data class UserPublicKeyResponse(
    val email: String,
    @SerializedName("sharing_public_key") val sharingPublicKey: String?,
    // Invite-time key-substitution defense: the server also returns the
    // invitee's pseudonymous user_id and Ed25519 signing key. The inviter keys a
    // TOFU pin on (circle_id, user_id) and re-checks the sharing key against it
    // before wrapping the live circle key, so a server that swaps the sharing key
    // on a re-invite of an already-pinned member is caught (fail closed). Both are
    // absent for unmigrated accounts / no-match emails.
    @SerializedName("user_id") val userId: String? = null,
    @SerializedName("signing_public_key") val signingPublicKey: String? = null
)

/**
 * The server's answer to an invitation: `invite_token` and `delivery`. It names neither the
 * invitation nor its circle. The token is the invitee's, and is left unread here: nothing in
 * this app uses it, and a data class would print it. Nothing more may be required here: a
 * required field the server does not send would refuse every invitation here after the
 * server had made it.
 */
data class InviteResponse(
    /** "notified", "emailed_no_account", or "emailed_lookup_failed" (the last only from older servers). */
    val delivery: String? = null,
    /** Not sent by the server. One that is sent must name the circle it was sent in
     * (CanonicalIdsTrackApi).
     */
    @SerializedName("circle_id") val circleId: String? = null,
)

/** Owner-only; the new owner must already be a member. */
data class TransferOwnershipRequest(
    @SerializedName("new_owner_id") val newOwnerId: String
)

/**
 * The live ownership claim on a circle — `GET`/`POST /circles/{id}/claim-ownership`.
 *
 * ```json
 * {"success":true,"data":{
 *   "claim_id":"...","circle_id":"...","claimant_user_id":"...",
 *   "status":"pending","requested_at":"...","resolves_at":"..."}}
 * ```
 *
 * A circle has exactly one owner and no way to add a second, so an owner who becomes
 * unreachable freezes handover, deletion and the roster permanently — nobody can undo
 * that from the server side either. An active ADMIN can therefore open a claim: the
 * owner is told at once, and one action refuses it for good. Only silence for the whole
 * window completes it, and that window is fourteen days.
 *
 * Every field is nullable, for the reason spelled out on [RotateKeysResponse]: Retrofit's
 * Gson is plain (`di/AppModule.kt`: `GsonBuilder().create()`), so a field whose name does
 * not match the server's is left at its JVM default instead of failing. That is silent,
 * and for this endpoint the silence is dangerous: a deadline read as 0 or a claim read as
 * absent both end with the owner never being shown the refuse control.
 *
 * [resolvesAt] and [requestedAt] stay STRINGS on purpose. They are RFC3339 with an offset,
 * and the server writes sub-second digits only when they are non-zero (none at all, not
 * even a decimal point, on a whole second), so the exact format varies between two
 * responses from the same server. Parse with `Instant.parse`, which accepts both, and
 * render in the DEVICE's zone. Never a zone-less `SimpleDateFormat`: it reads the instant
 * as local time and moves the deadline by the offset, which on this screen is the
 * difference between refusing in time and not.
 */
data class OwnershipClaimResponse(
    @SerializedName("claim_id") val claimId: String? = null,
    @SerializedName("circle_id") val circleId: String? = null,
    /** Who asked. Resolvable to a display name through the circle's member list. */
    @SerializedName("claimant_user_id") val claimantUserId: String? = null,
    /**
     * Always `"pending"` in practice: the GET filters `status = 'pending'`, so a refused,
     * withdrawn, completed or voided claim is not returned at all — it simply stops being
     * there. Do not build UI that waits to observe a terminal status on this endpoint.
     */
    val status: String? = null,
    @SerializedName("requested_at") val requestedAt: String? = null,
    /** When silence completes the claim. The one number the owner can act on. */
    @SerializedName("resolves_at") val resolvesAt: String? = null
)

data class PendingInviteResponse(
    val id: String,
    @SerializedName("invite_type") val inviteType: String?,
    @SerializedName("target_email") val targetEmail: String?,
    val status: String?,
    @SerializedName("created_day_bucket") val createdDayBucket: Int,
    @SerializedName("expires_day_bucket") val expiresDayBucket: Int,
    @SerializedName("has_encrypted_key") val hasEncryptedKey: Boolean = false,
    // What renewing it needs.
    @SerializedName("invited_by") val invitedBy: String? = null,
    @SerializedName("target_user_id") val targetUserId: String? = null,
    val statement: String? = null,
    @SerializedName("statement_signature") val statementSignature: String? = null,
    @SerializedName("key_version") val keyVersion: Int? = null,
    /** The invitee's app found it does not name them as they are now (or the accept said so). */
    @SerializedName("renewal_requested") val renewalRequested: Boolean = false,
    @SerializedName("needs_renewal") val needsRenewal: Boolean = false,
)

/** `PUT /circles/{id}/invites/{invite_id}/statement`: the renewed invitation. */
data class RenewInvitationRequest(
    val statement: String,
    @SerializedName("statement_signature") val statementSignature: String,
    /** The circle's copy for the keys shown in the question; kind 1 only, optional. */
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload? = null,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String? = null,
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
)

data class RenewInvitationResponse(
    val renewed: Boolean = false,
    @SerializedName("key_version") val keyVersion: Int? = null,
)

// ===== Location consent =====

/**
 * The location-processing notice as the server serves it.
 *
 * Fetched, not bundled. The server records which text a consent was given
 * against (a digest of the notice), so rendering a local paraphrase
 * would file a record naming a text the user never read.
 */
data class LocationConsentNoticeResponse(
    val version: String,
    val digest: String,
    /** Markdown; see LocationConsentDialog for the subset that is rendered. */
    val text: String,
)

/**
 * The agreement to the location notice, carried by the accept and the creation it makes
 * possible: its presence IS the consent.
 */
data class GrantLocationConsentRequest(
    @SerializedName("client_notice_version") val clientNoticeVersion: String,
    val surface: String = "android",
)

/** Current consent state for one circle. */
data class LocationConsentResponse(
    @SerializedName("circle_id") val circleId: String,
    /** `granted` | `withdrawn` | `none`. */
    val status: String,
    /** `presented` | `implied`. An `implied` row is NOT a consent. */
    val mode: String? = null,
    @SerializedName("notice_version") val noticeVersion: String? = null,
    @SerializedName("granted_at") val grantedAt: String? = null,
    @SerializedName("withdrawn_at") val withdrawnAt: String? = null,
    @SerializedName("withdrawal_reason") val withdrawalReason: String? = null,
    @SerializedName("notice_outdated") val noticeOutdated: Boolean = false,
)

data class ReceivedInviteResponse(
    val id: String,
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("invited_by") val invitedBy: String,
    @SerializedName("invite_type") val inviteType: String,
    @SerializedName("created_day_bucket") val createdDayBucket: Int,
    @SerializedName("expires_day_bucket") val expiresDayBucket: Int,
    @SerializedName("has_encrypted_key") val hasEncryptedKey: Boolean,
    /**
     * Not served by current servers: an invitation names its inviter, not the circle, until the
     * person joins. Read only from an older server's answer; every screen has a nameless form.
     */
    @SerializedName("circle_name") val circleName: String? = null,
    @SerializedName("encrypted_circle_name") val encryptedCircleName: String? = null,
    @SerializedName("circle_name_nonce") val circleNameNonce: String? = null,
    @SerializedName("inviter_display_name") val inviterDisplayName: String? = null,
    /** The signed invitation, checked against this account before accepting. */
    val statement: String? = null,
    @SerializedName("statement_signature") val statementSignature: String? = null,
    /**
     * It waits to be renewed: it no longer names this account as it is now, and the
     * circle has been asked to confirm it's them.
     */
    @SerializedName("renewal_requested") val renewalRequested: Boolean = false,
)

/** `POST /invites/{id}/mismatch`. */
data class InviteMismatchResponse(val reported: Boolean = false)

// ===== Blocked people =====

/**
 * One address on this person's list of blocked people (`GET api/v1/invites/blocks`): its owner
 * may not invite them to a circle. The server keeps it trimmed and in lower case.
 */
data class BlockedPersonResponse(
    @SerializedName("blocked_email") val blockedEmail: String,
    /** RFC 3339, kept as the text it is (see [OwnershipClaimResponse]). Not shown. */
    @SerializedName("created_at") val createdAt: String? = null,
)

/** `POST api/v1/invites/block`, and `POST api/v1/invites/unblock`: the address, in the body. */
data class BlockPersonRequest(val email: String)

/**
 * The client generates the new key locally and wraps it for every current
 * member; the server never sees it unwrapped. Required after anyone leaves —
 * until it runs, the departed member's copy still opens new traffic.
 */
data class RotateKeysRequest(
    /**
     * Encrypted name with new key (re-encrypted). Null (omitted) when a repair after a
     * misattribution has no name read here to seal: the server keeps the circle's.
     */
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload?,
    /** Encrypted keys for all active members */
    @SerializedName("member_keys") val memberKeys: List<MemberKeyUpdate>,
    /** Expected key version for optimistic locking */
    @SerializedName("expected_key_version") val expectedKeyVersion: Int? = null,
    /** Key chain link: old key encrypted with new key for history access */
    @SerializedName("key_chain_link") val keyChainLink: Map<String, Any>? = null,
    /**
     * Active members this device deliberately leaves without the new key: an
     * unapproved key change, somebody whose access its user has not approved, or
     * an account the server lists for the key but not in the circle's member
     * list. Their stored wrap is left untouched. Null (omitted) when there are
     * none, which is also what an older server understands.
     */
    @SerializedName("pending_members") val pendingMembers: List<String>? = null,
    /** On a circle with refresh records, the record this key change carries. */
    @SerializedName("refresh_record") val refreshRecord: RefreshRecordPayload? = null,
)

/**
 * Encrypted key for a single member during key rotation.
 *
 * `wrapSignature` is the hex-encoded Ed25519 signature over the
 * pingolin.sig.v1.* wrap context, produced by the SDK when the rotating
 * client has a signing key loaded. May be null for legacy installs.
 */
data class MemberKeyUpdate(
    @SerializedName("user_id") val userId: String,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("wrap_signature") val wrapSignature: String? = null
)

/**
 * `signingPublicKey` is the hex-encoded Ed25519 signing key. May be null for
 * members who haven't uploaded one.
 */
data class MemberPublicKeyResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("sharing_public_key") val sharingPublicKey: String,
    @SerializedName("signing_public_key") val signingPublicKey: String? = null,
    /**
     * Version of this member's STORED wrap; null when they hold none (keyless).
     * A member is owed the current key when this is null or below the circle's
     * key_version. Absent altogether from older servers.
     */
    @SerializedName("key_version") val keyVersion: Int? = null,
    /** Only for members owed the current key: for the "Give <name> access?" prompt. */
    val email: String? = null,
)

/**
 * Response from `POST /api/v1/circles/:id/members/:userId/revoke-access`.
 *
 * [warning] is the server's own words for what a removal without a rotation
 * does and does not do. It is shown verbatim: the paraphrase everyone reaches
 * for is "they no longer have access", which is true of the API and false of
 * the data they already hold — and the person reading it is usually acting on
 * a device they no longer trust.
 */
data class RevokeAccessResponse(
    @SerializedName("revoked_user_id") val revokedUserId: String,
    @SerializedName("key_rotation_needed") val keyRotationNeeded: Boolean = true,
    @SerializedName("warning") val warning: String? = null,
)

/**
 * Body for `POST /api/v1/circles/:id/members/eject-and-rotate`.
 *
 * The caller fetches the current member set, generates a fresh circle key
 * via the SDK, wraps it for every surviving member (excluding the ejected
 * user), and submits the bundle atomically. Server enforces full coverage
 * and bumps `key_version`.
 */
data class EjectAndRotateRequest(
    @SerializedName("eject_user_id") val ejectUserId: String,
    @SerializedName("new_key_version") val newKeyVersion: Int,
    @SerializedName("member_wraps") val memberWraps: List<EjectMemberWrap>,
    /**
     * Circle name re-encrypted under the new key — the same field
     * [RotateKeysRequest] carries. An eject IS a rotation; omitting this
     * leaves the name readable only to holders of the outgoing key.
     */
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload? = null,
    /**
     * Old key encrypted under the new one, so a member holding only the
     * current key can still read pre-rotation data. Without it every eject
     * punches a permanent hole in the key chain — see the note on
     * [RotateKeysRequest.keyChainLink].
     */
    @SerializedName("key_chain_link") val keyChainLink: Map<String, Any>? = null,
    /** Survivors given no wrap of the new key — see [RotateKeysRequest.pendingMembers]. */
    @SerializedName("pending_members") val pendingMembers: List<String>? = null,
    /** On a circle with refresh records, the eject's record, naming them removed. */
    @SerializedName("refresh_record") val refreshRecord: RefreshRecordPayload? = null,
)

data class EjectMemberWrap(
    @SerializedName("user_id") val userId: String,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: String,
    @SerializedName("circle_key_nonce") val circleKeyNonce: String,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("wrap_signature") val wrapSignature: String? = null
)

/**
 * Response to `POST /circles/{id}/rotate-keys`.
 *
 * ```json
 * {"success":true,"data":{"new_key_version":2}}
 * ```
 *
 * The two endpoints that rotate a key answer with DIFFERENT shapes, so each has its
 * own DTO. Retrofit's Gson is plain (`di/AppModule.kt`: `GsonBuilder().create()`), so
 * a field whose name the server never sends is built through Unsafe and left at its
 * JVM default: version 0, flag false. A version 0 stored in Room would become the
 * `expected_key_version` of the NEXT rotation, which loses the optimistic lock by
 * construction.
 *
 * Every field is nullable on purpose. A primitive would read an absent key as 0/false —
 * indistinguishable from the server actually saying so.
 */
data class RotateKeysResponse(
    /** Version the circle key is now at. Null only if the server said nothing. */
    @SerializedName("new_key_version") val newKeyVersion: Int? = null,
    /**
     * Another client rotated first. NOT an error — the server answers the lost race
     * with 200 and this flag, so a client that cannot see the field
     * reads a peer's win as its own success and stores whatever version it guessed.
     * Re-fetch the circle instead of writing a version.
     */
    @SerializedName("already_rotated") val alreadyRotated: Boolean? = null
)

/**
 * Response to `POST /circles/{id}/members/eject-and-rotate` — a different shape from
 * [RotateKeysResponse], which is why they are two types:
 *
 * ```json
 * {"success":true,"data":{"new_key_version":3,"ejected_user_id":"<uuid>"}}
 * ```
 *
 * There is no `already_rotated` here: this endpoint reports a lost race as 409
 * `KEY_VERSION_MISMATCH` (the `new_key_version` must be exactly one greater check).
 */
data class EjectAndRotateResponse(
    @SerializedName("new_key_version") val newKeyVersion: Int? = null,
    @SerializedName("ejected_user_id") val ejectedUserId: String? = null
)

data class MemberResponse(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("encrypted_display_name") val encryptedDisplayName: EncryptedPayload? = null,
    val role: String,
    @SerializedName("can_see_realtime") val canSeeRealtime: Boolean = true,
    @SerializedName("can_see_history") val canSeeHistory: Boolean = true,
    @SerializedName("joined_day_bucket") val joinedDayBucket: Int? = null,
    val status: String? = null,
    /** Hex-encoded Ed25519 signing public key. May be null for legacy
     * members. */
    @SerializedName("signing_public_key") val signingPublicKey: String? = null,
    /** Hex-encoded Ed25519 signature on the wrapped circle key bound to this
     * member. May be null for legacy wraps. */
    @SerializedName("wrap_signature") val wrapSignature: String? = null,
    /** Server-side flag: may this member initiate a key rotation? Gates the
     *  auto-rotation hook. */
    @SerializedName("can_rotate_keys") val canRotateKeys: Boolean = false,
    /** Sharing (X25519) public key, hex-encoded. Used to compute the short
     * authentication string for out-of-band verification. May be null for
     * unmigrated members. */
    @SerializedName("sharing_public_key") val sharingPublicKey: String? = null,
    /** The version of this member's stored copy of the circle; absent when they hold none. */
    @SerializedName("key_version") val keyVersion: Int? = null,
    /** Who made (and signed) this member's stored copy. Absent on legacy wraps. */
    @SerializedName("wrap_signer_user_id") val wrapSignerUserId: String? = null,
    /** This member's stored copy itself, for checking who handed it over. */
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload? = null,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String? = null,
    /** The signed invitation this member joined with; absent without one. */
    val invitation: MembershipStatementDto? = null,
    /** Admissions (kind 3) owners/admins signed about this member. */
    val vouches: List<MembershipStatementDto>? = null,
    /** This member's app asked for a fresh copy of the circle. */
    @SerializedName("access_requested") val accessRequested: Boolean = false,
    /**
     * They were told their app is too old (no signing key) and asked to update, within
     * 30 days; gone once they have.
     */
    @SerializedName("update_requested") val updateRequested: Boolean = false,
    /**
     * Why this member's location may not be updating, for the rest of the circle: "on",
     * "paused" or "off". Nullable on purpose: Gson leaves a Kotlin default out, and an
     * older server sends nothing, which means "on".
     */
    @SerializedName("location_sharing") val locationSharing: String? = null,
    /** When a timed pause ends (RFC 3339, UTC) while [locationSharing] is "paused"; null otherwise. */
    @SerializedName("sharing_paused_until") val sharingPausedUntil: String? = null,
    /**
     * Whether this member shows the circle their location history: "on" or "off".
     * Null from an older server, which means "on".
     */
    @SerializedName("history_sharing") val historySharing: String? = null,
)

/** `POST /circles/{id}/members/{user_id}/update-request`. */
data class MemberUpdateRequestResponse(
    val requested: Boolean = false,
    /** False when they were already told within the last day. */
    val sent: Boolean = false,
)

/** A signed membership statement as the server relays it: the exact bytes and their signature, hex. */
data class MembershipStatementDto(
    val statement: String,
    val signature: String,
    /** Who the server says signed it; the statement names its signer, and that is what counts. */
    @SerializedName("signer_user_id") val signerUserId: String? = null
)

/** `PUT /circles/{id}/members/{user_id}/access`: hand a member the circle's CURRENT copy. */
data class DeliverAccessRequest(
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    @SerializedName("wrap_signature") val wrapSignature: String
)

data class DeliverAccessResponse(
    val delivered: Boolean? = null,
    @SerializedName("key_version") val keyVersion: Int? = null
)

/** `POST /circles/{id}/access/request`: this device cannot use its copy. */
data class AccessRequestResponse(
    val requested: Boolean? = null
)

/** `PUT /circles/{id}/members/{user_id}/statement`: an owner/admin's admission (kind 3). */
data class StoreStatementRequest(
    val statement: String,
    @SerializedName("statement_signature") val statementSignature: String
)

data class StoreStatementResponse(
    val stored: Boolean? = null
)

/**
 * `POST /circles/{id}/statements/held`: statement ids as 16 bytes of hex each, at most 20.
 */
data class StatementsHeldRequest(
    @SerializedName("statement_ids") val statementIds: List<String>
)

/** Only the ids the server holds, in the order asked; an id it doesn't hold is simply absent. */
data class StatementsHeldResponse(
    val held: List<HeldStatement> = emptyList()
)

data class HeldStatement(
    /** Hex, lower case. */
    @SerializedName("statement_id") val statementId: String,
    /** `pending_invitation`, `member_invitation` or `vouch`. */
    @SerializedName("held_as") val heldAs: String? = null
)

/** Owner-only, and the owner's own role cannot be changed.
 *  Valid roles: "admin", "member". */
data class UpdateMemberRoleRequest(
    val role: String
)

// ===== Session Key DTOs =====

/** A session key lets a run of messages share one wrap instead of paying a
 *  key-wrap per message. */
data class PublishSessionKeyRequest(
    @SerializedName("session_key_id") val sessionKeyId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("encrypted_session_key") val encryptedSessionKey: String,
    @SerializedName("key_nonce") val keyNonce: String,
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("expires_at") val expiresAt: Long
)

data class PublishSessionKeyResponse(
    @SerializedName("session_key_id") val sessionKeyId: String
)

/** Encrypted session key material for a peer to import. */
data class SessionKeyResponse(
    @SerializedName("session_key_id") val sessionKeyId: String,
    @SerializedName("encrypted_session_key") val encryptedSessionKey: String,
    @SerializedName("key_nonce") val keyNonce: String,
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("user_id") val userId: String,
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String
)

// ===== Location DTOs =====

/** /locations nests its rows one level deeper — `data.locations`.
 *
 * There is no cursor and no page: the response IS the whole requested window.
 * The server time-slices the range down to a fixed budget of 5,000 points
 * rather than returning the newest N with a cursor — precisely so that a
 * request for a year comes back spanning the year instead of ending a day in.
 * Its `limit` parameter is accepted and ignored for the same reason.
 *
 * Do not add `next_cursor`/`has_more` fields: the server never sends them, so
 * Gson would leave them null/false and every caller would stop after one
 * request believing it had reached the last page. A client row cap is the
 * truncating design the downsample exists to avoid. */
data class LocationHistoryResponse(
    val locations: List<EncryptedLocationResponse>,
    /** Max days of history the server is allowed to return for this caller
     *  (2 for free tier, 365 for premium). Compare against the requested
     *  range to detect silent clamping. */
    @SerializedName("history_days_allowed") val historyDaysAllowed: Int? = null,
    /** The downsample overflowed the server's hard row cap and the OLDEST
     * groups were dropped, so the far end of the window is missing.
     *
     * Sent only when true, so the `false` default is the honest reading
     * of an absent field. */
    @SerializedName("truncated") val truncated: Boolean = false
)

/**
 * Location response with encrypted metadata.
 *
 * SECURITY: userId and deviceId are encrypted with circle key to prevent
 * the server from learning who is sharing with whom (metadata protection).
 */
data class LatestLocationsWrapper(
    val locations: List<EncryptedLocationResponse> = emptyList(),
    @SerializedName("key_rotation") val keyRotation: Any? = null,
    /**
     * Where each member who turned real-time sharing off in the circle was when they did, frozen;
     * absent when there's none, and from an older server. Kept apart from [locations] so no alert
     * ever reads one as fresh: see LocationRepository.frozenLocations.
     */
    @SerializedName("last_known") val lastKnown: List<EncryptedLocationResponse>? = null,
)

data class EncryptedLocationResponse(
    /** Encrypted user ID - decrypt with circle key to get actual userId */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload?,
    /** Encrypted device ID - decrypt with circle key to get actual deviceId */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload?,
    /** Legacy: plaintext userId for backward compatibility (deprecated) */
    @SerializedName("user_id") val userId: String?,
    /** Legacy: plaintext deviceId for backward compatibility (deprecated) */
    @SerializedName("device_id") val deviceId: String?,
    /** Day bucket (days since Unix epoch) for efficient querying */
    @SerializedName("day_bucket") val dayBucket: Int?,
    /** Encrypted timestamp - precise timestamp encrypted with circle key */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload?,
    @SerializedName("encrypted_payload") val encryptedPayload: EncryptedPayload,
    /** Legacy: plaintext timestamp for backward compatibility (deprecated) */
    val timestamp: String? = null,
    /** Envelope encryption: wrapped msg_key (hex-encoded).
     *  When present, encrypted_payload was encrypted with a random msg_key,
     *  and this field contains msg_key encrypted with the circle/self key. */
    @SerializedName("wrapped_msg_key") val wrappedMsgKey: String? = null,
    /** Nonce used for wrapping msg_key (hex-encoded, 24 bytes). */
    @SerializedName("wrapped_msg_key_nonce") val wrappedMsgKeyNonce: String? = null,
    /** Key ID used for encryption. If starts with "s:", this is a session key ID. */
    @SerializedName("key_id") val keyId: String? = null,
    /**
     * Whether this member permits alerts about them in this circle (their
     * `allow_alerts_on_me` privacy setting), as the server reported it
     * ALONGSIDE THIS FIX.
     *
     * Plaintext beside the ciphertext, like `sharing_mode`, because it is the
     * server's own consent field rather than part of the sealed payload — and,
     * like `sharing_mode`, it is AN INSTRUCTION THE RECIPIENT CARRIES OUT. The
     * blind server never sees a subscriber-side crossing and so cannot suppress
     * it; this device is the only place the opt-out can be honoured.
     *
     * NULL = the server did not say = ALLOWED. Absence permits at every consent
     * gate in this system; reading it as denial would silently disable alerts for
     * every member with no privacy row, which is the default state.
     */
    @SerializedName("allow_alerts_on_me") val allowAlertsOnMe: Boolean? = null,
    /**
     * How this member shares in this circle, as the server reported it beside this fix:
     * "exact", "approximate", or "paused" (a pause that has run out). Plaintext beside the
     * ciphertext, like `allow_alerts_on_me`, and like it AN INSTRUCTION THE RECIPIENT CARRIES
     * OUT (under "approximate" the client MUST blur the coordinates before display): an
     * approximate member is drawn as an area, never a pin.
     *
     * NULL = an older server that does not say = shown as before.
     */
    @SerializedName("sharing_mode") val sharingMode: String? = null,
    /**
     * Under "approximate": the member's radius in whole miles, varied ±20 % afresh on every
     * response so a fixed value cannot fingerprint them. Never shown as a number; the area
     * keeps the largest one seen ([com.pingolin.track.util.ApproximateMembersStore]).
     */
    @SerializedName("approximate_radius_miles") val approximateRadiusMiles: Int? = null,
    /**
     * A late point: kept by its sender while it could not share with the circle and sent
     * afterwards as history. Shown in history like any other row, never judged for alerts.
     * Absent (false) on every other row.
     */
    @SerializedName("history_only") val historyOnly: Boolean = false
)

data class EncryptedPayload(
    val nonce: String,
    val ciphertext: String,
    /** Key ID used for encryption. If starts with "s:", this is a session key ID. */
    @SerializedName("key_id") val keyId: String? = null,
    /** Envelope encryption: wrapped msg_key (base64-encoded).
     *  Present when the payload was encrypted with a random per-message key. */
    @SerializedName("wrapped_msg_key") val wrappedMsgKey: String? = null,
    /** Nonce used for wrapping msg_key (base64-encoded). */
    @SerializedName("wrapped_msg_key_nonce") val wrappedMsgKeyNonce: String? = null
)

// ===== Place DTOs =====

data class CreatePlaceRequest(
    /** Client-supplied primary key, used only by a RESTORE. Normal creates omit
     *  it and the server mints one. Supplying the id the backup recorded is what
     *  makes restoring the same file twice converge: the insert is
     *  `ON CONFLICT (id) DO NOTHING` and a collision answers 409. */
    val id: String? = null,
    @SerializedName("circle_id") val circleId: String?,  // null for personal places
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    @SerializedName("encrypted_address") val encryptedAddress: EncryptedPayload?,
    /** Optional free-form notes. Backend field name is `encrypted_notes`. */
    @SerializedName("encrypted_notes") val encryptedNotes: EncryptedPayload? = null,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("radius_meters") val radiusMeters: Int,
    val icon: String?,
    val color: String?,
    /** Place type: "place" (50-2000m) or "boundary" (200-25000m). Defaults to "place". */
    @SerializedName("place_type") val placeType: String = "place",
    /** Optional geofence boundary radius in meters (200-25000). Null means no boundary. */
    @SerializedName("boundary_radius") val boundaryRadius: Int? = null,
    /** Owner-side wrap of the per-place key under the user's personal (self)
     *  key. Required for envelope-encrypted personal places, omitted (along
     *  with wrappedKeyNonce) on legacy circle-owned creates. The server
     *  enforces both-or-neither. */
    @SerializedName("wrapped_key") val wrappedKey: String? = null,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String? = null,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

data class PlaceResponse(
    val id: String,
    /** Circle ID. Null for personal places, nil UUID also maps to personal. */
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    @SerializedName("encrypted_address") val encryptedAddress: EncryptedPayload?,
    @SerializedName("encrypted_notes") val encryptedNotes: EncryptedPayload? = null,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("radius_meters") val radiusMeters: Int,
    val icon: String?,
    val color: String?,
    /** Place type. A legacy field the server does not send, so this MUST be nullable:
     * plain Gson ignores the Kotlin default and leaves a missing field null, which would
     * NPE the non-null PlaceEntity.placeType. The mapping defaults it to "place". */
    @SerializedName("place_type") val placeType: String? = null,
    /** Legacy geofence boundary radius; the server does not send it (always null). */
    @SerializedName("boundary_radius") val boundaryRadius: Int? = null,
    /** Server-maintained: true iff an active alert subscription targets this place.
     * Geofence monitoring is gated on this ("an alert turns monitoring on")
     * instead of the legacy placeType/boundaryRadius. */
    @SerializedName("alerts_enabled") val alertsEnabled: Boolean = false,
    @SerializedName("created_at") val createdAt: String,
    /** Circle IDs this place is shared with (envelope encryption) */
    @SerializedName("shared_circles") val sharedCircles: List<String>? = null,
    /** place_key wrapped under the recipient's key — the owner's personal key
     *  when the requester owns the place, the circle's key when reached via a
     *  share. Hex-encoded. None on legacy circle-owned places. */
    @SerializedName("wrapped_key") val wrappedKey: String? = null,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String? = null,
    /** When wrappedKey came from a share of the place (the requester isn't the
     * owner), the circle whose key wraps it. Null when wrappedKey came from
     * the owner-side wrap on the place. */
    @SerializedName("wrap_via_circle") val wrapViaCircle: String? = null,
    /** Pseudonymous owner user id, surfaced by the server only on
     *  share-recipient reads. Resolves to a display name via the
     *  local members list to render "Shared by <member>" attribution. */
    @SerializedName("user_id") val userId: String? = null,
    /** Server-authoritative ownership flag: true iff the requester created this
     * place. Preferred over the `wrapViaCircle == null` proxy, which misfires
     * for a non-creator member of a LEGACY circle place (they own no wrap yet
     * still can't updatePlace → 422). Nullable so an older server that omits
     * it still parses; callers fall back to `wrapViaCircle == null`. */
    @SerializedName("is_owner") val isOwner: Boolean? = null
)

data class UpdatePlaceRequest(
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload?,
    @SerializedName("encrypted_address") val encryptedAddress: EncryptedPayload? = null,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    @SerializedName("encrypted_notes") val encryptedNotes: EncryptedPayload? = null,
    @SerializedName("radius_meters") val radiusMeters: Int?,
    val icon: String?,
    val color: String?,
    @SerializedName("boundary_radius") val boundaryRadius: Int? = null,
    /** Present when migrating a legacy place that encrypts directly under the
     *  circle key over to envelope encryption. Both fields must travel
     *  together — the server enforces both-or-neither. */
    @SerializedName("wrapped_key") val wrappedKey: String? = null,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String? = null
)

data class SharePlaceRequest(
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("wrapped_key") val wrappedKey: String,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String
)

/**
 * Atomic legacy-to-envelope migration request. Server commits the
 * UPDATE (new ciphertexts + owner-wrap) and every place_shares INSERT
 * inside a single transaction — partial state is impossible.
 */
data class MigratePlaceToEnvelopeRequest(
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    @SerializedName("encrypted_address") val encryptedAddress: EncryptedPayload? = null,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("encrypted_notes") val encryptedNotes: EncryptedPayload? = null,
    @SerializedName("wrapped_key") val wrappedKey: String,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String,
    @SerializedName("circle_shares") val circleShares: List<CircleShareWrap> = emptyList(),
)

data class CircleShareWrap(
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("wrapped_key") val wrappedKey: String,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String,
)

// ===== Place Alert DTOs =====

/** Who the alert watches is encrypted; the server routes it without learning
 *  which member it is about. */
data class CreatePlaceAlertRequest(
    @SerializedName("place_id") val placeId: String,
    /** The circle the subscriber set this alert in — used by the server to route the
     *  notification, so an alert on a personal place shared to a circle still notifies. */
    @SerializedName("circle_id") val circleId: String?,
    /** Server-side reference for verification. Null = circle-wide alert (all members). */
    @SerializedName("target_user_id") val targetUserId: String?,
    /** E2EE: Encrypted target user ID (encrypted with the per-place key; circle key for
     *  legacy circle places). For circle-wide, encrypts the sentinel "all". */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    @SerializedName("alert_on_arrival") val alertOnArrival: Boolean,
    @SerializedName("alert_on_departure") val alertOnDeparture: Boolean,
    /** Active days (e.g., ["monday", "tuesday", "wednesday", "thursday", "friday"]) */
    @SerializedName("active_days") val activeDays: List<String>?,
    /** Start time for active window (e.g., "08:00") */
    @SerializedName("active_start_time") val activeStartTime: String?,
    /** End time for active window (e.g., "17:00") */
    @SerializedName("active_end_time") val activeEndTime: String?,
    /** IANA zone the window above is written in (e.g., "America/Los_Angeles"), so the
     *  server-side worker judges it the same way the local evaluator does. The column
     *  defaults to 'UTC', so leaving this null means every scheduled alert the SERVER
     *  routes fires at UTC clock times. See [PlaceAlertRules.deviceTimezoneId]. */
    @SerializedName("timezone") val timezone: String? = null,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_sms") val notifySms: Boolean,
    @SerializedName("is_recurring") val isRecurring: Boolean,
    /** Optional expiration timestamp (RFC3339) */
    @SerializedName("expires_at") val expiresAt: String?,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** BLAKE3(circle_key, "arrival"|"departure") server match hash — required for
     *  the alert to fire (the worker matches a mover's crossing hash against these). */
    @SerializedName("arrival_hash") val arrivalHash: String? = null,
    @SerializedName("departure_hash") val departureHash: String? = null,
    /** BLAKE3(circle_key, "enter"|"exit") server match hash. The Android mover
     *  reports boundary crossings hashed over "enter"/"exit", so the place-alerts
     *  worker only matches those crossings against these enter/exit hashes — the
     *  arrival/departure hashes above never match an enter/exit crossing. Co-populated
     *  with arrival/departure so newly created alerts actually fire. */
    @SerializedName("enter_type_hash") val enterTypeHash: String? = null,
    @SerializedName("exit_type_hash") val exitTypeHash: String? = null,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

/**
 * E2EE compliant place alert response.
 * Target user identity is encrypted - only circle members can decrypt.
 */
data class PlaceAlertResponse(
    val id: String,
    @SerializedName("place_id") val placeId: String,
    /** The circle this alert routes/fires under (the subscriber's chosen circle;
     * falls back server-side to the place's own circle). REQUIRED to re-derive the
     * match hashes under the correct key on a rotation — a personal place's alert is
     * homed to a REAL circle, not the device "self" key. Nullable so an older server
     * that omits it still parses; callers fall back to `place.circleId`. */
    @SerializedName("circle_id") val circleId: String? = null,
    /** Alert creator's user ID (acceptable as plaintext - they're viewing their own alerts) */
    @SerializedName("user_id") val userId: String,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    @SerializedName("alert_on_arrival") val alertOnArrival: Boolean,
    @SerializedName("alert_on_departure") val alertOnDeparture: Boolean,
    @SerializedName("active_days") val activeDays: List<String>?,
    @SerializedName("active_start_time") val activeStartTime: String?,
    @SerializedName("active_end_time") val activeEndTime: String?,
    /** True if the time window spans overnight (e.g., 22:00 - 06:00) */
    @SerializedName("is_overnight") val isOvernight: Boolean,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_sms") val notifySms: Boolean,
    @SerializedName("is_recurring") val isRecurring: Boolean,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("expires_at") val expiresAt: String?,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** TRUE after a circle key rotation — match hashes derived from the old key.
     *  Nullable so an older backend that omits it still parses. */
    @SerializedName("needs_hash_update") val needsHashUpdate: Boolean? = null,
    /**
     * The TARGET has withdrawn `allow_alerts_on_me` in this alert's consent
     * circle, so the alert is DORMANT: the server will not route it and this
     * device must not raise it. The row stays active and configured, so consent
     * returning restores it untouched.
     *
     * Nullable so an older backend that omits it still parses, and so absence
     * reads as ALLOWED.
     */
    @SerializedName("suppressed_by_target") val suppressedByTarget: Boolean? = null
)

/**
 * Request to update a place alert.
 */
data class UpdatePlaceAlertRequest(
    @SerializedName("alert_on_arrival") val alertOnArrival: Boolean,
    @SerializedName("alert_on_departure") val alertOnDeparture: Boolean,
    @SerializedName("active_days") val activeDays: List<String>?,
    @SerializedName("active_start_time") val activeStartTime: String?,
    @SerializedName("active_end_time") val activeEndTime: String?,
    /** Re-sent on every edit, not just creation: a subscriber who moves zones and
     *  retimes their window would otherwise keep the zone they first set it in. See
     *  [CreatePlaceAlertRequest.timezone]. */
    @SerializedName("timezone") val timezone: String? = null,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_sms") val notifySms: Boolean,
    @SerializedName("is_recurring") val isRecurring: Boolean,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("expires_at") val expiresAt: String?,
    @SerializedName("arrival_hash") val arrivalHash: String? = null,
    @SerializedName("departure_hash") val departureHash: String? = null,
    /** BLAKE3(circle_key, "enter"|"exit") server match hash. Co-populated with
     *  arrival/departure — the mover reports crossings over "enter"/"exit", so the
     *  worker matches those against enter/exit hashes (see CreatePlaceAlertRequest). */
    @SerializedName("enter_type_hash") val enterTypeHash: String? = null,
    @SerializedName("exit_type_hash") val exitTypeHash: String? = null
)

/** Body for POST /place-alerts/{id}/refresh-hashes — match hashes re-derived
 *  with the current circle key after a rotation. */
data class RefreshPlaceAlertHashesRequest(
    @SerializedName("arrival_hash") val arrivalHash: String? = null,
    @SerializedName("departure_hash") val departureHash: String? = null,
    /** BLAKE3(circle_key, "enter"|"exit") server match hash — re-derived alongside
     *  arrival/departure after a key rotation so enter/exit crossing matching also
     *  recovers (the mover reports crossings over "enter"/"exit"). */
    @SerializedName("enter_type_hash") val enterTypeHash: String? = null,
    @SerializedName("exit_type_hash") val exitTypeHash: String? = null
)

/**
 * E2EE compliant place alert event response (arrival/departure history).
 * All identifying information is encrypted - only circle members can decrypt.
 */
data class PlaceAlertEventResponse(
    val id: String,
    @SerializedName("place_alert_id") val placeAlertId: String,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID - only circle members can decrypt */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** E2EE: Hash of event type for server-side routing (cannot reverse to "arrival"/"departure") */
    @SerializedName("event_type_hash") val eventTypeHash: String,
    /** E2EE: Encrypted event type ("arrival" or "departure") - only circle members can decrypt */
    @SerializedName("encrypted_event_type") val encryptedEventType: EncryptedPayload,
    /** E2EE: Day bucket when event occurred (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp - only circle members can decrypt */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload
)

// ===== Check-in DTOs =====

/**
 * E2EE compliant check-in request.
 * All identifying information is encrypted with the circle key.
 */
data class CreateCheckInRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("check_in_type") val type: String,
    /** Encrypted message payload (nonce + ciphertext) - encrypted with circle key */
    @SerializedName("encrypted_message") val encryptedMessage: EncryptedPayload?,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    @SerializedName("place_id") val placeId: String?,
    /** E2EE: Encrypted user ID (encrypted with circle key) - circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID (encrypted with circle key) */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp (encrypted with circle key) */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload,
    /** E2EE: User pseudonym (keyed hash) for server-side filtering */
    @SerializedName("user_pseudonym") val userPseudonym: String,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

/**
 * E2EE compliant check-in response.
 * User/device IDs and timestamps are encrypted - only circle members can decrypt.
 */
data class CheckInResponse(
    val id: String,
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("place_id") val placeId: String?,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    /** Encrypted message - must be decrypted with circle key */
    @SerializedName("encrypted_message") val encryptedMessage: EncryptedPayload?,
    @SerializedName("check_in_type") val checkInType: String,
    /** E2EE: Encrypted user ID - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID - only circle members can decrypt */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp - only circle members can decrypt */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload
)

// ===== SOS DTOs =====

/**
 * E2EE compliant SOS trigger request.
 * All identifying information is encrypted with the circle key.
 */
data class TriggerSosRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("encrypted_device_state") val encryptedDeviceState: EncryptedPayload?,
    /** E2EE: Encrypted user ID (encrypted with circle key) - circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID (encrypted with circle key) */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp (encrypted with circle key) */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload
)

/**
 * E2EE compliant SOS alert response.
 * User/device IDs and timestamps are encrypted - only circle members can decrypt.
 */
data class SosAlertResponse(
    val id: String,
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("encrypted_device_state") val encryptedDeviceState: EncryptedPayload?,
    val status: String,
    /** E2EE: Encrypted user ID - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID - only circle members can decrypt */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp - only circle members can decrypt */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload,
    /** E2EE: Day bucket when resolved (null if not resolved) */
    @SerializedName("resolved_day_bucket") val resolvedDayBucket: Int?,
    /** Per-CALLER: did this user acknowledge? Server-side this is a per-member
     * acknowledgment — acknowledging does not change the shared status. */
    @SerializedName("acknowledged_by_me") val acknowledgedByMe: Boolean = false,
    /** Per-CALLER: did this user dismiss? Private to them; other members are unaffected. */
    @SerializedName("dismissed_by_me") val dismissedByMe: Boolean = false,
    /** RFC 3339 instant this caller dismissed it, or null. Drives the undo window. */
    @SerializedName("dismissed_at") val dismissedAt: String? = null,
    /** How many members acknowledged (count only — no identities). */
    @SerializedName("acknowledgment_count") val acknowledgmentCount: Int = 0,
    /**
     * Who has acknowledged, oldest first (user ids, never names): GET /sos/{id} serves it to the
     * alert's raiser alone. Absent for every other member and on every other route, which keep
     * the count only: null is "not said".
     */
    @SerializedName("acknowledged_by") val acknowledgedBy: List<String>? = null,
)

/**
 * Body of PUT /api/v1/sos/{id}/reseal: the create's sealed fields exactly, under the circle's
 * current key. The time is the ORIGINAL raise time, never "now"; no device state stores none.
 */
data class ResealSosRequest(
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    @SerializedName("encrypted_device_state") val encryptedDeviceState: EncryptedPayload?,
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload,
)

/** Body of POST /api/v1/sos/{id}/receipt. */
data class SosReceiptRequest(
    /** Always "android" from this app; the server also accepts "ios" and "web". */
    val platform: String = "android"
)

/**
 * How far the caller's OWN alert got. Counts only — the server never says which
 * members were reached, the same posture `acknowledgmentCount` takes.
 */
data class SosDeliveryResponse(
    /** Active circle members other than the raiser. */
    val recipients: Int = 0,
    /** Recipients reachable on at least one push surface. */
    val addressable: Int = 0,
    /** Recipients a push was handed to a provider for. Not receipt evidence. */
    val dispatched: Int = 0,
    /** Recipients the email fallback reached. */
    val emailed: Int = 0,
    /** Recipients whose DEVICE confirmed arrival. The only receipt evidence. */
    val confirmed: Int = 0,
    /**
     * One of: alone, pending, confirmed, partial, no_confirmation, undeliverable.
     * Derived server-side so Android, iOS and web cannot each invent a different
     * threshold for "this failed".
     */
    val state: String = "pending"
)

// ===== Privacy DTOs =====

data class PrivacySettingsResponse(
    val id: String? = null,
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("sharing_mode") val sharingMode: String = "exact",
    @SerializedName("approximate_radius_miles") val approximateRadiusMiles: Int? = null,
    @SerializedName("approximate_until") val approximateUntil: String? = null,
    @SerializedName("share_realtime") val shareRealtime: Boolean,
    @SerializedName("share_history") val shareHistory: Boolean,
    @SerializedName("share_battery") val shareBattery: Boolean,
    @SerializedName("paused_until") val pausedUntil: String?,
    /**
     * While paused: the mode a resume goes back to, "exact" or "approximate" (the mode held
     * before the pause). Null when not paused, unknown, or from a server that predates it;
     * read as "exact". Nullable: Gson skips Kotlin defaults.
     */
    @SerializedName("mode_before_pause") val modeBeforePause: String? = null,
    @SerializedName("primary_device_id") val primaryDeviceId: String?,
    @SerializedName("primary_device_pseudonym") val primaryDevicePseudonym: String? = null,
    @SerializedName("show_all_devices") val showAllDevices: Boolean = false,
    @SerializedName("allow_alerts_on_me") val allowAlertsOnMe: Boolean = true,
    @SerializedName("created_day_bucket") val createdDayBucket: Int = 0,
    @SerializedName("updated_day_bucket") val updatedDayBucket: Int = 0,
    /**
     * The spans in which this user didn't share with the circle (paused, or real-time sharing off)
     * that ended in the last 72 hours or still run, oldest first: the spans in which ingest drops
     * this user's points to the circle. How this phone learns a pause set and ended on another one
     * while it was offline. Server clock. Null from a server that predates it.
     */
    @SerializedName("not_sharing_intervals") val notSharingIntervals: List<NotSharingInterval?>? = null,
    /**
     * The spans in which this user shared with the circle approximately, and in how wide a bubble
     * (the STORED radius), that ended in the last 72 hours or still run, oldest first: a point
     * goes no finer than the bubble in force when it was taken. Server clock. Null from a server
     * that predates it.
     */
    @SerializedName("approximate_intervals") val approximateIntervals: List<ApproximateInterval?>? = null,
)

/** One of [PrivacySettingsResponse.approximateIntervals]: RFC 3339 times, [end] null while it runs with no end known. */
data class ApproximateInterval(
    val start: String?,
    val end: String? = null,
    @SerializedName("radius_miles") val radiusMiles: Int? = null,
)

/** One of [PrivacySettingsResponse.notSharingIntervals]: RFC 3339 times; [end] null while it runs with no end known. */
data class NotSharingInterval(
    val start: String?,
    val end: String? = null,
)

/**
 * PUT /circles/{id}/privacy — a COMPLETE replacement of the privacy row, not a patch.
 *
 * The server writes every column unconditionally, and an absent key is stored as NULL.
 * So any field this DTO does not send is CLEARED by every call.
 *
 * That is why [pausedUntil] and [approximateUntil] exist here even though nothing but
 * the pause/resume flow chooses them: without them, pausing for four hours and then
 * flipping any unrelated toggle would rewrite `paused_until` to NULL — which the server
 * reads as "paused until the user resumes", silently making a timed pause permanent.
 * Callers that are not changing that dimension must round-trip the value they last
 * read; callers that ARE (resume) pass null deliberately.
 */
data class UpdatePrivacyRequest(
    /** Sharing mode: 'exact', 'approximate', or 'paused' */
    @SerializedName("sharing_mode") val sharingMode: String,
    @SerializedName("share_realtime") val shareRealtime: Boolean,
    @SerializedName("share_history") val shareHistory: Boolean,
    @SerializedName("share_battery") val shareBattery: Boolean,
    /** Approximate radius in miles when sharingMode is 'approximate' (1-25). */
    @SerializedName("approximate_radius_miles") val approximateRadiusMiles: Int? = null,
    /**
     * RFC3339 instant the pause lapses, or null for "paused until I resume".
     * Round-trip [PrivacySettingsResponse.pausedUntil]; null only to resume.
     */
    @SerializedName("paused_until") val pausedUntil: String? = null,
    /**
     * RFC3339 instant bubble mode lapses, or null for "approximate until I turn it off".
     * Round-trip [PrivacySettingsResponse.approximateUntil] when not changing the mode.
     */
    @SerializedName("approximate_until") val approximateUntil: String? = null,
    /** Primary device to show on map. If null, shows most recent device. */
    @SerializedName("primary_device_id") val primaryDeviceId: String? = null,
    /** Primary device pseudonym (required when primary_device_id is set). */
    @SerializedName("primary_device_pseudonym") val primaryDevicePseudonym: String? = null,
    /** If true, show all devices on map. If false, show only primary device. */
    @SerializedName("show_all_devices") val showAllDevices: Boolean = false,
    /** If true (default), members may set arrival/departure alerts targeting me here. */
    @SerializedName("allow_alerts_on_me") val allowAlertsOnMe: Boolean = true
)

/** The create-only privacy PUT's answer: whether the row was written (false: one was there already). */
data class PrivacyWritten(
    @SerializedName("written") val written: Boolean? = null,
)

data class EnableBubbleRequest(
    /** Bubble radius in miles (1-25). */
    @SerializedName("radius_miles") val radiusMiles: Int,
    /** Duration in hours before bubble mode expires (1-720). */
    @SerializedName("duration_hours") val durationHours: Int
)

data class PauseSharingRequest(
    /** Auto-resume timestamp in RFC3339 format, or null for indefinite pause. */
    @SerializedName("resume_at") val resumeAt: String? = null
)

// ===== Boundary DTOs (E2EE Boundary Monitoring) =====

/**
 * E2EE compliant boundary crossing report request.
 * All boundary checking is done CLIENT-SIDE to maintain end-to-end encryption.
 * The server never sees plaintext coordinates or event types.
 */
data class ReportBoundaryCrossingRequest(
    /** The place/boundary that was crossed */
    @SerializedName("place_id") val placeId: String,
    /** The user who crossed the boundary */
    @SerializedName("crossing_user_id") val crossingUserId: String,
    /** REQUIRED: Encrypted event_type ("enter" or "exit") with circle key */
    @SerializedName("encrypted_event_type") val encryptedEventType: EncryptedPayload,
    /** REQUIRED: Encrypted device_id with circle key */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** REQUIRED: BLAKE3 hash for server-side routing: hash(circle_key || event_type) */
    @SerializedName("event_type_hash") val eventTypeHash: String,
    /** Optional encrypted location at time of crossing */
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    /**
     * E2EE: when the crossing ACTUALLY happened (epoch ms as a string), sealed
     * with the circle key.
     *
     * The server stores and echoes this without reading it, and the RECIPIENT
     * decrypts it to stamp the notification. Without it the only time reaching a
     * subscriber is the server's processing time, so a crossing reported after
     * an offline stretch would announce itself as if it had just happened.
     *
     * Encrypted rather than a plain number because the protocol only grants the
     * server day-granularity timing (the plaintext timestamp is deprecated, the
     * encrypted timestamp is required, and a day bucket serves ranges).
     */
    @SerializedName("encrypted_occurred_at") val encryptedOccurredAt: EncryptedPayload? = null
)

/**
 * Response after reporting a boundary crossing.
 */
data class ReportBoundaryCrossingResponse(
    @SerializedName("event_id") val eventId: String,
    @SerializedName("notifications_sent") val notificationsSent: Int
)

/**
 * E2EE compliant boundary event response.
 */
data class BoundaryEventResponse(
    val id: String,
    @SerializedName("place_id") val placeId: String,
    @SerializedName("circle_id") val circleId: String,
    /** E2EE: Encrypted user ID - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted user ID of who reported this crossing */
    @SerializedName("encrypted_reported_by_user_id") val encryptedReportedByUserId: EncryptedPayload,
    /** E2EE: Encrypted device ID - only circle members can decrypt */
    @SerializedName("encrypted_device_id") val encryptedDeviceId: EncryptedPayload,
    /** Hash of event type for server-side filtering */
    @SerializedName("event_type_hash") val eventTypeHash: String,
    /** E2EE: Encrypted event type - only circle members can decrypt */
    @SerializedName("encrypted_event_type") val encryptedEventType: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp */
    @SerializedName("encrypted_occurred_at") val encryptedOccurredAt: EncryptedPayload
)

/**
 * Current boundary state for a user (inside/outside each place).
 */
data class BoundaryStateResponse(
    val id: String,
    @SerializedName("place_id") val placeId: String,
    @SerializedName("circle_id") val circleId: String,
    /** E2EE: Encrypted user ID - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** Whether user is currently inside the boundary */
    @SerializedName("is_inside") val isInside: Boolean,
    /** E2EE: Day bucket when state was last updated */
    @SerializedName("last_seen_day_bucket") val lastSeenDayBucket: Int
)

// ===== Two-Factor Authentication DTOs =====

/**
 * Request to verify 2FA code after SRP login
 */
data class TwoFactorVerifyRequest(
    @SerializedName("two_factor_token") val twoFactorToken: String,
    val code: String,
    val method: String  // "totp", "sms", "email"
)

/**
 * Response after successful 2FA verification
 */
data class TwoFactorVerifyResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_in") val expiresIn: Long?,
    val user: SrpUserResponse,
    @SerializedName("sharing_public_key") val sharingPublicKey: String?,
    @SerializedName("signing_public_key") val signingPublicKey: String?,
    @SerializedName("kdf_params") val kdfParams: KdfParamsResponse?,
    @SerializedName("encrypted_keys") val encryptedKeys: EncryptedKeysResponse?
)

// ===== User Preferences DTOs =====

/**
 * User preferences for language, time format, and date format.
 * Synced from auth service.
 */
data class UserPreferencesResponse(
    val language: String,           // 'en', 'es', 'fr', 'de', 'pt', 'ja', 'zh', 'ko'
    @SerializedName("time_format") val timeFormat: String,  // '12h' or '24h'
    @SerializedName("date_format") val dateFormat: String,  // 'mdy', 'dmy', or 'ymd'
    @SerializedName("session_timeout") val sessionTimeout: Int?,  // seconds, null = never
    val theme: String?              // 'light', 'dark', or 'system'
)

/**
 * Request to update user preferences.
 */
data class UpdatePreferencesRequest(
    val language: String? = null,
    @SerializedName("time_format") val timeFormat: String? = null,
    @SerializedName("date_format") val dateFormat: String? = null,
    @SerializedName("session_timeout") val sessionTimeout: Int? = null,
    val theme: String? = null
)

// ===== Geocoding DTOs =====

/**
 * Encrypted geocoding preferences.
 * The server stores this as an opaque blob - only the client can decrypt.
 */
data class GeocodingPrefsResponse(
    /** Base64-encoded XChaCha20-Poly1305 ciphertext */
    val encrypted: String?,
    /** Base64-encoded 24-byte nonce */
    val nonce: String?
)

/**
 * Request to update encrypted geocoding preferences.
 */
data class UpdateGeocodingPrefsRequest(
    /** Base64-encoded XChaCha20-Poly1305 ciphertext */
    val encrypted: String,
    /** Base64-encoded 24-byte nonce */
    val nonce: String
)

/**
 * Decrypted geocoding preferences (client-side only).
 * This is never sent to the server in plaintext.
 */
data class GeocodingPreferences(
    /** List of downloaded region IDs (e.g., ["us-ca", "us-ny", "gb"]) */
    val regions: List<String>,
    /** Whether the user has been prompted to select regions */
    val prompted: Boolean,
    /** Whether to download entire regions instead of using tiling */
    val downloadEntireRegions: Boolean = false
)

// ===== Notification DTOs =====

// ===== GDPR Data Export DTOs (Article 20 - Right to Data Portability) =====

/**
 * Complete data export response.
 * All sensitive data remains encrypted - decryption must happen client-side.
 */
data class DataExportResponse(
    /** Export metadata */
    val metadata: ExportMetadata,
    /** User's registered devices */
    val devices: List<DeviceExport>?,
    /** User's circle memberships */
    val circles: List<CircleMembershipExport>?,
    /** User's places */
    val places: List<PlaceExport>?,
    /** User's check-ins */
    @SerializedName("check_ins") val checkIns: List<CheckInExport>?,
    /** User's location history (encrypted) */
    val locations: List<LocationExport>?,
    // Configuration sections (format 1.2+). Parsed as raw trees rather than
    // typed models: this client only needs to carry them into the written file,
    // and a typed model here would silently drop any field it predates.
    val alerts: com.google.gson.JsonArray? = null,
    @SerializedName("alert_rules") val alertRules: com.google.gson.JsonArray? = null,
    @SerializedName("inactivity_alerts") val inactivityAlerts: com.google.gson.JsonArray? = null,
    val privacy: com.google.gson.JsonArray? = null,
    /** Singleton — both underlying tables are keyed by user_id alone. */
    val preferences: com.google.gson.JsonObject? = null,
    // Format 1.3. Passed through as raw JSON like the config sections above:
    // this client writes the export file, it does not interpret these.
    val profile: com.google.gson.JsonObject? = null,
    @SerializedName("circle_invites") val circleInvites: com.google.gson.JsonArray? = null,
    @SerializedName("alert_events") val alertEvents: com.google.gson.JsonArray? = null,
    @SerializedName("place_shares") val placeShares: com.google.gson.JsonArray? = null,
    @SerializedName("web_push_subscriptions") val webPushSubscriptions: com.google.gson.JsonArray? = null,
    @SerializedName("boundary_events") val boundaryEvents: com.google.gson.JsonArray? = null,
    @SerializedName("destinations") val destinations: com.google.gson.JsonArray? = null,
    @SerializedName("circle_notifications") val circleNotifications: com.google.gson.JsonArray? = null,
    @SerializedName("notification_history") val notificationHistory: com.google.gson.JsonArray? = null,
    @SerializedName("circle_activity") val circleActivity: com.google.gson.JsonArray? = null,
    /** Format 1.9, "Blocked people": the addresses whose owners may not invite this account to a
     *  circle, as its own list shows them. Served only when asked for. */
    @SerializedName("blocked_people") val blockedPeople: com.google.gson.JsonArray? = null
)

/**
 * Export metadata.
 */
data class ExportMetadata(
    /** User ID this export belongs to */
    @SerializedName("user_id") val userId: String,
    /** Day bucket when export was generated */
    @SerializedName("exported_day_bucket") val exportedDayBucket: Int,
    /** Start of date range (day bucket) */
    @SerializedName("start_day_bucket") val startDayBucket: Int,
    /** End of date range (day bucket) */
    @SerializedName("end_day_bucket") val endDayBucket: Int,
    /** Export format version for future compatibility */
    @SerializedName("format_version") val formatVersion: String,
    /** Total number of location records */
    @SerializedName("location_count") val locationCount: Int,
    // Per-section truncation flags (format 1.1+). A section that merely LIMITs
    // cannot tell a full page from a truncated one, and an export that looks
    // whole when it is not is only discovered at restore time. Defaulted to
    // false so a response from an older server still parses.
    @SerializedName("devices_has_more") val devicesHasMore: Boolean = false,
    @SerializedName("circles_has_more") val circlesHasMore: Boolean = false,
    @SerializedName("places_has_more") val placesHasMore: Boolean = false,
    @SerializedName("check_ins_has_more") val checkInsHasMore: Boolean = false,
    @SerializedName("locations_has_more") val locationsHasMore: Boolean = false,
    @SerializedName("alerts_has_more") val alertsHasMore: Boolean = false,
    @SerializedName("alert_rules_has_more") val alertRulesHasMore: Boolean = false,
    @SerializedName("inactivity_alerts_has_more") val inactivityAlertsHasMore: Boolean = false,
    @SerializedName("privacy_has_more") val privacyHasMore: Boolean = false,
    @SerializedName("circle_invites_has_more") val circleInvitesHasMore: Boolean = false,
    @SerializedName("alert_events_has_more") val alertEventsHasMore: Boolean = false,
    @SerializedName("place_shares_has_more") val placeSharesHasMore: Boolean = false,
    @SerializedName("web_push_subscriptions_has_more") val webPushSubscriptionsHasMore: Boolean = false,
    @SerializedName("boundary_events_has_more") val boundaryEventsHasMore: Boolean = false,
    @SerializedName("destinations_has_more") val destinationsHasMore: Boolean = false,
    @SerializedName("circle_notifications_has_more") val circleNotificationsHasMore: Boolean = false,
    @SerializedName("notification_history_has_more") val notificationHistoryHasMore: Boolean = false,
    @SerializedName("circle_activity_has_more") val circleActivityHasMore: Boolean = false,
    @SerializedName("blocked_people_has_more") val blockedPeopleHasMore: Boolean = false,
    /** False when ANY section still has data to fetch. */
    @SerializedName("is_complete") val isComplete: Boolean = true
)

/**
 * Exported device information.
 */
data class DeviceExport(
    val id: String,
    /** Always an empty placeholder — the server stores no device name. */
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    /** Push channel for this row (fcm, apns, webpush, direct), from the
     * server's `device_type`. */
    @SerializedName("device_type") val deviceType: String? = null,
    /** Day bucket when registered. Server sends `day_bucket`. */
    @SerializedName("day_bucket") val dayBucket: Int = 0,
    /** Registration time in ms. `day_bucket` is too coarse to page on — many
     *  rows share one — so this is the device cursor's key. */
    @SerializedName("created_at_ms") val createdAtMs: Long = 0
)

/**
 * Exported circle membership.
 */
data class CircleMembershipExport(
    @SerializedName("circle_id") val circleId: String,
    /** Encrypted circle name */
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    /** User's role in the circle */
    val role: String,
    /** Encrypted circle key for this user */
    @SerializedName("encrypted_circle_key") val encryptedCircleKey: EncryptedPayload,
    /** Ephemeral public key for key decapsulation */
    @SerializedName("ephemeral_public_key") val ephemeralPublicKey: String,
    /** Key version */
    @SerializedName("key_version") val keyVersion: Int,
    /** Day bucket when joined. Also the circles cursor key — nullable
     *  server-side, and the query orders a NULL bucket as 0, so the cursor must
     *  mirror that or rows are skipped. */
    @SerializedName("joined_day_bucket") val joinedDayBucket: Int?
)

/**
 * Exported place.
 */
data class PlaceExport(
    val id: String,
    /** Circle this place belongs to (if shared) */
    @SerializedName("circle_id") val circleId: String?,
    /** Encrypted place name */
    @SerializedName("encrypted_name") val encryptedName: EncryptedPayload,
    /** Encrypted location data (lat/lng/address) */
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload,
    /** Radius in meters */
    @SerializedName("radius_meters") val radiusMeters: Int,
    /** Day bucket when created */
    @SerializedName("created_day_bucket") val createdDayBucket: Int,
    /** Owner-side wrap of the per-place key under the self key. Present on
     * every personal place; absent on legacy circle-owned rows whose
     * fields are encrypted under the circle key directly. Without it those
     * places export as undecryptable — the key is nowhere else in the file. */
    @SerializedName("wrapped_key") val wrappedKey: String? = null,
    @SerializedName("wrapped_key_nonce") val wrappedKeyNonce: String? = null
)

/**
 * Exported check-in.
 */
data class CheckInExport(
    val id: String,
    /**
     * Null on a check-in that belongs to no circle: one made personally, or one
     * whose circle was later deleted (the server then clears the circle id).
     *
     * Declaring it non-null would NOT protect anything — Gson writes through
     * Kotlin's nullability without complaint, so the null would arrive anyway
     * and `decryptCheckIn` would throw on it; that function's
     * `catch (e: Exception) { null }` would drop the row, and the backup file
     * would simply not contain the check-in. `PlaceExport.circleId` is
     * nullable for the same reason.
     */
    @SerializedName("circle_id") val circleId: String?,
    /** Check-in type (safe_arrival, general, emergency_ok) */
    @SerializedName("check_in_type") val checkInType: String,
    /** Encrypted message */
    @SerializedName("encrypted_message") val encryptedMessage: EncryptedPayload?,
    /** Encrypted location data */
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    /** Day bucket when created */
    @SerializedName("created_day_bucket") val createdDayBucket: Int
)

/**
 * Exported location record.
 */
data class LocationExport(
    /** Device that recorded this location */
    @SerializedName("device_id") val deviceId: String,
    /** Circle this location was shared with */
    @SerializedName("circle_id") val circleId: String,
    /** Day bucket for this record */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** Encrypted timestamp for precise time */
    @SerializedName("encrypted_timestamp") val encryptedTimestamp: EncryptedPayload?,
    /** Encrypted location payload (lat, lng, accuracy, etc.) */
    @SerializedName("encrypted_payload") val encryptedPayload: EncryptedPayload,
    /** Key ID used for encryption */
    @SerializedName("key_id") val keyId: String,
    /** Server-side insertion time — the second part of the locations cursor. */
    @SerializedName("inserted_at_ms") val insertedAtMs: Long = 0,
    /** Row discriminator. `inserted_at` is assigned per insert BATCH, so many
     *  rows share one value and (day_bucket, inserted_at) is not a total order:
     *  without this every row tied with the last of a page is skipped. */
    @SerializedName("row_key") val rowKey: String? = null
)

// ===== Config DTOs =====

/**
 * Map configuration from backend.
 */
data class MapConfigResponse(
    val pmtiles: PMTilesConfigResponse?,
    val fallback: FallbackMapConfigResponse
)

data class PMTilesConfigResponse(
    @SerializedName("tilesUrl") val tilesUrl: String,
    @SerializedName("lightStyle") val lightStyle: String,
    @SerializedName("darkStyle") val darkStyle: String,
    /**
     * Server-supplied tile URL template. NULLABLE on purpose: an older server
     * does not send it, and Gson leaves an absent field null rather than
     * failing, so the app must keep working against one.
     *
     * This is the cache-bust lever — tiles are served `immutable,
     * max-age=31536000`, so with only the hard-coded literal in
     * [SessionManager.DEFAULT_PMTILES_TILE_TEMPLATE] a cached tile could
     * outlive a rebuild by a year with no purge that reaches the device.
     */
    @SerializedName("tileTemplate") val tileTemplate: String? = null
)

data class FallbackMapConfigResponse(
    @SerializedName("lightStyle") val lightStyle: String,
    @SerializedName("darkStyle") val darkStyle: String
)

/**
 * Track backend configuration response.
 */
data class TracksConfigResponse(
    @SerializedName("authUiUrl") val authUiUrl: String?,
    val map: MapConfigResponse
)

// ===== Account Deletion DTOs =====

/**
 * Pseudonym entry for GDPR-compliant location data deletion.
 * The client derives these from circle keys it holds.
 */
data class PseudonymEntry(
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("user_pseudonym") val userPseudonym: String
)

/**
 * Request to delete user account.
 * Identity is proven with a zero-knowledge SRP reauth token (see the
 * /auth/reauth/srp step1|step2 endpoints); the raw password is never
 * sent. `password` is kept nullable only as a legacy fallback and is
 * left unset by the client.
 * Pseudonyms enable GDPR-compliant deletion of the account's location data.
 */
data class DeleteAccountRequest(
    @SerializedName("reauth_token") val reauthToken: String? = null,
    val password: String? = null,
    val pseudonyms: List<PseudonymEntry>? = null,
    /**
     * The user's explicit answer to the 409 the server returns when the account
     * still holds bought credit. Deleting destroys it — the record goes with the
     * account — so the server refuses the first attempt rather than doing it
     * quietly. Never set on the first try: the whole point is that destroying
     * somebody's money takes a deliberate second act.
     */
    @SerializedName("forfeit_credit_balance") val forfeitCreditBalance: Boolean? = null
)

/**
 * Response after successful account deletion.
 */
data class DeleteAccountResponse(
    val deleted: Boolean,
    /** Subscriptions the server stopped as part of the deletion. */
    @SerializedName("subscriptions_canceled") val subscriptionsCanceled: Int = 0,
    /**
     * App Store subscriptions still running. Only the customer can cancel these,
     * in their Apple account settings — Apple offers no server-side call.
     */
    @SerializedName("app_store_subscriptions_remaining") val appStoreSubscriptionsRemaining: Int = 0,
    /** Cancellations that failed. Non-zero means they are still being billed. */
    @SerializedName("subscription_cancellations_failed") val subscriptionCancellationsFailed: Int = 0,
    /**
     * What happened to the location and note data: "completed",
     * "not_applicable", "queued" or "failed". Defaulted so an older server
     * still decodes.
     */
    @SerializedName("data_erasure") val dataErasure: String = "completed",
    /**
     * One flag to key off, so each client does not re-derive it from the counts
     * and one of them get it wrong. True when the user has to act themselves.
     */
    @SerializedName("billing_needs_attention") val billingNeedsAttention: Boolean = false
)

// ===== Platform Attestation DTOs =====

/**
 * Response from attestation status endpoint.
 * Indicates which attestation methods are available on the server.
 */
data class AttestationStatusResponse(
    @SerializedName("apple_attest_enabled") val appleAttestEnabled: Boolean,
    @SerializedName("google_integrity_enabled") val googleIntegrityEnabled: Boolean,
    @SerializedName("android_keystore_enabled") val androidKeystoreEnabled: Boolean,
    @SerializedName("turnstile_enabled") val turnstileEnabled: Boolean
)

/**
 * Response from attestation challenge endpoint.
 */
data class AttestationChallengeResponse(
    val challenge: String,
    val method: String,
    @SerializedName("expires_at") val expiresAt: String
)

/**
 * Request to verify Google Play Integrity token.
 */
data class GoogleIntegrityVerifyRequest(
    @SerializedName("integrity_token") val integrityToken: String,
    val nonce: String
)

/**
 * Request to verify Android hardware keystore attestation.
 */
data class AndroidKeystoreVerifyRequest(
    @SerializedName("certificate_chain") val certificateChain: List<String>,
    val challenge: String
)

/**
 * Request to verify Turnstile + optional hardware attestation combo (for degoogled devices).
 */
data class TurnstileComboVerifyRequest(
    @SerializedName("turnstile_token") val turnstileToken: String,
    @SerializedName("certificate_chain") val certificateChain: List<String>?,
    val challenge: String?,
    @SerializedName("device_fingerprint") val deviceFingerprint: String?
)

/**
 * Response from attestation verification endpoints.
 */
data class AttestationVerifyResponse(
    @SerializedName("attestation_token") val attestationToken: String,
    val platform: String,
    @SerializedName("expires_at") val expiresAt: String
)

/**
 * Extended response from Android keystore attestation (includes security info).
 */
data class AndroidKeystoreVerifyResponse(
    @SerializedName("attestation_token") val attestationToken: String,
    val platform: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("security_level") val securityLevel: String,
    @SerializedName("verified_boot_state") val verifiedBootState: String
)

// ===== Destination DTOs (Travel Intentions) =====

/**
 * E2EE compliant destination creation request.
 * All location data is encrypted with circle key - server never sees coordinates.
 */
data class CreateDestinationRequest(
    @SerializedName("circle_id") val circleId: String,
    /** Encrypted destination coordinates (lat/lng encrypted with circle key) */
    @SerializedName("encrypted_destination") val encryptedDestination: EncryptedPayload,
    /** Optional encrypted destination name */
    @SerializedName("encrypted_dest_name") val encryptedDestName: EncryptedPayload?,
    /** E2EE: Encrypted ETA (precise timestamp encrypted with circle key) */
    @SerializedName("encrypted_eta") val encryptedEta: EncryptedPayload?,
    /** E2EE: Day bucket for estimated arrival (days since Unix epoch) */
    @SerializedName("eta_day_bucket") val etaDayBucket: Int?,
    /** Notify circle when arrived */
    @SerializedName("notify_arrival") val notifyArrival: Boolean?,
    /** E2EE: Encrypted user ID (destination creator) - encrypted with circle key */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

/**
 * E2EE compliant destination response.
 * Location and user identity are encrypted - only circle members can decrypt.
 */
data class DestinationResponse(
    val id: String,
    /** E2EE: Encrypted user ID (destination creator) - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    @SerializedName("circle_id") val circleId: String,
    /** Encrypted destination coordinates */
    @SerializedName("encrypted_destination") val encryptedDestination: EncryptedPayload,
    /** Optional encrypted destination name */
    @SerializedName("encrypted_dest_name") val encryptedDestName: EncryptedPayload?,
    /** E2EE: Encrypted ETA (precise timestamp encrypted with circle key) */
    @SerializedName("encrypted_eta") val encryptedEta: EncryptedPayload?,
    /** E2EE: Day bucket for estimated arrival (days since Unix epoch) */
    @SerializedName("eta_day_bucket") val etaDayBucket: Int?,
    @SerializedName("notify_arrival") val notifyArrival: Boolean,
    /** Status: active, arrived, cancelled, expired */
    val status: String,
    /** E2EE: Day bucket when destination sharing was started (days since Unix epoch) */
    @SerializedName("started_day_bucket") val startedDayBucket: Int,
    /** E2EE: Day bucket when user arrived (days since Unix epoch) */
    @SerializedName("arrived_day_bucket") val arrivedDayBucket: Int?,
    /** E2EE: Day bucket when destination was created (days since Unix epoch) */
    @SerializedName("created_day_bucket") val createdDayBucket: Int,
    /** E2EE: Day bucket when destination was last updated (days since Unix epoch) */
    @SerializedName("updated_day_bucket") val updatedDayBucket: Int,
    /**
     * The destination's CREATOR as the server records it (the row's own `user_id`);
     * [encryptedUserId] is the id the creator sealed. "Mine" needs both to name this account
     * ([com.pingolin.track.data.local.entity.DestinationEntity.isMine]). Nullable, so an older
     * server still decodes: then nothing is "mine".
     */
    @SerializedName("user_id") val userId: String? = null,
)

/**
 * Request to update a destination.
 */
data class UpdateDestinationRequest(
    /** Update status (active, arrived, cancelled) */
    val status: String?,
    /** E2EE: Encrypted ETA (precise timestamp encrypted with circle key) */
    @SerializedName("encrypted_eta") val encryptedEta: EncryptedPayload?,
    /** E2EE: Day bucket for estimated arrival (days since Unix epoch) */
    @SerializedName("eta_day_bucket") val etaDayBucket: Int?,
    /** Update arrival notification preference */
    @SerializedName("notify_arrival") val notifyArrival: Boolean?
)

/**
 * E2EE compliant arrival report request.
 * Client detects arrival locally and reports to server.
 */
data class ReportArrivalRequest(
    /** Optional encrypted location at time of arrival */
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?,
    /** E2EE: Encrypted user ID (event actor) - required */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: BLAKE3 hash of (circle_key || event_type) for routing without revealing type */
    @SerializedName("event_type_hash") val eventTypeHash: String,
    /** E2EE: Encrypted event type (e.g., "arrival") - required */
    @SerializedName("encrypted_event_type") val encryptedEventType: EncryptedPayload,
    /**
     * E2EE: when the arrival ACTUALLY happened (epoch ms as a string), sealed
     * with the circle key. Same contract as
     * [ReportBoundaryCrossingRequest.encryptedOccurredAt]: the server stores and
     * echoes it without reading it, and the RECIPIENT decrypts it to stamp the
     * notification.
     *
     * Optional on the wire (the server accepts a request without it), but every
     * producer sends it: the iOS app (DestinationsManager), Android's own
     * crossing path (BoundaryRepository.reportCrossing) and this request. The
     * consumer is EncryptedOccurredAt.
     *
     * Without it the subscriber's only clock is the notification's creation
     * time on the server — the moment the server was TOLD. That is not merely
     * a few seconds late: LocationTrackingService un-flags a failed auto-arrival
     * and retries on a later tick, so an arrival detected offline would be
     * announced with the RECONNECT time, which is precisely the case a sealed
     * occurred-at exists for.
     */
    @SerializedName("encrypted_occurred_at") val encryptedOccurredAt: EncryptedPayload? = null
)

/**
 * Response after reporting arrival at a destination.
 */
data class ReportArrivalResponse(
    @SerializedName("destination_id") val destinationId: String,
    @SerializedName("event_id") val eventId: String,
    @SerializedName("notifications_sent") val notificationsSent: Int
)

/**
 * E2EE compliant destination event response.
 * All identifying information is encrypted - only circle members can decrypt.
 */
data class DestinationEventResponse(
    val id: String,
    @SerializedName("destination_id") val destinationId: String,
    /** E2EE: Encrypted user ID (event actor) - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: BLAKE3 hash of (circle_key || event_type) for routing without revealing type */
    @SerializedName("event_type_hash") val eventTypeHash: String,
    /** E2EE: Encrypted event type - only circle members can decrypt */
    @SerializedName("encrypted_event_type") val encryptedEventType: EncryptedPayload,
    /** E2EE: Day bucket when event occurred (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** Encrypted location at time of event */
    @SerializedName("encrypted_location") val encryptedLocation: EncryptedPayload?
)

// ===== Inactivity Alert DTOs =====

/**
 * E2EE compliant inactivity alert creation request.
 * Used to monitor when a target user has been inactive for a specified time.
 */
data class CreateInactivityAlertRequest(
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("target_user_id") val targetUserId: String,
    /** Minutes of inactivity before alerting (5-1440). Converted to hours server-side. */
    @SerializedName("timeout_minutes") val timeoutMinutes: Int,
    /** If true, notify the circle when the creator is inactive */
    @SerializedName("is_self_alert") val isSelfAlert: Boolean,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean?,
    /** Opt-in: also alert when the target is seen again ("back online") */
    @SerializedName("notify_on_return") val notifyOnReturn: Boolean?,
    /** Minimum minutes between alerts (15-1440) */
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int?,
    /** E2EE: Encrypted alert conditions for client-side evaluation */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?,
    /** If true, server cannot evaluate - client must poll pending_evaluations */
    @SerializedName("client_only") val clientOnly: Boolean?,
    /** E2EE: Target user's per-circle pseudonym. Lets the server evaluate inactivity
     *  (the authoritative, consent-checked path) without learning the real user ID. */
    @SerializedName("target_user_pseudonym") val targetUserPseudonym: String? = null,
    /** E2EE: Encrypted target user ID (encrypted with circle key). REQUIRED by backend. */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch). REQUIRED by backend. */
    @SerializedName("day_bucket") val dayBucket: Int,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

/**
 * E2EE compliant inactivity alert response.
 */
data class InactivityAlertResponse(
    val id: String,
    /** Alert creator's user ID (acceptable as plaintext - viewing their own alerts) */
    @SerializedName("user_id") val userId: String,
    @SerializedName("circle_id") val circleId: String,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** Timeout in hours (hour-level granularity for privacy, 1-24) */
    @SerializedName("timeout_hours") val timeoutHours: Int,
    @SerializedName("is_self_alert") val isSelfAlert: Boolean,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_on_return") val notifyOnReturn: Boolean = false,
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int,
    @SerializedName("is_active") val isActive: Boolean,
    /** E2EE: Day bucket of last trigger (days since Unix epoch) */
    @SerializedName("last_triggered_day_bucket") val lastTriggeredDayBucket: Int?,
    /** E2EE: Day bucket when created (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted alert conditions for client-side evaluation */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?,
    /** If true, this alert requires client-side evaluation */
    @SerializedName("client_only") val clientOnly: Boolean
)

/**
 * Request to update an inactivity alert.
 */
data class UpdateInactivityAlertRequest(
    @SerializedName("timeout_minutes") val timeoutMinutes: Int?,
    @SerializedName("is_active") val isActive: Boolean?,
    @SerializedName("notify_push") val notifyPush: Boolean?,
    @SerializedName("notify_email") val notifyEmail: Boolean?,
    @SerializedName("notify_on_return") val notifyOnReturn: Boolean?,
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int?,
    /** E2EE: Encrypted alert conditions for client-side evaluation */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?
)

/**
 * E2EE compliant inactivity event response.
 * Represents when an inactivity alert was triggered.
 */
data class InactivityEventResponse(
    val id: String,
    @SerializedName("inactivity_alert_id") val inactivityAlertId: String,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Day bucket of last seen activity (days since Unix epoch) */
    @SerializedName("last_seen_day_bucket") val lastSeenDayBucket: Int,
    /** E2EE: Timeout in hours (hour-level granularity for privacy, 1-24) */
    @SerializedName("timeout_hours") val timeoutHours: Int,
    /** E2EE: Day bucket when triggered (days since Unix epoch) */
    @SerializedName("triggered_day_bucket") val triggeredDayBucket: Int,
    /** E2EE: Encrypted precise triggered timestamp - only circle members can decrypt */
    @SerializedName("encrypted_triggered_at") val encryptedTriggeredAt: EncryptedPayload,
    /** E2EE: Day bucket when resolved (days since Unix epoch), if resolved */
    @SerializedName("resolved_day_bucket") val resolvedDayBucket: Int?,
    @SerializedName("resolved_reason") val resolvedReason: String?
)

// ===== Smart Alert Rule DTOs =====

/**
 * E2EE compliant alert rule creation request.
 * Supported rule types: 'low_battery', 'offline'
 */
data class CreateAlertRuleRequest(
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("target_user_id") val targetUserId: String,
    /** Rule type: 'low_battery' or 'offline' */
    @SerializedName("rule_type") val ruleType: String,
    /** Flexible JSON conditions (for backward compatibility) */
    val conditions: Map<String, Any>,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_sms") val notifySms: Boolean,
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int?,
    /** E2EE: Encrypted conditions for full privacy */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?,
    /** E2EE: Encrypted target user ID (encrypted with circle key) */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /**
     * A change waiting to sync sends its own id: the server answers a resend with the first
     * row, or 409 `request_already_done`, and creates nothing. A tap sends none.
     */
    @SerializedName("client_request_id") val clientRequestId: String? = null
)

/**
 * Paginated alert rules list response.
 */
data class AlertRulesListResponse(
    val items: List<AlertRuleResponse>,
    @SerializedName("next_cursor") val nextCursor: String? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)

/**
 * E2EE compliant alert rule response.
 */
data class AlertRuleResponse(
    val id: String,
    /** Alert creator's user ID (acceptable as plaintext - viewing their own alerts) */
    @SerializedName("user_id") val userId: String,
    @SerializedName("circle_id") val circleId: String?,
    /** Rule type identifier (low_battery, offline). Not encrypted as server needs this for evaluation. */
    @SerializedName("rule_type") val ruleType: String,
    /** Server-evaluated conditions (e.g. {"threshold": 20}) - returned for the rule's owner to edit. */
    @SerializedName("conditions") val conditions: Map<String, Any>? = null,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("notify_push") val notifyPush: Boolean,
    @SerializedName("notify_email") val notifyEmail: Boolean,
    @SerializedName("notify_sms") val notifySms: Boolean,
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int?,
    /** E2EE: Encrypted conditions - only circle members can decrypt */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int
)

/**
 * A firing this device decided on, reported so the account's other devices —
 * and the web, which cannot evaluate at all — share one history.
 *
 * Everything that says WHAT happened is sealed with the circle key. The server
 * ends up holding that this account recorded something on this day, which is
 * what it needs to hand the rows back.
 */
data class CreateAlertEventRequest(
    @SerializedName("alert_rule_id") val alertRuleId: String?,
    @SerializedName("day_bucket") val dayBucket: Int,
    /**
     * Left null deliberately. The column exists to filter server-side without
     * the server learning the type, and with three possible values a hash of
     * one is reversible by trying all three — so it would buy filtering at the
     * cost of the thing it claims to protect. The rule id already separates a
     * battery firing from an inactivity one.
     */
    @SerializedName("alert_type_hash") val alertTypeHash: String? = null,
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    @SerializedName("encrypted_alert_type") val encryptedAlertType: EncryptedPayload,
    @SerializedName("encrypted_triggered_at") val encryptedTriggeredAt: EncryptedPayload
)

/**
 * A circle-mate's request to be watched, as their circle sees it.
 *
 * A self-alert is the one inactivity rule its owner's device cannot evaluate —
 * the phone that has gone quiet is the phone that would have to notice — so the
 * other members' devices judge it. This is how they learn it exists. Being told
 * is the whole purpose of the rule, so the disclosure is the feature.
 */
data class CircleSelfAlertDto(
    val id: String,
    /** The member who asked to be watched; for a self-alert, the owner IS the subject. */
    @SerializedName("user_id") val userId: String,
    @SerializedName("circle_id") val circleId: String,
    @SerializedName("timeout_hours") val timeoutHours: Int,
    @SerializedName("notify_on_return") val notifyOnReturn: Boolean = false
)

/**
 * Ask the server to wake the people holding alert rules in one circle.
 *
 * Deliberately the whole request: no battery level, no threshold, no target,
 * no reason. The server holds no key for any of it and is not told. Each woken
 * device decides for itself, from data it can already decrypt, whether anything
 * should be shown.
 */
data class WakeWatchersRequest(
    @SerializedName("circle_id") val circleId: String
)

/**
 * Request to update an alert rule.
 */
data class UpdateAlertRuleRequest(
    @SerializedName("is_active") val isActive: Boolean?,
    @SerializedName("notify_push") val notifyPush: Boolean?,
    @SerializedName("notify_email") val notifyEmail: Boolean?,
    @SerializedName("notify_sms") val notifySms: Boolean?,
    @SerializedName("cooldown_minutes") val cooldownMinutes: Int?,
    /** E2EE: Encrypted conditions for full privacy */
    @SerializedName("encrypted_conditions") val encryptedConditions: EncryptedPayload?,
    /** Server-evaluated conditions (e.g. {"threshold": 20}); used to edit the battery threshold. */
    @SerializedName("conditions") val conditions: Map<String, Any>? = null
)

/**
 * Paginated alert events list response.
 */
data class AlertEventsListResponse(
    val items: List<AlertEventResponse>,
    @SerializedName("next_cursor") val nextCursor: String? = null,
    @SerializedName("has_more") val hasMore: Boolean = false
)

/**
 * E2EE compliant alert event response.
 * Represents when a smart alert rule was triggered.
 */
data class AlertEventResponse(
    val id: String,
    @SerializedName("alert_rule_id") val alertRuleId: String?,
    /** E2EE: Encrypted user ID (alert creator) - only circle members can decrypt */
    @SerializedName("encrypted_user_id") val encryptedUserId: EncryptedPayload,
    /** E2EE: Encrypted target user ID - only circle members can decrypt */
    @SerializedName("encrypted_target_user_id") val encryptedTargetUserId: EncryptedPayload,
    /** E2EE: Hash of alert type for routing (server cannot reverse) */
    @SerializedName("alert_type_hash") val alertTypeHash: String,
    /** E2EE: Encrypted alert type - only circle members can decrypt */
    @SerializedName("encrypted_alert_type") val encryptedAlertType: EncryptedPayload,
    val message: String,
    val metadata: Map<String, Any>?,
    /** E2EE: Day bucket for coarse timing (days since Unix epoch) */
    @SerializedName("day_bucket") val dayBucket: Int,
    /** E2EE: Encrypted precise timestamp - only circle members can decrypt */
    @SerializedName("encrypted_triggered_at") val encryptedTriggeredAt: EncryptedPayload,
    /** E2EE: Day bucket when acknowledged (days since Unix epoch) */
    @SerializedName("acknowledged_day_bucket") val acknowledgedDayBucket: Int?,
    @SerializedName("notified_push") val notifiedPush: Boolean,
    @SerializedName("notified_email") val notifiedEmail: Boolean,
    @SerializedName("notified_sms") val notifiedSms: Boolean
)

// ===== Notification Center DTOs =====

/**
 * In-app notification response for the notification center.
 */
data class NotificationResponse(
    val id: String,
    @SerializedName("circle_id") val circleId: String?,
    @SerializedName("notification_type") val notificationType: String,
    val title: String,
    val body: String?,
    val metadata: Map<String, Any>?,
    @SerializedName("is_read") val isRead: Boolean = false,
    @SerializedName("created_day_bucket") val createdDayBucket: Int? = null,
    /** RFC3339 server timestamp. Used to render "Today"/"Yesterday" in the
     * device's LOCAL timezone — the day bucket is UTC-based and mislabels
     * local days for users west of UTC. Nullable for resilience against
     * older servers that don't send it. */
    @SerializedName("created_at") val createdAt: String? = null
)

/**
 * Paginated notifications list response.
 */
data class NotificationsListResponse(
    val notifications: List<NotificationResponse>,
    @SerializedName("unread_count") val unreadCount: Long = 0,
    @SerializedName("has_more") val hasMore: Boolean = false,
    @SerializedName("next_cursor") val nextCursor: String? = null
)

/**
 * Response for unread notification count endpoint.
 */
data class UnreadCountResponse(
    val count: Long
)

// ===== Support Ticket DTOs =====

/**
 * Request to create a support ticket via the auth service.
 * Category is hardcoded to "track" for the Track Android app.
 */
data class SupportTicketRequest(
    val subject: String,
    val message: String,
    val category: String = "track"
)

/**
 * Response after creating a support ticket.
 */
data class SupportTicketResponse(
    @SerializedName("ticket_id") val ticketId: String?,
    val status: String?
)

/**
 * Backfill request: all pseudonyms the caller has derived for themself
 * across every circle-key version they hold. Server stores them against
 * the caller's user_id (taken from the auth token, not the body) so
 * historical location rows tagged with pre-rotation pseudonyms become
 * visible to the /locations history endpoint.
 */
data class PseudonymBackfillRequest(
    val pseudonyms: List<String>
)

/**
 * Backfill response: how many of the submitted pseudonyms were new versus
 * already on file (idempotent). Both together always equals the submitted
 * count.
 */
data class PseudonymBackfillResponse(
    val inserted: Long,
    val skipped: Long
)


// ===== Identities from before a password reset =====

/**
 * A recovered pre-reset private key, sealed for this app by the account portal.
 *
 * The portal's own copy is wrapped under the auth MASTER key, which mobile never
 * holds — so it seals a second copy under a value the SDK derives from the key
 * it already has. Absent when the recovery was made before the portal sealed
 * such a second copy.
 */
data class SealedPreviousIdentityDto(
    val nonce: String,
    val ciphertext: String
)

data class PreviousIdentityDto(
    @SerializedName("key_version") val keyVersion: Int,
    @SerializedName("app_sealed_private_key") val appSealedPrivateKey: SealedPreviousIdentityDto? = null,
    /**
     * The auth server's v2: the old sharing private key and that identity's signing PUBLIC
     * key, sealed like v1. With it, this account's own signatures from before the reset count
     * again. Absent until the portal has written it.
     */
    @SerializedName("app_sealed_private_key_v2") val appSealedPrivateKeyV2: SealedPreviousIdentityDto? = null,
) {
    /** What the SDK is given for this identity: v2 when present, else v1. */
    val sealedForThisApp: SealedPreviousIdentityDto? get() = appSealedPrivateKeyV2 ?: appSealedPrivateKey
}

data class PreviousIdentitiesResponse(
    val identities: List<PreviousIdentityDto> = emptyList()
)

/**
 * Reply to `GET /api/v1/auth/archived-recovery`, reduced to what the app reads: how
 * many email resets left data behind. The entries also carry the wrapped pre-reset
 * keys, which only the account portal uses; they are deliberately not parsed here.
 */
data class ArchivedRecoveryResponse(
    val archived: List<ArchivedRecoveryEntryDto> = emptyList()
)

data class ArchivedRecoveryEntryDto(
    @SerializedName("key_version") val keyVersion: Int = 0,
)

/**
 * Reply to `GET /api/v1/security/revocation-status`.
 *
 * Per server instance, so `enforcing = false` is authoritative and
 * `enforcing = true` is not — another instance may be failing open while
 * the one answering is healthy. That is the right direction for a warning:
 * it never invents a window that did not happen.
 */
data class RevocationStatusResponse(
    val enforcing: Boolean = true,
)
