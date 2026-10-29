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
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.OwnWrapSignatureResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.util.AccountIds
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.RecordMemberState

/**
 * Through the real CircleRepository with the server's replies decoded as the app decodes them
 * (CanonicalIdsTrackApi): the server spells this account's own entry any way the SDK reads, and
 * it is still this account's own, never a stranger pinned under this account's id. The SDK wraps
 * a rotation's entry to this device's own key only when it names exactly the sender, so the
 * entry must carry the canonical id. An id that is no UUID never reaches the SDK at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalIdsTrustTest {

    /** The circle, a UUID the decoder takes (hex letters, so its upper case differs). */
    private val fam = "fa0c1e00-0000-4000-8000-0000000000fa"

    /** Another circle, whose copy names a sender that is no UUID. */
    private val eves = "e0e5c1e0-0000-4000-8000-0000000000e5"

    @After
    fun tearDown() = clearAllMocks()

    private val me = "0e000000-0000-4000-8000-0000000000e1"
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val owner = "0a0e0000-0000-4000-8000-0000000000a0"

    /** This account's id as the server may spell it: every form the SDK's parser takes but the canonical one. */
    private val otherSpellings = listOf(me.uppercase(), me.replace("-", ""), "{$me}", "urn:uuid:$me")

    private fun TestScope.harness(recorded: Boolean, role: String = "owner") = TrustHarness(self = me, selfRole = role, canonicalIds = true, circleId = fam).also { h ->
        h.repository.deliveryScope = this
        // A plain member's device, in a circle whose owner is listed too.
        if (role == "owner") h.earlierSeed(me, dan) else h.earlierSeed(me, owner, dan)
        if (recorded) h.recordedCircle()
        coEvery { h.tracksApi.refreshRecords(any()) } returns
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(emptyList(), more = false), error = null))
    }

    @Test
    fun `the list names this account in another spelling with the server's key - no pin for it, and the rotation's own entry is this account's`() = runTest {
        // (this device's role, records): a plain member's rotation without a record, an owner's first record, a recorded rotation.
        for ((role, recorded) in listOf("member" to false, "owner" to false, "owner" to true)) {
            for (spelling in otherSpellings) {
                val h = harness(recorded, role)
                // The server's key under this account's entry, in the member list and the member keys alike.
                val listedOwner = if (role == "owner") emptyList() else listOf(h.rosterMember(owner, role = "owner"))
                h.serverLists(listedOwner + h.rosterMember(spelling, role = role, keysOf = "server") + h.rosterMember(dan))
                val sent = h.rotationAccepted()

                h.repository.refreshMembers(fam)
                advanceUntilIdle()
                h.repository.autoRotateIfNeeded(fam)
                advanceUntilIdle()

                val case = "role=$role recorded=$recorded $spelling"
                val everyone = (if (role == "owner") emptyList() else listOf(owner)) + me + dan
                verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), match { AccountIds.same(it, me) }, any(), any()) }
                assertTrue(case, h.pins.sharing.keys.none { AccountIds.same(it, me) })
                assertTrue(case, sent.isCaptured)
                assertEquals(case, everyone, sent.captured.memberKeys.map { it.userId })
                if (role == "owner") {
                    // A record's builder takes this account's entry with this device's own keys (RefreshRecords.recordMembers).
                    verify {
                        h.cryptoManager.prepareRecordedRotation(fam, me, any(), match { entries ->
                            entries.map { it.userId to it.state } == everyone.map { it to RecordMemberState.INCLUDED }
                        }, match { members -> members.single { it.userId == me }.sharingPublicKeyHex == sharing(me) }, any(), any())
                    }
                } else {
                    // The SDK takes the entry that names the sender as its own, and wraps it to its own key.
                    verify { h.cryptoManager.prepareKeyRotation(fam, me, everyone, listOf(sharing(owner), sharing("server"), sharing(dan)), any(), any()) }
                }
                // Its own row, in the list's role: this device may rotate, and shows it.
                assertEquals(case, everyone, h.storedRows.map { it.userId })
                assertEquals(case, emptyList<String>(), h.refusedIds)
            }
        }
    }

    @Test
    fun `an entry whose id is no UUID never reaches the SDK - no pin, no copy, no number, and the rest go on`() = runTest {
        for (notId in listOf("user-eve", " $dan", "{${dan.replace("-", "")}}", "URN:UUID:$dan")) {
            val h = harness(recorded = false)
            // Signed in by this account: were it taken, it would be confirmed and pinned.
            val invite = h.invitation(notId, by = me)
            h.serverLists(listOf(h.rosterMember(me, role = "owner"), h.rosterMember(dan), h.rosterMember(notId, invite)))
            val sent = h.rotationAccepted()
            h.deliveriesAccepted()

            h.repository.refreshMembers(fam)
            advanceUntilIdle()
            h.repository.deliverOwedAccess(fam)
            h.repository.autoRotateIfNeeded(fam)
            advanceUntilIdle()

            verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), notId, any(), any()) }
            verify(exactly = 0) { h.cryptoManager.approveMemberKeyChange(any(), notId, any(), any()) }
            verify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull(any(), any(), any(), notId, any()) }
            verify(exactly = 0) { h.cryptoManager.computeSafetyNumber(any(), notId, any(), any()) }
            verify(exactly = 0) { h.cryptoManager.retainMemberPins(any(), match { notId in it }) }
            verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), match { notId in it }, any(), any(), any()) }
            assertEquals(notId, listOf(me, dan), sent.captured.memberKeys.map { it.userId })
            assertTrue(notId, sent.captured.pendingMembers.orEmpty().none { it == notId })
            assertEquals(notId, listOf(me, dan), h.storedRows.map { it.userId })
            assertTrue(notId, h.refusedIds.contains("member list: 1 account id(s) that aren't UUIDs; their entries refused"))
            assertTrue(notId, h.refusedIds.contains("member keys: 1 account id(s) that aren't UUIDs; their entries refused"))
        }
    }

    @Test
    fun `a copy the server says this account sent itself, in any spelling, is this account's own - and one from no UUID is never stored`() = runTest {
        for (spelling in otherSpellings) {
            val h = harness(recorded = false)
            coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, error = null, data = listOf(
                CircleKeyBundleResponse(fam, EncryptedPayload("n", "c"), "e", 3, senderUserId = spelling, recipientUserId = spelling),
                CircleKeyBundleResponse(eves, EncryptedPayload("n2", "c2"), "e2", 3, senderUserId = "user-eve"),
            )))
            coEvery { h.tracksApi.signOwnWrap(any(), any()) } returns
                Response.success(ApiResponse(success = true, data = OwnWrapSignatureResponse(signed = true), error = null))
            every { h.cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } returns "own-sig"

            h.repository.importCircleKeys()

            verify { h.cryptoManager.storeCircleKey(fam, "nc", "e", 3, me, me, null, null) }
            // An unsigned copy of its own, signed by its own device.
            coVerify { h.tracksApi.signOwnWrap(fam, any()) }
            verify(exactly = 0) { h.cryptoManager.storeCircleKey(eves, any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(spelling, listOf("circle keys: 1 account id(s) that aren't UUIDs; their entries refused"), h.refusedIds)
        }
    }
}
