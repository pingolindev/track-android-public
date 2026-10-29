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

import com.pingolin.track.data.remote.dto.CircleRefreshRecords
import com.pingolin.track.data.remote.dto.MemberResponse
import com.pingolin.track.data.remote.dto.MembershipStatementDto
import com.pingolin.track.data.remote.dto.RefreshRecordsResponse
import com.pingolin.track.data.remote.dto.ServedCircleHead
import com.pingolin.track.data.remote.dto.ServedRefreshRecord
import com.pingolin.track.util.KeyAccessApprovals.Origin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.pingolin_mobile.CircleHead
import uniffi.pingolin_mobile.CircleRole
import uniffi.pingolin_mobile.RecordProblem
import uniffi.pingolin_mobile.RecordVersionState
import uniffi.pingolin_mobile.RecordVersionStatus
import uniffi.pingolin_mobile.RefreshRecordsReport

/** What the app carries between the records call and the SDK (RefreshRecords). */
class RefreshRecordsTest {

    private fun record(version: Int, body: String = "r$version") = ServedRefreshRecord(version, body, "s$version")
    private fun head(userId: String) = ServedCircleHead(userId, "head-$userId", "sig-$userId")

    @Test
    fun `every page is read, each circle asked again from the last version it was served`() = runTest {
        val asked = mutableListOf<Map<String, Int>>()
        val pages = ArrayDeque(
            listOf(
                RefreshRecordsResponse(
                    listOf(
                        CircleRefreshRecords("a", listOf(record(3), record(4)), complete = true, heads = listOf(head("me"))),
                        // Not named in since: served from 0, and cut at the page's end.
                        CircleRefreshRecords("b", listOf(record(1), record(2)), complete = false),
                    ),
                    more = true,
                ),
                RefreshRecordsResponse(
                    listOf(
                        CircleRefreshRecords("a", emptyList(), complete = true, heads = listOf(head("me"), head("alice"))),
                        CircleRefreshRecords("b", listOf(record(3)), complete = true, heads = listOf(head("me"))),
                    ),
                    more = false,
                ),
            )
        )

        val served = RefreshRecords.fetchAll(mapOf("a" to 2)) { asked += it; pages.removeFirst() }!!

        assertEquals(listOf(mapOf("a" to 2), mapOf("a" to 4, "b" to 2)), asked)
        assertEquals(listOf(3, 4), served.getValue("a").records.map { it.version })
        assertEquals(listOf(1, 2, 3), served.getValue("b").records.map { it.version })
        assertEquals("heads from the circle's last page", listOf("me", "alice"), served.getValue("a").heads.map { it.userId })
        assertEquals(listOf("me"), served.getValue("b").heads.map { it.userId })
    }

    @Test
    fun `two records served for one version both reach the SDK - a fork is its to see`() = runTest {
        val pages = ArrayDeque(
            listOf(
                RefreshRecordsResponse(listOf(CircleRefreshRecords("a", listOf(record(4, "ours")), complete = false)), more = true),
                RefreshRecordsResponse(listOf(CircleRefreshRecords("a", listOf(record(4, "theirs")), complete = true)), more = false),
            )
        )

        val served = RefreshRecords.fetchAll(emptyMap()) { pages.removeFirst() }!!

        assertEquals(listOf("ours", "theirs"), served.getValue("a").records.map { it.record })
    }

    @Test
    fun `a page that can't be had, or pages that never end, decide nothing`() = runTest {
        var calls = 0
        assertNull(RefreshRecords.fetchAll(emptyMap()) { calls++; if (calls == 1) RefreshRecordsResponse(emptyList(), more = true) else null })
        assertEquals(2, calls)

        calls = 0
        assertNull(RefreshRecords.fetchAll(emptyMap()) { calls++; RefreshRecordsResponse(emptyList(), more = true) })
        assertEquals(RefreshRecords.MAX_PAGES, calls)
    }

