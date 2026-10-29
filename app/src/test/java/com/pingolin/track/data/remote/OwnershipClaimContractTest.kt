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

package com.pingolin.track.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.OwnershipClaimResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract for the circle ownership claim routes.
 *
 * This DTO is the only thing standing between an owner and losing their circle by
 * silence. The server tells them a claim exists and completes it after fourteen days if
 * nobody refuses; if the client cannot read the payload, the refuse control never
 * appears and the transfer happens anyway. Nothing about that failure is visible — no
 * crash, no error — which is exactly why the names are pinned here.
 *
 * Retrofit's Gson is plain (`di/AppModule.kt`: `GsonBuilder().create()`), so a field
 * whose name does not match the server's is not an error: Gson builds the object through
 * Unsafe and leaves the field at its JVM default, which reads as a plausible zero rather
 * than a failure (see [com.pingolin.track.data.remote.dto.RotateKeysResponse]).
 *
 * Every name below is the server's literal wire name, not what seemed reasonable:
 * `claim_id`, `circle_id`, `claimant_user_id`, `status`, `requested_at` and
 * `resolves_at`, the last two RFC 3339 timestamps.
 */
class OwnershipClaimContractTest {

    private val gson = GsonBuilder().create()

    private val envelopeType =
        object : TypeToken<ApiResponse<OwnershipClaimResponse>>() {}.type

    private fun decodeEnvelope(json: String): ApiResponse<OwnershipClaimResponse> =
        gson.fromJson(json, envelopeType)

    private val liveClaim = """
        {"success":true,"data":{
          "claim_id":"7b1f0f4e-0000-4000-8000-00000000c1a1",
          "circle_id":"7b1f0f4e-0000-4000-8000-00000000c123",
          "claimant_user_id":"7b1f0f4e-0000-4000-8000-0000000000ad",
          "status":"pending",
          "requested_at":"2026-08-23T10:15:30.123456Z",
          "resolves_at":"2026-09-06T10:15:30.123456Z"}}
    """.trimIndent()

    /** Every field the server sends has to land, under the server's own name. */
    @Test
    fun `the claim decodes under the server's field names`() {
        val claim = decodeEnvelope(liveClaim).data
        assertNotNull("the claim must decode at all", claim)
        requireNotNull(claim)

        assertEquals("7b1f0f4e-0000-4000-8000-00000000c1a1", claim.claimId)
        assertEquals("7b1f0f4e-0000-4000-8000-00000000c123", claim.circleId)
        assertEquals("7b1f0f4e-0000-4000-8000-0000000000ad", claim.claimantUserId)
        assertEquals("pending", claim.status)
        assertEquals("2026-08-23T10:15:30.123456Z", claim.requestedAt)
        assertEquals("2026-09-06T10:15:30.123456Z", claim.resolvesAt)
    }

    /**
     * The deadline is the one number the owner can act on, and the server sends it. This
     * is the other half of that promise: it has to survive the mapping too, not just the
     * serialisation.
     */
    @Test
    fun `the deadline survives the mapping`() {
        val claim = requireNotNull(decodeEnvelope(liveClaim).data)
        assertEquals(
            "resolves_at must reach the client — without it the owner sees no deadline",
            "2026-09-06T10:15:30.123456Z",
            claim.resolvesAt,
        )
    }

    /**
     * "Nobody is claiming this circle" is a 200 with a null payload, not an error and not
     * an absent key. Reading it as a failure would make every unclaimed circle look
     * broken; reading a failure as this would make every claimed one look safe.
     */
    @Test
    fun `no live claim decodes as a null payload, not a failure`() {
        val envelope = decodeEnvelope("""{"success":true,"data":null}""")

        assertTrue("the call still succeeded", envelope.success)
        assertNull("no claim means a null payload", envelope.data)
        assertNull(envelope.error)
    }

    /**
     * The Gson trap, pinned. A server that stops sending a field must leave it null so the
     * UI can tell "not sent" from "sent as empty": a DTO that read an absent version as 0
     * and put it into Room would lose that distinction (see `RotateKeysResponse`).
     */
    @Test
    fun `fields the server omits stay null rather than becoming defaults`() {
        val claim = requireNotNull(
            decodeEnvelope("""{"success":true,"data":{"claim_id":"only-this"}}""").data
        )

        assertEquals("only-this", claim.claimId)
        assertNull(claim.resolvesAt)
        assertNull(claim.requestedAt)
        assertNull(claim.claimantUserId)
        assertNull(claim.status)
        assertNull(claim.circleId)
    }

    /**
     * Proves the `@SerializedName` annotations are what is doing the work, rather than the
     * Kotlin property names happening to line up.
     *
     * If somebody removes them, this payload — the camelCase spelling the server does NOT
     * use — starts decoding, and the real snake_case one silently stops. Without this
     * test that swap looks like a no-op refactor.
     */
    @Test
    fun `camelCase names are not accepted — the wire is snake_case`() {
        val claim = requireNotNull(
            decodeEnvelope(
                """{"success":true,"data":{"claimId":"x","resolvesAt":"y","claimantUserId":"z"}}"""
            ).data
        )

        assertNull("claim_id is the wire name, not claimId", claim.claimId)
        assertNull("resolves_at is the wire name, not resolvesAt", claim.resolvesAt)
        assertNull("claimant_user_id is the wire name, not claimantUserId", claim.claimantUserId)
    }

    /**
     * The 409 an admin gets when somebody has already asked. The code is what the UI
     * switches on, read from the envelope's top level.
     */
    @Test
    fun `the duplicate-claim refusal carries its error code`() {
        val envelope = decodeEnvelope(
            """{"success":false,"data":null,
                "error":"Somebody has already asked to take over this circle",
                "error_code":"CIRCLE_OWNERSHIP_CLAIM_EXISTS"}"""
        )

        assertEquals("CIRCLE_OWNERSHIP_CLAIM_EXISTS", envelope.errorCode)
        assertEquals("Somebody has already asked to take over this circle", envelope.error)
        assertNull(envelope.data)
    }
}
