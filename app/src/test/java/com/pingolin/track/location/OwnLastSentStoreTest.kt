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

package com.pingolin.track.location

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The re-seal's record store, on the platform's preferences (EncryptedSharedPreferences needs the
 * Android Keystore, so the tests pass plain ones): this account's records only, a compare-and-update
 * that never writes over a newer record nor brings back a removed one, and everything gone at sign-out.
 */
// A plain Application on purpose: booting TrackApplication pulls in SQLCipher's native lib.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OwnLastSentStoreTest {

    private companion object {
        const val CIRCLE = "c1c1c1c1-0000-4000-8000-0000000000c1"
        const val ME = "a0a0a0a0-0000-4000-8000-0000000000a0"
        const val SOMEONE = "b0b0b0b0-0000-4000-8000-0000000000b0"
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var owner: String? = ME

    private fun store() = SecurePrefsOwnLastSent(context, { owner }) { it.getSharedPreferences("own_last_sent_test", Context.MODE_PRIVATE) }

    private val record = OwnLastSent(
        devicePseudonym = "device-ps", nonce = "01".repeat(24), ciphertext = "02".repeat(40), keyId = null,
        wrappedKey = "03".repeat(48), wrappedKeyNonce = "04".repeat(24), latitude = 37.33, longitude = -122.0,
        accuracy = 9.0, timestampMs = 1_760_000_000_000L, keyVersion = 2, coarsenRadiusMeters = 0.0,
    )

    @Test
    fun `a record reads back as written, in the circle's one spelling, for this account only`() {
        store().put(CIRCLE, record)

        assertEquals(record, store().get(CIRCLE.uppercase()))
        assertEquals(listOf(CIRCLE), store().circles())
        owner = SOMEONE
        assertNull(store().get(CIRCLE))
        assertTrue(store().circles().isEmpty())
    }

    @Test
    fun `an update writes only over the record it was given`() {
        val store = store()
        store.put(CIRCLE, record)
        val fresh = record.copy(nonce = "05".repeat(24), keyVersion = 3)

        assertTrue(store.update(CIRCLE, record, fresh))
        assertEquals(fresh, store.get(CIRCLE))
        // The record moved on since: the stale update writes nothing.
        assertFalse(store.update(CIRCLE, record, record.copy(triedVersion = 3)))
        assertEquals(fresh, store.get(CIRCLE))
    }

    @Test
    fun `removed with the circle, and everything at sign-out`() {
        val store = store()
        store.put(CIRCLE, record)
        store.remove(CIRCLE)
        assertNull(store.get(CIRCLE))

        store.put(CIRCLE, record)
        store.wipeAll()
        assertNull(store().get(CIRCLE))
    }

    @Test
    fun `a removal or the sign-out wipe landing while an update writes stays done`() {
        for (removal in listOf<(SecurePrefsOwnLastSent) -> Unit>({ it.remove(CIRCLE) }, { it.wipeAll() })) {
            val prefs = HeldWrite(context.getSharedPreferences("own_last_sent_held", Context.MODE_PRIVATE))
            val store = SecurePrefsOwnLastSent(context, { owner }) { prefs }
            store.put(CIRCLE, record)
            prefs.holdNext.set(true)
            var written: Boolean? = null
            val updating = thread { written = store.update(CIRCLE, record, record.copy(keyVersion = 3)) }
            // The update has read its record and is about to write over it.
            assertTrue(prefs.held.await(5, TimeUnit.SECONDS))
            val removing = thread { removal(store) }
            // It waits for the update: given a second, it hasn't gone ahead.
            removing.join(1_000)
            prefs.release.countDown()
            updating.join(5_000)
            removing.join(5_000)

            assertEquals(true, written)
            assertNull(store.get(CIRCLE))
        }
    }

    /** [inner], whose next [edit] once [holdNext] is set waits for [release]: a write held after its read. */
    private class HeldWrite(val inner: SharedPreferences) : SharedPreferences by inner {
        val holdNext = AtomicBoolean(false)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun edit(): SharedPreferences.Editor {
            if (holdNext.compareAndSet(true, false)) {
                held.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return inner.edit()
        }
    }
}
