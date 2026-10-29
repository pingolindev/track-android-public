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

interface AuthApi {

    /** Unauthenticated by design — it is fetched before there is a session. */
    @GET("api/config")
    suspend fun getConfig(): Response<AuthConfigResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    /**
     * Refresh the access + refresh tokens.
     *
     * Preferred flow: client sends the opaque refresh token in
     * `X-Refresh-Token`. The server looks the session up by the hash,
     * atomically rotates the row, and returns the new pair. The
     * Authorization header is deliberately ignored in this flow: a refresh
     * that depended on the JWT verifying would log every user out on a
     * signing-key rotation.
     *
     * Legacy flow (older sessions that have no refresh token): if the
     * header is absent, the server falls back to the existing JWT-based
     * path and *attaches* a freshly generated refresh token to the
     * session. Subsequent refreshes must use the header form.
     *
     * Body is empty because everything sits in headers.
     */
    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(
        @retrofit2.http.Header("X-Refresh-Token") refreshToken: String? = null,
        @Body request: Map<String, String> = emptyMap(),
    ): Response<ApiResponse<RefreshAccessTokenResponse>>

    /**
     * Ends this session. The refresh token names it, so the server can end it even when the
     * access token sent with it has expired; nothing is refreshed around it.
     */
    @POST("api/v1/auth/logout")
    suspend fun logout(@Body request: com.pingolin.track.data.remote.dto.LogoutRequest): Response<Unit>

    /**
     * Rename the user-visible label for a session (= "device" in the
     * Devices UI on the account site). Name sanitization happens
     * server-side: empty / whitespace-only clears the override and
     * falls back to the auto-detected User-Agent string.
     */
    @retrofit2.http.PATCH("api/v1/auth/sessions/{id}")
    suspend fun renameSession(
        @retrofit2.http.Path("id") sessionId: String,
        @Body request: com.pingolin.track.data.remote.dto.RenameSessionRequest,
    ): Response<ApiResponse<Unit>>

    /**
     * List all active sessions (= devices) for the current user. Each
     * row carries device_name, ip_address, last_used_at, and an
     * is_current flag for the calling JWT. This is the canonical source for
     * the Devices UI — track's own device list covers only the subset that
     * pushes location.
     */
    @GET("api/v1/auth/sessions")
    suspend fun listSessions(): Response<ApiResponse<com.pingolin.track.data.remote.dto.SessionsListResponse>>

    /**
     * Revoke a session. The server marks the session revoked and
     * deactivates the device's push registration, so the revoked
     * device stops receiving notifications.
     */
    @retrofit2.http.HTTP(method = "DELETE", path = "api/v1/auth/sessions/{id}", hasBody = false)
    suspend fun revokeSession(
        @retrofit2.http.Path("id") sessionId: String,
    ): Response<ApiResponse<Unit>>

    /** Identities the user recovered after a password reset. Empty for almost
     *  every account; see PreviousIdentityLoader. */
    @GET("api/v1/auth/previous-identities")
    suspend fun getPreviousIdentities(): Response<ApiResponse<PreviousIdentitiesResponse>>

    /**
     * Whether an email password reset left data this account's old recovery key can
     * restore. Read only to explain a locked circle (see PreviousIdentityLoader
     * .preResetDataExists); rate-limited server-side to 20 an hour, so it is asked
     * at most once per session.
     */
    @GET("api/v1/auth/archived-recovery")
    suspend fun getArchivedRecovery(): Response<ApiResponse<ArchivedRecoveryResponse>>

    /**
     * The signed-in user. Wrapped in [ApiResponse] like every other auth route,
     * so the body is `{"success":true,"data":{…}}`.
     *
     * Declared as a bare `Response<UserResponse>`, the ENVELOPE would parse into
     * `UserResponse` with every field null, and a reader of a `/me` field would
     * silently read null — which for the recovery-address row would mean
     * telling every account it has none.
     */
    @GET("api/v1/auth/me")
    suspend fun getCurrentUser(): Response<ApiResponse<UserResponse>>

    @PUT("api/v1/auth/me")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<UserResponse>

    @POST("api/v1/auth/password/change")
    suspend fun changePassword(@Body request: ChangePasswordRequest): Response<Unit>

    @POST("api/v1/auth/password/reset")
    suspend fun requestPasswordReset(@Body request: PasswordResetRequest): Response<Unit>

