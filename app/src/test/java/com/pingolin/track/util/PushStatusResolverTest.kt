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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Push Notifications status line. Local prefs alone cannot tell these two
 * apart:
 *
 *   - push was never set up (user gets nothing, and should be told so);
 *   - this install lost its local copy of a registration the server still
 *     holds and the distributor is still subscribed to (user gets everything).
 *
 * The second must not read "registration pending" while the device's ntfy
 * topic, server row and last-delivery timestamps all agree. These tests pin
 * each combination, because the failure mode is a status that is merely
 * *plausible* rather than true.
 */
class PushStatusResolverTest {

    private fun webpush(
        endpoint: String? = "https://ntfy.sh/abc123?up=1",
        paused: Boolean = false,
    ) = PushRegistration(
        pushType = "webpush",
        endpoint = endpoint,
        hasToken = false,
        paused = paused,
        lastSuccessAt = null,
    )

    private fun fcm(paused: Boolean = false) = PushRegistration(
        pushType = "fcm",
        endpoint = null,
        hasToken = true,
        paused = paused,
        lastSuccessAt = null,
    )

    // MARK: - A registration lost locally

    /**
     * The central case. Local endpoint gone, server registration live,
     * distributor still subscribed. Delivery works, so the status must say so
     * — and hand back the endpoint to restore.
     */
    @Test
    fun `lost local endpoint with a live server registration reads as registered`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = null,
            serverRegistrations = listOf(webpush()),
        )

        assertEquals("Status: UnifiedPush", status.text)
        assertFalse(status.isError)
        assertEquals(SessionManager.PUSH_TYPE_UNIFIED_PUSH, status.restore?.pushType)
        assertEquals("https://ntfy.sh/abc123?up=1", status.restore?.endpoint)
    }

    /** Even the push TYPE can go missing; the server still names the channel. */
    @Test
    fun `lost local push type is recovered from the server registration`() {
        val status = PushStatusResolver.resolve(
            pushType = null,
            pushToken = null,
            pushEndpoint = null,
            serverRegistrations = listOf(webpush()),
        )

        assertEquals("Status: UnifiedPush", status.text)
        assertEquals(SessionManager.PUSH_TYPE_UNIFIED_PUSH, status.restore?.pushType)
    }

    // MARK: - Local state with no server registration

    /**
     * Local state looks healthy but the server has nothing for this session, so
     * no push can be addressed here. A local-only check would call this "fine"
     * and it would stay invisible. It is the one case worth an error.
     */
    @Test
    fun `local state with no server registration is an error`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = "https://ntfy.sh/abc123?up=1",
            serverRegistrations = emptyList(),
        )

        assertEquals("Status: UnifiedPush — not synced to server", status.text)
        assertTrue(status.isError)
        assertNull(status.restore)
        assertTrue(
            "and it must be repairable, not merely reported — see below",
            status.needsReregistration
        )
    }

    /**
     * A DEVICE THE SERVER HAS SILENCED MUST BE ABLE TO COME BACK.
     *
     * Three consecutive delivery failures (timeouts count) deactivate a push
     * token server-side. Local state is untouched by that and looks perfect,
     * so no repair path would act on it:
     * `MainActivity.reconcilePushTokenRegistration` and `PushTokenSyncWorker`
     * both return early on `SessionManager.isPushTokenSynced()` — set by the
     * registration that succeeded, cleared only by logout or an FCM token
     * rotation — and the restore direction has no gap to fill. Not even a token
     * rotation could help: `PUT /devices/:id/push-token` UPDATES an existing row
     * and 404s when there is none.
     *
     * Without a signal the device would stay silent permanently, and the app's
     * only response would be to colour a line red. This flag is what makes it
     * actionable; the ONLY repair is a fresh device-registration POST, which is
     * what its readers call.
     */
    @Test
    fun `a silenced device asks to be re-registered, on either provider`() {
        val fcmStatus = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_FCM,
            pushToken = "fcm-token-still-here",
            pushEndpoint = null,
            serverRegistrations = emptyList(),
        )
        assertTrue("an FCM device the server dropped must re-register", fcmStatus.needsReregistration)

        // The server holding SOME row is not enough — it has to be one that can
        // address the channel this device is actually using.
        val wrongTypeStatus = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = "https://ntfy.sh/abc123?up=1",
            serverRegistrations = listOf(fcm()),
        )
        assertTrue(wrongTypeStatus.needsReregistration)
    }

    /**
     * Re-registration is a write. Every state that is NOT the silenced one must
     * leave it alone, or a healthy install re-POSTs its device row on each
     * render, and an offline one does it on every start.
     */
    @Test
    fun `nothing else asks to be re-registered`() {
        // Healthy: server and local agree.
        assertFalse(
            PushStatusResolver.resolve(
                pushType = SessionManager.PUSH_TYPE_FCM,
                pushToken = "fcm-token",
                pushEndpoint = null,
                serverRegistrations = listOf(fcm()),
            ).needsReregistration
        )

        // Paused is a deliberate server-side state, not a lost registration.
        assertFalse(
            PushStatusResolver.resolve(
                pushType = SessionManager.PUSH_TYPE_FCM,
                pushToken = "fcm-token",
                pushEndpoint = null,
                serverRegistrations = listOf(fcm(paused = true)),
            ).needsReregistration
        )

        // Never registered: there is nothing to recover, and the normal
        // registration path handles it.
        assertFalse(
            PushStatusResolver.resolve(
                pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
                pushToken = null,
                pushEndpoint = null,
                serverRegistrations = emptyList(),
            ).needsReregistration
        )

        // No server opinion at all (offline, or a backend without the
        // endpoint). Reading that as "the server has nothing" would re-register
        // on every flaky start.
        assertFalse(
            "a null answer is not an empty one",
            PushStatusResolver.resolve(
                pushType = SessionManager.PUSH_TYPE_FCM,
                pushToken = "fcm-token",
                pushEndpoint = null,
                serverRegistrations = null,
            ).needsReregistration
        )

        // The opposite skew: server live, local copy lost. That is a restore,
        // and re-registering instead would push this install's empty state over
        // a registration that is working.
        val restoreCase = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = null,
            serverRegistrations = listOf(webpush()),
        )
        assertNotNull(restoreCase.restore)
        assertFalse(restoreCase.needsReregistration)
    }

    // MARK: - Genuinely not set up

    @Test
    fun `nothing anywhere is not configured`() {
        val status = PushStatusResolver.resolve(null, null, null, emptyList())
        assertEquals("Status: Not configured", status.text)
        assertFalse(status.isError)
    }

    /** Chosen a provider, nothing registered yet, on either side. */
    @Test
    fun `provider chosen but registered nowhere is pending, not an error`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = null,
            serverRegistrations = emptyList(),
        )

        assertEquals("Status: UnifiedPush — registration pending", status.text)
        assertFalse(status.isError)
        assertNull(status.restore)
    }

    // MARK: - Healthy

    @Test
    fun `local and server agreeing needs no restore`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_FCM,
            pushToken = "fcm-token",
            pushEndpoint = null,
            serverRegistrations = listOf(fcm()),
        )

        assertEquals("Status: Google FCM", status.text)
        assertFalse(status.isError)
        assertNull(status.restore)
    }

    @Test
    fun `a paused channel says paused rather than claiming delivery`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_FCM,
            pushToken = "fcm-token",
            pushEndpoint = null,
            serverRegistrations = listOf(fcm(paused = true)),
        )

        assertEquals("Status: Google FCM — paused", status.text)
    }

    // MARK: - No opinion from the server

    /**
     * Offline, or a backend without the endpoint. The screen must fall back to
     * local state and must NOT invent an error — an unreachable server says
     * nothing about whether push works.
     */
    @Test
    fun `unknown server state falls back to local and never errors`() {
        val healthy = PushStatusResolver.resolve(
            SessionManager.PUSH_TYPE_UNIFIED_PUSH, null, "https://ntfy.sh/abc123?up=1", null
        )
        assertEquals("Status: UnifiedPush", healthy.text)
        assertFalse(healthy.isError)
        assertNull(healthy.restore)

        val pending = PushStatusResolver.resolve(
            SessionManager.PUSH_TYPE_UNIFIED_PUSH, null, null, null
        )
        assertEquals("Status: UnifiedPush — registration pending", pending.text)
        assertFalse(pending.isError)
        assertNull(pending.restore)
    }

    // MARK: - The mapping that would break everything silently

    /**
     * The server stores UnifiedPush as a "webpush" row. Get this wrong and
     * every UnifiedPush user matches no server registration, which lands them
     * in "not synced to server" — a red error for a working channel.
     */
    @Test
    fun `unifiedpush maps to the server's webpush spelling, both ways`() {
        assertEquals("webpush", PushStatusResolver.serverPushType(SessionManager.PUSH_TYPE_UNIFIED_PUSH))
        assertEquals("fcm", PushStatusResolver.serverPushType(SessionManager.PUSH_TYPE_FCM))
        assertNull(PushStatusResolver.serverPushType(null))
        assertNull(PushStatusResolver.serverPushType("nonsense"))

        assertEquals(SessionManager.PUSH_TYPE_UNIFIED_PUSH, PushStatusResolver.localPushType("webpush"))
        assertEquals(SessionManager.PUSH_TYPE_FCM, PushStatusResolver.localPushType("fcm"))
        assertNull(PushStatusResolver.localPushType("apns"))
    }

    /** A registration for the OTHER transport must not count as this one's. */
    @Test
    fun `an fcm row does not satisfy a unifiedpush selection`() {
        val status = PushStatusResolver.resolve(
            pushType = SessionManager.PUSH_TYPE_UNIFIED_PUSH,
            pushToken = null,
            pushEndpoint = "https://ntfy.sh/abc123?up=1",
            serverRegistrations = listOf(fcm()),
        )

        assertEquals("Status: UnifiedPush — not synced to server", status.text)
        assertTrue(status.isError)
    }

    // MARK: - Distributor line

    @Test
    fun `distributor line prefers either endpoint over offering setup`() {
        assertEquals(
            "No UnifiedPush distributor apps installed. Install ntfy or another distributor.",
            PushStatusResolver.distributorStatus(0, null, null)
        )
        // Distributor installed, local copy lost, server has it.
        assertEquals(
            "Registered (endpoint configured)",
            PushStatusResolver.distributorStatus(1, null, "https://ntfy.sh/abc123?up=1")
        )
        assertEquals(
            "Registered (endpoint configured)",
            PushStatusResolver.distributorStatus(1, "https://ntfy.sh/abc123?up=1", null)
        )
        assertEquals(
            "2 distributor(s) available",
            PushStatusResolver.distributorStatus(2, null, null)
        )
    }

    /** No distributor beats any endpoint: nothing on device can receive. */
    @Test
    fun `no distributor wins even when an endpoint is on file`() {
        assertEquals(
            "No UnifiedPush distributor apps installed. Install ntfy or another distributor.",
            PushStatusResolver.distributorStatus(0, "https://ntfy.sh/abc123?up=1", "https://ntfy.sh/abc123?up=1")
        )
    }
}
