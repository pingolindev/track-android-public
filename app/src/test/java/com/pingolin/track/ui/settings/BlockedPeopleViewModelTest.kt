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

package com.pingolin.track.ui.settings

import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.BlockPersonRequest
import com.pingolin.track.data.remote.dto.BlockedPersonResponse
import com.pingolin.track.util.SessionManager
import io.mockk.MockKAnnotations
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * "Blocked people": the list, a block and an unblock, and what is said when one fails. Never
 * the server's own words, and no "Retry" to press.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BlockedPeopleViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @MockK
    private lateinit var trackApi: TrackApi

    @MockK
    private lateinit var sessionManager: SessionManager

    private val unreachable = "Pingolin couldn't be reached. Check your connection and try again."
    private val trouble = "Pingolin is having trouble just now. Please try again in a moment."

    /** What a server might say. None of it is ever on screen. */
    private val said = "duplicate key value violates unique constraint circle_invite_blocks_user_email. Call 0800 555 0100"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)
        every { sessionManager.getEmail() } returns "me@example.test"
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun people(vararg emails: String) = emails.map { BlockedPersonResponse(it, "2026-10-07T13:00:00Z") }
    private fun listed(vararg emails: String): Response<ApiResponse<List<BlockedPersonResponse>>> =
        Response.success(ApiResponse(true, people(*emails), null))
    private val done: Response<ApiResponse<Unit>> = Response.success(ApiResponse(true, null, null))
    private fun <T> refused(status: Int, code: String? = null): Response<T> = Response.error(
        status,
        com.google.gson.Gson().toJson(
            mapOf("success" to false, "error" to said) + (code?.let { mapOf("error_code" to it) } ?: emptyMap()),
        ).toResponseBody(null),
    )

    private fun vm() = BlockedPeopleViewModel(trackApi, sessionManager)

    /**
     * Runs [check], and then the screen goes, WHATEVER became of the check. A list that keeps
     * failing is read again for as long as the screen is up, and `runTest` waits for every
     * coroutine: with the screen left up after a failed assertion the test would never end,
     * and the failure it should report would never be written down.
     */
    private inline fun BlockedPeopleViewModel.checkedAndGone(check: () -> Unit) {
        try {
            check()
        } finally {
            viewModelScope.cancel()
        }
    }

    // --- The list ------------------------------------------------------------------------------

    @Test
    fun `the list is read when the screen opens, and shown by address`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("zoe@example.test", "adam@example.test")
        val vm = vm()
        assertTrue("reading", vm.state.value.loading)
        assertNull("no list yet: never shown as empty", vm.state.value.people)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(listOf("adam@example.test", "zoe@example.test"), vm.state.value.people!!.map { it.blockedEmail })
        assertNull(vm.state.value.failure)
    }

    @Test
    fun `nobody blocked is an empty list`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed()
        val vm = vm()
        runCurrent()
        assertEquals(emptyList<BlockedPersonResponse>(), vm.state.value.people)
    }

    /** No "Retry" to press; the list is read again by itself. */
    @Test
    fun `a list that cannot be read is not an empty list, says so in this app's words, and is read again by itself`() = runTest(testDispatcher) {
        var calls = 0
        coEvery { trackApi.listBlockedPeople() } answers {
            calls++
            when (calls) {
                1 -> throw java.io.IOException("offline")
                2 -> refused(500)
                else -> listed("adam@example.test")
            }
        }
        val vm = vm()
        vm.reloadPausesMs = listOf(1_000L, 3_000L)
        runCurrent()
        assertNull("could not be read: no list", vm.state.value.people)
        assertEquals(unreachable, vm.state.value.failure)
        assertNull("nothing can be blocked before the list is read", vm.addressToBlock("sam@example.test"))

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, calls)
        assertNull(vm.state.value.people)
        assertEquals(trouble, vm.state.value.failure)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals("the second pause is the longer one", 2, calls)
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(3, calls)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
        assertNull("read: nothing to say any more", vm.state.value.failure)
    }

    @Test
    fun `an answer that says it failed is not a list`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns Response.success(ApiResponse(false, people("sam@example.test"), said))
        val vm = vm()
        vm.reloadPausesMs = listOf(60_000L)
        runCurrent()
        vm.checkedAndGone {
            assertNull(vm.state.value.people)
            assertEquals(trouble, vm.state.value.failure)
        }
    }

    // --- A block -------------------------------------------------------------------------------

    @Test
    fun `a block sends the address without the spaces around it, lists it at once, and reads the server's list`() = runTest(testDispatcher) {
        var list = listed("adam@example.test")
        coEvery { trackApi.listBlockedPeople() } answers { list }
        val sent = mutableListOf<BlockPersonRequest>()
        val taken = CompletableDeferred<Unit>()
        coEvery { trackApi.blockPerson(capture(sent)) } coAnswers { taken.await(); done }
        val vm = vm()
        runCurrent()

        vm.block("  Sam@Example.test ")
        runCurrent()
        assertTrue("on its way", vm.state.value.busy)
        assertEquals(listOf(BlockPersonRequest("Sam@Example.test")), sent)
        assertNull("nothing to empty the field for yet", vm.events.value)

        // The server takes it, and keeps it in lower case.
        list = listed("adam@example.test", "sam@example.test")
        taken.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(BlockedPeopleEvent.Blocked, vm.events.value)
        assertEquals(listOf("adam@example.test", "sam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
        coVerify(exactly = 2) { trackApi.listBlockedPeople() }
        vm.consumeEvent()
        assertNull(vm.events.value)
    }

    @Test
    fun `a block the server took is on the list even when the list cannot be read again just then`() = runTest(testDispatcher) {
        var calls = 0
        coEvery { trackApi.listBlockedPeople() } answers {
            calls++
            if (calls == 1) listed("adam@example.test") else throw java.io.IOException("offline")
        }
        coEvery { trackApi.blockPerson(any()) } returns done
        val vm = vm()
        vm.reloadPausesMs = listOf(60_000L)
        runCurrent()

        vm.block("Sam@Example.test")
        runCurrent()
        vm.checkedAndGone {
            assertEquals(listOf("adam@example.test", "sam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
            assertEquals(BlockedPeopleEvent.Blocked, vm.events.value)
        }
    }

    @Test
    fun `nothing is sent for what is no address, one's own, or one already blocked`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("sam@example.test")
        val vm = vm()
        runCurrent()
        for (typed in listOf("", "sam", "ME@example.test", "SAM@Example.test")) {
            vm.block(typed)
            runCurrent()
        }
        coVerify(exactly = 0) { trackApi.blockPerson(any()) }
        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a block that fails says so in this app's words, lists nothing, and empties nothing`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("adam@example.test")
        val vm = vm()
        runCurrent()

        coEvery { trackApi.blockPerson(any()) } throws java.io.IOException("offline")
        vm.block("sam@example.test")
        runCurrent()
        assertEquals(unreachable, vm.state.value.failure)
        assertFalse(vm.state.value.busy)
        assertNull("the field keeps what was typed", vm.events.value)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })

        // Refused with a plain 400, or with a 409 that is not the full list. Never the server's text.
        for (answer in listOf(refused<ApiResponse<Unit>>(400), refused(409), refused(409, "invite_already_exists"))) {
            coEvery { trackApi.blockPerson(any()) } returns answer
            vm.block("sam@example.test")
            runCurrent()
            assertEquals(trouble, vm.state.value.failure)
            assertFalse(vm.state.value.failure!!.contains("0800"))
            assertFalse("and it is not taken for a full list", vm.state.value.listFull)
            assertNull(vm.events.value)
        }
        // And an answer that says it failed.
        coEvery { trackApi.blockPerson(any()) } returns Response.success(ApiResponse(false, null, said))
        vm.block("sam@example.test")
        runCurrent()
        assertEquals(trouble, vm.state.value.failure)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
    }

    /**
     * The server refuses a block of the 501st address with 409 `blocked_people_limit`. That is
     * no trouble ("Pingolin is having trouble just now. Please try again in a moment."): nothing
     * is wrong, and trying again never helps.
     */
    @Test
    fun `a full list is said as a full list, not as trouble, and nothing is listed or emptied`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("adam@example.test")
        val vm = vm()
        runCurrent()

        coEvery { trackApi.blockPerson(any()) } returns refused(409, "blocked_people_limit")
        vm.block("sam@example.test")
        runCurrent()
        assertTrue(vm.state.value.listFull)
        assertNull("the screen has its own words for it: no second line beside them", vm.state.value.failure)
        assertFalse(vm.state.value.busy)
        assertNull("the field keeps what was typed", vm.events.value)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
    }

    @Test
    fun `it stops being said at the next request - an unblock makes room`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("adam@example.test")
        val vm = vm()
        runCurrent()
        coEvery { trackApi.blockPerson(any()) } returns refused(409, "blocked_people_limit")
        vm.block("sam@example.test")
        runCurrent()
        assertTrue(vm.state.value.listFull)

        coEvery { trackApi.unblockPerson(any()) } returns done
        vm.unblock("adam@example.test")
        runCurrent()
        assertFalse(vm.state.value.listFull)
        assertNull(vm.state.value.failure)

        // And the block that follows is an ordinary one.
        coEvery { trackApi.blockPerson(any()) } returns done
        coEvery { trackApi.listBlockedPeople() } returns listed("sam@example.test")
        vm.block("sam@example.test")
        runCurrent()
        assertFalse(vm.state.value.listFull)
        assertEquals(listOf("sam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
    }

    /** Not only an unblock: the next block is a new request, and what was said of the last one goes when it is made. */
    @Test
    fun `a full list stops being said once another block is made - while it is on its way, and when it is taken`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed()
        val vm = vm()
        runCurrent()
        coEvery { trackApi.blockPerson(any()) } returns refused(409, "blocked_people_limit")
        vm.block("sam@example.test")
        runCurrent()
        assertTrue(vm.state.value.listFull)

        // The server has room now (someone was unblocked on another device). The answer is held.
        val answer = kotlinx.coroutines.CompletableDeferred<Response<ApiResponse<Unit>>>()
        coEvery { trackApi.blockPerson(any()) } coAnswers { answer.await() }
        coEvery { trackApi.listBlockedPeople() } returns listed("sam@example.test")
        vm.block("sam@example.test")
        runCurrent()
        assertTrue(vm.state.value.busy)
        assertFalse("not said while the next block is on its way", vm.state.value.listFull)
        answer.complete(done)
        runCurrent()
        assertFalse("nor once it was taken", vm.state.value.listFull)
        assertNull(vm.state.value.failure)
        assertEquals(listOf("sam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
    }

    @Test
    fun `a full list, then a block that fails for another reason, says the other reason`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed()
        val vm = vm()
        runCurrent()
        coEvery { trackApi.blockPerson(any()) } returns refused(409, "blocked_people_limit")
        vm.block("sam@example.test")
        runCurrent()
        coEvery { trackApi.blockPerson(any()) } throws java.io.IOException("offline")
        vm.block("sam@example.test")
        runCurrent()
        assertFalse(vm.state.value.listFull)
        assertEquals(unreachable, vm.state.value.failure)
    }

    @Test
    fun `a second block is not sent while one is on its way`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed()
        val taken = CompletableDeferred<Unit>()
        coEvery { trackApi.blockPerson(any()) } coAnswers { taken.await(); done }
        val vm = vm()
        runCurrent()
        vm.block("sam@example.test")
        runCurrent()
        vm.block("pat@example.test")
        vm.unblock("adam@example.test")
        runCurrent()
        coVerify(exactly = 1) { trackApi.blockPerson(any()) }
        coVerify(exactly = 0) { trackApi.unblockPerson(any()) }
        taken.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a failure is cleared by the next request that is made`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed()
        coEvery { trackApi.blockPerson(any()) } throws java.io.IOException("offline")
        val vm = vm()
        runCurrent()
        vm.block("sam@example.test")
        runCurrent()
        assertEquals(unreachable, vm.state.value.failure)

        val taken = CompletableDeferred<Unit>()
        coEvery { trackApi.blockPerson(any()) } coAnswers { taken.await(); done }
        vm.block("sam@example.test")
        runCurrent()
        assertNull("asked again: the old failure is not still standing", vm.state.value.failure)
        taken.complete(Unit)
        runCurrent()
    }

    // --- An unblock ----------------------------------------------------------------------------

    @Test
    fun `an unblock sends the address as the list gave it, and takes that row off`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("adam@example.test", "zoe@example.test")
        coEvery { trackApi.unblockPerson(any()) } returns done
        val vm = vm()
        runCurrent()

        vm.unblock("adam@example.test")
        runCurrent()
        coVerify(exactly = 1) { trackApi.unblockPerson(BlockPersonRequest("adam@example.test")) }
        assertEquals(listOf("zoe@example.test"), vm.state.value.people!!.map { it.blockedEmail })
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun `an unblock that fails says so and leaves the row`() = runTest(testDispatcher) {
        coEvery { trackApi.listBlockedPeople() } returns listed("adam@example.test")
        val vm = vm()
        runCurrent()

        coEvery { trackApi.unblockPerson(any()) } throws java.io.IOException("offline")
        vm.unblock("adam@example.test")
        runCurrent()
        assertEquals(unreachable, vm.state.value.failure)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })

        coEvery { trackApi.unblockPerson(any()) } returns refused(500)
        vm.unblock("adam@example.test")
        runCurrent()
        assertEquals(trouble, vm.state.value.failure)
        assertEquals(listOf("adam@example.test"), vm.state.value.people!!.map { it.blockedEmail })
        assertFalse(vm.state.value.busy)
    }
}
