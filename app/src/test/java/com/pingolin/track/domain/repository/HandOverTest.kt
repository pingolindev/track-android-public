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
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.util.HandOvers
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
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
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMemberState
import uniffi.pingolin_mobile.RecordedRotationBundle
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * The owner's hand-over on a circle with refresh records: the record first, naming the new owner
 * and this account a member, then the server's transfer at once; a transfer with no answer is
 * kept and sent again, keyed to the circle, the record's version and its member; one refused for
 * good is dropped. Never a transfer first on a recorded circle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOverTest {

    @After
    fun tearDown() = clearAllMocks()

    private val built = mutableListOf<List<PlannedEntry>>()

    private fun TestScope.harness(recorded: Boolean = true, chainRoleOfMe: CircleRole = CircleRole.OWNER) = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = this
        h.earlierSeed("me", "alice", "bob")
        h.circle = h.circle.copy(keyRotationNeeded = false, isOwner = true)
        // carol: listed, nothing signed about her here, so not confirmed.
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("bob"), h.rosterMember("carol")))
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns recorded
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 3)
        every { h.cryptoManager.chainRole("c-1", any()) } answers {
            when (arg<String>(1)) { "me" -> chainRoleOfMe; "alice", "bob", "carol" -> CircleRole.MEMBER; else -> null }
        }
        // The eligibility check (INCLUDE) allows alice and bob.
        every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
            arg<List<uniffi.pingolin_mobile.RecordMember>>(1).map { m ->
                MemberEligibility(m.userId, m.userId == "alice" || m.userId == "bob", null, false, false, false, null, false)
            }
        }
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            val entries = arg<List<PlannedEntry>>(3)
            built += entries
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
        h.rotationAccepted()
    }

    private fun TrustHarness.transferAnswers(vararg answers: Response<ApiResponse<Unit>>) {
        coEvery { tracksApi.transferOwnership("c-1", any()) } returnsMany answers.toList()
    }

    private val taken: Response<ApiResponse<Unit>> get() = Response.success(ApiResponse(success = true, data = Unit, error = null))

    private fun refused(status: Int): Response<ApiResponse<Unit>> = Response.error(status, "{}".toResponseBody(null))

    @Test
    fun `a hand-over that can't be read now hands nothing over`() = runTest {
        val h = harness()
        h.transferAnswers(taken)
        val prefs = com.pingolin.track.testutil.DamagedPrefs()
        h.repository.handOvers = com.pingolin.track.util.SecurePrefsHandOvers(io.mockk.mockk(), { "me" }, { prefs })
        prefs.damaged = true

        val result = h.repository.transferOwnership("c-1", "alice")

        assertEquals(CircleRepository.HAND_OVER_NOT_DONE, result.exceptionOrNull()?.message)
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.transferOwnership(any(), any()) }
    }

    @Test
    fun `the owner hands over with a record first, and the server's transfer follows its 200`() = runTest {
        val h = harness()
        h.transferAnswers(taken)

        assertTrue(h.repository.transferOwnership("c-1", "alice").isSuccess)

        val entries = built.single()
        assertTrue(PlannedEntry("me", RecordMemberState.INCLUDED, CircleRole.MEMBER) in entries)
        assertTrue(PlannedEntry("alice", RecordMemberState.INCLUDED, CircleRole.OWNER) in entries)
        coVerifyOrder {
            h.tracksApi.rotateCircleKeys("c-1", any())
            h.tracksApi.transferOwnership("c-1", match { it.newOwnerId == "alice" })
        }
        assertNull("done, so forgotten", h.handOvers.of("c-1"))
    }

    @Test
    fun `a name that doesn't open here never stops a hand-over, and the new key carries none it may not`() = runTest {
        // No circle list read in this process, and the row showing the placeholder.
        val h = harness()
        h.circle = h.circle.copy(name = LockedCircle.PLACEHOLDER_NAME)
        h.transferAnswers(taken)
        val sent = h.rotationAccepted()

        assertTrue(h.repository.transferOwnership("c-1", "alice").isSuccess)

        verify { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), true, "") }
        assertNull("rotate-keys keeps the circle's", sent.captured.encryptedName)
        coVerify { h.tracksApi.transferOwnership("c-1", match { it.newOwnerId == "alice" }) }
    }

    @Test
    fun `a hand-over told "not yet" reads the records once and builds once more, and otherwise isn't done, with its own line`() = runTest {
        // The hand-over keeps HAND_OVER_NOT_DONE.
        for (times in listOf(1, 2)) {
            built.clear()
            val h = harness()
            h.transferAnswers(taken)
            var left = times
            every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
                if (left-- > 0) throw uniffi.pingolin_mobile.MobileException.InvalidInput("import this circle's records first, then rotate")
                val entries = arg<List<PlannedEntry>>(3)
                built += entries
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

            val handed = h.repository.transferOwnership("c-1", "alice")

            coVerify(atLeast = 1) { h.tracksApi.refreshRecords(any()) }
            if (times == 1) {
                assertTrue(handed.isSuccess)
                coVerify(exactly = 1) { h.tracksApi.transferOwnership("c-1", any()) }
            } else {
                assertEquals(CircleRepository.HAND_OVER_NOT_DONE, handed.exceptionOrNull()?.message)
                coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
                coVerify(exactly = 0) { h.tracksApi.transferOwnership(any(), any()) }
                verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
            }
        }
    }

    @Test
    fun `a transfer with no answer is kept, sent again after a circle refresh, and never to anyone else`() = runTest {
        val h = harness()
        h.transferAnswers(refused(503), refused(503), taken)

        assertEquals(CircleRepository.HAND_OVER_NOT_DONE, h.repository.transferOwnership("c-1", "alice").exceptionOrNull()?.message)
        assertEquals(HandOvers.HandOver(4, "alice"), h.handOvers.of("c-1"))

        // Another member: not while this one's transfer waits.
        assertEquals(CircleRepository.HAND_OVER_NOT_DONE, h.repository.transferOwnership("c-1", "bob").exceptionOrNull()?.message)
        // The person's own try again: the transfer only, no second record.
        assertTrue(h.repository.transferOwnership("c-1", "alice").isFailure)
        h.repository.retryHandOvers()

        assertNull(h.handOvers.of("c-1"))
        assertEquals(1, built.size)
        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
        coVerify(exactly = 3) { h.tracksApi.transferOwnership("c-1", match { it.newOwnerId == "alice" }) }
        coVerify(exactly = 0) { h.tracksApi.transferOwnership("c-1", match { it.newOwnerId == "bob" }) }
    }

    @Test
    fun `a transfer refused for good is dropped, and not sent again`() = runTest {
        val h = harness()
        h.transferAnswers(refused(400))

        assertTrue(h.repository.transferOwnership("c-1", "alice").isFailure)
        assertNull(h.handOvers.of("c-1"))
        h.repository.retryHandOvers()

        coVerify(exactly = 1) { h.tracksApi.transferOwnership("c-1", any()) }
    }

    @Test
    fun `no record, and no transfer, for a member who wouldn't get the new key`() = runTest {
        for (target in listOf("carol", "bob")) {
            // carol: not confirmed here, so the plan doesn't wrap to her. bob: the SDK's
            // eligibility check says no.
            built.clear()
            val h = harness()
            if (target == "bob") {
                every { h.cryptoManager.refreshEligibility("c-1", any(), EligibilityPurpose.INCLUDE) } answers {
                    arg<List<uniffi.pingolin_mobile.RecordMember>>(1).map { m -> MemberEligibility(m.userId, m.userId == "alice", null, false, false, false, null, false) }
                }
            }
            h.transferAnswers(taken)

            assertEquals(target, CircleRepository.HAND_OVER_NOT_DONE, h.repository.transferOwnership("c-1", target).exceptionOrNull()?.message)
            assertTrue(built.isEmpty())
            coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
            coVerify(exactly = 0) { h.tracksApi.transferOwnership(any(), any()) }
        }
    }

    @Test
    fun `behind, or with records not read here, nothing is handed over and no transfer goes first`() = runTest {
        val behind = harness()
        behind.circleVersion = 4
        behind.repository.catchUpPausesMs = emptyList()
        coEvery { behind.tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        behind.transferAnswers(taken)
        assertTrue(behind.repository.transferOwnership("c-1", "alice").exceptionOrNull() is KeyNotCurrentException)
        coVerify(exactly = 0) { behind.tracksApi.transferOwnership(any(), any()) }

        val unread = harness()
        every { unread.cryptoManager.refreshHeadVersions() } returns emptyMap()
        unread.transferAnswers(taken)
        assertEquals(CircleRepository.HAND_OVER_NOT_DONE, unread.repository.transferOwnership("c-1", "alice").exceptionOrNull()?.message)
        coVerify(exactly = 0) { unread.tracksApi.transferOwnership(any(), any()) }
        assertTrue(built.isEmpty())
    }

    @Test
    fun `the server's transfer alone where there are no records, or the chain's owner is someone else`() = runTest {
        for ((recorded, role) in listOf(false to CircleRole.OWNER, true to CircleRole.ADMIN)) {
            val h = harness(recorded = recorded, chainRoleOfMe = role)
            h.transferAnswers(taken)

            assertTrue(h.repository.transferOwnership("c-1", "alice").isSuccess)
            coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
            coVerify(exactly = 1) { h.tracksApi.transferOwnership("c-1", any()) }
            assertNull(h.handOvers.of("c-1"))
        }
        assertTrue(built.isEmpty())
    }
}
