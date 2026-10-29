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

package com.pingolin.track.data.remote

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pingolin.track.data.remote.dto.EncryptedBlobRequest
import com.pingolin.track.data.remote.dto.StoreSelfKeyRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * POST /users/me/self-key is an upsert, and the personal key it stores is the
 * only thing that can open a user's personal location history. A client that
 * could not READ the stored key and minted a replacement over it would make
 * every personal location row on the account permanently unreadable, because
 * the real key exists nowhere but the phone.
 *
 * The server refuses an overwrite while the stored key is protecting live data.
 * That guard needs one thing this client can give it and the payload cannot: a
 * way to tell the two writes apart. `republishSelfKey` re-uploads the key this
 * device ALREADY HOLDS, to repair a copy some other client clobbered — the cure
 * — and on the wire it is byte-for-byte the shape of the disease. `replace` is
 * that distinction, so these tests pin which requests carry it.
 *
 * Uses the same default Gson the app's Retrofit converter builds
 * (`di/AppModule.kt`: `GsonConverterFactory.create()`), including its default of
 * omitting null fields.
 */
class StoreSelfKeyRequestContractTest {

    private val gson = GsonBuilder().create()

    private fun encode(request: StoreSelfKeyRequest): JsonObject =
        JsonParser.parseString(gson.toJson(request)).asJsonObject

    private val blob = EncryptedBlobRequest(nonce = "nonce-1", ciphertext = "ciphertext-1")

    /**
     * `createAndUploadSelfKey` builds exactly this request — the two-argument form, no
     * flag. It is reached from the "no self key on server" branch, where by definition
     * we cannot know what we might be writing over, so it is precisely the call the
     * server must stay free to refuse. An explicit `"replace": false` would be just as
     * good here, but a field that is simply absent cannot be got wrong by a future
     * caller who copies this construction and forgets it.
     */
    @Test
    fun `a minting request sends no replace flag at all`() {
        val json = encode(
            StoreSelfKeyRequest(
                encryptedSelfKey = blob,
                ephemeralPublicKey = "eph-pub-1"
            )
        )
        assertFalse(
            "a mint must not claim the right to replace: the server has to be able to refuse it",
            json.has("replace")
        )
    }

    /**
     * `republishSelfKey` — the Settings action that re-publishes the key from this
     * device, and its automatic cooldown-guarded twin `republishSelfKeyIfHeld`, which
     * calls straight through to it — wraps a key it has just proved it holds.
     * Overwriting with it loses nothing and is the only way to undo another client's
     * mint, so this is the one write that says so.
     */
    @Test
    fun `a re-publish request declares replace`() {
        val json = encode(
            StoreSelfKeyRequest(
                encryptedSelfKey = blob,
                ephemeralPublicKey = "eph-pub-1",
                replace = true
            )
        )
        assertTrue("re-publish must reach the wire under the server's name", json.has("replace"))
        assertTrue(json.get("replace").asBoolean)
    }

    /**
     * The flag is additive: a server that does not know a `replace` field ignores it,
     * so adding it changes nothing for such a server. That only holds while the fields
     * the server DOES read arrive unchanged.
     */
    @Test
    fun `adding the flag leaves the rest of the payload untouched`() {
        listOf(null, true).forEach { replace ->
            val json = encode(
                StoreSelfKeyRequest(
                    encryptedSelfKey = blob,
                    ephemeralPublicKey = "eph-pub-1",
                    replace = replace
                )
            )
            val encrypted = json.getAsJsonObject("encrypted_self_key")
            assertEquals("nonce-1", encrypted.get("nonce").asString)
            assertEquals("ciphertext-1", encrypted.get("ciphertext").asString)
            assertEquals("eph-pub-1", json.get("ephemeral_public_key").asString)
        }
    }
}