    @Test
    fun `the members carried from a rejected tip are the report's names of it, listed here`() {
        val listed = listOf("me", "Newt", "ann", "zed")
        assertEquals(setOf("Newt", "ann"), RefreshRecords.rejectedIncluded(listOf("newt", "ANN", "gone"), listed))
        assertEquals("the plan builds on the head", emptySet<String>(), RefreshRecords.rejectedIncluded(emptyList(), listed))
    }

    @Test
    fun `the ping-pong stop compares what a version was rejected for - E's refusals and the names the chain doesn't count here`() {
        fun status(version: Int, notAllowed: List<String>, named: List<String>) = uniffi.pingolin_mobile.RecordVersionStatus(
            version, uniffi.pingolin_mobile.RecordVersionState.REJECTED, uniffi.pingolin_mobile.RecordProblem.R2_NOT_ALLOWED, notAllowed, 0u, named,
        )
        fun judged(vararg versions: uniffi.pingolin_mobile.RecordVersionStatus) = RefreshRecords.Judged(
            uniffi.pingolin_mobile.RefreshRecordsReport(versions.toList(), 3, false, false, emptyList(), emptyList()), againstFreshList = true,
        )
        val counted = setOf("ann", "bob")
        val uncounted = { id: String -> id.lowercase() !in counted }
        // v5 is rejected for o (not allowed) and names n, whom the chain doesn't count, pending.
        val v5 = status(5, notAllowed = listOf("O"), named = listOf("ann", "N", "O"))
        assertEquals(setOf("o", "n"), RefreshRecords.rejectedFor(v5, uncounted))

        val now = 10 * RefreshRecords.REPAIR_INTERVAL_MS
        fun repairs(lastFor: Set<String>) = RefreshRecords.repairNow(judged(v5), emptySet(), null, lastFor, now, uncounted)?.version
        assertEquals(5, repairs(setOf("zed")))
        assertEquals("rejected for o again: stop", null, repairs(setOf("o")))
        assertEquals("named again, uncounted: stop", null, repairs(setOf("n")))
        assertEquals("a counted name is no reason", 5, repairs(setOf("ann")))
    }

    private fun row(id: String, role: String = "member", keyVersion: Int? = 3, withStatements: Boolean = false, keyless: Boolean = false) = MemberResponse(
        userId = id,
        displayName = id,
        role = role,
        sharingPublicKey = if (keyless) null else "listed-x-$id",
        signingPublicKey = if (keyless) null else "listed-s-$id",
        keyVersion = keyVersion,
        invitation = if (withStatements) MembershipStatementDto("inv-$id", "inv-sig-$id") else null,
        vouches = if (withStatements) listOf(MembershipStatementDto("adm-$id", "adm-sig-$id")) else null,
    )

    private fun snapshot(confirmed: Map<String, Origin>, rows: List<MemberResponse>) = CircleTrustSnapshot(
        result = MemberConfirmation.Result(
            confirmed = confirmed,
            newlyConfirmed = emptyMap(),
            boundKeys = emptyMap(),
            invalid = emptySet(),
            signerUnconfirmed = emptySet(),
            signerChanged = emptySet(),
            askSigner = emptySet(),
            statementLess = emptySet(),
            subjectConflict = emptySet(),
            holding = emptyMap(),
            selfReceiveOnly = false,
        ),
        roster = emptyList(),
        accessRequested = emptySet(),
        ownerOrAdmin = false,
        rows = rows.associateBy { it.userId },
    )

