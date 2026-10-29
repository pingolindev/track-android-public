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

import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleKeyBundleResponse
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.KeyChainLinkResponse
import com.pingolin.track.data.remote.dto.PutHeadRequest
import com.pingolin.track.data.remote.dto.PutHeadResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsRequest
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedCircleHead
import com.pingolin.track.data.remote.dto.ServedRefreshRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleHead
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.MemberHead
import uniffi.pingolin_mobile.RecordMember
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordVersionState
import uniffi.pingolin_mobile.RecordVersionStatus
import uniffi.pingolin_mobile.RefreshRecordsReport
import uniffi.pingolin_mobile.SignedCircleHead
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * After every key import this device reads the circles' refresh records and has the SDK check
 * them against its own judged member list, then publishes the account's head. The SDK is
 * mocked: what matters here is what the app hands it, when, and what it does with the report.
 */
class RefreshRecordsSyncTest {

    private val h = TrustHarness()

    /** What the SDK was asked to import, in order. */
    private data class Imported(
        val circleId: String,
        val records: List<SignedRefreshRecord>,
        val links: List<KeyChainLinkData>,
        val members: List<RecordMember>,
        val rosterComplete: Boolean,
        val ownHead: SignedCircleHead?,
        val memberHeads: List<MemberHead>,
    )

    private val imported = mutableListOf<Imported>()

    /** The SDK's reports, one per import; the last one repeats. */
    private val reports = mutableListOf(report(status(4, RecordVersionState.ACCEPTED)))

    /** What refreshHeadVersions says: 0 until an import in this process, and while forked. */
    private var head = 0

    private var served = CircleRefreshRecords("c-1", listOf(ServedRefreshRecord(4, "r4", "s4")), complete = true)
    private val reads = mutableListOf<Map<String, Int>>()
    private val puts = mutableListOf<PutHeadRequest>()

    private fun status(version: Int, state: RecordVersionState, problem: RecordProblem = RecordProblem.NONE) =
        RecordVersionStatus(version, state, problem, emptyList(), 0u, emptyList())

    private fun report(vararg versions: RecordVersionStatus, forked: Boolean = false, askForFreshCopy: Boolean = false) =
        RefreshRecordsReport(versions.toList(), null, askForFreshCopy, forked, emptyList(), emptyList())

    private fun headOf(user: String) = ServedCircleHead(user, "head-$user", "sig-$user")

