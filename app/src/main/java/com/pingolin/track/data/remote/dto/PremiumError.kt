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
import retrofit2.Response

/**
 * Structured quota/premium detail attached to a backend 402 (Payment
 * Required). Mirrors the auth server's `QuotaError` shape:
 *
 * ```json
 * { "resource": "places", "is_count_limit": true, "current": 2, "limit": 2, "tier": "free" }
 * ```
 *
 * `is_count_limit` distinguishes a hard count cap (places/circles/members/
 * place_alerts — `current`/`limit` meaningful) from a boolean feature gate
 * (destinations/smart_alerts/web_push — `current`/`limit` both 0).
 */
data class QuotaError(
    val resource: String,
    @SerializedName("is_count_limit") val isCountLimit: Boolean,
    val current: Long,
    val limit: Long,
    val tier: String,
)

/**
 * A request the server refused, with its HTTP [status] and `error_code`, for code that has to
 * tell refusals apart: a change waiting to sync is dropped, retried or done by them. Its
 * message is this app's line ([ApiErrors.plainLine]), never the server's.
 */
open class RefusedException(
    message: String,
    val status: Int,
    val errorCode: String?,
) : Exception(message)

/**
 * Carries a backend quota/premium 402 up the `Result` chain so the UI can
 * show the premium prompt (see [com.pingolin.track.ui.dialogs.TrackPremiumBottomSheet])
 * instead of a bland Toast. [errorCode] is the canonical code
 * (`quota_places_exceeded`, `premium_required_destinations`, …); [quota]
 * is the structured detail when present.
 */
class QuotaException(
    message: String,
    errorCode: String?,
    val quota: QuotaError?,
    status: Int = 402,
) : RefusedException(message, status, errorCode)

/**
 * The id a create supplied is already taken (HTTP 409).
 *
 * Only a RESTORE supplies an id, so this only arises there, and it means
 * "already restored" rather than a failure — a clean second run of the same
 * backup would otherwise report as a total failure. It is a distinct type
 * because the server answers a plain 409 deliberately: a 500 or a 200 would
 * tell the caller whether an id exists under ANOTHER account.
 */
class AlreadyExistsException(message: String, errorCode: String? = null) : RefusedException(message, 409, errorCode)

/** The [QuotaError] if this throwable is a [QuotaException], else null. */
val Throwable.quotaError: QuotaError?
    get() = (this as? QuotaException)?.quota

/** The canonical error code if this throwable is a [QuotaException], else null. */
val Throwable.premiumErrorCode: String?
    get() = (this as? QuotaException)?.errorCode

/** True when this throwable represents a premium/quota gate (a 402). */
val Throwable.isPremiumGate: Boolean
    get() = this is QuotaException

/**
 * Parses the structured error body backends send — its `error_code` and `quota` drive
 * the premium prompt — and says what a person is told about a refusal ([plainLine]).
 */
object ApiErrors {

    // What a person is told about a refused request, by kind. The same words as on iPhone.
    const val PLAN_LIMIT = "That's beyond what your plan includes."
    const val TOO_MANY = "Too many tries just now. Please wait a moment and try again."
    const val TROUBLE = "Pingolin is having trouble just now. Please try again in a moment."
    const val NOT_ALLOWED = "You don't have permission to do that."
    const val GONE = "That isn't there any more."

    /**
     * What a person is told about a refused request: by the refusal's `error_code`, else its
     * HTTP [status], in this app's words — else [fallback], which says what didn't happen
     * ("Couldn't … just now."). Never the server's own `error` or `message` text, which can
     * mention keys, or carry whatever a compromised server wants a person to read: that is
     * for logs.
     */
    fun plainLine(status: Int, errorCode: String?, fallback: String): String {
        val code = errorCode.orEmpty()
        return when {
            status == 402 || code.startsWith("quota_") || code.startsWith("premium_required") -> PLAN_LIMIT
            // The status first: auth's 503 `rate_limiter_unavailable` is trouble, not too many tries.
            status == 429 -> TOO_MANY
            status >= 500 -> TROUBLE
            code.startsWith("rate_") -> TOO_MANY
            code.startsWith("server_") -> TROUBLE
            status == 403 || code.startsWith("auth_not_") || code == "auth_insufficient_permissions" -> NOT_ALLOWED
            status == 404 || code.endsWith("_not_found") -> GONE
            else -> fallback
        }
    }

