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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An account id as this app keeps it: a UUID's bytes, lower-case, hyphenated. */
class AccountIdsTest {

    private val canonical = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val hex = canonical.replace("-", "")

    /** Every spelling the SDK's parser (uuid 1.24) takes. */
    private val spellings = listOf(
        canonical, canonical.uppercase(), "0A1b2C3d-4E5f-4A6b-8C7d-9E0f1A2b3C4d",
        hex, hex.uppercase(),
        "{$canonical}", "{${canonical.uppercase()}}",
        "urn:uuid:$canonical", "urn:uuid:${canonical.uppercase()}",
    )

    /** No UUID in any spelling the SDK takes. */
    private val others = listOf(
        null, "", "me", "user-dana",
        " $canonical", "$canonical ", "$canonical\n",
        canonical.replace("-", "_"), canonical.dropLast(1), canonical + "0", canonical.replace("4e5f", "4e5g"),
        "0a1b2c3d4e5f-4a6b-8c7d-9e0f1a2b3c4d", "0a1b2c3d-4e5f4a6b-8c7d-9e0f1a2b3c4d", "${hex}0",
        "{$hex}", "{$canonical", "$canonical}", "[$canonical]", "{ $canonical}", "{{$canonical}}",
        "URN:UUID:$canonical", "urn:uuid:$hex", "urn:uuid:{$canonical}", "urn:uuid: $canonical", "uuid:$canonical",
    )

    @Test
    fun `every spelling the SDK takes is its canonical form`() {
        for (spelling in spellings) {
            assertEquals(spelling, canonical, AccountIds.canonical(spelling))
        }
    }

    @Test
    fun `anything else is no account id`() {
        for (other in others) {
            assertNull(other.toString(), AccountIds.canonical(other))
        }
    }

    @Test
    fun `the invite lookup's narrower rule takes 8-4-4-4-12 in any case or 32 hex, and nothing else`() {
        for (spelling in spellings.take(5)) {
            assertEquals(spelling, canonical, AccountIds.strict(spelling))
        }
        for (other in spellings.drop(5) + others) {
            assertNull(other.toString(), AccountIds.strict(other))
        }
    }

    @Test
    fun `two spellings are the same account only with the same bytes`() {
        assertTrue(AccountIds.same(canonical.uppercase(), hex))
        assertTrue(AccountIds.same("{$canonical}", "urn:uuid:${canonical.uppercase()}"))
        assertFalse(AccountIds.same(canonical, "1a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"))
        assertFalse("no account is nobody's", AccountIds.same("me", "me"))
        assertFalse(AccountIds.same(null, canonical))
    }

    @Test
    fun `the personal key's slot is the nil UUID in any spelling the SDK takes, or "self", and nothing else`() {
        // Never a circle.
        for (slot in listOf(
            "00000000-0000-0000-0000-000000000000", "00000000000000000000000000000000",
            "{00000000-0000-0000-0000-000000000000}", "urn:uuid:00000000-0000-0000-0000-000000000000", "self", "Self",
        )) {
            assertTrue(slot, AccountIds.isPersonalSlot(slot))
        }
        for (other in listOf(canonical, "00000000-0000-0000-0000-000000000001", "0", "selfie", " self", "", null)) {
            assertFalse(other.toString(), AccountIds.isPersonalSlot(other))
        }
    }
}