    @Test
    fun `a member is confirmed only with the keys this device confirms for them, and everyone on the list is listed`() {
        val judged = snapshot(
            confirmed = mapOf("me" to Origin.OWN, "alice" to Origin.STATEMENT, "bob" to Origin.STATEMENT),
            rows = listOf(
                row("me", role = "owner"), row("alice", role = "admin", withStatements = true), row("bob"), row("carol", keyVersion = null),
                row("kay", keyVersion = null, keyless = true),
            ),
        )
        val asked = mutableListOf<String>()

        val members = RefreshRecords.recordMembers(judged) { userId ->
            asked += userId
            when (userId) {
                "me" -> "own-x" to "own-s"
                "alice" -> "pin-x-alice" to "pin-s-alice"
                else -> null // bob: confirmed, but this device holds no keys for him
            }
        }.associateBy { it.userId }

        assertEquals("keys are looked up for confirmed members only", listOf("me", "alice", "bob"), asked)
        with(members.getValue("me")) {
            assertTrue(confirmed); assertEquals("own-x", sharingPublicKeyHex); assertEquals("own-s", signingPublicKeyHex)
            assertEquals(CircleRole.OWNER, listedRole)
        }
        with(members.getValue("alice")) {
            assertTrue(confirmed); assertEquals("pin-x-alice", sharingPublicKeyHex); assertEquals("pin-s-alice", signingPublicKeyHex)
            assertEquals(CircleRole.ADMIN, listedRole)
            assertEquals(listOf("inv-alice", "adm-alice"), statements.map { it.statementHex })
            assertEquals(listOf("inv-sig-alice", "adm-sig-alice"), statements.map { it.signatureHex })
        }
        with(members.getValue("bob")) {
            assertFalse(confirmed); assertEquals("listed-x-bob", sharingPublicKeyHex); assertEquals("listed-s-bob", signingPublicKeyHex)
        }
        with(members.getValue("carol")) {
            assertFalse(confirmed); assertEquals("listed-x-carol", sharingPublicKeyHex)
            assertEquals(CircleRole.MEMBER, listedRole)
            assertTrue("no copy on the list", serverSaysKeyless)
        }
        with(members.getValue("kay")) {
            assertFalse(confirmed)
            assertEquals("no published keys: none, never an empty one", null to null, sharingPublicKeyHex to signingPublicKeyHex)
        }
        assertTrue(members.values.all { it.listed && !it.personConfirmedKeys })
        assertFalse(members.getValue("alice").serverSaysKeyless)
    }

    @Test
    fun `the heads compared are those of members this device confirms, this account's own included`() {
        val heads = listOf(head("me"), head("alice"), head("mallory"))

        val compared = RefreshRecords.memberHeads(heads, confirmed = setOf("me", "alice"))

        assertEquals(listOf("me", "alice"), compared.map { it.userId })
        assertEquals("head-alice", compared[1].head.headHex)
        assertEquals("sig-alice", compared[1].head.signatureHex)
        assertEquals("head-me", RefreshRecords.ownHead(heads, "ME")!!.headHex)
        assertNull(RefreshRecords.ownHead(listOf(head("alice")), "me"))
    }

    @Test
    fun `this account's head is published when the server serves none that verifies, or an older one`() {
        fun served(version: Int, circle: String = "c-1", user: String = "me") = CircleHead(circle, user, version, "chain")

        assertTrue("withheld, or signed by a key before a reset", RefreshRecords.headDue(5, null, "c-1", "me"))
        assertTrue("older", RefreshRecords.headDue(5, served(4), "c-1", "me"))
        assertTrue("another circle's", RefreshRecords.headDue(5, served(5, circle = "c-2"), "c-1", "me"))
        assertTrue("another account's", RefreshRecords.headDue(5, served(5, user = "alice"), "c-1", "me"))
        assertFalse("the same", RefreshRecords.headDue(5, served(5), "C-1", "ME"))
        assertFalse("another device of this account is ahead", RefreshRecords.headDue(5, served(6), "c-1", "me"))
        assertFalse("no accepted head: forked, or nothing imported", RefreshRecords.headDue(0, null, "c-1", "me"))
    }

    private fun status(version: Int, state: RecordVersionState, problem: RecordProblem = RecordProblem.NONE) =
        RecordVersionStatus(version, state, problem, emptyList(), 0u, emptyList())

    private fun report(vararg versions: RecordVersionStatus) =
        RefreshRecordsReport(versions.toList(), null, false, false, emptyList(), emptyList())

