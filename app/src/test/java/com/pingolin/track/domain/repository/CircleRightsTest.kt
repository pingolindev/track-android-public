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
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.domain.repository.CircleRights.Records
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CircleRole

/**
 * Admins invite, manage invitations and remove members; only the owner changes roles, hands the
 * circle on or deletes it, as the server already rules. Each is offered only where it takes
 * effect: on a circle with refresh records an invitation from an owner or admin in the chain, a
 * role change from the chain's owner; and while the records wait, nothing at all.
 */
class CircleRightsTest {

    private fun rights(role: String?, records: Records, chain: CircleRole? = null) = CircleRights.of(role, records, chain)

    @Test
    fun `a member, or someone off the list, is offered nothing`() {
        for (records in Records.values()) {
            for (chain in listOf(null, CircleRole.OWNER, CircleRole.ADMIN, CircleRole.MEMBER)) {
                assertEquals("$records/$chain", CircleRights.Rights.NONE, rights("member", records, chain))
                assertEquals("$records/$chain", CircleRights.Rights.NONE, rights(null, records, chain))
            }
        }
    }

    @Test
    fun `without records the server's role decides - an owner everything, an admin all but roles`() {
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = true), rights("owner", Records.NONE))
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = false), rights("admin", Records.NONE))
    }

    @Test
    fun `while the records wait nothing is offered, owner or admin`() {
        for (chain in listOf(null, CircleRole.OWNER, CircleRole.ADMIN)) {
            assertEquals(CircleRights.Rights.NONE, rights("owner", Records.WAITING, chain))
            assertEquals(CircleRights.Rights.NONE, rights("admin", Records.WAITING, chain))
        }
    }

    @Test
    fun `with records an invitation counts from an owner or admin in the chain, a role change from the chain's owner`() {
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = true), rights("owner", Records.READ, CircleRole.OWNER))
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = false), rights("admin", Records.READ, CircleRole.ADMIN))
        // An owner the chain names only an admin (an ownership claim): no role changes as if they counted.
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = false), rights("owner", Records.READ, CircleRole.ADMIN))
        // An admin on the server's word only: their invitation wouldn't admit anyone, but the server still removes and withdraws for them.
        for (chain in listOf(CircleRole.MEMBER, null)) {
            assertEquals(CircleRights.Rights(invite = false, manageInvites = true, remove = true, changeRoles = false), rights("admin", Records.READ, chain))
            assertEquals(CircleRights.Rights(invite = false, manageInvites = true, remove = true, changeRoles = false), rights("owner", Records.READ, chain))
        }
    }

    // ===== Wiring: what the repository reads for them =====

    private fun harness(recorded: Boolean, head: Int, chain: CircleRole?) = TrustHarness().also { h ->
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns recorded
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to head)
        every { h.cryptoManager.chainRole("c-1", "me") } returns chain
    }

    /** A records read that succeeds, serving [circles]: the reply covers every circle this account is in. */
    private fun TrustHarness.readServes(vararg circles: CircleRefreshRecords) {
        coEvery { tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(circles.toList(), more = false), error = null))
    }

    @Test
    fun `a circle is told as having no records only once a read here served it so - till then nothing on the server's word`() = runTest {
        // A fresh install, a new phone, cleared data: the SDK has latched nothing, and no read has succeeded.
        val h = harness(recorded = false, head = 0, chain = null)
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))
        assertEquals("an admin by the server's word alone", CircleRights.Rights.NONE, h.repository.rightsIn("c-1", "admin"))
        assertEquals(CircleRights.Rights.NONE, h.repository.rightsIn("c-1", "owner"))
        assertFalse(h.repository.roleChangesTakeEffect("c-1"))

        // A read that fails decides nothing; one that hasn't the whole of c-1 yet, or not c-1, neither.
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.error(503, "{}".toResponseBody(null))
        h.repository.syncRefreshRecords()
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))
        h.readServes(CircleRefreshRecords("c-1", complete = false))
        h.repository.syncRefreshRecords()
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))
        h.readServes(CircleRefreshRecords("c-2", complete = true))
        h.repository.syncRefreshRecords()
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))

        // Served complete with nothing: no records, and the server's role decides.
        h.readServes(CircleRefreshRecords("c-1", complete = true))
        h.repository.syncRefreshRecords()
        assertEquals(Records.NONE, h.repository.recordsState("c-1"))
        assertEquals(CircleRights.Rights(invite = true, manageInvites = true, remove = true, changeRoles = false), h.repository.rightsIn("c-1", "admin"))
        assertTrue(h.repository.roleChangesTakeEffect("c-1"))
        // An SDK that can't say whether it has records here says nothing.
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } throws IllegalStateException("unreadable")
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false

        // The next account on this phone starts with nothing read.
        h.repository.wipeInMemory()
        assertEquals(Records.WAITING, h.repository.recordsState("c-1"))
    }

    @Test
    fun `the repository reads the records and this account's place in the chain`() {
        assertEquals("no records read here yet", Records.WAITING, harness(recorded = false, head = 0, chain = null).repository.recordsState("c-1"))
        assertEquals(Records.WAITING, harness(recorded = true, head = 0, chain = CircleRole.OWNER).repository.recordsState("c-1"))
        assertEquals(Records.READ, harness(recorded = true, head = 3, chain = CircleRole.OWNER).repository.recordsState("c-1"))

        assertTrue(harness(recorded = true, head = 3, chain = CircleRole.ADMIN).repository.rightsIn("c-1", "admin").invite)
        assertFalse("a server-only admin", harness(recorded = true, head = 3, chain = CircleRole.MEMBER).repository.rightsIn("c-1", "admin").invite)
        assertEquals(CircleRights.Rights.NONE, harness(recorded = true, head = 0, chain = CircleRole.OWNER).repository.rightsIn("c-1", "owner"))
    }

    @Test
    fun `role changes aren't offered while the records wait`() {
        assertFalse(harness(recorded = true, head = 0, chain = CircleRole.OWNER).repository.roleChangesTakeEffect("c-1"))
        assertTrue(harness(recorded = true, head = 3, chain = CircleRole.OWNER).repository.roleChangesTakeEffect("c-1"))
        assertFalse("no records read here yet", harness(recorded = false, head = 0, chain = null).repository.roleChangesTakeEffect("c-1"))
    }

    @Test
    fun `every read of the records says so, even one that decided nothing - the screens look again`() = runTest {
        val h = harness(recorded = true, head = 0, chain = CircleRole.OWNER)
        val before = h.repository.recordsNoted.value
        h.repository.syncRefreshRecords()
        assertEquals(before + 1, h.repository.recordsNoted.value)
    }
}