    @Before
    fun setUp() {
        // What a roster pass hands over reads the records too: not here, where every
        // import counted is the records check's own.
        h.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() })
        h.earlierSeed("me", "alice")
        h.serverLists(listOf(h.rosterMember("me"), h.rosterMember("alice")))
        every { h.cryptoManager.refreshHeadVersions() } answers { mapOf("c-1" to head) }
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns true
        every { h.cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } answers {
            imported += Imported(arg(0), arg(1), arg(2), arg(3), arg(4), arg(5), arg(6))
            val next = if (reports.size > 1) reports.removeAt(0) else reports.first()
            head = if (next.forked) 0 else next.versions.lastOrNull { it.state == RecordVersionState.ACCEPTED }?.version ?: head
            next
        }
        coEvery { h.tracksApi.refreshRecords(any()) } answers {
            reads += arg<RefreshRecordsRequest>(0).since
            Response.success(ApiResponse(success = true, data = RefreshRecordsResponse(listOf(served), more = false), error = null))
        }
        every { h.cryptoManager.signCircleHead("c-1", "me") } returns SignedCircleHead("my-head", "my-sig")
        // No head served verifies under this account's current key unless a test says so.
        every { h.cryptoManager.verifyCircleHead(any(), any(), any()) } returns null
        coEvery { h.tracksApi.putCircleHead("c-1", any()) } answers {
            puts += arg<PutHeadRequest>(1)
            Response.success(ApiResponse(success = true, data = PutHeadResponse(true), error = null))
        }
        coEvery { h.tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))
    }

    @Test
    fun `with no member list judged yet, the records wait for one, read now, and are judged against it`() = runTest {
        h.repository.syncRefreshRecords()

        coVerify(exactly = 1) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        assertEquals("one import, after the list", 1, imported.size)
        with(imported.single()) {
            assertEquals("c-1", circleId)
            assertTrue(rosterComplete)
            assertEquals(listOf("r4"), records.map { it.recordHex })
            assertEquals(listOf("s4"), records.map { it.signatureHex })
            val byId = members.associateBy { it.userId }
            assertEquals(setOf("me", "alice"), byId.keys)
            // This account with its own keys, alice with the keys pinned for her.
            assertTrue(byId.getValue("me").confirmed)
            assertEquals(TrustHarness.sharing("me"), byId.getValue("me").sharingPublicKeyHex)
            assertEquals(TrustHarness.signing("me"), byId.getValue("me").signingPublicKeyHex)
            assertTrue(byId.getValue("alice").confirmed)
            assertEquals(TrustHarness.sharing("alice"), byId.getValue("alice").sharingPublicKeyHex)
            assertEquals(TrustHarness.signing("alice"), byId.getValue("alice").signingPublicKeyHex)
        }
        assertEquals("read before the list and again after it", listOf(mapOf("c-1" to 0), mapOf("c-1" to 0)), reads)
        assertTrue(h.repository.recordsJudged.getValue("c-1").againstFreshList)
    }

    @Test
    fun `a fresh install confirms the members its account's own head vouches for`() = runTest {
        // Nothing seeded, nothing pinned, nothing signed about alice: without the
        // head she would be hidden here (statement-less).
        val fresh = TrustHarness()
        fresh.repository.deliveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() })
        fresh.serverLists(listOf(fresh.rosterMember("me"), fresh.rosterMember("alice")))
        every { fresh.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 0)
        every { fresh.cryptoManager.circleHasRefreshRecords("c-1") } returns true
        var vouch = listOf("alice")
        every { fresh.cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } answers {
            RefreshRecordsReport(listOf(status(4, RecordVersionState.ACCEPTED)), 4, false, false, vouch, emptyList()).also { vouch = emptyList() }
        }
        coEvery { fresh.tracksApi.refreshRecords(any()) } returns Response.success(
            ApiResponse(success = true, data = RefreshRecordsResponse(listOf(served.copy(heads = listOf(headOf("me")))), more = false), error = null)
        )
        coEvery { fresh.tracksApi.putCircleHead("c-1", any()) } returns
            Response.success(ApiResponse(success = true, data = PutHeadResponse(true), error = null))

        fresh.repository.syncRefreshRecords()

        assertEquals(com.pingolin.track.util.KeyAccessApprovals.Origin.ANCHOR, fresh.origins()["alice"])
        assertTrue("shown", "alice" in fresh.storedRows.map { it.userId })
        assertEquals("pinned with the keys the vouch matched", TrustHarness.sharing("alice"), fresh.pins.sharing["alice"])
    }

    @Test
    fun `every key import is followed by the records check, with the links that import served`() = runTest {
        h.repository.refreshMembers("c-1")
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(
            ApiResponse(
                success = true,
                data = listOf(
                    CircleKeyBundleResponse(
                        circleId = "c-1",
                        encryptedCircleKey = EncryptedPayload("nn", "cc"),
                        ephemeralPublicKey = "eph",
                        keyVersion = 4,
                        keyChain = listOf(KeyChainLinkResponse(4, 3, "ek4", "nk4"), KeyChainLinkResponse(3, 2, "ek3", "nk3")),
                        senderUserId = "alice",
                        wrapSignature = "sig",
                    )
                ),
                error = null,
            )
        )

        h.repository.importCircleKeys()

        assertEquals(1, imported.size)
        assertEquals(listOf(KeyChainLinkData(4, 3, "ek4", "nk4"), KeyChainLinkData(3, 2, "ek3", "nk3")), imported.single().links)
        assertEquals("judged against the list this process already read", false, h.repository.recordsJudged.getValue("c-1").againstFreshList)
    }

    @Test
    fun `a circle with no records is left to the legacy rules, and a head alone ends that for good`() = runTest {
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns false
        served = CircleRefreshRecords("c-1", emptyList(), complete = true)
        // A member list judged already: nothing but the legacy rule keeps the import away.
        h.repository.refreshMembers("c-1")

        h.repository.syncRefreshRecords()

        verify(exactly = 0) { h.cryptoManager.noteCircleHasRecords(any()) }
        assertTrue("nothing imported", imported.isEmpty())
        coVerify(exactly = 1) { h.tracksApi.getCircleMembers(any(), any(), any()) }

        // The server keeps its records back, but a member's head shows they exist.
        served = CircleRefreshRecords("c-1", emptyList(), heads = listOf(headOf("alice")), complete = true)
        h.repository.syncRefreshRecords()

        verify(exactly = 1) { h.cryptoManager.noteCircleHasRecords("c-1") }
        assertTrue("nothing to import", imported.isEmpty())
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `a refusal a later list may settle is judged again against a list read after the records`() = runTest {
        h.repository.refreshMembers("c-1")
        reports.clear()
        reports += report(status(4, RecordVersionState.REJECTED, RecordProblem.R2_UNMATCHED_ENTRY))
        reports += report(status(4, RecordVersionState.ACCEPTED))

        h.repository.syncRefreshRecords()

        assertEquals(2, imported.size)
        coVerify(exactly = 2) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        with(h.repository.recordsJudged.getValue("c-1")) {
            assertTrue(againstFreshList)
            assertEquals(RecordVersionState.ACCEPTED, report.versions.single().state)
        }
    }

    @Test
    fun `a refusal that holds against a fresh list stays, and waits for the next roster pass`() = runTest {
        reports.clear()
        reports += report(status(4, RecordVersionState.REJECTED, RecordProblem.R2_NOT_ALLOWED))

        h.repository.syncRefreshRecords()

        // No list yet: one read, then the list, then the import against it. Not read again.
        assertEquals(1, imported.size)
        coVerify(exactly = 1) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        assertTrue(h.repository.recordsJudged.getValue("c-1").againstFreshList)

        // The next roster pass (a screen opening the circle) judges it again, once, against that list.
        h.repository.refreshMembers("c-1")

        assertEquals(2, imported.size)
        coVerify(exactly = 2) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        assertTrue(h.repository.recordsJudged.getValue("c-1").againstFreshList)
    }

    @Test
    fun `a roster pass of a circle with nothing waiting reads no records`() = runTest {
        h.repository.syncRefreshRecords()
        assertEquals(1, imported.size)
        val readsBefore = reads.size

        h.repository.refreshMembers("c-1")

        assertEquals(readsBefore, reads.size)
        assertEquals(1, imported.size)
    }

    @Test
    fun `a read that fails decides nothing`() = runTest {
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.error(503, "{}".toResponseBody(null))

        h.repository.syncRefreshRecords()

        assertTrue(imported.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.getCircleMembers(any(), any(), any()) }
        assertTrue(puts.isEmpty())
    }

    @Test
    fun `a copy the SDK refused or dropped is asked for again`() = runTest {
        reports.clear()
        reports += report(status(4, RecordVersionState.REFERENCE, RecordProblem.KEY_TAG), askForFreshCopy = true)

        h.repository.syncRefreshRecords()

        coVerify(exactly = 1) { h.tracksApi.requestAccess("c-1") }
    }

    @Test
    fun `this account's head is published after an import when the server has none`() = runTest {
        h.repository.syncRefreshRecords()

        assertEquals(listOf(PutHeadRequest("my-head", "my-sig")), puts)
        assertEquals("the own head goes to the SDK as it was served: none", null, imported.single().ownHead)
    }

    @Test
    fun `after an upgrade the head waits, unpublished, until its record is known here in full`() = runTest {
        // The SDK's signCircleHead refuses until the next import serves the head's
        // record.
        every { h.cryptoManager.signCircleHead("c-1", "me") } throws
            uniffi.pingolin_mobile.MobileException.InvalidInput("the head's record isn't known here in full yet: import this circle's records first")
        h.repository.syncRefreshRecords()
        assertTrue(puts.isEmpty())
        // The locked words, whatever the error case.
        every { h.cryptoManager.signCircleHead("c-1", "me") } throws
            uniffi.pingolin_mobile.MobileException.KeyNotFound("import this circle's records first")
        head = 0
        h.repository.syncRefreshRecords()
        assertTrue(puts.isEmpty())

        // The next import serves it.
        every { h.cryptoManager.signCircleHead("c-1", "me") } returns SignedCircleHead("my-head", "my-sig")
        head = 0
        h.repository.syncRefreshRecords()
        assertEquals(listOf(PutHeadRequest("my-head", "my-sig")), puts)
    }

    @Test
    fun `after a password reset the head served under the old key is published again, signed by the new one`() = runTest {
        head = 4
        served = served.copy(records = emptyList(), heads = listOf(headOf("me"), headOf("alice")))
        h.repository.refreshMembers("c-1")
        // Served at v4, signed before the reset: it doesn't verify under the current key.
        every { h.cryptoManager.verifyCircleHead("head-me", "sig-me", TrustHarness.signing("me")) } returns null

        h.repository.syncRefreshRecords()

        assertEquals(listOf(PutHeadRequest("my-head", "my-sig")), puts)
        assertEquals(SignedCircleHead("head-me", "sig-me"), imported.single().ownHead)
    }

    @Test
    fun `a head the server already has, or an older one, decides the publish`() = runTest {
        head = 4
        served = served.copy(records = emptyList(), heads = listOf(headOf("me")))
        h.repository.refreshMembers("c-1")
        every { h.cryptoManager.verifyCircleHead("head-me", "sig-me", TrustHarness.signing("me")) } returns CircleHead("c-1", "me", 4, "chain")

        h.repository.syncRefreshRecords()
        assertTrue("the same head: nothing to publish", puts.isEmpty())

        every { h.cryptoManager.verifyCircleHead("head-me", "sig-me", TrustHarness.signing("me")) } returns CircleHead("c-1", "me", 3, "chain")
        h.repository.syncRefreshRecords()
        assertEquals("an older one: published, once for the import", 1, puts.size)
    }

    @Test
    fun `a forked circle's head is never published, whatever head the SDK still names`() = runTest {
        // The app holds this itself, not only through refreshHeadVersions' 0 while forked.
        reports.clear()
        reports += report(status(4, RecordVersionState.WAITING, RecordProblem.FORK), forked = true)
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 4)

        h.repository.syncRefreshRecords()

        assertTrue(puts.isEmpty())
        verify(exactly = 0) { h.cryptoManager.signCircleHead(any(), any()) }
    }

    @Test
    fun `nothing is published while the circle is forked, and this account's own head is compared as a member's`() = runTest {
        served = served.copy(heads = listOf(headOf("me"), headOf("alice"), headOf("mallory")))
        reports.clear()
        reports += report(status(4, RecordVersionState.WAITING, RecordProblem.FORK), forked = true)

        h.repository.syncRefreshRecords()

        assertTrue(puts.isEmpty())
        verify(exactly = 0) { h.cryptoManager.signCircleHead(any(), any()) }
        // A served head of this account's that contradicts its chain forks the
        // circle in the SDK, as a member's does; mallory isn't confirmed here.
        assertEquals(listOf("me", "alice"), imported.single().memberHeads.map { it.userId })
        // Behind: nothing is sealed, delivered or rotated for it ("Waiting to sync").
        every { h.cryptoManager.getActiveVersion("c-1") } throws uniffi.pingolin_mobile.MobileException.InvalidInput("forked")
        assertFalse(h.repository.holdsCurrentKey("c-1"))
        assertTrue("c-1" in h.repository.behindCircles.value)
    }
}