    @Test
    fun `a repair is due only for an R2 refusal, and a later member list may settle R2's and an unknown signer`() {
        val r2 = report(status(4, RecordVersionState.ACCEPTED), status(5, RecordVersionState.REJECTED, RecordProblem.R2_UNMATCHED_ENTRY))
        assertEquals(5, RefreshRecords.repairDue(r2))
        assertTrue(RefreshRecords.listMaySettle(r2))

        val signer = report(status(5, RecordVersionState.REJECTED, RecordProblem.SIGNER_UNKNOWN))
        assertNull("R1 is never repaired", RefreshRecords.repairDue(signer))
        assertTrue(RefreshRecords.listMaySettle(signer))

        for (problem in listOf(RecordProblem.R2_NOT_ALLOWED, RecordProblem.ROLE, RecordProblem.REMOVAL)) {
            assertEquals(problem.name, 5, RefreshRecords.repairDue(report(status(5, RecordVersionState.REJECTED, problem))))
        }
        val waiting = report(status(5, RecordVersionState.WAITING, RecordProblem.SIGNER_UNKNOWN))
        assertNull(RefreshRecords.repairDue(waiting))
        assertTrue(RefreshRecords.listMaySettle(waiting))

        for (problem in listOf(RecordProblem.KEY_TAG, RecordProblem.SIGNATURE, RecordProblem.FORK, RecordProblem.STRUCTURE)) {
            val refused = report(status(5, RecordVersionState.REJECTED, problem))
            assertNull(problem.name, RefreshRecords.repairDue(refused))
            assertFalse(problem.name, RefreshRecords.listMaySettle(refused))
        }
        val missing = report(status(5, RecordVersionState.WAITING, RecordProblem.LINK_MISSING))
        assertFalse(RefreshRecords.listMaySettle(missing))
        assertFalse(RefreshRecords.listMaySettle(report(status(5, RecordVersionState.ACCEPTED), status(6, RecordVersionState.REFERENCE))))
    }

    @Test
    fun `a misattribution passed over is repaired, after one roster pass like an R2 refusal - a failing signature still only waits`() {
        // Only a repair brings writes back.
        val misattributed = report(status(4, RecordVersionState.ACCEPTED), status(5, RecordVersionState.REJECTED, RecordProblem.MISATTRIBUTED))
        assertEquals(5, RefreshRecords.repairDue(misattributed))
        assertTrue("the roster pass a repair waits for", RefreshRecords.listMaySettle(misattributed))
        assertEquals(5, RefreshRecords.repairNow(RefreshRecords.Judged(misattributed, againstFreshList = true), emptySet(), null, emptySet(), 0L)?.version)
        assertNull(RefreshRecords.repairNow(RefreshRecords.Judged(misattributed, againstFreshList = false), emptySet(), null, emptySet(), 0L))

        val signature = report(status(5, RecordVersionState.WAITING, RecordProblem.SIGNATURE))
        assertNull(RefreshRecords.repairDue(signature))
        assertFalse(RefreshRecords.listMaySettle(signature))
    }

    @Test
    fun `the SDK's locked "not yet" is its words, whatever the error case, and nothing else is`() {
        // The SDK's locked words, in each error case that carries them.
        assertTrue(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.InvalidInput("import this circle's records first, then rotate")))
        assertTrue(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.KeyNotFound("import this circle's records first")))
        assertTrue(RefreshRecords.notImportedYet(
            uniffi.pingolin_mobile.MobileException.InvalidInput("the head's record isn't known here in full yet: import this circle's records first"),
        ))
        assertFalse(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.InvalidInput("this circle's chain forked: it waits")))
        assertFalse(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.InvalidInput("no record of this circle is accepted here yet")))
        assertFalse("the SDK's own errors only", RefreshRecords.notImportedYet(IllegalStateException("import this circle's records first")))
        // As the real SDK's errors read in Kotlin: the case's words, then the SDK's, apostrophe and all.
        assertTrue(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.InvalidInput("Invalid input: import this circle's records first, then rotate")))
        assertTrue(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.KeyNotFound("Key not found: import this circle's records first")))
        assertFalse(RefreshRecords.notImportedYet(uniffi.pingolin_mobile.MobileException.KeyNotFound("Key not found: no record of this circle is accepted here")))
    }
}
