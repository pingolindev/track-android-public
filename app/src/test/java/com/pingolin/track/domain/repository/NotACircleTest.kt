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

import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.ReceivedInviteResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedCircleHead
import com.pingolin.track.util.AccountIds
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A "circle" named by the personal key's slot: the nil UUID in any spelling, or "self". A hostile
 * server lists it flagged, owed a delivery, with a key copy riding on it, serves copies and
 * records for it, and invites to it. Nothing reaches the SDK for it: no copy is stored (least of
 * all into the personal slot), nothing is wrapped, built, linked, latched or derived, and nothing
 * goes out. A row an earlier build stored for it goes, and the personal key's slots are never
 * touched. Through the real decoder and CircleRepository; the SDK is mocked, and what matters is
 * what the app asks of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotACircleTest {

    /** The circle, a UUID the decoder takes (hex letters, so its upper case differs). */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"

    @After
    fun tearDown() = clearAllMocks()

    /** The personal key's slot in every spelling the SDK takes, and this app's slot name. */
    private val slots = listOf(
        "00000000-0000-0000-0000-000000000000", "00000000000000000000000000000000",
        "{00000000-0000-0000-0000-000000000000}", "urn:uuid:00000000-0000-0000-0000-000000000000", "self", "SELF",
    )

    private val ann = "a11ce000-0000-4000-8000-00000000000a"

    /** What the refresh's sweep starts, run to its end by [settle]. */
    private val sweeps = SupervisorJob()

    private fun io.mockk.MockKMatcherScope.slot() = match<String> { AccountIds.isPersonalSlot(it) }

    /** [id] as a hostile server lists it: flagged, owed a delivery, owned here, a copy riding on it. */
    private fun hostile(id: String, owner: Boolean = true, flagged: Boolean = true) = CircleResponse(
        id = id,
        encryptedName = EncryptedPayload(nonce = "nn", ciphertext = "nc"),
        encryptedKey = EncryptedPayload(nonce = "kn", ciphertext = "kc"),
        ephemeralPublicKey = "eph",
        memberCount = 2,
        isOwner = owner,
        dayBucket = 20000,
        keyRotationNeeded = flagged,
        keyVersion = 2,
        senderUserId = ann,
        wrapSignature = "sig",
        deliveryNeeded = flagged,
        nameKeyVersion = 2,
    )

    private fun bundle(id: String) = CircleKeyBundleResponse(
        circleId = id,
        encryptedCircleKey = EncryptedPayload("kn", "kc"),
        ephemeralPublicKey = "eph",
        keyVersion = 2,
        senderUserId = ann,
        recipientUserId = "me",
        wrapSignature = "sig",
    )

    private fun invitation(id: String) = ReceivedInviteResponse(
        id = "i-$id", circleId = id, invitedBy = ann, inviteType = "account", createdDayBucket = 1, expiresDayBucket = 8,
        hasEncryptedKey = true,
    )

    /**
     * A member's phone holding c-1 at v2 and, from an earlier build, a row for the slot itself. The
     * server lists c-1 at v3 (so the refresh imports keys and records) and the slot in every spelling:
     * the first three flagged for a new key, the rest owed a delivery. [decoder]: the replies come
     * through the app's decoder, as they always do; without it, the sinks must refuse on their own.
     */
    private fun TestScope.harness(decoder: Boolean = true) = TrustHarness(selfRole = "member", canonicalIds = decoder, circleId = fam).also { h ->
        h.repository.deliveryScope = CoroutineScope(coroutineContext + sweeps)
        h.repository.catchUpPausesMs = emptyList()
        h.earlierSeed("me", ann)
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember(ann)))
        h.circle = h.circle.copy(keyVersion = 2, keyRotationNeeded = false, isOwner = false)
        val stored = listOf(h.circle, h.circle.copy(id = slots[0], name = "Family"), h.circle.copy(id = "self", name = "Family"))
        coEvery { h.circleDao.getAll() } returns stored
        coEvery { h.circleDao.insertAll(any()) } just Runs
        coEvery { h.circleDao.deleteById(any()) } just Runs
        every { h.cryptoManager.newestHeldVersion(any()) } returns 3
        // The personal key is held in its slots, as on every phone.
        every { h.cryptoManager.hasCircleKey(slot()) } returns true
        every { h.cryptoManager.getActiveVersion(slot()) } returns 1
        coEvery { h.tracksApi.getCircles() } returns Response.success(
            ApiResponse(
                true,
                PaginatedResponse(
                    items = slots.mapIndexed { i, id -> if (i < 3) hostile(id) else hostile(id).copy(keyRotationNeeded = false) } +
                        hostile(fam, owner = false, flagged = false).copy(keyVersion = 3),
                    hasMore = false,
                ),
                null,
            ),
        )
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(true, slots.map { bundle(it) }, null))
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(
            ApiResponse(
                true,
                RefreshRecordsResponse(
                    (slots + fam).map { CircleRefreshRecords(it, emptyList(), listOf(ServedCircleHead(ann, "h", "s")), complete = true) },
                    more = false,
                ),
                null,
            ),
        )
        coEvery { h.tracksApi.getReceivedInvites() } returns Response.success(ApiResponse(true, (slots + fam).map { invitation(it) }, null))
    }

    private suspend fun TestScope.settle() {
        while (true) {
            advanceUntilIdle()
            val running = sweeps.children.filter { it.isActive }.toList()
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /** Nothing reached the SDK for the slot, and nothing went out for it. */
    private fun TrustHarness.nothingForTheSlot() {
        verify(exactly = 0) { cryptoManager.storeCircleKey(slot(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("no first copy for it", firstCopies.isEmpty())
        verify(exactly = 0) { cryptoManager.wrapCircleKeyForMemberFull(slot(), any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.prepareKeyRotation(slot(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.prepareRecordedRotation(slot(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.createKeyChainLink(slot()) }
        verify(exactly = 0) { cryptoManager.noteCircleHasRecords(slot()) }
        verify(exactly = 0) { cryptoManager.importRefreshRecords(slot(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.deriveUserPseudonymsAllVersions(slot(), any()) }
        verify(exactly = 0) { cryptoManager.pinMemberKeys(slot(), any(), any(), any()) }
        verify(exactly = 0) { cryptoManager.forgetCircle(slot()) }
        verify(exactly = 0) { cryptoManager.discardUnconfirmedRotation(slot()) }
        coVerify(exactly = 0) { tracksApi.deliverAccess(slot(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.rotateCircleKeys(slot(), any()) }
        coVerify(exactly = 0) { tracksApi.getCircleMembers(slot(), any(), any()) }
        coVerify(exactly = 0) { tracksApi.getMemberPublicKeys(slot()) }
    }

    @Test
    fun `a hostile list, copies and records naming the personal key's slot reach nothing, and the rest stands`() = runTest {
        val h = harness()

        assertTrue(h.repository.refreshCircles().isSuccess)
        settle()

        h.nothingForTheSlot()
        val written = mutableListOf<List<CircleEntity>>()
        coVerify { h.circleDao.insertAll(capture(written)) }
        assertEquals("only the circle is stored", listOf(fam), written.flatten().map { it.id }.distinct())
        verify(atLeast = 1) { h.cryptoManager.noteCircleHasRecords(fam) }
        assertTrue("the decoder counted the list's", "circle list: ${slots.size} entr(ies) naming the personal key's slot as a circle; refused" in h.refusedIds)
        assertTrue("and the copies'", "circle keys: ${slots.size} entr(ies) naming the personal key's slot as a circle; refused" in h.refusedIds)
        assertTrue("and the records'", "refresh records: ${slots.size} entr(ies) naming the personal key's slot as a circle; refused" in h.refusedIds)

        assertEquals("and invitations to it", listOf(fam), h.repository.fetchReceivedInvites().getOrThrow().map { it.circleId })
    }

    @Test
    fun `were the decoder passed, every sink still refuses the slot - no copy enters the personal slot, and nothing is wrapped, built, latched or derived`() = runTest {
        // The riding wrap of a listed nil circle, which would otherwise land in the personal
        // slot, never reaches the store, even with nothing refused on the way in.
        val h = harness(decoder = false)

        h.repository.refreshCircles()
        settle()
        h.repository.refreshCircles()
        settle()

        h.nothingForTheSlot()
        verify(exactly = 0) { h.cryptoManager.storeFirstCircleKey(slot(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a row an earlier build stored for the slot goes, with its members, and the personal key's slots are never touched`() = runTest {
        // Only the fake circle's own rows. Nothing is asked of the SDK at all, so both
        // personal slots stay byte for byte as they were.
        val h = harness()
        coEvery { h.tracksApi.getCircles() } returns Response.success(ApiResponse(true, PaginatedResponse(items = emptyList<CircleResponse>(), hasMore = false), null))
        io.mockk.clearMocks(h.cryptoManager, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)

        assertTrue(h.repository.refreshCircles().isSuccess)
        settle()

        coVerify(exactly = 1) { h.circleDao.deleteById(slots[0]) }
        coVerify(exactly = 1) { h.circleDao.deleteById("self") }
        coVerify(exactly = 1) { h.circleMemberDao.deleteByCircle(slots[0]) }
        coVerify(exactly = 1) { h.circleMemberDao.deleteByCircle("self") }
        coVerify(exactly = 0) { h.circleDao.deleteById(fam) }
        io.mockk.confirmVerified(h.cryptoManager)
    }

    @Test
    fun `a row an earlier build stored under a second spelling of a circle's id goes quietly, and the circle and its keys stay`() = runTest {
        // Room only, a count logged; the SDK's keys under that spelling are the SDK's own
        // to fold. A row whose id is no UUID at all is keyed as itself, and stays.
        val h = harness()
        val respelled = listOf(fam.uppercase(), fam.replace("-", ""), "{$fam}")
        coEvery { h.circleDao.getAll() } returns listOf(h.circle) + (respelled + "c-local").map { h.circle.copy(id = it) }
        coEvery { h.tracksApi.getCircles() } returns Response.success(ApiResponse(true, PaginatedResponse(items = emptyList<CircleResponse>(), hasMore = false), null))
        io.mockk.clearMocks(h.cryptoManager, answers = false, recordedCalls = true, childMocks = false, verificationMarks = true, exclusionRules = false)

        assertTrue(h.repository.refreshCircles().isSuccess)
        settle()

        for (id in respelled) {
            coVerify(exactly = 1) { h.circleDao.deleteById(id) }
            coVerify(exactly = 1) { h.circleMemberDao.deleteByCircle(id) }
        }
        coVerify(exactly = 0) { h.circleDao.deleteById(fam) }
        coVerify(exactly = 0) { h.circleDao.deleteById("c-local") }
        io.mockk.confirmVerified(h.cryptoManager)
    }

    @Test
    fun `each way in for a circle refuses the slot, and nothing is wrapped, built or sent`() = runTest {
        val h = harness()
        coEvery { h.circleDao.getById(any()) } answers { h.circle.copy(id = firstArg(), keyRotationNeeded = true, isOwner = true) }

        for (slot in slots) {
            assertEquals(slot, 0, h.repository.deliverOwedAccess(slot))
            h.repository.autoRotateIfNeeded(slot)
            assertTrue(slot, h.repository.refreshMembers(slot).isFailure)
            assertTrue(slot, h.repository.inviteToCircle(slot, "dan@x.test").isFailure)
            assertTrue(slot, h.repository.ejectMember(slot, ann).isFailure)
            assertTrue(slot, h.repository.transferOwnership(slot, ann).isFailure)
            assertTrue(slot, h.repository.acceptInvite("i-$slot", slot, "v2").isFailure)
            assertTrue(
                slot,
                h.repository.renewInvitation(
                    InviteRenewalQuestion(slot, "Family", "inv-1", "st-1", "dan@x.test", ann, "sx", "ss", null, MembershipStatements.Signed("s", "g")),
                ).isFailure,
            )
            settle()
        }

        h.nothingForTheSlot()
        coVerify(exactly = 0) { h.tracksApi.lookupPublicKey(any()) }
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(slot(), any()) }
        coVerify(exactly = 0) { h.tracksApi.acceptInvite(any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(slot(), any()) }
        coVerify(exactly = 0) { h.tracksApi.transferOwnership(slot(), any()) }
    }
}
