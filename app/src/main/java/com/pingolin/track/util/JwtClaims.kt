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

package com.pingolin.track.util

import android.util.Base64
import org.json.JSONObject

/**
 * Decode the payload of a JWT *without verifying* the signature. We never
 * trust these values for security decisions — only for surfacing
 * server-issued metadata the client already has in its token (session id,
 * audience, etc.). The signature was verified by the server before this
 * token was minted; the same token round-trips on every authenticated
 * request, so trusting its payload locally for UI purposes (e.g. picking
 * a session_id to PATCH-rename) is fine.
 *
 * Returns null on any decode/JSON failure rather than throwing, because
 * callers treat "we couldn't read the claim" the same as "the claim
 * isn't there" — both fall back to disabling the dependent UI.
 */
object JwtClaims {
    /** Extract `sid` (the session's id on the server) from the access JWT. */
    fun sessionId(accessToken: String?): String? = readClaim(accessToken, "sid")

    private fun readClaim(accessToken: String?, name: String): String? {
        val token = accessToken ?: return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        return try {
            // JWT uses URL-safe base64 with no padding; Android's
            // Base64.decode tolerates missing padding when URL_SAFE is set.
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.isNull(name)) null else json.optString(name).ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
