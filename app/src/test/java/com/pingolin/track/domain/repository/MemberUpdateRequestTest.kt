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
import com.pingolin.track.data.remote.dto.MemberUpdateRequestResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A member listed with a sharing key and no signing key runs an app too old for the
 * circle. Nobody here can let them back in until they update, so this app asks the server
 * to tell them, and the member list says they have been asked. Nobody here is asked
 * anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberUpdateRequestTest {

    @After
    fun tearDown() = clearAllMocks()

    private fun TestScope.harness(): TrustHarness =
        TrustHarness(selfRole = "member").also {
            it.repository.deliveryScope = this
            it.earlierSeed("me", "owner", "bob")
        }

    private fun TrustHarness.bobWithoutSigning(updateRequested: Boolean = false) =
        rosterMember("bob").copy(signingPublicKey = null, updateRequested = updateRequested)

    private fun TrustHarness.serverTellsThem(): () -> Int {
        var calls = 0
        coEvery { tracksApi.requestMemberUpdate("c-1", "bob") } answers {
            calls++
            Response.success(ApiResponse(success = true, data = MemberUpdateRequestResponse(requested = true, sent = true), error = null))
        }
        return { calls }
    }

    @Test
    fun `a member whose app is too old is told to update by the server, and the list says so`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.bobWithoutSigning()))
        val calls = h.serverTellsThem()

        h.repository.refreshMembers("c-1")

        assertEquals(1, calls())
        assertEquals(setOf("bob"), h.repository.memberStates.value["c-1"]?.askedToUpdate)
        assertTrue("nobody here is asked anything", h.repository.memberKeyAlerts.value.isEmpty())

        h.repository.refreshMembers("c-1")
        assertEquals("once a day from this phone", 1, calls())
    }

    @Test
    fun `already asked by someone else - not asked again, and still shown as asked`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.bobWithoutSigning(updateRequested = true)))

        h.repository.refreshMembers("c-1")

        coVerify(exactly = 0) { h.tracksApi.requestMemberUpdate(any(), any()) }
        assertEquals(setOf("bob"), h.repository.memberStates.value["c-1"]?.askedToUpdate)
    }

    @Test
    fun `a refusal is not shown as asked`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.bobWithoutSigning()))
        coEvery { h.tracksApi.requestMemberUpdate("c-1", "bob") } returns Response.error(
            409, """{"success":false,"error_code":"member_up_to_date"}""".toResponseBody(null),
        )

        h.repository.refreshMembers("c-1")

        assertEquals(emptySet<String>(), h.repository.memberStates.value["c-1"]?.askedToUpdate)
    }

    @Test
    fun `members with both keys are left alone`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("owner", role = "owner"), h.rosterMember("bob")))

        h.repository.refreshMembers("c-1")

        coVerify(exactly = 0) { h.tracksApi.requestMemberUpdate(any(), any()) }
        assertEquals(emptySet<String>(), h.repository.memberStates.value["c-1"]?.askedToUpdate)
    }
}
