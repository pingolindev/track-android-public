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

import com.pingolin.track.data.remote.dto.PushRegistration

/**
 * Works out what the Push Notifications section should say, by reconciling
 * this install's local push prefs against the registrations the SERVER holds
 * for this session.
 *
 * WHY THIS EXISTS
 *   Local prefs alone are not enough. They can go missing while delivery
 *   still works perfectly — the distributor keeps its subscription and the
 *   server keeps an active row, but this install forgets the endpoint it was
 *   handed. Read from prefs alone, the screen would say "registration
 *   pending", which a user reasonably reads as "I will not get
 *   notifications", about a channel that delivers fine.
 *
 *   The opposite skew matters more: local state present but NO server
 *   registration means nothing can address this device, so no push will
 *   ever be sent. That is the case worth an error colour.
 *
 * Pure by design — no Context, no I/O — so every combination below is unit
 * testable, which is the only way the local/server skew stays honest.
 */
object PushStatusResolver {

    /** Server-side spelling of a local push type, or null if unrecognised. */
    fun serverPushType(localPushType: String?): String? = when (localPushType) {
        SessionManager.PUSH_TYPE_FCM -> "fcm"
        // The server calls UnifiedPush "webpush" — it stores the endpoint in a
        // webpush-shaped row. Getting this mapping wrong makes every
        // UnifiedPush user look unregistered, so it is pinned by a test.
        SessionManager.PUSH_TYPE_UNIFIED_PUSH -> "webpush"
        else -> null
    }

    /** Local push type for a server-side spelling, or null if unrecognised. */
    fun localPushType(serverPushType: String?): String? = when (serverPushType) {
        "fcm" -> SessionManager.PUSH_TYPE_FCM
        "webpush" -> SessionManager.PUSH_TYPE_UNIFIED_PUSH
        else -> null
    }

    private fun label(localPushType: String?): String? = when (localPushType) {
        SessionManager.PUSH_TYPE_FCM -> "Google FCM"
        SessionManager.PUSH_TYPE_UNIFIED_PUSH -> "UnifiedPush"
        else -> null
    }

    /**
     * Local prefs to write back when the server knows a registration this
     * install has lost. Applied only to FILL a gap, never to overwrite a local
     * value: local state comes from the distributor and is authoritative when
     * present; the server's copy is only ever what this app last told it.
     */
    data class Restore(val pushType: String, val endpoint: String?)

    data class Status(
        val text: String,
        val isError: Boolean,
        /**
         * A push provider is in play at all. False only for "Not configured",
         * which is the one state that gets no status icon — absence of a
         * decision is not a health signal either way.
         */
        val configured: Boolean,
        val restore: Restore? = null,
        /**
         * The server holds nothing that can address this device, while this
         * install's own push state is intact. Only a fresh device-registration
         * POST can repair it — `PUT /devices/:id/push-token` UPDATES an existing
         * row and 404s when there is none — so the caller must re-register.
         *
         * This is the state a device lands in when the server deactivates its
         * token (three consecutive delivery failures, timeouts included). Local
         * state stays perfectly intact, which is exactly why the ordinary repair
         * paths bail: `reconcilePushTokenRegistration` and
         * `PushTokenSyncWorker` both return early on
         * `SessionManager.isPushTokenSynced()`, set by the registration that
         * succeeded and cleared only by logout or an FCM token rotation; and
         * `restoreLostPushRegistration` only ever FILLS a gap, so it declines
         * to act too. Without re-registering, the device would stay silent for
         * good.
         */
        val needsReregistration: Boolean = false,
    )

    /**
     * @param serverRegistrations what the server can deliver to this session,
     *   or null when that could not be determined (offline, or a backend
     *   without the endpoint). Null means "no opinion" — the status then falls
     *   back to local state and never claims more than local knows.
     */
    fun resolve(
        pushType: String?,
        pushToken: String?,
        pushEndpoint: String?,
        serverRegistrations: List<PushRegistration>?,
    ): Status {
        // A lost pref can take the TYPE with it. Fall back to whatever the
        // server says this session registered, so the screen names the real
        // channel instead of "Not configured".
        val effectiveType = pushType
            ?: serverRegistrations?.firstNotNullOfOrNull { localPushType(it.pushType) }

        val label = label(effectiveType)
            ?: return Status("Status: Not configured", isError = false, configured = false)

        val localOk = when (effectiveType) {
            SessionManager.PUSH_TYPE_FCM -> pushToken != null
            SessionManager.PUSH_TYPE_UNIFIED_PUSH -> pushEndpoint != null
            else -> false
        }

        val wanted = serverPushType(effectiveType)
        val server = serverRegistrations?.firstOrNull { it.pushType == wanted }

        // Unknown server state: local-only behaviour.
        if (serverRegistrations == null) {
            return if (localOk) Status("Status: $label", isError = false, configured = true)
            else Status("Status: $label — registration pending", isError = false, configured = true)
        }

        if (server == null) {
            // Nothing on the server can address this device. Whether or not
            // local state looks healthy, no push will arrive — a local-only
            // check would call this case fine and leave it invisible.
            return if (localOk) {
                // Repairable, and only by re-registering —
                // see [Status.needsReregistration].
                // Reporting it is not enough: the user has
                // no button that repairs this.
                Status(
                    "Status: $label — not synced to server",
                    isError = true,
                    configured = true,
                    needsReregistration = true,
                )
            } else {
                Status("Status: $label — registration pending", isError = false, configured = true)
            }
        }

        if (server.paused) {
            return Status("Status: $label — paused", isError = false, configured = true)
        }

        // Server has a live registration. If this install lost its copy, the
        // channel still works — say so, and hand back what to restore.
        val restore = if (!localOk) Restore(effectiveType!!, server.endpoint) else null
        return Status("Status: $label", isError = false, configured = true, restore = restore)
    }

    /**
     * Distributor line for UnifiedPush. [serverEndpoint] is the endpoint the
     * server holds, so an install whose own copy went missing still reads as
     * registered rather than offering to set up something already set up.
     */
    fun distributorStatus(
        distributorCount: Int,
        localEndpoint: String?,
        serverEndpoint: String?,
    ): String = when {
        distributorCount == 0 ->
            "No UnifiedPush distributor apps installed. Install ntfy or another distributor."
        localEndpoint != null || serverEndpoint != null -> "Registered (endpoint configured)"
        else -> "$distributorCount distributor(s) available"
    }
}
