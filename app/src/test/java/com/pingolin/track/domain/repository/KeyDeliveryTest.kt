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
import com.pingolin.track.data.remote.dto.EjectAndRotateRequest
import com.pingolin.track.data.remote.dto.EjectAndRotateResponse
import com.pingolin.track.data.remote.dto.InviteRequest
import com.pingolin.track.data.remote.dto.InviteResponse
import com.pingolin.track.data.remote.dto.RotateKeysRequest
import com.pingolin.track.data.remote.dto.RotateKeysResponse
import com.pingolin.track.data.remote.dto.UserPublicKeyResponse
import com.pingolin.track.domain.repository.TrustHarness.Companion.sharing
import com.pingolin.track.domain.repository.TrustHarness.Companion.signing
import com.pingolin.track.util.KeyAccessApprovals.Origin
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * A rotation delivers the new circle key only to members this device confirms, and goes
 * ahead around the ones it cannot. Drives the real CircleRepository rotation against a
 * mocked server and crypto manager and checks what goes on the wire. This device is the
 * circle's owner, and first saw the circle before signed invitations (its seed from then:
 * me, alice, bob, eve).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyDeliveryTest {

    private lateinit var h: TrustHarness
    private val repository get() = h.repository

    /** Accounts the invite lookup names are UUIDs (AccountIds.strict). */
    private val dan = "da400000-0000-4000-8000-0000000000d4"
    private val bob = "b0b00000-0000-4000-8000-00000000000b"
    private val mallory = "3a110000-0000-4000-8000-000000000011"

    @Before
    fun setUp() {
        h = TrustHarness()
        h.earlierSeed("me", "alice", "bob", "eve")
    }

    @After
    fun tearDown() = clearAllMocks()

    private fun TestScope.deliverHere() {
        repository.deliveryScope = this
    }

    private fun members(vararg ids: String) = ids.map { h.rosterMember(it) }

    /**
     * A keyless newcomer invited by mallory, whom this device cannot confirm (she carries
     * nothing): the owner is asked about the newcomer, with their number. mallory herself is
     * hidden and never asked about.
     */
    private fun invitedByUnconfirmed() =
        listOf(h.rosterMember("mallory"), h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null))

    @Test
    fun `a rotation wraps only confirmed members and leaves the rest pending`() = runTest {
        deliverHere()
        // bob: key change not accepted here. newcomer: invited from an older app, hidden
        // and never asked about. ghost: listed for the key, absent from the member list.
        h.pins.pin("bob", sharing("bob-old"), signing("bob-old"))
        h.serverLists(
            roster = members("me", "alice", "bob") + h.rosterMember("newcomer", keyVersion = null),
            keys = listOf(h.key("me"), h.key("alice"), h.key("bob"), h.key("newcomer", keyVersion = null, email = "n@x.test"), h.key("ghost")),
        )
        val sent = h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(setOf("bob", "newcomer", "ghost"), sent.captured.pendingMembers.orEmpty().toSet())
        // Nobody is pinned for being listed.
        verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), "ghost", any(), any()) }
        verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), "newcomer", any(), any()) }
        // Nobody is asked about the newcomer; the owner's line counts them.
        assertTrue(repository.keyAccessRequests.value.isEmpty())
        assertEquals(1, repository.memberStates.value["c-1"]?.keptOut)
        // Getting a wrap is not an approval; nor is being listed. eve left: gone, tombstoned.
        assertEquals(setOf("me", "alice", "bob"), h.approvals.approved("c-1"))
        assertEquals(mapOf("eve" to 3), h.approvals.tombstones("c-1"))
        // bob's change is the per-device question, with his number.
        assertEquals("number-of-bob", repository.memberKeyAlerts.value.single { it.userId == "bob" }.number)
        // The rotation landed: this account's own open SOS there goes along to the new key,
        // with no key read first: this device just made that key.
        coVerify(exactly = 1) { h.sosRepository.resealOwn(setOf("c-1"), keysRead = true) }
        // No circle list read in this process: no name the new key may carry.
        assertNull(sent.captured.encryptedName)
    }

    @Test
    fun `Let them in admits them and hands them the circle, with no rotation`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        assertEquals(KeyAccessRequest.Reason.UNCONFIRMED_INVITER, request.reason)

        repository.grantKeyAccess(request, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        // An admission for exactly the keys the member list shows, signed here.
        val admission = h.statements.parse(admitted.getValue("newcomer").statement)!!
        assertEquals(MembershipStatements.Kind.ADMISSION, admission.kind)
        assertEquals(sharing("newcomer"), admission.subjectSharingKeyHex)
        assertEquals(signing("newcomer"), admission.subjectSigningKeyHex)
        // The current copy, to the newcomer, at this device's version.
        assertEquals(3, delivered.getValue("newcomer").keyVersion)
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        assertTrue(repository.keyAccessRequests.value.isEmpty())
        assertEquals(Origin.OWN, h.origins()["newcomer"])
    }

    /** "Let them in" binds exactly the keys behind the number shown. */
    @Test
    fun `the number shown for A, the list shows B before the tap - nothing is signed or pinned, and B's number is asked`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val shownForA = repository.keyAccessRequests.value.single()
        assertEquals(sharing("newcomer"), shownForA.sharingKeyHex)

        // The list moves to B before the tap.
        h.serverLists(roster = members("me", "alice") + listOf(h.rosterMember("mallory"),
            h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null, keysOf = "newcomer-b")))
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        repository.grantKeyAccess(shownForA, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        // (alice's one-time backfill admission is this owner phone's own business.)
        assertFalse(admitted.containsKey("newcomer"))
        assertFalse(delivered.containsKey("newcomer"))
        assertEquals(null, h.pins.sharing["newcomer"])
        assertEquals(null, h.origins()["newcomer"])
        val again = repository.keyAccessRequests.value.single()
        assertEquals(sharing("newcomer-b"), again.sharingKeyHex)
        assertEquals("number-of-newcomer", again.number)
    }

    @Test
    fun `the list swaps to B after Let them in, before the next pass - B is never pinned or handed the circle`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // The server swaps the newcomer's keys right after the answer.
        h.serverLists(roster = members("me", "alice") + listOf(h.rosterMember("mallory"),
            h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null, keysOf = "newcomer-b")))

        repository.grantKeyAccess(request, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()
        repository.refreshMembers("c-1")
        advanceUntilIdle()

        // A was pinned and admitted when answered; B only ever raises the key-change question.
        assertEquals(sharing("newcomer"), h.pins.sharing["newcomer"])
        assertEquals(sharing("newcomer"), h.statements.parse(admitted.getValue("newcomer").statement)!!.subjectSharingKeyHex)
        assertFalse(delivered.containsKey("newcomer"))
        verify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing("newcomer-b"), any(), any(), any()) }
        assertEquals(sharing("newcomer-b"), repository.memberKeyAlerts.value.single { it.userId == "newcomer" }.newSharingHex)
    }

    @Test
    fun `a let-in's re-check reads the judged roster - waiting, let in once answered, and a hidden member is never let in`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        h.statementsAccepted()

        assertEquals(CircleRepository.LetInNow.WAITING, repository.letInNow("c-1", "newcomer"))
        assertEquals("hidden, never asked about", CircleRepository.LetInNow.NOT_ASKED, repository.letInNow("c-1", "mallory"))

        repository.refreshMembers("c-1")
        advanceUntilIdle()
        repository.grantKeyAccess(repository.keyAccessRequests.value.single(), com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        assertEquals("this person's own choice", CircleRepository.LetInNow.CHOSEN_HERE, repository.letInNow("c-1", "newcomer"))
        assertEquals("confirmed otherwise", CircleRepository.LetInNow.LET_IN, repository.letInNow("c-1", "alice"))
    }

    @Test
    fun `Let them in whose admission gets no answer leaves no choice - a let-in still sees them waiting`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        // The admission never reaches the server.
        coEvery { h.tracksApi.storeMembershipStatement("c-1", any(), any()) } throws java.net.SocketTimeoutException()

        repository.grantKeyAccess(repository.keyAccessRequests.value.single(), com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        assertEquals(null, h.origins()["newcomer"])
        assertEquals(CircleRepository.LetInNow.WAITING, repository.letInNow("c-1", "newcomer"))
    }

    @Test
    fun `a waiting Let them in whose admission is stored records the choice and hands over, signing nothing`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // The earlier run's admission is stored: the server lists it with them.
        val stored = h.statements.statement(
            MembershipStatements.Kind.ADMISSION, "c-1", "me", signing("me"), "newcomer", sharing("newcomer"), signing("newcomer"),
        )
        h.serverLists(
            roster = members("me", "alice") + listOf(
                h.rosterMember("mallory"),
                h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), vouches = listOf(stored), keyVersion = null),
            )
        )
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        val signedBefore = h.statements.signed.size
        val run = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") }, admissionHeld = true)

        assertTrue(repository.grantKeyAccess(request, pendingRun = run).isSuccess)
        advanceUntilIdle()

        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue("no second admission", admitted.isEmpty())
        assertEquals("nothing signed", signedBefore, h.statements.signed.size)
        assertTrue("handed the circle, as after any Let them in", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a waiting Let them in - its admission first, then the choice read back, then the hand-over`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // What this device had chosen for them when each request left.
        val choiceAtAdmission = mutableListOf<Origin?>()
        coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } answers {
            choiceAtAdmission += h.origins()["newcomer"]
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.StoreStatementResponse(true), error = null))
        }
        val choiceAtDelivery = mutableListOf<Origin?>()
        coEvery { h.tracksApi.deliverAccess("c-1", "newcomer", any()) } answers {
            choiceAtDelivery += h.origins()["newcomer"]
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.DeliverAccessResponse(true, 3), error = null))
        }
        val saved = mutableListOf<com.pingolin.track.domain.pending.SignedStatement>()

        val result = repository.grantKeyAccess(request, pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { saved += it }))
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals("its id saved before it went", 1, saved.size)
        assertEquals("no choice while its admission went out", listOf<Origin?>(null), choiceAtAdmission)
        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertEquals("handed over once chosen", listOf<Origin?>(Origin.OWN), choiceAtDelivery)
    }

    @Test
    fun `a waiting Let them in whose admission isn't stored records no choice and hands nothing over`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        val failures = listOf<() -> Unit>(
            { coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } throws java.net.SocketTimeoutException() },
            { coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } returns Response.error(503, "{}".toResponseBody(null)) },
            { coEvery { h.tracksApi.storeMembershipStatement("c-1", "newcomer", any()) } returns Response.error(403, "{}".toResponseBody(null)) },
        )

        for (fail in failures) {
            fail()
            val result = repository.grantKeyAccess(request, pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
            advanceUntilIdle()
            assertTrue(result.isFailure)
        }

        assertEquals("no choice left behind", null, h.origins()["newcomer"])
        assertFalse("nothing handed over", delivered.containsKey("newcomer"))
    }

    @Test
    fun `a waiting Let them in whose choice doesn't reach the disk hands nothing over, and waits`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        h.approvalPrefs.failCommits = true

        val result = repository.grantKeyAccess(request, pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        assertTrue(result.exceptionOrNull() is com.pingolin.track.domain.pending.StatementNotSaved)
        assertTrue("its admission went out first: the next run finds it held", "newcomer" in admitted)
        assertFalse("nothing handed over", delivered.containsKey("newcomer"))
        h.approvalPrefs.failCommits = false
        assertTrue("not recorded", "newcomer" !in h.origins())
    }

    @Test
    fun `once a waiting Let them in records its choice, a rotation after their invitation ran out still includes them`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // Its own admission stored by an earlier run: this run only records the choice.
        val stored = h.statements.statement(
            MembershipStatements.Kind.ADMISSION, "c-1", "me", signing("me"), "newcomer", sharing("newcomer"), signing("newcomer"),
        )
        assertTrue(
            repository.grantKeyAccess(request, pendingRun = com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", admissionHeld = true)).isSuccess
        )
        advanceUntilIdle()
        // Later: the invitation ran out, the admission is all the server lists with them, and a
        // member removal rotates the key.
        h.serverLists(
            roster = members("me", "alice") + listOf(
                h.rosterMember("mallory"),
                h.rosterMember(
                    "newcomer", h.invitation("newcomer", by = "mallory", expiresAt = h.statements.now - 86_400),
                    vouches = listOf(stored), keyVersion = null,
                ),
            )
        )
        val sent = h.rotationAccepted()
        h.circle = h.circle.copy(keyRotationNeeded = true)

        repository.noteRotationCause("c-1", "member_removed")
        repository.autoRotateIfNeeded("c-1")

        assertTrue("delivered to after the rotation", "newcomer" in sent.captured.memberKeys.map { it.userId })
    }

    @Test
    fun `after a rotation that passed over them, their invitation run out - only the choice lets this device hand them the circle`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        // This device's own admission is stored and names them, at version 3; their invitation
        // ran out; then the circle moved to version 4 elsewhere, with no copy for them.
        val stored = h.statements.statement(
            MembershipStatements.Kind.ADMISSION, "c-1", "me", signing("me"), "newcomer", sharing("newcomer"), signing("newcomer"),
            atVersion = 3,
        )
        h.circleVersion = 4
        h.heldKeyVersion = 4
        h.serverLists(
            roster = members("me", "alice") + listOf(
                h.rosterMember("mallory"),
                h.rosterMember(
                    "newcomer", h.invitation("newcomer", by = "mallory", atVersion = 3, expiresAt = h.statements.now - 86_400),
                    vouches = listOf(stored), keyVersion = null,
                ),
            )
        )
        val delivered = h.deliveriesAccepted()

        repository.deliverOwedAccess("c-1")
        advanceUntilIdle()

        assertEquals("confirmed by the admission, never chosen here", Origin.STATEMENT, h.origins()["newcomer"])
        assertFalse("without the choice nobody here hands it over", delivered.containsKey("newcomer"))

        assertTrue(repository.recordLetInChoice("c-1", "newcomer"))
        advanceUntilIdle()

        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertEquals("with it, this device does", 4, delivered.getValue("newcomer").keyVersion)
    }

    private fun executorsOver(repo: CircleRepository) = com.pingolin.track.domain.pending.PendingExecutors(
        io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), { repo },
        io.mockk.mockk(), io.mockk.mockk(), h.sessionManager,
        { io.mockk.mockk<AuthRepository> { io.mockk.every { signingKeyMissing() } returns false } },
    )

    private val letInInputs = com.pingolin.track.domain.pending.PendingInputs.LetIn(
        "Family", "newcomer", "Newcomer", "UNCONFIRMED_INVITER", sharing("newcomer"), signing("newcomer"),
    )

    private val letInChange = com.pingolin.track.data.local.entity.PendingChangeEntity(
        id = "ch-1", account = "me", circleId = "c-1", kind = com.pingolin.track.domain.pending.PendingKind.LET_IN.name,
        inputs = com.pingolin.track.domain.pending.PendingInputs.toJson(letInInputs), createdAt = 0, expiresAt = Long.MAX_VALUE,
        state = com.pingolin.track.domain.pending.PendingChanges.RUNNING,
    )

    /** Its own admission stored, the app restarted: nothing is cached for the circle. */
    private fun admissionHeldAfterARestart(listed: Boolean = true) {
        h.circle = h.circle.copy(keyRotationNeeded = false)
        val stored = h.statements.statement(
            MembershipStatements.Kind.ADMISSION, "c-1", "me", signing("me"), "newcomer", sharing("newcomer"), signing("newcomer"),
            atVersion = 3,
        )
        h.serverLists(
            roster = members("me", "alice") + h.rosterMember("mallory") + listOfNotNull(
                h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), vouches = listOf(stored), keyVersion = null)
                    .takeIf { listed }
            )
        )
    }

    @Test
    fun `after a restart, a let-in's run with its admission held reads the complete roster and records the choice`() = runTest {
        deliverHere()
        admissionHeldAfterARestart()
        val delivered = h.deliveriesAccepted()
        val executors = executorsOver(repository)

        val outcome = executors.forKind(com.pingolin.track.domain.pending.PendingKind.LET_IN)!!.run(
            letInChange, letInInputs,
            com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { error("nothing signed") }, admissionHeld = true),
        )
        advanceUntilIdle()

        assertEquals(com.pingolin.track.domain.pending.PendingOutcome.Done(), outcome)
        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue("handed the circle", delivered.containsKey("newcomer"))
    }

    @Test
    fun `after a restart, an end with its admission held records the choice from the complete roster - never without one`() = runTest {
        deliverHere()
        admissionHeldAfterARestart()
        val delivered = h.deliveriesAccepted()
        val executors = executorsOver(repository)

        // No complete roster: it waits, nothing recorded.
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), any()) } returns Response.error(503, "{}".toResponseBody(null))
        assertFalse(executors.recordLetIn(letInChange))
        assertEquals(null, h.origins()["newcomer"])

        // They left: nobody to choose, and it ends with no choice.
        admissionHeldAfterARestart(listed = false)
        assertTrue(executors.recordLetIn(letInChange))
        assertEquals(null, h.origins()["newcomer"])

        // Listed: the choice, and the hand-over.
        admissionHeldAfterARestart()
        assertTrue(executors.recordLetIn(letInChange))
        advanceUntilIdle()
        assertEquals(Origin.OWN, h.origins()["newcomer"])
        assertTrue(delivered.containsKey("newcomer"))
    }

    @Test
    fun `the list swaps to B while Let them in is being signed - the admission still binds A`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // The server swaps the newcomer's keys while the admission is being made: the first
        // look at the circle inside it (whether this phone is current) takes the new list.
        var armed = true
        coEvery { h.circleDao.getById("c-1") } coAnswers {
            if (armed) {
                armed = false
                h.serverLists(roster = members("me", "alice") + listOf(h.rosterMember("mallory"),
                    h.rosterMember("newcomer", h.invitation("newcomer", by = "mallory"), keyVersion = null, keysOf = "newcomer-b")))
                repository.refreshMembers("c-1")
            }
            h.circle.copy(keyVersion = h.circleVersion)
        }

        repository.grantKeyAccess(request, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        assertFalse("the swap never happened", armed)
        assertEquals(sharing("newcomer"), h.statements.parse(admitted.getValue("newcomer").statement)!!.subjectSharingKeyHex)
        assertEquals(signing("newcomer"), h.statements.parse(admitted.getValue("newcomer").statement)!!.subjectSigningKeyHex)
    }

    @Test
    fun `a pin that doesn't take stops Let them in - nothing approved, signed or handed over`() = runTest {
        deliverHere()
        h.circle = h.circle.copy(keyRotationNeeded = false)
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        val admitted = h.statementsAccepted()
        val delivered = h.deliveriesAccepted()
        repository.refreshMembers("c-1")
        advanceUntilIdle()
        val request = repository.keyAccessRequests.value.single()
        // By the tap this phone holds other keys for them: its pins refuse the question's.
        h.pins.pin("newcomer", sharing("newcomer-old"), signing("newcomer-old"))

        repository.grantKeyAccess(request, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        assertFalse(admitted.containsKey("newcomer"))
        assertFalse(delivered.containsKey("newcomer"))
        assertEquals(null, h.origins()["newcomer"])
        assertEquals(sharing("newcomer-old"), h.pins.sharing["newcomer"])
    }

    @Test
    fun `Not now leaves them out and does not ask again this session`() = runTest {
        deliverHere()
        h.serverLists(roster = members("me") + invitedByUnconfirmed())
        h.rotationAccepted()
        repository.autoRotateIfNeeded("c-1")
        repository.deferKeyAccess(repository.keyAccessRequests.value.single())

        repository.refreshMembers("c-1")
        advanceUntilIdle()

        assertTrue(repository.keyAccessRequests.value.isEmpty())
        assertTrue("newcomer" !in h.approvals.approved("c-1"))
    }

    @Test
    fun `a keyless join is never rotated for, and nobody unconfirmed is handed anything`() = runTest {
        deliverHere()
        repository.noteRotationCause("c-1", "member_joined")
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        h.rotationAccepted()
        h.deliveriesAccepted()

        repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.deliverAccess(any(), any(), any()) }
        assertEquals("newcomer", repository.keyAccessRequests.value.single().userId)
    }

    @Test
    fun `a member list that cannot be fetched stops the rotation`() = runTest {
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } returns
            Response.success(ApiResponse(success = true, data = listOf(h.key("me"), h.key("alice")), error = null))
        coEvery { h.tracksApi.getCircleMembers("c-1", any(), any()) } throws RuntimeException("offline")

        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `removing a member goes ahead around a survivor whose key change is not accepted`() = runTest {
        // Failing the removal here would leave the person being removed holding the
        // live key.
        deliverHere()
        h.pins.pin("bob", sharing("bob-old"), signing("bob-old"))
        h.serverLists(roster = members("me", "alice", "bob", "eve"))
        val sent = slot<EjectAndRotateRequest>()
        coEvery { h.tracksApi.ejectAndRotate("c-1", capture(sent)) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )

        val result = repository.ejectMember("c-1", "eve")

        assertTrue(result.isSuccess)
        assertEquals(setOf("me", "alice"), sent.captured.memberWraps.map { it.userId }.toSet())
        assertEquals(listOf("bob"), sent.captured.pendingMembers)
        assertEquals("eve", sent.captured.ejectUserId)
    }

    @Test
    fun `a removal whose member list failed to load for a moment goes through without anyone trying again`() = runTest {
        // Read again while it fails for a reason that passes.
        deliverHere()
        h.serverLists(roster = members("me", "alice", "eve"))
        var reads = 0
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } answers {
            if (++reads == 1) throw java.io.IOException("offline")
            else Response.success(ApiResponse(success = true, data = listOf(h.key("me"), h.key("alice"), h.key("eve")), error = null))
        }
        val sent = slot<EjectAndRotateRequest>()
        coEvery { h.tracksApi.ejectAndRotate("c-1", capture(sent)) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        assertEquals(2, reads)
        assertEquals(setOf("me", "alice"), sent.captured.memberWraps.map { it.userId }.toSet())
    }

    @Test
    fun `a removal refused for a reason that does not pass is reported at once`() = runTest {
        deliverHere()
        h.serverLists(roster = members("me", "alice", "eve"))
        coEvery { h.tracksApi.getMemberPublicKeys("c-1") } returns
            Response.error(403, """{"success":false}""".toResponseBody(null))

        assertTrue(repository.ejectMember("c-1", "eve").isFailure)
        coVerify(exactly = 1) { h.tracksApi.getMemberPublicKeys("c-1") }
    }

    // ---- SDK call order ---------------------

    @Test
    fun `pins of anyone not confirmed are retired before the new key is made`() = runTest {
        deliverHere()
        h.serverLists(roster = members("me", "alice") + h.rosterMember("ghost"))
        h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        // This owner's device writes the record-less circle's first record.
        io.mockk.verifyOrder {
            h.cryptoManager.retainMemberPins("c-1", listOf("me", "alice"))
            h.cryptoManager.prepareRecordedRotation("c-1", any(), uniffi.pingolin_mobile.RefreshRecordKind.TRANSITION, any(), any(), any(), any())
        }
    }

    @Test
    fun `a rotation the server did not take is discarded`() = runTest {
        h.serverLists(roster = members("me", "alice"))
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns
            Response.error(500, """{"success":false}""".toResponseBody(null))

        repository.autoRotateIfNeeded("c-1")

        verify { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `a rotation that throws on the way is discarded`() = runTest {
        h.serverLists(roster = members("me", "alice"))
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } throws java.io.IOException("reset")

        repository.autoRotateIfNeeded("c-1")

        verify { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `an accepted rotation is not discarded`() = runTest {
        h.serverLists(roster = members("me", "alice"))
        h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a removal the server refused is discarded`() = runTest {
        h.serverLists(roster = members("me", "alice", "eve"))
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns
            Response.error(409, """{"success":false,"error_code":"KEY_VERSION_MISMATCH"}""".toResponseBody(null))

        assertTrue(repository.ejectMember("c-1", "eve").isFailure)

        verify { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }

    @Test
    fun `removing a member retires their pin before the new key is made`() = runTest {
        // Until the eject lands they are still on the roster, so still confirmed and pinned;
        // a pinned member could sign a wrap for the version the removal creates.
        h.serverLists(roster = members("me", "alice", "eve"))
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )

        repository.ejectMember("c-1", "eve")

        // This owner's eject of a record-less circle writes its first record.
        io.mockk.verifyOrder {
            h.cryptoManager.retainMemberPins("c-1", listOf("me", "alice"))
            h.cryptoManager.prepareRecordedRotation("c-1", any(), uniffi.pingolin_mobile.RefreshRecordKind.TRANSITION, any(), any(), any(), any())
        }
    }

    // ---- an unanswered join is not re-fetched on every refresh -------------------

    private fun keylessJoinNobodyConfirms() {
        repository.noteRotationCause("c-1", "member_joined")
        h.serverLists(roster = members("me", "alice") + invitedByUnconfirmed())
        h.rotationAccepted()
        h.deliveriesAccepted()
    }

    @Test
    fun `a keyless join nobody confirms is not re-fetched on every refresh`() = runTest {
        // The server keeps the circle flagged until somebody lets them in, and each look
        // costs a full member list — every 5 minutes, for days, on every member's phone.
        deliverHere()
        keylessJoinNobodyConfirms()

        repository.autoRotateIfNeeded("c-1")
        repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        coVerify(exactly = 1) { h.tracksApi.getCircleMembers("c-1", any(), any()) }
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
    }

    @Test
    fun `a departure is re-planned at once, even while a join waits for an answer`() = runTest {
        deliverHere()
        keylessJoinNobodyConfirms()
        repository.autoRotateIfNeeded("c-1")

        repository.noteRotationCause("c-1", "member_removed")
        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys("c-1", any()) }
    }

    @Test
    fun `Let them in delivers at once, whatever the memo says`() = runTest {
        deliverHere()
        keylessJoinNobodyConfirms()
        h.statementsAccepted()
        repository.autoRotateIfNeeded("c-1")
        val request = repository.keyAccessRequests.value.single()

        repository.grantKeyAccess(request, com.pingolin.track.domain.pending.PendingRun("ch-1", "ch-1", onSigned = { }))
        advanceUntilIdle()

        coVerify(exactly = 1) { h.tracksApi.deliverAccess("c-1", "newcomer", any()) }
    }

    // ---- who is trusted with the key: this device's confirmation only --------------

    @Test
    fun `a ghost the server says holds the current key is left out and counted, never asked about`() = runTest {
        // The server writes the member list and every key_version on it: a claimed
        // current wrap is no reason to hand over the next key.
        deliverHere()
        h.serverLists(roster = members("me", "alice", "ghost"))
        val sent = h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(listOf("ghost"), sent.captured.pendingMembers)
        // Nothing vouches for them: never asked about, counted as kept out.
        assertTrue(repository.keyAccessRequests.value.isEmpty())
        assertEquals(1, repository.memberStates.value["c-1"]?.keptOut)
        // Not shown, either.
        assertTrue(h.storedRows.none { it.userId == "ghost" })
    }

    @Test
    fun `a removal does not wrap to a ghost the server says holds the current key`() = runTest {
        deliverHere()
        h.serverLists(roster = members("me", "alice", "eve", "ghost"))
        val sent = slot<EjectAndRotateRequest>()
        coEvery { h.tracksApi.ejectAndRotate("c-1", capture(sent)) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)

        assertEquals(setOf("me", "alice"), sent.captured.memberWraps.map { it.userId }.toSet())
        assertEquals(listOf("ghost"), sent.captured.pendingMembers)
    }

    @Test
    fun `a device that first sees a circle now trusts nobody for being listed`() = runTest {
        deliverHere()
        h.approvals.wipeAll() // a fresh install: no seed from before signed invitations
        h.pins.sharing.clear(); h.pins.signing.clear()
        h.serverLists(roster = members("me", "alice", "bob"))
        val first = h.rotationAccepted()

        repository.noteRotationCause("c-1", "member_removed")
        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me"), first.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(setOf("alice", "bob"), first.captured.pendingMembers.orEmpty().toSet())
        assertEquals(setOf("me"), h.storedRows.map { it.userId }.toSet())
    }

    @Test
    fun `a member trusted in another circle, listed with the keys pinned for them, starts a circle first seen now`() = runTest {
        deliverHere()
        h.approvals.wipeAll()
        h.pins.sharing.clear(); h.pins.signing.clear()
        // alice is trusted in another circle here; bob is only pinned (as builds before
        // signed invitations pinned everyone they were shown), which is no trust at all.
        h.approvals.approve("c-2", listOf("alice"), Origin.STATEMENT)
        h.pins.pin("alice", sharing("alice"), signing("alice"))
        h.pins.pin("bob", sharing("bob"), signing("bob"))
        h.serverLists(roster = members("me", "alice", "bob"))
        val sent = h.rotationAccepted()

        repository.noteRotationCause("c-1", "member_removed")
        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(Origin.ANCHOR, h.origins()["alice"])
        assertTrue("bob" !in h.origins())
    }

    private fun inviteGoesThrough(email: String, lookup: UserPublicKeyResponse?): io.mockk.CapturingSlot<InviteRequest> {
        coEvery { h.tracksApi.lookupPublicKey(email) } returns
            if (lookup != null) Response.success(ApiResponse(success = true, data = lookup, error = null))
            else Response.error(404, """{"success":false}""".toResponseBody(null))
        val sent = slot<InviteRequest>()
        coEvery { h.tracksApi.inviteToCircle("c-1", capture(sent)) } returns Response.success(
            ApiResponse(
                success = true,
                data = InviteResponse(delivery = "notified"),
                error = null,
            )
        )
        return sent
    }

    @Test
    fun `an invitation is signed for the account and keys the server named, and they are handed the circle`() = runTest {
        deliverHere()
        // A circle with records: a joiner is handed the current key, with no rotation (a
        // record-less one would get its first record from this owner's device at once).
        h.recordedCircle()
        val sent = inviteGoesThrough("dan@x.test", UserPublicKeyResponse("dan@x.test", sharing(dan), userId = dan, signingPublicKey = signing(dan)))
        assertTrue(repository.inviteToCircle("c-1", "dan@x.test").isSuccess)

        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals(MembershipStatements.Kind.INVITATION_TO_ACCOUNT, statement.kind)
        assertEquals(dan, statement.subjectUserId)
        assertEquals(sharing(dan), statement.subjectSharingKeyHex)
        assertEquals(signing(dan), statement.subjectSigningKeyHex)
        assertTrue(h.statements.verify(sent.captured.statement!!, sent.captured.statementSignature!!, signing("me")))

        // Dan accepts: the server copies the invitation onto his row.
        repository.noteRotationCause("c-1", "member_joined")
        val invitation = MembershipStatements.Signed(sent.captured.statement!!, sent.captured.statementSignature!!)
        h.serverLists(roster = members("me", "alice") + h.rosterMember(dan, invitation, keyVersion = null))
        val delivered = h.deliveriesAccepted()

        repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(3, delivered.getValue(dan).keyVersion)
        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        assertTrue(repository.keyAccessRequests.value.isEmpty())
    }

    @Test
    fun `an invitee whose account changed is asked about with their number, and It's them sends the invitation`() = runTest {
        deliverHere()
        // bob was in a circle with this user before; since then his keys changed.
        h.pins.pin(bob, sharing("bob-old"), signing("bob-old"))
        val sent = inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))

        val change = repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() as InviteeChangedException
        assertEquals("number-of-$bob", change.number)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())

        assertTrue(repository.confirmInviteeAndInvite(change).isSuccess)

        // This device alone now trusts his new details, and the invitation binds them.
        verify { h.cryptoManager.approveMemberKeyChange("c-1", bob, sharing(bob), signing(bob)) }
        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals(sharing(bob), statement.subjectSharingKeyHex)
        assertEquals(signing(bob), statement.subjectSigningKeyHex)
    }

    @Test
    fun `It's them about one account, and the address names another by then - nothing pinned, signed or sent`() = runTest {
        deliverHere()
        h.pins.pin(bob, sharing("bob-old"), signing("bob-old"))
        inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))
        val change = repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() as InviteeChangedException
        // By the answer, the server names another account for the address.
        inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(mallory), userId = mallory, signingPublicKey = signing(mallory)))

        val refused = repository.confirmInviteeAndInvite(change).exceptionOrNull()

        assertTrue(refused is InviteeNotAsConfirmedException)
        assertEquals("Their details keep changing, so bob@x.test wasn't invited.", refused!!.message)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())
        assertTrue("mallory not pinned", h.pins.sharing[mallory] == null)
        verify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull("c-1", sharing(mallory), any(), any(), any()) }
    }

    @Test
    fun `It's them, and the address names no account by then - nothing sent to the email instead`() = runTest {
        deliverHere()
        h.pins.pin(bob, sharing("bob-old"), signing("bob-old"))
        inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))
        val change = repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() as InviteeChangedException
        inviteGoesThrough("bob@x.test", null)

        assertTrue(repository.confirmInviteeAndInvite(change).exceptionOrNull() is InviteeNotAsConfirmedException)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())
    }

    @Test
    fun `It's them, and the lookup doesn't answer - nothing goes out unsigned, it can be tried again`() = runTest {
        deliverHere()
        h.pins.pin(bob, sharing("bob-old"), signing("bob-old"))
        inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))
        val change = repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() as InviteeChangedException
        coEvery { h.tracksApi.lookupPublicKey("bob@x.test") } returns Response.error(503, """{"success":false}""".toResponseBody(null))

        assertEquals("Couldn't check bob@x.test just now.", repository.confirmInviteeAndInvite(change).exceptionOrNull()?.message)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
    }

    @Test
    fun `an invitee whose signing key alone changed is asked about too, and nothing is signed, wrapped or sent`() = runTest {
        deliverHere()
        // The sharing key the server shows is the one pinned for bob; the signing key is
        // not. The invitation would bind a key this device never saw for him.
        h.pins.pin(bob, sharing(bob), signing("bob-old"))
        val sent = inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))

        val change = repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() as InviteeChangedException
        // What the Circles screen asks: "Is this really bob@x.test?", with his number.
        assertEquals("bob@x.test", change.email)
        assertEquals(signing(bob), change.signingKeyHex)
        assertEquals("number-of-$bob", change.number)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        verify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull(any(), any(), any(), any(), any()) }
        assertTrue(h.statements.signed.isEmpty())

        // "It's them" accepts both new keys here, and the invitation binds them.
        assertTrue(repository.confirmInviteeAndInvite(change).isSuccess)
        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals(sharing(bob), statement.subjectSharingKeyHex)
        assertEquals(signing(bob), statement.subjectSigningKeyHex)
    }

    @Test
    fun `a pin with no signing key does not vouch for the one an invitation would bind`() = runTest {
        deliverHere()
        // bob's pin holds a sharing key and no signing key.
        h.pins.sharing[bob] = sharing(bob)
        h.pins.signing.remove(bob)
        inviteGoesThrough("bob@x.test", UserPublicKeyResponse("bob@x.test", sharing(bob), userId = bob, signingPublicKey = signing(bob)))

        assertTrue(repository.inviteToCircle("c-1", "bob@x.test").exceptionOrNull() is InviteeChangedException)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())
    }

    @Test
    fun `somebody invited before they had an account is handed the circle too, receive-only and never pinned`() = runTest {
        deliverHere()
        // A circle with records: a joiner is handed the current key, with no rotation.
        h.recordedCircle()
        val sent = inviteGoesThrough("Erin@X.test", lookup = null)
        assertTrue(repository.inviteToCircle("c-1", "Erin@X.test").isSuccess)
        val statement = h.statements.parse(sent.captured.statement!!)!!
        assertEquals(MembershipStatements.Kind.INVITATION_TO_EMAIL, statement.kind)
        assertEquals("erin@x.test", statement.subjectEmail)

        // She signs up and accepts: the server lists her with the email it stored, and
        // with the invitation.
        val invitation = MembershipStatements.Signed(sent.captured.statement!!, sent.captured.statementSignature!!)
        repository.noteRotationCause("c-1", "member_joined")
        h.serverLists(
            roster = members("me", "alice") + h.rosterMember("erin", invitation, keyVersion = null),
            keys = listOf(h.key("me"), h.key("alice"), h.key("erin", keyVersion = null, email = "erin@x.test")),
        )
        val delivered = h.deliveriesAccepted()

        repository.autoRotateIfNeeded("c-1")
        advanceUntilIdle()

        assertEquals(3, delivered.getValue("erin").keyVersion)
        assertTrue(repository.keyAccessRequests.value.isEmpty())
        // Her keys are this device's record, never an SDK pin.
        verify(exactly = 0) { h.cryptoManager.pinMemberKeys(any(), "erin", any(), any()) }
        assertEquals(sharing("erin") to signing("erin"), h.approvals.receiveOnlyKeys("c-1")["erin"])
    }

    @Test
    fun `a member who left and comes back with no new invitation is left out, and never asked about`() = runTest {
        deliverHere()
        // eve leaves: the next complete member list takes her approval with her.
        h.serverLists(roster = members("me", "alice"))
        assertTrue(repository.refreshMembers("c-1").isSuccess)
        assertTrue("eve" !in h.approvals.approved("c-1"))

        // Back again, and (the server says) still holding an old wrap.
        h.serverLists(roster = members("me", "alice") + h.rosterMember("eve", keyVersion = 2))
        val sent = h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertTrue(repository.keyAccessRequests.value.isEmpty())
        assertEquals(1, repository.memberStates.value["c-1"]?.keptOut)
    }

    @Test
    fun `a member this user removed is not wrapped to if the server keeps listing them`() = runTest {
        deliverHere()
        h.serverLists(roster = members("me", "alice", "eve"))
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )
        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        // A records read judges that eject's own record here, so the next change may build.
        coEvery { h.tracksApi.refreshRecords(any()) } returns Response.success(ApiResponse(
            success = true,
            data = com.pingolin.track.data.remote.dto.RefreshRecordsResponse(
                listOf(com.pingolin.track.data.remote.dto.CircleRefreshRecords("c-1", listOf(com.pingolin.track.data.remote.dto.ServedRefreshRecord(4, "r4", "s4")), complete = true)),
                more = false,
            ),
            error = null,
        ))
        coEvery { h.tracksApi.putCircleHead("c-1", any()) } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.PutHeadResponse(true), error = null))
        coEvery { h.tracksApi.requestAccess("c-1") } returns
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        repository.syncRefreshRecords()

        // The server "forgets" the removal.
        h.circleVersion = 4
        h.heldKeyVersion = 4
        h.serverLists(roster = members("me", "alice", "eve"))
        val sent = h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        assertEquals(setOf("me", "alice"), sent.captured.memberKeys.map { it.userId }.toSet())
        assertEquals(listOf("eve"), sent.captured.pendingMembers)
    }

    // ---- a device the key moved on without (sealing) -------------------------------

    @Test
    fun `a device behind on the key leaves the re-key to one that holds it`() = runTest {
        h.heldKeyVersion = 2 // the circle is at v3
        h.serverLists(roster = members("me", "alice"))
        h.rotationAccepted()

        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 0) { h.tracksApi.rotateCircleKeys(any(), any()) }
        verify(exactly = 0) { h.cryptoManager.prepareKeyRotation(any(), any(), any(), any(), any(), any()) }
        assertEquals(setOf("c-1"), repository.behindCircles.value)
    }

    @Test
    fun `a device behind on the key cannot remove a member`() = runTest {
        h.heldKeyVersion = 2
        h.serverLists(roster = members("me", "alice", "eve"))

        val result = repository.ejectMember("c-1", "eve")

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        coVerify(exactly = 0) { h.tracksApi.ejectAndRotate(any(), any()) }
    }

    @Test
    fun `a device behind on the key sends no invitation, signed or not`() = runTest {
        h.heldKeyVersion = 2
        inviteGoesThrough("dan@x.test", UserPublicKeyResponse("dan@x.test", sharing(dan), userId = dan, signingPublicKey = signing(dan)))

        val result = repository.inviteToCircle("c-1", "dan@x.test")

        assertTrue(result.exceptionOrNull() is KeyNotCurrentException)
        coVerify(exactly = 0) { h.tracksApi.inviteToCircle(any(), any()) }
        assertTrue(h.statements.signed.isEmpty())
        verify(exactly = 0) { h.cryptoManager.wrapCircleKeyForMemberFull(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `an invitation from a phone behind on the circle waits for it to catch up, then goes out`() = runTest {
        // Instead of "try again once synced", ask the members for the current copy and
        // look for it while the person waits. A member's app hands it over.
        h.heldKeyVersion = 2
        coEvery { h.tracksApi.requestAccess("c-1") } answers {
            h.heldKeyVersion = 3
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        }
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = emptyList(), error = null))
        val sent = inviteGoesThrough("dan@x.test", UserPublicKeyResponse("dan@x.test", sharing(dan), userId = dan, signingPublicKey = signing(dan)))

        assertTrue(repository.inviteToCircle("c-1", "dan@x.test").isSuccess)

        coVerify(exactly = 1) { h.tracksApi.requestAccess("c-1") }
        // Signed only once it holds the current version.
        assertEquals(MembershipStatements.Kind.INVITATION_TO_ACCOUNT, h.statements.parse(sent.captured.statement!!)!!.kind)
    }

    @Test
    fun `a removal from a phone behind on the circle waits for it to catch up, then goes through`() = runTest {
        h.heldKeyVersion = 2
        h.serverLists(roster = members("me", "alice", "eve"))
        coEvery { h.tracksApi.requestAccess("c-1") } answers {
            h.heldKeyVersion = 3
            Response.success(ApiResponse(success = true, data = com.pingolin.track.data.remote.dto.AccessRequestResponse(true), error = null))
        }
        coEvery { h.tracksApi.getCircleKeys() } returns Response.success(ApiResponse(success = true, data = emptyList(), error = null))
        coEvery { h.tracksApi.ejectAndRotate("c-1", any()) } returns Response.success(
            ApiResponse(success = true, data = EjectAndRotateResponse(newKeyVersion = 4, ejectedUserId = "eve"), error = null)
        )

        assertTrue(repository.ejectMember("c-1", "eve").isSuccess)
        coVerify(exactly = 1) { h.tracksApi.ejectAndRotate("c-1", any()) }
    }

    @Test
    fun `an invitation the server refuses as signed at an old version reads as waiting to sync`() = runTest {
        // The circle moved on since this device last listed it (the server takes only
        // its current version).
        inviteGoesThrough("dan@x.test", UserPublicKeyResponse("dan@x.test", sharing(dan), userId = dan, signingPublicKey = signing(dan)))
        coEvery { h.tracksApi.inviteToCircle("c-1", any()) } returns Response.error(
            400,
            """{"success":false,"error":"Invalid invitation statement: at_version is not this circle's current key version","error_code":"invite_invalid_statement"}"""
                .toResponseBody(null),
        )

        assertTrue(repository.inviteToCircle("c-1", "dan@x.test").exceptionOrNull() is KeyNotCurrentException)
    }

    // ---- rotate-keys' rate limit (the server: one rotation per circle every 30 s) ---

    @Test
    fun `a rate-limited rotation waits out the limit once and goes through`() = runTest {
        h.serverLists(roster = members("me", "alice"))
        val sent = mutableListOf<RotateKeysRequest>()
        coEvery { h.tracksApi.rotateCircleKeys("c-1", capture(sent)) } returnsMany listOf(
            Response.error(
                429,
                """{"success":false,"error":"Key rotation rate limit: try again in 12s"}""".toResponseBody(null),
            ),
            Response.success(ApiResponse(success = true, data = RotateKeysResponse(newKeyVersion = 4), error = null)),
        )

        repository.autoRotateIfNeeded("c-1")

        assertEquals(2, sent.size)
        assertEquals(setOf("me", "alice"), sent.last().memberKeys.map { it.userId }.toSet())
        verify(exactly = 0) { h.cryptoManager.discardUnconfirmedRotation(any()) }
    }

    @Test
    fun `a rotation refused for another reason is not retried`() = runTest {
        h.serverLists(roster = members("me", "alice"))
        coEvery { h.tracksApi.rotateCircleKeys("c-1", any()) } returns
            Response.error(400, """{"success":false,"error_code":"key_missing_members"}""".toResponseBody(null))

        repository.autoRotateIfNeeded("c-1")

        coVerify(exactly = 1) { h.tracksApi.rotateCircleKeys(any(), any()) }
        verify { h.cryptoManager.discardUnconfirmedRotation("c-1") }
    }
}
