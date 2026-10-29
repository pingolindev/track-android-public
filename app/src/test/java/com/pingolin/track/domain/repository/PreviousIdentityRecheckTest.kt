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

import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.PreviousIdentitiesResponse
import com.pingolin.track.data.remote.dto.PreviousIdentityDto
import com.pingolin.track.data.remote.dto.SealedPreviousIdentityDto
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.SealedPreviousIdentity

/**
 * A restore in the account portal reaches a running app. The `identities_check` push, or a
 * foreground while a circle is still locked since a reset, nudges a re-check — the ordinary
 * signed-in GET and the SDK import. At most one per three minutes; nudges refused meanwhile
 * leave a single pending re-check for when the window ends; the SDK is given the union of
 * what this session has seen (its import replaces the set); and a load that outlives its
 * session stores nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviousIdentityRecheckTest {

    private val authApi = mockk<AuthApi>()
    private val cryptoManager = mockk<CryptoManager>()

    /** What the SDK holds open, and every set it was given. */
    private var open = 0
    private var signingKeys = 0
    private val imports = mutableListOf<List<SealedPreviousIdentity>>()
    private var gets = 0
    private var reloads = 0

    private fun sealed(id: String) = SealedPreviousIdentity(nonce = "n-$id", ciphertext = "c-$id")

    /** Identity [id]'s v2 blob: it also carries that identity's signing public key. */
    private fun sealedV2(id: String) = SealedPreviousIdentity(nonce = "n2-$id", ciphertext = "c2-$id")

    private fun answer(vararg ids: String) = Response.success(ApiResponse(success = true, data = PreviousIdentitiesResponse(
        ids.map { PreviousIdentityDto(keyVersion = 1, appSealedPrivateKey = SealedPreviousIdentityDto("n-$it", "c-$it")) },
    ), error = null))

    /** Every identity with both blobs, as the portal writes them once it has v2. */
    private fun answerV2(vararg ids: String) = Response.success(ApiResponse(success = true, data = PreviousIdentitiesResponse(
        ids.map {
            PreviousIdentityDto(
                keyVersion = 1,
                appSealedPrivateKey = SealedPreviousIdentityDto("n-$it", "c-$it"),
                appSealedPrivateKeyV2 = SealedPreviousIdentityDto("n2-$it", "c2-$it"),
            )
        },
    ), error = null))

    @Before
    fun setUp() {
        every { cryptoManager.previousIdentityCount() } answers { open.toUInt() }
        every { cryptoManager.previousSigningPublicKeys() } answers { List(signingKeys) { "signing-$it" } }
        // As the SDK's v2 import: it replaces the set; one identity per id, and a v2 blob brings
        // its signing key whatever v1 blob of the same identity came with it.
        every { cryptoManager.importPreviousIdentitiesV2(any()) } answers {
            val set = firstArg<List<SealedPreviousIdentity>>()
            imports += set
            open = set.map { it.ciphertext.substringAfter('-') }.toSet().size
            signingKeys = set.filter { it.ciphertext.startsWith("c2-") }.map { it.ciphertext.substringAfter('-') }.toSet().size
            open.toUInt()
        }
    }

    private fun TestScope.loader(vararg answers: Array<String>) = PreviousIdentityLoader(authApi, cryptoManager).also { loader ->
        val queue = ArrayDeque(answers.toList())
        coEvery { authApi.getPreviousIdentities() } answers {
            gets++
            answer(*(queue.removeFirstOrNull() ?: emptyArray()))
        }
        loader.clock = { testScheduler.currentTime }
        loader.scope = backgroundScope
        loader.onNewlyOpened = { reloads++ }
    }

    private val window = PreviousIdentityLoader.RECHECK_WINDOW_MS

    @Test
    fun `many nudges inside the window - exactly one request, when the window ends`() = runTest {
        val loader = loader(arrayOf("a"), arrayOf("a", "b"))
        loader.nudge()
        assertEquals(1, gets)

        advanceTimeBy(60_000)
        repeat(5) { loader.nudge() }
        assertEquals(1, gets)

        advanceTimeBy(window - 60_000 - 1)
        runCurrent()
        assertEquals("not before the window ends", 1, gets)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("one, when it ends", 2, gets)
        advanceTimeBy(10 * window)
        runCurrent()
        assertEquals("and no more", 2, gets)
        assertEquals(setOf(sealed("a"), sealed("b")), imports.last().toSet())
    }

    @Test
    fun `a nudge during a running re-check - exactly one follow-up`() = runTest {
        val loader = loader()
        val release = CompletableDeferred<Unit>()
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            gets++
            if (gets == 1) release.await()
            answer("a")
        }
        val first = launch { loader.nudge() }
        runCurrent()
        assertEquals(1, gets)

        loader.nudge()
        loader.nudge()
        loader.nudge()
        assertEquals(1, gets)
        release.complete(Unit)
        first.join()

        advanceTimeBy(window + 1)
        runCurrent()
        assertEquals(2, gets)
        advanceTimeBy(10 * window)
        runCurrent()
        assertEquals(2, gets)
    }

    @Test
    fun `an empty answer after a good one - the count is unchanged, and only something new reloads the circles`() = runTest {
        val loader = loader(arrayOf("a", "b"), emptyArray(), arrayOf("c"))
        loader.nudge()
        assertEquals(2, open)
        assertEquals(1, reloads)

        advanceTimeBy(window + 1)
        loader.nudge()
        assertEquals(2, gets)
        assertEquals("the union: nothing is lost", 2, open)
        assertEquals("nothing new, nothing reloaded", 1, reloads)

        advanceTimeBy(window + 1)
        loader.nudge()
        assertEquals(setOf(sealed("a"), sealed("b"), sealed("c")), imports.last().toSet())
        assertEquals(3, open)
        assertEquals(2, reloads)
    }

    @Test
    fun `sign-out clears everything - the pending re-check and what was held for the union`() = runTest {
        val loader = loader(arrayOf("a"), arrayOf("b"))
        loader.nudge()
        loader.nudge()   // inside the window: pending
        loader.reset()

        advanceTimeBy(10 * window)
        runCurrent()
        assertEquals("the pending re-check went with the session", 1, gets)

        // The next account's first look holds only its own.
        loader.nudge()
        assertEquals(2, gets)
        assertEquals(listOf(sealed("b")), imports.last())
    }

    @Test
    fun `a re-check in flight at sign-out adds nothing - and neither does the start-up load`() = runTest {
        val loader = loader()
        val release = CompletableDeferred<Unit>()
        coEvery { authApi.getPreviousIdentities() } coAnswers {
            gets++
            release.await()
            answer("previous-account")
        }
        val recheck = launch { loader.nudge() }
        val startUp = launch { loader.ensureLoaded() }
        runCurrent()
        assertEquals(2, gets)

        loader.reset()
        release.complete(Unit)
        recheck.join()
        startUp.join()

        assertTrue("nothing reached the SDK", imports.isEmpty())
        assertEquals(0, open)
        assertEquals(0, reloads)
        // Not marked loaded either: the next session loads its own.
        coEvery { authApi.getPreviousIdentities() } answers { gets++; answer("next-account") }
        loader.ensureLoaded()
        assertEquals(listOf(sealed("next-account")), imports.single())
    }

    /** Per identity, v2 when the answer has it; only the v2 import is called. */
    @Test
    fun `v2 wins per identity on the wire, and only the v2 import is used`() = runTest {
        val loader = loader()
        coEvery { authApi.getPreviousIdentities() } answers {
            gets++
            Response.success(ApiResponse(success = true, data = PreviousIdentitiesResponse(listOf(
                PreviousIdentityDto(1, SealedPreviousIdentityDto("n-a", "c-a"), SealedPreviousIdentityDto("n2-a", "c2-a")),
                PreviousIdentityDto(2, SealedPreviousIdentityDto("n-b", "c-b")),
                PreviousIdentityDto(3, null, SealedPreviousIdentityDto("n2-c", "c2-c")),
                PreviousIdentityDto(4, null, null),
            )), error = null))
        }

        assertEquals(3, loader.ensureLoaded())

        assertEquals(listOf(sealedV2("a"), sealed("b"), sealedV2("c")), imports.single())
        io.mockk.verify(exactly = 0) { cryptoManager.importPreviousIdentities(any()) }
    }

    /**
     * A v1 identity upgraded to v2 is more opened, so the circles load again: that is what
     * opens an owner's own circle signed before the reset. A later v1-only answer never drops
     * the signing key: the held v2 goes into every import.
     */
    @Test
    fun `a v1 identity upgraded to v2 loads the circles again, and a later v1 answer keeps its signing key`() = runTest {
        val loader = loader()
        // The last answer is an older-format one that also brings a new identity, b.
        val answers = ArrayDeque(listOf(answer("a"), answerV2("a"), answer("a", "b")))
        coEvery { authApi.getPreviousIdentities() } answers { gets++; answers.removeFirst() }

        loader.nudge()
        assertEquals(1, open)
        assertEquals(0, signingKeys)
        assertEquals(1, reloads)

        advanceTimeBy(window + 1)
        loader.nudge()
        assertEquals("the same identity", 1, open)
        assertEquals(1, signingKeys)
        assertEquals("gaining its signing key reloads", 2, reloads)

        advanceTimeBy(window + 1)
        loader.nudge()
        assertEquals(3, gets)
        assertTrue("the held v2 blob goes into the import", sealedV2("a") in imports.last())
        assertEquals("a's signing key is kept", 1, signingKeys)
        assertEquals(2, open)
        assertEquals(3, reloads)
    }

    @Test
    fun `the foreground looks again only while a circle is locked since a reset, or a re-check is pending`() = runTest {
        val loader = loader(arrayOf("a"), arrayOf("a"), arrayOf("a"))
        loader.onForeground(lockedSinceReset = false)
        assertEquals(0, gets)
        loader.onForeground(lockedSinceReset = true)
        assertEquals(1, gets)

        // A push inside the window leaves a pending re-check; the next foreground after the
        // window runs it, once.
        loader.nudge()
        advanceTimeBy(window + 1)
        loader.onForeground(lockedSinceReset = false)
        runCurrent()
        assertEquals(2, gets)
        loader.onForeground(lockedSinceReset = false)
        advanceTimeBy(10 * window)
        runCurrent()
        assertEquals(2, gets)
    }
}