    /** [plainLine] for [response]: its status and its error body's `error_code`. Consumes the error body. */
    fun plainLine(response: Response<*>, fallback: String): String =
        plainLine(response.code(), runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull(), fallback)

    data class Parsed(
        val error: String?,
        val errorCode: String?,
        val quota: QuotaError?,
    )

    /**
     * Parse a raw error-body string, or null if it carries no useful fields. Gson, not org.json:
     * the JVM tests' org.json is a stub, so a parse through it passed nothing there.
     */
    fun parse(errorBody: String?): Parsed? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = com.google.gson.JsonParser.parseString(errorBody).asJsonObject
            fun com.google.gson.JsonObject.text(name: String): String? =
                get(name)?.takeIf { it.isJsonPrimitive }?.asString?.ifBlank { null }
            fun com.google.gson.JsonObject.long(name: String): Long =
                get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() } ?: 0
            val error = json.text("error")
            val errorCode = json.text("error_code")
            val quota = json.get("quota")?.takeIf { it.isJsonObject }?.asJsonObject?.let { q ->
                QuotaError(
                    resource = q.text("resource") ?: "",
                    isCountLimit = q.get("is_count_limit")?.takeIf { it.isJsonPrimitive }
                        ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
                    current = q.long("current"),
                    limit = q.long("limit"),
                    tier = q.text("tier") ?: "free",
                )
            }
            if (error == null && errorCode == null && quota == null) null
            else Parsed(error, errorCode, quota)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the failure exception for a non-successful response. Returns a
     * [QuotaException] (carrying error_code + quota) when the response is a
     * 402 or the body carries quota fields, otherwise a plain [Exception].
     * Its message is this app's ([plainLine], else [fallbackMessage]), never
     * the backend's.
     *
     * NOTE: consumes `errorBody()` — call once per response.
     */
    fun toException(response: Response<*>, fallbackMessage: String): Exception {
        val parsed = try {
            parse(response.errorBody()?.string())
        } catch (_: Exception) {
            null
        }
        val message = plainLine(response.code(), parsed?.errorCode, fallbackMessage)
        // Checked BEFORE the quota arm: the 409 carries an `error_code`
        // (`place_already_exists`) and would otherwise be dressed up as a
        // QuotaException, which is what the premium prompt keys off.
        if (response.code() == 409) return AlreadyExistsException(message, parsed?.errorCode)
        return if (response.code() == 402 || parsed?.errorCode != null || parsed?.quota != null) {
            QuotaException(message, parsed?.errorCode, parsed?.quota, response.code())
        } else {
            RefusedException(message, response.code(), null)
        }
    }

    /**
     * [plainLine] for [response], as a [RefusedException] carrying its status and `error_code`.
     * Consumes the error body.
     */
    fun refused(response: Response<*>, fallback: String): RefusedException {
        val code = codeOf(response)
        return RefusedException(plainLine(response.code(), code, fallback), response.code(), code)
    }

    /** The refusal's `error_code`, for a decision; never its words. Consumes the error body. */
    fun codeOf(response: Response<*>): String? = runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull()
}

/**
 * An ownership claim on this circle already exists (HTTP 409,
 * `error_code = CIRCLE_OWNERSHIP_CLAIM_EXISTS`).
 *
 * The server allows only one pending claim per circle, so of two racing
 * admins the second is refused. Distinct from a plain failure because it is
 * not one: the claim the caller wanted to open is already open, and the
 * useful response is to show them the live claim — who asked, and when it
 * settles — rather than a Toast saying the tap did not work.
 */
class OwnershipClaimExistsException(message: String) : Exception(message)
