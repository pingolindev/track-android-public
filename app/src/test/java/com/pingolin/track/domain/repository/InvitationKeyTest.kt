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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.MembershipStatements.Kind
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleRole

/**
 * The key an invitation carries is a delivery. On a circle with refresh records it goes only by
 * this device's own fresh invitation: this account an owner or admin in the chain, holding the
 * version in use. Otherwise the invitation goes without a key (it is still signed), and the
 * invitee waits for a member's app to hand them the circle. A circle without records: the
 * invitation carries the key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InvitationKeyTest {

    /** Accounts the invite lookup names are UUIDs (AccountIds.strict). */
    private val dan = "da400000-0000-4000-8000-0000000000d4"

    @After
    fun tearDown() = clearAllMocks()

    private fun TestScope.harness(chainRoleOfMe: CircleRole?, recorded: Boolean = true) = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice")
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns recorded
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 3)
        every { h.cryptoManager.chainRole("c-1", "me") } returns chainRoleOfMe
        coEvery { h.tracksApi.lookupPublicKey("dan@x.test") } returns Response.success(
            ApiResponse(success = true, data = UserPublicKeyResponse("dan@x.test", sharing(dan), dan, signing(dan)), error = null)
        )
    }

    private fun TrustHarness.invitationsTaken(): io.mockk.CapturingSlot<InviteRequest> {
        val sent = slot<InviteRequest>()
        coEvery { tracksApi.inviteToCircle("c-1", capture(sent)) } returns Response.success(
            ApiResponse(true, InviteResponse(delivery = "notified"), null)
        )
        return sent
    }

    @Test
    fun `on a circle with records an invitation carries the key only by an owner or admin in the chain`() = runTest {
        for ((role, carries) in listOf(CircleRole.OWNER to true, CircleRole.ADMIN to true, CircleRole.MEMBER to false, null to false)) {
            val h = harness(role)
            val sent = h.invitationsTaken()

            assertTrue("$role", h.repository.inviteToCircle("c-1", "dan@x.test").isSuccess)

            assertEquals("$role", carries, sent.captured.encryptedCircleKey != null)
            assertEquals("$role", carries, sent.captured.ephemeralPublicKey != null)
            // Signed for dan's account either way.
            assertEquals(Kind.INVITATION_TO_ACCOUNT, h.statements.parse(sent.captured.statement!!)!!.kind)
        }
    }

    @Test
    fun `on a circle without records the invitation carries the key, wrapped to the invitee`() = runTest {
        val h = harness(chainRoleOfMe = null, recorded = false)
        val sent = h.invitationsTaken()

        assertTrue(h.repository.inviteToCircle("c-1", "dan@x.test").isSuccess)

        assertEquals("c-${sharing(dan)}", sent.captured.encryptedCircleKey?.ciphertext)
    }
}
