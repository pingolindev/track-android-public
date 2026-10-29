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

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One account's pins kept under two spellings of its id, merged for one import, by the rule the
 * SDK shares with the other clients: equal keys one pin, different keys the pin that reads as
 * changed until approved; and two spellings of one member are one pin.
 */
class MemberPinBlobsTest {

    private val ann = "a11ce000-0000-4000-8000-00000000000a"
    private val circle = "c1c10000-0000-4000-8000-0000000000c1"
    private val bob = "b0b00000-0000-4000-8000-00000000000b"
    private val cy = "c7000000-0000-4000-8000-0000000000c7"

    private fun pin(sharing: String, signing: String, first: Long, verified: Long, approved: Long? = null) =
        """{"sharing_pubkey_hex":"${sharing.repeat(32)}","signing_pubkey_hex":"${signing.repeat(32)}","first_pinned_at":$first,"last_verified_at":$verified,"user_approved_change_at":${approved ?: "null"}}"""

    private fun blob(owner: String, pins: Map<String, String>, identity: Map<String, String> = emptyMap()) =
        """{"format":"f1","owner_user_id":"$owner","pins":{${pins.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}},""" +
            """"identity_pins":{${identity.entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"""

    private fun JsonObject.pin(map: String, key: String): JsonObject = getAsJsonObject(map).getAsJsonObject(key)

    @Test
    fun `one blob is imported as it is`() {
        assertNull(MemberPinBlobs.merged(listOf(blob(ann, emptyMap())), ann))
        assertNull(MemberPinBlobs.merged(emptyList(), ann))
    }

    @Test
    fun `two spellings of one member are one pin - by the same rule as two spellings of the account`() {
        val merged = JsonParser.parseString(MemberPinBlobs.merged(listOf(
            blob(ann.uppercase(), mapOf(
                "${circle.uppercase()}:${bob.uppercase()}" to pin("aa", "bb", 100, 200, 150),
                "$circle:{$cy}" to pin("cc", "dd", 100, 200),
            ), mapOf(bob.replace("-", "") to pin("aa", "bb", 90, 200))),
            blob(ann, mapOf(
                "urn:uuid:$circle:$bob" to pin("aa", "bb", 50, 300, 120),
                "$circle:$cy" to pin("ee", "ff", 80, 400, 390),
                "c-1:me" to pin("11", "22", 1, 2),
            ), mapOf(bob to pin("aa", "bb", 95, 250, 10))),
        ), ann)!!).asJsonObject

        assertEquals(ann, merged.get("owner_user_id").asString)
        assertEquals("f1", merged.get("format").asString)
        // A circle id that isn't one UUID in the SDK's spellings leaves the key as it is.
        assertEquals(setOf("$circle:$bob", "$circle:$cy", "urn:uuid:$circle:$bob", "c-1:me"), merged.getAsJsonObject("pins").keySet())
        with(merged.pin("pins", "$circle:$bob")) {
            assertEquals("aa".repeat(32), get("sharing_pubkey_hex").asString)
            assertEquals(100L, get("first_pinned_at").asLong)
            assertEquals(150L, get("user_approved_change_at").asLong)
        }
        with(merged.pin("pins", "$circle:$cy")) {
            assertEquals(MemberPinBlobs.CHANGED_UNTIL_APPROVED, get("sharing_pubkey_hex").asString)
            assertEquals(MemberPinBlobs.CHANGED_UNTIL_APPROVED, get("signing_pubkey_hex").asString)
            assertEquals(80L, get("first_pinned_at").asLong)
            assertEquals(400L, get("last_verified_at").asLong)
            assertTrue(get("user_approved_change_at").isJsonNull)
        }
        assertEquals(setOf(bob), merged.getAsJsonObject("identity_pins").keySet())
        with(merged.pin("identity_pins", bob)) {
            assertEquals("bb".repeat(32), get("signing_pubkey_hex").asString)
            assertEquals(90L, get("first_pinned_at").asLong)
            assertEquals(250L, get("last_verified_at").asLong)
            assertEquals(10L, get("user_approved_change_at").asLong)
        }
    }

    @Test
    fun `a key that differs only in case is the same key`() {
        val merged = JsonParser.parseString(MemberPinBlobs.merged(listOf(
            blob(ann, mapOf("$circle:$bob" to pin("AA", "BB", 10, 20))),
            blob(ann.uppercase(), mapOf("$circle:$bob" to pin("aa", "bb", 5, 30))),
        ), ann)!!).asJsonObject

        with(merged.pin("pins", "$circle:$bob")) {
            assertEquals("AA".repeat(32), get("sharing_pubkey_hex").asString)
            assertEquals(5L, get("first_pinned_at").asLong)
            assertEquals(30L, get("last_verified_at").asLong)
            assertTrue(get("user_approved_change_at").isJsonNull)
        }
    }
}
