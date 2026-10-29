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
import com.pingolin.track.data.remote.dto.EjectAndRotateResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.KeyChainLinkResponse
import com.pingolin.track.data.remote.dto.OwnWrapSignatureResponse
import com.pingolin.track.data.remote.dto.PutHeadResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedRefreshRecord
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.KeyRotationBundle
import uniffi.pingolin_mobile.MemberKeyBundle
import uniffi.pingolin_mobile.MemberKeyRotationEntry
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.RecordVersionState
import uniffi.pingolin_mobile.RecordVersionStatus
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordedRotationBundle
import uniffi.pingolin_mobile.RefreshRecordsReport
import uniffi.pingolin_mobile.SignedCircleHead
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * This device's own key change of a circle:
 *  - once the server takes it, the link it sent is what the next records import walks, before any key sync,
 *    so this device's own new record never waits on its own link (LinkMissing);
 *  - while the server has taken this device's own record and no import has judged it here (been served it,
 *    or reached it), no key change builds another; an eject or hand-over reads the records first;
 *  - an attempt refused before it minted a key ("v3 of this circle waits here") discards nothing; one that
 *    minted a key and then failed discards that key.
 * Through the real CircleRepository, the SDK mocked (TrustHarness): its link is KeyChainLinkData(v + 1, v,
 * "ek", "nk").
 */
class OwnKeyChangeTest {

    private lateinit var h: TrustHarness
    private val repository get() = h.repository

    /** The links each records import was handed, in order. */
    private val imported = mutableListOf<List<KeyChainLinkData>>()

    /** The link this device's own key change from v3 sends, as the harness's SDK builds it. */
    private val sent = KeyChainLinkData(4, 3, "ek", "nk")

    private val waits = "v3 of this circle waits here: no record is built until it's decided"

