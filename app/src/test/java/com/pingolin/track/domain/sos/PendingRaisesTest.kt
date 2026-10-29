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

package com.pingolin.track.domain.sos

import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A help request that hasn't gone is kept, per account, so a process Android ends never loses it
 * silently, and so one account's request never goes out as another's. Sign-out wipes them.
 */
class PendingRaisesTest {

    private companion object {
        const val A = "beef0000-0000-4000-8000-00000000000a"
        const val B = "a11ce000-0000-4000-8000-00000000a11c"
        const val CIRCLE = "ca11ab1e-0000-4000-8000-0000000000c1"
        const val PRESS = 1_800_000_000_000L
    }

    private val prefs = InMemoryPrefs()
    private var owner: String? = A
    private val store = SecurePrefsPendingRaises(mockk(relaxed = true), { owner }, { prefs })

    private val waiting = PendingRaise(CIRCLE, PRESS, PRESS + 5_000, PendingRaise.State.WAITING)

    @Test
    fun `kept per account, and never read as another account's`() {
        store.put(waiting)
        assertEquals(waiting, store.get())

        owner = B
        assertNull("another account sees none", store.get())
        val theirs = waiting.copy(state = PendingRaise.State.UNSURE, told = true)
        store.put(theirs)
        assertEquals(theirs, store.get())

        owner = A
        assertEquals(waiting, store.get())
        store.remove()
        assertNull(store.get())
        owner = B
        assertEquals("A's removal leaves B's", theirs, store.get())
    }

    @Test
    fun `every field and state survives the file`() {
        for (state in PendingRaise.State.values()) {
            for (told in listOf(false, true)) {
                for (storeRefused in listOf(false, true)) {
                    val raise = PendingRaise(CIRCLE, PRESS, PRESS + 5_000, state, told, storeRefused)
                    store.put(raise)
                    assertEquals(raise, store.get())
                }
            }
        }
    }

    @Test
    fun `a wait for a connection is stored in the same line format, and one on the session store with its cause`() {
        store.put(waiting)
        assertEquals("a line from before reads the same", "$CIRCLE\t$PRESS\t${PRESS + 5_000}\tWAITING\tfalse", prefs.getString("$A|raise", null))
        store.put(waiting.copy(storeRefused = true))
        assertEquals("$CIRCLE\t$PRESS\t${PRESS + 5_000}\tWAITING\tfalse\tstore", prefs.getString("$A|raise", null))
        assertEquals(waiting.copy(storeRefused = true), store.get())
        prefs.edit().putString("$A|raise", "$CIRCLE\t$PRESS\t${PRESS + 5_000}\tWAITING\tfalse\tsomething").commit()
        assertNull("a sixth field that isn't the store's is no record", store.get())
    }

    @Test
    fun `a line that doesn't read is no record, and nobody signed in reads or writes none`() {
        prefs.edit().putString("$A|raise", "not a record").commit()
        assertNull(store.get())

        owner = null
        store.put(waiting)
        assertNull(store.get())
        owner = A
        assertNull("nothing was written for nobody", store.get())
    }

    @Test
    fun `sign-out wipes every account's`() {
        store.put(waiting)
        owner = B
        store.put(waiting)
        store.wipeAll()
        assertNull(store.get())
        owner = A
        assertNull(store.get())
    }

    @Test
    fun `the sign-out paths wipe the file and cancel the job, the start and sign-in take the raise up`() {
        fun source(path: String) = File("src/main/java/com/pingolin/track/$path").readText()
        val auth = source("domain/repository/AuthRepository.kt")
        assertEquals(1, Regex("""runCatching \{ com\.pingolin\.track\.worker\.SosRaiseWorker\.cancel\(context\) \}""").findAll(auth).count())
        assertEquals(1, Regex("""runCatching \{ com\.pingolin\.track\.domain\.sos\.SecurePrefsPendingRaises\.wipe\(context\) \}""").findAll(auth).count())
        val app = source("TrackApplication.kt")
        assertTrue(app.contains("runCatching { sosDispatcher.get().forgetAccount() }"))
        // Only with a session store that opens: one that can't has nothing to resume.
        assertTrue(app.contains("if (sessionManager.get().storeRefusal() == null && sessionManager.get().isLoggedIn()) {\n                runCatching { sosDispatcher.get().resumePending() }"))
        val main = source("ui/main/MainActivity.kt")
        assertTrue(main.contains("runCatching { sosDispatcher.get().resumePending() }"))
        val worker = source("worker/SosRaiseWorker.kt")
        assertTrue(worker.contains(".setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(worker.contains("SosDispatcher.Waited.STILL_WAITING -> Result.retry()"))
        val outside = source("domain/sos/SosRaiseOutside.kt")
        assertTrue(outside.contains("NotificationCompat.Builder(context, TrackApplication.SOS_CHANNEL_ID)"))
        assertTrue(outside.contains(".setSilent(true)"))
        assertTrue(outside.contains(".setOngoing(waiting)"))
    }
}