    @POST("api/v1/auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest): Response<ApiResponse<Unit>>

    @POST("api/v1/auth/email/resend-verification")
    suspend fun resendVerification(): Response<ApiResponse<Unit>>

    @GET("api/v1/auth/email/status")
    suspend fun getVerificationStatus(): Response<ApiResponse<EmailVerificationStatusResponse>>

    // ===== SRP (Secure Remote Password) Authentication =====
    // Zero-knowledge password authentication - password never leaves device

    /**
     * SRP Step 1: Initiate authentication, get salt and server public value
     */
    @POST("api/v1/auth/srp/step1")
    suspend fun srpStep1(@Body request: SrpStep1Request): Response<ApiResponse<SrpStep1Response>>

    /**
     * SRP Step 2: Send client proof, receive server proof and JWT
     */
    @POST("api/v1/auth/srp/step2")
    suspend fun srpStep2(@Body request: SrpStep2Request): Response<ApiResponse<SrpStep2Response>>

    // ===== Re-authentication (zero-knowledge, for sensitive ops) =====
    // A fresh SRP handshake for the already-authenticated user. Identity
    // for step 1 is proven by the bearer token (auto-attached by the
    // OkHttp interceptor), so no email travels on the wire. Step 2
    // returns a short-lived reauth_token that stands in for the raw
    // password on sensitive operations (e.g. account deletion).

    /**
     * Re-auth SRP Step 1: get salt, server public value, session id, and
     * bcrypt salt. Bodyless POST — the caller is identified by the JWT.
     */
    @POST("api/v1/auth/reauth/srp/step1")
    suspend fun reauthStep1(): Response<ApiResponse<SrpStep1Response>>

    /**
     * Re-auth SRP Step 2: send client proof, receive the reauth token.
     */
    @POST("api/v1/auth/reauth/srp/step2")
    suspend fun reauthStep2(@Body request: SrpStep2Request): Response<ApiResponse<ReauthStep2Response>>



    // ===== Two-Factor Authentication =====

    /**
     * Verify 2FA code after SRP step 2 returns requires_2fa=true.
     *
     * The route is `auth/login/2fa`. Nothing under `auth/2fa/` verifies a login:
     * that prefix holds only the 2FA settings routes.
     */
    @POST("api/v1/auth/login/2fa")
    suspend fun verify2fa(@Body request: TwoFactorVerifyRequest): Response<ApiResponse<TwoFactorVerifyResponse>>

    // ===== Billing / Subscription =====

    @GET("api/v1/billing/subscription")
    suspend fun getSubscriptions(): Response<ApiResponse<List<SubscriptionInfo>>>

    // ===== User Preferences =====

    /**
     * Get user preferences (language, time/date format, theme)
     */
    @GET("api/v1/auth/preferences")
    suspend fun getPreferences(): Response<ApiResponse<UserPreferencesResponse>>

    /**
     * Update user preferences
     */
    @PUT("api/v1/auth/preferences")
    suspend fun updatePreferences(@Body request: UpdatePreferencesRequest): Response<ApiResponse<UserPreferencesResponse>>

    /**
     * Delete user account (GDPR compliant)
     * Requires password confirmation and optionally accepts pseudonyms for location data deletion
     */
    @HTTP(method = "DELETE", path = "api/v1/auth/me", hasBody = true)
    suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<ApiResponse<DeleteAccountResponse>>

    // ===== Platform Attestation (CAPTCHA bypass for mobile) =====

    /**
     * Get attestation status - which methods are available on the server
     */
    @GET("api/v1/auth/attest/status")
    suspend fun getAttestationStatus(): Response<ApiResponse<AttestationStatusResponse>>

    /**
     * Get attestation challenge for a specific platform
     * @param platform "google" for Play Integrity, "android_hardware" for keystore attestation
     */
    @GET("api/v1/auth/attest/challenge")
    suspend fun getAttestationChallenge(@Query("platform") platform: String): Response<ApiResponse<AttestationChallengeResponse>>

    /**
     * Verify Google Play Integrity token
     */
    @POST("api/v1/auth/attest/google")
    suspend fun verifyGoogleIntegrity(@Body request: GoogleIntegrityVerifyRequest): Response<ApiResponse<AttestationVerifyResponse>>

    /**
     * Verify Android hardware keystore attestation
     */
    @POST("api/v1/auth/attest/android-hardware")
    suspend fun verifyAndroidKeystore(@Body request: AndroidKeystoreVerifyRequest): Response<ApiResponse<AndroidKeystoreVerifyResponse>>

    /**
     * Verify Turnstile + optional hardware attestation combo (for degoogled devices)
     */
    @POST("api/v1/auth/attest/turnstile-combo")
    suspend fun verifyTurnstileCombo(@Body request: TurnstileComboVerifyRequest): Response<ApiResponse<AttestationVerifyResponse>>

    // ===== Support =====

    /**
     * Submit a support ticket.
     * Requires authentication (JWT Bearer token).
     */
    @POST("api/v1/support/ticket")
    suspend fun submitSupportTicket(@Body request: SupportTicketRequest): Response<ApiResponse<SupportTicketResponse>>
}
