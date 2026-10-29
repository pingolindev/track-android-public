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

package com.pingolin.track.domain.pending

import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.CircleMemberDao
import com.pingolin.track.data.local.entity.CircleEntity
import com.pingolin.track.testutil.InMemoryPendingChangeDao
import com.pingolin.track.util.SessionManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeping a change waiting to sync: inputs only, one change per target, the caps, and
 * Cancel.
 */
class PendingChangesTest {

    private val dao = InMemoryPendingChangeDao()
    private var account = "me"
    private val sessionManager = mockk<SessionManager> { every { getUserId() } answers { account } }
    private val scheduler = mockk<PendingChangeScheduler>(relaxed = true)
    private val circleDao = mockk<CircleDao> {
        coEvery { getById(any()) } answers {
            CircleEntity(id = arg(0), name = "Family", encryptedKey = null, createdAt = 0, updatedAt = 0)
        }
    }
    private val store = PendingChanges(dao, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))
        .also { it.now = { clock } }
    private var clock = 1_000_000L

    private fun checkIn(message: String? = "home", at: Long = clock) =
        PendingInputs.CheckIn("Family", "safe_arrival", message, 51.5, -0.12, null, at)

    @Test
    fun `a change is kept as its inputs only, with its line, its age limit and a run scheduled`() = runTest {
        val result = store.queue<Unit>("c-1", checkIn())

        val queued = result.exceptionOrNull() as QueuedForSync
        assertEquals(PendingLines.CHECK_IN, queued.message)
        val kept = dao.changes.value.single()
        assertEquals(queued.changeId, kept.id)
        assertEquals("me", kept.account)
        assertEquals(PendingKind.CHECK_IN.name, kept.kind)
        assertEquals(PendingChanges.WAITING, kept.state)
        assertEquals(clock + PendingKind.DAY_MS, kept.expiresAt)
        assertEquals("", kept.signedIds)
        assertEquals(checkIn(), PendingInputs.read(kept))
        verify { scheduler.kick() }
    }

    @Test
    fun `one change per target, keeping its id and place in the queue (a share of the same place)`() = runTest {
        store.queue<Unit>("c-1", PendingInputs.SharePlace("Family", "p-1", "Home"))
        val first = dao.changes.value.single()
        clock += 60_000
        val again = store.queue<Unit>("c-1", PendingInputs.SharePlace("Family", "p-1", "Home, renamed")).exceptionOrNull() as QueuedForSync

        val kept = dao.changes.value.single()
        assertEquals(first.id, kept.id)
        assertEquals(first.createdAt, kept.createdAt)
        assertEquals("never gone out: the new tap restarts the age limit", clock + 7 * PendingKind.DAY_MS, kept.expiresAt)
        assertTrue("went into the change already waiting", !again.created)
        assertEquals("Home, renamed", (PendingInputs.read(kept) as PendingInputs.SharePlace).placeName)
        // Another circle is another target.
        store.queue<Unit>("c-2", PendingInputs.SharePlace("Friends", "p-1", "Home"))
        assertEquals(2, dao.changes.value.size)
    }

    @Test
    fun `a change that may have gone out keeps its own age limit when a tap merges into it`() = runTest {
        store.queue<Unit>("c-1", PendingInputs.SharePlace("Family", "p-1", "Home"))
        val first = dao.changes.value.single()
        dao.update(first.copy(lastOutcome = PendingChanges.LOST_ANSWER))
        clock += 6 * PendingKind.DAY_MS
        store.queue<Unit>("c-1", PendingInputs.SharePlace("Family", "p-1", "Home, renamed"))

        assertEquals(first.expiresAt, dao.changes.value.single().expiresAt)
    }

    @Test
    fun `a change with a saved statement id keeps its own age limit and its ids when a tap merges into it`() = runTest {
        store.queue<Unit>("c-1", PendingInputs.Invite("Family", "dan@x.test"))
        val statement = SignedStatement("00000000-0000-4000-8000-000000000001", at = clock)
        val first = PendingChanges.withSigned(dao.changes.value.single(), statement)
        dao.update(first)
        clock += 6 * PendingKind.DAY_MS
        store.queue<Unit>("c-1", PendingInputs.Invite("Family", "dan@x.test"))

        val merged = dao.changes.value.single()
        assertEquals(first.expiresAt, merged.expiresAt)
        assertEquals(listOf(statement), PendingChanges.signed(merged))
    }

    @Test
    fun `a change a save kept is dropped again with no line, but never once a run has tried it`() = runTest {
        val kept = store.queue<Unit>("c-1", checkIn()).exceptionOrNull() as QueuedForSync
        assertTrue(kept.created)
        assertTrue(store.discard(kept.changeId))
        assertTrue(dao.changes.value.isEmpty())
        assertTrue(dao.notices.value.isEmpty())

        clock += 3_000
        val tried = (store.queue<Unit>("c-1", checkIn(message = "x", at = clock)).exceptionOrNull() as QueuedForSync).changeId
        dao.update(dao.get(tried)!!.copy(attempts = 1, lastOutcome = PendingChangeRunner.NOT_SENT))
        assertTrue(!store.discard(tried))
        assertEquals(1, dao.changes.value.size)
    }

    @Test
    fun `a place edit merges its fields into the one already waiting`() = runTest {
        store.queue<Unit>("c-1", PendingInputs.EditPlace("Family", "p-1", "Home", name = "House"))
        store.queue<Unit>("c-1", PendingInputs.EditPlace("Family", "p-1", "House", radius = 250))

        val kept = PendingInputs.read(dao.changes.value.single()) as PendingInputs.EditPlace
        assertEquals("House", kept.name)
        assertEquals(250, kept.radius)
    }

    @Test
    fun `two identical taps within two seconds are one check-in, later ones are another`() = runTest {
        store.queue<Unit>("c-1", checkIn())
        clock += 1_500
        store.queue<Unit>("c-1", checkIn(at = clock))
        assertEquals(1, dao.changes.value.size)

        clock += 2_500
        store.queue<Unit>("c-1", checkIn(at = clock))
        assertEquals(2, dao.changes.value.size)

        // Not identical: another message.
        store.queue<Unit>("c-1", checkIn(message = "work", at = clock))
        assertEquals(3, dao.changes.value.size)
    }

    @Test
    fun `at 20 changes in a circle the action is refused as too many and nothing is kept`() = runTest {
        repeat(PendingChanges.MAX_PER_CIRCLE) { i ->
            clock += 3_000
            store.queue<Unit>("c-1", checkIn(message = "m$i", at = clock))
        }
        clock += 3_000
        val refused = store.queue<Unit>("c-1", checkIn(message = "one more", at = clock)).exceptionOrNull()

        assertTrue(refused !is QueuedForSync)
        assertEquals("Not done: 20 changes are already waiting to go out for Family from this phone.", refused?.message)
        assertEquals(PendingChanges.MAX_PER_CIRCLE, dao.changes.value.size)
    }

    @Test
    fun `at 100 changes for the account nothing more is kept`() = runTest {
        repeat(PendingChanges.MAX_PER_ACCOUNT) { i ->
            clock += 3_000
            store.queue<Unit>("c-${i / 20}", checkIn(message = "m$i", at = clock))
        }
        clock += 3_000
        val refused = store.queue<Unit>("c-9", checkIn(message = "one more", at = clock)).exceptionOrNull()

        assertTrue(refused !is QueuedForSync)
        assertEquals(PendingChanges.MAX_PER_ACCOUNT, dao.changes.value.size)
    }

    @Test
    fun `Cancel removes a waiting change at once and leaves no line`() = runTest {
        val id = (store.queue<Unit>("c-1", checkIn()).exceptionOrNull() as QueuedForSync).changeId

        assertEquals(PendingChanges.Cancelled.REMOVED, store.cancel(id))
        assertTrue(dao.changes.value.isEmpty())
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `Cancel after a lost answer removes it and says it may already have gone out`() = runTest {
        val id = (store.queue<Unit>("c-1", checkIn()).exceptionOrNull() as QueuedForSync).changeId
        dao.update(dao.get(id)!!.copy(lastOutcome = PendingChanges.LOST_ANSWER))

        assertEquals(PendingChanges.Cancelled.REMOVED, store.cancel(id))
        assertTrue(dao.changes.value.isEmpty())
        assertEquals(
            "Cancelled, but your check-in may already have gone out. If it did, it shows as usual.",
            dao.notices.value.single().line,
        )
    }

    @Test
    fun `a change going out can't be cancelled`() = runTest {
        val id = (store.queue<Unit>("c-1", checkIn()).exceptionOrNull() as QueuedForSync).changeId
        dao.update(dao.get(id)!!.copy(state = PendingChanges.RUNNING))

        assertEquals(PendingChanges.Cancelled.SENDING, store.cancel(id))
        assertEquals(PendingChanges.RUNNING, dao.get(id)?.state)
    }

    @Test
    fun `another account's change is neither shown, counted nor cancelled`() = runTest {
        val id = (store.queue<Unit>("c-1", checkIn()).exceptionOrNull() as QueuedForSync).changeId
        account = "someone-else"

        assertEquals(0, store.waitingCount())
        assertEquals(PendingChanges.Cancelled.GONE, store.cancel(id))
        assertEquals(1, dao.changes.value.size)
    }

    @Test
    fun `leaving a circle takes its changes and their lines with it, and says nothing`() = runTest {
        store.queue<Unit>("c-1", checkIn())
        store.queue<Unit>("c-2", checkIn())
        dao.insertNotice(store.notice(dao.changes.value.first { it.circleId == "c-1" }, "a line"))

        store.forgetCircle("c-1")

        assertEquals(listOf("c-2"), dao.changes.value.map { it.circleId })
        assertTrue(dao.notices.value.isEmpty())
    }

    @Test
    fun `the sign-out line counts what is waiting`() = runTest {
        store.queue<Unit>("c-1", checkIn())
        assertEquals("1 change waiting to sync on this phone won't be made.", PendingLines.signOut(store.waitingCount()))
        clock += 3_000
        store.queue<Unit>("c-1", checkIn(message = "x", at = clock))
        assertEquals("2 changes waiting to sync on this phone won't be made.", PendingLines.signOut(store.waitingCount()))
    }

    private suspend fun askedAbout(inputs: PendingInputs, ask: PendingQuestions.Ask): String {
        val id = (store.queue<Unit>("c-1", inputs).exceptionOrNull() as QueuedForSync).changeId
        dao.update(dao.get(id)!!.copy(state = PendingChanges.NEEDS_PERSON, question = PendingQuestions.ask(ask)))
        return id
    }

    @Test
    fun `It's them continues the change with exactly the details shown, asked once`() = runTest {
        val ask = PendingQuestions.Ask(PendingQuestions.MEMBER, "sam", "s2", "g2", "12345")
        val id = askedAbout(PendingInputs.MakeAdmin("Family", "sam", "Sam", "s1", "g1"), ask)

        val answered = store.answer(id, accept = true)

        assertEquals(id, answered?.id)
        val change = dao.get(id)!!
        assertEquals(PendingChanges.WAITING, change.state)
        assertEquals(null, change.question)
        assertEquals(PendingInputs.MakeAdmin("Family", "sam", "Sam", "s2", "g2", asked = true), PendingInputs.read(change))
        verify { scheduler.kick() }
    }

    @Test
    fun `It's them to an invitee takes their account and keys as confirmed`() = runTest {
        val ask = PendingQuestions.Ask(PendingQuestions.INVITEE, "dan", "s2", "g2")
        val id = askedAbout(PendingInputs.Invite("Family", "dan@x.test"), ask)

        store.answer(id, accept = true)

        assertEquals(
            PendingInputs.Invite("Family", "dan@x.test", confirmedUserId = "dan", confirmedSharingKey = "s2", confirmedSigningKey = "g2"),
            PendingInputs.read(dao.get(id)!!),
        )
    }

    @Test
    fun `No ends it with no line - one that signed settles its ids first, never just deleted`() = runTest {
        val ask = PendingQuestions.Ask(PendingQuestions.LET_IN, "sam", "s2", "g2")
        val plain = askedAbout(PendingInputs.LetIn("Family", "sam", "Sam", "UNCONFIRMED_INVITER", "s1", "g1"), ask)
        store.answer(plain, accept = false)
        assertEquals(null, dao.get(plain))
        assertTrue(dao.notices.value.isEmpty())

        clock += 3_000
        val signedOnce = askedAbout(PendingInputs.LetIn("Family", "kim", "Kim", "UNCONFIRMED_INVITER", "s1", "g1"), ask.copy(userId = "kim"))
        dao.update(PendingChanges.withSigned(dao.get(signedOnce)!!, SignedStatement("00000000-0000-4000-8000-000000000001", at = clock)))
        store.answer(signedOnce, accept = false)
        val parked = dao.get(signedOnce)!!
        assertEquals(PendingChanges.SETTLING, parked.state)
        assertEquals("DROPPED" to null, PendingQuestions.readEnding(parked.question))
    }

    @Test
    fun `every stored field is named, so a change kept by one release build reads the same after an update`() {
        // A release build renames fields (R8). Gson writes a field under its @SerializedName
        // whatever the build calls it, and the keep rule for that annotation keeps the field.
        val kinds = PendingInputs::class.java.declaredClasses.filter {
            PendingInputs::class.java.isAssignableFrom(it) && it != PendingInputs::class.java
        }
        assertEquals("one inputs class per kind that is kept (the two place alerts share one)", 14, kinds.size)
        // And the rest a change keeps as JSON: the statements it signed and what it asks.
        val stored = kinds + listOf(SignedStatement::class.java, PendingQuestions.Ask::class.java)
        val unnamed = stored.flatMap { kind ->
            kind.declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic }
                .filter { it.getAnnotation(com.google.gson.annotations.SerializedName::class.java) == null }
                .map { "${kind.simpleName}.${it.name}" }
        }
        assertEquals(emptyList<String>(), unnamed)
        // And a stored change carries those names, not the build's.
        assertTrue(PendingInputs.toJson(checkIn()).contains("\"checked_in_at\":"))
    }

    @Test
    fun `changes and lines kept under another spelling of the account are this account's, moved to its canonical id`() = runTest {
        // An id saved before canonical ids (AccountIds).
        val canonical = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        account = canonical.uppercase()
        store.queue<Unit>("c-1", checkIn())
        dao.insertNotice(com.pingolin.track.data.local.entity.DroppedNoticeEntity(
            id = "n-1", account = canonical.uppercase(), circleId = "c-1", kind = PendingKind.CHECK_IN.name, line = "gone", createdAt = clock,
        ))
        account = "someone-else"
        store.queue<Unit>("c-1", checkIn("other"))
        account = canonical
        val fresh = PendingChanges(dao, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))

        assertEquals(1, fresh.waitingCount())
        assertEquals(setOf(canonical, "someone-else"), dao.changes.value.map { it.account }.toSet())
        assertEquals(listOf(canonical), dao.notices.value.map { it.account })
    }

    @Test
    fun `a move the tables can't take now is tried again at the next call, and the call goes on`() = runTest {
        val canonical = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        account = canonical.replace("-", "")
        store.queue<Unit>("c-1", checkIn())
        account = canonical
        var failing = true
        val flaky = object : com.pingolin.track.data.local.dao.PendingChangeDao by dao {
            override suspend fun reassign(from: String, to: String) {
                if (failing) throw IllegalStateException("the database can't be written just now")
                dao.reassign(from, to)
            }
        }
        val fresh = PendingChanges(flaky, sessionManager, scheduler, circleDao, mockk<CircleMemberDao>(relaxed = true))

        assertEquals("not moved yet", 0, fresh.waitingCount())
        failing = false
        assertEquals(1, fresh.waitingCount())
        assertEquals(listOf(canonical), dao.changes.value.map { it.account })
    }
}
