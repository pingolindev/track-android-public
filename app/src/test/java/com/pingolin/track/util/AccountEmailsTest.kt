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

package com.pingolin.track.util

import com.pingolin.track.testutil.DamagedPrefs
import com.pingolin.track.testutil.InMemoryPrefs
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed-email guard: what holds trust goes for another account only when an email was typed
 * and it isn't the one that account typed.
 */
class AccountEmailsTest {

    private val ann = "a11ce000-0000-4000-8000-00000000000a"
    private val ben = "b0b00000-0000-4000-8000-00000000000b"

    @Test
    fun `another account's goes only when the typed email differs from its own`() {
        val emails = AccountEmails(mockk()) { InMemoryPrefs() }
        emails.record(ann, " Ann@X.test ")

        assertEquals("ann@x.test", emails.of(ann))
        assertEquals("under any spelling of its id", "ann@x.test", emails.of(ann.uppercase()))
        assertTrue(emails.guard("ben@x.test")(ann))
        assertFalse("the same email, typed another way", emails.guard("ANN@x.test ")(ann))
        assertFalse("no email typed", emails.guard(null)(ann))
        assertFalse("a blank email typed", emails.guard("  ")(ann))
        assertFalse("no email saved for that account", emails.guard("ben@x.test")(ben))
    }

    @Test
    fun `an email that can't be read is no answer - kept`() {
        val prefs = DamagedPrefs()
        val emails = AccountEmails(mockk()) { prefs }
        emails.record(ann, "ann@x.test")
        prefs.damaged = true

        assertNull(emails.of(ann))
        assertFalse(emails.guard("ben@x.test")(ann))
    }

    @Test
    fun `another account's email goes with what its guard lets go, and an erasure leaves none`() {
        val prefs = InMemoryPrefs()
        val emails = AccountEmails(mockk()) { prefs }
        emails.record(ann, "ann@x.test")
        emails.record(ben, "ben@x.test")

        emails.keepOnly(ben) { false }
        assertEquals("ann@x.test", emails.of(ann))
        emails.keepOnly(ben, emails.guard("ben@x.test"))
        assertNull(emails.of(ann))
        assertEquals("ben@x.test", emails.of(ben))

        emails.wipeAll()
        assertTrue(prefs.data.isEmpty())
    }
}
