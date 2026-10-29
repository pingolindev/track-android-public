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

/*
 * Wire DTOs for the privacy-preserving geocode-token flow (Privacy-Pass-style
 * VOPRF). See the mobile SDK's `geocode_tokens.rs` for the client crypto.
 *
 * The design splits "who you are" from "where you are": the user authenticates
 * once to mint a batch of anonymous single-use credits (issuance), then spends
 * one credit per reverse-geocode on a **session-less** endpoint that carries no
 * identity. These DTOs are the JSON shapes of those three calls.
 *
 * NOTE: [com.pingolin.track.di.AppModule] builds Gson with no field-naming
 * policy, so every snake_case JSON key needs an explicit @SerializedName.
 */

/**
 * Response of `GET /api/v1/geocode/token-key` (public, unauthenticated). Flat
 * JSON — NOT wrapped in [ApiResponse]. Clients pin (`keyId`, `publicKey`) to
 * verify issuance DLEQ proofs and detect key rotation.
 */
data class GeocodeTokenKeyResponse(
    val success: Boolean = false,
    @SerializedName("key_id") val keyId: String? = null,
    @SerializedName("public_key") val publicKey: String? = null,
    @SerializedName("ttl_seconds") val ttlSeconds: Long? = null,
    val suite: String? = null,
)

/** Body of `POST /api/v1/geocode/tokens` — base64 blinded elements, one per credit. */
data class GeocodeIssueRequest(
    val blinded: List<String>,
)

/**
 * `data` payload of the [ApiResponse] from `POST /api/v1/geocode/tokens`.
 * `evaluated` + `proofs` are index-paired with the request `blinded` batch.
 */
data class GeocodeIssueResponse(
    @SerializedName("key_id") val keyId: String,
    @SerializedName("public_key") val publicKey: String,
    val evaluated: List<String>,
    val proofs: List<String>,
    @SerializedName("ttl_seconds") val ttlSeconds: Long = 0,
    val suite: String? = null,
)

/**
 * Body of `POST /api/v1/geocode/reverse` (session-less redemption). Carries NO
 * identity — only the spent credit + coordinates. The backend aliases `lat`/
 * `lng` (also accepts `point.lat`/`point.lon`).
 */
data class GeocodeRedeemRequest(
    val token: GeocodeRedeemToken,
    @SerializedName("key_id") val keyId: String,
    val lat: Double,
    val lng: Double,
    val size: Int = 1,
    /** Optional Pelias layer restriction, allow-listed server-side. "address"
     *  makes a reverse at a venue's own coordinates return the nearest ADDRESS
     *  record instead of the venue, which carries no street. */
    val layers: String? = null,
)

/**
 * Forward/text-search redemption body. Same credit and key epoch as
 * [GeocodeRedeemRequest]; the payload is the user's query plus an optional
 * focus point to bias results toward what they're looking at.
 *
 * `text` is user content — do not log it.
 */
data class GeocodeSearchRequest(
    val token: GeocodeRedeemToken,
    @SerializedName("key_id") val keyId: String,
    val text: String,
    val size: Int = 10,
    @SerializedName("focus.point.lat") val focusLat: Double? = null,
    @SerializedName("focus.point.lon") val focusLng: Double? = null,
)

/** The single-use credit presented at redemption: `(nonce, W)`. */
data class GeocodeRedeemToken(
    @SerializedName("nonce_b64") val nonceB64: String,
    @SerializedName("evaluated_b64") val evaluatedB64: String,
)
