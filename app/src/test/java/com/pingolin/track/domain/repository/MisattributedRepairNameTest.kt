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

import com.google.gson.Gson
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.data.remote.dto.AccessRequestResponse
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.CircleResponse
import com.pingolin.track.data.remote.dto.EncryptedPayload
import com.pingolin.track.data.remote.dto.PaginatedResponse
import com.pingolin.track.data.remote.dto.PutHeadResponse
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedRefreshRecord
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KeyChainLinkData
import uniffi.pingolin_mobile.KeyRotationBundle
import uniffi.pingolin_mobile.MemberKeyBundle
import uniffi.pingolin_mobile.MemberKeyRotationEntry
import uniffi.pingolin_mobile.MobileException
import uniffi.pingolin_mobile.PlannedEntry
import uniffi.pingolin_mobile.RecordMemberState
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordVersionState
import uniffi.pingolin_mobile.RecordVersionStatus
import uniffi.pingolin_mobile.RecordedRotationBundle
import uniffi.pingolin_mobile.RefreshRecordKind
import uniffi.pingolin_mobile.RefreshRecordsReport
import uniffi.pingolin_mobile.SignedCircleHead
import uniffi.pingolin_mobile.SignedRefreshRecord

/**
 * A repair answering a version rejected as MISATTRIBUTED is never held back by the circle's name,
 * and carries none (rotate-keys keeps the circle's): the key in use there is the misattributing
 * member's, who could have sealed a name under it. No key change waits on a name that doesn't
 * open here; only being behind on the key stops one. Through the real CircleRepository: circle
 * refreshes, the records import rejects v3, and the refresh's sweep repairs. The SDK is mocked:
 * what matters is what the app hands it, and sends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MisattributedRepairNameTest {

    @After
    fun tearDown() = clearAllMocks()

    /** A call of the builder: its kind, the name it was handed to seal, and whether the records lock was held. */
    private data class Build(val kind: RefreshRecordKind, val name: String, val underRecordsLock: Boolean)

    private val built = mutableListOf<Build>()

    /** What the SDK's records import says; isBehind follows it. */
    private var report = report(status(2, RecordVersionState.ACCEPTED))

    /** The records the server serves above the head. */
    private var served = emptyList<ServedRefreshRecord>()

    /** Whether key 3 reached this device, and whether it opens the name sealed under it. */
    private var holdsV3 = false
    private var v3NameOpens = true

    /** The version this device seals under (getActiveVersion): the last whose record it accepted. */
    private var sealsUnder = 2

    /** What the refreshes' sweeps start (the repair among them), run to its end by [settle]. */
    private val sweeps = SupervisorJob()

    private fun status(version: Int, state: RecordVersionState, problem: RecordProblem = RecordProblem.NONE) =
        RecordVersionStatus(version, state, problem, emptyList(), 0u, emptyList())

    private fun report(vararg versions: RecordVersionStatus) = RefreshRecordsReport(versions.toList(), null, false, false, emptyList(), emptyList())

    /** c-1 as the server lists it: at [keyVersion], the name sealed under key [sealedUnder], which the server says is [nameKeyVersion]. */
    private fun listed(keyVersion: Int, nameKeyVersion: Int? = keyVersion, sealedUnder: Int = nameKeyVersion ?: keyVersion, flagged: Boolean = false) = CircleResponse(
        id = "c-1",
        encryptedName = EncryptedPayload(nonce = "nonce-v$sealedUnder", ciphertext = "sealed-v$sealedUnder"),
        encryptedKey = null,
        ephemeralPublicKey = null,
        memberCount = 3,
        isOwner = true,
        dayBucket = 20000,
        keyRotationNeeded = flagged,
        keyVersion = keyVersion,
        nameKeyVersion = nameKeyVersion,
    )

    /**
     * An owner's phone on a circle with records it accepted up to v2, whose row it holds at v2
     * ("Family", read before). Key 2 opens "Family"; key 3, if it came here, "Renamed at v3".
     */
    private fun TestScope.harness() = TrustHarness(selfRole = "owner").also { h ->
        h.repository.deliveryScope = CoroutineScope(coroutineContext + sweeps)
        h.repository.catchUpPausesMs = emptyList()
        h.earlierSeed("me", "alice", "bob")
        h.serverLists(listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice"), h.rosterMember("bob")))
        h.recordedCircle()
        h.circleVersion = 2
        h.circle = h.circle.copy(keyVersion = 2, keyRotationNeeded = false)
        every { h.cryptoManager.refreshHeadVersions() } returns mapOf("c-1" to 2)
        every { h.cryptoManager.getActiveVersion("c-1") } answers { sealsUnder }
        every { h.cryptoManager.newestHeldVersion("c-1") } answers { if (holdsV3) 3 else 2 }
        every { h.cryptoManager.isBehind("c-1") } answers { report.versions.any { it.state != RecordVersionState.ACCEPTED } }
        every { h.cryptoManager.decryptDataV2("c-1", any(), "name") } answers {
            when (arg<EncryptedData>(1).ciphertext) {
                "sealed-v2" -> "Family"
                "sealed-v3" -> if (holdsV3 && v3NameOpens) "Renamed at v3" else throw MobileException.DecryptionFailed("not opened here")
                else -> throw MobileException.DecryptionFailed("not opened here")
            }
        }
        // The same names with the version that opened them (what
        // sealableName reads).
        every { h.cryptoManager.decryptDataV2Opened("c-1", any(), "name") } answers {
            when (arg<EncryptedData>(1).ciphertext) {
                "sealed-v2" -> uniffi.pingolin_mobile.OpenedText("Family", 2)
                "sealed-v3" -> if (holdsV3 && v3NameOpens) uniffi.pingolin_mobile.OpenedText("Renamed at v3", 3) else throw MobileException.DecryptionFailed("not opened here")
                else -> throw MobileException.DecryptionFailed("not opened here")
            }
        }
        every { h.cryptoManager.isCurrentKeyVersion("c-1", any()) } answers { arg<Int>(1) == sealsUnder }
        every { h.cryptoManager.importRefreshRecords("c-1", any(), any(), any(), any(), any(), any()) } answers { report }
        coEvery { h.tracksApi.refreshRecords(any()) } answers {
            Response.success(ApiResponse(true, RefreshRecordsResponse(listOf(CircleRefreshRecords("c-1", served, complete = true)), more = false), null))
        }
        every { h.cryptoManager.signCircleHead("c-1", "me") } returns SignedCircleHead("my-head", "my-sig")
        every { h.cryptoManager.verifyCircleHead(any(), any(), any()) } returns null
        coEvery { h.tracksApi.putCircleHead("c-1", any()) } returns Response.success(ApiResponse(true, PutHeadResponse(true), null))
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(true, emptyList(), null))
        coEvery { h.tracksApi.requestAccess("c-1") } returns Response.success(ApiResponse(true, AccessRequestResponse(true), null))
        // The rows this device keeps from the circle list.
        coEvery { h.circleDao.getAll() } answers { listOf(h.circle.copy(keyVersion = h.circleVersion)) }
        coEvery { h.circleDao.insertAll(any()) } answers {
            arg<List<CircleEntity>>(0).single().let { row ->
                h.circle = row
                h.circleVersion = row.keyVersion
            }
        }
        every { h.cryptoManager.prepareRecordedRotation("c-1", "me", any(), any(), any(), any(), any()) } answers {
            built += Build(arg(2), arg(6), h.repository.recordsMutex.isLocked)
            val included = arg<List<PlannedEntry>>(3).filter { it.state == RecordMemberState.INCLUDED }.map { it.userId }
            RecordedRotationBundle(
                KeyRotationBundle(
                    newKeyVersion = 4,
                    encryptedName = EncryptedData("sealed-name-nonce", "sealed-name"),
                    memberKeys = included.map { MemberKeyRotationEntry(it, MemberKeyBundle(EncryptedData("n-$it", "c-$it"), "e-$it", "s-$it")) },
                    keyChainLink = KeyChainLinkData(4, 2, "ek", "nk"),
                ),
                SignedRefreshRecord("repair-hex", "repair-sig"),
            )
        }
    }

    /** The server lists c-1 as [dto], and this device refreshes its circles; what the refresh starts runs too. */
    private suspend fun TestScope.refresh(h: TrustHarness, dto: CircleResponse) {
        coEvery { h.tracksApi.getCircles() } returns
            Response.success(ApiResponse(true, PaginatedResponse(items = listOf(dto), hasMore = false), null))
        assertTrue("the refresh went through", h.repository.refreshCircles().isSuccess)
        settle()
    }

    /** Everything the sweeps started, run to its end: parts of it wait on other threads (the SDK's imports). */
    private suspend fun TestScope.settle() {
        while (true) {
            advanceUntilIdle()
            val running = sweeps.children.filter { it.isActive }.toList()
            if (running.isEmpty()) return
            running.joinAll()
        }
    }

    /** C's copy of v3, A's record signed with C's key: the SDK rejects it as MISATTRIBUTED. */
    private fun misattributedV3(problem: RecordProblem = RecordProblem.MISATTRIBUTED) {
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.REJECTED, problem))
    }

    @Test
    fun `key 3 never came here - the repair goes, though the row is locked, and carries no name`() = runTest {
        val h = harness()
        val sent = h.rotationAccepted()
        refresh(h, listed(keyVersion = 2))

        // V's v3: its name sealed under key 3, which never came here; the records import rejects it.
        misattributedV3()
        refresh(h, listed(keyVersion = 3))

        assertTrue("locked here", LockedCircle.isPlaceholder(h.circle.name))
        assertEquals(listOf(Build(RefreshRecordKind.REPAIR, "", underRecordsLock = true)), built)
        assertNull("rotate-keys keeps the circle's", sent.captured.encryptedName)
        assertFalse(Gson().toJson(sent.captured).contains("encrypted_name"))
        assertEquals(setOf(3), h.repairBudget.repaired("c-1"))
    }

    @Test
    fun `key 3 came here - its name is read and shown, never sealed, and the repair carries no name`() = runTest {
        val h = harness()
        val sent = h.rotationAccepted()
        refresh(h, listed(keyVersion = 2))

        holdsV3 = true
        misattributedV3()
        refresh(h, listed(keyVersion = 3))

        assertEquals("Renamed at v3", h.circle.name)
        assertEquals(listOf(Build(RefreshRecordKind.REPAIR, "", underRecordsLock = true)), built)
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `a name that doesn't open here stops nothing - a repair and a flagged rotation each go, without it`() = runTest {
        // Any member may plant a name nobody opens; a phone that waited on it would never
        // rotate or eject again. Key 3 is here but doesn't open the name.
        for (problem in listOf(RecordProblem.R2_NOT_ALLOWED, RecordProblem.MISATTRIBUTED)) {
            built.clear()
            val h = harness()
            val sent = h.rotationAccepted()
            refresh(h, listed(keyVersion = 2))
            holdsV3 = true
            v3NameOpens = false
            misattributedV3(problem)

            refresh(h, listed(keyVersion = 3))

            assertTrue(problem.name, LockedCircle.isPlaceholder(h.circle.name))
            assertEquals(problem.name, listOf(Build(RefreshRecordKind.REPAIR, "", underRecordsLock = true)), built)
            assertNull(problem.name, sent.captured.encryptedName)
        }

        // A flagged rotation, no repair due, at the current version, on a row whose name doesn't open here.
        built.clear()
        val h = harness()
        val sent = h.rotationAccepted()
        refresh(h, listed(keyVersion = 2))
        holdsV3 = true
        v3NameOpens = false
        sealsUnder = 3
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.ACCEPTED))
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))

        refresh(h, listed(keyVersion = 3, flagged = true))

        assertTrue(LockedCircle.isPlaceholder(h.circle.name))
        assertEquals(listOf(RefreshRecordKind.ROTATION to ""), built.map { it.kind to it.name })
        assertNull("rotate-keys keeps the circle's", sent.captured.encryptedName)
    }

    @Test
    fun `a name opened under the key in use, which the rotation replaces, is carried`() = runTest {
        val h = harness()
        val sent = h.rotationAccepted()

        refresh(h, listed(keyVersion = 2, flagged = true))

        assertEquals(listOf(RefreshRecordKind.ROTATION to "Family"), built.map { it.kind to it.name })
        assertEquals(EncryptedPayload("sealed-name-nonce", "sealed-name"), sent.captured.encryptedName)
    }

    @Test
    fun `delivered but not accepted - a name only the newer held version opens is never carried`() = runTest {
        // v3's key came here and opens the name sealed under it, but v3's record isn't accepted:
        // this device seals under v2. An R2_NOT_ALLOWED refusal's repair.
        val h = harness()
        val sent = h.rotationAccepted()
        refresh(h, listed(keyVersion = 2))
        holdsV3 = true
        misattributedV3(RecordProblem.R2_NOT_ALLOWED)

        refresh(h, listed(keyVersion = 3))

        assertEquals("shown", "Renamed at v3", h.circle.name)
        assertEquals(listOf(Build(RefreshRecordKind.REPAIR, "", underRecordsLock = true)), built)
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `delivered but not accepted - a flagged rotation from the version in use never carries a name only the newer version opens`() = runTest {
        // The list says v2 and serves the name sealed under v3, whose key came here and whose
        // record waits: this device seals under v2, the version the rotation replaces. The
        // newest version held is not the key in use.
        val h = harness()
        val sent = h.rotationAccepted()
        holdsV3 = true
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.WAITING, RecordProblem.SIGNER_UNKNOWN))
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))

        refresh(h, listed(keyVersion = 2, nameKeyVersion = 3, sealedUnder = 3, flagged = true))

        assertEquals("shown", "Renamed at v3", h.circle.name)
        assertEquals(listOf(RefreshRecordKind.ROTATION to ""), built.map { it.kind to it.name })
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `a name only an older held version opens shows, and is never carried`() = runTest {
        // v3 accepted and sealed under here; the list serves the name sealed under v2.
        val h = harness()
        val sent = h.rotationAccepted()
        holdsV3 = true
        sealsUnder = 3
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.ACCEPTED))
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))

        refresh(h, listed(keyVersion = 3, nameKeyVersion = 3, sealedUnder = 2, flagged = true))

        assertEquals("shown", "Family", h.circle.name)
        assertEquals(listOf(RefreshRecordKind.ROTATION to ""), built.map { it.kind to it.name })
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `a name under the version sealed under here, which isn't the one the change replaces, is never carried`() = runTest {
        // This device seals under v3 while the circle's row still says v2: not behind, and not the version replaced.
        val h = harness()
        val sent = h.rotationAccepted()
        holdsV3 = true
        sealsUnder = 3
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.ACCEPTED))
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))

        refresh(h, listed(keyVersion = 2, nameKeyVersion = 3, sealedUnder = 3, flagged = true))

        assertEquals(listOf(RefreshRecordKind.ROTATION to ""), built.map { it.kind to it.name })
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `behind on the key, a flagged rotation still waits`() = runTest {
        // v3's record waits here: this device seals under v2 while the circle is at v3.
        val h = harness()
        h.rotationAccepted()
        refresh(h, listed(keyVersion = 2))
        holdsV3 = true
        report = report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.WAITING, RecordProblem.SIGNER_UNKNOWN))
        served = listOf(ServedRefreshRecord(3, "v3-hex", "v3-sig"))

        refresh(h, listed(keyVersion = 3, flagged = true))

        assertTrue(built.isEmpty())
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `the repair is built under the records lock only while it is still the one due, else nothing is built and the next pass plans again`() = runTest {
        // The member list the repair reads comes with a records import that changes the repair
        // due: v3 now refused for another reason (another member's list settled it), or a later
        // version misattributed. The misattribution admitted, and the name chosen for it, are
        // then not the repair due.
        val changes = listOf(
            "v3 refused for another reason" to report(status(2, RecordVersionState.ACCEPTED), status(3, RecordVersionState.REJECTED, RecordProblem.R2_NOT_ALLOWED)),
            "v4 misattributed too" to report(
                status(2, RecordVersionState.ACCEPTED),
                status(3, RecordVersionState.REJECTED, RecordProblem.MISATTRIBUTED),
                status(4, RecordVersionState.REJECTED, RecordProblem.MISATTRIBUTED),
            ),
        )
        for ((what, changed) in changes) {
            built.clear()
            report = report(status(2, RecordVersionState.ACCEPTED))
            val h = harness()
            h.rotationAccepted()
            refresh(h, listed(keyVersion = 2))
            misattributedV3()
            val misattributed = report
            val list = listOf(h.rosterMember("me", role = "owner"), h.rosterMember("alice"), h.rosterMember("bob"))
            // The repair's own reads, after its admission: the keys, then the member list, which changes it.
            var repairReadKeys = false
            var swapped = false
            coEvery { h.tracksApi.getMemberPublicKeys("c-1") } answers {
                repairReadKeys = true
                Response.success(ApiResponse(true, list.map { h.key(it.userId) }, null))
            }
            coEvery { h.tracksApi.getCircleMembers("c-1", any(), any()) } answers {
                if (repairReadKeys && !swapped) {
                    swapped = true
                    report = changed
                    h.repository.recordsJudged["c-1"] = RefreshRecords.Judged(report, againstFreshList = true)
                }
                Response.success(ApiResponse(true, PaginatedResponse(items = list, hasMore = false), null))
            }

            refresh(h, listed(keyVersion = 3))

            assertTrue("$what: the misattribution's repair was admitted and read the list", swapped)
            assertTrue("$what: nothing built for a repair no longer due", built.isEmpty())
            coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }

            // No backoff: with the misattribution admitted due again, the next pass builds it, under the lock.
            report = misattributed
            h.repository.recordsJudged["c-1"] = RefreshRecords.Judged(report, againstFreshList = true)
            h.repository.autoRotateIfNeeded("c-1")
            settle()
            assertEquals(what, listOf(Build(RefreshRecordKind.REPAIR, "", underRecordsLock = true)), built)
            assertFalse("$what: let go after the build", h.repository.recordsMutex.isLocked)
        }
    }

}