    @Before
    fun setUp() {
        h = TrustHarness()
        h.earlierSeed("me", "alice", "eve")
        h.serverLists(roster = listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("eve")))
        // No roster pass in the background: every import counted here is one this test asks for.
        repository.deliveryScope = CoroutineScope(Job().also { it.cancel() })
        every { h.cryptoManager.importRefreshRecords(any(), any(), any(), any(), any(), any(), any()) } answers {
            imported += arg<List<KeyChainLinkData>>(2)
            RefreshRecordsReport(
                listOf(RecordVersionStatus(4, RecordVersionState.ACCEPTED, RecordProblem.NONE, emptyList(), 0u, emptyList())),
                null, false, false, emptyList(), emptyList(),
            )
        }
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(ApiResponse(
            success = true,
            data = RefreshRecordsResponse(listOf(CircleRefreshRecords("c-1", listOf(ServedRefreshRecord(4, "r4", "s4")), complete = true)), more = false),
            error = null,
        ))
        every { h.cryptoManager.signCircleHead("c-1", "me") } returns SignedCircleHead("my-head", "my-sig")
        every { h.cryptoManager.verifyCircleHead(any(), any(), any()) } returns null
        coEvery { h.tracksApi.putCircleHead("c-1", any()) } returns
            Response.success(ApiResponse(success = true, data = PutHeadResponse(true), error = null))
        coEvery { h.tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = AccessRequestResponse(true), error = null))
    }

    @After
    fun tearDown() = clearAllMocks()

    /** The circle has records from here on: the next import judges them. */
    private fun recordsFromNowOn() {
        every { h.cryptoManager.circleHasRefreshRecords("c-1") } returns true
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 3)
    }

    private fun ejectLands() = coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.success(
        ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
    )

    private fun waitsHere() =
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } throws MobileException.InvalidInput(waits)

    // ---- the link an own change sent is walked before the next key sync ------------------------------------

    @Test
    fun `an own transition that lands puts the link it sent into the next records import, before any key sync`() = runTest {
        // A record-less circle this owner flags: its key change is the circle's first record.
        h.rotationAccepted()
        repository.autoRotateIfNeeded("c-1")
        verify { h.cryptoManager.prepareRecordedRotation("c-1", any(), uniffi.pingolin_mobile.RefreshRecordKind.TRANSITION, any(), any(), any(), any()) }

        recordsFromNowOn()
        repository.syncRefreshRecords()

        assertEquals(listOf(sent), imported.last())
    }

    @Test
    fun `an own eject that lands puts the link it sent into the next records import`() = runTest {
        ejectLands()
        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)

        recordsFromNowOn()
        repository.syncRefreshRecords()

        assertEquals(listOf(sent), imported.last())
    }

    @Test
    fun `a key change the server refused adds no link`() = runTest {
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns
            Response.error(500, """{"success":false}""".toResponseBody(null))
        repository.autoRotateIfNeeded("c-1")

        recordsFromNowOn()
        repository.syncRefreshRecords()

        assertEquals("only what a key sync served: none here", emptyList<KeyChainLinkData>(), imported.last())
    }

    @Test
    fun `an eject the server refused adds no link`() = runTest {
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns
            Response.error(409, """{"success":false,"error_code":"KEY_VERSION_MISMATCH"}""".toResponseBody(null))
        assertTrue(repository.ejectMember("c-1", "eve").isFailure)

        recordsFromNowOn()
        repository.syncRefreshRecords()

        assertEquals("only what a key sync served: none here", emptyList<KeyChainLinkData>(), imported.last())
    }

    @Test
    fun `a key sync puts the server's links back, and an own link replaces one of the same versions`() = runTest {
        val served = listOf(
            KeyChainLinkResponse(3, 2, "ek-3", "nk-3"),
            KeyChainLinkResponse(4, 3, "stale", "stale-n"),
        )
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, error = null, data = listOf(
            CircleKeyBundleResponse("c-1", EncryptedPayload("n", "c"), "e", 3, keyChain = served, senderUserId = "me", recipientUserId = "me"),
        )))
        coEvery { h.tracksApi.signOwnWrap(any(), any()) } returns
            Response.success(ApiResponse(success = true, data = OwnWrapSignatureResponse(signed = true), error = null))
        every { h.cryptoManager.signOwnCircleKeyWrap(any(), any(), any(), any(), any()) } returns "own-sig"
        h.recordedCircle()
        repository.importCircleKeys()
        assertEquals("a key sync's import walks the served list", RefreshRecords.links(served), imported.last())

        h.rotationAccepted()
        repository.autoRotateIfNeeded("c-1")
        repository.syncRefreshRecords()
        assertEquals(
            "the served 3→2 kept, and 4→3 the one this device sent",
            listOf(KeyChainLinkData(3, 2, "ek-3", "nk-3"), sent),
            imported.last(),
        )

        repository.importCircleKeys()
        assertEquals("the next key sync's list is the server's again", RefreshRecords.links(served), imported.last())
    }

    // ---- no record is built on an own record that is not judged here ---------------------------------

    private fun fourMembers() {
        h.earlierSeed("me", "alice", "eve", "bob")
        h.serverLists(roster = listOf(h.rosterMember("me"), h.rosterMember("alice"), h.rosterMember("eve"), h.rosterMember("bob")))
    }

    @Test
    fun `two own ejects back to back with no import between - the second waits, mints nothing, and the earlier key stays`() = runTest {
        h.recordedCircle()
        fourMembers()
        ejectLands()
        // No records read gets through: nothing judges the first eject's record here.
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.error(500, """{"success":false}""".toResponseBody(null))

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        val second = repository.ejectMember("c-1", "bob")

        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull() is KeyNotCurrentException)
        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a second own eject imports the first one's record before it builds the next`() = runTest {
        h.recordedCircle()
        fourMembers()
        ejectLands()

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        assertTrue(repository.ejectMember("c-1", "bob").isSuccess)

        io.mockk.verifyOrder {
            h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any())
            h.cryptoManager.importRefreshRecords("c-1", any(), any(), any(), any(), any(), any())
            h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any())
        }
        assertEquals("the import walked the link the first eject sent", listOf(sent), imported.first())
    }

    @Test
    fun `a hand-over right after an own eject, with no import between, waits too`() = runTest {
        h.recordedCircle()
        fourMembers()
        ejectLands()
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.error(500, """{"success":false}""".toResponseBody(null))

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        val handOver = repository.transferOwnership("c-1", "alice")

        assertEquals(CircleRepository.HAND_OVER_NOT_DONE, handOver.exceptionOrNull()?.message)
        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    /** The records read serves c-1 with records of [versions] only. */
    private fun recordsServed(vararg versions: Int) {
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(ApiResponse(
            success = true,
            data = RefreshRecordsResponse(
                listOf(CircleRefreshRecords("c-1", versions.map { ServedRefreshRecord(it, "r$it", "s$it") }, complete = true)),
                more = false,
            ),
            error = null,
        ))
    }

    @Test
    fun `an import whose read began before this device's own record was taken leaves the wait in place`() = runTest {
        h.recordedCircle()
        fourMembers()
        ejectLands()
        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)

        // A read that began before the server took v4 serves nothing above the head.
        recordsServed()
        val second = repository.ejectMember("c-1", "bob")

        assertTrue(second.exceptionOrNull() is KeyNotCurrentException)
        // That import ran (served nothing), and the mark stayed: not an import skipped.
        verify(exactly = 1) { h.cryptoManager.importRefreshRecords("c-1", emptyList(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }

        // A read served v4 (the head still 3 here): it was judged, so the next change builds.
        recordsServed(4)
        assertTrue(repository.ejectMember("c-1", "bob").isSuccess)
        verify(exactly = 2) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a rotation's own record keeps the wait through an import whose read began before it was taken`() = runTest {
        h.recordedCircle()
        h.rotationAccepted()
        repository.autoRotateIfNeeded("c-1")
        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }

        recordsServed()
        repository.syncRefreshRecords()
        repository.autoRotateIfNeeded("c-1")
        verify(exactly = 1) { h.cryptoManager.importRefreshRecords("c-1", emptyList(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }

        // A read served v4, the version the rotation's record carries: judged, so the next rotation builds.
        recordsServed(4)
        repository.syncRefreshRecords()
        repository.autoRotateIfNeeded("c-1")
        verify(exactly = 2) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an import with the head at this device's own record clears the wait, though nothing new was served`() = runTest {
        h.recordedCircle()
        fourMembers()
        ejectLands()
        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)

        // v4 was taken here before the mark was set (head 4): the next read serves nothing above it.
        recordsServed()
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 4)

        assertTrue(repository.ejectMember("c-1", "bob").isSuccess)
        verify(exactly = 2) { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
    }

    // ---- a refused attempt keeps an earlier own key --------------------------------------------------------

    @Test
    fun `an eject refused before minting keeps the earlier own key - nothing is discarded, and it still says why`() = runTest {
        h.recordedCircle()
        waitsHere()

        val result = repository.ejectMember("c-1", "eve")

        assertTrue(result.isFailure)
        assertEquals(waits, (result.exceptionOrNull() as? MobileException)?.message)
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a rotation refused before minting keeps the earlier own key`() = runTest {
        h.recordedCircle()
        waitsHere()

        repository.autoRotateIfNeeded("c-1")

        verify { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a hand-over refused before minting keeps the earlier own key`() = runTest {
        h.recordedCircle()
        waitsHere()

        assertTrue(repository.transferOwnership("c-1", "alice").isFailure)

        verify { h.cryptoManager.prepareRecordedRotation("c-1", any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `an eject that minted its key and then failed on the way discards that key`() = runTest {
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } throws java.io.IOException("reset")

        assertTrue(repository.ejectMember("c-1", "eve").isFailure)

        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `an eject without a record that minted its key and then failed on the way discards that key`() = runTest {
        // A record-less circle whose first record isn't written here: the eject re-keys without a record, by
        // its own mint.
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("no first record here")
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } throws java.io.IOException("reset")

        assertTrue(repository.ejectMember("c-1", "eve").isFailure)

        verify { h.cryptoManager.prepareKeyRotation("c-1", any(), any(), any(), any(), any()) }
        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `a hand-over that minted its key and then failed on the way discards that key`() = runTest {
        h.recordedCircle()
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } throws java.io.IOException("reset")

        assertTrue(repository.transferOwnership("c-1", "alice").isFailure)

        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `a rotation without a record that minted its key and then failed discards that key`() = runTest {
        // A record-less circle whose first record isn't written here: the flagged change goes
        // without a record.
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } throws
            MobileException.InvalidInput("no first record here")
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } throws java.io.IOException("reset")

        repository.autoRotateIfNeeded("c-1")

        verify { h.cryptoManager.prepareKeyRotation("c-1", any(), any(), any(), any(), any()) }
        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `a rotation whose ownership claim the builder refused, built again without it and then lost, discards that key`() = runTest {
        // An admin in the chain whom the list shows as owner, the chain's owner off the list, claims it.
        // The builder refuses the claim once; the record goes without it, and that second build mints
        // the key.
        h.recordedCircle()
        every { h.cryptoManager.chainRole("c-1", any()) } answers {
            if (arg<String>(1) == "me") CircleRole.ADMIN else CircleRole.MEMBER
        }
        var builds = 0
        every { h.cryptoManager.prepareRecordedRotation(any(), any(), any(), any(), any(), any(), any()) } answers {
            if (++builds == 1) throw MobileException.InvalidInput("the claim isn't taken yet")
            RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = 4,
                    encryptedName = EncryptedData("nn", "cc"),
                    memberKeys = listOf(MemberKeyRotationEntry("alice", MemberKeyBundle(EncryptedData("n", "c"), "e", "s"))),
                    keyChainLink = sent,
                ),
                SignedRefreshRecord("record", "record-sig"),
            )
        }
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } throws java.io.IOException("reset")

        repository.autoRotateIfNeeded("c-1")

        assertEquals("built twice: with the claim, then without", 2, builds)
        verify(exactly = 1) { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }
}
