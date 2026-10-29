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
import com.pingolin.track.data.remote.dto.RefreshRecordPayload
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.RotateKeysRequest
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.EligibilityPurpose
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.KeyRotationBundle
import uniffi.pingolin_mobile.MemberEligibility
import uniffi.pingolin_mobile.MemberKeyBundle
import uniffi.pingolin_mobile.MemberKeyRotationEntry
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMemberState
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordedRotationBundle
import uniffi.pingolin_mobile.RefreshRecordKind
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * A circle with refresh records rotates with its record, the SDK's eligibility check
 * (INCLUDE) deciding who gets the new key; an owner's or admin's device writes a
 * record-less circle's first record at once (the transition); a plain member's device
 * rotates a record-less circle without a record. The SDK is mocked: what matters is
 * what the app asks it and sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordedRotationFlowTest {

    @After
    fun tearDown() = clearAllMocks()

    private val built = mutableListOf<Pair<RefreshRecordKind, List<PlannedEntry>>>()

    private fun TestScope.harness(selfRole: String = "owner", flagged: Boolean = true, recorded: Boolean = true) = TrustHarness(selfRole = selfRole).also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice", "bob")
        h.circle = h.circle.copy(keyRotationNeeded = flagged, isOwner = selfRole == "owner")
        // carol: listed, nothing signed about her here, so not confirmed.
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol")))
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns recorded
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 3)
        every { h.cryptoManager.chainRole("c-1", any()) } answers {
            when (arg<String>(1)) { "me" -> CircleRole.OWNER; "alice", "bob", "carol" -> CircleRole.MEMBER; else -> null }
        }
        // The eligibility check (INCLUDE) allows alice; bob's admission is too old for a
        // removal the chain records.
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            arg<List<uniffi.pingolin_mobile.RecordMember>>(1).map { m ->
                MemberEligibility(m.userId, m.userId == "alice", null, false, false, false, null, false)
            }
        }
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            val entries = arg<List<PlannedEntry>>(3)
            built += arg<RefreshRecordKind>(2) to entries
            val included = entries.filter { it.state == RecordMemberState.INCLUDED }.map { it.userId }
            RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = 4,
                    encryptedName = EncryptedData("nn", "cc"),
                    memberKeys = included.map { MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it")) },
                    keyChainLink = KeyChainLinkData(4, 3, "ek", "nk"),
                ),
                SignedRefreshRecord("record-hex", "record-sig"),
            )
        }
        coEvery { h.tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(emptyList(), more = false), error = null))
    }

    @Test
    fun `a circle with records rotates with its record, and E_include decides who gets the key`() = runTest {
        val h = harness()
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        val (kind, entries) = built.single()
        assertEquals(RefreshRecordKind.ROTATION, kind)
        assertEquals(
            listOf(
                PlannedEntry("me", RecordMemberState.INCLUDED, null),
                PlannedEntry("alice", RecordMemberState.INCLUDED, null),
                PlannedEntry("bob", RecordMemberState.PENDING, null),
                PlannedEntry("carol", RecordMemberState.PENDING, null),
            ),
            entries,
        )
        with(sent.captured) {
            assertEquals(RefreshRecordPayload("record-hex", "record-sig"), refreshRecord)
            assertEquals("the version the record moves from", 3, expectedKeyVersion)
            assertEquals(listOf("me", "alice"), memberKeys.map { it.userId })
            assertEquals(setOf("bob", "carol"), pendingMembers.orEmpty().toSet())
            assertEquals(mapOf("from_version" to 4, "to_version" to 3, "encrypted_key" to "ek", "nonce" to "nk"), keyChainLink)
        }
        verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.cryptoManager.createKeyChainLink(any()) }
    }

    @Test
    fun `the version sent is the one the record moves from, not what a stale circle list says`() = runTest {
        // With a record, the new version is expected_key_version + 1.
        val h = harness()
        h.heldKeyVersion = 4 // the list still says v3; this device holds v4, and its record goes to v5
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } returns RecordedRotationBundle(
            KeyRotationBundle(5, EncryptedData("nn", "cc"), emptyList(), KeyChainLinkData(5, 4, "ek", "nk")),
            SignedRefreshRecord("record-hex", "record-sig"),
        )
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(4, sent.captured.expectedKeyVersion)
    }

    @Test
    fun `an owner's device writes a record-less circle's first record at once, from the member list and its roles`() = runTest {
        val h = harness(flagged = false, recorded = false)
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue("due at once, unflagged", h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        val (kind, entries) = built.single()
        assertEquals(RefreshRecordKind.TRANSITION, kind)
        assertEquals(
            listOf(
                PlannedEntry("me", RecordMemberState.INCLUDED, CircleRole.OWNER),
                PlannedEntry("alice", RecordMemberState.INCLUDED, CircleRole.MEMBER),
                PlannedEntry("bob", RecordMemberState.INCLUDED, CircleRole.MEMBER),
                PlannedEntry("carol", RecordMemberState.PENDING, CircleRole.MEMBER),
            ),
            entries,
        )
        assertEquals(RefreshRecordPayload("record-hex", "record-sig"), sent.captured.refreshRecord)
        assertEquals(listOf("carol"), sent.captured.pendingMembers)
    }

    @Test
    fun `a plain member's device rotates a record-less circle without a record`() = runTest {
        val h = harness(selfRole = "member", recorded = false)
        // Everyone listed is confirmed here, so a plain member may finish the flagged refresh.
        h.serverLists(listOf(h.rosterMember("owner", role = "owner"), h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob")))
        h.earlierSeed("me", "owner", "alice", "bob")
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertFalse("no transition from a plain member's device", h.repository.rotationDue(h.circle.copy(keyRotationNeeded = false)))

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(built.isEmpty())
        verify(exactly = 1) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        assertNull(sent.captured.refreshRecord)
    }

    @Test
    fun `a transition the builder refuses leaves the circle as it is, and isn't tried again at once`() = runTest {
        // A listed owner with no keys: no transition can be written.
        val h = harness(flagged = false, recorded = false)
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("a listed owner with no keys")
        h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        assertFalse(h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a flagged record-less circle whose transition is refused rotates without a record`() = runTest {
        val h = harness(flagged = true, recorded = false)
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("a listed owner with no keys")
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        verify(exactly = 1) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        assertNull(sent.captured.refreshRecord)
    }

    private fun TrustHarness.ejectsTaken(): io.mockk.CapturingSlot<com.pingolin.track.data.remote.dto.EjectAndRotateRequest> {
        val sent = slot<com.pingolin.track.data.remote.dto.EjectAndRotateRequest>()
        coEvery { tracksApi.ejectAndRotate("c-1", capture(sent)) } returns Response.success(
            ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "bob"), error = null)
        )
        return sent
    }

    @Test
    fun `an eject of a circle with records names them removed, and sends its record`() = runTest {
        val h = harness(flagged = false)
        val sent = h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)

        assertEquals(
            listOf(
                PlannedEntry("me", RecordMemberState.INCLUDED, null),
                PlannedEntry("alice", RecordMemberState.INCLUDED, null),
                PlannedEntry("bob", RecordMemberState.REMOVED, null),
                PlannedEntry("carol", RecordMemberState.PENDING, null),
            ),
            built.single().second,
        )
        with(sent.captured) {
            assertEquals("bob", ejectUserId)
            assertEquals(4, newKeyVersion)
            assertEquals(RefreshRecordPayload("record-hex", "record-sig"), refreshRecord)
            assertEquals(listOf("me", "alice"), memberWraps.map { it.userId })
            assertEquals("never the member removed", listOf("carol"), pendingMembers)
            assertEquals(mapOf("from_version" to 4, "to_version" to 3, "encrypted_key" to "ek", "nonce" to "nk"), keyChainLink)
        }
        verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an owner's eject of a record-less circle writes its first record, without them`() = runTest {
        val h = harness(flagged = false, recorded = false)
        val sent = h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)

        val (kind, entries) = built.single()
        assertEquals(RefreshRecordKind.TRANSITION, kind)
        assertEquals(listOf("me", "alice", "carol"), entries.map { it.userId })
        assertEquals(RefreshRecordPayload("record-hex", "record-sig"), sent.captured.refreshRecord)
        assertEquals(listOf("carol"), sent.captured.pendingMembers)
    }

    @Test
    fun `an eject the server finds stale reads the records again, and says so in this app's words`() = runTest {
        val h = harness(flagged = false)
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.error(
            409, """{"success":false,"error":"stale: fetch the records","error_code":"key_refresh_record_stale"}""".toResponseBody(null),
        )

        val removed = h.repository.ejectMember("c-1", "bob")

        assertEquals("Couldn't remove them just now.", removed.exceptionOrNull()?.message)
        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
        coVerify(atLeast = 1) { h.tracksApi.refreshRecords(any()) }
    }

    @Test
    fun `a removal made while behind is named removed by this device's next rotation, then forgotten`() = runTest {
        // Removal while behind: revoke-access, no new key.
        val h = harness(flagged = false)
        h.circleVersion = 4
        coEvery { h.tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        coEvery { h.tracksApi.revokeMemberAccess("c-1", "bob") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.RevokeAccessResponse("bob", true, "warning"), error = null))
        h.repository.catchUpPausesMs = emptyList()

        assertEquals(false, h.repository.removeMember("c-1", "bob").getOrNull())
        assertEquals(setOf("bob"), h.ownRemovals.of("c-1"))

        // Caught up, and the circle flagged by the removal: this device rotates, with bob named removed
        // (off the list by now).
        h.heldKeyVersion = 4
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("carol")))
        h.rotationAccepted()
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in built.single().second)
        assertEquals("named, so forgotten", emptySet<String>(), h.ownRemovals.of("c-1"))
    }

    /**
     * v4's record taken here as [head] says (4: accepted or a reference; 3: v4's record waiting;
     * 0: forked), and v4's key never came: this device seals under v3. The list shows the
     * placeholder.
     */
    private fun TrustHarness.leftBelow(head: Int) {
        circleVersion = 4
        heldKeyVersion = 3
        every { cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to head)
        repository.catchUpPausesMs = emptyList()
        coEvery { tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        circle = circle.copy(name = LockedCircle.PLACEHOLDER_NAME)
        every { cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            built += arg<RefreshRecordKind>(2) to arg<List<PlannedEntry>>(3)
            val included = arg<List<PlannedEntry>>(3).filter { it.state == RecordMemberState.INCLUDED }.map { it.userId }
            RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = 5,
                    encryptedName = EncryptedData("nn", "cc"),
                    memberKeys = included.map { MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it")) },
                    keyChainLink = KeyChainLinkData(5, 3, "ek", "nk"),
                ),
                SignedRefreshRecord("record-hex", "record-sig"),
            )
        }
    }

    @Test
    fun `an owner's eject goes from below a version whose record this device took but whose key never came`() = runTest {
        // The SDK builds on v4's record and links down to v3.
        val h = harness(flagged = false)
        h.leftBelow(head = 4)
        val sent = h.ejectsTaken()

        assertEquals("re-keyed", true, h.repository.removeMember("c-1", "bob").getOrNull())

        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in built.single().second)
        // From below, no name: the key in use there is the removed member's.
        verify { h.cryptoManager.prepareRecordedRotation("c-1", "me", RefreshRecordKind.ROTATION, any(), any(), true, "") }
        with(sent.captured) {
            assertEquals(5, newKeyVersion)
            assertNull("no name sent: rotate-keys keeps the circle's", encryptedName)
            assertTrue("the member removed gets no wrap", memberWraps.none { it.userId == "bob" })
            assertEquals(mapOf("from_version" to 5, "to_version" to 3, "encrypted_key" to "ek", "nonce" to "nk"), keyChainLink)
        }
        coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
    }

    @Test
    fun `a name that doesn't open here never stops an eject from the top, and the new key carries none`() = runTest {
        val h = harness(flagged = false)
        h.circle = h.circle.copy(name = LockedCircle.PLACEHOLDER_NAME)
        val sent = h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)
        verify { h.cryptoManager.prepareRecordedRotation("c-1", "me", RefreshRecordKind.ROTATION, any(), any(), true, "") }
        assertNull("rotate-keys keeps the circle's", sent.captured.encryptedName)
    }

    @Test
    fun `no eject from below a version whose record waits here, or while the chain is forked`() = runTest {
        for (head in listOf(3, 0)) {
            built.clear()
            val h = harness(flagged = false)
            h.leftBelow(head)
            h.ejectsTaken()

            assertTrue("head $head", h.repository.ejectMember("c-1", "bob").exceptionOrNull() is KeyNotCurrentException)
            assertTrue(built.isEmpty())
            coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(any(), any()) }
        }
    }

    @Test
    fun `the eject from below goes without a name, and the circle's stays as it is`() = runTest {
        // The name never blocks an eject from below, and the eject carries none.
        val h = harness(flagged = false)
        h.leftBelow(head = 4)
        val sent = h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)

        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in built.single().second)
        assertNull("no name sent: rotate-keys keeps the circle's", sent.captured.encryptedName)
        assertEquals(5, sent.captured.newKeyVersion)
    }

    @Test
    fun `an ownership claim - the claimant's eject of the chain's owner names itself owner`() = runTest {
        // The server's owner is an admin in the chain, whose owner is still listed.
        val h = harness(flagged = false)
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        every { h.cryptoManager.chainRole("c-1", "bob") } returns CircleRole.OWNER
        h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)

        val entries = built.single().second
        assertTrue(PlannedEntry("me", RecordMemberState.INCLUDED, CircleRole.OWNER) in entries)
        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in entries)
        assertFalse("role changes only on the list's word stay the server's", h.repository.roleChangesTakeEffect("c-1"))
    }

    @Test
    fun `an ownership claim - with the chain's owner off the list the claimant's next rotation claims it, and goes without the claim once the builder refuses it`() = runTest {
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        // The departure of the chain's owner doesn't count yet: the builder refuses the claim (Role).
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), match { plan -> plan.any { it.userId == "me" && it.role == CircleRole.OWNER } }, any(), any(), any()) } answers {
            built += arg<RefreshRecordKind>(2) to arg<List<PlannedEntry>>(3)
            throw MobileException.InvalidInput("the plan needs a right this device lacks (Role)")
        }
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(listOf(CircleRole.OWNER, null), built.map { (_, entries) -> entries.single { it.userId == "me" }.role })
        assertEquals(RefreshRecordPayload("record-hex", "record-sig"), sent.captured.refreshRecord)
    }

    @Test
    fun `no claim while the chain's owner is on the list, none by a plain member of the chain, and none by an admin the list doesn't show as its owner`() = runTest {
        // (list role, chain role, the chain's owner listed)
        for ((listRole, me, ownerListed) in listOf(Triple("owner", CircleRole.ADMIN, true), Triple("owner", CircleRole.MEMBER, false), Triple("admin", CircleRole.ADMIN, false))) {
            built.clear()
            val h = harness(selfRole = listRole)
            every { h.cryptoManager.chainRole("c-1", "me") } returns me
            if (ownerListed) every { h.cryptoManager.chainRole("c-1", "alice") } returns CircleRole.OWNER
            h.repository.refreshMembers("c-1")
            h.rotationAccepted()

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()

            assertEquals("$listRole/$me", null, built.single().second.single { it.userId == "me" }.role)
        }
    }

    @Test
    fun `the claimant's eject of the chain's owner fails when the builder refuses its claim, and isn't built again without it`() = runTest {
        val h = harness(flagged = false)
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        every { h.cryptoManager.chainRole("c-1", "bob") } returns CircleRole.OWNER
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            built += arg<RefreshRecordKind>(2) to arg<List<PlannedEntry>>(3)
            throw MobileException.InvalidInput("the plan needs a right this device lacks (Removal)")
        }
        h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isFailure)

        assertEquals(1, built.size)
        coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(any(), any()) }
    }

    @Test
    fun `an admin in the chain ejects with a new key, naming them removed`() = runTest {
        val h = harness(selfRole = "admin", flagged = false)
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        val sent = h.ejectsTaken()

        assertEquals("re-keyed", true, h.repository.removeMember("c-1", "bob").getOrNull())
        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in built.single().second)
        assertEquals("bob", sent.captured.ejectUserId)
        coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
    }

    @Test
    fun `an admin only on the server's list removes without a new key, and leaves the departure to the builder`() = runTest {
        // Ownership claims: a server-only promotion gives no right to a "removed".
        val h = harness(selfRole = "admin", flagged = false)
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.MEMBER
        coEvery { h.tracksApi.revokeMemberAccess("c-1", "bob") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.RevokeAccessResponse("bob", true, "warning"), error = null))

        assertEquals("removed, without a new key", false, h.repository.removeMember("c-1", "bob").getOrNull())
        coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(any(), any()) }
        assertTrue(built.isEmpty())

        // The circle's next flagged rotation: bob, off the list, isn't this device's to name.
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.serverLists(listOf(h.rosterMember("me", role = "admin"), h.rosterMember("alice"), h.rosterMember("carol")))
        h.repository.refreshMembers("c-1")
        h.rotationAccepted()
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(built.single().second.none { it.userId == "bob" })
        assertEquals(emptySet<String>(), h.ownRemovals.of("c-1"))
    }

    @Test
    fun `the owner's role change is recorded by its next rotation, at once, and then forgotten`() = runTest {
        // Only a record of the chain's owner changes a role; it rotates right after the change.
        val h = harness(flagged = false)
        // The rotation the change starts, joined below (its member list is judged off the test dispatcher).
        val started = kotlinx.coroutines.SupervisorJob()
        h.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(coroutineContext + started)
        every { h.cryptoManager.chainRole("c-1", "alice") } returns CircleRole.ADMIN
        coEvery { h.tracksApi.updateMemberRole("c-1", "alice", any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        h.rotationAccepted()

        assertTrue(h.repository.updateMemberRole("c-1", "alice", "member").isSuccess)
        started.children.toList().forEach { it.join() }

        assertTrue(PlannedEntry("alice", RecordMemberState.INCLUDED, CircleRole.MEMBER) in built.single().second)
        assertEquals("recorded, so forgotten", emptyMap<String, String>(), h.ownerRoleChanges.of("c-1"))
    }

    /**
     * dave joined after the circle's last record: listed, confirmed here, and never named by the chain.
     * [allowed]: whether the SDK's eligibility check (INCLUDE) allows him (a promotion's own
     * admission is one of its bases); [keysOf]: whose keys his row lists.
     */
    private fun daveJoined(h: TrustHarness, allowed: Boolean = true, keysOf: String = "dave") {
        h.earlierSeed("me", "alice", "bob", "dave")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("dave", keysOf = keysOf)))
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            arg<List<uniffi.pingolin_mobile.RecordMember>>(1).map { m ->
                MemberEligibility(m.userId, m.userId == "alice" || (allowed && m.userId == "dave"), null, false, false, false, null, false)
            }
        }
    }

    @Test
    fun `a promotion of a member the chain never named is recorded at once, the record naming him admin`() = runTest {
        val h = harness(flagged = false)
        daveJoined(h)
        h.repository.refreshMembers("c-1")
        h.ownerRoleChanges.put("c-1", "dave", "admin")
        h.rotationAccepted()

        assertTrue("due, though the chain never named him", h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(PlannedEntry("dave", RecordMemberState.INCLUDED, CircleRole.ADMIN) in built.single().second)
        assertEquals("recorded, so forgotten", emptyMap<String, String>(), h.ownerRoleChanges.of("c-1"))
    }

    @Test
    fun `a never-named member's promotion that no record can include yet isn't due, so nothing rotates or loops`() = runTest {
        val notAllowed = harness(flagged = false)
        daveJoined(notAllowed, allowed = false)
        notAllowed.repository.refreshMembers("c-1")
        notAllowed.ownerRoleChanges.put("c-1", "dave", "admin")
        assertFalse("E_include doesn't allow him", notAllowed.repository.rotationDue(notAllowed.circle))
        notAllowed.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertTrue(built.isEmpty())
        assertEquals("still owed", mapOf("dave" to "admin"), notAllowed.ownerRoleChanges.of("c-1"))

        val keyChanged = harness(flagged = false)
        daveJoined(keyChanged, keysOf = "dave-new")
        keyChanged.repository.refreshMembers("c-1")
        keyChanged.ownerRoleChanges.put("c-1", "dave", "admin")
        assertFalse(
            "his listed keys aren't the ones confirmed here, so the rotation plan wouldn't wrap to him",
            keyChanged.repository.rotationDue(keyChanged.circle),
        )
    }

    @Test
    fun `owed promotions are never due on a device whose account is only an admin in the chain`() = runTest {
        val h = harness(flagged = false)
        daveJoined(h)
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        h.repository.refreshMembers("c-1")
        h.ownerRoleChanges.put("c-1", "dave", "admin")
        h.ownerRoleChanges.put("c-1", "alice", "admin")
        assertFalse(h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a demotion made while the promotion's record is on its way stays owed, and is due next`() = runTest {
        val h = harness(flagged = false)
        daveJoined(h)
        h.repository.refreshMembers("c-1")
        h.ownerRoleChanges.put("c-1", "dave", "admin")
        // "Remove admin" goes through while the server takes the record that names him admin.
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } answers {
            h.ownerRoleChanges.put("c-1", "dave", "member")
            Response.success(ApiResponse(success = true, data = RotateKeysResponse(newKeyVersion = h.circleVersion + 1), error = null))
        }

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(PlannedEntry("dave", RecordMemberState.INCLUDED, CircleRole.ADMIN) in built.single().second)
        assertEquals("the demotion stays owed", mapOf("dave" to "member"), h.ownerRoleChanges.of("c-1"))
        // Once the chain shows him admin, his demotion is due.
        every { h.cryptoManager.chainRole("c-1", "dave") } returns CircleRole.ADMIN
        assertTrue(h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a never-named member's demotion stays owed, isn't due, and writes nothing`() = runTest {
        val h = harness(flagged = false)
        val started = kotlinx.coroutines.SupervisorJob()
        h.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(coroutineContext + started)
        daveJoined(h, allowed = false)
        h.repository.refreshMembers("c-1")
        coEvery { h.tracksApi.updateMemberRole("c-1", "dave", any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        h.rotationAccepted()

        assertTrue(h.repository.updateMemberRole("c-1", "dave", "member").isSuccess)
        started.children.toList().forEach { it.join() }

        assertTrue("nothing to record", built.isEmpty())
        assertEquals("owed till a record names him", mapOf("dave" to "member"), h.ownerRoleChanges.of("c-1"))
        assertFalse(h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a role change the rotation can't send yet waits for the next one`() = runTest {
        val h = harness(flagged = false)
        every { h.cryptoManager.chainRole("c-1", "alice") } returns CircleRole.ADMIN
        h.ownerRoleChanges.put("c-1", "alice", "member")
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns Response.error(503, "{}".toResponseBody(null))

        assertTrue(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(PlannedEntry("alice", RecordMemberState.INCLUDED, CircleRole.MEMBER) in built.single().second)
        assertEquals(mapOf("alice" to "member"), h.ownerRoleChanges.of("c-1"))
    }

    @Test
    fun `a role change by an owner only on the server's list stays the server's, and no rotation runs for it`() = runTest {
        // Ownership claims: the claimant's role changes are refused by the builder (Role): no retry, no
        // loop.
        val h = harness(flagged = false)
        // The chain's owner (bob) is still listed: its roles stay theirs. alice is an
        // admin in the chain, so the change differs from it.
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        every { h.cryptoManager.chainRole("c-1", "bob") } returns CircleRole.OWNER
        every { h.cryptoManager.chainRole("c-1", "alice") } returns CircleRole.ADMIN
        coEvery { h.tracksApi.updateMemberRole("c-1", "alice", any()) } returns Response.success(ApiResponse(success = true, data = Unit, error = null))
        h.rotationAccepted()

        assertTrue(h.repository.updateMemberRole("c-1", "alice", "member").isSuccess)
        advanceUntilIdle()

        assertTrue(built.isEmpty())
        assertFalse(h.repository.rotationDue(h.circle))

        // The circle's next flagged rotation writes no role, and forgets the change.
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(built.single().second.all { it.role == null })
        assertEquals(emptyMap<String, String>(), h.ownerRoleChanges.of("c-1"))
    }

    @Test
    fun `a rotation that doesn't land keeps the removal for the next one`() = runTest {
        val h = harness()
        h.ownRemovals.add("c-1", "bob", h.statements.now)
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns Response.error(503, "{}".toResponseBody(null))

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(PlannedEntry("bob", RecordMemberState.REMOVED, null) in built.single().second)
        assertEquals(setOf("bob"), h.ownRemovals.of("c-1"))
    }

    /** [version] rejected here for [problem], not allowing [notAllowed]; the tip a plan builds on names [named] (default: them). */
    private fun rejected(version: Int, problem: RecordProblem, vararg notAllowed: String, named: List<String> = notAllowed.toList()) = RefreshRecords.Judged(
        uniffi.pingolin_mobile.RefreshRecordsReport(
            listOf(uniffi.pingolin_mobile.RecordVersionStatus(version, uniffi.pingolin_mobile.RecordVersionState.REJECTED, problem, notAllowed.toList(), 0u, named)),
            2, false, false, emptyList(), named,
        ),
        againstFreshList = true,
    )

    /** v3's record rejected here: this device seals under v2 and holds v3 to read with. */
    private fun TrustHarness.rejectedHere(judged: RefreshRecords.Judged) {
        every { cryptoManager.getActiveVersion("c-1") } returns 2
        every { cryptoManager.newestHeldVersion("c-1") } returns 3
        repository.recordsJudged["c-1"] = judged
    }

    @Test
    fun `on a rejected tip the repair carries the listed members it includes that E doesn't allow here, and names them removed once removed here`() = runTest {
        // The member newt, listed, first named by v3, which this device rejected
        // (R2_NOT_ALLOWED); the chain doesn't count newt, and the builder builds on v3.
        // Here only the repair builds on a rejected tip: a plain rotation or an eject waits
        // for the current version (holdsCurrentVersion), and an eject then removes without
        // a new key.
        for (removedHere in listOf(false, true)) {
            built.clear()
            val h = harness(flagged = false)
            h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("newt")))
            every { h.cryptoManager.chainRole("c-1", "newt") } returns null
            every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 2)
            h.rejectedHere(rejected(3, RecordProblem.R2_NOT_ALLOWED, "newt"))
            if (removedHere) h.ownRemovals.add("c-1", "newt", h.statements.now)
            h.rotationAccepted()
            h.repository.refreshMembers("c-1")
            advanceUntilIdle()

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()

            val (kind, entries) = built.single()
            assertEquals(RefreshRecordKind.REPAIR, kind)
            val expected = if (removedHere) RecordMemberState.REMOVED else RecordMemberState.PENDING
            assertTrue("removed here: $removedHere", PlannedEntry("newt", expected, null) in entries)
        }
    }

    /** The records read serves one record, and the SDK judges it as [judged] says. */
    private fun TrustHarness.importJudges(judged: RefreshRecords.Judged) {
        every { cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } returns judged.report
        coEvery { tracksApi.refreshRecords(any()) } returns Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(
            listOf(com.pingolin.track.data.remote.dto.CircleRefreshRecords("c-1", listOf(com.pingolin.track.data.remote.dto.ServedRefreshRecord(4, "r4", "s4")), complete = true)),
            more = false,
        ), error = null))
    }

    @Test
    fun `the rejected tip's names go into every recorded kind, and none once an import judged this device's own record`() = runTest {
        val h = harness(flagged = false)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("newt")))
        every { h.cryptoManager.chainRole("c-1", "newt") } returns null
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        // The tip a plan builds on names newt, pending there: not a refusal here (notAllowed is empty).
        h.repository.recordsJudged["c-1"] = rejected(4, RecordProblem.SIGNER_UNKNOWN, named = listOf("newt"))
        h.ejectsTaken()

        assertTrue(h.repository.ejectMember("c-1", "bob").isSuccess)
        assertTrue(PlannedEntry("newt", RecordMemberState.PENDING, null) in built.last().second)

        // Its own record taken, nothing more is built until an import judged it.
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.rotationAccepted()
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals("waits for the import", 1, built.size)

        // The import judges this device's own record the tip, accepted: newt isn't named.
        h.importJudges(RefreshRecords.Judged(
            uniffi.pingolin_mobile.RefreshRecordsReport(
                listOf(uniffi.pingolin_mobile.RecordVersionStatus(5, uniffi.pingolin_mobile.RecordVersionState.ACCEPTED, RecordProblem.NONE, emptyList(), 0u, emptyList())),
                null, false, false, emptyList(), emptyList(),
            ),
            againstFreshList = true,
        ))
        h.repository.syncRefreshRecords()
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals(2, built.size)
        assertEquals(RefreshRecordKind.ROTATION, built.last().first)
        assertTrue(built.last().second.none { it.userId == "newt" })
    }

    @Test
    fun `a record the server refuses as invalid settles nothing - the removal and the role change stay owed, and the eject says so in this app's words`() = runTest {
        // A record whose signature doesn't verify under the submitter's current key
        // (signed before a reset on another device) is refused by the server. Surfaced and
        // built again, never done.
        val h = harness(flagged = false)
        assertTrue(h.ownRemovals.add("c-1", "carol", h.statements.now))
        assertTrue(h.ownerRoleChanges.put("c-1", "alice", "admin"))
        val invalid = """{"success":false,"error":"the record doesn't verify","error_code":"key_refresh_record_invalid"}"""
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.error(400, invalid.toResponseBody(null))
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns Response.error(400, invalid.toResponseBody(null))

        val removed = h.repository.removeMember("c-1", "bob")
        h.circle = h.circle.copy(keyRotationNeeded = true)
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals("Couldn't remove them just now.", removed.exceptionOrNull()?.message)
        coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
        assertEquals("both built a record", 2, built.size)
        verify(exactly = 2) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
        assertEquals(setOf("carol"), h.ownRemovals.of("c-1"))
        assertEquals(mapOf("alice" to "admin"), h.ownerRoleChanges.of("c-1"))
    }

    @Test
    fun `after a rotation lands the next waits for an import, and one that names the rejected tip still carries it`() = runTest {
        val h = harness()
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("newt")))
        every { h.cryptoManager.chainRole("c-1", "newt") } returns null
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        val tip = rejected(4, RecordProblem.SIGNER_UNKNOWN, named = listOf("newt"))
        h.repository.recordsJudged["c-1"] = tip
        h.rotationAccepted()
        suspend fun rotate() {
            h.circle = h.circle.copy(keyRotationNeeded = true)
            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()
        }

        rotate()
        assertTrue("carried from the rejected tip", PlannedEntry("newt", RecordMemberState.PENDING, null) in built.last().second)
        rotate()
        assertEquals("its own record not judged here yet: nothing built", 1, built.size)

        // The records read again name that tip still: carried again.
        h.importJudges(tip)
        h.repository.syncRefreshRecords()
        rotate()
        assertEquals(2, built.size)
        assertTrue("carried again", PlannedEntry("newt", RecordMemberState.PENDING, null) in built.last().second)
    }

    @Test
    fun `while this user's removals or role changes can't be read, a recorded rotation, an eject, a removal and a role change wait`() = runTest {
        for (unreadable in listOf("removals", "role changes")) {
            built.clear()
            val h = harness()
            val sent = h.rotationAccepted()
            val ejected = h.ejectsTaken()
            assertTrue(h.ownRemovals.add("c-1", "carol", h.statements.now))
            assertTrue(h.ownerRoleChanges.put("c-1", "alice", "admin"))
            val prefs = if (unreadable == "removals") h.ownRemovalsPrefs else h.ownerRoleChangesPrefs
            val stored = prefs.data.toMap()
            prefs.damaged = true

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()
            val removed = h.repository.removeMember("c-1", "bob")

            assertTrue(unreadable, built.isEmpty())
            assertFalse(unreadable, sent.isCaptured)
            assertFalse(unreadable, ejected.isCaptured)
            assertEquals(unreadable, "Couldn't remove them just now.", removed.exceptionOrNull()?.message)
            coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
            if (unreadable == "removals") {
                assertEquals("Couldn't remove them just now.", h.repository.revokeMemberAccess("c-1", "bob").exceptionOrNull()?.message)
                coVerify(exactly = 0) { h.tracksApi.revokeMemberAccess(any(), any()) }
            } else {
                assertEquals(CircleRepository.PROMOTION_NOT_DONE, h.repository.updateMemberRole("c-1", "bob", "member").exceptionOrNull()?.message)
                coVerify(exactly = 0) { h.tracksApi.updateMemberRole(any(), any(), any()) }
            }
            assertEquals("nothing written over them ($unreadable)", stored, prefs.data)

            // Once they read, the eject names what was owed: carol removed, alice's role.
            prefs.damaged = false
            assertTrue(unreadable, h.repository.removeMember("c-1", "bob").isSuccess)
            val entries = built.single().second
            assertTrue(unreadable, PlannedEntry("carol", RecordMemberState.REMOVED, null) in entries)
            assertTrue(unreadable, PlannedEntry("bob", RecordMemberState.REMOVED, null) in entries)
            assertTrue(unreadable, entries.any { it.userId == "alice" && it.role == CircleRole.ADMIN })
        }
    }

    @Test
    fun `while the repair budget can't be read no repair is due, and once it reads the repair goes`() = runTest {
        val h = harness(flagged = false)
        h.rejectedHere(rejected(3, RecordProblem.R2_NOT_ALLOWED, "bob"))
        h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        h.repairBudgetPrefs.damaged = true

        assertFalse(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertTrue(built.isEmpty())

        h.repairBudgetPrefs.damaged = false
        assertTrue(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals(RefreshRecordKind.REPAIR, built.single().first)
        assertEquals(setOf(3), h.repairBudget.repaired("c-1"))
    }

    @Test
    fun `a re-invite that ran out unused brings its removal back at the plan, unless they joined with it`() = runTest {
        for (joined in listOf(false, true)) {
            built.clear()
            val h = harness()
            h.rotationAccepted()
            every { h.cryptoManager.chainRole("c-1", "dave") } returns CircleRole.MEMBER
            // dave was removed here; a re-invite of theirs from here cleared it, and ran out.
            assertTrue(h.ownRemovals.add("c-1", "dave", h.statements.now - 10 * 86_400))
            h.ownRemovals.clearedByInvitation("c-1", "dave", "st-dave", h.statements.now - 2 * 86_400)
            if (joined) {
                h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("dave")))
            }

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()

            val entries = built.single().second
            assertEquals("joined: $joined", !joined, PlannedEntry("dave", RecordMemberState.REMOVED, null) in entries)
        }
    }

    @Test
    fun `a record rejected here on a fresh list is repaired by the next version, once`() = runTest {
        val h = harness(flagged = false)
        h.rejectedHere(rejected(3, RecordProblem.R2_NOT_ALLOWED, "bob"))
        val sent = h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(h.repository.rotationDue(h.circle))
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(RefreshRecordKind.REPAIR, built.single().first)
        assertEquals(RefreshRecordPayload("record-hex", "record-sig"), sent.captured.refreshRecord)
        assertEquals(setOf(3), h.repairBudget.repaired("c-1"))
        assertFalse("once per rejected version", h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a misattribution's repair links from below at once, and an R2 refusal's waits for a device holding that version`() = runTest {
        // Nothing honest is sealed under a misattributed version's key.
        for ((problem, repairs) in listOf(RecordProblem.MISATTRIBUTED to true, RecordProblem.R2_NOT_ALLOWED to false)) {
            built.clear()
            val h = harness(flagged = false)
            h.rejectedHere(rejected(3, problem, named = emptyList()))
            // v3's key never came here.
            every { h.cryptoManager.newestHeldVersion("c-1") } returns 2
            h.rotationAccepted()
            h.repository.refreshMembers("c-1")
            advanceUntilIdle()

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()

            assertEquals(problem.name, if (repairs) listOf(RefreshRecordKind.REPAIR) else emptyList(), built.map { it.first })
        }
    }

    /**
     * The builder says the SDK's locked "not yet" for its first [times] calls, then builds as
     * [harness] does.
     */
    private fun TrustHarness.notYetFirst(times: Int, notYet: MobileException = MobileException.InvalidInput("import this circle's records first, then rotate")) {
        var left = times
        every { cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            if (left-- > 0) throw notYet
            val entries = arg<List<PlannedEntry>>(3)
            built += arg<RefreshRecordKind>(2) to entries
            val included = entries.filter { it.state == RecordMemberState.INCLUDED }.map { it.userId }
            RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = 4,
                    encryptedName = EncryptedData("nn", "cc"),
                    memberKeys = included.map { MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it")) },
                    keyChainLink = KeyChainLinkData(4, 3, "ek", "nk"),
                ),
                SignedRefreshRecord("record-hex", "record-sig"),
            )
        }
    }

    @Test
    fun `the SDK's "not yet" is no failure - nothing is built or sent, there is no back-off, and the next pass builds`() = runTest {
        // Every site that carries the SDK's locked words, whatever the error case.
        for (notYet in listOf<MobileException>(
            MobileException.InvalidInput("import this circle's records first, then rotate"),
            MobileException.KeyNotFound("import this circle's records first"),
        )) {
            built.clear()
            val h = harness()
            val sent = h.rotationAccepted()
            h.notYetFirst(1, notYet)

            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()

            assertFalse(notYet.javaClass.simpleName, sent.isCaptured)
            assertTrue(built.isEmpty())
            verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
            assertEquals("noted once, until it builds", setOf("c-1"), h.repository.notImportedYetNoted)

            // No back-off: the very next pass builds.
            h.repository.autoRotateIfNeeded("c-1")
            advanceUntilIdle()
            assertEquals(1, built.size)
            assertTrue(sent.isCaptured)
            assertTrue(h.repository.notImportedYetNoted.isEmpty())
        }
    }

    @Test
    fun `"not yet" is never answered with the claim's retry`() = runTest {
        // A claimant: its builder's refusal would be retried without the claim; "not yet" isn't.
        val h = harness()
        every { h.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        h.notYetFirst(2)
        val sent = h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) }
        assertFalse(sent.isCaptured)

        // The claim refused (its owner's departure doesn't count yet), and its retry told "not yet": no failure either.
        built.clear()
        val again = harness()
        every { again.cryptoManager.chainRole("c-1", "me") } returns CircleRole.ADMIN
        var calls = 0
        every { again.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            calls++
            if (calls == 1) throw MobileException.InvalidInput("the plan needs a right this device lacks (Role)")
            throw MobileException.KeyNotFound("import this circle's records first")
        }
        again.rotationAccepted()
        again.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals(2, calls)
        assertEquals(setOf("c-1"), again.repository.notImportedYetNoted)
        verify(exactly = 0) { again.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a repair told "not yet" under the records lock lets it go, spends nothing and builds at the next pass`() = runTest {
        val h = harness(flagged = false)
        h.rejectedHere(rejected(3, RecordProblem.MISATTRIBUTED, named = emptyList()))
        every { h.cryptoManager.newestHeldVersion("c-1") } returns 2
        h.rotationAccepted()
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        h.notYetFirst(1)

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertTrue(built.isEmpty())
        assertFalse("let go", h.repository.recordsMutex.isLocked)
        assertEquals(emptySet<Int>(), h.repairBudget.repaired("c-1"))

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals(listOf(RefreshRecordKind.REPAIR), built.map { it.first })
        assertEquals(setOf(3), h.repairBudget.repaired("c-1"))
    }

    @Test
    fun `a refusal that isn't the locked "not yet" still backs off`() = runTest {
        val h = harness()
        val sent = h.rotationAccepted()
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("this circle's chain forked: it waits")

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) }
        assertFalse(sent.isCaptured)
        assertTrue(h.repository.notImportedYetNoted.isEmpty())
    }

    @Test
    fun `an eject told "not yet" reads the records once and builds once more, and otherwise isn't done, refused as not current`() = runTest {
        for (times in listOf(1, 2)) {
            built.clear()
            val h = harness(flagged = false)
            val sent = h.ejectsTaken()
            h.notYetFirst(times)

            val ejected = h.repository.ejectMember("c-1", "bob")

            verify(exactly = 2) { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) }
            coVerify(atLeast = 1) { h.tracksApi.refreshRecords(any()) }
            if (times == 1) {
                assertTrue(ejected.isSuccess)
                assertTrue(sent.isCaptured)
            } else {
                assertTrue(ejected.exceptionOrNull() is KeyNotCurrentException)
                assertFalse(sent.isCaptured)
                verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
            }
        }
    }

    @Test
    fun `a rejection on a list read before the records waits for one read after them`() = runTest {
        val h = harness(flagged = false)
        h.rejectedHere(rejected(3, RecordProblem.R2_NOT_ALLOWED, "bob").copy(againstFreshList = false))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertFalse(h.repository.rotationDue(h.circle))
    }

    @Test
    fun `R1's refusals are never repaired, and a plain member repairs only a flagged circle`() = runTest {
        val h = harness(flagged = false)
        h.rejectedHere(rejected(3, RecordProblem.SIGNER_UNKNOWN))
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertFalse(h.repository.rotationDue(h.circle))

        val member = harness(selfRole = "member", flagged = false)
        member.serverLists(listOf(member.rosterMember("owner", role = "owner"), member.rosterMember("me"), member.rosterMember("alice")))
        member.earlierSeed("me", "owner", "alice")
        member.rejectedHere(rejected(3, RecordProblem.R2_UNMATCHED_ENTRY))
        member.repository.refreshMembers("c-1")
        advanceUntilIdle()
        assertFalse(member.repository.rotationDue(member.circle))

        // Flagged, any member may rotate, and this one's rotation is the repair.
        member.circle = member.circle.copy(keyRotationNeeded = true)
        member.rotationAccepted()
        member.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()
        assertEquals(RefreshRecordKind.REPAIR, built.last().first)
    }

    @Test
    fun `one repair an hour, and none when the record after this device's repair is rejected for the same member`() = runTest {
        val h = harness(flagged = false)
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        h.repairBudget.spend("c-1", 3, listOf("bob"), System.currentTimeMillis())
        h.rejectedHere(rejected(5, RecordProblem.R2_NOT_ALLOWED, "carol"))
        assertFalse("an hour between repairs", h.repository.rotationDue(h.circle))

        h.repairBudget.spend("c-1", 3, listOf("bob"), System.currentTimeMillis() - RefreshRecords.REPAIR_INTERVAL_MS)
        assertTrue(h.repository.rotationDue(h.circle))

        h.rejectedHere(rejected(5, RecordProblem.R2_NOT_ALLOWED, "BOB"))
        assertFalse("the same member again: it waits", h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a repair records what its version was rejected for, the tip's names the chain doesn't count here too, and the stop compares them`() = runTest {
        val h = harness(flagged = false)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol"), h.rosterMember("newt")))
        every { h.cryptoManager.chainRole("c-1", "newt") } returns null
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 2)
        h.repository.refreshMembers("c-1")
        advanceUntilIdle()
        // v3 rejected here naming newt, whom the chain doesn't count, and alice, whom it does;
        // the eligibility check refused nobody.
        h.rejectedHere(rejected(3, RecordProblem.R2_UNMATCHED_ENTRY, named = listOf("newt", "alice")))
        h.rotationAccepted()

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(RefreshRecordKind.REPAIR, built.single().first)
        assertEquals(setOf("newt"), h.repairBudget.lastRepairedFor("c-1"))

        // An hour on, its successor rejected for newt again: it stops and waits.
        h.repairBudget.spend("c-1", 3, listOf("newt"), System.currentTimeMillis() - RefreshRecords.REPAIR_INTERVAL_MS)
        h.rejectedHere(rejected(4, RecordProblem.R2_UNMATCHED_ENTRY, named = listOf("newt")))
        assertFalse("the same member again: it waits", h.repository.rotationDue(h.circle))
    }

    @Test
    fun `a delivery on a circle with records goes only to whom E_deliver allows, with It's them passed on`() = runTest {
        val h = harness(flagged = false)
        // bob's keys changed and this device's person said "It's them".
        h.personConfirmedKeys.confirm("c-1", "bob", TrustHarness.sharing("bob"), TrustHarness.signing("bob"))
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", keyVersion = 2), h.rosterMember("bob", keyVersion = 2)))
        val asked = slot<List<uniffi.pingolin_mobile.RecordMember>>()
        every { h.cryptoManager.refreshEligibility("c-1", capture(asked), EligibilityPurpose.DELIVER) } answers {
            asked.captured.map { MemberEligibility(it.userId, it.userId == "bob", null, false, false, false, null, false) }
        }
        val delivered = h.deliveriesAccepted()

        assertEquals(1, h.repository.deliverOwedAccess("c-1"))

        assertEquals(setOf("bob"), delivered.keys)
        assertTrue(asked.captured.single { it.userId == "bob" }.personConfirmedKeys)
        assertFalse(asked.captured.single { it.userId == "alice" }.personConfirmedKeys)
    }

    @Test
    fun `a delivery on a circle with records hands nothing over while E can't be told`() = runTest {
        val h = harness(flagged = false)
        // Without records, the creator's exemption would hand alice the key:
        // this device created the circle.
        h.approvals.markCreatedHere("c-1")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice", keyVersion = 2)))
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.DELIVER) } throws
            MobileException.InvalidInput("records not imported in this process")
        val delivered = h.deliveriesAccepted()

        assertEquals(0, h.repository.deliverOwedAccess("c-1"))
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun `a lost race drops this device's own rotation and record`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns
            Response.success(ApiResponse(success = true, data = RotateKeysResponse(newKeyVersion = 4, alreadyRotated = true), error = null))

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `a record the server finds stale drops the rotation, and the records are read again`() = runTest {
        val h = harness()
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns Response.error(
            409, """{"success":false,"error_code":"key_refresh_record_stale"}""".toResponseBody(null),
        )

        h.repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
        coVerify(atLeast = 1) { h.tracksApi.refreshRecords(any()) }
    }
}
